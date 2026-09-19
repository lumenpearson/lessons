"""Endpoints consumed by the Android app and its widget.

Everything here is readable with a bare device token. The few writes - a
homework tick, a personal task - are the device owner's own facts, scoped to
the Telegram account the device is linked to; changes to the class's data
live in ``app.api.edit`` behind a role check.
"""

from __future__ import annotations

import hashlib
from datetime import date as Date
from datetime import datetime, timedelta
from datetime import time as Time

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, Response, status
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class, current_device
from app.config import get_settings
from app.db import EXPECTED_REVISION, current_revision, get_session
from app.models import (
    DeviceInvite,
    DeviceToken,
    Homework,
    JoinMode,
    PersonalTask,
    Role,
    SchoolClass,
    Subject,
    TimetableEntry,
)
from app.schedule import ResolvedDay, ResolvedLesson, ScheduleResolver
from app.schemas import (
    API_VERSION,
    BundleOut,
    CalendarOut,
    ClassOut,
    DayOut,
    DeviceOut,
    DoneIn,
    DoneOut,
    EventOut,
    HomeworkItemOut,
    HomeworkOut,
    JoinRequest,
    JoinResponse,
    LessonOut,
    MeOut,
    NowOut,
    SubjectOut,
    TaskIn,
    TaskOut,
    TaskPatch,
    TermOut,
    UnlinkOut,
)
from app.security import JoinThrottle, client_bucket, hash_token, new_token
from app.services import audit, device_invites, linking
from app.services import calendar as calendar_service
from app.services import subjects as subjects_service
from app.services import tasks as task_service
from app.services import terms as terms_service

router = APIRouter(prefix="/api/v1", tags=["client"])

# A whole school year, because that is the horizon the calendar draws. It used
# to be 31, and 31 days is what the client cached: every date past the window
# read «Нет данных», including the rest of the term, which looked like data
# ending a month after the class was created rather than like a window.
#
# The widest year 1 September (or the Monday after) to 31 May can be is 274
# days; the slack is for a client that anchors a little earlier than the year
# opens. Widening it costs the server almost nothing — ScheduleResolver issues
# the same handful of queries whatever the range, and resolves the rest in
# Python from the weekly template it has already loaded.
MAX_BUNDLE_DAYS = 280

# ``start`` is arbitrary client input and the resolver does date arithmetic on
# top of it (up to ``days`` forward, then another three weeks of look-ahead).
# Near ``date.max`` that arithmetic raises OverflowError, which the widget would
# see as a 500. Bound the parameter to dates a school timetable can plausibly
# mean and reject the rest with the documented 422.
MIN_BUNDLE_START = Date(2000, 1, 1)
MAX_BUNDLE_START = Date(2100, 1, 1)

# ``GET /homework``: the default window and the widest one allowed. Homework
# older than a term is not something the app shows, and an unbounded range is
# an unbounded query.
HOMEWORK_DEFAULT_DAYS = 21
MAX_HOMEWORK_DAYS = 62

# The calendar feed's horizon. Calendar apps re-fetch a subscription every few
# hours, so what matters is the coming weeks, not the whole year.
CALENDAR_DAYS = 60

# Every guess is checked against *every* class at once, so the search space an
# attacker has to beat is the code length alone, and what stands between them
# and a permanent read token for somebody's timetable is this limit. It counts
# in the database rather than in process memory; see JoinThrottle for why that
# distinction is the whole point. Only failures are counted, so a classroom
# joining from one school NAT is never blocked by each other's successes.
join_limiter = JoinThrottle(limit=30, window=900.0)


def _forwarded_list(request: Request, name: str) -> str:
    """Every line of a repeated list header, joined back into the one list.

    RFC 9110 §5.3: several field lines of a comma-separated field mean the same
    as one line with the values joined. ``headers.get`` hands back only the
    first line, and behind a proxy that adds its own line rather than extending
    the caller's, the first line is the caller's - so counting from the right
    within it landed on an address the caller chose.
    """
    return ", ".join(request.headers.getlist(name))


