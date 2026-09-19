"""The read-mostly bot views: week, «что дальше», tasks, homework ticks,
reminders and the phone deep link.

Rendering is tested as pure functions at fixed clocks; the handlers are
called directly with the same stubs as ``test_bot_handlers``.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta
from types import SimpleNamespace

import pytest
from sqlalchemy import select
from test_bot_handlers import FakeCallback, FakeEditable, FakeMessage, FakeState

from app.bot.handlers.manage import cmd_link
from app.bot.handlers.reminders import (
    cmd_remind,
    reminder_clear_time,
    reminder_off,
    reminder_time_apply,
    reminder_time_prompt,
    reminder_toggle,
)
from app.bot.handlers.start import (
    cmd_help,
    cmd_start_link,
    cmd_today,
    cmd_tomorrow,
    on_contact,
)
from app.bot.handlers.tasks import (
    cmd_homework,
    cmd_task,
    cmd_tasks,
    homework_tick_keyboard,
    homework_toggle,
    homework_view,
    remind_at_for,
    task_add_text,
    task_delete,
    task_set_reminder,
    task_toggle_done,
    task_view,
)
from app.bot.handlers.week import cmd_next, cmd_week, distinct_from, menu_next, show_week
from app.bot.keyboards import main_menu, task_list_keyboard
from app.bot.render import (
    INVISIBLE,
    WEEK_TEXT_LIMIT,
    duration,
    plural,
    render_access_list,
    render_day,
    render_homework_digest,
    render_next,
    render_reminder_card,
    render_role_help,
    render_task_list,
    render_task_saved,
    render_week,
)
from app.bot.states import AddTask, SetReminderTime
from app.models import (
    BotUser,
    DayKind,
    DeviceToken,
    EventKind,
    Homework,
    HomeworkDone,
    PersonalTask,
    PhoneInvite,
    ReminderSettings,
    Role,
    TimetableEntry,
    WeekParity,
)
from app.schedule import ResolvedDay, ResolvedEvent, ResolvedHomework, ResolvedLesson
from app.security import hash_token, new_token
from app.services import reminders

# 2026-09-07 is a Monday in ISO week 37 - an odd week, «числитель».
MONDAY = date(2026, 9, 7)


def lesson(index: int, subject: str, start: str, end: str, **extra) -> ResolvedLesson:
    return ResolvedLesson(
        index=index,
        subject=subject,
        starts_at=time.fromisoformat(start),
        ends_at=time.fromisoformat(end),
        **extra,
    )


def four_lessons(**extra) -> list[ResolvedLesson]:
    return [
        lesson(1, "Алгебра", "08:30", "09:15", room="214"),
        lesson(2, "Физика", "09:25", "10:10", room="305"),
        lesson(3, "История", "10:25", "11:10"),
        lesson(4, "Химия", "11:25", "12:10", room="402"),
    ]


def day(on: date, lessons=None, **extra) -> ResolvedDay:
    return ResolvedDay(
        date=on, weekday=on.isoweekday(), kind=DayKind.NORMAL, lessons=lessons or [], **extra
    )


def at(clock: str, on: date = MONDAY) -> datetime:
    return datetime.combine(on, time.fromisoformat(clock))


def buttons(keyboard) -> list[str]:
    return [button.text for row in keyboard.inline_keyboard for button in row]


# --------------------------------------------------------------------------
# Plurals
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("count", "expected"),
    [
        (1, "1 урок"),
        (2, "2 урока"),
        (5, "5 уроков"),
        (11, "11 уроков"),
        (21, "21 урок"),
        (0, "0 уроков"),
    ],
)
def test_plural_forms(count, expected):
    assert plural(count, "урок", "урока", "уроков") == expected


@pytest.mark.parametrize(
    ("minutes", "expected"),
    [(0, "0 мин"), (7, "7 мин"), (60, "1 ч"), (72, "1 ч 12 мин"), (120, "2 ч")],
)
def test_duration(minutes, expected):
    assert duration(minutes) == expected


# --------------------------------------------------------------------------
# Week
# --------------------------------------------------------------------------


def week_days(**extra) -> list[ResolvedDay]:
    days = [day(MONDAY + timedelta(days=offset)) for offset in range(6)]
    days[0].lessons = four_lessons()
    return days


def test_week_has_a_block_per_day_and_no_parity_line_by_default():
    text = render_week(week_days(), MONDAY, parity_matters=False)
    assert "<b>Пн, 7 сентября · сегодня</b>" in text
    assert "<b>Сб, 12 сентября</b>" in text
    assert "1 · 08:30 Алгебра · 214" in text
    assert "3 · 10:25 История\n" in text
    assert "Уроков нет" in text
    assert "Неделя:" not in text


def test_week_names_the_parity_only_when_the_class_alternates():
    assert "Неделя: числитель" in render_week(week_days(), MONDAY, parity_matters=True)
    next_week = [day(MONDAY + timedelta(days=7 + offset)) for offset in range(6)]
    assert "Неделя: знаменатель" in render_week(next_week, MONDAY, parity_matters=True)


def test_week_marks_cancelled_replaced_events_and_counts_homework():
    days = week_days()
    days[0].lessons[1].is_cancelled = True
    days[0].lessons[2].is_replaced = True
    days[0].events = [
        ResolvedEvent(
            title="Обед", kind=EventKind.CANTEEN, starts_at=time(12, 30), ends_at=time(13, 0)
        )
    ]
    days[0].homework = [ResolvedHomework("Алгебра", "№ 1")]
    days[1].homework = [ResolvedHomework("Алгебра", "№ 1"), ResolvedHomework("Физика", "§ 2")]
    days[2].homework = [ResolvedHomework(f"П{i}", "x") for i in range(5)]
    days[3].kind = DayKind.HOLIDAY

    text = render_week(days, MONDAY, parity_matters=False)
    assert "<s>2 · 09:25 Физика</s>" in text
    assert "3 · 10:25 История 🔁" in text
    assert "🍽 12:30 Обед" in text
    assert "📝 1 задание" in text
    assert "📝 2 задания" in text
    assert "📝 5 заданий" in text
    assert "🏖 Каникулы / выходной" in text


def test_week_escapes_what_people_typed():
    days = week_days()
    days[0].lessons[0].subject = "<b>Алгебра</b>"
    text = render_week(days, MONDAY, parity_matters=False)
    assert "&lt;b&gt;Алгебра&lt;/b&gt;" in text


def test_week_sheds_rooms_then_events_to_stay_under_the_limit():
    days = [day(MONDAY + timedelta(days=offset)) for offset in range(6)]
    for one in days:
        one.lessons = [
            lesson(
                i,
                f"Предмет {i}",
                "08:30",
                "09:15",
                room=f"каб. {i}" * 6,
                teacher="Иванова Мария Ивановна " * 3,
            )
            for i in range(1, 12)
        ]
        one.events = [
            ResolvedEvent(
                title="Событие " * 8,
                kind=EventKind.EVENT,
                starts_at=time(12, 30),
                ends_at=time(13, 0),
            )
            for _ in range(6)
        ]
    text = render_week(days, MONDAY, parity_matters=False)
    assert len(text) <= WEEK_TEXT_LIMIT
    assert "Иванова" not in text
    assert "Предмет 11" in text  # lessons are never dropped


# --------------------------------------------------------------------------
# «Что дальше»
# --------------------------------------------------------------------------


def test_next_before_school():
    text = render_next(day(MONDAY, four_lessons()), None, at("07:18"))
    assert text == "Первый урок в 08:30 (через 1 ч 12 мин): Алгебра, каб. 214"


def test_next_inside_a_lesson_counts_down_and_names_the_break():
    text = render_next(day(MONDAY, four_lessons()), None, at("10:58"))
    assert "Сейчас: <b>3. История</b> — до 11:10 (осталось 12 мин)" in text
    assert "Дальше: перемена 15 мин, потом 4. Химия, каб. 402" in text


def test_next_rounds_seconds_up():
    text = render_next(day(MONDAY, four_lessons()), None, at("11:09:30"))
    assert "осталось 1 мин" in text


def test_next_during_a_break():
    text = render_next(day(MONDAY, four_lessons()), None, at("11:18"))
    assert text.startswith("Перемена до 11:25 (осталось 7 мин)")
    assert "Дальше: <b>4. Химия, каб. 402</b>" in text


def test_next_in_the_last_lesson():
    text = render_next(day(MONDAY, four_lessons()), None, at("12:00"))
    assert "Сейчас: <b>4. Химия, каб. 402</b> — до 12:10 (осталось 10 мин)" in text
    assert "уроки закончились" in text


def test_next_after_school_describes_tomorrow():
    tomorrow = day(MONDAY + timedelta(days=1), four_lessons())
    text = render_next(day(MONDAY, four_lessons()), tomorrow, at("12:10"))
    assert text == "Уроки закончились.\nЗавтра: 4 урока, первый в 08:30 — Алгебра"


def test_next_after_school_names_a_later_school_day_by_weekday():
    next_monday = day(MONDAY + timedelta(days=7), four_lessons()[:1])
    text = render_next(day(MONDAY, four_lessons()), next_monday, at("15:00"))
    assert "В понедельник, 14 сентября: 1 урок, первый в 08:30 — Алгебра" in text
    tuesday = day(MONDAY + timedelta(days=8), four_lessons()[:2])
    assert "Во вторник, 15 сентября: 2 урока" in render_next(
        day(MONDAY, four_lessons()), tuesday, at("15:00")
    )


def test_next_on_a_day_off():
    holiday = day(MONDAY)
    holiday.kind = DayKind.HOLIDAY
    text = render_next(holiday, None, at("10:00"))
    assert text.startswith("🏖 Каникулы / выходной")
    assert "Следующий учебный день пока не назначен." in text
    assert render_next(day(MONDAY), None, at("10:00")).startswith("Сегодня уроков нет.")


def test_next_skips_a_cancelled_lesson():
    lessons = four_lessons()
    lessons[1].is_cancelled = True
    text = render_next(day(MONDAY, lessons), None, at("09:30"))
    assert text.startswith("Перемена до 10:25 (осталось 55 мин)")
    assert "Дальше: <b>3. История</b>" in text
    # Inside the first lesson the break announced is the long one.
    text = render_next(day(MONDAY, lessons), None, at("09:00"))
    assert "перемена 1 ч 10 мин, потом 3. История" in text


def test_next_ignores_events_when_deciding_the_state():
    resolved = day(MONDAY, four_lessons())
    resolved.events = [
        ResolvedEvent(
            title="Обед", kind=EventKind.CANTEEN, starts_at=time(11, 10), ends_at=time(11, 25)
        )
    ]
    assert render_next(resolved, None, at("11:18")).startswith("Перемена до 11:25")


def test_refresh_never_produces_an_identical_message():
    message = FakeEditable(text="Уроки закончились.\nЗавтра: 4 урока")
    same = "Уроки закончились.\nЗавтра: <b>4 урока</b>"
    assert distinct_from(same, message) == same + INVISIBLE
    message.text = same.replace("<b>", "").replace("</b>", "") + INVISIBLE
    assert distinct_from(same, message) == same
    assert distinct_from("другое", message) == "другое"


# --------------------------------------------------------------------------
# Week and «next» handlers
# --------------------------------------------------------------------------


async def test_week_command_renders_the_fixture_monday(session, school_class):
    message = FakeMessage(text="/week")
    await cmd_week(message, session, school_class, Role.VIEWER)
    assert "Алгебра · 214" in message.last
    assert "Неделя:" not in message.last

    session.add(
        TimetableEntry(
            class_id=school_class.id, weekday=2, index=1, subject_name="Труд", parity=WeekParity.ODD
        )
    )
    await session.commit()
    await cmd_week(message, session, school_class, Role.VIEWER)
    assert "Неделя: " in message.last


async def test_week_navigation_is_clamped(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    await show_week(callback, SimpleNamespace(offset=10_000), session, school_class, Role.VIEWER)
    assert "Неделя" in callback.message.last
    assert not callback.alerted


async def test_week_and_next_refuse_strangers(session, school_class):
    message = FakeMessage(text="/week")
    await cmd_week(message, session, None, None)
    assert "Нет доступа" in message.last
    callback = FakeCallback(message=FakeEditable())
    await menu_next(callback, session, school_class, None)
    assert callback.alerted


async def test_next_command_answers_something_sensible(session, school_class):
    message = FakeMessage(text="/next")
    await cmd_next(message, session, school_class, Role.VIEWER)
    assert message.last
    assert "<" not in message.last.replace("<b>", "").replace("</b>", "")


# --------------------------------------------------------------------------
# Tasks
# --------------------------------------------------------------------------


def make_task(title: str, due: date | None = None, **extra) -> PersonalTask:
    task = PersonalTask(
        class_id=1, telegram_id=42, title=title, due_date=due, priority=1, done=False
    )
    for key, value in extra.items():
        setattr(task, key, value)
    return task


def test_task_list_groups_in_order_with_icons():
    tasks = [
        make_task("Просрочка", MONDAY - timedelta(days=1), priority=2),
        make_task("Сегодня дело", MONDAY, due_time=time(18, 0), subject_name="Алгебра"),
        make_task("Завтра дело", MONDAY + timedelta(days=1), priority=0),
        make_task("Потом", MONDAY + timedelta(days=5)),
        make_task("Когда-нибудь"),
        make_task("Готово", done=True),
    ]
    text = render_task_list(tasks, MONDAY)
    order = [
        text.index(f"<b>{name}</b>")
        for name in ("Просрочено", "Сегодня", "Завтра", "Позже", "Без срока")
    ]
    assert order == sorted(order)
    assert "🔴 Просрочка — до 06.09" in text
    assert "🟡 Сегодня дело — до 07.09 18:00 (Алгебра)" in text
    assert "⚪ Завтра дело — до 08.09" in text
    assert "Готово" not in text
    assert "<b>Сделано</b>" in render_task_list(tasks, MONDAY, show_done=True)
    assert "✅ <s>Готово</s>" in render_task_list(tasks, MONDAY, show_done=True)


def test_task_list_truncates_and_escapes():
    tasks = [make_task(f"<Задача {i}>") for i in range(45)]
    text = render_task_list(tasks, MONDAY)
    assert "… и ещё 5" in text
    assert "&lt;Задача 0&gt;" in text
    assert len(text) < 4096


def test_task_keyboard_shows_ten_buttons_at_most():
    tasks = [make_task(f"Очень длинное название задачи номер {i}") for i in range(12)]
    for i, task in enumerate(tasks):
        task.id = i + 1
    labels = buttons(task_list_keyboard(tasks, show_done=False))
    assert labels[0] == "☐ Очень длинное название…"
    assert sum(label.startswith("☐") for label in labels) == 10
    assert "🗂 Показать сделанные" in labels
    assert "Скрыть сделанные" in buttons(task_list_keyboard(tasks, show_done=True))


def test_empty_task_list_explains_the_grammar():
    text = render_task_list([], MONDAY)
    assert "/task" in text
    assert "Удалить" not in " ".join(buttons(task_list_keyboard([], False)))


async def test_task_command_parses_one_shot_text(session, school_class):
    message = FakeMessage(text="/task Купить тетрадь до 15.09 в 18:00 !")
    await cmd_task(
        message,
        SimpleNamespace(args="Купить тетрадь до 15.09 в 18:00 !"),
        FakeState(),
        session,
        school_class,
        Role.VIEWER,
    )
    task = await session.scalar(select(PersonalTask))
    assert task.title == "Купить тетрадь"
    assert task.due_time == time(18, 0)
    assert task.due_date.month == 9 and task.due_date.day == 15
    assert task.priority == 2
    assert task.telegram_id == 42
    assert "Купить тетрадь" in message.last
    assert "🔴 высокий" in message.last
    assert "Напомнить?" in message.last


async def test_task_command_without_text_starts_the_form(session, school_class):
    message = FakeMessage(text="/task")
    state = FakeState()
    await cmd_task(message, SimpleNamespace(args=None), state, session, school_class, Role.VIEWER)
    assert state.state is AddTask.text
    assert "до 15.09" in message.last

    typed = FakeMessage(text="Сдать реферат ?")
    await task_add_text(typed, state, session, school_class, Role.VIEWER)
    task = await session.scalar(select(PersonalTask))
    assert task.title == "Сдать реферат"
    assert task.priority == 0
    assert state.cleared
    assert "Срок: не задан" in typed.last


async def test_task_form_refuses_a_caller_without_a_class(session, school_class):
    state = FakeState(state=AddTask.text)
    message = FakeMessage(text="взлом")
    await task_add_text(message, state, session, None, None)
    assert message.replies == []
    assert state.cleared
    assert await session.scalar(select(PersonalTask)) is None


async def test_task_with_only_a_date_is_rejected(session, school_class):
    message = FakeMessage(text="/task завтра")
    await cmd_task(
        message, SimpleNamespace(args="завтра"), FakeState(), session, school_class, Role.VIEWER
    )
    assert await session.scalar(select(PersonalTask)) is None
    assert "что нужно сделать" in message.last


async def test_ticking_a_task_is_scoped_to_its_owner(session, school_class):
    session.add(PersonalTask(class_id=school_class.id, telegram_id=42, title="Моя"))
    session.add(PersonalTask(class_id=school_class.id, telegram_id=99, title="Чужая"))
    await session.commit()
    mine = await session.scalar(select(PersonalTask).where(PersonalTask.title == "Моя"))
    theirs = await session.scalar(select(PersonalTask).where(PersonalTask.title == "Чужая"))

    callback = FakeCallback(message=FakeEditable())
    await task_toggle_done(
        callback,
        SimpleNamespace(value=str(theirs.id), show_done=0),
        session,
        school_class,
        Role.VIEWER,
    )
    assert callback.alerted
    await session.refresh(theirs)
    assert theirs.done is False

    callback = FakeCallback(message=FakeEditable())
    await task_toggle_done(
        callback,
        SimpleNamespace(value=str(mine.id), show_done=0),
        session,
        school_class,
        Role.VIEWER,
    )
    await session.refresh(mine)
    assert mine.done is True
    assert mine.done_at is not None
    assert "Список пуст" in callback.message.last
    assert callback.answers[-1] == ("Сделано ✅", False)

    callback = FakeCallback(message=FakeEditable())
    await task_toggle_done(
        callback,
        SimpleNamespace(value="not-an-id", show_done=0),
        session,
        school_class,
        Role.VIEWER,
    )
    assert callback.alerted


async def test_deleting_a_task(session, school_class):
    session.add(PersonalTask(class_id=school_class.id, telegram_id=42, title="Удалить меня"))
    await session.commit()
    task = await session.scalar(select(PersonalTask))

    callback = FakeCallback(message=FakeEditable())
    await task_delete(
        callback,
        SimpleNamespace(value=str(task.id), show_done=0),
        session,
        school_class,
        Role.VIEWER,
    )
    assert await session.scalar(select(PersonalTask)) is None
    assert callback.answers[-1] == ("Удалено", False)


def test_remind_at_options():
    task = make_task("Реферат", date(2026, 9, 15), due_time=time(18, 0))
    assert remind_at_for(task, "hour") == datetime(2026, 9, 15, 17, 0)
    assert remind_at_for(task, "morning") == datetime(2026, 9, 15, 8, 0)
    assert remind_at_for(task, "eve") == datetime(2026, 9, 14, 20, 0)
    assert remind_at_for(task, "bogus") is None
    assert remind_at_for(make_task("Без времени", date(2026, 9, 15)), "hour") is None
    assert remind_at_for(make_task("Без даты"), "morning") is None


async def test_setting_a_task_reminder(session, school_class):
    far = date.today() + timedelta(days=30)
    session.add(
        PersonalTask(class_id=school_class.id, telegram_id=42, title="Реферат", due_date=far)
    )
    session.add(
        PersonalTask(
            class_id=school_class.id, telegram_id=42, title="Прошлое", due_date=date(2020, 1, 1)
        )
    )
    await session.commit()
    task = await session.scalar(select(PersonalTask).where(PersonalTask.title == "Реферат"))
    past = await session.scalar(select(PersonalTask).where(PersonalTask.title == "Прошлое"))

    callback = FakeCallback(message=FakeEditable())
    await task_set_reminder(
        callback,
        SimpleNamespace(value=f"{task.id}_eve", show_done=0),
        session,
        school_class,
        Role.VIEWER,
    )
    await session.refresh(task)
    assert task.remind_at == datetime.combine(far - timedelta(days=1), time(20, 0))
    assert "Напомню" in callback.message.last

    callback = FakeCallback(message=FakeEditable())
    await task_set_reminder(
        callback,
        SimpleNamespace(value=f"{task.id}_hour", show_done=0),
        session,
        school_class,
        Role.VIEWER,
    )
    assert callback.alerted  # no due time -> «за час» is not possible

    callback = FakeCallback(message=FakeEditable())
    await task_set_reminder(
        callback,
        SimpleNamespace(value=f"{past.id}_morning", show_done=0),
        session,
        school_class,
        Role.VIEWER,
    )
    assert callback.alerted
    await session.refresh(past)
    assert past.remind_at is None


async def test_task_view_lists_only_the_callers_tasks(session, school_class):
    session.add(PersonalTask(class_id=school_class.id, telegram_id=42, title="Моя"))
    session.add(PersonalTask(class_id=school_class.id, telegram_id=99, title="Чужая"))
    await session.commit()
    text, keyboard = await task_view(session, school_class, 42, False)
    assert "Моя" in text
    assert "Чужая" not in text
    assert "☐ Моя" in buttons(keyboard)


# --------------------------------------------------------------------------
# Homework ticks
# --------------------------------------------------------------------------


def test_homework_digest_is_backwards_compatible_and_strikes_done_items():
    days = [
        day(
            MONDAY, homework=[ResolvedHomework("Алгебра", "№ 1"), ResolvedHomework("Физика", "§ 2")]
        )
    ]
    plain = render_homework_digest(days, MONDAY)
    assert "• <b>Алгебра</b>: № 1" in plain
    assert "✅" not in plain

    ticked = render_homework_digest(days, MONDAY, {(MONDAY, "Алгебра")})
    assert "✅ <s><b>Алгебра</b>: № 1</s>" in ticked
    assert "• <b>Физика</b>: § 2" in ticked


#: The longest message Telegram will deliver, counted after entity parsing.
#: Not a constant of ours - it is theirs, and a message past it is refused
#: whole rather than clipped.
TELEGRAM_TEXT_LIMIT = 4096


def a_fortnight_of_homework() -> list[ResolvedDay]:
    """Three assignments a day for the fortnight the digest asks the resolver for.

    Nothing exotic: «Домашнее задание» looks fourteen days ahead, and three
    subjects setting something each day is an ordinary week in a ninth year.
    """
    return [
        day(
            MONDAY + timedelta(days=offset),
            homework=[
                ResolvedHomework(
                    subject,
                    "Прочитать параграф 12, ответить на вопросы 1-5 письменно, "
                    "решить задачи 340, 341 и 342 из учебника",
                    id=offset * 10 + n,
                )
                for n, subject in enumerate(("Алгебра", "Физика", "История"))
            ],
        )
        for offset in range(14)
    ]


class FakeContactMessage(FakeMessage):
    """A shared contact, and the keyboard each reply was sent with."""

    def __init__(self, phone: str, user_id: int = 555) -> None:
        super().__init__(text=None, user_id=user_id)
        self.contact = SimpleNamespace(user_id=user_id, phone_number=phone)
        self.markups: list[object] = []

    async def answer(self, text: str, reply_markup=None, **_) -> None:
        self.replies.append(text)
        self.markups.append(reply_markup)


async def test_sharing_a_contact_draws_the_menu_of_the_role_you_actually_have(
    session, school_class
):
    """An unused invite may name a role below the one the person already holds.

    `claim_phone_invites` keeps the higher role — that much was already true —
    but the reply and the keyboard were built from the invite, so an admin
    sharing their number was told they were an observer and handed an
    observer's menu, with «🧩 Расписание», «👥 Доступ» and «⚙️ Класс» gone.
    """
    session.add(BotUser(telegram_id=555, class_id=school_class.id, role=Role.ADMIN))
    session.add(PhoneInvite(class_id=school_class.id, phone="79001234567", role=Role.VIEWER))
    await session.commit()

    message = FakeContactMessage("+7 900 123-45-67")
    await on_contact(message, session)

    assert Role.ADMIN.title_ru in message.replies[0]
    assert "👥 Доступ" in buttons(message.markups[-1])


def test_the_homework_digest_stays_inside_the_message_telegram_will_send():
    """A fortnight of ordinary homework used to build 5371 characters.

    Telegram refuses the whole message over 4096 - so «📝 Домашнее задание»
    answered «что-то пошло не так» and /homework answered nothing at all, for
    exactly the classes that use the feature most.
    """
    text = render_homework_digest(a_fortnight_of_homework(), MONDAY)

    assert len(text) <= TELEGRAM_TEXT_LIMIT
    # Cut, and said so: a digest that silently stops after the eighth day is
    # the failure «… и ещё N» exists to name.
    assert "… и ещё" in text


def test_every_homework_row_drawn_has_a_button_under_it():
    """The rule this project already holds for the management pages.

    The digest drew every assignment of the fortnight and the keyboard offered
    twelve buttons, so the rest were visible, untickable and unmentioned.
    """
    days = a_fortnight_of_homework()
    ids = {
        (one.date, item.subject): item.id
        for one in days
        for item in one.homework
    }

    text = render_homework_digest(days, MONDAY)
    keyboard = homework_tick_keyboard(days, MONDAY, set(), ids, [])

    drawn = [line for line in text.splitlines() if line.startswith(("• ", "✅ "))]
    pressable = [label for label in buttons(keyboard) if label.startswith(("☐ ", "✅ "))]
    assert len(drawn) == len(pressable)


def test_tick_keyboard_labels_and_cap():
    days = [
        day(MONDAY, homework=[ResolvedHomework("Алгебра", "№ 1")]),
        day(
            MONDAY + timedelta(days=1),
            homework=[ResolvedHomework("Очень длинное название предмета", "x")],
        ),
        day(
            MONDAY + timedelta(days=3), homework=[ResolvedHomework(f"П{i}", "x") for i in range(15)]
        ),
    ]
    ids = {
        (d.date, item.subject): 100 + n
        for n, (d, item) in enumerate((d, i) for d in days for i in d.homework)
    }
    keyboard = homework_tick_keyboard(days, MONDAY, {(MONDAY, "Алгебра")}, ids, [])
    labels = buttons(keyboard)
    assert labels[0] == "✅ Алгебра · сегодня"
    assert labels[1] == "☐ Очень длинное назва… · завтра"
    assert labels[2] == "☐ П0 · Чт 10.09"
    assert labels[-1] == "‹ Меню"
    assert len(labels) == 12 + 1
    assert keyboard.inline_keyboard[0][0].callback_data == "hwt:toggle:100"


async def test_homework_command_and_toggle(session, school_class):
    due = date.today() + timedelta(days=2)
    session.add(
        Homework(class_id=school_class.id, due_date=due, subject_name="Алгебра", text="№ 12")
    )
    await session.commit()
    homework = await session.scalar(select(Homework))

    message = FakeMessage(text="/homework")
    await cmd_homework(message, session, school_class, Role.EDITOR)
    assert "• <b>Алгебра</b>: № 12" in message.last

    text, keyboard = await homework_view(session, school_class, 42, Role.EDITOR)
    labels = buttons(keyboard)
    assert labels[0].startswith("☐ Алгебра")
    assert "➕ Добавить ДЗ" in labels
    _, viewer_keyboard = await homework_view(session, school_class, 42, Role.VIEWER)
    assert "➕ Добавить ДЗ" not in buttons(viewer_keyboard)

    callback = FakeCallback(message=FakeEditable())
    await homework_toggle(
        callback,
        SimpleNamespace(action="toggle", value=str(homework.id)),
        session,
        school_class,
        Role.VIEWER,
    )
    assert "✅ <s><b>Алгебра</b>: № 12</s>" in callback.message.last
    assert callback.answers[-1] == ("Сделано ✅", False)
    tick = await session.scalar(select(HomeworkDone))
    assert tick.telegram_id == 42

    # A classmate's view is untouched by my tick.
    other_text, _ = await homework_view(session, school_class, 99, Role.VIEWER)
    assert "✅" not in other_text

    callback = FakeCallback(message=FakeEditable())
    await homework_toggle(
        callback,
        SimpleNamespace(action="toggle", value=str(homework.id)),
        session,
        school_class,
        Role.VIEWER,
    )
    assert "• <b>Алгебра</b>: № 12" in callback.message.last
    assert await session.scalar(select(HomeworkDone)) is None


async def test_toggling_a_deleted_or_foreign_homework_is_harmless(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    await homework_toggle(
        callback,
        SimpleNamespace(action="toggle", value="12345"),
        session,
        school_class,
        Role.VIEWER,
    )
    assert callback.alerted
    assert "Домашних заданий пока нет" in callback.message.last
    callback = FakeCallback(message=FakeEditable())
    await homework_toggle(
        callback, SimpleNamespace(action="toggle", value="x"), session, school_class, Role.VIEWER
    )
    assert callback.alerted


# --------------------------------------------------------------------------
# Reminders
# --------------------------------------------------------------------------


async def test_remind_card_is_created_with_the_defaults(session, school_class):
    message = FakeMessage(text="/remind")
    await cmd_remind(
        message, SimpleNamespace(args=None), FakeState(), session, school_class, Role.VIEWER
    )
    assert "☀️ Утренняя сводка: <b>выкл</b>" in message.last
    assert "🔄 Замены и события: <b>вкл</b>" in message.last
    assert "📝 Новые задания: <b>выкл</b>" in message.last
    assert "пяти минут" in message.last
    settings = await session.scalar(select(ReminderSettings))
    assert settings.telegram_id == 42 and settings.class_id == school_class.id


async def test_setting_a_digest_time(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    state = FakeState()
    await reminder_time_prompt(
        callback,
        SimpleNamespace(action="set_time", value="morning"),
        state,
        school_class,
        Role.VIEWER,
    )
    assert state.state is SetReminderTime.kind
    assert state.data == {"kind": "morning"}

    bad = FakeMessage(text="полвосьмого")
    await reminder_time_apply(bad, state, session, school_class, Role.VIEWER)
    assert "Не понял время" in bad.last
    assert not state.cleared

    good = FakeMessage(text="7.30")
    await reminder_time_apply(good, state, session, school_class, Role.VIEWER)
    settings = await session.scalar(select(ReminderSettings))
    assert settings.morning_at == time(7, 30)
    assert "☀️ Утренняя сводка: <b>07:30</b>" in good.last
    assert state.cleared

    callback = FakeCallback(message=FakeEditable())
    await reminder_clear_time(
        callback,
        SimpleNamespace(action="clear_time", value="morning"),
        session,
        school_class,
        Role.VIEWER,
    )
    await session.refresh(settings)
    assert settings.morning_at is None


async def test_a_digest_time_already_past_today_starts_tomorrow(session, school_class):
    """The tick asks only whether the class clock is past the time and whether
    it was sent today, so a morning digest set in the evening used to arrive
    five minutes later — «☀️ Доброе утро!» over a day that was already over."""
    state = FakeState(data={"kind": "morning"}, state=SetReminderTime.kind)
    message = FakeMessage(text="0:00")

    await reminder_time_apply(message, state, session, school_class, Role.VIEWER)

    settings = await session.scalar(select(ReminderSettings))
    assert settings.morning_at == time(0, 0)
    # Midnight is behind every wall clock there is, so this holds whenever the
    # suite runs.
    today = reminders.local_now(datetime.now(UTC), school_class).date()
    assert settings.last_morning_sent == today
    assert await reminders.due_digests(session, datetime.now(UTC)) == []


async def test_time_step_refuses_a_forged_kind_and_a_stranger(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    await reminder_time_prompt(
        callback,
        SimpleNamespace(action="set_time", value="root"),
        FakeState(),
        school_class,
        Role.VIEWER,
    )
    assert callback.alerted

    state = FakeState(data={"kind": "morning"}, state=SetReminderTime.kind)
    message = FakeMessage(text="7:30")
    await reminder_time_apply(message, state, session, None, None)
    assert message.replies == []
    assert state.cleared
    assert await session.scalar(select(ReminderSettings)) is None

    state = FakeState(data={"kind": "evil"}, state=SetReminderTime.kind)
    message = FakeMessage(text="7:30")
    await reminder_time_apply(message, state, session, school_class, Role.VIEWER)
    assert "Начните заново" in message.last
    assert state.cleared


async def test_toggles_and_remind_off(session, school_class):
    callback = FakeCallback(message=FakeEditable())
    await reminder_toggle(
        callback,
        SimpleNamespace(action="toggle", value="homework"),
        session,
        school_class,
        Role.VIEWER,
    )
    settings = await session.scalar(select(ReminderSettings))
    assert settings.notify_homework is True
    assert "📝 Новые задания: <b>вкл</b>" in callback.message.last

    await reminder_toggle(
        callback,
        SimpleNamespace(action="toggle", value="changes"),
        session,
        school_class,
        Role.VIEWER,
    )
    await session.refresh(settings)
    assert settings.notify_changes is False

    settings.morning_at = time(7, 30)
    await session.commit()
    message = FakeMessage(text="/remind off")
    await cmd_remind(
        message, SimpleNamespace(args="off"), FakeState(), session, school_class, Role.VIEWER
    )
    await session.refresh(settings)
    assert settings.morning_at is None and settings.evening_at is None
    assert settings.notify_homework is False
    assert "выключены" in message.last

    settings.notify_changes = True
    await session.commit()
    callback = FakeCallback(message=FakeEditable())
    await reminder_off(callback, session, school_class, Role.VIEWER)
    await session.refresh(settings)
    assert settings.notify_changes is False


# --------------------------------------------------------------------------
# Deep link, /tomorrow, /help, menu
# --------------------------------------------------------------------------


async def _device(session, school_class, code="ABC234") -> DeviceToken:
    device = DeviceToken(
        token_hash=hash_token(new_token()),
        class_id=school_class.id,
        device_name="Pixel <8>",
        link_code=code,
    )
    session.add(device)
    await session.commit()
    return device


async def test_deep_link_binds_the_device_and_names_the_role(session, school_class):
    device = await _device(session, school_class)
    session.add(BotUser(telegram_id=42, class_id=school_class.id, role=Role.EDITOR))
    await session.commit()

    message = FakeMessage(text="/start link_abc234")
    state = FakeState(state=AddTask.text)
    await cmd_start_link(message, SimpleNamespace(args="link_abc234"), state, session)

    await session.refresh(device)
    assert device.telegram_id == 42
    assert device.link_code is None
    assert state.cleared
    assert "Pixel &lt;8&gt;" in message.last
    assert "<b>Редактор</b>" in message.last
    assert "9А" in message.last


async def test_deep_link_without_a_role_says_the_phone_stays_read_only(session, school_class):
    await _device(session, school_class)
    message = FakeMessage(text="/start link_ABC234")
    await cmd_start_link(message, SimpleNamespace(args="link_ABC234"), FakeState(), session)
    assert "нет роли" in message.last
    assert "только читает" in message.last


async def test_deep_link_rejects_unknown_used_and_oversized_codes(session, school_class):
    device = await _device(session, school_class)
    message = FakeMessage(text="/start link_ZZZZZZ")
    await cmd_start_link(message, SimpleNamespace(args="link_ZZZZZZ"), FakeState(), session)
    assert "не подошёл" in message.last

    await cmd_start_link(message, SimpleNamespace(args="link_" + "A" * 40), FakeState(), session)
    assert "не подошёл" in message.last

    await cmd_start_link(message, SimpleNamespace(args="link_ABC234"), FakeState(), session)
    await cmd_start_link(
        FakeMessage(user_id=7), SimpleNamespace(args="link_ABC234"), FakeState(), session
    )
    await session.refresh(device)
    assert device.telegram_id == 42  # the second claim did not re-home the phone


async def test_tomorrow_shows_the_next_day(session, school_class):
    message = FakeMessage(text="/tomorrow")
    await cmd_tomorrow(message, session, school_class)
    assert message.last.startswith("<b>Завтра, ")


async def test_help_is_grouped_by_role():
    viewer = FakeMessage(text="/help")
    await cmd_help(viewer, Role.VIEWER)
    assert "/week" in viewer.last and "/tasks" in viewer.last
    assert "/import" not in viewer.last and "/code" not in viewer.last

    admin = FakeMessage(text="/help")
    await cmd_help(admin, Role.ADMIN)
    assert "<b>Администрирование</b>" in admin.last
    assert "/import" in admin.last and "/stats" in admin.last and "/code" in admin.last

    stranger = FakeMessage(text="/help")
    await cmd_help(stranger, None)
    assert "/start" in stranger.last


def test_main_menu_has_the_new_rows_for_everyone():
    labels = buttons(main_menu(Role.VIEWER))
    assert "📆 Календарь" in labels
    assert ["🗓 Неделя", "⏭ Что дальше", "✅ Мои задачи", "🔔 Напоминания"] == labels[4:8]
    assert "⚙️ Класс" not in labels
    assert "⚙️ Класс" in buttons(main_menu(Role.ADMIN))


# --------------------------------------------------------------------------
# The day card, drawn rather than assumed
# --------------------------------------------------------------------------


def test_a_day_with_nothing_on_it_says_so_instead_of_ending_on_the_heading():
    text = render_day(day(MONDAY), MONDAY)

    assert text.startswith("<b>Сегодня, 7 сентября (понедельник)</b>")
    assert "Уроков нет." in text
    # The three optional blocks are absent, not empty headings.
    assert "События" not in text
    assert "Домашнее задание" not in text


def test_a_day_off_names_the_kind_and_the_reason_above_the_empty_list():
    text = render_day(
        ResolvedDay(
            date=MONDAY,
            weekday=1,
            kind=DayKind.HOLIDAY,
            note="Актированный день: −38 °C",
        ),
        MONDAY,
    )

    assert "🏖 Каникулы / выходной" in text
    assert "<i>Актированный день: −38 °C</i>" in text
    assert "Уроков нет." in text


def test_a_day_draws_replacements_cancellations_events_and_homework():
    lessons = four_lessons()
    lessons[1].is_cancelled = True
    lessons[2].is_replaced = True
    lessons[2].teacher = "Петрова"
    lessons[2].note = "в актовом зале"
    text = render_day(
        day(
            MONDAY,
            lessons,
            events=[
                ResolvedEvent(
                    title="Экскурсия",
                    kind=EventKind.TRIP,
                    starts_at=time(12, 30),
                    ends_at=time(15, 0),
                    location="Эрмитаж",
                )
            ],
            homework=[ResolvedHomework("Алгебра", "№ 12–15")],
        ),
        MONDAY,
    )

    assert "2. <s>Физика</s> — отменён" in text
    assert "каб. 305" not in text  # a cancelled lesson keeps no room and no teacher
    assert "3. <b>История</b> 🔁 · Петрова" in text
    assert "<i>в актовом зале</i>" in text
    assert "🚌 <code>12:30–15:00</code> Экскурсия · Эрмитаж" in text
    assert "📝 <b>Алгебра</b>: № 12–15" in text


def test_a_day_escapes_the_room_the_note_and_the_event_somebody_typed():
    lessons = [lesson(1, "Алгебра", "08:30", "09:15", room="2<14", note="A&B")]
    text = render_day(
        day(
            MONDAY,
            lessons,
            events=[
                ResolvedEvent(
                    title="<b>Сбор</b>",
                    kind=EventKind.MEETING,
                    starts_at=time(15, 0),
                    ends_at=time(16, 0),
                )
            ],
            homework=[ResolvedHomework("Алгебра", "a < b")],
            note="<i>x</i>",
        ),
        MONDAY,
    )

    assert "каб. 2&lt;14" in text
    assert "A&amp;B" in text
    assert "&lt;b&gt;Сбор&lt;/b&gt;" in text
    assert "a &lt; b" in text
    assert "<i>&lt;i&gt;x&lt;/i&gt;</i>" in text


# --------------------------------------------------------------------------
# «Что дальше» when there is no next day worth naming
# --------------------------------------------------------------------------


def test_next_does_not_trip_over_a_following_day_whose_lessons_are_all_cancelled():
    """``lessons[0]`` on an empty list is an error dialog where a line belongs.

    ``next_school_day`` filters on the same predicate this line does, so the
    bot's own caller cannot reach it - but the renderer takes any resolved day
    and the two predicates have to disagree only once.
    """
    dead = day(MONDAY + timedelta(days=1), [lesson(1, "Алгебра", "08:30", "09:15")])
    dead.lessons[0].is_cancelled = True

    text = render_next(day(MONDAY), dead, at("09:00"))

    assert "Сегодня уроков нет." in text
    assert "Следующий учебный день пока не назначен." in text


def test_next_says_nothing_is_scheduled_for_a_following_day_with_no_lessons_at_all():
    text = render_next(day(MONDAY), day(MONDAY + timedelta(days=1)), at("09:00"))

    assert "Следующий учебный день пока не назначен." in text


# --------------------------------------------------------------------------
# An empty week and an empty digest
# --------------------------------------------------------------------------


def test_a_week_of_holidays_names_every_day_and_never_claims_lessons():
    days = [
        ResolvedDay(date=MONDAY + timedelta(days=n), weekday=n + 1, kind=DayKind.HOLIDAY)
        for n in range(6)
    ]
    text = render_week(days, MONDAY, parity_matters=False)

    assert "<b>🗓 Неделя 7–12 сентября</b>" in text
    assert text.count("🏖 Каникулы / выходной") == 6
    # The kind is the reason; repeating «Уроков нет» under it says it twice.
    assert "Уроков нет" not in text
    assert "Пн, 7 сентября · сегодня" in text


def test_an_ordinary_week_with_no_timetable_yet_says_so_on_every_day():
    days = [day(MONDAY + timedelta(days=n)) for n in range(6)]
    text = render_week(days, MONDAY, parity_matters=False)

    assert text.count("Уроков нет") == 6


def test_the_homework_digest_is_empty_when_everything_due_is_in_the_past():
    """Homework due yesterday is not «upcoming», and the digest that showed it
    would be a to-do list nobody can ever clear."""
    stale = day(
        MONDAY - timedelta(days=1), homework=[ResolvedHomework("Алгебра", "№ 1")]
    )
    assert render_homework_digest([stale], MONDAY) == "📝 Домашних заданий пока нет."
    assert render_homework_digest([], MONDAY) == "📝 Домашних заданий пока нет."


# --------------------------------------------------------------------------
# The access list
# --------------------------------------------------------------------------


async def _access_view(session, school_class):
    """Members and invites read back the way «👥 Доступ» reads them."""
    members = list(
        await session.scalars(
            select(BotUser)
            .where(BotUser.class_id == school_class.id)
            .order_by(BotUser.role.desc(), BotUser.id)
        )
    )
    invites = list(
        await session.scalars(
            select(PhoneInvite).where(PhoneInvite.class_id == school_class.id)
        )
    )
    return render_access_list(members, invites)


async def test_an_access_list_with_nobody_in_it_says_nobody_rather_than_nothing(
    session, school_class
):
    text = await _access_view(session, school_class)

    assert "<b>👥 Доступ к классу</b>" in text
    assert "<i>Пока никого нет.</i>" in text
    assert "Приглашения по номеру" not in text


async def test_the_access_list_names_each_member_by_the_best_name_it_has(
    session, school_class
):
    session.add_all(
        [
            BotUser(
                telegram_id=1,
                class_id=school_class.id,
                role=Role.OWNER,
                full_name="Иванова <И.И.>",
                username="ivanova",
            ),
            BotUser(
                telegram_id=2, class_id=school_class.id, role=Role.EDITOR, username="petya"
            ),
            BotUser(telegram_id=3, class_id=school_class.id, role=Role.VIEWER),
        ]
    )
    await session.commit()

    text = await _access_view(session, school_class)

    assert "• Иванова &lt;И.И.&gt; (@ivanova) — <b>Владелец</b>" in text
    assert "• petya (@petya) — <b>Редактор</b>" in text
    # No name and no handle: the id is what is left, and it is better than a
    # blank bullet nobody can act on.
    assert "• 3 — <b>Наблюдатель</b>" in text
    assert "Пока никого нет" not in text


async def test_an_invite_leaves_the_list_the_moment_it_is_used(session, school_class):
    session.add_all(
        [
            PhoneInvite(
                class_id=school_class.id,
                phone="79990000001",
                role=Role.EDITOR,
                label="мама <Пети>",
            ),
            PhoneInvite(
                class_id=school_class.id,
                phone="79990000002",
                role=Role.VIEWER,
                used_by=77,
            ),
        ]
    )
    await session.commit()

    text = await _access_view(session, school_class)

    assert "<b>Приглашения по номеру</b>" in text
    assert "⏳ +79990000001 → Редактор — мама &lt;Пети&gt;" in text
    assert "79990000002" not in text


# --------------------------------------------------------------------------
# The sentence under a role, and the reminder card
# --------------------------------------------------------------------------


@pytest.mark.parametrize("role", list(Role))
def test_every_role_has_a_sentence_saying_what_it_may_do(role):
    """A ``dict[...]`` lookup: a role added without a line here is a KeyError
    on the first /start of whoever holds it."""
    text = render_role_help(role)

    assert text.startswith("Вы") or text.startswith("У вас")
    assert text.endswith(".")


def test_the_role_sentences_widen_as_the_role_does():
    assert "смотреть" in render_role_help(Role.VIEWER)
    assert "домашнее задание" in render_role_help(Role.EDITOR)
    assert "расписание и звонки" in render_role_help(Role.ADMIN)
    assert "полный доступ" in render_role_help(Role.OWNER)


async def test_the_reminder_card_of_a_person_who_has_set_nothing(session, school_class):
    settings = ReminderSettings(class_id=school_class.id, telegram_id=42)
    session.add(settings)
    await session.commit()
    await session.refresh(settings)

    text = render_reminder_card(settings)

    assert "☀️ Утренняя сводка: <b>выкл</b>" in text
    assert "🌙 Домашка на завтра: <b>выкл</b>" in text
    # The column defaults, drawn as the database actually stored them.
    assert "🔄 Замены и события: <b>вкл</b>" in text
    assert "📝 Новые задания: <b>выкл</b>" in text
    assert "по часам класса" in text


def test_the_reminder_card_prints_the_times_that_are_set():
    text = render_reminder_card(
        ReminderSettings(
            morning_at=time(7, 30),
            evening_at=time(20, 0),
            notify_changes=False,
            notify_homework=True,
        )
    )

    assert "☀️ Утренняя сводка: <b>07:30</b>" in text
    assert "🌙 Домашка на завтра: <b>20:00</b>" in text
    assert "🔄 Замены и события: <b>выкл</b>" in text
    assert "📝 Новые задания: <b>вкл</b>" in text


# --------------------------------------------------------------------------
# The confirmation after a task is parsed
# --------------------------------------------------------------------------


async def test_a_saved_task_is_read_back_as_it_was_understood(session, school_class):
    """The point of the card: it is the only chance to notice that «до 15.09»
    was read as a date and «!» as a priority."""
    message = FakeMessage(text="/task Реферат по истории до 15.09 в 18:00 !")
    await cmd_task(
        message, SimpleNamespace(args="Реферат по истории до 15.09 в 18:00 !"),
        FakeState(), session, school_class, Role.VIEWER,
    )
    task = await session.scalar(select(PersonalTask))

    text = render_task_saved(task, task.due_date - timedelta(days=1))

    assert "✅ Задача добавлена: <b>Реферат по истории</b>" in text
    assert text.splitlines()[1].startswith("Срок: завтра, 15 сентября (")
    assert text.endswith(", 18:00\nПриоритет: 🔴 высокий")


def test_a_task_with_no_deadline_says_the_deadline_is_unset():
    text = render_task_saved(PersonalTask(title="Купить <тетрадь>", priority=0), MONDAY)

    assert "✅ Задача добавлена: <b>Купить &lt;тетрадь&gt;</b>" in text
    assert "Срок: не задан" in text
    assert "Приоритет: ⚪ низкий" in text


def test_a_task_due_on_a_date_with_no_time_names_only_the_date():
    text = render_task_saved(
        PersonalTask(title="Реферат", priority=1, due_date=date(2026, 9, 15)), MONDAY
    )

    assert "Срок: 15 сентября (вторник)" in text
    assert not text.split("Срок: ")[1].split("\n")[0].endswith("0:00")
    assert "Приоритет: 🟡 обычный" in text


# --------------------------------------------------------------------------
# /today, /tasks, /link
# --------------------------------------------------------------------------


async def test_today_draws_the_class_day_with_the_day_pager_under_it(
    session, school_class
):
    message = FakeMessage(text="/today")
    await cmd_today(message, session, school_class)

    assert message.last.startswith("<b>Сегодня, ")
    # The fixture's timetable is a Monday; on any other weekday the class has
    # none, and either way the card has to say which it is.
    assert "Алгебра" in message.last or "Уроков нет." in message.last


async def test_today_lists_the_template_of_whatever_weekday_today_is(session, school_class):
    """The fixture's three lessons, moved onto today in the class's own zone —
    which is the zone «сегодня» is decided in, not the server's."""
    today = datetime.now(school_class.tz).date()
    entries = await session.scalars(
        select(TimetableEntry).where(TimetableEntry.class_id == school_class.id)
    )
    for entry in entries:
        entry.weekday = today.isoweekday()
    await session.commit()

    message = FakeMessage(text="/today")
    await cmd_today(message, session, school_class)

    assert message.last.startswith(f"<b>Сегодня, {today.day} ")
    assert "1. <b>Алгебра</b>" in message.last
    assert "каб. 214" in message.last
    assert "3. <b>История</b>" in message.last
    # The bells the fixture pasted, not invented times.
    assert "<code>08:30–09:15</code>" in message.last


