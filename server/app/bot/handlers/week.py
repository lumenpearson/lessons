"""The week at a glance and «что дальше» — the two views people open at the
bus stop.

Both are read-only and available to every role. What matters here is the
clock: every "now" is taken in the class's own zone, because the server runs
wherever Vercel put it and a class in Vladivostok is already at the third
lesson when Moscow wakes up.
"""

from __future__ import annotations

import re
from datetime import datetime, timedelta
from html import unescape

from aiogram import F, Router
from aiogram.filters import Command
from aiogram.types import CallbackQuery, Message
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.keyboards import Menu, WeekNav, next_keyboard, shift_weeks, week_nav
from app.bot.render import INVISIBLE, render_next, render_week
from app.models import Role, SchoolClass, TimetableEntry, WeekParity
from app.schedule import ScheduleResolver

router = Router(name="week")

#: How far the ‹ › buttons may walk. A year either way covers "what was the
#: timetable in September" and stops a held-down button from asking the
#: resolver about the year 2400.
MAX_WEEK_OFFSET = 52

_TAG = re.compile(r"<[^>]+>")


def _now(school_class: SchoolClass) -> datetime:
    """Now, in the class's own zone rather than the server's."""
    return datetime.now(school_class.tz)


async def _parity_matters(session: AsyncSession, school_class: SchoolClass) -> bool:
    """Whether this class alternates weeks at all. A class that does not must
    not be told which week it is - the line would only raise the question."""
    row = await session.scalar(
        select(TimetableEntry.id)
        .where(
            TimetableEntry.class_id == school_class.id,
            TimetableEntry.parity != WeekParity.ANY,
        )
        .limit(1)
    )
    return row is not None


async def week_text(session: AsyncSession, school_class: SchoolClass, offset: int) -> str:
    """Monday–Saturday of the week ``offset`` weeks from the current one."""
    today = _now(school_class).date()
    anchor = shift_weeks(today, offset)
    if anchor is None:
        # The offset came off a ‹ › button in callback data; a crafted one is
        # an OverflowError rather than a far-away Monday.
        return "Такой недели нет."
    monday = anchor - timedelta(days=anchor.weekday())
    days = await ScheduleResolver(session, school_class).resolve_range(monday, 6)
    return render_week(days, today, await _parity_matters(session, school_class))


async def next_text(session: AsyncSession, school_class: SchoolClass) -> str:
    now = _now(school_class)
    resolver = ScheduleResolver(session, school_class)
    day = (await resolver.resolve_range(now.date(), 1))[0]
    next_day = await resolver.next_school_day(now.date())
    return render_next(day, next_day, now)


def distinct_from(text: str, message: Message) -> str:
    """``text``, made different from what ``message`` already shows.

    «Обновить» pressed twice within the same minute renders the same view, and
    Telegram answers an unchanged edit with a 400. The global error handler
    swallows that one, but the user then sees a button that did nothing.
    Appending an invisible character (and dropping it again on the next press)
    keeps every refresh a real edit.
    """
    current = (getattr(message, "text", None) or "").replace(INVISIBLE, "")
    plain = unescape(_TAG.sub("", text))
    if plain != current:
        return text
    if (getattr(message, "text", None) or "").endswith(INVISIBLE):
        return text
    return text + INVISIBLE


def _clamp_offset(offset: int) -> int:
    return max(-MAX_WEEK_OFFSET, min(MAX_WEEK_OFFSET, offset))


# --------------------------------------------------------------------------
# Week
# --------------------------------------------------------------------------


@router.message(Command("week"))
async def cmd_week(
    message: Message,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await message.answer("Нет доступа. Откройте /start, чтобы получить его.")
        return
    await message.answer(await week_text(session, school_class, 0), reply_markup=week_nav(0))


@router.callback_query(Menu.filter(F.action == "week"))
async def menu_week(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    await callback.message.edit_text(
        await week_text(session, school_class, 0), reply_markup=week_nav(0)
    )
    await callback.answer()


@router.callback_query(WeekNav.filter())
async def show_week(
    callback: CallbackQuery,
    callback_data: WeekNav,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    offset = _clamp_offset(callback_data.offset)
    await callback.message.edit_text(
        await week_text(session, school_class, offset), reply_markup=week_nav(offset)
    )
    await callback.answer()


# --------------------------------------------------------------------------
# «Что дальше»
# --------------------------------------------------------------------------


@router.message(Command("next"))
async def cmd_next(
    message: Message,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await message.answer("Нет доступа. Откройте /start, чтобы получить его.")
        return
    await message.answer(await next_text(session, school_class), reply_markup=next_keyboard())


@router.callback_query(Menu.filter(F.action == "next"))
async def menu_next(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Both the menu button and «🔄 Обновить» land here."""
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    text = distinct_from(await next_text(session, school_class), callback.message)
    await callback.message.edit_text(text, reply_markup=next_keyboard())
    await callback.answer()
