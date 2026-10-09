"""``MeService``'s task writes: ``UpdateTask`` and ``DeleteTask``.

v1's ``PATCH /tasks/{id}``, ``POST /tasks/{id}/done`` and ``DELETE
/tasks/{id}`` over v2, through ``tasks.update_task`` and ``delete_task``. The
mask is read once, by ``masks.update_paths`` (AIP-134): no mask changes what
the request sets, as v1's ``PATCH`` did; a masked field left unset is cleared,
except ``title`` and ``priority``, which cannot be; ``done`` is v1's
``POST …/done``, and taking it back needs the mask, because an unset ``bool``
reads as false. A write's success is asked once per transport on fresh data,
and its refusals through ``both`` (the 3b plan, Ruling 17).
"""

from __future__ import annotations

from datetime import date, time

from protobuf.wkt import FieldMask
from sqlalchemy import func, select

from app import wording
from app.contract.lessons.v2.me_pb import DeleteTaskRequest, Task, UpdateTaskRequest
from app.models import Homework, PersonalTask, SchoolClass
from app.rpc.masks import NOT_CHANGEABLE

#: The accounts behind v2_tokens' viewer and editor phones.
VIEWER = 2001
EDITOR = 2002
UPDATE = "MeService/UpdateTask"
DELETE = "MeService/DeleteTask"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _row(session, school_class, telegram_id: int = VIEWER) -> PersonalTask:
    task = PersonalTask(
        class_id=school_class.id,
        telegram_id=telegram_id,
        title="Купить тетрадь",
        notes="в клетку",
        subject_name="Алгебра",
        due_date=date(2026, 9, 15),
        due_time=time(18, 0),
        priority=2,
    )
    session.add(task)
    await session.commit()
    return task


async def _columns(session, task_id: int):
    return (
        await session.execute(
            select(
                PersonalTask.title,
                PersonalTask.notes,
                PersonalTask.subject_name,
                PersonalTask.due_date,
                PersonalTask.due_time,
                PersonalTask.priority,
                PersonalTask.homework_id,
            ).where(PersonalTask.id == task_id)
        )
    ).one()


def _update(task_id: int, *paths: str, **fields) -> UpdateTaskRequest:
    mask = FieldMask(paths=list(paths)) if paths else None
    return UpdateTaskRequest(task=Task(id=task_id, **fields), update_mask=mask)


async def test_an_update_without_a_mask_changes_only_what_it_sends(
    v2, v2_tokens, session, school_class
) -> None:
    first = await _row(session, school_class)
    second = await _row(session, school_class)
    viewer = v2_tokens["viewer"]
    rest = await v2.rest(UPDATE, _update(first.id, title="Купить две тетради"), token=viewer)
    connect = await v2.connect(UPDATE, _update(second.id, subject_name="Физика"), token=viewer)
    assert (rest.status, connect.status) == (200, 200)
    assert tuple(await _columns(session, first.id)) == (
        "Купить две тетради",
        "в клетку",
        "Алгебра",
        date(2026, 9, 15),
        time(18, 0),
        2,
        None,
    )
    assert (connect.message.task.title, connect.message.task.subject_name) == (
        "Купить тетрадь",
        "Физика",
    )
    # The database's stamp of this very update, read back into the answer.
    assert rest.message.task.updated_at is not None


async def test_a_masked_field_left_out_is_cleared_but_a_title_or_a_priority_is_refused(
    v2, v2_tokens, session, school_class
) -> None:
    task = await _row(session, school_class)
    viewer = v2_tokens["viewer"]
    cleared = await v2.rest(
        UPDATE, _update(task.id, "notes", "due_time", "subject_name"), token=viewer
    )
    assert cleared.status == 200
    columns = await _columns(session, task.id)
    assert (columns.notes, columns.due_time, columns.subject_name) == (None, None, None)
    assert columns.due_date == date(2026, 9, 15)
    for path in ("title", "priority"):
        refused = await v2.both(UPDATE, _update(task.id, path), token=viewer)
        assert (refused.status, refused.reason) == (400, "VALIDATION_FAILED"), path
        assert [name for name, _ in refused.violations] == [f"task.{path}"]
    columns = await _columns(session, task.id)
    assert (columns.title, columns.priority) == ("Купить тетрадь", 2)


