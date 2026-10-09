"""Attaching a phone to a Telegram account.

A device token on its own is read-only (see ``app.security``). To let the app
write, its owner sends the bot the short code the app shows; from then on the
device acts with whatever role that account holds in the class, looked up at
request time by :func:`effective_role`. Nothing here stores a role: revoking
someone in the bot revokes their phone in the same instant, and there is no
second permission system to keep in step.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import DeviceToken, Role
from app.security import new_join_code
from app.services.roles import get_role

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
    that changed on every poll would be unreadable. Nothing is committed: the
    caller commits before it shows the code, because a code the database
    never kept links nothing.
    """
    if device.link_code:
        return device.link_code

    for _ in range(_ATTEMPTS):
        code = new_join_code(LINK_CODE_LENGTH)
        taken = await session.scalar(select(DeviceToken.id).where(DeviceToken.link_code == code))
        if taken is not None:
            continue
        try:
            async with session.begin_nested():
                device.link_code = code
                await session.flush()
        except IntegrityError:
            # Two invocations drew the same code between the check and the
            # write; the unique index caught it. Only the savepoint is rolled
            # back, so the caller's transaction goes on; the row is read
            # again, because the rollback expired what this write changed.
            # Draw again.
            await session.refresh(device)
            continue
        return code
    raise RuntimeError("could not find a free link code")


async def link_code_for(session: AsyncSession, device: DeviceToken) -> str | None:
    """The code this phone shows to be linked, or ``None`` for a phone that is
    linked already: a code left on a linked row could be typed by somebody
    else and re-home the phone. v1's ``GET /me`` and v2's ``CreateLinkCode``.
    Nothing is committed."""
    if device.is_linked:
        return None
    return await issue_link_code(session, device)


def deep_link(bot_username: str, code: str) -> str | None:
    """``https://t.me/<bot>?start=link_<code>``: the bot opened with the code
    already in it, which its ``/start link_<code>`` reads. ``None`` when the
    deployment names no bot (``BOT_USERNAME``), written with its «@» or not."""
    username = bot_username.lstrip("@")
    return f"https://t.me/{username}?start=link_{code}" if username else None


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
    """Back to read-only. A fresh code is issued on the next request.

    Leaves the transaction open, unlike :func:`link_device`, because one of
    its two callers, ``services/manage/devices.py``'s ``unlink``, writes an
    audit line straight afterwards, and the line has to land with the unlink
    or not at all. Committing here made "phone unlinked, nothing in the log"
    a possible outcome of one failed insert - and the log is the only place an
    admin can see that somebody else did it. The other caller,
    :func:`unlink_self`, writes no audit line: the phone did it to itself.
    """
    device.telegram_id = None
    device.linked_at = None
    device.link_code = None


async def unlink_self(session: AsyncSession, device: DeviceToken) -> None:
    """Back to read-only, at the phone's own asking: v1's ``POST /me/unlink``
    and v2's ``UnlinkMe``.

    A phone no account is behind changes nothing, its link code included,
    which :func:`unlink_device` would clear; so asking twice is not an error,
    and the second time writes nothing. No audit line: the phone did it to
    itself, and v1 wrote none. Nothing is committed.
    """
    if device.is_linked:
        await unlink_device(session, device)


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


@dataclass(frozen=True)
class Access:
    """What a device may do in its class, as a phone is told it.

    v1's ``/bundle`` and ``/me`` and v2's ``DeviceAccess`` and ``Me`` all
    answer these three facts, and «may edit» is a rule rather than a field:
    editor or above. One copy of it, here, because a second copy in a v2
    handler is the copy that would one day say «admin».
    """

    linked: bool
    #: ``None`` for an unlinked device, and for a linked account that is not a
    #: member of the class.
    role: Role | None

    @property
    def can_edit(self) -> bool:
        return self.role is not None and self.role.at_least(Role.EDITOR)

    @classmethod
    def of(cls, device: DeviceToken, role: Role | None) -> Access:
        return cls(linked=device.is_linked, role=role)


async def access_of(session: AsyncSession, device: DeviceToken) -> Access:
    """The device's :class:`Access`, with its role read now, as every request does."""
    return Access.of(device, await effective_role(session, device))
