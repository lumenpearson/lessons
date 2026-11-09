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
    if model in (DayOverride, Homework):
        from app.services import gone

        real_lock = gone.lock_parent

        async def before_sqlite_retains_the_file(session, target, target_id, **kwargs):
            if (
                session.get_bind().dialect.name == "sqlite"
                and target is SchoolClass and not done
            ):
                # SQLite's parent check serializes every writer in the file;
                # lose the already-read child before that check, not after it.
                done.append(True)
                async with SessionLocal() as other:
                    await other.execute(delete(model).where(model.id == row_id))
                    await other.commit()
            await real_lock(session, target, target_id, **kwargs)

        monkeypatch.setattr(gone, "lock_parent", before_sqlite_retains_the_file)
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


@pytest.mark.parametrize("field", ["short_name", "teacher", "colour_typed", "colour_pick"])
async def test_subject_detail_deleted_mid_write_does_not_leave_a_form_or_audit(
    session, school_class, monkeypatch, FakeMessage, FakeCallback, FakeEditable, FakeState, field
) -> None:
    from app.bot.handlers.manage import subjects
    from app.models import Subject

    row = Subject(class_id=school_class.id, name="Физика")
    session.add(row)
    await session.commit()
    row_id = row.id
    raced = _deleted_meanwhile(monkeypatch, audit, "record", Subject, row_id)
    state = FakeState(data={"subject_id": row_id})
    if field == "colour_pick":
        callback = FakeCallback(message=FakeEditable())
        await subjects.subject_colour_pick(
            callback, SimpleNamespace(value=f"{row_id}:#112233"), state,
            session, school_class, Role.ADMIN,
        )
        assert callback.answers[-1] == ("Предмет уже удалён", True)
    else:
        handler = getattr(subjects, f"subject_{field}")
        message = FakeMessage(text="#112233" if field == "colour_typed" else "Новое")
        await handler(message, state, session, school_class, Role.ADMIN)
        assert message.last == "Предмет уже удалён."
    assert raced
    assert (state.cleared, state.data) == (True, {})
    assert (await _count(Subject), await _count(AuditEntry)) == (0, 0)
    await session.commit()


async def test_homework_deleted_mid_write_clears_the_form_without_announcing_success(
    session, school_class, monkeypatch, FakeMessage, FakeState
) -> None:
    from app.bot.handlers.content.homework import homework_text

    row = Homework(class_id=school_class.id, due_date=MONDAY, subject_name="Физика", text="Старое")
    session.add(row)
    await session.commit()
    raced = _deleted_meanwhile(monkeypatch, audit, "record", Homework, row.id)
    state = FakeState(data={"due": MONDAY.isoformat(), "subject": "Физика"})
    message = FakeMessage(text="Новое задание")
    await homework_text(message, state, session, school_class, Role.EDITOR)
    assert raced
    assert message.last == "Задание уже удалено. Начните заново."
    assert (state.cleared, state.data) == (True, {})
    assert (await _count(Homework), await _count(AuditEntry)) == (0, 0)
    await session.commit()


async def test_subject_rename_deleted_before_cascade_is_gone_and_rolls_back(
    session, school_class, monkeypatch, FakeMessage, FakeState
) -> None:
    from app.bot.handlers.manage.subjects import subject_rename
    from app.models import Subject
    from app.services import structure

    row = Subject(class_id=school_class.id, name="Физика")
    session.add(row)
    await session.commit()
    raced = _deleted_meanwhile(monkeypatch, structure, "rename_subject", Subject, row.id)
    state = FakeState(data={"subject_id": row.id})
    message = FakeMessage(text="Астрономия")
    await subject_rename(message, state, session, school_class, Role.ADMIN)
    assert raced
    assert message.last == "Предмет уже удалён."
    assert (state.cleared, state.data) == (True, {})
    assert (await _count(Subject), await _count(AuditEntry)) == (0, 0)
    await session.commit()


@pytest.mark.parametrize("action", ["toggle", "reminder"])
async def test_task_deleted_mid_write_answers_the_press_without_editing_the_list(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, action
) -> None:
    from datetime import datetime

    from app.bot.handlers import tasks
    from app.models import PersonalTask

    row = PersonalTask(
        class_id=school_class.id, telegram_id=42, title="Тетрадь",
        due_date=MONDAY, due_time=time(15),
    )
    session.add(row)
    await session.commit()
    raced = _deleted_meanwhile(monkeypatch, session, "commit", PersonalTask, row.id)
    callback = FakeCallback(message=FakeEditable())
    if action == "toggle":
        await tasks.task_toggle_done(
            callback, SimpleNamespace(value=str(row.id), show_done=False),
            session, school_class, Role.VIEWER,
        )
    else:
        monkeypatch.setattr(tasks, "_now", lambda _: datetime(2026, 9, 14, 10))
        await tasks.task_set_reminder(
            callback, SimpleNamespace(value=f"{row.id}_hour"),
            session, school_class, Role.VIEWER,
        )
    assert raced
    assert callback.answers[-1] == ("Задача уже удалена", True)
    assert not callback.message.replies
    assert await _count(PersonalTask) == 0
    await session.commit()


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


async def test_term_replaced_by_scheme_mid_write_clears_the_bot_form(
    session, school_class, monkeypatch, FakeMessage, FakeState
) -> None:
    from app.bot.handlers.manage.terms import term_edit_apply

    row = (await _terms(session, school_class))[0]
    state = FakeState(data={"term_index": row.index})
    message = FakeMessage(
        text=f"{row.starts_on:%d.%m.%Y} - {row.ends_on - timedelta(days=1):%d.%m.%Y}"
    )
    raced = _deleted_at_flush(monkeypatch, Term, row.id)
    await term_edit_apply(message, state, session, school_class, Role.ADMIN)
    assert raced
    assert message.last == terms_service.NO_SUCH_TERM
    assert (state.cleared, state.data) == (True, {})
    assert await _count(AuditEntry) == 0

    await session.commit()


