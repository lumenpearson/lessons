"""v2 over Connect, mounted at ``/api/rpc``: the guards, the stream's refusal, the fail-safe.

Each guard stands in front of a defect ``connectrpc`` 0.12.1 shows under
HTTP/1.1, and each test sends the request that found it
(``docs/specs/2026-10-05-server-v2-design.md``, decision 7).
"""

from __future__ import annotations

import gzip
import json
import subprocess
import sys
from pathlib import Path

import httpx
import pytest
from fastapi import FastAPI

import app.rpc as rpc_module
from app import main
from app.api.public import router as public_router
from app.rpc import GRPC_REFUSED, _Services, rpc_app
from app.rpc.handlers import HANDLERS


@pytest.fixture
def unserved(monkeypatch) -> str:
    """A method with no handler, by construction: ``DiaryService/ListStudents``
    is taken out of ``HANDLERS`` for the test, so the answer does not depend on
    which methods a later stage serves. ``invoke`` reads the same dict, so both
    transports see it."""
    key = "lessons.v2.DiaryService/ListStudents"
    monkeypatch.delitem(HANDLERS, key, raising=False)
    return key


async def test_native_grpc_over_http_1_1_is_refused_before_the_library(v2) -> None:
    for content_type in ("application/grpc", "application/grpc+proto", "application/grpc+json"):
        response = await v2.http.post(
            "/api/rpc/lessons.v2.ClassService/GetClass",
            content=b"\x00\x00\x00\x00\x00",
            headers={"Content-Type": content_type},
        )
        assert response.status_code == 415, content_type
        assert response.text == GRPC_REFUSED


async def test_grpc_web_is_not_native_grpc(v2, unserved) -> None:
    """gRPC-Web runs over HTTP/1.1, and ``connectrpc`` serves it: the guard
    must not take ``application/grpc-web…`` for ``application/grpc…``. The
    method has no handler (``unserved``), so the library's answer is its
    UNIMPLEMENTED, whichever methods a stage serves."""
    response = await v2.http.post(
        f"/api/rpc/{unserved}",
        content=b"\x00\x00\x00\x00\x00",
        headers={"Content-Type": "application/grpc-web+proto"},
    )
    assert response.status_code == 200
    assert b"grpc-status: 12" in response.content


async def test_native_grpc_over_http_2_reaches_the_library(unserved) -> None:
    """On the host target, over HTTP/2 with trailers, the guard steps aside
    (3c serves it). Asked of the app directly, with the scope an HTTP/2 server
    would build, because httpx's ASGI transport speaks only HTTP/1.1. The
    method has no handler (``unserved``), so the library's answer is its
    UNIMPLEMENTED, whichever methods a stage serves."""
    sent: list[dict] = []
    body = [{"type": "http.request", "body": b"\x00\x00\x00\x00\x00", "more_body": False}]

    async def receive() -> dict:
        return body.pop(0) if body else {"type": "http.disconnect"}

    async def send(message: dict) -> None:
        sent.append(message)

    scope = {
        "type": "http",
        "http_version": "2",
        "method": "POST",
        "scheme": "http",
        "path": f"/{unserved}",
        "root_path": "",
        "query_string": b"",
        "headers": [(b"content-type", b"application/grpc"), (b"te", b"trailers")],
        "client": ("203.0.113.9", 52144),
        "extensions": {"http.response.trailers": {}},
    }
    await rpc_app()(scope, receive, send)
    assert sent[0]["status"] == 200
    trailers = dict(next(m for m in sent if m["type"] == "http.response.trailers")["headers"])
    assert trailers[b"grpc-status"] == b"12"


