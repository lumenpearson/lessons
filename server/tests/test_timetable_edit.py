"""One-lesson edits to the weekly template.

Every test here is really about ``uq_timetable_cell``. The interesting part of
adding, deleting and moving a lesson is not the row that changes — it is the
rows that have to renumber around it without two of them ever holding the same
number, which both Postgres and SQLite refuse mid-statement rather than at the
end. The park-and-return in ``_shift`` is the whole reason this module exists,
so it is what gets the coverage.
"""

from __future__ import annotations

from datetime import date

import pytest
from sqlalchemy import select

from app.models import Term, TermKind, TimetableEntry, WeekParity
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
    """The numerator and the denominator are one slot.

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

    # The original row stays the numerator and the copy became the denominator, so
    # «Обществознание» — the half that was edited — is the even one.
    assert await _day(session, school_class.id) == [
        (1, "Алгебра", "any"),
        (2, "Обществознание", "even"),
        (2, "История", "odd"),
        (3, "Физика", "any"),
    ]


async def test_splitting_copies_the_lesson_into_both_weeks(session, school_class):
    """An empty denominator half would be a hole the day view has to render every
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
    result = await structure.apply_timetable(
        session, school_class, {5: rows}, []
    )
    total, unrung = result.written, result.unrung
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
    result = await structure.apply_timetable(
        session, school_class, {5: rows}, bells
    )
    total, unrung = result.written, result.unrung
    await session.commit()

    assert total == 9 and unrung == []


async def test_a_bell_schedule_with_a_gap_is_read_by_its_numbers_not_its_count(
    session, school_class
):
    """"How many bells" and "which lessons they ring" are different questions.

    A «== Звонки ==» block may leave a hole — a school that numbers its lessons
    1, 2, 4 because the third slot is a shift change — and the resolver keys a
    lesson's times on the bell row of *the same number*. Counting the rows
    answered both questions with «three», which got both halves wrong at once:
    «4. Химия», which has a bell, was dropped and reported as having none, and
    a third lesson, which has none, would have been written and then drawn
    nowhere.
    """
    from datetime import time

    from app.services import structure

    bells = [(1, time(8, 0), time(8, 45)), (2, time(9, 0), time(9, 45)),
             (4, time(11, 0), time(11, 45))]
    rows = [
        (1, "Алгебра", None, None, WeekParity.ANY),
        (2, "Физика", None, None, WeekParity.ANY),
        (4, "Химия", None, None, WeekParity.ANY),
    ]
    result = await structure.apply_timetable(
        session, school_class, {5: rows}, bells
    )
    total, unrung = result.written, result.unrung
    await session.commit()

    assert unrung == [], "bell 4 is right there in the same paste"
    assert total == 3
    assert [index for index, _, _ in await _day(session, school_class.id, 5)] == [1, 2, 4]


async def test_an_import_refuses_a_lesson_whose_number_falls_in_the_gap(
    session, school_class
):
    """The other half of the same rule: a number below the last bell is not a
    number that has one."""
    from datetime import time

    from app.services import structure

    bells = [(1, time(8, 0), time(8, 45)), (2, time(9, 0), time(9, 45)),
             (4, time(11, 0), time(11, 45))]
    rows = [(index, f"Урок {index}", None, None, WeekParity.ANY) for index in (1, 2, 3, 4)]
    result = await structure.apply_timetable(
        session, school_class, {5: rows}, bells
    )
    total, unrung = result.written, result.unrung
    await session.commit()

    assert unrung == [3]
    assert total == 3
    assert [index for index, _, _ in await _day(session, school_class.id, 5)] == [1, 2, 4]


async def test_deleting_a_lesson_does_not_slide_another_onto_a_number_with_no_bell(
    session, school_class
):
    """`add_lesson` refuses an insert that would push a lesson onto an unrung
    number; the delete is the same move the other way and did not.

    With bells at 1, 2 and 4 — a school whose third slot is a shift change, which
    the grammar accepts — deleting the second lesson slid the fourth onto a
    third number that rings nothing, and the resolver draws a lesson only where
    its own bell is. The lesson was gone from every phone, widget, digest and
    the calendar feed while the editor still listed it.
    """
    from datetime import time

    from app.services import structure, timetable_edit

    bells = [(1, time(8, 0), time(8, 45)), (2, time(9, 0), time(9, 45)),
             (4, time(11, 0), time(11, 45))]
    rows = [
        (1, "Алгебра", None, None, WeekParity.ANY),
        (2, "Физика", None, None, WeekParity.ANY),
        (4, "Химия", None, None, WeekParity.ANY),
    ]
    await structure.apply_timetable(session, school_class, {5: rows}, bells)
    await session.commit()

    assert await timetable_edit.remove_lesson(session, school_class.id, 5, 2) == 1
    await session.commit()

    kept = await _day(session, school_class.id, 5)
    assert [index for index, _, _ in kept] == [1, 4]
    assert [subject for _, subject, _ in kept] == ["Алгебра", "Химия"]


