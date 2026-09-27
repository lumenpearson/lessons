"""``/bells``: the class's bell schedules, as «🔔 Звонки» edits them.

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring.
"""

from __future__ import annotations

from typing import Any

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class
from app.api.manage._common import Actor, _conflict, _count, admin_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import BellSchedule, DayOverride, SchoolClass
from app.schemas import (
    BellPeriodOut,
    BellPeriodsIn,
    BellScheduleIn,
    BellScheduleOut,
    BellSchedulePatch,
    DeletedOut,
)
from app.services import audit, structure

router = APIRouter(route_class=DishkaAnnotatedRoute)


# --------------------------------------------------------------------------
# 🔔 Bell schedules
# --------------------------------------------------------------------------


def _schedule_out(
    schedule: BellSchedule, school_class: SchoolClass, silenced: int = 0
) -> BellScheduleOut:
    return BellScheduleOut(
        id=schedule.id,
        name=schedule.name,
        is_default=schedule.id == school_class.bell_schedule_id,
        silenced_lessons=silenced,
        periods=[
            BellPeriodOut(index=row.index, starts_at=row.starts_at, ends_at=row.ends_at)
            for row in sorted(schedule.periods, key=lambda row: row.index)
        ],
    )


async def _schedule_or_404(
    session: AsyncSession, school_class: SchoolClass, schedule_id: int
) -> BellSchedule:
    schedule = await session.scalar(
        select(BellSchedule).where(
            BellSchedule.id == schedule_id, BellSchedule.class_id == school_class.id
        )
    )
    if schedule is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown bell schedule")
    return schedule


def _rows_of(payload: Any) -> list[tuple[int, Any, Any]]:
    """The wire shape as ``structure.write_bell_periods`` takes it."""
    return [(row.index, row.starts_at, row.ends_at) for row in payload.periods]


@router.get("/bells", response_model=list[BellScheduleOut])
async def bells_list(
    _: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> list[BellScheduleOut]:
    """Every schedule the class keeps - «Обычное», «Сокращённое», «Суббота» -
    with the class default marked."""
    rows = await session.scalars(
        select(BellSchedule)
        .where(BellSchedule.class_id == school_class.id)
        .order_by(BellSchedule.id)
    )
    return [_schedule_out(row, school_class) for row in rows]


@router.post("/bells", response_model=BellScheduleOut, status_code=status.HTTP_201_CREATED)
async def bells_create(
    payload: BellScheduleIn,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> BellScheduleOut:
    """A new named schedule, with its rows if they came with it.

    It does not become the class default: a shortened schedule is created in
    order to be pointed at by particular days, and making it the default the
    moment it exists would move every day onto it.
    """
    schedule = BellSchedule(class_id=school_class.id, name=payload.name)
    session.add(schedule)
    await session.flush()
    if payload.periods:
        await structure.write_bell_periods(session, schedule, _rows_of(payload))
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "bells.create",
        f"создано расписание звонков «{payload.name}»: {len(payload.periods)} уроков",
    )
    await session.commit()
    await session.refresh(schedule, ["periods"])
    return _schedule_out(schedule, school_class)


async def _rung_by_default(
    session: AsyncSession, school_class: SchoolClass
) -> set[int]:
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


@router.patch("/bells/{schedule_id}", response_model=BellScheduleOut)
async def bells_update(
    schedule_id: int,
    payload: BellSchedulePatch,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> BellScheduleOut:
    """Rename a schedule, or make it the one the class runs on by default.

    ``is_default: false`` is refused: a class with no default schedule has no
    times for an ordinary day, so the way to stop using this one is to make
    another one the default.
    """
    schedule = await _schedule_or_404(session, school_class, schedule_id)
    silenced: list[tuple[int, int]] = []

    if payload.name is not None and payload.name != schedule.name:
        old_name = schedule.name
        schedule.name = payload.name
        await audit.record(
            session,
            school_class.id,
            actor.telegram_id,
            "bells.rename",
            f"звонки «{old_name}» → «{payload.name}»",
        )

    if payload.is_default is not None:
        if not payload.is_default:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="make another schedule the default instead",
            )
        # A schedule may be created empty and filled in afterwards, which is
        # the point of the two-step flow — but the class default is what every
        # ordinary day rings, and a default that rings nothing draws nothing:
        # no lesson on any weekday can be placed on a timeline, so `/bundle`
        # answers zero lessons, `/now` answers «day_off» on a Monday, and the
        # phone, the widget, the calendar feed and the morning digest all go
        # blank at once with nothing anywhere reporting a problem. Worse,
        # `rung_indexes` then returns an empty set, which `can_ring` reads as
        # «this class has not set its bells up yet» and waves every lesson
        # number through. `edit.day_put` refuses the same thing at day level.
        if not schedule.periods:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="в этом расписании звонков нет ни одного урока",
            )
        if school_class.bell_schedule_id != schedule.id:
            # Moving the default to a *shorter* schedule takes lessons off
            # every weekday exactly as shrinking the current one does — the
            # rows past its last rung stay in the database and are drawn,
            # logged and announced nowhere. `write_bell_periods` has answered
            # this question since the other way in was closed, but it is only
            # reached when a schedule's rows are rewritten, and re-pointing
            # the class rewrites none. Asked here with the incoming
            # schedule's numbers, through the same function, so the two
            # cannot answer differently.
            # What the class rang a moment ago, against what it will ring
            # now. Asking only the incoming schedule counts rows that were
            # already silent, which said «перестали звонить уроков: N» on a
            # move to a schedule ringing exactly the same numbers.
            was = await _rung_by_default(session, school_class)
            silenced = await structure.lessons_silenced_by(
                session,
                school_class.id,
                was,
                {period.index for period in schedule.periods},
            )
            school_class.bell_schedule_id = schedule.id
            summary = f"основное расписание звонков: «{schedule.name}»"
            if silenced:
                summary += f", перестали звонить уроков: {len(silenced)}"
            await audit.record(
                session,
                school_class.id,
                actor.telegram_id,
                "bells.default",
                summary,
            )

    await session.commit()
    await session.refresh(schedule, ["periods"])
    return _schedule_out(schedule, school_class, len(silenced))


