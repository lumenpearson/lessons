"""The upstream's shapes, and what this project makes of them.

Every payload below is the real shape as far as it could be established from
open-source clients that talk to the Petersburg diary. Where it could not be -
lessons and the timetable are the two whose response bodies are not documented
anywhere public - the tests pin the *tolerance* instead: several spellings of
the same field, and a row that cannot be read being dropped rather than
raising.
"""

from __future__ import annotations

from datetime import date, time

from app.providers.petersburg import mapper as m
from app.providers.petersburg.models import MarkKind

TODAY = date(2026, 9, 12)


def child(**over):
    item = {
        "identity": {"id": 4021},
        "firstname": "Пётр",
        "surname": "Иванов",
        "middlename": "Сергеевич",
        "hash_uid": "a1b2c3",
        "educations": [
            {
                "education_id": 90210,
                "group_id": 771,
                "group_name": "9А",
                "institution_name": "ГБОУ СОШ № 1",
            }
        ],
    }
    item.update(over)
    return item


# ---- students -------------------------------------------------------------


def test_a_child_becomes_a_student_with_the_handles_later_calls_need():
    student = m.to_students([child()])[0]
    assert student.id == 4021
    assert student.full_name == "Иванов Пётр Сергеевич"
    assert student.school == "ГБОУ СОШ № 1"
    assert student.class_name == "9А"
    assert student.education_id == 90210
    assert student.group_id == 771


def test_a_child_with_no_education_row_is_skipped():
    """There is nothing later calls could ask about, so there is nothing to
    put on a screen either."""
    assert m.to_students([child(educations=[])]) == []
    assert m.to_students([child(educations=None)]) == []


def test_a_child_is_found_through_whichever_education_row_carries_a_handle():
    """Two education rows is a pupil who changed school, and the upstream does
    not promise which one comes first. Reading only ``educations[0]`` meant one
    unreadable entry took the whole child off the screen: a parent of one was
    told they have no children at all, which reads as the account being wrong."""
    assert m.to_students([child(educations=[None, {"education_id": 90210}])])[0].education_id == (
        90210
    )
    moved = m.to_students(
        [
            child(
                educations=[
                    {"group_name": "8А", "institution_name": "Старая школа"},
                    {"education_id": 90211, "group_name": "9А", "institution_name": "ГБОУ № 1"},
                ]
            )
        ]
    )
    assert [student.education_id for student in moved] == [90211]
    assert moved[0].class_name == "9А"


def test_a_child_with_no_identity_is_skipped():
    assert m.to_students([child(identity={})]) == []


def test_a_missing_middle_name_is_absent_rather_than_empty():
    student = m.to_students([child(middlename="")])[0]
    assert student.middle_name is None
    assert student.full_name == "Иванов Пётр"


# ---- periods --------------------------------------------------------------


def test_the_period_covering_today_is_the_current_one():
    items = [
        {
            "identity": {"id": 1},
            "name": "1 четверть",
            "date_from": "01.09.2026",
            "date_to": "26.10.2026",
        },
        {
            "identity": {"id": 2},
            "name": "2 четверть",
            "date_from": "05.11.2026",
            "date_to": "28.12.2026",
        },
    ]
    periods = m.to_periods(items, TODAY)
    assert [period.is_current for period in periods] == [True, False]
    assert periods[0].starts_on == date(2026, 9, 1)


def test_a_period_without_dates_is_kept_but_never_current():
    period = m.to_periods([{"identity": {"id": 3}, "name": "Год"}], TODAY)[0]
    assert period.is_current is False
    assert period.starts_on is None


# ---- marks ----------------------------------------------------------------


def mark(**over):
    item = {
        "id": 55,
        "date": "10.09.2026",
        "subject_id": 12,
        "subject_name": "Алгебра",
        "estimate_value_name": "5",
        "estimate_type_code": "10000",
        "estimate_type_name": "Ответ на уроке",
        "estimate_comment": None,
    }
    item.update(over)
    return item


def test_a_grade_keeps_its_number_and_is_named_a_grade():
    found = m.to_marks([mark()])[0]
    assert found.value == "5"
    assert found.kind is MarkKind.GRADE
    assert found.date == date(2026, 9, 10)
    assert found.reason == "Ответ на уроке"


def test_an_absence_is_not_a_mark_and_reads_as_one_letter():
    """Upstream sends absences in the same list as grades, told apart only by
    a numeric type code. Reading that code here is what stops every consumer
    from having to."""
    found = m.to_marks([mark(estimate_type_code="30000", estimate_value_name=None)])[0]
    assert found.value == "Н"
    assert found.kind is MarkKind.ABSENCE


