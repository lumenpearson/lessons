"""The corrections over the diary belong to the child (#165).

The owner's decision of 26 September 2026: everyone whose **own** diary lists a
child sees and edits that child's corrections — both parents, the pupil's own
account — and the login a phone names at ``POST /api/v1/diary/session`` plays no
part, because nothing upstream vouches for it. What this file pins:

- the key is the diary's server and the pupil's id on it, so one number on two
  servers is two children, and a child listed by a number nothing says is one
  person's gets no corrections at all;
- a login — copied from another family, or spelled like a scope — reaches
  exactly what the session's own diary lists and nothing else;
- a reset for one child reaches everybody's corrections for that child, and no
  other child's;
- no scope can ever equal a key the code before this change wrote, so neither
  side can read or write the other's rows.

Nothing here reaches a real diary: each account's pupils are answered by a fake
upstream that reads which session is asking, the way the real one does.
"""

from __future__ import annotations

from urllib.parse import unquote

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import select

from app.main import app
from app.models import DiaryOverride, DiarySession
from app.providers.diary import http as diary_http
from app.providers.diary.registry import NETSCHOOL, PETERSBURG
from app.providers.netschool import client as nsclient
from app.providers.netschool import regions
from app.providers.petersburg import client as pbclient
from app.services import diary as service

CHILDREN_PATH = "/api/journal/person/related-child-list"
SCHEDULE_PATH = "/api/journal/schedule/list-by-education"
LESSON_TARGET = "lesson:2026-09-15:n1:Алгебра"
WEEK = "from=2026-09-14&to=2026-09-20"

#: Three Petersburg accounts: three sessions the diary handed three phones.
JWT_A = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJBIn0.account-a-signature"
JWT_B = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJCIn0.account-b-signature"
JWT_C = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJDIn0.account-c-signature"


def a_child(person: int | None, *, plain_id: int | None = None, education: int = 90210) -> dict:
    """A related-child-list item: by ``identity.id``, or — the fallback — by a
    plain ``id`` with no identity at all."""
    item: dict = {
        "firstname": "Пётр",
        "surname": "Иванов",
        "educations": [{"education_id": education, "group_id": 771, "group_name": "9А"}],
    }
    if person is not None:
        item["identity"] = {"id": person}
    if plain_id is not None:
        item["id"] = plain_id
    return item


class _Petersburg:
    """Petersburg's server: each session's own pupils, and one lesson a week."""

    def __init__(self) -> None:
        self.children: dict[str, list[dict]] = {}

    def handler(self, request: httpx.Request) -> httpx.Response:
        cookies = dict(
            part.strip().split("=", 1)
            for part in request.headers.get("cookie", "").split(";")
            if "=" in part
        )
        token = unquote(cookies.get("X-JWT-Token", ""))
        if request.url.path == CHILDREN_PATH:
            return httpx.Response(200, json={"data": {"items": self.children.get(token, [])}})
        if request.url.path == SCHEDULE_PATH:
            lesson = {"date": "15.09.2026", "subject_name": "Алгебра", "number": 1, "office": "12"}
            return httpx.Response(200, json={"data": {"items": [lesson]}})
        return httpx.Response(404, json={"message": "no such path"})


