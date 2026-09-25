"""Registering a session the phone opened: ``POST /api/v1/diary/session``.

The phone signs in with the diary itself, over its own connection, so the
password never reaches this server; it hands over the session the diary gave
it, once, and forgets it. What this file pins is what the server does with
that hand-over:

- it is **checked** with a read of this server's own, from this server's
  address, before anything is kept — and what the read hands back (a rotated
  token, the pupils, the school) is what is kept and answered;
- nothing in the body that is not a session is accepted — not a password, not
  a cookie that would smuggle a second one, not a region outside the
  allow-list — and a refusal never repeats what was sent;
- the failures that say something about the session are counted against the
  same limit as ``/login``'s wrong passwords, and the ones that say nothing
  about it are not;
- ``/login`` itself is unchanged, bar the header every diary 503 now carries.

Nothing here reaches a real diary. Petersburg's pooled client and «Сетевой
город»'s are each replaced by one over ``httpx.MockTransport``.
"""

from __future__ import annotations

import json
from datetime import UTC, date, datetime

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import select

from app.api.diary import diary_login_limiter
from app.config import get_settings
from app.main import app
from app.models import DiarySession, JoinAttempt, SchoolClass
from app.providers.diary import http as diary_http
from app.providers.netschool import client as nsclient
from app.providers.netschool import regions
from app.providers.petersburg import client as pbclient
from app.services import diary as service

#: A Petersburg session as the phone would hand it over: three base64url parts.
JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MDIxIn0.c2lnbmF0dXJlLWZyb20tdGhlLXBob25l"
ROTATED = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MDIxIn0.cm90YXRlZC1ieS10aGUtcmVhZA"
CHILDREN_PATH = "/api/journal/person/related-child-list"
SCHEDULE_PATH = "/api/journal/schedule/list-by-education"

CHILD = {
    "identity": {"id": 4021},
    "firstname": "Пётр",
    "surname": "Иванов",
    "educations": [
        {"education_id": 90210, "group_id": 771, "group_name": "9А",
         "institution_name": "ГБОУ СОШ № 1"}
    ],
}


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest.fixture
async def petersburg(monkeypatch, FakeUpstream):
    """Petersburg's upstream, answering the pupils by default."""
    fake = FakeUpstream({CHILDREN_PATH: {"items": [CHILD]}})

    async def shared():
        return httpx.AsyncClient(
            base_url=pbclient.BASE_URL, transport=httpx.MockTransport(fake.handler)
        )

    monkeypatch.setattr(pbclient, "shared_client", shared)
    return fake


class _NetSchoolUpstream:
    """«Сетевой город»'s regional server: the bootstrap's four answers, any of
    which a test replaces, and every request it was sent."""

    def __init__(self) -> None:
        self.seen: list[httpx.Request] = []
        self.routes: dict[str, httpx.Response | object] = {
            "/webapi/student/diary/init": {"students": [
                {"studentId": 11, "nickName": "Иванов Иван", "classId": 3}]},
            "/webapi/years/current": {"id": 2026, "startDate": "2026-09-01",
                                      "endDate": "2027-05-31"},
            "/webapi/context": {"organizationName": "Гимназия № 7"},
            "/webapi/grade/assignment/types": [{"id": 3, "name": "Домашнее задание"}],
        }

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.seen.append(request)
        route = self.routes.get(request.url.path)
        if isinstance(route, httpx.Response):
            return route
        if route is None:
            return httpx.Response(404, json={})
        return httpx.Response(200, json=route)


@pytest.fixture
async def netschool(monkeypatch):
    fake = _NetSchoolUpstream()

    async def shared():
        return httpx.AsyncClient(
            transport=httpx.MockTransport(fake.handler), cookies=diary_http.NoCookieJar()
        )

    monkeypatch.setattr(nsclient, "shared_client", shared)
    return fake


@pytest.fixture
def no_diary_secret(monkeypatch):
    """A deployment without ``DIARY_SECRET``; see ``test_diary_api``'s twin."""
    monkeypatch.setattr(get_settings(), "diary_secret", "", raising=False)
    yield
    get_settings.cache_clear()


def _petersburg_body(**over) -> dict:
    return {"provider": "petersburg", "login": "parent@example.com",
            "credential": {"token": JWT}} | over