def test_lateness_has_its_own_letter():
    found = m.to_marks([mark(estimate_type_code="30001", estimate_value_name=None)])[0]
    assert found.value == "О"
    assert found.kind is MarkKind.LATE


def test_a_remark_is_a_remark_rather_than_a_word_in_the_cell():
    found = m.to_marks([mark(estimate_value_name="Замечание")])[0]
    assert found.value == "!"
    assert found.kind is MarkKind.REMARK


def test_a_non_numeric_value_is_kept_verbatim_and_not_called_a_grade():
    found = m.to_marks([mark(estimate_value_name="зачёт")])[0]
    assert found.value == "зачёт"
    assert found.kind is MarkKind.OTHER


def test_marks_come_back_in_date_order():
    items = [mark(date="12.09.2026"), mark(date="09.09.2026"), mark(date="10.09.2026")]
    assert [found.date.day for found in m.to_marks(items)] == [9, 10, 12]


def test_a_mark_with_no_subject_is_dropped_rather_than_shown_unlabelled():
    assert m.to_marks([mark(subject_name=None)]) == []


# ---- lessons and homework -------------------------------------------------


def test_a_lesson_is_read_through_whichever_names_the_upstream_used():
    """The two lesson endpoints do not agree with each other and neither is
    documented, so both spellings are accepted."""
    first = m.to_lessons(
        [
            {
                "date": "14.09.2026",
                "subject_name": "Физика",
                "number": 3,
                "time_from": "10:25",
                "time_to": "11:10",
                "office": "305",
                "teacher_name": "Иванова И.И.",
                "task": "§12, № 3-5",
            }
        ]
    )[0]
    second = m.to_lessons(
        [
            {
                "datetime_from": "14.09.2026 10:25:00",
                "subject": "Физика",
                "lesson_number": 3,
                "cabinet": "305",
                "teacher": "Иванова И.И.",
                "homework": "§12, № 3-5",
            }
        ]
    )[0]
    for lesson in (first, second):
        assert lesson.date == date(2026, 9, 14)
        assert lesson.subject == "Физика"
        assert lesson.number == 3
        assert lesson.room == "305"
        assert lesson.teacher == "Иванова И.И."
        assert lesson.homework == "§12, № 3-5"
    assert first.starts_at == time(10, 25)
    assert second.starts_at == time(10, 25)


def test_a_field_the_upstream_wrapped_in_an_object_is_still_read():
    """The undocumented half of this API grows objects where strings were.

    ``"subject": "Физика"`` turning into ``"subject": {"id": 7, "name":
    "Физика"}`` is not a broken row - it is the same lesson, one rename later.
    Every reader here takes a string or a number, so before the object was
    looked into, that rename dropped the subject, and a lesson with no subject
    is dropped whole: a week of lessons became an empty week, on the phone and
    in the card, with nothing in the log to say why.
    """
    lesson = m.to_lessons(
        [
            {
                "date": {"value": "14.09.2026"},
                "subject": {"id": 7, "name": "Физика"},
                "number": {"value": 3},
                "office": {"name": "305"},
                "teacher": {"fullname": "Иванова И.И."},
                "task": {"text": "§12, № 3-5"},
            }
        ]
    )[0]
    assert lesson.date == date(2026, 9, 14)
    assert lesson.subject == "Физика"
    assert lesson.number == 3
    assert lesson.room == "305"
    assert lesson.teacher == "Иванова И.И."
    assert lesson.homework == "§12, № 3-5"


def test_an_object_with_no_scalar_in_it_is_still_just_unreadable():
    """The unwrapping is one level and only to a scalar. A deeper walk would
    have to guess which nested string was meant, and a guess here puts a
    teacher's surname in the room column."""
    assert m.to_lessons([{"date": "14.09.2026", "subject": {"parts": ["Физика"]}}]) == []


def test_a_whole_batch_nobody_could_read_is_logged_rather_than_answered_empty(caplog):
    """One unreadable row is a bad row; all of them is a shape change.

    Dropping what cannot be read is deliberate, and that is exactly why the
    total loss has to say something: an empty week from a renamed field and an
    empty week from a holiday are the same answer to every caller above this
    file. The keys are in the line because they are what the next spelling in
    ``_LESSON_SUBJECT`` and friends has to be.
    """
    items = [
        {"lessonDate": "14.09.2026", "subjectTitle": "Физика"},
        {"lessonDate": "15.09.2026", "subjectTitle": "Алгебра"},
    ]
    with caplog.at_level("WARNING"):
        assert m.to_lessons(items) == []

    assert "2 lesson row(s) in, none readable" in caplog.text
    assert "lessonDate, subjectTitle" in caplog.text


