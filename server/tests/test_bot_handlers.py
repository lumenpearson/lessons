"""Bot handler logic.

The handlers are called directly with lightweight stubs rather than through a
real Bot. aiogram does not enforce its types at call time, and what is worth
testing here is the parsing and the permission checks, not Telegram's transport.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, time
from types import SimpleNamespace
from typing import Any

import pytest
from sqlalchemy import select

from app.bot.handlers.access import apply_role, invite_phone, invite_role
from app.bot.handlers.content import (
    _parse_time_range,
    homework_pick_day,
    homework_pick_subject,
    homework_text,
    override_subject,
)
from app.bot.handlers.timetable import bells_apply, timetable_apply
from app.bot.keyboards import HomeworkAction
from app.models import (
    BellPeriod,
    BotUser,
    Homework,
    LessonOverride,
    PhoneInvite,
    Role,
    TimetableEntry,
)
from app.models import OverrideAction as OverrideActionEnum

MONDAY = date(2026, 9, 7)


@dataclass
class FakeState:
    """Stand-in for aiogram's FSMContext."""

    data: dict[str, Any] = field(default_factory=dict)
    state: Any = None
    cleared: bool = False

    async def get_data(self) -> dict[str, Any]:
        return dict(self.data)

    async def update_data(self, **kwargs: Any) -> dict[str, Any]:
        self.data.update(kwargs)
        return dict(self.data)

    async def set_state(self, state: Any) -> None:
        self.state = state

    async def clear(self) -> None:
        self.cleared = True
        self.data.clear()
        self.state = None


@dataclass
class FakeMessage:
    text: str | None = ""
    user_id: int = 42
    replies: list[str] = field(default_factory=list)

    @property
    def from_user(self):
        return SimpleNamespace(id=self.user_id, username="tester", full_name="Тестер")

    async def answer(self, text: str, **_: Any) -> None:
        self.replies.append(text)

    @property
    def last(self) -> str:
        return self.replies[-1]


@dataclass
class FakeCallback:
    user_id: int = 42
    message: FakeMessage = field(default_factory=FakeMessage)
    answers: list[tuple[str | None, bool]] = field(default_factory=list)

    @property
    def from_user(self):
        return SimpleNamespace(id=self.user_id, username="tester", full_name="Тестер")

    async def answer(self, text: str | None = None, show_alert: bool = False, **_: Any) -> None:
        self.answers.append((text, show_alert))

    @property
    def alerted(self) -> bool:
        return any(alert for _, alert in self.answers)


class FakeEditable(FakeMessage):
    async def edit_text(self, text: str, **_: Any) -> None:
        self.replies.append(text)


# --------------------------------------------------------------------------
# Timetable parsing
# --------------------------------------------------------------------------


async def test_pasting_a_weekday_replaces_it_wholesale(session, school_class):
    message = FakeMessage(
        text="1. Алгебра, 214\n2. Физика, 305, Иванова И.И.\n3. История"
    )
    state = FakeState(data={"weekday": 2})

    await timetable_apply(message, state, session, school_class, Role.ADMIN)

    entries = list(
        await session.scalars(
            select(TimetableEntry)
            .where(TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == 2)
            .order_by(TimetableEntry.index)
        )
    )
    assert [e.subject_name for e in entries] == ["Алгебра", "Физика", "История"]
    assert entries[0].room == "214"
    assert entries[1].teacher == "Иванова И.И."
    assert entries[2].room is None
    assert "сохранено уроков — 3" in message.last


async def test_pasting_replaces_rather_than_merges(session, school_class):
    """Monday already has three lessons from the fixture."""
    message = FakeMessage(text="1. Только один урок")
    state = FakeState(data={"weekday": 1})
    await timetable_apply(message, state, session, school_class, Role.ADMIN)

    entries = list(
        await session.scalars(
            select(TimetableEntry).where(
                TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == 1
            )
        )
    )
    assert len(entries) == 1


