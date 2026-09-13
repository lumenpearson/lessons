"""One-lesson edits to the weekly template.

Every test here is really about ``uq_timetable_cell``. The interesting part of
adding, deleting and moving a lesson is not the row that changes — it is the
rows that have to renumber around it without two of them ever holding the same
number, which both Postgres and SQLite refuse mid-statement rather than at the
end. The park-and-return in ``_shift`` is the whole reason this module exists,
so it is what gets the coverage.
"""

from __future__ import annotations

import pytest
from sqlalchemy import select

from app.models import TimetableEntry, WeekParity
from app.services import timetable_edit as edit


async def _day(session, class_id: int, weekday: int = 1) -> list[tuple[int, str, str]]:
    """The day as (index, subject, parity), in the order a reader sees it."""
    rows = await session.scalars(
        select(TimetableEntry)
        .where(TimetableEntry.class_id == class_id, TimetableEntry.weekday == weekday)
        .order_by(TimetableEntry.index, TimetableEntry.parity)
    )
    return [(row.index, row.subject_name, row.parity.value) for row in rows]


async def test_a_lesson_appends_after_the_last_one(session, school_class):
    index = await edit.add_lesson(session, school_class.id, 1, subject="Химия", room="118")
    await session.commit()

    assert index == 4
    assert await _day(session, school_class.id) == [
        (1, "Алгебра", "any"),
        (2, "Физика", "any"),
        (3, "История", "any"),
        (4, "Химия", "any"),
    ]


async def test_inserting_in_the_middle_pushes_the_rest_down(session, school_class):
    """The case the naive ``index + 1`` UPDATE loses.

    Three rows shift by one, and the first of them lands on the number the
    second still holds. Statement-order-dependent duplicate key if the park is
    ever removed.
    """
    index = await edit.add_lesson(session, school_class.id, 1, subject="Геометрия", at=2)
    await session.commit()

    assert index == 2
    assert await _day(session, school_class.id) == [
        (1, "Алгебра", "any"),
        (2, "Геометрия", "any"),
        (3, "Физика", "any"),
        (4, "История", "any"),
    ]


async def test_deleting_closes_the_gap_rather_than_leaving_a_hole(session, school_class):
    """A day numbered 1, 2, 4 reads as a lost lesson, and hands lesson 4 the
    fourth bell when it is now the third thing that happens."""
    removed = await edit.remove_lesson(session, school_class.id, 1, 2)
    await session.commit()

    assert removed == 1
    assert await _day(session, school_class.id) == [
        (1, "Алгебра", "any"),
        (2, "История", "any"),
    ]


async def test_a_deleted_slot_takes_both_of_its_parities_with_it(session, school_class):
    await edit.split_parity(session, school_class.id, 1, 2)
    await session.commit()

    removed = await edit.remove_lesson(session, school_class.id, 1, 2)
    await session.commit()

    assert removed == 2
    assert await _day(session, school_class.id) == [
        (1, "Алгебра", "any"),
        (2, "История", "any"),
    ]


@pytest.mark.parametrize(
    ("index", "up", "expected"),
    [
        (2, True, ["Физика", "Алгебра", "История"]),
        (2, False, ["Алгебра", "История", "Физика"]),
    ],
)
async def test_a_lesson_swaps_with_its_neighbour(session, school_class, index, up, expected):
    landed = await edit.move_lesson(session, school_class.id, 1, index, up=up)
    await session.commit()

    assert landed == (1 if up else 3)
    assert [subject for _, subject, _ in await _day(session, school_class.id)] == expected


async def test_moving_past_the_end_is_refused_rather_than_silently_ignored(
    session, school_class
):
    """The caller needs the difference to answer «уже первый»: a button that
    redraws an unchanged day is read as a broken button."""
    assert await edit.move_lesson(session, school_class.id, 1, 1, up=True) is None
    assert await edit.move_lesson(session, school_class.id, 1, 3, up=False) is None
    assert await edit.move_lesson(session, school_class.id, 1, 9, up=True) is None


async def test_moving_a_slot_carries_both_weeks_with_it(session, school_class):
    """числитель and знаменатель are one slot.

    Move them apart and one week's third lesson becomes the other week's
    second — which nobody asked for — and the bells, keyed on the index alone,
    are then right for one week and wrong for the other.
    """
    await edit.split_parity(session, school_class.id, 1, 3)
    await edit.edit_lesson(
        session,
        school_class.id,
        1,
        3,
        WeekParity.EVEN,
        subject="Обществознание",
        room=None,
        teacher=None,
    )
    await session.commit()

    await edit.move_lesson(session, school_class.id, 1, 3, up=True)
    await session.commit()

    # The original row stays числитель and the copy became знаменатель, so
    # «Обществознание» — the half that was edited — is the even one.
    assert await _day(session, school_class.id) == [
        (1, "Алгебра", "any"),
        (2, "Обществознание", "even"),
        (2, "История", "odd"),
        (3, "Физика", "any"),
    ]


async def test_splitting_copies_the_lesson_into_both_weeks(session, school_class):
    """An empty знаменатель would be a hole the day view has to render every
    second week, and the next edit is changing one half anyway."""
    assert await edit.split_parity(session, school_class.id, 1, 1) is True
    await session.commit()

    assert (await _day(session, school_class.id))[:2] == [
        (1, "Алгебра", "even"),
        (1, "Алгебра", "odd"),
    ]
    # Already split — the second press must not add a third row.
    assert await edit.split_parity(session, school_class.id, 1, 1) is False


async def test_merging_keeps_one_week_and_frees_the_slot(session, school_class):
    await edit.split_parity(session, school_class.id, 1, 1)
    await edit.edit_lesson(
        session,
        school_class.id,
        1,
        1,
        WeekParity.EVEN,
        subject="Геометрия",
        room=None,
        teacher=None,
    )
    await session.commit()

    assert await edit.merge_parity(session, school_class.id, 1, 1, WeekParity.EVEN) is True
    await session.commit()

    assert (await _day(session, school_class.id))[0] == (1, "Геометрия", "any")


async def test_a_day_cannot_grow_past_the_sanity_limit(session, school_class):
    for number in range(edit.MAX_INDEX):
        await edit.add_lesson(session, school_class.id, 2, subject=f"Урок {number}")
    await session.commit()

    assert await edit.add_lesson(session, school_class.id, 2, subject="Ещё один") is None


async def test_the_pager_counts_a_split_slot_once(session, school_class):
    """Two parities are one lesson on the page — «Пн 3», not «Пн 4»."""
    await edit.split_parity(session, school_class.id, 1, 1)
    await session.commit()

    assert await edit.day_counts(session, school_class.id) == {1: 3}
