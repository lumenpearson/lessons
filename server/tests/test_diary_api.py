"""The diary endpoints, against a fake upstream.

Nothing here touches dnevnik2.petersburgedu.ru. The provider's shared httpx
client is replaced with one driven by a hand-written transport, which is also
the only honest way to test an integration whose upstream is undocumented: the
tests pin what this project does with each answer, not what the service does.
"""

from __future__ import annotations

import json
from datetime import date

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import select

from app.main import app
from app.models import DiarySession
from app.providers.petersburg import client as provider_client

LOGIN_PATH = "/api/user/auth/login"


class FakeUpstream:
    """Answers the upstream's paths, and records what it was asked.

    @param routes path -> either a dict (sent as ``{"data": ...}``) or a
        callable taking the request and returning a full ``httpx.Response``.
    """

    def __init__(self, routes: dict[str, object]) -> None:
        self.routes = routes
        self.seen: list[httpx.Request] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.seen.append(request)
        route = self.routes.get(request.url.path)
        if route is None:
            return httpx.Response(404, json={"message": "no such path"})
        if callable(route):
            return route(request)
        return httpx.Response(200, json={"data": route})

    def query(self, path: str) -> dict[str, str]:
        for request in self.seen:
            if request.url.path == path:
                return dict(request.url.params)
        raise AssertionError(f"{path} was never called")


@pytest.fixture
async def upstream(monkeypatch):
    """Installs a fake upstream and hands back the recorder."""
    fake = FakeUpstream({})

    async def shared():
        return httpx.AsyncClient(
            base_url=provider_client.BASE_URL,
            transport=httpx.MockTransport(fake.handler),
        )

    monkeypatch.setattr(provider_client, "shared_client", shared)
    return fake


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


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


def with_token(request: httpx.Request) -> httpx.Response:
    """The login answer, with the session in a cookie as the upstream sends it."""
    body = json.loads(request.content)
    if body.get("password") != "correct":
        return httpx.Response(401, json={"message": "Неверный логин или пароль"})
    response = httpx.Response(200, json={"data": {"token": "body-token"}})
    response.headers["set-cookie"] = "X-JWT-Token=cookie-token; Path=/"
    return response


async def sign_in(client, upstream, password: str = "correct") -> str:
    upstream.routes[LOGIN_PATH] = with_token
    response = await client.post(
        "/api/v1/diary/login", json={"login": "parent@example.com", "password": password}
    )
    assert response.status_code == 200, response.text
    return response.json()["token"]


# ---- signing in -----------------------------------------------------------


async def test_login_returns_a_token_of_ours_and_never_the_upstream_one(
    client, upstream, session
):
    token = await sign_in(client, upstream)
    assert token != "cookie-token"

    row = await session.scalar(select(DiarySession))
    # Ours is stored hashed, exactly like a device token; theirs is stored as
    # it is, because it has to be sent back to them.
    assert row.upstream_token == "cookie-token"
    assert row.login == "parent@example.com"


async def test_the_password_is_never_written_anywhere(client, upstream, session):
    await sign_in(client, upstream)
    row = await session.scalar(select(DiarySession))
    stored = json.dumps(
        {
            column.name: str(getattr(row, column.name))
            for column in DiarySession.__table__.columns
        },
        ensure_ascii=False,
    )
    assert "correct" not in stored


async def test_the_cookie_wins_over_the_body_when_they_disagree(client, upstream, session):
    """Both carry a token and they have been seen to differ; the cookie is the
    one later calls accept."""
    await sign_in(client, upstream)
    row = await session.scalar(select(DiarySession))
    assert row.upstream_token == "cookie-token"


async def test_a_wrong_password_is_401_and_opens_no_session(client, upstream, session):
    upstream.routes[LOGIN_PATH] = with_token
    response = await client.post(
        "/api/v1/diary/login", json={"login": "parent@example.com", "password": "wrong"}
    )
    assert response.status_code == 401
    assert await session.scalar(select(DiarySession)) is None


@pytest.mark.parametrize("login", ["", "  ", "ab"])
async def test_a_login_that_is_not_a_login_is_rejected_by_the_schema(client, login):
    response = await client.post(
        "/api/v1/diary/login", json={"login": login, "password": "x"}
    )
    assert response.status_code == 422


async def test_every_endpoint_needs_a_bearer(client):
    for path in (
        "/api/v1/diary/students",
        "/api/v1/diary/students/1/schedule",
        "/api/v1/diary/students/1/grades",
        "/api/v1/diary/students/1/homework",
        "/api/v1/diary/students/1/teachers",
    ):
        response = await client.get(path)
        assert response.status_code == 401, path


