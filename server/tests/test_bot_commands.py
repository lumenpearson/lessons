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

from collections.abc import AsyncGenerator, Iterator
from datetime import UTC, datetime
from typing import Any

import pytest
from aiogram import Bot, Dispatcher
from aiogram.client.session.base import BaseSession
from aiogram.dispatcher.event.bases import UNHANDLED
from aiogram.filters import Command
from aiogram.fsm.storage.base import StorageKey
from aiogram.methods import TelegramMethod
from aiogram.types import CallbackQuery, Chat, Message, Update
from aiogram.types import User as TgUser
from sqlalchemy import select

from app.bot import states
from app.bot.bot import COMMANDS, build_dispatcher
from app.bot.content_keyboard import HomeworkTick
from app.bot.handlers.manage import NEED_ADMIN
from app.bot.handlers.start.menu import cmd_start, cmd_start_link, on_contact
from app.bot.handlers.start.onboarding import (
    create_class_letter,
    create_class_school_search,
    create_class_school_typed,
    create_class_timezone,
)
from app.bot.handlers.start.timezone import change_timezone_apply
from app.bot.handlers.unknown import STALE_CARD, UNKNOWN_COMMAND
from app.bot.manage_keyboards.bells import BellsAction
from app.bot.manage_states import EditSubject
from app.bot.middlewares import FORM_DROPPED, looks_like_command
from app.bot.project_keyboard import ProjectAction
from app.bot.week_keyboard import WeekNav
from app.db import SessionLocal
from app.fsm_storage import DatabaseStorage
from app.models import AuditEntry, BotUser, DayEvent, Homework, Role, SchoolClass

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


async def test_a_tick_still_reaches_the_homework_flow(bot, sent, editor):
    """The «сделал» ticks moved from `tasks` into `content/homework.py`. What a
    tick on an assignment that is no longer there answers is the homework
    flow's own sentence, so the press went where it always went — not to the
    catch-all and not to a screen asked earlier."""
    await dispatcher().feed_update(bot, _press(HomeworkTick(action="toggle", value="999").pack()))

    answers = [m for m in sent.sent if type(m).__name__ == "AnswerCallbackQuery"]
    assert [a.text for a in answers] == ["Это задание уже удалено"]


@pytest.mark.parametrize(
    ("role", "drawn"), [(Role.VIEWER, False), (Role.ADMIN, True)], ids=["viewer", "admin"]
)
async def test_the_role_decorator_answers_through_the_dispatcher(
    bot, sent, session, school_class, role, drawn
):
    """«⚙️ Класс» checks roles with `@needs` on each handler (#207), and the
    handler tests call the handlers directly, so this is the half they cannot
    see: aiogram reads what to inject off the function the decorator wraps,
    and a refusal has to be answered by the handler that matched rather than
    handed on to the catch-all. An admin gets the page, which is proof the
    wrapped handler received its session, class, role and state; a наблюдатель
    gets the refusal as an alert, and nothing drawn.
    """
    session.add(BotUser(telegram_id=USER_ID, class_id=school_class.id, role=role))
    await session.commit()

    result = await dispatcher().feed_update(bot, _press(BellsAction(action="list").pack()))

    assert result is not UNHANDLED
    answers = [m for m in sent.sent if type(m).__name__ == "AnswerCallbackQuery"]
    assert len(answers) == 1, "the press was answered more than once, or not at all"
    edits = [m for m in sent.sent if type(m).__name__ == "EditMessageText"]
    if drawn:
        assert edits and "Расписания звонков" in edits[0].text
        assert not answers[0].show_alert
    else:
        assert edits == []
        assert answers[0].text == NEED_ADMIN
        assert answers[0].show_alert is True


async def test_a_refused_form_step_through_the_dispatcher_drops_the_form_quietly(
    bot, sent, session, school_class
):
    """The other shape of the same refusal: text typed into a form this person
    has no right to have open. FSM state is per-user, so a client can put
    itself into «Новое название предмета» - and the answer is to drop the form
    and say nothing, exactly as the check at the top of the step did by hand."""
    session.add(BotUser(telegram_id=USER_ID, class_id=school_class.id, role=Role.VIEWER))
    await session.commit()
    await _set_state(bot, EditSubject.create)

    await dispatcher().feed_update(bot, _message("Физика"))

    assert await _state_of(bot) is None
    assert sent.texts == []
    async with SessionLocal() as db:
        assert (await db.scalars(select(AuditEntry))).all() == []


# --------------------------------------------------------------------------
# What counts as a command: aiogram's answer, not a second one
# --------------------------------------------------------------------------


async def test_a_command_with_a_bare_mention_gets_out_of_the_form(bot, sent, editor):
    """«/week@» is «/week» to aiogram, so it has to be a command to the breakout.

    aiogram's `Command` reads an empty mention as no mention and hands «/week@»
    to the week. The breakout read it as text, so the form stayed open, and the
    step - whose router is included before the week's - took «/week@» as the
    assignment: committed, audited and announced (#276).
    """
    await _set_state(bot, states.AddHomework.text, due="2026-09-07", subject="Алгебра")

    await dispatcher().feed_update(bot, _message("/week@"))

    async with SessionLocal() as db:
        assert (await db.scalars(select(Homework))).all() == []
    assert await _state_of(bot) is None
    assert FORM_DROPPED in sent.texts
    assert _week_card_drawn(sent)


