"""Четверти and полугодия — the rules, not the rendering.

The scheme follows the grade until an admin says otherwise, the dates are the
school's own and therefore editable, and the edits that a school year cannot
hold are refused with a sentence rather than clamped: a clamped date is a date
the admin believes they set.
"""

from __future__ import annotations

from datetime import date

import pytest

from app.models import TermKind
from app.schedule import school_year_bounds
from app.services import terms as service


@pytest.mark.parametrize(
    ("grade", "expected"),
    [(1, TermKind.QUARTER), (9, TermKind.QUARTER), (10, TermKind.SEMESTER),
     (11, TermKind.SEMESTER), (None, TermKind.QUARTER)],
)
def test_the_scheme_follows_the_grade(school_class, grade, expected):
    school_class.grade = grade
    school_class.term_kind = None
    assert service.scheme_of(school_class) is expected


def test_a_stored_choice_beats_the_grade(school_class):
    """A school may teach an eleventh year in quarters, and «никто не решал» has
    to stay different from «решили четверти» or the choice cannot be made."""
    school_class.grade = 11
    school_class.term_kind = TermKind.QUARTER
    assert service.scheme_of(school_class) is TermKind.QUARTER


@pytest.mark.parametrize(
    ("grade", "letter", "expected"),
    [(9, "А", "9А"), (11, None, "11"), (5, " Б ", "5Б"), (10, "ФМ", "10ФМ")],
)
def test_the_name_is_composed_from_the_number(grade, letter, expected):
    assert service.compose_name(grade, letter, fallback="старое") == expected


def test_a_class_with_no_number_keeps_the_name_it_had():
    """Every class made before the column existed has a name and nothing else,
    and «9» read out of «9А» is the guess the column exists to avoid."""
    assert service.compose_name(None, None, fallback="9А") == "9А"


@pytest.mark.parametrize("grade", [0, 12, -1, 99])
def test_a_grade_outside_the_school_is_refused(grade):
    with pytest.raises(service.TermError):
        service.validate_grade(grade)


@pytest.mark.parametrize("raw", [None, "", "   ", "-"])
def test_no_letter_is_a_letter_of_none(raw):
    assert service.normalise_letter(raw) is None


async def test_seeding_gives_a_class_the_conventional_set(session, school_class):
    school_class.grade = 9
    seeded = await service.ensure(session, school_class, 2026)

    assert [t.index for t in seeded] == [1, 2, 3, 4]
    assert all(t.kind is TermKind.QUARTER for t in seeded)
    # Nose to tail with no gap and no overlap, inside the year.
    year_start, year_end = school_year_bounds(date(2026, 10, 15))
    assert seeded[0].starts_on == year_start
    assert seeded[-1].ends_on == year_end
    one_day = date(2026, 9, 2) - date(2026, 9, 1)
    for earlier, later in zip(seeded, seeded[1:], strict=False):
        assert later.starts_on == earlier.ends_on + one_day


async def test_seeding_twice_does_not_double_the_terms(session, school_class):
    """The read path calls this too — a class made before the feature has no
    terms, and the first person to open its calendar should see them."""
    school_class.grade = 9
    first = await service.ensure(session, school_class, 2026)
    again = await service.ensure(session, school_class, 2026)

    assert [t.id for t in first] == [t.id for t in again]


async def test_a_tenth_year_gets_two_halves(session, school_class):
    school_class.grade = 10
    seeded = await service.ensure(session, school_class, 2026)

    assert [t.index for t in seeded] == [1, 2]
    assert all(t.kind is TermKind.SEMESTER for t in seeded)


async def test_switching_the_scheme_replaces_the_set(session, school_class):
    """Four quarters and two halves do not map onto each other; a leftover
    third quarter inside a year that has two is not a state to keep."""
    school_class.grade = 9
    await service.ensure(session, school_class, 2026)

    switched = await service.set_scheme(session, school_class, TermKind.SEMESTER, 2026)
    await session.commit()

    assert [t.index for t in switched] == [1, 2]
    assert await service.read(session, school_class.id, 2026) == switched
    assert school_class.term_kind is TermKind.SEMESTER


async def test_a_term_can_be_moved(session, school_class):
    school_class.grade = 9
    await service.ensure(session, school_class, 2026)

    moved = await service.set_bounds(
        session, school_class, 2026, 1, date(2026, 9, 1), date(2026, 10, 25)
    )
    await session.commit()

    assert moved.ends_on == date(2026, 10, 25)


async def test_a_term_may_not_end_before_it_starts(session, school_class):
    school_class.grade = 9
    await service.ensure(session, school_class, 2026)

    with pytest.raises(service.TermError, match="раньше"):
        await service.set_bounds(
            session, school_class, 2026, 1, date(2026, 10, 25), date(2026, 9, 1)
        )


async def test_a_term_may_not_reach_outside_the_school_year(session, school_class):
    """Reaching into June claims lessons in the holidays."""
    school_class.grade = 9
    await service.ensure(session, school_class, 2026)

    with pytest.raises(service.TermError, match="учебный год"):
        await service.set_bounds(
            session, school_class, 2026, 4, date(2027, 4, 1), date(2027, 6, 20)
        )


async def test_two_terms_may_not_overlap(session, school_class):
    """«Какая сейчас четверть» has to have one answer."""
    school_class.grade = 9
    await service.ensure(session, school_class, 2026)

    with pytest.raises(service.TermError, match="ересекается"):
        await service.set_bounds(
            session, school_class, 2026, 1, date(2026, 9, 1), date(2026, 12, 1)
        )


async def test_an_unknown_term_is_refused_rather_than_created(session, school_class):
    school_class.grade = 9
    await service.ensure(session, school_class, 2026)

    with pytest.raises(service.TermError):
        await service.set_bounds(
            session, school_class, 2026, 7, date(2026, 9, 1), date(2026, 9, 2)
        )


async def test_a_date_resolves_to_the_term_holding_it(session, school_class):
    school_class.grade = 9
    seeded = await service.ensure(session, school_class, 2026)

    # 15 October 2026 — the date from the calendar bug report.
    assert service.term_at(seeded, date(2026, 10, 15)).index == 1
    assert service.term_at(seeded, date(2026, 11, 20)).index == 2


async def test_the_summer_belongs_to_no_term(session, school_class):
    """Каникулы are a real answer, not a missing one."""
    school_class.grade = 9
    seeded = await service.ensure(session, school_class, 2026)

    assert service.term_at(seeded, date(2027, 7, 1)) is None
