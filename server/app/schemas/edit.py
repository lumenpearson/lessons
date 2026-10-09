"""Editing the class from the phone: substitutions, events and marked days.

``DeletedOut`` is here and not in a module of its own; the management
endpoints answer a delete with it too.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import time as Time
from typing import Any, Literal

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


class EventPatch(BaseModel):
    """v2's ``UpdateEvent``: every field optional, and only the ones sent
    change. ``null`` takes ``location`` away, and puts ``covers_lesson`` back
    to what the kind means; the date, the times, the title and the kind
    cannot be cleared. Cleaned and held as ``EventIn`` holds a new event: the
    caller sends both times whenever one of them moves, so that the one that
    stays is held against the one that moves."""

    date: Date | None = None
    starts_at: Time | None = None
    ends_at: Time | None = None
    title: str | None = Field(default=None, min_length=1, max_length=200)
    kind: EventKindName | None = None
    location: str | None = Field(default=None, max_length=120)
    covers_lesson: bool | None = None

    # An absent field is never validated, so these run only on a value the
    # client sent: an explicit null is refused while «leave it alone» stays
    # the default, as ``TaskPatch`` does it.
    @field_validator("date", "starts_at", "ends_at", "kind")
    @classmethod
    def _when_present(cls, value: Any) -> Any:
        if value is None:
            raise ValueError("must not be null")
        return value

    @field_validator("title")
    @classmethod
    def _clean_title(cls, value: str | None) -> str:
        cleaned = _clean_optional_text(value)
        if cleaned is None:
            raise ValueError("title must not be blank")
        return cleaned

    _clean_location = field_validator("location")(_clean_optional_text)

    @model_validator(mode="after")
    def _ends_after_start(self) -> EventPatch:
        starts, ends = self.starts_at, self.ends_at
        if starts is not None and ends is not None and ends <= starts:
            raise ValueError("ends_at must be after starts_at")
        return self


class EventCreatedOut(BaseModel):
    id: int


DayKindName = Literal["normal", "holiday", "shortened", "remote"]


class DateIn(BaseModel):
    """A date a v2 request names on its own — ``GetDay``'s ``date`` and
    ``UpdateDay``'s ``day.date`` — parsed as ``DayIn`` parses v1's ``date``."""

    date: Date


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
