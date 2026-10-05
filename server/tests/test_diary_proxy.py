"""The Petersburg diary's optional proxy (#334).

The city's network drops connections from outside Russia (#235), so production
reaches the diary through a Russian proxy named by ``DIARY_PROXY_URL``, and
only the diary does. These tests hold the setting's reading and the client's
route. They reach no network: the route is read off the client httpx built.
"""

from __future__ import annotations

import httpx
import pytest

from app.config import Settings, get_settings
from app.providers.petersburg import client as petersburg


@pytest.mark.parametrize(
    "value",
    [
        "http://proxy.example:3128",
        "http://user:secret@proxy.example:3128",
        "https://proxy.example:443",
        "  http://proxy.example:3128  ",
    ],
)
def test_a_usable_proxy_is_used(value):
    assert Settings(diary_proxy_url=value).diary_proxy == value.strip()


@pytest.mark.parametrize(
    "value",
    [
        "",
        "   ",
        "socks5://proxy.example:1080",
        "proxy.example:3128",
        "http://",
        "http://proxy.example:port",
    ],
)
def test_an_unusable_or_empty_proxy_means_direct(value):
    assert Settings(diary_proxy_url=value).diary_proxy is None


def test_an_unusable_proxy_is_announced_without_its_value():
    """It may carry the proxy's password, and the startup log is no place for it."""
    lines = Settings(diary_proxy_url="ftp://user:hunter2@proxy.example:21").disabled_features()
    announced = [line for line in lines if "DIARY_PROXY_URL" in line]
    assert announced
    assert not any("hunter2" in line or "proxy.example" in line for line in lines)


def test_an_empty_proxy_announces_nothing():
    """Direct is a route, not a feature switched off: a deployment inside Russia needs none."""
    lines = Settings(diary_proxy_url="").disabled_features()
    assert not any("DIARY_PROXY_URL" in line for line in lines)


def _pool_of(client: httpx.AsyncClient) -> str:
    # httpx keeps the route per URL pattern; a proxy is a pool of its own kind.
    # Private, and read only here, because the alternative is a real proxy.
    transport = client._transport_for_url(httpx.URL(petersburg.BASE_URL))
    return type(transport._pool).__name__  # type: ignore[attr-defined]


@pytest.fixture
async def fresh_client(monkeypatch):
    """A shared client built from the environment this test sets, then dropped."""
    await petersburg.close_client()
    get_settings.cache_clear()
    yield monkeypatch
    await petersburg.close_client()
    get_settings.cache_clear()


async def test_the_diary_goes_through_the_proxy_when_one_is_set(fresh_client):
    fresh_client.setenv("DIARY_PROXY_URL", "http://user:secret@proxy.example:3128")
    get_settings.cache_clear()
    client = await petersburg.shared_client()
    assert _pool_of(client) == "AsyncHTTPProxy"


async def test_the_diary_goes_direct_without_one(fresh_client):
    fresh_client.delenv("DIARY_PROXY_URL", raising=False)
    get_settings.cache_clear()
    client = await petersburg.shared_client()
    assert _pool_of(client) == "AsyncConnectionPool"
