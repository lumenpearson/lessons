"""The button timetable editor, driven through its handlers.

``test_timetable_edit.py`` covers the renumbering; this covers the screen —
that the cursor in the payload survives every hop, that a viewer can read the
template without being handed buttons that would refuse them, and that the two
things the old paste editor could not do at all (reorder a lesson, see how long
a перемена is) reach the database and come back onto the screen.
"""

from __future__ import annotations

from datetime import time

import pytest
from sqlalchemy import func, select
from test_bot_calendar import FakeCallback, FakeEditable
from test_bot_handlers import FakeState

from app.bot import editor_render
from app.bot.editor_keyboard import BREAKS, EditorAction, EditorSubject
from app.bot.handlers import editor
from app.models import (
    BellPeriod,
    BellSchedule,
    Role,
    SchoolClass,
    Subject,
    TimetableEntry,
    WeekParity,
)
from app.services import timetable_edit


def labels(keyboard) -> list[str]:
    return [button.text for row in keyboard.inline_keyboard for button in row]


def cursors(keyboard) -> list[EditorAction]:
    out = []
    for row in keyboard.inline_keyboard:
        for button in row:
            if button.callback_data.startswith("ted:"):
                out.append(EditorAction.unpack(button.callback_data))
    return out


async def open_day(session, school_class, day: int = 1, flags: int = 0, role=Role.ADMIN):
    callback = FakeCallback()
    await editor.editor_day(
        callback,
        EditorAction(action="day", day=day, flags=flags),
        session,
        school_class,
        role,
    )
    return callback


# --------------------------------------------------------------------------
# Reading and paging
# --------------------------------------------------------------------------


async def test_the_day_lists_its_lessons_and_names_the_week_beside_it(session, school_class):
    callback = await open_day(session, school_class)

    assert "Понедельник" in callback.message.last
    assert "Алгебра" in callback.message.last
    # The week strip: the whole point of it is answering «а во вторник
    # сколько?» without stepping onto Вторник.
    assert "Пн 3" in callback.message.last
    assert [label for label in labels(callback.message.keyboard) if label.startswith("2.")] == [
        "2. Физика"
    ]


async def test_the_arrows_step_a_day_and_carry_the_view_with_them(session, school_class):
    """The breaks switch is part of the cursor, not of the day.

    Turn it on, page to Вторник, and it is still on — otherwise checking three
    days' перемены means three presses of the same switch.
    """
    callback = await open_day(session, school_class, day=1, flags=BREAKS)

    pager = [cursor for cursor in cursors(callback.message.keyboard) if cursor.action == "day"]
    previous, current, following = pager[0], pager[1], pager[2]

    assert (previous.day, current.day, following.day) == (6, 1, 2)
    assert all(cursor.flags == BREAKS for cursor in pager)


async def test_the_week_wraps_at_saturday_rather_than_dead_ending(session, school_class):
    saturday = await open_day(session, school_class, day=6)
    pager = [cursor for cursor in cursors(saturday.message.keyboard) if cursor.action == "day"]

    assert pager[2].day == 1  # › from Суббота is Понедельник, not Воскресенье


async def test_the_breaks_switch_shows_the_gap_and_only_when_it_is_on(session, school_class):
    plain = await open_day(session, school_class, flags=0)
    assert "перемена" not in plain.message.last

    with_breaks = await open_day(session, school_class, flags=BREAKS)
    # DEFAULT_BELLS: first lesson ends 09:15, second starts 09:25. Asserted
    # with the whole line, not just «10 минут» — the loose form was true of
    # «перемена · 10 10 минут» too, and that is what it was hiding.
    assert "⏸ перемена · 10 минут" in with_breaks.message.last


async def test_a_viewer_reads_the_template_but_is_offered_nothing_that_would_refuse(
    session, school_class
):
    """A button nobody in the room may press teaches people to ignore the
    refusals — the same rule «Предметы» already follows."""
    callback = await open_day(session, school_class, role=Role.VIEWER)
    shown = labels(callback.message.keyboard)

    assert "Алгебра" in callback.message.last
    assert "➕ Урок" not in shown
    assert "🍽 Столовая" not in shown
    assert "📋 Вставить день" not in shown
    assert "⏱ Перемены" in shown  # reading is not editing


