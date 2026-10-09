"""The diary as a feature: sessions, and the questions the API asks.

Sits between the routes and the provider so that neither has to know the
other's shape. The routes get models and exceptions; the provider gets a token
and a date range. What lives only here is the part that is this project's
policy rather than the upstream's behaviour: how a session is stored, when it
is refreshed, and what happens when it dies.

The rule about credentials is enforced here, in one place: the upstream's own
session is what is kept, never a password. It reaches this server one of two
ways. :func:`sign_in` takes a password, uses it once to log in and writes it
nowhere — the bot's sign-in page and ``POST /api/v1/diary/login``, which older
apps still call. :func:`adopt` takes a session the phone opened itself,
straight with the diary, so no password ever came here; it is checked with a
read of this server's own before it is kept. Either way the session is sealed,
refreshed in place whenever a call brings back a newer one, and when the
upstream stops accepting it the row is marked expired and the person signs in
again. That costs a login screen every few days and buys not holding a
family's password.

The corrections a family lays over what came down are filed per child, in
:mod:`app.services.diary_corrections`, beside the rules for applying them in
:mod:`app.services.diary_overrides`.
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from datetime import date as Date
from typing import Any

from sqlalchemy import ColumnElement, and_, func, select
from sqlalchemy import delete as sa_delete
from sqlalchemy import update as sa_update
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto import diary_enabled, seal, unseal
from app.db import rows_affected
from app.models import DiarySession, SchoolClass
from app.providers.diary.base import AdoptRequest, SignInRequest
from app.providers.diary.errors import (
    BadCredentials,
    DiaryError,
    NoStudents,
    SessionExpired,
    UpstreamUnavailable,
)
from app.providers.diary.models import (
    AcademicPeriod,
    AttendanceEvent,
    DiaryLesson,
    HomeworkItem,
    Mark,
    Student,
    Subject,
    Teacher,
)
from app.providers.diary.registry import PETERSBURG, Binding, Needs, provider_for, row_for
from app.providers.diary.registry import binding as class_binding
from app.security import DiaryAttempt, hash_token, new_token
from app.services import clock, diary_corrections
from app.services import diary_overrides as overrides
from app.services.diary_corrections import UnknownDiaryServer, child_scope

log = logging.getLogger(__name__)

#: How far apart two "last used" writes may be. Same reasoning as the device
#: tokens': this is telemetry, and writing it per request turns a read into a
#: write transaction.
LAST_USED_INTERVAL_SECONDS = 900

#: How long :func:`adopt` waits for the diary. Inside the phone's 30 s read
#: timeout and ``vercel.json``'s 30 s ``maxDuration``, with room for the
#: database write after it, so the phone always hears an answer and never gives
#: up on a session this server then goes on to keep (#233).
ADOPT_UPSTREAM_BUDGET_SECONDS = 22.0


class DiaryDisabled(RuntimeError):
    """The deployment has no ``DIARY_SECRET``, so the diary does not run.

    A distinct exception rather than a generic failure because the answer to
    it is an operator's, not a user's: nothing the person types will help, and
    the message they get should say so instead of «попробуйте позже».
    """


class UnknownProvider(ValueError):
    """A provider key the table has no row for, named by the caller."""

    def __init__(self, key: str) -> None:
        super().__init__("unknown diary provider")
        self.key = key


class RegionNotServed(ValueError):
    """A provider whose binding needs a region, named with one its allow-list
    does not hold or that takes no password — or with none."""


class SchoolRequired(ValueError):
    """A provider whose binding needs a school, named without one."""


class SessionRefused(BadCredentials):
    """The diary would not take the session a phone opened, from this server.

    What the provider's ``adopt`` raises for it is :class:`SessionExpired` — a
    401, a 403 or a login page in answer to the validating read — or, from a
    provider that judged it as credentials, :class:`BadCredentials`. Told apart
    from the first because a read's :class:`SessionExpired` means «sign in
    again», and here that would loop: the session was good on the phone seconds
    ago, and it is this server the diary will not take it from. A
    :class:`BadCredentials`, because nothing retried with it will help, and so
    every shell words the two alike.
    """


class UnknownStudent(LookupError):
    """A pupil id this session's diary does not list: another family's child,
    or nobody's."""

    def __init__(self, student_id: int) -> None:
        super().__init__("unknown student")
        self.student_id = student_id


