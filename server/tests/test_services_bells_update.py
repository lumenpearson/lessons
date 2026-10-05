"""The bells' patch, in ``services/``: what v1's two writes to a schedule held.

v1's ``PATCH /manage/bells/{id}`` (the name and the default) and its ``PUT
…/periods`` (the rows) kept their rules in the router; v2 has one masked
``UpdateBellSchedule``. So the patch moved to ``bells_service.update`` before
v2's handler was written, with v1 calling it
(``docs/specs/2026-10-05-server-v2-design.md``, decision 2), and
``test_api_manage.py``, untouched, is the proof that v1's answers did not
move. These hold what one patch with all three changes adds: the order, the
lessons that stopped ringing counted once, and the rows read back before the
default moves.
"""

from __future__ import annotations

from datetime import time

import pytest
from sqlalchemy import select

from app import wording
from app.models import AuditEntry, BellSchedule
from app.services.manage import bells as bells_service

TWO_ROWS = [(1, time(9, 0), time(9, 40)), (2, time(9, 50), time(10, 30))]


async def _actions(session) -> list[str]:
    return list(await session.scalars(select(AuditEntry.action).order_by(AuditEntry.id)))


async def _summaries(session, action: str) -> list[str]:
    return list(
        await session.scalars(
            select(AuditEntry.summary).where(AuditEntry.action == action).order_by(AuditEntry.id)
        )
    )


async def _schedule(session, school_class, name: str, rows) -> BellSchedule:
    schedule = await bells_service.create(session, school_class, 2003, name, rows)
    await session.commit()
    await session.refresh(schedule, ["periods"])
    return schedule


async def test_a_bells_update_renames_then_rewrites_the_rows_then_moves_the_default(
    session, school_class
) -> None:
    """The fixture's Monday carries lessons 1 to 3, and its default rings 1 to 7.
    «Сб» is renamed, given two rows and made the default in one patch, its
    keys in another order than the one the patch applies them in. Lesson 3
    stops ringing, and it is counted once, by the move, against what the class
    rang before the patch."""
    saturday = await _schedule(session, school_class, "Сб", [(1, time(9, 0), time(9, 40))])
    silenced = await bells_service.update(
        session,
        school_class,
        2003,
        saturday,
        {"is_default": True, "periods": TWO_ROWS, "name": "Суббота"},
    )
    assert silenced == [(1, 3)]
    assert school_class.bell_schedule_id == saturday.id
    await session.commit()
    assert await _actions(session) == [
        "bells.create",
        "bells.rename",
        "bells.edit",
        "bells.default",
    ]
    assert await _summaries(session, "bells.edit") == ["звонки «Суббота»: 2 уроков"]
    assert await _summaries(session, "bells.default") == [
        "основное расписание звонков: «Суббота», перестали звонить уроков: 1"
    ]


async def test_a_bells_update_that_unsets_the_default_is_refused_before_anything_is_written(
    session, school_class
) -> None:
    default = await session.get(BellSchedule, school_class.bell_schedule_id)
    with pytest.raises(bells_service.DefaultRequired):
        await bells_service.update(
            session, school_class, 2003, default, {"name": "Другое", "is_default": False}
        )
    assert default.name == "Обычное"
    assert [row for row in session.new if isinstance(row, AuditEntry)] == []


async def test_an_empty_schedule_given_rows_can_become_the_default_in_one_update(
    session, school_class
) -> None:
    """The rows are read back before the default moves. Without that, the move
    would read the collection the bulk delete left behind, still empty, and
    refuse the schedule as one that rings nothing."""
    empty = await _schedule(session, school_class, "Пустое", [])
    assert empty.periods == []
    rows = [*TWO_ROWS, (3, time(10, 40), time(11, 20))]
    silenced = await bells_service.update(
        session, school_class, 2003, empty, {"periods": rows, "is_default": True}
    )
    # Monday's lessons 1 to 3 all ring under the new rows: nothing stopped.
    assert silenced == []
    assert school_class.bell_schedule_id == empty.id
    assert [period.index for period in empty.periods] == [1, 2, 3]


def test_the_bells_sentences_are_v1_s() -> None:
    assert wording.UNKNOWN_BELL_SCHEDULE_DETAIL == "Unknown bell schedule"
    assert wording.BELL_DEFAULT_REQUIRED_DETAIL == "make another schedule the default instead"
    assert wording.EMPTY_BELL_SCHEDULE_DETAIL == "в этом расписании звонков нет ни одного урока"
    assert wording.BELL_SCHEDULE_IS_DEFAULT_DETAIL == (
        "this is the class default; make another one the default first"
    )
    assert wording.bell_schedule_in_use_detail(2) == "2 special day(s) still use this schedule"
