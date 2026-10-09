"""The diary's reads without a window, and what the diary can do: ``ListStudents``,
``ListPeriods``, ``ListDiarySubjects``, ``ListTeachers``, ``ListTurnstileEvents``
and ``GetDiaryCapabilities``.

v1's ``/diary`` reads over v2, through ``services/diary``'s ``DiaryService``: the
pupil resolved from the session's own diary on every call, the same answer in
v2's shape, read against v1's own for the same session
(``docs/specs/2026-10-05-server-v2-design.md``, decisions 10, 12 and 14). What
v1 did not have: a feature the session's provider does not declare is
``FEATURE_UNSUPPORTED`` before the diary is asked anything, and the
capabilities say which features each provider has. Nothing here reaches a real
diary: Petersburg's pooled client is replaced by one over
``httpx.MockTransport``, and its clock is pinned to Monday 14 September 2026.
"""

from __future__ import annotations

from datetime import date
from typing import Any

import httpx
import pytest
from sqlalchemy import select

from app.config import get_settings
from app.contract.lessons.v2.diary_pb import (
    AttendanceDirection,
    DiaryFeature,
    ListDiarySubjectsRequest,
    ListPeriodsRequest,
    ListTeachersRequest,
    ListTurnstileEventsRequest,
    SignInMethod,
)
from app.crypto import seal
from app.db import SessionLocal
from app.models import DiarySession
from app.providers.petersburg import client as pbclient
from app.providers.petersburg import provider as pbprovider
from app.rpc.diary import NOT_IN_THIS_DIARY
from app.security import hash_token
from app.services import diary as diary_service

TODAY = date(2026, 9, 14)
CHILDREN = "/api/journal/person/related-child-list"
PERIODS = "/api/group/group/get-list-period"
SUBJECTS = "/api/journal/subject/list-studied"
TEACHERS = "/api/journal/teacher/list"
TURNSTILE = "/api/journal/acs/list"
CHILD = {
    "identity": {"id": 4021},
    "firstname": "Пётр",
    "surname": "Иванов",
    "middlename": "Сергеевич",
    "educations": [
        {
            "education_id": 90210,
            "group_id": 771,
            "group_name": "9А",
            "institution_name": "ГБОУ СОШ № 1",
        }
    ],
}
#: The reads of one pupil this file serves, each with the request it is asked.
READS = {
    "DiaryService/ListPeriods": ListPeriodsRequest,
    "DiaryService/ListDiarySubjects": ListDiarySubjectsRequest,
    "DiaryService/ListTeachers": ListTeachersRequest,
    "DiaryService/ListTurnstileEvents": ListTurnstileEventsRequest,
}


