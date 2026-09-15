"""The interactive timetable editor — one message you stay inside.

The paste editor in ``timetable.py`` is still the way a term's schedule gets
entered, and this does not replace it. It replaces the *other* thing that
editor was being used for: changing one lesson. Doing that by paste means
retyping the day, and a typo in a line you were not touching is a lesson
silently lost — while getting to the next day meant leaving, picking Вторник,
and reading it back from the start.

So: one message, edited in place. The cursor — which day, which lesson,
whether the breaks show — lives in the callback payload rather than in FSM
state, because FSM state is per-user and stored in the database, and a cursor
that lives in the payload keeps working on a message left open across a
redeploy. FSM is used for exactly one thing here, the two prompts that need
typed text (a new lesson, a renamed one), and it carries the cursor with it so
the answer comes back to the screen it was asked from.
"""

from __future__ import annotations

from aiogram import F, Router
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.editor_keyboard import (
    BREAKS,
    EditorAction,
    EditorSubject,
    canteen_keyboard,
    day_keyboard,
    slot_keyboard,
    subject_picker,
)
from app.bot.editor_render import render_canteen, render_day, render_slot, slots
from app.bot.keyboards import WEEKDAY_FULL, Menu, cancel_keyboard
from app.bot.render import plural
from app.bot.states import EditorLesson
from app.models import (
    BellPeriod,
    BellSchedule,
    Role,
    SchoolClass,
    Subject,
    TimetableEntry,
    WeekParity,
)
from app.services import audit, subjects, timetable_edit, timetable_io

router = Router(name="editor")

#: What the editor needs before it will draw anything. Reading a template is a
#: viewer's business — «какой третий урок в среду» is a question anybody in the
#: class may ask — but every button that changes one is checked again here
#: rather than only at the entry point: the payload that arrived came from the
#: presser's own client, and re-checking costs one comparison.
READ_MINIMUM = Role.VIEWER
EDIT_MINIMUM = Role.ADMIN


async def _entries(session: AsyncSession, class_id: int, weekday: int) -> list[TimetableEntry]:
    return list(
        await session.scalars(
            select(TimetableEntry)
            .where(TimetableEntry.class_id == class_id, TimetableEntry.weekday == weekday)
            .order_by(TimetableEntry.index)
        )
    )


async def _bells(
    session: AsyncSession, school_class: SchoolClass
) -> tuple[BellSchedule | None, dict[int, BellPeriod]]:
    """The class's default schedule and its rows by lesson number.

    A class with no default schedule is normal — a fresh class has none until
    someone pastes звонки — so this answers with an empty map rather than
    refusing, and the views drop their times and their breaks.
    """
    if not school_class.bell_schedule_id:
        return None, {}
    schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
    if schedule is None:
        return None, {}
    return schedule, {period.index: period for period in schedule.periods}


async def _draw_day(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass,
    day: int,
    flags: int,
    role: Role,
) -> None:
    entries = await _entries(session, school_class.id, day)
    schedule, periods = await _bells(session, school_class)
    counts = await timetable_edit.day_counts(session, school_class.id)
    await callback.message.edit_text(
        render_day(
            day,
            entries,
            periods,
            canteen_after=schedule.canteen_after_index if schedule else None,
            show_breaks=bool(flags & BREAKS),
            counts=counts,
        ),
        reply_markup=day_keyboard(
            day, flags, entries, can_edit=role.at_least(EDIT_MINIMUM)
        ),
    )


def _guard(
    school_class: SchoolClass | None, role: Role | None, minimum: Role
) -> str | None:
    if school_class is None or role is None:
        return "Сначала присоединитесь к классу"
    if not role.at_least(minimum):
        return "Только для администраторов"
    return None


# --------------------------------------------------------------------------
# Reading
# --------------------------------------------------------------------------


