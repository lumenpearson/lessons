"""Wire format shared with the Android client.

Field names are the contract. Anything renamed here must be renamed in
``core/network`` on the Android side and bumped in ``API_VERSION``.
"""

from __future__ import annotations

import re
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
    """One line, single-spaced, or ``None``.

    Runs of whitespace are collapsed, not merely trimmed at the ends, because
    the bot has always done exactly that - ``" ".join(text.split())`` on every
    name it is typed - and a name is compared as text in three places that
    cannot see each other: the uniqueness check that makes the subject
    dictionary a dictionary, the rename that carries the timetable, the
    homework and the substitutions along by name, and the widget's own matching. A
    class where «Алгебра и начала» was added from the phone and «Алгебра  и
    начала» from the bot has two subjects that look like one, and a rename of
    either moves none of the other's lessons.

    Every whitespace character becomes a space first, so a tab is a word break
    rather than something ``_strip_control_chars`` silently deletes - it is
    neither a space nor printable, so «а\tб» used to be stored as «аб».
    """
    if value is None:
        return None
    spaced = "".join(" " if ch.isspace() else ch for ch in value)
    return " ".join(_strip_control_chars(spaced).split()) or None


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
    diary_sessions_purged: int = 0
    device_tokens_purged: int = 0
    diary_links_purged: int = 0
    device_invites_purged: int = 0


# ---------------------------------------------------------------------------
# The electronic diary
#
# The wire shapes of ``/api/v1/diary``. Every one is built from a provider
# model by its own ``of``, and that constructor is the seam: when the upstream
# changes, the provider's mapper absorbs it, these classes do not move, and the
# Android client never learns that anything happened.
# ---------------------------------------------------------------------------


class DiaryLoginIn(BaseModel):
    """Credentials, used once and never stored.

    ``login`` rather than ``email`` because the upstream accepts more than one
    kind of identifier and calling it an email would be a promise this project
    cannot keep.
    """

    login: str = Field(min_length=3, max_length=200)
    password: str = Field(min_length=1, max_length=200)

    @field_validator("login")
    @classmethod
    def _clean_login(cls, value: str) -> str:
        cleaned = _strip_control_chars(value).strip()
        if len(cleaned) < 3:
            raise ValueError("login must contain at least 3 usable characters")
        return cleaned


class DiaryLoginOut(BaseModel):
    token: str
    login: str


class DiaryStudentOut(BaseModel):
    id: int
    first_name: str
    last_name: str
    middle_name: str | None = None
    full_name: str
    school: str | None = None
    class_name: str | None = None

    @classmethod
    def of(cls, student) -> DiaryStudentOut:
        # Deliberately without ``education_id`` and ``group_id``: they are the
        # upstream's handles, the server resolves them from the student id on
        # every request, and a client that learned them would be a client that
        # could be pointed at somebody else's child.
        return cls(
            id=student.id,
            first_name=student.first_name,
            last_name=student.last_name,
            middle_name=student.middle_name,
            full_name=student.full_name,
            school=student.school,
            class_name=student.class_name,
        )


class DiaryPeriodOut(BaseModel):
    id: int
    name: str
    starts_on: Date | None = None
    ends_on: Date | None = None
    is_current: bool = False

    @classmethod
    def of(cls, period) -> DiaryPeriodOut:
        return cls(
            id=period.id,
            name=period.name,
            starts_on=period.starts_on,
            ends_on=period.ends_on,
            is_current=period.is_current,
        )


class DiarySubjectOut(BaseModel):
    id: int | None = None
    name: str

    @classmethod
    def of(cls, subject) -> DiarySubjectOut:
        return cls(id=subject.id, name=subject.name)


class DiaryTeacherOut(BaseModel):
    id: int | None = None
    name: str
    position: str | None = None
    subjects: list[str] = []

    @classmethod
    def of(cls, teacher) -> DiaryTeacherOut:
        return cls(
            id=teacher.id,
            name=teacher.name,
            position=teacher.position,
            subjects=list(teacher.subjects),
        )


class DiaryMarkOut(BaseModel):
    """One register entry.

    ``value`` is what belongs in the cell and ``kind`` says what it means, so a
    client can colour an absence differently without knowing that upstream it
    was type code 30000.
    """

    id: int | None = None
    subject_id: int | None = None
    subject: str
    date: Date | None = None
    value: str
    kind: str
    reason: str | None = None
    comment: str | None = None

    @classmethod
    def of(cls, mark) -> DiaryMarkOut:
        return cls(
            id=mark.id,
            subject_id=mark.subject_id,
            subject=mark.subject_name,
            date=mark.date,
            value=mark.value,
            kind=mark.kind.value,
            reason=mark.reason,
            comment=mark.comment,
        )


