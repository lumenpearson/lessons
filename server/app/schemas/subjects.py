"""The subject dictionary: read by every phone, managed by an admin.

The colour cleaning lives here because a subject is the only row a colour is
written to; a lesson's colour is its subject's.
"""

from __future__ import annotations

import re

from pydantic import BaseModel, Field, field_validator

from app.schemas._text import _clean_optional_text


class SubjectOut(BaseModel):
    name: str
    short_name: str | None = None
    teacher: str | None = None
    color: str | None = None


#: The bot's own spelling of a colour, so the two surfaces store one format.
_COLOUR_RE = re.compile(r"^#?([0-9a-fA-F]{6})$")


def _clean_colour(value: str | None) -> str | None:
    """``#5b6abf`` / ``5B6ABF`` -> ``#5B6ABF``; a dash or blank clears it.

    One spelling in the database is what lets the app compare a lesson's colour
    against a palette entry without normalising first, and it is what the
    bot's colour picker already writes.
    """
    if value is None:
        return None
    raw = value.strip()
    if raw in {"", "-", "—"}:
        return None
    match = _COLOUR_RE.match(raw)
    if match is None:
        raise ValueError("colour must be six hex digits, as #5B6ABF")
    return f"#{match.group(1).upper()}"


class ManagedSubjectOut(SubjectOut):
    """A subject with its id. The read-only dictionary in ``GET /subjects`` is
    keyed by name because that is what a lesson stores; management addresses a
    row, which needs the id."""

    id: int


class SubjectIn(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    short_name: str | None = Field(default=None, max_length=16)
    teacher: str | None = Field(default=None, max_length=120)
    color: str | None = Field(default=None, max_length=9)

    @field_validator("name")
    @classmethod
    def _clean_name(cls, value: str) -> str:
        cleaned = _clean_optional_text(value)
        if cleaned is None:
            raise ValueError("name must not be blank")
        return cleaned

    _clean_short = field_validator("short_name")(_clean_optional_text)
    _clean_teacher = field_validator("teacher")(_clean_optional_text)
    _clean_color = field_validator("color")(_clean_colour)


class SubjectPatch(BaseModel):
    """Only the fields present are changed; ``null`` clears a nullable one.
    ``name`` is the rename, and it carries the timetable with it."""

    name: str | None = Field(default=None, min_length=1, max_length=120)
    short_name: str | None = Field(default=None, max_length=16)
    teacher: str | None = Field(default=None, max_length=120)
    color: str | None = Field(default=None, max_length=9)

    # Sent, so it is validated; absent, so it is not. A subject with no name
    # would be a lesson that cannot be spelled.
    @field_validator("name")
    @classmethod
    def _name_when_present(cls, value: str | None) -> str:
        cleaned = _clean_optional_text(value)
        if cleaned is None:
            raise ValueError("name must not be blank")
        return cleaned

    _clean_short = field_validator("short_name")(_clean_optional_text)
    _clean_teacher = field_validator("teacher")(_clean_optional_text)
    _clean_color = field_validator("color")(_clean_colour)


class SubjectSavedOut(BaseModel):
    """``moved`` is how many timetable, homework and substitution rows a rename
    carried with it - zero for every other kind of edit, and the number an
    admin needs to believe the rename actually happened."""

    subject: ManagedSubjectOut
    moved: int = 0
