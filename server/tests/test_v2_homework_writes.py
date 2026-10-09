"""``HomeworkService``'s changes: ``UpdateHomework`` and ``DeleteHomework``.

v1 changed an assignment by sending it again (``PUT /homework``) and deleted
it with ``DELETE /homework/{id}``; v2 changes it in place, through
``homework.update``, and deletes it through ``homework.delete``. The mask is
read once, by ``masks.update_paths`` (AIP-134): no mask changes what the
request sets; a masked address left unset is taken away, and a masked date,
subject or text left unset is refused, because none of them can be cleared.
Moving an assignment onto a subject that has homework that day is
``RESOURCE_EXISTS`` (the 3b plan, «Rulings for 3b-5»). Each change is
announced once committed, in v1's words and never to its author; a change
that changes nothing writes nothing and tells nobody. A write's success is
asked once per transport on fresh data, and its refusals through ``both``.
"""

from __future__ import annotations

from datetime import date, datetime
from typing import Any

from protobuf.wkt import FieldMask
from sqlalchemy import func, select

from app import wording
from app.contract.lessons.v2.homework_pb import (
    DeleteHomeworkRequest,
    GetHomeworkRequest,
    Homework,
    UpdateHomeworkRequest,
)
from app.db import SessionLocal
from app.models import AuditEntry, HomeworkDone, PersonalTask, SchoolClass, Subject
from app.models import Homework as HomeworkRow
from app.rpc.errors import HOMEWORK_EXISTS
from app.rpc.masks import NOT_CHANGEABLE
from app.services import clock
from app.services import homework as homework_service

#: The accounts behind v2_tokens' viewer and editor phones.
VIEWER = 2001
EDITOR = 2002
UPDATE = "HomeworkService/UpdateHomework"
DELETE = "HomeworkService/DeleteHomework"
MONDAY = date(2026, 9, 14)
TUESDAY = date(2026, 9, 15)


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _pin_clock(monkeypatch) -> None:
    """Monday 7 September, so the 14th reads as «14 сентября (понедельник)»."""
    monkeypatch.setattr(
        clock, "now", lambda school_class: datetime(2026, 9, 7, 10, 0, tzinfo=school_class.tz)
    )


async def _homework(
    session, school_class, subject: str = "Алгебра", due: date = MONDAY, **fields: Any
) -> HomeworkRow:
    row = HomeworkRow(
        class_id=school_class.id, due_date=due, subject_name=subject, text="№ 12–15", **fields
    )
    session.add(row)
    await session.commit()
    return row


def _update(homework_id: int, *paths: str, **fields: Any) -> UpdateHomeworkRequest:
    mask = FieldMask(paths=list(paths)) if paths else None
    return UpdateHomeworkRequest(homework=Homework(id=homework_id, **fields), update_mask=mask)


async def _committed(statement: Any) -> Any:
    async with SessionLocal() as fresh:
        return await fresh.scalar(statement)


async def _columns(homework_id: int) -> tuple[Any, ...]:
    async with SessionLocal() as fresh:
        row = await fresh.execute(
            select(
                HomeworkRow.due_date,
                HomeworkRow.subject_name,
                HomeworkRow.text,
                HomeworkRow.attachment_url,
                HomeworkRow.created_by,
            ).where(HomeworkRow.id == homework_id)
        )
        return tuple(row.one())


async def _lines() -> list[tuple[str, str]]:
    async with SessionLocal() as fresh:
        rows = await fresh.execute(
            select(AuditEntry.action, AuditEntry.summary).order_by(AuditEntry.id)
        )
        return [tuple(row) for row in rows]


