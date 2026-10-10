"""``CreateDiarySession`` and ``DeleteDiarySession``: v1's ``/session`` and ``/logout`` over v2.

Through ``services/diary.register``, so both versions keep the same sessions,
refuse the same ones, count the same attempts on one budget, and answer in
words both share (``docs/specs/2026-10-05-server-v2-design.md``, decisions 5,
11 and 14). A create is not idempotent, so its success is asked once per
transport (the 3b plan, Ruling 17); its refusals, which keep nothing, through
``both``. Nothing here reaches a real diary: Petersburg's pooled client and
«Сетевой город»'s are each replaced by one over ``httpx.MockTransport``.
"""

from __future__ import annotations

from typing import Any

import httpx
import pytest
from protobuf import Oneof
from sqlalchemy import func, select

from app import wording
from app.config import get_settings
from app.contract.lessons.v2.diary_pb import (
    CreateDiarySessionRequest,
    DeleteDiarySessionResponse,
    DiaryStudent,
    NetSchoolCookies,
    NetSchoolCredential,
    PetersburgCredential,
)
from app.db import SessionLocal
from app.models import DiarySession, JoinAttempt
from app.providers.diary import http as diary_http
from app.providers.diary.errors import AddressRefused, SignInUnsupported
from app.providers.netschool import client as nsclient
from app.providers.petersburg import client as pbclient
from app.providers.petersburg.provider import PetersburgProvider
from app.security import diary_login_limiter, diary_open_limiter, hash_token
from app.services import diary as diary_service

CREATE = "DiaryService/CreateDiarySession"
DELETE = "DiaryService/DeleteDiarySession"
JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MDIxIn0.c2lnbmF0dXJlLWZyb20tdGhlLXBob25l"
CHILDREN_PATH = "/api/journal/person/related-child-list"
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


@pytest.fixture
def petersburg(monkeypatch, FakeUpstream):
    """Petersburg's upstream, answering the pupils by default."""
    fake = FakeUpstream({CHILDREN_PATH: {"items": [CHILD]}})

    async def shared() -> httpx.AsyncClient:
        return httpx.AsyncClient(
            base_url=pbclient.BASE_URL, transport=httpx.MockTransport(fake.handler)
        )

    monkeypatch.setattr(pbclient, "shared_client", shared)
    return fake


@pytest.fixture
def netschool(monkeypatch, FakeUpstream):
    """«Сетевой город»'s regional server: the bootstrap's four answers."""
    fake = FakeUpstream(
        {
            "/webapi/student/diary/init": {
                "students": [{"studentId": 11, "nickName": "Иванов Иван", "classId": 3}]
            },
            "/webapi/years/current": {
                "id": 2026,
                "startDate": "2026-09-01",
                "endDate": "2027-05-31",
            },
            "/webapi/context": {"organizationName": "Гимназия № 7"},
            "/webapi/grade/assignment/types": [{"id": 3, "name": "Домашнее задание"}],
        }
    )

    def handler(request: httpx.Request) -> httpx.Response:
        # «Сетевой город» answers bare JSON, not Petersburg's {"data": …}.
        fake.seen.append(request)
        route = fake.routes.get(request.url.path)
        if isinstance(route, httpx.Response):
            return route
        if route is None:
            return httpx.Response(404, json={})
        return httpx.Response(200, json=route)

    async def shared() -> httpx.AsyncClient:
        return httpx.AsyncClient(
            transport=httpx.MockTransport(handler), cookies=diary_http.NoCookieJar()
        )

    monkeypatch.setattr(nsclient, "shared_client", shared)
    return fake


def _petersburg(**over: Any) -> CreateDiarySessionRequest:
    fields: dict[str, Any] = {
        "login": "parent@example.com",
        "credential": Oneof("petersburg", PetersburgCredential(token=JWT)),
    }
    return CreateDiarySessionRequest(**(fields | over))


