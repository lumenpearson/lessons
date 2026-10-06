"""The self-check every cron tick ends with, and what the owner is told of it.

Nothing inside a serverless deployment notices that it is broken. On 5 October
a merge did not deploy for half an hour (#349) and the diary's proxy answered
nothing for forty minutes, and the owner found each one by asking. So the tick,
the one thing that arrives from outside every five minutes, ends by asking four
questions (``docs/specs/2026-10-05-monitoring-design.md``):

- ``schema``: is the database at the revision this code expects?
- ``v2``: did v2 load, or does ``main.mount_v2`` answer 503 for it?
- ``diary_proxy``: does a ``HEAD`` of the Petersburg diary through
  ``DIARY_PROXY_URL`` get any answer at all within five seconds?
- ``deploy``: is production running ``main``'s head, or did ``main`` move less
  than fifteen minutes ago?

Each ends ``ok``, ``failing`` or ``unknown``. Unknown is a check that could not
run - GitHub not answering, a deployment that is not production, a proxy
nobody configured - and it never alerts. What each said last is a row of
``health_checks``. The owner, every account in ``OWNER_IDS``, is written to when
a check starts failing, when it comes back, and every six hours while it stays
failing, never once per tick. The text names the infrastructure only, never a
class, a family or a child.

It runs after the digests and before the diary keep-alive (``api/cron.py``,
Task 5), inside its own guard and its own time budget, so a failing check, a
slow GitHub or a Telegram that refuses the alert never fails the tick. The
checks themselves stop :data:`SEND_RESERVE_SECONDS` before that budget runs
out, so a check that ate most of the tick still leaves the alert itself a
real chance to go out, rather than discovering there is no time left only
once it has something to say.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from html import escape

import httpx
from sqlalchemy import select
from sqlalchemy import update as sa_update
from sqlalchemy.ext.asyncio import AsyncSession

from app import telegram_send
from app.config import Settings, deployment
from app.db import EXPECTED_REVISION, current_revision, rows_affected
from app.models import HealthCheck
from app.providers.petersburg.client import BASE_URL as DIARY_ORIGIN
from app.wording import cut, duration

log = logging.getLogger(__name__)

OK, FAILING, UNKNOWN = "ok", "failing", "unknown"

#: The checks, in the order the owner's screen lists them.
CHECKS = ("schema", "v2", "diary_proxy", "deploy")

#: How often the owner is reminded of a check that stays failing: often
#: enough not to be forgotten, rarely enough not to be muted (the design's
#: question 1, answered by the owner).
REMINDER_EVERY = timedelta(hours=6)

#: How long ``main`` may be ahead of production before the deploy is late.
#: Vercel builds a merge in two or three minutes; fifteen is a deploy that
#: did not start, which is what #349 was.
DEPLOY_GRACE = timedelta(minutes=15)

#: The proxy's ``HEAD`` and GitHub's read each get this long, and no longer.
CHECK_TIMEOUT_SECONDS = 5.0
#: The alerts' sends together get this long, and no longer.
SEND_TIMEOUT_SECONDS = 10.0
#: Counted from the start of the request, as the keep-alive's deadlines are:
#: nothing of the self-check is started past this point, so the tick ends
#: inside the function's ceiling (``maxDuration: 30`` in ``vercel.json``)
#: however long the digests took first. This is the sends' bound; the network
#: checks stop :data:`SEND_RESERVE_SECONDS` earlier than it.
HEALTH_HARD_STOP_SECONDS = 28.0
#: How much of the hard stop belongs to the sends alone, refused to the
#: checks. Without this, a check clamped to a sliver of a second by a tick
#: that had already spent most of its budget could time out and read
#: ``failing`` for a thing that is actually fine (reclassified in
#: :func:`_within`), and in a real incident the alert itself could be born
#: with no time left to go out - the very case the self-check exists for.
SEND_RESERVE_SECONDS = 5.0

#: The longest reason kept: one line on a screen, and the column's width.
REASON_MAX = 200

GITHUB_API = "https://api.github.com"

#: What the owner is told, per check: when it starts failing, when it is back.
WORDS: dict[str, tuple[str, str]] = {
    "schema": ("Схема базы не совпадает с кодом", "Схема базы снова совпадает с кодом"),
    "v2": ("v2 не загрузился: /api/v2 и /api/rpc отвечают 503", "v2 снова загружен"),
    "diary_proxy": ("Прокси дневника не отвечает", "Прокси дневника снова работает"),
    "deploy": (
        "Продакшен не на последнем коммите main",
        "Продакшен снова на последнем коммите main",
    ),
}

#: A sender: each ``(telegram_id, text)`` in, whether each was delivered out.
Sender = Callable[[Sequence[tuple[int, str]]], Awaitable[list[bool]]]


def _now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


@dataclass(frozen=True)
class Result:
    """What one check said this tick."""

    status: str
    reason: str = ""
    round_trip_ms: int | None = None
    #: Whether a network check's own budget ran out, as opposed to any other
    #: reason it is ``failing``. Set by the check itself, from the exception's
    #: *type* (:func:`_within` reads it rather than matching the reason's
    #: wording, which is free to change without silently breaking the clamp
    #: rule). Never written to ``health_checks`` - only ``status``, ``reason``
    #: and ``round_trip_ms`` are.
    timed_out: bool = False


@dataclass(frozen=True)
class Previous:
    """What :func:`decide` reads of a check's row."""

    status: str
    since: datetime
    last_alert_at: datetime | None


