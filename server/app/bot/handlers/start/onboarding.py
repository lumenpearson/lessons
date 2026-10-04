"""Creating the first class: grade, letter, school, time zone.

Part of :mod:`app.bot.handlers.start`. Every step but the first is filtered by
its ``CreateClass`` state, and the last one takes a ``TimezonePick`` — the
payload ``timezone`` takes without a state — so this router is included before
that one.
"""

from __future__ import annotations

from datetime import datetime
from html import escape

from aiogram import F, Router
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.keyboards import cancel_keyboard, main_menu
from app.bot.render import cut
from app.bot.roles import is_env_owner
from app.bot.start_keyboard import (
    GradePick,
    SchoolPick,
    TimezonePick,
    school_fallback,
    school_picker,
    timezone_picker,
)
from app.bot.states import CreateClass
from app.models import DEFAULT_BELLS, BellPeriod, BellSchedule, BotUser, Role, SchoolClass
from app.providers import dadata
from app.security import new_join_code
from app.services import schools as schools_service
from app.services import terms as terms_service
from app.services.terms import TermError, compose_name, normalise_letter, validate_grade
from app.timezones import DEFAULT_TIMEZONE, is_supported, label_for

router = Router(name="start.onboarding")


@router.callback_query(CreateClass.grade, GradePick.filter())
async def create_class_grade(
    callback: CallbackQuery,
    callback_data: GradePick,
    state: FSMContext,
) -> None:
    # The payload is attacker-controlled like any other, and the keyboard is
    # the only thing that would otherwise keep it inside 1..11.
    try:
        grade = validate_grade(callback_data.grade)
    except TermError as error:
        await callback.answer(str(error), show_alert=True)
        return

    await state.update_data(grade=grade)
    await callback.message.edit_text(
        f"Класс — <b>{grade}</b>.\n\n"
        "Теперь буква: <code>А</code>, <code>Б</code>, <code>инж</code>… "
        "или отправьте <code>-</code>, если буквы нет.",
    )
    await state.set_state(CreateClass.letter)
    await callback.answer()


@router.message(CreateClass.letter)
async def create_class_letter(message: Message, state: FSMContext) -> None:
    try:
        letter = normalise_letter(message.text)
    except TermError as error:
        await message.answer(str(error))
        return

    await state.update_data(letter=letter)
    await _ask_school(message, state)


SKIP_ANSWERS = {"-", "—", ""}


SEARCH_PROMPT = (
    "Теперь школа. Напишите название или номер — «гимназия 3», "
    "«школа 197 Санкт-Петербург» — и бот поищет её в реестре.\n\n"
    "Или отправьте <code>-</code>, чтобы пропустить."
)


MANUAL_PROMPT = (
    "Введите название школы так, как оно должно стоять в карточке класса "
    "(или отправьте <code>-</code>, чтобы пропустить):"
)


async def _ask_school(message: Message, state: FSMContext) -> None:
    """The school step, in whichever form this deployment can offer.

    Without a directory key there is nothing to search, so the question
    becomes the one it has always been — type the name. Said plainly rather
    than by showing a search box that answers «не настроено» to everything.
    """
    if schools_service.available():
        await message.answer(SEARCH_PROMPT, reply_markup=cancel_keyboard())
        await state.set_state(CreateClass.school)
    else:
        await message.answer(MANUAL_PROMPT, reply_markup=cancel_keyboard())
        await state.set_state(CreateClass.school_manual)


async def _ask_timezone(message: Message, state: FSMContext) -> None:
    await message.answer(
        "В каком часовом поясе находится школа?\n\n"
        "Это влияет на то, когда приложение и виджет считают уроки идущими — "
        "выберите пояс своего города:",
        reply_markup=timezone_picker(),
    )
    await state.set_state(CreateClass.timezone)


@router.message(CreateClass.school)
async def create_class_school_search(message: Message, state: FSMContext) -> None:
    """A search query, not a name. The name arrives from a button below."""
    raw = (message.text or "").strip()
    if raw in SKIP_ANSWERS:
        await state.update_data(school=None)
        await _ask_timezone(message, state)
        return

    try:
        result = await schools_service.search(raw)
    except schools_service.SearchError as error:
        await message.answer(str(error))
        return
    except dadata.DirectoryError as error:
        # The directory is somebody else's service and this is a class being
        # created: typing the name has to stay possible on a day it is down.
        await state.update_data(school_results=[])
        await message.answer(error.message, reply_markup=school_fallback())
        return

    if not result.schools:
        await state.update_data(school_results=[])
        await message.answer(
            # Cut to what was actually searched with, and cut before escaping:
            # `raw` is a whole inbound message, which Telegram allows up to its
            # own ceiling, and the sentence around it is 95 characters more.
            f"По запросу «{escape(cut(raw, schools_service.MAX_QUERY))}» ничего не нашлось.\n\n"
            "Попробуйте номер школы и город, или введите название вручную.",
            reply_markup=school_fallback(),
        )
        return

    # The whole result set is kept, not the page: the upstream has no offset,
    # so paging is local and a page turn must not cost a second search.
    await state.update_data(
        school_results=[school.model_dump() for school in result.schools],
        school_truncated=result.truncated,
    )
    page = schools_service.page_of(result.schools, 1, truncated=result.truncated)
    await message.answer(
        _search_caption(page),
        reply_markup=school_picker(page, page_size=schools_service.PAGE_SIZE),
    )


