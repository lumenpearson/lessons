"""One задание per subject per day, under a race.

Both shells promised this in their own docstrings and neither could keep it:
`homework` had an index on `(class_id, due_date)` and no unique constraint, and
both writers read then wrote. Two people saving «Алгебра» for Friday at the
same moment made two rows — the evening digest listed the subject twice, and
ticking one off left the other unticked.
"""

from __future__ import annotations

from datetime import date as Date

from sqlalchemy import func, select

from app.models import Homework, Subject
from app.services import homework as homework_service

FRIDAY = Date(2026, 9, 11)


async def test_saving_the_same_subject_twice_replaces_rather_than_duplicates(
    session, school_class
):
    first, created = await homework_service.upsert(
        session, school_class.id, FRIDAY, "Алгебра", "§5", actor=42
    )
    await session.commit()
    assert created is True

    second, created_again = await homework_service.upsert(
        session, school_class.id, FRIDAY, "Алгебра", "§6", actor=42
    )
    await session.commit()

    assert created_again is False
    assert second.id == first.id
    assert second.text == "§6"


async def test_the_spelling_the_class_uses_is_what_decides_sameness(session, school_class):
    """`app/schedule.py` matches a задание to its lesson by exact name, so a
    typed «алгебра» has to become the «Алгебра» the class already uses —
    otherwise it founds a second задание beside the first, which is the same
    duplicate by another route.

    The dictionary is what holds the spelling, so the test seeds it: a class
    that has ever pasted a timetable has these rows, and one that has not gets
    whatever case was typed, which is the documented behaviour of
    `subjects.spelling` and not this rule's business."""
    session.add(Subject(class_id=school_class.id, name="Алгебра"))
    await session.commit()

    await homework_service.upsert(session, school_class.id, FRIDAY, "Алгебра", "§5", actor=42)
    await session.commit()

    _row, created = await homework_service.upsert(
        session, school_class.id, FRIDAY, "алгебра", "§6", actor=42
    )
    await session.commit()

    assert created is False
    assert await session.scalar(select(func.count()).select_from(Homework)) == 1


async def test_a_stale_read_becomes_the_update_it_meant_to_be(
    session, school_class, monkeypatch
):
    """The real race, forced rather than hoped for.

    Two writers read the same empty day; the first commits; the second is still
    holding the answer it got before that and goes on to insert. There is no
    way to interleave that through `upsert`'s own surface on SQLite — its read
    happens inside it, and by the time the second call runs the row is
    committed and simply found. So the stale read is staged directly: `_find`
    is made to answer «nothing here» once, exactly as it truthfully did a
    moment earlier, while the row is already in the table.

    Without the unique constraint the insert then succeeds and the class has
    two заданий for one subject on one day, which is the defect. With it, the
    loser recovers into the update it meant to be.
    """
    await homework_service.upsert(session, school_class.id, FRIDAY, "Алгебра", "первый", actor=1)
    await session.commit()

    real_find = homework_service._find
    answered_stale = False

    async def stale_once(*args, **kwargs):
        nonlocal answered_stale
        if not answered_stale:
            answered_stale = True
            return None
        return await real_find(*args, **kwargs)

    monkeypatch.setattr(homework_service, "_find", stale_once)

    row, created = await homework_service.upsert(
        session, school_class.id, FRIDAY, "Алгебра", "второй", actor=2
    )
    await session.commit()

    assert answered_stale, "the stale read never happened, so nothing was tested"
    assert created is False, "the loser must update, not insert a twin"
    assert await session.scalar(select(func.count()).select_from(Homework)) == 1
    assert row.text == "второй"
