"""«📱 /link» and «🙋 /request»: what a member does for themselves, and the admins' answer.

Part of :mod:`app.bot.handlers.manage`; the rules every screen of it
follows are in that package's docstring.
"""

from __future__ import annotations

import logging
from html import escape

from aiogram import F, Router
from aiogram.filters import Command, CommandObject
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.handlers.manage._common import (
    _bot_of,
    _int_or_none,
    needs,
)
from app.bot.keyboards import back_to_menu, cancel_keyboard
from app.bot.manage_keyboards.requests import RequestAction, request_keyboard
from app.bot.manage_render import _common as mr
from app.bot.manage_states import RequestAccess
from app.models import AccessRequest, Role, SchoolClass
from app.services import access as access_service
from app.services import audit, linking
from app.services.manage import requests as requests_service

log = logging.getLogger(__name__)

router = Router(name="manage.requests")


# --------------------------------------------------------------------------
# 📱 /link and 🙋 /request
# --------------------------------------------------------------------------


@router.message(Command("link"))
@needs(Role.VIEWER)
async def cmd_link(
    message: Message,
    command: CommandObject,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    """Attach the phone showing ``code`` to this account.

    No role check beyond membership: linking gives the phone *this* account's
    role, whatever it is, so it can never be more than the person already has.
    """
    code = (command.args or "").strip()
    if not 1 <= len(code) <= 16:
        await message.answer(
            "📱 <b>Привязка телефона</b>\n\n"
            "Откройте в приложении экран привязки и пришлите код: "
            "<code>/link ABC123</code>."
        )
        return

    device = await linking.link_device(session, code, message.from_user.id)
    if device is None:
        await message.answer(
            "Код не подошёл. Он действует один раз — откройте экран привязки "
            "в приложении ещё раз и пришлите новый."
        )
        return

    device_role = await linking.effective_role(session, device)
    name = escape(device.device_name or "Телефон")
    if device_role is not None and device_role.at_least(Role.EDITOR):
        tail = f"Ваша роль: <b>{device_role.title_ru}</b> — приложение может редактировать."
    else:
        title = device_role.title_ru if device_role is not None else "нет роли"
        tail = (
            f"Ваша роль: <b>{title}</b> — только чтение. "
            "Запросить доступ редактора: /request"
        )
    await audit.record(
        session,
        device.class_id,
        message.from_user.id,
        "device.link",
        f"привязано устройство «{device.device_name or device.id}»",
    )
    await session.commit()
    await message.answer(f"📱 Устройство «{name}» привязано. {tail}")


async def _notify_admins(
    session: AsyncSession,
    bot,
    school_class: SchoolClass,
    request: AccessRequest,
    who: str,
) -> None:
    """Tell every admin, and let one bad recipient be nobody else's problem."""
    if bot is None:
        return

    admins = await requests_service.admins(session, school_class.id)
    body = (
        f"🙋 <b>Запрос доступа</b>\n\n"
        f"{who} просит роль <b>{Role.EDITOR.title_ru}</b> "
        f"в классе <b>{escape(school_class.name)}</b>."
    )
    if request.message:
        body += f"\n\n<i>{escape(request.message)}</i>"

    for admin in admins:
        try:
            await bot.send_message(
                admin.telegram_id, body, reply_markup=request_keyboard(request.id)
            )
        except Exception:  # noqa: BLE001 - one blocked admin is not the requester's problem
            log.warning("could not notify admin %s", admin.telegram_id, exc_info=True)


@router.message(Command("request"))
@needs(Role.VIEWER)
async def cmd_request(
    message: Message,
    command: CommandObject,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    if role.at_least(Role.EDITOR):
        await message.answer(
            f"У вас уже роль <b>{role.title_ru}</b> — запрашивать нечего."
        )
        return

    text = (command.args or "").strip()
    if not text:
        await state.set_state(RequestAccess.message)
        await message.answer(
            "🙋 <b>Запрос доступа редактора</b>\n\n"
            "Напишите пару слов о себе — администратор увидит их вместе с запросом. "
            "Или пришлите <code>-</code>, чтобы отправить без комментария.",
            reply_markup=cancel_keyboard(),
        )
        return

    await _submit_request(message, state, session, school_class, text)


@router.message(RequestAccess.message)
@needs(Role.VIEWER, step=True)
async def request_message(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    if role.at_least(Role.EDITOR):
        await state.clear()
        return
    raw = " ".join((message.text or "").split())
    await _submit_request(
        message, state, session, school_class, None if raw in {"-", "—", ""} else raw
    )


async def _submit_request(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    text: str | None,
) -> None:
    request = await requests_service.submit(
        session, school_class.id, message.from_user.id, text
    )
    who = mr.person(message.from_user.full_name, message.from_user.username, message.from_user.id)
    await audit.record(
        session,
        school_class.id,
        message.from_user.id,
        "access.request",
        f"{message.from_user.full_name or message.from_user.id} просит роль редактора",
    )
    await session.commit()
    await state.clear()

    await _notify_admins(session, _bot_of(message), school_class, request, who)
    await message.answer(
        "✅ Запрос отправлен администраторам класса. Они ответят здесь же."
    )


async def _tell_requester(bot, telegram_id: int, text: str) -> None:
    if bot is None:
        return
    try:
        await bot.send_message(telegram_id, text)
    except Exception:  # noqa: BLE001 - the decision stands whether or not it was delivered
        log.warning("could not tell %s about the decision", telegram_id, exc_info=True)


async def _request_by_id(
    session: AsyncSession, school_class: SchoolClass, raw: str
) -> AccessRequest | None:
    request_id = _int_or_none(raw)
    if request_id is None:
        return None
    return await requests_service.pending_one(session, school_class.id, request_id)


@router.callback_query(RequestAction.filter(F.action == "approve"))
@needs(Role.ADMIN)
async def request_approve(
    callback: CallbackQuery,
    callback_data: RequestAction,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    """Grant the requested role through the same rules as «👥 Доступ».

    Literally the same rules: `services/access.approve_request` is also what
    `PATCH /api/v1/manage/requests/{id}/approve` calls, so a phone and this
    button cannot come to different answers. The refusal it raises carries the
    Russian sentence this alert shows.
    """
    request = await _request_by_id(session, school_class, callback_data.value)
    if request is None:
        await callback.answer("Запрос уже закрыт", show_alert=True)
        return

    target_role = request.requested_role
    try:
        member = await access_service.approve_request(
            session,
            school_class,
            request,
            actor_id=callback.from_user.id,
            actor_role=role,
            role=target_role,
        )
    except access_service.GrantRefused as refused:
        await callback.answer(str(refused), show_alert=True)
        return

    name = mr.person(member.full_name, member.username, member.telegram_id)
    await session.commit()

    await _tell_requester(
        _bot_of(callback),
        request.telegram_id,
        access_service.approval_notice(school_class, target_role),
    )
    await callback.message.edit_text(
        f"✅ {name} — теперь <b>{target_role.title_ru}</b>.", reply_markup=back_to_menu()
    )
    await callback.answer("Выдано")


@router.callback_query(RequestAction.filter(F.action == "decline"))
@needs(Role.ADMIN)
async def request_decline(
    callback: CallbackQuery,
    callback_data: RequestAction,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    request = await _request_by_id(session, school_class, callback_data.value)
    if request is None:
        await callback.answer("Запрос уже закрыт", show_alert=True)
        return

    await requests_service.decline(session, school_class.id, callback.from_user.id, request)
    await session.commit()

    await _tell_requester(
        _bot_of(callback), request.telegram_id, requests_service.decline_notice(school_class)
    )
    await callback.message.edit_text("✖️ Запрос отклонён.", reply_markup=back_to_menu())
    await callback.answer("Отклонено")
