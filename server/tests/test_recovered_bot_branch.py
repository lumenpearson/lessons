"""A missing diary race recovered from e805dad0 while reconciling branches (#725)."""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.handlers import diary as diary_bot
from app.crypto import seal
from app.db import Base, SessionLocal
from app.models import (
    AuditEntry,
    DiarySession,
    Role,
    SchoolClass,
    Subject,
    TimetableEntry,
    WeekParity,
)
from app.providers.petersburg.models import Student
from app.security import hash_token

PUPIL = 4021


def _deleted_at_commit(monkeypatch, model: type[Base], row_id: int) -> list[bool]:
    """Stage another request's deletion before this session flushes its change.

    Nothing is called between this handler's assignment and its commit, so
    the commit boundary is the last place to reproduce the competing request.
    """
    real = AsyncSession.commit
    done: list[bool] = []

    async def first_somebody_deletes(self: AsyncSession) -> None:
        if not done and any(isinstance(row, model) for row in self.dirty):
            done.append(True)
            async with SessionLocal() as other:
                await other.execute(delete(model).where(model.id == row_id))
                await real(other)
        await real(self)

    monkeypatch.setattr(AsyncSession, "commit", first_somebody_deletes)
    return done


async def _count(model: type[Base]) -> int:
    async with SessionLocal() as fresh:
        return await fresh.scalar(select(func.count()).select_from(model)) or 0


def _delete_before_parent_retention(monkeypatch, row_id: int) -> list[bool]:
    from app.services import gone

    real_lock = gone.lock_parent
    raced = []

    async def delete_after_child_lookup(session, target, target_id, **kwargs):
        if target is SchoolClass and not raced:
            async with SessionLocal() as other:
                await other.execute(delete(TimetableEntry).where(TimetableEntry.id == row_id))
                await other.commit()
            raced.append(True)
        return await real_lock(session, target, target_id, **kwargs)

    monkeypatch.setattr(gone, "lock_parent", delete_after_child_lookup)
    return raced


@pytest.mark.parametrize("entry", ["view", "root"])
async def test_the_bots_diary_says_a_session_signed_out_meanwhile_is_signed_out(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, entry
) -> None:
    """The bot's diary writes one thing, the child it shows; it corrects
    nothing (``handlers/diary._body``). A session «Выйти» took away between a
    press's read and that write is signed out — not «the diary answered
    something unexpected», over a session the middleware cannot commit."""
    school_class.diary_provider = diary_bot.PETERSBURG
    row = DiarySession(
        token_hash=hash_token("bot-654"),
        upstream_token=seal("upstream"),
        login="parent@example.com",
        telegram_id=42,
        class_id=school_class.id,
        student_id=PUPIL if entry == "view" else PUPIL - 1,
    )
    session.add(row)
    await session.commit()

    class Upstream:
        """The diary, minus the network: one account, two children."""

        def __init__(self, *_: Any) -> None:
            pass

        async def students(self) -> list[Student]:
            return [
                Student(id=1, first_name="Пётр", last_name="Иванов", education_id=PUPIL),
                Student(id=2, first_name="Аня", last_name="Иванова", education_id=PUPIL + 1),
            ]

    monkeypatch.setattr(diary_bot.diary_service, "DiaryService", Upstream)
    # Nothing is called between the child's change and its commit.
    raced = _deleted_at_commit(monkeypatch, DiarySession, row.id)
    callback = FakeCallback(message=FakeEditable())

    if entry == "root":
        await diary_bot.diary_root(callback, session, school_class, Role.VIEWER)
    else:
        await diary_bot.diary_view(
            callback,
            diary_bot.DiaryAction(view="day", offset=0, student=PUPIL + 1),
            session,
            school_class,
        )

    assert raced, "the delete never happened, so nothing was tested"
    # The middleware commits next: a session left failed raises
    # PendingRollbackError there, outside anything that answers.
    await session.commit()
    assert callback.answers == [(diary_bot.SIGNED_OUT, True)]
    assert await _count(DiarySession) == 0


