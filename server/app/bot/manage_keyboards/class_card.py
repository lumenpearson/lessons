"""The «⚙️ Класс» menu, which opens every other screen, and the class switcher."""

from __future__ import annotations

from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import DANGER
from app.bot.keyboards import ClassAction, Menu, back_to_menu
from app.bot.manage_keyboards._common import ManageAction, back_to
from app.bot.manage_keyboards.audit_log import AuditAction
from app.bot.manage_keyboards.bells import BellsAction
from app.bot.manage_keyboards.devices import DeviceAction
from app.bot.manage_keyboards.holidays import DayKindAction
from app.bot.manage_keyboards.subjects import SubjectAction
from app.bot.manage_keyboards.terms import TermAction


def class_menu(
    *,
    is_owner: bool,
    many_classes: bool,
    pending: int,
    diary_bound: bool = False,
) -> InlineKeyboardMarkup:
    rows: list[list[InlineKeyboardButton]] = [
        [
            InlineKeyboardButton(
                text="✏️ Название", callback_data=ManageAction(action="rename").pack()
            ),
            InlineKeyboardButton(
                text="🏫 Школа", callback_data=ManageAction(action="school").pack()
            ),
        ],
        [
            InlineKeyboardButton(
                text="🏙 Город", callback_data=ManageAction(action="city").pack()
            ),
            # Packed rather than hand-written: the string it produces is the
            # same today, and a prefix rename or a new field on ``ClassAction``
            # would turn a literal into a button that answers nothing.
            InlineKeyboardButton(
                text="🕒 Часовой пояс",
                callback_data=ClassAction(action="timezone").pack(),
            ),
        ],
        [
            InlineKeyboardButton(
                text="📚 Предметы", callback_data=SubjectAction(action="list").pack()
            ),
            InlineKeyboardButton(
                text="🏖 Особые дни", callback_data=DayKindAction(action="list").pack()
            ),
        ],
        [
            InlineKeyboardButton(
                text="🔔 Звонки", callback_data=BellsAction(action="list").pack()
            ),
            InlineKeyboardButton(
                text="📱 Устройства", callback_data=DeviceAction(action="list").pack()
            ),
        ],
        [
            InlineKeyboardButton(
                text="📅 Календарь", callback_data=ManageAction(action="calendar").pack()
            ),
            InlineKeyboardButton(
                text="📜 Журнал", callback_data=AuditAction(action="page", value="0").pack()
            ),
        ],
        [
            InlineKeyboardButton(
                text="🗓 Четверти", callback_data=TermAction(action="list").pack()
            ),
        ],
    ]
    rows.append(
        [
            # Painted by what pressing does, like every other toggle here: red
            # while the press takes something away, plain while it gives it
            # back.
            InlineKeyboardButton(
                text="📒 Дневник: отвязать" if diary_bound else "📒 Привязать дневник",
                callback_data=ManageAction(action="diary_bind").pack(),
                style=DANGER if diary_bound else None,
            ),
        ]
    )
    if pending:
        rows.append(
            [
                InlineKeyboardButton(
                    text=f"🙋 Запросы доступа · {pending}",
                    callback_data=Menu(action="access").pack(),
                )
            ]
        )
    if many_classes:
        rows.append(
            [
                InlineKeyboardButton(
                    text="🔀 Сменить класс", callback_data=ManageAction(action="switch").pack()
                )
            ]
        )
    if is_owner:
        rows.append(
            [
                InlineKeyboardButton(text="🔁 Сменить код", callback_data="cls:rotate_code:"),
                InlineKeyboardButton(
                    text="🗑 Удалить класс",
                    callback_data=ManageAction(action="delete").pack(),
                    style=DANGER,
                ),
            ]
        )
    return back_to_menu(rows)


def switch_keyboard(classes: list) -> InlineKeyboardMarkup:
    rows = [
        [
            InlineKeyboardButton(
                text=f"🏫 {school_class.name}"[:40],
                callback_data=ManageAction(action="switch_to", value=str(school_class.id)).pack(),
            )
        ]
        for school_class in classes[:15]
    ]
    rows.append(back_to("root"))
    return back_to_menu(rows)
