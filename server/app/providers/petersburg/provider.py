"""Petersburg behind the shared provider contract.

Everything the diary does for Petersburg was in `services/diary.DiaryService`.
That knowledge moves here, unchanged, so the service can hold any provider and
Petersburg is just the first: the same calls, the same mapper functions, the
same «today is the city's clock» rule for periods.

``PetersburgConnection`` keeps its :class:`PetersburgClient` public, because a
test swaps a fake in through it. The credential is the client's session token,
which the client refreshes in place on most calls; the service re-seals it
whenever :attr:`credential` changes.
"""

from __future__ import annotations

from datetime import date as Date

from app.providers.diary.base import Adopted, AdoptRequest
from app.providers.diary.errors import NoStudents
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
from app.providers.petersburg import mapper as m
from app.providers.petersburg.client import TIMEZONE, PetersburgClient, today


class PetersburgConnection:
    """One Petersburg session's reads."""

    __slots__ = ("client",)

    def __init__(self, credential: str) -> None:
        self.client = PetersburgClient(credential or None)

    @property
    def credential(self) -> str:
        return self.client.token or ""

    async def students(self) -> list[Student]:
        return m.to_students(await self.client.children())

    async def periods(self, group_id: int) -> list[AcademicPeriod]:
        # The upstream's own clock, not the server's — the same reason the old
        # DiaryService.periods gave: this diary is one city's, that city keeps
        # Moscow time, and «current term» read on the server's UTC clock put an
        # evening pupil into yesterday and answered the subjects screen empty.
        return m.to_periods(await self.client.periods(group_id), today())

    async def subjects(self, group_id: int, period_id: int) -> list[Subject]:
        return m.to_subjects(await self.client.subjects(group_id, period_id))

    async def teachers(self, education_id: int) -> list[Teacher]:
        return m.to_teachers(await self.client.teachers(education_id))

    async def marks(self, education_id: int, date_from: Date, date_to: Date) -> list[Mark]:
        return m.to_marks(await self.client.marks(education_id, date_from, date_to))

    async def schedule(
        self, education_id: int, date_from: Date, date_to: Date
    ) -> list[DiaryLesson]:
        return m.to_lessons(await self.client.schedule(education_id, date_from, date_to))

    async def homework(
        self, education_id: int, date_from: Date, date_to: Date
    ) -> list[HomeworkItem]:
        return m.to_homework(await self.client.lessons(education_id, date_from, date_to))

    async def attendance(self, education_id: int) -> list[AttendanceEvent]:
        return m.to_attendance(await self.client.attendance(education_id))

    async def keep_alive(self) -> None:
        """Nothing to do: this session is not kept alive by us."""

    async def close(self) -> None:
        """The upstream has no logout that can be called without a browser."""

    def today(self) -> Date:
        return today()


class PetersburgProvider:
    """The «Петербургское образование» diary."""

    key = "petersburg"
    title = "Санкт-Петербург"
    genitive = "Санкт-Петербурга"
    site = "dnevnik2.petersburgedu.ru"

    async def sign_in(self, request) -> str:  # noqa: ANN001 - the SignInRequest protocol
        client = PetersburgClient()
        return await client.login(request.login.strip(), request.password)

    async def adopt(self, request: AdoptRequest) -> Adopted:
        """One read with the phone's token, from here: the pupils.

        The cheapest call that proves the session opens this account from our
        address, and the one the phone would make next anyway, so its answer
        rides back with the token. A 401, a 403 or a 200 of the login page is
        :class:`SessionExpired` through the client's own rules — the upstream
        did not take the session from here. The token the answer may have
        rotated is the one kept.
        """
        connection = PetersburgConnection(request.credential)
        students = await connection.students()
        if not students:
            raise NoStudents()
        return Adopted(credential=connection.credential, students=tuple(students))

    def open(self, credential: str) -> PetersburgConnection:
        return PetersburgConnection(credential)

    def zone(self, region: str | None) -> str:  # noqa: ARG002 - one city, one zone
        return TIMEZONE.key