def test_an_ordinary_empty_answer_is_not_reported_as_a_shape_change(caplog):
    """No rows at all is a week with no lessons in it, which is not news."""
    with caplog.at_level("WARNING"):
        assert m.to_lessons([]) == []
        assert m.to_marks([]) == []
    assert caplog.text == ""


def test_a_row_that_is_not_a_lesson_is_skipped_rather_than_failing_the_week():
    items = [
        {"subject_name": "Физика"},  # no date
        {"date": "14.09.2026"},  # no subject
        {"date": "14.09.2026", "subject_name": "Алгебра"},
    ]
    lessons = m.to_lessons(items)
    assert [lesson.subject for lesson in lessons] == ["Алгебра"]


def test_homework_is_lifted_out_of_the_lessons_that_carry_it():
    """There is no homework endpoint upstream: homework is a field on a
    lesson. Lifting it here is what lets the API have the resource clients
    actually ask for."""
    items = [
        {"date": "15.09.2026", "subject_name": "Алгебра", "task": "№ 42"},
        {"date": "15.09.2026", "subject_name": "История"},
        {"date": "14.09.2026", "subject_name": "Физика", "task": " §12 "},
    ]
    homework = m.to_homework(items)
    assert [(item.due_date.day, item.subject, item.text) for item in homework] == [
        (14, "Физика", "§12"),
        (15, "Алгебра", "№ 42"),
    ]


# ---- teachers, attendance -------------------------------------------------


def test_a_teacher_name_is_assembled_from_the_three_parts():
    teacher = m.to_teachers(
        [
            {
                "identity": {"id": 9},
                "surname": "Петрова",
                "firstname": "Анна",
                "middlename": "Львовна",
                "position_name": "Учитель",
                "subjects": ["Алгебра", {"subject_name": "Геометрия"}],
            }
        ]
    )[0]
    assert teacher.name == "Петрова Анна Львовна"
    assert teacher.subjects == ["Алгебра", "Геометрия"]
    assert teacher.position == "Учитель"


def test_attendance_is_newest_first_and_its_direction_is_one_word():
    events = m.to_attendance(
        [
            {"identity": {"id": 1}, "direction": "input", "datetime": "12.09.2026 08:21:00"},
            {"identity": {"id": 2}, "direction": "output", "datetime": "12.09.2026 14:02:00"},
        ]
    )
    assert [event.direction for event in events] == ["out", "in"]
    assert events[0].at.hour == 14


def test_an_undated_turnstile_row_is_dropped():
    assert m.to_attendance([{"direction": "input"}]) == []


def test_the_russian_spelling_of_a_direction_is_read_as_the_direction_it_is():
    """«вход» and «выход» differ by two letters and mean opposite things. The
    field has been seen in English; an undocumented API translated once has
    been translated twice, and «выход» matched on its first letter reads as a
    way in."""
    events = m.to_attendance(
        [
            {"direction": "Вход", "datetime": "12.09.2026 08:21:00"},
            {"direction": "ВЫХОД", "datetime": "12.09.2026 14:02:00"},
        ]
    )
    assert [event.direction for event in events] == ["out", "in"]


def test_a_direction_this_code_does_not_know_is_not_reported_as_leaving():
    """The reader of this row is a parent asking whether their child is in the
    building. A word nobody here has seen — a third state, a new translation,
    an empty field — answered that «ушёл», confidently and possibly wrongly.
    «unknown» is a third answer the screen can give honestly."""
    events = m.to_attendance(
        [
            {"direction": "проход", "datetime": "12.09.2026 08:21:00"},
            {"direction": "", "datetime": "12.09.2026 09:00:00"},
            {"datetime": "12.09.2026 10:00:00"},
        ]
    )
    assert [event.direction for event in events] == ["unknown", "unknown", "unknown"]


# ---- parsing --------------------------------------------------------------


def test_dates_are_read_in_every_format_the_upstream_has_used():
    assert m.parse_date("15.09.2026") == date(2026, 9, 15)
    assert m.parse_date("2026-09-15") == date(2026, 9, 15)
    assert m.parse_date("15.09.2026 10:25:00") == date(2026, 9, 15)
    assert m.parse_date("не дата") is None
    assert m.parse_date(None) is None


