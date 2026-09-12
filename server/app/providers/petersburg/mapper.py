"""Upstream shapes in, this project's models out.

Every oddity of the Petersburg diary is meant to die in this file: the
``identity.id`` nesting, the Russian date strings, the numeric code that turns
a "mark" into an absence, the several names one field goes by.

It is deliberately forgiving. The upstream is undocumented and changes without
notice, and the response shapes for lessons and the timetable could not be
established from any public source - only their endpoints and parameters
could. So a field is looked for under every name it has been seen to use, and
a row that cannot be read is dropped rather than taking the request with it: a
week of marks with one unreadable entry is worth more than an error page.

What is *not* forgiving is the shape going out. If a lesson has no date there
is no lesson, and it is skipped.
"""

from __future__ import annotations

import logging
from datetime import date as Date
from datetime import datetime
from datetime import time as Time
from typing import Any

from app.providers.petersburg.models import (
    AcademicPeriod,
    AttendanceEvent,
    DiaryLesson,
    HomeworkItem,
    Mark,
    MarkKind,
    Student,
    Subject,
    Teacher,
)

log = logging.getLogger(__name__)

#: Their code for "was not there". The one magic number worth naming: it is
#: what separates a mark from an absence, and both arrive in the same list.
ABSENCE_CODE = "30000"
LATE_CODE = "30001"

#: What an absence and a remark are written as in a paper register, which is
#: what people expect to see in the cell.
ABSENCE_MARK = "Н"
LATE_MARK = "О"
REMARK_MARK = "!"

_DATE_FORMATS = ("%d.%m.%Y", "%Y-%m-%d", "%d.%m.%y")
_TIME_FORMATS = ("%H:%M:%S", "%H:%M")


# ---------------------------------------------------------------------------
# Field access
# ---------------------------------------------------------------------------


def pick(source: dict[str, Any], *names: str) -> Any:
    """First present, non-empty value among ``names``.

    Several names rather than one because the upstream is inconsistent about
    them between endpoints - a room is ``office`` in one response and
    ``cabinet`` in another - and because a rename upstream should cost a name
    in this tuple, not an outage.
    """
    for name in names:
        value = source.get(name)
        if value not in (None, "", [], {}):
            return value
    return None


def text(source: dict[str, Any], *names: str) -> str | None:
    value = pick(source, *names)
    if isinstance(value, str):
        stripped = value.strip()
        return stripped or None
    if isinstance(value, int | float):
        return str(value)
    return None


def number(source: dict[str, Any], *names: str) -> int | None:
    value = pick(source, *names)
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.strip().lstrip("-").isdigit():
        return int(value.strip())
    return None


def identity_id(source: dict[str, Any]) -> int | None:
    """``{"identity": {"id": 7}}``, which is how they carry most primary keys.

    Falls back to a plain ``id`` because some endpoints send that instead.
    """
    identity = source.get("identity")
    if isinstance(identity, dict):
        found = number(identity, "id")
        if found is not None:
            return found
    return number(source, "id")


def parse_date(raw: Any) -> Date | None:
    if isinstance(raw, str):
        candidate = raw.strip()
        # Some fields are a datetime; the date is all this needs.
        candidate = candidate.split(" ")[0].split("T")[0]
        for fmt in _DATE_FORMATS:
            try:
                return datetime.strptime(candidate, fmt).date()
            except ValueError:
                continue
    return None


def parse_time(raw: Any) -> Time | None:
    if isinstance(raw, str):
        candidate = raw.strip()
        if " " in candidate:
            candidate = candidate.split(" ")[-1]
        for fmt in _TIME_FORMATS:
            try:
                return datetime.strptime(candidate, fmt).time()
            except ValueError:
                continue
    return None


def parse_datetime(raw: Any) -> datetime | None:
    if not isinstance(raw, str):
        return None
    candidate = raw.strip().replace("T", " ")
    for fmt in ("%d.%m.%Y %H:%M:%S", "%Y-%m-%d %H:%M:%S", "%d.%m.%Y %H:%M", "%Y-%m-%d %H:%M"):
        try:
            return datetime.strptime(candidate, fmt)
        except ValueError:
            continue
    return None


