"""``WatchClass``: the class's revision on open, on every change, and on a heartbeat.

A stream that works never ends, and httpx's ASGI transport answers only once
an answer has ended — so each stream here is driven through the app message
by message, as an HTTP server drives it, over Connect's streaming protocol and,
once, over native gRPC with the scope an HTTP/2 server builds. The bus is
attached for each test, as ``app.main`` attaches it where streaming is on, and
the heartbeat is put out of reach, or to a moment where a test waits for one, so
that what the design promises
(``docs/specs/2026-10-05-server-v2-design.md``, decision 13) is asked in
seconds: a revision on open, a new one for a change any shell makes, the same
one again when nothing changes, the gate asked again each time, and nothing
held between.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import struct
from datetime import date
from typing import Any

import pytest
from connectrpc.errors import ConnectError
from sqlalchemy import update

from app import watch
from app.contract.lessons.v2.homework_pb import CreateHomeworkRequest, Homework
from app.contract.lessons.v2.me_pb import CreateTaskRequest, Task
from app.contract.lessons.v2.school_class_pb import DeleteClassRequest
from app.contract.lessons.v2.watch_pb import WatchClassResponse
from app.db import SessionLocal, engine
from app.models import DeviceToken, SchoolClass
from app.rpc import watch as rpc_watch
from app.security import hash_token
from app.services import homework as homework_service

PATH = "/api/rpc/lessons.v2.WatchService/WatchClass"
#: The heartbeat of a test that does not wait for one: a message that comes
#: within PATIENCE is a change's, never the heartbeat's.
QUIET = 30.0
#: The heartbeat of a test that waits for one: a moment.
BEAT = 0.3
#: How long a test waits for a message it is owed: generous, because a loaded
#: CI worker is slow, and a stream that works answers in milliseconds.
PATIENCE = 5.0


@pytest.fixture
def streaming(monkeypatch):
    """Streaming on, as on a host: the bus attached, and no heartbeat in sight."""
    already = watch.listening()
    watch.attach()
    monkeypatch.setattr(rpc_watch, "HEARTBEAT_SECONDS", QUIET)
    yield watch
    if not already:
        watch.detach()


@pytest.fixture
def beating(streaming, monkeypatch):
    """Streaming on, with a heartbeat a test can wait for."""
    monkeypatch.setattr(rpc_watch, "HEARTBEAT_SECONDS", BEAT)
    return streaming


class _Watcher:
    """One ``WatchClass`` call, driven through the app as a server drives it."""

    def __init__(self, token: str | None, *, grpc: bool = False) -> None:
        self.token, self.grpc = token, grpc
        self.frames: asyncio.Queue[tuple[int, bytes] | None] = asyncio.Queue()
        self.status: int | None = None
        self.trailers: dict[bytes, bytes] = {}
        self.error: dict[str, Any] | None = None
        self._gone = asyncio.Event()
        self._task: asyncio.Task[None] | None = None

    async def __aenter__(self) -> _Watcher:
        from app.main import app

        payload = b"" if self.grpc else b"{}"
        body = struct.pack(">BI", 0, len(payload)) + payload
        headers = [
            (b"content-type", b"application/grpc" if self.grpc else b"application/connect+json")
        ]
        if self.grpc:
            headers.append((b"te", b"trailers"))
        if self.token is not None:
            headers.append((b"authorization", f"Bearer {self.token}".encode()))
        scope = {
            "type": "http",
            "http_version": "2" if self.grpc else "1.1",
            "method": "POST",
            "scheme": "http",
            "path": PATH,
            "raw_path": PATH.encode(),
            "root_path": "",
            "query_string": b"",
            "headers": headers,
            "client": ("203.0.113.9", 52144),
            "server": ("test", 80),
            "extensions": {"http.response.trailers": {}},
        }
        delivered = False

        async def receive() -> dict[str, Any]:
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": body, "more_body": False}
            await self._gone.wait()
            return {"type": "http.disconnect"}

        buffer = b""

        async def send(message: dict[str, Any]) -> None:
            nonlocal buffer
            if message["type"] == "http.response.start":
                self.status = message["status"]
            elif message["type"] == "http.response.body":
                buffer += message.get("body", b"")
                while len(buffer) >= 5:
                    flags, length = struct.unpack(">BI", buffer[:5])
                    if len(buffer) < 5 + length:
                        break
                    frame, buffer = buffer[5 : 5 + length], buffer[5 + length :]
                    await self.frames.put((flags, frame))
                if not message.get("more_body", False) and not self.grpc:
                    await self.frames.put(None)
            elif message["type"] == "http.response.trailers":
                self.trailers = dict(message["headers"])
                await self.frames.put(None)

        self._task = asyncio.create_task(app(scope, receive, send))
        return self

    async def __aexit__(self, *exc: object) -> None:
        self._gone.set()
        assert self._task is not None
        try:
            await asyncio.wait_for(asyncio.shield(self._task), 1)
        except TimeoutError:
            # The stream notices a client gone only when it next sends, which
            # with a quiet heartbeat is long after the test: cancel it, as a
            # server shutting down does. connectrpc answers a cancellation by
            # ending the stream with CANCELED, raised out of the app.
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError, ConnectError):
                await self._task

    async def next(self, timeout: float = 5.0) -> WatchClassResponse | None:
        """The next message, or ``None`` when the stream has ended."""
        item = await asyncio.wait_for(self.frames.get(), timeout)
        if item is None:
            return None
        flags, frame = item
        if flags & 0x02:
            # Connect's end-of-stream message: the error, if the stream ended on one.
            self.error = json.loads(frame).get("error")
            return await self.next(timeout)
        if self.grpc:
            return WatchClassResponse.from_binary(frame)
        return WatchClassResponse.from_json(frame)

    async def end(self, within: float = PATIENCE) -> list[WatchClassResponse]:
        """Every message until the stream ends, which it must ``within`` seconds:
        a heartbeat may run before the write a test waits on has landed."""
        messages = []
        async with asyncio.timeout(within):
            while (message := await self.next(within)) is not None:
                messages.append(message)
        return messages

    def ended_with(self) -> tuple[str | None, str | None]:
        """The code and the reason the stream ended with, on either protocol."""
        if self.grpc:
            status = self.trailers.get(b"grpc-status", b"").decode()
            return status, None
        assert self.error is not None, "the stream ended without an error"
        detail = self.error["details"][0]
        return self.error["code"], detail.get("debug", {}).get("reason")


async def test_the_first_message_is_the_class_s_revision_now(
    streaming, v2_tokens, school_class
) -> None:
    async with _Watcher(v2_tokens["unlinked"]) as stream:
        first = await stream.next()
        assert first is not None
        assert first.revision == streaming.revision(school_class.id)
        assert first.revision.startswith(streaming.BOOT)


@pytest.mark.parametrize("shell", ["v1", "v2", "bot"])
async def test_a_change_any_shell_makes_wakes_the_watcher_with_a_new_revision(
    streaming, v2, v2_tokens, school_class, shell
) -> None:
    async with _Watcher(v2_tokens["viewer"]) as stream:
        first = await stream.next()
        if shell == "v1":
            written = await v2.http.put(
                "/api/v1/homework",
                json={"due_date": "2026-09-15", "subject": "Алгебра", "text": "№ 1–5"},
                headers={"Authorization": f"Bearer {v2_tokens['editor']}"},
            )
            assert written.status_code == 200, written.text
        elif shell == "v2":
            homework = Homework(due_date="2026-09-15", subject="Алгебра", text="№ 1–5")
            written = await v2.connect(
                "HomeworkService/CreateHomework",
                CreateHomeworkRequest(homework=homework),
                token=v2_tokens["editor"],
            )
            assert written.status == 200, written.body
        else:
            async with SessionLocal() as update_session:
                klass = await update_session.get(SchoolClass, school_class.id)
                await homework_service.create(
                    update_session, klass, 2002, date(2026, 9, 15), "Алгебра", "№ 1–5"
                )
                await update_session.commit()
        changed = await stream.next(timeout=PATIENCE)
        assert changed is not None and first is not None
        assert changed.revision != first.revision
        assert changed.revision == streaming.revision(school_class.id)
        assert changed.has_field("changed_at")


async def test_a_quiet_class_hears_its_own_revision_again_at_the_heartbeat(
    beating, v2, v2_tokens, school_class
) -> None:
    """A pupil's own task is no change of the class: the next message is the
    heartbeat, saying the same revision."""
    async with _Watcher(v2_tokens["viewer"]) as stream:
        first = await stream.next()
        written = await v2.connect(
            "MeService/CreateTask",
            CreateTaskRequest(task=Task(title="Купить тетрадь")),
            token=v2_tokens["viewer"],
        )
        assert written.status == 200, written.body
        again = await stream.next(timeout=PATIENCE)
        assert again is not None and first is not None
        assert again.revision == first.revision


async def test_a_burst_of_changes_is_one_message_with_the_last_revision(
    streaming, v2_tokens, school_class
) -> None:
    async with _Watcher(v2_tokens["viewer"]) as stream:
        await stream.next()
        streaming.publish([school_class.id])
        streaming.publish([school_class.id])
        latest = streaming.revision(school_class.id)
        woken = await stream.next(timeout=PATIENCE)
        assert woken is not None and woken.revision == latest
        with pytest.raises(TimeoutError):
            await stream.next(timeout=0.5)


async def test_a_device_revoked_while_it_watches_is_refused_at_the_next_heartbeat(
    beating, v2_tokens, session
) -> None:
    """The revocation writes no window table and wakes nobody; the heartbeat's
    gate is what finds it."""
    token = v2_tokens["viewer"]
    async with _Watcher(token) as stream:
        await stream.next()
        await session.execute(
            update(DeviceToken)
            .where(DeviceToken.token_hash == hash_token(token))
            .values(revoked=True)
        )
        await session.commit()
        await stream.end()
        assert stream.ended_with() == ("unauthenticated", "DEVICE_TOKEN_INVALID")


async def test_a_class_deleted_ends_its_streams_at_once(
    streaming, v2, v2_tokens, school_class, monkeypatch
) -> None:
    """The delete is a change of the class: every watcher's gate runs at once
    and finds its device gone with the class, long before any heartbeat."""
    async with _Watcher(v2_tokens["viewer"]) as stream:
        await stream.next()
        deleted = await v2.connect(
            "ClassService/DeleteClass",
            DeleteClassRequest(confirmation=school_class.name),
            token=v2_tokens["owner"],
        )
        assert deleted.status == 200, deleted.body
        assert await stream.next(timeout=PATIENCE) is None
        assert stream.ended_with() == ("unauthenticated", "DEVICE_TOKEN_INVALID")


async def test_a_watcher_holds_no_connection_between_its_messages(
    streaming, v2_tokens, school_class
) -> None:
    """One session held per watcher would hold a pooled connection for as long
    as a phone is in the foreground; the pool is 5 + 10 (decision 13)."""
    async with _Watcher(v2_tokens["viewer"]) as one, _Watcher(v2_tokens["editor"]) as two:
        await one.next()
        await two.next()
        assert engine.pool.checkedout() == 0
        streaming.publish([school_class.id])
        await one.next(timeout=PATIENCE)
        await two.next(timeout=PATIENCE)
        assert engine.pool.checkedout() == 0


async def test_a_watcher_that_goes_away_is_forgotten(beating, v2_tokens, school_class) -> None:
    """With a heartbeat due, the stream learns at its next message that its
    client is gone and closes the handler; a server that cancels the task
    instead reaches the same ``finally``, which forgets the watcher."""
    async with _Watcher(v2_tokens["viewer"]) as stream:
        await stream.next()
        assert beating.watchers(school_class.id) == 1
    assert beating.watchers(school_class.id) == 0


@pytest.mark.parametrize("vercel", ["1", ""], ids=["on-vercel", "streaming-off"])
async def test_where_streaming_is_off_the_gate_runs_and_the_feature_is_refused(
    v2_tokens, served_settings, monkeypatch, vercel, request
) -> None:
    """On Vercel even with the bus attached: no flag can claim a stream there."""
    if vercel:
        request.getfixturevalue("streaming")
    monkeypatch.setattr(served_settings, "vercel", vercel)
    async with _Watcher(None) as anonymous:
        assert await anonymous.next() is None
        assert anonymous.ended_with() == ("unauthenticated", "DEVICE_TOKEN_INVALID")
    async with _Watcher(v2_tokens["viewer"]) as refused:
        assert await refused.next() is None
        assert refused.ended_with() == ("unimplemented", "FEATURE_UNSUPPORTED")


async def test_over_native_grpc_the_revision_comes_in_binary_and_the_end_in_trailers(
    streaming, v2, v2_tokens, school_class
) -> None:
    async with _Watcher(v2_tokens["viewer"], grpc=True) as stream:
        first = await stream.next()
        assert first is not None
        assert first.revision == streaming.revision(school_class.id)
        assert stream.status == 200
        deleted = await v2.connect(
            "ClassService/DeleteClass",
            DeleteClassRequest(confirmation=school_class.name),
            token=v2_tokens["owner"],
        )
        assert deleted.status == 200, deleted.body
        assert await stream.next(timeout=PATIENCE) is None
        assert stream.ended_with() == ("16", None)
