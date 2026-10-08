"""``DirectoryService``: the school directory, a search over the ЕГРЮЛ company register.

``ListSchoolRegions`` is v1's ``GET /directory/school-regions``, for a phone
that belongs to no class yet: anonymous, and the same door, in the same order
(``directory.school_regions``), so a caller has twenty searches and one
anonymous share of the directory's allowance whichever version it asks
(``docs/specs/2026-10-05-server-v2-design.md``, decision 11).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.contract.lessons.v2.directory_pb import (
    ListSchoolRegionsRequest,
    ListSchoolRegionsResponse,
    SchoolRegion,
)
from app.services import directory as directory_service
from app.services import schools as schools_service

if TYPE_CHECKING:
    from app.rpc.call import Call


def _region(group: schools_service.RegionGroup) -> SchoolRegion:
    return SchoolRegion(
        region=group.region,
        code=group.code,
        label=group.label,
        schools=group.schools,
        cities=group.cities,
        examples=group.examples,
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
