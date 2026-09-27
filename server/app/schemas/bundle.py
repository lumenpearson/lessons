"""What the widget reads: the bundle, the days in it, and ``/now``.

``API_VERSION`` lives here because the bundle is what carries it. ``RoleName``
lives here because ``DeviceOut`` is where a role first reaches the client; the
management modules take it from here rather than spelling the four roles again.
The calendar feed's answer is here too — it is another way to read these days.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import time as Time
from typing import Literal

from pydantic import BaseModel, Field

API_VERSION = 1


class LessonOut(BaseModel):
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


class EventOut(BaseModel):
    title: str
    kind: str
    starts_at: Time
    ends_at: Time
    location: str | None = None
    covers_lesson: bool = False


class HomeworkOut(BaseModel):
    subject: str
    text: str
    attachment_url: str | None = None


class HolidayOut(BaseModel):
    """A named date, whether or not it stops the lessons.

    ``code`` is stable and ``title`` is Russian, the way every other string the
    server puts on a screen is. A client that wants the name in its own
    language matches the code and falls back to the title, so a date this
    server learns before that client does still has something to draw.
    """

    code: str
    title: str
    #: True only for a statutory non-working day, and then the day has no
    #: lessons. False for an observance — «День учителя» is a full Wednesday.
    stops_lessons: bool


class DayOut(BaseModel):
    date: Date
    weekday: int
    kind: str
    lessons: list[LessonOut] = []
    events: list[EventOut] = []
    homework: list[HomeworkOut] = []
    note: str | None = None
    #: What this date is called, if it is called anything.
    holiday: HolidayOut | None = None
    #: Why there are no lessons, when the timetable was not what decided it:
    #: `out_of_year`, `between_terms` or `public_holiday`. Null on an ordinary
    #: day, including an ordinary empty one — «nobody put lessons on a Sunday»
    #: is not a reason, it is the absence of one.
    #:
    #: Added beside `kind` rather than folded into it: all three arrive as
    #: `holiday` today, and a client that has never heard of this field keeps
    #: drawing what it drew. What it buys the ones that have is four different
    #: accents on a calendar instead of one.
    off_reason: str | None = None


class TermOut(BaseModel):
    """One quarter or half-year, as the class actually runs it."""

    index: int
    kind: Literal["quarter", "semester"]
    starts_on: Date
    ends_on: Date


class ClassOut(BaseModel):
    id: int
    name: str
    # The year of school, 1..11, and the letter that distinguishes two classes
    # of the same year. Null on a class created before they existed: its name
    # is all there is, and «9» read out of «9А» would be a guess.
    grade: int | None = None
    letter: str | None = None
    school: str | None = None
    city: str | None = None
    timezone: str
    #: Which scheme the year is cut into, and the terms themselves — the app
    #: renders «2 четверть» and shades the calendar from these rather than
    #: recomputing dates a school is free to have moved.
    term_kind: Literal["quarter", "semester"] | None = None
    terms: list[TermOut] = Field(default_factory=list)


RoleName = Literal["viewer", "editor", "admin", "owner"]


class DeviceOut(BaseModel):
    """What this device may do. Never carries the Telegram id it is linked to:
    the app shows a name and a role, and the id is nobody's business."""

    linked: bool = False
    role: RoleName | None = None
    can_edit: bool = False


class BundleOut(BaseModel):
    """One response holds everything the widget needs to run offline for two weeks."""

    api_version: int = API_VERSION
    school_class: ClassOut
    generated_at: str
    days: list[DayOut]
    next_school_day: DayOut | None = None
    # Added in the same API version: absent for a client that predates it, and
    # ``None`` is what such a client's mapper ignores.
    device: DeviceOut | None = None


NowState = Literal["before_school", "lesson", "break", "after_school", "day_off", "no_data"]


class NowOut(BaseModel):
    """The server's answer to "what is happening right now", in the class's zone.

    The widget normally computes this from the bundle itself; the endpoint is
    for clients that would rather ask than carry the resolver, and it is what
    the bundle's own maths is checked against.
    """

    date: Date
    time: Time
    state: NowState
    current: LessonOut | None = None
    next: LessonOut | None = None
    # Seconds to the next boundary: the end of ``current`` during a lesson,
    # the start of ``next`` before school and in a break. ``None`` when the
    # school day is over or never started.
    until_next_seconds: int | None = None
    # The first day with lessons strictly after today.
    next_school_day: Date | None = None


class CalendarOut(BaseModel):
    url: str
