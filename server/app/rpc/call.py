"""One call of one v2 method: its gate, its scope, its handler, its commit, its effects.

Both transports call :func:`invoke` for every unary method — the RPC adapters
with what Connect's ``RequestContext`` carries, the REST transcoder with what
Starlette's request does — so REST and RPC cannot disagree about a rule
(``docs/specs/2026-10-05-server-v2-design.md``, decisions 3 and 4). A handler
is a plain ``async`` function of ``(call, request)`` that knows neither. The
one stream, which REST cannot carry, is :func:`stream`'s, and its handler an
async generator (below).

**The scope.** Each call opens a dishka ``REQUEST`` scope from the process
container, the way the bot's ``ContextMiddleware`` does and not through
``setup_dishka``: it reads ``di.container()`` per call, because a mounted ASGI
app gets no lifespan and a captured container would outlive a shutdown.

**The commit is here, and only here.** On success the session commits, then
the call's effects run in order with the session still open — a Telegram
notice reads its recipients from it — and an effect that fails is logged and
dropped, as v1's ``edit._tell`` is: the change is already saved. On a refusal
the session rolls back and no effect runs. ``test_rpc_call.py`` greps
``app/rpc`` for ``.commit(`` outside this module.

**What a refusal does not roll back, on purpose.** Services that commit inside
themselves keep doing so, and their writes stay when the call is refused:

- ``JoinThrottle.admit`` — a wrong join code stays counted, and so does a
  search of the anonymous school directory;
- ``security.DiaryAttempt``, through ``services.diary.register`` — an attempt
  to keep a diary session stays counted, as a failure or as a session opened,
  and a session opened is committed with it;
- ``quota.spend`` — a unit of the directory's daily allowance stays spent,
  because the directory counted the request it paid for either way;
- ``services.join.join`` — the device token it mints is committed inside
  the call, as v1's ``/join`` committed it, so a refusal raised after it
  would not take the phone's token back (``CreateDevice`` raises none);
- ``services.diary.find_session`` (and ``_expire``, ``_remember_token``) — a
  dead diary credential stays expired, a rotated one stays kept;
- ``api.deps.touch_last_seen`` — the device stays seen.

Each has a test over v2.

**A stream holds no session** (decision 13). :func:`stream` serves a
server-streaming method: the gate runs in a scope of its own, committed and
closed before the first message, and again in another each time the handler
calls :meth:`Stream.recheck`. One scope held per watcher would hold a pooled
connection for as long as a phone is in the foreground, and the pool is
5 + 10. A refusal from any of those gates ends the stream with the gate's own
answer, worded by the one table as every refusal is.
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator, Awaitable, Callable, Sequence
from contextlib import aclosing
from dataclasses import dataclass, field
from typing import Any

from connectrpc.code import Code
from connectrpc.errors import ConnectError
from protobuf import Message
from sqlalchemy.ext.asyncio import AsyncSession

from app import di
from app.api.deps import caller_bucket
from app.config import Settings
from app.models import DeviceToken, DiarySession, Role, SchoolClass
from app.rpc import gate
from app.rpc.errors import connect_error
from app.rpc.handlers import HANDLERS, STREAMS
from app.rpc.methods import Method

log = logging.getLogger(__name__)

#: What a method with no handler answers, on both transports: the generated
#: ``Protocol``'s own default, word for word, before any gate or scope.
#: ``test_rpc_call.py`` holds the two level.
NOT_IMPLEMENTED = "Not implemented"

Effect = Callable[[], Awaitable[None]]


@dataclass
class Call:
    """What a handler is handed: everything the gate found, and the session."""

    method: Method
    session: AsyncSession
    settings: Settings
    # Out of the repr: the lines include `Authorization: Bearer …`, and a Call
    # lands in tracebacks and debug logs. The ORM rows print no columns.
    headers: Sequence[tuple[str, str]] = field(repr=False)
    #: The caller's host, without a port; ``None`` when the server knows none.
    peer: str | None
    client_version: int | None = None
    device: DeviceToken | None = None
    school_class: SchoolClass | None = None
    role: Role | None = None
    diary: DiarySession | None = None
    effects: list[Effect] = field(default_factory=list)

    def bucket(self, scope: str = "") -> str:
        """The caller's rate-limit bucket — v1's, for the same caller."""
        return caller_bucket(self.headers, self.peer, scope=scope)

    def after_commit(self, effect: Effect) -> None:
        """Run ``effect`` once the call's change is committed, and never if it is not."""
        self.effects.append(effect)

    def device_and_class(self) -> tuple[DeviceToken, SchoolClass]:
        """The device and its class, which the gate set for every device method."""
        if self.device is None or self.school_class is None:
            raise RuntimeError(f"{self.method.key} asked for a device it does not take")
        return self.device, self.school_class


