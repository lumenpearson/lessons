"""The sign-in form: the one page, and the reasons it is a page at all.

What is being tested is not HTML. It is the handful of properties that make
taking a password here safer than taking it in a chat: the ticket is worth one
attempt, a GET does not spend it, the password never reaches the database, and
the headers that stop the ticket leaking out of the URL are actually sent.
"""

from __future__ import annotations

import json
import logging

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import select

from app.api import diary_web as from_app
from app.bot import diary_render
from app.config import get_settings
from app.main import app
from app.models import DiaryLinkCode, DiarySession, SchoolClass
from app.providers.petersburg import client as provider_client
from app.services import diary_link


@pytest.fixture
async def upstream(monkeypatch, FakeUpstream, LOGIN_PATH, with_token):
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
    # The form and the submit read the class's diary binding now (#137), so the
    # ticket's class has to be bound. Petersburg, the diary these tests exercise.
    school_class.diary_provider = "petersburg"
    await session.commit()
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


async def test_an_oversized_body_is_dropped_before_it_is_all_in_memory(web, ticket):
    """The constant says «before reading it», and now that is true.

    `await request.body()` buffers the whole thing and only then lets anything
    measure it, which on a serverless function means the megabyte is already in
    the memory it has least of. The body is read in chunks with a running
    total, so a sender that keeps talking is cut off at the line rather than
    after it — this test counts what the app actually pulled.
    """
    sent = 0
    total = 4 * 1024 * 1024

    async def flood():
        nonlocal sent
        for _ in range(total // (64 * 1024)):
            sent += 64 * 1024
            yield b"x" * (64 * 1024)

    response = await web.post(
        f"/diary/signin/{ticket}",
        content=flood(),
        headers={"content-type": "application/x-www-form-urlencoded"},
    )

    # Refused as a body that is not this form's, which is also why the ticket
    # survives: nothing was attempted upstream.
    assert response.status_code == 400
    assert sent < total, "the whole flood was read before anything measured it"
    # One chunk is the smallest thing a reader can pull, and the sender's
    # chunks are 64 KiB; what matters is that it stopped at the first one over
    # the line instead of swallowing four megabytes.
    assert sent <= 64 * 1024, f"read {sent} bytes to refuse a body capped at {from_app.MAX_BODY}"


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
    web, upstream, ticket, session, LOGIN_PATH, caplog
):
    """The whole argument for the page. It passes through this server to
    dnevnik2 and is written down nowhere — not in our row, not in a log, not
    in a chat. The log half was only this docstring's word until the page
    started saying «нигде не сохраняется» on the strength of it (#150)."""
    caplog.set_level(logging.DEBUG)

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
    assert "hunter2-secret" not in caplog.text


async def test_neither_text_says_the_password_skips_this_server(web, ticket):
    """#150. The page said the password went «прямо в дневник», and so did the
    bot's card that leads to it; the form posts to our own path, and the
    handler hands the password on. Both now say it goes through this server,
    and keep the two promises that are true: not in the chat, kept nowhere."""
    page = (await web.get(f"/diary/signin/{ticket}")).text
    card = diary_render.render_signed_out("Санкт-Петербурга")

    # The fact the wording has to agree with: the browser posts here.
    assert f'action="/diary/signin/{ticket}"' in page
    for text in (page, card):
        assert "прямо в дневник" not in text
        assert "передаёт пароль дневнику для входа" in text
        assert "нигде не сохраняется" in text
    assert "а не в чате" in page
    assert "<b>Пароль не вводится в чат.</b>" in card


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


# ---- a class whose binding is not the one the link was minted under (#137) --


@pytest.fixture
def password_sent(monkeypatch) -> list[dict]:
    """Every sign-in the page attempts. A refusal here must leave it empty:
    the point of refusing is that the password never leaves this server."""
    calls: list[dict] = []

    async def spy(session, login, password, **kwargs):
        calls.append({"login": login, **kwargs})
        raise AssertionError("the password was sent to a diary")

    monkeypatch.setattr(from_app.diary_service, "sign_in", spy)
    return calls


async def test_a_link_into_a_class_with_no_diary_opens_no_form_and_sends_nothing(
    web, session, school_class, password_sent
):
    """Refused at the GET and at the POST, without spending the ticket: the
    family did nothing wrong, and a class bound again can use the same link."""
    assert school_class.diary_provider is None
    code = await diary_link.mint(session, telegram_id=42, class_id=school_class.id)

    shown = await web.get(f"/diary/signin/{code}")
    assert shown.status_code == 410
    assert "type=password" not in shown.text

    sent = await web.post(
        f"/diary/signin/{code}", data={"login": "parent@example.com", "password": "secret"}
    )
    assert sent.status_code == 410
    assert "не привязан" in sent.text
    assert password_sent == []
    row = await session.scalar(select(DiaryLinkCode))
    await session.refresh(row)
    assert row.used_at is None


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