@dataclass(frozen=True)
class Target:
    """Where a sign-in or a session goes: a provider, and for one whose binding
    needs them, an allow-listed region and a school; ``None`` for one that
    does not."""

    provider: str
    region: str | None
    school_id: int | None


def target(provider: str | None, region: str | None, school_id: int | None) -> Target:
    """The provider, region and school to sign in with or adopt into, checked
    against the provider's row before any upstream call and before anything is
    counted, so a region this server does not serve never receives a request,
    whichever door it came through.

    An absent provider is Petersburg, so an older phone that sends only a login
    and a password is unchanged. A provider whose binding needs nothing takes
    no region and no school, whatever was sent with it.

    @raises UnknownProvider, RegionNotServed or SchoolRequired, in that order.
    """
    row = row_for(provider or PETERSBURG)
    if row is None:
        raise UnknownProvider(provider or PETERSBURG)
    if row.needs is Needs.NOTHING:
        return Target(row.key, None, None)
    served = row.served_region(region)
    if served is None:
        raise RegionNotServed
    if school_id is None:
        raise SchoolRequired
    return Target(row.key, served, school_id)


def sealed_form(provider: str, handed: Mapping[str, Any]) -> str:
    """What a phone handed over, in its provider's own serialisation: the one
    field that is the whole session (Petersburg's bare token), or the JSON of
    everything handed («Сетевой город»'s ``at``, cookies, ``ver`` and
    ``time_out``), a field it did not hand left out rather than stored as a
    null. ``handed`` is what v1's credential schema validated, dumped without
    its nulls, whichever version received it."""
    row = row_for(provider)
    if row is None:
        raise UnknownProvider(provider)
    if row.bare_field is not None:
        return str(handed[row.bare_field])
    return json.dumps(dict(handed), ensure_ascii=False, separators=(",", ":"))


def utcnow() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


def zone_for(provider: str, region: str | None = None) -> str:
    """The IANA zone a diary's days are cut at — Petersburg's city clock, or a
    «Сетевой город» region's administrative centre.

    Asked of the provider, whose connection's ``today()`` cuts the day with
    the very same rule, so what the phone is told at registration and what
    this server reads as «today» for the same session cannot drift apart.
    """
    impl = provider_for(provider)
    if impl is None:
        # A key no implementation answers. Callers validate first, so this is a
        # programming error, not a user's.
        raise ValueError(f"unknown diary provider {provider!r}")
    return impl.zone(region)


async def _open_row(
    session: AsyncSession,
    credential: str,
    *,
    login: str,
    provider: str,
    region: str | None,
    telegram_id: int | None = None,
) -> tuple[str, DiarySession]:
    """Seal an upstream credential and open a session of ours on it.

    The one place a row is minted, whichever way the credential arrived, so
    that «what a session holds» has one answer. Every caller has just had the
    upstream accept the credential, which is what ``upstream_ok_at`` records.

    Leaves the commit to its caller (the 3b plan, Ruling 106): a row whose
    token never reached a phone is no session to keep, and the keep-alive would
    ping it for a month. :func:`register`'s and ``/login``'s rows are committed
    with the attempt's outcome, the sign-in page's with its class.

    @return the token to hand the client — shown once, stored only as a hash —
        and the row behind it.
    """
    now = utcnow()
    token = new_token()
    row = DiarySession(
        token_hash=hash_token(token),
        # Sealed before it is ever handed to the session, so there is no path
        # through this function on which the plaintext reaches the ORM.
        upstream_token=seal(credential),
        login=login.strip(),
        provider=provider,
        region=region,
        telegram_id=telegram_id,
        last_used_at=now,
        upstream_ok_at=now,
    )
    session.add(row)
    await session.flush()
    return token, row