async def test_lesson_deleted_after_editor_read_drops_the_prompt_and_audit(
    session, school_class, monkeypatch, FakeMessage, FakeState
) -> None:
    from app.bot.handlers.editor import editor_take_lesson
    from app.models import Subject, TimetableEntry

    subject = Subject(class_id=school_class.id, name="Физика")
    session.add(subject)
    await session.commit()
    row = await session.scalar(select(TimetableEntry).where(TimetableEntry.index == 2))
    raced = _deleted_meanwhile(monkeypatch, audit, "record", TimetableEntry, row.id)
    state = FakeState(data={"day": 1, "index": 2, "flags": 0, "mode": "edit"})
    message = FakeMessage(text="Физика, 404")
    await editor_take_lesson(message, state, session, school_class, Role.ADMIN)
    assert raced
    assert message.last == "Урока уже нет — откройте расписание заново."
    assert (state.cleared, state.data) == (True, {})
    assert await _count(AuditEntry) == 0
    await session.commit()


@pytest.mark.parametrize("deleted_index", [2, 3])
async def test_editor_move_whose_slot_was_deleted_does_not_move_its_neighbour(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, deleted_index
) -> None:
    from app.bot.handlers.editor import editor_move
    from app.models import TimetableEntry
    from app.services import timetable_edit

    real = timetable_edit._indexes
    raced: list[bool] = []

    async def indexes_then_delete(*args):
        result = await real(*args)
        async with SessionLocal() as other:
            await other.execute(delete(TimetableEntry).where(TimetableEntry.index == deleted_index))
            await other.commit()
        raced.append(True)
        return result

    monkeypatch.setattr(timetable_edit, "_indexes", indexes_then_delete)
    callback = FakeCallback(message=FakeEditable())
    await editor_move(
        callback, SimpleNamespace(arg=1, day=1, index=2, flags=0),
        session, school_class, Role.ADMIN,
    )
    assert raced
    assert callback.answers[-1] == ("Урока уже нет — откройте расписание заново.", True)
    assert await _count(AuditEntry) == 0
    remaining = list(
        await session.scalars(select(TimetableEntry.index).order_by(TimetableEntry.index))
    )
    assert remaining == [i for i in [1, 2, 3] if i != deleted_index]
    await session.commit()


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
    imported = answered["lessons.v2.TimetableService/ImportTimetable"]
    assert imported is not None
    assert imported.metadata == {"resource": "bell_schedule"}
    assert imported.message == wording.UNKNOWN_BELL_SCHEDULE_DETAIL


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
        manage.timetable,
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
    assert gone_answer("/api/v1/manage/timetable/import") == (
        404, wording.UNKNOWN_BELL_SCHEDULE_DETAIL,
    )
    assert gone_answer("/diary/signin") is None


@pytest.mark.parametrize(
    "field", ["school", "timezone", "join_mode", "same_school", "same_timezone"]
)
async def test_v1_class_deleted_before_its_first_write_has_no_audit_or_server_error(
    v2, v2_tokens, session, school_class, monkeypatch, field
) -> None:
    from app.services.manage import classes

    target = {"school": "set_field", "timezone": "set_timezone", "join_mode": "set_join_mode"}
    values = {"school": "Новая школа", "timezone": "Asia/Yekaterinburg", "join_mode": "invite"}
    if field.startswith("same_"):
        field = field.removeprefix("same_")
        setattr(school_class, field, values[field])
        await session.commit()
    raced = _deleted_meanwhile(monkeypatch, classes, target[field], SchoolClass, school_class.id)
    answer = await v2.http.patch(
        "/api/v1/manage/class", json={field: values[field]}, headers=_auth(v2_tokens["admin"])
    )
    assert raced
    assert (answer.status_code, answer.json()) == (404, {"detail": wording.CLASS_GONE_DETAIL})
    assert (await _count(SchoolClass), await _count(AuditEntry)) == (0, 0)


@pytest.mark.parametrize(
    "field", ["school", "timezone", "join_mode", "same_school", "same_timezone"]
)
async def test_v2_class_deleted_before_its_first_write_is_class_gone(
    v2, v2_tokens, session, school_class, monkeypatch, field
) -> None:
    from app.contract.lessons.v2.school_class_pb import JoinMode, UpdateClassRequest
    from app.contract.lessons.v2.school_class_pb import SchoolClass as Card
    from app.services.manage import classes

    target = {"school": "set_field", "timezone": "set_timezone", "join_mode": "set_join_mode"}
    values = {
        "school": "Новая школа",
        "timezone": "Asia/Yekaterinburg",
        "join_mode": JoinMode.INVITE,
    }
    if field.startswith("same_"):
        field = field.removeprefix("same_")
        setattr(school_class, field, values[field])
        await session.commit()
    raced = _deleted_meanwhile(monkeypatch, classes, target[field], SchoolClass, school_class.id)
    answer = await v2.connect(
        "ClassService/UpdateClass",
        UpdateClassRequest(
            school_class=Card(**{field: values[field]}), update_mask=FieldMask(paths=[field])
        ),
        token=v2_tokens["admin"],
    )
    assert raced
    assert (answer.code, answer.reason, answer.metadata, answer.error) == (
        "NOT_FOUND",
        "RESOURCE_NOT_FOUND",
        {"resource": "class"},
        wording.CLASS_GONE_DETAIL,
    )
    assert (await _count(SchoolClass), await _count(AuditEntry)) == (0, 0)


@pytest.mark.parametrize("unchanged", [False, True])
async def test_bot_class_deleted_under_a_typed_field_clears_the_form(
    session, school_class, monkeypatch, FakeMessage, FakeState, unchanged
) -> None:
    from app.bot.handlers.manage.class_card import class_field_apply
    from app.services.manage import classes

    if unchanged:
        school_class.school = "Новая школа"
        await session.commit()
    raced = _deleted_meanwhile(monkeypatch, classes, "set_field", SchoolClass, school_class.id)
    state = FakeState(data={"field": "school"})
    message = FakeMessage(text="Новая школа")
    await class_field_apply(message, state, session, school_class, Role.ADMIN)
    assert raced
    assert message.last == "Класс уже удалён. Откройте /start."
    assert (state.cleared, state.data) == (True, {})
    assert (await _count(SchoolClass), await _count(AuditEntry)) == (0, 0)
    await session.commit()


@pytest.mark.parametrize("field", ["timezone", "join_mode", "same_timezone"])
async def test_bot_class_deleted_under_a_setting_answers_the_press(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, field
) -> None:
    from app.bot.handlers.access import switch_join_mode
    from app.bot.handlers.start.timezone import change_timezone_apply
    from app.services.manage import classes

    if field == "same_timezone":
        field = "timezone"
        school_class.timezone = "Asia/Yekaterinburg"
        await session.commit()
    target = "set_timezone" if field == "timezone" else "set_join_mode"
    raced = _deleted_meanwhile(monkeypatch, classes, target, SchoolClass, school_class.id)
    callback = FakeCallback(message=FakeEditable())
    if field == "timezone":
        await change_timezone_apply(
            callback, SimpleNamespace(zone="Asia/Yekaterinburg"), session, school_class, Role.ADMIN
        )
    else:
        await switch_join_mode(
            callback, SimpleNamespace(value="invite"), session, school_class, Role.ADMIN
        )
    assert raced
    assert callback.answers[-1] == ("Класс уже удалён. Откройте /start.", True)
    assert not callback.message.replies
    assert (await _count(SchoolClass), await _count(AuditEntry)) == (0, 0)
    await session.commit()


