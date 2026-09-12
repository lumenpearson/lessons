"""Attaching a phone to a Telegram account.

A device token on its own is read-only (see ``app.security``). To let the app
write, its owner sends the bot the short code the app shows; from then on the
device acts with whatever role that account holds in the class, looked up at
request time by :func:`effective_role`. Nothing here stores a role: revoking
someone in the bot revokes their phone in the same instant, and there is no
second permission system to keep in step.
"""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.roles import get_role
from app.models import DeviceToken, Role
from app.security import new_join_code

#: Six characters, not the eight of a join code. A link code is single-use,
#: only ever matched against devices that are not yet linked, and a correct
#: guess gains the guesser nothing: it attaches *their* account to a stranger's
#: phone, which then writes with the guesser's own role - and the owner sees
#: the wrong name on the link screen. Six is what fits a glance at the app.
LINK_CODE_LENGTH = 6

# How many random codes to try before giving up. The space is 32**6, so a
# collision is rare and ten in a row means something else is broken.
_ATTEMPTS = 10


def _utcnow() -> datetime:
    """Naive UTC, matching the naive ``DateTime`` columns the model declares."""
    return datetime.now(UTC).replace(tzinfo=None)


async def issue_link_code(session: AsyncSession, device: DeviceToken) -> str:
    """The code the app shows. Stable until used: asking twice returns the same one.

    The app polls its own status while the link screen is open, and a code
    that changed on every poll would be unreadable.
    """
    if device.link_code:
        return device.link_code

    for _ in range(_ATTEMPTS):
        code = new_join_code(LINK_CODE_LENGTH)
        taken = await session.scalar(select(DeviceToken.id).where(DeviceToken.link_code == code))
        if taken is not None:
            continue
        device.link_code = code
        try:
            await session.commit()
        except IntegrityError:
            # Two invocations drew the same code between the check and the
            # commit; the unique index caught it. Draw again.
            await session.rollback()
            await session.refresh(device)
            continue
        return code
    raise RuntimeError("could not find a free link code")


async def link_device(session: AsyncSession, code: str, telegram_id: int) -> DeviceToken | None:
    """Claim the device showing ``code`` for ``telegram_id``.

    ``None`` for an unknown, used or revoked code - the same answer for all
    three on purpose, so the reply cannot be used to tell whether a code was
    ever real.
    """
    # Codes are minted upper-case from an alphabet without ambiguous glyphs,
    # so upper-casing the input is the whole of "case-insensitive" and the
    # unique index on the column still serves the lookup.
    normalised = code.strip().upper()
    if not normalised:
        return None

    device = await session.scalar(
        select(DeviceToken).where(
            DeviceToken.link_code == normalised,
            DeviceToken.revoked.is_(False),
            DeviceToken.telegram_id.is_(None),
        )
    )
    if device is None:
        return None

    device.telegram_id = telegram_id
    device.linked_at = _utcnow()
    # Cleared, not kept: a code that stays on a linked row could be typed
    # again by somebody else and silently re-home the phone.
    device.link_code = None
    await session.commit()
    return device


async def unlink_device(session: AsyncSession, device: DeviceToken) -> None:
    """Back to read-only. A fresh code is issued on the next request."""
    device.telegram_id = None
    device.linked_at = None
    device.link_code = None
    await session.commit()


async def devices_of(
    session: AsyncSession, class_id: int, include_revoked: bool = False
) -> list[DeviceToken]:
    """Oldest first, so the numbering in a list does not shuffle as phones join."""
    query = select(DeviceToken).where(DeviceToken.class_id == class_id)
    if not include_revoked:
        query = query.where(DeviceToken.revoked.is_(False))
    return list(await session.scalars(query.order_by(DeviceToken.id)))


async def effective_role(session: AsyncSession, device: DeviceToken) -> Role | None:
    """What the linked account may do in the device's class, right now.

    Looked up on every call rather than cached on the row: the role can be
    changed or revoked in the bot at any moment and the phone has to follow.
    Whether the device itself is revoked is the caller's check - the API
    refuses a revoked token before it ever gets here.
    """
    if device.telegram_id is None:
        return None
    return await get_role(session, device.telegram_id, device.class_id)
