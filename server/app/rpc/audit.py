"""``AuditService``: «📜 Журнал», a page at a time. v1's ``GET /manage/log``.

v1 paged by ``limit`` and ``offset``; v2 by AIP-158's ``page_size`` and
``page_token``. The token is opaque to a client, and what it is here is plain:
the id of the last line a page served, so the next page is the lines after
that one (``journal.page_after``). A line written between two page turns
therefore moves nothing on the next page, which an offset would. A token this
list did not hand out — one that does not decode, has another prefix, or
names no line of this class's log — is ``VALIDATION_FAILED`` on
``page_token``, and the refusal never repeats it
(``docs/specs/2026-10-05-server-v2-3b-plan.md``, Ruling 4).
"""

from __future__ import annotations

import base64
import binascii
from typing import TYPE_CHECKING

from app.contract.lessons.v2.audit_pb import (
    AuditEntry,
    ListAuditEntriesRequest,
    ListAuditEntriesResponse,
)
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.rpc import values
from app.rpc.errors import Refusal
from app.services.manage import classes as classes_service
from app.services.manage import journal

if TYPE_CHECKING:
    from app.rpc.call import Call

#: Lines per page when the request names none: «📜 Журнал»'s own page, and v1's.
DEFAULT_PAGE_SIZE = 30
#: The most one page holds; a larger ``page_size`` is read as this (AIP-158).
MAX_PAGE_SIZE = 100

#: Fixed, and naming no value: a refusal never repeats what was sent.
PAGE_SIZE_REFUSED = "page_size must not be negative"
PAGE_TOKEN_REFUSED = "page_token is not one this list handed out"

#: What a token says before its line id, so that a token of another list can
#: never pass for one of this.
_PREFIX = b"audit:"
#: Longer than any token this list hands out; a longer one is refused unread.
_TOKEN_MAX = 64
#: The largest id an ``int32`` holds, which bounds every id this server assigns.
_ID_MAX = 2**31 - 1


def page_token(entry_id: int) -> str:
    """The token of the page after the line ``entry_id``: URL-safe base64, unpadded."""
    return base64.urlsafe_b64encode(_PREFIX + str(entry_id).encode()).rstrip(b"=").decode()


def _bad_token() -> Refusal:
    return Refusal(
        ErrorReason.VALIDATION_FAILED,
        PAGE_TOKEN_REFUSED,
        violations=[("page_token", PAGE_TOKEN_REFUSED)],
    )


def _entry_id(token: str) -> int:
    """The line ``token`` points after, or ``VALIDATION_FAILED`` on ``page_token``."""
    if len(token) > _TOKEN_MAX or not token.isascii():
        raise _bad_token()
    try:
        # Strict: the lenient decoder drops characters outside the alphabet,
        # so «audit:7» with a stray «!» would decode as a real token.
        raw = base64.b64decode(token + "=" * (-len(token) % 4), altchars=b"-_", validate=True)
    except (binascii.Error, ValueError):
        raise _bad_token() from None
    digits = raw.removeprefix(_PREFIX)
    if digits == raw or not digits.isdigit() or len(digits) > 10:
        raise _bad_token()
    entry_id = int(digits)
    if not 0 < entry_id <= _ID_MAX:
        raise _bad_token()
    return entry_id


async def list_audit_entries(
    call: Call, request: ListAuditEntriesRequest
) -> ListAuditEntriesResponse:
    """Who changed what, newest first, a page at a time. Writes nothing."""
    _admin, school_class = call.device_and_class()
    if request.page_size < 0:
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            PAGE_SIZE_REFUSED,
            violations=[("page_size", PAGE_SIZE_REFUSED)],
        )
    size = min(request.page_size or DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE)
    after_id: int | None = None
    if request.page_token:
        after_id = _entry_id(request.page_token)
        if not await journal.has_line(call.session, school_class.id, after_id):
            raise _bad_token()
    entries, more = await journal.page_after(
        call.session, school_class.id, limit=size, after_id=after_id
    )
    names = await classes_service.member_names(call.session, school_class.id)
    return ListAuditEntriesResponse(
        audit_entries=[
            AuditEntry(
                id=row.id,
                action=row.action,
                summary=row.summary,
                who=names.get(row.telegram_id) if row.telegram_id is not None else None,
                at=values.instant(row.created_at),
            )
            for row in entries
        ],
        next_page_token=page_token(entries[-1].id) if more else "",
    )
