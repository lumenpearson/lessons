"""A day's mark, in ``services/``: one write for three shells.

v1's ``PUT /days`` held its rules in the router — a schedule the day names is
this class's and rings something, and a shortened day names one — while the
bot's «🏖 Особые дни» wrote through ``special_days.mark``, which asked none of
them. v2's ``UpdateDay`` writes the same marks, so one write,
``special_days.put_day``, holds the rules a fact at a time, a mark two phones
write at once (#382), and that nothing the services write is committed by
them.
"""

from __future__ import annotations

from datetime import date, datetime
from datetime import time as Time
from types import SimpleNamespace
from typing import Any

import pytest
from sqlalchemy import func, select

from app import wording
from app.bot.handlers.manage.holidays import SHORTENED_WITHOUT_BELLS, holiday_kind
from app.db import SessionLocal
from app.models import (
    AuditEntry,
    BellPeriod,
    BellSchedule,
    DayKind,
    DayOverride,
    Role,
    SchoolClass,
)
from app.services import clock
from app.services.manage import bells, special_days

MONDAY = date(2026, 9, 14)
TUESDAY = date(2026, 9, 15)
EDITOR = 42
#: 14 September, as people read it on Monday the 7th.
WHEN = "14 сентября (понедельник)"


def _pin_clock(monkeypatch) -> None:
    """Monday 7 September, 10:00 in the class's zone."""
    monkeypatch.setattr(
        clock, "now", lambda school_class: datetime(2026, 9, 7, 10, 0, tzinfo=school_class.tz)
    )


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _committed(statement: Any) -> Any:
    """What a session of its own reads: only what is committed."""
    async with SessionLocal() as fresh:
        return await fresh.scalar(statement)


async def _lines(session) -> list[tuple[str, str]]:
    rows = await session.execute(
        select(AuditEntry.action, AuditEntry.summary).order_by(AuditEntry.id)
    )
    return [tuple(row) for row in rows]


async def _short_bells(session, school_class) -> BellSchedule:
    short = BellSchedule(class_id=school_class.id, name="Сокращённое")
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


def _stale_once(monkeypatch) -> list[bool]:
    """``mark_on`` answers «no mark» once, as it truthfully did a moment
    before somebody else's mark was committed: the race, staged as
    ``test_homework_upsert.py`` stages its own."""
    real = special_days.mark_on
    stale: list[bool] = []

    async def stale_once(*args: Any, **kwargs: Any) -> Any:
        if not stale:
            stale.append(True)
            return None
        return await real(*args, **kwargs)

    monkeypatch.setattr(special_days, "mark_on", stale_once)
    return stale


