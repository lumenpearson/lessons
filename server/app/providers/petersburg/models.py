"""Petersburg's model names, which are now the shared diary models.

This module pre-dates :mod:`app.providers.diary.models`. Every model it once
defined lives there now and is re-exported here unchanged — the same classes,
so ``isinstance`` holds across both spellings and nothing that imports
``app.providers.petersburg.models`` breaks. New code should import from
:mod:`app.providers.diary.models`.

``DiaryAccount`` used to live here and had no reader; it is gone (#138).
"""

from __future__ import annotations

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

__all__ = [
    "AcademicPeriod",
    "AttendanceEvent",
    "DiaryLesson",
    "HomeworkItem",
    "Mark",
    "MarkKind",
    "Student",
    "Subject",
    "Teacher",
]
