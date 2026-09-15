"""Adding, removing and reordering one lesson of the weekly template.

The template could already be rewritten — a whole weekday at a time, by pasting
a message (``timetable_io.parse_timetable_block``). That is still the fastest
way to enter a term's schedule from a photo of the board, and it is not going
anywhere. It is a poor way to make one change: to move физика from third to
second you retype the day, and a typo in the line you were not touching is a
lesson you have now silently lost.

So this is the other half — the edits a button can make, one lesson at a time,
with the rest of the day untouched by construction.

**The index is a slot, and a slot holds every parity at once.** Moving lesson 3
moves числитель and знаменатель together. Any other rule and one week's third
lesson becomes the other week's second, which is not a reordering of anything
a person asked for — and the bells, which are keyed on the index alone, would
then be right for one week and wrong for the other.

Nothing here commits: the caller's transaction owns the change so that the
audit line lands with it or not at all, the same rule the rest of
``app/services/`` follows.
"""

from __future__ import annotations

from sqlalchemy import delete as sa_delete
from sqlalchemy import func, select
from sqlalchemy import update as sa_update
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import rows_affected
from app.models import BellPeriod, SchoolClass, TimetableEntry, WeekParity
from app.services import subjects

#: Where a row waits while another takes its number.
#:
#: ``uq_timetable_cell`` is a plain unique constraint, so both Postgres and
#: SQLite check it row by row as an UPDATE walks its rows — which means the
#: obvious ``SET index = index + 1 WHERE index >= 3`` fails as soon as the
#: first row lands on the second row's number, depending on nothing more
#: predictable than the order the rows come back in. Every shift below is
#: therefore two statements: out to the park, then back down. No timetable has
#: a thousand lessons in a day, so nothing real is ever parked on.
PARK = 1000

#: Refuse a day longer than this. Not a schema limit — a sanity one: a class
#: with more than this many lessons in a day has a broken import behind it,
#: and the bells only go as far as their own rows do anyway.
MAX_INDEX = 20


async def rings(session: AsyncSession, class_id: int) -> int:
    """How many lessons the class's default bells actually ring.

    The real ceiling on a weekday, and it is not a constant. The resolver
    builds a day's timeline out of the bell rows, so a lesson written at an
    index that has none is stored happily and then dropped: the editor showed
    it, no phone ever did, and nothing anywhere said so. ``MAX_INDEX`` is only
    the backstop for a class whose bells are missing altogether.
    """
    total = await session.scalar(
        select(func.count())
        .select_from(BellPeriod)
        .join(SchoolClass, SchoolClass.bell_schedule_id == BellPeriod.schedule_id)
        .where(SchoolClass.id == class_id)
    )
    return int(total or 0)


async def _ceiling(session: AsyncSession, class_id: int) -> int:
    """The highest lesson number this class can actually show."""
    return min(MAX_INDEX, await rings(session, class_id) or MAX_INDEX)


async def _indexes(session: AsyncSession, class_id: int, weekday: int) -> list[int]:
    """Every lesson number the day uses, ascending, each once."""
    rows = await session.scalars(
        select(TimetableEntry.index)
        .where(TimetableEntry.class_id == class_id, TimetableEntry.weekday == weekday)
        .distinct()
        .order_by(TimetableEntry.index)
    )
    return [int(index) for index in rows]


async def _shift(
    session: AsyncSession, class_id: int, weekday: int, *, at_least: int, by: int
) -> None:
    """Move every slot from ``at_least`` up by ``by`` (may be negative)."""
    where = (
        TimetableEntry.class_id == class_id,
        TimetableEntry.weekday == weekday,
        TimetableEntry.index >= at_least,
    )
    await session.execute(
        sa_update(TimetableEntry).where(*where).values(index=TimetableEntry.index + PARK)
    )
    await session.execute(
        sa_update(TimetableEntry)
        .where(
            TimetableEntry.class_id == class_id,
            TimetableEntry.weekday == weekday,
            TimetableEntry.index >= PARK,
        )
        .values(index=TimetableEntry.index - PARK + by)
    )


async def add_lesson(
    session: AsyncSession,
    class_id: int,
    weekday: int,
    *,
    subject: str,
    room: str | None = None,
    teacher: str | None = None,
    parity: WeekParity = WeekParity.ANY,
    at: int | None = None,
) -> int | None:
    """Put a lesson in the day. ``at`` inserts and pushes down; ``None`` appends.

    Returns the number it got, or ``None`` when there is no slot for it — the
    day is at ``MAX_INDEX``, or, far more often, past the last bell the class
    rings. The second is the one that used to be missing: the row was written,
    the editor drew it, and :func:`app.schedule.ScheduleResolver` — which takes
    a lesson's times from the bell row of the same number — dropped it from
    every phone, every widget, every digest and the calendar feed, with nothing
    to say it had.
    """
    used = await _indexes(session, class_id, weekday)
    if len(used) >= MAX_INDEX:
        return None
    ceiling = await _ceiling(session, class_id)

    if at is None:
        index = (used[-1] + 1) if used else 1
    else:
        index = max(1, min(at, (used[-1] + 1) if used else 1))
    if index > ceiling:
        return None
    # An insert pushes everything below it down, so the day's last lesson is
    # what has to still fit — otherwise adding a second lesson quietly costs
    # the seventh.
    if at is not None and index in used and used[-1] + 1 > ceiling:
        return None
    if at is not None and index in used:
        await _shift(session, class_id, weekday, at_least=index, by=1)

    # The dictionary decides the spelling, and hands back the row to point at:
    # a lesson typed «алгебра» into a class that already has «Алгебра» joins it
    # rather than founding a second subject with its own colour.
    name, subject_id = await subjects.canonical(session, class_id, subject)
    session.add(
        TimetableEntry(
            class_id=class_id,
            weekday=weekday,
            index=index,
            subject_id=subject_id,
            subject_name=name,
            room=room,
            teacher=teacher,
            parity=parity,
        )
    )
    return index


