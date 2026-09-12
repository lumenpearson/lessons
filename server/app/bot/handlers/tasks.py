"""Personal tasks and «сделал» ticks on homework.

Everything here belongs to the person pressing the button, never to the
class: a task is always looked up by (id, class, owner) through
``services.tasks.get_task``, and a homework tick is a row keyed by the
presser's Telegram id. Ids travel in callback data, which the user controls,
so an id on its own is never trusted.

Adding a task is one message in the class chat's own dialect - «Купить
тетрадь до 15.09 в 18:00 !» - because a date picker on a phone is slower than
typing, and the grammar is small enough to remember.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import datetime, time, timedelta
from html import escape

from aiogram import F, Router
from aiogram.filters import Command, CommandObject
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.keyboards import (
    HomeworkAction,
    HomeworkTick,
    Menu,
    TaskAction,
    cancel_keyboard,
    cut,
    task_delete_confirm,
    task_delete_picker,
    task_list_keyboard,
    task_remind_keyboard,
)
from app.bot.render import (
    WEEKDAYS_SHORT,
    human_date,
    render_homework_digest,
    render_task_list,
    render_task_saved,
)
from app.bot.states import AddTask
from app.models import Homework, PersonalTask, Role, SchoolClass
from app.schedule import ResolvedDay, ScheduleResolver
from app.services import tasks as task_service

router = Router(name="tasks")

TASK_HELP = (
    "Напишите задачу одним сообщением. Срок, время и важность можно указать "
    "прямо в тексте:\n\n"
    "<code>Купить тетрадь до 15.09 в 18:00 !</code>\n\n"
    "Понимаю «до 15.09», «завтра», «послезавтра», «в пятницу», «к понедельнику», "
    "«в 18:00»; «!» — важно, «?» — не срочно."
)

#: Homework buttons in one keyboard. More than this does not fit on a phone
#: above the message, and two weeks of homework rarely reach it.
HOMEWORK_BUTTONS_MAX = 12

#: How far ahead the digest looks, in days - the same window the app shows.
HOMEWORK_DAYS = 14

NO_ACCESS = "Нет доступа. Откройте /start, чтобы получить его."


def _now(school_class: SchoolClass) -> datetime:
    """Now, in the class's own zone rather than the server's."""
    return datetime.now(school_class.tz)


def _int_or_none(raw: str) -> int | None:
    """An id from callback data, or ``None`` for anything a crafted one might carry."""
    try:
        return int(raw)
    except (TypeError, ValueError):
        return None


# --------------------------------------------------------------------------
# Task list
# --------------------------------------------------------------------------


async def task_view(
    session: AsyncSession, school_class: SchoolClass, telegram_id: int, show_done: bool
) -> tuple[str, InlineKeyboardMarkup]:
    """Text and keyboard of «Мои задачи» for one person."""
    tasks = await task_service.list_tasks(
        session, school_class.id, telegram_id, include_done=show_done
    )
    today = _now(school_class).date()
    return render_task_list(tasks, today, show_done), task_list_keyboard(tasks, show_done)


@router.message(Command("tasks"))
async def cmd_tasks(
    message: Message,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await message.answer(NO_ACCESS)
        return
    text, keyboard = await task_view(session, school_class, message.from_user.id, False)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(Menu.filter(F.action == "tasks"))
async def menu_tasks(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    await state.clear()
    text, keyboard = await task_view(session, school_class, callback.from_user.id, False)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


@router.callback_query(TaskAction.filter(F.action == "list"))
async def task_list(
    callback: CallbackQuery,
    callback_data: TaskAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    await state.clear()
    text, keyboard = await task_view(
        session, school_class, callback.from_user.id, bool(callback_data.show_done)
    )
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


@router.callback_query(TaskAction.filter(F.action == "done"))
async def task_toggle_done(
    callback: CallbackQuery,
    callback_data: TaskAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    task_id = _int_or_none(callback_data.value)
    task = (
        await task_service.get_task(session, task_id, school_class.id, callback.from_user.id)
        if task_id is not None
        else None
    )
    if task is None:
        await callback.answer("Задача уже удалена", show_alert=True)
        return

    await task_service.set_done(session, task, not task.done)
    text, keyboard = await task_view(
        session, school_class, callback.from_user.id, bool(callback_data.show_done)
    )
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer("Сделано ✅" if task.done else "Снова в работе")


# --------------------------------------------------------------------------
# Adding
# --------------------------------------------------------------------------


@router.callback_query(TaskAction.filter(F.action == "add"))
async def task_add_prompt(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    await callback.message.edit_text(TASK_HELP, reply_markup=cancel_keyboard())
    await state.set_state(AddTask.text)
    await callback.answer()


async def _save_from_text(
    message: Message, session: AsyncSession, school_class: SchoolClass, raw: str
) -> None:
    """Parse, save, confirm - shared by the FSM step and the ``/task`` shortcut."""
    today = _now(school_class).date()
    title, due_date, due_time, priority = task_service.parse_task_text(raw, today)
    if not title:
        await message.answer(
            "Не понял, что за задача - в сообщении остались только дата и время. "
            "Напишите, что нужно сделать:",
            reply_markup=cancel_keyboard(),
        )
        return

    task = await task_service.add_task(
        session,
        school_class.id,
        message.from_user.id,
        title,
        due_date=due_date,
        due_time=due_time,
        priority=priority,
    )

    text = render_task_saved(task, today)
    if task.due_date is not None:
        text += "\n\nНапомнить?"
        keyboard = task_remind_keyboard(task.id, task.due_time is not None)
    else:
        keyboard = InlineKeyboardMarkup(
            inline_keyboard=[
                [
                    InlineKeyboardButton(
                        text="‹ К списку", callback_data=TaskAction(action="list").pack()
                    )
                ]
            ]
        )
    await message.answer(text, reply_markup=keyboard)


@router.message(AddTask.text)
async def task_add_text(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await state.clear()
        return
    raw = (message.text or "").strip()
    if not raw:
        await message.answer("Пришлите текст задачи:", reply_markup=cancel_keyboard())
        return
    await state.clear()
    await _save_from_text(message, session, school_class, raw)


@router.message(Command("task"))
async def cmd_task(
    message: Message,
    command: CommandObject,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """``/task Купить тетрадь до 15.09 в 18:00 !`` - the one-shot form."""
    if school_class is None or role is None:
        await message.answer(NO_ACCESS)
        return
    raw = (command.args or "").strip()
    if not raw:
        await message.answer(TASK_HELP, reply_markup=cancel_keyboard())
        await state.set_state(AddTask.text)
        return
    await state.clear()
    await _save_from_text(message, session, school_class, raw)


# --------------------------------------------------------------------------
# Reminders on a task
# --------------------------------------------------------------------------


def remind_at_for(task: PersonalTask, kind: str) -> datetime | None:
    """Class wall time for one of the three offered reminders, or ``None``
    when the task has no date (or no time, for «за час»)."""
    if task.due_date is None:
        return None
    if kind == "hour":
        if task.due_time is None:
            return None
        return datetime.combine(task.due_date, task.due_time) - timedelta(hours=1)
    if kind == "morning":
        return datetime.combine(task.due_date, time(8, 0))
    if kind == "eve":
        return datetime.combine(task.due_date - timedelta(days=1), time(20, 0))
    return None


@router.callback_query(TaskAction.filter(F.action == "remind"))
async def task_set_reminder(
    callback: CallbackQuery,
    callback_data: TaskAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return

    raw_id, _, kind = callback_data.value.partition("_")
    task_id = _int_or_none(raw_id)
    task = (
        await task_service.get_task(session, task_id, school_class.id, callback.from_user.id)
        if task_id is not None
        else None
    )
    if task is None:
        await callback.answer("Задача уже удалена", show_alert=True)
        return

    remind_at = remind_at_for(task, kind)
    if remind_at is None:
        await callback.answer("Для этого напоминания нужны дата и время", show_alert=True)
        return
    now = _now(school_class).replace(tzinfo=None)
    if remind_at <= now:
        await callback.answer("Это время уже прошло", show_alert=True)
        return

    task.remind_at = remind_at
    await session.commit()

    today = now.date()
    when = human_date(remind_at.date(), today)
    await callback.message.edit_text(
        f"⏰ Напомню {escape(when)} в {remind_at:%H:%M}: <b>{escape(task.title)}</b>",
        reply_markup=InlineKeyboardMarkup(
            inline_keyboard=[
                [
                    InlineKeyboardButton(
                        text="‹ К списку", callback_data=TaskAction(action="list").pack()
                    )
                ]
            ]
        ),
    )
    await callback.answer()


# --------------------------------------------------------------------------
# Deleting
# --------------------------------------------------------------------------


@router.callback_query(TaskAction.filter(F.action == "delete_pick"))
async def task_delete_pick(
    callback: CallbackQuery,
    callback_data: TaskAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    show_done = bool(callback_data.show_done)
    tasks = await task_service.list_tasks(
        session, school_class.id, callback.from_user.id, include_done=show_done
    )
    if not tasks:
        await callback.answer("Удалять нечего")
        return
    await callback.message.edit_text(
        "Какую задачу удалить?", reply_markup=task_delete_picker(tasks, show_done)
    )
    await callback.answer()


@router.callback_query(TaskAction.filter(F.action == "delete"))
async def task_delete_confirm_prompt(
    callback: CallbackQuery,
    callback_data: TaskAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    task_id = _int_or_none(callback_data.value)
    task = (
        await task_service.get_task(session, task_id, school_class.id, callback.from_user.id)
        if task_id is not None
        else None
    )
    if task is None:
        await callback.answer("Задача уже удалена", show_alert=True)
        return
    await callback.message.edit_text(
        f"Удалить задачу <b>{escape(task.title)}</b>?",
        reply_markup=task_delete_confirm(task.id, bool(callback_data.show_done)),
    )
    await callback.answer()


@router.callback_query(TaskAction.filter(F.action == "delete_confirm"))
async def task_delete(
    callback: CallbackQuery,
    callback_data: TaskAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    task_id = _int_or_none(callback_data.value)
    task = (
        await task_service.get_task(session, task_id, school_class.id, callback.from_user.id)
        if task_id is not None
        else None
    )
    if task is not None:
        await task_service.delete_task(session, task)
    text, keyboard = await task_view(
        session, school_class, callback.from_user.id, bool(callback_data.show_done)
    )
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer("Удалено" if task is not None else "Задача уже удалена")


# --------------------------------------------------------------------------
# Homework ticks
# --------------------------------------------------------------------------


def _day_short(day: Date, today: Date) -> str:
    delta = (day - today).days
    if delta == 0:
        return "сегодня"
    if delta == 1:
        return "завтра"
    return f"{WEEKDAYS_SHORT[day.weekday()]} {day:%d.%m}"


def homework_tick_keyboard(
    days: list[ResolvedDay],
    today: Date,
    done: set[tuple[Date, str]],
    ids: dict[tuple[Date, str], int],
    extra_rows: list[list[InlineKeyboardButton]] | None = None,
) -> InlineKeyboardMarkup:
    """One «☐/✅ Предмет · день» button per upcoming homework, in digest order.

    ``ids`` maps (due date, subject) to the homework row id; an item the
    resolver shows but ``ids`` does not know (a race with a deletion) gets no
    button rather than a broken one.
    """
    rows: list[list[InlineKeyboardButton]] = []
    for day in days:
        if day.date < today:
            continue
        for item in day.homework:
            key = (day.date, item.subject)
            homework_id = ids.get(key)
            if homework_id is None or len(rows) >= HOMEWORK_BUTTONS_MAX:
                continue
            mark = "✅" if key in done else "☐"
            rows.append(
                [
                    InlineKeyboardButton(
                        text=f"{mark} {cut(item.subject, 20)} · {_day_short(day.date, today)}",
                        callback_data=HomeworkTick(action="toggle", value=str(homework_id)).pack(),
                    )
                ]
            )
    rows.extend(extra_rows or [])
    rows.append([InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())])
    return InlineKeyboardMarkup(inline_keyboard=rows)


async def homework_view(
    session: AsyncSession, school_class: SchoolClass, telegram_id: int, role: Role
) -> tuple[str, InlineKeyboardMarkup]:
    """The digest with this person's ticks, and the keyboard to flip them."""
    today = _now(school_class).date()
    days = await ScheduleResolver(session, school_class).resolve_range(today, HOMEWORK_DAYS)

    rows = await session.scalars(
        select(Homework).where(
            Homework.class_id == school_class.id,
            Homework.due_date >= today,
            Homework.due_date <= today + timedelta(days=HOMEWORK_DAYS - 1),
        )
    )
    ids = {(item.due_date, item.subject_name): item.id for item in rows}
    ticks = await task_service.homework_ticks(session, telegram_id, list(ids.values()))
    done = {key for key, homework_id in ids.items() if homework_id in ticks}

    extra: list[list[InlineKeyboardButton]] = []
    if role.at_least(Role.EDITOR):
        extra.append(
            [
                InlineKeyboardButton(
                    text="➕ Добавить ДЗ", callback_data=HomeworkAction(action="add").pack()
                )
            ]
        )
    return (
        render_homework_digest(days, today, done),
        homework_tick_keyboard(days, today, done, ids, extra),
    )


@router.message(Command("homework"))
async def cmd_homework(
    message: Message,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await message.answer(NO_ACCESS)
        return
    text, keyboard = await homework_view(session, school_class, message.from_user.id, role)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(HomeworkTick.filter(F.action == "toggle"))
async def homework_toggle(
    callback: CallbackQuery,
    callback_data: HomeworkTick,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    homework_id = _int_or_none(callback_data.value)
    homework = (
        await session.scalar(
            select(Homework).where(
                Homework.id == homework_id, Homework.class_id == school_class.id
            )
        )
        if homework_id is not None
        else None
    )
    if homework is None:
        await callback.answer("Это задание уже удалено", show_alert=True)
        text, keyboard = await homework_view(
            session, school_class, callback.from_user.id, role
        )
        await callback.message.edit_text(text, reply_markup=keyboard)
        return

    now_done = await task_service.toggle_homework_done(
        session, homework, callback.from_user.id
    )
    text, keyboard = await homework_view(session, school_class, callback.from_user.id, role)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer("Сделано ✅" if now_done else "Отметка снята")


__all__ = ["homework_tick_keyboard", "homework_view", "router", "task_view"]
