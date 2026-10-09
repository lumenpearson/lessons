"""``EventService``'s changes: ``UpdateEvent`` and ``DeleteEvent``.

v1 could not change an event, only delete it (``DELETE /events/{id}``); v2
changes one in place, through ``events.update``, and deletes it through the
``events.delete`` v1 now calls. The mask is read once, by
``masks.update_paths`` (AIP-134): no mask changes what the request sets; a
masked place left unset is taken away, and a masked ``covers_lesson`` goes
back to what the kind means; a masked date, time or title left unset is
refused. One time moved is held against the one that stays. An event moved
to another day is announced once, on its new day (the 3b plan, «Rulings for
3b-5»); a change that changes nothing writes nothing and tells nobody. A
write's success is asked once per transport on fresh data, and its refusals
through ``both``; the database is read from sessions of their own.
"""

from __future__ import annotations

from datetime import date, datetime, time
from typing import Any

from protobuf.wkt import FieldMask
from sqlalchemy import func, select

from app import wording
from app.contract.lessons.v2.event_pb import (
    DeleteEventRequest,
    Event,
    GetEventRequest,
    UpdateEventRequest,
)
from app.db import SessionLocal
from app.models import AuditEntry, DayEvent, EventKind, SchoolClass
from app.rpc.masks import NOT_CHANGEABLE
from app.services import clock

UPDATE = "EventService/UpdateEvent"
DELETE = "EventService/DeleteEvent"
MONDAY = date(2026, 9, 14)


def _pin_clock(monkeypatch) -> None:
    """Monday 7 September, so the 14th reads as «14 сентября (понедельник)»."""
    monkeypatch.setattr(
        clock, "now", lambda school_class: datetime(2026, 9, 7, 10, 0, tzinfo=school_class.tz)
    )


async def _event(session, school_class, **fields: Any) -> DayEvent:
    row = DayEvent(
        class_id=fields.pop("class_id", school_class.id),
        date=fields.pop("date", MONDAY),
        starts_at=fields.pop("starts_at", time(12, 30)),
        ends_at=fields.pop("ends_at", time(13, 0)),
        title=fields.pop("title", "Экскурсия"),
        kind=fields.pop("kind", EventKind.TRIP),
        location=fields.pop("location", "Эрмитаж"),
        covers_lesson=fields.pop("covers_lesson", True),
    )
    session.add(row)
    await session.commit()
    return row


def _update(event_id: int, *paths: str, **fields: Any) -> UpdateEventRequest:
    mask = FieldMask(paths=list(paths)) if paths else None
    return UpdateEventRequest(event=Event(id=event_id, **fields), update_mask=mask)


async def _committed(statement: Any) -> Any:
    async with SessionLocal() as fresh:
        return await fresh.scalar(statement)


async def _columns(event_id: int) -> Any:
    async with SessionLocal() as fresh:
        rows = await fresh.execute(
            select(
                DayEvent.date,
                DayEvent.starts_at,
                DayEvent.ends_at,
                DayEvent.title,
                DayEvent.kind,
                DayEvent.location,
                DayEvent.covers_lesson,
            ).where(DayEvent.id == event_id)
        )
        return rows.one()


async def _lines() -> list[tuple[str, str]]:
    async with SessionLocal() as fresh:
        rows = await fresh.execute(
            select(AuditEntry.action, AuditEntry.summary).order_by(AuditEntry.id)
        )
        return [tuple(row) for row in rows]


