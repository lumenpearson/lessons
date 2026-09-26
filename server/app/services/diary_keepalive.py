"""Hold «Сетевой город» sessions open from the cron tick.

A NetSchool session idles out in fifteen minutes to an hour, and this project
stores no password to sign back in with. So the tick pings each live session
with ``GET /webapi/context``, which resets its idle timer — the same call every
open client keeps a session alive with. It needs the external cron (#120):
GitHub's fallback clock is too sparse, and a session goes dead between two of
its ticks.

Four rules the review nailed down:

- **Never touch ``last_used_at``.** Pinging must not look like the family using
  the session, or the 30-day purge of one nobody opens would never fire.
- **Claim before pinging.** ``keepalive_attempted_at`` is set on every attempt,
  whatever its outcome, so a failing origin cannot sit at the head of the queue
  for ever and starve the healthy ones, and two overlapping ticks do not both
  ping the same row.
- **Read, then ping with no session open, then apply.** The pings run with the
  database transaction closed; the results are written back one row at a time
  with a guarded ``UPDATE`` that matches nothing if the row was signed out or
  its credential rotated meanwhile — so a vanished or changed row is skipped,
  not a lost update and not a crash. The guard compares the sealed blob read
  at the claim with the one stored now: that is exact, although sealing is not
  deterministic, because a rotation writes a new blob and nothing else does.
- **An origin that refuses our address is left alone for the rest of the
  tick.** A regional server dropping this deployment's address answers every
  session the same way, so after the first :class:`AddressRefused` the claims
  on that origin not yet started are skipped rather than walked into the same
  wall — at most :data:`KEEPALIVE_CONCURRENCY` pings are wasted per origin per
  tick, the ones already in flight.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy import update as sa_update
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto import diary_enabled, seal, unseal
from app.db import rows_affected
from app.models import DiarySession
from app.providers.diary.errors import AddressRefused, SessionExpired
from app.providers.diary.registry import NETSCHOOL, provider_for

log = logging.getLogger(__name__)

#: At most this many sessions a tick, so one tick's work is bounded whatever the
#: number of families. The rest are first next tick: the queue is ordered by
#: how long since a row was last known good (see `_claim`).
KEEPALIVE_BATCH = 200
#: A floor between two pings of one session, so extra or overlapping ticks are
#: free and one address is not hammered. Well under the shortest idle window.
KEEPALIVE_MIN_INTERVAL = timedelta(minutes=4)
#: Stop starting new pings this many seconds into the tick, so the whole
#: request stays inside the platform's function ceiling (``maxDuration: 30``
#: in ``vercel.json``). Asked when a ping gets its slot, not when its task is
#: created: `asyncio.gather` creates all two hundred at once, so a check made
#: there passed every one of them at t≈0 and stopped nothing.
KEEPALIVE_DEADLINE_SECONDS = 18.0
#: And give up on whatever is still in flight at this point, filed as "skip".
#: A ping has no total timeout of its own — httpx's are per phase, and the
#: SecurityWarning acknowledgement takes the long read one — so without this
#: a ping started at 17.9 s could run the function past its ceiling, and a
#: function killed there never reaches `_apply`: nothing it learnt is written.
KEEPALIVE_HARD_STOP_SECONDS = 24.0
#: At most this many pings in flight at once.
KEEPALIVE_CONCURRENCY = 8


def _now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


@dataclass
class _Claim:
    """One row claimed for a ping, read out as plain values.

    ``credential`` is the **sealed** blob as it stood at the claim; ``region``
    names the origin, which is what the breaker groups by.
    """

    id: int
    provider: str
    region: str | None
    credential: str


@dataclass
class _Result:
    id: int
    outcome: str  # "ok" | "expired" | "skip" | "refused"
    #: The sealed blob read at the claim, so the write-back matches only a row
    #: whose credential has not been rotated since.
    claimed: str
    credential: str | None = None


async def keep_alive(session: AsyncSession, *, started: float | None = None) -> tuple[int, int]:
    """Ping the live «Сетевой город» sessions. @return (kept_alive, lost).

    ``started`` is ``loop.time()`` at the start of the request, so the deadline
    counts the digests and purges that ran first; omitted, it counts from here.
    """
    if not diary_enabled():
        # Nothing would unseal, so a ping could only fail. Do nothing rather
        # than expire every session over a key that was mistyped for an hour.
        return 0, 0
    loop = asyncio.get_running_loop()
    started = started if started is not None else loop.time()

    claims = await _claim(session)
    if not claims:
        return 0, 0

    results = await _ping_all(claims, loop=loop, started=started)
    return await _apply(session, results)


async def _claim(session: AsyncSession) -> list[_Claim]:
    """Take the next batch and stamp ``keepalive_attempted_at`` so no other
    tick takes them too. Committed here, before any network call."""
    now = _now()
    cutoff = now - KEEPALIVE_MIN_INTERVAL
    stmt = (
        select(DiarySession)
        .where(
            DiarySession.provider == NETSCHOOL,
            DiarySession.expired_at.is_(None),
            (DiarySession.keepalive_attempted_at.is_(None))
            | (DiarySession.keepalive_attempted_at < cutoff),
        )
        # Stalest first: the last attempt, or — for a row never pinged — the
        # moment the upstream last took it, which for a new row is its
        # registration. It used to be never-pinged rows first, whatever their
        # age, so more than a batch of fresh registrations a tick (one real
        # session replayed into `/session` is enough) meant no family already
        # in the queue was pinged again, and each idled out upstream.
        .order_by(
            func.coalesce(
                DiarySession.keepalive_attempted_at,
                DiarySession.upstream_ok_at,
                DiarySession.created_at,
            ),
            DiarySession.id,
        )
        .limit(KEEPALIVE_BATCH)
    )
    # FOR UPDATE SKIP LOCKED on Postgres so two overlapping ticks never take the
    # same rows; SQLite (local, tests) has no such clause and one worker anyway.
    if session.bind and session.bind.dialect.name == "postgresql":
        stmt = stmt.with_for_update(skip_locked=True)
    rows = list(await session.scalars(stmt))
    claims: list[_Claim] = []
    for row in rows:
        row.keepalive_attempted_at = now
        claims.append(
            _Claim(
                id=row.id,
                provider=row.provider or "",
                region=row.region,
                credential=row.upstream_token,
            )
        )
    await session.commit()
    return claims


async def _ping_all(claims: list[_Claim], *, loop, started: float) -> list[_Result]:
    """Ping every claimed session, with no database session held open.

    ``refused`` is the per-tick breaker: an origin lands in it on its first
    :class:`AddressRefused`, and a claim on it that has not started yet is
    skipped — checked after the semaphore, so a claim that waited behind the
    first refusal sees it. The row stays claimed, so it goes to the back of
    the queue like any other skipped one.
    """
    semaphore = asyncio.Semaphore(KEEPALIVE_CONCURRENCY)
    refused: set[str] = set()

    async def one(claim: _Claim) -> _Result:
        async with semaphore:
            elapsed = loop.time() - started
            if elapsed > KEEPALIVE_DEADLINE_SECONDS:
                return _Result(claim.id, "skip", claim.credential)  # out of budget; first next tick
            if claim.region is not None and claim.region in refused:
                return _Result(claim.id, "skip", claim.credential)
            try:
                result = await asyncio.wait_for(
                    _ping(claim), KEEPALIVE_HARD_STOP_SECONDS - elapsed
                )
            except TimeoutError:
                log.info("diary keep-alive ping for session %s ran out of the tick", claim.id)
                result = _Result(claim.id, "skip", claim.credential)
        if result.outcome == "refused" and claim.region is not None:
            if claim.region not in refused:
                log.warning(
                    "diary keep-alive: %s refuses this address; "
                    "its other sessions wait for the next tick",
                    claim.region,
                )
            refused.add(claim.region)
        return result

    return list(await asyncio.gather(*(one(c) for c in claims)))


async def _ping(claim: _Claim) -> _Result:
    provider = provider_for(claim.provider)
    plain = unseal(claim.credential)
    if provider is None or not plain:
        # A key rotation or an unknown provider: leave it for a real request to
        # expire when somebody actually asks, rather than expiring en masse.
        return _Result(claim.id, "skip", claim.credential)
    connection = provider.open(plain)
    before = connection.credential
    try:
        await connection.keep_alive()
    except SessionExpired:
        return _Result(claim.id, "expired", claim.credential)
    except AddressRefused:
        # Before the broad catch below, which would file it as one more
        # failed ping: this one says something about every session on the
        # origin, and `_ping_all` acts on it.
        return _Result(claim.id, "refused", claim.credential)
    except Exception:  # noqa: BLE001 - a down origin is left as-is
        log.info("diary keep-alive ping failed for session %s", claim.id, exc_info=True)
        return _Result(claim.id, "skip", claim.credential)
    # Re-sealed only when the ping rotated something: sealing is not
    # deterministic, so writing back an unchanged session would churn the
    # stored blob on every tick for nothing. Compared with what the
    # connection itself serialised before the ping, not with the stored text,
    # so a difference in spelling is not taken for a rotation.
    rotated = connection.credential if connection.credential != before else None
    return _Result(claim.id, "ok", claim.credential, credential=rotated)



async def _apply(session: AsyncSession, results: list[_Result]) -> tuple[int, int]:
    """Write each ping's outcome with a guarded UPDATE, then commit once.

    Each UPDATE matches on what was read — the row, still live, still holding
    the sealed credential the claim read — so a row signed out or rotated
    between the claim and now simply matches nothing and is skipped. Without
    the credential in the guard, a read that rotated the cookies while the ping
    was in flight had them overwritten with the older ones, and the next read
    was refused; and a ping that failed on the old credential expired a row
    whose new one was fine.
    """
    now = _now()
    kept = lost = 0
    for result in results:
        if result.outcome == "ok":
            values = {"kept_alive_at": now, "upstream_ok_at": now}
            if result.credential:
                values["upstream_token"] = seal(result.credential)
            written = await session.execute(
                sa_update(DiarySession)
                .where(
                    DiarySession.id == result.id,
                    DiarySession.expired_at.is_(None),
                    DiarySession.upstream_token == result.claimed,
                )
                .values(**values)
            )
            kept += rows_affected(written)
        elif result.outcome == "expired":
            written = await session.execute(
                sa_update(DiarySession)
                .where(
                    DiarySession.id == result.id,
                    DiarySession.expired_at.is_(None),
                    DiarySession.upstream_token == result.claimed,
                )
                .values(expired_at=now)
            )
            # Counted by what was written: a row the guard skipped was not
            # lost, and the tick's report should not say it was.
            lost += rows_affected(written)
    await session.commit()
    return kept, lost
