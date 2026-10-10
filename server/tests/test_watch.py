"""The class-changed bus (``app/watch.py``): what wakes a class's watchers, and when.

Asked of real sessions over the suite's SQLite, with the listener attached for
the test alone: the suite runs with streaming off, as every deployment but a
streaming host does (``docs/specs/2026-10-05-server-v2-design.md``,
decision 13). A test reads ``changes`` before and after, never a revision's
text: a revision is opaque to every client, and to these tests too.
"""

from __future__ import annotations

from datetime import date, time
from typing import Any

import pytest
from sqlalchemy import select
from sqlalchemy import update as sa_update

from app import watch
from app.db import SessionLocal
from app.models import (
    AuditEntry,
    BellPeriod,
    BellSchedule,
    BotUser,
    DayEvent,
    DayKind,
    DayOverride,
    EventKind,
    Homework,
    LessonOverride,
    OverrideAction,
    PersonalTask,
    ReminderSettings,
    Role,
    SchoolClass,
    Subject,
    Term,
    TermKind,
    TimetableEntry,
    WeekParity,
)


@pytest.fixture
def bus():
    """The listener, attached for one test."""
    already = watch.listening()
    watch.attach()
    yield watch
    if not already:
        watch.detach()


def _homework(class_id: int, text: str = "№ 1–5") -> Homework:
    return Homework(
        class_id=class_id, due_date=date(2026, 9, 15), subject_name="Алгебра", text=text
    )


def _row(table: str, class_id: int) -> Any:
    """A new row of ``table`` in ``class_id``, as little as the table needs."""
    return {
        "bot_users": lambda: BotUser(telegram_id=3001, class_id=class_id, role=Role.EDITOR),
        "bell_schedules": lambda: BellSchedule(class_id=class_id, name="Сокращённое"),
        "subjects": lambda: Subject(class_id=class_id, name="Химия"),
        "timetable_entries": lambda: TimetableEntry(
            class_id=class_id, weekday=2, index=1, subject_name="Химия", parity=WeekParity.ANY
        ),
        "terms": lambda: Term(
            class_id=class_id,
            year=2026,
            kind=TermKind.QUARTER,
            index=1,
            starts_on=date(2026, 9, 1),
            ends_on=date(2026, 10, 26),
        ),
        "day_overrides": lambda: DayOverride(
            class_id=class_id, date=date(2026, 11, 4), kind=DayKind.HOLIDAY
        ),
        "lesson_overrides": lambda: LessonOverride(
            class_id=class_id, date=date(2026, 9, 14), index=1, action=OverrideAction.CANCEL
        ),
        "day_events": lambda: DayEvent(
            class_id=class_id,
            date=date(2026, 9, 15),
            starts_at=time(15),
            ends_at=time(16),
            title="Родительское собрание",
            kind=EventKind.MEETING,
        ),
        "homework": lambda: _homework(class_id),
    }[table]()


#: The window tables a plain row of which is a row of its class; the class
#: itself and a bell period are asked on their own below.
ROWS = sorted(watch.WINDOW_TABLES - {"classes", "bell_periods"})


def test_every_window_table_is_asked_here() -> None:
    assert {*ROWS, "classes", "bell_periods"} == watch.WINDOW_TABLES


@pytest.mark.parametrize("table", ROWS)
async def test_a_committed_insert_into_a_window_table_wakes_its_class(
    bus, session, school_class, table
) -> None:
    start = bus.changes(school_class.id)
    session.add(_row(table, school_class.id))
    await session.commit()
    assert bus.changes(school_class.id) == start + 1


async def test_an_update_and_a_delete_wake_it_and_a_write_that_changes_nothing_does_not(
    bus, session, school_class
) -> None:
    entry = await session.scalar(
        select(TimetableEntry).where(
            TimetableEntry.class_id == school_class.id, TimetableEntry.index == 1
        )
    )
    start = bus.changes(school_class.id)
    entry.room = "101"
    await session.commit()
    entry.room = "101"
    await session.commit()
    await session.delete(entry)
    await session.commit()
    assert bus.changes(school_class.id) == start + 2


async def test_the_class_row_wakes_its_own_class(bus, session, school_class) -> None:
    start = bus.changes(school_class.id)
    school_class.name = "9Б"
    await session.commit()
    assert bus.changes(school_class.id) == start + 1


async def test_a_bell_period_wakes_the_class_of_its_schedule(bus, school_class) -> None:
    """In a session that has not read the schedule: the bus reads it to find
    the class, and a period added by id, one changed and one deleted each
    wake it once."""
    class_id, schedule_id = school_class.id, school_class.bell_schedule_id
    start = bus.changes(class_id)
    async with SessionLocal() as fresh:
        period = await fresh.scalar(
            select(BellPeriod).where(BellPeriod.schedule_id == schedule_id, BellPeriod.index == 1)
        )
        period.ends_at = time(9, 10)
        await fresh.commit()
        fresh.add(
            BellPeriod(schedule_id=schedule_id, index=9, starts_at=time(16), ends_at=time(16, 45))
        )
        await fresh.commit()
        await fresh.delete(period)
        await fresh.commit()
    assert bus.changes(class_id) == start + 3


