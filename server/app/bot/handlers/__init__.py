"""Handler routers, ordered so that specific routers win over the catch-all."""

from aiogram import Router

from app.bot.handlers import (
    access,
    calendar,
    content,
    diary,
    editor,
    manage,
    project,
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
    # were `tasks`'s — rather than with the rest of `content`. They were kept
    # here because aiogram's `Command` read «/homework@» as /homework and
    # `CommandBreakoutMiddleware` did not, so a form step asked between the two
    # places took it as its answer, and moving the ticks would have changed who
    # answered. Since #276 the breakout reads a command as aiogram does and the
    # form is gone before any router is asked, so this place no longer decides
    # anything; moving them is a change of its own, not made with that fix.
    router.include_router(homework_ticks)
    router.include_router(reminders.router)
    router.include_router(manage.router)
    # The deployment owner's screen. Before the catch-all, which answers its
    # commands for everybody else (handlers/project.py says why).
    router.include_router(project.router)
    # Last, and it has to be: it matches any command at all, so anything above
    # it that answers one must have been asked first. What reaches here is a
    # command nothing in the bot has.
    router.include_router(unknown.router)
    return router


__all__ = ["build_router"]