async def remove_lesson(session: AsyncSession, class_id: int, weekday: int, index: int) -> int:
    """Delete the whole slot and close the gap. Returns rows removed.

    Closing the gap is the point: a day numbered 1, 2, 4 reads as a lost lesson
    rather than as a deleted one, and the bells would hand lesson 4 the fourth
    bell when it is now the third thing that happens.
    """
    result = await session.execute(
        sa_delete(TimetableEntry).where(
            TimetableEntry.class_id == class_id,
            TimetableEntry.weekday == weekday,
            TimetableEntry.index == index,
        )
    )
    removed = rows_affected(result)
    if removed:
        await _shift(session, class_id, weekday, at_least=index + 1, by=-1)
    return removed


async def move_lesson(
    session: AsyncSession, class_id: int, weekday: int, index: int, *, up: bool
) -> int | None:
    """Swap a slot with its neighbour. Returns the number it ends on.

    ``None`` when there is no neighbour that way — the caller says «уже первый»
    rather than redrawing an unchanged day, because a button that visibly does
    nothing is read as a broken button.
    """
    used = await _indexes(session, class_id, weekday)
    if index not in used:
        return None
    position = used.index(index)
    neighbour_at = position - 1 if up else position + 1
    if not 0 <= neighbour_at < len(used):
        return None
    neighbour = used[neighbour_at]

    def _set(from_index: int, to_index: int):
        return (
            sa_update(TimetableEntry)
            .where(
                TimetableEntry.class_id == class_id,
                TimetableEntry.weekday == weekday,
                TimetableEntry.index == from_index,
            )
            .values(index=to_index)
        )

    await session.execute(_set(index, PARK))
    await session.execute(_set(neighbour, index))
    await session.execute(_set(PARK, neighbour))
    return neighbour


async def edit_lesson(
    session: AsyncSession,
    class_id: int,
    weekday: int,
    index: int,
    parity: WeekParity,
    *,
    subject: str,
    room: str | None,
    teacher: str | None,
) -> bool:
    """Rewrite one cell in place, or create it if that parity has no row yet.

    The create half is what makes «задать знаменатель» a single button: a slot
    that holds one «каждую неделю» row is split by rewriting that row to
    числитель and adding a знаменатель beside it, and both halves land here.
    """
    entry = await session.scalar(
        select(TimetableEntry).where(
            TimetableEntry.class_id == class_id,
            TimetableEntry.weekday == weekday,
            TimetableEntry.index == index,
            TimetableEntry.parity == parity,
        )
    )
    name, subject_id = await subjects.canonical(session, class_id, subject)
    if entry is None:
        used = await _indexes(session, class_id, weekday)
        if index not in used:
            return False
        session.add(
            TimetableEntry(
                class_id=class_id,
                weekday=weekday,
                index=index,
                subject_id=subject_id,
                subject_name=name,
                room=room,
                teacher=teacher,
                parity=parity,
            )
        )
        return True

    entry.subject_id = subject_id
    entry.subject_name = name
    entry.room = room
    entry.teacher = teacher
    return True


async def split_parity(
    session: AsyncSession, class_id: int, weekday: int, index: int
) -> bool:
    """Turn a «каждую неделю» slot into a числитель/знаменатель pair.

    The знаменатель starts as a copy, because the alternative — an empty half —
    is a slot the day view has to render as a hole on every second week, and
    the commonest edit after splitting is changing one of the two anyway.
    """
    rows = list(
        await session.scalars(
            select(TimetableEntry).where(
                TimetableEntry.class_id == class_id,
                TimetableEntry.weekday == weekday,
                TimetableEntry.index == index,
            )
        )
    )
    if len(rows) != 1 or rows[0].parity is not WeekParity.ANY:
        return False

    original = rows[0]
    original.parity = WeekParity.ODD
    session.add(
        TimetableEntry(
            class_id=class_id,
            weekday=weekday,
            index=index,
            # A copy, link included: the знаменатель is the same subject until
            # somebody changes it, and a half that lost its colour on the way
            # would look like two different lessons on alternate weeks.
            subject_id=original.subject_id,
            subject_name=original.subject_name,
            room=original.room,
            teacher=original.teacher,
            parity=WeekParity.EVEN,
        )
    )
    return True


async def merge_parity(
    session: AsyncSession, class_id: int, weekday: int, index: int, keep: WeekParity
) -> bool:
    """Collapse a split slot back to «каждую неделю», keeping ``keep``'s row."""
    rows = list(
        await session.scalars(
            select(TimetableEntry).where(
                TimetableEntry.class_id == class_id,
                TimetableEntry.weekday == weekday,
                TimetableEntry.index == index,
            )
        )
    )
    if len(rows) < 2:
        return False

    survivor = next((row for row in rows if row.parity is keep), rows[0])
    for row in rows:
        if row is not survivor:
            await session.delete(row)
    # Flushed before the survivor takes ANY: until the losers are gone the slot
    # would briefly hold two rows whose parity is the same, which is exactly
    # what uq_timetable_cell exists to refuse.
    await session.flush()
    survivor.parity = WeekParity.ANY
    return True


async def day_counts(session: AsyncSession, class_id: int) -> dict[int, int]:
    """Lessons per weekday, for the week pager's «Пн 6 · Вт 5 · …» line."""
    rows = await session.execute(
        select(TimetableEntry.weekday, func.count(TimetableEntry.index.distinct()))
        .where(TimetableEntry.class_id == class_id)
        .group_by(TimetableEntry.weekday)
    )
    return {int(weekday): int(count) for weekday, count in rows}
