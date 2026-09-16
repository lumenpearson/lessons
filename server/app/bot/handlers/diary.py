"""The personal diary, inside the class chat.

The class's own data — timetable, homework, замены — belongs to the class and
everybody in it sees the same thing. The diary is the opposite and the code has
to keep it that way: it is one family's record of one child, and the only
person who ever sees it here is the one who signed in. An admin binding the
class to dnevnik2 gives the class nothing; it offers every member a door to
their *own* account.

That is why every lookup below starts from ``callback.from_user.id`` and never
from the class. There is no screen, no button and no payload in this file that
can address somebody else's session — a session is found by (telegram_id,
class_id) and nothing else, so a crafted payload reaches the presser's own
diary or nothing at all.
"""

from __future__ import annotations

import logging
from datetime import date as Date
from datetime import datetime, timedelta

from aiogram import F, Router
from aiogram.types import CallbackQuery
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.diary_keyboard import (
    MAX_OFFSET,
    DiaryAction,
    diary_keyboard,
    sign_in_keyboard,
    signed_out_keyboard,
    student_picker,
)
from app.bot.diary_render import (
    render_day,
    render_homework,
    render_marks,
    render_signed_out,
    render_week,
)
from app.bot.keyboards import Menu, shift_days, shift_weeks
from app.config import get_settings
from app.crypto import diary_enabled
from app.models import DiarySession, Role, SchoolClass
from app.providers.petersburg import PetersburgError, SessionExpired, UpstreamUnavailable
from app.services import diary as diary_service
from app.services import diary_link

log = logging.getLogger(__name__)

router = Router(name="diary")

#: The provider a class may be bound to. One today; the column is a string so
#: that a second one does not rename it.
PETERSBURG = "petersburg"

#: How many days of homework a «Задания» screen asks for.
HOMEWORK_DAYS = 14


async def _session_for(
    session: AsyncSession, telegram_id: int, class_id: int
) -> DiarySession | None:
    """This person's live diary session in this class, or ``None``.

    Keyed on both, always. On telegram_id alone, a parent in two classes would
    read one child's diary from the other class's screen; on class_id alone
    there would be no diary in this bot worth having.
    """
    row = await session.scalar(
        select(DiarySession)
        .where(
            DiarySession.telegram_id == telegram_id,
            DiarySession.class_id == class_id,
            DiarySession.expired_at.is_(None),
        )
        .order_by(DiarySession.id.desc())
    )
    if row is None:
        return None
    if diary_service.upstream_of(row) is None:
        row.expired_at = diary_service.utcnow()
        await session.commit()
        return None
    return row


def _bound(school_class: SchoolClass) -> bool:
    return school_class.diary_provider == PETERSBURG


async def _offer_sign_in(callback: CallbackQuery) -> None:
    await callback.message.edit_text(
        render_signed_out(),
        reply_markup=signed_out_keyboard(can_sign_in=diary_enabled()),
        disable_web_page_preview=True,
    )