async def sign_in(
    session: AsyncSession,
    login: str,
    password: str,
    telegram_id: int | None = None,
    *,
    provider: str = PETERSBURG,
    region: str | None = None,
    school_id: int | None = None,
) -> tuple[str, DiarySession]:
    """Logs in upstream through the named provider and opens a session of ours.

    @return the token to hand the client - shown once, stored only as a hash -
        and the row behind it.
    """
    # Checked before the upstream call, not after: without a key the result
    # has nowhere to go, and sending someone's password to a third party to
    # then throw the answer away is the one order of operations that is worse
    # than refusing.
    if not diary_enabled():
        raise DiaryDisabled("DIARY_SECRET is not configured")

    impl = provider_for(provider)
    if impl is None:
        # A provider key no implementation answers. Callers validate first, so
        # this is a programming error, not a user's.
        raise ValueError(f"unknown diary provider {provider!r}")

    credential = await impl.sign_in(
        SignInRequest(
            login=login.strip(), password=password, region=region, school_id=school_id
        )
    )
    return await _open_row(
        session, credential, login=login, provider=provider, region=region,
        telegram_id=telegram_id,
    )


@dataclass(frozen=True)
class Registered:
    """A session the phone opened, now ours: the token to hand back once, the
    row, what the validating read already fetched, the school it was opened
    with, and the zone its diary cuts its days at."""

    token: str
    row: DiarySession
    students: list[Student]
    school_name: str | None
    school_id: int | None
    zone: str


async def adopt(
    session: AsyncSession,
    *,
    provider: str,
    login: str,
    credential: str,
    region: str | None = None,
    school_id: int | None = None,
) -> Registered:
    """Keep a session the phone opened itself, once this server has seen it work.

    The password never came here: the phone signed in with the diary directly
    and hands over only what the diary gave it. The provider reads with it from
    this server's address — that read is the whole check, since a session the
    upstream will not take from here is worth nothing to keep — and what it
    hands back (possibly rotated) is what is sealed.

    ``login`` is what the person typed on the phone. Nothing upstream vouches
    for it, so it names the row — what «Вход выполнен» prints — and nothing
    else: corrections are filed under the child (:func:`child_scope`) and
    reached only through a pupil this session's own diary lists.

    The row carries no Telegram account and no class: a phone's session is not
    the bot's, and linking the two is deliberately left for later.
    """
    # Before the upstream call, for `sign_in`'s reason: without a key the
    # answer has nowhere to go, and a read made to throw it away is a read
    # made from our address for nothing.
    if not diary_enabled():
        raise DiaryDisabled("DIARY_SECRET is not configured")

    impl = provider_for(provider)
    if impl is None:
        raise ValueError(f"unknown diary provider {provider!r}")

    # One deadline over the upstream half, never the database write after it.
    # «Сетевой город» adopts in four reads in a row, each limited per phase
    # only, so a slow region could hold the request past the phone's 30 s read
    # timeout and Vercel's 30 s ceiling — the phone then gave up on a session
    # this server went on to keep (#233). Out of time is the same answer as no
    # answer: a 503 «upstream», not counted against the throttle, which the
    # phone shows as «Дневник не отвечает» and can retry without the password.
    try:
        adopted = await asyncio.wait_for(
            impl.adopt(AdoptRequest(credential=credential, region=region, school_id=school_id)),
            ADOPT_UPSTREAM_BUDGET_SECONDS,
        )
    except TimeoutError as failure:
        raise UpstreamUnavailable("Дневник не ответил вовремя") from failure
    token, row = await _open_row(
        session, adopted.credential, login=login, provider=provider, region=region
    )
    return Registered(
        token=token,
        row=row,
        students=list(adopted.students),
        school_name=adopted.school_name,
        school_id=school_id,
        zone=impl.zone(region),
    )


