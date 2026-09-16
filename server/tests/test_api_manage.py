"""The management surface: `/api/v1/manage`.

Each endpoint is checked three ways - it does the thing, it refuses the wrong
role, and it behaves at the edge that would otherwise cost somebody their data:
a rename that collides, an import that would overwrite a day, a phone that
revokes itself.

Everything runs through the real ASGI app against the SQLite test database.
Telegram is stubbed where the app would send.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, time, timedelta
from typing import Any

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import event, func, select, text
from sqlalchemy import update as sa_update

from app.api import manage
from app.config import get_settings
from app.db import SessionLocal, engine
from app.fsm_storage import FsmRecord  # noqa: F401 - registers fsm_states before create_all
from app.main import app
from app.models import (
    AccessRequest,
    AuditEntry,
    BellPeriod,
    BellSchedule,
    BotUser,
    DayOverride,
    DeviceToken,
    Homework,
    JoinMode,
    LessonOverride,
    OverrideAction,
    Role,
    SchoolClass,
    Subject,
    TimetableEntry,
)
from app.services import linking

MONDAY = date(2026, 9, 7)

OWNER_ID = 1000  # OWNER_IDS in conftest: an environment owner, never granted in-bot
ADMIN_ID = 6001
EDITOR_ID = 6002
VIEWER_ID = 6003
ASKER_ID = 6004


@dataclass
class FakeBot:
    """Records what the app tried to send."""

    sent: list[tuple[int, str]] = field(default_factory=list)
    closed: bool = False

    async def send_message(self, chat_id: int, text: str, **_: Any) -> None:
        self.sent.append((chat_id, text))

    @property
    def session(self) -> FakeBot:
        return self

    async def close(self) -> None:
        self.closed = True


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest.fixture
def recording_bot(monkeypatch):
    """Give the decision endpoints a bot, and record what it sends."""
    bot = FakeBot()
    monkeypatch.setattr(get_settings(), "bot_token", "123456:TEST")
    monkeypatch.setattr(manage, "_build_bot", lambda: bot)
    return bot


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _token(client, device_name: str = "pytest") -> str:
    response = await client.post(
        "/api/v1/join", json={"code": "TEST42", "device_name": device_name}
    )
    assert response.status_code == 200, response.text
    return response.json()["token"]


async def _linked_token(
    client, session, school_class, telegram_id: int, role: Role | None, name: str = "pytest"
) -> str:
    """A phone linked to an account holding ``role`` - the way a person does
    it: read the code off /me, type it into the bot."""
    if role is not None:
        session.add(BotUser(telegram_id=telegram_id, class_id=school_class.id, role=role))
        await session.commit()
    token = await _token(client, name)
    me = await client.get("/api/v1/me", headers=_auth(token))
    assert await linking.link_device(session, me.json()["link_code"], telegram_id) is not None
    return token


async def _admin(client, session, school_class) -> str:
    return await _linked_token(client, session, school_class, ADMIN_ID, Role.ADMIN)


async def _audit(session, school_class) -> list[AuditEntry]:
    rows = await session.scalars(
        select(AuditEntry).where(AuditEntry.class_id == school_class.id).order_by(AuditEntry.id)
    )
    return list(rows)


async def _actions(session, school_class) -> list[str]:
    return [row.action for row in await _audit(session, school_class)]


# --------------------------------------------------------------------------
# Who may reach what
# --------------------------------------------------------------------------

#: Every endpoint, with the role it demands. The bodies are valid so that a
#: refusal is certainly about the role and not about the payload.
ENDPOINTS: list[tuple[str, str, dict | None, str]] = [
    ("GET", "/api/v1/manage/class", None, "admin"),
    ("PATCH", "/api/v1/manage/class", {"name": "9Б"}, "admin"),
    ("DELETE", "/api/v1/manage/class", {"confirm_name": "9А"}, "owner"),
    ("GET", "/api/v1/manage/subjects", None, "editor"),
    ("POST", "/api/v1/manage/subjects", {"name": "Химия"}, "admin"),
    ("PATCH", "/api/v1/manage/subjects/1", {"teacher": "Иванова"}, "admin"),
    ("DELETE", "/api/v1/manage/subjects/1", None, "admin"),
    ("GET", "/api/v1/manage/bells", None, "admin"),
    ("POST", "/api/v1/manage/bells", {"name": "Сокращённое"}, "admin"),
    ("PATCH", "/api/v1/manage/bells/1", {"name": "Другое"}, "admin"),
    (
        "PUT",
        "/api/v1/manage/bells/1/periods",
        {"periods": [{"index": 1, "starts_at": "09:00", "ends_at": "09:40"}]},
        "admin",
    ),
    ("DELETE", "/api/v1/manage/bells/1", None, "admin"),
    ("GET", "/api/v1/manage/timetable", None, "admin"),
    ("POST", "/api/v1/manage/timetable/import", {"text": "== Вторник ==\n1. Химия"}, "admin"),
    ("GET", "/api/v1/manage/devices", None, "admin"),
    ("POST", "/api/v1/manage/devices/1/revoke", None, "admin"),
    ("POST", "/api/v1/manage/devices/1/unlink", None, "admin"),
    ("GET", "/api/v1/manage/log", None, "admin"),
    ("GET", "/api/v1/manage/stats", None, "editor"),
    ("GET", "/api/v1/manage/requests", None, "admin"),
    ("POST", "/api/v1/manage/requests/1/approve", None, "admin"),
    ("POST", "/api/v1/manage/requests/1/decline", None, "admin"),
    ("GET", "/api/v1/manage/terms", None, "admin"),
    ("PUT", "/api/v1/manage/terms/scheme", {"kind": "semester"}, "admin"),
    (
        "PUT",
        "/api/v1/manage/terms/1",
        {"starts_on": "2026-09-01", "ends_on": "2026-10-20"},
        "admin",
    ),
    # Read-only, and still admin: every call spends part of a daily allowance
    # on somebody else's service, so the auth table is where that is enforced.
    ("GET", "/api/v1/manage/schools?q=гимназия", None, "admin"),
]


@pytest.mark.parametrize("method,url,body,minimum", ENDPOINTS)
async def test_manage_needs_a_bearer_token(client, school_class, method, url, body, minimum):
    assert (await client.request(method, url, json=body)).status_code == 401


@pytest.mark.parametrize("method,url,body,minimum", ENDPOINTS)
async def test_manage_refuses_an_unlinked_device(client, school_class, method, url, body, minimum):
    token = await _token(client)
    response = await client.request(method, url, json=body, headers=_auth(token))
    assert response.status_code == 403
    assert response.json()["detail"] == "device is not linked"


@pytest.mark.parametrize("method,url,body,minimum", ENDPOINTS)
async def test_manage_refuses_a_viewer(
    client, session, school_class, method, url, body, minimum
):
    token = await _linked_token(client, session, school_class, VIEWER_ID, Role.VIEWER)
    response = await client.request(method, url, json=body, headers=_auth(token))
    assert response.status_code == 403
    assert response.json()["detail"] == f"{minimum} role required"
    assert await _audit(session, school_class) == []


@pytest.mark.parametrize(
    "method,url,body",
    [(method, url, body) for method, url, body, minimum in ENDPOINTS if minimum == "admin"],
)
async def test_admin_endpoints_refuse_an_editor(client, session, school_class, method, url, body):
    """An editor writes homework and замены; the structure of the class is not
    theirs to change. Exactly the line the bot draws."""
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)
    response = await client.request(method, url, json=body, headers=_auth(token))
    assert response.status_code == 403
    assert response.json()["detail"] == "admin role required"


async def test_deleting_the_class_refuses_an_admin(client, session, school_class):
    token = await _admin(client, session, school_class)
    response = await client.request(
        "DELETE", "/api/v1/manage/class", json={"confirm_name": "9А"}, headers=_auth(token)
    )
    assert response.status_code == 403
    assert response.json()["detail"] == "owner role required"
    assert await session.get(SchoolClass, school_class.id) is not None


async def test_an_account_with_no_role_is_refused(client, session, school_class):
    token = await _linked_token(client, session, school_class, 6099, None)
    response = await client.get("/api/v1/manage/stats", headers=_auth(token))
    assert response.status_code == 403
    assert response.json()["detail"] == "editor role required"


# --------------------------------------------------------------------------
# The class card
# --------------------------------------------------------------------------


async def test_class_card_reads_the_card_the_bot_draws(client, session, school_class):
    token = await _admin(client, session, school_class)
    body = (await client.get("/api/v1/manage/class", headers=_auth(token))).json()

    assert body["id"] == school_class.id
    assert body["name"] == "9А" and body["school"] == "Школа № 1"
    assert body["timezone"] == "Europe/Moscow"
    assert "МСК" in body["timezone_label"]
    assert body["join_code"] == "TEST42"
    assert body["members"] == 1 and body["devices"] == 1
    assert body["pending_requests"] == 0
    assert body["bell_schedule_id"] == school_class.bell_schedule_id
    assert body["calendar_ready"] is False


async def test_class_update_renames_moves_zone_and_logs_each_field(
    client, session, school_class
):
    token = await _admin(client, session, school_class)
    response = await client.patch(
        "/api/v1/manage/class",
        json={"name": "9Б", "city": "Казань", "timezone": "Asia/Yekaterinburg"},
        headers=_auth(token),
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["name"] == "9Б" and body["city"] == "Казань"
    assert body["timezone"] == "Asia/Yekaterinburg"

    await session.refresh(school_class)
    assert school_class.name == "9Б" and school_class.timezone == "Asia/Yekaterinburg"
    assert sorted(await _actions(session, school_class)) == [
        "class.city",
        "class.name",
        "class.timezone",
    ]


async def test_class_update_clears_a_field_with_null_and_leaves_absent_ones(
    client, session, school_class
):
    token = await _admin(client, session, school_class)
    body = (
        await client.patch(
            "/api/v1/manage/class", json={"school": None}, headers=_auth(token)
        )
    ).json()
    assert body["school"] is None
    assert body["name"] == "9А"  # absent, so untouched


async def test_class_update_refuses_an_unknown_zone_and_a_blank_name(
    client, session, school_class
):
    token = await _admin(client, session, school_class)
    zone = await client.patch(
        "/api/v1/manage/class", json={"timezone": "Mars/Olympus"}, headers=_auth(token)
    )
    assert zone.status_code == 422 and zone.json()["detail"] == "unknown timezone"

    for bad in ({"name": None}, {"name": "   "}):
        assert (
            await client.patch("/api/v1/manage/class", json=bad, headers=_auth(token))
        ).status_code == 422

    await session.refresh(school_class)
    assert school_class.name == "9А" and school_class.timezone is None
    assert await _audit(session, school_class) == []


async def test_class_update_switches_the_join_mode_both_ways(client, session, school_class):
    """The phone's half of the bot's «🔒 Только по приглашениям».

    Three spellings meet here and only two of them are the same: the wire says
    «invite», the column holds «INVITE» — SQLAlchemy stores a PEP-435 enum by
    member name — and the audit line says neither. The raw read is deliberate:
    an ORM round-trip would agree with itself whichever string had been
    written, and a column holding a value the enum cannot look up raises on the
    *next* request rather than this one.
    """
    token = await _admin(client, session, school_class)

    response = await client.patch(
        "/api/v1/manage/class", json={"join_mode": "invite"}, headers=_auth(token)
    )
    assert response.status_code == 200, response.text
    assert response.json()["join_mode"] == "invite"

    stored = await session.scalar(
        text("select join_mode from classes where id = :id"), {"id": school_class.id}
    )
    assert stored == "INVITE"

    await session.refresh(school_class)
    assert school_class.join_mode is JoinMode.INVITE
    body = (await client.get("/api/v1/manage/class", headers=_auth(token))).json()
    assert body["join_mode"] == "invite"
    assert await _actions(session, school_class) == ["access.join_mode"]

    back = await client.patch(
        "/api/v1/manage/class", json={"join_mode": "open"}, headers=_auth(token)
    )
    assert back.json()["join_mode"] == "open"
    assert (
        await session.scalar(
            text("select join_mode from classes where id = :id"), {"id": school_class.id}
        )
        == "OPEN"
    )


async def test_class_update_refuses_a_join_mode_nobody_defined(client, session, school_class):
    """A literal in the schema rather than the enum, so this is a 422 naming
    the field instead of a 500 from somewhere inside SQLAlchemy."""
    token = await _admin(client, session, school_class)

    response = await client.patch(
        "/api/v1/manage/class", json={"join_mode": "INVITE"}, headers=_auth(token)
    )
    assert response.status_code == 422
    assert "join_mode" in response.text

    await session.refresh(school_class)
    assert school_class.join_mode is JoinMode.OPEN
    assert await _audit(session, school_class) == []


async def test_class_update_leaves_the_join_mode_alone_when_it_is_not_sent(
    client, session, school_class
):
    """An absent field is never written, and this one decides who can read the
    class — a rename that quietly reopened it would be the worst kind."""
    token = await _admin(client, session, school_class)
    await client.patch("/api/v1/manage/class", json={"join_mode": "invite"}, headers=_auth(token))

    body = (
        await client.patch("/api/v1/manage/class", json={"name": "9Б"}, headers=_auth(token))
    ).json()

    assert body["name"] == "9Б"
    assert body["join_mode"] == "invite"
    assert await _actions(session, school_class) == ["access.join_mode", "class.name"]


async def test_class_delete_needs_the_name_typed_back(client, session, school_class):
    token = await _linked_token(client, session, school_class, OWNER_ID, None)
    wrong = await client.request(
        "DELETE", "/api/v1/manage/class", json={"confirm_name": "9а"}, headers=_auth(token)
    )
    assert wrong.status_code == 422
    assert await session.get(SchoolClass, school_class.id) is not None


async def test_class_delete_takes_the_class_and_its_devices(client, session, school_class):
    """The owner's own phone stops working, which is the point: there is
    nothing left for its token to read."""
    class_id = school_class.id
    token = await _linked_token(client, session, school_class, OWNER_ID, None)
    response = await client.request(
        "DELETE", "/api/v1/manage/class", json={"confirm_name": "9А"}, headers=_auth(token)
    )
    assert response.status_code == 200
    assert response.json() == {"id": class_id, "deleted": True}

    # A fresh query, not ``session.get``: the test session still holds the row
    # it created in its identity map and would hand it back without asking.
    assert await session.scalar(select(SchoolClass).where(SchoolClass.id == class_id)) is None
    assert (await client.get("/api/v1/manage/class", headers=_auth(token))).status_code == 401


# --------------------------------------------------------------------------
# Subjects
# --------------------------------------------------------------------------


async def _subject(session, school_class, name: str = "Алгебра", **kwargs) -> Subject:
    subject = Subject(class_id=school_class.id, name=name, **kwargs)
    session.add(subject)
    await session.commit()
    return subject


async def test_subjects_list_is_open_to_an_editor(client, session, school_class):
    await _subject(session, school_class, "Физика")
    await _subject(session, school_class, "Алгебра", teacher="Иванова")
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)

    body = (await client.get("/api/v1/manage/subjects", headers=_auth(token))).json()
    # «История» is not one of the two rows this test added: it is the third
    # lesson of the fixture's Monday, and the list adopts what the timetable
    # already uses. A dictionary that answered «Алгебра, Физика» for a class
    # visibly teaching three subjects is the bug this behaviour exists for.
    assert [row["name"] for row in body] == ["Алгебра", "История", "Физика"]
    assert next(row for row in body if row["name"] == "Алгебра")["teacher"] == "Иванова"
    assert body[0]["teacher"] == "Иванова" and body[0]["id"]


async def test_the_subjects_screen_keeps_the_linking_it_just_did(client, session, school_class):
    """The list adopts what the timetable uses and points the lessons at the
    rows — and it used to commit only when it had *created* something. A class
    whose dictionary was already complete but whose lessons were not yet linked
    therefore had the UPDATEs run and thrown away when the session closed, on
    every read, forever. It healed only because `/bundle` commits
    unconditionally and some phone eventually polls it; the screen that exists
    to edit this list did the work and dropped it.
    """
    # The dictionary already holds all three of the fixture Monday's subjects,
    # so nothing is created — but the lessons still point at nothing.
    for name in ("Алгебра", "Физика", "История"):
        await _subject(session, school_class, name)
    await session.execute(
        sa_update(TimetableEntry)
        .where(TimetableEntry.class_id == school_class.id)
        .values(subject_id=None)
    )
    await session.commit()

    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)
    assert (await client.get("/api/v1/manage/subjects", headers=_auth(token))).status_code == 200

    # Read on a session of its own: the request's own session is long gone, and
    # what is being asked is whether anything was written down at all.
    async with SessionLocal() as fresh:
        unlinked = await fresh.scalar(
            select(func.count())
            .select_from(TimetableEntry)
            .where(
                TimetableEntry.class_id == school_class.id,
                TimetableEntry.subject_id.is_(None),
            )
        )
    assert unlinked == 0


async def test_subject_create_and_colour_normalisation(client, session, school_class):
    token = await _admin(client, session, school_class)
    response = await client.post(
        "/api/v1/manage/subjects",
        json={"name": " Химия ", "teacher": "Петров", "color": "5b6abf"},
        headers=_auth(token),
    )
    assert response.status_code == 201, response.text
    subject = response.json()["subject"]
    assert subject["name"] == "Химия" and subject["color"] == "#5B6ABF"
    assert response.json()["moved"] == 0
    assert await _actions(session, school_class) == ["subject.add"]


async def test_subject_create_refuses_a_duplicate_name(client, session, school_class):
    await _subject(session, school_class, "Химия")
    token = await _admin(client, session, school_class)
    response = await client.post(
        "/api/v1/manage/subjects", json={"name": "Химия"}, headers=_auth(token)
    )
    assert response.status_code == 409
    assert "already" in response.json()["detail"]
    assert await _audit(session, school_class) == []


async def test_subject_rename_carries_the_timetable_homework_and_overrides(
    client, session, school_class
):
    subject = await _subject(session, school_class, "Алгебра")
    session.add(
        Homework(
            class_id=school_class.id,
            due_date=MONDAY,
            subject_name="Алгебра",
            text="№ 12",
        )
    )
    session.add(
        LessonOverride(
            class_id=school_class.id,
            date=MONDAY,
            index=4,
            action=OverrideAction.REPLACE,
            subject_name="Алгебра",
        )
    )
    await session.commit()
    token = await _admin(client, session, school_class)

    response = await client.patch(
        f"/api/v1/manage/subjects/{subject.id}",
        json={"name": "Алгебра и начала анализа"},
        headers=_auth(token),
    )
    assert response.status_code == 200, response.text
    # One timetable row from the fixture's Monday, one homework, one замена.
    assert response.json()["moved"] == 3
    assert response.json()["subject"]["name"] == "Алгебра и начала анализа"

    left = await session.scalars(
        select(TimetableEntry.subject_name).where(TimetableEntry.class_id == school_class.id)
    )
    assert "Алгебра" not in set(left)
    entries = await _audit(session, school_class)
    assert [row.action for row in entries] == ["subject.rename"]
    assert "строк обновлено: 3" in entries[0].summary


async def test_subject_rename_onto_an_existing_name_is_refused(client, session, school_class):
    algebra = await _subject(session, school_class, "Алгебра")
    await _subject(session, school_class, "Геометрия")
    token = await _admin(client, session, school_class)

    response = await client.patch(
        f"/api/v1/manage/subjects/{algebra.id}",
        json={"name": "Геометрия"},
        headers=_auth(token),
    )
    assert response.status_code == 409
    await session.refresh(algebra)
    assert algebra.name == "Алгебра"
    assert await _audit(session, school_class) == []
    # And the timetable was not half-moved either.
    names = await session.scalars(
        select(TimetableEntry.subject_name).where(TimetableEntry.class_id == school_class.id)
    )
    assert "Алгебра" in set(names)


async def test_subject_patch_sets_and_clears_the_small_fields(client, session, school_class):
    subject = await _subject(session, school_class, "Физика", teacher="Петров", color="#111111")
    token = await _admin(client, session, school_class)

    response = await client.patch(
        f"/api/v1/manage/subjects/{subject.id}",
        json={"short_name": "Физ", "teacher": None, "color": None},
        headers=_auth(token),
    )
    body = response.json()["subject"]
    assert body["short_name"] == "Физ" and body["teacher"] is None and body["color"] is None
    assert response.json()["moved"] == 0
    assert sorted(await _actions(session, school_class)) == [
        "subject.colour",
        "subject.short_name",
        "subject.teacher",
    ]


async def test_subject_delete_is_refused_while_the_timetable_uses_it(
    client, session, school_class
):
    """Deleting half of one list is refused, and says how much of it is in use.

    It used to succeed and leave the lessons alone. Once the dictionary began
    keeping itself that stopped meaning anything: the name is still in the
    weekly template, so the next read adopts it straight back — without the
    colour, the short name or the teacher the deleted row carried — and the
    admin is told nothing.
    """
    subject = await _subject(session, school_class, "Алгебра")
    token = await _admin(client, session, school_class)

    response = await client.delete(
        f"/api/v1/manage/subjects/{subject.id}", headers=_auth(token)
    )
    assert response.status_code == 409
    assert "lesson" in response.json()["detail"]
    assert await session.scalar(select(Subject).where(Subject.id == subject.id)) is not None
    assert await _actions(session, school_class) == []


async def test_a_subject_nothing_teaches_still_deletes(client, session, school_class):
    """The case the endpoint was really for: a row the template never mentions."""
    subject = await _subject(session, school_class, "Астрономия")
    token = await _admin(client, session, school_class)

    response = await client.delete(
        f"/api/v1/manage/subjects/{subject.id}", headers=_auth(token)
    )
    assert response.status_code == 200
    assert response.json() == {"id": subject.id, "deleted": True}
    assert await session.scalar(select(Subject).where(Subject.id == subject.id)) is None

    names = await session.scalars(
        select(TimetableEntry.subject_name).where(TimetableEntry.class_id == school_class.id)
    )
    assert "Астрономия" not in set(names)
    assert await _actions(session, school_class) == ["subject.delete"]


async def test_a_subject_of_another_class_is_a_404(client, session, school_class):
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    stranger = Subject(class_id=other.id, name="Биология")
    session.add(stranger)
    await session.commit()
    token = await _admin(client, session, school_class)

    assert (
        await client.patch(
            f"/api/v1/manage/subjects/{stranger.id}",
            json={"name": "Ботаника"},
            headers=_auth(token),
        )
    ).status_code == 404
    assert (
        await client.delete(f"/api/v1/manage/subjects/{stranger.id}", headers=_auth(token))
    ).status_code == 404


# --------------------------------------------------------------------------
# Bell schedules
# --------------------------------------------------------------------------


async def test_bells_list_marks_the_class_default(client, session, school_class):
    token = await _admin(client, session, school_class)
    body = (await client.get("/api/v1/manage/bells", headers=_auth(token))).json()

    assert len(body) == 1
    assert body[0]["id"] == school_class.bell_schedule_id
    assert body[0]["is_default"] is True
    assert body[0]["periods"][0] == {
        "index": 1,
        "starts_at": "08:30:00",
        "ends_at": "09:15:00",
    }


async def test_bells_create_with_rows_does_not_become_the_default(
    client, session, school_class
):
    token = await _admin(client, session, school_class)
    response = await client.post(
        "/api/v1/manage/bells",
        json={
            "name": "Сокращённое",
            "periods": [
                {"index": 1, "starts_at": "08:30", "ends_at": "09:00"},
                {"index": 2, "starts_at": "09:10", "ends_at": "09:40"},
            ],
        },
        headers=_auth(token),
    )
    assert response.status_code == 201, response.text
    body = response.json()
    assert body["name"] == "Сокращённое" and body["is_default"] is False
    assert [row["index"] for row in body["periods"]] == [1, 2]

    await session.refresh(school_class)
    assert school_class.bell_schedule_id != body["id"]
    assert await _actions(session, school_class) == ["bells.create"]


async def test_bells_rename_and_make_default(client, session, school_class):
    token = await _admin(client, session, school_class)
    created = (
        await client.post(
            "/api/v1/manage/bells", json={"name": "Суббота"}, headers=_auth(token)
        )
    ).json()

    response = await client.patch(
        f"/api/v1/manage/bells/{created['id']}",
        json={"name": "Субботнее", "is_default": True},
        headers=_auth(token),
    )
    assert response.status_code == 200, response.text
    assert response.json()["name"] == "Субботнее" and response.json()["is_default"] is True

    await session.refresh(school_class)
    assert school_class.bell_schedule_id == created["id"]
    assert await _actions(session, school_class) == [
        "bells.create",
        "bells.rename",
        "bells.default",
    ]


async def test_bells_cannot_be_un_defaulted(client, session, school_class):
    token = await _admin(client, session, school_class)
    response = await client.patch(
        f"/api/v1/manage/bells/{school_class.bell_schedule_id}",
        json={"is_default": False},
        headers=_auth(token),
    )
    assert response.status_code == 422
    await session.refresh(school_class)
    assert school_class.bell_schedule_id is not None


async def test_bells_periods_replace_the_rows_wholesale(client, session, school_class):
    token = await _admin(client, session, school_class)
    schedule_id = school_class.bell_schedule_id

    response = await client.put(
        f"/api/v1/manage/bells/{schedule_id}/periods",
        json={
            "periods": [
                {"index": 1, "starts_at": "09:00", "ends_at": "09:40"},
                {"index": 2, "starts_at": "09:50", "ends_at": "10:30"},
            ]
        },
        headers=_auth(token),
    )
    assert response.status_code == 200, response.text
    assert [row["index"] for row in response.json()["periods"]] == [1, 2]

    rows = list(
        await session.scalars(select(BellPeriod).where(BellPeriod.schedule_id == schedule_id))
    )
    assert len(rows) == 2 and rows[0].starts_at == time(9, 0)
    assert await _actions(session, school_class) == ["bells.edit"]

    # The day view follows immediately.
    bundle = (
        await client.get(
            "/api/v1/bundle",
            params={"start": MONDAY.isoformat(), "days": 1},
            headers=_auth(token),
        )
    ).json()
    assert bundle["days"][0]["lessons"][0]["starts_at"] == "09:00:00"


async def test_bells_periods_refuse_an_empty_or_repeating_list(client, session, school_class):
    """A paste that turned out to be prose must never be the reason a class
    loses the times it runs on."""
    token = await _admin(client, session, school_class)
    schedule_id = school_class.bell_schedule_id

    for bad in (
        {"periods": []},
        {
            "periods": [
                {"index": 1, "starts_at": "09:00", "ends_at": "09:40"},
                {"index": 1, "starts_at": "10:00", "ends_at": "10:40"},
            ]
        },
        {"periods": [{"index": 1, "starts_at": "09:40", "ends_at": "09:00"}]},
    ):
        response = await client.put(
            f"/api/v1/manage/bells/{schedule_id}/periods", json=bad, headers=_auth(token)
        )
        assert response.status_code == 422, bad

    kept = await session.scalars(select(BellPeriod).where(BellPeriod.schedule_id == schedule_id))
    assert len(list(kept)) == 7


async def test_bells_delete_refuses_the_default_and_a_schedule_days_use(
    client, session, school_class
):
    token = await _admin(client, session, school_class)
    default = await client.delete(
        f"/api/v1/manage/bells/{school_class.bell_schedule_id}", headers=_auth(token)
    )
    assert default.status_code == 409 and "default" in default.json()["detail"]

    other = (
        await client.post(
            "/api/v1/manage/bells", json={"name": "Сокращённое"}, headers=_auth(token)
        )
    ).json()
    session.add(
        DayOverride(
            class_id=school_class.id,
            date=MONDAY,
            kind="shortened",
            bell_schedule_id=other["id"],
        )
    )
    await session.commit()

    used = await client.delete(f"/api/v1/manage/bells/{other['id']}", headers=_auth(token))
    assert used.status_code == 409 and "1 special day" in used.json()["detail"]
    assert await session.get(BellSchedule, other["id"]) is not None


async def test_bells_delete_removes_a_free_schedule(client, session, school_class):
    token = await _admin(client, session, school_class)
    other = (
        await client.post(
            "/api/v1/manage/bells", json={"name": "Сокращённое"}, headers=_auth(token)
        )
    ).json()

    response = await client.delete(f"/api/v1/manage/bells/{other['id']}", headers=_auth(token))
    assert response.status_code == 200
    assert await session.get(BellSchedule, other["id"]) is None
    assert await _actions(session, school_class) == ["bells.create", "bells.delete"]


# --------------------------------------------------------------------------
# Export and import
# --------------------------------------------------------------------------


async def test_timetable_export_is_the_paste_format(client, session, school_class):
    token = await _admin(client, session, school_class)
    body = (await client.get("/api/v1/manage/timetable", headers=_auth(token))).json()

    assert body["lessons"] == 3
    assert "== Понедельник ==" in body["text"]
    assert "1. Алгебра, 214" in body["text"]
    assert "== Звонки ==" in body["text"]


async def test_timetable_import_reports_the_conflict_and_writes_nothing(
    client, session, school_class
):
    token = await _admin(client, session, school_class)
    response = await client.post(
        "/api/v1/manage/timetable/import",
        json={"text": "== Понедельник ==\n1. Химия, 118\nне строка\n2. Биология"},
        headers=_auth(token),
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["applied"] is False
    assert body["conflicts"] == [{"weekday": 1, "existing": 3, "incoming": 2}]
    assert body["rejected"] == ["не строка"]

    names = await session.scalars(
        select(TimetableEntry.subject_name).where(TimetableEntry.class_id == school_class.id)
    )
    assert "Химия" not in set(names)
    assert await _audit(session, school_class) == []


async def test_timetable_import_applies_with_replace(client, session, school_class):
    token = await _admin(client, session, school_class)
    text = (
        "== Понедельник ==\n1. Химия, 118\n2. Биология\n\n"
        "== Вторник ==\n1. История\n\n"
        "== Звонки ==\n1. 09:00-09:40\n2. 09:50-10:30\n"
    )
    response = await client.post(
        "/api/v1/manage/timetable/import",
        json={"text": text, "replace": True},
        headers=_auth(token),
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["applied"] is True
    assert body["days"] == [1, 2] and body["lessons"] == 3 and body["bells"] == 2

    rows = list(
        await session.scalars(
            select(TimetableEntry).where(TimetableEntry.class_id == school_class.id)
        )
    )
    assert {row.subject_name for row in rows} == {"Химия", "Биология", "История"}
    periods = await session.scalars(
        select(BellPeriod).where(BellPeriod.schedule_id == school_class.bell_schedule_id)
    )
    assert len(list(periods)) == 2

    entries = await _audit(session, school_class)
    assert [row.action for row in entries] == ["timetable.import"]
    assert "дней 2" in entries[0].summary


async def test_timetable_import_of_an_untouched_day_needs_no_replace(
    client, session, school_class
):
    """Only the weekdays the paste names are touched, so a Tuesday block
    against a Monday-only timetable is not a conflict at all."""
    token = await _admin(client, session, school_class)
    response = await client.post(
        "/api/v1/manage/timetable/import",
        json={"text": "== Вторник ==\n1. Химия, 118"},
        headers=_auth(token),
    )
    assert response.json()["applied"] is True
    assert response.json()["conflicts"] == []

    monday = await session.scalars(
        select(TimetableEntry).where(
            TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == 1
        )
    )
    assert len(list(monday)) == 3


async def test_timetable_import_refuses_a_paste_with_no_day_in_it(
    client, session, school_class
):
    token = await _admin(client, session, school_class)
    response = await client.post(
        "/api/v1/manage/timetable/import",
        json={"text": "просто текст без заголовков"},
        headers=_auth(token),
    )
    assert response.status_code == 422
    assert await _audit(session, school_class) == []


async def test_export_survives_an_import(client, session, school_class):
    """The format is a backup only if it round-trips."""
    token = await _admin(client, session, school_class)
    original = (await client.get("/api/v1/manage/timetable", headers=_auth(token))).json()["text"]

    applied = await client.post(
        "/api/v1/manage/timetable/import",
        json={"text": original, "replace": True},
        headers=_auth(token),
    )
    assert applied.json()["applied"] is True
    again = (await client.get("/api/v1/manage/timetable", headers=_auth(token))).json()["text"]
    assert again == original


# --------------------------------------------------------------------------
# Devices
# --------------------------------------------------------------------------


async def test_devices_list_shows_the_owner_and_the_role_it_borrows(
    client, session, school_class
):
    token = await _admin(client, session, school_class)
    await _token(client, "чужой телефон")

    body = (await client.get("/api/v1/manage/devices", headers=_auth(token))).json()
    assert len(body) == 2
    linked = next(row for row in body if row["linked"])
    assert linked["role"] == "admin"
    assert linked["owner"] == str(ADMIN_ID)
    assert linked["device_name"] == "pytest"
    unlinked = next(row for row in body if not row["linked"])
    assert unlinked["role"] is None and unlinked["owner"] is None


async def test_the_device_list_asks_for_a_role_once_per_owner_not_once_per_phone(
    client, session, school_class
):
    """A role belongs to the account, not to the phone.

    «📱 Устройства» in the bot resolves it once per owner; the list here asked
    per row, so a class where everybody has joined from their own phone paid a
    round trip a phone for an answer it already had.
    """
    token = await _admin(client, session, school_class)

    async def more_phones(count: int) -> None:
        for number in range(count):
            extra = await _token(client, f"телефон {number}")
            me = await client.get("/api/v1/me", headers=_auth(extra))
            await linking.link_device(session, me.json()["link_code"], ADMIN_ID)

    async def list_devices() -> tuple[int, int]:
        """(phones listed, times the class's members were read)."""
        seen: list[str] = []

        def record(conn, cursor, statement, parameters, context, executemany):
            seen.append(statement)

        event.listen(engine.sync_engine, "before_cursor_execute", record)
        try:
            response = await client.get("/api/v1/manage/devices", headers=_auth(token))
        finally:
            event.remove(engine.sync_engine, "before_cursor_execute", record)
        assert response.status_code == 200, response.text
        return len(response.json()), len([line for line in seen if "FROM bot_users" in line])

    await more_phones(1)
    two_phones, lookups_for_two = await list_devices()
    await more_phones(8)
    ten_phones, lookups_for_ten = await list_devices()

    assert (two_phones, ten_phones) == (2, 10)
    assert lookups_for_ten == lookups_for_two


async def test_revoking_a_device_stops_its_token_at_once(client, session, school_class):
    token = await _admin(client, session, school_class)
    victim = await _token(client, "потерянный")
    device = await session.scalar(
        select(DeviceToken).where(DeviceToken.device_name == "потерянный")
    )

    response = await client.post(
        f"/api/v1/manage/devices/{device.id}/revoke", headers=_auth(token)
    )
    assert response.status_code == 200
    assert response.json()["revoked"] is True
    assert (await client.get("/api/v1/me", headers=_auth(victim))).status_code == 401

    # Revoked devices are off the list unless asked for, and revoking twice
    # does not write a second line.
    assert (await client.get("/api/v1/manage/devices", headers=_auth(token))).json().__len__() == 1
    with_revoked = await client.get(
        "/api/v1/manage/devices", params={"include_revoked": True}, headers=_auth(token)
    )
    assert len(with_revoked.json()) == 2
    await client.post(f"/api/v1/manage/devices/{device.id}/revoke", headers=_auth(token))
    assert await _actions(session, school_class) == ["device.revoke"]


async def test_an_admin_may_revoke_the_phone_they_are_holding(client, session, school_class):
    token = await _admin(client, session, school_class)
    device = await session.scalar(select(DeviceToken).where(DeviceToken.device_name == "pytest"))

    assert (
        await client.post(f"/api/v1/manage/devices/{device.id}/revoke", headers=_auth(token))
    ).status_code == 200
    assert (await client.get("/api/v1/manage/class", headers=_auth(token))).status_code == 401


async def test_unlinking_a_device_puts_it_back_to_read_only(client, session, school_class):
    admin_token = await _admin(client, session, school_class)
    editor_token = await _linked_token(
        client, session, school_class, EDITOR_ID, Role.EDITOR, "телефон редактора"
    )
    device = await session.scalar(
        select(DeviceToken).where(DeviceToken.device_name == "телефон редактора")
    )

    response = await client.post(
        f"/api/v1/manage/devices/{device.id}/unlink", headers=_auth(admin_token)
    )
    assert response.status_code == 200
    assert response.json()["linked"] is False and response.json()["role"] is None

    me = (await client.get("/api/v1/me", headers=_auth(editor_token))).json()
    assert me["linked"] is False and me["can_edit"] is False
    assert (
        await client.put(
            "/api/v1/homework",
            json={"due_date": "2026-09-14", "subject": "Алгебра", "text": "№ 1"},
            headers=_auth(editor_token),
        )
    ).status_code == 403

    again = await client.post(
        f"/api/v1/manage/devices/{device.id}/unlink", headers=_auth(admin_token)
    )
    assert again.status_code == 409
    assert await _actions(session, school_class) == ["device.unlink"]


async def test_the_same_subject_typed_two_ways_is_one_subject(client, session, school_class):
    """The dictionary is only a dictionary if the two writers spell alike.

    The bot single-spaces every name it is typed; the API used to trim the ends
    and leave the middle alone. A class that added «Алгебра и начала» from the
    bot and «Алгебра  и  начала» from the phone had two subjects that look like
    one, and a rename of either carried none of the other's lessons.
    """
    token = await _admin(client, session, school_class)

    created = await client.post(
        "/api/v1/manage/subjects",
        json={"name": "Алгебра  и  начала"},
        headers=_auth(token),
    )
    assert created.status_code == 201
    assert created.json()["subject"]["name"] == "Алгебра и начала"

    duplicate = await client.post(
        "/api/v1/manage/subjects", json={"name": "Алгебра и начала"}, headers=_auth(token)
    )
    assert duplicate.status_code == 409


async def test_a_failed_log_line_leaves_the_phone_linked(
    client, session, school_class, monkeypatch
):
    """The unlink and its audit line are one transaction, or neither happens.

    The log is the only way an admin sees that another admin unlinked somebody,
    so "phone unlinked, nothing written down" is the one outcome worth ruling
    out. It used to be reachable: the unlink committed itself and the log line
    was inserted afterwards, on a second commit that could fail on its own.
    """
    admin_token = await _admin(client, session, school_class)
    await _linked_token(
        client, session, school_class, EDITOR_ID, Role.EDITOR, "телефон редактора"
    )
    device = await session.scalar(
        select(DeviceToken).where(DeviceToken.device_name == "телефон редактора")
    )
    device_id = device.id
    class_id = school_class.id

    async def explode(*args: Any, **kwargs: Any) -> None:
        raise RuntimeError("the log is full")

    monkeypatch.setattr(manage.audit, "record", explode)
    with pytest.raises(RuntimeError):
        await client.post(
            f"/api/v1/manage/devices/{device.id}/unlink", headers=_auth(admin_token)
        )

    await session.rollback()
    again = await session.scalar(select(DeviceToken).where(DeviceToken.id == device_id))
    assert again.telegram_id == EDITOR_ID
    logged = await session.scalars(select(AuditEntry).where(AuditEntry.class_id == class_id))
    assert list(logged) == []


async def test_a_device_of_another_class_is_a_404(client, session, school_class):
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    stranger = DeviceToken(token_hash="x" * 64, class_id=other.id, device_name="чужой")
    session.add(stranger)
    await session.commit()
    token = await _admin(client, session, school_class)

    assert (
        await client.post(f"/api/v1/manage/devices/{stranger.id}/revoke", headers=_auth(token))
    ).status_code == 404


# --------------------------------------------------------------------------
# The audit log
# --------------------------------------------------------------------------


async def test_log_pages_newest_first_and_names_the_author(client, session, school_class):
    session.add(
        BotUser(
            telegram_id=ADMIN_ID,
            class_id=school_class.id,
            role=Role.ADMIN,
            username="admin_ann",
        )
    )
    await session.commit()
    token = await _token(client)
    me = await client.get("/api/v1/me", headers=_auth(token))
    await linking.link_device(session, me.json()["link_code"], ADMIN_ID)

    for name in ("Химия", "Биология", "География"):
        assert (
            await client.post(
                "/api/v1/manage/subjects", json={"name": name}, headers=_auth(token)
            )
        ).status_code == 201

    first = (
        await client.get("/api/v1/manage/log", params={"limit": 2}, headers=_auth(token))
    ).json()
    assert first["has_more"] is True and first["limit"] == 2 and first["offset"] == 0
    assert len(first["entries"]) == 2
    assert "География" in first["entries"][0]["summary"]
    assert first["entries"][0]["action"] == "subject.add"
    assert first["entries"][0]["who"] == "@admin_ann"
    assert first["entries"][0]["at"] is not None

    second = (
        await client.get(
            "/api/v1/manage/log", params={"limit": 2, "offset": 2}, headers=_auth(token)
        )
    ).json()
    assert second["has_more"] is False and len(second["entries"]) == 1
    assert "Химия" in second["entries"][0]["summary"]


async def test_log_refuses_a_negative_offset(client, session, school_class):
    token = await _admin(client, session, school_class)
    response = await client.get(
        "/api/v1/manage/log", params={"offset": -5}, headers=_auth(token)
    )
    assert response.status_code == 422


# --------------------------------------------------------------------------
# Stats
# --------------------------------------------------------------------------


async def test_stats_are_the_numbers_the_bot_shows(client, session, school_class):
    session.add(
        Homework(
            class_id=school_class.id,
            due_date=date.today() + timedelta(days=3),
            subject_name="Алгебра",
            text="№ 12",
        )
    )
    session.add(
        Homework(
            class_id=school_class.id,
            due_date=date.today() - timedelta(days=30),
            subject_name="Физика",
            text="старое",
        )
    )
    await session.commit()
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)

    body = (await client.get("/api/v1/manage/stats", headers=_auth(token))).json()
    assert body["lessons_per_week"] == 3.0 and body["subjects_count"] == 3
    assert {row["name"] for row in body["subjects"]} == {"Алгебра", "Физика", "История"}
    assert body["homework_open"] == 1 and body["homework_total"] == 2
    assert body["members_by_role"] == {"editor": 1}
    assert body["devices_active"] == 1
    assert body["overrides_upcoming"] == 0 and body["events_upcoming"] == 0


