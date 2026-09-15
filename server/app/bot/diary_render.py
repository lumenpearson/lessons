"""The diary, said in Telegram.

Separate from ``render.py`` for the same reason ``editor_render.py`` is: it
renders somebody else's data. The class's own timetable is this project's —
resolved, parity-aware, with замены applied. The diary is the school's record
of one child, fetched live, and the only honest thing to do with it is show
what came back and say when it came back empty.

That last part is the rule the whole file follows: **an empty answer is said,
not hidden.** The upstream returns nothing for a day it has no data for, for a
day in the holidays, and for a day whose teacher has not filled the journal
yet — and a screen that renders all three as a blank is a screen that makes a
parent refresh it four times. So each view names which of those it is when it
can, and admits it cannot when it cannot.

Everything the upstream sends is escaped before it goes into a line. The bot
sends HTML, and «реши § 4 при a<b» is a homework text a maths teacher writes;
unescaped it does not lose the «<», it makes Telegram refuse the whole message,
so the parent sees no diary at all rather than one damaged row.
"""

from __future__ import annotations

from datetime import date as Date
from html import escape

from app.bot.render import WEEKDAYS, human_date
from app.providers.petersburg.models import (
    DiaryLesson,
    HomeworkItem,
    Mark,
    Student,
)

#: How a mark is drawn. Colour rather than a number alone, because the thing a
#: parent scans a term for is the twos, and «2» among thirty other digits is
#: found by reading rather than by looking.
MARK_ICONS = {"5": "🟢", "4": "🟢", "3": "🟡", "2": "🔴", "1": "🔴"}

#: A code the register uses where a mark would go.
ABSENCE_ICONS = {"Н": "⚪", "Б": "⚪", "У": "⚪"}


def mark_icon(value: str) -> str:
    return MARK_ICONS.get(value) or ABSENCE_ICONS.get(value) or "⚫"


def student_line(student: Student) -> str:
    parts = [f"<b>{escape(student.full_name)}</b>"]
    where = " · ".join(escape(part) for part in (student.class_name, student.school) if part)
    if where:
        parts.append(where)
    return "\n".join(parts)


def render_day(lessons: list[DiaryLesson], day: Date, today: Date) -> str:
    """One day of the diary: lessons, rooms, what was set."""
    heading = f"📒 <b>{human_date(day, today).capitalize()}</b>"
    if not lessons:
        return (
            f"{heading}\n\n<i>Дневник ничего не вернул на этот день. "
            "Это может быть выходной, каникулы или просто незаполненный журнал.</i>"
        )

    lines = [heading, ""]
    for lesson in sorted(lessons, key=lambda item: (item.number or 0, item.subject)):
        number = f"<b>{lesson.number}.</b> " if lesson.number else "• "
        head = f"{number}{escape(lesson.subject)}"
        tail = " · ".join(escape(part) for part in (lesson.room, lesson.teacher) if part)
        lines.append(f"{head}{f' · {tail}' if tail else ''}")
        if lesson.starts_at and lesson.ends_at:
            lines.append(f"    <code>{lesson.starts_at:%H:%M}–{lesson.ends_at:%H:%M}</code>")
        if lesson.topic:
            lines.append(f"    <i>{escape(lesson.topic)}</i>")
        if lesson.homework:
            lines.append(f"    📝 {escape(lesson.homework)}")
    return "\n".join(lines)


def render_homework(items: list[HomeworkItem], start: Date, end: Date, today: Date) -> str:
    """What is due, grouped by the day it is due on rather than the day it was
    set — which is the question anybody actually asks of homework."""
    if not items:
        return (
            "📝 <b>Задания</b>\n\n"
            f"<i>С {start:%d.%m} по {end:%d.%m} дневник ничего не вернул.</i>"
        )

    by_day: dict[Date, list[HomeworkItem]] = {}
    for item in items:
        by_day.setdefault(item.due_date, []).append(item)

    lines = ["📝 <b>Задания</b>", ""]
    for day in sorted(by_day):
        when = human_date(day, today).capitalize()
        lines.append(f"<b>{when}</b>")
        for item in sorted(by_day[day], key=lambda one: one.subject):
            lines.append(f"• <b>{escape(item.subject)}</b> — {escape(item.text)}")
        lines.append("")
    return "\n".join(lines).rstrip()


def render_marks(marks: list[Mark], start: Date, end: Date) -> str:
    """Marks by subject, with the average where there is one.

    The average is computed here and not asked of the upstream: the upstream's
    own average is a term average under its own rounding rules, and showing it
    beside a hand-picked date range would be two different numbers claiming to
    be the same thing.
    """
    if not marks:
        return (
            "📊 <b>Оценки</b>\n\n"
            f"<i>С {start:%d.%m} по {end:%d.%m} оценок нет.</i>"
        )

    by_subject: dict[str, list[Mark]] = {}
    for mark in marks:
        by_subject.setdefault(mark.subject_name, []).append(mark)

    lines = ["📊 <b>Оценки</b>", f"<i>{start:%d.%m} — {end:%d.%m}</i>", ""]
    for subject in sorted(by_subject):
        entries = by_subject[subject]
        drawn = " ".join(f"{mark_icon(mark.value)}{escape(mark.value)}" for mark in entries)
        # Digits only. «Н» and «Б» are attendance codes sitting in the same
        # column, and averaging them in would drag every absence towards a two.
        numeric = [int(mark.value) for mark in entries if mark.value.isdigit()]
        average = f" · <b>{sum(numeric) / len(numeric):.2f}</b>" if numeric else ""
        lines.append(f"<b>{escape(subject)}</b>{average}")
        lines.append(f"    {drawn}")
    return "\n".join(lines)


def render_week(lessons: list[DiaryLesson], start: Date, today: Date) -> str:
    """Six days at a glance — subject names only, which is all that fits."""
    by_day: dict[Date, list[DiaryLesson]] = {}
    for lesson in lessons:
        by_day.setdefault(lesson.date, []).append(lesson)

    lines = [f"📒 <b>Неделя с {start:%d.%m}</b>", ""]
    for offset in range(6):
        day = Date.fromordinal(start.toordinal() + offset)
        name = WEEKDAYS[day.weekday()].capitalize()
        marker = "<b>" if day == today else ""
        close = "</b>" if day == today else ""
        items = sorted(by_day.get(day, []), key=lambda item: (item.number or 0))
        if not items:
            lines.append(f"{marker}{name}{close} — <i>нет</i>")
            continue
        names = ", ".join(escape(item.subject) for item in items)
        lines.append(f"{marker}{name}{close} · {len(items)}")
        lines.append(f"    {names}")
    return "\n".join(lines)


def render_signed_out() -> str:
    return (
        "📒 <b>Электронный дневник</b>\n\n"
        "Класс привязан к дневнику Санкт-Петербурга. Вход — ваш личный: "
        "вы видите своего ребёнка, и никто из класса не видит его за вас.\n\n"
        "<b>Пароль не вводится в чат.</b> Бот даст ссылку на страницу входа — "
        "оттуда пароль уходит прямо в дневник и нигде не сохраняется."
    )