@dataclass(frozen=True)
class Step:
    """What a check's row becomes, and what, if anything, the owner is told.

    ``alert`` is ``"down"``, ``"reminder"``, ``"up"`` or ``None``; ``lasted``
    is how long the failure has gone on, for a reminder and for ``"up"``.
    """

    status: str
    since: datetime
    alert: str | None = None
    lasted: timedelta | None = None


# --------------------------------------------------------------------------
# The rule
# --------------------------------------------------------------------------


def decide(previous: Previous | None, result: Result, now: datetime) -> Step:
    """The alert rule, as one function of what was stored and what was seen.

    - A check that starts failing is told once («down»), and again every
      :data:`REMINDER_EVERY` while it stays failing («reminder»).
    - One that comes back after the owner was told is told once («up»), with
      how long it was down.
    - ``unknown`` is told nothing, and it neither starts nor ends a failure:
      ``since`` stays, so a failure that went unknown and then came back is
      reported whole, and one that went unknown and then failed again is not
      reported twice.
    - ``last_alert_at`` is what the owner was told: set, they believe the
      check is failing. A failure the owner was never told of (its alert did
      not get through, and the next tick retries it) is not followed by an
      «up».
    """
    if previous is None:
        return Step(result.status, now, "down" if result.status == FAILING else None)

    told = previous.last_alert_at
    was_failing = previous.status == FAILING or (previous.status == UNKNOWN and told is not None)

    if result.status == UNKNOWN:
        return Step(UNKNOWN, previous.since)

    if result.status == FAILING:
        since = previous.since if was_failing else now
        if told is None:
            return Step(FAILING, since, "down")
        if now - told >= REMINDER_EVERY:
            return Step(FAILING, since, "reminder", now - since)
        return Step(FAILING, since)

    since = now if was_failing else previous.since
    if told is not None:
        return Step(OK, since, "up", now - previous.since)
    return Step(OK, since)


def message(name: str, step: Step, reason: str) -> str:
    """The owner's message for ``step``, or ``""`` when there is none.

    HTML, as the bot sends everything: the reason is built from facts by the
    check, and escaped all the same, because everything that goes into a
    message is.
    """
    down, back = WORDS[name]
    minutes = max(1, round(step.lasted.total_seconds() / 60)) if step.lasted else 0
    if step.alert == "up":
        return f"🟢 {back}, простой {duration(minutes)}"
    if step.alert == "reminder":
        text = f"🔴 {down} — уже {duration(minutes)}"
    elif step.alert == "down":
        text = f"🔴 {down}"
    else:
        return ""
    if reason:
        text += f"\n<code>{escape(cut(reason, REASON_MAX))}</code>"
    return text


