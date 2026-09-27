"""«🔔 Звонки»: the class's bell schedules and which one is the default.

Part of :mod:`app.bot.handlers.manage`; the rules every screen of it
follows are in that package's docstring.
"""

from __future__ import annotations

from html import escape

from aiogram import F, Router
from aiogram.filters import Command
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot import manage_render as mr
from app.bot import render
from app.bot.handlers.manage._common import _allowed, _int_or_none, _refusal
from app.bot.handlers.timetable import REJECTED_MAX
from app.bot.keyboards import back_to_menu, cancel_keyboard
from app.bot.manage_keyboards import BellsAction, bells_list_keyboard
from app.bot.manage_states import EditBellRows, NewBellSchedule
from app.bot.render import clamp, more_line, plural
from app.models import BellSchedule, DayOverride, Role, SchoolClass
from app.services import audit, structure, timetable_io

router = Router(name="manage.bells")


# --------------------------------------------------------------------------
# 🔔 Bells
# --------------------------------------------------------------------------
#
# A class may keep several bell schedules — «Обычное», «Сокращённое», «Суббота»
# — and a shortened day points at one of them. One of them is the class
# default, and that one is what the day view uses when nothing else says
# otherwise.

BELLS_ROWS_HELP = (
    "Пришлите строки расписания:\n\n"
    "<code>1. 08:30-09:15\n"
    "2. 09:25-10:10\n"
    "3. 10:25-11:10</code>\n\n"
    "Строка, которую я не разберу, не изменит сохранённое — "
    "пустой присылкой звонки не стереть."
)


def _parse_bells(raw: str):
    """Rows out of a paste, via the same grammar the week import uses.

    ``parse_bells_block`` only reads what is under a «== Звонки ==» header,
    because a week paste must never touch the bells by accident. Here the
    header is implied by the question that was asked, so it is prepended.
    """
    return timetable_io.parse_bells_block("== Звонки ==\n" + (raw or ""))


async def _schedules_of(session: AsyncSession, class_id: int) -> list[BellSchedule]:
    return list(
        await session.scalars(
            select(BellSchedule)
            .where(BellSchedule.class_id == class_id)
            .order_by(BellSchedule.id)
        )
    )


async def _rung_by_default(session: AsyncSession, school_class: SchoolClass) -> set[int]:
    """The lesson numbers the class rings today. Empty when it has no default."""
    if school_class.bell_schedule_id is None:
        return set()
    current = await session.scalar(
        select(BellSchedule).where(BellSchedule.id == school_class.bell_schedule_id)
    )
    return {period.index for period in current.periods} if current is not None else set()


async def _schedule_by_id(
    session: AsyncSession, school_class: SchoolClass, raw: str
) -> BellSchedule | None:
    schedule_id = _int_or_none(raw)
    if schedule_id is None:
        return None
    return await session.scalar(
        select(BellSchedule).where(
            BellSchedule.id == schedule_id, BellSchedule.class_id == school_class.id
        )
    )


async def _bells_view(session: AsyncSession, school_class: SchoolClass):
    schedules = await _schedules_of(session, school_class.id)
    return mr.render_bells(schedules, school_class.bell_schedule_id), bells_list_keyboard(
        schedules, school_class.bell_schedule_id
    )


