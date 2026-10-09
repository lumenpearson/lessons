"""Homework on its own, outside a day: the list, the ticks, and a row written.

The per-day shape the bundle carries, ``HomeworkOut``, is in ``bundle``;
``HomeworkItemOut`` is that shape with the id and the date added.
"""

from __future__ import annotations

from datetime import date as Date

from pydantic import BaseModel, Field, field_validator

from app.schemas._text import _clean_notes, _strip_control_chars
from app.schemas.bundle import HomeworkOut


class HomeworkItemOut(HomeworkOut):
    """A homework row on its own, outside a day: the list and the ticks need
    the id and the date that the bundle's per-day shape carries implicitly."""

    id: int
    due_date: Date
    # This device's owner's tick. Always ``false`` for an unlinked device.
    done: bool = False


class DoneIn(BaseModel):
    done: bool


class DoneOut(BaseModel):
    id: int
    done: bool


def _subject(value: str | None) -> str:
    cleaned = _strip_control_chars(value).strip() if value is not None else ""
    if not cleaned:
        raise ValueError("subject must not be blank")
    return cleaned


def _text(value: str | None) -> str:
    # Line breaks stay: «№ 12–15\nустно § 4» is how homework is written.
    cleaned = _clean_notes(value)
    if cleaned is None:
        raise ValueError("text must not be blank")
    return cleaned


def _url(value: str | None) -> str | None:
    if value is None:
        return None
    cleaned = _strip_control_chars(value).strip()
    return cleaned or None


class HomeworkIn(BaseModel):
    due_date: Date
    subject: str = Field(min_length=1, max_length=120)
    text: str = Field(min_length=1, max_length=4000)
    attachment_url: str | None = Field(default=None, max_length=500)

    _clean_subject = field_validator("subject")(_subject)
    _clean_text = field_validator("text")(_text)
    _clean_url = field_validator("attachment_url")(_url)


class HomeworkPatch(BaseModel):
    """v2's ``UpdateHomework``: every field optional, and only the ones sent
    change. ``null`` takes ``attachment_url`` away; the date, the subject and
    the text cannot be cleared. Each is cleaned as ``HomeworkIn`` cleans it,
    so that a create and an update take the same words."""

    due_date: Date | None = None
    subject: str | None = Field(default=None, min_length=1, max_length=120)
    text: str | None = Field(default=None, min_length=1, max_length=4000)
    attachment_url: str | None = Field(default=None, max_length=500)

    # An absent field is never validated, so these run only on a value the
    # client sent: an explicit null is refused while «leave it alone» stays
    # the default, as ``TaskPatch`` does it.
    _clean_subject = field_validator("subject")(_subject)
    _clean_text = field_validator("text")(_text)
    _clean_url = field_validator("attachment_url")(_url)

    @field_validator("due_date")
    @classmethod
    def _date_when_present(cls, value: Date | None) -> Date:
        if value is None:
            raise ValueError("due_date must not be null")
        return value


class DateWindowIn(BaseModel):
    """v1's ``GET /homework`` query, ``from`` and ``to``, as v2's lists name
    the two: ``ListHomework``, ``ListEvents`` and ``ListSubstitutions``. Parsed
    as FastAPI parsed v1's, so that both versions take the same dates; the
    window itself is ``services/clock.window``'s."""

    start_date: Date | None = None
    end_date: Date | None = None
