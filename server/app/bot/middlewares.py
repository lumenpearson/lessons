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
from dishka.integrations.aiogram import CONTAINER_NAME
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.roles import default_class_for, get_role, list_memberships
from app.db import SessionLocal
from app.di import container
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

        # One request scope per update, opened here rather than by
        # `setup_dishka`. That helper registers a single middleware, holding
        # the container it was handed, on *every* observer — so a message
        # entered a scope twice, and the two were **siblings** on the root
        # container rather than parent and child. Nothing resolved a session at
        # the update level, so today that cost a scope and not a second
        # session; the first outer middleware or injected error handler that
        # asked for one would have got its own, on its own connection, that
        # this middleware never commits. That is precisely the second answer to
        # «where does a session come from» this container exists to remove.
        #
        # It also reads `container()` at call time. `setup_dishka` captures the
        # container by value, and `app/api/telegram.py` caches the dispatcher
        # for the life of the process — so after a shutdown closed the
        # container, every webhook update would have gone on being served from
        # the closed one, silently, exactly as it would have on the API side
        # before the lifespan learned to re-read it.
        #
        # The scope is put in `data` under dishka's own key, so a handler that
        # wants `@inject` and `FromDishka` has one. What is given up by not
        # calling `setup_dishka`: the other twenty-six observers, `errors`
        # among them, have no container. Nothing there injects, and an error
        # handler that needed a session would want this middleware's, not one
        # of its own.
        async with container()() as scope:
            data[CONTAINER_NAME] = scope
            # The same provider an endpoint is served by, so «a session for one
            # unit of work» has one definition instead of one per shell. The
            # commit below stays here, because that half is *not* shared: an
            # endpoint commits where it decides it is finished, and for a
            # handler this middleware is the finish line.
            session: AsyncSession = await scope.get(AsyncSession)
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
