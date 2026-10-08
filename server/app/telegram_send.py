"""Telegram for the code that is not the bot: a bot built for one job, and closed after it.

The bot's package, ``app.bot``, is aiogram's routers, keyboards and renderers,
and the code under it is the only code that may lean on it: ``services/``
never imports ``app.bot`` (``tests/test_service_layering.py``), and the API's
cold start never loads aiogram (``tests/test_cold_start.py``). Yet three things
outside the bot send messages - the reminder tick, the notices v1's writes
send, and the self-check's alerts to the owner (``services/health.py``) - and
each used to build its bot through ``app.bot.bot``. This module is the neutral
place for that. aiogram is imported inside the functions and never at the top,
so importing this module costs a cold start nothing, and ``app.bot.bot``
imports ``build_bot`` back from here.

v2's notices are sent from here, as effects ``rpc/call.py`` runs after the
commit (stage 3b-3). v1's keep their own ``_build_bot`` seams in
``api/manage/requests.py`` and ``api/edit.py``, which build through
``build_bot`` here, so that no v1 test had to change.
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from typing import TYPE_CHECKING, Any

from app.config import get_settings

if TYPE_CHECKING:
    # A name for the annotation, and nothing more: see the module docstring.
    from aiogram import Bot

log = logging.getLogger(__name__)


def build_bot() -> Bot:
    """The bot every sender uses: this deployment's token, and HTML as its parse mode."""
    from aiogram import Bot
    from aiogram.client.default import DefaultBotProperties
    from aiogram.enums import ParseMode

    return Bot(
        token=get_settings().bot_token,
        default=DefaultBotProperties(parse_mode=ParseMode.HTML),
    )


async def close_bot(bot: Any) -> None:
    """Close the HTTP session a bot built for one job opened.

    ``getattr`` because the tests hand the callers fakes, and a fake without a
    session has nothing to close.
    """
    bot_session = getattr(bot, "session", None)
    if bot_session is not None:
        await bot_session.close()


async def send(messages: Sequence[tuple[int, str]]) -> list[bool]:
    """Send each ``(telegram_id, text)`` through one bot built for this call.

    Returns, per message, whether Telegram took it. Never raises: a refused
    message, or a token aiogram will not build a bot from, is logged and
    reported as ``False``, because every caller is in the middle of something
    a Telegram outage must not undo. With no ``BOT_TOKEN`` nothing is built
    and nothing is sent. The bot is closed before this returns, whatever
    happened.
    """
    if not messages:
        return []
    if not get_settings().bot_token:
        log.warning("%d message(s) not sent: BOT_TOKEN is empty", len(messages))
        return [False] * len(messages)
    try:
        bot = build_bot()
    except Exception:  # noqa: BLE001 - see the docstring
        log.warning("%d message(s) not sent: no bot could be built", len(messages), exc_info=True)
        return [False] * len(messages)
    delivered: list[bool] = []
    try:
        for telegram_id, text in messages:
            try:
                await bot.send_message(telegram_id, text)
            except Exception:  # noqa: BLE001 - see the docstring
                log.warning("could not send a message to %s", telegram_id, exc_info=True)
                delivered.append(False)
            else:
                delivered.append(True)
    finally:
        try:
            await close_bot(bot)
        except Exception as error:  # noqa: BLE001 - see the docstring
            # A close that raises after delivery must not lose `delivered`:
            # the caller (`health._tell`) would read the exception as nobody
            # having been told and give a «down» or a reminder's claim back,
            # so the next tick would send what the owner already has.
            log.warning("could not close the bot: %s", type(error).__name__)
    return delivered
