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


# ---------------------------------------------------------------------------
# Through the real client, not a patched `keep_alive`
# ---------------------------------------------------------------------------


def _upstream(monkeypatch, answer):
    """«Сетевой город»'s shared client over a transport answering every call
    with ``answer(request)``."""
    import httpx

    from app.providers.diary import http as diary_http
    from app.providers.netschool import client as nsclient

    async def shared():
        return httpx.AsyncClient(
            transport=httpx.MockTransport(answer), cookies=diary_http.NoCookieJar()
        )

    monkeypatch.setattr(nsclient, "shared_client", shared)


async def test_an_outage_page_does_not_sign_the_region_out(session, monkeypatch):
    """A regional server mid-deploy answers nginx's 502 page. Read as a login
    page, every session the tick claimed on that region was expired at once —
    and none can be signed back in without its family, because no password is
    kept. Every keep-alive test before this one patched `keep_alive` itself,
    so the client's own reading of the answer was never asked."""
    import httpx

    rows = [_netschool_row() for _ in range(5)]
    session.add_all(rows)
    await session.commit()
    _upstream(monkeypatch, lambda request: httpx.Response(
        502, text="<html><body><h1>502 Bad Gateway</h1>nginx</body></html>",
        headers={"content-type": "text/html"},
    ))

    assert await diary_keepalive.keep_alive(session) == (0, 0)
    for row in rows:
        await session.refresh(row)
        assert row.expired_at is None


async def test_a_login_page_still_expires_the_session(session, monkeypatch):
    import httpx

    row = _netschool_row()
    session.add(row)
    await session.commit()
    _upstream(monkeypatch, lambda request: httpx.Response(
        200, text="<form action=/login>", headers={"content-type": "text/html"}
    ))

    assert await diary_keepalive.keep_alive(session) == (0, 1)


# ---------------------------------------------------------------------------
# The tick's budget, and the queue
# ---------------------------------------------------------------------------


def _claims(count: int) -> list:
    return [
        diary_keepalive._Claim(id=i, provider="netschool", region="zabaikalsky", credential="x")
        for i in range(count)
    ]


async def test_the_deadline_stops_the_pings_still_waiting_for_a_slot(monkeypatch):
    """`gather` starts every claim's task at once, so the deadline — checked
    before the semaphore — was passed by all of them at t≈0: forty claims, a
    deadline at 1.5 s and one-second pings pinged all forty in five seconds.
    Checked when the slot comes, the third wave of eight never starts."""
    import asyncio

    async def slow(claim):
        await asyncio.sleep(0.2)
        return diary_keepalive._Result(claim.id, "ok", claim.credential)

    monkeypatch.setattr(diary_keepalive, "_ping", slow)
    monkeypatch.setattr(diary_keepalive, "KEEPALIVE_DEADLINE_SECONDS", 0.3)
    loop = asyncio.get_running_loop()

    results = await diary_keepalive._ping_all(_claims(40), loop=loop, started=loop.time())

    pinged = [r for r in results if r.outcome == "ok"]
    skipped = [r for r in results if r.outcome == "skip"]
    assert diary_keepalive.KEEPALIVE_CONCURRENCY <= len(pinged) <= 16
    assert len(pinged) + len(skipped) == 40


async def test_a_ping_still_in_flight_at_the_hard_stop_is_given_up(monkeypatch):
    """A hung origin, or a SecurityWarning round trip on the long read timeout,
    held the tick until the platform killed it — before `_apply`, so nothing
    the tick learnt was written. It is now abandoned as a skip."""
    import asyncio

    async def hung(claim):
        await asyncio.sleep(30)
        return diary_keepalive._Result(claim.id, "ok", claim.credential)

    monkeypatch.setattr(diary_keepalive, "_ping", hung)
    monkeypatch.setattr(diary_keepalive, "KEEPALIVE_HARD_STOP_SECONDS", 0.3)
    loop = asyncio.get_running_loop()
    began = loop.time()

    results = await diary_keepalive._ping_all(_claims(8), loop=loop, started=began)

    assert loop.time() - began < 5
    assert [r.outcome for r in results] == ["skip"] * 8