def _forwarded_entry(header: str | None, hops: int) -> str | None:
    """The address the outermost *trusted* proxy put into a forwarding header.

    Entries are appended left to right, so with ``hops`` proxies in front the
    one they added is ``hops`` places from the right. Everything to its left is
    whatever the caller chose to send, and is ignored. A header with fewer
    entries than there are proxies cannot have come through them, so it yields
    nothing rather than the closest match.
    """
    if not header:
        return None
    parts = [part.strip() for part in header.split(",")]
    parts = [part for part in parts if part]
    if len(parts) < hops:
        return None
    return parts[-hops]


def caller_bucket(request: Request) -> str:
    """Identifies the caller for rate-limiting purposes.

    Public, and no longer ``_client_bucket``, because two endpoint families
    now measure the same caller: `/join` and the diary sign-in. One bucketing
    rule for both, or the second one would be measuring something else.

    Reading the leftmost ``X-Forwarded-For`` entry is the usual advice and it is
    exactly wrong: that entry is whatever the client sent, so an attacker sets
    it themselves and lands in a fresh bucket on every request, defeating the
    limit they are being measured by. So no forwarding header is believed unless
    the deployment says how many proxies are in front of it.

    On Vercel the platform writes ``x-vercel-forwarded-for`` itself, replacing
    any copy the client sent, so that one is trustworthy with no configuration —
    and it has to be used, because there every request arrives from the same
    internal address and the socket would put the whole internet in one bucket.

    Otherwise the socket address is used, which is right when the app is run
    directly and, for anything in between, is what ``TRUSTED_PROXY_HOPS`` is for.
    """
    settings = get_settings()

    if settings.behind_vercel:
        vercel = _forwarded_entry(_forwarded_list(request, "x-vercel-forwarded-for"), 1)
        if vercel:
            return client_bucket(vercel)

    hops = settings.trusted_proxy_hops
    if hops > 0:
        forwarded = _forwarded_entry(_forwarded_list(request, "x-forwarded-for"), hops)
        if forwarded:
            return client_bucket(forwarded)

    host = request.client.host if request.client else "unknown"
    return client_bucket(host)


def _to_day_out(day: ResolvedDay) -> DayOut:
    return DayOut(
        date=day.date,
        weekday=day.weekday,
        kind=day.kind.value,
        note=day.note,
        lessons=[
            LessonOut(
                index=lesson.index,
                subject=lesson.subject,
                starts_at=lesson.starts_at,
                ends_at=lesson.ends_at,
                room=lesson.room,
                teacher=lesson.teacher,
                color=lesson.color,
                is_replaced=lesson.is_replaced,
                is_cancelled=lesson.is_cancelled,
                note=lesson.note,
            )
            for lesson in day.lessons
        ],
        events=[
            EventOut(
                title=event.title,
                kind=event.kind.value,
                starts_at=event.starts_at,
                ends_at=event.ends_at,
                location=event.location,
                covers_lesson=event.covers_lesson,
            )
            for event in day.events
        ],
        homework=[
            HomeworkOut(subject=hw.subject, text=hw.text, attachment_url=hw.attachment_url)
            for hw in day.homework
        ],
    )


@router.get("/health")
async def health() -> dict[str, object]:
    return {"status": "ok", "api_version": API_VERSION}


@router.get("/warmup")
async def warmup(session: AsyncSession = Depends(get_session)) -> dict[str, object]:
    """Same as `/health`, plus one round trip to the database.

    `/health` deliberately never opens a connection, so pinging it keeps the
    serverless function loaded but leaves a Neon compute that has scaled to
    zero asleep - the query that finally wakes it is then the user's own
    `/start`, or a device's own sync. This endpoint exists to be pinged
    instead, by an external scheduler: see docs/deploy.md for how the two
    cold starts stack and why hitting this one on a timer is the free
    mitigation for both.
    """
    await session.execute(text("SELECT 1"))

    # And, since the connection is open anyway, whether the schema is the one
    # this code was written against.
    #
    # Worth the extra query because of how the mismatch presents without it: a
    # deploy that lands before its migration does not fail at startup, it fails
    # on the first ORM read of whatever gained a column — for the bot that is
    # the middleware, so *every* update dies, and the cause is an
    # UndefinedColumnError forty frames down a traceback. Saying it here turns
    # that into one line in whatever already pings this endpoint.
    revision = await current_revision(session)
    schema_ok = revision == EXPECTED_REVISION
    body: dict[str, object] = {
        "status": "ok" if schema_ok else "degraded",
        "api_version": API_VERSION,
        "schema": revision or "unknown",
    }
    if not schema_ok:
        body["expected_schema"] = EXPECTED_REVISION
        body["detail"] = _drift_detail(revision)
    return body


