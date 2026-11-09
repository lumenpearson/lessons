"""An audited retry still has to find the row it claims to have written."""

from datetime import date
from types import SimpleNamespace

import pytest
from sqlalchemy import delete, func, select
from sqlalchemy.orm.exc import StaleDataError

from app import wording
from app.db import SessionLocal
from app.models import AuditEntry, DayKind, DayOverride, Homework, Role
from app.services import homework
from app.services.manage import special_days

MONDAY = date(2026, 9, 14)


async def _seed(session, school_class, resource, actor):
    row = (
        Homework(class_id=school_class.id, due_date=MONDAY, subject_name="Алгебра",
                 text="Задание", created_by=actor)
        if resource == "homework" else
        DayOverride(class_id=school_class.id, date=MONDAY, kind=DayKind.HOLIDAY)
    )
    session.add(row)
    await session.commit()
    return row.id


def _lose_the_read_row(monkeypatch, resource, row_id):
    owner, name, model = (
        (homework, "_find", Homework) if resource == "homework"
        else (special_days, "mark_on", DayOverride)
    )
    real_read = getattr(owner, name)
    staged = []

    async def deleted_after_read(*args, **kwargs):
        row = await real_read(*args, **kwargs)
        if not staged:
            async with SessionLocal() as other:
                await other.execute(delete(model).where(model.id == row_id))
                await other.commit()
            staged.append(True)
        return row

    monkeypatch.setattr(owner, name, deleted_after_read)
    return staged


async def _nothing_survived(resource, *, audit_count=0):
    model = Homework if resource == "homework" else DayOverride
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(model)) == 0
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == audit_count


@pytest.mark.parametrize("resource", ["homework", "day"])
async def test_an_unchanged_audited_write_refuses_its_deleted_row(
    session, school_class, monkeypatch, resource
):
    row_id = await _seed(session, school_class, resource, 2002)

    async def write():
        if resource == "homework":
            return await homework.put(session, school_class, 2002, MONDAY, "Алгебра", "Задание")
        return await special_days.set_day(
            session, school_class, 2002, MONDAY, kind=DayKind.HOLIDAY,
            note=None, bell_schedule_id=None,
        )

    healthy = await write()
    await session.commit()
    assert healthy.notice is not None
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 1

    staged = _lose_the_read_row(monkeypatch, resource, row_id)
    with pytest.raises(StaleDataError):
        await write()
        await session.commit()
    await session.rollback()
    assert staged == [True]
    await _nothing_survived(resource, audit_count=1)


@pytest.mark.parametrize("resource", ["homework", "day"])
async def test_v1_an_unchanged_audited_retry_refuses_without_a_notice(
    v2, v2_tokens, session, school_class, monkeypatch, notices, resource
):
    row_id = await _seed(session, school_class, resource, 2002)
    staged = _lose_the_read_row(monkeypatch, resource, row_id)
    payload = (
        {"due_date": MONDAY.isoformat(), "subject": "Алгебра", "text": "Задание"}
        if resource == "homework" else
        {"date": MONDAY.isoformat(), "kind": "holiday", "note": None, "bell_schedule_id": None}
    )
    answer = await v2.http.put(
        "/api/v1/homework" if resource == "homework" else "/api/v1/days",
        headers={"Authorization": f"Bearer {v2_tokens['editor']}"}, json=payload,
    )
    detail = (
        wording.UNKNOWN_HOMEWORK_DETAIL if resource == "homework" else wording.DAY_NOT_MARKED_DETAIL
    )
    assert (answer.status_code, answer.json()) == (404, {"detail": detail})
    assert staged == [True]
    assert notices.built == 0
    await _nothing_survived(resource)


@pytest.mark.parametrize("resource", ["homework", "day"])
async def test_bot_an_unchanged_audited_retry_clears_the_stale_form(
    session, school_class, monkeypatch, FakeMessage, FakeState, FakeCallback,
    FakeEditable, resource,
):
    from app.bot.handlers.content import homework as bot_homework
    from app.bot.handlers.manage import holidays

    row_id = await _seed(session, school_class, resource, 42)
    staged = _lose_the_read_row(monkeypatch, resource, row_id)
    notices = []

    async def notify(*args, **kwargs):
        notices.append(True)

    monkeypatch.setattr(bot_homework.notify, "notify_subscribers", notify)
    if resource == "homework":
        state = FakeState(data={"due": MONDAY.isoformat(), "subject": "Алгебра"})
        message = FakeMessage(text="Задание")
        await bot_homework.homework_text(message, state, session, school_class, Role.EDITOR)
        assert message.last == "Задание уже удалено. Начните заново."
    else:
        state = FakeState(data={"date": MONDAY.isoformat()})
        callback = FakeCallback(message=FakeEditable())
        await holidays.holiday_kind(
            callback, SimpleNamespace(value=f"{MONDAY.isoformat()}:holiday"),
            state, session, school_class, Role.EDITOR,
        )
        assert callback.answers[-1] == (holidays.DAY_GONE, True)
    assert staged == [True]
    assert state.cleared and state.data == {}
    assert notices == []
    await _nothing_survived(resource)
    await session.commit()