class DiaryEditOut(BaseModel):
    """One field a family has corrected, as the client needs to draw it.

    ``original`` is what the diary says **now**, not what it said when the
    correction was written — the client shows it as «в дневнике: …», and the
    question it answers is what is currently being covered up.
    """

    field: str
    value: str
    original: str | None = None
    #: The diary has changed this field since the correction was made, so the
    #: value being hidden is no longer the one the person decided to replace.
    changed_upstream: bool = False

    @classmethod
    def of(cls, edit) -> DiaryEditOut:
        return cls(
            field=edit.field,
            value=edit.value,
            original=edit.original,
            changed_upstream=edit.changed_upstream,
        )


class DiaryLessonOut(BaseModel):
    date: Date
    number: int | None = None
    subject: str
    starts_at: Time | None = None
    ends_at: Time | None = None
    room: str | None = None
    teacher: str | None = None
    homework: str | None = None
    topic: str | None = None
    #: The key a correction for this lesson is filed under. Sent down so the
    #: client echoes it back rather than building its own: two implementations
    #: of a key that has to match exactly would agree until the first lesson
    #: with no number, and then quietly stop.
    target: str = ""
    edits: list[DiaryEditOut] = Field(default_factory=list)
    #: Another lesson the same day carries the same key, so no correction is
    #: applied to either. See ``services/diary_overrides.lesson_target``.
    ambiguous: bool = False

    @classmethod
    def of(cls, overlaid) -> DiaryLessonOut:
        lesson = overlaid.lesson
        return cls(
            date=lesson.date,
            number=lesson.number,
            subject=lesson.subject,
            starts_at=lesson.starts_at,
            ends_at=lesson.ends_at,
            room=lesson.room,
            teacher=lesson.teacher,
            homework=lesson.homework,
            topic=lesson.topic,
            target=overlaid.target,
            edits=[DiaryEditOut.of(edit) for edit in overlaid.edits],
            ambiguous=overlaid.ambiguous,
        )


class DiaryHomeworkOut(BaseModel):
    id: int | None = None
    due_date: Date
    subject: str
    text: str
    teacher: str | None = None
    #: @see DiaryLessonOut.target
    target: str = ""
    edits: list[DiaryEditOut] = Field(default_factory=list)
    #: Another item due the same day shares this key — two assignments in one
    #: subject, neither carrying an upstream id — so no correction is applied to
    #: either. @see DiaryLessonOut.ambiguous
    ambiguous: bool = False

    @classmethod
    def of(cls, overlaid) -> DiaryHomeworkOut:
        item = overlaid.item
        return cls(
            id=item.id,
            due_date=item.due_date,
            subject=item.subject,
            text=item.text,
            teacher=item.teacher,
            target=overlaid.target,
            edits=[DiaryEditOut.of(edit) for edit in overlaid.edits],
            ambiguous=overlaid.ambiguous,
        )


class DiaryResetIn(BaseModel):
    """Which correction to take off.

    A body rather than a query string, and a POST rather than a DELETE, because
    a target is free text: a subject named «Физика & астрономия» produces a key
    with an ampersand in it, and a caller that encodes it a shade imperfectly
    resets nothing while being told 204. The value that has to match byte for
    byte does not travel in a URL.
    """

    target: str = Field(min_length=1, max_length=300)
    field: str = Field(min_length=1, max_length=40)


class DiaryOverrideIn(BaseModel):
    """A correction being written. The target came from a read; it is echoed."""

    target: str = Field(min_length=1, max_length=300)
    field: str = Field(min_length=1, max_length=40)
    #: Empty is a real answer — the diary often carries a placeholder where a
    #: family would rather see nothing — so it is stored rather than treated as
    #: a reset. Resetting is ``POST .../overrides/reset``, which names the
    #: target and field in a body; it used to be a ``DELETE`` with them in the
    #: query string, and a target is a colon-joined string with a subject name
    #: in it, which is the kind of thing a proxy log truncates and an ``&`` in
    #: a subject silently cuts in half.
    value: str = Field(max_length=4000)
    #: What the person was looking at when they wrote the correction.
    #:
    #: Taken from the client rather than re-read upstream, and deliberately: the
    #: question it exists to answer is "has the diary changed since the person
    #: decided to replace this", and the answer is about what *they* saw, not
    #: about what the upstream happened to say in the second this request
    #: landed. It is also nobody else's data — a family's own correction of
    #: their own diary — so there is no boundary here to defend.
    original: str | None = Field(default=None, max_length=4000)


class DiaryOverrideOut(BaseModel):
    """A stored correction, for the screen that lists and resets them."""

    target: str
    field: str
    value: str
    #: What the diary said **when this was written** — not what it says now.
    #:
    #: Spelled differently from :attr:`DiaryEditOut.original` on purpose. The
    #: two are the same feature, travel to the same client and mean opposite
    #: things: one is the value currently being covered up, this one is the
    #: value the person decided to replace, however long ago. One name for both
    #: is a client rendering «в дневнике: …» from whichever it happened to have.
    original_when_written: str | None = None
    updated_at: datetime

    @classmethod
    def of(cls, row) -> DiaryOverrideOut:
        return cls(
            target=row.target,
            field=row.field,
            value=row.value,
            original_when_written=row.original,
            updated_at=row.updated_at,
        )


