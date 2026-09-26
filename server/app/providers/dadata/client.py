"""The HTTP half of the school directory.

This is the only file in the project that knows DaData's URL, its parameter
names and the fact that its key travels in an ``Authorization: Token`` header
rather than as a bearer. Everything it returns is the raw ``suggestions`` list;
turning that into :class:`~app.providers.dadata.models.School` is
:mod:`app.providers.dadata.mapper`'s job.

Why an API at all, rather than a table shipped with the project: there is no
official nationwide register of Russian schools to download. The open-data
endpoints of Rosobrnadzor, the federal education watchdog — the ones every
GitHub project that tried this links to — answer 404 today. What does exist is
the ЕГРЮЛ company register, which every school is in because every school is
one, and DaData is the search over it that people actually use (119 files on
GitHub call this exact URL). The cost is a request per search — two when the
first finds nothing, see :func:`suggest_schools` — and a key in the
environment; the alternative is a snapshot that is wrong by September and says
nothing about it. Nobody calls it per keystroke: the bot searches on a sent
message and the phone on submit or after a pause in typing.

One client per process, not one per request: a person narrowing a search asks
three or four times in a row, and three TLS handshakes is most of the wait.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from typing import Any

import httpx

from app.config import get_settings
from app.providers.dadata.exceptions import (
    NotConfigured,
    QuotaExceeded,
    UnexpectedResponse,
    UpstreamUnavailable,
)

log = logging.getLogger(__name__)

BASE_URL = "https://suggestions.dadata.ru/suggestions/api/4_1/rs"
SUGGEST_PARTY = "/suggest/party"

#: Their ceiling, not ours. The endpoint is built for type-ahead: twenty rows
#: is the most it will return and there is no offset, so a search that matches
#: three hundred schools cannot be paged through — it has to be narrowed. That
#: is why :class:`~app.providers.dadata.models.SchoolPage` carries
#: ``truncated``: the paging this project shows is over what arrived, and the
#: only honest thing to do when twenty arrive is to say so.
MAX_SUGGESTIONS = 20

#: The ОКВЭД activity codes for general education: primary (85.12), basic
#: (85.13) and secondary (85.14). Without this filter a search for «гимназия 3»
#: finds the company that rents the building. 85.11 — pre-school — is
#: deliberately out: a nursery
#: has no 9 «Б».
SCHOOL_OKVED = ("85.12", "85.13", "85.14")

#: The fallback filter, applied here rather than upstream when the filtered
#: search finds nothing at all. Some schools are registered under a
#: neighbouring code in the same group (85.21 a college, 85.41 supplementary),
#: and a person who typed their school's exact name would otherwise be told it
#: does not exist.
EDUCATION_PREFIX = "85."

#: Registered, or on its way out but still teaching this year. A school
#: liquidated in 2013 is noise; one liquidating right now is where somebody is
#: sitting in September.
ACTIVE_STATUS = ("ACTIVE", "LIQUIDATING")

#: This endpoint is called from inside a Telegram callback, where the person is
#: watching a spinner on a button, so the read timeout is short on purpose: a
#: directory that answers in eight seconds is worse than one that says it is
#: not answering and lets the name be typed.
_TIMEOUT = httpx.Timeout(connect=5.0, read=10.0, write=10.0, pool=5.0)
_LIMITS = httpx.Limits(max_connections=5, max_keepalive_connections=2)

_client: httpx.AsyncClient | None = None
_client_lock = asyncio.Lock()


def _token() -> str:
    """The key as it will actually be sent, or ``""``.

    Trimmed, like ``crypto.cipher()`` trims ``DIARY_SECRET``, and for the same
    reason: a value pasted into a host's environment form arrives with a
    trailing newline or a leading space often enough that «set» and «usable»
    have to be the same question. Untrimmed, a key of spaces was truthy here,
    went upstream as ``Authorization: Token   `` and came back 401 — which
    this provider reads as the daily allowance being spent, so the deployment
    that never had a key was told «Лимит запросов исчерпан» and its owner went
    to look at their DaData billing.
    """
    return get_settings().dadata_token_value


def configured() -> bool:
    """Whether this deployment has a key. Cheap enough to ask on every screen."""
    return get_settings().dadata_configured


async def shared_client() -> httpx.AsyncClient:
    """The one client, built on first use."""
    global _client
    if _client is None:
        async with _client_lock:
            if _client is None:
                _client = httpx.AsyncClient(
                    base_url=BASE_URL,
                    timeout=_TIMEOUT,
                    limits=_LIMITS,
                    headers={"accept": "application/json"},
                )
    return _client


async def close_client() -> None:
    """Closes the shared client. For the lifespan's shutdown and for tests."""
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None


