"""«🏖 Особые дни»: the list, and what marking a period says back."""

from __future__ import annotations

from datetime import date as Date
from html import escape

from app.bot.render import DAY_KIND_LABELS, clamp, more_line, plural
from app.models import DayKind, DayOverride

#: Rows listed before «… и ещё N». Twenty lines is about a phone screen and a
#: half; past that the reader scrolls instead of reading. «🏖 Особые дни» draws
#: this many, and ``manage_keyboards.holidays`` builds this many 🗑 buttons under
#: them.
LIST_MAX = 20


#: The «обычный день» kind never has a row of its own - that is what deleting
#: the override means - so it needs no label here.
KIND_LABELS = dict(DAY_KIND_LABELS)


def render_holidays(
    overrides: list[DayOverride],
    schedules: dict[int, str],
    today: Date,
    only: DayKind | None = None,
) -> str:
    lines = ["<b>🏖 Особые дни</b>", ""]
    if not overrides:
        # Which kind of empty, because the two mean different things to do
        # next: «нет вовсе» is a calendar to fill in, «нет таких» is a filter
        # to take off. Telling somebody who filtered to «дистанционно» that
        # there are no special days at all would be answering a question they
        # did not ask.
        if only is None:
            lines.append("<i>Впереди особых дней нет.</i>")
        else:
            label = KIND_LABELS.get(only, "такого вида")
            lines.append(f"<i>Впереди нет дней: {escape(label)}.</i>")
            lines.append("")
            lines.append("<i>«Все» покажет остальные.</i>")
        return "\n".join(lines)

    for override in overrides[:LIST_MAX]:
        label = KIND_LABELS.get(override.kind, "День")
        row = f"<b>{override.date:%d.%m}</b> · {label}"
        if override.kind is DayKind.SHORTENED and override.bell_schedule_id:
            name = schedules.get(override.bell_schedule_id)
            if name:
                row += f" · 🔔 {escape(name)}"
        if override.note:
            row += f" · {escape(override.note)}"
        if override.date == today:
            row += " · сегодня"
        lines.append(row)
    lines.extend(more_line(len(overrides), LIST_MAX))
    return clamp(lines)


#: The word the confirmation uses, per kind. Read from `DAY_KIND_LABELS` would
#: be wrong: those carry an emoji and read as a heading («🏖 Каникулы /
#: выходной»), and this is the middle of a sentence.
_PERIOD_WORDS: dict[DayKind, str] = {
    DayKind.HOLIDAY: "Каникулы",
    DayKind.REMOTE: "Дистанционное обучение",
    DayKind.SELF_STUDY: "Самоподготовка",
    DayKind.DAY_OFF: "Отгул",
}


def render_period_result(first: Date, last: Date, created: int, kind: DayKind) -> str:
    word = _PERIOD_WORDS.get(kind, "Отмечено")
    return (
        f"✅ {word} с <b>{first:%d.%m}</b> по <b>{last:%d.%m}</b>: "
        f"отмечено {plural(created, 'день', 'дня', 'дней')}."
    )
