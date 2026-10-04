"""«🎉 События»: something on a day that is not a lesson.

Part of :mod:`app.bot.handlers.content`; the order every write here follows —
save, audit line, then announce — is in that package's docstring.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import time
from html import escape

from aiogram import F, Router
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, InlineKeyboardButton, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.content_keyboard import EventAction
from app.bot.handlers.calendar import open_month
from app.bot.handlers.content._common import (
    BAD_DATE,
    BAD_PICK,
    _date_or_none,
    _kind_or_none,
    _today,
)
from app.bot.keyboards import Menu, back_to_menu, cancel_keyboard
from app.bot.render import human_date, relative_day_name
from app.bot.states import AddEvent
from app.models import DayEvent, EventKind, Role, SchoolClass
from app.services import audit, notify

router = Router(name="content.events")

EVENT_KIND_LABELS = {
    EventKind.CANTEEN: "🍽 Столовая",
    EventKind.EVENT: "🎉 Мероприятие",
    EventKind.EXAM: "📋 Контрольная",
    EventKind.TRIP: "🚌 Экскурсия",
    EventKind.MEETING: "👥 Собрание",
}


def _parse_time_range(raw: str) -> tuple[time, time] | None:
    """Accepts "12:30-13:15" or "12:30 13:15"."""
    parts = raw.replace("—", "-").replace("–", "-").replace("-", " ").split()
    if len(parts) != 2:
        return None
    try:
        start = time.fromisoformat(parts[0] if len(parts[0]) > 2 else f"{parts[0]}:00")
        end = time.fromisoformat(parts[1] if len(parts[1]) > 2 else f"{parts[1]}:00")
    except ValueError:
        return None
    return (start, end) if start < end else None


# --------------------------------------------------------------------------
# Events
# --------------------------------------------------------------------------


@router.callback_query(Menu.filter(F.action == "events"))
async def events_root(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await callback.answer("Нужна роль редактора", show_alert=True)
        return

    await state.clear()
    await callback.message.edit_text(
        "🎉 <b>События</b>\n\nНа какой день добавляем?",
        reply_markup=open_month("ev", _today(school_class)),
    )
    await callback.answer()


@router.callback_query(EventAction.filter(F.action == "pick_day"))
async def event_pick_day(
    callback: CallbackQuery,
    callback_data: EventAction,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await callback.answer("Нужна роль редактора", show_alert=True)
        return

    # Parsed here even though nothing needs the value until the last step:
    # otherwise an unreadable date is carried through three questions and then
    # raises out of the handler that finally reads it, which looks to the user
    # like the answer they just typed was the problem.
    if _date_or_none(callback_data.value) is None:
        await callback.answer(BAD_DATE, show_alert=True)
        return

    await state.update_data(date=callback_data.value)
    rows = [
        [
            InlineKeyboardButton(
                text=label,
                callback_data=EventAction(action="pick_kind", value=kind.value).pack(),
            )
        ]
        for kind, label in EVENT_KIND_LABELS.items()
    ]
    await callback.message.edit_text("Что это за событие?", reply_markup=back_to_menu(rows))
    await state.set_state(AddEvent.kind)
    await callback.answer()


@router.callback_query(AddEvent.kind, EventAction.filter(F.action == "pick_kind"))
async def event_pick_kind(
    callback: CallbackQuery,
    callback_data: EventAction,
    state: FSMContext,
) -> None:
    if _kind_or_none(callback_data.value) is None:
        await callback.answer(BAD_PICK, show_alert=True)
        return

    await state.update_data(kind=callback_data.value)
    await callback.message.edit_text(
        "Во сколько? Пришлите интервал, например <code>12:30-13:15</code>.",
        reply_markup=cancel_keyboard(),
    )
    await state.set_state(AddEvent.time)
    await callback.answer()


@router.message(AddEvent.time)
async def event_time(message: Message, state: FSMContext) -> None:
    parsed = _parse_time_range(message.text or "")
    if parsed is None:
        await message.answer(
            "Не понял время. Формат: <code>12:30-13:15</code>, начало раньше конца."
        )
        return
    start, end = parsed
    await state.update_data(start=start.isoformat(), end=end.isoformat())
    await message.answer("Как назовём событие?", reply_markup=cancel_keyboard())
    await state.set_state(AddEvent.title)


@router.message(AddEvent.title)
async def event_title(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await state.clear()
        return

    title = (message.text or "").strip()
    if not title:
        await message.answer("Пришлите название события:")
        return

    # Cut once, like the substitution in ``overrides.py``, and then stored, logged, shown
    # back and announced as the same string. Echoed whole it both promised a
    # name the row had not kept and, at 4096 characters typed here, made the
    # confirmation 4164 — a message Telegram refuses, sent after the event was
    # committed, from a handler with nothing to apologise on.
    title = title[:200]

    data = await state.get_data()
    kind = EventKind(data["kind"])
    day = Date.fromisoformat(data["date"])
    session.add(
        DayEvent(
            class_id=school_class.id,
            date=day,
            starts_at=time.fromisoformat(data["start"]),
            ends_at=time.fromisoformat(data["end"]),
            title=title,
            kind=kind,
            # Only «мероприятие» and «экскурсия» usually replace a lesson.
            covers_lesson=kind in {EventKind.EVENT, EventKind.TRIP},
        )
    )
    await audit.record(
        session,
        school_class.id,
        message.from_user.id,
        "event.add",
        f"событие {day:%d.%m} {data['start'][:5]}: {title}",
    )
    await session.commit()
    await state.clear()

    today = _today(school_class)
    when = f"{data['start'][:5]}–{data['end'][:5]}"
    await message.answer(
        f"✅ Событие добавлено: <b>{escape(title)}</b> "
        f"{human_date(day, today)}, {when}.",
        reply_markup=back_to_menu(),
    )
    await notify.notify_subscribers(
        session,
        getattr(message, "bot", None),
        school_class,
        # ``shorten`` for the same reason the announcements in ``homework.py``
        # and ``overrides.py`` use it.
        f"{EVENT_KIND_LABELS.get(kind, '🎉')} <b>{escape(notify.shorten(title))}</b> "
        f"{relative_day_name(day, today)}, {when}.",
        kind="changes",
        exclude=message.from_user.id,
    )
