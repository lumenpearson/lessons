"""«📤 Экспорт» and «📥 Импорт»: the weekly template as text, both ways.

Part of :mod:`app.bot.handlers.manage`; the rules every screen of it
follows are in that package's docstring.
"""

from __future__ import annotations

from html import escape

from aiogram import F, Router
from aiogram.filters import Command
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot import manage_render as mr
from app.bot.handlers.manage._common import _allowed, _refusal
from app.bot.keyboards import WEEKDAY_FULL, back_to_menu, cancel_keyboard
from app.bot.manage_keyboards import ImportAction, import_keyboard
from app.bot.manage_states import ImportTimetable
from app.models import BellPeriod, BellSchedule, Role, SchoolClass, TimetableEntry
from app.services import audit, structure, timetable_io

router = Router(name="manage.import_export")


# --------------------------------------------------------------------------
# 📤 Export and 📥 import of the timetable
# --------------------------------------------------------------------------


# There is deliberately no `_export_parts` here any more.
#
# It re-split the export on the *escaped* length, on the reasoning that «every
# «&» costs four more characters and every «<» or «>» three». That reasoning is
# wrong, and the Bot API says so in one line: `sendMessage`'s `text` is
# «1-4096 characters **after entities parsing**». Telegram counts what it
# parses — `<code>` costs nothing and `&amp;` counts as the one «&» it becomes
# — so an export could not overflow by being escaped, and the thing this
# defended against could not happen.
#
# What it did instead was real. Shrinking the limit by the expansion factor
# turned 200 lines of apostrophes from 6 messages into 34, and 34 consecutive
# `answer` calls into one chat is the per-chat flood limit: `TelegramRetryAfter`
# raised out of a `Message` handler, which has no callback to apologise on, so
# the export stopped partway with nothing said — the exact failure it was
# written to remove, moved to a different trigger.
#
# `split_text` at `CHUNK_LIMIT` is what the export uses, as it did before.


