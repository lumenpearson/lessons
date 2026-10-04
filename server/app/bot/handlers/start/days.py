"""A day on its own: «📅 Сегодня», «🗓 Завтра», ``/today``, ``/tomorrow`` and the
‹ › between days.

Part of :mod:`app.bot.handlers.start`. ``_today`` is this module's, and a test
that pins the clock patches ``datetime`` here, where ``_today`` reads it.
"""

from __future__ import annotations

from datetime import datetime, timedelta

from aiogram import Router
from aiogram.filters import Command
from aiogram.types import CallbackQuery, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.handlers.start._common import WELCOME_UNKNOWN
from app.bot.keyboards import DayNav, day_nav, request_contact, shift_days
from app.bot.render import render_day
from app.config import get_settings
from app.models import Role, SchoolClass
from app.schedule import ScheduleResolver

router = Router(name="start.days")


def _today(school_class: SchoolClass | None = None) -> datetime:
    """Now, in the class's own zone rather than the server's."""
    tz = school_class.tz if school_class is not None else get_settings().tz
    return datetime.now(tz)


@router.callback_query(DayNav.filter())
async def show_day(
    callback: CallbackQuery,
    callback_data: DayNav,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return

    today = _today(school_class).date()
    target = shift_days(today, callback_data.offset)
    if target is None:
        await callback.answer("Такого дня нет", show_alert=True)
        return
    resolver = ScheduleResolver(session, school_class)
    days = await resolver.resolve_range(target, 1)

    await callback.message.edit_text(
        render_day(days[0], today),
        reply_markup=day_nav(callback_data.offset),
    )
    await callback.answer()


@router.message(Command("today"))
async def cmd_today(
    message: Message,
    session: AsyncSession,
    school_class: SchoolClass | None,
) -> None:
    if school_class is None:
        await message.answer(WELCOME_UNKNOWN, reply_markup=request_contact())
        return
    today = _today(school_class).date()
    days = await ScheduleResolver(session, school_class).resolve_range(today, 1)
    await message.answer(render_day(days[0], today), reply_markup=day_nav(0))


@router.message(Command("tomorrow"))
async def cmd_tomorrow(
    message: Message,
    session: AsyncSession,
    school_class: SchoolClass | None,
) -> None:
    if school_class is None:
        await message.answer(WELCOME_UNKNOWN, reply_markup=request_contact())
        return
    today = _today(school_class).date()
    days = await ScheduleResolver(session, school_class).resolve_range(
        today + timedelta(days=1), 1
    )
    await message.answer(render_day(days[0], today), reply_markup=day_nav(1))
