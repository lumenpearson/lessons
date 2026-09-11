"""Read-only endpoints consumed by the Android app and its widget."""

from __future__ import annotations

from datetime import date as Date
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class
from app.config import get_settings
from app.db import get_session
from app.models import DeviceToken, SchoolClass
from app.schedule import ResolvedDay, ScheduleResolver
from app.schemas import (
    API_VERSION,
    BundleOut,
    ClassOut,
    DayOut,
    EventOut,
    HomeworkOut,
    JoinRequest,
    JoinResponse,
    LessonOut,
)
from app.security import JoinThrottle, client_bucket, hash_token, new_token

router = APIRouter(prefix="/api/v1", tags=["client"])

MAX_BUNDLE_DAYS = 31

# ``start`` is arbitrary client input and the resolver does date arithmetic on
# top of it (up to ``days`` forward, then another three weeks of look-ahead).
# Near ``date.max`` that arithmetic raises OverflowError, which the widget would
# see as a 500. Bound the parameter to dates a school timetable can plausibly
# mean and reject the rest with the documented 422.
MIN_BUNDLE_START = Date(2000, 1, 1)
MAX_BUNDLE_START = Date(2100, 1, 1)

# Every guess is checked against *every* class at once, so the search space an
# attacker has to beat is the code length alone, and what stands between them
# and a permanent read token for somebody's timetable is this limit. It counts
# in the database rather than in process memory; see JoinThrottle for why that
# distinction is the whole point. Only failures are counted, so a classroom
# joining from one school NAT is never blocked by each other's successes.
join_limiter = JoinThrottle(limit=30, window=900.0)


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


def _client_bucket(request: Request) -> str:
    """Identifies the caller for rate-limiting purposes.

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
        vercel = _forwarded_entry(request.headers.get("x-vercel-forwarded-for"), 1)
        if vercel:
            return client_bucket(vercel)

    hops = settings.trusted_proxy_hops
    if hops > 0:
        forwarded = _forwarded_entry(request.headers.get("x-forwarded-for"), hops)
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


@router.post("/join", response_model=JoinResponse)
async def join(
    request: Request,
    payload: JoinRequest,
    session: AsyncSession = Depends(get_session),
) -> JoinResponse:
    """Exchange a class join code for a long-lived read-only device token."""
    client = _client_bucket(request)
    retry_after = await join_limiter.blocked_for(session, client)
    if retry_after is not None:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many join attempts",
            headers={"Retry-After": str(int(retry_after) + 1)},
        )

    code = payload.code.strip().upper()
    school_class = await session.scalar(select(SchoolClass).where(SchoolClass.join_code == code))
    if school_class is None:
        await join_limiter.record_failure(session, client)
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown join code")

    token = new_token()
    session.add(
        DeviceToken(
            token_hash=hash_token(token),
            class_id=school_class.id,
            device_name=payload.device_name,
        )
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
    start: Date | None = Query(default=None, description="First day, defaults to today"),
    days: int = Query(default=14, ge=1, le=MAX_BUNDLE_DAYS),
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> BundleOut:
    """Everything the client caches in one round trip.

    The client computes the current lesson/break state locally from this
    payload, so the widget keeps ticking with no network.
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

    return BundleOut(
        school_class=ClassOut(
            id=school_class.id,
            name=school_class.name,
            school=school_class.school,
            city=school_class.city,
            timezone=school_class.timezone_name,
        ),
        generated_at=datetime.now(school_class.tz).isoformat(),
        days=[_to_day_out(day) for day in resolved],
        next_school_day=_to_day_out(following) if following else None,
    )
