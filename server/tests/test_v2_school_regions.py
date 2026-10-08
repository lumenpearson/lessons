"""``DirectoryService.ListSchoolRegions``: which regions a school of this name is in.

v1's ``GET /directory/school-regions`` over v2, through the same
``directory.school_regions``: the same answer, best-ranked region first; the
same refusals in the same order and words; and one budget for both versions,
twenty searches a caller and one anonymous share of the directory's allowance
(``docs/specs/2026-10-05-server-v2-design.md``, decision 11). Anonymous, so no
call here carries a token. Every call past validation is counted, both of
``both``'s, and stays counted when it is refused (decision 4).

Nothing reaches DaData: ``dadata.suggest_schools`` is replaced, and keeps the
queries it was asked.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import pytest
from sqlalchemy import func, select

from app import wording
from app.config import get_settings
from app.contract.lessons.v2.directory_pb import ListSchoolRegionsRequest
from app.models import JoinAttempt
from app.providers import dadata
from app.security import directory_limiter
from app.services import quota
from app.services import schools as schools_service

METHOD = "DirectoryService/ListSchoolRegions"
V1 = "/api/v1/directory/school-regions"


def _row(name: str, *, ogrn: str, city: str, kladr: str) -> dict[str, Any]:
    """One suggestion of DaData's ``suggest/party``, carrying what the mapper reads."""
    return {
        "value": name,
        "data": {
            "ogrn": ogrn,
            "name": {"short_with_opf": name},
            "state": {"status": "ACTIVE"},
            "address": {"value": city, "data": {"city": city, "region_kladr_id": kladr}},
        },
    }


#: Moscow's one hit ranks first, so Moscow comes first, though Tatarstan has two.
LYCEUM = [
    _row('ГБОУ "ЛИЦЕЙ № 1535"', ogrn="1", city="Москва", kladr="7700000000000"),
    _row('МБОУ "ЛИЦЕЙ № 1535"', ogrn="2", city="Казань", kladr="1600000000000"),
    _row('МАОУ "ЛИЦЕЙ № 1535"', ogrn="3", city="Набережные Челны", kladr="1600000000000"),
]


@dataclass
class _Upstream:
    """A configured directory: what it answers, what it fails with, and the
    queries it was asked."""

    rows: list[dict[str, Any]] = field(default_factory=list)
    failure: Exception | None = None
    asked: list[str] = field(default_factory=list)


@pytest.fixture
def upstream(monkeypatch) -> _Upstream:
    directory = _Upstream()
    monkeypatch.setattr(get_settings(), "dadata_token", "<redacted>")

    async def suggest(query: str, *, region: str | None = None, may_retry: Any = None):
        directory.asked.append(query)
        if directory.failure is not None:
            raise directory.failure
        return directory.rows

    monkeypatch.setattr(dadata, "suggest_schools", suggest)
    return directory


def _ask(query: str) -> ListSchoolRegionsRequest:
    return ListSchoolRegionsRequest(query=query)


def _plain(region: Any) -> dict[str, Any]:
    """A v2 region as v1's ``SchoolRegionOut`` writes it."""
    return {
        "region": region.region if region.has_field("region") else None,
        "code": region.code if region.has_field("code") else None,
        "label": region.label if region.has_field("label") else None,
        "schools": region.schools,
        "cities": list(region.cities),
        "examples": list(region.examples),
    }


async def _attempts(session) -> int:
    return await session.scalar(select(func.count()).select_from(JoinAttempt)) or 0


async def _spent(session) -> int:
    return await quota.used(session, quota.DADATA_ANONYMOUS, quota.quota_day())


async def test_the_regions_are_v1_s_best_first(v2, upstream) -> None:
    upstream.rows = LYCEUM
    answer = await v2.both(METHOD, _ask("  лицей   1535 "))
    v1 = (await v2.http.get(V1, params={"q": "  лицей   1535 "})).json()
    message = answer.message
    assert (message.query, message.truncated, message.generic) == (
        v1["query"],
        v1["truncated"],
        v1["generic"],
    )
    assert message.query == "лицей 1535"
    assert [_plain(region) for region in message.regions] == v1["regions"]
    assert [(region.region, region.code, region.schools) for region in message.regions] == [
        ("moscow", "77", 1),
        ("tatarstan", "16", 2),
    ]
    # One search a call, the two of `both` and v1's.
    assert upstream.asked == ["лицей 1535"] * 3


async def test_a_name_too_common_to_place_is_answered_without_the_directory(
    v2, session, upstream
) -> None:
    answer = await v2.both(METHOD, _ask("Школа № 5"))
    assert (answer.message.generic, list(answer.message.regions)) == (True, [])
    assert upstream.asked == []
    assert await _spent(session) == 0


async def test_a_short_query_is_refused_on_its_field_and_not_counted(v2, session, upstream) -> None:
    v1 = await v2.http.get(V1, params={"q": "шк"})
    answer = await v2.both(METHOD, _ask(" шк "))
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "INVALID_ARGUMENT",
        "VALIDATION_FAILED",
    )
    assert answer.violations == [("query", schools_service.QUERY_TOO_SHORT)]
    assert answer.error == v1.json()["detail"] == schools_service.QUERY_TOO_SHORT
    assert v1.status_code == 422
    assert await _attempts(session) == 0
    assert upstream.asked == []