# --------------------------------------------------------------------------
# Access requests
# --------------------------------------------------------------------------


async def _request(session, school_class, telegram_id: int, role: Role = Role.EDITOR):
    row = AccessRequest(
        class_id=school_class.id,
        telegram_id=telegram_id,
        requested_role=role,
        status="pending",
        message="я староста",
    )
    session.add(row)
    await session.commit()
    return row


async def test_requests_list_shows_who_is_waiting(client, session, school_class):
    await _request(session, school_class, ASKER_ID)
    token = await _admin(client, session, school_class)

    body = (await client.get("/api/v1/manage/requests", headers=_auth(token))).json()
    assert len(body) == 1
    assert body[0]["requested_role"] == "editor"
    assert body[0]["message"] == "я староста"
    assert body[0]["who"] == str(ASKER_ID)


async def test_approving_grants_the_role_logs_it_and_tells_the_asker(
    client, session, school_class, recording_bot
):
    request = await _request(session, school_class, ASKER_ID)
    token = await _admin(client, session, school_class)

    response = await client.post(
        f"/api/v1/manage/requests/{request.id}/approve", headers=_auth(token)
    )
    assert response.status_code == 200, response.text
    assert response.json()["status"] == "approved" and response.json()["role"] == "editor"

    member = await session.scalar(
        select(BotUser).where(
            BotUser.class_id == school_class.id, BotUser.telegram_id == ASKER_ID
        )
    )
    assert member is not None and member.role is Role.EDITOR
    await session.refresh(request)
    assert request.status == "approved" and request.decided_by == ADMIN_ID
    assert await _actions(session, school_class) == ["access.approve"]

    assert [chat_id for chat_id, _ in recording_bot.sent] == [ASKER_ID]
    assert "Редактор" in recording_bot.sent[0][1]
    assert recording_bot.closed

    # Answered once: the second tap finds nothing pending.
    assert (
        await client.post(f"/api/v1/manage/requests/{request.id}/approve", headers=_auth(token))
    ).status_code == 404


