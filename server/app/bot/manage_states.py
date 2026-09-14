"""FSM state groups for the management flows.

Separate from ``states.py`` so that the day-to-day editing flows and the
structural ones can be read - and changed - without meeting each other.
"""

from __future__ import annotations

from aiogram.fsm.state import State, StatesGroup


class EditSubject(StatesGroup):
    name = State()
    short_name = State()
    teacher = State()
    colour = State()
    create = State()


class EditClassField(StatesGroup):
    """One text prompt for whichever field the caller picked."""

    value = State()


class EditTerm(StatesGroup):
    """Both dates of one четверть, typed as one line."""

    span = State()


class DeleteClass(StatesGroup):
    """Typing the class name back is the confirmation; nothing else is."""

    confirm = State()


class AddHoliday(StatesGroup):
    date = State()
    period = State()
    note = State()


class ImportTimetable(StatesGroup):
    paste = State()


class NewBellSchedule(StatesGroup):
    name = State()
    rows = State()


class EditBellRows(StatesGroup):
    rows = State()


class RequestAccess(StatesGroup):
    message = State()
