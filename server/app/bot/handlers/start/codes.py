"""The codes that put a phone in a class: the class code (``/code``, «🔁 Сменить
код») and a personal one for the presser's own phone («📱 Подключить телефон»).

Part of :mod:`app.bot.handlers.start`. The third way in — a phone already in
the class linking itself through ``/start link_…`` — is ``menu``'s, because it
has to be asked before the bare ``/start``.
"""

from __future__ import annotations

from html import escape

from aiogram import F, Router
from aiogram.filters import Command
from aiogram.types import CallbackQuery, InlineKeyboardButton, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.keyboards import ClassAction, Menu, back_to_menu
from app.bot.render import plural
from app.models import JoinMode, Role, SchoolClass
from app.security import new_join_code
from app.services import audit, device_invites

router = Router(name="start.codes")


@router.message(Command("code"))
async def cmd_code(
    message: Message,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Show the join code the Android app needs.

    Still shown while the class is on invitations, because it is not gone —
    it is dormant, and the switch back makes it work again. What changes is
    the sentence under it: «введите его в приложении» about a code the app
    now refuses would send an admin to look for the fault in the app.
    """
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await message.answer("Команда доступна администраторам класса.")
        return
    if school_class.join_mode is JoinMode.INVITE:
        tail = (
            "Сейчас он ничего не открывает: класс подключает телефоны только "
            "по личным приглашениям. Личный код на один телефон берут в меню "
            "/start — кнопка «📱 Подключить телефон»; она есть у каждого, кто "
            "в классе.\n\n"
            "Вернуть вход по коду можно в разделе «👥 Доступ»."
        )
    else:
        tail = (
            "Его вводят в приложении при первом запуске. "
            "Код даёт только чтение расписания."
        )
    await message.answer(
        f"Код класса <b>{escape(school_class.name)}</b>: "
        f"<code>{escape(school_class.join_code)}</code>\n\n" + tail,
    )


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
        f"Новый код класса: <code>{escape(school_class.join_code)}</code>\n\n"
        "Уже подключённые устройства продолжат работать.",
        reply_markup=back_to_menu(),
    )
    await callback.answer("Код обновлён")


@router.callback_query(Menu.filter(F.action == "phone"))
async def phone_code(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """A code for the presser's own phone, in either join mode.

    No role check beyond membership, and the bound is on what the phone may
    **do**, not on how many phones there are: it acts with whatever role this
    account holds at the moment of each request, so an observer minting one
    gets an observer's phone, and a demotion follows it the same second.

    What it is *not* bounded by is forwarding. Anybody in the class can press
    this repeatedly and pass the codes on, so in «по приглашению» the class is
    joinable by whoever a member chooses to let in — the bot has replaced one
    shared secret with a named person deciding each time, not with a smaller
    number of readers. Every phone that arrives this way carries the account
    that let it in, in «📱 Устройства» and in the journal, which is the part
    that makes the trade worth it.
    """
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return

    code = await device_invites.mint(
        session, telegram_id=callback.from_user.id, class_id=school_class.id
    )
    await audit.record(
        session,
        school_class.id,
        callback.from_user.id,
        "device.invite",
        "выдан личный код на подключение телефона",
    )
    await session.commit()
    minutes = plural(device_invites.CODE_MINUTES, "минуту", "минуты", "минут")
    await callback.message.edit_text(
        "📱 <b>Подключить телефон</b>\n\n"
        # Named, because the code is minted for whichever class is active and
        # somebody in two of them has no other way to see which that is. A
        # phone silently joined to the wrong child's class looks exactly like a
        # phone joined to the right one.
        f"Класс: <b>{escape(school_class.name)}</b>\n"
        f"Ваш код: <code>{code}</code>\n\n"
        f"Введите его в приложении при первом запуске. Код действует {minutes} "
        "и годится для одного телефона — для второго нажмите кнопку ещё раз.\n\n"
        "Телефон, который его введёт, сразу станет вашим: он будет работать с "
        f"вашей ролью (<b>{role.title_ru}</b>), и отдельно присылать код "
        "привязки не нужно.",
        # The button again, so «нажмите кнопку ещё раз» is something the person
        # can actually do from the screen that says it.
        reply_markup=back_to_menu(
            [
                [
                    InlineKeyboardButton(
                        text="📱 Ещё код", callback_data=Menu(action="phone").pack()
                    )
                ]
            ]
        ),
    )
    await callback.answer()