@router.callback_query(Menu.filter(F.action == "editor"))
async def editor_open(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    refusal = _guard(school_class, role, READ_MINIMUM)
    if refusal:
        await callback.answer(refusal, show_alert=True)
        return
    await _draw_day(callback, session, school_class, 1, 0, role)
    await callback.answer()


@router.callback_query(EditorAction.filter(F.action.in_({"day", "breaks"})))
async def editor_day(
    callback: CallbackQuery,
    callback_data: EditorAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    refusal = _guard(school_class, role, READ_MINIMUM)
    if refusal:
        await callback.answer(refusal, show_alert=True)
        return
    await _draw_day(
        callback, session, school_class, callback_data.day, callback_data.flags, role
    )
    await callback.answer()


@router.callback_query(EditorAction.filter(F.action == "slot"))
async def editor_slot(
    callback: CallbackQuery,
    callback_data: EditorAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    refusal = _guard(school_class, role, READ_MINIMUM)
    if refusal:
        await callback.answer(refusal, show_alert=True)
        return

    entries = await _entries(session, school_class.id, callback_data.day)
    group = dict(slots(entries)).get(callback_data.index)
    if group is None:
        # The day changed under a message somebody left open. Redraw it rather
        # than answering an error about a lesson that is genuinely gone.
        await _draw_day(
            callback, session, school_class, callback_data.day, callback_data.flags, role
        )
        await callback.answer("Урока уже нет")
        return

    _, periods = await _bells(session, school_class)
    if not role.at_least(EDIT_MINIMUM):
        await callback.answer(
            render_slot(callback_data.index, group, periods.get(callback_data.index)),
            show_alert=True,
        )
        return

    await callback.message.edit_text(
        render_slot(callback_data.index, group, periods.get(callback_data.index)),
        reply_markup=slot_keyboard(
            callback_data.day, callback_data.index, callback_data.flags, group
        ),
    )
    await callback.answer()


# --------------------------------------------------------------------------
# Writing
# --------------------------------------------------------------------------


@router.callback_query(EditorAction.filter(F.action == "move"))
async def editor_move(
    callback: CallbackQuery,
    callback_data: EditorAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    refusal = _guard(school_class, role, EDIT_MINIMUM)
    if refusal:
        await callback.answer(refusal, show_alert=True)
        return

    up = callback_data.arg == 0
    landed = await timetable_edit.move_lesson(
        session, school_class.id, callback_data.day, callback_data.index, up=up
    )
    if landed is None:
        await callback.answer("Уже первый" if up else "Уже последний")
        return

    await audit.record(
        session,
        school_class.id,
        callback.from_user.id,
        "timetable.move",
        f"{WEEKDAY_FULL[callback_data.day - 1]}: урок {callback_data.index} → {landed}",
    )
    await session.commit()
    await _draw_day(
        callback, session, school_class, callback_data.day, callback_data.flags, role
    )
    await callback.answer()


@router.callback_query(EditorAction.filter(F.action == "drop"))
async def editor_drop(
    callback: CallbackQuery,
    callback_data: EditorAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    refusal = _guard(school_class, role, EDIT_MINIMUM)
    if refusal:
        await callback.answer(refusal, show_alert=True)
        return

    removed = await timetable_edit.remove_lesson(
        session, school_class.id, callback_data.day, callback_data.index
    )
    if removed:
        await audit.record(
            session,
            school_class.id,
            callback.from_user.id,
            "timetable.remove",
            f"{WEEKDAY_FULL[callback_data.day - 1]}: удалён урок {callback_data.index}",
        )
        await session.commit()
    await _draw_day(
        callback, session, school_class, callback_data.day, callback_data.flags, role
    )
    await callback.answer("Удалён" if removed else "Урока уже нет")


@router.callback_query(EditorAction.filter(F.action.in_({"split", "merge"})))
async def editor_parity(
    callback: CallbackQuery,
    callback_data: EditorAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    refusal = _guard(school_class, role, EDIT_MINIMUM)
    if refusal:
        await callback.answer(refusal, show_alert=True)
        return

    if callback_data.action == "split":
        changed = await timetable_edit.split_parity(
            session, school_class.id, callback_data.day, callback_data.index
        )
        note = "разделён по неделям"
    else:
        keep = WeekParity.EVEN if callback_data.arg else WeekParity.ODD
        changed = await timetable_edit.merge_parity(
            session, school_class.id, callback_data.day, callback_data.index, keep
        )
        note = "сведён в «каждую неделю»"

    if changed:
        await audit.record(
            session,
            school_class.id,
            callback.from_user.id,
            "timetable.parity",
            f"{WEEKDAY_FULL[callback_data.day - 1]}: урок {callback_data.index} {note}",
        )
        await session.commit()
    await _draw_day(
        callback, session, school_class, callback_data.day, callback_data.flags, role
    )
    await callback.answer()


@router.callback_query(EditorAction.filter(F.action == "canteen"))
async def editor_canteen(
    callback: CallbackQuery,
    callback_data: EditorAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    refusal = _guard(school_class, role, EDIT_MINIMUM)
    if refusal:
        await callback.answer(refusal, show_alert=True)
        return

    schedule, periods = await _bells(session, school_class)
    if schedule is None or len(periods) < 2:
        await callback.answer(
            "Сначала задайте звонки — столовая стоит на перемене, а перемен пока нет.",
            show_alert=True,
        )
        return

    await callback.message.edit_text(
        render_canteen(schedule.canteen_after_index, periods),
        reply_markup=canteen_keyboard(
            callback_data.day, callback_data.flags, periods, schedule.canteen_after_index
        ),
    )
    await callback.answer()


@router.callback_query(EditorAction.filter(F.action == "eat"))
async def editor_set_canteen(
    callback: CallbackQuery,
    callback_data: EditorAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    refusal = _guard(school_class, role, EDIT_MINIMUM)
    if refusal:
        await callback.answer(refusal, show_alert=True)
        return

    schedule, _ = await _bells(session, school_class)
    if schedule is None:
        await callback.answer("Звонков нет", show_alert=True)
        return

    # index 0 is «не отмечать» — the payload's own «no lesson selected».
    schedule.canteen_after_index = callback_data.index or None
    where = f"после {callback_data.index}" if callback_data.index else "не отмечена"
    await audit.record(
        session,
        school_class.id,
        callback.from_user.id,
        "bells.canteen",
        f"столовая: {where}",
    )
    await session.commit()
    await _draw_day(
        callback, session, school_class, callback_data.day, callback_data.flags, role
    )
    await callback.answer("Отмечено" if callback_data.index else "Снято")


# --------------------------------------------------------------------------
# The two prompts that need typed text
# --------------------------------------------------------------------------


@router.callback_query(EditorAction.filter(F.action.in_({"add", "edit"})))
async def editor_ask_lesson(
    callback: CallbackQuery,
    callback_data: EditorAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    refusal = _guard(school_class, role, EDIT_MINIMUM)
    if refusal:
        await callback.answer(refusal, show_alert=True)
        return

    # The cursor rides along in FSM data so the typed answer comes back to the
    # screen it was asked from, on the day it was asked about.
    await state.update_data(
        day=callback_data.day,
        index=callback_data.index,
        flags=callback_data.flags,
        parity=callback_data.arg,
        mode=callback_data.action,
    )
    await state.set_state(EditorLesson.text)

    # The class's own subjects, offered as buttons. Drawn from the dictionary
    # after it has adopted whatever the timetable already uses, so the list is
    # never empty for a class that visibly teaches something — that emptiness
    # was the whole complaint: «расписание не зависит от списка предметов».
    await subjects.sync_from_timetable(session, school_class.id)
    known = list(
        await session.scalars(
            select(Subject).where(Subject.class_id == school_class.id).order_by(Subject.name)
        )
    )

    what = "Новый урок" if callback_data.action == "add" else f"Урок {callback_data.index}"
    await callback.message.edit_text(
        f"<b>{what} · {WEEKDAY_FULL[callback_data.day - 1]}</b>\n\n"
        + ("Выберите предмет кнопкой или пришлите" if known else "Пришлите предмет")
        + " текстом — можно с кабинетом и учителем через запятую:\n"
        "<code>Физика, 305, Петров П.П.</code>",
        reply_markup=(
            subject_picker(known, callback_data.day, callback_data.flags)
            if known
            else cancel_keyboard()
        ),
    )
    await callback.answer()


@router.callback_query(EditorLesson.text, EditorSubject.filter())
async def editor_pick_subject(
    callback: CallbackQuery,
    callback_data: EditorSubject,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """A subject chosen off the list instead of typed.

    No room and no teacher: the button carries a subject and nothing else, and
    the subject's own teacher now reaches the lesson through the dictionary
    anyway. A room is a per-slot fact and is added by editing the lesson.
    """
    refusal = _guard(school_class, role, EDIT_MINIMUM)
    if refusal:
        await callback.answer(refusal, show_alert=True)
        return

    # Re-scoped by the query, like every other id on this surface: the payload
    # is the user's to forge, and a subject id belonging to another class must
    # find nothing rather than be written into this one's timetable.
    subject = await session.scalar(
        select(Subject).where(
            Subject.id == callback_data.subject,
            Subject.class_id == school_class.id,
        )
    )
    if subject is None:
        await callback.answer("Предмет не найден — начните сначала", show_alert=True)
        return

    await _write_lesson(
        callback.message,
        state,
        session,
        school_class,
        subject=subject.name,
        room=None,
        teacher=None,
        who=callback.from_user.id,
        edit_in_place=True,
    )
    await callback.answer()


@router.message(EditorLesson.text)
async def editor_take_lesson(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if _guard(school_class, role, EDIT_MINIMUM):
        await state.clear()
        return

    # The same grammar the paste editor speaks, minus the leading number: the
    # button already knows which lesson this is, and one parser means a typed
    # «Физика, 305» means here exactly what it means in a week paste.
    subject, room, teacher = timetable_io.split_lesson_body(message.text or "")
    if not subject:
        await message.answer("Не понял предмет. Пришлите ещё раз:")
        return

    await _write_lesson(
        message,
        state,
        session,
        school_class,
        subject=subject,
        room=room,
        teacher=teacher,
        who=message.from_user.id,
        edit_in_place=False,
    )


async def _write_lesson(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    *,
    subject: str,
    room: str | None,
    teacher: str | None,
    who: int,
    edit_in_place: bool,
) -> None:
    """Put one lesson in the template and redraw the day.

    Shared by the two ways of answering the same question — a typed line and a
    tapped subject — because they differ in exactly two things: where the
    subject came from, and whether the day is redrawn as a new message or in
    place of the picker. Everything between (the cursor, the parity rule, the
    audit line, the transaction) is one thing, and two copies of it would stop
    being one within a month.
    """
    data = await state.get_data()
    day, index = int(data["day"]), int(data["index"])
    flags, mode = int(data["flags"]), data["mode"]
    parity = WeekParity.EVEN if data.get("parity") else WeekParity.ODD

    if mode == "add":
        landed = await timetable_edit.add_lesson(
            session, school_class.id, day, subject=subject, room=room, teacher=teacher
        )
        if landed is None:
            await state.clear()
            # Say which ceiling was hit. Almost always it is the bells, and
            # «слишком много уроков» sent an admin looking for a limit on the
            # timetable when what they needed was one more row in «🔔 Звонки».
            rings = await timetable_edit.rings(session, school_class.id)
            await message.answer(
                f"В расписании звонков {rings} "
                f"{plural(rings, 'урок', 'урока', 'уроков')}, и все заняты.\n\n"
                "Добавьте звонок в «🔔 Звонки» — урок без своего звонка "
                "не показывается ни в приложении, ни в виджете."
                if rings
                else "В этом дне уже слишком много уроков."
            )
            return
        action, note = "timetable.add", f"добавлен урок {landed}: {subject}"
    else:
        entries = await _entries(session, school_class.id, day)
        group = dict(slots(entries)).get(index, [])
        # A slot that was never split has one row and it is «каждую неделю»;
        # the parity the button carried is meaningless there and would create
        # a second row beside it.
        target = group[0].parity if len(group) == 1 else parity
        await timetable_edit.edit_lesson(
            session,
            school_class.id,
            day,
            index,
            target,
            subject=subject,
            room=room,
            teacher=teacher,
        )
        action, note = "timetable.edit", f"урок {index}: {subject}"

    await audit.record(
        session,
        school_class.id,
        who,
        action,
        f"{WEEKDAY_FULL[day - 1]}: {note}",
    )
    await session.commit()
    await state.clear()

    entries = await _entries(session, school_class.id, day)
    schedule, periods = await _bells(session, school_class)
    counts = await timetable_edit.day_counts(session, school_class.id)
    text = render_day(
        day,
        entries,
        periods,
        canteen_after=schedule.canteen_after_index if schedule else None,
        show_breaks=bool(flags & BREAKS),
        counts=counts,
    )
    keyboard = day_keyboard(day, flags, entries, can_edit=True)
    # A typed answer is a new message and the day follows it; a tapped one
    # replaces the picker, so that choosing a subject does not leave a dead
    # keyboard above the day it just changed.
    if edit_in_place:
        await message.edit_text(text, reply_markup=keyboard)
    else:
        await message.answer(text, reply_markup=keyboard)
