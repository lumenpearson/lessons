"""How the deployment reaches each diary's server (#406).

``DIARY_EGRESS`` is ``direct``, ``proxy`` or ``relay``, read beside
``DIARY_PROXY_URL`` (docs/specs/2026-10-10-deployment-scenarios-design.md,
«How a scenario is chosen»). Under ``proxy``, Petersburg and the twelve
«Сетевой город» regions that answered from the RUVDS proxy on 11 October 2026
go through it; the other seven stay direct until the relay (#408). Nothing here
reaches the network.
"""

from __future__ import annotations

import json
from urllib.parse import urlsplit

import httpx
import pytest

from app.config import DiaryEgress, Settings, get_settings
from app.crypto import seal
from app.models import DiarySession
from app.providers.diary import http as diary_http
from app.providers.diary import registry
from app.providers.diary.registry import NETSCHOOL, PETERSBURG
from app.providers.netschool import client as nsclient
from app.providers.netschool import regions
from app.security import hash_token, new_token
from app.services import diary_keepalive

PROXY = "http://user:hunter2@proxy.example:3128"
DIRECT, VIA = DiaryEgress.DIRECT, DiaryEgress.PROXY

#: The owner's probe of 11 October 2026: every host of the allow-list asked for
#: its school search through the RUVDS proxy, after Squid's ACL was widened.
ANSWERED = frozenset(
    {
        "asurso.ru",
        "e-school.obr.lenreg.ru",
        "giseo.rkomi.ru",
        "netcity.admsakhalin.ru",
        "netschool.edu22.info",
        "region.zabedu.ru",
        "school.sgo41.ru",
        "sgo.cit73.ru",
        "sgo.e-mordovia.ru",
        "sgo.e-yakutia.ru",
        "sgo.edu-ri.ru",
        "sgo.mari-el.gov.ru",
    }
)
WAF = frozenset(
    {"deti.obr03.ru", "region.obramur.ru", "sgo.prim-edu.ru", "sgo.tomedu.ru", "sgo1.edu71.ru"}
)
SILENT = frozenset({"e-school.ryazan.gov.ru", "sgo.kchgov.ru"})

EVERY_REGION = regions.listed(password_only=False)


@pytest.mark.parametrize(
    ("egress", "proxy", "expected"),
    [
        ("", PROXY, VIA),
        ("", "", DIRECT),
        ("", "ftp://user:x@host:21", DIRECT),
        ("proxy", PROXY, VIA),
        ("proxy", "", DIRECT),
        ("direct", PROXY, DIRECT),
        ("relay", PROXY, DIRECT),
        ("relay", "", DIRECT),
        (" Proxy\n", PROXY, VIA),
        ("sideways", PROXY, VIA),
        ("sideways", "", DIRECT),
    ],
    ids=[
        "production-today",
        "nothing-set",
        "unusable-proxy",
        "proxy",
        "proxy-without-one",
        "direct-beside-one",
        "relay-not-built",
        "relay-without-one",
        "case-and-spacing",
        "unusable-read-as-unset",
        "unusable-without-one",
    ],
)
def test_the_egress_takes_effect_as_the_owner_decided(egress, proxy, expected):
    settings = Settings(DIARY_EGRESS=egress, DIARY_PROXY_URL=proxy)
    assert settings.diary_egress_value is expected


def _announced(settings: Settings) -> list[str]:
    return [line for line in settings.disabled_features() if "DIARY_EGRESS" in line]


def test_production_as_it_stands_announces_nothing_about_the_route():
    """DIARY_PROXY_URL set and DIARY_EGRESS never written: production on 11 October."""
    lines = Settings(DIARY_PROXY_URL=PROXY).disabled_features()
    assert not any("DIARY_EGRESS" in line or "DIARY_PROXY_URL" in line for line in lines)


def test_an_unusable_egress_is_announced_never_repeated_and_read_as_unset():
    settings = Settings(DIARY_PROXY_URL=PROXY, DIARY_EGRESS="hunter2-sideways")
    (line,) = _announced(settings)
    assert "unusable" in line and "the proxy reaches" in line
    assert not any("hunter2" in each for each in settings.disabled_features())
    assert settings.diary_egress_value is VIA


def test_relay_is_accepted_announced_as_not_built_and_goes_direct():
    settings = Settings(DIARY_PROXY_URL=PROXY, DIARY_EGRESS="relay")
    (line,) = _announced(settings)
    assert "#408" in line and "directly" in line
    assert settings.diary_egress_value is DIRECT