@pytest.mark.parametrize("parent", ["class", "schedule"])
async def test_bot_bell_rows_losing_a_parent_drop_the_paste_and_audit(
    session, school_class, monkeypatch, FakeMessage, FakeState, parent
) -> None:
    from app.bot.handlers.manage.bells import bells_rows_apply
    from app.models import BellSchedule
    from app.services.manage import bells

    schedule_id = school_class.bell_schedule_id
    model, row_id = (
        (SchoolClass, school_class.id) if parent == "class" else (BellSchedule, schedule_id)
    )
    raced = _deleted_meanwhile(monkeypatch, bells, "replace_rows", model, row_id)
    state = FakeState(data={"schedule_id": schedule_id})
    message = FakeMessage(text="1. 09:00-09:40")
    await bells_rows_apply(message, state, session, school_class, Role.ADMIN)
    assert raced
    assert message.last == "Расписание уже удалено."
    assert (state.cleared, state.data) == (True, {})
    assert await _count(AuditEntry) == 0
    await session.commit()


@pytest.mark.parametrize("parent", ["class", "schedule"])
async def test_bot_default_bells_losing_a_parent_answer_without_a_success(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, parent
) -> None:
    from app.bot.handlers.manage.bells import bells_make_default
    from app.models import BellPeriod, BellSchedule
    from app.services.manage import bells

    schedule = BellSchedule(class_id=school_class.id, name="Сокращённое")
    session.add(schedule)
    await session.flush()
    session.add(
        BellPeriod(schedule_id=schedule.id, index=1, starts_at=time(9), ends_at=time(9, 40))
    )
    await session.commit()
    model, row_id = (
        (SchoolClass, school_class.id) if parent == "class" else (BellSchedule, schedule.id)
    )
    raced = _deleted_meanwhile(monkeypatch, bells, "make_default", model, row_id)
    callback = FakeCallback(message=FakeEditable())
    await bells_make_default(
        callback, SimpleNamespace(value=str(schedule.id)), session, school_class, Role.ADMIN
    )
    assert raced
    assert callback.answers[-1] == ("Расписание не найдено", True)
    assert not callback.message.replies
    assert await _count(AuditEntry) == 0
    await session.commit()


async def test_bot_event_in_a_deleted_class_drops_the_form_and_announces_nothing(
    session, school_class, monkeypatch, FakeMessage, FakeState
) -> None:
    from unittest.mock import AsyncMock

    from app.bot.handlers.content import events

    notified = AsyncMock()
    monkeypatch.setattr(events.notify, "notify_subscribers", notified)
    state = FakeState(
        data={"date": MONDAY.isoformat(), "kind": "trip", "start": "09:00", "end": "11:00"}
    )
    raced = _deleted_meanwhile(monkeypatch, state, "get_data", SchoolClass, school_class.id)
    message = FakeMessage(text="Поездка в планетарий")
    await events.event_title(message, state, session, school_class, Role.EDITOR)
    assert raced
    assert message.last == "Класс уже удалён. Откройте /start."
    assert (state.cleared, state.data) == (True, {})
    assert (await _count(DayEvent), await _count(AuditEntry)) == (0, 0)
    notified.assert_not_awaited()
    await session.commit()


@pytest.mark.parametrize("transport", ["v1", "v2"])
@pytest.mark.parametrize("action", ["rows", "default"])
@pytest.mark.parametrize("parent", ["class", "schedule"])
async def test_bell_api_losing_a_parent_refuses_without_a_partial_write(
    v2, v2_tokens, session, school_class, monkeypatch, transport, action, parent
) -> None:
    from app.contract.lessons.v2.bell_pb import BellPeriod as PeriodMessage
    from app.contract.lessons.v2.bell_pb import BellSchedule as ScheduleMessage
    from app.contract.lessons.v2.bell_pb import UpdateBellScheduleRequest
    from app.models import BellPeriod, BellSchedule
    from app.services.manage import bells

    old_default = school_class.bell_schedule_id
    schedule = BellSchedule(class_id=school_class.id, name="Сокращённое")
    session.add(schedule)
    await session.flush()
    session.add(
        BellPeriod(schedule_id=schedule.id, index=1, starts_at=time(9), ends_at=time(9, 40))
    )
    await session.commit()
    class_id, schedule_id = school_class.id, schedule.id
    model, row_id = (SchoolClass, class_id) if parent == "class" else (BellSchedule, schedule_id)
    target = "replace_rows" if action == "rows" else "make_default"
    raced = _deleted_meanwhile(monkeypatch, bells, target, model, row_id)
    if transport == "v1":
        path = f"/api/v1/manage/bells/{schedule_id}"
        payload = {"is_default": True}
        if action == "rows":
            path += "/periods"
            payload = {"periods": [{"index": 1, "starts_at": "10:00", "ends_at": "10:40"}]}
        answer = await v2.http.request(
            "PUT" if action == "rows" else "PATCH",
            path,
            json=payload,
            headers=_auth(v2_tokens["admin"]),
        )
        assert (answer.status_code, answer.json()) == (
            404,
            {"detail": wording.UNKNOWN_BELL_SCHEDULE_DETAIL},
        )
    else:
        sent = ScheduleMessage(id=schedule_id)
        field = "is_default" if action == "default" else "periods"
        if action == "default":
            sent.is_default = True
        else:
            sent.periods.append(PeriodMessage(index=1, starts_at="10:00", ends_at="10:40"))
        answer = await v2.connect(
            "BellService/UpdateBellSchedule",
            UpdateBellScheduleRequest(schedule=sent, update_mask=FieldMask(paths=[field])),
            token=v2_tokens["admin"],
        )
        assert (answer.code, answer.reason, answer.metadata, answer.error) == (
            "NOT_FOUND",
            "RESOURCE_NOT_FOUND",
            {"resource": "bell_schedule"},
            wording.UNKNOWN_BELL_SCHEDULE_DETAIL,
        )
    assert raced
    assert await _count(AuditEntry) == 0
    async with SessionLocal() as fresh:
        current_class = await fresh.get(SchoolClass, class_id)
        if parent == "class":
            assert current_class is None
        else:
            assert current_class.bell_schedule_id == old_default
        assert await fresh.get(BellSchedule, schedule_id) is None


