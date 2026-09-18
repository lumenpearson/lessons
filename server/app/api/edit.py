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

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class, current_device
from app.api.public import MAX_BUNDLE_START, MIN_BUNDLE_START, _homework_out, _today
from app.bot.render import human_date
from app.config import get_settings
from app.db import get_session
from app.models import (
    BellPeriod,
    BellSchedule,
    DayEvent,
    DayKind,
    DayOverride,
    DeviceToken,
    EventKind,
    Homework,
    LessonOverride,
    OverrideAction,
    Role,
    SchoolClass,
)
from app.schedule import ScheduleResolver
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
from app.services import audit, linking, notify, subjects, timetable_edit
from app.services import homework as homework_service
from app.services import tasks as task_service

log = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["edit"])


# --------------------------------------------------------------------------
# Who may write
# --------------------------------------------------------------------------


async def editor_device(
    device: DeviceToken = Depends(current_device),
    session: AsyncSession = Depends(get_session),
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
    """Same bounds as ``/bundle``: the resolver does arithmetic on top of it."""
    if not (MIN_BUNDLE_START <= day <= MAX_BUNDLE_START):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"date must be between {MIN_BUNDLE_START.isoformat()} "
                f"and {MAX_BUNDLE_START.isoformat()}"
            ),
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
    from app.bot.bot import build_bot

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
    session: AsyncSession = Depends(get_session),
) -> HomeworkItemOut:
    """Upsert by (date, subject), exactly as the bot does: one задание per
    subject per day, and sending it again replaces the text."""
    _check_date(payload.due_date)
    actor = device.telegram_id
    existing, created = await homework_service.upsert(
        session,
        school_class.id,
        payload.due_date,
        payload.subject,
        payload.text,
        actor,
        attachment_url=payload.attachment_url,
    )
    subject_name = existing.subject_name
    action, verb = ("homework.add", "добавлено") if created else ("homework.update", "обновлено")

    when = human_date(payload.due_date, _today(school_class))
    await audit.record(
        session, school_class.id, actor, action, f"ДЗ {verb}: {subject_name}, {when}"
    )
    await session.commit()
    await session.refresh(existing)

    await _tell(
        session,
        school_class,
        f"📝 Задание {verb}: <b>{escape(subject_name)}</b> {escape(when)}\n"
        f"{escape(payload.text)}",
        kind="homework",
        author=actor,
    )
    ticks = await task_service.homework_ticks(session, actor, [existing.id])
    return _homework_out(existing, existing.id in ticks)


