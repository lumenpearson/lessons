"""Weekly template and bell schedule — the ADMIN-level structural editing.

Entering a whole week through inline buttons is miserable, so the timetable is
edited by pasting one message per weekday. That is the fastest input method
Telegram offers and it survives copy-paste from a class chat.
"""

from __future__ import annotations

import re
from datetime import time
from html import escape

from aiogram import F, Router
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, InlineKeyboardButton, Message
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.keyboards import (
    WEEKDAY_FULL,
    Menu,
    TimetableAction,
    back_to_menu,
    cancel_keyboard,
    weekday_picker,
)
from app.bot.states import EditBells, EditTimetable
from app.models import BellPeriod, BellSchedule, Role, SchoolClass, TimetableEntry, WeekParity

router = Router(name="timetable")

TIMETABLE_HELP = (
    "Пришлите расписание одним сообщением, по строке на урок:\n\n"
    "<code>1. Алгебра, 214\n"
    "2. Физика, 305, Иванова И.И.\n"
    "3. История</code>\n\n"
    "Формат строки: <b>номер. предмет[, кабинет[, учитель]]</b>.\n"
    "Пустое сообщение (<code>-</code>) очистит этот день."
)

BELLS_HELP = (
    "Пришлите расписание звонков, по строке на урок:\n\n"
    "<code>1. 08:30-09:15\n"
    "2. 09:25-10:10\n"
    "3. 10:25-11:10</code>"
)

LESSON_LINE = re.compile(r"^\s*(\d{1,2})\s*[.)]?\s*(.+)$")
BELL_LINE = re.compile(
    r"^\s*(\d{1,2})\s*[.)]?\s*(\d{1,2}[:.]\d{2})\s*[-–—]\s*(\d{1,2}[:.]\d{2})\s*$"
)


def _parse_time(raw: str) -> time:
    return time.fromisoformat(raw.replace(".", ":"))


def _weekday_or_none(raw: str) -> int | None:
    """1..7, or ``None`` for anything a crafted callback might carry."""
    try:
        weekday = int(raw)
    except (TypeError, ValueError):
        return None
    return weekday if 1 <= weekday <= 7 else None


@router.callback_query(Menu.filter(F.action == "timetable"))
async def timetable_root(
    callback: CallbackQuery,
    role: Role | None,
) -> None:
    if role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return

    extra = [
        [
            InlineKeyboardButton(
                text="🔔 Расписание звонков",
                callback_data=TimetableAction(action="bells").pack(),
            )
        ]
    ]
    await callback.message.edit_text(
        "🧩 <b>Расписание уроков</b>\n\nВыберите день недели:",
        reply_markup=_weekday_keyboard(extra),
    )
    await callback.answer()


def _weekday_keyboard(extra: list[list[InlineKeyboardButton]]):
    keyboard = weekday_picker(TimetableAction, "pick_day")
    keyboard.inline_keyboard = keyboard.inline_keyboard[:-1] + extra + [
        keyboard.inline_keyboard[-1]
    ]
    return keyboard