async def test_approving_with_an_explicit_role_is_bounded_by_the_grantor(
    client, session, school_class
):
    """Nobody may grant at or above their own level - the rule that stops an
    admin promoting a friend to admin and being demoted by them."""
    request = await _request(session, school_class, ASKER_ID)
    token = await _admin(client, session, school_class)

    response = await client.post(
        f"/api/v1/manage/requests/{request.id}/approve",
        json={"role": "admin"},
        headers=_auth(token),
    )
    assert response.status_code == 403
    assert response.json()["detail"] == "cannot grant a role at or above your own"
    assert await session.scalar(
        select(BotUser).where(BotUser.telegram_id == ASKER_ID)
    ) is None
    await session.refresh(request)
    assert request.status == "pending"


async def test_approving_a_peer_is_refused(client, session, school_class):
    session.add(BotUser(telegram_id=ASKER_ID, class_id=school_class.id, role=Role.ADMIN))
    await session.commit()
    request = await _request(session, school_class, ASKER_ID)
    token = await _admin(client, session, school_class)

    response = await client.post(
        f"/api/v1/manage/requests/{request.id}/approve", headers=_auth(token)
    )
    assert response.status_code == 403
    assert response.json()["detail"] == "cannot change this member's role"


async def test_an_owner_may_grant_admin(client, session, school_class, recording_bot):
    request = await _request(session, school_class, ASKER_ID, Role.ADMIN)
    token = await _linked_token(client, session, school_class, OWNER_ID, None)

    response = await client.post(
        f"/api/v1/manage/requests/{request.id}/approve", headers=_auth(token)
    )
    assert response.status_code == 200
    assert response.json()["role"] == "admin"
    member = await session.scalar(select(BotUser).where(BotUser.telegram_id == ASKER_ID))
    assert member.role is Role.ADMIN