async def test_a_viewer_taps_a_lesson_and_gets_it_as_an_alert_not_an_editor(
    session, school_class
):
    callback = FakeCallback()
    await editor.editor_slot(
        callback,
        EditorAction(action="slot", day=1, index=2),
        session,
        school_class,
        Role.VIEWER,
    )

    assert callback.alerted
    assert "Физика" in callback.answers[-1][0]


# --------------------------------------------------------------------------
# Writing
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("arg", "expected"),
    [(0, ["Физика", "Алгебра", "История"]), (1, ["Алгебра", "История", "Физика"])],
)
async def test_moving_a_lesson_redraws_the_day_in_its_new_order(
    session, school_class, arg, expected
):
    callback = FakeCallback()
    await editor.editor_move(
        callback,
        EditorAction(action="move", day=1, index=2, arg=arg),
        session,
        school_class,
        Role.ADMIN,
    )

    first = next(
        line for line in callback.message.last.splitlines() if line.startswith("<b>1.</b>")
    )
    assert first.startswith(f"<b>1.</b> {expected[0]}")
    assert not callback.alerted


async def test_moving_off_the_end_says_so_instead_of_redrawing_nothing(session, school_class):
    """A button that visibly does nothing is read as a broken button."""
    callback = FakeCallback()
    await editor.editor_move(
        callback,
        EditorAction(action="move", day=1, index=1, arg=0),
        session,
        school_class,
        Role.ADMIN,
    )

    assert callback.answers[-1][0] == "Уже первый"
    assert callback.message.texts == []  # nothing was redrawn


async def test_deleting_closes_the_gap_on_screen_too(session, school_class):
    callback = FakeCallback()
    await editor.editor_drop(
        callback,
        EditorAction(action="drop", day=1, index=1),
        session,
        school_class,
        Role.ADMIN,
    )

    body = callback.message.last
    assert "Алгебра" not in body
    assert "<b>1.</b> Физика" in body
    assert "<b>2.</b> История" in body


async def test_an_editor_may_not_reorder_the_template(session, school_class):
    """Замены are an editor's business; the weekly template is an admin's."""
    callback = FakeCallback()
    await editor.editor_move(
        callback,
        EditorAction(action="move", day=1, index=2, arg=0),
        session,
        school_class,
        Role.EDITOR,
    )

    assert callback.alerted
    assert callback.message.texts == []


async def test_a_typed_lesson_lands_on_the_day_it_was_asked_from(session, school_class):
    """The cursor rides in the FSM data, so the answer comes back to Среда
    rather than to whatever day the editor happens to open on."""
    state = FakeState()
    ask = FakeCallback()
    await editor.editor_ask_lesson(
        ask,
        EditorAction(action="add", day=3, flags=BREAKS),
        state,
        session,
        school_class,
        Role.ADMIN,
    )
    assert state.data["day"] == 3 and state.data["flags"] == BREAKS

    message = FakeEditable()
    message.text = "Геометрия"
    message.from_user = type("U", (), {"id": 42})()
    await editor.editor_take_lesson(message, state, session, school_class, Role.ADMIN)

    assert "Среда" in message.last
    assert "Геометрия" in message.last
    assert state.cleared


async def test_a_lesson_typed_with_a_room_and_a_teacher_keeps_both(session, school_class):
    state = FakeState()
    state.data.update(day=2, index=0, flags=0, parity=0, mode="add")

    message = FakeEditable()
    message.text = "Физика, 305, Петров П.П."
    message.from_user = type("U", (), {"id": 42})()
    await editor.editor_take_lesson(message, state, session, school_class, Role.ADMIN)

    assert "Физика · 305 · Петров П.П." in message.last


async def test_a_parity_suffix_typed_by_hand_does_not_become_the_subject_name(
    session, school_class
):
    """The bug that made the day editor and the week import disagree once.

    «Физика [чис]» parsed as a subject literally called that and went into the
    template under that name. Here the button already chose the week, so the
    suffix is stripped and ignored rather than kept.
    """
    state = FakeState()
    state.data.update(day=2, index=0, flags=0, parity=0, mode="add")

    message = FakeEditable()
    message.text = "Физика [чис]"
    message.from_user = type("U", (), {"id": 42})()
    await editor.editor_take_lesson(message, state, session, school_class, Role.ADMIN)

    assert "<b>1.</b> Физика" in message.last
    assert "[чис]" not in message.last


