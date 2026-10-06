"""``BellService``: the class's bell schedules, as «🔔 Звонки» edits them.

v1's ``/manage/bells``, over the same services, with the class default
marked and each time ``"HH:MM"`` where v1 wrote seconds. ``GetBellSchedule``
reads one schedule by its id, which v1 had no endpoint for; an id of another
class's schedule finds nothing, as in v1.

v1 changed one schedule two ways, ``PATCH`` for the name and the default and
``PUT …/periods`` for the rows. v2 has one ``UpdateBellSchedule``, its mask
read by ``masks.update_paths``, and the patch is the one v1 applies
(``bells_service.update``): the rename, then the rows, then the default, so
``silenced_lessons`` counts once what the request stopped ringing. Every
field is checked by v1's own schema, so the two versions refuse the same
requests; the error table words each refusal in v1's words.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from app import wording
from app.contract.lessons.v2.bell_pb import (
    BellPeriod,
    BellSchedule,
    CreateBellScheduleRequest,
    CreateBellScheduleResponse,
    DeleteBellScheduleRequest,
    DeleteBellScheduleResponse,
    GetBellScheduleRequest,
    GetBellScheduleResponse,
    ListBellSchedulesRequest,
    ListBellSchedulesResponse,
    UpdateBellScheduleRequest,
    UpdateBellScheduleResponse,
)
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.models import BellSchedule as ScheduleRow
from app.models import SchoolClass
from app.rpc import values
from app.rpc.errors import Refusal, validate
from app.rpc.masks import update_paths
from app.schemas import BellPeriodsIn, BellScheduleIn, BellSchedulePatch
from app.services.manage import bells as bells_service

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.rpc.call import Call

#: What ``update_mask`` may name, and nothing more. The order a patch is
#: applied in is not this tuple's: ``bells_service.update`` decides it, the
#: rename, then the rows, then the default (Ruling 21).
CHANGEABLE = ("name", "periods", "is_default")


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


def _rows(sent: BellSchedule) -> list[dict[str, Any]]:
    """The request's rows as v1's ``BellPeriodIn`` reads them, which parses
    each ``"HH:MM"`` and refuses a lesson that ends before it starts."""
    return [
        {"index": row.index, "starts_at": row.starts_at, "ends_at": row.ends_at}
        for row in sent.periods
    ]


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


async def create_bell_schedule(
    call: Call, request: CreateBellScheduleRequest
) -> CreateBellScheduleResponse:
    """A new named schedule, with its rows if they came with it, checked by v1's
    ``BellScheduleIn``. It does not become the default, whatever the request
    says of ``is_default``, and the id a client sends is ignored; REST answers
    201."""
    admin, school_class = call.device_and_class()
    sent = request.schedule if request.schedule is not None else BellSchedule()
    form = validate(BellScheduleIn, {"name": sent.name, "periods": _rows(sent)}, at="schedule.")
    schedule = await bells_service.create(
        call.session,
        school_class,
        admin.telegram_id,
        form.name,
        [(row.index, row.starts_at, row.ends_at) for row in form.periods],
    )
    # The rows were added after `create`'s own flush, and the collection was
    # never loaded: flushed and read back here, committed by `invoke`.
    await call.session.flush()
    await call.session.refresh(schedule, ["periods"])
    return CreateBellScheduleResponse(schedule=_message(schedule, school_class))


async def update_bell_schedule(
    call: Call, request: UpdateBellScheduleRequest
) -> UpdateBellScheduleResponse:
    """Rename a schedule, replace its rows, or make it the class default.

    The mask is read once, by ``masks.update_paths``. The name and the default
    are checked by v1's ``BellSchedulePatch``, which refuses a blank name; the
    rows by v1's ``BellPeriodsIn``, which refuses none at all, so a masked
    ``periods`` left out is refused rather than emptying the schedule. A
    masked ``is_default`` left out is false, which the patch refuses on its
    field (``DefaultRequired``): make another schedule the default instead.
    """
    admin, school_class = call.device_and_class()
    sent = request.schedule if request.schedule is not None else BellSchedule()
    paths = update_paths(request.update_mask, request.schedule, CHANGEABLE)
    changes: dict[str, Any] = {}
    if "name" in paths or "is_default" in paths:
        patch = validate(
            BellSchedulePatch,
            {field: getattr(sent, field) for field in ("name", "is_default") if field in paths},
            at="schedule.",
        )
        changes.update(patch.model_dump(exclude_unset=True))
    if "periods" in paths:
        rows = validate(BellPeriodsIn, {"periods": _rows(sent)}, at="schedule.")
        changes["periods"] = [(row.index, row.starts_at, row.ends_at) for row in rows.periods]
    schedule = await _schedule(call.session, school_class, sent.id)
    silenced = await bells_service.update(
        call.session, school_class, admin.telegram_id, schedule, changes
    )
    # New rows went in by a bulk delete the collection never saw.
    await call.session.flush()
    await call.session.refresh(schedule, ["periods"])
    return UpdateBellScheduleResponse(
        schedule=_message(schedule, school_class), silenced_lessons=len(silenced)
    )


async def delete_bell_schedule(
    call: Call, request: DeleteBellScheduleRequest
) -> DeleteBellScheduleResponse:
    """Delete a schedule nothing depends on. The class default, and a schedule
    special days point at, are ``RESOURCE_IN_USE``: deleting either would move
    those days onto another schedule nobody chose."""
    admin, school_class = call.device_and_class()
    schedule = await _schedule(call.session, school_class, request.schedule_id)
    await bells_service.delete(call.session, school_class, admin.telegram_id, schedule)
    return DeleteBellScheduleResponse()
