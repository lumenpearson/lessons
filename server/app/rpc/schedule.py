"""``ScheduleService``: one school year of the class's days. v1's ``GET /bundle``."""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.contract.lessons.v2.schedule_pb import (
    ClassSummary,
    GetScheduleWindowRequest,
    GetScheduleWindowResponse,
    ScheduleWindow,
)
from app.rpc import values
from app.services import window as window_service
from app.services.linking import Access

if TYPE_CHECKING:
    from app.rpc.call import Call


async def get_schedule_window(
    call: Call, request: GetScheduleWindowRequest
) -> GetScheduleWindowResponse:
    """Every day of the school year that opens in ``request.year``, and its tag.

    Writes nothing (decision 10): terms are computed when the year has none,
    subjects are not adopted, and the only write is the gate's
    ``last_seen_at``. The tag is a strong SHA-256 of the window's canonical
    JSON before ``generated_at`` is set, so two answers of an unchanged window
    carry one tag; a ``W/``, a list or ``*`` in ``if_none_match`` match as
    v1's ``If-None-Match`` did. A year outside ``window.FIRST_YEAR`` ..
    ``LAST_YEAR`` is ``VALIDATION_FAILED`` on ``year``.
    """
    device, school_class = call.device_and_class()
    built = await window_service.for_year(
        call.session, school_class, request.year, access=Access.of(device, call.role)
    )
    window = ScheduleWindow(
        school_class=ClassSummary(
            id=school_class.id,
            name=school_class.name,
            grade=school_class.grade,
            letter=school_class.letter,
            school=school_class.school,
            city=school_class.city,
            timezone=school_class.timezone_name,
            term_kind=values.term_kind(built.scheme),
            terms=[values.term(span) for span in built.terms],
        ),
        days=[values.day(day) for day in built.days],
        device=values.device_access(built.access),
    )
    etag = window_service.strong_etag(window.to_json())
    if request.has_field("if_none_match") and window_service.etag_matches(
        request.if_none_match, etag
    ):
        return GetScheduleWindowResponse(not_modified=True, etag=etag)
    window.generated_at = values.instant(values.now())
    return GetScheduleWindowResponse(etag=etag, window=window)
