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
from app.bot.render import ACCESS_MEMBERS_MAX, render_access_list
from app.bot.roles import can_grant
from app.bot.states import AddInvite
from app.models import AccessRequest, BotUser, JoinMode, PhoneInvite, Role, SchoolClass
from app.security import normalise_phone
from app.services import audit, device_invites, reminders

router = Router(name="access")

#: Pending requests shown before the member list. More than this and the
#: keyboard stops fitting on a phone; the rest appear as they are answered.
PENDING_MAX = 5

#: Each join mode said as what a phone can do, not as the name of the setting.
#: The admin reading this page is choosing who may connect one, and «открытый»
#: or «по приглашениям» answers that only for somebody who already knows which
#: is which — which is nobody the first time they open the page.
JOIN_MODE_TEXT = {
    JoinMode.OPEN: (
        "🔓 <b>Телефон подключается кодом класса.</b> Код один на всех: кто "
        "его перешлёт, тот и подключит телефон."
    ),
    JoinMode.INVITE: (
        "🔒 <b>Телефон подключается только по личному приглашению.</b> Код "
        "класса сейчас ничего не открывает: каждый берёт себе одноразовый код "
        "сам — кнопка «📱 Подключить телефон» в меню.\n"
        # Said because the list directly above this line is also called
        # «приглашения» and means something else entirely: those hand a person
        # a role by phone number, this hands a phone a code. An admin reading
        # the page top to bottom meets the word twice in ten lines.
        "<i>Это не те приглашения, что в списке выше: там — доступ человеку по "
        "номеру телефона, здесь — код на один телефон.</i>"
    ),
}


def _grantable_roles(actor: Role) -> list[Role]:
    return [role for role in (Role.VIEWER, Role.EDITOR, Role.ADMIN) if can_grant(actor, role)]


def _int_or_none(raw: str) -> int | None:
    """A telegram id out of callback data, or ``None`` for a crafted one.

    The same shape ``manage.py`` and ``tasks.py`` carry, and for the same
    reason: a client may send any string as callback data, and a bare ``int()``
    on it does not refuse the press — it raises out of the handler, so nothing
    ever answers the callback and the button spins until Telegram gives up.
    """
    try:
        return int(raw)
    except (TypeError, ValueError):
        return None


def _role_or_none(raw: str) -> Role | None:
    """Ditto for the role a picker sends back. ``Role("наблюдтель")`` is a
    ``ValueError``, and one typo away from a real member of the enum."""
    try:
        return Role(raw)
    except ValueError:
        return None


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
        await session.scalars(
            select(PhoneInvite)
            .where(PhoneInvite.class_id == school_class.id)
            .order_by(PhoneInvite.id)
        )
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
                        text="Отклонить",
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
    invite_only = school_class.join_mode is JoinMode.INVITE
    extra.append(
        [
            InlineKeyboardButton(
                text="🔓 Вернуть вход по коду" if invite_only else "🔒 Только по приглашениям",
                # The mode it asks for, not «the other one». A keyboard is a
                # message, messages stay in the chat, and an admin with two
                # «👥 Доступ» pages open would otherwise press a button still
                # labelled «только по приглашениям» and re-publish the class
                # code — the one mistake on this page that hands the class's
                # timetable back to everybody holding an old code.
                callback_data=AccessAction(
                    action="join_mode",
                    value=(JoinMode.OPEN if invite_only else JoinMode.INVITE).value,
                ).pack(),
                # Painted by what pressing it does, like every other toggle
                # here: red while the press switches the class code off, plain
                # while it gives it back.
                style=None if invite_only else DANGER,
            )
        ]
    )

    body = (
        "\n".join(lines)
        + render_access_list(members, invites)
        # Under the list rather than above it, so it sits next to the button
        # that changes it.
        + "\n\n"
        + JOIN_MODE_TEXT[school_class.join_mode]
    )
    await callback.message.edit_text(body, reply_markup=back_to_menu(extra))
    await callback.answer()


