"""The class as an iCalendar feed, for Google Calendar, Apple Calendar and the
rest.

The feed is addressed by ``SchoolClass.calendar_token``, a secret separate
from the join code on purpose: a subscription URL ends up in calendar
settings, on a family laptop and in the odd screenshot, and none of those
should be able to mint device tokens.

Times are written as local wall time with a ``TZID`` naming the class's IANA
zone and no ``VTIMEZONE`` block. Strictly RFC 5545 wants the block; in
practice every calendar that matters resolves IANA names itself, and a
generated ``VTIMEZONE`` for a Russian zone is a page of transition rules that
would be wrong the next time the Duma moves a clock.
"""

from __future__ import annotations

import secrets
from collections.abc import Iterable
from datetime import UTC, datetime
from datetime import date as Date
from datetime import time as Time

from sqlalchemy.ext.asyncio import AsyncSession

from app.models import PersonalTask, SchoolClass, TaskPriority
from app.schedule import ResolvedDay

PRODID = "-//Lessons//Школьный дневник//RU"

#: RFC 5545 §3.1: content lines are folded at 75 octets, not characters.
LINE_OCTETS = 75

# iCalendar priority runs 1 (highest) to 9; 0 is "undefined".
_ICS_PRIORITY = {
    int(TaskPriority.HIGH): 1,
    int(TaskPriority.NORMAL): 5,
    int(TaskPriority.LOW): 9,
}


# --------------------------------------------------------------------------
# Token
# --------------------------------------------------------------------------


async def ensure_calendar_token(session: AsyncSession, school_class: SchoolClass) -> str:
    """The class's feed secret, minted on first request and stable after."""
    if not school_class.calendar_token:
        school_class.calendar_token = secrets.token_urlsafe(24)
        await session.commit()
    return school_class.calendar_token


async def rotate_calendar_token(session: AsyncSession, school_class: SchoolClass) -> str:
    """A new secret; every existing subscription stops updating."""
    school_class.calendar_token = secrets.token_urlsafe(24)
    await session.commit()
    return school_class.calendar_token


# --------------------------------------------------------------------------
# Text encoding
# --------------------------------------------------------------------------


def escape_text(value: str) -> str:
    """RFC 5545 §3.3.11: backslash, semicolon, comma and newline are escaped."""
    return (
        value.replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\n", "\\n")
    )


def fold(line: str) -> list[str]:
    """Split one content line at 75 octets, continuation lines led by a space.

    Counted in UTF-8 octets, never splitting inside a character: a Cyrillic
    letter is two bytes and a line cut between them is not text any more.
    """
    parts: list[str] = []
    current: list[str] = []
    used = 0
    for char in line:
        size = len(char.encode("utf-8"))
        if used + size > LINE_OCTETS:
            parts.append("".join(current))
            current, used = [" "], 1
        current.append(char)
        used += size
    parts.append("".join(current))
    return parts


def _local(day: Date, clock: Time) -> str:
    return f"{day:%Y%m%d}T{clock:%H%M%S}"


def _uid_part(row_id: int | None, date_key: str, position: int) -> str:
    """The part of a ``UID`` that names *which* row this is.

    The row id when there is one, because a UID is an identity and has to
    survive its neighbours. It used to be the item's position within its day,
    which is not one: delete the first of three заданий and the remaining two
    slide up into its UID and the second's. Every subscriber's client then sees
    two to-dos change into different subjects — ticked-off ones included — and
    a third disappear, with nothing to say it happened.

    Falls back to the position for a resolved row that carries no id, which is
    only ever a hand-built one in a test. Prefixed so the two namespaces cannot
    collide as ids and positions pass each other.
    """
    return f"id{row_id}" if row_id is not None else f"{date_key}-{position}"


def _utc_stamp(moment: datetime) -> str:
    """``DTSTAMP`` form. A naive value is taken as UTC, like every naive column."""
    aware = moment.replace(tzinfo=UTC) if moment.tzinfo is None else moment.astimezone(UTC)
    return f"{aware:%Y%m%dT%H%M%S}Z"


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------


