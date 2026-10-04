"""«📚 Предметы»: the list and one subject's card."""

from __future__ import annotations

from html import escape

from app.bot.render import clamp, more_line

#: Subjects drawn on «📚 Предметы», and the ✏️ buttons ``manage_keyboards``
#: builds under them — one number for both. Three of the four list pages used
#: to draw more rows than they offered buttons for — forty subjects above
#: thirty ✏️ buttons, twenty bell schedules above ten — so the tail was
#: visible, unreachable, and unmentioned by «… и ещё N». The caps differ per
#: page because the rows do: a schedule carries three buttons and twelve lines
#: of times, a subject one button and one line.
SUBJECTS_MAX = 30


def swatch(colour: str | None) -> str:
    """«■ #5B6ABF» - the square is the colour's only preview Telegram allows."""
    if not colour:
        return "—"
    return f"■ {escape(colour)}"


def render_subjects(subjects: list) -> str:
    lines = ["<b>📚 Предметы</b>", ""]
    if not subjects:
        lines.append("<i>Список пуст. «🔄 Собрать из расписания» создаст его по урокам.</i>")
        return "\n".join(lines)

    for subject in subjects[:SUBJECTS_MAX]:
        parts = [f"<b>{escape(subject.name)}</b>"]
        if subject.short_name:
            parts.append(escape(subject.short_name))
        if subject.teacher:
            parts.append(escape(subject.teacher))
        parts.append(swatch(subject.color))
        lines.append("• " + " · ".join(parts))
    lines.extend(more_line(len(subjects), SUBJECTS_MAX))
    return clamp(lines)


def render_subject_card(subject) -> str:
    return "\n".join(
        [
            f"<b>{escape(subject.name)}</b>",
            "",
            f"Сокращение: {escape(subject.short_name) if subject.short_name else '—'}",
            f"Учитель: {escape(subject.teacher) if subject.teacher else '—'}",
            f"Цвет: {swatch(subject.color)}",
            "",
            "<i>Переименование предмета переименует его и в расписании, "
            "и в домашних заданиях, и в заменах.</i>",
        ]
    )
