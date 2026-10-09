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
from html import escape
from typing import Any

from dishka.integrations.fastapi import FromDishka, inject
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class, current_device
from app.api.public import _homework_out
from app.api.routing import DishkaAnnotatedRoute
from app.config import get_settings
from app.models import (
    DayKind,
    DeviceToken,
    EventKind,
    LessonOverride,
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
from app.services import audit, clock, linking, notify, subjects, timetable_edit
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
    human_date,
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


async def _refuse_if_no_lesson_can_be_drawn(
    session: AsyncSession, school_class: SchoolClass, day: Date
) -> None:
    """Refuse a substitution on a day the resolver draws no lessons on at all.

    The rule and its three sentences live in
    `services/timetable_edit.why_no_lesson_can_be_drawn`, because the bot needs
    the same refusal and used to have none of it.
    """
    refusal = await timetable_edit.why_no_lesson_can_be_drawn(session, school_class.id, day)
    if refusal is not None:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=refusal)



@router.put("/overrides", response_model=OverrideOut)
async def override_put(
    payload: OverrideIn,
    device: DeviceToken = Depends(editor_device),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> OverrideOut:
    """One row per (date, lesson number). ``clear`` removes it, which is how
    a lesson goes back to the timetable."""
    _check_date(payload.date)
    actor = device.telegram_id
    when = human_date(payload.date, clock.today(school_class))
    existing = await session.scalar(
        select(LessonOverride).where(
            LessonOverride.class_id == school_class.id,
            LessonOverride.date == payload.date,
            LessonOverride.index == payload.index,
        )
    )

    if payload.action == "clear":
        if existing is not None:
            await session.delete(existing)
            await audit.record(
                session,
                school_class.id,
                actor,
                "override.clear",
                f"Замена снята: урок №{payload.index}, {when}",
            )
            await session.commit()
            await _tell(
                session,
                school_class,
                f"♻️ Урок №{payload.index} {escape(when)} снова идёт по расписанию.",
                kind="changes",
                author=actor,
            )
        return OverrideOut(date=payload.date, index=payload.index, action="clear")

    # Asked before the create/update split, and deliberately not inside it.
    # The bell check below is create-only on purpose — an existing row at a bad
    # number must stay editable, which is how a class gets out of one — but
    # these two are not that shape: a row already sitting on a summer date or a
    # hand-marked holiday is exactly the one whose re-announcement would say
    # «🔁 Замена» about a lesson nobody will ever see, and every row written
    # before this endpoint learned to refuse is such a row.
    await _refuse_if_no_lesson_can_be_drawn(session, school_class, payload.date)

    if existing is None:
        # A substitution at a number the day has no bell for is stored, written to
        # the log, announced to everybody with «🔁 Замена … урок №8» — and
        # drawn by nothing, because the resolver takes a lesson's times from
        # the bell row of the same number and drops what has none. The
        # timetable learned this; this, the other way a lesson changes, had no
        # check at all. Only on create: an existing row at a bad number must
        # stay clearable, which is how a class gets out of one.
        rung = await timetable_edit.rung_indexes_on(session, school_class.id, payload.date)
        if not timetable_edit.can_ring(rung, payload.index):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"нет звонка для урока №{payload.index} в этот день",
            )

    # Cancelling needs something to cancel, and so does a replacement that
    # names no subject. A substitution at an empty number is a legitimate
    # edit — it is how a lesson is *added* to a day — but only when it
    # brings a subject of its own: the resolver inherits the subject from
    # the template row under the override, and with no row and no subject
    # it has nothing to draw and drops it on the way out. Either way the
    # write would be stored, logged, and announced to everybody with
    # «🚫 Урок №7 отменён» or «🔁 Замена … кабинет/учитель» about a lesson
    # nobody can see. The bot reaches neither: it draws its «🚫» under a
    # lesson that exists and always asks for a typed subject. This is the
    # API-only half of an invariant the timetable already holds.
    #
    # Unlike the bell check above this one runs on update too, and it asks
    # `timetable_edit` rather than the resolver — because on update the
    # resolver would be answering about the row being edited. A substitution
    # that added «Астрономия» to an empty number makes the day report a
    # lesson at that number, so a second write carrying only a room passed
    # the check, cleared `subject_name`, and left a row the resolver drops:
    # announced to every subscriber, drawn nowhere, and no longer refusable
    # because the number now looked occupied.
    if payload.action == "cancel" or not payload.subject:
        on_template = await timetable_edit.template_indexes_on(
            session, school_class.id, payload.date
        )
        if payload.index not in on_template:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    f"в этот день нет урока №{payload.index}, отменять нечего"
                    if payload.action == "cancel"
                    else f"в этот день нет урока №{payload.index}: "
                    "замене без предмета нечего заменять"
                ),
            )

    if existing is None:
        existing = LessonOverride(
            class_id=school_class.id,
            date=payload.date,
            index=payload.index,
            action=OverrideAction.REPLACE,
        )
        session.add(existing)

    if payload.action == "cancel":
        existing.action = OverrideAction.CANCEL
        existing.subject_name = None
        existing.room = None
        existing.teacher = None
        existing.note = payload.note
        summary = f"Урок №{payload.index} отменён, {when}"
        text = f"🚫 Урок №{payload.index} {escape(when)} отменён."
    else:
        existing.action = OverrideAction.REPLACE
        # The class's spelling: the resolver looks a substitution's colour up by
        # exact name, so one sent in the wrong case draws grey among coloured
        # lessons.
        existing.subject_name = (
            await subjects.spelling(session, school_class.id, payload.subject)
            if payload.subject
            else payload.subject
        )
        existing.room = payload.room
        existing.teacher = payload.teacher
        existing.note = payload.note
        what = existing.subject_name or "кабинет/учитель"
        summary = f"Замена: урок №{payload.index}, {when} — {what}"
        text = (
            f"🔁 Замена {escape(when)}: урок №{payload.index} — <b>{escape(what)}</b>"
            + (f", каб. {escape(payload.room)}" if payload.room else "")
        )
    if payload.note:
        text += f"\n{escape(payload.note)}"

    await audit.record(session, school_class.id, actor, f"override.{payload.action}", summary)
    await session.commit()
    await _tell(session, school_class, text, kind="changes", author=actor)

    return OverrideOut(
        date=existing.date,
        index=existing.index,
        action=payload.action,
        subject=existing.subject_name,
        room=existing.room,
        teacher=existing.teacher,
        note=existing.note,
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
