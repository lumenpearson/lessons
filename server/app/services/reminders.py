"""Morning and evening digests and one-off task reminders.

There is no scheduler in a serverless deployment, so nothing here runs on its
own: an external cron calls the tick endpoint every few minutes and the
endpoint calls :func:`send_due`. Everything is therefore written to be safe to
run twice in the same minute and safe to run an hour late. What is due is
decided from the class's own clock and from what was already sent today,
never from "when did the last tick run" - and each row is marked sent
*before* the message goes out, so a crash mid-send costs one digest rather
than repeating it.
"""

from __future__ import annotations

import logging
import re
from datetime import UTC, datetime, timedelta
from datetime import date as Date
from datetime import time as Time
from html import escape
from typing import Any

from sqlalchemy import and_, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.render import MONTHS_GENITIVE, human_date, relative_day_name, render_day
from app.models import Homework, PersonalTask, ReminderSettings, SchoolClass
from app.schedule import ResolvedDay, ScheduleResolver
from app.services.tasks import homework_ticks

log = logging.getLogger(__name__)

#: Rows handed out per tick. A tick is one serverless invocation with a time
#: limit; whatever it does not get to is still due on the next one.
MAX_PER_TICK = 200

#: Homework lines in an evening digest before «… и ещё N».
MAX_DIGEST_LINES = 30

# No inhabited zone is further ahead of UTC than +14 (Kiribati; Kamchatka is
# +12). "Now" in any class's zone is therefore at most this far past UTC now,
# which lets the queries below discard rows that cannot be due yet without
# knowing each class's zone.
_MAX_ZONE_OFFSET = timedelta(hours=14)

_CLOCK = re.compile(r"^\s*(?P<hour>\d{1,2})(?:[:.\-\s]?(?P<minute>\d{2}))?\s*$")


# --------------------------------------------------------------------------
# Time helpers
# --------------------------------------------------------------------------


def _as_utc(now_utc: datetime) -> datetime:
    """Aware UTC, whether the caller passed an aware instant or a naive UTC one."""
    if now_utc.tzinfo is None:
        return now_utc.replace(tzinfo=UTC)
    return now_utc.astimezone(UTC)


def local_now(now_utc: datetime, school_class: SchoolClass) -> datetime:
    """Naive wall time in the class's zone - the clock every column here uses."""
    return _as_utc(now_utc).astimezone(school_class.tz).replace(tzinfo=None)


def parse_clock(raw: str) -> Time | None:
    """"7:30", "07.30", "7", "1930", "7-30" -> a time; anything else -> None."""
    match = _CLOCK.match(raw)
    if match is None:
        return None
    hour = int(match.group("hour"))
    minute = int(match.group("minute") or 0)
    if hour > 23 or minute > 59:
        return None
    return Time(hour, minute)


# --------------------------------------------------------------------------
# Settings
# --------------------------------------------------------------------------


async def settings_for(
    session: AsyncSession, class_id: int, telegram_id: int, create: bool = True
) -> ReminderSettings | None:
    """This person's row for this class, created with the defaults on first use."""
    query = select(ReminderSettings).where(
        ReminderSettings.class_id == class_id, ReminderSettings.telegram_id == telegram_id
    )
    settings = await session.scalar(query)
    if settings is not None or not create:
        return settings

    session.add(ReminderSettings(class_id=class_id, telegram_id=telegram_id))
    try:
        await session.commit()
    except IntegrityError:
        # Two invocations creating the same row at once; one of them lost to
        # the unique constraint and simply reads what the other wrote.
        await session.rollback()
    return await session.scalar(query)


# --------------------------------------------------------------------------
# What is due
# --------------------------------------------------------------------------


