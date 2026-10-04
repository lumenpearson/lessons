"""What every management keyboard is built with: the class card's payload, and
the way back to it."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton


class ManageAction(CallbackData, prefix="mg"):
    action: str  # root | rename | school | city | calendar | rotate_feed | delete | ...
    value: str = ""


def rows_of(buttons: list[InlineKeyboardButton], per_row: int) -> list[list[InlineKeyboardButton]]:
    return [buttons[i : i + per_row] for i in range(0, len(buttons), per_row)]


def back_to(action: str, label: str = "‹ Назад") -> list[InlineKeyboardButton]:
    return [InlineKeyboardButton(text=label, callback_data=ManageAction(action=action).pack())]
