"""Structural edits to a class: subject names, bell rows, the weekly template.

The bot and the API both make these edits, and both have to make them the same
way. A subject rename that moved the dictionary entry but not the timetable, or
a bell rewrite that left half the old rows in place, leaves a class that no
longer agrees with itself - and the second surface to implement the rule is the
one that gets it subtly wrong.

Nothing here commits. The caller's transaction owns the change, so the audit
line describing it lands with it or not at all.
"""

from __future__ import annotations

from datetime import time as Time

from sqlalchemy import delete as sa_delete
from sqlalchemy import func, select
from sqlalchemy import update as sa_update
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import rows_affected
from app.models import (
    BellPeriod,
    BellSchedule,
    Homework,
    LessonOverride,
    SchoolClass,
    Subject,
    TimetableEntry,
)
from app.services import subjects, timetable_edit

#: What a schedule is called when a paste brings bell times to a class that has
#: none at all. Named rather than left blank: it is about to be the class's
#: default, and «Обычное» is what the day view will show under it.
DEFAULT_SCHEDULE_NAME = "Обычное"

#: One row of a bell schedule as the parsers hand it over.
BellRow = tuple[int, Time, Time]


async def rename_subject(
    session: AsyncSession, class_id: int, subject: Subject, new_name: str
) -> int:
    """Rename the dictionary entry and every row that spells the old name.

    The timetable, the homework and the замены store the subject as text, not
    as a foreign key - deliberately, so a lesson keeps its name when a subject
    is deleted. The price is that a rename has to be a cascade, and it has to
    happen in the caller's transaction: a half-applied rename would leave the
    class with two subjects where it had one and no way to tell which rows
    belong to which. Returns how many rows moved.
    """
    old_name = subject.name
    moved = 0

    # The timetable is matched on the link first and the old spelling second.
    # The link is the reliable half — it survives a row whose name drifted —
    # and the name is what catches rows written before the link existed, which
    # is every row in every class older than it.
    result = await session.execute(
        sa_update(TimetableEntry)
        .where(
            TimetableEntry.class_id == class_id,
            (TimetableEntry.subject_id == subject.id)
            | (TimetableEntry.subject_name == old_name),
        )
        .values(subject_name=new_name, subject_id=subject.id)
    )
    moved += rows_affected(result)

    # Homework and замены carry no link at all: a lesson keeps its name when a
    # subject is deleted, and that is the whole reason they store text.
    for model in (Homework, LessonOverride):
        result = await session.execute(
            sa_update(model)
            .where(model.class_id == class_id, model.subject_name == old_name)
            .values(subject_name=new_name)
        )
        moved += rows_affected(result)

    subject.name = new_name
    return moved


async def write_bell_periods(
    session: AsyncSession, schedule: BellSchedule, rows: list[BellRow]
) -> None:
    """Replace a schedule's rows wholesale.

    The delete is a bulk statement, which goes round the ORM, so the eagerly
    loaded ``periods`` collection is stale afterwards and every caller
    refreshes it before rendering the result.
    """
    await session.execute(sa_delete(BellPeriod).where(BellPeriod.schedule_id == schedule.id))
    for index, start, end in rows:
        session.add(
            BellPeriod(schedule_id=schedule.id, index=index, starts_at=start, ends_at=end)
        )


async def lessons_per_weekday(
    session: AsyncSession, class_id: int, weekdays: list[int]
) -> dict[int, int]:
    """How many lessons each of ``weekdays`` already carries.

    An import replaces whole weekdays, so this is what stands to be overwritten
    by one. The bot shows it as a preview screen before «Применить»; the API has
    no screen to show, so it answers with these numbers instead of quietly
    winning.
    """
    if not weekdays:
        return {}
    rows = await session.execute(
        select(TimetableEntry.weekday, func.count())
        .where(TimetableEntry.class_id == class_id, TimetableEntry.weekday.in_(weekdays))
        .group_by(TimetableEntry.weekday)
    )
    return {int(weekday): int(count) for weekday, count in rows}


async def apply_timetable(
    session: AsyncSession,
    school_class: SchoolClass,
    days: dict[int, list],
    bells: list[BellRow],
) -> tuple[int, BellSchedule | None, list[int]]:
    """Replace exactly the weekdays ``days`` names, and the bells if any came.

    Days the paste did not mention are left alone, so importing a single day's
    block is a legitimate thing to do. A day that appears with no lessons under
    it is emptied - that is how a paste says «в четверг уроков нет».

    Returns (lessons written, the schedule the bells went into, the lesson
    numbers that had no bell to ring them). The schedule is handed back rather
    than refreshed here because a bulk delete left its ``periods`` stale and
    only the caller knows whether it is about to be read.

    **A lesson with no bell of its own number is not written.** The resolver
    takes a lesson's times from the bell row of the same number, so such a row
    used to be stored, counted in «уроков добавлено» and then shown nowhere at
    all. What is checked is what the class will ring *after* this import, not
    before: a paste that brings a «== Звонки ==» block with eight rows may
    legitimately bring an eighth lesson with it, and checking against the old
    schedule would reject the very line that the same paste makes valid.

    The numbers, not how many of them there are. Counting the rows is the same
    answer only while they run 1..N with no gap, and a «== Звонки ==» block may
    leave one — at which point the count refused «4. Химия», which had a bell,
    and would have admitted a third lesson, which had none.
    """
    if days:
        await session.execute(
            sa_delete(TimetableEntry).where(
                TimetableEntry.class_id == school_class.id,
                TimetableEntry.weekday.in_(list(days)),
            )
        )

    rung = (
        {index for index, _start, _end in bells}
        if bells
        else await timetable_edit.rung_indexes(session, school_class.id)
    )

    total = 0
    unrung: list[int] = []
    for weekday, rows in days.items():
        for index, subject, room, teacher, parity in rows:
            if not timetable_edit.can_ring(rung, index):
                unrung.append(index)
                continue
            # A paste is where a class's subjects usually come into existence,
            # and where two spellings of one of them usually do too. The
            # dictionary settles both: the name it already holds wins, and a
            # name it does not hold is adopted rather than left unlinked.
            name, subject_id = await subjects.canonical(session, school_class.id, subject)
            session.add(
                TimetableEntry(
                    class_id=school_class.id,
                    weekday=weekday,
                    index=index,
                    subject_id=subject_id,
                    subject_name=name,
                    room=room,
                    teacher=teacher,
                    parity=parity,
                )
            )
            total += 1

    schedule: BellSchedule | None = None
    if bells:
        if school_class.bell_schedule_id:
            schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
        if schedule is None:
            schedule = BellSchedule(class_id=school_class.id, name=DEFAULT_SCHEDULE_NAME)
            session.add(schedule)
            await session.flush()
            school_class.bell_schedule_id = schedule.id
        await write_bell_periods(session, schedule, bells)

    return total, schedule, sorted(set(unrung))
