"""``EventService``: the class's events — a trip, an exam, a canteen break.

v1 had ``PUT /events``, which always inserted, and ``DELETE /events/{id}``;
v2 serves both over the same services (``services/events.py``) and adds a
list and an update. ``CreateEvent`` is a ``POST``, so that a retried create
can be told apart from a second event: events have no natural key, and two
«Обед» on one day are two breaks. Every method is an editor's, the reads
included, as the contract has them. A «мероприятие» or a trip stands in for
the lessons it overlaps unless told otherwise, and each write is announced to
the class's subscribers once it is committed, and never when it is refused,
in v1's words and without its author
(``docs/specs/2026-10-05-server-v2-3b-plan.md``, 3b-5).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app import telegram_send, wording
from app.contract.lessons.v2 import common_pb
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.event_pb import (
    CreateEventRequest,
    CreateEventResponse,
    Event,
    GetEventRequest,
    GetEventResponse,
    ListEventsRequest,
    ListEventsResponse,
)
from app.models import DayEvent, EventKind
from app.rpc import dates, values
from app.rpc.errors import Refusal, validate
from app.schemas import EventIn
from app.services import clock
from app.services import events as events_service

if TYPE_CHECKING:
    from app.rpc.call import Call

#: What a write reads of an ``Event``, as v1's ``EventIn`` takes it: the id is
#: the server's.
_WRITTEN = ("date", "starts_at", "ends_at", "title", "kind", "location", "covers_lesson")

#: The ``optional`` fields of ``Event``: unset means no place, and for
#: ``covers_lesson`` what the kind means.
_OPTIONAL = frozenset({"location", "covers_lesson"})

#: v2's kinds and the model's, matched by member name (``rpc/values.py``).
_KINDS = {common_pb.EventKind[kind.name]: kind for kind in EventKind}

#: A ``kind`` no value of ``EventKind`` names. It arrives in the binary
#: encoding only: JSON drops an unknown enum value on both transports, and the
#: request then reads as naming none (the 3b plan, Ruling 41). Fixed, and
#: naming the field, never the number.
KIND_REFUSED = "kind is not one of the event kinds"


def _message(row: DayEvent) -> Event:
    return Event(
        id=row.id,
        date=values.date_string(row.date),
        starts_at=values.time_string(row.starts_at),
        ends_at=values.time_string(row.ends_at),
        title=row.title,
        kind=common_pb.EventKind[row.kind.name],
        location=row.location,
        covers_lesson=row.covers_lesson,
    )


def _kind(value: common_pb.EventKind) -> str:
    """v1's name of the kind ``value`` names; unset is an event, as v1's
    ``EventIn`` defaulted."""
    if value == common_pb.EventKind.UNSPECIFIED:
        return EventKind.EVENT.value
    kind = _KINDS.get(value)
    if kind is None:
        raise Refusal(
            ErrorReason.VALIDATION_FAILED, KIND_REFUSED, violations=[("event.kind", KIND_REFUSED)]
        )
    return kind.value


def _sent(event: Event, field: str) -> object:
    """What a request says of ``field``, as v1's schemas take it: ``None`` for
    an ``optional`` one it leaves unset, since protobuf-py reads an unset
    string as ``""``, and the kind by v1's name."""
    if field in _OPTIONAL and not event.has_field(field):
        return None
    if field == "kind":
        return _kind(event.kind)
    return getattr(event, field)


async def _row(call: Call, event_id: int) -> DayEvent:
    """This class's event ``event_id``, or ``RESOURCE_NOT_FOUND``: an id of
    another class's event finds nothing, as in v1."""
    _editor, school_class = call.device_and_class()
    row = await events_service.event_of(call.session, school_class.id, event_id)
    if row is None:
        raise Refusal(
            ErrorReason.RESOURCE_NOT_FOUND, wording.UNKNOWN_EVENT_DETAIL, resource="event"
        )
    return row


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


async def list_events(call: Call, request: ListEventsRequest) -> ListEventsResponse:
    """Events on dates in a window, by date and then the time they start:
    today and three weeks on when unset, sixty-two days at most, as
    ``ListHomework``'s. Writes nothing."""
    _editor, school_class = call.device_and_class()
    start, end = dates.window(request, clock.today(school_class))
    rows = await events_service.between(call.session, school_class.id, start, end)
    return ListEventsResponse(events=[_message(row) for row in rows])


async def get_event(call: Call, request: GetEventRequest) -> GetEventResponse:
    """One event. Writes nothing."""
    return GetEventResponse(event=_message(await _row(call, request.event_id)))


async def create_event(call: Call, request: CreateEventRequest) -> CreateEventResponse:
    """A new event, always, cleaned and checked by v1's ``EventIn``: a date
    inside the bounds v1 holds a write to, an end after the start, a title of
    1 to 200 characters on one line, and a place of up to 120. An unset kind
    is an event, and an unset ``covers_lesson`` what the kind means. The id a
    client sends is ignored. Announced once committed; REST answers 201."""
    editor, school_class = call.device_and_class()
    sent = request.event if request.event is not None else Event()
    form = validate(EventIn, {name: _sent(sent, name) for name in _WRITTEN}, at="event.")
    dates.bounded(form.date, "event.date")
    written = await events_service.create(
        call.session,
        school_class,
        editor.telegram_id,
        date=form.date,
        starts_at=form.starts_at,
        ends_at=form.ends_at,
        title=form.title,
        kind=EventKind(form.kind),
        location=form.location,
        covers_lesson=form.covers_lesson,
    )
    _announce(call, written.notice)
    return CreateEventResponse(event=_message(written.event))
