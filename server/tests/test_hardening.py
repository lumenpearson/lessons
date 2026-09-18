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
from starlette.datastructures import Headers

from app.api.public import (
    MAX_BUNDLE_START,
    MIN_BUNDLE_START,
    _client_bucket,
    join_limiter,
)
from app.config import get_settings
from app.main import app
from app.models import (
    BellPeriod,
    BellSchedule,
    DeviceInvite,
    DeviceToken,
    Role,
    SchoolClass,
    TimetableEntry,
)
from app.schemas import _clean_optional_text
from app.security import JoinThrottle, client_bucket
from app.services import device_invites


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
    session.add(DeviceToken(token_hash="deadbeef", class_id=class_id, device_name="phone"))
    await session.commit()

    assert (
        await session.scalars(select(TimetableEntry).where(TimetableEntry.class_id == class_id))
    ).all()

    await session.delete(school_class)
    await session.commit()

    for model in (TimetableEntry, DeviceToken, BellSchedule):
        rows = (await session.scalars(select(model).where(model.class_id == class_id))).all()
        assert rows == [], f"{model.__name__} rows outlived their class"


async def test_deleting_a_class_takes_its_unspent_connect_codes_with_it(session, school_class):
    """A personal code outliving its class is a code that opens nothing.

    Worth its own test rather than a name added to the list above, because the
    row can be *live* at the moment the class goes: `find_live` would hand it
    to `/join`, which would then look the class up and find nothing. Both
    halves of the guard are at issue — the `ondelete="CASCADE"` on the model
    and the `PRAGMA foreign_keys=ON` that makes SQLite honour it at all.
    """
    class_id = school_class.id
    await device_invites.mint(session, telegram_id=770, class_id=class_id)
    assert (await session.scalars(select(DeviceInvite))).all()

    await session.delete(school_class)
    await session.commit()

    left = (await session.scalars(select(DeviceInvite))).all()
    assert left == [], "a connect code outlived the class it was minted for"


async def test_deleting_a_bell_schedule_takes_its_periods(session, school_class):
    schedule_id = school_class.bell_schedule_id
    schedule = await session.get(BellSchedule, schedule_id)

    school_class.bell_schedule_id = None
    await session.commit()
    await session.delete(schedule)
    await session.commit()

    periods = (
        await session.scalars(select(BellPeriod).where(BellPeriod.schedule_id == schedule_id))
    ).all()
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


async def test_the_limit_survives_a_process_that_remembers_nothing(session):
    """The counter is in the database, so a fresh process still sees it.

    This is the whole point of the change. The previous limiter kept a dict per
    process and called that correct on the premise of "a single uvicorn worker";
    on the Vercel deployment each concurrent invocation is its own process, so
    the dict was empty nearly every time it was read and the ceiling never
    existed. A second JoinThrottle instance here stands in for that second
    process.
    """
    throttle = JoinThrottle(limit=3, window=100.0)
    key = client_bucket("198.51.100.7")

    for _ in range(3):
        assert await throttle.blocked_for(session, key) is None
        await throttle.record_failure(session, key)

    # A different object, as a cold start would be, reading the same table.
    other_process = JoinThrottle(limit=3, window=100.0)
    blocked = await other_process.blocked_for(session, key)
    assert blocked is not None and blocked > 0


async def test_the_limit_forgets_once_the_window_has_passed(session):
    from datetime import UTC, datetime, timedelta

    from app.models import JoinAttempt

    throttle = JoinThrottle(limit=2, window=10.0)
    key = client_bucket("198.51.100.7")
    await throttle.record_failure(session, key)
    await throttle.record_failure(session, key)
    assert await throttle.blocked_for(session, key) is not None

    # Age the rows rather than sleep: the window is wall-clock, not a counter.
    stale = datetime.now(UTC).replace(tzinfo=None) - timedelta(seconds=30)
    for attempt in (await session.scalars(select(JoinAttempt))).all():
        attempt.created_at = stale
    await session.commit()

    assert await throttle.blocked_for(session, key) is None


async def test_two_clients_are_counted_separately(session):
    throttle = JoinThrottle(limit=1, window=100.0)
    one = client_bucket("198.51.100.7")
    two = client_bucket("203.0.113.9")

    await throttle.record_failure(session, one)

    assert await throttle.blocked_for(session, one) is not None
    assert await throttle.blocked_for(session, two) is None


