"""Writes to the class's shared data from a linked phone.

Every endpoint here does what the matching bot flow does, with the same
rules, because it *is* the same person: a device token on its own is
read-only, and what turns it into an editor is the Telegram account it was
linked to and that account's role in the class - looked up on every request
by ``linking.effective_role``, so a revoke in the bot takes effect on the
next tap in the app. There is no second permission system.

Each write lands in the audit log under the linked account, and reaches the
class's subscribers the way a bot edit does. The bot is built for that one
send and closed again: on a serverless deployment the request is the whole
life of the process.
"""

from __future__ import annotations

import logging
from datetime import date as Date
from typing import Any

from dishka.integrations.fastapi import FromDishka, inject
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class, current_device
from app.api.public import _homework_out
from app.api.routing import DishkaAnnotatedRoute
from app.config import get_settings
from app.models import (
    DayKind,
    DeviceToken,
    EventKind,
    OverrideAction,
    Role,
    SchoolClass,
)
from app.schemas import (
    DayIn,
    DayOverrideOut,
    DeletedOut,
    EventCreatedOut,
    EventIn,
    HomeworkIn,
    HomeworkItemOut,
    OverrideIn,
    OverrideOut,
)
from app.services import clock, linking, notify, substitutions
from app.services import events as events_service
from app.services import homework as homework_service
from app.services import tasks as task_service
from app.services.manage import bells as bells_service
from app.services.manage import special_days
from app.wording import (
    EMPTY_BELL_SCHEDULE_DETAIL,
    SCHEDULE_NOT_IN_CLASS_DETAIL,
    SHORTENED_NEEDS_SCHEDULE_DETAIL,
    UNKNOWN_EVENT_DETAIL,
    UNKNOWN_HOMEWORK_DETAIL,
    lesson_not_on_timetable_detail,
    no_bell_detail,
)

log = logging.getLogger(__name__)

router = APIRouter(route_class=DishkaAnnotatedRoute, prefix="/api/v1", tags=["edit"])


# --------------------------------------------------------------------------
# Who may write
# --------------------------------------------------------------------------


@inject
async def editor_device(
    device: DeviceToken = Depends(current_device),
    *,
    session: FromDishka[AsyncSession],
) -> DeviceToken:
    """The device, provided it is linked to an account that may edit.

    Two distinct 403s on purpose: the app shows «привяжите телефон» for one
    and «попросите роль редактора» for the other, and they are different
    screens.
    """
    if device.telegram_id is None:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="device is not linked")
    role = await linking.effective_role(session, device)
    if role is None or not role.at_least(Role.EDITOR):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="editor role required")
    return device


def _check_date(day: Date) -> None:
    """Same bounds as ``/bundle``: the resolver does arithmetic on top of it.
    The sentence is ``clock``'s, which v2's writes answer with too."""
    if not clock.in_bounds(day):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=clock.DATE_OUT_OF_BOUNDS
        )


# --------------------------------------------------------------------------
# Telling the class
# --------------------------------------------------------------------------


def _build_bot() -> Any | None:
    """A bot for one send, or ``None`` when the deployment has no token.

    Imported lazily: aiogram costs seconds to import and a write endpoint
    should not pay that on a deployment that has no bot to notify with.
    """
    if not get_settings().bot_token:
        return None
    from app.telegram_send import build_bot

    return build_bot()


async def _tell(
    session: AsyncSession,
    school_class: SchoolClass,
    text: str,
    *,
    kind: str,
    author: int,
) -> None:
    """Notify subscribers after the commit. Never fails the request: the edit
    is already saved, and a Telegram outage is not a reason to tell the
    editor it was not."""
    bot = _build_bot()
    if bot is None:
        return
    try:
        await notify.notify_subscribers(session, bot, school_class, text, kind=kind, exclude=author)
    except Exception:  # noqa: BLE001 - see the docstring
        log.warning("could not notify class %s", school_class.id, exc_info=True)
    finally:
        bot_session = getattr(bot, "session", None)
        if bot_session is not None:
            await bot_session.close()


# --------------------------------------------------------------------------
# Homework
# --------------------------------------------------------------------------


