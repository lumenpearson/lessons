"""``HomeworkService.CreateHomework``: a new assignment, never a second one for a subject on a day.

v1's ``PUT /homework`` over v2, through ``homework.create``: the same cleaning
by v1's ``HomeworkIn``, the class's spelling of the subject, the same line in
the journal and the same notice to the class. Where v1 upserted, v2 refuses a
subject that already has homework that day with ``RESOURCE_EXISTS``. The
notice is an effect: it goes out once the assignment is committed, never on a
refusal and never to its author (``docs/specs/2026-10-05-server-v2-design.md``,
decision 4). A write's success is asked once per transport on fresh data, and
its refusals through ``both`` (the 3b plan, Ruling 17). The database is read
from sessions of their own, which see only what is committed.
"""

from __future__ import annotations

from datetime import date, datetime
from typing import Any

from sqlalchemy import func, select

from app.contract.lessons.v2.homework_pb import CreateHomeworkRequest, Homework
from app.db import SessionLocal
from app.models import AuditEntry, Subject
from app.models import Homework as HomeworkRow
from app.rpc.errors import HOMEWORK_EXISTS
from app.services import audit, clock
from app.services import homework as homework_service

#: The account behind v2_tokens' editor phone, who writes.
EDITOR = 2002
CREATE = "HomeworkService/CreateHomework"
DUE = "2026-09-14"
#: 14 September, as people read it on Monday the 7th.
WHEN = "14 сентября (понедельник)"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _pin_clock(monkeypatch) -> None:
    monkeypatch.setattr(
        clock, "now", lambda school_class: datetime(2026, 9, 7, 10, 0, tzinfo=school_class.tz)
    )


def _create(subject: str, text: str, **fields: Any) -> CreateHomeworkRequest:
    fields.setdefault("due_date", DUE)
    return CreateHomeworkRequest(homework=Homework(subject=subject, text=text, **fields))


async def _committed(statement: Any) -> Any:
    async with SessionLocal() as fresh:
        return await fresh.scalar(statement)


async def _rows() -> list[tuple[Any, ...]]:
    async with SessionLocal() as fresh:
        rows = await fresh.execute(
            select(
                HomeworkRow.due_date,
                HomeworkRow.subject_name,
                HomeworkRow.text,
                HomeworkRow.created_by,
            ).order_by(HomeworkRow.id)
        )
        return [tuple(row) for row in rows]


async def _lines() -> list[tuple[str, str]]:
    async with SessionLocal() as fresh:
        rows = await fresh.execute(
            select(AuditEntry.action, AuditEntry.summary).order_by(AuditEntry.id)
        )
        return [tuple(row) for row in rows]


