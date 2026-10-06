"""``/bells``: the class's bell schedules, as «🔔 Звонки» edits them.

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring. The patch the two writes to one
schedule apply is ``services/manage/bells.update``, which v2's
``UpdateBellSchedule`` applies too, and the sentences the refusals answer
with are ``app/wording.py``'s.
"""

from __future__ import annotations

from typing import Any

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app import wording
from app.api.deps import current_class
from app.api.manage._common import Actor, _conflict, admin_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import BellSchedule, SchoolClass
from app.schemas import (
    BellPeriodOut,
    BellPeriodsIn,
    BellScheduleIn,
    BellScheduleOut,
    BellSchedulePatch,
    DeletedOut,
)
from app.services.manage import bells as bells_service

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
    schedule = await bells_service.schedule_of(session, school_class.id, schedule_id)
    if schedule is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=wording.UNKNOWN_BELL_SCHEDULE_DETAIL
        )
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
    rows = await bells_service.schedules_of(session, school_class.id)
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
    schedule = await bells_service.create(
        session, school_class, actor.telegram_id, payload.name, _rows_of(payload)
    )
    await session.commit()
    await session.refresh(schedule, ["periods"])
    return _schedule_out(schedule, school_class)


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
    another one the default. A schedule that rings nothing cannot become it,
    for the reason ``ScheduleEmpty`` gives, and making the default what it
    already is writes nothing. The patch is ``services/manage/bells.update``,
    which ``PUT …/periods`` and v2's ``UpdateBellSchedule`` apply too.
    """
    schedule = await _schedule_or_404(session, school_class, schedule_id)
    try:
        silenced = await bells_service.update(
            session,
            school_class,
            actor.telegram_id,
            schedule,
            payload.model_dump(exclude_unset=True),
        )
    except bells_service.DefaultRequired as required:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=wording.BELL_DEFAULT_REQUIRED_DETAIL,
        ) from required
    except bells_service.ScheduleEmpty as empty:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=wording.EMPTY_BELL_SCHEDULE_DETAIL,
        ) from empty

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
    silenced = await bells_service.update(
        session, school_class, actor.telegram_id, schedule, {"periods": _rows_of(payload)}
    )
    await session.commit()
    await session.refresh(schedule, ["periods"])
    return _schedule_out(schedule, school_class, len(silenced))


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
    try:
        await bells_service.delete(session, school_class, actor.telegram_id, schedule)
    except bells_service.ScheduleIsDefault as default:
        raise _conflict(wording.BELL_SCHEDULE_IS_DEFAULT_DETAIL) from default
    except bells_service.ScheduleInUse as in_use:
        raise _conflict(wording.bell_schedule_in_use_detail(in_use.days)) from in_use
    await session.commit()
    return DeletedOut(id=schedule_id)
