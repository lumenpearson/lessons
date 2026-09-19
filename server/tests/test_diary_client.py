"""What the provider makes of an answer the upstream did not promise.

`tests/test_diary_api.py` drives the same client through the routes; this file
is the half of it that is about one call in isolation — the login, which is the
only one made without a session and therefore the only one for which "your
session ended" cannot be the answer.

Nothing here reaches the network: the shared client is replaced by one over an
``httpx.MockTransport``.
"""

from __future__ import annotations

import pathlib

import httpx
import pytest

from app.providers.petersburg import client as provider_client
from app.providers.petersburg.exceptions import (
    BadCredentials,
    SessionExpired,
    UnexpectedResponse,
)


@pytest.fixture
async def upstream(monkeypatch):
    """Installs a transport whose answer each test sets."""
    answer: dict[str, httpx.Response] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        return answer["response"]

    client = httpx.AsyncClient(
        base_url=provider_client.BASE_URL, transport=httpx.MockTransport(handler)
    )

    async def shared():
        return client

    monkeypatch.setattr(provider_client, "shared_client", shared)
    yield answer
    await client.aclose()


async def test_a_login_answered_with_html_is_not_reported_as_an_expired_session(upstream):
    """A 200 that is not JSON means «you are logged out» on every call that
    carries a session. On the login it cannot: nobody is signed in yet.

    Reported as SessionExpired it reached the phone as 401 with
    ``X-Diary-Reauth: required`` — the app's instruction to ask for the
    password again, on the sign-in screen the person is already looking at.
    They retype it, the upstream is still serving its page, and nothing in the
    loop ever says the password is not what is wrong.
    """
    upstream["response"] = httpx.Response(200, text="<html><body>Вход</body></html>")

    with pytest.raises(UnexpectedResponse):
        await provider_client.PetersburgClient().login("parent@example.com", "hunter2")


async def test_a_refused_pair_is_still_a_refused_pair(upstream):
    upstream["response"] = httpx.Response(401, json={"message": "no"})

    with pytest.raises(BadCredentials):
        await provider_client.PetersburgClient().login("parent@example.com", "hunter2")


async def test_html_on_a_call_that_did_carry_a_session_still_means_sign_in_again(upstream):
    """The other side of the same rule, so that narrowing it did not undo it."""
    upstream["response"] = httpx.Response(200, text="<html><body>Вход</body></html>")

    with pytest.raises(SessionExpired):
        await provider_client.PetersburgClient("a-session").children()


# ---- the dependency this file's behaviour rests on -------------------------


def _httpx_specifier(text: str) -> str:
    """The one requirement line for httpx, wherever it is written down."""
    for line in text.splitlines():
        stripped = line.strip().strip('",')
        if stripped.startswith("httpx"):
            return stripped
    raise AssertionError("httpx is not required anywhere in this file")


async def test_a_call_carries_its_own_session_in_the_request(monkeypatch):
    """The property the version bound below exists to protect: the session is
    handed to the call, not kept on the shared client, because that client
    serves every family at once."""
    sent: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        sent.append(request)
        return httpx.Response(200, json={"data": {"items": []}})

    client = httpx.AsyncClient(
        base_url=provider_client.BASE_URL, transport=httpx.MockTransport(handler)
    )

    async def shared():
        return client

    monkeypatch.setattr(provider_client, "shared_client", shared)
    try:
        await provider_client.PetersburgClient("a-session").children()
    finally:
        await client.aclose()

    assert f"{provider_client.SESSION_COOKIE}=a-session" in sent[0].headers["cookie"]
    # And nothing stuck to the client, which is the half that keeps two
    # families' sessions from meeting.
    assert not client.cookies


@pytest.mark.parametrize(
    "path",
    [
        pathlib.Path(__file__).resolve().parents[1] / "pyproject.toml",
        pathlib.Path(__file__).resolve().parents[2] / "requirements.txt",
    ],
    ids=["pyproject", "requirements"],
)
def test_httpx_is_bounded_above_because_this_client_depends_on_a_deprecation(path):
    """Every diary call is ``request(..., cookies=...)``, which httpx 0.28
    warns is "being deprecated" in favour of cookies on the client — the one
    migration this provider must not take, because the client is shared by
    every family at once.

    A release that dropped the parameter would not fail a build or a test: the
    call would simply go out with no session, the upstream would answer with
    its login page, and every user would be told their diary session expired.
    Nothing about that says «pip installed a newer httpx», so the version is
    where it has to be caught.
    """
    specifier = _httpx_specifier(path.read_text(encoding="utf-8"))
    assert "<" in specifier, f"{path.name} lets httpx float past the deprecation: {specifier}"
    assert specifier.startswith("httpx>=0.28.1,<")


def test_the_two_places_httpx_is_pinned_say_the_same_thing():
    """``requirements.txt`` exists only because Vercel's builder cannot read a
    pyproject in a subdirectory, so it is a copy — and a copy that drifts is
    worse than no copy, because the deployed set is the one nobody runs tests
    against."""
    here = pathlib.Path(__file__).resolve()
    assert _httpx_specifier((here.parents[1] / "pyproject.toml").read_text()) == _httpx_specifier(
        (here.parents[2] / "requirements.txt").read_text()
    )


async def test_two_session_cookies_in_one_answer_do_not_escape_as_a_bare_exception(
    upstream,
):
    """A PHP application rotating a scoped session sets the same name twice.

    `Set-Cookie: X-JWT-Token=new1; Path=/` beside
    `Set-Cookie: X-JWT-Token=new2; Path=/api` — clear the old scope, set the
    new one — and `httpx.Cookies.get` answers that with `CookieConflict`,
    which is not an `httpx.HTTPError`. It therefore walked out of this package
    as something `exceptions.py` says cannot happen: `api/diary._guard`
    catches only `PetersburgError`, so every diary endpoint answered 500
    rather than 401 or 502, on every call, because the cookie shape does not
    change; and `diary_web` landed in its bare `except Exception`, said
    «дневник ответил непонятно» and kept the ticket it had already spent.
    """
    cookie = provider_client.SESSION_COOKIE
    upstream["response"] = httpx.Response(
        200,
        json={"data": {"list": []}},
        headers=[
            ("set-cookie", f"{cookie}=new1; Path=/"),
            ("set-cookie", f"{cookie}=new2; Path=/api"),
        ],
    )

    client = provider_client.PetersburgClient("old-token")
    await client.children()

    assert client.token in {"new1", "new2"}
