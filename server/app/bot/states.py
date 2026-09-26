"""FSM state groups for the bot's multi-step forms."""

from __future__ import annotations

from aiogram.fsm.state import State, StatesGroup


class CreateClass(StatesGroup):
    # The number first, then the letter: the number is a button and the letter
    # is optional, so the step that cannot be skipped comes first.
    grade = State()
    letter = State()
    # Two school steps, because there are two ways to answer the question. The
    # first takes a search query and shows what the directory found; the second
    # takes the name itself, and is reached by «✏️ Ввести вручную» or when
    # there is no directory to search. Keeping them apart is what stops a typed
    # school name from being sent off as a query.
    school = State()
    school_manual = State()
    timezone = State()


class BindDiary(StatesGroup):
    # Binding a class to «Сетевой город» after the region is chosen: this one
    # state holds the region key in its data and takes a school-name query. Kept
    # apart from CreateClass.school so a typed school name here is never sent off
    # as a create-a-class query, and the pick payload is its own prefix.
    school = State()


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
