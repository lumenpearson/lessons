"""«🗓 Четверти» and ``/manage/terms``: quarters and half-years, edited.

The rules - which dates a term may span, what overlaps - are
:mod:`app.services.terms`. This is the year both shells edit and the line each
edit leaves in the log.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.models import SchoolClass, Term, TermKind
from app.services import audit
from app.services import terms as terms_service


def current_year(school_class: SchoolClass) -> int:
    """The school year in force *for this class*, in the class's own zone.

    Not the server's: a class in Kamchatka turns the page on 1 September nine
    hours before a class in Kaliningrad, and the server is in neither.
    """
    return terms_service.opening_year_of(datetime.now(school_class.tz).date())


async def current(session: AsyncSession, school_class: SchoolClass) -> tuple[int, list[Term]]:
    """This year's terms, seeding the conventional set if the class has none.

    The seeding writes, and the caller commits it.
    """
    year = current_year(school_class)
    return year, await terms_service.ensure(session, school_class, year)


async def change_scheme(
    session: AsyncSession, school_class: SchoolClass, actor_id: int | None, wanted: TermKind
) -> list[Term]:
    """Switch between quarters and half-years, reseeding the year.

    A replacement rather than an edit: four quarters and two halves do not map
    onto each other.
    """
    rows = await terms_service.set_scheme(session, school_class, wanted, current_year(school_class))
    await audit.record(
        session,
        school_class.id,
        actor_id,
        "class.term_kind",
        f"схема: {'полугодия' if wanted is TermKind.SEMESTER else 'четверти'}",
    )
    return rows


async def move_term(
    session: AsyncSession,
    school_class: SchoolClass,
    actor_id: int | None,
    index: int,
    starts_on: Date,
    ends_on: Date,
) -> int:
    """Move one term's edges within this year.

    @return the year it was moved in.
    @raises terms_service.TermError with the rule, in Russian, when the dates
        do not fit the year or overlap a neighbour. Nothing is logged then.
    """
    year, _ = await current(session, school_class)
    await terms_service.set_bounds(session, school_class, year, index, starts_on, ends_on)
    await audit.record(
        session,
        school_class.id,
        actor_id,
        "class.term",
        f"период {index}: {starts_on:%d.%m.%Y} — {ends_on:%d.%m.%Y}",
    )
    return year