async def suggest_schools(
    query: str,
    *,
    region: str | None = None,
    may_retry: Callable[[], Awaitable[bool]] | None = None,
) -> list[dict[str, Any]]:
    """Raw suggestions for one search.

    @param region a region or city to prefer, as people write it («Санкт-
        Петербург», «Татарстан»). A hint, not a filter: the upstream's
        ``locations`` narrows hard, and a school just over a city boundary is
        still the school the child goes to.
    @param may_retry asked before the second request an empty first answer
        makes, and that request is skipped — with ``[]`` — when it answers
        false. The second request costs the allowance exactly what the first
        did, so a caller that meters its share has to be asked for it rather
        than find out afterwards; the anonymous directory passes its daily
        spend here. ``None``, which is what the bot and ``/manage/schools``
        pass, retries as it always has.
    @raises NotConfigured when this deployment has no key.
    """
    token = _token()
    if not token:
        raise NotConfigured()

    payload: dict[str, Any] = {
        "query": query,
        "count": MAX_SUGGESTIONS,
        "status": list(ACTIVE_STATUS),
        "okved": list(SCHOOL_OKVED),
    }
    if region:
        # A boost, not ``locations``: theirs is a hard filter, and a school one
        # street over the city boundary is still the one the child attends.
        payload["locations_boost"] = [{"region": region}]

    suggestions = await _post(SUGGEST_PARTY, payload, token)
    if suggestions:
        return suggestions

    # Nothing matched under the three school codes. Rather than tell somebody
    # who typed their own school's name that it does not exist, ask again
    # without the filter and keep whatever is in the education group. One
    # extra request, only ever on an empty result.
    if may_retry is not None and not await may_retry():
        return []
    payload.pop("okved", None)
    return [
        item
        for item in await _post(SUGGEST_PARTY, payload, token)
        if _okved_of(item).startswith(EDUCATION_PREFIX)
    ]


async def _post(path: str, payload: dict[str, Any], token: str) -> list[dict[str, Any]]:
    client = await shared_client()
    try:
        response = await client.post(
            path,
            json=payload,
            headers={"authorization": f"Token {token}", "content-type": "application/json"},
        )
    except httpx.TimeoutException as failure:
        raise UpstreamUnavailable("Справочник школ не ответил вовремя") from failure
    except httpx.HTTPError as failure:
        log.warning("dadata %s failed: %s", path, failure)
        raise UpstreamUnavailable() from failure

    # 401 is a wrong or missing key, 403 a key whose plan does not cover this
    # call, 429 the daily allowance. All three are the owner's to fix and none
    # is worth retrying inside the request, so they share one answer.
    if response.status_code in (401, 403, 429):
        log.warning("dadata refused the key: %s", response.status_code)
        raise QuotaExceeded()
    if response.status_code >= 500:
        raise UpstreamUnavailable()
    if response.status_code != 200:
        raise UnexpectedResponse()

    try:
        body = response.json()
    except ValueError as failure:
        raise UnexpectedResponse() from failure
    if not isinstance(body, dict):
        raise UnexpectedResponse()
    suggestions = body.get("suggestions")
    if suggestions is None:
        return []
    if not isinstance(suggestions, list):
        raise UnexpectedResponse()
    rows = [item for item in suggestions if isinstance(item, dict)]
    if len(rows) != len(suggestions):
        _note_rows_that_were_not_objects(suggestions, rows)
    return rows


def _note_rows_that_were_not_objects(
    suggestions: list[Any], rows: list[dict[str, Any]]
) -> None:
    """Say what was dropped for not being an object, and what it was instead.

    The same guard the diary provider carries, for the same reason: `mapper`
    says so when a whole answer read as nothing, but it never sees the row that
    was not an object at all, because this function has already taken it out —
    twenty strings are twenty rows the mapper is never handed, so to it the
    answer was simply empty and there was nothing to remark on. A
    whole answer of the wrong shape therefore reached the bot as «ничего не
    найдено» - which is also what a school that is genuinely not in the
    register looks like - and said nothing about which of the two it was.

    Dropping, not raising, and the returned list is the same one as before:
    `suggest_schools` reads it to decide whether to ask a second time without
    the ОКВЭД filter, and that decision must not move because a line was added
    to the log.
    """
    kinds = ", ".join(
        sorted({type(item).__name__ for item in suggestions if not isinstance(item, dict)})
    )
    if rows:
        log.info(
            "dadata: dropped %d of %d suggestion(s) that were not objects (%s)",
            len(suggestions) - len(rows),
            len(suggestions),
            kinds,
        )
        return
    log.warning(
        "dadata: all %d suggestion(s) were %s rather than objects; the register's "
        "shape has moved and the search reads as «nothing found»",
        len(suggestions),
        kinds,
    )


def _okved_of(item: dict[str, Any]) -> str:
    data = item.get("data")
    okved = data.get("okved") if isinstance(data, dict) else None
    return okved if isinstance(okved, str) else ""
