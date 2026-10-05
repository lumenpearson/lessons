"""The Russian the bot and the services both put into a message.

Calendar names, the message budget, a date said aloud and the day card. The
bot draws all of them, and so does something that is not the bot: the
morning digest in ``services/reminders`` is the day card under «☀️ Доброе
утро!», the evening one and the task reminders date their rows in the same
words and keep to the same budget, and the paste grammar in
``services/timetable_io`` writes the same weekday names.

They lived in ``app.bot.render``, and those services imported them from
there — so the layer the API shares with the bot reached up into the bot's
own package, and every endpoint that imports or exports a timetable did too
(#205). A service may not import ``app.bot``; this module sits below both,
beside ``schedule``, and imports nothing but ``models`` and ``schedule``.
``app.bot.render`` imports every public name here back, so a
``from app.bot.render import …`` anywhere reads the same objects it always did.
"""

from __future__ import annotations

from collections.abc import Iterable
from datetime import date as Date
from html import escape

from app.models import DayKind, EventKind
from app.schedule import DayOffReason, ResolvedDay

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

WEEKDAYS_SHORT = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"]

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
    DayKind.SELF_STUDY: "📖 Самоподготовка",
    DayKind.DAY_OFF: "🌿 Отгул",
}


def plural(count: int, one: str, few: str, many: str) -> str:
    """«1 урок», «2 урока», «5 уроков» — Russian has three plural forms, not two."""
    tail = count % 100
    if 11 <= tail <= 19:
        form = many
    else:
        tail %= 10
        form = one if tail == 1 else few if 2 <= tail <= 4 else many
    return f"{count} {form}"


# --------------------------------------------------------------------------
# Message budgets
# --------------------------------------------------------------------------
#
# A message Telegram will not deliver is a screen that says nothing: the
# ceiling is 4096 characters after entity parsing, and the whole message is
# refused rather than clipped. Every renderer that grows with the data has to
# carry a budget, and these are the three tools for it.
#
# They used to live in ``manage_render``, which is downstream of
# ``app.bot.render``, so the four management pages had them and nothing else
# could. That is how the day view, the access list, the task list and the
# diary's four renderers each ended up without one: the fix was written twice
# and reached neither ``render_day`` nor anything in ``diary_render``.

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


#: Why a day is empty, in the words the reader can act on.
#:
#: «Уроков нет» is true of all four and useless for three: somebody looking at
#: a blank Tuesday in November wants to know whether it is the holidays, a
#: public holiday, or a day the timetable simply has nothing on — and those
#: are three different things to do next.
_OFF_REASON_LINES: dict[DayOffReason, str] = {
    DayOffReason.OUT_OF_YEAR: "Учебный год окончен — уроков нет.",
    DayOffReason.BETWEEN_TERMS: "Каникулы между периодами — уроков нет.",
    DayOffReason.PUBLIC_HOLIDAY: "Праздничный день — уроков нет.",
}


def _no_lessons_because(day: ResolvedDay) -> str:
    return _OFF_REASON_LINES.get(day.off_reason, "Уроков нет.") if day.off_reason else "Уроков нет."


def render_day(day: ResolvedDay, today: Date) -> str:
    lines = [f"<b>{escape(human_date(day.date, today)).capitalize()}</b>"]

    kind_label = DAY_KIND_LABELS.get(day.kind)
    if kind_label:
        lines.append(kind_label)
    if day.holiday:
        # Escaped like everything else that is not this file's own text. These
        # titles are ours rather than typed by anybody, but the rule is about
        # where a string is written rather than about who wrote it — «женщин и
        # девочек» is fine today and the next name added may not be.
        lines.append(f"🎉 {escape(day.holiday.title)}")
    if day.note:
        lines.append(f"<i>{escape(day.note)}</i>")

    if not day.lessons:
        lines.append("")
        lines.append(_no_lessons_because(day))
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


# ---------------------------------------------------------------------------
# What v1 and v2 both answer with
#
# A service refuses with an exception carrying facts, never a sentence, and
# each shell words it. Where v1's endpoint and v2's error table word one
# refusal alike — v2's message is v1's sentence wherever v1 had one
# (docs/specs/2026-10-05-server-v2-design.md, decision 5) — the sentence is
# here, once, so the two cannot drift. Some are English, as v1's generic
# answers always were.
# ---------------------------------------------------------------------------

#: A deployment without ``DIARY_SECRET``, on every door: v1's 503 and v2's
#: ``DIARY_DISABLED`` alike.
DIARY_DISABLED_DETAIL = "Дневник на этом сервере выключен."

#: v1's ``POST /join`` and v2's ``CreateDevice``, for each refusal of
#: ``services/join.py``: too many wrong codes, a code that names nothing, a
#: class that takes personal codes only, and a class at its phone limit.
JOIN_THROTTLED_DETAIL = "Too many join attempts"
JOIN_UNKNOWN_CODE_DETAIL = "Unknown join code"
JOIN_INVITE_ONLY_DETAIL = "Этот класс принимает только по личному приглашению из бота"
JOIN_DEVICE_LIMIT_DETAIL = (
    "К классу подключено слишком много телефонов. Возьмите личный код в боте "
    "(«📱 Подключить телефон») или попросите администратора отключить старые телефоны"
)

#: v1's ``/manage/subjects`` and v2's ``SubjectService``: an id that names no
#: subject of the class, a name the class already has (ignoring case), a
#: rename that would put two assignments on one subject and one day, and a
#: subject the weekly template still teaches.
UNKNOWN_SUBJECT_DETAIL = "Unknown subject"
SUBJECT_EXISTS_DETAIL = "a subject with that name is already in this class"


def subject_rename_clash_detail(days: Iterable[Date]) -> str:
    return "homework under both names on the same day: " + ", ".join(
        day.isoformat() for day in days
    )


def subject_in_use_detail(lessons: int) -> str:
    return f"{lessons} lesson(s) still use this subject"


#: v1's ``/manage/devices`` and v2's ``ClassDeviceService``: an id that names
#: no phone of the class, and an unlink of a phone no account is behind.
UNKNOWN_DEVICE_DETAIL = "Unknown device"
CLASS_DEVICE_NOT_LINKED_DETAIL = "device is not linked"


#: v1's ``/manage/bells`` and v2's ``BellService``: an id that names no
#: schedule of the class, ``is_default`` false, a default that rings nothing
#: (v1's ``PUT /days`` refuses a day pointed at one in the same words), and
#: the two schedules a delete must leave: the class default, and one special
#: days point at.
UNKNOWN_BELL_SCHEDULE_DETAIL = "Unknown bell schedule"
BELL_DEFAULT_REQUIRED_DETAIL = "make another schedule the default instead"
EMPTY_BELL_SCHEDULE_DETAIL = "в этом расписании звонков нет ни одного урока"
BELL_SCHEDULE_IS_DEFAULT_DETAIL = "this is the class default; make another one the default first"


def bell_schedule_in_use_detail(days: int) -> str:
    return f"{days} special day(s) still use this schedule"
