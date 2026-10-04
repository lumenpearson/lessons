"""Buttons for «📚 Предметы»."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import DANGER, SUCCESS
from app.bot.keyboards import back_to_menu
from app.bot.manage_keyboards._common import back_to, rows_of
from app.bot.manage_render.subjects import SUBJECTS_MAX


# ``sep="|"`` because ``value`` is itself a pair - «name:12», «12:5B6ABF».
# aiogram packs fields with its separator and refuses a value that contains
# one, so with the default ':' every keyboard here raised ValueError the moment
# it was built - a page that could not be drawn at all. The pipe never appears
# in a date, an id or a colour; ``holidays.DayKindAction`` is built the same way.
class SubjectAction(CallbackData, prefix="sub", sep="|"):
    action: str  # list | open | add | field | colour | delete | collect
    value: str = ""  # «field» and «colour» carry «<tag>:<id>» / «<id>:<hex>»


#: Named colours offered instead of asking for hex. Eight is what fits two rows
#: on a phone, and covers the palette a timetable actually needs.
COLOUR_PRESETS: list[tuple[str, str]] = [
    ("🔵 Синий", "#5B6ABF"),
    ("🟢 Зелёный", "#3E8E7E"),
    ("🟠 Оранжевый", "#C46A4A"),
    ("🟣 Фиолетовый", "#8E6AC4"),
    ("🔴 Красный", "#C4534A"),
    ("🟡 Жёлтый", "#C4A24A"),
    ("🟤 Коричневый", "#8A6A4A"),
    ("⚪️ Серый", "#6E7178"),
]


def subject_list_keyboard(
    subjects: list, can_edit: bool, can_collect: bool | None = None
) -> InlineKeyboardMarkup:
    """``can_collect`` is separate because «собрать из расписания» writes only
    the names the timetable already contains, which an editor may do, while
    adding and renaming a subject is an admin's job. A button nobody in the
    room may press is worse than no button: it teaches people to ignore the
    refusals."""
    if can_collect is None:
        can_collect = can_edit

    rows: list[list[InlineKeyboardButton]] = []
    for subject in subjects[:SUBJECTS_MAX]:
        rows.append(
            [
                InlineKeyboardButton(
                    text=f"✏️ {subject.name}"[:40],
                    callback_data=SubjectAction(action="open", value=str(subject.id)).pack(),
                )
            ]
        )
    actions: list[InlineKeyboardButton] = []
    if can_edit:
        actions.append(
            InlineKeyboardButton(
                text="➕ Добавить",
                callback_data=SubjectAction(action="add").pack(),
                style=SUCCESS,
            )
        )
    if can_collect:
        actions.append(
            InlineKeyboardButton(
                text="🔄 Собрать из расписания",
                callback_data=SubjectAction(action="collect").pack(),
            )
        )
    if actions:
        rows.append(actions)
    rows.append(back_to("root"))
    return back_to_menu(rows)


def subject_card_keyboard(subject_id: int) -> InlineKeyboardMarkup:
    sid = str(subject_id)
    rows = [
        [
            InlineKeyboardButton(
                text="✏️ Название",
                callback_data=SubjectAction(action="field", value=f"name:{sid}").pack(),
            ),
            InlineKeyboardButton(
                text="🔤 Сокращение",
                callback_data=SubjectAction(action="field", value=f"short:{sid}").pack(),
            ),
        ],
        [
            InlineKeyboardButton(
                text="👤 Учитель",
                callback_data=SubjectAction(action="field", value=f"teacher:{sid}").pack(),
            ),
            InlineKeyboardButton(
                text="🎨 Цвет",
                callback_data=SubjectAction(action="field", value=f"colour:{sid}").pack(),
            ),
        ],
        [
            InlineKeyboardButton(
                text="🗑 Удалить",
                callback_data=SubjectAction(action="delete", value=sid).pack(),
                style=DANGER,
            )
        ],
        [
            InlineKeyboardButton(
                text="‹ К предметам", callback_data=SubjectAction(action="list").pack()
            )
        ],
    ]
    return InlineKeyboardMarkup(inline_keyboard=rows)


def colour_keyboard(subject_id: int) -> InlineKeyboardMarkup:
    buttons = [
        InlineKeyboardButton(
            text=label,
            callback_data=SubjectAction(
                action="colour", value=f"{subject_id}:{hex_value.lstrip('#')}"
            ).pack(),
        )
        for label, hex_value in COLOUR_PRESETS
    ]
    rows = rows_of(buttons, 2)
    rows.append(
        [
            InlineKeyboardButton(
                text="🚫 Без цвета",
                callback_data=SubjectAction(action="colour", value=f"{subject_id}:none").pack(),
                style=DANGER,
            )
        ]
    )
    rows.append(
        [
            InlineKeyboardButton(
                text="‹ Назад",
                callback_data=SubjectAction(action="open", value=str(subject_id)).pack(),
            )
        ]
    )
    return InlineKeyboardMarkup(inline_keyboard=rows)
