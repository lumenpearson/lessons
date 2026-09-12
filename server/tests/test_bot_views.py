"""The read-mostly bot views: week, «что дальше», tasks, homework ticks,
reminders and the phone deep link.

Rendering is tested as pure functions at fixed clocks; the handlers are
called directly with the same stubs as ``test_bot_handlers``.
"""

from __future__ import annotations

from datetime import date, datetime, time, timedelta
from types import SimpleNamespace

import pytest
from sqlalchemy import select
from test_bot_handlers import FakeCallback, FakeEditable, FakeMessage, FakeState

from app.bot.handlers.reminders import (
    cmd_remind,
    reminder_clear_time,
    reminder_off,
    reminder_time_apply,
    reminder_time_prompt,
    reminder_toggle,
)
from app.bot.handlers.start import cmd_help, cmd_start_link, cmd_tomorrow
from app.bot.handlers.tasks import (
    cmd_homework,
    cmd_task,
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
    render_homework_digest,
    render_next,
    render_task_list,
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
    ReminderSettings,
    Role,
    TimetableEntry,
    WeekParity,
)
from app.schedule import ResolvedDay, ResolvedEvent, ResolvedHomework, ResolvedLesson
from app.security import hash_token, new_token

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
    assert ["🗓 Неделя", "⏭ Что дальше", "✅ Мои задачи", "🔔 Напоминания"] == labels[3:7]
    assert "⚙️ Класс" not in labels
    assert "⚙️ Класс" in buttons(main_menu(Role.ADMIN))
