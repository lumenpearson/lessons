"""The shape a diary provider meets, and the two ways a session reaches it.

Two protocols and three dataclasses. `services/diary.DiaryService` builds a
:class:`DiaryConnection` from the provider named on the session row and calls
it; it never knows which upstream is behind it. A provider raises only the
:mod:`app.providers.diary.errors` family from these methods — never a
``ValueError`` or a ``KeyError``, which would reach the API edge as a 500.

A session arrives one of two ways. :class:`SignInRequest` carries a password,
which this server sends upstream once (the bot's sign-in page, and
``POST /api/v1/diary/login`` for the apps that still call it).
:class:`AdoptRequest` carries a session the phone opened itself, straight with
the diary, so the password never came here at all; the provider checks it with
a read of its own and hands back :class:`Adopted`.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date as Date
from typing import Protocol, runtime_checkable

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


@dataclass(frozen=True)
class SignInRequest:
    """What a family types, plus which diary and where.

    ``region`` and ``school_id`` are how a provider that serves many regional
    servers, such as «Сетевой город», knows which one to talk to. Petersburg
    ignores both. ``password`` is ``repr=False`` so it never lands in a log
    line or a traceback that formats the request.
    """

    login: str
    password: str = field(repr=False)
    region: str | None = None
    school_id: int | None = None


@dataclass(frozen=True)
class AdoptRequest:
    """A session the phone opened itself, handed over once for this server to keep.

    ``credential`` is the provider's own serialisation of what the phone
    received — Petersburg's bare JWT, «Сетевой город»'s JSON of ``at``, the
    cookies, ``ver`` and ``timeOut`` — and ``repr=False`` for the same reason
    as a password: it opens the account.
    """

    credential: str = field(repr=False)
    region: str | None = None
    school_id: int | None = None


@dataclass(frozen=True)
class Adopted:
    """What a validated session comes to: the credential to seal, and what the
    validating read already fetched, so the caller does not ask again.

    ``credential`` may differ from the one handed in — the validating read can
    rotate it — and it is this one that is sealed. ``school_name`` is what the
    upstream calls the school, where it says; ``None`` otherwise.
    """

    credential: str = field(repr=False)
    students: tuple[Student, ...] = ()
    school_name: str | None = None


@runtime_checkable
class DiaryConnection(Protocol):
    """One signed-in session's reads.

    ``credential`` is the current serialised upstream session; the service
    re-seals it whenever it changes, so a token the upstream rotates mid-call
    is kept. Every method raises only a :mod:`app.providers.diary.errors`
    subclass.
    """

    credential: str

    async def students(self) -> list[Student]: ...
    async def periods(self, group_id: int) -> list[AcademicPeriod]: ...
    async def subjects(self, group_id: int, period_id: int) -> list[Subject]: ...
    async def teachers(self, education_id: int) -> list[Teacher]: ...
    async def schedule(
        self, education_id: int, date_from: Date, date_to: Date
    ) -> list[DiaryLesson]: ...
    async def homework(
        self, education_id: int, date_from: Date, date_to: Date
    ) -> list[HomeworkItem]: ...
    async def marks(
        self, education_id: int, date_from: Date, date_to: Date
    ) -> list[Mark]: ...
    async def attendance(self, education_id: int) -> list[AttendanceEvent]: ...

    async def keep_alive(self) -> None:
        """Reset the upstream's idle timer. A no-op where none is needed."""

    async def close(self) -> None:
        """Tell the upstream we are done, best effort. A no-op where it has no logout."""

    def today(self) -> Date:
        """The date it is in the diary's own zone."""


@runtime_checkable
class DiaryProvider(Protocol):
    """One diary platform.

    ``key`` is stored in ``classes.diary_provider`` and ``diary_sessions.provider``.
    ``title`` and ``genitive`` are what the bot and the web form print
    («Сетевой город» / «Сетевого города»).
    """

    key: str
    title: str
    genitive: str
    site: str

    async def sign_in(self, request: SignInRequest) -> str:
        """Sign in and return the serialised credential, which the caller seals."""

    async def adopt(self, request: AdoptRequest) -> Adopted:
        """Check a session the phone opened, from this server's address, and
        return what to seal. A session the upstream will not take from here
        is :class:`~app.providers.diary.errors.SessionExpired`; an account
        with no pupil is :class:`~app.providers.diary.errors.NoStudents`."""

    def open(self, credential: str) -> DiaryConnection:
        """A connection for a stored credential."""

    def zone(self, region: str | None) -> str:
        """The IANA zone this diary's days are cut at, for ``region``.

        The one rule: a connection's ``today()`` cuts the day with it, and the
        phone is told it at registration, so the two never disagree about
        which day it is in the diary."""
