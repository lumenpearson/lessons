"""``/export`` and ``/import``: a timetable cut into messages, and what
«Применить» is about to do."""

from __future__ import annotations

from html import escape

from app.bot.render import WEEKDAYS, clamp, more_line, plural

#: Telegram refuses a message over 4096 characters. Splitting at 4000 leaves
#: room for the header a caller adds to each part.
CHUNK_LIMIT = 4000


#: Unparsed lines echoed back under «⚠️ Не разобрал строки». The same rule as
#: the pages above — what is drawn is what «… и ещё N» counts from — but here
#: the number was written twice into the body of ``render_import_preview``,
#: which is the arrangement that let the four pages disagree with their own
#: keyboards. Ten, because a rejected line is shown to be corrected and a
#: paste that got more than ten wrong is a paste to rewrite, not to fix.
REJECTED_MAX = 10


def split_text(text: str, limit: int = CHUNK_LIMIT) -> list[str]:
    """Cut a long message into Telegram-sized parts on line boundaries.

    A single line longer than the limit is cut mid-line rather than dropped:
    losing a line of somebody's timetable to keep the formatting tidy would be
    the wrong trade in an export that doubles as a backup.
    """
    if not text:
        return [""]

    parts: list[str] = []
    current: list[str] = []
    used = 0
    for line in text.split("\n"):
        while len(line) > limit:
            if current:
                parts.append("\n".join(current))
                current, used = [], 0
            parts.append(line[:limit])
            line = line[limit:]
        extra = len(line) + (1 if current else 0)
        if used + extra > limit:
            parts.append("\n".join(current))
            current, used = [line], len(line)
            continue
        current.append(line)
        used += extra
    if current:
        parts.append("\n".join(current))
    return parts or [""]


def render_import_preview(days: dict[int, list], rejected: list[str], bells: int = 0) -> str:
    """What «Применить» is about to do, in the order it will do it.

    ``bells`` is a count rather than the rows: a «== Звонки ==» block is part
    of the same paste and belongs in the same list. It used to be a line the
    handler glued on after this returned, which put it below the «Применить»
    footer and left a bells-only paste reading «Ни одного дня не распознано» -
    over a button that was about to rewrite the class's bells.
    """
    lines = ["<b>📥 Импорт расписания</b>", ""]
    for weekday in sorted(days):
        count = len(days[weekday])
        lines.append(
            f"• {WEEKDAYS[weekday - 1].capitalize()}: "
            f"{plural(count, 'урок', 'урока', 'уроков')}"
        )
    if bells:
        lines.append(f"• Звонки: {plural(bells, 'урок', 'урока', 'уроков')}")
    # A paste that yielded neither days nor bells never reaches here: the
    # handler answers it with the whole of IMPORT_HELP instead, because at that
    # point there is nothing to preview and the question is what to send.
    if rejected:
        lines.append("")
        lines.append("⚠️ Не разобрал строки:")
        for line in rejected[:REJECTED_MAX]:
            lines.append(f"<code>{escape(line)}</code>")
        lines.extend(more_line(len(rejected), REJECTED_MAX))
    if days or bells:
        lines.append("")
        if days:
            lines.append(
                "«Применить» заменит эти дни целиком. Остальные дни недели останутся как есть."
            )
        if bells:
            lines.append("Звонки заменят основное расписание класса целиком.")
    return clamp(lines)
