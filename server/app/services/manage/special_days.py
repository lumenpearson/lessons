"""«🏖 Особые дни»: the dates a class marks as not an ordinary school day.

A :class:`DayOverride` is how the class says "this date is not a normal school
day". «Обычный день» is the absence of a row, not a kind of row, so choosing it
deletes the mark instead of storing ``DayKind.NORMAL`` - otherwise the resolver
would have two ways to spell the same thing.

Only the bot's «⚙️ Класс» edits marks in bulk; the phone marks one day at a time
through ``PUT /api/v1/edit/days``, under the editor's role, with its own rules
about the bells. These are the reads and the one write the bot's screen does.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import BellSchedule, DayKind, DayOverride, SchoolClass


async def upcoming(
    session: AsyncSession, class_id: int, today: Date, only: DayKind | None = None
) -> list[DayOverride]:
    """The marks from ``today`` on, by date; narrowed to one kind if asked."""
    query = (
        select(DayOverride)
        .where(DayOverride.class_id == class_id, DayOverride.date >= today)
        .order_by(DayOverride.date)
    )
    if only is not None:
        query = query.where(DayOverride.kind == only)
    return list(await session.scalars(query))


async def schedule_names(session: AsyncSession, class_id: int) -> dict[int, str]:
    """Bell schedule id -> name, for the marks that point at one."""
    return {
        schedule.id: schedule.name
        for schedule in await session.scalars(
            select(BellSchedule).where(BellSchedule.class_id == class_id)
        )
    }


async def mark_on(session: AsyncSession, class_id: int, day: Date) -> DayOverride | None:
    """The class's mark on ``day``, if it has one."""
    return await session.scalar(
        select(DayOverride).where(DayOverride.class_id == class_id, DayOverride.date == day)
    )


async def mark(
    session: AsyncSession, school_class: SchoolClass, day: Date, kind: DayKind
) -> DayOverride:
    """Mark ``day`` as ``kind``, over whatever mark it had."""
    override = await mark_on(session, school_class.id, day)
    if override is None:
        override = DayOverride(class_id=school_class.id, date=day, kind=kind)
        session.add(override)
    override.kind = kind
    if kind is not DayKind.SHORTENED:
        # A bell schedule only means anything on a shortened day; leaving a
        # stale one on a holiday would surface in the day view as a
        # schedule nobody chose.
        override.bell_schedule_id = None
    elif override.bell_schedule_id is None:
        # Never null on a shortened day. The picker opens next and is where
        # the real answer comes from, but the row is committed before it is
        # asked — so walking away used to leave «⏱ Сокращённые уроки» over a
        # day that names no schedule, which `api/edit.day_put` refuses with a
        # 422 and the resolver silently draws as a normal day. The class
        # default is the honest starting value: it is what the day would ring
        # anyway, and now it says so on the card.
        override.bell_schedule_id = school_class.bell_schedule_id
    return override


async def mark_period(
    session: AsyncSession, school_class: SchoolClass, first: Date, days: int, kind: DayKind
) -> None:
    """Mark ``days`` consecutive dates from ``first`` as ``kind``."""
    for offset in range(days):
        await mark(session, school_class, first + timedelta(days=offset), kind)
