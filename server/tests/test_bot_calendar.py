"""The month calendar and the day card it opens.

The grid is a pure function of a year, a month and the class's today, so its
shape is tested without a database; the card and the role gate go through the
handlers with the same stubs the rest of the bot tests use.
"""

from __future__ import annotations

from datetime import date
from types import SimpleNamespace
from typing import Any

import pytest
from test_bot_handlers import FakeState

from app.bot.button_style import DANGER, SUCCESS
from app.bot.calendar_keyboard import (
    CalendarAction,
    clamp_month,
    day_card_keyboard,
    month_grid,
    month_keyboard,
    pickable_months,
)
from app.bot.handlers import calendar as handlers
from app.bot.handlers import content
from app.bot.handlers.calendar import calendar_card, calendar_nav, calendar_open, cmd_day
from app.bot.handlers.content import homework_pick_day
from app.bot.keyboards import EventAction, HomeworkAction
from app.bot.keyboards import OverrideAction as OverrideCB
from app.models import DayKind, DayOverride, Role

# A Sunday in the middle of the 2026/27 school year, so every month of that
# year is reachable from it.
TODAY = date(2026, 9, 13)


class FakeEditable:
    """A message that remembers the last text and keyboard it was given."""

    def __init__(self) -> None:
        self.texts: list[str] = []
        self.markups: list[Any] = []

    async def answer(self, text: str, reply_markup: Any = None, **_: Any) -> None:
        self.texts.append(text)
        self.markups.append(reply_markup)

    edit_text = answer

    async def edit_reply_markup(self, reply_markup: Any = None, **_: Any) -> None:
        self.texts.append(self.texts[-1] if self.texts else "")
        self.markups.append(reply_markup)

    @property
    def last(self) -> str:
        return self.texts[-1]

    @property
    def keyboard(self) -> Any:
        return self.markups[-1]


class FakeCallback:
    def __init__(self, message: FakeEditable | None = None, user_id: int = 42) -> None:
        self.message = message or FakeEditable()
        self.user_id = user_id
        self.answers: list[tuple[str | None, bool]] = []

    @property
    def from_user(self):
        return SimpleNamespace(id=self.user_id, username="tester", full_name="Тестер")

    async def answer(self, text: str | None = None, show_alert: bool = False, **_: Any) -> None:
        self.answers.append((text, show_alert))

    @property
    def alerted(self) -> bool:
        return any(alert for _, alert in self.answers)


def labels(keyboard) -> list[str]:
    return [button.text for row in keyboard.inline_keyboard for button in row]


def payloads(keyboard) -> list[str]:
    return [button.callback_data for row in keyboard.inline_keyboard for button in row]


# --------------------------------------------------------------------------
# The grid
# --------------------------------------------------------------------------


def test_a_month_that_starts_on_a_sunday_is_padded_with_six_blanks():
    """February 2026 begins on a Sunday — the worst case for a Monday-first
    grid, and the one a naive «start at the 1st» layout gets wrong."""
    weeks = month_grid(2026, 2)

    assert weeks[0] == [None, None, None, None, None, None, 1]
    assert weeks[-1] == [23, 24, 25, 26, 27, 28, None]
    assert all(len(week) == 7 for week in weeks)
    assert [day for week in weeks for day in week if day] == list(range(1, 29))


def test_february_of_a_leap_year_keeps_its_twenty_ninth():
    weeks = month_grid(2028, 2)
    days = [day for week in weeks for day in week if day]

    assert days == list(range(1, 30))
    assert weeks[0][:1] == [None] and weeks[0][1] == 1  # 1 February 2028 is a Tuesday


