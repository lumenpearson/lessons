"""The Telegram webhook endpoint.

Used instead of long polling wherever the deployment cannot keep a process
alive, which is every serverless platform. The bot is otherwise identical: the
same dispatcher, the same routers, the same database-backed FSM storage.
"""

from __future__ import annotations

import logging

from aiogram import Bot, Dispatcher
from aiogram.types import Update
from fastapi import APIRouter, Header, HTTPException, Request, status

from app.config import get_settings

log = logging.getLogger(__name__)

router = APIRouter(tags=["telegram"])

# Built once per process and reused. On a warm serverless instance this is the
# difference between one dispatcher and one per update.
_bot: Bot | None = None
_dispatcher: Dispatcher | None = None


def _instances() -> tuple[Bot, Dispatcher]:
    global _bot, _dispatcher
    if _bot is None or _dispatcher is None:
        from app.bot.bot import build_bot, build_dispatcher

        _bot = build_bot()
        _dispatcher = build_dispatcher()
    return _bot, _dispatcher


async def handle_update(payload: dict, secret_header: str | None) -> None:
    """Verify the caller, then feed the update to the dispatcher.

    Separated from the route so it can be tested without an HTTP layer.
    """
    settings = get_settings()

    if not settings.webhook_enabled:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Webhook is not configured"
        )

    # Telegram echoes the secret we registered with setWebhook. Without this
    # check the URL is an open endpoint that accepts a forged update carrying
    # any user id - and the bot authorises by user id, so that is a complete
    # takeover, not merely spam.
    if not secret_header or not _constant_time_equals(secret_header, settings.webhook_secret):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Bad secret token")

    bot, dispatcher = _instances()
    update = Update.model_validate(payload, context={"bot": bot})
    await dispatcher.feed_update(bot, update)


def _constant_time_equals(left: str, right: str) -> bool:
    from hmac import compare_digest

    return compare_digest(left.encode("utf-8"), right.encode("utf-8"))


@router.post("/telegram/webhook")
async def telegram_webhook(
    request: Request,
    x_telegram_bot_api_secret_token: str | None = Header(default=None),
) -> dict[str, bool]:
    """Receive one update from Telegram.

    Always answers 200 once the secret checks out, even if a handler raised.
    Telegram retries non-2xx responses with backoff and eventually disables the
    webhook, so a single bad message must not take the whole bot down; the
    failure goes to the log instead.
    """
    try:
        payload = await request.json()
    except Exception:  # noqa: BLE001 - any malformed body is the same answer
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Body is not JSON"
        ) from None

    try:
        await handle_update(payload, x_telegram_bot_api_secret_token)
    except HTTPException:
        raise
    except Exception:  # noqa: BLE001 - see the docstring
        log.exception("Telegram update failed")

    return {"ok": True}
