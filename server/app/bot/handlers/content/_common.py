"""What the three flows of :mod:`app.bot.handlers.content` share: the class's
own today, the two refusals a picker gives, and the guards that turn what a
callback payload carries into a value or ``None``."""

from __future__ import annotations

from datetime import date as Date
from datetime import datetime

from app.config import get_settings
from app.models import EventKind, SchoolClass


def _today(school_class: SchoolClass | None = None) -> Date:
    """Today in the class's own zone.

    Falls back to the server default only when no class is in scope, which in
    practice means a handler that has already refused the request.
    """
    tz = school_class.tz if school_class is not None else get_settings().tz
    return datetime.now(tz).date()


#: The answer a day picker gives to a date it cannot read.
BAD_DATE = "Непонятная дата. Откройте календарь заново."
BAD_PICK = "Не понял, что выбрано. Откройте экран заново."


def _date_or_none(raw: str) -> Date | None:
    """A date out of a callback payload, or ``None``.

    Every «на какой день?» in this module used to call
    ``Date.fromisoformat(callback_data.value)`` straight, which is correct for
    every payload this bot builds and an unhandled ``ValueError`` for every
    other one — and a callback payload is whatever the client sends, not only
    what was put on a button. The three handlers below are the boundary: past
    them the date travels in the FSM state and the five places that read it
    back can go on trusting it. `manage.py` and `timetable.py` already did this;
    this is the same guard under the same name.
    """
    try:
        return Date.fromisoformat(raw)
    except (TypeError, ValueError):
        return None


def _kind_or_none(raw: str) -> EventKind | None:
    """The boundary ``_date_or_none`` draws, for the other two things this
    module carries through the FSM state.

    The date obeys it and the kind, on the very next screen, did not: it was
    stored raw and turned into an ``EventKind`` three questions later, in the
    handler that saves the event. That is exactly the failure the comment above
    ``event_pick_day`` warns about — the person had by then typed a time and a
    title, and what broke was the press before all of it.
    """
    try:
        return EventKind(raw)
    except ValueError:
        return None


def _index_or_none(raw: str) -> int | None:
    """Ditto for the lesson number the override flow carries. Read back by
    three handlers as ``int(data["index"])``, none of which could refuse it."""
    try:
        index = int(raw)
    except (TypeError, ValueError):
        return None
    return index if index >= 0 else None
