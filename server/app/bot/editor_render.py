"""The editor's own view of a weekday: lessons, and optionally the gaps.

Separate from ``render.py`` because it renders a different thing. That file
renders a *resolved* day — what will actually happen on 15 September, замены
and каникулы applied. This renders the **template**, which has no date, and
whose whole point is the two parity variants a resolved day has already
collapsed into one.

The breaks are the reason the view has a switch. A перемена is not stored
anywhere: it is the gap between bell N's end and bell N+1's start, so it can
only ever be derived, and deriving it doubles the line count of a day that
most of the time you are reading to check which subject is third. So it is off
by default and one button away — and when it is on, it is the only place in the
project that says out loud how long anybody actually gets to eat.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from datetime import time as Time

from app.bot.keyboards import WEEKDAY_FULL
from app.bot.render import WEEKDAYS_SHORT, plural
from app.models import BellPeriod, TimetableEntry, WeekParity

#: How the two halves of a split slot are labelled in the list.
PARITY_MARK = {WeekParity.ODD: "чис", WeekParity.EVEN: "знам"}


def gap_minutes(earlier: Time, later: Time) -> int:
    """Minutes between two wall-clock times on the same day.

    Naive local time all the way down, per the project's rule: a bell rings at
    08:30 whether or not the clocks changed. A negative result means the bell
    rows are out of order, which the caller renders as nothing rather than as
    «−5 мин».
    """
    base = datetime(2000, 1, 1)
    return int(
        ((base + timedelta(hours=later.hour, minutes=later.minute))
         - (base + timedelta(hours=earlier.hour, minutes=earlier.minute))).total_seconds()
        // 60
    )


def _lesson_text(entry: TimetableEntry) -> str:
    parts = [entry.subject_name]
    if entry.room:
        parts.append(entry.room)
    if entry.teacher:
        parts.append(entry.teacher)
    return " · ".join(parts)


def slots(entries: list[TimetableEntry]) -> list[tuple[int, list[TimetableEntry]]]:
    """The day grouped into slots, ascending, each slot's rows parity-ordered.

    A slot is the unit the editor moves and deletes, so it is the unit the view
    is built from too — one numbered line per slot, however many weeks live
    inside it.
    """
    grouped: dict[int, list[TimetableEntry]] = {}
    for entry in entries:
        grouped.setdefault(entry.index, []).append(entry)
    for rows in grouped.values():
        rows.sort(key=lambda row: (row.parity is not WeekParity.ODD, row.parity.value))
    return sorted(grouped.items())


def slot_label(index: int, rows: list[TimetableEntry], width: int = 22) -> str:
    """One slot as a button caption: «2. Физика» or «2. Физика / Химия»."""
    if len(rows) == 1:
        name = rows[0].subject_name
    else:
        name = " / ".join(row.subject_name for row in rows)
    name = " ".join(name.split())
    if len(name) > width:
        name = name[: width - 1].rstrip() + "…"
    return f"{index}. {name}"


def render_day(
    weekday: int,
    entries: list[TimetableEntry],
    periods: dict[int, BellPeriod],
    *,
    canteen_after: int | None = None,
    show_breaks: bool = False,
    counts: dict[int, int] | None = None,
) -> str:
    """The editor's message for one weekday.

    ``counts`` draws the week strip under the heading, so that «во вторник
    вроде было шесть» is answered without stepping through the days — which is
    the thing the old editor made you do, one re-entry per day.
    """
    lines = [f"🧩 <b>Расписание · {WEEKDAY_FULL[weekday - 1]}</b>"]

    if counts:
        # WEEKDAYS_SHORT, not the full names cut to two characters: «четверг»
        # sliced is «че», and a strip of «по вт ср че пя су» is six labels
        # nobody in Russia writes.
        strip = " · ".join(
            f"{'<b>' if day == weekday else ''}{name} {counts.get(day, 0)}"
            f"{'</b>' if day == weekday else ''}"
            for day, name in enumerate(WEEKDAYS_SHORT[:6], start=1)
        )
        lines.append(strip)
    lines.append("")

    day = slots(entries)
    if not day:
        lines.append("<i>Уроков нет. «➕ Урок» — добавить первый.</i>")
        return "\n".join(lines)

    for position, (index, rows) in enumerate(day):
        period = periods.get(index)
        if len(rows) == 1:
            lines.append(f"<b>{index}.</b> {_lesson_text(rows[0])}")
        else:
            lines.append(f"<b>{index}.</b>")
            for row in rows:
                mark = PARITY_MARK.get(row.parity, "")
                lines.append(f"    <i>{mark}</i> {_lesson_text(row)}")
        if show_breaks and period is not None:
            lines.append(
                f"    <code>{period.starts_at:%H:%M}–{period.ends_at:%H:%M}</code>"
            )

        if not show_breaks:
            continue
        following = day[position + 1][0] if position + 1 < len(day) else None
        next_period = periods.get(following) if following is not None else None
        if period is None or next_period is None:
            continue
        minutes = gap_minutes(period.ends_at, next_period.starts_at)
        if minutes <= 0:
            continue
        word = plural(minutes, "минута", "минуты", "минут")
        if canteen_after == index:
            lines.append(f"    🍽 <b>столовая</b> · {minutes} {word}")
        else:
            lines.append(f"    ⏸ перемена · {minutes} {word}")

    if show_breaks and not periods:
        lines.append("")
        lines.append("<i>Звонков ещё нет — перемены посчитать не из чего.</i>")
    return "\n".join(lines)


def render_slot(index: int, rows: list[TimetableEntry], period: BellPeriod | None) -> str:
    """The card for one slot, opened by tapping its line."""
    lines = [f"<b>Урок {index}</b>"]
    if period is not None:
        lines.append(f"<code>{period.starts_at:%H:%M}–{period.ends_at:%H:%M}</code>")
    lines.append("")
    if len(rows) == 1:
        lines.append(_lesson_text(rows[0]))
        lines.append("")
        lines.append("<i>Каждую неделю.</i>")
    else:
        for row in rows:
            mark = PARITY_MARK.get(row.parity, "")
            lines.append(f"<b>{mark}</b> · {_lesson_text(row)}")
    return "\n".join(lines)


def render_canteen(canteen_after: int | None, periods: dict[int, BellPeriod]) -> str:
    lines = ["🍽 <b>Столовая</b>", ""]
    if canteen_after is None:
        lines.append("Не отмечена.")
    else:
        period = periods.get(canteen_after)
        following = periods.get(canteen_after + 1)
        when = ""
        if period is not None and following is not None:
            when = f" — <code>{period.ends_at:%H:%M}–{following.starts_at:%H:%M}</code>"
        lines.append(f"На перемене после <b>{canteen_after}-го урока</b>{when}.")
    lines.append("")
    lines.append(
        "<i>Отмечается на расписании звонков, а не на дате: это одна и та же "
        "перемена каждый день, и она уезжает вместе со звонками, когда день "
        "сокращают.</i>"
    )
    return "\n".join(lines)