async def test_deleting_a_lesson_still_closes_the_gap_when_every_number_rings(
    session, school_class
):
    """The renumbering is the point when it can be done: a day left at 1, 2, 4
    under bells that ring 1..7 reads as a lost lesson rather than a deleted
    one."""
    from app.services import timetable_edit

    for index, subject in ((1, "Алгебра"), (2, "Физика"), (3, "Химия")):
        await timetable_edit.add_lesson(
            session, school_class.id, 5, subject=subject, at=index
        )
    await session.commit()

    assert await timetable_edit.remove_lesson(session, school_class.id, 5, 2) == 1
    await session.commit()

    kept = await _day(session, school_class.id, 5)
    assert [index for index, _, _ in kept] == [1, 2]
    assert [subject for _, subject, _ in kept] == ["Алгебра", "Химия"]


async def test_the_editor_reads_the_gapped_schedule_the_same_way(session, school_class):
    """`add_lesson` shares the rule, so the button editor and the paste agree.

    The count said «three bells, all taken» after the second lesson: it refused
    the fourth, which rings, and would have taken a third, which does not.
    """
    from datetime import time

    from app.models import BellSchedule
    from app.services import structure

    bells_row = await session.get(BellSchedule, school_class.bell_schedule_id)
    await structure.write_bell_periods(
        session,
        bells_row,
        [(1, time(8, 0), time(8, 45)), (2, time(9, 0), time(9, 45)),
         (4, time(11, 0), time(11, 45))],
    )
    await session.commit()

    assert await edit.rings(session, school_class.id) == 3
    assert await edit.rung_indexes(session, school_class.id) == {1, 2, 4}
    assert await edit.add_lesson(session, school_class.id, 6, subject="Алгебра") == 1
    assert await edit.add_lesson(session, school_class.id, 6, subject="Физика") == 2
    # The next lesson is the next one that rings — the fourth, not a third
    # that would be stored and then drawn nowhere.
    assert await edit.add_lesson(session, school_class.id, 6, subject="Химия") == 4
    # And there is no fifth bell, so the day is full at three lessons.
    assert await edit.add_lesson(session, school_class.id, 6, subject="Лишний") is None
    await session.commit()

    assert [index for index, _, _ in await _day(session, school_class.id, 6)] == [1, 2, 4]


# ---- a slot is one lesson, or two halves ----------------------------------


async def test_a_parity_row_may_not_be_created_beside_a_weekly_one(session, school_class):
    """Two rows that both answer for the same week is a lesson the resolver has
    to guess at.

    `uq_timetable_cell` is (class, weekday, index, parity), so «каждую неделю»
    and «числитель» at lesson 1 satisfy it — and both pass the resolver's parity
    filter on an odd week. The template is loaded with no ``ORDER BY``, so which
    of the two subjects a phone drew was whatever the database handed back
    first. The paste path has refused this since `timetable_io._conflicts`; the
    editor's button refuses it in the handler. The service is where both of them
    reach the database, so the rule belongs here too.
    """
    from app.schedule import ScheduleResolver

    written = await edit.edit_lesson(
        session, school_class.id, 1, 1, WeekParity.ODD,
        subject="Тень", room=None, teacher=None,
    )
    await session.commit()

    assert written is False
    assert await _day(session, school_class.id) == [
        (1, "Алгебра", "any"),
        (2, "Физика", "any"),
        (3, "История", "any"),
    ]
    day = (await ScheduleResolver(session, school_class).resolve_range(date(2026, 9, 7), 1))[0]
    assert [lesson.subject for lesson in day.lessons] == ["Алгебра", "Физика", "История"]


async def test_a_weekly_row_may_not_be_created_beside_a_split_slot(session, school_class):
    """The same rule from the other side: a slot that already alternates cannot
    also hold a lesson that happens every week."""
    await edit.split_parity(session, school_class.id, 1, 2)
    await session.commit()

    assert await edit.edit_lesson(
        session, school_class.id, 1, 2, WeekParity.ANY,
        subject="Тень", room=None, teacher=None,
    ) is False
    await session.commit()

    assert [parity for index, _, parity in await _day(session, school_class.id) if index == 2] == [
        "even",
        "odd",
    ]


async def test_the_missing_half_of_a_split_slot_can_still_be_written(session, school_class):
    """A paste may bring «3. История [чис]» with no denominator half under it, and
    filling the other half in is one edit rather than a retyped day."""
    from app.models import TimetableEntry as Entry

    session.add(
        Entry(class_id=school_class.id, weekday=4, index=1,
              subject_name="История", parity=WeekParity.ODD)
    )
    await session.commit()

    assert await edit.edit_lesson(
        session, school_class.id, 4, 1, WeekParity.EVEN,
        subject="Обществознание", room=None, teacher=None,
    ) is True
    await session.commit()

    assert await _day(session, school_class.id, 4) == [
        (1, "Обществознание", "even"),
        (1, "История", "odd"),
    ]


# --------------------------------------------------------------------------
# The other end of «a lesson number needs a bell of its own number»
# --------------------------------------------------------------------------


