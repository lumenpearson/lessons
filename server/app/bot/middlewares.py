"""Middleware that hands every handler a database session, the active class and
the caller's role — so no handler has to repeat that lookup.

It also decides *which* class a user who is in several of them is currently
looking at. That preference is a stored fact, not a process variable: every
Vercel invocation is a fresh Python process, so a "current class" kept in
memory would be whatever the last cold start happened to pick. It lives in the
FSM table under its own ``destiny``, next to the conversations, where it
survives a redeploy and costs one indexed read per update.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

from aiogram import BaseMiddleware
from aiogram.fsm.storage.base import StorageKey
from aiogram.types import TelegramObject, User

from app.bot.roles import default_class_for, get_role, list_memberships
from app.db import SessionLocal
from app.fsm_storage import PREFS_DESTINY, DatabaseStorage
from app.models import SchoolClass

# ``PREFS_DESTINY`` is imported rather than declared here: it is a separate
# destiny from the conversations on purpose - clearing a half-finished flow must
# not also forget which class the person was working in - and the sweep in
# ``fsm_storage`` is what has to know that this row is not one, so the name
# lives beside the sweep.


def prefs_key(telegram_id: int) -> StorageKey:
    """The one storage key the preference is written to and read from.

    ``bot_id`` is zero rather than the real bot's: the preference is about a
    person and a class, and the bot the update happened to arrive through is
    not part of that identity - deriving it from the Bot object would also make
    the key unreachable from code that has no Bot, such as this middleware.
    """
    return StorageKey(bot_id=0, chat_id=telegram_id, user_id=telegram_id, destiny=PREFS_DESTINY)


async def preferred_class_id(telegram_id: int) -> int | None:
    """The class this user last chose, if they ever chose one."""
    storage = DatabaseStorage(SessionLocal)
    data = await storage.get_data(prefs_key(telegram_id))
    value = data.get("class_id")
    return value if isinstance(value, int) else None


async def active_class(session, telegram_id: int) -> SchoolClass | None:
    """The class a user lands in: their choice while it still holds, else the
    first membership.

    A stale preference - the class was deleted, or somebody's access to it was
    revoked - is ignored rather than honoured, which is what keeps a removed
    member from carrying on in a class they are no longer part of.
    """
    memberships = await list_memberships(session, telegram_id)
    if not memberships:
        return await default_class_for(session, telegram_id)
    if len(memberships) == 1:
        # The common case by a wide margin, and it has nothing to choose
        # between: no reason to pay for the preference read on every update.
        return await session.get(SchoolClass, memberships[0].class_id)

    chosen = await preferred_class_id(telegram_id)
    if chosen is not None and any(member.class_id == chosen for member in memberships):
        school_class = await session.get(SchoolClass, chosen)
        if school_class is not None:
            return school_class
    return await session.get(SchoolClass, memberships[0].class_id)


class ContextMiddleware(BaseMiddleware):
    async def __call__(
        self,
        handler: Callable[[TelegramObject, dict[str, Any]], Awaitable[Any]],
        event: TelegramObject,
        data: dict[str, Any],
    ) -> Any:
        user: User | None = data.get("event_from_user")

        async with SessionLocal() as session:
            data["session"] = session
            data["school_class"] = None
            data["role"] = None

            if user is not None:
                school_class = await active_class(session, user.id)
                data["school_class"] = school_class
                if school_class is not None:
                    data["role"] = await get_role(session, user.id, school_class.id)

            try:
                result = await handler(event, data)
                await session.commit()
                return result
            except Exception:
                await session.rollback()
                raise
