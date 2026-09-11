"""End-to-end coverage of the endpoints the Android client depends on."""

from __future__ import annotations

from datetime import date, time

import httpx
import pytest
from httpx import ASGITransport

from app.main import app
from app.models import DayEvent, EventKind, Homework, LessonOverride, OverrideAction

MONDAY = date(2026, 9, 7)


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


async def _token(client, code: str = "TEST42") -> str:
    response = await client.post("/api/v1/join", json={"code": code, "device_name": "pytest"})
    assert response.status_code == 200, response.text
    return response.json()["token"]


async def test_health_is_unauthenticated(client):
    response = await client.get("/api/v1/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


async def test_join_returns_a_token_and_class_identity(client, school_class):
    response = await client.post("/api/v1/join", json={"code": "test42", "device_name": "Pixel"})
    assert response.status_code == 200

    body = response.json()
    assert body["class_id"] == school_class.id
    assert body["class_name"] == "9А"
    assert body["school"] == "Школа № 1"
    assert len(body["token"]) > 20


async def test_join_rejects_an_unknown_code(client, school_class):
    response = await client.post("/api/v1/join", json={"code": "NOPE99"})
    assert response.status_code == 404


@pytest.mark.parametrize("code", ["    ", " \t\n\r ", "\x00\x01\x02\x03"])
async def test_join_rejects_a_code_that_sanitises_away_to_nothing(client, school_class, code):
    """A code long enough to pass min_length but empty once cleaned is a 422.

    It used to be a 500: the validator returned None for it, pydantic does not
    re-check an after-validator's return against the annotation, and the handler
    then called .strip() on None. An unauthenticated 500 generator, reachable by
    tapping send on a field holding a space.
    """
    response = await client.post("/api/v1/join", json={"code": code})
    assert response.status_code == 422


async def test_join_keeps_a_code_that_only_needs_trimming(client, school_class):
    response = await client.post("/api/v1/join", json={"code": "  test42  "})
    assert response.status_code == 200


async def test_bundle_requires_a_bearer_token(client, school_class):
    assert (await client.get("/api/v1/bundle")).status_code == 401
    assert (
        await client.get("/api/v1/bundle", headers={"Authorization": "Bearer nonsense"})
    ).status_code == 401


async def test_bundle_returns_the_resolved_window(client, school_class):
    token = await _token(client)
    response = await client.get(
        "/api/v1/bundle",
        params={"start": MONDAY.isoformat(), "days": 7},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200

    body = response.json()
    assert body["api_version"] == 1
    assert body["school_class"]["name"] == "9А"
    assert body["school_class"]["timezone"] == "Europe/Moscow"
    assert len(body["days"]) == 7

    monday = body["days"][0]
    assert monday["date"] == MONDAY.isoformat()
    assert monday["weekday"] == 1
    assert [lesson["subject"] for lesson in monday["lessons"]] == [
        "Алгебра",
        "Физика",
        "История",
    ]
    assert monday["lessons"][0]["starts_at"] == "08:30:00"


async def test_bundle_carries_overrides_events_and_homework(client, session, school_class):
    session.add(
        LessonOverride(
            class_id=school_class.id,
            date=MONDAY,
            index=2,
            action=OverrideAction.REPLACE,
            subject_name="Химия",
            room="118",
        )
    )
    session.add(
        DayEvent(
            class_id=school_class.id,
            date=MONDAY,
            starts_at=time(12, 30),
            ends_at=time(13, 0),
            title="Обед",
            kind=EventKind.CANTEEN,
        )
    )
    session.add(
        Homework(
            class_id=school_class.id, due_date=MONDAY, subject_name="Алгебра", text="№ 12–15"
        )
    )
    await session.commit()

    token = await _token(client)
    response = await client.get(
        "/api/v1/bundle",
        params={"start": MONDAY.isoformat(), "days": 1},
        headers={"Authorization": f"Bearer {token}"},
    )
    day = response.json()["days"][0]

    assert day["lessons"][1]["subject"] == "Химия"
    assert day["lessons"][1]["is_replaced"] is True
    assert day["events"][0]["kind"] == "canteen"
    assert day["homework"][0]["text"] == "№ 12–15"


async def test_bundle_resolves_the_next_school_day_beyond_the_window(client, school_class):
    """Asking on a Friday for one day still answers "what is homework for Monday"."""
    token = await _token(client)
    friday = date(2026, 9, 11)
    response = await client.get(
        "/api/v1/bundle",
        params={"start": friday.isoformat(), "days": 1},
        headers={"Authorization": f"Bearer {token}"},
    )
    body = response.json()

    assert body["days"][0]["lessons"] == []
    assert body["next_school_day"] is not None
    assert body["next_school_day"]["date"] == "2026-09-14"


async def test_bundle_rejects_an_absurd_window(client, school_class):
    token = await _token(client)
    response = await client.get(
        "/api/v1/bundle",
        params={"days": 400},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 422


async def test_a_revoked_token_stops_working(client, session, school_class):
    from sqlalchemy import select

    from app.models import DeviceToken

    token = await _token(client)
    device = await session.scalar(select(DeviceToken))
    device.revoked = True
    await session.commit()

    response = await client.get("/api/v1/bundle", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 401


async def test_join_reports_the_class_timezone(client, session, school_class):
    """The client needs the zone to compute "now" for a school it is not near."""
    school_class.timezone = "Asia/Vladivostok"
    school_class.city = "Владивосток"
    await session.commit()

    response = await client.post("/api/v1/join", json={"code": "TEST42"})
    assert response.json()["timezone"] == "Asia/Vladivostok"


async def test_bundle_uses_the_class_timezone_not_the_server_one(client, session, school_class):
    school_class.timezone = "Asia/Kamchatka"
    school_class.city = "Петропавловск-Камчатский"
    await session.commit()

    token = await _token(client)
    body = (
        await client.get("/api/v1/bundle", headers={"Authorization": f"Bearer {token}"})
    ).json()

    assert body["school_class"]["timezone"] == "Asia/Kamchatka"
    assert body["school_class"]["city"] == "Петропавловск-Камчатский"
    # generated_at must carry the class's offset, not the server's +03:00.
    assert body["generated_at"].endswith("+12:00")


async def test_a_class_without_a_timezone_falls_back_to_the_server_default(client, school_class):
    token = await _token(client)
    body = (
        await client.get("/api/v1/bundle", headers={"Authorization": f"Bearer {token}"})
    ).json()
    assert body["school_class"]["timezone"] == "Europe/Moscow"
