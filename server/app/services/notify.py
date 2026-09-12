"""Immediate messages to the class when something changes.

An editor adding a замена for tomorrow is the moment the class wants to hear
about it; a digest the next morning is too late for the pupil who would have
left the textbook at home. So the handlers that write замены, events and
homework call :func:`notify_subscribers` right after committing, and each
subscriber's own flags decide whether they hear about it.
"""

from __future__ import annotations

import logging
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import ReminderSettings, SchoolClass

log = logging.getLogger(__name__)

_FLAGS = {
    "changes": ReminderSettings.notify_changes,
    "homework": ReminderSettings.notify_homework,
}


async def notify_subscribers(
    session: AsyncSession,
    bot: Any,
    school_class: SchoolClass,
    text: str,
    *,
    kind: str,
    exclude: int | None,
) -> int:
    """Send ``text`` (already HTML-escaped by the caller) to everyone in the
    class who opted into ``kind``; returns how many got it.

    ``exclude`` is the author: the person who just added the homework does not
    need a message telling them so. ``bot`` may be ``None`` - the handler tests
    call handlers with plain fakes - in which case nothing is sent.
    """
    if bot is None:
        return 0
    try:
        flag = _FLAGS[kind]
    except KeyError:
        raise ValueError(f"unknown notification kind: {kind!r}") from None

    from aiogram.exceptions import TelegramForbiddenError

    query = select(ReminderSettings).where(
        ReminderSettings.class_id == school_class.id, flag.is_(True)
    )
    if exclude is not None:
        query = query.where(ReminderSettings.telegram_id != exclude)

    sent = 0
    blocked = False
    for settings in await session.scalars(query):
        try:
            await bot.send_message(settings.telegram_id, text)
        except TelegramForbiddenError:
            # They blocked the bot. Every future write to the class would try
            # them again and fail again; switch them off instead.
            settings.notify_changes = False
            settings.notify_homework = False
            settings.morning_at = None
            settings.evening_at = None
            blocked = True
        except Exception:  # noqa: BLE001 - one recipient's outage is not the editor's problem
            log.warning("could not notify %s", settings.telegram_id, exc_info=True)
        else:
            sent += 1

    if blocked:
        await session.commit()
    return sent
