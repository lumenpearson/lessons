"""The numbers on the owner's «📊 Проект»: how the whole deployment stands.

Reads only - a handful of ``count(*)``, the self-check's rows and, on
Postgres, two catalogue queries - and nothing is written by looking. No
request is counted here: counting them in the database would add a write to
every request, which on serverless with Neon is real latency, and would break
«reads stop writing». The request graphs are Vercel's and Sentry's, which see
every request already (``docs/specs/2026-10-05-monitoring-design.md``).

Every number is the deployment's, every class's summed, which is why the
screen is the deployment owner's alone (``bot/handlers/project.py``).
"""

from __future__ import annotations

import platform
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy import text as sa_text
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import STARTED_AT, Settings, deployment
from app.db import EXPECTED_REVISION, current_revision
from app.models import (
    BotUser,
    DeviceToken,
    DiarySession,
    HealthCheck,
    ReminderSettings,
    SchoolClass,
)
from app.providers.diary.registry import PETERSBURG


@dataclass(frozen=True)
class CheckRow:
    """One self-check as the screen shows it."""

    name: str
    status: str
    reason: str
    since: datetime
    checked_at: datetime
    previous_checked_at: datetime | None
    round_trip_ms: int | None


@dataclass(frozen=True)
class ProjectStats:
    """Everything «📊 Проект» draws. Times are naive UTC, like every column."""

    now: datetime
    checks: list[CheckRow]
    # The server.
    commit: str
    environment: str
    region: str
    started_at: datetime
    python: str
    # The database.
    revision: str | None
    expected_revision: str
    database_bytes: int | None
    connections: int | None
    # The clock.
    last_tick: datetime | None
    tick_before: datetime | None
    mornings_today: int
    evenings_today: int
    # The project.
    classes: int
    accounts: int
    phones_day: int
    phones_week: int
    #: ``(build, phones)``, newest build first and the phones that never said
    #: one (every APK that speaks only v1) last, as ``None``.
    builds: list[tuple[int | None, int]]
    #: ``(provider, live sessions)``, the most first.
    diaries: list[tuple[str, int]]


def _now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


async def checks(session: AsyncSession) -> list[CheckRow]:
    """The self-check's rows, as the last tick left them."""
    rows = await session.scalars(select(HealthCheck).order_by(HealthCheck.name))
    return [
        CheckRow(
            name=row.name,
            status=row.status,
            reason=row.reason,
            since=row.since,
            checked_at=row.checked_at,
            previous_checked_at=row.previous_checked_at,
            round_trip_ms=row.round_trip_ms,
        )
        for row in rows
    ]


async def postgres_numbers(session: AsyncSession) -> tuple[int | None, int | None]:
    """The database's size in bytes and its open connections, from Postgres's
    own catalogue. Asked of Postgres only: SQLite has neither."""
    size = await session.scalar(sa_text("SELECT pg_database_size(current_database())"))
    connections = await session.scalar(
        sa_text("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()")
    )
    return (
        None if size is None else int(size),
        None if connections is None else int(connections),
    )


async def _count(session: AsyncSession, query) -> int:
    return int(await session.scalar(select(func.count()).select_from(query.subquery())) or 0)


async def gather(
    session: AsyncSession, settings: Settings, now: datetime | None = None
) -> ProjectStats:
    """Read every number «📊 Проект» shows. Writes nothing."""
    now = now or _now()
    rows = await checks(session)
    running = deployment()

    revision = await current_revision(session)
    database_bytes = connections = None
    bind = session.get_bind()
    if bind.dialect.name == "postgresql":
        database_bytes, connections = await postgres_numbers(session)

    # The deployment's day, for a count across classes in every zone: a digest
    # is marked with its class's own date, so near midnight Moscow a class far
    # east has already moved on. The marks are claims, made before the send
    # (``reminders.claim``), so this counts digests the tick took on, which a
    # send that failed is among.
    today = datetime.now(settings.tz).date()
    mornings = await _count(
        session, select(ReminderSettings.id).where(ReminderSettings.last_morning_sent == today)
    )
    evenings = await _count(
        session, select(ReminderSettings.id).where(ReminderSettings.last_evening_sent == today)
    )

    live_phone = DeviceToken.revoked.is_(False)

    async def seen_within(days: int) -> int:
        since = now - timedelta(days=days)
        return await _count(
            session, select(DeviceToken.id).where(live_phone, DeviceToken.last_seen_at >= since)
        )

    phones_day = await seen_within(1)
    phones_week = await seen_within(7)
    builds = [
        (build, int(phones))
        for build, phones in await session.execute(
            select(DeviceToken.client_version, func.count())
            .where(live_phone)
            .group_by(DeviceToken.client_version)
        )
    ]
    builds.sort(key=lambda pair: (pair[0] is None, -(pair[0] or 0)))

    # A NULL provider is a session from before 0015, which is Petersburg's.
    provider = func.coalesce(DiarySession.provider, PETERSBURG)
    diaries = [
        (str(name), int(sessions))
        for name, sessions in await session.execute(
            select(provider, func.count())
            .where(DiarySession.expired_at.is_(None))
            .group_by(provider)
        )
    ]
    diaries.sort(key=lambda pair: (-pair[1], pair[0]))

    return ProjectStats(
        now=now,
        checks=rows,
        commit=running.commit,
        environment=running.environment,
        region=running.region,
        started_at=STARTED_AT,
        python=platform.python_version(),
        revision=revision,
        expected_revision=EXPECTED_REVISION,
        database_bytes=database_bytes,
        connections=connections,
        last_tick=max((row.checked_at for row in rows), default=None),
        tick_before=max(
            (row.previous_checked_at for row in rows if row.previous_checked_at is not None),
            default=None,
        ),
        mornings_today=mornings,
        evenings_today=evenings,
        classes=await _count(session, select(SchoolClass.id)),
        accounts=await _count(session, select(BotUser.telegram_id).distinct()),
        phones_day=phones_day,
        phones_week=phones_week,
        builds=builds,
        diaries=diaries,
    )
