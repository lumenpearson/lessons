"""Access-request submission must refuse a Class deleted after authentication."""

from types import SimpleNamespace

import pytest
from sqlalchemy import delete, func, select
from sqlalchemy.orm.exc import StaleDataError

from app.db import SessionLocal
from app.models import AccessRequest, AuditEntry, Role, SchoolClass
from app.services.manage import requests


async def _remove(class_id):
    async with SessionLocal() as other:
        await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
        await other.commit()


async def _nothing_survived():
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(AccessRequest)) == 0
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0


async def test_access_request_submission_refuses_a_class_deleted_after_authentication(
    session, school_class
):
    class_id = school_class.id
    await session.commit()
    await _remove(class_id)
    with pytest.raises(StaleDataError):
        await requests.submit(session, class_id, 42, "Please grant editor access")
        await session.commit()
    await session.rollback()
    await _nothing_survived()


@pytest.mark.parametrize("entry", ["command", "fsm"])
async def test_bot_request_submission_answers_class_loss_without_notifying_admins(
    session, school_class, FakeMessage, FakeState, monkeypatch, entry
):
    from app.bot.handlers.manage import requests as bot_requests

    class_id = school_class.id
    await session.commit()
    await _remove(class_id)
    notices = []

    async def record_notice(*args, **kwargs):
        notices.append(True)

    monkeypatch.setattr(bot_requests, "_notify_admins", record_notice)
    message, state = FakeMessage(text="Please grant editor access"), FakeState()
    if entry == "command":
        await bot_requests.cmd_request(
            message, SimpleNamespace(args=message.text), state, session, school_class, Role.VIEWER
        )
    else:
        await bot_requests.request_message(message, state, session, school_class, Role.VIEWER)
    assert message.replies == ["Класс уже удалён. Откройте /start."]
    assert state.cleared
    assert notices == []
    await session.commit()
    await _nothing_survived()
