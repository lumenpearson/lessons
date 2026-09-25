"""The shared known answers for both diary sign-ins, run through the real code.

`tests/vectors/diary_protocol.json` is read by this file and by the phone's
`DiaryProtocolVectorsTest`, because the sign-in protocol of each diary now
lives in two codebases: the phone signs in itself and hands the session over,
and this server signs in with a password on the bot's page and on the older
``/login``. Two implementations of one protocol drift apart within a month
unless something holds them to one set of answers; the file is that set, and
neither implementation is the oracle.

Every case goes through the real client over ``httpx.MockTransport`` — not a
re-implementation of its rules — so a case that fails here is a behaviour of
the code, not of the test. A rule that is not a request (the password hash,
which role is picked) is asked of the function that implements it.

What a failure here means: the Python side changed and the file did not, or
the reverse. Change them together, and the phone's test with them.
"""

from __future__ import annotations

import json
import re
from datetime import timedelta
from pathlib import Path
from typing import Any

import httpx
import pytest
from pydantic import ValidationError

from app import schemas
from app.api import cron
from app.providers.diary import http as diary_http
from app.providers.diary.errors import (
    AddressRefused,
    BadCredentials,
    DiaryError,
    SessionExpired,
    SignInUnsupported,
    UnexpectedResponse,
    UpstreamUnavailable,
)
from app.providers.netschool import client as nsclient
from app.providers.netschool.regions import get as region_for
from app.providers.petersburg import client as pbclient

VECTORS: dict[str, Any] = json.loads(
    (Path(__file__).parent / "vectors" / "diary_protocol.json").read_text(encoding="utf-8")
)
NETSCHOOL = VECTORS["netschool"]
FLOW = NETSCHOOL["flow"]
PETERSBURG = VECTORS["petersburg"]
REGION = region_for("zabaikalsky")
#: Shaped like the ``X-JWT-Token`` Petersburg issues, which is what a registration takes.
_JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOjF9.c2lnbmF0dXJl"


@pytest.fixture(autouse=True)
def fresh_database():
    """Nothing here touches the database, so the conftest fixture of this name
    — a drop and a create of every table, before each of the hundred cases
    below — is overridden with nothing. It was most of this file's runtime."""
    yield


def _kind(failure: DiaryError) -> str:
    """The vectors' name for a failure. Most specific first: an
    ``AddressRefused`` is an ``UpstreamUnavailable`` too, and the two must not
    be confused — one is «try later», the other «not from this address»."""
    for cls, name in (
        (AddressRefused, "address_refused"),
        (UpstreamUnavailable, "unavailable"),
        (BadCredentials, "bad_credentials"),
        (SignInUnsupported, "sign_in_unsupported"),
        (UnexpectedResponse, "unexpected"),
        (SessionExpired, "session_expired"),
    ):
        if isinstance(failure, cls):
            return name
    return type(failure).__name__


def _json(status: int, body: Any) -> httpx.Response:
    return httpx.Response(status, json=body)


def _serve_netschool(monkeypatch, handler) -> None:
    """The «Сетевой город» client's pooled client, replaced by one on a mock
    transport — built like the real one, carrying no cookies of its own."""

    async def shared() -> httpx.AsyncClient:
        return httpx.AsyncClient(
            transport=httpx.MockTransport(handler),
            cookies=diary_http.NoCookieJar(),
            follow_redirects=False,
        )

    monkeypatch.setattr(nsclient, "shared_client", shared)


async def _netschool_sign_in(
    monkeypatch,
    *,
    logindata: Any = None,
    getdata: Any = None,
    login_answers: list[tuple[int, Any]] | None = None,
) -> tuple[dict[str, Any] | DiaryError, list[httpx.Request]]:
    """One whole password sign-in through `NetSchoolClient.login`.

    The flow's own answers stand in for whatever the case does not replace;
    `/webapi/login` answers the listed answers in order, then a plain success.
    Only the diary's error family is caught: anything else escaping is exactly
    the defect these cases exist to catch, and fails the test on its own.
    """
    seen: list[httpx.Request] = []
    answers = list(login_answers or [])

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        path = request.url.path
        if path == "/webapi/logindata":
            return _json(200, FLOW["logindata"] if logindata is None else logindata)
        if path == "/webapi/auth/getdata":
            return _json(200, FLOW["getdata"] if getdata is None else getdata)
        if path == "/webapi/login":
            status, body = answers.pop(0) if answers else (200, {"at": "after-role"})
            return _json(status, body)
        return _json(404, {})

    _serve_netschool(monkeypatch, handler)
    client = nsclient.NetSchoolClient(REGION, {"v": 1, "region": REGION.key})
    try:
        await client.login(FLOW["login"], FLOW["password"], FLOW["school_id"])
    except DiaryError as failure:
        return failure, seen
    return client.session, seen


