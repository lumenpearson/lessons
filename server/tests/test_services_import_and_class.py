"""The timetable's import and the class card's patch, in ``services/``.

v1's ``POST /manage/timetable/import`` and ``PATCH /manage/class`` held their
rules in the router: the parse, the conflicts, and a line for each lesson
dropped or silenced; the letter, the zone, the recomposed name and the join
mode. Both moved before v2's ``ImportTimetable`` and ``UpdateClass`` were
written, with v1 calling the moved code
(``docs/specs/2026-10-05-server-v2-design.md``, decision 2), and
``test_api_manage.py``, untouched, is the proof that v1's answers did not
move. These hold what v2 adds, a preview that writes nothing, and the
patch's order.
"""

from __future__ import annotations

import pytest
from sqlalchemy import select

from app import wording
from app.models import AuditEntry
from app.services.manage import classes as classes_service
from app.services.manage import timetable as timetable_service
from app.timezones import label_for

#: A Monday the fixture already teaches three lessons on, and a line no
#: parser reads.
CONFLICTING = "== Понедельник ==\n1. Химия, 118\nне строка\n2. Биология"


async def _actions(session) -> list[str]:
    return list(await session.scalars(select(AuditEntry.action).order_by(AuditEntry.id)))


async def test_an_import_preview_writes_nothing_and_counts_the_pasted_lines(
    session, school_class, statement_writes
) -> None:
    """A preview wins over ``replace``: it is v1's answer to a paste over
    lessons, the paste's own counts and the parser's lines, written nowhere.
    No gate runs here, so there is no last seen to allow: nothing at all."""
    with statement_writes() as seen:
        outcome = await timetable_service.import_paste(
            session, school_class, 2003, CONFLICTING, replace=True, validate_only=True
        )
        await session.flush()
    assert seen == []
    assert outcome == timetable_service.ImportOutcome(
        applied=False,
        days=[1],
        lessons=2,
        bells=0,
        conflicts=[timetable_service.Conflict(weekday=1, existing=3, incoming=2)],
        rejected=["не строка"],
    )
    # Held here rather than trusted: the same paste applied is seen writing,
    # so the empty capture above is the preview's and not a deaf probe's.
    with statement_writes() as applied:
        await timetable_service.import_paste(
            session, school_class, 2003, CONFLICTING, replace=True
        )
        await session.flush()
    assert applied


async def test_a_paste_with_no_day_and_no_bells_is_refused_as_a_fact(
    session, school_class
) -> None:
    with pytest.raises(timetable_service.PasteEmpty):
        await timetable_service.import_paste(
            session, school_class, 2003, "просто текст без заголовков", replace=True
        )
    assert [row for row in session.new if isinstance(row, AuditEntry)] == []


async def test_an_import_names_each_lesson_it_dropped_and_each_it_silenced(
    session, school_class
) -> None:
    """Monday is not in the paste and keeps lessons 1 to 3; the paste's bells
    ring 1 and 2, so Monday's third lesson stops ringing, and Tuesday's eighth
    has no bell at all. Each is a line of its own, in v1's words."""
    outcome = await timetable_service.import_paste(
        session,
        school_class,
        2003,
        "== Вторник ==\n1. Химия\n8. Физика\n\n== Звонки ==\n1. 09:00-09:40\n2. 09:50-10:30\n",
        replace=False,
    )
    assert (outcome.applied, outcome.days, outcome.lessons, outcome.bells) == (True, [2], 1, 2)
    assert outcome.conflicts == []
    assert outcome.rejected == [
        "вторник, урок 8: нет такого звонка в расписании звонков",
        "понедельник, урок 3: больше не звонит — новые звонки короче",
    ]
    assert outcome.schedule is not None
    await session.commit()
    assert await _actions(session) == ["timetable.import"]


async def test_a_class_update_recomposes_the_name_and_logs_each_field(
    session, school_class
) -> None:
    school_class.grade, school_class.letter = 9, "А"
    await session.commit()
    await classes_service.update(
        session, school_class, 2003, {"grade": 10, "letter": " Б ", "city": "Казань"}
    )
    # The letter read by the bot's rule, and the name composed from it.
    assert (school_class.name, school_class.letter, school_class.city) == ("10Б", "Б", "Казань")
    await session.commit()
    assert await _actions(session) == ["class.grade", "class.letter", "class.city", "class.name"]


async def test_a_class_update_with_an_unknown_zone_writes_nothing(session, school_class) -> None:
    """The zone is asked about before any field is written, the name included."""
    with pytest.raises(classes_service.UnknownTimezone):
        await classes_service.update(
            session, school_class, 2003, {"name": "9Б", "timezone": "Mars/Olympus"}
        )
    assert school_class.name == "9А"
    assert [row for row in session.new if isinstance(row, AuditEntry)] == []


async def test_the_zone_label_is_the_one_the_bot_prints(session, school_class) -> None:
    # No zone stored: the class runs on the deployment's, Moscow in the tests.
    assert classes_service.timezone_label(school_class) == label_for("Europe/Moscow")
    school_class.timezone = "Asia/Yekaterinburg"
    assert classes_service.timezone_label(school_class) == label_for("Asia/Yekaterinburg")


def test_the_import_and_zone_sentences_are_v1_s() -> None:
    assert wording.TIMETABLE_PASTE_EMPTY_DETAIL == (
        "no weekday header and no bells block found in the text"
    )
    assert wording.UNKNOWN_TIMEZONE_DETAIL == "unknown timezone"
