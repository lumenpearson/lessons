"""«🗓 Четверти»: the class's quarters or half-years, edited against the shared rules.

Part of :mod:`app.bot.handlers.manage`; the rules every screen of it
follows are in that package's docstring.
"""

from __future__ import annotations

from datetime import datetime
from html import escape

from aiogram import F, Router
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.handlers.manage._common import _int_or_none, needs
from app.bot.keyboards import back_to_menu, cancel_keyboard
from app.bot.manage_keyboards.terms import TermAction, terms_menu
from app.bot.manage_states import EditTerm
from app.models import Role, SchoolClass, TermKind
from app.services import terms as terms_service
from app.services.manage import terms as terms_manage

router = Router(name="manage.terms")


# --------------------------------------------------------------------------
# 🗓 Quarters and half-years
#
# The dates are a school's own: the holidays move, a region shifts its spring
# break, a quarantine eats a week. So this is an editor rather than a display,
# and the rules it edits against live in ``app/services/terms.py`` — the same
# ones `/api/v1/manage` uses, because two implementations of "does this term
# overlap" disagree within a month.
# --------------------------------------------------------------------------


async def _terms_card(session: AsyncSession, school_class: SchoolClass):
    year, rows = await terms_manage.current(session, school_class)
    await session.commit()

    scheme = terms_service.scheme_of(school_class)
    is_semester = scheme is TermKind.SEMESTER
    heading = "полугодия" if is_semester else "четверти"
    today = datetime.now(school_class.tz).date()
    current = terms_service.term_at(rows, today)

    lines = [
        f"🗓 <b>{escape(school_class.name)}</b> — {heading} {year}/{year + 1}",
        "",
    ]
    for term in rows:
        mark = " ←" if current is not None and term.index == current.index else ""
        lines.append(
            f"<b>{term.index}.</b> {term.starts_on:%d.%m.%Y} — "
            f"{term.ends_on:%d.%m.%Y} · {term.days} дн.{mark}"
        )
    if current is None:
        lines.append("")
        lines.append("Сейчас каникулы.")
    lines.append("")
    lines.append("Нажмите период, чтобы изменить его даты.")

    return "\n".join(lines), terms_menu(rows, is_semester=is_semester)


@router.callback_query(TermAction.filter(F.action == "list"))
@needs(Role.ADMIN)
async def terms_list(
    callback: CallbackQuery,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    await state.clear()
    text, keyboard = await _terms_card(session, school_class)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer()


@router.callback_query(TermAction.filter(F.action == "scheme"))
@needs(Role.ADMIN)
async def terms_scheme(
    callback: CallbackQuery,
    callback_data: TermAction,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    wanted = TermKind.SEMESTER if callback_data.value == "semester" else TermKind.QUARTER
    await terms_manage.change_scheme(session, school_class, callback.from_user.id, wanted)
    await session.commit()
    await state.clear()

    text, keyboard = await _terms_card(session, school_class)
    await callback.message.edit_text(text, reply_markup=keyboard)
    await callback.answer("Схема изменена")


@router.callback_query(TermAction.filter(F.action == "edit"))
@needs(Role.ADMIN)
async def term_edit_prompt(
    callback: CallbackQuery,
    callback_data: TermAction,
    state: FSMContext,
    school_class: SchoolClass,
    role: Role,
) -> None:
    # ``_int_or_none``, not ``isdigit`` and ``int``: those two do not ask the
    # same question, so «²» passed the check and raised inside the conversion
    # — out of the handler, before ``callback.answer`` was ever reached.
    index = _int_or_none(callback_data.value)
    if index is None or index < 1:
        await callback.answer("Неизвестный период", show_alert=True)
        return

    await state.set_state(EditTerm.span)
    await state.update_data(term_index=index)
    await callback.message.edit_text(
        f"Период <b>{index}</b>. Пришлите обе даты одной строкой:\n\n"
        "<code>01.09.2026 - 31.10.2026</code>",
        reply_markup=cancel_keyboard(),
    )
    await callback.answer()


@router.message(EditTerm.span)
@needs(Role.ADMIN, step=True)
async def term_edit_apply(
    message: Message,
    state: FSMContext,
    session: AsyncSession,
    school_class: SchoolClass,
    role: Role,
) -> None:
    data = await state.get_data()
    index = int(data.get("term_index", 0))
    if index < 1:
        await state.clear()
        await message.answer("Начните заново: /class", reply_markup=back_to_menu())
        return

    span = terms_service.parse_span(message.text)
    if span is None:
        await message.answer(
            "Не разобрал. Две даты через дефис:\n\n<code>01.09.2026 - 31.10.2026</code>"
        )
        return

    try:
        await terms_manage.move_term(
            session, school_class, message.from_user.id, index, span[0], span[1]
        )
    except terms_service.TermError as error:
        # The message is the rule, in Russian, and the prompt stays open: the
        # admin has one date to correct, not a form to start again.
        await message.answer(str(error))
        return
    await session.commit()
    await state.clear()

    text, keyboard = await _terms_card(session, school_class)
    await message.answer(text, reply_markup=keyboard)
