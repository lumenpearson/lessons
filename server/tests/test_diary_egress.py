"""How the deployment reaches each diary's server (#406).

``DIARY_EGRESS`` is ``direct``, ``proxy`` or ``relay``, read beside
``DIARY_PROXY_URL`` (docs/specs/2026-10-10-deployment-scenarios-design.md,
«How a scenario is chosen»). Under ``proxy``, Petersburg and the twelve
«Сетевой город» regions that answered from the RUVDS proxy on 11 October 2026
go through it; the other seven stay direct until the relay (#408). Nothing here
reaches the network.
"""

from __future__ import annotations

from urllib.parse import urlsplit

import pytest

from app.config import DiaryEgress, Settings
from app.providers.diary import registry
from app.providers.diary.registry import NETSCHOOL, PETERSBURG
from app.providers.netschool import regions

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
