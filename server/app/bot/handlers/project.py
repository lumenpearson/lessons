"""«📊 Проект»: how the whole deployment stands, for the deployment's owner.

Owner here means an account in ``OWNER_IDS``, not a class's owner: the screen
sums every class's numbers and shows the infrastructure under them, which are
the deployment's (the monitoring design's question 2, answered by the owner).
``/project`` shows all of it; ``/health`` its first block alone; a button on
«⚙️ Класс» opens it too, drawn for the deployment's owner and nobody else.

For anybody else these are commands this bot does not have. The filter hands
the update on, and the catch-all answers it as it answers any command it does
not know (``handlers/unknown.py``). A filter rather than ``@needs``, for the
very reason ``@needs`` is a decorator: a failed filter hands the update on
instead of refusing it, and here handing it on is the point - a refusal would
say the screen exists. Neither is in ``COMMANDS``, the one menu every account
sees.

It reads and writes nothing (``services/project_stats.py``).
"""

from __future__ import annotations

from aiogram import Router
from aiogram.filters import Command
from aiogram.types import CallbackQuery, Message, TelegramObject
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot import project_render
from app.bot.keyboards import back_to_menu
from app.bot.project_keyboard import ProjectAction, project_keyboard
from app.config import get_settings
from app.services import project_stats
from app.services.roles import is_env_owner

router = Router(name="project")


def deployment_owner(event: TelegramObject) -> bool:
    """Whether the update comes from an account in ``OWNER_IDS``."""
    user = getattr(event, "from_user", None)
    return user is not None and is_env_owner(user.id)


async def _screen(session: AsyncSession) -> str:
    settings = get_settings()
    stats = await project_stats.gather(session, settings)
    return project_render.render_project(stats, settings.tz)


@router.message(Command("project"), deployment_owner)
async def cmd_project(message: Message, session: AsyncSession) -> None:
    await message.answer(await _screen(session), reply_markup=project_keyboard())


@router.message(Command("health"), deployment_owner)
async def cmd_health(message: Message, session: AsyncSession) -> None:
    checks = await project_stats.checks(session)
    text = project_render.render_health(checks, get_settings().tz)
    await message.answer(text, reply_markup=back_to_menu())


@router.callback_query(ProjectAction.filter(), deployment_owner)
async def project_open(callback: CallbackQuery, session: AsyncSession) -> None:
    # «🔄 Обновить» pressed twice within a minute draws the same text, which
    # Telegram refuses as «message is not modified»; `bot._on_error` answers
    # that press, as it does for every screen.
    await callback.message.edit_text(await _screen(session), reply_markup=project_keyboard())
    await callback.answer()
