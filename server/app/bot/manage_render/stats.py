"""``/find``: the assignments a word turns up."""

from __future__ import annotations

from datetime import date as Date
from datetime import timedelta
from html import escape

from app.bot.render import MONTHS_GENITIVE, WEEKDAYS, clamp, cut

#: The query echoed back in «🔎 Поиск: …». `/find` bounds its argument from
#: below (two characters) and not from above, and the heading is line 0 of a
#: page `clamp` cuts from the end — so an unbounded needle was the one line
#: that could never give way. Pasting an assignment back into `/find` to find
#: it again is the obvious way to use the command, and `schemas` accepts 4000
#: characters for one: that refused the empty page whole, and collapsed the
#: page with hits into «… и ещё N строк» with not one of the found assignments
#: on it. Long enough to recognise the query, short enough that even a needle
#: of quotation marks — six characters each after escaping — stays a heading.
SEARCH_NEEDLE_MAX = 120


def render_search(needle: str, rows: list, today: Date) -> str:
    """Search hits, newest due first. The needle is echoed back cut and then
    escaped too - it is the one string on this page that certainly came from a
    keyboard, and it was the only free text here without a budget."""
    lines = [f"<b>🔎 Поиск: {escape(cut(needle, SEARCH_NEEDLE_MAX))}</b>", ""]
    if not rows:
        lines.append("<i>Ничего не нашлось. Ищу по заданиям за последний месяц и впереди.</i>")
        # Clamped like the branch below, though the two lines above it are now
        # bounded: this page is one message either way, and a branch that
        # joins without a budget is how the unbounded needle got out.
        return clamp(lines)

    for item in rows:
        day = item.due_date
        when = f"{day.day} {MONTHS_GENITIVE[day.month - 1]}"
        if day == today:
            when = "сегодня"
        elif day == today + timedelta(days=1):
            when = "завтра"
        lines.append(
            f"• <b>{escape(item.subject_name)}</b> ({when}, {WEEKDAYS[day.weekday()]}): "
            f"{escape(cut(item.text, 200))}"
        )
    return clamp(lines)
