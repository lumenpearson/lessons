"""Class-loss regressions in auxiliary bot class settings."""

from types import SimpleNamespace

import pytest
from sqlalchemy import delete, func, select
from sqlalchemy.orm.exc import StaleDataError

from app import wording
from app.config import get_settings
from app.contract.lessons.v2.me_pb import CreateCalendarFeedRequest
from app.db import SessionLocal
from app.models import AuditEntry, DeviceInvite, Role, SchoolClass


@pytest.mark.parametrize("operation", [
    "calendar-set", "calendar-unset", "join-code", "phone-code",
    "diary-unbind", "diary-provider", "diary-repeat",
])
async def test_auxiliary_class_writes_refuse_a_class_deleted_after_authentication(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, operation
):
    from app.bot.handlers.manage import calendar_feed, diary_binding
    from app.bot.handlers.start import codes

    if operation in {"diary-unbind", "diary-repeat"}:
        school_class.diary_provider = "petersburg"
    class_id = school_class.id
    await session.commit()
    async with SessionLocal() as other:
        await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
        await other.commit()
    callback = FakeCallback(message=FakeEditable())
    if operation.startswith("calendar"):
        monkeypatch.setattr(
            get_settings(), "public_base_url",
            "https://lessons.example.com" if operation == "calendar-set" else "",
        )
        await calendar_feed.calendar_rotate(callback, session, school_class, Role.ADMIN)
    elif operation == "join-code":
        await codes.rotate_code(callback, session, school_class, Role.OWNER)
    elif operation == "phone-code":
        await codes.phone_code(callback, session, school_class, Role.VIEWER)
    elif operation == "diary-unbind":
        await diary_binding.class_diary_bind(callback, session, school_class, Role.ADMIN)
    else:
        await diary_binding.class_diary_provider(
            callback, SimpleNamespace(value="petersburg"), session, school_class, Role.ADMIN
        )
    assert callback.answers[-1] == ("Класс уже удалён. Откройте /start.", True)
    assert callback.message.replies == []
    await session.commit()
    async with SessionLocal() as fresh:
        assert await fresh.get(SchoolClass, class_id) is None
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0


async def test_personal_phone_codes_refuse_the_deleted_class_in_the_shared_service(
    session, school_class
):
    from app.services import device_invites

    class_id = school_class.id
    await session.commit()
    async with SessionLocal() as other:
        await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
        await other.commit()
    with pytest.raises(StaleDataError):
        await device_invites.mint(session, telegram_id=42, class_id=class_id)
        await session.commit()
    await session.rollback()


async def test_personal_phone_code_minting_does_not_commit_the_callers_write(
    session, school_class
):
    from app.services import audit, device_invites

    await session.commit()
    await audit.record(session, school_class.id, 42, "test.before", "Caller-owned write")
    await device_invites.mint(session, telegram_id=42, class_id=school_class.id)
    await session.rollback()
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0
        assert await fresh.scalar(select(func.count()).select_from(DeviceInvite)) == 0


async def test_failed_phone_code_audit_leaves_no_committed_code(
    session, school_class, monkeypatch, FakeCallback, FakeEditable
):
    from app.bot.handlers.start import codes
    from app.services import audit

    await session.commit()

    async def stale_audit(*args, **kwargs):
        raise StaleDataError("The audited target was deleted")

    monkeypatch.setattr(audit, "record", stale_audit)
    callback = FakeCallback(message=FakeEditable())
    await codes.phone_code(callback, session, school_class, Role.VIEWER)
    assert callback.answers[-1] == ("Класс уже удалён. Откройте /start.", True)
    assert callback.message.replies == []
    await session.commit()
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(DeviceInvite)) == 0


async def test_the_final_diary_school_choice_refuses_class_loss_after_its_lookup(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, FakeState
):
    from app.bot.handlers.manage import diary_binding
    from app.services.manage import classes

    class_id = school_class.id
    await session.commit()
    real_read = classes.class_of

    async def deleted_after_lookup(*args, **kwargs):
        row = await real_read(*args, **kwargs)
        async with SessionLocal() as other:
            await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
            await other.commit()
        return row

    monkeypatch.setattr(classes, "class_of", deleted_after_lookup)
    callback = FakeCallback(message=FakeEditable())
    state = FakeState(data={
        "diary_region": "amur", "diary_class_id": class_id,
        "diary_schools": [{"id": 11, "name": "Гимназия № 1"}],
    })
    await diary_binding.class_diary_school(
        callback, SimpleNamespace(value=0), session, Role.ADMIN, state
    )
    assert callback.answers[-1] == ("Класс уже удалён. Откройте /start.", True)
    assert state.cleared and state.data == {}
    assert callback.message.replies == []
    await session.commit()
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0


