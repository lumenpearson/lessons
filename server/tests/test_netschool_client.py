"""The «Сетевой город» client, against a mock transport.

Nothing here reaches a live server. A routing handler answers each `/webapi`
path the sign-in and the reads touch, and the tests pin the things the review
said must hold: the credential holds a session and never a password, a wrong
password is BadCredentials, a Госуслуги-only region refuses before any password
is sent, a blocked address is its own error, the token never rides in a URL,
and two families' cookies never meet on the shared client.
"""

from __future__ import annotations

import json

import httpx
import pytest

from app.providers.diary import http as diary_http
from app.providers.diary.errors import (
    AddressRefused,
    BadCredentials,
    SessionExpired,
    SignInUnsupported,
)
from app.providers.netschool import client as nsclient
from app.providers.netschool.provider import NetSchoolProvider
from app.providers.netschool.regions import get as region_for

LOGINDATA_OK = {"productName": "Сетевой Город. Образование", "version": "5.58.0",
                "schoolLogin": True, "esiaMainAuth": False, "cacheVer": "639"}


def _login_success_routes(recorder: list[httpx.Request]):
    """A handler that walks a whole password sign-in and bootstrap to success."""

    def handler(request: httpx.Request) -> httpx.Response:
        recorder.append(request)
        path = request.url.path
        if path == "/webapi/logindata":
            return httpx.Response(200, json=LOGINDATA_OK,
                                  headers={"set-cookie": "NSSESSIONID=sess-A; Path=/"})
        if path == "/webapi/auth/getdata":
            return httpx.Response(200, json={"lt": "111", "ver": "222", "salt": "333"})
        if path == "/webapi/login":
            return httpx.Response(200, json={"at": "at-A", "timeOut": 900000,
                                             "accountInfo": {"user": {"id": 1}}})
        if path == "/webapi/student/diary/init":
            return httpx.Response(200, json={"students": [
                {"studentId": 11, "nickName": "Иванов Иван", "classId": 3}]})
        if path == "/webapi/years/current":
            return httpx.Response(200, json={"id": 2026, "startDate": "2026-09-01",
                                             "endDate": "2027-05-31"})
        if path == "/webapi/context":
            return httpx.Response(200, json={"organizationName": "Школа № 1"})
        if path == "/webapi/grade/assignment/types":
            return httpx.Response(200, json=[{"id": 3, "name": "Домашнее задание"}])
        return httpx.Response(404, json={})

    return handler


def _install(monkeypatch, handler):
    real = httpx.AsyncClient

    def build(**kwargs):
        return real(**kwargs, transport=httpx.MockTransport(handler))

    monkeypatch.setattr(diary_http.httpx, "AsyncClient", build)


async def test_sign_in_seals_a_session_and_never_the_password(monkeypatch):
    reqs: list[httpx.Request] = []
    _install(monkeypatch, _login_success_routes(reqs))
    await nsclient.close_client()
    try:
        cred = await NetSchoolProvider().sign_in(_req("parent", "hunter2", "zabaikalsky", 42))
    finally:
        await nsclient.close_client()

    session = json.loads(cred)
    assert session["at"] == "at-A"
    assert session["cookies"]["NSSESSIONID"] == "sess-A"
    assert session["year_id"] == 2026
    assert session["year_start"] == "2026-09-01"
    assert session["types"] == {"3": "Домашнее задание"}
    assert session["time_out"] == 900000
    # No password, and no hash of it, anywhere in the sealed credential.
    assert "hunter2" not in cred
    for key in ("pw", "pw2", "password"):
        assert key not in session
    # The login form posted pw/pw2, but never the raw password.
    login_req = next(r for r in reqs if r.url.path == "/webapi/login")
    body = login_req.content.decode()
    assert "hunter2" not in body and "pw2=" in body


async def test_the_token_never_rides_in_a_url(monkeypatch):
    reqs: list[httpx.Request] = []
    _install(monkeypatch, _login_success_routes(reqs))
    await nsclient.close_client()
    try:
        cred = await NetSchoolProvider().sign_in(_req("parent", "hunter2", "zabaikalsky", 42))
        conn = NetSchoolProvider().open(cred)
        await conn.keep_alive()
    finally:
        await nsclient.close_client()

    for r in reqs:
        assert "at-A" not in str(r.url)
        assert "sess-A" not in str(r.url)
        assert "hunter2" not in str(r.url)


