"""What leaves for Sentry, and what never does (152-ФЗ).

Two halves. The scrubbing hooks are asked directly, with an event carrying
everything the SDK could ever put in one. And one event is built for real: a
fresh interpreter starts Sentry exactly as ``app.main`` does, sends a request
carrying a diary token and a child's name through a route that fails, and
hands back what the SDK would have put on the wire. Fresh, because Sentry's
integrations patch Starlette for the life of a process, and this suite's
process serves a thousand other requests.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

from app import observability
from app.config import Settings, get_settings

SERVER = Path(__file__).resolve().parents[1]

TOKEN = "dt_9f3b2c7e1a5d4f6b8c0e2a4d6f8b0c1e"
CHILD = "Иванова Мария"
FAKE_DSN = "https://public@o0.ingest.de.sentry.io/0"
ROUTE = "/api/v1/diary/students/{student_id}/schedule"


def _everything() -> dict:
    """An error event as the SDK builds one, with every field that could
    carry the token or the name filled with them."""
    return {
        "event_id": "e" * 32,
        "timestamp": "2026-10-06T12:00:00Z",
        "level": "error",
        "platform": "python",
        "sdk": {"name": "sentry.python", "version": "2.71.0"},
        "release": "a" * 40,
        "environment": "production",
        "server_name": "ip-10-0-0-1",
        "transaction": ROUTE,
        "transaction_info": {"source": "route"},
        "message": f"failed for {CHILD}",
        "logentry": {"message": "diary read for %s", "params": [CHILD]},
        "exception": {
            "values": [
                {
                    "type": "ValueError",
                    "module": None,
                    "value": f"no lessons for {CHILD}",
                    "mechanism": {
                        "type": "starlette",
                        "handled": False,
                        # A chained or grouped exception's own links: safe to
                        # keep, because none of the four is a message or a
                        # value a caller could have written.
                        "exception_id": 0,
                        "parent_id": 1,
                        "source": "__cause__",
                        "is_exception_group": False,
                        "data": {"t": TOKEN},
                    },
                    "stacktrace": {
                        "frames": [
                            {
                                "filename": "app/api/diary.py",
                                "abs_path": f"/home/{CHILD}/app/api/diary.py",
                                "function": "schedule",
                                "module": "app.api.diary",
                                "lineno": 120,
                                "in_app": True,
                                "context_line": f'    name = "{CHILD}"',
                                "pre_context": [TOKEN],
                                "vars": {"token": TOKEN, "name": CHILD},
                            }
                        ]
                    },
                }
            ]
        },
        "request": {
            "url": f"https://lessons.example/api/v1/diary/students/42/schedule?child={CHILD}",
            "method": "GET",
            "headers": {"authorization": f"Bearer {TOKEN}"},
            "cookies": {"session": TOKEN},
            "query_string": f"child={CHILD}",
            "data": {"name": CHILD},
        },
        "breadcrumbs": {"values": [{"message": f"signed in as {CHILD}"}]},
        "extra": {"token": TOKEN},
        "user": {"id": "42", "username": CHILD},
        "tags": {"child": CHILD},
        "modules": {"fastapi": "0.141.1"},
        "contexts": {
            "trace": {
                "trace_id": "1" * 32,
                "span_id": "2" * 16,
                "op": "http.server",
                "status": "internal_error",
                "data": {"url": f"/schedule?child={CHILD}"},
                "dynamic_sampling_context": {"transaction": ROUTE},
            },
            "runtime": {"name": "CPython", "version": "3.12"},
        },
    }


def _leaks(event: dict) -> list[str]:
    dumped = json.dumps(event, ensure_ascii=False)
    return [secret for secret in (TOKEN, CHILD, "Иванова", "Мария") if secret in dumped]


def test_an_error_keeps_its_type_its_place_and_its_route_and_nothing_else():
    scrubbed = observability.scrub(_everything())

    assert _leaks(scrubbed) == []
    assert set(scrubbed) == {
        "event_id", "timestamp", "level", "platform", "sdk", "release", "environment",
        "transaction", "transaction_info", "exception", "contexts",
    }
    (value,) = scrubbed["exception"]["values"]
    assert value["type"] == "ValueError" and "value" not in value
    assert value["stacktrace"]["frames"] == [
        {
            "filename": "app/api/diary.py",
            "function": "schedule",
            "module": "app.api.diary",
            "lineno": 120,
            "in_app": True,
        }
    ]
    assert value["mechanism"] == {
        "type": "starlette",
        "handled": False,
        "exception_id": 0,
        "parent_id": 1,
        "source": "__cause__",
        "is_exception_group": False,
    }
    assert scrubbed["transaction"] == ROUTE
    assert scrubbed["contexts"] == {
        "trace": {
            "trace_id": "1" * 32,
            "span_id": "2" * 16,
            "op": "http.server",
            "status": "internal_error",
        }
    }


def test_a_timed_request_keeps_its_route_and_its_duration_and_nothing_else():
    timed = {
        **_everything(),
        "type": "transaction",
        "start_timestamp": "2026-10-06T11:59:59Z",
        "spans": [
            {"op": "http.client", "description": f"GET https://dnevnik2/?child={CHILD}"},
            {"op": "db", "description": "SELECT * FROM diary_sessions WHERE token_hash = ?"},
        ],
        "measurements": {"x": {"value": 1}},
    }
    scrubbed = observability.scrub_transaction(timed)

    assert _leaks(scrubbed) == []
    assert scrubbed["spans"] == []
    assert (scrubbed["transaction"], scrubbed["start_timestamp"], scrubbed["timestamp"]) == (
        ROUTE, "2026-10-06T11:59:59Z", "2026-10-06T12:00:00Z",
    )
    assert "request" not in scrubbed and "measurements" not in scrubbed


def test_a_path_no_route_matched_is_never_the_name():
    """A path the router did not match is whatever the caller sent: a
    calendar feed's carries its secret. A Connect method's names a service
    and a method, and nothing a caller could hide a value in."""

    def named(transaction: str) -> str:
        event = {"transaction": transaction, "transaction_info": {"source": "url"}}
        return observability.scrub(event)["transaction"]

    assert named(f"http://test/api/v1/calendar/{TOKEN}.ics") == observability.UNMATCHED
    assert named(f"http://test/api/rpc/lessons.v2.X/{CHILD}") == observability.UNMATCHED
    assert (
        named("http://test:None/api/rpc/lessons.v2.SubjectService/ListSubjects")
        == "/api/rpc/lessons.v2.SubjectService/ListSubjects"
    )


async def test_a_bot_error_reaches_sentry_only_where_it_is_set(monkeypatch):
    from app.bot import bot as bot_module

    captured: list[BaseException] = []
    monkeypatch.setattr(observability, "capture", captured.append)
    failure = RuntimeError(f"a handler failed for {CHILD}")
    event = SimpleNamespace(update=SimpleNamespace(callback_query=None), exception=failure)

    monkeypatch.setattr(get_settings(), "sentry_dsn", "")
    await bot_module._on_error(event)
    assert captured == []

    monkeypatch.setattr(get_settings(), "sentry_dsn", FAKE_DSN)
    await bot_module._on_error(event)
    assert captured == [failure]


def test_the_sample_of_timings_is_the_owner_s_five_percent():
    assert observability.TRACES_SAMPLE_RATE == 0.05
    assert Settings(SENTRY_DSN=f" {FAKE_DSN} ").sentry_configured
    assert not Settings(SENTRY_DSN="  ").sentry_configured


def test_a_failure_starting_sentry_is_logged_by_type_and_does_not_propagate(monkeypatch):
    """`app.main`'s guard: whatever `sentry_sdk.init` could still raise past
    `Settings`' own shape check must not take the whole API down with it, and
    the log line must not repeat whatever the exception's message carried -
    that is where a real DSN's key would show up."""
    import app.main as main_module

    monkeypatch.setattr(get_settings(), "sentry_dsn", FAKE_DSN)

    def boom(settings):
        raise RuntimeError(f"a secret right here: {TOKEN}")

    monkeypatch.setattr(observability, "init", boom)

    messages: list[str] = []
    monkeypatch.setattr(main_module.log, "error", lambda fmt, *args: messages.append(fmt % args))

    main_module._start_sentry()  # must not raise

    assert messages == ["Sentry did not start: RuntimeError"]