async def test_bot_new_bells_in_a_deleted_class_drop_the_form(
    session, school_class, monkeypatch, FakeMessage, FakeState
) -> None:
    from app.bot.handlers.manage.bells import bells_new_rows
    from app.models import BellSchedule
    from app.services.manage import bells

    raced = _deleted_meanwhile(monkeypatch, bells, "create", SchoolClass, school_class.id)
    state = FakeState(data={"name": "Сокращённое"})
    message = FakeMessage(text="1. 09:00-09:40")
    await bells_new_rows(message, state, session, school_class, Role.ADMIN)
    assert raced
    assert message.last == "Класс уже удалён. Откройте /start."
    assert (state.cleared, state.data) == (True, {})
    assert (await _count(BellSchedule), await _count(AuditEntry)) == (0, 0)
    await session.commit()


@pytest.mark.parametrize("transport", ["v1", "v2"])
@pytest.mark.parametrize("resource", ["bells", "events"])
async def test_create_api_in_a_deleted_class_refuses_without_an_orphan(
    v2, v2_tokens, session, school_class, monkeypatch, transport, resource
) -> None:
    from app.contract.lessons.v2.bell_pb import BellSchedule as ScheduleMessage
    from app.contract.lessons.v2.bell_pb import CreateBellScheduleRequest
    from app.contract.lessons.v2.common_pb import EventKind as ProtoKind
    from app.contract.lessons.v2.event_pb import CreateEventRequest
    from app.models import BellSchedule
    from app.services import events
    from app.services.manage import bells

    service = bells if resource == "bells" else events
    raced = _deleted_meanwhile(monkeypatch, service, "create", SchoolClass, school_class.id)
    if resource == "bells":
        payload = {"name": "Сокращённое"}
        sent = CreateBellScheduleRequest(schedule=ScheduleMessage(name="Сокращённое"))
        method = "BellService/CreateBellSchedule"
        detail, metadata = wording.UNKNOWN_BELL_SCHEDULE_DETAIL, {"resource": "bell_schedule"}
    else:
        payload = {
            "date": MONDAY.isoformat(),
            "starts_at": "09:00",
            "ends_at": "11:00",
            "title": "Поездка",
            "kind": "trip",
        }
        sent = CreateEventRequest(
            event=Event(
                date=MONDAY.isoformat(),
                starts_at="09:00",
                ends_at="11:00",
                title="Поездка",
                kind=ProtoKind.TRIP,
            )
        )
        method = "EventService/CreateEvent"
        detail, metadata = wording.UNKNOWN_EVENT_DETAIL, {"resource": "event"}
    if transport == "v1":
        answer = await v2.http.request(
            "POST" if resource == "bells" else "PUT",
            "/api/v1/manage/bells" if resource == "bells" else "/api/v1/events",
            json=payload,
            headers=_auth(v2_tokens["admin"]),
        )
        assert (answer.status_code, answer.json()) == (404, {"detail": detail})
    else:
        answer = await v2.connect(method, sent, token=v2_tokens["admin"])
        assert (answer.code, answer.reason, answer.metadata, answer.error) == (
            "NOT_FOUND",
            "RESOURCE_NOT_FOUND",
            metadata,
            detail,
        )
    assert raced
    assert (await _count(SchoolClass), await _count(BellSchedule)) == (0, 0)
    assert (await _count(DayEvent), await _count(AuditEntry)) == (0, 0)


async def test_a_parent_reference_does_not_block_an_unrelated_postgres_class_edit(
    session, school_class
) -> None:
    from sqlalchemy import text, update

    from app.services import gone

    if session.get_bind().dialect.name != "postgresql":
        pytest.skip("PostgreSQL row-lock compatibility; SQLite has one database write lock")
    class_id = school_class.id
    await gone.lock_parent(session, SchoolClass, class_id)
    async with SessionLocal() as other:
        await other.execute(text("SET LOCAL lock_timeout = '500ms'"))
        await other.execute(
            update(SchoolClass).where(SchoolClass.id == class_id).values(name="10А")
        )
        await other.commit()
    await session.rollback()
    async with SessionLocal() as fresh:
        assert (await fresh.get(SchoolClass, class_id)).name == "10А"


@pytest.mark.parametrize("parent", ["class", "schedule"])
async def test_parent_identity_stays_until_the_transaction_ends(
    session, school_class, parent
) -> None:
    from sqlalchemy import text
    from sqlalchemy.exc import DBAPIError

    from app.models import BellSchedule
    from app.services import gone

    model, row_id = (
        (SchoolClass, school_class.id)
        if parent == "class"
        else (BellSchedule, school_class.bell_schedule_id)
    )
    postgres = session.get_bind().dialect.name == "postgresql"
    await gone.lock_parent(session, model, row_id)
    async with SessionLocal() as other:
        original_timeout = None if postgres else await other.scalar(text("PRAGMA busy_timeout"))
        try:
            await other.execute(
                text(
                    "SET LOCAL lock_timeout = '100ms'" if postgres else "PRAGMA busy_timeout = 100"
                )
            )
            with pytest.raises(DBAPIError) as blocked:
                await other.execute(delete(model).where(model.id == row_id))
            if postgres:
                assert blocked.value.orig.sqlstate == "55P03"
            else:
                assert blocked.value.orig.sqlite_errorcode == 5
        finally:
            if not postgres:
                await other.execute(text(f"PRAGMA busy_timeout = {original_timeout}"))
            await other.rollback()
    await session.rollback()
    async with SessionLocal() as other:
        await other.execute(delete(model).where(model.id == row_id))
        await other.commit()
        assert await other.get(model, row_id) is None


