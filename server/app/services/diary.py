"""The diary as a feature: sessions, and the questions the API asks.

Sits between the routes and the provider so that neither has to know the
other's shape. The routes get models and exceptions; the provider gets a token
and a date range. What lives only here is the part that is this project's
policy rather than the upstream's behaviour: how a session is stored, when it
is refreshed, and what happens when it dies.

The rule about credentials is enforced here, in one place: the password is used
once, to log in, and is never written anywhere. The upstream's own session
token is what is kept, it is refreshed in place whenever a call brings back a
newer one, and when the upstream stops accepting it the row is marked expired
and the person signs in again. That costs a login screen every few days and
buys not holding a family's password.
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime
from datetime import date as Date
from typing import Any

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto import diary_enabled, seal, unseal
from app.models import DiaryOverride, DiarySession
from app.providers.petersburg import (
    PetersburgClient,
    SessionExpired,
)
from app.providers.petersburg import mapper as m
from app.providers.petersburg.models import (
    AcademicPeriod,
    AttendanceEvent,
    DiaryLesson,
    HomeworkItem,
    Mark,
    Student,
    Subject,
    Teacher,
)
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


async def sign_in(
    session: AsyncSession, login: str, password: str, telegram_id: int | None = None
) -> tuple[str, DiarySession]:
    """Logs in upstream and opens a session of ours.

    @return the token to hand the client - shown once, stored only as a hash -
        and the row behind it.
    """
    # Checked before the upstream call, not after: without a key the result
    # has nowhere to go, and sending someone's password to a third party to
    # then throw the answer away is the one order of operations that is worse
    # than refusing.
    if not diary_enabled():
        raise DiaryDisabled("DIARY_SECRET is not configured")

    client = PetersburgClient()
    upstream = await client.login(login.strip(), password)

    token = new_token()
    row = DiarySession(
        token_hash=hash_token(token),
        # Sealed before it is ever handed to the session, so there is no path
        # through this function on which the plaintext reaches the ORM.
        upstream_token=seal(upstream),
        login=login.strip(),
        telegram_id=telegram_id,
        last_used_at=utcnow(),
    )
    session.add(row)
    await session.commit()
    return token, row


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
# other half: getting the rows in and out. They are keyed by the upstream login
# rather than by the session, because a session ends every few days and a
# correction must not.


def owner_key(login: str) -> str:
    """The form of a login that corrections are filed under.

    Case-folded, because the upstream does not care: a family that signed in as
    ``Ivan@mail.ru`` and later types ``ivan@mail.ru`` lands in the same account
    there, and must land on the same corrections here. Keyed on the raw string,
    every one of them would vanish the first time somebody's keyboard
    capitalised the first letter — with no reset button, because there would be
    nothing left to reset.

    The row's own ``login`` stays as it was typed: it is what «вы вошли как»
    prints, and that should say what the person wrote.
    """
    return login.strip().casefold()


async def load_corrections(
    session: AsyncSession, login: str, student_id: int
) -> dict[str, dict[str, tuple[str, str | None]]]:
    """Every correction for one child, shaped the way the overlay wants it.

    All of them, not a date range: a target carries its date inside a string,
    and asking the database to reason about that would make the key format
    something the schema knows. A family has a handful of these.
    """
    rows = await session.scalars(
        select(DiaryOverride).where(
            DiaryOverride.login == owner_key(login),
            DiaryOverride.student_id == student_id,
        )
    )
    corrections: dict[str, dict[str, tuple[str, str | None]]] = {}
    for row in rows:
        corrections.setdefault(row.target, {})[row.field] = (row.value, row.original)
    return corrections


async def list_overrides(
    session: AsyncSession, login: str, student_id: int
) -> list[DiaryOverride]:
    """The raw rows, for the screen that lists and resets them."""
    rows = await session.scalars(
        select(DiaryOverride)
        .where(
            DiaryOverride.login == owner_key(login),
            DiaryOverride.student_id == student_id,
        )
        .order_by(DiaryOverride.target, DiaryOverride.field)
    )
    return list(rows)


async def put_override(
    session: AsyncSession,
    login: str,
    student_id: int,
    target: str,
    field: str,
    value: str,
    original: str | None,
) -> DiaryOverride:
    """Writes a correction, replacing the one that was there.

    Upsert rather than insert: correcting the same field twice is the ordinary
    case — a person fixes a typo in their own fix — and a second row would make
    the unique constraint the thing that reports it.
    """
    row = await session.scalar(
        select(DiaryOverride).where(
            DiaryOverride.login == owner_key(login),
            DiaryOverride.student_id == student_id,
            DiaryOverride.target == target,
            DiaryOverride.field == field,
        )
    )
    if row is not None:
        row.value = value
        row.original = original
        await session.commit()
        await session.refresh(row)
        return row

    row = DiaryOverride(
        login=owner_key(login),
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
        # Select-then-insert has a gap, and two devices of one family — or one
        # device double-tapping through a retry — fall into it. The unique
        # constraint catches it, which is the constraint doing its job; what it
        # must not do is become a 500 on the way out, because from the person's
        # side both taps said the same thing and the answer to both is the row
        # that is now there.
        await session.rollback()
        row = await session.scalar(
            select(DiaryOverride).where(
                DiaryOverride.login == owner_key(login),
                DiaryOverride.student_id == student_id,
                DiaryOverride.target == target,
                DiaryOverride.field == field,
            )
        )
        if row is None:
            raise
        row.value = value
        row.original = original
        await session.commit()
    await session.refresh(row)
    return row


async def drop_override(
    session: AsyncSession, login: str, student_id: int, target: str, field: str
) -> bool:
    """Resets one field. @return whether there was anything to reset."""
    row = await session.scalar(
        select(DiaryOverride).where(
            DiaryOverride.login == owner_key(login),
            DiaryOverride.student_id == student_id,
            DiaryOverride.target == target,
            DiaryOverride.field == field,
        )
    )
    if row is None:
        return False
    await session.delete(row)
    await session.commit()
    return True


async def drop_overrides(session: AsyncSession, login: str, student_id: int) -> int:
    """Resets everything for one child. @return how many were dropped."""
    rows = await list_overrides(session, login, student_id)
    for row in rows:
        await session.delete(row)
    if rows:
        await session.commit()
    return len(rows)


async def sign_out(session: AsyncSession, row: DiarySession) -> None:
    """Forgets the session. The upstream is not told: it has no logout that
    can be called without a browser, and its token expires on its own."""
    await session.delete(row)
    await session.commit()


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

    __slots__ = ("session", "row", "client", "_upstream")

    def __init__(self, session: AsyncSession, row: DiarySession) -> None:
        self.session = session
        self.row = row
        # The plaintext lives for the life of this object and nowhere else. An
        # empty string when the seal will not open, which every call then turns
        # into the upstream's own «signed out» — the same ending the person
        # would reach a few days later anyway.
        self._upstream = upstream_of(row) or ""
        self.client = PetersburgClient(self._upstream)

    async def students(self) -> list[Student]:
        return await self._call(lambda: self.client.children(), m.to_students)

    async def periods(self, group_id: int) -> list[AcademicPeriod]:
        today = Date.today()
        return await self._call(
            lambda: self.client.periods(group_id), lambda items: m.to_periods(items, today)
        )

    async def subjects(self, group_id: int, period_id: int) -> list[Subject]:
        return await self._call(
            lambda: self.client.subjects(group_id, period_id), m.to_subjects
        )

    async def teachers(self, education_id: int) -> list[Teacher]:
        return await self._call(lambda: self.client.teachers(education_id), m.to_teachers)

    async def marks(self, education_id: int, date_from: Date, date_to: Date) -> list[Mark]:
        return await self._call(
            lambda: self.client.marks(education_id, date_from, date_to), m.to_marks
        )

    async def schedule(
        self, education_id: int, date_from: Date, date_to: Date
    ) -> list[DiaryLesson]:
        return await self._call(
            lambda: self.client.schedule(education_id, date_from, date_to), m.to_lessons
        )

    async def lessons(
        self, education_id: int, date_from: Date, date_to: Date
    ) -> list[DiaryLesson]:
        return await self._call(
            lambda: self.client.lessons(education_id, date_from, date_to), m.to_lessons
        )

    async def homework(
        self, education_id: int, date_from: Date, date_to: Date
    ) -> list[HomeworkItem]:
        return await self._call(
            lambda: self.client.lessons(education_id, date_from, date_to), m.to_homework
        )

    async def attendance(self, education_id: int) -> list[AttendanceEvent]:
        return await self._call(lambda: self.client.attendance(education_id), m.to_attendance)

    # ---- plumbing -----------------------------------------------------

    async def _call(self, fetch, convert):
        try:
            items: list[dict[str, Any]] = await fetch()
        except SessionExpired:
            await self._expire()
            raise
        await self._remember_token()
        return convert(items)

    async def _remember_token(self) -> None:
        """Stores a refreshed upstream token, and the fact we were here.

        Both writes are the same transaction and neither is worth failing a
        read over, so a failure here is logged and swallowed - the answer the
        caller asked for has already been fetched.
        """
        now = utcnow()
        changed = False
        if self.client.token and self.client.token != self._upstream:
            self._upstream = self.client.token
            self.row.upstream_token = seal(self.client.token)
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
            # touched next. The routes read `row.login` after calling in here,
            # so without this the failure this branch exists to absorb comes
            # back as a 500 from a line that only wanted a string.
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