# ---------------------------------------------------------------------------
# Students
# ---------------------------------------------------------------------------


def to_students(items: list[dict[str, Any]]) -> list[Student]:
    students: list[Student] = []
    for item in items:
        # A pupil with no education row has nothing later calls can ask about,
        # so there is nothing to show and nothing to select.
        educations = item.get("educations")
        education = educations[0] if isinstance(educations, list) and educations else None
        if not isinstance(education, dict):
            continue
        education_id = number(education, "education_id", "id")
        if education_id is None:
            continue

        student_id = identity_id(item)
        if student_id is None:
            continue

        students.append(
            Student(
                id=student_id,
                first_name=text(item, "firstname", "first_name") or "",
                last_name=text(item, "surname", "last_name", "lastname") or "",
                middle_name=text(item, "middlename", "middle_name"),
                school=text(education, "institution_name", "school_name"),
                class_name=text(education, "group_name", "class_name"),
                education_id=education_id,
                group_id=number(education, "group_id"),
            )
        )
    return students


def to_periods(items: list[dict[str, Any]], today: Date) -> list[AcademicPeriod]:
    periods: list[AcademicPeriod] = []
    for item in items:
        period_id = identity_id(item)
        name = text(item, "name")
        if period_id is None or name is None:
            continue
        starts = parse_date(pick(item, "date_from", "date_start"))
        ends = parse_date(pick(item, "date_to", "date_end"))
        periods.append(
            AcademicPeriod(
                id=period_id,
                name=name,
                starts_on=starts,
                ends_on=ends,
                is_current=bool(starts and ends and starts <= today <= ends),
            )
        )
    return periods


def to_subjects(items: list[dict[str, Any]]) -> list[Subject]:
    subjects: list[Subject] = []
    for item in items:
        name = text(item, "subject_name", "name", "title")
        if name is None:
            continue
        subjects.append(Subject(id=number(item, "subject_id") or identity_id(item), name=name))
    return subjects


def to_teachers(items: list[dict[str, Any]]) -> list[Teacher]:
    teachers: list[Teacher] = []
    for item in items:
        parts = [
            text(item, "surname", "last_name"),
            text(item, "firstname", "first_name"),
            text(item, "middlename", "middle_name"),
        ]
        name = " ".join(part for part in parts if part) or text(item, "name", "fullname")
        if not name:
            continue
        raw_subjects = pick(item, "subjects", "subject_list")
        subjects: list[str] = []
        if isinstance(raw_subjects, list):
            for entry in raw_subjects:
                if isinstance(entry, str) and entry.strip():
                    subjects.append(entry.strip())
                elif isinstance(entry, dict):
                    found = text(entry, "subject_name", "name")
                    if found:
                        subjects.append(found)
        teachers.append(
            Teacher(
                id=identity_id(item),
                name=name,
                position=text(item, "position_name", "position"),
                subjects=subjects,
            )
        )
    return teachers


# ---------------------------------------------------------------------------
# Marks
# ---------------------------------------------------------------------------


def classify_mark(item: dict[str, Any]) -> tuple[str, MarkKind]:
    """What the cell should say, and what it actually is.

    The upstream puts grades, absences, lateness and remarks in one list and
    distinguishes them by ``estimate_type_code``. Reading that code here means
    no consumer ever has to: they get "Н" and :attr:`MarkKind.ABSENCE`.
    """
    code = text(item, "estimate_type_code", "type_code")
    value = text(item, "estimate_value_name", "value_name", "value")
    reason = text(item, "estimate_type_name", "type_name")

    if code == ABSENCE_CODE:
        return ABSENCE_MARK, MarkKind.ABSENCE
    if code == LATE_CODE:
        return LATE_MARK, MarkKind.LATE
    if value == "Замечание" or reason == "Замечание":
        return REMARK_MARK, MarkKind.REMARK
    if value is None:
        return "", MarkKind.OTHER
    # A grade is a grade only when it reads like one; anything else keeps its
    # own wording rather than being forced into a number.
    kind = MarkKind.GRADE if value.strip().isdigit() else MarkKind.OTHER
    return value, kind


