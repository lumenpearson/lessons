"""Corrections laid over the diary, without a database or an upstream.

``services/diary_overrides`` is free of SQLAlchemy and of FastAPI on purpose,
so this runs in milliseconds and pins the part that is this project's policy:
what a correction is addressed to, what may be corrected at all, and what
happens when the diary changes underneath one.
"""

from __future__ import annotations

from datetime import date

import pytest

from app.providers.petersburg.models import DiaryLesson, HomeworkItem
from app.services import diary_overrides as ov

MONDAY = date(2026, 9, 7)


def lesson(**kwargs) -> DiaryLesson:
    base = {"date": MONDAY, "number": 1, "subject": "Алгебра"}
    return DiaryLesson(**(base | kwargs))


def homework(**kwargs) -> HomeworkItem:
    base = {"due_date": MONDAY, "subject": "Алгебра", "text": "№ 42"}
    return HomeworkItem(**(base | kwargs))


def correction(value: str, was: str | None = None) -> tuple[str, str | None]:
    return (value, was)


# ---- what a correction is addressed to ------------------------------------


def test_a_lesson_is_addressed_by_day_number_and_subject():
    assert ov.lesson_target(lesson()) == "lesson:2026-09-07:n1:Алгебра"


def test_a_lesson_with_no_number_keeps_its_place_in_the_key():
    """The empty slot stays, so a numbered and an unnumbered lesson of the same
    subject on the same day cannot collapse onto one key."""
    assert ov.lesson_target(lesson(number=None)) == "lesson:2026-09-07::Алгебра"
    assert ov.lesson_target(lesson(number=None)) != ov.lesson_target(lesson())


def test_a_double_lesson_is_two_targets_because_the_number_differs():
    first = ov.lesson_target(lesson(number=3))
    second = ov.lesson_target(lesson(number=4))
    assert first != second


def test_homework_uses_its_upstream_id_when_it_has_one():
    assert ov.homework_target(homework(id=77)) == "hw:id:77"


def test_homework_without_an_id_falls_back_to_the_day_and_subject():
    assert ov.homework_target(homework()) == "hw:2026-09-07:Алгебра"


# ---- what may be corrected ------------------------------------------------


@pytest.mark.parametrize("field", sorted(ov.LESSON_FIELDS))
def test_every_lesson_field_in_the_set_is_accepted(field):
    ov.check("lesson:2026-09-07:n1:Алгебра", field)


def test_a_lesson_subject_is_not_correctable_because_it_is_half_the_key():
    with pytest.raises(ov.UnsupportedField):
        ov.check("lesson:2026-09-07:n1:Алгебра", "subject")


def test_a_mark_is_not_correctable_at_all():
    """A grade is a claim about what happened. An app that let a family rewrite
    one would be producing a false record that looks official."""
    with pytest.raises(ov.UnknownTarget):
        ov.check("mark:id:5", "value")


def test_a_target_this_server_would_never_produce_is_refused():
    with pytest.raises(ov.UnknownTarget):
        ov.check("something-else", "text")
    with pytest.raises(ov.UnknownTarget):
        ov.check("lesson", "room")


def test_an_oversized_target_is_refused_rather_than_truncated():
    """Truncating would let two different lessons land on one key."""
    with pytest.raises(ov.UnknownTarget):
        ov.check("hw:2026-09-07:" + "я" * ov.MAX_TARGET_LENGTH, "text")


# ---- applying them --------------------------------------------------------


def test_a_correction_replaces_the_value_and_says_what_it_replaced():
    lessons = [lesson(room="12")]
    result = ov.overlay_lessons(
        lessons, {"lesson:2026-09-07:n1:Алгебра": {"room": correction("204", "12")}}
    )

    assert result[0].lesson.room == "204"
    assert [edit.field for edit in result[0].edits] == ["room"]
    assert result[0].edits[0].original == "12"
    assert result[0].edits[0].changed_upstream is False


