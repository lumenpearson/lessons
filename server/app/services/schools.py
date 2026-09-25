"""Searching the school directory, and cutting the answer into pages.

The one implementation the bot's picker and ``GET /api/v1/manage/schools``
both call — same page size, same «уточните запрос», same wording when the
directory is not configured. Two implementations of "page 2 of a school
search" disagree about what page 2 is within a month.

**The paging here is not the upstream's.** DaData's suggest endpoint is built
for type-ahead: twenty rows is the ceiling and there is no offset, so there is
nothing to page *through*. What this does is cut the twenty into screenfuls a
thumb can read, and say so when twenty came back — because then the answer is
"the first twenty of an unknown number" and the only way forward is a longer
query. Pretending otherwise would show «стр. 1 из 4» for a search that matched
three hundred schools and quietly hide the other 280.

Searching for a class touches no table: a school is stored as its name on
``SchoolClass.school``, which is what the class card has always shown. The
anonymous directory (:func:`regions_for`) is the one caller that writes, and
only to spend from its daily share of the upstream's allowance
(``services/quota.py``).

**Two questions, one search.** The bot and ``/manage/schools`` ask «which
school is this» and get a page of schools. The phone, before it knows anything
about the person holding it, asks «which region is a school of this name in»
and gets the same twenty rows grouped by region — so that «лицей 1535» can
answer «Москва» and the region list can open on it. Same upstream, same query
rules, same errors; only what is done with the rows differs.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app import catalog
from app.providers import dadata
from app.services import quota

#: Five to a screen. A Telegram inline keyboard of school names is one button
#: per row — the names are long — and more than five turns the message into a
#: scroll on a phone.
PAGE_SIZE = 5

#: Below this the upstream ranks on too little and returns the first twenty
#: legal entities in the country whose name starts with «ш». The number is the
#: shortest real query anybody types: «лицей 2» is seven characters, «гимн 3»
#: is six.
MIN_QUERY = 3

#: What is sent upstream, and what the bot echoes back when nothing was found.
#: Their query field is a search box rather than an identifier, so a longer
#: query is a paste of a whole address and ranks worse than the name alone.
#: Named rather than written into ``normalise_query`` because the echo has to
#: quote the query that was actually searched with: while the truncation was a
#: literal there, the bot's «По запросу … ничего не нашлось» quoted the
#: untruncated one, and an inbound message at Telegram's own ceiling made that
#: reply 4191 characters — refused whole, from a ``Message`` handler with
#: nothing to apologise on.
MAX_QUERY = 150

#: What the class card keeps. The column is ``String(200)``; a full ЕГРЮЛ name
#: with the legal form spelled out can exceed it, and a silently truncated name
#: is worse than a short one.
MAX_NAME = 200


class SearchError(ValueError):
    """A query that cannot be searched with, said in Russian.

    A ``ValueError`` like the other services' errors, so the API turns it into
    a 422 and the bot shows the same sentence in an alert.
    """


def normalise_query(raw: str | None) -> str:
    """The query as it will be sent, or a Russian sentence saying why not."""
    query = " ".join((raw or "").split())
    if len(query) < MIN_QUERY:
        raise SearchError(f"Введите хотя бы {MIN_QUERY} символа названия школы")
    return query[:MAX_QUERY]


def available() -> bool:
    """Whether this deployment can search at all."""
    return dadata.configured()


@dataclass(frozen=True)
class SearchResult:
    """Everything one search found, before it is cut into pages."""

    schools: list[dadata.School]
    #: The upstream's ceiling was reached, so this is the first twenty of an
    #: unknown number.
    truncated: bool


async def search(raw_query: str | None, *, region: str | None = None) -> SearchResult:
    """Every school matching ``raw_query``, at most twenty of them.

    Searched once and paged afterwards by :func:`page_of`, rather than once per
    page: the upstream has no offset to page with, a daily allowance to spend,
    and a person turning pages is looking at one answer, not asking again.

    @raises SearchError when the query is too short to search with.
    @raises app.providers.dadata.DirectoryError when the directory is not
        configured, not answering, or answering something new.
    """
    query = normalise_query(raw_query)
    items = await dadata.suggest_schools(query, region=region)
    return SearchResult(
        schools=dadata.to_schools(items),
        # Read off what the upstream sent, not off what survived mapping: the
        # ceiling was hit whether or not one of the twenty was unreadable.
        truncated=len(items) >= dadata.MAX_SUGGESTIONS,
    )


def page_of(
    schools: list[dadata.School],
    page: int = 1,
    *,
    size: int = PAGE_SIZE,
    truncated: bool = False,
) -> dadata.SchoolPage:
    """One screenful.

    @param page one-based. A page past either end is clamped rather than
        refused: the pager is a button somebody pressed twice, and «страницы не
        существует» is not an answer to that.
    @param size rows per page. Five suits an inline keyboard; a client with a
        scrolling list asks for all twenty at once and pages them itself, which
        is one upstream search instead of four.
    """
    size = max(1, size)
    total = len(schools)
    pages = max(1, -(-total // size))
    page = min(max(page, 1), pages)
    start = (page - 1) * size
    return dadata.SchoolPage(
        items=schools[start : start + size],
        page=page,
        pages=pages,
        total=total,
        truncated=truncated,
    )


def stored_name(school: dadata.School) -> str:
    """What goes on ``SchoolClass.school`` once one is picked."""
    return school.name[:MAX_NAME]


# ---------------------------------------------------------------------------
# Which regions a school of this name is in
# ---------------------------------------------------------------------------

#: The most regions one answer names. Twenty rows can in principle be twenty
#: regions, and a list of twenty is the region picker again rather than an
#: answer.
REGION_GROUPS_MAX = 10

#: A full answer spread over this many regions or more is a name too common to
#: place — «Гимназия № 1» is in every one of them, and twenty rows are twenty of
#: several hundred — so the answer is «выберите регион», not a guess.
GENERIC_REGIONS = 4

#: What a group shows of itself: a few towns and a few names, enough to
#: recognise one's own school by, never the whole list.
GROUP_CITIES = 3
GROUP_EXAMPLES = 3

#: A token of a folded query: the numero sign alone, a run of digits, or a run
#: of letters. The numero sign is its own token because people type «№5» as
#: often as «№ 5», and the catalog's list names it on its own.
_TOKENS = re.compile(r"№|\d+|[^\W\d_]+")


def _fold(text: str) -> str:
    return text.casefold().replace("ё", "е")


def is_generic(query: str) -> bool:
    """Whether ``query`` names a kind of school and a small number, and nothing else.

    «Школа № 5», «СОШ 12» and «гимназия 3» are in every region there is, so
    asking the directory for them spends an upstream request on twenty rows
    from twenty places and places nothing. With the words a school's name is
    *made of* taken away (``catalog.school_words()``, the same list the phone
    reads), what is left has to be something that tells schools apart: a
    town, a name, or a number of three digits or more. «лицей 1535» and
    «гимназия 1 Казань» are asked; «гимназия 3» is not.
    """
    words = {_fold(word) for word in catalog.school_words()}
    left = [token for token in _TOKENS.findall(_fold(query)) if token not in words]
    return all(token.isdigit() and len(token) <= 2 for token in left)


@dataclass
class RegionGroup:
    """The hits of one search that are in one region."""

    #: The catalog's key, or ``None`` when the directory named a region the
    #: catalog cannot place — then ``label`` is what the directory called it.
    region: str | None
    #: The two-digit subject code: the catalog's for a region it placed, the
    #: directory's own for one it could not.
    code: str | None
    label: str | None
    schools: int = 0
    cities: list[str] = field(default_factory=list)
    examples: list[str] = field(default_factory=list)

    def add(self, school: dadata.School) -> None:
        self.schools += 1
        if school.city and school.city not in self.cities and len(self.cities) < GROUP_CITIES:
            self.cities.append(school.city)
        if school.name not in self.examples and len(self.examples) < GROUP_EXAMPLES:
            self.examples.append(school.name)


@dataclass(frozen=True)
class RegionSearch:
    """Everything one «which region» search found."""

    #: As searched: normalised and cut to :data:`MAX_QUERY`.
    query: str
    groups: list[RegionGroup] = field(default_factory=list)
    #: The upstream's ceiling was reached; see :class:`SearchResult`.
    truncated: bool = False
    #: Too common to place — said instead of a list, or on top of one.
    generic: bool = False


def place(school: dadata.School) -> catalog.CatalogRegion | None:
    """The catalog region a register row is in, or ``None``.

    By code first, because the code has one spelling and the name several; by
    the register's own name for the region only when the row carries no code
    the catalog knows. Never by a partial name — that is ``by_dadata_name``'s
    rule, and the reason «Алтай» cannot land in «Алтайский край».
    """
    return catalog.by_code(school.region_code) or catalog.by_dadata_name(school.region)


def group_by_region(schools: list[dadata.School]) -> list[RegionGroup]:
    """The schools, grouped by the region each is in, best-ranked region first.

    A group's place is its best hit's, and since the rows arrive in the
    upstream's rank order that is simply the order the groups were first seen
    in — no two hits share a rank, so there is never a tie to break by size.
    A row that names no region at all — no code, no name — is left out: a
    group without a region is no answer to «which region».
    """
    groups: dict[tuple[str | None, ...], RegionGroup] = {}
    for school in schools:
        region = place(school)
        if region is not None:
            key: tuple[str | None, ...] = ("catalog", region.key)
            group = groups.get(key) or RegionGroup(region=region.key, code=region.code, label=None)
        elif school.region_code or school.region:
            # Kept apart by the register's own spelling, folded like the
            # catalog folds it, so «г Байконур» and «г. Байконур» are one.
            key = ("register", school.region_code, catalog.normalise_name(school.region or ""))
            group = groups.get(key) or RegionGroup(
                region=None, code=school.region_code, label=school.region
            )
        else:
            continue
        group.add(school)
        groups[key] = group
    return list(groups.values())


async def regions_for(
    session: AsyncSession, raw_query: str | None, *, now: datetime | None = None
) -> RegionSearch:
    """The regions a school called ``raw_query`` may be in.

    A generic name is answered here, with no upstream call and nothing spent.
    Anything else spends one unit of the anonymous daily share before it is
    sent, and a second only if the upstream's empty-answer retry is made —
    which it is not, when that second unit is not there.

    @raises SearchError when the query is too short to search with.
    @raises quota.AllowanceSpent when today's anonymous share is gone.
    @raises app.providers.dadata.DirectoryError when the directory is not
        configured, not answering, or answering something new.
    """
    query = normalise_query(raw_query)
    if is_generic(query):
        return RegionSearch(query=query, generic=True)

    day = quota.quota_day(now)

    async def spend_one() -> bool:
        # The cap is read at the call, not bound at import, so the allowance
        # can be changed in one place and a test can lower it.
        return await quota.spend(
            session, quota.DADATA_ANONYMOUS, 1, cap=quota.ANONYMOUS_DAILY_UNITS, day=day
        )

    if not await spend_one():
        raise quota.AllowanceSpent(quota.seconds_to_reset(now))

    items = await dadata.suggest_schools(query, may_retry=spend_one)
    groups = group_by_region(dadata.to_schools(items, per_region=True))
    truncated = len(items) >= dadata.MAX_SUGGESTIONS
    return RegionSearch(
        query=query,
        groups=groups[:REGION_GROUPS_MAX],
        truncated=truncated,
        # Only a *full* answer can be too common to place: fewer than twenty
        # rows is everything the register has, and five regions of it is five
        # real answers.
        generic=truncated and len(groups) >= GENERIC_REGIONS,
    )
