"""Substitutions, in ``services/``: three questions, asked once for three shells.

v1's ``PUT /overrides`` asked, in its router, whether the day draws lessons at
all, whether it rings the number, and whether a lesson is underneath; the
bot's «🔄 Замены» asked the first two again in its own handler, and v2's
``SubstitutionService`` writes the same rows. The rules moved to
``services/substitutions.py``, as facts each shell words, and v1 and the bot
call them (``docs/specs/2026-10-05-server-v2-design.md``, decision 2).
``test_api_extended.py``, ``test_bot_handlers.py``, ``test_timetable_edit.py``
and ``test_announcements.py``, untouched, are the proof that v1's answers and
the bot's screens did not move; these hold the rules a fact at a time, a
substitution two phones write at once (#382), the bot's third question (#383),
and that nothing the services write is committed by them.
"""

from __future__ import annotations

from datetime import date, datetime
from typing import Any

import pytest
from sqlalchemy import func, select

from app import wording
from app.bot.handlers.content.overrides import override_cancel, override_subject
from app.db import SessionLocal
from app.models import (
    AuditEntry,
    DayKind,
    DayOverride,
    LessonOverride,
    OverrideAction,
    Role,
    SchoolClass,
    Subject,
)
from app.services import clock, substitutions, timetable_edit

#: A Monday of the school year: the class's template has lessons 1 to 3 on a
#: Monday, and its bells ring 1 to 7.
MONDAY = date(2026, 9, 14)
EDITOR = 42
#: 14 September, as people read it on Monday the 7th.
WHEN = "14 сентября (понедельник)"
REPLACE, CANCEL = OverrideAction.REPLACE, OverrideAction.CANCEL


def _pin_clock(monkeypatch) -> None:
    """Monday 7 September, 10:00 in the class's zone."""
    monkeypatch.setattr(
        clock, "now", lambda school_class: datetime(2026, 9, 7, 10, 0, tzinfo=school_class.tz)
    )


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _all(**fields: Any) -> dict[str, Any]:
    """Every field of a write, as v1 sends them: what is not given is none."""
    return {name: fields.get(name) for name in ("action", "subject", "room", "teacher", "note")}


async def _committed(statement: Any) -> Any:
    """What a session of its own reads: only what is committed."""
    async with SessionLocal() as fresh:
        return await fresh.scalar(statement)


async def _lines(session) -> list[tuple[str, str]]:
    rows = await session.execute(
        select(AuditEntry.action, AuditEntry.summary).order_by(AuditEntry.id)
    )
    return [tuple(row) for row in rows]


def _stale_once(monkeypatch) -> list[bool]:
    """``substitution_on`` answers «none» once, as it truthfully did a moment
    before somebody else's substitution was committed."""
    real = substitutions.substitution_on
    stale: list[bool] = []

    async def stale_once(*args: Any, **kwargs: Any) -> Any:
        if not stale:
            stale.append(True)
            return None
        return await real(*args, **kwargs)

    monkeypatch.setattr(substitutions, "substitution_on", stale_once)
    return stale