async def test_declining_closes_the_request_and_tells_the_asker(
    client, session, school_class, recording_bot
):
    request = await _request(session, school_class, ASKER_ID)
    token = await _admin(client, session, school_class)

    response = await client.post(
        f"/api/v1/manage/requests/{request.id}/decline", headers=_auth(token)
    )
    assert response.status_code == 200
    assert response.json()["status"] == "declined" and response.json()["role"] is None

    await session.refresh(request)
    assert request.status == "declined"
    assert await session.scalar(select(BotUser).where(BotUser.telegram_id == ASKER_ID)) is None
    assert await _actions(session, school_class) == ["access.decline"]
    assert [chat_id for chat_id, _ in recording_bot.sent] == [ASKER_ID]
    assert "отклонён" in recording_bot.sent[0][1]


async def test_a_request_of_another_class_is_a_404(client, session, school_class):
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    stranger = AccessRequest(
        class_id=other.id, telegram_id=ASKER_ID, requested_role=Role.EDITOR, status="pending"
    )
    session.add(stranger)
    await session.commit()
    token = await _admin(client, session, school_class)

    assert (
        await client.post(
            f"/api/v1/manage/requests/{stranger.id}/approve", headers=_auth(token)
        )
    ).status_code == 404


async def test_a_decision_survives_a_deployment_with_no_bot(client, session, school_class):
    """No BOT_TOKEN: the role is still granted and nobody is told."""
    assert get_settings().bot_token == ""
    request = await _request(session, school_class, ASKER_ID)
    token = await _admin(client, session, school_class)

    response = await client.post(
        f"/api/v1/manage/requests/{request.id}/approve", headers=_auth(token)
    )
    assert response.status_code == 200
    assert await session.scalar(select(BotUser).where(BotUser.telegram_id == ASKER_ID)) is not None


