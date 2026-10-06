"""Entry point: the client API and the Telegram admin bot in one process.

Both write the same database, over the same ``app/services/``: the bot is the
admin panel, and a phone linked to a Telegram account writes through
the edit routes (``/api/v1/homework``, ``/overrides``, ``/events``, ``/days``)
and ``/api/v1/manage`` with that account's role, while the
rest of the API reads, or writes only an account's or a family's own rows.
Keeping them together means one database and no extra deployment moving parts.
If the bot ever needs to scale separately, split it at ``run_polling`` —
nothing else is shared.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from collections.abc import AsyncIterator

from dishka.integrations.fastapi import setup_dishka
from fastapi import FastAPI
from starlette.responses import JSONResponse
from starlette.routing import Mount

from app.api.cron import router as cron_router
from app.api.diary import router as diary_router
from app.api.diary_web import router as diary_web_router
from app.api.directory import router as directory_router
from app.api.edit import router as edit_router
from app.api.manage import router as manage_router
from app.api.public import router as public_router
from app.config import get_settings
from app.db import engine, init_db
from app.di import close_container, container

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger(__name__)

def _start_sentry() -> None:
    """Sentry, where SENTRY_DSN is set, and imported only there: a deployment
    without it never loads sentry_sdk (tests/test_cold_start.py). Called at
    this module's top level rather than from the lifespan, which a
    serverless platform may never run, so its integration sees the first
    request.

    ``Settings.sentry_dsn_value`` already refuses a DSN ``sentry_sdk.init``
    itself would raise ``BadDsn`` on, but a failure from anywhere else in the
    SDK must not take v1, v2, the webhook and the cron tick down with it -
    this function's own caller is every one of them, at cold start. Logged
    by the exception's type alone: its message can carry the DSN's key.
    """
    if not get_settings().sentry_configured:
        return
    from app.observability import init as init_sentry

    try:
        init_sentry(get_settings())
    except Exception as error:
        log.error("Sentry did not start: %s", type(error).__name__)


_start_sentry()


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
        # is what returns their sockets rather than leaving them to a finaliser.
        # Imported in this ``finally`` because that is the only place they are
        # used. It is not a cold-start saving for Petersburg or DaData — the API
        # surface (`api/diary.py`, the schools directory) already loads their
        # clients at module scope — but «Сетевой город»'s client is loaded
        # lazily through the registry and nothing else imports it, so keeping
        # this import local does keep it off the path of a process that never
        # binds a class to it.
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


# The interactive docs and the schema they are drawn from, locally and never on
# the production server (#200): nobody there needs them — the app is written
# against docs/api.md, not against a schema it fetches — and each is surface.
# The signal is the one `get_settings` refuses to start on, Vercel's own
# `VERCEL`, never a guess at "this looks like production". A compose or VPS
# deployment keeps them, as it keeps every other local default.
_api_docs = not get_settings().behind_vercel
app = FastAPI(
    title="Lessons",
    version="0.1.0",
    description="School diary API and Telegram admin bot",
    lifespan=lifespan,
    openapi_url="/openapi.json" if _api_docs else None,
    docs_url="/docs" if _api_docs else None,
    redoc_url="/redoc" if _api_docs else None,
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
# The school directory a phone asks before it has a class: anonymous, and
# metered per caller and per day (app/api/directory.py). Its imports are the
# school search's own, which `manage_router` already loads, and the region
# catalog it reads is parsed on the first search, not here.
app.include_router(directory_router)
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
# Imported here, where it is mounted. The module itself costs nothing to
# import: it keeps aiogram out of its top level and builds the dispatcher on
# the first update, so a cold start that serves the phone pays nothing for the
# bot even where the webhook is mounted — which on Vercel is every deployment.
# `tests/test_cold_start.py` holds that in both configurations.
if get_settings().webhook_enabled:
    from app.api.telegram import router as telegram_router

    app.include_router(telegram_router, prefix="/api/v1")
    log.info("Telegram webhook mounted at /api/v1/telegram/webhook")
else:
    # Without this line an unset BOT_TOKEN or WEBHOOK_SECRET is indistinguishable
    # from a routing fault: both look like a bare 404 on the webhook.
    log.warning("Telegram webhook NOT mounted: BOT_TOKEN and/or WEBHOOK_SECRET unset")


#: What `/api/v2` and `/api/rpc` answer when v2 could not be loaded.
V2_UNAVAILABLE = "v2 is not available on this deployment; v1 is unaffected"


async def _v2_unavailable(scope, receive, send) -> None:
    """The answer where v2 would be, in each transport's own error shape."""
    if scope["type"] != "http":
        return
    if scope["path"].startswith("/api/rpc"):
        body: dict[str, object] = {"code": "unavailable", "message": V2_UNAVAILABLE}
    else:
        body = {
            "error": {
                "code": 503,
                "message": V2_UNAVAILABLE,
                "status": "UNAVAILABLE",
                "details": [],
            }
        }
    await JSONResponse(body, status_code=503)(scope, receive, send)


def mount_v2(target: FastAPI) -> bool:
    """Serve v2 beside v1 — REST under `/api/v2`, Connect under `/api/rpc` — or
    answer 503 there if v2 cannot be loaded, and say whether it was.

    3a is the first time production imports `connectrpc`, the generated
    contract and their native wheels (`protobuf-py-ext`, `pyqwest`), none of
    which had been imported on Vercel before
    (docs/specs/2026-10-05-server-v2-design.md, decision 7). An import error
    here would otherwise be the whole function's, and v1 — every phone in
    production — and the webhook would go down with v2. So it is caught,
    logged with its traceback, and v2's two prefixes answer 503 in their own
    error shapes, while everything else is served as before.
    """
    try:
        from app.rest import rest_routes
        from app.rpc import rpc_app

        routes = rest_routes()
        services = rpc_app()
    except Exception:
        log.exception("v2 could not be loaded: /api/v2 and /api/rpc answer 503, v1 is served")
        target.router.routes.append(Mount("/api/v2", app=_v2_unavailable))
        target.router.routes.append(Mount("/api/rpc", app=_v2_unavailable))
        # Read back by `/api/v1/warmup`: a fallback that only the log knew of
        # would be invisible to whatever pings it.
        target.state.v2_mounted = False
        return False
    target.router.routes.extend(routes)
    target.mount("/api/rpc", services)
    target.state.v2_mounted = True
    return True


# v2, beside v1 and under prefixes v1 never used. After v1's routers, so that
# nothing of v1's can be shadowed by a route built from the contract.
mount_v2(app)


@app.get("/")
async def root() -> dict[str, str]:
    return {"service": "lessons", "docs": "/docs"}


def main() -> None:
    import uvicorn

    settings = get_settings()
    uvicorn.run("app.main:app", host=settings.host, port=settings.port, reload=False)


if __name__ == "__main__":
    main()
