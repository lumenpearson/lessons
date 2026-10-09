"""«🏖 Особые дни»: holidays, shortened, remote and other marked days.

Part of :mod:`app.bot.handlers.manage`; the rules every screen of it
follows are in that package's docstring.
"""

from __future__ import annotations

from datetime import date as Date
from html import escape

from aiogram import F, Router
from aiogram.filters import Command
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.handlers.calendar import open_month
from app.bot.handlers.manage._common import (
    _date_or_none,
    _day_kind_or_none,
    _int_or_none,
    _parse_day,
    _today,
    needs,
)
from app.bot.keyboards import back_to_menu, cancel_keyboard
from app.bot.manage_keyboards.holidays import (
    PERIOD_KINDS,
    DayKindAction,
    bells_pick_keyboard,
    day_kind_keyboard,
    holiday_list_keyboard,
    period_kind_keyboard,
)
from app.bot.manage_render import holidays as mr
from app.bot.manage_states import AddHoliday
from app.models import DayKind, Role, SchoolClass
from app.services import audit
from app.services.manage import bells as bells_service
from app.services.manage import special_days

router = Router(name="manage.holidays")

#: Days a «📆 Период» may cover in one go. A school year is longer than this,
#: but a period typed by hand is a holiday, and a typo in the year would
#: otherwise write thousands of rows before anybody noticed.
PERIOD_MAX_DAYS = 120

NOTE_MAX = 300

#: Said when a shortened day has nothing to ring: the picker's starting value
#: — the day's own schedule, or the class default — rings no lesson, which only
#: a class whose bells were never filled in can reach.
SHORTENED_WITHOUT_BELLS = (
    "Сокращённому дню нужно расписание звонков, а в основном нет ни одного урока — "
    "заполните «🔔 Звонки» и отметьте день снова."
)


# --------------------------------------------------------------------------
# 🏖 Special days
# --------------------------------------------------------------------------
#
# A DayOverride is how the class says "this date is not a normal school day".
# «Обычный день» is the absence of a row, not a kind of row, so choosing it
# deletes the override instead of storing DayKind.NORMAL — otherwise the
# resolver would have two ways to spell the same thing.

_KIND_BY_TAG = {
    "holiday": DayKind.HOLIDAY,
    "shortened": DayKind.SHORTENED,
    "remote": DayKind.REMOTE,
    "self_study": DayKind.SELF_STUDY,
    "day_off": DayKind.DAY_OFF,
}

#: The kind said in the middle of a sentence, for the audit line and the
#: confirmation. Read by **subscript**, not `.get`, and deliberately: a kind
#: with no entry here is a `KeyError` out of a callback handler, which never
#: reaches `callback.answer()` and leaves the button spinning until Telegram
#: gives up. `test_every_day_kind_can_be_said` keeps the table complete so the
#: subscript stays safe, rather than papering over a gap with a default that
#: would print «День» at somebody who chose «самоподготовка».
_KIND_SUMMARY = {
    DayKind.HOLIDAY: "каникулы / выходной",
    DayKind.SHORTENED: "сокращённые уроки",
    DayKind.REMOTE: "дистанционно",
    DayKind.SELF_STUDY: "самоподготовка",
    DayKind.DAY_OFF: "отгул",
}

HOLIDAY_DATE_HELP = (
    "Выберите день или пришлите дату: <code>12.09</code> или <code>12.09.2026</code>."
)


async def _holiday_view(
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
    only: DayKind | None = None,
):
    """The list of marked days, optionally narrowed to one kind.

    The filter lives in the callback payload rather than in FSM state, which is
    the rule the editor's pager already follows: a card on a screen is a card
    somebody may come back to in an hour, and a filter held in state would
    either have expired by then or be silently applied to a different screen.
    Carried in the payload, the card *is* its own filter.
    """
    today = _today(school_class)
    overrides = await special_days.upcoming(session, school_class.id, today, only)
    schedules = await special_days.schedule_names(session, school_class.id)
    return mr.render_holidays(overrides, schedules, today, only), holiday_list_keyboard(
        overrides,
        only=only,
        can_edit=role.at_least(Role.EDITOR),
        can_period=role.at_least(Role.ADMIN),
    )