def _netschool_body(**over) -> dict:
    return {
        "provider": "netschool",
        "login": "ivanova",
        "region": "zabaikalsky",
        "school_id": 42,
        "credential": {
            "at": "56574745368264517434263",
            "cookies": {"NSSESSIONID": "sess-phone", "ESRNSec": "esrn-phone"},
            "ver": "639",
            "time_out": 900000,
        },
    } | over


async def _register(client, body: dict) -> httpx.Response:
    return await client.post("/api/v1/diary/session", json=body)


async def _attempts(session) -> int:
    return len(list(await session.scalars(select(JoinAttempt))))


# --------------------------------------------------------------------------
# What is kept
# --------------------------------------------------------------------------


async def test_a_petersburg_session_is_validated_upstream_once_and_sealed(
    client, session, petersburg
):
    response = await _register(client, _petersburg_body())

    assert response.status_code == 200, response.text
    # One read, with the phone's token as the session cookie, from here.
    (call,) = petersburg.seen
    assert call.url.path == CHILDREN_PATH
    assert f"X-JWT-Token={JWT}" in call.headers["cookie"]

    row = await session.scalar(select(DiarySession))
    assert row.upstream_token != JWT  # sealed, not stored as it came
    assert service.upstream_of(row) == JWT
    assert row.provider == "petersburg" and row.region is None
    assert row.login == "parent@example.com"
    assert row.upstream_ok_at is not None


async def test_the_token_handed_back_is_ours_and_the_upstream_one_is_never_echoed(
    client, session, petersburg
):
    response = await _register(client, _petersburg_body())

    body = response.json()
    assert JWT not in response.text
    assert set(body) == {
        "token", "login", "provider", "region", "school_id", "school_name", "zone", "students",
    }
    row = await session.scalar(select(DiarySession))
    from app.security import hash_token

    assert row.token_hash == hash_token(body["token"])


async def test_a_refreshed_token_from_the_validating_call_is_what_is_kept(
    client, session, petersburg
):
    def rotating(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"data": {"items": [CHILD]}},
            headers={"set-cookie": f"X-JWT-Token={ROTATED}; Path=/"},
        )

    petersburg.routes[CHILDREN_PATH] = rotating
    response = await _register(client, _petersburg_body())

    assert response.status_code == 200, response.text
    row = await session.scalar(select(DiarySession))
    assert service.upstream_of(row) == ROTATED


async def test_a_netschool_session_is_bootstrapped_before_it_is_kept(client, session, netschool):
    response = await _register(client, _netschool_body())

    assert response.status_code == 200, response.text
    # The four bootstrap calls, in order, all to the allow-listed origin and
    # none with a password: there is none.
    assert [r.url.path for r in netschool.seen] == [
        "/webapi/student/diary/init",
        "/webapi/years/current",
        "/webapi/context",
        "/webapi/grade/assignment/types",
    ]
    origin = httpx.URL(regions.get("zabaikalsky").origin)
    assert {(r.url.scheme, r.url.host) for r in netschool.seen} == {(origin.scheme, origin.host)}
    assert all(r.headers["at"] == "56574745368264517434263" for r in netschool.seen)
    assert all(
        r.headers["cookie"] == "NSSESSIONID=sess-phone; ESRNSec=esrn-phone"
        for r in netschool.seen
    )

    row = await session.scalar(select(DiarySession))
    assert row.provider == "netschool" and row.region == "zabaikalsky"
    sealed = json.loads(service.upstream_of(row))
    assert sealed["year_id"] == 2026
    assert sealed["types"] == {"3": "Домашнее задание"}
    assert sealed["school"] == "Гимназия № 7"
    assert sealed["school_id"] == 42
    assert sealed["ver"] == "639" and sealed["time_out"] == 900000

    body = response.json()
    assert body["provider"] == "netschool"
    assert body["region"] == "zabaikalsky"
    assert body["school_id"] == 42
    assert body["school_name"] == "Гимназия № 7"
    assert body["zone"] == "Asia/Chita"
    assert "sess-phone" not in response.text and "56574745368264517434263" not in response.text


async def test_the_students_come_back_with_the_token(client, petersburg, netschool):
    first = (await _register(client, _petersburg_body())).json()
    assert first["students"] == [{
        "id": 4021, "first_name": "Пётр", "last_name": "Иванов", "middle_name": None,
        "full_name": "Иванов Пётр", "school": "ГБОУ СОШ № 1", "class_name": "9А",
    }]
    second = (await _register(client, _netschool_body())).json()
    assert [s["id"] for s in second["students"]] == [11]
    assert second["students"][0]["school"] == "Гимназия № 7"