# --------------------------------------------------------------------------
# Четверти и полугодия
# --------------------------------------------------------------------------


async def test_terms_are_seeded_on_first_read(client, session, school_class):
    school_class.grade = 9
    await session.commit()
    headers = _auth(await _admin(client, session, school_class))

    body = (await client.get("/api/v1/manage/terms", headers=headers)).json()

    assert body["kind"] == "quarter"
    assert [term["index"] for term in body["terms"]] == [1, 2, 3, 4]


async def test_the_scheme_can_be_switched_from_the_phone(client, session, school_class):
    school_class.grade = 9
    await session.commit()
    headers = _auth(await _admin(client, session, school_class))

    body = (
        await client.put(
            "/api/v1/manage/terms/scheme", json={"kind": "semester"}, headers=headers
        )
    ).json()

    assert body["kind"] == "semester"
    assert len(body["terms"]) == 2
    assert "class.term_kind" in await _actions(session, school_class)


async def test_a_term_can_be_moved_from_the_phone(client, session, school_class):
    school_class.grade = 9
    await session.commit()
    headers = _auth(await _admin(client, session, school_class))
    year = (await client.get("/api/v1/manage/terms", headers=headers)).json()["year"]

    response = await client.put(
        "/api/v1/manage/terms/1",
        json={"starts_on": f"{year}-09-01", "ends_on": f"{year}-10-20"},
        headers=headers,
    )

    assert response.status_code == 200, response.text
    assert response.json()["terms"][0]["ends_on"] == f"{year}-10-20"


