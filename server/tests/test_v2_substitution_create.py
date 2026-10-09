"""``SubstitutionService.CreateSubstitution``: a new substitution, never a second one for a lesson.

v1's ``PUT /overrides`` over v2, through ``substitutions.create``: the same
cleaning by v1's ``OverrideIn``, the class's spelling of the subject, the same
three questions in v1's words, the same line in the journal and the same
notice to the class. Where v1 changed the row there, v2 refuses a lesson that
already has a substitution that day with ``RESOURCE_EXISTS``. A substitution
nobody would see is refused with its reason — ``NO_LESSON_ON_DAY`` and its
``why``, ``NO_BELL_FOR_LESSON`` and ``LESSON_NOT_ON_TIMETABLE`` with the
``index`` — which leave ``LATER`` here. The notice is an effect: it goes out
once the row is committed, never on a refusal and never to its author. A
write's success is asked once per transport on fresh data, and its refusals
through ``both``; the database is read from sessions of their own.
"""

from __future__ import annotations

from datetime import date, datetime
from datetime import time as Time
from typing import Any

from sqlalchemy import func, select

from app import wording
from app.contract.lessons.v2.substitution_pb import (
    CreateSubstitutionRequest,
    Substitution,
    SubstitutionAction,
)
from app.db import SessionLocal
from app.models import (
    AuditEntry,
    BellPeriod,
    BellSchedule,
    DayKind,
    DayOverride,
    LessonOverride,
    OverrideAction,
    Subject,
    Term,
)
from app.rpc.errors import SUBSTITUTION_EXISTS
from app.rpc.substitution import ACTION_REFUSED
from app.services import audit, clock, substitutions

CREATE = "SubstitutionService/CreateSubstitution"
REPLACE, CANCEL = SubstitutionAction.REPLACE, SubstitutionAction.CANCEL
#: A Monday of the school year: the template has lessons 1 to 3 on a Monday,
#: and the class's bells ring 1 to 7.
MONDAY = date(2026, 9, 14)
#: 14 September, as people read it on Monday the 7th.
WHEN = "14 сентября (понедельник)"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _pin_clock(monkeypatch) -> None:
    monkeypatch.setattr(
        clock, "now", lambda school_class: datetime(2026, 9, 7, 10, 0, tzinfo=school_class.tz)
    )


def _create(day: date, index: int, **fields: Any) -> CreateSubstitutionRequest:
    return CreateSubstitutionRequest(
        substitution=Substitution(date=day.isoformat(), index=index, **fields)
    )


def _v1(day: date, index: int, action: str, **fields: Any) -> dict[str, Any]:
    """The same substitution as v1's ``PUT /overrides`` takes it."""
    return {"date": day.isoformat(), "index": index, "action": action, **fields}


async def _committed(statement: Any) -> Any:
    async with SessionLocal() as fresh:
        return await fresh.scalar(statement)


async def _rows() -> list[tuple[Any, ...]]:
    async with SessionLocal() as fresh:
        rows = await fresh.execute(
            select(
                LessonOverride.date,
                LessonOverride.index,
                LessonOverride.action,
                LessonOverride.subject_name,
                LessonOverride.room,
            ).order_by(LessonOverride.date, LessonOverride.index)
        )
        return [tuple(row) for row in rows]


async def _lines() -> list[tuple[str, str]]:
    async with SessionLocal() as fresh:
        rows = await fresh.execute(
            select(AuditEntry.action, AuditEntry.summary).order_by(AuditEntry.id)
        )
        return [tuple(row) for row in rows]


