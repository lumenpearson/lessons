"""«🕒 Часовой пояс» on a class that exists.

Part of :mod:`app.bot.handlers.start`. The wizard asks the same question in
``onboarding`` with the same ``TimezonePick``; that router is included first
and filtered on its state, which is the only thing telling the two apart.
"""

from __future__ import annotations

from aiogram import F, Router
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.keyboards import ClassAction, back_to_menu
from app.bot.start_keyboard import TimezonePick, timezone_picker
from app.models import Role, SchoolClass
from app.services.manage import classes as classes_service
from app.timezones import label_for

router = Router(name="start.timezone")


@router.callback_query(ClassAction.filter(F.action == "timezone"))
async def change_timezone_prompt(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return

    # An abandoned "create class" flow would otherwise swallow the zone button.
    await state.clear()

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
    try:
        await classes_service.set_timezone(
            session, school_class, callback.from_user.id, callback_data.zone
        )
    except classes_service.UnknownTimezone:
        await callback.answer("Неизвестный часовой пояс", show_alert=True)
        return
    await session.commit()

    await callback.message.edit_text(
        f"✅ Часовой пояс класса: <b>{label_for(callback_data.zone)}</b>\n\n"
        "Время звонков не изменилось — изменилось только то, по какому времени "
        "считается «сейчас».",
        reply_markup=back_to_menu(),
    )
    await callback.answer()