# --------------------------------------------------------------------------
# What is refused before anything goes upstream
# --------------------------------------------------------------------------


async def test_a_region_outside_the_allow_list_is_422_with_no_call(client, session, netschool):
    response = await _register(client, _netschool_body(region="atlantis"))

    assert response.status_code == 422
    assert netschool.seen == []
    assert await _attempts(session) == 0


async def test_an_esia_only_region_is_422_with_no_call(client, session, netschool):
    """altai-krai, primorye and tula let families in through Госуслуги only; no
    session the phone could open with a password is one of theirs."""
    for region in ("altai-krai", "primorye", "tula"):
        response = await _register(client, _netschool_body(region=region))
        assert response.status_code == 422, region
    assert netschool.seen == []
    assert await _attempts(session) == 0


async def test_a_password_in_the_body_is_refused(client, session, petersburg, netschool):
    """No door on this endpoint takes a password, by mistake or otherwise."""
    for body in (
        _petersburg_body(password="hunter2"),
        _netschool_body(password="hunter2"),
        _petersburg_body(credential={"token": JWT, "password": "hunter2"}),
    ):
        response = await _register(client, body)
        assert response.status_code == 422, body
        assert "hunter2" not in response.text
    assert petersburg.seen == [] and netschool.seen == []
    assert await session.scalar(select(DiarySession)) is None


async def test_the_body_is_discriminated_by_provider(client, petersburg, netschool):
    """Each provider's body is its own shape: Petersburg's carries no region,
    «Сетевой город»'s must carry one and a school, and a provider nobody
    implements is not a body at all."""
    refused = [
        _petersburg_body(region="zabaikalsky"),
        {k: v for k, v in _netschool_body().items() if k != "region"},
        {k: v for k, v in _netschool_body().items() if k != "school_id"},
        _netschool_body(credential={"token": JWT}),
        _petersburg_body(provider="moscow"),
        {k: v for k, v in _petersburg_body().items() if k != "provider"},
    ]
    for body in refused:
        response = await _register(client, body)
        assert response.status_code == 422, body
    assert petersburg.seen == [] and netschool.seen == []

    assert (await _register(client, _petersburg_body())).status_code == 200
    assert (await _register(client, _netschool_body())).status_code == 200


async def test_a_cookie_value_that_would_inject_a_second_cookie_is_refused(client, netschool):
    for cookies in (
        {"NSSESSIONID": "x; ESRNSec=forged"},
        {"NSSESSIONID": "x", "ESRNSec": "y, z"},
        {"NSSESSIONID": "x", "Tracking": "t"},
    ):
        body = _netschool_body()
        body["credential"]["cookies"] = cookies
        response = await _register(client, body)
        assert response.status_code == 422, cookies
    assert netschool.seen == []


async def test_a_header_value_with_a_line_break_is_refused(client, netschool):
    body = _netschool_body()
    body["credential"]["at"] = "5657474536\r\nX-Evil: 1"
    response = await _register(client, body)

    assert response.status_code == 422
    assert netschool.seen == []


async def test_a_jwt_that_is_not_a_jwt_is_refused(client, petersburg):
    for token in ("not-a-jwt-at-all-really", f"{JWT}; X-Other=1", "a.b.c", "a" * 5000):
        response = await _register(client, _petersburg_body(credential={"token": token}))
        assert response.status_code == 422, token
    assert petersburg.seen == []


async def test_a_refused_body_never_echoes_what_it_carried(client):
    """FastAPI's 422 repeats each refused value back as ``input``. Here that
    value is an upstream session, or a password somebody should not have
    sent, and neither goes back out."""
    body = _netschool_body(password="hunter2")
    body["credential"]["cookies"] = {"NSSESSIONID": "secret; ESRNSec=forged"}
    response = await _register(client, body)

    assert response.status_code == 422
    assert "hunter2" not in response.text
    assert "secret" not in response.text
    assert all("input" not in error for error in response.json()["detail"])
    # It still says where the problem is.
    assert any(error["loc"][-1] == "password" for error in response.json()["detail"])


