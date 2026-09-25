"""The anonymous school directory: ``GET /api/v1/directory/school-regions``.

What a phone with no class asks on its first screen — «which regions is a
school of this name in» — and everything that stands between that question and
somebody else's allowance:

- the answer is the directory's twenty rows grouped by the region catalog's
  keys, best-ranked region first, placed by the KLADR code before the name;
- a name too common to place is said to be, and «школа № 5» costs no upstream
  request at all;
- every call past validation counts against its caller, twenty in a quarter
  of an hour, on the table `/join` counts in and with `/join`'s window;
- every upstream request is spent from the anonymous daily share, the
  empty-answer retry included, while the bot's search is not metered here at
  all.

Nothing reaches DaData: the pooled client is replaced by one over
``httpx.MockTransport`` that answers each request with the next body and
keeps what was sent.
"""

from __future__ import annotations

import ast
from pathlib import Path

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import func, select

from app.api import cron, diary, directory, public
from app.config import get_settings
from app.main import app
from app.models import JoinAttempt, UsageCounter
from app.providers.dadata import client as dadata_client
from app.services import quota
from app.services import schools as schools_service

APP = Path(__file__).resolve().parent.parent / "app"
PATH = "/api/v1/directory/school-regions"


def row(
    name: str,
    *,
    ogrn: str,
    city: str | None = None,
    region: str | None = None,
    kladr: str | None = None,
) -> dict:
    """One ``suggest/party`` suggestion, carrying only what the mapper reads."""
    address: dict = {}
    if city:
        address["city"] = city
    if region:
        address["region_with_type"] = region
    if kladr:
        address["region_kladr_id"] = kladr
    return {
        "value": name,
        "data": {
            "ogrn": ogrn,
            "okved": "85.14",
            "name": {"short_with_opf": name},
            "state": {"status": "ACTIVE"},
            "address": {"value": city or "", "data": address},
        },
    }


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest.fixture
def upstream(monkeypatch):
    """A configured directory answering with ``answer(...)``'s bodies in turn.

    ``sent`` is every request that went out, because how many went out is
    what the allowance is counted in.
    """
    monkeypatch.setattr(get_settings(), "dadata_token", "<redacted>")
    sent: list[httpx.Request] = []
    bodies: list[object] = [{"suggestions": []}]

    def handler(request: httpx.Request) -> httpx.Response:
        sent.append(request)
        body = bodies[min(len(sent) - 1, len(bodies) - 1)]
        if isinstance(body, httpx.Response):
            return body
        return httpx.Response(200, json=body)

    fake = httpx.AsyncClient(
        base_url=dadata_client.BASE_URL, transport=httpx.MockTransport(handler)
    )

    async def shared():
        return fake

    monkeypatch.setattr(dadata_client, "shared_client", shared)

    class Upstream:
        requests = sent

        @staticmethod
        def answer(*answers: object) -> None:
            bodies[:] = [
                a if isinstance(a, httpx.Response) else {"suggestions": a} for a in answers
            ]

    return Upstream


async def _attempts(session) -> int:
    return await session.scalar(select(func.count()).select_from(JoinAttempt)) or 0


async def _spent_today(session) -> int:
    return await quota.used(session, quota.DADATA_ANONYMOUS, quota.quota_day())


# ---- the answer -------------------------------------------------------------