def _netschool(**over: Any) -> CreateDiarySessionRequest:
    credential = NetSchoolCredential(
        at="56574745368264517434263",
        cookies=NetSchoolCookies(ns_session_id="sess-phone", esrn_sec="esrn-phone"),
        ver="639",
        time_out=900000,
    )
    fields: dict[str, Any] = {
        "login": "ivanova",
        "credential": Oneof("netschool", credential),
        "region": "zabaikalsky",
        "school_id": 42,
    }
    return CreateDiarySessionRequest(**(fields | over))


def _v1_body(request: CreateDiarySessionRequest) -> dict[str, Any]:
    """The same session as v1's ``/session`` takes it."""
    chosen = request.credential
    assert chosen is not None
    if chosen.field == "petersburg":
        return {
            "provider": "petersburg",
            "login": request.login,
            "credential": {"token": chosen.value.token},
        }
    return {
        "provider": "netschool",
        "login": request.login,
        "region": request.region,
        "school_id": request.school_id,
        "credential": {
            "at": chosen.value.at,
            "cookies": {
                "NSSESSIONID": chosen.value.cookies.ns_session_id,
                "ESRNSec": chosen.value.cookies.esrn_sec,
            },
            "ver": chosen.value.ver,
            "time_out": chosen.value.time_out,
        },
    }


async def _attempts() -> int:
    async with SessionLocal() as fresh:
        return await fresh.scalar(select(func.count()).select_from(JoinAttempt)) or 0


async def _rows() -> list[DiarySession]:
    async with SessionLocal() as fresh:
        return list(await fresh.scalars(select(DiarySession).order_by(DiarySession.id)))


async def test_a_petersburg_session_is_kept_and_answered_as_v1_answers_it(v2, petersburg) -> None:
    rest = await v2.rest(CREATE, _petersburg())
    connect = await v2.connect(CREATE, _petersburg())
    v1 = await v2.http.post("/api/v1/diary/session", json=_v1_body(_petersburg()))
    assert (rest.status, connect.status, v1.status_code) == (201, 200, 200)
    # A diary token is a credential: never kept by a cache. Connect's answers
    # carry no such header yet (#357).
    assert rest.headers["cache-control"] == "private, no-store"
    expected = v1.json()
    for answer in (rest, connect):
        made = answer.message.session
        assert made.token and made.token != JWT
        assert (made.login, made.provider, made.zone) == (
            expected["login"],
            expected["provider"],
            expected["zone"],
        )
        assert not made.has_field("region") and not made.has_field("school_id")
        assert not made.has_field("school_name")
        assert list(made.students) == [
            DiaryStudent(
                id=4021,
                first_name="Пётр",
                last_name="Иванов",
                full_name="Иванов Пётр",
                school="ГБОУ СОШ № 1",
                class_name="9А",
            )
        ]
    assert [s["full_name"] for s in expected["students"]] == ["Иванов Пётр"]
    rows = await _rows()
    assert len(rows) == 3
    assert {row.token_hash for row in rows} == {
        hash_token(rest.message.session.token),
        hash_token(connect.message.session.token),
        hash_token(expected["token"]),
    }
    # Sealed, not stored as it came; and an opened session is no failure.
    assert all(diary_service.upstream_of(row) == JWT != row.upstream_token for row in rows)
    assert await _attempts() == 3


async def test_a_netschool_session_names_its_region_its_school_and_its_zone(v2, netschool) -> None:
    rest = await v2.rest(CREATE, _netschool())
    v1 = (await v2.http.post("/api/v1/diary/session", json=_v1_body(_netschool()))).json()
    made = rest.message.session
    assert (
        (made.provider, made.region, made.school_id, made.school_name, made.zone)
        == (
            v1["provider"],
            v1["region"],
            v1["school_id"],
            v1["school_name"],
            v1["zone"],
        )
        == ("netschool", "zabaikalsky", 42, "Гимназия № 7", "Asia/Chita")
    )
    assert [student.id for student in made.students] == [s["id"] for s in v1["students"]] == [11]
    # The phone's cookies went upstream as one header each time, from here.
    assert all("NSSESSIONID=" in request.headers["cookie"] for request in netschool.seen)