async def test_an_update_without_a_mask_changes_only_what_it_sends(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    first = await _homework(session, school_class, attachment_url="https://example.com/p.pdf")
    second = await _homework(session, school_class, "Физика")
    editor = v2_tokens["editor"]
    rest = await v2.rest(UPDATE, _update(first.id, text="№ 16"), token=editor)
    connect = await v2.connect(UPDATE, _update(second.id, due_date="2026-09-15"), token=editor)
    assert (rest.status, connect.status) == (200, 200)
    assert await _columns(first.id) == (
        MONDAY,
        "Алгебра",
        "№ 16",
        "https://example.com/p.pdf",
        EDITOR,
    )
    moved = connect.message.homework
    assert (moved.due_date, moved.subject, moved.text) == ("2026-09-15", "Физика", "№ 12–15")
    v1 = await v2.http.get(
        "/api/v1/homework", params={"from": "2026-09-14", "to": "2026-09-15"}, headers=_auth(editor)
    )
    assert [(row["subject"], row["due_date"], row["text"]) for row in v1.json()] == [
        ("Алгебра", "2026-09-14", "№ 16"),
        ("Физика", "2026-09-15", "№ 12–15"),
    ]
    assert await _lines() == [
        ("homework.update", "ДЗ обновлено: Алгебра, 14 сентября (понедельник)"),
        ("homework.update", "ДЗ обновлено: Физика, 15 сентября (вторник)"),
    ]


async def test_a_masked_address_left_out_is_taken_away_but_a_date_a_subject_or_a_text_is_refused(
    v2, v2_tokens, session, school_class
) -> None:
    row = await _homework(session, school_class, attachment_url="https://example.com/p.pdf")
    editor = v2_tokens["editor"]
    cleared = await v2.rest(UPDATE, _update(row.id, "attachment_url"), token=editor)
    assert cleared.status == 200
    assert not cleared.message.homework.has_field("attachment_url")
    assert (await _columns(row.id))[3] is None
    for path in ("due_date", "subject", "text"):
        refused = await v2.both(UPDATE, _update(row.id, path), token=editor)
        assert (refused.status, refused.reason) == (400, "VALIDATION_FAILED"), path
        assert [name for name, _ in refused.violations] == [f"homework.{path}"]
    bounded = await v2.both(UPDATE, _update(row.id, due_date="2200-01-01"), token=editor)
    assert bounded.violations == [("homework.due_date", clock.DATE_OUT_OF_BOUNDS)]
    assert (await _columns(row.id))[:3] == (MONDAY, "Алгебра", "№ 12–15")


async def test_moving_an_assignment_onto_a_subject_that_has_homework_that_day_is_refused(
    v2, v2_tokens, session, school_class, notices, subscribers
) -> None:
    session.add(Subject(class_id=school_class.id, name="Алгебра"))
    await session.commit()
    algebra = await _homework(session, school_class)
    physics = await _homework(session, school_class, "Физика")
    later = await _homework(session, school_class, due=TUESDAY)
    editor = v2_tokens["editor"]
    # Onto Monday's «Алгебра» by its subject, in another case, and by its day.
    for sent in (
        _update(physics.id, subject=" алгебра "),
        _update(later.id, due_date="2026-09-14"),
    ):
        refused = await v2.both(UPDATE, sent, token=editor)
        assert (refused.status, refused.code, refused.reason) == (
            409,
            "ALREADY_EXISTS",
            "RESOURCE_EXISTS",
        )
        assert (refused.metadata, refused.error) == (
            {"resource": "homework", "field": "subject"},
            HOMEWORK_EXISTS,
        )
    assert (await _columns(physics.id))[:2] == (MONDAY, "Физика")
    assert (await _columns(later.id))[:2] == (TUESDAY, "Алгебра")
    assert await _lines() == []
    assert notices.built == 0
    # Its own subject in another case is no move: the class's spelling is.
    same = await v2.connect(UPDATE, _update(algebra.id, subject="АЛГЕБРА"), token=editor)
    assert (same.status, same.message.homework.subject) == (200, "Алгебра")


async def test_a_twin_moved_onto_in_the_same_instant_is_refused_as_existing(
    v2, v2_tokens, session, school_class, notices, subscribers, monkeypatch
) -> None:
    """The move's own race: the check answers «nothing there» while the twin
    is, and the move meets the unique constraint inside its savepoint and is
    refused as if the check had seen it."""
    await _homework(session, school_class)
    physics = await _homework(session, school_class, "Физика")
    real_find = homework_service._find
    stale: list[bool] = []

    async def stale_once(*args: Any, **kwargs: Any) -> Any:
        if not stale:
            stale.append(True)
            return None
        return await real_find(*args, **kwargs)

    monkeypatch.setattr(homework_service, "_find", stale_once)
    answer = await v2.connect(
        UPDATE, _update(physics.id, subject="Алгебра"), token=v2_tokens["editor"]
    )
    assert stale, "the stale read never happened, so nothing was tested"
    assert (answer.status, answer.reason, answer.error) == (409, "RESOURCE_EXISTS", HOMEWORK_EXISTS)
    assert (await _columns(physics.id))[1] == "Физика"
    assert await _lines() == []
    assert notices.built == 0


async def test_an_update_is_announced_after_the_commit_on_the_day_it_is_on_now(
    v2, v2_tokens, session, school_class, notices, subscribers, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    row = await _homework(session, school_class)
    notices.looks = lambda: _committed(select(HomeworkRow.due_date).where(HomeworkRow.id == row.id))
    answer = await v2.connect(
        UPDATE,
        _update(row.id, "due_date", "text", due_date="2026-09-15", text="№ 16 <i>"),
        token=v2_tokens["editor"],
    )
    assert answer.status == 200
    notice = "📝 Задание обновлено: <b>Алгебра</b> 15 сентября (вторник)\n№ 16 &lt;i&gt;"
    # v1's update branch, to those who asked for homework but its author, and
    # only once the bot's own session read the new day.
    assert sorted(notices.sent) == [(7001, notice), (7002, notice)]
    assert notices.saw == [TUESDAY, TUESDAY]


async def test_an_update_that_changes_nothing_writes_nothing_and_tells_nobody(
    v2, v2_tokens, session, school_class, notices, subscribers, statement_writes
) -> None:
    """A retried update, or one that sends what is there: no line in the
    journal and no second notice. The call before it touched the phone's last
    call, so inside the fifteen minutes there is nothing else it may write."""
    row = await _homework(session, school_class)
    editor = v2_tokens["editor"]
    await v2.rest(
        "HomeworkService/GetHomework", GetHomeworkRequest(homework_id=row.id), token=editor
    )
    with statement_writes() as seen:
        answer = await v2.both(
            UPDATE, _update(row.id, subject="Алгебра", text=" № 12–15 "), token=editor
        )
    assert (answer.status, answer.message.homework.text) == (200, "№ 12–15")
    assert seen == []
    assert notices.built == 0


async def test_deleting_an_assignment_takes_its_ticks_with_it_and_announces_it(
    v2, v2_tokens, session, school_class, notices, subscribers, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    first = await _homework(session, school_class)
    second = await _homework(session, school_class, "Физика <b>")
    session.add(HomeworkDone(homework_id=first.id, telegram_id=VIEWER))
    session.add(
        PersonalTask(
            class_id=school_class.id, telegram_id=VIEWER, title="Сделать", homework_id=first.id
        )
    )
    await session.commit()
    editor = v2_tokens["editor"]
    rest = await v2.rest(DELETE, DeleteHomeworkRequest(homework_id=first.id), token=editor)
    connect = await v2.connect(DELETE, DeleteHomeworkRequest(homework_id=second.id), token=editor)
    assert (rest.status, rest.body, connect.status) == (200, b"{}", 200)
    assert await _committed(select(func.count()).select_from(HomeworkRow)) == 0
    assert await _committed(select(func.count()).select_from(HomeworkDone)) == 0
    # A task made from it stays, without the link.
    assert await _committed(select(PersonalTask.homework_id)) is None
    when = "14 сентября (понедельник)"
    assert await _lines() == [
        ("homework.delete", f"ДЗ удалено: Алгебра, {when}"),
        ("homework.delete", f"ДЗ удалено: Физика <b>, {when}"),
    ]
    assert sorted(text for _, text in notices.sent) == [
        f"🗑 Задание удалено: <b>Алгебра</b> {when}",
        f"🗑 Задание удалено: <b>Алгебра</b> {when}",
        f"🗑 Задание удалено: <b>Физика &lt;b&gt;</b> {when}",
        f"🗑 Задание удалено: <b>Физика &lt;b&gt;</b> {when}",
    ]
    again = await v2.both(DELETE, DeleteHomeworkRequest(homework_id=first.id), token=editor)
    assert (again.status, again.reason, again.error) == (
        404,
        "RESOURCE_NOT_FOUND",
        wording.UNKNOWN_HOMEWORK_DETAIL,
    )
    assert notices.built == 2


async def test_homework_of_another_class_can_be_neither_changed_nor_deleted(
    v2, v2_tokens, session, notices, subscribers
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    foreign = HomeworkRow(class_id=other.id, due_date=MONDAY, subject_name="Алгебра", text="№ 1")
    session.add(foreign)
    await session.commit()
    for name, request in (
        (UPDATE, _update(foreign.id, text="Моё")),
        (DELETE, DeleteHomeworkRequest(homework_id=foreign.id)),
    ):
        answer = await v2.both(name, request, token=v2_tokens["editor"])
        assert (answer.status, answer.reason, answer.metadata) == (
            404,
            "RESOURCE_NOT_FOUND",
            {"resource": "homework"},
        ), name
        assert answer.error == wording.UNKNOWN_HOMEWORK_DETAIL
    assert (await _columns(foreign.id))[2] == "№ 1"
    assert notices.built == 0


async def test_a_mask_naming_a_field_the_method_does_not_change_is_refused_on_it(
    v2, v2_tokens, session, school_class
) -> None:
    row = await _homework(session, school_class)
    for path in ("id", "done"):
        answer = await v2.both(UPDATE, _update(row.id, path, text="x"), token=v2_tokens["editor"])
        assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED"), path
        assert answer.violations == [("update_mask", NOT_CHANGEABLE)]
    assert (await _columns(row.id))[2] == "№ 12–15"
