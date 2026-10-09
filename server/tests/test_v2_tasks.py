"""``MeService``'s tasks, read and created: the linked account's own list.

v1's ``GET /tasks`` and ``POST /tasks`` over v2, through the same services:
the same list in the same order, at most 200; somebody else's task not found,
as one that never existed is not; a new task cleaned and checked by v1's
``TaskIn``, with a ``homework_id`` of this class only and a reminder kept as
the class's wall time. A date is ``"YYYY-MM-DD"``, a time ``"HH:MM"``, a
reminder ``"YYYY-MM-DDTHH:MM"``, and when something happened is an instant
(``docs/api.md``, «What the values look like»). A read writes nothing
(``docs/specs/2026-10-05-server-v2-design.md``, decision 10).
"""

from __future__ import annotations

from datetime import UTC, date, datetime

from sqlalchemy import func, select

from app import wording
from app.contract.lessons.v2.me_pb import (
    CreateTaskRequest,
    GetTaskRequest,
    ListTasksRequest,
    Task,
)
from app.models import Homework, PersonalTask, SchoolClass

#: The accounts behind v2_tokens' viewer and editor phones.
VIEWER = 2001
EDITOR = 2002
CREATE = "MeService/CreateTask"
MONDAY = date(2026, 9, 7)


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _row(session, school_class, telegram_id: int = VIEWER, **fields) -> PersonalTask:
    task = PersonalTask(class_id=school_class.id, telegram_id=telegram_id, **fields)
    session.add(task)
    await session.commit()
    return task


async def _count(session) -> int:
    return await session.scalar(select(func.count()).select_from(PersonalTask)) or 0


async def test_the_tasks_are_v1_s_in_v2_s_shape(v2, v2_tokens) -> None:
    viewer = v2_tokens["viewer"]
    created = await v2.http.post(
        "/api/v1/tasks",
        json={
            "title": "Купить тетрадь",
            "notes": "в клетку",
            "due_date": "2026-09-15",
            "due_time": "18:00",
            "priority": 2,
            "subject_name": "Алгебра",
            "remind_at": "2026-09-15T08:00:00",
        },
        headers=_auth(viewer),
    )
    assert created.status_code == 201
    v1 = (await v2.http.get("/api/v1/tasks", headers=_auth(viewer))).json()
    answer = await v2.both("MeService/ListTasks", token=viewer)
    rows = answer.message.tasks
    assert [row.id for row in rows] == [row["id"] for row in v1]
    task = rows[0]
    assert (task.title, task.notes, task.subject_name) == ("Купить тетрадь", "в клетку", "Алгебра")
    # "HH:MM" and a wall-clock minute, where v1 wrote seconds.
    assert (task.due_date, task.due_time, task.remind_at) == (
        "2026-09-15",
        "18:00",
        "2026-09-15T08:00",
    )
    assert (task.priority, task.done, task.done_at) == (2, False, None)
    assert not task.has_field("homework_id")
    # An instant, where v1 wrote the same stamp with no zone.
    stamp = datetime.fromisoformat(v1[0]["created_at"]).replace(tzinfo=UTC)
    assert task.created_at.to_datetime() == stamp


async def test_done_tasks_are_listed_only_when_asked_for(
    v2, v2_tokens, session, school_class
) -> None:
    done = await _row(
        session, school_class, title="Сделано", done=True, done_at=datetime(2026, 9, 7, 6, 0)
    )
    open_ = await _row(session, school_class, title="Сделать")
    viewer = v2_tokens["viewer"]
    plain = await v2.both("MeService/ListTasks", token=viewer)
    every = await v2.both("MeService/ListTasks", ListTasksRequest(include_done=True), token=viewer)
    assert [row.id for row in plain.message.tasks] == [open_.id]
    # Undone first.
    assert [row.id for row in every.message.tasks] == [open_.id, done.id]
    assert every.message.tasks[1].done_at.to_datetime() == datetime(2026, 9, 7, 6, 0, tzinfo=UTC)


async def test_somebody_else_s_task_is_not_found_as_one_that_never_was(
    v2, v2_tokens, session, school_class
) -> None:
    mine = await _row(session, school_class, title="Моя")
    theirs = await _row(session, school_class, telegram_id=EDITOR, title="Чужая")
    viewer = v2_tokens["viewer"]
    own = await v2.both("MeService/GetTask", GetTaskRequest(task_id=mine.id), token=viewer)
    assert own.message.task.title == "Моя"
    v1 = await v2.http.patch(
        f"/api/v1/tasks/{theirs.id}", json={"title": "x"}, headers=_auth(viewer)
    )
    assert v1.status_code == 404
    for task_id in (theirs.id, 999_999):
        answer = await v2.both("MeService/GetTask", GetTaskRequest(task_id=task_id), token=viewer)
        assert (answer.status, answer.code, answer.reason) == (
            404,
            "NOT_FOUND",
            "RESOURCE_NOT_FOUND",
        )
        assert answer.metadata == {"resource": "task"}
        assert answer.error == v1.json()["detail"] == wording.UNKNOWN_TASK_DETAIL
    listed = await v2.both("MeService/ListTasks", token=viewer)
    assert [row.id for row in listed.message.tasks] == [mine.id]


