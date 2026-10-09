"""The class's wall clock, and the dates a request may name.

Both lived in ``api/public.py``, where ``edit.py`` and ``diary.py`` imported
them from a router, and where a v2 handler could not reach them without
importing v1 (``docs/specs/2026-10-05-server-v2-design.md``, decision 2). They
are one answer for every shell: a bound that two copies hold is a bound that
one day disagrees with itself.
"""

from __future__ import annotations

from datetime import UTC, datetime
from datetime import date as Date

from app.models import SchoolClass

#: The widest dates a request may name. Every date a client sends is arbitrary
#: input, and the resolver does arithmetic on top of it — up to a year
#: forward, then three weeks of look-ahead — which near ``date.max`` raises
#: OverflowError: a 500 out of a query string. These are the dates a school
#: timetable can plausibly mean, and everything outside them is refused with
#: a sentence saying so.
MIN_DATE = Date(2000, 1, 1)
MAX_DATE = Date(2100, 1, 1)

#: What a date a write names is refused with outside those bounds: v1's
#: ``_check_date`` and v2's writes alike, built from the bounds so that the
#: sentence cannot disagree with them.
DATE_OUT_OF_BOUNDS = f"date must be between {MIN_DATE.isoformat()} and {MAX_DATE.isoformat()}"


def in_bounds(*days: Date) -> bool:
    """Whether every one of ``days`` is a date a request may name."""
    return all(MIN_DATE <= day <= MAX_DATE for day in days)


def now(school_class: SchoolClass) -> datetime:
    """The class's wall clock, in its own zone rather than the server's.

    A function rather than an expression at each call site, so that a test
    pins it to a Monday morning in one place: ``today`` reads it through this
    module's globals, so patching ``clock.now`` moves both.
    """
    return datetime.now(school_class.tz)


def today(school_class: SchoolClass) -> Date:
    """The class's date, which is not the server's when they are hours apart."""
    return now(school_class).date()


def wall(stamp: datetime | None, school_class: SchoolClass) -> datetime | None:
    """A stored UTC stamp on the class's own wall clock.

    ``created_at`` and friends are naive UTC in the database. An admin in
    Vladivostok reading a Moscow server's log should not see yesterday evening
    against this morning's change, so v1's management answers convert them
    here, as the bot's «📜 Журнал» does. v2 sends instants instead
    (``rpc.values.instant``), and a client converts them with the class's zone.
    Moved from ``api/manage/_common._wall`` (the server-v2 design, decision 2).
    """
    if stamp is None:
        return None
    return stamp.replace(tzinfo=UTC).astimezone(school_class.tz).replace(tzinfo=None)