async def test_fresh_registrations_do_not_starve_a_family_already_in_the_queue(
    session, monkeypatch
):
    """Never-pinged rows used to go first whatever their age, so more than a
    batch of new ones a tick — one real session replayed into `/session` over
    and over is enough — meant a family's row, due for its ping, never got
    one. The queue is now the stalest first, and a fresh row was just seen
    working by the registration itself."""
    from datetime import datetime, timedelta

    monkeypatch.setattr(diary_keepalive, "KEEPALIVE_BATCH", 5)
    now = datetime.utcnow()
    family = _netschool_row(
        keepalive_attempted_at=now - timedelta(minutes=10), upstream_ok_at=now - timedelta(hours=1)
    )
    session.add(family)
    await session.commit()
    session.add_all([_netschool_row(upstream_ok_at=now) for _ in range(5)])
    await session.commit()

    claimed = await diary_keepalive._claim(session)

    assert family.id in {claim.id for claim in claimed}


async def test_a_second_tick_within_the_floor_pings_nothing(session, monkeypatch):
    """An extra or overlapping tick is free: a row attempted less than
    `KEEPALIVE_MIN_INTERVAL` ago is not taken again. Without the floor every
    tick — the external cron and `reminders.yml` both — pings every session,
    and a caller who can fire the tick can make us hammer a regional server."""
    from datetime import datetime, timedelta

    pinged: list[int] = []

    async def fake_keep_alive(self):
        pinged.append(1)

    monkeypatch.setattr(
        "app.providers.netschool.provider.NetSchoolConnection.keep_alive", fake_keep_alive
    )
    row = _netschool_row()
    stale = _netschool_row(
        keepalive_attempted_at=datetime.utcnow()
        - diary_keepalive.KEEPALIVE_MIN_INTERVAL
        - timedelta(minutes=1)
    )
    session.add_all([row, stale])
    await session.commit()

    assert await diary_keepalive.keep_alive(session) == (2, 0)
    assert await diary_keepalive.keep_alive(session) == (0, 0)
    assert pinged == [1, 1]


async def test_a_tick_that_arrives_out_of_budget_pings_nothing(session, monkeypatch):
    """The deadline counts from the start of the request the tick handed in,
    so a morning whose digests used the budget up leaves the keep-alive for
    the next tick rather than running the function past its ceiling."""
    import asyncio

    pinged: list[int] = []

    async def fake_keep_alive(self):
        pinged.append(1)

    monkeypatch.setattr(
        "app.providers.netschool.provider.NetSchoolConnection.keep_alive", fake_keep_alive
    )
    row = _netschool_row()
    session.add(row)
    await session.commit()

    late = asyncio.get_running_loop().time() - diary_keepalive.KEEPALIVE_DEADLINE_SECONDS - 1
    assert await diary_keepalive.keep_alive(session, started=late) == (0, 0)
    assert pinged == []
    await session.refresh(row)
    assert row.kept_alive_at is None


async def test_on_postgres_two_overlapping_ticks_never_take_the_same_rows():
    """`FOR UPDATE SKIP LOCKED` is the only thing keeping two ticks — the
    external cron and `reminders.yml` — off each other's rows, and it is
    asked for on Postgres alone, which CI does not run. So the claim is built
    against a session that says it is Postgres and the statement read back."""
    from types import SimpleNamespace

    from sqlalchemy.dialects import postgresql

    class Captured:
        bind = SimpleNamespace(dialect=postgresql.dialect())

        def __init__(self) -> None:
            self.statements: list = []

        async def scalars(self, statement):
            self.statements.append(statement)
            return []

        async def commit(self) -> None:
            pass

    captured = Captured()
    assert await diary_keepalive._claim(captured) == []

    (statement,) = captured.statements
    sql = " ".join(str(statement.compile(dialect=postgresql.dialect())).split())
    assert sql.endswith("FOR UPDATE SKIP LOCKED")
    assert "diary_sessions.keepalive_attempted_at <" in sql
