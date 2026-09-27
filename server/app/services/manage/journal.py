"""«📜 Журнал» and ``/manage/log``: who changed what, a page at a time."""

from __future__ import annotations

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
