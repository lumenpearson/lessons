"""The subject dictionary and the timetable, kept in step.

The bug behind this file, measured on the live class: 35 lessons under 20
distinct names and an empty «📚 Предметы». `timetable_entries.subject_id`
existed in the schema and was written by nothing at all, so the resolver —
which looks a colour up by name — had nothing to look in, and the schedule was
drawn without colours no matter what an admin typed into the dictionary.

Two rules are pinned here. Every write that names a subject goes through the
dictionary, so the two lists cannot drift apart again; and a class that
predates the link heals on the first read rather than waiting for somebody to
press «собрать из расписания».
"""

from __future__ import annotations

from sqlalchemy import select

from app.models import Subject, TimetableEntry, WeekParity
from app.services import structure, subjects, timetable_edit


async def _entries(session, class_id: int) -> list[TimetableEntry]:
    return list(
        await session.scalars(
            select(TimetableEntry)
            .where(TimetableEntry.class_id == class_id)
            .order_by(TimetableEntry.weekday, TimetableEntry.index)
        )
    )


async def _names(session, class_id: int) -> list[str]:
    return list(
        await session.scalars(
            select(Subject.name).where(Subject.class_id == class_id).order_by(Subject.name)
        )
    )


# ---- the name itself ------------------------------------------------------


def test_a_name_is_trimmed_and_single_spaced():
    assert subjects.normalise("  Алгебра   и   начала  ") == "Алгебра и начала"


def test_an_empty_name_is_not_a_subject():
    assert subjects.normalise(None) == ""
    assert subjects.normalise("   ") == ""


def test_a_name_longer_than_the_column_is_cut():
    assert len(subjects.normalise("Ш" * 400)) == subjects.MAX_NAME


# ---- writes go through the dictionary -------------------------------------


async def test_adding_a_lesson_registers_its_subject(session, school_class):
    await timetable_edit.add_lesson(session, school_class.id, 2, subject="Геометрия")
    await session.commit()

    assert "Геометрия" in await _names(session, school_class.id)
    added = [e for e in await _entries(session, school_class.id) if e.weekday == 2]
    assert added[0].subject_id is not None


async def test_a_second_spelling_joins_the_first_rather_than_founding_a_subject(
    session, school_class
):
    """«алгебра» in a class that has «Алгебра» is the same lesson, and two rows
    would be two colours and two entries in every picker."""
    await timetable_edit.add_lesson(session, school_class.id, 2, subject="Алгебра")
    await timetable_edit.add_lesson(session, school_class.id, 3, subject="алгебра")
    await session.commit()

    names = [name for name in await _names(session, school_class.id) if name.lower() == "алгебра"]
    assert names == ["Алгебра"]
    # And the stored spelling is the dictionary's, not the typist's.
    written = [e for e in await _entries(session, school_class.id) if e.weekday == 3]
    assert written[0].subject_name == "Алгебра"


async def test_editing_a_lesson_relinks_it(session, school_class):
    await timetable_edit.edit_lesson(
        session, school_class.id, 1, 1, WeekParity.ANY, subject="Геометрия", room=None, teacher=None
    )
    await session.commit()

    changed = next(e for e in await _entries(session, school_class.id) if e.index == 1)
    assert changed.subject_name == "Геометрия"
    assert changed.subject_id is not None
    assert "Геометрия" in await _names(session, school_class.id)


async def test_splitting_a_slot_carries_the_link_to_both_halves(session, school_class):
    """The знаменатель is the same subject until somebody changes it; a half
    that lost its colour would read as two different lessons."""
    await subjects.sync_from_timetable(session, school_class.id)
    await timetable_edit.split_parity(session, school_class.id, 1, 1)
    await session.commit()

    halves = [e for e in await _entries(session, school_class.id) if e.index == 1]
    assert len(halves) == 2
    assert {half.subject_id for half in halves} == {halves[0].subject_id}
    assert halves[0].subject_id is not None


async def test_a_pasted_week_adopts_its_subjects(session, school_class):
    await structure.apply_timetable(
        session,
        school_class,
        days={
            4: [
                (1, "Химия", "12", None, WeekParity.ANY),
                (2, "Биология", None, None, WeekParity.ANY),
            ]
        },
        bells=[],
    )
    await session.commit()

    names = await _names(session, school_class.id)
    assert "Химия" in names and "Биология" in names
    pasted = [e for e in await _entries(session, school_class.id) if e.weekday == 4]
    assert all(entry.subject_id is not None for entry in pasted)


# ---- healing what predates the link ---------------------------------------


async def test_a_class_with_a_timetable_and_no_dictionary_heals(session, school_class):
    """The live case: lessons everywhere and «Предметы» empty."""
    assert await _names(session, school_class.id) == []

    created = await subjects.sync_from_timetable(session, school_class.id)
    await session.commit()

    assert created == 3
    assert await _names(session, school_class.id) == ["Алгебра", "История", "Физика"]
    assert all(entry.subject_id is not None for entry in await _entries(session, school_class.id))


async def test_healing_twice_changes_nothing(session, school_class):
    await subjects.sync_from_timetable(session, school_class.id)
    await session.commit()

    assert await subjects.sync_from_timetable(session, school_class.id) == 0


async def test_healing_keeps_a_subject_somebody_already_wrote_down(session, school_class):
    """An admin's «Алгебра», with its colour, is not replaced by a second one
    harvested from the timetable."""
    session.add(Subject(class_id=school_class.id, name="Алгебра", color="#ff0000"))
    await session.flush()

    await subjects.sync_from_timetable(session, school_class.id)
    await session.commit()

    rows = list(
        await session.scalars(
            select(Subject).where(Subject.class_id == school_class.id, Subject.name == "Алгебра")
        )
    )
    assert len(rows) == 1
    assert rows[0].color == "#ff0000"


async def test_a_later_typist_does_not_rename_a_subject_by_shouting(session, school_class):
    """The fixture's template says «Физика». Typing «физика» must join it, not
    found a lower-case dictionary entry and drag every existing row down to
    it — that is one person quietly renaming a subject for the whole class."""
    await timetable_edit.add_lesson(session, school_class.id, 2, subject="физика")
    await session.commit()

    physics = [
        name for name in await _names(session, school_class.id) if name.lower() == "физика"
    ]
    assert physics == ["Физика"]
    added = [e for e in await _entries(session, school_class.id) if e.weekday == 2]
    assert added[0].subject_name == "Физика"


async def test_a_class_with_no_timetable_creates_nothing(session, school_class):
    await session.execute(
        TimetableEntry.__table__.delete().where(TimetableEntry.class_id == school_class.id)
    )
    assert await subjects.sync_from_timetable(session, school_class.id) == 0


# ---- renaming -------------------------------------------------------------


async def test_a_rename_moves_the_rows_and_keeps_the_link(session, school_class):
    await subjects.sync_from_timetable(session, school_class.id)
    await session.flush()
    subject = await subjects.find(session, school_class.id, "Алгебра")

    moved = await structure.rename_subject(session, school_class.id, subject, "Алгебра и начала")
    await session.commit()

    assert moved >= 1
    row = next(e for e in await _entries(session, school_class.id) if e.index == 1)
    assert row.subject_name == "Алгебра и начала"
    assert row.subject_id == subject.id
