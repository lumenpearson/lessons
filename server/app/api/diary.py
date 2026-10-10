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

A session of ours is opened two ways. ``POST /session`` registers one the
phone opened itself, straight with the diary, so the password never reaches
this server; that is what the app does. ``POST /login`` takes the password and
signs in from here, and stays exactly as it was for the apps that still call
it. Every 503 either answers carries ``X-Diary-Unavailable`` — ``disabled``,
``address-refused`` or ``upstream`` — so the app can tell «выключен на
сервере» from «дневник не отвечает» without reading the Russian.
"""

from __future__ import annotations

from collections.abc import Callable, Coroutine
from datetime import date as Date
from typing import Annotated, Any

from dishka.integrations.fastapi import FromDishka, inject
from fastapi import APIRouter, Body, Depends, Header, HTTPException, Query, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import Response
from sqlalchemy.ext.asyncio import AsyncSession

from app import wording
from app.api.deps import request_bucket
from app.api.routing import DishkaAnnotatedRoute
from app.crypto import diary_enabled
from app.models import DiarySession
from app.providers.diary.errors import (
    AddressRefused,
    BadCredentials,
    DiaryError,
    NoStudents,
    SessionExpired,
    SignInUnsupported,
    UnexpectedResponse,
    UpstreamUnavailable,
)
from app.providers.diary.models import Student
from app.providers.petersburg import (
    today as diary_today,
)
from app.schemas import (
    DiaryAttendanceOut,
    DiaryCapabilitiesOut,
    DiaryHomeworkOut,
    DiaryLessonOut,
    DiaryLoginIn,
    DiaryLoginOut,
    DiaryMarkOut,
    DiaryOverrideIn,
    DiaryOverrideOut,
    DiaryPeriodOut,
    DiaryProvidersOut,
    DiaryResetIn,
    DiarySessionBody,
    DiarySessionOut,
    DiaryStudentOut,
    DiarySubjectOut,
    DiaryTeacherOut,
    NetSchoolCapabilitiesOut,
    NetSchoolSessionIn,
)
from app.security import DIARY_FAILURES_BUCKET, DIARY_OPENED_BUCKET, DiaryAttempt, Throttled
from app.security import diary_login_limiter as diary_login_limiter
from app.security import diary_open_limiter as diary_open_limiter
from app.services import clock, diary_corrections
from app.services import diary as service
from app.services import diary_overrides as overrides

router = APIRouter(route_class=DishkaAnnotatedRoute, prefix="/api/v1/diary", tags=["diary"])

#: How wide a window one request may ask for, and how wide it is when no end
#: is named: `services/diary`'s, which v2's reads answer too.
MAX_RANGE_DAYS = service.WINDOW_MAX_DAYS
DEFAULT_RANGE_DAYS = service.WINDOW_DAYS

#: v1's words for each refusal of `services/diary.window`, in its own field
#: names, `from` and `to`. The out-of-bounds sentence is `clock`'s own, so it
#: lives once rather than being re-spelled here from `clock.MIN_DATE` and
#: `clock.MAX_DATE`.
_RANGE_REFUSED = {
    clock.OUT_OF_BOUNDS: clock.DATES_OUT_OF_BOUNDS,
    clock.BACKWARDS: "`to` is before `from`",
    clock.TOO_WIDE: f"the range must be at most {MAX_RANGE_DAYS} days",
}


def _range(
    date_from: Date | None, date_to: Date | None, today: Date | None = None
) -> tuple[Date, Date]:
    # The diary's own day, not the server's — the provider's, so «Сетевой
    # город» gets its region's zone and Petersburg its city's. The default is
    # Petersburg's when a caller passes no `today`, which keeps the standalone
    # `_range(None, None)` behaviour its test pins.
    try:
        return service.window(date_from, date_to, today or diary_today())
    except clock.WindowRefused as refusal:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=_RANGE_REFUSED[refusal.why]
        ) from None


@inject
async def current_diary(
    authorization: str = Header(default=""),
    *,
    session: FromDishka[AsyncSession],
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
    # With no key the diary is off, and «off» is the answer — the one `/login`
    # and `/session` give. A 401 here would sign the family out over a
    # misconfiguration that putting the key back undoes (#302).
    if not diary_enabled():
        raise _disabled()
    row = await service.find_session(session, token)
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Diary session is not valid",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return row


@inject
def _service(
    row: DiarySession = Depends(current_diary),
    *,
    session: FromDishka[AsyncSession],
) -> service.DiaryService:
    return service.DiaryService(session, row)


#: Says which of three things a diary 503 is, because the app does something
#: different about each and the ``detail`` is Russian prose it should not
#: parse: ``disabled`` (no ``DIARY_SECRET`` — nothing anybody types will help),
#: ``address-refused`` (the region drops this server's address — not the
#: password, not worth retrying) and ``upstream`` (the diary is down or will
#: not take this way in — try later).
UNAVAILABLE_HEADER = "X-Diary-Unavailable"

#: The detail of the 503 a deployment without ``DIARY_SECRET`` answers, in the
#: words v2's ``DIARY_DISABLED`` uses too (``app/wording.py``).
DISABLED_DETAIL = wording.DIARY_DISABLED_DETAIL


def _unavailable(failure: UpstreamUnavailable | SignInUnsupported) -> HTTPException:
    """The 503 for an upstream that did not let us in, with its reason named.

    ``SignInUnsupported`` is ``upstream`` too: it is the region's own answer
    about who it lets in, and it comes only from the password sign-in.
    """
    reason = "address-refused" if isinstance(failure, AddressRefused) else "upstream"
    return HTTPException(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        detail=failure.message,
        headers={UNAVAILABLE_HEADER: reason},
    )


def _disabled() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        detail=DISABLED_DETAIL,
        headers={UNAVAILABLE_HEADER: "disabled"},
    )


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
    except SignInUnsupported as failure:
        # The region takes only Госуслуги. A 503 rather than a 401, because it
        # is not the password and there is nothing to retry — and so that the
        # login limiter, which forgives only a 503, does not count an attempt
        # where nothing looked at a password.
        raise _unavailable(failure) from failure
    except UpstreamUnavailable as failure:
        # AddressRefused is a subclass and lands here too: a 503, uncounted,
        # carrying its own «дело не в пароле» message and its own header value.
        raise _unavailable(failure) from failure
    except UnexpectedResponse as failure:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY, detail=failure.message
        ) from failure
    except DiaryError as failure:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY, detail=failure.message
        ) from failure


# ---------------------------------------------------------------------------
# Session
# ---------------------------------------------------------------------------


#: The two diary limiters and the attempt they count are `app.security`'s:
#: v2's `CreateDiarySession` (3b) counts on the same rows, so a caller cannot
#: double its guesses by alternating versions (the server-v2 design, decision
#: 11). Imported above under their own names, which the tests read here.

_THROTTLED_DETAIL = wording.DIARY_THROTTLED_DETAIL


def _buckets(request: Request) -> dict[str, str]:
    """The caller's two diary buckets, as `DiaryAttempt.admit` takes them."""
    return {
        "failures_key": request_bucket(request, scope=DIARY_FAILURES_BUCKET),
        "opened_key": request_bucket(request, scope=DIARY_OPENED_BUCKET),
    }


