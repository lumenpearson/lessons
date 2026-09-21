"""A command typed into an open form breaks out of the form.

These tests go through the **real dispatcher** — `build_dispatcher()`, the one
both polling and the webhook use — rather than calling handlers directly, which
is what the rest of the bot tests do. The defect being held here is about
propagation: `content.router` is included before `week`, so what decided that
«/week» at the homework prompt became homework called «/week» was router order.
Reasoning about router order is exactly how this was got wrong, so it is
measured instead: the message goes in at the top and what comes out of the
fake Telegram session is what the reader would have seen.
"""

from __future__ import annotations

from collections.abc import AsyncGenerator
from datetime import UTC, datetime
from typing import Any

import pytest
from aiogram import Bot, Dispatcher
from aiogram.client.session.base import BaseSession
from aiogram.dispatcher.event.bases import UNHANDLED
from aiogram.fsm.storage.base import StorageKey
from aiogram.methods import TelegramMethod
from aiogram.types import CallbackQuery, Chat, Message, Update
from aiogram.types import User as TgUser
from sqlalchemy import select

from app.bot import states
from app.bot.bot import COMMANDS, build_dispatcher
from app.bot.handlers.unknown import STALE_CARD, UNKNOWN_COMMAND
from app.bot.keyboards import WeekNav
from app.bot.manage_states import EditSubject
from app.bot.middlewares import FORM_DROPPED
from app.db import SessionLocal
from app.fsm_storage import DatabaseStorage
from app.models import BotUser, DayEvent, Homework, Role, SchoolClass

USER_ID = 4242


class FakeSession(BaseSession):
    """Telegram, as far as one test is concerned.

    Records the outgoing methods and answers each with something of the right
    shape. Nothing here talks to the network, and `Bot` is otherwise the real
    one — `message.answer` has to go somewhere, and where it went is the whole
    assertion.
    """

    def __init__(self) -> None:
        super().__init__()
        self.sent: list[TelegramMethod[Any]] = []

    async def close(self) -> None:  # pragma: no cover - nothing to close
        return None

    async def make_request(self, bot: Bot, method: TelegramMethod[Any], timeout: int | None = None):
        self.sent.append(method)
        return Message(
            message_id=1,
            date=datetime.now(UTC),
            chat=Chat(id=USER_ID, type="private"),
            text=getattr(method, "text", None),
        ).as_(bot)

    async def stream_content(  # pragma: no cover - no file is ever downloaded
        self,
        url: str,
        headers: Any = None,
        timeout: int = 30,
        chunk_size: int = 65536,
        raise_for_status: bool = True,
    ) -> AsyncGenerator[bytes, None]:
        yield b""

    @property
    def texts(self) -> list[str]:
        return [text for method in self.sent if (text := getattr(method, "text", None))]


#: One dispatcher for the whole module. `build_router()` returns the handler
#: modules' own router objects, and aiogram refuses to attach a router twice —
#: which is itself a reminder that this is the *one* dispatcher the deployment
#: runs, not a copy of it.
_DISPATCHER: Dispatcher | None = None


def dispatcher() -> Dispatcher:
    global _DISPATCHER
    if _DISPATCHER is None:
        _DISPATCHER = build_dispatcher()
    return _DISPATCHER


@pytest.fixture
def bot() -> Bot:
    return Bot(token="424242:TEST", session=FakeSession())


@pytest.fixture
def sent(bot: Bot) -> FakeSession:
    session = bot.session
    assert isinstance(session, FakeSession)
    return session


def _week_card_drawn(sent: FakeSession) -> bool:
    """Whether «/week» answered.

    Read off the keyboard rather than the text: the card's lessons are those of
    whatever week the test happens to run in, and `schedule.py` draws none at
    all in June — an assertion on «Алгебра» would have passed all year and
    failed every summer. The ‹ Сегодня › pager is under the week card and
    nothing else.
    """
    wanted = WeekNav(offset=0).pack()
    for method in sent.sent:
        rows = getattr(getattr(method, "reply_markup", None), "inline_keyboard", None) or []
        if any(button.callback_data == wanted for row in rows for button in row):
            return True
    return False


def _message(text: str) -> Update:
    return Update(
        update_id=1,
        message=Message(
            message_id=1,
            date=datetime.now(UTC),
            chat=Chat(id=USER_ID, type="private"),
            from_user=TgUser(id=USER_ID, is_bot=False, first_name="Тестер"),
            text=text,
        ),
    )


def _press(data: str) -> Update:
    """A button press, as Telegram delivers one."""
    user = TgUser(id=USER_ID, is_bot=False, first_name="Тестер")
    return Update(
        update_id=2,
        callback_query=CallbackQuery(
            id="press-1",
            from_user=user,
            chat_instance="chat-instance",
            data=data,
            message=Message(
                message_id=7,
                date=datetime.now(UTC),
                chat=Chat(id=USER_ID, type="private"),
                from_user=user,
            ),
        ),
    )


