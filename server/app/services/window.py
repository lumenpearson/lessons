"""One school year of a class's days, and the entity tag a phone caches it under.

v1's ``/bundle`` built its window and its tag inside the router
(``api/public.py``); v2's ``GetScheduleWindow`` may not import a router, and a
second copy of «what is in the window» or «does this tag match» is the copy
that drifts (``docs/specs/2026-10-05-server-v2-design.md``, decision 2). So the
rules live here and both versions call them:

- :func:`etag_matches` is v1's ``_matches``, verbatim: a list of tags, ``*``
  and the ``W/`` prefix;
- :func:`strong_etag` is the quoting and hashing v1's ``_etag`` did, over
  whichever canonical text the caller's wire format has;
- :func:`for_year` is v2's window: one school year, terms computed rather than
  seeded, nothing written.

v1's ``/bundle`` keeps its own order — resolve, look ahead, seed, adopt,
commit — because moving a step would move its answers.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

from sqlalchemy.ext.asyncio import AsyncSession

from app.models import SchoolClass, TermKind
from app.schedule import ResolvedDay, ScheduleResolver, school_year_end, school_year_start
from app.services import clock
from app.services import terms as terms_service
from app.services.linking import Access
from app.services.terms import TermSpan

#: The first and last school years a request may name: those whose every day
#: lies inside ``clock``'s bounds. 2000's year opens on 1 September 2000; 2099's
#: would close on 31 May 2100, past ``MAX_DATE``.
FIRST_YEAR = clock.MIN_DATE.year
LAST_YEAR = clock.MAX_DATE.year - 2


class YearOutOfBounds(ValueError):
    """A school year outside :data:`FIRST_YEAR`..:data:`LAST_YEAR`."""


@dataclass(frozen=True)
class Window:
    """What a phone caches for one school year, before any wire format."""

    school_class: SchoolClass
    #: The scheme in force today, which the stored terms may predate.
    scheme: TermKind
    terms: list[TermSpan]
    days: list[ResolvedDay]
    access: Access


def year_in_bounds(year: int) -> bool:
    """Whether ``year`` opens a school year a request may name."""
    return FIRST_YEAR <= year <= LAST_YEAR


async def for_year(
    session: AsyncSession, school_class: SchoolClass, year: int, *, access: Access
) -> Window:
    """Every day of the school year that opens in ``year``, writing nothing.

    The days are the resolver's, from the year's first teaching day through
    31 May; the terms are :func:`terms.spans`, the stored rows or the
    conventional set, never seeded. Raises :class:`YearOutOfBounds` before any
    date is built from a year outside the bounds, because ``date(0, 9, 1)``
    is a ``ValueError`` and ``date(10000, …)`` one too.
    """
    if not year_in_bounds(year):
        raise YearOutOfBounds(year)
    first, last = school_year_start(year), school_year_end(year)
    days = await ScheduleResolver(session, school_class).resolve_range(
        first, (last - first).days + 1
    )
    return Window(
        school_class=school_class,
        scheme=terms_service.scheme_of(school_class),
        terms=await terms_service.spans(session, school_class, year),
        days=days,
        access=access,
    )


def strong_etag(canonical: str) -> str:
    """A strong validator over a window's canonical text, quoted.

    The caller hands the text with ``generated_at`` blanked: the timestamp
    changes on every request, so hashing it would mean no two answers ever
    match and the 304 path never runs. Everything else is what the phone
    caches, the device's own access included, so a role changed in the bot
    is a new tag.
    """
    return '"' + hashlib.sha256(canonical.encode("utf-8")).hexdigest() + '"'


def etag_matches(if_none_match: str | None, etag: str) -> bool:
    """RFC 9110 ``If-None-Match``: a list of validators, ``*``, weak forms allowed."""
    if not if_none_match:
        return False
    for candidate in if_none_match.split(","):
        candidate = candidate.strip()
        if candidate.startswith("W/"):
            candidate = candidate[2:]
        if candidate == "*" or candidate == etag:
            return True
    return False
