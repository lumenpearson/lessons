"""``BellService``: the class's bell schedules, as «🔔 Звонки» lists them.

v1's ``GET /manage/bells``, over the same services, with the class default
marked and each time ``"HH:MM"`` where v1 wrote seconds. ``GetBellSchedule``
reads one schedule by its id, which v1 had no endpoint for; an id of another
class's schedule finds nothing, as in v1's writes.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app import wording
from app.contract.lessons.v2.bell_pb import (
    BellPeriod,
    BellSchedule,
    GetBellScheduleRequest,
    GetBellScheduleResponse,
    ListBellSchedulesRequest,
    ListBellSchedulesResponse,
)
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.models import BellSchedule as ScheduleRow
from app.models import SchoolClass
from app.rpc import values
from app.rpc.errors import Refusal
from app.services.manage import bells as bells_service

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.rpc.call import Call


def _message(schedule: ScheduleRow, school_class: SchoolClass) -> BellSchedule:
    return BellSchedule(
        id=schedule.id,
        name=schedule.name,
        is_default=schedule.id == school_class.bell_schedule_id,
        periods=[
            BellPeriod(
                index=row.index,
                starts_at=values.time_string(row.starts_at),
                ends_at=values.time_string(row.ends_at),
            )
            for row in sorted(schedule.periods, key=lambda row: row.index)
        ],
    )


async def _schedule(
    session: AsyncSession, school_class: SchoolClass, schedule_id: int
) -> ScheduleRow:
    """This class's schedule ``schedule_id``, or ``RESOURCE_NOT_FOUND``: an id
    of another class's schedule finds nothing, as in v1."""
    schedule = await bells_service.schedule_of(session, school_class.id, schedule_id)
    if schedule is None:
        raise Refusal(
            ErrorReason.RESOURCE_NOT_FOUND,
            wording.UNKNOWN_BELL_SCHEDULE_DETAIL,
            resource="bell_schedule",
        )
    return schedule


async def list_bell_schedules(
    call: Call, request: ListBellSchedulesRequest
) -> ListBellSchedulesResponse:
    """Every schedule the class keeps, in the order they were made. Writes nothing."""
    _admin, school_class = call.device_and_class()
    rows = await bells_service.schedules_of(call.session, school_class.id)
    return ListBellSchedulesResponse(schedules=[_message(row, school_class) for row in rows])


async def get_bell_schedule(
    call: Call, request: GetBellScheduleRequest
) -> GetBellScheduleResponse:
    """One schedule, by its id, with its rows in order. Writes nothing."""
    _admin, school_class = call.device_and_class()
    schedule = await _schedule(call.session, school_class, request.schedule_id)
    return GetBellScheduleResponse(schedule=_message(schedule, school_class))
