"""The cron keep-alive that holds «Сетевой город» sessions open.

It pings live netschool rows, expires the ones the upstream has dropped, leaves
the ones behind a down or blocking origin, and never touches ``last_used_at``
(so the 30-day purge of an unused session still fires). A ping runs with no
database session held open; the outcome is written back with a guarded UPDATE,
so a row signed out between the claim and the write is skipped, not a crash.
"""

from __future__ import annotations

import json

from app.crypto import seal
from app.models import DiarySession
from app.providers.diary.errors import SessionExpired, UpstreamUnavailable
from app.security import hash_token, new_token
from app.services import diary_keepalive


def _netschool_row(**over) -> DiarySession:
    cred = json.dumps({"v": 1, "region": "zabaikalsky", "school_id": 1, "at": "at",
                       "cookies": {}, "year_id": 2026})
    fields = dict(
        token_hash=hash_token(new_token()),
        upstream_token=seal(cred),
        login="parent@example.com",
        provider="netschool",
        region="zabaikalsky",
    )
    fields.update(over)
    return DiarySession(**fields)


async def test_a_live_session_is_pinged_and_not_touched_as_used(session, monkeypatch):
    row = _netschool_row()
    session.add(row)
    await session.commit()

    pinged: list[int] = []

    async def fake_keep_alive(self):
        pinged.append(1)

    monkeypatch.setattr(
        "app.providers.netschool.provider.NetSchoolConnection.keep_alive", fake_keep_alive
    )
    kept, lost = await diary_keepalive.keep_alive(session)
    await session.refresh(row)

    assert (kept, lost) == (1, 0)
    assert pinged == [1]
    assert row.kept_alive_at is not None
    assert row.upstream_ok_at is not None
    assert row.keepalive_attempted_at is not None
    # The family did not use it; the purge clock must not have moved.
    assert row.last_used_at is None
    assert row.expired_at is None


async def test_a_dropped_session_is_expired(session, monkeypatch):
    row = _netschool_row()
    session.add(row)
    await session.commit()

    async def dead(self):
        raise SessionExpired

    monkeypatch.setattr(
        "app.providers.netschool.provider.NetSchoolConnection.keep_alive", dead
    )
    kept, lost = await diary_keepalive.keep_alive(session)
    await session.refresh(row)

    assert (kept, lost) == (0, 1)
    assert row.expired_at is not None


async def test_a_down_origin_leaves_the_session_alone(session, monkeypatch):
    row = _netschool_row()
    session.add(row)
    await session.commit()

    async def down(self):
        raise UpstreamUnavailable

    monkeypatch.setattr(
        "app.providers.netschool.provider.NetSchoolConnection.keep_alive", down
    )
    kept, lost = await diary_keepalive.keep_alive(session)
    await session.refresh(row)

    assert (kept, lost) == (0, 0)
    assert row.expired_at is None
    # It was attempted, so it goes to the back of the queue rather than the front.
    assert row.keepalive_attempted_at is not None


async def test_petersburg_and_expired_rows_are_left_out(session, monkeypatch):
    petersburg = DiarySession(
        token_hash=hash_token(new_token()), upstream_token=seal("tok"),
        login="p@example.com", provider="petersburg",
    )
    already_dead = _netschool_row(expired_at=__import__("datetime").datetime(2020, 1, 1))
    session.add_all([petersburg, already_dead])
    await session.commit()

    called: list[int] = []

    async def fake(self):
        called.append(1)

    monkeypatch.setattr(
        "app.providers.netschool.provider.NetSchoolConnection.keep_alive", fake
    )
    kept, lost = await diary_keepalive.keep_alive(session)
    assert (kept, lost) == (0, 0)
    assert called == []  # neither a Petersburg row nor an already-expired one is pinged


async def test_the_keep_alive_does_nothing_without_a_diary_secret(session, monkeypatch):
    row = _netschool_row()
    session.add(row)
    await session.commit()
    monkeypatch.setattr(diary_keepalive, "diary_enabled", lambda: False)
    assert await diary_keepalive.keep_alive(session) == (0, 0)
