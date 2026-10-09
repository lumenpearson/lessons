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
commit: :func:`send` to one person (stage 3b-3), and :func:`notify_class` to
a class's subscribers, through ``services/notify.notify_subscribers`` (stage
3b-5). v1's keep their own ``_build_bot`` seams in ``api/manage/requests.py``
and ``api/edit.py``, which build through ``build_bot`` here, so that no v1
test had to change.
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from typing import TYPE_CHECKING, Any

from app.config import get_settings
from app.services import notify

if TYPE_CHECKING:
    # Names for the annotations, and nothing more: see the module docstring.
    from aiogram import Bot
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.models import SchoolClass

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


async def notify_class(
    session: AsyncSession,
    school_class: SchoolClass,
    text: str,
    *,
    kind: str,
    author: int | None,
) -> int:
    """Tell ``school_class``'s subscribers to ``kind`` — all but ``author`` —
    through one bot built for this notice, and answer how many were told.

    v2's writes register it as an effect (``Call.after_commit``), so it runs
    once the change is committed, never on a refusal, and with the call's
    session still open: ``notify_subscribers`` reads the recipients from it,
    switches off whoever blocked the bot, and commits that switch-off itself -
    the one write this effect makes after the call's own commit. ``text`` is
    HTML, escaped by whoever worded it. Never raises: a bot that cannot be
    built, a recipient Telegram refuses or a failure on the way is logged, and
    answered as fewer told, because the change is saved already. With no
    ``BOT_TOKEN`` nothing is built. The bot is closed whatever happened.
    """
    # Read once, before anything below can fail: a failing switch-off commit
    # can leave every row the session holds expired, `school_class` included,
    # and reading its `.id` again in a log line would then need a fresh
    # SELECT a synchronous `except` block cannot await - raising out of a
    # function whose contract is "never raises".
    class_id = school_class.id
    if not get_settings().bot_token:
        return 0
    try:
        bot = build_bot()
    except Exception:  # noqa: BLE001 - see the docstring
        log.warning("class %s was not told: no bot could be built", class_id, exc_info=True)
        return 0
    try:
        return await notify.notify_subscribers(
            session, bot, school_class, text, kind=kind, exclude=author
        )
    except Exception:  # noqa: BLE001 - see the docstring
        log.warning("could not notify class %s", class_id, exc_info=True)
        return 0
    finally:
        try:
            await close_bot(bot)
        except Exception as error:  # noqa: BLE001 - see the docstring
            log.warning("could not close the bot: %s", type(error).__name__)
