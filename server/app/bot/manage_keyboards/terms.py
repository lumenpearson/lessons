"""Buttons for «🗓 Четверти»."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.manage_keyboards._common import back_to


class TermAction(CallbackData, prefix="trm"):
    action: str  # list | scheme | edit
    value: str = ""


def terms_menu(terms, *, is_semester: bool) -> InlineKeyboardMarkup:
    """One row per term, plus the switch between the two schemes.

    The scheme button is painted by what pressing it does rather than by the
    state it reports — «Перейти на полугодия» is an offer, and green on a
    button that says «полугодия» while the class is on quarters reads as a
    claim about the present.
    """
    rows = [
        [
            InlineKeyboardButton(
                text=f"{term.index}. {term.starts_on:%d.%m} — {term.ends_on:%d.%m}",
                callback_data=TermAction(action="edit", value=str(term.index)).pack(),
            )
        ]
        for term in terms
    ]
    rows.append(
        [
            InlineKeyboardButton(
                text="📗 Перейти на четверти" if is_semester else "📘 Перейти на полугодия",
                callback_data=TermAction(
                    action="scheme", value="quarter" if is_semester else "semester"
                ).pack(),
            )
        ]
    )
    rows.append(back_to("root"))
    return InlineKeyboardMarkup(inline_keyboard=rows)