async def test_a_dash_clears_the_weekday(session, school_class):
    message = FakeMessage(text="-")
    state = FakeState(data={"weekday": 1})
    await timetable_apply(message, state, session, school_class, Role.ADMIN)

    entries = list(
        await session.scalars(
            select(TimetableEntry).where(
                TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == 1
            )
        )
    )
    assert entries == []
    assert "очищено" in message.last


async def test_unparseable_lines_are_reported_not_swallowed(session, school_class):
    message = FakeMessage(text="1. Алгебра\nчто-то не то\n2. Физика")
    state = FakeState(data={"weekday": 3})
    await timetable_apply(message, state, session, school_class, Role.ADMIN)

    assert "сохранено уроков — 2" in message.last
    assert "Не разобрал" in message.last
    assert "что-то не то" in message.last


async def test_an_editor_cannot_rewrite_the_timetable(session, school_class):
    message = FakeMessage(text="1. Взлом")
    state = FakeState(data={"weekday": 1})

    await timetable_apply(message, state, session, school_class, Role.EDITOR)

    assert message.replies == []
    assert state.cleared
    entries = list(
        await session.scalars(
            select(TimetableEntry).where(TimetableEntry.weekday == 1)
        )
    )
    assert [e.subject_name for e in entries] == ["Алгебра", "Физика", "История"]


# --------------------------------------------------------------------------
# Bells parsing
# --------------------------------------------------------------------------


async def test_bells_accept_both_colon_and_dot_and_various_dashes(session, school_class):
    message = FakeMessage(text="1. 09:00-09:40\n2. 09.50–10.30\n3. 10:40 — 11:20")
    await bells_apply(message, FakeState(), session, school_class, Role.ADMIN)

    periods = list(
        await session.scalars(
            select(BellPeriod)
            .where(BellPeriod.schedule_id == school_class.bell_schedule_id)
            .order_by(BellPeriod.index)
        )
    )
    assert len(periods) == 3
    assert periods[0].starts_at == time(9, 0)
    assert periods[1].ends_at == time(10, 30)
    assert periods[2].starts_at == time(10, 40)


async def test_bells_reject_an_inverted_range(session, school_class):
    message = FakeMessage(text="1. 10:00-09:00")
    await bells_apply(message, FakeState(), session, school_class, Role.ADMIN)

    assert "Не удалось разобрать" in message.last


async def test_bells_refuse_to_wipe_the_schedule_on_a_bad_paste(session, school_class):
    before = len(
        list(
            await session.scalars(
                select(BellPeriod).where(
                    BellPeriod.schedule_id == school_class.bell_schedule_id
                )
            )
        )
    )
    message = FakeMessage(text="полная ерунда")
    await bells_apply(message, FakeState(), session, school_class, Role.ADMIN)

    after = len(
        list(
            await session.scalars(
                select(BellPeriod).where(
                    BellPeriod.schedule_id == school_class.bell_schedule_id
                )
            )
        )
    )
    assert after == before


# --------------------------------------------------------------------------
# Homework
# --------------------------------------------------------------------------


async def test_homework_is_saved(session, school_class):
    message = FakeMessage(text="№ 12–15")
    state = FakeState(data={"due": MONDAY.isoformat(), "subject": "Алгебра"})

    await homework_text(message, state, session, school_class, Role.EDITOR)

    item = await session.scalar(select(Homework))
    assert item.subject_name == "Алгебра"
    assert item.text == "№ 12–15"
    assert item.due_date == MONDAY
    assert item.created_by == 42
    assert "добавлено" in message.last


async def test_resending_the_same_subject_updates_instead_of_duplicating(session, school_class):
    state = FakeState(data={"due": MONDAY.isoformat(), "subject": "Алгебра"})
    await homework_text(FakeMessage(text="старое"), state, session, school_class, Role.EDITOR)

    state2 = FakeState(data={"due": MONDAY.isoformat(), "subject": "Алгебра"})
    message = FakeMessage(text="новое")
    await homework_text(message, state2, session, school_class, Role.EDITOR)

    items = list(await session.scalars(select(Homework)))
    assert len(items) == 1
    assert items[0].text == "новое"
    assert "обновлено" in message.last