def _forms(seen: list[httpx.Request], path: str) -> list[dict[str, str]]:
    """The decoded form of every request to ``path`` — decoded, because httpx
    and OkHttp write the same fields with different bytes."""
    from urllib.parse import parse_qsl

    return [
        dict(parse_qsl(request.content.decode(), keep_blank_values=True))
        for request in seen
        if request.url.path == path
    ]


# --------------------------------------------------------------------------
# «Сетевой город»
# --------------------------------------------------------------------------


@pytest.mark.parametrize("case", NETSCHOOL["hash"], ids=lambda c: repr(c["password"]))
def test_netschool_password_hashes_match_the_vectors(case):
    if "failure" in case:
        with pytest.raises(DiaryError) as raised:
            nsclient._hash_password(case["salt"], case["password"])
        assert _kind(raised.value) == case["failure"]
        # And the failure carries nothing of what was typed.
        assert case["password"] not in str(raised.value)
        return
    assert nsclient._hash_password(case["salt"], case["password"]) == (case["pw"], case["pw2"])


@pytest.mark.parametrize("case", NETSCHOOL["logindata_cases"], ids=lambda c: json.dumps(c["body"]))
async def test_netschool_logindata_answers_match_the_vectors(monkeypatch, case):
    def handler(request: httpx.Request) -> httpx.Response:
        return _json(200, case["body"])

    _serve_netschool(monkeypatch, handler)
    client = nsclient.NetSchoolClient(REGION)
    try:
        await client.login_allowed()
    except DiaryError as failure:
        assert _kind(failure) == case["expect"]
        return
    assert case["expect"] == "ok"
    if case["ver"] is None:
        # Never stored as None: that is what was sent as the word "None".
        assert "ver" not in client.session
    else:
        assert client.session["ver"] == case["ver"]


@pytest.mark.parametrize("case", NETSCHOOL["getdata_cases"], ids=lambda c: json.dumps(c["body"]))
async def test_netschool_getdata_answers_match_the_vectors(monkeypatch, case):
    outcome, seen = await _netschool_sign_in(
        monkeypatch, getdata=case["body"], login_answers=[(200, {"at": "x"})]
    )
    if case["expect"] != "ok":
        assert isinstance(outcome, DiaryError), outcome
        assert _kind(outcome) == case["expect"]
        # Nothing that failed at getdata may have posted a password.
        assert _forms(seen, "/webapi/login") == []
        return
    assert not isinstance(outcome, DiaryError), outcome
    (form,) = _forms(seen, "/webapi/login")
    assert list(form) == NETSCHOOL["login_form_fields"]
    for field, value in case["form"].items():
        assert form[field] == value


@pytest.mark.parametrize(
    "case", NETSCHOOL["login_cases"], ids=lambda c: f"{c['status']}-{json.dumps(c['body'])}"
)
async def test_netschool_sign_in_answers_match_the_vectors(monkeypatch, case):
    answer = (case["status"], case["body"])
    answers = [(200, FLOW["role_question"]), answer] if case.get("tried_role") else [answer]
    outcome, seen = await _netschool_sign_in(monkeypatch, login_answers=answers)
    expect = case["expect"]

    if "failure" in expect:
        assert isinstance(outcome, DiaryError), outcome
        assert _kind(outcome) == expect["failure"]
        if "message" in expect:
            wanted = expect["message"] or BadCredentials.message
            assert outcome.message == wanted
        return

    assert not isinstance(outcome, DiaryError), outcome
    if expect.get("needs_role"):
        # Re-run with a fresh salt, and the role picked from the answer.
        assert len(_forms(seen, "/webapi/auth/getdata")) == 2
        first, second = _forms(seen, "/webapi/login")
        assert "rolegroup" not in first
        assert second["rolegroup"] == expect["rolegroup"]
        assert outcome["at"] == "after-role"
        return
    assert outcome["at"] == expect["at"]
    assert outcome.get("time_out") == expect["time_out"]


def test_the_login_form_carries_exactly_the_vector_fields():
    """A role retry adds ``rolegroup``; everything else is exactly this list."""
    assert NETSCHOOL["login_form_fields"] == ["loginType", "scid", "un", "pw", "pw2", "lt", "ver"]


