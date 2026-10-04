"""Buttons for «🔔 Напоминания» (``handlers/reminders``)."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import DANGER
from app.bot.keyboards import Menu


class ReminderAction(CallbackData, prefix="rem"):
    action: str  # card | toggle | set_time | clear_time | off
    value: str = ""


def reminder_keyboard(settings) -> InlineKeyboardMarkup:
    """One row per digest (change time / switch off) and a toggle per flag."""

    def _digest_row(kind: str, icon: str, enabled: bool) -> list[InlineKeyboardButton]:
        row = [
            InlineKeyboardButton(
                text=f"{icon} Изменить время",
                callback_data=ReminderAction(action="set_time", value=kind).pack(),
            )
        ]
        if enabled:
            row.append(
                InlineKeyboardButton(
                    text=f"{icon} Выключить",
                    callback_data=ReminderAction(action="clear_time", value=kind).pack(),
                    style=DANGER,
                )
            )
        return row

    def _toggle(label: str, flag: str, on: bool) -> list[InlineKeyboardButton]:
        # Painted by what pressing it does, not by the state it reports: these
        # two carry both halves in one label, so «Замены: выключить» is red
        # because pressing it switches the substitutions off, and the same button reading
        # «включить» is plain because pressing it takes nothing away.
        return [
            InlineKeyboardButton(
                text=f"{label}: " + ("выключить" if on else "включить"),
                callback_data=ReminderAction(action="toggle", value=flag).pack(),
                style=DANGER if on else None,
            )
        ]

    rows = [
        _digest_row("morning", "☀️", settings.morning_at is not None),
        _digest_row("evening", "🌙", settings.evening_at is not None),
        _toggle("🔄 Замены", "changes", settings.notify_changes),
        _toggle("📝 Задания", "homework", settings.notify_homework),
        [
            InlineKeyboardButton(
                text="🔕 Выключить всё",
                callback_data=ReminderAction(action="off").pack(),
                style=DANGER,
            )
        ],
        [InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())],
    ]
    return InlineKeyboardMarkup(inline_keyboard=rows)
