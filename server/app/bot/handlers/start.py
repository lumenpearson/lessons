"""Onboarding, the main menu and day browsing."""

from __future__ import annotations

from datetime import datetime, timedelta
from html import escape

from aiogram import F, Router
from aiogram.filters import Command, CommandObject, CommandStart
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message, ReplyKeyboardRemove
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.keyboards import (
    ClassAction,
    DayNav,
    GradePick,
    Menu,
    SchoolPick,
    TimezonePick,
    back_to_menu,
    cancel_keyboard,
    day_nav,
    grade_picker,
    main_menu,
    request_contact,
    school_fallback,
    school_picker,
    timezone_picker,
)
from app.bot.render import plural, render_day, render_role_help
from app.bot.roles import claim_phone_invites, get_role, is_env_owner
from app.bot.states import CreateClass
from app.config import get_settings
from app.models import (
    DEFAULT_BELLS,
    BellPeriod,
    BellSchedule,
    BotUser,
    JoinMode,
    Role,
    SchoolClass,
)
from app.providers import dadata
from app.schedule import ScheduleResolver
from app.security import new_join_code
from app.services import device_invites, linking
from app.services import schools as schools_service
from app.services import terms as terms_service
from app.services.terms import TermError, compose_name, normalise_letter, validate_grade
from app.timezones import DEFAULT_TIMEZONE, is_supported, label_for

router = Router(name="start")

WELCOME_UNKNOWN = (
    "👋 Это бот управления школьным дневником.\n\n"
    "Вас пока нет в списке доступа. Если вам выдали приглашение по номеру "
    "телефона, нажмите кнопку ниже — бот сверит номер и выдаст доступ."
)


def _today(school_class: SchoolClass | None = None) -> datetime:
    """Now, in the class's own zone rather than the server's."""
    tz = school_class.tz if school_class is not None else get_settings().tz
    return datetime.now(tz)


async def _send_menu(message: Message, school_class: SchoolClass, role: Role) -> None:
    await message.answer(
        f"<b>{escape(school_class.name)}</b>"
        + (f" · {escape(school_class.school)}" if school_class.school else "")
        + f"\nВаша роль: <b>{role.title_ru}</b>. {render_role_help(role)}",
        reply_markup=main_menu(role, school_class.diary_provider),
    )


#: Link codes are six characters; anything longer is not one and is not
#: worth a database round trip.
LINK_CODE_MAX = 16


@router.message(CommandStart(deep_link=True, magic=F.args.startswith("link_")))
async def cmd_start_link(
    message: Message,
    command: CommandObject,
    state: FSMContext,
    session: AsyncSession,
) -> None:
    """``t.me/<bot>?start=link_<code>`` - the QR / button on the app's link screen.

    No role check: attaching a phone to *this* account is what the link does,
    and the phone then acts with whatever role this account holds in the
    device's class - possibly none, in which case it stays read-only. The
    reply says which, so the person knows what to ask an admin for.
    """
    await state.clear()
    code = (command.args or "")[len("link_"):].strip()
    device = (
        await linking.link_device(session, code, message.from_user.id)
        if 0 < len(code) <= LINK_CODE_MAX
        else None
    )
    if device is None:
        await message.answer(
            "Код не подошёл. Он действует один раз - откройте экран привязки в "
            "приложении ещё раз и отсканируйте новый."
        )
        return

    school_class = await session.get(SchoolClass, device.class_id)
    role = await get_role(session, message.from_user.id, device.class_id)
    name = escape(device.device_name or "Телефон")
    class_name = escape(school_class.name) if school_class is not None else "класс"
    if role is None:
        access = (
            "У вас пока нет роли в этом классе, поэтому телефон только читает "
            "расписание. Попросите администратора выдать доступ."
        )
    else:
        access = f"Телефон действует с вашей ролью: <b>{role.title_ru}</b>."
    await message.answer(
        f"📱 Устройство <b>{name}</b> привязано к вашему аккаунту "
        f"(класс <b>{class_name}</b>).\n{access}"
    )


