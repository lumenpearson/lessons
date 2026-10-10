"""The host target (``app/host.py``), asked in-process: its root, its Envoy, its door.

What only a running server can show — HTTP/2 itself, a native gRPC client, a
stream that outlives a response — is ``test_host_live.py``'s, against the host
CI's «Host» job starts. What is asked here runs anywhere the suite does: the
ASGI app the host serves, driven with the scope an HTTP/2 server builds; the
correction made to pyvoy's Envoy configuration, on a configuration shaped as
pyvoy 1.3.0 writes it; and the settings refusal, which the launcher meets
before it starts any server.
"""

from __future__ import annotations

import asyncio
import copy
import os
import struct
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

from app import host
from app.config import LOCAL_DATABASE_URL
from app.contract.lessons.v2.diary_pb import GetDiaryCapabilitiesResponse
from app.rpc import GRPC_REFUSED

SERVER = Path(__file__).resolve().parents[1]


async def _ask(
    path: str, content_type: str, body: bytes, *, http_version: str = "2"
) -> tuple[int, bytes, dict[bytes, bytes]]:
    """One request through ``host.application``: its status, body and trailers."""
    sent: list[dict[str, Any]] = []
    delivered = False

    async def receive() -> dict[str, Any]:
        nonlocal delivered
        if not delivered:
            delivered = True
            return {"type": "http.request", "body": body, "more_body": False}
        return {"type": "http.disconnect"}

    async def send(message: dict[str, Any]) -> None:
        sent.append(message)

    scope = {
        "type": "http",
        "http_version": http_version,
        "method": "POST",
        "scheme": "http",
        "path": path,
        "raw_path": path.encode(),
        "root_path": "",
        "query_string": b"",
        "headers": [(b"content-type", content_type.encode()), (b"te", b"trailers")],
        "client": ("203.0.113.9", 52144),
        "server": ("test", 80),
        "extensions": {"http.response.trailers": {}},
    }
    await host.application(scope, receive, send)
    status = next(m["status"] for m in sent if m["type"] == "http.response.start")
    answer = b"".join(m.get("body", b"") for m in sent if m["type"] == "http.response.body")
    trailers = next((dict(m["headers"]) for m in sent if m["type"] == "http.response.trailers"), {})
    return status, answer, trailers


def _envelope(payload: bytes) -> bytes:
    return struct.pack(">BI", 0, len(payload)) + payload


async def test_native_grpc_at_the_root_reaches_the_contract() -> None:
    """Where every gRPC client calls, with no prefix: the same services
    ``app.main`` mounts at ``/api/rpc``."""
    status, body, trailers = await _ask(
        "/lessons.v2.DiaryService/GetDiaryCapabilities", "application/grpc", _envelope(b"")
    )
    assert (status, trailers[b"grpc-status"]) == (200, b"0")
    _flags, length = struct.unpack(">BI", body[:5])
    answer = GetDiaryCapabilitiesResponse.from_binary(body[5 : 5 + length])
    assert answer.capabilities.enabled is True


async def test_the_root_answers_native_grpc_and_nothing_else() -> None:
    """Connect keeps its one path, ``/api/rpc``, on both targets: at the
    root it is ``app.main``'s, which has nothing there."""
    status, _body, _trailers = await _ask(
        "/lessons.v2.DiaryService/GetDiaryCapabilities", "application/json", b"{}"
    )
    assert status == 404


async def test_native_grpc_at_the_root_over_http_1_1_meets_the_same_guard() -> None:
    status, body, _trailers = await _ask(
        "/lessons.v2.DiaryService/GetDiaryCapabilities",
        "application/grpc",
        _envelope(b""),
        http_version="1.1",
    )
    assert (status, body) == (415, GRPC_REFUSED.encode())


@pytest.mark.parametrize("content_type", ["application/grpc-web", "application/grpc-web+proto"])
async def test_grpc_web_at_the_root_is_not_native_grpc(content_type) -> None:
    """grpc-web is Connect's own territory, kept at ``/api/rpc`` on both
    targets; the root answers true native gRPC alone, so a grpc-web call at
    the root reaches ``app.main``, which has no route for it there, exactly
    as :func:`test_the_root_answers_native_grpc_and_nothing_else` does for
    JSON."""
    status, _body, _trailers = await _ask(
        "/lessons.v2.DiaryService/GetDiaryCapabilities", content_type, b"{}"
    )
    assert status == 404


async def test_native_grpc_at_the_root_is_unavailable_not_404_when_v2_failed_to_load(
    monkeypatch,
) -> None:
    """``/api/rpc`` answers 503 (UNAVAILABLE, retried) when v2 could not be
    loaded (``main.mount_v2``'s fallback); the root must say the same, never
    ``app.main``'s bare 404 for a path it has no route for, which a gRPC
    client reads as UNIMPLEMENTED and does not retry."""
    from app.main import app as main_app

    monkeypatch.setattr(main_app.state, "v2_services", None)
    status, _body, _trailers = await _ask(
        "/lessons.v2.DiaryService/GetDiaryCapabilities", "application/grpc", _envelope(b"")
    )
    assert status == 503


