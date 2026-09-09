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
    TimetableEntry,
    WeekParity,
)

# How far ahead we are willing to look for "the next school day".
MAX_LOOKAHEAD_DAYS = 21


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
            slots[index] = ResolvedLesson(
                index=index,
                subject=subject,
                starts_at=period.starts_at,
                ends_at=period.ends_at,
                room=override.room or (existing.room if existing else None),
                teacher=override.teacher or (existing.teacher if existing else None),
                color=self._subject_colors.get(subject),
                is_replaced=True,
                note=override.note,
            )

        resolved.lessons = sorted(slots.values(), key=lambda lesson: lesson.index)
        return resolved
