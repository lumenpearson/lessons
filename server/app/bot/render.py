"""Turning resolved days into Telegram messages.

Kept apart from the handlers so the wording can be changed without touching
any control flow.
"""

from __future__ import annotations

import math
from datetime import date as Date
from datetime import datetime, timedelta
from datetime import time as Time
from html import escape

from app.models import DayKind, EventKind, PersonalTask, ReminderSettings, Role, WeekParity
from app.schedule import ResolvedDay, ResolvedLesson, week_parity

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


# --------------------------------------------------------------------------
# Plurals and small formatting helpers
# --------------------------------------------------------------------------

WEEKDAYS_SHORT = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"]

PRIORITY_ICONS = {2: "🔴", 1: "🟡", 0: "⚪"}

#: Longest message the week view may grow to before it starts dropping
#: detail. Telegram's ceiling is 4096 characters *after* entity parsing; the
#: margin covers the tags and the navigation hint the handler may append.
WEEK_TEXT_LIMIT = 3900

#: A character with no glyph and no whitespace semantics. Appended to a
#: re-rendered message that did not change, so that ``edit_text`` has
#: something to edit instead of answering "message is not modified".
INVISIBLE = "⁣"


def plural(count: int, one: str, few: str, many: str) -> str:
    """"1 урок", "2 урока", "5 уроков" — Russian has three forms, not two."""
    tail = count % 100
    if 11 <= tail <= 19:
        form = many
    else:
        tail %= 10
        form = one if tail == 1 else few if 2 <= tail <= 4 else many
    return f"{count} {form}"


def duration(minutes: int) -> str:
    """"1 ч 12 мин", "45 мин", "2 ч" — whole minutes, never seconds."""
    hours, rest = divmod(max(minutes, 0), 60)
    parts = []
    if hours:
        parts.append(plural(hours, "ч", "ч", "ч"))
    if rest or not hours:
        parts.append(f"{rest} мин")
    return " ".join(parts)


def _minutes_until(now: datetime, at: Time) -> int:
    """Whole minutes from ``now`` to ``at`` on the same day, rounded up.

    Rounded up rather than down: "осталось 0 мин" while the bell has not yet
    rung reads as a bug, "1 мин" reads as a countdown.
    """
    target = datetime.combine(now.date(), at, tzinfo=now.tzinfo)
    seconds = (target - now).total_seconds()
    return max(0, math.ceil(seconds / 60))


# --------------------------------------------------------------------------
# Week view
# --------------------------------------------------------------------------


def _week_lesson_line(lesson: ResolvedLesson, detail: int) -> str:
    """One compact row; ``detail`` 2 shows room and teacher, 1 and 0 hide them."""
    head = f"{lesson.index} · {lesson.starts_at:%H:%M} {escape(lesson.subject)}"
    if lesson.is_cancelled:
        return f"<s>{head}</s>"
    extras = []
    if detail >= 2:
        if lesson.room:
            extras.append(escape(lesson.room))
        if lesson.teacher:
            extras.append(escape(lesson.teacher))
    marks = " 🔁" if lesson.is_replaced else ""
    suffix = f" · {' · '.join(extras)}" if extras else ""
    return f"{head}{marks}{suffix}"


def _render_week_at(days: list[ResolvedDay], today: Date, parity_matters: bool, detail: int) -> str:
    lines: list[str] = []
    if days:
        first, last = days[0].date, days[-1].date
        if first.month == last.month:
            span = f"{first.day}–{last.day} {MONTHS_GENITIVE[first.month - 1]}"
        else:
            span = (
                f"{first.day} {MONTHS_GENITIVE[first.month - 1]} – "
                f"{last.day} {MONTHS_GENITIVE[last.month - 1]}"
            )
        lines.append(f"<b>🗓 Неделя {span}</b>")
        if parity_matters:
            parity = "числитель" if week_parity(first) is WeekParity.ODD else "знаменатель"
            lines.append(f"Неделя: {parity}")

    for day in days:
        lines.append("")
        title = (
            f"{WEEKDAYS_SHORT[day.date.weekday()]}, "
            f"{day.date.day} {MONTHS_GENITIVE[day.date.month - 1]}"
        )
        if day.date == today:
            title += " · сегодня"
        lines.append(f"<b>{title}</b>")

        kind_label = DAY_KIND_LABELS.get(day.kind)
        if kind_label:
            lines.append(kind_label)
        if not day.lessons and not kind_label:
            lines.append("Уроков нет")
        for lesson in day.lessons:
            lines.append(_week_lesson_line(lesson, detail))
        if detail >= 1:
            for event in day.events:
                icon = EVENT_ICONS.get(event.kind, "•")
                lines.append(f"{icon} {event.starts_at:%H:%M} {escape(event.title)}")
        if day.homework:
            lines.append(f"📝 {plural(len(day.homework), 'задание', 'задания', 'заданий')}")
    return "\n".join(lines)