def test_the_grid_names_the_month_labels_the_weekdays_and_marks_today():
    keyboard = month_keyboard("day", 2026, 9, TODAY)
    rows = keyboard.inline_keyboard

    assert [button.text for button in rows[0]] == ["Сентябрь 2026"]
    assert [button.text for button in rows[1]] == ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"]

    # Today is the green one and is written like every other day. It used to be
    # the one in guillemets, which cost the cell a character of width in a grid
    # whose columns are supposed to line up.
    greens = {button.text for row in rows for button in row if button.style == SUCCESS}
    assert greens == {"13", "Сегодня"}
    assert "«13»" not in labels(keyboard)


def test_the_only_paint_on_a_month_is_now_and_the_way_out():
    """Colour only separates while most of the keyboard is plain.

    A month is forty-odd buttons. The arrows are deliberately *not* among the
    painted ones: they sit under the thumb in a grid where every other cell is
    grey, and a blue pair there was the loudest thing on a screen whose point
    is the green day. Which leaves two — green for now, red for the way out —
    and forty buttons of background to read them against.
    """
    rows = month_keyboard("day", 2026, 10, TODAY).inline_keyboard
    styles = [button.style for row in rows for button in row]

    assert {button.text for row in rows for button in row if button.style == DANGER} == {
        "Отмена"
    }
    assert styles.count(SUCCESS) == 1  # «Сегодня»; October holds no today
    arrows = [button for row in rows for button in row if button.text in ("‹", "›")]
    assert len(arrows) == 2
    assert all(button.style is None for button in arrows)


def test_a_day_button_opens_the_card_and_the_homework_grid_reuses_its_handler():
    """The calendar adds a way in, not a second «добавить ДЗ»: its day buttons
    carry the payload the homework flow's own handler is registered for."""
    card_day = next(
        button
        for row in month_keyboard("day", 2026, 9, TODAY).inline_keyboard
        for button in row
        if button.text == "13"
    )
    assert CalendarAction.unpack(card_day.callback_data).action == "card"

    homework_day = next(
        button
        for row in month_keyboard("hw", 2026, 9, TODAY).inline_keyboard
        for button in row
        if button.text == "13"
    )
    unpacked = HomeworkAction.unpack(homework_day.callback_data)
    assert (unpacked.action, unpacked.value) == ("pick_day", "2026-09-13")


# --------------------------------------------------------------------------
# Navigation bounds
# --------------------------------------------------------------------------


def test_the_school_year_is_the_one_today_falls_in():
    assert pickable_months(date(2026, 9, 1)) == (date(2026, 9, 1), date(2027, 8, 1))
    assert pickable_months(date(2026, 6, 15)) == (date(2025, 9, 1), date(2026, 8, 1))
    assert pickable_months(date(2027, 1, 3)) == (date(2026, 9, 1), date(2027, 8, 1))


def test_the_arrows_stop_at_both_ends_of_the_school_year():
    september = month_keyboard("day", 2026, 9, TODAY)
    assert "‹" not in labels(september)
    assert "›" in labels(september)

    august = month_keyboard("day", 2027, 8, TODAY)
    assert "‹" in labels(august)
    assert "›" not in labels(august)

    october = month_keyboard("day", 2026, 10, TODAY)
    assert "‹" in labels(october) and "›" in labels(october)


def test_a_month_outside_the_school_year_is_pulled_back_into_it():
    """A button from a message left open long enough draws the nearest month
    it may rather than an error nobody can act on."""
    assert clamp_month(2030, 5, TODAY) == date(2027, 8, 1)
    assert clamp_month(2019, 5, TODAY) == date(2026, 9, 1)
    assert "Август 2027" in labels(month_keyboard("day", 2030, 5, TODAY))


# --------------------------------------------------------------------------
# Payload size
# --------------------------------------------------------------------------


