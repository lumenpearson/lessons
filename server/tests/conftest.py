"""Test fixtures.

The environment is configured *before* any app module is imported, because
``app.db`` builds its engine at import time from the cached settings.
"""

from __future__ import annotations

import os
import tempfile
from collections.abc import AsyncIterator
from pathlib import Path

import pytest

_TMP_DIR = Path(tempfile.mkdtemp(prefix="lessons-tests-"))
os.environ["DATABASE_URL"] = f"sqlite+aiosqlite:///{_TMP_DIR / 'test.db'}"
os.environ["RUN_BOT"] = "false"
os.environ["BOT_TOKEN"] = ""
os.environ["OWNER_IDS"] = "1000"
os.environ["TIMEZONE"] = "Europe/Moscow"
# The diary refuses to run without a key, on purpose (app/crypto.py). Tests
# that exercise it therefore have to configure one; that the *absence* of one
# switches the feature off is itself a test, in test_diary_crypto.py.
os.environ["DIARY_SECRET"] = "test-secret-not-a-real-one-0123456789abcdef"

from app.db import Base, SessionLocal, engine  # noqa: E402
from app.models import (  # noqa: E402
    DEFAULT_BELLS,
    BellPeriod,
    BellSchedule,
    SchoolClass,
    TimetableEntry,
    WeekParity,
)


@pytest.fixture(autouse=True)
async def fresh_database() -> AsyncIterator[None]:
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
        await conn.run_sync(Base.metadata.create_all)
    yield


@pytest.fixture
async def session() -> AsyncIterator:
    async with SessionLocal() as db:
        yield db


@pytest.fixture
async def school_class(session) -> SchoolClass:
    """A class with the default bells and a three-lesson Monday."""
    bells = BellSchedule(class_id=0, name="Обычное")
    klass = SchoolClass(name="9А", school="Школа № 1", join_code="TEST42")
    session.add(klass)
    await session.flush()

    bells.class_id = klass.id
    session.add(bells)
    await session.flush()

    for index, starts_at, ends_at in DEFAULT_BELLS:
        session.add(
            BellPeriod(schedule_id=bells.id, index=index, starts_at=starts_at, ends_at=ends_at)
        )
    klass.bell_schedule_id = bells.id

    # Monday (weekday 1)
    for index, subject, room in [(1, "Алгебра", "214"), (2, "Физика", "305"), (3, "История", None)]:
        session.add(
            TimetableEntry(
                class_id=klass.id,
                weekday=1,
                index=index,
                subject_name=subject,
                room=room,
                parity=WeekParity.ANY,
            )
        )
    await session.commit()
    return klass
