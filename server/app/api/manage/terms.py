"""``/terms``: the class's quarters or half-years, as «🗓 Четверти» edits them.

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring.
"""

from __future__ import annotations

from datetime import datetime

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class
from app.api.manage._common import Actor, admin_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import SchoolClass, TermKind
from app.schemas import TermBoundsIn, TermOut, TermSchemeIn, TermsOut
from app.services import audit
from app.services import terms as terms_service

router = APIRouter(route_class=DishkaAnnotatedRoute)


# --------------------------------------------------------------------------
# Quarters and half-years
#
# The same service the bot's editor calls, for the same reason the rest of
# this module does it that way: «пересекается с периодом 2» decided twice
# disagrees within a month.
# --------------------------------------------------------------------------


def _terms_out(school_class: SchoolClass, year: int, rows) -> TermsOut:
    return TermsOut(
        kind=terms_service.scheme_of(school_class).value,
        year=year,
        terms=[
            TermOut(
                index=term.index,
                kind=term.kind.value,
                starts_on=term.starts_on,
                ends_on=term.ends_on,
            )
            for term in rows
        ],
    )


def _term_year(school_class: SchoolClass) -> int:
    """The school year in force for this class, in the class's own zone."""
    return terms_service.opening_year_of(datetime.now(school_class.tz).date())


@router.get("/terms", response_model=TermsOut)
async def terms_list(
    _: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> TermsOut:
    """This class's terms, seeding the conventional set if it has none."""
    year = _term_year(school_class)
    rows = await terms_service.ensure(session, school_class, year)
    await session.commit()
    return _terms_out(school_class, year, rows)


@router.put("/terms/scheme", response_model=TermsOut)
async def terms_set_scheme(
    payload: TermSchemeIn,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> TermsOut:
    """Switch between quarters and half-years, reseeding the year.

    A replacement rather than an edit: four quarters and two halves do not map
    onto each other, and a leftover third quarter inside a year that has two is
    not a state worth keeping.
    """
    year = _term_year(school_class)
    wanted = TermKind(payload.kind)
    rows = await terms_service.set_scheme(session, school_class, wanted, year)
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "class.term_kind",
        f"схема: {'полугодия' if wanted is TermKind.SEMESTER else 'четверти'}",
    )
    await session.commit()
    return _terms_out(school_class, year, rows)


@router.put("/terms/{index}", response_model=TermsOut)
async def terms_set_bounds(
    index: int,
    payload: TermBoundsIn,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> TermsOut:
    """Move one term's edges.

    422 with the service's own Russian sentence rather than a field-shaped
    error: what is wrong is the relationship between this term and the year or
    its neighbours, and «конец периода раньше его начала» is the thing worth
    putting on the screen.
    """
    year = _term_year(school_class)
    await terms_service.ensure(session, school_class, year)
    try:
        await terms_service.set_bounds(
            session, school_class, year, index, payload.starts_on, payload.ends_on
        )
    except terms_service.TermError as error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(error)
        ) from error

    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "class.term",
        f"период {index}: {payload.starts_on:%d.%m.%Y} — {payload.ends_on:%d.%m.%Y}",
    )
    await session.commit()
    rows = await terms_service.read(session, school_class.id, year)
    return _terms_out(school_class, year, rows)
