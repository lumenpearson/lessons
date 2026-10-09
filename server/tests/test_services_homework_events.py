"""What v1's ``edit.py`` held for homework and events, in ``services/``.

v1's ``PUT /homework``, ``DELETE /homework/{id}``, ``PUT /events`` and
``DELETE /events/{id}`` wrote the row, its line in the journal and the notice
the class is told, all inside the router. v2's ``HomeworkService`` and
``EventService`` write the same things, so the rules moved before their
handlers were written, with v1 calling them
(``docs/specs/2026-10-05-server-v2-design.md``, decision 2).
``test_api_extended.py`` and ``test_announcements.py``, untouched, are the
proof that v1's answers and notices did not move; these hold the rules a fact
at a time, and that nothing the services write is committed by them.
"""

from __future__ import annotations

from datetime import date, datetime, time, timedelta
from typing import Any

import pytest
from sqlalchemy import func, select

from app import wording
from app.db import SessionLocal
from app.models import AuditEntry, DayEvent, EventKind, Homework, SchoolClass
from app.services import clock, events
from app.services import homework as homework_service

MONDAY = date(2026, 9, 7)
EDITOR = 42
TODAY = "сегодня, 7 сентября (понедельник)"


def _pin_clock(monkeypatch) -> None:
    """Monday 7 September, 10:00 in the class's zone, so «сегодня» is the 7th."""
    monkeypatch.setattr(
        clock, "now", lambda school_class: datetime(2026, 9, 7, 10, 0, tzinfo=school_class.tz)
    )


async def _committed(statement: Any) -> Any:
    """What a session of its own reads: only what is committed."""
    async with SessionLocal() as fresh:
        return await fresh.scalar(statement)


async def _lines(session) -> list[tuple[str, str]]:
    rows = await session.execute(
        select(AuditEntry.action, AuditEntry.summary).order_by(AuditEntry.id)
    )
    return [tuple(row) for row in rows]


async def _trip(session, school_class, **fields) -> events.Written:
    return await events.create(
        session,
        school_class,
        EDITOR,
        date=fields.pop("date", MONDAY),
        starts_at=fields.pop("starts_at", time(12, 30)),
        ends_at=fields.pop("ends_at", time(13, 0)),
        title=fields.pop("title", "Экскурсия"),
        kind=fields.pop("kind", EventKind.TRIP),
        **fields,
    )


