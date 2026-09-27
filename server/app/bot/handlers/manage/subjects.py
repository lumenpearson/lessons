"""«📚 Предметы»: the class's subject dictionary.

Part of :mod:`app.bot.handlers.manage`; the rules every screen of it
follows are in that package's docstring.
"""

from __future__ import annotations

import re
from html import escape

from aiogram import F, Router
from aiogram.filters import Command
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot import manage_render as mr
from app.bot.handlers.manage._common import _int_or_none, needs
from app.bot.keyboards import back_to_menu, cancel_keyboard
from app.bot.manage_keyboards import (
    COLOUR_PRESETS,
    SubjectAction,
    colour_keyboard,
    subject_card_keyboard,
    subject_list_keyboard,
)
from app.bot.manage_states import EditSubject
from app.bot.render import plural
from app.models import Role, SchoolClass, Subject
from app.services.manage import subjects as subjects_service

router = Router(name="manage.subjects")

COLOUR_RE = re.compile(r"^#?([0-9a-fA-F]{6})$")

SUBJECT_NAME_MAX = 120
SHORT_NAME_MAX = 16
TEACHER_MAX = 120


# --------------------------------------------------------------------------
# 📚 Subjects
# --------------------------------------------------------------------------
#
# The subject dictionary is what keeps «Алгебра», «алгебра» and «Алг.» from
# being three different subjects in the timetable, the homework and the app's
# colour scheme. Renaming one therefore has to move every row that spells the
# old name, in the same transaction - see :func:`_rename_subject`.


async def _subject_view(session: AsyncSession, school_class: SchoolClass, role: Role):
    # «📚 Предметы» adopts the timetable's names on the way in, so the list
    # cannot be empty while the class has a full timetable. «Собрать из
    # расписания» stays: it is now the button for "I have just pasted a day and
    # want to see its subjects without leaving", and it is still free to press.
    subjects = await subjects_service.listing(session, school_class.id)
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
    return await subjects_service.subject_of(session, school_class.id, subject_id)


