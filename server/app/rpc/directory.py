"""``DirectoryService``: the school directory, a search over the ЕГРЮЛ company register.

``ListSchoolRegions`` is v1's ``GET /directory/school-regions``, for a phone
that belongs to no class yet: anonymous, and the same door, in the same order
(``directory.school_regions``), so a caller has twenty searches and one
anonymous share of the directory's allowance whichever version it asks
(``docs/specs/2026-10-05-server-v2-design.md``, decision 11).

``ListSchools`` is v1's ``GET /manage/schools``, for an admin naming the
class's school: the same ``schools.search``, unmetered, one upstream search a
call whatever the page, because the directory has no offset. What v2 changes
is the paging. v1 took a page number; v2 takes AIP-158's ``page_size`` and an
opaque ``page_token`` naming the first school of the next page, so a
``page_size`` changed between two calls still starts where the last page
ended (``docs/specs/2026-10-05-server-v2-3b-plan.md``, «Rulings for 3b-3»).
Its refusals are the provider's own, in v1's words.
"""

from __future__ import annotations

import base64
import binascii
from typing import TYPE_CHECKING

from app.contract.lessons.v2.directory_pb import (
    ListSchoolRegionsRequest,
    ListSchoolRegionsResponse,
    ListSchoolsRequest,
    ListSchoolsResponse,
    School,
    SchoolRegion,
)
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.providers import dadata
from app.rpc.errors import Refusal
from app.services import directory as directory_service
from app.services import schools as schools_service

if TYPE_CHECKING:
    from app.rpc.call import Call

#: Schools per page when the request names none: v1's page, and the bot's.
DEFAULT_PAGE_SIZE = schools_service.PAGE_SIZE
#: The most a page holds: everything one search can find. A larger
#: ``page_size`` is read as this, as AIP-158 has it.
MAX_PAGE_SIZE = dadata.MAX_SUGGESTIONS

#: Fixed, and naming no value: a refusal never repeats what was sent.
PAGE_SIZE_REFUSED = "page_size must not be negative"
PAGE_TOKEN_REFUSED = "page_token is not one this list handed out"
REGION_REFUSED = f"region must be at most {schools_service.MAX_REGION} characters"

#: What a token says before its position, so that another list's token can
#: never pass for one of this.
_PREFIX = b"schools:"
#: Longer than any token this list hands out; a longer one is refused unread.
_TOKEN_MAX = 32


def page_token(first: int) -> str:
    """The token of the page whose first school is ``first``, counted from 0:
    URL-safe base64, unpadded."""
    return base64.urlsafe_b64encode(_PREFIX + str(first).encode()).rstrip(b"=").decode()


def _bad_token() -> Refusal:
    return Refusal(
        ErrorReason.VALIDATION_FAILED,
        PAGE_TOKEN_REFUSED,
        violations=[("page_token", PAGE_TOKEN_REFUSED)],
    )


def _first(token: str) -> int:
    """The first school of the page ``token`` asks for, or ``VALIDATION_FAILED``
    on ``page_token`` for a token this list never wrote."""
    if len(token) > _TOKEN_MAX or not token.isascii():
        raise _bad_token()
    try:
        # Strict: the lenient decoder drops characters outside the alphabet.
        raw = base64.b64decode(token + "=" * (-len(token) % 4), altchars=b"-_", validate=True)
    except (binascii.Error, ValueError):
        raise _bad_token() from None
    digits = raw.removeprefix(_PREFIX)
    if digits == raw or not digits.isdigit() or len(digits) > 2:
        raise _bad_token()
    first = int(digits)
    # A next page starts after the first school, and no search finds a
    # twenty-first; «schools:05» is no token this list writes either.
    if not 0 < first < MAX_PAGE_SIZE or str(first).encode() != digits:
        raise _bad_token()
    return first


def _region(group: schools_service.RegionGroup) -> SchoolRegion:
    return SchoolRegion(
        region=group.region,
        code=group.code,
        label=group.label,
        schools=group.schools,
        cities=group.cities,
        examples=group.examples,
    )


def _school(school: dadata.School) -> School:
    return School(
        name=school.name,
        full_name=school.full_name,
        ogrn=school.ogrn,
        inn=school.inn,
        address=school.address,
        city=school.city,
        region=school.region,
        active=school.active,
    )


async def list_school_regions(
    call: Call, request: ListSchoolRegionsRequest
) -> ListSchoolRegionsResponse:
    """The regions a school called ``query`` may be in, best first, at most ten.

    Counted against the caller in v1's bucket, and refused in v1's order and
    words: throttled, a query too short (not counted), no directory, today's
    anonymous share spent, the directory failing. Writes the throttle's row
    and the allowance's count, and nothing else.
    """
    found = await directory_service.school_regions(
        call.session,
        request.query,
        client_key=call.bucket(scope=directory_service.BUCKET_SCOPE),
    )
    return ListSchoolRegionsResponse(
        query=found.query,
        regions=[_region(group) for group in found.groups],
        truncated=found.truncated,
        generic=found.generic,
    )


async def list_schools(call: Call, request: ListSchoolsRequest) -> ListSchoolsResponse:
    """The schools matching ``query``, a page at a time, from one search a call.

    The request's own fields are checked before anything is asked of the
    directory: a negative ``page_size``, a token this list did not hand out
    and a region hint longer than v1 takes are refused on their fields. A
    token past what the search finds now — the directory answered fewer than
    it did a page ago — is an empty last page. Writes nothing.
    """
    if request.page_size < 0:
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            PAGE_SIZE_REFUSED,
            violations=[("page_size", PAGE_SIZE_REFUSED)],
        )
    size = min(request.page_size or DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE)
    first = _first(request.page_token) if request.page_token else 0
    region = request.region if request.has_field("region") else None
    if region is not None and len(region) > schools_service.MAX_REGION:
        raise Refusal(
            ErrorReason.VALIDATION_FAILED, REGION_REFUSED, violations=[("region", REGION_REFUSED)]
        )
    found = await schools_service.search(request.query, region=region or None)
    after = first + size
    return ListSchoolsResponse(
        schools=[_school(school) for school in found.schools[first:after]],
        next_page_token=page_token(after) if after < len(found.schools) else "",
        total_size=len(found.schools),
        truncated=found.truncated,
    )
