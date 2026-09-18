"""The webhook endpoint is the bot's whole attack surface on serverless.

Telegram authorises nothing: it just POSTs JSON. The bot then trusts the user id
inside that JSON to decide who may rewrite a timetable. So the secret header is
the only thing between the public internet and full control of the bot, and it
gets tested accordingly.
"""

from __future__ import annotations

import pytest
from fastapi import HTTPException

from app.api import telegram
from app.config import Settings, get_settings

SECRET = "s3cret-token-value"


@pytest.fixture
def configured(monkeypatch):
    """Pretend the deployment has a bot token and a webhook secret."""
    settings = Settings(
        bot_token="123456:TEST",
        webhook_secret=SECRET,
        database_url=get_settings().database_url,
        run_bot=False,
    )
    monkeypatch.setattr(telegram, "get_settings", lambda: settings)
    return settings


@pytest.fixture(autouse=True)
def _no_real_dispatch(monkeypatch):
    """Record what would have been dispatched instead of dispatching it."""
    seen: list[dict] = []

    async def fake_feed(bot, update):
        seen.append(update.model_dump(exclude_none=True))

    class FakeDispatcher:
        feed_update = staticmethod(fake_feed)

    monkeypatch.setattr(telegram, "_instances", lambda: (object(), FakeDispatcher()))
    return seen


def _update(text: str = "/start") -> dict:
    return {
        "update_id": 1,
        "message": {
            "message_id": 1,
            "date": 1757000000,
            "chat": {"id": 1, "type": "private"},
            "from": {"id": 1, "is_bot": False, "first_name": "Тест"},
            "text": text,
        },
    }


async def test_a_correct_secret_is_accepted(configured, _no_real_dispatch):
    await telegram.handle_update(_update(), SECRET)
    assert len(_no_real_dispatch) == 1


async def test_a_wrong_secret_is_refused(configured):
    with pytest.raises(HTTPException) as raised:
        await telegram.handle_update(_update(), "not-the-secret")
    assert raised.value.status_code == 403


async def test_a_missing_secret_is_refused(configured):
    for header in (None, ""):
        with pytest.raises(HTTPException) as raised:
            await telegram.handle_update(_update(), header)
        assert raised.value.status_code == 403


async def test_a_secret_that_is_a_prefix_of_the_real_one_is_refused(configured):
    """Guards against a comparison that stops at the first difference."""
    with pytest.raises(HTTPException) as raised:
        await telegram.handle_update(_update(), SECRET[:-1])
    assert raised.value.status_code == 403


async def test_nothing_is_dispatched_when_the_secret_fails(configured, _no_real_dispatch):
    for bad in (None, "", "wrong", SECRET + "x"):
        with pytest.raises(HTTPException):
            await telegram.handle_update(_update(), bad)
    assert _no_real_dispatch == []


async def test_a_forged_delivery_is_refused_before_its_body_is_read(configured):
    """Who, then what.

    Nothing was ever *done* with a forged update — the secret check has always
    been there — but it ran after the body had been parsed, so anybody who
    knows the URL could make the function decode however much JSON they liked
    before being turned away. On a platform billed by the millisecond and
    sized by the megabyte, that is the whole cost of the request.
    """
    read = False

    class SpyRequest:
        @staticmethod
        async def json() -> dict:
            nonlocal read
            read = True
            return _update()

    with pytest.raises(HTTPException) as raised:
        await telegram.telegram_webhook(SpyRequest(), "not-the-secret")

    assert raised.value.status_code == 403
    assert read is False, "the stranger's body was parsed before they were refused"


async def test_the_endpoint_refuses_to_work_without_a_configured_secret(monkeypatch):
    """A deployment that forgot the secret must not serve an open endpoint."""
    settings = Settings(
        bot_token="123456:TEST",
        webhook_secret="",
        database_url=get_settings().database_url,
        run_bot=False,
    )
    monkeypatch.setattr(telegram, "get_settings", lambda: settings)

    with pytest.raises(HTTPException) as raised:
        await telegram.handle_update(_update(), "anything")
    assert raised.value.status_code == 404


async def test_webhook_enabled_needs_both_a_token_and_a_secret():
    assert not Settings(bot_token="", webhook_secret="x").webhook_enabled
    assert not Settings(bot_token="t", webhook_secret="").webhook_enabled
    assert Settings(bot_token="t", webhook_secret="x").webhook_enabled


async def test_a_handler_failure_does_not_reject_the_delivery(configured, monkeypatch):
    """Telegram disables a webhook that keeps returning errors, so one bad
    message must not take the bot offline for everyone."""

    class ExplodingDispatcher:
        @staticmethod
        async def feed_update(bot, update):
            raise RuntimeError("handler blew up")

    monkeypatch.setattr(telegram, "_instances", lambda: (object(), ExplodingDispatcher()))

    from fastapi import Request

    scope = {
        "type": "http",
        "method": "POST",
        "path": "/api/v1/telegram/webhook",
        "headers": [],
        "query_string": b"",
    }

    async def receive():
        import json

        return {"type": "http.request", "body": json.dumps(_update()).encode()}

    response = await telegram.telegram_webhook(Request(scope, receive), SECRET)
    assert response == {"ok": True}