async def test_a_scope_that_names_no_http_version_is_refused_as_http_1_1() -> None:
    """ASGI makes ``http_version`` optional and reads a missing one as "1.1",
    so a server that leaves it out must not walk native gRPC past the guard
    into the library's 500. Asked of the app directly, as the HTTP/2 case is."""
    sent: list[dict] = []

    async def receive() -> dict:
        return {"type": "http.request", "body": b"\x00\x00\x00\x00\x00", "more_body": False}

    async def send(message: dict) -> None:
        sent.append(message)

    scope = {
        "type": "http",
        "method": "POST",
        "scheme": "http",
        "path": "/lessons.v2.ClassService/GetClass",
        "root_path": "",
        "query_string": b"",
        "headers": [(b"content-type", b"application/grpc")],
        "client": ("203.0.113.9", 52144),
    }
    await rpc_app()(scope, receive, send)
    assert sent[0]["status"] == 415
    body = b"".join(m.get("body", b"") for m in sent if m["type"] == "http.response.body")
    assert body == GRPC_REFUSED.encode()


@pytest.mark.parametrize(
    ("content_type", "body"),
    [
        ("application/json", b'{"schoolClass": '),
        ("application/json", b'{"confirmation": {"a": 1}}'),
        ("application/json", b"[1, 2]"),
        ("application/proto", b"\xff\xff\xff"),
    ],
    ids=["truncated-json", "wrong-type", "not-an-object", "bad-varint"],
)
async def test_a_body_that_does_not_decode_is_invalid_argument_not_500(
    v2, content_type, body
) -> None:
    response = await v2.http.post(
        "/api/rpc/lessons.v2.ClassService/DeleteClass",
        content=body,
        headers={"Content-Type": content_type},
    )
    assert response.status_code == 400
    error = response.json()
    assert error["code"] == "invalid_argument"
    assert error["message"] == "The request could not be decoded"
    assert error["details"][0]["type"] == "google.rpc.ErrorInfo"
    assert error["details"][0]["debug"]["reason"] == "REQUEST_UNDECODABLE"


async def test_a_body_that_claims_gzip_and_is_not_is_undecodable_not_500(v2) -> None:
    """#337: ``zlib.error`` came out of the library's request reader as a 500."""
    response = await v2.http.post(
        "/api/rpc/lessons.v2.MeService/GetMe",
        content=b"not gzip at all",
        headers={
            "Content-Type": "application/json",
            "Content-Encoding": "gzip",
            "Connect-Protocol-Version": "1",
        },
    )
    assert response.status_code == 400
    error = response.json()
    assert error["code"] == "invalid_argument"
    assert error["message"] == "The request could not be decoded"
    assert error["details"][0]["debug"]["reason"] == "REQUEST_UNDECODABLE"


async def test_a_gzip_body_that_is_gzip_is_still_decompressed_and_decoded(
    v2, unserved
) -> None:
    """The method has no handler (``unserved`` removes it), so the proof
    that the body got through is the answer it earns: a valid one is the
    method's UNIMPLEMENTED, and one that decompresses into garbage is the
    codec's refusal, not the gzip one's."""
    headers = {
        "Content-Type": "application/json",
        "Content-Encoding": "gzip",
        "Connect-Protocol-Version": "1",
    }
    url = "/api/rpc/lessons.v2.DiaryService/ListStudents"
    valid = await v2.http.post(url, content=gzip.compress(b"{}"), headers=headers)
    assert valid.status_code == 501
    assert valid.json()["code"] == "unimplemented"
    garbage = await v2.http.post(url, content=gzip.compress(b"[1, 2]"), headers=headers)
    assert garbage.status_code == 400


async def test_an_unknown_service_is_404_and_a_get_of_a_write_is_405(v2) -> None:
    unknown = await v2.http.post(
        "/api/rpc/lessons.v2.NoSuchService/Nothing",
        content=b"{}",
        headers={"Content-Type": "application/json"},
    )
    assert unknown.status_code == 404
    write = await v2.http.get(
        "/api/rpc/lessons.v2.ClassService/DeleteClass", params={"encoding": "json", "message": "{}"}
    )
    assert write.status_code == 405


@pytest.mark.parametrize("vercel", ["1", ""], ids=["on-vercel", "elsewhere"])
async def test_watch_class_is_not_served_and_says_so_after_the_gate(
    v2, v2_tokens, monkeypatch, served_settings, vercel
) -> None:
    monkeypatch.setattr(served_settings, "vercel", vercel)
    anonymous = await v2.stream("WatchService/WatchClass")
    assert (anonymous.code, anonymous.reason) == ("UNAUTHENTICATED", "DEVICE_TOKEN_INVALID")

    watched = await v2.stream("WatchService/WatchClass", token=v2_tokens["unlinked"])
    assert watched.status == 200
    assert (watched.code, watched.reason) == ("UNIMPLEMENTED", "FEATURE_UNSUPPORTED")
    assert watched.metadata == {"feature": "streaming"}


