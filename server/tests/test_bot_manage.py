"""The management layer: предметы, особые дни, устройства, запросы доступа,
импорт и смена класса.

Same approach as ``test_bot_handlers.py``: the handlers are called directly
with lightweight stubs. aiogram does not enforce its types at call time, and
what is worth testing here is the permission checks, the re-scoping of ids and
the transactions - not Telegram's transport.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date as Date
from datetime import datetime, timedelta
from types import SimpleNamespace
from typing import Any

from sqlalchemy import select

from app.bot.handlers.manage import (
    bells_delete,
    bells_rows_apply,
    class_delete_apply,
    class_switch_to,
    cmd_bells,
    cmd_calendar,
    cmd_class,
    cmd_devices,
    cmd_export,
    cmd_find,
    cmd_holidays,
    cmd_log,
    cmd_request,
    cmd_subjects,
    device_revoke,
    holiday_period_apply,
    import_apply,
    import_preview,
    request_approve,
    subject_rename,
)
from app.bot.handlers.timetable import timetable_apply
from app.bot.manage_render import split_text, time_ago
from app.bot.middlewares import active_class, prefs_key
from app.db import SessionLocal
from app.fsm_storage import DatabaseStorage
from app.models import (
    AccessRequest,
    BellPeriod,
    BellSchedule,
    BotUser,
    DayOverride,
    DeviceToken,
    Homework,
    LessonOverride,
    OverrideAction,
    Role,
    SchoolClass,
    Subject,
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


def _command(args: str | None):
    """Just enough of aiogram's CommandObject for a handler that reads args."""
    return SimpleNamespace(command="x", args=args)


# --------------------------------------------------------------------------
# Callback prefixes
# --------------------------------------------------------------------------


def test_management_callbacks_do_not_collide_with_the_everyday_ones():
    """A duplicate prefix would not fail a build - it would route one
    feature's button into another feature's handler."""
    from aiogram.filters.callback_data import CallbackData

    from app.bot import keyboards, manage_keyboards

    prefixes: dict[str, str] = {}
    seen: set[type] = set()
    for module in (keyboards, manage_keyboards):
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


# --------------------------------------------------------------------------
# Расписание: чётность недели
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
# Предметы
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
    # One timetable row, one homework row, one override.
    assert "3" in message.last


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
# Особые дни
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
    assert "5 дней" in message.replies[0]


async def test_an_editor_cannot_mark_a_whole_period(session, school_class):
    message = FakeMessage(text="02.01.2027-06.01.2027")
    state = FakeState()
    await holiday_period_apply(message, state, session, school_class, Role.EDITOR)

    assert await session.scalar(select(DayOverride)) is None
    assert message.replies == []
    assert state.cleared


# --------------------------------------------------------------------------
# Устройства
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
# Запросы доступа
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
# Поиск
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
# Импорт
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
# Удаление класса
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
# Смена класса
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
# Уведомления администраторам
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
# Смена класса из бота
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
# Звонки
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
# Отображение
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
