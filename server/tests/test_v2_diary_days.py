"""The diary's reads of a window: ``ListScheduleDays``, ``ListDiaryHomework`` and ``ListMarks``.

v1's ``/schedule``, ``/homework`` and ``/grades`` over v2, through
``services/diary``: the window from the diary's own today, the pupil resolved
from the session's own diary, and the family's corrections — the child's, in
this diary — laid over the lessons and the homework and never over a mark
(``docs/specs/2026-10-05-server-v2-design.md``, decisions 10 and 14). Each is
read against v1's own answer for the same session. A window v2 refuses is
refused before the diary is asked anything. Nothing here reaches a real
diary: Petersburg's pooled client is replaced by one over
``httpx.MockTransport``, and its clock is pinned to Monday 14 September 2026.
"""

from __future__ import annotations

from datetime import date
from typing import Any

import httpx
import pytest

from app.contract.lessons.v2.diary_pb import (
    ListDiaryHomeworkRequest,
    ListMarksRequest,
    ListScheduleDaysRequest,
    MarkKind,
)
from app.models import DiaryOverride
from app.providers.petersburg import client as pbclient
from app.providers.petersburg import provider as pbprovider
from app.services import clock

TODAY = date(2026, 9, 14)
CHILDREN = "/api/journal/person/related-child-list"
SCHEDULE = "/api/journal/schedule/list-by-education"
LESSONS = "/api/journal/lesson/list-by-education"
MARKS = "/api/journal/estimate/table"
CHILD = {
    "identity": {"id": 4021},
    "firstname": "Пётр",
    "surname": "Иванов",
    "educations": [
        {
            "education_id": 90210,
            "group_id": 771,
            "group_name": "9А",
            "institution_name": "ГБОУ СОШ № 1",
        }
    ],
}
#: The three windowed reads, each with its request and v1's path.
READS = {
    "DiaryService/ListScheduleDays": (ListScheduleDaysRequest, "schedule"),
    "DiaryService/ListDiaryHomework": (ListDiaryHomeworkRequest, "homework"),
    "DiaryService/ListMarks": (ListMarksRequest, "grades"),
}


@pytest.fixture
def petersburg(monkeypatch, FakeUpstream):
    """Petersburg's upstream on 14 September 2026: a Tuesday answered before a
    Monday, homework, and three register entries."""
    fake = FakeUpstream(
        {
            CHILDREN: {"items": [CHILD]},
            SCHEDULE: {
                "items": [
                    {
                        "date": "15.09.2026",
                        "subject_name": "История",
                        "number": 1,
                        "time_from": "08:30",
                        "time_to": "09:15",
                    },
                    {
                        "date": "14.09.2026",
                        "subject_name": "Алгебра",
                        "number": 2,
                        "office": "12",
                        "teacher_name": "Петрова А. И.",
                        "theme": "Квадратные уравнения",
                    },
                    {"date": "14.09.2026", "subject_name": "Физика", "number": 1},
                ]
            },
            LESSONS: {
                "items": [
                    {"date": "15.09.2026", "subject_name": "Алгебра", "task": "№ 42"},
                    {
                        "identity": {"id": 77},
                        "date": "16.09.2026",
                        "subject_name": "Физика",
                        "task": "§ 3",
                    },
                ]
            },
            MARKS: {
                "items": [
                    {
                        "id": 1,
                        "date": "10.09.2026",
                        "subject_id": 12,
                        "subject_name": "Алгебра",
                        "estimate_value_name": "5",
                        "estimate_type_code": "10000",
                        "estimate_type_name": "Ответ на уроке",
                    },
                    {
                        "id": 2,
                        "date": "11.09.2026",
                        "subject_id": 12,
                        "subject_name": "Алгебра",
                        "estimate_type_code": "30000",
                    },
                ]
            },
        }
    )

    async def shared() -> httpx.AsyncClient:
        return httpx.AsyncClient(
            base_url=pbclient.BASE_URL, transport=httpx.MockTransport(fake.handler)
        )

    monkeypatch.setattr(pbclient, "shared_client", shared)
    monkeypatch.setattr(pbprovider, "today", lambda: TODAY)
    return fake


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _sent(message: Any, *names: str) -> tuple[Any, ...]:
    """The fields of ``message``, ``None`` for an ``optional`` one left unset,
    which protobuf reads as its zero: v1 wrote it as ``null``."""
    return tuple(getattr(message, name) if message.has_field(name) else None for name in names)