# --------------------------------------------------------------------------
# The checks
# --------------------------------------------------------------------------


async def check_schema(session: AsyncSession) -> Result:
    """The same reading ``/api/v1/warmup`` makes.

    A database *ahead* of the code is the window the project's own migration
    order creates on purpose - every additive revision goes on before the
    merge that needs it - and it closes on its own when the deploy lands, so
    it reads ``ok`` here exactly as ``api/public.py:_drift_detail`` already
    tells ``/api/v1/warmup``'s reader. *Behind* is the outage: the code reads
    a column that is not there, and that stays ``failing``. Every revision in
    this project is a zero-padded number, so comparing them as integers is
    meaningful; anything else (no ``alembic_version``, a non-numeric
    revision) falls through to the unchanged reading, which guesses no
    direction it cannot establish (#360).
    """
    revision = await current_revision(session)
    if revision is None:
        return Result(UNKNOWN, "no alembic_version table to read")
    if revision == EXPECTED_REVISION:
        return Result(OK, f"at {revision}")
    if revision.isdigit() and EXPECTED_REVISION.isdigit():
        if int(revision) > int(EXPECTED_REVISION):
            return Result(
                OK,
                f"database at {revision}, ahead of code at {EXPECTED_REVISION}: "
                "migrated before its merge",
            )
    return Result(FAILING, f"database at {revision}, code at {EXPECTED_REVISION}")


def check_v2(mounted: bool) -> Result:
    """``main.mount_v2``'s own record of whether v2 loaded."""
    if mounted:
        return Result(OK, "mounted")
    return Result(FAILING, "not loaded: /api/v2 and /api/rpc answer 503")


def _proxy_client(proxy: str, timeout: float) -> httpx.AsyncClient:
    """A client of its own rather than the diary's shared one, whose pool a
    probe must not hold and whose timeouts are a diary call's. The seam the
    tests replace."""
    return httpx.AsyncClient(proxy=proxy, timeout=timeout, follow_redirects=False)


async def check_diary_proxy(
    settings: Settings, *, timeout: float = CHECK_TIMEOUT_SECONDS
) -> Result:
    """One ``HEAD`` of the diary's front page through the proxy.

    Any HTTP status is ``ok``: it means the tunnel and the diary both
    answered, which is all this asks. Only when the setting is set; empty is
    a route, not a fault (``config.py``).
    """
    if not settings.diary_proxy_url.strip():
        return Result(UNKNOWN, "DIARY_PROXY_URL is empty: the diary is called directly")
    proxy = settings.diary_proxy
    if proxy is None:
        return Result(UNKNOWN, "DIARY_PROXY_URL is unusable: the diary is called directly")
    loop = asyncio.get_running_loop()
    started = loop.time()
    try:
        async with _proxy_client(proxy, timeout) as client:
            response = await asyncio.wait_for(client.head(f"{DIARY_ORIGIN}/"), timeout)
    except (httpx.TimeoutException, TimeoutError) as failure:
        # Caught ahead of the broader httpx.HTTPError below, and by type
        # rather than by the name it ends up in the reason: asyncio.wait_for's
        # own timeout and httpx's ConnectTimeout/ReadTimeout/… family both
        # land here, and only here is `timed_out` set, so the tick's clamp
        # rule (`_within`) never depends on a diagnostic string's wording.
        return Result(
            FAILING, f"no answer through the proxy: {type(failure).__name__}", timed_out=True
        )
    except httpx.HTTPError as failure:
        # The exception's type and never its text: httpx's message for a
        # refused tunnel can carry the proxy's address, which carries its
        # password.
        return Result(FAILING, f"no answer through the proxy: {type(failure).__name__}")
    round_trip = round((loop.time() - started) * 1000)
    return Result(OK, f"HTTP {response.status_code}", round_trip_ms=round_trip)


