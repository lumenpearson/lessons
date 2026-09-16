"""Четверти and полугодия: the year cut into the pieces a school marks in.

Two things live here, and they are one rule seen from two ends.

**The scheme follows the grade until somebody says otherwise.** 1 to 9 are
taught in four quarters and 10 to 11 in two semesters, because that is how the
leaving years are organised. `SchoolClass.term_kind` is null until it is
chosen, so "nobody decided, so quarters" stays distinguishable from "an admin
chose quarters for an eleventh year", which is a thing a school may do.

**The dates are the school's own.** The defaults in `app/schedule.py` are the
conventional ones and every one of them is meant to be edited: каникулы move, a
region shifts its spring break, a quarantine eats a week. So terms are rows
rather than a formula, seeded once per class per year and editable after.

Nothing here commits. The caller's transaction owns the change, so an audit
line lands with it or not at all — the rule the rest of ``app/services/``
follows.
"""

from __future__ import annotations

import re
from datetime import date as Date

from sqlalchemy import delete as sa_delete
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import SchoolClass, Term, TermKind
from app.schedule import (
    default_term_bounds,
    school_year_bounds,
    term_kind_for,
)

# The years a Russian school has. Anything outside it is a typo, and a class
# numbered 0 or 12 would resolve its term scheme from a comparison that happens
# to be true rather than from a decision.
MIN_GRADE = 1
MAX_GRADE = 11

# Long enough for "инж" or "ФМ", short enough that it is a suffix and not a
# second name; the column is String(8) and this is the rule behind it.
MAX_LETTER_LENGTH = 8


class TermError(ValueError):
    """A change a school year cannot hold. Carries the Russian explanation."""


def opening_year_of(on: Date) -> int:
    """The year the school year containing ``on`` opened in."""
    return school_year_bounds(on)[0].year


def scheme_of(school_class: SchoolClass) -> TermKind:
    """The scheme in force: the stored choice, else the one the grade implies."""
    return school_class.term_kind or term_kind_for(school_class.grade)


def compose_name(grade: int | None, letter: str | None, fallback: str) -> str:
    """The display name a grade and a letter make, e.g. 9 + "А" -> "9А".

    Falls back to whatever the class was already called when there is no grade
    to compose from — every class created before the number existed has a name
    and nothing else, and inventing "9" out of "9А" by pattern is the guess this
    column exists to avoid.
    """
    if grade is None:
        return fallback
    return f"{grade}{(letter or '').strip()}"


def validate_grade(grade: int) -> int:
    if not MIN_GRADE <= grade <= MAX_GRADE:
        raise TermError(f"Класс — число от {MIN_GRADE} до {MAX_GRADE}.")
    return grade


def normalise_letter(raw: str | None) -> str | None:
    """`None` for "no letter", otherwise the trimmed one."""
    letter = (raw or "").strip()
    if not letter or letter == "-":
        return None
    if len(letter) > MAX_LETTER_LENGTH:
        raise TermError(f"Буква класса — не длиннее {MAX_LETTER_LENGTH} символов.")
    return letter


async def read(session: AsyncSession, class_id: int, year: int) -> list[Term]:
    """This class's terms for one school year, in order."""
    rows = await session.scalars(
        select(Term)
        .where(Term.class_id == class_id, Term.year == year)
        .order_by(Term.index)
    )
    return list(rows)


