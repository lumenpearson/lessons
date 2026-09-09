"""Inline keyboards and typed callback payloads."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import (
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    KeyboardButton,
    ReplyKeyboardMarkup,
)

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


class HomeworkAction(CallbackData, prefix="hw"):
    action: str  # add | edit | delete | pick_day | pick_subject
    value: str = ""


class AccessAction(CallbackData, prefix="acl"):
    action: str  # list | invite | revoke | set_role | remove_invite
    value: str = ""


class RolePick(CallbackData, prefix="role"):
    role: str
    target: str = ""


class TimetableAction(CallbackData, prefix="tt"):
    action: str  # view | pick_day | set_cell | clear_cell | bells
    value: str = ""


class OverrideAction(CallbackData, prefix="ovr"):
    action: str  # add | cancel_lesson | pick_day | pick_index | clear
    value: str = ""


class EventAction(CallbackData, prefix="ev"):
    action: str  # add | delete | pick_kind | pick_day
    value: str = ""


class TimezonePick(CallbackData, prefix="tz"):
    zone: str


class ClassAction(CallbackData, prefix="cls"):
    action: str  # settings | rotate_code | rename | create | switch
    value: str = ""


def main_menu(role: Role) -> InlineKeyboardMarkup:
    rows: list[list[InlineKeyboardButton]] = [
        [
            InlineKeyboardButton(text="📅 Сегодня", callback_data=DayNav(offset=0).pack()),
            InlineKeyboardButton(text="🗓 Завтра", callback_data=DayNav(offset=1).pack()),
        ],
        [
            InlineKeyboardButton(
                text="📝 Домашнее задание", callback_data=Menu(action="homework").pack()
            )
        ],
    ]
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
                    text="🧩 Расписание", callback_data=Menu(action="timetable").pack()
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
                InlineKeyboardButton(text="‹", callback_data=DayNav(offset=offset - 1).pack()),
                InlineKeyboardButton(text="Сегодня", callback_data=DayNav(offset=0).pack()),
                InlineKeyboardButton(text="›", callback_data=DayNav(offset=offset + 1).pack()),
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
            [InlineKeyboardButton(text="✖️ Отмена", callback_data=Menu(action="root").pack())]
        ]
    )


def role_picker(available: list[Role], target: str = "") -> InlineKeyboardMarkup:
    rows = [
        [
            InlineKeyboardButton(
                text=role.title_ru, callback_data=RolePick(role=role.value, target=target).pack()
            )
        ]
        for role in available
    ]
    rows.append([InlineKeyboardButton(text="✖️ Отмена", callback_data=Menu(action="root").pack())])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def weekday_picker(callback_factory: type[CallbackData], action: str) -> InlineKeyboardMarkup:
    """Mon–Sat picker; Sunday is omitted because nothing is ever scheduled on it."""
    rows: list[list[InlineKeyboardButton]] = []
    for chunk_start in (0, 3):
        rows.append(
            [
                InlineKeyboardButton(
                    text=WEEKDAY_NAMES[i],
                    callback_data=callback_factory(action=action, value=str(i + 1)).pack(),
                )
                for i in range(chunk_start, chunk_start + 3)
            ]
        )
    rows.append([InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def date_picker(
    callback_factory: type[CallbackData], action: str, dates: list[tuple[str, str]]
) -> InlineKeyboardMarkup:
    """``dates`` is a list of (label, iso-date) pairs."""
    rows = [
        [
            InlineKeyboardButton(
                text=label, callback_data=callback_factory(action=action, value=iso).pack()
            )
        ]
        for label, iso in dates
    ]
    rows.append([InlineKeyboardButton(text="✖️ Отмена", callback_data=Menu(action="root").pack())])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def timezone_picker() -> InlineKeyboardMarkup:
    """One row per Russian time zone, labelled the way schedules are written.

    Eleven rows is a long keyboard, but it is a once-per-class decision and a
    wrong zone silently shifts every bell, so it is worth the scroll.
    """
    from app.timezones import RUSSIAN_TIMEZONES

    rows = [
        [
            InlineKeyboardButton(
                text=f"{offset} · {cities}",
                callback_data=TimezonePick(zone=zone).pack(),
            )
        ]
        for zone, offset, cities in RUSSIAN_TIMEZONES
    ]
    return InlineKeyboardMarkup(inline_keyboard=rows)