async def test_today_offers_a_stranger_the_contact_button_not_a_timetable(session):
    message = FakeMessage(text="/today")
    await cmd_today(message, session, None)

    assert "Вас пока нет в списке доступа" in message.last


async def test_tasks_lists_only_the_callers_own_and_refuses_a_stranger(
    session, school_class
):
    session.add_all(
        [
            PersonalTask(
                class_id=school_class.id, telegram_id=42, title="Моя", priority=1
            ),
            PersonalTask(
                class_id=school_class.id, telegram_id=7, title="Чужая", priority=1
            ),
        ]
    )
    await session.commit()

    mine = FakeMessage(text="/tasks")
    await cmd_tasks(mine, session, school_class, Role.VIEWER)
    assert "Моя" in mine.last
    assert "Чужая" not in mine.last

    stranger = FakeMessage(text="/tasks")
    await cmd_tasks(stranger, session, school_class, None)
    assert stranger.last == "Нет доступа. Откройте /start, чтобы получить его."


async def test_tasks_of_somebody_with_none_explains_the_grammar(session, school_class):
    message = FakeMessage(text="/tasks")
    await cmd_tasks(message, session, school_class, Role.VIEWER)

    assert "Список пуст" in message.last
    assert "/task Купить тетрадь до 15.09 в 18:00 !" in message.last