async def test_an_overlapping_term_is_refused_with_the_reason(client, session, school_class):
    """The rule is about the other rows, so the message names the one it hit —
    a field-shaped error could not say which period is in the way."""
    school_class.grade = 9
    await session.commit()
    headers = _auth(await _admin(client, session, school_class))
    year = (await client.get("/api/v1/manage/terms", headers=headers)).json()["year"]

    response = await client.put(
        "/api/v1/manage/terms/1",
        json={"starts_on": f"{year}-09-01", "ends_on": f"{year}-12-01"},
        headers=headers,
    )

    assert response.status_code == 422
    assert "ересекается" in response.json()["detail"]


async def test_a_term_reaching_into_the_holidays_is_refused(client, session, school_class):
    school_class.grade = 9
    await session.commit()
    headers = _auth(await _admin(client, session, school_class))
    year = (await client.get("/api/v1/manage/terms", headers=headers)).json()["year"]

    response = await client.put(
        "/api/v1/manage/terms/4",
        json={"starts_on": f"{year + 1}-04-01", "ends_on": f"{year + 1}-06-20"},
        headers=headers,
    )

    assert response.status_code == 422
    assert "учебный год" in response.json()["detail"]



# --------------------------------------------------------------------------
# The school directory
# --------------------------------------------------------------------------