@pytest.fixture
def petersburg(monkeypatch, FakeUpstream):
    """Petersburg's upstream on 14 September 2026, answering one pupil and,
    for them, two quarters, a subject, a teacher and three passages."""
    fake = FakeUpstream(
        {
            CHILDREN: {"items": [CHILD]},
            PERIODS: {
                "items": [
                    {
                        "identity": {"id": 1},
                        "name": "1 четверть",
                        "date_from": "01.09.2026",
                        "date_to": "25.10.2026",
                    },
                    {
                        "identity": {"id": 2},
                        "name": "2 четверть",
                        "date_from": "05.11.2026",
                        "date_to": "28.12.2026",
                    },
                ]
            },
            SUBJECTS: {"items": [{"subject_id": 12, "subject_name": "Алгебра"}]},
            TEACHERS: {
                "items": [
                    {
                        "identity": {"id": 5},
                        "surname": "Петрова",
                        "firstname": "Анна",
                        "middlename": "Ивановна",
                        "position_name": "учитель математики",
                        "subjects": [{"subject_name": "Алгебра"}],
                    }
                ]
            },
            TURNSTILE: {
                "items": [
                    {"datetime": "13.09.2026 08:01:00", "direction": "Отказ"},
                    {"datetime": "14.09.2026 14:02:59", "direction": "Выход"},
                    {"datetime": "14.09.2026 08:15:00", "direction": "Вход"},
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


async def _netschool_session(session) -> str:
    """A live «Сетевой город» session, whose credential nothing here opens."""
    session.add(
        DiarySession(
            token_hash=hash_token("v2-netschool-diary"),
            upstream_token=seal('{"v": 1, "region": "zabaikalsky", "school_id": 42}'),
            login="ivanova",
            provider="netschool",
            region="zabaikalsky",
        )
    )
    await session.commit()
    return "v2-netschool-diary"


def _asked(fake, path: str) -> int:
    return sum(1 for request in fake.seen if request.url.path == path)


async def test_the_pupils_are_v1_s_in_v2_s_shape(v2, v2_tokens, petersburg) -> None:
    token = v2_tokens["diary"]
    answer = await v2.both("DiaryService/ListStudents", token=token)
    v1 = (await v2.http.get("/api/v1/diary/students", headers=_auth(token))).json()
    assert [student.to_json() for student in answer.message.students] == [
        '{"id":"4021","firstName":"Пётр","lastName":"Иванов","middleName":"Сергеевич",'
        '"fullName":"Иванов Пётр Сергеевич","school":"ГБОУ СОШ № 1","className":"9А"}'
    ]
    (pupil,) = answer.message.students
    assert [
        pupil.id,
        pupil.first_name,
        pupil.last_name,
        pupil.middle_name,
        pupil.full_name,
        pupil.school,
        pupil.class_name,
    ] == list(v1[0].values())
    assert answer.headers["cache-control"] == "private, no-store"


async def test_periods_teachers_and_the_turnstile_are_v1_s_in_v2_s_shape(
    v2, v2_tokens, petersburg
) -> None:
    token = v2_tokens["diary"]
    periods = (
        await v2.both("DiaryService/ListPeriods", ListPeriodsRequest(student_id=4021), token=token)
    ).message.periods
    v1_periods = (
        await v2.http.get("/api/v1/diary/students/4021/periods", headers=_auth(token))
    ).json()
    assert (
        [
            (period.id, period.name, period.starts_on, period.ends_on, period.is_current)
            for period in periods
        ]
        == [tuple(period.values()) for period in v1_periods]
        == [
            (1, "1 четверть", "2026-09-01", "2026-10-25", True),
            (2, "2 четверть", "2026-11-05", "2026-12-28", False),
        ]
    )

    teachers = (
        await v2.both(
            "DiaryService/ListTeachers", ListTeachersRequest(student_id=4021), token=token
        )
    ).message.teachers
    v1_teachers = (
        await v2.http.get("/api/v1/diary/students/4021/teachers", headers=_auth(token))
    ).json()
    assert (
        [
            (teacher.id, teacher.name, teacher.position, list(teacher.subjects))
            for teacher in teachers
        ]
        == [tuple(teacher.values()) for teacher in v1_teachers]
        == [(5, "Петрова Анна Ивановна", "учитель математики", ["Алгебра"])]
    )

    passages = (
        await v2.both(
            "DiaryService/ListTurnstileEvents",
            ListTurnstileEventsRequest(student_id=4021),
            token=token,
        )
    ).message.turnstile_events
    v1_passages = (
        await v2.http.get("/api/v1/diary/students/4021/attendance", headers=_auth(token))
    ).json()
    # Newest first, each on the diary's wall clock to the minute; a word the
    # diary uses for neither way is «unknown», never «out».
    assert [(event.at, event.direction) for event in passages] == [
        ("2026-09-14T14:02", AttendanceDirection.OUT),
        ("2026-09-14T08:15", AttendanceDirection.IN),
        ("2026-09-13T08:01", AttendanceDirection.UNKNOWN),
    ]
    assert [event["direction"] for event in v1_passages] == ["out", "in", "unknown"]
    assert [event["at"][:16] for event in v1_passages] == [event.at for event in passages]


async def test_subjects_are_the_current_period_s_unless_one_is_named(
    v2, v2_tokens, petersburg
) -> None:
    token = v2_tokens["diary"]
    current = await v2.rest(
        "DiaryService/ListDiarySubjects", ListDiarySubjectsRequest(student_id=4021), token=token
    )
    assert [(s.id, s.name) for s in current.message.subjects] == [(12, "Алгебра")]
    assert petersburg.query(SUBJECTS)["p_periods[]"] == "1"
    petersburg.seen.clear()
    named = await v2.connect(
        "DiaryService/ListDiarySubjects",
        ListDiarySubjectsRequest(student_id=4021, period_id=2),
        token=token,
    )
    assert [s.name for s in named.message.subjects] == ["Алгебра"]
    assert petersburg.query(SUBJECTS)["p_periods[]"] == "2"
    assert _asked(petersburg, PERIODS) == 0


async def test_an_id_this_diary_does_not_list_reaches_nothing(v2, v2_tokens, petersburg) -> None:
    """Another family's child's id is no different from nobody's: v1's 404 in
    v1's words, asked of the session's own diary on every call, and nothing of
    that child is asked for."""
    token = v2_tokens["diary"]
    for name, request in READS.items():
        refused = await v2.both(name, request(student_id=999), token=token)
        assert (refused.status, refused.code, refused.reason, refused.metadata, refused.error) == (
            404,
            "NOT_FOUND",
            "RESOURCE_NOT_FOUND",
            {"resource": "student"},
            "Unknown student",
        ), name
    v1 = await v2.http.get("/api/v1/diary/students/999/periods", headers=_auth(token))
    assert (v1.status_code, v1.json()["detail"]) == (404, "Unknown student")
    assert {request.url.path for request in petersburg.seen} == {CHILDREN}


async def test_a_session_of_a_provider_this_deployment_does_not_know_is_the_gate_s_to_refuse(
    v2, session, diary_offline
) -> None:
    """#389, over v2: a session a later release opened with a diary this one
    does not know is refused as an unknown token, before any diary is asked,
    and left for the release that can read it."""
    session.add(
        DiarySession(
            token_hash=hash_token("third-diary"),
            upstream_token=seal("third-diary-cookie"),
            login="parent",
            provider="dnevnik-ru",
        )
    )
    await session.commit()
    refused = await v2.both("DiaryService/ListStudents", token="third-diary")
    assert (refused.status, refused.reason, refused.error) == (
        401,
        "DIARY_TOKEN_INVALID",
        "Diary session is not valid",
    )
    assert diary_offline == []
    async with SessionLocal() as fresh:
        assert (await fresh.scalar(select(DiarySession))).expired_at is None


@pytest.mark.parametrize(
    ("name", "feature"),
    [
        ("DiaryService/ListDiarySubjects", "DIARY_FEATURE_SUBJECTS"),
        ("DiaryService/ListTeachers", "DIARY_FEATURE_TEACHERS"),
        ("DiaryService/ListTurnstileEvents", "DIARY_FEATURE_TURNSTILE"),
    ],
)
async def test_a_netschool_session_is_refused_what_its_diary_never_has_before_asking_it(
    v2, session, diary_offline, name, feature
) -> None:
    """Ruling 104: «Сетевой город» never implemented these, and v1 answered
    each with an empty list a client could not tell from an empty diary. The
    refusal comes before the diary is asked for the pupils, or anything."""
    token = await _netschool_session(session)
    refused = await v2.both(name, READS[name](student_id=11), token=token)
    assert (refused.status, refused.code, refused.reason, refused.metadata, refused.error) == (
        501,
        "UNIMPLEMENTED",
        "FEATURE_UNSUPPORTED",
        {"feature": feature},
        NOT_IN_THIS_DIARY,
    )
    assert diary_offline == []


async def test_a_session_the_diary_ended_is_reauth_and_stays_ended(
    v2, v2_tokens, petersburg, session
) -> None:
    """«Sign in again», and the row stays expired though the call is refused
    (decision 4): the next call, on either transport, is the gate's to refuse."""
    petersburg.routes[CHILDREN] = lambda request: httpx.Response(401, json={})
    token = v2_tokens["diary"]
    ended = await v2.rest("DiaryService/ListStudents", token=token)
    assert (ended.status, ended.code, ended.reason, ended.error) == (
        401,
        "UNAUTHENTICATED",
        "DIARY_REAUTH",
        "Сессия дневника истекла — войдите заново",
    )
    async with SessionLocal() as fresh:
        row = await fresh.scalar(select(DiarySession))
        assert row.expired_at is not None
    again = await v2.connect("DiaryService/ListStudents", token=token)
    assert (again.reason, again.error) == ("DIARY_TOKEN_INVALID", "Diary session is not valid")
    assert _asked(petersburg, CHILDREN) == 1


async def test_a_credential_the_diary_rotated_is_kept_even_when_the_read_is_refused(
    v2, v2_tokens, petersburg
) -> None:
    """Decision 4: the answer that could not be read carried a new session,
    and replaying the old one next call would sign the family out."""

    def rotated(request: httpx.Request) -> httpx.Response:
        answer = httpx.Response(200, json={"unexpected": True})
        answer.headers["set-cookie"] = "X-JWT-Token=a.rotated.token; Path=/"
        return answer

    petersburg.routes[CHILDREN] = rotated
    refused = await v2.rest("DiaryService/ListStudents", token=v2_tokens["diary"])
    assert (refused.status, refused.reason) == (503, "DIARY_UPSTREAM_UNREADABLE")
    async with SessionLocal() as fresh:
        row = await fresh.scalar(select(DiarySession))
    assert diary_service.upstream_of(row) == "a.rotated.token"
    assert row.expired_at is None


async def test_the_reads_write_nothing_but_the_session_s_last_use(
    v2, v2_tokens, petersburg, statement_writes, unexpected_writes
) -> None:
    token = v2_tokens["diary"]
    requests: dict[str, Any] = {"DiaryService/ListStudents": None} | {
        name: request(student_id=4021) for name, request in READS.items()
    }
    with statement_writes() as seen:
        for name, request in requests.items():
            answer = await v2.both(name, request, token=token)
            assert answer.status == 200, name
    assert seen, "the session's last use is written once in fifteen minutes"
    assert unexpected_writes(seen) == []


async def test_capabilities_name_each_provider_s_ways_in_and_data_from_its_row(v2) -> None:
    """Decision 12: what sub-project 5 waits for before it moves the diary. A
    feature a provider never implemented is not listed (Ruling 104)."""
    answer = await v2.both("DiaryService/GetDiaryCapabilities")
    providers = {p.provider: p for p in answer.message.capabilities.providers}
    assert list(providers["petersburg"].sign_in_methods) == [SignInMethod.PASSWORD]
    assert list(providers["netschool"].sign_in_methods) == [SignInMethod.PASSWORD]
    assert list(providers["petersburg"].features) == [
        DiaryFeature.SCHEDULE,
        DiaryFeature.HOMEWORK,
        DiaryFeature.MARKS,
        DiaryFeature.PERIODS,
        DiaryFeature.SUBJECTS,
        DiaryFeature.TEACHERS,
        DiaryFeature.TURNSTILE,
    ]
    assert list(providers["netschool"].features) == [
        DiaryFeature.SCHEDULE,
        DiaryFeature.HOMEWORK,
        DiaryFeature.MARKS,
        DiaryFeature.PERIODS,
    ]
    # In JSON, a feature is its value's name, which a client decodes leniently.
    assert '"features":["DIARY_FEATURE_SCHEDULE",' in answer.body.decode()


async def test_with_the_diary_off_no_provider_offers_a_way_in_or_any_data(v2, monkeypatch) -> None:
    """Ruling 105: as before 3b-7, with ``enabled`` false; the regions stay
    v1's answer either way."""
    monkeypatch.setattr(get_settings(), "diary_secret", "", raising=False)
    answer = await v2.both("DiaryService/GetDiaryCapabilities")
    capabilities = answer.message.capabilities
    v1 = (await v2.http.get("/api/v1/diary/capabilities")).json()
    assert capabilities.enabled is v1["enabled"] is False
    assert all(not p.sign_in_methods and not p.features for p in capabilities.providers)
    providers = {p.provider: list(p.regions) for p in capabilities.providers}
    assert providers["netschool"] == v1["providers"]["netschool"]["regions"]
