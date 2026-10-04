"""Buttons for creating a class — grade, school, time zone — whose time-zone
picker «🕒 Часовой пояс» on an existing class reuses (``handlers/start``)."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.providers.dadata.models import SchoolPage


class TimezonePick(CallbackData, prefix="tz"):
    zone: str


class GradePick(CallbackData, prefix="grd"):
    grade: int


class SchoolPick(CallbackData, prefix="sch"):
    action: str  # pick | page | manual | skip | retry
    # For "pick", the school's position in the whole result set, not in the
    # page: the list is searched once and paged locally, so an index is stable
    # while a page number is not.
    value: int = 0


def grade_picker() -> InlineKeyboardMarkup:
    """Eleven numbers, four to a row.

    A number rather than a free-text name because the rest of the app has to
    reason about it — the term scheme follows the grade — and «9А» is not
    something to parse: a class may be «9 инж» or «5-й Б», and a pattern over
    that fails silently on the one class written differently. The letter is
    asked for separately and may be skipped.
    """
    from app.services.terms import MAX_GRADE, MIN_GRADE

    numbers = list(range(MIN_GRADE, MAX_GRADE + 1))
    rows = [
        [
            InlineKeyboardButton(text=str(grade), callback_data=GradePick(grade=grade).pack())
            for grade in numbers[start:start + 4]
        ]
        for start in range(0, len(numbers), 4)
    ]
    return InlineKeyboardMarkup(inline_keyboard=rows)


def school_picker(
    page: SchoolPage,
    *,
    page_size: int,
    allow_skip: bool = True,
) -> InlineKeyboardMarkup:
    """The search results, one school per row, with a pager under them.

    One per row because the names are long: two columns of «МБОУ "Средняя
    общеобразовательная школа № 197"» is two columns of ellipsis. The pager
    only appears when there is a second page, and its position rides in the
    callback payload rather than in FSM state — the same rule the timetable
    editor's ‹ › follows, and for the same reason: a stale state would page a
    different search.
    """
    start = (page.page - 1) * page_size
    rows: list[list[InlineKeyboardButton]] = [
        [
            InlineKeyboardButton(
                text=school.label if school.active else f"{school.label} · закрыта",
                callback_data=SchoolPick(action="pick", value=start + offset).pack(),
            )
        ]
        for offset, school in enumerate(page.items)
    ]

    if page.pages > 1:
        rows.append(
            [
                InlineKeyboardButton(
                    text="‹",
                    callback_data=SchoolPick(action="page", value=page.page - 1).pack(),
                ),
                InlineKeyboardButton(
                    text=f"{page.page}/{page.pages}",
                    # A label, not a button. Telegram has no inert button, so it
                    # points at the page it is already on.
                    callback_data=SchoolPick(action="page", value=page.page).pack(),
                ),
                InlineKeyboardButton(
                    text="›",
                    callback_data=SchoolPick(action="page", value=page.page + 1).pack(),
                ),
            ]
        )

    manual = [
        InlineKeyboardButton(
            text="✏️ Ввести вручную",
            callback_data=SchoolPick(action="manual").pack(),
        )
    ]
    if allow_skip:
        manual.append(
            InlineKeyboardButton(
                text="Пропустить",
                callback_data=SchoolPick(action="skip").pack(),
            )
        )
    rows.append(manual)
    return InlineKeyboardMarkup(inline_keyboard=rows)


def school_fallback(*, allow_skip: bool = True) -> InlineKeyboardMarkup:
    """What is offered when the directory found nothing or is not answering.

    Typing the name is never taken away, at any point in this flow: the
    directory is somebody else's service, and a class must be creatable on a
    day it is down.
    """
    row = [
        InlineKeyboardButton(
            text="✏️ Ввести вручную",
            callback_data=SchoolPick(action="manual").pack(),
        )
    ]
    if allow_skip:
        row.append(
            InlineKeyboardButton(
                text="Пропустить",
                callback_data=SchoolPick(action="skip").pack(),
            )
        )
    return InlineKeyboardMarkup(inline_keyboard=[row])


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
