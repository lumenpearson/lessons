"""Subject creation must retain the Class found by authentication."""

import pytest
from sqlalchemy import delete, func, select
from sqlalchemy.orm.exc import StaleDataError

from app import wording
from app.contract.lessons.v2.subject_pb import CreateSubjectRequest
from app.contract.lessons.v2.subject_pb import Subject as SubjectMessage
from app.db import SessionLocal
from app.models import AuditEntry, Role, SchoolClass, Subject
from app.services import subjects as dictionary
from app.services.manage import subjects


def _lose_after_name_check(monkeypatch, class_id):
    real_check = dictionary.clashing
    staged = []

    async def deleted_after_check(*args, **kwargs):
        found = await real_check(*args, **kwargs)
        if not staged:
            assert found is None
            async with SessionLocal() as other:
                await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
                await other.commit()
            staged.append(True)
        return found

    monkeypatch.setattr(dictionary, "clashing", deleted_after_check)
    return staged


async def _nothing_survived(class_id):
    async with SessionLocal() as fresh:
        assert await fresh.get(SchoolClass, class_id) is None
        assert await fresh.scalar(select(func.count()).select_from(Subject)) == 0
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0


async def test_subject_creation_refuses_a_class_deleted_after_the_name_check(
    session, school_class, monkeypatch
):
    class_id = school_class.id
    await session.commit()
    staged = _lose_after_name_check(monkeypatch, class_id)
    with pytest.raises(StaleDataError):
        await subjects.create(session, class_id, 42, "Химия")
        await session.commit()
    await session.rollback()
    assert staged == [True]
    await _nothing_survived(class_id)


@pytest.mark.parametrize("transport", ["v1", "rest", "connect"])
async def test_subject_creation_transports_refuse_the_deleted_class_without_an_orphan(
    v2, v2_tokens, session, school_class, monkeypatch, transport
):
    class_id = school_class.id
    await session.commit()
    staged = _lose_after_name_check(monkeypatch, class_id)
    if transport == "v1":
        answer = await v2.http.post(
            "/api/v1/manage/subjects", json={"name": "Химия"},
            headers={"Authorization": f"Bearer {v2_tokens['admin']}"},
        )
        assert (answer.status_code, answer.json()) == (
            404, {"detail": wording.UNKNOWN_SUBJECT_DETAIL}
        )
    else:
        answer = await getattr(v2, transport)(
            "SubjectService/CreateSubject", CreateSubjectRequest(
                subject=SubjectMessage(name="Химия")
            ), token=v2_tokens["admin"],
        )
        assert (answer.status, answer.code, answer.reason, answer.metadata, answer.error) == (
            404, "NOT_FOUND", "RESOURCE_NOT_FOUND", {"resource": "subject"},
            wording.UNKNOWN_SUBJECT_DETAIL,
        )
    assert staged == [True]
    await _nothing_survived(class_id)


async def test_bot_subject_creation_drops_the_form_for_the_deleted_class(
    session, school_class, monkeypatch, FakeMessage, FakeState
):
    from app.bot.handlers.manage import subjects as bot_subjects

    class_id = school_class.id
    await session.commit()
    staged = _lose_after_name_check(monkeypatch, class_id)
    state = FakeState(data={"pending": "subject"})
    message = FakeMessage(text="Химия")
    await bot_subjects.subject_create(message, state, session, school_class, Role.ADMIN)
    assert message.last == "Класс уже удалён. Откройте /start."
    assert state.cleared and state.data == {}
    assert staged == [True]
    await _nothing_survived(class_id)
    await session.commit()
