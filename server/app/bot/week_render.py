"""«🗓 Неделя» and «⏭ Что дальше»: a week in one message, and where the class
is right now (``handlers/week``)."""

from __future__ import annotations

import math
from datetime import date as Date
from datetime import datetime
from datetime import time as Time
from html import escape

from app.models import WeekParity
from app.schedule import ResolvedDay, ResolvedLesson, week_parity
from app.wording import DAY_KIND_LABELS, EVENT_ICONS, MONTHS_GENITIVE, WEEKDAYS_SHORT, clamp, plural

#: Longest message the week view may grow to before it starts dropping
#: detail. Telegram's ceiling is 4096 characters *after* entity parsing; the
#: margin covers the tags and the navigation hint the handler may append.
WEEK_TEXT_LIMIT = 3900


#: A character with no glyph and no whitespace semantics. Appended to a
#: re-rendered message that did not change, so that ``edit_text`` has
#: something to edit instead of answering "message is not modified".
INVISIBLE = "⁣"


def duration(minutes: int) -> str:
    """«1 ч 12 мин», «45 мин», «2 ч» — whole minutes, never seconds."""
    hours, rest = divmod(max(minutes, 0), 60)
    parts = []
    if hours:
        parts.append(plural(hours, "ч", "ч", "ч"))
    if rest or not hours:
        parts.append(f"{rest} мин")
    return " ".join(parts)


def _minutes_until(now: datetime, at: Time) -> int:
    """Whole minutes from ``now`` to ``at`` on the same day, rounded up.

    Rounded up rather than down: «осталось 0 мин» while the bell has not yet
    rung reads as a bug, «1 мин» reads as a countdown.
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


def _render_week_at(
    days: list[ResolvedDay], today: Date, parity_matters: bool, detail: int
) -> list[str]:
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
    return lines


def render_week(days: list[ResolvedDay], today: Date, parity_matters: bool) -> str:
    """Monday–Saturday in one message.

    Detail is shed in steps when the week would not fit: rooms and teachers go
    first, then events. A lesson row is never dropped *while detail is being
    shed* - a week view with a lesson missing is worse than one with the room
    missing.

    Past that the trade reverses, and for a long time this function did not
    notice. Detail 0 is already only the lesson rows, so a week that is still
    too long there cannot be shortened by dropping anything else - and the
    string was returned anyway. Telegram refuses a message over 4096 characters
    whole, so «🗓 Неделя» answered «что-то пошло не так» and `/week`, a plain
    `answer` with no callback to apologise on, answered nothing at all. Six
    days of eight lessons named «Основы безопасности жизнедеятельности и
    начальной военной подготовки (подгруппа 1)» came to 4648. A week with its
    tail cut and «… и ещё N строк» saying so is a week; a refused message is
    not one.
    """
    lines: list[str] = []
    for detail in (2, 1, 0):
        lines = _render_week_at(days, today, parity_matters, detail)
        text = "\n".join(lines)
        if len(text) <= WEEK_TEXT_LIMIT:
            return text
    return clamp(lines, WEEK_TEXT_LIMIT)


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
    if not lessons:
        # A day whose every lesson is cancelled is not a school day to announce,
        # and the line below reads ``lessons[0]``. ``next_school_day`` filters on
        # the same predicate, so today's one caller cannot get here - but the
        # signature invites any resolved day, and an IndexError out of a renderer
        # is an error dialog where a sentence belongs.
        return "Следующий учебный день пока не назначен."
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
