"""What the bot pushes to a lock screen when somebody writes to the class.

`notify.shorten` bounds the one part of an announcement that grows with what
was typed. The rule lived in `bot/handlers/content` only, so an assignment
typed into the bot arrived cut to 200 characters and the same assignment saved
from a phone arrived whole — the 4000 `HomeworkIn.text` accepts, which is an
unreadable lock screen below Telegram's ceiling and nothing at all above it,
because every recipient's send raises and both callers swallow it.

The test that closed that asserted `len(shorten("я" * 4000)) == NOTIFY_TEXT_MAX`
and nothing else. It held the helper, not the rule: removing `shorten` from
both call sites at once left the whole suite green. So this file presses the
handlers, reads what the *subscriber* was sent, and measures the one thing the
rule is about.

**The measurement is a difference, not a number.** Each announcement is sent
twice — once with a one-character field, once with four thousand — and what is
asserted is how much the second grew. Comparing against a written-down total
would mean copying the fixed part of every line into this file and updating it
whenever the wording changes; the difference is the typed field and nothing
else.

What is held is therefore the property, not one line of code. Two of the three
bot announcements are bounded twice over — the substitution cuts its subject to
120 and the event its title to 200 on the way in, *and* pass it through
`shorten` on the way out — so removing either alone leaves the push bounded and
these tests green, which is the right answer. Remove every bound and all four
go red.
"""

from __future__ import annotations

import ast
from dataclasses import dataclass, field
from datetime import date as Date
from datetime import timedelta
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import delete as sa_delete

from app.api import edit
from app.bot.handlers.content import (
    event_title,
    homework_text,
    override_cancel,
    override_clear,
    override_subject,
)
from app.config import get_settings
from app.main import app
from app.models import BotUser, DeviceToken, Homework, ReminderSettings, Role
from app.services import linking, notify

APP_ROOT = Path(__file__).resolve().parent.parent / "app"

#: The editor doing the writing, and the classmate who hears about it. They
#: have to differ: every announcement excludes its own author.
AUTHOR_ID = 7001
SUBSCRIBER_ID = 7002

#: Longer than anything a person types and longer than every cap in the
#: project, but still under Telegram's 4096 — so an unbounded announcement
#: sends, and only the difference below can tell it apart from a bounded one.
FLOOD = "я" * 4000


@dataclass
class _Bot:
    """Records what the class was actually sent."""

    sent: list[tuple[int, str]] = field(default_factory=list)

    async def send_message(self, chat_id: int, text: str, **_: Any) -> None:
        self.sent.append((chat_id, text))

    @property
    def session(self) -> _Bot:
        return self

    async def close(self) -> None:
        return None


@dataclass
class _Press:
    """A Message or a CallbackQuery from the author, carrying a bot to push with."""

    text: str | None = ""
    bot: Any = None
    replies: list[str] = field(default_factory=list)

    @property
    def from_user(self):
        return SimpleNamespace(id=AUTHOR_ID, username="editor", full_name="Редактор")

    @property
    def message(self):
        return self

    async def answer(self, text: str | None = None, **_: Any) -> None:
        if text is not None:
            self.replies.append(text)

    async def edit_text(self, text: str, **_: Any) -> None:
        self.replies.append(text)


@dataclass
class _State:
    data: dict[str, Any] = field(default_factory=dict)

    async def get_data(self) -> dict[str, Any]:
        return dict(self.data)

    async def update_data(self, **kwargs: Any) -> dict[str, Any]:
        self.data.update(kwargs)
        return dict(self.data)

    async def set_state(self, state: Any) -> None:
        return None

    async def clear(self) -> None:
        self.data.clear()


@pytest.fixture
async def subscribed(session, school_class):
    """A classmate who asked to hear about both kinds of change."""
    session.add(
        ReminderSettings(
            class_id=school_class.id,
            telegram_id=SUBSCRIBER_ID,
            notify_changes=True,
            notify_homework=True,
        )
    )
    session.add(BotUser(telegram_id=AUTHOR_ID, class_id=school_class.id, role=Role.EDITOR))
    await session.commit()


def _tomorrow(school_class) -> Date:
    """A date the class rings, so «урок №1» exists and the write is accepted."""
    from datetime import datetime

    day = datetime.now(school_class.tz).date()
    while day.isoweekday() != 1:
        day += timedelta(days=1)
    return day


async def _pushed(bot: _Bot) -> str:
    assert bot.sent, "nobody was told, so this test measured nothing"
    chat_id, text = bot.sent[-1]
    assert chat_id == SUBSCRIBER_ID
    return text


