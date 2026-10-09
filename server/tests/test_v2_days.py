"""``DayService``: ``GetDay`` and ``UpdateDay``.

v1's ``PUT /days`` over v2, through ``special_days.put_day``: the same kinds,
the same checks in v1's words, the same line in the journal and the same
notice to the class. A date nobody marked is an ordinary day. ``UpdateDay``
changes a mark, and with ``allow_missing`` makes one (AIP-134), where taking
off a mark the date does not have writes nothing and tells nobody (the 3b
plan, «Rulings for 3b-6»). v2 writes only the four kinds v1 wrote. A write's
success is asked once per transport on fresh data, and its refusals through
``both``; the database is read from sessions of their own, which see only
what is committed.
"""

from __future__ import annotations

from datetime import date, datetime
from datetime import time as Time
from typing import Any

from protobuf.wkt import FieldMask
from sqlalchemy import select

from app import wording
from app.contract.lessons.v2.common_pb import DayKind as ProtoKind
from app.contract.lessons.v2.day_pb import Day, GetDayRequest, UpdateDayRequest
from app.db import SessionLocal
from app.models import AuditEntry, BellPeriod, BellSchedule, DayKind, DayOverride, SchoolClass
from app.rpc.day import DAY_NOT_MARKED
from app.rpc.masks import NOT_CHANGEABLE
from app.services import clock

GET = "DayService/GetDay"
UPDATE = "DayService/UpdateDay"
MONDAY = date(2026, 9, 14)
TUESDAY = date(2026, 9, 15)
#: 14 September, as people read it on Monday the 7th.
WHEN = "14 сентября (понедельник)"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _pin_clock(monkeypatch) -> None:
    """Monday 7 September, 10:00 in the class's zone, for v1 and v2 alike."""
    monkeypatch.setattr(
        clock, "now", lambda school_class: datetime(2026, 9, 7, 10, 0, tzinfo=school_class.tz)
    )


def _update(day: date, *paths: str, allow_missing: bool = False, **fields: Any):
    mask = FieldMask(paths=list(paths)) if paths else None
    return UpdateDayRequest(
        day=Day(date=day.isoformat(), **fields), update_mask=mask, allow_missing=allow_missing
    )


async def _committed(statement: Any) -> Any:
    async with SessionLocal() as fresh:
        return await fresh.scalar(statement)


async def _marks() -> list[tuple[Any, ...]]:
    async with SessionLocal() as fresh:
        rows = await fresh.execute(
            select(
                DayOverride.date, DayOverride.kind, DayOverride.note, DayOverride.bell_schedule_id
            ).order_by(DayOverride.date)
        )
        return [tuple(row) for row in rows]


async def _lines() -> list[tuple[str, str]]:
    async with SessionLocal() as fresh:
        rows = await fresh.execute(
            select(AuditEntry.action, AuditEntry.summary).order_by(AuditEntry.id)
        )
        return [tuple(row) for row in rows]


async def _short_bells(session, school_class, name: str = "Сокращённое") -> BellSchedule:
    short = BellSchedule(class_id=school_class.id, name=name)
    session.add(short)
    await session.flush()
    for index in (1, 2, 3):
        session.add(
            BellPeriod(
                schedule_id=short.id,
                index=index,
                starts_at=Time(8 + index, 0),
                ends_at=Time(8 + index, 30),
            )
        )
    await session.commit()
    return short


async def test_a_date_nobody_marked_is_an_ordinary_day_and_a_marked_one_is_its_mark(
    v2, v2_tokens, session, school_class
) -> None:
    session.add_all(
        [
            DayOverride(
                class_id=school_class.id, date=MONDAY, kind=DayKind.HOLIDAY, note="Каникулы"
            ),
            DayOverride(class_id=school_class.id, date=TUESDAY, kind=DayKind.SELF_STUDY),
        ]
    )
    await session.commit()
    editor = v2_tokens["editor"]

    marked = (await v2.both(GET, GetDayRequest(date="2026-09-14"), token=editor)).message.day
    assert (marked.date, marked.kind, marked.note) == ("2026-09-14", ProtoKind.HOLIDAY, "Каникулы")
    assert not marked.has_field("bell_schedule_id")
    # The bot's kinds are read here, as the bundle reads them.
    studying = (await v2.both(GET, GetDayRequest(date="2026-09-15"), token=editor)).message.day
    assert studying.kind == ProtoKind.SELF_STUDY
    ordinary = (await v2.both(GET, GetDayRequest(date="2026-09-16"), token=editor)).message.day
    assert (ordinary.kind, ordinary.has_field("note")) == (ProtoKind.NORMAL, False)
    v1 = await v2.http.get(
        "/api/v1/bundle", params={"start": "2026-09-14", "days": 3}, headers=_auth(editor)
    )
    assert [day["kind"] for day in v1.json()["days"]] == ["holiday", "self_study", "normal"]