# --------------------------------------------------------------------------
# Столовая
# --------------------------------------------------------------------------


async def test_the_canteen_is_refused_while_there_are_no_bells_to_stand_on(
    session, school_class
):
    school_class.bell_schedule_id = None
    await session.commit()

    callback = FakeCallback()
    await editor.editor_canteen(
        callback, EditorAction(action="canteen", day=1), session, school_class, Role.ADMIN
    )

    assert callback.alerted
    assert "звонки" in callback.answers[-1][0].lower()


async def test_marking_the_canteen_names_it_on_the_break_it_falls_on(session, school_class):
    callback = FakeCallback()
    await editor.editor_set_canteen(
        callback,
        EditorAction(action="eat", day=1, index=2, flags=BREAKS),
        session,
        school_class,
        Role.ADMIN,
    )

    schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
    assert schedule.canteen_after_index == 2
    assert "🍽 <b>столовая</b>" in callback.message.last
    # And the ordinary break above it is still an ordinary break.
    assert "⏸ перемена" in callback.message.last


async def test_clearing_the_canteen_sends_it_back_to_being_a_break(session, school_class):
    schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
    schedule.canteen_after_index = 2
    await session.commit()

    callback = FakeCallback()
    await editor.editor_set_canteen(
        callback,
        EditorAction(action="eat", day=1, index=0, flags=BREAKS),
        session,
        school_class,
        Role.ADMIN,
    )

    await session.refresh(schedule)
    assert schedule.canteen_after_index is None
    assert "столовая" not in callback.message.last


# --------------------------------------------------------------------------
# The gap arithmetic itself
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("earlier", "later", "expected"),
    [
        (time(9, 15), time(9, 25), 10),
        (time(11, 10), time(11, 30), 20),
        (time(9, 15), time(9, 15), 0),
        (time(9, 25), time(9, 15), -10),  # bells out of order; caller drops it
    ],
)
def test_a_break_is_the_gap_between_two_bells(earlier, later, expected):
    assert editor_render.gap_minutes(earlier, later) == expected


def test_a_weekday_outside_the_week_does_not_unpack():
    """The payload is whatever the sender typed into a button's data.

    `day` is used both as an index into WEEKDAY_FULL and as the weekday written
    onto a TimetableEntry, so `day=0` labelled itself «Воскресенье» off the end
    of the list and then saved a lesson on weekday 0 — a row the resolver keys
    on isoweekday() and can never return. The editor showed it, every phone did
    not, and nothing said so.

    Refusing to unpack is what aiogram reads as a filter that did not match, so
    a forged payload costs a round trip instead of a lesson nobody can see.
    """
    for day in (0, -1, 7, 9):
        packed = f"ted:add:{day}:0:0:0"
        with pytest.raises(ValueError):
            EditorAction.unpack(packed)

    for day in (1, 3, 6):
        assert EditorAction.unpack(f"ted:add:{day}:0:0:0").day == day


# --------------------------------------------------------------------------
# The day as a message: what each branch of it actually prints
# --------------------------------------------------------------------------


def entry(index: int, subject: str, parity=WeekParity.ANY, **extra) -> TimetableEntry:
    return TimetableEntry(
        class_id=1, weekday=1, index=index, subject_name=subject, parity=parity, **extra
    )


def _ring(starts: str, ends: str) -> dict:
    return {"starts_at": time.fromisoformat(starts), "ends_at": time.fromisoformat(ends)}


def period(index: int, starts: str, ends: str) -> BellPeriod:
    return BellPeriod(index=index, **_ring(starts, ends))


def test_a_weekday_with_nothing_on_it_points_at_the_button_that_fixes_that():
    text = editor_render.render_day(3, [], {})

    assert text.startswith("🧩 <b>Расписание · Среда</b>")
    assert "«➕ Урок» — добавить первый" in text


def test_the_week_strip_bolds_the_day_you_are_on_and_counts_the_others():
    text = editor_render.render_day(2, [], {}, counts={1: 6, 2: 5, 4: 3})

    assert "Пн 6 · <b>Вт 5</b> · Ср 0 · Чт 3 · Пт 0 · Сб 0" in text
    # Saturday is the last rung; Sunday is not a day the editor offers.
    assert "Вс" not in text