# --------------------------------------------------------------------------
# What the upstream said, and what it costs the caller
# --------------------------------------------------------------------------


async def test_a_session_the_upstream_will_not_accept_from_here_is_409_and_counted(
    client, session, petersburg, netschool
):
    """The session worked on the phone seconds ago; from here it does not.
    Not a 401 with ``X-Diary-Reauth``: asking for the password again would go
    round the same loop. Counted, because it is also what a replay of a
    session that was never real looks like."""
    petersburg.routes[CHILDREN_PATH] = lambda request: httpx.Response(401, json={})
    first = await _register(client, _petersburg_body())
    netschool.routes["/webapi/student/diary/init"] = httpx.Response(401, json={})
    second = await _register(client, _netschool_body())

    for response in (first, second):
        assert response.status_code == 409, response.text
        assert "X-Diary-Reauth" not in response.headers
    assert await _attempts(session) == 2
    assert await session.scalar(select(DiarySession)) is None


async def test_an_account_with_no_pupil_is_403(client, session, petersburg, netschool):
    petersburg.routes[CHILDREN_PATH] = {"items": []}
    first = await _register(client, _petersburg_body())
    netschool.routes["/webapi/student/diary/init"] = {"students": []}
    second = await _register(client, _netschool_body())

    for response in (first, second):
        assert response.status_code == 403, response.text
        assert response.json()["detail"] == "В этой учётной записи нет ученика"
    assert await _attempts(session) == 2
    assert await session.scalar(select(DiarySession)) is None


async def test_a_refusing_region_is_503_and_not_counted(client, session, netschool):
    """The region drops this server's address. Nothing judged the session, so
    nothing is counted — and the header says which 503 it is."""
    netschool.routes["/webapi/student/diary/init"] = httpx.Response(
        200, text="<h1>Доступ к сайту запрещён</h1>", headers={"content-type": "text/html"}
    )
    response = await _register(client, _netschool_body())

    assert response.status_code == 503
    assert response.headers["X-Diary-Unavailable"] == "address-refused"
    assert await _attempts(session) == 0


async def test_the_upstream_being_down_is_503_and_not_counted(client, session, petersburg):
    petersburg.routes[CHILDREN_PATH] = lambda request: httpx.Response(502, text="bad gateway")
    response = await _register(client, _petersburg_body())

    assert response.status_code == 503
    assert response.headers["X-Diary-Unavailable"] == "upstream"
    assert await _attempts(session) == 0


async def test_an_unreadable_answer_is_502_and_counted(client, session, petersburg, netschool):
    petersburg.routes[CHILDREN_PATH] = {"items": "not a list"}
    first = await _register(client, _petersburg_body())
    netschool.routes["/webapi/student/diary/init"] = [{"studentId": 11}]
    second = await _register(client, _netschool_body())

    for response in (first, second):
        assert response.status_code == 502, response.text
    assert await _attempts(session) == 2


async def test_without_a_diary_secret_it_is_503_and_nothing_goes_upstream(
    client, session, petersburg, no_diary_secret
):
    response = await _register(client, _petersburg_body())

    assert response.status_code == 503
    assert response.headers["X-Diary-Unavailable"] == "disabled"
    assert response.json()["detail"] == "Дневник на этом сервере выключен."
    assert petersburg.seen == []
    assert await _attempts(session) == 0


async def test_sign_in_and_registration_failures_share_one_limit(
    client, petersburg, LOGIN_PATH, with_token
):
    """A caller who spent ``/login``'s ten wrong passwords does not get ten
    more tries by session, and the other way round: one limiter, one bucket."""
    petersburg.routes[LOGIN_PATH] = with_token
    for _ in range(diary_login_limiter.limit):
        wrong = await client.post(
            "/api/v1/diary/login", json={"login": "parent@example.com", "password": "wrong"}
        )
        assert wrong.status_code == 401

    blocked = await _register(client, _petersburg_body())
    assert blocked.status_code == 429
    assert "Retry-After" in blocked.headers


async def test_registration_failures_count_against_the_password_sign_in(
    client, petersburg, LOGIN_PATH, with_token
):
    petersburg.routes[CHILDREN_PATH] = lambda request: httpx.Response(401, json={})
    for _ in range(diary_login_limiter.limit):
        assert (await _register(client, _petersburg_body())).status_code == 409

    petersburg.routes[LOGIN_PATH] = with_token
    blocked = await client.post(
        "/api/v1/diary/login", json={"login": "parent@example.com", "password": "correct"}
    )
    assert blocked.status_code == 429