@pytest.mark.parametrize("entry", ["command", "card"])
async def test_calendar_link_creation_refuses_class_loss_without_showing_a_dead_link(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, FakeMessage, FakeState, entry
):
    from app.bot.handlers.manage import calendar_feed

    class_id = school_class.id
    await session.commit()
    async with SessionLocal() as other:
        await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
        await other.commit()
    monkeypatch.setattr(get_settings(), "public_base_url", "https://lessons.example.com")
    if entry == "command":
        message, state = FakeMessage(), FakeState(data={"pending": True})
        await calendar_feed.cmd_calendar(message, state, session, school_class, Role.VIEWER)
        assert message.replies == ["Класс уже удалён. Откройте /start."]
        assert state.cleared
    else:
        callback = FakeCallback(message=FakeEditable())
        await calendar_feed.calendar_card(callback, session, school_class, Role.VIEWER)
        assert callback.answers[-1] == ("Класс уже удалён. Откройте /start.", True)
        assert callback.message.replies == []
    await session.commit()


async def test_calendar_secret_creation_refuses_the_deleted_class_in_the_shared_service(
    session, school_class
):
    from app.services import calendar

    class_id = school_class.id
    await session.commit()
    async with SessionLocal() as other:
        await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
        await other.commit()
    with pytest.raises(StaleDataError):
        await calendar.ensure_calendar_token(session, school_class)
    await session.rollback()


@pytest.mark.parametrize("transport", ["v1", "rest", "connect"])
async def test_calendar_creation_transports_refuse_class_loss_after_authentication(
    v2, v2_tokens, session, school_class, monkeypatch, served_settings, transport
):
    from app.services import calendar

    class_id = school_class.id
    await session.commit()
    monkeypatch.setattr(served_settings, "public_base_url", "https://lessons.example.com")
    monkeypatch.setattr(get_settings(), "public_base_url", "https://lessons.example.com")
    real_ensure = calendar.ensure_calendar_token

    async def deleted_after_authentication(*args, **kwargs):
        async with SessionLocal() as other:
            await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
            await other.commit()
        return await real_ensure(*args, **kwargs)

    monkeypatch.setattr(calendar, "ensure_calendar_token", deleted_after_authentication)
    token = v2_tokens["viewer"]
    if transport == "v1":
        answer = await v2.http.get(
            "/api/v1/calendar", headers={"Authorization": f"Bearer {token}"}
        )
        assert (answer.status_code, answer.json()) == (404, {"detail": wording.CLASS_GONE_DETAIL})
    else:
        answer = await getattr(v2, transport)(
            "MeService/CreateCalendarFeed", CreateCalendarFeedRequest(), token=token
        )
        assert (answer.status, answer.code, answer.reason, answer.metadata, answer.error) == (
            404, "NOT_FOUND", "RESOURCE_NOT_FOUND", {"resource": "class"}, wording.CLASS_GONE_DETAIL
        )


async def test_calendar_rotation_does_not_commit_the_callers_other_writes(
    session, school_class
):
    from app.services import audit, calendar

    class_id = school_class.id
    await session.commit()
    await audit.record(session, class_id, 42, "test.before", "Caller-owned write")
    await calendar.rotate_calendar_token(session, school_class)
    await session.rollback()
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0
        assert (await fresh.get(SchoolClass, class_id)).calendar_token is None


async def test_a_failed_calendar_rotation_audit_leaves_the_old_secret_in_place(
    session, school_class, monkeypatch, FakeCallback, FakeEditable
):
    from app.bot.handlers.manage import calendar_feed
    from app.services import audit

    class_id = school_class.id
    await session.commit()
    monkeypatch.setattr(get_settings(), "public_base_url", "https://lessons.example.com")

    async def stale_audit(*args, **kwargs):
        raise StaleDataError("The audited target was deleted")

    monkeypatch.setattr(audit, "record", stale_audit)
    callback = FakeCallback(message=FakeEditable())
    await calendar_feed.calendar_rotate(callback, session, school_class, Role.ADMIN)
    assert callback.answers[-1] == ("Класс уже удалён. Откройте /start.", True)
    assert callback.message.replies == []
    await session.commit()
    async with SessionLocal() as fresh:
        assert (await fresh.get(SchoolClass, class_id)).calendar_token is None
