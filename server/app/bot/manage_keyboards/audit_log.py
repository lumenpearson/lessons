"""Buttons for «📜 Журнал»."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import PRIMARY
from app.bot.keyboards import back_to_menu
from app.bot.manage_keyboards._common import back_to
from app.bot.manage_render.audit_log import AUDIT_PAGE


class AuditAction(CallbackData, prefix="au"):
    action: str  # page
    value: str = ""


def audit_keyboard(offset: int, more: bool) -> InlineKeyboardMarkup:
    rows: list[list[InlineKeyboardButton]] = []
    if more:
        rows.append(
            [
                InlineKeyboardButton(
                    text="Ещё ›",
                    callback_data=AuditAction(
                        action="page", value=str(offset + AUDIT_PAGE)
                    ).pack(),
                    style=PRIMARY,
                )
            ]
        )
    rows.append(back_to("root"))
    return back_to_menu(rows)
