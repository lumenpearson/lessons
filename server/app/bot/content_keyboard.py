"""Callback payloads of the day-to-day editing flows (``handlers/content``) and
of the «сделал» ticks.

Payloads only: the flows build their buttons inline, one question at a time,
and the month grid they share is ``calendar_keyboard``'s, which packs these
same classes for its «на какой день?».
"""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData


class HomeworkAction(CallbackData, prefix="hw"):
    action: str  # add | edit | delete | pick_day | pick_subject
    value: str = ""


class OverrideAction(CallbackData, prefix="ovr"):
    action: str  # add | cancel_lesson | pick_day | pick_index | clear
    value: str = ""


class EventAction(CallbackData, prefix="ev"):
    action: str  # add | delete | pick_kind | pick_day
    value: str = ""


class HomeworkTick(CallbackData, prefix="hwt"):
    action: str  # toggle
    value: str = ""
