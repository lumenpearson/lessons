"""``SubstitutionService``'s changes: ``UpdateSubstitution`` and ``DeleteSubstitution``.

v1 changed a substitution by sending it again (``PUT /overrides``) and took it
away with ``"clear"``; v2 changes it in place, through
``substitutions.update``, and deletes it through ``substitutions.delete``. The
mask is read once, by ``masks.update_paths`` (AIP-134): no mask changes what
the request sets; a masked subject, room, teacher or note left unset is
cleared, and a masked action is refused, since a substitution always replaces
or cancels. A row at a number the day no longer rings stays editable (the 3b
plan, «Rulings for 3b-6»), but a day that draws no lessons and a lesson the
template does not have are refused as on a create. Each change is announced
once committed, in v1's words and never to its author; a change that changes
nothing writes nothing and tells nobody. A write's success is asked once per
transport on fresh data, and its refusals through ``both``.
"""

from __future__ import annotations

from datetime import date, datetime
from typing import Any

from protobuf.wkt import FieldMask
from sqlalchemy import select

from app import wording
from app.contract.lessons.v2.substitution_pb import (
    CreateSubstitutionRequest,
    DeleteSubstitutionRequest,
    GetSubstitutionRequest,
    Substitution,
    SubstitutionAction,
    UpdateSubstitutionRequest,
)
from app.db import SessionLocal
from app.models import AuditEntry, LessonOverride, OverrideAction, SchoolClass
from app.rpc.masks import NOT_CHANGEABLE
from app.rpc.substitution import ACTION_REFUSED, UNKNOWN_SUBSTITUTION
from app.services import clock

UPDATE = "SubstitutionService/UpdateSubstitution"
DELETE = "SubstitutionService/DeleteSubstitution"
REPLACE, CANCEL = OverrideAction.REPLACE, OverrideAction.CANCEL
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


async def _substitution(
    session, school_class, index: int, day: date = MONDAY, **fields: Any
) -> LessonOverride:
    row = LessonOverride(
        class_id=school_class.id,
        date=day,
        index=index,
        action=fields.pop("action", REPLACE),
        **fields,
    )
    session.add(row)
    await session.commit()
    return row


def _update(substitution_id: int, *paths: str, **fields: Any) -> UpdateSubstitutionRequest:
    mask = FieldMask(paths=list(paths)) if paths else None
    return UpdateSubstitutionRequest(
        substitution=Substitution(id=substitution_id, **fields), update_mask=mask
    )


async def _columns(substitution_id: int) -> tuple[Any, ...]:
    async with SessionLocal() as fresh:
        row = await fresh.execute(
            select(
                LessonOverride.action,
                LessonOverride.subject_name,
                LessonOverride.room,
                LessonOverride.teacher,
                LessonOverride.note,
            ).where(LessonOverride.id == substitution_id)
        )
        found = row.one_or_none()
        return tuple(found) if found is not None else ()


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
    chemistry = await _substitution(
        session, school_class, 2, subject_name="Химия", room="118", teacher="Иванов", note="x"
    )
    physics = await _substitution(session, school_class, 3, subject_name="Физика")
    editor = v2_tokens["editor"]
    rest = await v2.rest(UPDATE, _update(chemistry.id, room=" 214 "), token=editor)
    connect = await v2.connect(UPDATE, _update(physics.id, teacher="Петров"), token=editor)
    assert (rest.status, connect.status) == (200, 200)
    assert await _columns(chemistry.id) == (REPLACE, "Химия", "214", "Иванов", "x")
    assert await _columns(physics.id) == (REPLACE, "Физика", None, "Петров", None)
    assert connect.message.substitution.teacher == "Петров"
    v1 = await v2.http.get(
        "/api/v1/bundle", params={"start": "2026-09-14", "days": 1}, headers=_auth(editor)
    )
    lessons = v1.json()["days"][0]["lessons"]
    assert [(lesson["room"], lesson["teacher"]) for lesson in lessons[1:]] == [
        ("214", "Иванов"),
        (None, "Петров"),
    ]
    assert await _lines() == [
        ("override.replace", f"Замена: урок №2, {WHEN} — Химия"),
        ("override.replace", f"Замена: урок №3, {WHEN} — Физика"),
    ]


