"""An audited term retry must still meet the term or class it edits."""

from types import SimpleNamespace

import pytest
from sqlalchemy import delete, func, select
from sqlalchemy.orm.exc import StaleDataError

from app import wording
from app.contract.lessons.v2.common_pb import Term as TermMessage
from app.contract.lessons.v2.common_pb import TermKind as ProtoKind
from app.contract.lessons.v2.school_class_pb import (
    TermScheme,
    UpdateTermRequest,
    UpdateTermSchemeRequest,
)
from app.db import SessionLocal
from app.models import AuditEntry, Role, SchoolClass, Term, TermKind
from app.services import terms
from app.services.manage import terms as terms_manage


async def _seed(session, school_class, monkeypatch):
    monkeypatch.setattr(terms_manage, "current_year", lambda _class: 2026)
    school_class.term_kind = TermKind.QUARTER
    rows = await terms.ensure(session, school_class, 2026, kind=TermKind.QUARTER)
    await session.commit()
    first = rows[0]
    return SimpleNamespace(
        class_id=school_class.id, term_id=first.id, count=len(rows),
        starts=first.starts_on, ends=first.ends_on,
    )


def _lose_after_read(monkeypatch, context, resource):
    staged = []

    async def remove_once():
        if not staged:
            model, row_id = (
                (Term, context.term_id) if resource == "bounds"
                else (SchoolClass, context.class_id)
            )
            async with SessionLocal() as other:
                await other.execute(delete(model).where(model.id == row_id))
                await other.commit()
            staged.append(True)

    if resource == "bounds":
        real_bounds = terms.set_bounds

        async def loss_inside_the_bounds_write(*args, **kwargs):
            real_read = terms.read

            async def deleted_after_the_bounds_read(*read_args, **read_kwargs):
                rows = await real_read(*read_args, **read_kwargs)
                await remove_once()
                return rows

            monkeypatch.setattr(terms, "read", deleted_after_the_bounds_read)
            return await real_bounds(*args, **kwargs)

        monkeypatch.setattr(terms, "set_bounds", loss_inside_the_bounds_write)
    else:
        real_scheme = terms.set_scheme

        async def deleted_after_the_class_read(*args, **kwargs):
            await remove_once()
            return await real_scheme(*args, **kwargs)

        monkeypatch.setattr(terms, "set_scheme", deleted_after_the_class_read)
    return staged


async def _no_partial_write(context, resource, *, audit_count=0):
    async with SessionLocal() as fresh:
        remaining = await fresh.scalar(
            select(func.count()).select_from(Term).where(Term.class_id == context.class_id)
        )
        assert remaining == (context.count - 1 if resource == "bounds" else 0)
        assert await fresh.get(Term, context.term_id) is None
        assert await fresh.scalar(
            select(func.count()).select_from(AuditEntry)
            .where(AuditEntry.class_id == context.class_id)
        ) == audit_count


@pytest.mark.parametrize("resource", ["bounds", "scheme"])
async def test_an_unchanged_term_write_refuses_the_row_deleted_after_read(
    session, school_class, monkeypatch, resource
):
    context = await _seed(session, school_class, monkeypatch)

    async def write():
        if resource == "bounds":
            return await terms_manage.move_term(
                session, school_class, 42, 1, context.starts, context.ends
            )
        return await terms_manage.change_scheme(session, school_class, 42, TermKind.QUARTER)

    await write()
    await session.commit()
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 1
    staged = _lose_after_read(monkeypatch, context, resource)
    with pytest.raises(StaleDataError):
        await write()
        await session.commit()
    await session.rollback()
    assert staged == [True]
    await _no_partial_write(context, resource, audit_count=1 if resource == "bounds" else 0)


