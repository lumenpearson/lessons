"""Buttons under an import preview."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import DANGER, SUCCESS


class ImportAction(CallbackData, prefix="imp"):
    action: str  # apply | cancel
    value: str = ""


def import_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(
                    text="✅ Применить",
                    callback_data=ImportAction(action="apply").pack(),
                    style=SUCCESS,
                ),
                InlineKeyboardButton(
                    text="Отмена",
                    callback_data=ImportAction(action="cancel").pack(),
                    style=DANGER,
                ),
            ]
        ]
    )
