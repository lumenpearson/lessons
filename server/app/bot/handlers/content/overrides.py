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
from sqlalchemy.ext.asyncio import AsyncSession

from app import wording
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
from app.models import OverrideAction, Role, SchoolClass
from app.schedule import ScheduleResolver
from app.services import audit, notify, substitutions

router = Router(name="content.overrides")

#: Said in full rather than «не получилось»: the number is the thing that
#: is wrong, and «🔔 Звонки» is where it is put right.
NO_BELL = (
    "В этот день нет звонка для урока №{index}, поэтому замену никто бы не "
    "увидел. Добавьте звонок в «🔔 Звонки» или выберите другой урок."
)

#: Said in full, like NO_BELL: the lesson is on the screen because a
#: substitution put it there, not because the weekly template did, so
#: «🚫 Отменить урок» finds nothing underneath to cancel — «♻️ Вернуть по
#: расписанию» on the same card is how it comes off.
ADDED_BY_SUBSTITUTION = (
    "Урок №{index} добавлен заменой — в расписании его нет, отменять нечего. "
    "Чтобы убрать его, нажмите «♻️ Вернуть по расписанию»."
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
    show. The three questions — does the day draw lessons at all, does it ring
    this number, is there a lesson underneath — are
    ``services/substitutions``', which v1 and v2 ask too: the first and the
    third are said in the API's words, and the bell in this screen's own,
    which names «🔔 Звонки». The bell is asked of a new row only, because an
    existing row at a bad number has to stay clearable, and against *this
    day's* bells, because a shortened day rings a shorter schedule than the
    class's usual.

    The bot asked two of the three until #383. Its picker lists the lessons the
    day draws, so a lesson added by a substitution at a number the template
    leaves empty could be «отменён» here: stored as a cancellation of nothing,
    announced to every subscriber and drawn nowhere — which v1 refused. The bot
    now refuses it too, pointing at «♻️ Вернуть по расписанию» when a
    substitution added the lesson (:data:`ADDED_BY_SUBSTITUTION`); that sentence
    alone, of everything this function returns, means `override_cancel` must
    keep the conversation state rather than clear it, since no write happened
    before the refusal.
    """
    changes: dict[str, object] = {"action": action}
    if action is OverrideAction.REPLACE:
        # The screen asks for a subject and a room; a teacher or a note set
        # from a phone stays as it was.
        changes.update(subject=subject, room=room)
    try:
        await substitutions.upsert(session, class_id, day, index, changes)
    except substitutions.NoLessonOnDay as refused:
        return refused.sentence
    except substitutions.NoBellForLesson:
        return NO_BELL.format(index=index)
    except substitutions.LessonNotOnTimetable as refused:
        if refused.cancelling:
            existing = await substitutions.substitution_on(session, class_id, day, index)
            if existing is not None:
                return ADDED_BY_SUBSTITUTION.format(index=index)
        return wording.lesson_not_on_timetable_detail(index, cancelling=refused.cancelling)
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
        # Through ``shorten`` like the homework announcement in ``homework.py``,
        # so that one rule bounds what this bot pushes to a lock screen whatever wrote it.
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
        # Every refusal but one clears the state, as it always has. The one
        # exception is this screen's own sentence (:data:`ADDED_BY_SUBSTITUTION`):
        # no write happened before it, so #373's rule (commit before touching
        # state) does not apply, and the state is what «♻️ Вернуть по
        # расписанию» on the same card still needs.
        if refusal != ADDED_BY_SUBSTITUTION.format(index=index):
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

    existing = await substitutions.substitution_on(session, school_class.id, day, index)
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
