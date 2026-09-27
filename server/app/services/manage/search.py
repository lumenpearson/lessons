"""«🔎 /find»: a class's homework, reachable by memory instead of by date."""

from __future__ import annotations

from datetime import date as Date

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Homework


async def homework(
    session: AsyncSession, class_id: int, needle: str, *, since: Date, limit: int
) -> list[Homework]:
    """Assignments due from ``since`` on whose text or subject contains ``needle``.

    Newest first, at most ``limit``. ``lower().contains()`` rather than ILIKE,
    which SQLite does not have. Both dialects fold Cyrillic: Postgres does it
    natively, and ``app.db`` replaces SQLite's ASCII-only ``lower()`` with
    Python's on every connection, so the search behaves the same for the
    developer and for the class.
    """
    pattern = needle.lower()
    return list(
        await session.scalars(
            select(Homework)
            .where(
                Homework.class_id == class_id,
                Homework.due_date >= since,
                func.lower(Homework.text).contains(pattern)
                | func.lower(Homework.subject_name).contains(pattern),
            )
            .order_by(Homework.due_date.desc(), Homework.id.desc())
            .limit(limit)
        )
    )
