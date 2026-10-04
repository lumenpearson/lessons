"""Buttons under «🗓 Неделя» and «⏭ Что дальше» (``handlers/week``)."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import SUCCESS
from app.bot.keyboards import Menu


class WeekNav(CallbackData, prefix="wk"):
    offset: int  # weeks relative to the current one


def week_nav(offset: int) -> InlineKeyboardMarkup:
    """«Сегодня» returns to the week that contains today, not to the day view,
    so the reader stays in the week view they opened."""
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(
                    text="‹", callback_data=WeekNav(offset=offset - 1).pack()
                ),
                InlineKeyboardButton(
                    text="Сегодня", callback_data=WeekNav(offset=0).pack(), style=SUCCESS
                ),
                InlineKeyboardButton(
                    text="›", callback_data=WeekNav(offset=offset + 1).pack()
                ),
            ],
            [InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())],
        ]
    )


def next_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [InlineKeyboardButton(text="🔄 Обновить", callback_data=Menu(action="next").pack())],
            [InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())],
        ]
    )
