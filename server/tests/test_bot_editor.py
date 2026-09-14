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
from test_bot_calendar import FakeCallback, FakeEditable
from test_bot_handlers import FakeState

from app.bot import editor_render
from app.bot.editor_keyboard import BREAKS, EditorAction
from app.bot.handlers import editor
from app.models import BellSchedule, Role


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
    assert "перемена" in with_breaks.message.last
    # DEFAULT_BELLS: first lesson ends 09:15, second starts 09:25.
    assert "10 минут" in with_breaks.message.last


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
