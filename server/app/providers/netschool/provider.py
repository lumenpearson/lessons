"""«Сетевой город» behind the shared provider contract.

`NetSchoolProvider.sign_in` runs the password login and then the bootstrap that
every open client runs — the children (a staff-only account with no pupil is
refused here, not on the first screen later), the school year and its bounds,
and the assignment-type map — sealing them into the credential so later reads
cost fewer calls and a walk can be clipped to the year.

`NetSchoolConnection` walks the diary a week at a time, because the route is a
week at a time in every client, and clips each walk to the school year's bounds
so a session kept alive across 1 September does not ask for a year that has
ended. A credential that will not parse makes every call
:class:`SessionExpired`, so a key rotation expires the row rather than 500ing.
"""

from __future__ import annotations

import json
import logging
from datetime import date as Date
from datetime import datetime, timedelta

from app.providers.diary.errors import SessionExpired, UnexpectedResponse
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
from app.providers.netschool import mapper as m
from app.providers.netschool.client import CREDENTIAL_VERSION, NetSchoolClient
from app.providers.netschool.regions import Region
from app.providers.netschool.regions import get as region_for
from app.timezones import resolve

log = logging.getLogger(__name__)

#: The widest a single walk may run, matching the API's own MAX_RANGE_DAYS, so
#: a run is at most ~10 weekly calls.
MAX_WEEKS = 11


def _iso(day: Date) -> str:
    return day.isoformat()


def _weeks(date_from: Date, date_to: Date, year_start: Date | None, year_end: Date | None):
    """(weekStart, weekEnd) Monday–Sunday spans covering [from, to], clipped to
    the school year. A week wholly outside the year yields nothing, so no call
    is made for it."""
    lo = max(date_from, year_start) if year_start else date_from
    hi = min(date_to, year_end) if year_end else date_to
    cursor = lo - timedelta(days=lo.weekday())  # Monday of the first week
    count = 0
    while cursor <= hi and count < MAX_WEEKS:
        week_start = max(cursor, lo)
        week_end = min(cursor + timedelta(days=6), hi)
        if week_start <= week_end:
            yield week_start, week_end
        cursor += timedelta(days=7)
        count += 1


class NetSchoolConnection:
    __slots__ = ("_client", "_region")

    def __init__(self, region: Region, session: dict) -> None:
        self._region = region
        self._client = NetSchoolClient(region, session)

    @property
    def credential(self) -> str:
        return self._client.credential

    def _year(self) -> tuple[int | None, Date | None, Date | None]:
        s = self._client.session
        return (
            s.get("year_id"),
            m._parse_date(s.get("year_start")),
            m._parse_date(s.get("year_end")),
        )

    async def students(self) -> list[Student]:
        init = await self._client.diary_init()
        try:
            return m.to_students(init, self._client.session.get("school"))
        except m.UnexpectedResponseError:
            raise UnexpectedResponse from None

    async def periods(self, group_id: int) -> list[AcademicPeriod]:
        terms = await self._client.terms_search(group_id)
        return m.to_periods(terms, self.today())

    async def subjects(self, group_id: int, period_id: int) -> list[Subject]:  # noqa: ARG002
        return []

    async def teachers(self, education_id: int) -> list[Teacher]:  # noqa: ARG002
        return []

    async def attendance(self, education_id: int) -> list[AttendanceEvent]:  # noqa: ARG002
        return m.to_attendance()

    async def _walk(self, education_id: int, date_from: Date, date_to: Date):
        year_id, year_start, year_end = self._year()
        if year_id is None:
            raise SessionExpired  # no year handle sealed → the session is unusable
        for week_start, week_end in _weeks(date_from, date_to, year_start, year_end):
            yield week_start, week_end, await self._client.diary_week(
                education_id, year_id, _iso(week_start), _iso(week_end)
            )

    async def schedule(
        self, education_id: int, date_from: Date, date_to: Date
    ) -> list[DiaryLesson]:
        out: list[DiaryLesson] = []
        async for ws, we, diary in self._walk(education_id, date_from, date_to):
            out.extend(m.to_lessons(diary, ws, we))
        return out

    async def homework(
        self, education_id: int, date_from: Date, date_to: Date
    ) -> list[HomeworkItem]:
        out: list[HomeworkItem] = []
        async for ws, we, diary in self._walk(education_id, date_from, date_to):
            out.extend(m.to_homework(diary, ws, we))
        return out

    async def marks(self, education_id: int, date_from: Date, date_to: Date) -> list[Mark]:
        types = self._client.session.get("types") or {}
        out: list[Mark] = []
        async for ws, we, diary in self._walk(education_id, date_from, date_to):
            out.extend(m.to_marks(diary, types, ws, we))
        return out

    async def keep_alive(self) -> None:
        await self._client.keep_alive()

    async def close(self) -> None:
        await self._client.logout()

    def today(self) -> Date:
        return datetime.now(resolve(self._region.zone)).date()