async def test_a_substitution_is_written_with_its_line_and_its_notice_as_v1_wrote_them(
    session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    session.add(Subject(class_id=school_class.id, name="Химия"))
    await session.commit()
    replaced = await substitutions.put(
        session,
        school_class,
        EDITOR,
        MONDAY,
        2,
        _all(action=REPLACE, subject="химия", room="118", note="учитель <на конференции>"),
    )
    row = replaced.substitution
    # The class's spelling, so that the resolver finds the subject's colour.
    assert (row.action, row.subject_name, row.room) == (REPLACE, "Химия", "118")
    assert replaced.notice == (
        f"🔁 Замена {WHEN}: урок №2 — <b>Химия</b>, каб. 118\nучитель &lt;на конференции&gt;"
    )
    cancelled = await substitutions.put(
        session, school_class, EDITOR, MONDAY, 2, _all(action=CANCEL, subject="Химия")
    )
    # The same row, and a cancellation carries no subject, room or teacher.
    assert cancelled.substitution is row
    assert (row.action, row.subject_name, row.room, row.teacher) == (CANCEL, None, None, None)
    assert cancelled.notice == f"🚫 Урок №2 {WHEN} отменён."
    room_only = await substitutions.put(
        session, school_class, EDITOR, MONDAY, 1, _all(action=REPLACE, room="305")
    )
    assert room_only.notice == f"🔁 Замена {WHEN}: урок №1 — <b>кабинет/учитель</b>, каб. 305"
    assert await _lines(session) == [
        ("override.replace", f"Замена: урок №2, {WHEN} — Химия"),
        ("override.cancel", f"Урок №2 отменён, {WHEN}"),
        ("override.replace", f"Замена: урок №1, {WHEN} — кабинет/учитель"),
    ]


async def test_the_three_questions_are_facts_each_shell_words_for_itself(
    session, school_class
) -> None:
    session.add(DayOverride(class_id=school_class.id, date=MONDAY, kind=DayKind.DAY_OFF))
    await session.commit()
    for day, why in (
        (date(2027, 6, 14), "out_of_year"),
        (date(2027, 3, 8), "public_holiday"),
        (MONDAY, "marked_day_off"),
    ):
        with pytest.raises(substitutions.NoLessonOnDay) as refused:
            await substitutions.upsert(
                session, school_class.id, day, 1, {"action": REPLACE, "subject": "Химия"}
            )
        assert refused.value.why == why
        # The sentence both shells have always shown for that day.
        assert refused.value.sentence == await timetable_edit.why_no_lesson_can_be_drawn(
            session, school_class.id, day
        )
    tuesday = date(2026, 9, 15)
    with pytest.raises(substitutions.NoBellForLesson) as no_bell:
        await substitutions.upsert(
            session, school_class.id, tuesday, 8, {"action": REPLACE, "subject": "Химия"}
        )
    assert no_bell.value.index == 8
    # Tuesday has no template lessons, and its bells ring 1 to 7.
    for changes, cancelling in (
        ({"action": CANCEL}, True),
        ({"action": REPLACE, "room": "305"}, False),
    ):
        with pytest.raises(substitutions.LessonNotOnTimetable) as underneath:
            await substitutions.upsert(session, school_class.id, tuesday, 7, changes)
        assert (underneath.value.index, underneath.value.cancelling) == (7, cancelling)
    assert await session.scalar(select(func.count()).select_from(LessonOverride)) == 0


async def test_a_row_at_a_number_that_no_longer_rings_stays_editable_and_a_new_one_does_not(
    session, school_class
) -> None:
    """The bell is asked of a new row only: an existing row at a bad number
    has to stay editable, which is how a class gets out of one."""
    stray = LessonOverride(
        class_id=school_class.id, date=MONDAY, index=9, action=REPLACE, subject_name="Химия"
    )
    session.add(stray)
    await session.commit()
    changed = await substitutions.update(session, school_class, EDITOR, stray, {"room": "214"})
    assert (changed.substitution.room, changed.notice is not None) == ("214", True)
    again, _created = await substitutions.upsert(
        session, school_class.id, MONDAY, 9, {"action": REPLACE, "subject": "Физика"}
    )
    assert (again is stray, stray.subject_name) == (True, "Физика")
    with pytest.raises(substitutions.NoBellForLesson):
        await substitutions.create(
            session, school_class, EDITOR, date(2026, 9, 21), 9, _all(action=REPLACE, subject="x")
        )


async def test_a_lesson_with_a_substitution_is_not_created_a_second_time(
    session, school_class, monkeypatch
) -> None:
    session.add(LessonOverride(class_id=school_class.id, date=MONDAY, index=2, action=CANCEL))
    await session.commit()
    with pytest.raises(substitutions.SubstitutionExists):
        await substitutions.create(
            session, school_class, EDITOR, MONDAY, 2, _all(action=REPLACE, subject="Химия")
        )
    # The race: the read answers «none» while the twin is in the table. The
    # insert meets the unique constraint inside its savepoint, is refused the
    # same way, and the caller's transaction goes on.
    stale = _stale_once(monkeypatch)
    with pytest.raises(substitutions.SubstitutionExists):
        await substitutions.create(
            session, school_class, EDITOR, MONDAY, 2, _all(action=REPLACE, subject="Химия")
        )
    assert stale, "the stale read never happened, so nothing was tested"
    session.add(Subject(class_id=school_class.id, name="Химия"))
    await session.commit()
    assert await _committed(select(func.count()).select_from(LessonOverride)) == 1
    assert await _committed(select(func.count()).select_from(Subject)) == 1


async def test_a_substitution_somebody_else_wrote_in_the_same_instant_is_changed_not_doubled(
    session, school_class, monkeypatch
) -> None:
    """#382, at the service: the write that lost the race becomes the change it
    would have been a moment later, and leaves the twin's note as it was."""
    session.add(
        LessonOverride(class_id=school_class.id, date=MONDAY, index=2, action=CANCEL, note="их")
    )
    await session.commit()
    stale = _stale_once(monkeypatch)

    row, created = await substitutions.upsert(
        session, school_class.id, MONDAY, 2, {"action": REPLACE, "subject": "Химия"}
    )
    await session.commit()

    assert stale, "the stale read never happened, so nothing was tested"
    assert (created, row.action, row.subject_name, row.note) == (False, REPLACE, "Химия", "их")
    assert await _committed(select(func.count()).select_from(LessonOverride)) == 1


async def test_two_phones_substituting_one_lesson_at_once_both_succeed(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    """#382, through v1: the phone that lost the race met the unique constraint
    and answered 500, after its line and before its notice."""
    session.add(LessonOverride(class_id=school_class.id, date=MONDAY, index=2, action=CANCEL))
    await session.commit()
    stale = _stale_once(monkeypatch)

    answer = await v2.http.put(
        "/api/v1/overrides",
        json={"date": MONDAY.isoformat(), "index": 2, "action": "replace", "subject": "Химия"},
        headers=_auth(v2_tokens["editor"]),
    )

    assert stale, "the stale read never happened, so nothing was tested"
    assert answer.status_code == 200, answer.text
    assert (answer.json()["action"], answer.json()["subject"]) == ("replace", "Химия")
    assert await _committed(select(func.count()).select_from(LessonOverride)) == 1


async def test_a_change_that_changes_nothing_writes_nothing_and_tells_nobody(
    session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    row = LessonOverride(
        class_id=school_class.id, date=MONDAY, index=2, action=REPLACE, subject_name="Химия"
    )
    session.add(row)
    await session.commit()
    for same in ({"subject": "Химия"}, {"action": REPLACE, "room": None}):
        unchanged = await substitutions.update(session, school_class, EDITOR, row, same)
        assert unchanged.notice is None, same
    assert await _lines(session) == []
    moved = await substitutions.update(session, school_class, EDITOR, row, {"teacher": "Иванов"})
    assert moved.notice == f"🔁 Замена {WHEN}: урок №2 — <b>Химия</b>"
    assert [action for action, _ in await _lines(session)] == ["override.replace"]


async def test_deleting_words_its_line_and_its_notice_and_a_lookup_stays_in_its_class(
    session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    later = date(2026, 9, 21)
    rows = [
        LessonOverride(class_id=school_class.id, date=later, index=1, action=CANCEL),
        LessonOverride(class_id=school_class.id, date=MONDAY, index=3, action=CANCEL),
        LessonOverride(class_id=school_class.id, date=MONDAY, index=1, action=CANCEL),
        LessonOverride(class_id=other.id, date=MONDAY, index=2, action=CANCEL),
    ]
    session.add_all(rows)
    await session.commit()
    listed = await substitutions.between(session, school_class.id, MONDAY, later)
    # By date, then lesson number; another class's is not among them.
    assert [(row.date, row.index) for row in listed] == [(MONDAY, 1), (MONDAY, 3), (later, 1)]
    assert await substitutions.substitution_of(session, school_class.id, rows[3].id) is None
    own = await substitutions.substitution_of(session, school_class.id, rows[1].id)
    assert own is rows[1]

    notice = await substitutions.delete(session, school_class, EDITOR, own)
    assert notice == f"♻️ Урок №3 {WHEN} снова идёт по расписанию."
    assert await _lines(session) == [("override.clear", f"Замена снята: урок №3, {WHEN}")]
    assert await substitutions.substitution_on(session, school_class.id, MONDAY, 3) is None


async def test_the_bot_will_not_cancel_a_lesson_the_template_does_not_have(
    session, school_class, FakeCallback, FakeEditable, FakeMessage, FakeState
) -> None:
    """#383. A lesson added by a substitution at a number the template leaves
    empty is in the bot's picker, because the picker lists what the day draws;
    «🚫 Отменить урок» on it stored a cancellation of nothing, announced to
    every subscriber and drawn nowhere, which v1 refused and still does. The
    bot now asks the same third question, and points the same way out."""
    added = FakeMessage(text="Астрономия")
    await override_subject(
        added,
        FakeState(data={"date": MONDAY.isoformat(), "index": "7"}),
        session,
        school_class,
        Role.EDITOR,
    )
    assert "Астрономия" in added.last

    callback = FakeCallback(message=FakeEditable())
    await override_cancel(
        callback,
        FakeState(data={"date": MONDAY.isoformat(), "index": "7"}),
        session,
        school_class,
        Role.EDITOR,
    )
    assert callback.answers[-1] == (
        wording.lesson_not_on_timetable_detail(7, cancelling=True),
        True,
    )
    row = await session.scalar(select(LessonOverride))
    assert (row.action, row.subject_name) == (REPLACE, "Астрономия")
    assert [action for action, _ in await _lines(session)] == ["override.replace"]


async def test_nothing_the_services_write_is_committed_until_the_caller_commits(
    session, school_class
) -> None:
    """A new substitution goes in through a savepoint, the first write of its
    transaction: what a rollback leaves is the code's answer on SQLite as on
    Postgres (#373)."""
    await substitutions.put(
        session, school_class, EDITOR, MONDAY, 2, _all(action=REPLACE, subject="Химия")
    )
    await session.rollback()
    for table in (LessonOverride, AuditEntry):
        assert await _committed(select(func.count()).select_from(table)) == 0, table


def test_the_sentences_both_versions_answer_with_are_v1_s() -> None:
    assert wording.no_bell_detail(8) == "нет звонка для урока №8 в этот день"
    assert (
        wording.lesson_not_on_timetable_detail(7, cancelling=True)
        == "в этот день нет урока №7, отменять нечего"
    )
    assert (
        wording.lesson_not_on_timetable_detail(7, cancelling=False)
        == "в этот день нет урока №7: замене без предмета нечего заменять"
    )
