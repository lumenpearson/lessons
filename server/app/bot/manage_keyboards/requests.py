"""Buttons on an access request: approve or decline."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import DANGER, SUCCESS


class RequestAction(CallbackData, prefix="rq"):
    action: str  # approve | decline
    value: str = ""


def request_keyboard(request_id: int) -> InlineKeyboardMarkup:
    rid = str(request_id)
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(
                    text="✅ Одобрить",
                    callback_data=RequestAction(action="approve", value=rid).pack(),
                    style=SUCCESS,
                ),
                InlineKeyboardButton(
                    text="Отклонить",
                    callback_data=RequestAction(action="decline", value=rid).pack(),
                    style=DANGER,
                ),
            ]
        ]
    )
