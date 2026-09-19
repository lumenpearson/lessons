"""Bot and dispatcher wiring, plus the polling task the API lifespan owns."""

from __future__ import annotations

import asyncio
import logging
from contextlib import suppress

from aiogram import Bot, Dispatcher
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.exceptions import TelegramBadRequest
from aiogram.types import BotCommand, ErrorEvent
from dishka.integrations.aiogram import setup_dishka

from app.bot.handlers import build_router
from app.bot.middlewares import ContextMiddleware
from app.config import get_settings
from app.db import SessionLocal
from app.di import container
from app.fsm_storage import DatabaseStorage

log = logging.getLogger(__name__)

# Shown by BotFather in this order, so the everyday ones come first and the
# admin ones last. Every command listed here has a handler; the list is not
# filtered by role because Telegram shows one list per bot, and a command a
# viewer cannot use answers with a polite refusal rather than silence.
COMMANDS = [
    BotCommand(command="start", description="Главное меню"),
    BotCommand(command="today", description="Сегодня"),
    BotCommand(command="tomorrow", description="Завтра"),
    BotCommand(command="day", description="Календарь: любой день"),
    BotCommand(command="week", description="Неделя"),
    BotCommand(command="next", description="Что дальше"),
    BotCommand(command="homework", description="Домашнее задание"),
    BotCommand(command="tasks", description="Мои задачи"),
    BotCommand(command="remind", description="Напоминания"),
    BotCommand(command="find", description="Поиск по ДЗ"),
    BotCommand(command="subjects", description="Предметы"),
    BotCommand(command="holidays", description="Каникулы и особые дни"),
    BotCommand(command="bells", description="Звонки"),
    BotCommand(command="devices", description="Устройства"),
    BotCommand(command="log", description="Журнал изменений"),
    BotCommand(command="class", description="Настройки класса"),
    BotCommand(command="export", description="Экспорт расписания"),
    BotCommand(command="import", description="Импорт расписания"),
    BotCommand(command="stats", description="Статистика"),
    BotCommand(command="calendar", description="Подписка на календарь"),
    BotCommand(command="link", description="Привязать телефон"),
    BotCommand(command="request", description="Запросить доступ"),
    BotCommand(command="code", description="Код класса"),
    BotCommand(command="help", description="Справка"),
]


def build_dispatcher() -> Dispatcher:
    """The one dispatcher factory, shared by polling and webhook.

    Storage is the database rather than memory even when polling, so the two
    modes behave identically and a restart does not drop conversations that
    were half-finished.
    """
    dispatcher = Dispatcher(storage=DatabaseStorage(SessionLocal))
    # The container first, because `ContextMiddleware` takes its session out of
    # it: this registers an *outer* middleware on every observer, and an outer
    # middleware on the update runs before the inner ones below. `auto_inject`
    # is deliberately off — the handlers here are handed their session, class
    # and role by `ContextMiddleware` and are not going to be rewritten to ask
    # for them one at a time, so wrapping all of them at startup would buy
    # nothing. A handler that wants something else can carry `@inject` itself.
    #
    # It registers on *every* observer, so a message enters a scope twice —
    # once on `update` and once on `message`. That is dishka's design and it
    # costs one nested scope, not a second session: the inner entry is a child
    # of the outer one, `AsyncSession` is REQUEST-scoped, and a child resolves
    # it from the parent. One session per update, closed when the update is
    # done, which is what `ContextMiddleware` commits.
    setup_dishka(container(), dispatcher)
    # Both message and callback flows need the session/class/role bundle.
    dispatcher.message.middleware(ContextMiddleware())
    dispatcher.callback_query.middleware(ContextMiddleware())
    dispatcher.include_router(build_router())
    dispatcher.errors.register(_on_error)
    return dispatcher


async def _on_error(event: ErrorEvent) -> bool:
    """Answer the callback whatever happened, so no button spins forever.

    There are thirty ``edit_text`` calls across the handlers and not one of them
    was guarded, with no error handler registered either. Two ordinary things
    therefore left a button with a loading spinner on it until Telegram timed
    out:

    * Pressing a button that re-renders the same view — «Сегодня» from the day
      view, «‹ Меню» from the menu — makes Telegram answer *message is not
      modified*, which is a 400. The exception escaped before ``answer()``.
    * Any handler raising at all, including the ``data["…"]`` reads in the FSM
      flows that assume a step the user may not have been through.

    On the webhook deployment none of this is visible: the route answers 200 on
    an exception, so the only symptom is a bot that stops responding.

    An unmodified message is not worth telling anybody about. Anything else is
    logged and the user is told to start again — which matters because their
    conversation state is *not* cleared here: it lives in the database, and the
    ``ErrorEvent`` is not the place to reach for a storage key. ``/start``
    clears it, which is why the sentence names that command rather than
    apologising in general.
    """
    callback = event.update.callback_query
    error = event.exception

    if isinstance(error, TelegramBadRequest) and "message is not modified" in str(error).lower():
        if callback is not None:
            with suppress(TelegramBadRequest):
                await callback.answer()
        return True

    log.exception("Bot handler failed", exc_info=error)

    if callback is not None:
        with suppress(TelegramBadRequest):
            await callback.answer("Что-то пошло не так. Начните заново: /start", show_alert=True)
    return True


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
        with suppress(asyncio.CancelledError):
            await polling
        await bot.session.close()
        log.info("Telegram bot stopped")
