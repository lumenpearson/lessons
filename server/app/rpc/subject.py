"""``SubjectService``: the class's subject dictionary, as «📚 Предметы» edits it.

One resource with ids, readable by any phone in the class, where v1 had two:
``GET /subjects`` (no ids) and ``/manage/subjects`` (ids, an editor's). v2's
reads adopt nothing (``docs/specs/2026-10-05-server-v2-design.md``, decision
10): every write that names a subject in the weekly template links it
already, and v1's repair of rows written before the link existed stays in
v1's manage list and in the bot.

The writes are v1's, through the same services and v1's own schemas, so the
two versions refuse the same requests: a name the class has in any case is
``RESOURCE_EXISTS``, a rename onto a name with homework the same day
``SUBJECT_RENAME_CLASH``, and a subject the timetable teaches
``RESOURCE_IN_USE``, each worded by ``rpc/errors.py`` in v1's words.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app import wording
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.subject_pb import (
    CreateSubjectRequest,
    CreateSubjectResponse,
    DeleteSubjectRequest,
    DeleteSubjectResponse,
    GetSubjectRequest,
    GetSubjectResponse,
    ListSubjectsRequest,
    ListSubjectsResponse,
    Subject,
    UpdateSubjectRequest,
    UpdateSubjectResponse,
)
from app.models import Subject as SubjectRow
from app.rpc.errors import Refusal, validate
from app.rpc.masks import update_paths
from app.schemas import SubjectIn, SubjectPatch
from app.services.manage import subjects as subjects_service

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.rpc.call import Call

#: What ``update_mask`` takes, in the order a patch is applied: the rename
#: first, because it is the change that can be refused, then each detail
#: (``subjects_service.update``).
CHANGEABLE = ("name", "short_name", "teacher", "color")

#: The ``optional`` fields of ``Subject``: unset reads as ``""``, and means none.
_OPTIONAL = frozenset({"short_name", "teacher", "color"})


def _message(row: SubjectRow) -> Subject:
    return Subject(
        id=row.id,
        name=row.name,
        short_name=row.short_name,
        teacher=row.teacher,
        color=row.color,
    )


def _sent(subject: Subject, field: str) -> str | None:
    """What the request says of ``field``: ``None`` for an optional one it
    leaves unset, since protobuf-py reads an unset string as ``""``."""
    if field in _OPTIONAL and not subject.has_field(field):
        return None
    return getattr(subject, field)


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


async def create_subject(call: Call, request: CreateSubjectRequest) -> CreateSubjectResponse:
    """Add a subject, cleaned and checked by v1's ``SubjectIn``. The id a client
    sends is ignored; REST answers 201."""
    admin, school_class = call.device_and_class()
    sent = request.subject if request.subject is not None else Subject()
    form = validate(SubjectIn, {field: _sent(sent, field) for field in CHANGEABLE}, at="subject.")
    row = await subjects_service.create(
        call.session,
        school_class.id,
        admin.telegram_id,
        form.name,
        short_name=form.short_name,
        teacher=form.teacher,
        color=form.color,
    )
    # The id is the database's: flushed here, committed by `invoke`.
    await call.session.flush()
    return CreateSubjectResponse(subject=_message(row))


async def update_subject(call: Call, request: UpdateSubjectRequest) -> UpdateSubjectResponse:
    """Rename a subject, or set or clear its short name, teacher or colour.

    The mask is read once, by ``masks.update_paths``; the fields it names are
    cleaned and checked by v1's ``SubjectPatch``, then applied by the patch v1
    applies (``subjects_service.update``): the rename first, carrying the
    timetable, the homework and the substitutions with it, then each detail,
    one log line each. ``moved`` says how many rows the rename carried.
    """
    admin, school_class = call.device_and_class()
    sent = request.subject if request.subject is not None else Subject()
    paths = update_paths(request.update_mask, request.subject, CHANGEABLE)
    patch = validate(SubjectPatch, {field: _sent(sent, field) for field in paths}, at="subject.")
    row = await _row(call.session, school_class.id, sent.id)
    moved = await subjects_service.update(
        call.session,
        school_class.id,
        admin.telegram_id,
        row,
        patch.model_dump(exclude_unset=True),
    )
    return UpdateSubjectResponse(subject=_message(row), moved=moved)


async def delete_subject(call: Call, request: DeleteSubjectRequest) -> DeleteSubjectResponse:
    """Take a subject out of the dictionary, once nothing in the weekly template
    teaches it: out of the timetable first, out of the dictionary second."""
    admin, school_class = call.device_and_class()
    row = await _row(call.session, school_class.id, request.subject_id)
    await subjects_service.delete(call.session, school_class.id, admin.telegram_id, row)
    return DeleteSubjectResponse()
