"""``DirectoryService.ListSchools``: the schools matching a name, for an admin naming the class's.

v1's ``GET /manage/schools`` over v2, through the same ``schools.search``: one
upstream search a call, whatever the page, because the directory has no
offset. What v2 changes is the paging: AIP-158's ``page_size`` and an opaque
``page_token`` naming the first school of the next page, where v1 took a page
number (the 3b plan, «Rulings for 3b-3»). The refusals are v1's, in v1's
words: the provider's own sentence when the directory is not configured or
fails, which ends «введите название вручную», because an admin can type the
name. A read writes nothing (``docs/specs/2026-10-05-server-v2-design.md``,
decision 10).

Nothing reaches DaData: ``dadata.suggest_schools`` is replaced, and keeps what
it was asked.
"""

from __future__ import annotations

import ast
import base64
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import pytest

from app.config import get_settings
from app.contract.lessons.v2.directory_pb import ListSchoolsRequest
from app.providers import dadata
from app.rpc.directory import PAGE_SIZE_REFUSED, PAGE_TOKEN_REFUSED, REGION_REFUSED
from app.services import schools as schools_service

METHOD = "DirectoryService/ListSchools"
V1 = "/api/v1/manage/schools"
CLIENT = Path(__file__).resolve().parents[1] / "app" / "providers" / "dadata" / "client.py"

#: Every exception of the directory's, by the name ``client.py`` raises it under.
DIRECTORY_ERRORS = {
    "DirectoryError",
    "NotConfigured",
    "UpstreamUnavailable",
    "QuotaExceeded",
    "UnexpectedResponse",
}


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _row(index: int) -> dict[str, Any]:
    """One suggestion of DaData's ``suggest/party``: a gymnasium in Perm, every
    second one closed by the register."""
    name = f'МБОУ "ГИМНАЗИЯ № {index}"'
    return {
        "value": name,
        "data": {
            "ogrn": f"102780000{index:04d}",
            "inn": f"59000{index:05d}",
            "name": {"short_with_opf": name, "full_with_opf": f"УЧРЕЖДЕНИЕ {name}"},
            "state": {"status": "ACTIVE" if index % 2 else "LIQUIDATED"},
            "address": {
                "value": f"г Пермь, ул Ленина, д {index}",
                "data": {"city": "Пермь", "region_with_type": "Пермский край"},
            },
        },
    }


def _rows(count: int) -> list[dict[str, Any]]:
    return [_row(index) for index in range(1, count + 1)]


def _ogrns(count: int) -> list[str]:
    return [f"102780000{index:04d}" for index in range(1, count + 1)]


@dataclass
class _Upstream:
    """A configured directory: what it answers, what it fails with, and the
    (query, region) of every search it was asked."""

    rows: list[dict[str, Any]] = field(default_factory=list)
    failure: Exception | None = None
    asked: list[tuple[str, str | None]] = field(default_factory=list)


@pytest.fixture
def upstream(monkeypatch) -> _Upstream:
    directory = _Upstream()
    monkeypatch.setattr(get_settings(), "dadata_token", "<redacted>")

    async def suggest(query: str, *, region: str | None = None, may_retry: Any = None):
        directory.asked.append((query, region))
        if directory.failure is not None:
            raise directory.failure
        return directory.rows

    monkeypatch.setattr(dadata, "suggest_schools", suggest)
    return directory


def _plain(school: Any) -> dict[str, Any]:
    """A v2 school as v1's ``SchoolOut`` writes it."""
    optional = ("ogrn", "inn", "address", "city", "region")
    return {
        "name": school.name,
        "full_name": school.full_name,
        **{key: getattr(school, key) if school.has_field(key) else None for key in optional},
        "active": school.active,
    }


