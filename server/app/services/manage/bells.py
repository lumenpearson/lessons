"""«🔔 Звонки» and ``/manage/bells``: the class's bell schedules.

A class may keep several - «Обычное», «Сокращённое», «Суббота» - and a
shortened day points at one of them. One is the class default, and that one is
what every ordinary day rings. The rows themselves are written by
:func:`app.services.structure.write_bell_periods`, which also answers what a
change stops ringing; this module is the rest of what the two shells did twice.
"""

from __future__ import annotations

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import BellPeriod, BellSchedule, DayOverride, SchoolClass
from app.services import audit, structure
from app.services.structure import BellRow


class ScheduleEmpty(Exception):
    """A schedule with no rows may not be the class default.

    Every ordinary day rings the default, and a day that rings nothing draws
    nothing: ``/bundle`` answers zero lessons, ``/now`` answers «day_off» on a
    Monday, and the phone, the widget, the calendar feed and the morning digest
    go blank together with nothing anywhere reporting a problem. Worse,
    ``rung_indexes`` then returns an empty set, which ``can_ring`` reads as
    «this class has not set its bells up yet» and waves every lesson number
    through. ``edit.day_put`` refuses the same thing at day level.
    """


class ScheduleIsDefault(Exception):
    """The class default cannot be deleted; another one has to become it first."""


class ScheduleInUse(Exception):
    """Special days still point at the schedule; ``days`` says how many."""

    def __init__(self, days: int) -> None:
        super().__init__(str(days))
        self.days = days


async def schedules_of(session: AsyncSession, class_id: int) -> list[BellSchedule]:
    """Every schedule the class keeps, in the order they were made."""
    return list(
        await session.scalars(
            select(BellSchedule)
            .where(BellSchedule.class_id == class_id)
            .order_by(BellSchedule.id)
        )
    )


async def schedule_of(
    session: AsyncSession, class_id: int, schedule_id: int
) -> BellSchedule | None:
    """Scoped by the query: another class's schedule id finds nothing."""
    return await session.scalar(
        select(BellSchedule).where(
            BellSchedule.id == schedule_id, BellSchedule.class_id == class_id
        )
    )


async def rings_anything(session: AsyncSession, schedule: BellSchedule) -> bool:
    """Whether the schedule has one row at all - one row, not the count."""
    return (
        await session.scalar(
            select(BellPeriod.id).where(BellPeriod.schedule_id == schedule.id).limit(1)
        )
        is not None
    )


async def rung_by_default(session: AsyncSession, school_class: SchoolClass) -> set[int]:
    """The lesson numbers the class rings today, before anything is changed.

    An empty set when the class has no default at all, which is the honest
    reading: nothing was ringing, so nothing can stop.
    """
    if school_class.bell_schedule_id is None:
        return set()
    current = await session.scalar(
        select(BellSchedule).where(BellSchedule.id == school_class.bell_schedule_id)
    )
    return {period.index for period in current.periods} if current is not None else set()


async def create(
    session: AsyncSession,
    school_class: SchoolClass,
    actor_id: int | None,
    name: str,
    rows: list[BellRow],
) -> BellSchedule:
    """A new named schedule, with its rows if it came with any.

    It does not become the class default: a shortened schedule is made in
    order to be pointed at by particular days, and making it the default the
    moment it exists would move every day onto it.
    """
    schedule = BellSchedule(class_id=school_class.id, name=name)
    session.add(schedule)
    await session.flush()
    if rows:
        await structure.write_bell_periods(session, schedule, rows)
    await audit.record(
        session,
        school_class.id,
        actor_id,
        "bells.create",
        f"создано расписание звонков «{name}»: {len(rows)} уроков",
    )
    return schedule


async def rename(
    session: AsyncSession,
    school_class: SchoolClass,
    actor_id: int | None,
    schedule: BellSchedule,
    name: str,
) -> None:
    """Rename a schedule. The same name again writes nothing."""
    if name == schedule.name:
        return
    old_name = schedule.name
    schedule.name = name
    await audit.record(
        session, school_class.id, actor_id, "bells.rename", f"звонки «{old_name}» → «{name}»"
    )


async def make_default(
    session: AsyncSession,
    school_class: SchoolClass,
    actor_id: int | None,
    schedule: BellSchedule,
) -> list[tuple[int, int]]:
    """Make ``schedule`` the one every ordinary day rings.

    Moving the default to a *shorter* schedule takes lessons off every weekday
    exactly as shrinking the current one does, and nothing rewrites a row, so
    ``write_bell_periods`` never runs and never counts them. They are counted
    here through the same function, against what the class rang a moment ago:
    asking only the incoming schedule counts rows that were already silent,
    which said «перестали звонить уроков: N» on a move to a schedule ringing
    exactly the same numbers.

    Making the default what it already is changes nothing and logs nothing.

    @return the (weekday, number) rows that stopped ringing.
    @raises ScheduleEmpty when the schedule has no rows.
    """
    if not schedule.periods:
        raise ScheduleEmpty(schedule.name)
    if school_class.bell_schedule_id == schedule.id:
        return []

    was = await rung_by_default(session, school_class)
    silenced = await structure.lessons_silenced_by(
        session, school_class.id, was, {period.index for period in schedule.periods}
    )
    school_class.bell_schedule_id = schedule.id
    summary = f"основное расписание звонков: «{schedule.name}»"
    if silenced:
        summary += f", перестали звонить уроков: {len(silenced)}"
    await audit.record(session, school_class.id, actor_id, "bells.default", summary)
    return silenced


async def replace_rows(
    session: AsyncSession,
    school_class: SchoolClass,
    actor_id: int | None,
    schedule: BellSchedule,
    rows: list[BellRow],
) -> list[tuple[int, int]]:
    """Replace a schedule's rows wholesale.

    Wholesale because that is what editing bells is: the times after the
    lesson that moved all shift with it, and the rows that are now true are
    one intention, not six.

    @return the (weekday, number) rows that stopped ringing. Shrinking the
        class's own schedule takes lessons off every weekday that carried a
        number past the new last rung; they are still stored and drawn nowhere,
        so the log line and the shell's answer are where an admin finds out.
    """
    orphaned = await structure.write_bell_periods(session, schedule, rows)
    summary = f"звонки «{schedule.name}»: {len(rows)} уроков"
    if orphaned:
        summary += f", перестали звонить уроков: {len(orphaned)}"
    await audit.record(session, school_class.id, actor_id, "bells.edit", summary)
    return orphaned


async def delete(
    session: AsyncSession,
    school_class: SchoolClass,
    actor_id: int | None,
    schedule: BellSchedule,
) -> str:
    """Delete a schedule nothing depends on.

    Refused for the class default and for anything a day still points at. Both
    are a ``SET NULL`` at the database level, which would silently move those
    days onto the default schedule - a change nobody asked for, on dates an
    admin is not looking at.

    @return the name it had, for the shell to say.
    @raises ScheduleIsDefault, ScheduleInUse
    """
    if schedule.id == school_class.bell_schedule_id:
        raise ScheduleIsDefault(schedule.name)
    used = int(
        await session.scalar(
            select(func.count())
            .select_from(DayOverride)
            .where(
                DayOverride.class_id == school_class.id,
                DayOverride.bell_schedule_id == schedule.id,
            )
        )
        or 0
    )
    if used:
        raise ScheduleInUse(used)

    name = schedule.name
    await session.delete(schedule)
    await audit.record(
        session, school_class.id, actor_id, "bells.delete", f"удалено расписание звонков «{name}»"
    )
    return name
