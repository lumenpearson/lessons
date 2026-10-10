"""The host target: the whole app over HTTP/2, from one long-running process.

``python -m app.host`` serves what Vercel serves — v1, v2 over REST and
Connect, the Telegram webhook and the cron tick — and what Vercel cannot:
native gRPC, which needs HTTP/2 and response trailers, and ``WatchClass``'s
stream, which needs a process that outlives a response
(``docs/specs/2026-10-05-server-v2-design.md``, decision 13). It is a
complete deployment, chosen instead of Vercel rather than beside it
(question 2): the webhook and the external cron are pointed at it, and it
runs as one instance, because a stream hears what this one process writes
(``app/watch.py``).

**The server is pyvoy**: Envoy, with the app running inside it, HTTP/1.1 and
HTTP/2 with prior knowledge on one port, trailers and gzip. ``--server
hypercorn`` is the fallback the 5 October spike proved as well; CI's «Host»
job runs both.

**It never sets ``LESSONS_TARGET``.** The marker is a deployment's statement
about itself, and the root ``Dockerfile`` makes it; CI's job and a local run
start this module without it, against SQLite, as a local uvicorn runs. The
settings are read here before any server starts, so a host the refusal stops
says why in its own log and not from inside Envoy.

**Native gRPC is answered at the root too.** A gRPC client calls
``/lessons.v2.<Service>/<Method>`` and knows no path prefix; connect-kotlin,
which the app uses, takes ``/api/rpc`` as Vercel serves it. So
:func:`application` hands a native gRPC request under ``/lessons.v2.`` to the
same services ``app.main`` mounts at ``/api/rpc``, and everything else to
``app.main``, as on Vercel.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import signal
import sys
from collections.abc import Awaitable, Callable, MutableMapping
from typing import Any

from app.config import DeploymentNotConfigured, get_settings

log = logging.getLogger(__name__)

Scope = MutableMapping[str, Any]
Receive = Callable[[], Awaitable[MutableMapping[str, Any]]]
Send = Callable[[MutableMapping[str, Any]], Awaitable[None]]

#: Where a native gRPC call lands when a client is pointed at the host itself.
GRPC_ROOT = "/lessons.v2."

#: The app pyvoy loads into Envoy, by name.
ENTRY = "app.host:application"

#: The servers this module can run, the first by default.
SERVERS = ("pyvoy", "hypercorn")

#: The suffix of a filter's `typed_config` `@type` that marks it as Envoy's
#: HTTP connection manager, whose idea of the client's address
#: :func:`envoy_config` corrects. Envoy itself chooses the filter by this
#: value, never by the filter's own `name` — which happens to spell
#: `envoy.filters.network.http_connection_manager` in pyvoy 1.3.0's own
#: output today, but is not what decides it.
_HCM_TYPE_SUFFIX = ".HttpConnectionManager"


def _main_app() -> Any:
    # Imported on the first call rather than with this module, so that the
    # launcher — which under pyvoy only starts Envoy, whose own interpreter
    # serves the app — builds no engine, mounts no route and starts no Sentry
    # of its own.
    from app.main import app

    return app


async def application(scope: Scope, receive: Receive, send: Send) -> None:
    """``app.main``'s app, with native gRPC answered at the root as well."""
    main = _main_app()
    if scope["type"] == "http" and scope["path"].startswith(GRPC_ROOT):
        from app.rpc import is_native_grpc

        if is_native_grpc(scope):
            # None when v2 could not be loaded (app.main.mount_v2's fallback).
            services = getattr(main.state, "v2_services", None)
            if services is None:
                # The same answer /api/rpc gives there: 503, read as
                # UNAVAILABLE and retried — never app.main's bare 404 for a
                # path it has no route for, which a gRPC client reads as
                # UNIMPLEMENTED and does not retry.
                from app.main import _v2_unavailable

                await _v2_unavailable({**scope, "path": "/api/rpc" + scope["path"]}, receive, send)
                return
            await services({**scope, "root_path": ""}, receive, send)
            return
        # grpc-web and anything else under /lessons.v2.: Connect's own
        # territory, kept at /api/rpc on both targets — fall through below.
    await main(scope, receive, send)


