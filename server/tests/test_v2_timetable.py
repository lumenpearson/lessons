"""``TimetableService``: the weekly template as text, out and back in.

``GetTimetable`` is v1's ``GET /manage/timetable`` byte for byte.
``ImportTimetable`` is v1's ``POST /manage/timetable/import`` through the same
``import_paste``: the same conflicts, the same line for each lesson dropped or
silenced, the same refusal of a paste with no day in it. What v2 adds is
``validate_only``, the bot's preview before «Применить», answered as a success
with nothing written (AIP-163;
``docs/specs/2026-10-05-server-v2-3b-plan.md``, Ruling 24). An import that
writes is asked once per transport on fresh data, and everything that writes
nothing through ``both`` (Ruling 17).
"""

from __future__ import annotations

from sqlalchemy import delete, select

from app import wording
from app.contract.lessons.v2.timetable_pb import ImportConflict, ImportTimetableRequest
from app.models import AuditEntry, BellPeriod, TimetableEntry

#: A Monday the fixture already teaches three lessons on, and a line no
#: parser reads.
CONFLICTING = "== Понедельник ==\n1. Химия, 118\nне строка\n2. Биология"

#: A Tuesday with an eighth lesson, under bells that ring two: the eighth has
#: no bell, and Monday's third, which the paste leaves alone, stops ringing.
DROPPING = (
    "== Вторник ==\n1. Химия\n8. Физика\n\n"
    "== Звонки ==\n1. 09:00-09:40\n2. 09:50-10:30\n"
)


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _actions(session) -> list[str]:
    return list(await session.scalars(select(AuditEntry.action).order_by(AuditEntry.id)))


async def _names(session, school_class) -> set[str]:
    return set(
        await session.scalars(
            select(TimetableEntry.subject_name).where(TimetableEntry.class_id == school_class.id)
        )
    )


def _plain(answer) -> dict:
    """A v2 import answer as v1's ``TimetableImportOut`` writes it."""
    message = answer.message
    return {
        "applied": message.applied,
        "days": list(message.days),
        "lessons": message.lessons,
        "bells": message.bells,
        "conflicts": [
            {"weekday": row.weekday, "existing": row.existing, "incoming": row.incoming}
            for row in message.conflicts
        ],
        "rejected": list(message.rejected),
    }


async def test_the_timetable_is_v1_s_export_byte_for_byte(v2, v2_tokens) -> None:
    admin = v2_tokens["admin"]
    v1 = (await v2.http.get("/api/v1/manage/timetable", headers=_auth(admin))).json()
    answer = await v2.both("TimetableService/GetTimetable", token=admin)
    timetable = answer.message.timetable
    assert (timetable.text, timetable.lessons) == (v1["text"], v1["lessons"])
    assert timetable.lessons == 3
    assert "1. Алгебра, 214" in timetable.text


async def test_a_class_with_no_lessons_answers_its_bells_and_no_error(
    v2, v2_tokens, session, school_class
) -> None:
    await session.execute(delete(TimetableEntry).where(TimetableEntry.class_id == school_class.id))
    await session.commit()
    admin = v2_tokens["admin"]
    v1 = (await v2.http.get("/api/v1/manage/timetable", headers=_auth(admin))).json()
    answer = await v2.both("TimetableService/GetTimetable", token=admin)
    assert answer.status == 200
    assert answer.message.timetable.lessons == 0
    assert answer.message.timetable.text == v1["text"]
    assert answer.message.timetable.text.startswith("== Звонки ==")


async def test_reading_the_timetable_writes_nothing_but_the_last_seen(
    v2, v2_tokens, statement_writes, unexpected_writes, last_seen_rule
) -> None:
    with statement_writes() as seen:
        answer = await v2.both("TimetableService/GetTimetable", token=v2_tokens["admin"])
    assert answer.status == 200
    assert answer.message.timetable.lessons == 3
    # A write happened, the phone's last call, and nothing else did.
    assert seen
    assert unexpected_writes(seen) == []
    assert all(last_seen_rule.match(statement) for statement in seen), seen


async def test_validate_only_writes_nothing_and_answers_the_preview(
    v2, v2_tokens, session, school_class, statement_writes, unexpected_writes, last_seen_rule
) -> None:
    """The bot's preview before «Применить»: the paste read and nothing
    written, even with ``replace`` set. It is the answer v1 gave a paste over
    lessons: the paste's own counts, its conflicts and the parser's lines."""
    admin = v2_tokens["admin"]
    request = ImportTimetableRequest(text=CONFLICTING, replace=True, validate_only=True)
    with statement_writes() as seen:
        answer = await v2.both("TimetableService/ImportTimetable", request, token=admin)
    assert answer.status == 200
    # The phone's last call is the one write, with replace set or not.
    assert seen
    assert unexpected_writes(seen) == []
    assert all(last_seen_rule.match(statement) for statement in seen), seen
    assert list(answer.message.conflicts) == [ImportConflict(weekday=1, existing=3, incoming=2)]
    v1 = await v2.http.post(
        "/api/v1/manage/timetable/import", json={"text": CONFLICTING}, headers=_auth(admin)
    )
    assert v1.json()["applied"] is False
    assert _plain(answer) == v1.json()
    assert "Химия" not in await _names(session, school_class)
    assert await _actions(session) == []


