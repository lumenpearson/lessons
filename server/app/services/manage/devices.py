"""«📱 Устройства» and ``/manage/devices``: the phones on a class.

A device token is read-only until its owner links it to a Telegram account;
from then on it writes with whatever role that account holds *right now*
(:func:`app.services.linking.effective_role`). Which is why a role is a lookup
and never something stored on the row: revoking somebody in «Доступ» has
already revoked their phone by the time a list of phones is drawn.
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import DeviceToken, Role
from app.services import audit, linking


class DeviceNotLinked(Exception):
    """There is no account behind the phone, so there is nothing to unlink."""


def label(device: DeviceToken) -> str:
    """What the log and the answers call a phone that never gave its name."""
    return device.device_name or f"Устройство {device.id}"


async def device_of(session: AsyncSession, class_id: int, device_id: int) -> DeviceToken | None:
    """Scoped by the query: another class's device id finds nothing."""
    return await session.scalar(
        select(DeviceToken).where(DeviceToken.id == device_id, DeviceToken.class_id == class_id)
    )


async def owner_roles(
    session: AsyncSession, devices: list[DeviceToken]
) -> dict[int, Role | None]:
    """Each linked owner's role right now, looked up once per owner.

    The role hangs off the account, not the phone, and a class where everybody
    joined from their own phone was paying a round trip a row for an answer
    already in hand.
    """
    roles: dict[int, Role | None] = {}
    for device in devices:
        if device.telegram_id is not None and device.telegram_id not in roles:
            roles[device.telegram_id] = await linking.effective_role(session, device)
    return roles


async def revoke(
    session: AsyncSession, class_id: int, actor_id: int | None, device: DeviceToken
) -> bool:
    """Switch a phone off. Revoked, not deleted: the row is what a token is
    checked against, and keeping it is what makes the refusal instant and
    permanent.

    A phone already off changes nothing and adds no second line to the log -
    the bot's copy of this used to add one for every press of a stale button.

    @return whether anything changed.
    """
    if device.revoked:
        return False
    device.revoked = True
    await audit.record(
        session, class_id, actor_id, "device.revoke", f"отключено устройство «{label(device)}»"
    )
    return True


async def unlink(
    session: AsyncSession, class_id: int, actor_id: int | None, device: DeviceToken
) -> None:
    """Back to read-only, without taking the phone off the class.

    @raises DeviceNotLinked when nobody's account is behind it: whoever pressed
        it expected something to change, and silence would say it had.
    """
    if device.telegram_id is None:
        raise DeviceNotLinked(label(device))
    name = label(device)
    await linking.unlink_device(session, device)
    await audit.record(
        session,
        class_id,
        actor_id,
        "device.unlink",
        f"отвязано устройство «{name}» — снова только чтение",
    )
