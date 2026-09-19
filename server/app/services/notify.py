"""Immediate messages to the class when something changes.

An editor adding a substitution for tomorrow is the moment the class wants to
hear about it; a digest the next morning is too late for the pupil who would
have left the textbook at home. So the handlers that write substitutions, events and
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

#: Free-form text in an announcement, before it is shortened.
#:
#: Homework text is free-form and can be a paragraph — `HomeworkIn.text`
#: accepts 4000 characters and the column caps at nothing. A push notification
#: that long is unreadable on a lock screen, and past Telegram's own ceiling it
#: is not a notification at all: the whole message is refused, every recipient's
#: send raises, both callers swallow it, and nobody is told anything while the
#: write answers 200.
#:
#: It lives here rather than in either shell because both of them announce the
#: same assignment: the rule was in `bot/handlers/content` only, so an
#: assignment typed into the bot arrived cut to 200 characters and the same one saved
#: from a phone arrived whole.
NOTIFY_TEXT_MAX = 200


def shorten(text: str) -> str:
    """``text`` as one line, no longer than [NOTIFY_TEXT_MAX].

    Cut before escaping, never after: cutting after can leave «&am», which is
    a message Telegram refuses of its own.
    """
    text = " ".join(text.split())
    return text if len(text) <= NOTIFY_TEXT_MAX else text[: NOTIFY_TEXT_MAX - 1].rstrip() + "…"

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
