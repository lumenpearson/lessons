"""The resolver decides what the app and the widget will show. Cover its rules."""

from __future__ import annotations

from datetime import date, time

import pytest

from app.models import (
    DayEvent,
    DayKind,
    DayOverride,
    EventKind,
    Homework,
    LessonOverride,
    OverrideAction,
    TimetableEntry,
    WeekParity,
)
from app.schedule import ScheduleResolver, week_parity

# 2026-09-07 is a Monday.
MONDAY = date(2026, 9, 7)
TUESDAY = date(2026, 9, 8)
SATURDAY = date(2026, 9, 12)


async def test_template_expands_onto_the_bell_schedule(session, school_class):
    days = await ScheduleResolver(session, school_class).resolve_range(MONDAY, 1)
    lessons = days[0].lessons

    assert [lesson.subject for lesson in lessons] == ["Алгебра", "Физика", "История"]
    assert lessons[0].starts_at == time(8, 30)
    assert lessons[0].ends_at == time(9, 15)
    assert lessons[0].room == "214"
    assert days[0].has_lessons


async def test_a_day_with_no_template_has_no_lessons(session, school_class):
    days = await ScheduleResolver(session, school_class).resolve_range(TUESDAY, 1)
    assert days[0].lessons == []
    assert not days[0].has_lessons


async def test_replacement_override_swaps_subject_and_room(session, school_class):
    session.add(
        LessonOverride(
            class_id=school_class.id,
            date=MONDAY,
            index=2,
            action=OverrideAction.REPLACE,
            subject_name="Химия",
            room="118",
            note="Вместо физики",
        )
    )
    await session.commit()

    days = await ScheduleResolver(session, school_class).resolve_range(MONDAY, 1)
    second = days[0].lessons[1]

    assert second.subject == "Химия"
    assert second.room == "118"
    assert second.is_replaced
    assert second.note == "Вместо физики"
    # The replacement keeps the bell slot it replaced.
    assert second.starts_at == time(9, 25)


async def test_cancel_override_marks_the_lesson_without_removing_it(session, school_class):
    session.add(
        LessonOverride(
            class_id=school_class.id, date=MONDAY, index=1, action=OverrideAction.CANCEL
        )
    )
    await session.commit()

    days = await ScheduleResolver(session, school_class).resolve_range(MONDAY, 1)
    first = days[0].lessons[0]

    assert first.is_cancelled
    # Still listed, so the UI can show it struck through rather than silently shifting.
    assert first.subject == "Алгебра"
    assert days[0].has_lessons


async def test_cancelling_every_lesson_empties_the_day(session, school_class):
    for index in (1, 2, 3):
        session.add(
            LessonOverride(
                class_id=school_class.id, date=MONDAY, index=index, action=OverrideAction.CANCEL
            )
        )
    await session.commit()

    days = await ScheduleResolver(session, school_class).resolve_range(MONDAY, 1)
    assert not days[0].has_lessons


async def test_add_override_inserts_a_lesson_absent_from_the_template(session, school_class):
    session.add(
        LessonOverride(
            class_id=school_class.id,
            date=MONDAY,
            index=4,
            action=OverrideAction.ADD,
            subject_name="Классный час",
        )
    )
    await session.commit()

    days = await ScheduleResolver(session, school_class).resolve_range(MONDAY, 1)
    assert [lesson.subject for lesson in days[0].lessons][-1] == "Классный час"
    assert days[0].lessons[-1].starts_at == time(11, 25)


async def test_holiday_override_clears_the_day_but_keeps_events(session, school_class):
    session.add(
        DayOverride(
            class_id=school_class.id, date=MONDAY, kind=DayKind.HOLIDAY, note="День знаний"
        )
    )
    session.add(
        DayEvent(
            class_id=school_class.id,
            date=MONDAY,
            starts_at=time(10, 0),
            ends_at=time(11, 0),
            title="Линейка",
            kind=EventKind.EVENT,
        )
    )
    await session.commit()

    day = (await ScheduleResolver(session, school_class).resolve_range(MONDAY, 1))[0]
    assert day.kind is DayKind.HOLIDAY
    assert day.lessons == []
    assert day.note == "День знаний"
    assert [event.title for event in day.events] == ["Линейка"]


async def test_shortened_day_uses_its_own_bell_schedule(session, school_class):
    from app.models import BellPeriod, BellSchedule

    short = BellSchedule(class_id=school_class.id, name="Сокращённое")
    session.add(short)
    await session.flush()
    for index, starts_at, ends_at in [
        (1, time(8, 30), time(9, 0)),
        (2, time(9, 10), time(9, 40)),
        (3, time(9, 50), time(10, 20)),
    ]:
        session.add(
            BellPeriod(schedule_id=short.id, index=index, starts_at=starts_at, ends_at=ends_at)
        )
    session.add(
        DayOverride(
            class_id=school_class.id,
            date=MONDAY,
            kind=DayKind.SHORTENED,
            bell_schedule_id=short.id,
        )
    )
    await session.commit()

    day = (await ScheduleResolver(session, school_class).resolve_range(MONDAY, 1))[0]
    assert day.lessons[0].ends_at == time(9, 0)
    assert day.lessons[2].ends_at == time(10, 20)