async def test_a_viewer_cannot_add_homework(session, school_class):
    message = FakeMessage(text="взлом")
    state = FakeState(data={"due": MONDAY.isoformat(), "subject": "Алгебра"})

    await homework_text(message, state, session, school_class, Role.VIEWER)

    assert await session.scalar(select(Homework)) is None
    assert state.cleared


# --------------------------------------------------------------------------
# Замены
# --------------------------------------------------------------------------


async def test_override_parses_subject_and_room(session, school_class):
    message = FakeMessage(text="Химия, 118")
    state = FakeState(data={"date": MONDAY.isoformat(), "index": "2"})

    await override_subject(message, state, session, school_class, Role.EDITOR)

    override = await session.scalar(select(LessonOverride))
    assert override.action is OverrideActionEnum.REPLACE
    assert override.subject_name == "Химия"
    assert override.room == "118"
    assert override.index == 2


async def test_override_without_a_room_leaves_it_null(session, school_class):
    message = FakeMessage(text="Химия")
    state = FakeState(data={"date": MONDAY.isoformat(), "index": "2"})

    await override_subject(message, state, session, school_class, Role.EDITOR)

    override = await session.scalar(select(LessonOverride))
    assert override.subject_name == "Химия"
    assert override.room is None


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("12:30-13:15", (time(12, 30), time(13, 15))),
        ("12:30 13:15", (time(12, 30), time(13, 15))),
        ("12:30–13:15", (time(12, 30), time(13, 15))),
        ("13:15-12:30", None),
        ("не время", None),
        ("12:30", None),
    ],
)
def test_time_range_parsing(raw, expected):
    assert _parse_time_range(raw) == expected


# --------------------------------------------------------------------------
# Access
# --------------------------------------------------------------------------


async def test_invite_rejects_something_that_is_not_a_number(session, school_class):
    message = FakeMessage(text="привет")
    state = FakeState()

    await invite_phone(message, state, Role.ADMIN)

    assert "не похоже на номер" in message.last.lower()
    assert state.state is None


