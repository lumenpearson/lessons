"""``EventService``'s reads and ``CreateEvent``.

v1 had no list of events, only ``PUT /events``, which always inserted, and
the days of ``/bundle``; v2 lists them in the window ``ListHomework`` has, and
creates them through the same ``events.create`` v1's ``PUT`` now calls: the
same cleaning by v1's ``EventIn``, the same default for what an event stands
in for, the same line in the journal and the same notice, which goes out once
the event is committed, never on a refusal and never to its author. Every
method is an editor's, the reads included. A write's success is asked once
per transport on fresh data, and its refusals through ``both``; the database
is read from sessions of their own.
"""

from __future__ import annotations

from datetime import date, datetime, time
from typing import Any

from sqlalchemy import func, select

from app import wording
from app.contract.lessons.v2.common_pb import EventKind as ProtoKind
from app.contract.lessons.v2.event_pb import (
    CreateEventRequest,
    Event,
    GetEventRequest,
    ListEventsRequest,
)
from app.db import SessionLocal
from app.models import AuditEntry, DayEvent, EventKind, SchoolClass
from app.rpc.event import KIND_REFUSED
from app.services import clock

CREATE = "EventService/CreateEvent"
LIST = "EventService/ListEvents"
GET = "EventService/GetEvent"
DAY = "2026-09-14"
#: 14 September, as people read it on Monday the 7th.
WHEN = "14 сентября (понедельник)"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _pin_clock(monkeypatch) -> None:
    monkeypatch.setattr(
        clock, "now", lambda school_class: datetime(2026, 9, 7, 10, 0, tzinfo=school_class.tz)
    )


def _create(title: str, starts_at: str = "12:30", ends_at: str = "13:00", **fields: Any):
    fields.setdefault("date", DAY)
    return CreateEventRequest(
        event=Event(title=title, starts_at=starts_at, ends_at=ends_at, **fields)
    )


async def _committed(statement: Any) -> Any:
    async with SessionLocal() as fresh:
        return await fresh.scalar(statement)


async def _stored() -> list[tuple[Any, ...]]:
    async with SessionLocal() as fresh:
        rows = await fresh.execute(
            select(DayEvent.title, DayEvent.kind, DayEvent.covers_lesson).order_by(DayEvent.id)
        )
        return [tuple(row) for row in rows]


