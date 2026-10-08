"""The phone's own writes are committed by whoever called them, never inside the service.

``tasks.add_task``, ``set_done``, ``delete_task`` and ``toggle_homework_done``,
``calendar.ensure_calendar_token`` and ``linking.issue_link_code`` used to
commit inside themselves, which v2 cannot use: a v2 handler never commits,
``invoke`` does, once (``docs/specs/2026-10-05-server-v2-design.md``, decision
4). They stop, and every caller commits after the call: v1's routers, as the
manage endpoints do, and the bot's handlers before they tell Telegram, because
the middleware commits only after the handler and rolls back when a reply
fails. The two retries that relied on a failing commit, a link code drawn
twice and a racing tick, concede inside a savepoint instead.

On SQLite, which these tests run on, a savepoint that opens the transaction
commits when it is released (#373). A test of a write behind a savepoint makes
a write of its own first, as a caller does, so that what it reads is the
function and not the driver.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import date
from types import SimpleNamespace
from typing import Any

from sqlalchemy import func, select, update

from app.bot.handlers.content.homework import homework_toggle
from app.bot.handlers.manage.calendar_feed import cmd_calendar
from app.bot.handlers.tasks import cmd_task, task_delete, task_toggle_done
from app.config import get_settings
from app.db import SessionLocal
from app.models import (
    AuditEntry,
    DeviceToken,
    Homework,
    HomeworkDone,
    PersonalTask,
    Role,
    SchoolClass,
)
from app.security import hash_token
from app.services import audit, calendar, linking, tasks

MONDAY = date(2026, 9, 7)


async def _committed(statement: Any) -> Any:
    """What a session of its own reads: only what is committed."""
    async with SessionLocal() as fresh:
        return await fresh.scalar(statement)


async def _a_write_of_the_caller_s_own(session, school_class) -> None:
    """Open the transaction with a write, as a caller's own would: SQLite
    commits a savepoint that opens the transaction when it is released (#373)."""
    await audit.record(session, school_class.id, 42, "test.earlier", "раньше в той же транзакции")
    await session.flush()


async def _homework(session, school_class) -> Homework:
    item = Homework(class_id=school_class.id, due_date=MONDAY, subject_name="Алгебра", text="№ 1")
    session.add(item)
    await session.commit()
    return item


# ---- the services ------------------------------------------------------------


async def test_a_task_s_writes_are_the_caller_s_to_commit(session, school_class) -> None:
    # Read before the rollbacks: each expires every loaded row, and an expired
    # attribute cannot be reloaded from an async session by plain access.
    class_id = school_class.id
    added = await tasks.add_task(session, class_id, 42, "Купить тетрадь")
    # Flushed, so that the caller can render it: the id and the server's stamp.
    assert added.id is not None and added.created_at is not None
    await session.rollback()
    assert await _committed(select(func.count()).select_from(PersonalTask)) == 0

    kept = await tasks.add_task(session, class_id, 42, "Сдать реферат")
    await session.commit()
    kept_id = kept.id
    await tasks.set_done(session, kept, True)
    await session.rollback()
    assert await _committed(select(PersonalTask.done).where(PersonalTask.id == kept_id)) is False

    await session.refresh(kept)
    await tasks.delete_task(session, kept)
    await session.rollback()
    assert await _committed(select(PersonalTask.id).where(PersonalTask.id == kept_id)) == kept_id


async def test_a_tick_is_the_caller_s_to_commit(session, school_class) -> None:
    homework = await _homework(session, school_class)
    ticks = select(func.count()).select_from(HomeworkDone)
    await _a_write_of_the_caller_s_own(session, school_class)
    assert await tasks.toggle_homework_done(session, homework, 42) is True
    await session.rollback()
    assert await _committed(ticks) == 0
    assert await _committed(select(func.count()).select_from(AuditEntry)) == 0

    await session.refresh(homework)
    session.add(HomeworkDone(homework_id=homework.id, telegram_id=42))
    await session.commit()
    assert await tasks.toggle_homework_done(session, homework, 42) is False
    await session.rollback()
    assert await _committed(ticks) == 1


async def test_the_feed_secret_is_the_caller_s_to_commit(session, school_class) -> None:
    stored = select(SchoolClass.calendar_token).where(SchoolClass.id == school_class.id)
    assert await calendar.ensure_calendar_token(session, school_class)
    await session.rollback()
    assert await _committed(stored) is None

    await session.refresh(school_class)
    kept = await calendar.ensure_calendar_token(session, school_class)
    await session.commit()
    assert await _committed(stored) == kept


async def test_a_link_code_is_the_caller_s_to_commit(session, school_class) -> None:
    device = DeviceToken(token_hash=hash_token("the-caller-commits"), class_id=school_class.id)
    session.add(device)
    await session.commit()
    device_id = device.id
    await _a_write_of_the_caller_s_own(session, school_class)
    code = await linking.issue_link_code(session, device)
    assert len(code) == linking.LINK_CODE_LENGTH
    await session.rollback()
    stored = select(DeviceToken.link_code).where(DeviceToken.id == device_id)
    assert await _committed(stored) is None
    assert await _committed(select(func.count()).select_from(AuditEntry)) == 0


async def test_a_link_code_drawn_twice_is_drawn_again_without_a_commit(
    session, school_class, monkeypatch
) -> None:
    """Two invocations draw the same code between the check and the write. The
    unique index catches the second inside its savepoint, it draws again, and
    nothing is committed: the old retry rolled the caller's whole transaction
    back and committed the next draw itself."""
    winner = DeviceToken(token_hash=hash_token("winner"), class_id=school_class.id)
    loser = DeviceToken(token_hash=hash_token("loser"), class_id=school_class.id)
    session.add_all([winner, loser])
    await session.commit()
    winner_id, loser_id = winner.id, loser.id
    draws = iter(["AAAAAA", "BBBBBB"])
    monkeypatch.setattr(linking, "new_join_code", lambda length: next(draws))

    checks: list[object] = []
    check = session.scalar

    async def checked_then_taken(statement, *args, **kwargs):
        found = await check(statement, *args, **kwargs)
        checks.append(found)
        if len(checks) == 1:
            # The other invocation, between this one's check and its write.
            async with SessionLocal() as other:
                await other.execute(
                    update(DeviceToken)
                    .where(DeviceToken.id == winner_id)
                    .values(link_code="AAAAAA")
                )
                await other.commit()
        return found

    commits: list[str] = []
    commit = session.commit

    async def counted() -> None:
        commits.append("commit")
        await commit()

    monkeypatch.setattr(session, "scalar", checked_then_taken)
    monkeypatch.setattr(session, "commit", counted)

    assert await linking.issue_link_code(session, loser) == "BBBBBB"
    assert checks == [None, None]
    assert commits == []
    await session.commit()
    codes = select(DeviceToken.id, DeviceToken.link_code).order_by(DeviceToken.id)
    async with SessionLocal() as fresh:
        assert list(await fresh.execute(codes)) == [(winner_id, "AAAAAA"), (loser_id, "BBBBBB")]


async def test_a_link_code_collision_keeps_the_caller_s_earlier_write(
    session, school_class, monkeypatch
) -> None:
    """The wrong fix for the retry above - ``commit`` turned into ``flush``,
    with the except-clause's full ``session.rollback()`` left standing - rolls
    back whatever the caller wrote earlier in the same transaction along with
    the collision, because a whole-session rollback cannot tell the caller's
    write from the savepoint's. Forcing the collision against a code already
    committed, by making the existence check miss it once, needs no second
    session: a second one here would just wait on this session's own write
    lock, on SQLite, until the test's own timeout.

    The caller's write has to be the session's first. On SQLite a savepoint
    that opens the transaction commits on release regardless of what the
    caller does afterwards (#373), so without that ordering this test would
    pass on the wrong fix too, for the driver's reason rather than the
    function's."""
    taken = DeviceToken(
        token_hash=hash_token("taken"), class_id=school_class.id, link_code="AAAAAA"
    )
    device = DeviceToken(token_hash=hash_token("mine"), class_id=school_class.id)
    session.add_all([taken, device])
    await session.commit()
    draws = iter(["AAAAAA", "BBBBBB"])
    monkeypatch.setattr(linking, "new_join_code", lambda length: next(draws))

    await _a_write_of_the_caller_s_own(session, school_class)

    check = session.scalar
    seen: list[object] = []

    async def miss_the_first_check(statement, *args, **kwargs):
        found = await check(statement, *args, **kwargs)
        seen.append(found)
        # The first check is made to miss the code that is already taken, so
        # this one session meets its own collision without a second to cause it.
        return None if len(seen) == 1 else found

    monkeypatch.setattr(session, "scalar", miss_the_first_check)

    assert await linking.issue_link_code(session, device) == "BBBBBB"
    await session.commit()
    assert await _committed(select(func.count()).select_from(AuditEntry)) == 1


# ---- the bot: committed before Telegram is told -----------------------------


@dataclass
class _Witness:
    """A chat the bot speaks in, as a message or as a press and its message.
    Whenever the bot says anything, it reads the database from a session of
    its own, so what it saw is what was committed by then."""

    looks: Callable[[], Awaitable[Any]]
    user_id: int = 42
    saw: list[Any] = field(default_factory=list)

    @property
    def from_user(self) -> SimpleNamespace:
        return SimpleNamespace(id=self.user_id, username="tester", full_name="Тестер")

    @property
    def message(self) -> _Witness:
        return self

    async def answer(self, text: str | None = None, *args: Any, **kwargs: Any) -> None:
        self.saw.append(await self.looks())

    edit_text = answer


async def test_a_task_typed_into_the_bot_is_committed_before_it_is_confirmed(
    session, school_class, FakeState
) -> None:
    chat = _Witness(lambda: _committed(select(PersonalTask.title)))
    await cmd_task(
        chat,
        SimpleNamespace(args="Купить тетрадь"),
        FakeState(),
        session,
        school_class,
        Role.VIEWER,
    )
    assert chat.saw == ["Купить тетрадь"]


async def test_a_task_ticked_in_the_bot_is_committed_before_the_list_is_redrawn(
    session, school_class
) -> None:
    task = PersonalTask(class_id=school_class.id, telegram_id=42, title="Сдать реферат")
    session.add(task)
    await session.commit()
    done = select(PersonalTask.done).where(PersonalTask.id == task.id)
    press = _Witness(lambda: _committed(done))
    await task_toggle_done(
        press, SimpleNamespace(value=str(task.id), show_done=0), session, school_class, Role.VIEWER
    )
    assert press.saw == [True, True]


async def test_a_task_deleted_in_the_bot_is_committed_before_the_list_is_redrawn(
    session, school_class
) -> None:
    task = PersonalTask(class_id=school_class.id, telegram_id=42, title="Удалить меня")
    session.add(task)
    await session.commit()
    press = _Witness(lambda: _committed(select(func.count()).select_from(PersonalTask)))
    await task_delete(
        press, SimpleNamespace(value=str(task.id), show_done=0), session, school_class, Role.VIEWER
    )
    assert press.saw == [0, 0]


async def test_a_tick_taken_off_in_the_bot_is_committed_before_the_list_is_redrawn(
    session, school_class
) -> None:
    """The tick taken off, not put on: putting it on goes through a savepoint
    that opens the transaction, which SQLite commits on release whoever else
    does (#373), so only taking it off can show the handler's own commit."""
    homework = await _homework(session, school_class)
    session.add(HomeworkDone(homework_id=homework.id, telegram_id=42))
    await session.commit()
    press = _Witness(lambda: _committed(select(func.count()).select_from(HomeworkDone)))
    await homework_toggle(
        press,
        SimpleNamespace(action="toggle", value=str(homework.id)),
        session,
        school_class,
        Role.VIEWER,
    )
    assert press.saw == [0, 0]


async def test_the_feed_secret_the_bot_shows_is_committed_before_it_is_shown(
    session, school_class, FakeState, monkeypatch
) -> None:
    monkeypatch.setattr(get_settings(), "public_base_url", "https://lessons.example.com")
    stored = select(SchoolClass.calendar_token).where(SchoolClass.id == school_class.id)
    chat = _Witness(lambda: _committed(stored))
    await cmd_calendar(chat, FakeState(), session, school_class, Role.VIEWER)
    assert chat.saw == [school_class.calendar_token]
    assert chat.saw[0]
