"""The month grid every «на какой день?» is answered with, and the day card.

Telegram has no date picker, so a bot either builds the grid people already
recognise from every other calendar bot — a month, seven columns, arrows to
step a month — or it offers a list of buttons. This project offered a list of
the next seven days, which put two things out of reach entirely: a day that
has already happened (homework is written down after the lesson at least as
often as before it) and anything more than a week out (the holidays are marked
a month ahead, a test two).

Kept out of ``keyboards.py`` because it is one feature with one payload, and
out of the handlers because the grid is a pure function of a year, a month and
the class's today — which is what makes its shape testable without a database.
"""

from __future__ import annotations

from calendar import Calendar
from datetime import date as Date

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import DANGER, SUCCESS
from app.bot.keyboards import EventAction, HomeworkAction, Menu, back_to_menu
from app.bot.keyboards import OverrideAction as OverrideCB
from app.bot.manage_keyboards import DayKindAction
from app.bot.render import MONTHS_NOMINATIVE, WEEKDAYS_SHORT
from app.models import Role


class CalendarAction(CallbackData, prefix="cal"):
    """The grid's own buttons.

    ``flow`` is the picker the grid was opened for, so ‹ and › come back with
    the same buttons under them; ``value`` is ``YYYYMM`` for a month and
    ``YYYYMMDD`` for a day — digits only, because a payload is limited to 64
    *bytes* and this one has to stay obviously inside that whatever is added
    to it later.
    """

    action: str  # open | nav | card | skip
    flow: str = "day"
    value: str = ""


#: The payload a day button carries, per flow. Everything but «day» hands the
#: date to the flow's existing handler untouched, so the calendar is a new way
#: into the substitutions, events and special days flows rather than a second implementation of
#: any of them. Their handlers parse ISO dates and keep doing so.
DAY_PAYLOADS: dict[str, tuple[type[CallbackData], str]] = {
    "hw": (HomeworkAction, "pick_day"),
    "ovr": (OverrideCB, "pick_day"),
    "ev": (EventAction, "pick_day"),
    "dk": (DayKindAction, "pick_date"),
}

#: What a flow's grid is worth opening at all. Checked when the arrows are
#: pressed as well as at the entry point: the state that got someone here is
#: their own client's, and re-checking costs nothing.
FLOW_MINIMUM: dict[str, Role] = {
    "day": Role.VIEWER,
    "hw": Role.EDITOR,
    "ovr": Role.EDITOR,
    "ev": Role.EDITOR,
    "dk": Role.EDITOR,
}

#: A cell with no day in it. Telegram refuses an empty label, so the padding
#: is a space; it answers nothing and looks like the hole it is.
BLANK = " "


def school_year_bounds(today: Date) -> tuple[Date, Date]:
    """First and last month the arrows may reach, as those months' first days.

    One school year — 1 September to 31 August — the one ``today`` falls in.
    Anything outside it cannot be planned: in June the timetable for September
    does not exist yet, and a date in the year that ended cannot be taught
    again. It also means ‹ held down stops at September instead of walking back
    to 1970, which is the only thing an unbounded calendar reliably does.
    """
    start_year = today.year if today.month >= 9 else today.year - 1
    return Date(start_year, 9, 1), Date(start_year + 1, 8, 1)


def clamp_month(year: int, month: int, today: Date) -> Date:
    """The asked-for month's first day, pulled back inside the school year."""
    first, last = school_year_bounds(today)
    asked = Date(year, month, 1)
    return min(max(asked, first), last)


