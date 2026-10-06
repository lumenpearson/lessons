"""``AccessRequestService``: «🙋 Запросы доступа», the people waiting for a role.

v1's ``GET /manage/requests``, over the same services: the class's open
requests, oldest first, each named as an admin reads them. v1 wrote when each
was asked as the class's wall time; v2 writes an instant.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.contract.lessons.v2.access_request_pb import (
    AccessRequest,
    ListAccessRequestsRequest,
    ListAccessRequestsResponse,
)
from app.models import AccessRequest as RequestRow
from app.rpc import values
from app.services.manage import classes as classes_service
from app.services.manage import requests as requests_service

if TYPE_CHECKING:
    from app.rpc.call import Call


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


async def list_access_requests(
    call: Call, request: ListAccessRequestsRequest
) -> ListAccessRequestsResponse:
    """Everybody waiting for a role in the class, oldest first. Writes nothing."""
    _admin, school_class = call.device_and_class()
    rows = await requests_service.pending(call.session, school_class.id)
    names = await classes_service.member_names(call.session, school_class.id)
    return ListAccessRequestsResponse(access_requests=[_message(row, names) for row in rows])