def test_a_split_slot_is_one_number_with_both_weeks_under_it():
    text = editor_render.render_day(
        1,
        [
            entry(1, "Алгебра", WeekParity.ODD),
            entry(1, "Геометрия", WeekParity.EVEN),
        ],
        {},
    )

    assert "<b>1.</b>\n    <i>чис</i> Алгебра\n    <i>знам</i> Геометрия" in text


def test_the_breaks_switch_with_no_bells_behind_it_says_why_it_shows_nothing():
    """The switch is available to a viewer, and a class whose звонки have not
    been pasted yet would otherwise turn it on and see no difference at all."""
    text = editor_render.render_day(1, [entry(1, "Алгебра")], {}, show_breaks=True)

    assert "Звонков ещё нет — перемены посчитать не из чего." in text
    assert "перемена" not in text.replace("перемены посчитать", "")


def test_a_bell_row_that_ends_after_the_next_one_starts_draws_no_break():
    """``gap_minutes`` goes negative when the rows are out of order, and
    «⏸ перемена · −5 минут» is worse than no line at all."""
    text = editor_render.render_day(
        1,
        [entry(1, "Алгебра"), entry(2, "Физика")],
        {1: period(1, "08:30", "09:30"), 2: period(2, "09:25", "10:10")},
        show_breaks=True,
    )

    assert "перемена" not in text
    assert "<code>08:30–09:30</code>" in text


def test_the_canteen_line_replaces_the_break_it_falls_on_and_only_that_one():
    text = editor_render.render_day(
        1,
        [entry(1, "Алгебра"), entry(2, "Физика"), entry(3, "История")],
        {
            1: period(1, "08:30", "09:15"),
            2: period(2, "09:25", "10:10"),
            3: period(3, "10:25", "11:10"),
        },
        canteen_after=2,
        show_breaks=True,
    )

    assert "⏸ перемена · 10 минут" in text
    assert "🍽 <b>столовая</b> · 15 минут" in text
    assert text.count("🍽") == 1


def test_a_subject_with_an_angle_bracket_in_it_does_not_break_the_message():
    """The paste grammar takes «Алгебра <7>» whole and the bot sends HTML.
    ``render.py`` has always escaped this column; this file did not, so one
    typed bracket left the editor unable to draw its own day."""
    text = editor_render.render_day(
        1, [entry(1, "Алгебра <7>", room="2<14", teacher="Иванова & Co")], {}
    )

    assert "Алгебра &lt;7&gt; · 2&lt;14 · Иванова &amp; Co" in text
    assert "<7>" not in text


# --------------------------------------------------------------------------
# The slot card and the button that opens it
# --------------------------------------------------------------------------


def test_a_slot_that_never_split_says_so_in_as_many_words():
    text = editor_render.render_slot(
        2, [entry(2, "Физика", room="305")], period(2, "09:25", "10:10")
    )

    assert "<b>Урок 2</b>" in text
    assert "<code>09:25–10:10</code>" in text
    assert "Физика · 305" in text
    assert "<i>Каждую неделю.</i>" in text


def test_a_split_slot_card_names_both_weeks_and_claims_neither_is_every_week():
    text = editor_render.render_slot(
        1,
        [entry(1, "Алгебра", WeekParity.ODD), entry(1, "Геометрия", WeekParity.EVEN)],
        None,
    )

    assert "<b>чис</b> · Алгебра" in text
    assert "<b>знам</b> · Геометрия" in text
    assert "Каждую неделю" not in text
    assert "–" not in text  # no bells, so no time range invented


@pytest.mark.parametrize(
    ("rows", "expected"),
    [
        ([("Физика", WeekParity.ANY)], "2. Физика"),
        ([("Физика", WeekParity.ODD), ("Химия", WeekParity.EVEN)], "2. Физика / Химия"),
        ([("Основы безопасности жизнедеятельности", WeekParity.ANY)], "2. Основы безопасности ж…"),
    ],
)
def test_a_slot_button_fits_a_thumb(rows, expected):
    made = [entry(2, name, parity) for name, parity in rows]
    assert editor_render.slot_label(2, made) == expected