async def test_a_wrong_password_is_bad_credentials(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/webapi/logindata":
            return httpx.Response(200, json=LOGINDATA_OK)
        if request.url.path == "/webapi/auth/getdata":
            return httpx.Response(200, json={"lt": "1", "ver": "2", "salt": "3"})
        if request.url.path == "/webapi/login":
            return httpx.Response(409, json={"message": "Неверный пароль"})
        return httpx.Response(404, json={})

    _install(monkeypatch, handler)
    await nsclient.close_client()
    try:
        with pytest.raises(BadCredentials):
            await NetSchoolProvider().sign_in(_req("parent", "wrong", "zabaikalsky", 42))
    finally:
        await nsclient.close_client()


async def test_a_esia_only_region_refuses_before_any_password(monkeypatch):
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.path)
        if request.url.path == "/webapi/logindata":
            return httpx.Response(200, json={**LOGINDATA_OK, "schoolLogin": False})
        return httpx.Response(200, json={})

    _install(monkeypatch, handler)
    await nsclient.close_client()
    try:
        with pytest.raises(SignInUnsupported):
            # ingushetia allows passwords in the table, but this server says no.
            await NetSchoolProvider().sign_in(_req("parent", "hunter2", "ingushetia", 42))
    finally:
        await nsclient.close_client()
    assert "/webapi/auth/getdata" not in seen and "/webapi/login" not in seen


async def test_a_blocked_address_is_address_refused(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="<h1>Доступ к сайту запрещён</h1>",
                              headers={"content-type": "text/html"})

    _install(monkeypatch, handler)
    await nsclient.close_client()
    try:
        with pytest.raises(AddressRefused):
            await NetSchoolProvider().sign_in(_req("parent", "hunter2", "zabaikalsky", 42))
    finally:
        await nsclient.close_client()


async def test_a_dead_session_answers_session_expired(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={})

    _install(monkeypatch, handler)
    await nsclient.close_client()
    region = region_for("zabaikalsky")
    conn = NetSchoolProvider().open(json.dumps(
        {"v": 1, "region": "zabaikalsky", "school_id": 42, "at": "old", "year_id": 2026,
         "year_start": "2026-09-01", "year_end": "2027-05-31", "cookies": {}}))
    try:
        with pytest.raises(SessionExpired):
            await conn.keep_alive()
    finally:
        await nsclient.close_client()
    assert region is not None


async def test_two_families_cookies_never_meet_on_the_shared_client(monkeypatch):
    cookies_seen: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        cookies_seen.append(request.headers.get("cookie"))
        return httpx.Response(200, json={"students": []},
                              headers={"set-cookie": "NSSESSIONID=leak; Path=/"})

    _install(monkeypatch, handler)
    await nsclient.close_client()
    region = region_for("zabaikalsky")
    a = nsclient.NetSchoolClient(region, {"at": "at-A", "cookies": {"NSSESSIONID": "sess-A"}})
    b = nsclient.NetSchoolClient(region, {"at": "at-B", "cookies": {"NSSESSIONID": "sess-B"}})
    try:
        await a.diary_init()
        await b.diary_init()
    finally:
        await nsclient.close_client()
    # B's request carries only B's session, never A's leaked Set-Cookie.
    assert cookies_seen == ["NSSESSIONID=sess-A", "NSSESSIONID=sess-B"]


def _req(login, password, region, school_id):
    from app.providers.diary.base import SignInRequest

    return SignInRequest(login=login, password=password, region=region, school_id=school_id)


# --------------------------------------------------------------------------
# A session this code did not write itself
#
# The phone now opens a «Сетевой город» session and hands it over, so the
# stored session is no longer only what `login` produced. What goes into a
# header is checked on the way out, whatever wrote it.
# --------------------------------------------------------------------------


def _serve(monkeypatch, handler):
    """Replace the pooled client with one on a mock transport, without touching
    ``httpx.AsyncClient`` itself — so a test can also drive the ASGI app."""

    async def shared():
        return httpx.AsyncClient(
            transport=httpx.MockTransport(handler), cookies=diary_http.NoCookieJar()
        )

    monkeypatch.setattr(nsclient, "shared_client", shared)


