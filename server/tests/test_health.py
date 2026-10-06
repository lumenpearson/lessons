"""The self-check the tick ends with: four checks, their states, and the alert rule.

The outside world is faked at its three seams: the proxy's client
(``health._proxy_client``), GitHub's (``health._github_client``) and Vercel's
own environment variables. Nothing here reaches the network, and nothing here
sends a message: ``run`` is handed a sender that records what it was given.

What the owner would notice first is pinned hardest: one message on a change,
none per tick, a reminder every six hours, nothing at all for ``unknown``, and
a Telegram that refuses never failing the run.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Callable
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import pytest
from sqlalchemy import select, update
from sqlalchemy import text as sa_text

from app.config import Deployment, Settings, deployment
from app.db import EXPECTED_REVISION
from app.models import HealthCheck
from app.services import health
from app.services.health import FAILING, OK, UNKNOWN, Previous, Result, Step, decide

OWNER = 1000  # OWNER_IDS in conftest
PROXY = "http://user:hunter2@proxy.example:3128"
COMMIT = "a" * 40
MAIN = "b" * 40
T0 = datetime(2026, 10, 6, 12, 0)


def _now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


@pytest.fixture(autouse=True)
def forget_main_s_head(monkeypatch):
    """The ETag cache is the process's; each test starts without one."""
    monkeypatch.setattr(health, "_main_head", None)


class Sent:
    """A sender that records each batch and answers as it is told to."""

    def __init__(self, answer: str = "delivered") -> None:
        self.batches: list[list[tuple[int, str]]] = []
        self.answer = answer

    async def __call__(self, messages):
        self.batches.append(list(messages))
        if self.answer == "raises":
            raise RuntimeError("Telegram is down")
        return [self.answer == "delivered"] * len(messages)

    @property
    def texts(self) -> list[str]:
        return [text for batch in self.batches for _, text in batch]


async def _rows(session) -> dict[str, HealthCheck]:
    session.expire_all()
    return {row.name: row for row in await session.scalars(select(HealthCheck))}


@pytest.fixture
async def stamped(session) -> AsyncIterator[Callable[[str], Any]]:
    """Put an ``alembic_version`` in the test database, and take it away
    after: the autouse ``drop_all`` does not know alembic's table."""

    async def stamp(revision: str) -> None:
        await session.execute(sa_text("DROP TABLE IF EXISTS alembic_version"))
        await session.execute(
            sa_text("CREATE TABLE alembic_version (version_num VARCHAR(32) NOT NULL)")
        )
        await session.execute(
            sa_text("INSERT INTO alembic_version (version_num) VALUES (:v)"), {"v": revision}
        )
        await session.commit()

    yield stamp
    await session.execute(sa_text("DROP TABLE IF EXISTS alembic_version"))
    await session.commit()


def _fake_proxy(monkeypatch, handler) -> list[str]:
    """Answer the proxy check with ``handler``; returns the proxies it was built with."""
    built: list[str] = []

    def client(proxy: str, timeout: float) -> httpx.AsyncClient:
        built.append(proxy)
        return httpx.AsyncClient(transport=httpx.MockTransport(handler), timeout=timeout)

    monkeypatch.setattr(health, "_proxy_client", client)
    return built


def _fake_github(monkeypatch, handler) -> list[httpx.Request]:
    """Answer GitHub with ``handler``; returns the requests it was asked."""
    asked: list[httpx.Request] = []

    async def recording(request: httpx.Request) -> httpx.Response:
        asked.append(request)
        answer = handler(request)
        return await answer if asyncio.iscoroutine(answer) else answer

    def client(timeout: float) -> httpx.AsyncClient:
        return httpx.AsyncClient(
            base_url=health.GITHUB_API, transport=httpx.MockTransport(recording), timeout=timeout
        )

    monkeypatch.setattr(health, "_github_client", client)
    return asked


def _commit(sha: str, moved_at: datetime, etag: str = 'W/"one"') -> httpx.Response:
    return httpx.Response(
        200,
        json={"sha": sha, "commit": {"committer": {"date": f"{moved_at:%Y-%m-%dT%H:%M:%S}Z"}}},
        headers={"etag": etag},
    )


