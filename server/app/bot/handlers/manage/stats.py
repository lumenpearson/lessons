"""«📊 Статистика» and «🔎 Поиск»: the class's numbers and a homework search.

Part of :mod:`app.bot.handlers.manage`; the rules every screen of it
follows are in that package's docstring.
"""

from __future__ import annotations

from datetime import timedelta

from aiogram import Router
from aiogram.filters import Command, CommandObject
from aiogram.fsm.context import FSMContext
from aiogram.types import Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot import manage_render as mr
from app.bot.handlers.manage._common import _today, needs
from app.bot.keyboards import back_to_menu
from app.models import Role, SchoolClass
from app.services import stats as stats_service
from app.services.manage import search

router = Router(name="manage.stats")

#: Homework rows one search may answer with.
SEARCH_MAX = 15

#: How far back a search looks. Older homework is history nobody is looking
#: for, and including it makes every search slower and noisier.
SEARCH_BACK_DAYS = 30


# --------------------------------------------------------------------------
# 📊 Statistics and 🔎 search
# --------------------------------------------------------------------------


@router.message(Command("stats"))
@needs(Role.EDITOR)
async def cmd_stats(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await state.clear()
    numbers = await stats_service.class_stats(session, school_class)
    await message.answer(
        stats_service.render_stats(numbers, school_class), reply_markup=back_to_menu()
    )


@router.message(Command("find"))
@needs(Role.VIEWER)
async def cmd_find(
    message: Message,
    command: CommandObject,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    """Search this class's homework. Any member may: it is the same text the
    day view already shows them, only reachable by memory instead of by date."""
    needle = (command.args or "").strip()
    if len(needle) < 2:
        await message.answer(
            "🔎 <b>Поиск по домашним заданиям</b>\n\n"
            "Напишите, что искать: <code>/find параграф 12</code>.\n"
            "Ищу по тексту задания и по названию предмета, "
            "минимум два символа."
        )
        return

    today = _today(school_class)
    rows = await search.homework(
        session,
        school_class.id,
        needle,
        since=today - timedelta(days=SEARCH_BACK_DAYS),
        limit=SEARCH_MAX,
    )
    await message.answer(mr.render_search(needle, rows, today), reply_markup=back_to_menu())
