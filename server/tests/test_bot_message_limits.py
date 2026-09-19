"""Every renderer that grows with the data carries a budget.

A message Telegram will not deliver is a screen that says nothing: the ceiling
is 4096 characters after entity parsing, and the whole message is refused
rather than clipped. The homework digest learned this after a fortnight of
three assignments a day came to 5371 characters and «📝 Домашнее задание» answered
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
import re
from dataclasses import dataclass, field
from html import escape, unescape
from types import SimpleNamespace
from typing import Any

import pytest
from sqlalchemy import select

from app.bot import diary_render, editor_render, render
from app.bot.handlers.access import JOIN_MODE_TEXT, access_root
from app.bot.handlers.content import event_title, override_subject
from app.bot.handlers.manage import bells_new_rows, cmd_export
from app.bot.handlers.timetable import TIMETABLE_HELP, timetable_pick_day
from app.bot.keyboards import TimetableAction
from app.models import (
    AccessRequest,
    BellPeriod,
    BellSchedule,
    BotUser,
    DayEvent,
    DayKind,
    JoinMode,
    LessonOverride,
    PhoneInvite,
    Role,
    TimetableEntry,
    WeekParity,
)
from app.providers.petersburg.models import DiaryLesson, HomeworkItem, Mark
from app.schedule import ResolvedDay, ResolvedHomework, ResolvedLesson
from app.services import notify
from app.services.reminders import render_evening

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
    text = render.render_access_list(members, [], render.MESSAGE_LIMIT)
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
    text = render.render_access_list(members, [], render.MESSAGE_LIMIT)
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


def test_an_evening_digest_of_two_ordinary_заданий_still_sends():
    """The one renderer the rule had never reached, and the worst place for it:
    `send_due` marks the digest sent *before* building it, so a refused message
    means the evening never goes out and is never retried."""
    day = ResolvedDay(
        date=TODAY + dt.timedelta(days=1),
        weekday=1,
        kind=DayKind.NORMAL,
        homework=[
            ResolvedHomework(subject="Литература", text="я" * 2500),
            ResolvedHomework(subject="История", text="я" * 1800),
        ],
    )
    assert len(render_evening(day, TODAY, set())) <= TELEGRAM_LIMIT


def test_an_evening_digest_of_a_full_timetable_still_sends():
    day = ResolvedDay(
        date=TODAY + dt.timedelta(days=1),
        weekday=1,
        kind=DayKind.NORMAL,
        homework=[
            ResolvedHomework(subject=f"Предмет {n}", text="я" * 4000) for n in range(30)
        ],
    )
    assert len(render_evening(day, TODAY, set())) <= TELEGRAM_LIMIT


def test_shorten_cuts_to_the_cap_it_declares():
    """The helper, and only the helper.

    This used to be called «…the same length whichever shell wrote it» and was
    the whole guard on that claim — which it could not be: removing
    `notify.shorten` from both call sites at once left the entire suite green,
    because nothing pressed either of them. The claim is now held where it can
    be, in `tests/test_announcements.py`, which reads what the *subscriber* was
    sent. What is left here is the unit under it.
    """
    assert len(notify.shorten("я" * 4000)) == notify.NOTIFY_TEXT_MAX


# --------------------------------------------------------------------------
# The budgets the first pass did not reach
#
# Six more renderers and handlers grew with data the class does not control,
# and each was over the ceiling on input this bot already accepts. Two of them
# had a budget that the *caller* then spent — which is the shape worth naming:
# a renderer that clamps to 3900 is not a message that fits, it is a message
# that fits if nothing is glued to it.
# --------------------------------------------------------------------------


@dataclass
class _Reply:
    """Enough of a Message/CallbackQuery for a handler that only answers.

    The handlers below are tested rather than their renderers because in each
    of them the budget *is* the handler: what overran was the gluing together,
    and a renderer called on its own says nothing about that.
    """

    text: str | None = ""
    replies: list[str] = field(default_factory=list)
    bot: Any = None

    @property
    def from_user(self):
        return SimpleNamespace(id=42, username="tester", full_name="Тестер")

    @property
    def message(self):
        return self

    async def answer(self, text: str | None = None, **_: Any) -> None:
        if text is not None:
            self.replies.append(text)

    async def edit_text(self, text: str, **_: Any) -> None:
        self.replies.append(text)

    @property
    def last(self) -> str:
        return self.replies[-1]


@dataclass
class _State:
    data: dict[str, Any] = field(default_factory=dict)

    async def get_data(self) -> dict[str, Any]:
        return dict(self.data)

    async def update_data(self, **kwargs: Any) -> dict[str, Any]:
        self.data.update(kwargs)
        return dict(self.data)

    async def set_state(self, state: Any) -> None:
        pass

    async def clear(self) -> None:
        self.data.clear()


#: A subject name a school really writes out, and the length is the point.
LONG_SUBJECT = (
    "Основы безопасности жизнедеятельности и начальной военной "
    "подготовки (подгруппа 1)"
)


def test_a_week_of_subjects_written_out_in_full_still_sends():
    """«🗓 Неделя» sheds rooms, then events, and then returned the string anyway.

    Detail 0 is already only the lesson rows, so a week still too long there
    could not be shortened by dropping anything else — and it was sent
    regardless. Six days of eight lessons named in full came to 4648
    characters, so the button answered «что-то пошло не так» and `/week`, a
    plain `answer`, answered nothing at all.
    """
    monday = dt.date(2026, 9, 14)
    days = [
        ResolvedDay(
            date=monday + dt.timedelta(days=offset),
            weekday=offset + 1,
            kind=DayKind.NORMAL,
            lessons=[
                ResolvedLesson(
                    index=index,
                    subject=LONG_SUBJECT,
                    starts_at=dt.time(8, 0),
                    ends_at=dt.time(8, 45),
                    room="305а",
                    teacher="Иванова Анна Петровна",
                )
                for index in range(1, 9)
            ],
        )
        for offset in range(6)
    ]
    text = render.render_week(days, monday, False)
    assert len(text) <= TELEGRAM_LIMIT
    assert "… и ещё" in text


def test_a_week_that_fits_is_not_cut():
    """The last resort must be invisible on the week every class actually has."""
    monday = dt.date(2026, 9, 14)
    days = [
        ResolvedDay(
            date=monday + dt.timedelta(days=offset),
            weekday=offset + 1,
            kind=DayKind.NORMAL,
            lessons=[
                ResolvedLesson(
                    index=index,
                    subject="Алгебра",
                    starts_at=dt.time(8, 0),
                    ends_at=dt.time(8, 45),
                    room="214",
                    teacher="Иванова А. П.",
                )
                for index in range(1, 7)
            ],
        )
        for offset in range(6)
    ]
    text = render.render_week(days, monday, False)
    assert "… и ещё" not in text
    assert "214" in text and "Иванова А. П." in text


def test_a_weekday_of_lessons_that_alternate_weeks_still_draws_in_the_editor():
    """«🧩 Расписание» grows three ways at once and had no budget at all.

    The paste grammar takes a 120-character subject, a 32-character room and a
    120-character teacher; a slot split by weeks draws two of those, and
    «⏱ Перемены» adds two more lines per slot. Seven split slots came to 4274
    characters — so the editor answered «что-то пошло не так», and a lesson
    typed into it committed and then looked lost, because the redraw that
    would have shown it was the thing that failed.
    """
    entries = []
    for index in range(1, 8):
        for parity in (WeekParity.ODD, WeekParity.EVEN):
            entries.append(
                TimetableEntry(
                    class_id=1,
                    weekday=1,
                    index=index,
                    subject_name="я" * 120,
                    room="я" * 32,
                    teacher="я" * 120,
                    parity=parity,
                )
            )
    periods = {
        index: BellPeriod(
            schedule_id=1, index=index, starts_at=dt.time(8, 0), ends_at=dt.time(8, 45)
        )
        for index in range(1, 8)
    }
    text = editor_render.render_day(
        1, entries, periods, show_breaks=True, counts={1: 7}
    )
    assert len(text) <= TELEGRAM_LIMIT
    assert "… и ещё" in text


def test_a_slot_card_carries_the_same_budget():
    """The slot card is written against the list it is handed, not against
    today's unique key.

    `(class, weekday, number, parity)` caps a slot at three rows, so this is
    not reachable from the database this week — but `render_slot` takes a
    `list` and `slots()` builds it by grouping, and a renderer with no budget
    is a renderer waiting for the grouping to change. Twenty rows at the
    grammar's maxima is 5867 characters.
    """
    rows = [
        TimetableEntry(
            class_id=1,
            weekday=1,
            index=3,
            subject_name="я" * 120,
            room="я" * 32,
            teacher="я" * 120,
            parity=WeekParity.ODD if number % 2 else WeekParity.EVEN,
        )
        for number in range(20)
    ]
    assert len(editor_render.render_slot(3, rows, None)) <= TELEGRAM_LIMIT


async def test_the_paste_editor_opens_on_a_day_too_long_to_quote_whole(
    session, school_class
):
    """Picking a weekday quotes it back in the format it may be pasted in.

    Seven slots split by weeks, at the grammar's own maxima, made that
    quotation 4485 characters — so the paste editor could not be opened for
    the day, not even to fix the day that had broken it. The help text is the
    instructions for the box this message asks to have filled in, so it is
    reserved rather than cut.
    """
    for index in range(1, 8):
        for parity in (WeekParity.ODD, WeekParity.EVEN):
            session.add(
                TimetableEntry(
                    class_id=school_class.id,
                    weekday=2,
                    index=index,
                    subject_name="я" * 120,
                    room="я" * 32,
                    teacher="я" * 120,
                    parity=parity,
                )
            )
    await session.flush()

    callback = _Reply()
    await timetable_pick_day(
        callback,
        TimetableAction(action="pick_day", value="2"),
        _State(),
        session,
        school_class,
        Role.ADMIN,
    )

    assert len(callback.last) <= TELEGRAM_LIMIT
    assert "… и ещё" in callback.last
    # The question survives the cut; the quotation is what gives way.
    assert TIMETABLE_HELP in callback.last


async def test_a_new_bell_schedule_echoes_no_more_prose_than_it_can_send(
    session, school_class
):
    """A rejected line is echoed whole, and five was a row cap, not a budget.

    One 4094-character paste whose first line is a real bell row and whose
    other five are prose produced a 4152-character reply. The schedule had been
    created and committed by then, and this is a `Message` handler with no
    callback to apologise on, so the admin saw neither the confirmation nor the
    «🔔 Расписания звонков» list that follows it.
    """
    junk = "\n".join(["я" * 815] * 5)
    message = _Reply(text=f"1. 08:30-09:15\n{junk}")
    assert len(message.text) <= TELEGRAM_LIMIT

    await bells_new_rows(message, _State(data={"name": "Суббота"}), session, school_class,
                         Role.ADMIN)

    assert await session.scalar(select(BellSchedule).where(BellSchedule.name == "Суббота"))
    assert len(message.replies[0]) <= TELEGRAM_LIMIT
    assert "… и ещё" in message.replies[0]


#: Telegram allows a first name and a last name of 64 characters each, so a
#: member row can carry 129 before the «@handle» and the role are added.
LONG_MEMBER_NAME = (
    "Александрова-Константинопольская-Преображенская "
    "Анна-Мария Владиславовна-Мариинская"
)


async def _crowded_access_page(session, school_class, note: str = "о" * 300):
    """Thirty members, ten pending invites and five requests with their notes.

    ``note`` is a parameter because what matters about it is not its length.
    «о» is one character before escaping and one after; a quotation mark is one
    before and six after, and the budget is spent in the second currency.
    """
    for n in range(30):
        session.add(
            BotUser(
                telegram_id=1000 + n,
                class_id=school_class.id,
                role=Role.VIEWER,
                username="anna_aleksandrova_2011_spb_kir",
                full_name=LONG_MEMBER_NAME,
            )
        )
    for n in range(10):
        session.add(
            PhoneInvite(
                class_id=school_class.id,
                phone=f"7900123456{n}",
                role=Role.EDITOR,
                invited_by=42,
                label="осенний сбор макулатуры, классный родительский комитет",
            )
        )
    for n in range(5):
        session.add(
            AccessRequest(
                class_id=school_class.id,
                telegram_id=1000 + n,
                requested_role=Role.EDITOR,
                status="pending",
                message=note,
            )
        )
    school_class.join_mode = JoinMode.INVITE
    await session.flush()

    callback = _Reply()
    await access_root(callback, session, school_class, Role.ADMIN)
    return callback.last


async def test_the_access_page_sends_with_its_heading_and_its_footing_on(
    session, school_class
):
    """`render_access_list` clamps to 3900 — of a message that is not only it.

    The pending requests are drawn above it and the join-mode paragraph below,
    and both were outside its budget, so a class where both parents of fifteen
    pupils had joined came to 4915 characters. «👥 Доступ» is the only screen
    the join mode can be switched back from, so the class was locked out of
    its own front door. The list is handed what is left rather than the whole
    ceiling; clamping the composed body instead would have cut the paragraph
    that explains the button.
    """
    body = await _crowded_access_page(session, school_class)

    assert len(body) <= TELEGRAM_LIMIT
    assert body.endswith(JOIN_MODE_TEXT[JoinMode.INVITE])


async def test_a_request_note_is_cut_before_it_eats_the_member_list(
    session, school_class
):
    """The note is `String(300)` and five may be on the page at once.

    Once the list is given only what is left, an uncut note stops being a
    length bug and becomes a theft: five of them take 1500 characters off the
    member list underneath, which drew twelve names instead of eighteen — and
    a name that is not drawn is a role no button can change.
    """
    body = await _crowded_access_page(session, school_class)

    assert "о" * (render.ACCESS_REQUEST_NOTE_MAX + 1) not in body
    assert body.count(escape(LONG_MEMBER_NAME)) > 12


async def test_the_requests_above_the_list_cannot_spend_the_whole_page(
    session, school_class
):
    """Cutting the note bounds the raw value; the message carries the escaped one.

    `cut` stops a note at `ACCESS_REQUEST_NOTE_MAX` characters — and `escape`
    then turns each «"» into six. Five notes of quotation marks are 3600
    characters where five notes of «о» are 600, so the heading rows alone
    spent the budget, the subtraction handed the member list a *negative*
    limit, `clamp` answered «… и ещё N строк» for the whole of it, and the
    page was still 4225 characters and still refused. Nothing above the list
    was bounded; the cut only made each single row look reasonable.

    The head is clamped too now, against everything except the tail and
    `ACCESS_LIST_FLOOR` — so the requests are cut before the class's own
    membership disappears, and «👥 Доступ» answers the question it is named
    for even on the worst page it can be handed.
    """
    body = await _crowded_access_page(session, school_class, note='"' * 300)

    assert len(body) <= TELEGRAM_LIMIT
    # The paragraph under the button that switches the class code back on.
    assert body.endswith(JOIN_MODE_TEXT[JoinMode.INVITE])
    # And the list itself is still there, not clamped away to its «… и ещё».
    assert "<b>👥 Доступ к классу</b>" in body
    assert escape(LONG_MEMBER_NAME) in body
    # The list's heading starts a line of its own. The blank line that used to
    # do that was the last entry of `lines`, so clamping the head took it away
    # and «… и ещё 2 строки» ran straight into «👥 Доступ к классу».
    assert "\n<b>👥 Доступ к классу</b>" in body


async def test_a_substitution_shows_back_exactly_what_it_stored(session, school_class):
    """Stored cut to 120 characters and echoed whole — wrong twice over.

    The confirmation claimed a subject the row had not kept, and at 4096
    characters typed into «Пришлите новый предмет» it came to 4161, which
    Telegram refuses — after the substitution had been committed, from a
    handler with nothing to apologise on.
    """
    message = _Reply(text="я" * 4096)
    await override_subject(
        message, _State(data={"date": "2026-09-21", "index": "2"}), session,
        school_class, Role.EDITOR,
    )

    override = await session.scalar(select(LessonOverride))
    assert len(message.last) <= TELEGRAM_LIMIT
    assert override.subject_name in message.last


async def test_an_event_shows_back_exactly_what_it_stored(session, school_class):
    """The same shape one screen over: `title[:200]` stored, `title` echoed.

    4096 characters at «Как назовём событие?» made the confirmation 4164.
    """
    message = _Reply(text="я" * 4096)
    await event_title(
        message,
        _State(data={"date": "2026-09-21", "kind": "event", "start": "09:00:00",
                     "end": "10:00:00"}),
        session,
        school_class,
        Role.EDITOR,
    )

    event = await session.scalar(select(DayEvent))
    assert len(message.last) <= TELEGRAM_LIMIT
    assert event.title in message.last


async def test_the_export_sends_every_part_it_promises(session, school_class):
    """The export arrives whole, and is measured the way Telegram measures it.

    This test was first written for a defect that does not exist. The
    reasoning was that «every «&» costs four more characters and every «<» or
    «>» three», so an escaped part would pass 4096 — and `_export_parts` was
    built to re-split on the escaped length. The Bot API settles it in one
    line: `sendMessage`'s `text` is «1-4096 characters **after entities
    parsing**». Telegram counts what it parses, so `<code>` costs nothing and
    `&lt;` counts as the one «<» it becomes. The parts were never too long.

    What the re-splitting did do was turn a long export into many more
    messages — 200 lines of apostrophes went from 6 to 34 — and 34 consecutive
    sends into one chat is the per-chat flood limit, raised out of a `Message`
    handler that has no callback to apologise on. So it is gone, and this now
    holds the two things that are actually true: the export splits, and what
    Telegram will count stays under the ceiling.
    """
    for weekday in range(1, 7):
        for index in range(4, 21):
            session.add(
                TimetableEntry(
                    class_id=school_class.id,
                    weekday=weekday,
                    index=index,
                    subject_name="Алгебра <7>",
                    room="214",
                    # Long enough that the export passes `CHUNK_LIMIT` on its
                    # raw text, which is what actually splits it. The first
                    # version of this fixture only passed the limit *after*
                    # escaping, so it stopped splitting at all the moment the
                    # escaped measurement was removed — and would have gone on
                    # asserting «> 1 part» about one part.
                    teacher="Иванова-Петрова Анна Владимировна",
                    parity=WeekParity.ANY,
                )
            )
    await session.flush()

    message = _Reply()
    await cmd_export(message, _State(), session, school_class, Role.ADMIN)

    assert len(message.replies) > 1, "the export has to be long enough to split"
    for part in message.replies:
        # What Telegram counts: the text after entity parsing, so the tags go
        # and each entity is the one character it stands for.
        parsed = unescape(re.sub(r"<[^>]+>", "", part))
        assert len(parsed) <= TELEGRAM_LIMIT, len(parsed)
    # Nothing is lost on the way: the parts still reassemble into the export.
    assert sum(part.count("Алгебра &lt;7&gt;") for part in message.replies) == 6 * 17
