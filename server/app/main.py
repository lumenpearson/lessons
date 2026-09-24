"""Entry point: the read-only client API and the Telegram admin bot in one process.

Keeping them together means the bot writes and the API reads the same database
with no extra deployment moving parts. If the bot ever needs to scale
separately, split it at ``run_polling`` — nothing else is shared.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from collections.abc import AsyncIterator

from dishka.integrations.fastapi import setup_dishka
from fastapi import FastAPI

from app.api.cron import router as cron_router
from app.api.diary import router as diary_router
from app.api.diary_web import router as diary_web_router
from app.api.edit import router as edit_router
from app.api.manage import router as manage_router
from app.api.public import router as public_router
from app.config import get_settings
from app.db import engine, init_db
from app.di import close_container, container

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger(__name__)


def _report_bot_exit(task: asyncio.Task) -> None:
    """Log why the polling task stopped, if it stopped on its own."""
    if task.cancelled():
        return
    failure = task.exception()
    if failure is not None:
        log.error("Telegram polling stopped: %s", failure, exc_info=failure)


@contextlib.asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()

    # The container this run's requests are served from. Set here as well as
    # at import, because the shutdown below closes it and forgets it: without
    # this line `app.state` would still name the closed one, and a second
    # lifespan in one process — which `tests/test_startup.py` is — would be
    # served from a container that had finalised its app-scope objects.
    # Nothing app-scoped holds a resource today, so that would be invisible
    # until the first one did.
    app.state.dishka_container = container()

    # Say out loud what is switched off. These are not faults — each is a
    # documented way to run without a feature — but a deployment missing one
    # rarely meant to be, and the only other evidence is a screen in the bot
    # that somebody has to reach before anyone learns of it. The settings that
    # are *not* optional never get this far: `get_settings` refuses at the
    # door (app/config.py).
    for feature in settings.disabled_features():
        log.warning("%s", feature)

    # Bootstrap the schema only for a local SQLite file. On a managed Postgres
    # it is created once by `python -m scripts.init_db`: emitting create_all on
    # every serverless cold start costs a round trip per table before the first
    # response, and two cold starts racing the same DDL can deadlock.
    if engine.dialect.name == "sqlite":
        await init_db()

    stop_event = asyncio.Event()
    bot_task: asyncio.Task | None = None

    if settings.bot_enabled:
        from app.bot.bot import run_polling

        bot_task = asyncio.create_task(run_polling(stop_event))
        # Nothing awaits this task until shutdown, so without a callback a
        # failure before aiogram's own retry loop takes over - a malformed
        # token, a 401 from set_my_commands, no network at boot - is stored in
        # the task and never seen. The bot is then simply silent for the life
        # of the process, with a clean log.
        bot_task.add_done_callback(_report_bot_exit)
    else:
        log.warning("Telegram bot disabled (RUN_BOT=false or BOT_TOKEN unset)")

    try:
        yield
    finally:
        stop_event.set()
        if bot_task is not None:
            with contextlib.suppress(asyncio.CancelledError):
                await bot_task
        # Each provider holds a process-wide pooled HTTP client; closing them
        # is what returns their sockets rather than leaving them to a
        # finaliser. Imported here rather than at module scope so none of them
        # lands on the cold-start path of a request that uses none.
        from app.providers.dadata import close_client as close_directory
        from app.providers.netschool.client import close_client as close_netschool
        from app.providers.petersburg import close_client

        await close_client()
        await close_netschool()
        await close_directory()
        # Last, and after the bot has stopped: a handler still running would
        # otherwise be holding a session out of a container that has shut its
        # own scopes.
        await close_container()


app = FastAPI(
    title="Lessons",
    version="0.1.0",
    description="School diary API and Telegram admin bot",
    lifespan=lifespan,
)

# At module scope rather than in the lifespan, because `setup_dishka` installs
# an ASGI middleware and Starlette builds that stack once, on the first
# request: a middleware added from inside the lifespan is added to a list
# nothing reads again. The container itself builds nothing here — every
# provider is lazy, and `Settings` is `get_settings()`, which has already been
# called by `app.db` at import.
setup_dishka(container(), app)
app.include_router(public_router)
# The electronic diary of Saint Petersburg, behind its own bearer and its own
# prefix. Mounted unconditionally: it needs no configuration of ours, only a
# person's own account with the service, and an endpoint that answers 401
# without one is honest about what it is.
app.include_router(diary_router)
# The sign-in form. Not under /api/v1: it is a page a person opens, not an
# endpoint a client calls, and it is the one HTML this project serves — see
# app/api/diary_web.py for why a password may not be typed into a chat.
app.include_router(diary_web_router)
app.include_router(edit_router)
# The management surface: what the bot's /subjects, /bells, /class, /devices,
# /log, /stats, /export, /import and access requests do, for a class admin
# holding a phone instead of the bot. Same roles, same audit log.
app.include_router(manage_router)
# Always mounted; the endpoint itself answers 404 until CRON_SECRET is set,
# the same way the webhook does, and the aiogram import it needs is deferred
# until a tick actually runs.
app.include_router(cron_router)

# Only mounted when a webhook secret is configured. On a long-polling
# deployment the endpoint would be dead weight and one more thing to secure.
#
# Imported here rather than at the top of the file, because importing the module
# costs about four seconds of aiogram before anything else can run, and a
# serverless cold start pays it on the way to the first response. It used to be
# paid on *every* cold start, including the free-tier deployment this project
# documents, where the webhook is unmounted and aiogram is then imported purely
# to be told it is not wanted. The heavy `app.bot.bot` import inside
# `app/api/telegram.py` is already deferred the same way and for the same
# reason; this is the outer half of it.
if get_settings().webhook_enabled:
    from app.api.telegram import router as telegram_router

    app.include_router(telegram_router, prefix="/api/v1")
    log.info("Telegram webhook mounted at /api/v1/telegram/webhook")
else:
    # Without this line an unset BOT_TOKEN or WEBHOOK_SECRET is indistinguishable
    # from a routing fault: both look like a bare 404 on the webhook.
    log.warning("Telegram webhook NOT mounted: BOT_TOKEN and/or WEBHOOK_SECRET unset")


@app.get("/")
async def root() -> dict[str, str]:
    return {"service": "lessons", "docs": "/docs"}


def main() -> None:
    import uvicorn

    settings = get_settings()
    uvicorn.run("app.main:app", host=settings.host, port=settings.port, reload=False)


if __name__ == "__main__":
    main()