async def test_a_paste_over_lessons_lists_its_conflicts_and_writes_nothing(
    v2, v2_tokens, session, school_class
) -> None:
    admin = v2_tokens["admin"]
    answer = await v2.both(
        "TimetableService/ImportTimetable", ImportTimetableRequest(text=CONFLICTING), token=admin
    )
    v1 = await v2.http.post(
        "/api/v1/manage/timetable/import", json={"text": CONFLICTING}, headers=_auth(admin)
    )
    assert answer.status == 200
    assert answer.message.applied is False
    assert _plain(answer) == v1.json()
    assert "Химия" not in await _names(session, school_class)
    assert await _actions(session) == []


async def test_a_paste_is_applied_on_either_path(v2, v2_tokens, session, school_class) -> None:
    admin = v2_tokens["admin"]
    text = (
        "== Понедельник ==\n1. Химия, 118\n2. Биология\n\n"
        "== Вторник ==\n1. История\n\n"
        "== Звонки ==\n1. 09:00-09:40\n2. 09:50-10:30\n"
    )
    rest = await v2.rest(
        "TimetableService/ImportTimetable",
        ImportTimetableRequest(text=text, replace=True),
        token=admin,
    )
    assert rest.status == 200
    message = rest.message
    assert (message.applied, list(message.days), message.lessons, message.bells) == (
        True,
        [1, 2],
        3,
        2,
    )
    assert list(message.conflicts) == [ImportConflict(weekday=1, existing=3, incoming=2)]
    assert await _names(session, school_class) == {"Химия", "Биология", "История"}
    periods = await session.scalars(
        select(BellPeriod.index).where(BellPeriod.schedule_id == school_class.bell_schedule_id)
    )
    assert sorted(periods) == [1, 2]
    # A weekday the timetable leaves empty is no conflict, and needs no `replace`.
    connect = await v2.connect(
        "TimetableService/ImportTimetable",
        ImportTimetableRequest(text="== Среда ==\n1. Физика"),
        token=admin,
    )
    assert connect.status == 200
    assert (connect.message.applied, list(connect.message.days), connect.message.lessons) == (
        True,
        [3],
        1,
    )
    assert list(connect.message.conflicts) == []
    assert await _actions(session) == ["timetable.import", "timetable.import"]


async def test_a_dropped_and_a_silenced_lesson_are_named_in_v1_s_words(
    v2, v2_tokens, session
) -> None:
    """Tuesday's eighth lesson has no bell, and Monday's third, which the paste
    leaves alone, stops ringing under the paste's two bells: one line each."""
    answer = await v2.rest(
        "TimetableService/ImportTimetable",
        ImportTimetableRequest(text=DROPPING),
        token=v2_tokens["admin"],
    )
    assert answer.status == 200
    assert (answer.message.applied, answer.message.lessons) == (True, 1)
    assert list(answer.message.rejected) == [
        "вторник, урок 8: нет такого звонка в расписании звонков",
        "понедельник, урок 3: больше не звонит — новые звонки короче",
    ]
    assert await session.scalar(select(AuditEntry.summary)) == (
        "импорт расписания: дней 1, уроков 1, звонков 2, без звонка пропущено 1, "
        "перестали звонить 1"
    )


async def test_a_paste_with_no_day_in_it_is_refused_on_its_text(v2, v2_tokens, session) -> None:
    admin = v2_tokens["admin"]
    prose = "просто текст без заголовков"
    v1 = await v2.http.post(
        "/api/v1/manage/timetable/import", json={"text": prose}, headers=_auth(admin)
    )
    answer = await v2.both(
        "TimetableService/ImportTimetable",
        ImportTimetableRequest(text=prose, replace=True),
        token=admin,
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "INVALID_ARGUMENT",
        "VALIDATION_FAILED",
    )
    assert answer.violations == [("text", wording.TIMETABLE_PASTE_EMPTY_DETAIL)]
    assert answer.error == v1.json()["detail"] == wording.TIMETABLE_PASTE_EMPTY_DETAIL
    assert v1.status_code == 422
    empty = await v2.both(
        "TimetableService/ImportTimetable", ImportTimetableRequest(text=""), token=admin
    )
    assert empty.reason == "VALIDATION_FAILED"
    assert [field for field, _ in empty.violations] == ["text"]
    assert await _actions(session) == []
