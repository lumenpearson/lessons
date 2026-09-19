"""Every renderer that grows with the data carries a budget.

A message Telegram will not deliver is a screen that says nothing: the ceiling
is 4096 characters after entity parsing, and the whole message is refused
rather than clipped. The homework digest learned this after a fortnight of
three заданий a day came to 5371 characters and «📝 Домашнее задание» answered
«что-то пошло не так» — while `/homework`, a plain `answer` with no callback to
apologise on, answered nothing at all.

The fix reached two renderers. It did not reach the day view, the access list,
the task list or any of the diary's four, each of which grows with data the
class does not control, and each of which was over the ceiling on input the API
already accepts. The numbers in each test below are the measured ones.

They are written against the *renderers* rather than the handlers because the
handlers differ only in which of them they call, and because a renderer can be
handed the pathological input in one line.
"""

from __future__ import annotations

import datetime as dt
from types import SimpleNamespace

import pytest

from app.bot import diary_render, render
from app.models import DayKind, Role
from app.providers.petersburg.models import DiaryLesson, HomeworkItem, Mark
from app.schedule import ResolvedDay, ResolvedHomework, ResolvedLesson

#: Telegram's own ceiling. Every assertion here is against this and not against
#: the renderers' own margins, because the margins are the fix and the ceiling
#: is the fact.
TELEGRAM_LIMIT = 4096

TODAY = dt.date(2026, 9, 21)


def test_a_day_with_one_maximum_length_задание_still_sends():
    """`schemas.HomeworkIn.text` accepts 4000 characters and the column is
    uncapped, so one pasted essay used to take the day view to 4098 — on
    «сегодня», on «завтра», on the ‹ › pager, in the calendar card and in the
    morning digest at once."""
    day = ResolvedDay(
        date=TODAY,
        weekday=0,
        kind=DayKind.NORMAL,
        homework=[ResolvedHomework(subject="Алгебра", text="я" * 4000)],
    )
    text = render.render_day(day, TODAY)
    assert len(text) <= TELEGRAM_LIMIT
    assert "…" in text


def test_a_full_day_of_long_заданий_still_sends():
    lessons = [
        ResolvedLesson(
            index=index + 1,
            subject="Алгебра",
            starts_at=dt.time(8 + index, 30),
            ends_at=dt.time(9 + index, 15),
            room="214",
            teacher="Иванова А. П.",
        )
        for index in range(8)
    ]
    day = ResolvedDay(
        date=TODAY,
        weekday=0,
        kind=DayKind.NORMAL,
        lessons=lessons,
        homework=[
            ResolvedHomework(subject=f"Предмет {n}", text="я" * 1700) for n in range(8)
        ],
    )
    assert len(render.render_day(day, TODAY)) <= TELEGRAM_LIMIT


def test_a_short_day_is_not_shortened():
    """The budget must be invisible on the data every class actually has."""
    day = ResolvedDay(
        date=TODAY,
        weekday=0,
        kind=DayKind.NORMAL,
        homework=[ResolvedHomework(subject="Алгебра", text="Параграф 12, упр. 4–9")],
    )
    text = render.render_day(day, TODAY)
    assert "Параграф 12, упр. 4–9" in text
    assert "…" not in text


def test_the_access_list_of_a_class_where_both_parents_joined_still_sends():
    """Sixty members went to 4204 characters — and «👥 Доступ» is the only
    screen the join mode can be switched back from, so the class was locked
    out of its own settings."""
    members = [
        SimpleNamespace(
            full_name=f"Иванова Мария Петровна {n}",
            username=None,
            telegram_id=100000 + n,
            role=Role.VIEWER,
        )
        for n in range(60)
    ]
    text = render.render_access_list(members, [])
    assert len(text) <= TELEGRAM_LIMIT


def test_no_member_is_named_whose_role_no_button_can_change():
    """The rule `test_no_list_page_draws_a_row_the_keyboard_cannot_reach`
    holds for the four management pages, applied to the fifth.

    The role picker in `handlers/access` slices with this same constant,
    imported rather than written again, so the two cannot drift apart into the
    state this test describes: twenty-five names drawn above twenty buttons,
    with nothing saying five of them were out of reach.
    """
    members = [
        SimpleNamespace(
            full_name=f"Ученик {n}", username=None, telegram_id=n, role=Role.VIEWER
        )
        for n in range(25)
    ]
    text = render.render_access_list(members, [])
    assert text.count("Ученик ") == render.ACCESS_MEMBERS_MAX
    assert f"… и ещё {25 - render.ACCESS_MEMBERS_MAX}" in text


def test_forty_tasks_with_real_titles_still_send():
    """`TASK_LINES_MAX` caps lines, and a line carries a 200-character title:
    forty of them is twice what Telegram will send, which no line count can
    express."""
    tasks = [
        SimpleNamespace(
            id=n,
            title="я" * 200,
            due_date=TODAY,
            due_time=dt.time(18, 0),
            subject_name="История",
            priority=1,
            done=False,
        )
        for n in range(40)
    ]
    assert len(render.render_task_list(tasks, TODAY)) <= TELEGRAM_LIMIT


@pytest.mark.parametrize("per_day", [3, 6])
def test_a_fortnight_of_the_diary_s_homework_still_sends(per_day: int):
    """The class's own digest was given a budget after 5371 characters. This
    renders the same shape from an upstream with no length limit at all, over
    the fourteen days `handlers/diary` asks for, and measured 6113."""
    items = [
        HomeworkItem(
            due_date=TODAY + dt.timedelta(days=day),
            subject=f"Предмет {index}",
            text=(
                "Параграф 12, упражнения 4–9 письменно, выучить определения и "
                "подготовить устный ответ по вопросам в конце параграфа"
            ),
        )
        for day in range(14)
        for index in range(per_day)
    ]
    text = diary_render.render_homework(items, TODAY, TODAY + dt.timedelta(days=13), TODAY)
    assert len(text) <= TELEGRAM_LIMIT


def test_a_month_of_the_diary_s_marks_still_sends():
    marks = [
        Mark(date=TODAY, subject_name=f"Предмет {index}", value="5")
        for index in range(20)
        for _ in range(30)
    ]
    assert len(diary_render.render_marks(marks, TODAY, TODAY + dt.timedelta(days=30))) <= (
        TELEGRAM_LIMIT
    )


def test_a_diary_day_whose_every_lesson_carries_an_essay_still_sends():
    lessons = [
        DiaryLesson(
            date=TODAY,
            number=index + 1,
            subject=f"Предмет {index}",
            room="301",
            teacher="Иванова А. А.",
            topic="я" * 500,
            homework="я" * 500,
        )
        for index in range(10)
    ]
    assert len(diary_render.render_day(lessons, TODAY, TODAY)) <= TELEGRAM_LIMIT


def test_a_diary_week_of_a_school_that_fills_the_journal_still_sends():
    lessons = [
        DiaryLesson(
            date=TODAY + dt.timedelta(days=day),
            number=index + 1,
            subject=f"Предмет с длинным названием {index}",
        )
        for day in range(6)
        for index in range(12)
    ]
    assert len(diary_render.render_week(lessons, TODAY, TODAY)) <= TELEGRAM_LIMIT
