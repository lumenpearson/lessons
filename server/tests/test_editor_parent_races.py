"""Candidate stale-parent regressions in remaining timetable controls."""

import asyncio
from datetime import date, time
from types import SimpleNamespace

import pytest
from sqlalchemy import delete, func, select, text
from sqlalchemy.orm.exc import StaleDataError

from app.db import SessionLocal
from app.models import (
    AuditEntry,
    BellPeriod,
    BellSchedule,
    LessonOverride,
    OverrideAction,
    Role,
    SchoolClass,
    TimetableEntry,
    WeekParity,
)


async def _remove(model, row_id):
    async with SessionLocal() as other:
        await other.execute(delete(model).where(model.id == row_id))
        await other.commit()


async def _seed_bells(session, school_class):
    schedule = BellSchedule(class_id=school_class.id, name="Обычные")
    session.add(schedule)
    await session.flush()
    school_class.bell_schedule_id = schedule.id
    session.add_all([
        BellPeriod(schedule_id=schedule.id, index=1, starts_at=time(8), ends_at=time(8, 45)),
        BellPeriod(schedule_id=schedule.id, index=2, starts_at=time(9), ends_at=time(9, 45)),
    ])
    await session.commit()
    return schedule.id


@pytest.mark.parametrize("resource", ["class", "bells"])
@pytest.mark.parametrize("index", [0, 1])
async def test_canteen_changes_refuse_a_parent_deleted_after_bells_were_read(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, resource, index
):
    from app.bot.handlers import editor

    class_id = school_class.id
    schedule_id = await _seed_bells(session, school_class)
    real_read = editor._bells
    staged = []

    async def deleted_after_bell_read(*args, **kwargs):
        answer = await real_read(*args, **kwargs)
        if not staged:
            assert answer[0] is not None and set(answer[1]) == {1, 2}
            await _remove(SchoolClass if resource == "class" else BellSchedule,
                          class_id if resource == "class" else schedule_id)
            staged.append(True)
        return answer

    monkeypatch.setattr(editor, "_bells", deleted_after_bell_read)
    callback = FakeCallback(message=FakeEditable())
    await editor.editor_set_canteen(
        callback, SimpleNamespace(day=1, flags=0, index=index), session, school_class, Role.ADMIN
    )
    assert staged == [True]
    assert callback.alerted
    assert callback.message.replies == []
    await session.commit()
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0


@pytest.mark.parametrize("operation", ["split", "merge"])
async def test_parity_changes_refuse_class_loss_after_slot_lookup(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, operation
):
    from app.bot.handlers import editor

    class_id = school_class.id
    await session.execute(delete(TimetableEntry).where(
        TimetableEntry.class_id == class_id, TimetableEntry.weekday == 1, TimetableEntry.index == 1
    ))
    for parity in ([WeekParity.ANY] if operation == "split" else [WeekParity.ODD, WeekParity.EVEN]):
        session.add(TimetableEntry(class_id=class_id, weekday=1, index=1,
                                   subject_name="Физика", parity=parity))
    await session.commit()
    real_read = session.scalars
    staged = []

    async def deleted_after_slot_read(*args, **kwargs):
        rows = list(await real_read(*args, **kwargs))
        if rows and isinstance(rows[0], TimetableEntry) and not staged:
            await _remove(SchoolClass, class_id)
            staged.append(True)
        return rows

    monkeypatch.setattr(session, "scalars", deleted_after_slot_read)
    callback = FakeCallback(message=FakeEditable())
    await editor.editor_parity(
        callback, SimpleNamespace(day=1, flags=0, index=1, action=operation, arg=0),
        session, school_class, Role.ADMIN,
    )
    assert staged == [True]
    assert callback.alerted
    assert callback.message.replies == []
    await session.commit()
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0


async def test_override_clear_refuses_class_loss_after_read(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, FakeState
):
    from app.bot.handlers.content import overrides
    from app.services import substitutions

    class_id, day = school_class.id, date(2026, 9, 15)
    session.add(LessonOverride(class_id=class_id, date=day, index=1,
                               action=OverrideAction.CANCEL))
    await session.commit()
    real_read = substitutions.substitution_on

    async def deleted_after_read(*args, **kwargs):
        row = await real_read(*args, **kwargs)
        await _remove(SchoolClass, class_id)
        return row

    monkeypatch.setattr(substitutions, "substitution_on", deleted_after_read)
    callback = FakeCallback(message=FakeEditable())
    state = FakeState(data={"date": day.isoformat(), "index": 1})
    await overrides.override_clear(callback, state, session, school_class, Role.ADMIN)
    assert callback.alerted and state.cleared
    assert callback.message.replies == []
    await session.commit()
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0