# --------------------------------------------------------------------------
# The bot shell
# --------------------------------------------------------------------------


async def _announce_homework(session, school_class, text: str) -> str:
    # Each press has to be the *first* write for that day. «📝 Новое задание»
    # and «📝 Обновлено задание» differ by three characters, and the difference
    # this file measures is supposed to be the free-text field and nothing
    # else — an upsert on the second press would put the wording in it too.
    await session.execute(sa_delete(Homework).where(Homework.class_id == school_class.id))
    await session.commit()

    bot = _Bot()
    await homework_text(
        _Press(text=text, bot=bot),
        _State(data={"due": _tomorrow(school_class).isoformat(), "subject": "Алгебра"}),
        session,
        school_class,
        Role.EDITOR,
    )
    return await _pushed(bot)


async def _announce_substitution(session, school_class, subject: str) -> str:
    bot = _Bot()
    await override_subject(
        _Press(text=subject, bot=bot),
        _State(data={"date": _tomorrow(school_class).isoformat(), "index": "1"}),
        session,
        school_class,
        Role.EDITOR,
    )
    return await _pushed(bot)


async def _announce_event(session, school_class, title: str) -> str:
    bot = _Bot()
    await event_title(
        _Press(text=title, bot=bot),
        _State(
            data={
                "date": _tomorrow(school_class).isoformat(),
                "kind": "event",
                "start": "09:00:00",
                "end": "10:00:00",
            }
        ),
        session,
        school_class,
        Role.EDITOR,
    )
    return await _pushed(bot)


@pytest.mark.parametrize(
    ("what", "announce"),
    [
        ("задание", _announce_homework),
        ("замена", _announce_substitution),
        ("событие", _announce_event),
    ],
)
async def test_what_the_bot_pushes_grows_by_no_more_than_the_cap(
    session, school_class, subscribed, what, announce
):
    """Type four thousand characters where the person types five.

    The difference between the two announcements is the typed field and nothing
    else, so it is exactly what the caps are supposed to bound. Unbounded, the
    difference is 3999: a paragraph nobody can read on a lock screen — and one
    «<» further up would have taken it past 4096, where it is not a push at
    all, because `notify_subscribers` swallows every recipient's failure.
    """
    short = await announce(session, school_class, "я")
    flooded = await announce(session, school_class, FLOOD)

    grew_by = len(flooded) - len(short)
    assert grew_by <= notify.NOTIFY_TEXT_MAX, (
        f"«{what}» pushed {grew_by} characters of free text, cap is "
        f"{notify.NOTIFY_TEXT_MAX}"
    )
    assert FLOOD not in flooded


async def test_an_announcement_with_no_free_text_in_it_still_reaches_the_class(
    session, school_class, subscribed
):
    """The two that carry no typed string, pressed so that the list below is
    complete rather than complete-except-for-the-boring-ones.

    They are bounded by construction — a lesson number and a date — and that is
    the claim: if one of them ever grows a field, this file is where it has to
    be answered for.
    """
    day = _tomorrow(school_class)
    state = _State(data={"date": day.isoformat(), "index": "1"})

    cancel_bot = _Bot()
    await override_cancel(
        _Press(bot=cancel_bot), state, session, school_class, Role.EDITOR
    )
    cancelled = await _pushed(cancel_bot)

    clear_bot = _Bot()
    await override_clear(
        _Press(bot=clear_bot),
        _State(data={"date": day.isoformat(), "index": "1"}),
        session,
        school_class,
        Role.EDITOR,
    )
    restored = await _pushed(clear_bot)

    assert "№1" in cancelled and "№1" in restored
    assert len(cancelled) < 200 and len(restored) < 200


# --------------------------------------------------------------------------
# The API shell — the same assignment, saved from a phone
# --------------------------------------------------------------------------


@pytest.fixture
async def api(session, school_class, subscribed, monkeypatch):
    """A linked editor's phone, and the bot the write endpoint notifies with."""
    bot = _Bot()
    monkeypatch.setattr(get_settings(), "bot_token", "123456:TEST")
    monkeypatch.setattr(edit, "_build_bot", lambda: bot)

    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        joined = await client.post(
            "/api/v1/join", json={"code": "TEST42", "device_name": "pytest"}
        )
        assert joined.status_code == 200, joined.text
        token = joined.json()["token"]

        me = await client.get("/api/v1/me", headers={"Authorization": f"Bearer {token}"})
        device = await linking.link_device(session, me.json()["link_code"], AUTHOR_ID)
        assert isinstance(device, DeviceToken)

        yield SimpleNamespace(client=client, token=token, bot=bot)