async def test_week_parity_filters_the_template(session, school_class):
    session.add(
        TimetableEntry(
            class_id=school_class.id,
            weekday=1,
            index=4,
            subject_name="Астрономия",
            parity=WeekParity.ODD,
        )
    )
    await session.commit()

    subjects_by_date = {}
    for day in (MONDAY, MONDAY.replace(day=14)):
        resolved = (await ScheduleResolver(session, school_class).resolve_range(day, 1))[0]
        subjects_by_date[day] = [lesson.subject for lesson in resolved.lessons]

    odd_week = MONDAY if week_parity(MONDAY).value == "odd" else MONDAY.replace(day=14)
    even_week = MONDAY.replace(day=14) if odd_week == MONDAY else MONDAY

    assert "Астрономия" in subjects_by_date[odd_week]
    assert "Астрономия" not in subjects_by_date[even_week]


async def test_homework_is_attached_to_the_day_it_is_due(session, school_class):
    session.add(
        Homework(
            class_id=school_class.id,
            due_date=TUESDAY,
            subject_name="Алгебра",
            text="№ 12–15",
        )
    )
    await session.commit()

    days = await ScheduleResolver(session, school_class).resolve_range(MONDAY, 2)
    assert days[0].homework == []
    assert days[1].homework[0].text == "№ 12–15"


async def test_next_school_day_skips_the_weekend(session, school_class):
    resolver = ScheduleResolver(session, school_class)
    following = await resolver.next_school_day(SATURDAY)

    assert following is not None
    assert following.date == date(2026, 9, 14)  # the next Monday
    assert following.has_lessons


async def test_next_school_day_gives_up_rather_than_looping_forever(session, school_class):
    # Wipe the template: there is no school day to find.
    await session.execute(TimetableEntry.__table__.delete())
    await session.commit()

    assert await ScheduleResolver(session, school_class).next_school_day(MONDAY) is None


@pytest.mark.parametrize("days", [1, 7, 14])
async def test_resolve_range_returns_exactly_the_requested_days(session, school_class, days):
    resolved = await ScheduleResolver(session, school_class).resolve_range(MONDAY, days)
    assert len(resolved) == days
    assert resolved[0].date == MONDAY
    assert resolved[-1].date == MONDAY.fromordinal(MONDAY.toordinal() + days - 1)


async def test_a_replacement_subject_does_not_inherit_the_old_teacher(session, school_class):
    """Monday lesson 2 is Физика. Replacing it with История must not pin the
    physics teacher on the history lesson; the subject dictionary knows who
    teaches history, and when it does not, nobody is better than the wrong one."""
    from sqlalchemy import select

    from app.models import LessonOverride, OverrideAction, Subject, TimetableEntry

    physics = await session.scalar(
        select(TimetableEntry).where(
            TimetableEntry.class_id == school_class.id,
            TimetableEntry.weekday == 1,
            TimetableEntry.index == 2,
        )
    )
    physics.teacher = "Иванова И.И."
    session.add(Subject(class_id=school_class.id, name="История", teacher="Петров П.П."))
    session.add(
        LessonOverride(
            class_id=school_class.id,
            date=MONDAY,
            index=2,
            action=OverrideAction.REPLACE,
            subject_name="История",
        )
    )
    session.add(
        LessonOverride(
            class_id=school_class.id,
            date=MONDAY,
            index=1,
            action=OverrideAction.REPLACE,
            subject_name="Обществознание",
        )
    )
    await session.commit()

    day = (await ScheduleResolver(session, school_class).resolve_range(MONDAY, 1))[0]
    by_index = {lesson.index: lesson for lesson in day.lessons}
    assert by_index[2].subject == "История"
    assert by_index[2].teacher == "Петров П.П."
    assert by_index[2].room is None
    # No dictionary entry: unknown rather than the algebra teacher's room.
    assert by_index[1].teacher is None
    assert by_index[1].room is None


async def test_a_room_change_for_the_same_subject_keeps_the_teacher(session, school_class):
    from sqlalchemy import select

    from app.models import LessonOverride, OverrideAction, TimetableEntry

    physics = await session.scalar(
        select(TimetableEntry).where(
            TimetableEntry.class_id == school_class.id,
            TimetableEntry.weekday == 1,
            TimetableEntry.index == 2,
        )
    )
    physics.teacher = "Иванова И.И."
    session.add(
        LessonOverride(
            class_id=school_class.id,
            date=MONDAY,
            index=2,
            action=OverrideAction.REPLACE,
            subject_name="Физика",
            room="101",
        )
    )
    await session.commit()

    day = (await ScheduleResolver(session, school_class).resolve_range(MONDAY, 1))[0]
    lesson = next(item for item in day.lessons if item.index == 2)
    assert lesson.room == "101"
    assert lesson.teacher == "Иванова И.И."
    assert lesson.is_replaced