async def test_an_admin_cannot_mint_another_admin(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    state = FakeState(data={"phone": "79001234567"})

    await invite_role(
        callback,
        SimpleNamespace(role=Role.ADMIN.value, target=""),
        state,
        session,
        school_class,
        Role.ADMIN,
    )

    assert callback.alerted
    assert await session.scalar(select(PhoneInvite)) is None


async def test_an_owner_can_mint_an_admin(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    state = FakeState(data={"phone": "79001234567"})

    await invite_role(
        callback,
        SimpleNamespace(role=Role.ADMIN.value, target=""),
        state,
        session,
        school_class,
        Role.OWNER,
    )

    invite = await session.scalar(select(PhoneInvite))
    assert invite.role is Role.ADMIN
    assert not callback.alerted


async def test_an_admin_cannot_demote_a_peer(session, school_class):
    session.add(BotUser(telegram_id=99, class_id=school_class.id, role=Role.ADMIN))
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await apply_role(
        callback,
        SimpleNamespace(role=Role.VIEWER.value, target="99"),
        session,
        school_class,
        Role.ADMIN,
    )

    victim = await session.scalar(select(BotUser).where(BotUser.telegram_id == 99))
    assert victim.role is Role.ADMIN
    assert callback.alerted


async def test_an_admin_can_change_an_editor(session, school_class):
    session.add(BotUser(telegram_id=99, class_id=school_class.id, role=Role.EDITOR))
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await apply_role(
        callback,
        SimpleNamespace(role=Role.VIEWER.value, target="99"),
        session,
        school_class,
        Role.ADMIN,
    )

    victim = await session.scalar(select(BotUser).where(BotUser.telegram_id == 99))
    assert victim.role is Role.VIEWER
    assert not callback.alerted


class _FakeCallback:
    """Just enough CallbackQuery to see whether it was answered."""

    def __init__(self) -> None:
        self.answers: list[tuple[str | None, bool]] = []

    async def answer(self, text: str | None = None, show_alert: bool = False) -> None:
        self.answers.append((text, show_alert))


def _error_event(exception: BaseException, callback: object) -> Any:
    return SimpleNamespace(
        update=SimpleNamespace(callback_query=callback),
        exception=exception,
    )


async def test_pressing_a_button_that_changes_nothing_is_answered_quietly():
    """Telegram calls an unchanged re-render a 400, and it is an ordinary press.

    "Сегодня" from the day view and "‹ Меню" from the menu both re-render the
    identical message. Unhandled, the exception escaped before `answer()` ran
    and the button kept its loading spinner until Telegram gave up.
    """
    from aiogram.exceptions import TelegramBadRequest

    from app.bot.bot import _on_error

    callback = _FakeCallback()
    error = TelegramBadRequest(
        method=SimpleNamespace(),
        message="Bad Request: message is not modified",
    )

    assert await _on_error(_error_event(error, callback)) is True
    assert callback.answers == [(None, False)]


async def test_any_other_handler_failure_tells_the_user_instead_of_hanging():
    """Including the FSM flows that read a step the user may not have taken."""
    from app.bot.bot import _on_error

    callback = _FakeCallback()

    assert await _on_error(_error_event(KeyError("phone"), callback)) is True
    assert len(callback.answers) == 1
    text, show_alert = callback.answers[0]
    assert text is not None
    assert show_alert is True


async def test_a_failure_with_no_callback_is_still_handled():
    """A message handler raising must not bubble out of the dispatcher."""
    from app.bot.bot import _on_error

    assert await _on_error(_error_event(RuntimeError("boom"), None)) is True


# --------------------------------------------------------------------------
# Picking a day for homework
# --------------------------------------------------------------------------


async def test_a_long_subject_name_does_not_break_the_day_picker(session, school_class):
    """Telegram counts callback payloads in bytes; Cyrillic costs two each.

    The subject name used to go into the payload cut to 48 *characters*, so a
    real Russian subject packed past the 64-byte ceiling and aiogram refused to
    build the keyboard. The exception surfaced on the previous step — the user
    chose a day and was told to start again — and no subject with a long name
    could be given homework at all.
    """
    long_name = "Основы безопасности жизнедеятельности"
    session.add(
        TimetableEntry(
            class_id=school_class.id,
            weekday=1,
            index=4,
            subject_name=long_name,
        )
    )
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    state = FakeState()

    await homework_pick_day(
        callback,
        HomeworkAction(action="pick_day", value=MONDAY.isoformat()),
        state,
        session,
        school_class,
        Role.EDITOR,
    )

    assert "По какому предмету?" in callback.message.last
    assert long_name in state.data["subjects"]
    assert not callback.alerted


async def test_the_chosen_subject_comes_from_the_list_it_was_offered_from(
    session, school_class
):
    callback = FakeCallback(message=FakeEditable())
    state = FakeState(data={"subjects": ["Алгебра", "Основы безопасности жизнедеятельности"]})

    await homework_pick_subject(callback, HomeworkAction(action="pick_subject", value="1"), state)

    assert state.data["subject"] == "Основы безопасности жизнедеятельности"
    assert "Основы безопасности жизнедеятельности" in callback.message.last


async def test_a_button_from_a_stale_keyboard_is_refused(session, school_class):
    """The index names a position in a list the flow has since replaced."""
    callback = FakeCallback(message=FakeEditable())
    state = FakeState(data={"subjects": ["Алгебра"]})

    await homework_pick_subject(callback, HomeworkAction(action="pick_subject", value="7"), state)

    assert callback.alerted
    assert "subject" not in state.data
