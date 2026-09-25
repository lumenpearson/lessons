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
from datetime import timedelta
from typing import Annotated, Any

from dishka.integrations.fastapi import FromDishka, inject
from fastapi import APIRouter, Body, Depends, Header, HTTPException, Query, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.public import MAX_BUNDLE_START, MIN_BUNDLE_START, caller_bucket
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
from app.providers.diary.registry import NETSCHOOL, PETERSBURG
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
from app.security import JoinThrottle
from app.services import diary as service
from app.services import diary_overrides as overrides

router = APIRouter(route_class=DishkaAnnotatedRoute, prefix="/api/v1/diary", tags=["diary"])

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


def _range(
    date_from: Date | None, date_to: Date | None, today: Date | None = None
) -> tuple[Date, Date]:
    for day in (date_from, date_to):
        if day is not None and not (MIN_DATE <= day <= MAX_DATE):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"dates must be between {MIN_DATE.isoformat()} and {MAX_DATE.isoformat()}",
            )
    # The diary's own day, not the server's — the provider's, so «Сетевой
    # город» gets its region's zone and Petersburg its city's. The default is
    # Petersburg's when a caller passes no `today`, which keeps the standalone
    # `_range(None, None)` behaviour its test pins.
    start = date_from or today or diary_today()
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

#: The detail of the 503 a deployment without ``DIARY_SECRET`` answers.
DISABLED_DETAIL = "Дневник на этом сервере выключен."


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


#: Failed diary sign-ins one caller may make in a quarter of an hour.
#:
#: The other door onto this same service counts even harder: `diary_web`
#: spends its one-time ticket *before* the sign-in, and its own comment says
#: why — «it is what stops whoever holds the URL guessing passwords against
#: the upstream from our address». This endpoint had no ticket and no limit at
#: all, so it was that oracle with the door held open: anybody could post a
#: login and a guess and read the answer off the status code, 401 for wrong
#: and 200 for right, as fast as they liked. Two things follow from that and
#: both are ours: credential stuffing against a third party's school diary
#: proxied through this server, and the upstream blocking this deployment's
#: address — which takes the feature down for every family on it, including
#: the `/diary/signin` page the ticket was protecting.
#:
#: Looser than `/join`'s thirty, because a parent who has forgotten which of
#: their two e-mail addresses the school has is a real person making real
#: mistakes, and only failures are counted.
diary_login_limiter = JoinThrottle(limit=10, window=900.0)


async def _refuse_if_throttled(session: AsyncSession, client: str) -> None:
    """429 while ``client`` has spent its failures. One check for both doors
    onto the upstream, so they cannot drift into two limits."""
    retry_after = await diary_login_limiter.blocked_for(session, client)
    if retry_after is not None:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Слишком много попыток входа. Попробуйте позже.",
            headers={"Retry-After": str(int(retry_after) + 1)},
        )


def _served_region(key: str | None) -> str:
    """An allow-listed «Сетевой город» region that still takes a password, or
    a 422 — asked before any upstream call, so a region we do not serve never
    receives a request, whichever door it came through."""
    from app.providers.netschool import regions

    region = regions.get(key)
    if region is None or not region.password:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Unknown or unsupported region for «Сетевой город»",
        )
    return region.key


def _resolve_login_target(payload: DiaryLoginIn) -> tuple[str, str | None, int | None]:
    """The provider, region and school to sign in with, validated locally.

    Absent provider is Petersburg, so an older phone that sends only a login and
    a password is unchanged. For «Сетевой город» the region must be an
    allow-listed key that still takes a password and the school a positive id —
    checked here, before any upstream call, so a bad target is a 422 and never a
    request to a region we do not serve.
    """
    provider = payload.provider or PETERSBURG
    if provider == PETERSBURG:
        return PETERSBURG, None, None
    if provider == NETSCHOOL:
        region = _served_region(payload.region)
        if payload.school_id is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="A school id is required for «Сетевой город»",
            )
        return NETSCHOOL, region, payload.school_id
    raise HTTPException(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        detail=f"Unknown diary provider {provider!r}",
    )