@router.put("/bells/{schedule_id}/periods", response_model=BellScheduleOut)
async def bells_periods(
    schedule_id: int,
    payload: BellPeriodsIn,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> BellScheduleOut:
    """Replace a schedule's rows wholesale.

    Wholesale rather than row by row because that is what editing bells is:
    the times of the lessons after the one that moved all shift with it, and
    sending the six rows that are now true is one intention, not six.
    """
    schedule = await _schedule_or_404(session, school_class, schedule_id)
    orphaned = await structure.write_bell_periods(session, schedule, _rows_of(payload))
    summary = f"звонки «{schedule.name}»: {len(payload.periods)} уроков"
    if orphaned:
        # Shrinking the class's own schedule takes lessons off every weekday
        # that carried a number past the new last rung. They are still stored
        # and they are drawn nowhere, so the log is where an admin finds out.
        summary += f", перестали звонить уроков: {len(orphaned)}"
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "bells.edit",
        summary,
    )
    await session.commit()
    await session.refresh(schedule, ["periods"])
    return _schedule_out(schedule, school_class, len(orphaned))


@router.delete("/bells/{schedule_id}", response_model=DeletedOut)
async def bells_delete(
    schedule_id: int,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> DeletedOut:
    """Refused for the class default and for anything a day still points at.

    Both are a ``SET NULL`` at the database level, which would silently move
    those days onto the default schedule - a change nobody asked for, on dates
    an admin is not looking at.
    """
    schedule = await _schedule_or_404(session, school_class, schedule_id)

    if schedule.id == school_class.bell_schedule_id:
        raise _conflict("this is the class default; make another one the default first")

    used = await _count(
        session,
        DayOverride,
        DayOverride.class_id == school_class.id,
        DayOverride.bell_schedule_id == schedule.id,
    )
    if used:
        raise _conflict(f"{used} special day(s) still use this schedule")

    name = schedule.name
    await session.delete(schedule)
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "bells.delete",
        f"удалено расписание звонков «{name}»",
    )
    await session.commit()
    return DeletedOut(id=schedule_id)