async def test_an_admin_taps_a_lesson_and_gets_the_card_with_its_buttons(
    session, school_class
):
    callback = FakeCallback()
    await editor.editor_slot(
        callback,
        EditorAction(action="slot", day=1, index=2),
        session,
        school_class,
        Role.ADMIN,
    )

    assert "<b>Урок 2</b>" in callback.message.last
    assert "Физика · 305" in callback.message.last
    shown = labels(callback.message.keyboard)
    assert "▲ Выше" in shown and "▼ Ниже" in shown
    assert "✏️ Изменить" in shown and "✂️ По неделям" in shown
    assert "🗑 Удалить урок" in shown


async def test_a_viewers_alert_is_the_card_with_its_tags_taken_off(session, school_class):
    """A callback answer has no parse mode: Telegram shows the string exactly
    as given, so the HTML card arrived reading «<b>Урок 2</b>»."""
    callback = FakeCallback()
    await editor.editor_slot(
        callback,
        EditorAction(action="slot", day=1, index=2),
        session,
        school_class,
        Role.VIEWER,
    )

    shown = callback.answers[-1][0]
    assert "<" not in shown and ">" not in shown
    assert shown.startswith("Урок 2")
    assert "09:25–10:10" in shown
    assert "Физика · 305" in shown


async def test_tapping_a_lesson_somebody_else_just_deleted_redraws_the_day(
    session, school_class
):
    """The day changed under a message left open. Saying «ошибка» about a
    lesson that is genuinely gone teaches people to distrust the screen."""
    callback = FakeCallback()
    await editor.editor_slot(
        callback,
        EditorAction(action="slot", day=1, index=9),
        session,
        school_class,
        Role.ADMIN,
    )

    assert callback.answers[-1][0] == "Урока уже нет"
    assert not callback.alerted
    assert "🧩 <b>Расписание · Понедельник</b>" in callback.message.last


async def test_the_editor_opens_on_monday_and_refuses_a_stranger(session, school_class):
    callback = FakeCallback()
    await editor.editor_open(callback, session, school_class, Role.VIEWER)
    assert "🧩 <b>Расписание · Понедельник</b>" in callback.message.last

    stranger = FakeCallback()
    await editor.editor_open(stranger, session, None, None)
    assert stranger.alerted
    assert "присоединитесь к классу" in stranger.answers[-1][0]
    assert stranger.message.texts == []


# --------------------------------------------------------------------------
# Splitting a slot by weeks and putting it back
# --------------------------------------------------------------------------


async def test_splitting_a_slot_copies_it_into_both_weeks(session, school_class):
    """An empty знаменатель would be a hole the day view draws every second
    week; a copy is also what the next edit almost always starts from."""
    callback = FakeCallback()
    await editor.editor_parity(
        callback,
        EditorAction(action="split", day=1, index=2),
        session,
        school_class,
        Role.ADMIN,
    )

    rows = list(
        await session.scalars(
            select(TimetableEntry).where(
                TimetableEntry.class_id == school_class.id,
                TimetableEntry.weekday == 1,
                TimetableEntry.index == 2,
            )
        )
    )
    assert sorted(row.parity for row in rows) == sorted([WeekParity.ODD, WeekParity.EVEN])
    assert {row.subject_name for row in rows} == {"Физика"}
    assert "<i>чис</i> Физика" in callback.message.last
    assert "<i>знам</i> Физика" in callback.message.last


async def test_merging_keeps_the_week_the_button_named(session, school_class):
    await editor.editor_parity(
        FakeCallback(),
        EditorAction(action="split", day=1, index=2),
        session,
        school_class,
        Role.ADMIN,
    )
    await timetable_edit.edit_lesson(
        session, school_class.id, 1, 2, WeekParity.EVEN,
        subject="Химия", room=None, teacher=None,
    )
    await session.commit()

    callback = FakeCallback()
    await editor.editor_parity(
        callback,
        EditorAction(action="merge", day=1, index=2, arg=1),  # keep знаменатель
        session,
        school_class,
        Role.ADMIN,
    )

    rows = list(
        await session.scalars(
            select(TimetableEntry).where(
                TimetableEntry.class_id == school_class.id,
                TimetableEntry.weekday == 1,
                TimetableEntry.index == 2,
            )
        )
    )
    assert [(row.subject_name, row.parity) for row in rows] == [("Химия", WeekParity.ANY)]
    assert "<b>2.</b> Химия" in callback.message.last
    assert "знам" not in callback.message.last