async def test_everything_else_is_the_app_vercel_serves() -> None:
    sent: list[dict[str, Any]] = []

    async def receive() -> dict[str, Any]:
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(message: dict[str, Any]) -> None:
        sent.append(message)

    scope = {
        "type": "http",
        "http_version": "1.1",
        "method": "GET",
        "scheme": "http",
        "path": "/api/v1/health",
        "raw_path": b"/api/v1/health",
        "root_path": "",
        "query_string": b"",
        "headers": [],
        "client": ("203.0.113.9", 52144),
        "server": ("test", 80),
    }
    await host.application(scope, receive, send)
    assert next(m["status"] for m in sent if m["type"] == "http.response.start") == 200


#: The configuration pyvoy 1.3.0 writes for an app on a plain and a TLS port,
#: read with ``--print-envoy-config`` and cut to what the correction touches.
PYVOY_CONFIG: dict[str, Any] = {
    "admin": {"address": {"socket_address": {"address": "127.0.0.1", "port_value": 0}}},
    "static_resources": {
        "listeners": [
            {
                "name": listener,
                "filter_chains": [
                    {
                        "filters": [
                            {
                                "name": "envoy.filters.network.http_connection_manager",
                                "typed_config": {
                                    "@type": "type.googleapis.com/envoy.extensions.filters"
                                    ".network.http_connection_manager.v3.HttpConnectionManager",
                                    "generate_request_id": False,
                                    "stat_prefix": "ingress_http",
                                },
                            }
                        ]
                    }
                ],
            }
            for listener in ("listener", "listener_tls")
        ]
    },
}


def test_envoy_takes_the_client_from_the_connection_on_every_listener() -> None:
    corrected = host.envoy_config(copy.deepcopy(PYVOY_CONFIG))
    managers = [
        chain["filters"][0]["typed_config"]
        for listener in corrected["static_resources"]["listeners"]
        for chain in listener["filter_chains"]
    ]
    assert len(managers) == 2
    for manager in managers:
        assert manager["use_remote_address"] is True
        assert manager["skip_xff_append"] is True
        assert manager["generate_request_id"] is False


def test_envoy_is_matched_by_its_type_rather_than_its_name() -> None:
    """Envoy itself chooses the filter by ``typed_config``'s ``@type``, never
    by the filter's own ``name`` — a listener whose manager carries an
    unusual name must still be corrected."""
    odd = copy.deepcopy(PYVOY_CONFIG)
    for listener in odd["static_resources"]["listeners"]:
        listener["filter_chains"][0]["filters"][0]["name"] = "an.unusual.name"
    corrected = host.envoy_config(odd)
    managers = [
        chain["filters"][0]["typed_config"]
        for listener in corrected["static_resources"]["listeners"]
        for chain in listener["filter_chains"]
    ]
    assert len(managers) == 2
    for manager in managers:
        assert manager["use_remote_address"] is True
        assert manager["skip_xff_append"] is True


def test_a_configuration_with_nothing_to_correct_is_refused_rather_than_served() -> None:
    """A pyvoy that stopped writing the manager would otherwise start a host
    whose throttles a forged header walks past."""
    bare = copy.deepcopy(PYVOY_CONFIG)
    for listener in bare["static_resources"]["listeners"]:
        chain_filter = listener["filter_chains"][0]["filters"][0]
        chain_filter["name"] = "envoy.filters.network.tcp_proxy"
        chain_filter["typed_config"]["@type"] = (
            "type.googleapis.com/envoy.extensions.filters.network.tcp_proxy.v3.TcpProxy"
        )
    with pytest.raises(RuntimeError, match="client's address"):
        host.envoy_config(bare)


async def test_pyvoy_is_given_its_own_stdout_and_stderr_rather_than_pyvoys_default(
    monkeypatch,
) -> None:
    """pyvoy 1.3.0 defaults both to ``subprocess.DEVNULL`` (its own CLI passes
    ``None``): left at the default, the app's own logging, a traceback, and
    why Envoy itself failed to start would all be thrown away."""
    captured: dict[str, Any] = {}

    class FakePyvoyServer:
        def __init__(self, entry: str, **kwargs: Any) -> None:
            captured["entry"] = entry
            captured.update(kwargs)

        async def __aenter__(self) -> FakePyvoyServer:
            return self

        async def __aexit__(self, *exc_info: Any) -> None:
            return None

        async def wait(self) -> int | None:
            # Never resolves on its own: the stop event below is what ends
            # the server, exactly as a real Envoy would be told to stop.
            await asyncio.Event().wait()
            return None

        @property
        def listener_address(self) -> str:
            return "127.0.0.1"

    monkeypatch.setattr("pyvoy.PyvoyServer", FakePyvoyServer)
    stop = asyncio.Event()
    stop.set()

    assert await host._serve_pyvoy("127.0.0.1", 0, stop) == 0
    assert captured["stdout"] is None
    assert captured["stderr"] is None


def test_the_launcher_meets_the_settings_refusal_before_any_server_starts() -> None:
    """With the marker and the local defaults, ``python -m app.host`` says
    why and stops, before pyvoy is so much as imported: the same refusal a
    Vercel deployment meets (decision 7)."""
    env = {name: value for name, value in os.environ.items() if name != "VERCEL"}
    env.update(
        {
            "LESSONS_TARGET": "host",
            "DATABASE_URL": LOCAL_DATABASE_URL,
            "BOT_TOKEN": "",
            "RUN_BOT": "false",
        }
    )
    result = subprocess.run(
        [sys.executable, "-m", "app.host"],
        cwd=str(SERVER),
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert result.returncode == 1
    assert "DATABASE_URL" in result.stderr and "BOT_TOKEN" in result.stderr
    assert "docs/deploy.md" in result.stderr
    assert "host target on" not in result.stderr
