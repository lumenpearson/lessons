"""Buttons for the personal diary.

Same shape as the timetable editor's: one payload type carrying a cursor, so
that a screen left open keeps working and nothing about *where you are* lives
in FSM state. Here the cursor is a view and a day offset, and the day offset is
why the arrows exist — the diary is read by walking backwards through the week
that just happened at least as often as forwards.

``student`` is in the payload rather than looked up per press because a parent
account may carry several children, and the answer is a property of the button
that was pressed rather than of the person pressing it.
"""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import DANGER, SUCCESS
from app.bot.keyboards import Menu

#: How far the arrows will walk from today, in days.
#:
#: A school year either way. Not unbounded: every press is an upstream call,
#: and an arrow held down against somebody else's service is the kind of thing
#: that gets an address blocked.
MAX_OFFSET = 200


class DiaryAction(CallbackData, prefix="dry"):
    """A cursor into somebody's diary.

    ``view`` — day | week | homework | marks | students | signin | signout.
    ``offset`` — days from today for «day», weeks for «week»; one field because
    only one of them is ever live. «marks» ignores it and always shows the last
    thirty days, and «homework» reads a fixed window from today, so neither
    carries an offset here.
    """

    view: str
    offset: int = 0
    student: int = 0


def _at(view: str, offset: int, student: int) -> str:
    return DiaryAction(view=view, offset=offset, student=student).pack()


def signed_out_keyboard(*, can_sign_in: bool) -> InlineKeyboardMarkup:
    rows: list[list[InlineKeyboardButton]] = []
    if can_sign_in:
        rows.append(
            [
                InlineKeyboardButton(
                    text="🔐 Войти в дневник",
                    callback_data=_at("signin", 0, 0),
                    style=SUCCESS,
                )
            ]
        )
    rows.append([InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def student_picker(students: list, current: int) -> InlineKeyboardMarkup:
    """Which child is being read. Asked once and remembered on the session —
    asking on every screen is a question with the same answer every time."""
    rows = [
        [
            InlineKeyboardButton(
                text=f"{'✓ ' if student.education_id == current else ''}{student.full_name}"[:40],
                callback_data=_at("day", 0, student.education_id),
                style=SUCCESS if student.education_id == current else None,
            )
        ]
        for student in students
    ]
    rows.append([InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def diary_keyboard(
    view: str, offset: int, student: int, *, many_students: bool
) -> InlineKeyboardMarkup:
    """The frame every diary screen wears: the pager, the views, the way out."""
    rows: list[list[InlineKeyboardButton]] = []

    # Arrows only where walking makes sense. Marks are a range, not a day, and
    # an arrow on them would be stepping a window nobody asked to step.
    if view in ("day", "week"):
        back = max(offset - 1, -MAX_OFFSET)
        forward = min(offset + 1, MAX_OFFSET)
        rows.append(
            [
                InlineKeyboardButton(text="‹", callback_data=_at(view, back, student)),
                InlineKeyboardButton(
                    text="Сегодня" if view == "day" else "Эта неделя",
                    callback_data=_at(view, 0, student),
                    # Green is the state: it is lit when you are already on
                    # today, which is also when pressing it does nothing.
                    style=SUCCESS if offset == 0 else None,
                ),
                InlineKeyboardButton(text="›", callback_data=_at(view, forward, student)),
            ]
        )

    tabs = [
        ("day", "📅 День"),
        ("week", "🗓 Неделя"),
        ("homework", "📝 Задания"),
        ("marks", "📊 Оценки"),
    ]
    # The current view is not a button back to itself: a tab that redraws the
    # same screen is a press that looks broken. It stays as a label instead.
    rows.append(
        [
            InlineKeyboardButton(
                text=("• " + label) if name == view else label,
                callback_data=_at(name, 0, student) if name != view else _at(view, offset, student),
                style=SUCCESS if name == view else None,
            )
            for name, label in tabs[:2]
        ]
    )
    rows.append(
        [
            InlineKeyboardButton(
                text=("• " + label) if name == view else label,
                callback_data=_at(name, 0, student) if name != view else _at(view, offset, student),
                style=SUCCESS if name == view else None,
            )
            for name, label in tabs[2:]
        ]
    )

    bottom: list[InlineKeyboardButton] = []
    if many_students:
        bottom.append(
            InlineKeyboardButton(text="👥 Ребёнок", callback_data=_at("students", 0, student))
        )
    bottom.append(
        InlineKeyboardButton(
            text="Выйти", callback_data=_at("signout", 0, student), style=DANGER
        )
    )
    rows.append(bottom)
    rows.append([InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def sign_in_keyboard(url: str | None) -> InlineKeyboardMarkup:
    """The link out to the form, or nothing to offer when there is no URL.

    A deployment with no ``PUBLIC_BASE_URL`` cannot build a link that would
    resolve, and printing one that does not is worse than saying so.
    """
    rows: list[list[InlineKeyboardButton]] = []
    if url:
        rows.append([InlineKeyboardButton(text="🔐 Открыть страницу входа", url=url)])
    rows.append(
        [InlineKeyboardButton(text="‹ Назад", callback_data=_at("day", 0, 0))]
    )
    return InlineKeyboardMarkup(inline_keyboard=rows)