async def register(
    session: AsyncSession,
    *,
    provider: str,
    login: str,
    handed: Mapping[str, Any],
    region: str | None,
    school_id: int | None,
    failures_key: str,
    opened_key: str,
) -> Registered:
    """Keep a session a phone opened itself, counted on both diary limiters,
    or raise the fact that says why not: v1's ``POST /diary/session`` and v2's
    ``CreateDiarySession`` alike, on one budget (the server-v2 design,
    decision 11).

    In that route's order: the target, so a region this server does not serve
    is :class:`RegionNotServed` before anything is counted or sent; then the
    attempt, ``security.Throttled`` while the caller has spent either limit;
    then :func:`adopt`, the diary's own read from this server's address.

    - **Counted as a failure**, the diary having judged what was sent:
      :class:`SessionRefused` (the session would not open from here),
      ``NoStudents``, and any other answer of the diary's family nobody can read.
    - **Counted as a session opened**: a success, twenty to a caller in fifteen
      minutes across both doors.
    - **Not counted**, nothing having judged it: :class:`DiaryDisabled`, and
      ``UpstreamUnavailable`` (``AddressRefused`` and a read out of time
      included).

    The attempt's outcome commits, on purpose (``security.DiaryAttempt``), and
    with it the new row, which is never kept apart from the outcome that
    counts it; anything else is the caller's to commit.
    """
    where = target(provider, region, school_id)
    # Sealed before the attempt is counted: a bad shape (a missing bare field)
    # is this caller's error, not the diary's, and nothing judged it, so it
    # must not count as a try against the throttle either.
    credential = sealed_form(where.provider, handed)
    attempt = await DiaryAttempt.admit(session, failures_key=failures_key, opened_key=opened_key)
    try:
        registered = await adopt(
            session,
            provider=where.provider,
            login=login,
            credential=credential,
            region=where.region,
            school_id=where.school_id,
        )
    except (DiaryDisabled, UpstreamUnavailable):
        await attempt.not_judged(session)
        raise
    except NoStudents:
        # Before the clause below, which it is a SessionExpired of: the session
        # opened, and there is nobody behind it to read.
        await attempt.failed(session)
        raise
    except (SessionExpired, BadCredentials) as failure:
        # Counted: this is also what a replay of a session that was never real
        # looks like.
        await attempt.failed(session)
        raise SessionRefused from failure
    except DiaryError:
        await attempt.failed(session)
        raise
    await attempt.succeeded(session)
    return registered


#: The days a diary read covers after its start when no end is named, and the
#: most it may span: v1's ``/diary`` reads and v2's alike. The upstream is asked
#: for the same span, and a year of lessons in one call is how an undocumented
#: API starts refusing to answer at all. The most is ``clock``'s own sixty-two,
#: so that the error table's sentence, built from it, is true of these too.
WINDOW_DAYS = 14
WINDOW_MAX_DAYS = clock.WINDOW_MAX_DAYS


def window(start: Date | None, end: Date | None, today: Date) -> tuple[Date, Date]:
    """The first and last day a diary read covers, both included: ``today`` —
    the diary's own, never the server's — and :data:`WINDOW_DAYS` on when
    unset, :data:`WINDOW_MAX_DAYS` at most. Moved from v1's ``_range`` (the
    server-v2 design, decision 2), whose order it keeps: each date named is
    bounded before anything is derived from it, because ``start + 14 days``
    near ``date.max`` raises OverflowError.

    @raises clock.WindowRefused naming the edge at fault, and why.
    """
    for edge, day in (("start", start), ("end", end)):
        if day is not None and not clock.in_bounds(day):
            raise clock.WindowRefused(edge, clock.OUT_OF_BOUNDS)
    first = start or today
    last = end or first + timedelta(days=WINDOW_DAYS)
    if last < first:
        raise clock.WindowRefused("end", clock.BACKWARDS)
    if (last - first).days > WINDOW_MAX_DAYS:
        raise clock.WindowRefused("end", clock.TOO_WIDE)
    return first, last


