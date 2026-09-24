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
