"""Weekly template and bell schedule — the ADMIN-level structural editing.

Entering a whole week through inline buttons is miserable, so the timetable is
edited by pasting one message per weekday. That is the fastest input method
Telegram offers and it survives copy-paste from a class chat.
"""

from __future__ import annotations

import re
from datetime import time

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

    weekday = int(callback_data.value)
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
            f"{entry.index}. {entry.subject_name}"
            + (f", {entry.room}" if entry.room else "")
            + (f", {entry.teacher}" if entry.teacher else "")
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
    weekday = int(data["weekday"])
    raw = (message.text or "").strip()

    # Replacing the whole weekday in one transaction keeps the template and the
    # message the admin just sent identical — no partial merges to reason about.
    await session.execute(
        delete(TimetableEntry).where(
            TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == weekday
        )
    )

    if raw in {"-", "—"}:
        await session.commit()
        await state.clear()
        await message.answer(
            f"🧹 {WEEKDAY_FULL[weekday - 1]}: расписание очищено.", reply_markup=back_to_menu()
        )
        return

    added: list[str] = []
    rejected: list[str] = []
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
        if not subject:
            rejected.append(line.strip())
            continue

        session.add(
            TimetableEntry(
                class_id=school_class.id,
                weekday=weekday,
                index=index,
                subject_name=subject,
                room=(parts[1][:32] if len(parts) > 1 and parts[1] else None),
                teacher=(parts[2][:120] if len(parts) > 2 and parts[2] else None),
                parity=WeekParity.ANY,
            )
        )
        added.append(f"{index}. {subject}")

    await session.commit()
    await state.clear()

    lines = [f"✅ {WEEKDAY_FULL[weekday - 1]}: сохранено уроков — {len(added)}."]
    if added:
        lines.append("")
        lines.extend(added)
    if rejected:
        lines.append("")
        lines.append("⚠️ Не разобрал строки:")
        lines.extend(f"<code>{line}</code>" for line in rejected)

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
        parsed.append((int(match.group(1)), start, end))

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
        lines.extend(f"<code>{line}</code>" for line in rejected)
    await message.answer("\n".join(lines), reply_markup=back_to_menu())