class NetSchoolProvider:
    """The «Сетевой город. Образование» diary."""

    key = "netschool"
    title = "Сетевой город"
    genitive = "«Сетевого города»"

    async def sign_in(self, request) -> str:  # noqa: ANN001 - the SignInRequest protocol
        region = region_for(request.region)
        if region is None or not region.password or request.school_id is None:
            # A caller reached sign-in with a region the allow-list does not
            # serve, or without a school. Callers validate first, so this is a
            # programming error rather than a user's — a ValueError, not a
            # DiaryError.
            raise ValueError("netschool sign-in needs an allow-listed region and a school id")
        client = NetSchoolClient(region, {"v": CREDENTIAL_VERSION, "region": region.key,
                                          "school_id": request.school_id})
        await client.login(request.login.strip(), request.password, request.school_id)
        await self._bootstrap(client)
        return client.credential

    @staticmethod
    async def _bootstrap(client: NetSchoolClient) -> None:
        """After the password is accepted: the children, the year and the type
        map. A failure here is a real session's failure — SessionExpired-shaped
        — because the password was already judged."""
        init = await client.diary_init()
        try:
            students = m.to_students(init, None)
        except m.UnexpectedResponseError:
            raise UnexpectedResponse from None
        if not students:
            raise SessionExpired("В этой учётной записи нет ученика")
        year = await client.years_current()
        if isinstance(year, dict):
            client.session["year_id"] = year.get("id")
            client.session["year_start"] = _year_date(year.get("startDate"))
            client.session["year_end"] = _year_date(year.get("endDate"))
        ctx = await client.context()
        if isinstance(ctx, dict):
            org = ctx.get("organization")
            name = ctx.get("organizationName") or (
                org.get("name") if isinstance(org, dict) else None
            )
            if name:
                client.session["school"] = str(name)[:300]
        types = await client.assignment_types()
        if isinstance(types, list):
            client.session["types"] = {
                str(t.get("id")): str(t.get("name"))
                for t in types
                if isinstance(t, dict) and t.get("id") is not None
            }

    def open(self, credential: str) -> NetSchoolConnection:
        try:
            session = json.loads(credential)
            region = region_for(session.get("region"))
            if region is None:
                raise ValueError("unknown region")
        except (ValueError, TypeError):
            # A credential that will not parse, or names a region no longer
            # served, is a dead session: every call raises SessionExpired, and
            # the row is expired on lookup rather than 500ing.
            return _DeadConnection()  # type: ignore[return-value]
        return NetSchoolConnection(region, session)


def _year_date(raw) -> str | None:  # noqa: ANN001
    parsed = m._parse_date(raw)
    return parsed.isoformat() if parsed else None


class _DeadConnection:
    """A connection for an unreadable credential: everything is SessionExpired."""

    credential = ""

    def today(self) -> Date:
        return datetime.now().date()

    async def _dead(self, *a, **k):  # noqa: ANN002, ANN003, ARG002
        raise SessionExpired

    async def close(self) -> None:
        """Nothing to log out of: the credential never opened."""

    students = periods = subjects = teachers = attendance = _dead
    schedule = homework = marks = keep_alive = _dead
