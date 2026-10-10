"""The host target, asked over the network: what only a running HTTP/2 server can answer.

Run by CI's «Host» job against ``python -m app.host``, under pyvoy in one job
and hypercorn in the other, each started on a SQLite file of its own and with
streaming on (``docs/specs/2026-10-05-server-v2-design.md``, decisions 13 and 14).
Everywhere else every test here is skipped: they are marked ``host``, and
``conftest.py`` skips the mark unless ``LESSONS_HOST_URL`` names a running
host. The class, its members and their phones are written into the host's
database directly (``LESSONS_HOST_DATABASE_URL``), because only the bot can
link a phone; every change after that goes through the host, because a stream
is woken only by what the host itself writes.

The client is grpcio, Google's own implementation, so what is proved is that
a gRPC client that is not this server's library reads what it answers. The
forged ``X-Forwarded-For`` test spends the join budget of the job's address
for a quarter of an hour, so it is the last in the file.
"""

from __future__ import annotations

import asyncio
import os
import queue
import secrets
import threading
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from typing import Any
from urllib.parse import urlsplit

import httpx
import pytest
from protobuf.wkt import FieldMask
from sqlalchemy import delete
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.contract.lessons.v2.bell_pb import BellPeriod as BellRow
from app.contract.lessons.v2.bell_pb import BellSchedule as BellMessage
from app.contract.lessons.v2.bell_pb import UpdateBellScheduleRequest, UpdateBellScheduleResponse
from app.contract.lessons.v2.class_device_pb import (
    RevokeClassDeviceRequest,
    RevokeClassDeviceResponse,
)
from app.contract.lessons.v2.diary_pb import (
    GetDiaryCapabilitiesRequest,
    GetDiaryCapabilitiesResponse,
)
from app.contract.lessons.v2.homework_pb import (
    CreateHomeworkRequest,
    CreateHomeworkResponse,
    Homework,
)
from app.contract.lessons.v2.me_pb import GetMeRequest, GetMeResponse
from app.contract.lessons.v2.timetable_pb import ImportTimetableRequest, ImportTimetableResponse
from app.contract.lessons.v2.watch_pb import WatchClassRequest, WatchClassResponse
from app.models import (
    DEFAULT_BELLS,
    BellPeriod,
    BellSchedule,
    BotUser,
    DeviceToken,
    JoinAttempt,
    Role,
    SchoolClass,
    TimetableEntry,
    WeekParity,
)
from app.security import hash_token

pytestmark = pytest.mark.host

#: How long a test waits for a message the host owes it. Generous, because a
#: CI runner is slower than anything here should be; a stream that works
#: answers in milliseconds.
PATIENCE = 15.0


@dataclass
class Seeded:
    """A class written into the host's database, and the bearers of its phones."""

    class_id: int
    schedule_id: int
    tokens: dict[str, str] = field(default_factory=dict)
    devices: dict[str, int] = field(default_factory=dict)


async def _seed() -> Seeded:
    engine = create_async_engine(os.environ["LESSONS_HOST_DATABASE_URL"])
    try:
        sessions = async_sessionmaker(engine, expire_on_commit=False)
        async with sessions() as session:
            school_class = SchoolClass(
                name="9А", school="Школа № 1", join_code=f"H{secrets.token_hex(4).upper()}"
            )
            session.add(school_class)
            await session.flush()
            bells = BellSchedule(class_id=school_class.id, name="Обычное")
            session.add(bells)
            await session.flush()
            for index, starts_at, ends_at in DEFAULT_BELLS:
                session.add(
                    BellPeriod(
                        schedule_id=bells.id, index=index, starts_at=starts_at, ends_at=ends_at
                    )
                )
            school_class.bell_schedule_id = bells.id
            for weekday in (1, 2):
                for index, subject in ((1, "Алгебра"), (2, "Физика")):
                    session.add(
                        TimetableEntry(
                            class_id=school_class.id,
                            weekday=weekday,
                            index=index,
                            subject_name=subject,
                            parity=WeekParity.ANY,
                        )
                    )
            seeded = Seeded(class_id=school_class.id, schedule_id=bells.id)
            first_id = 8_000_000 + secrets.randbelow(1_000_000)
            phones = {}
            for offset, (name, role) in enumerate(
                (("viewer", Role.VIEWER), ("editor", Role.EDITOR), ("admin", Role.ADMIN))
            ):
                telegram_id = first_id + offset
                session.add(BotUser(telegram_id=telegram_id, class_id=school_class.id, role=role))
                token = f"host-{name}-{secrets.token_hex(12)}"
                phones[name] = DeviceToken(
                    token_hash=hash_token(token),
                    class_id=school_class.id,
                    device_name=f"{name} phone",
                    telegram_id=telegram_id,
                    linked_at=datetime(2026, 9, 1),
                )
                session.add(phones[name])
                seeded.tokens[name] = token
            await session.commit()
            seeded.devices = {name: phone.id for name, phone in phones.items()}
            return seeded
    finally:
        await engine.dispose()