async def _admit(session: AsyncSession, request: Request) -> DiaryAttempt:
    """Count the attempt on both diary limiters, or 429 while either is spent."""
    try:
        return await DiaryAttempt.admit(session, **_buckets(request))
    except Throttled as refusal:
        raise _throttled(refusal.retry_after) from None


def _throttled(retry_after: float) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_429_TOO_MANY_REQUESTS,
        detail=_THROTTLED_DETAIL,
        headers={"Retry-After": str(int(retry_after) + 1)},
    )


def _resolve_login_target(payload: DiaryLoginIn) -> service.Target:
    """The provider, region and school to sign in with, validated locally by
    `services/diary.target`: a 422 that nothing upstream saw, for a provider no
    row answers, a region this server does not serve with a password, or a
    «Сетевой город» sign-in without a school."""
    try:
        return service.target(payload.provider, payload.region, payload.school_id)
    except service.UnknownProvider as failure:
        detail = f"Unknown diary provider {failure.key!r}"
    except service.RegionNotServed:
        detail = wording.DIARY_REGION_NOT_SERVED_DETAIL
    except service.SchoolRequired:
        detail = "A school id is required for «Сетевой город»"
    raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=detail)


async def login(
    request: Request,
    payload: DiaryLoginIn,
    *,
    session: FromDishka[AsyncSession],
) -> DiaryLoginOut:
    """Signs in to the diary and opens a session of ours.

    The password is used for this one call and is never stored. When the
    upstream session eventually expires, requests answer 401 with
    ``X-Diary-Reauth: required`` and the app asks for it again.
    """
    # The buckets are `_admit`'s, asked for through `deps.caller_bucket`
    # rather than built by putting «diary:» in front of what it returns. The
    # counter's column is `VARCHAR(64)` and the digest already fills it, so the
    # prefix made every recorded failure six characters too long for Postgres:
    # the insert raised out of the `except` that was recording it, so a wrong
    # password answered 500 instead of 401 and nothing was ever counted — the
    # limit existed only in the tests, where SQLite does not enforce a width.
    #
    # The target is checked before the attempt is counted: a region we do not serve is a 422
    # that nothing upstream saw, and counting it would charge the caller for
    # our refusal.
    where = _resolve_login_target(payload)
    attempt = await _admit(session, request)

    try:
        token, row = await _guard(
            service.sign_in(
                session,
                payload.login,
                payload.password,
                provider=where.provider,
                region=where.region,
                school_id=where.school_id,
            )
        )
    except service.DiaryDisabled as failure:
        # No ``DIARY_SECRET``, so the feature is off — see ``app/crypto.py``
        # for why that is a refusal rather than a fallback. ``_guard`` knows
        # the *upstream's* failures and nothing else, so this one went out of
        # the handler: a bare 500 on an unauthenticated endpoint, a stack trace
        # per attempt, and nothing for the app to put on the screen. The other
        # door onto the same service, ``POST /diary/signin``, has always
        # answered 503 and said so in words; this is that answer.
        await attempt.not_judged(session)
        raise _disabled() from failure
    except HTTPException as refusal:
        # Everything but a 503 counts, and the line is drawn where the other
        # door onto this upstream draws it.
        #
        # It used to count a 401 alone, on the reasoning that «a 502 says
        # nothing about the password». That is only true of the 502s where
        # nothing ever looked at one — and `UnexpectedResponse`, the commonest
        # of them, is not that: `PetersburgClient.login` raises it for a 200
        # whose body is not JSON, and a login form built on Yii answers a
        # *wrong password* with the same 200 of HTML that a captcha arrives in.
        # `diary_web` worked this out first and spends its ticket on exactly
        # that branch, at length, in its own comment. So while the upstream is
        # serving HTML — which is precisely when it is defending itself — this
        # endpoint answered 502 to every attempt and recorded none, leaving the
        # unlimited password oracle the limiter below exists to prevent.
        #
        # 503 is the one that is safe to forgive, and it is safe for the reason
        # `diary_web` names: it means the feature is off, or the transport
        # failed, or the upstream answered 5xx — nothing read what was typed.
        if refusal.status_code == status.HTTP_503_SERVICE_UNAVAILABLE:
            await attempt.not_judged(session)
        else:
            await attempt.failed(session)
        raise
    # Commits the new session with the attempt's outcome, on purpose
    # (`security.DiaryAttempt`): `sign_in` leaves its row to its caller.
    await attempt.succeeded(session)
    return DiaryLoginOut(token=token, login=row.login)


