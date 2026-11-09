"""Personal task creation must refuse a Class deleted after authentication."""

from types import SimpleNamespace

import pytest
from sqlalchemy import delete, func, select
from sqlalchemy.orm.exc import StaleDataError

from app import wording
from app.contract.lessons.v2.me_pb import CreateTaskRequest, Task
from app.db import SessionLocal
from app.models import PersonalTask, Role, SchoolClass
from app.services import tasks


async def _remove(class_id):
    async with SessionLocal() as other:
        await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
        await other.commit()


async def _nothing_survived(class_id):
    async with SessionLocal() as fresh:
        assert await fresh.get(SchoolClass, class_id) is None
        assert await fresh.scalar(select(func.count()).select_from(PersonalTask)) == 0


@pytest.mark.parametrize("entry", ["add", "create"])
async def test_new_personal_tasks_refuse_the_class_lost_after_authentication(
    session, school_class, entry
):
    class_id = school_class.id
    await session.commit()
    await _remove(class_id)
    with pytest.raises(StaleDataError):
        if entry == "add":
            await tasks.add_task(session, class_id, 42, "Тетрадь")
        else:
            await tasks.create_task(session, school_class, 42, "Тетрадь")
        await session.commit()
    await session.rollback()
    await _nothing_survived(class_id)


@pytest.mark.parametrize("transport", ["v1", "rest", "connect"])
async def test_task_creation_transports_refuse_the_class_lost_after_authentication(
    v2, v2_tokens, session, school_class, monkeypatch, transport
):
    class_id = school_class.id
    await session.commit()
    real_create = tasks.create_task
    staged = []

    async def deleted_after_authentication(*args, **kwargs):
        await _remove(class_id)
        staged.append(True)
        return await real_create(*args, **kwargs)

    monkeypatch.setattr(tasks, "create_task", deleted_after_authentication)
    token = v2_tokens["viewer"]
    if transport == "v1":
        answer = await v2.http.post(
            "/api/v1/tasks", json={"title": "Тетрадь"},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert (answer.status_code, answer.json()) == (
            404, {"detail": wording.UNKNOWN_TASK_DETAIL}
        )
    else:
        answer = await getattr(v2, transport)(
            "MeService/CreateTask", CreateTaskRequest(task=Task(title="Тетрадь")), token=token
        )
        assert (answer.status, answer.code, answer.reason, answer.metadata, answer.error) == (
            404, "NOT_FOUND", "RESOURCE_NOT_FOUND", {"resource": "task"},
            wording.UNKNOWN_TASK_DETAIL,
        )
    assert staged == [True]
    await _nothing_survived(class_id)


@pytest.mark.parametrize("entry", ["fsm", "command"])
async def test_bot_task_creation_refuses_the_lost_class_without_a_saved_confirmation(
    session, school_class, FakeMessage, FakeState, entry
):
    from app.bot.handlers import tasks as bot_tasks

    class_id = school_class.id
    await session.commit()
    await _remove(class_id)
    message, state = FakeMessage(text="Тетрадь"), FakeState()
    if entry == "fsm":
        await bot_tasks.task_add_text(message, state, session, school_class, Role.VIEWER)
    else:
        await bot_tasks.cmd_task(
            message, SimpleNamespace(args="Тетрадь"), state, session, school_class, Role.VIEWER
        )
    assert message.replies == [bot_tasks.NO_ACCESS]
    assert state.cleared
    await session.commit()
    await _nothing_survived(class_id)
