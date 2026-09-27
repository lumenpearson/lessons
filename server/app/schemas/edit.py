"""Editing the class from the phone: substitutions, events and marked days.

``DeletedOut`` is here and not in a module of its own; the management
endpoints answer a delete with it too.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import time as Time
from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator

from app.schemas._text import _clean_optional_text

OverrideActionName = Literal["replace", "cancel", "clear"]


class OverrideIn(BaseModel):
    date: Date
    index: int = Field(ge=1, le=20)
    action: OverrideActionName
    subject: str | None = Field(default=None, max_length=120)
    room: str | None = Field(default=None, max_length=32)
    teacher: str | None = Field(default=None, max_length=120)
    note: str | None = Field(default=None, max_length=500)

    _clean_subject = field_validator("subject")(_clean_optional_text)
    _clean_room = field_validator("room")(_clean_optional_text)
    _clean_teacher = field_validator("teacher")(_clean_optional_text)
    _clean_note = field_validator("note")(_clean_optional_text)

    @model_validator(mode="after")
    def _replace_needs_something(self) -> OverrideIn:
        # A replacement with nothing in it would be stored and then dropped by
        # the resolver as a no-op; better to say so up front.
        if self.action == "replace" and not (self.subject or self.room or self.teacher):
            raise ValueError("replace needs a subject, a room or a teacher")
        return self


class OverrideOut(BaseModel):
    date: Date
    index: int
    action: OverrideActionName
    subject: str | None = None
    room: str | None = None
    teacher: str | None = None
    note: str | None = None


EventKindName = Literal["event", "canteen", "exam", "trip", "meeting"]


class EventIn(BaseModel):
    date: Date
    starts_at: Time
    ends_at: Time
    title: str = Field(min_length=1, max_length=200)
    kind: EventKindName = "event"
    location: str | None = Field(default=None, max_length=120)
    # Defaults like the bot: only a «мероприятие» or a trip normally stands in
    # for lessons; a canteen break sits in a break.
    covers_lesson: bool | None = None

    @field_validator("title")
    @classmethod
    def _clean_title(cls, value: str) -> str:
        cleaned = _clean_optional_text(value)
        if cleaned is None:
            raise ValueError("title must not be blank")
        return cleaned

    _clean_location = field_validator("location")(_clean_optional_text)

    @model_validator(mode="after")
    def _ends_after_start(self) -> EventIn:
        if self.ends_at <= self.starts_at:
            raise ValueError("ends_at must be after starts_at")
        return self


class EventCreatedOut(BaseModel):
    id: int


DayKindName = Literal["normal", "holiday", "shortened", "remote"]


class DayIn(BaseModel):
    date: Date
    kind: DayKindName
    note: str | None = Field(default=None, max_length=500)
    bell_schedule_id: int | None = None

    _clean_note = field_validator("note")(_clean_optional_text)


class DayOverrideOut(BaseModel):
    date: Date
    kind: DayKindName
    note: str | None = None
    bell_schedule_id: int | None = None


class DeletedOut(BaseModel):
    id: int
    deleted: bool = True