async def test_expired_attempts_are_cleared_out_as_new_ones_arrive(session):
    """The table holds roughly one window, with no scheduler to prune it."""
    from datetime import UTC, datetime, timedelta

    from app.models import JoinAttempt

    throttle = JoinThrottle(limit=5, window=1.0)
    await throttle.record_failure(session, client_bucket("198.51.100.7"))

    stale = datetime.now(UTC).replace(tzinfo=None) - timedelta(seconds=60)
    for attempt in (await session.scalars(select(JoinAttempt))).all():
        attempt.created_at = stale
    await session.commit()

    await throttle.record_failure(session, client_bucket("203.0.113.9"))

    remaining = (await session.scalars(select(JoinAttempt))).all()
    assert len(remaining) == 1


async def test_a_client_cannot_pick_its_own_bucket_with_a_header(client, school_class):
    """X-Forwarded-For is client-controlled, so an unconfigured app ignores it.

    Reading its leftmost entry is the usual advice and it is exactly wrong here:
    an attacker would set it themselves and land in a fresh bucket on every
    request, which is the limit not existing again by another route. With no
    declared proxies in front, every one of these lands in the same bucket.
    """
    spoofed = {"X-Forwarded-For": "10.0.0.1"}
    for index in range(join_limiter.limit):
        spoofed["X-Forwarded-For"] = f"10.0.0.{index}"
        assert (
            await client.post("/api/v1/join", json={"code": "NOPE99"}, headers=spoofed)
        ).status_code == 404

    spoofed["X-Forwarded-For"] = "10.0.0.250"
    blocked = await client.post("/api/v1/join", json={"code": "NOPE99"}, headers=spoofed)
    assert blocked.status_code == 429


def _request(headers: dict[str, str], host: str = "127.0.0.1"):
    """Enough of a Request for _client_bucket, which only reads these two."""
    return SimpleNamespace(headers=Headers(headers), client=SimpleNamespace(host=host))


def test_one_declared_proxy_means_the_rightmost_entry(monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, "trusted_proxy_hops", 1)

    # The caller prepended their own entry; the proxy appended the real one.
    spoofed = _request({"X-Forwarded-For": "10.0.0.1, 198.51.100.7"})
    honest = _request({"X-Forwarded-For": "198.51.100.7"})
    assert _client_bucket(spoofed) == _client_bucket(honest) == client_bucket("198.51.100.7")


def test_a_short_forwarded_header_falls_back_to_the_socket(monkeypatch):
    """Two proxies cannot have produced a one-entry header, so it is not one."""
    settings = get_settings()
    monkeypatch.setattr(settings, "trusted_proxy_hops", 2)

    assert _client_bucket(_request({"X-Forwarded-For": "10.0.0.1"})) == client_bucket("127.0.0.1")


def _repeated_request(lines: list[bytes], name: bytes = b"x-forwarded-for"):
    """A request carrying ``name`` on several field lines, as a proxy that adds
    its own line rather than extending the caller's leaves it."""
    return SimpleNamespace(
        headers=Headers(raw=[(name, line) for line in lines]),
        client=SimpleNamespace(host="127.0.0.1"),
    )


def test_a_repeated_forwarded_header_is_the_one_list_it_means(monkeypatch):
    """RFC 9110 §5.3: several lines of a comma-separated field are one list.

    Read a line at a time, the first line is the caller's own - so a caller
    behind a proxy that appends its own line picked its own bucket on every
    request, which is the limit not existing by the route this whole function
    exists to close.
    """
    settings = get_settings()
    monkeypatch.setattr(settings, "trusted_proxy_hops", 1)

    spoofed = _repeated_request([b"10.0.0.1", b"198.51.100.7"])
    assert _client_bucket(spoofed) == client_bucket("198.51.100.7")


def test_a_repeated_header_still_has_to_be_long_enough_for_the_proxies(monkeypatch):
    """Two lines, two proxies: the entry two from the right is the caller's own
    first line, not a third address nobody sent."""
    settings = get_settings()
    monkeypatch.setattr(settings, "trusted_proxy_hops", 3)

    short = _repeated_request([b"10.0.0.1", b"198.51.100.7"])
    assert _client_bucket(short) == client_bucket("127.0.0.1")


def test_on_vercel_the_platform_header_wins_over_the_client_one(monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, "vercel", "1")

    bucket = _client_bucket(
        _request({"X-Forwarded-For": "10.0.0.1", "X-Vercel-Forwarded-For": "198.51.100.7"})
    )
    assert bucket == client_bucket("198.51.100.7")


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


