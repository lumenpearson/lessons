"""Managing the class itself: the «⚙️ Класс» card and its edits, its terms, its deletion.

Part of the management surface; what holds across it is in the package
docstring.
"""

from __future__ import annotations

from datetime import date as Date
from typing import Literal

from pydantic import BaseModel, Field, field_validator

from app.schemas._text import _clean_optional_text
from app.schemas.bundle import TermOut


class ManagedClassOut(BaseModel):
    """The class card the bot draws under «⚙️ Класс», as data.

    ``join_code`` is in here because the card shows it: this endpoint is
    admin-only, and the code is what an admin reads out to the class.
    """

    id: int
    name: str
    school: str | None = None
    city: str | None = None
    timezone: str
    # «МСК+2 (UTC+5) · Екатеринбург» - the same label the bot prints, so the
    # app does not have to carry the table of Russian zones twice.
    timezone_label: str
    join_code: str
    #: "open" | "invite" — whether that code is enough on its own.
    join_mode: str = "open"
    members: int
    devices: int
    pending_requests: int
    bell_schedule_id: int | None = None
    calendar_ready: bool = False


class TermSchemeIn(BaseModel):
    """Switch the class between quarters and half-years."""

    kind: Literal["quarter", "semester"]


class TermBoundsIn(BaseModel):
    """Both edges of one term. Validated against the year in the service, not
    here: «пересекается с периодом 2» is a rule about the other rows, and
    pydantic can only see this one."""

    starts_on: Date
    ends_on: Date


class TermsOut(BaseModel):
    kind: Literal["quarter", "semester"]
    year: int
    terms: list[TermOut] = Field(default_factory=list)


class ClassPatch(BaseModel):
    """Only the fields present are changed; ``null`` clears a nullable one.

    ``name`` and ``timezone`` are not nullable: the name is what every message
    calls this class and what the delete confirmation is typed against, and a
    class with no zone would have no "today".
    """

    name: str | None = Field(default=None, min_length=1, max_length=64)
    # 1..11. The bound is the school's, not the column's: a class numbered 0 or
    # 12 would resolve its term scheme from a comparison that happens to be
    # true rather than from a decision.
    grade: int | None = Field(default=None, ge=1, le=11)
    letter: str | None = Field(default=None, max_length=8)
    school: str | None = Field(default=None, max_length=200)
    city: str | None = Field(default=None, max_length=120)
    timezone: str | None = Field(default=None, max_length=64)
    #: Who vouches for a phone: the class code, or the bot. See ``JoinMode``.
    #: A literal rather than the enum so an unknown value is a 422 with the
    #: field named, not a 500 from deep inside SQLAlchemy.
    join_mode: Literal["open", "invite"] | None = None

    # An absent field is never validated, so these two run only on a value the
    # client actually sent - which is how an explicit ``null`` is refused while
    # "leave it alone" stays the default.
    @field_validator("name", "timezone")
    @classmethod
    def _required_when_present(cls, value: str | None) -> str:
        cleaned = _clean_optional_text(value)
        if cleaned is None:
            raise ValueError("must not be blank")
        return cleaned

    _clean_school = field_validator("school")(_clean_optional_text)
    _clean_city = field_validator("city")(_clean_optional_text)


class ClassDeleteIn(BaseModel):
    """The class's own name, typed back.

    The same confirmation the bot asks for, and for the same reason: a «вы
    уверены?» button is pressed by the same thumb that pressed the one before
    it, while a name has to be read off the screen first.
    """

    confirm_name: str = Field(min_length=1, max_length=64)