async def test_a_new_assignment_answers_201_in_the_class_s_spelling_and_is_v1_s_to_read(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    session.add(Subject(class_id=school_class.id, name="Алгебра"))
    await session.commit()
    editor = v2_tokens["editor"]
    rest = await v2.rest(
        CREATE,
        _create(
            "  алгебра ",
            "№ 12–15\nустно § 4",
            attachment_url=" https://example.com/p.pdf ",
            id=777,
            done=True,
        ),
        token=editor,
    )
    connect = await v2.connect(CREATE, _create("Физика", "§ 3"), token=editor)
    assert (rest.status, connect.status) == (201, 200)
    made = rest.message.homework
    # The class's spelling, the line breaks kept, the address trimmed; the id
    # and the tick are the server's.
    assert (made.due_date, made.subject, made.text, made.attachment_url, made.done) == (
        DUE,
        "Алгебра",
        "№ 12–15\nустно § 4",
        "https://example.com/p.pdf",
        False,
    )
    assert made.id != 777
    v1 = await v2.http.get(
        "/api/v1/homework", params={"from": DUE, "to": DUE}, headers=_auth(editor)
    )
    assert [row["id"] for row in v1.json()] == [made.id, connect.message.homework.id]
    assert await _lines() == [
        ("homework.add", f"ДЗ добавлено: Алгебра, {WHEN}"),
        ("homework.add", f"ДЗ добавлено: Физика, {WHEN}"),
    ]


async def test_a_new_assignment_is_announced_after_the_commit_and_not_to_its_author(
    v2, v2_tokens, notices, subscribers, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    notices.looks = lambda: _committed(select(func.count()).select_from(HomeworkRow))
    answer = await v2.connect(CREATE, _create("Алгебра", "№ 12–15 <b>"), token=v2_tokens["editor"])
    assert answer.status == 200
    notice = f"📝 Задание добавлено: <b>Алгебра</b> {WHEN}\n№ 12–15 &lt;b&gt;"
    # v1's words, to those who asked for homework but its author, and only
    # once a session of the bot's own could read the assignment.
    assert sorted(notices.sent) == [(7001, notice), (7002, notice)]
    assert notices.saw == [1, 1]
    assert notices.closed == notices.built == 1


async def test_a_subject_that_already_has_homework_that_day_is_refused_as_existing(
    v2, v2_tokens, session, school_class, notices, subscribers
) -> None:
    session.add(Subject(class_id=school_class.id, name="Алгебра"))
    await session.commit()
    editor = v2_tokens["editor"]
    v1 = await v2.http.put(
        "/api/v1/homework",
        json={"due_date": DUE, "subject": "Алгебра", "text": "№ 1"},
        headers=_auth(editor),
    )
    assert v1.status_code == 200
    # v1's own notice, through its own seam, to the two who asked for homework.
    assert (notices.built, len(notices.sent)) == (1, 2)
    # In any case: the class's spelling decides sameness, as in v1's upsert.
    for subject in ("Алгебра", "  АЛГЕБРА "):
        answer = await v2.both(CREATE, _create(subject, "№ 2"), token=editor)
        assert (answer.status, answer.code, answer.reason) == (
            409,
            "ALREADY_EXISTS",
            "RESOURCE_EXISTS",
        ), subject
        assert (answer.metadata, answer.error) == (
            {"resource": "homework", "field": "subject"},
            HOMEWORK_EXISTS,
        )
    assert await _rows() == [(date(2026, 9, 14), "Алгебра", "№ 1", EDITOR)]
    assert [action for action, _ in await _lines()] == ["homework.add"]
    # The refusals built no bot and told nobody.
    assert (notices.built, len(notices.sent)) == (1, 2)


async def test_a_twin_written_in_the_same_instant_is_refused_as_existing(
    v2, v2_tokens, session, school_class, notices, subscribers, monkeypatch
) -> None:
    """The race, staged as ``test_homework_upsert.py`` stages it: the check
    answers «nothing here», as it truthfully did a moment earlier, while the
    twin is already in the table. The insert meets the unique constraint
    inside its savepoint, and the create is refused as if the check had seen
    it."""
    session.add(
        HomeworkRow(
            class_id=school_class.id, due_date=date(2026, 9, 14), subject_name="Алгебра", text="№ 1"
        )
    )
    await session.commit()
    real_find = homework_service._find
    stale: list[bool] = []

    async def stale_once(*args: Any, **kwargs: Any) -> Any:
        if not stale:
            stale.append(True)
            return None
        return await real_find(*args, **kwargs)

    monkeypatch.setattr(homework_service, "_find", stale_once)
    answer = await v2.connect(CREATE, _create("Алгебра", "№ 2"), token=v2_tokens["editor"])
    assert stale, "the stale read never happened, so nothing was tested"
    assert (answer.status, answer.reason, answer.error) == (409, "RESOURCE_EXISTS", HOMEWORK_EXISTS)
    assert await _rows() == [(date(2026, 9, 14), "Алгебра", "№ 1", None)]
    assert notices.built == 0


async def test_a_refused_assignment_is_refused_on_its_field_and_tells_nobody(
    v2, v2_tokens, notices, subscribers
) -> None:
    editor = v2_tokens["editor"]
    v1 = await v2.http.put(
        "/api/v1/homework",
        json={"due_date": "2200-01-01", "subject": "Алгебра", "text": "№ 1"},
        headers=_auth(editor),
    )
    assert (v1.status_code, v1.json()["detail"]) == (422, clock.DATE_OUT_OF_BOUNDS)
    bounded = await v2.both(CREATE, _create("Алгебра", "№ 1", due_date="2200-01-01"), token=editor)
    assert (bounded.status, bounded.reason, bounded.error) == (
        400,
        "VALIDATION_FAILED",
        clock.DATE_OUT_OF_BOUNDS,
    )
    assert bounded.violations == [("homework.due_date", clock.DATE_OUT_OF_BOUNDS)]
    for sent, field_name in (
        (_create("Алгебра", "   "), "homework.text"),
        (_create("х" * 121, "№ 1"), "homework.subject"),
        (_create("Алгебра", "№ 1", attachment_url="u" * 501), "homework.attachment_url"),
        (_create("Алгебра", "№ 1", due_date="14.09.2026"), "homework.due_date"),
    ):
        answer = await v2.both(CREATE, sent, token=editor)
        assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED"), field_name
        assert [name for name, _ in answer.violations] == [field_name]
    assert await _committed(select(func.count()).select_from(HomeworkRow)) == 0
    assert notices.built == 0


async def test_an_assignment_that_fails_after_its_row_is_written_leaves_no_row_and_tells_nobody(
    v2, v2_tokens, notices, subscribers, monkeypatch
) -> None:
    """The row goes in through a savepoint, the first write of the call's
    transaction, and then the journal's line fails. ``invoke``'s one commit
    makes it all or nothing, and on SQLite that holds now that a savepoint
    stays inside the transaction as it does on Postgres (#373): before, its
    release committed the row by itself."""

    async def broken(*args: Any, **kwargs: Any) -> None:
        raise RuntimeError("the journal is down")

    monkeypatch.setattr(audit, "record", broken)
    answer = await v2.connect(CREATE, _create("Алгебра", "№ 1"), token=v2_tokens["editor"])
    assert (answer.status, answer.code) == (500, "INTERNAL")
    assert await _committed(select(func.count()).select_from(HomeworkRow)) == 0
    assert notices.built == 0