def render_week(days: list[ResolvedDay], today: Date, parity_matters: bool) -> str:
    """Monday–Saturday in one message.

    Detail is shed in steps when the week would not fit: rooms and teachers go
    first, then events. A lesson row is never dropped - a week view with a
    lesson missing is worse than one with the room missing.
    """
    for detail in (2, 1, 0):
        text = _render_week_at(days, today, parity_matters, detail)
        if len(text) <= WEEK_TEXT_LIMIT:
            return text
    return text


# --------------------------------------------------------------------------
# «Что дальше»
# --------------------------------------------------------------------------


def _lesson_ref(lesson: ResolvedLesson, with_room: bool = True) -> str:
    text = f"{lesson.index}. {escape(lesson.subject)}"
    if with_room and lesson.room:
        text += f", каб. {escape(lesson.room)}"
    return text


def _next_day_line(next_day: ResolvedDay | None, today: Date) -> str:
    if next_day is None:
        return "Следующий учебный день пока не назначен."
    lessons = [lesson for lesson in next_day.lessons if not lesson.is_cancelled]
    delta = (next_day.date - today).days
    if delta == 1:
        when = "Завтра"
    else:
        when = (
            f"В{'о' if next_day.date.weekday() == 1 else ''} "
            f"{_weekday_accusative(next_day.date)}, "
            f"{next_day.date.day} {MONTHS_GENITIVE[next_day.date.month - 1]}"
        )
    first = lessons[0]
    return (
        f"{when}: {plural(len(lessons), 'урок', 'урока', 'уроков')}, "
        f"первый в {first.starts_at:%H:%M} — {escape(first.subject)}"
    )


def _weekday_accusative(day: Date) -> str:
    """«в понедельник», «во вторник», «в среду», «в пятницу», «в субботу»."""
    return [
        "понедельник",
        "вторник",
        "среду",
        "четверг",
        "пятницу",
        "субботу",
        "воскресенье",
    ][day.weekday()]


def render_next(day: ResolvedDay, next_day: ResolvedDay | None, now: datetime) -> str:
    """Where the class is right now: in a lesson, on a break, before or after school.

    ``now`` is class wall time. Cancelled lessons are skipped as if they were
    not there - a cancelled third lesson makes the break after the second one
    longer, which is what the pupils experience. Events do not change the
    state: a canteen slot is still a break.
    """
    today = now.date()
    clock = now.time()
    lessons = sorted(
        (lesson for lesson in day.lessons if not lesson.is_cancelled),
        key=lambda lesson: (lesson.starts_at, lesson.index),
    )

    if not lessons:
        label = DAY_KIND_LABELS.get(day.kind) or "Сегодня уроков нет."
        return f"{label}\n{_next_day_line(next_day, today)}"

    first, last = lessons[0], lessons[-1]
    if clock < first.starts_at:
        wait = duration(_minutes_until(now, first.starts_at))
        return (
            f"Первый урок в {first.starts_at:%H:%M} (через {wait}): "
            f"{escape(first.subject)}"
            + (f", каб. {escape(first.room)}" if first.room else "")
        )

    if clock >= last.ends_at:
        return f"Уроки закончились.\n{_next_day_line(next_day, today)}"

    for position, lesson in enumerate(lessons):
        following = lessons[position + 1] if position + 1 < len(lessons) else None
        if lesson.starts_at <= clock < lesson.ends_at:
            left = _minutes_until(now, lesson.ends_at)
            lines = [
                f"Сейчас: <b>{_lesson_ref(lesson)}</b> — до {lesson.ends_at:%H:%M} "
                f"(осталось {duration(left)})"
            ]
            if following is None:
                lines.append("Дальше: уроки закончились 🎉")
            else:
                gap = _minutes_until(
                    datetime.combine(today, lesson.ends_at, tzinfo=now.tzinfo),
                    following.starts_at,
                )
                lines.append(
                    f"Дальше: перемена {duration(gap)}, потом {_lesson_ref(following)}"
                )
            return "\n".join(lines)
        if following is not None and lesson.ends_at <= clock < following.starts_at:
            left = _minutes_until(now, following.starts_at)
            return (
                f"Перемена до {following.starts_at:%H:%M} (осталось {duration(left)})\n"
                f"Дальше: <b>{_lesson_ref(following)}</b>"
            )

    # Unreachable when bells are sane; a lesson that ends before it starts
    # would land here, and "no idea" beats a traceback.
    return "Уроки идут.\n" + _next_day_line(next_day, today)


