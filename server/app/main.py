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

from fastapi import FastAPI

from app.api.public import router as public_router
from app.config import get_settings
from app.db import engine, init_db

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger(__name__)


@contextlib.asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()

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
    else:
        log.warning("Telegram bot disabled (RUN_BOT=false or BOT_TOKEN unset)")

    try:
        yield
    finally:
        stop_event.set()
        if bot_task is not None:
            with contextlib.suppress(asyncio.CancelledError):
                await bot_task


app = FastAPI(
    title="Lessons",
    version="0.1.0",
    description="School diary API and Telegram admin bot",
    lifespan=lifespan,
)
app.include_router(public_router)

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
