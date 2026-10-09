"""``HomeworkService``: the class's homework, set by an editor and read by every phone.

v1's ``GET /homework``, ``PUT /homework`` and ``DELETE /homework/{id}`` over
v2, through the same services (``services/homework.py``). Each row carries
this phone's owner's tick, and none for a phone no account is behind; the
window is today and three weeks on when unset, sixty-two days at most
(``rpc/dates.py``). What v2 does not repeat is v1's upsert by (date,
subject): ``CreateHomework`` refuses a subject that already has homework that
day, and ``UpdateHomework`` changes it. Each write is announced to the class's
subscribers once it is committed, and never when it is refused, in v1's
words and without its author (``docs/specs/2026-10-05-server-v2-3b-plan.md``,
3b-5).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app import telegram_send, wording
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.homework_pb import (
    CreateHomeworkRequest,
    CreateHomeworkResponse,
    GetHomeworkRequest,
    GetHomeworkResponse,
    Homework,
    ListHomeworkRequest,
    ListHomeworkResponse,
)
from app.models import Homework as HomeworkRow
from app.rpc import dates, values
from app.rpc.errors import Refusal, validate
from app.schemas import HomeworkIn
from app.services import clock
from app.services import homework as homework_service
from app.services import tasks as tasks_service

if TYPE_CHECKING:
    from app.rpc.call import Call

#: What a write reads of a ``Homework``, as v1's ``HomeworkIn`` takes it: the
#: id and ``done`` are the server's.
_WRITTEN = ("due_date", "subject", "text", "attachment_url")

#: The ``optional`` field of ``Homework``: unset reads as ``""``, and means none.
_OPTIONAL = frozenset({"attachment_url"})


def _message(row: HomeworkRow, ticked: set[int]) -> Homework:
    return Homework(
        id=row.id,
        due_date=values.date_string(row.due_date),
        subject=row.subject_name,
        text=row.text,
        attachment_url=row.attachment_url,
        done=row.id in ticked,
    )


async def _ticked(call: Call, rows: list[HomeworkRow]) -> set[int]:
    """Which of ``rows`` this phone's owner has ticked off: none for a phone
    no account is behind, as v1's list answered it."""
    device, _school_class = call.device_and_class()
    if device.telegram_id is None:
        return set()
    return await tasks_service.homework_ticks(
        call.session, device.telegram_id, [row.id for row in rows]
    )


async def _row(call: Call, homework_id: int) -> HomeworkRow:
    """This class's homework ``homework_id``, or ``RESOURCE_NOT_FOUND``: an id
    of another class's homework finds nothing, as in v1."""
    _device, school_class = call.device_and_class()
    row = await homework_service.homework_of(call.session, school_class.id, homework_id)
    if row is None:
        raise Refusal(
            ErrorReason.RESOURCE_NOT_FOUND, wording.UNKNOWN_HOMEWORK_DETAIL, resource="homework"
        )
    return row


def _sent(homework: Homework, field: str) -> object:
    """What a request says of ``field``: ``None`` for the ``optional`` one it
    leaves unset, since protobuf-py reads an unset string as ``""``."""
    if field in _OPTIONAL and not homework.has_field(field):
        return None
    return getattr(homework, field)


def _announce(call: Call, notice: str) -> None:
    """Tell the class's subscribers to homework, all but the editor who wrote
    it: v1's notice, as an effect (``telegram_send.notify_class``), so that it
    goes out once the change is committed and never when it is refused. The
    values are captured here; the effect reads its recipients from the call's
    session, which ``invoke`` keeps open for it."""
    editor, school_class = call.device_and_class()
    session, author = call.session, editor.telegram_id
    call.after_commit(
        lambda: telegram_send.notify_class(
            session, school_class, notice, kind="homework", author=author
        )
    )


async def list_homework(call: Call, request: ListHomeworkRequest) -> ListHomeworkResponse:
    """Homework due in a window, by date and then subject: v1's ``GET
    /homework``. Writes nothing."""
    _device, school_class = call.device_and_class()
    start, end = dates.window(request, clock.today(school_class))
    rows = await homework_service.due_between(call.session, school_class.id, start, end)
    ticked = await _ticked(call, rows)
    return ListHomeworkResponse(homework=[_message(row, ticked) for row in rows])


async def get_homework(call: Call, request: GetHomeworkRequest) -> GetHomeworkResponse:
    """One assignment, with this phone's owner's tick. Writes nothing."""
    row = await _row(call, request.homework_id)
    return GetHomeworkResponse(homework=_message(row, await _ticked(call, [row])))


async def create_homework(call: Call, request: CreateHomeworkRequest) -> CreateHomeworkResponse:
    """A new assignment, cleaned and checked by v1's ``HomeworkIn``: a date
    inside the bounds v1 holds a write to, a subject of 1 to 120 characters
    stored in the class's spelling, a text of 1 to 4000 with its line breaks,
    and an address of up to 500. A subject that already has homework that day
    is ``RESOURCE_EXISTS``, where v1's ``PUT`` replaced its text;
    ``UpdateHomework`` changes it. The id and ``done`` a client sends are
    ignored. Announced once committed; REST answers 201."""
    editor, school_class = call.device_and_class()
    sent = request.homework if request.homework is not None else Homework()
    form = validate(HomeworkIn, {name: _sent(sent, name) for name in _WRITTEN}, at="homework.")
    dates.bounded(form.due_date, "homework.due_date")
    saved = await homework_service.create(
        call.session,
        school_class,
        editor.telegram_id,
        form.due_date,
        form.subject,
        form.text,
        attachment_url=form.attachment_url,
    )
    _announce(call, saved.notice)
    # Nobody has ticked an assignment that did not exist a moment ago.
    return CreateHomeworkResponse(homework=_message(saved.homework, set()))