@pytest.mark.parametrize(
    "stored",
    [
        {"at": "at-A\r\nX-Evil: 1", "cookies": {"NSSESSIONID": "s"}},
        {"at": "at-A", "cookies": {"NSSESSIONID": "s; ESRNSec=forged"}},
        {"at": "at-A", "cookies": {"NSSESSIONID": "s", "Tracking": "t"}},
        {"at": 123, "cookies": {}},
        {"at": "at-A", "cookies": ["NSSESSIONID=s"]},
    ],
    ids=["line-break-in-at", "semicolon-in-cookie", "unknown-cookie", "number-at", "cookie-list"],
)
async def test_a_stored_value_that_is_not_header_safe_is_never_sent(monkeypatch, stored):
    """A value that would become a second header or a second cookie is a dead
    session, and nothing leaves: no request at all is made with it."""
    sent: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        sent.append(request)
        return httpx.Response(200, json={})

    _serve(monkeypatch, handler)
    client = nsclient.NetSchoolClient(region_for("zabaikalsky"), dict(stored))
    with pytest.raises(SessionExpired):
        await client.keep_alive()
    assert sent == []


def _bootstrap_routes(recorder: list[httpx.Request]):
    def handler(request: httpx.Request) -> httpx.Response:
        recorder.append(request)
        path = request.url.path
        if path == "/webapi/student/diary/init":
            return httpx.Response(200, json={"students": [
                {"studentId": 11, "nickName": "Иванов Иван", "classId": 3}]})
        if path == "/webapi/years/current":
            return httpx.Response(200, json={"id": 2026, "startDate": "2026-09-01",
                                             "endDate": "2027-05-31"})
        if path == "/webapi/context":
            return httpx.Response(200, json={"organizationName": "Школа № 1"})
        if path == "/webapi/grade/assignment/types":
            return httpx.Response(200, json=[{"id": 3, "name": "Домашнее задание"}])
        return httpx.Response(404, json={})

    return handler


async def test_adopt_keeps_only_the_fields_it_knows(monkeypatch):
    """Whatever else the handed-over JSON carries — a password a careless client
    put there, a cookie nobody asked for, a ``ver`` of null — is not sealed."""
    from app.providers.diary.base import AdoptRequest

    seen: list[httpx.Request] = []
    _serve(monkeypatch, _bootstrap_routes(seen))
    handed = {
        "at": "at-phone",
        "cookies": {"NSSESSIONID": "sess-phone", "ESRNSec": "", "Tracking": "t"},
        "ver": None,
        "password": "hunter2",
        "school": "forged",
    }
    adopted = await NetSchoolProvider().adopt(
        AdoptRequest(credential=json.dumps(handed), region="zabaikalsky", school_id=42)
    )

    session = json.loads(adopted.credential)
    assert set(session) == {
        "v", "region", "school_id", "at", "cookies",
        "year_id", "year_start", "year_end", "school", "types",
    }
    assert session["cookies"] == {"NSSESSIONID": "sess-phone"}
    assert session["school"] == "Школа № 1"  # the upstream's, not the phone's
    assert session["school_id"] == 42 and session["region"] == "zabaikalsky"
    assert "hunter2" not in adopted.credential
    assert [s.id for s in adopted.students] == [11]
    assert adopted.school_name == "Школа № 1"
    # The four bootstrap calls, in order, all to the allow-listed origin.
    assert [r.url.path for r in seen] == [
        "/webapi/student/diary/init",
        "/webapi/years/current",
        "/webapi/context",
        "/webapi/grade/assignment/types",
    ]
    assert {r.url.host for r in seen} == {"region.zabedu.ru"}
    assert all(r.headers["at"] == "at-phone" for r in seen)


async def test_adopt_refuses_a_region_the_route_should_have_refused():
    """The route checks the allow-list first, so reaching here without it is a
    programming error — a ValueError, and no request."""
    from app.providers.diary.base import AdoptRequest

    for region, school in (("atlantis", 1), ("tula", 1), ("zabaikalsky", None)):
        with pytest.raises(ValueError):
            await NetSchoolProvider().adopt(
                AdoptRequest(credential="{}", region=region, school_id=school)
            )