#: The username `Command` is told this bot has, when a text mentions one.
_BOT_USERNAME = "lessons_test_bot"


class _BotNamed:
    """All that `Command` asks of a bot, and only when the text carries a
    mention: who it is. The `bot` fixture would send getMe through the fake
    session, which answers every method with a message."""

    def __init__(self, username: str) -> None:
        self.username = username

    async def me(self) -> TgUser:
        return TgUser(id=1, is_bot=True, first_name="Дневник", username=self.username)


def _command_filters() -> list[Command]:
    """Every `Command` filter the dispatcher runs, `CommandStart` included.

    Discovered, as the states are above, so a command added tomorrow joins the
    comparison without anybody listing it.
    """

    def walk(router: Any) -> Iterator[Command]:
        for handler in router.observers["message"].handlers:
            for found in handler.filters or ():
                if isinstance(found.callback, Command):
                    yield found.callback
        for child in router.sub_routers:
            yield from walk(child)

    filters = list(walk(dispatcher()))
    assert len(filters) > 20, "the discovery stopped finding the bot's commands"
    return filters


def _as_message(text: str) -> Message:
    return Message(
        message_id=1,
        date=datetime.now(UTC),
        chat=Chat(id=USER_ID, type="private"),
        text=text,
    )


async def _aiogram_dispatches(text: str) -> bool:
    """Whether aiogram itself hands this text to one of this bot's commands.

    Asked of the real filters, with aiogram's own parser, rather than of a
    restatement of it: a restatement is how the breakout came to disagree.
    """
    message = _as_message(text)
    bot: Any = _BotNamed(_BOT_USERNAME)
    for command in _command_filters():
        if await command(message, bot):
            return True
    return False


#: Texts on which the breakout and aiogram must give one answer: a command to
#: this bot closes the form, and anything else may be the form's answer.
_SAME_ANSWER = [
    ("/week", True),
    ("/week на завтра", True),
    # #276: aiogram reads an empty mention as no mention at all.
    ("/week@", True),
    ("/week@ на завтра", True),
    ("/week@\nна завтра", True),
    ("/homework@", True),
    ("/start@", True),
    (f"/week@{_BOT_USERNAME}", True),
    (f"/week@{_BOT_USERNAME.upper()}", True),
    # aiogram splits on any whitespace before it looks, so what comes before
    # the slash, and a no-break space after the name, are whitespace to it.
    (" /week", True),
    ("\n/week", True),
    ("/week на завтра", True),
    # A mention that is no username: aiogram compares it with this bot's and
    # refuses, and nothing else would take it.
    ("/week@@", False),
    ("/week@@ на завтра", False),
    (f"/week@{_BOT_USERNAME}@", False),
    ("/week@.", False),
    (f"/@{_BOT_USERNAME}", False),
    ("/week,", False),
    ("/недели", False),
    ("/ week", False),
    ("/", False),
    ("week", False),
    ("", False),
    ("стр. 5, упр. 3/4", False),
]


@pytest.mark.parametrize(("text", "is_command"), _SAME_ANSWER, ids=repr)
async def test_the_breakout_reads_a_command_as_aiogram_does(text, is_command):
    """What closes a form is what aiogram would dispatch as a command.

    Wherever the two disagree, one of two things goes wrong. A text aiogram
    dispatches and the breakout does not is taken by the open step as its
    answer, which is #276. The reverse closes a form on text that was meant as
    its answer, which is what «/ 5 стр» must never do.
    """
    assert await _aiogram_dispatches(text) is is_command, "the table is wrong about aiogram"
    assert looks_like_command(text) is is_command


#: Texts the breakout counts as commands and this bot does not answer - wider
#: on purpose, and only in *which* command and *which* bot, never in shape.
_WIDER_ON_PURPOSE = [
    # A typo for «/week»: the form closes and the catch-all says so.
    "/wek",
    "/wek@",
    # aiogram compares names as written, and the menu only ever sends lower case.
    "/Week",
    # A command to another bot is still somebody addressing a bot.
    "/week@other_bot",
]


@pytest.mark.parametrize("text", _WIDER_ON_PURPOSE, ids=repr)
async def test_the_breakout_is_wider_than_this_bot_only_in_name_and_addressee(text):
    """Each of these is a command that aiogram would dispatch, to a bot with
    that command and that username, and only this bot's list says no."""
    assert looks_like_command(text)
    assert not await _aiogram_dispatches(text)

    named = Command.extract_command(text)
    elsewhere: Any = _BotNamed(named.mention or _BOT_USERNAME)
    assert await Command(named.command)(_as_message(text), elsewhere)


# --------------------------------------------------------------------------
# Which of two handlers an update reaches
# --------------------------------------------------------------------------


