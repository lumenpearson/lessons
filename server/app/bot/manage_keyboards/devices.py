"""Buttons for «📱 Устройства»."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import DANGER
from app.bot.keyboards import back_to_menu
from app.bot.manage_keyboards._common import back_to
from app.bot.manage_render.devices import DEVICES_MAX


class DeviceAction(CallbackData, prefix="dev"):
    action: str  # list | revoke | unlink
    value: str = ""


def device_keyboard(devices: list) -> InlineKeyboardMarkup:
    rows: list[list[InlineKeyboardButton]] = []
    for device in devices[:DEVICES_MAX]:
        did = str(device.id)
        row = [
            InlineKeyboardButton(
                text=f"🚫 {device.device_name or device.id}"[:28],
                callback_data=DeviceAction(action="revoke", value=did).pack(),
                style=DANGER,
            )
        ]
        if device.telegram_id is not None:
            row.append(
                InlineKeyboardButton(
                    text="🔗 Отвязать",
                    callback_data=DeviceAction(action="unlink", value=did).pack(),
                    style=DANGER,
                )
            )
        rows.append(row)
    rows.append(back_to("root"))
    return back_to_menu(rows)
