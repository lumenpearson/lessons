"""Personal to-do items and «сделал» ticks on homework.

Everything here is scoped to one Telegram account. A task is looked up by
(id, class, owner) and never by id alone: task ids are sequential and travel
in callback data, and a crafted callback must not be able to tick or delete a
classmate's list.

:func:`parse_task_text` is the one-message grammar - «Купить тетрадь до 15.09
в 18:00 !» - because typing a date into a picker on a phone is slower than
writing it, and the class chat already speaks this dialect.
"""

from __future__ import annotations

import re
from datetime import UTC, datetime, timedelta
from datetime import date as Date
from datetime import time as Time

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Homework, HomeworkDone, PersonalTask, TaskPriority

TITLE_MAX = 200
SUBJECT_MAX = 120

# --------------------------------------------------------------------------
# The message grammar
# --------------------------------------------------------------------------

_RELATIVE_DAYS = {"сегодня": 0, "завтра": 1, "послезавтра": 2}

# Accusative after «в»/«во», dative after «к»/«ко», nominative for good
# measure, plus the two-letter abbreviations everybody writes.
_WEEKDAY_FORMS: dict[str, int] = {
    "понедельник": 1, "понедельнику": 1, "пн": 1,
    "вторник": 2, "вторнику": 2, "вт": 2,
    "среда": 3, "среду": 3, "среде": 3, "ср": 3,
    "четверг": 4, "четвергу": 4, "чт": 4,
    "пятница": 5, "пятницу": 5, "пятнице": 5, "пт": 5,
    "суббота": 6, "субботу": 6, "субботе": 6, "сб": 6,
    "воскресенье": 7, "воскресенью": 7, "вс": 7,
}  # fmt: skip

# Longest first, so «ср» cannot claim the head of «среду» and leave the tail
# in the title. The trailing ``(?!\w)`` would reject that anyway; the order
# just keeps the regex from backtracking through every form.
_WEEKDAY_ALTERNATION = "|".join(sorted(_WEEKDAY_FORMS, key=len, reverse=True))

# ``(?<!\w)`` in front keeps «срок 15.09» from being read as «к 15.09»;
# ``(?!\w)`` behind keeps «в пн» from matching the start of «в пне...».
# Python's ``\w`` is Unicode-aware, so both work for Cyrillic.
_DATE_TOKEN = re.compile(
    r"(?<!\w)(?:"
    r"(?P<relative>сегодня|завтра|послезавтра)"
    r"|(?:до|к|ко)\s+(?P<day>\d{1,2})\.(?P<month>\d{1,2})(?:\.(?P<year>\d{4}|\d{2}))?"
    rf"|(?:в|во|к|ко)\s+(?P<weekday>{_WEEKDAY_ALTERNATION})\.?"
    r")(?!\w)",
    re.IGNORECASE,
)

# «до 18.00» is a clock because no month makes it a date; «до 20.09» is a
# date even when the clock regex would happily read it as 20:09. The first
# date token wins and the rest stay in the title, so a second «до 20.09» can
# still be lying around when this runs - :func:`_looks_like_date` keeps the
# clock from claiming it.
_TIME_TOKEN = re.compile(
    r"(?<!\w)(?P<prep>в|к|до)\s+(?P<hour>\d{1,2})(?P<sep>[:.])(?P<minute>\d{2})(?!\w)",
    re.IGNORECASE,
)

# A run of marks standing on its own anywhere, or glued to the end of the
# text. A question mark in the middle of a sentence is punctuation and stays.
_PRIORITY_TOKEN = re.compile(r"(?:(?<=\s)|^)[!?]+(?=\s|$)|[!?]+\s*$")

# Punctuation left dangling where a token was cut out: «Купить тетрадь, до 15.09».
_TITLE_EDGE = " ,;:-–—"


def _safe_date(year: int, month: int, day: int) -> Date | None:
    try:
        return Date(year, month, day)
    except ValueError:
        return None


