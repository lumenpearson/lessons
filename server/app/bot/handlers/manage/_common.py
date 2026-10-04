"""What every screen of :mod:`app.bot.handlers.manage` shares.

The refusal sentences, the one role check every handler is gated by
(:func:`needs`), and the small parsers that turn a value the client sent into
one the handlers can trust or ``None``.
"""

from __future__ import annotations

import functools
import inspect
import re
from collections.abc import Awaitable, Callable
from datetime import date as Date
from datetime import datetime
from typing import ParamSpec

from aiogram.types import CallbackQuery, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot.manage_render import _common as mr
from app.models import DayKind, Role, SchoolClass
from app.services.manage import classes as classes_service

NO_ACCESS = "Нет доступа. Откройте /start, чтобы получить его."
NEED_ADMIN = "Только для администраторов"
NEED_EDITOR = "Нужна роль редактора"
NEED_OWNER = "Только для владельца класса"


# --------------------------------------------------------------------------
# Guards and small parsers
# --------------------------------------------------------------------------


def _allowed(school_class: SchoolClass | None, role: Role | None, minimum: Role) -> bool:
    """The whole of the permission check, in one place so no handler invents
    its own version of it."""
    return school_class is not None and role is not None and role.at_least(minimum)


def _refusal(role: Role | None, minimum: Role) -> str:
    """The sentence a refusal says. A screen every member may open refuses
    only somebody who is not one, so it is «нет доступа» whatever else."""
    if role is None or minimum is Role.VIEWER:
        return NO_ACCESS
    if minimum is Role.OWNER:
        return NEED_OWNER
    return NEED_ADMIN if minimum is Role.ADMIN else NEED_EDITOR


_P = ParamSpec("_P")


def needs(
    minimum: Role, *, step: bool = False, refusal: str | None = None
) -> Callable[[Callable[_P, Awaitable[None]]], Callable[_P, Awaitable[None]]]:
    """The role a handler demands, checked before its body runs.

    The one role check of «⚙️ Класс». The rule is :func:`_allowed` - the class
    is known and the member's role is at least ``minimum`` - and it used to be
    written out at the top of seventy-two handlers, each copy with its own
    refusal after it. A handler now says what it demands where it is
    registered::

        @router.callback_query(SubjectAction.filter(F.action == "open"))
        @needs(Role.ADMIN)
        async def subject_open(...): ...

    A refusal has three shapes, decided here, and each is what the handlers
    did by hand:

    * a press is answered with the refusal in an alert - one left unanswered
      keeps its spinner until Telegram gives up;
    * a command is answered with the refusal as a message - whoever typed
      «/bells» is owed a sentence saying why not;
    * a ``step`` of a form drops the form. FSM state is per-user and therefore
      attacker-controlled - a client can put itself into any step and send a
      message - so a step that trusted the step before it would be a write
      with no check at all. Text typed into a form nobody had the right to
      open is answered with nothing; a press on one is still answered.

    The sentence is :func:`_refusal`'s unless ``refusal`` names one: a screen
    that has always said «Только для администраторов» to everybody keeps
    saying it.

    **A decorator, and not an aiogram filter or a flag a middleware reads**,
    because the check has to travel with the function. A filter that fails
    does not refuse: aiogram offers the update to the next handler and in the
    end to ``unknown``, so a refused press would be answered by whatever else
    matched, or not at all. A flag is read only on the way in through the
    dispatcher, and every handler here is also called directly - by the tests
    that press each step as a наблюдатель and demand a refusal, which are the
    only proof the check exists at all. Wrapped, a handler cannot be reached
    without its check, whichever way it is called. aiogram reads the arguments
    it injects off the unwrapped function (``inspect.unwrap``), so the handler
    still receives exactly what it declares, and ``functools.wraps`` keeps the
    name the handler-order tests compare.

    The handler must take its event first, as ``callback`` or ``message``, and
    declare ``school_class`` and ``role`` - and ``state``, for a step. Anything
    else is a ``TypeError`` at import: a gate that cannot see the role is not
    one.
    """

    def decorate(handler: Callable[_P, Awaitable[None]]) -> Callable[_P, Awaitable[None]]:
        signature = inspect.signature(handler)
        names = list(signature.parameters)
        event = names[0] if names else ""
        wanted = {"school_class", "role"} | ({"state"} if step else set())
        if event not in {"callback", "message"} or not wanted <= set(names):
            raise TypeError(
                f"{handler.__qualname__} cannot be gated: it must take `callback` or "
                f"`message` first and declare {sorted(wanted)}"
            )

        @functools.wraps(handler)
        async def gated(*args: _P.args, **kwargs: _P.kwargs) -> None:
            arguments = signature.bind_partial(*args, **kwargs).arguments
            role = arguments.get("role")
            if _allowed(arguments.get("school_class"), role, minimum):
                await handler(*args, **kwargs)
                return
            if step:
                await arguments["state"].clear()
            text = refusal or _refusal(role, minimum)
            if event == "callback":
                await arguments["callback"].answer(text, show_alert=True)
            elif not step:
                await arguments["message"].answer(text)

        return gated

    return decorate