def test_an_untouched_lesson_carries_its_key_and_no_edits():
    result = ov.overlay_lessons([lesson(room="12")], {})
    assert result[0].lesson.room == "12"
    assert result[0].edits == ()
    assert result[0].target == "lesson:2026-09-07:n1:Алгебра"


def test_an_empty_correction_on_an_optional_field_means_nothing_is_there():
    """The diary often carries a placeholder where a family would rather see
    nothing, so an empty correction is a real answer rather than a reset."""
    result = ov.overlay_lessons(
        [lesson(homework="—")],
        {"lesson:2026-09-07:n1:Алгебра": {"homework": correction("", "—")}},
    )
    assert result[0].lesson.homework is None


def test_a_correction_for_another_lesson_is_not_applied_to_this_one():
    result = ov.overlay_lessons(
        [lesson(number=2)],
        {"lesson:2026-09-07:n1:Алгебра": {"room": correction("204")}},
    )
    assert result[0].lesson.room is None
    assert result[0].edits == ()


def test_the_diary_moving_afterwards_is_reported_and_not_hidden():
    """A teacher who finally fills in the homework a family typed themselves
    must not have it covered up with nothing on screen to say so."""
    result = ov.overlay_lessons(
        [lesson(homework="§ 5, упр. 3")],
        {"lesson:2026-09-07:n1:Алгебра": {"homework": correction("§ 5", was=None)}},
    )

    edit = result[0].edits[0]
    assert result[0].lesson.homework == "§ 5"
    assert edit.changed_upstream is True
    assert edit.original == "§ 5, упр. 3"


def test_the_diary_moving_does_not_reset_the_correction_by_itself():
    result = ov.overlay_lessons(
        [lesson(room="301")],
        {"lesson:2026-09-07:n1:Алгебра": {"room": correction("204", "12")}},
    )
    assert result[0].lesson.room == "204"


def test_two_lessons_sharing_a_key_correct_neither_and_say_so():
    """The diary numbers lessons per day and is not above giving two of them
    the same number when a class splits into groups. Room and teacher are what
    tell those apart, and they are exactly the fields being corrected — so
    there is nothing left to key on, and putting one group's room on both
    halves is worse than applying nothing."""
    split = [lesson(room="12"), lesson(room="14")]
    result = ov.overlay_lessons(
        split, {"lesson:2026-09-07:n1:Алгебра": {"room": correction("204", "12")}}
    )

    assert [item.lesson.room for item in result] == ["12", "14"]
    assert all(item.ambiguous for item in result)
    assert all(item.edits == () for item in result)


def test_a_lesson_that_shares_no_key_is_not_marked_ambiguous():
    mixed = [lesson(number=1), lesson(number=2)]
    result = ov.overlay_lessons(mixed, {})
    assert not any(item.ambiguous for item in result)


def test_homework_text_is_corrected_the_same_way():
    result = ov.overlay_homework(
        [homework(id=77, text="§ 5")], {"hw:id:77": {"text": correction("§ 5, упр. 3", "§ 5")}}
    )
    assert result[0].item.text == "§ 5, упр. 3"
    assert result[0].target == "hw:id:77"
    assert result[0].edits[0].field == "text"


def test_a_field_outside_the_set_is_ignored_even_if_a_row_exists():
    """Belt and braces: the write path refuses these, and a row that predates a
    narrowing of the set must not start being applied."""
    result = ov.overlay_homework(
        [homework(id=77)], {"hw:id:77": {"subject": correction("Геометрия")}}
    )
    assert result[0].item.subject == "Алгебра"
    assert result[0].edits == ()


def test_upstream_order_survives_the_overlay():
    lessons = [lesson(number=index, subject=f"Урок {index}") for index in (3, 1, 2)]
    result = ov.overlay_lessons(lessons, {})
    assert [item.lesson.number for item in result] == [3, 1, 2]