async def test_a_masked_field_left_out_is_cleared_but_the_action_is_refused(
    v2, v2_tokens, session, school_class
) -> None:
    row = await _substitution(session, school_class, 2, subject_name="Химия", room="118", note="x")
    bare = await _substitution(session, school_class, 3, subject_name="Физика")
    editor = v2_tokens["editor"]
    cleared = await v2.rest(UPDATE, _update(row.id, "room", "note"), token=editor)
    assert cleared.status == 200
    assert await _columns(row.id) == (REPLACE, "Химия", None, None, None)
    refused = await v2.both(UPDATE, _update(row.id, "action"), token=editor)
    assert (refused.status, refused.violations) == (
        400,
        [("substitution.action", ACTION_REFUSED)],
    )
    # A replacement left with no subject, room or teacher draws nothing.
    emptied = await v2.both(UPDATE, _update(bare.id, "subject"), token=editor)
    assert (emptied.reason, [name for name, _ in emptied.violations]) == (
        "VALIDATION_FAILED",
        ["substitution"],
    )
    assert await _columns(bare.id) == (REPLACE, "Физика", None, None, None)


async def test_a_row_at_a_number_that_no_longer_rings_stays_editable(
    v2, v2_tokens, session, school_class
) -> None:
    """``UpdateSubstitution`` skips the bell, and ``CreateSubstitution`` keeps
    it: a row a class can no longer see must stay changeable, which is how it
    gets out of one (the proto, and the 3b plan, «Rulings for 3b-6»)."""
    stray = await _substitution(session, school_class, 9, subject_name="Химия")
    editor = v2_tokens["editor"]
    changed = await v2.connect(UPDATE, _update(stray.id, room="214"), token=editor)
    assert (changed.status, changed.message.substitution.room) == (200, "214")
    created = await v2.both(
        "SubstitutionService/CreateSubstitution",
        CreateSubstitutionRequest(
            substitution=Substitution(
                date="2026-09-21", index=9, action=SubstitutionAction.REPLACE, subject="Химия"
            )
        ),
        token=editor,
    )
    assert (created.reason, created.metadata) == ("NO_BELL_FOR_LESSON", {"index": "9"})


async def test_an_update_that_would_leave_nothing_to_draw_is_refused(
    v2, v2_tokens, session, school_class
) -> None:
    """Lesson 7 was added by a substitution at a number the template leaves
    empty. Cancelling it would strike through nothing, and taking its subject
    away would leave a teacher over nothing; deleting it is the way out."""
    added = await _substitution(
        session, school_class, 7, subject_name="Астрономия", teacher="Иванов"
    )
    editor = v2_tokens["editor"]
    for request, cancelling in (
        (_update(added.id, action=SubstitutionAction.CANCEL), True),
        (_update(added.id, "subject"), False),
    ):
        refused = await v2.both(UPDATE, request, token=editor)
        assert (refused.status, refused.reason, refused.metadata) == (
            400,
            "LESSON_NOT_ON_TIMETABLE",
            {"index": "7"},
        ), cancelling
        assert refused.error == wording.lesson_not_on_timetable_detail(7, cancelling=cancelling)
    assert await _columns(added.id) == (REPLACE, "Астрономия", None, "Иванов", None)
    gone = await v2.rest(DELETE, DeleteSubstitutionRequest(substitution_id=added.id), token=editor)
    assert (gone.status, await _columns(added.id)) == (200, ())


async def test_an_update_on_a_day_that_draws_no_lessons_is_refused(
    v2, v2_tokens, session, school_class
) -> None:
    """A row written before the checks existed, on a summer date: its change
    would be announced about a lesson nobody can see. Deleting it still
    works."""
    summer = await _substitution(
        session, school_class, 1, day=date(2027, 6, 7), subject_name="Старое"
    )
    editor = v2_tokens["editor"]
    refused = await v2.both(UPDATE, _update(summer.id, subject="Новое"), token=editor)
    assert (refused.status, refused.reason, refused.metadata) == (
        400,
        "NO_LESSON_ON_DAY",
        {"why": "out_of_year"},
    )
    assert await _columns(summer.id) == (REPLACE, "Старое", None, None, None)
    gone = await v2.connect(
        DELETE, DeleteSubstitutionRequest(substitution_id=summer.id), token=editor
    )
    assert gone.status == 200