# ---- whose fault the failure was ------------------------------------------


def _maintenance(request: httpx.Request) -> httpx.Response:
    """What the upstream sends while it is down or showing a captcha: a 200,
    and a page. It is the shape ``PetersburgClient.login`` turns into
    ``UnexpectedResponse``."""
    return httpx.Response(200, text="<html><body>Технические работы</body></html>")


def _refuses(request: httpx.Request) -> httpx.Response:
    return httpx.Response(503, text="upstream down")


async def test_a_diary_in_maintenance_is_not_reported_as_a_wrong_password(
    web, upstream, ticket, LOGIN_PATH
):
    """The password was right. The diary was serving HTML.

    Told «Неверный логин или пароль» the person retypes a correct password
    forever: nothing on the page, in the bot or in the app suggests the diary
    is what is broken, and the ticket they would need for another go is gone.
    """
    upstream.routes[LOGIN_PATH] = _maintenance

    response = await web.post(
        f"/diary/signin/{ticket}",
        data={"login": "parent@example.com", "password": "correct"},
    )

    assert "Неверный логин или пароль" not in response.text
    assert "dnevnik2.petersburgedu.ru" in response.text
    assert response.status_code == 502


async def test_an_unreadable_netschool_answer_names_the_regions_own_server(
    web, session, school_class, monkeypatch
):
    """#158: the fallback sent a «Сетевой город» family to Петербург's diary.

    Opening the diary in a browser is the one check that tells a person
    whether their diary is down — so it has to be *their* diary's address,
    the regional server the class is bound to, not dnevnik2.
    """
    from app.providers.diary.errors import UnexpectedResponse

    school_class.diary_provider = "netschool"
    school_class.diary_region = "samara"
    school_class.diary_school_id = 1234
    school_class.diary_school_name = "Школа № 1"
    await session.commit()
    code = await diary_link.mint(session, telegram_id=42, class_id=school_class.id)

    async def unreadable(*args, **kwargs):
        raise UnexpectedResponse

    monkeypatch.setattr(from_app.diary_service, "sign_in", unreadable)

    response = await web.post(
        f"/diary/signin/{code}", data={"login": "parent", "password": "correct"}
    )

    assert response.status_code == 502
    assert "asurso.ru" in response.text
    assert "dnevnik2.petersburgedu.ru" not in response.text


async def test_an_unreadable_answer_still_costs_the_ticket(
    web, upstream, ticket, session, LOGIN_PATH, with_token
):
    """The message changes; the economics do not, and that is deliberate.

    A 200 of HTML is what this upstream sends for a captcha — and it is also
    what a login form built on Yii sends for a **wrong password**. Nobody has
    ever opened this diary for real, so from here the two are the same bytes.
    Handing the ticket back on a verdict we cannot read would make this URL an
    unlimited password oracle against the upstream from our address, which is
    the single thing spending the ticket early exists to prevent.
    """
    upstream.routes[LOGIN_PATH] = _maintenance

    answer = await web.post(
        f"/diary/signin/{ticket}",
        data={"login": "parent@example.com", "password": "correct"},
    )
    assert answer.status_code == 502
    # The point of the fix: it no longer blames the password.
    assert "Неверный логин или пароль" not in answer.text
    assert "технические работы" in answer.text

    row = await session.scalar(select(DiaryLinkCode))
    await session.refresh(row)
    assert row.used_at is not None, "an unreadable verdict is still an attempt"

    upstream.routes[LOGIN_PATH] = with_token
    second = await web.post(
        f"/diary/signin/{ticket}",
        data={"login": "parent@example.com", "password": "correct"},
    )
    assert second.status_code == 410


async def test_a_diary_that_does_not_answer_says_so_and_keeps_the_ticket(
    web, upstream, ticket, session, LOGIN_PATH
):
    upstream.routes[LOGIN_PATH] = _refuses

    response = await web.post(
        f"/diary/signin/{ticket}",
        data={"login": "parent@example.com", "password": "correct"},
    )

    assert response.status_code == 503
    assert "Неверный логин или пароль" not in response.text
    assert "не отвечает" in response.text

    row = await session.scalar(select(DiaryLinkCode))
    await session.refresh(row)
    assert row.used_at is None


