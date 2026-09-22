"""Callback payloads and keyboards for the management pages.

Kept out of ``keyboards.py`` deliberately: that file is the vocabulary of the
day-to-day flows, and a prefix collision between the two would not fail a
build - it would silently route one feature's button into another feature's
handler. Every prefix here is checked against that file by
``tests/test_bot_manage.py``.
"""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.button_style import DANGER, PRIMARY, SUCCESS
from app.bot.keyboards import ClassAction, Menu, back_to_menu
from app.bot.manage_render import AUDIT_PAGE, BELLS_MAX, DEVICES_MAX, LIST_MAX, SUBJECTS_MAX
from app.models import DayKind


class TermAction(CallbackData, prefix="trm"):
    action: str  # list | scheme | edit
    value: str = ""


class ManageAction(CallbackData, prefix="mg"):
    action: str  # root | rename | school | city | calendar | rotate_feed | delete | ...
    value: str = ""


# ``sep="|"`` on the two payloads whose ``value`` is itself a pair -
# «name:12», «2026-10-26:holiday». aiogram packs fields with its separator and
# refuses a value that contains one, so with the default ':' every one of these
# keyboards raised ValueError the moment it was built - a page that could not
# be drawn at all. The pipe never appears in a date, an id or a colour.
class SubjectAction(CallbackData, prefix="sub", sep="|"):
    action: str  # list | open | add | field | colour | delete | collect
    value: str = ""  # «field» and «colour» carry «<tag>:<id>» / «<id>:<hex>»


class DayKindAction(CallbackData, prefix="dk", sep="|"):
    action: str  # list | add | pick_date | kind | bells | delete | period
    value: str = ""  # «kind» and «bells» carry «<iso date>:<kind|schedule id>»


class BellsAction(CallbackData, prefix="bl"):
    action: str  # list | open | edit | create | default | delete
    value: str = ""


class DeviceAction(CallbackData, prefix="dev"):
    action: str  # list | revoke | unlink
    value: str = ""


class RequestAction(CallbackData, prefix="rq"):
    action: str  # approve | decline
    value: str = ""


class AuditAction(CallbackData, prefix="au"):
    action: str  # page
    value: str = ""


class ImportAction(CallbackData, prefix="imp"):
    action: str  # apply | cancel
    value: str = ""


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


def rows_of(buttons: list[InlineKeyboardButton], per_row: int) -> list[list[InlineKeyboardButton]]:
    return [buttons[i : i + per_row] for i in range(0, len(buttons), per_row)]


def back_to(action: str, label: str = "‹ Назад") -> list[InlineKeyboardButton]:
    return [InlineKeyboardButton(text=label, callback_data=ManageAction(action=action).pack())]


def class_menu(
    *,
    is_owner: bool,
    many_classes: bool,
    pending: int,
    diary_bound: bool = False,
) -> InlineKeyboardMarkup:
    rows: list[list[InlineKeyboardButton]] = [
        [
            InlineKeyboardButton(
                text="✏️ Название", callback_data=ManageAction(action="rename").pack()
            ),
            InlineKeyboardButton(
                text="🏫 Школа", callback_data=ManageAction(action="school").pack()
            ),
        ],
        [
            InlineKeyboardButton(
                text="🏙 Город", callback_data=ManageAction(action="city").pack()
            ),
            # Packed rather than hand-written: the string it produces is the
            # same today, and a prefix rename or a new field on ``ClassAction``
            # would turn a literal into a button that answers nothing.
            InlineKeyboardButton(
                text="🕒 Часовой пояс",
                callback_data=ClassAction(action="timezone").pack(),
            ),
        ],
        [
            InlineKeyboardButton(
                text="📚 Предметы", callback_data=SubjectAction(action="list").pack()
            ),
            InlineKeyboardButton(
                text="🏖 Особые дни", callback_data=DayKindAction(action="list").pack()
            ),
        ],
        [
            InlineKeyboardButton(
                text="🔔 Звонки", callback_data=BellsAction(action="list").pack()
            ),
            InlineKeyboardButton(
                text="📱 Устройства", callback_data=DeviceAction(action="list").pack()
            ),
        ],
        [
            InlineKeyboardButton(
                text="📅 Календарь", callback_data=ManageAction(action="calendar").pack()
            ),
            InlineKeyboardButton(
                text="📜 Журнал", callback_data=AuditAction(action="page", value="0").pack()
            ),
        ],
        [
            InlineKeyboardButton(
                text="🗓 Четверти", callback_data=TermAction(action="list").pack()
            ),
        ],
    ]
    rows.append(
        [
            # Painted by what pressing does, like every other toggle here: red
            # while the press takes something away, plain while it gives it
            # back.
            InlineKeyboardButton(
                text="📒 Дневник: отвязать" if diary_bound else "📒 Привязать дневник",
                callback_data=ManageAction(action="diary_bind").pack(),
                style=DANGER if diary_bound else None,
            ),
        ]
    )
    if pending:
        rows.append(
            [
                InlineKeyboardButton(
                    text=f"🙋 Запросы доступа · {pending}",
                    callback_data=Menu(action="access").pack(),
                )
            ]
        )
    if many_classes:
        rows.append(
            [
                InlineKeyboardButton(
                    text="🔀 Сменить класс", callback_data=ManageAction(action="switch").pack()
                )
            ]
        )
    if is_owner:
        rows.append(
            [
                InlineKeyboardButton(text="🔁 Сменить код", callback_data="cls:rotate_code:"),
                InlineKeyboardButton(
                    text="🗑 Удалить класс",
                    callback_data=ManageAction(action="delete").pack(),
                    style=DANGER,
                ),
            ]
        )
    return back_to_menu(rows)


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


