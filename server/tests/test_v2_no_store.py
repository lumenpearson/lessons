"""The answers no cache may keep, kept from caches on every transport (#357).

REST always said ``Cache-Control: private, no-store`` on the diary's reads,
the calendar feed, the class card and the answers that are a credential;
Connect said nothing, and ``GetClass`` and ``GetCalendarFeed`` are
``NO_SIDE_EFFECTS``, so a Connect client may send them as a ``GET`` whose
answer — the join code, the feed's secret address — a private cache could
keep. Both transports now read one table, and these tests hold both to it.
"""

from __future__ import annotations

import json
from types import SimpleNamespace
from typing import Any

import pytest
from connectrpc.request import Headers

from app.config import get_settings

NO_STORE = "private, no-store"
ORIGIN = "https://lessons.example.com"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _connect_get(v2: Any, name: str, token: str) -> Any:
    """``name`` over Connect as a ``GET``, as a client may send a side-effect-free call."""
    return await v2.http.get(
        f"/api/rpc/lessons.v2.{name}",
        params={"encoding": "json", "message": "{}", "connect": "v1"},
        headers=_auth(token),
    )


@pytest.fixture
def published(monkeypatch, served_settings) -> None:
    """A deployment with a public address, so ``GetCalendarFeed`` answers at all."""
    monkeypatch.setattr(served_settings, "public_base_url", ORIGIN)
    monkeypatch.setattr(get_settings(), "public_base_url", ORIGIN)


async def test_the_class_card_is_never_stored_over_connect_post_or_get(v2, v2_tokens) -> None:
    admin = v2_tokens["admin"]
    posted = await v2.connect("ClassService/GetClass", token=admin)
    got = await _connect_get(v2, "ClassService/GetClass", admin)
    assert (posted.status, got.status_code) == (200, 200)
    # The answer that matters: the join code is in it.
    assert posted.message.school_class.join_code == "TEST42"
    assert json.loads(got.content)["schoolClass"]["joinCode"] == "TEST42"
    assert posted.headers["cache-control"] == got.headers["cache-control"] == NO_STORE


async def test_the_calendar_feed_is_never_stored_over_connect_post_or_get(
    v2, v2_tokens, published
) -> None:
    viewer = v2_tokens["viewer"]
    minted = await v2.connect("MeService/CreateCalendarFeed", token=viewer)
    posted = await v2.connect("MeService/GetCalendarFeed", token=viewer)
    got = await _connect_get(v2, "MeService/GetCalendarFeed", viewer)
    assert (minted.status, posted.status, got.status_code) == (200, 200, 200)
    assert posted.message.calendar_feed.url.startswith(ORIGIN)
    assert (
        minted.headers["cache-control"]
        == posted.headers["cache-control"]
        == got.headers["cache-control"]
        == NO_STORE
    )


async def test_an_answer_outside_the_table_carries_no_cache_control(v2, v2_tokens) -> None:
    """The header is the table's, not every answer's: ``GetMe`` stays cacheable
    as the client's own stack sees fit, over both verbs, as over REST."""
    viewer = v2_tokens["viewer"]
    posted = await v2.connect("MeService/GetMe", token=viewer)
    got = await _connect_get(v2, "MeService/GetMe", viewer)
    rest = await v2.rest("MeService/GetMe", token=viewer)
    assert (posted.status, got.status_code, rest.status) == (200, 200, 200)
    assert "cache-control" not in posted.headers
    assert "cache-control" not in got.headers
    assert "cache-control" not in rest.headers


def test_both_transports_read_one_table() -> None:
    import app.rest as rest
    import app.rpc as rpc
    from app.rpc import caching

    assert rest.NEVER_STORED is caching.NEVER_STORED
    assert rpc.NEVER_STORED is caching.NEVER_STORED
    assert rest.NO_STORE is caching.NO_STORE


async def test_every_method_is_kept_from_caches_alike_on_both_transports(monkeypatch) -> None:
    """Every unary method's success, answered by REST's writer and by the
    Connect adapter, says the same about caching: the drift #357 was."""
    import app.rest as rest
    import app.rpc as rpc
    from app.rpc.methods import METHODS

    async def answered(method, request, **_kwargs):
        return method.output()

    monkeypatch.setattr(rpc, "invoke", answered)
    differences = []
    stored_nowhere = set()
    for method in METHODS.values():
        if method.streaming or method.binding is None:
            continue
        over_rest = rest._answer(method, method.output()).headers.get("cache-control")
        ctx = SimpleNamespace(
            request_headers=Headers(),
            response_headers=Headers(),
            client_address="203.0.113.9:52144",
        )
        await rpc._unary(method)(object(), method.input(), ctx)
        over_connect = ctx.response_headers.get("cache-control")
        if over_rest != over_connect:
            differences.append((method.key, over_rest, over_connect))
        if over_rest == NO_STORE:
            stored_nowhere.add(method.key)
    assert differences == []
    # The table is a few methods, not every answer, and holds the ones #357 named.
    assert {
        "lessons.v2.ClassService/GetClass",
        "lessons.v2.ClassService/UpdateClass",
        "lessons.v2.MeService/GetCalendarFeed",
        "lessons.v2.DeviceService/CreateDevice",
    } <= stored_nowhere
    assert "lessons.v2.MeService/GetMe" not in stored_nowhere


def test_every_name_the_table_writes_by_hand_is_a_method() -> None:
    """A misspelt key would keep nothing from any cache, silently."""
    from app.rpc.caching import NO_STORE_ALSO, NO_STORE_CREDENTIAL
    from app.rpc.methods import METHODS

    assert (NO_STORE_ALSO | NO_STORE_CREDENTIAL) - METHODS.keys() == set()