def _search_caption(page: dadata.SchoolPage) -> str:
    head = f"Нашлось: <b>{page.total}</b>. Выберите свою школу:"
    if page.truncated:
        # Twenty is their ceiling, not the number of matches. Saying «первые
        # 20" is the only thing that tells somebody their school may be in the
        # part that never arrived, and that a longer query is the way to it.
        head = (
            "Показаны первые <b>20</b> совпадений — реестр больше за раз не "
            "отдаёт. Если своей школы нет, добавьте в запрос город или номер.\n\n"
            "Выберите школу:"
        )
    return head


def _stored_schools(data: dict) -> list[dadata.School]:
    raw = data.get("school_results") or []
    return [dadata.School(**item) for item in raw if isinstance(item, dict)]


@router.callback_query(CreateClass.school, SchoolPick.filter(F.action == "page"))
async def create_class_school_page(
    callback: CallbackQuery,
    callback_data: SchoolPick,
    state: FSMContext,
) -> None:
    data = await state.get_data()
    schools = _stored_schools(data)
    if not schools:
        await callback.answer("Поиск устарел — отправьте запрос заново", show_alert=True)
        return
    page = schools_service.page_of(
        schools, callback_data.value, truncated=bool(data.get("school_truncated"))
    )
    await callback.message.edit_text(
        _search_caption(page),
        reply_markup=school_picker(page, page_size=schools_service.PAGE_SIZE),
    )
    await callback.answer()


@router.callback_query(CreateClass.school, SchoolPick.filter(F.action == "pick"))
async def create_class_school_pick(
    callback: CallbackQuery,
    callback_data: SchoolPick,
    state: FSMContext,
) -> None:
    schools = _stored_schools(await state.get_data())
    # The payload is attacker-controlled like any other; the index is checked
    # against the list rather than trusted to be one the keyboard drew.
    if not 0 <= callback_data.value < len(schools):
        await callback.answer("Поиск устарел — отправьте запрос заново", show_alert=True)
        return

    school = schools[callback_data.value]
    await state.update_data(
        school=schools_service.stored_name(school),
        school_results=[],
    )
    await callback.message.edit_text(f"Школа: <b>{escape(school.name)}</b>")
    await _ask_timezone(callback.message, state)
    await callback.answer()


@router.callback_query(CreateClass.school, SchoolPick.filter(F.action == "manual"))
async def create_class_school_manual(callback: CallbackQuery, state: FSMContext) -> None:
    await state.update_data(school_results=[])
    await callback.message.edit_text(MANUAL_PROMPT)
    await state.set_state(CreateClass.school_manual)
    await callback.answer()


@router.callback_query(CreateClass.school, SchoolPick.filter(F.action == "skip"))
async def create_class_school_skip(callback: CallbackQuery, state: FSMContext) -> None:
    await state.update_data(school=None, school_results=[])
    await callback.message.edit_text("Школа не указана.")
    await _ask_timezone(callback.message, state)
    await callback.answer()


@router.message(CreateClass.school_manual)
async def create_class_school_typed(message: Message, state: FSMContext) -> None:
    school = (message.text or "").strip()
    await state.update_data(
        school=None if school in SKIP_ANSWERS else school[: schools_service.MAX_NAME]
    )
    await _ask_timezone(message, state)


@router.callback_query(CreateClass.timezone, TimezonePick.filter())
async def create_class_timezone(
    callback: CallbackQuery,
    callback_data: TimezonePick,
    state: FSMContext,
    session: AsyncSession,
) -> None:
    # FSM state is per-user and therefore attacker-controlled; creating a class
    # hands the caller OWNER of it, so the environment owner list is re-checked
    # here rather than trusted from the step that set the state.
    if not is_env_owner(callback.from_user.id):
        await state.clear()
        await callback.answer("Только для владельца", show_alert=True)
        return

    data = await state.get_data()
    if "grade" not in data:
        await state.clear()
        await callback.answer("Начните сначала: /start", show_alert=True)
        return
    zone = callback_data.zone if is_supported(callback_data.zone) else DEFAULT_TIMEZONE

    bells = BellSchedule(class_id=0, name="Обычное")
    grade = data["grade"]
    letter = data.get("letter")
    school_class = SchoolClass(
        name=compose_name(grade, letter, fallback=str(grade)),
        grade=grade,
        letter=letter,
        school=data.get("school"),
        timezone=zone,
        join_code=new_join_code(),
    )
    session.add(school_class)
    await session.flush()

    bells.class_id = school_class.id
    session.add(bells)
    await session.flush()

    for index, starts_at, ends_at in DEFAULT_BELLS:
        session.add(
            BellPeriod(schedule_id=bells.id, index=index, starts_at=starts_at, ends_at=ends_at)
        )

    school_class.bell_schedule_id = bells.id
    # Seeded here rather than lazily on first read so the class has terms
    # from the moment it exists — the scheme follows the grade that was just
    # picked, and every date is editable afterwards.
    await terms_service.ensure(
        session, school_class, terms_service.opening_year_of(datetime.now(school_class.tz).date())
    )
    session.add(
        BotUser(
            telegram_id=callback.from_user.id,
            class_id=school_class.id,
            role=Role.OWNER,
            username=callback.from_user.username,
            full_name=callback.from_user.full_name,
        )
    )
    await session.commit()
    await state.clear()

    await callback.message.edit_text(
        f"✅ Класс <b>{escape(school_class.name)}</b> создан.\n"
        f"Часовой пояс: {label_for(zone)}\n\n"
        f"Код для приложения: <code>{school_class.join_code}</code>\n"
        "Введите его в приложении на телефоне, чтобы подключить расписание.\n\n"
        "Дальше стоит заполнить расписание уроков в разделе «Расписание».",
        reply_markup=main_menu(Role.OWNER, school_class.diary_provider),
    )
    await callback.answer()