@pytest.mark.parametrize("with_day", [False, True])
@pytest.mark.parametrize("transport", ["v1", "v2"])
@pytest.mark.parametrize(
    ("parent", "has_default"),
    [
        pytest.param("class", True, id="class_with_default"),
        pytest.param("class", False, id="class_without_default"),
        pytest.param("schedule", True, id="schedule"),
    ],
)
async def test_timetable_import_losing_a_parent_refuses_without_recreating_it(
    v2, v2_tokens, session, school_class, monkeypatch, transport, parent, with_day, has_default
) -> None:
    from app.contract.lessons.v2.timetable_pb import ImportTimetableRequest
    from app.models import BellSchedule, TimetableEntry
    from app.services import structure

    if not has_default:
        school_class.bell_schedule_id = None
        await session.commit()
    model, row_id = (
        (SchoolClass, school_class.id)
        if parent == "class"
        else (BellSchedule, school_class.bell_schedule_id)
    )
    raced = _deleted_meanwhile(monkeypatch, structure, "apply_timetable", model, row_id)
    before = await _count(TimetableEntry)
    paste = "== Звонки ==\n1. 09:00-09:40"
    if with_day:
        paste = "== Вторник ==\n1. Химия\n\n" + paste
    if transport == "v1":
        answer = await v2.http.post(
            "/api/v1/manage/timetable/import",
            json={"text": paste, "replace": True},
            headers=_auth(v2_tokens["admin"]),
        )
        assert (answer.status_code, answer.json()) == (
            404,
            {"detail": wording.UNKNOWN_BELL_SCHEDULE_DETAIL},
        )
    else:
        answer = await v2.connect(
            "TimetableService/ImportTimetable",
            ImportTimetableRequest(text=paste, replace=True),
            token=v2_tokens["admin"],
        )
        assert (answer.code, answer.reason, answer.metadata, answer.error) == (
            "NOT_FOUND",
            "RESOURCE_NOT_FOUND",
            {"resource": "bell_schedule"},
            wording.UNKNOWN_BELL_SCHEDULE_DETAIL,
        )
    assert raced
    assert (await _count(BellSchedule), await _count(AuditEntry)) == (0, 0)
    assert await _count(TimetableEntry) == (0 if parent == "class" else before)


@pytest.mark.parametrize("clear", [False, True])
async def test_bot_weekday_in_a_deleted_class_drops_the_form(
    session, school_class, monkeypatch, FakeMessage, FakeState, clear
) -> None:
    from app.bot.handlers.timetable import timetable_apply
    from app.services import structure

    raced = _deleted_meanwhile(
        monkeypatch, structure, "apply_timetable", SchoolClass, school_class.id
    )
    state = FakeState(data={"weekday": 1})
    message = FakeMessage(text="-" if clear else "1. Физика")
    await timetable_apply(message, state, session, school_class, Role.ADMIN)
    assert raced
    assert message.last == "Класс уже удалён. Откройте /start."
    assert (state.cleared, state.data) == (True, {})
    assert await _count(AuditEntry) == 0
    await session.commit()


@pytest.mark.parametrize("parent", ["class", "schedule"])
async def test_bot_default_bell_paste_losing_a_parent_drops_the_form(
    session, school_class, monkeypatch, FakeMessage, FakeState, parent
) -> None:
    from app.bot.handlers.timetable import bells_apply
    from app.models import BellSchedule
    from app.services import gone

    model, row_id = (
        (SchoolClass, school_class.id)
        if parent == "class"
        else (BellSchedule, school_class.bell_schedule_id)
    )
    state = FakeState()
    raced = _deleted_meanwhile(monkeypatch, gone, "lock_parent", model, row_id)
    message = FakeMessage(text="1. 09:00-09:40")
    await bells_apply(message, state, session, school_class, Role.ADMIN)
    assert raced
    assert message.last == "Класс или расписание уже удалены. Откройте /start."
    assert (state.cleared, state.data) == (True, {})
    assert await _count(AuditEntry) == 0
    await session.commit()


@pytest.mark.parametrize("parent", ["class", "schedule"])
async def test_bot_import_losing_a_parent_clears_the_preview_and_answers(
    session, school_class, monkeypatch, FakeState, FakeCallback, FakeEditable, parent
) -> None:
    from app.bot.handlers.manage.import_export import import_apply
    from app.models import BellSchedule
    from app.services.manage import timetable

    model, row_id = (
        (SchoolClass, school_class.id)
        if parent == "class"
        else (BellSchedule, school_class.bell_schedule_id)
    )
    raced = _deleted_meanwhile(monkeypatch, timetable, "apply", model, row_id)
    state = FakeState(data={"raw": "== Звонки ==\n1. 09:00-09:40"})
    callback = FakeCallback(message=FakeEditable())
    await import_apply(callback, state, session, school_class, Role.ADMIN)
    assert raced
    assert callback.answers[-1] == ("Класс или звонки уже удалены. Начните импорт заново.", True)
    assert not callback.message.replies
    assert (state.cleared, state.data) == (True, {})
    assert await _count(AuditEntry) == 0
    await session.commit()


@pytest.mark.filterwarnings("error::sqlalchemy.exc.SAWarning")
async def test_bell_deletion_locks_the_parent_before_its_orm_children(
    session, school_class
) -> None:
    import asyncio

    from sqlalchemy import event

    from app.db import engine
    from app.models import BellPeriod, BellSchedule
    from app.services import gone, structure
    from app.services.manage import bells

    if session.get_bind().dialect.name != "postgresql":
        pytest.skip("PostgreSQL parent/child row-lock ordering; SQLite has one write lock")
    schedule = BellSchedule(class_id=school_class.id, name="Сокращённое")
    session.add(schedule)
    await session.flush()
    session.add(
        BellPeriod(schedule_id=schedule.id, index=1, starts_at=time(9), ends_at=time(9, 40))
    )
    await session.commit()
    class_id, schedule_id = school_class.id, schedule.id
    await gone.lock_parent(session, SchoolClass, class_id)
    await gone.lock_parent(session, BellSchedule, schedule_id)
    parent_attempted, children_deleted = asyncio.Event(), asyncio.Event()

    def before_statement(connection, _cursor, statement, _parameters, _context, _many):
        sql = statement.upper()
        if connection.info.get("test_bell_deleter"):
            if ("FROM BELL_SCHEDULES" in sql and "FOR UPDATE" in sql) or sql.lstrip().startswith(
                "DELETE FROM BELL_SCHEDULES"
            ):
                parent_attempted.set()

    def after_statement(connection, _cursor, statement, _parameters, _context, _many):
        if connection.info.get("test_bell_deleter"):
            if statement.upper().lstrip().startswith("DELETE FROM BELL_PERIODS"):
                children_deleted.set()

    async with SessionLocal() as deleter:
        other_class = await deleter.get(SchoolClass, class_id)
        other_schedule = await bells.schedule_of(deleter, class_id, schedule_id)
        info = (await deleter.connection()).sync_connection.info
        info["test_bell_deleter"] = True
        event.listen(engine.sync_engine, "before_cursor_execute", before_statement)
        event.listen(engine.sync_engine, "after_cursor_execute", after_statement)

        async def remove() -> None:
            await bells.delete(deleter, other_class, 42, other_schedule)
            await deleter.commit()

        deleting = asyncio.create_task(remove())
        waiting_parent = asyncio.create_task(parent_attempted.wait())
        waiting_children = asyncio.create_task(children_deleted.wait())
        try:
            done, _ = await asyncio.wait(
                [waiting_parent, waiting_children, deleting],
                timeout=10,
                return_when=asyncio.FIRST_COMPLETED,
            )
            assert done, "the deleter never reached its parent lock or ORM children"
            assert parent_attempted.is_set() and not children_deleted.is_set(), (
                "the deleter locked children before waiting on the editor's parent lock"
            )
            await structure.write_bell_periods(session, schedule, [(1, time(10), time(10, 40))])
            await session.commit()
            await asyncio.wait_for(deleting, timeout=10)
        finally:
            await session.rollback()
            waiting_parent.cancel()
            waiting_children.cancel()
            await asyncio.gather(waiting_parent, waiting_children, return_exceptions=True)
            try:
                await asyncio.wait_for(deleting, timeout=10)
            finally:
                event.remove(engine.sync_engine, "before_cursor_execute", before_statement)
                event.remove(engine.sync_engine, "after_cursor_execute", after_statement)
                info.pop("test_bell_deleter", None)
    async with SessionLocal() as fresh:
        assert await fresh.get(BellSchedule, schedule_id) is None
    assert await _count(AuditEntry) == 1


