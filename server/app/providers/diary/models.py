"""The diary as this project describes it, whichever provider answers.

These are the models the rest of the application and the Android client see.
They are deliberately not any upstream's shapes: nothing here is called
``estimate_value_name`` or ``assignmentName``, nothing carries ``p_educations``
or ``weekDays``, and no field exists only because an undocumented API happened
to send it.

That distance is the point of the whole integration. When an upstream renames
a field, its ``mapper`` changes and these do not - so neither does the public
API, and neither does the phone. Two providers fill the same models from two
different upstreams, and every consumer reads one shape.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import datetime
from datetime import time as Time
from enum import StrEnum

from pydantic import BaseModel, Field


class MarkKind(StrEnum):
    """What a mark in the register actually is.

    An upstream mixes several different things into one list - a grade, an
    absence, a late arrival and a teacher's remark all arrive together and are
    told apart by a code. Naming them here is what stops every consumer from
    re-learning that code.
    """

    GRADE = "grade"
    ABSENCE = "absence"
    LATE = "late"
    REMARK = "remark"
    OTHER = "other"


class Student(BaseModel):
    """One pupil the signed-in account may see."""

    id: int
    first_name: str
    last_name: str
    middle_name: str | None = None
    school: str | None = None
    class_name: str | None = None
    #: Opaque upstream handles. Present because later calls need them and the
    #: client passes them straight back; never parsed by the phone.
    education_id: int
    group_id: int | None = None

    @property
    def full_name(self) -> str:
        parts = [self.last_name, self.first_name, self.middle_name]
        return " ".join(part for part in parts if part)


class AcademicPeriod(BaseModel):
    """A quarter or a term, with the dates it covers."""

    id: int
    name: str
    starts_on: Date | None = None
    ends_on: Date | None = None
    is_current: bool = False


class Subject(BaseModel):
    id: int | None = None
    name: str


class Teacher(BaseModel):
    id: int | None = None
    name: str
    position: str | None = None
    subjects: list[str] = Field(default_factory=list)


class Mark(BaseModel):
    """One entry in the register.

    @property value what is written in the cell: «5», «Н», «!» - already
        normalised, so a client never has to know which code means absent.
    """

    id: int | None = None
    subject_id: int | None = None
    subject_name: str
    date: Date | None = None
    value: str
    kind: MarkKind = MarkKind.OTHER
    #: The teacher's word for the occasion: «Контрольная работа», «Ответ на уроке».
    reason: str | None = None
    comment: str | None = None


class DiaryLesson(BaseModel):
    """A lesson as the diary has it, with whatever was set on it."""

    date: Date
    number: int | None = None
    subject: str
    starts_at: Time | None = None
    ends_at: Time | None = None
    room: str | None = None
    teacher: str | None = None
    homework: str | None = None
    topic: str | None = None


class HomeworkItem(BaseModel):
    """Homework, addressed to the day it is due rather than the day it was set."""

    id: int | None = None
    due_date: Date
    subject: str
    text: str
    teacher: str | None = None


class AttendanceEvent(BaseModel):
    """A turnstile record.

    ``direction`` is ``in``, ``out`` or ``unknown``. The third one is not a
    parsing failure to be tidied away: an upstream is undocumented, and a
    spelling this code has not seen must not be rendered as the child leaving
    the building. Anything reading this has three cases to answer, and the
    third one is «дневник не сказал, куда».

    Not every provider has turnstiles: «Сетевой город» has none, so its
    ``attendance`` is always empty. The model stays here for the one that does.
    """

    at: datetime
    direction: str  # "in" | "out" | "unknown"