@pytest.mark.parametrize("window", [{"from": "9999-12-31"}, {"from": "9999-12-20"}])
async def test_an_absurd_homework_window_is_rejected_cleanly(client, school_class, window):
    """`/homework` derives its default end from `from` before anything checks
    `from` at all, and `start + 21 days` within three weeks of `date.max`
    raises OverflowError rather than returning a date. `/bundle` has answered
    422 to the same date since the first audit; this one answered 500, to a
    query string any device token can type.
    """
    token = await _token(client)
    response = await client.get(
        "/api/v1/homework",
        params=window,
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 422, response.text


async def test_a_plausible_homework_window_still_defaults_its_end(client, school_class):
    """The bound must not have narrowed the window the app actually asks for."""
    token = await _token(client)
    response = await client.get(
        "/api/v1/homework",
        params={"from": "2026-09-07"},
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


async def test_a_device_name_of_only_control_characters_becomes_null(client, session, school_class):
    assert (
        await client.post("/api/v1/join", json={"code": "TEST42", "device_name": "\x00\x01"})
    ).status_code == 200
    device = await session.scalar(select(DeviceToken))
    assert device.device_name is None


# --------------------------------------------------------------------------
# The bot and the API have to spell a name the same way, or the dictionary that
# makes a subject unique stops making it unique.
# --------------------------------------------------------------------------


def test_a_name_is_single_spaced_the_way_the_bot_single_spaces_it():
    """``" ".join(text.split())`` is what every bot handler does to a typed name.

    The API used to trim the ends and leave the middle alone, so «Алгебра  и
    начала» went into the database with the two spaces it was sent with. Nothing
    shows the difference: it is one subject to the eye and two to every
    comparison - the uniqueness check, the rename cascade that matches lessons
    and homework by name, and the widget.
    """
    assert _clean_optional_text("Алгебра  и   начала") == "Алгебра и начала"
    assert _clean_optional_text("  Физика ") == "Физика"
    # A tab is neither a space nor printable, so stripping control characters
    # first used to glue the words together rather than break them.
    assert _clean_optional_text("Основы\tбезопасности") == "Основы безопасности"
    assert _clean_optional_text("первая\nвторая") == "первая вторая"
    assert _clean_optional_text("   ") is None


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


async def test_last_seen_is_refreshed_once_the_interval_has_passed(client, session, school_class):
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
    state = _State({"grade": 9, "letter": "Взлом", "school": None})

    await create_class_timezone(callback, SimpleNamespace(zone="Europe/Moscow"), state, session)

    assert callback.alerted
    assert state.cleared
    assert (await session.scalars(select(SchoolClass))).all() == []


async def test_the_owner_can_still_create_a_class(session):
    from app.bot.handlers.start import create_class_timezone

    callback = _Callback(user_id=1000)  # matches OWNER_IDS in conftest
    state = _State({"grade": 9, "letter": "Б", "school": "Школа № 2"})

    await create_class_timezone(callback, SimpleNamespace(zone="Asia/Omsk"), state, session)

    created = await session.scalar(select(SchoolClass).where(SchoolClass.name == "9Б"))
    assert created is not None
    assert created.timezone == "Asia/Omsk"
    assert (created.grade, created.letter) == (9, "Б")
    assert not callback.alerted


async def test_a_half_finished_create_flow_does_not_raise(session):
    """The grade step never ran, so the data dict has no "grade" key."""
    from app.bot.handlers.start import create_class_timezone

    callback = _Callback(user_id=1000)
    state = _State({})

    await create_class_timezone(callback, SimpleNamespace(zone="Europe/Moscow"), state, session)

    assert callback.alerted
    assert (await session.scalars(select(SchoolClass))).all() == []


async def test_a_forged_grade_is_refused_rather_than_stored(session):
    """The keyboard offers 1..11; the payload it packs is a number an attacker
    types. A class numbered 99 would resolve its term scheme from a comparison
    that happens to be true rather than from a decision."""
    from app.bot.handlers.start import create_class_grade

    callback = _Callback(user_id=1000)
    state = _State({})

    await create_class_grade(callback, SimpleNamespace(grade=99), state)

    assert callback.alerted
    assert "grade" not in state.data


async def test_creating_a_class_seeds_its_terms(session):
    """A class has четверти from the moment it exists; the alternative is an
    empty screen for the first person who opens the calendar."""
    from app.bot.handlers.start import create_class_timezone
    from app.models import Term, TermKind

    callback = _Callback(user_id=1000)
    state = _State({"grade": 10, "letter": None, "school": None})

    await create_class_timezone(callback, SimpleNamespace(zone="Europe/Moscow"), state, session)

    created = await session.scalar(select(SchoolClass).where(SchoolClass.grade == 10))
    seeded = (await session.scalars(select(Term).where(Term.class_id == created.id))).all()
    # A tenth year is taught in halves, so there are two of them.
    assert len(seeded) == 2
    assert all(term.kind is TermKind.SEMESTER for term in seeded)


# --------------------------------------------------------------------------
# Every message uses ParseMode.HTML, so a class named "<b>" must not be able
# to break or forge the markup of a message the bot sends.
# --------------------------------------------------------------------------


async def test_a_class_name_containing_markup_is_escaped(session):
    from app.bot.handlers.manage import class_root

    hostile = SchoolClass(
        name="<b>9А</b><script>",
        school="<i>Школа</i>",
        join_code="HOSTIL",
        timezone="Europe/Moscow",
    )
    session.add(hostile)
    await session.commit()

    callback = _Callback(user_id=1000)
    await class_root(callback, _State({}), session, hostile, Role.OWNER)

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


# --------------------------------------------------------------------------
# The last-seen write must not poison the request it rides on
# --------------------------------------------------------------------------


async def test_a_failed_last_seen_write_still_serves_the_bundle(client, school_class, monkeypatch):
    """The rollback that absorbs the failure also expires every row the session
    holds, and an expired row in an async session reloads itself lazily - which
    raises MissingGreenlet from whatever the endpoint reads next. The branch
    that exists to keep the read working used to be the thing that broke it."""
    from sqlalchemy.exc import SQLAlchemyError

    from app.api import deps

    token = await _token(client)

    calls = {"n": 0}
    real_commit = deps.AsyncSession.commit

    async def failing_commit(self):
        calls["n"] += 1
        if calls["n"] == 1:
            raise SQLAlchemyError("disk full")
        return await real_commit(self)

    monkeypatch.setattr(deps.AsyncSession, "commit", failing_commit)

    response = await client.get(
        "/api/v1/bundle", headers={"Authorization": f"Bearer {token}"}
    )
    assert response.status_code == 200, response.text
    assert response.json()["school_class"]["name"] == school_class.name
    assert calls["n"] >= 1


# --------------------------------------------------------------------------
# `GET /bundle` seeds a brand-new class's terms on the read path, and every
# phone in that class polls it on the same timer - so "idempotent" is not
# enough on its own.
# --------------------------------------------------------------------------


async def test_the_bundle_that_loses_the_seeding_race_still_answers_the_phone(
    client, school_class
):
    """Two devices seeding one class at once, and both get a timetable.

    The bundle is a read that writes: a class with no terms for this year gets
    them here, because the alternative is an empty calendar on the first phone
    to open it. Two requests arriving together both find the year unseeded and
    both insert index 1, and the second loses `uq_term_slot` — which on Postgres
    poisons the request's whole transaction and answers a 500 to a phone that
    asked for a timetable. `terms.ensure` now seeds inside a savepoint and the
    loser concedes to the winner's rows, which are the same four dates.

    The rival commits from a second connection while this request is between
    its read and its insert, hooked onto the flush that sends that insert —
    `last_seen_at` is already written and committed by then, so the only
    statement in the window is the one the race is about.
    """
    from app.db import SessionLocal, get_session
    from app.models import Term
    from app.services import terms as terms_service

    token = await _token(client)
    start = date(2026, 9, 7)
    year = terms_service.opening_year_of(start)
    class_id = school_class.id

    async def a_session_that_loses_the_race():
        async with SessionLocal() as db:
            real_flush = db.flush

            async def flush_once_a_rival_has_seeded(*args, **kwargs):
                if any(isinstance(obj, Term) for obj in db.new):
                    db.flush = real_flush
                    async with SessionLocal() as rival:
                        klass = await rival.get(SchoolClass, class_id)
                        await terms_service.ensure(rival, klass, year)
                        await rival.commit()
                return await real_flush(*args, **kwargs)

            db.flush = flush_once_a_rival_has_seeded
            yield db

    app.dependency_overrides[get_session] = a_session_that_loses_the_race
    try:
        response = await client.get(
            "/api/v1/bundle",
            params={"start": start.isoformat(), "days": 1},
            headers={"Authorization": f"Bearer {token}"},
        )
    finally:
        app.dependency_overrides.pop(get_session)

    assert response.status_code == 200, response.text
    served = response.json()["school_class"]["terms"]
    assert [term["index"] for term in served] == [1, 2, 3, 4]

    async with SessionLocal() as after:
        stored = await terms_service.read(after, class_id, year)
    assert len(stored) == 4, "one set of four, not the two the race tried to write"
    assert [term.starts_on.isoformat() for term in stored] == [
        term["starts_on"] for term in served
    ]
