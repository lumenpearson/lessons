"""«🔄 Замены»: a lesson replaced, cancelled or put back for one day.

Part of :mod:`app.bot.handlers.content`; the order every write here follows —
save, audit line, then announce — is in that package's docstring.
"""

from __future__ import annotations

from datetime import date as Date
from html import escape

from aiogram import F, Router
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, InlineKeyboardButton, Message
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.button_style import DANGER
from app.bot.content_keyboard import OverrideAction as OverrideCB
from app.bot.handlers.calendar import open_month
from app.bot.handlers.content._common import (
    BAD_DATE,
    BAD_PICK,
    _date_or_none,
    _index_or_none,
    _today,
)
from app.bot.keyboards import Menu, back_to_menu
from app.bot.render import human_date, relative_day_name
from app.bot.states import AddOverride
from app.models import LessonOverride, OverrideAction, Role, SchoolClass
from app.schedule import ScheduleResolver
from app.services import audit, notify, subjects, timetable_edit

router = Router(name="content.overrides")

#: Said in full rather than «не получилось»: the number is the thing that
#: is wrong, and «🔔 Звонки» is where it is put right.
NO_BELL = (
    "В этот день нет звонка для урока №{index}, поэтому замену никто бы не "
    "увидел. Добавьте звонок в «🔔 Звонки» или выберите другой урок."
)


# --------------------------------------------------------------------------
# Substitutions
# --------------------------------------------------------------------------


