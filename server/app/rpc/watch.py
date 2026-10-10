"""``WatchService``: the class changing, as it happens — a beta of the host target.

``WatchClass`` streams revisions, never data (decision 13 of
``docs/specs/2026-10-05-server-v2-design.md``): on open, the class's revision
now; then a new one whenever a commit changes the class — whichever shell made
it, v1, v2 or the bot, as long as this process made it (``app/watch.py``); and
every :data:`HEARTBEAT_SECONDS` the one it has, so that a connection that quiet
is known to be alive and nothing between closes it as idle. Before every
message after the first the gate runs again (``call.Stream.recheck``): a
device revoked, or its class deleted with its devices, ends the stream with
``UNAUTHENTICATED`` / ``DEVICE_TOKEN_INVALID`` at the next change or
heartbeat. Changes made while the watcher is busy are one message, read as it
is sent, so none is lost.

**A phone holds one stream.** A newer ``WatchClass`` from the same device
ends the one it held before: the older wakes at once, sees it is no longer
the device's, and returns, so it ends cleanly — with no error — and its
watcher is forgotten, while every other phone's stream goes on. A phone gone
from coverage without closing its connection is noticed only when the
kernel gives up on that connection — about a quarter of an hour on Linux's
defaults, by calculation, since neither server, as ``app.host`` runs it,
pings an HTTP/2 peer — and until then its stream runs the gate at every
heartbeat, on the one pool. When the phone comes back and opens a stream,
that orphan ends then rather than standing beside the new one, and a client
reconnecting in a loop holds one stream, not a growing number. The map is
this process's alone and writes nothing, which the host's single instance
allows (``app/watch.py``).

Where streaming is off — on Vercel always, and wherever ``LESSONS_STREAMING``
is not true — the method says the feature is not here, after the gate has
checked the caller like any other (decision 7).
"""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import AsyncGenerator, Callable, Iterator
from typing import TYPE_CHECKING

from app import watch as bus
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.watch_pb import WatchClassRequest, WatchClassResponse
from app.rpc import values
from app.rpc.errors import Refusal

if TYPE_CHECKING:
    from app.rpc.call import Stream

#: ``FEATURE_UNSUPPORTED``'s ``feature`` for a server capability, as
#: ``errors.proto`` allows beside a ``DiaryFeature`` name.
STREAMING = "streaming"

NOT_HERE = "Streaming is served by the host target, not by this deployment"

#: How long a stream stays quiet before it says the same revision again. Under
#: the idle timeouts a proxy or a mobile network keeps — Envoy's own is five
#: minutes — and long enough that a class's watchers cost the database one
#: gate's reads each per half a minute.
HEARTBEAT_SECONDS = 30.0

#: Each device's stream, by the device's id: the event that stream waits on.
#: Setting it is how a newer stream from the device ends the older one, which
#: is otherwise asleep until its class changes or its heartbeat is due.
_held: dict[int, asyncio.Event] = {}


@contextlib.contextmanager
def _one_stream_per_device(device_id: int, woken: asyncio.Event) -> Iterator[Callable[[], bool]]:
    """Make the stream waiting on ``woken`` the device's one, waking the one it
    replaces; yields whether a newer one has replaced this one since."""
    older = _held.get(device_id)
    _held[device_id] = woken
    if older is not None:
        older.set()
    try:
        yield lambda: _held.get(device_id) is not woken
    finally:
        # A replaced stream leaves the newer one's place alone.
        if _held.get(device_id) is woken:
            del _held[device_id]


async def watch_class(
    call: Stream, request: WatchClassRequest
) -> AsyncGenerator[WatchClassResponse, None]:
    """The class's revision now, then again whenever it changes or it is time,
    until the device opens a newer stream."""
    if call.settings.behind_vercel or not bus.listening():
        raise Refusal(ErrorReason.FEATURE_UNSUPPORTED, NOT_HERE, feature=STREAMING)
    with (
        bus.watching(call.class_id) as woken,
        _one_stream_per_device(call.device_id, woken) as replaced,
    ):
        while True:
            yield _now(call.class_id)
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(woken.wait(), HEARTBEAT_SECONDS)
            woken.clear()
            if replaced():
                # Ended, not refused: nothing is wrong with the phone, and the
                # gate is not asked again for a stream nobody reads.
                return
            await call.recheck()


def _now(class_id: int) -> WatchClassResponse:
    return WatchClassResponse(
        revision=bus.revision(class_id),
        changed_at=values.maybe_instant(bus.changed_at(class_id)),
    )
