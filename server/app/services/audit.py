"""The audit log: one line per change, attributed to whoever made it.

:func:`record` only stages the row. The caller commits it together with the
change it describes, so the two land together or not at all: an entry saying
«удалено ДЗ» about homework that is still there, because the delete failed
after the log line went in, would be worse than no entry.
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import AuditEntry

#: Column widths. Anything longer is cut rather than refused, because the log
#: must never be the reason a legitimate edit fails.
SUMMARY_MAX = 500
ACTION_MAX = 64


async def record(
    session: AsyncSession,
    class_id: int,
    telegram_id: int | None,
    action: str,
    summary: str,
) -> None:
    """Stage one log line. Does not commit; the caller's transaction does."""
    session.add(
        AuditEntry(
            class_id=class_id,
            telegram_id=telegram_id,
            action=action[:ACTION_MAX],
            summary=summary[:SUMMARY_MAX],
        )
    )


async def recent(
    session: AsyncSession, class_id: int, limit: int = 30, offset: int = 0
) -> list[AuditEntry]:
    """Newest first.

    ``id`` breaks ties: ``created_at`` is a server default with one-second
    resolution on SQLite, and a handler that logs two lines in one go would
    otherwise show them in arbitrary order.
    """
    rows = await session.scalars(
        select(AuditEntry)
        .where(AuditEntry.class_id == class_id)
        .order_by(AuditEntry.created_at.desc(), AuditEntry.id.desc())
        .limit(limit)
        .offset(offset)
    )
    return list(rows)