def _shift_month(anchor: Date, months: int) -> Date:
    index = (anchor.year * 12 + anchor.month - 1) + months
    return Date(index // 12, index % 12 + 1, 1)


def month_grid(year: int, month: int) -> list[list[int | None]]:
    """Weeks of the month as rows of seven, Monday first, ``None`` for padding.

    Five rows for most months, six when a 31-day month starts on a Saturday or
    Sunday, four for a February that starts on a Monday — the row count is the
    month's, not a constant, because a fixed six rows would put a blank week
    under half the year.
    """
    weeks = Calendar(firstweekday=0).monthdayscalendar(year, month)
    return [[day or None for day in week] for week in weeks]


def _day_button(flow: str, day: Date, today: Date) -> InlineKeyboardButton:
    # Today is green and otherwise written like every other day. It used to be
    # «13» — the only mark a keyboard had for «this one is now» before buttons
    # could be coloured, and a poor one: guillemets are quotation marks, they
    # made the cell a character wider than its neighbours, and in a grid where
    # width is alignment that is the row that looks wrong rather than the day
    # that looks current.
    if flow in DAY_PAYLOADS:
        factory, action = DAY_PAYLOADS[flow]
        payload = factory(action=action, value=day.isoformat()).pack()
    else:
        payload = CalendarAction(
            action="card", flow=flow, value=f"{day:%Y%m%d}"
        ).pack()
    return InlineKeyboardButton(
        text=str(day.day),
        callback_data=payload,
        style=SUCCESS if day == today else None,
    )


def _blank(flow: str) -> InlineKeyboardButton:
    return InlineKeyboardButton(
        text=BLANK, callback_data=CalendarAction(action="skip", flow=flow).pack()
    )


def month_keyboard(flow: str, year: int, month: int, today: Date) -> InlineKeyboardMarkup:
    """A month of ``flow``'s picker: heading, weekday initials, days, arrows.

    ``year``/``month`` are clamped rather than refused, so a button from a
    message left open across the New Year draws the nearest month it may
    instead of an error nobody can act on.
    """
    anchor = clamp_month(year, month, today)
    first, last = school_year_bounds(today)

    rows: list[list[InlineKeyboardButton]] = [
        [
            InlineKeyboardButton(
                text=f"{MONTHS_NOMINATIVE[anchor.month - 1]} {anchor.year}",
                callback_data=CalendarAction(action="skip", flow=flow).pack(),
            )
        ],
        [
            InlineKeyboardButton(
                text=name, callback_data=CalendarAction(action="skip", flow=flow).pack()
            )
            for name in WEEKDAYS_SHORT
        ],
    ]

    for week in month_grid(anchor.year, anchor.month):
        rows.append(
            [
                _blank(flow)
                if day is None
                else _day_button(flow, Date(anchor.year, anchor.month, day), today)
                for day in week
            ]
        )

    def _arrow(label: str, target: Date, reachable: bool) -> InlineKeyboardButton:
        # At a bound the arrow stays in place as a dead button rather than
        # disappearing: a row that changes width moves the other arrow under
        # the thumb that was aiming for it.
        if not reachable:
            return _blank(flow)
        return InlineKeyboardButton(
            text=label,
            callback_data=CalendarAction(
                action="nav", flow=flow, value=f"{target:%Y%m}"
            ).pack(),
        )

    previous, following = _shift_month(anchor, -1), _shift_month(anchor, 1)
    rows.append(
        [
            _arrow("‹", previous, previous >= first),
            InlineKeyboardButton(
                text="Сегодня",
                callback_data=CalendarAction(
                    action="nav", flow=flow, value=f"{today:%Y%m}"
                ).pack(),
                style=SUCCESS,
            ),
            _arrow("›", following, following <= last),
        ]
    )
    rows.append(
        [
            InlineKeyboardButton(
                text="Отмена",
                callback_data=Menu(action="root").pack(),
                style=DANGER,
            )
        ]
    )
    return InlineKeyboardMarkup(inline_keyboard=rows)


def day_card_keyboard(day: Date, role: Role) -> InlineKeyboardMarkup:
    """What may be assigned to ``day``, by whoever opened the card.

    A viewer gets the two navigation rows and nothing else. Offering them a
    button that answers «нужна роль редактора» would teach them to distrust
    every other button on the keyboard, and the role minimums here are the
    ones the flows behind them already enforce.
    """
    iso = day.isoformat()
    rows: list[list[InlineKeyboardButton]] = []
    if role.at_least(Role.EDITOR):
        rows.append(
            [
                InlineKeyboardButton(
                    text="📝 Задать ДЗ",
                    callback_data=HomeworkAction(action="pick_day", value=iso).pack(),
                ),
                InlineKeyboardButton(
                    text="🔄 Замена",
                    callback_data=OverrideCB(action="pick_day", value=iso).pack(),
                ),
            ]
        )
        rows.append(
            [
                InlineKeyboardButton(
                    text="🎉 Событие",
                    callback_data=EventAction(action="pick_day", value=iso).pack(),
                ),
                InlineKeyboardButton(
                    text="🏖 Тип дня",
                    callback_data=DayKindAction(action="pick_date", value=iso).pack(),
                ),
            ]
        )
    rows.append(
        [
            InlineKeyboardButton(
                text="‹ Календарь",
                callback_data=CalendarAction(
                    action="open", flow="day", value=f"{day:%Y%m}"
                ).pack(),
            )
        ]
    )
    return back_to_menu(rows)