@pytest.mark.parametrize(
    "case",
    NETSCHOOL["raw_answer_cases"],
    ids=lambda c: f"{c['step']}-{c['body'].strip()}",
)
async def test_netschool_raw_answers_match_the_vectors(monkeypatch, case):
    """Bodies no JSON value can stand for, sent as bytes at one step of an
    otherwise ordinary sign-in. A parser more lenient than ``json.loads``
    reads «<html>» as a word and «{"at": abc}» as an object, and then calls
    the first a dead diary and the second a wrong password."""
    raw = httpx.Response(
        case["status"],
        content=case["body"].encode(),
        headers={"content-type": case["content_type"]},
    )
    steps = {
        "logindata": "/webapi/logindata",
        "getdata": "/webapi/auth/getdata",
        "login": "/webapi/login",
    }

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == steps[case["step"]]:
            return raw
        if path == "/webapi/logindata":
            return _json(200, FLOW["logindata"])
        if path == "/webapi/auth/getdata":
            return _json(200, FLOW["getdata"])
        if path == "/webapi/login":
            return _json(200, {"at": "x"})
        return _json(404, {})

    _serve_netschool(monkeypatch, handler)
    client = nsclient.NetSchoolClient(REGION, {"v": 1, "region": REGION.key})
    try:
        await client.login(FLOW["login"], FLOW["password"], FLOW["school_id"])
    except DiaryError as failure:
        assert _kind(failure) == case["expect"]
        return
    assert case["expect"] == "ok"


@pytest.mark.parametrize("case", NETSCHOOL["role_pick"], ids=lambda c: json.dumps(c["body"]))
def test_netschool_role_pick_matches_the_vectors(case):
    assert nsclient.NetSchoolClient._pick_parent_role(case["body"]) == case["expect"]


@pytest.mark.parametrize(
    "case", NETSCHOOL["refusal"]["cases"], ids=lambda c: f"{c['status']}-{c['body']}"
)
async def test_netschool_refusal_rule_matches_the_vectors(monkeypatch, case):
    assert list(nsclient._WAF_MARKERS) == NETSCHOOL["refusal"]["markers"]

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            case["status"],
            content=case["body"].encode(),
            headers={"content-type": case["content_type"]},
        )

    _serve_netschool(monkeypatch, handler)
    client = nsclient.NetSchoolClient(REGION)
    try:
        await client._send("GET", "/webapi/logindata", auth=False)
    except AddressRefused:
        refused = True
    else:
        refused = False
    assert refused is case["refused"]


@pytest.mark.parametrize(
    "case", NETSCHOOL["schools_search"]["query_cases"], ids=lambda c: c["query"][:12]
)
async def test_netschool_school_search_cuts_the_query_like_the_vectors(monkeypatch, case):
    search = NETSCHOOL["schools_search"]
    sent: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == search["path"]
        # No session rides a search: the server answers it to the login page.
        assert "at" not in request.headers and "cookie" not in request.headers
        sent.append(request.url.params[search["query_parameter"]])
        return _json(200, [])

    _serve_netschool(monkeypatch, handler)
    await nsclient.NetSchoolClient(REGION).schools_search(case["query"])
    assert sent == [case["sent"]]
    assert len(case["sent"]) <= search["query_cut_code_points"]


@pytest.mark.parametrize(
    "case", NETSCHOOL["schools_search"]["cases"], ids=lambda c: json.dumps(c["body"])[:40]
)
async def test_netschool_school_search_rows_match_the_vectors(monkeypatch, case):
    def handler(request: httpx.Request) -> httpx.Response:
        return _json(200, case["body"])

    _serve_netschool(monkeypatch, handler)
    try:
        rows = await nsclient.NetSchoolClient(REGION).schools_search("школа")
    except DiaryError as failure:
        assert _kind(failure) == case["expect"]
        return
    assert rows == case["expect"]


def test_the_session_cookies_are_the_vectors():
    assert list(nsclient.SESSION_COOKIES) == NETSCHOOL["session_cookies"]


# --------------------------------------------------------------------------
# Petersburg
# --------------------------------------------------------------------------


async def _petersburg_login(monkeypatch, case) -> tuple[str | DiaryError, list[httpx.Request]]:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        headers = [("content-type", case["content_type"])]
        headers += [("set-cookie", cookie) for cookie in case["set_cookie"]]
        return httpx.Response(case["status"], headers=headers, content=case["body"].encode())

    async def shared() -> httpx.AsyncClient:
        return httpx.AsyncClient(
            base_url=pbclient.BASE_URL,
            transport=httpx.MockTransport(handler),
            cookies=pbclient._NoCookieJar(),
            headers={"user-agent": pbclient.USER_AGENT},
        )

    monkeypatch.setattr(pbclient, "shared_client", shared)
    try:
        return await pbclient.PetersburgClient().login("parent@example.com", "secret"), seen
    except DiaryError as failure:
        return failure, seen