@router.message(CommandStart())
async def cmd_start(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    await state.clear()

    if school_class is None:
        if is_env_owner(message.from_user.id):
            await message.answer(
                "👋 Классов ещё нет. Давайте создадим первый.\n\n"
                "Какой это класс?",
                reply_markup=grade_picker(),
            )
            await state.set_state(CreateClass.grade)
            return
        await message.answer(WELCOME_UNKNOWN, reply_markup=request_contact())
        return

    if role is None:
        await message.answer(WELCOME_UNKNOWN, reply_markup=request_contact())
        return

    await _send_menu(message, school_class, role)


@router.message(F.contact)
async def on_contact(
    message: Message,
    session: AsyncSession,
) -> None:
    """Apply any invite matching the shared phone number.

    Telegram only lets a user share *their own* contact through this button, so
    a match here is proof of ownership of the number.
    """
    contact = message.contact
    if contact.user_id != message.from_user.id:
        await message.answer(
            "Это чужой контакт. Нажмите кнопку «Поделиться номером», чтобы "
            "отправить свой.",
            reply_markup=request_contact(),
        )
        return

    granted = await claim_phone_invites(
        session,
        telegram_id=message.from_user.id,
        phone=contact.phone_number,
        username=message.from_user.username,
        full_name=message.from_user.full_name,
    )

    if not granted:
        await message.answer(
            "Приглашений для этого номера не найдено. Попросите администратора "
            "добавить ваш номер.",
            reply_markup=ReplyKeyboardRemove(),
        )
        return

    school_class, role = granted[0]
    await message.answer(
        f"✅ Доступ выдан: <b>{escape(school_class.name)}</b>, роль <b>{role.title_ru}</b>.",
        reply_markup=ReplyKeyboardRemove(),
    )
    await _send_menu(message, school_class, role)


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
            f"По запросу «{escape(raw)}» ничего не нашлось.\n\n"
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
        # Twenty is their ceiling, not the number of matches. Saying "первые
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
    # Seeded here rather than lazily on first read so the class has четверти
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


@router.callback_query(Menu.filter(F.action == "root"))
async def back_root(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    await state.clear()
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    await callback.message.edit_text(
        f"<b>{escape(school_class.name)}</b>\nВаша роль: <b>{role.title_ru}</b>.",
        reply_markup=main_menu(role, school_class.diary_provider),
    )
    await callback.answer()


@router.callback_query(DayNav.filter())
async def show_day(
    callback: CallbackQuery,
    callback_data: DayNav,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return

    today = _today(school_class).date()
    target = today + timedelta(days=callback_data.offset)
    resolver = ScheduleResolver(session, school_class)
    days = await resolver.resolve_range(target, 1)

    await callback.message.edit_text(
        render_day(days[0], today),
        reply_markup=day_nav(callback_data.offset),
    )
    await callback.answer()


@router.message(Command("today"))
async def cmd_today(
    message: Message,
    session: AsyncSession,
    school_class: SchoolClass | None,
) -> None:
    if school_class is None:
        await message.answer(WELCOME_UNKNOWN, reply_markup=request_contact())
        return
    today = _today(school_class).date()
    days = await ScheduleResolver(session, school_class).resolve_range(today, 1)
    await message.answer(render_day(days[0], today), reply_markup=day_nav(0))


@router.message(Command("tomorrow"))
async def cmd_tomorrow(
    message: Message,
    session: AsyncSession,
    school_class: SchoolClass | None,
) -> None:
    if school_class is None:
        await message.answer(WELCOME_UNKNOWN, reply_markup=request_contact())
        return
    today = _today(school_class).date()
    days = await ScheduleResolver(session, school_class).resolve_range(
        today + timedelta(days=1), 1
    )
    await message.answer(render_day(days[0], today), reply_markup=day_nav(1))


@router.message(Command("code"))
async def cmd_code(
    message: Message,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Show the join code the Android app needs.

    Still shown while the class is on invitations, because it is not gone —
    it is dormant, and the switch back makes it work again. What changes is
    the sentence under it: «введите его в приложении» about a code the app
    now refuses would send an admin to look for the fault in the app.
    """
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await message.answer("Команда доступна администраторам класса.")
        return
    if school_class.join_mode is JoinMode.INVITE:
        tail = (
            "Сейчас он ничего не открывает: класс подключает телефоны только "
            "по личным приглашениям. Личный код на один телефон берут в меню "
            "/start — кнопка «📱 Подключить телефон»; она есть у каждого, кто "
            "в классе.\n\n"
            "Вернуть вход по коду можно в разделе «👥 Доступ»."
        )
    else:
        tail = (
            "Его вводят в приложении при первом запуске. "
            "Код даёт только чтение расписания."
        )
    await message.answer(
        f"Код класса <b>{escape(school_class.name)}</b>: "
        f"<code>{escape(school_class.join_code)}</code>\n\n" + tail,
    )


@router.callback_query(ClassAction.filter(F.action == "rotate_code"))
async def rotate_code(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Rotating the code does not revoke devices that already joined —
    it only stops the old code from being used again."""
    if school_class is None or role is None or not role.at_least(Role.OWNER):
        await callback.answer("Только для владельца", show_alert=True)
        return

    school_class.join_code = new_join_code()
    await session.commit()
    await callback.message.edit_text(
        f"Новый код класса: <code>{escape(school_class.join_code)}</code>\n\n"
        "Уже подключённые устройства продолжат работать.",
        reply_markup=back_to_menu(),
    )
    await callback.answer("Код обновлён")


@router.callback_query(Menu.filter(F.action == "phone"))
async def phone_code(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """A code for the presser's own phone, in either join mode.

    No role check beyond membership, for the same reason /link has none: the
    phone that redeems this acts with whatever role this account holds, so the
    code can never hand out more than the person already has. A наблюдатель
    minting one gets a наблюдатель's phone.
    """
    if school_class is None or role is None:
        await callback.answer("Нет доступа", show_alert=True)
        return

    code = await device_invites.mint(
        session, telegram_id=callback.from_user.id, class_id=school_class.id
    )
    minutes = plural(device_invites.CODE_MINUTES, "минуту", "минуты", "минут")
    await callback.message.edit_text(
        "📱 <b>Подключить телефон</b>\n\n"
        f"Ваш код: <code>{code}</code>\n\n"
        f"Введите его в приложении при первом запуске. Код действует {minutes} "
        "и годится для одного телефона — для второго нажмите кнопку ещё раз.\n\n"
        "Телефон, который его введёт, сразу станет вашим: он будет работать с "
        f"вашей ролью (<b>{role.title_ru}</b>), и отдельно присылать код "
        "привязки не нужно.",
        reply_markup=back_to_menu(),
    )
    await callback.answer()


HELP_SECTIONS: list[tuple[Role | None, str, list[str]]] = [
    (
        None,
        "Расписание",
        [
            "/today — сегодня",
            "/tomorrow — завтра",
            "/day — календарь: любой день и всё, что в нём",
            "/week — неделя",
            "/next — что дальше: урок, перемена, сколько осталось",
            "/homework — домашнее задание с отметками «сделал»",
            "/find — поиск по домашним заданиям",
        ],
    ),
    (
        None,
        "Личное",
        [
            "/tasks — мои задачи",
            "/task <i>текст</i> — добавить задачу одной строкой",
            "/remind — напоминания и сводки",
            "/calendar — подписка на календарь",
            "/link — привязать телефон к аккаунту",
            "/request — запросить доступ повыше",
        ],
    ),
    (
        Role.ADMIN,
        "Администрирование",
        [
            "/subjects — предметы и учителя",
            "/holidays — каникулы и особые дни",
            "/bells — расписание звонков",
            "/devices — подключённые устройства",
            "/log — журнал изменений",
            "/class — настройки класса",
            "/export — выгрузить расписание текстом",
            "/import — загрузить расписание текстом",
            "/stats — статистика класса",
            "/code — код класса для приложения",
        ],
    ),
]


@router.message(Command("help"))
async def cmd_help(message: Message, role: Role | None) -> None:
    """Grouped by what the caller may do: a viewer is not shown admin commands
    that would only answer with a refusal."""
    lines = ["<b>Команды</b>", "/start — главное меню", "/help — эта справка"]
    for minimum, title, items in HELP_SECTIONS:
        if minimum is not None and (role is None or not role.at_least(minimum)):
            continue
        lines.append("")
        lines.append(f"<b>{title}</b>")
        lines.extend(items)
    if role is not None and role.at_least(Role.EDITOR):
        lines.append("")
        lines.append("Замены, события и домашнее задание добавляются из меню /start.")
    await message.answer("\n".join(lines))


@router.callback_query(ClassAction.filter(F.action == "timezone"))
async def change_timezone_prompt(
    callback: CallbackQuery,
    state: FSMContext,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return

    # An abandoned "create class" flow would otherwise swallow the zone button.
    await state.clear()

    await callback.message.edit_text(
        f"Текущий пояс: <b>{label_for(school_class.timezone_name)}</b>\n\n"
        "Выберите новый:",
        reply_markup=timezone_picker(),
    )
    await callback.answer()


@router.callback_query(TimezonePick.filter())
async def change_timezone_apply(
    callback: CallbackQuery,
    callback_data: TimezonePick,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Changing the zone does not move any stored time.

    Bells are wall time, so 08:30 stays 08:30 — what changes is which instant
    the app and the widget consider "now" for this class.
    """
    if school_class is None or role is None or not role.at_least(Role.ADMIN):
        await callback.answer("Только для администраторов", show_alert=True)
        return
    if not is_supported(callback_data.zone):
        await callback.answer("Неизвестный часовой пояс", show_alert=True)
        return

    school_class.timezone = callback_data.zone
    await session.commit()

    await callback.message.edit_text(
        f"✅ Часовой пояс класса: <b>{label_for(callback_data.zone)}</b>\n\n"
        "Время звонков не изменилось — изменилось только то, по какому времени "
        "считается «сейчас».",
        reply_markup=back_to_menu(),
    )
    await callback.answer()
