"""``/requests``: the access requests waiting for an admin, and the answer.

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring.
"""

from __future__ import annotations

import logging
from typing import Any

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class
from app.api.manage._common import Actor, _member_names, _person, _wall, admin_actor
from app.api.routing import DishkaAnnotatedRoute
from app.config import get_settings
from app.models import AccessRequest, Role, SchoolClass
from app.schemas import AccessRequestOut, RequestDecisionIn, RequestDecisionOut
from app.services import access as access_service
from app.services.manage import requests as requests_service

log = logging.getLogger(__name__)

router = APIRouter(route_class=DishkaAnnotatedRoute)


def _build_bot() -> Any | None:
    """A bot for one send, or ``None`` when the deployment has no token.

    Imported lazily: aiogram costs seconds to import and a management endpoint
    should not pay that on a deployment that has no bot to notify with. One
    per router module, as in ``app.api.edit`` and ``app.api.cron`` - it is the
    seam the tests replace.
    """
    if not get_settings().bot_token:
        return None
    from app.bot.bot import build_bot

    return build_bot()


async def _tell(telegram_id: int, text: str) -> None:
    """Tell one person what was decided. Never fails the request: the decision
    is already committed, and a Telegram outage does not undo it."""
    bot = _build_bot()
    if bot is None:
        return
    try:
        await bot.send_message(telegram_id, text)
    except Exception:  # noqa: BLE001 - see the docstring
        log.warning("could not tell %s about the decision", telegram_id, exc_info=True)
    finally:
        bot_session = getattr(bot, "session", None)
        if bot_session is not None:
            await bot_session.close()


# --------------------------------------------------------------------------
# 🙋 Access requests
# --------------------------------------------------------------------------


async def _request_or_404(
    session: AsyncSession, school_class: SchoolClass, request_id: int
) -> AccessRequest:
    """Pending, and this class's. A request that has already been answered is
    gone as far as this endpoint is concerned, so two admins tapping «Выдать»
    at once cannot grant twice."""
    request = await requests_service.pending_one(session, school_class.id, request_id)
    if request is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown request")
    return request


@router.get("/requests", response_model=list[AccessRequestOut])
async def requests_list(
    _: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> list[AccessRequestOut]:
    """Everybody waiting for a role, oldest first."""
    rows = await requests_service.pending(session, school_class.id)
    names = await _member_names(session, school_class.id)
    return [
        AccessRequestOut(
            id=row.id,
            who=names.get(row.telegram_id, str(row.telegram_id)),
            requested_role=row.requested_role.value,
            message=row.message,
            created_at=_wall(row.created_at, school_class),
        )
        for row in rows
    ]


@router.post("/requests/{request_id}/approve", response_model=RequestDecisionOut)
async def request_approve(
    request_id: int,
    payload: RequestDecisionIn | None = None,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> RequestDecisionOut:
    """Grant the role, through the same rules as «👥 Доступ» in the bot.

    Literally the same rules: `services/access.approve_request` is what the
    bot's own «✅ Выдать» calls, so the two surfaces cannot drift apart. The
    only thing this one does differently is let an admin answer with a role
    other than the one asked for - which `can_grant` still gates, inside the
    service.

    The requester is told in Telegram, because that is where they asked.
    """
    request = await _request_or_404(session, school_class, request_id)
    target = Role(payload.role) if payload is not None and payload.role else request.requested_role

    try:
        member = await access_service.approve_request(
            session,
            school_class,
            request,
            actor_id=actor.telegram_id,
            actor_role=actor.role,
            role=target,
        )
    except access_service.GrantRefused as refused:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=refused.detail
        ) from refused

    who = _person(member.full_name, member.username, member.telegram_id)
    await session.commit()

    await _tell(request.telegram_id, access_service.approval_notice(school_class, target))
    return RequestDecisionOut(id=request_id, status="approved", role=target.value, who=who)


@router.post("/requests/{request_id}/decline", response_model=RequestDecisionOut)
async def request_decline(
    request_id: int,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> RequestDecisionOut:
    """Say no. The person keeps whatever role they already had, and is told -
    silence would leave them asking again."""
    request = await _request_or_404(session, school_class, request_id)
    names = await _member_names(session, school_class.id)
    who = names.get(request.telegram_id, str(request.telegram_id))

    await requests_service.decline(session, school_class.id, actor.telegram_id, request)
    await session.commit()

    await _tell(request.telegram_id, requests_service.decline_notice(school_class))
    return RequestDecisionOut(id=request_id, status="declined", who=who)