# --------------------------------------------------------------------------
# What a registered session is afterwards
# --------------------------------------------------------------------------


async def test_a_registered_session_reads_like_a_signed_in_one(client, petersburg):
    petersburg.routes[SCHEDULE_PATH] = {"items": [
        {"date": "15.09.2026", "subject_name": "Алгебра", "number": 1, "office": "12"}]}
    token = (await _register(client, _petersburg_body())).json()["token"]
    headers = {"Authorization": f"Bearer {token}"}

    students = await client.get("/api/v1/diary/students", headers=headers)
    assert students.status_code == 200
    assert [s["id"] for s in students.json()] == [4021]

    lessons = await client.get(
        "/api/v1/diary/students/4021/schedule?from=2026-09-14&to=2026-09-20", headers=headers
    )
    assert lessons.status_code == 200, lessons.text
    assert lessons.json()[0]["subject"] == "Алгебра"
    # And every read carries the session that was handed over.
    assert all(f"X-JWT-Token={JWT}" in r.headers["cookie"] for r in petersburg.seen)


async def test_the_login_finds_the_corrections_a_password_sign_in_left(
    client, petersburg, LOGIN_PATH, with_token
):
    """Corrections are keyed by the login, case-folded. A family that used to
    sign in with a password and now registers from the phone keeps them."""
    petersburg.routes[LOGIN_PATH] = with_token
    petersburg.routes[SCHEDULE_PATH] = {"items": [
        {"date": "15.09.2026", "subject_name": "Алгебра", "number": 1, "office": "12"}]}
    signed_in = await client.post(
        "/api/v1/diary/login", json={"login": "Parent@Example.com", "password": "correct"}
    )
    old = {"Authorization": f"Bearer {signed_in.json()['token']}"}
    schedule = "/api/v1/diary/students/4021/schedule?from=2026-09-14&to=2026-09-20"
    target = (await client.get(schedule, headers=old)).json()[0]["target"]
    put = await client.put(
        "/api/v1/diary/students/4021/overrides",
        headers=old,
        json={"target": target, "field": "room", "value": "204"},
    )
    assert put.status_code == 200, put.text

    token = (await _register(client, _petersburg_body(login="parent@example.com"))).json()["token"]
    lesson = (await client.get(schedule, headers={"Authorization": f"Bearer {token}"})).json()[0]
    assert lesson["room"] == "204"


async def test_a_registered_session_is_invisible_to_the_bot(client, session, petersburg):
    """Showing a phone's session in the bot is deliberately left for later: the
    row belongs to no Telegram account and no class, so no bot screen finds
    it — even with a device token of a class bound to the same diary sent."""
    from app.bot.handlers import diary as bot_diary

    klass = SchoolClass(name="9А", join_code="PHONE42", diary_provider="petersburg")
    session.add(klass)
    await session.commit()
    joined = await client.post("/api/v1/join", json={"code": "PHONE42"})
    device = joined.json()["token"]

    response = await client.post(
        "/api/v1/diary/session", json=_petersburg_body(), headers={"X-Device-Token": device}
    )
    assert response.status_code == 200, response.text

    row = await session.scalar(select(DiarySession))
    assert row.telegram_id is None and row.class_id is None
    assert await bot_diary._session_for(session, 42, klass) is None


async def test_a_device_token_never_authorises_a_diary_read(client, session, school_class):
    """The class token and the diary token are two bearers on two endpoint
    families; neither opens the other's."""
    joined = await client.post("/api/v1/join", json={"code": "TEST42"})
    device = joined.json()["token"]

    response = await client.get(
        "/api/v1/diary/students", headers={"Authorization": f"Bearer {device}"}
    )
    assert response.status_code == 401


# --------------------------------------------------------------------------
# The rest of the diary's surface this batch touched
# --------------------------------------------------------------------------


async def test_the_old_login_endpoint_is_unchanged(client, petersburg, LOGIN_PATH, with_token):
    """Older apps still call it, and read exactly two fields."""
    petersburg.routes[LOGIN_PATH] = with_token
    response = await client.post(
        "/api/v1/diary/login", json={"login": "parent@example.com", "password": "correct"}
    )

    assert response.status_code == 200
    assert set(response.json()) == {"token", "login"}
    assert response.json()["login"] == "parent@example.com"


