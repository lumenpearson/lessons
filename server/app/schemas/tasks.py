"""A person's own tasks, which nobody else in the class sees."""

from __future__ import annotations

from datetime import date as Date
from datetime import datetime
from datetime import time as Time

from pydantic import BaseModel, Field, field_validator

from app.schemas._text import _clean_notes, _clean_optional_text


class TaskOut(BaseModel):
    id: int
    title: str
    notes: str | None = None
    subject_name: str | None = None
    due_date: Date | None = None
    due_time: Time | None = None
    priority: int
    done: bool
    # UTC: an instant, not a time anybody wrote down. See `PersonalTask`.
    done_at: datetime | None = None
    homework_id: int | None = None
    # Class wall time, unlike the three around it.
    remind_at: datetime | None = None
    # UTC, with `done_at`.
    created_at: datetime | None = None
    updated_at: datetime | None = None


class TaskIn(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    notes: str | None = Field(default=None, max_length=2000)
    due_date: Date | None = None
    due_time: Time | None = None
    priority: int = Field(default=1, ge=0, le=2)
    subject_name: str | None = Field(default=None, max_length=120)
    homework_id: int | None = None
    remind_at: datetime | None = None

    @field_validator("title")
    @classmethod
    def _clean_title(cls, value: str) -> str:
        cleaned = _clean_optional_text(value)
        if cleaned is None:
            raise ValueError("title must not be blank")
        return cleaned

    _clean_subject = field_validator("subject_name")(_clean_optional_text)
    _clean_notes = field_validator("notes")(_clean_notes)


class TaskPatch(BaseModel):
    """Every field optional; only the ones sent are changed. ``null`` clears a
    nullable field, which is why "absent" and "null" are told apart."""

    title: str | None = Field(default=None, min_length=1, max_length=200)
    notes: str | None = Field(default=None, max_length=2000)
    due_date: Date | None = None
    due_time: Time | None = None
    priority: int | None = Field(default=None, ge=0, le=2)
    subject_name: str | None = Field(default=None, max_length=120)
    homework_id: int | None = None
    remind_at: datetime | None = None
    done: bool | None = None

    # An absent field is never validated, so these two run only on a value the
    # client actually sent - which is how an explicit ``null`` is refused while
    # "leave it alone" stays the default, exactly as ``ClassPatch`` does it.
    # Both columns are NOT NULL, and without the refusal ``{"title": null}``
    # reached the UPDATE: an IntegrityError forty frames down, which the app
    # sees as a 500 on a field it merely cleared.
    @field_validator("title")
    @classmethod
    def _clean_title(cls, value: str | None) -> str:
        cleaned = _clean_optional_text(value)
        if cleaned is None:
            raise ValueError("title must not be blank")
        return cleaned

    @field_validator("priority")
    @classmethod
    def _priority_when_present(cls, value: int | None) -> int:
        if value is None:
            raise ValueError("priority must not be null")
        return value

    _clean_subject = field_validator("subject_name")(_clean_optional_text)
    _clean_notes = field_validator("notes")(_clean_notes)
