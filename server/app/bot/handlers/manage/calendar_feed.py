"""«📅 Календарь»: the class's read-only calendar feed and its secret link.

Part of :mod:`app.bot.handlers.manage`; the rules every screen of it
follows are in that package's docstring.
"""

from __future__ import annotations

from aiogram import F, Router
from aiogram.filters import Command
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, InlineKeyboardButton, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot import manage_render as mr
from app.bot.handlers.manage._common import NO_ACCESS, _allowed, _refusal
from app.bot.keyboards import back_to_menu
from app.bot.manage_keyboards import ManageAction, back_to
from app.config import get_settings
from app.models import Role, SchoolClass
from app.services import audit
from app.services import calendar as calendar_service

router = Router(name="manage.calendar_feed")


# --------------------------------------------------------------------------
# 📅 Calendar
# --------------------------------------------------------------------------


async def _calendar_text(session: AsyncSession, school_class: SchoolClass, rotated: bool) -> str:
    base = get_settings().public_base_url.rstrip("/")
    if not base:
        return mr.render_calendar(None)
    if rotated:
        token = await calendar_service.rotate_calendar_token(session, school_class)
    else:
        token = await calendar_service.ensure_calendar_token(session, school_class)
    return mr.render_calendar(f"{base}/api/v1/calendar/{token}.ics", rotated=rotated)


def _calendar_keyboard(role: Role):
    rows: list[list[InlineKeyboardButton]] = []
    if role.at_least(Role.ADMIN):
        rows.append(
            [
                InlineKeyboardButton(
                    text="🔁 Новая ссылка",
                    callback_data=ManageAction(action="rotate_feed").pack(),
                )
            ]
        )
        rows.append(back_to("root"))
    return back_to_menu(rows)


@router.message(Command("calendar"))
async def cmd_calendar(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Any member may subscribe: the feed is read-only and its secret is not
    the join code, so a calendar URL cannot be turned into write access."""
    if not _allowed(school_class, role, Role.VIEWER):
        await message.answer(NO_ACCESS)
        return
    await state.clear()
    await message.answer(
        await _calendar_text(session, school_class, rotated=False),
        reply_markup=_calendar_keyboard(role),
    )


@router.callback_query(ManageAction.filter(F.action == "calendar"))
async def calendar_card(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.VIEWER):
        await callback.answer(NO_ACCESS, show_alert=True)
        return
    await callback.message.edit_text(
        await _calendar_text(session, school_class, rotated=False),
        reply_markup=_calendar_keyboard(role),
    )
    await callback.answer()


@router.callback_query(ManageAction.filter(F.action == "rotate_feed"))
async def calendar_rotate(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Every existing subscription stops updating — that is the point."""
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    text = await _calendar_text(session, school_class, rotated=True)
    await audit.record(
        session, school_class.id, callback.from_user.id, "calendar.rotate",
        "выдана новая ссылка на календарь, старая отключена",
    )
    await session.commit()
    await callback.message.edit_text(text, reply_markup=_calendar_keyboard(role))
    await callback.answer("Ссылка обновлена")
