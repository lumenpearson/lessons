"""The shape a diary provider meets, and the request that signs one in.

Two protocols and one dataclass. `services/diary.DiaryService` builds a
:class:`DiaryConnection` from the provider named on the session row and calls
it; it never knows which upstream is behind it. A provider raises only the
:mod:`app.providers.diary.errors` family from these methods — never a
``ValueError`` or a ``KeyError``, which would reach the API edge as a 500.
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

    def open(self, credential: str) -> DiaryConnection:
        """A connection for a stored credential."""