async def test_recovered_scheme_press_refuses_a_class_deleted_before_the_write(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, FakeState
):
    from app.bot.handlers.manage import terms

    class_id = school_class.id
    real_change = terms.terms_manage.change_scheme
    deleted = []

    async def delete_before_change(*args, **kwargs):
        async with SessionLocal() as other:
            await other.execute(delete(SchoolClass).where(SchoolClass.id == class_id))
            await other.commit()
        deleted.append(True)
        return await real_change(*args, **kwargs)

    monkeypatch.setattr(terms.terms_manage, "change_scheme", delete_before_change)
    callback, state = FakeCallback(message=FakeEditable()), FakeState()
    await terms.terms_scheme(
        callback, SimpleNamespace(action="scheme", value="semester"), state,
        session, school_class, Role.ADMIN,
    )
    assert deleted == [True]
    assert callback.answers[-1] == ("Класс уже удалён. Откройте /start.", True)
    assert state.cleared
    await session.commit()
    assert await _count(AuditEntry) == 0


@pytest.mark.parametrize("operation", ["split", "merge"])
async def test_recovered_parity_press_rolls_back_partial_rows_without_redrawing(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, operation
):
    from app.bot.handlers import editor

    class_id = school_class.id
    victim = await session.scalar(select(TimetableEntry).where(
        TimetableEntry.class_id == class_id, TimetableEntry.weekday == 1,
        TimetableEntry.index == 2,
    ))
    if operation == "merge":
        victim.parity = WeekParity.ODD
        session.add(TimetableEntry(class_id=class_id, weekday=1, index=2,
                                   subject_name="Химия", parity=WeekParity.EVEN))
        await session.commit()
    victim_id = victim.id
    columns = (TimetableEntry.id, TimetableEntry.weekday, TimetableEntry.index,
               TimetableEntry.parity, TimetableEntry.subject_name)
    before = set((await session.execute(select(*columns).where(
        TimetableEntry.class_id == class_id,
    ))).all())
    expected = {row for row in before if row.id != victim_id}
    raced = _delete_before_parent_retention(monkeypatch, victim_id)
    callback = FakeCallback(message=FakeEditable())
    await editor.editor_parity(
        callback, SimpleNamespace(day=1, index=2, flags=0, arg=0, action=operation),
        session, school_class, Role.ADMIN,
    )
    assert raced == [True]
    assert callback.answers[-1] == ("Урока уже нет — откройте расписание заново.", True)
    assert not callback.message.replies
    await session.commit()
    assert await _count(AuditEntry) == 0
    async with SessionLocal() as fresh:
        remaining = set((await fresh.execute(select(*columns).where(
            TimetableEntry.class_id == class_id,
        ))).all())
        assert remaining == expected


async def test_recovered_picked_subject_answers_a_deleted_lesson_and_clears_its_form(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, FakeState
):
    from app.bot.handlers import editor

    subject = Subject(class_id=school_class.id, name="Физика")
    session.add(subject)
    await session.commit()
    subject_id = subject.id
    victim_id = await session.scalar(select(TimetableEntry.id).where(
        TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == 1,
        TimetableEntry.index == 2,
    ))
    raced = _delete_before_parent_retention(monkeypatch, victim_id)
    state = FakeState(data={"day": 1, "index": 2, "flags": 0, "mode": "edit"})
    callback = FakeCallback(message=FakeEditable())
    await editor.editor_pick_subject(
        callback, SimpleNamespace(subject=subject_id), state,
        session, school_class, Role.ADMIN,
    )
    assert raced == [True]
    assert state.cleared
    assert callback.message.replies == ["Урока уже нет — откройте расписание заново."]
    assert callback.answers
    await session.commit()
    assert await _count(AuditEntry) == 0
    assert await _count(TimetableEntry) == 2
    assert await _count(Subject) == 1