async def test_a_diary_init_that_is_not_an_object_is_unreadable(monkeypatch):
    """A list where ``diary/init`` should be an object used to raise
    AttributeError out of the bootstrap — a 500 on the sign-in."""
    from app.providers.diary.errors import UnexpectedResponse

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=[{"studentId": 11}])

    _serve(monkeypatch, handler)
    client = nsclient.NetSchoolClient(region_for("zabaikalsky"), {"at": "at-A", "cookies": {}})
    with pytest.raises(UnexpectedResponse):
        await NetSchoolProvider.bootstrap(client)


async def test_a_missing_ver_is_never_sent_as_the_word_none(monkeypatch):
    """A region that sends no ``cacheVer`` used to have None stored, and the
    logout — and the SecurityWarning acknowledgement — posted ``ver=None``.
    Sessions sealed before the fix still hold that None, so the reading side
    is held too."""
    posted: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/webapi/logindata":
            return httpx.Response(200, json={"schoolLogin": True})
        posted.append(request.content.decode())
        return httpx.Response(200, json={})

    _serve(monkeypatch, handler)
    region = region_for("zabaikalsky")

    fresh = nsclient.NetSchoolClient(region)
    await fresh.login_allowed()
    assert "ver" not in fresh.session

    for session in ({"at": "at-A"}, {"at": "at-A", "ver": None}):
        await nsclient.NetSchoolClient(region, dict(session)).logout()
    assert posted == ["at=at-A&ver=", "at=at-A&ver="]
    assert all("None" not in body for body in posted)


async def test_a_school_whose_id_is_true_is_not_a_school(monkeypatch):
    """``isinstance(True, int)`` is true in Python, so ``{"id": true}`` was kept
    as the school with id 1 — a row the admin could bind a class to."""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=[
            {"id": True, "name": "Не школа"},
            {"id": 7, "name": "Гимназия № 7", "addressString": "ул. Мира, 3"},
        ])

    _serve(monkeypatch, handler)
    rows = await nsclient.NetSchoolClient(region_for("zabaikalsky")).schools_search("гимназия")
    assert rows == [{"id": 7, "name": "Гимназия № 7", "address": "ул. Мира, 3"}]


MALFORMED_SIGN_INS = {
    "logindata-array": {"/webapi/logindata": [1]},
    "salt-in-cyrillic": {"/webapi/auth/getdata": {"salt": "соль", "lt": "1", "ver": "2"}},
    "timeout-in-words": {"/webapi/login": {"at": "at-A", "timeOut": "soon"}},
    "login-array": {"/webapi/login": ["x"]},
    "at-a-number": {"/webapi/login": {"at": 123}},
    "role-with-no-roles": {"/webapi/login": {"entryPoint": "/choose-session-role"}},
}


@pytest.mark.parametrize("override", MALFORMED_SIGN_INS.values(), ids=MALFORMED_SIGN_INS.keys())
async def test_a_malformed_sign_in_answer_is_never_a_500(monkeypatch, override):
    """Each of these walked out of the client as AttributeError,
    UnicodeEncodeError, ValueError, TypeError or an internal exception, and
    ``POST /api/v1/diary/login`` answered 500 with a stack trace. Now each is
    one of the diary's own answers: a status the app has a sentence for."""
    from httpx import ASGITransport

    from app.main import app

    defaults = {
        "/webapi/logindata": LOGINDATA_OK,
        "/webapi/auth/getdata": {"lt": "1", "ver": "2", "salt": "3"},
        "/webapi/login": {"at": "at-A"},
        "/webapi/student/diary/init": {"students": [{"studentId": 11, "nickName": "Иван"}]},
        "/webapi/years/current": {"id": 2026},
        "/webapi/context": {},
        "/webapi/grade/assignment/types": [],
    }
    routes = {**defaults, **override}

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=routes.get(request.url.path, {}))

    _serve(monkeypatch, handler)
    async with httpx.AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as api:
        response = await api.post(
            "/api/v1/diary/login",
            json={"login": "parent", "password": "hunter2", "provider": "netschool",
                  "region": "zabaikalsky", "school_id": 42},
        )
    assert response.status_code != 500, response.text
    assert response.status_code in (200, 401, 502, 503)