def _drift_detail(revision: str | None) -> str:
    """Which way the two disagree, because the two directions are opposites.

    A database *behind* the code is the outage: the code reads a column that is
    not there. A database *ahead* of it is the documented procedure working —
    migrate, then merge — caught in the window between the two, and it closes
    on its own when the deploy lands. Telling the second one to run a migration
    it has already run sends the reader after a problem that is not there, and
    past the one that is (a deploy that never arrived).

    Every revision in this project is a zero-padded number, so comparing them
    as integers is meaningful. Anything else — a hash, a branch label — falls
    through to wording that does not guess a direction it cannot establish.
    """
    if revision is None:
        return "В базе нет таблицы alembic_version: схему создавали не миграциями."
    if revision.isdigit() and EXPECTED_REVISION.isdigit():
        if int(revision) < int(EXPECTED_REVISION):
            return (
                "База отстала от кода. Запустите «alembic upgrade head» "
                "с рабочим DATABASE_URL — до следующего деплоя, а не после."
            )
        return (
            "База впереди кода: миграция применена, деплой ещё не доехал. "
            "Это нормальное окно правильного порядка, и оно закроется само. "
            "Если не закрылось — смотрите, дошёл ли деплой."
        )
    return "Схема базы и схема кода расходятся."


@router.post("/join", response_model=JoinResponse)
async def join(
    request: Request,
    payload: JoinRequest,
    session: AsyncSession = Depends(get_session),
) -> JoinResponse:
    """Exchange a code for a long-lived device token.

    Read-only when the code is the class's: a token with no Telegram account
    behind it is refused by every write path. A personal invite from the bot
    carries the account that asked for it, so the phone that redeems one writes
    with that account's role at the moment of each request — «read-only» has
    not been true of every token since invites existed.
    """
    client = caller_bucket(request)
    retry_after = await join_limiter.blocked_for(session, client)
    if retry_after is not None:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many join attempts",
            headers={"Retry-After": str(int(retry_after) + 1)},
        )

    code = payload.code.strip().upper()
    # The class code first, and a personal invite only if it names no class.
    # The two cannot collide — they are different lengths, see
    # ``services/device_invites.CODE_LENGTH`` — so the order is about which
    # refusal the caller gets rather than about which code wins.
    school_class = await session.scalar(select(SchoolClass).where(SchoolClass.join_code == code))
    invite: DeviceInvite | None = None

    if school_class is not None and school_class.join_mode is JoinMode.INVITE:
        # A real code for a real class, refused because this class does not let
        # a shared secret in. Said plainly rather than as "unknown code": the
        # person holding it has been given it by somebody, and telling them it
        # is wrong sends them back to that person instead of to the bot.
        #
        # Not a failed attempt for the throttle either. The limiter is there to
        # stop somebody walking the code space, and this caller has already
        # found a code — counting it would let a class that switched to invites
        # lock out everybody who still had the old one.
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Этот класс принимает только по личному приглашению из бота",
        )

    if school_class is None:
        invite = await device_invites.find_live(session, code)
        if invite is not None:
            school_class = await session.get(SchoolClass, invite.class_id)

    if school_class is None:
        await join_limiter.record_failure(session, client)
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown join code")

    # Spent before the token is minted, not after: the update is what makes
    # "one code, one phone" true against a second request that read the same
    # live row, and a token minted first would be a token already handed out
    # by the time we found out we lost.
    if invite is not None and not await device_invites.burn(session, invite):
        # Lost a race microseconds wide: the row was live when it was read and
        # spent by the time it was written. Not counted against the limiter,
        # for the same reason the 403 above is not — this caller had a real
        # code, and the limiter is there for somebody who does not.
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown join code")

    token = new_token()
    session.add(
        DeviceToken(
            token_hash=hash_token(token),
            class_id=school_class.id,
            device_name=payload.device_name,
            # An invite carries the account that asked for it, so the device is
            # linked in the same breath as it joins. On the class code it stays
            # null, which is what "joined, nobody knows whose phone" looks like.
            telegram_id=invite.telegram_id if invite is not None else None,
            linked_at=device_invites.utcnow() if invite is not None else None,
        )
    )
    if invite is not None:
        # The same line `/link` writes, for the same event: a phone that from
        # now on acts with somebody's role. On the invite path this is the only
        # place it can be written — there is no second step to hang it on — and
        # in «по приглашению» this is the only door, so without it the journal
        # stops answering «кто подключил этот телефон» exactly when it becomes
        # the only question worth asking of it.
        await audit.record(
            session,
            school_class.id,
            invite.telegram_id,
            "device.link",
            f"телефон подключён по личному коду: {payload.device_name or 'без названия'}",
        )
    await session.commit()
    return JoinResponse(
        token=token,
        class_id=school_class.id,
        class_name=school_class.name,
        school=school_class.school,
        timezone=school_class.timezone_name,
    )


