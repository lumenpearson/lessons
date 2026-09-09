"""Middleware that hands every handler a database session, the active class and
the caller's role — so no handler has to repeat that lookup."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

from aiogram import BaseMiddleware
from aiogram.types import TelegramObject, User

from app.bot.roles import default_class_for, get_role
from app.db import SessionLocal


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
                school_class = await default_class_for(session, user.id)
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
