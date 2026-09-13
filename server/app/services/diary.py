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
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto import diary_enabled, seal, unseal
from app.models import DiarySession
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


def _utcnow() -> datetime:
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
        last_used_at=_utcnow(),
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
        row.expired_at = _utcnow()
        await session.commit()
        return None
    return row


async def sign_out(session: AsyncSession, row: DiarySession) -> None:
    """Forgets the session. The upstream is not told: it has no logout that
    can be called without a browser, and its token expires on its own."""
    await session.delete(row)
    await session.commit()


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
        now = _utcnow()
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

    async def _expire(self) -> None:
        """Marks the session dead so the next request fails fast, with the
        answer that actually helps: sign in again."""
        self.row.expired_at = _utcnow()
        try:
            await self.session.commit()
        except Exception:  # noqa: BLE001
            log.warning("could not mark the diary session expired", exc_info=True)
            await self.session.rollback()
