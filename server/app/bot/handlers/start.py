"""Onboarding, the main menu and day browsing."""

from __future__ import annotations

from datetime import datetime, timedelta

from aiogram import F, Router
from aiogram.filters import Command, CommandStart
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, InlineKeyboardButton, Message, ReplyKeyboardRemove
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.keyboards import (
    ClassAction,
    DayNav,
    Menu,
    TimezonePick,
    back_to_menu,
    cancel_keyboard,
    day_nav,
    main_menu,
    request_contact,
    timezone_picker,
)
from app.bot.render import render_day, render_role_help
from app.bot.roles import claim_phone_invites, is_env_owner
from app.bot.states import CreateClass
from app.config import get_settings
from app.models import DEFAULT_BELLS, BellPeriod, BellSchedule, BotUser, Role, SchoolClass
from app.schedule import ScheduleResolver
from app.security import new_join_code
from app.timezones import DEFAULT_TIMEZONE, is_supported, label_for

router = Router(name="start")

WELCOME_UNKNOWN = (
    "👋 Это бот управления школьным дневником.\n\n"
    "Вас пока нет в списке доступа. Если вам выдали приглашение по номеру "
    "телефона, нажмите кнопку ниже — бот сверит номер и выдаст доступ."
)


def _today(school_class: SchoolClass | None = None) -> datetime:
    """Now, in the class's own zone rather than the server's."""
    tz = school_class.tz if school_class is not None else get_settings().tz
    return datetime.now(tz)


async def _send_menu(message: Message, school_class: SchoolClass, role: Role) -> None:
    await message.answer(
        f"<b>{school_class.name}</b>"
        + (f" · {school_class.school}" if school_class.school else "")
        + f"\nВаша роль: <b>{role.title_ru}</b>. {render_role_help(role)}",
        reply_markup=main_menu(role),
    )


