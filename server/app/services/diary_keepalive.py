"""Hold «Сетевой город» sessions open from the cron tick.

A NetSchool session idles out in fifteen minutes to an hour, and this project
stores no password to sign back in with. So the tick pings each live session
with ``GET /webapi/context``, which resets its idle timer — the same call every
open client keeps a session alive with. It needs the external cron (#120):
GitHub's fallback clock is too sparse, and a session goes dead between two of
its ticks.

Three rules the review nailed down:

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
  not a lost update and not a crash.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from sqlalchemy import select
from sqlalchemy import update as sa_update
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto import diary_enabled, seal, unseal
from app.models import DiarySession
from app.providers.diary.errors import SessionExpired
from app.providers.diary.registry import NETSCHOOL, provider_for

log = logging.getLogger(__name__)

#: At most this many sessions a tick, so one tick's work is bounded whatever the
#: number of families. The rest are first next tick (ordered oldest-attempt).
KEEPALIVE_BATCH = 200
#: A floor between two pings of one session, so extra or overlapping ticks are
#: free and one address is not hammered. Well under the shortest idle window.
KEEPALIVE_MIN_INTERVAL = timedelta(minutes=4)
#: Stop starting new pings this many seconds into the tick, so the whole
#: request stays inside the platform's function ceiling.
KEEPALIVE_DEADLINE_SECONDS = 18.0
#: At most this many pings in flight at once.
KEEPALIVE_CONCURRENCY = 8


def _now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


@dataclass
class _Claim:
    """One row claimed for a ping, read out as plain values."""

    id: int
    provider: str
    credential: str


@dataclass
class _Result:
    id: int
    outcome: str  # "ok" | "expired" | "skip"
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
        .order_by(DiarySession.keepalive_attempted_at.is_(None).desc(),
                  DiarySession.keepalive_attempted_at,
                  DiarySession.id)
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
        claims.append(_Claim(id=row.id, provider=row.provider or "", credential=row.upstream_token))
    await session.commit()
    return claims


async def _ping_all(claims: list[_Claim], *, loop, started: float) -> list[_Result]:
    """Ping every claimed session, with no database session held open."""
    semaphore = asyncio.Semaphore(KEEPALIVE_CONCURRENCY)

    async def one(claim: _Claim) -> _Result:
        if loop.time() - started > KEEPALIVE_DEADLINE_SECONDS:
            return _Result(claim.id, "skip")  # out of budget; first next tick
        async with semaphore:
            return await _ping(claim)

    return list(await asyncio.gather(*(one(c) for c in claims)))


async def _ping(claim: _Claim) -> _Result:
    provider = provider_for(claim.provider)
    plain = unseal(claim.credential)
    if provider is None or not plain:
        # A key rotation or an unknown provider: leave it for a real request to
        # expire when somebody actually asks, rather than expiring en masse.
        return _Result(claim.id, "skip")
    connection = provider.open(plain)
    try:
        await connection.keep_alive()
    except SessionExpired:
        return _Result(claim.id, "expired")
    except Exception:  # noqa: BLE001 - a down or blocking origin is left as-is
        log.info("diary keep-alive ping failed for session %s", claim.id, exc_info=True)
        return _Result(claim.id, "skip")
    return _Result(claim.id, "ok", credential=connection.credential)



async def _apply(session: AsyncSession, results: list[_Result]) -> tuple[int, int]:
    """Write each ping's outcome with a guarded UPDATE, then commit once.

    Each UPDATE matches on what was read, so a row signed out or rotated between
    the claim and now simply matches nothing and is skipped — no lost update, no
    StaleDataError.
    """
    now = _now()
    kept = lost = 0
    for result in results:
        if result.outcome == "ok":
            values = {"kept_alive_at": now, "upstream_ok_at": now}
            if result.credential:
                values["upstream_token"] = seal(result.credential)
            await session.execute(
                sa_update(DiarySession)
                .where(DiarySession.id == result.id, DiarySession.expired_at.is_(None))
                .values(**values)
            )
            kept += 1
        elif result.outcome == "expired":
            await session.execute(
                sa_update(DiarySession)
                .where(DiarySession.id == result.id, DiarySession.expired_at.is_(None))
                .values(expired_at=now)
            )
            lost += 1
    await session.commit()
    return kept, lost
