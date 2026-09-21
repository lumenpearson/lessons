"""Handler routers, ordered so that specific routers win over the catch-all."""

from aiogram import Router

from app.bot.handlers import (
    access,
    calendar,
    content,
    diary,
    editor,
    manage,
    reminders,
    start,
    tasks,
    timetable,
    unknown,
    week,
)


def build_router() -> Router:
    router = Router(name="root")
    router.include_router(start.router)
    router.include_router(access.router)
    router.include_router(calendar.router)
    router.include_router(content.router)
    router.include_router(diary.router)
    router.include_router(editor.router)
    router.include_router(timetable.router)
    router.include_router(week.router)
    router.include_router(tasks.router)
    router.include_router(reminders.router)
    router.include_router(manage.router)
    # Last, and it has to be: it matches any command at all, so anything above
    # it that answers one must have been asked first. What reaches here is a
    # command nothing in the bot has.
    router.include_router(unknown.router)
    return router


__all__ = ["build_router"]
