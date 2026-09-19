"""The linked-device surface: linking, ticks, tasks, the class feed, the
write endpoints and the reminder tick.

Everything runs through the real ASGI app against the SQLite test database.
Telegram is stubbed where the app would send - the tick and the write
endpoints get a bot that records messages instead of delivering them.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from typing import Any
from zoneinfo import ZoneInfo

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import select

from app.api import cron, edit, public
from app.config import Settings, get_settings
from app.fsm_storage import FsmRecord  # registers fsm_states before create_all runs
from app.main import app
from app.models import (
    AuditEntry,
    BellPeriod,
    BellSchedule,
    BotUser,
    DayEvent,
    DayKind,
    DayOverride,
    DeviceToken,
    DiarySession,
    EventKind,
    Homework,
    JoinAttempt,
    LessonOverride,
    OverrideAction,
    PersonalTask,
    ReminderSettings,
    Role,
    SchoolClass,
    Subject,
)
from app.security import hash_token, new_join_code
from app.services import linking, subjects

MONDAY = date(2026, 9, 7)
MOSCOW = ZoneInfo("Europe/Moscow")

EDITOR_ID = 5001
VIEWER_ID = 5002
STRANGER_ID = 5003


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


async def _token(client, code: str = "TEST42", device_name: str = "pytest") -> str:
    response = await client.post("/api/v1/join", json={"code": code, "device_name": device_name})
    assert response.status_code == 200, response.text
    return response.json()["token"]


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _link(client, session, token: str, telegram_id: int) -> DeviceToken:
    """Link the way a person does: read the code off /me, type it into the bot."""
    me = await client.get("/api/v1/me", headers=_auth(token))
    assert me.status_code == 200, me.text
    device = await linking.link_device(session, me.json()["link_code"], telegram_id)
    assert device is not None
    return device


async def _member(session, school_class, telegram_id: int, role: Role) -> None:
    session.add(BotUser(telegram_id=telegram_id, class_id=school_class.id, role=role))
    await session.commit()


async def _linked_token(client, session, school_class, telegram_id: int, role: Role | None) -> str:
    if role is not None:
        await _member(session, school_class, telegram_id, role)
    token = await _token(client)
    await _link(client, session, token, telegram_id)
    return token


async def _homework(session, school_class, due: date, subject: str = "Алгебра") -> Homework:
    item = Homework(class_id=school_class.id, due_date=due, subject_name=subject, text="№ 12–15")
    session.add(item)
    await session.commit()
    return item


async def _audit(session, school_class) -> list[AuditEntry]:
    rows = await session.scalars(
        select(AuditEntry).where(AuditEntry.class_id == school_class.id).order_by(AuditEntry.id)
    )
    return list(rows)


def _pin_clock(monkeypatch, at: datetime) -> None:
    monkeypatch.setattr(public, "_now", lambda school_class: at.astimezone(school_class.tz))


# --------------------------------------------------------------------------
# /bundle: ETag and the device block
# --------------------------------------------------------------------------


async def test_bundle_answers_304_to_a_matching_etag(client, school_class):
    token = await _token(client)
    params = {"start": MONDAY.isoformat(), "days": 7}

    first = await client.get("/api/v1/bundle", params=params, headers=_auth(token))
    assert first.status_code == 200
    etag = first.headers["ETag"]
    assert etag.startswith('"') and etag.endswith('"') and len(etag) == 66

    # generated_at differs between the two calls; the validator must not.
    second = await client.get("/api/v1/bundle", params=params, headers=_auth(token))
    assert second.headers["ETag"] == etag

    cached = await client.get(
        "/api/v1/bundle", params=params, headers={**_auth(token), "If-None-Match": etag}
    )
    assert cached.status_code == 304
    assert cached.content == b""
    assert cached.headers["ETag"] == etag

    weak = await client.get(
        "/api/v1/bundle",
        params=params,
        headers={**_auth(token), "If-None-Match": f'"other", W/{etag}'},
    )
    assert weak.status_code == 304

    stale = await client.get(
        "/api/v1/bundle", params=params, headers={**_auth(token), "If-None-Match": '"nope"'}
    )
    assert stale.status_code == 200


async def test_bundle_etag_changes_with_the_content(client, session, school_class):
    token = await _token(client)
    params = {"start": MONDAY.isoformat(), "days": 7}
    before = (await client.get("/api/v1/bundle", params=params, headers=_auth(token))).headers[
        "ETag"
    ]
    await _homework(session, school_class, MONDAY)
    after = (await client.get("/api/v1/bundle", params=params, headers=_auth(token))).headers[
        "ETag"
    ]
    assert before != after


async def test_bundle_describes_an_unlinked_device(client, school_class):
    token = await _token(client)
    body = (await client.get("/api/v1/bundle", headers=_auth(token))).json()
    assert body["api_version"] == 1
    assert body["device"] == {"linked": False, "role": None, "can_edit": False}


# --------------------------------------------------------------------------
# /me and linking
# --------------------------------------------------------------------------


async def test_me_issues_a_link_code_once_and_repeats_it(client, school_class):
    token = await _token(client, device_name="Pixel 8")
    first = await client.get("/api/v1/me", headers=_auth(token))
    assert first.status_code == 200
    body = first.json()
    assert body["device_name"] == "Pixel 8"
    assert body["linked"] is False
    assert body["role"] is None
    assert body["can_edit"] is False
    assert len(body["link_code"]) == 6
    # No bot username configured: no deep link to offer.
    assert body["bot_deep_link"] is None

    second = await client.get("/api/v1/me", headers=_auth(token))
    assert second.json()["link_code"] == body["link_code"]


async def test_me_offers_a_deep_link_when_the_bot_has_a_username(client, school_class, monkeypatch):
    monkeypatch.setattr(get_settings(), "bot_username", "@lessons_bot")
    token = await _token(client)
    body = (await client.get("/api/v1/me", headers=_auth(token))).json()
    assert body["bot_deep_link"] == f"https://t.me/lessons_bot?start=link_{body['link_code']}"


async def test_linking_shows_up_on_me_and_in_the_bundle(client, session, school_class):
    await _member(session, school_class, EDITOR_ID, Role.EDITOR)
    token = await _token(client)
    await _link(client, session, token, EDITOR_ID)

    me = (await client.get("/api/v1/me", headers=_auth(token))).json()
    assert me["linked"] is True
    assert me["role"] == "editor"
    assert me["can_edit"] is True
    assert me["link_code"] is None and me["bot_deep_link"] is None
    # The Telegram id stays on the server.
    assert "telegram_id" not in me

    bundle = (await client.get("/api/v1/bundle", headers=_auth(token))).json()
    assert bundle["device"] == {"linked": True, "role": "editor", "can_edit": True}


async def test_a_linked_account_without_a_role_cannot_edit(client, session, school_class):
    token = await _linked_token(client, session, school_class, STRANGER_ID, None)
    me = (await client.get("/api/v1/me", headers=_auth(token))).json()
    assert me == {
        "device_name": "pytest",
        "linked": True,
        "role": None,
        "can_edit": False,
        "link_code": None,
        "bot_deep_link": None,
    }


async def test_unlink_goes_back_to_read_only_with_a_fresh_code(client, session, school_class):
    token = await _linked_token(client, session, school_class, VIEWER_ID, Role.VIEWER)
    first_code = None

    response = await client.post("/api/v1/me/unlink", headers=_auth(token))
    assert response.status_code == 200
    assert response.json() == {"linked": False}

    me = (await client.get("/api/v1/me", headers=_auth(token))).json()
    assert me["linked"] is False and me["role"] is None
    assert me["link_code"] != first_code and len(me["link_code"]) == 6

    # Idempotent.
    assert (await client.post("/api/v1/me/unlink", headers=_auth(token))).status_code == 200


async def test_me_requires_a_bearer_token(client, school_class):
    assert (await client.get("/api/v1/me")).status_code == 401


# --------------------------------------------------------------------------
# Homework list and ticks
# --------------------------------------------------------------------------


async def test_homework_list_is_windowed_and_ordered(client, session, school_class, monkeypatch):
    _pin_clock(monkeypatch, datetime(2026, 9, 7, 10, 0, tzinfo=MOSCOW))
    await _homework(session, school_class, MONDAY + timedelta(days=1), "Физика")
    await _homework(session, school_class, MONDAY + timedelta(days=1), "Алгебра")
    await _homework(session, school_class, MONDAY, "История")
    await _homework(session, school_class, MONDAY + timedelta(days=40), "Химия")
    await _homework(session, school_class, MONDAY - timedelta(days=1), "Вчерашнее")
    token = await _token(client)

    default = await client.get("/api/v1/homework", headers=_auth(token))
    assert default.status_code == 200
    rows = default.json()
    assert [(row["due_date"], row["subject"]) for row in rows] == [
        ("2026-09-07", "История"),
        ("2026-09-08", "Алгебра"),
        ("2026-09-08", "Физика"),
    ]
    assert rows[0]["text"] == "№ 12–15"
    assert rows[0]["attachment_url"] is None
    assert all(row["done"] is False for row in rows)

    wide = await client.get(
        "/api/v1/homework",
        params={"from": "2026-09-01", "to": "2026-10-31"},
        headers=_auth(token),
    )
    assert [row["subject"] for row in wide.json()] == [
        "Вчерашнее",
        "История",
        "Алгебра",
        "Физика",
        "Химия",
    ]


@pytest.mark.parametrize(
    "params",
    [
        {"from": "2026-09-01", "to": "2026-12-31"},
        {"from": "2026-09-10", "to": "2026-09-01"},
        {"from": "1999-01-01"},
        {"from": "not-a-date"},
    ],
)
async def test_homework_list_rejects_a_bad_window(client, school_class, params):
    token = await _token(client)
    response = await client.get("/api/v1/homework", params=params, headers=_auth(token))
    assert response.status_code == 422


async def test_homework_done_needs_a_linked_device(client, session, school_class):
    item = await _homework(session, school_class, MONDAY)
    token = await _token(client)
    response = await client.post(
        f"/api/v1/homework/{item.id}/done", json={"done": True}, headers=_auth(token)
    )
    assert response.status_code == 403
    assert response.json()["detail"] == "device is not linked"


async def test_homework_done_is_per_person_and_idempotent(client, session, school_class):
    item = await _homework(session, school_class, MONDAY)
    mine = await _linked_token(client, session, school_class, VIEWER_ID, Role.VIEWER)
    theirs = await _linked_token(client, session, school_class, STRANGER_ID, None)

    url = f"/api/v1/homework/{item.id}/done"
    assert (await client.post(url, json={"done": True}, headers=_auth(mine))).json() == {
        "id": item.id,
        "done": True,
    }
    # Sent twice (a retry): still done, not toggled back.
    assert (await client.post(url, json={"done": True}, headers=_auth(mine))).json()["done"] is True

    params = {"from": MONDAY.isoformat(), "to": MONDAY.isoformat()}
    assert (await client.get("/api/v1/homework", params=params, headers=_auth(mine))).json()[0][
        "done"
    ] is True
    assert (await client.get("/api/v1/homework", params=params, headers=_auth(theirs))).json()[0][
        "done"
    ] is False

    undone = await client.post(url, json={"done": False}, headers=_auth(mine))
    assert undone.json()["done"] is False
    assert (await client.get("/api/v1/homework", params=params, headers=_auth(mine))).json()[0][
        "done"
    ] is False


async def test_homework_done_404s_outside_the_class(client, session, school_class):
    other = SchoolClass(name="11Б", join_code=new_join_code())
    session.add(other)
    await session.commit()
    foreign = await _homework(session, other, MONDAY)
    token = await _linked_token(client, session, school_class, VIEWER_ID, Role.VIEWER)

    response = await client.post(
        f"/api/v1/homework/{foreign.id}/done", json={"done": True}, headers=_auth(token)
    )
    assert response.status_code == 404
    assert (
        await client.post("/api/v1/homework/999999/done", json={"done": True}, headers=_auth(token))
    ).status_code == 404


# --------------------------------------------------------------------------
# /subjects
# --------------------------------------------------------------------------


async def test_subjects_lists_the_dictionary(client, session, school_class):
    session.add(Subject(class_id=school_class.id, name="Физика", short_name="Физ", color="#3E8E7E"))
    session.add(Subject(class_id=school_class.id, name="Алгебра", teacher="Иванова А. П."))
    await session.commit()
    token = await _token(client)

    response = await client.get("/api/v1/subjects", headers=_auth(token))
    assert response.status_code == 200
    assert response.json() == [
        {"name": "Алгебра", "short_name": None, "teacher": "Иванова А. П.", "color": None},
        {"name": "Физика", "short_name": "Физ", "teacher": None, "color": "#3E8E7E"},
    ]


# --------------------------------------------------------------------------
# /now
# --------------------------------------------------------------------------


async def test_now_inside_the_first_lesson(client, school_class, monkeypatch):
    # Lesson 1 runs 08:30–09:15 on the default bells.
    _pin_clock(monkeypatch, datetime(2026, 9, 7, 8, 40, 30, tzinfo=MOSCOW))
    token = await _token(client)
    body = (await client.get("/api/v1/now", headers=_auth(token))).json()

    assert body["date"] == "2026-09-07"
    assert body["time"] == "08:40:30"
    assert body["state"] == "lesson"
    assert body["current"]["subject"] == "Алгебра"
    assert body["next"]["subject"] == "Физика"
    # 34 minutes 30 seconds to the end of the lesson.
    assert body["until_next_seconds"] == 34 * 60 + 30
    assert body["next_school_day"] == "2026-09-14"


async def test_now_in_a_break_and_before_school(client, school_class, monkeypatch):
    _pin_clock(monkeypatch, datetime(2026, 9, 7, 9, 20, tzinfo=MOSCOW))
    token = await _token(client)
    body = (await client.get("/api/v1/now", headers=_auth(token))).json()
    assert body["state"] == "break"
    assert body["current"] is None
    assert body["next"]["subject"] == "Физика"
    assert body["until_next_seconds"] == 5 * 60

    _pin_clock(monkeypatch, datetime(2026, 9, 7, 7, 0, tzinfo=MOSCOW))
    body = (await client.get("/api/v1/now", headers=_auth(token))).json()
    assert body["state"] == "before_school"
    assert body["next"]["subject"] == "Алгебра"
    assert body["until_next_seconds"] == 90 * 60


async def test_now_after_school_and_on_a_day_off(client, school_class, monkeypatch):
    _pin_clock(monkeypatch, datetime(2026, 9, 7, 15, 0, tzinfo=MOSCOW))
    token = await _token(client)
    body = (await client.get("/api/v1/now", headers=_auth(token))).json()
    assert body["state"] == "after_school"
    assert body["current"] is None and body["next"] is None
    assert body["until_next_seconds"] is None
    assert body["next_school_day"] == "2026-09-14"

    # Tuesday: the fixture class has no lessons.
    _pin_clock(monkeypatch, datetime(2026, 9, 8, 10, 0, tzinfo=MOSCOW))
    body = (await client.get("/api/v1/now", headers=_auth(token))).json()
    assert body["state"] == "day_off"
    assert body["next_school_day"] == "2026-09-14"


async def test_now_skips_a_cancelled_lesson(client, session, school_class, monkeypatch):
    session.add(
        LessonOverride(
            class_id=school_class.id, date=MONDAY, index=1, action=OverrideAction.CANCEL
        )
    )
    await session.commit()
    _pin_clock(monkeypatch, datetime(2026, 9, 7, 8, 40, tzinfo=MOSCOW))
    token = await _token(client)
    body = (await client.get("/api/v1/now", headers=_auth(token))).json()
    assert body["state"] == "before_school"
    assert body["next"]["subject"] == "Физика"


async def test_now_reports_no_data_for_a_class_without_a_timetable(client, session, monkeypatch):
    session.add(SchoolClass(name="Пустой", join_code="EMPTY1"))
    await session.commit()
    _pin_clock(monkeypatch, datetime(2026, 9, 7, 10, 0, tzinfo=MOSCOW))
    token = await _token(client, code="EMPTY1")
    body = (await client.get("/api/v1/now", headers=_auth(token))).json()
    assert body["state"] == "no_data"
    assert body["next_school_day"] is None


# --------------------------------------------------------------------------
# Personal tasks
# --------------------------------------------------------------------------


async def test_tasks_require_a_linked_device(client, school_class):
    token = await _token(client)
    assert (await client.get("/api/v1/tasks", headers=_auth(token))).status_code == 403
    response = await client.post("/api/v1/tasks", json={"title": "x"}, headers=_auth(token))
    assert response.status_code == 403
    assert response.json()["detail"] == "device is not linked"


async def test_tasks_crud_is_scoped_to_the_owner(client, session, school_class):
    mine = await _linked_token(client, session, school_class, VIEWER_ID, Role.VIEWER)
    theirs = await _linked_token(client, session, school_class, STRANGER_ID, None)

    created = await client.post(
        "/api/v1/tasks",
        json={
            "title": "  Купить   тетрадь ",
            "notes": "в клетку",
            "due_date": "2026-09-15",
            "due_time": "18:00",
            "priority": 2,
            "subject_name": "Алгебра",
            "remind_at": "2026-09-15T08:00:00",
        },
        headers=_auth(mine),
    )
    assert created.status_code == 201, created.text
    task = created.json()
    assert task["title"] == "Купить тетрадь"
    assert task["notes"] == "в клетку"
    assert task["due_date"] == "2026-09-15" and task["due_time"] == "18:00:00"
    assert task["priority"] == 2 and task["done"] is False
    assert task["remind_at"] == "2026-09-15T08:00:00"
    assert task["created_at"] is not None
    task_id = task["id"]

    listed = (await client.get("/api/v1/tasks", headers=_auth(mine))).json()
    assert [row["id"] for row in listed] == [task_id]
    assert (await client.get("/api/v1/tasks", headers=_auth(theirs))).json() == []

    # The other owner cannot see, change, tick or delete it.
    for method, url, body in [
        ("PATCH", f"/api/v1/tasks/{task_id}", {"title": "чужое"}),
        ("POST", f"/api/v1/tasks/{task_id}/done", {"done": True}),
        ("DELETE", f"/api/v1/tasks/{task_id}", None),
    ]:
        response = await client.request(method, url, json=body, headers=_auth(theirs))
        assert response.status_code == 404, (method, response.text)

    patched = await client.patch(
        f"/api/v1/tasks/{task_id}",
        json={"title": "Купить две тетради", "due_time": None, "priority": 0},
        headers=_auth(mine),
    )
    assert patched.status_code == 200, patched.text
    assert patched.json()["title"] == "Купить две тетради"
    assert patched.json()["due_time"] is None
    assert patched.json()["due_date"] == "2026-09-15"
    assert patched.json()["priority"] == 0

    done = await client.post(
        f"/api/v1/tasks/{task_id}/done", json={"done": True}, headers=_auth(mine)
    )
    assert done.json()["done"] is True and done.json()["done_at"] is not None
    assert (await client.get("/api/v1/tasks", headers=_auth(mine))).json() == []
    with_done = await client.get(
        "/api/v1/tasks", params={"include_done": "true"}, headers=_auth(mine)
    )
    assert [row["id"] for row in with_done.json()] == [task_id]

    undone = await client.patch(
        f"/api/v1/tasks/{task_id}", json={"done": False}, headers=_auth(mine)
    )
    assert undone.json()["done"] is False and undone.json()["done_at"] is None

    deleted = await client.delete(f"/api/v1/tasks/{task_id}", headers=_auth(mine))
    assert deleted.status_code == 204
    assert (await client.delete(f"/api/v1/tasks/{task_id}", headers=_auth(mine))).status_code == 404
    assert await session.scalar(select(PersonalTask).where(PersonalTask.id == task_id)) is None


async def test_task_validation(client, session, school_class):
    token = await _linked_token(client, session, school_class, VIEWER_ID, Role.VIEWER)
    for body in [{"title": "   "}, {"title": "x", "priority": 3}, {"title": "x" * 201}, {}]:
        response = await client.post("/api/v1/tasks", json=body, headers=_auth(token))
        assert response.status_code == 422, body

    # A homework link must point into this class.
    other = SchoolClass(name="11Б", join_code=new_join_code())
    session.add(other)
    await session.commit()
    foreign = await _homework(session, other, MONDAY)
    response = await client.post(
        "/api/v1/tasks", json={"title": "x", "homework_id": foreign.id}, headers=_auth(token)
    )
    assert response.status_code == 422

    own = await _homework(session, school_class, MONDAY)
    response = await client.post(
        "/api/v1/tasks", json={"title": "x", "homework_id": own.id}, headers=_auth(token)
    )
    assert response.status_code == 201
    assert response.json()["homework_id"] == own.id


async def test_a_patch_may_not_null_a_column_that_cannot_be_null(
    client, session, school_class
):
    """``TaskPatch`` tells "absent" and "null" apart so that null can *clear* a
    field - and both of these columns are NOT NULL. Sending null used to reach
    the UPDATE and come back as a 500 out of an IntegrityError, on a request
    the app makes by clearing a text box. ``ClassPatch`` and ``SubjectPatch``
    have refused the same shape since they were written.
    """
    token = await _linked_token(client, session, school_class, VIEWER_ID, Role.VIEWER)
    created = await client.post("/api/v1/tasks", json={"title": "x"}, headers=_auth(token))
    assert created.status_code == 201, created.text
    task_id = created.json()["id"]

    for body in [{"title": None}, {"priority": None}]:
        response = await client.patch(
            f"/api/v1/tasks/{task_id}", json=body, headers=_auth(token)
        )
        assert response.status_code == 422, (body, response.text)

    # The nullable neighbours still clear, which is what the null is *for*.
    response = await client.patch(
        f"/api/v1/tasks/{task_id}",
        json={"notes": None, "due_date": None, "subject_name": None},
        headers=_auth(token),
    )
    assert response.status_code == 200, response.text
    assert response.json()["notes"] is None


async def test_an_aware_remind_at_is_stored_as_class_wall_time(client, session, school_class):
    token = await _linked_token(client, session, school_class, VIEWER_ID, Role.VIEWER)
    response = await client.post(
        "/api/v1/tasks",
        json={"title": "x", "remind_at": "2026-09-15T05:00:00Z"},
        headers=_auth(token),
    )
    assert response.status_code == 201
    # 05:00 UTC is 08:00 in Moscow.
    assert response.json()["remind_at"] == "2026-09-15T08:00:00"


# --------------------------------------------------------------------------
# Calendar
# --------------------------------------------------------------------------


async def test_calendar_url_mints_the_token_once(client, session, school_class, monkeypatch):
    token = await _token(client)
    first = await client.get("/api/v1/calendar", headers=_auth(token))
    assert first.status_code == 200
    url = first.json()["url"]
    assert url.startswith("http://test/api/v1/calendar/") and url.endswith(".ics")
    assert (await client.get("/api/v1/calendar", headers=_auth(token))).json()["url"] == url

    monkeypatch.setattr(get_settings(), "public_base_url", "https://lessons.example.com/")
    public_url = (await client.get("/api/v1/calendar", headers=_auth(token))).json()["url"]
    assert public_url == url.replace("http://test", "https://lessons.example.com")


async def test_calendar_feed_is_icalendar_with_crlf(client, session, school_class, monkeypatch):
    _pin_clock(monkeypatch, datetime(2026, 9, 7, 7, 0, tzinfo=MOSCOW))
    await _homework(session, school_class, MONDAY, "Алгебра")
    session.add(
        DayEvent(
            class_id=school_class.id,
            date=MONDAY,
            starts_at=time(12, 30),
            ends_at=time(13, 0),
            title="Обед, столовая",
            kind=EventKind.CANTEEN,
        )
    )
    await session.commit()
    token = await _token(client)
    url = (await client.get("/api/v1/calendar", headers=_auth(token))).json()["url"]

    feed = await client.get(url)
    assert feed.status_code == 200
    assert feed.headers["content-type"].startswith("text/calendar")
    assert feed.headers["cache-control"] == "private, max-age=900"
    body = feed.text
    assert body.startswith("BEGIN:VCALENDAR\r\n")
    assert body.endswith("END:VCALENDAR\r\n")
    assert "\n" not in body.replace("\r\n", "")
    assert "X-WR-CALNAME:9А" in body
    assert "DTSTART;TZID=Europe/Moscow:20260907T083000" in body
    assert "SUMMARY:Алгебра" in body
    assert "SUMMARY:Обед\\, столовая" in body
    assert "BEGIN:VTODO" in body
    # The class feed is shared: nobody's personal tasks are in it.
    assert "UID:task-" not in body


async def test_calendar_feed_404s_on_an_unknown_token(client, school_class):
    assert (await client.get("/api/v1/calendar/nope.ics")).status_code == 404
    assert (await client.get("/api/v1/calendar/" + "x" * 80 + ".ics")).status_code == 404
    assert (await client.get("/api/v1/calendar/.ics")).status_code == 404


# --------------------------------------------------------------------------
# Write endpoints
# --------------------------------------------------------------------------

WRITES = [
    ("PUT", "/api/v1/homework", {"due_date": "2026-09-14", "subject": "Алгебра", "text": "№ 1"}),
    ("DELETE", "/api/v1/homework/1", None),
    ("PUT", "/api/v1/overrides", {"date": "2026-09-14", "index": 1, "action": "cancel"}),
    (
        "PUT",
        "/api/v1/events",
        {"date": "2026-09-14", "starts_at": "12:00", "ends_at": "12:30", "title": "Обед"},
    ),
    ("DELETE", "/api/v1/events/1", None),
    ("PUT", "/api/v1/days", {"date": "2026-09-14", "kind": "holiday"}),
]


@pytest.mark.parametrize("method,url,body", WRITES)
async def test_writes_refuse_an_unlinked_device(client, school_class, method, url, body):
    token = await _token(client)
    response = await client.request(method, url, json=body, headers=_auth(token))
    assert response.status_code == 403
    assert response.json()["detail"] == "device is not linked"


@pytest.mark.parametrize("method,url,body", WRITES)
async def test_writes_refuse_a_viewer(client, session, school_class, method, url, body):
    token = await _linked_token(client, session, school_class, VIEWER_ID, Role.VIEWER)
    response = await client.request(method, url, json=body, headers=_auth(token))
    assert response.status_code == 403
    assert response.json()["detail"] == "editor role required"
    assert await _audit(session, school_class) == []


@pytest.mark.parametrize("method,url,body", WRITES)
async def test_writes_refuse_a_linked_account_with_no_role(
    client, session, school_class, method, url, body
):
    token = await _linked_token(client, session, school_class, STRANGER_ID, None)
    response = await client.request(method, url, json=body, headers=_auth(token))
    assert response.status_code == 403
    assert response.json()["detail"] == "editor role required"


async def test_writes_require_a_bearer_token(client, school_class):
    response = await client.put("/api/v1/homework", json=WRITES[0][2])
    assert response.status_code == 401


@pytest.fixture
def recording_bot(monkeypatch):
    """Give the write endpoints a bot to notify with, and record what it sends."""
    bot = FakeBot()
    monkeypatch.setattr(get_settings(), "bot_token", "123456:TEST")
    monkeypatch.setattr(edit, "_build_bot", lambda: bot)
    return bot


async def _subscriber(session, school_class, telegram_id: int, **flags) -> None:
    session.add(ReminderSettings(class_id=school_class.id, telegram_id=telegram_id, **flags))
    await session.commit()


async def test_homework_in_another_case_updates_the_task_already_set(
    client, session, school_class, recording_bot
):
    """Homework upserts on (date, subject_name), so a phone sending «алгебра»
    used to found a second assignment beside the «Алгебра» already there — and
    both then went out in the evening digest. It also survived a rename, which
    moves homework by exact old name, and afterwards named a subject the class
    no longer had.

    The dictionary's spelling is stored instead, so the second send lands on
    the first.
    """
    await subjects.sync_from_timetable(session, school_class.id)
    await session.commit()
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)

    first = await client.put(
        "/api/v1/homework",
        json={"due_date": "2026-09-14", "subject": "Алгебра", "text": "№ 1"},
        headers=_auth(token),
    )
    shouted = await client.put(
        "/api/v1/homework",
        json={"due_date": "2026-09-14", "subject": "  АЛГЕБРА ", "text": "№ 2"},
        headers=_auth(token),
    )

    assert shouted.status_code == 200, shouted.text
    assert shouted.json()["id"] == first.json()["id"]
    assert shouted.json()["subject"] == "Алгебра"
    rows = list(await session.scalars(select(Homework).where(Homework.class_id == school_class.id)))
    assert len(rows) == 1 and rows[0].text == "№ 2"


async def test_homework_for_a_subject_the_class_has_no_entry_for_is_kept_as_typed(
    client, session, school_class, recording_bot
):
    """Never founds a dictionary entry: a picker that grew a subject because
    somebody wrote down an assignment would be learning from the wrong half of
    the app."""
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)

    response = await client.put(
        "/api/v1/homework",
        json={"due_date": "2026-09-14", "subject": "Астрономия", "text": "§ 4"},
        headers=_auth(token),
    )

    assert response.json()["subject"] == "Астрономия"
    assert await subjects.find(session, school_class.id, "Астрономия") is None


async def test_homework_put_upserts_audits_and_notifies(
    client, session, school_class, recording_bot
):
    await _subscriber(session, school_class, 7001, notify_homework=True)
    await _subscriber(session, school_class, 7002, notify_homework=False)
    await _subscriber(session, school_class, EDITOR_ID, notify_homework=True)
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)

    created = await client.put(
        "/api/v1/homework",
        json={"due_date": "2026-09-14", "subject": "Алгебра", "text": "№ 12–15 <b>"},
        headers=_auth(token),
    )
    assert created.status_code == 200, created.text
    body = created.json()
    assert body["subject"] == "Алгебра" and body["due_date"] == "2026-09-14"
    assert body["text"] == "№ 12–15 <b>" and body["done"] is False
    homework_id = body["id"]

    updated = await client.put(
        "/api/v1/homework",
        json={
            "due_date": "2026-09-14",
            "subject": "Алгебра",
            "text": "№ 16",
            "attachment_url": "https://example.com/p.pdf",
        },
        headers=_auth(token),
    )
    assert updated.json()["id"] == homework_id
    assert updated.json()["text"] == "№ 16"
    assert updated.json()["attachment_url"] == "https://example.com/p.pdf"

    rows = list(await session.scalars(select(Homework).where(Homework.class_id == school_class.id)))
    assert len(rows) == 1 and rows[0].created_by == EDITOR_ID

    entries = await _audit(session, school_class)
    assert [(row.action, row.telegram_id) for row in entries] == [
        ("homework.add", EDITOR_ID),
        ("homework.update", EDITOR_ID),
    ]
    assert "Алгебра" in entries[0].summary

    # Subscribers with the homework flag, minus the author; HTML escaped.
    assert [chat_id for chat_id, _ in recording_bot.sent] == [7001, 7001]
    assert "&lt;b&gt;" in recording_bot.sent[0][1]
    assert "<b>Алгебра</b>" in recording_bot.sent[0][1]
    assert recording_bot.closed


async def test_homework_delete(client, session, school_class, recording_bot):
    item = await _homework(session, school_class, MONDAY + timedelta(days=7))
    await _subscriber(session, school_class, 7001, notify_homework=True)
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)

    response = await client.delete(f"/api/v1/homework/{item.id}", headers=_auth(token))
    assert response.status_code == 200
    assert response.json() == {"id": item.id, "deleted": True}
    assert await session.scalar(select(Homework).where(Homework.id == item.id)) is None
    assert [row.action for row in await _audit(session, school_class)] == ["homework.delete"]
    assert len(recording_bot.sent) == 1 and "удалено" in recording_bot.sent[0][1]

    again = await client.delete(f"/api/v1/homework/{item.id}", headers=_auth(token))
    assert again.status_code == 404


async def test_writes_work_without_a_bot_token(client, session, school_class):
    """No BOT_TOKEN: the edit still lands, nobody is notified, nothing breaks."""
    assert get_settings().bot_token == ""
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)
    response = await client.put("/api/v1/homework", json=WRITES[0][2], headers=_auth(token))
    assert response.status_code == 200
    assert len(await _audit(session, school_class)) == 1


async def test_override_replace_cancel_and_clear(client, session, school_class, recording_bot):
    await _subscriber(session, school_class, 7001, notify_changes=True)
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)
    next_monday = (MONDAY + timedelta(days=7)).isoformat()

    replaced = await client.put(
        "/api/v1/overrides",
        json={
            "date": next_monday,
            "index": 2,
            "action": "replace",
            "subject": "Химия",
            "room": "118",
            "note": "учитель на конференции",
        },
        headers=_auth(token),
    )
    assert replaced.status_code == 200, replaced.text
    assert replaced.json() == {
        "date": next_monday,
        "index": 2,
        "action": "replace",
        "subject": "Химия",
        "room": "118",
        "teacher": None,
        "note": "учитель на конференции",
    }

    bundle = (
        await client.get(
            "/api/v1/bundle", params={"start": next_monday, "days": 1}, headers=_auth(token)
        )
    ).json()
    lesson = bundle["days"][0]["lessons"][1]
    assert lesson["subject"] == "Химия" and lesson["room"] == "118" and lesson["is_replaced"]

    cancelled = await client.put(
        "/api/v1/overrides",
        json={"date": next_monday, "index": 2, "action": "cancel"},
        headers=_auth(token),
    )
    assert cancelled.json()["action"] == "cancel" and cancelled.json()["subject"] is None
    stored = select(LessonOverride).where(LessonOverride.class_id == school_class.id)
    rows = list(await session.scalars(stored))
    assert len(rows) == 1 and rows[0].action is OverrideAction.CANCEL

    cleared = await client.put(
        "/api/v1/overrides",
        json={"date": next_monday, "index": 2, "action": "clear"},
        headers=_auth(token),
    )
    assert cleared.json()["action"] == "clear"
    assert await session.scalar(stored) is None
    # Clearing what is not there is fine and is not logged.
    assert (
        await client.put(
            "/api/v1/overrides",
            json={"date": next_monday, "index": 2, "action": "clear"},
            headers=_auth(token),
        )
    ).status_code == 200

    assert [row.action for row in await _audit(session, school_class)] == [
        "override.replace",
        "override.cancel",
        "override.clear",
    ]
    assert [chat_id for chat_id, _ in recording_bot.sent] == [7001, 7001, 7001]
    assert "Химия" in recording_bot.sent[0][1]


async def test_override_validation(client, session, school_class):
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)
    for body in [
        {"date": "2026-09-14", "index": 1, "action": "replace"},
        {"date": "2026-09-14", "index": 0, "action": "cancel"},
        {"date": "2026-09-14", "index": 1, "action": "add"},
        {"date": "2200-01-01", "index": 1, "action": "cancel"},
    ]:
        response = await client.put("/api/v1/overrides", json=body, headers=_auth(token))
        assert response.status_code == 422, body


async def test_events_add_and_delete(client, session, school_class, recording_bot):
    await _subscriber(session, school_class, 7001, notify_changes=True)
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)

    created = await client.put(
        "/api/v1/events",
        json={
            "date": "2026-09-14",
            "starts_at": "12:30",
            "ends_at": "13:00",
            "title": "Экскурсия",
            "kind": "trip",
            "location": "Эрмитаж",
        },
        headers=_auth(token),
    )
    assert created.status_code == 201, created.text
    event_id = created.json()["id"]
    event = await session.get(DayEvent, event_id)
    assert event.kind is EventKind.TRIP and event.covers_lesson is True
    assert event.location == "Эрмитаж"

    canteen = await client.put(
        "/api/v1/events",
        json={"date": "2026-09-14", "starts_at": "11:10", "ends_at": "11:25", "title": "Обед",
              "kind": "canteen"},
        headers=_auth(token),
    )
    assert (await session.get(DayEvent, canteen.json()["id"])).covers_lesson is False

    bad = await client.put(
        "/api/v1/events",
        json={"date": "2026-09-14", "starts_at": "13:00", "ends_at": "12:30", "title": "x"},
        headers=_auth(token),
    )
    assert bad.status_code == 422

    deleted = await client.delete(f"/api/v1/events/{event_id}", headers=_auth(token))
    assert deleted.status_code == 200 and deleted.json()["deleted"] is True
    again = await client.delete(f"/api/v1/events/{event_id}", headers=_auth(token))
    assert again.status_code == 404

    assert [row.action for row in await _audit(session, school_class)] == [
        "event.add",
        "event.add",
        "event.delete",
    ]
    assert len(recording_bot.sent) == 3
    assert "Экскурсия" in recording_bot.sent[0][1]


async def test_a_day_cannot_ring_a_bell_schedule_that_has_no_rows(
    client, session, school_class, recording_bot
):
    """«Сокращённые уроки» has to ring something, and an empty schedule rings nothing.

    `POST /api/v1/manage/bells` may create a schedule with no rows on purpose —
    «a schedule may be created empty and filled in afterwards», says
    `BellScheduleIn` — so this is a state a class really reaches: make the
    short schedule, point Friday at it, fill the times in later. The resolver
    takes a lesson's times from the bell row of the *same number*, so until
    those rows exist the day draws nothing at all: an empty Friday on every
    phone, in the widget and in the calendar feed, under a card that says
    «⏱ Сокращённые уроки», with nothing logged anywhere.

    Worse, `timetable_edit.rung_indexes_on` falls back to the class's default
    bells when the named schedule has no rows, so a substitution for that day is
    accepted at any number the *ordinary* day rings — the one check that exists
    to stop a lesson being stored where nothing can draw it.

    Refused at the door, which is the decision `day_put` already makes for a
    shortened day with no schedule at all.
    """
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)
    day = (MONDAY + timedelta(days=7)).isoformat()

    empty = BellSchedule(class_id=school_class.id, name="Пустое")
    session.add(empty)
    await session.commit()

    refused = await client.put(
        "/api/v1/days",
        json={"date": day, "kind": "shortened", "bell_schedule_id": empty.id},
        headers=_auth(token),
    )
    assert refused.status_code == 422, refused.text
    assert refused.json()["detail"] == "в этом расписании звонков нет ни одного урока"

    # Nothing was written, so nothing draws an empty day and no substitution can be
    # hung on one.
    assert (
        await session.scalar(select(DayOverride).where(DayOverride.class_id == school_class.id))
    ) is None
    bundle = await client.get(
        "/api/v1/bundle", params={"start": day, "days": 1}, headers=_auth(token)
    )
    drawn = bundle.json()["days"][0]
    assert drawn["kind"] == "normal" and len(drawn["lessons"]) == 3


async def test_cancelling_a_lesson_the_day_does_not_have_is_refused(
    client, session, school_class, recording_bot
):
    """«🚫 Урок №7 отменён» about a number nobody was going to be at.

    A substitution at an empty number is a legitimate edit — it is how a lesson
    is *added* to a day — but a cancellation needs something to cancel. The row
    was stored, written to the log and announced to every subscriber, and
    then the resolver dropped it on the way out, because it only cancels a
    lesson the day actually has. The bot cannot reach this: it draws its «🚫»
    under a lesson that exists.
    """
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)
    # The class rings seven bells and Monday has three lessons, so the seventh
    # passes the bell check and still has nothing in it.
    refused = await client.put(
        "/api/v1/overrides",
        json={"date": (MONDAY + timedelta(days=7)).isoformat(), "index": 7, "action": "cancel"},
        headers=_auth(token),
    )

    assert refused.status_code == 422, refused.text
    assert "отменять нечего" in refused.json()["detail"]
    assert await session.scalar(select(LessonOverride)) is None
    assert recording_bot.sent == []

    # The other action at the same number is still accepted: that is how a
    # lesson gets added to a day at all.
    added = await client.put(
        "/api/v1/overrides",
        json={
            "date": (MONDAY + timedelta(days=7)).isoformat(),
            "index": 7,
            "action": "replace",
            "subject": "Астрономия",
        },
        headers=_auth(token),
    )
    assert added.status_code == 200, added.text


async def test_a_substitution_with_no_subject_at_an_empty_number_is_refused(
    client, session, school_class, recording_bot
):
    """«🔁 Замена … — кабинет/учитель» about a lesson that is not there.

    `OverrideIn` accepts a replacement carrying only a room or only a teacher,
    which is right when there is a template lesson underneath to inherit the
    subject from. With nothing underneath, `subject_name` lands NULL, and the
    resolver drops the row — `subject = override.subject_name or (existing.subject
    if existing else None)` is None and the loop moves on. The row was stored,
    written to the log, and announced to every subscriber as a change to a
    lesson no phone, no widget and no calendar feed will ever show.

    The bot cannot reach it: it builds its picker from the day's own lessons and
    always asks for a typed subject. This is the API-only half of the invariant
    the cancellation above already holds.
    """
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)
    day = (MONDAY + timedelta(days=7)).isoformat()

    refused = await client.put(
        "/api/v1/overrides",
        json={"date": day, "index": 7, "action": "replace", "teacher": "Иванов И.И."},
        headers=_auth(token),
    )

    assert refused.status_code == 422, refused.text
    assert "нечего заменять" in refused.json()["detail"]
    assert await session.scalar(select(LessonOverride)) is None
    assert recording_bot.sent == []

    # The same payload over a lesson the day does have is still a room change,
    # which is the case the schema was widened for in the first place.
    over_a_real_lesson = await client.put(
        "/api/v1/overrides",
        json={"date": day, "index": 1, "action": "replace", "teacher": "Иванов И.И."},
        headers=_auth(token),
    )
    assert over_a_real_lesson.status_code == 200, over_a_real_lesson.text


async def test_a_substitution_outside_the_school_year_is_refused(
    client, session, school_class, recording_bot
):
    """A lesson in June is announced to the class and drawn nowhere.

    `_resolve_day` asks `school_year_bounds` and returns before it reads the
    overrides at all, so the summer rule that stops the template repeating
    stops a substitution too — silently, after the write, the audit line and
    everybody's «🔁 Замена 4 июня». `_check_date`'s own bounds run to 2100 and
    cannot see this, and they must not learn to: homework and events out of
    season are deliberately kept, and only the lessons are not.

    The endpoint asks the resolver's own `school_year_bounds` rather than
    re-reading the rule, so the two cannot drift apart.
    """
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)
    summer = date(MONDAY.year + 1, 6, 4)
    assert summer.month == 6

    refused = await client.put(
        "/api/v1/overrides",
        json={
            "date": summer.isoformat(),
            "index": 1,
            "action": "replace",
            "subject": "Консультация",
        },
        headers=_auth(token),
    )

    assert refused.status_code == 422, refused.text
    assert "вне учебного года" in refused.json()["detail"]
    assert await session.scalar(select(LessonOverride)) is None
    assert recording_bot.sent == []


async def test_a_substitution_on_a_hand_marked_holiday_is_refused(
    client, session, school_class, recording_bot
):
    """The third date shape, and the one a class actually creates.

    `_resolve_day` has two early returns above the override loop, not one:
    out of season, and `kind is DayKind.HOLIDAY`. The first was closed and the
    second was not, and the guard that would have caught it was written — it
    was simply gated behind «and no subject», so a replacement *carrying* a
    subject walked straight past. «Каникулы» marked by hand is a thing every
    class does, where a June substitution is a thing almost nobody does.

    Stored, audited, and «🔁 Замена 14 сентября — Консультация» to every
    subscriber; the bundle for that day answers no lessons. The bot cannot
    reach it — it builds its picker from the day's own lessons and says «В этот
    день уроков нет — заменять нечего».
    """
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)
    day = MONDAY + timedelta(days=7)
    session.add(
        DayOverride(
            class_id=school_class.id, date=day, kind=DayKind.HOLIDAY, note="Каникулы"
        )
    )
    await session.commit()

    refused = await client.put(
        "/api/v1/overrides",
        json={
            "date": day.isoformat(),
            "index": 1,
            "action": "replace",
            "subject": "Консультация",
        },
        headers=_auth(token),
    )

    assert refused.status_code == 422, refused.text
    assert "выходной" in refused.json()["detail"]
    assert await session.scalar(select(LessonOverride)) is None
    assert recording_bot.sent == []


async def test_an_existing_substitution_out_of_season_cannot_be_re_announced(
    client, session, school_class, recording_bot
):
    """Refusing on create only leaves every row written before the fix live.

    The bell check above is create-only for a stated reason — an existing row
    at a number that does not ring must stay editable, which is how a class
    gets out of one. The season and holiday checks are not that shape: a row
    already sitting on a summer date is exactly the one whose update would
    announce «🔁 Замена» about a lesson nobody can see, and every such row
    written before this endpoint learned to refuse is one of those.

    Clearing it still works, which is the way out that matters.
    """
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)
    summer = date(MONDAY.year + 1, 6, 7)
    session.add(
        LessonOverride(
            class_id=school_class.id,
            date=summer,
            index=1,
            action=OverrideAction.REPLACE,
            subject_name="Старое",
        )
    )
    await session.commit()

    refused = await client.put(
        "/api/v1/overrides",
        json={"date": summer.isoformat(), "index": 1, "action": "replace", "subject": "Новое"},
        headers=_auth(token),
    )
    assert refused.status_code == 422, refused.text
    assert "вне учебного года" in refused.json()["detail"]
    assert recording_bot.sent == []

    # The row is still there and is still removable — that is the way out.
    cleared = await client.put(
        "/api/v1/overrides",
        json={"date": summer.isoformat(), "index": 1, "action": "clear"},
        headers=_auth(token),
    )
    assert cleared.status_code == 200, cleared.text
    assert await session.scalar(select(LessonOverride)) is None


async def test_days_set_and_clear(client, session, school_class, recording_bot):
    await _subscriber(session, school_class, 7001, notify_changes=True)
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)
    next_monday = (MONDAY + timedelta(days=7)).isoformat()

    holiday = await client.put(
        "/api/v1/days",
        json={"date": next_monday, "kind": "holiday", "note": "День учителя"},
        headers=_auth(token),
    )
    assert holiday.status_code == 200, holiday.text
    assert holiday.json() == {
        "date": next_monday,
        "kind": "holiday",
        "note": "День учителя",
        "bell_schedule_id": None,
    }
    day = (
        await client.get(
            "/api/v1/bundle", params={"start": next_monday, "days": 1}, headers=_auth(token)
        )
    ).json()["days"][0]
    assert day["kind"] == "holiday" and day["lessons"] == [] and day["note"] == "День учителя"

    # A shortened day on the class's own bells; somebody else's bells are refused.
    other_bells = BellSchedule(class_id=school_class.id, name="Сокращённое")
    session.add(other_bells)
    foreign_class = SchoolClass(name="11Б", join_code=new_join_code())
    session.add(foreign_class)
    await session.flush()
    # With rows in it, because a schedule that rings nothing is now refused —
    # see `test_a_day_cannot_ring_a_bell_schedule_that_has_no_rows`.
    for index in (1, 2, 3):
        session.add(
            BellPeriod(
                schedule_id=other_bells.id,
                index=index,
                starts_at=time(8 + index, 0),
                ends_at=time(8 + index, 30),
            )
        )
    foreign_bells = BellSchedule(class_id=foreign_class.id, name="Чужое")
    session.add(foreign_bells)
    await session.commit()

    refused = await client.put(
        "/api/v1/days",
        json={"date": next_monday, "kind": "shortened", "bell_schedule_id": foreign_bells.id},
        headers=_auth(token),
    )
    assert refused.status_code == 422

    shortened = await client.put(
        "/api/v1/days",
        json={"date": next_monday, "kind": "shortened", "bell_schedule_id": other_bells.id},
        headers=_auth(token),
    )
    assert shortened.json()["kind"] == "shortened"

    # A shortened day with no schedule to ring is refused: the resolver would
    # fall back to the class default, so the day would announce short lessons
    # and draw the normal ones.
    bare = await client.put(
        "/api/v1/days",
        json={"date": next_monday, "kind": "shortened"},
        headers=_auth(token),
    )
    assert bare.status_code == 422
    assert "bell schedule" in bare.json()["detail"]
    assert shortened.json()["bell_schedule_id"] == other_bells.id
    rows = list(
        await session.scalars(select(DayOverride).where(DayOverride.class_id == school_class.id))
    )
    assert len(rows) == 1

    normal = await client.put(
        "/api/v1/days", json={"date": next_monday, "kind": "normal"}, headers=_auth(token)
    )
    assert normal.json() == {
        "date": next_monday,
        "kind": "normal",
        "note": None,
        "bell_schedule_id": None,
    }
    assert (
        await session.scalar(select(DayOverride).where(DayOverride.class_id == school_class.id))
    ) is None

    assert [row.action for row in await _audit(session, school_class)] == [
        "day.set",
        "day.set",
        "day.clear",
    ]
    assert len(recording_bot.sent) == 3


async def test_a_revoked_role_stops_the_phone_at_once(client, session, school_class):
    """No role is cached on the device: the bot's revoke is the API's revoke."""
    token = await _linked_token(client, session, school_class, EDITOR_ID, Role.EDITOR)
    assert (
        await client.put("/api/v1/homework", json=WRITES[0][2], headers=_auth(token))
    ).status_code == 200

    membership = await session.scalar(select(BotUser).where(BotUser.telegram_id == EDITOR_ID))
    membership.role = Role.VIEWER
    await session.commit()

    assert (
        await client.put("/api/v1/homework", json=WRITES[0][2], headers=_auth(token))
    ).status_code == 403
    assert (await client.get("/api/v1/me", headers=_auth(token))).json()["can_edit"] is False


# --------------------------------------------------------------------------
# Cron
# --------------------------------------------------------------------------

CRON_SECRET = "tick-secret"


def _configure_cron(monkeypatch, *, secret: str = CRON_SECRET, bot_token: str = "123456:TEST"):
    settings = Settings(
        bot_token=bot_token,
        cron_secret=secret,
        database_url=get_settings().database_url,
        run_bot=False,
    )
    monkeypatch.setattr(cron, "get_settings", lambda: settings)


@pytest.fixture
def tick_bot(monkeypatch):
    bot = FakeBot()
    monkeypatch.setattr(cron, "_build_bot", lambda: bot)
    return bot


async def test_cron_tick_does_not_exist_until_configured(client, school_class):
    response = await client.get("/api/v1/cron/tick", headers={"X-Cron-Secret": "anything"})
    assert response.status_code == 404


async def test_cron_tick_refuses_a_bad_secret(client, school_class, monkeypatch, tick_bot):
    _configure_cron(monkeypatch)
    assert (await client.get("/api/v1/cron/tick")).status_code == 403
    assert (
        await client.get("/api/v1/cron/tick", headers={"X-Cron-Secret": "wrong"})
    ).status_code == 403
    assert (
        await client.post("/api/v1/cron/tick", headers={"X-Cron-Secret": CRON_SECRET + "x"})
    ).status_code == 403
    assert tick_bot.sent == []


async def test_cron_tick_needs_a_bot_token(client, school_class, monkeypatch, tick_bot):
    _configure_cron(monkeypatch, bot_token="")
    response = await client.get("/api/v1/cron/tick", headers={"X-Cron-Secret": CRON_SECRET})
    assert response.status_code == 503


async def test_cron_tick_delivers_a_morning_digest_once(
    client, session, school_class, monkeypatch, tick_bot
):
    _configure_cron(monkeypatch)
    await _subscriber(session, school_class, 42, morning_at=time(7, 30))

    class _Clock(datetime):
        @classmethod
        def now(cls, tz=None):
            # 07:45 Monday in Moscow, as the tick would see it in UTC.
            return datetime(2026, 9, 7, 4, 45, tzinfo=tz)

    monkeypatch.setattr(cron, "datetime", _Clock)

    first = await client.post("/api/v1/cron/tick", headers={"X-Cron-Secret": CRON_SECRET})
    assert first.status_code == 200, first.text
    assert first.json() == {
        "morning": 1,
        "evening": 0,
        "tasks": 0,
        "failed": 0,
        "fsm_purged": 0,
        "join_attempts_purged": 0,
        "diary_sessions_purged": 0,
        "device_tokens_purged": 0,
        "diary_links_purged": 0,
        "device_invites_purged": 0,
    }
    assert len(tick_bot.sent) == 1
    recipient, text = tick_bot.sent[0]
    assert recipient == 42
    assert text.startswith("☀️ Доброе утро!") and "Алгебра" in text
    assert tick_bot.closed

    # Five minutes later: nothing more to send.
    second = await client.get("/api/v1/cron/tick", headers={"X-Cron-Secret": CRON_SECRET})
    assert second.status_code == 200
    assert second.json()["morning"] == 0
    assert len(tick_bot.sent) == 1

    settings = await session.scalar(
        select(ReminderSettings).where(ReminderSettings.telegram_id == 42)
    )
    await session.refresh(settings)
    assert settings.last_morning_sent == MONDAY


async def test_cron_tick_sweeps_stale_rows(client, session, school_class, monkeypatch, tick_bot):
    _configure_cron(monkeypatch)
    old = datetime.utcnow() - timedelta(days=3)
    session.add(FsmRecord(key="1:1:1:0::default", state="x", data="{}", updated_at=old))
    now = datetime.utcnow()
    session.add(FsmRecord(key="1:1:2:0::default", state="x", data="{}", updated_at=now))
    session.add(JoinAttempt(client_key="a", created_at=old))
    session.add(JoinAttempt(client_key="b", created_at=datetime.utcnow()))
    await session.commit()

    response = await client.get("/api/v1/cron/tick", headers={"X-Cron-Secret": CRON_SECRET})
    assert response.status_code == 200
    assert response.json()["fsm_purged"] == 1
    assert response.json()["join_attempts_purged"] == 1


async def test_cron_tick_sweeps_dead_and_forgotten_diary_sessions(
    client, session, school_class, monkeypatch, tick_bot
):
    """A diary session holds somebody else's live bearer token.

    Nothing removed one before this sweep: signing out drops a row, but a phone
    that is reinstalled, wiped or simply never opened again leaves its session
    behind for good, and the row keeps the upstream credential in it. The two
    cutoffs are deliberately different - a session the upstream has already
    refused is worthless the moment it is marked, and is kept only long enough
    to answer the app still holding our token.
    """
    _configure_cron(monkeypatch)
    now = datetime.utcnow()
    rows = {
        # Refused by the upstream two days ago: gone.
        "dead": DiarySession(
            token_hash="a" * 64, upstream_token="x", login="a@e", last_used_at=now,
            expired_at=now - timedelta(days=2),
        ),
        # Refused an hour ago: kept, so the app is told to sign in again rather
        # than handed a 401 it cannot explain.
        "just-expired": DiarySession(
            token_hash="b" * 64, upstream_token="x", login="b@e", last_used_at=now,
            expired_at=now - timedelta(hours=1),
        ),
        # Alive, but untouched for two months.
        "forgotten": DiarySession(
            token_hash="c" * 64, upstream_token="x", login="c@e",
            last_used_at=now - timedelta(days=60),
        ),
        # Created and never used - judged by created_at, which every row has.
        "never-used": DiarySession(
            token_hash="d" * 64, upstream_token="x", login="d@e",
            created_at=now - timedelta(days=60),
        ),
        "in-use": DiarySession(
            token_hash="e" * 64, upstream_token="x", login="e@e", last_used_at=now
        ),
    }
    for row in rows.values():
        session.add(row)
    await session.commit()

    response = await client.get("/api/v1/cron/tick", headers={"X-Cron-Secret": CRON_SECRET})
    assert response.status_code == 200
    assert response.json()["diary_sessions_purged"] == 3

    left = await session.scalars(select(DiarySession.login))
    assert sorted(left) == ["b@e", "e@e"]


async def test_cron_tick_sweeps_the_phones_that_stopped_asking(
    client, session, school_class, monkeypatch, tick_bot
):
    """A join mints a row and nothing ever removed one.

    A pupil who reinstalls, clears the app's data or re-enters the code leaves
    the previous token behind — live, able to read the class for as long as the
    class exists, and on the admin's list forever. The cut-off is deliberately
    far past the summer holidays: a phone silent since the end of May must still work in
    September.
    """
    _configure_cron(monkeypatch)
    now = datetime.utcnow()
    session.add_all([
        DeviceToken(
            token_hash="f" * 64, class_id=school_class.id, device_name="старый",
            last_seen_at=now - timedelta(days=200),
        ),
        DeviceToken(
            token_hash="g" * 64, class_id=school_class.id, device_name="через каникулы",
            last_seen_at=now - timedelta(days=100),
        ),
        DeviceToken(
            token_hash="h" * 64, class_id=school_class.id, device_name="никогда не заходил",
            created_at=now - timedelta(days=200),
        ),
        DeviceToken(
            token_hash="i" * 64, class_id=school_class.id, device_name="в работе",
            last_seen_at=now,
        ),
    ])
    await session.commit()

    response = await client.get("/api/v1/cron/tick", headers={"X-Cron-Secret": CRON_SECRET})
    assert response.status_code == 200
    assert response.json()["device_tokens_purged"] == 2

    left = await session.scalars(
        select(DeviceToken.device_name).where(DeviceToken.class_id == school_class.id)
    )
    assert sorted(name for name in left if name) == ["в работе", "через каникулы"]


def test_current_device_is_what_the_class_dependency_builds_on():
    """One token lookup per request, however many dependencies ask."""
    from app.api import deps

    assert hash_token("x") != hash_token("y")
    assert deps.current_class.__code__.co_varnames[:1] == ("device",)