def test_proxy_asked_for_with_no_proxy_is_announced_and_goes_direct():
    settings = Settings(DIARY_EGRESS="proxy", DIARY_PROXY_URL="")
    (line,) = _announced(settings)
    assert "DIARY_PROXY_URL is empty" in line
    assert settings.diary_egress_value is DIRECT


def test_direct_beside_a_usable_proxy_is_a_choice_and_announces_nothing():
    assert _announced(Settings(DIARY_PROXY_URL=PROXY, DIARY_EGRESS="direct")) == []


def test_a_proxy_written_in_server_env_reaches_no_test(tmp_path):
    """#419. Settings reads server/.env beside the environment, and the
    environment wins only for a name it holds: conftest holds both empty."""
    env = tmp_path / ".env"
    env.write_text(f"DIARY_PROXY_URL={PROXY}\nDIARY_EGRESS=proxy\n", "utf-8")
    settings = Settings(_env_file=env)
    assert settings.diary_proxy is None
    assert settings.diary_egress_value is DIRECT


def test_the_route_table_is_the_owner_s_probe_of_11_october():
    hosts = {urlsplit(region.origin).hostname: region for region in EVERY_REGION}
    assert set(hosts) == ANSWERED | WAF | SILENT
    assert {host for host, region in hosts.items() if region.via_proxy} == ANSWERED


def test_under_proxy_petersburg_and_the_twelve_go_through_it_and_the_rest_stay_direct():
    settings = Settings(DIARY_PROXY_URL=PROXY)
    assert registry.route(PETERSBURG, None, settings) is VIA
    assert registry.proxy_for(PETERSBURG, None, settings) == PROXY
    for region in EVERY_REGION:
        expected = VIA if region.via_proxy else DIRECT
        assert registry.route(NETSCHOOL, region.key, settings) is expected, region.key
    assert registry.proxy_for(NETSCHOOL, "amur", settings) is None
    # A key off the allow-list is never a host, so never a route through anything.
    assert registry.route(NETSCHOOL, "atlantis", settings) is DIRECT
    assert registry.route("dnevnik-ru", None, settings) is DIRECT


@pytest.mark.parametrize("egress", ["direct", "relay"])
def test_direct_and_relay_send_nothing_through_the_proxy_even_with_one_set(egress):
    settings = Settings(DIARY_PROXY_URL=PROXY, DIARY_EGRESS=egress)
    assert registry.route(PETERSBURG, None, settings) is DIRECT
    assert {registry.route(NETSCHOOL, r.key, settings) for r in EVERY_REGION} == {DIRECT}


# ---- the pool each call goes out through -----------------------------------


@pytest.fixture
def two_pools(monkeypatch) -> dict[str, list[httpx.Request]]:
    """Both «Сетевой город» pools over mock transports, each recording what it
    was asked. Every answer is a region that takes a password."""
    asked: dict[str, list[httpx.Request]] = {"direct": [], "proxy": []}

    def pool(name: str):
        def answer(request: httpx.Request) -> httpx.Response:
            asked[name].append(request)
            if request.url.path == "/webapi/schools/search":
                return httpx.Response(200, json=[{"id": 1, "name": "Школа № 1"}])
            if request.url.path == "/webapi/terms/search":
                return httpx.Response(200, json=[])
            return httpx.Response(200, json={"schoolLogin": True})

        async def build() -> httpx.AsyncClient:
            return httpx.AsyncClient(
                transport=httpx.MockTransport(answer), cookies=diary_http.NoCookieJar()
            )

        return build

    monkeypatch.setattr(nsclient, "shared_client", pool("direct"))
    monkeypatch.setattr(nsclient, "proxied_client", pool("proxy"))
    return asked


@pytest.fixture
def egress(monkeypatch):
    """This process's DIARY_EGRESS and DIARY_PROXY_URL, for one test."""

    def put(value: str = "", proxy: str = PROXY) -> None:
        monkeypatch.setattr(get_settings(), "diary_egress", value)
        monkeypatch.setattr(get_settings(), "diary_proxy_url", proxy)

    return put


def _hosts(requests: list[httpx.Request]) -> set[str]:
    return {request.url.host for request in requests}


