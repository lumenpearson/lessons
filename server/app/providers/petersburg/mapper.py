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


#: What the scalar is called when the upstream wraps a field in an object.
#: The other way this API changes is exactly this: ``"subject": "Алгебра"``
#: becomes ``"subject": {"id": 7, "name": "Алгебра"}``, and the reader that
#: expected a string sees a dict, fails its isinstance check and answers None.
#: One such field is the difference between a week of lessons and an empty
#: week, with no error anywhere - so the object is looked into rather than
#: refused.
#:
#: ``id`` is last and is a last resort — and it is why the ``*_name``
#: spellings above it are named one by one. This API writes `subject_name`,
#: `teacher_name`, `office_name` and `task_name` at the *top* level, so those
#: are the likeliest spellings inside a wrapper too; without them a wrapped
#: `{"id": 12, "subject_name": "Алгебра"}` fell through to the id, and the
#: family read a week of lessons called «12» in rooms called «3», with nothing
#: logged because the row *was* readable and `note_if_nothing_read` never
#: fired. A correction filed against that week is filed under «12» and
#: survives the fix, landing on nothing.
_WRAPPED = (
    "name",
    "title",
    "value",
    "text",
    "short_name",
    "fullname",
    "subject_name",
    "teacher_name",
    "office_name",
    "task_name",
    "id",
)


def unwrap(value: Any) -> Any:
    """The scalar inside a one-level object, or the value unchanged.

    Only one level, and only a scalar: a deeper walk would start guessing which
    of several nested strings is the one meant, and guessing wrong here puts a
    teacher's surname where a room number belongs.
    """
    if not isinstance(value, dict):
        return value
    for name in _WRAPPED:
        inner = value.get(name)
        if isinstance(inner, bool):
            continue
        if isinstance(inner, str) and inner.strip():
            return inner
        if isinstance(inner, int | float):
            return inner
    return None


def pick(source: dict[str, Any], *names: str) -> Any:
    """First present, non-empty value among ``names``.

    Several names rather than one because the upstream is inconsistent about
    them between endpoints - a room is ``office`` in one response and
    ``cabinet`` in another - and because a rename upstream should cost a name
    in this tuple, not an outage.
    """
    for name in names:
        value = unwrap(source.get(name))
        if value not in (None, "", [], {}):
            return value
    return None


def note_if_nothing_read(kind: str, items: list[Any], mapped: list[Any]) -> None:
    """Say so when a whole batch read as nothing.

    Dropping the row that cannot be read is right - a week of marks is worth
    more than an error page - but *every* row unreadable is not a bad row, it
    is a shape this file no longer recognises, and silence is the one answer
    that looks exactly like a quiet week. The keys are logged because they are
    what the next name in a tuple above has to be.
    """
    if not items or mapped:
        return
    keys = sorted({key for item in items if isinstance(item, dict) for key in item})
    log.warning(
        "petersburg: %d %s row(s) in, none readable; keys seen: %s",
        len(items),
        kind,
        ", ".join(keys[:20]) or "(no dict rows at all)",
    )


def text(source: dict[str, Any], *names: str) -> str | None:
    value = pick(source, *names)
    if isinstance(value, str):
        stripped = value.strip()
        return stripped or None
    # `bool` is an `int` in Python, and `{"homework": false}` is a shape a JSON
    # API produces without meaning anything by it. Read as a number it becomes
    # the word «False», shown to the family as the assignment.
    if isinstance(value, bool):
        return None
    if isinstance(value, int | float):
        return str(value)
    return None