@pytest.mark.parametrize("operation", ["remove", "move"])
async def test_slot_changes_and_class_deletion_do_not_form_a_parent_child_lock_cycle(
    session, school_class, operation
):
    from app.services import audit, timetable_edit

    if session.get_bind().dialect.name != "postgresql":
        pytest.skip("PostgreSQL Class/TimetableEntry locks; SQLite has one file write lock")
    class_id = school_class.id
    session.add_all([
        TimetableEntry(class_id=class_id, weekday=6, index=i, subject_name="Физика",
                       parity=WeekParity.ANY) for i in [1, 2]
    ])
    await session.commit()
    if operation == "remove":
        assert await timetable_edit.remove_lesson(session, class_id, 6, 1) == 1
    else:
        assert await timetable_edit.move_lesson(session, class_id, 6, 1, up=False) == 2
    writer_pid = await session.scalar(text("SELECT pg_backend_pid()"))
    started = asyncio.Event()
    deleter_pid = None

    async def delete_class():
        nonlocal deleter_pid
        async with SessionLocal() as other:
            deleter_pid = await other.scalar(text("SELECT pg_backend_pid()"))
            started.set()
            await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
            await other.commit()

    deleter = asyncio.create_task(delete_class())
    try:
        await asyncio.wait_for(started.wait(), 5)
        for _ in range(500):
            if deleter.done():
                await deleter
                raise AssertionError("Class deletion did not wait for the slot writer")
            blockers = await session.scalar(
                text("SELECT pg_blocking_pids(:pid)"), {"pid": deleter_pid}
            )
            if writer_pid in blockers:
                break
            await asyncio.sleep(0.01)
        else:
            raise AssertionError("Class deletion never waited for this writer")
        await audit.record(session, class_id, 42, "timetable.test", "Slot changed")
        await asyncio.wait_for(session.commit(), 15)
        await asyncio.wait_for(deleter, 15)
    finally:
        await session.rollback()
        if not deleter.done():
            deleter.cancel()
        await asyncio.gather(deleter, return_exceptions=True)
    async with SessionLocal() as fresh:
        assert await fresh.get(SchoolClass, class_id) is None
        assert await fresh.scalar(select(func.count()).select_from(TimetableEntry)) == 0
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0


@pytest.mark.parametrize("operation", ["add", "edit"])
async def test_template_writes_refuse_class_loss_before_the_callers_audit(
    session, school_class, monkeypatch, operation
):
    from app.services import audit, timetable_edit

    class_id = school_class.id
    await session.commit()
    if operation == "add":
        await _remove(SchoolClass, class_id)
    else:
        real_read = session.scalar
        staged = []

        async def deleted_after_cell_lookup(*args, **kwargs):
            row = await real_read(*args, **kwargs)
            if isinstance(row, TimetableEntry) and not staged:
                await _remove(SchoolClass, class_id)
                staged.append(True)
            return row

        monkeypatch.setattr(session, "scalar", deleted_after_cell_lookup)
    with pytest.raises(StaleDataError):
        if operation == "add":
            await timetable_edit.add_lesson(session, class_id, 1, subject="Новый предмет")
        else:
            assert await timetable_edit.edit_lesson(
                session, class_id, 1, 1, WeekParity.ANY,
                subject="Новый предмет", room="2", teacher=None,
            )
        await audit.record(session, class_id, 42, "timetable.test", "Template changed")
        await session.commit()
    await session.rollback()
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0


@pytest.mark.parametrize("mode", ["add", "edit"])
@pytest.mark.parametrize("edit_in_place", [False, True])
async def test_lesson_answers_handle_parent_loss_before_the_service_returns(
    session, school_class, monkeypatch, FakeState, FakeEditable, mode, edit_in_place
):
    from app.bot.handlers import editor

    async def missing_parent(*args, **kwargs):
        raise StaleDataError("The referenced parent was deleted")

    monkeypatch.setattr(editor.timetable_edit, f"{mode}_lesson", missing_parent)
    state = FakeState(data={"day": 1, "index": 1, "flags": 0, "mode": mode})
    message = FakeEditable()
    await editor._write_lesson(
        message, state, session, school_class,
        subject="Физика", room=None, teacher=None, who=42, edit_in_place=edit_in_place,
    )
    assert state.cleared
    assert message.replies == ["Урока уже нет — откройте расписание заново."]
    await session.commit()
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(AuditEntry)) == 0
