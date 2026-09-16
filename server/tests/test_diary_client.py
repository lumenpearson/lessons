"""What the provider makes of an answer the upstream did not promise.

`tests/test_diary_api.py` drives the same client through the routes; this file
is the half of it that is about one call in isolation — the login, which is the
only one made without a session and therefore the only one for which "your
session ended" cannot be the answer.

Nothing here reaches the network: the shared client is replaced by one over an
``httpx.MockTransport``.
"""

from __future__ import annotations

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
