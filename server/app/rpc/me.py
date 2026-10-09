"""``MeService``: what a phone does for itself.

Who it is, its link to an account, the class's calendar feed, and the linked
account's own tasks and homework ticks, which nobody else in the class sees.
Nothing here changes the class. 3a serves ``GetMe`` and 3b-4 the rest, each
v1's ``/me``, ``/me/unlink``, ``/calendar``, ``/tasks`` or
``/homework/{id}/done`` through the same services. What v2 does not repeat is
minting on a read: ``GetMe`` and ``GetCalendarFeed`` mint nothing, and
``CreateLinkCode`` and ``CreateCalendarFeed`` do
(``docs/specs/2026-10-05-server-v2-design.md``, decision 10).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app import wording
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.me_pb import (
    CalendarFeed,
    CreateCalendarFeedRequest,
    CreateCalendarFeedResponse,
    CreateHomeworkTickRequest,
    CreateHomeworkTickResponse,
    CreateLinkCodeRequest,
    CreateLinkCodeResponse,
    CreateTaskRequest,
    CreateTaskResponse,
    DeleteHomeworkTickRequest,
    DeleteHomeworkTickResponse,
    DeleteTaskRequest,
    DeleteTaskResponse,
    GetCalendarFeedRequest,
    GetCalendarFeedResponse,
    GetMeRequest,
    GetMeResponse,
    GetTaskRequest,
    GetTaskResponse,
    HomeworkTick,
    LinkCode,
    ListTasksRequest,
    ListTasksResponse,
    Me,
    Task,
    UnlinkMeRequest,
    UnlinkMeResponse,
    UpdateTaskRequest,
    UpdateTaskResponse,
)
from app.models import DeviceToken, Homework, PersonalTask
from app.rpc import values
from app.rpc.errors import Refusal, validate
from app.rpc.masks import update_paths
from app.schemas import TaskIn, TaskPatch
from app.services import calendar as calendar_service
from app.services import homework as homework_service
from app.services import linking
from app.services import tasks as tasks_service
from app.services.linking import Access

if TYPE_CHECKING:
    from app.rpc.call import Call

#: ``FEATURE_UNSUPPORTED``'s ``feature`` for a deployment with no public
#: address, a server capability as ``errors.proto`` allows beside a
#: ``DiaryFeature`` name.
CALENDAR_FEED = "calendar_feed"

#: New in v2, so English like v1's own generic answers.
NO_FEED_ADDRESS = "This server has no public address to give a calendar feed"

#: What ``CreateTask`` reads of a ``Task``, as v1's ``TaskIn`` takes it: the
#: id, ``done`` and the stamps are the server's.
_WRITTEN = (
    "title",
    "notes",
    "subject_name",
    "due_date",
    "due_time",
    "priority",
    "homework_id",
    "remind_at",
)

#: The ``optional`` fields of ``Task``: unset reads as ``""`` or 0, and means none.
_OPTIONAL = frozenset(_WRITTEN) - {"title"}

#: What ``update_mask`` may name, and nothing more: the proto comment's list,
#: which is v1's ``TaskPatch``. ``tasks.update_task`` decides the order.
CHANGEABLE = (*_WRITTEN, "done")


def _me(device: DeviceToken, access: Access) -> Me:
    return Me(
        device_name=device.device_name,
        linked=access.linked,
        role=values.role(access.role),
        can_edit=access.can_edit,
    )


async def get_me(call: Call, request: GetMeRequest) -> GetMeResponse:
    """Who this device is. Mints nothing: v1's ``/me`` issued a link code as a
    side effect, which ``CreateLinkCode`` does in v2 (decision 10). The role is
    the one the gate read for this call."""
    device, _school_class = call.device_and_class()
    return GetMeResponse(me=_me(device, Access.of(device, call.role)))


async def unlink_me(call: Call, request: UnlinkMeRequest) -> UnlinkMeResponse:
    """Back to read-only, keeping the phone in the class: v1's ``/me/unlink``.

    A phone no account is behind changes nothing, its link code included, and
    is answered as it is, so a retry lands on the first answer. No audit line,
    as v1 wrote none: the phone did it to itself.
    """
    device, _school_class = call.device_and_class()
    await linking.unlink_self(call.session, device)
    # Nobody is behind the phone now, so it holds no role, whatever the gate read.
    return UnlinkMeResponse(me=_me(device, Access.of(device, None)))


async def create_link_code(call: Call, request: CreateLinkCodeRequest) -> CreateLinkCodeResponse:
    """The code to send the bot, minted on the first ask and the same until it
    is used, which is the code v1's ``/me`` shows too; none for a phone that is
    linked already. The deep link is there when the deployment names its bot.
    Never cached (``rest.NO_STORE_CREDENTIAL``): it links the phone to whoever
    sends it."""
    device, _school_class = call.device_and_class()
    code = await linking.link_code_for(call.session, device)
    if code is None:
        return CreateLinkCodeResponse()
    return CreateLinkCodeResponse(
        link_code=LinkCode(
            code=code, bot_deep_link=linking.deep_link(call.settings.bot_username, code)
        )
    )


def _feed_origin(call: Call) -> str:
    """The origin the feed's address is written on, ``PUBLIC_BASE_URL``, or
    ``FEATURE_UNSUPPORTED``. v1 falls back to the request's own host, which
    behind Vercel is an internal one (``docs/api.md``); v2 hands out no
    address it cannot stand behind, as «📅 Календарь» hands out none."""
    origin = call.settings.public_base_url.rstrip("/")
    if not origin:
        raise Refusal(ErrorReason.FEATURE_UNSUPPORTED, NO_FEED_ADDRESS, feature=CALENDAR_FEED)
    return origin


async def get_calendar_feed(call: Call, request: GetCalendarFeedRequest) -> GetCalendarFeedResponse:
    """The class's subscription address when the class has a feed secret, and
    none when it has not. Mints nothing, where v1's ``GET /calendar`` did:
    ``CreateCalendarFeed`` mints now. Writes nothing."""
    _device, school_class = call.device_and_class()
    origin = _feed_origin(call)
    secret = school_class.calendar_token
    return GetCalendarFeedResponse(
        calendar_feed=CalendarFeed(
            url=calendar_service.feed_url(origin, secret) if secret else None
        )
    )


async def create_calendar_feed(
    call: Call, request: CreateCalendarFeedRequest
) -> CreateCalendarFeedResponse:
    """The class's subscription address, minting its secret when it has none,
    and the same address on every ask after (``calendar.ensure_calendar_token``,
    which v1 and the bot call too). Asks for a linked account, where v1 let any
    phone of the class mint it; no rotation, since the feed is the whole
    class's. Never cached (``rest.NO_STORE_CREDENTIAL``)."""
    _device, school_class = call.device_and_class()
    origin = _feed_origin(call)
    secret = await calendar_service.ensure_calendar_token(call.session, school_class)
    return CreateCalendarFeedResponse(
        calendar_feed=CalendarFeed(url=calendar_service.feed_url(origin, secret))
    )


