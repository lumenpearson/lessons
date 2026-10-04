"""The way in: ``/start`` and the main menu, creating the first class, a day on
its own, the codes that put a phone in a class, ``/help``, and the class's time
zone.

One module per flow, each with a router of its own, included below in one
written-down order the way ``handlers/manage`` is. This was one file of eight
hundred lines where the create-a-class wizard sat between the menu and the day
view. ``_common`` holds the one sentence two of them say. Nothing is
re-exported but ``router``: a test that pins the clock patches ``days``, where
``_today`` reads it.
"""

from aiogram import Router

from app.bot.handlers.start import codes, days, help_page, menu, onboarding, timezone

router = Router(name="start")

# The order an update is offered to the flows in. It is the order the handlers
# stood in when this was one file, with one exception that changes nothing:
# «‹ Меню» (`menu.back_root`) used to come after the wizard's six buttons, and
# it takes `Menu(action="root")` while they take `GradePick`, `SchoolPick` and
# `TimezonePick`, so no press can match both. Two orders here do matter, and
# `test_bot_commands.py` holds both: `menu` before `onboarding`, so a shared
# contact is not taken as a wizard step's answer, and `onboarding` before
# `timezone`, whose handler takes the wizard's `TimezonePick` without its state.
router.include_routers(
    menu.router,
    onboarding.router,
    days.router,
    codes.router,
    help_page.router,
    timezone.router,
)

__all__ = ["router"]
