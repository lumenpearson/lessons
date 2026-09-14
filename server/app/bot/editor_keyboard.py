"""Buttons for the interactive timetable editor.

One payload type for the whole feature, because every screen here is the same
screen with a different cursor on it: a weekday, maybe a lesson inside it, and
whether the breaks are showing. Keeping that in the payload rather than in FSM
state is deliberate — bot state lives in the database and each update may hit a
fresh process, so a keyboard that carries its own position keeps working on a
message left open across a redeploy, and two people editing different days do
not overwrite each other's cursor.

``flags`` is a bitfield for the same reason a payload is 64 *bytes*: one more
boolean view switch should not cost a field, and every field costs its name in
every button on the page.
"""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import DANGER, SUCCESS
from app.bot.editor_render import slot_label
from app.bot.keyboards import WEEKDAY_FULL, Menu, TimetableAction
from app.models import BellPeriod, TimetableEntry, WeekParity

#: The bit in ``EditorAction.flags`` that says the breaks are showing.
BREAKS = 1

#: Monday–Saturday. Sunday is not offered for the same reason the weekday
#: picker never offered it: nothing is ever scheduled on it, and an arrow that
#: steps onto an always-empty day is an arrow press people learn to skip.
LAST_WEEKDAY = 6


class EditorAction(CallbackData, prefix="ted"):
    """A cursor into the editor.

    ``day`` is 1–6, ``index`` the lesson number (0 = «no lesson selected»),
    ``flags`` the view switches. The prefix is ``ted`` rather than ``ed``
    because ``tests/test_bot_manage.py`` checks every prefix against every
    other, and a two-letter one is a collision waiting for the next feature.
    """

    action: str  # day | slot | add | edit | move | drop | split | merge | …
    day: int = 1
    index: int = 0
    flags: int = 0  # view switches only — they survive every navigation
    arg: int = 0  # action-specific: «move» direction, «edit»/«merge» parity


class EditorSubject(CallbackData, prefix="tes"):
    """One subject of the class, picked instead of typed.

    Only the subject travels. The cursor — which day, which lesson, add or
    edit — is already in FSM data by the time this keyboard is drawn, put there
    by the same handler that draws it, and that is the editor's existing rule
    for the typed answer too.
    """

    subject: int


def _cursor(action: str, day: int, flags: int, index: int = 0, arg: int = 0) -> str:
    """One button's payload.

    ``flags`` carries only what the *view* is, so it can be handed on
    unexamined by every screen; anything an action needs goes in ``arg``. They
    were one field for about ten minutes, and «выше/ниже» left its bit set in
    the cursor that then navigated away — a stale bit that nothing reads is
    still a bit the next reader has to prove nothing reads.
    """
    return EditorAction(action=action, day=day, index=index, flags=flags, arg=arg).pack()


def subject_picker(subjects: list, day: int, flags: int) -> InlineKeyboardMarkup:
    """The class's own subjects, two to a row, above the typed prompt.

    Typing still works — the state is set before this is shown — and it is
    still the only way to give a room and a teacher in one go. This is for the
    other case, which is most of them: the subject is one the class already
    has, and picking it off a list is both faster and the only way to be sure
    the spelling matches the one «📚 Предметы» holds.

    An empty dictionary draws no buttons rather than an empty card: a class
    whose timetable is being typed for the first time has nothing to offer yet.
    """
    buttons = [
        InlineKeyboardButton(
            text=subject.short_name or subject.name,
            callback_data=EditorSubject(subject=subject.id).pack(),
        )
        for subject in subjects
    ]
    rows = [buttons[start : start + 2] for start in range(0, len(buttons), 2)]
    rows.append(
        [
            InlineKeyboardButton(
                text="Отмена",
                callback_data=_cursor("day", day, flags),
                style=DANGER,
            )
        ]
    )
    return InlineKeyboardMarkup(inline_keyboard=rows)