@pytest.fixture
def seeded() -> Seeded:
    return asyncio.run(_seed())


@pytest.fixture
def grpc() -> Any:
    """grpcio, imported here: only the «Host» job installs it (``.[host-check]``)."""
    import grpc as grpcio

    return grpcio


@pytest.fixture
def channel(grpc) -> Any:
    target = urlsplit(os.environ["LESSONS_HOST_URL"]).netloc
    with grpc.insecure_channel(target) as opened:
        yield opened


@pytest.fixture
def http() -> Any:
    with httpx.Client(base_url=os.environ["LESSONS_HOST_URL"], timeout=PATIENCE) as client:
        yield client


def _unary(channel: Any, path: str, response: Any) -> Any:
    return channel.unary_unary(
        path,
        request_serializer=lambda message: message.to_binary(),
        response_deserializer=response.from_binary,
    )


def _bearer(token: str) -> list[tuple[str, str]]:
    return [("authorization", f"Bearer {token}")]


class _Watch:
    """A ``WatchClass`` stream, read on a thread of its own into a queue."""

    def __init__(self, channel: Any, grpc: Any, token: str) -> None:
        self._grpc = grpc
        method = channel.unary_stream(
            "/lessons.v2.WatchService/WatchClass",
            request_serializer=lambda message: message.to_binary(),
            response_deserializer=WatchClassResponse.from_binary,
        )
        self.call = method(WatchClassRequest(), metadata=_bearer(token))
        self.messages: queue.Queue[Any] = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        try:
            for message in self.call:
                self.messages.put(message)
        except self._grpc.RpcError as error:
            self.messages.put(error)
        else:
            self.messages.put(None)

    def next(self) -> Any:
        """The next message, or the error the stream ended with."""
        return self.messages.get(timeout=PATIENCE)

    def next_revision_after(self, revision: str) -> str:
        """The first revision other than ``revision``: heartbeats repeat it."""
        while True:
            message = self.next()
            assert isinstance(message, WatchClassResponse), message
            if message.revision != revision:
                return message.revision

    def close(self) -> None:
        self.call.cancel()


def test_a_unary_call_is_answered_over_native_grpc(channel, seeded) -> None:
    """At the root, where a gRPC client calls, with a credential and without."""
    capabilities = _unary(
        channel, "/lessons.v2.DiaryService/GetDiaryCapabilities", GetDiaryCapabilitiesResponse
    )(GetDiaryCapabilitiesRequest(), timeout=PATIENCE)
    assert capabilities.capabilities.providers
    me = _unary(channel, "/lessons.v2.MeService/GetMe", GetMeResponse)(
        GetMeRequest(), metadata=_bearer(seeded.tokens["editor"]), timeout=PATIENCE
    )
    # The seeded phone, read back from the host's own database.
    assert (me.me.device_name, me.me.linked, me.me.can_edit) == ("editor phone", True, True)


def test_a_method_the_contract_does_not_have_is_unimplemented(channel, grpc) -> None:
    with pytest.raises(grpc.RpcError) as refused:
        _unary(channel, "/lessons.v2.MeService/NoSuchMethod", GetMeResponse)(
            GetMeRequest(), timeout=PATIENCE
        )
    assert refused.value.code() == grpc.StatusCode.UNIMPLEMENTED


def test_watch_class_hears_an_orm_write_a_bulk_write_and_a_bell_change(
    channel, grpc, seeded
) -> None:
    """A row written through the unit of work, a bulk statement that only
    touches the bus, and a bell change (``app/watch.py``). The bell change
    writes its periods after a bulk delete that touches the bus too, so it
    wakes the class whichever way the bus finds it; that a bell period is found
    through its schedule is ``test_watch.py``'s to show."""
    watch = _Watch(channel, grpc, seeded.tokens["viewer"])
    try:
        first = watch.next()
        assert isinstance(first, WatchClassResponse), first
        seen = [first.revision]

        homework = Homework(due_date="2026-09-15", subject="Алгебра", text="№ 1–5")
        _unary(channel, "/lessons.v2.HomeworkService/CreateHomework", CreateHomeworkResponse)(
            CreateHomeworkRequest(homework=homework),
            metadata=_bearer(seeded.tokens["editor"]),
            timeout=PATIENCE,
        )
        seen.append(watch.next_revision_after(seen[-1]))

        # A weekday named with nothing under it is emptied: one bulk delete,
        # nothing through the unit of work.
        imported = _unary(
            channel, "/lessons.v2.TimetableService/ImportTimetable", ImportTimetableResponse
        )(
            ImportTimetableRequest(text="== Вторник ==\n", replace=True),
            metadata=_bearer(seeded.tokens["admin"]),
            timeout=PATIENCE,
        )
        assert imported.applied
        seen.append(watch.next_revision_after(seen[-1]))

        periods = [
            BellRow(
                index=index,
                starts_at=starts_at.strftime("%H:%M"),
                ends_at=(datetime.combine(date(2026, 9, 1), ends_at) - timedelta(minutes=5))
                .time()
                .strftime("%H:%M"),
            )
            for index, starts_at, ends_at in DEFAULT_BELLS
        ]
        _unary(channel, "/lessons.v2.BellService/UpdateBellSchedule", UpdateBellScheduleResponse)(
            UpdateBellScheduleRequest(
                schedule=BellMessage(id=seeded.schedule_id, periods=periods),
                update_mask=FieldMask(paths=["periods"]),
            ),
            metadata=_bearer(seeded.tokens["admin"]),
            timeout=PATIENCE,
        )
        seen.append(watch.next_revision_after(seen[-1]))
        assert len(set(seen)) == 4
    finally:
        watch.close()