@router.callback_query(Menu.filter(F.action == "overrides"))
async def overrides_root(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await callback.answer("Нужна роль редактора", show_alert=True)
        return

    await state.clear()
    await callback.message.edit_text(
        "🔄 <b>Замены</b>\n\nВыберите день:",
        reply_markup=open_month("ovr", _today(school_class)),
    )
    await callback.answer()


@router.callback_query(OverrideCB.filter(F.action == "pick_day"))
async def override_pick_day(
    callback: CallbackQuery,
    callback_data: OverrideCB,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await callback.answer("Нужна роль редактора", show_alert=True)
        return

    target = _date_or_none(callback_data.value)
    if target is None:
        await callback.answer(BAD_DATE, show_alert=True)
        return
    await state.update_data(date=callback_data.value)

    days = await ScheduleResolver(session, school_class).resolve_range(target, 1)
    rows = [
        [
            InlineKeyboardButton(
                text=f"{lesson.index}. {lesson.subject}"
                + (" (отменён)" if lesson.is_cancelled else ""),
                callback_data=OverrideCB(action="pick_index", value=str(lesson.index)).pack(),
            )
        ]
        for lesson in days[0].lessons
    ]
    if not rows:
        await callback.message.edit_text(
            "В этот день уроков нет — заменять нечего.", reply_markup=back_to_menu()
        )
        await state.clear()
        await callback.answer()
        return

    await callback.message.edit_text(
        f"{human_date(target, _today(school_class)).capitalize()}\n\nКакой урок меняем?",
        reply_markup=back_to_menu(rows),
    )
    await state.set_state(AddOverride.index)
    await callback.answer()


@router.callback_query(AddOverride.index, OverrideCB.filter(F.action == "pick_index"))
async def override_pick_index(
    callback: CallbackQuery,
    callback_data: OverrideCB,
    state: FSMContext,
) -> None:
    if _index_or_none(callback_data.value) is None:
        await callback.answer(BAD_PICK, show_alert=True)
        return

    await state.update_data(index=callback_data.value)
    rows = [
        [
            InlineKeyboardButton(
                text="🚫 Отменить урок",
                callback_data=OverrideCB(action="cancel_lesson").pack(),
                style=DANGER,
            )
        ],
        [
            InlineKeyboardButton(
                text="♻️ Вернуть по расписанию",
                callback_data=OverrideCB(action="clear").pack(),
            )
        ],
    ]
    await callback.message.edit_text(
        f"Урок №{callback_data.value}.\n\n"
        "Пришлите новый предмет — можно с кабинетом через запятую, "
        "например <code>Физика, 214</code>.\n"
        "Или выберите действие ниже:",
        reply_markup=back_to_menu(rows),
    )
    await state.set_state(AddOverride.subject)
    await callback.answer()


async def _save_override(
    session: AsyncSession,
    class_id: int,
    day: Date,
    index: int,
    action: OverrideAction,
    subject: str | None = None,
    room: str | None = None,
) -> str | None:
    """Write the substitution, or answer with why this day cannot carry it.

    ``None`` means written. A sentence means refused, and it is the sentence to
    show — the API says the same ones, because both come from
    `services/timetable_edit`.

    Two questions, and they are not the same. **Does the day draw lessons at
    all** — it does not out of the school year or when somebody marked it
    «выходной», and a row written then is stored, logged and announced to every
    subscriber while being drawn on no phone. This check had no twin here at
    all: the bot was thought safe because its lesson picker offers nothing on
    such a day, which is true of the picker and not of the flow, since the
    bot's calendar bounds the year differently (1 September to 1 August) and so
    offers June, July and August. **And does the day ring this number** — the
    resolver takes a lesson's times from the bell row of the same number, so a
    row at a number that does not ring is drawn by nothing either. The second
    is checked on create only, because an existing row at a bad number has to
    stay clearable, and against *this day's* bells, because a shortened day
    rings a shorter schedule than the class's usual.
    """
    out_of_season = await timetable_edit.why_no_lesson_can_be_drawn(session, class_id, day)
    if out_of_season is not None:
        return out_of_season

    existing = await session.scalar(
        select(LessonOverride).where(
            LessonOverride.class_id == class_id,
            LessonOverride.date == day,
            LessonOverride.index == index,
        )
    )
    if existing is None:
        rung = await timetable_edit.rung_indexes_on(session, class_id, day)
        if not timetable_edit.can_ring(rung, index):
            return NO_BELL.format(index=index)
        existing = LessonOverride(class_id=class_id, date=day, index=index, action=action)
        session.add(existing)
    existing.action = action
    # The class's spelling: `app/schedule.py` looks a substitution's colour up by
    # exact name, so one typed in the wrong case draws grey among coloured
    # lessons on every phone.
    existing.subject_name = (
        await subjects.spelling(session, class_id, subject) if subject else subject
    )
    existing.room = room
    # Staged, not committed: the caller commits it together with its audit
    # line, so a substitution and the record of who made it land as one fact.
    return None


@router.message(AddOverride.subject)
async def override_subject(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await state.clear()
        return

    raw = (message.text or "").strip()
    if not raw:
        await message.answer("Пришлите название предмета:")
        return

    subject, _, room = raw.partition(",")
    # Cut once, here, and the same value is then stored, logged, shown back and
    # announced. It used to be cut only on the way into the database and echoed
    # whole on the way out, which was wrong twice over: the confirmation
    # claimed text the row had not kept, and a 4096-character paste at this
    # prompt made the reply 4161 characters — refused by Telegram, after the
    # substitution had already been committed, with no callback to apologise on.
    subject = subject.strip()[:120]
    room = room.strip()[:32] or None

    data = await state.get_data()
    day = Date.fromisoformat(data["date"])
    index = int(data["index"])

    written = await _save_override(
        session,
        school_class.id,
        day,
        index,
        OverrideAction.REPLACE,
        subject=subject,
        room=room,
    )
    if written is not None:
        await state.clear()
        await message.answer(written)
        return
    await audit.record(
        session,
        school_class.id,
        message.from_user.id,
        "override.replace",
        f"замена {day:%d.%m}, урок №{index}: {subject}",
    )
    await session.commit()
    await state.clear()

    today = _today(school_class)
    await message.answer(
        f"✅ Замена сохранена: {human_date(day, today)}, урок №{index} — "
        f"<b>{escape(subject)}</b>.",
        reply_markup=back_to_menu(),
    )
    await notify.notify_subscribers(
        session,
        getattr(message, "bot", None),
        school_class,
        # Through ``shorten`` like the homework announcement above, so that one
        # rule bounds what this bot pushes to a lock screen whatever wrote it.
        f"🔄 Замена {relative_day_name(day, today)}, урок №{index} — "
        f"<b>{escape(notify.shorten(subject))}</b>.",
        kind="changes",
        exclude=message.from_user.id,
    )


@router.callback_query(AddOverride.subject, OverrideCB.filter(F.action == "cancel_lesson"))
async def override_cancel(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await callback.answer("Нужна роль редактора", show_alert=True)
        return

    data = await state.get_data()
    day = Date.fromisoformat(data["date"])
    index = int(data["index"])
    refusal = await _save_override(session, school_class.id, day, index, OverrideAction.CANCEL)
    if refusal is not None:
        await state.clear()
        await callback.answer(refusal, show_alert=True)
        return
    await audit.record(
        session,
        school_class.id,
        callback.from_user.id,
        "override.cancel",
        f"отменён урок №{index} {day:%d.%m}",
    )
    await session.commit()
    await state.clear()

    today = _today(school_class)
    await callback.message.edit_text(
        f"🚫 Урок №{index} {human_date(day, today)} отменён.",
        reply_markup=back_to_menu(),
    )
    await callback.answer()
    await notify.notify_subscribers(
        session,
        getattr(callback, "bot", None),
        school_class,
        f"🚫 Урок №{index} {relative_day_name(day, today)} отменён.",
        kind="changes",
        exclude=callback.from_user.id,
    )


@router.callback_query(AddOverride.subject, OverrideCB.filter(F.action == "clear"))
async def override_clear(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await callback.answer("Нужна роль редактора", show_alert=True)
        return

    data = await state.get_data()
    day = Date.fromisoformat(data["date"])
    index = int(data["index"])

    existing = await session.scalar(
        select(LessonOverride).where(
            LessonOverride.class_id == school_class.id,
            LessonOverride.date == day,
            LessonOverride.index == index,
        )
    )
    if existing is not None:
        await session.delete(existing)
        await audit.record(
            session,
            school_class.id,
            callback.from_user.id,
            "override.clear",
            f"урок №{index} {day:%d.%m} снова по расписанию",
        )
        await session.commit()

    await state.clear()
    await callback.message.edit_text(
        f"♻️ Урок №{index} снова идёт по расписанию.", reply_markup=back_to_menu()
    )
    await callback.answer()
    if existing is not None:
        await notify.notify_subscribers(
            session,
            getattr(callback, "bot", None),
            school_class,
            f"♻️ Урок №{index} {relative_day_name(day, _today(school_class))} "
            "снова идёт по расписанию.",
            kind="changes",
            exclude=callback.from_user.id,
        )
