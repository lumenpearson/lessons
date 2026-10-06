"""``ClassDeviceService``: the phones on the class, as «📱 Устройства» lists them.

v1's ``/manage/devices``, over the same services: ``linking.devices_of``,
``devices_service.owner_roles``, ``revoke`` and ``unlink``. A role is looked
up, never stored, so a phone revoked in the bot has changed this list already.
What v2 adds is ``client_version``, the app version a phone last sent to v2's
gate (``docs/specs/2026-10-05-server-v2-design.md``, decision 15), and every
stamp is an instant rather than the class's wall time.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app import wording
from app.contract.lessons.v2.class_device_pb import (
    ClassDevice,
    ListClassDevicesRequest,
    ListClassDevicesResponse,
    RevokeClassDeviceRequest,
    RevokeClassDeviceResponse,
    UnlinkClassDeviceRequest,
    UnlinkClassDeviceResponse,
)
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.models import DeviceToken, Role, SchoolClass
from app.rpc import values
from app.rpc.errors import Refusal
from app.services import linking
from app.services.manage import classes as classes_service
from app.services.manage import devices as devices_service

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.rpc.call import Call


def _message(device: DeviceToken, names: dict[int, str], role: Role | None) -> ClassDevice:
    return ClassDevice(
        id=device.id,
        device_name=device.device_name,
        linked=device.is_linked,
        # A display name: the @username, else the name Telegram gave, else the
        # numeric id, as the bot shows an admin (``classes.display_name``).
        owner=names.get(device.telegram_id) if device.telegram_id is not None else None,
        role=values.role(role),
        revoked=device.revoked,
        created_at=values.maybe_instant(device.created_at),
        last_seen_at=values.maybe_instant(device.last_seen_at),
        linked_at=values.maybe_instant(device.linked_at),
        client_version=device.client_version,
    )


async def _device(
    session: AsyncSession, school_class: SchoolClass, device_id: int
) -> DeviceToken:
    """This class's phone ``device_id``, revoked or not, or ``RESOURCE_NOT_FOUND``:
    an id of another class's phone finds nothing, as in v1."""
    device = await devices_service.device_of(session, school_class.id, device_id)
    if device is None:
        raise Refusal(
            ErrorReason.RESOURCE_NOT_FOUND, wording.UNKNOWN_DEVICE_DETAIL, resource="device"
        )
    return device


async def list_class_devices(
    call: Call, request: ListClassDevicesRequest
) -> ListClassDevicesResponse:
    """The phones on the class's list, oldest first; revoked ones only when asked."""
    _admin, school_class = call.device_and_class()
    devices = await linking.devices_of(
        call.session, school_class.id, include_revoked=request.include_revoked
    )
    names = await classes_service.member_names(call.session, school_class.id)
    # Once per owner, not once per phone, as v1's list and the bot's page do.
    roles = await devices_service.owner_roles(call.session, devices)
    return ListClassDevicesResponse(
        devices=[_message(device, names, roles.get(device.telegram_id)) for device in devices]
    )


async def revoke_class_device(
    call: Call, request: RevokeClassDeviceRequest
) -> RevokeClassDeviceResponse:
    """Switch a phone off for good: revoked, not deleted, so its token goes on failing.

    A repeat changes nothing, logs nothing and answers the revoked phone again,
    so a retried request lands on the first one's answer. An admin may revoke
    the phone in their hand, whose next call is ``DEVICE_TOKEN_INVALID``. The
    role is read before the revoke, as v1 reads it.
    """
    admin, school_class = call.device_and_class()
    device = await _device(call.session, school_class, request.device_id)
    names = await classes_service.member_names(call.session, school_class.id)
    role = await linking.effective_role(call.session, device)
    await devices_service.revoke(call.session, school_class.id, admin.telegram_id, device)
    return RevokeClassDeviceResponse(device=_message(device, names, role))


async def unlink_class_device(
    call: Call, request: UnlinkClassDeviceRequest
) -> UnlinkClassDeviceResponse:
    """Back to read-only, keeping the phone in the class. A phone no account is
    behind is ``CLASS_DEVICE_NOT_LINKED``: the admin expected something to change."""
    admin, school_class = call.device_and_class()
    device = await _device(call.session, school_class, request.device_id)
    await devices_service.unlink(call.session, school_class.id, admin.telegram_id, device)
    names = await classes_service.member_names(call.session, school_class.id)
    role = await linking.effective_role(call.session, device)
    return UnlinkClassDeviceResponse(device=_message(device, names, role))
