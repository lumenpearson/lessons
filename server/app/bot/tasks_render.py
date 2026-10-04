"""«✅ Мои задачи»: one person's list, and what a saved task says back."""

from __future__ import annotations

from datetime import date as Date
from html import escape

from app.models import PersonalTask
from app.wording import clamp, cut, human_date

PRIORITY_ICONS = {2: "🔴", 1: "🟡", 0: "⚪"}


#: Lines of tasks in one message before «… и ещё N». The text lists more than
#: the keyboard can offer on purpose, so that nothing is hidden entirely — and
#: [TASKS_UNREACHABLE] below is what stops that being the silent half of the
#: same defect the manage pages had, where rows were drawn that no button
#: could reach and nothing said so.
TASK_LINES_MAX = 40

#: Task buttons in one keyboard. Telegram allows a hundred; a phone shows about
#: ten before the message scrolls out of view.
#:
#: Declared here rather than in `tasks_keyboard`, the way `manage_render` declares
#: `SUBJECTS_MAX` and `manage_keyboards` reads it: what is drawn and what can
#: be pressed have to read one number, and this pair had two.
TASK_BUTTONS_MAX = 10

#: Drawn once, where the buttons stop, when the list is longer than they are.
TASKS_UNREACHABLE = (
    "— ниже кнопок нет: отметить и удалить можно только задачи выше, "
    "остальные — после того, как разберёте эти."
)

#: A task's title on its row. The column holds 200 characters, and forty rows
#: of two hundred is twice what Telegram will send — a line budget cannot
#: express that, which is why this one is in characters and the clamp below
#: catches what neither number does.
TASK_TITLE_MAX = 80

TASK_GROUPS = ("Просрочено", "Сегодня", "Завтра", "Позже", "Без срока")


def task_group(task: PersonalTask, today: Date) -> str:
    if task.due_date is None:
        return "Без срока"
    delta = (task.due_date - today).days
    if delta < 0:
        return "Просрочено"
    if delta == 0:
        return "Сегодня"
    if delta == 1:
        return "Завтра"
    return "Позже"


def task_line(task: PersonalTask) -> str:
    """«🔴 Реферат — до 15.09 18:00 (История)»."""
    icon = PRIORITY_ICONS.get(task.priority, "🟡")
    text = escape(cut(task.title, TASK_TITLE_MAX))
    if task.due_date is not None:
        due = f"до {task.due_date:%d.%m}"
        if task.due_time is not None:
            due += f" {task.due_time:%H:%M}"
        text += f" — {due}"
    if task.subject_name:
        text += f" ({escape(task.subject_name)})"
    if task.done:
        return f"✅ <s>{text}</s>"
    return f"{icon} {text}"


def render_task_list(tasks: list[PersonalTask], today: Date, show_done: bool = False) -> str:
    """Grouped by urgency; done ones, when shown, sit at the bottom."""
    open_tasks = [task for task in tasks if not task.done]
    done_tasks = [task for task in tasks if task.done] if show_done else []

    if not open_tasks and not done_tasks:
        return (
            "✅ <b>Мои задачи</b>\n\n"
            "Список пуст. Добавьте задачу кнопкой ниже или командой "
            "<code>/task Купить тетрадь до 15.09 в 18:00 !</code>."
        )

    lines = ["✅ <b>Мои задачи</b>"]
    budget = TASK_LINES_MAX
    hidden = 0
    drawn = 0

    def _emit(title: str, items: list[PersonalTask]) -> None:
        nonlocal budget, hidden, drawn
        if not items:
            return
        lines.append("")
        lines.append(f"<b>{title}</b>")
        for task in items:
            if budget <= 0:
                hidden += 1
                continue
            # The keyboard takes the first `TASK_BUTTONS_MAX` of the same list
            # in the same order, so the tail of this text is exactly the part
            # nothing can press. Saying so once, where it happens, is the
            # cheapest honest answer: the alternative is either hiding tasks or
            # a keyboard nobody can scroll.
            if drawn == TASK_BUTTONS_MAX:
                lines.append(TASKS_UNREACHABLE)
            lines.append(task_line(task))
            budget -= 1
            drawn += 1

    for group in TASK_GROUPS:
        _emit(group, [task for task in open_tasks if task_group(task, today) == group])
    _emit("Сделано", done_tasks)
    if hidden:
        lines.append(f"… и ещё {hidden}")
    return clamp(lines)


def render_task_saved(task: PersonalTask, today: Date) -> str:
    """The confirmation after parsing free text - shows what was understood."""
    lines = [f"✅ Задача добавлена: <b>{escape(task.title)}</b>"]
    if task.due_date is not None:
        due = human_date(task.due_date, today)
        if task.due_time is not None:
            due += f", {task.due_time:%H:%M}"
        lines.append(f"Срок: {escape(due)}")
    else:
        lines.append("Срок: не задан")
    lines.append(
        "Приоритет: "
        + {2: "🔴 высокий", 1: "🟡 обычный", 0: "⚪ низкий"}.get(task.priority, "🟡 обычный")
    )
    return "\n".join(lines)
