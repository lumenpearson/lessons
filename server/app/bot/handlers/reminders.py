"""What the bot may message this person about, and when.

The settings row is per person per class and is created on first visit with
the defaults from the model (no digests, замены on, homework off) - nobody
receives a message they did not switch on. Times are class wall time, like
every other clock in the schema; the reminder tick compares them against
the class's zone, never the server's.
"""

from __future__ import annotations

from aiogram import F, Router
from aiogram.filters import Command, CommandObject
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, InlineKeyboardMarkup, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.keyboards import Menu, ReminderAction, cancel_keyboard, reminder_keyboard
from app.bot.render import render_reminder_card
from app.bot.states import SetReminderTime
from app.models import ReminderSettings, Role, SchoolClass
from app.services import reminders as reminder_service

router = Router(name="reminders")

NO_ACCESS = "Нет доступа. Откройте /start, чтобы получить его."

DIGEST_KINDS = {"morning", "evening"}

TIME_PROMPTS = {
    "morning": "Во сколько присылать утреннюю сводку? Например <code>7:30</code>.",
    "evening": "Во сколько присылать домашку на завтра? Например <code>20:00</code>.",
}


async def reminder_view(
    session: AsyncSession, school_class: SchoolClass, telegram_id: int
) -> tuple[str, InlineKeyboardMarkup, ReminderSettings]:
    settings = await reminder_service.settings_for(session, school_class.id, telegram_id)
    return render_reminder_card(settings), reminder_keyboard(settings), settings


async def disable_all(session: AsyncSession, settings: ReminderSettings) -> None:
    settings.morning_at = None
    settings.evening_at = None
    settings.notify_changes = False
    settings.notify_homework = False
    await session.commit()


@router.message(Command("remind"))
async def cmd_remind(
    message: Message,
    command: CommandObject,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """``/remind`` shows the card; ``/remind off`` silences everything at once."""
    if school_class is None or role is None:
        await message.answer(NO_ACCESS)
        return
    await state.clear()
    text, keyboard, settings = await reminder_view(session, school_class, message.from_user.id)
    if (command.args or "").strip().lower() in {"off", "выкл", "стоп"}:
        await disable_all(session, settings)
        text, keyboard, _ = await reminder_view(session, school_class, message.from_user.id)
        text = "🔕 Все напоминания выключены.\n\n" + text
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(Menu.filter(F.action == "reminders"))
async def menu_reminders(
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
    text, keyboard, _ = await reminder_view(session, school_class, callback.from_user.id)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


@router.callback_query(ReminderAction.filter(F.action == "card"))
async def reminder_card(
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
    text, keyboard, _ = await reminder_view(session, school_class, callback.from_user.id)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


@router.callback_query(ReminderAction.filter(F.action == "toggle"))
async def reminder_toggle(
    callback: CallbackQuery,
    callback_data: ReminderAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    settings = await reminder_service.settings_for(
        session, school_class.id, callback.from_user.id
    )
    if callback_data.value == "changes":
        settings.notify_changes = not settings.notify_changes
        enabled = settings.notify_changes
    elif callback_data.value == "homework":
        settings.notify_homework = not settings.notify_homework
        enabled = settings.notify_homework
    else:
        await callback.answer("Неизвестная настройка", show_alert=True)
        return
    await session.commit()

    await callback.message.edit_text(
        render_reminder_card(settings), reply_markup=reminder_keyboard(settings)
    )
    await callback.answer("Включено" if enabled else "Выключено")


@router.callback_query(ReminderAction.filter(F.action == "clear_time"))
async def reminder_clear_time(
    callback: CallbackQuery,
    callback_data: ReminderAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    if callback_data.value not in DIGEST_KINDS:
        await callback.answer("Неизвестная сводка", show_alert=True)
        return
    settings = await reminder_service.settings_for(
        session, school_class.id, callback.from_user.id
    )
    if callback_data.value == "morning":
        settings.morning_at = None
    else:
        settings.evening_at = None
    await session.commit()

    await callback.message.edit_text(
        render_reminder_card(settings), reply_markup=reminder_keyboard(settings)
    )
    await callback.answer("Выключено")


@router.callback_query(ReminderAction.filter(F.action == "off"))
async def reminder_off(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    settings = await reminder_service.settings_for(
        session, school_class.id, callback.from_user.id
    )
    await disable_all(session, settings)
    await callback.message.edit_text(
        "🔕 Все напоминания выключены.\n\n" + render_reminder_card(settings),
        reply_markup=reminder_keyboard(settings),
    )
    await callback.answer("Выключено")


@router.callback_query(ReminderAction.filter(F.action == "set_time"))
async def reminder_time_prompt(
    callback: CallbackQuery,
    callback_data: ReminderAction,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    if callback_data.value not in DIGEST_KINDS:
        await callback.answer("Неизвестная сводка", show_alert=True)
        return
    await state.update_data(kind=callback_data.value)
    await state.set_state(SetReminderTime.kind)
    await callback.message.edit_text(
        TIME_PROMPTS[callback_data.value] + "\n\nВремя — по часам класса.",
        reply_markup=cancel_keyboard(),
    )
    await callback.answer()


@router.message(SetReminderTime.kind)
async def reminder_time_apply(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await state.clear()
        return

    data = await state.get_data()
    kind = data.get("kind")
    if kind not in DIGEST_KINDS:
        # The state outlived its data (a storage wipe between steps); there
        # is nothing sensible to apply the time to.
        await state.clear()
        await message.answer("Начните заново: /remind")
        return

    parsed = reminder_service.parse_clock(message.text or "")
    if parsed is None:
        await message.answer(
            "Не понял время. Напишите, например, <code>7:30</code> или <code>20:00</code>:",
            reply_markup=cancel_keyboard(),
        )
        return

    settings = await reminder_service.settings_for(
        session, school_class.id, message.from_user.id
    )
    if kind == "morning":
        settings.morning_at = parsed
    else:
        settings.evening_at = parsed
    await session.commit()
    await state.clear()

    await message.answer(
        f"✅ Время сохранено: <b>{parsed:%H:%M}</b>.\n\n" + render_reminder_card(settings),
        reply_markup=reminder_keyboard(settings),
    )
