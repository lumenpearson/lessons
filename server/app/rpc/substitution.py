"""``SubstitutionService``: one lesson on one date replaced or cancelled.

v1's ``PUT /overrides`` over v2, through the same service
(``services/substitutions.py``), and the reads v1 did not have. v1 upserted
by date and lesson number, with ``"clear"`` as an action; v2 creates a
substitution, refusing a second one on the same lesson with
``RESOURCE_EXISTS``, changes it under a mask, and deletes it, which puts the
lesson back on the timetable. A substitution nobody would ever see is refused
with a reason a client can act on: ``NO_LESSON_ON_DAY``,
``NO_BELL_FOR_LESSON`` (on a create only) or ``LESSON_NOT_ON_TIMETABLE``.
Every method is an editor's, the reads included, as the contract has them.
Each write is announced to the class's subscribers to changes once it is
committed, and never when it is refused, in v1's words and without its
author (``docs/specs/2026-10-05-server-v2-3b-plan.md``, 3b-6).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app import telegram_send
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.substitution_pb import (
    CreateSubstitutionRequest,
    CreateSubstitutionResponse,
    DeleteSubstitutionRequest,
    DeleteSubstitutionResponse,
    GetSubstitutionRequest,
    GetSubstitutionResponse,
    ListSubstitutionsRequest,
    ListSubstitutionsResponse,
    Substitution,
    SubstitutionAction,
    UpdateSubstitutionRequest,
    UpdateSubstitutionResponse,
)
from app.models import LessonOverride, OverrideAction
from app.rpc import dates, values
from app.rpc.errors import Refusal, validate
from app.rpc.masks import update_paths
from app.schemas import OverrideIn
from app.services import clock, substitutions

if TYPE_CHECKING:
    from app.rpc.call import Call

#: v2's actions and the model's. The model's ADD is not in the contract,
#: because nothing writes it: a row of it would read as no action.
_ACTIONS = {
    SubstitutionAction.REPLACE: OverrideAction.REPLACE,
    SubstitutionAction.CANCEL: OverrideAction.CANCEL,
}
_PROTO_ACTIONS = {model: proto for proto, model in _ACTIONS.items()}

#: No substitution of this class has that id. v2's sentence: v1 named a
#: substitution by its date and number, and never answered 404.
UNKNOWN_SUBSTITUTION = "Unknown substitution"

#: What a write reads of a ``Substitution``, as v1's ``OverrideIn`` takes it:
#: the id is the server's.
_WRITTEN = ("date", "index", "action", "subject", "room", "teacher", "note")

#: The ``optional`` fields of ``Substitution``: unset reads as ``""``, and
#: means none.
_OPTIONAL = frozenset({"subject", "room", "teacher", "note"})

#: What ``update_mask`` may name, and nothing more: the proto comment's list.
#: The date and the number are the row.
CHANGEABLE = ("action", "subject", "room", "teacher", "note")

#: An ``action`` left unset, or a number no value of ``SubstitutionAction``
#: names, which arrives in the binary encoding only. Fixed, naming the field
#: and never the number. v1 took ``"clear"`` here too, which is
#: ``DeleteSubstitution`` now.
ACTION_REFUSED = "action must be replace or cancel"


def _message(row: LessonOverride) -> Substitution:
    return Substitution(
        id=row.id,
        date=values.date_string(row.date),
        index=row.index,
        action=_PROTO_ACTIONS.get(row.action, SubstitutionAction.UNSPECIFIED),
        subject=row.subject_name,
        room=row.room,
        teacher=row.teacher,
        note=row.note,
    )


async def _row(call: Call, substitution_id: int) -> LessonOverride:
    """This class's substitution ``substitution_id``, or ``RESOURCE_NOT_FOUND``:
    an id of another class's substitution finds nothing."""
    _editor, school_class = call.device_and_class()
    row = await substitutions.substitution_of(call.session, school_class.id, substitution_id)
    if row is None:
        raise Refusal(ErrorReason.RESOURCE_NOT_FOUND, UNKNOWN_SUBSTITUTION, resource="substitution")
    return row


async def list_substitutions(
    call: Call, request: ListSubstitutionsRequest
) -> ListSubstitutionsResponse:
    """Substitutions on dates in a window, by date and then lesson number:
    today and three weeks on when unset, sixty-two days at most, as
    ``ListHomework``'s. Writes nothing."""
    _editor, school_class = call.device_and_class()
    start, end = dates.window(request, clock.today(school_class))
    rows = await substitutions.between(call.session, school_class.id, start, end)
    return ListSubstitutionsResponse(substitutions=[_message(row) for row in rows])


async def get_substitution(call: Call, request: GetSubstitutionRequest) -> GetSubstitutionResponse:
    """One substitution. Writes nothing."""
    return GetSubstitutionResponse(substitution=_message(await _row(call, request.substitution_id)))