async def test_a_distinctive_name_is_grouped_by_region_best_first(client, upstream):
    upstream.answer(
        [
            row('ГБОУ "ЛИЦЕЙ № 1535"', ogrn="1", city="Москва", kladr="7700000000000"),
            row('МБОУ "ЛИЦЕЙ № 1535"', ogrn="2", city="Казань", kladr="1600000000000"),
            row('ГБОУ "ЛИЦЕЙ № 1535" КОРПУС 2', ogrn="3", city="Москва", kladr="7700000000000"),
            row('МАОУ "ЛИЦЕЙ № 1535"', ogrn="4", city="Набережные Челны", kladr="1600000000000"),
            row('МБОУ "ЛИЦЕЙ № 1535 Б"', ogrn="5", city="Елабуга", kladr="1600000000000"),
            row('МБОУ "ЛИЦЕЙ № 1535 В"', ogrn="6", city="Альметьевск", kladr="1600000000000"),
        ]
    )

    response = await client.get(PATH, params={"q": "  лицей   1535 "})

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["query"] == "лицей 1535"
    assert body["truncated"] is False and body["generic"] is False
    # Moscow first because its first hit was ranked first, although Tatarstan
    # has more of them: the upstream's rank is what says which school is meant.
    assert [(r["region"], r["code"], r["schools"]) for r in body["regions"]] == [
        ("moscow", "77", 2),
        ("tatarstan", "16", 4),
    ]
    moscow, tatarstan = body["regions"]
    assert moscow["cities"] == ["Москва"]
    assert moscow["examples"] == ['ГБОУ "Лицей № 1535"', 'ГБОУ "Лицей № 1535" корпус 2']
    # Three towns and three names at most, in rank order: a hint, not a list.
    assert tatarstan["cities"] == ["Казань", "Набережные Челны", "Елабуга"]
    assert len(tatarstan["examples"]) == 3
    # A placed region is named by the catalog, in the reader's language.
    assert moscow["label"] is None and tatarstan["label"] is None
    assert len(upstream.requests) == 1


async def test_the_region_comes_from_the_kladr_code(client, upstream):
    """The code has one spelling and the name several, so the code wins —
    even over a name the catalog would have read as somewhere else."""
    upstream.answer(
        [row('МБОУ "ЛИЦЕЙ № 1535"', ogrn="1", region="Респ Башкортостан", kladr="1600000000000")]
    )

    body = (await client.get(PATH, params={"q": "лицей 1535"})).json()

    assert [r["region"] for r in body["regions"]] == ["tatarstan"]


async def test_the_region_falls_back_to_its_name_without_a_code(client, upstream):
    """Whether party rows carry ``region_kladr_id`` at all is unverified, so
    the register's own name for the region is the second way in."""
    upstream.answer(
        [
            row('МБОУ "ЛИЦЕЙ № 1535"', ogrn="1", region="Респ Татарстан"),
            row('МБОУ "ЛИЦЕЙ № 1535 А"', ogrn="2", region="Респ Татарстан", kladr="ab00000000000"),
        ]
    )

    body = (await client.get(PATH, params={"q": "лицей 1535"})).json()

    assert [(r["region"], r["code"], r["schools"]) for r in body["regions"]] == [
        ("tatarstan", "16", 2)
    ]


async def test_a_region_the_catalog_cannot_place_comes_back_with_its_label(client, upstream):
    """Baikonur is leased, not a subject: it has a KLADR code (99) and no
    place in the catalog. Said with the register's own words rather than
    dropped or forced into the nearest name."""
    upstream.answer(
        [
            row('МБОУ "ШКОЛА № 245"', ogrn="1", city="Байконур", region="г Байконур",
                kladr="9900000000000"),
            row('МБОУ "ШКОЛА № 245"', ogrn="2", city="Казань", kladr="1600000000000"),
            # Nothing at all about where: no answer to «which region», left out.
            row('АНО "ШКОЛА № 245"', ogrn="3"),
        ]
    )

    body = (await client.get(PATH, params={"q": "школа 245"})).json()

    assert body["regions"] == [
        {
            "region": None,
            "code": "99",
            "label": "г Байконур",
            "schools": 1,
            "cities": ["Байконур"],
            "examples": ['МБОУ "Школа № 245"'],
        },
        {
            "region": "tatarstan",
            "code": "16",
            "label": None,
            "schools": 1,
            "cities": ["Казань"],
            "examples": ['МБОУ "Школа № 245"'],
        },
    ]