@pytest.fixture
def production(monkeypatch) -> Settings:
    """A production deployment on Vercel, as the platform describes it."""
    monkeypatch.setenv("VERCEL_ENV", "production")
    monkeypatch.setenv("VERCEL_GIT_COMMIT_SHA", COMMIT)
    monkeypatch.setenv("VERCEL_GIT_REPO_OWNER", "lumenpearson")
    monkeypatch.setenv("VERCEL_GIT_REPO_SLUG", "lessons")
    return Settings(VERCEL="1")


# --------------------------------------------------------------------------
# The rule
# --------------------------------------------------------------------------


def test_a_check_that_starts_failing_is_told_once():
    first = decide(None, Result(FAILING, "x"), T0)
    assert (first.status, first.since, first.alert) == (FAILING, T0, "down")

    after_ok = decide(Previous(OK, T0 - timedelta(days=2), None), Result(FAILING), T0)
    assert (after_ok.since, after_ok.alert) == (T0, "down")


def test_a_failure_is_not_told_again_on_every_tick():
    told = Previous(FAILING, T0, T0)
    later = decide(told, Result(FAILING), T0 + timedelta(minutes=5))
    assert (later.status, later.since, later.alert) == (FAILING, T0, None)


def test_a_failure_six_hours_after_the_last_alert_is_told_again():
    told = Previous(FAILING, T0, T0)
    almost = decide(told, Result(FAILING), T0 + timedelta(hours=6) - timedelta(seconds=1))
    assert almost.alert is None

    again = decide(told, Result(FAILING), T0 + timedelta(hours=6))
    assert (again.alert, again.lasted, again.since) == ("reminder", timedelta(hours=6), T0)


def test_a_recovery_is_told_once_with_how_long_it_lasted():
    back = decide(Previous(FAILING, T0, T0), Result(OK), T0 + timedelta(minutes=40))
    assert (back.status, back.since, back.alert, back.lasted) == (
        OK, T0 + timedelta(minutes=40), "up", timedelta(minutes=40),
    )

    settled = decide(Previous(OK, back.since, None), Result(OK), T0 + timedelta(hours=1))
    assert (settled.since, settled.alert) == (back.since, None)


def test_unknown_never_alerts_and_moves_no_period():
    assert decide(None, Result(UNKNOWN), T0).alert is None
    for previous in (Previous(OK, T0, None), Previous(FAILING, T0, T0)):
        step = decide(previous, Result(UNKNOWN), T0 + timedelta(hours=7))
        assert (step.status, step.since, step.alert) == (UNKNOWN, T0, None)


def test_a_failure_that_went_unknown_is_reported_whole_when_it_comes_back():
    """GitHub stopped answering in the middle of a late deploy, then the
    deploy landed: the «up» says how long the whole failure lasted."""
    went_unknown = Previous(UNKNOWN, T0, T0)
    back = decide(went_unknown, Result(OK), T0 + timedelta(hours=2))
    assert (back.alert, back.lasted) == ("up", timedelta(hours=2))


def test_a_failure_that_went_unknown_and_failed_again_is_not_told_twice():
    went_unknown = Previous(UNKNOWN, T0, T0)
    again = decide(went_unknown, Result(FAILING), T0 + timedelta(minutes=10))
    assert (again.status, again.since, again.alert) == (FAILING, T0, None)


def test_a_failure_the_owner_never_heard_of_is_told_next_tick_and_has_no_recovery():
    """Its alert did not get through, so the claim was given back: the next
    tick tells it, and a recovery before then says nothing at all."""
    unheard = Previous(FAILING, T0, None)
    assert decide(unheard, Result(FAILING), T0 + timedelta(minutes=5)).alert == "down"
    assert decide(unheard, Result(OK), T0 + timedelta(minutes=5)).alert is None


def test_the_messages_are_the_design_s_and_escape_the_reason():
    up = Step(OK, T0, "up", timedelta(minutes=40))
    assert health.message("diary_proxy", up, "") == (
        "🟢 Прокси дневника снова работает, простой 40 мин"
    )

    down = Step(FAILING, T0, "down")
    assert health.message("diary_proxy", down, "no answer: <ProxyError> & co") == (
        "🔴 Прокси дневника не отвечает\n"
        "<code>no answer: &lt;ProxyError&gt; &amp; co</code>"
    )

    reminder = Step(FAILING, T0, "reminder", timedelta(hours=6))
    assert health.message("deploy", reminder, "") == (
        "🔴 Продакшен не на последнем коммите main — уже 6 ч"
    )
    assert health.message("schema", Step(OK, T0), "at 0019") == ""
    assert set(health.WORDS) == set(health.CHECKS)


