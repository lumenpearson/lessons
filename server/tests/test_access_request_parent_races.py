"""Access decisions retain Class before locking their pending request."""

import asyncio
from types import SimpleNamespace

import pytest
from sqlalchemy import delete, func, select, text

from app import wording
from app.contract.lessons.v2.access_request_pb import (
    ApproveAccessRequestRequest,
    DeclineAccessRequestRequest,
)
from app.db import SessionLocal
from app.models import AccessRequest, AuditEntry, Role, SchoolClass
from app.services.manage import classes, requests


@pytest.mark.parametrize("decision", ["approve", "decline"])
async def test_deciding_a_locked_request_and_class_deletion_keep_one_lock_order(
    session, school_class, decision
):
    if session.get_bind().dialect.name != "postgresql":
        pytest.skip("PostgreSQL Class/AccessRequest locks; SQLite has one file write lock")
    class_id = school_class.id
    request = AccessRequest(class_id=class_id, telegram_id=777,
                            requested_role=Role.EDITOR, status="pending")
    session.add(request)
    await session.commit()
    request_id = request.id
    writer_pid = await session.scalar(text("SELECT pg_backend_pid()"))
    locked = await requests.pending_one(session, class_id, request_id)
    assert locked is not None
    pid_ready = asyncio.get_running_loop().create_future()
    deleting = None

    async def remove_class():
        async with SessionLocal() as other:
            pid_ready.set_result(await other.scalar(text("SELECT pg_backend_pid()")))
            owned = await other.get(SchoolClass, class_id)
            assert owned is not None
            await classes.delete(other, owned, owned.name)
            await other.commit()

    try:
        async with asyncio.timeout(10):
            deleting = asyncio.create_task(remove_class())
            deleter_pid = await asyncio.wait_for(pid_ready, timeout=5)
            async with SessionLocal() as observer:
                async with asyncio.timeout(5):
                    while writer_pid not in await observer.scalar(
                        text("SELECT pg_blocking_pids(:pid)"), {"pid": deleter_pid}
                    ):
                        await asyncio.sleep(0.01)
            if decision == "approve":
                await requests.approve(session, school_class, locked,
                                       actor_id=42, actor_role=Role.ADMIN)
            else:
                await requests.decline(session, class_id, 42, locked)
            await session.commit()
            await deleting
    finally:
        await session.rollback()
        if deleting is not None:
            if not deleting.done():
                deleting.cancel()
            await asyncio.gather(deleting, return_exceptions=True)
    async with SessionLocal() as fresh:
        assert await fresh.get(SchoolClass, class_id) is None
        assert await fresh.scalar(select(func.count()).select_from(AccessRequest)) == 0
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0


async def _seed(session, school_class):
    request = AccessRequest(class_id=school_class.id, telegram_id=777,
                            requested_role=Role.EDITOR, status="pending")
    session.add(request)
    await session.commit()
    return school_class.id, request.id


def _lose_before_lookup(monkeypatch, class_id):
    real_lookup = requests.pending_one
    staged = []

    async def deleted_before_lookup(*args, **kwargs):
        if not staged:
            async with SessionLocal() as other:
                await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
                await other.commit()
            staged.append(True)
        return await real_lookup(*args, **kwargs)

    monkeypatch.setattr(requests, "pending_one", deleted_before_lookup)
    return staged


async def _no_decision_survived(class_id):
    async with SessionLocal() as fresh:
        assert await fresh.get(SchoolClass, class_id) is None
        assert await fresh.scalar(select(func.count()).select_from(AccessRequest)) == 0
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0


@pytest.mark.parametrize("decision", ["approve", "decline"])
@pytest.mark.parametrize("transport", ["v1", "rest", "connect"])
async def test_access_decisions_keep_the_existing_missing_class_refusal(
    v2, v2_tokens, session, school_class, monkeypatch, decision, transport
):
    class_id, request_id = await _seed(session, school_class)
    staged = _lose_before_lookup(monkeypatch, class_id)
    if transport == "v1":
        answer = await v2.http.post(
            f"/api/v1/manage/requests/{request_id}/{decision}",
            headers={"Authorization": f"Bearer {v2_tokens['admin']}"},
        )
        assert (answer.status_code, answer.json()) == (
            404, {"detail": wording.UNKNOWN_ACCESS_REQUEST_DETAIL}
        )
    else:
        request_type, method = (
            (ApproveAccessRequestRequest, "ApproveAccessRequest") if decision == "approve"
            else (DeclineAccessRequestRequest, "DeclineAccessRequest")
        )
        answer = await getattr(v2, transport)(
            "AccessRequestService/" + method, request_type(request_id=request_id),
            token=v2_tokens["admin"],
        )
        assert (answer.status, answer.code, answer.reason, answer.metadata, answer.error) == (
            404, "NOT_FOUND", "RESOURCE_NOT_FOUND", {"resource": "access_request"},
            wording.UNKNOWN_ACCESS_REQUEST_DETAIL,
        )
    assert staged == [True]
    await _no_decision_survived(class_id)


@pytest.mark.parametrize("decision", ["approve", "decline"])
async def test_bot_access_decisions_refuse_the_missing_class_before_notifying(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, decision
):
    from app.bot.handlers.manage import requests as bot_requests

    class_id, request_id = await _seed(session, school_class)
    staged = _lose_before_lookup(monkeypatch, class_id)
    notified = []

    async def notify(*args, **kwargs):
        notified.append(True)

    monkeypatch.setattr(bot_requests, "_tell_requester", notify)
    editable = FakeEditable()
    callback = FakeCallback(message=editable)
    await getattr(bot_requests, "request_" + decision)(
        callback, SimpleNamespace(value=str(request_id)), session, school_class, Role.ADMIN
    )
    assert callback.answers[-1] == ("Запрос уже закрыт", True)
    assert editable.replies == []
    assert notified == [] and staged == [True]
    await _no_decision_survived(class_id)
    await session.commit()