# ---------------------------------------------------------------------------
# A session the phone opened
# ---------------------------------------------------------------------------


#: The detail of a registration the upstream refused from this server, in the
#: words v2's ``DIARY_CREDENTIALS_REJECTED`` uses too (``app/wording.py``).
REFUSED_FROM_HERE_DETAIL = wording.DIARY_SESSION_REFUSED_DETAIL


class _NoEchoRoute(DishkaAnnotatedRoute):
    """A route whose 422 names what was wrong and never repeats what was sent.

    FastAPI's validation answer carries each refused value back as ``input``.
    On ``/session`` that value is an upstream session — a cookie that failed
    the charset check, a token that is not a JWT — or, for a client that sent
    one, a password; on ``/login`` it is the password itself, refused for its
    length (#390). A session or a password goes to this server once and is
    never echoed; the refusal is no exception to that.
    """

    def get_route_handler(self) -> Callable[[Request], Coroutine[Any, Any, Response]]:
        handler = super().get_route_handler()

        async def without_echo(request: Request) -> Response:
            try:
                return await handler(request)
            except RequestValidationError as refusal:
                raise RequestValidationError(
                    [
                        {key: value for key, value in error.items() if key != "input"}
                        for error in refusal.errors()
                    ]
                ) from None

        return without_echo