async def test_our_own_crash_still_costs_the_ticket(web, upstream, ticket, session, monkeypatch):
    """This branch catches everything, including whatever we might raise
    *after* the upstream has already judged the password — so it cannot be
    told apart from an attempt, and an attempt is what the ticket pays for.
    Forgiving it would be a second way to get a free guess."""

    async def explodes(*args, **kwargs):
        raise RuntimeError("boom")

    monkeypatch.setattr("app.api.diary_web.diary_service.sign_in", explodes)

    response = await web.post(
        f"/diary/signin/{ticket}",
        data={"login": "parent@example.com", "password": "correct"},
    )

    assert response.status_code == 500
    row = await session.scalar(select(DiaryLinkCode))
    await session.refresh(row)
    assert row.used_at is not None


async def test_a_refused_sign_in_never_draws_the_form_again(web, upstream, ticket):
    """The page that says «не получилось» must not carry a form.

    The ticket is spent by the attempt, so a form drawn under the refusal is a
    form whose «Войти» does nothing but answer 410 — which reads as a second
    failure and sends people back to the bot for a third link. `_form` used to
    take an `error` to print above exactly that, and a `401` to answer it with;
    nothing ever passed one, and the branch would have been wrong if it had.
    """
    response = await web.post(
        f"/diary/signin/{ticket}",
        data={"login": "parent@example.com", "password": "wrong"},
    )

    assert response.status_code == 401
    assert "<form" not in response.text
    # Not a style left behind by the branch that went either, which is how a
    # renderer keeps a shape nothing builds.
    assert "class=bad" not in response.text


async def _netschool_ticket(session, school_class, monkeypatch, routes: dict) -> str:
    """A ticket for a class bound to «Сетевой город» in samara, with the
    regional server answering ``routes`` (path → JSON) and 404 elsewhere."""
    from app.providers.diary import http as diary_http
    from app.providers.netschool import client as nsclient

    def handler(request: httpx.Request) -> httpx.Response:
        body = routes.get(request.url.path)
        if body is None:
            return httpx.Response(404, json={})
        return httpx.Response(200, json=body)

    async def shared():
        return httpx.AsyncClient(
            transport=httpx.MockTransport(handler), cookies=diary_http.NoCookieJar()
        )

    monkeypatch.setattr(nsclient, "shared_client", shared)
    school_class.diary_provider = "netschool"
    school_class.diary_region = "samara"
    school_class.diary_school_id = 1234
    school_class.diary_school_name = "Школа № 1"
    await session.commit()
    return await diary_link.mint(session, telegram_id=42, class_id=school_class.id)


async def test_an_account_with_no_pupil_is_told_so_rather_than_sent_to_the_site(
    web, session, school_class, monkeypatch
):
    """The password was right and the account lists no pupil. `NoStudents` fell
    through to «ответил непонятно … откройте asurso.ru, попросите новую
    ссылку» — a diary that is up, and a link that fails the same way."""
    code = await _netschool_ticket(session, school_class, monkeypatch, {
        "/webapi/logindata": {"schoolLogin": True, "cacheVer": "1"},
        "/webapi/auth/getdata": {"lt": "1", "ver": "2", "salt": "3"},
        "/webapi/login": {"at": "at-staff"},
        "/webapi/student/diary/init": {"students": []},
    })

    response = await web.post(
        f"/diary/signin/{code}", data={"login": "teacher", "password": "correct"}
    )

    assert response.status_code == 403
    assert "В этой учётной записи нет ученика" in response.text
    assert "ответил непонятно" not in response.text
    assert "родителя или ученика" in response.text
    row = await session.scalar(select(DiaryLinkCode))
    await session.refresh(row)
    assert row.used_at is not None, "the password was judged"


async def test_a_region_that_takes_only_gosuslugi_does_not_say_try_again(
    web, session, school_class, monkeypatch
):
    """The page said «логин и пароль тут не подойдут» over «откройте её снова и
    попробуйте ещё раз», because the note was picked by the ticket alone."""
    code = await _netschool_ticket(session, school_class, monkeypatch, {
        "/webapi/logindata": {"schoolLogin": False},
    })

    response = await web.post(
        f"/diary/signin/{code}", data={"login": "parent", "password": "correct"}
    )

    assert response.status_code == 503
    assert "Госуслуги" in response.text
    assert "попробуйте ещё раз" not in response.text
    assert "asurso.ru" in response.text
    row = await session.scalar(select(DiaryLinkCode))
    await session.refresh(row)
    assert row.used_at is None, "nothing looked at the password"