@router.get("/bundle", response_model=BundleOut)
async def bundle(
    response: Response,
    start: Date | None = Query(default=None, description="First day, defaults to today"),
    days: int = Query(default=14, ge=1, le=MAX_BUNDLE_DAYS),
    if_none_match: str | None = Header(default=None),
    device: DeviceToken = Depends(current_device),
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> BundleOut | Response:
    """Everything the client caches in one round trip.

    The client computes the current lesson/break state locally from this
    payload, so the widget keeps ticking with no network.

    Answers 304 to a matching ``If-None-Match``: the widget polls on a timer
    and nearly every poll finds nothing changed, so nearly every poll should
    cost a hash comparison rather than a two-week payload over mobile data.
    """
    if start is not None and not (MIN_BUNDLE_START <= start <= MAX_BUNDLE_START):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"start must be between {MIN_BUNDLE_START.isoformat()} "
                f"and {MAX_BUNDLE_START.isoformat()}"
            ),
        )

    # The class's own zone, not the server's: a Kaliningrad class and a
    # Vladivostok class hosted by the same instance are ten hours apart, and
    # "today" has to mean the right day for each of them.
    today = start or datetime.now(school_class.tz).date()

    resolver = ScheduleResolver(session, school_class)
    resolved = await resolver.resolve_range(today, days)

    # Look past the requested window so "homework for the next school day" still
    # resolves across long holidays.
    last_with_lessons = max((d.date for d in resolved if d.has_lessons), default=today)
    following = await ScheduleResolver(session, school_class).next_school_day(last_with_lessons)

    # Seeded on read as well as on creation: a class made before terms existed
    # has none, and the first person to open its calendar should see the
    # conventional ones rather than nothing. `ensure` is idempotent, so this
    # writes on exactly one request per class per year and reads on the rest.
    #
    # That one request is regularly several: every phone in the class polls
    # this endpoint on the same timer, so the first read of a brand-new class
    # is as many simultaneous seedings as there are devices. Both services
    # therefore insert inside a savepoint and concede to whoever got there
    # first (`terms.ensure`, `subjects._adopt`) - the loser returns the winner's
    # rows and answers the same bundle, rather than taking this read's whole
    # transaction down with a unique-constraint violation.
    year = terms_service.opening_year_of(today)
    terms = await terms_service.ensure(session, school_class, year)
    # Same reasoning for the subject dictionary: a class whose timetable was
    # typed before the link existed has names in the template and nothing in
    # «📚 Предметы», which on the phone is a timetable with no colours at all.
    # Also idempotent, and free — a count — once everything is in step.
    await subjects_service.sync_from_timetable(session, school_class.id)
    await session.commit()

    out = BundleOut(
        school_class=ClassOut(
            id=school_class.id,
            name=school_class.name,
            grade=school_class.grade,
            letter=school_class.letter,
            school=school_class.school,
            city=school_class.city,
            timezone=school_class.timezone_name,
            term_kind=terms_service.scheme_of(school_class).value,
            terms=[
                TermOut(
                    index=term.index,
                    kind=term.kind.value,
                    starts_on=term.starts_on,
                    ends_on=term.ends_on,
                )
                for term in terms
            ],
        ),
        generated_at=datetime.now(school_class.tz).isoformat(),
        days=[_to_day_out(day) for day in resolved],
        next_school_day=_to_day_out(following) if following else None,
        device=await _device_out(session, device),
    )

    etag = _etag(out)
    if _matches(if_none_match, etag):
        return Response(status_code=status.HTTP_304_NOT_MODIFIED, headers={"ETag": etag})
    response.headers["ETag"] = etag
    return out