def _token(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


async def test_the_schools_are_v1_s_from_one_search(v2, v2_tokens, upstream) -> None:
    upstream.rows = _rows(7)
    admin = v2_tokens["admin"]
    request = ListSchoolsRequest(query=" гимназия  3 ", region="Пермский край", page_size=20)
    answer = await v2.both(METHOD, request, token=admin)
    v1 = await v2.http.get(
        V1,
        params={"q": " гимназия  3 ", "region": "Пермский край", "page_size": 20},
        headers=_auth(admin),
    )
    message = answer.message
    assert [_plain(school) for school in message.schools] == v1.json()["items"]
    assert (message.total_size, message.truncated) == (v1.json()["total"], v1.json()["truncated"])
    assert (message.total_size, message.next_page_token) == (7, "")
    # A school the register has closed is shown, not hidden.
    assert [school.active for school in message.schools] == [i % 2 == 1 for i in range(1, 8)]
    # One search a call, and the region a hint passed on as it was sent.
    assert upstream.asked == [("гимназия 3", "Пермский край")] * 3


async def test_the_pages_walk_one_answer_and_each_asks_again(v2, v2_tokens, upstream) -> None:
    """Twelve schools, five to a page: three pages, the last of two, each a
    search of its own, and nothing repeated or skipped."""
    upstream.rows = _rows(12)
    admin = v2_tokens["admin"]
    first = await v2.both(METHOD, ListSchoolsRequest(query="гимназия", page_size=5), token=admin)
    seen = [school.ogrn for school in first.message.schools]
    token = first.message.next_page_token
    for expected in (5, 2):
        page = await v2.rest(
            METHOD, ListSchoolsRequest(query="гимназия", page_size=5, page_token=token), token=admin
        )
        assert (len(page.message.schools), page.message.total_size) == (expected, 12)
        seen += [school.ogrn for school in page.message.schools]
        token = page.message.next_page_token
    assert token == ""
    assert seen == _ogrns(12)
    assert len(upstream.asked) == 4
    # A page size changed between calls starts where the last page ended.
    wider = await v2.rest(
        METHOD,
        ListSchoolsRequest(
            query="гимназия", page_size=20, page_token=first.message.next_page_token
        ),
        token=admin,
    )
    assert [school.ogrn for school in wider.message.schools] == _ogrns(12)[5:]
    assert wider.message.next_page_token == ""


async def test_the_page_size_is_five_unset_twenty_at_most_and_never_negative(
    v2, v2_tokens, upstream
) -> None:
    upstream.rows = _rows(7)
    admin = v2_tokens["admin"]
    unset = await v2.both(METHOD, ListSchoolsRequest(query="гимназия"), token=admin)
    assert len(unset.message.schools) == schools_service.PAGE_SIZE == 5
    assert unset.message.next_page_token != ""
    # Read as the most a page holds, as AIP-158 has it, where v1 refused it.
    large = await v2.both(METHOD, ListSchoolsRequest(query="гимназия", page_size=50), token=admin)
    assert (len(large.message.schools), large.message.next_page_token) == (7, "")
    searched = len(upstream.asked)
    negative = await v2.both(
        METHOD, ListSchoolsRequest(query="гимназия", page_size=-1), token=admin
    )
    assert (negative.status, negative.reason) == (400, "VALIDATION_FAILED")
    assert negative.violations == [("page_size", PAGE_SIZE_REFUSED)]
    assert len(upstream.asked) == searched


async def test_a_token_this_list_did_not_hand_out_is_refused_without_repeating_it(
    v2, v2_tokens, upstream
) -> None:
    upstream.rows = _rows(7)
    for token in (
        "not a token!",
        _token(b"audit:5"),  # another list's
        _token(b"schools:0"),  # the first page has no token
        _token(b"schools:05"),  # never written so
        _token(b"schools:20"),  # no search finds a twenty-first school
        _token(b"schools:-1"),
        "A" * 64,
    ):
        answer = await v2.both(
            METHOD,
            ListSchoolsRequest(query="гимназия", page_token=token),
            token=v2_tokens["admin"],
        )
        assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED"), token
        assert answer.violations == [("page_token", PAGE_TOKEN_REFUSED)]
        assert token not in (answer.error or "")
    assert upstream.asked == []


async def test_a_token_past_what_the_search_finds_now_is_an_empty_last_page(
    v2, v2_tokens, upstream
) -> None:
    """The directory answers each call afresh and may find fewer schools than
    it did a page ago: the page after them is empty and the last, rather than
    a refusal of a token this list did hand out."""
    upstream.rows = _rows(12)
    admin = v2_tokens["admin"]
    first = await v2.rest(METHOD, ListSchoolsRequest(query="гимназия", page_size=10), token=admin)
    upstream.rows = _rows(7)
    after = await v2.both(
        METHOD,
        ListSchoolsRequest(
            query="гимназия", page_size=10, page_token=first.message.next_page_token
        ),
        token=admin,
    )
    assert after.status == 200
    assert (list(after.message.schools), after.message.next_page_token) == ([], "")
    assert after.message.total_size == 7


async def test_a_region_longer_than_v1_takes_is_refused_on_its_field(
    v2, v2_tokens, upstream
) -> None:
    admin = v2_tokens["admin"]
    region = "Пермский край " * 10
    v1 = await v2.http.get(V1, params={"q": "гимназия", "region": region}, headers=_auth(admin))
    answer = await v2.both(METHOD, ListSchoolsRequest(query="гимназия", region=region), token=admin)
    assert v1.status_code == 422
    assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED")
    assert answer.violations == [("region", REGION_REFUSED)]
    assert upstream.asked == []


async def test_a_short_query_is_refused_on_its_field_in_v1_s_words(v2, v2_tokens, upstream) -> None:
    admin = v2_tokens["admin"]
    v1 = await v2.http.get(V1, params={"q": "шк"}, headers=_auth(admin))
    answer = await v2.both(METHOD, ListSchoolsRequest(query="шк"), token=admin)
    assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED")
    assert answer.violations == [("query", schools_service.QUERY_TOO_SHORT)]
    assert answer.error == v1.json()["detail"] == schools_service.QUERY_TOO_SHORT
    assert upstream.asked == []


async def test_without_a_key_the_search_is_disabled_in_v1_s_words(
    v2, v2_tokens, monkeypatch
) -> None:
    monkeypatch.setattr(get_settings(), "dadata_token", "")
    admin = v2_tokens["admin"]
    v1 = await v2.http.get(V1, params={"q": "гимназия"}, headers=_auth(admin))
    answer = await v2.both(METHOD, ListSchoolsRequest(query="гимназия"), token=admin)
    assert (answer.status, answer.code, answer.reason) == (503, "UNAVAILABLE", "DIRECTORY_DISABLED")
    # The provider's sentence, as v1 and the bot say it: an admin can type the name.
    assert answer.error == v1.json()["detail"] == dadata.NotConfigured.message
    assert "вручную" in answer.error
    assert v1.status_code == 503


async def test_a_failing_directory_is_unavailable_in_v1_s_words(v2, v2_tokens, upstream) -> None:
    admin = v2_tokens["admin"]
    for failure in (
        dadata.UpstreamUnavailable(),
        dadata.QuotaExceeded(),
        dadata.UnexpectedResponse(),
    ):
        upstream.failure = failure
        v1 = await v2.http.get(V1, params={"q": "гимназия"}, headers=_auth(admin))
        answer = await v2.both(METHOD, ListSchoolsRequest(query="гимназия"), token=admin)
        assert (answer.status, answer.code, answer.reason) == (
            503,
            "UNAVAILABLE",
            "DIRECTORY_UNAVAILABLE",
        ), failure
        assert answer.metadata == {}
        assert answer.error == v1.json()["detail"] == failure.message


def test_the_directory_s_sentences_are_its_own_never_what_was_sent() -> None:
    """``ListSchools`` answers a directory that is not configured or fails with
    the provider's own sentence, as v1 and the bot do: the one refusal of 3b-3
    whose message is an exception's text (the 3b plan, «Rulings for 3b-3»). It
    may be, because the client raises each of them with no text or with a
    literal; this holds that, so that a sentence built from what was sent or
    what the directory said cannot reach a client unnoticed."""
    raised = [
        node
        for node in ast.walk(ast.parse(CLIENT.read_text(encoding="utf-8")))
        if isinstance(node, ast.Call) and getattr(node.func, "id", None) in DIRECTORY_ERRORS
    ]
    # Held here rather than trusted: a walk that found nothing would pass.
    assert len(raised) >= 5
    for node in raised:
        assert node.keywords == [], ast.unparse(node)
        assert all(
            isinstance(arg, ast.Constant) and isinstance(arg.value, str) for arg in node.args
        ), ast.unparse(node)


async def test_searching_writes_nothing_but_the_last_seen(
    v2, v2_tokens, upstream, statement_writes, unexpected_writes, last_seen_rule
) -> None:
    upstream.rows = _rows(3)
    with statement_writes() as seen:
        answer = await v2.both(
            METHOD, ListSchoolsRequest(query="гимназия"), token=v2_tokens["admin"]
        )
    assert answer.status == 200
    assert len(answer.message.schools) == 3
    # The phone's last call, and nothing else: the admins' search spends
    # nothing of the anonymous share.
    assert seen
    assert unexpected_writes(seen) == []
    assert all(last_seen_rule.match(statement) for statement in seen), seen
