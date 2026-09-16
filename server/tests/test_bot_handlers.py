"""Bot handler logic.

The handlers are called directly with lightweight stubs rather than through a
real Bot. aiogram does not enforce its types at call time, and what is worth
testing here is the parsing and the permission checks, not Telegram's transport.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date, time
from types import SimpleNamespace
from typing import Any

import pytest
from aiogram.types import CallbackQuery, User
from sqlalchemy import select

from app.bot.handlers import content
from app.bot.handlers.access import (
    JOIN_MODE_TEXT,
    access_root,
    apply_role,
    invite_phone,
    invite_role,
    revoke,
    switch_join_mode,
)
from app.bot.handlers.access import router as access_router
from app.bot.handlers.content import (
    _parse_time_range,
    event_pick_kind,
    event_time,
    event_title,
    homework_pick_day,
    homework_pick_subject,
    homework_text,
    homework_typed_subject,
    override_cancel,
    override_clear,
    override_subject,
)
from app.bot.handlers.start import cmd_code, phone_code, show_day
from app.bot.handlers.timetable import bells_apply, timetable_apply
from app.bot.handlers.week import week_text
from app.bot.keyboards import (
    AccessAction,
    EventAction,
    HomeworkAction,
    RolePick,
    shift_days,
    shift_weeks,
)
from app.bot.roles import list_memberships
from app.models import (
    AuditEntry,
    BellPeriod,
    BotUser,
    DayEvent,
    DeviceInvite,
    EventKind,
    Homework,
    JoinMode,
    LessonOverride,
    OverrideAction,
    PhoneInvite,
    Role,
    SchoolClass,
    TimetableEntry,
)
from app.models import OverrideAction as OverrideActionEnum
from app.services import device_invites

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
# Разбор того, что приходит снаружи
#
# `_date_or_none` и `_shorten` — граница между payload'ом клиента и состоянием
# FSM, за которой пять мест читают дату уже не проверяя. Ни одна из двух не
# вызывалась ни одним тестом.
# --------------------------------------------------------------------------


def test_a_date_out_of_a_callback_payload_is_never_trusted():
    """A payload is whatever the client sends, not only what was on a button.

    `Date.fromisoformat` on it straight is an unhandled `ValueError` — a 500 in
    the middleware and «что-то пошло не так» for a person who pressed a
    calendar. The guard exists for that; this is what it has to swallow.
    """
    assert content._date_or_none("2026-09-07") == date(2026, 9, 7)
    for bad in ("", "вчера", "2026-13-40", "2026-09-07T10:00", None, "07.09.2026"):
        assert content._date_or_none(bad) is None, bad


def test_a_notification_is_shortened_without_losing_the_start():
    """The digest line carries the assignment, and Telegram is not the place to
    paste four paragraphs — but the first words are what tells somebody which
    assignment it is, so the cut is at the end and it is marked."""
    assert content._shorten("  два   пробела\nи перевод ") == "два пробела и перевод"

    long = "я" * (content.NOTIFY_TEXT_MAX + 50)
    cut = content._shorten(long)
    assert len(cut) <= content.NOTIFY_TEXT_MAX
    assert cut.endswith("…")
    # Short enough to pass through untouched, including the ellipsis-free edge.
    exact = "я" * content.NOTIFY_TEXT_MAX
    assert content._shorten(exact) == exact


async def test_typing_a_subject_instead_of_picking_one_moves_the_flow_on(session):
    """The picker is buttons, but a class with an empty dictionary has none —
    so the name is typed, and that path had no test at all."""
    state = FakeState()

    message = FakeMessage(text="  Астрономия  ")
    await homework_typed_subject(message, state)

    assert state.data["subject"] == "Астрономия"
    assert "текст задания" in message.last


async def test_an_empty_subject_does_not_move_the_flow_on(session):
    state = FakeState()

    message = FakeMessage(text="   ")
    await homework_typed_subject(message, state)

    assert "subject" not in state.data
    assert "название предмета" in message.last


# --------------------------------------------------------------------------
# Событие, целиком
#
# Ни один тест никогда не доходил здесь дальше выбора дня: `event_pick_kind`,
# `event_time` и `event_title` не назывались нигде. Это значит, что событие в
# классе никто не заводил ни разу, кроме как пальцем — а «Четверти» показали,
# чего стоит строка, которую не исполняли.
# --------------------------------------------------------------------------


async def test_an_event_is_added_by_walking_the_whole_flow(session, school_class):
    """Kind, then time, then title — the three steps a person actually takes."""
    state = FakeState(data={"date": MONDAY.isoformat()})

    callback = FakeCallback(message=FakeEditable())
    await event_pick_kind(callback, EventAction(action="pick_kind", value="trip"), state)
    assert state.data["kind"] == "trip"
    assert "12:30-13:15" in callback.message.last

    await event_time(FakeMessage(text="09:00-11:30"), state)
    assert state.data["start"] == "09:00:00" and state.data["end"] == "11:30:00"

    message = FakeMessage(text="Поездка в планетарий")
    await event_title(message, state, session, school_class, Role.EDITOR)

    event = await session.scalar(select(DayEvent))
    assert event.title == "Поездка в планетарий"
    assert event.date == MONDAY
    assert event.starts_at == time(9, 0) and event.ends_at == time(11, 30)
    assert event.kind is EventKind.TRIP
    # An excursion replaces lessons; that is the whole reason the flag exists.
    assert event.covers_lesson is True
    assert "09:00–11:30" in message.last
    assert state.cleared


async def test_a_meeting_does_not_replace_the_lessons_it_sits_beside(session, school_class):
    """`covers_lesson` is the difference between «вместо уроков» and «после».

    A parents' meeting in the evening must not blank the school day, and the
    only thing deciding that is a set literal in the handler.
    """
    state = FakeState(data={"date": MONDAY.isoformat(), "kind": "meeting",
                            "start": "18:00:00", "end": "19:00:00"})

    await event_title(FakeMessage(text="Родительское собрание"), state, session,
                      school_class, Role.EDITOR)

    event = await session.scalar(select(DayEvent))
    assert event.kind is EventKind.MEETING
    assert event.covers_lesson is False


async def test_time_that_is_not_a_range_is_refused_and_the_step_holds(session, school_class):
    """Wrong input must not advance the conversation.

    A flow that moves on regardless asks for a title and then saves an event
    with whatever times happened to be in the state — which for a fresh
    conversation is nothing at all, i.e. a `KeyError` two messages later.
    """
    state = FakeState(data={"date": MONDAY.isoformat(), "kind": "event"})

    for bad in ("завтра", "25:00-26:00", "13:15-12:30", "12:30", ""):
        message = FakeMessage(text=bad)
        await event_time(message, state)
        assert "Не понял время" in message.last, bad
        assert "start" not in state.data, bad


async def test_an_event_with_no_title_is_not_saved(session, school_class):
    state = FakeState(data={"date": MONDAY.isoformat(), "kind": "event",
                            "start": "10:00:00", "end": "11:00:00"})

    message = FakeMessage(text="   ")
    await event_title(message, state, session, school_class, Role.EDITOR)

    assert await session.scalar(select(DayEvent)) is None
    assert "название" in message.last
    # And the conversation stays where it was, so the next line typed is a title.
    assert not state.cleared


async def test_a_viewer_cannot_add_an_event(session, school_class):
    state = FakeState(data={"date": MONDAY.isoformat(), "kind": "event",
                            "start": "10:00:00", "end": "11:00:00"})

    await event_title(FakeMessage(text="взлом"), state, session, school_class, Role.VIEWER)

    assert await session.scalar(select(DayEvent)) is None
    assert state.cleared


# --------------------------------------------------------------------------
# Отмена урока и возврат к расписанию
#
# Обе операции разрушающие, обе были без единого теста.
# --------------------------------------------------------------------------


async def test_cancelling_a_lesson_writes_the_override_and_says_so(session, school_class):
    state = FakeState(data={"date": MONDAY.isoformat(), "index": 2})
    callback = FakeCallback(message=FakeEditable())

    await override_cancel(callback, state, session, school_class, Role.EDITOR)

    row = await session.scalar(select(LessonOverride))
    assert row.action is OverrideAction.CANCEL
    assert row.index == 2 and row.date == MONDAY
    assert "отменён" in callback.message.last
    assert state.cleared


async def test_putting_a_lesson_back_removes_the_override(session, school_class):
    cancel = FakeState(data={"date": MONDAY.isoformat(), "index": 2})
    await override_cancel(FakeCallback(message=FakeEditable()), cancel, session,
                          school_class, Role.EDITOR)
    assert await session.scalar(select(LessonOverride)) is not None

    state = FakeState(data={"date": MONDAY.isoformat(), "index": 2})
    callback = FakeCallback(message=FakeEditable())
    await override_clear(callback, state, session, school_class, Role.EDITOR)

    assert await session.scalar(select(LessonOverride)) is None
    assert "по расписанию" in callback.message.last


async def test_clearing_a_lesson_that_was_never_changed_is_not_an_error(session, school_class):
    """«Вернуть по расписанию» on an untouched lesson is a no-op, and has to
    read as one: the person pressed it because they were not sure."""
    state = FakeState(data={"date": MONDAY.isoformat(), "index": 5})
    callback = FakeCallback(message=FakeEditable())

    await override_clear(callback, state, session, school_class, Role.EDITOR)

    assert await session.scalar(select(LessonOverride)) is None
    assert "по расписанию" in callback.message.last
    # Nothing happened, so nothing is claimed in the log either.
    logged = list(await session.scalars(
        select(AuditEntry).where(AuditEntry.action == "override.clear")
    ))
    assert logged == []


async def test_a_viewer_can_neither_cancel_a_lesson_nor_restore_one(session, school_class):
    for handler in (override_cancel, override_clear):
        state = FakeState(data={"date": MONDAY.isoformat(), "index": 2})
        callback = FakeCallback(message=FakeEditable())

        await handler(callback, state, session, school_class, Role.VIEWER)

        assert callback.alerted
        assert await session.scalar(select(LessonOverride)) is None


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


# --------------------------------------------------------------------------
# Личный код на телефон и режим входа в класс
# --------------------------------------------------------------------------


class MarkupEditable(FakeEditable):
    """Keeps the keyboard as well as the text.

    The access page says which mode the class is in twice — in a sentence and
    on the button that changes it — and the two disagreeing is exactly the bug
    worth catching.
    """

    markup: Any = None

    async def edit_text(self, text: str, **kwargs: Any) -> None:
        self.markup = kwargs.get("reply_markup")
        self.replies.append(text)


def _shown_code(text: str) -> str:
    """The code out of the <code> block the reply shows it in."""
    found = re.search(r"<code>([^<]+)</code>", text)
    assert found is not None, text
    return found.group(1)


def _labels(markup) -> list[str]:
    return [button.text for row in markup.inline_keyboard for button in row]


async def test_a_member_gets_a_personal_code_for_their_own_phone(session, school_class):
    callback = FakeCallback(message=FakeEditable())

    await phone_code(callback, session, school_class, Role.VIEWER)

    invite = await session.scalar(select(DeviceInvite))
    assert invite.telegram_id == 42
    assert invite.class_id == school_class.id
    assert not invite.is_used
    assert await device_invites.find_live(session, _shown_code(callback.message.last)) is invite
    # The three things the reply has to say, so that nobody has to be told
    # them in the chat afterwards.
    assert str(device_invites.CODE_MINUTES) in callback.message.last
    assert "одного телефона" in callback.message.last
    assert "привязки не нужно" in callback.message.last
    assert not callback.alerted


async def test_asking_twice_leaves_one_live_code(session, school_class):
    """The older code is further up the chat, where somebody else scrolls
    past it — two live ones is two chances for that to matter."""
    first = FakeCallback(message=FakeEditable())
    await phone_code(first, session, school_class, Role.VIEWER)
    second = FakeCallback(message=FakeEditable())
    await phone_code(second, session, school_class, Role.VIEWER)

    assert len(list(await session.scalars(select(DeviceInvite)))) == 1
    assert await device_invites.find_live(session, _shown_code(first.message.last)) is None
    assert await device_invites.find_live(session, _shown_code(second.message.last)) is not None


async def test_somebody_outside_the_class_gets_no_code(session, school_class):
    callback = FakeCallback(message=FakeEditable())

    await phone_code(callback, session, school_class, None)

    assert callback.alerted
    assert await session.scalar(select(DeviceInvite)) is None


async def test_the_access_page_says_which_mode_the_class_is_in(session, school_class):
    callback = FakeCallback(message=MarkupEditable())
    await access_root(callback, session, school_class, Role.ADMIN)
    assert JOIN_MODE_TEXT[JoinMode.OPEN] in callback.message.last
    assert "🔒 Только по приглашениям" in _labels(callback.message.markup)

    school_class.join_mode = JoinMode.INVITE
    await session.commit()

    callback = FakeCallback(message=MarkupEditable())
    await access_root(callback, session, school_class, Role.ADMIN)
    assert JOIN_MODE_TEXT[JoinMode.INVITE] in callback.message.last
    assert "🔓 Вернуть вход по коду" in _labels(callback.message.markup)


def _wants(mode: JoinMode) -> AccessAction:
    return AccessAction(action="join_mode", value=mode.value)


async def test_an_admin_switches_the_mode_both_ways(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    await switch_join_mode(callback, _wants(JoinMode.INVITE), session, school_class, Role.ADMIN)

    assert school_class.join_mode is JoinMode.INVITE
    # The promise the confirmation has to make: switching takes nothing away.
    assert "уже подключены" in callback.message.last.lower()

    await switch_join_mode(callback, _wants(JoinMode.OPEN), session, school_class, Role.ADMIN)
    assert school_class.join_mode is JoinMode.OPEN
    assert "снова" in callback.message.last

    logged = list(
        await session.scalars(select(AuditEntry).where(AuditEntry.action == "access.join_mode"))
    )
    assert len(logged) == 2
    assert all(entry.telegram_id == 42 for entry in logged)


async def test_a_stale_page_cannot_undo_what_somebody_else_just_did(session, school_class):
    """The press carries the mode it wants, so it cannot mean «the other one».

    Two «👥 Доступ» pages open, both rendered while the class was open. One is
    used to switch to invitations. The other still shows «🔒 Только по
    приглашениям» — and pressing it must not hand the class code back to
    everybody who still has it, which is what a toggle would do.
    """
    stale = _wants(JoinMode.INVITE)

    first = FakeCallback(message=FakeEditable())
    await switch_join_mode(first, stale, session, school_class, Role.ADMIN)
    assert school_class.join_mode is JoinMode.INVITE

    second = FakeCallback(message=FakeEditable())
    await switch_join_mode(second, stale, session, school_class, Role.ADMIN)

    assert school_class.join_mode is JoinMode.INVITE
    assert "ничего не изменилось" in second.message.last
    # And the log records one change, not two.
    logged = list(
        await session.scalars(select(AuditEntry).where(AuditEntry.action == "access.join_mode"))
    )
    assert len(logged) == 1


async def test_a_button_from_a_build_that_spelled_the_modes_differently_is_refused(
    session, school_class
):
    callback = FakeCallback(message=FakeEditable())

    await switch_join_mode(
        callback, AccessAction(action="join_mode", value="whatever"), session, school_class,
        Role.ADMIN,
    )

    assert callback.alerted
    assert school_class.join_mode is JoinMode.OPEN
    assert await session.scalar(select(AuditEntry)) is None


async def test_an_editor_cannot_switch_the_mode(session, school_class):
    callback = FakeCallback(message=FakeEditable())

    await switch_join_mode(
        callback, _wants(JoinMode.INVITE), session, school_class, Role.EDITOR
    )

    assert callback.alerted
    assert school_class.join_mode is JoinMode.OPEN
    assert await session.scalar(select(AuditEntry)) is None


async def test_the_class_code_reply_stops_promising_a_join_in_invite_mode(
    session, school_class
):
    message = FakeMessage()
    await cmd_code(message, school_class, Role.ADMIN)
    assert "вводят в приложении" in message.last
    assert school_class.join_code in message.last

    school_class.join_mode = JoinMode.INVITE
    await session.commit()

    message = FakeMessage()
    await cmd_code(message, school_class, Role.ADMIN)
    # Still shown — it is dormant, not gone — but with the way in that works.
    assert school_class.join_code in message.last
    assert "ничего не открывает" in message.last
    assert "📱 Подключить телефон" in message.last


async def test_the_class_code_stays_admin_only(session, school_class):
    message = FakeMessage()
    await cmd_code(message, school_class, Role.EDITOR)
    assert school_class.join_code not in message.last


# --------------------------------------------------------------------------
# Доступ: то, что приходит из callback-данных
# --------------------------------------------------------------------------


def _role_press(target: str) -> CallbackQuery:
    """A RolePick press as Telegram delivers it, so the registered filters can
    be asked about it rather than guessed at."""
    return CallbackQuery(
        id="1",
        from_user=User(id=42, is_bot=False, first_name="Тестер"),
        chat_instance="chat",
        data=RolePick(role=Role.EDITOR.value, target=target).pack(),
    )


def _callback_filters(handler: Any) -> list[Any]:
    for registered in access_router.callback_query.handlers:
        if registered.callback is handler:
            return [
                one.callback
                for one in registered.filters
                if type(one.callback).__name__ == "CallbackQueryFilter"
            ]
    raise AssertionError("handler is not registered on the access router")


async def test_the_two_role_pickers_never_match_the_same_press():
    """Both flows put up a picker of roles and both send back a ``RolePick``;
    the member flow puts the member's id in ``target`` and the invite flow
    leaves it empty. While the invite handler matched *any* RolePick, an admin
    part-way through «пригласить по номеру» who pressed a role on an older
    «Новая роль» card had a phone invite created instead — and was told so in
    a sentence naming the number, not the person."""
    for filter_ in _callback_filters(invite_role):
        assert not await filter_(_role_press("99"))
        assert await filter_(_role_press(""))
    for filter_ in _callback_filters(apply_role):
        assert await filter_(_role_press("99"))
        assert not await filter_(_role_press(""))


async def test_a_crafted_member_id_is_refused_rather_than_raised(session, school_class):
    """Callback data is whatever the client sent. A bare ``int()`` on it does
    not refuse the press — it raises out of the handler, so nothing answers the
    callback and the button spins until Telegram gives up."""
    session.add(BotUser(telegram_id=99, class_id=school_class.id, role=Role.EDITOR))
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await apply_role(
        callback,
        SimpleNamespace(role=Role.VIEWER.value, target="взлом"),
        session,
        school_class,
        Role.ADMIN,
    )

    assert callback.alerted
    untouched = await session.scalar(select(BotUser).where(BotUser.telegram_id == 99))
    assert untouched.role is Role.EDITOR


async def test_a_role_that_is_not_a_role_is_refused_rather_than_raised(session, school_class):
    """``Role("начальник")`` is a ``ValueError``, and the picker's payload is
    as forgeable as the id beside it."""
    session.add(BotUser(telegram_id=99, class_id=school_class.id, role=Role.EDITOR))
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await apply_role(
        callback,
        SimpleNamespace(role="начальник", target="99"),
        session,
        school_class,
        Role.ADMIN,
    )

    assert callback.alerted
    untouched = await session.scalar(select(BotUser).where(BotUser.telegram_id == 99))
    assert untouched.role is Role.EDITOR


async def test_revoking_a_crafted_id_finds_nobody_rather_than_raising(session, school_class):
    session.add(BotUser(telegram_id=99, class_id=school_class.id, role=Role.EDITOR))
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await revoke(
        callback,
        SimpleNamespace(action="revoke", value="никогда"),
        session,
        school_class,
        Role.ADMIN,
    )

    assert callback.alerted
    assert await session.scalar(select(BotUser).where(BotUser.telegram_id == 99)) is not None


async def test_a_role_press_with_no_number_behind_it_asks_to_start_again(session, school_class):
    """FSM state lives in the database and outlives the process that wrote it,
    so the two screens of this flow can be separated by a redeploy. Reaching
    into the data for a key that is not there raised a ``KeyError`` where an
    admin only needed to be asked for the number again."""
    callback = FakeCallback(message=FakeEditable())
    state = FakeState(data={})

    await invite_role(
        callback,
        SimpleNamespace(role=Role.EDITOR.value, target=""),
        state,
        session,
        school_class,
        Role.ADMIN,
    )

    assert callback.alerted
    assert state.cleared
    assert await session.scalar(select(PhoneInvite)) is None


async def test_the_classes_a_person_is_in_come_back_in_a_stated_order(session, school_class):
    """Three things read this list as if its order meant something: the class
    the middleware falls back to, the one ``default_class_for`` picks, and the
    order of the «🔀 Сменить класс» buttons. An unordered ``SELECT`` promises
    none of it."""
    second = SchoolClass(name="9Б", join_code="SECOND01")
    session.add(second)
    await session.flush()
    session.add(BotUser(telegram_id=42, class_id=school_class.id, role=Role.ADMIN))
    session.add(BotUser(telegram_id=42, class_id=second.id, role=Role.VIEWER))
    await session.commit()

    memberships = await list_memberships(session, 42)
    assert [member.class_id for member in memberships] == [school_class.id, second.id]
    # And the same order twice, after a write that could have moved a row.
    memberships[1].full_name = "Тестер"
    await session.commit()
    again = await list_memberships(session, 42)
    assert [member.class_id for member in again] == [school_class.id, second.id]


# --------------------------------------------------------------------------
# Смещение дня из callback-данных
# --------------------------------------------------------------------------


def test_a_day_offset_that_is_not_a_date_comes_back_as_nothing():
    """``timedelta(days=999999999)`` is an OverflowError, not a far-away day,
    and the number arrives in callback data — whatever the client sent, not
    only what this bot put on a ‹ › button."""
    assert shift_days(date(2026, 9, 16), 1) == date(2026, 9, 17)
    assert shift_days(date(2026, 9, 16), -1) == date(2026, 9, 15)
    assert shift_days(date(2026, 9, 16), 999_999_999) is None
    assert shift_days(date(2026, 9, 16), -999_999_999) is None
    # The bounds are date's own: one day past the last one it can hold.
    assert shift_days(date(9999, 12, 31), 1) is None
    assert shift_weeks(date(2026, 9, 16), 1) == date(2026, 9, 23)
    assert shift_weeks(date(2026, 9, 16), 999_999_999) is None


async def test_paging_the_day_view_past_every_date_is_refused(session, school_class):
    callback = FakeCallback(message=FakeEditable())

    await show_day(
        callback,
        SimpleNamespace(offset=999_999_999),
        session,
        school_class,
        Role.VIEWER,
    )

    assert callback.alerted
    assert not callback.message.replies


async def test_paging_the_week_view_past_every_date_is_refused(session, school_class):
    text = await week_text(session, school_class, 999_999_999)
    assert "Такой недели нет" in text