def _key(bot: Bot) -> StorageKey:
    return StorageKey(bot_id=bot.id, chat_id=USER_ID, user_id=USER_ID)


async def _set_state(bot: Bot, state: Any, **data: Any) -> None:
    storage = DatabaseStorage(SessionLocal)
    await storage.set_state(_key(bot), state)
    if data:
        await storage.set_data(_key(bot), data)


async def _state_of(bot: Bot) -> str | None:
    return await DatabaseStorage(SessionLocal).get_state(_key(bot))


@pytest.fixture
async def editor(session, school_class: SchoolClass) -> SchoolClass:
    """An editor of the fixture class, which is what every form here needs."""
    session.add(BotUser(telegram_id=USER_ID, class_id=school_class.id, role=Role.EDITOR))
    await session.commit()
    return school_class


async def test_a_command_at_a_form_step_runs_instead_of_answering_it(bot, sent, editor):
    """«/week» at «Теперь пришлите текст задания:» is a request for the week.

    Before the breakout it was homework: the text «/week», committed, audited
    and announced to every subscriber.
    """
    await _set_state(bot, states.AddHomework.text, due="2026-09-07", subject="Алгебра")

    await dispatcher().feed_update(bot, _message("/week"))

    async with SessionLocal() as db:
        assert (await db.scalars(select(Homework))).all() == []
    assert await _state_of(bot) is None
    assert FORM_DROPPED in sent.texts
    # The week card really was drawn, which is the half a filter alone could
    # not promise: the message has to fall through to a router included later.
    assert _week_card_drawn(sent)


@pytest.mark.parametrize(
    "state",
    [
        states.AddHomework.subject,
        states.AddOverride.subject,
        states.AddEvent.title,
        states.AddTask.text,
    ],
)
async def test_no_form_step_reads_a_command_as_its_answer(bot, sent, editor, state):
    """The four steps named in the report, each on its own.

    They are here by name as well as in the sweep below because each was a
    different kind of damage: homework and a substitution are announced to the
    class, an event is written into the calendar feed, a task is private.
    """
    await _set_state(bot, state, due="2026-09-07", date="2026-09-07", subject="Алгебра")

    await dispatcher().feed_update(bot, _message("/week"))

    assert await _state_of(bot) is None
    assert FORM_DROPPED in sent.texts
    async with SessionLocal() as db:
        assert (await db.scalars(select(Homework))).all() == []
        assert (await db.scalars(select(DayEvent))).all() == []


def _every_state() -> list[Any]:
    """Every state the bot can be sitting in, discovered rather than listed.

    Listed states go stale the moment somebody adds a form; discovered ones
    make the next free-text step fail this test the day it is written, which
    is the point — the rule must not be something a new handler can forget to
    opt into.
    """
    from aiogram.fsm.state import State, StatesGroup

    from app.bot import manage_states

    found: list[Any] = []
    for module in (states, manage_states):
        for name in dir(module):
            group = getattr(module, name)
            if not isinstance(group, type) or not issubclass(group, StatesGroup):
                continue
            found.extend(value for value in vars(group).values() if isinstance(value, State))
    assert len(found) > 20, "the discovery stopped finding the bot's states"
    return found


@pytest.mark.parametrize("state", _every_state(), ids=lambda state: str(state.state))
async def test_a_command_gets_out_of_every_state_there_is(bot, sent, editor, state):
    """Whatever form is open, «/week» answers with the week.

    This is the rule itself, held the way
    `test_no_list_page_draws_a_row_the_keyboard_cannot_reach` holds its one: a
    new free-text step is a new `State`, and a new `State` is a new case here,
    with nothing to remember.
    """
    await _set_state(bot, state, due="2026-09-07", date="2026-09-07", subject="Алгебра")

    await dispatcher().feed_update(bot, _message("/week"))

    assert await _state_of(bot) is None
    assert FORM_DROPPED in sent.texts
    assert _week_card_drawn(sent), f"the week card never came back from {state.state}"


async def test_nothing_is_said_when_no_form_was_open(bot, sent, editor):
    """The line is about something that was dropped, so with nothing to drop
    there is nothing to say."""
    await dispatcher().feed_update(bot, _message("/week"))

    assert FORM_DROPPED not in sent.texts
    assert _week_card_drawn(sent), "the week card should still have been sent"


async def test_a_lone_slash_is_text_and_not_a_command(bot, sent, editor):
    """«/» is not a command Telegram would show, and a form step may be
    answered with one."""
    await _set_state(bot, states.AddHomework.subject)

    await dispatcher().feed_update(bot, _message("/"))

    assert await _state_of(bot) == states.AddHomework.text.state
    assert FORM_DROPPED not in sent.texts


