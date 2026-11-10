"""Task links refuse homework removed after lookup instead of failing their FK."""

import asyncio
from datetime import date

import pytest
from sqlalchemy import delete, func, select, text

from app import wording
from app.contract.lessons.v2.me_pb import CreateTaskRequest, Task, UpdateTaskRequest
from app.db import SessionLocal
from app.models import Homework, PersonalTask
from app.services import tasks


@pytest.mark.parametrize("operation", ["create", "update"])
async def test_task_refuses_homework_deleted_after_validation_lookup(
    session, school_class, monkeypatch, operation
):
    homework = Homework(
        class_id=school_class.id, due_date=date(2026, 9, 15), subject_name="Физика", text="§ 3"
    )
    task = PersonalTask(class_id=school_class.id, telegram_id=42, title="Исходная")
    session.add_all([homework, task])
    await session.commit()
    homework_id = homework.id
    real = tasks.homework_service.homework_of
    staged = []

    async def deleted_after_read(*args, **kwargs):
        row = await real(*args, **kwargs)
        assert row is not None
        async with SessionLocal() as other:
            await other.execute(delete(Homework).where(Homework.id == homework_id))
            await other.commit()
        staged.append(True)
        return row

    monkeypatch.setattr(tasks.homework_service, "homework_of", deleted_after_read)
    with pytest.raises(tasks.HomeworkNotInClass):
        if operation == "create":
            await tasks.create_task(session, school_class, 42, "Новая", homework_id=homework_id)
        else:
            await tasks.update_task(
                session, school_class, task, {"title": "Новая", "homework_id": homework_id}
            )
        await session.commit()
    assert staged == [True]
    await session.rollback()


@pytest.mark.parametrize("operation", ["create", "update"])
@pytest.mark.parametrize("transport", ["v1", "rest", "connect"])
async def test_task_link_transports_keep_the_existing_homework_validation_refusal(
    v2, v2_tokens, session, school_class, monkeypatch, operation, transport
):
    homework = Homework(
        class_id=school_class.id, due_date=date(2026, 9, 15), subject_name="Физика", text="§ 3"
    )
    task = PersonalTask(class_id=school_class.id, telegram_id=2001, title="Исходная")
    session.add_all([homework, task])
    await session.commit()
    homework_id, task_id = homework.id, task.id
    real = tasks.homework_service.homework_of
    staged = []

    async def deleted_after_read(*args, **kwargs):
        row = await real(*args, **kwargs)
        assert row is not None
        async with SessionLocal() as other:
            await other.execute(delete(Homework).where(Homework.id == homework_id))
            await other.commit()
        staged.append(True)
        return row

    monkeypatch.setattr(tasks.homework_service, "homework_of", deleted_after_read)
    token = v2_tokens["viewer"]
    if transport == "v1":
        method = v2.http.post if operation == "create" else v2.http.patch
        path = "/api/v1/tasks" if operation == "create" else f"/api/v1/tasks/{task_id}"
        response = await method(
            path,
            json={"title": "Новая", "homework_id": homework_id},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert (response.status_code, response.json()) == (
            422,
            {"detail": wording.HOMEWORK_NOT_IN_CLASS_DETAIL},
        )
    else:
        request = (
            CreateTaskRequest(task=Task(title="Новая", homework_id=homework_id))
            if operation == "create"
            else UpdateTaskRequest(task=Task(id=task_id, title="Новая", homework_id=homework_id))
        )
        response = await getattr(v2, transport)(
            "MeService/" + ("CreateTask" if operation == "create" else "UpdateTask"),
            request,
            token=token,
        )
        assert (response.status, response.code, response.reason) == (
            400,
            "INVALID_ARGUMENT",
            "VALIDATION_FAILED",
        )
        assert response.violations == [("task.homework_id", wording.HOMEWORK_NOT_IN_CLASS_DETAIL)]
    assert staged == [True]
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(PersonalTask)) == 1
        persisted = await fresh.get(PersonalTask, task_id)
        assert (persisted.title, persisted.homework_id) == ("Исходная", None)


@pytest.mark.parametrize("operation", ["create", "update"])
async def test_task_link_keeps_homework_until_its_callers_commit(session, school_class, operation):
    if session.get_bind().dialect.name != "postgresql":
        pytest.skip("PostgreSQL row retention; SQLite serializes file writes")
    homework = Homework(
        class_id=school_class.id, due_date=date(2026, 9, 15), subject_name="Физика", text="§ 3"
    )
    task = PersonalTask(class_id=school_class.id, telegram_id=42, title="Исходная")
    session.add_all([homework, task])
    await session.commit()
    homework_id = homework.id
    if operation == "create":
        task = await tasks.create_task(session, school_class, 42, "Новая", homework_id=homework_id)
    else:
        await tasks.update_task(
            session, school_class, task, {"title": "Новая", "homework_id": homework_id}
        )
    task_id = task.id
    writer_pid = await session.scalar(text("SELECT pg_backend_pid()"))
    started = asyncio.Event()
    deleter_pid = None

    async def delete_homework():
        nonlocal deleter_pid
        async with SessionLocal() as other:
            deleter_pid = await other.scalar(text("SELECT pg_backend_pid()"))
            started.set()
            await other.execute(delete(Homework).where(Homework.id == homework_id))
            await other.commit()

    deleter = asyncio.create_task(delete_homework())
    try:
        await asyncio.wait_for(started.wait(), 5)
        for _ in range(500):
            if deleter.done():
                await deleter
                raise AssertionError("Homework deletion did not wait for the task writer")
            if writer_pid in await session.scalar(
                text("SELECT pg_blocking_pids(:pid)"), {"pid": deleter_pid}
            ):
                break
            await asyncio.sleep(0.01)
        else:
            raise AssertionError("Homework deletion never waited for this task writer")
        await asyncio.wait_for(session.commit(), 15)
        await asyncio.wait_for(deleter, 15)
    finally:
        await session.rollback()
        if not deleter.done():
            deleter.cancel()
        await asyncio.gather(deleter, return_exceptions=True)
    async with SessionLocal() as fresh:
        assert await fresh.get(Homework, homework_id) is None
        persisted = await fresh.get(PersonalTask, task_id)
        assert persisted.title == "Новая" and persisted.homework_id is None