def number(source: dict[str, Any], *names: str) -> int | None:
    value = pick(source, *names)
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        candidate = value.strip()
        # `isdecimal`, not `isdigit`: they differ on exactly the characters
        # `int()` refuses, and this project has already paid for that
        # distinction once — `config.py`'s `owner_id_list` waved «²» through an
        # `isdigit` filter and the raise took down the very reporter meant to
        # explain it. The lesson was not applied here. A `ValueError` is not a
        # `PetersburgError`, so one superscript in one lesson number walks past
        # `api/diary._guard` as a bare 500 and past `bot/handlers/diary._stumble`
        # as a button that spins for ever with nothing said — instead of that
        # one row being dropped, which is what this file promises.
        if not candidate.lstrip("-").isdecimal():
            return None
        try:
            return int(candidate)
        except ValueError:
            # Python refuses to convert more than 4300 digits, and no test of
            # the characters can see that coming: the string is decimal all the
            # way along. «--5» arrives here too, for the same reason.
            return None
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
        # A lesson's start arrives inside a whole datetime as often as on its
        # own, and that datetime comes in two spellings: «14.09.2026 10:25:00»
        # and the ISO «2026-09-14T10:25:00». parse_date and parse_datetime both
        # take either; this one used to take only the first, so an endpoint
        # answering in ISO gave every lesson a date and no time at all - a card
        # with a blank where the bell is, and nothing logged to say why.
        candidate = candidate.replace("T", " ")
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
        # so there is nothing to show and nothing to select. "No education row"
        # means no *usable* one, though, not "the first one is unusable": a
        # pupil who changed school carries two, and one unreadable entry in
        # that list used to take the whole child off the screen - a parent with
        # one child was told they have none, which reads as the account being
        # wrong rather than as one row the upstream sent oddly.
        educations = item.get("educations")
        education = None
        education_id = None
        for candidate in educations if isinstance(educations, list) else ():
            if not isinstance(candidate, dict):
                continue
            found = number(candidate, "education_id", "id")
            if found is not None:
                education, education_id = candidate, found
                break
        if education is None or education_id is None:
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
    note_if_nothing_read("student", items, students)
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
    note_if_nothing_read("period", items, periods)
    return periods


def to_subjects(items: list[dict[str, Any]]) -> list[Subject]:
    subjects: list[Subject] = []
    for item in items:
        name = text(item, "subject_name", "name", "title")
        if name is None:
            continue
        subjects.append(Subject(id=number(item, "subject_id") or identity_id(item), name=name))
    note_if_nothing_read("subject", items, subjects)
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
    note_if_nothing_read("teacher", items, teachers)
    return teachers


# ---------------------------------------------------------------------------
# Marks
# ---------------------------------------------------------------------------


def classify_mark(item: dict[str, Any]) -> tuple[str, MarkKind]:
    """What the cell should say, and what it actually is.

    The upstream puts grades, absences, lateness and remarks in one list and
    distinguishes them by ``estimate_type_code``. Reading that code here means
    no consumer ever has to: they get «Н» and :attr:`MarkKind.ABSENCE`.
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
    note_if_nothing_read("mark", items, marks)
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
    note_if_nothing_read("lesson", items, lessons)
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


#: The spellings of a turnstile direction that have actually been seen, and the
#: two languages they arrive in. ``input``/``output`` is what the endpoint sends
#: today; the Russian pair is what their own screens show, and an undocumented
#: API that is translated once has been translated twice.
#:
#: Prefixes rather than equality because the field has carried «Вход в здание»
#: as readily as «вход» - and matched on the *first* letter it would have read
#: «выход» as a way in, which is the one mistake this table exists to prevent.
_DIRECTION_IN = ("in", "вход", "приход")
_DIRECTION_OUT = ("out", "вых", "уход")


def _direction(raw: str) -> str:
    """``in``, ``out``, or ``unknown`` for a word this code does not know.

    Not defaulting to ``out``. A turnstile row is read by a parent checking
    whether their child is in the building, and the previous default answered
    that question confidently and possibly wrongly: a spelling nobody here has
    seen - a new translation, a third state like «отказ» - rendered as the
    child *leaving*. A row that says «непонятно» is worth something, because
    the reader can go and look; a row that says the wrong thing is worth less
    than nothing.
    """
    word = raw.strip().lower()
    if word.startswith(_DIRECTION_IN):
        return "in"
    if word.startswith(_DIRECTION_OUT):
        return "out"
    if word:
        # Worth a line: it is how a new spelling is noticed before somebody
        # asks why the turnstile stopped saying anything.
        log.info("petersburg: unknown turnstile direction %r", raw)
    return "unknown"


def to_attendance(items: list[dict[str, Any]]) -> list[AttendanceEvent]:
    events: list[AttendanceEvent] = []
    for item in items:
        at = parse_datetime(pick(item, "datetime", "date_time", "date"))
        if at is None:
            continue
        events.append(
            AttendanceEvent(at=at, direction=_direction(text(item, "direction") or ""))
        )
    note_if_nothing_read("attendance", items, events)
    events.sort(key=lambda event: event.at, reverse=True)
    return events
