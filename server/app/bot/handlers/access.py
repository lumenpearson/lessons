"""Access control: who is in the class, and adding people by phone number.

Requests for a higher role («🙋 Запросы доступа») land at the top of this page
rather than in a list of their own: the answer to «кто это вообще?» is the
member list right underneath, and an admin deciding without it is deciding
blind. The buttons carry ``RequestAction``, whose handlers live in
``manage.py`` next to the rest of the request flow.
"""

from __future__ import annotations

from html import escape

from aiogram import F, Router
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, InlineKeyboardButton, Message
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.button_style import DANGER, SUCCESS
from app.bot.keyboards import (
    AccessAction,
    Menu,
    RolePick,
    back_to_menu,
    cancel_keyboard,
    role_picker,
)
from app.bot.manage_keyboards import RequestAction
from app.bot.manage_render import person
from app.bot.render import render_access_list
from app.bot.roles import can_grant
from app.bot.states import AddInvite
from app.models import AccessRequest, BotUser, PhoneInvite, Role, SchoolClass
from app.security import normalise_phone
from app.services import audit

router = Router(name="access")

#: Pending requests shown before the member list. More than this and the
#: keyboard stops fitting on a phone; the rest appear as they are answered.
PENDING_MAX = 5


def _grantable_roles(actor: Role) -> list[Role]:
    return [role for role in (Role.VIEWER, Role.EDITOR, Role.ADMIN) if can_grant(actor, role)]


