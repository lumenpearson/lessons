"""The diary, as this project's API.

Everything here is the public shape: dates are ISO, names are English, and
nothing carries an upstream field name, a cookie or a ``p_educations[]``. The
provider behind it can be rewritten without this file changing, which is the
arrangement the whole integration exists to produce.

The endpoints sit under ``/api/v1/diary`` rather than at the root because this
server already serves a class timetable at ``/api/v1``, filled by the bot, and
the two are different things: one is a class's shared plan, the other is one
family's account somewhere else. Keeping them apart in the path keeps them
apart in everybody's head.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import timedelta

from fastapi import APIRouter, Depends, Header, HTTPException, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.models import DiarySession
from app.providers.petersburg import (
    BadCredentials,
    PetersburgError,
    SessionExpired,
    UnexpectedResponse,
    UpstreamUnavailable,
)
from app.providers.petersburg import (
    today as diary_today,
)
from app.schemas import (
    DiaryAttendanceOut,
    DiaryHomeworkOut,
    DiaryLessonOut,
    DiaryLoginIn,
    DiaryLoginOut,
    DiaryMarkOut,
    DiaryPeriodOut,
    DiaryStudentOut,
    DiarySubjectOut,
    DiaryTeacherOut,
)
from app.services import diary as service

router = APIRouter(prefix="/api/v1/diary", tags=["diary"])

#: How wide a window one request may ask for. The upstream is asked for the
#: same span, and a year of lessons in one call is how an undocumented API
#: starts refusing to answer at all.
MAX_RANGE_DAYS = 62
DEFAULT_RANGE_DAYS = 14


def _range(date_from: Date | None, date_to: Date | None) -> tuple[Date, Date]:
    # The diary's own day, not the server's: see `petersburg.TIMEZONE`.
    start = date_from or diary_today()
    end = date_to or start + timedelta(days=DEFAULT_RANGE_DAYS)
    if end < start:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="`to` is before `from`",
        )
    if (end - start).days > MAX_RANGE_DAYS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"the range must be at most {MAX_RANGE_DAYS} days",
        )
    return start, end


async def current_diary(
    authorization: str = Header(default=""),
    session: AsyncSession = Depends(get_session),
) -> DiarySession:
    """The signed-in diary session, or 401.

    A separate bearer from the device token on purpose: a phone can be joined
    to a class without a diary account, and signed in to a diary without
    joining a class. Neither implies the other, and one token that meant both
    would have to be re-minted whenever either half changed.
    """
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    row = await service.find_session(session, token)
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Diary session is not valid",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return row


def _service(
    row: DiarySession = Depends(current_diary),
    session: AsyncSession = Depends(get_session),
) -> service.DiaryService:
    return service.DiaryService(session, row)


async def _guard(awaitable):
    """Turns a provider failure into the status code that fits it.

    Four different things can go wrong and the client does four different
    things about them, so they are four status codes rather than one 502 with
    a message to parse.
    """
    try:
        return await awaitable
    except BadCredentials as failure:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail=failure.message
        ) from failure
    except SessionExpired as failure:
        # 401 with a header the client can switch on: this one means "ask for
        # the password again", not "your token is wrong".
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=failure.message,
            headers={"X-Diary-Reauth": "required"},
        ) from failure
    except UpstreamUnavailable as failure:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=failure.message
        ) from failure
    except UnexpectedResponse as failure:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY, detail=failure.message
        ) from failure
    except PetersburgError as failure:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY, detail=failure.message
        ) from failure


# ---------------------------------------------------------------------------
# Session
# ---------------------------------------------------------------------------


@router.post("/login", response_model=DiaryLoginOut)
async def login(
    payload: DiaryLoginIn,
    session: AsyncSession = Depends(get_session),
) -> DiaryLoginOut:
    """Signs in to the diary and opens a session of ours.

    The password is used for this one call and is never stored. When the
    upstream session eventually expires, requests answer 401 with
    ``X-Diary-Reauth: required`` and the app asks for it again.
    """
    token, row = await _guard(service.sign_in(session, payload.login, payload.password))
    return DiaryLoginOut(token=token, login=row.login)


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(
    row: DiarySession = Depends(current_diary),
    session: AsyncSession = Depends(get_session),
) -> None:
    await service.sign_out(session, row)


@router.get("/students", response_model=list[DiaryStudentOut])
async def students(svc: service.DiaryService = Depends(_service)) -> list[DiaryStudentOut]:
    """Every pupil this account may see. One for most parents."""
    found = await _guard(svc.students())
    return [DiaryStudentOut.of(student) for student in found]


# ---------------------------------------------------------------------------
# One student
# ---------------------------------------------------------------------------


async def _student(svc: service.DiaryService, student_id: int):
    """The student, or 404 - resolved from the account rather than trusted.

    The path carries an id and the id is not a secret, so it is looked up among
    the pupils this session may actually see. Without that, one account's id in
    another account's request would be a way to read somebody else's child.
    """
    for student in await _guard(svc.students()):
        if student.id == student_id:
            return student
    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown student")


@router.get("/students/{student_id}/schedule", response_model=list[DiaryLessonOut])
async def schedule(
    student_id: int,
    date_from: Date | None = Query(default=None, alias="from"),
    date_to: Date | None = Query(default=None, alias="to"),
    svc: service.DiaryService = Depends(_service),
) -> list[DiaryLessonOut]:
    student = await _student(svc, student_id)
    start, end = _range(date_from, date_to)
    lessons = await _guard(svc.schedule(student.education_id, start, end))
    return [DiaryLessonOut.of(lesson) for lesson in lessons]


@router.get("/students/{student_id}/homework", response_model=list[DiaryHomeworkOut])
async def homework(
    student_id: int,
    date_from: Date | None = Query(default=None, alias="from"),
    date_to: Date | None = Query(default=None, alias="to"),
    svc: service.DiaryService = Depends(_service),
) -> list[DiaryHomeworkOut]:
    """Homework as its own resource.

    Upstream it is a field on a lesson; the client should not have to know
    that, so the provider pulls it out and this endpoint exists.
    """
    student = await _student(svc, student_id)
    start, end = _range(date_from, date_to)
    items = await _guard(svc.homework(student.education_id, start, end))
    return [DiaryHomeworkOut.of(item) for item in items]


@router.get("/students/{student_id}/grades", response_model=list[DiaryMarkOut])
async def grades(
    student_id: int,
    date_from: Date | None = Query(default=None, alias="from"),
    date_to: Date | None = Query(default=None, alias="to"),
    svc: service.DiaryService = Depends(_service),
) -> list[DiaryMarkOut]:
    student = await _student(svc, student_id)
    start, end = _range(date_from, date_to)
    marks = await _guard(svc.marks(student.education_id, start, end))
    return [DiaryMarkOut.of(mark) for mark in marks]


@router.get("/students/{student_id}/periods", response_model=list[DiaryPeriodOut])
async def periods(
    student_id: int,
    svc: service.DiaryService = Depends(_service),
) -> list[DiaryPeriodOut]:
    student = await _student(svc, student_id)
    if student.group_id is None:
        return []
    found = await _guard(svc.periods(student.group_id))
    return [DiaryPeriodOut.of(period) for period in found]


@router.get("/students/{student_id}/subjects", response_model=list[DiarySubjectOut])
async def subjects(
    student_id: int,
    period_id: int | None = Query(default=None),
    svc: service.DiaryService = Depends(_service),
) -> list[DiarySubjectOut]:
    """Subjects studied in a period; the current one when none is named."""
    student = await _student(svc, student_id)
    if student.group_id is None:
        return []
    chosen = period_id
    if chosen is None:
        found = await _guard(svc.periods(student.group_id))
        current = next((period for period in found if period.is_current), None)
        if current is None:
            return []
        chosen = current.id
    items = await _guard(svc.subjects(student.group_id, chosen))
    return [DiarySubjectOut.of(item) for item in items]


@router.get("/students/{student_id}/teachers", response_model=list[DiaryTeacherOut])
async def teachers(
    student_id: int,
    svc: service.DiaryService = Depends(_service),
) -> list[DiaryTeacherOut]:
    student = await _student(svc, student_id)
    found = await _guard(svc.teachers(student.education_id))
    return [DiaryTeacherOut.of(teacher) for teacher in found]


@router.get("/students/{student_id}/attendance", response_model=list[DiaryAttendanceOut])
async def attendance(
    student_id: int,
    svc: service.DiaryService = Depends(_service),
) -> list[DiaryAttendanceOut]:
    """Turnstile records, newest first."""
    student = await _student(svc, student_id)
    events = await _guard(svc.attendance(student.education_id))
    return [DiaryAttendanceOut.of(event) for event in events]
