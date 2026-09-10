"""Bot and dispatcher wiring, plus the polling task the API lifespan owns."""

from __future__ import annotations

import asyncio
import logging

from aiogram import Bot, Dispatcher
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.types import BotCommand

from app.bot.handlers import build_router
from app.bot.middlewares import ContextMiddleware
from app.config import get_settings
from app.db import SessionLocal
from app.fsm_storage import DatabaseStorage

log = logging.getLogger(__name__)

COMMANDS = [
    BotCommand(command="start", description="Главное меню"),
    BotCommand(command="today", description="Расписание на сегодня"),
    BotCommand(command="code", description="Код класса для приложения"),
    BotCommand(command="help", description="Справка"),
]


def build_dispatcher() -> Dispatcher:
    """The one dispatcher factory, shared by polling and webhook.

    Storage is the database rather than memory even when polling, so the two
    modes behave identically and a restart does not drop conversations that
    were half-finished.
    """
    dispatcher = Dispatcher(storage=DatabaseStorage(SessionLocal))
    # Both message and callback flows need the session/class/role bundle.
    dispatcher.message.middleware(ContextMiddleware())
    dispatcher.callback_query.middleware(ContextMiddleware())
    dispatcher.include_router(build_router())
    return dispatcher


def build_bot() -> Bot:
    return Bot(
        token=get_settings().bot_token,
        default=DefaultBotProperties(parse_mode=ParseMode.HTML),
    )


async def run_polling(stop_event: asyncio.Event) -> None:
    """Long-poll until ``stop_event`` is set.

    Polling is used rather than a webhook so the server runs behind any NAT with
    no TLS termination of its own. Switching to a webhook later only changes this
    function.
    """
    bot = build_bot()
    dispatcher = build_dispatcher()

    await bot.set_my_commands(COMMANDS)
    log.info("Telegram bot started")

    polling = asyncio.create_task(dispatcher.start_polling(bot, handle_signals=False))
    try:
        await stop_event.wait()
    finally:
        await dispatcher.stop_polling()
        polling.cancel()
        with __import__("contextlib").suppress(asyncio.CancelledError):
            await polling
        await bot.session.close()
        log.info("Telegram bot stopped")
