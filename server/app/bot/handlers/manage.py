"""Управление классом: предметы, особые дни, звонки, устройства, журнал, импорт.

This is the structural half of the bot - everything that shapes what the
day-to-day flows in ``content.py`` and ``timetable.py`` are allowed to say.

Three rules hold everywhere in this module, and each of them is the answer to
a way the previous shape of this code could be abused:

* **A role check at the top of every handler, including every FSM step.**
  FSM state is per-user and therefore attacker-controlled: a client can set
  itself into ``EditSubject.name`` and send a message, so a step that trusted
  the step before it would be a rename with no permission check at all.
* **Every id arrives as text and is re-scoped by the query that reads it.**
  Callback data is user-supplied. A subject is never fetched by id alone but
  by ``(id, class_id)``, so a crafted payload naming another class's row finds
  nothing rather than editing it.
* **No state in the process.** Every Vercel invocation is a fresh Python
  process, so anything that has to survive a step lives in FSM storage (the
  database) or in the callback payload - never in a module-level dict.

User-supplied text is escaped once, at render time, by ``manage_render``; the
few strings built here are escaped in place for the same reason.
"""

from __future__ import annotations

import re
from datetime import date as Date
from datetime import datetime, timedelta
from html import escape

from aiogram import F, Router
from aiogram.filters import Command, CommandObject
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, InlineKeyboardButton, Message
from sqlalchemy import delete as sa_delete
from sqlalchemy import func, select
from sqlalchemy import update as sa_update
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot import manage_render as mr
from app.bot.keyboards import (
    WEEKDAY_FULL,
    Menu,
    back_to_menu,
    cancel_keyboard,
    date_picker,
)
from app.bot.manage_keyboards import (
    COLOUR_PRESETS,
    AuditAction,
    BellsAction,
    DayKindAction,
    DeviceAction,
    ImportAction,
    ManageAction,
    RequestAction,
    SubjectAction,
    audit_keyboard,
    back_to,
    bells_list_keyboard,
    bells_pick_keyboard,
    class_menu,
    colour_keyboard,
    day_kind_keyboard,
    device_keyboard,
    holiday_list_keyboard,
    import_keyboard,
    request_keyboard,
    subject_card_keyboard,
    subject_list_keyboard,
    switch_keyboard,
)
from app.bot.manage_states import (
    AddHoliday,
    DeleteClass,
    EditBellRows,
    EditClassField,
    EditSubject,
    ImportTimetable,
    NewBellSchedule,
    RequestAccess,
)
from app.bot.middlewares import prefs_key
from app.bot.render import upcoming_dates
from app.bot.roles import can_grant, list_memberships
from app.config import get_settings
from app.db import SessionLocal
from app.fsm_storage import DatabaseStorage
from app.models import (
    AccessRequest,
    BellPeriod,
    BellSchedule,
    BotUser,
    DayKind,
    DayOverride,
    DeviceToken,
    Homework,
    LessonOverride,
    Role,
    SchoolClass,
    Subject,
    TimetableEntry,
)
from app.services import audit, linking, timetable_io
from app.services import calendar as calendar_service
from app.services import stats as stats_service
from app.timezones import label_for

router = Router(name="manage")

NO_ACCESS = "Нет доступа. Откройте /start, чтобы получить его."
NEED_ADMIN = "Только для администраторов"
NEED_EDITOR = "Нужна роль редактора"
NEED_OWNER = "Только для владельца класса"

#: Days a «📆 Период» may cover in one go. A school year is longer than this,
#: but a period typed by hand is каникулы, and a typo in the year would
#: otherwise write thousands of rows before anybody noticed.
PERIOD_MAX_DAYS = 120

#: Homework rows one search may answer with.
SEARCH_MAX = 15

#: How far back a search looks. Older homework is history nobody is looking
#: for, and including it makes every search slower and noisier.
SEARCH_BACK_DAYS = 30