async def test_an_update_without_a_mask_changes_only_what_it_sends(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    first = await _event(session, school_class)
    second = await _event(session, school_class, title="Обед", kind=EventKind.CANTEEN)
    editor = v2_tokens["editor"]
    rest = await v2.rest(UPDATE, _update(first.id, title="  Экскурсия  в музей "), token=editor)
    connect = await v2.connect(UPDATE, _update(second.id, location="Столовая"), token=editor)
    assert (rest.status, connect.status) == (200, 200)
    assert tuple(await _columns(first.id)) == (
        MONDAY,
        time(12, 30),
        time(13, 0),
        "Экскурсия в музей",
        EventKind.TRIP,
        "Эрмитаж",
        True,
    )
    assert (connect.message.event.title, connect.message.event.location) == ("Обед", "Столовая")
    when = "14 сентября (понедельник)"
    assert await _lines() == [
        ("event.update", f"Событие изменено: Экскурсия в музей, {when} 12:30–13:00"),
        ("event.update", f"Событие изменено: Обед, {when} 12:30–13:00"),
    ]


async def test_an_event_moved_to_another_day_is_announced_once_on_its_new_day(
    v2, v2_tokens, session, school_class, notices, subscribers, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    row = await _event(session, school_class)
    notices.looks = lambda: _committed(select(DayEvent.date).where(DayEvent.id == row.id))
    answer = await v2.connect(UPDATE, _update(row.id, date="2026-09-15"), token=v2_tokens["editor"])
    assert answer.status == 200
    notice = "📅 Событие изменено: <b>Экскурсия</b> 15 сентября (вторник), 12:30–13:00, Эрмитаж"
    # Once to each who asked to hear about changes but its author, naming the
    # new day, and only once the bot's own session read it.
    assert sorted(notices.sent) == [(7001, notice), (7003, notice)]
    assert notices.saw == [date(2026, 9, 15)] * 2


async def test_one_time_moved_is_held_against_the_time_that_stays(
    v2, v2_tokens, session, school_class, notices, subscribers
) -> None:
    row = await _event(session, school_class)
    editor = v2_tokens["editor"]
    for sent in (_update(row.id, starts_at="13:30"), _update(row.id, ends_at="12:00")):
        refused = await v2.both(UPDATE, sent, token=editor)
        assert (refused.status, refused.reason) == (400, "VALIDATION_FAILED")
        assert [name for name, _ in refused.violations] == ["event"]
    assert (await _columns(row.id))[1:3] == (time(12, 30), time(13, 0))
    assert notices.built == 0
    earlier = await v2.rest(UPDATE, _update(row.id, starts_at="11:00"), token=editor)
    assert (earlier.message.event.starts_at, earlier.message.event.ends_at) == ("11:00", "13:00")


async def test_a_masked_field_left_out_clears_the_place_and_gives_the_lessons_back_to_the_kind(
    v2, v2_tokens, session, school_class
) -> None:
    row = await _event(session, school_class, covers_lesson=False)
    editor = v2_tokens["editor"]
    cleared = await v2.rest(UPDATE, _update(row.id, "location", "covers_lesson"), token=editor)
    assert cleared.status == 200
    assert not cleared.message.event.has_field("location")
    # A trip stands in for the lessons it overlaps unless told otherwise.
    assert cleared.message.event.covers_lesson is True
    for path in ("date", "starts_at", "ends_at", "title"):
        refused = await v2.both(UPDATE, _update(row.id, path), token=editor)
        assert (refused.status, refused.reason) == (400, "VALIDATION_FAILED"), path
        assert [name for name, _ in refused.violations] == [f"event.{path}"]
    bounded = await v2.both(UPDATE, _update(row.id, date="2200-01-01"), token=editor)
    assert bounded.violations == [("event.date", clock.DATE_OUT_OF_BOUNDS)]
    columns = await _columns(row.id)
    assert (columns.title, columns.location, columns.covers_lesson) == ("Экскурсия", None, True)


async def test_an_update_that_changes_nothing_writes_nothing_and_tells_nobody(
    v2, v2_tokens, session, school_class, notices, subscribers, statement_writes
) -> None:
    """A retried update, or one that sends what is there: no line in the
    journal and no second notice. The call before it touched the phone's last
    call, so inside the fifteen minutes there is nothing else it may write."""
    row = await _event(session, school_class)
    editor = v2_tokens["editor"]
    await v2.rest("EventService/GetEvent", GetEventRequest(event_id=row.id), token=editor)
    with statement_writes() as seen:
        answer = await v2.both(
            UPDATE, _update(row.id, title="Экскурсия", location=" Эрмитаж "), token=editor
        )
    assert (answer.status, answer.message.event.location) == (200, "Эрмитаж")
    assert seen == []
    assert notices.built == 0


async def test_deleting_an_event_announces_it_and_again_finds_nothing(
    v2, v2_tokens, session, school_class, notices, subscribers, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    first = await _event(session, school_class)
    second = await _event(session, school_class, title="Обед <3", kind=EventKind.CANTEEN)
    editor = v2_tokens["editor"]
    rest = await v2.rest(DELETE, DeleteEventRequest(event_id=first.id), token=editor)
    connect = await v2.connect(DELETE, DeleteEventRequest(event_id=second.id), token=editor)
    assert (rest.status, rest.body, connect.status) == (200, b"{}", 200)
    assert await _committed(select(func.count()).select_from(DayEvent)) == 0
    when = "14 сентября (понедельник)"
    assert await _lines() == [
        ("event.delete", f"Событие удалено: Экскурсия, {when}"),
        ("event.delete", f"Событие удалено: Обед <3, {when}"),
    ]
    assert sorted(notices.sent) == [
        (7001, f"🗑 Событие отменено: <b>Обед &lt;3</b> {when}"),
        (7001, f"🗑 Событие отменено: <b>Экскурсия</b> {when}"),
        (7003, f"🗑 Событие отменено: <b>Обед &lt;3</b> {when}"),
        (7003, f"🗑 Событие отменено: <b>Экскурсия</b> {when}"),
    ]
    again = await v2.both(DELETE, DeleteEventRequest(event_id=first.id), token=editor)
    assert (again.status, again.reason, again.error) == (
        404,
        "RESOURCE_NOT_FOUND",
        wording.UNKNOWN_EVENT_DETAIL,
    )
    assert notices.built == 2


async def test_an_event_of_another_class_can_be_neither_changed_nor_deleted(
    v2, v2_tokens, session, school_class, notices, subscribers
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    foreign = await _event(session, school_class, class_id=other.id)
    for name, request in (
        (UPDATE, _update(foreign.id, title="Моё")),
        (DELETE, DeleteEventRequest(event_id=foreign.id)),
    ):
        answer = await v2.both(name, request, token=v2_tokens["editor"])
        assert (answer.status, answer.reason, answer.metadata) == (
            404,
            "RESOURCE_NOT_FOUND",
            {"resource": "event"},
        ), name
        assert answer.error == wording.UNKNOWN_EVENT_DETAIL
    assert (await _columns(foreign.id)).title == "Экскурсия"
    assert notices.built == 0


async def test_a_mask_naming_a_field_the_method_does_not_change_is_refused_on_it(
    v2, v2_tokens, session, school_class
) -> None:
    row = await _event(session, school_class)
    answer = await v2.both(UPDATE, _update(row.id, "id", title="x"), token=v2_tokens["editor"])
    assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED")
    assert answer.violations == [("update_mask", NOT_CHANGEABLE)]
    assert (await _columns(row.id)).title == "Экскурсия"
