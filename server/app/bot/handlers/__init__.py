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
from app.bot.handlers.content.homework import ticks as homework_ticks


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
    # The «сделал» ticks live with the homework they tick, in
    # `content/homework.py`, and are asked here — where they stood while they
    # were `tasks`'s — rather than with the rest of `content`. aiogram's
    # `Command` reads «/homework@» as /homework and `CommandBreakoutMiddleware`
    # does not, so a form step asked between the two places has always taken
    # it as its answer; asking the ticks earlier would change who answers.
    router.include_router(homework_ticks)
    router.include_router(reminders.router)
    router.include_router(manage.router)
    # Last, and it has to be: it matches any command at all, so anything above
    # it that answers one must have been asked first. What reaches here is a
    # command nothing in the bot has.
    router.include_router(unknown.router)
    return router


__all__ = ["build_router"]
