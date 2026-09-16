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

from app.api.public import MAX_BUNDLE_START, MIN_BUNDLE_START
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
    DiaryOverrideIn,
    DiaryOverrideOut,
    DiaryPeriodOut,
    DiaryResetIn,
    DiaryStudentOut,
    DiarySubjectOut,
    DiaryTeacherOut,
)
from app.services import diary as service
from app.services import diary_overrides as overrides

router = APIRouter(prefix="/api/v1/diary", tags=["diary"])

#: How wide a window one request may ask for. The upstream is asked for the
#: same span, and a year of lessons in one call is how an undocumented API
#: starts refusing to answer at all.
MAX_RANGE_DAYS = 62
DEFAULT_RANGE_DAYS = 14

#: The widest dates a request may name — literally `/bundle`'s own pair,
#: imported rather than repeated, because a second copy of a bound that must
#: agree with the first is a bound that eventually does not.
#:
#: They are needed here for the same reason: `from` is arbitrary client input
#: and the default window is `start + 14 days` on top of it, which within a
#: fortnight of `date.max` raises OverflowError — a 500 out of a query string,
#: where every other bad date on this surface is a 422 saying what was wrong.
MIN_DATE = MIN_BUNDLE_START
MAX_DATE = MAX_BUNDLE_START


def _range(date_from: Date | None, date_to: Date | None) -> tuple[Date, Date]:
    for day in (date_from, date_to):
        if day is not None and not (MIN_DATE <= day <= MAX_DATE):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"dates must be between {MIN_DATE.isoformat()} and {MAX_DATE.isoformat()}",
            )
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
    row: DiarySession = Depends(current_diary),
    session: AsyncSession = Depends(get_session),
) -> list[DiaryLessonOut]:
    student = await _student(svc, student_id)
    start, end = _range(date_from, date_to)
    lessons = await _guard(svc.schedule(student.education_id, start, end))
    corrections = await service.load_corrections(session, row.login, student_id)
    return [
        DiaryLessonOut.of(overlaid)
        for overlaid in overrides.overlay_lessons(lessons, corrections)
    ]


@router.get("/students/{student_id}/homework", response_model=list[DiaryHomeworkOut])
async def homework(
    student_id: int,
    date_from: Date | None = Query(default=None, alias="from"),
    date_to: Date | None = Query(default=None, alias="to"),
    svc: service.DiaryService = Depends(_service),
    row: DiarySession = Depends(current_diary),
    session: AsyncSession = Depends(get_session),
) -> list[DiaryHomeworkOut]:
    """Homework as its own resource.

    Upstream it is a field on a lesson; the client should not have to know
    that, so the provider pulls it out and this endpoint exists.
    """
    student = await _student(svc, student_id)
    start, end = _range(date_from, date_to)
    items = await _guard(svc.homework(student.education_id, start, end))
    corrections = await service.load_corrections(session, row.login, student_id)
    return [
        DiaryHomeworkOut.of(overlaid)
        for overlaid in overrides.overlay_homework(items, corrections)
    ]


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


# ---------------------------------------------------------------------------
# Corrections
# ---------------------------------------------------------------------------
#
# The only write surface the diary has beyond signing in and out, and it writes
# nothing upstream: a correction is stored here and laid over the answer on the
# way out. Marks and attendance are deliberately not correctable — see
# ``services/diary_overrides`` for why an app that let a family rewrite a grade
# would be producing a false record that looks official.
#
# Scoped by the upstream login rather than by the session, so signing out and
# back in finds the corrections where they were left. The student is checked
# against the account on every call for the same reason the read endpoints do
# it: an id from another family is otherwise a way to write into their diary.


@router.get("/students/{student_id}/overrides", response_model=list[DiaryOverrideOut])
async def list_overrides(
    student_id: int,
    svc: service.DiaryService = Depends(_service),
    row: DiarySession = Depends(current_diary),
    session: AsyncSession = Depends(get_session),
) -> list[DiaryOverrideOut]:
    """Every correction this account has made for this child."""
    await _student(svc, student_id)
    found = await service.list_overrides(session, row.login, student_id)
    return [DiaryOverrideOut.of(item) for item in found]


@router.put("/students/{student_id}/overrides", response_model=DiaryOverrideOut)
async def put_override(
    student_id: int,
    payload: DiaryOverrideIn,
    svc: service.DiaryService = Depends(_service),
    row: DiarySession = Depends(current_diary),
    session: AsyncSession = Depends(get_session),
) -> DiaryOverrideOut:
    """Writes or replaces one correction.

    The target is the string the read endpoints handed down; it is not parsed
    beyond the check that it names something correctable. A target this server
    would never produce is refused rather than stored: a row no read path can
    match would sit in the table looking like a correction somebody made, with
    no way to reset it, because the button that resets it only appears next to
    the value it changed.
    """
    await _student(svc, student_id)
    try:
        overrides.check(payload.target, payload.field)
    except overrides.UnknownTarget as failure:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Эту запись нельзя исправить",
        ) from failure
    except overrides.UnsupportedField as failure:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Это поле нельзя исправить",
        ) from failure
    try:
        overrides.check_value(payload.field, payload.value)
    except overrides.EmptyNotAllowed as failure:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Это поле не может быть пустым",
        ) from failure

    stored = await service.put_override(
        session,
        login=row.login,
        student_id=student_id,
        target=payload.target,
        field=payload.field,
        value=payload.value,
        original=payload.original,
    )
    return DiaryOverrideOut.of(stored)


@router.post(
    "/students/{student_id}/overrides/reset",
    status_code=status.HTTP_204_NO_CONTENT,
)
async def reset_override(
    student_id: int,
    payload: DiaryResetIn,
    svc: service.DiaryService = Depends(_service),
    row: DiarySession = Depends(current_diary),
    session: AsyncSession = Depends(get_session),
) -> None:
    """Resets one field back to what the diary says.

    A POST with a body rather than a DELETE with a query string: the target is
    free text and can carry an ampersand, and a reset that quietly matched
    nothing while answering 204 is worse than one that is awkward to spell.

    Idempotent, and answers 204 whether or not there was a row: "there is no
    correction here" is the state the caller asked for, and a 404 would make
    the client decide whether to show an error for having got what it wanted.
    """
    await _student(svc, student_id)
    await service.drop_override(
        session, row.login, student_id, payload.target, payload.field
    )


@router.delete(
    "/students/{student_id}/overrides/all",
    status_code=status.HTTP_204_NO_CONTENT,
)
async def reset_all_overrides(
    student_id: int,
    svc: service.DiaryService = Depends(_service),
    row: DiarySession = Depends(current_diary),
    session: AsyncSession = Depends(get_session),
) -> None:
    """Resets every correction for this child. The diary answers for itself again."""
    await _student(svc, student_id)
    await service.drop_overrides(session, row.login, student_id)