@pytest.mark.parametrize("transport", ["bot", "v1", "v2"])
@pytest.mark.parametrize("parent", ["class", "schedule"])
async def test_bell_delete_race_is_class_gone_or_an_idempotent_success(
    v2, v2_tokens, session, school_class, monkeypatch, FakeCallback, FakeEditable, transport, parent
) -> None:
    from app.bot.handlers.manage.bells import bells_delete
    from app.contract.lessons.v2.bell_pb import DeleteBellScheduleRequest
    from app.models import BellSchedule
    from app.services.manage import bells

    schedule = BellSchedule(class_id=school_class.id, name="Сокращённое")
    session.add(schedule)
    await session.commit()
    schedule_id = schedule.id
    model, row_id = (
        (SchoolClass, school_class.id) if parent == "class" else (BellSchedule, schedule_id)
    )
    raced = _deleted_meanwhile(monkeypatch, bells, "delete", model, row_id)
    if transport == "bot":
        callback = FakeCallback(message=FakeEditable())
        await bells_delete(
            callback, SimpleNamespace(value=str(schedule_id)), session, school_class, Role.ADMIN
        )
        if parent == "class":
            assert callback.answers[-1] == ("Расписание не найдено", True)
            assert not callback.message.replies
        else:
            assert callback.answers[-1] == ("Удалено: Сокращённое", False)
        await session.commit()
    elif transport == "v1":
        answer = await v2.http.delete(
            f"/api/v1/manage/bells/{schedule_id}", headers=_auth(v2_tokens["admin"])
        )
        expected = (
            (404, {"detail": wording.UNKNOWN_BELL_SCHEDULE_DETAIL})
            if parent == "class"
            else (200, {"id": schedule_id, "deleted": True})
        )
        assert (answer.status_code, answer.json()) == expected
    else:
        answer = await v2.connect(
            "BellService/DeleteBellSchedule",
            DeleteBellScheduleRequest(schedule_id=schedule_id),
            token=v2_tokens["admin"],
        )
        if parent == "class":
            assert (answer.code, answer.reason, answer.metadata, answer.error) == (
                "NOT_FOUND",
                "RESOURCE_NOT_FOUND",
                {"resource": "bell_schedule"},
                wording.UNKNOWN_BELL_SCHEDULE_DETAIL,
            )
        else:
            assert answer.status == 200
    assert raced
    assert await _count(AuditEntry) == 0


async def _bell_delete_candidate(session, school_class):
    from app.models import BellPeriod, BellSchedule

    row = BellSchedule(class_id=school_class.id, name="Сокращённое")
    session.add(row)
    await session.flush()
    session.add(BellPeriod(schedule_id=row.id, index=1, starts_at=time(9), ends_at=time(9, 40)))
    await session.commit()
    await session.refresh(row, ["periods"])
    return row


async def test_bell_delete_rechecks_the_default_after_the_class_was_read(
    session, school_class
) -> None:
    from app.models import BellSchedule
    from app.services.manage import bells

    row = await _bell_delete_candidate(session, school_class)
    class_id, schedule_id = school_class.id, row.id
    async with SessionLocal() as other:
        current = await other.get(SchoolClass, class_id)
        current.bell_schedule_id = schedule_id
        await other.commit()
    assert school_class.bell_schedule_id != schedule_id
    with pytest.raises(bells.ScheduleIsDefault):
        await bells.delete(session, school_class, 42, row)
    await session.rollback()
    async with SessionLocal() as fresh:
        current = await fresh.get(SchoolClass, class_id)
        assert current.bell_schedule_id == schedule_id
        assert await fresh.get(BellSchedule, schedule_id) is not None
    assert await _count(AuditEntry) == 0


async def test_bell_delete_rechecks_a_special_day_added_after_its_usage_count(
    session, school_class, monkeypatch
) -> None:
    from app.models import BellSchedule
    from app.services.manage import bells

    row = await _bell_delete_candidate(session, school_class)
    class_id, schedule_id = school_class.id, row.id
    real = session.scalar
    staged = []

    async def count_then_reference(statement, *args, **kwargs):
        result = await real(statement, *args, **kwargs)
        if not staged and "count(" in str(statement).lower():
            staged.append(True)
            async with SessionLocal() as other:
                other.add(DayOverride(
                    class_id=class_id, date=MONDAY, kind=DayKind.SHORTENED,
                    bell_schedule_id=schedule_id,
                ))
                await other.commit()
        return result

    monkeypatch.setattr(session, "scalar", count_then_reference)
    with pytest.raises(bells.ScheduleInUse) as refusal:
        await bells.delete(session, school_class, 42, row)
    assert staged and refusal.value.days == 1
    await session.rollback()
    async with SessionLocal() as fresh:
        marked = await fresh.scalar(select(DayOverride).where(DayOverride.class_id == class_id))
        assert marked.bell_schedule_id == schedule_id
        assert await fresh.get(BellSchedule, schedule_id) is not None
    assert await _count(AuditEntry) == 0


