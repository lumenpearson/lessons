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

import logging
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

log = logging.getLogger(__name__)

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


# --------------------------------------------------------------------------
# 📱 Устройства
# --------------------------------------------------------------------------
#
# A device token is read-only until its owner links it to a Telegram account;
# from then on it writes with whatever role that account holds *right now*
# (``linking.effective_role``). Which is why this page shows the role as a
# lookup and not as something stored on the row: revoking somebody in «Доступ»
# has already revoked their phone by the time this page is drawn.


async def _device_view(session: AsyncSession, school_class: SchoolClass):
    devices = await linking.devices_of(session, school_class.id)
    owners: dict[int, tuple[str, Role | None]] = {}
    names = await _member_names(session, school_class.id)
    for device in devices:
        if device.telegram_id is None or device.telegram_id in owners:
            continue
        owners[device.telegram_id] = (
            names.get(device.telegram_id, str(device.telegram_id)),
            await linking.effective_role(session, device),
        )
    return mr.render_devices(devices, owners), device_keyboard(devices)


async def _device_by_id(
    session: AsyncSession, school_class: SchoolClass, raw: str
) -> DeviceToken | None:
    device_id = _int_or_none(raw)
    if device_id is None:
        return None
    return await session.scalar(
        select(DeviceToken).where(
            DeviceToken.id == device_id, DeviceToken.class_id == school_class.id
        )
    )


