"""``/start`` in both its forms, a shared phone number, and «‹ Меню».

Part of :mod:`app.bot.handlers.start`. ``/start link_…`` is answered here
rather than with the other codes: it is a ``/start`` too, so it has to be asked
before the bare one, and keeping both in one router leaves that to their order
in this file alone.
"""

from __future__ import annotations

from html import escape

from aiogram import F, Router
from aiogram.filters import CommandObject, CommandStart
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message, ReplyKeyboardRemove
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.handlers.start._common import WELCOME_UNKNOWN
from app.bot.keyboards import Menu, main_menu, request_contact
from app.bot.render import render_role_help
from app.bot.roles import claim_phone_invites, get_role, is_env_owner
from app.bot.start_keyboard import grade_picker
from app.bot.states import CreateClass
from app.models import Role, SchoolClass
from app.services import linking

router = Router(name="start.menu")


async def _send_menu(message: Message, school_class: SchoolClass, role: Role) -> None:
    await message.answer(
        f"<b>{escape(school_class.name)}</b>"
        + (f" · {escape(school_class.school)}" if school_class.school else "")
        + f"\nВаша роль: <b>{role.title_ru}</b>. {render_role_help(role)}",
        reply_markup=main_menu(role, school_class.diary_provider),
    )


#: Link codes are six characters; anything longer is not one and is not
#: worth a database round trip.
LINK_CODE_MAX = 16


@router.message(CommandStart(deep_link=True, magic=F.args.startswith("link_")))
async def cmd_start_link(
    message: Message,
    command: CommandObject,
    state: FSMContext,
    session: AsyncSession,
) -> None:
    """``t.me/<bot>?start=link_<code>`` - the QR / button on the app's link screen.

    No role check: attaching a phone to *this* account is what the link does,
    and the phone then acts with whatever role this account holds in the
    device's class - possibly none, in which case it stays read-only. The
    reply says which, so the person knows what to ask an admin for.
    """
    await state.clear()
    code = (command.args or "")[len("link_"):].strip()
    device = (
        await linking.link_device(session, code, message.from_user.id)
        if 0 < len(code) <= LINK_CODE_MAX
        else None
    )
    if device is None:
        await message.answer(
            "Код не подошёл. Он действует один раз - откройте экран привязки в "
            "приложении ещё раз и отсканируйте новый."
        )
        return

    school_class = await session.get(SchoolClass, device.class_id)
    role = await get_role(session, message.from_user.id, device.class_id)
    name = escape(device.device_name or "Телефон")
    class_name = escape(school_class.name) if school_class is not None else "класс"
    if role is None:
        access = (
            "У вас пока нет роли в этом классе, поэтому телефон только читает "
            "расписание. Попросите администратора выдать доступ."
        )
    else:
        access = f"Телефон действует с вашей ролью: <b>{role.title_ru}</b>."
    await message.answer(
        f"📱 Устройство <b>{name}</b> привязано к вашему аккаунту "
        f"(класс <b>{class_name}</b>).\n{access}"
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
                "Какой это класс?",
                reply_markup=grade_picker(),
            )
            await state.set_state(CreateClass.grade)
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
        f"✅ Доступ выдан: <b>{escape(school_class.name)}</b>, роль <b>{role.title_ru}</b>.",
        reply_markup=ReplyKeyboardRemove(),
    )
    await _send_menu(message, school_class, role)


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
        f"<b>{escape(school_class.name)}</b>\nВаша роль: <b>{role.title_ru}</b>.",
        reply_markup=main_menu(role, school_class.diary_provider),
    )
    await callback.answer()
