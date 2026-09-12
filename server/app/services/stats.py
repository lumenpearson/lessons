"""«📊 Статистика»: a one-screen picture of the class for its admins.

Counts, not history. What an admin wants to know is whether the timetable is
complete, whether homework is being entered and how many phones are actually
connected - the numbers that say whether the class is using the thing.
"""

from __future__ import annotations

from datetime import datetime
from html import escape

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import (
    BotUser,
    DayEvent,
    DayOverride,
    DeviceToken,
    Homework,
    LessonOverride,
    Role,
    SchoolClass,
    TimetableEntry,
    WeekParity,
)

#: Subjects listed before «… и ещё N».
MAX_SUBJECT_ROWS = 15


async def _count(session: AsyncSession, query) -> int:
    return int(await session.scalar(select(func.count()).select_from(query.subquery())) or 0)


async def class_stats(session: AsyncSession, school_class: SchoolClass) -> dict:
    """Numbers only; :func:`render_stats` turns them into a message."""
    class_id = school_class.id
    # The class's own zone: an event at 09:00 Vladivostok is already past
    # when a Moscow server still thinks it is last night.
    today = datetime.now(school_class.tz).date()

    entries = await session.scalars(
        select(TimetableEntry).where(TimetableEntry.class_id == class_id)
    )
    # A lesson that alternates weeks is half a lesson a week on average, which
    # is what «часов в неделю» means on a school's own paperwork.
    hours: dict[str, float] = {}
    for entry in entries:
        weight = 1.0 if entry.parity is WeekParity.ANY else 0.5
        hours[entry.subject_name] = hours.get(entry.subject_name, 0.0) + weight
    subjects = sorted(hours.items(), key=lambda item: (-item[1], item[0]))

    members_rows = await session.execute(
        select(BotUser.role, func.count())
        .where(BotUser.class_id == class_id)
        .group_by(BotUser.role)
    )
    members_by_role: dict[Role, int] = {role: int(count) for role, count in members_rows}

    return {
        "today": today,
        "lessons_per_week": sum(hours.values()),
        "subjects": subjects,
        "subjects_count": len(hours),
        "homework_open": await _count(
            session,
            select(Homework.id).where(Homework.class_id == class_id, Homework.due_date >= today),
        ),
        "homework_total": await _count(
            session, select(Homework.id).where(Homework.class_id == class_id)
        ),
        "members_by_role": members_by_role,
        "devices_active": await _count(
            session,
            select(DeviceToken.id).where(
                DeviceToken.class_id == class_id, DeviceToken.revoked.is_(False)
            ),
        ),
        "overrides_upcoming": (
            await _count(
                session,
                select(LessonOverride.id).where(
                    LessonOverride.class_id == class_id, LessonOverride.date >= today
                ),
            )
            + await _count(
                session,
                select(DayOverride.id).where(
                    DayOverride.class_id == class_id, DayOverride.date >= today
                ),
            )
        ),
        "events_upcoming": await _count(
            session,
            select(DayEvent.id).where(DayEvent.class_id == class_id, DayEvent.date >= today),
        ),
    }


def format_hours(value: float) -> str:
    """"4", "3,5" - a decimal comma, the way Russian paperwork writes it."""
    if float(value).is_integer():
        return str(int(value))
    return f"{value:.1f}".replace(".", ",")


def render_stats(stats: dict, school_class: SchoolClass) -> str:
    members = stats["members_by_role"]
    if members:
        roster = " · ".join(
            f"{role.title_ru}: {members[role]}"
            for role in sorted(members, key=lambda r: r.rank, reverse=True)
        )
    else:
        roster = "пока никого"

    lines = [
        f"📊 <b>Статистика класса {escape(school_class.name)}</b>",
        "",
        f"🧩 Уроков в неделю: <b>{format_hours(stats['lessons_per_week'])}</b>, "
        f"предметов: <b>{stats['subjects_count']}</b>",
        f"📝 Домашних заданий: <b>{stats['homework_open']}</b> актуальных, "
        f"всего <b>{stats['homework_total']}</b>",
        f"🔄 Замен и особых дней впереди: <b>{stats['overrides_upcoming']}</b>",
        f"🎉 Событий впереди: <b>{stats['events_upcoming']}</b>",
        f"👥 Участники: {roster}",
        f"📱 Подключённых устройств: <b>{stats['devices_active']}</b>",
    ]

    subjects = stats["subjects"]
    if subjects:
        lines.append("")
        lines.append("<b>Часов в неделю по предметам</b>")
        for name, hours in subjects[:MAX_SUBJECT_ROWS]:
            lines.append(f"• {escape(name)} — {format_hours(hours)}")
        hidden = len(subjects) - MAX_SUBJECT_ROWS
        if hidden > 0:
            lines.append(f"… и ещё {hidden}")
    else:
        lines.append("")
        lines.append("<i>Расписание ещё не заполнено.</i>")

    return "\n".join(lines)