async def test_reading_tasks_writes_nothing_but_the_last_seen(
    v2, v2_tokens, session, school_class, statement_writes, unexpected_writes, last_seen_rule
) -> None:
    task = await _row(session, school_class, title="Моя")
    viewer = v2_tokens["viewer"]
    with statement_writes() as seen:
        listed = await v2.both("MeService/ListTasks", token=viewer)
        one = await v2.both("MeService/GetTask", GetTaskRequest(task_id=task.id), token=viewer)
    assert (len(listed.message.tasks), one.message.task.id) == (1, task.id)
    # A write happened, the phone's last call, and nothing else did.
    assert seen
    assert unexpected_writes(seen) == []
    assert all(last_seen_rule.match(statement) for statement in seen), seen


async def test_a_new_task_answers_201_and_is_cleaned_as_v1_cleans_it(
    v2, v2_tokens, session
) -> None:
    viewer = v2_tokens["viewer"]
    sent = Task(id=777, title="  Сдать   реферат ", subject_name=" Физика ", done=True)
    rest = await v2.rest(CREATE, CreateTaskRequest(task=sent), token=viewer)
    connect = await v2.connect(CREATE, CreateTaskRequest(task=Task(title="Конспект")), token=viewer)
    assert (rest.status, connect.status) == (201, 200)
    made = rest.message.task
    # One line, single-spaced; priority 1 when none is sent; the id and
    # `done` are the server's.
    assert (made.title, made.subject_name, made.priority, made.done) == (
        "Сдать реферат",
        "Физика",
        1,
        False,
    )
    assert made.id != 777 and made.created_at is not None
    rows = await session.execute(
        select(PersonalTask.id, PersonalTask.telegram_id, PersonalTask.title).order_by(
            PersonalTask.id
        )
    )
    assert list(rows) == [
        (made.id, VIEWER, "Сдать реферат"),
        (connect.message.task.id, VIEWER, "Конспект"),
    ]


async def test_a_task_naming_homework_of_another_class_is_refused_on_its_field(
    v2, v2_tokens, session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    foreign = Homework(class_id=other.id, due_date=MONDAY, subject_name="Алгебра", text="№ 1")
    own = Homework(class_id=school_class.id, due_date=MONDAY, subject_name="Физика", text="§ 3")
    session.add_all([foreign, own])
    await session.commit()
    viewer = v2_tokens["viewer"]
    v1 = await v2.http.post(
        "/api/v1/tasks", json={"title": "x", "homework_id": foreign.id}, headers=_auth(viewer)
    )
    assert v1.status_code == 422
    for homework_id in (foreign.id, 999_999):
        answer = await v2.both(
            CREATE, CreateTaskRequest(task=Task(title="x", homework_id=homework_id)), token=viewer
        )
        assert (answer.status, answer.code, answer.reason) == (
            400,
            "INVALID_ARGUMENT",
            "VALIDATION_FAILED",
        )
        assert answer.violations == [("task.homework_id", wording.HOMEWORK_NOT_IN_CLASS_DETAIL)]
        assert answer.error == v1.json()["detail"] == wording.HOMEWORK_NOT_IN_CLASS_DETAIL
    assert await _count(session) == 0

    linked = await v2.rest(
        CREATE, CreateTaskRequest(task=Task(title="x", homework_id=own.id)), token=viewer
    )
    assert linked.message.task.homework_id == own.id


async def test_a_task_without_a_title_or_past_the_priorities_is_refused_on_its_field(
    v2, v2_tokens, session
) -> None:
    for task, field in (
        (Task(), "task.title"),
        (Task(title="   "), "task.title"),
        (Task(title="x" * 201), "task.title"),
        (Task(title="x", priority=3), "task.priority"),
    ):
        answer = await v2.both(CREATE, CreateTaskRequest(task=task), token=v2_tokens["viewer"])
        assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED"), field
        assert [name for name, _ in answer.violations] == [field]
    assert await _count(session) == 0


async def test_a_reminder_with_an_offset_is_kept_as_the_class_s_wall_time(
    v2, v2_tokens, session
) -> None:
    """The contract writes a reminder as the class's wall time. One sent with an
    offset is taken as v1 takes it: as an instant, stored at the class's own
    clock, which is the one the reminder tick reads."""
    viewer = v2_tokens["viewer"]
    instant = await v2.rest(
        CREATE,
        CreateTaskRequest(task=Task(title="x", remind_at="2026-09-15T05:00:00Z")),
        token=viewer,
    )
    # 05:00 UTC is 08:00 in Moscow, the class's zone.
    assert instant.message.task.remind_at == "2026-09-15T08:00"
    wall = await v2.connect(
        CREATE, CreateTaskRequest(task=Task(title="y", remind_at="2026-09-15T08:00")), token=viewer
    )
    assert wall.message.task.remind_at == "2026-09-15T08:00"
    stored = await session.scalars(select(PersonalTask.remind_at).order_by(PersonalTask.id))
    assert list(stored) == [datetime(2026, 9, 15, 8, 0)] * 2
