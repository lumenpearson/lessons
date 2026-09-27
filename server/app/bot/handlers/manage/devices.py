"""«📱 Устройства»: the phones on the class, revoked or unlinked from here.

Part of :mod:`app.bot.handlers.manage`; the rules every screen of it
follows are in that package's docstring.
"""

from __future__ import annotations

from aiogram import F, Router
from aiogram.filters import Command
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot import manage_render as mr
from app.bot.handlers.manage._common import _allowed, _int_or_none, _member_names, _refusal
from app.bot.manage_keyboards import DeviceAction, device_keyboard
from app.models import DeviceToken, Role, SchoolClass
from app.services import audit, linking

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
    owners: dict[int, tuple[str, Role | None]] = {}
    names = await _member_names(session, school_class.id)
    for device in devices:
        if device.telegram_id is None or device.telegram_id in owners:
            continue
        owners[device.telegram_id] = (
            names.get(device.telegram_id, str(device.telegram_id)),
            await linking.effective_role(session, device),
        )
    return mr.render_devices(devices, owners), device_keyboard(devices)


async def _device_by_id(
    session: AsyncSession, school_class: SchoolClass, raw: str
) -> DeviceToken | None:
    device_id = _int_or_none(raw)
    if device_id is None:
        return None
    return await session.scalar(
        select(DeviceToken).where(
            DeviceToken.id == device_id, DeviceToken.class_id == school_class.id
        )
    )


@router.message(Command("devices"))
async def cmd_devices(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await message.answer(_refusal(role, Role.ADMIN))
        return
    await state.clear()
    text, keyboard = await _device_view(session, school_class)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(DeviceAction.filter(F.action == "list"))
async def devices_list(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return
    await state.clear()
    text, keyboard = await _device_view(session, school_class)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


@router.callback_query(DeviceAction.filter(F.action == "revoke"))
async def device_revoke(
    callback: CallbackQuery,
    callback_data: DeviceAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Revoked, not deleted: the row is what the API checks a token against,
    and keeping it is what makes the refusal instant and permanent."""
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    device = await _device_by_id(session, school_class, callback_data.value)
    if device is None:
        await callback.answer("Устройство не найдено", show_alert=True)
        return

    device.revoked = True
    name = device.device_name or f"Устройство {device.id}"
    await audit.record(
        session, school_class.id, callback.from_user.id, "device.revoke",
        f"отключено устройство «{name}»",
    )
    await session.commit()

    text, keyboard = await _device_view(session, school_class)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer(f"Отключено: {name}"[:200])


@router.callback_query(DeviceAction.filter(F.action == "unlink"))
async def device_unlink(
    callback: CallbackQuery,
    callback_data: DeviceAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Back to read-only without taking the phone off the class."""
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    device = await _device_by_id(session, school_class, callback_data.value)
    if device is None:
        await callback.answer("Устройство не найдено", show_alert=True)
        return
    if device.telegram_id is None:
        await callback.answer("Устройство и так не привязано", show_alert=True)
        return

    name = device.device_name or f"Устройство {device.id}"
    await linking.unlink_device(session, device)
    await audit.record(
        session, school_class.id, callback.from_user.id, "device.unlink",
        f"отвязано устройство «{name}» — снова только чтение",
    )
    await session.commit()

    text, keyboard = await _device_view(session, school_class)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer("Отвязано — теперь только чтение")