def test_every_payload_the_calendar_builds_stays_well_inside_64_bytes():
    """Telegram counts a callback payload in bytes, and aiogram refuses to
    build a keyboard holding a longer one — which is a page that cannot be
    drawn at all, on the step *before* the one that would have been too long.
    Every payload here is prefix, tag and digits, so the margin is large."""
    candidates: list[str] = []
    for flow in ("day", "hw", "ovr", "ev", "dk"):
        for year, month in ((2026, 9), (2026, 12), (2027, 8)):
            candidates += payloads(month_keyboard(flow, year, month, TODAY))
    for role in Role:
        candidates += payloads(day_card_keyboard(date(2026, 12, 31), role))

    longest = max(candidates, key=lambda payload: len(payload.encode()))
    assert len(longest.encode()) <= 32, longest


# --------------------------------------------------------------------------
# The day card
# --------------------------------------------------------------------------


async def test_the_day_card_shows_an_ordinary_day_with_its_lessons(session, school_class):
    callback = FakeCallback()

    await calendar_card(
        callback,
        CalendarAction(action="card", flow="day", value="20260907"),
        session,
        school_class,
        Role.EDITOR,
    )

    assert "7 сентября (понедельник)" in callback.message.last
    assert "Алгебра" in callback.message.last and "Физика" in callback.message.last
    assert "🏖" not in callback.message.last
    assert not callback.alerted


async def test_the_day_card_says_a_holiday_is_one(session, school_class):
    session.add(
        DayOverride(
            class_id=school_class.id,
            date=date(2026, 9, 7),
            kind=DayKind.HOLIDAY,
            note="осенние каникулы",
        )
    )
    await session.commit()
    callback = FakeCallback()

    await calendar_card(
        callback,
        CalendarAction(action="card", flow="day", value="20260907"),
        session,
        school_class,
        Role.EDITOR,
    )

    assert "🏖 Каникулы / выходной" in callback.message.last
    assert "осенние каникулы" in callback.message.last
    assert "Алгебра" not in callback.message.last


async def test_the_cards_homework_button_lands_in_the_homework_flow(session, school_class):
    """Not a copy of «добавить ДЗ» with the date filled in: the card's button
    is the payload ``homework_pick_day`` is registered for, unpacked and fed
    to that same handler here."""
    button = next(
        button
        for row in day_card_keyboard(date(2026, 9, 7), Role.EDITOR).inline_keyboard
        for button in row
        if button.text == "📝 Задать ДЗ"
    )
    state = FakeState()
    callback = FakeCallback()

    await homework_pick_day(
        callback,
        HomeworkAction.unpack(button.callback_data),
        state,
        session,
        school_class,
        Role.EDITOR,
    )

    assert state.data["due"] == "2026-09-07"
    assert "По какому предмету?" in callback.message.last
    assert state.data["subjects"] == ["Алгебра", "Физика", "История"]


def test_the_day_card_offers_a_viewer_nothing_they_would_be_refused():
    viewer = labels(day_card_keyboard(date(2026, 9, 7), Role.VIEWER))
    editor = labels(day_card_keyboard(date(2026, 9, 7), Role.EDITOR))

    assert viewer == ["‹ Календарь", "‹ Меню"]
    for label in ("📝 Задать ДЗ", "🔄 Замена", "🎉 Событие", "🏖 Тип дня"):
        assert label in editor
        assert label not in viewer
    assert labels(day_card_keyboard(date(2026, 9, 7), Role.ADMIN)) == editor


# --------------------------------------------------------------------------
# The role gate on the grid itself
# --------------------------------------------------------------------------


async def test_a_viewer_may_browse_the_calendar_but_not_the_homework_grid(
    session, school_class, monkeypatch
):
    # The clock is pinned so the bounds under test are the ones TODAY names,
    # rather than whichever school year the suite happens to run in.
    monkeypatch.setattr(handlers, "_today", lambda *_: TODAY)

    browsing = FakeCallback()
    await calendar_nav(
        browsing,
        CalendarAction(action="nav", flow="day", value="202610"),
        school_class,
        Role.VIEWER,
    )
    assert not browsing.alerted
    assert "Октябрь 2026" in labels(browsing.message.keyboard)

    planning = FakeCallback()
    await calendar_nav(
        planning,
        CalendarAction(action="nav", flow="hw", value="202610"),
        school_class,
        Role.VIEWER,
    )
    assert planning.alerted
    assert planning.message.markups == []