async def test_done_is_v1_s_post_done_and_taking_it_back_needs_the_mask(
    v2, v2_tokens, session, school_class
) -> None:
    task = await _row(session, school_class)
    viewer = v2_tokens["viewer"]
    done = await v2.rest(UPDATE, _update(task.id, done=True), token=viewer)
    assert done.message.task.done is True
    assert done.message.task.done_at is not None
    v1 = await v2.http.get("/api/v1/tasks", params={"include_done": "true"}, headers=_auth(viewer))
    assert v1.json()[0]["done"] is True
    # Without a mask an unset bool is no change, and false is unset.
    unmasked = await v2.connect(UPDATE, _update(task.id, done=False), token=viewer)
    assert unmasked.message.task.done is True
    undone = await v2.connect(UPDATE, _update(task.id, "done"), token=viewer)
    assert (undone.message.task.done, undone.message.task.done_at) == (False, None)


async def test_an_update_naming_homework_of_another_class_changes_nothing(
    v2, v2_tokens, session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    foreign = Homework(
        class_id=other.id, due_date=date(2026, 9, 7), subject_name="Алгебра", text="№ 1"
    )
    session.add(foreign)
    await session.commit()
    task = await _row(session, school_class)
    refused = await v2.both(
        UPDATE, _update(task.id, title="Чужое", homework_id=foreign.id), token=v2_tokens["viewer"]
    )
    assert (refused.status, refused.reason) == (400, "VALIDATION_FAILED")
    assert refused.violations == [("task.homework_id", wording.HOMEWORK_NOT_IN_CLASS_DETAIL)]
    columns = await _columns(session, task.id)
    assert (columns.title, columns.homework_id) == ("Купить тетрадь", None)


async def test_somebody_else_s_task_can_be_neither_changed_nor_deleted(
    v2, v2_tokens, session, school_class
) -> None:
    theirs = await _row(session, school_class, telegram_id=EDITOR)
    for name, request in (
        (UPDATE, _update(theirs.id, title="Моё")),
        (DELETE, DeleteTaskRequest(task_id=theirs.id)),
    ):
        answer = await v2.both(name, request, token=v2_tokens["viewer"])
        assert (answer.status, answer.reason, answer.metadata) == (
            404,
            "RESOURCE_NOT_FOUND",
            {"resource": "task"},
        ), name
        assert answer.error == wording.UNKNOWN_TASK_DETAIL
    assert (await _columns(session, theirs.id)).title == "Купить тетрадь"


async def test_deleting_a_task_answers_empty_and_again_finds_nothing(
    v2, v2_tokens, session, school_class
) -> None:
    first = await _row(session, school_class)
    second = await _row(session, school_class)
    viewer = v2_tokens["viewer"]
    rest = await v2.rest(DELETE, DeleteTaskRequest(task_id=first.id), token=viewer)
    connect = await v2.connect(DELETE, DeleteTaskRequest(task_id=second.id), token=viewer)
    assert (rest.status, rest.body, connect.status) == (200, b"{}", 200)
    assert await session.scalar(select(func.count()).select_from(PersonalTask)) == 0
    again = await v2.both(DELETE, DeleteTaskRequest(task_id=first.id), token=viewer)
    assert (again.status, again.reason) == (404, "RESOURCE_NOT_FOUND")


async def test_a_mask_naming_a_field_the_method_does_not_change_is_refused_on_it(
    v2, v2_tokens, session, school_class
) -> None:
    task = await _row(session, school_class)
    for path in ("id", "created_at", "done_at"):
        answer = await v2.both(UPDATE, _update(task.id, path, title="x"), token=v2_tokens["viewer"])
        assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED"), path
        assert answer.violations == [("update_mask", NOT_CHANGEABLE)]
    assert (await _columns(session, task.id)).title == "Купить тетрадь"
