"""``SubjectService``: the class's subject dictionary, as «📚 Предметы» edits it.

One resource with ids, readable by any phone in the class, where v1 had two:
``GET /subjects`` (no ids) and ``/manage/subjects`` (ids, an editor's). v2's
reads adopt nothing (``docs/specs/2026-10-05-server-v2-design.md``, decision
10): every write that names a subject in the weekly template links it
already, and v1's repair of rows written before the link existed stays in
v1's manage list and in the bot.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app import wording
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.subject_pb import (
    GetSubjectRequest,
    GetSubjectResponse,
    ListSubjectsRequest,
    ListSubjectsResponse,
    Subject,
)
from app.models import Subject as SubjectRow
from app.rpc.errors import Refusal
from app.services.manage import subjects as subjects_service

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.rpc.call import Call


def _message(row: SubjectRow) -> Subject:
    return Subject(
        id=row.id,
        name=row.name,
        short_name=row.short_name,
        teacher=row.teacher,
        color=row.color,
    )


async def _row(session: AsyncSession, class_id: int, subject_id: int) -> SubjectRow:
    """This class's subject ``subject_id``, or ``RESOURCE_NOT_FOUND``: an id of
    another class's subject finds nothing, as in v1."""
    row = await subjects_service.subject_of(session, class_id, subject_id)
    if row is None:
        raise Refusal(
            ErrorReason.RESOURCE_NOT_FOUND, wording.UNKNOWN_SUBJECT_DETAIL, resource="subject"
        )
    return row


async def list_subjects(call: Call, request: ListSubjectsRequest) -> ListSubjectsResponse:
    """The dictionary, alphabetically, as it stands. Writes nothing."""
    _device, school_class = call.device_and_class()
    rows = await subjects_service.dictionary_of(call.session, school_class.id)
    return ListSubjectsResponse(subjects=[_message(row) for row in rows])


async def get_subject(call: Call, request: GetSubjectRequest) -> GetSubjectResponse:
    """One entry of the dictionary, by its id."""
    _device, school_class = call.device_and_class()
    row = await _row(call.session, school_class.id, request.subject_id)
    return GetSubjectResponse(subject=_message(row))