async def test_the_day_command_opens_the_calendar_at_the_current_month(
    school_class, monkeypatch
):
    monkeypatch.setattr(handlers, "_today", lambda *_: TODAY)
    message = FakeEditable()

    await cmd_day(message, FakeState(), school_class, Role.VIEWER)

    assert "Календарь" in message.last
    assert "Сентябрь 2026" in labels(message.keyboard)
    assert "13" in labels(message.keyboard)


@pytest.mark.parametrize("payload", ["000001", "000000", "999913", "00ab12"])
async def test_a_month_nobody_could_have_pressed_is_refused_not_raised(
    payload, session, school_class, monkeypatch
):
    """Callback data is whatever the client sends, not only what we put on a button.

    «000001» is six digits with a month of one, which is as far as the check
    used to go: the year went through untouched, `clamp_month` built
    ``Date(0, 1, 1)`` and the handler died with a ValueError - «Что-то пошло не
    так. Начните заново» on a keyboard that looks perfectly ordinary. The same
    shape as the long-subject payload this project has already been bitten by,
    and the same answer: parse it by building the date, and refuse what will
    not build.
    """
    monkeypatch.setattr(handlers, "_today", lambda *_: TODAY)
    callback = FakeCallback()

    await calendar_nav(
        callback,
        CalendarAction(action="nav", flow="day", value=payload),
        school_class,
        Role.VIEWER,
    )

    assert callback.alerted
    assert [text for text, _ in callback.answers] == ["Непонятный месяц"]
    # And the grid the reader was looking at is left exactly as it was.
    assert callback.message.markups == []


async def test_the_same_payload_on_the_way_back_falls_through_to_this_month(
    session, school_class, monkeypatch
):
    """`open` has no refusal to give - it is the «‹ Календарь» button on a day
    card, and there is always a right answer for it: the month today is in."""
    monkeypatch.setattr(handlers, "_today", lambda *_: TODAY)
    callback = FakeCallback()

    await calendar_open(
        callback,
        CalendarAction(action="open", flow="day", value="000001"),
        school_class,
        Role.VIEWER,
    )

    assert "Сентябрь 2026" in labels(callback.message.keyboard)


@pytest.mark.parametrize(
    "flow, factory, action",
    [
        ("hw", HomeworkAction, "pick_day"),
        ("ovr", OverrideCB, "pick_day"),
        ("ev", EventAction, "pick_day"),
    ],
)
async def test_a_day_payload_that_is_not_a_date_is_refused_not_raised(
    flow, factory, action, session, school_class, monkeypatch
):
    """The three flows the calendar hands a date to, at their boundary.

    `manage.py` and `timetable.py` had guarded theirs; these three called
    ``Date.fromisoformat`` on the payload, which is right for every button this
    bot builds and an unhandled ValueError for anything else. «Событие» was the
    worst of the three: it stored the value unparsed and raised three questions
    later, at the step that finally read it, so the failure looked like it
    belonged to whatever the user had just typed.
    """
    monkeypatch.setattr(content, "_today", lambda *_: TODAY)
    callback = FakeCallback()
    handler = {
        "hw": content.homework_pick_day,
        "ovr": content.override_pick_day,
        "ev": content.event_pick_day,
    }[flow]

    state = FakeState()
    payload = factory(action=action, value="не-дата")
    if flow == "ev":
        await handler(callback, payload, state, school_class, Role.EDITOR)
    else:
        await handler(callback, payload, state, session, school_class, Role.EDITOR)

    assert [text for text, _ in callback.answers] == [content.BAD_DATE]
    assert callback.message.markups == []
    # Nothing was written down, so the next tap starts clean rather than on a
    # half-filled flow whose date is a string that will not parse.
    assert await state.get_data() == {}
