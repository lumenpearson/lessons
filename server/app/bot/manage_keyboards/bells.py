"""Buttons for «🔔 Звонки»."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import DANGER, SUCCESS
from app.bot.keyboards import back_to_menu
from app.bot.manage_keyboards._common import back_to
from app.bot.manage_render.bells import BELLS_MAX


class BellsAction(CallbackData, prefix="bl"):
    action: str  # list | open | edit | create | default | delete
    value: str = ""


def bells_list_keyboard(schedules: list, default_id: int | None) -> InlineKeyboardMarkup:
    rows: list[list[InlineKeyboardButton]] = []
    for schedule in schedules[:BELLS_MAX]:
        sid = str(schedule.id)
        row = [
            InlineKeyboardButton(
                text=f"✏️ {schedule.name}"[:32],
                callback_data=BellsAction(action="edit", value=sid).pack(),
                # The default one is green, and ⭐ — «сделать основным» — is
                # left plain on the others: green says which row is in force,
                # the same thing it says about today in the calendar, and only
                # one of the two can have it without the page having a colour
                # that means «current» and one that means «make current».
                style=SUCCESS if schedule.id == default_id else None,
            )
        ]
        if schedule.id != default_id:
            row.append(
                InlineKeyboardButton(
                    text="⭐", callback_data=BellsAction(action="default", value=sid).pack()
                )
            )
            row.append(
                InlineKeyboardButton(
                    text="🗑",
                    callback_data=BellsAction(action="delete", value=sid).pack(),
                    style=DANGER,
                )
            )
        rows.append(row)
    rows.append(
        [
            InlineKeyboardButton(
                text="➕ Новое расписание звонков",
                callback_data=BellsAction(action="create").pack(),
                style=SUCCESS,
            )
        ]
    )
    rows.append(back_to("root"))
    return back_to_menu(rows)
