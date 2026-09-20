"""Day-to-day editing: homework, substitutions and events. This is what an EDITOR does.

Every write here does three things, in this order: it saves, it writes one
line to the audit log in the same transaction, and only then it tells the
subscribers. The order matters. A notification about a substitution that failed to
save would send thirty people to the wrong room, so nothing is announced
before it is committed — and ``notify_subscribers`` swallows a single
recipient's outage rather than failing the edit that caused it.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import datetime, time
from html import escape

from aiogram import F, Router
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, InlineKeyboardButton, Message
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.button_style import DANGER
from app.bot.handlers.calendar import open_month
from app.bot.handlers.tasks import homework_view
from app.bot.keyboards import (
    EventAction,
    HomeworkAction,
    Menu,
    back_to_menu,
    cancel_keyboard,
)
from app.bot.keyboards import (
    OverrideAction as OverrideCB,
)
from app.bot.render import clamp, human_date, relative_day_name
from app.bot.states import AddEvent, AddHomework, AddOverride
from app.config import get_settings
from app.models import (
    DayEvent,
    EventKind,
    LessonOverride,
    OverrideAction,
    Role,
    SchoolClass,
)
from app.schedule import ScheduleResolver
from app.services import audit, notify, subjects, timetable_edit
from app.services import homework as homework_service

router = Router(name="content")

EVENT_KIND_LABELS = {
    EventKind.CANTEEN: "🍽 Столовая",
    EventKind.EVENT: "🎉 Мероприятие",
    EventKind.EXAM: "📋 Контрольная",
    EventKind.TRIP: "🚌 Экскурсия",
    EventKind.MEETING: "👥 Собрание",
}


def _today(school_class: SchoolClass | None = None) -> Date:
    """Today in the class's own zone.

    Falls back to the server default only when no class is in scope, which in
    practice means a handler that has already refused the request.
    """
    tz = school_class.tz if school_class is not None else get_settings().tz
    return datetime.now(tz).date()


#: The answer a day picker gives to a date it cannot read.
BAD_DATE = "Непонятная дата. Откройте календарь заново."
BAD_PICK = "Не понял, что выбрано. Откройте экран заново."
#: Said in full rather than «не получилось»: the number is the thing that
#: is wrong, and «🔔 Звонки» is where it is put right.
NO_BELL = (
    "В этот день нет звонка для урока №{index}, поэтому замену никто бы не "
    "увидел. Добавьте звонок в «🔔 Звонки» или выберите другой урок."
)


def _date_or_none(raw: str) -> Date | None:
    """A date out of a callback payload, or ``None``.

    Every «на какой день?» in this module used to call
    ``Date.fromisoformat(callback_data.value)`` straight, which is correct for
    every payload this bot builds and an unhandled ``ValueError`` for every
    other one — and a callback payload is whatever the client sends, not only
    what was put on a button. The three handlers below are the boundary: past
    them the date travels in the FSM state and the five places that read it
    back can go on trusting it. `manage.py` and `timetable.py` already did this;
    this is the same guard under the same name.
    """
    try:
        return Date.fromisoformat(raw)
    except (TypeError, ValueError):
        return None


def _kind_or_none(raw: str) -> EventKind | None:
    """The boundary ``_date_or_none`` draws, for the other two things this
    module carries through the FSM state.

    The date obeys it and the kind, on the very next screen, did not: it was
    stored raw and turned into an ``EventKind`` three questions later, in the
    handler that saves the event. That is exactly the failure the comment above
    ``event_pick_day`` warns about — the person had by then typed a time and a
    title, and what broke was the press before all of it.
    """
    try:
        return EventKind(raw)
    except ValueError:
        return None


def _index_or_none(raw: str) -> int | None:
    """Ditto for the lesson number the override flow carries. Read back by
    three handlers as ``int(data["index"])``, none of which could refuse it."""
    try:
        index = int(raw)
    except (TypeError, ValueError):
        return None
    return index if index >= 0 else None


def _parse_time_range(raw: str) -> tuple[time, time] | None:
    """Accepts "12:30-13:15" or "12:30 13:15"."""
    parts = raw.replace("—", "-").replace("–", "-").replace("-", " ").split()
    if len(parts) != 2:
        return None
    try:
        start = time.fromisoformat(parts[0] if len(parts[0]) > 2 else f"{parts[0]}:00")
        end = time.fromisoformat(parts[1] if len(parts[1]) > 2 else f"{parts[1]}:00")
    except ValueError:
        return None
    return (start, end) if start < end else None


# --------------------------------------------------------------------------
# Homework
# --------------------------------------------------------------------------


@router.callback_query(Menu.filter(F.action == "homework"))
async def homework_root(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """The digest with this reader's own «сделал» ticks.

    The same view the /homework command and the tick buttons render, from
    ``handlers/tasks.py`` — one function, so a tick made from the menu and a
    tick made from the command cannot disagree about what is done.
    """
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return

    text, keyboard = await homework_view(session, school_class, callback.from_user.id, role)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


@router.callback_query(HomeworkAction.filter(F.action == "add"))
async def homework_add(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await callback.answer("Нужна роль редактора", show_alert=True)
        return

    # No state: from here the date travels in the callback payload, which is
    # what lets the same button sit on the day card as well as under this
    # question. A step that depended on the state set here would refuse the
    # card's button for no reason the person pressing it could see.
    await state.clear()
    await callback.message.edit_text(
        "На какой день задано?",
        reply_markup=open_month("hw", _today(school_class)),
    )
    await callback.answer()


@router.callback_query(HomeworkAction.filter(F.action == "pick_day"))
async def homework_pick_day(
    callback: CallbackQuery,
    callback_data: HomeworkAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await callback.answer("Нужна роль редактора", show_alert=True)
        return

    due = _date_or_none(callback_data.value)
    if due is None:
        await callback.answer(BAD_DATE, show_alert=True)
        return
    await state.update_data(due=callback_data.value)

    days = await ScheduleResolver(session, school_class).resolve_range(due, 1)
    subjects = list(
        dict.fromkeys(lesson.subject for lesson in days[0].lessons if not lesson.is_cancelled)
    )

    # The button carries the subject's position in this list, not its name.
    #
    # Telegram allows a callback payload 64 *bytes* long. The name used to be
    # cut to 48 *characters*, which is the same thing only in Latin: every
    # Cyrillic letter is two bytes, so «Основы безопасности жизнедеятельности»
    # packed to 88 bytes and aiogram refused to build the keyboard at all. The
    # exception landed on the step before — the day was chosen, the error said
    # «начните заново», and no subject with a long name could ever be picked.
    #
    # The list is put in the FSM data, which is a database row and has no such
    # limit, so the payload is now one or two digits whatever the subject is
    # called.
    await state.update_data(subjects=subjects)

    rows = [
        [
            InlineKeyboardButton(
                text=subject,
                callback_data=HomeworkAction(action="pick_subject", value=str(index)).pack(),
            )
        ]
        for index, subject in enumerate(subjects)
    ]
    prompt = (
        "По какому предмету?"
        if rows
        else "В этот день уроков нет — введите название предмета вручную:"
    )
    await callback.message.edit_text(prompt, reply_markup=back_to_menu(rows))
    await state.set_state(AddHomework.subject)
    await callback.answer()


@router.callback_query(AddHomework.subject, HomeworkAction.filter(F.action == "pick_subject"))
async def homework_pick_subject(
    callback: CallbackQuery,
    callback_data: HomeworkAction,
    state: FSMContext,
) -> None:
    data = await state.get_data()
    subjects: list[str] = data.get("subjects") or []

    # An index that no longer names anything is a button from a keyboard older
    # than the flow it belongs to — a message left open while the day was
    # chosen again. Saying so beats writing homework for whatever subject
    # happens to sit at that position now.
    #
    # Through the module's own guard rather than ``isdigit`` + ``int``: the two
    # do not ask the same question. «²» is a digit to ``str.isdigit`` and a
    # ``ValueError`` to ``int``, so that spelling let a payload past the check
    # and into the conversion, which raises out of the handler — and a press
    # that reaches a traceback never reaches ``callback.answer``.
    index = _index_or_none(callback_data.value)
    if index is None or index >= len(subjects):
        await callback.answer("Список устарел. Выберите день заново.", show_alert=True)
        return

    subject = subjects[index]
    await state.update_data(subject=subject)
    await callback.message.edit_text(
        f"Предмет: <b>{escape(subject)}</b>\n\nТеперь пришлите текст задания:",
        reply_markup=cancel_keyboard(),
    )
    await state.set_state(AddHomework.text)
    await callback.answer()


@router.message(AddHomework.subject)
async def homework_typed_subject(message: Message, state: FSMContext) -> None:
    subject = (message.text or "").strip()
    if not subject:
        await message.answer("Введите название предмета:")
        return
    await state.update_data(subject=subject[:120])
    await message.answer("Теперь пришлите текст задания:", reply_markup=cancel_keyboard())
    await state.set_state(AddHomework.text)


@router.message(AddHomework.text)
async def homework_text(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await state.clear()
        return

    text = (message.text or "").strip()
    if not text:
        await message.answer("Пустое задание. Пришлите текст:")
        return

    data = await state.get_data()
    due = Date.fromisoformat(data["due"])
    # Through the service, which owns «one задание per subject per day» for
    # both shells — including the class's spelling, so a typed «алгебра»
    # updates the «Алгебра» already set for that day rather than founding a
    # second assignment beside it, and both going out in the evening digest.
    written, created = await homework_service.upsert(
        session, school_class.id, due, data["subject"], text, message.from_user.id
    )
    subject = written.subject_name
    verb = "добавлено" if created else "обновлено"

    await audit.record(
        session,
        school_class.id,
        message.from_user.id,
        "homework.save",
        f"{verb} ДЗ: {subject} на {due:%d.%m}",
    )
    await session.commit()
    await state.clear()

    today = _today(school_class)
    await message.answer(
        clamp(
            [
                f"✅ Задание {verb}.",
                "",
                f"<b>{escape(subject)}</b> {human_date(due, today)}",
                escape(text),
            ]
        ),
        reply_markup=back_to_menu(),
    )
    await notify.notify_subscribers(
        session,
        getattr(message, "bot", None),
        school_class,
        f"📝 {'Обновлено' if verb == 'обновлено' else 'Новое'} задание: "
        f"<b>{escape(subject)}</b> {relative_day_name(due, today)} — "
        f"{escape(notify.shorten(text))}",
        kind="homework",
        # The author already knows; they just typed it.
        exclude=message.from_user.id,
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
) -> bool:
    """Write the substitution, or report that this day has no such lesson to change.

    The resolver takes a lesson's times from the bell row of the same number,
    so a substitution at a number the day does not ring is stored, logged, announced
    to everybody with «🔁 Замена … урок №8» and then drawn by nothing. Checked
    on create only — an existing row at a bad number has to stay clearable,
    which is how a class gets out of one — and against *this day's* bells,
    because a shortened day rings a shorter schedule than the class's usual.
    """
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
            return False
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
    return True


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
    if not written:
        await state.clear()
        await message.answer(NO_BELL.format(index=index))
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
    if not await _save_override(session, school_class.id, day, index, OverrideAction.CANCEL):
        await state.clear()
        await callback.answer(NO_BELL.format(index=index), show_alert=True)
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


# --------------------------------------------------------------------------
# Events
# --------------------------------------------------------------------------


@router.callback_query(Menu.filter(F.action == "events"))
async def events_root(
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
        "🎉 <b>События</b>\n\nНа какой день добавляем?",
        reply_markup=open_month("ev", _today(school_class)),
    )
    await callback.answer()


@router.callback_query(EventAction.filter(F.action == "pick_day"))
async def event_pick_day(
    callback: CallbackQuery,
    callback_data: EventAction,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await callback.answer("Нужна роль редактора", show_alert=True)
        return

    # Parsed here even though nothing needs the value until the last step:
    # otherwise an unreadable date is carried through three questions and then
    # raises out of the handler that finally reads it, which looks to the user
    # like the answer they just typed was the problem.
    if _date_or_none(callback_data.value) is None:
        await callback.answer(BAD_DATE, show_alert=True)
        return

    await state.update_data(date=callback_data.value)
    rows = [
        [
            InlineKeyboardButton(
                text=label,
                callback_data=EventAction(action="pick_kind", value=kind.value).pack(),
            )
        ]
        for kind, label in EVENT_KIND_LABELS.items()
    ]
    await callback.message.edit_text("Что это за событие?", reply_markup=back_to_menu(rows))
    await state.set_state(AddEvent.kind)
    await callback.answer()


@router.callback_query(AddEvent.kind, EventAction.filter(F.action == "pick_kind"))
async def event_pick_kind(
    callback: CallbackQuery,
    callback_data: EventAction,
    state: FSMContext,
) -> None:
    if _kind_or_none(callback_data.value) is None:
        await callback.answer(BAD_PICK, show_alert=True)
        return

    await state.update_data(kind=callback_data.value)
    await callback.message.edit_text(
        "Во сколько? Пришлите интервал, например <code>12:30-13:15</code>.",
        reply_markup=cancel_keyboard(),
    )
    await state.set_state(AddEvent.time)
    await callback.answer()


@router.message(AddEvent.time)
async def event_time(message: Message, state: FSMContext) -> None:
    parsed = _parse_time_range(message.text or "")
    if parsed is None:
        await message.answer(
            "Не понял время. Формат: <code>12:30-13:15</code>, начало раньше конца."
        )
        return
    start, end = parsed
    await state.update_data(start=start.isoformat(), end=end.isoformat())
    await message.answer("Как назовём событие?", reply_markup=cancel_keyboard())
    await state.set_state(AddEvent.title)


@router.message(AddEvent.title)
async def event_title(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.EDITOR):
        await state.clear()
        return

    title = (message.text or "").strip()
    if not title:
        await message.answer("Пришлите название события:")
        return

    # Cut once, like the substitution above, and then stored, logged, shown
    # back and announced as the same string. Echoed whole it both promised a
    # name the row had not kept and, at 4096 characters typed here, made the
    # confirmation 4164 — a message Telegram refuses, sent after the event was
    # committed, from a handler with nothing to apologise on.
    title = title[:200]

    data = await state.get_data()
    kind = EventKind(data["kind"])
    day = Date.fromisoformat(data["date"])
    session.add(
        DayEvent(
            class_id=school_class.id,
            date=day,
            starts_at=time.fromisoformat(data["start"]),
            ends_at=time.fromisoformat(data["end"]),
            title=title,
            kind=kind,
            # Only «мероприятие» and «экскурсия» usually replace a lesson.
            covers_lesson=kind in {EventKind.EVENT, EventKind.TRIP},
        )
    )
    await audit.record(
        session,
        school_class.id,
        message.from_user.id,
        "event.add",
        f"событие {day:%d.%m} {data['start'][:5]}: {title}",
    )
    await session.commit()
    await state.clear()

    today = _today(school_class)
    when = f"{data['start'][:5]}–{data['end'][:5]}"
    await message.answer(
        f"✅ Событие добавлено: <b>{escape(title)}</b> "
        f"{human_date(day, today)}, {when}.",
        reply_markup=back_to_menu(),
    )
    await notify.notify_subscribers(
        session,
        getattr(message, "bot", None),
        school_class,
        # ``shorten`` for the same reason the two announcements above use it.
        f"{EVENT_KIND_LABELS.get(kind, '🎉')} <b>{escape(notify.shorten(title))}</b> "
        f"{relative_day_name(day, today)}, {when}.",
        kind="changes",
        exclude=message.from_user.id,
    )
