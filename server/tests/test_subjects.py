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

from app.db import SessionLocal
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
    """The denominator half is the same subject until somebody changes it; a half
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


# ---- what the audit of this feature found ---------------------------------


async def test_a_shouted_duplicate_cannot_be_founded(session, school_class):
    """The uniqueness guard folds case, because the matcher does.

    When it did not, «ФИЗИКА» was allowed in beside «Физика» — and then
    `sync_from_timetable`, which folds, saw one subject where the dictionary
    had two and rewrote every «Физика» lesson onto whichever row it happened to
    keep. A read renaming a subject and dropping the colour of the one it
    abandoned.
    """
    await subjects.sync_from_timetable(session, school_class.id)
    await session.commit()

    assert await subjects.clashing(session, school_class.id, "ФИЗИКА") is not None
    assert await subjects.clashing(session, school_class.id, "  физика  ") is not None
    assert await subjects.clashing(session, school_class.id, "Астрономия") is None

    # A rename checking its own new name must not collide with itself.
    physics = await subjects.find(session, school_class.id, "Физика")
    assert await subjects.clashing(
        session, school_class.id, "физика", besides=physics.id
    ) is None


async def test_the_oldest_spelling_wins_and_keeps_winning(session, school_class):
    """A class that acquired two spellings before the guard folded case must
    not have the survivor chosen afresh on every read: two phones polling a
    minute apart would drag its lessons back and forth between the two rows."""
    await subjects.sync_from_timetable(session, school_class.id)
    original = await subjects.find(session, school_class.id, "Физика")
    session.add(Subject(class_id=school_class.id, name="ФИЗИКА"))
    await session.commit()

    for _ in range(2):
        await subjects.sync_from_timetable(session, school_class.id)
        await session.commit()
        rows = [e for e in await _entries(session, school_class.id) if e.subject_id == original.id]
        assert rows and all(e.subject_name == original.name for e in rows)


async def test_a_subject_the_timetable_uses_is_counted(session, school_class):
    """What the delete guard asks before it refuses."""
    await subjects.sync_from_timetable(session, school_class.id)
    await session.commit()
    algebra = await subjects.find(session, school_class.id, "Алгебра")
    assert await subjects.lessons_using(session, school_class.id, algebra) >= 1

    spare = Subject(class_id=school_class.id, name="Астрономия")
    session.add(spare)
    await session.commit()
    assert await subjects.lessons_using(session, school_class.id, spare) == 0


async def test_a_subject_is_counted_even_before_the_rows_are_linked(session, school_class):
    """By link *or* by spelling: a class that predates the link has neither,
    and the guard has to see it anyway."""
    await session.execute(
        TimetableEntry.__table__.update()
        .where(TimetableEntry.class_id == school_class.id)
        .values(subject_id=None)
    )
    algebra = await subjects.ensure(session, school_class.id, "Алгебра")
    await session.commit()
    assert await subjects.lessons_using(session, school_class.id, algebra) >= 1


async def test_spelling_answers_with_the_class_s_own(session, school_class):
    """Homework and substitutions carry a name and no link, so the spelling is the
    whole of what can be agreed — and it is what the upsert key and the colour
    lookup are both built on."""
    await subjects.sync_from_timetable(session, school_class.id)
    await session.commit()

    assert await subjects.spelling(session, school_class.id, "алгебра") == "Алгебра"
    assert await subjects.spelling(session, school_class.id, "  АЛГЕБРА ") == "Алгебра"
    # Never founds an entry: a picker that grew a subject because somebody
    # wrote down an assignment would be learning from the wrong half of the app.
    before = await _names(session, school_class.id)
    assert await subjects.spelling(session, school_class.id, "Астрономия") == "Астрономия"
    assert await _names(session, school_class.id) == before


# ---- two reads healing the same class at the same instant ------------------


async def test_two_reads_healing_one_class_at_once_concede_rather_than_raise(
    session, school_class
):
    """The savepoint in `_adopt`, pinned from the outside.

    Healing runs on the read path, so a class with an empty dictionary has
    every phone in it try to fill the same one on the same poll. Both find
    «Алгебра» absent, both insert it, and the loser of `uq_subject_name` would
    take the whole request down with it on Postgres — a 500 on exactly the
    first read this healing exists for. The loser concedes to the row that is
    there and links its lessons to it, which is the same answer.
    """
    class_id = school_class.id

    async with SessionLocal() as loser:
        real_flush = loser.flush

        async def flush_after_a_rival_healed(*args, **kwargs):
            loser.flush = real_flush
            # The other request got the whole dictionary in while this one was
            # between its find and its insert.
            async with SessionLocal() as rival:
                await subjects.sync_from_timetable(rival, class_id)
                await rival.commit()
            return await real_flush(*args, **kwargs)

        loser.flush = flush_after_a_rival_healed
        created = await subjects.sync_from_timetable(loser, class_id)
        # The caller's transaction survived the conflict - that is the whole
        # point of the savepoint, and on Postgres the only thing that saves it.
        await loser.commit()

    # And it says what it did: conceding is finding a row, not writing one, and
    # «🔄 Собрать из расписания» prints this number back at an admin.
    assert created == 0

    async with SessionLocal() as after:
        assert await _names(after, class_id) == ["Алгебра", "История", "Физика"]
        entries = await _entries(after, class_id)
    assert all(entry.subject_id is not None for entry in entries), (
        "the loser links its lessons to the rows the winner wrote"
    )
