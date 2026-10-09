"""What a transaction on the SQLite engine has to do because Postgres does it.

The suite and a local development server run on SQLite; production runs on
Postgres, and ``app/db.py`` is where the two are kept agreeing on whatever the
code depends on. These tests hold the two places where the driver's own
transaction handling did not.

pysqlite, and aiosqlite over it, begins a transaction before an INSERT, an
UPDATE or a DELETE and before nothing else. A ``SAVEPOINT`` sent while no
transaction is open therefore opens one of its own, and its ``RELEASE``
commits it: a write made inside ``session.begin_nested()`` as the session's
first write outlived the ``rollback()`` after it, and a caller that forgot to
commit could not be caught by any test (#373). ``homework.upsert``,
``subjects._adopt``, ``terms.ensure``, ``reminders``,
``linking.issue_link_code`` and ``tasks.toggle_homework_done`` all write that
way.

And a read holds no lock once it has been answered, which is why the fix
begins the transaction at that savepoint rather than at the session's first
statement, as SQLAlchemy's recipe for the driver does. Under that recipe
every read keeps its lock until the transaction ends. The bot's middleware
reads on every update, and the FSM storage then commits the conversation on
a connection of its own while the handler waits for it: with SQLite's
rollback journal that commit waits for the middleware's read lock until the
thirty-second busy timeout says «database is locked»; with the WAL journal it
goes through, and the handler's own write after it fails at once, its
snapshot older than the conversation. Postgres, at READ COMMITTED, blocks
neither.

What the fix does keep is the lock of a transaction it begins: once a
savepoint has begun one, what is read in it keeps SQLite's read lock until the
commit or the rollback, as a write's lock always has. The reminders tick
builds its digests inside a savepoint that writes nothing and then sweeps the
conversations on a connection of its own, so the tick ends its transaction
before the sweep.
"""

from __future__ import annotations

from datetime import UTC, datetime, time, timedelta
from types import SimpleNamespace
from typing import Any

import httpx
from aiogram.fsm.storage.base import StorageKey
from sqlalchemy import func, select

from app.api import cron
from app.bot.middlewares import ContextMiddleware
from app.config import Settings, get_settings
from app.db import SessionLocal
from app.fsm_storage import DatabaseStorage, FsmRecord
from app.main import app
from app.models import AuditEntry, BotUser, DeviceToken, ReminderSettings, Role
from app.security import hash_token
from app.services import audit


async def _committed(statement):
    """What a session of its own reads: only what is committed."""
    async with SessionLocal() as fresh:
        return await fresh.scalar(statement)


async def test_a_rollback_takes_back_a_savepoint_that_opened_the_transaction(
    session, school_class
) -> None:
    """The probe #373 was found with, statement for statement: a read, then a
    write inside a savepoint, then a rollback. On the driver's own handling
    the statements were ``SELECT``, ``SAVEPOINT``, ``UPDATE``, ``RELEASE`` and
    no ``BEGIN``, and the release was the commit, so another session read the
    code before anybody committed it and still read it after the rollback."""
    device = DeviceToken(token_hash=hash_token("savepoint-probe"), class_id=school_class.id)
    session.add(device)
    await session.commit()
    device_id = device.id
    stored = select(DeviceToken.link_code).where(DeviceToken.id == device_id)

    # A statement rather than `session.get`, which would answer from the
    # identity map and read nothing. The read leaves no transaction open on
    # SQLite: it is the savepoint that has to begin one.
    device = await session.scalar(select(DeviceToken).where(DeviceToken.id == device_id))
    assert device is not None
    async with session.begin_nested():
        device.link_code = "AAAAAA"
        await session.flush()
    assert await _committed(stored) is None, "a released savepoint committed its write"

    await session.rollback()
    assert await _committed(stored) is None, "a rollback kept a savepoint's write"


async def test_an_update_s_conversation_is_written_after_the_middleware_has_read(
    session, school_class
) -> None:
    """The bot's busiest path, end to end on the real pieces: the middleware
    reads the member's class and role, the handler moves the conversation on
    through the FSM storage, which commits on a connection of its own, and
    then writes through the update's session, which the middleware commits.

    Holds the half of the fix that is a decision: a read takes no lock that
    outlives it. Either form of the recipe the fix did not use fails here, as
    the module's docstring says — the first after thirty seconds."""
    session.add(BotUser(telegram_id=42, class_id=school_class.id, role=Role.EDITOR))
    await session.commit()
    storage = DatabaseStorage(SessionLocal)
    key = StorageKey(bot_id=1, chat_id=42, user_id=42)

    async def handler(event, data) -> str:
        assert data["role"] is Role.EDITOR  # the middleware has read
        await storage.set_state(key, "AddHomework:text")
        await audit.record(data["session"], school_class.id, 42, "test.after", "после формы")
        await data["session"].flush()
        return "handled"

    user = SimpleNamespace(id=42)
    assert await ContextMiddleware()(handler, object(), {"event_from_user": user}) == "handled"

    assert await _committed(select(FsmRecord.state)) == "AddHomework:text"
    assert await _committed(select(func.count()).select_from(AuditEntry)) == 1


class _Bot:
    """Takes the digest and keeps it."""

    def __init__(self) -> None:
        self.sent: list[tuple[int, str]] = []

    async def send_message(self, chat_id: int, text: str, **_: Any) -> None:
        self.sent.append((chat_id, text))

    @property
    def session(self) -> _Bot:
        return self

    async def close(self) -> None:
        pass


async def test_a_tick_that_sent_a_digest_still_sweeps_the_conversations(
    session, school_class, monkeypatch
) -> None:
    """A digest is built inside a savepoint that reads and writes nothing,
    and since #373 was fixed that savepoint begins a transaction which then
    holds SQLite's read lock. The FSM sweep after it commits on a connection
    of its own, and waited on that lock for the thirty seconds of the busy
    timeout before failing the tick «database is locked» — until the tick
    learned to end its transaction first."""
    settings = Settings(
        bot_token="123456:TEST",
        cron_secret="tick-secret",
        database_url=get_settings().database_url,
        run_bot=False,
    )
    monkeypatch.setattr(cron, "get_settings", lambda: settings)
    bot = _Bot()
    monkeypatch.setattr(cron, "_build_bot", lambda: bot)

    class _Monday(datetime):
        @classmethod
        def now(cls, tz=None):
            # 07:45 on a Monday in Moscow, as the tick reads it in UTC.
            return datetime(2026, 9, 7, 4, 45, tzinfo=tz)

    monkeypatch.setattr(cron, "datetime", _Monday)
    session.add(ReminderSettings(class_id=school_class.id, telegram_id=42, morning_at=time(7, 30)))
    abandoned = datetime.now(UTC).replace(tzinfo=None) - timedelta(days=3)
    session.add(FsmRecord(key="1:42:42:0::default", state="x", data="{}", updated_at=abandoned))
    await session.commit()

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/api/v1/cron/tick", headers={"X-Cron-Secret": "tick-secret"})
    assert response.status_code == 200, response.text
    assert response.json()["morning"] == 1
    assert response.json()["fsm_purged"] == 1
    assert [recipient for recipient, _text in bot.sent] == [42]
