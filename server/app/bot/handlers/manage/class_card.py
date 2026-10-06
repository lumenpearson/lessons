"""«⚙️ Класс»: the class card, its typed fields, switching class and deleting it.

Part of :mod:`app.bot.handlers.manage`; the rules every screen of it
follows are in that package's docstring.
"""

from __future__ import annotations

from html import escape

from aiogram import F, Router
from aiogram.filters import Command
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.handlers.manage._common import (
    NEED_OWNER,
    _int_or_none,
    needs,
)
from app.bot.keyboards import Menu, back_to_menu, cancel_keyboard
from app.bot.manage_keyboards._common import ManageAction
from app.bot.manage_keyboards.class_card import class_menu, switch_keyboard
from app.bot.manage_render import class_card as mr
from app.bot.manage_states import DeleteClass, EditClassField
from app.bot.middlewares import prefs_key
from app.bot.roles import is_env_owner, list_memberships
from app.db import SessionLocal
from app.fsm_storage import DatabaseStorage
from app.models import Role, SchoolClass
from app.providers.diary.registry import binding as diary_binding
from app.services.manage import classes as classes_service
from app.timezones import label_for

router = Router(name="manage.class_card")


# --------------------------------------------------------------------------
# ⚙️ The class
# --------------------------------------------------------------------------


async def _class_card(
    session: AsyncSession, school_class: SchoolClass, role: Role, telegram_id: int
):
    memberships = await list_memberships(session, telegram_id)
    counts = await classes_service.counts(session, school_class.id)

    text = mr.render_class_card(
        school_class,
        zone_label=label_for(school_class.timezone_name),
        feed_ready=bool(school_class.calendar_token),
        members=counts.members,
        devices=counts.devices,
        pending=counts.pending,
        diary_label=_diary_label(school_class),
    )
    keyboard = class_menu(
        is_owner=role.at_least(Role.OWNER),
        # The button only appears for somebody who actually has somewhere to
        # switch to; one membership is the overwhelmingly common case.
        many_classes=len(memberships) > 1,
        pending=counts.pending,
        diary_bound=bool(school_class.diary_provider),
        deployment_owner=is_env_owner(telegram_id),
    )
    return text, keyboard


def _diary_label(school_class: SchoolClass) -> str:
    """The diary line for the class card: the provider, and for «Сетевой город»
    the region and school. Reads the binding, so a class bound to a region since
    dropped from the allow-list reads «не привязан» rather than a dead name."""
    b = diary_binding(school_class)
    if b is None:
        return "не привязан"
    if b.region:
        from app.providers.netschool import regions

        region = regions.get(b.region)
        parts = [b.provider.title]
        if region is not None:
            parts.append(region.title)
        if b.school_name:
            parts.append(b.school_name)
        return " · ".join(parts)
    return b.provider.title


