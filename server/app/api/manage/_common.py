"""What every endpoint of :mod:`app.api.manage` shares: who is asking, and
the refusal the class's own state makes.

The member names and the class's wall clock lived here too, until v2 needed
them (``docs/specs/2026-10-05-server-v2-design.md``, decision 2): they are
``services/manage/classes.member_names`` and ``services/clock.wall`` now.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass

from dishka.integrations.fastapi import FromDishka, inject
from fastapi import Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_device
from app.models import DeviceToken, Role
from app.services import linking

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


def _conflict(detail: str) -> HTTPException:
    """409, for a request that is well-formed and refused by the class's own
    state: a name already taken, a schedule days still point at."""
    return HTTPException(status_code=status.HTTP_409_CONFLICT, detail=detail)