async def test_a_branch_in_another_region_is_not_folded_into_its_head(client, upstream):
    """A филиал is registered under its head school's OGRN. The bot's picker
    has always shown the two as one, and still does; the directory's question
    is about regions, and there are two of them."""
    head = row('ГБОУ "ШКОЛА № 1580"', ogrn="1027700000001", city="Москва", kladr="7700000000000")
    branch = row(
        'ФИЛИАЛ ГБОУ "ШКОЛА № 1580"', ogrn="1027700000001", city="Химки", kladr="5000000000000"
    )
    upstream.answer([head, branch])

    body = (await client.get(PATH, params={"q": "школа 1580"})).json()
    assert [r["region"] for r in body["regions"]] == ["moscow", "moscow-oblast"]

    found = await schools_service.search("школа 1580")
    assert len(found.schools) == 1


async def test_a_type_word_and_a_short_number_is_answered_without_dadata(
    client, upstream, session
):
    for query in ["Школа № 5", "СОШ 12", "гимназия 3", "МБОУ СОШ №7", "лицей N 2"]:
        response = await client.get(PATH, params={"q": query})
        assert response.status_code == 200, query
        assert response.json()["generic"] is True, query
        assert response.json()["regions"] == []

    assert upstream.requests == []
    assert await _spent_today(session) == 0

    # And a name that does tell schools apart is asked.
    upstream.answer([row('ГБОУ "ЛИЦЕЙ № 1535"', ogrn="1", kladr="7700000000000")])
    for query in ["лицей 1535", "гимназия 1 Казань"]:
        assert (await client.get(PATH, params={"q": query})).json()["generic"] is False
    assert len(upstream.requests) == 2


async def test_twenty_hits_across_many_regions_is_generic(client, upstream):
    codes = ["77", "78", "16", "50", "66"]
    upstream.answer(
        [
            row(f'МБОУ "ГИМНАЗИЯ № 1{i}"', ogrn=str(i), kladr=f"{codes[i % 5]}00000000000")
            for i in range(20)
        ]
    )

    body = (await client.get(PATH, params={"q": "гимназия 1 имени"})).json()

    assert body["truncated"] is True
    assert body["generic"] is True
    # Still said, for a phone that wants to offer them: generic is on top of
    # the list, not instead of it.
    assert len(body["regions"]) == 5


async def test_fewer_than_twenty_hits_in_many_regions_is_a_real_answer(client, upstream):
    """Nineteen rows is everything the register has, so five regions of it are
    five places the school really is."""
    codes = ["77", "78", "16", "50", "66"]
    upstream.answer(
        [row(f'МБОУ "ЛИЦЕЙ № 1{i}"', ogrn=str(i), kladr=f"{codes[i % 5]}00000000000")
         for i in range(19)]
    )

    body = (await client.get(PATH, params={"q": "лицей имени"})).json()

    assert body["truncated"] is False
    assert body["generic"] is False


async def test_at_most_ten_regions_are_named(client, upstream):
    codes = [f"{n:02d}" for n in range(1, 21)]
    upstream.answer(
        [row(f'МБОУ "ЛИЦЕЙ № {i}"', ogrn=str(i), kladr=f"{codes[i]}00000000000") for i in range(20)]
    )

    body = (await client.get(PATH, params={"q": "лицей имени"})).json()

    assert len(body["regions"]) == schools_service.REGION_GROUPS_MAX == 10
    assert [r["code"] for r in body["regions"]] == codes[:10]


# ---- what it refuses, and what it counts ------------------------------------


async def test_a_short_query_is_422_and_spends_nothing(client, upstream, session):
    for params in [{"q": "шк"}, {"q": "   "}, {}]:
        response = await client.get(PATH, params=params)
        assert response.status_code == 422
        assert "3 символа" in response.json()["detail"]

    assert upstream.requests == []
    assert await _attempts(session) == 0
    assert await _spent_today(session) == 0