async def test_link_attaches_the_phone_and_names_the_role_it_now_has(
    session, school_class
):
    device = await _device(session, school_class, code="LNK234")
    session.add(BotUser(telegram_id=42, class_id=school_class.id, role=Role.EDITOR))
    await session.commit()

    message = FakeMessage(text="/link LNK234")
    await cmd_link(
        message, SimpleNamespace(args="lnk234"), session, school_class, Role.EDITOR
    )

    await session.refresh(device)
    assert device.telegram_id == 42
    assert device.link_code is None
    assert "Pixel &lt;8&gt;" in message.last
    assert "<b>Редактор</b>" in message.last
    assert "может редактировать" in message.last


async def test_link_without_a_code_explains_where_the_code_comes_from(
    session, school_class
):
    message = FakeMessage(text="/link")
    await cmd_link(message, SimpleNamespace(args=None), session, school_class, Role.VIEWER)

    assert "📱 <b>Привязка телефона</b>" in message.last
    assert "/link ABC123" in message.last


async def test_link_says_the_same_thing_to_a_wrong_code_as_to_a_used_one(
    session, school_class
):
    """The three refusals are one sentence on purpose: a different answer for
    «never existed» would say whether a code was ever real."""
    session.add(BotUser(telegram_id=42, class_id=school_class.id, role=Role.VIEWER))
    device = await _device(session, school_class, code="LNK235")
    await session.commit()

    unknown = FakeMessage(text="/link ZZZZZZ")
    await cmd_link(
        unknown, SimpleNamespace(args="ZZZZZZ"), session, school_class, Role.VIEWER
    )
    assert "Код не подошёл" in unknown.last

    await cmd_link(
        FakeMessage(), SimpleNamespace(args="LNK235"), session, school_class, Role.VIEWER
    )
    again = FakeMessage(text="/link LNK235")
    await cmd_link(
        again, SimpleNamespace(args="LNK235"), session, school_class, Role.VIEWER
    )
    assert "Код не подошёл" in again.last
    await session.refresh(device)
    assert device.telegram_id == 42


async def test_a_linked_phone_of_a_viewer_is_told_it_only_reads(session, school_class):
    await _device(session, school_class, code="LNK236")
    session.add(BotUser(telegram_id=42, class_id=school_class.id, role=Role.VIEWER))
    await session.commit()

    message = FakeMessage(text="/link LNK236")
    await cmd_link(
        message, SimpleNamespace(args="LNK236"), session, school_class, Role.VIEWER
    )

    assert "<b>Наблюдатель</b>" in message.last
    assert "только чтение" in message.last
    assert "/request" in message.last


async def test_link_refuses_somebody_with_no_class(session):
    message = FakeMessage(text="/link ABC123")
    await cmd_link(message, SimpleNamespace(args="ABC123"), session, None, None)

    assert "Нет доступа" in message.last
