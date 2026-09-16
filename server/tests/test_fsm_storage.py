"""FSM state must survive the process that created it.

These exist because the serverless deployment target hands each Telegram update
to a possibly-fresh instance. Memory storage looks fine in every local test and
then loses half of every multi-step conversation in production.
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta

from aiogram.fsm.state import State, StatesGroup
from aiogram.fsm.storage.base import StorageKey

from app.db import SessionLocal
from app.fsm_storage import DatabaseStorage, FsmRecord


class Sample(StatesGroup):
    first = State()
    second = State()


def key(user: int = 1, chat: int = 1, destiny: str = "default") -> StorageKey:
    return StorageKey(bot_id=42, chat_id=chat, user_id=user, destiny=destiny)


def storage() -> DatabaseStorage:
    return DatabaseStorage(SessionLocal)


async def test_state_round_trips():
    store = storage()
    assert await store.get_state(key()) is None

    await store.set_state(key(), Sample.first)
    assert await store.get_state(key()) == Sample.first.state

    await store.set_state(key(), Sample.second)
    assert await store.get_state(key()) == Sample.second.state


async def test_data_round_trips_including_cyrillic():
    store = storage()
    assert await store.get_data(key()) == {}

    await store.set_data(key(), {"subject": "Алгебра", "index": 2})
    assert await store.get_data(key()) == {"subject": "Алгебра", "index": 2}


async def test_state_and_data_are_independent():
    """A flow sets data first and state second, or the reverse; neither may
    clobber the other."""
    store = storage()
    await store.set_data(key(), {"due": "2026-09-07"})
    await store.set_state(key(), Sample.first)

    assert await store.get_state(key()) == Sample.first.state
    assert await store.get_data(key()) == {"due": "2026-09-07"}


async def test_clearing_the_state_drops_the_data():
    """Otherwise the next flow reads a date the user chose for a different one."""
    store = storage()
    await store.set_state(key(), Sample.first)
    await store.set_data(key(), {"due": "2026-09-07"})

    await store.set_state(key(), None)

    assert await store.get_state(key()) is None
    assert await store.get_data(key()) == {}


async def test_users_chats_and_destinies_do_not_share_state():
    store = storage()
    await store.set_data(key(user=1), {"who": "first"})
    await store.set_data(key(user=2), {"who": "second"})
    await store.set_data(key(user=1, chat=99), {"who": "other chat"})
    await store.set_data(key(user=1, destiny="parallel"), {"who": "other flow"})

    assert (await store.get_data(key(user=1)))["who"] == "first"
    assert (await store.get_data(key(user=2)))["who"] == "second"
    assert (await store.get_data(key(user=1, chat=99)))["who"] == "other chat"
    assert (await store.get_data(key(user=1, destiny="parallel")))["who"] == "other flow"


async def test_a_second_storage_instance_sees_the_first_one_s_state():
    """The actual point: a new process must resume the conversation."""
    await storage().set_state(key(), Sample.first)
    await storage().set_data(key(), {"subject": "Физика"})

    resumed = storage()
    assert await resumed.get_state(key()) == Sample.first.state
    assert await resumed.get_data(key()) == {"subject": "Физика"}


async def test_update_data_merges():
    store = storage()
    await store.set_data(key(), {"a": 1})
    merged = await store.update_data(key(), {"b": 2})

    assert merged == {"a": 1, "b": 2}
    assert await store.get_data(key()) == {"a": 1, "b": 2}


async def test_setting_no_state_on_an_unknown_key_stores_nothing():
    store = storage()
    await store.set_state(key(user=777), None)

    async with SessionLocal() as session:
        assert await session.get(FsmRecord, "42:1:777:0::default") is None


async def test_clearing_a_conversation_nobody_started_writes_nothing():
    """``state.clear()`` is ``set_state(None)`` and then ``set_data({})``.

    The first half already refused to store "no state". The second stored an
    empty dict, which says the same nothing - and the bot clears state in over
    a hundred places, most of them a menu button resetting a flow the person
    was never in. Each one was an INSERT and a commit against a database in
    another region, for a row whose whole content is that there is nothing.
    """
    store = storage()
    await store.set_state(key(user=778), None)
    await store.set_data(key(user=778), {})

    async with SessionLocal() as session:
        assert await session.get(FsmRecord, "42:1:778:0::default") is None

    # A clear of something real still clears it.
    await store.set_data(key(user=778), {"subject": "Алгебра"})
    await store.set_data(key(user=778), {})
    assert await store.get_data(key(user=778)) == {}


async def test_corrupt_data_restarts_the_flow_instead_of_wedging_it():
    store = storage()
    await store.set_data(key(), {"ok": True})

    async with SessionLocal() as session:
        record = await session.get(FsmRecord, "42:1:1:0::default")
        record.data = "{not json"
        await session.commit()

    assert await store.get_data(key()) == {}


async def test_non_object_json_is_treated_as_empty():
    store = storage()
    await store.set_data(key(), {"ok": True})

    async with SessionLocal() as session:
        record = await session.get(FsmRecord, "42:1:1:0::default")
        record.data = json.dumps([1, 2, 3])
        await session.commit()

    assert await store.get_data(key()) == {}


async def test_purge_removes_only_abandoned_conversations():
    store = storage()
    await store.set_data(key(user=1), {"fresh": True})
    await store.set_data(key(user=2), {"stale": True})

    async with SessionLocal() as session:
        old = await session.get(FsmRecord, "42:1:2:0::default")
        old.updated_at = datetime.utcnow() - timedelta(days=30)
        await session.commit()

    removed = await store.purge_stale()

    assert removed == 1
    assert await store.get_data(key(user=1)) == {"fresh": True}
    assert await store.get_data(key(user=2)) == {}


async def test_losing_the_insert_race_applies_the_change_instead_of_raising():
    """Two instances see no row, both insert; the loser must land its update
    on the winner's row rather than surface IntegrityError to the handler."""
    from unittest.mock import patch

    storage = DatabaseStorage(SessionLocal)
    key = StorageKey(bot_id=1, chat_id=99, user_id=99)

    original_get = SessionLocal.class_.get
    calls = {"n": 0}

    async def racing_get(self, entity, ident, *args, **kwargs):
        calls["n"] += 1
        if calls["n"] == 1:
            # The first read sees nothing - and between it and our insert
            # somebody else inserts the same key.
            async with SessionLocal() as other:
                other.add(
                    FsmRecord(
                        key=ident, state="Other:step", data='{"x": 1}',
                        updated_at=datetime.utcnow(),
                    )
                )
                await other.commit()
            return None
        return await original_get(self, entity, ident, *args, **kwargs)

    with patch.object(SessionLocal.class_, "get", racing_get):
        await storage.set_data(key, {"y": 2})

    assert await storage.get_data(key) == {"y": 2}
    assert await storage.get_state(key) == "Other:step"


def test_bootstrap_creates_the_fsm_table_in_a_fresh_interpreter(tmp_path):
    """``scripts.init_db`` runs in a process that has never imported the tests
    or the bot; create_all there has to know about fsm_states by itself."""
    import os
    import subprocess
    import sys

    script = (
        "import asyncio, sqlite3\n"
        "from app.db import init_db\n"
        "asyncio.run(init_db())\n"
        f"names = [r[0] for r in sqlite3.connect({str(tmp_path / 'boot.db')!r})"
        ".execute(\"select name from sqlite_master where type='table'\")]\n"
        "print('fsm_states' in names)\n"
    )
    env = dict(os.environ, DATABASE_URL=f"sqlite+aiosqlite:///{tmp_path / 'boot.db'}")
    result = subprocess.run(
        [sys.executable, "-c", script], env=env, capture_output=True, text=True, check=True
    )
    assert result.stdout.strip() == "True", result.stderr
