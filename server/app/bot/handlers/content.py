"""Day-to-day editing: homework, замены and events. This is what an EDITOR does."""

from __future__ import annotations

from datetime import date as Date
from datetime import datetime, time

from aiogram import F, Router
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, InlineKeyboardButton, Message
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.keyboards import (
    EventAction,
    HomeworkAction,
    Menu,
    back_to_menu,
    cancel_keyboard,
    date_picker,
)
from app.bot.keyboards import (
    OverrideAction as OverrideCB,
)
from app.bot.render import human_date, render_homework_digest, upcoming_dates
from app.bot.states import AddEvent, AddHomework, AddOverride
from app.config import get_settings
from app.models import (
    DayEvent,
    EventKind,
    Homework,
    LessonOverride,
    OverrideAction,
    Role,
    SchoolClass,
)
from app.schedule import ScheduleResolver

router = Router(name="content")

EVENT_KIND_LABELS = {
    EventKind.CANTEEN: "🍽 Столовая",
    EventKind.EVENT: "🎉 Мероприятие",
    EventKind.EXAM: "📋 Контрольная",
    EventKind.TRIP: "🚌 Экскурсия",
    EventKind.MEETING: "👥 Собрание",
}


def _today(school_class: SchoolClass | None = None) -> Date:
    """Today in the class's own zone.

    Falls back to the server default only when no class is in scope, which in
    practice means a handler that has already refused the request.
    """
    tz = school_class.tz if school_class is not None else get_settings().tz
    return datetime.now(tz).date()


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
# Homework
# --------------------------------------------------------------------------


