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

Nothing here touches the database. A school is stored as its name on
``SchoolClass.school``, which is what the class card has always shown.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.providers import dadata

#: Five to a screen. A Telegram inline keyboard of school names is one button
#: per row — the names are long — and more than five turns the message into a
#: scroll on a phone.
PAGE_SIZE = 5

#: Below this the upstream ranks on too little and returns the first twenty
#: legal entities in the country whose name starts with «ш». The number is the
#: shortest real query anybody types: «лицей 2» is seven characters, «гимн 3»
#: is six.
MIN_QUERY = 3

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
    # Their query field is a search box, not an identifier; a very long one is
    # a paste of a whole address and ranks worse than the name alone.
    return query[:150]


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
