"""What the bot says about the class rather than on one screen, and the door to
the wording both shells share.

Each screen's renderer lives beside this file — ``week_render``,
``homework_render``, ``tasks_render``, ``access_render``, ``reminders_render``,
as ``diary_render`` and ``editor_render`` already did — kept apart from the
handlers so the wording can be changed without touching any control flow.
What is left here is the sentence under the menu that says what a role may do,
and the warning both bells editors print.

The calendar names, the message budget, ``plural`` and the day card are not
here: the digests in ``services/reminders`` and the paste grammar in
``services/timetable_io`` need them too, and a service may not import the bot
(#205), so they live in :mod:`app.wording`. Every one of them is imported back
below, so ``from app.bot.render import …`` in a handler reads the same object it
always did. Nothing left in this file draws with them, so each is imported as
``X as X``, which is how the linter tells a re-export from an unused import.
"""

from __future__ import annotations

from app.models import Role
from app.wording import DAY_HOMEWORK_TEXT_MAX as DAY_HOMEWORK_TEXT_MAX
from app.wording import DAY_KIND_LABELS as DAY_KIND_LABELS
from app.wording import EVENT_ICONS as EVENT_ICONS
from app.wording import MESSAGE_LIMIT as MESSAGE_LIMIT
from app.wording import MONTHS_GENITIVE as MONTHS_GENITIVE
from app.wording import MONTHS_NOMINATIVE as MONTHS_NOMINATIVE
from app.wording import WEEKDAYS as WEEKDAYS
from app.wording import WEEKDAYS_SHORT as WEEKDAYS_SHORT
from app.wording import clamp as clamp
from app.wording import cut as cut
from app.wording import human_date as human_date
from app.wording import more_line as more_line
from app.wording import plural as plural
from app.wording import relative_day_name as relative_day_name
from app.wording import render_day as render_day


def render_role_help(role: Role) -> str:
    return {
        Role.VIEWER: "Вы можете смотреть расписание и домашнее задание.",
        Role.EDITOR: "Вы можете добавлять домашнее задание, замены и события.",
        Role.ADMIN: (
            "Вы можете редактировать расписание и звонки, а также выдавать "
            "доступ редакторам."
        ),
        Role.OWNER: "У вас полный доступ, включая назначение администраторов.",
    }[role]


def silenced_lessons(silenced: list[tuple[int, int]]) -> str:
    """What to tell an admin whose new bells left lessons ringing nowhere.

    One sentence in one place, because there are two bells editors and they
    used to disagree about whether to say anything at all: «🧩 Расписание» →
    «🔔 Звонки» wrote the rows by hand and reported nothing, while «⚙️ Класс»
    → «🔔 Звонки» went through `structure.write_bell_periods` and warned. The
    rule that a lesson needs a bell of its own number is the service's; this
    is the only sentence that says so, and both editors now print it.

    The numbers are named because they are what somebody has to go and fix,
    and the count is of rows — one number under two weekdays is two lessons
    nobody will see.
    """
    numbers = ", ".join(str(index) for index in sorted({index for _day, index in silenced}))
    return (
        f"⚠️ Уроки № {numbers} в расписании класса больше не звонят "
        f"({len(silenced)} шт.) — они останутся в базе, но их никто не увидит. "
        "Добавьте звонки этих номеров или уберите уроки."
    )
