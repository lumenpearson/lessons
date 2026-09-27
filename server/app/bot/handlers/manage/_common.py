"""What every screen of :mod:`app.bot.handlers.manage` shares.

The refusal sentences, the one permission check, and the small parsers that
turn a value the client sent into one the handlers can trust or ``None``.
"""

from __future__ import annotations

import re
from datetime import date as Date
from datetime import datetime

from aiogram.types import CallbackQuery, Message
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot import manage_render as mr
from app.models import AccessRequest, BotUser, DayKind, Role, SchoolClass

NO_ACCESS = "Нет доступа. Откройте /start, чтобы получить его."
NEED_ADMIN = "Только для администраторов"
NEED_EDITOR = "Нужна роль редактора"
NEED_OWNER = "Только для владельца класса"


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


def _day_kind_or_none(raw: str) -> DayKind | None:
    """A day kind out of callback data or FSM state, or ``None``.

    `DayKind(raw)` raises on anything else, and a raise here leaves the button
    spinning until Telegram gives up rather than answering — the same trap the
    `_int_or_none` above exists for, on a value that arrives from the client.
    """
    try:
        return DayKind(raw)
    except ValueError:
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

    Both conversions are inside the ``try``, and it catches ``OverflowError``
    as well, because ``str.isdigit()`` is not the same question as «will
    ``int()`` take this» and ``date()`` does not raise only ``ValueError``.
    Every character of «²2.09» is a digit to ``isdigit`` and «²2» is a
    ``ValueError`` to ``int``; «12.09.99999999999999999999» converts happily
    and then overflows the C long ``date()`` is built on. Either way the
    exception went straight past the ``except ValueError`` and out of the
    handler — and both callers are `Message` handlers with no callback to
    apologise on, so a stuck key on the year bought an answer that never came
    and a prompt still waiting for a date, which the next thing typed would be
    read as.
    """
    parts = [part for part in re.split(r"[.\-/\s]+", raw.strip()) if part]
    if len(parts) not in (2, 3) or not all(part.isdigit() for part in parts):
        return None
    try:
        day, month = int(parts[0]), int(parts[1])
        if len(parts) == 3:
            year = int(parts[2])
            if year < 100:
                year += 2000
        else:
            year = today.year
        result = Date(year, month, day)
    except (ValueError, OverflowError):
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