def _action(value: SubstitutionAction) -> str:
    """v1's name of the action ``value`` names, or ``VALIDATION_FAILED`` on
    ``substitution.action``: a substitution replaces a lesson or cancels it,
    and v1 had no default either."""
    action = _ACTIONS.get(value)
    if action is None:
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            ACTION_REFUSED,
            violations=[("substitution.action", ACTION_REFUSED)],
        )
    return action.value


def _sent(substitution: Substitution, field: str) -> object:
    """What a request says of ``field``, as v1's ``OverrideIn`` takes it:
    ``None`` for an ``optional`` one it leaves unset, since protobuf-py reads
    an unset string as ``""``, and the action by v1's name."""
    if field in _OPTIONAL and not substitution.has_field(field):
        return None
    if field == "action":
        return _action(substitution.action)
    return getattr(substitution, field)


def _announce(call: Call, notice: str) -> None:
    """Tell the class's subscribers to changes, all but the editor who made
    it: v1's notice, as an effect (``telegram_send.notify_class``), so that it
    goes out once the change is committed and never when it is refused."""
    editor, school_class = call.device_and_class()
    session, author = call.session, editor.telegram_id
    call.after_commit(
        lambda: telegram_send.notify_class(
            session, school_class, notice, kind="changes", author=author
        )
    )


async def create_substitution(
    call: Call, request: CreateSubstitutionRequest
) -> CreateSubstitutionResponse:
    """A new substitution, checked by v1's ``OverrideIn``: a date inside the
    bounds v1 holds a write to, a lesson number from 1 to 20, a replacement
    with a subject, a room or a teacher, each one line and within v1's
    lengths, and the subject stored in the class's spelling. A lesson that
    already has one that day is ``RESOURCE_EXISTS``, where v1's ``PUT``
    changed it; a day that draws no lessons, a number it rings no bell for,
    and a cancellation or a bare room or teacher at a number the template
    leaves empty are refused with their reasons. The id a client sends is
    ignored. Announced once committed; REST answers 201."""
    editor, school_class = call.device_and_class()
    sent = request.substitution if request.substitution is not None else Substitution()
    form = validate(OverrideIn, {name: _sent(sent, name) for name in _WRITTEN}, at="substitution.")
    dates.bounded(form.date, "substitution.date")
    written = await substitutions.create(
        call.session,
        school_class,
        editor.telegram_id,
        form.date,
        form.index,
        {
            "action": OverrideAction(form.action),
            "subject": form.subject,
            "room": form.room,
            "teacher": form.teacher,
            "note": form.note,
        },
    )
    _announce(call, written.notice)
    return CreateSubstitutionResponse(substitution=_message(written.substitution))


async def update_substitution(
    call: Call, request: UpdateSubstitutionRequest
) -> UpdateSubstitutionResponse:
    """Change a substitution's action, subject, room, teacher or note, checked
    by v1's ``OverrideIn`` over the row as it would stand: v1 changed one by
    sending it again.

    The mask is read once, by ``masks.update_paths``: without one, what the
    request sets changes and nothing else. A masked subject, room, teacher or
    note left unset is cleared, and a masked action left unset is refused,
    since a substitution always replaces or cancels; a cancellation keeps no
    subject, room or teacher. A replacement left with none of the three is
    refused on ``substitution``. A row at a number the day no longer rings
    stays editable, which is how a class gets out of one, but a day that
    draws no lessons and a lesson the template does not have are refused as
    on a create. Announced once committed, in v1's words; an update that
    changes nothing writes nothing and tells nobody.
    """
    editor, school_class = call.device_and_class()
    sent = request.substitution if request.substitution is not None else Substitution()
    paths = update_paths(request.update_mask, request.substitution, CHANGEABLE)
    row = await _row(call, sent.id)
    if not paths:
        return UpdateSubstitutionResponse(substitution=_message(row))
    stored = {
        "date": row.date,
        "index": row.index,
        "action": row.action.value,
        "subject": row.subject_name,
        "room": row.room,
        "teacher": row.teacher,
        "note": row.note,
    }
    sent_fields = {name: _sent(sent, name) for name in paths}
    form = validate(OverrideIn, {**stored, **sent_fields}, at="substitution.")
    changes = {name: getattr(form, name) for name in paths}
    if "action" in changes:
        changes["action"] = OverrideAction(changes["action"])
    written = await substitutions.update(
        call.session, school_class, editor.telegram_id, row, changes
    )
    if written.notice is not None:
        _announce(call, written.notice)
    return UpdateSubstitutionResponse(substitution=_message(row))


async def delete_substitution(
    call: Call, request: DeleteSubstitutionRequest
) -> DeleteSubstitutionResponse:
    """Delete a substitution, which puts the lesson back on the timetable:
    v1's ``PUT /overrides`` with ``clear``. Asked again, it is
    ``RESOURCE_NOT_FOUND``, where v1's ``clear`` answered as the first time
    did; either way the class is told once. Announced once committed, in
    v1's words."""
    editor, school_class = call.device_and_class()
    row = await _row(call, request.substitution_id)
    notice = await substitutions.delete(call.session, school_class, editor.telegram_id, row)
    _announce(call, notice)
    return DeleteSubstitutionResponse()
