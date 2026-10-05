"""``/devices``: the phones on the class, as «📱 Устройства» lists them.

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring.
"""

from __future__ import annotations

import logging

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app import wording
from app.api.deps import current_class
from app.api.manage._common import Actor, _conflict, admin_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import DeviceToken, Role, SchoolClass
from app.schemas import ManagedDeviceOut
from app.services import linking
from app.services.clock import wall
from app.services.manage import devices as devices_service
from app.services.manage.classes import member_names

log = logging.getLogger(__name__)

router = APIRouter(route_class=DishkaAnnotatedRoute)


# --------------------------------------------------------------------------
# 📱 Devices
# --------------------------------------------------------------------------


def _device_out(
    device: DeviceToken,
    school_class: SchoolClass,
    names: dict[int, str],
    role: Role | None,
) -> ManagedDeviceOut:
    return ManagedDeviceOut(
        id=device.id,
        device_name=device.device_name,
        linked=device.is_linked,
        owner=names.get(device.telegram_id) if device.telegram_id is not None else None,
        role=role.value if role is not None else None,
        revoked=device.revoked,
        created_at=wall(device.created_at, school_class),
        last_seen_at=wall(device.last_seen_at, school_class),
        linked_at=wall(device.linked_at, school_class),
    )


async def _device_or_404(
    session: AsyncSession, school_class: SchoolClass, device_id: int
) -> DeviceToken:
    device = await devices_service.device_of(session, school_class.id, device_id)
    if device is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=wording.UNKNOWN_DEVICE_DETAIL
        )
    return device


@router.get("/devices", response_model=list[ManagedDeviceOut])
async def devices_list(
    include_revoked: bool = Query(default=False),
    _: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> list[ManagedDeviceOut]:
    """The phones on the class's list, oldest first.

    The role is a lookup, not a stored field: a device acts with whatever role
    its owner holds right now, so revoking somebody in the bot has already
    changed this list by the time it is drawn. ``include_revoked`` brings back
    the ones that were switched off, which the bot's page leaves out.
    """
    devices = await linking.devices_of(session, school_class.id, include_revoked=include_revoked)
    names = await member_names(session, school_class.id)
    # Once per owner, not once per phone, through the function «📱 Устройства»
    # in the bot resolves it with.
    roles = await devices_service.owner_roles(session, devices)
    return [
        _device_out(device, school_class, names, roles.get(device.telegram_id))
        for device in devices
    ]


@router.post("/devices/{device_id}/revoke", response_model=ManagedDeviceOut)
async def device_revoke(
    device_id: int,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> ManagedDeviceOut:
    """Switch a phone off. Revoked, not deleted: the row is what a token is
    checked against, and keeping it is what makes the refusal instant and
    permanent. Revoking an already revoked device changes nothing and adds no
    second line to the log.

    An admin may do this to the phone they are holding, and then the next
    request from it is a 401. That is the point - it is how a lost phone is
    dealt with from the one that is still in a pocket.
    """
    device = await _device_or_404(session, school_class, device_id)
    names = await member_names(session, school_class.id)
    role = await linking.effective_role(session, device)
    if await devices_service.revoke(session, school_class.id, actor.telegram_id, device):
        await session.commit()
    return _device_out(device, school_class, names, role)


@router.post("/devices/{device_id}/unlink", response_model=ManagedDeviceOut)
async def device_unlink(
    device_id: int,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> ManagedDeviceOut:
    """Back to read-only, without taking the phone off the class.

    The device keeps reading the timetable and loses the role it borrowed from
    its owner's account. A device that is not linked has nothing to unlink,
    which is a 409 rather than a silent success: the admin pressed it expecting
    something to change.
    """
    device = await _device_or_404(session, school_class, device_id)
    try:
        await devices_service.unlink(session, school_class.id, actor.telegram_id, device)
    except devices_service.DeviceNotLinked as not_linked:
        raise _conflict(wording.CLASS_DEVICE_NOT_LINKED_DETAIL) from not_linked
    await session.commit()
    names = await member_names(session, school_class.id)
    role = await linking.effective_role(session, device)
    return _device_out(device, school_class, names, role)