@router.message(Command("holidays"))
@needs(Role.EDITOR)
async def cmd_holidays(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await state.clear()
    text, keyboard = await _holiday_view(session, school_class, role)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(DayKindAction.filter(F.action == "list"))
@needs(Role.EDITOR)
async def holidays_list(
    callback: CallbackQuery,
    callback_data: DayKindAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await state.clear()
    # An unreadable filter narrows to nothing rather than raising: the value is
    # whatever the client sent, and `DayKind(raw)` out of a callback handler
    # never reaches `callback.answer()`.
    only = _day_kind_or_none(callback_data.value) if callback_data.value else None
    text, keyboard = await _holiday_view(session, school_class, role, only)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


@router.callback_query(DayKindAction.filter(F.action == "add"))
@needs(Role.EDITOR)
async def holiday_add(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await state.set_state(AddHoliday.date)
    await callback.message.edit_text(
        f"🏖 <b>Особый день</b>\n\n{HOLIDAY_DATE_HELP}",
        reply_markup=open_month("dk", _today(school_class)),
    )
    await callback.answer()


async def _ask_kind(editable, day: Date) -> None:
    await editable.edit_text(
        f"<b>{day:%d.%m.%Y}</b>\n\nЧто это за день?",
        reply_markup=day_kind_keyboard(day.isoformat()),
    )


@router.callback_query(DayKindAction.filter(F.action == "pick_date"))
@needs(Role.EDITOR)
async def holiday_pick_date(
    callback: CallbackQuery,
    callback_data: DayKindAction,
    state: FSMContext,
    school_class: SchoolClass,
    role: Role,
) -> None:
    day = _date_or_none(callback_data.value)
    if day is None:
        await callback.answer("Непонятная дата", show_alert=True)
        return

    # From here the date travels in the callback payload, so the flow no longer
    # depends on a state the user could have set for something else.
    await state.clear()
    await _ask_kind(callback.message, day)
    await callback.answer()


@router.message(AddHoliday.date)
@needs(Role.EDITOR, step=True)
async def holiday_typed_date(
    message: Message,
    state: FSMContext,
    school_class: SchoolClass,
    role: Role,
) -> None:
    day = _parse_day(message.text or "", _today(school_class))
    if day is None:
        await message.answer(f"Не понял дату. {HOLIDAY_DATE_HELP}")
        return

    await state.clear()
    await message.answer(
        f"<b>{day:%d.%m.%Y}</b>\n\nЧто это за день?",
        reply_markup=day_kind_keyboard(day.isoformat()),
    )


@router.callback_query(DayKindAction.filter(F.action == "kind"))
@needs(Role.EDITOR)
async def holiday_kind(
    callback: CallbackQuery,
    callback_data: DayKindAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    raw_date, _, tag = callback_data.value.partition(":")
    day = _date_or_none(raw_date)
    if day is None:
        await callback.answer("Непонятная дата", show_alert=True)
        return

    if tag == "normal":
        put = await special_days.put_day(session, school_class, day, {"kind": DayKind.NORMAL})
        if put.had_mark:
            await audit.record(
                session, school_class.id, callback.from_user.id, "dayoverride.delete",
                f"{day:%d.%m}: снова обычный день",
            )
            await session.commit()
        await state.clear()
        text, keyboard = await _holiday_view(session, school_class, role)
        await callback.message.edit_text(text, reply_markup=keyboard)
        await callback.answer("Обычный день")
        return

    kind = _KIND_BY_TAG.get(tag)
    if kind is None:
        await callback.answer("Неизвестный тип дня", show_alert=True)
        return

    # A bell schedule means something on a shortened day only, so any other
    # kind takes the day's away; a shortened day starts on the one it had, or
    # the class default. That is the picker's starting value, written before
    # the picker is asked: the row is committed first, and walking away from
    # the picker must still leave a day that names what it rings.
    changes: dict[str, object] = {"kind": kind, "bell_schedule_id": None}
    if kind is DayKind.SHORTENED:
        current = await special_days.mark_on(session, school_class.id, day)
        changes["bell_schedule_id"] = (
            current.bell_schedule_id
            if current is not None and current.bell_schedule_id
            else school_class.bell_schedule_id
        )
    try:
        await special_days.put_day(session, school_class, day, changes)
    except (special_days.ShortenedNeedsSchedule, bells_service.ScheduleEmpty):
        await callback.answer(SHORTENED_WITHOUT_BELLS, show_alert=True)
        return
    await audit.record(
        session, school_class.id, callback.from_user.id, "dayoverride.set",
        f"{day:%d.%m}: {_KIND_SUMMARY[kind]}",
    )
    await session.commit()

    if kind is DayKind.SHORTENED:
        schedules = await bells_service.schedules_of(session, school_class.id)
        await state.clear()
        await callback.message.edit_text(
            f"<b>{day:%d.%m}</b> — сокращённые уроки.\n\nПо какому расписанию звонков?",
            reply_markup=bells_pick_keyboard(schedules, day.isoformat()),
        )
        await callback.answer()
        return

    await _ask_note(callback.message, state, day, kind)
    await callback.answer()


async def _ask_note(editable, state: FSMContext, day: Date, kind: DayKind) -> None:
    await state.set_state(AddHoliday.note)
    await state.update_data(date=day.isoformat())
    await editable.edit_text(
        f"✅ <b>{day:%d.%m}</b> — {_KIND_SUMMARY[kind]}.\n\n"
        "Пришлите заметку (например, «осенние каникулы») или <code>-</code>, "
        "чтобы оставить без неё.",
        reply_markup=cancel_keyboard(),
    )


@router.callback_query(DayKindAction.filter(F.action == "bells"))
@needs(Role.EDITOR)
async def holiday_bells(
    callback: CallbackQuery,
    callback_data: DayKindAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    raw_date, _, raw_id = callback_data.value.partition(":")
    day = _date_or_none(raw_date)
    if day is None:
        await callback.answer("Непонятная дата", show_alert=True)
        return

    override = await special_days.mark_on(session, school_class.id, day)
    if override is None:
        await callback.answer("День уже не отмечен", show_alert=True)
        return

    schedule_id = _int_or_none(raw_id)
    if schedule_id:
        schedule = await bells_service.schedule_of(session, school_class.id, schedule_id)
        if schedule is None:
            await callback.answer("Расписание звонков не найдено", show_alert=True)
            return
        # A schedule with no rows is legitimate — «🔔 Звонки» creates it empty
        # and the times are typed in afterwards — but a day pointed at one
        # draws no lessons at all, because the resolver takes a lesson's times
        # from the bell row of its own number. The card would say «⏱ Сокращённые
        # уроки» over an empty day on every phone, in the widget and in the
        # calendar feed, and nothing would be logged. `api/edit.day_put` refuses
        # the same thing for the same reason.
        if not await bells_service.rings_anything(session, schedule):
            await callback.answer(
                f"В «{schedule.name}» ещё нет ни одного урока — "
                "заполните звонки, иначе день будет пустым.",
                show_alert=True,
            )
            return
        await special_days.put_day(session, school_class, day, {"bell_schedule_id": schedule.id})
        summary = f"{day:%d.%m}: звонки «{schedule.name}»"
    else:
        # The keyboard no longer draws this button; a card left open from
        # before it was removed still can.
        await callback.answer(
            "У сокращённого дня должно быть своё расписание звонков — "
            "выберите его в списке.",
            show_alert=True,
        )
        return

    await audit.record(
        session, school_class.id, callback.from_user.id, "dayoverride.bells", summary
    )
    await session.commit()
    await _ask_note(callback.message, state, day, DayKind.SHORTENED)
    await callback.answer()


@router.message(AddHoliday.note)
@needs(Role.EDITOR, step=True)
async def holiday_note(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    data = await state.get_data()
    day = _date_or_none(str(data.get("date", "")))
    if day is None:
        await state.clear()
        await message.answer("Начните заново: /holidays", reply_markup=back_to_menu())
        return

    override = await special_days.mark_on(session, school_class.id, day)
    if override is None:
        await state.clear()
        await message.answer("День уже не отмечен.", reply_markup=back_to_menu())
        return

    raw = " ".join((message.text or "").split())
    if raw not in {"-", "—", ""}:
        await special_days.put_day(session, school_class, day, {"note": raw[:NOTE_MAX]})
        await audit.record(
            session, school_class.id, message.from_user.id, "dayoverride.note",
            f"{day:%d.%m}: заметка «{override.note}»",
        )
        await session.commit()

    await state.clear()
    text, keyboard = await _holiday_view(session, school_class, role)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(DayKindAction.filter(F.action == "delete"))
@needs(Role.EDITOR)
async def holiday_delete(
    callback: CallbackQuery,
    callback_data: DayKindAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    day = _date_or_none(callback_data.value)
    if day is None:
        await callback.answer("Непонятная дата", show_alert=True)
        return

    put = await special_days.put_day(session, school_class, day, {"kind": DayKind.NORMAL})
    if put.had_mark:
        await audit.record(
            session, school_class.id, callback.from_user.id, "dayoverride.delete",
            f"{day:%d.%m}: отметка снята",
        )
        await session.commit()

    await state.clear()
    text, keyboard = await _holiday_view(session, school_class, role)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer("Снято" if put.had_mark else "Уже снято")


@router.callback_query(DayKindAction.filter(F.action == "period"))
@needs(Role.ADMIN)
async def holiday_period_start(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass,
    role: Role,
) -> None:
    """A whole range of holiday days is admin-only: it rewrites weeks of the
    class's calendar from one message."""
    # Which kind first, dates second. The flow used to mark holidays and
    # nothing else, so «самоподготовка с 12 по 16» meant marking five days one
    # at a time — and a week of remote teaching, which is the shape this is
    # actually needed in, meant five presses and five typos to make.
    await state.clear()
    await callback.message.edit_text(
        "📆 <b>Отметить период</b>\n\n"
        "Чем отметить каждый день внутри периода?",
        reply_markup=period_kind_keyboard(),
    )
    await callback.answer()


@router.callback_query(DayKindAction.filter(F.action == "period_kind"))
@needs(Role.ADMIN)
async def holiday_period_kind(
    callback: CallbackQuery,
    callback_data: DayKindAction,
    state: FSMContext,
    school_class: SchoolClass,
    role: Role,
) -> None:
    """The kind is picked, now the dates. Re-checked here, not trusted from the
    press that got this far: a card can sit on a screen after a role changes."""
    kind = _day_kind_or_none(callback_data.value)
    if kind is None or kind not in {chosen for chosen, _ in PERIOD_KINDS}:
        # Not an exception. Callback data is whatever the client sent, and a
        # bare conversion on it leaves the button spinning until Telegram
        # gives up — see the guards in this module's siblings.
        await callback.answer("Не знаю такого вида дня.", show_alert=True)
        return

    label = next(text for chosen, text in PERIOD_KINDS if chosen is kind)
    await state.set_state(AddHoliday.period)
    await state.update_data(period_kind=kind.value)
    await callback.message.edit_text(
        f"📆 <b>{escape(label)}</b>\n\n"
        "Пришлите период: <code>26.10-05.11</code>.\n"
        f"Каждый день внутри будет отмечен так, не больше "
        f"{PERIOD_MAX_DAYS} дней за раз.",
        reply_markup=cancel_keyboard(),
    )
    await callback.answer()


@router.message(AddHoliday.period)
@needs(Role.ADMIN, step=True)
async def holiday_period_apply(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    today = _today(school_class)
    raw = (message.text or "").replace("—", "-").replace("–", "-")
    parts = [part for part in raw.split("-") if part.strip()]
    first = _parse_day(parts[0], today) if len(parts) >= 2 else None
    last = _parse_day(parts[1], today) if len(parts) >= 2 else None
    if first is None or last is None or last < first:
        await message.answer(
            "Не понял период. Формат: <code>26.10-05.11</code>, начало раньше конца."
        )
        return

    span = (last - first).days + 1
    if span > PERIOD_MAX_DAYS:
        await message.answer(
            f"Это {span} дней — слишком много за один раз. "
            f"Максимум {PERIOD_MAX_DAYS}; разбейте период на части."
        )
        return

    # Read from the state the picker wrote, and defaulted rather than refused:
    # a form that outlives a restart still has a date to apply, and holidays
    # are what this flow did before there was anything to pick.
    data = await state.get_data()
    kind = _day_kind_or_none(str(data.get("period_kind", ""))) or DayKind.HOLIDAY

    await special_days.mark_period(session, school_class, first, span, kind)
    await audit.record(
        session,
        school_class.id,
        message.from_user.id,
        "dayoverride.period",
        f"{kind.value}: {first:%d.%m}–{last:%d.%m}, дней: {span}",
    )
    await session.commit()
    await state.clear()

    await message.answer(mr.render_period_result(first, last, span, kind))
    text, keyboard = await _holiday_view(session, school_class, role)
    await message.answer(text, reply_markup=keyboard)