async def test_splitting_something_already_split_changes_nothing_and_still_redraws(
    session, school_class
):
    await editor.editor_parity(
        FakeCallback(),
        EditorAction(action="split", day=1, index=2),
        session,
        school_class,
        Role.ADMIN,
    )
    callback = FakeCallback()
    await editor.editor_parity(
        callback,
        EditorAction(action="split", day=1, index=2),
        session,
        school_class,
        Role.ADMIN,
    )

    rows = await session.scalars(
        select(TimetableEntry).where(
            TimetableEntry.class_id == school_class.id,
            TimetableEntry.weekday == 1,
            TimetableEntry.index == 2,
        )
    )
    assert len(list(rows)) == 2
    assert "<i>чис</i> Физика" in callback.message.last


async def test_an_editor_may_not_split_the_template_either(session, school_class):
    callback = FakeCallback()
    await editor.editor_parity(
        callback,
        EditorAction(action="split", day=1, index=2),
        session,
        school_class,
        Role.EDITOR,
    )

    assert callback.alerted
    assert "Только для администраторов" in callback.answers[-1][0]
    assert callback.message.texts == []


# --------------------------------------------------------------------------
# Picking a subject off the class's own list
# --------------------------------------------------------------------------


async def test_the_prompt_offers_the_subjects_the_timetable_already_uses(
    session, school_class
):
    """«расписание не зависит от списка предметов» was the complaint: the
    dictionary adopts whatever the template teaches before the list is drawn,
    so it is never empty for a class that visibly teaches something."""
    state = FakeState()
    callback = FakeCallback()
    await editor.editor_ask_lesson(
        callback,
        EditorAction(action="add", day=3),
        state,
        session,
        school_class,
        Role.ADMIN,
    )

    shown = labels(callback.message.keyboard)
    assert "Алгебра" in shown and "Физика" in shown and "История" in shown
    assert "Отмена" in shown
    assert "<b>Новый урок · Среда</b>" in callback.message.last
    assert state.data["day"] == 3 and state.data["mode"] == "add"


async def test_a_tapped_subject_lands_on_the_day_and_redraws_it_in_place(
    session, school_class
):
    state = FakeState()
    await editor.editor_ask_lesson(
        FakeCallback(), EditorAction(action="add", day=4), state, session, school_class, Role.ADMIN
    )
    subject = await session.scalar(
        select(Subject).where(
            Subject.class_id == school_class.id, Subject.name == "Физика"
        )
    )

    callback = FakeCallback()
    await editor.editor_pick_subject(
        callback,
        EditorSubject(subject=subject.id),
        state,
        session,
        school_class,
        Role.ADMIN,
    )

    assert "🧩 <b>Расписание · Четверг</b>" in callback.message.last
    assert "<b>1.</b> Физика" in callback.message.last
    assert state.cleared


async def test_a_subject_id_belonging_to_another_class_finds_nothing(session, school_class):
    """The payload is the user's to forge; a subject is re-scoped by the query
    like every other id on this surface."""
    other = SchoolClass(name="9Б", school="Школа № 1", join_code="OTHER42")
    session.add(other)
    await session.flush()
    theirs = Subject(class_id=other.id, name="Латынь")
    session.add(theirs)
    await session.commit()

    state = FakeState()
    state.data.update(day=1, index=0, flags=0, parity=0, mode="add")
    callback = FakeCallback()
    await editor.editor_pick_subject(
        callback,
        EditorSubject(subject=theirs.id),
        state,
        session,
        school_class,
        Role.ADMIN,
    )

    assert callback.alerted
    assert "Предмет не найден" in callback.answers[-1][0]
    assert "Латынь" not in "".join(callback.message.texts)


# --------------------------------------------------------------------------
# Typing a lesson in
# --------------------------------------------------------------------------


async def test_text_that_is_not_a_subject_asks_again_rather_than_saving_it(
    session, school_class
):
    state = FakeState()
    state.data.update(day=1, index=0, flags=0, parity=0, mode="add")
    message = FakeEditable()
    message.text = "   "
    message.from_user = type("U", (), {"id": 42})()

    await editor.editor_take_lesson(message, state, session, school_class, Role.ADMIN)

    assert message.last == "Не понял предмет. Пришлите ещё раз:"
    assert not state.cleared