async def test_the_events_are_v1_s_in_v2_s_shape(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    editor = v2_tokens["editor"]
    made = {}
    for day, starts, ends, title, kind, place in (
        (DAY, "12:30", "13:00", "Экскурсия", "trip", "Эрмитаж"),
        ("2026-09-08", "11:10", "11:25", "Обед", "canteen", None),
        ("2026-09-08", "09:00", "09:15", "Линейка", "event", None),
        ("2026-10-20", "10:00", "11:00", "После окна", "exam", None),
    ):
        body = {"date": day, "starts_at": starts, "ends_at": ends, "title": title, "kind": kind}
        if place:
            body["location"] = place
        created = await v2.http.put("/api/v1/events", json=body, headers=_auth(editor))
        assert created.status_code == 201, created.text
        made[title] = created.json()["id"]
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    session.add(
        DayEvent(
            class_id=other.id,
            date=date(2026, 9, 8),
            starts_at=time(10, 0),
            ends_at=time(10, 30),
            title="Чужое",
            kind=EventKind.EVENT,
        )
    )
    await session.commit()

    rows = (await v2.both(LIST, token=editor)).message.events
    # Today and three weeks on, by date and then the time it starts; this
    # class's alone.
    assert [row.title for row in rows] == ["Линейка", "Обед", "Экскурсия"]
    assert [row.id for row in rows] == [made["Линейка"], made["Обед"], made["Экскурсия"]]
    lunch, trip = rows[1], rows[2]
    assert (lunch.date, lunch.starts_at, lunch.ends_at, lunch.kind, lunch.covers_lesson) == (
        "2026-09-08",
        "11:10",
        "11:25",
        ProtoKind.CANTEEN,
        False,
    )
    assert not lunch.has_field("location")
    assert (trip.kind, trip.covers_lesson, trip.location) == (ProtoKind.TRIP, True, "Эрмитаж")
    one = await v2.both(GET, GetEventRequest(event_id=made["Экскурсия"]), token=editor)
    assert one.message.event == trip
    wide = await v2.both(
        LIST, ListEventsRequest(start_date="2026-09-01", end_date="2026-10-31"), token=editor
    )
    assert [row.title for row in wide.message.events][-1] == "После окна"


async def test_a_window_of_events_is_refused_as_the_homework_s_is(v2, v2_tokens) -> None:
    answer = await v2.both(
        LIST, ListEventsRequest(start_date="1999-01-01"), token=v2_tokens["editor"]
    )
    assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED")
    assert answer.violations == [("start_date", clock.DATES_OUT_OF_BOUNDS)]


async def test_an_event_of_another_class_or_none_is_not_found(v2, v2_tokens, session) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    foreign = DayEvent(
        class_id=other.id,
        date=date(2026, 9, 14),
        starts_at=time(10, 0),
        ends_at=time(10, 30),
        title="Чужое",
        kind=EventKind.EVENT,
    )
    session.add(foreign)
    await session.commit()
    editor = v2_tokens["editor"]
    v1 = await v2.http.delete(f"/api/v1/events/{foreign.id}", headers=_auth(editor))
    assert v1.status_code == 404
    for event_id in (foreign.id, 999_999):
        answer = await v2.both(GET, GetEventRequest(event_id=event_id), token=editor)
        assert (answer.status, answer.code, answer.reason, answer.metadata) == (
            404,
            "NOT_FOUND",
            "RESOURCE_NOT_FOUND",
            {"resource": "event"},
        )
        assert answer.error == v1.json()["detail"] == wording.UNKNOWN_EVENT_DETAIL


async def test_reading_events_writes_nothing_but_the_last_seen(
    v2, v2_tokens, session, school_class, statement_writes, unexpected_writes, last_seen_rule
) -> None:
    row = DayEvent(
        class_id=school_class.id,
        date=date(2026, 9, 14),
        starts_at=time(10, 0),
        ends_at=time(10, 30),
        title="Линейка",
        kind=EventKind.EVENT,
    )
    session.add(row)
    await session.commit()
    editor = v2_tokens["editor"]
    with statement_writes() as seen:
        listed = await v2.both(LIST, token=editor)
        one = await v2.both(GET, GetEventRequest(event_id=row.id), token=editor)
    assert (listed.status, one.message.event.id) == (200, row.id)
    # A write happened, the phone's last call, and nothing else did.
    assert seen
    assert unexpected_writes(seen) == []
    assert all(last_seen_rule.match(statement) for statement in seen), seen


async def test_a_new_event_answers_201_and_stands_in_for_lessons_as_its_kind_means(
    v2, v2_tokens, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    editor = v2_tokens["editor"]
    trip = await v2.rest(
        CREATE, _create("  Экскурсия  в  музей ", kind=ProtoKind.TRIP, id=777), token=editor
    )
    assert trip.status == 201
    made = trip.message.event
    # One line, single-spaced; the id is the server's.
    assert (made.title, made.kind, made.covers_lesson) == (
        "Экскурсия в музей",
        ProtoKind.TRIP,
        True,
    )
    assert made.id != 777
    for request in (
        _create("Обед", "11:10", "11:25", kind=ProtoKind.CANTEEN),
        # Two «Обед» on one day are two breaks.
        _create("Обед", "11:10", "11:25", kind=ProtoKind.CANTEEN),
        _create("Поход", kind=ProtoKind.TRIP, covers_lesson=False),
        # No kind is an event, as v1 defaulted.
        _create("Концерт"),
    ):
        assert (await v2.connect(CREATE, request, token=editor)).status == 200
    assert await _stored() == [
        ("Экскурсия в музей", EventKind.TRIP, True),
        ("Обед", EventKind.CANTEEN, False),
        ("Обед", EventKind.CANTEEN, False),
        ("Поход", EventKind.TRIP, False),
        ("Концерт", EventKind.EVENT, True),
    ]
    async with SessionLocal() as fresh:
        first = await fresh.scalar(select(AuditEntry.summary).order_by(AuditEntry.id))
    assert first == f"Событие: Экскурсия в музей, {WHEN} 12:30–13:00"


async def test_a_new_event_is_announced_after_the_commit_in_v1_s_words(
    v2, v2_tokens, notices, subscribers, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    editor = v2_tokens["editor"]
    notices.looks = lambda: _committed(select(func.count()).select_from(DayEvent))
    answer = await v2.connect(
        CREATE, _create("Экскурсия", kind=ProtoKind.TRIP, location="<Эрмитаж>"), token=editor
    )
    assert answer.status == 200
    notice = f"📅 Событие: <b>Экскурсия</b> {WHEN}, 12:30–13:00, &lt;Эрмитаж&gt;"
    # To those who asked to hear about changes but its author, once a session
    # of the bot's own could read the event.
    assert sorted(notices.sent) == [(7001, notice), (7003, notice)]
    assert notices.saw == [1, 1]
    # v1's PUT of the same event says the same words.
    v1 = await v2.http.put(
        "/api/v1/events",
        json={
            "date": DAY,
            "starts_at": "12:30",
            "ends_at": "13:00",
            "title": "Экскурсия",
            "kind": "trip",
            "location": "<Эрмитаж>",
        },
        headers=_auth(editor),
    )
    assert v1.status_code == 201
    assert [text for _, text in notices.sent[2:]] == [notice, notice]


async def test_an_event_v1_would_refuse_is_refused_on_its_field_and_tells_nobody(
    v2, v2_tokens, notices, subscribers
) -> None:
    editor = v2_tokens["editor"]
    v1 = await v2.http.put(
        "/api/v1/events",
        json={"date": DAY, "starts_at": "13:00", "ends_at": "12:30", "title": "x"},
        headers=_auth(editor),
    )
    assert v1.status_code == 422
    for request, field_name in (
        (_create("x", "13:00", "12:30"), "event"),
        (_create("x", date="2200-01-01"), "event.date"),
        (_create("   "), "event.title"),
        (_create("x", location="м" * 121), "event.location"),
        (_create("x", "25:00"), "event.starts_at"),
    ):
        answer = await v2.both(CREATE, request, token=editor)
        assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED"), field_name
        assert [name for name, _ in answer.violations] == [field_name]
    assert await _committed(select(func.count()).select_from(DayEvent)) == 0
    assert notices.built == 0


async def test_a_kind_no_value_names_is_refused_on_its_field(v2, v2_tokens) -> None:
    """A number ``EventKind`` does not name reaches the handler in the binary
    encoding, which keeps it. Read as JSON it never arrives: both transports
    ignore unknown values, and the request reads as naming no kind."""
    answer = await v2.connect(
        CREATE, _create("x", kind=ProtoKind(99)), token=v2_tokens["editor"], binary=True
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "INVALID_ARGUMENT",
        "VALIDATION_FAILED",
    )
    assert answer.violations == [("event.kind", KIND_REFUSED)]
    assert await _committed(select(func.count()).select_from(DayEvent)) == 0
