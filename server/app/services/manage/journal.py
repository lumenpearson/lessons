"""«📜 Журнал» and ``/manage/log``: who changed what, a page at a time."""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import AuditEntry
from app.services import audit


async def page(
    session: AsyncSession, class_id: int, *, limit: int, offset: int
) -> tuple[list[AuditEntry], bool]:
    """``limit`` lines from ``offset``, newest first, and whether more follow.

    One row more than ``limit`` is read and thrown away: that is what «more»
    is, and it costs one row instead of the count of an append-only table on
    every page turn.
    """
    entries = await audit.recent(session, class_id, limit=limit + 1, offset=offset)
    return entries[:limit], len(entries) > limit


async def has_line(session: AsyncSession, class_id: int, entry_id: int) -> bool:
    """Whether ``entry_id`` names a line of this class's log. A page token of
    another class's log, or of a line that never was, names nothing here."""
    found = await session.scalar(
        select(AuditEntry.id).where(AuditEntry.id == entry_id, AuditEntry.class_id == class_id)
    )
    return found is not None


async def page_after(
    session: AsyncSession, class_id: int, *, limit: int, after_id: int | None
) -> tuple[list[AuditEntry], bool]:
    """``limit`` lines after the line ``after_id``, newest first, and whether
    more follow: v2's page, keyed on the last line it served
    (:func:`app.services.audit.older_than`). One row more is read and thrown
    away, as :func:`page` does, because that is what «more» is."""
    entries = await audit.older_than(session, class_id, after_id, limit=limit + 1)
    return entries[:limit], len(entries) > limit