# Added by hand rather than by decorator only to give it the route class that
# keeps a refused password out of the 422 (#390).
router.add_api_route(
    "/login",
    login,
    methods=["POST"],
    response_model=DiaryLoginOut,
    route_class_override=_NoEchoRoute,
)


@router.get("/capabilities", response_model=DiaryCapabilitiesOut)
async def capabilities() -> DiaryCapabilitiesOut:
    """What this server's diary can do, before the phone takes a password.

    Anonymous and database-free, so it is cheap to ask on every sign-in
    screen: whether the diary runs here at all, and which «Сетевой город»
    regions this server signs in to — a region missing from the list is one
    the phone should not send a password to, since the session it opened could
    not be kept. A server from before registration answers 404 here.
    """
    from app.providers.netschool import regions

    return DiaryCapabilitiesOut(
        enabled=diary_enabled(),
        providers=DiaryProvidersOut(
            netschool=NetSchoolCapabilitiesOut(regions=[r.key for r in regions.listed()]),
        ),
    )


async def register_session(
    request: Request,
    payload: Annotated[DiarySessionBody, Body(discriminator="provider")],
    *,
    session: FromDishka[AsyncSession],
) -> DiarySessionOut:
    """Keep a session the phone opened itself, and hand back a token of ours.

    The phone signed in with the diary directly, so the password never came
    here; what arrives is the session the diary gave it. It is read with once,
    from this server's address — that read is the check — then sealed, and the
    phone forgets its copy. Nothing here stores or accepts a password: a
    ``password`` key anywhere in the body is a 422.

    Shares ``/login``'s limiters **and buckets**, so a caller who spent ten
    wrong passwords does not get ten more tries by session. Counted as a
    failure: a session the upstream refused (409), an account with no pupil
    (403), an answer nobody can read (502). Counted as a session opened: a 200,
    twenty to a caller in fifteen minutes across both doors. Not counted:
    anything that never reached the upstream (422), the feature being off, or
    the upstream not answering (503).

    409 rather than ``/login``'s 401 + ``X-Diary-Reauth``, because asking for
    the password again would loop: the session was good on the phone seconds
    ago, and it is *this server* the diary will not take it from.

    The rules, the order and the counting are `services/diary.register`'s,
    which v2's ``CreateDiarySession`` calls too; this words its facts.
    """
    try:
        registered = await service.register(
            session,
            provider=payload.provider,
            login=payload.login,
            handed=payload.credential.model_dump(exclude_none=True),
            region=payload.region if isinstance(payload, NetSchoolSessionIn) else None,
            school_id=payload.school_id if isinstance(payload, NetSchoolSessionIn) else None,
            **_buckets(request),
        )
    except service.RegionNotServed:
        # Before anything was counted or sent: a 422 that nothing upstream saw.
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=wording.DIARY_REGION_NOT_SERVED_DETAIL,
        ) from None
    except Throttled as refusal:
        raise _throttled(refusal.retry_after) from None
    except service.DiaryDisabled as failure:
        raise _disabled() from failure
    except UpstreamUnavailable as failure:
        # Nothing judged the session: the diary did not answer, or will not
        # talk to this address at all. Forgiven, like /login's 503.
        raise _unavailable(failure) from failure
    except NoStudents as failure:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=failure.message
        ) from failure
    except service.SessionRefused as failure:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=REFUSED_FROM_HERE_DETAIL
        ) from failure
    except DiaryError as failure:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY, detail=failure.message
        ) from failure

    return DiarySessionOut(
        token=registered.token,
        login=registered.row.login,
        provider=payload.provider,
        region=registered.row.region,
        school_id=registered.school_id,
        school_name=registered.school_name,
        zone=registered.zone,
        students=[DiaryStudentOut.of(student) for student in registered.students],
    )