async def test_a_new_substitution_answers_201_in_the_class_s_spelling_and_is_v1_s_to_read(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    session.add(Subject(class_id=school_class.id, name="Химия"))
    await session.commit()
    editor = v2_tokens["editor"]
    rest = await v2.rest(
        CREATE,
        _create(MONDAY, 2, action=REPLACE, subject="  химия ", room=" 118 ", id=777),
        token=editor,
    )
    connect = await v2.connect(
        CREATE, _create(MONDAY, 1, action=CANCEL, note="учитель болеет"), token=editor
    )
    assert (rest.status, connect.status) == (201, 200)
    made = rest.message.substitution
    # The class's spelling, the room trimmed; the id is the server's.
    assert (made.date, made.index, made.action, made.subject, made.room) == (
        "2026-09-14",
        2,
        REPLACE,
        "Химия",
        "118",
    )
    assert made.id != 777
    assert connect.message.substitution.note == "учитель болеет"
    v1 = await v2.http.get(
        "/api/v1/bundle", params={"start": "2026-09-14", "days": 1}, headers=_auth(editor)
    )
    lessons = v1.json()["days"][0]["lessons"]
    assert (lessons[0]["is_cancelled"], lessons[0]["note"]) == (True, "учитель болеет")
    assert (lessons[1]["subject"], lessons[1]["room"], lessons[1]["is_replaced"]) == (
        "Химия",
        "118",
        True,
    )
    assert await _lines() == [
        ("override.replace", f"Замена: урок №2, {WHEN} — Химия"),
        ("override.cancel", f"Урок №1 отменён, {WHEN}"),
    ]


async def test_a_new_substitution_is_announced_after_the_commit_and_not_to_its_author(
    v2, v2_tokens, notices, subscribers, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    notices.looks = lambda: _committed(select(func.count()).select_from(LessonOverride))
    answer = await v2.connect(
        CREATE,
        _create(MONDAY, 2, action=REPLACE, subject="Химия <b>", room="118", note="зал <2>"),
        token=v2_tokens["editor"],
    )
    assert answer.status == 200
    notice = f"🔁 Замена {WHEN}: урок №2 — <b>Химия &lt;b&gt;</b>, каб. 118\nзал &lt;2&gt;"
    # v1's words, to those who asked to hear about changes but its author,
    # and only once a session of the bot's own could read the row.
    assert sorted(notices.sent) == [(7001, notice), (7003, notice)]
    assert notices.saw == [1, 1]
    assert notices.closed == notices.built == 1


async def test_a_lesson_that_already_has_a_substitution_that_day_is_refused_as_existing(
    v2, v2_tokens, notices, subscribers
) -> None:
    editor = v2_tokens["editor"]
    v1 = await v2.http.put(
        "/api/v1/overrides", json=_v1(MONDAY, 2, "replace", subject="Химия"), headers=_auth(editor)
    )
    assert v1.status_code == 200
    # v1's own notice, through its own seam, to the two who asked for changes.
    assert (notices.built, len(notices.sent)) == (1, 2)
    for action in (CANCEL, REPLACE):
        answer = await v2.both(
            CREATE, _create(MONDAY, 2, action=action, subject="Физика"), token=editor
        )
        assert (answer.status, answer.code, answer.reason) == (
            409,
            "ALREADY_EXISTS",
            "RESOURCE_EXISTS",
        ), action
        assert (answer.metadata, answer.error) == (
            {"resource": "substitution", "field": "index"},
            SUBSTITUTION_EXISTS,
        )
    assert await _rows() == [(MONDAY, 2, OverrideAction.REPLACE, "Химия", None)]
    assert [action for action, _ in await _lines()] == ["override.replace"]
    # The refusals built no bot and told nobody.
    assert (notices.built, len(notices.sent)) == (1, 2)


async def test_a_twin_written_in_the_same_instant_is_refused_as_existing(
    v2, v2_tokens, session, school_class, notices, subscribers, monkeypatch
) -> None:
    """The race: the check answers «none», as it truthfully did a moment
    earlier, while the twin is already in the table. The insert meets the
    unique constraint inside its savepoint, and the create is refused as if
    the check had seen it."""
    session.add(
        LessonOverride(class_id=school_class.id, date=MONDAY, index=2, action=OverrideAction.CANCEL)
    )
    await session.commit()
    real_on = substitutions.substitution_on
    stale: list[bool] = []

    async def stale_once(*args: Any, **kwargs: Any) -> Any:
        if not stale:
            stale.append(True)
            return None
        return await real_on(*args, **kwargs)

    monkeypatch.setattr(substitutions, "substitution_on", stale_once)
    answer = await v2.connect(
        CREATE, _create(MONDAY, 2, action=REPLACE, subject="Химия"), token=v2_tokens["editor"]
    )
    assert stale, "the stale read never happened, so nothing was tested"
    assert (answer.status, answer.reason, answer.error) == (
        409,
        "RESOURCE_EXISTS",
        SUBSTITUTION_EXISTS,
    )
    assert await _rows() == [(MONDAY, 2, OverrideAction.CANCEL, None, None)]
    assert notices.built == 0


async def test_a_day_that_draws_no_lessons_is_refused_with_why(
    v2, v2_tokens, session, school_class, notices, subscribers
) -> None:
    """The four reasons a day draws nothing, each in v1's sentence and with
    the reason a client acts on. The class keeps quarters with a gap in the
    autumn, so that a term's own dates decide two of them."""
    for index, (starts, ends) in enumerate(
        (
            (date(2026, 9, 1), date(2026, 10, 24)),
            (date(2026, 11, 5), date(2026, 12, 28)),
            (date(2027, 1, 9), date(2027, 3, 20)),
            (date(2027, 4, 1), date(2027, 5, 31)),
        ),
        start=1,
    ):
        session.add(
            Term(class_id=school_class.id, year=2026, index=index, starts_on=starts, ends_on=ends)
        )
    marked = date(2026, 9, 21)
    session.add(DayOverride(class_id=school_class.id, date=marked, kind=DayKind.DAY_OFF))
    await session.commit()
    editor = v2_tokens["editor"]
    for day, why in (
        (date(2027, 6, 14), "out_of_year"),
        (date(2026, 11, 2), "between_terms"),
        (date(2027, 3, 8), "public_holiday"),
        (marked, "marked_day_off"),
    ):
        answer = await v2.both(
            CREATE, _create(day, 1, action=REPLACE, subject="Химия"), token=editor
        )
        assert (answer.status, answer.code, answer.reason, answer.metadata) == (
            400,
            "FAILED_PRECONDITION",
            "NO_LESSON_ON_DAY",
            {"why": why},
        ), why
        v1 = await v2.http.put(
            "/api/v1/overrides", json=_v1(day, 1, "replace", subject="Химия"), headers=_auth(editor)
        )
        assert (v1.status_code, v1.json()["detail"]) == (422, answer.error), why
    assert await _rows() == []
    assert notices.built == 0


async def test_a_number_the_day_rings_no_bell_for_is_refused(
    v2, v2_tokens, session, school_class, notices, subscribers
) -> None:
    """Against the day's own bells: a shortened day rings a shorter schedule
    than the class's usual, and a lesson past it would be drawn nowhere."""
    short = BellSchedule(class_id=school_class.id, name="Сокращённое")
    session.add(short)
    await session.flush()
    for index in (1, 2, 3):
        session.add(
            BellPeriod(
                schedule_id=short.id,
                index=index,
                starts_at=Time(8 + index, 0),
                ends_at=Time(8 + index, 30),
            )
        )
    session.add(
        DayOverride(
            class_id=school_class.id, date=MONDAY, kind=DayKind.SHORTENED, bell_schedule_id=short.id
        )
    )
    await session.commit()
    editor = v2_tokens["editor"]
    for day, index in ((MONDAY, 4), (date(2026, 9, 21), 8)):
        answer = await v2.both(
            CREATE, _create(day, index, action=REPLACE, subject="Химия"), token=editor
        )
        assert (answer.status, answer.code, answer.reason, answer.metadata) == (
            400,
            "FAILED_PRECONDITION",
            "NO_BELL_FOR_LESSON",
            {"index": str(index)},
        ), index
        assert answer.error == wording.no_bell_detail(index)
        v1 = await v2.http.put(
            "/api/v1/overrides",
            json=_v1(day, index, "replace", subject="Химия"),
            headers=_auth(editor),
        )
        assert (v1.status_code, v1.json()["detail"]) == (422, answer.error)
    assert await _rows() == []
    assert notices.built == 0


async def test_cancelling_or_a_bare_room_where_the_template_has_no_lesson_is_refused(
    v2, v2_tokens, notices, subscribers
) -> None:
    """Lesson 7 rings on a Monday and the template leaves it empty: a
    cancellation would strike through nothing, and a room alone would inherit
    no subject. A replacement that brings its own subject is how a lesson is
    added, and is created."""
    editor = v2_tokens["editor"]
    for fields, v1_fields, cancelling in (
        ({"action": CANCEL}, {"action": "cancel"}, True),
        ({"action": REPLACE, "room": "305"}, {"action": "replace", "room": "305"}, False),
    ):
        answer = await v2.both(CREATE, _create(MONDAY, 7, **fields), token=editor)
        assert (answer.status, answer.code, answer.reason, answer.metadata) == (
            400,
            "FAILED_PRECONDITION",
            "LESSON_NOT_ON_TIMETABLE",
            {"index": "7"},
        ), cancelling
        assert answer.error == wording.lesson_not_on_timetable_detail(7, cancelling=cancelling)
        v1 = await v2.http.put(
            "/api/v1/overrides",
            json={"date": MONDAY.isoformat(), "index": 7, **v1_fields},
            headers=_auth(editor),
        )
        assert (v1.status_code, v1.json()["detail"]) == (422, answer.error)
    assert notices.built == 0
    added = await v2.rest(
        CREATE, _create(MONDAY, 7, action=REPLACE, subject="Астрономия"), token=editor
    )
    assert (added.status, added.message.substitution.subject) == (201, "Астрономия")


async def test_a_refused_substitution_is_refused_on_its_field_and_tells_nobody(
    v2, v2_tokens, notices, subscribers
) -> None:
    editor = v2_tokens["editor"]
    bounded = await v2.both(CREATE, _create(date(2200, 1, 1), 1, action=CANCEL), token=editor)
    assert bounded.violations == [("substitution.date", clock.DATE_OUT_OF_BOUNDS)]
    for request, field_name in (
        (_create(MONDAY, 0, action=CANCEL), "substitution.index"),
        (_create(MONDAY, 21, action=CANCEL), "substitution.index"),
        (_create(MONDAY, 1), "substitution.action"),
        (_create(MONDAY, 1, action=REPLACE, note="только заметка"), "substitution"),
        (_create(MONDAY, 1, action=REPLACE, subject="х" * 121), "substitution.subject"),
        (_create(MONDAY, 1, action=REPLACE, room="1" * 33), "substitution.room"),
        (
            CreateSubstitutionRequest(
                substitution=Substitution(date="14.09.2026", index=1, action=CANCEL)
            ),
            "substitution.date",
        ),
    ):
        answer = await v2.both(CREATE, request, token=editor)
        assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED"), field_name
        assert [name for name, _ in answer.violations] == [field_name]
    # A number SubstitutionAction does not name arrives in the binary encoding only.
    unknown = await v2.connect(
        CREATE,
        _create(MONDAY, 1, action=SubstitutionAction(99)),
        token=editor,
        binary=True,
    )
    assert (unknown.reason, unknown.violations) == (
        "VALIDATION_FAILED",
        [("substitution.action", ACTION_REFUSED)],
    )
    assert await _rows() == []
    assert notices.built == 0


async def test_a_substitution_that_fails_after_its_row_is_written_leaves_no_row_and_tells_nobody(
    v2, v2_tokens, notices, subscribers, monkeypatch
) -> None:
    """The row goes in through a savepoint, the first write of the call's
    transaction, and then the journal's line fails: ``invoke``'s one commit
    makes it all or nothing, on SQLite as on Postgres (#373)."""

    async def broken(*args: Any, **kwargs: Any) -> None:
        raise RuntimeError("the journal is down")

    monkeypatch.setattr(audit, "record", broken)
    answer = await v2.connect(
        CREATE, _create(MONDAY, 2, action=REPLACE, subject="Химия"), token=v2_tokens["editor"]
    )
    assert (answer.status, answer.code) == (500, "INTERNAL")
    assert await _committed(select(func.count()).select_from(LessonOverride)) == 0
    assert notices.built == 0