@router.callback_query(Menu.filter(F.action == "access"))
async def access_root(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return

    members = list(
        await session.scalars(
            select(BotUser)
            .where(BotUser.class_id == school_class.id)
            .order_by(BotUser.role.desc(), BotUser.id)
        )
    )
    invites = list(
        await session.scalars(select(PhoneInvite).where(PhoneInvite.class_id == school_class.id))
    )
    pending = list(
        await session.scalars(
            select(AccessRequest)
            .where(AccessRequest.class_id == school_class.id, AccessRequest.status == "pending")
            .order_by(AccessRequest.id)
        )
    )
    by_id = {member.telegram_id: member for member in members}

    extra: list[list[InlineKeyboardButton]] = []
    lines: list[str] = []
    if pending:
        lines.append("<b>🙋 Запросы доступа</b>")
        for request in pending[:PENDING_MAX]:
            member = by_id.get(request.telegram_id)
            plain = (
                (member.full_name or member.username or str(member.telegram_id))
                if member is not None
                else str(request.telegram_id)
            )
            who = person(
                member.full_name if member else None,
                member.username if member else None,
                request.telegram_id,
            )
            note = f" — {escape(request.message)}" if request.message else ""
            lines.append(f"⏳ {who} → <b>{request.requested_role.title_ru}</b>{note}")
            extra.append(
                [
                    InlineKeyboardButton(
                        text=f"✅ {plain}"[:28],
                        callback_data=RequestAction(
                            action="approve", value=str(request.id)
                        ).pack(),
                        style=SUCCESS,
                    ),
                    InlineKeyboardButton(
                        text="✖️",
                        callback_data=RequestAction(
                            action="decline", value=str(request.id)
                        ).pack(),
                        style=DANGER,
                    ),
                ]
            )
        lines.append("")

    extra.append(
        [
            InlineKeyboardButton(
                text="➕ Добавить по номеру",
                callback_data=AccessAction(action="invite").pack(),
                style=SUCCESS,
            )
        ]
    )
    if members:
        extra.append(
            [
                InlineKeyboardButton(
                    text="✏️ Изменить роль",
                    callback_data=AccessAction(action="pick_member").pack(),
                )
            ]
        )

    body = "\n".join(lines) + render_access_list(members, invites)
    await callback.message.edit_text(body, reply_markup=back_to_menu(extra))
    await callback.answer()


@router.callback_query(AccessAction.filter(F.action == "invite"))
async def invite_start(
    callback: CallbackQuery,
    state: FSMContext,
    role: Role | None,
) -> None:
    if role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return

    await callback.message.edit_text(
        "Отправьте номер телефона человека в любом формате, например "
        "<code>+7 900 123-45-67</code>.\n\n"
        "Когда он напишет боту и поделится контактом, роль выдастся автоматически.",
        reply_markup=cancel_keyboard(),
    )
    await state.set_state(AddInvite.phone)
    await callback.answer()


@router.message(AddInvite.phone)
async def invite_phone(
    message: Message,
    state: FSMContext,
    role: Role | None,
) -> None:
    if role is None or not role.at_least(Role.ADMIN):
        await state.clear()
        return

    phone = normalise_phone(message.text or "")
    if not 10 <= len(phone) <= 15:
        await message.answer("Это не похоже на номер телефона. Попробуйте ещё раз:")
        return

    await state.update_data(phone=phone)
    await message.answer(
        f"Номер: <code>+{phone}</code>\nКакую роль выдать?",
        reply_markup=role_picker(_grantable_roles(role)),
    )
    await state.set_state(AddInvite.role)


@router.callback_query(AddInvite.role, RolePick.filter())
async def invite_role(
    callback: CallbackQuery,
    callback_data: RolePick,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return

    target = Role(callback_data.role)
    if not can_grant(role, target):
        await callback.answer("Нельзя выдать роль выше вашей", show_alert=True)
        return

    data = await state.get_data()
    phone = data["phone"]

    existing = await session.scalar(
        select(PhoneInvite).where(
            PhoneInvite.class_id == school_class.id, PhoneInvite.phone == phone
        )
    )
    if existing is not None:
        existing.role = target
        existing.invited_by = callback.from_user.id
        existing.used_by = None
        existing.used_at = None
    else:
        session.add(
            PhoneInvite(
                class_id=school_class.id,
                phone=phone,
                role=target,
                invited_by=callback.from_user.id,
            )
        )
    await audit.record(
        session,
        school_class.id,
        callback.from_user.id,
        "access.invite",
        f"приглашение +{phone} → {target.title_ru}",
    )
    await session.commit()
    await state.clear()

    await callback.message.edit_text(
        f"✅ Приглашение создано.\n\n"
        f"<code>+{phone}</code> → <b>{target.title_ru}</b>\n\n"
        "Передайте человеку ссылку на бота. После команды /start ему нужно "
        "нажать «Поделиться номером».",
        reply_markup=back_to_menu(),
    )
    await callback.answer()


@router.callback_query(AccessAction.filter(F.action == "pick_member"))
async def pick_member(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return

    members = list(
        await session.scalars(
            select(BotUser)
            .where(
                BotUser.class_id == school_class.id,
                BotUser.telegram_id != callback.from_user.id,
            )
            .order_by(BotUser.id)
        )
    )
    if not members:
        await callback.answer("Больше никого нет", show_alert=True)
        return

    rows = [
        [
            InlineKeyboardButton(
                text=f"{member.full_name or member.telegram_id} — {member.role.title_ru}",
                callback_data=AccessAction(
                    action="set_role", value=str(member.telegram_id)
                ).pack(),
            )
        ]
        for member in members[:20]
    ]
    await callback.message.edit_text("Кому меняем роль?", reply_markup=back_to_menu(rows))
    await callback.answer()


@router.callback_query(AccessAction.filter(F.action == "set_role"))
async def set_role_prompt(
    callback: CallbackQuery,
    callback_data: AccessAction,
    role: Role | None,
) -> None:
    if role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return

    options = _grantable_roles(role)
    rows = [
        [
            InlineKeyboardButton(
                text=option.title_ru,
                callback_data=RolePick(role=option.value, target=callback_data.value).pack(),
            )
        ]
        for option in options
    ]
    rows.append(
        [
            InlineKeyboardButton(
                text="🚫 Убрать доступ",
                callback_data=AccessAction(action="revoke", value=callback_data.value).pack(),
                style=DANGER,
            )
        ]
    )
    await callback.message.edit_text("Новая роль:", reply_markup=back_to_menu(rows))
    await callback.answer()


@router.callback_query(RolePick.filter(F.target != ""))
async def apply_role(
    callback: CallbackQuery,
    callback_data: RolePick,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return

    target_role = Role(callback_data.role)
    if not can_grant(role, target_role):
        await callback.answer("Нельзя выдать роль выше вашей", show_alert=True)
        return

    member = await session.scalar(
        select(BotUser).where(
            BotUser.class_id == school_class.id,
            BotUser.telegram_id == int(callback_data.target),
        )
    )
    if member is None:
        await callback.answer("Пользователь не найден", show_alert=True)
        return

    # Guard against an admin demoting somebody at or above their own level.
    if member.role.rank >= role.rank:
        await callback.answer("Нельзя менять роль этого пользователя", show_alert=True)
        return

    was = member.role
    member.role = target_role
    member.granted_by = callback.from_user.id
    await audit.record(
        session,
        school_class.id,
        callback.from_user.id,
        "access.role",
        f"{member.full_name or member.telegram_id}: {was.title_ru} → {target_role.title_ru}",
    )
    await session.commit()

    await callback.message.edit_text(
        f"✅ Роль обновлена: <b>{target_role.title_ru}</b>", reply_markup=back_to_menu()
    )
    await callback.answer()


@router.callback_query(AccessAction.filter(F.action == "revoke"))
async def revoke(
    callback: CallbackQuery,
    callback_data: AccessAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return

    member = await session.scalar(
        select(BotUser).where(
            BotUser.class_id == school_class.id,
            BotUser.telegram_id == int(callback_data.value),
        )
    )
    if member is None:
        await callback.answer("Пользователь не найден", show_alert=True)
        return
    if member.role.rank >= role.rank:
        await callback.answer("Нельзя убрать доступ этому пользователю", show_alert=True)
        return

    await audit.record(
        session,
        school_class.id,
        callback.from_user.id,
        "access.revoke",
        f"убран доступ: {member.full_name or member.telegram_id} ({member.role.title_ru})",
    )
    await session.delete(member)
    await session.commit()
    await callback.message.edit_text("🚫 Доступ убран.", reply_markup=back_to_menu())
    await callback.answer()