class _NetSchool:
    """Every «Сетевой город» server at once: the bootstrap, and each session's
    pupils by its ``at``. Which server was asked is on the request's host."""

    def __init__(self) -> None:
        self.pupils: dict[str, list[int]] = {}

    def handler(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/webapi/student/diary/init":
            ids = self.pupils.get(request.headers.get("at", ""), [])
            return httpx.Response(200, json={"students": [
                {"studentId": sid, "nickName": "Иванов Иван", "classId": 3} for sid in ids
            ]})
        if path == "/webapi/years/current":
            return httpx.Response(
                200, json={"id": 2026, "startDate": "2026-09-01", "endDate": "2027-05-31"}
            )
        if path == "/webapi/context":
            return httpx.Response(200, json={"organizationName": "Гимназия № 7"})
        if path == "/webapi/grade/assignment/types":
            return httpx.Response(200, json=[{"id": 3, "name": "Домашнее задание"}])
        return httpx.Response(404, json={})


@pytest.fixture
async def client():
    async with httpx.AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        yield ac


@pytest.fixture
def petersburg(monkeypatch) -> _Petersburg:
    fake = _Petersburg()

    async def shared():
        return httpx.AsyncClient(
            base_url=pbclient.BASE_URL, transport=httpx.MockTransport(fake.handler)
        )

    monkeypatch.setattr(pbclient, "shared_client", shared)
    return fake


@pytest.fixture
def netschool(monkeypatch) -> _NetSchool:
    fake = _NetSchool()

    async def shared():
        return httpx.AsyncClient(
            transport=httpx.MockTransport(fake.handler), cookies=diary_http.NoCookieJar()
        )

    monkeypatch.setattr(nsclient, "shared_client", shared)
    return fake


async def register_petersburg(client, jwt: str, login: str) -> dict[str, str]:
    response = await client.post(
        "/api/v1/diary/session",
        json={"provider": "petersburg", "login": login, "credential": {"token": jwt}},
    )
    assert response.status_code == 200, response.text
    return {"Authorization": f"Bearer {response.json()['token']}"}


async def register_netschool(client, at: str, login: str, region: str) -> dict[str, str]:
    response = await client.post(
        "/api/v1/diary/session",
        json={
            "provider": "netschool",
            "login": login,
            "region": region,
            "school_id": 42,
            "credential": {"at": at, "cookies": {"NSSESSIONID": f"sess-{at}"}},
        },
    )
    assert response.status_code == 200, response.text
    return {"Authorization": f"Bearer {response.json()['token']}"}


def overrides(student: int) -> str:
    return f"/api/v1/diary/students/{student}/overrides"


async def write(client, headers, student: int, value: str, target: str = LESSON_TARGET,
                field: str = "room") -> None:
    written = await client.put(
        overrides(student), headers=headers,
        json={"target": target, "field": field, "value": value, "original": "12"},
    )
    assert written.status_code == 200, written.text


async def values(client, headers, student: int) -> list[str]:
    listed = await client.get(overrides(student), headers=headers)
    assert listed.status_code == 200, listed.text
    return [row["value"] for row in listed.json()]


async def room(client, headers, student: int) -> str:
    lessons = await client.get(
        f"/api/v1/diary/students/{student}/schedule?{WEEK}", headers=headers
    )
    assert lessons.status_code == 200, lessons.text
    return lessons.json()[0]["room"]


# ---- the scope -------------------------------------------------------------


def _every_scope() -> list[str]:
    scopes = [service.child_scope(None, None), service.child_scope(PETERSBURG, None)]
    for region in regions.listed(password_only=False):
        scopes.append(service.child_scope(NETSCHOOL, region.key))
    return scopes


def test_a_scope_names_the_diary_s_server_and_nothing_a_phone_typed():
    assert service.child_scope(None, None) == "CHILD:petersburg"
    assert service.child_scope(PETERSBURG, None) == "CHILD:petersburg"
    assert service.child_scope(NETSCHOOL, "zabaikalsky") == "CHILD:netschool:region.zabedu.ru"
    # The host with its port, as the session's own calls reach it.
    assert (
        service.child_scope(NETSCHOOL, "sakhalin")
        == "CHILD:netschool:netcity.admsakhalin.ru:11111"
    )
    # Every server is its own scope; the only two that coincide are
    # Petersburg's two spellings, NULL and its name.
    scopes = _every_scope()
    assert len(set(scopes)) == len(scopes) - 1
    assert all(len(scope) <= DiaryOverride.__table__.c.login.type.length for scope in scopes)


@pytest.mark.parametrize(
    "login",
    [
        "parent@example.com",
        "CHILD:petersburg",
        "child:petersburg",
        "  Child:Petersburg  ",
        "CHILD:netschool:region.zabedu.ru",
    ],
)
def test_no_scope_is_the_key_any_login_ever_made(login):
    """The key before this change was ``login.strip().casefold()`` (or a
    lower-case hash), and a scope starts in upper case — so no login, even one
    typed to look like a scope, lands on a per-child row under the old code,
    and no per-child row is ever read as somebody's login under the new."""
    legacy = login.strip().casefold()
    for scope in _every_scope():
        assert scope != legacy
        # The general form: a casefolded string is its own casefold, a scope never is.
        assert scope != scope.casefold()


def test_a_region_the_allow_list_does_not_hold_has_no_scope():
    """Refused, never guessed: a fallback would file one server's pupils beside
    another's under one number."""
    for provider, region in ((NETSCHOOL, "atlantis"), (NETSCHOOL, None), ("elsewhere", None)):
        with pytest.raises(service.UnknownDiaryServer):
            service.child_scope(provider, region)


def test_the_scope_follows_the_server_not_the_region_key(monkeypatch):
    """Two regions on one server share its numbering, and a region whose
    origin moves stops matching its old corrections — lost, rather than laid
    over whichever child has the same number on the new server."""
    zabaikalsky = regions.get("zabaikalsky")
    twin = regions.Region("twin", "Двойник", "https://REGION.zabedu.ru", "Asia/Chita")
    monkeypatch.setitem(regions._BY_KEY, "twin", twin)
    assert service.child_scope(NETSCHOOL, "twin") == service.child_scope(NETSCHOOL, "zabaikalsky")

    before = service.child_scope(NETSCHOOL, "zabaikalsky")
    moved = regions.Region("zabaikalsky", zabaikalsky.title, "https://sgo.zabedu.ru",
                           zabaikalsky.zone)
    monkeypatch.setitem(regions._BY_KEY, "zabaikalsky", moved)
    assert service.child_scope(NETSCHOOL, "zabaikalsky") != before


async def test_the_store_refuses_a_login_where_a_scope_belongs(session):
    """A login passed where a scope belongs is #165 again, and it would
    type-check: so the store refuses it rather than filing under it."""
    for call in (
        lambda: service.list_overrides(session, "parent@example.com", 4021),
        lambda: service.load_corrections(session, "parent@example.com", 4021),
        lambda: service.drop_overrides(session, "child:petersburg", 4021),
    ):
        with pytest.raises(ValueError, match="not under a login"):
            await call()
    assert await service.list_overrides(session, "CHILD:petersburg", 4021) == []


# ---- who reaches a child ---------------------------------------------------


async def test_a_login_copied_from_another_family_reaches_only_its_own_diary(
    client, petersburg
):
    """#165. B's phone registers B's own session and names A's login — or a
    login spelled like a scope. B reaches exactly the pupils B's own diary
    lists: every door onto A's child is a 404, A's correction survives B's
    attempts at it, and what B sees of its own child does not depend on which
    login B named."""
    petersburg.children = {JWT_A: [a_child(4021)], JWT_B: [a_child(5055, education=90555)]}
    a = await register_petersburg(client, JWT_A, "parent@example.com")
    await write(client, a, 4021, "204")

    for login in ("parent@example.com", "CHILD:petersburg", "child:petersburg"):
        b = await register_petersburg(client, JWT_B, login)
        listed = await client.get("/api/v1/diary/students", headers=b)
        assert [pupil["id"] for pupil in listed.json()] == [5055]

        refusals = [
            await client.get(f"/api/v1/diary/students/4021/schedule?{WEEK}", headers=b),
            await client.get(f"/api/v1/diary/students/4021/homework?{WEEK}", headers=b),
            await client.get(overrides(4021), headers=b),
            await client.put(overrides(4021), headers=b, json={
                "target": LESSON_TARGET, "field": "room", "value": "666"}),
            await client.post(f"{overrides(4021)}/reset", headers=b, json={
                "target": LESSON_TARGET, "field": "room"}),
            await client.delete(f"{overrides(4021)}/all", headers=b),
        ]
        assert [answer.status_code for answer in refusals] == [404] * 6, login
        assert await values(client, b, 5055) == []

    assert await values(client, a, 4021) == ["204"]
    assert await room(client, a, 4021) == "204"

    # The login plays no part in what B sees of B's own child either: written
    # under A's login, read under B's own.
    as_a = await register_petersburg(client, JWT_B, "parent@example.com")
    await write(client, as_a, 5055, "301")
    as_b = await register_petersburg(client, JWT_B, "b@example.com")
    assert await values(client, as_b, 5055) == ["301"]
    # And A, whose diary does not list 5055, cannot reach it at all.
    assert (await client.get(overrides(5055), headers=a)).status_code == 404


async def test_a_child_found_by_a_plain_id_gets_no_corrections_at_all(
    client, petersburg, session
):
    """`identity_id` falls back to a plain ``id`` when there is no identity,
    and nothing says what that numbers. With the login out of the key, the
    number would be all that told two families' children apart: B and C each
    list a different child as plain 4021, and a shared scope would let either
    read, overwrite and reset the other's. So such a child fails closed — its
    diary as it came, nothing listed, a write refused with the 422 the app
    reads as «Это поле нельзя исправить», a reset with nothing to take off —
    and none of it reaches A's child, whose person id is 4021."""
    petersburg.children = {
        JWT_A: [a_child(4021)],
        JWT_B: [a_child(None, plain_id=4021, education=90555)],
        JWT_C: [a_child(None, plain_id=4021, education=90777)],
    }
    a = await register_petersburg(client, JWT_A, "parent@example.com")
    await write(client, a, 4021, "204")

    for jwt in (JWT_B, JWT_C):
        other = await register_petersburg(client, jwt, "parent@example.com")
        refused = await client.put(overrides(4021), headers=other, json={
            "target": LESSON_TARGET, "field": "room", "value": "999", "original": "12"})
        assert refused.status_code == 422, refused.text
        assert refused.json()["detail"] == "Для этого ученика правки недоступны"
        assert await values(client, other, 4021) == []
        assert await room(client, other, 4021) == "12"
        resets = [
            await client.post(f"{overrides(4021)}/reset", headers=other, json={
                "target": LESSON_TARGET, "field": "room"}),
            await client.delete(f"{overrides(4021)}/all", headers=other),
        ]
        assert [answer.status_code for answer in resets] == [204, 204]

    stored = await session.scalars(select(DiaryOverride))
    assert [(row.login, row.student_id, row.value) for row in stored] == [
        ("CHILD:petersburg", 4021, "204")
    ]
    assert await values(client, a, 4021) == ["204"]


async def test_one_pupil_number_on_two_servers_is_two_children(client, petersburg, netschool):
    """A «Сетевой город» pupil id is numbered per regional server: pupil 11 in
    Забайкалье is not pupil 11 in Приамурье, nor the Petersburg person whose
    identity is 11. On one server it is one child, whichever parent asks."""
    netschool.pupils = {"at-zab-mother-001": [11], "at-zab-father-002": [11],
                        "at-amur-00000003": [11]}
    petersburg.children = {JWT_C: [a_child(11)]}

    mother = await register_netschool(client, "at-zab-mother-001", "ivanova", "zabaikalsky")
    await write(client, mother, 11, "из Забайкалья", target="hw:id:77", field="text")

    father = await register_netschool(client, "at-zab-father-002", "ivanov", "zabaikalsky")
    assert await values(client, father, 11) == ["из Забайкалья"]

    amur = await register_netschool(client, "at-amur-00000003", "ivanova", "amur")
    assert await values(client, amur, 11) == []
    await write(client, amur, 11, "из Приамурья", target="hw:id:77", field="text")

    city = await register_petersburg(client, JWT_C, "ivanova")
    assert await values(client, city, 11) == []

    assert await values(client, mother, 11) == ["из Забайкалья"]
    assert await values(client, amur, 11) == ["из Приамурья"]


async def test_resetting_everything_for_one_child_reaches_everyone_and_no_other_child(
    client, petersburg
):
    """The reset is the child's too: the other parent's «сбросить всё» takes
    off what this parent wrote for that child — and nothing of the sibling's,
    because the delete is bounded by the pupil."""
    siblings = [a_child(4021), a_child(4022, education=90222)]
    petersburg.children = {JWT_A: siblings, JWT_B: siblings}
    a = await register_petersburg(client, JWT_A, "mother@example.com")
    await write(client, a, 4021, "204")
    await write(client, a, 4022, "305")

    b = await register_petersburg(client, JWT_B, "father@example.com")
    dropped = await client.delete(f"{overrides(4021)}/all", headers=b)
    assert dropped.status_code == 204

    assert await values(client, a, 4021) == []
    assert await values(client, a, 4022) == ["305"]


async def test_a_row_filed_under_a_login_is_read_by_nobody(client, petersburg, session):
    """What revision 0017 re-files: until it runs, a row keyed by a login is
    invisible to every session — including one that names that very login,
    and one whose login is spelled like the scope."""
    session.add_all([
        DiaryOverride(login="parent@example.com", student_id=4021, target=LESSON_TARGET,
                      field="room", value="legacy"),
        DiaryOverride(login="child:petersburg", student_id=4021, target=LESSON_TARGET,
                      field="topic", value="legacy"),
    ])
    await session.commit()
    petersburg.children = {JWT_A: [a_child(4021)]}

    for login in ("parent@example.com", "CHILD:petersburg"):
        a = await register_petersburg(client, JWT_A, login)
        assert await values(client, a, 4021) == []
        assert await room(client, a, 4021) == "12"


async def test_a_session_whose_region_names_no_server_is_sent_to_sign_in_again(
    client, netschool, session
):
    """A «Сетевой город» row the allow-list cannot place gets no scope and no
    fallback: it is expired, and the route says «войдите заново», the way an
    unreadable credential does."""
    netschool.pupils = {"at-zab-mother-001": [11]}
    headers = await register_netschool(client, "at-zab-mother-001", "ivanova", "zabaikalsky")
    row = await session.scalar(select(DiarySession))
    row.region = "atlantis"
    await session.commit()

    response = await client.get(overrides(11), headers=headers)

    assert response.status_code == 401, response.text
    assert response.headers["X-Diary-Reauth"] == "required"
    await session.refresh(row)
    assert row.expired_at is not None