async def test_a_day_with_no_bell_left_for_the_lesson_says_which_ceiling_it_hit(
    session, school_class
):
    """«Слишком много уроков» sent an admin looking for a limit on the
    timetable when what they needed was one more row in «🔔 Звонки»."""
    schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
    for extra in list(schedule.periods):
        if extra.index > 3:
            await session.delete(extra)
    await session.commit()

    state = FakeState()
    state.data.update(day=1, index=0, flags=0, parity=0, mode="add")
    message = FakeEditable()
    message.text = "Химия"
    message.from_user = type("U", (), {"id": 42})()

    await editor.editor_take_lesson(message, state, session, school_class, Role.ADMIN)

    assert "В расписании звонков 3 урока, и все заняты." in message.last
    assert "🔔 Звонки" in message.last
    assert state.cleared


async def test_editing_a_split_slot_changes_only_the_week_the_button_carried(
    session, school_class
):
    await editor.editor_parity(
        FakeCallback(),
        EditorAction(action="split", day=1, index=2),
        session,
        school_class,
        Role.ADMIN,
    )

    state = FakeState()
    state.data.update(day=1, index=2, flags=0, parity=1, mode="edit")  # знаменатель
    message = FakeEditable()
    message.text = "Химия, 402"
    message.from_user = type("U", (), {"id": 42})()

    await editor.editor_take_lesson(message, state, session, school_class, Role.ADMIN)

    assert "<i>чис</i> Физика · 305" in message.last
    assert "<i>знам</i> Химия · 402" in message.last


async def test_a_viewer_who_somehow_reaches_the_prompt_is_dropped_out_of_it(
    session, school_class
):
    state = FakeState()
    state.data.update(day=1, index=0, flags=0, parity=0, mode="add")
    message = FakeEditable()
    message.text = "Химия"
    message.from_user = type("U", (), {"id": 42})()

    await editor.editor_take_lesson(message, state, session, school_class, Role.VIEWER)

    assert state.cleared
    assert message.texts == []
    assert await session.scalar(
        select(func.count()).select_from(TimetableEntry).where(
            TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == 1
        )
    ) == 3


# --------------------------------------------------------------------------
# Столовая, as its own screen
# --------------------------------------------------------------------------


def test_an_unmarked_canteen_says_it_is_unmarked_rather_than_drawing_nothing():
    text = editor_render.render_canteen(None, {1: period(1, "08:30", "09:15")})

    assert "🍽 <b>Столовая</b>" in text
    assert "Не отмечена." in text
    assert "уезжает вместе со звонками" in text


def test_a_marked_canteen_names_the_break_it_sits_on():
    text = editor_render.render_canteen(
        2, {2: period(2, "09:25", "10:10"), 3: period(3, "10:25", "11:10")}
    )

    assert "На перемене после <b>2-го урока</b> — <code>10:10–10:25</code>." in text


def test_a_marked_canteen_with_no_bell_after_it_still_says_where_it_is():
    """The lesson it follows is the fact; the clock is what the bells add."""
    text = editor_render.render_canteen(2, {})

    assert "На перемене после <b>2-го урока</b>." in text
    assert "<code>" not in text


async def test_the_canteen_screen_offers_one_button_per_break(session, school_class):
    callback = FakeCallback()
    await editor.editor_canteen(
        callback, EditorAction(action="canteen", day=1), session, school_class, Role.ADMIN
    )

    shown = labels(callback.message.keyboard)
    # Seven bells means six breaks: the last lesson has none after it.
    assert [label for label in shown if label.startswith("после ")] == [
        f"после {index}" for index in range(1, 7)
    ]
    assert "Не отмечена." in callback.message.last


# --------------------------------------------------------------------------
# Every button that writes, refused from the same place
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("handler", "payload"),
    [
        ("editor_drop", EditorAction(action="drop", day=1, index=1)),
        ("editor_parity", EditorAction(action="split", day=1, index=1)),
        ("editor_canteen", EditorAction(action="canteen", day=1)),
        ("editor_set_canteen", EditorAction(action="eat", day=1, index=2)),
    ],
)
async def test_a_write_button_refuses_an_editor_and_leaves_the_screen_alone(
    session, school_class, handler, payload
):
    """Every one of them re-checks rather than trusting the keyboard it came
    from: the payload arrived from the presser's own client."""
    callback = FakeCallback()
    await getattr(editor, handler)(callback, payload, session, school_class, Role.EDITOR)

    assert callback.alerted
    assert callback.answers[-1][0] == "Только для администраторов"
    assert callback.message.texts == []


