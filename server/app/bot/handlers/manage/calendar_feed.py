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

from app.bot.handlers.manage._common import needs
from app.bot.keyboards import back_to_menu
from app.bot.manage_keyboards._common import ManageAction, back_to
from app.bot.manage_render import calendar_feed as mr
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
        # Committed before the address is shown: an address for a secret the
        # database never kept is a subscription that answers 404 for ever.
        await session.commit()
    return mr.render_calendar(calendar_service.feed_url(base, token), rotated=rotated)


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
@needs(Role.VIEWER)
async def cmd_calendar(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    """Any member may subscribe: the feed is read-only and its secret is not
    the join code, so a calendar URL cannot be turned into write access."""
    await state.clear()
    await message.answer(
        await _calendar_text(session, school_class, rotated=False),
        reply_markup=_calendar_keyboard(role),
    )


@router.callback_query(ManageAction.filter(F.action == "calendar"))
@needs(Role.VIEWER)
async def calendar_card(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await callback.message.edit_text(
        await _calendar_text(session, school_class, rotated=False),
        reply_markup=_calendar_keyboard(role),
    )
    await callback.answer()


@router.callback_query(ManageAction.filter(F.action == "rotate_feed"))
@needs(Role.ADMIN)
async def calendar_rotate(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    """Every existing subscription stops updating — that is the point."""
    text = await _calendar_text(session, school_class, rotated=True)
    await audit.record(
        session, school_class.id, callback.from_user.id, "calendar.rotate",
        "выдана новая ссылка на календарь, старая отключена",
    )
    await session.commit()
    await callback.message.edit_text(text, reply_markup=_calendar_keyboard(role))
    await callback.answer("Ссылка обновлена")