def render_ics(
    school_class: SchoolClass,
    days: list[ResolvedDay],
    *,
    generated_at: datetime,
    tasks: Iterable[PersonalTask] = (),
) -> str:
    """One VEVENT per lesson and event, one VTODO per homework and task.

    UIDs are derived from what they describe (class, date, slot), so a
    subscription that fetches the feed again sees the same lesson as the same
    entry - an updated замена replaces it rather than appearing twice.
    """
    zone = school_class.timezone_name
    stamp = _utc_stamp(generated_at)
    lines: list[str] = [
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        f"PRODID:{PRODID}",
        "CALSCALE:GREGORIAN",
        "METHOD:PUBLISH",
        f"X-WR-CALNAME:{escape_text(school_class.name)}",
        f"X-WR-TIMEZONE:{zone}",
    ]

    for day in days:
        date_key = day.date.isoformat()

        for lesson in day.lessons:
            # A cancelled lesson is struck through in the bot because the slot
            # still matters there; in a calendar an entry that says «отменён»
            # is just an alarm that goes off for nothing.
            if lesson.is_cancelled:
                continue
            lines += [
                "BEGIN:VEVENT",
                f"UID:lesson-{school_class.id}-{date_key}-{lesson.index}@lessons",
                f"DTSTAMP:{stamp}",
                f"DTSTART;TZID={zone}:{_local(day.date, lesson.starts_at)}",
                f"DTEND;TZID={zone}:{_local(day.date, lesson.ends_at)}",
                f"SUMMARY:{escape_text(lesson.subject)}",
            ]
            if lesson.room:
                lines.append(f"LOCATION:{escape_text('каб. ' + lesson.room)}")
            description = [
                part
                for part in (lesson.teacher, "Замена" if lesson.is_replaced else None, lesson.note)
                if part
            ]
            if description:
                lines.append(f"DESCRIPTION:{escape_text(chr(10).join(description))}")
            lines.append("END:VEVENT")

        for position, event in enumerate(day.events, 1):
            lines += [
                "BEGIN:VEVENT",
                f"UID:event-{school_class.id}-{_uid_part(event.id, date_key, position)}@lessons",
                f"DTSTAMP:{stamp}",
                f"DTSTART;TZID={zone}:{_local(day.date, event.starts_at)}",
                f"DTEND;TZID={zone}:{_local(day.date, event.ends_at)}",
                f"SUMMARY:{escape_text(event.title)}",
            ]
            if event.location:
                lines.append(f"LOCATION:{escape_text(event.location)}")
            lines.append("END:VEVENT")

        for position, item in enumerate(day.homework, 1):
            summary = f"{item.subject}: {' '.join(item.text.split())}"
            lines += [
                "BEGIN:VTODO",
                f"UID:homework-{school_class.id}-{_uid_part(item.id, date_key, position)}@lessons",
                f"DTSTAMP:{stamp}",
                f"DUE;VALUE=DATE:{day.date:%Y%m%d}",
                f"SUMMARY:{escape_text(summary)}",
                f"DESCRIPTION:{escape_text(item.text)}",
                "END:VTODO",
            ]

    for task in tasks:
        lines += [
            "BEGIN:VTODO",
            f"UID:task-{task.id}@lessons",
            f"DTSTAMP:{stamp}",
            f"SUMMARY:{escape_text(task.title)}",
        ]
        if task.notes:
            lines.append(f"DESCRIPTION:{escape_text(task.notes)}")
        if task.subject_name:
            lines.append(f"CATEGORIES:{escape_text(task.subject_name)}")
        if task.due_date is not None and task.due_time is not None:
            lines.append(f"DUE;TZID={zone}:{_local(task.due_date, task.due_time)}")
        elif task.due_date is not None:
            lines.append(f"DUE;VALUE=DATE:{task.due_date:%Y%m%d}")
        lines.append(f"PRIORITY:{_ICS_PRIORITY.get(task.priority, 5)}")
        if task.done:
            lines.append("STATUS:COMPLETED")
            if task.done_at is not None:
                lines.append(f"COMPLETED:{_utc_stamp(task.done_at)}")
        else:
            lines.append("STATUS:NEEDS-ACTION")
        lines.append("END:VTODO")

    lines.append("END:VCALENDAR")
    return "\r\n".join(part for line in lines for part in fold(line)) + "\r\n"
