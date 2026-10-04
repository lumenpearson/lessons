"""«🔔 Звонки»: the schedules, and one schedule's rows as its editor takes them back."""

from __future__ import annotations

from html import escape

from app.bot.render import clamp, more_line, plural

#: Schedules drawn on «🔔 Звонки», and the rows ``manage_keyboards.bells``
#: builds for them; ``subjects.SUBJECTS_MAX`` says why each page has a number of
#: its own.
BELLS_MAX = 10


def render_bells(schedules: list, default_id: int | None) -> str:
    lines = ["<b>🔔 Расписания звонков</b>", ""]
    if not schedules:
        lines.append("<i>Ни одного расписания ещё нет.</i>")
        return "\n".join(lines)

    for schedule in schedules[:BELLS_MAX]:
        star = "⭐ " if schedule.id == default_id else ""
        periods = schedule.periods
        lines.append(
            f"{star}<b>{escape(schedule.name)}</b> — "
            f"{plural(len(periods), 'урок', 'урока', 'уроков')}"
        )
        for period in periods[:12]:
            lines.append(
                f"<code>{period.index}. {period.starts_at:%H:%M}-{period.ends_at:%H:%M}</code>"
            )
        lines.extend(more_line(len(periods), 12))
        lines.append("")
    # Every other list on these pages says how much it hid, and this one did
    # not: a class past the cap simply lost the tail of them, with no line to
    # say so.
    lines.extend(more_line(len(schedules), BELLS_MAX))
    lines.append("⭐ — основное расписание класса.")
    return clamp(lines)


def render_bell_rows(schedule) -> str:
    """The stored rows in exactly the format the editor accepts back."""
    return "\n".join(
        f"{period.index}. {period.starts_at:%H:%M}-{period.ends_at:%H:%M}"
        for period in schedule.periods
    )