def _resolve_date(match: re.Match[str], today: Date) -> Date | None:
    relative = match.group("relative")
    if relative:
        return today + timedelta(days=_RELATIVE_DAYS[relative.lower()])

    weekday = match.group("weekday")
    if weekday:
        target = _WEEKDAY_FORMS[weekday.lower()]
        # The next such weekday counting from today inclusive: «в пн» typed on
        # a Monday morning means this one, not the one in a week.
        return today + timedelta(days=(target - today.isoweekday()) % 7)

    day, month = int(match.group("day")), int(match.group("month"))
    year_raw = match.group("year")
    if year_raw is not None:
        year = int(year_raw) if len(year_raw) == 4 else 2000 + int(year_raw)
        return _safe_date(year, month, day)

    # No year: the nearest such date that is not already behind us. «до 15.09»
    # written in October means next September. An explicit year never rolls,
    # because then the person meant exactly that date, past or not.
    candidate = _safe_date(today.year, month, day)
    if candidate is None or candidate < today:
        return _safe_date(today.year + 1, month, day)
    return candidate


def _looks_like_date(match: re.Match[str]) -> bool:
    """A clock match that the date grammar would also accept: «до 20.09», «к 1.05».

    Only the date prepositions and the dot separator qualify - «в 10.10» is
    ten past ten, because «в» never introduces a DD.MM date. The year is a
    leap year so that «до 29.02» counts as a date too.
    """
    if match.group("prep").lower() == "в" or match.group("sep") != ".":
        return False
    return _safe_date(2000, int(match.group("minute")), int(match.group("hour"))) is not None


def _resolve_time(match: re.Match[str]) -> Time | None:
    if _looks_like_date(match):
        return None
    hour, minute = int(match.group("hour")), int(match.group("minute"))
    if hour > 23 or minute > 59:
        return None
    return Time(hour, minute)


def parse_task_text(raw: str, today: Date) -> tuple[str, Date | None, Time | None, int]:
    """«Купить тетрадь до 15.09 в 18:00 !» -> (title, due_date, due_time, priority).

    Recognised and removed from the title: «до 15.09» / «до 15.09.2026» / «к
    15.09», «сегодня» / «завтра» / «послезавтра», «в пн» … «в вс» and the
    full weekday names («в пятницу», «к понедельнику»), «в 18:00» / «в 18.00»
    / «до 18:00», and a standalone or trailing «!» / «!!» (high) or «?» (low).
    The first token of each kind wins; anything unrecognised stays in the
    title, which is the safest thing to do with text a person wrote.
    """
    text = raw.strip()

    due_date: Date | None = None
    for match in _DATE_TOKEN.finditer(text):
        due_date = _resolve_date(match, today)
        if due_date is not None:
            text = f"{text[: match.start()]} {text[match.end():]}"
            break

    due_time: Time | None = None
    for match in _TIME_TOKEN.finditer(text):
        due_time = _resolve_time(match)
        if due_time is not None:
            text = f"{text[: match.start()]} {text[match.end():]}"
            break

    marks: list[str] = []

    def _take(match: re.Match[str]) -> str:
        marks.append(match.group(0).strip())
        return " "

    text = _PRIORITY_TOKEN.sub(_take, text)
    joined = "".join(marks)
    if "!" in joined:
        priority = int(TaskPriority.HIGH)
    elif "?" in joined:
        priority = int(TaskPriority.LOW)
    else:
        priority = int(TaskPriority.NORMAL)

    title = " ".join(text.split()).strip(_TITLE_EDGE)[:TITLE_MAX].rstrip()
    return title, due_date, due_time, priority


# --------------------------------------------------------------------------
# Tasks
# --------------------------------------------------------------------------


def _utcnow() -> datetime:
    """Naive UTC, matching the naive ``DateTime`` columns the model declares."""
    return datetime.now(UTC).replace(tzinfo=None)


def _clamp_priority(priority: int) -> int:
    return max(int(TaskPriority.LOW), min(int(TaskPriority.HIGH), int(priority)))