@router.message(Command("devices"))
async def cmd_devices(
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
    text, keyboard = await _device_view(session, school_class)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(DeviceAction.filter(F.action == "list"))
async def devices_list(
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
    text, keyboard = await _device_view(session, school_class)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


@router.callback_query(DeviceAction.filter(F.action == "revoke"))
async def device_revoke(
    callback: CallbackQuery,
    callback_data: DeviceAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Revoked, not deleted: the row is what the API checks a token against,
    and keeping it is what makes the refusal instant and permanent."""
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    device = await _device_by_id(session, school_class, callback_data.value)
    if device is None:
        await callback.answer("Устройство не найдено", show_alert=True)
        return

    device.revoked = True
    name = device.device_name or f"Устройство {device.id}"
    await audit.record(
        session, school_class.id, callback.from_user.id, "device.revoke",
        f"отключено устройство «{name}»",
    )
    await session.commit()

    text, keyboard = await _device_view(session, school_class)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer(f"Отключено: {name}"[:200])


@router.callback_query(DeviceAction.filter(F.action == "unlink"))
async def device_unlink(
    callback: CallbackQuery,
    callback_data: DeviceAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Back to read-only without taking the phone off the class."""
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    device = await _device_by_id(session, school_class, callback_data.value)
    if device is None:
        await callback.answer("Устройство не найдено", show_alert=True)
        return
    if device.telegram_id is None:
        await callback.answer("Устройство и так не привязано", show_alert=True)
        return

    name = device.device_name or f"Устройство {device.id}"
    await linking.unlink_device(session, device)
    await audit.record(
        session, school_class.id, callback.from_user.id, "device.unlink",
        f"отвязано устройство «{name}» — снова только чтение",
    )
    await session.commit()

    text, keyboard = await _device_view(session, school_class)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer("Отвязано — теперь только чтение")


# --------------------------------------------------------------------------
# 📜 Журнал
# --------------------------------------------------------------------------


async def _audit_view(session: AsyncSession, school_class: SchoolClass, offset: int):
    entries = await audit.recent(session, school_class.id, limit=AUDIT_PAGE + 1, offset=offset)
    more = len(entries) > AUDIT_PAGE
    entries = entries[:AUDIT_PAGE]
    names = await _member_names(session, school_class.id)
    return (
        mr.render_audit(entries, names, school_class.tz, offset),
        audit_keyboard(offset, more),
    )


@router.message(Command("log"))
async def cmd_log(
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
    text, keyboard = await _audit_view(session, school_class, 0)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(AuditAction.filter(F.action == "page"))
async def audit_page(
    callback: CallbackQuery,
    callback_data: AuditAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    offset = _int_or_none(callback_data.value) or 0
    # A negative offset is not a page; SQL would take it as "no offset" and
    # quietly show page one under a heading that says otherwise.
    offset = max(0, offset)

    await state.clear()
    text, keyboard = await _audit_view(session, school_class, offset)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


# --------------------------------------------------------------------------
# ⚙️ Класс
# --------------------------------------------------------------------------


async def _class_card(
    session: AsyncSession, school_class: SchoolClass, role: Role, telegram_id: int
):
    memberships = await list_memberships(session, telegram_id)
    members = int(
        await session.scalar(
            select(func.count()).select_from(BotUser).where(BotUser.class_id == school_class.id)
        )
        or 0
    )
    devices = int(
        await session.scalar(
            select(func.count())
            .select_from(DeviceToken)
            .where(DeviceToken.class_id == school_class.id, DeviceToken.revoked.is_(False))
        )
        or 0
    )
    pending = len(await _pending_requests(session, school_class.id))

    text = mr.render_class_card(
        school_class,
        zone_label=label_for(school_class.timezone_name),
        feed_ready=bool(school_class.calendar_token),
        members=members,
        devices=devices,
        pending=pending,
    )
    keyboard = class_menu(
        is_owner=role.at_least(Role.OWNER),
        # The button only appears for somebody who actually has somewhere to
        # switch to; one membership is the overwhelmingly common case.
        many_classes=len(memberships) > 1,
        pending=pending,
    )
    return text, keyboard


@router.message(Command("class"))
async def cmd_class(
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
    text, keyboard = await _class_card(session, school_class, role, message.from_user.id)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(ManageAction.filter(F.action == "root"))
async def class_root(
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
    text, keyboard = await _class_card(session, school_class, role, callback.from_user.id)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


#: field tag -> (column, prompt, limit). Only these three are typed; the zone
#: and the join code have pickers of their own in ``start.py``.
_CLASS_FIELDS = {
    "rename": ("name", "Новое название класса:", 64),
    "school": ("school", "Название школы («-» — убрать):", 200),
    "city": ("city", "Город («-» — убрать):", 120),
}


@router.callback_query(ManageAction.filter(F.action.in_({"rename", "school", "city"})))
async def class_field_prompt(
    callback: CallbackQuery,
    callback_data: ManageAction,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    target = _CLASS_FIELDS.get(callback_data.action)
    if target is None:  # pragma: no cover - the filter already narrowed it
        await callback.answer("Неизвестное поле", show_alert=True)
        return

    _, prompt, _ = target
    await state.set_state(EditClassField.value)
    await state.update_data(field=callback_data.action)
    await callback.message.edit_text(prompt, reply_markup=cancel_keyboard())
    await callback.answer()


@router.message(EditClassField.value)
async def class_field_apply(
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
    target = _CLASS_FIELDS.get(str(data.get("field", "")))
    if target is None:
        await state.clear()
        await message.answer("Начните заново: /class", reply_markup=back_to_menu())
        return
    column, prompt, limit = target

    raw = " ".join((message.text or "").split())
    if column == "name":
        # The name is what every other message calls this class, and what the
        # delete confirmation is typed against; it cannot be empty.
        if not 1 <= len(raw) <= limit:
            await message.answer(f"От 1 до {limit} символов. {prompt}")
            return
        value = raw
    else:
        value = None if raw in {"-", "—", ""} else raw[:limit]

    setattr(school_class, column, value)
    await audit.record(
        session,
        school_class.id,
        message.from_user.id,
        f"class.{column}",
        f"{column}: {value or 'убрано'}",
    )
    await session.commit()
    await state.clear()

    text, keyboard = await _class_card(session, school_class, role, message.from_user.id)
    await message.answer(text, reply_markup=keyboard)


# --------------------------------------------------------------------------
# 🔀 Смена класса
# --------------------------------------------------------------------------


@router.callback_query(ManageAction.filter(F.action == "switch"))
async def class_switch(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.VIEWER):
        await callback.answer(NO_ACCESS, show_alert=True)
        return

    memberships = await list_memberships(session, callback.from_user.id)
    classes: list[SchoolClass] = []
    for member in memberships:
        found = await session.get(SchoolClass, member.class_id)
        if found is not None:
            classes.append(found)
    if len(classes) < 2:
        await callback.answer("Вы состоите только в одном классе", show_alert=True)
        return

    await callback.message.edit_text(
        "🔀 <b>Сменить класс</b>\n\nВ каком классе работаем дальше?",
        reply_markup=switch_keyboard(classes),
    )
    await callback.answer()


@router.callback_query(ManageAction.filter(F.action == "switch_to"))
async def class_switch_to(
    callback: CallbackQuery,
    callback_data: ManageAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Remember which class this person is working in.

    The preference is written to FSM storage — the database — under its own
    key, and the middleware reads exactly that key on the next update. Nothing
    is kept in the process: on Vercel the next message is a different one.

    The membership is checked here and checked again by the middleware every
    time it reads the preference, so a class somebody is later removed from
    stops being their default by itself.
    """
    if school_class is None or role is None:
        await callback.answer(NO_ACCESS, show_alert=True)
        return

    class_id = _int_or_none(callback_data.value)
    memberships = await list_memberships(session, callback.from_user.id)
    if class_id is None or not any(member.class_id == class_id for member in memberships):
        await callback.answer("Вы не состоите в этом классе", show_alert=True)
        return

    target = await session.get(SchoolClass, class_id)
    if target is None:
        await callback.answer("Класс не найден", show_alert=True)
        return

    storage = DatabaseStorage(SessionLocal)
    await storage.set_data(prefs_key(callback.from_user.id), {"class_id": class_id})

    await state.clear()
    await callback.message.edit_text(
        f"✅ Текущий класс: <b>{escape(target.name)}</b>.\n\n"
        "Все команды теперь про него. Открыть меню: /start",
        reply_markup=back_to_menu(),
    )
    await callback.answer(f"Класс: {target.name}"[:200])


# --------------------------------------------------------------------------
# 🗑 Удаление класса
# --------------------------------------------------------------------------


@router.callback_query(ManageAction.filter(F.action == "delete"))
async def class_delete_prompt(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.OWNER):
        await callback.answer(NEED_OWNER, show_alert=True)
        return

    await state.set_state(DeleteClass.confirm)
    await callback.message.edit_text(
        f"🗑 <b>Удалить класс {escape(school_class.name)}?</b>\n\n"
        "Вместе с ним исчезнут расписание, домашние задания, замены, события, "
        "журнал и все привязанные устройства. Это нельзя отменить.\n\n"
        f"Чтобы подтвердить, пришлите название класса точно так: "
        f"<code>{escape(school_class.name)}</code>",
        reply_markup=cancel_keyboard(),
    )
    await callback.answer()


@router.message(DeleteClass.confirm)
async def class_delete_apply(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Typing the name back is the confirmation, and the only one.

    A «вы уверены?» button is pressed by the same thumb that pressed the one
    before it. Nothing here writes an audit line: the log lives in the class
    and goes with it.
    """
    if not _allowed(school_class, role, Role.OWNER):
        await state.clear()
        return

    typed = (message.text or "").strip()
    if typed != school_class.name:
        await message.answer(
            "Название не совпало — класс не тронут.\n"
            f"Чтобы удалить, пришлите ровно: <code>{escape(school_class.name)}</code>"
        )
        return

    name = school_class.name
    await session.delete(school_class)
    await session.commit()
    await state.clear()
    await message.answer(
        f"🗑 Класс <b>{escape(name)}</b> удалён вместе со всеми данными."
    )


# --------------------------------------------------------------------------
# 📅 Календарь
# --------------------------------------------------------------------------


async def _calendar_text(session: AsyncSession, school_class: SchoolClass, rotated: bool) -> str:
    base = get_settings().public_base_url.rstrip("/")
    if not base:
        return mr.render_calendar(None)
    if rotated:
        token = await calendar_service.rotate_calendar_token(session, school_class)
    else:
        token = await calendar_service.ensure_calendar_token(session, school_class)
    return mr.render_calendar(f"{base}/api/v1/calendar/{token}.ics", rotated=rotated)


def _calendar_keyboard(role: Role):
    rows: list[list[InlineKeyboardButton]] = []
    if role.at_least(Role.ADMIN):
        rows.append(
            [
                InlineKeyboardButton(
                    text="🔁 Новая ссылка",
                    callback_data=ManageAction(action="rotate_feed").pack(),
                )
            ]
        )
        rows.append(back_to("root"))
    return back_to_menu(rows)


@router.message(Command("calendar"))
async def cmd_calendar(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Any member may subscribe: the feed is read-only and its secret is not
    the join code, so a calendar URL cannot be turned into write access."""
    if not _allowed(school_class, role, Role.VIEWER):
        await message.answer(NO_ACCESS)
        return
    await state.clear()
    await message.answer(
        await _calendar_text(session, school_class, rotated=False),
        reply_markup=_calendar_keyboard(role),
    )


@router.callback_query(ManageAction.filter(F.action == "calendar"))
async def calendar_card(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.VIEWER):
        await callback.answer(NO_ACCESS, show_alert=True)
        return
    await callback.message.edit_text(
        await _calendar_text(session, school_class, rotated=False),
        reply_markup=_calendar_keyboard(role),
    )
    await callback.answer()


@router.callback_query(ManageAction.filter(F.action == "rotate_feed"))
async def calendar_rotate(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Every existing subscription stops updating — that is the point."""
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    text = await _calendar_text(session, school_class, rotated=True)
    await audit.record(
        session, school_class.id, callback.from_user.id, "calendar.rotate",
        "выдана новая ссылка на календарь, старая отключена",
    )
    await session.commit()
    await callback.message.edit_text(text, reply_markup=_calendar_keyboard(role))
    await callback.answer("Ссылка обновлена")


# --------------------------------------------------------------------------
# 📤 Экспорт и 📥 импорт расписания
# --------------------------------------------------------------------------


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
    preview = mr.render_import_preview(days, rejected)
    if bells:
        preview += f"\n• Звонки: {len(bells)} уроков"
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

    if days:
        await session.execute(
            sa_delete(TimetableEntry).where(
                TimetableEntry.class_id == school_class.id,
                TimetableEntry.weekday.in_(list(days)),
            )
        )
    total = 0
    for weekday, rows in days.items():
        for index, subject, room, teacher, parity in rows:
            session.add(
                TimetableEntry(
                    class_id=school_class.id,
                    weekday=weekday,
                    index=index,
                    subject_name=subject,
                    room=room,
                    teacher=teacher,
                    parity=parity,
                )
            )
            total += 1

    schedule = None
    if bells:
        if school_class.bell_schedule_id:
            schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
        if schedule is None:
            schedule = BellSchedule(class_id=school_class.id, name="Обычное")
            session.add(schedule)
            await session.flush()
            school_class.bell_schedule_id = schedule.id
        await _write_periods(session, schedule, bells)

    summary = f"импорт расписания: дней {len(days)}, уроков {total}"
    if bells:
        summary += f", звонков {len(bells)}"
    await audit.record(
        session, school_class.id, callback.from_user.id, "timetable.import", summary
    )
    await session.commit()
    if schedule is not None:
        await session.refresh(schedule, ["periods"])
    await state.clear()

    lines = [f"✅ Импорт применён: {len(days)} дн., уроков — {total}."]
    for weekday in sorted(days):
        lines.append(f"• {WEEKDAY_FULL[weekday - 1]}: {len(days[weekday])}")
    if bells:
        lines.append(f"• Звонки: {len(bells)}")
    await callback.message.edit_text("\n".join(lines), reply_markup=back_to_menu())
    await callback.answer("Готово")


# --------------------------------------------------------------------------
# 📊 Статистика и 🔎 поиск
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
    # One honest caveat: SQLite's own ``lower()`` folds ASCII only, so on the
    # development file «Алгебра» does not match «алгебра». Postgres — which is
    # what a real class runs on — folds Cyrillic properly, so the search works
    # where it matters; teaching SQLite otherwise means an ICU build or a
    # second stored column, and neither is worth it for a dev convenience.
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


# --------------------------------------------------------------------------
# 📱 /link и 🙋 /request
# --------------------------------------------------------------------------


@router.message(Command("link"))
async def cmd_link(
    message: Message,
    command: CommandObject,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Attach the phone showing ``code`` to this account.

    No role check beyond membership: linking gives the phone *this* account's
    role, whatever it is, so it can never be more than the person already has.
    """
    if not _allowed(school_class, role, Role.VIEWER):
        await message.answer(NO_ACCESS)
        return

    code = (command.args or "").strip()
    if not 1 <= len(code) <= 16:
        await message.answer(
            "📱 <b>Привязка телефона</b>\n\n"
            "Откройте в приложении экран привязки и пришлите код: "
            "<code>/link ABC123</code>."
        )
        return

    device = await linking.link_device(session, code, message.from_user.id)
    if device is None:
        await message.answer(
            "Код не подошёл. Он действует один раз — откройте экран привязки "
            "в приложении ещё раз и пришлите новый."
        )
        return

    device_role = await linking.effective_role(session, device)
    name = escape(device.device_name or "Телефон")
    if device_role is not None and device_role.at_least(Role.EDITOR):
        tail = f"Ваша роль: <b>{device_role.title_ru}</b> — приложение может редактировать."
    else:
        title = device_role.title_ru if device_role is not None else "нет роли"
        tail = (
            f"Ваша роль: <b>{title}</b> — только чтение. "
            "Запросить доступ редактора: /request"
        )
    await audit.record(
        session,
        device.class_id,
        message.from_user.id,
        "device.link",
        f"привязано устройство «{device.device_name or device.id}»",
    )
    await session.commit()
    await message.answer(f"📱 Устройство «{name}» привязано. {tail}")


async def _create_request(
    session: AsyncSession, school_class: SchoolClass, telegram_id: int, text: str | None
) -> AccessRequest:
    """One open request per person per class — a second one replaces the first,
    so a nervous requester cannot fill an admin's screen."""
    existing = await session.scalar(
        select(AccessRequest).where(
            AccessRequest.class_id == school_class.id,
            AccessRequest.telegram_id == telegram_id,
            AccessRequest.status == "pending",
        )
    )
    if existing is None:
        existing = AccessRequest(
            class_id=school_class.id,
            telegram_id=telegram_id,
            requested_role=Role.EDITOR,
            status="pending",
        )
        session.add(existing)
    existing.requested_role = Role.EDITOR
    existing.status = "pending"
    existing.message = (text or None) and text[:300]
    existing.decided_by = None
    existing.decided_at = None
    await session.flush()
    return existing


async def _notify_admins(
    session: AsyncSession,
    bot,
    school_class: SchoolClass,
    request: AccessRequest,
    who: str,
) -> None:
    """Tell every admin, and let one bad recipient be nobody else's problem."""
    if bot is None:
        return

    admins = await session.scalars(
        select(BotUser).where(
            BotUser.class_id == school_class.id,
            BotUser.role.in_([Role.ADMIN, Role.OWNER]),
        )
    )
    body = (
        f"🙋 <b>Запрос доступа</b>\n\n"
        f"{who} просит роль <b>{Role.EDITOR.title_ru}</b> "
        f"в классе <b>{escape(school_class.name)}</b>."
    )
    if request.message:
        body += f"\n\n<i>{escape(request.message)}</i>"

    for admin in admins:
        try:
            await bot.send_message(
                admin.telegram_id, body, reply_markup=request_keyboard(request.id)
            )
        except Exception:  # noqa: BLE001 - one blocked admin is not the requester's problem
            log.warning("could not notify admin %s", admin.telegram_id, exc_info=True)


@router.message(Command("request"))
async def cmd_request(
    message: Message,
    command: CommandObject,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.VIEWER):
        await message.answer(NO_ACCESS)
        return
    if role.at_least(Role.EDITOR):
        await message.answer(
            f"У вас уже роль <b>{role.title_ru}</b> — запрашивать нечего."
        )
        return

    text = (command.args or "").strip()
    if not text:
        await state.set_state(RequestAccess.message)
        await message.answer(
            "🙋 <b>Запрос доступа редактора</b>\n\n"
            "Напишите пару слов о себе — администратор увидит их вместе с запросом. "
            "Или пришлите <code>-</code>, чтобы отправить без комментария.",
            reply_markup=cancel_keyboard(),
        )
        return

    await _submit_request(message, state, session, school_class, text)


@router.message(RequestAccess.message)
async def request_message(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.VIEWER) or role.at_least(Role.EDITOR):
        await state.clear()
        return
    raw = " ".join((message.text or "").split())
    await _submit_request(
        message, state, session, school_class, None if raw in {"-", "—", ""} else raw
    )


async def _submit_request(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    text: str | None,
) -> None:
    request = await _create_request(session, school_class, message.from_user.id, text)
    who = mr.person(message.from_user.full_name, message.from_user.username, message.from_user.id)
    await audit.record(
        session,
        school_class.id,
        message.from_user.id,
        "access.request",
        f"{message.from_user.full_name or message.from_user.id} просит роль редактора",
    )
    await session.commit()
    await state.clear()

    await _notify_admins(session, _bot_of(message), school_class, request, who)
    await message.answer(
        "✅ Запрос отправлен администраторам класса. Они ответят здесь же."
    )


async def _tell_requester(bot, telegram_id: int, text: str) -> None:
    if bot is None:
        return
    try:
        await bot.send_message(telegram_id, text)
    except Exception:  # noqa: BLE001 - the decision stands whether or not it was delivered
        log.warning("could not tell %s about the decision", telegram_id, exc_info=True)


async def _request_by_id(
    session: AsyncSession, school_class: SchoolClass, raw: str
) -> AccessRequest | None:
    request_id = _int_or_none(raw)
    if request_id is None:
        return None
    return await session.scalar(
        select(AccessRequest).where(
            AccessRequest.id == request_id,
            AccessRequest.class_id == school_class.id,
            AccessRequest.status == "pending",
        )
    )


@router.callback_query(RequestAction.filter(F.action == "approve"))
async def request_approve(
    callback: CallbackQuery,
    callback_data: RequestAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Grant the requested role through the same rules as «👥 Доступ».

    ``can_grant`` is the single source of "may I hand out this role", and the
    rank guard below is the same one ``access.py`` applies when changing an
    existing member: nobody may raise somebody to their own level or touch a
    peer.
    """
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    request = await _request_by_id(session, school_class, callback_data.value)
    if request is None:
        await callback.answer("Запрос уже закрыт", show_alert=True)
        return

    target_role = request.requested_role
    if not can_grant(role, target_role):
        await callback.answer("Нельзя выдать роль выше вашей", show_alert=True)
        return

    member = await session.scalar(
        select(BotUser).where(
            BotUser.class_id == school_class.id,
            BotUser.telegram_id == request.telegram_id,
        )
    )
    if member is not None and member.role.rank >= role.rank:
        await callback.answer("Нельзя менять роль этого пользователя", show_alert=True)
        return

    if member is None:
        member = BotUser(
            telegram_id=request.telegram_id,
            class_id=school_class.id,
            role=target_role,
            granted_by=callback.from_user.id,
        )
        session.add(member)
    elif member.role.rank < target_role.rank:
        member.role = target_role
        member.granted_by = callback.from_user.id

    request.status = "approved"
    request.decided_by = callback.from_user.id
    request.decided_at = datetime.utcnow()

    name = mr.person(member.full_name, member.username, member.telegram_id)
    await audit.record(
        session,
        school_class.id,
        callback.from_user.id,
        "access.approve",
        f"выдана роль {target_role.title_ru}: {member.full_name or member.telegram_id}",
    )
    await session.commit()

    await _tell_requester(
        _bot_of(callback),
        request.telegram_id,
        f"✅ Доступ выдан: <b>{target_role.title_ru}</b> в классе "
        f"<b>{escape(school_class.name)}</b>. Откройте /start.",
    )
    await callback.message.edit_text(
        f"✅ {name} — теперь <b>{target_role.title_ru}</b>.", reply_markup=back_to_menu()
    )
    await callback.answer("Выдано")


@router.callback_query(RequestAction.filter(F.action == "decline"))
async def request_decline(
    callback: CallbackQuery,
    callback_data: RequestAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if not _allowed(school_class, role, Role.ADMIN):
        await callback.answer(_refusal(role, Role.ADMIN), show_alert=True)
        return

    request = await _request_by_id(session, school_class, callback_data.value)
    if request is None:
        await callback.answer("Запрос уже закрыт", show_alert=True)
        return

    request.status = "declined"
    request.decided_by = callback.from_user.id
    request.decided_at = datetime.utcnow()
    await audit.record(
        session,
        school_class.id,
        callback.from_user.id,
        "access.decline",
        f"отклонён запрос доступа от {request.telegram_id}",
    )
    await session.commit()

    await _tell_requester(
        _bot_of(callback),
        request.telegram_id,
        f"✖️ Запрос доступа в классе <b>{escape(school_class.name)}</b> отклонён.",
    )
    await callback.message.edit_text("✖️ Запрос отклонён.", reply_markup=back_to_menu())
    await callback.answer("Отклонено")
