"""«📜 Журнал»: who changed what, a page at a time.

Part of :mod:`app.bot.handlers.manage`; the rules every screen of it
follows are in that package's docstring.
"""

from __future__ import annotations

from aiogram import F, Router
from aiogram.filters import Command
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot import manage_render as mr
from app.bot.handlers.manage._common import _allowed, _int_or_none, _member_names, _refusal
from app.bot.manage_keyboards import AuditAction, audit_keyboard
from app.models import Role, SchoolClass
from app.services import audit

router = Router(name="manage.audit_log")


# --------------------------------------------------------------------------
# 📜 The log
# --------------------------------------------------------------------------


async def _audit_view(session: AsyncSession, school_class: SchoolClass, offset: int):
    entries = await audit.recent(session, school_class.id, limit=mr.AUDIT_PAGE + 1, offset=offset)
    more = len(entries) > mr.AUDIT_PAGE
    entries = entries[:mr.AUDIT_PAGE]
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