# Added by hand rather than by decorator only to give it the route class that
# keeps a refused session out of the 422.
router.add_api_route(
    "/session",
    register_session,
    methods=["POST"],
    response_model=DiarySessionOut,
    route_class_override=_NoEchoRoute,
)


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(
    row: DiarySession = Depends(current_diary),
    *,
    session: FromDishka[AsyncSession],
) -> None:
    await service.sign_out(session, row)
    await session.commit()


@router.get("/students", response_model=list[DiaryStudentOut])
async def students(svc: service.DiaryService = Depends(_service)) -> list[DiaryStudentOut]:
    """Every pupil this account may see. One for most parents."""
    found = await _guard(svc.students())
    return [DiaryStudentOut.of(student) for student in found]


# ---------------------------------------------------------------------------
# One student
# ---------------------------------------------------------------------------


async def _student(svc: service.DiaryService, student_id: int) -> Student:
    """The student, or 404 - resolved from the account rather than trusted
    (`DiaryService.student`, which v2's reads ask too)."""
    try:
        return await _guard(svc.student(student_id))
    except service.UnknownStudent:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=wording.UNKNOWN_STUDENT_DETAIL
        ) from None


async def _child(svc: service.DiaryService, student_id: int) -> tuple[Student, str | None]:
    """The student, or 404, and the scope its corrections are filed under.

    The one door every correction read and write goes through, so that the
    404 comes before any row is touched and the scope comes from the session's
    diary — never from the login it was registered under (#165). ``None`` is a
    child that can have no corrections (`DiaryService.scope_of`): the reads lay
    none over it, the list is empty, a reset has nothing to take off, and a
    write is refused.
    """
    try:
        return await _guard(svc.child(student_id))
    except service.UnknownStudent:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=wording.UNKNOWN_STUDENT_DETAIL
        ) from None


@router.get("/students/{student_id}/schedule", response_model=list[DiaryLessonOut])
async def schedule(
    student_id: int,
    date_from: Date | None = Query(default=None, alias="from"),
    date_to: Date | None = Query(default=None, alias="to"),
    svc: service.DiaryService = Depends(_service),
) -> list[DiaryLessonOut]:
    student, scope = await _child(svc, student_id)
    start, end = _range(date_from, date_to, svc.today())
    lessons = await _guard(svc.schedule_of(student, scope, start, end))
    return [DiaryLessonOut.of(overlaid) for overlaid in lessons]


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
    student, scope = await _child(svc, student_id)
    start, end = _range(date_from, date_to, svc.today())
    items = await _guard(svc.homework_of(student, scope, start, end))
    return [DiaryHomeworkOut.of(overlaid) for overlaid in items]


@router.get("/students/{student_id}/grades", response_model=list[DiaryMarkOut])
async def grades(
    student_id: int,
    date_from: Date | None = Query(default=None, alias="from"),
    date_to: Date | None = Query(default=None, alias="to"),
    svc: service.DiaryService = Depends(_service),
) -> list[DiaryMarkOut]:
    student = await _student(svc, student_id)
    start, end = _range(date_from, date_to, svc.today())
    marks = await _guard(svc.marks(student.education_id, start, end))
    return [DiaryMarkOut.of(mark) for mark in marks]


@router.get("/students/{student_id}/periods", response_model=list[DiaryPeriodOut])
async def periods(
    student_id: int,
    svc: service.DiaryService = Depends(_service),
) -> list[DiaryPeriodOut]:
    student = await _student(svc, student_id)
    found = await _guard(svc.periods_of(student))
    return [DiaryPeriodOut.of(period) for period in found]


