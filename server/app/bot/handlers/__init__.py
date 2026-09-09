"""Handler routers, ordered so that specific routers win over the catch-all."""

from aiogram import Router

from app.bot.handlers import access, content, start, timetable


def build_router() -> Router:
    router = Router(name="root")
    router.include_router(start.router)
    router.include_router(access.router)
    router.include_router(content.router)
    router.include_router(timetable.router)
    return router


__all__ = ["build_router"]