async def test_the_school_search_says_it_is_unavailable_rather_than_broken(
    client, session, school_class, monkeypatch
):
    """No key is not a 500. Nothing failed — the feature was never configured,
    and the client's answer to that is to let the name be typed."""
    monkeypatch.setattr(get_settings(), "dadata_token", "")
    token = await _admin(client, session, school_class)

    response = await client.get("/api/v1/manage/schools?q=гимназия", headers=_auth(token))
    assert response.status_code == 503
    assert "вручную" in response.json()["detail"]


async def test_a_query_too_short_to_search_with_is_422_in_russian(
    client, session, school_class, monkeypatch
):
    monkeypatch.setattr(get_settings(), "dadata_token", "test-key")
    token = await _admin(client, session, school_class)

    response = await client.get("/api/v1/manage/schools?q=шк", headers=_auth(token))
    assert response.status_code == 422
    assert "символа" in response.json()["detail"]


async def test_the_search_returns_a_page_and_says_when_it_was_cut_short(
    client, session, school_class, monkeypatch
):
    """Twenty is the directory's ceiling, not the number of matches, and
    ``truncated`` is the only thing that says so."""
    monkeypatch.setattr(get_settings(), "dadata_token", "test-key")

    async def fake_suggest(query: str, *, region: str | None = None):
        assert query == "гимназия 3"
        return [
            {
                "value": f"ГИМНАЗИЯ № {i}",
                "data": {
                    "ogrn": f"102780000{i:04d}",
                    "name": {"short_with_opf": f'МБОУ "ГИМНАЗИЯ № {i}"'},
                    "state": {"status": "ACTIVE"},
                    "address": {"value": "г Пермь", "data": {"city": "Пермь"}},
                },
            }
            for i in range(20)
        ]

    monkeypatch.setattr("app.providers.dadata.suggest_schools", fake_suggest)
    token = await _admin(client, session, school_class)

    response = await client.get(
        "/api/v1/manage/schools", params={"q": " гимназия  3 ", "page": 2}, headers=_auth(token)
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["page"] == 2
    assert body["pages"] == 4
    assert body["total"] == 20
    assert body["truncated"] is True
    assert [item["name"] for item in body["items"]] == [
        f'МБОУ "Гимназия № {i}"' for i in range(5, 10)
    ]
    assert body["items"][0]["city"] == "Пермь"


async def test_a_page_past_the_end_comes_back_as_the_last_one(
    client, session, school_class, monkeypatch
):
    monkeypatch.setattr(get_settings(), "dadata_token", "test-key")

    async def fake_suggest(query: str, *, region: str | None = None):
        return [
            {"value": "ШКОЛА № 1", "data": {"ogrn": "1", "state": {"status": "ACTIVE"}}},
        ]

    monkeypatch.setattr("app.providers.dadata.suggest_schools", fake_suggest)
    token = await _admin(client, session, school_class)

    response = await client.get(
        "/api/v1/manage/schools", params={"q": "школа 1", "page": 99}, headers=_auth(token)
    )
    assert response.status_code == 200, response.text
    assert response.json()["page"] == 1
    assert response.json()["truncated"] is False


async def test_asking_for_everything_at_once_costs_one_upstream_search(
    client, session, school_class, monkeypatch
):
    """The directory has no offset, so each call searches again — a client that
    pages should take all twenty and cut them up itself."""
    monkeypatch.setattr(get_settings(), "dadata_token", "test-key")
    searches: list[str] = []

    async def fake_suggest(query: str, *, region: str | None = None):
        searches.append(query)
        return [
            {"value": f"ШКОЛА {i}", "data": {"ogrn": str(i), "state": {"status": "ACTIVE"}}}
            for i in range(20)
        ]

    monkeypatch.setattr("app.providers.dadata.suggest_schools", fake_suggest)
    token = await _admin(client, session, school_class)

    response = await client.get(
        "/api/v1/manage/schools",
        params={"q": "школа", "page_size": 20},
        headers=_auth(token),
    )
    assert response.status_code == 200, response.text
    assert response.json()["pages"] == 1
    assert len(response.json()["items"]) == 20
    assert len(searches) == 1


async def test_a_page_size_above_the_directorys_ceiling_is_refused(
    client, session, school_class, monkeypatch
):
    monkeypatch.setattr(get_settings(), "dadata_token", "test-key")
    token = await _admin(client, session, school_class)

    response = await client.get(
        "/api/v1/manage/schools",
        params={"q": "школа", "page_size": 50},
        headers=_auth(token),
    )
    assert response.status_code == 422