#: Starts Sentry as ``app.main`` does, through a transport that keeps what it
#: is handed, and fails one request that carries a diary token in its header
#: and a child's name in its query, its body, its exception and a local. A
#: second request matches no route at all, with the token in its path - the
#: one place a route template can never carry it.
_REQUEST = """
import asyncio, json, logging
import httpx
from fastapi import FastAPI
from sentry_sdk.transport import Transport

from app import observability
from app.config import Settings

TOKEN, CHILD, ROUTE = {token!r}, {child!r}, {route!r}
caught = []
types = []
headers = []


class Keep(Transport):
    def capture_envelope(self, envelope):
        headers.append(dict(envelope.headers))
        for item in envelope.items:
            types.append(item.type)
            if item.payload.json is not None:
                caught.append(item.payload.json)


observability.init(Settings(SENTRY_DSN={dsn!r}), transport=Keep(), traces_sample_rate=1.0)
app = FastAPI()


@app.post(ROUTE)
async def schedule(student_id: int, child: str = ""):
    diary_token = TOKEN
    logging.getLogger("app").error("reading the diary of %s", child)
    raise ValueError(f"no lessons for {{child}} with {{diary_token}}")


async def main():
    transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        answer = await client.post(
            "/api/v1/diary/students/42/schedule",
            params={{"child": CHILD}},
            json={{"name": CHILD}},
            headers={{"Authorization": f"Bearer {{TOKEN}}", "Cookie": f"s={{TOKEN}}"}},
        )
        assert answer.status_code == 500
        unmatched = await client.get(f"/no-such-route/{{TOKEN}}")
        assert unmatched.status_code == 404


asyncio.run(main())
import sentry_sdk
sentry_sdk.flush()
print(json.dumps(
    {{"events": caught, "types": types, "headers": headers}}, ensure_ascii=False, default=str,
))
"""


