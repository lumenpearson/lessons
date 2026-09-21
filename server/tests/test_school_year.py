"""Where the school year starts and stops.

The calendar used to hold a rolling 31 days and nothing else, so every date
past the window read «Нет данных» — including the rest of the term. The horizon
is the school year now, and the school year is not "1 September to 1 June":
1 September lands on a weekend every few years, and June is exams and holidays
rather than lessons.
"""

from __future__ import annotations

from datetime import date
from datetime import date as Date

import pytest

from app.models import DayKind, DayOverride, TermKind
from app.schedule import (
    ScheduleResolver,
    school_year_bounds,
    school_year_days,
    school_year_end,
    school_year_start,
)
from app.services import terms as terms_service


@pytest.mark.parametrize(
    ("opening", "expected"),
    [
        # 1 September 2025 is a Monday — teaching starts on it.
        (2025, date(2025, 9, 1)),
        # 2026: a Tuesday. 2027: a Wednesday.
        (2026, date(2026, 9, 1)),
        (2027, date(2027, 9, 1)),
        # 1 September 2029 is a Saturday, so the first day is Monday the 3rd.
        (2029, date(2029, 9, 3)),
        # 1 September 2030 is a Sunday: Monday the 2nd.
        (2030, date(2030, 9, 2)),
    ],
)
def test_the_year_opens_on_a_weekday(opening, expected):
    assert school_year_start(opening) == expected
    assert expected.weekday() < 5


def test_the_year_closes_at_the_end_of_may():
    """June is excluded on purpose; the template would otherwise keep
    repeating lessons through exams and into the holidays."""
    assert school_year_end(2026) == date(2027, 5, 31)


@pytest.mark.parametrize(
    ("on", "opening"),
    [
        (date(2026, 9, 1), 2026),      # the first day itself
        (date(2026, 10, 15), 2026),    # the date from the bug report
        (date(2026, 12, 31), 2026),    # across the new year
        (date(2027, 1, 1), 2026),      # still the same school year
        (date(2027, 5, 31), 2026),     # the last day itself
    ],
)
def test_a_date_in_term_belongs_to_the_year_that_opened_before_it(on, opening):
    assert school_year_bounds(on) == (school_year_start(opening), school_year_end(opening))


@pytest.mark.parametrize("on", [date(2027, 6, 1), date(2027, 7, 20), date(2027, 8, 31)])
def test_the_summer_belongs_to_the_year_about_to_open(on):
    """In July «какое у меня расписание» is a question about September. Answering
    it with last May's is answering one nobody asked."""
    assert school_year_bounds(on) == (school_year_start(2027), school_year_end(2027))


def test_the_span_covers_every_day_of_the_year_ends_included():
    start, end = school_year_bounds(date(2026, 10, 15))
    assert school_year_days(date(2026, 10, 15)) == (end - start).days + 1


def test_no_school_year_is_longer_than_the_bundle_allows():
    """The client asks for the whole year in one request, so the widest year
    there can be has to fit inside MAX_BUNDLE_DAYS."""
    from app.api.public import MAX_BUNDLE_DAYS

    widest = max(
        (school_year_end(y) - school_year_start(y)).days + 1
        for y in range(2024, 2100)
    )
    assert widest <= MAX_BUNDLE_DAYS


# --------------------------------------------------------------------------
# The school's own dates, not the constant
# --------------------------------------------------------------------------


async def _semesters(session, school_class, year: int, second_ends: Date) -> None:
    """The two half-years, with the second one's end moved as an admin would."""
    await terms_service.set_scheme(session, school_class, TermKind.SEMESTER, year)
    rows = await terms_service.read(session, school_class.id, year)
    await terms_service.set_bounds(
        session, school_class, year, 2, rows[1].starts_on, second_ends
    )
    await session.commit()


