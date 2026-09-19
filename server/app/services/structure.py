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

from dataclasses import dataclass, field
from datetime import date as Date
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


@dataclass(frozen=True)
class TimetableImport:
    """What an import actually did, for the caller to report.

    ``dropped`` carries one entry per row that was thrown away, not one per
    lesson number: a week paste puts «8. Алгебра» under Monday and Tuesday
    alike, and the class either rings an eighth lesson or it does not, so both
    rows go. While the count came off the distinct numbers, the card said
    «без звонка пропущено 1» for two lessons nobody would ever see, and the
    arithmetic above it («уроков 14» against sixteen pasted lines) was the only
    place the second one was mentioned at all.
    """

    written: int
    schedule: BellSchedule | None
    dropped: list[tuple[int, int]]

    #: Lessons already in the template that the bells this import wrote no
    #: longer ring. The other end of ``dropped``: that one is a pasted lesson
    #: the class cannot ring, this one is a stored lesson the class has just
    #: stopped ringing. Same invariant, opposite direction, and until now only
    #: the first half was ever checked — so pasting a five-row Saturday block
    #: over the default schedule quietly took lessons 6 and 7 off every
    #: weekday, on every phone, with «уроков — 5» as the only thing anybody
    #: was told.
    orphaned: list[tuple[int, int]] = field(default_factory=list)

    @property
    def unrung(self) -> list[int]:
        """The lesson numbers to name in «нет таких номеров», each once.

        The numbers are a property of the class's bells, not of the paste, so a
        number repeated across weekdays is one thing to fix, said once.
        """
        return sorted({index for _weekday, index in self.dropped})


async def homework_clashing(
    session: AsyncSession, class_id: int, old_name: str, new_name: str
) -> list[Date]:
    """The days on which renaming ``old_name`` to ``new_name`` would collide.

    Homework is unique per (class, day, subject name) — `0013` — and a rename
    is a bulk `UPDATE` over the text. So a class that has one задание under
    «Алгебра» and another under «Матан» on the same Friday cannot rename the
    first onto the second: the statement raises `IntegrityError` out of the
    middle of a transaction that had already moved the timetable and the
    замены, nothing is committed, and the whole rename is lost with no message
    anybody can act on.

    The dictionary check the two shells already make does not see this. A
    задание can be written under a name that is not a dictionary entry at all
    — `subjects.spelling` never founds one — so the colliding name is
    invisible to `subjects.clashing`, and the only configuration that reaches
    the `UPDATE` with a collision is exactly the one it cannot see.

    Returns the days, so the answer can name them: «есть задания и там и там»
    is not something an admin can do anything about.
    """
    if old_name == new_name:
        return []
    old_days = select(Homework.due_date).where(
        Homework.class_id == class_id, Homework.subject_name == old_name
    )
    clashing = await session.scalars(
        select(Homework.due_date)
        .where(
            Homework.class_id == class_id,
            Homework.subject_name == new_name,
            Homework.due_date.in_(old_days),
        )
        .order_by(Homework.due_date)
    )
    return list(clashing)


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
) -> list[tuple[int, int]]:
    """Replace a schedule's rows wholesale; answer with what stops ringing.

    The delete is a bulk statement, which goes round the ORM, so the eagerly
    loaded ``periods`` collection is stale afterwards and every caller
    refreshes it before rendering the result.

    The return value is the other half of «a lesson number needs a bell of its
    own number», which until now was only ever checked when a *lesson* was
    written. Shrinking the schedule is the same invariant broken from the
    other end: paste a five-row Saturday over the class's default and every
    weekday's lessons 6 and 7 are still stored, and drawn, logged and
    announced nowhere — «✅ Звонки сохранены: 5 уроков» and not a word about
    the fourteen lessons that just left every phone in the class.

    The (weekday, index) pairs, because one number under two weekdays is two
    lessons nobody will see — the same reason `apply_timetable` counts rows
    and not numbers. Only the class's own default is checked: a schedule some
    particular day points at affects that day, and the day is a different
    question from the weekly template.
    """
    orphaned: list[tuple[int, int]] = []
    if schedule.class_id is not None and schedule.id is not None:
        rung = {index for index, _, _ in rows}
        default_of = await session.scalar(
            select(SchoolClass.bell_schedule_id).where(SchoolClass.id == schedule.class_id)
        )
        if default_of == schedule.id:
            existing = await session.execute(
                select(TimetableEntry.weekday, TimetableEntry.index)
                .where(TimetableEntry.class_id == schedule.class_id)
                .order_by(TimetableEntry.weekday, TimetableEntry.index)
            )
            orphaned = [
                (int(weekday), int(index))
                for weekday, index in existing
                if int(index) not in rung
            ]

    await session.execute(sa_delete(BellPeriod).where(BellPeriod.schedule_id == schedule.id))
    for index, start, end in rows:
        session.add(
            BellPeriod(schedule_id=schedule.id, index=index, starts_at=start, ends_at=end)
        )
    return orphaned


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
) -> TimetableImport:
    """Replace exactly the weekdays ``days`` names, and the bells if any came.

    Days the paste did not mention are left alone, so importing a single day's
    block is a legitimate thing to do. A day that appears with no lessons under
    it is emptied - that is how a paste says «в четверг уроков нет».

    Returns a :class:`TimetableImport`: how many lessons were written, the
    schedule the bells went into, and every row that had no bell to ring it as
    (weekday, lesson number). The schedule is handed back rather than refreshed
    here because a bulk delete left its ``periods`` stale and only the caller
    knows whether it is about to be read.

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
    dropped: list[tuple[int, int]] = []
    for weekday, rows in days.items():
        for index, subject, room, teacher, parity in rows:
            if not timetable_edit.can_ring(rung, index):
                dropped.append((weekday, index))
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
    orphaned: list[tuple[int, int]] = []
    if bells:
        if school_class.bell_schedule_id:
            schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
        if schedule is None:
            schedule = BellSchedule(class_id=school_class.id, name=DEFAULT_SCHEDULE_NAME)
            session.add(schedule)
            await session.flush()
            school_class.bell_schedule_id = schedule.id
        orphaned = await write_bell_periods(session, schedule, bells)

    return TimetableImport(
        written=total, schedule=schedule, dropped=dropped, orphaned=orphaned
    )