#: Audit lines per page of «📜 Журнал».
AUDIT_PAGE = 30

COLOUR_RE = re.compile(r"^#?([0-9a-fA-F]{6})$")

SUBJECT_NAME_MAX = 120
SHORT_NAME_MAX = 16
TEACHER_MAX = 120
NOTE_MAX = 300


# --------------------------------------------------------------------------
# Guards and small parsers
# --------------------------------------------------------------------------


def _allowed(school_class: SchoolClass | None, role: Role | None, minimum: Role) -> bool:
    """The whole of the permission check, in one place so no handler invents
    its own version of it."""
    return school_class is not None and role is not None and role.at_least(minimum)


def _refusal(role: Role | None, minimum: Role) -> str:
    if role is None:
        return NO_ACCESS
    if minimum is Role.OWNER:
        return NEED_OWNER
    return NEED_ADMIN if minimum is Role.ADMIN else NEED_EDITOR


def _int_or_none(raw: str) -> int | None:
    """An id out of callback data, or ``None`` for anything a crafted one carries."""
    try:
        return int(raw)
    except (TypeError, ValueError):
        return None


def _date_or_none(raw: str) -> Date | None:
    try:
        return Date.fromisoformat(raw)
    except (TypeError, ValueError):
        return None


def _today(school_class: SchoolClass) -> Date:
    """Today on the class's own wall clock, never the server's."""
    return datetime.now(school_class.tz).date()


def _bot_of(event: Message | CallbackQuery):
    """The Bot behind an update, or ``None``.

    The handler tests call these functions with plain stubs that have no bot
    at all, and a notification is never worth failing the edit it describes.
    """
    return getattr(event, "bot", None)


def _parse_day(raw: str, today: Date) -> Date | None:
    """«12.09» or «12.09.2026» -> a date.

    A bare day and month is read in the current year unless that lands well in
    the past: a school year straddles New Year, so «10.01» typed in December
    means the January that is coming, not the one that has gone.
    """
    parts = [part for part in re.split(r"[.\-/\s]+", raw.strip()) if part]
    if len(parts) not in (2, 3) or not all(part.isdigit() for part in parts):
        return None
    day, month = int(parts[0]), int(parts[1])
    if len(parts) == 3:
        year = int(parts[2])
        if year < 100:
            year += 2000
    else:
        year = today.year
    try:
        result = Date(year, month, day)
    except ValueError:
        return None
    if len(parts) == 2 and (today - result).days > 90:
        try:
            result = Date(year + 1, month, day)
        except ValueError:  # pragma: no cover - 29 February in a common year
            return None
    return result


async def _pending_requests(session: AsyncSession, class_id: int) -> list[AccessRequest]:
    return list(
        await session.scalars(
            select(AccessRequest)
            .where(AccessRequest.class_id == class_id, AccessRequest.status == "pending")
            .order_by(AccessRequest.id)
        )
    )


async def _member_names(session: AsyncSession, class_id: int) -> dict[int, str]:
    """Telegram id -> the name to show, already escaped by ``manage_render``."""
    members = await session.scalars(select(BotUser).where(BotUser.class_id == class_id))
    return {
        member.telegram_id: mr.person(member.full_name, member.username, member.telegram_id)
        for member in members
    }


# --------------------------------------------------------------------------
# 📚 Предметы
# --------------------------------------------------------------------------
#
# The subject dictionary is what keeps «Алгебра», «алгебра» and «Алг.» from
# being three different subjects in the timetable, the homework and the app's
# colour scheme. Renaming one therefore has to move every row that spells the
# old name, in the same transaction - see :func:`_rename_subject`.


async def _subjects_of(session: AsyncSession, class_id: int) -> list[Subject]:
    return list(
        await session.scalars(
            select(Subject).where(Subject.class_id == class_id).order_by(Subject.name)
        )
    )