@pytest.mark.parametrize("reference", ["default", "day"])
async def test_bot_bell_delete_refusal_releases_its_transaction_before_answering(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, reference
) -> None:
    from app.bot.handlers.manage.bells import bells_delete
    from app.models import BellSchedule
    from app.services.manage import bells

    row = await _bell_delete_candidate(session, school_class)
    class_id, schedule_id = school_class.id, row.id
    real_delete = bells.delete

    async def referenced_after_read(*args, **kwargs):
        async with SessionLocal() as other:
            if reference == "default":
                current = await other.get(SchoolClass, class_id)
                current.bell_schedule_id = schedule_id
            else:
                other.add(DayOverride(
                    class_id=class_id, date=MONDAY, kind=DayKind.SHORTENED,
                    bell_schedule_id=schedule_id,
                ))
            await other.commit()
        return await real_delete(*args, **kwargs)

    monkeypatch.setattr(bells, "delete", referenced_after_read)
    callback = FakeCallback(message=FakeEditable())
    real_answer = callback.answer

    async def answer_after_release(*args, **kwargs):
        assert not session.in_transaction(), "the refusal must release locks before Telegram I/O"
        await real_answer(*args, **kwargs)

    monkeypatch.setattr(callback, "answer", answer_after_release)
    await bells_delete(
        callback, SimpleNamespace(value=str(schedule_id)), session, school_class, Role.ADMIN
    )
    expected = (
        "Это основное расписание класса. Сначала сделайте основным другое."
        if reference == "default"
        else "По нему идут особые дни (1). Сначала измените их."
    )
    assert callback.answers == [(expected, True)]
    assert not callback.message.replies
    async with SessionLocal() as fresh:
        assert await fresh.get(BellSchedule, schedule_id) is not None
    assert await _count(AuditEntry) == 0


@pytest.mark.parametrize("transport", ["v1", "rest", "connect"])
@pytest.mark.parametrize("reference", ["default", "day"])
async def test_api_bell_delete_rechecks_new_references_without_changing_its_refusal(
    v2, v2_tokens, session, school_class, monkeypatch, transport, reference
) -> None:
    from app.contract.lessons.v2.bell_pb import DeleteBellScheduleRequest
    from app.models import BellSchedule
    from app.services.manage import bells

    row = await _bell_delete_candidate(session, school_class)
    class_id, schedule_id = school_class.id, row.id
    real_delete = bells.delete
    staged = []

    async def add_reference():
        staged.append(True)
        async with SessionLocal() as other:
            if reference == "default":
                current = await other.get(SchoolClass, class_id)
                current.bell_schedule_id = schedule_id
            else:
                other.add(DayOverride(
                    class_id=class_id, date=MONDAY, kind=DayKind.SHORTENED,
                    bell_schedule_id=schedule_id,
                ))
            await other.commit()

    async def referenced_after_read(write_session, *args, **kwargs):
        if reference == "default":
            await add_reference()
        else:
            real_scalar = write_session.scalar

            async def after_usage_count(statement, *scalar_args, **scalar_kwargs):
                result = await real_scalar(statement, *scalar_args, **scalar_kwargs)
                if not staged and "count(" in str(statement).lower():
                    await add_reference()
                return result

            monkeypatch.setattr(write_session, "scalar", after_usage_count)
        return await real_delete(write_session, *args, **kwargs)

    monkeypatch.setattr(bells, "delete", referenced_after_read)
    expected = (
        wording.BELL_SCHEDULE_IS_DEFAULT_DETAIL
        if reference == "default" else wording.bell_schedule_in_use_detail(1)
    )
    if transport == "v1":
        answer = await v2.http.delete(
            f"/api/v1/manage/bells/{schedule_id}", headers=_auth(v2_tokens["admin"])
        )
        assert (answer.status_code, answer.json()) == (409, {"detail": expected})
    else:
        answer = await getattr(v2, transport)(
            "BellService/DeleteBellSchedule", DeleteBellScheduleRequest(schedule_id=schedule_id),
            token=v2_tokens["admin"],
        )
        assert (answer.status, answer.code, answer.reason, answer.error) == (
            400, "FAILED_PRECONDITION", "RESOURCE_IN_USE", expected
        )
        metadata = {"resource": "bell_schedule", "used_by": "class"}
        if reference == "day":
            metadata = {"resource": "bell_schedule", "used_by": "days", "count": "1"}
        assert answer.metadata == metadata
    assert staged == [True]
    async with SessionLocal() as fresh:
        assert await fresh.get(BellSchedule, schedule_id) is not None
    assert await _count(AuditEntry) == 0


def _day_parent_deleted_before_write(monkeypatch, model, row_id):
    from app.models import BellSchedule
    from app.services import gone
    from app.services.manage import bells

    staged = []

    async def remove_once():
        if not staged:
            staged.append(True)
            async with SessionLocal() as other:
                await other.execute(delete(model).where(model.id == row_id))
                await other.commit()

    real_lock = gone.lock_parent

    async def before_retaining_the_parent(session, target, target_id, **kwargs):
        if (target is model and target_id == row_id) or (
            session.get_bind().dialect.name == "sqlite" and target is SchoolClass
        ):
            await remove_once()
        await real_lock(session, target, target_id, **kwargs)

    monkeypatch.setattr(gone, "lock_parent", before_retaining_the_parent)
    owner, name = (
        (bells, "rings_anything") if model is BellSchedule else (special_days, "mark_on")
    )
    real_read = getattr(owner, name)

    async def after_the_unprotected_read(*args, **kwargs):
        result = await real_read(*args, **kwargs)
        await remove_once()
        return result

    # A guard moved before the read must meet the same lost parent, not block
    # this test's deleting transaction after it already retained that parent.
    monkeypatch.setattr(owner, name, after_the_unprotected_read)
    return staged


