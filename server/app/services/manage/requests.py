"""«🙋 Запрос доступа» and ``/manage/requests``: the requests waiting for an answer.

Granting is :func:`app.services.access.approve_request`, which both shells
already shared; this is the rest of what they wrote twice - finding a request
that is still open, answering yes with the role sent or the one asked for
(:func:`approve`, which v1's router held and v2's ``ApproveAccessRequest``
applies too), and saying no - and what only the bot does, because only
Telegram can be asked in: raising a request, and finding who to tell.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from html import escape

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import AccessRequest, BotUser, Role, SchoolClass
from app.services import access, audit
from app.services.manage import classes

#: The note a requester may leave, in characters; the column is as wide.
NOTE_MAX = 300


async def submit(
    session: AsyncSession, class_id: int, telegram_id: int, note: str | None
) -> AccessRequest:
    """Ask for the editor's role.

    One open request per person per class - a second one replaces the first,
    so a nervous requester cannot fill an admin's screen.
    """
    request = await session.scalar(
        select(AccessRequest).where(
            AccessRequest.class_id == class_id,
            AccessRequest.telegram_id == telegram_id,
            AccessRequest.status == "pending",
        )
    )
    if request is None:
        request = AccessRequest(
            class_id=class_id,
            telegram_id=telegram_id,
            requested_role=Role.EDITOR,
            status="pending",
        )
        session.add(request)
    request.requested_role = Role.EDITOR
    request.status = "pending"
    request.message = (note or None) and note[:NOTE_MAX]
    request.decided_by = None
    request.decided_at = None
    await session.flush()
    return request


async def admins(session: AsyncSession, class_id: int) -> list[BotUser]:
    """Everybody who may answer a request: the admins and the owner."""
    return list(
        await session.scalars(
            select(BotUser).where(
                BotUser.class_id == class_id,
                BotUser.role.in_([Role.ADMIN, Role.OWNER]),
            )
        )
    )


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


@dataclass(frozen=True)
class Approval:
    """What answering yes did."""

    #: The membership as it now stands: made, raised, or left where it was,
    #: because an existing member is never lowered.
    member: BotUser
    #: The role the request was answered with: the one sent, else the one asked for.
    granted: Role
    #: The member as an admin reads them (``classes.display_name``).
    who: str


async def approve(
    session: AsyncSession,
    school_class: SchoolClass,
    request: AccessRequest,
    *,
    actor_id: int,
    actor_role: Role,
    role: Role | None = None,
) -> Approval:
    """Answer yes with ``role``, or with the role asked for when none is sent.

    v1 and v2 let an admin answer with another role than the one asked for;
    the bot always grants the one asked for. Either way it goes through the
    ladder :func:`app.services.access.approve_request` holds. Nothing is
    committed: the caller commits the grant with its audit line, and only then
    tells whoever asked.

    @raises app.services.access.GrantRefused when the ladder does not allow it.
    """
    granted = role if role is not None else request.requested_role
    member = await access.approve_request(
        session,
        school_class,
        request,
        actor_id=actor_id,
        actor_role=actor_role,
        role=granted,
    )
    return Approval(
        member=member,
        granted=granted,
        who=classes.display_name(member.full_name, member.username, member.telegram_id),
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