async def test_teaching_stops_when_the_last_term_does(session, school_class):
    """Reported from a real class, and visible as three dots under a day.

    «2 полугодие» was given an end of 28 May in «🗓 Четверти», and the calendar
    scrolled to that May still drew a full day on the 29th, the 30th and the
    31st. The horizon was `SCHOOL_YEAR_END_MONTH` — 31 May, and always will be
    — so the dates the admin had typed decided the term's *name* and nothing
    else. The lessons were drawn on the phone, in the widget and in the
    calendar feed, and no amount of re-syncing could change them, because the
    server was sending exactly what it meant to.
    """
    year = 2026
    await _semesters(session, school_class, year, Date(2027, 5, 28))

    resolver = ScheduleResolver(session, school_class)
    # 24 May 2027 is a Monday, the weekday this class has lessons on.
    days = {day.date: day for day in await resolver.resolve_range(Date(2027, 5, 24), 9)}

    assert days[Date(2027, 5, 24)].has_lessons, "the 24th is inside the term"
    for past_the_end in (Date(2027, 5, 31),):
        assert not days[past_the_end].has_lessons, f"{past_the_end} is past the term's end"
        assert days[past_the_end].kind is DayKind.HOLIDAY


async def test_the_gap_between_two_terms_is_out_of_season(session, school_class):
    """Autumn holidays, described once by moving two dates.

    The conventional bounds are contiguous — each term opens the day after the
    last one closed — so a class that has never touched them sees no change.
    A class that has described a gap has described the holidays in it, and
    having to mark those nine days by hand as well would be asking the same
    question twice.
    """
    year = 2026
    await terms_service.set_scheme(session, school_class, TermKind.QUARTER, year)
    rows = await terms_service.read(session, school_class.id, year)
    await terms_service.set_bounds(
        session, school_class, year, 1, rows[0].starts_on, Date(2026, 10, 26)
    )
    await terms_service.set_bounds(
        session, school_class, year, 2, Date(2026, 11, 5), rows[1].ends_on
    )
    await session.commit()

    resolver = ScheduleResolver(session, school_class)
    days = {day.date: day for day in await resolver.resolve_range(Date(2026, 10, 26), 11)}

    assert days[Date(2026, 10, 26)].has_lessons, "the last day of the quarter still teaches"
    # 2 November 2026 is a Monday, and it is inside the gap.
    assert not days[Date(2026, 11, 2)].has_lessons
    assert days[Date(2026, 11, 2)].kind is DayKind.HOLIDAY


async def test_a_class_with_no_terms_keeps_the_conventional_horizon(session, school_class):
    """The rule this replaced, for every class that has never opened the card."""
    resolver = ScheduleResolver(session, school_class)
    days = {day.date: day for day in await resolver.resolve_range(Date(2027, 5, 24), 9)}

    assert days[Date(2027, 5, 24)].has_lessons
    assert days[Date(2027, 5, 31)].has_lessons, "31 May is the conventional last day"
    assert not days[Date(2027, 6, 1)].has_lessons


async def test_a_day_marked_by_hand_past_the_term_keeps_what_it_was_given(
    session, school_class
):
    """Out of season is not a reason to overwrite somebody's own answer.

    The same rule the summer already followed: the lessons are out of season,
    the day is not. A graduation marked as a normal day on 30 May stays that
    day, with its note, and only loses the template.
    """
    year = 2026
    await _semesters(session, school_class, year, Date(2027, 5, 28))
    session.add(
        DayOverride(
            class_id=school_class.id,
            date=Date(2027, 5, 31),
            kind=DayKind.NORMAL,
            note="Последний звонок",
        )
    )
    await session.commit()

    resolver = ScheduleResolver(session, school_class)
    days = {day.date: day for day in await resolver.resolve_range(Date(2027, 5, 31), 1)}

    assert days[Date(2027, 5, 31)].kind is DayKind.NORMAL
    assert days[Date(2027, 5, 31)].note == "Последний звонок"
    assert not days[Date(2027, 5, 31)].has_lessons
