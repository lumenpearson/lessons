"""The dates a Russian school year is punctuated by, and which of them stop lessons.

Two different things live here and they are kept apart on purpose.

**Statutory non-working days** (:data:`PUBLIC`) are the ones Article 112 of the
Labour Code names. Nobody teaches on them, so the resolver drops the weekly
template for that date exactly the way it drops it out of season. There are
fourteen and they do not move.

**Observances** (:data:`OBSERVANCES`) are the school's own calendar: «День
знаний», «День учителя», «Международный день родного языка». Lessons happen on
all of them. They are here because the calendar is where somebody looks to find
out what today is, and a date that is only a badge is still worth a badge.

**What is deliberately not here: the yearly transfers.** Russia moves its days
off around each December by government decree — 2026's are not derivable from
2025's, and a holiday landing on a Saturday shifts to a weekday by that decree
rather than by a rule. Inventing them would be worse than not having them,
because a wrong non-working day removes a real day of lessons from somebody's
timetable and looks exactly like a correct one. The dates below are the fixed
statutory ones; a transfer is marked by hand, in the bot, like any other day.

Each entry carries a stable ``code`` as well as its Russian title, so a client
that wants to write the name in its own language can, and one that does not
shows what the server sent. The titles here are Russian because the product is,
and because everything else the server puts on a screen — a subject, a note, a
room — is Russian too.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date as Date


@dataclass(frozen=True, slots=True)
class Holiday:
    """One named date. ``stops_lessons`` is the only behavioural half."""

    code: str
    title: str
    #: True for a statutory non-working day: the template does not apply.
    stops_lessons: bool


#: ``(month, day)`` → the statutory non-working days.
#:
#: The January run is eight separate entries rather than a range because that
#: is how the Labour Code words it, and because a reader checking this list
#: against the law should be able to do it line by line.
PUBLIC: dict[tuple[int, int], Holiday] = {
    (1, 1): Holiday("ru_new_year", "Новогодние каникулы", True),
    (1, 2): Holiday("ru_new_year", "Новогодние каникулы", True),
    (1, 3): Holiday("ru_new_year", "Новогодние каникулы", True),
    (1, 4): Holiday("ru_new_year", "Новогодние каникулы", True),
    (1, 5): Holiday("ru_new_year", "Новогодние каникулы", True),
    (1, 6): Holiday("ru_new_year", "Новогодние каникулы", True),
    (1, 7): Holiday("ru_christmas", "Рождество Христово", True),
    (1, 8): Holiday("ru_new_year", "Новогодние каникулы", True),
    (2, 23): Holiday("ru_defender", "День защитника Отечества", True),
    (3, 8): Holiday("ru_womens_day", "Международный женский день", True),
    (5, 1): Holiday("ru_spring_labour", "Праздник Весны и Труда", True),
    (5, 9): Holiday("ru_victory", "День Победы", True),
    (6, 12): Holiday("ru_russia_day", "День России", True),
    (11, 4): Holiday("ru_unity", "День народного единства", True),
}

#: ``(month, day)`` → dates a school marks but still teaches on.
#:
#: Chosen for what a school year actually observes: the ones about learning,
#: language, teachers and children. A longer list would be a calendar of
#: everything, which is a different product — and each of these has to earn a
#: line on a day card that already carries lessons and homework.
OBSERVANCES: dict[tuple[int, int], Holiday] = {
    (9, 1): Holiday("ru_knowledge_day", "День знаний", False),
    (9, 8): Holiday("un_literacy_day", "Международный день распространения грамотности", False),
    (9, 26): Holiday("eu_languages_day", "Европейский день языков", False),
    (10, 5): Holiday("un_teachers_day", "День учителя", False),
    (11, 16): Holiday("un_tolerance_day", "Международный день толерантности", False),
    (11, 17): Holiday("un_students_day", "Международный день студентов", False),
    (11, 20): Holiday("un_childrens_day", "Всемирный день ребёнка", False),
    (12, 3): Holiday("un_disabilities_day", "Международный день инвалидов", False),
    (12, 10): Holiday("un_human_rights_day", "День прав человека", False),
    (12, 12): Holiday("ru_constitution_day", "День Конституции России", False),
    (1, 24): Holiday("un_education_day", "Международный день образования", False),
    (1, 25): Holiday("ru_students_day", "День российского студенчества", False),
    (1, 27): Holiday(
        "un_holocaust_remembrance", "Международный день памяти жертв Холокоста", False
    ),
    (2, 11): Holiday(
        "un_women_in_science_day", "Международный день женщин и девочек в науке", False
    ),
    (2, 21): Holiday("un_mother_language_day", "Международный день родного языка", False),
    (3, 14): Holiday("world_pi_day", "Международный день числа «пи»", False),
    (4, 2): Holiday("world_childrens_book_day", "Международный день детской книги", False),
    (4, 12): Holiday("ru_cosmonautics_day", "День космонавтики", False),
    (4, 22): Holiday("un_earth_day", "Международный день Земли", False),
    (4, 23): Holiday("un_book_day", "Всемирный день книги и авторского права", False),
    (5, 18): Holiday("un_museum_day", "Международный день музеев", False),
    (5, 24): Holiday("ru_slavic_writing_day", "День славянской письменности и культуры", False),
    (6, 1): Holiday("un_child_protection_day", "День защиты детей", False),
    (6, 5): Holiday("un_environment_day", "Всемирный день окружающей среды", False),
    (6, 6): Holiday("ru_russian_language_day", "День русского языка", False),
}


def holiday_on(day: Date) -> Holiday | None:
    """The named date ``day`` is, or ``None``.

    A statutory day wins over an observance where both fall on one date, which
    today is nowhere — but the two tables are edited separately and by
    different reasoning, and «which of the two applies» is not a question to
    leave to whichever dictionary happens to be looked at first.
    """
    key = (day.month, day.day)
    return PUBLIC.get(key) or OBSERVANCES.get(key)


def stops_lessons(day: Date) -> bool:
    """Whether the weekly template must not be applied to ``day``."""
    holiday = holiday_on(day)
    return holiday is not None and holiday.stops_lessons
