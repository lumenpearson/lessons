"""The shared services: what the bot handlers and the API endpoints build on.

Telegram is stubbed with a bot that records what it was asked to send and can
be told to behave like a user who blocked the bot or a network that is down.
Everything else runs against the real (SQLite) database.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, date, datetime, time, timedelta
from typing import Any

import pytest
from aiogram.exceptions import TelegramForbiddenError
from sqlalchemy import select

from app.crypto import seal
from app.db import SessionLocal
from app.models import (
    BellPeriod,
    BotUser,
    DayEvent,
    DayKind,
    DayOverride,
    DeviceToken,
    DiarySession,
    EventKind,
    Homework,
    HomeworkDone,
    LessonOverride,
    OverrideAction,
    Role,
    SchoolClass,
    TimetableEntry,
    WeekParity,
)
from app.schedule import ResolvedDay, ResolvedHomework, ResolvedLesson, ScheduleResolver
from app.security import hash_token, new_join_code, new_token
from app.services import audit, calendar, linking, notify, reminders, stats, tasks, timetable_io

# 2026-09-07 is a Monday; the fixture class has lessons on Mondays only.
MONDAY = date(2026, 9, 7)
SATURDAY = date(2026, 9, 12)
NEXT_MONDAY = date(2026, 9, 14)

# 07:30 on Monday in Vladivostok (UTC+10) is 21:30 UTC on Sunday.
VLADIVOSTOK_0730 = datetime(2026, 9, 6, 21, 30, tzinfo=UTC)
# 07:30 on Monday in Moscow (UTC+3).
MOSCOW_0730 = datetime(2026, 9, 7, 4, 30, tzinfo=UTC)
# 20:00 on Monday in Moscow.
MOSCOW_2000 = datetime(2026, 9, 7, 17, 0, tzinfo=UTC)


@dataclass
class FakeBot:
    """Records sends; ``blocked`` ids answer like a user who blocked the bot,
    ``broken`` ids like a network that is down."""

    sent: list[tuple[int, str]] = field(default_factory=list)
    blocked: set[int] = field(default_factory=set)
    broken: set[int] = field(default_factory=set)

    async def send_message(self, chat_id: int, text: str, **_: Any) -> None:
        if chat_id in self.blocked:
            raise TelegramForbiddenError(
                method=None, message="Forbidden: bot was blocked by the user"
            )
        if chat_id in self.broken:
            raise RuntimeError("network down")
        self.sent.append((chat_id, text))

    @property
    def recipients(self) -> list[int]:
        return sorted(chat_id for chat_id, _ in self.sent)


async def _device(session, school_class, **extra) -> DeviceToken:
    device = DeviceToken(
        token_hash=hash_token(new_token()),
        class_id=school_class.id,
        device_name="Pixel",
        **extra,
    )
    session.add(device)
    await session.commit()
    return device


async def _class_in(session, zone: str, name: str = "11Б") -> SchoolClass:
    klass = SchoolClass(name=name, join_code=new_join_code(), timezone=zone)
    session.add(klass)
    await session.commit()
    return klass


async def _homework(session, school_class, due: date, subject="Алгебра", text="№ 12–15"):
    item = Homework(class_id=school_class.id, due_date=due, subject_name=subject, text=text)
    session.add(item)
    await session.commit()
    return item


# --------------------------------------------------------------------------
# audit
# --------------------------------------------------------------------------


async def test_audit_is_newest_first_and_truncated(session, school_class):
    await audit.record(session, school_class.id, 42, "homework.add", "первая")
    await audit.record(session, school_class.id, None, "homework.delete", "х" * 600)
    await session.commit()

    rows = await audit.recent(session, school_class.id)
    assert [row.action for row in rows] == ["homework.delete", "homework.add"]
    assert len(rows[0].summary) == 500
    assert rows[0].telegram_id is None
    assert rows[1].telegram_id == 42

    page = await audit.recent(session, school_class.id, limit=1, offset=1)
    assert [row.action for row in page] == ["homework.add"]


async def test_audit_record_leaves_the_commit_to_the_caller(session, school_class):
    # Read before the rollback: it expires every loaded row, and an expired
    # attribute cannot be reloaded from an async session by plain access.
    class_id = school_class.id
    await audit.record(session, class_id, 42, "homework.add", "не дошло")
    await session.rollback()
    assert await audit.recent(session, class_id) == []


async def test_audit_is_scoped_to_the_class(session, school_class):
    other = await _class_in(session, "Europe/Moscow")
    await audit.record(session, other.id, 42, "class.rename", "чужое")
    await session.commit()
    assert await audit.recent(session, school_class.id) == []


# --------------------------------------------------------------------------
# linking
# --------------------------------------------------------------------------


async def test_issue_link_code_is_short_unambiguous_and_stable(session, school_class):
    device = await _device(session, school_class)
    code = await linking.issue_link_code(session, device)

    assert len(code) == linking.LINK_CODE_LENGTH == 6
    assert code.isupper() or code.isdigit()
    assert not set(code) & set("O0I1")
    assert await linking.issue_link_code(session, device) == code

    async with SessionLocal() as other:
        assert (await other.get(DeviceToken, device.id)).link_code == code


async def test_issue_link_code_draws_again_on_a_collision(session, school_class, monkeypatch):
    taken = await _device(session, school_class, link_code="AAAAAA")
    device = await _device(session, school_class)
    draws = iter(["AAAAAA", "BBBBBB"])
    monkeypatch.setattr(linking, "new_join_code", lambda length: next(draws))

    assert await linking.issue_link_code(session, device) == "BBBBBB"
    assert taken.link_code == "AAAAAA"


async def test_link_device_claims_the_code_exactly_once(session, school_class):
    device = await _device(session, school_class)
    code = await linking.issue_link_code(session, device)

    linked = await linking.link_device(session, f"  {code.lower()} ", 42)
    assert linked is not None and linked.id == device.id
    assert device.telegram_id == 42
    assert device.is_linked
    assert device.linked_at is not None
    assert device.link_code is None

    # A used code is gone: the second person gets nothing and the first keeps the phone.
    assert await linking.link_device(session, code, 43) is None
    assert device.telegram_id == 42


async def test_link_device_refuses_unknown_empty_and_revoked(session, school_class):
    assert await linking.link_device(session, "", 42) is None
    assert await linking.link_device(session, "   ", 42) is None
    assert await linking.link_device(session, "NOPE22", 42) is None

    revoked = await _device(session, school_class, revoked=True)
    code = await linking.issue_link_code(session, revoked)
    assert await linking.link_device(session, code, 42) is None
    assert revoked.telegram_id is None


async def test_unlink_then_reissue_gives_a_fresh_code(session, school_class):
    device = await _device(session, school_class)
    code = await linking.issue_link_code(session, device)
    await linking.link_device(session, code, 42)

    await linking.unlink_device(session, device)
    assert device.telegram_id is None
    assert device.linked_at is None
    assert not device.is_linked

    fresh = await linking.issue_link_code(session, device)
    assert fresh != code
    assert await linking.link_device(session, code, 42) is None
    assert (await linking.link_device(session, fresh, 42)) is not None


async def test_devices_of_hides_revoked_unless_asked(session, school_class):
    first = await _device(session, school_class)
    gone = await _device(session, school_class, revoked=True)
    second = await _device(session, school_class)
    other = await _class_in(session, "Europe/Moscow")
    await _device(session, other)

    assert [d.id for d in await linking.devices_of(session, school_class.id)] == [
        first.id,
        second.id,
    ]
    assert [d.id for d in await linking.devices_of(session, school_class.id, True)] == [
        first.id,
        gone.id,
        second.id,
    ]


async def test_effective_role_follows_the_linked_account(session, school_class):
    device = await _device(session, school_class)
    assert await linking.effective_role(session, device) is None

    session.add(BotUser(telegram_id=42, class_id=school_class.id, role=Role.EDITOR))
    await session.commit()
    device.telegram_id = 42
    assert await linking.effective_role(session, device) is Role.EDITOR

    # Environment owners (OWNER_IDS=1000 in conftest) need no membership row.
    device.telegram_id = 1000
    assert await linking.effective_role(session, device) is Role.OWNER

    # Linked to somebody who is not in this class: a phone, but no rights.
    device.telegram_id = 77
    assert await linking.effective_role(session, device) is None


# --------------------------------------------------------------------------
# tasks: the message grammar
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    "raw, expected",
    [
        (
            "Купить тетрадь до 15.09 в 18:00 !",
            ("Купить тетрадь", date(2026, 9, 15), time(18, 0), 2),
        ),
        ("Сдать реферат до 15.09.2026", ("Сдать реферат", date(2026, 9, 15), None, 1)),
        ("Сдать реферат до 15.09.26", ("Сдать реферат", date(2026, 9, 15), None, 1)),
        # A DD.MM already behind us means next year.
        ("Сдать реферат до 01.03", ("Сдать реферат", date(2027, 3, 1), None, 1)),
        # An explicit year never rolls, even into the past.
        ("Сдать реферат до 01.03.2026", ("Сдать реферат", date(2026, 3, 1), None, 1)),
        ("Сдать реферат к 15.09", ("Сдать реферат", date(2026, 9, 15), None, 1)),
        ("Позвонить завтра", ("Позвонить", date(2026, 9, 13), None, 1)),
        ("Позвонить послезавтра", ("Позвонить", date(2026, 9, 14), None, 1)),
        ("Позвонить сегодня", ("Позвонить", date(2026, 9, 12), None, 1)),
        ("Доклад в пн", ("Доклад", date(2026, 9, 14), None, 1)),
        # Today is a Saturday: «в сб» is today, not next week.
        ("Доклад в сб", ("Доклад", date(2026, 9, 12), None, 1)),
        ("Доклад в пт.", ("Доклад", date(2026, 9, 18), None, 1)),
        ("Доклад во вторник", ("Доклад", date(2026, 9, 15), None, 1)),
        ("Доклад к пятнице", ("Доклад", date(2026, 9, 18), None, 1)),
        ("Доклад к понедельнику в 18.00", ("Доклад", date(2026, 9, 14), time(18, 0), 1)),
        ("Быть дома до 18:00", ("Быть дома", None, time(18, 0), 1)),
        ("Доклад ?", ("Доклад", None, None, 0)),
        ("Доклад!!", ("Доклад", None, None, 2)),
        ("!! Доклад", ("Доклад", None, None, 2)),
        ("ЗАВТРА В 7.30 !", ("", date(2026, 9, 13), time(7, 30), 2)),
        # Punctuation inside a sentence is punctuation.
        ("Что задали? Узнать до 18:00", ("Что задали? Узнать", None, time(18, 0), 1)),
        # «срок» ends in «к» but is not the preposition.
        ("срок 15.09 узнать", ("срок 15.09 узнать", None, None, 1)),
        ("Купить тетрадь, до 15.09", ("Купить тетрадь", date(2026, 9, 15), None, 1)),
        # Nonsense dates and clocks stay in the title rather than vanishing.
        ("до 31.02 сделать", ("до 31.02 сделать", None, None, 1)),
        ("Встреча в 25:00", ("Встреча в 25:00", None, None, 1)),
        ("Задача 3.14 в 12:00", ("Задача 3.14", None, time(12, 0), 1)),
        # «в» never introduces a date, so a dotted pair after it is a clock.
        ("Встреча в 10.10", ("Встреча", None, time(10, 10), 1)),
        # A second date-shaped token stays a date (kept in the title), not 10:10.
        ("Уборка завтра до 10.10", ("Уборка до 10.10", date(2026, 9, 13), None, 1)),
        ("  Много   пробелов   ", ("Много пробелов", None, None, 1)),
        ("х" * 300, ("х" * 200, None, None, 1)),
    ],
)
def test_parse_task_text(raw, expected):
    assert tasks.parse_task_text(raw, SATURDAY) == expected


def test_parse_task_text_first_token_of_each_kind_wins():
    title, due, at, _ = tasks.parse_task_text("Доклад завтра до 20.09 в 10:00 в 11:00", SATURDAY)
    assert due == date(2026, 9, 13)
    assert at == time(10, 0)
    assert title == "Доклад до 20.09 в 11:00"


# --------------------------------------------------------------------------
# tasks: rows
# --------------------------------------------------------------------------


async def test_list_tasks_orders_by_urgency(session, school_class):
    cid = school_class.id
    undated = await tasks.add_task(session, cid, 42, "без даты")
    wednesday = await tasks.add_task(session, cid, 42, "среда", due_date=date(2026, 9, 16))
    monday_evening = await tasks.add_task(
        session, cid, 42, "понедельник вечер", due_date=NEXT_MONDAY, due_time=time(18, 0)
    )
    monday = await tasks.add_task(session, cid, 42, "понедельник", due_date=NEXT_MONDAY)
    urgent = await tasks.add_task(session, cid, 42, "без даты, срочно", priority=2)
    finished = await tasks.add_task(session, cid, 42, "сделано", due_date=SATURDAY)
    await tasks.set_done(session, finished, True)
    await tasks.add_task(session, cid, 43, "чужая")

    ids = [task.id for task in await tasks.list_tasks(session, cid, 42)]
    assert ids == [monday_evening.id, monday.id, wednesday.id, urgent.id, undated.id]

    with_done = await tasks.list_tasks(session, cid, 42, include_done=True)
    assert [task.id for task in with_done] == ids + [finished.id]
    assert len(await tasks.list_tasks(session, cid, 42, limit=2)) == 2


async def test_add_task_normalises_its_input(session, school_class):
    task = await tasks.add_task(
        session,
        school_class.id,
        42,
        "  Купить   тетрадь  ",
        priority=7,
        subject_name="Ф" * 200,
        notes="",
    )
    assert task.id is not None
    assert task.title == "Купить тетрадь"
    assert task.priority == 2
    assert len(task.subject_name) == 120
    assert task.notes is None
    assert task.created_at is not None
    assert task.done is False

    low = await tasks.add_task(session, school_class.id, 42, "тихо", priority=-3)
    assert low.priority == 0

    with pytest.raises(ValueError):
        await tasks.add_task(session, school_class.id, 42, "   ")


async def test_get_task_is_scoped_to_its_owner(session, school_class):
    other = await _class_in(session, "Europe/Moscow")
    task = await tasks.add_task(session, school_class.id, 42, "моя")

    assert (await tasks.get_task(session, task.id, school_class.id, 42)) is task
    assert await tasks.get_task(session, task.id, school_class.id, 43) is None
    assert await tasks.get_task(session, task.id, other.id, 42) is None
    assert await tasks.get_task(session, task.id + 1000, school_class.id, 42) is None


async def test_set_done_and_delete(session, school_class):
    task = await tasks.add_task(session, school_class.id, 42, "моя")

    await tasks.set_done(session, task, True)
    assert task.done and task.done_at is not None
    await tasks.set_done(session, task, False)
    assert not task.done and task.done_at is None

    await tasks.delete_task(session, task)
    assert await tasks.get_task(session, task.id, school_class.id, 42) is None


async def test_homework_ticks_toggle_per_person(session, school_class):
    homework = await _homework(session, school_class, MONDAY)

    assert await tasks.homework_ticks(session, 42, []) == set()
    assert await tasks.toggle_homework_done(session, homework, 42) is True
    assert await tasks.homework_ticks(session, 42, [homework.id, 999]) == {homework.id}
    assert await tasks.homework_ticks(session, 43, [homework.id]) == set()
    assert await tasks.toggle_homework_done(session, homework, 42) is False
    assert await tasks.homework_ticks(session, 42, [homework.id]) == set()


async def test_toggle_survives_a_racing_duplicate(session, school_class):
    """The app and the bot ticking the same homework in the same instant."""
    homework = await _homework(session, school_class, MONDAY)
    real_commit = session.commit

    async def racing_commit() -> None:
        async with SessionLocal() as other:
            other.add(HomeworkDone(homework_id=homework.id, telegram_id=42))
            await other.commit()
        session.commit = real_commit
        await real_commit()

    session.commit = racing_commit
    assert await tasks.toggle_homework_done(session, homework, 42) is True
    # The row the caller is holding still renders after the rollback.
    assert homework.subject_name == "Алгебра"
    assert await tasks.homework_ticks(session, 42, [homework.id]) == {homework.id}


# --------------------------------------------------------------------------
# reminders
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("7:30", time(7, 30)),
        ("07.30", time(7, 30)),
        ("7", time(7, 0)),
        ("1930", time(19, 30)),
        ("730", time(7, 30)),
        ("7-30", time(7, 30)),
        (" 20 15 ", time(20, 15)),
        ("24:00", None),
        ("7:60", None),
        ("abc", None),
        ("", None),
        ("7:3", None),
    ],
)
def test_parse_clock(raw, expected):
    assert reminders.parse_clock(raw) == expected


async def test_settings_for_creates_the_row_once(session, school_class):
    assert await reminders.settings_for(session, school_class.id, 42, create=False) is None

    settings = await reminders.settings_for(session, school_class.id, 42)
    assert settings is not None
    assert settings.notify_changes is True
    assert settings.notify_homework is False
    assert settings.morning_at is None and settings.evening_at is None

    again = await reminders.settings_for(session, school_class.id, 42)
    assert again.id == settings.id
    assert (await reminders.settings_for(session, school_class.id, 43)).id != settings.id


async def test_due_digests_uses_each_class_zone(session, school_class):
    vladivostok = await _class_in(session, "Asia/Vladivostok")
    far_east = await reminders.settings_for(session, vladivostok.id, 42)
    far_east.morning_at = time(7, 30)
    moscow = await reminders.settings_for(session, school_class.id, 42)
    moscow.morning_at = time(7, 30)
    await session.commit()

    due = await reminders.due_digests(session, VLADIVOSTOK_0730)
    assert [(s.id, k.id, kind) for s, k, kind in due] == [(far_east.id, vladivostok.id, "morning")]

    # One minute earlier it is 07:29 in Vladivostok and nothing is due anywhere.
    assert await reminders.due_digests(session, VLADIVOSTOK_0730 - timedelta(minutes=1)) == []

    # Seven hours later Moscow has reached 07:30 too, and Vladivostok is still unsent.
    due = await reminders.due_digests(session, MOSCOW_0730)
    assert {(s.id, kind) for s, _, kind in due} == {
        (far_east.id, "morning"),
        (moscow.id, "morning"),
    }


async def test_due_digests_counts_today_in_the_class_zone(session, school_class):
    vladivostok = await _class_in(session, "Asia/Vladivostok")
    settings = await reminders.settings_for(session, vladivostok.id, 42)
    settings.morning_at = time(7, 30)
    await session.commit()

    assert await reminders.claim(session, settings, "morning", MONDAY) is True
    assert settings.last_morning_sent == MONDAY
    assert await reminders.due_digests(session, VLADIVOSTOK_0730) == []

    # 23:59 Monday in Vladivostok is 13:59 UTC - still Monday there, so still sent.
    assert await reminders.due_digests(session, datetime(2026, 9, 7, 13, 59, tzinfo=UTC)) == []
    # Midnight there starts Tuesday, but 07:30 has not come round yet.
    assert await reminders.due_digests(session, datetime(2026, 9, 7, 14, 0, tzinfo=UTC)) == []
    # Tuesday 07:30 there: a new day, due again.
    assert len(await reminders.due_digests(session, VLADIVOSTOK_0730 + timedelta(days=1))) == 1

    # Sent yesterday means due today.
    await reminders.claim(session, settings, "morning", MONDAY - timedelta(days=1))
    assert len(await reminders.due_digests(session, VLADIVOSTOK_0730)) == 1


async def test_due_digests_evening_and_naive_utc(session, school_class):
    settings = await reminders.settings_for(session, school_class.id, 42)
    settings.evening_at = time(20, 0)
    silent = await reminders.settings_for(session, school_class.id, 43)
    assert silent.morning_at is None and silent.evening_at is None
    await session.commit()

    naive = MOSCOW_2000.replace(tzinfo=None)
    due = await reminders.due_digests(session, naive)
    assert [(s.id, kind) for s, _, kind in due] == [(settings.id, "evening")]
    assert await reminders.due_digests(session, naive - timedelta(minutes=1)) == []

    settings.morning_at = time(7, 30)
    await session.commit()
    due = await reminders.due_digests(session, MOSCOW_2000)
    assert [kind for _, _, kind in due] == ["morning", "evening"]


async def test_claim_rejects_an_unknown_kind(session, school_class):
    settings = await reminders.settings_for(session, school_class.id, 42)
    with pytest.raises(ValueError):
        await reminders.claim(session, settings, "noon", MONDAY)
    assert await reminders.claim(session, settings, "evening", MONDAY) is True
    assert settings.last_evening_sent == MONDAY and settings.last_morning_sent is None


async def test_only_one_of_two_overlapping_ticks_may_send(session, school_class):
    """The claim is what stops a digest going out twice.

    `due_digests` selects and `send_due` marks as it reaches each row, so a
    tick that starts while another is still working used to select the same
    rows and send them again. Marking before sending protects against a tick
    that dies halfway, which is a different failure — and this deployment has
    both, because `reminders.yml` timing `curl` out does not stop the
    serverless invocation it started.
    """
    settings = await reminders.settings_for(session, school_class.id, 42)

    assert await reminders.claim(session, settings, "morning", MONDAY) is True
    # The second tick, holding the same row it selected a moment earlier.
    assert await reminders.claim(session, settings, "morning", MONDAY) is False
    # Tomorrow is a different claim and is still there to be taken.
    assert await reminders.claim(
        session, settings, "morning", MONDAY + timedelta(days=1)
    ) is True


async def test_due_task_reminders_compare_class_wall_time(session, school_class):
    vladivostok = await _class_in(session, "Asia/Vladivostok")
    at_0730 = datetime(2026, 9, 7, 7, 30)
    far_east = await tasks.add_task(session, vladivostok.id, 42, "восток", remind_at=at_0730)
    moscow = await tasks.add_task(session, school_class.id, 42, "запад", remind_at=at_0730)
    finished = await tasks.add_task(session, vladivostok.id, 42, "сделано", remind_at=at_0730)
    await tasks.set_done(session, finished, True)
    await tasks.add_task(session, vladivostok.id, 42, "без напоминания")

    due = await reminders.due_task_reminders(session, VLADIVOSTOK_0730)
    assert [(t.id, k.id) for t, k in due] == [(far_east.id, vladivostok.id)]

    due = await reminders.due_task_reminders(session, MOSCOW_0730)
    assert {t.id for t, _ in due} == {far_east.id, moscow.id}


def test_render_morning_prefixes_the_day():
    day = ResolvedDay(date=MONDAY, weekday=1, kind=DayKind.NORMAL)
    text = reminders.render_morning(day, MONDAY)
    assert text.startswith("☀️ Доброе утро!\n\n<b>Сегодня, 7 сентября (понедельник)</b>")
    assert "Уроков нет." in text


def test_render_evening_marks_what_is_done():
    day = ResolvedDay(
        date=date(2026, 9, 8),
        weekday=2,
        kind=DayKind.NORMAL,
        homework=[
            ResolvedHomework("Алгебра", "№ 12–15"),
            ResolvedHomework("Физика", "§ 3 <b>"),
        ],
    )
    text = reminders.render_evening(day, MONDAY, {"Алгебра"})
    assert text.startswith("🌙 Добрый вечер! Домашнее задание на завтра, 8 сентября:")
    assert "✅ <b>Алгебра</b>: № 12–15" in text
    assert "📝 <b>Физика</b>: § 3 &lt;b&gt;" in text
    assert "Всё сделано" not in text

    assert "Всё сделано 👍" in reminders.render_evening(day, MONDAY, {"Алгебра", "Физика"})

    later = ResolvedDay(date=NEXT_MONDAY, weekday=1, kind=DayKind.NORMAL)
    assert reminders.render_evening(later, MONDAY, set()) == (
        "🌙 Добрый вечер! Домашних заданий на понедельник, 14 сентября нет."
    )
    far = ResolvedDay(date=date(2026, 10, 1), weekday=4, kind=DayKind.NORMAL)
    assert "на 1 октября нет" in reminders.render_evening(far, MONDAY, set())
    assert "не назначен" in reminders.render_evening(None, MONDAY, set())


def test_render_evening_truncates_a_long_list():
    day = ResolvedDay(
        date=date(2026, 9, 8),
        weekday=2,
        kind=DayKind.NORMAL,
        homework=[ResolvedHomework(f"Предмет {n}", "текст") for n in range(40)],
    )
    text = reminders.render_evening(day, MONDAY, set())
    assert text.count("📝") == reminders.MAX_DIGEST_LINES
    assert "… и ещё 10" in text
    assert len(text) < 4096


async def test_send_due_sends_marks_and_never_repeats(session, school_class):
    first = await reminders.settings_for(session, school_class.id, 42)
    first.morning_at, first.evening_at = time(7, 30), time(20, 0)
    second = await reminders.settings_for(session, school_class.id, 43)
    second.morning_at = time(7, 30)
    await session.commit()
    homework = await _homework(session, school_class, NEXT_MONDAY)
    await tasks.toggle_homework_done(session, homework, 42)

    bot = FakeBot()
    assert await reminders.send_due(session, bot, MOSCOW_0730) == {
        "morning": 2,
        "evening": 0,
        "tasks": 0,
        "failed": 0,
    }
    assert bot.recipients == [42, 43]
    for _, text in bot.sent:
        assert text.startswith("☀️ Доброе утро!")
        assert "Алгебра" in text
    assert first.last_morning_sent == MONDAY and second.last_morning_sent == MONDAY

    # The same minute again: everything is already accounted for.
    assert (await reminders.send_due(session, FakeBot(), MOSCOW_0730))["morning"] == 0

    evening_bot = FakeBot()
    counts = await reminders.send_due(session, evening_bot, MOSCOW_2000)
    assert counts["evening"] == 1 and counts["morning"] == 0
    (recipient, text), = evening_bot.sent
    assert recipient == 42
    assert "на понедельник, 14 сентября" in text
    assert "✅ <b>Алгебра</b>" in text
    assert first.last_evening_sent == MONDAY


async def test_send_due_is_silent_on_a_day_without_lessons(session, school_class):
    settings = await reminders.settings_for(session, school_class.id, 42)
    settings.morning_at = time(7, 30)
    await session.commit()

    bot = FakeBot()
    saturday_0730 = datetime(2026, 9, 12, 4, 30, tzinfo=UTC)
    counts = await reminders.send_due(session, bot, saturday_0730)
    assert counts == {"morning": 0, "evening": 0, "tasks": 0, "failed": 0}
    assert bot.sent == []
    # Still marked, so the tick does not resolve the same empty day all morning.
    assert settings.last_morning_sent == SATURDAY


async def test_send_due_switches_off_a_subscriber_who_blocked_the_bot(session, school_class):
    settings = await reminders.settings_for(session, school_class.id, 42)
    settings.morning_at, settings.evening_at = time(7, 30), time(20, 0)
    await session.commit()

    counts = await reminders.send_due(session, FakeBot(blocked={42}), MOSCOW_0730)
    assert counts["failed"] == 1 and counts["morning"] == 0
    assert settings.morning_at is None and settings.evening_at is None
    assert await reminders.due_digests(session, MOSCOW_2000) == []


async def test_send_due_counts_a_network_failure_without_retrying_it(session, school_class):
    settings = await reminders.settings_for(session, school_class.id, 42)
    settings.morning_at = time(7, 30)
    await session.commit()

    counts = await reminders.send_due(session, FakeBot(broken={42}), MOSCOW_0730)
    assert counts["failed"] == 1
    assert settings.morning_at == time(7, 30)
    # Marked before the send: a flaky network never turns into a message storm.
    assert settings.last_morning_sent == MONDAY
    assert (await reminders.send_due(session, FakeBot(), MOSCOW_0730))["failed"] == 0


async def test_send_due_sends_each_task_reminder_once(session, school_class):
    task = await tasks.add_task(
        session,
        school_class.id,
        42,
        "Взять форму",
        due_date=MONDAY,
        due_time=time(9, 0),
        notes="в спортзал",
        remind_at=datetime(2026, 9, 7, 7, 0),
    )
    bot = FakeBot()
    assert (await reminders.send_due(session, bot, MOSCOW_0730))["tasks"] == 1
    (recipient, text), = bot.sent
    assert recipient == 42
    assert text.startswith("⏰ Напоминание\n\n<b>Взять форму</b>")
    assert "Срок: сегодня, 7 сентября (понедельник), 09:00" in text
    assert "<i>в спортзал</i>" in text
    assert task.remind_at is None

    assert (await reminders.send_due(session, FakeBot(), MOSCOW_0730))["tasks"] == 0


# --------------------------------------------------------------------------
# notify
# --------------------------------------------------------------------------


async def test_notify_subscribers_respects_flags_and_the_author(session, school_class):
    for telegram_id, changes, homework in [(1, True, False), (2, True, True), (3, False, True)]:
        settings = await reminders.settings_for(session, school_class.id, telegram_id)
        settings.notify_changes, settings.notify_homework = changes, homework
    author = await reminders.settings_for(session, school_class.id, 4)
    author.notify_changes = author.notify_homework = True
    other = await _class_in(session, "Europe/Moscow")
    stranger = await reminders.settings_for(session, other.id, 5)
    stranger.notify_changes = stranger.notify_homework = True
    await session.commit()

    bot = FakeBot()
    sent = await notify.notify_subscribers(
        session, bot, school_class, "🔄 Замена", kind="changes", exclude=4
    )
    assert sent == 2
    assert bot.recipients == [1, 2]
    assert all(text == "🔄 Замена" for _, text in bot.sent)

    bot = FakeBot()
    sent = await notify.notify_subscribers(
        session, bot, school_class, "📝 ДЗ", kind="homework", exclude=None
    )
    assert sent == 3
    assert bot.recipients == [2, 3, 4]

    assert (
        await notify.notify_subscribers(
            session, None, school_class, "ничего", kind="changes", exclude=None
        )
        == 0
    )
    with pytest.raises(ValueError):
        await notify.notify_subscribers(
            session, FakeBot(), school_class, "x", kind="weather", exclude=None
        )


async def test_notify_subscribers_survives_bad_recipients(session, school_class):
    for telegram_id in (1, 2, 3):
        settings = await reminders.settings_for(session, school_class.id, telegram_id)
        settings.morning_at = time(7, 30)
    await session.commit()
    blocked = await reminders.settings_for(session, school_class.id, 1)
    flaky = await reminders.settings_for(session, school_class.id, 2)

    bot = FakeBot(blocked={1}, broken={2})
    sent = await notify.notify_subscribers(
        session, bot, school_class, "🔄 Замена", kind="changes", exclude=None
    )
    assert sent == 1
    assert bot.recipients == [3]

    # Blocked the bot: switched off everywhere, and it stuck.
    assert not blocked.notify_changes and not blocked.notify_homework
    assert blocked.morning_at is None
    async with SessionLocal() as other:
        row = await other.get(type(blocked), blocked.id)
        assert row.notify_changes is False
    # A network blip changes nothing for that person.
    assert flaky.notify_changes and flaky.morning_at == time(7, 30)


# --------------------------------------------------------------------------
# calendar
# --------------------------------------------------------------------------


async def test_calendar_token_is_minted_once_and_rotated_on_request(session, school_class):
    assert school_class.calendar_token is None

    token = await calendar.ensure_calendar_token(session, school_class)
    assert len(token) >= 32
    assert await calendar.ensure_calendar_token(session, school_class) == token

    rotated = await calendar.rotate_calendar_token(session, school_class)
    assert rotated != token
    assert school_class.calendar_token == rotated
    async with SessionLocal() as other:
        assert (await other.get(SchoolClass, school_class.id)).calendar_token == rotated


async def test_render_ics_writes_lessons_events_homework_and_tasks(session, school_class):
    cid = school_class.id
    session.add_all(
        [
            LessonOverride(
                class_id=cid,
                date=MONDAY,
                index=2,
                action=OverrideAction.REPLACE,
                subject_name="Химия",
                room="118",
                note="Вместо физики",
            ),
            LessonOverride(class_id=cid, date=MONDAY, index=3, action=OverrideAction.CANCEL),
            DayEvent(
                class_id=cid,
                date=MONDAY,
                starts_at=time(12, 30),
                ends_at=time(13, 0),
                title="Столовая",
                kind=EventKind.CANTEEN,
                location="1 этаж",
            ),
        ]
    )
    await session.commit()
    homework = await _homework(session, school_class, MONDAY)
    event = await session.scalar(select(DayEvent).where(DayEvent.class_id == cid))
    task = await tasks.add_task(
        session,
        cid,
        42,
        "Купить тетрадь",
        due_date=date(2026, 9, 15),
        due_time=time(18, 0),
        priority=2,
        subject_name="Физика",
        notes="в клетку",
    )
    done = await tasks.add_task(session, cid, 42, "Сделано", due_date=MONDAY)
    await tasks.set_done(session, done, True)

    days = await ScheduleResolver(session, school_class).resolve_range(MONDAY, 1)
    ics = calendar.render_ics(
        school_class,
        days,
        generated_at=datetime(2026, 9, 6, 12, 0, tzinfo=UTC),
        tasks=[task, done],
    )

    assert ics.endswith("\r\n")
    assert "\n" not in ics.replace("\r\n", "")
    lines = ics.split("\r\n")
    assert lines[0] == "BEGIN:VCALENDAR"
    assert lines[-2] == "END:VCALENDAR" and lines[-1] == ""
    assert "X-WR-CALNAME:9А" in lines
    assert "X-WR-TIMEZONE:Europe/Moscow" in lines
    assert "DTSTAMP:20260906T120000Z" in lines

    assert f"UID:lesson-{cid}-2026-09-07-1@lessons" in lines
    assert "DTSTART;TZID=Europe/Moscow:20260907T083000" in lines
    assert "DTEND;TZID=Europe/Moscow:20260907T091500" in lines
    assert "LOCATION:каб. 214" in lines
    assert "SUMMARY:Химия" in lines
    assert "LOCATION:каб. 118" in lines
    assert "DESCRIPTION:Замена\\nВместо физики" in lines
    # Cancelled lessons are not alarms.
    assert f"UID:lesson-{cid}-2026-09-07-3@lessons" not in lines
    assert "SUMMARY:История" not in lines

    assert f"UID:event-{cid}-id{event.id}@lessons" in lines
    assert "SUMMARY:Столовая" in lines
    assert "LOCATION:1 этаж" in lines
    assert "DTSTART;TZID=Europe/Moscow:20260907T123000" in lines

    assert f"UID:homework-{cid}-id{homework.id}@lessons" in lines
    assert "DUE;VALUE=DATE:20260907" in lines
    assert "SUMMARY:Алгебра: № 12–15" in lines

    assert f"UID:task-{task.id}@lessons" in lines
    assert "SUMMARY:Купить тетрадь" in lines
    assert "DESCRIPTION:в клетку" in lines
    assert "CATEGORIES:Физика" in lines
    assert "DUE;TZID=Europe/Moscow:20260915T180000" in lines
    assert "PRIORITY:1" in lines
    assert "STATUS:NEEDS-ACTION" in lines
    assert "STATUS:COMPLETED" in lines
    assert any(line.startswith("COMPLETED:") and line.endswith("Z") for line in lines)

    assert ics.count("BEGIN:VEVENT") == ics.count("END:VEVENT") == 3
    assert ics.count("BEGIN:VTODO") == ics.count("END:VTODO") == 3


def test_render_ics_escapes_and_folds():
    subject = "Русский язык и литература; углублённый курс, часть 1\\2 — " * 2
    day = ResolvedDay(
        date=MONDAY,
        weekday=1,
        kind=DayKind.NORMAL,
        lessons=[
            ResolvedLesson(
                index=1,
                subject=subject,
                starts_at=time(8, 30),
                ends_at=time(9, 15),
                teacher="Иванова И.И.",
                note="строка 1\r\nстрока 2",
            )
        ],
    )
    klass = SchoolClass(id=5, name="9А, «Б»", join_code="X", timezone="Asia/Vladivostok")

    ics = calendar.render_ics(klass, [day], generated_at=datetime(2026, 9, 6, 12, 0))
    lines = ics.split("\r\n")

    assert all(len(line.encode("utf-8")) <= 75 for line in lines)
    assert any(line.startswith(" ") for line in lines)
    # A naive generated_at is taken as UTC.
    assert "DTSTAMP:20260906T120000Z" in lines

    unfolded = ics.replace("\r\n ", "").split("\r\n")
    assert "X-WR-CALNAME:9А\\, «Б»" in unfolded
    assert "DTSTART;TZID=Asia/Vladivostok:20260907T083000" in unfolded
    assert "SUMMARY:" + calendar.escape_text(subject) in unfolded
    assert "DESCRIPTION:Иванова И.И.\\nстрока 1\\nстрока 2" in unfolded
    assert "UID:lesson-5-2026-09-07-1@lessons" in unfolded


def test_escape_text():
    assert calendar.escape_text("a,b;c\\d\ne\r\nf\rg") == "a\\,b\\;c\\\\d\\ne\\nf\\ng"


def test_fold_counts_octets_not_characters():
    line = "SUMMARY:" + "ж" * 100  # two octets each
    parts = calendar.fold(line)
    assert len(parts) > 1
    assert all(len(part.encode("utf-8")) <= 75 for part in parts)
    assert all(part.startswith(" ") for part in parts[1:])
    assert parts[0] + "".join(part[1:] for part in parts[1:]) == line
    assert calendar.fold("BEGIN:VCALENDAR") == ["BEGIN:VCALENDAR"]


# --------------------------------------------------------------------------
# stats
# --------------------------------------------------------------------------


async def test_class_stats_counts_what_an_admin_asks_about(session, school_class):
    cid = school_class.id
    today = datetime.now(school_class.tz).date()
    yesterday, tomorrow = today - timedelta(days=1), today + timedelta(days=1)
    session.add_all(
        [
            TimetableEntry(
                class_id=cid, weekday=2, index=1, subject_name="Алгебра", parity=WeekParity.ODD
            ),
            TimetableEntry(
                class_id=cid, weekday=2, index=1, subject_name="Химия", parity=WeekParity.EVEN
            ),
            Homework(class_id=cid, due_date=tomorrow, subject_name="Алгебра", text="№ 1"),
            Homework(class_id=cid, due_date=yesterday, subject_name="Алгебра", text="№ 0"),
            LessonOverride(
                class_id=cid, date=tomorrow, index=1, action=OverrideAction.CANCEL
            ),
            DayOverride(class_id=cid, date=yesterday, kind=DayKind.HOLIDAY),
            DayEvent(
                class_id=cid,
                date=today,
                starts_at=time(10, 0),
                ends_at=time(11, 0),
                title="Линейка",
            ),
            BotUser(telegram_id=42, class_id=cid, role=Role.EDITOR),
            BotUser(telegram_id=43, class_id=cid, role=Role.VIEWER),
            BotUser(telegram_id=44, class_id=cid, role=Role.VIEWER),
        ]
    )
    await session.commit()
    await _device(session, school_class)
    await _device(session, school_class, revoked=True)

    result = await stats.class_stats(session, school_class)
    assert result["today"] == today
    assert result["lessons_per_week"] == 4.0
    assert result["subjects"] == [
        ("Алгебра", 1.5),
        ("История", 1.0),
        ("Физика", 1.0),
        ("Химия", 0.5),
    ]
    assert result["subjects_count"] == 4
    assert result["homework_open"] == 1
    assert result["homework_total"] == 2
    assert result["members_by_role"] == {Role.EDITOR: 1, Role.VIEWER: 2}
    assert result["devices_active"] == 1
    assert result["overrides_upcoming"] == 1
    assert result["events_upcoming"] == 1

    text = stats.render_stats(result, school_class)
    assert "📊 <b>Статистика класса 9А</b>" in text
    assert "Уроков в неделю: <b>4</b>, предметов: <b>4</b>" in text
    assert "<b>1</b> актуальных, всего <b>2</b>" in text
    assert "Редактор: 1 · Наблюдатель: 2" in text
    assert "• Алгебра — 1,5" in text
    assert "• Химия — 0,5" in text


def test_render_stats_escapes_and_truncates():
    klass = SchoolClass(id=1, name="9<А>", join_code="X")
    result = {
        "today": MONDAY,
        "lessons_per_week": 18.5,
        "subjects": [(f"Предмет <{n}>", 1.0) for n in range(20)],
        "subjects_count": 20,
        "homework_open": 0,
        "homework_total": 0,
        "members_by_role": {},
        "devices_active": 0,
        "overrides_upcoming": 0,
        "events_upcoming": 0,
    }
    text = stats.render_stats(result, klass)
    assert "Статистика класса 9&lt;А&gt;" in text
    assert "<b>18,5</b>" in text
    assert "пока никого" in text
    assert "• Предмет &lt;0&gt; — 1" in text
    assert text.count("• ") == stats.MAX_SUBJECT_ROWS
    assert "… и ещё 5" in text

    empty = dict(result, subjects=[], subjects_count=0, lessons_per_week=0.0)
    assert "Расписание ещё не заполнено" in stats.render_stats(empty, klass)


# --------------------------------------------------------------------------
# timetable_io
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    "suffix, parity",
    [
        (" [чис]", WeekParity.ODD),
        (" [знам]", WeekParity.EVEN),
        (" (чис)", WeekParity.ODD),
        (" (знам)", WeekParity.EVEN),
        (" [1]", WeekParity.ODD),
        (" [2]", WeekParity.EVEN),
        ("(1)", WeekParity.ODD),
        (" ( 2 )", WeekParity.EVEN),
        (" числ", WeekParity.ODD),
        (" знам", WeekParity.EVEN),
        (" чис", WeekParity.ODD),
        (" ЧИСЛ", WeekParity.ODD),
        (" Знам", WeekParity.EVEN),
        (" числитель", WeekParity.ODD),
        (" знаменатель", WeekParity.EVEN),
        (" (Числитель)", WeekParity.ODD),
        (" [ЗНАМЕНАТЕЛЬ]", WeekParity.EVEN),
        ("", WeekParity.ANY),
    ],
)
def test_parse_lesson_line_reads_every_parity_spelling(suffix, parity):
    line = f"2. Физика, 305, Иванова И.И.{suffix}"
    assert timetable_io.parse_lesson_line(line) == (2, "Физика", "305", "Иванова И.И.", parity)


@pytest.mark.parametrize("line", ["", "   ", "Физика", "0. Физика", "1.", "12) .", "1. ,305"])
def test_parse_lesson_line_rejects_what_is_not_a_lesson(line):
    assert timetable_io.parse_lesson_line(line) is None


def test_parse_lesson_line_variants():
    assert timetable_io.parse_lesson_line("3 История") == (3, "История", None, None, WeekParity.ANY)
    assert timetable_io.parse_lesson_line("4) Труд, , Петров") == (
        4, "Труд", None, "Петров", WeekParity.ANY,
    )  # fmt: skip
    # A bare suffix must be its own word: «Механизм» ends in «изм», not «знам».
    assert timetable_io.parse_lesson_line("5. Механизм")[1:] == (
        "Механизм", None, None, WeekParity.ANY,
    )  # fmt: skip
    assert timetable_io.parse_lesson_line("6. Физика,305 [чис]") == (
        6, "Физика", "305", None, WeekParity.ODD,
    )  # fmt: skip
    parsed = timetable_io.parse_lesson_line("7. " + "П" * 200 + ", " + "1" * 40 + ", " + "У" * 200)
    assert parsed is not None
    assert (len(parsed[1]), len(parsed[2]), len(parsed[3])) == (120, 32, 120)


def test_split_parity():
    assert timetable_io.split_parity("Физика, 305 [чис]") == ("Физика, 305", WeekParity.ODD)
    assert timetable_io.split_parity("Физика, 305") == ("Физика, 305", WeekParity.ANY)


def test_parse_timetable_block_reads_every_header_style():
    raw = (
        "== Понедельник ==\n"
        "1. Алгебра, 214\n"
        "2. Физика [чис]\n"
        "2. Химия [знам]\n"
        "\n"
        "Вторник:\n"
        "-\n"
        "\n"
        "Пт:\n"
        "1. История\n"
        "\n"
        "== Звонки ==\n"
        "1. 08:30-09:15\n"
    )
    days, rejected = timetable_io.parse_timetable_block(raw)
    assert rejected == []
    assert days == {
        1: [
            (1, "Алгебра", "214", None, WeekParity.ANY),
            (2, "Физика", None, None, WeekParity.ODD),
            (2, "Химия", None, None, WeekParity.EVEN),
        ],
        2: [],
        5: [(1, "История", None, None, WeekParity.ANY)],
    }


def test_parse_timetable_block_rejects_conflicts_and_strays():
    raw = (
        "1. До заголовка\n"
        "*Среда*\n"
        "1. Алгебра\n"
        "1. Химия\n"
        "2. Физика [чис]\n"
        "2. Труд\n"
        "3. Труд\n"
        "3. Пение [знам]\n"
        "мусор\n"
        "Физика\n"
    )
    days, rejected = timetable_io.parse_timetable_block(raw)
    assert days == {
        3: [
            (1, "Алгебра", None, None, WeekParity.ANY),
            (2, "Физика", None, None, WeekParity.ODD),
            (3, "Труд", None, None, WeekParity.ANY),
        ]
    }
    assert rejected == [
        "1. До заголовка",
        "1. Химия",
        "2. Труд",
        "3. Пение [знам]",
        "мусор",
        "Физика",
    ]


def test_parse_timetable_block_of_nothing():
    assert timetable_io.parse_timetable_block("") == ({}, [])
    assert timetable_io.parse_timetable_block("== Звонки ==\n1. 08:30-09:15") == ({}, [])


def test_parse_bells_block():
    raw = (
        "== Понедельник ==\n"
        "1. Алгебра\n"
        "== Звонки ==\n"
        "1. 08:30-09:15\n"
        "2. 09.25 – 10.10\n"
        "3. 10:00-09:00\n"
        "2. 11:00-11:45\n"
        "abc\n"
    )
    rows, rejected = timetable_io.parse_bells_block(raw)
    assert rows == [(1, time(8, 30), time(9, 15)), (2, time(9, 25), time(10, 10))]
    assert rejected == ["3. 10:00-09:00", "2. 11:00-11:45", "abc"]
    # No header, no bells - a week paste never touches them by accident.
    assert timetable_io.parse_bells_block("1. 08:30-09:15") == ([], [])


async def test_export_roundtrips_through_parse(session, school_class):
    cid = school_class.id
    session.add_all(
        [
            TimetableEntry(class_id=cid, weekday=3, index=1, subject_name="Труд", teacher="Петров"),
            TimetableEntry(
                class_id=cid,
                weekday=3,
                index=2,
                subject_name="Физика",
                room="305",
                teacher="Иванова",
                parity=WeekParity.ODD,
            ),
            TimetableEntry(
                class_id=cid, weekday=3, index=2, subject_name="Химия", parity=WeekParity.EVEN
            ),
        ]
    )
    await session.commit()
    entries = list(
        await session.scalars(select(TimetableEntry).where(TimetableEntry.class_id == cid))
    )
    bells = list(
        await session.scalars(
            select(BellPeriod).where(BellPeriod.schedule_id == school_class.bell_schedule_id)
        )
    )

    text = timetable_io.export_timetable(entries, bells)
    assert text.startswith(
        "== Понедельник ==\n"
        "1. Алгебра, 214\n"
        "2. Физика, 305\n"
        "3. История\n"
        "\n"
        "== Среда ==\n"
        "1. Труд, , Петров\n"
        "2. Физика, 305, Иванова [чис]\n"
        "2. Химия [знам]\n"
        "\n"
        "== Звонки ==\n"
        "1. 08:30-09:15\n"
    )
    assert text.endswith("7. 14:20-15:05")

    days, rejected = timetable_io.parse_timetable_block(text)
    assert rejected == []
    expected: dict[int, list] = {}
    def _slot(entry: TimetableEntry) -> tuple[int, int, bool]:
        return entry.weekday, entry.index, entry.parity is not WeekParity.ODD

    for entry in sorted(entries, key=_slot):
        expected.setdefault(entry.weekday, []).append(
            (entry.index, entry.subject_name, entry.room, entry.teacher, entry.parity)
        )
    assert days == expected

    rows, rejected = timetable_io.parse_bells_block(text)
    assert rejected == []
    assert rows == [(p.index, p.starts_at, p.ends_at) for p in sorted(bells, key=lambda p: p.index)]


def test_export_of_nothing_is_empty():
    assert timetable_io.export_timetable([], []) == ""


async def test_a_calendar_uid_survives_its_neighbour_being_deleted(session, school_class):
    """UIDs used to be the item's position within its day, which is not an
    identity.

    Delete the first of three заданий and the other two slid up into its UID
    and the second's. A subscriber's client reads a UID as "which to-do is
    this", so two of them silently changed into different subjects — ticked-off
    ones included — and a third disappeared. Nothing in the feed said anything
    had happened.
    """
    algebra = await _homework(session, school_class, MONDAY, subject="Алгебра")
    biology = await _homework(session, school_class, MONDAY, subject="Биология")
    history = await _homework(session, school_class, MONDAY, subject="История")

    def uids_of(ics: str) -> dict[str, str]:
        """Subject -> the UID the feed gave it."""
        out, current = {}, None
        for line in ics.split("\r\n"):
            if line.startswith("UID:homework-"):
                current = line
            elif line.startswith("SUMMARY:") and current is not None:
                out[line[len("SUMMARY:"):].split(":")[0]] = current
                current = None
        return out

    async def render() -> dict[str, str]:
        days = await ScheduleResolver(session, school_class).resolve_range(MONDAY, 1)
        return uids_of(
            calendar.render_ics(
                school_class, days, generated_at=datetime(2026, 9, 6, 12, 0, tzinfo=UTC)
            )
        )

    before = await render()
    assert set(before) == {"Алгебра", "Биология", "История"}

    await session.delete(algebra)
    await session.commit()
    after = await render()

    assert set(after) == {"Биология", "История"}
    assert after["Биология"] == before["Биология"]
    assert after["История"] == before["История"]
    assert str(biology.id) in after["Биология"] and str(history.id) in after["История"]


def test_a_comma_inside_a_field_survives_the_round_trip():
    """The module docstring promises that a paste produced by the export
    survives the import unchanged. It did not.

    «Иностранный язык, второй» was written out plain and read back as the
    subject «Иностранный язык» in a room called «второй» — nothing rejected,
    nothing logged, the row simply became two different things. Teachers had it
    too: «Иванов И.И., к.п.н.» lost everything after the comma into a field
    that did not exist.
    """
    combinations = [
        ("Физика", None, None),
        ("Физика", "305", None),
        ("Физика", "305", "Петров П.П."),
        ("Физика", None, "Петров П.П."),
        ("Иностранный язык, второй", None, None),
        ("Иностранный язык, второй", "305", None),
        ("Иностранный язык, второй", "305", "Иванов И.И., к.п.н."),
        ("Физика", "каб. 3, левый", "Петров"),
        ("Физика", "305", "Иванов И.И., к.п.н."),
        ('Он сказал "да"', "12", None),
    ]
    for parity in (WeekParity.ANY, WeekParity.ODD, WeekParity.EVEN):
        for subject, room, teacher in combinations:
            entry = TimetableEntry(
                weekday=1, index=1, subject_name=subject, room=room,
                teacher=teacher, parity=parity,
            )
            line = timetable_io.format_lesson_line(entry)
            parsed = timetable_io.parse_lesson_line(line)
            assert parsed is not None, line
            assert parsed[1:] == (subject, room, teacher, parity), line


def test_an_ordinary_line_is_written_exactly_as_it_always_was():
    """Quoting is only for the fields that need it: every paste anybody has
    written so far has to keep meaning what it meant."""
    plain = TimetableEntry(
        weekday=1, index=2, subject_name="Физика", room="305",
        teacher="Иванова И.И.", parity=WeekParity.ANY,
    )
    assert timetable_io.format_lesson_line(plain) == "2. Физика, 305, Иванова И.И."


def test_the_last_field_keeps_its_commas_without_quoting():
    """A teacher is third and takes everything left, so «к.п.н.» needs no
    syntax — which is what keeps the format typeable by hand."""
    assert timetable_io.parse_lesson_line("1. Физика, 305, Иванов И.И., к.п.н.") == (
        1, "Физика", "305", "Иванов И.И., к.п.н.", WeekParity.ANY,
    )


# --------------------------------------------------------------------------
# The diary's clock
# --------------------------------------------------------------------------


class _FakeDiaryClient:
    """Enough of ``PetersburgClient`` for one call. ``token`` is read by
    ``DiaryService._remember_token`` on the way out of every call."""

    def __init__(self, items: list[dict[str, Any]]) -> None:
        self.items = items
        self.token = "upstream-token"

    async def periods(self, group_id: int) -> list[dict[str, Any]]:
        return self.items


async def test_the_current_period_is_read_off_the_diary_clock_not_the_server(
    session, school_class, monkeypatch
):
    """Which четверть is «текущая» decides what `GET /diary/.../subjects`
    answers when no period is named, and it answers with an **empty list** when
    none of them is current.

    Vercel runs in UTC and the diary is one city's, three hours ahead. Asked
    with the server's own clock, a pupil opening «Дневник» after nine in the
    evening on the first day of a quarter was still in yesterday - which is
    каникулы, between two periods - and got no subjects at all. The provider
    exports ``today()`` for exactly this; every other "today" in this project
    comes from the class's zone for the same reason.
    """
    from app.providers.petersburg import client as petersburg_client
    from app.services import diary as diary_service

    server_today = date.today()
    # It is already tomorrow where the diary is, and tomorrow opens a quarter.
    school_today = server_today + timedelta(days=1)

    class _SchoolClock:
        @staticmethod
        def now(tz=None):
            return datetime.combine(school_today, time(0, 30), tzinfo=tz)

    monkeypatch.setattr(petersburg_client, "datetime", _SchoolClock)

    row = DiarySession(
        token_hash=hash_token(new_token()),
        upstream_token=seal("upstream-token"),
        login="ivan@example.test",
    )
    session.add(row)
    await session.commit()

    service = diary_service.DiaryService(session, row)
    service.client = _FakeDiaryClient(
        [
            {
                "identity": {"id": 1},
                "name": "1 четверть",
                "date_from": (server_today - timedelta(days=60)).strftime("%d.%m.%Y"),
                "date_to": server_today.strftime("%d.%m.%Y"),
            },
            {
                "identity": {"id": 2},
                "name": "2 четверть",
                "date_from": school_today.strftime("%d.%m.%Y"),
                "date_to": (school_today + timedelta(days=60)).strftime("%d.%m.%Y"),
            },
        ]
    )

    found = await service.periods(group_id=1)
    current = [period.name for period in found if period.is_current]

    assert current == ["2 четверть"], (
        "the quarter that opened today where the school is has to be the current one"
    )


async def test_two_overlapping_ticks_do_not_send_one_task_reminder_twice(
    session, school_class
):
    """A task reminder is claimed by the database, the same as a digest is.

    It used to be claimed by writing ``remind_at = None`` onto the loaded row
    and committing — which answers the tick that dies halfway through, and not
    the tick that overlaps. `reminders.yml` gives `curl` five minutes and
    killing the request does not kill the serverless invocation it started, so
    this deployment has the overlapping one: `due_task_reminders` hands out up
    to two hundred rows and `send_due` clears each as it reaches it, so the
    window for the last of them is however long the rest took to send. A second
    tick selecting inside that window held the same rows with ``remind_at``
    still set, cleared an already-cleared column without complaint, and sent
    the reminder a second time.
    """
    task = await tasks.add_task(
        session,
        school_class.id,
        42,
        "Взять форму",
        remind_at=datetime(2026, 9, 7, 7, 0),
    )

    async with SessionLocal() as overlapping:
        # The second tick selected this row a moment ago and is still working
        # through the ones before it.
        held, _klass = (await reminders.due_task_reminders(overlapping, MOSCOW_0730))[0]
        assert held.remind_at is not None

        # The first tick reaches the same row, claims it and sends.
        bot = FakeBot()
        assert (await reminders.send_due(session, bot, MOSCOW_0730))["tasks"] == 1
        assert len(bot.sent) == 1

        # The second tick now reaches the row it is holding. What decides is
        # the database, not the value this tick still has in memory.
        assert await reminders.claim_task(overlapping, held) is False

    assert task.remind_at is None
