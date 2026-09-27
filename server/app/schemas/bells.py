"""Managing the bell schedules a class runs on.

Part of the management surface; what holds across it is in the package
docstring.
"""

from __future__ import annotations

from datetime import time as Time

from pydantic import BaseModel, Field, field_validator, model_validator

from app.schemas._text import _clean_optional_text


class BellPeriodIn(BaseModel):
    index: int = Field(ge=1, le=20)
    starts_at: Time
    ends_at: Time

    @model_validator(mode="after")
    def _ends_after_start(self) -> BellPeriodIn:
        if self.ends_at <= self.starts_at:
            raise ValueError("ends_at must be after starts_at")
        return self


class BellPeriodOut(BaseModel):
    index: int
    starts_at: Time
    ends_at: Time


class BellScheduleOut(BaseModel):
    id: int
    name: str
    # The one the class runs on when no day says otherwise.
    is_default: bool = False
    periods: list[BellPeriodOut] = []
    #: Lessons this write stopped ringing, counted in rows rather than in
    #: numbers — one number under two weekdays is two lessons nobody will see.
    #:
    #: Shrinking a schedule, or pointing the class at a shorter one, leaves
    #: every row past the new last rung stored and drawn nowhere. The bot says
    #: so in an alert the moment it happens; the API wrote the same count into
    #: the audit log and answered the caller with a schedule that looked
    #: entirely fine, so an admin editing bells from the phone lost six
    #: lessons and was told nothing until they went looking for the log. Zero
    #: on every read and on every write that silenced nothing, which is almost
    #: all of them.
    silenced_lessons: int = 0


class BellPeriodsIn(BaseModel):
    """At least one row, always.

    The stored schedule is the thing a class runs on, and an empty list is far
    more likely to be a client bug than somebody meaning "no bells at all" -
    which is what the bot's «пустой присылкой звонки не стереть» says too.
    """

    periods: list[BellPeriodIn] = Field(min_length=1, max_length=20)

    @model_validator(mode="after")
    def _indexes_are_unique(self) -> BellPeriodsIn:
        seen = {period.index for period in self.periods}
        if len(seen) != len(self.periods):
            raise ValueError("lesson numbers must not repeat")
        return self


class BellScheduleIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    # Optional here, unlike on the periods endpoint: a schedule may be created
    # empty and filled in afterwards.
    periods: list[BellPeriodIn] = Field(default=[], max_length=20)

    @field_validator("name")
    @classmethod
    def _clean_name(cls, value: str) -> str:
        cleaned = _clean_optional_text(value)
        if cleaned is None:
            raise ValueError("name must not be blank")
        return cleaned

    @model_validator(mode="after")
    def _indexes_are_unique(self) -> BellScheduleIn:
        seen = {period.index for period in self.periods}
        if len(seen) != len(self.periods):
            raise ValueError("lesson numbers must not repeat")
        return self


class BellSchedulePatch(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=64)
    # ``true`` makes this the class default. ``false`` is refused rather than
    # silently leaving the class with none: something has to be the default.
    is_default: bool | None = None

    @field_validator("name")
    @classmethod
    def _name_when_present(cls, value: str | None) -> str:
        cleaned = _clean_optional_text(value)
        if cleaned is None:
            raise ValueError("name must not be blank")
        return cleaned
