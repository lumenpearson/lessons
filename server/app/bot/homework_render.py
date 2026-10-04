"""The homework digest, and the rows its «сделал» buttons are built from."""

from __future__ import annotations

from datetime import date as Date
from html import escape

from app.schedule import ResolvedDay
from app.wording import human_date, plural

#: Assignments one digest draws before «… и ещё N».
#:
#: The number the keyboard under it can offer, and the keyboard is built from
#: the very rows this file decided on — ``handlers/content/homework`` reads
#: :func:`homework_digest_keys`, the way ``manage_keyboards.subjects`` reads
#: the cap ``manage_render.subjects`` declares. A row past the last button is a
#: tick nobody can make, with nothing on the screen saying so.
HOMEWORK_ITEMS_MAX = 12

#: How much of one assignment a row shows. ``handlers/content`` already cuts an
#: assignment to 200 characters for the «новое задание» notification; a digest row
#: is read rather than glanced at, so it gets more — but not the four thousand
#: characters the API accepts, because twelve of those are not a message.
HOMEWORK_TEXT_MAX = 400

#: What the whole digest may grow to. Telegram's ceiling is 4096 characters
#: *after* entity parsing, and a fortnight of three assignments a day passed it at
#: 5371: ``edit_text`` then answers 400, so «📝 Домашнее задание» said «что-то
#: пошло не так» and ``/homework`` — a plain ``answer`` with no callback to
#: apologise on — said nothing at all. The margin covers the tags.
HOMEWORK_DIGEST_LIMIT = 3500


def _homework_digest(
    days: list[ResolvedDay],
    today: Date,
    done: set[tuple[Date, str]] | frozenset[tuple[Date, str]],
) -> tuple[list[str], list[tuple[Date, str]], int]:
    """The digest's lines, the assignments they name, and how many did not fit.

    One place decides, because the message and the keyboard under it have to
    agree on which assignments are on the screen: the keyboard is built from the
    keys and the message from the lines, so a row can neither appear without a
    button nor a button without a row.
    """
    upcoming = [day for day in days if day.date >= today and day.homework]
    total = sum(len(day.homework) for day in upcoming)

    lines = ["<b>📝 Домашнее задание</b>"]
    keys: list[tuple[Date, str]] = []
    used = len(lines[0]) + 1
    headed: Date | None = None

    for day in upcoming:
        for item in day.homework:
            if len(keys) >= HOMEWORK_ITEMS_MAX:
                return lines, keys, total - len(keys)
            text = item.text
            if len(text) > HOMEWORK_TEXT_MAX:
                text = text[:HOMEWORK_TEXT_MAX].rstrip() + "…"
            # Cut before escaping, never after: «&am» is a broken entity and
            # a message Telegram refuses, which is the failure being fixed.
            body = f"<b>{escape(item.subject)}</b>: {escape(text)}"
            row = (
                f"✅ <s>{body}</s>"
                if (day.date, item.subject) in done
                else f"• {body}"
            )
            heading = (
                []
                if headed == day.date
                else ["", f"<b>{escape(human_date(day.date, today)).capitalize()}</b>"]
            )
            cost = sum(len(line) + 1 for line in heading) + len(row) + 1
            # ``keys and`` so the first assignment is always drawn: a digest whose
            # whole answer is «… и ещё 1» says less than the overrun did.
            if keys and used + cost > HOMEWORK_DIGEST_LIMIT:
                return lines, keys, total - len(keys)
            lines.extend(heading)
            lines.append(row)
            used += cost
            headed = day.date
            keys.append((day.date, item.subject))
    return lines, keys, 0


def render_homework_digest(
    days: list[ResolvedDay],
    today: Date,
    done: set[tuple[Date, str]] | frozenset[tuple[Date, str]] = frozenset(),
) -> str:
    """Upcoming homework grouped by the day it is due.

    ``done`` holds (due date, subject) pairs this reader has ticked off; those
    render struck through, so the message is the same for the whole class and
    only the ticks differ per person.
    """
    lines, keys, hidden = _homework_digest(days, today, done)
    if not keys:
        return "📝 Домашних заданий пока нет."
    if hidden:
        lines.append("")
        lines.append("… и ещё " + plural(hidden, "задание", "задания", "заданий"))
    return "\n".join(lines)


def homework_digest_keys(
    days: list[ResolvedDay],
    today: Date,
    done: set[tuple[Date, str]] | frozenset[tuple[Date, str]] = frozenset(),
) -> list[tuple[Date, str]]:
    """The (due date, subject) of every assignment :func:`render_homework_digest`
    draws, in the order it draws them — which is the order the tick buttons go
    in, and the whole of what they may cover."""
    return _homework_digest(days, today, done)[1]
