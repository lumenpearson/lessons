"""The calendar: a month of days, and the card that opens on one of them.

The card is the half that makes the grid worth having. Picking a date used to
be a step inside one flow; here it is a place — what that day already is, and
everything that may be назначено on it, for whoever is looking. Each of those
buttons carries the date into the flow's own handler, so nothing about
homework, замены, события or особые дни is implemented twice.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import datetime

from aiogram import F, Router
from aiogram.filters import Command
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, InlineKeyboardMarkup, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.calendar_keyboard import (
    FLOW_MINIMUM,
    CalendarAction,
    day_card_keyboard,
    month_keyboard,
)
from app.bot.keyboards import Menu
from app.bot.render import render_day
from app.config import get_settings
from app.models import Role, SchoolClass
from app.schedule import ScheduleResolver

router = Router(name="calendar")

CALENDAR_PROMPT = (
    "📆 <b>Календарь</b>\n\n"
    "Выберите день — бот покажет, что в нём, и предложит, что в него добавить."
)

NO_ACCESS = "Нет доступа"
NEED_EDITOR = "Нужна роль редактора"


def _today(school_class: SchoolClass | None = None) -> Date:
    """Today on the class's clock, not the server's."""
    tz = school_class.tz if school_class is not None else get_settings().tz
    return datetime.now(tz).date()


def _month_or_none(raw: str) -> tuple[int, int] | None:
    if len(raw) != 6 or not raw.isdigit():
        return None
    year, month = int(raw[:4]), int(raw[4:])
    return (year, month) if 1 <= month <= 12 else None


def _date_or_none(raw: str) -> Date | None:
    if len(raw) != 8 or not raw.isdigit():
        return None
    try:
        return Date(int(raw[:4]), int(raw[4:6]), int(raw[6:]))
    except ValueError:
        return None


def open_month(flow: str, today: Date, at: Date | None = None) -> InlineKeyboardMarkup:
    """The keyboard a flow's «на какой день?» is asked with.

    One call for every entry point, so «Замены» and «Календарь» cannot drift
    into showing two different grids.
    """
    anchor = at or today
    return month_keyboard(flow, anchor.year, anchor.month, today)


async def _day_card(
    session: AsyncSession, school_class: SchoolClass, role: Role, day: Date
) -> tuple[str, InlineKeyboardMarkup]:
    today = _today(school_class)
    days = await ScheduleResolver(session, school_class).resolve_range(day, 1)
    return render_day(days[0], today), day_card_keyboard(day, role)


@router.message(Command("day"))
async def cmd_day(
    message: Message,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await message.answer(NO_ACCESS)
        return
    await state.clear()
    today = _today(school_class)
    await message.answer(CALENDAR_PROMPT, reply_markup=open_month("day", today))


@router.callback_query(Menu.filter(F.action == "day"))
async def calendar_root(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer(NO_ACCESS, show_alert=True)
        return
    await state.clear()
    today = _today(school_class)
    await callback.message.edit_text(CALENDAR_PROMPT, reply_markup=open_month("day", today))
    await callback.answer()


@router.callback_query(CalendarAction.filter(F.action == "skip"))
async def calendar_skip(callback: CallbackQuery) -> None:
    """The heading, the weekday initials and the padding cells.

    They have to be buttons — a keyboard row holds nothing else — and every
    button must be answered or Telegram leaves a spinner on it.
    """
    await callback.answer()


@router.callback_query(CalendarAction.filter(F.action == "nav"))
async def calendar_nav(
    callback: CallbackQuery,
    callback_data: CalendarAction,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """A month step swaps the grid and leaves the question above it alone."""
    minimum = FLOW_MINIMUM.get(callback_data.flow)
    if school_class is None or role is None or minimum is None:
        await callback.answer(NO_ACCESS, show_alert=True)
        return
    if not role.at_least(minimum):
        await callback.answer(NEED_EDITOR, show_alert=True)
        return

    month = _month_or_none(callback_data.value)
    if month is None:
        await callback.answer("Непонятный месяц", show_alert=True)
        return

    await callback.message.edit_reply_markup(
        reply_markup=month_keyboard(callback_data.flow, *month, _today(school_class))
    )
    await callback.answer()


@router.callback_query(CalendarAction.filter(F.action == "open"))
async def calendar_open(
    callback: CallbackQuery,
    callback_data: CalendarAction,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Back to the grid from a day card, which replaced the question above it."""
    if school_class is None or role is None:
        await callback.answer(NO_ACCESS, show_alert=True)
        return

    today = _today(school_class)
    month = _month_or_none(callback_data.value) or (today.year, today.month)
    await callback.message.edit_text(
        CALENDAR_PROMPT, reply_markup=month_keyboard("day", *month, today)
    )
    await callback.answer()


@router.callback_query(CalendarAction.filter(F.action == "card"))
async def calendar_card(
    callback: CallbackQuery,
    callback_data: CalendarAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer(NO_ACCESS, show_alert=True)
        return

    day = _date_or_none(callback_data.value)
    if day is None:
        await callback.answer("Непонятная дата", show_alert=True)
        return

    text, keyboard = await _day_card(session, school_class, role, day)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()
