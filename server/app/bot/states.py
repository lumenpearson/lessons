"""FSM state groups for the bot's multi-step forms."""

from __future__ import annotations

from aiogram.fsm.state import State, StatesGroup


class CreateClass(StatesGroup):
    name = State()
    school = State()
    timezone = State()


class AddHomework(StatesGroup):
    subject = State()
    text = State()


class AddInvite(StatesGroup):
    phone = State()
    role = State()
    label = State()


class AddOverride(StatesGroup):
    index = State()
    subject = State()
    room = State()


class AddEvent(StatesGroup):
    kind = State()
    time = State()
    title = State()


class EditTimetable(StatesGroup):
    weekday = State()
    cell = State()


class EditBells(StatesGroup):
    rows = State()


class EditorLesson(StatesGroup):
    """The button editor's one typed prompt — adding or renaming a lesson.

    A single state, because the cursor it belongs to (day, lesson, view) rides
    in the FSM *data* rather than in the state name: the editor has one text
    question and six screens that can ask it.
    """

    text = State()


class AddTask(StatesGroup):
    text = State()


class SetReminderTime(StatesGroup):
    kind = State()
