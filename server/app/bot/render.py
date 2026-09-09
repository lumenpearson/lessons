"""Turning resolved days into Telegram messages.

Kept apart from the handlers so the wording can be changed without touching
any control flow.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import timedelta
from html import escape

from app.models import DayKind, EventKind, Role
from app.schedule import ResolvedDay

MONTHS_GENITIVE = [
    "января",
    "февраля",
    "марта",
    "апреля",
    "мая",
    "июня",
    "июля",
    "августа",
    "сентября",
    "октября",
    "ноября",
    "декабря",
]

WEEKDAYS = [
    "понедельник",
    "вторник",
    "среда",
    "четверг",
    "пятница",
    "суббота",
    "воскресенье",
]

EVENT_ICONS = {
    EventKind.EVENT: "🎉",
    EventKind.CANTEEN: "🍽",
    EventKind.EXAM: "📋",
    EventKind.TRIP: "🚌",
    EventKind.MEETING: "👥",
}

DAY_KIND_LABELS = {
    DayKind.HOLIDAY: "🏖 Каникулы / выходной",
    DayKind.SHORTENED: "⏱ Сокращённые уроки",
    DayKind.REMOTE: "💻 Дистанционное обучение",
}


def human_date(day: Date, today: Date | None = None) -> str:
    """"сегодня, 9 сентября (вторник)" — the form people actually read."""
    label = f"{day.day} {MONTHS_GENITIVE[day.month - 1]} ({WEEKDAYS[day.weekday()]})"
    if today is None:
        return label
    delta = (day - today).days
    if delta == 0:
        return f"сегодня, {label}"
    if delta == 1:
        return f"завтра, {label}"
    if delta == -1:
        return f"вчера, {label}"
    return label


def relative_day_name(day: Date, today: Date) -> str:
    """"на завтра" / "на понедельник" / "на 15 сентября"."""
    delta = (day - today).days
    if delta == 1:
        return "на завтра"
    if 2 <= delta <= 7:
        return f"на {WEEKDAYS[day.weekday()]}"
    return f"на {day.day} {MONTHS_GENITIVE[day.month - 1]}"


def render_day(day: ResolvedDay, today: Date) -> str:
    lines = [f"<b>{escape(human_date(day.date, today)).capitalize()}</b>"]

    kind_label = DAY_KIND_LABELS.get(day.kind)
    if kind_label:
        lines.append(kind_label)
    if day.note:
        lines.append(f"<i>{escape(day.note)}</i>")

    if not day.lessons:
        lines.append("")
        lines.append("Уроков нет.")
    else:
        lines.append("")
        for lesson in day.lessons:
            time_range = f"{lesson.starts_at:%H:%M}–{lesson.ends_at:%H:%M}"
            subject = escape(lesson.subject)
            if lesson.is_cancelled:
                row = f"{lesson.index}. <s>{subject}</s> — отменён"
            else:
                marks = " 🔁" if lesson.is_replaced else ""
                row = f"{lesson.index}. <b>{subject}</b>{marks}"
            extras = []
            if lesson.room and not lesson.is_cancelled:
                extras.append(f"каб. {escape(lesson.room)}")
            if lesson.teacher and not lesson.is_cancelled:
                extras.append(escape(lesson.teacher))
            suffix = f" · {' · '.join(extras)}" if extras else ""
            lines.append(f"<code>{time_range}</code>  {row}{suffix}")
            if lesson.note:
                lines.append(f"     <i>{escape(lesson.note)}</i>")

    if day.events:
        lines.append("")
        lines.append("<b>События</b>")
        for event in day.events:
            icon = EVENT_ICONS.get(event.kind, "•")
            where = f" · {escape(event.location)}" if event.location else ""
            lines.append(
                f"{icon} <code>{event.starts_at:%H:%M}–{event.ends_at:%H:%M}</code> "
                f"{escape(event.title)}{where}"
            )

    if day.homework:
        lines.append("")
        lines.append("<b>Домашнее задание</b>")
        for item in day.homework:
            lines.append(f"📝 <b>{escape(item.subject)}</b>: {escape(item.text)}")

    return "\n".join(lines)


def render_homework_digest(days: list[ResolvedDay], today: Date) -> str:
    """Upcoming homework grouped by the day it is due."""
    upcoming = [day for day in days if day.date >= today and day.homework]
    if not upcoming:
        return "📝 Домашних заданий пока нет."

    lines = ["<b>📝 Домашнее задание</b>"]
    for day in upcoming:
        lines.append("")
        lines.append(f"<b>{escape(human_date(day.date, today)).capitalize()}</b>")
        for item in day.homework:
            lines.append(f"• <b>{escape(item.subject)}</b>: {escape(item.text)}")
    return "\n".join(lines)


def render_access_list(members: list, invites: list) -> str:
    lines = ["<b>👥 Доступ к классу</b>", ""]
    if members:
        for member in members:
            name = escape(member.full_name or member.username or str(member.telegram_id))
            handle = f" (@{escape(member.username)})" if member.username else ""
            lines.append(f"• {name}{handle} — <b>{member.role.title_ru}</b>")
    else:
        lines.append("<i>Пока никого нет.</i>")

    pending = [invite for invite in invites if not invite.is_used]
    if pending:
        lines.append("")
        lines.append("<b>Приглашения по номеру</b>")
        for invite in pending:
            label = f" — {escape(invite.label)}" if invite.label else ""
            lines.append(f"⏳ +{invite.phone} → {invite.role.title_ru}{label}")
    return "\n".join(lines)


def render_role_help(role: Role) -> str:
    return {
        Role.VIEWER: "Вы можете смотреть расписание и домашнее задание.",
        Role.EDITOR: "Вы можете добавлять домашнее задание, замены и события.",
        Role.ADMIN: (
            "Вы можете редактировать расписание и звонки, а также выдавать "
            "доступ редакторам."
        ),
        Role.OWNER: "У вас полный доступ, включая назначение администраторов.",
    }[role]


def upcoming_dates(today: Date, count: int = 7) -> list[tuple[str, str]]:
    """(label, iso) pairs used to build date-picker keyboards."""
    result: list[tuple[str, str]] = []
    for offset in range(count):
        day = today + timedelta(days=offset)
        prefix = {0: "Сегодня", 1: "Завтра"}.get(offset, WEEKDAYS[day.weekday()].capitalize())
        result.append((f"{prefix}, {day.day} {MONTHS_GENITIVE[day.month - 1]}", day.isoformat()))
    return result