def test_an_event_built_from_a_request_leaves_with_neither_the_token_nor_the_child_s_name():
    script = _REQUEST.format(token=TOKEN, child=CHILD, route=ROUTE, dsn=FAKE_DSN)
    result = subprocess.run(
        [sys.executable, "-c", script],
        cwd=str(SERVER),
        env={**os.environ, "SENTRY_DSN": FAKE_DSN, "PYTHONIOENCODING": "utf-8"},
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=120,
    )
    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout.strip().splitlines()[-1])
    sent, types, envelope_headers = payload["events"], payload["types"], payload["headers"]

    # Exactly the two kinds the design describes - not "everything that is
    # not a transaction", which would also pass a release-health or a
    # client-report item silently, had either been left on.
    assert set(types) == {"event", "transaction"}
    # The envelope's own header, never an item's: 2.71.0 always sets these
    # two, and the dynamic sampling context - which would carry the route -
    # is already gone by the time this is built, because scrub dropped it
    # from `contexts.trace` first.
    for header in envelope_headers:
        assert set(header) <= {"event_id", "sent_at"}
    assert _leaks(sent) == []

    errors = [event for event in sent if event.get("type") != "transaction"]
    timings = [event for event in sent if event.get("type") == "transaction"]
    assert len(errors) == 1 and len(timings) == 2
    (value,) = errors[0]["exception"]["values"]
    assert value["type"] == "ValueError"
    assert any(frame.get("function") == "schedule" for frame in value["stacktrace"]["frames"])
    assert errors[0]["transaction"] == ROUTE
    by_route = {timing["transaction"]: timing for timing in timings}
    assert set(by_route) == {ROUTE, observability.UNMATCHED}
    assert all(timing["spans"] == [] for timing in timings)


#: A caller's own `sentry-trace` header, claiming the trace is sampled -
#: nothing upstream of this server actually propagates one, so the sampler
#: must decide on the rate alone, never on what a caller claims.
_TRACE_HEADER_REQUEST = """
import asyncio, json
import httpx
from fastapi import FastAPI
from sentry_sdk.transport import Transport

from app import observability
from app.config import Settings

types = []


class Keep(Transport):
    def capture_envelope(self, envelope):
        for item in envelope.items:
            types.append(item.type)


observability.init(Settings(SENTRY_DSN={dsn!r}), transport=Keep(), traces_sample_rate=0.0)
app = FastAPI()


@app.get("/ping")
async def ping():
    return {{"ok": True}}


async def main():
    transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        answer = await client.get(
            "/ping",
            headers={{"sentry-trace": "1" * 32 + "-" + "2" * 16 + "-1"}},
        )
    assert answer.status_code == 200


asyncio.run(main())
import sentry_sdk
sentry_sdk.flush()
print(json.dumps(types))
"""


def test_a_caller_s_sentry_trace_header_cannot_force_sampling():
    script = _TRACE_HEADER_REQUEST.format(dsn=FAKE_DSN)
    result = subprocess.run(
        [sys.executable, "-c", script],
        cwd=str(SERVER),
        env={**os.environ, "SENTRY_DSN": FAKE_DSN},
        capture_output=True,
        text=True,
        timeout=120,
    )
    assert result.returncode == 0, result.stderr
    types = json.loads(result.stdout.strip().splitlines()[-1])
    assert "transaction" not in types
