"""Read-only endpoints consumed by the Android app and its widget."""

from __future__ import annotations

from datetime import date as Date
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class
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
from app.security import hash_token, new_token

router = APIRouter(prefix="/api/v1", tags=["client"])

MAX_BUNDLE_DAYS = 31


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
    payload: JoinRequest,
    session: AsyncSession = Depends(get_session),
) -> JoinResponse:
    """Exchange a class join code for a long-lived read-only device token."""
    code = payload.code.strip().upper()
    school_class = await session.scalar(select(SchoolClass).where(SchoolClass.join_code == code))
    if school_class is None:
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
