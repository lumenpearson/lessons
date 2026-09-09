"""Populate a demo class so the Android app has something to talk to.

    python -m scripts.seed_demo

Prints the join code to paste into the app. Safe to re-run: it replaces the
demo class rather than duplicating it.
"""

from __future__ import annotations

import asyncio
from datetime import date, time, timedelta

from sqlalchemy import select

from app.db import init_db, session_scope
from app.models import (
    DEFAULT_BELLS,
    BellPeriod,
    BellSchedule,
    DayEvent,
    EventKind,
    Homework,
    LessonOverride,
    OverrideAction,
    SchoolClass,
    Subject,
    TimetableEntry,
    WeekParity,
)

DEMO_CODE = "DEMO24"

WEEK = {
    1: ["Алгебра", "Физика", "История", "Английский", "Литература"],
    2: ["Геометрия", "Химия", "Биология", "Физкультура", "Информатика"],
    3: ["Алгебра", "Русский язык", "География", "История", "Физика"],
    4: ["Геометрия", "Литература", "Английский", "Химия", "Обществознание"],
    5: ["Информатика", "Физкультура", "Алгебра", "Биология", "Классный час"],
}

ROOMS = {
    "Алгебра": "214",
    "Геометрия": "214",
    "Физика": "305",
    "Химия": "118",
    "Биология": "120",
    "История": "402",
    "Информатика": "217",
    "Английский": "310",
}

COLORS = {
    "Алгебра": "#5B6ABF",
    "Геометрия": "#5B6ABF",
    "Физика": "#3E8E7E",
    "Химия": "#C46A4A",
    "Биология": "#6AA84F",
    "История": "#8E6AC4",
    "Информатика": "#4A90C4",
    "Литература": "#C4586A",
}


async def seed() -> None:
    await init_db()

    async with session_scope() as session:
        existing = await session.scalar(
            select(SchoolClass).where(SchoolClass.join_code == DEMO_CODE)
        )
        if existing is not None:
            await session.delete(existing)
            await session.flush()

        klass = SchoolClass(
            name="9А",
            school="Демо-школа № 1",
            city="Санкт-Петербург",
            timezone="Europe/Moscow",
            join_code=DEMO_CODE,
        )
        session.add(klass)
        await session.flush()

        bells = BellSchedule(class_id=klass.id, name="Обычное")
        session.add(bells)
        await session.flush()
        for index, starts_at, ends_at in DEFAULT_BELLS:
            session.add(
                BellPeriod(
                    schedule_id=bells.id, index=index, starts_at=starts_at, ends_at=ends_at
                )
            )
        klass.bell_schedule_id = bells.id

        for name, color in COLORS.items():
            session.add(
                Subject(class_id=klass.id, name=name, color=color, teacher=None)
            )

        for weekday, subjects in WEEK.items():
            for index, subject in enumerate(subjects, start=1):
                session.add(
                    TimetableEntry(
                        class_id=klass.id,
                        weekday=weekday,
                        index=index,
                        subject_name=subject,
                        room=ROOMS.get(subject),
                        parity=WeekParity.ANY,
                    )
                )

        today = date.today()
        # A canteen break every school day of the coming week.
        for offset in range(7):
            day = today + timedelta(days=offset)
            if day.isoweekday() > 5:
                continue
            session.add(
                DayEvent(
                    class_id=klass.id,
                    date=day,
                    starts_at=time(11, 10),
                    ends_at=time(11, 25),
                    title="Обед",
                    kind=EventKind.CANTEEN,
                    location="Столовая",
                )
            )

        # Homework for the next few school days.
        for offset in range(1, 6):
            day = today + timedelta(days=offset)
            if day.isoweekday() > 5:
                continue
            for subject in WEEK[day.isoweekday()][:3]:
                session.add(
                    Homework(
                        class_id=klass.id,
                        due_date=day,
                        subject_name=subject,
                        text=f"Повторить материал по теме «{subject}», упражнения 1–5.",
                    )
                )

        # One замена and one cancellation, so the client has something to render.
        next_weekday = today + timedelta(days=1)
        while next_weekday.isoweekday() > 5:
            next_weekday += timedelta(days=1)
        session.add(
            LessonOverride(
                class_id=klass.id,
                date=next_weekday,
                index=2,
                action=OverrideAction.REPLACE,
                subject_name="Астрономия",
                room="305",
                note="Замена: учитель на конференции",
            )
        )
        session.add(
            LessonOverride(
                class_id=klass.id,
                date=next_weekday,
                index=5,
                action=OverrideAction.CANCEL,
                note="Отменён",
            )
        )

    print(f"Demo class ready. Join code: {DEMO_CODE}")


if __name__ == "__main__":
    asyncio.run(seed())
