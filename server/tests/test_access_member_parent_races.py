"""Class-loss regressions in the bot's access administration."""

from types import SimpleNamespace

import pytest
from sqlalchemy import delete, func, select
from sqlalchemy.orm.exc import StaleDataError

from app.bot.states import AddInvite
from app.db import SessionLocal
from app.models import AuditEntry, BotUser, PhoneInvite, Role, SchoolClass


async def _remove(class_id):
    async with SessionLocal() as other:
        await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
        await other.commit()


@pytest.mark.parametrize("operation", ["invite", "role", "revoke"])
async def test_bot_access_administration_refuses_the_class_lost_after_read(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, FakeState, operation
):
    from app.bot.handlers import access

    class_id = school_class.id
    session.add(BotUser(class_id=class_id, telegram_id=77, role=Role.VIEWER))
    await session.commit()
    callback = FakeCallback(message=FakeEditable())
    state = FakeState(data={"phone": "79000000000"}, state=AddInvite.role)
    if operation == "invite":
        await _remove(class_id)
        await access.invite_role(
            callback, SimpleNamespace(role=Role.EDITOR.value, target=""), state,
            session, school_class, Role.ADMIN,
        )
        assert callback.answers[-1] == ("Класс или приглашение уже удалены. Начните заново.", True)
        assert state.cleared
    else:
        real_scalar = session.scalar
        staged = []

        async def deleted_after_member_read(*args, **kwargs):
            row = await real_scalar(*args, **kwargs)
            if not staged:
                await _remove(class_id)
                staged.append(True)
            return row

        monkeypatch.setattr(session, "scalar", deleted_after_member_read)
        if operation == "role":
            await access.apply_role(
                callback, SimpleNamespace(role=Role.EDITOR.value, target="77"),
                session, school_class, Role.ADMIN,
            )
        else:
            await access.revoke(
                callback, SimpleNamespace(value="77"), session, school_class, Role.ADMIN
            )
        assert callback.answers[-1] == ("Пользователь не найден", True)
        assert staged == [True]
    assert callback.message.replies == []
    await session.commit()
    async with SessionLocal() as fresh:
        assert await fresh.get(SchoolClass, class_id) is None
        for model in [AuditEntry, BotUser, PhoneInvite]:
            assert await fresh.scalar(select(func.count()).select_from(model)) == 0


async def test_a_late_revocation_refusal_rolls_back_the_audit_with_the_membership(
    session, school_class, monkeypatch, FakeCallback, FakeEditable
):
    from app.bot.handlers import access
    from app.services import diary_link

    member = BotUser(class_id=school_class.id, telegram_id=77, role=Role.VIEWER)
    session.add(member)
    await session.commit()
    member_id = member.id

    async def stale_cleanup(*args, **kwargs):
        raise StaleDataError("A later cleanup lost its target")

    monkeypatch.setattr(diary_link, "drop_for", stale_cleanup)
    callback = FakeCallback(message=FakeEditable())
    await access.revoke(
        callback, SimpleNamespace(value="77"), session, school_class, Role.ADMIN
    )
    assert callback.answers[-1] == ("Пользователь не найден", True)
    assert callback.message.replies == []
    await session.commit()
    async with SessionLocal() as fresh:
        assert await fresh.get(BotUser, member_id) is not None
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0


async def test_class_scoped_diary_sign_out_leaves_the_callers_transaction_open(
    session, school_class
):
    from app.services import audit, diary

    await session.commit()
    await audit.record(session, school_class.id, 42, "test.before", "Caller-owned write")
    assert await diary.sign_out_here(session, telegram_id=77, class_id=school_class.id) == 0
    await session.rollback()
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0
