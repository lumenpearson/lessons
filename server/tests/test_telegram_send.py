"""The neutral sender: one bot built for one job, and closed after it.

``app.telegram_send`` is what the code outside the bot sends through - the
tick, v1's notices and the self-check's alerts - so that none of them needs
``app.bot`` (``tests/test_service_layering.py``) and none loads aiogram on the
API's cold start (``tests/test_cold_start.py``). What is held here is the
promise every caller leans on: it reports what it delivered, it never raises,
and it closes what it built.
"""

from __future__ import annotations

from typing import Any

from app import telegram_send
from app.config import get_settings


class _Bot:
    """A bot that refuses the ids it is told to, and says whether it was closed."""

    def __init__(self, refuses: set[int] | None = None) -> None:
        self.refuses = refuses or set()
        self.sent: list[tuple[int, str]] = []
        self.closed = False

    async def send_message(self, chat_id: int, text: str, **_: Any) -> None:
        if chat_id in self.refuses:
            raise RuntimeError("Forbidden: bot was blocked by the user")
        self.sent.append((chat_id, text))

    @property
    def session(self) -> _Bot:
        return self

    async def close(self) -> None:
        self.closed = True


class _BotWhoseCloseRaises(_Bot):
    """Delivers normally, then raises closing its session - a close after a
    real send, not a send that failed."""

    async def close(self) -> None:
        raise RuntimeError("session already closed")


def test_the_bot_package_builds_its_bot_here():
    """One factory, imported back rather than copied: a copy is a second
    definition free to drift from the first (a parse mode, a token)."""
    from app.bot import bot as bot_module

    assert bot_module.build_bot is telegram_send.build_bot


async def test_a_send_says_what_each_message_did_and_closes_its_bot(monkeypatch):
    bot = _Bot(refuses={2})
    monkeypatch.setattr(get_settings(), "bot_token", "123456:TEST")
    monkeypatch.setattr(telegram_send, "build_bot", lambda: bot)

    delivered = await telegram_send.send([(1, "раз"), (2, "два"), (3, "три")])

    assert delivered == [True, False, True]
    assert bot.sent == [(1, "раз"), (3, "три")]
    assert bot.closed


async def test_a_bot_whose_close_raises_still_returns_the_delivered_flags(monkeypatch):
    """A close that fails after delivery must not read, to the caller, as the
    send itself having failed - ``health._tell`` would give a «down» alert's
    claim back, and the next tick would send it to an owner who has it."""
    bot = _BotWhoseCloseRaises(refuses={2})
    monkeypatch.setattr(get_settings(), "bot_token", "123456:TEST")
    monkeypatch.setattr(telegram_send, "build_bot", lambda: bot)

    delivered = await telegram_send.send([(1, "раз"), (2, "два"), (3, "три")])

    assert delivered == [True, False, True]
    assert bot.sent == [(1, "раз"), (3, "три")]


async def test_without_a_token_nothing_is_built_and_nothing_is_sent(monkeypatch):
    def refuse() -> None:
        raise AssertionError("a bot was built with no token")

    monkeypatch.setattr(get_settings(), "bot_token", "")
    monkeypatch.setattr(telegram_send, "build_bot", refuse)

    assert await telegram_send.send([(1, "раз"), (2, "два")]) == [False, False]
    assert await telegram_send.send([]) == []


async def test_a_token_aiogram_will_not_build_a_bot_from_is_a_refusal_not_a_raise(monkeypatch):
    """aiogram refuses a malformed token when the bot is constructed, which
    is the one place a send could raise before its own guard. The real
    factory, so that the refusal is aiogram's own."""
    monkeypatch.setattr(get_settings(), "bot_token", "not-a-token")

    assert await telegram_send.send([(1, "раз")]) == [False]