@pytest.mark.parametrize(
    ("answer", "reason"),
    [
        (lambda request: httpx.Response(503, json={}), "upstream"),
        (lambda request: httpx.Response(403, text="<h1>Access denied</h1>",
                                        headers={"content-type": "text/html"}), "address-refused"),
    ],
    ids=["upstream", "address-refused"],
)
async def test_every_diary_503_says_which_kind_it_is(
    client, petersburg, netschool, LOGIN_PATH, answer, reason
):
    """The header the app switches on instead of reading Russian, on ``/login``
    as much as anywhere: before it, «выключен на сервере» and «дневник не
    отвечает» were one status and two sentences to parse."""
    if reason == "upstream":
        petersburg.routes[LOGIN_PATH] = answer
        response = await client.post(
            "/api/v1/diary/login", json={"login": "parent@example.com", "password": "x"}
        )
    else:
        netschool.routes["/webapi/logindata"] = answer(None)
        response = await client.post(
            "/api/v1/diary/login",
            json={"login": "parent", "password": "x", "provider": "netschool",
                  "region": "zabaikalsky", "school_id": 42},
        )
    assert response.status_code == 503
    assert response.headers["X-Diary-Unavailable"] == reason


async def test_a_read_that_finds_the_diary_down_says_so_in_the_header(client, petersburg):
    token = (await _register(client, _petersburg_body())).json()["token"]
    petersburg.routes[CHILDREN_PATH] = lambda request: httpx.Response(503, json={})

    response = await client.get(
        "/api/v1/diary/students", headers={"Authorization": f"Bearer {token}"}
    )
    assert response.status_code == 503
    assert response.headers["X-Diary-Unavailable"] == "upstream"


async def test_the_password_sign_in_without_a_key_says_disabled(
    client, petersburg, no_diary_secret
):
    response = await client.post(
        "/api/v1/diary/login", json={"login": "parent@example.com", "password": "correct"}
    )
    assert response.status_code == 503
    assert response.headers["X-Diary-Unavailable"] == "disabled"
    assert response.json() == {"detail": "Дневник на этом сервере выключен."}


async def test_capabilities_lists_only_password_regions(client):
    response = await client.get("/api/v1/diary/capabilities")

    assert response.status_code == 200
    body = response.json()
    assert body["enabled"] is True and body["registration"] is True
    assert body["providers"]["petersburg"] == {}
    listed = body["providers"]["netschool"]["regions"]
    assert listed == [r.key for r in regions.listed()]
    assert not {"altai-krai", "primorye", "tula"} & set(listed)


async def test_capabilities_says_disabled_without_a_secret(client, no_diary_secret):
    response = await client.get("/api/v1/diary/capabilities")

    assert response.status_code == 200
    assert response.json()["enabled"] is False


async def test_the_zone_handed_back_is_the_one_the_diary_cuts_its_day_at(monkeypatch):
    """What the phone is told and what this server reads as «today» for the
    same session come from one rule. At 16:30 UTC it is already tomorrow in
    Chita and still today in Moscow — the instant a second rule would show."""
    from app.providers.netschool import provider as nsprovider
    from app.providers.petersburg.provider import PetersburgProvider

    class Frozen(datetime):
        @classmethod
        def now(cls, tz=None):
            moment = datetime(2026, 9, 7, 16, 30, tzinfo=UTC)
            return moment.astimezone(tz) if tz is not None else moment

    monkeypatch.setattr(nsprovider, "datetime", Frozen)
    monkeypatch.setattr(pbclient, "datetime", Frozen)

    assert service.zone_for("netschool", "zabaikalsky") == "Asia/Chita"
    connection = nsprovider.NetSchoolProvider().open(json.dumps(
        {"v": 1, "region": "zabaikalsky", "school_id": 1, "at": "a", "cookies": {}}))
    assert connection.today() == date(2026, 9, 8)

    assert service.zone_for("petersburg") == "Europe/Moscow"
    assert PetersburgProvider().open(JWT).today() == date(2026, 9, 7)

    # Every region the allow-list serves has a zone this Python knows.
    for region in regions.listed(password_only=False):
        assert service.zone_for("netschool", region.key) == region.zone
