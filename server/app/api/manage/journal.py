"""``/log``: «📜 Журнал», a page at a time.

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring.
"""

from __future__ import annotations

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class
from app.api.manage._common import Actor, _member_names, _wall, admin_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import AuditEntry, SchoolClass
from app.schemas import AuditEntryOut, AuditPageOut
from app.services import audit

router = APIRouter(route_class=DishkaAnnotatedRoute)

#: Audit lines per page, matching «📜 Журнал» in the bot.
AUDIT_PAGE = 30


# --------------------------------------------------------------------------
# 📜 The audit log
# --------------------------------------------------------------------------


@router.get("/log", response_model=AuditPageOut)
async def audit_log(
    limit: int = Query(default=AUDIT_PAGE, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    _: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> AuditPageOut:
    """Who changed what, newest first.

    One row more than ``limit`` is read and thrown away: that is what
    ``has_more`` is, and it costs one row instead of the count of an
    append-only table on every page turn.
    """
    entries = await audit.recent(session, school_class.id, limit=limit + 1, offset=offset)
    has_more = len(entries) > limit
    entries = entries[:limit]
    names = await _member_names(session, school_class.id)

    def _entry(row: AuditEntry) -> AuditEntryOut:
        return AuditEntryOut(
            id=row.id,
            action=row.action,
            summary=row.summary,
            who=names.get(row.telegram_id) if row.telegram_id is not None else None,
            at=_wall(row.created_at, school_class),
        )

    return AuditPageOut(
        entries=[_entry(row) for row in entries],
        limit=limit,
        offset=offset,
        has_more=has_more,
    )
