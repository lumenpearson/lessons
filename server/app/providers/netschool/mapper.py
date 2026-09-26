"""«Сетевой город» JSON → the shared diary models.

Lenient in, strict out, like Petersburg's mapper: a single unreadable row is
dropped with a warning, and only a wholly unreadable answer raises
:class:`UnexpectedResponse`. The upstream field names are documented in
`docs/diaries/netschool.md` and were read from the open clients, not a live
server, so this file is the one place they live.

Homework is the assignments of ``typeId == 3`` — which is how the «Сетевой
город» web diary itself tells homework from classwork. A mark's ``dutyMark`` is
a debt («·»), drawn instead of a number, not an absence: this platform's
absences live only in the SignalR reports, which batch 1 does not read, so
:func:`to_marks` never yields an ABSENCE or a LATE and :func:`to_attendance`
is always empty.
"""

from __future__ import annotations

import logging
from datetime import date as Date
from datetime import time as Time
from typing import Any

from app.providers.diary.models import (
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

HOMEWORK_TYPE_ID = 3


def _text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _parse_date(raw: Any) -> Date | None:
    """A naive ISO date or datetime (``2026-09-15`` or ``…T00:00:00``)."""
    text = _text(raw)
    if not text:
        return None
    try:
        return Date.fromisoformat(text[:10])
    except ValueError:
        return None


def _parse_time(raw: Any) -> Time | None:
    """``HH:MM`` or ``HH:MM:SS`` — the two forms the clients see."""
    text = _text(raw)
    if not text:
        return None
    for fmt_len in (5, 8):
        try:
            return Time.fromisoformat(text[:fmt_len])
        except ValueError:
            continue
    return None


def _json_int(value: Any) -> int | None:
    """A JSON integer, or ``None`` — never a bool.

    `bool` is an `int` in Python, so a JSON ``true`` passes
    ``isinstance(value, int)`` as 1. A pupil's id is half of the key the
    child's corrections are filed under (`services/diary.child_scope`), so a
    ``true`` read as pupil 1 would lay pupil 1's corrections over another
    child. Petersburg's `number` refuses a bool too, and the client's
    `_json_int` is the same rule — not imported, because the client brings the
    HTTP module with it.
    """
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def to_students(init: dict[str, Any], school: str | None) -> list[Student]:
    """``student/diary/init`` → the pupils this account may see.

    ``students`` arrives as a list, or on some servers as an index-keyed dict;
    both are accepted. A child's only name is ``nickName``, one display string,
    so it is kept whole rather than split wrongly.
    """
    raw = init.get("students")
    if isinstance(raw, dict):
        raw = list(raw.values())
    if not isinstance(raw, list):
        raise UnexpectedResponseError
    students: list[Student] = []
    for row in raw:
        if not isinstance(row, dict):
            continue
        sid = _json_int(row.get("studentId"))
        if sid is None:
            continue
        nick = _text(row.get("nickName")) or ""
        students.append(
            Student(
                id=sid,
                last_name=nick,
                first_name="",
                school=school,
                class_name=_text(row.get("className")),
                education_id=sid,
                group_id=_json_int(row.get("classId")),
            )
        )
    if raw and not students:
        raise UnexpectedResponseError
    return students


def _days(diary: dict[str, Any]) -> list[dict[str, Any]]:
    days = diary.get("weekDays")
    return [d for d in days if isinstance(d, dict)] if isinstance(days, list) else []


def _lessons(day: dict[str, Any]) -> list[dict[str, Any]]:
    lessons = day.get("lessons")
    return [x for x in lessons if isinstance(x, dict)] if isinstance(lessons, list) else []


def _week_class_name(diary: dict[str, Any]) -> str | None:
    return _text(diary.get("className"))


def to_lessons(
    diary: dict[str, Any], date_from: Date, date_to: Date
) -> list[DiaryLesson]:
    """One week's ``student/diary`` → lessons within ``[date_from, date_to]``.

    A lesson with an empty ``subjectName`` is dropped: the SGO web diary hides
    those rows too. Homework on the lesson (``typeId == 3``) is joined into the
    lesson's ``homework``; the teacher and the topic are not in this route, so
    they stay ``None``.
    """
    out: list[DiaryLesson] = []
    for day in _days(diary):
        when = _parse_date(day.get("date"))
        if when is None or not (date_from <= when <= date_to):
            continue
        for lesson in _lessons(day):
            subject = _text(lesson.get("subjectName"))
            if not subject:
                continue
            homework = _homework_text(lesson)
            out.append(
                DiaryLesson(
                    date=when,
                    number=lesson.get("number") if isinstance(lesson.get("number"), int) else None,
                    subject=subject,
                    starts_at=_parse_time(lesson.get("startTime")),
                    ends_at=_parse_time(lesson.get("endTime")),
                    room=_text(lesson.get("room")),
                    homework=homework,
                )
            )
    return out


def _assignments(lesson: dict[str, Any]) -> list[dict[str, Any]]:
    items = lesson.get("assignments")
    return [a for a in items if isinstance(a, dict)] if isinstance(items, list) else []


def _homework_text(lesson: dict[str, Any]) -> str | None:
    parts = [
        _text(a.get("assignmentName"))
        for a in _assignments(lesson)
        if a.get("typeId") == HOMEWORK_TYPE_ID
    ]
    parts = [p for p in parts if p]
    return "; ".join(parts) if parts else None


def to_homework(
    diary: dict[str, Any], date_from: Date, date_to: Date
) -> list[HomeworkItem]:
    """Homework as its own resource, addressed to the day it is due."""
    out: list[HomeworkItem] = []
    for day in _days(diary):
        lesson_day = _parse_date(day.get("date"))
        for lesson in _lessons(day):
            subject = _text(lesson.get("subjectName"))
            if not subject:
                continue
            for a in _assignments(lesson):
                if a.get("typeId") != HOMEWORK_TYPE_ID:
                    continue
                text = _text(a.get("assignmentName"))
                if not text:
                    continue
                due = _parse_date(a.get("dueDate")) or lesson_day
                if due is None or not (date_from <= due <= date_to):
                    continue
                out.append(
                    HomeworkItem(
                        id=a.get("id") if isinstance(a.get("id"), int) else None,
                        due_date=due,
                        subject=subject,
                        text=text,
                    )
                )
    return out


def to_marks(
    diary: dict[str, Any], types: dict[str, str], date_from: Date, date_to: Date
) -> list[Mark]:
    """Marks from one week's diary. ``dutyMark`` is a debt («·»), not a grade."""
    out: list[Mark] = []
    for day in _days(diary):
        when = _parse_date(day.get("date"))
        if when is None or not (date_from <= when <= date_to):
            continue
        for lesson in _lessons(day):
            subject = _text(lesson.get("subjectName")) or ""
            for a in _assignments(lesson):
                mark = a.get("mark")
                if not isinstance(mark, dict):
                    continue
                if mark.get("dutyMark"):
                    value, kind = "·", MarkKind.OTHER
                elif isinstance(mark.get("mark"), int):
                    value, kind = str(mark["mark"]), MarkKind.GRADE
                else:
                    continue
                comment = a.get("markComment")
                out.append(
                    Mark(
                        id=a.get("id") if isinstance(a.get("id"), int) else None,
                        subject_name=subject,
                        date=when,
                        value=value,
                        kind=kind,
                        reason=types.get(str(a.get("typeId"))),
                        comment=_text(comment.get("name")) if isinstance(comment, dict) else None,
                    )
                )
    return out


def to_periods(
    terms: Any, today: Date, week_term_name: str | None = None
) -> list[AcademicPeriod]:
    """``terms/search`` → the year's terms. ``is_current`` is derived: the term
    holding today, ties broken by the diary week's ``termName``, else the one
    whose window is shortest."""
    if not isinstance(terms, list):
        return []
    periods: list[AcademicPeriod] = []
    for row in terms:
        if not isinstance(row, dict):
            continue
        tid = row.get("id")
        name = _text(row.get("termName"))
        if not isinstance(tid, int) or not name:
            continue
        periods.append(
            AcademicPeriod(
                id=tid,
                name=name,
                starts_on=_parse_date(row.get("startDate")),
                ends_on=_parse_date(row.get("endDate")),
            )
        )
    holding = [
        p for p in periods
        if p.starts_on and p.ends_on and p.starts_on <= today <= p.ends_on
    ]
    current: AcademicPeriod | None = None
    if len(holding) == 1:
        current = holding[0]
    elif holding:
        by_name = [p for p in holding if week_term_name and p.name == week_term_name]
        current = by_name[0] if by_name else min(
            holding, key=lambda p: (p.ends_on - p.starts_on)  # type: ignore[operator]
        )
    if current is not None:
        current.is_current = True
    return periods


def to_subjects(diary: dict[str, Any]) -> list[Subject]:  # noqa: ARG001
    """Not served in batch 1: the diary carries no subject list of its own."""
    return []


def to_teachers(diary: dict[str, Any]) -> list[Teacher]:  # noqa: ARG001
    """Not served in batch 1: the diary route carries no teacher."""
    return []


def to_attendance() -> list[AttendanceEvent]:
    """«Сетевой город» has no turnstile records at all."""
    return []


class UnexpectedResponseError(Exception):
    """Raised by the mappers on a wholly unreadable answer; the connection
    turns it into the shared :class:`UnexpectedResponse`. Kept private so the
    mapper stays free of the error module and testable on its own."""
