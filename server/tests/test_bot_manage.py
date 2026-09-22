"""The management layer: subjects, special days, devices, access requests,
import and switching class.

Same approach as ``test_bot_handlers.py``: the handlers are called directly
with lightweight stubs. aiogram does not enforce its types at call time, and
what is worth testing here is the permission checks, the re-scoping of ids and
the transactions - not Telegram's transport.
"""

from __future__ import annotations

import ast
import inspect
import re
import textwrap
from dataclasses import dataclass, field
from datetime import date as Date
from datetime import datetime, timedelta
from datetime import time as Time
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from sqlalchemy import select

from app.bot.handlers.manage import (
    NEED_ADMIN,
    NEED_EDITOR,
    NEED_OWNER,
    NO_ACCESS,
    audit_page,
    bells_create,
    bells_delete,
    bells_edit,
    bells_list,
    bells_make_default,
    bells_new_name,
    bells_new_rows,
    bells_rows_apply,
    calendar_card,
    calendar_rotate,
    class_delete_apply,
    class_delete_prompt,
    class_diary_bind,
    class_field_apply,
    class_field_prompt,
    class_root,
    class_switch,
    class_switch_to,
    cmd_bells,
    cmd_calendar,
    cmd_class,
    cmd_devices,
    cmd_export,
    cmd_find,
    cmd_holidays,
    cmd_import,
    cmd_link,
    cmd_log,
    cmd_request,
    cmd_stats,
    cmd_subjects,
    device_revoke,
    device_unlink,
    devices_list,
    holiday_add,
    holiday_bells,
    holiday_delete,
    holiday_kind,
    holiday_note,
    holiday_period_apply,
    holiday_period_kind,
    holiday_period_start,
    holiday_pick_date,
    holiday_typed_date,
    holidays_list,
    import_apply,
    import_cancel,
    import_preview,
    request_approve,
    request_decline,
    request_message,
    subject_add,
    subject_colour_pick,
    subject_colour_typed,
    subject_create,
    subject_delete,
    subject_field,
    subject_open,
    subject_rename,
    subject_short_name,
    subject_teacher,
    subjects_collect,
    subjects_list,
    term_edit_apply,
    term_edit_prompt,
    terms_list,
    terms_scheme,
)
from app.bot.handlers.manage import (
    router as manage_router,
)
from app.bot.handlers.timetable import timetable_apply
from app.bot.manage_keyboards import (
    DayKindAction,
    bells_list_keyboard,
    device_keyboard,
    holiday_list_keyboard,
    subject_list_keyboard,
)
from app.bot.manage_render import (
    BELLS_MAX,
    DEVICES_MAX,
    LIST_MAX,
    SUBJECTS_MAX,
    render_bells,
    render_devices,
    render_holidays,
    render_subjects,
    split_text,
    time_ago,
)
from app.bot.middlewares import active_class, prefs_key
from app.db import SessionLocal
from app.fsm_storage import DatabaseStorage
from app.models import (
    AccessRequest,
    BellPeriod,
    BellSchedule,
    BotUser,
    DayKind,
    DayOverride,
    DeviceToken,
    Homework,
    JoinMode,
    LessonOverride,
    OverrideAction,
    Role,
    SchoolClass,
    Subject,
    Term,
    TermKind,
    TimetableEntry,
    WeekParity,
)
from app.services import audit

# --------------------------------------------------------------------------
# Stubs
# --------------------------------------------------------------------------


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


class FakeBot:
    """Records what the bot was asked to send."""

    def __init__(self, failing: set[int] | None = None) -> None:
        self.sent: list[tuple[int, str]] = []
        self.failing = failing or set()

    async def send_message(self, chat_id: int, text: str, **_: Any) -> None:
        if chat_id in self.failing:
            raise RuntimeError("this one blocked the bot")
        self.sent.append((chat_id, text))


@dataclass
class FakeMessage:
    text: str | None = ""
    user_id: int = 42
    replies: list[str] = field(default_factory=list)
    bot: Any = None

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
    bot: Any = None

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



def a_day_ahead(school_class, days: int = 30) -> Date:
    """A date «🏖 Особые дни» will still be showing by the time it is drawn.

    The card is `DayOverride.date >= today` in the class's own zone and has no
    upper bound, so anything ahead is listed and anything behind is not. Seven
    tests below wrote 12 March 2027 out instead, and three of them asserted on
    the card: they were green until that morning and would then have gone red
    in CI with a diff naming no change at all, on a Friday, for nobody.
    """
    return datetime.now(school_class.tz).date() + timedelta(days=days)


def _command(args: str | None):
    """Just enough of aiogram's CommandObject for a handler that reads args."""
    return SimpleNamespace(command="x", args=args)


# --------------------------------------------------------------------------
# Callback prefixes
# --------------------------------------------------------------------------


def test_every_button_on_the_class_menu_is_one_its_payload_class_packed():
    """«⚙️ Класс» → «🕒 Часовой пояс» carried a hand-written
    ``"cls:timezone:"``. It was the right string, and nothing said so: a
    prefix renamed on ``ClassAction`` or a field added to it would have left
    a button whose press matches no filter, which is a spinner and then
    silence.
    """
    from aiogram.filters.callback_data import CallbackData

    from app.bot import calendar_keyboard, keyboards, manage_keyboards
    from app.bot.manage_keyboards import class_menu

    payloads = [
        value
        for module in (keyboards, manage_keyboards, calendar_keyboard)
        for value in vars(module).values()
        if isinstance(value, type)
        and issubclass(value, CallbackData)
        and value is not CallbackData
    ]
    menu = class_menu(is_owner=True, many_classes=True, pending=2, diary_bound=True)

    for row in menu.inline_keyboard:
        for button in row:
            if button.callback_data is None:
                continue
            assert any(
                _unpacks(payload, button.callback_data) for payload in payloads
            ), button.callback_data


def _unpacks(payload: type, data: str) -> bool:
    try:
        payload.unpack(data)
    except (TypeError, ValueError):
        return False
    return True


def test_management_callbacks_do_not_collide_with_the_everyday_ones():
    """A duplicate prefix would not fail a build - it would route one
    feature's button into another feature's handler."""
    from aiogram.filters.callback_data import CallbackData

    from app.bot import calendar_keyboard, keyboards, manage_keyboards

    prefixes: dict[str, str] = {}
    seen: set[type] = set()
    for module in (keyboards, manage_keyboards, calendar_keyboard):
        for name in dir(module):
            value = getattr(module, name)
            if (
                not isinstance(value, type)
                or not issubclass(value, CallbackData)
                or value is CallbackData
                # ``manage_keyboards`` imports Menu from ``keyboards``; the same
                # class under two names is not a collision.
                or value in seen
            ):
                continue
            seen.add(value)
            prefix = value.__prefix__
            assert prefix not in prefixes, (
                f"{value.__name__} reuses the prefix {prefix!r} of {prefixes[prefix]}"
            )
            prefixes[prefix] = value.__name__

    # And every payload packs: a value holding its own separator raises at the
    # moment the keyboard is built, which is a page that cannot be drawn.
    from app.bot.manage_keyboards import (
        bells_pick_keyboard,
        colour_keyboard,
        day_kind_keyboard,
        subject_card_keyboard,
    )

    subject_card_keyboard(12)
    colour_keyboard(12)
    day_kind_keyboard("2026-10-26")
    bells_pick_keyboard([SimpleNamespace(id=3, name="Сокращённое")], "2026-10-26")

    # The calendar builds a keyboard per flow out of three payload classes it
    # does not own, so a separator added to any of them surfaces here.
    from datetime import date

    from app.bot.calendar_keyboard import month_keyboard

    for flow in ("day", "hw", "ovr", "ev", "dk"):
        month_keyboard(flow, 2026, 10, date(2026, 10, 26))


# --------------------------------------------------------------------------
# The timetable: week parity
# --------------------------------------------------------------------------


async def test_a_parity_paste_makes_two_rows_for_one_lesson_number(session, school_class):
    message = FakeMessage(
        text="1. Алгебра, 214\n3. История [чис]\n3. Обществознание [знам]"
    )
    await timetable_apply(
        message, FakeState(data={"weekday": 2}), session, school_class, Role.ADMIN
    )

    third = list(
        await session.scalars(
            select(TimetableEntry).where(
                TimetableEntry.class_id == school_class.id,
                TimetableEntry.weekday == 2,
                TimetableEntry.index == 3,
            )
        )
    )
    assert {entry.parity for entry in third} == {WeekParity.ODD, WeekParity.EVEN}
    assert {entry.subject_name for entry in third} == {"История", "Обществознание"}
    assert "[чис]" in message.last


async def test_a_plain_line_replaces_both_parity_variants(session, school_class):
    await timetable_apply(
        FakeMessage(text="3. История [чис]\n3. Обществознание [знам]"),
        FakeState(data={"weekday": 2}),
        session,
        school_class,
        Role.ADMIN,
    )
    await timetable_apply(
        FakeMessage(text="3. История"),
        FakeState(data={"weekday": 2}),
        session,
        school_class,
        Role.ADMIN,
    )

    rows = list(
        await session.scalars(
            select(TimetableEntry).where(
                TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == 2
            )
        )
    )
    assert len(rows) == 1
    assert rows[0].parity is WeekParity.ANY


async def test_a_repeated_lesson_number_is_rejected_not_saved(session, school_class):
    """The same number twice with no parity would violate the unique key."""
    message = FakeMessage(text="1. Алгебра\n1. Физика")
    await timetable_apply(
        message, FakeState(data={"weekday": 4}), session, school_class, Role.ADMIN
    )

    rows = list(
        await session.scalars(
            select(TimetableEntry).where(
                TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == 4
            )
        )
    )
    assert [row.subject_name for row in rows] == ["Алгебра"]
    assert "Не разобрал" in message.last


# --------------------------------------------------------------------------
# Subjects
# --------------------------------------------------------------------------


async def test_renaming_a_subject_moves_the_timetable_homework_and_overrides(
    session, school_class
):
    subject = Subject(class_id=school_class.id, name="Алгебра")
    session.add(subject)
    session.add(
        Homework(
            class_id=school_class.id,
            due_date=Date(2026, 9, 14),
            subject_name="Алгебра",
            text="№ 42",
        )
    )
    session.add(
        LessonOverride(
            class_id=school_class.id,
            date=Date(2026, 9, 14),
            index=1,
            action=OverrideAction.REPLACE,
            subject_name="Алгебра",
        )
    )
    await session.commit()

    message = FakeMessage(text="Математика")
    state = FakeState(data={"subject_id": subject.id})
    await subject_rename(message, state, session, school_class, Role.ADMIN)

    assert subject.name == "Математика"
    entry = await session.scalar(
        select(TimetableEntry).where(
            TimetableEntry.class_id == school_class.id, TimetableEntry.index == 1
        )
    )
    assert entry.subject_name == "Математика"
    homework = await session.scalar(select(Homework))
    assert homework.subject_name == "Математика"
    override = await session.scalar(select(LessonOverride))
    assert override.subject_name == "Математика"
    # The whole sentence, not «"3" is somewhere in it»: the message also
    # carries both names, and «Математика» has no digit in it but a date or a
    # lesson number in some future wording would — a bare `"3" in` passes on
    # any of them. Three is one timetable row, one homework row, one override.
    assert message.last.endswith("Переименовано в расписании, заданиях и заменах: 3.")


async def test_renaming_a_subject_is_refused_for_an_editor(session, school_class):
    subject = Subject(class_id=school_class.id, name="Алгебра")
    session.add(subject)
    await session.commit()

    message = FakeMessage(text="Математика")
    state = FakeState(data={"subject_id": subject.id})
    await subject_rename(message, state, session, school_class, Role.EDITOR)

    assert subject.name == "Алгебра"
    assert message.replies == []
    assert state.cleared