@router.message(CommandStart())
async def cmd_start(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    await state.clear()

    if school_class is None:
        if is_env_owner(message.from_user.id):
            await message.answer(
                "👋 Классов ещё нет. Давайте создадим первый.\n\n"
                "Введите название класса, например <code>9А</code>:",
                reply_markup=cancel_keyboard(),
            )
            await state.set_state(CreateClass.name)
            return
        await message.answer(WELCOME_UNKNOWN, reply_markup=request_contact())
        return

    if role is None:
        await message.answer(WELCOME_UNKNOWN, reply_markup=request_contact())
        return

    await _send_menu(message, school_class, role)


@router.message(F.contact)
async def on_contact(
    message: Message,
    session: AsyncSession,
) -> None:
    """Apply any invite matching the shared phone number.

    Telegram only lets a user share *their own* contact through this button, so
    a match here is proof of ownership of the number.
    """
    contact = message.contact
    if contact.user_id != message.from_user.id:
        await message.answer(
            "Это чужой контакт. Нажмите кнопку «Поделиться номером», чтобы "
            "отправить свой.",
            reply_markup=request_contact(),
        )
        return

    granted = await claim_phone_invites(
        session,
        telegram_id=message.from_user.id,
        phone=contact.phone_number,
        username=message.from_user.username,
        full_name=message.from_user.full_name,
    )

    if not granted:
        await message.answer(
            "Приглашений для этого номера не найдено. Попросите администратора "
            "добавить ваш номер.",
            reply_markup=ReplyKeyboardRemove(),
        )
        return

    school_class, role = granted[0]
    await message.answer(
        f"✅ Доступ выдан: <b>{school_class.name}</b>, роль <b>{role.title_ru}</b>.",
        reply_markup=ReplyKeyboardRemove(),
    )
    await _send_menu(message, school_class, role)


@router.message(CreateClass.name)
async def create_class_name(message: Message, state: FSMContext) -> None:
    name = (message.text or "").strip()
    if not 1 <= len(name) <= 64:
        await message.answer("Название должно быть от 1 до 64 символов. Попробуйте ещё раз:")
        return
    await state.update_data(name=name)
    await message.answer(
        "Теперь введите название школы (или отправьте <code>-</code>, чтобы пропустить):",
        reply_markup=cancel_keyboard(),
    )
    await state.set_state(CreateClass.school)


@router.message(CreateClass.school)
async def create_class_school(message: Message, state: FSMContext) -> None:
    school = (message.text or "").strip()
    await state.update_data(school=None if school in {"-", ""} else school[:200])
    await message.answer(
        "В каком часовом поясе находится школа?\n\n"
        "Это влияет на то, когда приложение и виджет считают уроки идущими — "
        "выберите пояс своего города:",
        reply_markup=timezone_picker(),
    )
    await state.set_state(CreateClass.timezone)


@router.callback_query(CreateClass.timezone, TimezonePick.filter())
async def create_class_timezone(
    callback: CallbackQuery,
    callback_data: TimezonePick,
    state: FSMContext,
    session: AsyncSession,
) -> None:
    data = await state.get_data()
    zone = callback_data.zone if is_supported(callback_data.zone) else DEFAULT_TIMEZONE

    bells = BellSchedule(class_id=0, name="Обычное")
    school_class = SchoolClass(
        name=data["name"],
        school=data.get("school"),
        timezone=zone,
        join_code=new_join_code(),
    )
    session.add(school_class)
    await session.flush()

    bells.class_id = school_class.id
    session.add(bells)
    await session.flush()

    for index, starts_at, ends_at in DEFAULT_BELLS:
        session.add(
            BellPeriod(schedule_id=bells.id, index=index, starts_at=starts_at, ends_at=ends_at)
        )

    school_class.bell_schedule_id = bells.id
    session.add(
        BotUser(
            telegram_id=callback.from_user.id,
            class_id=school_class.id,
            role=Role.OWNER,
            username=callback.from_user.username,
            full_name=callback.from_user.full_name,
        )
    )
    await session.commit()
    await state.clear()

    await callback.message.edit_text(
        f"✅ Класс <b>{school_class.name}</b> создан.\n"
        f"Часовой пояс: {label_for(zone)}\n\n"
        f"Код для приложения: <code>{school_class.join_code}</code>\n"
        "Введите его в приложении на телефоне, чтобы подключить расписание.\n\n"
        "Дальше стоит заполнить расписание уроков в разделе «Расписание».",
        reply_markup=main_menu(Role.OWNER),
    )
    await callback.answer()


@router.callback_query(Menu.filter(F.action == "root"))
async def back_root(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    await state.clear()
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    await callback.message.edit_text(
        f"<b>{school_class.name}</b>\nВаша роль: <b>{role.title_ru}</b>.",
        reply_markup=main_menu(role),
    )
    await callback.answer()


@router.callback_query(DayNav.filter())
async def show_day(
    callback: CallbackQuery,
    callback_data: DayNav,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return

    today = _today(school_class).date()
    target = today + timedelta(days=callback_data.offset)
    resolver = ScheduleResolver(session, school_class)
    days = await resolver.resolve_range(target, 1)

    await callback.message.edit_text(
        render_day(days[0], today),
        reply_markup=day_nav(callback_data.offset),
    )
    await callback.answer()


@router.message(Command("today"))
async def cmd_today(
    message: Message,
    session: AsyncSession,
    school_class: SchoolClass | None,
) -> None:
    if school_class is None:
        await message.answer(WELCOME_UNKNOWN, reply_markup=request_contact())
        return
    today = _today(school_class).date()
    days = await ScheduleResolver(session, school_class).resolve_range(today, 1)
    await message.answer(render_day(days[0], today), reply_markup=day_nav(0))


@router.message(Command("code"))
async def cmd_code(
    message: Message,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Show the join code the Android app needs."""
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await message.answer("Команда доступна администраторам класса.")
        return
    await message.answer(
        f"Код класса <b>{school_class.name}</b>: <code>{school_class.join_code}</code>\n\n"
        "Его вводят в приложении при первом запуске. "
        "Код даёт только чтение расписания.",
    )


@router.callback_query(Menu.filter(F.action == "class"))
async def class_settings(
    callback: CallbackQuery,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return

    extra = [
        [
            InlineKeyboardButton(
                text="🕒 Часовой пояс",
                callback_data=ClassAction(action="timezone").pack(),
            )
        ]
    ]
    if role.at_least(Role.OWNER):
        extra.append(
            [
                InlineKeyboardButton(
                    text="🔁 Сменить код класса",
                    callback_data=ClassAction(action="rotate_code").pack(),
                )
            ]
        )

    await callback.message.edit_text(
        f"<b>⚙️ {school_class.name}</b>\n"
        + (f"Школа: {school_class.school}\n" if school_class.school else "")
        + f"Часовой пояс: {label_for(school_class.timezone_name)}\n"
        + f"Код для приложения: <code>{school_class.join_code}</code>",
        reply_markup=back_to_menu(extra),
    )
    await callback.answer()


@router.callback_query(ClassAction.filter(F.action == "rotate_code"))
async def rotate_code(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Rotating the code does not revoke devices that already joined —
    it only stops the old code from being used again."""
    if school_class is None or role is None or not role.at_least(Role.OWNER):
        await callback.answer("Только для владельца", show_alert=True)
        return

    school_class.join_code = new_join_code()
    await session.commit()
    await callback.message.edit_text(
        f"Новый код класса: <code>{school_class.join_code}</code>\n\n"
        "Уже подключённые устройства продолжат работать.",
        reply_markup=back_to_menu(),
    )
    await callback.answer("Код обновлён")


@router.message(Command("help"))
async def cmd_help(message: Message, role: Role | None) -> None:
    lines = [
        "<b>Команды</b>",
        "/start — главное меню",
        "/today — расписание на сегодня",
        "/help — эта справка",
    ]
    if role is not None and role.at_least(Role.ADMIN):
        lines.append("/code — код класса для приложения")
    await message.answer("\n".join(lines))


@router.callback_query(ClassAction.filter(F.action == "timezone"))
async def change_timezone_prompt(
    callback: CallbackQuery,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return

    await callback.message.edit_text(
        f"Текущий пояс: <b>{label_for(school_class.timezone_name)}</b>\n\n"
        "Выберите новый:",
        reply_markup=timezone_picker(),
    )
    await callback.answer()


@router.callback_query(TimezonePick.filter())
async def change_timezone_apply(
    callback: CallbackQuery,
    callback_data: TimezonePick,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Changing the zone does not move any stored time.

    Bells are wall time, so 08:30 stays 08:30 — what changes is which instant
    the app and the widget consider "now" for this class.
    """
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return
    if not is_supported(callback_data.zone):
        await callback.answer("Неизвестный часовой пояс", show_alert=True)
        return

    school_class.timezone = callback_data.zone
    await session.commit()

    await callback.message.edit_text(
        f"✅ Часовой пояс класса: <b>{label_for(callback_data.zone)}</b>\n\n"
        "Время звонков не изменилось — изменилось только то, по какому времени "
        "считается «сейчас».",
        reply_markup=back_to_menu(),
    )
    await callback.answer()