class _Unreadable(Exception):
    """GitHub answered, and not with ``main``'s head."""


@dataclass(frozen=True)
class _MainHead:
    repository: str
    etag: str
    sha: str
    #: When ``main`` moved to ``sha``: the commit's committer date, which for
    #: a merge made on GitHub is the moment of the merge. Naive UTC.
    moved_at: datetime


#: ``main``'s head as GitHub last told this process, and the ``ETag`` to ask
#: again with. In memory rather than in a row: a warm instance asks with it,
#: and an unchanged answer is a ``304`` - free of the rate limit only when the
#: request carries a working ``Authorization`` header (GitHub's own rule, not
#: this process's guess); an anonymous ``304`` still spends one of the sixty
#: requests an hour shared by every function on Vercel's egress address, same
#: as a cold instance asking without an ``ETag`` at all. ``GITHUB_READ_TOKEN``
#: is what makes the ``304`` actually free.
_main_head: _MainHead | None = None


def _github_client(timeout: float) -> httpx.AsyncClient:
    """The seam the tests replace."""
    return httpx.AsyncClient(
        base_url=GITHUB_API,
        timeout=timeout,
        follow_redirects=False,
        headers={
            "accept": "application/vnd.github+json",
            "user-agent": "lessons-health-check",
            "x-github-api-version": "2022-11-28",
        },
    )


async def _read_main(repository: str, token: str, timeout: float) -> _MainHead:
    global _main_head
    known = _main_head if _main_head is not None and _main_head.repository == repository else None
    headers = {"if-none-match": known.etag} if known is not None and known.etag else {}
    if token:
        # Never logged and never in a reason: it goes on the request and
        # nowhere else.
        headers["authorization"] = f"Bearer {token}"
    async with _github_client(timeout) as client:
        response = await client.get(f"/repos/{repository}/commits/main", headers=headers)
    if response.status_code == 304 and known is not None:
        return known
    if response.status_code != 200:
        # 403 and 429 are the rate limit, shared by every function on the
        # address: a refusal is GitHub's state, never production's.
        reason = f"GitHub answered HTTP {response.status_code}"
        if response.status_code in (403, 429) and not token:
            # The specific, fixable reason this address is out of requests:
            # an anonymous caller shares sixty an hour with the rest of
            # Vercel's egress, where an authorized one gets 5,000.
            reason += " (anonymous; set GITHUB_READ_TOKEN)"
        raise _Unreadable(reason)
    body = response.json()
    moved = datetime.fromisoformat(str(body["commit"]["committer"]["date"]))
    head = _MainHead(
        repository=repository,
        etag=response.headers.get("etag", ""),
        sha=str(body["sha"]),
        moved_at=moved.astimezone(UTC).replace(tzinfo=None),
    )
    _main_head = head
    return head