async def test_a_v2_that_cannot_be_imported_leaves_v1_serving(monkeypatch, caplog) -> None:
    """3a is production's first import of ``connectrpc`` and its native wheels.
    A failure there is caught: v2's prefixes answer 503, and v1 goes on."""
    monkeypatch.setitem(sys.modules, "app.rest", None)
    monkeypatch.setitem(sys.modules, "app.rpc", None)
    fresh = FastAPI()
    fresh.include_router(public_router)
    assert main.mount_v2(fresh) is False
    assert any("v2 could not be loaded" in record.getMessage() for record in caplog.records)

    transport = httpx.ASGITransport(app=fresh)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as http:
        assert (await http.get("/api/v1/health")).status_code == 200
        rest = await http.get("/api/v2/me")
        assert rest.status_code == 503
        assert rest.json()["error"]["status"] == "UNAVAILABLE"
        rpc = await http.post("/api/rpc/lessons.v2.MeService/GetMe", content=b"{}")
        assert rpc.status_code == 503
        assert rpc.json()["code"] == "unavailable"


def test_the_live_app_mounted_v2() -> None:
    """The other half: the app every test here reaches did load v2. The
    fallback mounts a route at the same path, so the path alone proves nothing."""
    route = next(r for r in main.app.routes if getattr(r, "path", None) == "/api/rpc")
    assert isinstance(route.app, _Services)  # type: ignore[attr-defined]
    assert main.app.state.v2_mounted is True
    # The host's root answers native gRPC from this same object (app/host.py).
    assert main.app.state.v2_services is route.app  # type: ignore[attr-defined]
    paths = {getattr(r, "path", None) for r in main.app.routes}
    assert "/api/v2/me" in paths and "/api/v2/class/scheduleWindows/{year}" in paths


async def test_warmup_says_whether_v2_is_mounted(v2, monkeypatch) -> None:
    assert (await v2.http.get("/api/v1/warmup")).json()["v2"] is True

    monkeypatch.setitem(sys.modules, "app.rpc", None)
    fresh = FastAPI()
    assert main.mount_v2(fresh) is False
    assert fresh.state.v2_mounted is False
    assert fresh.state.v2_services is None


_FALLBACK_PROBE = """
import asyncio, json, sys
sys.modules["connectrpc"] = None
import httpx
import app.main as m

async def go():
    transport = httpx.ASGITransport(app=m.app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as http:
        health = await http.get("/api/v1/health")
        rpc = await http.post("/api/rpc/lessons.v2.MeService/GetMe", content=b"{}")
        rest = await http.get("/api/v2/me")
        print(json.dumps([health.status_code, rpc.status_code, rpc.json(), rest.status_code,
                          m.app.state.v2_mounted, m.V2_UNAVAILABLE]))

asyncio.run(go())
"""


def test_a_connectrpc_that_cannot_be_imported_leaves_the_real_app_serving_v1() -> None:
    """The promise itself, in a fresh interpreter: were ``app.main`` to import
    ``app.rpc`` at the top, this would fail at import rather than answer 503."""
    import os

    env = {k: v for k, v in os.environ.items() if k != "VERCEL"}
    env.update({"BOT_TOKEN": "", "WEBHOOK_SECRET": "", "RUN_BOT": "false"})
    result = subprocess.run(
        [sys.executable, "-c", _FALLBACK_PROBE],
        cwd=str(Path(__file__).resolve().parents[1]),
        env=env,
        capture_output=True,
        text=True,
        timeout=120,
    )
    assert result.returncode == 0, result.stderr
    health, rpc_status, rpc_body, rest_status, mounted, sentence = json.loads(
        result.stdout.strip().splitlines()[-1]
    )
    assert health == 200
    assert (rpc_status, rest_status, mounted) == (503, 503, False)
    assert rpc_body == {"code": "unavailable", "message": sentence}