async def corrections(
    session: AsyncSession, scope: str | None, student_id: int
) -> dict[str, dict[str, tuple[str, str | None]]]:
    """Every correction laid over one child's diary, or none for a child who
    can have none (``scope`` ``None``, :meth:`DiaryService.scope_of`)."""
    if scope is None:
        return {}
    return await diary_corrections.load_corrections(session, scope, student_id)


def reads_binding(bound: Binding) -> ColumnElement[bool]:
    """Whether a session row reads the diary a class is bound to.

    The one clause for it, used by the bot's lookup and by the expiry on
    rebinding, so «which sessions belong to this binding» cannot be answered
    two ways. Petersburg is one server, so the provider is enough — and a row
    with no provider is Petersburg, which is what the column meant before it
    existed. A many-server provider must match the region too: a «Сетевой
    город» session is a session with one regional server, and read against
    another region's binding it would talk to the diary the class has left.

    Both halves are written with ``coalesce`` so the clause is never SQL's
    NULL, which ``NOT`` would leave NULL and so match nothing.
    """
    provider = func.coalesce(DiarySession.provider, PETERSBURG)
    if bound.region is None:
        return provider == bound.provider.key
    return and_(
        provider == bound.provider.key,
        func.coalesce(DiarySession.region, "") == bound.region,
    )


async def expire_off_binding(session: AsyncSession, school_class: SchoolClass) -> int:
    """Expire the class's live sessions its current binding no longer reads.

    Called when a class is bound, before the caller commits, so the binding
    and the expiry land together. The bot already stops *reading* such a row
    (:func:`reads_binding`); expiring it as well is what stops the keep-alive
    pinging a regional server the class has left, and what the purge then
    sweeps. The same region again, or another school in it, keeps them: a
    session is an account on the region's server, not on one school.

    Not called on unbinding. That takes the door away and nothing else — the
    sessions stay their owners' until they sign out, as the bot promises.

    @return how many rows were expired. Does not commit.
    """
    bound = class_binding(school_class)
    if bound is None:
        return 0
    result = await session.execute(
        sa_update(DiarySession)
        .where(
            DiarySession.class_id == school_class.id,
            DiarySession.expired_at.is_(None),
            ~reads_binding(bound),
        )
        .values(expired_at=utcnow())
    )
    return rows_affected(result)


def upstream_of(row: DiarySession) -> str | None:
    """The row's upstream credential, opened, or ``None`` if it cannot be.

    One place where a stored credential is decrypted, so that «can this session
    still be used» has one answer rather than one per caller. ``None`` covers a
    rotated key and a row written before encryption alike, and both mean the
    same thing to the person: sign in again.
    """
    return unseal(row.upstream_token)


async def find_session(session: AsyncSession, token: str) -> DiarySession | None:
    """The live session behind a token, or ``None``.

    A row whose credential will not open is expired here rather than handed on.
    Letting it through would spend an upstream round trip to be told the same
    thing, from an address the upstream rate-limits.

    A row of a provider this deployment does not know is refused and left as
    it is (#389). A provider is a value, not a migration, so a deployment rolled
    back past the release that added one still holds that provider's sessions;
    read here, they would go to whichever diary the reader fell back to, with
    another diary's credential. Not expired: the release that knows the
    provider reads them again.
    """
    row = await session.scalar(
        select(DiarySession).where(DiarySession.token_hash == hash_token(token))
    )
    if row is None or not row.is_live:
        return None
    if row_for(row.provider or PETERSBURG) is None:
        return None
    if await unusable(session, row):
        return None
    return row


async def unusable(session: AsyncSession, row: DiarySession) -> bool:
    """Whether the row's credential cannot be opened here, expiring it only
    when it never will.

    A seal that a configured key cannot open — the key was rotated, the row
    predates encryption — is dead, and expiring it is the honest answer. No
    key at all is a different situation: every seal reads as unreadable, and
    expiring them would throw away every family's session over a
    misconfiguration that putting the key back undoes (#302). Then the diary
    is off, and the row waits for the key.
    """
    if upstream_of(row) is not None:
        return False
    if diary_enabled():
        row.expired_at = utcnow()
        await session.commit()
    return True