def to_marks(items: list[dict[str, Any]]) -> list[Mark]:
    marks: list[Mark] = []
    for item in items:
        subject_name = text(item, "subject_name", "subject")
        if subject_name is None:
            continue
        value, kind = classify_mark(item)
        marks.append(
            Mark(
                id=number(item, "id") or identity_id(item),
                subject_id=number(item, "subject_id"),
                subject_name=subject_name,
                date=parse_date(pick(item, "date", "date_estimate", "lesson_date")),
                value=value,
                kind=kind,
                reason=text(item, "estimate_type_name", "type_name"),
                comment=text(item, "estimate_comment", "comment"),
            )
        )
    marks.sort(key=lambda mark: (mark.date or Date.min, mark.subject_name))
    return marks


# ---------------------------------------------------------------------------
# Lessons, homework
# ---------------------------------------------------------------------------

#: Field names a lesson's parts have been seen under. The timetable and the
#: lesson list do not agree with each other, and neither is documented, so both
#: spellings of everything are accepted.
_LESSON_DATE = ("date", "datetime_from", "lesson_date", "date_from")
_LESSON_SUBJECT = ("subject_name", "subject", "name")
_LESSON_NUMBER = ("number", "lesson_number", "order", "position")
_LESSON_START = ("datetime_from", "time_from", "start_time", "time_start")
_LESSON_END = ("datetime_to", "time_to", "end_time", "time_end")
_LESSON_ROOM = ("office", "cabinet", "room", "place_name", "office_name")
_LESSON_TEACHER = ("teacher_name", "teacher", "teachers_name", "employee_name")
_LESSON_HOMEWORK = ("task", "homework", "hometask", "lesson_task", "task_name")
_LESSON_TOPIC = ("theme", "topic", "lesson_theme", "subject_theme")


def to_lessons(items: list[dict[str, Any]]) -> list[DiaryLesson]:
    lessons: list[DiaryLesson] = []
    for item in items:
        date = parse_date(pick(item, *_LESSON_DATE))
        subject = text(item, *_LESSON_SUBJECT)
        if date is None or subject is None:
            # Not an error: the endpoint returns rows for things that are not
            # lessons (an empty slot, a cancelled period), and a row this
            # cannot read is a row with nothing to draw.
            continue
        lessons.append(
            DiaryLesson(
                date=date,
                number=number(item, *_LESSON_NUMBER),
                subject=subject,
                starts_at=parse_time(pick(item, *_LESSON_START)),
                ends_at=parse_time(pick(item, *_LESSON_END)),
                room=text(item, *_LESSON_ROOM),
                teacher=text(item, *_LESSON_TEACHER),
                homework=text(item, *_LESSON_HOMEWORK),
                topic=text(item, *_LESSON_TOPIC),
            )
        )
    lessons.sort(key=lambda lesson: (lesson.date, lesson.number or 0, lesson.subject))
    return lessons


def to_homework(items: list[dict[str, Any]]) -> list[HomeworkItem]:
    """Homework, taken from the lesson rows that carry any.

    The upstream has no homework endpoint: homework is a field on a lesson.
    Pulling it out here is what lets the public API have the resource a client
    actually wants, without the client knowing where it came from.
    """
    homework: list[HomeworkItem] = []
    for item in items:
        task = text(item, *_LESSON_HOMEWORK)
        subject = text(item, *_LESSON_SUBJECT)
        date = parse_date(pick(item, *_LESSON_DATE))
        if not task or subject is None or date is None:
            continue
        homework.append(
            HomeworkItem(
                id=identity_id(item),
                due_date=date,
                subject=subject,
                text=task,
                teacher=text(item, *_LESSON_TEACHER),
            )
        )
    homework.sort(key=lambda entry: (entry.due_date, entry.subject))
    return homework


def to_attendance(items: list[dict[str, Any]]) -> list[AttendanceEvent]:
    events: list[AttendanceEvent] = []
    for item in items:
        at = parse_datetime(pick(item, "datetime", "date_time", "date"))
        if at is None:
            continue
        raw = (text(item, "direction") or "").lower()
        events.append(AttendanceEvent(at=at, direction="in" if raw.startswith("i") else "out"))
    events.sort(key=lambda event: event.at, reverse=True)
    return events
