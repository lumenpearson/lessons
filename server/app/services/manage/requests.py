"""«🙋 Запрос доступа» and ``/manage/requests``: the requests waiting for an answer.

Granting is :func:`app.services.access.approve_request`, which both shells
already shared; this is the rest of what they wrote twice - finding a request
that is still open, and saying no.
"""

from __future__ import annotations

from datetime import UTC, datetime
from html import escape

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import AccessRequest, SchoolClass
from app.services import audit


async def pending(session: AsyncSession, class_id: int) -> list[AccessRequest]:
    """Everybody waiting for a role, oldest first."""
    return list(
        await session.scalars(
            select(AccessRequest)
            .where(AccessRequest.class_id == class_id, AccessRequest.status == "pending")
            .order_by(AccessRequest.id)
        )
    )


async def pending_one(
    session: AsyncSession, class_id: int, request_id: int
) -> AccessRequest | None:
    """Pending, and this class's. A request that has already been answered is
    gone as far as either shell is concerned, so two admins tapping «Выдать» at
    once cannot grant twice."""
    return await session.scalar(
        select(AccessRequest).where(
            AccessRequest.id == request_id,
            AccessRequest.class_id == class_id,
            AccessRequest.status == "pending",
        )
    )


async def decline(
    session: AsyncSession, class_id: int, actor_id: int, request: AccessRequest
) -> None:
    """Say no. The person keeps whatever role they already had."""
    request.status = "declined"
    request.decided_by = actor_id
    request.decided_at = datetime.now(UTC).replace(tzinfo=None)
    await audit.record(
        session,
        class_id,
        actor_id,
        "access.decline",
        f"отклонён запрос доступа от {request.telegram_id}",
    )


def decline_notice(school_class: SchoolClass) -> str:
    """What the requester is told in Telegram - silence would leave them asking
    again. HTML, because it goes out through the bot."""
    return f"✖️ Запрос доступа в классе <b>{escape(school_class.name)}</b> отклонён."
