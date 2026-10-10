"""``ResetCorrections`` and ``ClearCorrections``: v1's two ways of taking corrections off, over v2.

v1's ``POST /overrides/reset`` and ``DELETE /overrides/all``, through
``services/diary_corrections``, so a correction taken off by either version is
off for everyone who sees the child, and for nobody else
(``docs/specs/2026-10-05-server-v2-design.md``, decisions 2 and 14). Both
answer the same whether or not there was anything to take off, so each is
asked through ``both``. Nothing here reaches a real diary: Petersburg's pooled
client is replaced by one over ``httpx.MockTransport``.
"""

from __future__ import annotations

from datetime import date
from typing import Any

import httpx
import pytest
from sqlalchemy import select

from app import wording
from app.contract.lessons.v2.diary_pb import (
    ClearCorrectionsRequest,
    ClearCorrectionsResponse,
    CorrectionKey,
    ListScheduleDaysRequest,
    ResetCorrectionsRequest,
    ResetCorrectionsResponse,
)
from app.db import SessionLocal
from app.models import DiaryOverride
from app.providers.petersburg import client as pbclient
from app.providers.petersburg import provider as pbprovider
from app.rpc.diary import CORRECTIONS_MAX, TOO_MANY_CORRECTIONS
from app.services import diary_corrections

RESET = "DiaryService/ResetCorrections"
CLEAR = "DiaryService/ClearCorrections"
SCOPE = "CHILD:petersburg"
CHILDREN = "/api/journal/person/related-child-list"
SCHEDULE = "/api/journal/schedule/list-by-education"
LESSON = "lesson:2026-09-15:n1:Алгебра"
PUPILS = [
    {
        "identity": {"id": 4021},
        "firstname": "Пётр",
        "surname": "Иванов",
        "educations": [{"education_id": 90210, "group_id": 771, "group_name": "9А"}],
    },
    # Listed by a plain id, outside the diary's own numbering: a child who can
    # have no corrections at all.
    {
        "id": 7,
        "firstname": "Анна",
        "surname": "Иванова",
        "educations": [{"education_id": 90777, "group_id": 772, "group_name": "5Б"}],
    },
]


@pytest.fixture
def petersburg(monkeypatch, FakeUpstream):
    """Petersburg's upstream on 14 September 2026: two pupils, and one lesson."""
    fake = FakeUpstream(
        {
            CHILDREN: {"items": PUPILS},
            SCHEDULE: {
                "items": [
                    {"date": "15.09.2026", "subject_name": "Алгебра", "number": 1, "office": "12"}
                ]
            },
        }
    )

    async def shared() -> httpx.AsyncClient:
        return httpx.AsyncClient(
            base_url=pbclient.BASE_URL, transport=httpx.MockTransport(fake.handler)
        )

    monkeypatch.setattr(pbclient, "shared_client", shared)
    monkeypatch.setattr(pbprovider, "today", lambda: date(2026, 9, 14))
    return fake


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _reset(*keys: tuple[str, str], student_id: int = 4021) -> ResetCorrectionsRequest:
    return ResetCorrectionsRequest(
        student_id=student_id,
        corrections=[CorrectionKey(target=target, field=field) for target, field in keys],
    )


async def _correct(
    session,
    target: str,
    field: str,
    value: str,
    *,
    student_id: int = 4021,
    scope: str = SCOPE,
) -> None:
    session.add(
        DiaryOverride(login=scope, student_id=student_id, target=target, field=field, value=value)
    )
    await session.commit()


async def _stored() -> list[tuple[str, int, str, str, str]]:
    """Every correction, as a session of its own reads it: only what is committed."""
    async with SessionLocal() as fresh:
        rows = await fresh.scalars(select(DiaryOverride).order_by(DiaryOverride.id))
        return [(row.login, row.student_id, row.target, row.field, row.value) for row in rows]


async def test_a_reset_takes_the_named_corrections_off_and_nothing_else(
    v2, v2_tokens, session, petersburg
) -> None:
    await _correct(session, LESSON, "room", "204")
    await _correct(session, LESSON, "teacher", "Иванова И. И.")
    await _correct(session, "hw:id:77", "text", "§ 3, задачи 1–5")
    token = v2_tokens["diary"]
    answer = await v2.both(RESET, _reset((LESSON, "room"), ("hw:id:77", "text")), token=token)
    assert (answer.status, answer.message) == (200, ResetCorrectionsResponse())
    assert await _stored() == [(SCOPE, 4021, LESSON, "teacher", "Иванова И. И.")]
    v1 = (await v2.http.get("/api/v1/diary/students/4021/overrides", headers=_auth(token))).json()
    assert [row["field"] for row in v1] == ["teacher"]
    days = (
        await v2.rest(
            "DiaryService/ListScheduleDays",
            ListScheduleDaysRequest(
                student_id=4021, start_date="2026-09-15", end_date="2026-09-15"
            ),
            token=token,
        )
    ).message.schedule_days
    # The diary answers for the room again; the teacher stays corrected.
    lesson = days[0].lessons[0]
    assert (lesson.room, lesson.teacher, [edit.field for edit in lesson.edits]) == (
        "12",
        "Иванова И. И.",
        ["teacher"],
    )