def _owner(call: Call) -> int:
    """The account behind the phone, which the gate let through for every
    ``AUTH_KIND_DEVICE_LINKED`` method: whose tasks and ticks these are."""
    device, _school_class = call.device_and_class()
    if device.telegram_id is None:
        raise RuntimeError(f"{call.method.key} reached its handler with no account behind it")
    return device.telegram_id


def _task(row: PersonalTask) -> Task:
    return Task(
        id=row.id,
        title=row.title,
        notes=row.notes,
        subject_name=row.subject_name,
        due_date=values.date_string(row.due_date) if row.due_date is not None else None,
        due_time=values.time_string(row.due_time) if row.due_time is not None else None,
        priority=row.priority,
        done=row.done,
        done_at=values.maybe_instant(row.done_at),
        homework_id=row.homework_id,
        # The class's wall time, as the column holds it; the three stamps
        # around it are instants.
        remind_at=values.wall_moment(row.remind_at) if row.remind_at is not None else None,
        created_at=values.maybe_instant(row.created_at),
        updated_at=values.maybe_instant(row.updated_at),
    )


async def _own(call: Call, task_id: int) -> PersonalTask:
    """The linked account's own task ``task_id`` in this class, or
    ``RESOURCE_NOT_FOUND``, the same for somebody else's task as for one that
    never existed, so an id never reveals that a classmate keeps a list."""
    _device, school_class = call.device_and_class()
    row = await tasks_service.get_task(call.session, task_id, school_class.id, _owner(call))
    if row is None:
        raise Refusal(ErrorReason.RESOURCE_NOT_FOUND, wording.UNKNOWN_TASK_DETAIL, resource="task")
    return row


def _given(task: Task, fields: tuple[str, ...]) -> dict[str, object]:
    """The fields of ``task`` a create reads, as v1's ``TaskIn`` takes them: an
    ``optional`` one only when it is set, so that one left out takes
    ``TaskIn``'s default, a priority of 1 among them."""
    return {
        name: getattr(task, name)
        for name in fields
        if name not in _OPTIONAL or task.has_field(name)
    }


async def list_tasks(call: Call, request: ListTasksRequest) -> ListTasksResponse:
    """The linked account's tasks in this class, at most 200: undone first, then
    by deadline with undated ones last, urgent first. Done ones only when asked
    for. Writes nothing."""
    _device, school_class = call.device_and_class()
    rows = await tasks_service.list_tasks(
        call.session,
        school_class.id,
        _owner(call),
        include_done=request.include_done,
        limit=tasks_service.LIST_MAX,
    )
    return ListTasksResponse(tasks=[_task(row) for row in rows])