async def due_digests(
    session: AsyncSession, now_utc: datetime
) -> list[tuple[ReminderSettings, SchoolClass, str]]:
    """Every (settings, class, "morning"|"evening") whose time has passed today
    in the class's zone and which has not been sent today."""
    upper_day = (_as_utc(now_utc) + _MAX_ZONE_OFFSET).date()

    # Pre-filter in SQL on what can be decided without a zone: a row sent on
    # the latest date it can possibly be anywhere is sent today everywhere.
    # The exact test per class happens below.
    not_sent_morning = or_(
        ReminderSettings.last_morning_sent.is_(None),
        ReminderSettings.last_morning_sent < upper_day,
    )
    not_sent_evening = or_(
        ReminderSettings.last_evening_sent.is_(None),
        ReminderSettings.last_evening_sent < upper_day,
    )
    rows = await session.execute(
        select(ReminderSettings, SchoolClass)
        .join(SchoolClass, SchoolClass.id == ReminderSettings.class_id)
        .where(
            or_(
                and_(ReminderSettings.morning_at.is_not(None), not_sent_morning),
                and_(ReminderSettings.evening_at.is_not(None), not_sent_evening),
            )
        )
        .order_by(ReminderSettings.id)
    )

    due: list[tuple[ReminderSettings, SchoolClass, str]] = []
    for settings, school_class in rows:
        local = local_now(now_utc, school_class)
        today, clock = local.date(), local.time()
        if (
            settings.morning_at is not None
            and clock >= settings.morning_at
            and settings.last_morning_sent != today
        ):
            due.append((settings, school_class, "morning"))
        if (
            settings.evening_at is not None
            and clock >= settings.evening_at
            and settings.last_evening_sent != today
        ):
            due.append((settings, school_class, "evening"))
        if len(due) >= MAX_PER_TICK:
            break
    return due[:MAX_PER_TICK]


async def mark_sent(
    session: AsyncSession, settings: ReminderSettings, kind: str, day: Date
) -> None:
    if kind == "morning":
        settings.last_morning_sent = day
    elif kind == "evening":
        settings.last_evening_sent = day
    else:
        raise ValueError(f"unknown digest kind: {kind!r}")
    await session.commit()


async def due_task_reminders(
    session: AsyncSession, now_utc: datetime
) -> list[tuple[PersonalTask, SchoolClass]]:
    """Undone tasks whose ``remind_at`` (class wall time) has passed."""
    upper = (_as_utc(now_utc) + _MAX_ZONE_OFFSET).replace(tzinfo=None)
    rows = await session.execute(
        select(PersonalTask, SchoolClass)
        .join(SchoolClass, SchoolClass.id == PersonalTask.class_id)
        .where(
            PersonalTask.remind_at.is_not(None),
            PersonalTask.done.is_(False),
            PersonalTask.remind_at <= upper,
        )
        .order_by(PersonalTask.remind_at, PersonalTask.id)
    )
    due: list[tuple[PersonalTask, SchoolClass]] = []
    for task, school_class in rows:
        if task.remind_at <= local_now(now_utc, school_class):
            due.append((task, school_class))
            if len(due) >= MAX_PER_TICK:
                break
    return due


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------


def render_morning(day: ResolvedDay, today: Date) -> str:
    return "☀️ Доброе утро!\n\n" + render_day(day, today)


def _when(day: Date, today: Date) -> str:
    """"на завтра, 8 сентября" / "на понедельник, 14 сентября" / "на 21 сентября"."""
    label = relative_day_name(day, today)
    if (day - today).days <= 7:
        label += f", {day.day} {MONTHS_GENITIVE[day.month - 1]}"
    return label


def render_evening(next_day: ResolvedDay | None, today: Date, done_subjects: set[str]) -> str:
    """Homework due on the next school day, with this person's ticks."""
    if next_day is None:
        return "🌙 Добрый вечер! Ближайший учебный день ещё не назначен — домашних заданий нет."

    when = escape(_when(next_day.date, today))
    if not next_day.homework:
        return f"🌙 Добрый вечер! Домашних заданий {when} нет."

    lines = [f"🌙 Добрый вечер! Домашнее задание {when}:", ""]
    shown = next_day.homework[:MAX_DIGEST_LINES]
    for item in shown:
        mark = "✅" if item.subject in done_subjects else "📝"
        lines.append(f"{mark} <b>{escape(item.subject)}</b>: {escape(item.text)}")
    hidden = len(next_day.homework) - len(shown)
    if hidden > 0:
        lines.append(f"… и ещё {hidden}")
    if all(item.subject in done_subjects for item in next_day.homework):
        lines.append("")
        lines.append("Всё сделано 👍")
    return "\n".join(lines)