@router.put("/homework", response_model=HomeworkItemOut)
async def homework_put(
    payload: HomeworkIn,
    device: DeviceToken = Depends(editor_device),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> HomeworkItemOut:
    """Upsert by (date, subject), exactly as the bot does: one assignment per
    subject per day, and sending it again replaces the text. The line and the
    notice are ``homework.put``'s, which v2's writes share."""
    _check_date(payload.due_date)
    actor = device.telegram_id
    saved = await homework_service.put(
        session,
        school_class,
        actor,
        payload.due_date,
        payload.subject,
        payload.text,
        attachment_url=payload.attachment_url,
    )
    await session.commit()
    await session.refresh(saved.homework)

    await _tell(session, school_class, saved.notice, kind="homework", author=actor)
    ticks = await task_service.homework_ticks(session, actor, [saved.homework.id])
    return _homework_out(saved.homework, saved.homework.id in ticks)


@router.delete("/homework/{homework_id}", response_model=DeletedOut)
async def homework_delete(
    homework_id: int,
    device: DeviceToken = Depends(editor_device),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> DeletedOut:
    item = await homework_service.homework_of(session, school_class.id, homework_id)
    if item is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=UNKNOWN_HOMEWORK_DETAIL)

    notice = await homework_service.delete(session, school_class, device.telegram_id, item)
    await session.commit()

    await _tell(session, school_class, notice, kind="homework", author=device.telegram_id)
    return DeletedOut(id=homework_id)


# --------------------------------------------------------------------------
# Substitutions
# --------------------------------------------------------------------------


@router.put("/overrides", response_model=OverrideOut)
async def override_put(
    payload: OverrideIn,
    device: DeviceToken = Depends(editor_device),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> OverrideOut:
    """One row per (date, lesson number). ``clear`` removes it, which is how
    a lesson goes back to the timetable. The three questions a substitution
    is asked, the row, its line and its notice are
    ``services/substitutions.py``'s, which the bot's «🔄 Замены» and v2's
    ``SubstitutionService`` ask and write through too."""
    _check_date(payload.date)
    actor = device.telegram_id

    if payload.action == "clear":
        existing = await substitutions.substitution_on(
            session, school_class.id, payload.date, payload.index
        )
        if existing is not None:
            notice = await substitutions.delete(session, school_class, actor, existing)
            await session.commit()
            await _tell(session, school_class, notice, kind="changes", author=actor)
        return OverrideOut(date=payload.date, index=payload.index, action="clear")

    try:
        written = await substitutions.put(
            session,
            school_class,
            actor,
            payload.date,
            payload.index,
            {
                "action": OverrideAction(payload.action),
                "subject": payload.subject,
                "room": payload.room,
                "teacher": payload.teacher,
                "note": payload.note,
            },
        )
    except substitutions.NoLessonOnDay as refused:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=refused.sentence
        ) from None
    except substitutions.NoBellForLesson as refused:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=no_bell_detail(refused.index),
        ) from None
    except substitutions.LessonNotOnTimetable as refused:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=lesson_not_on_timetable_detail(refused.index, cancelling=refused.cancelling),
        ) from None
    await session.commit()
    await _tell(session, school_class, written.notice, kind="changes", author=actor)

    row = written.substitution
    return OverrideOut(
        date=row.date,
        index=row.index,
        action=payload.action,
        subject=row.subject_name,
        room=row.room,
        teacher=row.teacher,
        note=row.note,
    )


# --------------------------------------------------------------------------
# Events
# --------------------------------------------------------------------------


@router.put("/events", response_model=EventCreatedOut, status_code=status.HTTP_201_CREATED)
async def event_put(
    payload: EventIn,
    device: DeviceToken = Depends(editor_device),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> EventCreatedOut:
    """Events have no natural key - two «Обед» rows on one day are two breaks
    - so this always creates; ``DELETE`` is how one goes away. The row, its
    line and its notice are ``events.create``'s, which v2's ``CreateEvent``
    calls too."""
    _check_date(payload.date)
    actor = device.telegram_id
    written = await events_service.create(
        session,
        school_class,
        actor,
        date=payload.date,
        starts_at=payload.starts_at,
        ends_at=payload.ends_at,
        title=payload.title,
        kind=EventKind(payload.kind),
        location=payload.location,
        covers_lesson=payload.covers_lesson,
    )
    await session.commit()
    await session.refresh(written.event)

    await _tell(session, school_class, written.notice, kind="changes", author=actor)
    return EventCreatedOut(id=written.event.id)


@router.delete("/events/{event_id}", response_model=DeletedOut)
async def event_delete(
    event_id: int,
    device: DeviceToken = Depends(editor_device),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> DeletedOut:
    event = await events_service.event_of(session, school_class.id, event_id)
    if event is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=UNKNOWN_EVENT_DETAIL)

    notice = await events_service.delete(session, school_class, device.telegram_id, event)
    await session.commit()

    await _tell(session, school_class, notice, kind="changes", author=device.telegram_id)
    return DeletedOut(id=event_id)


# --------------------------------------------------------------------------
# Whole days
# --------------------------------------------------------------------------


@router.put("/days", response_model=DayOverrideOut)
async def day_put(
    payload: DayIn,
    device: DeviceToken = Depends(editor_device),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> DayOverrideOut:
    """Mark a date as a holiday / shortened / remote day. ``normal`` deletes
    the mark, so a day never carries a row that says nothing. The checks, the
    mark, its line and its notice are ``special_days.set_day``'s, over the one
    write the bot's «🏖 Особые дни» and v2's ``UpdateDay`` make too."""
    _check_date(payload.date)
    actor = device.telegram_id
    try:
        written = await special_days.set_day(
            session,
            school_class,
            actor,
            payload.date,
            kind=DayKind(payload.kind),
            note=payload.note,
            bell_schedule_id=payload.bell_schedule_id,
        )
    except special_days.ScheduleNotInClass:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=SCHEDULE_NOT_IN_CLASS_DETAIL
        ) from None
    except bells_service.ScheduleEmpty:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=EMPTY_BELL_SCHEDULE_DETAIL
        ) from None
    except special_days.ShortenedNeedsSchedule:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=SHORTENED_NEEDS_SCHEDULE_DETAIL,
        ) from None
    await session.commit()
    if written.notice is not None:
        await _tell(session, school_class, written.notice, kind="changes", author=actor)

    mark = written.mark
    if mark is None:
        return DayOverrideOut(date=payload.date, kind="normal")
    return DayOverrideOut(
        date=mark.date,
        kind=mark.kind.value,
        note=mark.note,
        bell_schedule_id=mark.bell_schedule_id,
    )
