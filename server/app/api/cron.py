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
from sqlalchemy import delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
from app.db import SessionLocal, get_session
from app.models import JoinAttempt
from app.schemas import TickOut
from app.services import reminders

router = APIRouter(prefix="/api/v1", tags=["cron"])

# Failed join attempts older than the throttle's window count for nothing.
# The limiter prunes them itself on every failure it records; this sweep is
# for the quiet weeks in which nobody mistypes a code and nothing prunes.
JOIN_ATTEMPT_TTL = timedelta(hours=1)


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
    return result.rowcount or 0


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
    return TickOut(**counts, fsm_purged=fsm_purged, join_attempts_purged=join_purged)


# GitHub's scheduler and a curl from a terminal both speak GET; a cron
# service that insists on POST gets the same handler.
router.add_api_route("/cron/tick", tick, methods=["GET", "POST"], response_model=TickOut)