def envoy_config(config: dict[str, Any]) -> dict[str, Any]:
    """pyvoy's Envoy configuration, with the client's address taken from the connection.

    pyvoy leaves Envoy's ``use_remote_address`` off, and then Envoy hands the
    app the last ``X-Forwarded-For`` entry as the client: whatever the caller
    wrote there. Probed with pyvoy 1.3.0, a request carrying
    ``X-Forwarded-For: 198.51.100.7, 203.0.113.8`` reached the app as
    ``203.0.113.8``. Every
    throttle keys on that address when no proxy is trusted
    (``api/deps.caller_bucket``), so a caller writing a new one each time
    would never be throttled at all. Turned on, with ``skip_xff_append`` so
    that Envoy adds no entry of its own, the app sees the connection's peer as
    the client and the header exactly as it arrived — which is what
    ``TRUSTED_PROXY_HOPS`` reads behind a proxy, as it does under uvicorn.

    Two more settings of the manager would take the client from what the
    caller sends again, and pyvoy 1.3.0 writes neither: an
    ``xff_num_trusted_hops`` above nought, which trusts that many entries of
    the header and is pinned to nought here, and an
    ``original_ip_detection_extensions`` entry, which is refused rather than
    removed, because an extension a later pyvoy wrote would be there for a
    reason this module cannot know — and Envoy takes none beside
    ``use_remote_address`` anyway. So a pyvoy that wrote either could not
    start a host whose throttles a forged header walks past.
    """
    managers = [
        chain_filter["typed_config"]
        for listener in config.get("static_resources", {}).get("listeners", [])
        for chain in listener.get("filter_chains", [])
        for chain_filter in chain.get("filters", [])
        if chain_filter.get("typed_config", {}).get("@type", "").endswith(_HCM_TYPE_SUFFIX)
    ]
    if not managers:
        raise RuntimeError(
            "pyvoy's Envoy configuration has no HTTP connection manager to correct: "
            "the client's address would be the caller's to choose"
        )
    if any(manager.get("original_ip_detection_extensions") for manager in managers):
        raise RuntimeError(
            "pyvoy's Envoy configuration detects the client's address with an extension: "
            "the client's address would be read from what the caller sends"
        )
    for manager in managers:
        manager["use_remote_address"] = True
        manager["skip_xff_append"] = True
        manager["xff_num_trusted_hops"] = 0
    return config


async def _serve_pyvoy(host: str, port: int, stop: asyncio.Event) -> int:
    from pyvoy import PyvoyServer

    class Server(PyvoyServer):
        def get_envoy_config(self) -> dict[str, Any]:
            return envoy_config(super().get_envoy_config())

    server = Server(
        ENTRY,
        address=host,
        port=port,
        # One Python thread, so one event loop: the engine's pool and the bus
        # a stream is woken by both belong to the loop that made them.
        worker_threads=1,
        # Required, not guessed: the lifespan is where a SQLite file gets its
        # tables and the providers' clients are closed.
        lifespan=True,
        content_encodings=["gzip"],
        # pyvoy 1.3.0 defaults both to subprocess.DEVNULL (its own CLI passes
        # None): left at the default, the app's own logging, a traceback, and
        # why Envoy itself failed to start would all be thrown away, and the
        # host's log would hold only this module's lines.
        stdout=None,
        stderr=None,
    )
    async with server:
        log.info("host target on %s:%s, served by pyvoy", server.listener_address, port)
        exited = asyncio.ensure_future(server.wait())
        stopping = asyncio.ensure_future(stop.wait())
        done, _pending = await asyncio.wait({exited, stopping}, return_when=asyncio.FIRST_COMPLETED)
        stopping.cancel()
        if exited in done:
            # Envoy went away by itself: never a clean exit for a server.
            log.error("Envoy stopped on its own, with %s", exited.result())
            return exited.result() or 1
        exited.cancel()
    return 0


async def _serve_hypercorn(host: str, port: int, stop: asyncio.Event) -> int:
    from hypercorn.asyncio import serve
    from hypercorn.config import Config

    config = Config()
    config.bind = [f"{host}:{port}"]
    # The app logs for itself; a line per request would put every phone's
    # path and address into the log.
    config.accesslog = None
    log.info("host target on %s:%s, served by hypercorn", host, port)
    await serve(application, config, shutdown_trigger=stop.wait, mode="asgi")
    return 0


async def _run(server: str, host: str, port: int) -> int:
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for signum in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(signum, stop.set)
        except (NotImplementedError, RuntimeError):
            # Windows: Ctrl+C arrives as KeyboardInterrupt, which asyncio.run
            # turns into a cancellation that stops the server on its way out.
            pass
    serve = _serve_pyvoy if server == "pyvoy" else _serve_hypercorn
    return await serve(host, port, stop)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        prog="python -m app.host",
        description="Serve the host target: v1, v2 over REST, Connect and native gRPC, "
        "WatchClass, the webhook and the tick, over HTTP/2.",
    )
    parser.add_argument(
        "--server",
        choices=SERVERS,
        default=SERVERS[0],
        help="pyvoy (the default), or hypercorn, the fallback",
    )
    arguments = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s"
    )
    try:
        settings = get_settings()
    except DeploymentNotConfigured as refusal:
        sys.exit(str(refusal))
    sys.exit(asyncio.run(_run(arguments.server, settings.host, settings.port)))


if __name__ == "__main__":
    main()
