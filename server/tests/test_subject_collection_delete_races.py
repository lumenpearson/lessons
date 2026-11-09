"""Explicit subject collection and deletion retain their Class until commit."""

from types import SimpleNamespace

import pytest
from sqlalchemy import delete, event, func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm.exc import StaleDataError

from app import wording
from app.contract.lessons.v2.subject_pb import DeleteSubjectRequest
from app.db import SessionLocal
from app.models import AuditEntry, Role, SchoolClass, Subject
from app.services import subjects as dictionary
from app.services.manage import subjects


async def _seed(session, school_class, operation):
    row = None
    if operation == "delete":
        row = Subject(class_id=school_class.id, name="Химия")
        session.add(row)
    await session.commit()
    return row, school_class.id


def _lose_after_validation(monkeypatch, class_id, operation):
    staged = []

    async def remove_once():
        if not staged:
            async with SessionLocal() as other:
                await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
                await other.commit()
            staged.append(True)

    if operation == "delete":
        real_check = dictionary.lessons_using

        async def deleted_after_usage_check(*args, **kwargs):
            count = await real_check(*args, **kwargs)
            assert count == 0
            await remove_once()
            return count

        monkeypatch.setattr(dictionary, "lessons_using", deleted_after_usage_check)
    else:
        real_read = AsyncSession.scalars

        async def deleted_after_dictionary_read(self, statement, *args, **kwargs):
            result = await real_read(self, statement, *args, **kwargs)
            descriptions = getattr(statement, "column_descriptions", ())
            if descriptions and descriptions[0].get("expr") is Subject:
                await remove_once()
            return result

        monkeypatch.setattr(AsyncSession, "scalars", deleted_after_dictionary_read)
    return staged


async def _nothing_survived(class_id):
    async with SessionLocal() as fresh:
        assert await fresh.get(SchoolClass, class_id) is None
        assert await fresh.scalar(select(func.count()).select_from(Subject)) == 0
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0


@pytest.mark.parametrize("operation", ["collect", "delete"])
async def test_explicit_subject_dictionary_writes_refuse_the_deleted_class(
    session, school_class, monkeypatch, operation
):
    row, class_id = await _seed(session, school_class, operation)
    staged = _lose_after_validation(monkeypatch, class_id, operation)
    with pytest.raises(StaleDataError):
        if operation == "collect":
            await subjects.collect(session, class_id, 42)
        else:
            await subjects.delete(session, class_id, 42, row)
        await session.commit()
    await session.rollback()
    assert staged == [True]
    await _nothing_survived(class_id)


@pytest.mark.parametrize("transport", ["v1", "rest", "connect"])
async def test_subject_delete_transports_refuse_the_class_lost_after_usage_check(
    v2, v2_tokens, session, school_class, monkeypatch, transport
):
    row, class_id = await _seed(session, school_class, "delete")
    subject_id = row.id
    staged = _lose_after_validation(monkeypatch, class_id, "delete")
    if transport == "v1":
        answer = await v2.http.delete(
            f"/api/v1/manage/subjects/{subject_id}",
            headers={"Authorization": f"Bearer {v2_tokens['admin']}"},
        )
        assert (answer.status_code, answer.json()) == (
            404, {"detail": wording.UNKNOWN_SUBJECT_DETAIL}
        )
    else:
        answer = await getattr(v2, transport)(
            "SubjectService/DeleteSubject", DeleteSubjectRequest(subject_id=subject_id),
            token=v2_tokens["admin"],
        )
        assert (answer.status, answer.code, answer.reason, answer.metadata, answer.error) == (
            404, "NOT_FOUND", "RESOURCE_NOT_FOUND", {"resource": "subject"},
            wording.UNKNOWN_SUBJECT_DETAIL,
        )
    assert staged == [True]
    await _nothing_survived(class_id)


@pytest.mark.parametrize("operation", ["collect", "delete"])
async def test_bot_subject_dictionary_writes_drop_a_deleted_class_form(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, FakeState, operation
):
    from app.bot.handlers.manage import subjects as bot_subjects

    row, class_id = await _seed(session, school_class, operation)
    staged = _lose_after_validation(monkeypatch, class_id, operation)
    state = FakeState(data={"pending": operation})
    callback = FakeCallback(message=FakeEditable())
    if operation == "collect":
        await bot_subjects.subjects_collect(callback, state, session, school_class, Role.EDITOR)
        assert callback.answers[-1] == ("Класс уже удалён. Откройте /start.", True)
    else:
        await bot_subjects.subject_delete(
            callback, SimpleNamespace(value=str(row.id)), state, session, school_class, Role.ADMIN
        )
        assert callback.answers[-1] == ("Предмет уже удалён", True)
    assert staged == [True]
    assert state.cleared and state.data == {}
    await _nothing_survived(class_id)
    await session.commit()


async def test_collecting_a_complete_dictionary_is_read_only_and_does_not_audit(
    session, school_class
):
    assert await subjects.collect(session, school_class.id, 42) == 3
    await session.commit()
    writes = []

    def record_write(_connection, _cursor, statement, _parameters, _context, _many):
        if statement.lstrip().split(None, 1)[0].upper() in {"INSERT", "UPDATE", "DELETE"}:
            writes.append(statement)

    engine = session.get_bind()
    event.listen(engine, "before_cursor_execute", record_write)
    try:
        assert await subjects.collect(session, school_class.id, 42) == 0
        await session.commit()
        assert writes == []
    finally:
        event.remove(engine, "before_cursor_execute", record_write)
    assert await session.scalar(select(func.count()).select_from(AuditEntry)) == 1
