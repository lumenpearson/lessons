"""Day-to-day editing: homework, substitutions and events. This is what an EDITOR does.

Every write here does three things, in this order: it saves, it writes one
line to the audit log in the same transaction, and only then it tells the
subscribers. The order matters. A notification about a substitution that failed to
save would send thirty people to the wrong room, so nothing is announced
before it is committed — and ``notify_subscribers`` swallows a single
recipient's outage rather than failing the edit that caused it.

One module per flow — ``homework``, ``overrides``, ``events`` — each with a
router of its own, and ``_common`` for the clock and the guards on what a
payload carries. The flows import those by name, so a test that pins the clock
patches ``_today`` on the flow's own module; nothing here re-exports it, so a
patch aimed at this package raises instead of quietly changing nothing.
"""

from aiogram import Router

from app.bot.handlers.content import events, homework, overrides

router = Router(name="content")

# The order the three flows stood in when this was one file. No two of them
# can match one update: each has its own payload class and its own states.
router.include_routers(homework.router, overrides.router, events.router)

__all__ = ["router"]