@pytest.mark.parametrize("transport", ["v1", "rest", "connect"])
@pytest.mark.parametrize("resource", ["bounds", "scheme"])
async def test_unchanged_term_api_writes_keep_the_existing_stale_refusal(
    v2, v2_tokens, session, school_class, monkeypatch, resource, transport
):
    context = await _seed(session, school_class, monkeypatch)
    staged = _lose_after_read(monkeypatch, context, resource)
    if transport == "v1":
        payload = (
            {"starts_on": context.starts.isoformat(), "ends_on": context.ends.isoformat()}
            if resource == "bounds" else {"kind": "quarter"}
        )
        answer = await v2.http.put(
            "/api/v1/manage/terms/1" if resource == "bounds" else "/api/v1/manage/terms/scheme",
            headers={"Authorization": f"Bearer {v2_tokens['admin']}"}, json=payload,
        )
        assert (answer.status_code, answer.json()) == (
            (422, {"detail": terms.NO_SUCH_TERM}) if resource == "bounds"
            else (404, {"detail": wording.CLASS_GONE_DETAIL})
        )
    else:
        request = (
            UpdateTermRequest(term=TermMessage(
                index=1, starts_on=context.starts.isoformat(), ends_on=context.ends.isoformat()
            )) if resource == "bounds" else
            UpdateTermSchemeRequest(term_scheme=TermScheme(kind=ProtoKind.QUARTER))
        )
        answer = await getattr(v2, transport)(
            "ClassService/UpdateTerm" if resource == "bounds" else "ClassService/UpdateTermScheme",
            request, token=v2_tokens["admin"],
        )
        assert (answer.status, answer.code, answer.reason, answer.metadata, answer.error) == (
            (400, "FAILED_PRECONDITION", "TERM_BOUNDS_REFUSED", {}, terms.NO_SUCH_TERM)
            if resource == "bounds" else
            (404, "NOT_FOUND", "RESOURCE_NOT_FOUND",
             {"resource": "class"}, wording.CLASS_GONE_DETAIL)
        )
    assert staged == [True]
    await _no_partial_write(context, resource)


@pytest.mark.parametrize("action", ["bounds", "scheme", "scheme_changed"])
async def test_bot_term_writes_clear_the_form_when_their_row_was_deleted(
    session, school_class, monkeypatch, FakeMessage, FakeState, FakeCallback,
    FakeEditable, action,
):
    from app.bot.handlers.manage import terms as bot_terms

    context = await _seed(session, school_class, monkeypatch)
    resource = "bounds" if action == "bounds" else "scheme"
    staged = _lose_after_read(monkeypatch, context, resource)
    state = FakeState(data={"term_index": 1})
    if action == "bounds":
        message = FakeMessage(text=f"{context.starts:%d.%m.%Y} - {context.ends:%d.%m.%Y}")
        await bot_terms.term_edit_apply(message, state, session, school_class, Role.ADMIN)
        assert message.last == terms.NO_SUCH_TERM
    else:
        callback = FakeCallback(message=FakeEditable())
        await bot_terms.terms_scheme(
            callback,
            SimpleNamespace(value="semester" if action == "scheme_changed" else "quarter"),
            state, session, school_class, Role.ADMIN,
        )
        assert callback.answers[-1] == ("Класс уже удалён. Откройте /start.", True)
    assert staged == [True]
    assert state.cleared and state.data == {}
    await _no_partial_write(context, resource)
    await session.commit()


async def test_a_term_write_and_cascading_class_delete_keep_one_lock_order(
    session, school_class, monkeypatch
):
    import asyncio
    from datetime import timedelta

    from sqlalchemy import text

    from app.services import audit
    from app.services.manage import classes

    if session.get_bind().dialect.name != "postgresql":
        pytest.skip("PostgreSQL Class/Term lock ordering; SQLite has one file write lock")
    context = await _seed(session, school_class, monkeypatch)
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

    async def after_the_term_update(*args, **kwargs):
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

    monkeypatch.setattr(audit, "record", after_the_term_update)
    try:
        async with asyncio.timeout(10):
            await terms_manage.move_term(
                session, school_class, 42, 1, context.starts, context.ends - timedelta(days=1)
            )
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
        assert await fresh.scalar(select(func.count()).select_from(Term)) == 0
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0
