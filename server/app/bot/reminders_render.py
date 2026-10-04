"""The «🔔 Напоминания» card (``handlers/reminders``)."""

from __future__ import annotations

from datetime import time as Time

from app.models import ReminderSettings


def render_reminder_card(settings: ReminderSettings) -> str:
    def _clock(value: Time | None) -> str:
        return f"{value:%H:%M}" if value is not None else "выкл"

    def _flag(value: bool) -> str:
        return "вкл" if value else "выкл"

    return "\n".join(
        [
            "<b>🔔 Напоминания</b>",
            "",
            f"☀️ Утренняя сводка: <b>{_clock(settings.morning_at)}</b>",
            f"🌙 Домашка на завтра: <b>{_clock(settings.evening_at)}</b>",
            f"🔄 Замены и события: <b>{_flag(settings.notify_changes)}</b>",
            f"📝 Новые задания: <b>{_flag(settings.notify_homework)}</b>",
            "",
            "<i>Сводки приходят в течение примерно пяти минут после указанного "
            "времени. Время — по часам класса.</i>",
        ]
    )
