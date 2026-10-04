"""The bot's shared vocabulary: the main menu and its payloads, the day pager,
the payloads two features both pack, and the small keyboards every screen
ends with.

A feature's own payloads and buttons live beside this file, one module each
— ``start_keyboard``, ``content_keyboard``, ``tasks_keyboard``,
``week_keyboard``, ``reminders_keyboard``, ``access_keyboard`` — as
``editor_keyboard``, ``calendar_keyboard`` and ``diary_keyboard`` already
did. They import ``Menu`` and ``cut`` from here, never the other way round,
which is why ``DayNav`` stays: ``main_menu`` packs it.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import timedelta

from aiogram.filters.callback_data import CallbackData
from aiogram.types import (
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    KeyboardButton,
    ReplyKeyboardMarkup,
)

from app.bot.button_style import DANGER, PRIMARY, SUCCESS
from app.models import Role

WEEKDAY_NAMES = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"]
WEEKDAY_FULL = [
    "Понедельник",
    "Вторник",
    "Среда",
    "Четверг",
    "Пятница",
    "Суббота",
    "Воскресенье",
]


class Menu(CallbackData, prefix="m"):
    action: str


class DayNav(CallbackData, prefix="day"):
    offset: int


def shift_days(today: Date, offset: int) -> Date | None:
    """``today`` moved by ``offset`` days, or ``None`` if that is not a date.

    The offset arrives in callback data, which is whatever the client sent and
    not only what this bot put on a ‹ › button. ``timedelta(days=999999999)``
    is an ``OverflowError``, not a large date, and it raised out of three
    handlers — the day view, the week view and the diary — where all that was
    wanted was to refuse the press.

    Attempting the arithmetic rather than range-checking the number, for the
    same reason ``calendar._month_or_none`` builds the date rather than
    checking the parts: the bounds belong to ``date`` and it already knows
    them.
    """
    try:
        return today + timedelta(days=offset)
    except (OverflowError, ValueError):
        return None


def shift_weeks(today: Date, offset: int) -> Date | None:
    """``shift_days`` for the two views that page a week at a time. Separate
    because a week's worth of overflow starts seven times sooner."""
    try:
        return today + timedelta(weeks=offset)
    except (OverflowError, ValueError):
        return None


class TimetableAction(CallbackData, prefix="tt"):
    action: str  # view | pick_day | set_cell | clear_cell | bells
    value: str = ""


class ClassAction(CallbackData, prefix="cls"):
    action: str  # settings | rotate_code | rename | create | switch
    value: str = ""


def main_menu(role: Role, diary_provider: str | None = None) -> InlineKeyboardMarkup:
    rows: list[list[InlineKeyboardButton]] = [
        [
            InlineKeyboardButton(
                text="📅 Сегодня", callback_data=DayNav(offset=0).pack(), style=SUCCESS
            ),
            InlineKeyboardButton(
                text="🗓 Завтра", callback_data=DayNav(offset=1).pack(), style=PRIMARY
            ),
        ],
        [
            InlineKeyboardButton(
                text="📝 Домашнее задание", callback_data=Menu(action="homework").pack()
            )
        ],
        # Next to «сегодня» and «завтра» on purpose: it is the same question
        # asked about any other day, and it is where a viewer looks up a past
        # day as readily as an editor plans a future one.
        [
            InlineKeyboardButton(
                text="📆 Календарь", callback_data=Menu(action="day").pack(), style=PRIMARY
            )
        ],
    ]
    rows.append(
        [
            InlineKeyboardButton(
                text="🗓 Неделя", callback_data=Menu(action="week").pack(), style=PRIMARY
            ),
            InlineKeyboardButton(text="⏭ Что дальше", callback_data=Menu(action="next").pack()),
        ]
    )
    rows.append(
        [
            InlineKeyboardButton(text="✅ Мои задачи", callback_data=Menu(action="tasks").pack()),
            InlineKeyboardButton(
                text="🔔 Напоминания", callback_data=Menu(action="reminders").pack()
            ),
        ]
    )
    # Offered to every role, because it is not the class's data and no role in
    # the class grants any of it: the button opens *your* diary or offers you
    # the door to it, and an observer has exactly as much right to their own
    # child's marks as the owner has.
    if diary_provider:
        rows.append(
            [
                InlineKeyboardButton(
                    text="📒 Мой дневник", callback_data=Menu(action="diary").pack()
                )
            ]
        )
    # Offered to every role, and low in the list rather than beside «Класс»:
    # the code behind it is worth one phone — the presser's own — so it is a
    # personal button like «Мои задачи», not an admin one. A button because
    # /link has always existed and almost nobody types it: nothing on any
    # screen said it was there.
    rows.append(
        [
            InlineKeyboardButton(
                text="📱 Подключить телефон", callback_data=Menu(action="phone").pack()
            )
        ]
    )
    if role.at_least(Role.EDITOR):
        rows.append(
            [
                InlineKeyboardButton(
                    text="🔄 Замены", callback_data=Menu(action="overrides").pack()
                ),
                InlineKeyboardButton(text="🎉 События", callback_data=Menu(action="events").pack()),
            ]
        )
    if role.at_least(Role.ADMIN):
        rows.append(
            [
                InlineKeyboardButton(
                    text="🧩 Расписание", callback_data=Menu(action="editor").pack()
                ),
                InlineKeyboardButton(text="👥 Доступ", callback_data=Menu(action="access").pack()),
            ]
        )
        rows.append(
            [InlineKeyboardButton(text="⚙️ Класс", callback_data=Menu(action="class").pack())]
        )
    return InlineKeyboardMarkup(inline_keyboard=rows)


def back_to_menu(extra: list[list[InlineKeyboardButton]] | None = None) -> InlineKeyboardMarkup:
    rows = list(extra or [])
    rows.append([InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def day_nav(offset: int) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(
                    text="‹", callback_data=DayNav(offset=offset - 1).pack()
                ),
                InlineKeyboardButton(
                    text="Сегодня", callback_data=DayNav(offset=0).pack(), style=SUCCESS
                ),
                InlineKeyboardButton(
                    text="›", callback_data=DayNav(offset=offset + 1).pack()
                ),
            ],
            [InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())],
        ]
    )


def request_contact() -> ReplyKeyboardMarkup:
    return ReplyKeyboardMarkup(
        keyboard=[[KeyboardButton(text="📱 Поделиться номером", request_contact=True)]],
        resize_keyboard=True,
        one_time_keyboard=True,
        input_field_placeholder="Нажмите кнопку, чтобы подтвердить номер",
    )


def cancel_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(
                    text="Отмена",
                    callback_data=Menu(action="root").pack(),
                    style=DANGER,
                )
            ]
        ]
    )


def weekday_picker(callback_factory: type[CallbackData], action: str) -> InlineKeyboardMarkup:
    """Mon–Sat picker; Sunday is omitted because nothing is ever scheduled on it."""
    rows: list[list[InlineKeyboardButton]] = []
    for chunk_start in (0, 3):
        rows.append(
            [
                InlineKeyboardButton(
                    text=WEEKDAY_NAMES[i],
                    callback_data=callback_factory(action=action, value=str(i + 1)).pack(),
                    style=PRIMARY,
                )
                for i in range(chunk_start, chunk_start + 3)
            ]
        )
    rows.append([InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def cut(text: str, limit: int) -> str:
    """Button labels have no wrapping; a long title is cut with an ellipsis."""
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