async def test_every_call_counts_against_the_caller(client, upstream, session, monkeypatch):
    """A search that finds its school spent the same upstream request as one
    that finds nothing, so success is counted too — unlike `/join`, where a
    classroom's successes must never lock the classroom out."""
    upstream.answer([row('ГБОУ "ЛИЦЕЙ № 1535"', ogrn="1", kladr="7700000000000")])
    for _ in range(10):
        assert (await client.get(PATH, params={"q": "лицей 1535"})).status_code == 200
    for _ in range(5):
        assert (await client.get(PATH, params={"q": "школа 5"})).status_code == 200
    # A refusal past validation is a call too.
    monkeypatch.setattr(get_settings(), "dadata_token", "")
    for _ in range(5):
        assert (await client.get(PATH, params={"q": "лицей 1535"})).status_code == 503

    assert await _attempts(session) == 20
    refused = await client.get(PATH, params={"q": "лицей 1535"})
    assert refused.status_code == 429
    assert 0 < int(refused.headers["Retry-After"]) <= 901
    # Refused at the door: not counted again, and nothing went upstream.
    assert await _attempts(session) == 20
    assert len(upstream.requests) == 10

    # Its own bucket: twenty searches have not spent a phone's join attempts.
    joined = await client.post("/api/v1/join", json={"code": "NOSUCH12"})
    assert joined.status_code == 404


async def test_the_daily_allowance_stops_the_search_and_says_until_when(
    client, upstream, session, monkeypatch
):
    monkeypatch.setattr(quota, "ANONYMOUS_DAILY_UNITS", 2)
    upstream.answer([row('ГБОУ "ЛИЦЕЙ № 1535"', ogrn="1", kladr="7700000000000")])
    for _ in range(2):
        assert (await client.get(PATH, params={"q": "лицей 1535"})).status_code == 200
    assert await _spent_today(session) == 2

    longest = quota.seconds_to_reset()
    response = await client.get(PATH, params={"q": "лицей 1535"})
    shortest = quota.seconds_to_reset()

    assert response.status_code == 503
    assert response.headers["X-Directory-Unavailable"] == "spent"
    assert "на сегодня" in response.json()["detail"]
    assert "выберите регион" in response.json()["detail"]
    # Until Moscow midnight, when the next day's share begins.
    assert shortest <= int(response.headers["Retry-After"]) <= longest
    assert len(upstream.requests) == 2
    assert await _spent_today(session) == 2

    # A generic name never needed the allowance, so it is still answered.
    assert (await client.get(PATH, params={"q": "школа 5"})).json()["generic"] is True


async def test_the_empty_result_retry_is_charged_and_skipped_when_nothing_is_left(
    client, upstream, session, monkeypatch
):
    """The retry without the ОКВЭД filter is a second DaData request, and a
    unit is a request: it is asked for, and not made when it is not there."""
    monkeypatch.setattr(quota, "ANONYMOUS_DAILY_UNITS", 3)
    upstream.answer([], [row('МБОУ "ЛИЦЕЙ № 1535"', ogrn="1", kladr="1600000000000")])

    first = await client.get(PATH, params={"q": "лицей 1535"})
    assert [r["region"] for r in first.json()["regions"]] == ["tatarstan"]
    assert len(upstream.requests) == 2
    assert await _spent_today(session) == 2

    # One unit left: the first request has it, the retry does not happen, and
    # the answer is an honest empty one rather than an error.
    upstream.answer([])
    second = await client.get(PATH, params={"q": "лицей 1535"})
    assert second.status_code == 200
    assert second.json()["regions"] == []
    assert len(upstream.requests) == 3
    assert await _spent_today(session) == 3


