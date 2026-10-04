"""Buttons for «✅ Мои задачи» (``handlers/tasks``).

``TASK_BUTTONS_MAX`` is the renderer's, not this module's: what is drawn and
what can be pressed read one number.
"""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import DANGER, PRIMARY, SUCCESS
from app.bot.keyboards import Menu, cut
from app.bot.tasks_render import TASK_BUTTONS_MAX


class TaskAction(CallbackData, prefix="task"):
    action: str  # list | done | add | delete_pick | delete | delete_confirm | remind
    value: str = ""
    # Whether the list the button came from was showing finished tasks, so
    # that ticking one re-renders the same list rather than the default one.
    show_done: int = 0


def task_list_keyboard(tasks: list, show_done: bool) -> InlineKeyboardMarkup:
    """One toggle button per task, then the list actions.

    ``tasks`` is the same list the text was rendered from, so the buttons and
    the lines agree; the caller passes done ones only when they are shown.
    """
    flag = 1 if show_done else 0
    rows: list[list[InlineKeyboardButton]] = []
    for task in tasks[:TASK_BUTTONS_MAX]:
        mark = "✅" if task.done else "☐"
        rows.append(
            [
                InlineKeyboardButton(
                    text=f"{mark} {cut(task.title, 24)}",
                    callback_data=TaskAction(
                        action="done", value=str(task.id), show_done=flag
                    ).pack(),
                    # Green is the state, not the button's effect: pressing a
                    # green one un-ticks the task. ✅ against a green row is
                    # the same fact said twice, which is what a tick list
                    # wants — the eye finds the done ones by colour and only
                    # then reads them.
                    style=SUCCESS if task.done else None,
                )
            ]
        )
    rows.append(
        [
            InlineKeyboardButton(
                text="➕ Добавить",
                callback_data=TaskAction(action="add").pack(),
                style=SUCCESS,
            ),
            InlineKeyboardButton(
                text="Скрыть сделанные" if show_done else "🗂 Показать сделанные",
                callback_data=TaskAction(action="list", show_done=0 if show_done else 1).pack(),
                style=PRIMARY,
            ),
        ]
    )
    if tasks:
        rows.append(
            [
                InlineKeyboardButton(
                    text="🗑 Удалить…",
                    callback_data=TaskAction(action="delete_pick", show_done=flag).pack(),
                    style=DANGER,
                )
            ]
        )
    rows.append([InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def task_delete_picker(tasks: list, show_done: bool) -> InlineKeyboardMarkup:
    flag = 1 if show_done else 0
    rows = [
        [
            InlineKeyboardButton(
                text=f"🗑 {cut(task.title, 28)}",
                callback_data=TaskAction(
                    action="delete", value=str(task.id), show_done=flag
                ).pack(),
                style=DANGER,
            )
        ]
        for task in tasks[:TASK_BUTTONS_MAX]
    ]
    rows.append(
        [
            InlineKeyboardButton(
                text="‹ К списку", callback_data=TaskAction(action="list", show_done=flag).pack()
            )
        ]
    )
    return InlineKeyboardMarkup(inline_keyboard=rows)


def task_delete_confirm(task_id: int, show_done: bool) -> InlineKeyboardMarkup:
    flag = 1 if show_done else 0
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(
                    text="🗑 Да, удалить",
                    callback_data=TaskAction(
                        action="delete_confirm", value=str(task_id), show_done=flag
                    ).pack(),
                    style=DANGER,
                ),
                # Deliberately plain, though «отмена» is red everywhere else:
                # the red button in this pair is the one that deletes, and a
                # second red button beside it would leave the two telling each
                # other apart on their labels alone — which is the reading the
                # colour was added to save.
                InlineKeyboardButton(
                    text="Отмена", callback_data=TaskAction(action="list", show_done=flag).pack()
                ),
            ]
        ]
    )


def task_remind_keyboard(task_id: int, has_time: bool) -> InlineKeyboardMarkup:
    """Offered right after a dated task is saved. «за час» needs a due time."""
    rows: list[list[InlineKeyboardButton]] = []
    # The three offsets are blue together and «без напоминания» is not: within
    # the group the colour separates nothing, which is right — they are three
    # answers to one question — and between the group and the way out of it, it
    # separates the only thing here worth separating.
    if has_time:
        rows.append(
            [
                InlineKeyboardButton(
                    text="⏰ За час",
                    callback_data=TaskAction(action="remind", value=f"{task_id}_hour").pack(),
                    style=PRIMARY,
                )
            ]
        )
    rows.append(
        [
            InlineKeyboardButton(
                text="⏰ Утром в 8:00 в день срока",
                callback_data=TaskAction(action="remind", value=f"{task_id}_morning").pack(),
                style=PRIMARY,
            )
        ]
    )
    rows.append(
        [
            InlineKeyboardButton(
                text="⏰ Накануне в 20:00",
                callback_data=TaskAction(action="remind", value=f"{task_id}_eve").pack(),
                style=PRIMARY,
            )
        ]
    )
    rows.append(
        [
            InlineKeyboardButton(
                text="Без напоминания", callback_data=TaskAction(action="list").pack()
            )
        ]
    )
    return InlineKeyboardMarkup(inline_keyboard=rows)