@router.message(Command("export"))
async def cmd_export(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """The whole template as text — which doubles as the backup.

    It is sent in ``<code>`` blocks so Telegram offers a copy button, split at
    4000 characters because the limit is 4096 and the header counts.
    """
    if not _allowed(school_class, role, Role.ADMIN):
        await message.answer(_refusal(role, Role.ADMIN))
        return
    await state.clear()

    entries = list(
        await session.scalars(
            select(TimetableEntry)
            .where(TimetableEntry.class_id == school_class.id)
            .order_by(TimetableEntry.weekday, TimetableEntry.index)
        )
    )
    periods: list[BellPeriod] = []
    if school_class.bell_schedule_id:
        schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
        if schedule is not None:
            periods = list(schedule.periods)

    body = timetable_io.export_timetable(entries, periods)
    if not body:
        await message.answer("Расписание пустое — экспортировать нечего.")
        return

    parts = mr.split_text(body)
    for number, part in enumerate(parts, start=1):
        header = f"📤 Экспорт, часть {number}/{len(parts)}\n" if len(parts) > 1 else ""
        await message.answer(f"{header}<code>{escape(part)}</code>")


IMPORT_HELP = (
    "Пришлите расписание одним сообщением, с заголовком перед каждым днём:\n\n"
    "<code>== Понедельник ==\n"
    "1. Алгебра, 214\n"
    "2. Физика, 305, Иванова И.И.\n"
    "3. История [чис]\n"
    "3. Обществознание [знам]\n\n"
    "== Вторник ==\n"
    "1. Химия, 118</code>\n\n"
    "Подойдёт и текст из /export — можно раз в четверть сохранять его себе "
    "и возвращать обратно.\n"
    "Блок <code>== Звонки ==</code> обновит основное расписание звонков."
)


@router.message(Command("import"))
async def cmd_import(
    message: Message,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await message.answer(_refusal(role, Role.ADMIN))
        return
    await state.set_state(ImportTimetable.paste)
    await message.answer(f"📥 <b>Импорт расписания</b>\n\n{IMPORT_HELP}")


@router.message(ImportTimetable.paste)
async def import_preview(
    message: Message,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Parse and show what would happen; nothing is written yet.

    The paste is kept in FSM storage rather than parsed twice into a process
    variable: «Применить» may well arrive at a different instance.
    """
    if not _allowed(school_class, role, Role.ADMIN):
        await state.clear()
        return

    raw = message.text or ""
    days, rejected = timetable_io.parse_timetable_block(raw)
    bells, _ = timetable_io.parse_bells_block(raw)
    if not days and not bells:
        await message.answer(
            "Не нашёл ни одного дня.\n\n" + IMPORT_HELP, reply_markup=cancel_keyboard()
        )
        return

    await state.update_data(raw=raw)
    preview = mr.render_import_preview(days, rejected, len(bells))
    await message.answer(preview, reply_markup=import_keyboard())


@router.callback_query(ImportAction.filter(F.action == "cancel"))
async def import_cancel(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return
    await state.clear()
    await callback.message.edit_text("Импорт отменён — ничего не изменилось.")
    await callback.answer()


@router.callback_query(ImportAction.filter(F.action == "apply"))
async def import_apply(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Replace exactly the weekdays the paste named, in one transaction.

    Days the paste did not mention are left alone, so importing a single day's
    block is a legitimate thing to do. A day that appears with no lessons under
    it is emptied — that is how a paste says «в четверг уроков нет».
    """
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    data = await state.get_data()
    raw = str(data.get("raw", ""))
    days, _ = timetable_io.parse_timetable_block(raw)
    bells, _ = timetable_io.parse_bells_block(raw)
    if not days and not bells:
        await state.clear()
        await callback.answer("Нечего применять — начните заново: /import", show_alert=True)
        return

    result = await structure.apply_timetable(session, school_class, days, bells)
    total = result.written
    schedule = result.schedule
    unrung = result.unrung

    summary = f"импорт расписания: дней {len(days)}, уроков {total}"
    if bells:
        summary += f", звонков {len(bells)}"
    if result.dropped:
        # Rows, not numbers. «8. Алгебра» under Monday and under Tuesday is two
        # lessons nobody will see, and this line is the only record of them.
        summary += f", без звонка пропущено {len(result.dropped)}"
    await audit.record(
        session, school_class.id, callback.from_user.id, "timetable.import", summary
    )
    await session.commit()
    if schedule is not None:
        await session.refresh(schedule, ["periods"])
    await state.clear()

    lines = [f"✅ Импорт применён: {len(days)} дн., уроков — {total}."]
    # What was written, not what was parsed. A lesson past the last bell is
    # dropped by ``apply_timetable``, so printing the parsed count put «уроков
    # — 7» directly above «Вторник: 9» — the card contradicting itself on two
    # consecutive lines. The service says which rows it dropped, by weekday, so
    # this counts them rather than re-deciding the rule a second time here.
    dropped_per_day: dict[int, int] = {}
    for weekday, _index in result.dropped:
        dropped_per_day[weekday] = dropped_per_day.get(weekday, 0) + 1
    for weekday in sorted(days):
        written = len(days[weekday]) - dropped_per_day.get(weekday, 0)
        lines.append(f"• {WEEKDAY_FULL[weekday - 1]}: {written}")
    if bells:
        lines.append(f"• Звонки: {len(bells)}")
    if unrung:
        # Said out loud rather than left in the difference between two numbers:
        # such a lesson is stored nowhere and drawn nowhere, and an admin who
        # is not told simply believes the paste worked.
        numbers = ", ".join(str(index) for index in unrung)
        lines.append(
            f"\n⚠️ Не добавлены уроки № {numbers}: в расписании звонков нет "
            "таких номеров. Добавьте звонки в «🔔 Звонки» и вставьте день заново."
        )
    await callback.message.edit_text("\n".join(lines), reply_markup=back_to_menu())
    await callback.answer("Готово")
