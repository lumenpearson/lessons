"""Wire format shared with the Android client.

Field names are the contract. Anything renamed here must be renamed in
``core/network`` on the Android side and bumped in ``API_VERSION``.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import time as Time

from pydantic import BaseModel, Field

API_VERSION = 1


class JoinRequest(BaseModel):
    code: str = Field(min_length=4, max_length=16)
    device_name: str | None = Field(default=None, max_length=120)


class JoinResponse(BaseModel):
    token: str
    class_id: int
    class_name: str
    school: str | None = None
    timezone: str


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


class DayOut(BaseModel):
    date: Date
    weekday: int
    kind: str
    lessons: list[LessonOut] = []
    events: list[EventOut] = []
    homework: list[HomeworkOut] = []
    note: str | None = None


class ClassOut(BaseModel):
    id: int
    name: str
    school: str | None = None
    city: str | None = None
    timezone: str


class BundleOut(BaseModel):
    """One response holds everything the widget needs to run offline for two weeks."""

    api_version: int = API_VERSION
    school_class: ClassOut
    generated_at: str
    days: list[DayOut]
    next_school_day: DayOut | None = None
