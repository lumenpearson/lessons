"""Answering «🙋 Запрос доступа», for both shells at once.

Granting a role from a request is forty lines of rules — who may grant what,
whose role is not yours to touch, create-the-member-or-raise-them, stamp the
request, log it — and it was written twice: once in `api/manage.py` and once in
`bot/handlers/manage.py`. They agreed, but only because somebody kept checking:
two copies of a permission rule are one merge away from disagreeing, and the
half that drifts is the half nobody is looking at.

A refusal carries why it refused, and the surfaces say it differently to
different people: the bot shows Russian in an alert to the admin who pressed
the button, v1 answers a 403 whose `detail` is English like every other detail
it sends, and v2 answers `ROLE_GRANT_REFUSED` with the same English and the
fact itself as `why`. The sentences are `app/wording.py`'s.

Nothing here commits, like the rest of `services/`: the caller commits the
grant together with its audit line, so a role and the record of who handed it
out land as one fact.
"""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app import wording
from app.models import AccessRequest, BotUser, Role, SchoolClass
from app.services import audit
from app.services.roles import can_grant

#: Why a grant was refused: nobody grants a role at or above their own, and a
#: member who is the grantor's peer or senior is not theirs to change. v2
#: sends it as ``ROLE_GRANT_REFUSED``'s ``why`` (``errors.proto``).
ROLE_TOO_HIGH = "role_too_high"
MEMBER_SENIOR = "member_senior"


class GrantRefused(Exception):
    """A grant the role ladder does not allow, and why: :data:`ROLE_TOO_HIGH`
    or :data:`MEMBER_SENIOR`.

    ``why`` is the fact. ``str(…)`` is the Russian sentence the bot shows in an
    alert, and ``detail`` the English one v1 puts in its 403 and v2 in its
    refusal, both looked up by ``why`` in ``app/wording.py``. One exception
    rather than two return values, because every refusal here ends the same
    way: nothing is written and somebody is told why.
    """

    def __init__(self, why: str) -> None:
        super().__init__(wording.GRANT_REFUSED_ALERTS[why])
        self.why = why
        self.detail = wording.GRANT_REFUSED_DETAILS[why]


async def approve_request(
    session: AsyncSession,
    school_class: SchoolClass,
    request: AccessRequest,
    *,
    actor_id: int,
    actor_role: Role,
    role: Role,
) -> BotUser:
    """Grant ``role`` to whoever raised ``request``, and stamp the request.

    ``can_grant`` is the single source of "may I hand out this role" — nobody
    may grant at or above their own level, and OWNER is granted by the
    environment alone — and the rank guard is the one «👥 Доступ» applies when
    changing an existing member: a peer's role is not yours to change. Without
    both, an admin could promote a friend to admin and be demoted by them a
    moment later.

    ``role`` is a parameter rather than ``request.requested_role`` because the
    API lets an admin answer with a different role than the one asked for; the
    bot always passes the role that was asked for. Either way it goes through
    ``can_grant``.

    An existing member is never lowered: a request for EDITOR approved for
    somebody who is already an ADMIN leaves them an ADMIN. Whatever is said
    about the answer names the role the returned row holds, never ``role``
    (#368): ``requests.approve`` carries that fact to the three shells.

    @raises GrantRefused when the actor may not grant this role, or when the
        member is their peer or senior.
    @return the membership row as it now stands. Nothing is committed.
    """
    if not can_grant(actor_role, role):
        raise GrantRefused(ROLE_TOO_HIGH)

    member = await member_of(session, school_class, request.telegram_id)
    if member is not None and member.role.rank >= actor_role.rank:
        raise GrantRefused(MEMBER_SENIOR)

    already = holds_already(member, role)
    if member is None:
        member = BotUser(
            telegram_id=request.telegram_id,
            class_id=school_class.id,
            role=role,
            granted_by=actor_id,
        )
        session.add(member)
    elif member.role.rank < role.rank:
        member.role = role
        member.granted_by = actor_id

    request.status = "approved"
    request.decided_by = actor_id
    request.decided_at = datetime.now(UTC).replace(tzinfo=None)

    who = member.full_name or member.telegram_id
    await audit.record(
        session,
        school_class.id,
        actor_id,
        "access.approve",
        # The journal says what happened: a yes that raised nobody granted
        # nothing, and «выдана роль Редактор» beside an admin was untrue (#368).
        f"одобрен запрос на роль {role.title_ru}: {who} уже {member.role.title_ru}"
        if already
        else f"выдана роль {role.title_ru}: {who}",
    )
    return member


async def member_of(
    session: AsyncSession, school_class: SchoolClass, telegram_id: int
) -> BotUser | None:
    """The membership of ``telegram_id`` in the class, if they have one."""
    return await session.scalar(
        select(BotUser).where(
            BotUser.class_id == school_class.id,
            BotUser.telegram_id == telegram_id,
        )
    )


def holds_already(member: BotUser | None, role: Role) -> bool:
    """Whether ``member`` holds ``role`` or a higher one, so granting it raises
    nobody: :func:`approve_request` never lowers a member."""
    return member is not None and member.role.rank >= role.rank


def approval_notice(school_class: SchoolClass, role: Role, *, already: bool = False) -> str:
    """What the requester is told, in the chat where they asked:
    ``wording.approval_notice`` for this class.

    ``role`` is the role they hold after the answer, and ``already`` says they
    held it before it (``Approval.already``), so the notice never names a role
    they were not given.
    """
    return wording.approval_notice(school_class.name, role, already=already)