async def test_resetting_what_was_never_corrected_answers_the_same(
    v2, v2_tokens, petersburg
) -> None:
    """«No correction here» is what was asked for: no error, and nothing to
    tell a retry from the first call by."""
    token = v2_tokens["diary"]
    for request in (_reset((LESSON, "topic")), _reset()):
        answer = await v2.both(RESET, request, token=token)
        assert (answer.status, answer.message) == (200, ResetCorrectionsResponse())
    v1 = await v2.http.post(
        "/api/v1/diary/students/4021/overrides/reset",
        headers=_auth(token),
        json={"target": LESSON, "field": "topic"},
    )
    assert v1.status_code == 204


async def test_clearing_takes_every_correction_of_this_child_and_none_of_another_s(
    v2, v2_tokens, session, petersburg
) -> None:
    await _correct(session, LESSON, "room", "204")
    await _correct(session, "hw:id:77", "text", "§ 3")
    await _correct(session, LESSON, "room", "305", student_id=4022)
    await _correct(
        session, LESSON, "room", "чужой дневник", scope="CHILD:netschool:region.zabedu.ru"
    )
    answer = await v2.both(
        CLEAR, ClearCorrectionsRequest(student_id=4021), token=v2_tokens["diary"]
    )
    assert (answer.status, answer.message) == (200, ClearCorrectionsResponse())
    assert await _stored() == [
        (SCOPE, 4022, LESSON, "room", "305"),
        ("CHILD:netschool:region.zabedu.ru", 4021, LESSON, "room", "чужой дневник"),
    ]


async def test_a_key_v1_would_refuse_is_refused_on_its_field_before_the_diary_is_asked(
    v2, v2_tokens, session, petersburg
) -> None:
    await _correct(session, LESSON, "room", "204")
    token = v2_tokens["diary"]
    for request, field in (
        (_reset((LESSON, "room"), ("", "room")), "corrections[1].target"),
        (_reset((LESSON, "room"), (LESSON, "f" * 41)), "corrections[1].field"),
        (_reset(*[(LESSON, "room")] * (CORRECTIONS_MAX + 1)), "corrections"),
    ):
        refused = await v2.both(RESET, request, token=token)
        assert (refused.status, refused.reason) == (400, "VALIDATION_FAILED"), field
        assert [name for name, _ in refused.violations] == [field]
    assert refused.error == TOO_MANY_CORRECTIONS
    assert petersburg.seen == []
    assert await _stored() == [(SCOPE, 4021, LESSON, "room", "204")]


async def test_a_pupil_who_can_have_none_has_nothing_to_reset_or_clear(
    v2, v2_tokens, session, petersburg
) -> None:
    """Its plain 7 reaches nothing of the person whose id is 7, and both
    answer as for a pupil with nothing corrected."""
    await _correct(session, LESSON, "room", "204", student_id=7)
    token = v2_tokens["diary"]
    reset = await v2.both(RESET, _reset((LESSON, "room"), student_id=7), token=token)
    cleared = await v2.both(CLEAR, ClearCorrectionsRequest(student_id=7), token=token)
    assert (reset.status, cleared.status) == (200, 200)
    assert await _stored() == [(SCOPE, 7, LESSON, "room", "204")]


async def test_another_family_s_child_reaches_nothing_on_a_reset_or_a_clear(
    v2, v2_tokens, session, petersburg
) -> None:
    await _correct(session, LESSON, "room", "204", student_id=999)
    token = v2_tokens["diary"]
    for name, request in (
        (RESET, _reset((LESSON, "room"), student_id=999)),
        (CLEAR, ClearCorrectionsRequest(student_id=999)),
    ):
        refused = await v2.both(name, request, token=token)
        assert (refused.status, refused.reason, refused.metadata, refused.error) == (
            404,
            "RESOURCE_NOT_FOUND",
            {"resource": "student"},
            wording.UNKNOWN_STUDENT_DETAIL,
        ), name
    v1 = await v2.http.delete("/api/v1/diary/students/999/overrides/all", headers=_auth(token))
    assert v1.status_code == 404
    assert await _stored() == [(SCOPE, 999, LESSON, "room", "204")]


async def test_a_reset_that_fails_midway_takes_nothing_off(
    v2, v2_tokens, session, petersburg, monkeypatch
) -> None:
    """All or none, as a batch is written: the second key fails once the
    first is taken off in the database — its own read flushes the first delete
    — and ``invoke``'s one rollback puts the first back."""
    await _correct(session, LESSON, "room", "204")
    await _correct(session, LESSON, "teacher", "Иванова И. И.")
    drop = diary_corrections.drop_override
    calls: list[object] = []

    async def fails_the_second(*args: Any, **kwargs: Any) -> bool:
        taken = await drop(*args, **kwargs)
        calls.append(args)
        if len(calls) == 2:
            raise RuntimeError("the connection went away")
        return taken

    monkeypatch.setattr(diary_corrections, "drop_override", fails_the_second)
    request = _reset((LESSON, "room"), (LESSON, "teacher"))
    for call in (v2.rest, v2.connect):
        calls.clear()
        failed = await call(RESET, request, token=v2_tokens["diary"])
        assert (failed.status, failed.code) == (500, "INTERNAL")
        assert [field for _, _, _, field, _ in await _stored()] == ["room", "teacher"]
