"""Audited device management must retain its Class and answer stale bot presses."""

from types import SimpleNamespace

import pytest
from sqlalchemy import delete, event, func, select
from sqlalchemy.orm.exc import StaleDataError

from app import wording
from app.contract.lessons.v2.class_device_pb import (
    RevokeClassDeviceRequest,
    UnlinkClassDeviceRequest,
)
from app.db import SessionLocal
from app.models import AuditEntry, DeviceToken, Role, SchoolClass
from app.services.manage import devices


async def _seed(session, school_class):
    row = DeviceToken(class_id=school_class.id, token_hash="e" * 64,
                      device_name="Телефон", telegram_id=42, revoked=False)
    session.add(row)
    await session.commit()
    return row, SimpleNamespace(class_id=school_class.id, device_id=row.id)


async def _remove(context, resource):
    model, row_id = ((SchoolClass, context.class_id) if resource == "class"
                     else (DeviceToken, context.device_id))
    async with SessionLocal() as other:
        await other.execute(delete(model).where(model.id == row_id))
        await other.commit()


def _lose_after_read(monkeypatch, context, resource):
    real_read = devices.device_of
    staged = []

    async def deleted_after_read(*args, **kwargs):
        row = await real_read(*args, **kwargs)
        if not staged:
            await _remove(context, resource)
            staged.append(True)
        return row

    monkeypatch.setattr(devices, "device_of", deleted_after_read)
    return staged


async def _nothing_survived(context):
    async with SessionLocal() as fresh:
        assert await fresh.get(DeviceToken, context.device_id) is None
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0


@pytest.mark.parametrize("operation", ["revoke", "unlink"])
async def test_audited_device_changes_refuse_a_class_deleted_after_read(
    session, school_class, operation
):
    row, context = await _seed(session, school_class)
    await _remove(context, "class")
    with pytest.raises(StaleDataError):
        await getattr(devices, operation)(session, context.class_id, 42, row)
        await session.commit()
    await session.rollback()
    await _nothing_survived(context)


@pytest.mark.parametrize("operation", ["revoke", "unlink"])
@pytest.mark.parametrize("transport", ["v1", "rest", "connect"])
async def test_device_management_transports_refuse_the_deleted_class_without_audit(
    v2, v2_tokens, session, school_class, monkeypatch, operation, transport
):
    _, context = await _seed(session, school_class)
    staged = _lose_after_read(monkeypatch, context, "class")
    if transport == "v1":
        answer = await v2.http.post(
            f"/api/v1/manage/devices/{context.device_id}/{operation}",
            headers={"Authorization": f"Bearer {v2_tokens['admin']}"},
        )
        assert (answer.status_code, answer.json()) == (
            404, {"detail": wording.UNKNOWN_DEVICE_DETAIL}
        )
    else:
        request_type, method = (
            (RevokeClassDeviceRequest, "RevokeClassDevice") if operation == "revoke"
            else (UnlinkClassDeviceRequest, "UnlinkClassDevice")
        )
        answer = await getattr(v2, transport)(
            "ClassDeviceService/" + method, request_type(device_id=context.device_id),
            token=v2_tokens["admin"],
        )
        assert (answer.status, answer.code, answer.reason, answer.metadata, answer.error) == (
            404, "NOT_FOUND", "RESOURCE_NOT_FOUND", {"resource": "device"},
            wording.UNKNOWN_DEVICE_DETAIL,
        )
    assert staged == [True]
    await _nothing_survived(context)


@pytest.mark.parametrize("operation", ["revoke", "unlink"])
@pytest.mark.parametrize("resource", ["class", "device"])
async def test_bot_device_management_answers_the_deleted_target_without_redrawing(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, operation, resource
):
    from app.bot.handlers.manage import devices as bot_devices

    _, context = await _seed(session, school_class)
    staged = _lose_after_read(monkeypatch, context, resource)
    editable = FakeEditable()
    callback = FakeCallback(message=editable)
    await getattr(bot_devices, "device_" + operation)(
        callback, SimpleNamespace(value=str(context.device_id)), session, school_class, Role.ADMIN
    )
    assert callback.answers[-1] == ("Устройство не найдено", True)
    assert editable.replies == []
    assert staged == [True]
    await _nothing_survived(context)
    await session.commit()


async def test_revoking_an_already_revoked_device_stays_read_only_without_an_audit(
    session, school_class
):
    row, context = await _seed(session, school_class)
    assert await devices.revoke(session, context.class_id, 42, row) is True
    await session.commit()
    writes = []

    def record_write(_connection, _cursor, statement, _parameters, _context, _many):
        if statement.lstrip().split(None, 1)[0].upper() in {"INSERT", "UPDATE", "DELETE"}:
            writes.append(statement)

    engine = session.get_bind()
    event.listen(engine, "before_cursor_execute", record_write)
    try:
        assert await devices.revoke(session, context.class_id, 42, row) is False
        await session.commit()
        assert writes == []
    finally:
        event.remove(engine, "before_cursor_execute", record_write)
    assert await session.scalar(select(func.count()).select_from(AuditEntry)) == 1