@router.callback_query(Menu.filter(F.action == "homework"))
async def homework_root(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return

    today = _today(school_class)
    days = await ScheduleResolver(session, school_class).resolve_range(today, 14)

    extra = []
    if role.at_least(Role.EDITOR):
        extra.append(
            [
                InlineKeyboardButton(
                    text="➕ Добавить ДЗ", callback_data=HomeworkAction(action="add").pack()
                )
            ]
        )
    await callback.message.edit_text(
        render_homework_digest(days, today), reply_markup=back_to_menu(extra)
    )
    await callback.answer()


@router.callback_query(HomeworkAction.filter(F.action == "add"))
async def homework_add(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await callback.answer("Нужна роль редактора", show_alert=True)
        return

    await callback.message.edit_text(
        "На какой день задано?",
        reply_markup=date_picker(
            HomeworkAction, "pick_day", upcoming_dates(_today(school_class), 7)
        ),
    )
    await state.set_state(AddHomework.date)
    await callback.answer()


@router.callback_query(AddHomework.date, HomeworkAction.filter(F.action == "pick_day"))
async def homework_pick_day(
    callback: CallbackQuery,
    callback_data: HomeworkAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
) -> None:
    if school_class is None:
        await callback.answer("Нет доступа", show_alert=True)
        return

    due = Date.fromisoformat(callback_data.value)
    await state.update_data(due=callback_data.value)

    days = await ScheduleResolver(session, school_class).resolve_range(due, 1)
    subjects = [lesson.subject for lesson in days[0].lessons if not lesson.is_cancelled]

    rows = [
        [
            InlineKeyboardButton(
                text=subject,
                callback_data=HomeworkAction(action="pick_subject", value=subject[:48]).pack(),
            )
        ]
        for subject in dict.fromkeys(subjects)
    ]
    prompt = (
        "По какому предмету?"
        if rows
        else "В этот день уроков нет — введите название предмета вручную:"
    )
    await callback.message.edit_text(prompt, reply_markup=back_to_menu(rows))
    await state.set_state(AddHomework.subject)
    await callback.answer()


@router.callback_query(AddHomework.subject, HomeworkAction.filter(F.action == "pick_subject"))
async def homework_pick_subject(
    callback: CallbackQuery,
    callback_data: HomeworkAction,
    state: FSMContext,
) -> None:
    await state.update_data(subject=callback_data.value)
    await callback.message.edit_text(
        f"Предмет: <b>{callback_data.value}</b>\n\nТеперь пришлите текст задания:",
        reply_markup=cancel_keyboard(),
    )
    await state.set_state(AddHomework.text)
    await callback.answer()


@router.message(AddHomework.subject)
async def homework_typed_subject(message: Message, state: FSMContext) -> None:
    subject = (message.text or "").strip()
    if not subject:
        await message.answer("Введите название предмета:")
        return
    await state.update_data(subject=subject[:120])
    await message.answer("Теперь пришлите текст задания:", reply_markup=cancel_keyboard())
    await state.set_state(AddHomework.text)


@router.message(AddHomework.text)
async def homework_text(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await state.clear()
        return

    text = (message.text or "").strip()
    if not text:
        await message.answer("Пустое задание. Пришлите текст:")
        return

    data = await state.get_data()
    due = Date.fromisoformat(data["due"])
    subject = data["subject"]

    existing = await session.scalar(
        select(Homework).where(
            Homework.class_id == school_class.id,
            Homework.due_date == due,
            Homework.subject_name == subject,
        )
    )
    if existing is not None:
        existing.text = text
        existing.created_by = message.from_user.id
        verb = "обновлено"
    else:
        session.add(
            Homework(
                class_id=school_class.id,
                due_date=due,
                subject_name=subject,
                text=text,
                created_by=message.from_user.id,
            )
        )
        verb = "добавлено"
    await session.commit()
    await state.clear()

    await message.answer(
        f"✅ Задание {verb}.\n\n<b>{subject}</b> {human_date(due, _today(school_class))}\n{text}",
        reply_markup=back_to_menu(),
    )


# --------------------------------------------------------------------------
# Замены
# --------------------------------------------------------------------------


@router.callback_query(Menu.filter(F.action == "overrides"))
async def overrides_root(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await callback.answer("Нужна роль редактора", show_alert=True)
        return

    await callback.message.edit_text(
        "🔄 <b>Замены</b>\n\nВыберите день:",
        reply_markup=date_picker(
            OverrideCB, "pick_day", upcoming_dates(_today(school_class), 7)
        ),
    )
    await state.set_state(AddOverride.date)
    await callback.answer()


@router.callback_query(AddOverride.date, OverrideCB.filter(F.action == "pick_day"))
async def override_pick_day(
    callback: CallbackQuery,
    callback_data: OverrideCB,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
) -> None:
    if school_class is None:
        await callback.answer("Нет доступа", show_alert=True)
        return

    target = Date.fromisoformat(callback_data.value)
    await state.update_data(date=callback_data.value)

    days = await ScheduleResolver(session, school_class).resolve_range(target, 1)
    rows = [
        [
            InlineKeyboardButton(
                text=f"{lesson.index}. {lesson.subject}"
                + (" (отменён)" if lesson.is_cancelled else ""),
                callback_data=OverrideCB(action="pick_index", value=str(lesson.index)).pack(),
            )
        ]
        for lesson in days[0].lessons
    ]
    if not rows:
        await callback.message.edit_text(
            "В этот день уроков нет — заменять нечего.", reply_markup=back_to_menu()
        )
        await state.clear()
        await callback.answer()
        return

    await callback.message.edit_text(
        f"{human_date(target, _today(school_class)).capitalize()}\n\nКакой урок меняем?",
        reply_markup=back_to_menu(rows),
    )
    await state.set_state(AddOverride.index)
    await callback.answer()


@router.callback_query(AddOverride.index, OverrideCB.filter(F.action == "pick_index"))
async def override_pick_index(
    callback: CallbackQuery,
    callback_data: OverrideCB,
    state: FSMContext,
) -> None:
    await state.update_data(index=callback_data.value)
    rows = [
        [
            InlineKeyboardButton(
                text="🚫 Отменить урок",
                callback_data=OverrideCB(action="cancel_lesson").pack(),
            )
        ],
        [
            InlineKeyboardButton(
                text="♻️ Вернуть по расписанию",
                callback_data=OverrideCB(action="clear").pack(),
            )
        ],
    ]
    await callback.message.edit_text(
        f"Урок №{callback_data.value}.\n\n"
        "Пришлите новый предмет — можно с кабинетом через запятую, "
        "например <code>Физика, 214</code>.\n"
        "Или выберите действие ниже:",
        reply_markup=back_to_menu(rows),
    )
    await state.set_state(AddOverride.subject)
    await callback.answer()


async def _save_override(
    session: AsyncSession,
    class_id: int,
    day: Date,
    index: int,
    action: OverrideAction,
    subject: str | None = None,
    room: str | None = None,
) -> None:
    existing = await session.scalar(
        select(LessonOverride).where(
            LessonOverride.class_id == class_id,
            LessonOverride.date == day,
            LessonOverride.index == index,
        )
    )
    if existing is None:
        existing = LessonOverride(class_id=class_id, date=day, index=index, action=action)
        session.add(existing)
    existing.action = action
    existing.subject_name = subject
    existing.room = room
    await session.commit()


@router.message(AddOverride.subject)
async def override_subject(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await state.clear()
        return

    raw = (message.text or "").strip()
    if not raw:
        await message.answer("Пришлите название предмета:")
        return

    subject, _, room = raw.partition(",")
    data = await state.get_data()
    day = Date.fromisoformat(data["date"])
    index = int(data["index"])

    await _save_override(
        session,
        school_class.id,
        day,
        index,
        OverrideAction.REPLACE,
        subject=subject.strip()[:120],
        room=(room.strip()[:32] or None),
    )
    await state.clear()
    await message.answer(
        f"✅ Замена сохранена: {human_date(day, _today(school_class))}, урок №{index} — "
        f"<b>{subject.strip()}</b>.",
        reply_markup=back_to_menu(),
    )


@router.callback_query(AddOverride.subject, OverrideCB.filter(F.action == "cancel_lesson"))
async def override_cancel(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await callback.answer("Нужна роль редактора", show_alert=True)
        return

    data = await state.get_data()
    day = Date.fromisoformat(data["date"])
    index = int(data["index"])
    await _save_override(session, school_class.id, day, index, OverrideAction.CANCEL)
    await state.clear()
    await callback.message.edit_text(
        f"🚫 Урок №{index} {human_date(day, _today(school_class))} отменён.",
        reply_markup=back_to_menu(),
    )
    await callback.answer()


@router.callback_query(AddOverride.subject, OverrideCB.filter(F.action == "clear"))
async def override_clear(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await callback.answer("Нужна роль редактора", show_alert=True)
        return

    data = await state.get_data()
    day = Date.fromisoformat(data["date"])
    index = int(data["index"])

    existing = await session.scalar(
        select(LessonOverride).where(
            LessonOverride.class_id == school_class.id,
            LessonOverride.date == day,
            LessonOverride.index == index,
        )
    )
    if existing is not None:
        await session.delete(existing)
        await session.commit()

    await state.clear()
    await callback.message.edit_text(
        f"♻️ Урок №{index} снова идёт по расписанию.", reply_markup=back_to_menu()
    )
    await callback.answer()


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

    await callback.message.edit_text(
        "🎉 <b>События</b>\n\nНа какой день добавляем?",
        reply_markup=date_picker(
            EventAction, "pick_day", upcoming_dates(_today(school_class), 7)
        ),
    )
    await state.set_state(AddEvent.date)
    await callback.answer()


@router.callback_query(AddEvent.date, EventAction.filter(F.action == "pick_day"))
async def event_pick_day(
    callback: CallbackQuery,
    callback_data: EventAction,
    state: FSMContext,
) -> None:
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

    data = await state.get_data()
    kind = EventKind(data["kind"])
    session.add(
        DayEvent(
            class_id=school_class.id,
            date=Date.fromisoformat(data["date"]),
            starts_at=time.fromisoformat(data["start"]),
            ends_at=time.fromisoformat(data["end"]),
            title=title[:200],
            kind=kind,
            # Только «мероприятие» и «экскурсия» обычно идут вместо уроков.
            covers_lesson=kind in {EventKind.EVENT, EventKind.TRIP},
        )
    )
    await session.commit()
    await state.clear()

    await message.answer(
        f"✅ Событие добавлено: <b>{title}</b> "
        f"{human_date(Date.fromisoformat(data['date']), _today(school_class))}, "
        f"{data['start'][:5]}–{data['end'][:5]}.",
        reply_markup=back_to_menu(),
    )