@dataclass
class Stream:
    """What a streaming handler is handed: the caller the gate admitted, and no session."""

    method: Method
    settings: Settings
    # Out of the repr, as `Call`'s: the lines include `Authorization: Bearer …`.
    headers: Sequence[tuple[str, str]] = field(repr=False)
    peer: str | None
    class_id: int
    device_id: int
    role: Role | None = None

    async def recheck(self) -> None:
        """The gate again, in a scope of its own, or the refusal it makes.

        On every event and every heartbeat: a device revoked, or its class
        deleted with its devices, since the last check is refused here, as a
        unary call would be, and the stream ends with that refusal.
        """
        self.settings, admitted = await _admitted(self.method, self.headers)
        self.device_id, self.class_id = _device_and_class(self.method, admitted)
        self.role = admitted.role


def _device_and_class(method: Method, admitted: gate.Admitted) -> tuple[int, int]:
    if admitted.device is None or admitted.school_class is None:
        raise RuntimeError(f"{method.key} streams to a caller that is no device")
    return admitted.device.id, admitted.school_class.id


async def _admitted(
    method: Method, headers: Sequence[tuple[str, str]]
) -> tuple[Settings, gate.Admitted]:
    """The gate in a scope of its own: committed — the device stays seen — and closed."""
    async with di.container()() as scope:
        session = await scope.get(AsyncSession)
        settings = await scope.get(Settings)
        try:
            admitted = await gate.admit(method, session, settings, headers)
            await session.commit()
        except Exception:
            await session.rollback()
            raise
        return settings, admitted


async def stream(
    method: Method,
    request: Message,
    *,
    headers: Sequence[tuple[str, str]],
    peer: str | None,
) -> AsyncIterator[Any]:
    """Serve one call of a streaming ``method``, message by message, or raise
    the ``ConnectError`` it ends with."""
    handler = STREAMS.get(method.key)
    if handler is None:
        raise ConnectError(Code.UNIMPLEMENTED, NOT_IMPLEMENTED)
    try:
        settings, admitted = await _admitted(method, headers)
        device_id, class_id = _device_and_class(method, admitted)
        call = Stream(
            method=method,
            settings=settings,
            headers=headers,
            peer=peer,
            class_id=class_id,
            device_id=device_id,
            role=admitted.role,
        )
        # `aclosing`, because closing this generator — the client gone, the
        # server shutting down — does not close the one it is iterating: the
        # handler's own `finally`, which forgets the watcher, would otherwise
        # wait for the garbage collector.
        async with aclosing(handler(call, request)) as messages:
            async for message in messages:
                yield message
    except ConnectError:
        raise
    except Exception as failure:
        raise connect_error(failure) from None


async def _run_effects(call: Call) -> None:
    for effect in call.effects:
        try:
            await effect()
        except Exception:
            log.warning("an effect of %s failed after its commit", call.method.key, exc_info=True)


async def invoke(
    method: Method,
    request: Message,
    *,
    headers: Sequence[tuple[str, str]],
    peer: str | None,
) -> Any:
    """Serve one call of ``method``, or raise the ``ConnectError`` it answers with."""
    handler = HANDLERS.get(method.key)
    if handler is None:
        raise ConnectError(Code.UNIMPLEMENTED, NOT_IMPLEMENTED)
    try:
        async with di.container()() as scope:
            session = await scope.get(AsyncSession)
            settings = await scope.get(Settings)
            try:
                admitted = await gate.admit(method, session, settings, headers)
                call = Call(
                    method=method,
                    session=session,
                    settings=settings,
                    headers=headers,
                    peer=peer,
                    client_version=admitted.client_version,
                    device=admitted.device,
                    school_class=admitted.school_class,
                    role=admitted.role,
                    diary=admitted.diary,
                )
                response = await handler(call, request)
                await session.commit()
            except Exception:
                await session.rollback()
                raise
            await _run_effects(call)
            return response
    except ConnectError:
        raise
    except Exception as failure:
        raise connect_error(failure) from None
