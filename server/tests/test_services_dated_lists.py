"""The window a list of the class's dated things covers, in ``services/``.

v1's ``GET /homework`` held its window in the router: today and three weeks
on by default, sixty-two days at most, and the start bounded before the end is
derived from it. v2's ``ListHomework`` and ``ListEvents`` answer the same
window, so it moved to ``clock.window`` with the query beside each list, and
v1 calls them (``docs/specs/2026-10-05-server-v2-design.md``, decision 2).
``test_api_extended.py``, untouched, is the proof that v1's answers did not
move; these hold the rule a fact at a time, and its refusals as facts each
shell words for itself.
"""

from __future__ import annotations

from datetime import date, time, timedelta

import pytest

from app.models import DayEvent, EventKind, Homework, SchoolClass
from app.services import clock, events
from app.services import homework as homework_service

MONDAY = date(2026, 9, 7)


def test_a_window_is_today_and_three_weeks_on_unless_it_names_its_edges() -> None:
    assert clock.window(None, None, MONDAY) == (MONDAY, MONDAY + timedelta(days=21))
    assert clock.window(date(2026, 9, 1), None, MONDAY) == (date(2026, 9, 1), date(2026, 9, 22))
    assert clock.window(None, date(2026, 9, 10), MONDAY) == (MONDAY, date(2026, 9, 10))
    # Sixty-two days is the most, and is allowed.
    widest = MONDAY + timedelta(days=62)
    assert clock.window(MONDAY, widest, MONDAY) == (MONDAY, widest)


@pytest.mark.parametrize(
    ("start", "end", "edge", "why"),
    [
        (date(1999, 12, 31), None, "start", clock.OUT_OF_BOUNDS),
        # Never an OverflowError: the start is bounded before the end is
        # derived from it.
        (date.max, None, "start", clock.OUT_OF_BOUNDS),
        # An end derived from a start near the bound is the start's fault.
        (date(2100, 1, 1), None, "start", clock.OUT_OF_BOUNDS),
        (None, date(2100, 1, 2), "end", clock.OUT_OF_BOUNDS),
        (date(2026, 9, 10), date(2026, 9, 1), "end", clock.BACKWARDS),
        (MONDAY, MONDAY + timedelta(days=63), "end", clock.TOO_WIDE),
    ],
)
def test_a_window_no_list_may_be_asked_for_names_the_edge_at_fault(start, end, edge, why) -> None:
    with pytest.raises(clock.WindowRefused) as refused:
        clock.window(start, end, MONDAY)
    assert (refused.value.edge, refused.value.why) == (edge, why)


async def test_homework_and_events_in_a_window_come_in_order_and_from_this_class_only(
    session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    tuesday = MONDAY + timedelta(days=1)
    for class_id, due, subject in (
        (school_class.id, tuesday, "Физика"),
        (school_class.id, tuesday, "Алгебра"),
        (school_class.id, MONDAY, "История"),
        (school_class.id, MONDAY + timedelta(days=30), "Химия"),
        (other.id, MONDAY, "Чужое"),
    ):
        session.add(Homework(class_id=class_id, due_date=due, subject_name=subject, text="№ 1"))
    for class_id, day, starts, title in (
        (school_class.id, tuesday, time(9, 0), "Линейка"),
        (school_class.id, MONDAY, time(12, 30), "Обед"),
        (school_class.id, MONDAY, time(11, 10), "Завтрак"),
        (other.id, MONDAY, time(10, 0), "Чужое"),
    ):
        session.add(
            DayEvent(
                class_id=class_id,
                date=day,
                starts_at=starts,
                ends_at=time(starts.hour, 30),
                title=title,
                kind=EventKind.CANTEEN,
            )
        )
    await session.commit()

    due = await homework_service.due_between(session, school_class.id, MONDAY, tuesday)
    # By date, then subject: v1's order.
    assert [row.subject_name for row in due] == ["История", "Алгебра", "Физика"]
    on = await events.between(session, school_class.id, MONDAY, tuesday)
    # By date, then the time it starts.
    assert [row.title for row in on] == ["Завтрак", "Обед", "Линейка"]


def test_the_window_s_numbers_and_sentences_are_v1_s() -> None:
    assert (clock.WINDOW_DAYS, clock.WINDOW_MAX_DAYS) == (21, 62)
    assert clock.DATES_OUT_OF_BOUNDS == "dates must be between 2000-01-01 and 2100-01-01"
    assert clock.WINDOW_TOO_WIDE == "the range may span at most 62 days"