async def test_a_put_words_its_line_and_its_notice_as_v1_did(
    session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    first = await homework_service.put(
        session, school_class, EDITOR, MONDAY, "Алгебра", "№ 12–15 <b>"
    )
    again = await homework_service.put(
        session,
        school_class,
        EDITOR,
        MONDAY,
        "Алгебра",
        "№ 16",
        attachment_url="https://example.com/p.pdf",
    )
    assert (first.created, again.created) == (True, False)
    assert again.homework is first.homework
    assert (again.homework.text, again.homework.attachment_url) == (
        "№ 16",
        "https://example.com/p.pdf",
    )
    assert await _lines(session) == [
        ("homework.add", f"ДЗ добавлено: Алгебра, {TODAY}"),
        ("homework.update", f"ДЗ обновлено: Алгебра, {TODAY}"),
    ]
    # Escaped here, for an HTML message: «<b>» typed is text, not bold.
    assert first.notice == f"📝 Задание добавлено: <b>Алгебра</b> {TODAY}\n№ 12–15 &lt;b&gt;"
    assert again.notice == f"📝 Задание обновлено: <b>Алгебра</b> {TODAY}\n№ 16"


async def test_an_assignment_s_notice_cuts_its_text_before_escaping_it(
    session, school_class
) -> None:
    """Cut after escaping, «<» × 300 could end in «&l», a message Telegram
    refuses whole."""
    saved = await homework_service.put(session, school_class, EDITOR, MONDAY, "Алгебра", "<" * 300)
    assert saved.notice.split("\n", 1)[1] == "&lt;" * 199 + "…"


async def test_deleting_an_assignment_words_its_line_and_its_notice(
    session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    row = Homework(
        class_id=school_class.id,
        due_date=MONDAY + timedelta(days=1),
        subject_name="Физика <i>",
        text="§ 3",
    )
    session.add(row)
    await session.commit()
    notice = await homework_service.delete(session, school_class, EDITOR, row)
    tomorrow = "завтра, 8 сентября (вторник)"
    assert notice == f"🗑 Задание удалено: <b>Физика &lt;i&gt;</b> {tomorrow}"
    assert await _lines(session) == [("homework.delete", f"ДЗ удалено: Физика <i>, {tomorrow}")]
    assert await session.scalar(select(func.count()).select_from(Homework)) == 0


async def test_an_event_stands_in_for_lessons_as_its_kind_means_unless_told(
    session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    trip = await _trip(session, school_class, location="Эрмитаж")
    canteen = await _trip(
        session,
        school_class,
        title="Обед",
        kind=EventKind.CANTEEN,
        starts_at=time(11, 10),
        ends_at=time(11, 25),
    )
    told = await _trip(session, school_class, title="Поход", covers_lesson=False)
    # Flushed, so that the caller can answer the id.
    assert trip.event.id is not None
    assert [written.event.covers_lesson for written in (trip, canteen, told)] == [
        True,
        False,
        False,
    ]
    assert trip.notice == f"📅 Событие: <b>Экскурсия</b> {TODAY}, 12:30–13:00, Эрмитаж"
    assert canteen.notice == f"📅 Событие: <b>Обед</b> {TODAY}, 11:10–11:25"
    assert (await _lines(session))[0] == ("event.add", f"Событие: Экскурсия, {TODAY} 12:30–13:00")


async def test_deleting_an_event_words_its_line_and_its_notice(
    session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    written = await _trip(session, school_class, title="Экскурсия <3")
    notice = await events.delete(session, school_class, EDITOR, written.event)
    assert notice == f"🗑 Событие отменено: <b>Экскурсия &lt;3</b> {TODAY}"
    assert (await _lines(session))[-1] == (
        "event.delete",
        f"Событие удалено: Экскурсия <3, {TODAY}",
    )
    assert await session.scalar(select(func.count()).select_from(DayEvent)) == 0


async def test_an_event_is_found_only_in_its_own_class(session, school_class) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    rows = [
        DayEvent(
            class_id=class_id,
            date=MONDAY,
            starts_at=time(11, 10),
            ends_at=time(11, 25),
            title="Обед",
            kind=EventKind.CANTEEN,
        )
        for class_id in (school_class.id, other.id)
    ]
    session.add_all(rows)
    await session.commit()
    own, foreign = rows
    assert await events.event_of(session, school_class.id, own.id) is own
    assert await events.event_of(session, school_class.id, foreign.id) is None
    assert await events.event_of(session, school_class.id, 999_999) is None


async def test_nothing_the_services_write_is_committed_until_the_caller_commits(
    session, school_class
) -> None:
    """A put goes through ``upsert``'s savepoint, the first write of its
    transaction: what a rollback leaves is the code's answer, now that SQLite
    keeps a savepoint inside the transaction as Postgres does (#373)."""
    await homework_service.put(session, school_class, EDITOR, MONDAY, "Алгебра", "№ 1")
    await _trip(session, school_class)
    await session.rollback()
    for table in (Homework, DayEvent, AuditEntry):
        assert await _committed(select(func.count()).select_from(table)) == 0, table


async def test_update_s_racing_twin_stays_inside_its_savepoint(
    session, school_class, monkeypatch
) -> None:
    """``update``'s own race (#381): the check answers «nothing there» while
    the twin already exists, so the move meets the unique constraint while
    flushing. That flush has to happen *inside* ``begin_nested()``'s
    savepoint — only the savepoint may be rolled back, never the caller's
    whole transaction, because this is called with a session the caller goes
    on using once ``HomeworkExists`` is caught."""
    algebra = Homework(
        class_id=school_class.id, due_date=MONDAY, subject_name="Алгебра", text="№ 1"
    )
    physics = Homework(
        class_id=school_class.id, due_date=MONDAY, subject_name="Физика", text="№ 2"
    )
    session.add_all([algebra, physics])
    await session.commit()

    real_find = homework_service._find
    stale: list[bool] = []

    async def stale_once(*args: Any, **kwargs: Any) -> Any:
        if not stale:
            stale.append(True)
            return None
        return await real_find(*args, **kwargs)

    monkeypatch.setattr(homework_service, "_find", stale_once)
    with pytest.raises(homework_service.HomeworkExists):
        await homework_service.update(
            session, school_class, EDITOR, physics, {"subject": "Алгебра"}
        )
    assert stale, "the stale read never happened, so nothing was tested"
    # The savepoint must have isolated the clash: the session goes on being
    # usable, and the two original rows are exactly as they were. The failed
    # flush expired ``physics`` (the row the savepoint rolled back), so it is
    # read again rather than read off the stale Python attribute.
    assert await session.scalar(select(func.count()).select_from(Homework)) == 2
    await session.refresh(physics)
    assert (algebra.subject_name, physics.subject_name) == ("Алгебра", "Физика")


def test_the_sentences_both_versions_answer_with_are_v1_s() -> None:
    assert wording.UNKNOWN_HOMEWORK_DETAIL == "Unknown homework"
    assert wording.UNKNOWN_EVENT_DETAIL == "Unknown event"
    assert clock.DATE_OUT_OF_BOUNDS == "date must be between 2000-01-01 and 2100-01-01"