async def _v1(v2, path: str, token: str, **query: str) -> Any:
    answer = await v2.http.get(
        f"/api/v1/diary/students/4021/{path}", params=query, headers=_auth(token)
    )
    assert answer.status_code == 200, answer.text
    return answer.json()


async def _correct(
    session,
    target: str,
    field: str,
    value: str,
    *,
    student_id: int = 4021,
    scope: str = "CHILD:petersburg",
) -> None:
    session.add(
        DiaryOverride(
            login=scope,
            student_id=student_id,
            target=target,
            field=field,
            value=value,
            original=None,
        )
    )
    await session.commit()


async def test_the_lessons_are_v1_s_day_by_day_with_the_corrections_laid_over(
    v2, v2_tokens, session, petersburg
) -> None:
    token = v2_tokens["diary"]
    await _correct(session, "lesson:2026-09-14:n2:Алгебра", "room", "301")
    request = ListScheduleDaysRequest(
        student_id=4021, start_date="2026-09-14", end_date="2026-09-20"
    )
    days = (await v2.both("DiaryService/ListScheduleDays", request, token=token)).message
    v1 = await _v1(v2, "schedule", token, **{"from": "2026-09-14", "to": "2026-09-20"})
    # One entry per day that has lessons, in date order, each day's lessons in
    # the order the diary is read in: v1's list, grouped.
    assert [
        (day.date, [lesson.subject for lesson in day.lessons]) for day in days.schedule_days
    ] == [
        ("2026-09-14", ["Физика", "Алгебра"]),
        ("2026-09-15", ["История"]),
    ]
    flat = [(day.date, lesson) for day in days.schedule_days for lesson in day.lessons]
    for (day, lesson), expected in zip(flat, v1, strict=True):
        fields = ("number", "subject", "room", "teacher", "topic", "homework")
        assert (day, *_sent(lesson, *fields)) == (
            expected["date"],
            *(expected[field] for field in fields),
        )
        assert (lesson.target, lesson.ambiguous) == (expected["target"], expected["ambiguous"])
        assert [(e.field, e.value, e.original, e.changed_upstream) for e in lesson.edits] == [
            (e["field"], e["value"], e["original"], e["changed_upstream"])
            for e in expected["edits"]
        ]
    algebra = days.schedule_days[0].lessons[1]
    assert (algebra.room, [edit.original for edit in algebra.edits]) == ("301", ["12"])
    history = days.schedule_days[1].lessons[0]
    # Times are "HH:MM", where v1 wrote seconds.
    assert (history.starts_at, history.ends_at) == ("08:30", "09:15")
    assert v1[2]["starts_at"] == "08:30:00"


async def test_homework_is_v1_s_with_its_corrections(v2, v2_tokens, session, petersburg) -> None:
    token = v2_tokens["diary"]
    await _correct(session, "hw:id:77", "text", "§ 3, задачи 1–5")
    request = ListDiaryHomeworkRequest(student_id=4021)
    homework = (
        await v2.both("DiaryService/ListDiaryHomework", request, token=token)
    ).message.homework
    v1 = await _v1(v2, "homework", token)
    assert (
        [
            (item.due_date, item.subject, item.text, item.target, item.has_field("id"))
            for item in homework
        ]
        == [
            (
                item["due_date"],
                item["subject"],
                item["text"],
                item["target"],
                item["id"] is not None,
            )
            for item in v1
        ]
        == [
            ("2026-09-15", "Алгебра", "№ 42", "hw:2026-09-15:Алгебра", False),
            ("2026-09-16", "Физика", "§ 3, задачи 1–5", "hw:id:77", True),
        ]
    )
    assert [(edit.field, edit.original) for edit in homework[1].edits] == [("text", "§ 3")]


async def test_marks_are_v1_s_and_never_corrected(v2, v2_tokens, session, petersburg) -> None:
    token = v2_tokens["diary"]
    request = ListMarksRequest(student_id=4021, start_date="2026-09-07", end_date="2026-09-13")
    marks = (await v2.both("DiaryService/ListMarks", request, token=token)).message.marks
    v1 = await _v1(v2, "grades", token, **{"from": "2026-09-07", "to": "2026-09-13"})
    fields = ("id", "subject_id", "subject", "date", "value", "reason", "comment")
    assert [_sent(mark, *fields) for mark in marks] == [
        tuple(mark[field] for field in fields) for mark in v1
    ]
    assert [(m.value, m.kind) for m in marks] == [("5", MarkKind.GRADE), ("Н", MarkKind.ABSENCE)]
    assert [m["kind"] for m in v1] == ["grade", "absence"]
    params = petersburg.query(MARKS)
    assert (params["p_date_from"], params["p_date_to"]) == ("07.09.2026", "13.09.2026")


