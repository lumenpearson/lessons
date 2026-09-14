"""Resolution of the weekly template + overrides into concrete days.

This module is deliberately free of FastAPI and aiogram imports: it is pure
domain logic that takes ORM rows and returns plain dataclasses, so it can be
unit-tested without a running app.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date as Date
from datetime import time as Time
from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import (
    BellPeriod,
    BellSchedule,
    DayEvent,
    DayKind,
    DayOverride,
    EventKind,
    Homework,
    LessonOverride,
    OverrideAction,
    SchoolClass,
    Subject,
    TermKind,
    TimetableEntry,
    WeekParity,
)

# How far ahead we are willing to look for "the next school day".
MAX_LOOKAHEAD_DAYS = 21

# The month the school year opens in, and the last month it runs through. June
# is excluded deliberately: it is exams and then holidays, and a timetable that
# kept repeating the weekly template through it would show lessons that nobody
# is going to.
SCHOOL_YEAR_START_MONTH = 9
SCHOOL_YEAR_END_MONTH = 5


def school_year_start(opening_year: int) -> Date:
    """First teaching day of the year that opens in ``opening_year``.

    The first of September unless it lands on a weekend, in which case teaching
    starts on the Monday after — which is what Russian schools do, and what
    makes a "year begins on the 1st" horizon wrong every few years.
    """
    first = Date(opening_year, SCHOOL_YEAR_START_MONTH, 1)
    # Monday is 0. Saturday (5) skips two days, Sunday (6) skips one.
    return first + timedelta(days={5: 2, 6: 1}.get(first.weekday(), 0))


def school_year_end(opening_year: int) -> Date:
    """Last day of the year that opened in ``opening_year``.

    The last day of May, whatever its length — no leap-year special case,
    because May has not got one.
    """
    return Date(opening_year + 1, SCHOOL_YEAR_END_MONTH, 31)


def school_year_bounds(on: Date) -> tuple[Date, Date]:
    """The school year ``on`` belongs to, as (first day, last day).

    A date in the summer between two years belongs to the one that is about to
    open rather than the one that has ended: in July the question "what is my
    timetable" is about September, and answering it with last May's is answering
    a question nobody asked.
    """
    opening = on.year if on.month >= SCHOOL_YEAR_START_MONTH else on.year - 1
    start, end = school_year_start(opening), school_year_end(opening)
    if on > end and on.month < SCHOOL_YEAR_START_MONTH:
        # Between the end of May and the start of September.
        opening = on.year
        start, end = school_year_start(opening), school_year_end(opening)
    return start, end


def school_year_days(on: Date) -> int:
    """How many days the school year containing ``on`` spans, ends included."""
    start, end = school_year_bounds(on)
    return (end - start).days + 1


# Where a grade stops being taught in quarters and starts being taught in
# halves. 10 and 11 are the exam years and are organised around полугодия.
FIRST_SEMESTER_GRADE = 10

# The conventional ends of the terms, as (month, day). They are what a class is
# seeded with and not what it is stuck with: каникулы move, a region shifts its
# spring break, a quarantine eats a week. Every one of these becomes a row the
# admin can edit — see `app/services/terms.py`.
#
# The starts are derived rather than listed: a term begins the day after the
# previous one ended, and the first begins when the year does. Listing both
# would let them contradict each other.
QUARTER_ENDS: tuple[tuple[int, int], ...] = ((10, 31), (12, 31), (3, 22), (5, 31))
SEMESTER_ENDS: tuple[tuple[int, int], ...] = ((12, 31), (5, 31))


def term_kind_for(grade: int | None) -> TermKind:
    """Which scheme a grade is taught in, when nobody has said otherwise."""
    if grade is not None and grade >= FIRST_SEMESTER_GRADE:
        return TermKind.SEMESTER
    return TermKind.QUARTER


def default_term_bounds(opening_year: int, kind: TermKind) -> list[tuple[Date, Date]]:
    """Conventional (start, end) for each term of one school year.

    A term runs from the day after the previous one ended through its own end
    date, and the first runs from the day the year opens. The December and May
    ends fall on the year boundary and on the end of the year itself, so no
    term ever reaches outside the year it belongs to.
    """
    ends = SEMESTER_ENDS if kind is TermKind.SEMESTER else QUARTER_ENDS
    year_start, year_end = school_year_start(opening_year), school_year_end(opening_year)

    bounds: list[tuple[Date, Date]] = []
    cursor = year_start
    for month, day in ends:
        # Months from September on belong to the opening year; January to May
        # belong to the next one.
        year = opening_year if month >= SCHOOL_YEAR_START_MONTH else opening_year + 1
        end = min(Date(year, month, day), year_end)
        # A school that ran a term short cannot make it end before it began.
        if end < cursor:
            end = cursor
        bounds.append((cursor, end))
        cursor = min(end + timedelta(days=1), year_end)
    return bounds



@dataclass(slots=True)
class ResolvedLesson:
    index: int
    subject: str
    starts_at: Time
    ends_at: Time
    room: str | None = None
    teacher: str | None = None
    color: str | None = None
    is_replaced: bool = False
    is_cancelled: bool = False
    note: str | None = None


@dataclass(slots=True)
class ResolvedEvent:
    title: str
    kind: EventKind
    starts_at: Time
    ends_at: Time
    location: str | None = None
    covers_lesson: bool = False


@dataclass(slots=True)
class ResolvedHomework:
    subject: str
    text: str
    attachment_url: str | None = None


@dataclass(slots=True)
class ResolvedDay:
    date: Date
    weekday: int
    kind: DayKind
    lessons: list[ResolvedLesson] = field(default_factory=list)
    events: list[ResolvedEvent] = field(default_factory=list)
    homework: list[ResolvedHomework] = field(default_factory=list)
    note: str | None = None

    @property
    def has_lessons(self) -> bool:
        return any(not lesson.is_cancelled for lesson in self.lessons)


def week_parity(day: Date) -> WeekParity:
    """ISO week number parity — the convention Russian schools use for
    «числитель/знаменатель» weeks."""
    return WeekParity.ODD if day.isocalendar().week % 2 == 1 else WeekParity.EVEN


class ScheduleResolver:
    """Loads everything a class needs for a date range in a handful of queries,
    then resolves each day in memory.

    Doing it this way keeps the endpoint at O(1) queries instead of O(days),
    which matters because the Android client asks for two weeks at a time.
    """

    def __init__(self, session: AsyncSession, school_class: SchoolClass) -> None:
        self.session = session
        self.school_class = school_class

    async def resolve_range(self, start: Date, days: int) -> list[ResolvedDay]:
        end = start + timedelta(days=days - 1)
        await self._load(start, end)
        return [self._resolve_day(start + timedelta(days=offset)) for offset in range(days)]

    async def next_school_day(self, after: Date) -> ResolvedDay | None:
        """First day strictly after ``after`` that actually has lessons."""
        start = after + timedelta(days=1)
        await self._load(start, start + timedelta(days=MAX_LOOKAHEAD_DAYS - 1))
        for offset in range(MAX_LOOKAHEAD_DAYS):
            day = self._resolve_day(start + timedelta(days=offset))
            if day.has_lessons:
                return day
        return None

    # ---- loading -------------------------------------------------------

    async def _load(self, start: Date, end: Date) -> None:
        class_id = self.school_class.id

        timetable = (
            await self.session.scalars(
                select(TimetableEntry).where(TimetableEntry.class_id == class_id)
            )
        ).all()
        subjects = (
            await self.session.scalars(select(Subject).where(Subject.class_id == class_id))
        ).all()
        day_overrides = (
            await self.session.scalars(
                select(DayOverride).where(
                    DayOverride.class_id == class_id,
                    DayOverride.date >= start,
                    DayOverride.date <= end,
                )
            )
        ).all()
        lesson_overrides = (
            await self.session.scalars(
                select(LessonOverride).where(
                    LessonOverride.class_id == class_id,
                    LessonOverride.date >= start,
                    LessonOverride.date <= end,
                )
            )
        ).all()
        events = (
            await self.session.scalars(
                select(DayEvent).where(
                    DayEvent.class_id == class_id,
                    DayEvent.date >= start,
                    DayEvent.date <= end,
                )
            )
        ).all()
        homework = (
            await self.session.scalars(
                select(Homework).where(
                    Homework.class_id == class_id,
                    Homework.due_date >= start,
                    Homework.due_date <= end,
                )
            )
        ).all()
        schedules = (
            await self.session.scalars(
                select(BellSchedule).where(BellSchedule.class_id == class_id)
            )
        ).all()

        self._timetable: dict[int, list[TimetableEntry]] = {}
        for entry in timetable:
            self._timetable.setdefault(entry.weekday, []).append(entry)

        self._subject_colors = {s.name: s.color for s in subjects if s.color}
        self._subject_teachers = {s.name: s.teacher for s in subjects if s.teacher}
        self._day_overrides = {o.date: o for o in day_overrides}

        self._lesson_overrides: dict[Date, dict[int, LessonOverride]] = {}
        for override in lesson_overrides:
            self._lesson_overrides.setdefault(override.date, {})[override.index] = override

        self._events: dict[Date, list[DayEvent]] = {}
        for event in events:
            self._events.setdefault(event.date, []).append(event)

        self._homework: dict[Date, list[Homework]] = {}
        for item in homework:
            self._homework.setdefault(item.due_date, []).append(item)

        self._bells: dict[int, dict[int, BellPeriod]] = {
            schedule.id: {period.index: period for period in schedule.periods}
            for schedule in schedules
        }
        self._default_bells = self._bells.get(self.school_class.bell_schedule_id or -1, {})

    # ---- resolution ----------------------------------------------------

    def _bells_for(self, day_override: DayOverride | None) -> dict[int, BellPeriod]:
        if day_override is not None and day_override.bell_schedule_id:
            return self._bells.get(day_override.bell_schedule_id, self._default_bells)
        return self._default_bells

    def _resolve_day(self, day: Date) -> ResolvedDay:
        weekday = day.isoweekday()
        day_override = self._day_overrides.get(day)
        kind = day_override.kind if day_override else DayKind.NORMAL

        resolved = ResolvedDay(
            date=day,
            weekday=weekday,
            kind=kind,
            note=day_override.note if day_override else None,
        )
        resolved.homework = [
            ResolvedHomework(item.subject_name, item.text, item.attachment_url)
            for item in sorted(self._homework.get(day, []), key=lambda h: h.subject_name)
        ]
        resolved.events = [
            ResolvedEvent(
                title=event.title,
                kind=event.kind,
                starts_at=event.starts_at,
                ends_at=event.ends_at,
                location=event.location,
                covers_lesson=event.covers_lesson,
            )
            for event in sorted(self._events.get(day, []), key=lambda e: e.starts_at)
        ]

        if kind is DayKind.HOLIDAY:
            return resolved

        bells = self._bells_for(day_override)
        overrides = self._lesson_overrides.get(day, {})
        parity = week_parity(day)

        # Base template for this weekday, filtered by week parity.
        slots: dict[int, ResolvedLesson] = {}
        for entry in self._timetable.get(weekday, []):
            if entry.parity is not WeekParity.ANY and entry.parity is not parity:
                continue
            period = bells.get(entry.index)
            if period is None:
                # A lesson with no bell row cannot be placed on the timeline.
                continue
            slots[entry.index] = ResolvedLesson(
                index=entry.index,
                subject=entry.subject_name,
                starts_at=period.starts_at,
                ends_at=period.ends_at,
                room=entry.room,
                teacher=entry.teacher,
                color=self._subject_colors.get(entry.subject_name),
            )

        for index, override in overrides.items():
            period = bells.get(index)
            if override.action is OverrideAction.CANCEL:
                if index in slots:
                    slots[index].is_cancelled = True
                    slots[index].note = override.note
                continue

            if period is None:
                continue

            existing = slots.get(index)
            subject = override.subject_name or (existing.subject if existing else None)
            if subject is None:
                continue
            # Room and teacher fall through from the template only while it is
            # still the same subject - a room change, a different teacher for
            # the same lesson. Once the subject itself is replaced the template
            # row describes a lesson that is not happening, and carrying its
            # teacher over pinned the физик's name on the история that took
            # the slot. The subject dictionary knows who teaches the new one.
            same_subject = existing is not None and existing.subject == subject
            inherited_room = existing.room if same_subject else None
            inherited_teacher = (
                existing.teacher if same_subject else self._subject_teachers.get(subject)
            )
            slots[index] = ResolvedLesson(
                index=index,
                subject=subject,
                starts_at=period.starts_at,
                ends_at=period.ends_at,
                room=override.room or inherited_room,
                teacher=override.teacher or inherited_teacher,
                color=self._subject_colors.get(subject),
                is_replaced=True,
                note=override.note,
            )

        resolved.lessons = sorted(slots.values(), key=lambda lesson: lesson.index)
        return resolved
