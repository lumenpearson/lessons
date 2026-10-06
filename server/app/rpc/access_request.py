"""``AccessRequestService``: «🙋 Запросы доступа», and the admin's answer.

v1's ``/manage/requests``, over the same services. The list is the class's
open requests, oldest first, each named as an admin reads them; v1 wrote when
each was asked as the class's wall time, v2 writes an instant.

Answering is ``requests_service.approve`` and ``decline``, which v1 calls too:
the ladder of «👥 Доступ» decides what an admin may grant, and a request
already answered is ``RESOURCE_NOT_FOUND``, so two admins cannot answer one
twice. Whoever asked is told in Telegram, where they asked, by an effect the
call runs once the decision is committed (``Call.after_commit``): never on a
refusal, and a notice Telegram will not deliver — the person blocked the bot,
or Telegram is down — is logged and dropped, because the decision stands
(``docs/specs/2026-10-05-server-v2-design.md``, decision 4 and «Risks»).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app import telegram_send, wording
from app.contract.lessons.v2.access_request_pb import (
    AccessRequest,
    ApproveAccessRequestRequest,
    ApproveAccessRequestResponse,
    DeclineAccessRequestRequest,
    DeclineAccessRequestResponse,
    ListAccessRequestsRequest,
    ListAccessRequestsResponse,
)
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.options_pb import Role as ProtoRole
from app.models import AccessRequest as RequestRow
from app.models import Role, SchoolClass
from app.rpc import values
from app.rpc.errors import Refusal
from app.services import access as access_service
from app.services.manage import classes as classes_service
from app.services.manage import requests as requests_service

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.rpc.call import Call, Effect

#: The roles a request may be answered with, by their v2 names. The ladder
#: refuses some of them for some admins, ``ROLE_OWNER`` for everybody; that is
#: ``ROLE_GRANT_REFUSED``, and only a number no value names is refused here.
_ROLES = {
    ProtoRole.VIEWER: Role.VIEWER,
    ProtoRole.EDITOR: Role.EDITOR,
    ProtoRole.ADMIN: Role.ADMIN,
    ProtoRole.OWNER: Role.OWNER,
}

#: Fixed, and naming no value: a refusal never repeats what was sent.
ROLE_REFUSED = "role must be a role the contract names, or unset for the role asked for"


def _message(row: RequestRow, names: dict[int, str]) -> AccessRequest:
    return AccessRequest(
        id=row.id,
        # The @username or the name Telegram gave, for somebody who holds a
        # role in the class, else the numeric id, which is what a person
        # still waiting for one usually is (``classes.display_name``).
        who=names.get(row.telegram_id, str(row.telegram_id)),
        requested_role=values.role(row.requested_role),
        message=row.message,
        created_at=values.instant(row.created_at),
    )


async def _pending(session: AsyncSession, school_class: SchoolClass, request_id: int) -> RequestRow:
    """This class's open request ``request_id``, or ``RESOURCE_NOT_FOUND``: one
    already answered is gone, as in v1, and another class's finds nothing."""
    pending = await requests_service.pending_one(session, school_class.id, request_id)
    if pending is None:
        raise Refusal(
            ErrorReason.RESOURCE_NOT_FOUND,
            wording.UNKNOWN_ACCESS_REQUEST_DETAIL,
            resource="access_request",
        )
    return pending


def _granting(sent: ProtoRole) -> Role | None:
    """The role an approval names, or ``None`` for the one asked for."""
    if sent == ProtoRole.UNSPECIFIED:
        return None
    role = _ROLES.get(sent)
    if role is None:
        raise Refusal(
            ErrorReason.VALIDATION_FAILED, ROLE_REFUSED, violations=[("role", ROLE_REFUSED)]
        )
    return role


def _actor_role(call: Call) -> Role:
    """The caller's role, which the gate read on this call and let through at
    ``ROLE_ADMIN`` or above."""
    if call.role is None:
        raise RuntimeError(f"{call.method.key} reached its handler without a role")
    return call.role


def _tell(telegram_id: int, text: str) -> Effect:
    """The notice to whoever asked, as an effect: sent once the decision is
    committed, by a bot built for it, which logs and drops whatever Telegram
    refuses (``telegram_send.send``). The two values are captured here, so
    the effect reads nothing of the session."""

    async def tell() -> None:
        await telegram_send.send([(telegram_id, text)])

    return tell


async def list_access_requests(
    call: Call, request: ListAccessRequestsRequest
) -> ListAccessRequestsResponse:
    """Everybody waiting for a role in the class, oldest first. Writes nothing."""
    _admin, school_class = call.device_and_class()
    rows = await requests_service.pending(call.session, school_class.id)
    names = await classes_service.member_names(call.session, school_class.id)
    return ListAccessRequestsResponse(access_requests=[_message(row, names) for row in rows])


async def approve_access_request(
    call: Call, request: ApproveAccessRequestRequest
) -> ApproveAccessRequestResponse:
    """Grant the role asked for, or the one the request names, by the ladder of
    «👥 Доступ», and tell whoever asked once it is committed.

    The answer's ``role`` is the role the member holds now, as the proto
    says: an existing member is never lowered, so an owner saying yes to an
    admin's old request for editor answers ``ROLE_ADMIN``. A role the ladder
    does not allow is ``ROLE_GRANT_REFUSED`` with ``why``, and tells nobody.
    """
    admin, school_class = call.device_and_class()
    role = _granting(request.role)
    pending = await _pending(call.session, school_class, request.request_id)
    approval = await requests_service.approve(
        call.session,
        school_class,
        pending,
        actor_id=admin.telegram_id,
        actor_role=_actor_role(call),
        role=role,
    )
    # v1's notice and the bot's, word for word: the role the request was
    # answered with.
    notice = access_service.approval_notice(school_class, approval.granted)
    call.after_commit(_tell(pending.telegram_id, notice))
    return ApproveAccessRequestResponse(
        request_id=pending.id, role=values.role(approval.member.role), who=approval.who
    )


async def decline_access_request(
    call: Call, request: DeclineAccessRequestRequest
) -> DeclineAccessRequestResponse:
    """Say no, and tell whoever asked once it is committed; they keep whatever
    role they had."""
    admin, school_class = call.device_and_class()
    pending = await _pending(call.session, school_class, request.request_id)
    names = await classes_service.member_names(call.session, school_class.id)
    await requests_service.decline(call.session, school_class.id, admin.telegram_id, pending)
    call.after_commit(_tell(pending.telegram_id, requests_service.decline_notice(school_class)))
    return DeclineAccessRequestResponse(
        request_id=pending.id, who=names.get(pending.telegram_id, str(pending.telegram_id))
    )