async def test_the_session_handed_over_is_never_echoed(v2, petersburg) -> None:
    created = await v2.rest(CREATE, _petersburg())
    assert JWT not in created.body.decode()
    petersburg.routes[CHILDREN_PATH] = lambda request: httpx.Response(401, json={})
    refused = await v2.both(CREATE, _petersburg())
    assert JWT not in refused.body.decode()


async def test_a_request_v1_would_refuse_is_refused_on_v2_s_own_fields(v2, petersburg) -> None:
    """v1's own schema decides, so the two versions refuse the same sessions —
    a token that is not a JWT, a cookie that would smuggle a second one, a
    region beside Petersburg's credential — before anything is counted or
    sent, naming the field as v2 spells it and never what was sent."""
    smuggled = NetSchoolCredential(
        at="56574745368264517434263", cookies=NetSchoolCookies(ns_session_id="a;b=c")
    )
    for request, field in (
        (CreateDiarySessionRequest(login="parent@example.com"), "credential"),
        (
            _petersburg(credential=Oneof("petersburg", PetersburgCredential(token="x" * 20))),
            "petersburg.token",
        ),
        (_petersburg(login=" ​ "), "login"),
        (_petersburg(region="zabaikalsky"), "region"),
        (_netschool(credential=Oneof("netschool", smuggled)), "netschool.cookies.ns_session_id"),
        (_netschool(school_id=None), "school_id"),
    ):
        refused = await v2.both(CREATE, request)
        assert (refused.status, refused.reason) == (400, "VALIDATION_FAILED"), field
        assert field in [name for name, _ in refused.violations], refused.violations
        assert "a;b=c" not in refused.body.decode()
    assert petersburg.seen == []
    assert await _attempts() == 0


async def test_a_region_this_server_does_not_serve_is_refused_before_anything_is_counted(
    v2, netschool
) -> None:
    """Off the allow-list, or one that takes only Госуслуги: refused in v1's
    words, on ``region``, and nothing upstream ever hears of it."""
    for region in ("tula", "moscow"):
        refused = await v2.both(CREATE, _netschool(region=region))
        v1 = await v2.http.post("/api/v1/diary/session", json=_v1_body(_netschool(region=region)))
        assert (refused.status, refused.reason, refused.error) == (
            400,
            "VALIDATION_FAILED",
            v1.json()["detail"],
        )
        assert refused.error == wording.DIARY_REGION_NOT_SERVED_DETAIL
        assert refused.violations == [("region", wording.DIARY_REGION_NOT_SERVED_DETAIL)]
    assert netschool.seen == []
    assert await _attempts() == 0


async def test_a_session_the_diary_will_not_take_from_here_is_rejected_and_counted(
    v2, petersburg, netschool
) -> None:
    """Not «sign in again»: the session was good on the phone seconds ago, so
    asking for the password would go round the same loop. Counted, because it
    is also what a replay of a session that was never real looks like."""
    petersburg.routes[CHILDREN_PATH] = lambda request: httpx.Response(401, json={})
    netschool.routes["/webapi/student/diary/init"] = httpx.Response(401, json={})
    for request in (_petersburg(), _netschool()):
        refused = await v2.both(CREATE, request)
        v1 = await v2.http.post("/api/v1/diary/session", json=_v1_body(request))
        assert v1.status_code == 409
        assert (refused.status, refused.code, refused.reason, refused.error) == (
            403,
            "PERMISSION_DENIED",
            "DIARY_CREDENTIALS_REJECTED",
            v1.json()["detail"],
        )
        assert refused.metadata == {}
    assert await _attempts() == 6
    assert await _rows() == []


async def test_an_account_with_no_pupil_is_refused_and_counted(v2, petersburg) -> None:
    petersburg.routes[CHILDREN_PATH] = {"items": []}
    refused = await v2.both(CREATE, _petersburg())
    v1 = await v2.http.post("/api/v1/diary/session", json=_v1_body(_petersburg()))
    assert v1.status_code == 403
    assert (refused.status, refused.reason, refused.error) == (
        403,
        "DIARY_NO_STUDENTS",
        v1.json()["detail"],
    )
    assert await _attempts() == 3


