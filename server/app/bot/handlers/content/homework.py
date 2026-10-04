"""«📝 Домашнее задание»: setting it, and the digest it lands in.

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

from app.bot.content_keyboard import HomeworkAction
from app.bot.handlers.calendar import open_month
from app.bot.handlers.content._common import BAD_DATE, _date_or_none, _index_or_none, _today
from app.bot.handlers.tasks import homework_view
from app.bot.keyboards import Menu, back_to_menu, cancel_keyboard
from app.bot.render import clamp, human_date, relative_day_name
from app.bot.states import AddHomework
from app.models import Role, SchoolClass
from app.schedule import ScheduleResolver
from app.services import audit, notify
from app.services import homework as homework_service

router = Router(name="content.homework")


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
