"""The rules in ``app/bot/button_style.py``, held to by the keyboards.

Colour is the one thing in a keyboard with no label to correct it: a button
whose text is wrong is reported within the day, a button whose colour is wrong
is simply believed. The three tests that matter are the two deliberate
exceptions — which look like oversights and will be «fixed» by someone reading
only the file they are in — and the restraint, which erodes one keyboard at a
time and is never visible in a single diff.
"""

from __future__ import annotations

from types import SimpleNamespace

from app.bot.button_style import DANGER, PRIMARY, SUCCESS
from app.bot.keyboards import main_menu, task_delete_confirm, task_list_keyboard
from app.bot.manage_keyboards import bells_list_keyboard
from app.models import Role


def buttons(keyboard):
    return [button for row in keyboard.inline_keyboard for button in row]


def test_the_escape_from_a_delete_confirmation_is_the_one_cancel_that_is_not_red():
    """The exception «отмена красная» has, and the reason for it.

    Красная means «this takes something away». In a confirmation both buttons
    qualify under a careless reading — one deletes, one abandons — so painting
    both leaves the pair distinguished by its labels alone, on the one screen
    where a misread costs the most.
    """
    pair = buttons(task_delete_confirm(task_id=7, show_done=False))

    assert [button.style for button in pair] == [DANGER, None]
    assert pair[0].text.startswith("🗑")
    assert pair[1].text == "Отмена"


def test_the_bells_page_paints_the_default_schedule_not_the_button_that_sets_one():
    """Green is «this is the current one», never «make this the current one».

    ⭐ is the obvious thing to paint here and it is the wrong one: with both
    green the page would say «current» and «make current» in one colour, and
    the row already in force — the only state the keyboard can show at all —
    would be the one row with nothing to mark it.
    """
    schedules = [
        SimpleNamespace(id=1, name="Обычные"),
        SimpleNamespace(id=2, name="Сокращённые"),
    ]

    rows = bells_list_keyboard(schedules, default_id=2).inline_keyboard

    default_row, other_row = rows[1], rows[0]
    assert default_row[0].style == SUCCESS
    assert len(default_row) == 1  # no ⭐ and no 🗑 on the one in force
    assert other_row[0].style is None
    assert [button.text for button in other_row] == ["✏️ Обычные", "⭐", "🗑"]
    assert other_row[1].style is None and other_row[2].style == DANGER


def test_green_on_a_task_is_its_state_and_most_of_a_keyboard_stays_plain():
    tasks = [
        SimpleNamespace(id=1, title="Сделать доклад", done=True),
        SimpleNamespace(id=2, title="Купить тетрадь", done=False),
        SimpleNamespace(id=3, title="Записаться", done=False),
    ]

    rows = buttons(task_list_keyboard(tasks, show_done=True))
    by_text = {button.text: button.style for button in rows}

    assert by_text["✅ Сделать доклад"] == SUCCESS
    assert by_text["☐ Купить тетрадь"] is None
    assert by_text["➕ Добавить"] == SUCCESS
    assert by_text["Скрыть сделанные"] == PRIMARY
    assert by_text["🗑 Удалить…"] == DANGER
    assert by_text["‹ Меню"] is None


def test_the_menu_paints_the_three_buttons_about_when_and_nothing_else():
    """The page every session starts on, and the one restraint is easiest to
    lose: it is a list of a dozen equal-looking entries, and each feature that
    lands here arrives wanting its own colour."""
    coloured = {
        button.text: button.style for button in buttons(main_menu(Role.OWNER)) if button.style
    }

    assert coloured == {
        "📅 Сегодня": SUCCESS,
        "🗓 Завтра": PRIMARY,
        "📆 Календарь": PRIMARY,
        "🗓 Неделя": PRIMARY,
    }
