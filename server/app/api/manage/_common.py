"""What every endpoint of :mod:`app.api.manage` shares: who is asking, and
the few conversions from a row to what goes on the wire.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import UTC, datetime

from dishka.integrations.fastapi import FromDishka, inject
from fastapi import Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_device
from app.models import DeviceToken, Role, SchoolClass
from app.services import linking
from app.services.manage import classes as classes_service

# --------------------------------------------------------------------------
# Who may manage
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Actor:
    """Who is making the change: the device, the Telegram account behind it,
    and that account's role in the class *right now*."""

    device: DeviceToken
    telegram_id: int
    role: Role


def _role_at_least(minimum: Role) -> Callable[..., Awaitable[Actor]]:
    """A dependency demanding ``minimum`` of the linked account.

    One factory rather than a guard written out in each endpoint, for the
    reason the bot keeps ``_allowed`` in one place: a check that is copied is a
    check that will eventually be copied wrong. The refusals are the two the
    app already knows from ``app.api.edit`` - «привяжите телефон» and «нужна
    роль» - with the role named, because the app shows a different screen for
    each and an admin-only page has to say «admin», not «editor».
    """

    @inject
    async def dependency(
        device: DeviceToken = Depends(current_device),
        *,
        session: FromDishka[AsyncSession],
    ) -> Actor:
        if device.telegram_id is None:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN, detail="device is not linked"
            )
        role = await linking.effective_role(session, device)
        if role is None or not role.at_least(minimum):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN, detail=f"{minimum.value} role required"
            )
        return Actor(device=device, telegram_id=device.telegram_id, role=role)

    return dependency


editor_actor = _role_at_least(Role.EDITOR)
admin_actor = _role_at_least(Role.ADMIN)
owner_actor = _role_at_least(Role.OWNER)


# --------------------------------------------------------------------------
# Small shared pieces
# --------------------------------------------------------------------------


def _wall(stamp: datetime | None, school_class: SchoolClass) -> datetime | None:
    """A stored UTC stamp on the class's own wall clock.

    ``created_at`` and friends are naive UTC in the database. An admin in
    Vladivostok reading a Moscow server's log should not see yesterday evening
    against this morning's change, so they are converted here the same way the
    bot's ``render_audit`` converts them.
    """
    if stamp is None:
        return None
    return stamp.replace(tzinfo=UTC).astimezone(school_class.tz).replace(tzinfo=None)


def _person(full_name: str | None, username: str | None, telegram_id: int | None) -> str:
    """The best name we hold for somebody, in the same order the bot picks it.

    Falls back to the numeric id rather than to «неизвестный»: an id is
    something an admin can actually act on, and this surface is admin-only.
    """
    if username:
        return f"@{username}"
    if full_name:
        return full_name
    return str(telegram_id) if telegram_id is not None else "—"


async def _member_names(session: AsyncSession, class_id: int) -> dict[int, str]:
    """Telegram id -> the name to show. Plain text: this is JSON, not HTML."""
    members = await classes_service.members(session, class_id)
    return {
        member.telegram_id: _person(member.full_name, member.username, member.telegram_id)
        for member in members
    }


def _conflict(detail: str) -> HTTPException:
    """409, for a request that is well-formed and refused by the class's own
    state: a name already taken, a schedule days still point at."""
    return HTTPException(status_code=status.HTTP_409_CONFLICT, detail=detail)
