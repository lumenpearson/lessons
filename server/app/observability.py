"""Sentry: each error with its place, a sample of timings, and nothing a person wrote.

Imported only where ``SENTRY_DSN`` is set. ``app.main`` and the bot's error
handler ask the setting before they import this module, so a deployment
without it never loads ``sentry_sdk`` and its cold start is what it was
(``tests/test_cold_start.py``). Deleting the setting turns all of it off with
no change to the code.

What leaves, for each error: the exception's type and its stack - file,
function, line - the route template (the path as the router declares it,
``{student_id}`` and never the number), the release (the commit) and the
environment. For 5 % of requests, chosen at random: the route template and
how long the request took. Those are Sentry's request graphs.

What never leaves, because it can carry a child's name, a family's credential
or what somebody typed (152-ФЗ): request and response bodies; headers, cookies
and query strings; the exception's message; local variables; source lines;
log lines and breadcrumbs; the spans inside a request, whose descriptions are
a diary URL with its query or an SQL statement. The SDK is told not to
collect most of it, and :func:`scrub` and :func:`scrub_transaction` then
rebuild every event from a list of what may stay, so a field the SDK starts
sending in a later release stays here unless somebody adds it to that list.
``tests/test_observability.py`` is the proof.
"""

from __future__ import annotations

import re
from typing import Any
from urllib.parse import urlsplit

import sentry_sdk
from sentry_sdk.integrations.fastapi import FastApiIntegration
from sentry_sdk.integrations.starlette import StarletteIntegration

from app.config import Settings, deployment

#: The share of requests whose timing is sent: well inside the free tier at
#: this project's traffic, and enough to draw the graphs (the design's
#: question 3, answered by the owner).
TRACES_SAMPLE_RATE = 0.05

#: What a request no route template matched is called: its path is whatever
#: the caller sent, and a calendar feed's path carries its secret.
UNMATCHED = "unmatched route"

#: A Connect method's path names a service and a method and nothing else, so
#: it may stand for a route; anything else under the mount may not.
_CONNECT_METHOD = re.compile(r"/api/rpc/lessons\.v2\.[A-Za-z]+/[A-Za-z]+")

_EVENT_KEYS = ("event_id", "timestamp", "level", "platform", "sdk", "release", "environment")
_TRANSACTION_KEYS = (
    "event_id",
    "type",
    "timestamp",
    "start_timestamp",
    "platform",
    "sdk",
    "release",
    "environment",
)
_FRAME_KEYS = ("filename", "function", "module", "lineno", "in_app")
_TRACE_KEYS = ("trace_id", "span_id", "parent_span_id", "op", "status")
#: ``type`` and ``handled`` say what caught it; the other four link one
#: exception in a chain or a group to the next - integers, a fixed word and a
#: boolean, none of them a message or a value a caller could have written.
_MECHANISM_KEYS = (
    "type", "handled", "exception_id", "parent_id", "source", "is_exception_group",
)


def init(
    settings: Settings,
    *,
    transport: Any = None,
    traces_sample_rate: float = TRACES_SAMPLE_RATE,
) -> None:
    """Start Sentry for this process. ``transport`` and the rate are for the tests."""
    running = deployment()
    sentry_sdk.init(
        dsn=settings.sentry_dsn_value,
        # Vercel's own name for the environment, so a preview's errors stay
        # apart from production's.
        environment=running.environment or "self-hosted",
        release=running.commit or None,
        transport=transport,
        # The two integrations a request needs and nothing else: the default
        # set would add log lines, the process's arguments and its modules,
        # and the automatic set would patch httpx and aiohttp to put
        # Sentry's headers on the diary's and Telegram's requests.
        default_integrations=False,
        auto_enabling_integrations=False,
        # Release health (a per-minute count of crashed and errored sessions)
        # and client reports (a count of events this process dropped and
        # why): both are envelope items neither hook below is asked to scrub,
        # keyed to no request and carrying no route, so the rule - nothing
        # leaves unless it is on the list - switches them off rather than
        # trusting them to stay harmless.
        auto_session_tracking=False,
        send_client_reports=False,
        integrations=[
            StarletteIntegration(transaction_style="url"),
            FastApiIntegration(transaction_style="url"),
        ],
        # Not `traces_sample_rate` alone: with no `traces_sampler`, an
        # incoming `sentry-trace` header's own sampled flag overrides it, and
        # nothing upstream of this server propagates a Sentry trace - so an
        # incoming decision is never ours to honour.
        traces_sampler=lambda _context: traces_sample_rate,
        send_default_pii=False,
        include_local_variables=False,
        include_source_context=False,
        max_breadcrumbs=0,
        max_request_body_size="never",
        trace_propagation_targets=[],
        before_send=scrub,
        before_send_transaction=scrub_transaction,
    )


def capture(error: BaseException) -> None:
    """Send one error the code caught itself: the bot's, which the webhook
    answers 200 for whatever happened, so no integration ever sees it."""
    sentry_sdk.capture_exception(error)


def scrub(event: dict[str, Any], hint: Any = None) -> dict[str, Any]:
    """An error event, rebuilt from what may leave and nothing else."""
    kept = {key: event[key] for key in _EVENT_KEYS if key in event}
    kept.update(_route(event))
    values = (event.get("exception") or {}).get("values") or []
    if values:
        kept["exception"] = {"values": [_exception(value) for value in values]}
    trace = _trace(event)
    if trace:
        kept["contexts"] = {"trace": trace}
    return kept


def scrub_transaction(event: dict[str, Any], hint: Any = None) -> dict[str, Any]:
    """A timed request, rebuilt the same way: its route and its duration."""
    kept = {key: event[key] for key in _TRANSACTION_KEYS if key in event}
    kept.update(_route(event))
    trace = _trace(event)
    if trace:
        kept["contexts"] = {"trace": trace}
    kept["spans"] = []
    return kept


def _route(event: dict[str, Any]) -> dict[str, Any]:
    name = event.get("transaction")
    if not name:
        return {}
    source = (event.get("transaction_info") or {}).get("source")
    if getattr(source, "value", source) == "route":
        return {"transaction": name, "transaction_info": {"source": "route"}}
    path = urlsplit(str(name)).path
    if _CONNECT_METHOD.fullmatch(path):
        return {"transaction": path, "transaction_info": {"source": "route"}}
    return {"transaction": UNMATCHED, "transaction_info": {"source": "custom"}}


def _exception(value: dict[str, Any]) -> dict[str, Any]:
    kept: dict[str, Any] = {"type": value.get("type"), "module": value.get("module")}
    mechanism = value.get("mechanism") or {}
    if mechanism:
        kept["mechanism"] = {key: mechanism[key] for key in _MECHANISM_KEYS if key in mechanism}
    frames = (value.get("stacktrace") or {}).get("frames") or []
    if frames:
        kept["stacktrace"] = {
            "frames": [{key: frame[key] for key in _FRAME_KEYS if key in frame} for frame in frames]
        }
    return kept


def _trace(event: dict[str, Any]) -> dict[str, Any]:
    trace = (event.get("contexts") or {}).get("trace") or {}
    return {key: trace[key] for key in _TRACE_KEYS if trace.get(key) is not None}