@pytest.mark.parametrize("region", EVERY_REGION, ids=lambda region: region.key)
async def test_a_region_s_calls_take_its_own_route(two_pools, egress, region):
    """The owner's table, call by call: a region marked direct never sees the
    proxy, and one marked for it never goes direct."""
    egress()
    await nsclient.NetSchoolClient(region).login_allowed()
    host = urlsplit(region.origin).hostname
    through, direct = _hosts(two_pools["proxy"]), _hosts(two_pools["direct"])
    if region.via_proxy:
        assert (through, direct) == ({host}, set())
    else:
        assert (through, direct) == (set(), {host})


async def test_with_the_egress_direct_no_region_sees_the_proxy(two_pools, egress):
    egress("direct")
    for region in EVERY_REGION:
        await nsclient.NetSchoolClient(region).login_allowed()
    assert two_pools["proxy"] == []
    assert len(two_pools["direct"]) == len(EVERY_REGION)


def _live(region: str) -> DiarySession:
    credential = json.dumps(
        {"v": 1, "region": region, "school_id": 1, "at": "at", "cookies": {}, "year_id": 2026}
    )
    return DiarySession(
        token_hash=hash_token(new_token()),
        upstream_token=seal(credential),
        login="parent@example.com",
        provider=NETSCHOOL,
        region=region,
    )


async def test_the_keep_alive_pings_each_session_by_its_region_s_route(
    session, two_pools, egress
):
    egress()
    session.add_all([_live("zabaikalsky"), _live("amur")])
    await session.commit()

    assert await diary_keepalive.keep_alive(session) == (2, 0)

    assert _hosts(two_pools["proxy"]) == {"region.zabedu.ru"}
    assert _hosts(two_pools["direct"]) == {"region.obramur.ru"}


async def test_the_bot_s_school_search_takes_the_region_s_route(two_pools, egress):
    """«⚙️ Класс» binds a diary through `NetSchoolClient(region)` — its sign-in
    options, then its schools (bot/handlers/manage/diary_binding.py). v1's and
    v2's school searches are DaData's register and never a region's server."""
    egress()
    for key in ("zabaikalsky", "amur"):
        found = await nsclient.NetSchoolClient(regions.get(key)).schools_search("Школа")
        assert found == [{"id": 1, "name": "Школа № 1", "address": None}]
    assert [request.url.path for request in two_pools["proxy"]] == ["/webapi/schools/search"]
    assert _hosts(two_pools["proxy"]) == {"region.zabedu.ru"}
    assert _hosts(two_pools["direct"]) == {"region.obramur.ru"}


async def test_a_period_read_takes_the_region_s_route(two_pools, egress):
    """`terms_search` sends its JSON body by a request of its own, outside `_send`."""
    egress()
    client = nsclient.NetSchoolClient(regions.get("zabaikalsky"), {"at": "at", "cookies": {}})
    assert await client.terms_search(3) == []
    assert [request.url.path for request in two_pools["proxy"]] == ["/webapi/terms/search"]
    assert two_pools["direct"] == []


def _pool_of(client: httpx.AsyncClient, origin: str) -> str:
    # As test_diary_proxy.py reads it: httpx keeps a route per URL pattern, and
    # a proxy is a pool of its own kind. Private, because the alternative is a
    # real proxy.
    transport = client._transport_for_url(httpx.URL(origin))
    return type(transport._pool).__name__  # type: ignore[attr-defined]


@pytest.fixture
async def fresh_pools(monkeypatch):
    """Both pools built from the environment this test sets, then dropped."""
    await nsclient.close_client()
    get_settings.cache_clear()
    yield monkeypatch
    await nsclient.close_client()
    get_settings.cache_clear()


async def test_the_proxied_pool_tunnels_and_the_direct_one_does_not(fresh_pools):
    fresh_pools.setenv("DIARY_PROXY_URL", PROXY)
    get_settings.cache_clear()
    origin = regions.get("zabaikalsky").origin
    assert _pool_of(await nsclient.proxied_client(), origin) == "AsyncHTTPProxy"
    assert _pool_of(await nsclient.shared_client(), origin) == "AsyncConnectionPool"


async def test_closing_closes_both_pools(fresh_pools):
    fresh_pools.setenv("DIARY_PROXY_URL", PROXY)
    get_settings.cache_clear()
    direct, through = await nsclient.shared_client(), await nsclient.proxied_client()
    await nsclient.close_client()
    assert direct.is_closed and through.is_closed
    assert await nsclient.proxied_client() is not through