async def test_a_header_that_is_not_utf8_is_read_not_a_500(v2, unserved) -> None:
    """#338: ``connectrpc`` decodes headers as UTF-8 and answered ``unknown``
    with the exception's text. Compared with the same call without it."""
    url = "/api/rpc/lessons.v2.DiaryService/ListStudents"
    plain = await v2.http.post(url, content=b"{}", headers={"Content-Type": "application/json"})
    odd = await v2.http.post(
        url,
        content=b"{}",
        headers={"Content-Type": "application/json", "x-name": b"caf\xe9"},
    )
    assert plain.status_code == 501
    assert (odd.status_code, odd.json()) == (plain.status_code, plain.json())


async def _asgi(scope: dict) -> tuple[int, bytes]:
    sent: list[dict] = []

    async def receive() -> dict:
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(message: dict) -> None:
        sent.append(message)

    await rpc_app()(scope, receive, send)
    body = b"".join(m.get("body", b"") for m in sent if m["type"] == "http.response.body")
    return sent[0]["status"], body


def _get_scope(query: bytes) -> dict:
    return {
        "type": "http",
        "http_version": "1.1",
        "method": "GET",
        "scheme": "http",
        "path": "/lessons.v2.DiaryService/ListStudents",
        "root_path": "",
        "query_string": query,
        "headers": [],
        "client": ("203.0.113.9", 52144),
    }


async def test_a_query_string_that_is_not_utf8_is_read_not_a_500(unserved) -> None:
    clean = await _asgi(_get_scope(b"encoding=json&message=%7B%7D"))
    odd = await _asgi(_get_scope(b"encoding=json&message=%7B%7D&x=caf\xe9"))
    assert clean[0] == 501
    assert odd == clean


async def test_an_authorization_that_is_not_utf8_gets_the_gates_refusal(v2) -> None:
    odd = {"Authorization": b"Bearer caf\xe9"}
    refused = await v2.stream("WatchService/WatchClass", headers=odd)
    assert (refused.status, refused.code, refused.reason) == (
        200,
        "UNAUTHENTICATED",
        "DEVICE_TOKEN_INVALID",
    )


async def test_a_gzip_body_over_the_read_limit_stays_resource_exhausted(v2) -> None:
    """``except ConnectError: raise`` in the compression wrapper: the library's own
    refusal must not become «undecodable»."""
    response = await v2.http.post(
        "/api/rpc/lessons.v2.MeService/GetMe",
        content=gzip.compress(b"0" * (4 * 1024 * 1024 + 1)),
        headers={
            "Content-Type": "application/json",
            "Content-Encoding": "gzip",
            "Connect-Protocol-Version": "1",
        },
    )
    assert response.json()["code"] == "resource_exhausted"


async def test_an_answer_that_cannot_be_encoded_is_internal_with_the_fixed_sentence(
    v2, v2_tokens, monkeypatch
) -> None:
    """The Connect twin of the REST test for #339: the serialiser's own text
    must not reach the client as ``unknown``."""
    from app.contract.lessons.v2 import me_pb

    async def unwritable(call, request):
        return me_pb.UpdateTaskResponse(task=me_pb.Task(id=5, title="\ud800"))

    monkeypatch.setitem(HANDLERS, "lessons.v2.MeService/UpdateTask", unwritable)
    answer = await v2.connect(
        "MeService/UpdateTask",
        me_pb.UpdateTaskRequest(task=me_pb.Task(id=5)),
        token=v2_tokens["viewer"],
    )
    assert answer.status == 500
    assert answer.code == "INTERNAL"
    assert answer.error == "The server failed to answer this request"
    assert "ud800" not in str(answer.body).lower()


async def test_the_peer_reaching_invoke_is_the_host_without_its_port(v2, monkeypatch) -> None:
    seen: dict[str, object] = {}

    async def invoke(method, request, *, headers, peer):
        seen["peer"] = peer
        return method.output()

    monkeypatch.setattr(rpc_module, "invoke", invoke)
    answer = await v2.connect("MeService/GetMe")
    assert answer.status == 200
    assert seen["peer"] == "203.0.113.9"