# --------------------------------------------------------------------------
# Homework digest with ticks
# --------------------------------------------------------------------------


def render_homework_digest(
    days: list[ResolvedDay],
    today: Date,
    done: set[tuple[Date, str]] | frozenset[tuple[Date, str]] = frozenset(),
) -> str:
    """Upcoming homework grouped by the day it is due.

    ``done`` holds (due date, subject) pairs this reader has ticked off; those
    render struck through, so the message is the same for the whole class and
    only the ticks differ per person.
    """
    upcoming = [day for day in days if day.date >= today and day.homework]
    if not upcoming:
        return "📝 Домашних заданий пока нет."

    lines = ["<b>📝 Домашнее задание</b>"]
    for day in upcoming:
        lines.append("")
        lines.append(f"<b>{escape(human_date(day.date, today)).capitalize()}</b>")
        for item in day.homework:
            body = f"<b>{escape(item.subject)}</b>: {escape(item.text)}"
            if (day.date, item.subject) in done:
                lines.append(f"✅ <s>{body}</s>")
            else:
                lines.append(f"• {body}")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# Personal tasks
# --------------------------------------------------------------------------

#: Lines of tasks in one message before «… и ещё N». Ten buttons is the
#: keyboard's ceiling; the text may list more so nothing is hidden entirely.
TASK_LINES_MAX = 40

TASK_GROUPS = ("Просрочено", "Сегодня", "Завтра", "Позже", "Без срока")


def task_group(task: PersonalTask, today: Date) -> str:
    if task.due_date is None:
        return "Без срока"
    delta = (task.due_date - today).days
    if delta < 0:
        return "Просрочено"
    if delta == 0:
        return "Сегодня"
    if delta == 1:
        return "Завтра"
    return "Позже"


def task_line(task: PersonalTask) -> str:
    """"🔴 Реферат — до 15.09 18:00 (История)"."""
    icon = PRIORITY_ICONS.get(task.priority, "🟡")
    text = escape(task.title)
    if task.due_date is not None:
        due = f"до {task.due_date:%d.%m}"
        if task.due_time is not None:
            due += f" {task.due_time:%H:%M}"
        text += f" — {due}"
    if task.subject_name:
        text += f" ({escape(task.subject_name)})"
    if task.done:
        return f"✅ <s>{text}</s>"
    return f"{icon} {text}"


def render_task_list(tasks: list[PersonalTask], today: Date, show_done: bool = False) -> str:
    """Grouped by urgency; done ones, when shown, sit at the bottom."""
    open_tasks = [task for task in tasks if not task.done]
    done_tasks = [task for task in tasks if task.done] if show_done else []

    if not open_tasks and not done_tasks:
        return (
            "✅ <b>Мои задачи</b>\n\n"
            "Список пуст. Добавьте задачу кнопкой ниже или командой "
            "<code>/task Купить тетрадь до 15.09 в 18:00 !</code>."
        )

    lines = ["✅ <b>Мои задачи</b>"]
    budget = TASK_LINES_MAX
    hidden = 0

    def _emit(title: str, items: list[PersonalTask]) -> None:
        nonlocal budget, hidden
        if not items:
            return
        lines.append("")
        lines.append(f"<b>{title}</b>")
        for task in items:
            if budget <= 0:
                hidden += 1
                continue
            lines.append(task_line(task))
            budget -= 1

    for group in TASK_GROUPS:
        _emit(group, [task for task in open_tasks if task_group(task, today) == group])
    _emit("Сделано", done_tasks)
    if hidden:
        lines.append(f"… и ещё {hidden}")
    return "\n".join(lines)


def render_task_saved(task: PersonalTask, today: Date) -> str:
    """The confirmation after parsing free text - shows what was understood."""
    lines = [f"✅ Задача добавлена: <b>{escape(task.title)}</b>"]
    if task.due_date is not None:
        due = human_date(task.due_date, today)
        if task.due_time is not None:
            due += f", {task.due_time:%H:%M}"
        lines.append(f"Срок: {escape(due)}")
    else:
        lines.append("Срок: не задан")
    lines.append(
        "Приоритет: "
        + {2: "🔴 высокий", 1: "🟡 обычный", 0: "⚪ низкий"}.get(task.priority, "🟡 обычный")
    )
    return "\n".join(lines)


# --------------------------------------------------------------------------
# Reminder settings
# --------------------------------------------------------------------------


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