@pytest.mark.parametrize(
    ("handler", "payload"),
    [
        ("editor_day", EditorAction(action="day", day=2)),
        ("editor_slot", EditorAction(action="slot", day=1, index=1)),
        ("editor_move", EditorAction(action="move", day=1, index=2, arg=0)),
    ],
)
async def test_the_editor_shows_a_stranger_nothing_at_all(session, handler, payload):
    callback = FakeCallback()
    await getattr(editor, handler)(callback, payload, session, None, None)

    assert callback.alerted
    assert callback.answers[-1][0] == "Сначала присоединитесь к классу"
    assert callback.message.texts == []


async def test_the_prompts_refuse_before_they_set_any_state(session, school_class):
    state = FakeState()
    callback = FakeCallback()
    await editor.editor_ask_lesson(
        callback,
        EditorAction(action="add", day=1),
        state,
        session,
        school_class,
        Role.EDITOR,
    )
    assert callback.alerted
    assert state.state is None and state.data == {}

    picked = FakeCallback()
    await editor.editor_pick_subject(
        picked, EditorSubject(subject=1), state, session, school_class, Role.EDITOR
    )
    assert picked.alerted
    assert picked.message.texts == []


async def test_a_class_pointed_at_a_bell_schedule_that_is_gone_still_draws(
    session, school_class
):
    """``bell_schedule_id`` outlives the row it names if the schedule is
    deleted; the day view drops its times rather than refusing to open."""
    schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
    await session.delete(schedule)
    await session.commit()

    callback = await open_day(session, school_class, flags=BREAKS)

    assert "<b>1.</b> Алгебра · 214" in callback.message.last
    assert "Звонков ещё нет" in callback.message.last
    assert "<code>" not in callback.message.last


async def test_the_canteen_is_refused_while_there_is_only_one_bell(session, school_class):
    """Столовая stands on a перемена, and one bell has no перемена after it."""
    lonely = BellSchedule(
        class_id=school_class.id,
        name="Один звонок",
        periods=[BellPeriod(index=1, **_ring("08:30", "09:15"))],
    )
    session.add(lonely)
    await session.flush()
    school_class.bell_schedule_id = lonely.id
    await session.commit()

    callback = FakeCallback()
    await editor.editor_canteen(
        callback, EditorAction(action="canteen", day=1), session, school_class, Role.ADMIN
    )

    assert callback.alerted
    assert "перемен пока нет" in callback.answers[-1][0]


async def test_marking_the_canteen_with_no_bells_at_all_says_so(session, school_class):
    school_class.bell_schedule_id = None
    await session.commit()

    callback = FakeCallback()
    await editor.editor_set_canteen(
        callback,
        EditorAction(action="eat", day=1, index=2),
        session,
        school_class,
        Role.ADMIN,
    )

    assert callback.alerted
    assert callback.answers[-1][0] == "Звонков нет"


async def test_a_long_split_slot_still_fits_the_alert_telegram_will_take(
    session, school_class
):
    """200 characters is Telegram's ceiling on a callback answer, and going
    over it is a 400 — which on this surface is a press that answers nothing.
    Two halves with a long subject, a room and a teacher reach 284."""
    await editor.editor_parity(
        FakeCallback(),
        EditorAction(action="split", day=1, index=2),
        session,
        school_class,
        Role.ADMIN,
    )
    for parity in (WeekParity.ODD, WeekParity.EVEN):
        await timetable_edit.edit_lesson(
            session,
            school_class.id,
            1,
            2,
            parity,
            subject="Основы безопасности жизнедеятельности и начальной военной подготовки",
            room="Кабинет 214, второй этаж, левое крыло",
            teacher="Иванова-Петрова Александра Владимировна",
        )
    await session.commit()

    callback = FakeCallback()
    await editor.editor_slot(
        callback,
        EditorAction(action="slot", day=1, index=2),
        session,
        school_class,
        Role.VIEWER,
    )

    shown = callback.answers[-1][0]
    assert len(shown) <= editor_render.ALERT_MAX
    assert shown.endswith("…")
    assert shown.startswith("Урок 2")
