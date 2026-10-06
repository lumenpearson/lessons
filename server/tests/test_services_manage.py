"""The rules v1's manage routers held and 3b-1's v2 handlers need, in ``services/``.

The member names and the class's wall clock (``api/manage/_common.py``), the
dictionary read without adoption and the rename-then-details patch
(``api/manage/subjects.py``), and the journal's page after a line: each moved
before its v2 handler is written, with v1 calling the moved code
(``docs/specs/2026-10-05-server-v2-design.md``, decision 2).
``test_api_manage.py`` is the proof v1's answers did not move; these hold the
shapes v2 adds.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta

import pytest
from sqlalchemy import select

from app import wording
from app.models import AuditEntry, BotUser, Role, SchoolClass, Subject
from app.services import clock
from app.services.manage import classes, journal
from app.services.manage import subjects as subjects_service

START = datetime(2026, 9, 1, 8, 0)


async def _lines(session, school_class, count: int) -> None:
    """``count`` log lines a minute apart, oldest first: «строка 0» onwards."""
    session.add_all(
        AuditEntry(
            class_id=school_class.id,
            action="test.line",
            summary=f"строка {n}",
            created_at=START + timedelta(minutes=n),
        )
        for n in range(count)
    )
    await session.commit()


def _summaries(rows: list[AuditEntry]) -> list[str]:
    return [row.summary for row in rows]


async def test_wall_puts_a_stored_utc_stamp_on_the_class_clock(session, school_class) -> None:
    school_class.timezone = "Asia/Vladivostok"
    await session.commit()
    # Vladivostok is ten hours ahead of UTC all year.
    assert clock.wall(datetime(2026, 9, 12, 5, 5, 33), school_class) == datetime(
        2026, 9, 12, 15, 5, 33
    )
    assert clock.wall(None, school_class) is None


async def test_a_member_is_named_by_username_then_full_name_then_id(
    session, school_class
) -> None:
    session.add_all(
        [
            BotUser(
                telegram_id=3001,
                class_id=school_class.id,
                role=Role.EDITOR,
                username="anna",
                full_name="Анна",
            ),
            BotUser(
                telegram_id=3002,
                class_id=school_class.id,
                role=Role.VIEWER,
                full_name="Пётр <7>",
            ),
            BotUser(telegram_id=3003, class_id=school_class.id, role=Role.VIEWER),
        ]
    )
    await session.commit()
    # Plain text, not HTML: these names go into JSON and protobuf, and the
    # bot escapes its own copy when it renders one.
    assert await classes.member_names(session, school_class.id) == {
        3001: "@anna",
        3002: "Пётр <7>",
        3003: "3003",
    }
    assert classes.display_name(None, None, None) == "—"


async def test_the_dictionary_read_adopts_nothing_and_the_listing_still_does(
    session, school_class, statement_writes
) -> None:
    # The fixture's Monday teaches three subjects the dictionary has never seen.
    with statement_writes() as seen:
        assert await subjects_service.dictionary_of(session, school_class.id) == []
    assert seen == []
    adopted = await subjects_service.listing(session, school_class.id)
    assert [row.name for row in adopted] == ["Алгебра", "История", "Физика"]


async def test_an_update_renames_first_then_sets_each_detail_with_a_line_each(
    session, school_class
) -> None:
    subject = Subject(class_id=school_class.id, name="Алгебра")
    session.add(subject)
    await session.commit()
    moved = await subjects_service.update(
        session,
        school_class.id,
        2003,
        subject,
        {"teacher": "Иванова А. П.", "name": "Алгебра и начала анализа"},
    )
    # One timetable row of the fixture's Monday spells the old name.
    assert moved == 1
    assert (subject.name, subject.teacher) == ("Алгебра и начала анализа", "Иванова А. П.")
    await session.commit()
    actions = list(await session.scalars(select(AuditEntry.action).order_by(AuditEntry.id)))
    assert actions == ["subject.rename", "subject.teacher"]


async def test_an_update_whose_rename_is_refused_writes_none_of_it(session, school_class) -> None:
    algebra = Subject(class_id=school_class.id, name="Алгебра")
    session.add_all([algebra, Subject(class_id=school_class.id, name="Геометрия")])
    await session.commit()
    with pytest.raises(subjects_service.SubjectExists):
        await subjects_service.update(
            session, school_class.id, 2003, algebra, {"name": "ГЕОМЕТРИЯ", "teacher": "Петров"}
        )
    assert algebra.teacher is None
    assert [row for row in session.new if isinstance(row, AuditEntry)] == []


def test_the_shared_sentences_are_v1_s() -> None:
    days = [date(2026, 9, 7), date(2026, 9, 14)]
    assert wording.subject_rename_clash_detail(days) == (
        "homework under both names on the same day: 2026-09-07, 2026-09-14"
    )
    assert wording.subject_in_use_detail(12) == "12 lesson(s) still use this subject"


async def test_a_page_after_a_line_is_the_lines_older_than_it(session, school_class) -> None:
    await _lines(session, school_class, 5)
    first, more = await journal.page_after(session, school_class.id, limit=2, after_id=None)
    assert _summaries(first) == ["строка 4", "строка 3"] and more
    second, more = await journal.page_after(
        session, school_class.id, limit=2, after_id=first[-1].id
    )
    assert _summaries(second) == ["строка 2", "строка 1"] and more
    last, more = await journal.page_after(
        session, school_class.id, limit=2, after_id=second[-1].id
    )
    assert _summaries(last) == ["строка 0"] and not more


async def test_lines_written_in_one_second_page_by_id(session, school_class) -> None:
    """A tie is ordinary: SQLite's stamps have one-second resolution, and
    Postgres gives every line of one transaction its ``now()``. ``id`` breaks
    it, as ``audit.recent`` does, and these lines take the server's own
    default stamp, the one a bound parameter would not equal."""
    for n in range(3):
        session.add(
            AuditEntry(class_id=school_class.id, action="test.line", summary=f"строка {n}")
        )
    await session.commit()
    first, _ = await journal.page_after(session, school_class.id, limit=2, after_id=None)
    second, more = await journal.page_after(
        session, school_class.id, limit=2, after_id=first[-1].id
    )
    assert _summaries(first + second) == ["строка 2", "строка 1", "строка 0"] and not more


async def test_a_line_written_between_two_pages_repeats_none_and_skips_none(
    session, school_class
) -> None:
    await _lines(session, school_class, 4)
    first, _ = await journal.page_after(session, school_class.id, limit=2, after_id=None)
    session.add(
        AuditEntry(
            class_id=school_class.id,
            action="test.line",
            summary="новая",
            created_at=START + timedelta(hours=1),
        )
    )
    await session.commit()
    second, more = await journal.page_after(
        session, school_class.id, limit=2, after_id=first[-1].id
    )
    assert _summaries(second) == ["строка 1", "строка 0"] and not more
    # v1's offset, for contrast: the new line pushes «строка 2» onto page two again.
    by_offset, _ = await journal.page(session, school_class.id, limit=2, offset=2)
    assert _summaries(by_offset) == ["строка 2", "строка 1"]


async def test_a_line_of_another_class_or_of_none_is_not_this_log_s(
    session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    theirs = AuditEntry(class_id=other.id, action="test.line", summary="чужая")
    session.add(theirs)
    await session.commit()
    assert await journal.has_line(session, school_class.id, theirs.id) is False
    assert await journal.has_line(session, other.id, theirs.id) is True
    assert await journal.has_line(session, school_class.id, 999_999) is False
