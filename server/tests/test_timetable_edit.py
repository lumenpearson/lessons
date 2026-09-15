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


# ---- the bells are the ceiling --------------------------------------------


async def test_a_lesson_past_the_last_bell_is_refused(session, school_class):
    """The resolver builds a day out of the bell rows, so a lesson numbered
    past the last one is stored and then drawn nowhere.

    It used to be accepted: the editor showed it, `MAX_INDEX` was 20 and had
    nothing to do with the bells, and no phone, widget, digest or calendar feed
    ever saw the row. Nothing said so, because a lesson missing from a day looks
    exactly like a lesson that was never added.
    """
    rings = await edit.rings(session, school_class.id)
    assert rings == 7, "the fixture class rings the seven default bells"

    while await edit.add_lesson(session, school_class.id, 2, subject="Физика") is not None:
        pass
    await session.commit()

    day = await _day(session, school_class.id, 2)
    assert [index for index, _, _ in day] == list(range(1, rings + 1))
    assert await edit.add_lesson(session, school_class.id, 2, subject="Лишний") is None


async def test_an_insert_that_would_push_the_last_lesson_past_the_bells_is_refused(
    session, school_class
):
    """Inserting pushes everything below it down, so a full day cannot take one
    in the middle either — otherwise adding a second lesson quietly costs the
    seventh, which is the same invisible row by another route."""
    while await edit.add_lesson(session, school_class.id, 3, subject="Физика") is not None:
        pass
    await session.commit()
    before = await _day(session, school_class.id, 3)

    assert await edit.add_lesson(session, school_class.id, 3, subject="Втиснутый", at=2) is None
    assert await _day(session, school_class.id, 3) == before


async def test_a_class_with_no_bells_at_all_still_takes_lessons(session, school_class):
    """`MAX_INDEX` is the backstop. A class whose schedule is missing is a class
    being set up, and refusing every lesson until the bells exist would be a
    worse answer than the one this is fixing."""
    school_class.bell_schedule_id = None
    await session.commit()

    assert await edit.rings(session, school_class.id) == 0
    assert await edit.add_lesson(session, school_class.id, 4, subject="Физика") == 1


async def test_an_import_skips_lessons_it_has_no_bell_for_and_says_which(
    session, school_class
):
    """The paste path had the same hole, and it counted the row in «уроков
    добавлено» on the way — so the number an admin read back was the number
    they pasted, and the lesson was nowhere."""
    from app.services import structure

    rows = [(index, f"Урок {index}", None, None, WeekParity.ANY) for index in range(1, 10)]
    total, _schedule, unrung = await structure.apply_timetable(
        session, school_class, {5: rows}, []
    )
    await session.commit()

    assert total == 7, "the class rings seven bells, so seven lessons landed"
    assert unrung == [8, 9]
    assert [index for index, _, _ in await _day(session, school_class.id, 5)] == list(range(1, 8))


async def test_an_import_that_brings_its_own_bells_may_bring_the_lessons_too(
    session, school_class
):
    """The ceiling is what the class will ring *after* the import, not before.

    A «== Звонки ==» block with nine rows makes a ninth lesson legitimate in the
    same paste, and checking against the old schedule would reject the very line
    the paste makes valid.
    """
    from datetime import time

    from app.services import structure

    rows = [(index, f"Урок {index}", None, None, WeekParity.ANY) for index in range(1, 10)]
    bells = [(index, time(8, 0), time(8, 45)) for index in range(1, 10)]
    total, _schedule, unrung = await structure.apply_timetable(
        session, school_class, {5: rows}, bells
    )
    await session.commit()

    assert total == 9 and unrung == []
