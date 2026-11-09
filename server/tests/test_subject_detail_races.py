"""Audited subject details retain their row and its class, including retries."""

from types import SimpleNamespace

import pytest
from protobuf.wkt import FieldMask
from sqlalchemy import delete, func, select
from sqlalchemy.orm.exc import StaleDataError

from app import wording
from app.contract.lessons.v2.subject_pb import Subject as SubjectMessage
from app.contract.lessons.v2.subject_pb import UpdateSubjectRequest
from app.db import SessionLocal
from app.models import AuditEntry, Role, SchoolClass, Subject
from app.services.manage import subjects

VALUES = {"short_name": "Физ", "teacher": "Учитель", "color": "#112233"}


async def _seed(session, school_class):
    row = Subject(class_id=school_class.id, name="Физика", **VALUES)
    session.add(row)
    await session.commit()
    return row, SimpleNamespace(class_id=school_class.id, subject_id=row.id)


async def _remove(context, resource):
    model, row_id = ((Subject, context.subject_id) if resource == "subject"
                     else (SchoolClass, context.class_id))
    async with SessionLocal() as other:
        await other.execute(delete(model).where(model.id == row_id))
        await other.commit()


def _lose_after_read(monkeypatch, context, resource):
    real_read = subjects.subject_of
    staged = []

    async def deleted_after_read(*args, **kwargs):
        row = await real_read(*args, **kwargs)
        if not staged:
            await _remove(context, resource)
            staged.append(True)
        return row

    monkeypatch.setattr(subjects, "subject_of", deleted_after_read)
    return staged


async def _nothing_survived(context, *, audit_count=0):
    async with SessionLocal() as fresh:
        assert await fresh.get(Subject, context.subject_id) is None
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)
                                  .where(AuditEntry.class_id == context.class_id)) == audit_count


@pytest.mark.parametrize("column", list(VALUES))
@pytest.mark.parametrize("resource", ["subject", "class"])
async def test_a_subject_detail_retry_refuses_its_deleted_row(
    session, school_class, column, resource
):
    row, context = await _seed(session, school_class)
    await subjects.set_detail(session, context.class_id, 42, row, column, VALUES[column])
    await session.commit()
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 1
    await _remove(context, resource)
    with pytest.raises(StaleDataError):
        await subjects.set_detail(session, context.class_id, 42, row, column, VALUES[column])
        await session.commit()
    await session.rollback()
    await _nothing_survived(context, audit_count=1 if resource == "subject" else 0)


@pytest.mark.parametrize("transport", ["v1", "rest", "connect"])
@pytest.mark.parametrize("resource", ["subject", "class"])
async def test_subject_detail_transports_refuse_a_deleted_subject_without_an_audit(
    v2, v2_tokens, session, school_class, monkeypatch, transport, resource
):
    _, context = await _seed(session, school_class)
    staged = _lose_after_read(monkeypatch, context, resource)
    if transport == "v1":
        answer = await v2.http.patch(
            f"/api/v1/manage/subjects/{context.subject_id}",
            headers={"Authorization": f"Bearer {v2_tokens['admin']}"},
            json={"teacher": VALUES["teacher"]},
        )
        assert (answer.status_code, answer.json()) == (
            404, {"detail": wording.UNKNOWN_SUBJECT_DETAIL}
        )
    else:
        answer = await getattr(v2, transport)(
            "SubjectService/UpdateSubject",
            UpdateSubjectRequest(subject=SubjectMessage(
                id=context.subject_id, teacher=VALUES["teacher"]
            ), update_mask=FieldMask(paths=["teacher"])), token=v2_tokens["admin"],
        )
        assert (answer.status, answer.code, answer.reason, answer.metadata, answer.error) == (
            404, "NOT_FOUND", "RESOURCE_NOT_FOUND", {"resource": "subject"},
            wording.UNKNOWN_SUBJECT_DETAIL,
        )
    assert staged == [True]
    await _nothing_survived(context)


@pytest.mark.parametrize("action,resource", [
    ("short_name", "subject"), ("teacher", "subject"),
    ("colour_typed", "subject"), ("colour_pick", "subject"), ("teacher", "class"),
])
async def test_bot_subject_detail_retries_clear_the_deleted_subject_form(
    session, school_class, monkeypatch, FakeState, FakeMessage, FakeCallback,
    FakeEditable, action, resource,
):
    from app.bot.handlers.manage import subjects as bot_subjects

    _, context = await _seed(session, school_class)
    staged = _lose_after_read(monkeypatch, context, resource)
    state = FakeState(data={"subject_id": context.subject_id})
    if action == "colour_pick":
        callback = FakeCallback(message=FakeEditable())
        await bot_subjects.subject_colour_pick(
            callback, SimpleNamespace(value=f"{context.subject_id}:#112233"),
            state, session, school_class, Role.ADMIN,
        )
        assert callback.answers[-1] == ("Предмет уже удалён", True)
    else:
        value = VALUES["color" if action == "colour_typed" else action]
        message = FakeMessage(text=value)
        await getattr(bot_subjects, "subject_" + action)(
            message, state, session, school_class, Role.ADMIN,
        )
        assert message.last == "Предмет уже удалён."
    assert staged == [True]
    assert state.cleared and state.data == {}
    await _nothing_survived(context)
    await session.commit()


async def test_a_subject_rename_and_cascading_class_delete_keep_one_lock_order(
    session, school_class, monkeypatch
):
    import asyncio

    from sqlalchemy import text

    from app.services import audit
    from app.services.manage import classes

    if session.get_bind().dialect.name != "postgresql":
        pytest.skip("PostgreSQL Class/Subject lock ordering; SQLite has one file write lock")
    _, context = await _seed(session, school_class)
    writer_pid = await session.scalar(text("SELECT pg_backend_pid()"))
    pid_ready = asyncio.get_running_loop().create_future()
    deleting = None
    real_audit = audit.record

    async def remove_class():
        async with SessionLocal() as other:
            pid_ready.set_result(await other.scalar(text("SELECT pg_backend_pid()")))
            owned_class = await other.get(SchoolClass, context.class_id)
            assert owned_class is not None
            await classes.delete(other, owned_class, owned_class.name)
            await other.commit()

    async def after_the_subject_update(*args, **kwargs):
        nonlocal deleting
        deleting = asyncio.create_task(remove_class())
        deleter_pid = await asyncio.wait_for(pid_ready, timeout=5)
        async with SessionLocal() as observer:
            async with asyncio.timeout(5):
                while writer_pid not in await observer.scalar(
                    text("SELECT pg_blocking_pids(:pid)"), {"pid": deleter_pid}
                ):
                    await asyncio.sleep(0.01)
        await real_audit(*args, **kwargs)

    monkeypatch.setattr(audit, "record", after_the_subject_update)
    try:
        async with asyncio.timeout(10):
            row = await session.get(Subject, context.subject_id)
            await subjects.rename(session, context.class_id, 42, row, "Химия")
            await session.commit()
            assert deleting is not None
            await deleting
    finally:
        await session.rollback()
        if deleting is not None:
            if not deleting.done():
                deleting.cancel()
            await asyncio.gather(deleting, return_exceptions=True)
    async with SessionLocal() as fresh:
        assert await fresh.get(SchoolClass, context.class_id) is None
        assert await fresh.scalar(select(func.count()).select_from(Subject)) == 0
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0
