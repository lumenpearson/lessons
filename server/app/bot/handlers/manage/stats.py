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
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot import manage_render as mr
from app.bot.handlers.manage._common import NO_ACCESS, _allowed, _refusal, _today
from app.bot.keyboards import back_to_menu
from app.models import Homework, Role, SchoolClass
from app.services import stats as stats_service

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
async def cmd_stats(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.EDITOR):
        await message.answer(_refusal(role, Role.EDITOR))
        return
    await state.clear()
    numbers = await stats_service.class_stats(session, school_class)
    await message.answer(
        stats_service.render_stats(numbers, school_class), reply_markup=back_to_menu()
    )


@router.message(Command("find"))
async def cmd_find(
    message: Message,
    command: CommandObject,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Search this class's homework. Any member may: it is the same text the
    day view already shows them, only reachable by memory instead of by date."""
    if not _allowed(school_class, role, Role.VIEWER):
        await message.answer(NO_ACCESS)
        return

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
    # ``lower().contains()`` rather than ILIKE, which SQLite does not have.
    # Both dialects fold Cyrillic: Postgres does it natively, and ``app.db``
    # replaces SQLite's ASCII-only ``lower()`` with Python's on every
    # connection, so the search behaves the same for the developer and for the
    # class.
    pattern = needle.lower()
    rows = list(
        await session.scalars(
            select(Homework)
            .where(
                Homework.class_id == school_class.id,
                Homework.due_date >= today - timedelta(days=SEARCH_BACK_DAYS),
                func.lower(Homework.text).contains(pattern)
                | func.lower(Homework.subject_name).contains(pattern),
            )
            .order_by(Homework.due_date.desc(), Homework.id.desc())
            .limit(SEARCH_MAX)
        )
    )
    await message.answer(mr.render_search(needle, rows, today), reply_markup=back_to_menu())