async def test_a_diary_that_does_not_answer_is_unavailable_and_not_counted(
    v2, petersburg, netschool
) -> None:
    """Nothing judged the session: forgiven, and said apart from a region that
    refuses this server's address, which is not worth retrying."""
    petersburg.routes[CHILDREN_PATH] = lambda request: httpx.Response(503, json={})
    down = await v2.both(CREATE, _petersburg())
    assert (down.status, down.reason, down.metadata) == (
        503,
        "DIARY_UNAVAILABLE",
        {"upstream": "upstream"},
    )
    netschool.routes["/webapi/student/diary/init"] = httpx.Response(403, text="blocked")
    refused = await v2.both(CREATE, _netschool())
    assert (refused.status, refused.reason, refused.metadata, refused.error) == (
        503,
        "DIARY_UNAVAILABLE",
        {"upstream": "address-refused"},
        AddressRefused.message,
    )
    assert await _attempts() == 0


async def test_an_answer_nobody_can_read_is_unreadable_and_counted(v2, petersburg) -> None:
    """A 200 that is not the diary's envelope, as a captcha or an outage page
    would be in another dress. Counted: a login form answers a wrong guess
    with the same bytes."""
    petersburg.routes[CHILDREN_PATH] = lambda request: httpx.Response(
        200, json={"message": "Pa55w0rd-s3cr3t-Hunter2"}
    )
    refused = await v2.both(CREATE, _petersburg())
    v1 = await v2.http.post("/api/v1/diary/session", json=_v1_body(_petersburg()))
    assert v1.status_code == 502
    assert (refused.status, refused.code, refused.reason) == (
        503,
        "UNAVAILABLE",
        "DIARY_UPSTREAM_UNREADABLE",
    )
    # The class's sentence, never the upstream's own message.
    assert refused.error == "Электронный дневник ответил непонятно"
    assert "Hunter2" not in refused.body.decode()
    assert await _attempts() == 3


async def test_a_failure_no_row_names_is_unreadable_and_counted(v2, monkeypatch) -> None:
    """A member of the diary's family the table has no row for — here the
    password sign-in's own refusal, which no v2 method can meet — is worded by
    the family's fallback, and counted, as v1's ``/session`` counts it."""

    async def refuses(self, request):
        raise SignInUnsupported

    monkeypatch.setattr(PetersburgProvider, "adopt", refuses)
    refused = await v2.both(CREATE, _petersburg())
    v1 = await v2.http.post("/api/v1/diary/session", json=_v1_body(_petersburg()))
    assert v1.status_code == 502
    assert (refused.reason, refused.error) == (
        "DIARY_UPSTREAM_UNREADABLE",
        SignInUnsupported.message,
    )
    assert await _attempts() == 3


async def test_v1_and_v2_draw_on_one_budget(v2, petersburg) -> None:
    """Decision 11: a caller alternating the versions gets ten guesses in a
    quarter of an hour, not twenty."""
    petersburg.routes[CHILDREN_PATH] = lambda request: httpx.Response(401, json={})
    for attempt in range(diary_login_limiter.limit):
        if attempt % 2:
            answer = await v2.http.post("/api/v1/diary/session", json=_v1_body(_petersburg()))
            assert answer.status_code == 409
        else:
            assert (await v2.rest(CREATE, _petersburg())).reason == "DIARY_CREDENTIALS_REJECTED"
    # Each transport on its own, not `both`: the seconds left are counted at
    # each call, and two calls a millisecond apart may straddle a second.
    rest = await v2.rest(CREATE, _petersburg())
    connect = await v2.connect(CREATE, _petersburg())
    for refused in (rest, connect):
        assert (refused.code, refused.reason, refused.error) == (
            "RESOURCE_EXHAUSTED",
            "THROTTLED",
            wording.DIARY_THROTTLED_DETAIL,
        )
        seconds = int(refused.metadata["retry_after_seconds"])
        assert seconds >= 1 and refused.retry_seconds == seconds
    assert rest.status == 429
    assert rest.headers["retry-after"] == rest.metadata["retry_after_seconds"]
    v1 = await v2.http.post("/api/v1/diary/session", json=_v1_body(_petersburg()))
    assert (v1.status_code, v1.json()["detail"]) == (429, wording.DIARY_THROTTLED_DETAIL)
    assert len(petersburg.seen) == diary_login_limiter.limit


