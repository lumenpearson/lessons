"""The diary's corrections in ``services/``: written by whoever calls, under one set of rules.

``services/diary_corrections`` committed inside itself, which v2 cannot use:
``BatchUpdateCorrections`` lands whole or not at all, and only ``invoke``
commits, once (``docs/specs/2026-10-05-server-v2-design.md``, decision 4). Its
writes now leave the commit to their caller — v1's routes commit after the
call — and the one retry that relied on a failing commit, two writers racing
for one field, concedes inside a savepoint instead. ``test_diary_api.py`` and
``test_diary_corrections_per_child.py``, untouched, are the proof that v1's
answers did not move: each of their writes is read back by a request of its
own, which sees only what was committed.
"""

from __future__ import annotations

from typing import Any

from sqlalchemy import func, select

from app.db import SessionLocal
from app.models import DiaryOverride
from app.services import diary_corrections as service

SCOPE = "CHILD:petersburg"
TARGET = "lesson:2026-09-15:n1:Алгебра"


async def _committed(statement: Any) -> Any:
    """What a session of its own reads: only what is committed."""
    async with SessionLocal() as fresh:
        return await fresh.scalar(statement)


async def test_a_correction_written_or_replaced_is_the_caller_s_to_commit(session) -> None:
    written = await service.put_override(session, SCOPE, 4021, TARGET, "room", "204", "12")
    # Flushed and read back, so that the caller can answer with it.
    assert written.id is not None and written.updated_at is not None
    await session.rollback()
    assert await _committed(select(func.count()).select_from(DiaryOverride)) == 0

    kept = await service.put_override(session, SCOPE, 4021, TARGET, "room", "204", "12")
    await session.commit()
    kept_id = kept.id
    replaced = await service.put_override(session, SCOPE, 4021, TARGET, "room", "301", None)
    assert (replaced.id, replaced.value, replaced.original) == (kept_id, "301", None)
    await session.rollback()
    assert await _committed(select(DiaryOverride.value)) == "204"


async def test_a_reset_and_a_clear_are_the_caller_s_to_commit(session) -> None:
    for field, value in (("room", "204"), ("teacher", "Иванова И. И.")):
        await service.put_override(session, SCOPE, 4021, TARGET, field, value, None)
    await session.commit()
    stored = select(func.count()).select_from(DiaryOverride)

    assert await service.drop_override(session, SCOPE, 4021, TARGET, "room") is True
    await session.rollback()
    assert await _committed(stored) == 2

    assert await service.drop_overrides(session, SCOPE, 4021) == 2
    await session.rollback()
    assert await _committed(stored) == 2

    assert await service.drop_overrides(session, SCOPE, 4021) == 2
    await session.commit()
    assert await _committed(stored) == 0


async def test_two_writers_racing_for_one_field_land_on_one_row_and_commit_nothing(
    session, monkeypatch
) -> None:
    """Both parents correct one room in the same instant. The other's row lands
    between this write's check and its insert; the unique constraint catches
    the insert inside its savepoint, this write becomes the update it would
    have been a moment later, and nothing is committed until the caller
    commits: the old retry rolled the caller's whole transaction back, and
    committed twice itself."""
    checks: list[object] = []
    check = session.scalar

    async def checked_then_taken(statement, *args, **kwargs):
        found = await check(statement, *args, **kwargs)
        checks.append(found)
        if len(checks) == 1:
            # The other parent's write, between this one's check and its insert.
            async with SessionLocal() as other:
                other.add(
                    DiaryOverride(
                        login=SCOPE,
                        student_id=4021,
                        target=TARGET,
                        field="room",
                        value="204",
                        original="12",
                    )
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

    written = await service.put_override(session, SCOPE, 4021, TARGET, "room", "301", "12")
    assert written.value == "301"
    assert checks[0] is None and checks[1] is not None
    assert commits == []
    assert await _committed(select(DiaryOverride.value)) == "204"
    await session.commit()
    async with SessionLocal() as fresh:
        assert list(await fresh.scalars(select(DiaryOverride.value))) == ["301"]