async def check_deploy(
    settings: Settings, now: datetime, *, timeout: float = CHECK_TIMEOUT_SECONDS
) -> Result:
    """Is production running ``main``'s head, or is the deploy still within its grace?"""
    running = deployment()
    if not settings.behind_vercel:
        return Result(UNKNOWN, "not on Vercel")
    if running.environment != "production":
        return Result(UNKNOWN, f"not production: VERCEL_ENV is {running.environment or 'unset'}")
    if not running.commit or not running.repository:
        return Result(UNKNOWN, "Vercel's system environment variables are not exposed")
    try:
        head = await asyncio.wait_for(
            _read_main(running.repository, settings.github_read_token_value, timeout), timeout
        )
    except _Unreadable as refused:
        return Result(UNKNOWN, str(refused))
    except (httpx.HTTPError, TimeoutError) as failure:
        return Result(UNKNOWN, f"GitHub did not answer: {type(failure).__name__}")
    except (KeyError, TypeError, ValueError):
        return Result(UNKNOWN, "GitHub's answer was not a commit")
    if head.sha == running.commit:
        return Result(OK, f"running main's head {head.sha[:7]}")
    behind = now - head.moved_at
    if behind < DEPLOY_GRACE:
        minutes = max(0, int(behind.total_seconds() // 60))
        return Result(OK, f"main moved to {head.sha[:7]} {minutes} min ago; the deploy has time")
    since = f"{head.moved_at:%Y-%m-%d %H:%M} UTC"
    return Result(FAILING, f"running {running.commit[:7]}, main is {head.sha[:7]} since {since}")


# --------------------------------------------------------------------------
# One run
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class _Alert:
    name: str
    kind: str
    text: str
    #: ``last_alert_at`` as this tick read it, and what telling the owner
    #: makes it: the claim is a compare-and-set from the one to the other.
    expected: datetime | None
    claimed: datetime | None


async def claim_alert(
    session: AsyncSession, name: str, expected: datetime | None, new: datetime | None
) -> bool:
    """Move ``last_alert_at`` from ``expected`` to ``new``, or report that
    something else already moved it. Commits.

    Two ticks overlap here as they do over the digests (``reminders.claim``):
    the external cron and the fallback workflow, or a tick killed by its
    caller's timeout and a retry. Both read the same row and both decide to
    tell the owner; the database lets exactly one of them.
    """
    column = HealthCheck.last_alert_at
    matches = column.is_(None) if expected is None else column == expected
    result = await session.execute(
        sa_update(HealthCheck).where(HealthCheck.name == name, matches).values(last_alert_at=new)
    )
    await session.commit()
    return rows_affected(result) == 1


async def _within(deadline: float, check: Callable[[float], Awaitable[Result]]) -> Result:
    """Run a check that goes to the network, inside what is left of the tick.

    A check given less than its full :data:`CHECK_TIMEOUT_SECONDS` because the
    tick had little of its own left, and that then times out, reads
    ``unknown`` rather than ``failing``: what ran out was the tick's budget,
    which says nothing about whether the thing being checked is actually
    down, and a reminder tick a few minutes later would have called it fine.
    This reads the check's own ``Result.timed_out`` rather than its reason's
    wording, so a rewording of that text can never silently bring the false
    «down» back.
    """
    left = deadline - asyncio.get_running_loop().time()
    if left <= 0:
        return Result(UNKNOWN, "the tick ran out of time before this check")
    budget = min(CHECK_TIMEOUT_SECONDS, left)
    try:
        result = await check(budget)
    except Exception as failure:  # noqa: BLE001 - a check that raises is a check that could not run
        # The type name only: an exception's own text, from a check that
        # talks to the proxy or to GitHub, can carry a password or a token.
        log.error("a health check raised %s", type(failure).__name__)
        return Result(UNKNOWN, f"the check raised {type(failure).__name__}")
    if budget < CHECK_TIMEOUT_SECONDS and result.status == FAILING and result.timed_out:
        # Built from the number alone, never from the check's own reason,
        # which already names the exception's type but not its text.
        return Result(UNKNOWN, f"the tick left this check only {budget:.1f} s")
    return result


async def _results(
    session: AsyncSession, settings: Settings, *, v2_mounted: bool, deadline: float, now: datetime
) -> dict[str, Result]:
    try:
        schema = await check_schema(session)
    except Exception as failure:  # noqa: BLE001 - see _within
        # On Postgres a statement that raised leaves the transaction aborted,
        # and everything this run writes next would fail on it.
        await session.rollback()
        log.exception("a health check raised")
        schema = Result(UNKNOWN, f"the check raised {type(failure).__name__}")
    try:
        v2 = check_v2(v2_mounted)
    except Exception as failure:  # noqa: BLE001 - see _within
        log.exception("a health check raised")
        v2 = Result(UNKNOWN, f"the check raised {type(failure).__name__}")
    proxy, deploy = await asyncio.gather(
        _within(deadline, lambda timeout: check_diary_proxy(settings, timeout=timeout)),
        _within(deadline, lambda timeout: check_deploy(settings, now, timeout=timeout)),
    )
    return {"schema": schema, "v2": v2, "diary_proxy": proxy, "deploy": deploy}


async def run(
    session: AsyncSession,
    settings: Settings,
    *,
    v2_mounted: bool,
    started: float | None = None,
    send: Sender | None = None,
) -> dict[str, str]:
    """Run the checks, keep what each said, and tell the owner of a change.

    ``started`` is ``loop.time()`` at the start of the request, so the budget
    counts what the tick did first; omitted, it counts from here. The network
    checks stop :data:`SEND_RESERVE_SECONDS` before the sends' own bound, so a
    check that used up most of the tick still leaves the alert a real chance
    to be sent. ``send`` is ``telegram_send.send`` unless a test hands
    another. Returns each check's status.
    """
    loop = asyncio.get_running_loop()
    start = started if started is not None else loop.time()
    checks_deadline = start + HEALTH_HARD_STOP_SECONDS - SEND_RESERVE_SECONDS
    send_deadline = start + HEALTH_HARD_STOP_SECONDS
    now = _now()
    results = await _results(
        session, settings, v2_mounted=v2_mounted, deadline=checks_deadline, now=now
    )

    stored = {row.name: row for row in await session.scalars(select(HealthCheck))}
    alerts: list[_Alert] = []
    for name in CHECKS:
        result = results[name]
        row = stored.get(name)
        previous = None if row is None else Previous(row.status, row.since, row.last_alert_at)
        step = decide(previous, result, now)
        if row is None:
            row = HealthCheck(name=name)
            session.add(row)
        else:
            row.previous_checked_at = row.checked_at
        row.status = step.status
        row.reason = cut(result.reason, REASON_MAX)
        row.since = step.since
        row.checked_at = now
        row.round_trip_ms = result.round_trip_ms
        if result.status == FAILING:
            log.warning("health check %s is failing: %s", name, result.reason)
        if step.alert is not None:
            expected = None if previous is None else previous.last_alert_at
            alerts.append(
                _Alert(
                    name=name,
                    kind=step.alert,
                    text=message(name, step, result.reason),
                    expected=expected,
                    claimed=None if step.alert == "up" else now,
                )
            )
    await session.commit()

    owners = settings.owner_id_list
    if owners:
        for alert in alerts:
            await _tell(session, alert, owners, send or telegram_send.send, send_deadline)
    return {name: results[name].status for name in CHECKS}


async def _tell(
    session: AsyncSession, alert: _Alert, owners: list[int], send: Sender, deadline: float
) -> None:
    """Claim the alert, send it, and give the claim back if nobody got it.

    The time is checked before the claim, not after: with none left, neither
    claiming nor sending is worth doing, and leaving the claim untouched means
    the next tick tries again immediately rather than finding the alert
    already spoken for. Given back on a failed send so that tick tries again
    rather than six hours later; except «up», whose figure would be wrong by
    then, so a recovery that does not get through is logged and not repeated.
    """
    left = deadline - asyncio.get_running_loop().time()
    if left <= 0:
        log.warning("no time left in the tick to tell the owner of %s", alert.name)
        return
    if not await claim_alert(session, alert.name, alert.expected, alert.claimed):
        return
    delivered: list[bool] = []
    try:
        delivered = await asyncio.wait_for(
            send([(owner, alert.text) for owner in owners]), min(SEND_TIMEOUT_SECONDS, left)
        )
    except Exception:  # noqa: BLE001 - a Telegram that refuses must not fail the tick
        log.warning("could not tell the owner of %s", alert.name, exc_info=True)
    if not any(delivered):
        log.warning("nobody was told that %s is %s", alert.name, alert.kind)
        if alert.kind != "up":
            await claim_alert(session, alert.name, alert.claimed, alert.expected)
