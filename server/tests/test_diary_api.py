"""The diary endpoints, against a fake upstream.

Nothing here touches dnevnik2.petersburgedu.ru. The provider's shared httpx
client is replaced with one driven by a hand-written transport, which is also
the only honest way to test an integration whose upstream is undocumented: the
tests pin what this project does with each answer, not what the service does.
"""

from __future__ import annotations

import json
from datetime import UTC, date, datetime, timedelta

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import select

from app.api import diary as diary_api
from app.config import get_settings
from app.db import SessionLocal
from app.main import app
from app.models import DiaryLinkCode, DiarySession
from app.providers.petersburg import client as provider_client
from app.security import hash_token
from app.services import diary as service
from app.services import diary_link

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


async def sign_in(
    client, upstream, password: str = "correct", login: str = "parent@example.com"
) -> str:
    upstream.routes[LOGIN_PATH] = with_token
    response = await client.post(
        "/api/v1/diary/login", json={"login": login, "password": password}
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
    # Ours is stored hashed, exactly like a device token. Theirs cannot be
    # hashed — it is replayed upstream on every call — so it is sealed instead,
    # and comes back only through the one function that opens one.
    assert row.upstream_token != "cookie-token"
    assert service.upstream_of(row) == "cookie-token"
    assert row.login == "parent@example.com"


@pytest.fixture
def no_diary_secret(monkeypatch):
    """Runs one test on a deployment that has no ``DIARY_SECRET``.

    ``get_settings`` is ``lru_cache``d, so patching the attribute on the one
    object every caller holds is what actually changes the answer, exactly as
    ``tests/test_diary_crypto.py`` does it.
    """
    monkeypatch.setattr(get_settings(), "diary_secret", "", raising=False)
    yield
    get_settings.cache_clear()


async def test_signing_in_without_a_key_is_refused_rather_than_crashing(
    client, upstream, no_diary_secret
):
    """No key means the diary is off, and «off» has to be an answer.

    ``app/crypto.py`` makes that refusal deliberate — a fallback to plaintext
    would be invisible — and ``services/diary.sign_in`` raises ``DiaryDisabled``
    before the password leaves the building. But ``_guard`` only knows the
    *upstream's* failures, so this one walked out of the handler: FastAPI turns
    that into a bare 500 on an endpoint anybody can POST to, with a stack trace
    per attempt and nothing for the app to show. ``POST /diary/signin`` — the
    other door onto the same service — has always answered 503 in words.
    """
    upstream.routes[LOGIN_PATH] = with_token

    response = await client.post(
        "/api/v1/diary/login", json={"login": "parent@example.com", "password": "correct"}
    )

    assert response.status_code == 503, response.text
    assert response.json()["detail"] == "Дневник на этом сервере выключен."
    # And nothing was sent upstream: refusing after the password has already
    # been handed to a third party would be the worse half of the same bug.
    assert upstream.seen == []


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
    assert service.upstream_of(row) == "cookie-token"


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
            # The key a correction for this item would be filed under, sent
            # down so the client never builds its own. Nothing is corrected
            # here, so the list is empty rather than absent.
            "target": "hw:2026-09-15:Алгебра",
            "edits": [],
            # Only one item shares this key, so the correction it would carry
            # is a correction of this item and nothing else.
            "ambiguous": False,
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


@pytest.mark.parametrize("window", ["from=9999-12-31", "from=9999-12-25&to=9999-12-31"])
async def test_a_date_at_the_end_of_the_calendar_is_refused_not_crashed(
    client, upstream, window
):
    """`from` with no `to` is `start + 14 days`, and near `date.max` that
    arithmetic raises OverflowError instead of returning a date. It reached the
    app as a 500 on a query string, where every other unusable range on this
    surface is a 422 that says what was wrong."""
    token = await sign_in(client, upstream)
    upstream.routes["/api/journal/person/related-child-list"] = {"items": [CHILD]}
    response = await client.get(
        f"/api/v1/diary/students/4021/schedule?{window}",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 422, response.text


async def test_an_ordinary_range_still_opens_its_default_window(client, upstream):
    """The bound is a bound, not a narrowing: a real school date still works
    with no `to` at all."""
    token = await sign_in(client, upstream)
    upstream.routes["/api/journal/person/related-child-list"] = {"items": [CHILD]}
    upstream.routes[SCHEDULE_PATH] = {"items": []}
    response = await client.get(
        "/api/v1/diary/students/4021/schedule?from=2026-09-07",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200, response.text


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
    assert service.upstream_of(row) == "second-token"
    assert row.upstream_token != "second-token"


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


def test_the_diary_s_today_is_the_city_s_today_not_the_server_s(monkeypatch):
    """Vercel runs in UTC; the diary is Saint Petersburg's, three hours ahead.

    Between nine in the evening and midnight there the two dates disagree, and
    that is exactly the stretch a pupil is most likely to be looking at
    tomorrow's lessons in. `date.today()` - the server's own clock - handed
    them a window that opened yesterday.
    """

    class FrozenJustAfterMidnightInMoscow(datetime):
        @classmethod
        def now(cls, tz=None):
            # 21:30 UTC on the 7th is 00:30 on the 8th in Moscow. The two
            # calendars disagree, which is the only instant worth testing.
            moment = datetime(2026, 9, 7, 21, 30, tzinfo=UTC)
            return moment.astimezone(tz) if tz is not None else moment

    monkeypatch.setattr(provider_client, "datetime", FrozenJustAfterMidnightInMoscow)

    assert provider_client.today() == date(2026, 9, 8)
    # And the window the API opens by default follows it, rather than the
    # server's date, which is still the 7th at that moment.
    start, _ = diary_api._range(None, None)
    assert start == date(2026, 9, 8)


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


# ---- corrections ----------------------------------------------------------
#
# The diary is read-only upstream and stays that way: what these pin is that a
# family's correction is stored here, laid over the answer on the way out, and
# can be taken back off again.

#: The timetable comes from its own endpoint; homework is pulled out of the
#: *lesson* list, which is a different path with a different date format.
SCHEDULE_PATH = "/api/journal/schedule/list-by-education"


def a_lesson(**fields) -> dict:
    return {
        "date": "15.09.2026",
        "subject_name": "Алгебра",
        "number": 1,
        "office": "12",
    } | fields


async def read_schedule(client, token: str) -> list[dict]:
    response = await client.get(
        "/api/v1/diary/students/4021/schedule?from=2026-09-14&to=2026-09-20",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200, response.text
    return response.json()


async def signed_in_with_a_lesson(client, upstream) -> str:
    token = await sign_in(client, upstream)
    upstream.routes["/api/journal/person/related-child-list"] = {"items": [CHILD]}
    upstream.routes[SCHEDULE_PATH] = {"items": [a_lesson()]}
    return token


async def test_a_correction_shows_up_in_the_next_read(client, upstream):
    token = await signed_in_with_a_lesson(client, upstream)
    target = (await read_schedule(client, token))[0]["target"]

    written = await client.put(
        "/api/v1/diary/students/4021/overrides",
        headers={"Authorization": f"Bearer {token}"},
        json={"target": target, "field": "room", "value": "204", "original": "12"},
    )
    assert written.status_code == 200, written.text

    lesson = (await read_schedule(client, token))[0]
    assert lesson["room"] == "204"
    assert lesson["edits"] == [
        {
            "field": "room",
            "value": "204",
            "original": "12",
            "changed_upstream": False,
        }
    ]


async def test_resetting_gives_the_diary_its_answer_back(client, upstream):
    token = await signed_in_with_a_lesson(client, upstream)
    target = (await read_schedule(client, token))[0]["target"]
    await client.put(
        "/api/v1/diary/students/4021/overrides",
        headers={"Authorization": f"Bearer {token}"},
        json={"target": target, "field": "room", "value": "204"},
    )

    reset = await client.post(
        "/api/v1/diary/students/4021/overrides/reset",
        headers={"Authorization": f"Bearer {token}"},
        json={"target": target, "field": "room"},
    )
    assert reset.status_code == 204

    lesson = (await read_schedule(client, token))[0]
    assert lesson["room"] == "12"
    assert lesson["edits"] == []


async def test_resetting_something_that_was_never_corrected_is_not_an_error(
    client, upstream
):
    """The caller asked for "no correction here" and that is the state; a 404
    would make the client show a failure for having got what it wanted."""
    token = await signed_in_with_a_lesson(client, upstream)
    target = (await read_schedule(client, token))[0]["target"]

    response = await client.post(
        "/api/v1/diary/students/4021/overrides/reset",
        headers={"Authorization": f"Bearer {token}"},
        json={"target": target, "field": "topic"},
    )
    assert response.status_code == 204


async def test_resetting_everything_clears_the_lot(client, upstream):
    token = await signed_in_with_a_lesson(client, upstream)
    target = (await read_schedule(client, token))[0]["target"]
    for field, value in (("room", "204"), ("teacher", "Иванова И. И.")):
        await client.put(
            "/api/v1/diary/students/4021/overrides",
            headers={"Authorization": f"Bearer {token}"},
            json={"target": target, "field": field, "value": value},
        )

    listed = await client.get(
        "/api/v1/diary/students/4021/overrides",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert len(listed.json()) == 2

    dropped = await client.delete(
        "/api/v1/diary/students/4021/overrides/all",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert dropped.status_code == 204

    lesson = (await read_schedule(client, token))[0]
    assert lesson["room"] == "12"
    assert lesson["edits"] == []


async def test_corrections_survive_signing_out_and_back_in(client, upstream, session):
    """The whole point of keying them on the account rather than the session.

    The upstream token dies every few days and the row goes with it; a
    correction that went too would already be gone by the time anybody pressed
    «сбросить», silently, and the button would look broken.
    """
    token = await signed_in_with_a_lesson(client, upstream)
    target = (await read_schedule(client, token))[0]["target"]
    await client.put(
        "/api/v1/diary/students/4021/overrides",
        headers={"Authorization": f"Bearer {token}"},
        json={"target": target, "field": "room", "value": "204"},
    )

    await client.post(
        "/api/v1/diary/logout", headers={"Authorization": f"Bearer {token}"}
    )
    again = await sign_in(client, upstream)
    assert again != token

    lesson = (await read_schedule(client, again))[0]
    assert lesson["room"] == "204"


async def test_correcting_a_second_time_replaces_rather_than_piles_up(client, upstream):
    token = await signed_in_with_a_lesson(client, upstream)
    target = (await read_schedule(client, token))[0]["target"]
    for value in ("204", "301"):
        await client.put(
            "/api/v1/diary/students/4021/overrides",
            headers={"Authorization": f"Bearer {token}"},
            json={"target": target, "field": "room", "value": value},
        )

    listed = await client.get(
        "/api/v1/diary/students/4021/overrides",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert [row["value"] for row in listed.json()] == ["301"]


@pytest.mark.parametrize(
    ("target", "field"),
    [
        ("mark:id:5", "value"),
        ("lesson:2026-09-15:n1:Алгебра", "subject"),
        ("nonsense", "text"),
    ],
)
async def test_a_correction_the_read_path_could_never_apply_is_refused(
    client, upstream, target, field
):
    """A stored row no read path can match would look like a correction
    somebody made, with no way to reset it: the button that resets one only
    appears next to the value it changed."""
    token = await signed_in_with_a_lesson(client, upstream)
    response = await client.put(
        "/api/v1/diary/students/4021/overrides",
        headers={"Authorization": f"Bearer {token}"},
        json={"target": target, "field": field, "value": "x"},
    )
    assert response.status_code == 422, response.text


async def test_corrections_for_another_family_child_are_not_reachable(client, upstream):
    token = await signed_in_with_a_lesson(client, upstream)
    response = await client.put(
        "/api/v1/diary/students/999/overrides",
        headers={"Authorization": f"Bearer {token}"},
        json={"target": "hw:id:1", "field": "text", "value": "x"},
    )
    assert response.status_code == 404


async def test_the_correction_endpoints_need_a_bearer(client):
    paths = [
        ("get", "/api/v1/diary/students/1/overrides"),
        ("put", "/api/v1/diary/students/1/overrides"),
        ("post", "/api/v1/diary/students/1/overrides/reset"),
        ("delete", "/api/v1/diary/students/1/overrides/all"),
    ]
    for method, path in paths:
        call = getattr(client, method)
        response = (
            await call(path, json={}) if method in ("put", "post") else await call(path)
        )
        assert response.status_code == 401, path


async def test_corrections_are_private_to_the_account_that_wrote_them(client, upstream):
    """Two parents of one child sign in with their own upstream accounts. The
    key is (login, student), so each set of corrections is theirs — and the
    fixture gives both accounts the same child, which is the only way this
    property can actually be observed."""
    mine = await signed_in_with_a_lesson(client, upstream)
    target = (await read_schedule(client, mine))[0]["target"]
    await client.put(
        "/api/v1/diary/students/4021/overrides",
        headers={"Authorization": f"Bearer {mine}"},
        json={"target": target, "field": "room", "value": "204"},
    )

    theirs = await sign_in(client, upstream, login="other@example.com")

    listed = await client.get(
        "/api/v1/diary/students/4021/overrides",
        headers={"Authorization": f"Bearer {theirs}"},
    )
    assert listed.json() == []
    assert (await read_schedule(client, theirs))[0]["room"] == "12"
    # …and mine are still mine.
    assert (await read_schedule(client, mine))[0]["room"] == "204"


async def test_a_login_typed_with_different_capitals_finds_its_corrections(
    client, upstream
):
    """The upstream does not care about the case, so neither may we: a family
    whose keyboard capitalises the first letter must not find every correction
    gone, with no reset button because there is nothing left to reset."""
    first = await signed_in_with_a_lesson(client, upstream)
    target = (await read_schedule(client, first))[0]["target"]
    await client.put(
        "/api/v1/diary/students/4021/overrides",
        headers={"Authorization": f"Bearer {first}"},
        json={"target": target, "field": "room", "value": "204"},
    )

    again = await sign_in(client, upstream, login="Parent@Example.com")

    assert (await read_schedule(client, again))[0]["room"] == "204"


async def test_the_diary_moving_underneath_a_correction_is_reported(client, upstream):
    token = await sign_in(client, upstream)
    upstream.routes["/api/journal/person/related-child-list"] = {"items": [CHILD]}
    upstream.routes[SCHEDULE_PATH] = {"items": [a_lesson(office="12")]}
    target = (await read_schedule(client, token))[0]["target"]
    await client.put(
        "/api/v1/diary/students/4021/overrides",
        headers={"Authorization": f"Bearer {token}"},
        json={"target": target, "field": "room", "value": "204", "original": "12"},
    )

    # The school moves the lesson. The correction is not thrown away for the
    # person, but it stops being silent about what it is covering.
    upstream.routes[SCHEDULE_PATH] = {"items": [a_lesson(office="301")]}
    lesson = (await read_schedule(client, token))[0]

    assert lesson["room"] == "204"
    assert lesson["edits"][0]["changed_upstream"] is True
    assert lesson["edits"][0]["original"] == "301"


async def test_a_homework_text_cannot_be_emptied(client, upstream):
    """It would take the row off the list, and the correction with it."""
    token = await signed_in_with_a_lesson(client, upstream)
    response = await client.put(
        "/api/v1/diary/students/4021/overrides",
        headers={"Authorization": f"Bearer {token}"},
        json={"target": "hw:id:77", "field": "text", "value": "   "},
    )
    assert response.status_code == 422


async def test_a_well_prefixed_but_malformed_target_is_refused(client, upstream):
    """The prefix is not the check: a key is matched by string equality, so
    `lesson:x` could only ever be a row nothing applies."""
    token = await signed_in_with_a_lesson(client, upstream)
    response = await client.put(
        "/api/v1/diary/students/4021/overrides",
        headers={"Authorization": f"Bearer {token}"},
        json={"target": "lesson:x", "field": "room", "value": "204"},
    )
    assert response.status_code == 422


async def test_a_refresh_that_fails_does_not_replace_the_answer_it_was_helping():
    """The un-expiring refresh after a rollback may not raise. Ever.

    It is the last statement of two `except` blocks that exist so a failed
    write does not fail the read — and it is a `SELECT` down the connection the
    commit just lost, so when the commit fails it usually fails too. In
    `_expire` the block it would hijack is on its way to re-raising
    `SessionExpired`, which the route turns into `401` with
    `X-Diary-Reauth: required`: the one signal the app has for «спросите пароль
    заново». A 500 in its place costs the family the sign-in prompt on the
    exact path whose whole job is to ask for it.
    """

    class RefusesToRefresh:
        async def refresh(self, _row):
            raise RuntimeError("the connection is gone")

    row = DiarySession(login="parent@example.com", token_hash="x", upstream_token="y")

    # No exception, and none swallowed silently either — it is logged.
    await service._refresh_quietly(RefusesToRefresh(), row)


# --------------------------------------------------------------------------
# The ticket that carries a person from the chat to the sign-in form.
# --------------------------------------------------------------------------


async def test_a_ticket_two_requests_hold_at_once_is_still_spent_once(session, school_class):
    """Two `POST /diary/signin/{code}` on one link, and only one gets in.

    `claim` used to read the row, check it in Python and then assign
    ``used_at``, which answers the request that arrives after the ticket was
    spent and not the one that arrives *while* it is being spent. Both read the
    same live row, both passed the check, and both wrote it: one link, two diary
    sessions on one account, and the second of them signed in with whatever
    credentials the second form carried. `device_invites.burn` had the right
    shape for this the whole time - a conditional UPDATE the database decides.
    """
    code = await diary_link.mint(session, telegram_id=42, class_id=school_class.id)

    async with SessionLocal() as overlapping:
        # The second request read the ticket a moment ago and is still parsing
        # its form. The row it holds is live.
        held = await overlapping.scalar(
            select(DiaryLinkCode).where(DiaryLinkCode.code_hash == hash_token(code))
        )
        assert held is not None and held.used_at is None

        # The first request reaches the same ticket and spends it.
        spent = await diary_link.claim(session, code)
        assert spent is not None
        # The caller renders the row afterwards, so what it carries has to
        # survive the statement that spent it.
        assert (spent.telegram_id, spent.class_id) == (42, school_class.id)

        # The second request now writes. What decides is the database, not the
        # row this request is still holding in memory.
        assert await diary_link.claim(overlapping, code) is None, (
            "one link is worth one sign-in; the loser is told the link is used"
        )

    async with SessionLocal() as after:
        rows = list(await after.scalars(select(DiaryLinkCode)))
    assert len(rows) == 1
    assert rows[0].used_at is not None


async def test_a_ticket_out_of_time_is_refused_without_being_marked_used(session, school_class):
    """Expiry moved into the statement, and it must not spend the row on its way.

    The refusal is the same sentence on the page either way, but the row is the
    record: a timed-out ticket marked used reads as «кто-то вошёл» when nobody
    did.
    """
    code = await diary_link.mint(session, telegram_id=42, class_id=school_class.id)
    row = await session.scalar(
        select(DiaryLinkCode).where(DiaryLinkCode.code_hash == hash_token(code))
    )
    assert row is not None
    row.expires_at = diary_link.utcnow() - timedelta(minutes=1)
    await session.commit()

    assert await diary_link.claim(session, code) is None
    async with SessionLocal() as after:
        stale = await after.scalar(
            select(DiaryLinkCode).where(DiaryLinkCode.code_hash == hash_token(code))
        )
    assert stale is not None and stale.used_at is None