async def test_what_the_api_pushes_grows_by_no_more_than_the_cap(
    api, session, school_class
):
    """The other shell, on the same assignment.

    `HomeworkIn.text` accepts 4000 characters and the column caps at nothing,
    so this is input the endpoint already takes — not a hypothetical.
    """
    due = _tomorrow(school_class).isoformat()

    async def save(text: str) -> str:
        response = await api.client.put(
            "/api/v1/homework",
            json={"due_date": due, "subject": "Алгебра", "text": text},
            headers={"Authorization": f"Bearer {api.token}"},
        )
        assert response.status_code == 200, response.text
        return await _pushed(api.bot)

    short = await save("я")
    flooded = await save(FLOOD)

    assert len(flooded) - len(short) <= notify.NOTIFY_TEXT_MAX
    assert FLOOD not in flooded


# --------------------------------------------------------------------------
# And nothing announces without being read here
# --------------------------------------------------------------------------

#: Every place in `app/` that pushes to the class, and what bounds the part of
#: it that a person typed.
#:
#: Written down with a reason each, because the reason is what a reviewer has
#: to agree with; the list itself is derived from the source by the test below
#: and fails naming anything that is not here. Adding a ninth announcement is
#: then a decision — measure it, or say in one line what already bounds it.
ANNOUNCED_HERE = {
    "api/edit.py:_tell": "the wrapper the endpoints below announce through; no text of its own",
    "api/edit.py:homework_put": "measured below — `HomeworkIn.text` accepts 4000",
    "api/edit.py:homework_delete": "a subject name, `max_length=120`",
    "api/edit.py:override_put": "subject/room/teacher 120 each and `note` 500, all schema-capped",
    "api/edit.py:event_put": "`title` 200 and `location` 120, both schema-capped",
    "api/edit.py:event_delete": "a stored title, `max_length=200`",
    "api/edit.py:day_put": "`DayIn.note`, `max_length=500`",
    "bot/handlers/content.py:homework_text": "measured below — free text, only `shorten`",
    "bot/handlers/content.py:override_subject": "measured below — a subject cut to 120 going in",
    "bot/handlers/content.py:event_title": "measured below — a title cut to 200 on the way in",
    "bot/handlers/content.py:override_cancel": "pressed below — a lesson number and a date",
    "bot/handlers/content.py:override_clear": "pressed below — a lesson number and a date",
}


def _announcing_call_sites() -> set[str]:
    """Every function in `app/` that pushes to the class, directly or through a
    wrapper in its own file.

    One file's worth of indirection, and no more: `api/edit.py` announces
    through its own ``_tell``, so a walk that only looked for
    ``notify_subscribers`` would name the wrapper and miss all seven endpoints
    behind it. Resolving names across files instead would start matching any
    function that happens to share a name with a wrapper somewhere else.
    """
    found: set[str] = set()
    for path in sorted(APP_ROOT.rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"), str(path))

        calls: dict[str, set[str]] = {}
        for node in ast.walk(tree):
            if not isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef):
                continue
            named = set()
            for inner in ast.walk(node):
                if isinstance(inner, ast.Call):
                    func = inner.func
                    named.add(
                        func.attr if isinstance(func, ast.Attribute) else getattr(func, "id", "")
                    )
            calls[node.name] = named

        announcing = {"notify_subscribers"}
        while True:
            grown = {name for name, named in calls.items() if named & announcing}
            if grown <= announcing:
                break
            announcing |= grown

        where = path.relative_to(APP_ROOT).as_posix()
        # Its own definition is not a call site; every caller of it is.
        callers = (announcing & set(calls)) - {"notify_subscribers"}
        found |= {f"{where}:{name}" for name in callers}
    return found


def test_every_place_that_pushes_to_the_class_is_read_in_this_file():
    """The list above, derived rather than trusted.

    The rule that was missing was missing from *one* of two call sites, and the
    test that was supposed to hold it named neither. A list written by hand
    goes stale the first time somebody adds an announcement; this one cannot,
    because it is compared against the source on every run.
    """
    real = _announcing_call_sites()

    assert real, "found no notify_subscribers call at all — the AST walk is broken"
    unread = real - set(ANNOUNCED_HERE)
    assert not unread, (
        "these push to the class and nothing here measures them or says what bounds them: "
        + ", ".join(sorted(unread))
    )
    gone = set(ANNOUNCED_HERE) - real
    assert not gone, "ANNOUNCED_HERE names call sites that no longer exist: " + ", ".join(
        sorted(gone)
    )
