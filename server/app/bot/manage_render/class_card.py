"""The «⚙️ Класс» card."""

from __future__ import annotations

from html import escape

from app.models import JoinMode


def render_class_card(
    school_class,
    *,
    zone_label: str,
    feed_ready: bool,
    members: int,
    devices: int,
    pending: int,
    diary_label: str = "не привязан",
) -> str:
    lines = [
        f"<b>⚙️ {escape(school_class.name)}</b>",
        "",
        f"🏫 Школа: {escape(school_class.school) if school_class.school else '—'}",
        f"🏙 Город: {escape(school_class.city) if school_class.city else '—'}",
        f"🕒 Часовой пояс: {escape(zone_label)}",
        f"🔑 Код для приложения: <code>{escape(school_class.join_code)}</code>",
        f"📅 Календарь: {'ссылка выдана' if feed_ready else 'ссылка ещё не создавалась'}",
        # What the code on the line above is worth. «Публичный/закрытый» stood
        # here for a year and decided nothing — no code read the flag — so an
        # admin who «закрыл» the class had closed nothing. This says which of
        # the two things is actually true, and «👥 Доступ» is where it changes.
        # The padlock matches the state, not the row: an admin skimming the
        # card reads the icon, and «🔓» over «только по приглашениям» says the
        # opposite of the words beside it. «👥 Доступ» paints it the same way.
        (
            "🔒 Подключение телефонов: только по личным приглашениям"
            if school_class.join_mode is JoinMode.INVITE
            else "🔓 Подключение телефонов: по коду класса"
        ),
        f"📒 Дневник: {escape(diary_label)}",
        "",
        f"👥 Участников: {members} · 📱 устройств: {devices}",
    ]
    if pending:
        lines.append(f"🙋 Запросов доступа: <b>{pending}</b>")
    return "\n".join(lines)