def day_keyboard(
    day: int,
    flags: int,
    entries: list[TimetableEntry],
    *,
    can_edit: bool,
) -> InlineKeyboardMarkup:
    """The main screen: the day's slots, the pager, the view switches."""
    from app.bot.editor_render import slots

    rows: list[list[InlineKeyboardButton]] = []
    for index, group in slots(entries):
        rows.append(
            [
                InlineKeyboardButton(
                    text=slot_label(index, group),
                    callback_data=_cursor("slot", day, flags, index),
                )
            ]
        )

    if can_edit:
        rows.append(
            [
                InlineKeyboardButton(
                    text="➕ Урок",
                    callback_data=_cursor("add", day, flags),
                    style=SUCCESS,
                )
            ]
        )

    # The pager. Grey arrows and a dead label between them: the label is the
    # heading repeated where the thumb is, so the day you are on is readable
    # without looking back up the message.
    previous = day - 1 if day > 1 else LAST_WEEKDAY
    following = day + 1 if day < LAST_WEEKDAY else 1
    rows.append(
        [
            InlineKeyboardButton(text="‹", callback_data=_cursor("day", previous, flags)),
            InlineKeyboardButton(
                text=WEEKDAY_FULL[day - 1], callback_data=_cursor("day", day, flags)
            ),
            InlineKeyboardButton(text="›", callback_data=_cursor("day", following, flags)),
        ]
    )

    showing = bool(flags & BREAKS)
    switches = [
        InlineKeyboardButton(
            text="⏱ Перемены",
            callback_data=_cursor("breaks", day, flags ^ BREAKS),
            # Green is the state, as everywhere else: it says the breaks are on
            # screen now, not that pressing it will turn them on.
            style=SUCCESS if showing else None,
        )
    ]
    if can_edit:
        switches.append(
            InlineKeyboardButton(text="🍽 Столовая", callback_data=_cursor("canteen", day, flags))
        )
    rows.append(switches)

    if can_edit:
        # The paste editor, reached from the day it would overwrite. It is
        # still the fastest way to enter a term from a photo of the board —
        # what it was bad at was changing one lesson, which is what the
        # buttons above are for. Leaving it only on a menu entry meant
        # choosing between the two before knowing which one you needed.
        rows.append(
            [
                InlineKeyboardButton(
                    text="📋 Вставить день",
                    callback_data=TimetableAction(action="pick_day", value=str(day)).pack(),
                ),
                InlineKeyboardButton(
                    text="🔔 Звонки", callback_data=TimetableAction(action="bells").pack()
                ),
            ]
        )

    rows.append([InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def slot_keyboard(
    day: int, index: int, flags: int, rows_in_slot: list[TimetableEntry]
) -> InlineKeyboardMarkup:
    """The card for one lesson: move it, edit it, split its weeks, delete it."""
    rows: list[list[InlineKeyboardButton]] = [
        [
            InlineKeyboardButton(
                text="▲ Выше", callback_data=_cursor("move", day, flags, index, arg=0)
            ),
            InlineKeyboardButton(
                text="▼ Ниже", callback_data=_cursor("move", day, flags, index, arg=1)
            ),
        ]
    ]

    if len(rows_in_slot) == 1:
        rows.append(
            [
                InlineKeyboardButton(
                    text="✏️ Изменить", callback_data=_cursor("edit", day, flags, index)
                ),
                InlineKeyboardButton(
                    text="✂️ По неделям", callback_data=_cursor("split", day, flags, index)
                ),
            ]
        )
    else:
        for row in rows_in_slot:
            mark = "чис" if row.parity is WeekParity.ODD else "знам"
            rows.append(
                [
                    InlineKeyboardButton(
                        text=f"✏️ {mark} · {row.subject_name}"[:34],
                        callback_data=_cursor(
                            "edit",
                            day,
                            flags,
                            index,
                            arg=1 if row.parity is WeekParity.EVEN else 0,
                        ),
                    )
                ]
            )
        rows.append(
            [
                InlineKeyboardButton(
                    text="Оставить чис",
                    callback_data=_cursor("merge", day, flags, index, arg=0),
                ),
                InlineKeyboardButton(
                    text="Оставить знам",
                    callback_data=_cursor("merge", day, flags, index, arg=1),
                ),
            ]
        )

    rows.append(
        [
            InlineKeyboardButton(
                text="🗑 Удалить урок",
                callback_data=_cursor("drop", day, flags, index),
                style=DANGER,
            )
        ]
    )
    rows.append(
        [
            InlineKeyboardButton(
                text="‹ К расписанию", callback_data=_cursor("day", day, flags)
            )
        ]
    )
    return InlineKeyboardMarkup(inline_keyboard=rows)


def canteen_keyboard(
    day: int, flags: int, periods: dict[int, BellPeriod], canteen_after: int | None
) -> InlineKeyboardMarkup:
    """Which break lunch falls on — one button per break, not per lesson.

    The last lesson has no break after it, so it is not offered: a столовая
    «после шестого» in a six-lesson schedule is a gap of zero minutes that the
    day view would have to decline to draw.
    """
    indexes = sorted(periods)[:-1]
    buttons = [
        InlineKeyboardButton(
            text=f"после {index}",
            callback_data=_cursor("eat", day, flags, index),
            style=SUCCESS if index == canteen_after else None,
        )
        for index in indexes
    ]
    rows = [buttons[start : start + 4] for start in range(0, len(buttons), 4)] or []
    if canteen_after is not None:
        rows.append(
            [
                InlineKeyboardButton(
                    text="Не отмечать",
                    callback_data=_cursor("eat", day, flags),
                    style=DANGER,
                )
            ]
        )
    rows.append(
        [
            InlineKeyboardButton(
                text="‹ К расписанию", callback_data=_cursor("day", day, flags)
            )
        ]
    )
    return InlineKeyboardMarkup(inline_keyboard=rows)