async def get_task(call: Call, request: GetTaskRequest) -> GetTaskResponse:
    """One of the linked account's own tasks. Writes nothing."""
    return GetTaskResponse(task=_task(await _own(call, request.task_id)))


async def create_task(call: Call, request: CreateTaskRequest) -> CreateTaskResponse:
    """A new task, cleaned and checked by v1's ``TaskIn``: a title of 1 to 200
    characters on one line, priority 1 when none is sent, a ``homework_id`` of
    this class's homework (``VALIDATION_FAILED`` on ``task.homework_id``
    otherwise), and a reminder kept as the class's wall time. The id, ``done``
    and the stamps a client sends are ignored, as ``TaskIn`` has none of them;
    REST answers 201."""
    _device, school_class = call.device_and_class()
    sent = request.task if request.task is not None else Task()
    form = validate(TaskIn, _given(sent, _WRITTEN), at="task.")
    row = await tasks_service.create_task(
        call.session,
        school_class,
        _owner(call),
        form.title,
        notes=form.notes,
        subject_name=form.subject_name,
        due_date=form.due_date,
        due_time=form.due_time,
        priority=form.priority,
        homework_id=form.homework_id,
        remind_at=form.remind_at,
    )
    return CreateTaskResponse(task=_task(row))


def _sent(task: Task, field: str) -> object:
    """What an update says of ``field``: ``None`` for an ``optional`` one it
    leaves unset, which clears it. A masked ``title`` left unset reads as
    ``""`` and a masked ``priority`` as ``None``, and ``TaskPatch`` refuses
    both: the proto says neither can be cleared."""
    if field in _OPTIONAL and not task.has_field(field):
        return None
    return getattr(task, field)


async def update_task(call: Call, request: UpdateTaskRequest) -> UpdateTaskResponse:
    """Change one of the linked account's own tasks: v1's ``PATCH /tasks/{id}``,
    and with ``done`` its ``POST /tasks/{id}/done``.

    The mask is read once, by ``masks.update_paths``: without one, what the
    request sets changes and nothing else. An unset ``bool`` reads as false,
    so ``done`` is taken back only under a mask that names it. The fields are
    checked by v1's ``TaskPatch`` and applied by ``tasks.update_task``, which
    refuses a ``homework_id`` of another class before it changes anything.
    """
    _device, school_class = call.device_and_class()
    sent = request.task if request.task is not None else Task()
    paths = update_paths(request.update_mask, request.task, CHANGEABLE)
    patch = validate(TaskPatch, {name: _sent(sent, name) for name in paths}, at="task.")
    row = await _own(call, sent.id)
    await tasks_service.update_task(
        call.session, school_class, row, patch.model_dump(exclude_unset=True)
    )
    # ``updated_at`` is the database's, set by this very update: flushed and
    # read back, so that the answer carries it. ``invoke`` commits.
    await call.session.flush()
    await call.session.refresh(row)
    return UpdateTaskResponse(task=_task(row))


async def delete_task(call: Call, request: DeleteTaskRequest) -> DeleteTaskResponse:
    """Delete one of the linked account's own tasks. Asked again, the task is
    ``RESOURCE_NOT_FOUND``, as v1's ``DELETE`` answered 404."""
    row = await _own(call, request.task_id)
    await tasks_service.delete_task(call.session, row)
    return DeleteTaskResponse()


async def _homework(call: Call, homework_id: int) -> Homework:
    """This class's homework ``homework_id``, or ``RESOURCE_NOT_FOUND``: an id of
    another class's homework finds nothing, as in v1."""
    _device, school_class = call.device_and_class()
    item = await homework_service.homework_of(call.session, school_class.id, homework_id)
    if item is None:
        raise Refusal(
            ErrorReason.RESOURCE_NOT_FOUND, wording.UNKNOWN_HOMEWORK_DETAIL, resource="homework"
        )
    return item


async def create_homework_tick(
    call: Call, request: CreateHomeworkTickRequest
) -> CreateHomeworkTickResponse:
    """Tick homework of this class off for the linked account: v1's ``POST
    /homework/{id}/done`` with ``true``. Ticking it twice is not an error: the
    state asked for is the state answered (``tasks.set_homework_done``)."""
    sent = request.homework_tick if request.homework_tick is not None else HomeworkTick()
    item = await _homework(call, sent.homework_id)
    await tasks_service.set_homework_done(call.session, item, _owner(call), True)
    return CreateHomeworkTickResponse(homework_tick=HomeworkTick(homework_id=item.id))


async def delete_homework_tick(
    call: Call, request: DeleteHomeworkTickRequest
) -> DeleteHomeworkTickResponse:
    """Take the linked account's tick off: v1's ``POST /homework/{id}/done`` with
    ``false``. Taking off a tick that is not there is not an error."""
    item = await _homework(call, request.homework_id)
    await tasks_service.set_homework_done(call.session, item, _owner(call), False)
    return DeleteHomeworkTickResponse()