async def test_a_mark_is_written_with_its_line_and_its_notice_as_v1_wrote_them(
    session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    first = await special_days.set_day(
        session,
        school_class,
        EDITOR,
        MONDAY,
        kind=DayKind.HOLIDAY,
        note="День <учителя>",
        bell_schedule_id=None,
    )
    assert (first.mark.kind, first.mark.note, first.mark.bell_schedule_id) == (
        DayKind.HOLIDAY,
        "День <учителя>",
        None,
    )
    # Escaped here, for an HTML message: «<учителя>» typed is text, not a tag.
    assert first.notice == f"📆 {WHEN} — выходной.\nДень &lt;учителя&gt;"
    remote = {"kind": DayKind.REMOTE, "note": None, "bell_schedule_id": None}
    second = await special_days.set_day(session, school_class, EDITOR, MONDAY, **remote)
    again = await special_days.set_day(session, school_class, EDITOR, MONDAY, **remote)
    assert second.mark is first.mark
    # v1 told the class of a mark whenever it was set, sent twice or not.
    assert second.notice == again.notice == f"📆 {WHEN} — дистанционное обучение."
    assert await _lines(session) == [
        ("day.set", f"{WHEN}: выходной"),
        ("day.set", f"{WHEN}: дистанционное обучение"),
        ("day.set", f"{WHEN}: дистанционное обучение"),
    ]


async def test_taking_a_mark_off_tells_only_when_there_was_one(
    session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    normal = {"kind": DayKind.NORMAL, "note": None, "bell_schedule_id": None}
    nothing = await special_days.set_day(session, school_class, EDITOR, MONDAY, **normal)
    assert (nothing.mark, nothing.notice) == (None, None)
    session.add(DayOverride(class_id=school_class.id, date=MONDAY, kind=DayKind.HOLIDAY))
    await session.commit()
    cleared = await special_days.set_day(session, school_class, EDITOR, MONDAY, **normal)
    assert (cleared.mark, cleared.notice) == (None, f"📆 {WHEN} — обычный учебный день.")
    assert await _lines(session) == [("day.clear", f"День снова обычный: {WHEN}")]
    assert await session.scalar(select(func.count()).select_from(DayOverride)) == 0


async def test_a_day_names_a_schedule_of_its_own_class_that_rings_and_a_short_day_names_one(
    session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    foreign = BellSchedule(class_id=other.id, name="Чужое")
    empty = BellSchedule(class_id=school_class.id, name="Пустое")
    session.add_all([foreign, empty])
    await session.commit()
    shortened = DayKind.SHORTENED
    for changes, refused in (
        ({"kind": shortened, "bell_schedule_id": foreign.id}, special_days.ScheduleNotInClass),
        ({"kind": shortened, "bell_schedule_id": 999_999}, special_days.ScheduleNotInClass),
        ({"kind": shortened, "bell_schedule_id": empty.id}, bells.ScheduleEmpty),
        ({"kind": shortened, "bell_schedule_id": None}, special_days.ShortenedNeedsSchedule),
        ({"kind": shortened}, special_days.ShortenedNeedsSchedule),
        # Asked of a schedule sent with any kind, as v1 asked it.
        ({"kind": DayKind.NORMAL, "bell_schedule_id": foreign.id}, special_days.ScheduleNotInClass),
    ):
        with pytest.raises(refused):
            await special_days.put_day(session, school_class, MONDAY, changes)
    assert await session.scalar(select(func.count()).select_from(DayOverride)) == 0


async def test_a_change_writes_what_it_names_and_is_told_only_when_something_changed(
    session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
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
    # Shortened with no schedule, as the bot wrote one before it learned
    # not to: a change of its note is not a change of its bells.
    session.add(DayOverride(class_id=school_class.id, date=TUESDAY, kind=DayKind.SHORTENED))
    await session.commit()

    noted = await special_days.update_day(
        session, school_class, EDITOR, MONDAY, {"note": "Педсовет"}
    )
    assert (noted.mark.kind, noted.mark.bell_schedule_id, noted.mark.note) == (
        DayKind.SHORTENED,
        short.id,
        "Педсовет",
    )
    assert noted.notice == f"📆 {WHEN} — сокращённые уроки.\nПедсовет"
    for unchanged in ({"note": "Педсовет"}, {"kind": DayKind.SHORTENED}):
        again = await special_days.update_day(session, school_class, EDITOR, MONDAY, unchanged)
        assert again.notice is None, unchanged
    legacy = await special_days.update_day(session, school_class, EDITOR, TUESDAY, {"note": "x"})
    assert (legacy.mark.bell_schedule_id, legacy.mark.note) == (None, "x")
    with pytest.raises(special_days.ShortenedNeedsSchedule):
        await special_days.update_day(
            session, school_class, EDITOR, TUESDAY, {"kind": DayKind.SHORTENED}
        )
    assert [action for action, _ in await _lines(session)] == ["day.set", "day.set"]


async def test_a_mark_somebody_else_wrote_in_the_same_instant_is_changed_not_doubled(
    session, school_class, monkeypatch
) -> None:
    """#382, at the service. The read answers «no mark» while the twin is
    already in the table; the insert meets the unique constraint inside its
    savepoint, and the write becomes the change it would have been a moment
    later, leaving the twin's note as it was."""
    session.add(DayOverride(class_id=school_class.id, date=MONDAY, kind=DayKind.REMOTE, note="их"))
    await session.commit()
    stale = _stale_once(monkeypatch)

    put = await special_days.put_day(session, school_class, MONDAY, {"kind": DayKind.HOLIDAY})
    await session.commit()

    assert stale, "the stale read never happened, so nothing was tested"
    assert (put.mark.kind, put.mark.note, put.had_mark, put.changed) == (
        DayKind.HOLIDAY,
        "их",
        True,
        True,
    )
    assert await _committed(select(func.count()).select_from(DayOverride)) == 1


async def test_two_phones_marking_one_date_at_once_both_succeed(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    """#382, through v1: the phone that lost the race met the unique constraint
    at its commit and answered 500, and the app took the mark for unsaved."""
    session.add(DayOverride(class_id=school_class.id, date=MONDAY, kind=DayKind.REMOTE))
    await session.commit()
    stale = _stale_once(monkeypatch)

    answer = await v2.http.put(
        "/api/v1/days",
        json={"date": MONDAY.isoformat(), "kind": "holiday", "note": "Актировка"},
        headers=_auth(v2_tokens["editor"]),
    )

    assert stale, "the stale read never happened, so nothing was tested"
    assert answer.status_code == 200, answer.text
    assert answer.json() == {
        "date": MONDAY.isoformat(),
        "kind": "holiday",
        "note": "Актировка",
        "bell_schedule_id": None,
    }
    assert await _committed(select(func.count()).select_from(DayOverride)) == 1


async def test_nothing_the_day_s_write_stages_is_committed_until_the_caller_commits(
    session, school_class
) -> None:
    """The insert goes through a savepoint, the first write of its transaction:
    what a rollback leaves is the code's answer on SQLite as on Postgres
    (#373)."""
    await special_days.set_day(
        session,
        school_class,
        EDITOR,
        MONDAY,
        kind=DayKind.HOLIDAY,
        note=None,
        bell_schedule_id=None,
    )
    await session.rollback()
    for table in (DayOverride, AuditEntry):
        assert await _committed(select(func.count()).select_from(table)) == 0, table


async def test_the_bot_will_not_mark_a_shortened_day_its_bells_cannot_ring(
    session, school_class, FakeCallback, FakeEditable, FakeState
) -> None:
    """The bot's starting value is the class default, written before its
    picker is asked; ``special_days.mark`` wrote it without asking whether it
    rings anything, or whether there was one. A day so marked draws nothing,
    or the normal lessons under «⏱ Сокращённые уроки». ``put_day`` asks, and
    the bot says so in an alert and writes nothing."""
    empty = BellSchedule(class_id=school_class.id, name="Пустое")
    session.add(empty)
    await session.flush()
    for default in (empty.id, None):
        school_class.bell_schedule_id = default
        await session.commit()
        callback = FakeCallback(message=FakeEditable())
        await holiday_kind(
            callback,
            SimpleNamespace(action="kind", value=f"{MONDAY.isoformat()}:shortened"),
            FakeState(),
            session,
            school_class,
            Role.EDITOR,
        )
        assert callback.answers[-1] == (SHORTENED_WITHOUT_BELLS, True), default
        assert await session.scalar(select(DayOverride)) is None
        assert await session.scalar(select(AuditEntry)) is None


def test_the_sentences_both_versions_answer_with_are_v1_s() -> None:
    assert wording.SCHEDULE_NOT_IN_CLASS_DETAIL == "bell_schedule_id is not in this class"
    assert (
        wording.SHORTENED_NEEDS_SCHEDULE_DETAIL
        == "a shortened day needs the bell schedule it rings"
    )
    assert wording.DAY_SET_LABELS == {
        DayKind.HOLIDAY: "выходной",
        DayKind.SHORTENED: "сокращённые уроки",
        DayKind.REMOTE: "дистанционное обучение",
    }
