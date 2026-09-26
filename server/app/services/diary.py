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
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import UTC, datetime
from datetime import date as Date
from urllib.parse import urlsplit

from sqlalchemy import ColumnElement, and_, func, select
from sqlalchemy import delete as sa_delete
from sqlalchemy import update as sa_update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto import diary_enabled, seal, unseal
from app.db import rows_affected
from app.models import DiaryOverride, DiarySession, SchoolClass
from app.providers.diary.base import AdoptRequest, SignInRequest
from app.providers.diary.errors import DiaryError, SessionExpired
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
from app.providers.diary.registry import NETSCHOOL, PETERSBURG, Binding, provider_for
from app.providers.diary.registry import binding as class_binding
from app.security import hash_token, new_token

log = logging.getLogger(__name__)

#: How far apart two "last used" writes may be. Same reasoning as the device
#: tokens': this is telemetry, and writing it per request turns a read into a
#: write transaction.
LAST_USED_INTERVAL_SECONDS = 900


class DiaryDisabled(RuntimeError):
    """The deployment has no ``DIARY_SECRET``, so the diary does not run.

    A distinct exception rather than a generic failure because the answer to
    it is an operator's, not a user's: nothing the person types will help, and
    the message they get should say so instead of «попробуйте позже».
    """


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
    await session.commit()
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
    row, and what the validating read already fetched."""

    token: str
    row: DiarySession
    students: list[Student]
    school_name: str | None


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

    adopted = await impl.adopt(
        AdoptRequest(credential=credential, region=region, school_id=school_id)
    )
    token, row = await _open_row(
        session, adopted.credential, login=login, provider=provider, region=region
    )
    return Registered(
        token=token, row=row, students=list(adopted.students), school_name=adopted.school_name
    )


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
    """
    row = await session.scalar(
        select(DiarySession).where(DiarySession.token_hash == hash_token(token))
    )
    if row is None or not row.is_live:
        return None
    if upstream_of(row) is None:
        row.expired_at = utcnow()
        await session.commit()
        return None
    return row


# ---------------------------------------------------------------------------
# Corrections laid over what came down
# ---------------------------------------------------------------------------
#
# The rules for applying them live in ``services/diary_overrides.py``, which is
# free of SQLAlchemy so that they can be tested without a database. This is the
# other half: getting the rows in and out. They are filed under the child — the
# diary, and the pupil's id on it — rather than under the session or the login:
# a session ends every few days and a correction must not, and the login is
# whatever the phone typed, which nothing upstream vouches for (#165). So they
# are shared by everyone whose own diary lists the child. Which children a
# session reaches is decided in the routes, by `_student`: the session's own
# diary is asked for its pupils on every call, and an id it does not list is a
# 404 before any row here is read or written.

#: What every scope starts with — upper case on purpose; see `child_scope`.
SCOPE_PREFIX = "CHILD:"


class UnknownDiaryServer(ValueError):
    """A session whose diary this code cannot name a server for: a provider no
    implementation answers, or a «Сетевой город» region the allow-list does not
    hold. Refused rather than given a fallback scope, which would file one
    server's pupils beside another's."""


def child_scope(provider: str | None, region: str | None) -> str:
    """The diary a child's corrections are filed under; ``student_id`` is the
    child. Stored in ``DiaryOverride.login``, a column named for what it held
    before.

    Not the session: a diary session ends every few days, and a correction
    that went with it would vanish before anybody pressed «сбросить», with
    nothing left to reset. Not the login either: it is what the phone typed,
    and nothing upstream vouches for it.

    **Petersburg** is one city-wide server, and a ``NULL`` provider is a row
    from before there was a second one: ``CHILD:petersburg``.

    **«Сетевой город»** is taken to number its pupils per regional server (see
    below), so the scope names the server — ``CHILD:netschool:`` and the host
    of the region's origin, resolved through the allow-list exactly as the
    session's own calls are. The host, not the region key: if a region's
    origin ever moves, the corrections stop matching (lost) rather than attach
    to whichever child carries the same number on the new server (leaked), and
    two regions on one server rightly share its numbering. A region the
    allow-list does not hold raises :class:`UnknownDiaryServer`, never a guess.

    Those two are the only shapes. A child a diary lists by an id outside the
    provider's own numbering (``Student.id_space``) gets no scope at all —
    :meth:`DiaryService.scope_of` answers ``None`` and the routes offer it no
    corrections — because the number would then be the whole key, and nothing
    says that numbering names one person across families.

    Assumed and never observed: that a «Сетевой город» pupil id is unique per
    server rather than per school, and that two parents' accounts see one
    child under one id, in either diary. Were it per school, the school would
    have to come from the diary's own answer — never from the ``school_id`` a
    phone sends at registration, which nothing compares with the diary.

    **Upper case is the point of the prefix.** A key written before this
    function existed is ``login.strip().casefold()`` for Petersburg, or
    ``netschool:`` and 64 lower-case hex digits — casefolded or lower-case
    either way — so no scope can ever equal one, whatever login somebody types:
    «CHILD:petersburg» folds to ``child:petersburg``. Code reverted to before
    this change therefore cannot read a per-child row, or write into one, under
    any login; revision ``0017`` rewrites the old rows into scopes. What such
    code writes while it runs is filed under logins this code never reads, so
    a revert is not lossless — ``0017``'s docstring says how to fold them in.
    """
    provider = provider or PETERSBURG
    if provider == PETERSBURG:
        scope = f"{SCOPE_PREFIX}{PETERSBURG}"
    elif provider == NETSCHOOL:
        from app.providers.netschool import regions

        known = regions.get(region)
        if known is None:
            raise UnknownDiaryServer(f"no «Сетевой город» server for region {region!r}")
        scope = f"{SCOPE_PREFIX}{NETSCHOOL}:{urlsplit(known.origin).netloc.lower()}"
    else:
        raise UnknownDiaryServer(f"unknown diary provider {provider!r}")
    return scope