async def test_an_update_is_announced_after_the_commit_in_v1_s_words(
    v2, v2_tokens, session, school_class, notices, subscribers, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    row = await _substitution(session, school_class, 2, subject_name="Химия", room="118")

    async def looks() -> Any:
        async with SessionLocal() as fresh:
            return await fresh.scalar(
                select(LessonOverride.action).where(LessonOverride.id == row.id)
            )

    notices.looks = looks
    answer = await v2.connect(
        UPDATE,
        _update(row.id, "action", "note", action=SubstitutionAction.CANCEL, note="болеет <2>"),
        token=v2_tokens["editor"],
    )
    assert answer.status == 200
    # A cancellation keeps no subject, room or teacher of its own.
    assert await _columns(row.id) == (CANCEL, None, None, None, "болеет <2>")
    notice = f"🚫 Урок №2 {WHEN} отменён.\nболеет &lt;2&gt;"
    # v1's words, to those who asked to hear about changes but its author,
    # and only once a session of the bot's own could read the change.
    assert sorted(notices.sent) == [(7001, notice), (7003, notice)]
    assert notices.saw == [CANCEL, CANCEL]
    assert await _lines() == [("override.cancel", f"Урок №2 отменён, {WHEN}")]


async def test_an_update_that_changes_nothing_writes_nothing_and_tells_nobody(
    v2, v2_tokens, session, school_class, notices, subscribers, statement_writes
) -> None:
    """A retried update, or one that sends what is there: no line in the
    journal and no second notice. The call before it touched the phone's last
    call, so inside the fifteen minutes there is nothing else it may write."""
    row = await _substitution(session, school_class, 2, subject_name="Химия", room="118")
    editor = v2_tokens["editor"]
    await v2.rest(
        "SubstitutionService/GetSubstitution",
        GetSubstitutionRequest(substitution_id=row.id),
        token=editor,
    )
    with statement_writes() as seen:
        same = await v2.both(UPDATE, _update(row.id, subject="Химия", room=" 118 "), token=editor)
        nothing = await v2.both(UPDATE, _update(row.id), token=editor)
    assert (same.status, same.message.substitution.room) == (200, "118")
    assert nothing.message.substitution.subject == "Химия"
    assert seen == []
    assert notices.built == 0


async def test_deleting_a_substitution_puts_the_lesson_back_and_announces_it(
    v2, v2_tokens, session, school_class, notices, subscribers, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    first = await _substitution(session, school_class, 2, action=CANCEL)
    second = await _substitution(session, school_class, 3, action=CANCEL)
    editor = v2_tokens["editor"]
    rest = await v2.rest(DELETE, DeleteSubstitutionRequest(substitution_id=first.id), token=editor)
    connect = await v2.connect(
        DELETE, DeleteSubstitutionRequest(substitution_id=second.id), token=editor
    )
    assert (rest.status, rest.body, connect.status) == (200, b"{}", 200)
    v1 = await v2.http.get(
        "/api/v1/bundle", params={"start": "2026-09-14", "days": 1}, headers=_auth(editor)
    )
    assert [lesson["is_cancelled"] for lesson in v1.json()["days"][0]["lessons"]] == [
        False,
        False,
        False,
    ]
    assert await _lines() == [
        ("override.clear", f"Замена снята: урок №2, {WHEN}"),
        ("override.clear", f"Замена снята: урок №3, {WHEN}"),
    ]
    assert sorted(text for _, text in notices.sent) == [
        f"♻️ Урок №2 {WHEN} снова идёт по расписанию.",
        f"♻️ Урок №2 {WHEN} снова идёт по расписанию.",
        f"♻️ Урок №3 {WHEN} снова идёт по расписанию.",
        f"♻️ Урок №3 {WHEN} снова идёт по расписанию.",
    ]
    again = await v2.both(DELETE, DeleteSubstitutionRequest(substitution_id=first.id), token=editor)
    assert (again.status, again.reason, again.error) == (
        404,
        "RESOURCE_NOT_FOUND",
        UNKNOWN_SUBSTITUTION,
    )
    assert notices.built == 2


async def test_a_substitution_of_another_class_can_be_neither_changed_nor_deleted(
    v2, v2_tokens, session, notices, subscribers
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    foreign = LessonOverride(
        class_id=other.id, date=MONDAY, index=2, action=REPLACE, subject_name="Химия"
    )
    session.add(foreign)
    await session.commit()
    for name, request in (
        (UPDATE, _update(foreign.id, room="214")),
        (DELETE, DeleteSubstitutionRequest(substitution_id=foreign.id)),
    ):
        answer = await v2.both(name, request, token=v2_tokens["editor"])
        assert (answer.status, answer.reason, answer.metadata) == (
            404,
            "RESOURCE_NOT_FOUND",
            {"resource": "substitution"},
        ), name
        assert answer.error == UNKNOWN_SUBSTITUTION
    assert await _columns(foreign.id) == (REPLACE, "Химия", None, None, None)
    assert notices.built == 0


async def test_a_mask_naming_a_field_the_method_does_not_change_is_refused_on_it(
    v2, v2_tokens, session, school_class
) -> None:
    row = await _substitution(session, school_class, 2, subject_name="Химия")
    for path in ("date", "index", "id"):
        answer = await v2.both(UPDATE, _update(row.id, path, room="214"), token=v2_tokens["editor"])
        assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED"), path
        assert answer.violations == [("update_mask", NOT_CHANGEABLE)]
    assert await _columns(row.id) == (REPLACE, "Химия", None, None, None)