async def test_v1_and_v2_draw_on_one_budget_for_opened_sessions(v2, petersburg) -> None:
    """The ``diary-open:`` bucket is the same spelling in `api/diary.py` and
    `rpc/diary.py` (decision 11): a caller alternating versions gets twenty
    opened sessions in a quarter of an hour, not forty. Mirrors
    `test_v1_and_v2_draw_on_one_budget` above, which holds the failures'
    bucket the same way — before this, only the failures' spelling was held
    by a test, so a typo in the opened one would have doubled this budget
    silently."""
    for attempt in range(diary_open_limiter.limit):
        if attempt % 2:
            answer = await v2.http.post("/api/v1/diary/session", json=_v1_body(_petersburg()))
            assert answer.status_code == 200
        else:
            assert (await v2.rest(CREATE, _petersburg())).status == 201
    # Each transport on its own, as above: the seconds left are counted at
    # each call, and two calls a millisecond apart may straddle a second.
    rest = await v2.rest(CREATE, _petersburg())
    connect = await v2.connect(CREATE, _petersburg())
    for refused in (rest, connect):
        assert (refused.code, refused.reason, refused.error) == (
            "RESOURCE_EXHAUSTED",
            "THROTTLED",
            wording.DIARY_THROTTLED_DETAIL,
        )
    v1 = await v2.http.post("/api/v1/diary/session", json=_v1_body(_petersburg()))
    assert (v1.status_code, v1.json()["detail"]) == (429, wording.DIARY_THROTTLED_DETAIL)
    assert len(petersburg.seen) == diary_open_limiter.limit


async def test_signing_out_ends_the_token_and_the_session(v2, petersburg) -> None:
    """Each transport signs out a session of its own: the second call with the
    same token is the gate's to refuse."""
    tokens = [(await v2.rest(CREATE, _petersburg())).message.session.token for _ in range(3)]
    rest = await v2.rest(DELETE, token=tokens[0])
    connect = await v2.connect(DELETE, token=tokens[1])
    assert (rest.status, connect.status) == (200, 200)
    assert rest.message == connect.message == DeleteDiarySessionResponse()
    again = await v2.both(DELETE, token=tokens[0])
    assert (again.status, again.reason, again.error) == (
        401,
        "DIARY_TOKEN_INVALID",
        "Diary session is not valid",
    )
    rows = await _rows()
    assert [row.token_hash for row in rows] == [hash_token(tokens[2])]
    # v1 reads the session v2 left, and no other.
    v1 = await v2.http.get(
        "/api/v1/diary/students", headers={"Authorization": f"Bearer {tokens[0]}"}
    )
    assert v1.status_code == 401


async def test_without_the_secret_the_diary_is_disabled_on_both_methods(
    v2, v2_tokens, petersburg, monkeypatch
) -> None:
    """Nothing anybody types will help: said in v1's words, before any token is
    read or anything is counted or sent, and no session is touched (#302)."""
    monkeypatch.setattr(get_settings(), "diary_secret", "", raising=False)
    created = await v2.both(CREATE, _petersburg())
    v1 = await v2.http.post("/api/v1/diary/session", json=_v1_body(_petersburg()))
    deleted = await v2.both(DELETE, token=v2_tokens["diary"])
    for refused in (created, deleted):
        assert (refused.status, refused.code, refused.reason, refused.error) == (
            503,
            "UNAVAILABLE",
            "DIARY_DISABLED",
            v1.json()["detail"],
        )
    assert v1.headers["x-diary-unavailable"] == "disabled"
    assert petersburg.seen == []
    assert await _attempts() == 0
    (row,) = await _rows()
    assert row.expired_at is None
