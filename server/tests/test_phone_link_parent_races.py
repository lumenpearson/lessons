"""Phone claims share their caller transaction and refuse deleted parents."""

from types import SimpleNamespace

import pytest
from sqlalchemy import delete, func, select
from sqlalchemy.orm.exc import StaleDataError

from app.db import SessionLocal
from app.models import AuditEntry, DeviceToken, Role, SchoolClass
from app.services import linking


async def seed(session, school_class):
    device = DeviceToken(class_id=school_class.id, token_hash="link-probe", link_code="ABC123")
    session.add(device)
    await session.commit()
    return device.id


async def test_link_class_loss_after_device_lookup(session, school_class, monkeypatch, FakeMessage):
    from app.bot.handlers.manage import requests

    await seed(session, school_class)
    class_id = school_class.id
    real = session.scalar
    staged = []

    async def read(*args, **kwargs):
        row = await real(*args, **kwargs)
        if isinstance(row, DeviceToken) and not staged:
            async with SessionLocal() as other:
                await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
                await other.commit()
            staged.append(True)
        return row

    monkeypatch.setattr(session, "scalar", read)
    message = FakeMessage()
    await requests.cmd_link(
        message, SimpleNamespace(args="ABC123"), session, school_class, Role.ADMIN
    )
    assert staged == [True]
    assert "привязано" not in message.last


async def test_link_late_audit_refusal_rolls_back_phone_claim(
    session, school_class, monkeypatch, FakeMessage
):
    from app.bot.handlers.manage import requests

    device_id = await seed(session, school_class)

    async def refuse(*args, **kwargs):
        raise StaleDataError("The referenced parent was deleted")

    monkeypatch.setattr(requests.audit, "record", refuse)
    message = FakeMessage()
    await requests.cmd_link(
        message, SimpleNamespace(args="ABC123"), session, school_class, Role.ADMIN
    )
    await session.commit()
    async with SessionLocal() as fresh:
        device = await fresh.get(DeviceToken, device_id)
        assert device.telegram_id is None and device.link_code == "ABC123"
    assert "привязано" not in message.last


async def test_link_service_leaves_commit_to_its_auditing_caller(session, school_class):
    device_id = await seed(session, school_class)
    assert await linking.link_device(session, "ABC123", 42) is not None
    await session.rollback()
    async with SessionLocal() as fresh:
        device = await fresh.get(DeviceToken, device_id)
        assert device.telegram_id is None and device.link_code == "ABC123"


async def test_deep_link_handles_parent_deleted_after_code_lookup(
    session, school_class, monkeypatch, FakeMessage, FakeState
):
    from app.bot.handlers.start import menu

    device = DeviceToken(class_id=school_class.id, token_hash="deep-probe", link_code="ABC123")
    session.add(device)
    await session.commit()
    class_id = school_class.id
    real = session.scalar
    staged = []

    async def read(*args, **kwargs):
        row = await real(*args, **kwargs)
        if isinstance(row, DeviceToken) and not staged:
            async with SessionLocal() as other:
                await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
                await other.commit()
            staged.append(True)
        return row

    monkeypatch.setattr(session, "scalar", read)
    message, state = FakeMessage(), FakeState()
    await menu.cmd_start_link(message, SimpleNamespace(args="link_ABC123"), state, session)
    assert staged == [True] and state.cleared
    assert "привязано" not in message.last


@pytest.mark.parametrize("caller", ["command", "deep_link"])
async def test_link_success_observes_committed_claim_before_telegram(
    session, school_class, FakeMessage, FakeState, caller
):
    from app.bot.handlers.manage import requests
    from app.bot.handlers.start import menu

    device = DeviceToken(class_id=school_class.id, token_hash="deep-success", link_code="ABC123")
    session.add(device)
    await session.commit()
    device_id = device.id
    witnessed = []

    class Message(FakeMessage):
        async def answer(self, text, **kwargs):
            async with SessionLocal() as fresh:
                row = await fresh.get(DeviceToken, device_id)
                assert row.telegram_id == 42 and row.link_code is None
                assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == (
                    1 if caller == "command" else 0
                )
                witnessed.append(True)
            await super().answer(text, **kwargs)

    message = Message()
    if caller == "command":
        await requests.cmd_link(
            message, SimpleNamespace(args="ABC123"), session, school_class, Role.ADMIN
        )
    else:
        await menu.cmd_start_link(
            message, SimpleNamespace(args="link_ABC123"), FakeState(), session
        )
    assert witnessed == [True] and "привязано" in message.last