@router.get("/students/{student_id}/subjects", response_model=list[DiarySubjectOut])
async def subjects(
    student_id: int,
    period_id: int | None = Query(default=None),
    svc: service.DiaryService = Depends(_service),
) -> list[DiarySubjectOut]:
    """Subjects studied in a period; the current one when none is named."""
    student = await _student(svc, student_id)
    items = await _guard(svc.subjects_of(student, period_id))
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
# Filed under the child — the diary's server and the pupil's id on it
# (`services/diary_corrections.child_scope`) — rather than under the session or the login,
# so signing out and back in finds them where they were left, and everyone
# whose own diary lists the child reads and writes the same set: both parents,
# the pupil's own account if it lists itself, and any other account the diary
# answers with the child, whatever its role. The login plays no part; it
# is whatever the phone typed, and a login copied from another family reaches
# nothing (#165). What does decide is `_child`, before every read and write:
# the id is looked up among the pupils this session's own diary lists, on
# every call, and anything else is a 404 — an id from another family is
# otherwise a way to write into their diary. A child the diary lists by an id
# outside its own numbering gets no corrections at all (`scope_of`): with the
# login gone from the key, that number alone would decide whose they were.


@router.get("/students/{student_id}/overrides", response_model=list[DiaryOverrideOut])
async def list_overrides(
    student_id: int,
    svc: service.DiaryService = Depends(_service),
    *,
    session: FromDishka[AsyncSession],
) -> list[DiaryOverrideOut]:
    """Every correction anybody who sees this child has made for them —
    this account's and, say, the other parent's alike; no row says whose."""
    _, scope = await _child(svc, student_id)
    if scope is None:
        return []
    found = await diary_corrections.list_overrides(session, scope, student_id)
    return [DiaryOverrideOut.of(item) for item in found]


@router.put("/students/{student_id}/overrides", response_model=DiaryOverrideOut)
async def put_override(
    student_id: int,
    payload: DiaryOverrideIn,
    svc: service.DiaryService = Depends(_service),
    *,
    session: FromDishka[AsyncSession],
) -> DiaryOverrideOut:
    """Writes or replaces one correction.

    The target is the string the read endpoints handed down; it is not parsed
    beyond the check that it names something correctable. A target this server
    would never produce is refused rather than stored: a row no read path can
    match would sit in the table looking like a correction somebody made, with
    no way to reset it, because the button that resets it only appears next to
    the value it changed.

    Replaces whatever was there, whoever wrote it: the last writer wins,
    ``original`` included.

    A child that can have no corrections is a ``422``, the status the app
    already reads on this route as «Это поле нельзя исправить» — true of every
    field of that child.
    """
    _, scope = await _child(svc, student_id)
    if scope is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Для этого ученика правки недоступны",
        )
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

    stored = await diary_corrections.put_override(
        session,
        scope=scope,
        student_id=student_id,
        target=payload.target,
        field=payload.field,
        value=payload.value,
        original=payload.original,
    )
    # The service leaves the commit to its caller, and a correction answered
    # before it is kept would be gone from the next read.
    await session.commit()
    return DiaryOverrideOut.of(stored)


@router.post(
    "/students/{student_id}/overrides/reset",
    status_code=status.HTTP_204_NO_CONTENT,
)
async def reset_override(
    student_id: int,
    payload: DiaryResetIn,
    svc: service.DiaryService = Depends(_service),
    *,
    session: FromDishka[AsyncSession],
) -> None:
    """Resets one field back to what the diary says.

    A POST with a body rather than a DELETE with a query string: the target is
    free text and can carry an ampersand, and a reset that quietly matched
    nothing while answering 204 is worse than one that is awkward to spell.

    Idempotent, and answers 204 whether or not there was a row: "there is no
    correction here" is the state the caller asked for, and a 404 would make
    the client decide whether to show an error for having got what it wanted.
    It takes the correction off for everyone who sees the child.
    """
    _, scope = await _child(svc, student_id)
    if scope is not None:
        await diary_corrections.drop_override(
            session, scope, student_id, payload.target, payload.field
        )
        await session.commit()


@router.delete(
    "/students/{student_id}/overrides/all",
    status_code=status.HTTP_204_NO_CONTENT,
)
async def reset_all_overrides(
    student_id: int,
    svc: service.DiaryService = Depends(_service),
    *,
    session: FromDishka[AsyncSession],
) -> None:
    """Resets every correction for this child, whoever wrote it, for everyone
    who sees the child — and nothing of any other child's. The diary answers
    for itself again."""
    _, scope = await _child(svc, student_id)
    if scope is not None:
        await diary_corrections.drop_overrides(session, scope, student_id)
        await session.commit()
