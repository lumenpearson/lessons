"""``DeviceService``: a code in, the device token out. v1's ``POST /join``."""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.contract.lessons.v2.device_pb import (
    CreateDeviceRequest,
    CreateDeviceResponse,
    DiaryBinding,
)
from app.providers.diary.registry import binding
from app.rpc.errors import validate
from app.schemas import JoinRequest
from app.services import join

if TYPE_CHECKING:
    from app.rpc.call import Call


async def create_device(call: Call, request: CreateDeviceRequest) -> CreateDeviceResponse:
    """Exchange a code for a long-lived device token, by v1's flow.

    Validated with v1's own ``JoinRequest``, so the two versions refuse the
    same codes (4 to 16 usable characters, a name up to 120). Then
    ``services/join.py``, over ``security``'s one ``join_limiter`` and the
    caller's bucket under v1's scope, so v1 and v2 draw on one budget
    (decision 11); its refusals are worded by ``rpc/errors.py``'s table. The
    service commits, as v1's endpoint did: a wrong code stays counted whatever
    this call then rolls back.
    """
    form = validate(
        JoinRequest,
        {
            "code": request.code,
            "device_name": request.device_name if request.has_field("device_name") else None,
        },
    )
    joined = await join.join(
        call.session, code=form.code, device_name=form.device_name, client_key=call.bucket()
    )
    school_class = joined.school_class
    bound = binding(school_class)
    return CreateDeviceResponse(
        token=joined.token,
        class_id=school_class.id,
        class_name=school_class.name,
        school=school_class.school,
        timezone=school_class.timezone_name,
        diary=DiaryBinding(
            provider=bound.provider.key,
            region=bound.region,
            school_id=bound.school_id,
            school_name=bound.school_name,
        )
        if bound is not None
        else None,
    )