@router.callback_query(AccessAction.filter(F.action == "join_mode"))
async def switch_join_mode(
    callback: CallbackQuery,
    callback_data: AccessAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Put the class into the mode the button asked for. Nothing else moves.

    Asked for, not toggled: the press carries the mode it wants, so a page
    left open from before somebody else switched cannot do the opposite of
    what it says. Pressing a button that is already true says so and changes
    nothing, which is the honest answer to a stale keyboard.

    Both directions are reversible and neither touches a device that is
    already connected. That is worth saying out loud in the reply: an admin
    who suspects otherwise either never switches, or switches and then spends
    the evening being asked why the timetable disappeared from thirty phones.
    """
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return

    try:
        wanted = JoinMode(callback_data.value)
    except ValueError:
        # A payload from a build that spelled the modes differently. Refusing
        # beats guessing: the wrong guess re-opens the class code.
        await callback.answer("Кнопка устарела. Откройте «👥 Доступ» заново.", show_alert=True)
        return

    if school_class.join_mode is wanted:
        await callback.message.edit_text(
            f"{JOIN_MODE_TEXT[wanted]}\n\nЭто уже так — ничего не изменилось.",
            reply_markup=back_to_menu(),
        )
        await callback.answer("Уже так")
        return

    to_invite = wanted is JoinMode.INVITE
    school_class.join_mode = wanted
    note = (
        "вход только по личным приглашениям"
        if to_invite
        else "вход по коду класса снова разрешён"
    )
    await audit.record(
        session, school_class.id, callback.from_user.id, "access.join_mode", note
    )
    await session.commit()

    if to_invite:
        tail = (
            "Ничего не отключилось: телефоны, которые уже подключены, "
            "продолжают работать — перестал действовать только код класса. "
            "Личный код на один телефон каждый берёт в меню сам, кнопкой "
            "«📱 Подключить телефон»."
        )
    else:
        tail = (
            "Код класса снова подключает телефоны, и личные коды, которые уже "
            "выданы, тоже действуют."
        )
    await callback.message.edit_text(
        f"{JOIN_MODE_TEXT[school_class.join_mode]}\n\n{tail}",
        reply_markup=back_to_menu(),
    )
    await callback.answer(note.capitalize())


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


# ``target`` is empty only on the picker this flow puts up; the member flow's
# picker carries the member's id. Without that half of the filter this
# handler swallowed both — an admin part-way through «пригласить по номеру»
# who pressed a role on an older «Новая роль» card created a phone invite
# instead, and was told so in a sentence about a different person.
@router.callback_query(AddInvite.role, RolePick.filter(F.target == ""))
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

    target = _role_or_none(callback_data.role)
    if target is None or not can_grant(role, target):
        await callback.answer("Нельзя выдать роль выше вашей", show_alert=True)
        return

    data = await state.get_data()
    phone = data.get("phone")
    if not phone:
        # The state outlived the number it was collected with — a redeploy
        # between the two screens is enough. Better to ask again than to raise.
        await state.clear()
        await callback.answer("Начните заново: номер не сохранился", show_alert=True)
        return

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
        for member in members[:ACCESS_MEMBERS_MAX]
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

    target_role = _role_or_none(callback_data.role)
    if target_role is None or not can_grant(role, target_role):
        await callback.answer("Нельзя выдать роль выше вашей", show_alert=True)
        return

    telegram_id = _int_or_none(callback_data.target)
    member = (
        None
        if telegram_id is None
        else await session.scalar(
            select(BotUser).where(
                BotUser.class_id == school_class.id,
                BotUser.telegram_id == telegram_id,
            )
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

    telegram_id = _int_or_none(callback_data.value)
    member = (
        None
        if telegram_id is None
        else await session.scalar(
            select(BotUser).where(
                BotUser.class_id == school_class.id,
                BotUser.telegram_id == telegram_id,
            )
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
    # Any connect code they are still holding goes with the membership. It is
    # matched by its hash alone, so it would keep letting a phone in for the
    # rest of its fifteen minutes — and in «по приглашению» that is the only
    # door the class has.
    await device_invites.drop_for(
        session, telegram_id=member.telegram_id, class_id=school_class.id
    )
    # Their subscriptions go the same way, and for the same reason: nothing on
    # the sending side re-reads the membership, so a settings row left behind
    # keeps the digests and every substitution arriving in the chat of somebody who
    # is no longer in the class.
    await reminders.drop_for(session, telegram_id=member.telegram_id, class_id=school_class.id)
    await session.delete(member)
    await session.commit()
    await callback.message.edit_text("🚫 Доступ убран.", reply_markup=back_to_menu())
    await callback.answer()