@router.callback_query(TimetableAction.filter(F.action == "pick_day"))
async def timetable_pick_day(
    callback: CallbackQuery,
    callback_data: TimetableAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return

    weekday = _weekday_or_none(callback_data.value)
    if weekday is None:
        await callback.answer("Неизвестный день недели", show_alert=True)
        return
    await state.update_data(weekday=weekday)

    entries = list(
        await session.scalars(
            select(TimetableEntry)
            .where(TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == weekday)
            .order_by(TimetableEntry.index)
        )
    )
    if entries:
        current = "\n".join(
            f"{entry.index}. {escape(entry.subject_name)}"
            + (f", {escape(entry.room)}" if entry.room else "")
            + (f", {escape(entry.teacher)}" if entry.teacher else "")
            for entry in entries
        )
        body = f"<b>{WEEKDAY_FULL[weekday - 1]}</b>\n\n<code>{current}</code>\n\n{TIMETABLE_HELP}"
    else:
        body = f"<b>{WEEKDAY_FULL[weekday - 1]}</b> — пока пусто.\n\n{TIMETABLE_HELP}"

    await callback.message.edit_text(body, reply_markup=cancel_keyboard())
    await state.set_state(EditTimetable.cell)
    await callback.answer()


@router.message(EditTimetable.cell)
async def timetable_apply(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await state.clear()
        return

    data = await state.get_data()
    weekday = _weekday_or_none(str(data.get("weekday", "")))
    if weekday is None:
        await state.clear()
        await message.answer(
            "Не понял, какой это день недели. Откройте «Расписание» заново.",
            reply_markup=back_to_menu(),
        )
        return
    raw = (message.text or "").strip()

    if raw in {"-", "—"}:
        await session.execute(
            delete(TimetableEntry).where(
                TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == weekday
            )
        )
        await session.commit()
        await state.clear()
        await message.answer(
            f"🧹 {WEEKDAY_FULL[weekday - 1]}: расписание очищено.", reply_markup=back_to_menu()
        )
        return

    # Parse the whole message before touching the database: a paste that turns
    # out to be unusable must not have wiped the weekday on its way through.
    parsed: list[tuple[int, str, str | None, str | None]] = []
    rejected: list[str] = []
    seen: set[int] = set()
    for line in raw.splitlines():
        if not line.strip():
            continue
        match = LESSON_LINE.match(line)
        if match is None:
            rejected.append(line.strip())
            continue

        index = int(match.group(1))
        parts = [part.strip() for part in match.group(2).split(",")]
        subject = parts[0][:120]
        # Lesson numbers start at 1, and the unique key is (day, number): a zero
        # or a repeated number used to abort the whole save with an IntegrityError.
        if not subject or index < 1 or index in seen:
            rejected.append(line.strip())
            continue
        seen.add(index)

        parsed.append(
            (
                index,
                subject,
                (parts[1][:32] if len(parts) > 1 and parts[1] else None),
                (parts[2][:120] if len(parts) > 2 and parts[2] else None),
            )
        )

    if not parsed:
        # Same rule as the bells: a bad paste never erases what is stored.
        # Clearing a day is spelled "-", and only that.
        await message.answer(
            "Не удалось разобрать ни одной строки — расписание не изменено.\n\n"
            + TIMETABLE_HELP,
            reply_markup=cancel_keyboard(),
        )
        return

    # Replacing the whole weekday in one transaction keeps the template and the
    # message the admin just sent identical — no partial merges to reason about.
    await session.execute(
        delete(TimetableEntry).where(
            TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == weekday
        )
    )
    for index, subject, room, teacher in parsed:
        session.add(
            TimetableEntry(
                class_id=school_class.id,
                weekday=weekday,
                index=index,
                subject_name=subject,
                room=room,
                teacher=teacher,
                parity=WeekParity.ANY,
            )
        )

    await session.commit()
    await state.clear()

    lines = [f"✅ {WEEKDAY_FULL[weekday - 1]}: сохранено уроков — {len(parsed)}."]
    lines.append("")
    lines.extend(f"{index}. {escape(subject)}" for index, subject, _, _ in parsed)
    if rejected:
        lines.append("")
        lines.append("⚠️ Не разобрал строки:")
        lines.extend(f"<code>{escape(line)}</code>" for line in rejected)

    await message.answer("\n".join(lines), reply_markup=back_to_menu())


@router.callback_query(TimetableAction.filter(F.action == "bells"))
async def bells_prompt(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return

    periods: list[BellPeriod] = []
    if school_class.bell_schedule_id:
        schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
        if schedule is not None:
            periods = schedule.periods

    current = "\n".join(
        f"{period.index}. {period.starts_at:%H:%M}-{period.ends_at:%H:%M}" for period in periods
    )
    body = "🔔 <b>Звонки</b>\n\n"
    if current:
        body += f"<code>{current}</code>\n\n"
    body += BELLS_HELP

    await callback.message.edit_text(body, reply_markup=cancel_keyboard())
    await state.set_state(EditBells.rows)
    await callback.answer()


@router.message(EditBells.rows)
async def bells_apply(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await state.clear()
        return

    parsed: list[tuple[int, time, time]] = []
    rejected: list[str] = []
    seen: set[int] = set()
    for line in (message.text or "").splitlines():
        if not line.strip():
            continue
        match = BELL_LINE.match(line)
        if match is None:
            rejected.append(line.strip())
            continue
        try:
            start = _parse_time(match.group(2))
            end = _parse_time(match.group(3))
        except ValueError:
            rejected.append(line.strip())
            continue
        if start >= end:
            rejected.append(line.strip())
            continue
        index = int(match.group(1))
        # (schedule, index) is unique: a zero or a repeat used to blow up the
        # commit *after* the old rows had been deleted.
        if index < 1 or index in seen:
            rejected.append(line.strip())
            continue
        seen.add(index)
        parsed.append((index, start, end))

    if not parsed:
        await message.answer(
            "Не удалось разобрать ни одной строки. " + BELLS_HELP, reply_markup=cancel_keyboard()
        )
        return

    schedule = None
    if school_class.bell_schedule_id:
        schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
    if schedule is None:
        schedule = BellSchedule(class_id=school_class.id, name="Обычное")
        session.add(schedule)
        await session.flush()
        school_class.bell_schedule_id = schedule.id

    await session.execute(delete(BellPeriod).where(BellPeriod.schedule_id == schedule.id))
    for index, start, end in parsed:
        session.add(
            BellPeriod(schedule_id=schedule.id, index=index, starts_at=start, ends_at=end)
        )
    await session.commit()
    await state.clear()

    lines = [f"✅ Звонки сохранены: {len(parsed)} уроков."]
    if rejected:
        lines.append("")
        lines.append("⚠️ Не разобрал строки:")
        lines.extend(f"<code>{escape(line)}</code>" for line in rejected)
    await message.answer("\n".join(lines), reply_markup=back_to_menu())
