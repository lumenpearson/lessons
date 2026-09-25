"""«Сетевой город» behind the shared provider contract.

`NetSchoolProvider.sign_in` runs the password login and then the bootstrap that
every open client runs — the children (a staff-only account with no pupil is
refused here, not on the first screen later), the school year and its bounds,
and the assignment-type map — sealing them into the credential so later reads
cost fewer calls and a walk can be clipped to the year. `adopt` runs the same
bootstrap over a session the phone opened itself: no password is involved, and
``diary/init`` from this server's address is what proves the session opens here.

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
from typing import Any
from zoneinfo import ZoneInfo

from app.providers.diary.base import Adopted, AdoptRequest
from app.providers.diary.errors import NoStudents, SessionExpired, UnexpectedResponse
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
from app.providers.netschool.client import CREDENTIAL_VERSION, SESSION_COOKIES, NetSchoolClient
from app.providers.netschool.regions import Region
from app.providers.netschool.regions import get as region_for
from app.timezones import resolve

log = logging.getLogger(__name__)

#: The widest a single walk may run, matching the API's own MAX_RANGE_DAYS, so
#: a run is at most ~10 weekly calls.
MAX_WEEKS = 11


def _iso(day: Date) -> str:
    return day.isoformat()


def zone_of(region: Region) -> ZoneInfo:
    """The zone a region's diary cuts its days at: its administrative centre's.

    The one rule, read by `NetSchoolConnection.today` and — through
    `NetSchoolProvider.zone` — by what the phone is told at registration, so
    the phone's «today» and the server's never disagree.
    """
    return resolve(region.zone)


def _students_of(init: Any, school: str | None) -> list[Student]:
    """``diary/init``'s pupils, with any answer that is not the object it
    should be read as the diary answering strangely rather than as a crash."""
    if not isinstance(init, dict):
        raise UnexpectedResponse
    try:
        return m.to_students(init, school)
    except m.UnexpectedResponseError:
        raise UnexpectedResponse from None


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
        return _students_of(init, self._client.session.get("school"))

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
        return datetime.now(zone_of(self._region)).date()


class NetSchoolProvider:
    """The «Сетевой город. Образование» diary."""

    key = "netschool"
    title = "Сетевой город"
    genitive = "«Сетевого города»"
    site = ""

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
        await self.bootstrap(client)
        return client.credential

    async def adopt(self, request: AdoptRequest) -> Adopted:
        """Keep a session the phone opened, once this server has seen it work.

        The stored session is built from the fields this code knows and no
        others — ``at``, the two session cookies, and ``ver``/``time_out`` only
        when present, so nothing is ever stored as ``None`` — and then the
        bootstrap runs over it: ``diary/init`` is the call that proves the
        session opens this account from our address, and the other three fill
        what a sealed session cannot read without. Four sequential calls,
        because each may rotate a cookie the next must carry.

        A region outside the allow-list, one that takes no password, or no
        school is a ``ValueError``: the route refuses all three before calling.
        A credential that is not the shape the route builds is
        :class:`SessionExpired`, like any session this server cannot use.
        """
        region = region_for(request.region)
        if region is None or not region.password or request.school_id is None:
            raise ValueError("netschool adoption needs an allow-listed region and a school id")
        try:
            handed = json.loads(request.credential)
        except ValueError:
            raise SessionExpired from None
        if not isinstance(handed, dict):
            raise SessionExpired
        cookies = handed.get("cookies")
        cookies = cookies if isinstance(cookies, dict) else {}
        session: dict[str, Any] = {
            "v": CREDENTIAL_VERSION,
            "region": region.key,
            "school_id": request.school_id,
            "at": handed.get("at"),
            "cookies": {name: cookies[name] for name in SESSION_COOKIES if cookies.get(name)},
        }
        if handed.get("ver"):
            session["ver"] = handed["ver"]
        if handed.get("time_out"):
            session["time_out"] = handed["time_out"]
        # The client refuses an `at` or a cookie that cannot be sent whole
        # before the first request, so nothing unchecked reaches a header.
        client = NetSchoolClient(region, session)
        init = await self.bootstrap(client)
        school = client.session.get("school")
        return Adopted(
            credential=client.credential,
            students=tuple(_students_of(init, school)),
            school_name=school,
        )

    @staticmethod
    async def bootstrap(client: NetSchoolClient) -> dict[str, Any]:
        """After the session is accepted: the children, the year and the type
        map. A failure here is a real session's failure — SessionExpired-shaped
        — because the password (or the phone) was already judged.

        @return the ``diary/init`` answer, so a caller that wants the pupils
            does not ask for them twice.
        """
        init = await client.diary_init()
        if not _students_of(init, None):
            raise NoStudents
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
        return init

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

    def zone(self, region: str | None) -> str:
        """The region's zone; Moscow's for a key the allow-list does not know,
        which is `app.timezones.resolve`'s own fallback."""
        known = region_for(region)
        return zone_of(known).key if known is not None else resolve(None).key


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