def test_a_phone_revoked_while_it_watches_is_cut_off(channel, grpc, seeded) -> None:
    """Revoked from another phone, then woken by the next change of its
    class: its gate runs again and refuses it."""
    watch = _Watch(channel, grpc, seeded.tokens["viewer"])
    try:
        assert isinstance(watch.next(), WatchClassResponse)
        _unary(
            channel, "/lessons.v2.ClassDeviceService/RevokeClassDevice", RevokeClassDeviceResponse
        )(
            RevokeClassDeviceRequest(device_id=seeded.devices["viewer"]),
            metadata=_bearer(seeded.tokens["admin"]),
            timeout=PATIENCE,
        )
        homework = Homework(due_date="2026-09-16", subject="Физика", text="§ 3")
        _unary(channel, "/lessons.v2.HomeworkService/CreateHomework", CreateHomeworkResponse)(
            CreateHomeworkRequest(homework=homework),
            metadata=_bearer(seeded.tokens["editor"]),
            timeout=PATIENCE,
        )
        while isinstance(message := watch.next(), WatchClassResponse):
            pass
        assert isinstance(message, grpc.RpcError), message
        assert message.code() == grpc.StatusCode.UNAUTHENTICATED
    finally:
        watch.close()


def test_the_rest_of_the_app_answers_over_http_1_1(http, seeded) -> None:
    """v1, REST and Connect beside native gRPC on one port, and the webhook and
    the tick mounted — refusing a caller without their secret, not missing."""
    bearer = {"Authorization": f"Bearer {seeded.tokens['viewer']}"}
    assert http.get("/api/v1/health").status_code == 200
    assert http.get("/api/v1/me", headers=bearer).status_code == 200
    assert http.get("/api/v2/me", headers=bearer).status_code == 200
    connect = http.post(
        "/api/rpc/lessons.v2.MeService/GetMe",
        content=b"{}",
        headers={**bearer, "Content-Type": "application/json"},
    )
    assert connect.status_code == 200
    assert http.post("/api/v1/telegram/webhook", json={}).status_code == 403
    assert http.get("/api/v1/cron/tick").status_code == 403


async def _forget_join_attempts() -> None:
    engine = create_async_engine(os.environ["LESSONS_HOST_DATABASE_URL"])
    try:
        async with engine.begin() as connection:
            await connection.execute(delete(JoinAttempt))
    finally:
        await engine.dispose()


def test_a_forged_forwarded_for_buys_no_fresh_join_budget(http) -> None:
    """pyvoy's Envoy took the client from the last ``X-Forwarded-For`` entry
    until ``app.host.envoy_config`` turned that off: a caller writing a new
    one each time would never have met the join limiter. Thirty wrong codes
    from thirty forged addresses, and the next is refused, as from one.

    The budget has to be fresh, and a second run against the same database
    within a quarter of an hour — under the other server, say — would find
    it spent. The attempts are cleared first, all of them: their key is a hash
    of the address the host sees this side as, loopback in CI and a gateway
    in front of a container, which this side cannot name; and the database
    is the run's own."""
    asyncio.run(_forget_join_attempts())
    answers = [
        http.post(
            "/api/v1/join",
            json={"code": f"NOSUCH{attempt:02d}"},
            headers={"X-Forwarded-For": f"198.51.100.{attempt + 1}"},
        ).status_code
        for attempt in range(30)
    ]
    assert set(answers) == {404}
    refused = http.post(
        "/api/v1/join",
        json={"code": "NOSUCH99"},
        headers={"X-Forwarded-For": "203.0.113.200"},
    )
    assert refused.status_code == 429