async def test_shrinking_the_class_bells_says_which_lessons_stop_ringing(
    session, school_class
):
    """The invariant was only ever checked when a *lesson* was written.

    Writing the bells is the same rule from the other side: the resolver takes
    a lesson's times from the bell row of its own number and drops what has
    none, so cutting the class's own schedule short leaves every lesson above
    the new last rung stored, and drawn, logged and announced nowhere. The
    admin was told «✅ Звонки сохранены: 5 уроков» and nothing else.

    Pairs and not numbers, for the reason `TimetableImport.dropped` is: one
    number under two weekdays is two lessons nobody will see.
    """
    from datetime import time

    from app.models import BellSchedule
    from app.services import structure

    schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
    await edit.add_lesson(session, school_class.id, 1, subject="Химия")
    await edit.add_lesson(session, school_class.id, 2, subject="Биология")
    await session.commit()

    orphaned = await structure.write_bell_periods(
        session,
        schedule,
        [(index, time(8 + index, 0), time(8 + index, 45)) for index in range(1, 4)],
    )
    await session.commit()

    assert (1, 4) in orphaned
    assert all(index > 3 for _weekday, index in orphaned)


async def test_bells_that_still_ring_every_lesson_report_nothing(session, school_class):
    """The warning must not fire on the edit every admin actually makes."""
    from datetime import time

    from app.models import BellSchedule
    from app.services import structure

    schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
    orphaned = await structure.write_bell_periods(
        session,
        schedule,
        [(index, time(8 + index, 0), time(8 + index, 45)) for index in range(1, 9)],
    )
    await session.commit()

    assert orphaned == []


# --------------------------------------------------------------------------
# What the write check refuses
# --------------------------------------------------------------------------


def _terms(class_id: int, year: int, kind: TermKind, *bounds: tuple[date, date]) -> list[Term]:
    return [
        Term(
            class_id=class_id,
            year=year,
            kind=kind,
            index=index,
            starts_on=starts,
            ends_on=ends,
        )
        for index, (starts, ends) in enumerate(bounds, start=1)
    ]


async def test_the_write_check_stops_at_the_date_the_class_stops_teaching_on(
    session, school_class
):
    """31 May under a half-year that ended on the 25th.

    The resolver has read the class's own terms since the day «🗓 Четверти»
    stopped deciding only a name — `_off_reason` answers `out_of_year` here and
    `_resolve_day` returns before the overrides. The check in front of the
    write asked `school_year_bounds`, which is 31 May and always will be, so a
    «🔁 Замена» for this date was stored, audited and pushed to every
    subscriber with `notify_changes`, and then drawn on no phone.
    """
    session.add_all(
        _terms(
            school_class.id,
            2026,
            TermKind.SEMESTER,
            (date(2026, 9, 1), date(2026, 12, 31)),
            (date(2027, 1, 1), date(2027, 5, 25)),
        )
    )
    await session.commit()

    refusal = await edit.why_no_lesson_can_be_drawn(session, school_class.id, date(2027, 5, 31))

    assert refusal is not None
    assert "25.05" in refusal, refusal
    # The day before the half-year closes is still a day a lesson can be put on.
    assert await edit.why_no_lesson_can_be_drawn(
        session, school_class.id, date(2027, 5, 24)
    ) is None


async def test_the_write_check_refuses_the_holidays_between_two_terms(session, school_class):
    """2 November, with the first quarter closed on 26 October.

    The gap between two terms is out of season by the same rule as the summer,
    which is how an admin marks the autumn holidays by moving two dates instead
    of nine days. The check knew nothing about terms at all, so the fortnight
    every class has in it took substitutions that resolve to nothing.
    """
    session.add_all(
        _terms(
            school_class.id,
            2026,
            TermKind.QUARTER,
            (date(2026, 9, 1), date(2026, 10, 26)),
            (date(2026, 11, 5), date(2026, 12, 31)),
            (date(2027, 1, 1), date(2027, 3, 22)),
            (date(2027, 3, 23), date(2027, 5, 31)),
        )
    )
    await session.commit()

    refusal = await edit.why_no_lesson_can_be_drawn(session, school_class.id, date(2026, 11, 2))

    assert refusal is not None
    assert "каникулы" in refusal, refusal
    assert await edit.why_no_lesson_can_be_drawn(
        session, school_class.id, date(2026, 11, 5)
    ) is None


async def test_the_write_check_refuses_a_public_holiday(session, school_class):
    """8 March 2027 is a Monday, and the resolver draws nothing on it.

    `holidays.stops_lessons` has always been part of the read path's answer and
    was never part of this one — so on a class's busiest weekday of the year a
    substitution passed every check and was drawn nowhere. The sentence names
    the day, because unlike the other two refusals there is nothing to fix:
    nobody is at school.
    """
    day = date(2027, 3, 8)
    assert day.isoweekday() == 1, "this test is about a holiday landing on a teaching day"

    refusal = await edit.why_no_lesson_can_be_drawn(session, school_class.id, day)

    assert refusal is not None
    assert "Международный женский день" in refusal, refusal
    assert await edit.why_no_lesson_can_be_drawn(
        session, school_class.id, date(2027, 3, 15)
    ) is None