async def test_a_slash_inside_the_text_is_left_alone(bot, sent, editor):
    """«стр. 5, упр. 3/4» is homework, not a command."""
    await _set_state(bot, states.AddHomework.text, due="2026-09-07", subject="Алгебра")

    await dispatcher().feed_update(bot, _message("стр. 5, упр. 3/4"))

    async with SessionLocal() as db:
        written = (await db.scalars(select(Homework))).all()
    assert [row.text for row in written] == ["стр. 5, упр. 3/4"]
    assert FORM_DROPPED not in sent.texts


async def test_a_command_the_bot_does_not_have_still_ends_the_form(bot, sent, editor):
    """«/wek» is somebody reaching for «/week», not the text of an assignment.

    Both lines, in that order: the form really was dropped, and the typo really
    did nothing. Silence here is what this test used to assert, and it was
    wrong the moment a command started closing a form — the reader was left
    holding a closed form with no idea whether the command had done something.
    """
    await _set_state(bot, states.AddHomework.text, due="2026-09-07", subject="Алгебра")

    await dispatcher().feed_update(bot, _message("/wek"))

    async with SessionLocal() as db:
        assert (await db.scalars(select(Homework))).all() == []
    assert await _state_of(bot) is None
    assert sent.texts == [FORM_DROPPED, UNKNOWN_COMMAND]


async def test_a_command_the_bot_does_not_have_says_so_with_no_form_open(bot, sent, editor):
    """And on its own, which is the ordinary case: a typo at the keyboard."""
    await dispatcher().feed_update(bot, _message("/wek"))

    assert sent.texts == [UNKNOWN_COMMAND]


@pytest.mark.parametrize("command", [entry.command for entry in COMMANDS])
async def test_a_command_in_the_menu_is_never_called_unknown(bot, sent, editor, command):
    """Every command BotFather shows has a handler, and now it is checked.

    `COMMANDS` has carried that as a comment since it was written. It matters
    more since the catch-all: it matches *any* command, so a router that stops
    answering one — or is moved below it by somebody tidying the list — turns
    a command in the menu into «не знаю такой команды», which is the worst of
    both. A refusal on grounds of role is a fine answer here; being told the
    command does not exist is not.
    """
    await dispatcher().feed_update(bot, _message(f"/{command}"))

    assert UNKNOWN_COMMAND not in sent.texts, f"/{command} is in the menu and reached the catch-all"


async def test_a_lone_slash_is_not_called_an_unknown_command(bot, sent, editor):
    """The same rule as the middleware's, and the same reason: «/» is text."""
    await dispatcher().feed_update(bot, _message("/"))

    assert sent.texts == []


async def test_a_state_from_the_manage_forms_is_dropped_too(bot, sent, session, school_class):
    """The rule is the dispatcher's, not one router's: «📚 Предметы» lives in
    `manage.py`, which is included last."""
    session.add(BotUser(telegram_id=USER_ID, class_id=school_class.id, role=Role.ADMIN))
    await session.commit()
    await _set_state(bot, EditSubject.create)

    await dispatcher().feed_update(bot, _message("/week"))

    assert await _state_of(bot) is None
    assert FORM_DROPPED in sent.texts


async def test_a_press_on_a_card_the_bot_forgot_still_gets_an_answer(bot, sent):
    """The spinner that never stopped.

    Twelve callback handlers across six modules are filtered on an FSM state
    and have no sibling for the same payload without it, so once the state is
    gone the press matches nothing: aiogram returns `UNHANDLED` without
    raising, `answerCallbackQuery` is never sent, and Telegram spins the button
    until it gives up. `CommandBreakoutMiddleware` is what made that reachable
    — «📝 Задать ДЗ», pick a day, type «/week», and the subject card is left
    above the week with live buttons and no state behind them.

    Driven through the real dispatcher, because what is being held is
    propagation: the catch-all is on the router included last, and a test that
    called the handler directly would prove nothing about whether a press ever
    arrives there.
    """
    result = await dispatcher().feed_update(bot, _press("hw:subject:17"))

    assert result is not UNHANDLED
    answers = [m for m in sent.sent if type(m).__name__ == "AnswerCallbackQuery"]
    assert [a.text for a in answers] == [STALE_CARD]
    assert answers[0].show_alert is True


async def test_a_press_a_handler_owns_never_reaches_the_catch_all(bot, sent):
    """And the other direction, which is what makes the catch-all safe.

    Included last means every real handler is asked first. If that stopped
    being true, this bot would answer «карточка устарела» to working buttons —
    which is worse than the spinner it replaced, and invisible.
    """
    await dispatcher().feed_update(bot, _press(WeekNav(offset=0).pack()))

    # The press is answered by `week`, whatever it decides to say — this user
    # has no class, so that is «сначала подключитесь», not a week card. What
    # matters is only that the answer did not come from the catch-all.
    answers = [m for m in sent.sent if type(m).__name__ == "AnswerCallbackQuery"]
    assert answers, "the press was not answered at all"
    assert STALE_CARD not in [a.text for a in answers]