def _etag(out: BundleOut) -> str:
    """A strong validator over the body with ``generated_at`` blanked.

    The timestamp changes on every request, so hashing it would mean no two
    responses ever match and the 304 path never runs. Everything else in the
    body is what the client actually caches.
    """
    stable = out.model_copy(update={"generated_at": ""}).model_dump_json()
    return '"' + hashlib.sha256(stable.encode("utf-8")).hexdigest() + '"'


def _matches(if_none_match: str | None, etag: str) -> bool:
    """RFC 9110 ``If-None-Match``: a list of validators, ``*``, weak forms allowed."""
    if not if_none_match:
        return False
    for candidate in if_none_match.split(","):
        candidate = candidate.strip()
        if candidate.startswith("W/"):
            candidate = candidate[2:]
        if candidate == "*" or candidate == etag:
            return True
    return False


async def _role_of(session: AsyncSession, device: DeviceToken) -> Role | None:
    return await linking.effective_role(session, device)


def _can_edit(role: Role | None) -> bool:
    return role is not None and role.at_least(Role.EDITOR)


async def _device_out(session: AsyncSession, device: DeviceToken) -> DeviceOut:
    role = await _role_of(session, device)
    return DeviceOut(
        linked=device.is_linked,
        role=role.value if role else None,
        can_edit=_can_edit(role),
    )


def _linked_id(device: DeviceToken) -> int:
    """The Telegram account behind the device, or the 403 every personal
    endpoint answers with when there is none."""
    if device.telegram_id is None:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="device is not linked")
    return device.telegram_id


def _check_bounds(*days: Date) -> None:
    """Refuse a date a school timetable cannot plausibly mean.

    Split out of ``_check_range`` because the callers have to reach it
    *before* they derive the other end of the window: ``end = start +
    timedelta(...)`` raises OverflowError within three weeks of ``date.max``,
    and that is a 500 on a query string anybody with a device token can type.
    """
    if any(not (MIN_BUNDLE_START <= day <= MAX_BUNDLE_START) for day in days):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"dates must be between {MIN_BUNDLE_START.isoformat()} "
                f"and {MAX_BUNDLE_START.isoformat()}"
            ),
        )


def _check_range(start: Date, end: Date, max_days: int) -> None:
    _check_bounds(start, end)
    if end < start:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="to must not precede from"
        )
    if (end - start).days > max_days:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"the range may span at most {max_days} days",
        )


def _today(school_class: SchoolClass) -> Date:
    return _now(school_class).date()


def _now(school_class: SchoolClass) -> datetime:
    """The class's wall clock. A function, not an expression, so the tests
    can pin it to a Monday morning."""
    return datetime.now(school_class.tz)


# --------------------------------------------------------------------------
# The device itself
# --------------------------------------------------------------------------