class DiaryAttendanceOut(BaseModel):
    at: datetime
    direction: str

    @classmethod
    def of(cls, event) -> DiaryAttendanceOut:
        return cls(at=event.at, direction=event.direction)


# ---------------------------------------------------------------------------
# Managing the class
#
# The phone's half of the bot's /class, /subjects, /bells, /devices, /log,
# /stats, /export and /import. Nothing here carries the caller's own role or
# class: both are derived server-side from the bearer token and the Telegram
# account it is linked to, so what the client believes about itself has never
# been part of this contract.
#
# Timestamps on this surface are class wall time, like every other clock the
# app is given - the audit log is read by a person sitting in the class's own
# zone, not by one sitting next to the server.
# ---------------------------------------------------------------------------


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


class SchoolOut(BaseModel):
    """One row of the school directory, as the picker shows it."""

    name: str
    full_name: str
    #: The OGRN, a Russian company registration number — thirteen digits,
    #: assigned once and never reused. Returned so a
    #: client can tell two «Гимназия № 3» apart without parsing the address.
    ogrn: str | None = None
    inn: str | None = None
    address: str | None = None
    city: str | None = None
    region: str | None = None
    #: False for a school the register has closed. Shown rather than hidden:
    #: a class created in May may belong to one merged over the summer.
    active: bool = True


class SchoolSearchOut(BaseModel):
    """One page of results, and whether there is more behind it.

    ``truncated`` is not «есть ещё страницы» — those are ``pages``. It means
    the directory's own ceiling of twenty was reached, so this is the first
    twenty of an unknown number and the way forward is a longer query, not a
    next page. A client that ignores it will show «найдено 20» for a search
    matching three hundred schools.
    """

    items: list[SchoolOut] = Field(default_factory=list)
    page: int
    pages: int
    total: int
    truncated: bool = False


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


class TimetableExportOut(BaseModel):
    """The whole weekly template in the bot's paste format.

    Byte-for-byte what «📤 Экспорт» sends, so a text saved from the bot can be
    imported by the app and the other way round.
    """

    text: str
    lessons: int


class TimetableImportIn(BaseModel):
    text: str = Field(min_length=1, max_length=20000)
    # Whether to overwrite weekdays that already have lessons. Without it an
    # import that would replace something answers with the conflicts instead.
    replace: bool = False


class ImportConflictOut(BaseModel):
    """One weekday the paste would overwrite: what is there now, what would
    replace it. Weekday is 1=Monday .. 7=Sunday, as everywhere else."""

    weekday: int
    existing: int
    incoming: int


class TimetableImportOut(BaseModel):
    """``applied: false`` means nothing was written - either the paste held no
    day the parser recognised, or it collided and ``replace`` was not set."""

    applied: bool
    days: list[int] = []
    lessons: int = 0
    bells: int = 0
    conflicts: list[ImportConflictOut] = []
    # Lines the parser could not read. Echoed back so an admin can fix the two
    # that were typos rather than re-reading the whole paste.
    rejected: list[str] = []


class ManagedDeviceOut(BaseModel):
    """One phone on the class's list.

    ``owner`` is a display name, never the Telegram id: an admin needs to know
    whose phone this is, and that is the whole of what they need.
    """

    id: int
    device_name: str | None = None
    linked: bool = False
    owner: str | None = None
    role: RoleName | None = None
    revoked: bool = False
    created_at: datetime | None = None
    last_seen_at: datetime | None = None
    linked_at: datetime | None = None


class AuditEntryOut(BaseModel):
    id: int
    # Machine tag: homework.add, subject.rename, timetable.import, ...
    action: str
    summary: str
    who: str | None = None
    at: datetime | None = None


class AuditPageOut(BaseModel):
    """``has_more`` rather than a total: the log is append-only and unbounded,
    and a count of it would be a full scan on every page turn."""

    entries: list[AuditEntryOut] = []
    limit: int
    offset: int
    has_more: bool = False


class SubjectHoursOut(BaseModel):
    """Lessons a week. A subject that alternates weeks counts a half, which is
    how a school's own paperwork writes «часов в неделю»."""

    name: str
    hours: float


class StatsOut(BaseModel):
    today: Date
    lessons_per_week: float
    subjects_count: int
    subjects: list[SubjectHoursOut] = []
    homework_open: int
    homework_total: int
    members_by_role: dict[RoleName, int] = {}
    devices_active: int
    overrides_upcoming: int
    events_upcoming: int


class AccessRequestOut(BaseModel):
    id: int
    who: str
    requested_role: RoleName
    message: str | None = None
    created_at: datetime | None = None


class RequestDecisionIn(BaseModel):
    """The role to grant. Absent means the one that was asked for, which is
    what pressing «Выдать» in the bot does. Ignored when declining."""

    role: RoleName | None = None


class RequestDecisionOut(BaseModel):
    id: int
    status: Literal["approved", "declined"]
    # The role actually granted, or ``null`` for a declined request.
    role: RoleName | None = None
    who: str
