"""``/stats``: the numbers «📊 Статистика» shows.

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring.
"""

from __future__ import annotations

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class
from app.api.manage._common import Actor, editor_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import SchoolClass
from app.schemas import StatsOut, SubjectHoursOut
from app.services import stats as stats_service

router = APIRouter(route_class=DishkaAnnotatedRoute)


# --------------------------------------------------------------------------
# 📊 Stats
# --------------------------------------------------------------------------


@router.get("/stats", response_model=StatsOut)
async def stats(
    _: Actor = Depends(editor_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> StatsOut:
    """The numbers «📊 Статистика» shows, without the sentence around them.

    An editor may read them, as in the bot: they say whether the timetable is
    complete and whether homework is being entered, which is exactly what the
    person entering it wants to know.
    """
    numbers = await stats_service.class_stats(session, school_class)
    return StatsOut(
        today=numbers["today"],
        lessons_per_week=numbers["lessons_per_week"],
        subjects_count=numbers["subjects_count"],
        subjects=[
            SubjectHoursOut(name=name, hours=hours) for name, hours in numbers["subjects"]
        ],
        homework_open=numbers["homework_open"],
        homework_total=numbers["homework_total"],
        members_by_role={role.value: count for role, count in numbers["members_by_role"].items()},
        devices_active=numbers["devices_active"],
        overrides_upcoming=numbers["overrides_upcoming"],
        events_upcoming=numbers["events_upcoming"],
    )