@router.message(Command("class"))
@needs(Role.ADMIN)
async def cmd_class(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await state.clear()
    text, keyboard = await _class_card(session, school_class, role, message.from_user.id)
    await message.answer(text, reply_markup=keyboard)


# Two entry points, one card. The «⚙️ Класс» button in the main menu used to
# open a thinner version of this that lived in start.py, so half the settings
# were on whichever card you had not opened.
@router.callback_query(Menu.filter(F.action == "class"))
@router.callback_query(ManageAction.filter(F.action == "root"))
@needs(Role.ADMIN)
async def class_root(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await state.clear()
    text, keyboard = await _class_card(session, school_class, role, callback.from_user.id)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


#: field tag -> (column, prompt, limit). Only these three are typed; the zone
#: and the join code have pickers of their own in ``handlers/start/``
#: (``timezone``, ``codes``).
_CLASS_FIELDS = {
    "rename": ("name", "Новое название класса:", 64),
    "school": ("school", "Название школы («-» — убрать):", 200),
    "city": ("city", "Город («-» — убрать):", 120),
}


@router.callback_query(ManageAction.filter(F.action.in_({"rename", "school", "city"})))
@needs(Role.ADMIN)
async def class_field_prompt(
    callback: CallbackQuery,
    callback_data: ManageAction,
    state: FSMContext,
    school_class: SchoolClass,
    role: Role,
) -> None:
    target = _CLASS_FIELDS.get(callback_data.action)
    if target is None:  # pragma: no cover - the filter already narrowed it
        await callback.answer("Неизвестное поле", show_alert=True)
        return

    _, prompt, _ = target
    await state.set_state(EditClassField.value)
    await state.update_data(field=callback_data.action)
    await callback.message.edit_text(prompt, reply_markup=cancel_keyboard())
    await callback.answer()


@router.message(EditClassField.value)
@needs(Role.ADMIN, step=True)
async def class_field_apply(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    data = await state.get_data()
    target = _CLASS_FIELDS.get(str(data.get("field", "")))
    if target is None:
        await state.clear()
        await message.answer("Начните заново: /class", reply_markup=back_to_menu())
        return
    column, prompt, limit = target

    raw = " ".join((message.text or "").split())
    if column == "name":
        # The name is what every other message calls this class, and what the
        # delete confirmation is typed against; it cannot be empty.
        if not 1 <= len(raw) <= limit:
            await message.answer(f"От 1 до {limit} символов. {prompt}")
            return
        value = raw
    else:
        value = None if raw in {"-", "—", ""} else raw[:limit]

    await classes_service.set_field(session, school_class, message.from_user.id, column, value)
    await session.commit()
    await state.clear()

    text, keyboard = await _class_card(session, school_class, role, message.from_user.id)
    await message.answer(text, reply_markup=keyboard)


# --------------------------------------------------------------------------
# 🔀 Switching class
# --------------------------------------------------------------------------


@router.callback_query(ManageAction.filter(F.action == "switch"))
@needs(Role.VIEWER)
async def class_switch(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    classes = await classes_service.classes_of(
        session, await list_memberships(session, callback.from_user.id)
    )
    if len(classes) < 2:
        await callback.answer("Вы состоите только в одном классе", show_alert=True)
        return

    await callback.message.edit_text(
        "🔀 <b>Сменить класс</b>\n\nВ каком классе работаем дальше?",
        reply_markup=switch_keyboard(classes),
    )
    await callback.answer()


@router.callback_query(ManageAction.filter(F.action == "switch_to"))
@needs(Role.VIEWER)
async def class_switch_to(
    callback: CallbackQuery,
    callback_data: ManageAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    """Remember which class this person is working in.

    The preference is written to FSM storage — the database — under its own
    key, and the middleware reads exactly that key on the next update. Nothing
    is kept in the process: on Vercel the next message is a different one.

    The membership is checked here and checked again by the middleware every
    time it reads the preference, so a class somebody is later removed from
    stops being their default by itself.
    """
    class_id = _int_or_none(callback_data.value)
    memberships = await list_memberships(session, callback.from_user.id)
    if class_id is None or not any(member.class_id == class_id for member in memberships):
        await callback.answer("Вы не состоите в этом классе", show_alert=True)
        return

    target = await classes_service.class_of(session, class_id)
    if target is None:
        await callback.answer("Класс не найден", show_alert=True)
        return

    storage = DatabaseStorage(SessionLocal)
    await storage.set_data(prefs_key(callback.from_user.id), {"class_id": class_id})

    await state.clear()
    await callback.message.edit_text(
        f"✅ Текущий класс: <b>{escape(target.name)}</b>.\n\n"
        "Все команды теперь про него. Открыть меню: /start",
        reply_markup=back_to_menu(),
    )
    await callback.answer(f"Класс: {target.name}"[:200])


# --------------------------------------------------------------------------
# 🗑 Deleting a class
# --------------------------------------------------------------------------


@router.callback_query(ManageAction.filter(F.action == "delete"))
@needs(Role.OWNER, refusal=NEED_OWNER)
async def class_delete_prompt(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await state.set_state(DeleteClass.confirm)
    await callback.message.edit_text(
        f"🗑 <b>Удалить класс {escape(school_class.name)}?</b>\n\n"
        "Вместе с ним исчезнут расписание, домашние задания, замены, события, "
        "журнал и все привязанные устройства. Это нельзя отменить.\n\n"
        f"Чтобы подтвердить, пришлите название класса точно так: "
        f"<code>{escape(school_class.name)}</code>",
        reply_markup=cancel_keyboard(),
    )
    await callback.answer()


@router.message(DeleteClass.confirm)
@needs(Role.OWNER, step=True)
async def class_delete_apply(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    """Typing the name back is the confirmation, and the only one.

    A «вы уверены?» button is pressed by the same thumb that pressed the one
    before it. Nothing here writes an audit line: the log lives in the class
    and goes with it.
    """
    name = school_class.name
    try:
        await classes_service.delete(session, school_class, message.text or "")
    except classes_service.NameMismatch:
        await message.answer(
            "Название не совпало — класс не тронут.\n"
            f"Чтобы удалить, пришлите ровно: <code>{escape(school_class.name)}</code>"
        )
        return
    await session.commit()
    await state.clear()
    await message.answer(
        f"🗑 Класс <b>{escape(name)}</b> удалён вместе со всеми данными."
    )
