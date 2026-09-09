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
from app.db import init_db

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger(__name__)


@contextlib.asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
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


@app.get("/")
async def root() -> dict[str, str]:
    return {"service": "lessons", "docs": "/docs"}


def main() -> None:
    import uvicorn

    settings = get_settings()
    uvicorn.run("app.main:app", host=settings.host, port=settings.port, reload=False)


if __name__ == "__main__":
    main()
