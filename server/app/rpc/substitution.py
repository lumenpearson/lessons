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

from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.substitution_pb import (
    GetSubstitutionRequest,
    GetSubstitutionResponse,
    ListSubstitutionsRequest,
    ListSubstitutionsResponse,
    Substitution,
    SubstitutionAction,
)
from app.models import LessonOverride, OverrideAction
from app.rpc import dates, values
from app.rpc.errors import Refusal
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
