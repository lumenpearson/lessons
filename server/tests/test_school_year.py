"""Where the school year starts and stops.

The calendar used to hold a rolling 31 days and nothing else, so every date
past the window read «Нет данных» — including the rest of the term. The horizon
is the school year now, and the school year is not "1 September to 1 June":
1 September lands on a weekend every few years, and June is exams and holidays
rather than lessons.
"""

from __future__ import annotations

from datetime import date

import pytest

from app.schedule import (
    school_year_bounds,
    school_year_days,
    school_year_end,
    school_year_start,
)


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