def _offered(observer: str) -> list[Any]:
    """Every handler on ``observer``, in the order the dispatcher offers an
    update to them.

    A router asks its own handlers first, in registration order, then each
    router included under it, in include order — depth first (aiogram's
    ``Router._propagate_event``). A module split into a package of routers
    keeps every update where it went only while this order holds.
    """

    def walk(router: Any) -> Iterator[Any]:
        yield from (handler.callback for handler in router.observers[observer].handlers)
        for child in router.sub_routers:
            yield from walk(child)

    return list(walk(dispatcher()))


#: Handlers that can both match one update, the one that must win first.
#: Nothing but registration order tells each pair apart — the shape «Two
#: screens can match one press» in CLAUDE.md warns against — so they are held
#: here, where splitting their module into several routers would otherwise
#: reorder them without a word.
_FIRST_WINS = [
    # «/start link_…» is a «/start» too: the bare handler would open the menu
    # instead of linking the phone.
    ("message", cmd_start_link, cmd_start),
    # A shared contact while the wizard waits for text: its steps are
    # filtered by state alone and would take the contact as the answer.
    ("message", on_contact, create_class_letter),
    ("message", on_contact, create_class_school_search),
    ("message", on_contact, create_class_school_typed),
    # Both take a TimezonePick, and only the wizard's state tells them apart:
    # the class card's handler would answer the wizard's last question as a
    # change to an existing class — a refusal, for somebody who has none yet.
    ("callback_query", create_class_timezone, change_timezone_apply),
]


@pytest.mark.parametrize(
    ("observer", "first", "then"),
    _FIRST_WINS,
    ids=[f"{first.__name__}-before-{then.__name__}" for _, first, then in _FIRST_WINS],
)
def test_of_two_handlers_for_one_update_the_right_one_is_asked_first(observer, first, then):
    offered = _offered(observer)

    assert offered.index(first) < offered.index(then), (
        f"{then.__module__}.{then.__name__} is now offered {observer} updates before "
        f"{first.__module__}.{first.__name__}"
    )


# --------------------------------------------------------------------------
# «📊 Проект»: the deployment owner's screen, and an unknown command to anybody else
# --------------------------------------------------------------------------

#: OWNER_IDS in conftest: the deployment's owner, who is nobody's member here.
DEPLOYMENT_OWNER = 1000


def _message_from(user_id: int, text: str) -> Update:
    return Update(
        update_id=3,
        message=Message(
            message_id=1,
            date=datetime.now(UTC),
            chat=Chat(id=user_id, type="private"),
            from_user=TgUser(id=user_id, is_bot=False, first_name="Владелец"),
            text=text,
        ),
    )


def _press_from(user_id: int, data: str) -> Update:
    user = TgUser(id=user_id, is_bot=False, first_name="Владелец")
    return Update(
        update_id=4,
        callback_query=CallbackQuery(
            id="press-2",
            from_user=user,
            chat_instance="chat-instance",
            data=data,
            message=Message(
                message_id=7,
                date=datetime.now(UTC),
                chat=Chat(id=user_id, type="private"),
                from_user=user,
            ),
        ),
    )


@pytest.mark.parametrize("command", ["/project", "/health"])
async def test_the_project_is_an_unknown_command_to_a_class_s_own_owner(
    bot, sent, session, school_class, command
):
    """A class's owner is not the deployment's: the screen sums every class
    and shows the infrastructure. To them the command answers as any command
    the bot does not have answers, so nothing says the screen exists."""
    session.add(BotUser(telegram_id=USER_ID, class_id=school_class.id, role=Role.OWNER))
    await session.commit()

    await dispatcher().feed_update(bot, _message(command))

    assert sent.texts == [UNKNOWN_COMMAND]


async def test_the_deployment_s_owner_gets_the_project_and_its_state(bot, sent):
    await dispatcher().feed_update(bot, _message_from(DEPLOYMENT_OWNER, "/project"))
    await dispatcher().feed_update(bot, _message_from(DEPLOYMENT_OWNER, "/health"))

    project, state = sent.texts
    assert project.startswith("<b>📊 Проект</b>")
    assert "<b>🖥 Сервер</b>" in project
    assert state.startswith("<b>🩺 Состояние</b>")
    assert "<b>🖥 Сервер</b>" not in state


async def test_the_project_button_opens_for_the_deployment_s_owner_alone(bot, sent):
    press = ProjectAction(action="open").pack()

    await dispatcher().feed_update(bot, _press(press))
    answers = [m for m in sent.sent if type(m).__name__ == "AnswerCallbackQuery"]
    assert [a.text for a in answers] == [STALE_CARD]
    assert [m for m in sent.sent if type(m).__name__ == "EditMessageText"] == []

    await dispatcher().feed_update(bot, _press_from(DEPLOYMENT_OWNER, press))
    edits = [m for m in sent.sent if type(m).__name__ == "EditMessageText"]
    assert len(edits) == 1 and edits[0].text.startswith("<b>📊 Проект</b>")