async def _tell_upstream_goodbye(row: DiarySession) -> None:
    """Best-effort logout upstream before we forget a session.

    Petersburg has no logout that works without a browser, so its connection's
    ``close`` does nothing; «Сетевой город» has one, and leaving a session live
    when the family pressed «Выйти» is exactly what its `close` prevents.
    Nothing here may raise: we are forgetting the row regardless.
    """
    provider = provider_for(row.provider or PETERSBURG)
    if provider is None:
        return
    credential = upstream_of(row)
    if not credential:
        return
    try:
        await provider.open(credential).close()
    except Exception:  # noqa: BLE001 - we are leaving; an upstream failure is fine
        log.info("diary logout upstream failed for session %s", row.id, exc_info=True)


async def sign_out(session: AsyncSession, row: DiarySession) -> None:
    """Forgets the session, telling the upstream first where it can be told.

    Leaves the commit to its caller (the 3b plan, Ruling 106): v1's
    ``/logout`` commits after it, and v2's ``DeleteDiarySession`` in ``invoke``.
    """
    await _tell_upstream_goodbye(row)
    await session.delete(row)
    await session.flush()


async def sign_out_here(
    session: AsyncSession, *, telegram_id: int, class_id: int
) -> int:
    """Forget every session this Telegram account holds in this class.

    The bot has no notion of «this browser session»: every one of its screens
    finds a session by (telegram_id, class_id) and by nothing else, so its
    «Выйти» means all of them. And there can be several — nothing expires an
    earlier sign-in, ``api/diary_web`` opens a row and leaves the ones already
    there, so somebody who signed in again from an older «🔐 Войти» card holds
    two. Dropping only the newest left «вы вышли» sitting over a diary that
    the next press walked straight back into.

    Scoped to the one class on purpose: a parent in two of them is signing out
    of one child, not both. @return how many rows went.
    """
    rows = list(
        await session.scalars(
            select(DiarySession).where(
                DiarySession.telegram_id == telegram_id,
                DiarySession.class_id == class_id,
            )
        )
    )
    for row in rows:
        await _tell_upstream_goodbye(row)
    result = await session.execute(
        sa_delete(DiarySession).where(
            DiarySession.telegram_id == telegram_id,
            DiarySession.class_id == class_id,
        )
    )
    await session.commit()
    return rows_affected(result)


async def _refresh_quietly(session: AsyncSession, row: DiarySession) -> None:
    """Un-expire ``row`` after a rollback, and never raise doing it.

    The refresh is a fresh ``SELECT`` down the connection the commit just lost,
    so when the commit failed it usually fails too — and it is the last
    statement of an ``except`` block, so an exception here replaces whatever
    that block was on its way to doing.

    In :meth:`DiaryService._expire` that would be the worst possible trade: the
    caller is on its way to re-raising ``SessionExpired``, which the route
    turns into ``401`` with ``X-Diary-Reauth: required`` — the one signal the
    app has for «спросите пароль заново». Losing it to a 500 costs the family
    the sign-in prompt on the exact path whose whole job is to ask for it.

    A refresh that fails leaves ``row`` expired, which is where it was before
    this helper existed. That is the old failure, not a new one.
    """
    try:
        await session.refresh(row)
    except Exception:  # noqa: BLE001 - see the docstring; nothing here may raise
        log.warning("could not refresh the diary session row", exc_info=True)


