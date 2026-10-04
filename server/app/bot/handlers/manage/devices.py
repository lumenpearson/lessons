"""«📱 Устройства»: the phones on the class, revoked or unlinked from here.

Part of :mod:`app.bot.handlers.manage`; the rules every screen of it
follows are in that package's docstring.
"""

from __future__ import annotations

from aiogram import F, Router
from aiogram.filters import Command
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.handlers.manage._common import _int_or_none, _member_names, needs
from app.bot.manage_keyboards import DeviceAction, device_keyboard
from app.bot.manage_render import devices as mr
from app.models import DeviceToken, Role, SchoolClass
from app.services import linking
from app.services.manage import devices as devices_service

router = Router(name="manage.devices")


# --------------------------------------------------------------------------
# 📱 Devices
# --------------------------------------------------------------------------
#
# A device token is read-only until its owner links it to a Telegram account;
# from then on it writes with whatever role that account holds *right now*
# (``linking.effective_role``). Which is why this page shows the role as a
# lookup and not as something stored on the row: revoking somebody in «Доступ»
# has already revoked their phone by the time this page is drawn.


async def _device_view(session: AsyncSession, school_class: SchoolClass):
    devices = await linking.devices_of(session, school_class.id)
    names = await _member_names(session, school_class.id)
    owners: dict[int, tuple[str, Role | None]] = {
        telegram_id: (names.get(telegram_id, str(telegram_id)), owner_role)
        for telegram_id, owner_role in (
            await devices_service.owner_roles(session, devices)
        ).items()
    }
    return mr.render_devices(devices, owners), device_keyboard(devices)


async def _device_by_id(
    session: AsyncSession, school_class: SchoolClass, raw: str
) -> DeviceToken | None:
    device_id = _int_or_none(raw)
    if device_id is None:
        return None
    return await devices_service.device_of(session, school_class.id, device_id)


@router.message(Command("devices"))
@needs(Role.ADMIN)
async def cmd_devices(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await state.clear()
    text, keyboard = await _device_view(session, school_class)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(DeviceAction.filter(F.action == "list"))
@needs(Role.ADMIN)
async def devices_list(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await state.clear()
    text, keyboard = await _device_view(session, school_class)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


@router.callback_query(DeviceAction.filter(F.action == "revoke"))
@needs(Role.ADMIN)
async def device_revoke(
    callback: CallbackQuery,
    callback_data: DeviceAction,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    """Revoked, not deleted: the row is what the API checks a token against,
    and keeping it is what makes the refusal instant and permanent."""
    device = await _device_by_id(session, school_class, callback_data.value)
    if device is None:
        await callback.answer("Устройство не найдено", show_alert=True)
        return

    # A phone already off is answered the same and logged once, not again.
    await devices_service.revoke(session, school_class.id, callback.from_user.id, device)
    await session.commit()
    name = devices_service.label(device)

    text, keyboard = await _device_view(session, school_class)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer(f"Отключено: {name}"[:200])


@router.callback_query(DeviceAction.filter(F.action == "unlink"))
@needs(Role.ADMIN)
async def device_unlink(
    callback: CallbackQuery,
    callback_data: DeviceAction,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    """Back to read-only without taking the phone off the class."""
    device = await _device_by_id(session, school_class, callback_data.value)
    if device is None:
        await callback.answer("Устройство не найдено", show_alert=True)
        return
    try:
        await devices_service.unlink(session, school_class.id, callback.from_user.id, device)
    except devices_service.DeviceNotLinked:
        await callback.answer("Устройство и так не привязано", show_alert=True)
        return
    await session.commit()

    text, keyboard = await _device_view(session, school_class)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer("Отвязано — теперь только чтение")
