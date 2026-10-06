"""The tick runs the self-check after the digests and the sweeps, before the
diary keep-alive, and the self-check never fails the tick.

Driven through the real endpoint, as ``test_api_extended.py``'s tick tests
are, with the same two seams: the settings the tick reads and the bot it
builds for the digests. The self-check's own sender is ``telegram_send.send``,
replaced here by a recorder, so nothing reaches Telegram.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import select

from app import (
    fsm_storage,  # noqa: F401 - registers fsm_states, which the tick sweeps
    telegram_send,
)
from app.api import cron
from app.config import Settings, get_settings
from app.main import app
from app.models import HealthCheck
from app.services import diary_keepalive, health

CRON_SECRET = "tick-secret"
HEADERS = {"X-Cron-Secret": CRON_SECRET}

#: What a tick with nothing due answers, as it did before the self-check.
QUIET_TICK = {
    "morning": 0,
    "evening": 0,
    "tasks": 0,
    "failed": 0,
    "fsm_purged": 0,
    "join_attempts_purged": 0,
    "diary_sessions_purged": 0,
    "device_tokens_purged": 0,
    "diary_links_purged": 0,
    "device_invites_purged": 0,
    "diary_sessions_kept_alive": 0,
    "diary_sessions_lost": 0,
    "diary_keepalive_failed": False,
}


class _Bot:
    """The digests' bot; nothing is due in these ticks, so it only closes."""

    async def send_message(self, chat_id: int, text: str, **_: Any) -> None:
        raise AssertionError("no digest is due")

    @property
    def session(self) -> _Bot:
        return self

    async def close(self) -> None:
        return None


@pytest.fixture
def ticking(monkeypatch) -> list[tuple[int, str]]:
    """A configured tick, and what the self-check sent through ``telegram_send``."""
    settings = Settings(
        bot_token="123456:TEST",
        cron_secret=CRON_SECRET,
        database_url=get_settings().database_url,
        run_bot=False,
    )
    monkeypatch.setattr(cron, "get_settings", lambda: settings)
    monkeypatch.setattr(cron, "_build_bot", _Bot)
    sent: list[tuple[int, str]] = []

    async def record(messages):
        sent.extend(messages)
        return [True] * len(messages)

    monkeypatch.setattr(telegram_send, "send", record)
    return sent


async def _tick() -> httpx.Response:
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.get("/api/v1/cron/tick", headers=HEADERS)


async def test_the_tick_runs_the_four_checks(session, ticking):
    response = await _tick()

    assert response.status_code == 200, response.text
    assert response.json() == QUIET_TICK
    rows = {row.name: row.status for row in await session.scalars(select(HealthCheck))}
    # The suite's database has no alembic_version, v2 loaded, no proxy is
    # configured, and this is not Vercel.
    assert rows == {"schema": "unknown", "v2": "ok", "diary_proxy": "unknown", "deploy": "unknown"}
    assert ticking == []


async def test_the_self_check_runs_before_the_keep_alive(session, ticking, monkeypatch):
    """The self-check's rows already exist when the keep-alive is asked to run."""
    calls: list[int] = []

    async def fake_keep_alive(session, *, started=None):
        rows = list(await session.scalars(select(HealthCheck)))
        calls.append(len(rows))
        return 0, 0

    monkeypatch.setattr(diary_keepalive, "keep_alive", fake_keep_alive)

    assert (await _tick()).status_code == 200

    assert calls == [4]


async def test_the_tick_tells_the_self_check_whether_v2_loaded(session, ticking, monkeypatch):
    monkeypatch.setattr(app.state, "v2_mounted", False)

    assert (await _tick()).status_code == 200
    assert (await _tick()).status_code == 200

    status = await session.scalar(select(HealthCheck.status).where(HealthCheck.name == "v2"))
    assert status == "failing"
    assert [recipient for recipient, _ in ticking] == [1000]
    assert ticking[0][1].startswith("🔴 v2 не загрузился")


async def test_the_tick_answers_as_before_when_every_check_raises(session, ticking, monkeypatch):
    async def broken(*args, **kwargs):
        raise RuntimeError("a bug")

    def broken_now(*args, **kwargs):
        raise RuntimeError("a bug")

    for name in ("check_schema", "check_diary_proxy", "check_deploy"):
        monkeypatch.setattr(health, name, broken)
    monkeypatch.setattr(health, "check_v2", broken_now)

    response = await _tick()

    assert response.status_code == 200, response.text
    assert response.json() == QUIET_TICK
    reasons = {row.name: row.reason for row in await session.scalars(select(HealthCheck))}
    assert set(reasons.values()) == {"the check raised RuntimeError"}


async def test_a_self_check_that_raises_whole_does_not_fail_the_tick(ticking, monkeypatch):
    async def broken(*args, **kwargs):
        raise RuntimeError("the self-check fell over")

    monkeypatch.setattr(health, "run", broken)

    response = await _tick()

    assert response.status_code == 200, response.text
    assert response.json() == QUIET_TICK


async def test_a_self_check_whose_commit_fails_still_lets_the_keep_alive_run(ticking, monkeypatch):
    """A commit that fails inside ``health.run`` (the first-run insert race, Ruling 5) leaves
    the tick's session needing a rollback; the keep-alive runs next on that same session and
    must not inherit it."""

    async def broken(session, settings, *, v2_mounted, started=None):
        now = datetime.now(UTC).replace(tzinfo=None)
        # Two rows with the same primary key in one flush: an IntegrityError on commit,
        # exactly what two overlapping ticks' first-run inserts could race into.
        session.add(HealthCheck(name="schema", status="ok", reason="", since=now, checked_at=now))
        session.add(HealthCheck(name="schema", status="ok", reason="", since=now, checked_at=now))
        await session.commit()

    monkeypatch.setattr(health, "run", broken)

    ran: list[bool] = []

    async def fake_keep_alive(session, *, started=None):
        await session.execute(select(HealthCheck))  # fails if the session was never rolled back
        ran.append(True)
        return 0, 0

    monkeypatch.setattr(diary_keepalive, "keep_alive", fake_keep_alive)

    response = await _tick()

    assert response.status_code == 200, response.text
    assert response.json() == QUIET_TICK
    assert ran == [True]
