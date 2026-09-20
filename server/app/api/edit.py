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
from app.api.public import MAX_BUNDLE_START, MIN_BUNDLE_START, _homework_out, _today
from app.api.routing import DishkaAnnotatedRoute
from app.bot.render import human_date
from app.config import get_settings
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
from app.schedule import SCHOOL_YEAR_START_MONTH, school_year_bounds
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
    *,
    session: FromDishka[AsyncSession],
) -> HomeworkItemOut:
    """Upsert by (date, subject), exactly as the bot does: one assignment per
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
        f"{escape(notify.shorten(payload.text))}",
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
    *,
    session: FromDishka[AsyncSession],
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
# Substitutions
# --------------------------------------------------------------------------


async def _refuse_if_no_lesson_can_be_drawn(
    session: AsyncSession, school_class: SchoolClass, day: Date
) -> None:
    """Refuse a substitution on a day the resolver draws no lessons on at all.

    `_resolve_day` has two early returns above the override loop, and a
    substitution written for a day that takes either of them is stored, written
    to the audit log and announced to every subscriber — and drawn on no phone,
    in no widget, in no calendar feed. The two are out of season, and a day
    somebody marked «выходной» by hand.

    Both are asked here rather than re-read, so this endpoint and the resolver
    cannot answer differently. Note what is *not* asked: whether the day has a
    lesson at this number. It need not — a substitution at an empty number is
    how a lesson is added to a day, and the bell check above is what keeps that
    honest. The question is only whether this day draws lessons at all.
    """
    year_start, year_end = school_year_bounds(day)
    if not year_start <= day <= year_end:
        # Two different dates land here and they are not the same refusal.
        #
        # Note which comparison they fail: ``day > year_end`` is unreachable.
        # ``school_year_bounds`` files a date past the end of May under the
        # year that is *about to open*, so June, July and August come back
        # already before ``year_start`` — and so do the first days of
        # September in a year where the 1st is a Saturday, because
        # ``school_year_start`` moves the first teaching day off a weekend —
        # a Saturday 1st pushes to the 3rd, a Sunday 1st to the 2nd, and both
        # land here. The next four are 2029, 2030, 2035 and 2040. The month is
        # what tells them apart from the summer.
        #
        # It mattered because the one sentence they shared said «эта дата вне
        # учебного года … для летних дел есть события» — which on «1 сентября»
        # answers a question about a day three months earlier and reads as a
        # broken date picker rather than as the horizon it is. Whether those
        # two days should draw a six-day class's Saturday lessons at all is a
        # question about ``school_year_start``, and it is not answered here.
        if day.month >= SCHOOL_YEAR_START_MONTH:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    "учебный год начинается "
                    f"{year_start.day}.{year_start.month:02d} — "
                    "до него уроков ещё нет; поставьте событие"
                ),
            )
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "эта дата вне учебного года — замену ставить не на что; "
                "для летних дел есть события"
            ),
        )

    marked = await session.scalar(
        select(DayOverride).where(
            DayOverride.class_id == school_class.id, DayOverride.date == day
        )
    )
    if marked is not None and marked.kind is DayKind.HOLIDAY:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "этот день отмечен как выходной — уроков на нём нет; "
                "снимите отметку или поставьте событие"
            ),
        )


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
    *,
    session: FromDishka[AsyncSession],
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
    *,
    session: FromDishka[AsyncSession],
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
        # уроки» — shortened lessons. Nothing fails: the phone, the widget, the
        # calendar feed and
        # the morning digest all agree there is no school that day, and a
        # substitution written for it is accepted at any number because
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
