"""The anonymous directory's door, in ``services/``: what v1's router held.

v1's ``GET /directory/school-regions`` held the order of its checks in its
router; v2's ``ListSchoolRegions`` asks the same question, so the order moved
to ``directory.school_regions`` before v2's handler was written, with v1
calling it (``docs/specs/2026-10-05-server-v2-design.md``, decisions 2 and 11).
``test_directory.py``, untouched, is the proof that v1's answers did not move;
these hold the order a fact at a time, and the sentences both versions answer
with.

Nothing reaches DaData: ``dadata.suggest_schools`` is replaced, and keeps the
queries it was asked.
"""

from __future__ import annotations

from typing import Any

import pytest
from sqlalchemy import func, select

from app import wording
from app.config import get_settings
from app.models import JoinAttempt
from app.providers import dadata
from app.security import directory_limiter
from app.services import directory as directory_service
from app.services import quota
from app.services import schools as schools_service

#: One caller's bucket, as a shell would hand it in.
KEY = "test-directory-caller"

#: One school in Moscow, as DaData's ``suggest/party`` writes it.
MOSCOW = {
    "value": 'ГБОУ "ЛИЦЕЙ № 1535"',
    "data": {
        "ogrn": "1",
        "name": {"short_with_opf": 'ГБОУ "ЛИЦЕЙ № 1535"'},
        "state": {"status": "ACTIVE"},
        "address": {"value": "Москва", "data": {"region_kladr_id": "7700000000000"}},
    },
}


@pytest.fixture
def asked(monkeypatch) -> list[str]:
    """A configured directory that finds the one school in Moscow; the queries
    it was asked, in order."""
    monkeypatch.setattr(get_settings(), "dadata_token", "<redacted>")
    queries: list[str] = []

    async def suggest(query: str, *, region: str | None = None, may_retry: Any = None):
        queries.append(query)
        return [MOSCOW]

    monkeypatch.setattr(dadata, "suggest_schools", suggest)
    return queries


async def _attempts(session) -> int:
    return await session.scalar(select(func.count()).select_from(JoinAttempt)) or 0


async def _spent(session) -> int:
    return await quota.used(session, quota.DADATA_ANONYMOUS, quota.quota_day())


async def test_a_short_query_is_handed_back_and_any_other_stays_counted(session, asked) -> None:
    with pytest.raises(schools_service.SearchError) as short:
        await directory_service.school_regions(session, " шк ", client_key=KEY)
    assert str(short.value) == schools_service.QUERY_TOO_SHORT
    assert await _attempts(session) == 0

    found = await directory_service.school_regions(session, "лицей  1535", client_key=KEY)
    assert found.query == "лицей 1535"
    assert [group.region for group in found.groups] == ["moscow"]
    assert asked == ["лицей 1535"]
    assert (await _attempts(session), await _spent(session)) == (1, 1)


async def test_a_caller_over_the_limit_is_refused_before_the_query_is_read(session, asked) -> None:
    for _ in range(directory_limiter.limit):
        found = await directory_service.school_regions(session, "школа № 5", client_key=KEY)
        assert found.generic is True
    with pytest.raises(directory_service.DirectoryThrottled) as throttled:
        # Too short as well: the limit is asked first, so this is no SearchError.
        await directory_service.school_regions(session, "шк", client_key=KEY)
    assert throttled.value.seconds >= 1
    # A name too common to place asked nothing and spent nothing.
    assert asked == []
    assert await _spent(session) == 0
    # Another caller's budget is its own.
    other = await directory_service.school_regions(session, "школа № 5", client_key="another")
    assert other.generic is True


async def test_without_a_key_even_a_common_name_is_disabled_and_stays_counted(
    session, monkeypatch
) -> None:
    """The key is asked about before the name is: v1 answered «не настроен»
    rather than «too common to place» on a deployment that has no directory."""
    monkeypatch.setattr(get_settings(), "dadata_token", "")
    with pytest.raises(directory_service.DirectoryDisabled):
        await directory_service.school_regions(session, "школа № 5", client_key=KEY)
    assert (await _attempts(session), await _spent(session)) == (1, 0)


async def test_a_failing_directory_is_one_fact_and_its_unit_stays_spent(
    session, monkeypatch
) -> None:
    monkeypatch.setattr(get_settings(), "dadata_token", "<redacted>")
    failures = [dadata.UpstreamUnavailable(), dadata.QuotaExceeded(), dadata.UnexpectedResponse()]

    async def fail(query: str, *, region: str | None = None, may_retry: Any = None):
        raise failures.pop(0)

    monkeypatch.setattr(dadata, "suggest_schools", fail)
    for _ in range(3):
        with pytest.raises(directory_service.DirectoryUnavailable):
            await directory_service.school_regions(session, "лицей 1535", client_key=KEY)
    assert failures == []
    assert (await _attempts(session), await _spent(session)) == (3, 3)


def test_the_directory_s_sentences_are_v1_s() -> None:
    assert wording.DIRECTORY_THROTTLED_DETAIL == (
        "Слишком много поисков подряд. Выберите регион из списка или попробуйте позже."
    )
    assert wording.DIRECTORY_DISABLED_DETAIL == (
        "Поиск школ не настроен — выберите регион из списка"
    )
    assert wording.DIRECTORY_SPENT_DETAIL == (
        "Поиск школ на сегодня исчерпан — выберите регион из списка"
    )
    assert wording.DIRECTORY_UPSTREAM_DETAIL == (
        "Поиск школ сейчас недоступен — выберите регион из списка"
    )
    assert schools_service.QUERY_TOO_SHORT == "Введите хотя бы 3 символа названия школы"
    # Written inline in v1's router until they moved: its region hint's
    # ceiling, and the scope of its bucket.
    assert schools_service.MAX_REGION == 120
    assert directory_service.BUCKET_SCOPE == "directory:"