async def test_the_bots_search_is_not_charged_to_the_anonymous_allowance(upstream, session):
    """The bot and `/manage/schools` are admins creating a class — the ones
    the anonymous cap exists to protect. Their search is exactly what it was:
    unmetered here, retrying on an empty answer as it always has."""
    upstream.answer([], [row('МБОУ "ЛИЦЕЙ № 1535"', ogrn="1", kladr="1600000000000")])

    found = await schools_service.search("лицей 1535")

    assert [school.region_code for school in found.schools] == ["16"]
    assert len(upstream.requests) == 2
    assert await session.scalar(select(func.count()).select_from(UsageCounter)) == 0


async def test_a_spend_past_the_cap_changes_nothing(session):
    day = quota.quota_day()

    taken = [await quota.spend(session, "test:scope", 1, cap=2, day=day) for _ in range(4)]

    assert taken == [True, True, False, False]
    assert await quota.used(session, "test:scope", day) == 2
    # A spend that would cross the cap is refused whole, not in part.
    assert await quota.spend(session, "test:other", 1, cap=2, day=day)
    assert not await quota.spend(session, "test:other", 2, cap=2, day=day)
    assert await quota.used(session, "test:other", day) == 1


async def test_without_a_dadata_key_it_is_503(client, monkeypatch, session):
    monkeypatch.setattr(get_settings(), "dadata_token", "   ")

    response = await client.get(PATH, params={"q": "лицей 1535"})

    assert response.status_code == 503
    assert response.headers["X-Directory-Unavailable"] == "disabled"
    assert response.json()["detail"] == directory.DISABLED_DETAIL
    assert "Retry-After" not in response.headers
    assert await _spent_today(session) == 0


@pytest.mark.parametrize(
    "answer",
    [
        httpx.Response(503),
        httpx.Response(429, json={"message": "Daily limit exceeded"}),
        httpx.Response(200, text="<html>maintenance</html>"),
    ],
    ids=["down", "dadata-quota", "unreadable"],
)
async def test_a_directory_failure_is_503_and_offers_the_list(client, upstream, answer):
    """The bot's sentences for these end «введите название вручную», which is
    the bot's way out; the phone's is the region list."""
    upstream.answer(answer)

    response = await client.get(PATH, params={"q": "лицей 1535"})

    assert response.status_code == 503
    assert response.headers["X-Directory-Unavailable"] == "upstream"
    assert response.json()["detail"] == directory.UPSTREAM_DETAIL
    assert "вручную" not in response.text


# ---- the table it shares -----------------------------------------------------


def _throttles_built_in_app() -> list[tuple[str, ast.Call]]:
    """Every ``JoinThrottle(...)`` written anywhere under ``app/``."""
    found = []
    for path in sorted(APP.rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call):
                continue
            name = getattr(node.func, "id", None) or getattr(node.func, "attr", None)
            if name == "JoinThrottle":
                found.append((str(path.relative_to(APP)), node))
    return found


def test_every_throttle_on_the_attempts_table_uses_one_window():
    """Each recorded attempt prunes the **whole** table to its own limiter's
    window, and the cron sweeps it to an hour. A limiter with a longer window
    than the others would have its history silently cut to theirs; one with a
    shorter window would cut theirs. So there is one window, and it is here.

    Found by reading the source rather than by listing the three known
    limiters, so a fourth cannot arrive without being asked this.
    """
    built = _throttles_built_in_app()
    assert sorted(path for path, _ in built) == [
        "api/diary.py",
        "api/directory.py",
        "api/public.py",
    ]

    for path, call in built:
        window = next((k.value for k in call.keywords if k.arg == "window"), None)
        if window is None and len(call.args) >= 2:
            window = call.args[1]
        assert isinstance(window, ast.Constant), path
        assert float(window.value) == 900.0, path

    live = [public.join_limiter, diary.diary_login_limiter, directory.directory_limiter]
    assert {limiter.window for limiter in live} == {900.0}
    assert cron.JOIN_ATTEMPT_TTL.total_seconds() >= 900.0
    assert directory.directory_limiter.limit == 20
