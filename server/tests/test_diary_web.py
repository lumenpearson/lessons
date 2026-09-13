"""The sign-in form: the one page, and the reasons it is a page at all.

What is being tested is not HTML. It is the handful of properties that make
taking a password here safer than taking it in a chat: the ticket is worth one
attempt, a GET does not spend it, the password never reaches the database, and
the headers that stop the ticket leaking out of the URL are actually sent.
"""

from __future__ import annotations

import json

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import select
from test_diary_api import LOGIN_PATH, FakeUpstream, with_token  # noqa: F401

from app.config import get_settings
from app.main import app
from app.models import DiaryLinkCode, DiarySession, SchoolClass
from app.providers.petersburg import client as provider_client
from app.services import diary_link


@pytest.fixture
async def upstream(monkeypatch):
    fake = FakeUpstream({LOGIN_PATH: with_token})

    async def shared():
        return httpx.AsyncClient(
            base_url=provider_client.BASE_URL,
            transport=httpx.MockTransport(fake.handler),
        )

    monkeypatch.setattr(provider_client, "shared_client", shared)
    return fake


@pytest.fixture
async def web():
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest.fixture
async def ticket(session, school_class) -> str:
    return await diary_link.mint(session, telegram_id=42, class_id=school_class.id)


async def test_the_form_is_served_for_a_live_ticket(web, ticket):
    response = await web.get(f"/diary/signin/{ticket}")

    assert response.status_code == 200
    assert 'type=password' in response.text
    assert "dnevnik2.petersburgedu.ru" in response.text


async def test_the_headers_that_keep_the_ticket_out_of_the_world_are_sent(web, ticket):
    """The ticket is in the URL, so the leaks are all URL-shaped: a Referer to
    somewhere else, a cached copy on a shared laptop, a search index."""
    response = await web.get(f"/diary/signin/{ticket}")

    assert response.headers["referrer-policy"] == "no-referrer"
    assert "no-store" in response.headers["cache-control"]
    assert "noindex" in response.headers["x-robots-tag"]
    assert "default-src 'none'" in response.headers["content-security-policy"]


async def test_a_get_does_not_spend_the_ticket(web, ticket, session):
    """A link preview or a prefetch would otherwise burn it before anybody had
    typed anything — and Telegram fetches link previews by itself."""
    await web.get(f"/diary/signin/{ticket}")
    await web.get(f"/diary/signin/{ticket}")

    row = await session.scalar(select(DiaryLinkCode))
    assert row.used_at is None


async def test_signing_in_opens_a_session_bound_to_the_telegram_account(
    web, upstream, ticket, session, school_class
):
    response = await web.post(
        f"/diary/signin/{ticket}",
        data={"login": "parent@example.com", "password": "correct"},
    )

    assert response.status_code == 200
    assert "Вы вошли" in response.text

    opened = await session.scalar(select(DiarySession))
    assert opened.telegram_id == 42
    assert opened.class_id == school_class.id
    assert opened.login == "parent@example.com"


async def test_the_password_reaches_the_upstream_and_nothing_else(
    web, upstream, ticket, session
):
    """The whole argument for the page. It goes to dnevnik2 and is written
    down nowhere — not in our row, not in a log, not in a chat."""

    def accepts_anything(request: httpx.Request) -> httpx.Response:
        response = httpx.Response(200, json={"data": {"token": "body-token"}})
        response.headers["set-cookie"] = "X-JWT-Token=cookie-token; Path=/"
        return response

    upstream.routes[LOGIN_PATH] = accepts_anything
    await web.post(
        f"/diary/signin/{ticket}",
        data={"login": "parent@example.com", "password": "hunter2-secret"},
    )

    sent = json.loads(next(r for r in upstream.seen if r.url.path == LOGIN_PATH).content)
    assert sent["password"] == "hunter2-secret"

    row = await session.scalar(select(DiarySession))
    stored = json.dumps(
        {c.name: str(getattr(row, c.name)) for c in DiarySession.__table__.columns},
        ensure_ascii=False,
    )
    assert "hunter2-secret" not in stored


async def test_a_ticket_is_worth_one_attempt_even_a_failed_one(
    web, upstream, ticket, session
):
    """Spent before the sign-in it authorises, not after.

    A ticket that survived a wrong password would let whoever holds the URL sit
    and guess against the upstream from our address — which is our address
    getting rate-limited for somebody else's attack.
    """
    first = await web.post(
        f"/diary/signin/{ticket}",
        data={"login": "parent@example.com", "password": "wrong"},
    )
    assert first.status_code == 401
    assert await session.scalar(select(DiarySession)) is None

    second = await web.post(
        f"/diary/signin/{ticket}",
        data={"login": "parent@example.com", "password": "correct"},
    )
    assert second.status_code == 410  # gone, not «try again»


async def test_a_malformed_body_does_not_burn_the_ticket(web, ticket, session):
    """A mistyped form has not attempted a sign-in, so it should not cost a
    trip back to the bot for a new link."""
    response = await web.post(
        f"/diary/signin/{ticket}", data={"login": "", "password": ""}
    )

    assert response.status_code == 400
    row = await session.scalar(select(DiaryLinkCode))
    assert row.used_at is None


async def test_an_unknown_ticket_is_gone_rather_than_a_form(web):
    response = await web.get("/diary/signin/never-minted-this")

    assert response.status_code == 410
    assert "type=password" not in response.text


async def test_a_second_link_retires_the_first(session, school_class):
    """Two live links is two chances for the older one — further up the chat,
    likelier to be scrolled past by somebody else — to still work."""
    first = await diary_link.mint(session, telegram_id=42, class_id=school_class.id)
    second = await diary_link.mint(session, telegram_id=42, class_id=school_class.id)

    assert await diary_link.claim(session, first) is None
    assert await diary_link.claim(session, second) is not None


async def test_an_expired_ticket_is_refused_and_purged(session, school_class, monkeypatch):
    code = await diary_link.mint(session, telegram_id=42, class_id=school_class.id)

    row = await session.scalar(select(DiaryLinkCode))
    row.expires_at = diary_link.utcnow()
    await session.commit()

    assert await diary_link.claim(session, code) is None
    assert await diary_link.purge(session) == 1
    assert await session.scalar(select(DiaryLinkCode)) is None


async def test_without_a_key_the_page_refuses_rather_than_asking_for_a_password(
    web, ticket, monkeypatch
):
    """The diary is off without DIARY_SECRET, and «off» has to include the one
    screen that would otherwise collect a credential it cannot store."""
    monkeypatch.setattr(get_settings(), "diary_secret", "", raising=False)
    try:
        response = await web.get(f"/diary/signin/{ticket}")
        assert response.status_code == 503
        assert "type=password" not in response.text
    finally:
        get_settings.cache_clear()


async def test_the_class_a_ticket_was_minted_in_is_the_one_it_signs_into(
    session, school_class
):
    """So that «Дневник» in one class cannot answer with a session opened in
    another, and leaving a class takes its session with it."""
    other = SchoolClass(name="9Б", school="Школа № 1", join_code="OTHER42")
    session.add(other)
    await session.commit()

    code = await diary_link.mint(session, telegram_id=42, class_id=other.id)
    claimed = await diary_link.claim(session, code)

    assert claimed.class_id == other.id
    assert claimed.class_id != school_class.id
