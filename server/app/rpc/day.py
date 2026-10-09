"""``DayService``: how a whole date departs from the weekly rhythm.

v1's ``PUT /days`` over v2, through the same write
(``special_days.put_day``), and a read v1 did not have. A date nobody marked
is ``DAY_KIND_NORMAL``, never ``NOT_FOUND``: every date has a kind.
``UpdateDay`` changes a mark, and with ``allow_missing`` makes one, which is an
upsert by date; ``DAY_KIND_NORMAL`` takes a mark off, and on a date with none
writes nothing. v2 writes the four kinds v1 wrote: a self-study day or a day
off is the bot's to set, read here, and changed here only into one of the
four. Both methods are an editor's, as the contract has them. Each change is
announced to the class's subscribers to changes once it is committed, never
when it is refused and never when it changed nothing, in v1's words and
without its author (``docs/specs/2026-10-05-server-v2-3b-plan.md``, 3b-6).
"""

from __future__ import annotations

from datetime import date as Date
from typing import TYPE_CHECKING

from app import telegram_send
from app.contract.lessons.v2 import common_pb
from app.contract.lessons.v2.day_pb import (
    Day,
    GetDayRequest,
    GetDayResponse,
    UpdateDayRequest,
    UpdateDayResponse,
)
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.models import DayKind, DayOverride
from app.rpc import dates, values
from app.rpc.errors import Refusal, validate
from app.rpc.masks import update_paths
from app.schemas import DayIn
from app.services.manage import special_days

if TYPE_CHECKING:
    from app.rpc.call import Call

#: What ``update_mask`` may name, and nothing more: the proto comment's list.
CHANGEABLE = ("kind", "note", "bell_schedule_id")

#: The ``optional`` fields of ``Day``: unset reads as ``""`` or ``0``, and
#: means none.
_OPTIONAL = frozenset({"note", "bell_schedule_id"})

#: v2's kinds and the model's, matched by member name (``rpc/values.py``).
_KINDS = {common_pb.DayKind[kind.name]: kind for kind in DayKind}

#: ``UpdateDay`` without ``allow_missing`` on a date nobody marked: there is
#: no mark to change (AIP-134). v2's sentence: v1's ``PUT`` always upserted.
DAY_NOT_MARKED = "this date has no mark; set allow_missing to mark it"


def _message(day: Date, mark: DayOverride | None) -> Day:
    if mark is None:
        return Day(date=values.date_string(day), kind=common_pb.DayKind.NORMAL)
    return Day(
        date=values.date_string(day),
        kind=common_pb.DayKind[mark.kind.name],
        note=mark.note,
        bell_schedule_id=mark.bell_schedule_id,
    )


def _sent(day: Day, field: str) -> object:
    """What a request says of ``field``, as v1's ``DayIn`` takes it: ``None``
    for an ``optional`` one it leaves unset, and the kind by v1's name —
    ``None`` for one unset or one no ``DayKind`` names, which ``DayIn``
    refuses on the field."""
    if field in _OPTIONAL and not day.has_field(field):
        return None
    if field == "kind":
        kind = _KINDS.get(day.kind)
        return kind.value if kind is not None else None
    return getattr(day, field)


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


async def get_day(call: Call, request: GetDayRequest) -> GetDayResponse:
    """The kind of one date, its note and the bell schedule it rings: a date
    nobody marked is an ordinary day. Writes nothing."""
    _editor, school_class = call.device_and_class()
    day = dates.named(request.date)
    mark = await special_days.mark_on(call.session, school_class.id, day)
    return GetDayResponse(day=_message(day, mark))


async def update_day(call: Call, request: UpdateDayRequest) -> UpdateDayResponse:
    """Mark a date, change its mark, or take it off, checked as v1's ``DayIn``
    checks a mark: one of the four kinds v1 wrote, a note of up to 500.

    Without ``allow_missing``, a date nobody marked is ``RESOURCE_NOT_FOUND``;
    with it, a mark is made, and ``DAY_KIND_NORMAL`` makes none and writes
    nothing. The mask is read once, by ``masks.update_paths``: without one,
    what the request sets changes and nothing else. A masked ``note`` or
    ``bell_schedule_id`` left unset is cleared, and a masked ``kind`` left
    unset is refused, since a day always has one. The fields the mask leaves
    alone are checked as they stand, so a self-study day changes only into a
    kind v2 writes. A schedule must be this class's and ring something, and a
    shortened day must name one. Announced once committed; an update that
    changes nothing writes nothing and tells nobody.
    """
    editor, school_class = call.device_and_class()
    sent = request.day if request.day is not None else Day()
    day = dates.named(sent.date, "day.")
    paths = update_paths(request.update_mask, request.day, CHANGEABLE)
    mark = await special_days.mark_on(call.session, school_class.id, day)
    if mark is None and not request.allow_missing:
        raise Refusal(ErrorReason.RESOURCE_NOT_FOUND, DAY_NOT_MARKED, resource="day")
    if mark is not None and not paths:
        return UpdateDayResponse(day=_message(day, mark))

    stored = {
        "kind": mark.kind.value if mark is not None else None,
        "note": mark.note if mark is not None else None,
        "bell_schedule_id": mark.bell_schedule_id if mark is not None else None,
    }
    sent_fields = {name: _sent(sent, name) for name in paths}
    form = validate(DayIn, {**stored, **sent_fields, "date": day}, at="day.")
    changes = {name: getattr(form, name) for name in paths}
    if "kind" in changes:
        changes["kind"] = DayKind(changes["kind"])
    written = await special_days.update_day(
        call.session, school_class, editor.telegram_id, day, changes
    )
    if written.notice is not None:
        _announce(call, written.notice)
    return UpdateDayResponse(day=_message(day, written.mark))
