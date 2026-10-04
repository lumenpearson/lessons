"""Payloads and the role picker of «👥 Доступ» (``handlers/access``).

Both role pickers send a ``RolePick``, and only ``target`` tells them apart —
see «Two screens can match one press» in CLAUDE.md.
"""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import DANGER
from app.bot.keyboards import Menu
from app.models import Role


class AccessAction(CallbackData, prefix="acl"):
    action: str  # list | invite | revoke | set_role | remove_invite | join_mode
    value: str = ""


class RolePick(CallbackData, prefix="role"):
    role: str
    target: str = ""


def role_picker(available: list[Role], target: str = "") -> InlineKeyboardMarkup:
    rows = [
        [
            InlineKeyboardButton(
                text=role.title_ru, callback_data=RolePick(role=role.value, target=target).pack()
            )
        ]
        for role in available
    ]
    rows.append(
        [
            InlineKeyboardButton(
                text="Отмена", callback_data=Menu(action="root").pack(), style=DANGER
            )
        ]
    )
    return InlineKeyboardMarkup(inline_keyboard=rows)
