"""The neutral sender: one bot built for one job, and closed after it.

``app.telegram_send`` is what the code outside the bot sends through - the
tick, v1's notices and the self-check's alerts - so that none of them needs
``app.bot`` (``tests/test_service_layering.py``) and none loads aiogram on the
API's cold start (``tests/test_cold_start.py``). What is held here is the
promise every caller leans on: it reports what it delivered, it never raises,
and it closes what it built.
"""

from __future__ import annotations

import logging
from typing import Any

from app import telegram_send
from app.config import get_settings
from app.models import ReminderSettings
from app.services import notify


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
    """``send`` wraps ``build_bot()`` in a ``try`` that reports a refusal as
    ``False`` (see the next test), so a stub that merely raises on a call
    proves nothing about whether the call happened at all - the early
    ``if not bot_token`` return could be deleted and this would still pass.
    ``calls`` is read instead: it is empty only if ``build_bot`` was never
    reached."""
    calls: list[None] = []

    def refuse() -> None:
        calls.append(None)
        raise AssertionError("a bot was built with no token")

    monkeypatch.setattr(get_settings(), "bot_token", "")
    monkeypatch.setattr(telegram_send, "build_bot", refuse)

    assert await telegram_send.send([(1, "раз"), (2, "два")]) == [False, False]
    assert await telegram_send.send([]) == []
    assert calls == []


async def test_a_token_aiogram_will_not_build_a_bot_from_is_a_refusal_not_a_raise(monkeypatch):
    """aiogram refuses a malformed token when the bot is constructed, which
    is the one place a send could raise before its own guard. The real
    factory, so that the refusal is aiogram's own."""
    monkeypatch.setattr(get_settings(), "bot_token", "not-a-token")

    assert await telegram_send.send([(1, "раз")]) == [False]


# ---- the class notice, v2's effect (stage 3b-5) ------------------------------


async def test_a_class_notice_reaches_its_subscribers_but_its_author_and_closes_its_bot(
    session, school_class, monkeypatch
):
    for telegram_id, changes, homework in (
        (7001, True, True),
        (7002, True, False),
        (7003, False, True),
    ):
        session.add(
            ReminderSettings(
                class_id=school_class.id,
                telegram_id=telegram_id,
                notify_changes=changes,
                notify_homework=homework,
            )
        )
    await session.commit()
    bot = _Bot()
    monkeypatch.setattr(get_settings(), "bot_token", "123456:TEST")
    monkeypatch.setattr(telegram_send, "build_bot", lambda: bot)

    told = await telegram_send.notify_class(
        session, school_class, "📝 <b>Алгебра</b>", kind="homework", author=7003
    )

    # Those who asked to hear about homework, all but the one who wrote it.
    assert told == 1
    assert bot.sent == [(7001, "📝 <b>Алгебра</b>")]
    assert bot.closed


async def test_a_class_notice_without_a_token_builds_nothing(session, school_class, monkeypatch):
    """Same reasoning as the `send` test above: ``notify_class`` also catches
    whatever a refusing ``build_bot`` raises, in its own ``try`` around that
    call, so the raise alone cannot tell a missing early return from a caught
    one. ``calls`` is what proves ``build_bot`` was never reached."""
    calls: list[None] = []

    def refuse() -> None:
        calls.append(None)
        raise AssertionError("a bot was built with no token")

    monkeypatch.setattr(get_settings(), "bot_token", "")
    monkeypatch.setattr(telegram_send, "build_bot", refuse)

    told = await telegram_send.notify_class(session, school_class, "x", kind="changes", author=None)
    assert told == 0
    assert calls == []


async def test_a_class_notice_survives_its_recipients_commit_leaving_the_class_expired(
    session, school_class, monkeypatch
):
    """``notify_subscribers`` commits a switch-off of its own when a
    recipient blocked the bot, and a commit that then fails can leave every
    row the session holds expired - ``school_class`` included. Reading
    ``school_class.id`` again afterwards, in a log line, would need a fresh
    SELECT the synchronous ``except`` block cannot await, and raise out of a
    function whose contract is «never raises». The stub reproduces only the
    expiry and a failure, not the real commit; that is enough to make the
    point - and enough to raise, since an expired attribute read outside an
    awaited call is exactly what SQLAlchemy's asyncio extension refuses."""

    async def expires_then_fails(*_args, **_kwargs):
        session.expire(school_class)
        raise RuntimeError("the switch-off commit failed")

    monkeypatch.setattr(notify, "notify_subscribers", expires_then_fails)
    bot = _Bot()
    monkeypatch.setattr(get_settings(), "bot_token", "123456:TEST")
    monkeypatch.setattr(telegram_send, "build_bot", lambda: bot)

    told = await telegram_send.notify_class(
        session, school_class, "x", kind="changes", author=None
    )

    assert told == 0


async def test_a_class_notice_that_fails_is_logged_and_never_raised(
    session, school_class, monkeypatch, caplog
):
    """It runs after the commit: the change is saved, so neither a bot that
    cannot be built nor a notice that fails on the way may reach the caller."""
    with caplog.at_level(logging.WARNING, logger="app.telegram_send"):
        monkeypatch.setattr(get_settings(), "bot_token", "not-a-token")
        unbuilt = await telegram_send.notify_class(
            session, school_class, "x", kind="changes", author=None
        )

        bot = _BotWhoseCloseRaises()
        monkeypatch.setattr(get_settings(), "bot_token", "123456:TEST")
        monkeypatch.setattr(telegram_send, "build_bot", lambda: bot)
        # A kind `notify_subscribers` does not know raises inside it.
        failed = await telegram_send.notify_class(
            session, school_class, "x", kind="news", author=None
        )
    assert (unbuilt, failed) == (0, 0)
    said = [record.getMessage() for record in caplog.records]
    assert any("no bot could be built" in line for line in said)
    assert any(f"could not notify class {school_class.id}" in line for line in said)
    assert any("could not close the bot" in line for line in said)
