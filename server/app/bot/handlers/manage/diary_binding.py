"""«📒 Дневник» on the class card: binding the class to an electronic diary.

Part of :mod:`app.bot.handlers.manage`; the rules every screen of it
follows are in that package's docstring.
"""

from __future__ import annotations

from html import escape

from aiogram import F, Router
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.handlers.manage._common import _allowed
from app.bot.handlers.manage.class_card import _class_card
from app.bot.keyboards import DiarySchoolPick
from app.bot.manage_keyboards import (
    DIARY_SCHOOLS_MAX,
    ManageAction,
    diary_provider_menu,
    diary_region_menu,
    diary_school_menu,
)
from app.bot.states import BindDiary
from app.models import Role, SchoolClass
from app.providers.diary.errors import AddressRefused, DiaryError, SignInUnsupported
from app.providers.diary.registry import NETSCHOOL, PETERSBURG
from app.providers.netschool import regions as ns_regions
from app.providers.netschool.client import NetSchoolClient
from app.services import audit, diary_link
from app.services import diary as diary_service

router = Router(name="manage.diary_binding")


@router.callback_query(ManageAction.filter(F.action == "diary_bind"))
async def class_diary_bind(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Bind or unbind the class's electronic diary.

    Binding gives the class nothing and takes nothing: it puts «📒 Мой дневник»
    on every member's menu, and what is behind that button is each member's own
    account. Unbinding likewise only removes the door — the sessions people
    opened stay theirs until they sign out, and are dropped with the class.
    """
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return

    if school_class.diary_provider:
        # Bound → unbind. The door goes; the sessions people opened stay theirs
        # until they sign out or the class is deleted, as three screens promise.
        # Nothing is expired here, deliberately: with no binding the bot reads
        # none of them anyway, and a later bind expires exactly those it does
        # not read (`diary_service.expire_off_binding`) — which, unlike this,
        # knows which diary the class went to.
        school_class.diary_provider = None
        school_class.diary_region = None
        school_class.diary_school_id = None
        school_class.diary_school_name = None
        # An outstanding sign-in link would still open a form; the submit
        # refuses an unbound class, but only a dropped ticket survives a
        # rebind that follows at once (#137).
        await diary_link.drop_for_class(session, school_class.id)
        await audit.record(
            session, school_class.id, callback.from_user.id, "class.diary", "дневник отвязан"
        )
        await session.commit()
        await _redraw_class(callback, session, school_class, role)
        await callback.answer("Дневник отвязан")
        return

    # Unbound → choose which diary. There is more than one now, so binding is a
    # small chooser rather than a single toggle.
    await callback.message.edit_text(
        "📒 <b>Электронный дневник</b>\n\nВыберите, к какому дневнику привязать класс.",
        reply_markup=diary_provider_menu(),
    )
    await callback.answer()


@router.callback_query(ManageAction.filter(F.action == "diary_prov"))
async def class_diary_provider(
    callback: CallbackQuery,
    callback_data: ManageAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Second step of binding: the provider was picked."""
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return
    if callback_data.value == PETERSBURG:
        school_class.diary_provider = PETERSBURG
        school_class.diary_region = None
        school_class.diary_school_id = None
        school_class.diary_school_name = None
        # The members' «Сетевой город» sessions read a diary the class has
        # left; expired in the same commit as the binding they no longer fit.
        # And the sign-in links minted under the old binding go with it, or a
        # form drawn for one diary sends its password to the other (#137).
        await diary_service.expire_off_binding(session, school_class)
        await diary_link.drop_for_class(session, school_class.id)
        await audit.record(
            session, school_class.id, callback.from_user.id, "class.diary",
            "привязан дневник Санкт-Петербурга",
        )
        await session.commit()
        await _redraw_class(callback, session, school_class, role)
        await callback.answer("Привязан дневник Санкт-Петербурга")
        return
    if callback_data.value == NETSCHOOL:
        await callback.message.edit_text(
            "🌆 <b>Сетевой город</b>\n\nВыберите регион.", reply_markup=diary_region_menu()
        )
        await callback.answer()
        return
    await callback.answer("Неизвестный дневник", show_alert=True)


@router.callback_query(ManageAction.filter(F.action == "diary_reg"))
async def class_diary_region(
    callback: CallbackQuery,
    callback_data: ManageAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
    state: FSMContext,
) -> None:
    """Third step: the «Сетевой город» region was picked. Check the region
    accepts a password from us before asking for the school, so the admin
    learns once rather than every family finding out at sign-in."""
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return
    region = ns_regions.get(callback_data.value)
    if region is None or not region.password:
        await callback.answer("Этот регион недоступен", show_alert=True)
        return
    ok, why = await _probe_region(region)
    if not ok:
        await callback.answer(why, show_alert=True)
        return
    await state.set_state(BindDiary.school)
    await state.update_data(diary_region=region.key, diary_class_id=school_class.id)
    await callback.message.edit_text(
        f"🌆 <b>Сетевой город</b> · {escape(region.title)}\n\n"
        "Напишите часть названия школы — я поищу её на сайте региона.",
    )
    await callback.answer()


async def _probe_region(region) -> tuple[bool, str]:  # noqa: ANN001
    """Ask the region's sign-in options, so a class is not bound to a diary no
    family can reach. @return (ok, message-if-not)."""
    try:
        await NetSchoolClient(region).login_allowed()
    except SignInUnsupported:
        return False, "Этот регион пускает только через Госуслуги — привязать нельзя."
    except AddressRefused:
        return False, "Сайт региона не отвечает нашему серверу. Сообщите владельцу проекта."
    except DiaryError:
        return False, "Сайт региона сейчас недоступен. Попробуйте позже."
    return True, ""


@router.message(BindDiary.school)
async def class_diary_search(
    message: Message,
    session: AsyncSession,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Fourth step: a school-name query. Show what the region's search found.

    FSM state is per-user and attacker-controlled, so the role is re-checked
    here as at every step — a non-admin who set itself into this state would
    otherwise turn the class card into an outbound search against a region
    server.
    """
    if not _allowed(school_class, role, Role.ADMIN):
        await state.clear()
        return
    data = await state.get_data()
    region = ns_regions.get(data.get("diary_region"))
    class_id = data.get("diary_class_id")
    if region is None or class_id is None:
        await state.clear()
        return
    query = (message.text or "").strip()
    if len(query) < 2:
        await message.answer("Введите хотя бы два символа названия школы.")
        return
    try:
        schools = await NetSchoolClient(region).schools_search(query)
    except DiaryError:
        await message.answer("Не удалось найти школы — сайт региона не ответил. Попробуйте позже.")
        return
    if not schools:
        await message.answer("Школа не найдена. Уточните запрос.")
        return
    shown = schools[:DIARY_SCHOOLS_MAX]
    await state.update_data(diary_schools=shown)
    caption = f"🌆 <b>Сетевой город</b> · {escape(region.title)}\n\nВыберите школу:"
    more = len(schools) > len(shown)
    if more:
        caption += f"\n\nПоказаны первые {len(shown)} — уточните запрос, если нужной нет."
    await message.answer(caption, reply_markup=diary_school_menu(shown, more=more))


@router.callback_query(DiarySchoolPick.filter(F.action == "cancel"))
async def class_diary_cancel(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
    state: FSMContext,
) -> None:
    # A role check like every other step: the callback payload is whatever the
    # client sent, and this one redraws the (admin-only) class card, so a
    # наблюдатель who pressed it would otherwise be shown it.
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await state.clear()
        await callback.answer("Только для администраторов", show_alert=True)
        return
    await state.clear()
    await _redraw_class(callback, session, school_class, role)
    await callback.answer("Отменено")


@router.callback_query(DiarySchoolPick.filter(F.action == "pick"))
async def class_diary_school(
    callback: CallbackQuery,
    callback_data: DiarySchoolPick,
    session: AsyncSession,
    role: Role | None,
    state: FSMContext,
) -> None:
    """Last step: a school was picked. Bind the class to it."""
    data = await state.get_data()
    schools = data.get("diary_schools") or []
    region = ns_regions.get(data.get("diary_region"))
    class_id = data.get("diary_class_id")
    index = callback_data.value
    if region is None or class_id is None or not (0 <= index < len(schools)):
        await state.clear()
        await callback.answer("Список устарел — начните заново.", show_alert=True)
        return
    school_class = await session.get(SchoolClass, class_id)
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await state.clear()
        await callback.answer("Только для администраторов", show_alert=True)
        return
    school = schools[index]
    school_class.diary_provider = NETSCHOOL
    school_class.diary_region = region.key
    school_class.diary_school_id = int(school["id"])
    school_class.diary_school_name = str(school["name"])[:300]
    # Sessions on another region's server, or Petersburg's, read a diary the
    # class has left: expired with the binding, so the keep-alive stops
    # pinging a server nobody here uses. The same region keeps them. The
    # sign-in links go whatever the region: each names one school (#137).
    await diary_service.expire_off_binding(session, school_class)
    await diary_link.drop_for_class(session, school_class.id)
    await audit.record(
        session, school_class.id, callback.from_user.id, "class.diary",
        f"привязан «Сетевой город»: {region.title}, {str(school['name'])[:200]}",
    )
    await session.commit()
    await state.clear()
    await _redraw_class(callback, session, school_class, role)
    await callback.answer("Дневник привязан")


async def _redraw_class(
    callback: CallbackQuery, session: AsyncSession, school_class: SchoolClass, role: Role
) -> None:
    text, keyboard = await _class_card(session, school_class, role, callback.from_user.id)
    await callback.message.edit_text(text, reply_markup=keyboard)
