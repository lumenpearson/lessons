"""Handler routers, ordered so that specific routers win over the catch-all."""

from aiogram import Router

from app.bot.handlers import (
    access,
    calendar,
    content,
    editor,
    manage,
    reminders,
    start,
    tasks,
    timetable,
    week,
)


def build_router() -> Router:
    router = Router(name="root")
    router.include_router(start.router)
    router.include_router(access.router)
    router.include_router(calendar.router)
    router.include_router(content.router)
    router.include_router(editor.router)
    router.include_router(timetable.router)
    router.include_router(week.router)
    router.include_router(tasks.router)
    router.include_router(reminders.router)
    router.include_router(manage.router)
    return router


__all__ = ["build_router"]