@router.get("/me", response_model=MeOut)
async def me(
    device: DeviceToken = Depends(current_device),
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> MeOut:
    """Who this device is, and the code to type into the bot if it is nobody yet.

    The code is issued here rather than at join time so that a device that
    never opens the link screen never holds one, and re-issued after an
    unlink for the same reason.
    """
    role = await _role_of(session, device)
    link_code: str | None = None
    deep_link: str | None = None
    if not device.is_linked:
        link_code = await linking.issue_link_code(session, device)
        username = get_settings().bot_username.lstrip("@")
        if username:
            deep_link = f"https://t.me/{username}?start=link_{link_code}"
    return MeOut(
        device_name=device.device_name,
        linked=device.is_linked,
        role=role.value if role else None,
        can_edit=_can_edit(role),
        link_code=link_code,
        bot_deep_link=deep_link,
    )


@router.post("/me/unlink", response_model=UnlinkOut)
async def unlink(
    device: DeviceToken = Depends(current_device),
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> UnlinkOut:
    """Back to read-only. Idempotent: unlinking an unlinked device is fine."""
    if device.is_linked:
        await linking.unlink_device(session, device)
        await session.commit()
    return UnlinkOut(linked=False)


# --------------------------------------------------------------------------
# Homework
# --------------------------------------------------------------------------


def _homework_out(item: Homework, done: bool) -> HomeworkItemOut:
    return HomeworkItemOut(
        id=item.id,
        due_date=item.due_date,
        subject=item.subject_name,
        text=item.text,
        attachment_url=item.attachment_url,
        done=done,
    )


@router.get("/homework", response_model=list[HomeworkItemOut])
async def homework_list(
    from_: Date | None = Query(default=None, alias="from"),
    to: Date | None = Query(default=None),
    device: DeviceToken = Depends(current_device),
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> list[HomeworkItemOut]:
    """Homework due in a window, each row with this person's own tick."""
    start = from_ or _today(school_class)
    # Bounded before the default window is derived from it, not after: `from`
    # is arbitrary client input and `start + 21 days` overflows within three
    # weeks of `date.max`, which the app saw as a 500 rather than as the 422
    # the same date has always got from `/bundle`.
    _check_bounds(start)
    end = to or start + timedelta(days=HOMEWORK_DEFAULT_DAYS)
    _check_range(start, end, MAX_HOMEWORK_DAYS)

    rows = list(
        await session.scalars(
            select(Homework)
            .where(
                Homework.class_id == school_class.id,
                Homework.due_date >= start,
                Homework.due_date <= end,
            )
            .order_by(Homework.due_date, Homework.subject_name, Homework.id)
        )
    )
    ticks: set[int] = set()
    if device.telegram_id is not None:
        ticks = await task_service.homework_ticks(
            session, device.telegram_id, [row.id for row in rows]
        )
    return [_homework_out(row, row.id in ticks) for row in rows]


async def _homework_in_class(
    session: AsyncSession, homework_id: int, school_class: SchoolClass
) -> Homework:
    item = await session.scalar(
        select(Homework).where(Homework.id == homework_id, Homework.class_id == school_class.id)
    )
    if item is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown homework")
    return item


@router.post("/homework/{homework_id}/done", response_model=DoneOut)
async def homework_done(
    homework_id: int,
    payload: DoneIn,
    device: DeviceToken = Depends(current_device),
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> DoneOut:
    """Set (not toggle) this person's tick: the app sends the state it shows,
    so a retried request lands on the same answer."""
    telegram_id = _linked_id(device)
    item = await _homework_in_class(session, homework_id, school_class)
    current = item.id in await task_service.homework_ticks(session, telegram_id, [item.id])
    if current != payload.done:
        current = await task_service.toggle_homework_done(session, item, telegram_id)
    return DoneOut(id=item.id, done=current)


# --------------------------------------------------------------------------
# Subjects
# --------------------------------------------------------------------------


@router.get("/subjects", response_model=list[SubjectOut])
async def subjects(
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> list[SubjectOut]:
    rows = await session.scalars(
        select(Subject).where(Subject.class_id == school_class.id).order_by(Subject.name)
    )
    return [
        SubjectOut(name=row.name, short_name=row.short_name, teacher=row.teacher, color=row.color)
        for row in rows
    ]


# --------------------------------------------------------------------------
# Now
# --------------------------------------------------------------------------


def _to_lesson_out(lesson: ResolvedLesson) -> LessonOut:
    return LessonOut(
        index=lesson.index,
        subject=lesson.subject,
        starts_at=lesson.starts_at,
        ends_at=lesson.ends_at,
        room=lesson.room,
        teacher=lesson.teacher,
        color=lesson.color,
        is_replaced=lesson.is_replaced,
        is_cancelled=lesson.is_cancelled,
        note=lesson.note,
    )


def _seconds_until(day: Date, clock: Time, at: datetime) -> int:
    target = datetime.combine(day, clock, tzinfo=at.tzinfo)
    return max(int((target - at).total_seconds()), 0)


def _now_state(
    day: ResolvedDay, at: datetime
) -> tuple[str, ResolvedLesson | None, ResolvedLesson | None, int | None]:
    """(state, current, next, seconds to the next boundary) for one instant.

    Cancelled lessons are skipped: a cancelled first lesson means the day
    starts with the second, and a widget saying «Идёт урок» about a lesson
    that is not happening is worse than none. Events are ignored on purpose -
    a canteen break sits *in* a break and an excursion that covers a lesson
    still leaves the bell schedule as the thing the countdown follows.
    """
    lessons = [lesson for lesson in day.lessons if not lesson.is_cancelled]
    if not lessons:
        return "day_off", None, None, None

    clock = at.time()
    for position, lesson in enumerate(lessons):
        following = lessons[position + 1] if position + 1 < len(lessons) else None
        if clock < lesson.starts_at:
            state = "before_school" if position == 0 else "break"
            return state, None, lesson, _seconds_until(day.date, lesson.starts_at, at)
        if clock < lesson.ends_at:
            return "lesson", lesson, following, _seconds_until(day.date, lesson.ends_at, at)
    return "after_school", None, None, None


@router.get("/now", response_model=NowOut)
async def now(
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> NowOut:
    at = _now(school_class)
    today = at.date()
    resolver = ScheduleResolver(session, school_class)
    day = (await resolver.resolve_range(today, 1))[0]
    state, current, following, until = _now_state(day, at)

    if state == "day_off":
        has_timetable = await session.scalar(
            select(TimetableEntry.id).where(TimetableEntry.class_id == school_class.id).limit(1)
        )
        if has_timetable is None:
            state = "no_data"

    next_day = await resolver.next_school_day(today)
    return NowOut(
        date=today,
        time=at.time().replace(microsecond=0),
        state=state,
        current=_to_lesson_out(current) if current else None,
        next=_to_lesson_out(following) if following else None,
        until_next_seconds=until,
        next_school_day=next_day.date if next_day else None,
    )


# --------------------------------------------------------------------------
# Personal tasks
# --------------------------------------------------------------------------


def _task_out(task: PersonalTask) -> TaskOut:
    return TaskOut(
        id=task.id,
        title=task.title,
        notes=task.notes,
        subject_name=task.subject_name,
        due_date=task.due_date,
        due_time=task.due_time,
        priority=task.priority,
        done=task.done,
        done_at=task.done_at,
        homework_id=task.homework_id,
        remind_at=task.remind_at,
        created_at=task.created_at,
        updated_at=task.updated_at,
    )


def _wall_time(value: datetime | None, school_class: SchoolClass) -> datetime | None:
    """Class wall time for the naive column. A naive value is taken as already
    being wall time; an aware one is an instant and is converted."""
    if value is None or value.tzinfo is None:
        return value
    return value.astimezone(school_class.tz).replace(tzinfo=None)


async def _check_homework_id(
    session: AsyncSession, homework_id: int | None, school_class: SchoolClass
) -> None:
    if homework_id is None:
        return
    found = await session.scalar(
        select(Homework.id).where(Homework.id == homework_id, Homework.class_id == school_class.id)
    )
    if found is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="homework_id is not in this class",
        )


async def _own_task(
    session: AsyncSession, task_id: int, school_class: SchoolClass, telegram_id: int
) -> PersonalTask:
    task = await task_service.get_task(session, task_id, school_class.id, telegram_id)
    if task is None:
        # Somebody else's task and a task that never existed are the same
        # answer: an id must not reveal that a classmate has a list.
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown task")
    return task


@router.get("/tasks", response_model=list[TaskOut])
async def tasks_list(
    include_done: bool = Query(default=False),
    device: DeviceToken = Depends(current_device),
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> list[TaskOut]:
    telegram_id = _linked_id(device)
    rows = await task_service.list_tasks(
        session, school_class.id, telegram_id, include_done=include_done, limit=200
    )
    return [_task_out(row) for row in rows]


@router.post("/tasks", response_model=TaskOut, status_code=status.HTTP_201_CREATED)
async def tasks_create(
    payload: TaskIn,
    device: DeviceToken = Depends(current_device),
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> TaskOut:
    telegram_id = _linked_id(device)
    await _check_homework_id(session, payload.homework_id, school_class)
    task = await task_service.add_task(
        session,
        school_class.id,
        telegram_id,
        payload.title,
        due_date=payload.due_date,
        due_time=payload.due_time,
        priority=payload.priority,
        subject_name=payload.subject_name,
        notes=payload.notes,
        homework_id=payload.homework_id,
        remind_at=_wall_time(payload.remind_at, school_class),
    )
    return _task_out(task)


@router.patch("/tasks/{task_id}", response_model=TaskOut)
async def tasks_update(
    task_id: int,
    payload: TaskPatch,
    device: DeviceToken = Depends(current_device),
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> TaskOut:
    telegram_id = _linked_id(device)
    task = await _own_task(session, task_id, school_class, telegram_id)

    changes = payload.model_dump(exclude_unset=True)
    if "homework_id" in changes:
        await _check_homework_id(session, changes["homework_id"], school_class)
    if "remind_at" in changes:
        changes["remind_at"] = _wall_time(changes["remind_at"], school_class)
    done = changes.pop("done", None)
    for name, value in changes.items():
        setattr(task, name, value)
    if done is not None and done != task.done:
        await task_service.set_done(session, task, done)
    else:
        await session.commit()
    await session.refresh(task)
    return _task_out(task)


@router.delete("/tasks/{task_id}", status_code=status.HTTP_204_NO_CONTENT)
async def tasks_delete(
    task_id: int,
    device: DeviceToken = Depends(current_device),
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> Response:
    telegram_id = _linked_id(device)
    task = await _own_task(session, task_id, school_class, telegram_id)
    await task_service.delete_task(session, task)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/tasks/{task_id}/done", response_model=TaskOut)
async def tasks_done(
    task_id: int,
    payload: DoneIn,
    device: DeviceToken = Depends(current_device),
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> TaskOut:
    telegram_id = _linked_id(device)
    task = await _own_task(session, task_id, school_class, telegram_id)
    if task.done != payload.done:
        await task_service.set_done(session, task, payload.done)
        # ``updated_at`` is set by the database on update and expired by the
        # commit; reading it back lazily is not possible on an async session.
        await session.refresh(task)
    return _task_out(task)


# --------------------------------------------------------------------------
# Calendar feed
# --------------------------------------------------------------------------


@router.get("/calendar", response_model=CalendarOut)
async def calendar_url(
    request: Request,
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> CalendarOut:
    """The subscription URL for this class, minting the feed secret on first ask."""
    token = await calendar_service.ensure_calendar_token(session, school_class)
    base = get_settings().public_base_url.rstrip("/") or str(request.base_url).rstrip("/")
    return CalendarOut(url=f"{base}/api/v1/calendar/{token}.ics")


@router.get("/calendar/{calendar_token}.ics")
async def calendar_feed(
    calendar_token: str,
    session: AsyncSession = Depends(get_session),
) -> Response:
    """The class as iCalendar. No bearer: calendar apps cannot send one.

    The token in the path is the whole of the authentication, which is why it
    is a secret of its own rather than the join code (see the model). Shared
    per class, so it carries no personal tasks - those are one person's and
    the feed is everybody's.
    """
    if not calendar_token or len(calendar_token) > 64:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown calendar")
    school_class = await session.scalar(
        select(SchoolClass).where(SchoolClass.calendar_token == calendar_token)
    )
    if school_class is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown calendar")

    today = _today(school_class)
    days = await ScheduleResolver(session, school_class).resolve_range(today, CALENDAR_DAYS)
    body = calendar_service.render_ics(
        school_class, days, generated_at=datetime.now(school_class.tz)
    )
    return Response(
        content=body,
        media_type="text/calendar; charset=utf-8",
        headers={
            # Private: the URL is a secret, and a shared cache holding the
            # body would hand it to the next request that guessed the path.
            "Cache-Control": "private, max-age=900",
            "Content-Disposition": 'inline; filename="lessons.ics"',
        },
    )
