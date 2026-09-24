"""The «Сетевой город» mapper, on hand-written payloads.

No network and no live server: every shape here is written by hand from the
open clients (`docs/diaries/netschool.md`), which is the only evidence there
is. The point is that our models come out right, and that a single bad row is
dropped rather than raising.
"""

from __future__ import annotations

from datetime import date

from app.providers.diary.models import MarkKind
from app.providers.netschool import mapper as m

# One week's /webapi/student/diary, trimmed to what the mapper reads.
WEEK = {
    "className": "6г",
    "termName": "2 четверть",
    "weekDays": [
        {
            "date": "2026-11-30T00:00:00",
            "lessons": [
                {
                    "number": 1,
                    "subjectName": "Алгебра",
                    "startTime": "09:00",
                    "endTime": "09:45",
                    "room": "312",
                    "assignments": [
                        {"id": 5, "typeId": 3, "assignmentName": "§14, №3-5",
                         "dueDate": "2026-12-02T00:00:00"},
                        {"id": 6, "typeId": 10, "assignmentName": "Ответ у доски",
                         "mark": {"mark": 5, "dutyMark": False},
                         "markComment": {"name": "Отлично"}},
                    ],
                },
                {
                    "number": 2,
                    "subjectName": "",  # empty subject: the row is hidden
                    "assignments": [],
                },
                {
                    "number": 3,
                    "subjectName": "Химия",
                    "startTime": "10:00:00",  # HH:MM:SS form
                    "endTime": "10:45:00",
                    "assignments": [
                        {"id": 7, "typeId": 4, "assignmentName": "К/р",
                         "mark": {"dutyMark": True}},  # a debt, «·», not a grade
                    ],
                },
            ],
        }
    ],
}

TYPES = {"3": "Домашнее задание", "4": "Контрольная работа", "10": "Ответ на уроке"}


def test_lessons_drop_the_empty_subject_and_read_both_time_forms():
    lessons = m.to_lessons(WEEK, date(2026, 11, 30), date(2026, 12, 6))
    assert [x.subject for x in lessons] == ["Алгебра", "Химия"]
    algebra, chem = lessons
    assert algebra.number == 1
    assert algebra.starts_at.isoformat() == "09:00:00"
    assert algebra.room == "312"
    assert algebra.homework == "§14, №3-5"  # only typeId==3
    assert chem.starts_at.isoformat() == "10:00:00"


def test_homework_is_addressed_to_its_due_day():
    hw = m.to_homework(WEEK, date(2026, 11, 30), date(2026, 12, 6))
    assert len(hw) == 1
    assert hw[0].due_date == date(2026, 12, 2)
    assert hw[0].subject == "Алгебра"
    assert hw[0].text == "§14, №3-5"


def test_marks_read_grade_and_duty_and_name_the_reason():
    marks = m.to_marks(WEEK, TYPES, date(2026, 11, 30), date(2026, 12, 6))
    assert len(marks) == 2
    grade, duty = marks
    assert (grade.value, grade.kind, grade.reason) == ("5", MarkKind.GRADE, "Ответ на уроке")
    assert grade.comment == "Отлично"
    assert (duty.value, duty.kind) == ("·", MarkKind.OTHER)


def test_a_week_is_filtered_to_the_requested_range():
    # Ask only for 1 December: the 30 November rows fall outside.
    assert m.to_lessons(WEEK, date(2026, 12, 1), date(2026, 12, 6)) == []


def test_students_keep_the_whole_display_name_and_accept_a_dict():
    as_list = {"students": [{"studentId": 11, "nickName": "Иванов Иван", "classId": 3,
                             "className": None}], "currentStudentId": 11}
    as_dict = {"students": {"0": {"studentId": 11, "nickName": "Иванов Иван", "classId": 3}}}
    for payload in (as_list, as_dict):
        students = m.to_students(payload, "Школа № 1")
        assert len(students) == 1
        assert students[0].id == 11 == students[0].education_id
        assert students[0].group_id == 3
        assert students[0].full_name == "Иванов Иван"
        assert students[0].school == "Школа № 1"


def test_a_wholly_unreadable_children_answer_raises():
    import pytest

    with pytest.raises(m.UnexpectedResponseError):
        m.to_students({"students": "nonsense"}, None)


def test_periods_mark_the_term_holding_today():
    terms = [
        {"id": 1, "termName": "1 четверть", "startDate": "2026-09-01", "endDate": "2026-10-26"},
        {"id": 2, "termName": "2 четверть", "startDate": "2026-11-05", "endDate": "2026-12-28"},
    ]
    periods = m.to_periods(terms, date(2026, 11, 30))
    current = [p for p in periods if p.is_current]
    assert len(current) == 1 and current[0].id == 2


def test_attendance_is_always_empty():
    assert m.to_attendance() == []