async def test_the_window_is_the_diary_s_today_and_fourteen_days_on(
    v2, v2_tokens, petersburg
) -> None:
    token = v2_tokens["diary"]
    await v2.rest(
        "DiaryService/ListScheduleDays", ListScheduleDaysRequest(student_id=4021), token=token
    )
    params = petersburg.query(SCHEDULE)
    assert (params["p_datetime_from"], params["p_datetime_to"]) == ("14.09.2026", "28.09.2026")
    petersburg.seen.clear()
    await v2.rest(
        "DiaryService/ListScheduleDays",
        ListScheduleDaysRequest(student_id=4021, start_date="2026-10-01"),
        token=token,
    )
    params = petersburg.query(SCHEDULE)
    assert (params["p_datetime_from"], params["p_datetime_to"]) == ("01.10.2026", "15.10.2026")


@pytest.mark.parametrize(
    ("start", "end", "field", "error"),
    [
        ("2026-09-20", "2026-09-01", "end_date", "end_date must not precede start_date"),
        ("2026-01-01", "2026-12-31", "end_date", clock.WINDOW_TOO_WIDE),
        ("9999-12-31", None, "start_date", clock.DATES_OUT_OF_BOUNDS),
        ("2026-09-01", "1999-12-31", "end_date", clock.DATES_OUT_OF_BOUNDS),
        ("14.09.2026", None, "start_date", None),
    ],
)
async def test_a_window_v2_refuses_is_refused_on_its_field_before_the_diary_is_asked(
    v2, v2_tokens, petersburg, start, end, field, error
) -> None:
    """Where v1 asked the diary for the pupil first and refused after, in its
    own words for its own field names."""
    token = v2_tokens["diary"]
    for name, (request, _path) in READS.items():
        sent = request(student_id=4021, start_date=start)
        if end is not None:
            sent.end_date = end
        refused = await v2.both(name, sent, token=token)
        assert (refused.status, refused.reason) == (400, "VALIDATION_FAILED"), name
        assert [violation for violation, _ in refused.violations] == [field]
        if error is not None:
            assert refused.error == error
    assert petersburg.seen == []


async def test_an_id_this_diary_does_not_list_reaches_none_of_its_days(
    v2, v2_tokens, petersburg
) -> None:
    """Another family's child's id, on the three reads of a window: refused
    as nobody's, and nothing but the session's own pupils is asked for."""
    for name, (request, path) in READS.items():
        refused = await v2.both(name, request(student_id=999), token=v2_tokens["diary"])
        assert (refused.status, refused.reason, refused.metadata) == (
            404,
            "RESOURCE_NOT_FOUND",
            {"resource": "student"},
        ), name
        v1 = await v2.http.get(
            f"/api/v1/diary/students/999/{path}", headers=_auth(v2_tokens["diary"])
        )
        assert (v1.status_code, v1.json()["detail"]) == (404, refused.error)
    assert {request.url.path for request in petersburg.seen} == {CHILDREN}


async def test_only_this_child_s_corrections_in_this_diary_are_laid_over(
    v2, v2_tokens, session, petersburg
) -> None:
    """Corrections are filed under the child and the diary: another child's,
    and the same id's on another diary's server, are nobody's business here."""
    target = "lesson:2026-09-14:n2:Алгебра"
    await _correct(session, target, "teacher", "чужой ребёнок", student_id=5)
    await _correct(
        session, target, "topic", "чужой дневник", scope="CHILD:netschool:region.zabedu.ru"
    )
    days = (
        await v2.both(
            "DiaryService/ListScheduleDays",
            ListScheduleDaysRequest(
                student_id=4021, start_date="2026-09-14", end_date="2026-09-14"
            ),
            token=v2_tokens["diary"],
        )
    ).message.schedule_days
    algebra = days[0].lessons[1]
    assert (algebra.teacher, algebra.topic, list(algebra.edits)) == (
        "Петрова А. И.",
        "Квадратные уравнения",
        [],
    )


async def test_the_windowed_reads_write_nothing_but_the_session_s_last_use(
    v2, v2_tokens, petersburg, statement_writes, unexpected_writes
) -> None:
    with statement_writes() as seen:
        for name, (request, _path) in READS.items():
            answer = await v2.both(name, request(student_id=4021), token=v2_tokens["diary"])
            assert answer.status == 200, name
    assert seen
    assert unexpected_writes(seen) == []
