"""A row another writer deletes between a write's read and its commit (#384).

A write that changes a row reads it, changes it in memory and commits. When
somebody else deletes the row in between, the flush's ``UPDATE`` matches
nothing and SQLAlchemy raises ``StaleDataError``, which nothing caught: v1
answered ``500``, v2 ``INTERNAL``, and the bot dropped the press with the
conversation still standing. The row is gone, which is «not found», never a
server error, and never a reason to make it again: an upsert that meets it
answers «not found» too, rather than re-creating what somebody has just
taken away.

Each test stages the race the way the issue's review did: a session of its
own deletes the row and commits after the write has read it and before the
write commits.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from datetime import date, time
from types import SimpleNamespace
from typing import Any

from protobuf.wkt import FieldMask
from sqlalchemy import delete, func, select

from app import wording
from app.bot.handlers.content.overrides import SUBSTITUTION_GONE, override_cancel, override_subject
from app.bot.handlers.manage.holidays import DAY_GONE, holiday_bells, holiday_kind, holiday_note
from app.contract.lessons.v2.day_pb import Day, UpdateDayRequest
from app.contract.lessons.v2.event_pb import Event, UpdateEventRequest
from app.contract.lessons.v2.homework_pb import Homework as HomeworkMessage
from app.contract.lessons.v2.homework_pb import UpdateHomeworkRequest
from app.contract.lessons.v2.substitution_pb import Substitution, UpdateSubstitutionRequest
from app.db import Base, SessionLocal
from app.models import (
    AuditEntry,
    DayEvent,
    DayKind,
    DayOverride,
    EventKind,
    Homework,
    LessonOverride,
    OverrideAction,
    Role,
)
from app.rpc.day import DAY_NOT_MARKED
from app.rpc.substitution import UNKNOWN_SUBSTITUTION
from app.services import audit
from app.services import homework as homework_service

#: A Monday of the school year: the class's template has lessons 1 to 3 on a
#: Monday, and its bells ring 1 to 7.
MONDAY = date(2026, 9, 14)


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _deleted_meanwhile(
    monkeypatch, owner: Any, name: str, model: type[Base], row_id: int
) -> list[bool]:
    """Delete ``model`` row ``row_id`` from a session of its own, and commit,
    just before ``owner.name`` runs for the first time — a point after the
    write has read its row and before it commits. The list says it happened."""
    real: Callable[..., Awaitable[Any]] = getattr(owner, name)
    done: list[bool] = []

    async def first_somebody_deletes(*args: Any, **kwargs: Any) -> Any:
        if not done:
            done.append(True)
            async with SessionLocal() as other:
                await other.execute(delete(model).where(model.id == row_id))
                await other.commit()
        return await real(*args, **kwargs)

    monkeypatch.setattr(owner, name, first_somebody_deletes)
    return done


async def _count(model: type[Base]) -> int:
    async with SessionLocal() as fresh:
        return await fresh.scalar(select(func.count()).select_from(model)) or 0


async def _substitution(session, school_class, index: int = 2) -> LessonOverride:
    row = LessonOverride(
        class_id=school_class.id,
        date=MONDAY,
        index=index,
        action=OverrideAction.REPLACE,
        subject_name="Химия",
    )
    session.add(row)
    await session.commit()
    return row


async def _mark(session, school_class, kind: DayKind = DayKind.HOLIDAY) -> DayOverride:
    row = DayOverride(class_id=school_class.id, date=MONDAY, kind=kind, note="каникулы")
    session.add(row)
    await session.commit()
    return row


# --------------------------------------------------------------------------
# v1
# --------------------------------------------------------------------------


async def test_v1_a_substitution_deleted_meanwhile_is_not_found_and_not_made_again(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    row = await _substitution(session, school_class)
    raced = _deleted_meanwhile(monkeypatch, audit, "record", LessonOverride, row.id)

    answer = await v2.http.put(
        "/api/v1/overrides",
        json={"date": MONDAY.isoformat(), "index": 2, "action": "replace", "room": "214"},
        headers=_auth(v2_tokens["editor"]),
    )

    assert raced, "the delete never happened, so nothing was tested"
    assert (answer.status_code, answer.json()) == (
        404,
        {"detail": wording.UNKNOWN_SUBSTITUTION_DETAIL},
    )
    assert (await _count(LessonOverride), await _count(AuditEntry)) == (0, 0)


async def test_v1_a_day_mark_deleted_meanwhile_is_not_found_and_not_made_again(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    row = await _mark(session, school_class)
    raced = _deleted_meanwhile(monkeypatch, audit, "record", DayOverride, row.id)

    answer = await v2.http.put(
        "/api/v1/days",
        json={"date": MONDAY.isoformat(), "kind": "holiday", "note": "осенние каникулы"},
        headers=_auth(v2_tokens["editor"]),
    )

    assert raced, "the delete never happened, so nothing was tested"
    assert (answer.status_code, answer.json()) == (404, {"detail": wording.DAY_NOT_MARKED_DETAIL})
    assert (await _count(DayOverride), await _count(AuditEntry)) == (0, 0)


# --------------------------------------------------------------------------
# v2
# --------------------------------------------------------------------------


async def test_v2_a_substitution_deleted_meanwhile_is_not_found(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    row = await _substitution(session, school_class)
    raced = _deleted_meanwhile(monkeypatch, audit, "record", LessonOverride, row.id)

    answer = await v2.connect(
        "SubstitutionService/UpdateSubstitution",
        UpdateSubstitutionRequest(
            substitution=Substitution(id=row.id, room="214"),
            update_mask=FieldMask(paths=["room"]),
        ),
        token=v2_tokens["editor"],
    )

    assert raced, "the delete never happened, so nothing was tested"
    assert (answer.code, answer.reason, answer.metadata, answer.error) == (
        "NOT_FOUND",
        "RESOURCE_NOT_FOUND",
        {"resource": "substitution"},
        UNKNOWN_SUBSTITUTION,
    )
    assert (await _count(LessonOverride), await _count(AuditEntry)) == (0, 0)


async def test_v2_a_day_mark_deleted_meanwhile_is_not_found_over_rest(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    await _mark(session, school_class)
    mark = await session.scalar(select(DayOverride))
    raced = _deleted_meanwhile(monkeypatch, audit, "record", DayOverride, mark.id)

    answer = await v2.rest(
        "DayService/UpdateDay",
        UpdateDayRequest(
            day=Day(date=MONDAY.isoformat(), note="осенние каникулы"),
            update_mask=FieldMask(paths=["note"]),
            allow_missing=True,
        ),
        token=v2_tokens["editor"],
    )

    assert raced, "the delete never happened, so nothing was tested"
    assert (answer.status, answer.code, answer.reason, answer.metadata, answer.error) == (
        404,
        "NOT_FOUND",
        "RESOURCE_NOT_FOUND",
        {"resource": "day"},
        DAY_NOT_MARKED,
    )
    assert (await _count(DayOverride), await _count(AuditEntry)) == (0, 0)


async def test_v2_homework_deleted_meanwhile_is_not_found(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    row = Homework(class_id=school_class.id, due_date=MONDAY, subject_name="Алгебра", text="№ 1")
    session.add(row)
    await session.commit()
    # Its own update flushes inside a savepoint before its line, so the race
    # is staged just before the service is called, with the row already read.
    raced = _deleted_meanwhile(monkeypatch, homework_service, "update", Homework, row.id)

    answer = await v2.connect(
        "HomeworkService/UpdateHomework",
        UpdateHomeworkRequest(
            homework=HomeworkMessage(id=row.id, text="№ 2"),
            update_mask=FieldMask(paths=["text"]),
        ),
        token=v2_tokens["editor"],
    )

    assert raced, "the delete never happened, so nothing was tested"
    assert (answer.code, answer.reason, answer.metadata, answer.error) == (
        "NOT_FOUND",
        "RESOURCE_NOT_FOUND",
        {"resource": "homework"},
        wording.UNKNOWN_HOMEWORK_DETAIL,
    )
    assert (await _count(Homework), await _count(AuditEntry)) == (0, 0)


async def test_v2_an_event_deleted_meanwhile_is_not_found(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    row = DayEvent(
        class_id=school_class.id,
        date=MONDAY,
        starts_at=time(12, 30),
        ends_at=time(13, 0),
        title="Экскурсия",
        kind=EventKind.TRIP,
    )
    session.add(row)
    await session.commit()
    raced = _deleted_meanwhile(monkeypatch, audit, "record", DayEvent, row.id)

    answer = await v2.connect(
        "EventService/UpdateEvent",
        UpdateEventRequest(
            event=Event(id=row.id, title="Музей"), update_mask=FieldMask(paths=["title"])
        ),
        token=v2_tokens["editor"],
    )

    assert raced, "the delete never happened, so nothing was tested"
    assert (answer.code, answer.reason, answer.metadata, answer.error) == (
        "NOT_FOUND",
        "RESOURCE_NOT_FOUND",
        {"resource": "event"},
        wording.UNKNOWN_EVENT_DETAIL,
    )
    assert (await _count(DayEvent), await _count(AuditEntry)) == (0, 0)


# --------------------------------------------------------------------------
# The bot
# --------------------------------------------------------------------------


async def test_the_bot_says_a_substitution_deleted_meanwhile_is_gone_and_clears_the_state(
    session, school_class, monkeypatch, FakeMessage, FakeState
) -> None:
    row = await _substitution(session, school_class)
    raced = _deleted_meanwhile(monkeypatch, audit, "record", LessonOverride, row.id)
    state = FakeState(data={"date": MONDAY.isoformat(), "index": "2"})
    message = FakeMessage(text="Физика, 214")

    await override_subject(message, state, session, school_class, Role.EDITOR)

    assert raced, "the delete never happened, so nothing was tested"
    assert message.last == SUBSTITUTION_GONE
    assert (state.cleared, state.data) == (True, {})
    assert (await _count(LessonOverride), await _count(AuditEntry)) == (0, 0)
    # The session is usable again: the middleware commits it after the handler.
    await session.commit()


async def test_the_bots_cancellation_of_a_substitution_deleted_meanwhile_says_so_too(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, FakeState
) -> None:
    row = await _substitution(session, school_class)
    raced = _deleted_meanwhile(monkeypatch, audit, "record", LessonOverride, row.id)
    state = FakeState(data={"date": MONDAY.isoformat(), "index": "2"})
    callback = FakeCallback(message=FakeEditable())

    await override_cancel(callback, state, session, school_class, Role.EDITOR)

    assert raced, "the delete never happened, so nothing was tested"
    assert callback.answers[-1] == (SUBSTITUTION_GONE, True)
    assert (state.cleared, state.data) == (True, {})
    assert (await _count(LessonOverride), await _count(AuditEntry)) == (0, 0)
    await session.commit()


async def test_the_bot_says_a_day_mark_deleted_meanwhile_is_gone_and_clears_the_state(
    session, school_class, monkeypatch, FakeMessage, FakeState
) -> None:
    row = await _mark(session, school_class)
    raced = _deleted_meanwhile(monkeypatch, audit, "record", DayOverride, row.id)
    state = FakeState(data={"date": MONDAY.isoformat()})
    message = FakeMessage(text="осенние каникулы")

    await holiday_note(message, state, session, school_class, Role.EDITOR)

    assert raced, "the delete never happened, so nothing was tested"
    assert message.last == f"{DAY_GONE}."
    assert (state.cleared, state.data) == (True, {})
    assert (await _count(DayOverride), await _count(AuditEntry)) == (0, 0)
    await session.commit()


async def test_the_bots_kind_for_a_day_mark_deleted_meanwhile_says_so_too(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, FakeState
) -> None:
    row = await _mark(session, school_class)
    raced = _deleted_meanwhile(monkeypatch, audit, "record", DayOverride, row.id)
    state = FakeState(data={"date": MONDAY.isoformat()})
    callback = FakeCallback(message=FakeEditable())

    await holiday_kind(
        callback,
        SimpleNamespace(action="kind", value=f"{MONDAY.isoformat()}:remote"),
        state,
        session,
        school_class,
        Role.EDITOR,
    )

    assert raced, "the delete never happened, so nothing was tested"
    assert callback.answers[-1] == (DAY_GONE, True)
    assert (state.cleared, state.data) == (True, {})
    assert (await _count(DayOverride), await _count(AuditEntry)) == (0, 0)
    await session.commit()


async def test_the_bots_bells_for_a_day_mark_deleted_meanwhile_says_so_too(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, FakeState
) -> None:
    row = await _mark(session, school_class, DayKind.SHORTENED)
    raced = _deleted_meanwhile(monkeypatch, audit, "record", DayOverride, row.id)
    state = FakeState(data={"date": MONDAY.isoformat()})
    callback = FakeCallback(message=FakeEditable())

    await holiday_bells(
        callback,
        SimpleNamespace(
            action="bells", value=f"{MONDAY.isoformat()}:{school_class.bell_schedule_id}"
        ),
        state,
        session,
        school_class,
        Role.EDITOR,
    )

    assert raced, "the delete never happened, so nothing was tested"
    assert callback.answers[-1] == (DAY_GONE, True)
    assert (state.cleared, state.data) == (True, {})
    assert (await _count(DayOverride), await _count(AuditEntry)) == (0, 0)
    await session.commit()


# --------------------------------------------------------------------------
# The two tables
# --------------------------------------------------------------------------


def test_every_resource_v2_answers_a_deleted_row_for_is_named_by_a_method() -> None:
    """A key no method's name gives would be a row that never answers."""
    from app.rpc.call import GONE, gone
    from app.rpc.methods import METHODS

    answered = {key: gone(method) for key, method in METHODS.items()}
    named = {refusal.metadata["resource"] for refusal in answered.values() if refusal is not None}
    assert named == {resource for resource, _ in GONE.values()}
    for noun in GONE:
        assert any(method.name.endswith(noun) for method in METHODS.values()), noun
    # A method whose resource has no «not found» of its own stays INTERNAL.
    assert answered["lessons.v2.MeService/UnlinkMe"] is None
    assert answered["lessons.v2.ClassService/UpdateTerm"] is None


def test_every_resource_v1_answers_a_deleted_row_for_is_a_path_it_serves() -> None:
    from app.api import edit, manage, public
    from app.api.routing import GONE, gone_detail

    paths = [route.path for route in [*edit.router.routes, *public.router.routes]]
    for module in (manage.bells, manage.classes, manage.devices, manage.requests, manage.subjects):
        paths += [manage.router.prefix + route.path for route in module.router.routes]
    for resource, detail in GONE.items():
        served = [path for path in paths if gone_detail(path) == detail]
        assert served, resource
    assert gone_detail("/api/v1/manage/bells/3/periods") == wording.UNKNOWN_BELL_SCHEDULE_DETAIL
    assert gone_detail("/api/v1/diary/students") is None