@router.delete("/homework/{homework_id}", response_model=DeletedOut)
async def homework_delete(
    homework_id: int,
    device: DeviceToken = Depends(editor_device),
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> DeletedOut:
    item = await session.scalar(
        select(Homework).where(Homework.id == homework_id, Homework.class_id == school_class.id)
    )
    if item is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown homework")

    when = human_date(item.due_date, _today(school_class))
    subject = item.subject_name
    await audit.record(
        session,
        school_class.id,
        device.telegram_id,
        "homework.delete",
        f"ДЗ удалено: {subject}, {when}",
    )
    await session.delete(item)
    await session.commit()

    await _tell(
        session,
        school_class,
        f"🗑 Задание удалено: <b>{escape(subject)}</b> {escape(when)}",
        kind="homework",
        author=device.telegram_id,
    )
    return DeletedOut(id=homework_id)


# --------------------------------------------------------------------------
# Замены
# --------------------------------------------------------------------------


@router.put("/overrides", response_model=OverrideOut)
async def override_put(
    payload: OverrideIn,
    device: DeviceToken = Depends(editor_device),
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> OverrideOut:
    """One row per (date, lesson number). ``clear`` removes it, which is how
    a lesson goes back to the timetable."""
    _check_date(payload.date)
    actor = device.telegram_id
    when = human_date(payload.date, _today(school_class))
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

    if existing is None:
        # A замена at a number the day has no bell for is stored, written to
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
        if payload.action == "cancel":
            # Cancelling needs something to cancel. A замена at an empty number
            # is a legitimate edit — it is how a lesson is *added* to a day —
            # but «🚫 Урок №7 отменён» about a number nobody was going to be at
            # goes into the log and into everybody's chat, and the resolver
            # drops the row on the way out because it only cancels a lesson the
            # day actually has. The bot cannot reach this: it draws its «🚫»
            # under a lesson that exists.
            day = (await ScheduleResolver(session, school_class).resolve_range(payload.date, 1))[0]
            if payload.index not in {lesson.index for lesson in day.lessons}:
                raise HTTPException(
                    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                    detail=f"в этот день нет урока №{payload.index}, отменять нечего",
                )
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
        # The class's spelling: the resolver looks a замена's colour up by
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
    session: AsyncSession = Depends(get_session),
) -> EventCreatedOut:
    """Events have no natural key - two «Обед» rows on one day are two breaks
    - so this always creates; ``DELETE`` is how one goes away."""
    _check_date(payload.date)
    actor = device.telegram_id
    kind = EventKind(payload.kind)
    covers = (
        payload.covers_lesson
        if payload.covers_lesson is not None
        else kind in {EventKind.EVENT, EventKind.TRIP}
    )
    event = DayEvent(
        class_id=school_class.id,
        date=payload.date,
        starts_at=payload.starts_at,
        ends_at=payload.ends_at,
        title=payload.title,
        kind=kind,
        location=payload.location,
        covers_lesson=covers,
    )
    session.add(event)

    when = human_date(payload.date, _today(school_class))
    span = f"{payload.starts_at:%H:%M}–{payload.ends_at:%H:%M}"
    await audit.record(
        session, school_class.id, actor, "event.add", f"Событие: {payload.title}, {when} {span}"
    )
    await session.commit()
    await session.refresh(event)

    await _tell(
        session,
        school_class,
        f"📅 Событие: <b>{escape(payload.title)}</b> {escape(when)}, {span}"
        + (f", {escape(payload.location)}" if payload.location else ""),
        kind="changes",
        author=actor,
    )
    return EventCreatedOut(id=event.id)


@router.delete("/events/{event_id}", response_model=DeletedOut)
async def event_delete(
    event_id: int,
    device: DeviceToken = Depends(editor_device),
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> DeletedOut:
    event = await session.scalar(
        select(DayEvent).where(DayEvent.id == event_id, DayEvent.class_id == school_class.id)
    )
    if event is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown event")

    when = human_date(event.date, _today(school_class))
    title = event.title
    await audit.record(
        session,
        school_class.id,
        device.telegram_id,
        "event.delete",
        f"Событие удалено: {title}, {when}",
    )
    await session.delete(event)
    await session.commit()

    await _tell(
        session,
        school_class,
        f"🗑 Событие отменено: <b>{escape(title)}</b> {escape(when)}",
        kind="changes",
        author=device.telegram_id,
    )
    return DeletedOut(id=event_id)


# --------------------------------------------------------------------------
# Whole days
# --------------------------------------------------------------------------

_DAY_LABELS = {
    DayKind.HOLIDAY: "выходной",
    DayKind.SHORTENED: "сокращённые уроки",
    DayKind.REMOTE: "дистанционное обучение",
}


@router.put("/days", response_model=DayOverrideOut)
async def day_put(
    payload: DayIn,
    device: DeviceToken = Depends(editor_device),
    school_class: SchoolClass = Depends(current_class),
    session: AsyncSession = Depends(get_session),
) -> DayOverrideOut:
    """Mark a date as a holiday / shortened / remote day. ``normal`` deletes
    the mark, so a day never carries a row that says nothing."""
    _check_date(payload.date)
    actor = device.telegram_id
    when = human_date(payload.date, _today(school_class))

    if payload.bell_schedule_id is not None:
        owned = await session.scalar(
            select(BellSchedule.id).where(
                BellSchedule.id == payload.bell_schedule_id,
                BellSchedule.class_id == school_class.id,
            )
        )
        if owned is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="bell_schedule_id is not in this class",
            )
        # And that it rings something. A schedule may legitimately be created
        # empty and filled in later (`BellScheduleIn.periods` defaults to an
        # empty list and says so), and the resolver takes a lesson's times from
        # the bell row of the same number - so a day pointed at an empty one
        # draws no lessons at all while the card above them says «сокращённые
        # уроки». Nothing fails: the phone, the widget, the calendar feed and
        # the morning digest all agree there is no school that day, and a
        # замена written for it is accepted at any number because
        # `timetable_edit.rung_indexes_on` falls back to the class default when
        # the named schedule has no rows. This is the same decision the check
        # underneath already makes for a shortened day with no schedule at all,
        # and it is made for the same reason.
        rings = await session.scalar(
            select(BellPeriod.id)
            .where(BellPeriod.schedule_id == payload.bell_schedule_id)
            .limit(1)
        )
        if rings is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="в этом расписании звонков нет ни одного урока",
            )

    # «Сокращённые уроки» is a claim about the times, and the times come from a
    # bell schedule. Without one the resolver falls back to the class default,
    # so the day announces shortened lessons and then draws the normal ones —
    # which is worse than not marking it at all, because somebody reads the
    # label and packs for a short day. The bot's flow always asks which
    # schedule to ring; this is the surface that could skip the question.
    if payload.kind == "shortened" and payload.bell_schedule_id is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="a shortened day needs the bell schedule it rings",
        )

    existing = await session.scalar(
        select(DayOverride).where(
            DayOverride.class_id == school_class.id, DayOverride.date == payload.date
        )
    )

    if payload.kind == "normal":
        if existing is not None:
            await session.delete(existing)
            await audit.record(
                session, school_class.id, actor, "day.clear", f"День снова обычный: {when}"
            )
            await session.commit()
            await _tell(
                session,
                school_class,
                f"📆 {escape(when)} — обычный учебный день.",
                kind="changes",
                author=actor,
            )
        return DayOverrideOut(date=payload.date, kind="normal")

    kind = DayKind(payload.kind)
    if existing is None:
        existing = DayOverride(class_id=school_class.id, date=payload.date, kind=kind)
        session.add(existing)
    existing.kind = kind
    existing.note = payload.note
    existing.bell_schedule_id = payload.bell_schedule_id

    label = _DAY_LABELS[kind]
    await audit.record(session, school_class.id, actor, "day.set", f"{when}: {label}")
    await session.commit()

    text = f"📆 {escape(when)} — {label}."
    if payload.note:
        text += f"\n{escape(payload.note)}"
    await _tell(session, school_class, text, kind="changes", author=actor)

    return DayOverrideOut(
        date=existing.date,
        kind=existing.kind.value,
        note=existing.note,
        bell_schedule_id=existing.bell_schedule_id,
    )
