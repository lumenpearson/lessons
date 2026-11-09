"""A class removed between an assignment's lookup and its first write."""

from datetime import date

import pytest
from sqlalchemy import delete, func, select
from sqlalchemy.orm.exc import StaleDataError

from app import wording
from app.contract.lessons.v2.homework_pb import CreateHomeworkRequest
from app.contract.lessons.v2.homework_pb import Homework as HomeworkMessage
from app.db import SessionLocal
from app.models import AuditEntry, Homework, Role, SchoolClass
from app.services import homework


def _lose_class_after_lookup(monkeypatch, class_id):
    real_find = homework._find
    staged = []

    async def lose_class_after_lookup(*args, **kwargs):
        found = await real_find(*args, **kwargs)
        if not staged:
            async with SessionLocal() as other:
                await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
                await other.commit()
            staged.append(True)
        return found

    monkeypatch.setattr(homework, "_find", lose_class_after_lookup)
    return staged


async def _nothing_was_written():
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(Homework)) == 0
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0


@pytest.mark.parametrize("write", ["upsert", "upsert-existing", "create"])
async def test_an_assignment_refuses_a_class_deleted_after_its_lookup(
    session, school_class, monkeypatch, write
):
    class_id = school_class.id
    if write == "upsert-existing":
        session.add(Homework(class_id=class_id, due_date=date(2026, 9, 14),
                             subject_name="Алгебра", text="Старое"))
        await session.commit()
    staged = _lose_class_after_lookup(monkeypatch, class_id)
    with pytest.raises(StaleDataError):
        if write.startswith("upsert"):
            await homework.upsert(session, class_id, date(2026, 9, 14), "Алгебра", "Задание", 2002)
        else:
            await homework.create(
                session, school_class, 2002, date(2026, 9, 14), "Алгебра", "Задание"
            )
    await session.rollback()
    assert staged == [True]
    await _nothing_was_written()


@pytest.mark.parametrize("transport", ["v1", "v1-existing", "rest", "connect"])
async def test_homework_parent_loss_keeps_the_api_missing_resource_refusal(
    v2, v2_tokens, session, school_class, monkeypatch, notices, transport
):
    class_id = school_class.id
    if transport == "v1-existing":
        session.add(Homework(class_id=class_id, due_date=date(2026, 9, 14),
                             subject_name="Алгебра", text="Старое"))
        await session.commit()
    staged = _lose_class_after_lookup(monkeypatch, class_id)
    if transport.startswith("v1"):
        answer = await v2.http.put(
            "/api/v1/homework", headers={"Authorization": f"Bearer {v2_tokens['editor']}"},
            json={"due_date": "2026-09-14", "subject": "Алгебра", "text": "Задание"},
        )
        assert (answer.status_code, answer.json()) == (
            404, {"detail": wording.UNKNOWN_HOMEWORK_DETAIL}
        )
    else:
        answer = await getattr(v2, transport)(
            "HomeworkService/CreateHomework",
            CreateHomeworkRequest(homework=HomeworkMessage(
                due_date="2026-09-14", subject="Алгебра", text="Задание"
            )), token=v2_tokens["editor"],
        )
        assert (answer.status, answer.code, answer.reason, answer.metadata, answer.error) == (
            404, "NOT_FOUND", "RESOURCE_NOT_FOUND", {"resource": "homework"},
            wording.UNKNOWN_HOMEWORK_DETAIL,
        )
    assert staged == [True]
    assert notices.built == 0
    await _nothing_was_written()


@pytest.mark.parametrize("existing", [False, True])
async def test_bot_homework_parent_loss_clears_state_without_success_or_notice(
    session, school_class, monkeypatch, FakeMessage, FakeState, existing
):
    from app.bot.handlers.content import homework as bot_homework

    class_id = school_class.id
    if existing:
        session.add(Homework(class_id=class_id, due_date=date(2026, 9, 14),
                             subject_name="Алгебра", text="Старое"))
        await session.commit()
    staged = _lose_class_after_lookup(monkeypatch, class_id)
    notices = []

    async def notify(*args, **kwargs):
        notices.append(True)

    monkeypatch.setattr(bot_homework.notify, "notify_subscribers", notify)
    message = FakeMessage(text="Задание")
    state = FakeState(data={"due": "2026-09-14", "subject": "Алгебра"})
    await bot_homework.homework_text(message, state, session, school_class, Role.EDITOR)
    assert staged == [True]
    assert message.last == "Задание уже удалено. Начните заново."
    assert state.cleared and state.data == {}
    assert notices == []
    await _nothing_was_written()
    await session.commit()
