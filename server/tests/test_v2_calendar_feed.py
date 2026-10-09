"""``MeService``'s calendar feed: the class's subscription address.

v1's ``GET /calendar`` minted the feed's secret on a read, for any phone of
the class, an anonymous one included. v2 mints only in ``CreateCalendarFeed``,
and both methods ask for a linked account, as the proto says; ``GetCalendarFeed``
reads and writes nothing (``docs/specs/2026-10-05-server-v2-design.md``,
decision 10). The address is v1's, on the deployment's ``PUBLIC_BASE_URL``,
and the feed itself stays at its v1 path. A deployment with no public address
offers no feed and mints nothing, as «📅 Календарь» offers none.
"""

from __future__ import annotations

import pytest
from sqlalchemy import select

from app.config import get_settings
from app.models import SchoolClass
from app.rpc.me import NO_FEED_ADDRESS

READ = "MeService/GetCalendarFeed"
MINT = "MeService/CreateCalendarFeed"
ORIGIN = "https://lessons.example.com"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def published(monkeypatch, served_settings) -> None:
    """A deployment with a public address, as v2 reads it and as v1 reads it."""
    monkeypatch.setattr(served_settings, "public_base_url", ORIGIN)
    monkeypatch.setattr(get_settings(), "public_base_url", ORIGIN)


async def _secret(session, school_class) -> str | None:
    return await session.scalar(
        select(SchoolClass.calendar_token).where(SchoolClass.id == school_class.id)
    )


async def test_the_feed_is_minted_once_and_read_after_at_v1_s_address(
    v2, v2_tokens, session, school_class, published
) -> None:
    token = v2_tokens["viewer"]
    before = await v2.both(READ, token=token)
    assert not before.message.calendar_feed.has_field("url")

    minted = await v2.both(MINT, token=token)
    url = minted.message.calendar_feed.url
    assert url == f"{ORIGIN}/api/v1/calendar/{await _secret(session, school_class)}.ics"
    read = await v2.both(READ, token=token)
    assert read.message.calendar_feed.url == url
    v1 = await v2.http.get("/api/v1/calendar", headers=_auth(token))
    assert v1.json()["url"] == url
    # A secret address is nobody's cache's, minted or read.
    assert minted.headers["cache-control"] == read.headers["cache-control"] == "private, no-store"
    # And it is the feed, at its v1 path.
    feed = await v2.http.get(url.removeprefix(ORIGIN))
    assert feed.status_code == 200
    assert feed.text.startswith("BEGIN:VCALENDAR")


async def test_reading_the_feed_mints_nothing(
    v2,
    v2_tokens,
    session,
    school_class,
    published,
    statement_writes,
    unexpected_writes,
    last_seen_rule,
) -> None:
    with statement_writes() as seen:
        answer = await v2.both(READ, token=v2_tokens["viewer"])
    assert answer.status == 200
    assert not answer.message.calendar_feed.has_field("url")
    # A write happened, the phone's last call, and nothing else did.
    assert seen
    assert unexpected_writes(seen) == []
    assert all(last_seen_rule.match(statement) for statement in seen), seen
    assert await _secret(session, school_class) is None


async def test_a_deployment_with_no_public_address_offers_no_feed_and_mints_none(
    v2, v2_tokens, session, school_class, monkeypatch, served_settings
) -> None:
    monkeypatch.setattr(served_settings, "public_base_url", "")
    for name in (READ, MINT):
        answer = await v2.both(name, token=v2_tokens["viewer"])
        assert (answer.status, answer.code, answer.reason) == (
            501,
            "UNIMPLEMENTED",
            "FEATURE_UNSUPPORTED",
        ), name
        assert answer.metadata == {"feature": "calendar_feed"}
        assert answer.error == NO_FEED_ADDRESS
    assert await _secret(session, school_class) is None


async def test_a_phone_nobody_is_behind_mints_no_feed_as_it_could_over_v1(
    v2, v2_tokens, session, school_class, published
) -> None:
    token = v2_tokens["unlinked"]
    for name in (READ, MINT):
        refused = await v2.both(name, token=token)
        assert (refused.status, refused.reason) == (403, "DEVICE_NOT_LINKED"), name
    assert await _secret(session, school_class) is None
    # v1 still mints for it: v1's answers do not change.
    assert (await v2.http.get("/api/v1/calendar", headers=_auth(token))).status_code == 200
    assert await _secret(session, school_class) is not None