@pytest.mark.parametrize("case", PETERSBURG["login_cases"], ids=lambda c: c["name"])
async def test_petersburg_login_answers_match_the_vectors(monkeypatch, case):
    outcome, seen = await _petersburg_login(monkeypatch, case)

    (request,) = seen
    assert request.url.path == PETERSBURG["login_path"]
    assert "cookie" not in request.headers  # nobody is signed in yet
    sent = json.loads(request.content)
    assert set(sent) == {*PETERSBURG["login_fields"], "login", "password"}
    for field, value in PETERSBURG["login_fields"].items():
        assert sent[field] == value

    expect = case["expect"]
    if "failure" in expect:
        assert isinstance(outcome, DiaryError), outcome
        assert _kind(outcome) == expect["failure"]
    else:
        assert outcome == expect["token"]


@pytest.mark.parametrize(
    "case",
    [c for c in PETERSBURG["login_cases"] if c["set_cookie"]],
    ids=lambda c: c["name"],
)
def test_session_cookie_rule_matches_the_vectors(case):
    """Both readers of a session cookie — Petersburg's own and the shared one
    «Сетевой город» uses — agree with the vectors: the cookie when the answer
    carries a live one, nothing (so the body wins) when it carries a deletion."""
    response = httpx.Response(
        case["status"],
        headers=[("set-cookie", cookie) for cookie in case["set_cookie"]],
        request=httpx.Request("POST", PETERSBURG["origin"] + PETERSBURG["login_path"]),
    )
    token = case["expect"]["token"]
    wanted = None if token == "body" else token
    name = PETERSBURG["session_cookie"]
    assert pbclient._session_cookie(response) == wanted
    assert diary_http.session_cookie(response, name) == wanted


# --------------------------------------------------------------------------
# What both sides share
# --------------------------------------------------------------------------


@pytest.mark.parametrize("case", VECTORS["credential"]["cases"], ids=lambda c: repr(c["value"]))
def test_credential_charset_rule_matches_the_vectors(case):
    """What the phone checks before it registers a session is what the server
    checks when it takes one, and again before it sends one."""
    pattern = VECTORS["credential"]["pattern"]
    # The vector's class is the server's own, anchored and bounded.
    assert pattern == "^" + diary_http._COOKIE_OCTETS.pattern[:-1] + "{1,4096}$"
    assert diary_http.cookie_value_ok(case["value"]) is case["ok"]
    assert (re.fullmatch(pattern, case["value"]) is not None) is case["ok"]


@pytest.mark.parametrize("case", VECTORS["header_token"]["cases"], ids=lambda c: repr(c["value"]))
def test_header_token_rule_matches_the_vectors(case):
    pattern = VECTORS["header_token"]["pattern"]
    assert pattern == "^" + diary_http._HEADER_TOKEN.pattern + "$"
    assert diary_http.header_value_ok(case["value"]) is case["ok"]
    assert (re.fullmatch(pattern, case["value"]) is not None) is case["ok"]


@pytest.mark.parametrize("case", VECTORS["login"]["cases"], ids=lambda c: ascii(c["typed"]))
def test_login_cleaning_matches_the_vectors(case):
    """The login the server keys a family's corrections on is the login the
    phone sends the diary and registers — one cleaning, on both sides. The
    registration body and the password sign-in each run it, so each is asked."""
    wanted = case["sent"]
    assert VECTORS["login"]["min_code_points"] == 3
    for model in (schemas.DiaryLoginIn, schemas.PetersburgSessionIn):
        fields: dict[str, Any] = {"login": case["typed"]}
        if model is schemas.DiaryLoginIn:
            fields["password"] = "secret"
        else:
            fields |= {"provider": "petersburg", "credential": {"token": _JWT}}
        if wanted is None:
            with pytest.raises(ValidationError):
                model.model_validate(fields)
        else:
            assert model.model_validate(fields).login == wanted


def test_user_agent_and_petersburg_origin_are_the_vectors():
    assert diary_http.USER_AGENT == VECTORS["user_agent"]
    assert pbclient.USER_AGENT == VECTORS["user_agent"]
    assert pbclient.BASE_URL == PETERSBURG["origin"]
    assert pbclient.SESSION_COOKIE == PETERSBURG["session_cookie"]


def test_the_idle_window_is_the_vectors():
    """A session nobody uses for this long is purged by the cron, and the
    phone says so on the screen — one number, held in both places."""
    assert timedelta(days=VECTORS["session"]["idle_days"]) == cron.DIARY_SESSION_TTL
