"""``MeService``: what a phone does for itself.

3a serves ``GetMe``; the other eight methods are 3b's.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.contract.lessons.v2.me_pb import GetMeRequest, GetMeResponse, Me
from app.rpc import values
from app.services.linking import Access

if TYPE_CHECKING:
    from app.rpc.call import Call


async def get_me(call: Call, request: GetMeRequest) -> GetMeResponse:
    """Who this device is. Mints nothing: v1's ``/me`` issued a link code as a
    side effect, which ``CreateLinkCode`` does in v2 (decision 10). The role is
    the one the gate read for this call."""
    device, _school_class = call.device_and_class()
    access = Access.of(device, call.role)
    return GetMeResponse(
        me=Me(
            device_name=device.device_name,
            linked=access.linked,
            role=values.role(access.role),
            can_edit=access.can_edit,
        )
    )
