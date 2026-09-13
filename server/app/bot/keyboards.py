"""Inline keyboards and typed callback payloads."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import (
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    KeyboardButton,
    ReplyKeyboardMarkup,
)

from app.bot.button_style import DANGER, PRIMARY, SUCCESS
from app.models import Role

WEEKDAY_NAMES = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"]
WEEKDAY_FULL = [
    "Понедельник",
    "Вторник",
    "Среда",
    "Четверг",
    "Пятница",
    "Суббота",
    "Воскресенье",
]


class Menu(CallbackData, prefix="m"):
    action: str


class DayNav(CallbackData, prefix="day"):
    offset: int


class HomeworkAction(CallbackData, prefix="hw"):
    action: str  # add | edit | delete | pick_day | pick_subject
    value: str = ""


class AccessAction(CallbackData, prefix="acl"):
    action: str  # list | invite | revoke | set_role | remove_invite
    value: str = ""


class RolePick(CallbackData, prefix="role"):
    role: str
    target: str = ""


class TimetableAction(CallbackData, prefix="tt"):
    action: str  # view | pick_day | set_cell | clear_cell | bells
    value: str = ""


class OverrideAction(CallbackData, prefix="ovr"):
    action: str  # add | cancel_lesson | pick_day | pick_index | clear
    value: str = ""


class EventAction(CallbackData, prefix="ev"):
    action: str  # add | delete | pick_kind | pick_day
    value: str = ""


class TimezonePick(CallbackData, prefix="tz"):
    zone: str


class ClassAction(CallbackData, prefix="cls"):
    action: str  # settings | rotate_code | rename | create | switch
    value: str = ""


class WeekNav(CallbackData, prefix="wk"):
    offset: int  # weeks relative to the current one


class TaskAction(CallbackData, prefix="task"):
    action: str  # list | done | add | delete_pick | delete | delete_confirm | remind
    value: str = ""
    # Whether the list the button came from was showing finished tasks, so
    # that ticking one re-renders the same list rather than the default one.
    show_done: int = 0


class HomeworkTick(CallbackData, prefix="hwt"):
    action: str  # toggle
    value: str = ""


class ReminderAction(CallbackData, prefix="rem"):
    action: str  # card | toggle | set_time | clear_time | off
    value: str = ""


def main_menu(role: Role) -> InlineKeyboardMarkup:
    rows: list[list[InlineKeyboardButton]] = [
        [
            InlineKeyboardButton(
                text="📅 Сегодня", callback_data=DayNav(offset=0).pack(), style=SUCCESS
            ),
            InlineKeyboardButton(
                text="🗓 Завтра", callback_data=DayNav(offset=1).pack(), style=PRIMARY
            ),
        ],
        [
            InlineKeyboardButton(
                text="📝 Домашнее задание", callback_data=Menu(action="homework").pack()
            )
        ],
        # Next to «сегодня» and «завтра» on purpose: it is the same question
        # asked about any other day, and it is where a viewer looks up a past
        # day as readily as an editor plans a future one.
        [
            InlineKeyboardButton(
                text="📆 Календарь", callback_data=Menu(action="day").pack(), style=PRIMARY
            )
        ],
    ]
    rows.append(
        [
            InlineKeyboardButton(
                text="🗓 Неделя", callback_data=Menu(action="week").pack(), style=PRIMARY
            ),
            InlineKeyboardButton(text="⏭ Что дальше", callback_data=Menu(action="next").pack()),
        ]
    )
    rows.append(
        [
            InlineKeyboardButton(text="✅ Мои задачи", callback_data=Menu(action="tasks").pack()),
            InlineKeyboardButton(
                text="🔔 Напоминания", callback_data=Menu(action="reminders").pack()
            ),
        ]
    )
    if role.at_least(Role.EDITOR):
        rows.append(
            [
                InlineKeyboardButton(
                    text="🔄 Замены", callback_data=Menu(action="overrides").pack()
                ),
                InlineKeyboardButton(text="🎉 События", callback_data=Menu(action="events").pack()),
            ]
        )
    if role.at_least(Role.ADMIN):
        rows.append(
            [
                InlineKeyboardButton(
                    text="🧩 Расписание", callback_data=Menu(action="editor").pack()
                ),
                InlineKeyboardButton(text="👥 Доступ", callback_data=Menu(action="access").pack()),
            ]
        )
        rows.append(
            [InlineKeyboardButton(text="⚙️ Класс", callback_data=Menu(action="class").pack())]
        )
    return InlineKeyboardMarkup(inline_keyboard=rows)


def back_to_menu(extra: list[list[InlineKeyboardButton]] | None = None) -> InlineKeyboardMarkup:
    rows = list(extra or [])
    rows.append([InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def day_nav(offset: int) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(
                    text="‹", callback_data=DayNav(offset=offset - 1).pack()
                ),
                InlineKeyboardButton(
                    text="Сегодня", callback_data=DayNav(offset=0).pack(), style=SUCCESS
                ),
                InlineKeyboardButton(
                    text="›", callback_data=DayNav(offset=offset + 1).pack()
                ),
            ],
            [InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())],
        ]
    )


def request_contact() -> ReplyKeyboardMarkup:
    return ReplyKeyboardMarkup(
        keyboard=[[KeyboardButton(text="📱 Поделиться номером", request_contact=True)]],
        resize_keyboard=True,
        one_time_keyboard=True,
        input_field_placeholder="Нажмите кнопку, чтобы подтвердить номер",
    )


def cancel_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(
                    text="Отмена",
                    callback_data=Menu(action="root").pack(),
                    style=DANGER,
                )
            ]
        ]
    )


def role_picker(available: list[Role], target: str = "") -> InlineKeyboardMarkup:
    rows = [
        [
            InlineKeyboardButton(
                text=role.title_ru, callback_data=RolePick(role=role.value, target=target).pack()
            )
        ]
        for role in available
    ]
    rows.append(
        [
            InlineKeyboardButton(
                text="Отмена", callback_data=Menu(action="root").pack(), style=DANGER
            )
        ]
    )
    return InlineKeyboardMarkup(inline_keyboard=rows)


def weekday_picker(callback_factory: type[CallbackData], action: str) -> InlineKeyboardMarkup:
    """Mon–Sat picker; Sunday is omitted because nothing is ever scheduled on it."""
    rows: list[list[InlineKeyboardButton]] = []
    for chunk_start in (0, 3):
        rows.append(
            [
                InlineKeyboardButton(
                    text=WEEKDAY_NAMES[i],
                    callback_data=callback_factory(action=action, value=str(i + 1)).pack(),
                    style=PRIMARY,
                )
                for i in range(chunk_start, chunk_start + 3)
            ]
        )
    rows.append([InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def timezone_picker() -> InlineKeyboardMarkup:
    """One row per Russian time zone, labelled the way schedules are written.

    Eleven rows is a long keyboard, but it is a once-per-class decision and a
    wrong zone silently shifts every bell, so it is worth the scroll.
    """
    from app.timezones import RUSSIAN_TIMEZONES

    rows = [
        [
            InlineKeyboardButton(
                text=f"{offset} · {cities}",
                callback_data=TimezonePick(zone=zone).pack(),
            )
        ]
        for zone, offset, cities in RUSSIAN_TIMEZONES
    ]
    return InlineKeyboardMarkup(inline_keyboard=rows)


# --------------------------------------------------------------------------
# Week, «что дальше», tasks and reminders
# --------------------------------------------------------------------------


def week_nav(offset: int) -> InlineKeyboardMarkup:
    """«Сегодня» returns to the week that contains today, not to the day view,
    so the reader stays in the week view they opened."""
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(
                    text="‹", callback_data=WeekNav(offset=offset - 1).pack()
                ),
                InlineKeyboardButton(
                    text="Сегодня", callback_data=WeekNav(offset=0).pack(), style=SUCCESS
                ),
                InlineKeyboardButton(
                    text="›", callback_data=WeekNav(offset=offset + 1).pack()
                ),
            ],
            [InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())],
        ]
    )


def next_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [InlineKeyboardButton(text="🔄 Обновить", callback_data=Menu(action="next").pack())],
            [InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())],
        ]
    )


def cut(text: str, limit: int) -> str:
    """Button labels have no wrapping; a long title is cut with an ellipsis."""
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


#: Task buttons in one keyboard. Telegram allows a hundred; a phone shows
#: about ten before the message scrolls out of view.
TASK_BUTTONS_MAX = 10


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


def reminder_keyboard(settings) -> InlineKeyboardMarkup:
    """One row per digest (change time / switch off) and a toggle per flag."""

    def _digest_row(kind: str, icon: str, enabled: bool) -> list[InlineKeyboardButton]:
        row = [
            InlineKeyboardButton(
                text=f"{icon} Изменить время",
                callback_data=ReminderAction(action="set_time", value=kind).pack(),
            )
        ]
        if enabled:
            row.append(
                InlineKeyboardButton(
                    text=f"{icon} Выключить",
                    callback_data=ReminderAction(action="clear_time", value=kind).pack(),
                    style=DANGER,
                )
            )
        return row

    def _toggle(label: str, flag: str, on: bool) -> list[InlineKeyboardButton]:
        # Painted by what pressing it does, not by the state it reports: these
        # two carry both halves in one label, so «Замены: выключить» is red
        # because pressing it switches замены off, and the same button reading
        # «включить» is plain because pressing it takes nothing away.
        return [
            InlineKeyboardButton(
                text=f"{label}: " + ("выключить" if on else "включить"),
                callback_data=ReminderAction(action="toggle", value=flag).pack(),
                style=DANGER if on else None,
            )
        ]

    rows = [
        _digest_row("morning", "☀️", settings.morning_at is not None),
        _digest_row("evening", "🌙", settings.evening_at is not None),
        _toggle("🔄 Замены", "changes", settings.notify_changes),
        _toggle("📝 Задания", "homework", settings.notify_homework),
        [
            InlineKeyboardButton(
                text="🔕 Выключить всё",
                callback_data=ReminderAction(action="off").pack(),
                style=DANGER,
            )
        ],
        [InlineKeyboardButton(text="‹ Меню", callback_data=Menu(action="root").pack())],
    ]
    return InlineKeyboardMarkup(inline_keyboard=rows)
