"""The reminder tick.

A serverless deployment has nothing that runs on its own, so the morning and
evening digests are sent by whoever calls this endpoint - a GitHub Actions
schedule in this repository, every five minutes. The endpoint does what a
scheduler thread would: work out what is due from the class clocks, send it,
and sweep the tables that grow between calls. It is safe to call twice in a
minute and safe to call an hour late; ``reminders.send_due`` is built for
both.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from hmac import compare_digest
from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException, status
from sqlalchemy import delete, func, or_
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
from app.db import SessionLocal, get_session, rows_affected
from app.models import DeviceToken, DiarySession, JoinAttempt
from app.schemas import TickOut
from app.services import device_invites, diary_link, reminders

router = APIRouter(prefix="/api/v1", tags=["cron"])

# Failed join attempts older than the throttle's window count for nothing.
# The limiter prunes them itself on every failure it records; this sweep is
# for the quiet weeks in which nobody mistypes a code and nothing prunes.
JOIN_ATTEMPT_TTL = timedelta(hours=1)

# A diary session nobody has used for a month is gone, and so is the upstream
# bearer token inside it. Nothing else ever removes one: signing out drops a
# row, but a phone that is reinstalled, lost or simply never opened again
# leaves its session behind, and that row holds a live credential for somebody
# else's service. The upstream's own token will not have outlived the month
# either, so the only thing thrown away is a string that no longer opens
# anything.
DIARY_SESSION_TTL = timedelta(days=30)

# One that the upstream has already refused is kept a day, no more: long enough
# for the app still holding our token to come back and be told to sign in
# again, rather than be handed a 401 it cannot explain.
DIARY_EXPIRED_TTL = timedelta(days=1)

# A phone that has not asked for the timetable in half a year is gone, and its
# token with it. Every ``POST /join`` mints a row, so a pupil who reinstalls the
# app, clears its data or re-enters the code leaves the old one behind - live,
# able to read the class for as long as the class exists, and on the admin's
# «📱 Устройства» page forever. Nothing else removes one: ``revoked`` is
# deliberately a tombstone rather than a delete, and a lost phone is revoked by
# hand, not swept.
#
# Half a year, because the quiet stretch this must not cut through is каникулы:
# a pupil who does not open the app between the end of May and September is
# silent for about a hundred days, and being signed out in September - by a
# rule they cannot see, needing a code an admin has to hand out again - is a
# far worse failure than a row too many. Anything actually in use is touched at
# least every fifteen minutes it is read (``deps._touch_last_seen``).
DEVICE_TOKEN_TTL = timedelta(days=180)


def _build_bot() -> Any:
    """Imported lazily: aiogram costs seconds to import, and the module is
    imported by ``app.main`` on every cold start of every endpoint."""
    from app.bot.bot import build_bot

    return build_bot()


async def _close_bot(bot: Any) -> None:
    bot_session = getattr(bot, "session", None)
    if bot_session is not None:
        await bot_session.close()


async def _purge_fsm() -> int:
    from app.fsm_storage import DatabaseStorage

    return await DatabaseStorage(SessionLocal).purge_stale()


async def _purge_join_attempts(session: AsyncSession) -> int:
    cutoff = datetime.now(UTC).replace(tzinfo=None) - JOIN_ATTEMPT_TTL
    result = await session.execute(delete(JoinAttempt).where(JoinAttempt.created_at <= cutoff))
    await session.commit()
    return rows_affected(result)


async def _purge_diary_sessions(session: AsyncSession) -> int:
    """Drop the sessions that are dead and the ones that have gone quiet.

    A session with no ``last_used_at`` at all is judged by ``created_at``,
    which every row has: one created and never used again is exactly the case
    the sweep is for.
    """
    now = datetime.now(UTC).replace(tzinfo=None)
    result = await session.execute(
        delete(DiarySession).where(
            or_(
                DiarySession.expired_at <= now - DIARY_EXPIRED_TTL,
                func.coalesce(DiarySession.last_used_at, DiarySession.created_at)
                <= now - DIARY_SESSION_TTL,
            )
        )
    )
    await session.commit()
    return rows_affected(result)


async def _purge_device_tokens(session: AsyncSession) -> int:
    """Drop the phones that stopped asking. See [DEVICE_TOKEN_TTL].

    Judged by ``created_at`` when a device never came back at all after
    joining, which is the commonest stale row there is: a code typed into a
    phone that was then handed to somebody else.
    """
    cutoff = datetime.now(UTC).replace(tzinfo=None) - DEVICE_TOKEN_TTL
    result = await session.execute(
        delete(DeviceToken).where(
            func.coalesce(DeviceToken.last_seen_at, DeviceToken.created_at) <= cutoff
        )
    )
    await session.commit()
    return rows_affected(result)


async def tick(
    x_cron_secret: str | None = Header(default=None),
    session: AsyncSession = Depends(get_session),
) -> TickOut:
    """Verify the caller, then run one tick.

    Mirrors the webhook: unset secret means the endpoint does not exist,
    because a tick anybody can trigger is a way to make the bot message every
    subscriber at will. The comparison is constant-time for the same reason
    the webhook's is.
    """
    settings = get_settings()
    if not settings.cron_secret:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Cron is not configured")
    if not x_cron_secret or not compare_digest(
        x_cron_secret.encode("utf-8"), settings.cron_secret.encode("utf-8")
    ):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Bad cron secret")
    if not settings.bot_token:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Bot token is not configured"
        )

    bot = _build_bot()
    try:
        counts = await reminders.send_due(session, bot, datetime.now(UTC))
    finally:
        await _close_bot(bot)

    fsm_purged = await _purge_fsm()
    join_purged = await _purge_join_attempts(session)
    diary_purged = await _purge_diary_sessions(session)
    device_purged = await _purge_device_tokens(session)
    # Sign-in tickets. Swept here for the same reason as everything above it:
    # on Vercel nothing runs between requests, so whatever gets cleaned up is
    # cleaned up by something arriving from outside.
    links_purged = await diary_link.purge(session)
    # The personal join codes of a class in «по приглашению». Same reason again:
    # they expire in fifteen minutes and nothing would ever come back for them.
    invites_purged = await device_invites.prune(session)
    return TickOut(
        **counts,
        fsm_purged=fsm_purged,
        join_attempts_purged=join_purged,
        diary_sessions_purged=diary_purged,
        device_tokens_purged=device_purged,
        diary_links_purged=links_purged,
        device_invites_purged=invites_purged,
    )


# GitHub's scheduler and a curl from a terminal both speak GET; a cron
# service that insists on POST gets the same handler.
router.add_api_route("/cron/tick", tick, methods=["GET", "POST"], response_model=TickOut)