def _of_child(scope: str, student_id: int) -> tuple[ColumnElement[bool], ColumnElement[bool]]:
    """The two halves of the key, as the WHERE clause every query here uses.

    Refuses anything that is not a scope, because a login passed where a scope
    belongs is #165 again — a string the phone chose deciding whose
    corrections are read — and it would type-check perfectly well.
    """
    if not scope.startswith(SCOPE_PREFIX):
        raise ValueError("corrections are filed under child_scope(), not under a login")
    return DiaryOverride.login == scope, DiaryOverride.student_id == student_id


async def load_corrections(
    session: AsyncSession, scope: str, student_id: int
) -> dict[str, dict[str, tuple[str, str | None]]]:
    """Every correction for one child, shaped the way the overlay wants it.

    All of them, not a date range: a target carries its date inside a string,
    and asking the database to reason about that would make the key format
    something the schema knows. A family has a handful of these.
    """
    rows = await session.scalars(select(DiaryOverride).where(*_of_child(scope, student_id)))
    corrections: dict[str, dict[str, tuple[str, str | None]]] = {}
    for row in rows:
        corrections.setdefault(row.target, {})[row.field] = (row.value, row.original)
    return corrections


async def list_overrides(
    session: AsyncSession, scope: str, student_id: int
) -> list[DiaryOverride]:
    """The raw rows, for the screen that lists and resets them."""
    rows = await session.scalars(
        select(DiaryOverride)
        .where(*_of_child(scope, student_id))
        .order_by(DiaryOverride.target, DiaryOverride.field)
    )
    return list(rows)


async def put_override(
    session: AsyncSession,
    scope: str,
    student_id: int,
    target: str,
    field: str,
    value: str,
    original: str | None,
) -> DiaryOverride:
    """Writes a correction, replacing the one that was there.

    Upsert rather than insert: correcting the same field twice is the ordinary
    case — a person fixes a typo in their own fix — and a second row would make
    the unique constraint the thing that reports it. The last writer wins,
    ``original`` included, whoever wrote the row before: there is one
    correction per field per child, and no column says whose it was.
    """
    one = (
        *_of_child(scope, student_id),
        DiaryOverride.target == target,
        DiaryOverride.field == field,
    )
    row = await session.scalar(select(DiaryOverride).where(*one))
    if row is not None:
        row.value = value
        row.original = original
        await session.commit()
        await session.refresh(row)
        return row

    row = DiaryOverride(
        login=scope,
        student_id=student_id,
        target=target,
        field=field,
        value=value,
        original=original,
    )
    session.add(row)
    try:
        await session.commit()
    except IntegrityError:
        # Select-then-insert has a gap, and two adults who both see the child —
        # or one device double-tapping through a retry — fall into it. The
        # unique constraint catches it, which is the constraint doing its job;
        # what it must not do is become a 500 on the way out: the answer to
        # both writes is the row that is now there, carrying the later value.
        await session.rollback()
        row = await session.scalar(select(DiaryOverride).where(*one))
        if row is None:
            raise
        row.value = value
        row.original = original
        await session.commit()
    await session.refresh(row)
    return row


async def drop_override(
    session: AsyncSession, scope: str, student_id: int, target: str, field: str
) -> bool:
    """Resets one field. @return whether there was anything to reset."""
    row = await session.scalar(
        select(DiaryOverride).where(
            *_of_child(scope, student_id),
            DiaryOverride.target == target,
            DiaryOverride.field == field,
        )
    )
    if row is None:
        return False
    await session.delete(row)
    await session.commit()
    return True


async def drop_overrides(session: AsyncSession, scope: str, student_id: int) -> int:
    """Resets everything for one child, for everyone who sees that child — the
    corrections are the child's, not the account's — and never beyond it: the
    rows go through `list_overrides`, whose WHERE carries ``student_id``.

    @return how many were dropped.
    """
    rows = await list_overrides(session, scope, student_id)
    for row in rows:
        await session.delete(row)
    if rows:
        await session.commit()
    return len(rows)


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
    """Forgets the session, telling the upstream first where it can be told."""
    await _tell_upstream_goodbye(row)
    await session.delete(row)
    await session.commit()


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
        # An unknown provider is treated like an unreadable seal: `find_session`
        # and the bot's `_session_for` expire such a row before building this,
        # so this fallback is only ever reached in a test that skips them.
        provider = provider or provider_for(PETERSBURG)
        self.connection = provider.open(self._credential)  # type: ignore[union-attr]

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
            # `api/deps.py:_touch_last_seen` carries the same refresh, for the
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
