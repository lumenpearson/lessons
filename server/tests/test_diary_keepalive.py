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
from app.db import SessionLocal
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


async def test_an_origin_that_refuses_us_is_not_pinged_again_in_the_same_tick(
    session, monkeypatch
):
    """A regional server that drops this address answers every session on it
    the same way. After the first refusal the rest of that origin's claims are
    skipped for the tick — at most the pings already in flight are wasted —
    while another origin's sessions are pinged as usual."""
    from app.providers.diary.errors import AddressRefused

    amur = seal(json.dumps({"v": 1, "region": "amur", "school_id": 1, "at": "at", "cookies": {}}))
    refusing = [_netschool_row(region="zabaikalsky") for _ in range(20)]
    elsewhere = [_netschool_row(region="amur", upstream_token=amur) for _ in range(3)]
    session.add_all(refusing + elsewhere)
    await session.commit()

    pinged: list[str] = []

    async def ping(self):
        region = self._region.key
        pinged.append(region)
        if region == "zabaikalsky":
            raise AddressRefused

    monkeypatch.setattr(
        "app.providers.netschool.provider.NetSchoolConnection.keep_alive", ping
    )
    kept, lost = await diary_keepalive.keep_alive(session)

    assert (kept, lost) == (3, 0)
    assert pinged.count("amur") == 3
    assert 1 <= pinged.count("zabaikalsky") <= diary_keepalive.KEEPALIVE_CONCURRENCY
    for row in refusing:
        await session.refresh(row)
        # Left alone, not expired: the address is refused, not the session.
        assert row.expired_at is None
        assert row.keepalive_attempted_at is not None


async def test_a_credential_rotated_during_the_ping_is_not_overwritten(session, monkeypatch):
    """A read rotated the cookies while the ping was in flight. The ping's
    write-back used to match on the row alone and put the older credential
    back over the newer one — and the next read was refused with it."""
    row = _netschool_row()
    session.add(row)
    await session.commit()
    rotated = seal(json.dumps({"v": 1, "region": "zabaikalsky", "school_id": 1,
                               "at": "rotated-by-a-read", "cookies": {}, "year_id": 2026}))

    async def ping_while_a_read_rotates(self):
        async with SessionLocal() as other:
            fresh = await other.get(DiarySession, row.id)
            fresh.upstream_token = rotated
            await other.commit()

    monkeypatch.setattr(
        "app.providers.netschool.provider.NetSchoolConnection.keep_alive",
        ping_while_a_read_rotates,
    )
    kept, lost = await diary_keepalive.keep_alive(session)

    async with SessionLocal() as check:
        stored = await check.get(DiarySession, row.id)
    assert stored.upstream_token == rotated
    assert (kept, lost) == (0, 0)


async def test_a_session_used_during_the_ping_is_not_expired_by_it(session, monkeypatch):
    """The ping failed on the credential it read; a read meanwhile rotated to
    a newer one that works. Expiring the row over the old one's failure would
    sign the family out of a session that is fine."""
    row = _netschool_row()
    session.add(row)
    await session.commit()

    async def dead_but_rotated_meanwhile(self):
        async with SessionLocal() as other:
            fresh = await other.get(DiarySession, row.id)
            fresh.upstream_token = seal(json.dumps(
                {"v": 1, "region": "zabaikalsky", "school_id": 1, "at": "newer",
                 "cookies": {}, "year_id": 2026}))
            await other.commit()
        raise SessionExpired

    monkeypatch.setattr(
        "app.providers.netschool.provider.NetSchoolConnection.keep_alive",
        dead_but_rotated_meanwhile,
    )
    kept, lost = await diary_keepalive.keep_alive(session)

    async with SessionLocal() as check:
        stored = await check.get(DiarySession, row.id)
    assert stored.expired_at is None
    assert (kept, lost) == (0, 0)


async def test_a_ping_rewrites_the_credential_only_when_it_rotated(session, monkeypatch):
    """A ping that rotated the session stores the new one; a ping that did not
    leaves the stored blob exactly as it was, rather than re-sealing the same
    session into a new blob every tick."""
    from app.crypto import unseal

    quiet = _netschool_row()
    rotating = _netschool_row(upstream_token=seal(json.dumps(
        {"v": 1, "region": "zabaikalsky", "school_id": 1, "at": "rotate-me", "cookies": {}})))
    session.add_all([quiet, rotating])
    await session.commit()
    quiet_blob = quiet.upstream_token

    async def ping(self):
        if self._client.session["at"] == "rotate-me":
            self._client.session["at"] = "rotated"

    monkeypatch.setattr("app.providers.netschool.provider.NetSchoolConnection.keep_alive", ping)
    assert await diary_keepalive.keep_alive(session) == (2, 0)

    async with SessionLocal() as check:
        assert (await check.get(DiarySession, quiet.id)).upstream_token == quiet_blob
        stored = (await check.get(DiarySession, rotating.id)).upstream_token
    assert json.loads(unseal(stored))["at"] == "rotated"