async def ensure(
    session: AsyncSession,
    school_class: SchoolClass,
    year: int,
    *,
    kind: TermKind | None = None,
    _retry: bool = True,
) -> list[Term]:
    """This class's terms for ``year``, seeding the conventional set if absent.

    Idempotent, and deliberately so: it is called from the read path as well as
    from the editor, because a class created before this feature existed has no
    terms at all and the first person to look at its calendar should see the
    conventional ones rather than an empty screen.

    **Seeds, never replaces.** A set that already exists is returned as it
    stands, whatever scheme it is in, and only an explicit ``kind`` — which
    reaches here from :func:`set_scheme` and nowhere else — may throw it away.
    Without that rule the replacement below is reachable from a read: a 9-й
    класс keeps ``term_kind`` NULL, so :func:`scheme_of` answers from the
    grade, and moving that class up to 10 makes the *next bundle request from
    any phone* delete four четверти whose dates an admin had spent an evening
    correcting, and write two conventional полугодия over them. Nothing would
    have asked, and the audit log would not carry it either.

    **Idempotent is not the same as race-safe**, and being called from a read
    is what makes the difference matter: every phone in a brand-new class polls
    ``/bundle`` on the same timer, so two requests both finding the year unseeded
    is the ordinary case rather than a rare one. Both then insert index 1,
    ``uq_term_slot`` refuses the second, and on Postgres that IntegrityError
    poisons the whole transaction — a 500 from a read, on exactly the first
    request this seeding exists for. So the insert goes in a savepoint, the way
    :func:`app.services.subjects._adopt` does, and **the loser concedes**: it
    rolls back its four rows and returns the winner's, which are the same four
    dates. Both callers answer a correct bundle; neither writes twice.
    """
    existing = await read(session, school_class.id, year)
    wanted = kind or scheme_of(school_class)
    if existing and (kind is None or existing[0].kind is wanted):
        return existing

    # A scheme change replaces the set rather than editing it: four quarters
    # and two semesters do not map onto each other, and keeping the leftovers
    # would leave a third quarter inside a year that has two halves.
    if existing:
        await session.execute(
            sa_delete(Term).where(Term.class_id == school_class.id, Term.year == year)
        )

    seeded = [
        Term(
            class_id=school_class.id,
            year=year,
            kind=wanted,
            index=index,
            starts_on=starts,
            ends_on=ends,
        )
        for index, (starts, ends) in enumerate(default_term_bounds(year, wanted), start=1)
    ]
    try:
        async with session.begin_nested():
            session.add_all(seeded)
            await session.flush()
    except IntegrityError:
        # Somebody seeded this same year between our read and our insert. The
        # savepoint took our four rows back with it, so the caller's
        # transaction is still usable and still owns whatever else is in it.
        conceded = await read(session, school_class.id, year)
        if kind is None or not _retry or (conceded and conceded[0].kind is wanted):
            return conceded
        # An explicit scheme change is the one caller that must not quietly
        # accept the other set: «10 класс, полугодия» would answer «сделано»
        # and leave four четверти on the screen. The rival's rows are now the
        # `existing` the branch above replaces, so one more pass does it.
        return await ensure(session, school_class, year, kind=kind, _retry=False)
    return seeded


async def set_scheme(
    session: AsyncSession,
    school_class: SchoolClass,
    kind: TermKind,
    year: int,
) -> list[Term]:
    """Switch this class between quarters and semesters, reseeding the year."""
    school_class.term_kind = kind
    return await ensure(session, school_class, year, kind=kind)


async def set_bounds(
    session: AsyncSession,
    school_class: SchoolClass,
    year: int,
    index: int,
    starts_on: Date,
    ends_on: Date,
) -> Term:
    """Move one term's edges, refusing anything the year cannot hold.

    The checks are the ones a wrong date actually produces: a term that ends
    before it starts renders as a negative length; one that reaches outside the
    school year claims lessons in July; two that overlap make "which четверть
    is this" ambiguous on a date that is in both. Each is refused with the
    sentence a person can act on rather than silently clamped, because a
    silently clamped date is a date the admin thinks they set.
    """
    terms = await read(session, school_class.id, year)
    target = next((t for t in terms if t.index == index), None)
    if target is None:
        raise TermError("Такого периода нет.")

    if ends_on < starts_on:
        raise TermError("Конец периода раньше его начала.")

    year_start, year_end = school_year_bounds(Date(year, 9, 15))
    if starts_on < year_start or ends_on > year_end:
        raise TermError(
            f"Период должен укладываться в учебный год: "
            f"{year_start:%d.%m.%Y} — {year_end:%d.%m.%Y}."
        )

    for other in terms:
        if other.index == index:
            continue
        if starts_on <= other.ends_on and other.starts_on <= ends_on:
            raise TermError(
                f"Пересекается с периодом {other.index} "
                f"({other.starts_on:%d.%m} — {other.ends_on:%d.%m})."
            )

    target.starts_on = starts_on
    target.ends_on = ends_on
    await session.flush()
    return target


#: «01.09.2026 - 31.10.2026», and every separator a person actually types
#: between two dates. The year is required: «01.09 - 31.10» is ambiguous across
#: the new year, which is precisely where the second quarter ends.
_SPAN = re.compile(
    r"^\s*(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{4})"
    r"\s*(?:-|—|–|по|\s)\s*"
    r"(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{4})\s*$"
)


def parse_span(raw: str | None) -> tuple[Date, Date] | None:
    """Two dates typed on one line, or `None` when it is not two dates.

    `None` rather than an exception: "не разобрал" and "эти даты не подходят"
    are different answers to the person typing, and only the second one is
    worth naming a rule for.
    """
    match = _SPAN.match(raw or "")
    if match is None:
        return None
    d1, m1, y1, d2, m2, y2 = (int(part) for part in match.groups())
    try:
        return Date(y1, m1, d1), Date(y2, m2, d2)
    except ValueError:
        # 31 February parses as six numbers and is still not a date.
        return None


def term_at(terms: list[Term], on: Date) -> Term | None:
    """Which term ``on`` falls in, or `None` — каникулы are a real answer."""
    for term in terms:
        if term.starts_on <= on <= term.ends_on:
            return term
    return None
