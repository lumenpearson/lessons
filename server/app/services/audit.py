"""The audit log: one line per change, attributed to whoever made it.

:func:`record` only stages the row. The caller commits it together with the
change it describes, so the two land together or not at all: an entry saying
«удалено ДЗ» about homework that is still there, because the delete failed
after the log line went in, would be worse than no entry.
"""

from __future__ import annotations

from sqlalchemy import and_, or_, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import aliased

from app.models import AuditEntry

#: Column widths. Anything longer is cut rather than refused, because the log
#: must never be the reason a legitimate edit fails.
SUMMARY_MAX = 500
ACTION_MAX = 64

#: Newest first. ``id`` breaks ties: ``created_at`` is a server default with
#: one-second resolution on SQLite and one transaction's ``now()`` on
#: Postgres, and a handler that logs two lines in one go would otherwise show
#: them in arbitrary order. Every reader of the log pages in this order.
NEWEST_FIRST = (AuditEntry.created_at.desc(), AuditEntry.id.desc())


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
    """Newest first, in :data:`NEWEST_FIRST` order."""
    rows = await session.scalars(
        select(AuditEntry)
        .where(AuditEntry.class_id == class_id)
        .order_by(*NEWEST_FIRST)
        .limit(limit)
        .offset(offset)
    )
    return list(rows)


async def older_than(
    session: AsyncSession, class_id: int, after_id: int | None, *, limit: int
) -> list[AuditEntry]:
    """Up to ``limit`` lines after the line ``after_id`` in :data:`NEWEST_FIRST`
    order, from the newest when ``after_id`` is ``None``.

    Keyed on a line rather than counted from the top, so a line written
    between two page turns shifts nothing: an offset would show the last line
    of one page again at the top of the next. The anchor's ``created_at`` is
    read in SQL rather than bound from Python, because SQLite stores a
    server-default stamp without microseconds and a bound one with them, and
    the two would compare as different strings at the same instant.
    """
    query = select(AuditEntry).where(AuditEntry.class_id == class_id)
    if after_id is not None:
        anchor = aliased(AuditEntry)
        at = select(anchor.created_at).where(anchor.id == after_id).scalar_subquery()
        query = query.where(
            or_(
                AuditEntry.created_at < at,
                and_(AuditEntry.created_at == at, AuditEntry.id < after_id),
            )
        )
    return list(await session.scalars(query.order_by(*NEWEST_FIRST).limit(limit)))
