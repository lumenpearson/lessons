"""Wire format shared with the Android client.

Field names are the contract. Anything renamed here must be renamed in
``core/network`` on the Android side and bumped in ``API_VERSION``.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import datetime
from datetime import time as Time
from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator

API_VERSION = 1


def _strip_control_chars(value: str) -> str:
    """Drop anything unprintable, including NUL.

    ``device_name`` and ``code`` are client-supplied and land in a database
    column. Postgres rejects NUL inside text outright, so a device that sent one
    got a 500 out of what should be a clean request.
    """
    return "".join(ch for ch in value if ch == " " or ch.isprintable())


def _clean_optional_text(value: str | None) -> str | None:
    if value is None:
        return None
    cleaned = _strip_control_chars(value.replace("\n", " ")).strip()
    return cleaned or None


def _clean_notes(value: str | None) -> str | None:
    """Notes keep their line breaks; everything else unprintable goes."""
    if value is None:
        return None
    cleaned = "".join(ch for ch in value if ch == "\n" or ch == " " or ch.isprintable())
    cleaned = cleaned.strip()
    return cleaned or None


#: Mirrors ``JoinRequest.code``'s own ``min_length``; see ``_clean_code``.
_MIN_CODE_LENGTH = 4


class JoinRequest(BaseModel):
    code: str = Field(min_length=4, max_length=16)
    device_name: str | None = Field(default=None, max_length=120)

    @field_validator("code")
    @classmethod
    def _clean_code(cls, value: str) -> str:
        """Sanitise the code, and reject one that sanitises away to nothing.

        This used to share ``_sanitise`` with ``device_name`` and return
        ``cleaned or None``. Pydantic does not re-check an after-validator's
        return against the field's annotation, so a code of four spaces — which
        passes ``min_length=4`` — arrived at the handler as ``None`` and the
        ``.strip()`` there raised ``AttributeError``. That is a 500 out of a
        request the schema exists to reject, on an unauthenticated endpoint.
        """
        cleaned = _strip_control_chars(value).strip()
        if len(cleaned) < _MIN_CODE_LENGTH:
            raise ValueError("code must contain at least 4 usable characters")
        return cleaned

    @field_validator("device_name")
    @classmethod
    def _clean_device_name(cls, value: str | None) -> str | None:
        """Optional, so sanitising it away to nothing simply means absent."""
        if value is None:
            return None
        cleaned = _strip_control_chars(value).strip()
        return cleaned or None


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


# --------------------------------------------------------------------------
# Linking
# --------------------------------------------------------------------------


class MeOut(BaseModel):
    device_name: str | None = None
    linked: bool
    role: RoleName | None = None
    can_edit: bool
    # Only while unlinked. A linked device has no code, and a code that stayed
    # on a linked row could be typed by somebody else and re-home the phone.
    link_code: str | None = None
    bot_deep_link: str | None = None


class UnlinkOut(BaseModel):
    linked: bool


# --------------------------------------------------------------------------
# Homework
# --------------------------------------------------------------------------


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


class HomeworkIn(BaseModel):
    due_date: Date
    subject: str = Field(min_length=1, max_length=120)
    text: str = Field(min_length=1, max_length=4000)
    attachment_url: str | None = Field(default=None, max_length=500)

    @field_validator("subject")
    @classmethod
    def _clean_subject(cls, value: str) -> str:
        cleaned = _strip_control_chars(value).strip()
        if not cleaned:
            raise ValueError("subject must not be blank")
        return cleaned

    @field_validator("text")
    @classmethod
    def _clean_text(cls, value: str) -> str:
        # Line breaks stay: «№ 12–15\nустно § 4» is how homework is written.
        cleaned = _clean_notes(value)
        if cleaned is None:
            raise ValueError("text must not be blank")
        return cleaned

    @field_validator("attachment_url")
    @classmethod
    def _clean_url(cls, value: str | None) -> str | None:
        if value is None:
            return None
        cleaned = _strip_control_chars(value).strip()
        return cleaned or None


# --------------------------------------------------------------------------
# Subjects
# --------------------------------------------------------------------------


class SubjectOut(BaseModel):
    name: str
    short_name: str | None = None
    teacher: str | None = None
    color: str | None = None


# --------------------------------------------------------------------------
# Now
# --------------------------------------------------------------------------


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


# --------------------------------------------------------------------------
# Personal tasks
# --------------------------------------------------------------------------


class TaskOut(BaseModel):
    id: int
    title: str
    notes: str | None = None
    subject_name: str | None = None
    due_date: Date | None = None
    due_time: Time | None = None
    priority: int
    done: bool
    done_at: datetime | None = None
    homework_id: int | None = None
    # Class wall time, like every other clock on this surface.
    remind_at: datetime | None = None
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

    @field_validator("title")
    @classmethod
    def _clean_title(cls, value: str | None) -> str | None:
        if value is None:
            return None
        cleaned = _clean_optional_text(value)
        if cleaned is None:
            raise ValueError("title must not be blank")
        return cleaned

    _clean_subject = field_validator("subject_name")(_clean_optional_text)
    _clean_notes = field_validator("notes")(_clean_notes)


# --------------------------------------------------------------------------
# Calendar
# --------------------------------------------------------------------------


class CalendarOut(BaseModel):
    url: str


# --------------------------------------------------------------------------
# Editing
# --------------------------------------------------------------------------


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


# --------------------------------------------------------------------------
# Cron
# --------------------------------------------------------------------------


class TickOut(BaseModel):
    morning: int
    evening: int
    tasks: int
    failed: int
    fsm_purged: int
    join_attempts_purged: int
