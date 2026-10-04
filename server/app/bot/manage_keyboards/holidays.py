"""Buttons for «🏖 Особые дни»."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import DANGER, PRIMARY, SUCCESS
from app.bot.keyboards import back_to_menu
from app.bot.manage_keyboards._common import back_to
from app.bot.manage_render.bells import BELLS_MAX
from app.bot.manage_render.holidays import LIST_MAX
from app.models import DayKind


# ``sep="|"`` for ``subjects.SubjectAction``'s reason: ``value`` is a pair -
# «2026-10-26:holiday» - and aiogram refuses a value holding its separator.
class DayKindAction(CallbackData, prefix="dk", sep="|"):
    action: str  # list | add | pick_date | kind | bells | delete | period
    value: str = ""  # «kind» and «bells» carry «<iso date>:<kind|schedule id>»


#: What a period may be marked as, and the order the buttons are drawn in.
#:
#: `NORMAL` is not here on purpose: marking a range as «ordinary» is the same
#: as not marking it, and the way to undo a period is to delete its days.
#: `SHORTENED` is not here either — a shortened day points at a bell schedule,
#: and picking one per day is exactly what the day card is for, so offering it
#: over a range would mean quietly choosing the class default for nine days.
PERIOD_KINDS: tuple[tuple[DayKind, str], ...] = (
    (DayKind.HOLIDAY, "🏖 Каникулы"),
    (DayKind.REMOTE, "💻 Дистанционно"),
    (DayKind.SELF_STUDY, "📖 Самоподготовка"),
    (DayKind.DAY_OFF, "🌿 Отгул"),
)


def period_kind_keyboard() -> InlineKeyboardMarkup:
    """Which of the four a range is being marked as, one per row.

    One per row rather than two by two: the labels are two words each and a
    cramped pair reads as one button on a narrow phone — and this is the press
    that rewrites a fortnight of somebody's calendar, so it is worth the height.
    """
    rows = [
        [
            InlineKeyboardButton(
                text=label,
                callback_data=DayKindAction(action="period_kind", value=kind.value).pack(),
                style=PRIMARY,
            )
        ]
        for kind, label in PERIOD_KINDS
    ]
    rows.append(
        [
            InlineKeyboardButton(
                text="‹ Назад",
                callback_data=DayKindAction(action="list").pack(),
            )
        ]
    )
    return InlineKeyboardMarkup(inline_keyboard=rows)


def holiday_list_keyboard(
    overrides: list,
    can_edit: bool,
    can_period: bool | None = None,
    only: DayKind | None = None,
) -> InlineKeyboardMarkup:
    """Marking one day is an editor's business; a whole range of holiday days
    rewrites weeks of the class's calendar at once and stays with admins."""
    if can_period is None:
        can_period = can_edit

    rows: list[list[InlineKeyboardButton]] = []
    # The filter row first, because it decides what the rows under it are.
    # Two per line: five buttons on one line is five unreadable truncations on
    # a narrow phone, and these are the words that say what is being looked at.
    filters = [(None, "Все")] + [(kind, label) for kind, label in PERIOD_KINDS]
    filters.append((DayKind.SHORTENED, "⏱ Сокращённые"))
    for index in range(0, len(filters), 2):
        rows.append(
            [
                InlineKeyboardButton(
                    # The one that is on is ticked rather than coloured: a
                    # style would compete with the «➕ Добавить» below, and the
                    # tick survives a client that draws no styles at all.
                    text=("✓ " if kind == only else "") + label,
                    callback_data=DayKindAction(
                        action="list", value="" if kind is None else kind.value
                    ).pack(),
                )
                for kind, label in filters[index : index + 2]
            ]
        )
    for override in overrides[:LIST_MAX]:
        rows.append(
            [
                InlineKeyboardButton(
                    text=f"🗑 {override.date.strftime('%d.%m')}",
                    callback_data=DayKindAction(
                        action="delete", value=override.date.isoformat()
                    ).pack(),
                    style=DANGER,
                )
            ]
        )
    actions: list[InlineKeyboardButton] = []
    if can_edit:
        actions.append(
            InlineKeyboardButton(
                text="➕ Добавить",
                callback_data=DayKindAction(action="add").pack(),
                style=SUCCESS,
            )
        )
    if can_period:
        actions.append(
            InlineKeyboardButton(
                text="📆 Период",
                callback_data=DayKindAction(action="period").pack(),
                style=PRIMARY,
            )
        )
    if actions:
        rows.append(actions)
    rows.append(back_to("root"))
    return back_to_menu(rows)


def day_kind_keyboard(iso_date: str) -> InlineKeyboardMarkup:
    kinds = [
        ("🏖 Каникулы / выходной", "holiday"),
        ("⏱ Сокращённые уроки", "shortened"),
        ("💻 Дистанционно", "remote"),
        ("📖 Самоподготовка", "self_study"),
        ("🌿 Отгул", "day_off"),
        ("✅ Обычный день (убрать)", "normal"),
    ]
    rows = [
        [
            InlineKeyboardButton(
                text=label,
                callback_data=DayKindAction(action="kind", value=f"{iso_date}:{kind}").pack(),
                # Only «обычный день» is green: it is the one answer here that
                # puts the day back the way it was, and the five above it are
                # each a different exception rather than degrees of one.
                style=SUCCESS if kind == "normal" else None,
            )
        ]
        for label, kind in kinds
    ]
    rows.append(
        [InlineKeyboardButton(text="‹ Назад", callback_data=DayKindAction(action="list").pack())]
    )
    return InlineKeyboardMarkup(inline_keyboard=rows)


def bells_pick_keyboard(schedules: list, iso_date: str) -> InlineKeyboardMarkup:
    rows = [
        [
            InlineKeyboardButton(
                text=f"🔔 {schedule.name}",
                callback_data=DayKindAction(
                    action="bells", value=f"{iso_date}:{schedule.id}"
                ).pack(),
            )
        ]
        for schedule in schedules[:BELLS_MAX]
    ]
    # No «Оставить обычные» here, and that is the whole point of this keyboard.
    # «⏱ Сокращённые уроки» is a claim about the times, and the times come from
    # a bell schedule: without one the resolver falls back to the class default,
    # so the day announces shortened lessons and then draws the normal ones —
    # which is worse than not marking it at all, because somebody reads the
    # label and packs for a short day. `api/edit.day_put` refuses that state
    # with a 422; this keyboard used to offer it as a button.
    #
    # A day that really does ring the usual bells is said by picking the usual
    # schedule off this list by name, which is the same fact written so that
    # the card can show it and the resolver can use it.
    #
    # «‹ Назад» is not a way to decline — the day already carries the class
    # default, so leaving changes nothing — it is a way to leave at all. This
    # was the one card in the bot with no exit row on it, which reads as a
    # screen that has caught you rather than one that is waiting for an answer.
    rows.append(
        [
            InlineKeyboardButton(
                text="‹ Назад", callback_data=DayKindAction(action="list").pack()
            )
        ]
    )
    return InlineKeyboardMarkup(inline_keyboard=rows)
