"""Buttons for binding a class to an electronic diary.

The unbound «📒 Привязать дневник» opens a small chooser rather than binding at
once, because there is more than one diary now. The rows drawn and the rows a
press can reach are the same set, so «… и ещё» can never lie: the region list
is every allow-listed, password-capable region, and the school list is capped
and says «показаны первые N» when the search found more.
"""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.manage_keyboards._common import ManageAction, back_to


class DiarySchoolPick(CallbackData, prefix="dsch"):
    """A pick from the «Сетевой город» school search, when binding a class.

    Its own prefix rather than sharing :class:`SchoolPick`, which is scoped to
    the create-a-class flow: two flows on one payload would be told apart only
    by which state was active, and a diary school id is not a ЕГРЮЛ record. The
    value is the school's index in the searched list held in FSM data, checked
    against that list where it is read — never trusted as an id.
    """

    action: str  # pick | cancel
    value: int = 0


#: At most this many schools shown after a search. One constant for the rows
#: drawn and the buttons built, so what is on the screen is what can be pressed.
DIARY_SCHOOLS_MAX = 8


def diary_provider_menu() -> InlineKeyboardMarkup:
    """Choose which diary to bind: Petersburg, or «Сетевой город»."""
    rows = [
        [
            InlineKeyboardButton(
                text="🏛 Санкт-Петербург",
                callback_data=ManageAction(action="diary_prov", value="petersburg").pack(),
            )
        ],
        [
            InlineKeyboardButton(
                text="🌆 Сетевой город",
                callback_data=ManageAction(action="diary_prov", value="netschool").pack(),
            )
        ],
        back_to("root"),
    ]
    return InlineKeyboardMarkup(inline_keyboard=rows)


def diary_region_menu() -> InlineKeyboardMarkup:
    """Every «Сетевой город» region a family can sign in to with a password."""
    from app.providers.netschool import regions

    rows = [
        [
            InlineKeyboardButton(
                text=region.title,
                callback_data=ManageAction(action="diary_reg", value=region.key).pack(),
            )
        ]
        for region in regions.listed()
    ]
    rows.append(back_to("root"))
    return InlineKeyboardMarkup(inline_keyboard=rows)


def diary_school_menu(schools: list[dict], *, more: bool) -> InlineKeyboardMarkup:
    """The search results, each carrying its index in the searched list.

    ``schools`` is already cut to :data:`DIARY_SCHOOLS_MAX`; ``more`` says the
    search found others, so the caption can ask for a narrower query.
    """
    rows = [
        [
            InlineKeyboardButton(
                text=school["name"][:60],
                callback_data=DiarySchoolPick(action="pick", value=index).pack(),
            )
        ]
        for index, school in enumerate(schools)
    ]
    rows.append(
        [
            InlineKeyboardButton(
                text="‹ Отмена", callback_data=DiarySchoolPick(action="cancel").pack()
            )
        ]
    )
    return InlineKeyboardMarkup(inline_keyboard=rows)
