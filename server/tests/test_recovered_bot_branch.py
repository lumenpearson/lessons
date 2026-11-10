"""A missing diary race recovered from e805dad0 while reconciling branches (#725)."""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm.exc import StaleDataError

from app.bot.handlers import diary as diary_bot
from app.crypto import seal
from app.db import Base, SessionLocal
from app.models import AuditEntry, DiarySession, Role, SchoolClass, Subject
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


async def test_the_bots_diary_says_a_session_signed_out_meanwhile_is_signed_out(
    session, school_class, monkeypatch, FakeCallback, FakeEditable
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
        student_id=PUPIL,
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
    assert (diary_bot.SIGNED_OUT, True) in callback.answers
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
async def test_recovered_parity_press_handles_a_service_stale_row_without_redrawing(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, operation
):
    from app.bot.handlers import editor

    called = []

    async def stale_row(*args, **kwargs):
        called.append(True)
        raise StaleDataError("synthetic slot deleted before the write")

    monkeypatch.setattr(editor.timetable_edit, f"{operation}_parity", stale_row)
    callback = FakeCallback(message=FakeEditable())
    await editor.editor_parity(
        callback, SimpleNamespace(day=1, index=2, flags=0, arg=0, action=operation),
        session, school_class, Role.ADMIN,
    )
    assert called == [True]
    assert callback.answers[-1] == ("Урока уже нет — откройте расписание заново.", True)
    assert not callback.message.replies
    await session.commit()
    assert await _count(AuditEntry) == 0


async def test_recovered_picked_subject_answers_a_stale_lesson_and_clears_its_form(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, FakeState
):
    from app.bot.handlers import editor

    subject = Subject(class_id=school_class.id, name="Физика")
    session.add(subject)
    await session.commit()
    subject_id = subject.id
    called = []

    async def stale_row(*args, **kwargs):
        called.append(True)
        raise StaleDataError("synthetic edited lesson deleted")

    monkeypatch.setattr(editor.timetable_edit, "edit_lesson", stale_row)
    state = FakeState(data={"day": 1, "index": 2, "flags": 0, "mode": "edit"})
    callback = FakeCallback(message=FakeEditable())
    await editor.editor_pick_subject(
        callback, SimpleNamespace(subject=subject_id), state,
        session, school_class, Role.ADMIN,
    )
    assert called == [True]
    assert state.cleared
    assert callback.message.replies == ["Урока уже нет — откройте расписание заново."]
    assert callback.answers
    await session.commit()
    assert await _count(AuditEntry) == 0
