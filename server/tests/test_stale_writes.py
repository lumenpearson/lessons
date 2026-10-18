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
write commits. The class can be that row (#654): deleted, it takes its
devices and its terms with it, and the write that read them meets nothing.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from datetime import date, time, timedelta
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from protobuf.wkt import FieldMask
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app import wording
from app.bot.handlers.content.overrides import SUBSTITUTION_GONE, override_cancel, override_subject
from app.bot.handlers.manage.holidays import (
    DAY_GONE,
    PERIOD_GONE,
    holiday_bells,
    holiday_kind,
    holiday_note,
    holiday_period_apply,
)
from app.contract.lessons.v2.common_pb import Term as TermMessage
from app.contract.lessons.v2.common_pb import TermKind as TermKindMessage
from app.contract.lessons.v2.day_pb import Day, UpdateDayRequest
from app.contract.lessons.v2.diary_pb import BatchUpdateCorrectionsRequest, CorrectionUpdate
from app.contract.lessons.v2.event_pb import Event, UpdateEventRequest
from app.contract.lessons.v2.homework_pb import Homework as HomeworkMessage
from app.contract.lessons.v2.homework_pb import UpdateHomeworkRequest
from app.contract.lessons.v2.me_pb import UnlinkMeRequest
from app.contract.lessons.v2.school_class_pb import (
    TermScheme,
    UpdateTermRequest,
    UpdateTermSchemeRequest,
)
from app.contract.lessons.v2.substitution_pb import Substitution, UpdateSubstitutionRequest
from app.db import Base, SessionLocal
from app.models import (
    AuditEntry,
    DayEvent,
    DayKind,
    DayOverride,
    DiaryOverride,
    EventKind,
    Homework,
    LessonOverride,
    OverrideAction,
    Role,
    SchoolClass,
    Term,
)
from app.providers.petersburg import client as pbclient
from app.providers.petersburg import provider as pbprovider
from app.rpc.day import DAY_NOT_MARKED
from app.rpc.substitution import UNKNOWN_SUBSTITUTION
from app.services import audit, linking
from app.services import homework as homework_service
from app.services import terms as terms_service
from app.services.manage import special_days
from app.services.manage import terms as terms_manage

#: A Monday of the school year: the class's template has lessons 1 to 3 on a
#: Monday, and its bells ring 1 to 7.
MONDAY = date(2026, 9, 14)


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _deleted_meanwhile(
    monkeypatch, owner: Any, name: str, model: type[Base], row_id: int, *, call: int = 1
) -> list[bool]:
    """Delete ``model`` row ``row_id`` from a session of its own, and commit,
    just before ``owner.name`` runs for the ``call``-th time — a point after
    the write has read its row and before it commits. The list says it
    happened."""
    real: Callable[..., Awaitable[Any]] = getattr(owner, name)
    calls: list[bool] = []
    done: list[bool] = []

    async def first_somebody_deletes(*args: Any, **kwargs: Any) -> Any:
        calls.append(True)
        if not done and len(calls) == call:
            done.append(True)
            async with SessionLocal() as other:
                await other.execute(delete(model).where(model.id == row_id))
                await other.commit()
        return await real(*args, **kwargs)

    monkeypatch.setattr(owner, name, first_somebody_deletes)
    return done


def _deleted_at_flush(monkeypatch, model: type[Base], row_id: int) -> list[bool]:
    """Delete ``model`` row ``row_id`` as :func:`_deleted_meanwhile` does, just
    before the first flush of a session holding a changed ``model`` row: for a
    write that reads its row and flushes the change with no call of its own in
    between to stage the delete in front of. The list says it happened."""
    real = AsyncSession.flush
    done: list[bool] = []

    async def first_somebody_deletes(self: AsyncSession, *args: Any, **kwargs: Any) -> None:
        if not done and any(isinstance(row, model) for row in self.dirty):
            done.append(True)
            async with SessionLocal() as other:
                await other.execute(delete(model).where(model.id == row_id))
                await other.commit()
        await real(self, *args, **kwargs)

    monkeypatch.setattr(AsyncSession, "flush", first_somebody_deletes)
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


async def _terms(session, school_class) -> list[Term]:
    """This school year's quarters, stored."""
    year = terms_manage.current_year(school_class)
    rows = await terms_service.ensure(session, school_class, year)
    await session.commit()
    return rows


def _moved(term: Term) -> tuple[str, str]:
    """``term``'s dates with its start a day later: a change, so an ``UPDATE``."""
    return (term.starts_on + timedelta(days=1)).isoformat(), term.ends_on.isoformat()


#: A pupil Petersburg's diary lists, and the scope their corrections are under.
PUPIL = 4021
SCOPE = "CHILD:petersburg"


@pytest.fixture
def petersburg(monkeypatch, FakeUpstream):
    """Petersburg's upstream, listing the one pupil."""
    pupil = {
        "identity": {"id": PUPIL},
        "firstname": "Пётр",
        "surname": "Иванов",
        "educations": [{"education_id": 90210, "group_id": 771, "group_name": "9А"}],
    }
    fake = FakeUpstream({"/api/journal/person/related-child-list": {"items": [pupil]}})

    async def shared() -> httpx.AsyncClient:
        return httpx.AsyncClient(
            base_url=pbclient.BASE_URL, transport=httpx.MockTransport(fake.handler)
        )

    monkeypatch.setattr(pbclient, "shared_client", shared)
    monkeypatch.setattr(pbprovider, "today", lambda: date(2026, 9, 14))
    return fake


async def _correction(session) -> DiaryOverride:
    row = DiaryOverride(login=SCOPE, student_id=PUPIL, target="hw:id:77", field="text", value="§ 1")
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


async def test_v1_a_term_replaced_meanwhile_is_a_term_the_year_does_not_have(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    """A scheme change reseeds the year, deleting the term this write has read."""
    first = (await _terms(session, school_class))[0]
    starts, ends = _moved(first)
    raced = _deleted_at_flush(monkeypatch, Term, first.id)

    answer = await v2.http.put(
        "/api/v1/manage/terms/1",
        json={"starts_on": starts, "ends_on": ends},
        headers=_auth(v2_tokens["admin"]),
    )

    assert raced, "the delete never happened, so nothing was tested"
    # The route's own answer for a term the year does not have.
    assert (answer.status_code, answer.json()) == (422, {"detail": terms_service.NO_SUCH_TERM})
    assert (await _count(Term), await _count(AuditEntry)) == (3, 0)


async def test_v1_a_scheme_change_in_a_class_deleted_meanwhile_is_class_gone(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    await _terms(session, school_class)
    raced = _deleted_meanwhile(
        monkeypatch, terms_service, "set_scheme", SchoolClass, school_class.id
    )

    answer = await v2.http.put(
        "/api/v1/manage/terms/scheme", json={"kind": "semester"}, headers=_auth(v2_tokens["admin"])
    )

    assert raced, "the delete never happened, so nothing was tested"
    assert (answer.status_code, answer.json()) == (404, {"detail": wording.CLASS_GONE_DETAIL})
    assert (await _count(SchoolClass), await _count(Term)) == (0, 0)


async def test_v1_a_correction_taken_off_meanwhile_is_not_found_and_not_made_again(
    v2, v2_tokens, session, petersburg, monkeypatch
) -> None:
    row = await _correction(session)
    raced = _deleted_at_flush(monkeypatch, DiaryOverride, row.id)

    answer = await v2.http.put(
        f"/api/v1/diary/students/{PUPIL}/overrides",
        json={"target": "hw:id:77", "field": "text", "value": "§ 2"},
        headers=_auth(v2_tokens["diary"]),
    )

    assert raced, "the delete never happened, so nothing was tested"
    # Not a 404: on the diary's routes the app reads every 404 as a child not
    # on the account, whatever the detail says.
    assert (answer.status_code, answer.json()) == (
        409,
        {"detail": wording.CORRECTION_GONE_DETAIL},
    )
    assert await _count(DiaryOverride) == 0


async def test_v1_unlinking_a_phone_whose_class_was_deleted_meanwhile_is_class_gone(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    raced = _deleted_meanwhile(monkeypatch, linking, "unlink_self", SchoolClass, school_class.id)

    answer = await v2.http.post("/api/v1/me/unlink", headers=_auth(v2_tokens["editor"]))

    assert raced, "the delete never happened, so nothing was tested"
    assert (answer.status_code, answer.json()) == (404, {"detail": wording.CLASS_GONE_DETAIL})
    assert await _count(SchoolClass) == 0


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
        wording.DAY_NOT_MARKED_DETAIL,
    )
    # Not UpdateDay's refusal of an unmarked date, which tells the caller to
    # set the allow_missing this one already set.
    assert DAY_NOT_MARKED != wording.DAY_NOT_MARKED_DETAIL
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


async def test_v2_a_term_replaced_meanwhile_is_a_term_the_year_does_not_have(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    first = (await _terms(session, school_class))[0]
    starts, ends = _moved(first)
    raced = _deleted_at_flush(monkeypatch, Term, first.id)

    answer = await v2.rest(
        "ClassService/UpdateTerm",
        UpdateTermRequest(term=TermMessage(index=1, starts_on=starts, ends_on=ends)),
        token=v2_tokens["admin"],
    )

    assert raced, "the delete never happened, so nothing was tested"
    # UpdateTerm's own refusal of a term the year does not have, in the words
    # errors.proto gives it; a term has no RESOURCE_NOT_FOUND.
    assert (answer.status, answer.code, answer.reason, answer.metadata, answer.error) == (
        400,
        "FAILED_PRECONDITION",
        "TERM_BOUNDS_REFUSED",
        {},
        terms_service.NO_SUCH_TERM,
    )
    assert (await _count(Term), await _count(AuditEntry)) == (3, 0)


async def test_v2_a_scheme_change_in_a_class_deleted_meanwhile_is_class_gone(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    await _terms(session, school_class)
    raced = _deleted_meanwhile(
        monkeypatch, terms_service, "set_scheme", SchoolClass, school_class.id
    )

    answer = await v2.connect(
        "ClassService/UpdateTermScheme",
        UpdateTermSchemeRequest(term_scheme=TermScheme(kind=TermKindMessage.SEMESTER)),
        token=v2_tokens["admin"],
    )

    assert raced, "the delete never happened, so nothing was tested"
    assert (answer.code, answer.reason, answer.metadata, answer.error) == (
        "NOT_FOUND",
        "RESOURCE_NOT_FOUND",
        {"resource": "class"},
        wording.CLASS_GONE_DETAIL,
    )
    assert (await _count(SchoolClass), await _count(Term)) == (0, 0)


async def test_v2_a_correction_taken_off_meanwhile_is_not_found_and_not_made_again(
    v2, v2_tokens, session, petersburg, monkeypatch
) -> None:
    row = await _correction(session)
    raced = _deleted_at_flush(monkeypatch, DiaryOverride, row.id)

    answer = await v2.connect(
        "DiaryService/BatchUpdateCorrections",
        BatchUpdateCorrectionsRequest(
            student_id=PUPIL,
            corrections=[CorrectionUpdate(target="hw:id:77", field="text", value="§ 2")],
        ),
        token=v2_tokens["diary"],
    )

    assert raced, "the delete never happened, so nothing was tested"
    assert (answer.code, answer.reason, answer.metadata, answer.error) == (
        "NOT_FOUND",
        "RESOURCE_NOT_FOUND",
        {"resource": "correction"},
        wording.CORRECTION_GONE_DETAIL,
    )
    assert await _count(DiaryOverride) == 0


async def test_v2_unlinking_a_phone_whose_class_was_deleted_meanwhile_is_class_gone(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    raced = _deleted_meanwhile(monkeypatch, linking, "unlink_self", SchoolClass, school_class.id)

    answer = await v2.connect("MeService/UnlinkMe", UnlinkMeRequest(), token=v2_tokens["editor"])

    assert raced, "the delete never happened, so nothing was tested"
    assert (answer.code, answer.reason, answer.metadata, answer.error) == (
        "NOT_FOUND",
        "RESOURCE_NOT_FOUND",
        {"resource": "class"},
        wording.CLASS_GONE_DETAIL,
    )
    assert await _count(SchoolClass) == 0


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


async def test_the_bots_period_over_a_mark_deleted_meanwhile_says_so_and_writes_nothing(
    session, school_class, monkeypatch, FakeMessage, FakeState
) -> None:
    """«📆 Период» marks each day through ``put_day``, whose read of the next
    day flushes the change to the one before: the race surfaces inside the
    loop, by autoflush, before the commit is ever reached."""
    monday = await _mark(session, school_class)
    session.add(DayOverride(class_id=school_class.id, date=date(2026, 9, 15), kind=DayKind.HOLIDAY))
    await session.commit()
    # Monday's mark is read and changed by the first call; it goes before the
    # second, whose read of Tuesday flushes Monday's change.
    raced = _deleted_meanwhile(monkeypatch, special_days, "put_day", DayOverride, monday.id, call=2)
    state = FakeState(data={"period_kind": DayKind.REMOTE.value})
    message = FakeMessage(text="14.09.2026-16.09.2026")

    await holiday_period_apply(message, state, session, school_class, Role.ADMIN)

    assert raced, "the delete never happened, so nothing was tested"
    assert message.last == PERIOD_GONE
    assert (state.cleared, state.data) == (True, {})
    async with SessionLocal() as fresh:
        kinds = list(await fresh.scalars(select(DayOverride.kind).order_by(DayOverride.date)))
    assert (kinds, await _count(AuditEntry)) == ([DayKind.HOLIDAY], 0)
    await session.commit()


# --------------------------------------------------------------------------
# The two tables
# --------------------------------------------------------------------------


def test_every_resource_v2_answers_a_deleted_row_for_is_named_by_a_method() -> None:
    """A key no method's name gives would be a row that never answers."""
    from app.contract.lessons.v2.errors_pb import ErrorReason
    from app.rpc.call import GONE, TERM_GONE, gone
    from app.rpc.methods import METHODS

    answered = {key: gone(method) for key, method in METHODS.items()}
    named = {
        refusal.metadata["resource"]
        for refusal in answered.values()
        if refusal is not None and refusal.reason is ErrorReason.RESOURCE_NOT_FOUND
    }
    assert named == {resource for resource, _ in GONE.values()}
    for noun in [*GONE, *TERM_GONE]:
        assert any(method.name.endswith(noun) for method in METHODS.values()), noun
    term = answered["lessons.v2.ClassService/UpdateTerm"]
    assert term is not None
    assert (term.reason, term.message) == (
        ErrorReason.TERM_BOUNDS_REFUSED,
        terms_service.NO_SUCH_TERM,
    )
    # The import meets a deleted class as a foreign key, never as this error,
    # and stays out of the table (#654).
    assert answered["lessons.v2.TimetableService/ImportTimetable"] is None


def test_every_resource_v1_answers_a_deleted_row_for_is_a_path_it_serves() -> None:
    import re

    from app.api import diary, edit, manage, public
    from app.api.routing import GONE, GONE_STATUS, gone_answer, gone_key

    routes = [*edit.router.routes, *public.router.routes, *diary.router.routes]
    paths = [route.path for route in routes]
    for module in (
        manage.bells,
        manage.classes,
        manage.devices,
        manage.requests,
        manage.subjects,
        manage.terms,
    ):
        paths += [manage.router.prefix + route.path for route in module.router.routes]
    # As a request names them: a number where the template has a parameter.
    paths = [re.sub(r"\{[^}]+\}", "1", path) for path in paths]
    for resource in GONE:
        assert any(gone_key(path) == resource for path in paths), resource
    assert set(GONE_STATUS) <= set(GONE)
    assert gone_answer("/api/v1/manage/bells/3/periods") == (
        404,
        wording.UNKNOWN_BELL_SCHEDULE_DETAIL,
    )
    # The longest key the path begins with: a term's edges, and the scheme.
    assert gone_answer("/api/v1/manage/terms/2") == (422, terms_service.NO_SUCH_TERM)
    assert gone_answer("/api/v1/manage/terms/scheme") == (404, wording.CLASS_GONE_DETAIL)
    assert gone_answer("/api/v1/diary/students/4021/overrides") == (
        409,
        wording.CORRECTION_GONE_DETAIL,
    )
    assert gone_answer("/api/v1/diary/students") is None
    assert gone_answer("/api/v1/diary/students/4021/marks") is None
    assert gone_answer("/api/v1/manage/timetable/import") is None
    assert gone_answer("/diary/signin") is None