async def list_tasks(
    session: AsyncSession,
    class_id: int,
    telegram_id: int,
    include_done: bool = False,
    limit: int = 50,
) -> list[PersonalTask]:
    """Undone first, then by deadline with undated ones last, urgent first, then age."""
    query = select(PersonalTask).where(
        PersonalTask.class_id == class_id, PersonalTask.telegram_id == telegram_id
    )
    if not include_done:
        query = query.where(PersonalTask.done.is_(False))
    query = query.order_by(
        PersonalTask.done.asc(),
        PersonalTask.due_date.asc().nulls_last(),
        PersonalTask.due_time.asc().nulls_last(),
        PersonalTask.priority.desc(),
        PersonalTask.id.asc(),
    ).limit(limit)
    return list(await session.scalars(query))


async def add_task(
    session: AsyncSession,
    class_id: int,
    telegram_id: int,
    title: str,
    *,
    due_date: Date | None = None,
    due_time: Time | None = None,
    priority: int = int(TaskPriority.NORMAL),
    subject_name: str | None = None,
    notes: str | None = None,
    homework_id: int | None = None,
    remind_at: datetime | None = None,
) -> PersonalTask:
    title = " ".join(title.split())[:TITLE_MAX]
    if not title:
        raise ValueError("a task needs a title")
    task = PersonalTask(
        class_id=class_id,
        telegram_id=telegram_id,
        title=title,
        notes=notes or None,
        subject_name=subject_name[:SUBJECT_MAX] if subject_name else None,
        due_date=due_date,
        due_time=due_time,
        priority=_clamp_priority(priority),
        homework_id=homework_id,
        remind_at=remind_at,
    )
    session.add(task)
    await session.commit()
    # Server defaults (``created_at``) are not fetched by the insert; the
    # caller renders the row it gets back, so load them now.
    await session.refresh(task)
    return task


async def set_done(session: AsyncSession, task: PersonalTask, done: bool) -> None:
    task.done = done
    task.done_at = _utcnow() if done else None
    await session.commit()


async def delete_task(session: AsyncSession, task: PersonalTask) -> None:
    await session.delete(task)
    await session.commit()


async def get_task(
    session: AsyncSession, task_id: int, class_id: int, telegram_id: int
) -> PersonalTask | None:
    """Always scoped to the owner: an id from a callback proves nothing by itself."""
    return await session.scalar(
        select(PersonalTask).where(
            PersonalTask.id == task_id,
            PersonalTask.class_id == class_id,
            PersonalTask.telegram_id == telegram_id,
        )
    )


# --------------------------------------------------------------------------
# Homework ticks
# --------------------------------------------------------------------------


async def homework_ticks(
    session: AsyncSession, telegram_id: int, homework_ids: list[int]
) -> set[int]:
    """Which of ``homework_ids`` this person has marked done."""
    if not homework_ids:
        return set()
    rows = await session.scalars(
        select(HomeworkDone.homework_id).where(
            HomeworkDone.telegram_id == telegram_id,
            HomeworkDone.homework_id.in_(list(homework_ids)),
        )
    )
    return set(rows)


async def toggle_homework_done(
    session: AsyncSession, homework: Homework, telegram_id: int
) -> bool:
    """Flip this person's tick on ``homework``; returns the new state."""
    homework_id = homework.id
    existing = await session.scalar(
        select(HomeworkDone).where(
            HomeworkDone.homework_id == homework_id, HomeworkDone.telegram_id == telegram_id
        )
    )
    if existing is not None:
        await session.delete(existing)
        await session.commit()
        return False

    session.add(HomeworkDone(homework_id=homework_id, telegram_id=telegram_id, done_at=_utcnow()))
    try:
        await session.commit()
    except IntegrityError:
        # Two taps in flight at once - the app and the bot, or a double tap on
        # a slow connection. The other one won and the tick is there, which is
        # the state this tap wanted too.
        await session.rollback()
        # The rollback expired every loaded row; in an async session the
        # caller cannot lazily reload ``homework`` when it renders the reply.
        await session.refresh(homework)
    return True