def test_a_time_is_read_out_of_either_spelling_of_a_datetime():
    """The lesson endpoints carry the start inside a whole datetime, and the
    two spellings are «14.09.2026 10:25:00» and the ISO «2026-09-14T10:25:00».
    parse_date takes either; a time that took only the first left every lesson
    of an ISO-speaking endpoint with a date and a blank where the bell is."""
    assert m.parse_time("10:25") == time(10, 25)
    assert m.parse_time("14.09.2026 10:25:00") == time(10, 25)
    assert m.parse_time("2026-09-14T10:25:00") == time(10, 25)
    assert m.parse_time("не время") is None
    assert m.parse_time(None) is None

    lesson = m.to_lessons(
        [{"datetime_from": "2026-09-14T10:25:00", "datetime_to": "2026-09-14T11:10:00",
          "subject": "Физика"}]
    )[0]
    assert (lesson.starts_at, lesson.ends_at) == (time(10, 25), time(11, 10))


def test_identity_id_reads_both_shapes():
    assert m.identity_id({"identity": {"id": 7}}) == 7
    assert m.identity_id({"id": 7}) == 7
    assert m.identity_id({"identity": {}}) is None


def test_a_wrapper_spelled_the_way_this_api_spells_it_is_read_as_a_name():
    """`_WRAPPED` ended at `id`, and this API writes `subject_name`.

    A wrapped `{"id": 12, "subject_name": "Алгебра"}` therefore fell through
    every spelling in the list and landed on the id — and because the row
    *was* readable, nothing was dropped and `note_if_nothing_read` never
    fired. The family read a week of lessons called «12», in rooms called
    «3», with «9» as the homework. A correction filed against that week is
    filed under «12» and survives the fix, landing on nothing.
    """
    (lesson,) = m.to_lessons(
        [
            {
                "date": "15.09.2026",
                "number": 3,
                "subject": {"id": 12, "subject_name": "Алгебра"},
                "teacher": {"id": 55, "teacher_name": "Иванова А. А."},
                "office": {"id": 3, "office_name": "301"},
                "task": {"id": 9, "task_name": "§14, упр. 5"},
            }
        ]
    )
    assert lesson.subject == "Алгебра"
    assert lesson.teacher == "Иванова А. А."
    assert lesson.room == "301"
    assert lesson.homework == "§14, упр. 5"


def test_a_boolean_is_not_read_as_a_number():
    """`bool` is an `int` in Python, so `{"homework": false}` — a shape a JSON
    API produces without meaning anything by it — was read as the number
    `False` and shown to the family as the word «False»."""
    (lesson,) = m.to_lessons(
        [{"date": "15.09.2026", "number": 1, "subject": "Алгебра", "homework": False}]
    )
    assert lesson.homework is None


def test_a_number_python_cannot_convert_drops_the_field_rather_than_the_screen():
    """«²» is a digit by `isdigit` and not a decimal, and `int('²')` raises.

    The raise is a `ValueError`, which is not a `PetersburgError` — so it walks
    straight past `api/diary._guard` (a bare 500 on the phone) and past
    `bot/handlers/diary._stumble`, whose `except PetersburgError` never fires,
    leaving the button spinning for ever with nothing said. One superscript in
    one lesson row therefore took the whole «Дневник» screen, instead of that
    one field being dropped.

    `config.py`'s `owner_id_list` is the same bug, found first and fixed with
    `isdecimal`; this line is where the lesson had not been applied.
    """
    (lesson,) = m.to_lessons([{"date": "15.09.2026", "subject_name": "Алгебра", "number": "²"}])
    assert lesson.number is None
    assert lesson.subject == "Алгебра"


def test_a_child_survives_an_education_id_no_conversion_can_read():
    """The same line, reached from the other side: an unreadable `education_id`
    must read as «this row has no handle» — the case `to_students` already
    walks past — and not as an exception out of the children list, which is the
    first screen the diary draws."""
    students = m.to_students(
        [
            child(
                educations=[
                    {"education_id": "²"},
                    {"education_id": 90210, "group_name": "9А"},
                ]
            )
        ]
    )
    assert [student.education_id for student in students] == [90210]


def test_a_number_too_long_for_python_to_convert_is_dropped_too():
    """Above 4300 digits `int()` raises `ValueError` on a string that is
    decimal all the way along, so no test of the characters can see it coming —
    only the conversion itself can, and it has to be the one that answers."""
    enormous = "9" * 4301
    (lesson,) = m.to_lessons(
        [{"date": "15.09.2026", "subject_name": "Алгебра", "number": enormous}]
    )
    assert lesson.number is None
    assert m.number({"id": enormous}, "id") is None