def bells_list_keyboard(schedules: list, default_id: int | None) -> InlineKeyboardMarkup:
    rows: list[list[InlineKeyboardButton]] = []
    for schedule in schedules[:BELLS_MAX]:
        sid = str(schedule.id)
        row = [
            InlineKeyboardButton(
                text=f"✏️ {schedule.name}"[:32],
                callback_data=BellsAction(action="edit", value=sid).pack(),
                # The default one is green, and ⭐ — «сделать основным» — is
                # left plain on the others: green says which row is in force,
                # the same thing it says about today in the calendar, and only
                # one of the two can have it without the page having a colour
                # that means «current» and one that means «make current».
                style=SUCCESS if schedule.id == default_id else None,
            )
        ]
        if schedule.id != default_id:
            row.append(
                InlineKeyboardButton(
                    text="⭐", callback_data=BellsAction(action="default", value=sid).pack()
                )
            )
            row.append(
                InlineKeyboardButton(
                    text="🗑",
                    callback_data=BellsAction(action="delete", value=sid).pack(),
                    style=DANGER,
                )
            )
        rows.append(row)
    rows.append(
        [
            InlineKeyboardButton(
                text="➕ Новое расписание звонков",
                callback_data=BellsAction(action="create").pack(),
                style=SUCCESS,
            )
        ]
    )
    rows.append(back_to("root"))
    return back_to_menu(rows)


def device_keyboard(devices: list) -> InlineKeyboardMarkup:
    rows: list[list[InlineKeyboardButton]] = []
    for device in devices[:DEVICES_MAX]:
        did = str(device.id)
        row = [
            InlineKeyboardButton(
                text=f"🚫 {device.device_name or device.id}"[:28],
                callback_data=DeviceAction(action="revoke", value=did).pack(),
                style=DANGER,
            )
        ]
        if device.telegram_id is not None:
            row.append(
                InlineKeyboardButton(
                    text="🔗 Отвязать",
                    callback_data=DeviceAction(action="unlink", value=did).pack(),
                    style=DANGER,
                )
            )
        rows.append(row)
    rows.append(back_to("root"))
    return back_to_menu(rows)


def request_keyboard(request_id: int) -> InlineKeyboardMarkup:
    rid = str(request_id)
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(
                    text="✅ Одобрить",
                    callback_data=RequestAction(action="approve", value=rid).pack(),
                    style=SUCCESS,
                ),
                InlineKeyboardButton(
                    text="Отклонить",
                    callback_data=RequestAction(action="decline", value=rid).pack(),
                    style=DANGER,
                ),
            ]
        ]
    )


def audit_keyboard(offset: int, more: bool) -> InlineKeyboardMarkup:
    rows: list[list[InlineKeyboardButton]] = []
    if more:
        rows.append(
            [
                InlineKeyboardButton(
                    text="Ещё ›",
                    callback_data=AuditAction(
                        action="page", value=str(offset + AUDIT_PAGE)
                    ).pack(),
                    style=PRIMARY,
                )
            ]
        )
    rows.append(back_to("root"))
    return back_to_menu(rows)


def import_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(
                    text="✅ Применить",
                    callback_data=ImportAction(action="apply").pack(),
                    style=SUCCESS,
                ),
                InlineKeyboardButton(
                    text="Отмена",
                    callback_data=ImportAction(action="cancel").pack(),
                    style=DANGER,
                ),
            ]
        ]
    )


def switch_keyboard(classes: list) -> InlineKeyboardMarkup:
    rows = [
        [
            InlineKeyboardButton(
                text=f"🏫 {school_class.name}"[:40],
                callback_data=ManageAction(action="switch_to", value=str(school_class.id)).pack(),
            )
        ]
        for school_class in classes[:15]
    ]
    rows.append(back_to("root"))
    return back_to_menu(rows)


def terms_menu(terms, *, is_semester: bool) -> InlineKeyboardMarkup:
    """One row per term, plus the switch between the two schemes.

    The scheme button is painted by what pressing it does rather than by the
    state it reports — «Перейти на полугодия» is an offer, and green on a
    button that says «полугодия» while the class is on quarters reads as a
    claim about the present.
    """
    rows = [
        [
            InlineKeyboardButton(
                text=f"{term.index}. {term.starts_on:%d.%m} — {term.ends_on:%d.%m}",
                callback_data=TermAction(action="edit", value=str(term.index)).pack(),
            )
        ]
        for term in terms
    ]
    rows.append(
        [
            InlineKeyboardButton(
                text="📗 Перейти на четверти" if is_semester else "📘 Перейти на полугодия",
                callback_data=TermAction(
                    action="scheme", value="quarter" if is_semester else "semester"
                ).pack(),
            )
        ]
    )
    rows.append(back_to("root"))
    return InlineKeyboardMarkup(inline_keyboard=rows)
