"""Turning resolved days into Telegram messages.

Kept apart from the handlers so the wording can be changed without touching
any control flow.
"""

from __future__ import annotations

import math
from datetime import date as Date
from datetime import datetime
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

#: Nominative, for a heading that names the month rather than a date in it:
#: «Сентябрь 2026», not «сентября».
MONTHS_NOMINATIVE = [
    "Январь",
    "Февраль",
    "Март",
    "Апрель",
    "Май",
    "Июнь",
    "Июль",
    "Август",
    "Сентябрь",
    "Октябрь",
    "Ноябрь",
    "Декабрь",
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


# --------------------------------------------------------------------------
# Message budgets
# --------------------------------------------------------------------------
#
# A message Telegram will not deliver is a screen that says nothing: the
# ceiling is 4096 characters after entity parsing, and the whole message is
# refused rather than clipped. Every renderer below that grows with the data
# has to carry a budget, and these are the three tools for it.
#
# They used to live in ``manage_render``, which is downstream of this module,
# so the four management pages had them and nothing else could. That is how
# the day view, the access list, the task list and the diary's four renderers
# each ended up without one: the fix was written twice and reached neither
# ``render_day`` here nor anything in ``diary_render``.

#: What one message may grow to.
#:
#: Telegram's ceiling is «1-4096 characters **after entities parsing**», which
#: is the Bot API's own wording for `sendMessage`'s `text`: it counts what it
#: parses, so `<b>` costs nothing and `&amp;` counts as the one «&» it becomes.
#: An earlier version of this comment said the margin was there because
#: Telegram counts the tags. It does not, and that mistake cost a day — it is
#: what `_export_parts` in `handlers/manage` was built to defend against, and
#: that turned six messages into thirty-four and walked into the flood limit.
#:
#: Everything here measures the raw string, tags and entities included, so it
#: is *conservative*: a page cut at 3900 raw is comfortably under 4096 parsed.
#: That is deliberate and cheaper than parsing twice to find out — but it means
#: a number in this file is a budget and never a measurement of what Telegram
#: will count. The margin itself is for a navigation hint a handler may append.
#:
#: There is one way the raw count reads *lower* than Telegram's, and it is the
#: reason this number must not be tidied up towards 4096: Telegram counts UTF-16
#: code units, and every emoji outside the BMP — «📝», «📥», «🗓», most of the
#: ones these cards are built from — is two of them where Python sees one. The
#: tags a raw count includes and a parsed count does not are worth far more
#: than that in every page measured so far, so the slack has never been spent;
#: nothing measures it, and at 4096 the first page to prove otherwise would not
#: be clipped, it would be refused.
MESSAGE_LIMIT = 3900


def more_line(total: int, shown: int) -> list[str]:
    hidden = total - shown
    return [f"… и ещё {hidden}"] if hidden > 0 else []


def clamp(lines: list[str], limit: int = MESSAGE_LIMIT) -> str:
    """Join lines, dropping the tail that would not fit and saying how many.

    Cutting from the end rather than shortening every line: the rows are
    ordered by what matters most (the nearest date, the newest change), so the
    ones that survive are the ones worth reading.
    """
    kept: list[str] = []
    used = 0
    for position, line in enumerate(lines):
        cost = len(line) + 1
        if used + cost > limit:
            rest = len(lines) - position
            kept.append("… и ещё " + plural(rest, "строка", "строки", "строк"))
            break
        kept.append(line)
        used += cost
    return "\n".join(kept)


def cut(text: str, limit: int) -> str:
    """One long free-text value, shortened for a list where it is one row.

    Called on the raw value and never on the escaped one: cutting after
    escaping can leave «&am», which is a message Telegram refuses of its own.
    """
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def human_date(day: Date, today: Date | None = None) -> str:
    """«сегодня, 9 сентября (вторник)» — the form people actually read."""
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
    """«на завтра» / «на понедельник» / «на 15 сентября»."""
    delta = (day - today).days
    if delta == 1:
        return "на завтра"
    if 2 <= delta <= 7:
        return f"на {WEEKDAYS[day.weekday()]}"
    return f"на {day.day} {MONTHS_GENITIVE[day.month - 1]}"


#: An assignment in the day view, before it is shortened.
#:
#: Generous on purpose, and not the digest's 400: this is one day, and the
#: point of opening it is to read the assignment rather than to be told there is
#: one. The cut exists for the other end — ``schemas`` accepts 4000 characters
#: for an assignment and the column is uncapped, so a single pasted essay used to
#: take the whole day over Telegram's ceiling, on «сегодня», on «завтра», on
#: the ‹ › pager, in the calendar card and in the morning digest at once, and
#: `/today` is a plain answer with no callback to apologise on.
DAY_HOMEWORK_TEXT_MAX = 1000


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
            text = escape(cut(item.text, DAY_HOMEWORK_TEXT_MAX))
            lines.append(f"📝 <b>{escape(item.subject)}</b>: {text}")

    return clamp(lines)


#: Members listed on «👥 Доступ», and the number the role picker builds its
#: buttons from.
#:
#: The same number in both places, imported by ``handlers/access`` rather than
#: written there again: while they were twenty and «all of them», a class where
#: both parents of thirty pupils had joined drew sixty names above twenty
#: buttons — forty people named on screen whose role nothing could change, and
#: no «… и ещё N» saying so, because the renderer counted from itself. It is
#: also the only screen the join mode can be switched back from, and at sixty
#: members the page went over Telegram's ceiling and stopped opening at all.
ACCESS_MEMBERS_MAX = 20

#: Pending phone invites listed under them. Fewer, because an invite is a row
#: nobody re-reads: it is a thing waiting to happen or to be revoked.
ACCESS_INVITES_MAX = 10

#: An invite's free-text label, which the API accepts at 120 characters.
ACCESS_LABEL_MAX = 60

#: A pending access request's note on the «👥 Доступ» heading, which
#: ``handlers/access`` draws above this list. The column is ``String(300)`` and
#: five of them may be on the page at once.
ACCESS_REQUEST_NOTE_MAX = 120


def render_access_list(members: list, invites: list, limit: int) -> str:
    """The member list, inside ``limit`` characters.

    ``limit`` is a parameter because this list is never the whole message: the
    handler draws pending requests above it and the join-mode explanation below
    it, and while the budget here was the whole of ``MESSAGE_LIMIT`` those two
    blocks were simply extra. Thirty members with the long names Telegram
    allows, ten pending phone invites and three access requests with their
    300-character notes came to 4623 characters, so «👥 Доступ» answered
    «что-то пошло не так» — and that is the only screen the join mode can be
    switched back from.
    """
    lines = ["<b>👥 Доступ к классу</b>", ""]
    if members:
        for member in members[:ACCESS_MEMBERS_MAX]:
            name = escape(member.full_name or member.username or str(member.telegram_id))
            handle = f" (@{escape(member.username)})" if member.username else ""
            lines.append(f"• {name}{handle} — <b>{member.role.title_ru}</b>")
        lines.extend(more_line(len(members), ACCESS_MEMBERS_MAX))
    else:
        lines.append("<i>Пока никого нет.</i>")

    pending = [invite for invite in invites if not invite.is_used]
    if pending:
        lines.append("")
        lines.append("<b>Приглашения по номеру</b>")
        for invite in pending[:ACCESS_INVITES_MAX]:
            label = f" — {escape(cut(invite.label, ACCESS_LABEL_MAX))}" if invite.label else ""
            lines.append(f"⏳ +{invite.phone} → {invite.role.title_ru}{label}")
        lines.extend(more_line(len(pending), ACCESS_INVITES_MAX))
    return clamp(lines, limit)


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
    """«1 урок», «2 урока», «5 уроков» — Russian has three plural forms, not two."""
    tail = count % 100
    if 11 <= tail <= 19:
        form = many
    else:
        tail %= 10
        form = one if tail == 1 else few if 2 <= tail <= 4 else many
    return f"{count} {form}"


def silenced_lessons(silenced: list[tuple[int, int]]) -> str:
    """What to tell an admin whose new bells left lessons ringing nowhere.

    One sentence in one place, because there are two bells editors and they
    used to disagree about whether to say anything at all: «🧩 Расписание» →
    «🔔 Звонки» wrote the rows by hand and reported nothing, while «⚙️ Класс»
    → «🔔 Звонки» went through `structure.write_bell_periods` and warned. The
    rule that a lesson needs a bell of its own number is the service's; this
    is the only sentence that says so, and both editors now print it.

    The numbers are named because they are what somebody has to go and fix,
    and the count is of rows — one number under two weekdays is two lessons
    nobody will see.
    """
    numbers = ", ".join(str(index) for index in sorted({index for _day, index in silenced}))
    return (
        f"⚠️ Уроки № {numbers} в расписании класса больше не звонят "
        f"({len(silenced)} шт.) — они останутся в базе, но их никто не увидит. "
        "Добавьте звонки этих номеров или уберите уроки."
    )


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


# --------------------------------------------------------------------------
# Homework digest with ticks
# --------------------------------------------------------------------------


#: Assignments one digest draws before «… и ещё N».
#:
#: The number the keyboard under it can offer, and the keyboard is built from
#: the very rows this file decided on — ``handlers/tasks`` reads
#: :func:`homework_digest_keys`, the way ``manage_keyboards`` reads the caps
#: ``manage_render`` declares. A row past the last button is a tick nobody can
#: make, with nothing on the screen saying so.
HOMEWORK_ITEMS_MAX = 12

#: How much of one assignment a row shows. ``handlers/content`` already cuts an
#: assignment to 200 characters for the «новое задание» notification; a digest row
#: is read rather than glanced at, so it gets more — but not the four thousand
#: characters the API accepts, because twelve of those are not a message.
HOMEWORK_TEXT_MAX = 400

#: What the whole digest may grow to. Telegram's ceiling is 4096 characters
#: *after* entity parsing, and a fortnight of three assignments a day passed it at
#: 5371: ``edit_text`` then answers 400, so «📝 Домашнее задание» said «что-то
#: пошло не так» and ``/homework`` — a plain ``answer`` with no callback to
#: apologise on — said nothing at all. The margin covers the tags.
HOMEWORK_DIGEST_LIMIT = 3500


def _homework_digest(
    days: list[ResolvedDay],
    today: Date,
    done: set[tuple[Date, str]] | frozenset[tuple[Date, str]],
) -> tuple[list[str], list[tuple[Date, str]], int]:
    """The digest's lines, the assignments they name, and how many did not fit.

    One place decides, because the message and the keyboard under it have to
    agree on which assignments are on the screen: the keyboard is built from the
    keys and the message from the lines, so a row can neither appear without a
    button nor a button without a row.
    """
    upcoming = [day for day in days if day.date >= today and day.homework]
    total = sum(len(day.homework) for day in upcoming)

    lines = ["<b>📝 Домашнее задание</b>"]
    keys: list[tuple[Date, str]] = []
    used = len(lines[0]) + 1
    headed: Date | None = None

    for day in upcoming:
        for item in day.homework:
            if len(keys) >= HOMEWORK_ITEMS_MAX:
                return lines, keys, total - len(keys)
            text = item.text
            if len(text) > HOMEWORK_TEXT_MAX:
                text = text[:HOMEWORK_TEXT_MAX].rstrip() + "…"
            # Cut before escaping, never after: «&am» is a broken entity and
            # a message Telegram refuses, which is the failure being fixed.
            body = f"<b>{escape(item.subject)}</b>: {escape(text)}"
            row = (
                f"✅ <s>{body}</s>"
                if (day.date, item.subject) in done
                else f"• {body}"
            )
            heading = (
                []
                if headed == day.date
                else ["", f"<b>{escape(human_date(day.date, today)).capitalize()}</b>"]
            )
            cost = sum(len(line) + 1 for line in heading) + len(row) + 1
            # ``keys and`` so the first assignment is always drawn: a digest whose
            # whole answer is «… и ещё 1» says less than the overrun did.
            if keys and used + cost > HOMEWORK_DIGEST_LIMIT:
                return lines, keys, total - len(keys)
            lines.extend(heading)
            lines.append(row)
            used += cost
            headed = day.date
            keys.append((day.date, item.subject))
    return lines, keys, 0


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
    lines, keys, hidden = _homework_digest(days, today, done)
    if not keys:
        return "📝 Домашних заданий пока нет."
    if hidden:
        lines.append("")
        lines.append("… и ещё " + plural(hidden, "задание", "задания", "заданий"))
    return "\n".join(lines)


def homework_digest_keys(
    days: list[ResolvedDay],
    today: Date,
    done: set[tuple[Date, str]] | frozenset[tuple[Date, str]] = frozenset(),
) -> list[tuple[Date, str]]:
    """The (due date, subject) of every assignment :func:`render_homework_digest`
    draws, in the order it draws them — which is the order the tick buttons go
    in, and the whole of what they may cover."""
    return _homework_digest(days, today, done)[1]


# --------------------------------------------------------------------------
# Personal tasks
# --------------------------------------------------------------------------

#: Lines of tasks in one message before «… и ещё N». The text lists more than
#: the keyboard can offer on purpose, so that nothing is hidden entirely — and
#: [TASKS_UNREACHABLE] below is what stops that being the silent half of the
#: same defect the manage pages had, where rows were drawn that no button
#: could reach and nothing said so.
TASK_LINES_MAX = 40

#: Task buttons in one keyboard. Telegram allows a hundred; a phone shows about
#: ten before the message scrolls out of view.
#:
#: Declared here rather than in `keyboards`, the way `manage_render` declares
#: `SUBJECTS_MAX` and `manage_keyboards` reads it: what is drawn and what can
#: be pressed have to read one number, and this pair had two.
TASK_BUTTONS_MAX = 10

#: Drawn once, where the buttons stop, when the list is longer than they are.
TASKS_UNREACHABLE = (
    "— ниже кнопок нет: отметить и удалить можно только задачи выше, "
    "остальные — после того, как разберёте эти."
)

#: A task's title on its row. The column holds 200 characters, and forty rows
#: of two hundred is twice what Telegram will send — a line budget cannot
#: express that, which is why this one is in characters and the clamp below
#: catches what neither number does.
TASK_TITLE_MAX = 80

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
    """«🔴 Реферат — до 15.09 18:00 (История)»."""
    icon = PRIORITY_ICONS.get(task.priority, "🟡")
    text = escape(cut(task.title, TASK_TITLE_MAX))
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
    drawn = 0

    def _emit(title: str, items: list[PersonalTask]) -> None:
        nonlocal budget, hidden, drawn
        if not items:
            return
        lines.append("")
        lines.append(f"<b>{title}</b>")
        for task in items:
            if budget <= 0:
                hidden += 1
                continue
            # The keyboard takes the first `TASK_BUTTONS_MAX` of the same list
            # in the same order, so the tail of this text is exactly the part
            # nothing can press. Saying so once, where it happens, is the
            # cheapest honest answer: the alternative is either hiding tasks or
            # a keyboard nobody can scroll.
            if drawn == TASK_BUTTONS_MAX:
                lines.append(TASKS_UNREACHABLE)
            lines.append(task_line(task))
            budget -= 1
            drawn += 1

    for group in TASK_GROUPS:
        _emit(group, [task for task in open_tasks if task_group(task, today) == group])
    _emit("Сделано", done_tasks)
    if hidden:
        lines.append(f"… и ещё {hidden}")
    return clamp(lines)


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
