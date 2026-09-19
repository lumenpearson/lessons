"""Populate a demo class so the Android app has something to talk to.

    python -m scripts.seed_demo

Prints the join code to paste into the app, and a device token that is
already linked to the demo editor account, for trying the write endpoints
with curl. Safe to re-run: it replaces the demo class rather than duplicating
it.
"""

from __future__ import annotations

import asyncio
import os
from datetime import date, datetime, time, timedelta

from sqlalchemy import select

from app.config import get_settings
from app.db import init_db, session_scope
from app.models import (
    DEFAULT_BELLS,
    AuditEntry,
    BellPeriod,
    BellSchedule,
    BotUser,
    DayEvent,
    DeviceToken,
    EventKind,
    Homework,
    LessonOverride,
    OverrideAction,
    PersonalTask,
    ReminderSettings,
    Role,
    SchoolClass,
    Subject,
    TaskPriority,
    TimetableEntry,
    WeekParity,
)
from app.security import hash_token, new_token

DEMO_CODE = "DEMO24"

# A made-up Telegram id for the demo editor. Nothing is ever sent to it: the
# demo has no bot token, and a real id here would mean the seed script could
# message a stranger the moment somebody ran it with one.
DEMO_EDITOR_ID = 100_000_001

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


#: What this script refuses to write to without being told twice.
#:
#: It runs `create_all` and then puts a class with the world-known join code
#: `DEMO24` into it, in `JoinMode.OPEN` — a read-token factory whose curl is
#: printed in `docs/api.md`. Against production that is a live, publicly
#: documented way into a real class, plus DDL on a database the deploy path
#: deliberately stopped running `create_all` on. And it is two lines apart in
#: `CLAUDE.md` from `DATABASE_URL='postgresql+asyncpg://…' alembic upgrade
#: head`, so the shell that ran the first is the shell that runs this one.
#:
#: Its sibling `scripts/init_db` already prints where it is about to write;
#: this printed «Demo class ready. Join code: DEMO24» and nothing else.
LOCAL_SCHEMES = ("sqlite",)


def _target(database_url: str) -> str:
    """Where this is about to write, with any password left out."""
    return database_url.split("@")[-1] if "@" in database_url else database_url


def _refuse_unless_confirmed(database_url: str) -> None:
    if database_url.split(":", 1)[0].split("+", 1)[0] in LOCAL_SCHEMES:
        return
    if os.environ.get("SEED_DEMO_I_MEAN_IT") == "yes":
        print(f"Seeding a NON-LOCAL database on request: {_target(database_url)}")
        return
    raise SystemExit(
        f"Refusing to seed {_target(database_url)}: this is not a local database.\n"
        "The demo class carries the published join code DEMO24 in open mode, so "
        "seeding a real deployment hands out read tokens for a real class.\n"
        "If that is genuinely what you want: SEED_DEMO_I_MEAN_IT=yes"
    )


async def seed() -> None:
    settings = get_settings()
    _refuse_unless_confirmed(settings.database_url)
    print(f"Seeding {_target(settings.database_url)} ...")
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

        # An editor with a phone already linked, so the write endpoints can be
        # tried straight away. The token is printed once and stored hashed.
        session.add(BotUser(telegram_id=DEMO_EDITOR_ID, class_id=klass.id, role=Role.EDITOR))
        device_token = new_token()
        session.add(
            DeviceToken(
                token_hash=hash_token(device_token),
                class_id=klass.id,
                device_name="Демо-телефон",
                telegram_id=DEMO_EDITOR_ID,
                linked_at=datetime.utcnow(),
            )
        )

        # Three personal tasks in the three shapes the list sorts by: a dated
        # urgent one, a dated ordinary one, and one with no date at all.
        session.add(
            PersonalTask(
                class_id=klass.id,
                telegram_id=DEMO_EDITOR_ID,
                title="Сдать реферат по истории",
                subject_name="История",
                due_date=next_weekday,
                due_time=time(8, 30),
                priority=int(TaskPriority.HIGH),
            )
        )
        session.add(
            PersonalTask(
                class_id=klass.id,
                telegram_id=DEMO_EDITOR_ID,
                title="Купить тетрадь в клетку",
                notes="48 листов",
                due_date=today + timedelta(days=3),
                priority=int(TaskPriority.NORMAL),
            )
        )
        session.add(
            PersonalTask(
                class_id=klass.id,
                telegram_id=DEMO_EDITOR_ID,
                title="Записаться на олимпиаду",
                priority=int(TaskPriority.LOW),
            )
        )

        session.add(
            ReminderSettings(
                class_id=klass.id,
                telegram_id=DEMO_EDITOR_ID,
                morning_at=time(7, 30),
                evening_at=time(20, 0),
                notify_changes=True,
                notify_homework=True,
            )
        )

        session.add(
            AuditEntry(
                class_id=klass.id,
                telegram_id=DEMO_EDITOR_ID,
                action="override.replace",
                summary=f"Замена: урок №2, {next_weekday:%d.%m} — Астрономия",
            )
        )

    print(f"Demo class ready. Join code: {DEMO_CODE}")
    print(f"Linked device token (editor): {device_token}")


if __name__ == "__main__":
    asyncio.run(seed())
