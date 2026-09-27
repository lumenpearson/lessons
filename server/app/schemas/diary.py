"""The electronic diary: what it reads back, and the corrections over it.

The wire shapes of ``/api/v1/diary``. Every one is built from a provider
model by its own ``of``, and that constructor is the seam: when the upstream
changes, the provider's mapper absorbs it, these classes do not move, and the
Android client never learns that anything happened.

Signing in, and the session the phone registers, are in ``diary_session``.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import datetime
from datetime import time as Time

from pydantic import BaseModel, Field


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
    #: landed. The row is shared by everyone whose diary lists the child, so
    #: the last writer's ``original`` is what everybody's ``changed_upstream``
    #: is measured against — and still there is no boundary here to defend:
    #: whoever can write it sees the child in their own diary and could write
    #: the value itself, and a wrong ``original`` only raises or lowers a flag
    #: that is drawn beside the diary's own current answer.
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
