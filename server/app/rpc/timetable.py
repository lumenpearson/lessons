"""``TimetableService``: the weekly template as text, «📤 Экспорт» and «📥 Импорт».

v1's ``/manage/timetable`` and ``/manage/timetable/import``, over the same
services: ``timetable_service.export`` and ``import_paste``. What v2 adds is
``validate_only`` (AIP-163): the preview the bot shows before «Применить»,
answered as a success with nothing written, where v1's only preview was its
answer to a paste over weekdays that have lessons
(``docs/specs/2026-10-05-server-v2-3b-plan.md``, Ruling 24).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.contract.lessons.v2.timetable_pb import (
    GetTimetableRequest,
    GetTimetableResponse,
    ImportConflict,
    ImportTimetableRequest,
    ImportTimetableResponse,
    Timetable,
)
from app.rpc.errors import validate
from app.schemas import TimetableImportIn
from app.services.manage import timetable as timetable_service

if TYPE_CHECKING:
    from app.rpc.call import Call


async def get_timetable(call: Call, request: GetTimetableRequest) -> GetTimetableResponse:
    """The template in the paste format, byte for byte what «📤 Экспорт» sends.
    A class with no lessons answers its bells alone, or an empty text, and
    never ``NOT_FOUND``. Writes nothing."""
    _admin, school_class = call.device_and_class()
    text, lessons = await timetable_service.export(call.session, school_class)
    return GetTimetableResponse(timetable=Timetable(text=text, lessons=lessons))


async def import_timetable(
    call: Call, request: ImportTimetableRequest
) -> ImportTimetableResponse:
    """Parse a paste and replace exactly the weekdays it names, or preview it.

    Checked by v1's ``TimetableImportIn`` (1 to 20000 characters). With
    ``validate_only`` nothing is written, ``replace`` or not. Without it, a
    paste over a weekday that has lessons writes nothing either and lists the
    conflicts, unless ``replace`` is set. A paste with no weekday header and
    no bells block is ``VALIDATION_FAILED`` on ``text``.
    """
    admin, school_class = call.device_and_class()
    form = validate(TimetableImportIn, {"text": request.text, "replace": request.replace})
    outcome = await timetable_service.import_paste(
        call.session,
        school_class,
        admin.telegram_id,
        form.text,
        replace=form.replace,
        validate_only=request.validate_only,
    )
    return ImportTimetableResponse(
        applied=outcome.applied,
        days=outcome.days,
        lessons=outcome.lessons,
        bells=outcome.bells,
        conflicts=[
            ImportConflict(
                weekday=conflict.weekday, existing=conflict.existing, incoming=conflict.incoming
            )
            for conflict in outcome.conflicts
        ],
        rejected=outcome.rejected,
    )