@pytest.mark.parametrize("lost", ["class", "schedule"])
async def test_a_special_day_losing_its_parent_after_validation_has_no_foreign_key_error(
    session, school_class, monkeypatch, lost
) -> None:
    from sqlalchemy.orm.exc import StaleDataError

    from app.models import BellSchedule

    schedule = await _bell_delete_candidate(session, school_class)
    model, row_id = (
        (SchoolClass, school_class.id) if lost == "class" else (BellSchedule, schedule.id)
    )
    staged = _day_parent_deleted_before_write(monkeypatch, model, row_id)
    refusal = StaleDataError if lost == "class" else special_days.ScheduleNotInClass
    with pytest.raises(refusal):
        await special_days.set_day(
            session, school_class, 42, MONDAY, kind=DayKind.SHORTENED,
            note="Короткий день", bell_schedule_id=schedule.id,
        )
    await session.rollback()
    assert staged
    assert (await _count(DayOverride), await _count(AuditEntry)) == (0, 0)

@pytest.mark.parametrize("transport", ["v1", "rest", "connect"])
@pytest.mark.parametrize("lost", ["class", "schedule"])
async def test_special_day_parent_races_keep_existing_api_refusals(
    v2, v2_tokens, session, school_class, monkeypatch, transport, lost
) -> None:
    from app.contract.lessons.v2.common_pb import DayKind as ProtoKind
    from app.models import BellSchedule

    schedule = await _bell_delete_candidate(session, school_class)
    schedule_id = schedule.id
    model, row_id = (
        (SchoolClass, school_class.id) if lost == "class" else (BellSchedule, schedule_id)
    )
    staged = []
    real_put = special_days.put_day

    async def lose_parent_inside_the_shared_write(*args, **kwargs):
        lost_at_write = _day_parent_deleted_before_write(monkeypatch, model, row_id)
        try:
            return await real_put(*args, **kwargs)
        finally:
            staged.extend(lost_at_write)

    monkeypatch.setattr(special_days, "put_day", lose_parent_inside_the_shared_write)
    sentence = (
        wording.DAY_NOT_MARKED_DETAIL if lost == "class" else wording.SCHEDULE_NOT_IN_CLASS_DETAIL
    )
    if transport == "v1":
        answer = await v2.http.put(
            "/api/v1/days", headers=_auth(v2_tokens["editor"]),
            json={"date": MONDAY.isoformat(), "kind": "shortened", "bell_schedule_id": schedule_id},
        )
        assert (answer.status_code, answer.json()) == (
            404 if lost == "class" else 422, {"detail": sentence}
        )
    else:
        answer = await getattr(v2, transport)(
            "DayService/UpdateDay",
            UpdateDayRequest(
                day=Day(date=MONDAY.isoformat(), kind=ProtoKind.SHORTENED,
                        bell_schedule_id=schedule_id),
                update_mask=FieldMask(paths=["kind", "bell_schedule_id"]), allow_missing=True,
            ), token=v2_tokens["editor"],
        )
        assert answer.error == sentence
        if lost == "class":
            assert (answer.status, answer.code, answer.reason, answer.metadata) == (
                404, "NOT_FOUND", "RESOURCE_NOT_FOUND", {"resource": "day"}
            )
        else:
            assert (answer.status, answer.reason, answer.violations) == (
                400, "VALIDATION_FAILED", [("day.bell_schedule_id", sentence)]
            )
    assert staged
    assert (await _count(DayOverride), await _count(AuditEntry)) == (0, 0)


@pytest.mark.parametrize("lost,action", [
    ("class", "kind"), ("class", "normal"), ("class", "bells"),
    ("class", "note"), ("class", "delete"), ("class", "period"),
    ("schedule", "kind"), ("schedule", "bells"),
])
async def test_bot_special_day_lost_parent_answers_without_a_form_or_audit(
    session, school_class, monkeypatch, FakeCallback, FakeEditable, FakeMessage, FakeState,
    lost, action,
) -> None:
    from app.bot.handlers.manage import holidays
    from app.models import BellSchedule

    schedule = await _bell_delete_candidate(session, school_class)
    schedule_id, class_id = schedule.id, school_class.id
    if action in {"normal", "bells", "note", "delete"}:
        await _mark(session, school_class)
    if lost == "schedule":
        school_class.bell_schedule_id = schedule_id
        await session.commit()
        staged = _day_parent_deleted_before_write(monkeypatch, BellSchedule, schedule_id)
    else:
        staged = []
        name = "mark_period" if action == "period" else "put_day"
        real_write = getattr(special_days, name)

        async def lose_class_after_the_ui_reads(*args, **kwargs):
            lost_at_write = _day_parent_deleted_before_write(monkeypatch, SchoolClass, class_id)
            try:
                return await real_write(*args, **kwargs)
            finally:
                staged.extend(lost_at_write)

        monkeypatch.setattr(special_days, name, lose_class_after_the_ui_reads)
    state = FakeState(data={"date": MONDAY.isoformat(), "period_kind": DayKind.HOLIDAY.value})
    callback = FakeCallback(message=FakeEditable())
    if action in {"kind", "normal"}:
        tag = "normal" if action == "normal" else "shortened" if lost == "schedule" else "holiday"
        await holidays.holiday_kind(
            callback, SimpleNamespace(value=f"{MONDAY.isoformat()}:{tag}"),
            state, session, school_class, Role.EDITOR,
        )
    elif action == "bells":
        await holidays.holiday_bells(
            callback, SimpleNamespace(value=f"{MONDAY.isoformat()}:{schedule_id}"),
            state, session, school_class, Role.EDITOR,
        )
    elif action == "delete":
        await holidays.holiday_delete(
            callback, SimpleNamespace(value=MONDAY.isoformat()),
            state, session, school_class, Role.EDITOR,
        )
    else:
        message = FakeMessage(text="14.09.2026-16.09.2026" if action == "period" else "Заметка")
        handler = holidays.holiday_period_apply if action == "period" else holidays.holiday_note
        await handler(message, state, session, school_class, Role.ADMIN)
        assert message.last == (PERIOD_GONE if action == "period" else f"{DAY_GONE}.")
    if action not in {"note", "period"}:
        sentence = "Расписание звонков не найдено" if lost == "schedule" else DAY_GONE
        assert callback.answers[-1] == (sentence, True)
        assert not callback.message.replies
    assert staged
    assert (state.cleared, state.data) == (True, {})
    kept = 1 if lost == "schedule" and action == "bells" else 0
    assert (await _count(DayOverride), await _count(AuditEntry)) == (kept, 0)
    if kept:
        async with SessionLocal() as fresh:
            mark = await fresh.scalar(select(DayOverride))
            assert (mark.kind, mark.note, mark.bell_schedule_id) == (
                DayKind.HOLIDAY, "каникулы", None
            )
    await session.commit()