# ---- reading --------------------------------------------------------------


async def test_students_never_expose_the_upstream_handles(client, upstream):
    token = await sign_in(client, upstream)
    upstream.routes["/api/journal/person/related-child-list"] = {"items": [CHILD]}

    response = await client.get(
        "/api/v1/diary/students", headers={"Authorization": f"Bearer {token}"}
    )
    assert response.status_code == 200
    student = response.json()[0]
    assert student["full_name"] == "Иванов Пётр Сергеевич"
    assert student["class_name"] == "9А"
    # education_id and group_id are the upstream's, and a client that knew them
    # would be a client that could be pointed at another family's child.
    assert "education_id" not in student
    assert "group_id" not in student


async def test_a_student_id_that_is_not_yours_is_a_404(client, upstream):
    token = await sign_in(client, upstream)
    upstream.routes["/api/journal/person/related-child-list"] = {"items": [CHILD]}

    response = await client.get(
        "/api/v1/diary/students/999/schedule", headers={"Authorization": f"Bearer {token}"}
    )
    assert response.status_code == 404


async def test_grades_arrive_normalised_and_the_range_reaches_the_upstream(client, upstream):
    token = await sign_in(client, upstream)
    upstream.routes["/api/journal/person/related-child-list"] = {"items": [CHILD]}
    upstream.routes["/api/journal/estimate/table"] = {
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
    }

    response = await client.get(
        "/api/v1/diary/students/4021/grades?from=2026-09-07&to=2026-09-13",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    values = [(mark["value"], mark["kind"]) for mark in response.json()]
    assert values == [("5", "grade"), ("Н", "absence")]

    params = upstream.query("/api/journal/estimate/table")
    assert params["p_date_from"] == "07.09.2026"
    assert params["p_date_to"] == "13.09.2026"
    assert params["p_educations[]"] == "90210"


async def test_homework_comes_from_the_lesson_list_and_says_nothing_about_it(
    client, upstream
):
    token = await sign_in(client, upstream)
    upstream.routes["/api/journal/person/related-child-list"] = {"items": [CHILD]}
    upstream.routes["/api/journal/lesson/list-by-education"] = {
        "items": [
            {"date": "15.09.2026", "subject_name": "Алгебра", "task": "№ 42"},
            {"date": "15.09.2026", "subject_name": "История"},
        ]
    }

    response = await client.get(
        "/api/v1/diary/students/4021/homework?from=2026-09-14&to=2026-09-20",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    assert response.json() == [
        {
            "id": None,
            "due_date": "2026-09-15",
            "subject": "Алгебра",
            "text": "№ 42",
            "teacher": None,
        }
    ]
    # Their odd date-plus-time format, which is neither ISO nor a timestamp.
    assert upstream.query("/api/journal/lesson/list-by-education")["p_datetime_from"] == (
        "14.09.2026+00:00:00"
    )


@pytest.mark.parametrize(
    "window",
    ["from=2026-09-20&to=2026-09-01", "from=2026-01-01&to=2026-12-31"],
)
async def test_an_impossible_or_enormous_range_is_refused(client, upstream, window):
    token = await sign_in(client, upstream)
    upstream.routes["/api/journal/person/related-child-list"] = {"items": [CHILD]}
    response = await client.get(
        f"/api/v1/diary/students/4021/schedule?{window}",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 422


# ---- the session ending ---------------------------------------------------


async def test_a_dead_upstream_session_answers_with_a_flag_the_app_can_act_on(
    client, upstream, session
):
    """401 alone says "your token is wrong"; this one means "ask for the
    password again", and the two need different screens."""
    token = await sign_in(client, upstream)
    upstream.routes["/api/journal/person/related-child-list"] = lambda request: httpx.Response(
        401, json={"message": "Unauthorized"}
    )

    response = await client.get(
        "/api/v1/diary/students", headers={"Authorization": f"Bearer {token}"}
    )
    assert response.status_code == 401
    assert response.headers.get("X-Diary-Reauth") == "required"

    # And the row is marked, so the next request fails without a round trip.
    row = await session.scalar(select(DiarySession))
    await session.refresh(row)
    assert row.expired_at is not None

    again = await client.get(
        "/api/v1/diary/students", headers={"Authorization": f"Bearer {token}"}
    )
    assert again.status_code == 401


async def test_html_instead_of_json_is_read_as_the_session_ending(client, upstream):
    """Logged out, the upstream answers 200 with a login page. For the person
    holding the phone that is a dead session, not a parse failure."""
    token = await sign_in(client, upstream)
    upstream.routes["/api/journal/person/related-child-list"] = lambda request: httpx.Response(
        200, text="<html><body>Вход</body></html>"
    )
    response = await client.get(
        "/api/v1/diary/students", headers={"Authorization": f"Bearer {token}"}
    )
    assert response.status_code == 401
    assert response.headers.get("X-Diary-Reauth") == "required"


async def test_the_upstream_being_down_is_503_rather_than_our_fault(client, upstream):
    token = await sign_in(client, upstream)
    upstream.routes["/api/journal/person/related-child-list"] = lambda request: httpx.Response(
        502, text="Bad Gateway"
    )
    response = await client.get(
        "/api/v1/diary/students", headers={"Authorization": f"Bearer {token}"}
    )
    assert response.status_code == 503


async def test_a_refreshed_upstream_token_is_kept(client, upstream, session):
    """They hand back a newer session on most calls; dropping it is how a
    session that should live for weeks dies in a day."""
    token = await sign_in(client, upstream)

    def refreshing(request: httpx.Request) -> httpx.Response:
        response = httpx.Response(200, json={"data": {"items": [CHILD]}})
        response.headers["set-cookie"] = "X-JWT-Token=second-token; Path=/"
        return response

    upstream.routes["/api/journal/person/related-child-list"] = refreshing
    await client.get("/api/v1/diary/students", headers={"Authorization": f"Bearer {token}"})

    row = await session.scalar(select(DiarySession))
    await session.refresh(row)
    assert row.upstream_token == "second-token"


async def test_signing_out_forgets_the_session(client, upstream, session):
    token = await sign_in(client, upstream)
    response = await client.post(
        "/api/v1/diary/logout", headers={"Authorization": f"Bearer {token}"}
    )
    assert response.status_code == 204
    assert await session.scalar(select(DiarySession)) is None


async def test_the_session_cookie_is_sent_upstream_on_every_call(client, upstream):
    token = await sign_in(client, upstream)
    upstream.routes["/api/journal/person/related-child-list"] = {"items": [CHILD]}
    await client.get("/api/v1/diary/students", headers={"Authorization": f"Bearer {token}"})

    call = next(
        request
        for request in upstream.seen
        if request.url.path == "/api/journal/person/related-child-list"
    )
    assert "X-JWT-Token=cookie-token" in call.headers.get("cookie", "")


def test_the_dates_this_project_sends_are_the_ones_the_upstream_reads():
    assert provider_client.serialise_date(date(2026, 9, 7)) == "07.09.2026"
    assert provider_client.serialise_datetime_range(date(2026, 9, 7)) == "07.09.2026+00:00:00"


# ---- the one shared upstream client ---------------------------------------


async def _through_the_real_shared_client(monkeypatch, calls: list[str | None]):
    """Drives the module's own ``shared_client`` against a mock transport.

    The ``upstream`` fixture builds a fresh client per call, which is exactly
    what hides anything the shared one accumulates between them - so this one
    goes through the real constructor instead.
    """
    real = httpx.AsyncClient

    def build(**kwargs):
        return real(**kwargs, transport=httpx.MockTransport(handler))

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.headers.get("cookie"))
        return httpx.Response(
            200,
            json={"data": {"items": []}},
            headers={"set-cookie": "X-JWT-Token=refreshed-for-the-first; Path=/"},
        )

    monkeypatch.setattr(provider_client.httpx, "AsyncClient", build)
    await provider_client.close_client()


async def test_one_family_s_session_never_travels_on_another_s_request(monkeypatch):
    """The upstream refreshes its cookie on the way past every call.

    httpx keeps what it sees on the *client*, and this client is one per
    process, so without care the refreshed cookie is merged into the next
    request - whosever it is.
    """
    calls: list[str | None] = []
    await _through_the_real_shared_client(monkeypatch, calls)
    try:
        await provider_client.PetersburgClient("first-family").children()
        await provider_client.PetersburgClient("second-family").children()
    finally:
        await provider_client.close_client()

    assert calls == ["X-JWT-Token=first-family", "X-JWT-Token=second-family"]


async def test_a_login_carries_no_session_at_all(monkeypatch):
    """A login is the one call with nobody behind it yet."""
    calls: list[str | None] = []
    await _through_the_real_shared_client(monkeypatch, calls)
    try:
        await provider_client.PetersburgClient("first-family").children()
        await provider_client.PetersburgClient().login("parent@example.com", "correct")
    finally:
        await provider_client.close_client()

    assert calls[1] is None