@router.message(Command("bells"))
async def cmd_bells(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await message.answer(_refusal(role, Role.ADMIN))
        return
    await state.clear()
    text, keyboard = await _bells_view(session, school_class)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(BellsAction.filter(F.action == "list"))
async def bells_list(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return
    await state.clear()
    text, keyboard = await _bells_view(session, school_class)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


@router.callback_query(BellsAction.filter(F.action == "edit"))
async def bells_edit(
    callback: CallbackQuery,
    callback_data: BellsAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    schedule = await _schedule_by_id(session, school_class, callback_data.value)
    if schedule is None:
        await callback.answer("Расписание не найдено", show_alert=True)
        return

    await state.set_state(EditBellRows.rows)
    await state.update_data(schedule_id=schedule.id)
    current = mr.render_bell_rows(schedule)
    body = f"🔔 <b>{escape(schedule.name)}</b>\n\n"
    if current:
        body += f"<code>{escape(current)}</code>\n\n"
    await callback.message.edit_text(body + BELLS_ROWS_HELP, reply_markup=cancel_keyboard())
    await callback.answer()


@router.message(EditBellRows.rows)
async def bells_rows_apply(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await state.clear()
        return

    data = await state.get_data()
    schedule = await _schedule_by_id(session, school_class, str(data.get("schedule_id", "")))
    if schedule is None:
        await state.clear()
        await message.answer("Расписание уже удалено.", reply_markup=back_to_menu())
        return

    rows, rejected = _parse_bells(message.text or "")
    if not rows:
        # The stored schedule is the thing a class runs on; a paste that turned
        # out to be prose must never be the reason it disappears.
        await message.answer(
            "Не удалось разобрать ни одной строки — звонки не изменены.\n\n" + BELLS_ROWS_HELP,
            reply_markup=cancel_keyboard(),
        )
        return

    orphaned = await structure.write_bell_periods(session, schedule, rows)
    summary = f"звонки «{schedule.name}»: {len(rows)} уроков"
    if orphaned:
        summary += f", перестали звонить уроков: {len(orphaned)}"
    await audit.record(
        session,
        school_class.id,
        message.from_user.id,
        "bells.edit",
        summary,
    )
    await session.commit()
    await session.refresh(schedule, ["periods"])
    await state.clear()

    lines = [f"✅ Звонки «{escape(schedule.name)}» сохранены: {len(rows)}."]
    if orphaned:
        # The lessons that were already there and no longer ring. They are not
        # deleted — they are stored and drawn nowhere — so this sentence is the
        # only place anybody is told, and it lives in `render` because the
        # other bells editor has to say the very same thing.
        lines.append("")
        lines.append(render.silenced_lessons(orphaned))
    if rejected:
        lines.append("")
        lines.append("⚠️ Не разобрал строки:")
        lines.extend(f"<code>{escape(line)}</code>" for line in rejected[:REJECTED_MAX])
        lines.extend(more_line(len(rejected), REJECTED_MAX))
    await message.answer(clamp(lines))

    text, keyboard = await _bells_view(session, school_class)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(BellsAction.filter(F.action == "create"))
async def bells_create(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return
    await state.set_state(NewBellSchedule.name)
    await callback.message.edit_text(
        "Название нового расписания звонков, например <code>Сокращённое</code>:",
        reply_markup=cancel_keyboard(),
    )
    await callback.answer()


@router.message(NewBellSchedule.name)
async def bells_new_name(
    message: Message,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await state.clear()
        return

    name = " ".join((message.text or "").split())
    if not 1 <= len(name) <= 64:
        await message.answer("Название от 1 до 64 символов. Ещё раз:")
        return

    await state.update_data(name=name)
    await state.set_state(NewBellSchedule.rows)
    await message.answer(
        f"<b>{escape(name)}</b>\n\n{BELLS_ROWS_HELP}", reply_markup=cancel_keyboard()
    )


@router.message(NewBellSchedule.rows)
async def bells_new_rows(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await state.clear()
        return

    data = await state.get_data()
    name = str(data.get("name", "")).strip()
    if not name:
        await state.clear()
        await message.answer("Начните заново: /bells", reply_markup=back_to_menu())
        return

    rows, rejected = _parse_bells(message.text or "")
    if not rows:
        await message.answer(
            "Не удалось разобрать ни одной строки.\n\n" + BELLS_ROWS_HELP,
            reply_markup=cancel_keyboard(),
        )
        return

    schedule = BellSchedule(class_id=school_class.id, name=name[:64])
    session.add(schedule)
    await session.flush()
    await structure.write_bell_periods(session, schedule, rows)
    await audit.record(
        session,
        school_class.id,
        message.from_user.id,
        "bells.create",
        f"создано расписание звонков «{name}»: {len(rows)} уроков",
    )
    await session.commit()
    await session.refresh(schedule, ["periods"])
    await state.clear()

    lines = [f"✅ Расписание «{escape(name)}» создано: "
        f"{plural(len(rows), 'урок', 'урока', 'уроков')}."]
    if rejected:
        # The same shape as `bells_rows_apply` above and `timetable_apply`:
        # a row cap *and* a character budget. Five was the row cap alone, and
        # a rejected line is echoed whole — one 4094-character paste whose
        # first line is a real bell row and whose other five are prose came to
        # 4152 characters, which Telegram refuses. The schedule had been
        # created and committed by then, and this is a `Message` handler with
        # no callback to apologise on, so the admin saw neither the
        # confirmation nor the «🔔 Расписания звонков» list that follows it.
        lines.append("")
        lines.append("⚠️ Не разобрал строки:")
        lines.extend(f"<code>{escape(line)}</code>" for line in rejected[:REJECTED_MAX])
        lines.extend(more_line(len(rejected), REJECTED_MAX))
    await message.answer(clamp(lines))

    text, keyboard = await _bells_view(session, school_class)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(BellsAction.filter(F.action == "default"))
async def bells_make_default(
    callback: CallbackQuery,
    callback_data: BellsAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    schedule = await _schedule_by_id(session, school_class, callback_data.value)
    if schedule is None:
        await callback.answer("Расписание не найдено", show_alert=True)
        return

    # A schedule may be created empty — that is the two-step flow, and the rows
    # are the next screen. Making an empty one the *class default* is another
    # thing: every ordinary day rings it, and a day that rings nothing draws
    # nothing. `/bundle` answers zero lessons, `/now` answers «выходной» on a
    # Monday, and the phone, the widget, the calendar feed and the morning
    # digest go blank together with nothing anywhere reporting a problem —
    # while the class's own timetable sits untouched underneath, which is what
    # makes it so hard to see. Worse, `rung_indexes` then returns an empty set,
    # which `can_ring` reads as «this class has not set its bells up yet» and
    # waves every lesson number through. `api/manage.bells_update` has refused
    # this since it was written and the «⭐» beside it did not; one rule, two
    # shells, is the whole reason `services/` exists.
    if not schedule.periods:
        await callback.answer(
            "В этом расписании звонков нет ни одного урока — сделать его "
            "основным нельзя. Сначала добавьте времена.",
            show_alert=True,
        )
        return

    # Moving the default to a *shorter* schedule takes lessons off every
    # weekday exactly as shrinking the current one does, and nothing rewrites a
    # row, so `write_bell_periods` never runs and never counts them. Asked here
    # through the same function the API asks, so the two answer alike.
    was = await _rung_by_default(session, school_class)
    orphaned = await structure.lessons_silenced_by(
        session, school_class.id, was, {period.index for period in schedule.periods}
    )
    school_class.bell_schedule_id = schedule.id
    summary = f"основное расписание звонков: «{schedule.name}»"
    if orphaned:
        summary += f", перестали звонить уроков: {len(orphaned)}"
    await audit.record(
        session, school_class.id, callback.from_user.id, "bells.default", summary,
    )
    await session.commit()

    text, keyboard = await _bells_view(session, school_class)
    await callback.message.edit_text(text, reply_markup=keyboard)
    if orphaned:
        # An alert rather than the log alone: the admin pressed a star and four
        # lessons left every phone in the class, and the audit page is not
        # where anybody looks next.
        await callback.answer(
            f"Основное расписание обновлено. Перестали звонить уроков: "
            f"{len(orphaned)} — они остались в базе, но их никто не увидит.",
            show_alert=True,
        )
        return
    await callback.answer("Основное расписание обновлено")


@router.callback_query(BellsAction.filter(F.action == "delete"))
async def bells_delete(
    callback: CallbackQuery,
    callback_data: BellsAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Refused for the class default and for anything a day still points at.

    Both are a ``SET NULL`` at the database level, which would silently move
    those days onto the default schedule — a change nobody asked for, on dates
    an admin is not looking at.
    """
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    schedule = await _schedule_by_id(session, school_class, callback_data.value)
    if schedule is None:
        await callback.answer("Расписание не найдено", show_alert=True)
        return

    if schedule.id == school_class.bell_schedule_id:
        await callback.answer(
            "Это основное расписание класса. Сначала сделайте основным другое.",
            show_alert=True,
        )
        return

    used = await session.scalar(
        select(func.count())
        .select_from(DayOverride)
        .where(
            DayOverride.class_id == school_class.id,
            DayOverride.bell_schedule_id == schedule.id,
        )
    )
    if used:
        await callback.answer(
            f"По нему идут особые дни ({used}). Сначала измените их.", show_alert=True
        )
        return

    name = schedule.name
    await session.delete(schedule)
    await audit.record(
        session, school_class.id, callback.from_user.id, "bells.delete",
        f"удалено расписание звонков «{name}»",
    )
    await session.commit()

    text, keyboard = await _bells_view(session, school_class)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer(f"Удалено: {name}"[:200])