@router.callback_query(Menu.filter(F.action == "diary"))
async def diary_root(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    if school_class is None or role is None:
        await callback.answer("Сначала присоединитесь к классу", show_alert=True)
        return
    if not _bound(school_class):
        await callback.answer(
            "Класс не привязан к электронному дневнику. "
            "Это делает администратор в «⚙️ Класс».",
            show_alert=True,
        )
        return
    if not diary_enabled():
        await callback.answer(
            "Дневник на этом сервере выключен: администратору нужно задать DIARY_SECRET.",
            show_alert=True,
        )
        return

    row = await _session_for(session, callback.from_user.id, school_class.id)
    if row is None:
        await _offer_sign_in(callback)
        await callback.answer()
        return

    await _show(callback, session, school_class, row, "day", 0, row.student_id or 0)
    await callback.answer()


@router.callback_query(DiaryAction.filter(F.view == "signin"))
async def diary_sign_in(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
    role: Role | None,
) -> None:
    """Hands out one ticket to the sign-in form.

    The link is never the password's route — the whole point of the page is
    that the password does not travel through Telegram. What travels is a
    ticket worth one attempt for fifteen minutes.
    """
    if school_class is None or role is None or not _bound(school_class):
        await callback.answer("Дневник недоступен", show_alert=True)
        return
    if not diary_enabled():
        await callback.answer("Дневник на этом сервере выключен.", show_alert=True)
        return

    base = get_settings().public_base_url.rstrip("/")
    if not base:
        # The honest failure. A link built from nothing would 404, and telling
        # somebody to «open the page» that does not exist is worse than saying
        # the deployment is not finished.
        await callback.message.edit_text(
            "🔐 <b>Вход в дневник</b>\n\n"
            "На этом сервере не задан публичный адрес (<code>PUBLIC_BASE_URL</code>), "
            "поэтому страницу входа открыть неоткуда. Это чинит администратор сервера.",
            reply_markup=sign_in_keyboard(None),
        )
        await callback.answer()
        return

    code = await diary_link.mint(
        session, telegram_id=callback.from_user.id, class_id=school_class.id
    )
    await callback.message.edit_text(
        "🔐 <b>Вход в дневник</b>\n\n"
        f"Ссылка действует {diary_link.TICKET_MINUTES} минут и только один раз.\n\n"
        "Пароль вводится на странице и уходит прямо в дневник — "
        "ни бот, ни эта база его не видят и не сохраняют.\n\n"
        "<b>Не пересылайте ссылку никому:</b> она открывает вход в ваш аккаунт.",
        reply_markup=sign_in_keyboard(f"{base}/diary/signin/{code}"),
        disable_web_page_preview=True,
    )
    await callback.answer()


@router.callback_query(DiaryAction.filter(F.view == "signout"))
async def diary_sign_out(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
) -> None:
    if school_class is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    row = await _session_for(session, callback.from_user.id, school_class.id)
    if row is not None:
        await diary_service.sign_out(session, row)
    await _offer_sign_in(callback)
    await callback.answer("Вы вышли из дневника")


@router.callback_query(DiaryAction.filter(F.view == "students"))
async def diary_students(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass | None,
) -> None:
    if school_class is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    row = await _session_for(session, callback.from_user.id, school_class.id)
    if row is None:
        await _offer_sign_in(callback)
        await callback.answer()
        return

    service = diary_service.DiaryService(session, row)
    try:
        students = await service.students()
    except PetersburgError as error:
        await _stumble(callback, session, school_class, error)
        return

    await callback.message.edit_text(
        "👥 <b>Чей дневник смотрим</b>",
        reply_markup=student_picker(students, row.student_id or 0),
    )
    await callback.answer()


@router.callback_query(DiaryAction.filter(F.view.in_({"day", "week", "homework", "marks"})))
async def diary_view(
    callback: CallbackQuery,
    callback_data: DiaryAction,
    session: AsyncSession,
    school_class: SchoolClass | None,
) -> None:
    if school_class is None:
        await callback.answer("Нет доступа", show_alert=True)
        return
    row = await _session_for(session, callback.from_user.id, school_class.id)
    if row is None:
        await _offer_sign_in(callback)
        await callback.answer()
        return

    await _show(
        callback,
        session,
        school_class,
        row,
        callback_data.view,
        callback_data.offset,
        callback_data.student or row.student_id or 0,
    )
    await callback.answer()


async def _show(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass,
    row: DiarySession,
    view: str,
    offset: int,
    student: int,
) -> None:
    """Fetch one view and draw it, or say why it could not be fetched."""
    offset = max(-MAX_OFFSET, min(offset, MAX_OFFSET))
    service = diary_service.DiaryService(session, row)

    try:
        students = await service.students()
        if not students:
            await callback.message.edit_text(
                "📒 <b>Дневник</b>\n\n"
                "<i>К этому аккаунту не привязан ни один ученик.</i>",
                reply_markup=signed_out_keyboard(can_sign_in=True),
            )
            return

        # A payload naming a child this account cannot see falls back to the
        # first one rather than being obeyed: the list came from the upstream
        # for *this* session, so it is the authority on what may be read.
        allowed = {item.education_id for item in students}
        if student not in allowed:
            student = students[0].education_id
        if row.student_id != student:
            row.student_id = student
            await session.commit()

        text = await _body(service, school_class, view, offset, student)
    except SessionExpired:
        await _offer_sign_in(callback)
        await callback.answer("Дневник разлогинил — войдите снова", show_alert=True)
        return
    except PetersburgError as error:
        await _stumble(callback, session, school_class, error)
        return
    except Exception:  # noqa: BLE001 - an upstream shape must not reach a chat
        log.exception("diary view failed")
        await callback.answer("Дневник ответил чем-то неожиданным.", show_alert=True)
        return

    await callback.message.edit_text(
        text,
        reply_markup=diary_keyboard(
            view, offset, student, many_students=len(students) > 1
        ),
        disable_web_page_preview=True,
    )


async def _body(
    service: diary_service.DiaryService,
    school_class: SchoolClass,
    view: str,
    offset: int,
    student: int,
) -> str:
    """One view as text.

    **Corrections are deliberately not applied here.** A family can lay a value
    over what the diary sent down (``services/diary_overrides``), and that
    happens in the app, where a corrected value is drawn as corrected and can be
    reset next to where it is shown. The bot draws a day as one block of text
    with no control per lesson, so a correction applied here would be
    indistinguishable from what the school actually wrote, with no way to take
    it back — and this is the surface a parent is most likely to be reading.
    Leaving it as the unmodified mirror of the diary is the safer half of the
    asymmetry, not an omission. If this ever gains per-lesson buttons, apply
    them *and* mark them; do not apply them quietly.
    """
    # «Today» is the class's, not the server's — the same rule the rest of the
    # project follows, and it matters here because a family in Kaliningrad
    # reading a Petersburg school is one hour apart from it.
    today = (
        datetime.now(school_class.tz).date() if school_class.tz is not None else Date.today()
    )

    if view == "day":
        day = shift_days(today, offset)
        if day is None:
            return "Такого дня нет."
        return render_day(await service.schedule(student, day, day), day, today)

    if view == "week":
        anchor = shift_weeks(today, offset)
        if anchor is None:
            return "Такой недели нет."
        start = anchor - timedelta(days=anchor.weekday())
        end = start + timedelta(days=5)
        return render_week(await service.schedule(student, start, end), start, today)

    if view == "homework":
        end = today + timedelta(days=HOMEWORK_DAYS)
        return render_homework(await service.homework(student, today, end), today, end, today)

    start = today - timedelta(days=30)
    return render_marks(await service.marks(student, start, today), start, today)


async def _stumble(
    callback: CallbackQuery,
    session: AsyncSession,
    school_class: SchoolClass,
    error: PetersburgError,
) -> None:
    """One place that turns an upstream failure into something to do about it."""
    if isinstance(error, UpstreamUnavailable):
        await callback.answer(
            "Дневник сейчас не отвечает. Это на их стороне — попробуйте позже.",
            show_alert=True,
        )
        return
    log.info("diary refused a call: %s", type(error).__name__)
    await callback.answer("Дневник не принял запрос. Попробуйте войти заново.", show_alert=True)