async def test_a_subject_id_from_another_class_is_not_found(session, school_class):
    other = SchoolClass(name="9Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    stranger = Subject(class_id=other.id, name="Химия")
    session.add(stranger)
    await session.commit()

    message = FakeMessage(text="Взлом")
    await subject_rename(
        message, FakeState(data={"subject_id": stranger.id}), session, school_class, Role.ADMIN
    )

    assert stranger.name == "Химия"
    assert "удалён" in message.last


# --------------------------------------------------------------------------
# Special days
# --------------------------------------------------------------------------


async def test_a_holiday_period_is_capped(session, school_class):
    message = FakeMessage(text="01.01.2027-31.12.2027")
    await holiday_period_apply(message, FakeState(), session, school_class, Role.ADMIN)

    assert "слишком много" in message.last
    assert await session.scalar(select(DayOverride)) is None


async def test_a_holiday_period_marks_every_day_inside_it(session, school_class):
    message = FakeMessage(text="02.01.2027-06.01.2027")
    await holiday_period_apply(message, FakeState(), session, school_class, Role.ADMIN)

    days = list(await session.scalars(select(DayOverride).order_by(DayOverride.date)))
    assert [day.date for day in days] == [Date(2027, 1, day) for day in range(2, 7)]
    # Anchored on the whole count, not on «5 дней» somewhere inside it:
    # `plural()` already contains the number, and an `f"{n} {plural(n, …)}"`
    # slip prints «5 5 дней» — which a substring check reads as correct.
    assert re.search(r"(?<!\d\s)\b5 дней\b", message.replies[0])
    assert "5 5" not in message.replies[0]


async def test_an_editor_cannot_mark_a_whole_period(session, school_class):
    message = FakeMessage(text="02.01.2027-06.01.2027")
    state = FakeState()
    await holiday_period_apply(message, state, session, school_class, Role.EDITOR)

    assert await session.scalar(select(DayOverride)) is None
    assert message.replies == []
    assert state.cleared


# --------------------------------------------------------------------------
# Devices
# --------------------------------------------------------------------------


async def test_revoking_a_device_marks_the_row(session, school_class):
    device = DeviceToken(
        token_hash="hash-1", class_id=school_class.id, device_name="Pixel 8"
    )
    session.add(device)
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await device_revoke(
        callback,
        SimpleNamespace(action="revoke", value=str(device.id)),
        session,
        school_class,
        Role.ADMIN,
    )

    await session.refresh(device)
    assert device.revoked is True
    assert not callback.alerted


async def test_a_device_of_another_class_cannot_be_revoked(session, school_class):
    other = SchoolClass(name="9Б", join_code="OTHER2")
    session.add(other)
    await session.flush()
    device = DeviceToken(token_hash="hash-2", class_id=other.id, device_name="Чужой")
    session.add(device)
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await device_revoke(
        callback,
        SimpleNamespace(action="revoke", value=str(device.id)),
        session,
        school_class,
        Role.ADMIN,
    )

    await session.refresh(device)
    assert device.revoked is False
    assert callback.alerted


# --------------------------------------------------------------------------
# Access requests
# --------------------------------------------------------------------------


async def test_a_request_is_created_once_and_replaced_on_a_second_try(session, school_class):
    session.add(BotUser(telegram_id=55, class_id=school_class.id, role=Role.VIEWER))
    await session.commit()

    message = FakeMessage(text="", user_id=55)
    await cmd_request(
        message, _command("пустите"), FakeState(), session, school_class, Role.VIEWER
    )
    await cmd_request(
        message, _command("очень надо"), FakeState(), session, school_class, Role.VIEWER
    )

    rows = list(await session.scalars(select(AccessRequest)))
    assert len(rows) == 1
    assert rows[0].status == "pending"
    assert rows[0].requested_role is Role.EDITOR
    assert rows[0].message == "очень надо"


async def test_an_editor_has_nothing_to_request(session, school_class):
    message = FakeMessage(text="", user_id=55)
    await cmd_request(
        message, _command("хочу"), FakeState(), session, school_class, Role.EDITOR
    )

    assert await session.scalar(select(AccessRequest)) is None
    assert "Редактор" in message.last


async def test_approving_a_request_grants_the_editor_role(session, school_class):
    session.add(BotUser(telegram_id=55, class_id=school_class.id, role=Role.VIEWER))
    request = AccessRequest(
        class_id=school_class.id,
        telegram_id=55,
        requested_role=Role.EDITOR,
        status="pending",
    )
    session.add(request)
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await request_approve(
        callback,
        SimpleNamespace(action="approve", value=str(request.id)),
        session,
        school_class,
        Role.ADMIN,
    )

    member = await session.scalar(select(BotUser).where(BotUser.telegram_id == 55))
    assert member.role is Role.EDITOR
    await session.refresh(request)
    assert request.status == "approved"
    assert not callback.alerted


async def test_approving_is_refused_when_the_actor_cannot_grant_that_role(session, school_class):
    """An admin may not hand out admin - the same rule «👥 Доступ» applies."""
    session.add(BotUser(telegram_id=55, class_id=school_class.id, role=Role.VIEWER))
    request = AccessRequest(
        class_id=school_class.id,
        telegram_id=55,
        requested_role=Role.ADMIN,
        status="pending",
    )
    session.add(request)
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await request_approve(
        callback,
        SimpleNamespace(action="approve", value=str(request.id)),
        session,
        school_class,
        Role.ADMIN,
    )

    member = await session.scalar(select(BotUser).where(BotUser.telegram_id == 55))
    assert member.role is Role.VIEWER
    await session.refresh(request)
    assert request.status == "pending"
    assert callback.alerted


async def test_a_viewer_cannot_approve_a_request(session, school_class):
    request = AccessRequest(
        class_id=school_class.id,
        telegram_id=55,
        requested_role=Role.EDITOR,
        status="pending",
    )
    session.add(request)
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await request_approve(
        callback,
        SimpleNamespace(action="approve", value=str(request.id)),
        session,
        school_class,
        Role.VIEWER,
    )

    await session.refresh(request)
    assert request.status == "pending"
    assert callback.alerted


# --------------------------------------------------------------------------
# Search
# --------------------------------------------------------------------------


async def test_find_escapes_html_in_what_it_found(session, school_class):
    session.add(
        Homework(
            class_id=school_class.id,
            due_date=Date.today(),
            subject_name="<Алгебра>",
            text="<b>параграф</b> 12",
        )
    )
    await session.commit()

    message = FakeMessage(text="")
    await cmd_find(message, _command("параграф"), session, school_class, Role.VIEWER)

    assert "&lt;b&gt;параграф&lt;/b&gt;" in message.last
    assert "<b>параграф</b>" not in message.last
    assert "&lt;Алгебра&gt;" in message.last


async def test_find_without_an_argument_explains_itself(session, school_class):
    message = FakeMessage(text="")
    await cmd_find(message, _command(None), session, school_class, Role.VIEWER)

    assert "/find" in message.last


async def test_find_ignores_case_and_looks_only_at_the_last_month(session, school_class):
    """Case folding is SQL's ``lower()``, which on SQLite is ASCII-only — so
    the case test uses a Latin word, the way the database really behaves."""
    today = Date.today()
    session.add(
        Homework(
            class_id=school_class.id,
            due_date=today,
            subject_name="Физика",
            text="Lab Work 7",
        )
    )
    session.add(
        Homework(
            class_id=school_class.id,
            due_date=today - timedelta(days=200),
            subject_name="Физика",
            text="lab work давно",
        )
    )
    await session.commit()

    message = FakeMessage(text="")
    await cmd_find(message, _command("LAB work"), session, school_class, Role.VIEWER)

    assert "Lab Work 7" in message.last
    assert "давно" not in message.last


# --------------------------------------------------------------------------
# Import
# --------------------------------------------------------------------------


PASTE = "== Вторник ==\n1. Химия, 118\n2. Биология\n\n== Среда ==\nерунда\n1. Алгебра"


async def test_import_previews_and_only_then_applies(session, school_class):
    message = FakeMessage(text=PASTE)
    state = FakeState()
    await import_preview(message, state, school_class, Role.ADMIN)

    assert "Вторник" in message.last
    assert "ерунда" in message.last
    # Nothing written yet.
    assert (
        await session.scalar(
            select(TimetableEntry).where(TimetableEntry.weekday == 2)
        )
    ) is None

    callback = FakeCallback(message=FakeEditable())
    await import_apply(callback, state, session, school_class, Role.ADMIN)

    tuesday = list(
        await session.scalars(
            select(TimetableEntry)
            .where(TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == 2)
            .order_by(TimetableEntry.index)
        )
    )
    assert [entry.subject_name for entry in tuesday] == ["Химия", "Биология"]
    assert tuesday[0].room == "118"
    assert state.cleared


async def test_import_leaves_untouched_weekdays_alone(session, school_class):
    """Monday comes from the fixture and the paste never mentions it."""
    state = FakeState()
    await import_preview(FakeMessage(text=PASTE), state, school_class, Role.ADMIN)
    await import_apply(
        FakeCallback(message=FakeEditable()), state, session, school_class, Role.ADMIN
    )

    monday = list(
        await session.scalars(
            select(TimetableEntry).where(
                TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == 1
            )
        )
    )
    assert len(monday) == 3


async def test_an_editor_cannot_import(session, school_class):
    state = FakeState()
    message = FakeMessage(text=PASTE)
    await import_preview(message, state, school_class, Role.EDITOR)

    assert message.replies == []
    assert state.cleared


# --------------------------------------------------------------------------
# Deleting a class
# --------------------------------------------------------------------------


async def test_deleting_a_class_needs_the_name_typed_back_exactly(session, school_class):
    class_id = school_class.id
    message = FakeMessage(text="9а")
    await class_delete_apply(message, FakeState(), session, school_class, Role.OWNER)

    assert await session.get(SchoolClass, class_id) is not None
    assert "не совпало" in message.last


async def test_deleting_a_class_works_with_the_exact_name(session, school_class):
    class_id = school_class.id
    message = FakeMessage(text="9А")
    await class_delete_apply(message, FakeState(), session, school_class, Role.OWNER)

    assert await session.get(SchoolClass, class_id) is None
    assert "удалён" in message.last


async def test_an_admin_cannot_delete_the_class(session, school_class):
    class_id = school_class.id
    message = FakeMessage(text="9А")
    state = FakeState()
    await class_delete_apply(message, state, session, school_class, Role.ADMIN)

    assert await session.get(SchoolClass, class_id) is not None
    assert message.replies == []
    assert state.cleared


# --------------------------------------------------------------------------
# Switching class
# --------------------------------------------------------------------------


async def _two_classes(session, school_class, telegram_id: int) -> SchoolClass:
    second = SchoolClass(name="9Б", join_code="SECOND")
    session.add(second)
    await session.flush()
    session.add(BotUser(telegram_id=telegram_id, class_id=school_class.id, role=Role.ADMIN))
    session.add(BotUser(telegram_id=telegram_id, class_id=second.id, role=Role.ADMIN))
    await session.commit()
    return second


async def test_the_middleware_honours_a_stored_class_preference(session, school_class):
    second = await _two_classes(session, school_class, 77)
    storage = DatabaseStorage(SessionLocal)
    await storage.set_data(prefs_key(77), {"class_id": second.id})

    active = await active_class(session, 77)
    assert active.id == second.id


async def test_the_middleware_ignores_a_preference_for_a_class_you_left(session, school_class):
    second = await _two_classes(session, school_class, 77)
    storage = DatabaseStorage(SessionLocal)
    await storage.set_data(prefs_key(77), {"class_id": second.id})

    membership = await session.scalar(
        select(BotUser).where(BotUser.telegram_id == 77, BotUser.class_id == second.id)
    )
    await session.delete(membership)
    await session.commit()

    active = await active_class(session, 77)
    assert active.id == school_class.id


async def test_without_a_preference_the_first_membership_wins(session, school_class):
    await _two_classes(session, school_class, 78)

    active = await active_class(session, 78)
    assert active.id == school_class.id


# --------------------------------------------------------------------------
# Notifying the administrators
# --------------------------------------------------------------------------


async def test_a_request_reaches_every_admin_and_survives_a_blocked_one(session, school_class):
    session.add(BotUser(telegram_id=55, class_id=school_class.id, role=Role.VIEWER))
    session.add(BotUser(telegram_id=10, class_id=school_class.id, role=Role.ADMIN))
    session.add(BotUser(telegram_id=11, class_id=school_class.id, role=Role.OWNER))
    session.add(BotUser(telegram_id=12, class_id=school_class.id, role=Role.EDITOR))
    await session.commit()

    bot = FakeBot(failing={11})
    message = FakeMessage(text="", user_id=55, bot=bot)
    await cmd_request(
        message, _command("возьмите меня"), FakeState(), session, school_class, Role.VIEWER
    )

    # The editor is not an admin and hears nothing; the owner blocked the bot
    # and that is nobody else's problem.
    assert [chat_id for chat_id, _ in bot.sent] == [10]
    assert "возьмите меня" in bot.sent[0][1]
    assert await session.scalar(select(AccessRequest)) is not None


async def test_a_decision_is_told_to_the_requester(session, school_class):
    session.add(BotUser(telegram_id=55, class_id=school_class.id, role=Role.VIEWER))
    request = AccessRequest(
        class_id=school_class.id,
        telegram_id=55,
        requested_role=Role.EDITOR,
        status="pending",
    )
    session.add(request)
    await session.commit()

    bot = FakeBot()
    callback = FakeCallback(message=FakeEditable(), bot=bot)
    await request_approve(
        callback,
        SimpleNamespace(action="approve", value=str(request.id)),
        session,
        school_class,
        Role.ADMIN,
    )

    assert bot.sent and bot.sent[0][0] == 55
    assert "Редактор" in bot.sent[0][1]


# --------------------------------------------------------------------------
# Switching class from the bot
# --------------------------------------------------------------------------


async def test_switching_class_is_remembered_for_the_next_update(session, school_class):
    second = await _two_classes(session, school_class, 79)

    callback = FakeCallback(user_id=79, message=FakeEditable())
    await class_switch_to(
        callback,
        SimpleNamespace(action="switch_to", value=str(second.id)),
        FakeState(),
        session,
        school_class,
        Role.ADMIN,
    )

    assert (await active_class(session, 79)).id == second.id


async def test_switching_to_a_class_you_are_not_in_is_refused(session, school_class):
    stranger = SchoolClass(name="9В", join_code="THIRD1")
    session.add(stranger)
    session.add(BotUser(telegram_id=80, class_id=school_class.id, role=Role.ADMIN))
    await session.commit()

    callback = FakeCallback(user_id=80, message=FakeEditable())
    await class_switch_to(
        callback,
        SimpleNamespace(action="switch_to", value=str(stranger.id)),
        FakeState(),
        session,
        school_class,
        Role.ADMIN,
    )

    assert callback.alerted
    assert (await active_class(session, 80)).id == school_class.id


# --------------------------------------------------------------------------
# Bells
# --------------------------------------------------------------------------


async def test_a_bad_bells_paste_never_erases_the_stored_schedule(session, school_class):
    before = len(
        list(
            await session.scalars(
                select(BellPeriod).where(
                    BellPeriod.schedule_id == school_class.bell_schedule_id
                )
            )
        )
    )
    message = FakeMessage(text="совершенно не расписание")
    await bells_rows_apply(
        message,
        FakeState(data={"schedule_id": school_class.bell_schedule_id}),
        session,
        school_class,
        Role.ADMIN,
    )

    after = list(
        await session.scalars(
            select(BellPeriod).where(BellPeriod.schedule_id == school_class.bell_schedule_id)
        )
    )
    assert len(after) == before
    assert "не изменены" in message.last


async def test_the_class_default_schedule_cannot_be_deleted(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    await bells_delete(
        callback,
        SimpleNamespace(action="delete", value=str(school_class.bell_schedule_id)),
        session,
        school_class,
        Role.ADMIN,
    )

    assert callback.alerted
    assert await session.get(BellSchedule, school_class.bell_schedule_id) is not None


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------


def test_time_ago_reads_like_russian():
    now = datetime(2026, 9, 12, 12, 0)
    assert time_ago(None) == "никогда"
    assert time_ago(now - timedelta(seconds=30), now) == "только что"
    assert time_ago(now - timedelta(minutes=5), now) == "5 мин назад"
    assert time_ago(now - timedelta(hours=2), now) == "2 ч назад"
    assert time_ago(now - timedelta(days=3), now) == "3 дн назад"
    # Past a month a relative figure stops meaning anything.
    assert time_ago(datetime(2026, 1, 5, 9, 0), now) == "05.01.2026"


def test_a_long_export_is_split_on_line_boundaries():
    body = "\n".join(f"{index}. Предмет" for index in range(1, 500))
    parts = split_text(body, limit=200)

    assert len(parts) > 1
    assert all(len(part) <= 200 for part in parts)
    # Nothing is lost and nothing is invented in the seams.
    assert "\n".join(parts) == body


async def test_every_management_page_renders(session, school_class):
    """One pass over every page an admin can open.

    These are long HTML strings built from database rows; the failure mode
    worth catching here is not a wrong word but a page that raises - a button
    whose payload will not pack, a keyboard built from an empty list, a name
    that is ``None``.
    """
    session.add(BotUser(telegram_id=42, class_id=school_class.id, role=Role.OWNER))
    session.add(
        DeviceToken(
            token_hash="hash-view",
            class_id=school_class.id,
            device_name="Pixel 8",
            telegram_id=42,
        )
    )
    session.add(Subject(class_id=school_class.id, name="Алгебра", color="#5B6ABF"))
    await session.commit()

    for page in (cmd_subjects, cmd_holidays, cmd_bells, cmd_devices, cmd_log, cmd_class):
        message = FakeMessage()
        await page(message, FakeState(), session, school_class, Role.OWNER)
        assert message.replies, page.__name__

    message = FakeMessage()
    await cmd_devices(message, FakeState(), session, school_class, Role.OWNER)
    assert "Pixel 8" in message.last
    assert "Владелец" in message.last

    message = FakeMessage()
    await cmd_export(message, FakeState(), session, school_class, Role.OWNER)
    assert "== Понедельник ==" in message.last

    message = FakeMessage()
    await cmd_calendar(message, FakeState(), session, school_class, Role.VIEWER)
    assert "PUBLIC_BASE_URL" in message.last


async def test_the_terms_card_draws(session, school_class):
    """«🗓 Четверти» crashed on every press, in production, on `main`.

    `_terms_card` printed `term.days` — a property that existed on `TermView`,
    a flattened copy of a term that nothing has ever constructed, and not on
    the `Term` the service actually returns. So the line was written against a
    type that never reaches it, and the only way to find out was to press the
    button: `AttributeError: 'Term' object has no attribute 'days'`, an error
    dialog, and no card.

    Nothing caught it because `tests/test_terms.py` opens with «the rules, not
    the rendering» — a reasonable split that left the rendering with no tests
    at all. This is that test. It asserts the numbers, not just that the call
    returns, because a card that draws «0 дн.» is as wrong as one that throws.
    """
    callback = FakeCallback(message=FakeEditable())

    await terms_list(callback, FakeState(), session, school_class, Role.ADMIN)

    card = callback.message.last
    assert "четверти" in card
    # Four of them, each with a length that counts both ends: a term running
    # 01.09 to 01.09 is one day of school, not nought.
    assert card.count("дн.") == 4
    assert " 0 дн." not in card
    assert "1." in card and "4." in card


async def test_a_term_is_as_long_as_both_its_ends(session, school_class):
    """The property the card reads, pinned where it now lives.

    It moved onto the model precisely because the copy that had it was dead:
    two types for one term is how a renderer ends up written against the one it
    will never be handed.
    """
    from datetime import date

    from app.services import terms as terms_service

    rows = await terms_service.ensure(session, school_class, 2026)
    await session.commit()

    for term in rows:
        assert term.days == (term.ends_on - term.starts_on).days + 1
        assert term.days > 0

    one_day = rows[0]
    one_day.starts_on = date(2026, 9, 1)
    one_day.ends_on = date(2026, 9, 1)
    assert one_day.days == 1


async def test_the_class_card_names_the_way_into_the_class_it_is_really_in(
    session, school_class
):
    """The line under the join code has to say what that code is worth.

    «Тип класса: публичный» stood here and decided nothing — no code read the
    flag, so an admin who «закрыл» the class had closed nothing while the card
    said otherwise. This line is read off ``join_mode``, which the join
    endpoint actually obeys, so the card cannot drift from the behaviour again.
    """
    message = FakeMessage()
    await cmd_class(message, FakeState(), session, school_class, Role.ADMIN)
    assert "🔓 Подключение телефонов: по коду класса" in message.last
    assert "Тип класса" not in message.last

    school_class.join_mode = JoinMode.INVITE
    await session.commit()

    message = FakeMessage()
    await cmd_class(message, FakeState(), session, school_class, Role.ADMIN)
    # The padlock follows the state: an admin skimming the card reads the icon.
    assert "🔒 Подключение телефонов: только по личным приглашениям" in message.last


async def test_a_full_log_page_still_fits_in_one_telegram_message(session, school_class):
    """Thirty entries of five hundred characters is fifteen thousand — well
    past the 4096 Telegram accepts, and the page is built from stored text."""
    for number in range(30):
        await audit.record(
            session,
            school_class.id,
            42,
            "test",
            f"{number} " + "очень длинная строка изменения " * 16,
        )
    await session.commit()

    message = FakeMessage()
    await cmd_log(message, FakeState(), session, school_class, Role.ADMIN)

    assert len(message.last) < 4096
    assert "и ещё" in message.last


async def test_search_folds_case_for_cyrillic_too(session, school_class):
    """SQLite's own ``lower()`` folds ASCII and stops; `app.db` replaces it, so
    the search works the same way for the developer as for the class."""
    from datetime import date, timedelta

    from app.bot.handlers.manage import cmd_find
    from app.models import Homework

    today = date.today()
    session.add(
        Homework(
            class_id=school_class.id,
            due_date=today + timedelta(days=1),
            subject_name="Алгебра",
            text="Параграф 12",
        )
    )
    await session.commit()

    message = FakeMessage(text="/find АЛГЕБРА")
    await cmd_find(
        message,
        _command("АЛГЕБРА"),
        session,
        school_class,
        Role.VIEWER,
    )
    assert "Алгебра" in message.last


# --------------------------------------------------------------------------
# A subject card
# --------------------------------------------------------------------------


async def _subject(session, school_class, **fields) -> Subject:
    subject = Subject(class_id=school_class.id, **fields)
    session.add(subject)
    await session.commit()
    return subject


async def test_the_subject_card_shows_every_field_it_stores(session, school_class):
    """Four stored columns, four lines. A card that drew the name and then a
    dash where the teacher is would look exactly like a subject nobody has
    filled in yet."""
    subject = await _subject(
        session,
        school_class,
        name="Алгебра",
        short_name="Алг",
        teacher="Иванова И. И.",
        color="#5B6ABF",
    )

    callback = FakeCallback(message=FakeEditable())
    await subject_open(
        callback,
        SimpleNamespace(action="open", value=str(subject.id)),
        FakeState(),
        session,
        school_class,
        Role.ADMIN,
    )

    card = callback.message.last
    assert "Алгебра" in card
    assert "Сокращение: Алг" in card
    assert "Учитель: Иванова И. И." in card
    assert "■ #5B6ABF" in card


async def test_a_subject_with_nothing_filled_in_draws_dashes_not_none(session, school_class):
    """``None`` printed straight would read «Учитель: None» — an English word
    in a Russian card, and one that looks like somebody's answer."""
    subject = await _subject(session, school_class, name="Физика")

    callback = FakeCallback(message=FakeEditable())
    await subject_open(
        callback,
        SimpleNamespace(action="open", value=str(subject.id)),
        FakeState(),
        session,
        school_class,
        Role.ADMIN,
    )

    card = callback.message.last
    assert "Сокращение: —" in card
    assert "Учитель: —" in card
    assert "Цвет: —" in card
    assert "None" not in card


async def test_picking_a_preset_colour_writes_it_and_the_card_shows_it(session, school_class):
    subject = await _subject(session, school_class, name="Алгебра")

    callback = FakeCallback(message=FakeEditable())
    await subject_colour_pick(
        callback,
        SimpleNamespace(action="colour", value=f"{subject.id}:3E8E7E"),
        FakeState(),
        session,
        school_class,
        Role.ADMIN,
    )

    await session.refresh(subject)
    assert subject.color == "#3E8E7E"
    assert "■ #3E8E7E" in callback.message.last


async def test_a_typed_colour_is_stored_in_the_one_spelling(session, school_class):
    """«5b6abf», «#5b6abf» and «#5B6ABF» are one colour. The app matches the
    stored string, so two spellings of one colour are two colours to it."""
    subject = await _subject(session, school_class, name="Алгебра")

    message = FakeMessage(text="5b6abf")
    await subject_colour_typed(
        message, FakeState(data={"subject_id": subject.id}), session, school_class, Role.ADMIN
    )

    await session.refresh(subject)
    assert subject.color == "#5B6ABF"
    assert "■ #5B6ABF" in message.last


async def test_a_colour_that_is_not_a_colour_keeps_the_prompt_open(session, school_class):
    subject = await _subject(session, school_class, name="Алгебра", color="#5B6ABF")

    message = FakeMessage(text="синий")
    state = FakeState(data={"subject_id": subject.id})
    await subject_colour_typed(message, state, session, school_class, Role.ADMIN)

    await session.refresh(subject)
    assert subject.color == "#5B6ABF"
    # The example, not a bare refusal: the admin has to know what to type next.
    assert "#5B6ABF" in message.last
    assert not state.cleared


async def test_a_dash_clears_the_colour_and_the_teacher(session, school_class):
    subject = await _subject(
        session, school_class, name="Алгебра", teacher="Иванова И. И.", color="#5B6ABF"
    )

    await subject_colour_typed(
        FakeMessage(text="-"),
        FakeState(data={"subject_id": subject.id}),
        session,
        school_class,
        Role.ADMIN,
    )
    message = FakeMessage(text="—")
    await subject_teacher(
        message, FakeState(data={"subject_id": subject.id}), session, school_class, Role.ADMIN
    )

    await session.refresh(subject)
    assert subject.color is None
    assert subject.teacher is None
    assert "Учитель: —" in message.last
    assert "Цвет: —" in message.last


async def test_a_short_name_longer_than_the_column_is_cut_not_refused(session, school_class):
    """The column is 16 characters. A refusal here would be a wall between an
    admin and a field whose whole purpose is to be short."""
    subject = await _subject(session, school_class, name="Алгебра")

    message = FakeMessage(text="Очень длинное сокращение")
    await subject_short_name(
        message, FakeState(data={"subject_id": subject.id}), session, school_class, Role.ADMIN
    )

    await session.refresh(subject)
    assert subject.short_name == "Очень длинное со"
    assert len(subject.short_name) == 16


async def test_the_colour_prompt_names_the_colour_the_subject_has_now(session, school_class):
    subject = await _subject(session, school_class, name="Алгебра", color="#C4534A")

    callback = FakeCallback(message=FakeEditable())
    await subject_field(
        callback,
        SimpleNamespace(action="field", value=f"colour:{subject.id}"),
        FakeState(),
        session,
        school_class,
        Role.ADMIN,
    )

    assert "■ #C4534A" in callback.message.last
    assert "Алгебра" in callback.message.last


async def test_an_unknown_field_name_is_refused_rather_than_guessed(session, school_class):
    subject = await _subject(session, school_class, name="Алгебра")

    callback = FakeCallback(message=FakeEditable())
    await subject_field(
        callback,
        SimpleNamespace(action="field", value=f"кабинет:{subject.id}"),
        FakeState(),
        session,
        school_class,
        Role.ADMIN,
    )

    assert callback.alerted
    assert callback.message.replies == []


async def test_a_second_subject_with_the_same_name_in_another_case_is_not_created(
    session, school_class
):
    """«физика» and «Физика» are one subject, and the second spelling opens the
    first rather than founding a rival beside it."""
    await _subject(session, school_class, name="Физика")

    message = FakeMessage(text="физика")
    state = FakeState()
    await subject_create(message, state, session, school_class, Role.ADMIN)

    rows = list(
        await session.scalars(select(Subject).where(Subject.class_id == school_class.id))
    )
    assert [row.name for row in rows] == ["Физика"]
    assert "Физика" in message.last
    assert "уже есть" in message.last
    assert state.cleared


async def test_a_new_subject_is_created_and_its_card_drawn(session, school_class):
    message = FakeMessage(text="  Химия  ")
    await subject_create(message, FakeState(), session, school_class, Role.ADMIN)

    subject = await session.scalar(select(Subject).where(Subject.name == "Химия"))
    assert subject is not None
    assert "Химия" in message.last
    assert "Сокращение: —" in message.last


async def test_renaming_to_a_name_the_class_already_uses_is_refused(session, school_class):
    """Two subjects of one name would break the unique key, and the paste that
    adopts names from the timetable would then have two rows to choose from."""
    algebra = await _subject(session, school_class, name="Алгебра")
    await _subject(session, school_class, name="Геометрия")

    message = FakeMessage(text="Геометрия")
    state = FakeState(data={"subject_id": algebra.id})
    await subject_rename(message, state, session, school_class, Role.ADMIN)

    await session.refresh(algebra)
    assert algebra.name == "Алгебра"
    assert "уже есть" in message.last
    assert not state.cleared


async def test_a_subject_the_timetable_still_uses_cannot_be_deleted(session, school_class):
    """The template stores the name as well as the link, so deleting the
    dictionary row only loses the colour and the teacher — and the next read
    adopts the name straight back."""
    algebra = await _subject(session, school_class, name="Алгебра", color="#5B6ABF")

    callback = FakeCallback(message=FakeEditable())
    await subject_delete(
        callback,
        SimpleNamespace(action="delete", value=str(algebra.id)),
        FakeState(),
        session,
        school_class,
        Role.ADMIN,
    )

    assert await session.get(Subject, algebra.id) is not None
    assert callback.alerted
    # The alert names the count, because «уберите из расписания» is only
    # actionable if you know how many lessons that is.
    assert "стоит в расписании: 1 урок." in callback.answers[0][0]


async def test_a_subject_nothing_teaches_goes_in_one_tap(session, school_class):
    spare = await _subject(session, school_class, name="Астрономия")

    callback = FakeCallback(message=FakeEditable())
    await subject_delete(
        callback,
        SimpleNamespace(action="delete", value=str(spare.id)),
        FakeState(),
        session,
        school_class,
        Role.ADMIN,
    )

    # By name, not by id: SQLite hands the freed rowid straight to the next
    # insert, and the redraw adopts three subjects out of the timetable.
    assert await session.scalar(select(Subject).where(Subject.name == "Астрономия")) is None
    assert not callback.alerted
    assert "Астрономия" not in callback.message.last


async def test_collecting_from_the_timetable_writes_down_the_names_it_finds(
    session, school_class
):
    callback = FakeCallback(message=FakeEditable())
    await subjects_collect(callback, FakeState(), session, school_class, Role.EDITOR)

    names = sorted(
        row.name
        for row in await session.scalars(
            select(Subject).where(Subject.class_id == school_class.id)
        )
    )
    assert names == ["Алгебра", "История", "Физика"]
    assert "Добавлено: 3" in (callback.answers[0][0] or "")
    for name in names:
        assert name in callback.message.last


# --------------------------------------------------------------------------
# Special days: the whole path from the button to the card
# --------------------------------------------------------------------------


async def test_a_shortened_day_asks_which_bells_and_then_carries_their_name(
    session, school_class
):
    """The point of «⏱ Сокращённые уроки» is the schedule it points at; a day
    marked shortened with no bells behind it changes nothing at all."""
    short = BellSchedule(class_id=school_class.id, name="Сокращённое")
    session.add(short)
    await session.flush()
    # With rows in it: a schedule that rings nothing is refused now, because a
    # day pointed at one draws nothing - see the test below.
    for index in (1, 2, 3):
        session.add(
            BellPeriod(
                schedule_id=short.id,
                index=index,
                starts_at=Time(8 + index, 0),
                ends_at=Time(8 + index, 30),
            )
        )
    await session.commit()

    day = a_day_ahead(school_class)
    callback = FakeCallback(message=FakeEditable())
    await holiday_kind(
        callback,
        SimpleNamespace(action="kind", value=f"{day.isoformat()}:shortened"),
        FakeState(),
        session,
        school_class,
        Role.EDITOR,
    )
    assert "расписанию звонков" in callback.message.last

    picked = FakeCallback(message=FakeEditable())
    await holiday_bells(
        picked,
        SimpleNamespace(action="bells", value=f"{day.isoformat()}:{short.id}"),
        FakeState(),
        session,
        school_class,
        Role.EDITOR,
    )

    override = await session.scalar(select(DayOverride).where(DayOverride.date == day))
    assert override.bell_schedule_id == short.id

    card = FakeMessage()
    await cmd_holidays(card, FakeState(), session, school_class, Role.EDITOR)
    assert f"{day:%d.%m}" in card.last
    assert "⏱ Сокращённые уроки" in card.last
    assert "🔔 Сокращённое" in card.last


async def test_a_day_is_not_pointed_at_a_bell_schedule_that_rings_nothing(
    session, school_class
):
    """«🔔 Звонки» creates a schedule empty and the times are typed in later,
    so an empty one is an ordinary state to be in — but a day pointed at it
    draws no lessons at all, because the resolver takes each lesson's times
    from the bell row of its own number. The card said «⏱ Сокращённые уроки»
    over an empty day on every phone, in the widget and in the calendar feed,
    and nothing anywhere said why. `api/edit.day_put` refuses it too."""
    empty = BellSchedule(class_id=school_class.id, name="Пустое")
    session.add(empty)
    await session.commit()

    day = a_day_ahead(school_class)
    await holiday_kind(
        FakeCallback(message=FakeEditable()),
        SimpleNamespace(action="kind", value=f"{day.isoformat()}:shortened"),
        FakeState(),
        session,
        school_class,
        Role.EDITOR,
    )

    picked = FakeCallback(message=FakeEditable())
    await holiday_bells(
        picked,
        SimpleNamespace(action="bells", value=f"{day.isoformat()}:{empty.id}"),
        FakeState(),
        session,
        school_class,
        Role.EDITOR,
    )

    override = await session.scalar(select(DayOverride).where(DayOverride.date == day))
    # Left where marking the day put it — the class default — rather than
    # pointed at a schedule that rings nothing. Never null: see below.
    assert override.bell_schedule_id == school_class.bell_schedule_id
    assert "ещё нет ни одного урока" in (picked.answers[-1][0] or "")


async def test_a_shortened_day_always_names_the_schedule_it_rings(session, school_class):
    """This test used to assert the opposite, and the opposite is a state the
    API refuses with a 422.

    «⏱ Сокращённые уроки» is a claim about the times, and the times come from
    a bell schedule. With none, `ScheduleResolver._bells_for` falls back to the
    class default — so the day announced shortened lessons on every phone, in
    the widget, in the calendar feed and in the morning digest, and then drew
    the normal ones. Somebody reads that label and packs for a short day.

    Two ways in, and both are closed. Marking the day now writes the class
    default rather than null, because the row is committed *before* the picker
    is asked and walking away used to leave it null; and «Оставить обычные» is
    gone from the picker, so the one button that re-created the state no
    longer exists. A card left open from before it was removed is refused.
    """
    day = a_day_ahead(school_class)
    await holiday_kind(
        FakeCallback(message=FakeEditable()),
        SimpleNamespace(action="kind", value=f"{day.isoformat()}:shortened"),
        FakeState(),
        session,
        school_class,
        Role.EDITOR,
    )

    override = await session.scalar(select(DayOverride).where(DayOverride.date == day))
    assert override.kind is DayKind.SHORTENED
    assert override.bell_schedule_id == school_class.bell_schedule_id

    stale = FakeCallback(message=FakeEditable())
    await holiday_bells(
        stale,
        SimpleNamespace(action="bells", value=f"{day.isoformat()}:0"),
        FakeState(),
        session,
        school_class,
        Role.EDITOR,
    )

    await session.refresh(override)
    assert override.bell_schedule_id == school_class.bell_schedule_id
    assert "своё расписание звонков" in (stale.answers[-1][0] or "")


def test_the_picker_for_a_shortened_day_offers_no_way_to_leave_it_unrung():
    """The button that created the state the API refuses is not drawn."""
    from app.bot.manage_keyboards import bells_pick_keyboard

    markup = bells_pick_keyboard(
        [SimpleNamespace(id=3, name="Сокращённое")], "2026-10-26"
    )
    payloads = [
        button.callback_data for row in markup.inline_keyboard for button in row
    ]
    assert not any(payload.endswith(":0") for payload in payloads)
    assert any(payload.endswith(":3") for payload in payloads)


async def test_a_note_typed_after_the_kind_lands_on_the_day_row(session, school_class):
    day = a_day_ahead(school_class)
    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await holiday_kind(
        callback,
        SimpleNamespace(action="kind", value=f"{day.isoformat()}:holiday"),
        state,
        session,
        school_class,
        Role.EDITOR,
    )
    assert state.data["date"] == day.isoformat()

    message = FakeMessage(text="весенние каникулы")
    await holiday_note(message, state, session, school_class, Role.EDITOR)

    override = await session.scalar(select(DayOverride).where(DayOverride.date == day))
    assert override.note == "весенние каникулы"
    assert "весенние каникулы" in message.last


async def test_a_dash_for_a_note_leaves_the_day_marked_and_unannotated(session, school_class):
    day = a_day_ahead(school_class)
    state = FakeState()
    await holiday_kind(
        FakeCallback(message=FakeEditable()),
        SimpleNamespace(action="kind", value=f"{day.isoformat()}:remote"),
        state,
        session,
        school_class,
        Role.EDITOR,
    )
    message = FakeMessage(text="-")
    await holiday_note(message, state, session, school_class, Role.EDITOR)

    override = await session.scalar(select(DayOverride).where(DayOverride.date == day))
    assert override.kind is DayKind.REMOTE
    assert override.note is None
    assert "💻 Дистанционное обучение" in message.last


async def test_обычный_день_removes_the_row_rather_than_storing_a_kind(session, school_class):
    """«Обычный день» is the absence of an override, not a kind of one — two
    spellings of the same thing would give the resolver a choice to get wrong."""
    day = a_day_ahead(school_class)
    session.add(DayOverride(class_id=school_class.id, date=day, kind=DayKind.HOLIDAY))
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await holiday_kind(
        callback,
        SimpleNamespace(action="kind", value=f"{day.isoformat()}:normal"),
        FakeState(),
        session,
        school_class,
        Role.EDITOR,
    )

    assert await session.scalar(select(DayOverride).where(DayOverride.date == day)) is None
    assert f"{day:%d.%m}" not in callback.message.last


async def test_deleting_a_day_takes_it_off_the_card(session, school_class):
    day = a_day_ahead(school_class)
    session.add(
        DayOverride(class_id=school_class.id, date=day, kind=DayKind.HOLIDAY, note="актировка")
    )
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await holiday_delete(
        callback,
        SimpleNamespace(action="delete", value=day.isoformat()),
        FakeState(),
        session,
        school_class,
        Role.EDITOR,
    )

    assert await session.scalar(select(DayOverride)) is None
    assert "актировка" not in callback.message.last
    assert "Впереди особых дней нет" in callback.message.last


async def test_a_bare_day_and_month_well_in_the_past_means_the_year_ahead(
    session, school_class
):
    """A school year straddles New Year: «10.01» typed in December is the
    January that is coming, not the one eleven months gone."""
    message = FakeMessage(text="01.01")
    await holiday_typed_date(message, FakeState(), school_class, Role.EDITOR)

    today = datetime.now(school_class.tz).date()
    expected = today.year if (today - Date(today.year, 1, 1)).days <= 90 else today.year + 1
    assert f"01.01.{expected}" in message.last


async def test_a_date_that_is_not_a_date_asks_again(session, school_class):
    message = FakeMessage(text="как-нибудь потом")
    state = FakeState()
    await holiday_typed_date(message, state, school_class, Role.EDITOR)

    assert "Не понял дату" in message.last
    assert not state.cleared


async def test_a_date_no_calendar_could_hold_asks_again_rather_than_answering_nothing(
    session, school_class
):
    """All digits and still not a date, in the two ways that are not `ValueError`.

    A stuck key on the year makes «12.09.2026» a number `int` reads happily and
    `date()` then refuses with an `OverflowError` — past an `except ValueError`
    — and «²» is a digit to `str.isdigit` and a `ValueError` to `int`, raised
    before the `try` was entered at all. Both left the handler through the
    exception, and this one is a `Message` with no callback to apologise on:
    the date was swallowed, nothing was said, and the prompt was still waiting
    for a date that the next thing typed would be read as.
    """
    for typed in ("12.09.99999999999999999999", "²2.09"):
        message = FakeMessage(text=typed)
        state = FakeState()
        await holiday_typed_date(message, state, school_class, Role.EDITOR)

        assert "Не понял дату" in message.last, typed
        assert not state.cleared


async def test_a_holiday_period_whose_year_overflows_is_refused(session, school_class):
    """The same parser, from «📆 Период» — and this one writes rows."""
    message = FakeMessage(text="26.10.99999999999999999999-05.11")
    await holiday_period_apply(message, FakeState(), session, school_class, Role.ADMIN)

    assert "Не понял период" in message.last
    assert await session.scalar(select(DayOverride)) is None


# --------------------------------------------------------------------------
# Bells
# --------------------------------------------------------------------------


async def test_a_second_bell_schedule_is_created_from_a_paste_and_listed(
    session, school_class
):
    state = FakeState()
    await bells_create(FakeCallback(message=FakeEditable()), state, school_class, Role.ADMIN)

    named = FakeMessage(text="Суббота")
    await bells_new_name(named, state, school_class, Role.ADMIN)
    assert "Суббота" in named.last

    rows = FakeMessage(text="1. 09:00-09:40\n2. 09:50-10:30\nчепуха")
    await bells_new_rows(rows, state, session, school_class, Role.ADMIN)

    schedule = await session.scalar(select(BellSchedule).where(BellSchedule.name == "Суббота"))
    assert schedule is not None
    assert [period.index for period in schedule.periods] == [1, 2]
    # The line it could not read is named, not silently dropped.
    assert "чепуха" in rows.replies[0]
    # And the card that follows shows the new schedule with its rows.
    assert "Суббота" in rows.last
    assert "09:00-09:40" in rows.last
    assert "2 урока" in rows.last


async def test_an_unnamed_bell_schedule_is_not_created(session, school_class):
    state = FakeState(data={})
    message = FakeMessage(text="   ")
    await bells_new_name(message, state, school_class, Role.ADMIN)

    assert "от 1 до 64" in message.last
    assert "name" not in state.data


async def test_the_bells_editor_offers_the_rows_in_the_format_it_accepts_back(
    session, school_class
):
    """The text in the message is what the admin edits and sends straight back,
    so it has to parse as the same grammar that produced it."""
    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await bells_edit(
        callback,
        SimpleNamespace(action="edit", value=str(school_class.bell_schedule_id)),
        state,
        session,
        school_class,
        Role.ADMIN,
    )

    assert "1. 08:30-09:15" in callback.message.last
    assert state.data["schedule_id"] == school_class.bell_schedule_id

    from app.bot.handlers.manage import _parse_bells

    offered = callback.message.last
    body = offered.split("<code>")[1].split("</code>")[0]
    rows, rejected = _parse_bells(body)
    assert len(rows) == 7
    assert rejected == []


async def test_the_star_moves_when_another_schedule_is_made_the_default(session, school_class):
    """The schedule is given rows, and that is the fix rather than the fixture.

    This test used to build a `BellSchedule` with no periods at all and assert
    the star moved onto it — i.e. it asserted the defect as correct behaviour,
    and would have gone red on the fix. Monday has three lessons, so three rows
    keep the star moving and lose nothing; the refusal and the loss have tests
    of their own below.
    """
    other = BellSchedule(class_id=school_class.id, name="Сокращённое")
    session.add(other)
    await session.flush()
    for index in (1, 2, 3):
        session.add(
            BellPeriod(
                schedule_id=other.id,
                index=index,
                starts_at=Time(8 + index, 0),
                ends_at=Time(8 + index, 30),
            )
        )
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await bells_make_default(
        callback,
        SimpleNamespace(action="default", value=str(other.id)),
        session,
        school_class,
        Role.ADMIN,
    )

    assert school_class.bell_schedule_id == other.id
    card = callback.message.last
    assert "⭐ <b>Сокращённое</b>" in card
    assert "⭐ <b>Обычное</b>" not in card
    # Every number Monday uses still rings, so nothing is said about a loss.
    assert "перестали звонить" not in (callback.answers[-1][0] or "")


async def test_a_schedule_that_rings_nothing_cannot_become_the_default_from_the_bot(
    session, school_class
):
    """The «⭐» beside a schedule the API has always refused to accept.

    A schedule may be created empty — that is the two-step flow. Making an
    empty one the class default is another thing: every ordinary day rings it,
    so `/bundle` answers zero lessons, `/now` answers «выходной» on a Monday,
    and the phone, the widget, the calendar feed and the morning digest go
    blank at once with the class's own timetable untouched underneath. Worse,
    `rung_indexes` then returns an empty set, which `can_ring` reads as «bells
    not set up yet» and waves every lesson number through.

    `api/manage.bells_update` has refused it since it was written. The bot did
    the assignment with no check at all, which is the shape `services/` exists
    to prevent: one rule, two shells.
    """
    empty = BellSchedule(class_id=school_class.id, name="Пустое")
    session.add(empty)
    await session.commit()
    was = school_class.bell_schedule_id

    callback = FakeCallback(message=FakeEditable())
    await bells_make_default(
        callback,
        SimpleNamespace(action="default", value=str(empty.id)),
        session,
        school_class,
        Role.ADMIN,
    )

    assert school_class.bell_schedule_id == was
    said, as_alert = callback.answers[-1]
    assert "нет ни одного урока" in (said or "") and as_alert
    # Refused before anything was written, so the card was never redrawn.
    assert callback.message.replies == []


async def test_moving_the_default_to_a_shorter_schedule_says_what_stopped_ringing(
    session, school_class
):
    """Four lessons leave every phone in the class and «⭐» said «обновлено».

    `write_bell_periods` has worked this out since shrinking a schedule was
    closed, but it only runs when a schedule's *rows* are rewritten, and
    re-pointing the class rewrites none. The bot now asks
    `structure.orphaned_lessons` — the same function the API asks — and says
    the count in an alert as well as the log, because the admin pressed a star
    and the audit page is not where anybody looks next.
    """
    short = BellSchedule(class_id=school_class.id, name="Короткое")
    session.add(short)
    await session.flush()
    session.add(
        BellPeriod(schedule_id=short.id, index=1, starts_at=Time(9, 0), ends_at=Time(9, 40))
    )
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await bells_make_default(
        callback,
        SimpleNamespace(action="default", value=str(short.id)),
        session,
        school_class,
        Role.ADMIN,
    )

    # Monday carried lessons 1, 2 and 3; the new default rings only the first.
    assert school_class.bell_schedule_id == short.id
    said, as_alert = callback.answers[-1]
    assert "Перестали звонить уроков: 2" in (said or "") and as_alert
    entries = await audit.recent(session, school_class.id, limit=1)
    assert "перестали звонить уроков: 2" in entries[0].summary

    # Nothing was deleted — the two lessons are still there, and still drawn
    # nowhere, which is the whole reason the sentence has to be said.
    still = await session.scalars(
        select(TimetableEntry).where(TimetableEntry.class_id == school_class.id)
    )
    assert len(list(still)) == 3


async def test_a_spare_bell_schedule_is_deleted_and_leaves_the_card(session, school_class):
    spare = BellSchedule(class_id=school_class.id, name="Лишнее")
    session.add(spare)
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await bells_delete(
        callback,
        SimpleNamespace(action="delete", value=str(spare.id)),
        session,
        school_class,
        Role.ADMIN,
    )

    assert await session.get(BellSchedule, spare.id) is None
    assert "Лишнее" not in callback.message.last


async def test_a_schedule_a_special_day_still_points_at_cannot_be_deleted(
    session, school_class
):
    """The foreign key is ``SET NULL``: deleting it would move those days onto
    the class default, on dates nobody is looking at."""
    spare = BellSchedule(class_id=school_class.id, name="Сокращённое")
    session.add(spare)
    await session.flush()
    session.add(
        DayOverride(
            class_id=school_class.id,
            date=Date(2027, 3, 12),
            kind=DayKind.SHORTENED,
            bell_schedule_id=spare.id,
        )
    )
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await bells_delete(
        callback,
        SimpleNamespace(action="delete", value=str(spare.id)),
        session,
        school_class,
        Role.ADMIN,
    )

    assert await session.get(BellSchedule, spare.id) is not None
    assert callback.alerted
    assert "особые дни (1)" in callback.answers[0][0]


async def test_the_bells_card_says_how_many_schedules_it_did_not_draw(session, school_class):
    """Every other list on these pages ends in «… и ещё N» when it runs out of
    room; this one ended in the legend and lost the tail in silence — and the
    keyboard under it is shorter still, so those rows were unreachable as well
    as unmentioned."""
    for number in range(30):
        session.add(BellSchedule(class_id=school_class.id, name=f"Расписание {number}"))
    await session.commit()

    message = FakeMessage()
    await cmd_bells(message, FakeState(), session, school_class, Role.ADMIN)

    # Thirty-one in the class, ten on the card — the number of ✏️ buttons
    # the keyboard under it offers.
    assert message.last.count("Расписание ") == 9
    assert "… и ещё 21" in message.last
    assert "основное расписание класса" in message.last


# --------------------------------------------------------------------------
# Devices
# --------------------------------------------------------------------------


async def test_unlinking_leaves_the_phone_on_the_class_as_a_reader(session, school_class):
    """Unlinking is not revoking: the phone keeps its token and its class, and
    loses only the account whose role let it write."""
    session.add(BotUser(telegram_id=42, class_id=school_class.id, role=Role.ADMIN))
    device = DeviceToken(
        token_hash="hash-link",
        class_id=school_class.id,
        device_name="Pixel 8",
        telegram_id=42,
        linked_at=datetime(2026, 9, 1, 10, 0),
    )
    session.add(device)
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await device_unlink(
        callback,
        SimpleNamespace(action="unlink", value=str(device.id)),
        session,
        school_class,
        Role.ADMIN,
    )

    await session.refresh(device)
    assert device.telegram_id is None
    assert device.revoked is False
    assert not callback.alerted
    assert "Pixel 8" in callback.message.last
    assert "не привязан" in callback.message.last


async def test_a_phone_nobody_claimed_cannot_be_unlinked(session, school_class):
    device = DeviceToken(token_hash="hash-free", class_id=school_class.id, device_name="Чей-то")
    session.add(device)
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await device_unlink(
        callback,
        SimpleNamespace(action="unlink", value=str(device.id)),
        session,
        school_class,
        Role.ADMIN,
    )

    assert callback.alerted
    assert callback.message.replies == []


async def test_linking_a_phone_gives_it_the_role_the_account_holds(session, school_class):
    from app.services import linking

    session.add(BotUser(telegram_id=42, class_id=school_class.id, role=Role.ADMIN))
    device = DeviceToken(
        token_hash="hash-code", class_id=school_class.id, device_name="Pixel 8"
    )
    session.add(device)
    await session.commit()
    code = await linking.issue_link_code(session, device)

    message = FakeMessage(text="")
    # Lower case on purpose: codes are minted upper-case and read back either way.
    await cmd_link(message, _command(code.lower()), session, school_class, Role.ADMIN)

    await session.refresh(device)
    assert device.telegram_id == 42
    assert device.link_code is None
    assert "Pixel 8" in message.last
    assert "Администратор" in message.last
    assert "редактировать" in message.last


async def test_a_link_code_works_once(session, school_class):
    from app.services import linking

    session.add(BotUser(telegram_id=42, class_id=school_class.id, role=Role.ADMIN))
    device = DeviceToken(token_hash="hash-once", class_id=school_class.id)
    session.add(device)
    await session.commit()
    code = await linking.issue_link_code(session, device)

    await cmd_link(FakeMessage(), _command(code), session, school_class, Role.ADMIN)
    second = FakeMessage()
    await cmd_link(second, _command(code), session, school_class, Role.ADMIN)

    assert "Код не подошёл" in second.last


async def test_link_without_a_code_explains_where_to_find_one(session, school_class):
    message = FakeMessage()
    await cmd_link(message, _command(None), session, school_class, Role.VIEWER)

    assert "/link" in message.last
    assert "приложении" in message.last


# --------------------------------------------------------------------------
# The log: paging
# --------------------------------------------------------------------------


async def test_the_log_pages_back_and_says_where_it_is(session, school_class):
    session.add(BotUser(telegram_id=42, class_id=school_class.id, role=Role.ADMIN,
                        username="admin"))
    for number in range(45):
        await audit.record(session, school_class.id, 42, "test", f"изменение № {number}")
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await audit_page(
        callback,
        SimpleNamespace(action="page", value="30"),
        FakeState(),
        session,
        school_class,
        Role.ADMIN,
    )

    page = callback.message.last
    # Newest first, so page two is the fifteen oldest.
    assert "· с 31" in page
    assert "изменение № 14" in page
    assert "изменение № 15" not in page
    assert "@admin" in page


async def test_a_negative_page_is_page_one_and_does_not_claim_otherwise(session, school_class):
    """SQL reads a negative OFFSET as no offset at all, so the page would be
    the first one under a heading promising the thirty-first line."""
    for number in range(5):
        await audit.record(session, school_class.id, 42, "test", f"изменение № {number}")
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await audit_page(
        callback,
        SimpleNamespace(action="page", value="-100"),
        FakeState(),
        session,
        school_class,
        Role.ADMIN,
    )

    page = callback.message.last
    assert "· с " not in page
    assert "изменение № 4" in page


# --------------------------------------------------------------------------
# The class card: fields, diary, calendar
# --------------------------------------------------------------------------


async def test_renaming_the_class_redraws_the_card_under_the_new_name(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await class_field_prompt(
        callback, SimpleNamespace(action="rename"), state, school_class, Role.ADMIN
    )
    assert state.data["field"] == "rename"

    message = FakeMessage(text="  9В  ")
    await class_field_apply(message, state, session, school_class, Role.ADMIN)

    assert school_class.name == "9В"
    assert "9В" in message.last
    assert "9А" not in message.last


async def test_an_empty_class_name_is_refused_and_the_old_one_stands(session, school_class):
    """The name is what the delete confirmation is typed against; a class with
    no name could not be deleted by its owner at all."""
    message = FakeMessage(text="   ")
    state = FakeState(data={"field": "rename"})
    await class_field_apply(message, state, session, school_class, Role.ADMIN)

    assert school_class.name == "9А"
    assert "От 1 до 64" in message.last
    assert not state.cleared


async def test_a_dash_clears_the_city_and_the_card_shows_a_dash(session, school_class):
    school_class.city = "Тверь"
    await session.commit()

    message = FakeMessage(text="-")
    await class_field_apply(
        message, FakeState(data={"field": "city"}), session, school_class, Role.ADMIN
    )

    assert school_class.city is None
    assert "🏙 Город: —" in message.last


async def test_a_field_prompt_that_expired_sends_the_admin_back_to_the_card(
    session, school_class
):
    message = FakeMessage(text="что-то")
    state = FakeState(data={})
    await class_field_apply(message, state, session, school_class, Role.ADMIN)

    assert "/class" in message.last
    assert state.cleared


async def test_binding_and_unbinding_the_diary_flips_one_line_of_the_card(
    session, school_class
):
    """Binding gives the class nothing and takes nothing — it puts «📒 Мой
    дневник» on the menu — so the card is the only place it is visible."""
    callback = FakeCallback(message=FakeEditable())
    await class_diary_bind(callback, session, school_class, Role.ADMIN)

    assert school_class.diary_provider is not None
    assert "📒 Дневник: Санкт-Петербург" in callback.message.last

    again = FakeCallback(message=FakeEditable())
    await class_diary_bind(again, session, school_class, Role.ADMIN)

    assert school_class.diary_provider is None
    assert "📒 Дневник: не привязан" in again.message.last


async def test_an_editor_cannot_bind_the_diary(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    await class_diary_bind(callback, session, school_class, Role.EDITOR)

    assert school_class.diary_provider is None
    assert callback.alerted


async def test_the_switch_screen_needs_somewhere_to_switch_to(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    await class_switch(callback, session, school_class, Role.ADMIN)

    assert callback.alerted
    assert callback.message.replies == []

    await _two_classes(session, school_class, 42)
    listed = FakeCallback(message=FakeEditable())
    await class_switch(listed, session, school_class, Role.ADMIN)

    assert not listed.alerted
    assert "Сменить класс" in listed.message.last


async def test_the_delete_prompt_spells_out_what_goes_and_asks_for_the_name(
    session, school_class
):
    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await class_delete_prompt(callback, state, school_class, Role.OWNER)

    prompt = callback.message.last
    assert "9А" in prompt
    assert "домашние задания" in prompt
    assert "нельзя отменить" in prompt
    assert state.state is not None


async def test_the_calendar_link_is_issued_once_and_the_rotation_says_so(
    session, school_class, monkeypatch
):
    """Rotating breaks every existing subscription — that is the point of the
    button, and the card has to say it out loud."""
    from app.config import get_settings

    monkeypatch.setenv("PUBLIC_BASE_URL", "https://example.test")
    get_settings.cache_clear()
    try:
        message = FakeMessage()
        await cmd_calendar(message, FakeState(), session, school_class, Role.ADMIN)
        first = school_class.calendar_token
        assert first
        assert f"https://example.test/api/v1/calendar/{first}.ics" in message.last

        again = FakeMessage()
        await cmd_calendar(again, FakeState(), session, school_class, Role.ADMIN)
        assert school_class.calendar_token == first

        callback = FakeCallback(message=FakeEditable())
        await calendar_rotate(callback, session, school_class, Role.ADMIN)

        assert school_class.calendar_token != first
        assert first not in callback.message.last
        assert "Старая ссылка больше не работает" in callback.message.last
    finally:
        get_settings.cache_clear()


# --------------------------------------------------------------------------
# Statistics
# --------------------------------------------------------------------------


async def test_a_fortnightly_lesson_counts_as_half_an_hour_a_week(session, school_class):
    """«Часов в неделю» is what a school's own paperwork means by it: a lesson
    that runs on odd weeks only is half a lesson a week."""
    session.add(
        TimetableEntry(
            class_id=school_class.id,
            weekday=2,
            index=1,
            subject_name="Химия",
            parity=WeekParity.ODD,
        )
    )
    await session.commit()

    message = FakeMessage()
    await cmd_stats(message, FakeState(), session, school_class, Role.EDITOR)

    card = message.last
    # Three full lessons on Monday plus half of Tuesday's.
    assert "Уроков в неделю: <b>3,5</b>" in card
    assert "предметов: <b>4</b>" in card
    assert "• Химия — 0,5" in card


async def test_the_stats_card_counts_the_people_and_the_phones(session, school_class):
    session.add(BotUser(telegram_id=1, class_id=school_class.id, role=Role.OWNER))
    session.add(BotUser(telegram_id=2, class_id=school_class.id, role=Role.EDITOR))
    session.add(BotUser(telegram_id=3, class_id=school_class.id, role=Role.EDITOR))
    session.add(DeviceToken(token_hash="s1", class_id=school_class.id))
    session.add(DeviceToken(token_hash="s2", class_id=school_class.id, revoked=True))
    session.add(
        Homework(
            class_id=school_class.id,
            due_date=Date.today() + timedelta(days=3),
            subject_name="Алгебра",
            text="№ 42",
        )
    )
    await session.commit()

    message = FakeMessage()
    await cmd_stats(message, FakeState(), session, school_class, Role.EDITOR)

    card = message.last
    assert "Владелец: 1 · Редактор: 2" in card
    # The revoked phone is not a connected phone.
    assert "Подключённых устройств: <b>1</b>" in card
    assert "<b>1</b> актуальных" in card


async def test_the_stats_card_of_a_class_with_no_timetable_says_so(session):
    bare = SchoolClass(name="10Б", join_code="BARE01")
    bare_user = BotUser(telegram_id=9, class_id=0, role=Role.OWNER)
    session.add(bare)
    await session.flush()
    bare_user.class_id = bare.id
    session.add(bare_user)
    await session.commit()

    message = FakeMessage()
    await cmd_stats(message, FakeState(), session, bare, Role.OWNER)

    card = message.last
    assert "Расписание ещё не заполнено" in card
    assert "Уроков в неделю: <b>0</b>" in card
    assert "Владелец: 1" in card


# --------------------------------------------------------------------------
# Import
# --------------------------------------------------------------------------


async def test_the_import_help_shows_the_shape_of_the_paste(session, school_class):
    message = FakeMessage()
    state = FakeState()
    await cmd_import(message, state, school_class, Role.ADMIN)

    assert "== Понедельник ==" in message.last
    assert "== Звонки ==" in message.last
    assert state.state is not None


async def test_a_paste_that_is_neither_days_nor_bells_gets_the_help_back(
    session, school_class
):
    message = FakeMessage(text="здравствуйте, вот расписание")
    state = FakeState()
    await import_preview(message, state, school_class, Role.ADMIN)

    assert "Не нашёл ни одного дня" in message.last
    # Nothing was stored, so «Применить» has nothing to apply.
    assert "raw" not in state.data


async def test_the_applied_import_counts_the_lessons_it_actually_wrote(session, school_class):
    """A lesson past the last bell is not written — the resolver builds a day
    out of the bell rows, so such a row would be stored and drawn nowhere. The
    card used to print the parsed count under a total that excluded them:
    «уроков — 7» directly above «Вторник: 9»."""
    paste = "== Вторник ==\n" + "\n".join(f"{number}. Предмет{number}" for number in range(1, 10))
    state = FakeState()
    await import_preview(FakeMessage(text=paste), state, school_class, Role.ADMIN)

    callback = FakeCallback(message=FakeEditable())
    await import_apply(callback, state, session, school_class, Role.ADMIN)

    written = list(
        await session.scalars(
            select(TimetableEntry).where(
                TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == 2
            )
        )
    )
    assert len(written) == 7

    card = callback.message.last
    assert "уроков — 7" in card
    assert "• Вторник: 7" in card
    assert "• Вторник: 9" not in card
    assert "Не добавлены уроки № 8, 9" in card


async def test_the_import_card_counts_dropped_rows_not_dropped_numbers(
    session, school_class
):
    """The ceiling is class-wide, so one number over it costs a row on every
    weekday that carries it. «Не добавлены уроки № 8» is right to say the
    number once — it is one thing to fix — but the audit line counted the same
    way and recorded «без звонка пропущено 1» for two lessons that are on no
    phone, with nothing else in the class to say the second one existed."""
    from app.models import AuditEntry

    paste = (
        "== Понедельник ==\n1. Химия\n8. Алгебра\n\n"
        "== Вторник ==\n1. История\n8. Физика\n"
    )
    state = FakeState()
    await import_preview(FakeMessage(text=paste), state, school_class, Role.ADMIN)

    callback = FakeCallback(message=FakeEditable())
    await import_apply(callback, state, session, school_class, Role.ADMIN)

    card = callback.message.last
    assert "• Понедельник: 1" in card and "• Вторник: 1" in card
    assert "Не добавлены уроки № 8" in card

    summary = await session.scalar(
        select(AuditEntry.summary).where(
            AuditEntry.class_id == school_class.id,
            AuditEntry.action == "timetable.import",
        )
    )
    assert "без звонка пропущено 2" in summary


async def test_a_paste_of_bells_alone_is_not_reported_as_nothing_recognised(
    session, school_class
):
    """«== Звонки ==» with no weekday under it is a legitimate paste — it is
    what /export gives a class whose timetable is empty. The preview called it
    «Ни одного дня не распознано» over an «✅ Применить» button that was about
    to rewrite the class's bells, and said nothing about doing so."""
    message = FakeMessage(text="== Звонки ==\n1. 08:00-08:45\n2. 09:00-09:45")
    state = FakeState()
    await import_preview(message, state, school_class, Role.ADMIN)

    preview = message.last
    assert "Ни одного дня не распознано" not in preview
    assert "• Звонки: 2 урока" in preview
    assert "Звонки заменят основное расписание класса целиком." in preview

    callback = FakeCallback(message=FakeEditable())
    await import_apply(callback, state, session, school_class, Role.ADMIN)

    schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
    await session.refresh(schedule, ["periods"])
    assert [period.index for period in schedule.periods] == [1, 2]


async def test_the_bells_line_of_a_preview_counts_in_russian(session, school_class):
    """One bell row read «Звонки: 1 уроков»; the line was glued on after the
    renderer returned, below the «Применить» footer, and never saw ``plural``."""
    message = FakeMessage(text="== Вторник ==\n1. Химия\n\n== Звонки ==\n1. 08:00-08:45")
    await import_preview(message, FakeState(), school_class, Role.ADMIN)

    preview = message.last
    assert "• Звонки: 1 урок" in preview
    assert "1 уроков" not in preview
    # And it stands with the day counts, above the footer, not after it.
    assert preview.index("Звонки: 1 урок") < preview.index("«Применить»")


# --------------------------------------------------------------------------
# Access requests: refusal and comment
# --------------------------------------------------------------------------


async def test_declining_a_request_tells_the_person_and_leaves_the_role_alone(
    session, school_class
):
    session.add(BotUser(telegram_id=55, class_id=school_class.id, role=Role.VIEWER))
    request = AccessRequest(
        class_id=school_class.id,
        telegram_id=55,
        requested_role=Role.EDITOR,
        status="pending",
    )
    session.add(request)
    await session.commit()

    bot = FakeBot()
    callback = FakeCallback(message=FakeEditable(), bot=bot)
    await request_decline(
        callback,
        SimpleNamespace(action="decline", value=str(request.id)),
        session,
        school_class,
        Role.ADMIN,
    )

    await session.refresh(request)
    assert request.status == "declined"
    assert request.decided_by == 42
    member = await session.scalar(select(BotUser).where(BotUser.telegram_id == 55))
    assert member.role is Role.VIEWER
    assert bot.sent and bot.sent[0][0] == 55
    assert "отклонён" in bot.sent[0][1]
    assert "9А" in bot.sent[0][1]


async def test_a_request_already_decided_cannot_be_decided_again(session, school_class):
    request = AccessRequest(
        class_id=school_class.id,
        telegram_id=55,
        requested_role=Role.EDITOR,
        status="declined",
    )
    session.add(request)
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await request_decline(
        callback,
        SimpleNamespace(action="decline", value=str(request.id)),
        session,
        school_class,
        Role.ADMIN,
    )

    assert callback.alerted
    assert callback.message.replies == []


async def test_a_comment_typed_at_the_prompt_reaches_the_admins(session, school_class):
    session.add(BotUser(telegram_id=55, class_id=school_class.id, role=Role.VIEWER))
    session.add(BotUser(telegram_id=10, class_id=school_class.id, role=Role.ADMIN))
    await session.commit()

    bot = FakeBot()
    message = FakeMessage(text="я староста", user_id=55, bot=bot)
    state = FakeState()
    await request_message(message, state, session, school_class, Role.VIEWER)

    stored = await session.scalar(select(AccessRequest))
    assert stored.message == "я староста"
    assert [chat_id for chat_id, _ in bot.sent] == [10]
    assert "я староста" in bot.sent[0][1]
    assert "Запрос отправлен" in message.last
    assert state.cleared


async def test_a_dash_sends_the_request_without_a_comment(session, school_class):
    session.add(BotUser(telegram_id=55, class_id=school_class.id, role=Role.VIEWER))
    await session.commit()

    message = FakeMessage(text="-", user_id=55)
    await request_message(message, FakeState(), session, school_class, Role.VIEWER)

    stored = await session.scalar(select(AccessRequest))
    assert stored is not None
    assert stored.message is None


# --------------------------------------------------------------------------
# Quarters and half-years
# --------------------------------------------------------------------------


async def _open_terms(session, school_class) -> int:
    """Draw «🗓 Четверти» once, which is what seeds the year, and say which year.

    ``set_bounds`` reads the stored terms and refuses «Такого периода нет» for
    a class that has none — the card is the only thing that creates them.
    """
    from app.services import terms as terms_service

    await terms_list(
        FakeCallback(message=FakeEditable()), FakeState(), session, school_class, Role.ADMIN
    )
    return terms_service.opening_year_of(datetime.now(school_class.tz).date())


async def test_switching_to_semesters_replaces_the_four_quarters_with_two(
    session, school_class
):
    """Four quarters and two halves do not map onto each other, so the set is
    replaced rather than edited — and the card has to show the new shape."""
    from app.services import terms as terms_service

    await _open_terms(session, school_class)

    callback = FakeCallback(message=FakeEditable())
    await terms_scheme(
        callback,
        SimpleNamespace(action="scheme", value="semester"),
        FakeState(),
        session,
        school_class,
        Role.ADMIN,
    )

    card = callback.message.last
    assert "полугодия" in card
    assert card.count("дн.") == 2
    assert "<b>3.</b>" not in card

    rows = list(await session.scalars(select(Term).where(Term.class_id == school_class.id)))
    assert len(rows) == 2
    assert school_class.term_kind is TermKind.SEMESTER
    assert terms_service.scheme_of(school_class) is TermKind.SEMESTER


async def test_editing_a_term_moves_its_dates_on_the_card(session, school_class):
    year = await _open_terms(session, school_class)

    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await term_edit_prompt(
        callback, SimpleNamespace(action="edit", value="1"), state, school_class, Role.ADMIN
    )
    assert state.data["term_index"] == 1
    assert "Период <b>1</b>" in callback.message.last

    message = FakeMessage(text=f"01.09.{year} - 25.10.{year}")
    await term_edit_apply(message, state, session, school_class, Role.ADMIN)

    card = message.last
    assert f"01.09.{year} — 25.10.{year}" in card
    # 1 September to 25 October counts both ends: 55 days.
    assert "· 55 дн." in card
    assert state.cleared


async def test_a_term_that_overlaps_the_next_one_keeps_the_prompt_open(session, school_class):
    """The refusal is the rule in Russian, and the prompt stays open: the admin
    has one date to correct, not a form to start again."""
    from app.services import terms as terms_service

    year = await _open_terms(session, school_class)
    state = FakeState(data={"term_index": 1})

    message = FakeMessage(text=f"01.09.{year} - 31.12.{year}")
    await term_edit_apply(message, state, session, school_class, Role.ADMIN)

    assert "Пересекается с периодом 2" in message.last
    assert not state.cleared

    rows = await terms_service.read(session, school_class.id, year)
    assert rows[0].ends_on != Date(year, 12, 31)


async def test_a_term_outside_the_school_year_is_refused(session, school_class):
    year = await _open_terms(session, school_class)

    message = FakeMessage(text=f"01.08.{year} - 10.08.{year}")
    await term_edit_apply(
        message, FakeState(data={"term_index": 1}), session, school_class, Role.ADMIN
    )

    assert "учебный год" in message.last


async def test_a_span_that_is_not_two_dates_asks_again(session, school_class):
    await _open_terms(session, school_class)

    message = FakeMessage(text="с сентября по октябрь")
    state = FakeState(data={"term_index": 1})
    await term_edit_apply(message, state, session, school_class, Role.ADMIN)

    assert "Не разобрал" in message.last
    assert not state.cleared


# --------------------------------------------------------------------------
# Buttons as an entrance to the same pages
# --------------------------------------------------------------------------


async def test_every_button_opens_the_same_page_as_its_command(session, school_class):
    """Each of these pages has two doors — a /command and a button — and they
    are the same page or they are two pages that drift apart. The button path
    edits a message instead of sending one, which is the only difference that
    is supposed to survive."""
    session.add(BotUser(telegram_id=42, class_id=school_class.id, role=Role.OWNER))
    session.add(
        DeviceToken(token_hash="hash-both", class_id=school_class.id, device_name="Pixel 8")
    )
    await session.commit()

    # A press lambda rather than a bare handler: «🏖 Особые дни» takes the
    # filter out of its own payload, so it has one argument the others do not,
    # and an unfiltered press is the one that has to match the typed command.
    pairs = [
        (
            cmd_subjects,
            lambda cb, role: subjects_list(cb, FakeState(), session, school_class, role),
            Role.EDITOR,
        ),
        (
            cmd_holidays,
            lambda cb, role: holidays_list(
                cb, DayKindAction(action="list"), FakeState(), session, school_class, role
            ),
            Role.EDITOR,
        ),
        (
            cmd_bells,
            lambda cb, role: bells_list(cb, FakeState(), session, school_class, role),
            Role.ADMIN,
        ),
        (
            cmd_devices,
            lambda cb, role: devices_list(cb, FakeState(), session, school_class, role),
            Role.ADMIN,
        ),
        (
            cmd_class,
            lambda cb, role: class_root(cb, FakeState(), session, school_class, role),
            Role.OWNER,
        ),
    ]
    for command, press, role in pairs:
        typed = FakeMessage()
        await command(typed, FakeState(), session, school_class, role)

        pressed = FakeCallback(message=FakeEditable())
        await press(pressed, role)

        assert pressed.message.last == typed.last, command.__name__
        assert not pressed.alerted, command.__name__


async def test_the_calendar_button_draws_what_the_command_draws(session, school_class):
    typed = FakeMessage()
    await cmd_calendar(typed, FakeState(), session, school_class, Role.VIEWER)

    pressed = FakeCallback(message=FakeEditable())
    await calendar_card(pressed, session, school_class, Role.VIEWER)

    assert pressed.message.last == typed.last
    assert "PUBLIC_BASE_URL" in pressed.message.last


# --------------------------------------------------------------------------
# Bells: saving the rows
# --------------------------------------------------------------------------


async def test_a_bells_paste_replaces_every_row_and_the_card_shows_the_new_times(
    session, school_class
):
    """The rows are deleted in bulk, which goes round the ORM and leaves the
    eagerly loaded ``periods`` stale. Without the refresh the card drawn a line
    later would show the seven old times under a message saying three."""
    message = FakeMessage(text="1. 09:00-09:40\n2. 09:50-10:30\n3. 10:40-11:20")
    await bells_rows_apply(
        message,
        FakeState(data={"schedule_id": school_class.bell_schedule_id}),
        session,
        school_class,
        Role.ADMIN,
    )

    rows = list(
        await session.scalars(
            select(BellPeriod)
            .where(BellPeriod.schedule_id == school_class.bell_schedule_id)
            .order_by(BellPeriod.index)
        )
    )
    assert [row.index for row in rows] == [1, 2, 3]

    assert "сохранены: 3" in message.replies[0]
    card = message.last
    assert "3 урока" in card
    assert "09:00-09:40" in card
    # The times that were there a moment ago are gone from the card too.
    assert "08:30-09:15" not in card
    assert "14:20-15:05" not in card


async def test_a_bells_paste_names_the_lines_it_could_not_read(session, school_class):
    message = FakeMessage(text="1. 09:00-09:40\nобед\n2. 09:50-10:30")
    await bells_rows_apply(
        message,
        FakeState(data={"schedule_id": school_class.bell_schedule_id}),
        session,
        school_class,
        Role.ADMIN,
    )

    assert "Не разобрал строки" in message.replies[0]
    assert "обед" in message.replies[0]
    assert "сохранены: 2" in message.replies[0]


async def test_rows_for_a_schedule_that_was_deleted_mid_flow_say_so(session, school_class):
    spare = BellSchedule(class_id=school_class.id, name="Лишнее")
    session.add(spare)
    await session.commit()
    schedule_id = spare.id
    await session.delete(spare)
    await session.commit()

    message = FakeMessage(text="1. 09:00-09:40")
    state = FakeState(data={"schedule_id": schedule_id})
    await bells_rows_apply(message, state, session, school_class, Role.ADMIN)

    assert "уже удалено" in message.last
    assert state.cleared


async def test_a_new_schedule_whose_name_was_lost_starts_again(session, school_class):
    """Every update may hit a fresh process, so a step that assumed the step
    before it had happened would be a create with no name at all."""
    message = FakeMessage(text="1. 09:00-09:40")
    state = FakeState(data={})
    await bells_new_rows(message, state, session, school_class, Role.ADMIN)

    assert "/bells" in message.last
    assert await session.scalar(select(BellSchedule).where(BellSchedule.name == "")) is None
    assert state.cleared


async def test_a_new_schedule_is_not_created_from_prose(session, school_class):
    before = len(list(await session.scalars(select(BellSchedule))))
    message = FakeMessage(text="как обычно, только короче")
    state = FakeState(data={"name": "Суббота"})
    await bells_new_rows(message, state, session, school_class, Role.ADMIN)

    after = list(await session.scalars(select(BellSchedule)))
    assert len(after) == before
    assert "ни одной строки" in message.last
    assert not state.cleared


# --------------------------------------------------------------------------
# Special days: the screens it all starts from
# --------------------------------------------------------------------------


async def test_the_add_day_screen_offers_a_month_and_a_typed_date(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await holiday_add(callback, state, school_class, Role.EDITOR)

    assert "Особый день" in callback.message.last
    assert "12.09" in callback.message.last
    assert state.state is not None


async def test_a_day_picked_in_the_calendar_asks_what_kind_of_day_it_is(
    session, school_class
):
    """The date travels in the callback payload from here on, so the flow no
    longer depends on an FSM state the user could have set for something else."""
    callback = FakeCallback(message=FakeEditable())
    state = FakeState(state="something else")
    await holiday_pick_date(
        callback,
        SimpleNamespace(action="pick_date", value="2027-03-12"),
        state,
        school_class,
        Role.EDITOR,
    )

    assert "12.03.2027" in callback.message.last
    assert "Что это за день?" in callback.message.last
    assert state.cleared


async def test_a_picked_date_that_is_not_a_date_is_refused(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    await holiday_pick_date(
        callback,
        SimpleNamespace(action="pick_date", value="никогда"),
        FakeState(),
        school_class,
        Role.EDITOR,
    )

    assert callback.alerted
    assert callback.message.replies == []


async def test_the_period_screen_asks_what_to_mark_it_as_before_the_dates(
    session, school_class
):
    """The first screen is the four kinds, and it opens no form.

    Marking a range used to mean holidays and nothing else, so «дистант с 12
    по 16» was five presses on five day cards. The dates come second now — and
    the state stays empty until a kind is picked, so a «26.10-05.11» typed at
    this screen is not swallowed by a form that does not know what to do with
    it yet.
    """
    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await holiday_period_start(callback, state, school_class, Role.ADMIN)

    card = callback.message.last
    assert "Самоподготовка" in card or "период" in card
    assert state.state is None, "nothing is being filled in until a kind is chosen"


async def test_the_ceiling_is_named_before_anybody_types_a_date(session, school_class):
    """Still named before the form opens — one screen later than it used to be.

    This is the promise, not the screen it is made on: somebody about to type
    «01.09-31.05» has to be told it will be refused before they type it, not
    after.
    """
    from app.bot.handlers.manage import PERIOD_MAX_DAYS

    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await holiday_period_kind(
        callback,
        DayKindAction(action="period_kind", value=DayKind.SELF_STUDY.value),
        state,
        school_class,
        Role.ADMIN,
    )

    assert "26.10-05.11" in callback.message.last
    assert str(PERIOD_MAX_DAYS) in callback.message.last
    assert state.state is not None


async def test_a_period_is_marked_with_the_kind_that_was_picked(session, school_class):
    """The whole point of the picker, end to end."""
    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await holiday_period_kind(
        callback,
        DayKindAction(action="period_kind", value=DayKind.REMOTE.value),
        state,
        school_class,
        Role.ADMIN,
    )

    message = FakeMessage(text="02.11.2026-04.11.2026")
    await holiday_period_apply(message, state, session, school_class, Role.ADMIN)

    days = list(await session.scalars(select(DayOverride).order_by(DayOverride.date)))
    assert [day.date for day in days] == [Date(2026, 11, day) for day in (2, 3, 4)]
    assert {day.kind for day in days} == {DayKind.REMOTE}
    assert "Дистанционное обучение" in message.replies[0]


async def test_a_period_with_no_kind_behind_it_is_still_the_holidays(
    session, school_class
):
    """A form that outlived a restart has a date to apply and no kind.

    Refusing it would throw away what somebody just typed over a process
    boundary they cannot see; holidays are what this flow marked before there
    was anything to pick, so that is what it falls back to.
    """
    message = FakeMessage(text="02.11.2026-03.11.2026")
    await holiday_period_apply(message, FakeState(), session, school_class, Role.ADMIN)

    days = list(await session.scalars(select(DayOverride)))
    assert {day.kind for day in days} == {DayKind.HOLIDAY}


async def test_a_crafted_kind_is_refused_rather_than_raising(session, school_class):
    """Callback data is whatever the client sent.

    `DayKind(raw)` raises on anything else, and a raise here never reaches
    `callback.answer()` — the button keeps its spinner until Telegram gives
    up, which reads as the app being broken rather than the press being wrong.
    """
    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await holiday_period_kind(
        callback,
        DayKindAction(action="period_kind", value="../../etc/passwd"),
        state,
        school_class,
        Role.ADMIN,
    )

    assert callback.alerted
    assert state.state is None
    assert callback.message.replies == []


async def test_a_kind_this_flow_does_not_offer_is_refused_too(session, school_class):
    """`NORMAL` and `SHORTENED` are parseable and still not on offer.

    A shortened day points at a bell schedule and `NORMAL` is the absence of a
    mark, so accepting either over a range would quietly do something nobody
    asked for — across a fortnight, from one message.
    """
    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await holiday_period_kind(
        callback,
        DayKindAction(action="period_kind", value=DayKind.SHORTENED.value),
        state,
        school_class,
        Role.ADMIN,
    )

    assert callback.alerted
    assert state.state is None


async def test_an_editor_is_not_offered_the_period_screen(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    await holiday_period_start(callback, FakeState(), school_class, Role.EDITOR)

    assert callback.alerted
    assert callback.message.replies == []


async def test_an_editor_cannot_pick_a_kind_for_a_period_either(session, school_class):
    """The second screen re-checks, rather than trusting the press that opened it:
    a card can sit on a phone after the role behind it has changed."""
    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await holiday_period_kind(
        callback,
        DayKindAction(action="period_kind", value=DayKind.REMOTE.value),
        state,
        school_class,
        Role.EDITOR,
    )

    assert callback.alerted
    assert state.state is None
    assert callback.message.replies == []


# --------------------------------------------------------------------------
# Import: cancelling
# --------------------------------------------------------------------------


async def test_cancelling_an_import_writes_nothing_and_says_so(session, school_class):
    state = FakeState()
    await import_preview(FakeMessage(text=PASTE), state, school_class, Role.ADMIN)
    assert state.data["raw"]

    callback = FakeCallback(message=FakeEditable())
    await import_cancel(callback, state, school_class, Role.ADMIN)

    assert "ничего не изменилось" in callback.message.last
    assert state.cleared
    assert (
        await session.scalar(
            select(TimetableEntry).where(TimetableEntry.weekday == 2)
        )
    ) is None


async def test_applying_an_import_whose_paste_is_gone_starts_again(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await import_apply(callback, state, session, school_class, Role.ADMIN)

    assert callback.alerted
    assert "/import" in callback.answers[0][0]
    assert state.cleared


# --------------------------------------------------------------------------
# An access request: a screen instead of an argument
# --------------------------------------------------------------------------


async def test_request_without_a_word_opens_the_prompt_rather_than_sending(
    session, school_class
):
    message = FakeMessage(text="", user_id=55)
    state = FakeState()
    await cmd_request(message, _command(None), state, session, school_class, Role.VIEWER)

    assert await session.scalar(select(AccessRequest)) is None
    assert "пару слов о себе" in message.last
    assert state.state is not None


async def test_approving_is_refused_for_somebody_who_already_outranks_you(
    session, school_class
):
    """An admin may not touch another admin's role — the same rule «👥 Доступ»
    applies, checked here because the request carries only what was asked for."""
    session.add(BotUser(telegram_id=55, class_id=school_class.id, role=Role.ADMIN))
    request = AccessRequest(
        class_id=school_class.id,
        telegram_id=55,
        requested_role=Role.EDITOR,
        status="pending",
    )
    session.add(request)
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await request_approve(
        callback,
        SimpleNamespace(action="approve", value=str(request.id)),
        session,
        school_class,
        Role.ADMIN,
    )

    member = await session.scalar(select(BotUser).where(BotUser.telegram_id == 55))
    assert member.role is Role.ADMIN
    await session.refresh(request)
    assert request.status == "pending"
    assert callback.alerted


async def test_approving_a_request_from_somebody_not_in_the_class_adds_them(
    session, school_class
):
    request = AccessRequest(
        class_id=school_class.id,
        telegram_id=77,
        requested_role=Role.EDITOR,
        status="pending",
    )
    session.add(request)
    await session.commit()

    callback = FakeCallback(message=FakeEditable())
    await request_approve(
        callback,
        SimpleNamespace(action="approve", value=str(request.id)),
        session,
        school_class,
        Role.OWNER,
    )

    member = await session.scalar(select(BotUser).where(BotUser.telegram_id == 77))
    assert member is not None
    assert member.role is Role.EDITOR
    assert member.granted_by == 42
    assert "Редактор" in callback.message.last


# --------------------------------------------------------------------------
# Quarters: the screen they are edited from
# --------------------------------------------------------------------------


async def test_a_term_prompt_that_expired_sends_the_admin_back(session, school_class):
    message = FakeMessage(text="01.09.2026 - 31.10.2026")
    state = FakeState(data={})
    await term_edit_apply(message, state, session, school_class, Role.ADMIN)

    assert "/class" in message.last
    assert state.cleared


async def test_a_term_number_that_is_not_a_number_is_refused(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await term_edit_prompt(
        callback, SimpleNamespace(action="edit", value="пятая"), state, school_class, Role.ADMIN
    )

    assert callback.alerted
    assert callback.message.replies == []
    assert state.state is None


async def test_a_term_number_that_only_looks_like_a_digit_is_refused(session, school_class):
    """«²» passes `str.isdigit` and fails `int`, which is not a refusal but a
    traceback — and a handler that raises never reaches `callback.answer`."""
    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await term_edit_prompt(
        callback, SimpleNamespace(action="edit", value="²"), state, school_class, Role.ADMIN
    )

    assert callback.alerted
    assert callback.message.replies == []
    assert state.state is None


# --------------------------------------------------------------------------
# A permission check at every step
# --------------------------------------------------------------------------


async def _fixtures_for_every_step(session, school_class):
    """One row of every kind the management handlers take an id for."""
    subject = Subject(class_id=school_class.id, name="Астрономия")
    spare = BellSchedule(class_id=school_class.id, name="Сокращённое")
    device = DeviceToken(token_hash="hash-guard", class_id=school_class.id, telegram_id=42)
    override = DayOverride(
        class_id=school_class.id, date=Date(2027, 3, 12), kind=DayKind.HOLIDAY
    )
    request = AccessRequest(
        class_id=school_class.id,
        telegram_id=55,
        requested_role=Role.EDITOR,
        status="pending",
    )
    session.add_all([subject, spare, device, override, request])
    await session.commit()
    return subject, spare, device, override, request


async def _row_counts(session) -> dict[str, int]:
    counts = {}
    for model in (
        Subject,
        BellSchedule,
        BellPeriod,
        DeviceToken,
        DayOverride,
        AccessRequest,
        BotUser,
        TimetableEntry,
    ):
        counts[model.__name__] = len(list(await session.scalars(select(model))))
    return counts


async def test_every_management_step_checks_the_role_for_itself(session, school_class):
    """FSM state is per-user and therefore attacker-controlled.

    A client can put itself into ``EditSubject.name`` and send a message, so a
    step that trusted the step before it would be a rename with no permission
    check at all. The same goes for a callback: the payload is whatever the
    client sent. This presses every management step as an observer and
    expects each one to refuse on its own — and the database to be untouched
    afterwards, which is the part a refusal that merely stopped drawing would
    not give.
    """
    subject, spare, device, override, request = await _fixtures_for_every_step(
        session, school_class
    )
    before = await _row_counts(session)
    viewer = Role.VIEWER
    day = override.date.isoformat()

    async def press(name, call):
        callback = FakeCallback(message=FakeEditable())
        await call(callback)
        assert callback.alerted, f"{name} did not refuse"
        assert callback.message.replies == [], f"{name} drew a page for a наблюдатель"

    async def send(name, call, data=None):
        message = FakeMessage(text="что угодно")
        state = FakeState(data=dict(data or {}))
        await call(message, state)
        assert message.replies == [], f"{name} answered a наблюдатель"
        assert state.cleared, f"{name} left the state open"

    await press(
        "subject_field",
        lambda cb: subject_field(
            cb,
            SimpleNamespace(action="field", value=f"name:{subject.id}"),
            FakeState(),
            session,
            school_class,
            viewer,
        ),
    )
    await press(
        "subject_colour_pick",
        lambda cb: subject_colour_pick(
            cb,
            SimpleNamespace(action="colour", value=f"{subject.id}:5B6ABF"),
            FakeState(),
            session,
            school_class,
            viewer,
        ),
    )
    await press(
        "subject_delete",
        lambda cb: subject_delete(
            cb,
            SimpleNamespace(action="delete", value=str(subject.id)),
            FakeState(),
            session,
            school_class,
            viewer,
        ),
    )
    await press(
        "subject_add",
        lambda cb: subject_add(cb, FakeState(), school_class, viewer),
    )
    await press(
        "subjects_collect",
        lambda cb: subjects_collect(cb, FakeState(), session, school_class, viewer),
    )
    await press(
        "subjects_list",
        lambda cb: subjects_list(cb, FakeState(), session, school_class, viewer),
    )
    await press(
        "holidays_list",
        lambda cb: holidays_list(
            cb, DayKindAction(action="list"), FakeState(), session, school_class, viewer
        ),
    )
    await press(
        "holiday_add", lambda cb: holiday_add(cb, FakeState(), school_class, viewer)
    )
    await press(
        "holiday_pick_date",
        lambda cb: holiday_pick_date(
            cb,
            SimpleNamespace(action="pick_date", value=day),
            FakeState(),
            school_class,
            viewer,
        ),
    )
    await press(
        "holiday_kind",
        lambda cb: holiday_kind(
            cb,
            SimpleNamespace(action="kind", value=f"{day}:holiday"),
            FakeState(),
            session,
            school_class,
            viewer,
        ),
    )
    await press(
        "holiday_bells",
        lambda cb: holiday_bells(
            cb,
            SimpleNamespace(action="bells", value=f"{day}:{spare.id}"),
            FakeState(),
            session,
            school_class,
            viewer,
        ),
    )
    await press(
        "holiday_delete",
        lambda cb: holiday_delete(
            cb,
            SimpleNamespace(action="delete", value=day),
            FakeState(),
            session,
            school_class,
            viewer,
        ),
    )
    await press(
        "holiday_period_start",
        lambda cb: holiday_period_start(cb, FakeState(), school_class, viewer),
    )
    await press(
        "holiday_period_kind",
        lambda cb: holiday_period_kind(
            cb,
            SimpleNamespace(action="period_kind", value=DayKind.REMOTE.value),
            FakeState(),
            school_class,
            viewer,
        ),
    )
    await press(
        "bells_list", lambda cb: bells_list(cb, FakeState(), session, school_class, viewer)
    )
    await press(
        "bells_edit",
        lambda cb: bells_edit(
            cb,
            SimpleNamespace(action="edit", value=str(spare.id)),
            FakeState(),
            session,
            school_class,
            viewer,
        ),
    )
    await press("bells_create", lambda cb: bells_create(cb, FakeState(), school_class, viewer))
    await press(
        "bells_make_default",
        lambda cb: bells_make_default(
            cb,
            SimpleNamespace(action="default", value=str(spare.id)),
            session,
            school_class,
            viewer,
        ),
    )
    await press(
        "bells_delete",
        lambda cb: bells_delete(
            cb,
            SimpleNamespace(action="delete", value=str(spare.id)),
            session,
            school_class,
            viewer,
        ),
    )
    await press(
        "devices_list",
        lambda cb: devices_list(cb, FakeState(), session, school_class, viewer),
    )
    await press(
        "device_revoke",
        lambda cb: device_revoke(
            cb,
            SimpleNamespace(action="revoke", value=str(device.id)),
            session,
            school_class,
            viewer,
        ),
    )
    await press(
        "device_unlink",
        lambda cb: device_unlink(
            cb,
            SimpleNamespace(action="unlink", value=str(device.id)),
            session,
            school_class,
            viewer,
        ),
    )
    await press(
        "audit_page",
        lambda cb: audit_page(
            cb,
            SimpleNamespace(action="page", value="30"),
            FakeState(),
            session,
            school_class,
            viewer,
        ),
    )
    await press(
        "class_root", lambda cb: class_root(cb, FakeState(), session, school_class, viewer)
    )
    await press(
        "class_field_prompt",
        lambda cb: class_field_prompt(
            cb, SimpleNamespace(action="rename"), FakeState(), school_class, viewer
        ),
    )
    await press(
        "class_delete_prompt",
        lambda cb: class_delete_prompt(cb, FakeState(), school_class, viewer),
    )
    await press(
        "class_diary_bind", lambda cb: class_diary_bind(cb, session, school_class, viewer)
    )
    await press(
        "calendar_rotate", lambda cb: calendar_rotate(cb, session, school_class, viewer)
    )
    await press(
        "import_cancel", lambda cb: import_cancel(cb, FakeState(), school_class, viewer)
    )
    await press(
        "import_apply",
        lambda cb: import_apply(cb, FakeState(), session, school_class, viewer),
    )
    await press(
        "request_approve",
        lambda cb: request_approve(
            cb,
            SimpleNamespace(action="approve", value=str(request.id)),
            session,
            school_class,
            viewer,
        ),
    )
    await press(
        "request_decline",
        lambda cb: request_decline(
            cb,
            SimpleNamespace(action="decline", value=str(request.id)),
            session,
            school_class,
            viewer,
        ),
    )
    await press(
        "terms_list", lambda cb: terms_list(cb, FakeState(), session, school_class, viewer)
    )
    await press(
        "terms_scheme",
        lambda cb: terms_scheme(
            cb,
            SimpleNamespace(action="scheme", value="semester"),
            FakeState(),
            session,
            school_class,
            viewer,
        ),
    )
    await press(
        "term_edit_prompt",
        lambda cb: term_edit_prompt(
            cb, SimpleNamespace(action="edit", value="1"), FakeState(), school_class, viewer
        ),
    )

    subject_state = {"subject_id": subject.id}
    await send(
        "subject_rename",
        lambda m, st: subject_rename(m, st, session, school_class, viewer),
        subject_state,
    )
    await send(
        "subject_short_name",
        lambda m, st: subject_short_name(m, st, session, school_class, viewer),
        subject_state,
    )
    await send(
        "subject_teacher",
        lambda m, st: subject_teacher(m, st, session, school_class, viewer),
        subject_state,
    )
    await send(
        "subject_colour_typed",
        lambda m, st: subject_colour_typed(m, st, session, school_class, viewer),
        subject_state,
    )
    await send(
        "subject_create",
        lambda m, st: subject_create(m, st, session, school_class, viewer),
    )
    await send(
        "holiday_typed_date",
        lambda m, st: holiday_typed_date(m, st, school_class, viewer),
    )
    await send(
        "holiday_note",
        lambda m, st: holiday_note(m, st, session, school_class, viewer),
        {"date": day},
    )
    await send(
        "holiday_period_apply",
        lambda m, st: holiday_period_apply(m, st, session, school_class, viewer),
    )
    await send(
        "bells_rows_apply",
        lambda m, st: bells_rows_apply(m, st, session, school_class, viewer),
        {"schedule_id": spare.id},
    )
    await send("bells_new_name", lambda m, st: bells_new_name(m, st, school_class, viewer))
    await send(
        "bells_new_rows",
        lambda m, st: bells_new_rows(m, st, session, school_class, viewer),
        {"name": "Суббота"},
    )
    await send(
        "class_field_apply",
        lambda m, st: class_field_apply(m, st, session, school_class, viewer),
        {"field": "rename"},
    )
    await send(
        "class_delete_apply",
        lambda m, st: class_delete_apply(m, st, session, school_class, viewer),
    )
    await send("import_preview", lambda m, st: import_preview(m, st, school_class, viewer))
    await send(
        "term_edit_apply",
        lambda m, st: term_edit_apply(m, st, session, school_class, viewer),
        {"term_index": 1},
    )

    # The card behind «✏️», which nothing pressed until the derived list below
    # was written: it shows the teacher, the colour and the short name, and its
    # own guard is the only thing between those and a payload anybody can
    # craft.
    await press(
        "subject_open",
        lambda cb: subject_open(
            cb,
            SimpleNamespace(action="open", value=str(subject.id)),
            FakeState(),
            session,
            school_class,
            viewer,
        ),
    )

    # The commands. They are a different refusal from the two above — a person
    # who types «/bells» is owed a sentence saying why not, where an FSM step
    # they never opened is owed silence — so they are asserted as one, against
    # the module's own refusal strings rather than against a copy of them.
    refusals = {NO_ACCESS, NEED_EDITOR, NEED_ADMIN, NEED_OWNER}

    async def command(name, call):
        message = FakeMessage(text="/что-угодно")
        state = FakeState()
        await call(message, state)
        assert message.replies, f"{name} said nothing at all to a наблюдатель"
        assert message.last in refusals, f"{name} drew a page for a наблюдатель"
        assert not state.cleared, f"{name} touched the state of a caller it refused"

    await command("cmd_subjects", lambda m, st: cmd_subjects(m, st, session, school_class, viewer))
    await command("cmd_holidays", lambda m, st: cmd_holidays(m, st, session, school_class, viewer))
    await command("cmd_bells", lambda m, st: cmd_bells(m, st, session, school_class, viewer))
    await command("cmd_devices", lambda m, st: cmd_devices(m, st, session, school_class, viewer))
    await command("cmd_class", lambda m, st: cmd_class(m, st, session, school_class, viewer))
    await command("cmd_log", lambda m, st: cmd_log(m, st, session, school_class, viewer))
    await command("cmd_stats", lambda m, st: cmd_stats(m, st, session, school_class, viewer))
    await command("cmd_export", lambda m, st: cmd_export(m, st, session, school_class, viewer))
    await command("cmd_import", lambda m, st: cmd_import(m, st, school_class, viewer))

    assert await _row_counts(session) == before
    assert school_class.name == "9А"
    assert school_class.diary_provider is None


#: Handlers on the management router that the test above does not press,
#: each with the reason it is not a step a наблюдатель must be refused at.
#:
#: Everything else is exempted by rule rather than by name: a handler whose
#: own guard asks for `Role.VIEWER` admits an observer on purpose, and
#: pressing it to demand a refusal would assert the opposite of the product.
#: These two ask for neither.
NOT_A_ROLE_GATE = {
    "class_switch_to": "switching between your own classes; the guard is «are you in it»",
}


def _minimum_role_of(source: str) -> dict[str, Role | None]:
    """What each function in ``handlers/manage`` demands, read off its own body.

    Two spellings, because the module uses two. ``_allowed(school_class, role,
    Role.X)`` is the shared check — its own docstring says it exists so that no
    handler invents a version of its own — and ``role.at_least(Role.X)`` is the
    one place that checks inline. Reading them beats keeping a second copy of
    the table in this file, which is the mistake that made the list above
    fifty-seven names long and nine names short.

    The *weakest* role mentioned wins, not the first one found: a guard reading
    ``not _allowed(…, VIEWER) or role.at_least(EDITOR)`` admits наблюдатели and
    refuses everybody else, so its minimum is наблюдатель. ``None`` means
    neither spelling appears, which is either a handler that needs no role or
    one that forgot; the test below refuses to guess which.
    """
    found: dict[str, Role | None] = {}
    for node in ast.walk(ast.parse(source)):
        if not isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef):
            continue
        named: list[Role] = []
        for call in ast.walk(node):
            if not isinstance(call, ast.Call):
                continue
            argument: ast.expr | None = None
            if getattr(call.func, "id", "") == "_allowed" and len(call.args) == 3:
                argument = call.args[2]
            elif getattr(call.func, "attr", "") == "at_least" and len(call.args) == 1:
                argument = call.args[0]
            if isinstance(argument, ast.Attribute) and argument.attr in Role.__members__:
                named.append(Role[argument.attr])
        found[node.name] = min(named, key=lambda role: role.rank) if named else None
    return found


def test_the_role_check_above_reaches_every_handler_that_has_one():
    """The list of steps pressed above, derived instead of remembered.

    It used to be fifty-seven handlers written out by hand, of the sixty-six
    registered — and the nine it missed included «✏️ Предмет», whose admin
    guard could be deleted with the whole suite staying green. Both halves are
    read from the source here: which handlers the router carries, and what each
    one demands. A handler registered tomorrow is covered the day it is
    registered, and one that quietly stops demanding a role is named rather
    than dropped.
    """
    from app.bot.handlers import manage as manage_module

    source = Path(manage_module.__file__).read_text(encoding="utf-8")
    minimums = _minimum_role_of(source)

    registered = {
        handler.callback.__name__
        for observer in (manage_router.callback_query, manage_router.message)
        for handler in observer.handlers
    }
    assert len(registered) > 50, "the router looks empty; this test would prove nothing"

    pressed = {
        node.id
        for node in ast.walk(
            ast.parse(textwrap.dedent(inspect.getsource(test_every_management_step_checks_the_role_for_itself)))
        )
        if isinstance(node, ast.Name)
    }

    ungated = sorted(
        name
        for name in registered
        if minimums.get(name) is None and name not in NOT_A_ROLE_GATE
    )
    assert not ungated, (
        "these are registered on the management router and never call `_allowed`; "
        f"decide what they demand before exempting them: {ungated}"
    )

    unpressed = sorted(
        name
        for name in registered
        if minimums.get(name) not in (None, Role.VIEWER) and name not in pressed
    )
    assert not unpressed, (
        "these demand a role above наблюдатель and the test above never presses them, "
        f"so deleting the guard would change nothing here: {unpressed}"
    )


async def test_a_crafted_id_finds_nothing_rather_than_somebody_elses_row(
    session, school_class
):
    """Callback data is user-supplied, so every id is re-scoped by the query
    that reads it. A payload carrying a word where a number belongs must land
    on «не найдено», not on an exception and not on another class's row."""
    subject, spare, device, override, request = await _fixtures_for_every_step(
        session, school_class
    )
    before = await _row_counts(session)

    async def press(name, call):
        callback = FakeCallback(message=FakeEditable())
        await call(callback)
        assert callback.alerted, f"{name} accepted a crafted id"
        assert callback.message.replies == [], name

    await press(
        "subject_field",
        lambda cb: subject_field(
            cb,
            SimpleNamespace(action="field", value="name:взлом"),
            FakeState(),
            session,
            school_class,
            Role.ADMIN,
        ),
    )
    await press(
        "subject_colour_pick",
        lambda cb: subject_colour_pick(
            cb,
            SimpleNamespace(action="colour", value="взлом:5B6ABF"),
            FakeState(),
            session,
            school_class,
            Role.ADMIN,
        ),
    )
    await press(
        "subject_delete",
        lambda cb: subject_delete(
            cb,
            SimpleNamespace(action="delete", value="взлом"),
            FakeState(),
            session,
            school_class,
            Role.ADMIN,
        ),
    )
    await press(
        "bells_edit",
        lambda cb: bells_edit(
            cb,
            SimpleNamespace(action="edit", value="взлом"),
            FakeState(),
            session,
            school_class,
            Role.ADMIN,
        ),
    )
    await press(
        "bells_make_default",
        lambda cb: bells_make_default(
            cb,
            SimpleNamespace(action="default", value="взлом"),
            session,
            school_class,
            Role.ADMIN,
        ),
    )
    await press(
        "device_revoke",
        lambda cb: device_revoke(
            cb,
            SimpleNamespace(action="revoke", value="взлом"),
            session,
            school_class,
            Role.ADMIN,
        ),
    )
    await press(
        "device_unlink",
        lambda cb: device_unlink(
            cb,
            SimpleNamespace(action="unlink", value="взлом"),
            session,
            school_class,
            Role.ADMIN,
        ),
    )
    await press(
        "request_decline",
        lambda cb: request_decline(
            cb,
            SimpleNamespace(action="decline", value="взлом"),
            session,
            school_class,
            Role.ADMIN,
        ),
    )
    await press(
        "holiday_kind",
        lambda cb: holiday_kind(
            cb,
            SimpleNamespace(action="kind", value="никогда:holiday"),
            FakeState(),
            session,
            school_class,
            Role.ADMIN,
        ),
    )
    await press(
        "holiday_kind unknown tag",
        lambda cb: holiday_kind(
            cb,
            SimpleNamespace(action="kind", value=f"{override.date.isoformat()}:праздник"),
            FakeState(),
            session,
            school_class,
            Role.ADMIN,
        ),
    )
    await press(
        "holiday_bells",
        lambda cb: holiday_bells(
            cb,
            SimpleNamespace(action="bells", value="никогда:1"),
            FakeState(),
            session,
            school_class,
            Role.ADMIN,
        ),
    )
    await press(
        "holiday_bells unknown schedule",
        lambda cb: holiday_bells(
            cb,
            SimpleNamespace(
                action="bells", value=f"{override.date.isoformat()}:{spare.id + 999}"
            ),
            FakeState(),
            session,
            school_class,
            Role.ADMIN,
        ),
    )
    await press(
        "holiday_delete",
        lambda cb: holiday_delete(
            cb,
            SimpleNamespace(action="delete", value="никогда"),
            FakeState(),
            session,
            school_class,
            Role.ADMIN,
        ),
    )

    assert await _row_counts(session) == before
    await session.refresh(device)
    assert device.revoked is False
    assert device.telegram_id == 42
    await session.refresh(override)
    assert override.kind is DayKind.HOLIDAY
    assert override.bell_schedule_id is None


# --------------------------------------------------------------------------
# Small things that have a branch of their own
# --------------------------------------------------------------------------


async def test_each_text_field_of_a_subject_opens_its_own_prompt(session, school_class):
    subject = await _subject(session, school_class, name="Алгебра")

    expected = {
        "name": "Новое название",
        "short": "узких экранов",
        "teacher": "Кто ведёт",
    }
    for tag, wording in expected.items():
        callback = FakeCallback(message=FakeEditable())
        state = FakeState()
        await subject_field(
            callback,
            SimpleNamespace(action="field", value=f"{tag}:{subject.id}"),
            state,
            session,
            school_class,
            Role.ADMIN,
        )
        assert "Алгебра" in callback.message.last, tag
        assert wording in callback.message.last, tag
        assert state.state is not None, tag
        assert state.data["subject_id"] == subject.id, tag


async def test_the_add_subject_button_asks_for_a_name(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await subject_add(callback, state, school_class, Role.ADMIN)

    assert "Название нового предмета" in callback.message.last
    assert state.state is not None


async def test_a_subject_deleted_while_its_prompt_was_open_says_so(session, school_class):
    """Every update may reach a fresh process and an hour may pass between the
    prompt and the answer; the row it named can be gone by then."""
    subject = await _subject(session, school_class, name="Астрономия")
    gone = subject.id
    await session.delete(subject)
    await session.commit()

    for handler in (subject_short_name, subject_teacher, subject_colour_typed):
        message = FakeMessage(text="что-нибудь")
        state = FakeState(data={"subject_id": gone})
        await handler(message, state, session, school_class, Role.ADMIN)
        assert "уже удалён" in message.last, handler.__name__
        assert state.cleared, handler.__name__


async def test_the_no_colour_button_clears_the_colour(session, school_class):
    subject = await _subject(session, school_class, name="Алгебра", color="#5B6ABF")

    callback = FakeCallback(message=FakeEditable())
    await subject_colour_pick(
        callback,
        SimpleNamespace(action="colour", value=f"{subject.id}:none"),
        FakeState(),
        session,
        school_class,
        Role.ADMIN,
    )

    await session.refresh(subject)
    assert subject.color is None
    assert "Цвет: —" in callback.message.last


async def test_a_colour_button_carrying_something_that_is_not_a_colour_is_refused(
    session, school_class
):
    subject = await _subject(session, school_class, name="Алгебра", color="#5B6ABF")

    callback = FakeCallback(message=FakeEditable())
    await subject_colour_pick(
        callback,
        SimpleNamespace(action="colour", value=f"{subject.id}:зелёный"),
        FakeState(),
        session,
        school_class,
        Role.ADMIN,
    )

    await session.refresh(subject)
    assert subject.color == "#5B6ABF"
    assert callback.alerted


async def test_a_period_that_is_not_two_dates_marks_nothing(session, school_class):
    for text in ("26 октября", "05.11-26.10", ""):
        message = FakeMessage(text=text)
        state = FakeState()
        await holiday_period_apply(message, state, session, school_class, Role.ADMIN)

        assert "Не понял период" in message.last, text
        assert not state.cleared, text
    assert await session.scalar(select(DayOverride)) is None


# --------------------------------------------------------------------------
# Empty and nearly empty pages
# --------------------------------------------------------------------------


async def test_a_class_with_nothing_in_it_draws_every_page_as_a_sentence(session):
    """A brand-new class has no timetable, no subjects, no bells and no
    phones. Each of those pages has an empty branch, and an empty branch is
    where a renderer most often reaches for something that is not there."""
    bare = SchoolClass(name="10Б", join_code="BARE01")
    session.add(bare)
    await session.commit()

    subjects = FakeMessage()
    await cmd_subjects(subjects, FakeState(), session, bare, Role.OWNER)
    assert "Список пуст" in subjects.last

    bells = FakeMessage()
    await cmd_bells(bells, FakeState(), session, bare, Role.OWNER)
    assert "Ни одного расписания ещё нет" in bells.last

    devices = FakeMessage()
    await cmd_devices(devices, FakeState(), session, bare, Role.OWNER)
    assert "Ни одного телефона не подключено" in devices.last

    holidays = FakeMessage()
    await cmd_holidays(holidays, FakeState(), session, bare, Role.OWNER)
    assert "Впереди особых дней нет" in holidays.last

    log = FakeMessage()
    await cmd_log(log, FakeState(), session, bare, Role.OWNER)
    assert "Записей пока нет" in log.last

    card = FakeMessage()
    await cmd_class(card, FakeState(), session, bare, Role.OWNER)
    assert "👥 Участников: 0 · 📱 устройств: 0" in card.last
    assert "🏫 Школа: —" in card.last
    assert "Запросов доступа" not in card.last

    export = FakeMessage()
    await cmd_export(export, FakeState(), session, bare, Role.OWNER)
    assert "экспортировать нечего" in export.last

    found = FakeMessage()
    await cmd_find(found, _command("параграф"), session, bare, Role.OWNER)
    assert "Ничего не нашлось" in found.last

    # And none of them said «None» or «0 дн.» along the way.
    for message in (subjects, bells, devices, holidays, log, card, export, found):
        assert "None" not in message.last


async def test_a_subject_row_carries_the_short_name_and_the_teacher(session, school_class):
    """The list row is where an admin checks that a subject is filled in, so
    every filled column has to show on it."""
    await _subject(
        session,
        school_class,
        name="Алгебра и начала анализа",
        short_name="Алгебра",
        teacher="Иванова И. И.",
        color="#5B6ABF",
    )

    message = FakeMessage()
    await cmd_subjects(message, FakeState(), session, school_class, Role.ADMIN)

    row = next(
        line for line in message.last.splitlines() if "Алгебра и начала анализа" in line
    )
    assert "Алгебра" in row
    assert "Иванова И. И." in row
    assert "■ #5B6ABF" in row


async def test_today_is_marked_on_the_special_days_card(session, school_class):
    """«Сегодня» is the one row on that page an admin might act on this
    morning, and the date alone does not say which one it is."""
    today = datetime.now(school_class.tz).date()
    session.add(DayOverride(class_id=school_class.id, date=today, kind=DayKind.HOLIDAY))
    session.add(
        DayOverride(
            class_id=school_class.id, date=today + timedelta(days=1), kind=DayKind.REMOTE
        )
    )
    await session.commit()

    message = FakeMessage()
    await cmd_holidays(message, FakeState(), session, school_class, Role.EDITOR)

    lines = message.last.splitlines()
    marked = [line for line in lines if "сегодня" in line]
    assert len(marked) == 1
    assert f"{today:%d.%m}" in marked[0]
    assert "🏖 Каникулы / выходной" in marked[0]


async def test_the_class_card_counts_the_people_waiting_at_the_door(session, school_class):
    session.add(
        AccessRequest(
            class_id=school_class.id,
            telegram_id=55,
            requested_role=Role.EDITOR,
            status="pending",
        )
    )
    session.add(
        AccessRequest(
            class_id=school_class.id,
            telegram_id=56,
            requested_role=Role.EDITOR,
            status="declined",
        )
    )
    await session.commit()

    message = FakeMessage()
    await cmd_class(message, FakeState(), session, school_class, Role.ADMIN)

    # The one that was declined is not waiting for anybody.
    assert "🙋 Запросов доступа: <b>1</b>" in message.last


async def test_a_search_hit_says_сегодня_and_завтра_before_it_says_a_date(
    session, school_class
):
    """The dates a search turns up are mostly the next two days, and «завтра»
    is what somebody looking for their homework actually needs to read."""
    today = datetime.now(school_class.tz).date()
    for offset, text in ((0, "на сегодня"), (1, "на завтра"), (5, "на потом")):
        session.add(
            Homework(
                class_id=school_class.id,
                due_date=today + timedelta(days=offset),
                subject_name="Алгебра",
                text=f"параграф {text}",
            )
        )
    await session.commit()

    message = FakeMessage()
    await cmd_find(message, _command("параграф"), session, school_class, Role.VIEWER)

    card = message.last
    assert "(сегодня, " in card
    assert "(завтра, " in card
    later = today + timedelta(days=5)
    assert f"({later.day} " in card


async def test_a_member_with_no_username_is_named_not_numbered(session, school_class):
    """An @username is the best handle, a name is the next best, and the id is
    the fallback — because an id is at least something an admin can act on."""
    session.add(
        BotUser(
            telegram_id=42,
            class_id=school_class.id,
            role=Role.ADMIN,
            full_name="Мария Петровна",
        )
    )
    await audit.record(session, school_class.id, 42, "test", "переименован предмет")
    await audit.record(session, school_class.id, 999, "test", "чужак что-то сделал")
    await session.commit()

    message = FakeMessage()
    await cmd_log(message, FakeState(), session, school_class, Role.ADMIN)

    assert "Мария Петровна" in message.last
    assert "· 999 ·" in message.last


def test_a_line_too_long_for_one_message_is_cut_rather_than_dropped():
    """An export doubles as the class's backup, so losing a line of somebody's
    timetable to keep the formatting tidy would be the wrong trade."""
    long_line = "1. " + "Предмет" * 200
    parts = split_text(f"== Понедельник ==\n{long_line}\n2. Физика", limit=100)

    assert all(len(part) <= 100 for part in parts)
    assert "".join(parts).count("Предмет") == 200
    assert parts[0] == "== Понедельник =="
    assert parts[-1].endswith("2. Физика")
    # The seams fall inside the long line and nowhere else: every part but the
    # first and the last is a slice of it, and nothing is invented or lost.
    body = f"== Понедельник ==\n{long_line}\n2. Физика"
    assert "".join(parts).replace("\n", "") == body.replace("\n", "")


def test_splitting_nothing_gives_one_empty_message():
    """``cmd_export`` sends one message per part; an empty list would send
    none at all and look like a command that did nothing."""
    assert split_text("") == [""]


# --------------------------------------------------------------------------
# Every drawn row is reachable
# --------------------------------------------------------------------------


def _reachable(markup, values: list[str]) -> int:
    """How many of ``values`` some button on the keyboard actually carries.

    Asking the keyboard about the rows by name rather than counting its rows:
    an action row («➕ Добавить», «‹ Назад») is not a row of the list, and the
    two separators here are both real — ``CallbackData`` packs with ``|`` or
    ``:`` depending on the payload.
    """
    carried = {
        re.split(r"[|:]", button.callback_data)[-1]
        for row in markup.inline_keyboard
        for button in row
    }
    return sum(1 for value in values if value in carried)


def test_no_list_page_draws_a_row_the_keyboard_cannot_reach():
    """Three of these four pages used to draw more rows than they offered
    buttons for — forty subjects above thirty ✏️, twenty schedules above
    ten — so the tail sat on the screen with no way to open it and nothing
    saying it was out of reach. «… и ещё N» is now the only way a row goes
    missing, and it counts from the same number the keyboard builds from."""
    count = 60

    subjects = [
        SimpleNamespace(id=n, name=f"Предмет {n}", short_name=None, teacher=None, color=None)
        for n in range(1, count + 1)
    ]
    assert render_subjects(subjects).count("Предмет ") == SUBJECTS_MAX
    assert f"… и ещё {count - SUBJECTS_MAX}" in render_subjects(subjects)
    ids = [str(subject.id) for subject in subjects]
    assert _reachable(subject_list_keyboard(subjects, can_edit=True), ids) == SUBJECTS_MAX

    schedules = [
        SimpleNamespace(id=n, name=f"Звонки {n}", periods=[]) for n in range(1, count + 1)
    ]
    assert render_bells(schedules, None).count("Звонки ") == BELLS_MAX
    assert f"… и ещё {count - BELLS_MAX}" in render_bells(schedules, None)
    ids = [str(schedule.id) for schedule in schedules]
    assert _reachable(bells_list_keyboard(schedules, None), ids) == BELLS_MAX

    devices = [
        SimpleNamespace(
            id=n, device_name=f"Телефон {n}", telegram_id=None, last_seen_at=None
        )
        for n in range(1, count + 1)
    ]
    assert render_devices(devices, {}).count("Телефон ") == DEVICES_MAX
    assert f"… и ещё {count - DEVICES_MAX}" in render_devices(devices, {})
    ids = [str(device.id) for device in devices]
    assert _reachable(device_keyboard(devices), ids) == DEVICES_MAX

    overrides = [
        DayOverride(
            id=n,
            class_id=1,
            date=Date(2026, 9, 1) + timedelta(days=n),
            kind=DayKind.HOLIDAY,
            note=f"Поездка {n}",
        )
        for n in range(1, count + 1)
    ]
    assert render_holidays(overrides, {}, Date(2026, 9, 1)).count("Поездка ") == LIST_MAX
    assert f"… и ещё {count - LIST_MAX}" in render_holidays(overrides, {}, Date(2026, 9, 1))
    days = [override.date.isoformat() for override in overrides]
    assert _reachable(holiday_list_keyboard(overrides, can_edit=True), days) == LIST_MAX
