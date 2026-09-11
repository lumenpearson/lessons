"""Regression tests for the audit fixes.

Each test here pins a defect that was found by reading the code rather than by
a failing test, which means without these the next refactor silently undoes it.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta
from types import SimpleNamespace

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import select

from app.api.public import MAX_BUNDLE_START, MIN_BUNDLE_START, join_limiter
from app.main import app
from app.models import BellPeriod, BellSchedule, DeviceToken, Role, SchoolClass, TimetableEntry
from app.security import RateLimiter


@pytest.fixture(autouse=True)
def _clean_limiter():
    join_limiter.reset()
    yield
    join_limiter.reset()


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


# --------------------------------------------------------------------------
# SQLite ignores foreign keys unless told otherwise, making every
# ondelete="CASCADE" in the model a no-op and leaving unreachable orphans.
# --------------------------------------------------------------------------


async def test_deleting_a_class_takes_its_children_with_it(session, school_class):
    class_id = school_class.id
    session.add(
        DeviceToken(token_hash="deadbeef", class_id=class_id, device_name="phone")
    )
    await session.commit()

    assert (await session.scalars(select(TimetableEntry).where(
        TimetableEntry.class_id == class_id))).all()

    await session.delete(school_class)
    await session.commit()

    for model in (TimetableEntry, DeviceToken, BellSchedule):
        rows = (await session.scalars(select(model).where(model.class_id == class_id))).all()
        assert rows == [], f"{model.__name__} rows outlived their class"


async def test_deleting_a_bell_schedule_takes_its_periods(session, school_class):
    schedule_id = school_class.bell_schedule_id
    schedule = await session.get(BellSchedule, schedule_id)

    school_class.bell_schedule_id = None
    await session.commit()
    await session.delete(schedule)
    await session.commit()

    periods = (await session.scalars(
        select(BellPeriod).where(BellPeriod.schedule_id == schedule_id))).all()
    assert periods == []


# --------------------------------------------------------------------------
# timezone_name is what the client derives "now" from, so it must never be a
# name the client cannot resolve while the server quietly falls back.
# --------------------------------------------------------------------------


async def test_timezone_name_never_returns_an_unresolvable_zone(session, school_class):
    school_class.timezone = "Mars/Olympus"
    await session.commit()

    assert school_class.timezone_name == "Europe/Moscow"
    assert school_class.tz.key == school_class.timezone_name


async def test_timezone_name_passes_a_real_zone_through(session, school_class):
    school_class.timezone = "Asia/Vladivostok"
    await session.commit()
    assert school_class.timezone_name == "Asia/Vladivostok"


# --------------------------------------------------------------------------
# Rate limiting on the join endpoint.
# --------------------------------------------------------------------------


def test_rate_limiter_allows_up_to_the_limit_then_blocks():
    limiter = RateLimiter(limit=3, window=100.0)

    for _ in range(3):
        assert limiter.blocked_for("a", now=0.0) is None
        limiter.record_failure("a", now=0.0)

    blocked = limiter.blocked_for("a", now=0.0)
    assert blocked is not None and blocked > 0


def test_rate_limiter_forgets_after_the_window():
    limiter = RateLimiter(limit=2, window=10.0)
    limiter.record_failure("a", now=0.0)
    limiter.record_failure("a", now=0.0)

    assert limiter.blocked_for("a", now=5.0) is not None
    assert limiter.blocked_for("a", now=11.0) is None


def test_rate_limiter_keys_are_independent():
    limiter = RateLimiter(limit=1, window=100.0)
    limiter.record_failure("a", now=0.0)

    assert limiter.blocked_for("a", now=0.0) is not None
    assert limiter.blocked_for("b", now=0.0) is None


def test_rate_limiter_table_does_not_grow_without_bound():
    limiter = RateLimiter(limit=5, window=1.0, max_keys=50)
    for index in range(500):
        limiter.record_failure(f"host-{index}", now=float(index))
    assert len(limiter._hits) <= 50


async def test_join_blocks_after_repeated_wrong_codes(client, school_class):
    for _ in range(join_limiter.limit):
        assert (await client.post("/api/v1/join", json={"code": "NOPE99"})).status_code == 404

    blocked = await client.post("/api/v1/join", json={"code": "NOPE99"})
    assert blocked.status_code == 429
    assert int(blocked.headers["Retry-After"]) >= 1


async def test_a_correct_code_is_never_counted_against_the_caller(client, school_class):
    """A whole class joining from one school NAT must not lock itself out."""
    for _ in range(join_limiter.limit * 2):
        assert (await client.post("/api/v1/join", json={"code": "TEST42"})).status_code == 200


async def test_a_blocked_caller_cannot_use_a_correct_code_either(client, school_class):
    for _ in range(join_limiter.limit):
        await client.post("/api/v1/join", json={"code": "NOPE99"})

    assert (await client.post("/api/v1/join", json={"code": "TEST42"})).status_code == 429


# --------------------------------------------------------------------------
# The bundle window: arbitrary client dates must not reach date arithmetic
# that can overflow, which the widget would see as a 500.
# --------------------------------------------------------------------------


async def _token(client, code: str = "TEST42") -> str:
    response = await client.post("/api/v1/join", json={"code": code})
    return response.json()["token"]


@pytest.mark.parametrize("start", ["9999-12-31", "0001-01-01", "1899-01-01", "2200-06-01"])
async def test_absurd_start_dates_are_rejected_cleanly(client, school_class, start):
    token = await _token(client)
    response = await client.get(
        "/api/v1/bundle",
        params={"start": start, "days": 14},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 422, response.text


@pytest.mark.parametrize("start", [MIN_BUNDLE_START, MAX_BUNDLE_START, date(2026, 9, 7)])
async def test_plausible_start_dates_are_accepted(client, school_class, start):
    token = await _token(client)
    response = await client.get(
        "/api/v1/bundle",
        params={"start": start.isoformat(), "days": 1},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200, response.text


# --------------------------------------------------------------------------
# Control characters in client input reach a text column; Postgres rejects NUL.
# --------------------------------------------------------------------------


async def test_a_device_name_with_control_characters_is_cleaned_not_rejected(
    client, session, school_class
):
    response = await client.post(
        "/api/v1/join",
        json={"code": "TEST42", "device_name": "Pixel\x00\x07 8\x1b"},
    )
    assert response.status_code == 200

    device = await session.scalar(select(DeviceToken))
    assert "\x00" not in device.device_name
    assert device.device_name == "Pixel 8"


async def test_a_device_name_of_only_control_characters_becomes_null(
    client, session, school_class
):
    assert (
        await client.post("/api/v1/join", json={"code": "TEST42", "device_name": "\x00\x01"})
    ).status_code == 200
    device = await session.scalar(select(DeviceToken))
    assert device.device_name is None


# --------------------------------------------------------------------------
# last_seen_at was written on every read, taking a write lock on the widget's
# polling interval.
# --------------------------------------------------------------------------


async def test_last_seen_is_not_rewritten_on_every_request(client, session, school_class):
    token = await _token(client)
    headers = {"Authorization": f"Bearer {token}"}

    await client.get("/api/v1/bundle", params={"days": 1}, headers=headers)
    device = await session.scalar(select(DeviceToken))
    await session.refresh(device)
    first = device.last_seen_at
    assert first is not None

    await client.get("/api/v1/bundle", params={"days": 1}, headers=headers)
    await session.refresh(device)
    assert device.last_seen_at == first, "a second read must not take a write lock"


async def test_last_seen_is_refreshed_once_the_interval_has_passed(
    client, session, school_class
):
    token = await _token(client)
    headers = {"Authorization": f"Bearer {token}"}
    await client.get("/api/v1/bundle", params={"days": 1}, headers=headers)

    device = await session.scalar(select(DeviceToken))
    stale = datetime.utcnow() - timedelta(hours=2)
    device.last_seen_at = stale
    await session.commit()

    await client.get("/api/v1/bundle", params={"days": 1}, headers=headers)
    await session.refresh(device)
    assert device.last_seen_at > stale


# --------------------------------------------------------------------------
# Creating a class grants OWNER of it. FSM state is per-user and effectively
# attacker-controlled, so reaching that step must not be the only check.
# --------------------------------------------------------------------------


class _Editable:
    def __init__(self) -> None:
        self.texts: list[str] = []

    async def edit_text(self, text: str, **_) -> None:
        self.texts.append(text)


class _Callback:
    def __init__(self, user_id: int) -> None:
        self.message = _Editable()
        self.answers: list[tuple[str | None, bool]] = []
        self._user_id = user_id

    @property
    def from_user(self):
        return SimpleNamespace(id=self._user_id, username="x", full_name="X")

    async def answer(self, text=None, show_alert: bool = False, **_) -> None:
        self.answers.append((text, show_alert))

    @property
    def alerted(self) -> bool:
        return any(alert for _, alert in self.answers)


class _State:
    def __init__(self, data: dict) -> None:
        self.data = data
        self.cleared = False

    async def get_data(self) -> dict:
        return dict(self.data)

    async def update_data(self, **kw) -> dict:
        self.data.update(kw)
        return dict(self.data)

    async def set_state(self, _state) -> None:
        pass

    async def clear(self) -> None:
        self.cleared = True
        self.data.clear()


async def test_a_non_owner_cannot_create_a_class_by_reaching_the_final_step(session):
    from app.bot.handlers.start import create_class_timezone

    callback = _Callback(user_id=2000)  # not in OWNER_IDS
    state = _State({"name": "Взлом", "school": None})

    await create_class_timezone(
        callback, SimpleNamespace(zone="Europe/Moscow"), state, session
    )

    assert callback.alerted
    assert state.cleared
    assert (await session.scalars(select(SchoolClass))).all() == []


async def test_the_owner_can_still_create_a_class(session):
    from app.bot.handlers.start import create_class_timezone

    callback = _Callback(user_id=1000)  # matches OWNER_IDS in conftest
    state = _State({"name": "9Б", "school": "Школа № 2"})

    await create_class_timezone(
        callback, SimpleNamespace(zone="Asia/Omsk"), state, session
    )

    created = await session.scalar(select(SchoolClass).where(SchoolClass.name == "9Б"))
    assert created is not None
    assert created.timezone == "Asia/Omsk"
    assert not callback.alerted


async def test_a_half_finished_create_flow_does_not_raise(session):
    """The name step never ran, so the data dict has no "name" key."""
    from app.bot.handlers.start import create_class_timezone

    callback = _Callback(user_id=1000)
    state = _State({})

    await create_class_timezone(
        callback, SimpleNamespace(zone="Europe/Moscow"), state, session
    )

    assert callback.alerted
    assert (await session.scalars(select(SchoolClass))).all() == []


# --------------------------------------------------------------------------
# Every message uses ParseMode.HTML, so a class named "<b>" must not be able
# to break or forge the markup of a message the bot sends.
# --------------------------------------------------------------------------


async def test_a_class_name_containing_markup_is_escaped(session):
    from app.bot.handlers.start import class_settings

    hostile = SchoolClass(
        name="<b>9А</b><script>",
        school="<i>Школа</i>",
        join_code="HOSTIL",
        timezone="Europe/Moscow",
    )
    session.add(hostile)
    await session.commit()

    callback = _Callback(user_id=1000)
    await class_settings(callback, hostile, Role.OWNER)

    rendered = callback.message.texts[-1]
    assert "&lt;b&gt;9А&lt;/b&gt;" in rendered
    assert "<script>" not in rendered
    assert "<i>Школа</i>" not in rendered


def test_telegram_id_columns_are_wide_enough_for_real_ids():
    """Telegram ids exceed 2^31, and Integer maps to int4 on Postgres.

    SQLite has no integer width, so nothing at runtime in this suite can catch a
    column that is too narrow — the failure only appears in production, as an
    asyncpg NumericValueOutOfRange the webhook swallows. Assert the declared
    type instead.
    """
    from sqlalchemy import BigInteger

    from app.models import BotUser, Homework, PhoneInvite

    columns = [
        BotUser.__table__.c.telegram_id,
        BotUser.__table__.c.granted_by,
        Homework.__table__.c.created_by,
        PhoneInvite.__table__.c.invited_by,
        PhoneInvite.__table__.c.used_by,
    ]
    for column in columns:
        assert isinstance(column.type, BigInteger), column