# --------------------------------------------------------------------------
# The checks
# --------------------------------------------------------------------------


async def test_the_schema_check_reads_the_revision_warmup_reads(session, stamped):
    unknown = await health.check_schema(session)
    assert unknown.status == UNKNOWN

    await stamped(EXPECTED_REVISION)
    assert (await health.check_schema(session)).status == OK

    await stamped("0001")
    failing = await health.check_schema(session)
    assert failing.status == FAILING
    assert "0001" in failing.reason and EXPECTED_REVISION in failing.reason


def test_the_v2_check_is_the_mount_s_own_record():
    assert health.check_v2(True).status == OK
    assert health.check_v2(False).status == FAILING


async def test_the_proxy_check_takes_any_answer_and_times_it(monkeypatch):
    seen: list[httpx.Request] = []

    def answer(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(405)

    built = _fake_proxy(monkeypatch, answer)
    result = await health.check_diary_proxy(Settings(DIARY_PROXY_URL=PROXY))

    assert (result.status, result.reason) == (OK, "HTTP 405")
    assert result.round_trip_ms is not None and result.round_trip_ms >= 0
    assert built == [PROXY]
    assert [(r.method, str(r.url)) for r in seen] == [("HEAD", "https://dnevnik2.petersburgedu.ru/")]


async def test_a_proxy_that_does_not_answer_is_failing_and_its_password_is_never_said(
    monkeypatch,
):
    def refuse(request: httpx.Request) -> httpx.Response:
        raise httpx.ProxyError(f"407 Proxy Authentication Required from {PROXY}")

    _fake_proxy(monkeypatch, refuse)
    result = await health.check_diary_proxy(Settings(DIARY_PROXY_URL=PROXY))

    assert result.status == FAILING
    assert result.reason == "no answer through the proxy: ProxyError"
    assert "hunter2" not in result.reason


async def test_a_proxy_slower_than_its_budget_is_failing(monkeypatch):
    async def stall(request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(5)
        return httpx.Response(200)

    _fake_proxy(monkeypatch, stall)
    result = await health.check_diary_proxy(Settings(DIARY_PROXY_URL=PROXY), timeout=0.05)

    assert result.status == FAILING
    assert result.reason == "no answer through the proxy: TimeoutError"


async def test_without_a_usable_proxy_there_is_nothing_to_check(monkeypatch):
    built = _fake_proxy(monkeypatch, lambda request: httpx.Response(200))

    empty = await health.check_diary_proxy(Settings(DIARY_PROXY_URL=""))
    unusable = await health.check_diary_proxy(Settings(DIARY_PROXY_URL="ftp://user:x@host:21"))

    assert (empty.status, unusable.status) == (UNKNOWN, UNKNOWN)
    assert "x@host" not in unusable.reason
    assert built == []


async def test_the_deploy_check_is_ok_on_main_s_head(monkeypatch, production):
    _fake_github(monkeypatch, lambda request: _commit(COMMIT, _now() - timedelta(days=1)))
    result = await health.check_deploy(production, _now())
    assert (result.status, result.reason) == (OK, f"running main's head {COMMIT[:7]}")


async def test_a_deploy_inside_its_grace_is_ok_and_one_past_it_is_failing(monkeypatch, production):
    now = _now()
    _fake_github(monkeypatch, lambda request: _commit(MAIN, now - timedelta(minutes=5)))
    assert (await health.check_deploy(production, now)).status == OK

    health._main_head = None
    _fake_github(monkeypatch, lambda request: _commit(MAIN, now - timedelta(minutes=20)))
    late = await health.check_deploy(production, now)
    assert late.status == FAILING
    assert COMMIT[:7] in late.reason and MAIN[:7] in late.reason


async def test_the_deploy_check_is_unknown_off_production(monkeypatch, production):
    asked = _fake_github(monkeypatch, lambda request: _commit(COMMIT, _now()))

    off_vercel = await health.check_deploy(Settings(VERCEL=""), _now())
    monkeypatch.setenv("VERCEL_ENV", "preview")
    preview = await health.check_deploy(production, _now())
    monkeypatch.setenv("VERCEL_ENV", "production")
    monkeypatch.delenv("VERCEL_GIT_COMMIT_SHA")
    unexposed = await health.check_deploy(production, _now())

    assert [off_vercel.status, preview.status, unexposed.status] == [UNKNOWN] * 3
    assert asked == []


@pytest.mark.parametrize(
    "answer",
    [
        lambda request: httpx.Response(403, json={"message": "API rate limit exceeded"}),
        lambda request: httpx.Response(200, json={"message": "not a commit"}),
        lambda request: (_ for _ in ()).throw(httpx.ConnectError("no route")),
    ],
    ids=["rate-limited", "not-a-commit", "no-answer"],
)
async def test_a_github_that_refuses_or_does_not_answer_is_unknown_never_failing(
    monkeypatch, production, answer
):
    _fake_github(monkeypatch, answer)
    result = await health.check_deploy(production, _now())
    assert result.status == UNKNOWN


async def test_an_unchanged_main_is_asked_with_its_etag_and_answered_304(monkeypatch, production):
    moved = _now() - timedelta(days=1)

    def answer(request: httpx.Request) -> httpx.Response:
        if request.headers.get("if-none-match") == 'W/"one"':
            return httpx.Response(304)
        return _commit(COMMIT, moved)

    asked = _fake_github(monkeypatch, answer)
    first = await health.check_deploy(production, _now())
    second = await health.check_deploy(production, _now())

    assert (first.status, second.status) == (OK, OK)
    assert [r.headers.get("if-none-match") for r in asked] == [None, 'W/"one"']
    assert asked[0].url.path == "/repos/lumenpearson/lessons/commits/main"


def test_the_deployment_is_what_vercel_says_and_nothing_else():
    said = deployment(
        {
            "VERCEL_ENV": "production",
            "VERCEL_GIT_COMMIT_SHA": f" {COMMIT}\n",
            "VERCEL_GIT_REPO_OWNER": "lumenpearson",
            "VERCEL_GIT_REPO_SLUG": "lessons",
            "VERCEL_REGION": "fra1",
        }
    )
    assert (said.environment, said.commit, said.repository, said.region) == (
        "production", COMMIT, "lumenpearson/lessons", "fra1",
    )
    assert deployment({"VERCEL_GIT_REPO_OWNER": "lumenpearson"}).repository == ""
    assert deployment({}) == Deployment(environment="", commit="", repository="", region="")


# --------------------------------------------------------------------------
# One run
# --------------------------------------------------------------------------


async def test_a_run_keeps_one_row_per_check_and_the_tick_before(session):
    sent = Sent()
    statuses = await health.run(session, Settings(), v2_mounted=True, send=sent)

    assert statuses == {"schema": UNKNOWN, "v2": OK, "diary_proxy": UNKNOWN, "deploy": UNKNOWN}
    first = await _rows(session)
    assert set(first) == set(health.CHECKS)
    assert all(row.previous_checked_at is None for row in first.values())
    checked = first["v2"].checked_at

    await health.run(session, Settings(), v2_mounted=True, send=sent)
    second = await _rows(session)
    assert second["v2"].previous_checked_at == checked
    assert second["v2"].since == first["v2"].since
    assert sent.batches == []


async def test_one_message_on_a_change_both_ways_and_none_per_tick(session):
    sent = Sent()
    for _ in range(3):
        await health.run(session, Settings(), v2_mounted=False, send=sent)
    assert sent.batches == [[(OWNER, "🔴 v2 не загрузился: /api/v2 и /api/rpc отвечают 503\n"
                                     "<code>not loaded: /api/v2 and /api/rpc answer 503</code>")]]

    await session.execute(
        update(HealthCheck)
        .where(HealthCheck.name == "v2")
        .values(since=_now() - timedelta(minutes=40))
    )
    await session.commit()
    for _ in range(2):
        await health.run(session, Settings(), v2_mounted=True, send=sent)

    assert sent.texts[1:] == ["🟢 v2 снова загружен, простой 40 мин"]
    assert (await _rows(session))["v2"].last_alert_at is None


async def test_a_check_still_failing_six_hours_on_is_told_again(session):
    sent = Sent()
    await health.run(session, Settings(), v2_mounted=False, send=sent)
    await session.execute(
        update(HealthCheck)
        .where(HealthCheck.name == "v2")
        .values(
            since=_now() - timedelta(hours=6, minutes=1),
            last_alert_at=_now() - timedelta(hours=6, minutes=1),
        )
    )
    await session.commit()

    await health.run(session, Settings(), v2_mounted=False, send=sent)
    await health.run(session, Settings(), v2_mounted=False, send=sent)

    assert len(sent.texts) == 2
    assert sent.texts[1].startswith(
        "🔴 v2 не загрузился: /api/v2 и /api/rpc отвечают 503 — уже 6 ч"
    )


@pytest.mark.parametrize("answer", ["raises", "refused"])
async def test_a_send_that_fails_fails_nothing_and_is_tried_again_next_tick(session, answer):
    failing = Sent(answer)
    statuses = await health.run(session, Settings(), v2_mounted=False, send=failing)

    assert statuses["v2"] == FAILING
    assert len(failing.batches) == 1
    assert (await _rows(session))["v2"].last_alert_at is None

    working = Sent()
    await health.run(session, Settings(), v2_mounted=False, send=working)
    assert len(working.batches) == 1
    assert (await _rows(session))["v2"].last_alert_at is not None


async def test_an_alert_one_of_two_owners_got_is_told_and_not_repeated(session):
    """One owner has blocked the bot: the other was told, which is what the
    alert is for, so the claim stands and the next tick says nothing."""

    class OneOfTwo(Sent):
        async def __call__(self, messages):
            self.batches.append(list(messages))
            return [True, False]

    sent = OneOfTwo()
    settings = Settings(OWNER_IDS="1000,1001")
    await health.run(session, settings, v2_mounted=False, send=sent)
    await health.run(session, settings, v2_mounted=False, send=sent)

    assert [[owner for owner, _ in batch] for batch in sent.batches] == [[1000, 1001]]
    assert (await _rows(session))["v2"].last_alert_at is not None


async def test_two_ticks_running_together_tell_the_owner_once(session):
    await health.run(session, Settings(), v2_mounted=True, send=Sent())
    now = _now()

    assert await health.claim_alert(session, "v2", None, now)
    assert not await health.claim_alert(session, "v2", None, now)
    assert await health.claim_alert(session, "v2", now, None)


async def test_with_no_owner_nothing_is_claimed_or_sent(session):
    sent = Sent()
    await health.run(session, Settings(OWNER_IDS=""), v2_mounted=False, send=sent)

    assert sent.batches == []
    assert (await _rows(session))["v2"].last_alert_at is None


async def test_a_tick_out_of_time_asks_nobody_and_says_so(monkeypatch, session, production):
    proxies = _fake_proxy(monkeypatch, lambda request: httpx.Response(200))
    asked = _fake_github(monkeypatch, lambda request: _commit(COMMIT, _now()))
    settings = Settings(VERCEL="1", DIARY_PROXY_URL=PROXY)
    long_ago = asyncio.get_running_loop().time() - 100

    statuses = await health.run(session, settings, v2_mounted=True, started=long_ago, send=Sent())

    assert (statuses["diary_proxy"], statuses["deploy"]) == (UNKNOWN, UNKNOWN)
    assert (proxies, asked) == ([], [])
    rows = await _rows(session)
    assert rows["deploy"].reason == "the tick ran out of time before this check"


async def test_a_check_that_raises_is_unknown_and_the_run_goes_on(monkeypatch, session):
    async def broken(*args, **kwargs):
        raise RuntimeError("a bug")

    monkeypatch.setattr(health, "check_schema", broken)
    monkeypatch.setattr(health, "check_diary_proxy", broken)

    statuses = await health.run(session, Settings(), v2_mounted=True, send=Sent())

    assert statuses == {"schema": UNKNOWN, "v2": OK, "diary_proxy": UNKNOWN, "deploy": UNKNOWN}
    rows = await _rows(session)
    assert rows["schema"].reason == "the check raised RuntimeError"
    assert rows["diary_proxy"].reason == "the check raised RuntimeError"