async def test_v1_and_v2_draw_on_one_budget(v2, session, upstream) -> None:
    """Alternating versions does not double a caller's twenty searches, and a
    search that finds its answer counts as much as one that does not."""
    for attempt in range(directory_limiter.limit):
        if attempt % 2:
            assert (await v2.http.get(V1, params={"q": "школа № 5"})).json()["generic"] is True
        else:
            assert (await v2.rest(METHOD, _ask("школа № 5"))).message.generic is True

    # Each transport on its own, not `both`: the seconds left are counted at
    # each call, and two calls a millisecond apart may straddle a second.
    rest = await v2.rest(METHOD, _ask("школа № 5"))
    connect = await v2.connect(METHOD, _ask("школа № 5"))
    for refused in (rest, connect):
        assert (refused.code, refused.reason) == ("RESOURCE_EXHAUSTED", "THROTTLED")
        assert refused.error == wording.DIRECTORY_THROTTLED_DETAIL
        seconds = int(refused.metadata["retry_after_seconds"])
        assert seconds >= 1 and refused.retry_seconds == seconds
    assert rest.status == 429
    assert rest.headers["retry-after"] == rest.metadata["retry_after_seconds"]
    assert (await v2.http.get(V1, params={"q": "школа № 5"})).status_code == 429
    assert await _attempts(session) == directory_limiter.limit


async def test_without_a_key_the_search_is_disabled_in_v1_s_words(v2, session, monkeypatch) -> None:
    monkeypatch.setattr(get_settings(), "dadata_token", "")
    v1 = await v2.http.get(V1, params={"q": "лицей 1535"})
    # A name too common to place is refused too: the key is asked about first.
    answer = await v2.both(METHOD, _ask("школа № 5"))
    assert (answer.status, answer.code, answer.reason) == (503, "UNAVAILABLE", "DIRECTORY_DISABLED")
    assert answer.metadata == {}
    assert answer.error == v1.json()["detail"] == wording.DIRECTORY_DISABLED_DETAIL
    assert v1.headers["X-Directory-Unavailable"] == "disabled"
    # Refused after it was counted, and it stays counted (decision 4).
    assert await _attempts(session) == 3
    assert await _spent(session) == 0


async def test_a_spent_share_says_until_when(v2, session, upstream, monkeypatch) -> None:
    monkeypatch.setattr(quota, "ANONYMOUS_DAILY_UNITS", 2)
    upstream.rows = LYCEUM
    assert (await v2.both(METHOD, _ask("лицей 1535"))).status == 200
    assert await _spent(session) == 2

    v1 = await v2.http.get(V1, params={"q": "лицей 1535"})
    longest = quota.seconds_to_reset()
    rest = await v2.rest(METHOD, _ask("лицей 1535"))
    connect = await v2.connect(METHOD, _ask("лицей 1535"))
    shortest = quota.seconds_to_reset()
    for refused in (rest, connect):
        assert (refused.code, refused.reason) == ("RESOURCE_EXHAUSTED", "DIRECTORY_SPENT")
        assert refused.error == v1.json()["detail"] == wording.DIRECTORY_SPENT_DETAIL
        seconds = int(refused.metadata["retry_after_seconds"])
        # Until Moscow midnight, when the next day's share begins.
        assert shortest <= seconds <= longest
        assert refused.retry_seconds == seconds
    # 429 where v1 sent 503: errors.proto files DIRECTORY_SPENT under
    # RESOURCE_EXHAUSTED.
    assert (rest.status, v1.status_code) == (429, 503)
    assert rest.headers["retry-after"] == rest.metadata["retry_after_seconds"]
    assert len(upstream.asked) == 2
    assert await _spent(session) == 2


async def test_a_failing_directory_is_unavailable_and_what_it_counted_stays(
    v2, session, upstream
) -> None:
    """Down, refusing the key or its own allowance, or answering something
    new: one «not now» in v1's words, never the bot's «вручную». The unit was
    spent before the request went out, and the call's refusal takes back
    neither it nor the attempt (decision 4)."""
    for failure in (
        dadata.UpstreamUnavailable(),
        dadata.QuotaExceeded(),
        dadata.UnexpectedResponse(),
    ):
        upstream.failure = failure
        answer = await v2.both(METHOD, _ask("лицей 1535"))
        assert (answer.status, answer.code, answer.reason) == (
            503,
            "UNAVAILABLE",
            "DIRECTORY_UNAVAILABLE",
        ), failure
        assert answer.metadata == {}
        assert answer.error == wording.DIRECTORY_UPSTREAM_DETAIL
        assert "вручную" not in answer.error
    v1 = await v2.http.get(V1, params={"q": "лицей 1535"})
    assert v1.json()["detail"] == wording.DIRECTORY_UPSTREAM_DETAIL
    assert v1.headers["X-Directory-Unavailable"] == "upstream"
    assert (await _attempts(session), await _spent(session)) == (7, 7)


async def test_a_search_writes_only_what_it_counts(
    v2, upstream, statement_writes, unexpected_writes
) -> None:
    upstream.rows = LYCEUM
    with statement_writes() as seen:
        answer = await v2.both(METHOD, _ask("лицей 1535"))
    assert answer.status == 200
    # The attempt counted and the unit spent, on each call, and nothing else:
    # an anonymous call has no phone to have seen.
    assert seen
    assert unexpected_writes(seen) == []
    assert all("join_attempts" in s or "usage_counters" in s for s in seen), seen
