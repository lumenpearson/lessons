"""Buttons for «📊 Проект» (``handlers/project``)."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.keyboards import back_to_menu


class ProjectAction(CallbackData, prefix="prj"):
    action: str  # open


def open_button() -> InlineKeyboardButton:
    """The way in from «⚙️ Класс», drawn for the deployment's owner alone."""
    return InlineKeyboardButton(text="📊 Проект", callback_data=ProjectAction(action="open").pack())


def project_keyboard() -> InlineKeyboardMarkup:
    return back_to_menu(
        [
            [
                InlineKeyboardButton(
                    text="🔄 Обновить", callback_data=ProjectAction(action="open").pack()
                )
            ]
        ]
    )