async def _subject_view(session: AsyncSession, school_class: SchoolClass, role: Role):
    subjects = await _subjects_of(session, school_class.id)
    return mr.render_subjects(subjects), subject_list_keyboard(
        subjects,
        can_edit=role.at_least(Role.ADMIN),
        can_collect=role.at_least(Role.EDITOR),
    )


async def _subject_by_id(
    session: AsyncSession, school_class: SchoolClass, raw: str
) -> Subject | None:
    """Scoped by the query itself: an id naming another class's subject finds
    nothing, whatever the callback payload claims."""
    subject_id = _int_or_none(raw)
    if subject_id is None:
        return None
    return await session.scalar(
        select(Subject).where(Subject.id == subject_id, Subject.class_id == school_class.id)
    )


@router.message(Command("subjects"))
async def cmd_subjects(
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
    text, keyboard = await _subject_view(session, school_class, role)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(SubjectAction.filter(F.action == "list"))
async def subjects_list(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.EDITOR):
        await callback.answer(_refusal(role, Role.EDITOR), show_alert=True)
        return
    await state.clear()
    text, keyboard = await _subject_view(session, school_class, role)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


@router.callback_query(SubjectAction.filter(F.action == "open"))
async def subject_open(
    callback: CallbackQuery,
    callback_data: SubjectAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    subject = await _subject_by_id(session, school_class, callback_data.value)
    if subject is None:
        await callback.answer("Предмет уже удалён", show_alert=True)
        return

    await state.clear()
    await callback.message.edit_text(
        mr.render_subject_card(subject), reply_markup=subject_card_keyboard(subject.id)
    )
    await callback.answer()


#: field tag -> (state to wait in, prompt). The tag travels in callback data,
#: so an unknown one is refused rather than mapped to a default.
_SUBJECT_FIELDS = {
    "name": (EditSubject.name, "Новое название предмета:"),
    "short": (
        EditSubject.short_name,
        "Сокращение для узких экранов, до 16 символов. «-» — убрать:",
    ),
    "teacher": (EditSubject.teacher, "Кто ведёт предмет? «-» — убрать:"),
}


@router.callback_query(SubjectAction.filter(F.action == "field"))
async def subject_field(
    callback: CallbackQuery,
    callback_data: SubjectAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    field, _, raw_id = callback_data.value.partition(":")
    subject = await _subject_by_id(session, school_class, raw_id)
    if subject is None:
        await callback.answer("Предмет уже удалён", show_alert=True)
        return

    await state.update_data(subject_id=subject.id)

    if field == "colour":
        await state.set_state(EditSubject.colour)
        await callback.message.edit_text(
            f"Цвет предмета <b>{escape(subject.name)}</b>: сейчас {mr.swatch(subject.color)}.\n\n"
            "Выберите из готовых или пришлите свой в виде <code>#5B6ABF</code>.",
            reply_markup=colour_keyboard(subject.id),
        )
        await callback.answer()
        return

    target = _SUBJECT_FIELDS.get(field)
    if target is None:
        await callback.answer("Неизвестное поле", show_alert=True)
        return
    next_state, prompt = target
    await state.set_state(next_state)
    await callback.message.edit_text(
        f"<b>{escape(subject.name)}</b>\n\n{prompt}", reply_markup=cancel_keyboard()
    )
    await callback.answer()


async def _subject_from_state(
    state: FSMContext, session: AsyncSession, school_class: SchoolClass
) -> Subject | None:
    data = await state.get_data()
    return await _subject_by_id(session, school_class, str(data.get("subject_id", "")))


async def _rename_subject(
    session: AsyncSession, class_id: int, subject: Subject, new_name: str
) -> int:
    """Rename the dictionary entry and every row that spells the old name.

    The timetable, the homework and the замены store the subject as text, not
    as a foreign key - deliberately, so a lesson keeps its name when a subject
    is deleted. The price is that a rename has to be a cascade, and it has to
    happen in the caller's transaction: a half-applied rename would leave the
    class with two subjects where it had one and no way to tell which rows
    belong to which.
    """
    old_name = subject.name
    moved = 0
    for model in (TimetableEntry, Homework, LessonOverride):
        result = await session.execute(
            sa_update(model)
            .where(model.class_id == class_id, model.subject_name == old_name)
            .values(subject_name=new_name)
        )
        moved += result.rowcount or 0
    subject.name = new_name
    return moved


@router.message(EditSubject.name)
async def subject_rename(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await state.clear()
        return

    name = " ".join((message.text or "").split())
    if not 1 <= len(name) <= SUBJECT_NAME_MAX:
        await message.answer(f"Название от 1 до {SUBJECT_NAME_MAX} символов. Ещё раз:")
        return

    subject = await _subject_from_state(state, session, school_class)
    if subject is None:
        await state.clear()
        await message.answer("Предмет уже удалён.", reply_markup=back_to_menu())
        return
    if name == subject.name:
        await state.clear()
        await message.answer("Название не изменилось.", reply_markup=back_to_menu())
        return

    clash = await session.scalar(
        select(Subject).where(
            Subject.class_id == school_class.id, Subject.name == name, Subject.id != subject.id
        )
    )
    if clash is not None:
        await message.answer(
            f"Предмет <b>{escape(name)}</b> уже есть. Придумайте другое название:"
        )
        return

    old_name = subject.name
    moved = await _rename_subject(session, school_class.id, subject, name)
    await audit.record(
        session,
        school_class.id,
        message.from_user.id,
        "subject.rename",
        f"предмет «{old_name}» → «{name}», строк обновлено: {moved}",
    )
    await session.commit()
    await state.clear()

    await message.answer(
        f"✅ <b>{escape(old_name)}</b> → <b>{escape(name)}</b>.\n"
        f"Переименовано в расписании, заданиях и заменах: {moved}.",
        reply_markup=subject_card_keyboard(subject.id),
    )


async def _save_subject_text(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    field: str,
    limit: int,
    label: str,
) -> None:
    """The short-name and teacher steps: same shape, different column."""
    raw = " ".join((message.text or "").split())
    subject = await _subject_from_state(state, session, school_class)
    if subject is None:
        await state.clear()
        await message.answer("Предмет уже удалён.", reply_markup=back_to_menu())
        return

    value = None if raw in {"-", "—", ""} else raw[:limit]
    setattr(subject, field, value)
    await audit.record(
        session,
        school_class.id,
        message.from_user.id,
        f"subject.{field}",
        f"{label} предмета «{subject.name}»: {value or 'убрано'}",
    )
    await session.commit()
    await state.clear()
    await message.answer(
        mr.render_subject_card(subject), reply_markup=subject_card_keyboard(subject.id)
    )


@router.message(EditSubject.short_name)
async def subject_short_name(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await state.clear()
        return
    await _save_subject_text(
        message, state, session, school_class, "short_name", SHORT_NAME_MAX, "сокращение"
    )


@router.message(EditSubject.teacher)
async def subject_teacher(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await state.clear()
        return
    await _save_subject_text(
        message, state, session, school_class, "teacher", TEACHER_MAX, "учитель"
    )


async def _apply_colour(
    session: AsyncSession,
    school_class: SchoolClass,
    subject: Subject,
    telegram_id: int,
    colour: str | None,
) -> None:
    subject.color = colour
    await audit.record(
        session,
        school_class.id,
        telegram_id,
        "subject.colour",
        f"цвет предмета «{subject.name}»: {colour or 'убран'}",
    )
    await session.commit()


@router.callback_query(SubjectAction.filter(F.action == "colour"))
async def subject_colour_pick(
    callback: CallbackQuery,
    callback_data: SubjectAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    raw_id, _, raw_colour = callback_data.value.partition(":")
    subject = await _subject_by_id(session, school_class, raw_id)
    if subject is None:
        await callback.answer("Предмет уже удалён", show_alert=True)
        return

    if raw_colour == "none":
        colour = None
    else:
        match = COLOUR_RE.match(raw_colour)
        if match is None:
            await callback.answer("Неизвестный цвет", show_alert=True)
            return
        colour = f"#{match.group(1).upper()}"

    await _apply_colour(session, school_class, subject, callback.from_user.id, colour)
    await state.clear()
    await callback.message.edit_text(
        mr.render_subject_card(subject), reply_markup=subject_card_keyboard(subject.id)
    )
    await callback.answer("Цвет сохранён")


@router.message(EditSubject.colour)
async def subject_colour_typed(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await state.clear()
        return

    raw = (message.text or "").strip()
    subject = await _subject_from_state(state, session, school_class)
    if subject is None:
        await state.clear()
        await message.answer("Предмет уже удалён.", reply_markup=back_to_menu())
        return

    if raw in {"-", "—"}:
        colour = None
    else:
        match = COLOUR_RE.match(raw)
        if match is None:
            presets = ", ".join(value for _, value in COLOUR_PRESETS[:3])
            await message.answer(
                "Цвет пишется как <code>#5B6ABF</code> — шесть шестнадцатеричных цифр. "
                f"Например: {escape(presets)}. «-» — убрать цвет."
            )
            return
        colour = f"#{match.group(1).upper()}"

    await _apply_colour(session, school_class, subject, message.from_user.id, colour)
    await state.clear()
    await message.answer(
        mr.render_subject_card(subject), reply_markup=subject_card_keyboard(subject.id)
    )


@router.callback_query(SubjectAction.filter(F.action == "add"))
async def subject_add(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return
    await state.set_state(EditSubject.create)
    await callback.message.edit_text(
        "Название нового предмета:", reply_markup=cancel_keyboard()
    )
    await callback.answer()


@router.message(EditSubject.create)
async def subject_create(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await state.clear()
        return

    name = " ".join((message.text or "").split())
    if not 1 <= len(name) <= SUBJECT_NAME_MAX:
        await message.answer(f"Название от 1 до {SUBJECT_NAME_MAX} символов. Ещё раз:")
        return

    existing = await session.scalar(
        select(Subject).where(Subject.class_id == school_class.id, Subject.name == name)
    )
    if existing is not None:
        await state.clear()
        await message.answer(
            f"Предмет <b>{escape(name)}</b> уже есть.",
            reply_markup=subject_card_keyboard(existing.id),
        )
        return

    subject = Subject(class_id=school_class.id, name=name)
    session.add(subject)
    await audit.record(
        session, school_class.id, message.from_user.id, "subject.add", f"добавлен предмет «{name}»"
    )
    await session.commit()
    await state.clear()
    await message.answer(
        mr.render_subject_card(subject), reply_markup=subject_card_keyboard(subject.id)
    )


@router.callback_query(SubjectAction.filter(F.action == "delete"))
async def subject_delete(
    callback: CallbackQuery,
    callback_data: SubjectAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Deleting a subject leaves the lessons alone.

    The timetable stores the name, so the class keeps its расписание and only
    loses the colour and the teacher — which is what an admin cleaning up a
    duplicate entry means, and the opposite of what deleting the lessons would
    mean.
    """
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    subject = await _subject_by_id(session, school_class, callback_data.value)
    if subject is None:
        await callback.answer("Предмет уже удалён", show_alert=True)
        return

    name = subject.name
    await session.delete(subject)
    await audit.record(
        session, school_class.id, callback.from_user.id, "subject.delete",
        f"удалён предмет «{name}»",
    )
    await session.commit()
    await state.clear()

    text, keyboard = await _subject_view(session, school_class, role)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer(f"Удалён: {name}"[:200])


@router.callback_query(SubjectAction.filter(F.action == "collect"))
async def subjects_collect(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Create a Subject row for every name the timetable already uses.

    An editor may run this: it invents nothing, it only writes down the names
    that are already in the class's расписание.
    """
    if not _allowed(school_class, role, Role.EDITOR):
        await callback.answer(_refusal(role, Role.EDITOR), show_alert=True)
        return

    names = list(
        await session.scalars(
            select(TimetableEntry.subject_name)
            .where(TimetableEntry.class_id == school_class.id)
            .distinct()
        )
    )
    known = {subject.name for subject in await _subjects_of(session, school_class.id)}
    created = [name for name in sorted(names) if name and name not in known]
    for name in created:
        session.add(Subject(class_id=school_class.id, name=name[:SUBJECT_NAME_MAX]))

    if created:
        await audit.record(
            session,
            school_class.id,
            callback.from_user.id,
            "subject.collect",
            f"собрано предметов из расписания: {len(created)}",
        )
        await session.commit()

    await state.clear()
    text, keyboard = await _subject_view(session, school_class, role)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer(
        f"Добавлено: {len(created)}" if created else "Все предметы расписания уже в списке"
    )


# --------------------------------------------------------------------------
# 🏖 Особые дни
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
}

_KIND_SUMMARY = {
    DayKind.HOLIDAY: "каникулы / выходной",
    DayKind.SHORTENED: "сокращённые уроки",
    DayKind.REMOTE: "дистанционно",
}

HOLIDAY_DATE_HELP = (
    "Выберите день или пришлите дату: <code>12.09</code> или <code>12.09.2026</code>."
)


async def _holiday_view(session: AsyncSession, school_class: SchoolClass, role: Role):
    today = _today(school_class)
    overrides = list(
        await session.scalars(
            select(DayOverride)
            .where(DayOverride.class_id == school_class.id, DayOverride.date >= today)
            .order_by(DayOverride.date)
        )
    )
    schedules = {
        schedule.id: schedule.name
        for schedule in await session.scalars(
            select(BellSchedule).where(BellSchedule.class_id == school_class.id)
        )
    }
    return mr.render_holidays(overrides, schedules, today), holiday_list_keyboard(
        overrides,
        can_edit=role.at_least(Role.EDITOR),
        can_period=role.at_least(Role.ADMIN),
    )


async def _override_for(
    session: AsyncSession, class_id: int, day: Date
) -> DayOverride | None:
    return await session.scalar(
        select(DayOverride).where(DayOverride.class_id == class_id, DayOverride.date == day)
    )


async def _upsert_override(
    session: AsyncSession, class_id: int, day: Date, kind: DayKind
) -> DayOverride:
    override = await _override_for(session, class_id, day)
    if override is None:
        override = DayOverride(class_id=class_id, date=day, kind=kind)
        session.add(override)
    override.kind = kind
    if kind is not DayKind.SHORTENED:
        # A bell schedule only means anything on a shortened day; leaving a
        # stale one on a день каникул would surface in the day view as a
        # schedule nobody chose.
        override.bell_schedule_id = None
    return override


@router.message(Command("holidays"))
async def cmd_holidays(
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
    text, keyboard = await _holiday_view(session, school_class, role)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(DayKindAction.filter(F.action == "list"))
async def holidays_list(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.EDITOR):
        await callback.answer(_refusal(role, Role.EDITOR), show_alert=True)
        return
    await state.clear()
    text, keyboard = await _holiday_view(session, school_class, role)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


@router.callback_query(DayKindAction.filter(F.action == "add"))
async def holiday_add(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.EDITOR):
        await callback.answer(_refusal(role, Role.EDITOR), show_alert=True)
        return

    await state.set_state(AddHoliday.date)
    await callback.message.edit_text(
        f"🏖 <b>Особый день</b>\n\n{HOLIDAY_DATE_HELP}",
        reply_markup=date_picker(
            DayKindAction, "pick_date", upcoming_dates(_today(school_class), 14)
        ),
    )
    await callback.answer()


async def _ask_kind(editable, day: Date) -> None:
    await editable.edit_text(
        f"<b>{day:%d.%m.%Y}</b>\n\nЧто это за день?",
        reply_markup=day_kind_keyboard(day.isoformat()),
    )


@router.callback_query(DayKindAction.filter(F.action == "pick_date"))
async def holiday_pick_date(
    callback: CallbackQuery,
    callback_data: DayKindAction,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.EDITOR):
        await callback.answer(_refusal(role, Role.EDITOR), show_alert=True)
        return

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
async def holiday_typed_date(
    message: Message,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.EDITOR):
        await state.clear()
        return

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
async def holiday_kind(
    callback: CallbackQuery,
    callback_data: DayKindAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.EDITOR):
        await callback.answer(_refusal(role, Role.EDITOR), show_alert=True)
        return

    raw_date, _, tag = callback_data.value.partition(":")
    day = _date_or_none(raw_date)
    if day is None:
        await callback.answer("Непонятная дата", show_alert=True)
        return

    if tag == "normal":
        override = await _override_for(session, school_class.id, day)
        if override is not None:
            await session.delete(override)
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

    await _upsert_override(session, school_class.id, day, kind)
    await audit.record(
        session, school_class.id, callback.from_user.id, "dayoverride.set",
        f"{day:%d.%m}: {_KIND_SUMMARY[kind]}",
    )
    await session.commit()

    if kind is DayKind.SHORTENED:
        schedules = list(
            await session.scalars(
                select(BellSchedule)
                .where(BellSchedule.class_id == school_class.id)
                .order_by(BellSchedule.id)
            )
        )
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
async def holiday_bells(
    callback: CallbackQuery,
    callback_data: DayKindAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.EDITOR):
        await callback.answer(_refusal(role, Role.EDITOR), show_alert=True)
        return

    raw_date, _, raw_id = callback_data.value.partition(":")
    day = _date_or_none(raw_date)
    if day is None:
        await callback.answer("Непонятная дата", show_alert=True)
        return

    override = await _override_for(session, school_class.id, day)
    if override is None:
        await callback.answer("День уже не отмечен", show_alert=True)
        return

    schedule_id = _int_or_none(raw_id)
    if schedule_id:
        schedule = await session.scalar(
            select(BellSchedule).where(
                BellSchedule.id == schedule_id, BellSchedule.class_id == school_class.id
            )
        )
        if schedule is None:
            await callback.answer("Расписание звонков не найдено", show_alert=True)
            return
        override.bell_schedule_id = schedule.id
        summary = f"{day:%d.%m}: звонки «{schedule.name}»"
    else:
        override.bell_schedule_id = None
        summary = f"{day:%d.%m}: обычные звонки"

    await audit.record(
        session, school_class.id, callback.from_user.id, "dayoverride.bells", summary
    )
    await session.commit()
    await _ask_note(callback.message, state, day, DayKind.SHORTENED)
    await callback.answer()


@router.message(AddHoliday.note)
async def holiday_note(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.EDITOR):
        await state.clear()
        return

    data = await state.get_data()
    day = _date_or_none(str(data.get("date", "")))
    if day is None:
        await state.clear()
        await message.answer("Начните заново: /holidays", reply_markup=back_to_menu())
        return

    override = await _override_for(session, school_class.id, day)
    if override is None:
        await state.clear()
        await message.answer("День уже не отмечен.", reply_markup=back_to_menu())
        return

    raw = " ".join((message.text or "").split())
    if raw not in {"-", "—", ""}:
        override.note = raw[:NOTE_MAX]
        await audit.record(
            session, school_class.id, message.from_user.id, "dayoverride.note",
            f"{day:%d.%m}: заметка «{override.note}»",
        )
        await session.commit()

    await state.clear()
    text, keyboard = await _holiday_view(session, school_class, role)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(DayKindAction.filter(F.action == "delete"))
async def holiday_delete(
    callback: CallbackQuery,
    callback_data: DayKindAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.EDITOR):
        await callback.answer(_refusal(role, Role.EDITOR), show_alert=True)
        return

    day = _date_or_none(callback_data.value)
    if day is None:
        await callback.answer("Непонятная дата", show_alert=True)
        return

    override = await _override_for(session, school_class.id, day)
    if override is not None:
        await session.delete(override)
        await audit.record(
            session, school_class.id, callback.from_user.id, "dayoverride.delete",
            f"{day:%d.%m}: отметка снята",
        )
        await session.commit()

    await state.clear()
    text, keyboard = await _holiday_view(session, school_class, role)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer("Снято" if override is not None else "Уже снято")


@router.callback_query(DayKindAction.filter(F.action == "period"))
async def holiday_period_start(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """A whole период of каникулы is admin-only: it rewrites weeks of the
    class's calendar from one message."""
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    await state.set_state(AddHoliday.period)
    await callback.message.edit_text(
        "📆 <b>Каникулы периодом</b>\n\n"
        "Пришлите период: <code>26.10-05.11</code>.\n"
        f"Каждый день внутри будет отмечен как каникулы, не больше "
        f"{PERIOD_MAX_DAYS} дней за раз.",
        reply_markup=cancel_keyboard(),
    )
    await callback.answer()


@router.message(AddHoliday.period)
async def holiday_period_apply(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await state.clear()
        return

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

    for offset in range(span):
        await _upsert_override(
            session, school_class.id, first + timedelta(days=offset), DayKind.HOLIDAY
        )
    await audit.record(
        session,
        school_class.id,
        message.from_user.id,
        "dayoverride.period",
        f"каникулы {first:%d.%m}–{last:%d.%m}, дней: {span}",
    )
    await session.commit()
    await state.clear()

    await message.answer(mr.render_period_result(first, last, span))
    text, keyboard = await _holiday_view(session, school_class, role)
    await message.answer(text, reply_markup=keyboard)


# --------------------------------------------------------------------------
# 🔔 Звонки
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


async def _write_periods(session: AsyncSession, schedule: BellSchedule, rows: list) -> None:
    """Replace a schedule's rows wholesale.

    The delete is a bulk statement, which goes round the ORM, so the eagerly
    loaded ``periods`` collection is stale afterwards and every caller
    refreshes it before rendering the result.
    """
    await session.execute(sa_delete(BellPeriod).where(BellPeriod.schedule_id == schedule.id))
    for index, start, end in rows:
        session.add(
            BellPeriod(schedule_id=schedule.id, index=index, starts_at=start, ends_at=end)
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

    await _write_periods(session, schedule, rows)
    await audit.record(
        session,
        school_class.id,
        message.from_user.id,
        "bells.edit",
        f"звонки «{schedule.name}»: {len(rows)} уроков",
    )
    await session.commit()
    await session.refresh(schedule, ["periods"])
    await state.clear()

    lines = [f"✅ Звонки «{escape(schedule.name)}» сохранены: {len(rows)}."]
    if rejected:
        lines.append("")
        lines.append("⚠️ Не разобрал строки:")
        lines.extend(f"<code>{escape(line)}</code>" for line in rejected[:10])
    await message.answer("\n".join(lines))

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
    await _write_periods(session, schedule, rows)
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

    lines = [f"✅ Расписание «{escape(name)}» создано: {len(rows)} уроков."]
    if rejected:
        lines.append("⚠️ Не разобрал строки: " + ", ".join(escape(line) for line in rejected[:5]))
    await message.answer("\n".join(lines))

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

    school_class.bell_schedule_id = schedule.id
    await audit.record(
        session, school_class.id, callback.from_user.id, "bells.default",
        f"основное расписание звонков: «{schedule.name}»",
    )
    await session.commit()

    text, keyboard = await _bells_view(session, school_class)
    await callback.message.edit_text(text, reply_markup=keyboard)
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