async def test_a_bell_period_added_through_its_schedule_wakes_the_class(
    bus, session, school_class
) -> None:
    start = bus.changes(school_class.id)
    schedule = BellSchedule(class_id=school_class.id, name="Субботнее")
    schedule.periods.append(BellPeriod(index=1, starts_at=time(9), ends_at=time(9, 40)))
    session.add(schedule)
    await session.commit()
    assert bus.changes(school_class.id) == start + 1


async def test_what_changes_no_window_wakes_nobody(bus, session, school_class) -> None:
    start = bus.changes(school_class.id)
    session.add_all(
        [
            PersonalTask(class_id=school_class.id, telegram_id=2001, title="Купить тетрадь"),
            ReminderSettings(class_id=school_class.id, telegram_id=2001),
            AuditEntry(class_id=school_class.id, telegram_id=2001, action="x", summary="y"),
        ]
    )
    await session.commit()
    assert bus.changes(school_class.id) == start


async def test_a_rollback_forgets_what_the_transaction_collected(
    bus, session, school_class
) -> None:
    class_id = school_class.id
    start = bus.changes(class_id)
    session.add(_homework(class_id))
    await session.flush()
    await session.rollback()
    session.add(PersonalTask(class_id=class_id, telegram_id=2001, title="Купить тетрадь"))
    await session.commit()
    assert bus.changes(class_id) == start


async def test_a_savepoint_released_publishes_nothing_until_the_commit(
    bus, session, school_class
) -> None:
    """SQLAlchemy fires ``after_commit`` for a savepoint's release as well. A
    watcher woken there would read the window before the transaction around
    it commits, find nothing new, and never be woken for the change again:
    ``homework.create`` writes inside exactly such a savepoint."""
    class_id = school_class.id
    start = bus.changes(class_id)
    with bus.watching(class_id) as woken:
        async with session.begin_nested():
            session.add(_homework(class_id))
        assert (bus.changes(class_id), woken.is_set()) == (start, False)
        await session.commit()
        assert (bus.changes(class_id), woken.is_set()) == (start + 1, True)


async def test_a_savepoint_rolled_back_never_hides_what_the_commit_holds(
    bus, session, school_class
) -> None:
    class_id = school_class.id
    start = bus.changes(class_id)
    nested = await session.begin_nested()
    session.add(_homework(class_id))
    await session.flush()
    await nested.rollback()
    session.add(_row("day_events", class_id))
    await session.commit()
    assert bus.changes(class_id) == start + 1


async def test_a_touch_goes_out_with_the_commit_and_is_forgotten_with_a_rollback(
    bus, session, school_class
) -> None:
    """What a bulk statement does: the unit of work never sees it."""
    class_id = school_class.id
    statement = (
        sa_update(TimetableEntry).where(TimetableEntry.class_id == class_id).values(room="7")
    )
    start = bus.changes(class_id)
    await session.execute(statement)
    await session.rollback()
    assert bus.changes(class_id) == start, "an untouched bulk write is invisible to the bus"
    await session.execute(statement)
    bus.touch(session, class_id)
    await session.rollback()
    await session.commit()
    assert bus.changes(class_id) == start
    await session.execute(statement)
    bus.touch(session, class_id)
    await session.commit()
    assert bus.changes(class_id) == start + 1


async def test_without_the_listener_nothing_is_collected_or_published(
    session, school_class
) -> None:
    assert not watch.listening(), "the suite runs with streaming off"
    class_id = school_class.id
    start = watch.changes(class_id)
    await session.execute(
        sa_update(TimetableEntry).where(TimetableEntry.class_id == class_id).values(room="7")
    )
    watch.touch(session, class_id)
    assert not session.info
    session.add(_homework(class_id))
    await session.commit()
    assert watch.changes(class_id) == start


async def test_a_watcher_is_woken_by_the_commit_never_by_the_flush_nor_by_another_class(
    bus, session, school_class
) -> None:
    other = SchoolClass(name="5А", join_code="OTHER5")
    session.add(other)
    await session.commit()
    class_id, other_id = school_class.id, other.id
    with bus.watching(class_id) as woken, bus.watching(other_id) as elsewhere:
        session.add(_homework(class_id))
        await session.flush()
        assert not woken.is_set()
        await session.commit()
        assert woken.is_set()
        assert not elsewhere.is_set()
        assert (bus.watchers(class_id), bus.watchers(other_id)) == (1, 1)
    assert (bus.watchers(class_id), bus.watchers(other_id)) == (0, 0)


def test_a_revision_moves_with_every_change_and_names_this_process(bus, monkeypatch) -> None:
    """A restarted host counts from nothing again; its name is what keeps its
    first revision from equalling one a phone kept from before the restart."""
    nobody = 987654
    first = bus.revision(nobody)
    bus.publish([nobody])
    second = bus.revision(nobody)
    assert first != second
    assert bus.changed_at(nobody) is not None
    monkeypatch.setattr(watch, "BOOT", "another-start")
    assert bus.revision(nobody) not in (first, second)


async def test_attaching_twice_hears_each_change_once(bus, session, school_class) -> None:
    bus.attach()
    start = bus.changes(school_class.id)
    session.add(_homework(school_class.id))
    await session.commit()
    assert bus.changes(school_class.id) == start + 1