def _int_or_none(raw: str) -> int | None:
    """An id out of callback data, or ``None`` for anything a crafted one carries."""
    try:
        return int(raw)
    except (TypeError, ValueError):
        return None


def _day_kind_or_none(raw: str) -> DayKind | None:
    """A day kind out of callback data or FSM state, or ``None``.

    `DayKind(raw)` raises on anything else, and a raise here leaves the button
    spinning until Telegram gives up rather than answering — the same trap the
    `_int_or_none` above exists for, on a value that arrives from the client.
    """
    try:
        return DayKind(raw)
    except ValueError:
        return None


def _date_or_none(raw: str) -> Date | None:
    try:
        return Date.fromisoformat(raw)
    except (TypeError, ValueError):
        return None


def _today(school_class: SchoolClass) -> Date:
    """Today on the class's own wall clock, never the server's."""
    return datetime.now(school_class.tz).date()


def _bot_of(event: Message | CallbackQuery):
    """The Bot behind an update, or ``None``.

    The handler tests call these functions with plain stubs that have no bot
    at all, and a notification is never worth failing the edit it describes.
    """
    return getattr(event, "bot", None)


def _parse_day(raw: str, today: Date) -> Date | None:
    """«12.09» or «12.09.2026» -> a date.

    A bare day and month is read in the current year unless that lands well in
    the past: a school year straddles New Year, so «10.01» typed in December
    means the January that is coming, not the one that has gone.

    Both conversions are inside the ``try``, and it catches ``OverflowError``
    as well, because ``str.isdigit()`` is not the same question as «will
    ``int()`` take this» and ``date()`` does not raise only ``ValueError``.
    Every character of «²2.09» is a digit to ``isdigit`` and «²2» is a
    ``ValueError`` to ``int``; «12.09.99999999999999999999» converts happily
    and then overflows the C long ``date()`` is built on. Either way the
    exception went straight past the ``except ValueError`` and out of the
    handler — and both callers are `Message` handlers with no callback to
    apologise on, so a stuck key on the year bought an answer that never came
    and a prompt still waiting for a date, which the next thing typed would be
    read as.
    """
    parts = [part for part in re.split(r"[.\-/\s]+", raw.strip()) if part]
    if len(parts) not in (2, 3) or not all(part.isdigit() for part in parts):
        return None
    try:
        day, month = int(parts[0]), int(parts[1])
        if len(parts) == 3:
            year = int(parts[2])
            if year < 100:
                year += 2000
        else:
            year = today.year
        result = Date(year, month, day)
    except (ValueError, OverflowError):
        return None
    if len(parts) == 2 and (today - result).days > 90:
        try:
            result = Date(year + 1, month, day)
        except ValueError:  # pragma: no cover - 29 February in a common year
            return None
    return result


async def _member_names(session: AsyncSession, class_id: int) -> dict[int, str]:
    """Telegram id -> the name to show, already escaped by ``manage_render``."""
    members = await classes_service.members(session, class_id)
    return {
        member.telegram_id: mr.person(member.full_name, member.username, member.telegram_id)
        for member in members
    }