class DiaryService:
    """One signed-in session's view of the diary.

    Every method persists a refreshed upstream token on the way out, because
    the upstream hands one back on most calls and dropping it is how a session
    that should have lived for weeks dies in a day.
    """

    __slots__ = ("session", "row", "connection", "_credential")

    def __init__(self, session: AsyncSession, row: DiarySession) -> None:
        self.session = session
        self.row = row
        # The plaintext lives for the life of this object and nowhere else. An
        # empty string when the seal will not open, which every call then turns
        # into the upstream's own «signed out» — the same ending the person
        # would reach a few days later anyway.
        self._credential = upstream_of(row) or ""
        provider = provider_for(row.provider or PETERSBURG)
        if provider is None:
            # Every door refuses such a row before building this: `find_session`
            # by the table, the bot's `_session_for` by the class's binding.
            # Reading it with another provider would send its credential to a
            # diary it was never opened with (#389).
            raise ValueError(f"no provider answers diary session {row.id}")
        self.connection = provider.open(self._credential)

    async def students(self) -> list[Student]:
        return await self._call(self.connection.students())

    async def periods(self, group_id: int) -> list[AcademicPeriod]:
        return await self._call(self.connection.periods(group_id))

    async def subjects(self, group_id: int, period_id: int) -> list[Subject]:
        return await self._call(self.connection.subjects(group_id, period_id))

    async def teachers(self, education_id: int) -> list[Teacher]:
        return await self._call(self.connection.teachers(education_id))

    async def marks(self, education_id: int, date_from: Date, date_to: Date) -> list[Mark]:
        return await self._call(self.connection.marks(education_id, date_from, date_to))

    async def schedule(
        self, education_id: int, date_from: Date, date_to: Date
    ) -> list[DiaryLesson]:
        return await self._call(self.connection.schedule(education_id, date_from, date_to))

    async def homework(
        self, education_id: int, date_from: Date, date_to: Date
    ) -> list[HomeworkItem]:
        return await self._call(self.connection.homework(education_id, date_from, date_to))

    async def attendance(self, education_id: int) -> list[AttendanceEvent]:
        return await self._call(self.connection.attendance(education_id))

    def today(self) -> Date:
        """The diary's own day, from the provider — one city's clock for
        Petersburg, the region's zone for «Сетевой город»."""
        return self.connection.today()

    async def scope_of(self, student: Student) -> str | None:
        """The scope ``student``'s corrections are filed under, from this
        session's diary — its provider and region, never its login — or
        ``None`` when this child can have no corrections at all.

        ``None`` is a child the diary listed by an id outside the provider's
        own numbering (``Student.id_space``: Petersburg's plain ``id``, where
        ``identity.id`` was missing). With the login out of the key, the
        number would be all that tells one family's child from another's, and
        nothing — no answer seen, no client read — says that numbering names a
        person across accounts rather than, say, a relation within one. Two
        families each listing a plain 7 would share, overwrite and reset one
        set. So such a child fails closed: its diary is shown as it came, and a
        correction is refused. No live answer has ever taken this path.

        Asked only of a pupil this session's own diary has just listed. A row
        whose region names no server this code knows is treated the way an
        unreadable credential is: expired, and :class:`SessionExpired` for the
        route to turn into «войдите заново», because a session this server
        cannot place is not one to keep reading, and a fallback scope would
        file its pupils beside another server's.
        """
        try:
            scope = child_scope(self.row.provider, self.row.region)
        except UnknownDiaryServer:
            log.warning("diary session %s names no known server", self.row.id)
            await self._expire()
            raise SessionExpired from None
        return None if student.id_space is not None else scope

    # ---- one pupil: the rules v1's routes held, for every shell -------

    async def student(self, student_id: int) -> Student:
        """The pupil ``student_id``, resolved from this account's own diary on
        every call rather than trusted. The id is not a secret, and without the
        lookup one family's id in another family's request would read
        somebody else's child.

        @raises UnknownStudent for an id this session's diary does not list.
        """
        for student in await self.students():
            if student.id == student_id:
                return student
        raise UnknownStudent(student_id)

    async def child(self, student_id: int) -> tuple[Student, str | None]:
        """The pupil, and the scope their corrections are filed under
        (:meth:`scope_of`): the one door every correction read and write goes
        through, so that an unknown id is refused before any row is touched and
        the scope comes from the session's diary, never from its login (#165)."""
        student = await self.student(student_id)
        return student, await self.scope_of(student)

    async def schedule_of(
        self, student: Student, scope: str | None, start: Date, end: Date
    ) -> list[overrides.OverlaidLesson]:
        """The pupil's lessons from ``start`` to ``end``, with the corrections
        filed under ``scope`` laid over them, in the diary's own order."""
        lessons = await self.schedule(student.education_id, start, end)
        found = await corrections(self.session, scope, student.id)
        return overrides.overlay_lessons(lessons, found)

    async def homework_of(
        self, student: Student, scope: str | None, start: Date, end: Date
    ) -> list[overrides.OverlaidHomework]:
        """The pupil's homework due from ``start`` to ``end``, with the
        corrections filed under ``scope`` laid over it."""
        items = await self.homework(student.education_id, start, end)
        found = await corrections(self.session, scope, student.id)
        return overrides.overlay_homework(items, found)

    async def periods_of(self, student: Student) -> list[AcademicPeriod]:
        """The pupil's quarters or terms; none for a pupil the diary files
        under no class, whose periods there is nothing to ask by."""
        if student.group_id is None:
            return []
        return await self.periods(student.group_id)

    async def subjects_of(self, student: Student, period_id: int | None) -> list[Subject]:
        """The subjects of ``period_id``, or of the current period when it is
        ``None``; none for a pupil with no class, or when no period is current."""
        if student.group_id is None:
            return []
        chosen = period_id
        if chosen is None:
            found = await self.periods(student.group_id)
            current = next((period for period in found if period.is_current), None)
            if current is None:
                return []
            chosen = current.id
        return await self.subjects(student.group_id, chosen)

    # ---- plumbing -----------------------------------------------------

    async def _call(self, awaitable):
        """Run one provider read, then keep any refreshed credential.

        A ``SessionExpired`` expires the row and is re-raised untouched — the
        route turns it into the re-sign-in signal. Any other provider failure
        still re-seals first: the answer that failed to read may have carried a
        rotated token, and dropping it would replay the old one next call.
        """
        try:
            result = await awaitable
        except SessionExpired:
            await self._expire()
            raise
        except DiaryError:
            await self._remember_token()
            raise
        await self._remember_token()
        return result

    async def _remember_token(self) -> None:
        """Stores a refreshed upstream credential, and the fact we were here.

        Both writes are the same transaction and neither is worth failing a
        read over, so a failure here is logged and swallowed - the answer the
        caller asked for has already been fetched.
        """
        now = utcnow()
        changed = False
        credential = self.connection.credential
        if credential and credential != self._credential:
            self._credential = credential
            self.row.upstream_token = seal(credential)
            changed = True
        last = self.row.last_used_at
        if last is None or (now - last).total_seconds() >= LAST_USED_INTERVAL_SECONDS:
            self.row.last_used_at = now
            changed = True
        if not changed:
            return
        try:
            await self.session.commit()
        except Exception:  # noqa: BLE001 - telemetry must not fail a read
            log.warning("could not store the refreshed diary session", exc_info=True)
            await self.session.rollback()
            # A rollback expires every instance in the session, including this
            # one — and an expired instance in an async session reloads itself
            # lazily, which raises MissingGreenlet from whatever attribute is
            # touched next. `scope_of` reads the row's provider and region
            # straight after `_student`'s call in here, so without this the
            # failure this branch exists to absorb comes back as a 500 from a
            # line that only wanted a string.
            # `api/deps.py:touch_last_seen` carries the same refresh, for the
            # same reason and after the same outage.
            await _refresh_quietly(self.session, self.row)

    async def _expire(self) -> None:
        """Marks the session dead so the next request fails fast, with the
        answer that actually helps: sign in again."""
        self.row.expired_at = utcnow()
        try:
            await self.session.commit()
        except Exception:  # noqa: BLE001
            log.warning("could not mark the diary session expired", exc_info=True)
            await self.session.rollback()
            # @see _remember_token: the rollback expires `row`, and the caller
            # reads it straight afterwards.
            await _refresh_quietly(self.session, self.row)