@router.post("/login", response_model=DiaryLoginOut)
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
    # A bucket of its own, asked for through `caller_bucket` rather than built
    # by putting «diary:» in front of what it returns. The counter's column is
    # `VARCHAR(64)` and the digest already fills it, so the prefix made every
    # recorded failure six characters too long for Postgres: the insert raised
    # out of the `except` that was recording it, so a wrong password answered
    # 500 instead of 401 and nothing was ever counted — the limit below existed
    # only in the tests, where SQLite does not enforce a width.
    client = caller_bucket(request, scope="diary:")
    await _refuse_if_throttled(session, client)

    provider, region, school_id = _resolve_login_target(payload)

    try:
        token, row = await _guard(
            service.sign_in(
                session,
                payload.login,
                payload.password,
                provider=provider,
                region=region,
                school_id=school_id,
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
        if refusal.status_code != status.HTTP_503_SERVICE_UNAVAILABLE:
            await diary_login_limiter.record_failure(session, client)
        raise
    return DiaryLoginOut(token=token, login=row.login)


# ---------------------------------------------------------------------------
# A session the phone opened
# ---------------------------------------------------------------------------


#: The detail of a registration the upstream refused from this server.
REFUSED_FROM_HERE_DETAIL = "Дневник не принял эту сессию с нашего сервера — дело не в пароле."


class _NoEchoRoute(DishkaAnnotatedRoute):
    """A route whose 422 names what was wrong and never repeats what was sent.

    FastAPI's validation answer carries each refused value back as ``input``.
    On ``/session`` that value is an upstream session — a cookie that failed
    the charset check, a token that is not a JWT — or, for a client that sent
    one, a password. A session goes to this server once and is never echoed;
    the refusal is no exception to that.
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


def _resolve_session_target(payload: DiarySessionBody) -> tuple[str, str | None, int | None]:
    """The provider, region and school to adopt into, checked against the
    allow-list before any call — a region outside it, or one that takes no
    password (altai-krai, primorye, tula), is a 422 that nothing upstream saw.
    """
    if not isinstance(payload, NetSchoolSessionIn):
        return PETERSBURG, None, None
    return NETSCHOOL, _served_region(payload.region), payload.school_id


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

    Shares ``/login``'s limiter **and bucket**, so a caller who spent ten
    wrong passwords does not get ten more tries by session. Counted: a session
    the upstream refused (409), an account with no pupil (403), an answer
    nobody can read (502). Not counted: anything that never reached the
    upstream (422), the feature being off, or the upstream not answering (503).

    409 rather than ``/login``'s 401 + ``X-Diary-Reauth``, because asking for
    the password again would loop: the session was good on the phone seconds
    ago, and it is *this server* the diary will not take it from.
    """
    # The same limiter and the same bucket as /login: a separate one would
    # double what one caller may try against the upstream from our address.
    client = caller_bucket(request, scope="diary:")
    await _refuse_if_throttled(session, client)

    provider, region, school_id = _resolve_session_target(payload)
    # The provider's own serialisation: Petersburg's bare token, or the JSON of
    # what «Сетевой город» handed the phone, with the fields it did not hand
    # left out rather than stored as nulls.
    credential = (
        payload.credential.model_dump_json(exclude_none=True)
        if isinstance(payload, NetSchoolSessionIn)
        else payload.credential.token
    )

    try:
        registered = await service.adopt(
            session,
            provider=provider,
            login=payload.login,
            credential=credential,
            region=region,
            school_id=school_id,
        )
    except service.DiaryDisabled as failure:
        raise _disabled() from failure
    except UpstreamUnavailable as failure:
        # Nothing judged the session: the diary did not answer, or will not
        # talk to this address at all. Forgiven, like /login's 503.
        raise _unavailable(failure) from failure
    except NoStudents as failure:
        await diary_login_limiter.record_failure(session, client)
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=failure.message
        ) from failure
    except (SessionExpired, BadCredentials) as failure:
        # Before the broader DiaryError below, and after NoStudents, which is
        # a SessionExpired too. Counted: this is also what a replay of a
        # session that was never real looks like.
        await diary_login_limiter.record_failure(session, client)
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=REFUSED_FROM_HERE_DETAIL
        ) from failure
    except DiaryError as failure:
        await diary_login_limiter.record_failure(session, client)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY, detail=failure.message
        ) from failure

    return DiarySessionOut(
        token=registered.token,
        login=registered.row.login,
        provider=provider,
        region=region,
        school_id=school_id,
        school_name=registered.school_name,
        zone=service.zone_for(provider, region),
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
    *,
    session: FromDishka[AsyncSession],
) -> list[DiaryLessonOut]:
    student = await _student(svc, student_id)
    start, end = _range(date_from, date_to, svc.today())
    lessons = await _guard(svc.schedule(student.education_id, start, end))
    corrections = await service.load_corrections(
        session, row.login, student_id, provider=row.provider or PETERSBURG, region=row.region
    )
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
    *,
    session: FromDishka[AsyncSession],
) -> list[DiaryHomeworkOut]:
    """Homework as its own resource.

    Upstream it is a field on a lesson; the client should not have to know
    that, so the provider pulls it out and this endpoint exists.
    """
    student = await _student(svc, student_id)
    start, end = _range(date_from, date_to, svc.today())
    items = await _guard(svc.homework(student.education_id, start, end))
    corrections = await service.load_corrections(
        session, row.login, student_id, provider=row.provider or PETERSBURG, region=row.region
    )
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
    start, end = _range(date_from, date_to, svc.today())
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
    *,
    session: FromDishka[AsyncSession],
) -> list[DiaryOverrideOut]:
    """Every correction this account has made for this child."""
    await _student(svc, student_id)
    found = await service.list_overrides(
        session, row.login, student_id, provider=row.provider or PETERSBURG, region=row.region
    )
    return [DiaryOverrideOut.of(item) for item in found]


@router.put("/students/{student_id}/overrides", response_model=DiaryOverrideOut)
async def put_override(
    student_id: int,
    payload: DiaryOverrideIn,
    svc: service.DiaryService = Depends(_service),
    row: DiarySession = Depends(current_diary),
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
        provider=row.provider or PETERSBURG,
        region=row.region,
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
    """
    await _student(svc, student_id)
    await service.drop_override(
        session, row.login, student_id, payload.target, payload.field,
        provider=row.provider or PETERSBURG, region=row.region,
    )


@router.delete(
    "/students/{student_id}/overrides/all",
    status_code=status.HTTP_204_NO_CONTENT,
)
async def reset_all_overrides(
    student_id: int,
    svc: service.DiaryService = Depends(_service),
    row: DiarySession = Depends(current_diary),
    *,
    session: FromDishka[AsyncSession],
) -> None:
    """Resets every correction for this child. The diary answers for itself again."""
    await _student(svc, student_id)
    await service.drop_overrides(
        session, row.login, student_id, provider=row.provider or PETERSBURG, region=row.region
    )
