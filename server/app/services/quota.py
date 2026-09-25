"""Daily allowances of somebody else's service, spent from the database.

One exists today: DaData's ten thousand requests a day, which the bot and
``/manage/schools`` spend behind a class admin and the anonymous school
directory spends for anybody at all. The anonymous door gets a fixed share and
stops there, so that no amount of anonymous searching leaves an admin creating
a class with «Лимит запросов исчерпан».

Counted in ``usage_counters`` (migration ``0016``), not in memory and not in
``join_attempts``. Not in memory for the reason ``JoinThrottle`` gives: on
Vercel every concurrent invocation is its own process. Not in
``join_attempts`` because that table is pruned to fifteen minutes on every
recorded attempt, and a day's count kept there would quietly become a quarter
of an hour's.

The spend is one statement — insert the day's row or add to it, but only while
the sum stays under the cap — so two invocations spending the last unit at the
same instant cannot both have it. There is no read-then-write here for them to
race through.
"""

from __future__ import annotations

import math
from datetime import UTC, datetime, time, timedelta
from datetime import date as Date
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import UsageCounter

#: The anonymous directory's share of DaData's allowance.
DADATA_ANONYMOUS = "dadata:anon"

#: Of DaData's 10,000 a day, which counts every kind of suggestion together.
#: The other 6,000 are the bot's and ``/manage/schools``', which nothing here
#: meters: those are admins creating a class, a few searches each, and they are
#: the ones this cap exists to protect.
ANONYMOUS_DAILY_UNITS = 4000

#: Where a day starts. DaData is a Moscow company and its allowance is assumed
#: to reset at Moscow midnight — assumed, because nothing in its documentation
#: says which clock it counts by. If it counts in UTC the worst case is two
#: anonymous days' worth inside one of DaData's, 8,000, which still leaves the
#: admins two thousand.
QUOTA_ZONE = ZoneInfo("Europe/Moscow")


class AllowanceSpent(Exception):
    """Today's share is gone; ``retry_after`` seconds until the next day's."""

    def __init__(self, retry_after: int) -> None:
        super().__init__(f"the daily allowance is spent; it resets in {retry_after} s")
        self.retry_after = retry_after


def _aware(now: datetime | None) -> datetime:
    # A naive `now` is UTC, which is what every other clock in this project
    # hands around (`security._utcnow`); reading it as the server's local time
    # would move the day's boundary with the host.
    if now is None:
        return datetime.now(UTC)
    return now if now.tzinfo is not None else now.replace(tzinfo=UTC)


def quota_day(now: datetime | None = None) -> Date:
    """The day an allowance is counted against: today in :data:`QUOTA_ZONE`."""
    return _aware(now).astimezone(QUOTA_ZONE).date()


def seconds_to_reset(now: datetime | None = None) -> int:
    """Whole seconds until the next day in :data:`QUOTA_ZONE`, never 0.

    Never 0, because it goes into ``Retry-After`` and a client told to wait
    no time at all asks again at once and is refused again.
    """
    moment = _aware(now)
    tomorrow = quota_day(moment) + timedelta(days=1)
    midnight = datetime.combine(tomorrow, time.min, tzinfo=QUOTA_ZONE)
    # Both sides in UTC: two datetimes sharing one tzinfo subtract as wall
    # clocks, which is an hour out across a DST change. Moscow keeps none
    # today; the zone is a constant somebody may change.
    remaining = (midnight.astimezone(UTC) - moment.astimezone(UTC)).total_seconds()
    return max(1, math.ceil(remaining))


async def spend(
    session: AsyncSession, scope: str, units: int, *, cap: int, day: Date
) -> bool:
    """Take ``units`` from ``scope``'s allowance for ``day``, or refuse.

    One statement: ``INSERT (scope, day, units) ON CONFLICT (scope, day) DO
    UPDATE SET used = used + units WHERE used + units <= cap RETURNING used``.
    A row comes back when the units were taken; none when the ``WHERE`` held
    the row as it was, which is a refusal that changed nothing. Postgres and
    SQLite (3.35 and later) both read it, and both are what this runs on.

    Commits, because a unit that was spent is spent whatever the request does
    next: it is about to become a request to DaData, which counts it either
    way.

    @return whether the units were taken.
    """
    if units <= 0:
        raise ValueError("spend at least one unit")
    # The insert that opens a day is not guarded by the WHERE, which only
    # applies to the update, so a first spend larger than the cap is refused
    # here instead.
    if units > cap:
        return False

    statement = (
        _upsert(session.get_bind().dialect.name)
        .values(scope=scope, day=day, used=units)
        .on_conflict_do_update(
            index_elements=[UsageCounter.scope, UsageCounter.day],
            set_={"used": UsageCounter.used + units},
            where=UsageCounter.used + units <= cap,
        )
        .returning(UsageCounter.used)
    )
    taken = (await session.execute(statement)).scalar_one_or_none()
    await session.commit()
    return taken is not None


async def used(session: AsyncSession, scope: str, day: Date) -> int:
    """How much of ``scope`` has been spent on ``day``; 0 for a day not begun.

    A column read, not ``session.get``: the spend writes past the ORM, and the
    session factory does not expire on commit, so an object loaded before a
    spend would go on reporting the count from before it.
    """
    spent = await session.scalar(
        select(UsageCounter.used).where(UsageCounter.scope == scope, UsageCounter.day == day)
    )
    return spent or 0


def _upsert(dialect: str):
    """The insert that can say ``ON CONFLICT``, which is each dialect's own.

    Imported by the dialect in use: a SQLite process never needs Postgres's
    compiler loaded, and this is on the path of an anonymous request.
    """
    if dialect == "postgresql":
        from sqlalchemy.dialects.postgresql import insert as postgres_insert

        return postgres_insert(UsageCounter)
    if dialect == "sqlite":
        from sqlalchemy.dialects.sqlite import insert as sqlite_insert

        return sqlite_insert(UsageCounter)
    raise NotImplementedError(f"no atomic spend on {dialect}")
