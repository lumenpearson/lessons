"""Candidate Class-code join refusal after the looked-up Class is deleted."""

import asyncio

import pytest
from sqlalchemy import delete, func, select, text

from app import wording
from app.contract.lessons.v2.device_pb import CreateDeviceRequest
from app.db import SessionLocal
from app.models import AuditEntry, DeviceToken, JoinAttempt, JoinMode, SchoolClass
from app.services import device_invites, join


async def test_class_code_join_refuses_a_class_deleted_after_lookup_without_counting_a_guess(
    session, school_class, monkeypatch
):
    class_id, code = school_class.id, school_class.join_code
    await session.commit()
    real_scalar = session.scalar
    staged = []

    async def deleted_after_class_lookup(*args, **kwargs):
        row = await real_scalar(*args, **kwargs)
        if isinstance(row, SchoolClass) and not staged:
            async with SessionLocal() as other:
                await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
                await other.commit()
            staged.append(True)
        return row

    monkeypatch.setattr(session, "scalar", deleted_after_class_lookup)
    with pytest.raises(join.JoinCodeUnknown):
        await join.join(session, code=code, device_name="Probe phone", client_key="parent-probe")
    await session.rollback()
    assert staged == [True]
    async with SessionLocal() as fresh:
        assert await fresh.get(SchoolClass, class_id) is None
        for model in [DeviceToken, AuditEntry, JoinAttempt]:
            assert await fresh.scalar(select(func.count()).select_from(model)) == 0


@pytest.mark.parametrize("transport", ["v1", "rest", "connect"])
async def test_device_creation_transports_refuse_class_loss_after_the_device_count(
    v2, session, school_class, monkeypatch, transport
):
    class_id, code = school_class.id, school_class.join_code
    await session.commit()
    real_count = join.live_devices
    staged = []

    async def deleted_after_count(*args, **kwargs):
        count = await real_count(*args, **kwargs)
        if not staged:
            async with SessionLocal() as other:
                await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
                await other.commit()
            staged.append(True)
        return count

    monkeypatch.setattr(join, "live_devices", deleted_after_count)
    if transport == "v1":
        answer = await v2.http.post("/api/v1/join", json={"code": code, "device_name": "Phone"})
        assert (answer.status_code, answer.json()) == (
            404, {"detail": wording.JOIN_UNKNOWN_CODE_DETAIL}
        )
    else:
        answer = await getattr(v2, transport)(
            "DeviceService/CreateDevice", CreateDeviceRequest(code=code, device_name="Phone")
        )
        assert (answer.status, answer.code, answer.reason, answer.error) == (
            404, "NOT_FOUND", "JOIN_CODE_UNKNOWN", wording.JOIN_UNKNOWN_CODE_DETAIL
        )
    assert staged == [True]
    async with SessionLocal() as fresh:
        for model in [DeviceToken, AuditEntry, JoinAttempt]:
            assert await fresh.scalar(select(func.count()).select_from(model)) == 0


async def test_personal_code_join_and_class_deletion_do_not_form_a_parent_child_lock_cycle(
    session, school_class, monkeypatch
):
    if session.get_bind().dialect.name != "postgresql":
        pytest.skip("PostgreSQL Class/DeviceInvite locks; SQLite has one file write lock")
    class_id = school_class.id
    school_class.join_mode = JoinMode.INVITE
    await session.commit()
    code = await device_invites.mint(session, telegram_id=42, class_id=class_id)
    await session.commit()
    real_burn = device_invites.burn
    deleter = None
    started = asyncio.Event()
    deleter_pid = None

    async def delete_class():
        nonlocal deleter_pid
        async with SessionLocal() as other:
            deleter_pid = await other.scalar(text("SELECT pg_backend_pid()"))
            started.set()
            await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
            await other.commit()

    async def deletion_started_after_burn(writer, invite):
        nonlocal deleter
        burned = await real_burn(writer, invite)
        assert burned
        writer_pid = await writer.scalar(text("SELECT pg_backend_pid()"))
        deleter = asyncio.create_task(delete_class())
        await asyncio.wait_for(started.wait(), 5)
        for _ in range(500):
            if deleter.done():
                await deleter
                raise AssertionError("Class deletion did not wait for the joining transaction")
            blockers = await writer.scalar(
                text("SELECT pg_blocking_pids(:pid)"), {"pid": deleter_pid}
            )
            if writer_pid in blockers:
                break
            await asyncio.sleep(0.01)
        else:
            raise AssertionError("Class deletion never waited for this writer")
        return burned

    monkeypatch.setattr(device_invites, "burn", deletion_started_after_burn)
    try:
        joined = await asyncio.wait_for(
            join.join(session, code=code, device_name="Phone", client_key="parent-lock-probe"), 15
        )
        assert joined.token
        assert deleter is not None
        await asyncio.wait_for(deleter, 15)
    finally:
        await session.rollback()
        if deleter is not None:
            if not deleter.done():
                deleter.cancel()
            await asyncio.gather(deleter, return_exceptions=True)
    async with SessionLocal() as fresh:
        assert await fresh.get(SchoolClass, class_id) is None
        assert await fresh.scalar(select(func.count()).select_from(DeviceToken)) == 0
        assert await fresh.scalar(select(func.count()).select_from(JoinAttempt)) == 0


async def test_a_real_personal_code_whose_class_disappears_is_not_an_incorrect_guess(
    session, school_class, monkeypatch
):
    class_id = school_class.id
    await session.commit()
    code = await device_invites.mint(session, telegram_id=42, class_id=class_id)
    await session.commit()
    session.expunge(school_class)
    real_get = session.get

    async def deleted_before_class_lookup(model, *args, **kwargs):
        if model is SchoolClass:
            async with SessionLocal() as other:
                await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
                await other.commit()
        return await real_get(model, *args, **kwargs)

    monkeypatch.setattr(session, "get", deleted_before_class_lookup)
    with pytest.raises(join.JoinCodeUnknown):
        await join.join(session, code=code, device_name="Phone", client_key="real-code-probe")
    await session.rollback()
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(JoinAttempt)) == 0