async def test_a_date_that_does_not_parse_or_is_out_of_bounds_is_refused_on_its_field(
    v2, v2_tokens
) -> None:
    editor = v2_tokens["editor"]
    unparsed = await v2.both(GET, GetDayRequest(date="14.09.2026"), token=editor)
    assert (unparsed.status, unparsed.reason) == (400, "VALIDATION_FAILED")
    assert [name for name, _ in unparsed.violations] == ["date"]
    for name, request, field_name in (
        (GET, GetDayRequest(date="2200-01-01"), "date"),
        (UPDATE, _update(date(2200, 1, 1), allow_missing=True, kind=ProtoKind.HOLIDAY), "day.date"),
    ):
        answer = await v2.both(name, request, token=editor)
        assert (answer.status, answer.reason, answer.error) == (
            400,
            "VALIDATION_FAILED",
            clock.DATE_OUT_OF_BOUNDS,
        ), name
        assert answer.violations == [(field_name, clock.DATE_OUT_OF_BOUNDS)]


async def test_marking_a_day_answers_its_mark_and_is_v1_s_to_read(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    short = await _short_bells(session, school_class)
    editor = v2_tokens["editor"]
    rest = await v2.rest(
        UPDATE,
        _update(MONDAY, allow_missing=True, kind=ProtoKind.HOLIDAY, note="  День  учителя "),
        token=editor,
    )
    connect = await v2.connect(
        UPDATE,
        _update(TUESDAY, allow_missing=True, kind=ProtoKind.SHORTENED, bell_schedule_id=short.id),
        token=editor,
    )
    assert (rest.status, connect.status) == (200, 200)
    # Cleaned as v1's DayIn cleans a note: one line, single-spaced.
    assert (rest.message.day.kind, rest.message.day.note) == (ProtoKind.HOLIDAY, "День учителя")
    assert connect.message.day.bell_schedule_id == short.id
    assert await _marks() == [
        (MONDAY, DayKind.HOLIDAY, "День учителя", None),
        (TUESDAY, DayKind.SHORTENED, None, short.id),
    ]
    v1 = await v2.http.get(
        "/api/v1/bundle", params={"start": "2026-09-14", "days": 2}, headers=_auth(editor)
    )
    assert [(day["kind"], day["note"]) for day in v1.json()["days"]] == [
        ("holiday", "День учителя"),
        ("shortened", None),
    ]
    assert await _lines() == [
        ("day.set", f"{WHEN}: выходной"),
        ("day.set", "15 сентября (вторник): сокращённые уроки"),
    ]


async def test_a_mark_is_announced_after_the_commit_and_not_to_its_author(
    v2, v2_tokens, notices, subscribers, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    notices.looks = lambda: _committed(select(DayOverride.kind).where(DayOverride.date == MONDAY))
    answer = await v2.connect(
        UPDATE,
        _update(MONDAY, allow_missing=True, kind=ProtoKind.HOLIDAY, note="День <учителя>"),
        token=v2_tokens["editor"],
    )
    assert answer.status == 200
    notice = f"📆 {WHEN} — выходной.\nДень &lt;учителя&gt;"
    # v1's words, to those who asked to hear about changes but its author,
    # and only once a session of the bot's own could read the mark.
    assert sorted(notices.sent) == [(7001, notice), (7003, notice)]
    assert notices.saw == [DayKind.HOLIDAY, DayKind.HOLIDAY]
    assert notices.closed == notices.built == 1


async def test_without_allow_missing_a_date_nobody_marked_is_not_found(
    v2, v2_tokens, notices, subscribers
) -> None:
    for kind in (ProtoKind.HOLIDAY, ProtoKind.NORMAL):
        answer = await v2.both(UPDATE, _update(MONDAY, kind=kind), token=v2_tokens["editor"])
        assert (answer.status, answer.code, answer.reason, answer.metadata) == (
            404,
            "NOT_FOUND",
            "RESOURCE_NOT_FOUND",
            {"resource": "day"},
        ), kind
        assert answer.error == DAY_NOT_MARKED
    assert await _marks() == []
    assert notices.built == 0


async def test_taking_off_a_mark_a_date_does_not_have_writes_nothing_and_tells_nobody(
    v2, v2_tokens, notices, subscribers, statement_writes
) -> None:
    """``allow_missing`` and ``DAY_KIND_NORMAL`` on a date nobody marked: a
    success that writes nothing, its line and its notice included. The call
    before it touched the phone's last call, so inside the fifteen minutes
    there is nothing else it may write."""
    editor = v2_tokens["editor"]
    await v2.rest(GET, GetDayRequest(date="2026-09-14"), token=editor)
    with statement_writes() as seen:
        answer = await v2.both(
            UPDATE, _update(MONDAY, allow_missing=True, kind=ProtoKind.NORMAL), token=editor
        )
    assert (answer.status, answer.message.day.kind) == (200, ProtoKind.NORMAL)
    assert seen == []
    assert notices.built == 0


async def test_taking_a_mark_off_is_announced_once_and_leaves_an_ordinary_day(
    v2, v2_tokens, session, school_class, notices, subscribers, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    session.add(DayOverride(class_id=school_class.id, date=MONDAY, kind=DayKind.REMOTE))
    await session.commit()
    editor = v2_tokens["editor"]
    taken = await v2.rest(UPDATE, _update(MONDAY, kind=ProtoKind.NORMAL), token=editor)
    assert (taken.status, taken.message.day.kind) == (200, ProtoKind.NORMAL)
    assert await _marks() == []
    assert await _lines() == [("day.clear", f"День снова обычный: {WHEN}")]
    notice = f"📆 {WHEN} — обычный учебный день."
    assert sorted(notices.sent) == [(7001, notice), (7003, notice)]
    # Asked again without allow_missing, there is no mark left to change.
    again = await v2.connect(UPDATE, _update(MONDAY, kind=ProtoKind.NORMAL), token=editor)
    assert (again.status, again.reason) == (404, "RESOURCE_NOT_FOUND")
    assert notices.built == 1


async def test_a_kind_v2_does_not_write_is_refused_on_its_field(
    v2, v2_tokens, session, school_class, notices, subscribers
) -> None:
    session.add(DayOverride(class_id=school_class.id, date=TUESDAY, kind=DayKind.SELF_STUDY))
    await session.commit()
    editor = v2_tokens["editor"]
    for request in (
        _update(MONDAY, allow_missing=True, kind=ProtoKind.SELF_STUDY),
        _update(MONDAY, allow_missing=True, kind=ProtoKind.DAY_OFF),
        # A new mark needs its kind, and a masked kind left unset is none.
        _update(MONDAY, allow_missing=True, note="Педсовет"),
        _update(MONDAY, "kind", allow_missing=True),
        # The bot's self-study day is changed here only into a kind v2 writes.
        _update(TUESDAY, note="Педсовет"),
    ):
        answer = await v2.both(UPDATE, request, token=editor)
        assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED"), request
        assert [name for name, _ in answer.violations] == ["day.kind"]
    # A number DayKind does not name arrives in the binary encoding only.
    unknown = await v2.connect(
        UPDATE,
        _update(MONDAY, allow_missing=True, kind=ProtoKind(99)),
        token=editor,
        binary=True,
    )
    assert (unknown.reason, [name for name, _ in unknown.violations]) == (
        "VALIDATION_FAILED",
        ["day.kind"],
    )
    assert await _marks() == [(TUESDAY, DayKind.SELF_STUDY, None, None)]
    assert notices.built == 0


async def test_a_shortened_day_rings_a_schedule_of_its_own_class_that_rings_something(
    v2, v2_tokens, session, school_class, notices, subscribers
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    foreign = BellSchedule(class_id=other.id, name="Чужое")
    empty = BellSchedule(class_id=school_class.id, name="Пустое")
    session.add_all([foreign, empty])
    await session.commit()
    editor = v2_tokens["editor"]
    for schedule_id, sentence in (
        (foreign.id, wording.SCHEDULE_NOT_IN_CLASS_DETAIL),
        (empty.id, wording.EMPTY_BELL_SCHEDULE_DETAIL),
        (None, wording.SHORTENED_NEEDS_SCHEDULE_DETAIL),
    ):
        fields: dict[str, Any] = {"kind": ProtoKind.SHORTENED}
        if schedule_id is not None:
            fields["bell_schedule_id"] = schedule_id
        answer = await v2.both(UPDATE, _update(MONDAY, allow_missing=True, **fields), token=editor)
        assert answer.status == 400 and answer.error == sentence, sentence
        if schedule_id == empty.id:
            assert (answer.code, answer.reason) == ("FAILED_PRECONDITION", "EMPTY_BELL_SCHEDULE")
        else:
            assert (answer.reason, answer.violations) == (
                "VALIDATION_FAILED",
                [("day.bell_schedule_id", sentence)],
            )
        # v1 refuses the same day in the same words.
        v1 = await v2.http.put(
            "/api/v1/days",
            json={"date": "2026-09-14", "kind": "shortened", "bell_schedule_id": schedule_id},
            headers=_auth(editor),
        )
        assert (v1.status_code, v1.json()["detail"]) == (422, sentence)
    assert await _marks() == []
    assert notices.built == 0


async def test_an_update_changes_only_what_it_names(v2, v2_tokens, session, school_class) -> None:
    short = await _short_bells(session, school_class)
    session.add(
        DayOverride(
            class_id=school_class.id,
            date=MONDAY,
            kind=DayKind.SHORTENED,
            bell_schedule_id=short.id,
            note="старое",
        )
    )
    await session.commit()
    editor = v2_tokens["editor"]
    unmasked = await v2.rest(UPDATE, _update(MONDAY, note="Педсовет"), token=editor)
    assert unmasked.status == 200
    assert await _marks() == [(MONDAY, DayKind.SHORTENED, "Педсовет", short.id)]
    cleared = await v2.connect(UPDATE, _update(MONDAY, "note"), token=editor)
    assert (cleared.status, cleared.message.day.has_field("note")) == (200, False)
    assert await _marks() == [(MONDAY, DayKind.SHORTENED, None, short.id)]
    # A shortened day's schedule cannot be cleared: it would ring the default.
    unrung = await v2.both(UPDATE, _update(MONDAY, "bell_schedule_id"), token=editor)
    assert unrung.violations == [("day.bell_schedule_id", wording.SHORTENED_NEEDS_SCHEDULE_DETAIL)]
    renamed = await v2.both(UPDATE, _update(MONDAY, "date"), token=editor)
    assert renamed.violations == [("update_mask", NOT_CHANGEABLE)]
    assert await _marks() == [(MONDAY, DayKind.SHORTENED, None, short.id)]


async def test_an_update_that_changes_nothing_writes_nothing_and_tells_nobody(
    v2, v2_tokens, session, school_class, notices, subscribers, statement_writes
) -> None:
    """A retried update, or one that sends what is there: no line in the
    journal and no second notice."""
    session.add(
        DayOverride(class_id=school_class.id, date=MONDAY, kind=DayKind.HOLIDAY, note="Каникулы")
    )
    await session.commit()
    editor = v2_tokens["editor"]
    await v2.rest(GET, GetDayRequest(date="2026-09-14"), token=editor)
    with statement_writes() as seen:
        same = await v2.both(
            UPDATE, _update(MONDAY, kind=ProtoKind.HOLIDAY, note=" Каникулы "), token=editor
        )
        nothing = await v2.both(UPDATE, _update(MONDAY), token=editor)
    assert (same.status, same.message.day.note) == (200, "Каникулы")
    assert nothing.message.day.kind == ProtoKind.HOLIDAY
    assert seen == []
    assert notices.built == 0


async def test_reading_a_day_writes_nothing_but_the_last_seen(
    v2, v2_tokens, session, school_class, statement_writes, unexpected_writes, last_seen_rule
) -> None:
    session.add(DayOverride(class_id=school_class.id, date=MONDAY, kind=DayKind.HOLIDAY))
    await session.commit()
    with statement_writes() as seen:
        marked = await v2.both(GET, GetDayRequest(date="2026-09-14"), token=v2_tokens["editor"])
        ordinary = await v2.both(GET, GetDayRequest(date="2026-09-16"), token=v2_tokens["admin"])
    assert (marked.message.day.kind, ordinary.message.day.kind) == (
        ProtoKind.HOLIDAY,
        ProtoKind.NORMAL,
    )
    # A write happened, each phone's last call, and nothing else did.
    assert seen
    assert unexpected_writes(seen) == []
    assert all(last_seen_rule.match(statement) for statement in seen), seen