@router.message(Command("subjects"))
@needs(Role.EDITOR)
async def cmd_subjects(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await state.clear()
    text, keyboard = await _subject_view(session, school_class, role)
    await message.answer(text, reply_markup=keyboard)


@router.callback_query(SubjectAction.filter(F.action == "list"))
@needs(Role.EDITOR)
async def subjects_list(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await state.clear()
    text, keyboard = await _subject_view(session, school_class, role)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


@router.callback_query(SubjectAction.filter(F.action == "open"))
@needs(Role.ADMIN)
async def subject_open(
    callback: CallbackQuery,
    callback_data: SubjectAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
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
@needs(Role.ADMIN)
async def subject_field(
    callback: CallbackQuery,
    callback_data: SubjectAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
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


@router.message(EditSubject.name)
@needs(Role.ADMIN, step=True)
async def subject_rename(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    name = " ".join((message.text or "").split())
    if not 1 <= len(name) <= SUBJECT_NAME_MAX:
        await message.answer(f"Название от 1 до {SUBJECT_NAME_MAX} символов. Ещё раз:")
        return

    subject = await _subject_from_state(state, session, school_class)
    if subject is None:
        await state.clear()
        await message.answer("Предмет уже удалён.", reply_markup=back_to_menu())
        return

    old_name = subject.name
    try:
        moved = await subjects_service.rename(
            session, school_class.id, message.from_user.id, subject, name
        )
    except subjects_service.SubjectExists:
        await message.answer(
            f"Предмет <b>{escape(name)}</b> уже есть. Придумайте другое название:"
        )
        return
    except subjects_service.HomeworkClash as clash:
        listed = ", ".join(f"{day:%d.%m}" for day in clash.days[:5])
        await message.answer(
            f"На эти дни уже есть задания и по <b>{escape(subject.name)}</b>, и по "
            f"<b>{escape(name)}</b>: {listed}. Уберите одно из них и повторите — "
            "иначе переименование потеряется целиком.",
            reply_markup=back_to_menu(),
        )
        await state.clear()
        return
    if moved is None:
        await state.clear()
        await message.answer("Название не изменилось.", reply_markup=back_to_menu())
        return
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
) -> None:
    """The short-name and teacher steps: same shape, different column."""
    raw = " ".join((message.text or "").split())
    subject = await _subject_from_state(state, session, school_class)
    if subject is None:
        await state.clear()
        await message.answer("Предмет уже удалён.", reply_markup=back_to_menu())
        return

    value = None if raw in {"-", "—", ""} else raw[:limit]
    await subjects_service.set_detail(
        session, school_class.id, message.from_user.id, subject, field, value
    )
    await session.commit()
    await state.clear()
    await message.answer(
        mr.render_subject_card(subject), reply_markup=subject_card_keyboard(subject.id)
    )


@router.message(EditSubject.short_name)
@needs(Role.ADMIN, step=True)
async def subject_short_name(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await _save_subject_text(message, state, session, school_class, "short_name", SHORT_NAME_MAX)


@router.message(EditSubject.teacher)
@needs(Role.ADMIN, step=True)
async def subject_teacher(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await _save_subject_text(message, state, session, school_class, "teacher", TEACHER_MAX)


async def _apply_colour(
    session: AsyncSession,
    school_class: SchoolClass,
    subject: Subject,
    telegram_id: int,
    colour: str | None,
) -> None:
    await subjects_service.set_detail(
        session, school_class.id, telegram_id, subject, "color", colour
    )
    await session.commit()


@router.callback_query(SubjectAction.filter(F.action == "colour"))
@needs(Role.ADMIN)
async def subject_colour_pick(
    callback: CallbackQuery,
    callback_data: SubjectAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
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
@needs(Role.ADMIN, step=True)
async def subject_colour_typed(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
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
@needs(Role.ADMIN)
async def subject_add(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await state.set_state(EditSubject.create)
    await callback.message.edit_text(
        "Название нового предмета:", reply_markup=cancel_keyboard()
    )
    await callback.answer()


@router.message(EditSubject.create)
@needs(Role.ADMIN, step=True)
async def subject_create(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    name = " ".join((message.text or "").split())
    if not 1 <= len(name) <= SUBJECT_NAME_MAX:
        await message.answer(f"Название от 1 до {SUBJECT_NAME_MAX} символов. Ещё раз:")
        return

    # Ignores case, so «физика» opens the class's «Физика» instead of founding
    # a second row beside it.
    try:
        subject = await subjects_service.create(
            session, school_class.id, message.from_user.id, name
        )
    except subjects_service.SubjectExists as taken:
        await state.clear()
        await message.answer(
            f"Предмет <b>{escape(taken.existing.name)}</b> уже есть.",
            reply_markup=subject_card_keyboard(taken.existing.id),
        )
        return
    await session.commit()
    await state.clear()
    await message.answer(
        mr.render_subject_card(subject), reply_markup=subject_card_keyboard(subject.id)
    )


@router.callback_query(SubjectAction.filter(F.action == "delete"))
@needs(Role.ADMIN)
async def subject_delete(
    callback: CallbackQuery,
    callback_data: SubjectAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    """Deleting a subject the timetable still uses is refused.

    It used to be allowed, and it left the lessons alone: the template stores
    the name as well as the link, so the class kept its timetable and lost
    only the colour and the teacher. That stopped being true when the
    dictionary started keeping itself — the name is still in the template, so
    the next read adopts it back, stripped of its colour and its teacher, and
    the admin is left believing they deleted something.

    The order is now stated instead: out of the timetable first, out of the
    dictionary second. A subject nothing teaches still goes in one tap.
    """
    subject = await _subject_by_id(session, school_class, callback_data.value)
    if subject is None:
        await callback.answer("Предмет уже удалён", show_alert=True)
        return

    try:
        name = await subjects_service.delete(
            session, school_class.id, callback.from_user.id, subject
        )
    except subjects_service.SubjectInUse as in_use:
        await callback.answer(
            # ``plural`` carries the number itself — printing it again beside
            # the call produced «стоит в расписании: 1 1 урок».
            f"«{subject.name}» стоит в расписании: "
            f"{plural(in_use.lessons, 'урок', 'урока', 'уроков')}. "
            "Сначала уберите их из расписания.",
            show_alert=True,
        )
        return
    await session.commit()
    await state.clear()

    text, keyboard = await _subject_view(session, school_class, role)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer(f"Удалён: {name}"[:200])


@router.callback_query(SubjectAction.filter(F.action == "collect"))
@needs(Role.EDITOR)
async def subjects_collect(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    """Create a Subject row for every name the timetable already uses.

    An editor may run this: it invents nothing, it only writes down the names
    that are already in the class's timetable.
    """
    created = await subjects_service.collect(session, school_class.id, callback.from_user.id)
    if created:
        await session.commit()

    await state.clear()
    text, keyboard = await _subject_view(session, school_class, role)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer(
        f"Добавлено: {created}" if created else "Все предметы расписания уже в списке"
    )