def render_task_reminder(task: PersonalTask, today: Date) -> str:
    lines = ["⏰ Напоминание", "", f"<b>{escape(task.title)}</b>"]
    if task.due_date is not None:
        due = human_date(task.due_date, today)
        if task.due_time is not None:
            due += f", {task.due_time:%H:%M}"
        lines.append(f"Срок: {escape(due)}")
    if task.notes:
        lines.append(f"<i>{escape(task.notes)}</i>")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# Sending
# --------------------------------------------------------------------------


async def _digest_text(
    session: AsyncSession,
    school_class: SchoolClass,
    telegram_id: int,
    kind: str,
    today: Date,
    cache: dict[tuple[Any, ...], Any],
) -> str | None:
    """The message for one recipient, or ``None`` when there is nothing to say.

    A class's day is resolved once per tick and shared by its subscribers -
    thirty pupils at the same 07:30 must not mean thirty resolutions.

    Silence is deliberate on a day with no lessons and no events (a weekend,
    the holidays) and on an evening with no school day in sight: a subscriber
    who asked for "today's lessons" did not ask to be told «Уроков нет» every
    morning of the summer.
    """
    resolver = ScheduleResolver(session, school_class)

    if kind == "morning":
        key = ("day", school_class.id, today)
        if key not in cache:
            cache[key] = (await resolver.resolve_range(today, 1))[0]
        day: ResolvedDay = cache[key]
        if not day.lessons and not day.events:
            return None
        return render_morning(day, today)

    key = ("next", school_class.id, today)
    if key not in cache:
        cache[key] = await resolver.next_school_day(today)
    next_day: ResolvedDay | None = cache[key]
    if next_day is None:
        return None

    key = ("homework", school_class.id, next_day.date)
    if key not in cache:
        cache[key] = list(
            await session.scalars(
                select(Homework).where(
                    Homework.class_id == school_class.id, Homework.due_date == next_day.date
                )
            )
        )
    homework: list[Homework] = cache[key]
    ticks = await homework_ticks(session, telegram_id, [item.id for item in homework])
    done_subjects = {item.subject_name for item in homework if item.id in ticks}
    return render_evening(next_day, today, done_subjects)


async def send_due(session: AsyncSession, bot: Any, now_utc: datetime) -> dict[str, int]:
    """One tick: send everything due and account for it. Safe to repeat."""
    from aiogram.exceptions import TelegramForbiddenError

    counts = {"morning": 0, "evening": 0, "tasks": 0, "failed": 0}
    cache: dict[tuple[Any, ...], Any] = {}

    for settings, school_class, kind in await due_digests(session, now_utc):
        today = local_now(now_utc, school_class).date()
        # Committed before the send: a crash between here and the send loses
        # one digest, whereas the other order repeats it on every retry.
        await mark_sent(session, settings, kind, today)
        try:
            text = await _digest_text(
                session, school_class, settings.telegram_id, kind, today, cache
            )
        except Exception:  # noqa: BLE001 - one broken class must not stop the tick
            log.exception("could not build %s digest for class %s", kind, school_class.id)
            counts["failed"] += 1
            continue
        if text is None:
            continue
        try:
            await bot.send_message(settings.telegram_id, text)
        except TelegramForbiddenError:
            # They blocked the bot. Nothing will ever reach them again, so
            # stop trying rather than fail on every tick for the rest of time.
            settings.morning_at = None
            settings.evening_at = None
            await session.commit()
            counts["failed"] += 1
        except Exception:  # noqa: BLE001 - a network blip for one person is not the tick's problem
            log.warning("could not send %s digest to %s", kind, settings.telegram_id, exc_info=True)
            counts["failed"] += 1
        else:
            counts[kind] += 1

    for task, school_class in await due_task_reminders(session, now_utc):
        today = local_now(now_utc, school_class).date()
        task.remind_at = None
        await session.commit()
        try:
            await bot.send_message(task.telegram_id, render_task_reminder(task, today))
        except Exception:  # noqa: BLE001 - same reasoning as above
            log.warning("could not send task reminder to %s", task.telegram_id, exc_info=True)
            counts["failed"] += 1
        else:
            counts["tasks"] += 1

    return counts
