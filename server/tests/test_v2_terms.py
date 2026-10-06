"""``ClassService``'s terms: quarters or half-years, and their dates.

``GetTermScheme`` and ``ListTerms`` read what v1's ``GET /manage/terms``
answered, and never seed it: a class with no terms stored is answered the
conventional set, computed (``terms.spans``), where v1 wrote it on the read
(``docs/specs/2026-10-05-server-v2-design.md``, decision 10).
``UpdateTermScheme`` and ``UpdateTerm`` are v1's two ``PUT``s through the same
services, which store the year's set inside their own write. A term the year
cannot hold is ``TERM_BOUNDS_REFUSED`` in the service's own sentence, as the
proto says. A write's success is asked once per transport on fresh data, and
its refusals through ``both`` (Ruling 17).
"""

from __future__ import annotations

from sqlalchemy import select

from app.contract.lessons.v2.common_pb import Term, TermKind
from app.contract.lessons.v2.school_class_pb import (
    TermScheme,
    UpdateTermRequest,
    UpdateTermSchemeRequest,
)
from app.models import AuditEntry
from app.models import Term as TermRow
from app.models import TermKind as SchemeRow
from app.rpc.school_class import TERM_KIND_REFUSED
from app.schedule import default_term_bounds
from app.services.manage.terms import current_year


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _actions(session) -> list[str]:
    return list(await session.scalars(select(AuditEntry.action).order_by(AuditEntry.id)))


async def _terms(session, school_class) -> list[TermRow]:
    """The terms the database holds now, in order."""
    return list(
        await session.scalars(
            select(TermRow)
            .where(TermRow.class_id == school_class.id)
            .order_by(TermRow.year, TermRow.index)
            .execution_options(populate_existing=True)
        )
    )


async def _graded(session, school_class, grade: int) -> int:
    """Give the class a grade, and answer the school year in force for it."""
    school_class.grade = grade
    await session.commit()
    return current_year(school_class)


def _plain(terms) -> list[dict[str, object]]:
    """v2's terms as v1's ``TermOut`` writes them."""
    return [
        {
            "index": term.index,
            "kind": term.kind.name.lower(),
            "starts_on": term.starts_on,
            "ends_on": term.ends_on,
        }
        for term in terms
    ]


async def test_reading_the_terms_of_a_class_that_has_none_writes_nothing(
    v2, v2_tokens, session, school_class, statement_writes, unexpected_writes, last_seen_rule
) -> None:
    year = await _graded(session, school_class, 9)
    admin = v2_tokens["admin"]
    with statement_writes() as seen:
        scheme = await v2.both("ClassService/GetTermScheme", token=admin)
        listed = await v2.both("ClassService/ListTerms", token=admin)
    assert (scheme.status, listed.status) == (200, 200)
    # A write happened, the phone's last call, and nothing else did: no term
    # was seeded.
    assert seen
    assert unexpected_writes(seen) == []
    assert all(last_seen_rule.match(statement) for statement in seen), seen
    assert await _terms(session, school_class) == []
    assert scheme.message.term_scheme == TermScheme(kind=TermKind.QUARTER, year=year)
    assert listed.message.year == year
    # v1's read seeds the conventional set, and it is the set v2 computed.
    v1 = (await v2.http.get("/api/v1/manage/terms", headers=_auth(admin))).json()
    assert (v1["kind"], v1["year"]) == ("quarter", year)
    assert _plain(listed.message.terms) == v1["terms"]
    assert len(await _terms(session, school_class)) == 4


async def test_the_scheme_follows_the_grade_until_it_is_chosen(
    v2, v2_tokens, session, school_class
) -> None:
    year = await _graded(session, school_class, 10)
    admin = v2_tokens["admin"]
    scheme = await v2.both("ClassService/GetTermScheme", token=admin)
    listed = await v2.both("ClassService/ListTerms", token=admin)
    assert scheme.message.term_scheme == TermScheme(kind=TermKind.SEMESTER, year=year)
    assert [(term.index, term.kind) for term in listed.message.terms] == [
        (1, TermKind.SEMESTER),
        (2, TermKind.SEMESTER),
    ]


async def test_the_scheme_is_switched_and_the_year_reseeded_on_either_path(
    v2, v2_tokens, session, school_class
) -> None:
    year = await _graded(session, school_class, 9)
    admin = v2_tokens["admin"]
    halves = await v2.rest(
        "ClassService/UpdateTermScheme",
        UpdateTermSchemeRequest(term_scheme=TermScheme(kind=TermKind.SEMESTER)),
        token=admin,
    )
    assert halves.status == 200
    assert halves.message.term_scheme == TermScheme(kind=TermKind.SEMESTER, year=year)
    assert [term.index for term in halves.message.terms] == [1, 2]
    assert [row.kind for row in await _terms(session, school_class)] == [SchemeRow.SEMESTER] * 2
    quarters = await v2.connect(
        "ClassService/UpdateTermScheme",
        UpdateTermSchemeRequest(term_scheme=TermScheme(kind=TermKind.QUARTER)),
        token=admin,
    )
    assert quarters.status == 200
    assert [term.index for term in quarters.message.terms] == [1, 2, 3, 4]
    assert len(await _terms(session, school_class)) == 4
    assert await _actions(session) == ["class.term_kind", "class.term_kind"]


async def test_a_scheme_that_is_neither_is_refused_on_its_field(
    v2, v2_tokens, session, school_class
) -> None:
    answer = await v2.both(
        "ClassService/UpdateTermScheme",
        UpdateTermSchemeRequest(term_scheme=TermScheme()),
        token=v2_tokens["admin"],
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "INVALID_ARGUMENT",
        "VALIDATION_FAILED",
    )
    assert answer.violations == [("term_scheme.kind", TERM_KIND_REFUSED)]
    assert await _terms(session, school_class) == []
    assert await _actions(session) == []


async def test_a_term_is_moved_and_the_year_stored_in_the_same_write(
    v2, v2_tokens, session, school_class
) -> None:
    """A class with no terms stored: the move stores the year's set and moves
    the first term in one write, as v1's ``PUT /terms/{index}`` does."""
    year = await _graded(session, school_class, 9)
    admin = v2_tokens["admin"]
    moved = await v2.rest(
        "ClassService/UpdateTerm",
        UpdateTermRequest(term=Term(index=1, starts_on=f"{year}-09-01", ends_on=f"{year}-10-20")),
        token=admin,
    )
    assert moved.status == 200
    assert moved.message.year == year
    first = moved.message.terms[0]
    assert (first.starts_on, first.ends_on) == (f"{year}-09-01", f"{year}-10-20")
    stored = await _terms(session, school_class)
    assert len(stored) == 4
    assert stored[0].ends_on.isoformat() == f"{year}-10-20"
    again = await v2.connect(
        "ClassService/UpdateTerm",
        UpdateTermRequest(term=Term(index=1, starts_on=f"{year}-09-02", ends_on=f"{year}-10-21")),
        token=admin,
    )
    assert again.status == 200
    assert again.message.terms[0].starts_on == f"{year}-09-02"
    assert await _actions(session) == ["class.term", "class.term"]


async def test_a_term_the_year_cannot_hold_is_refused_in_the_service_s_words(
    v2, v2_tokens, session, school_class
) -> None:
    year = await _graded(session, school_class, 9)
    admin = v2_tokens["admin"]
    for index, starts, ends, words in (
        # Into the second quarter, which runs from 1 November.
        (1, f"{year}-09-01", f"{year}-12-01", "ересекается"),
        # Past 31 May, into the summer.
        (4, f"{year + 1}-04-01", f"{year + 1}-06-20", "учебный год"),
        # A term the year does not have.
        (9, f"{year}-09-01", f"{year}-09-10", "Такого периода нет"),
    ):
        v1 = await v2.http.put(
            f"/api/v1/manage/terms/{index}",
            json={"starts_on": starts, "ends_on": ends},
            headers=_auth(admin),
        )
        answer = await v2.both(
            "ClassService/UpdateTerm",
            UpdateTermRequest(term=Term(index=index, starts_on=starts, ends_on=ends)),
            token=admin,
        )
        assert (answer.status, answer.code, answer.reason) == (
            400,
            "FAILED_PRECONDITION",
            "TERM_BOUNDS_REFUSED",
        ), index
        assert answer.metadata == {}
        assert answer.error == v1.json()["detail"]
        assert words in answer.error
        assert v1.status_code == 422
    # No term was moved and no line was logged. What may stay is the year's
    # conventional set, which `terms.ensure` seeds in a savepoint before the
    # move is refused: on Postgres the refusal rolls the savepoint back with
    # the rest, while on SQLite, where every test runs, releasing a savepoint
    # that opened the transaction commits it (v1's refusal keeps it the same way).
    stored = [(row.starts_on, row.ends_on) for row in await _terms(session, school_class)]
    assert stored in ([], default_term_bounds(year, SchemeRow.QUARTER))
    assert await _actions(session) == []


async def test_a_term_s_dates_that_are_no_dates_are_refused_on_their_fields(
    v2, v2_tokens, session, school_class
) -> None:
    answer = await v2.both(
        "ClassService/UpdateTerm",
        UpdateTermRequest(term=Term(index=1, starts_on="1 сентября")),
        token=v2_tokens["admin"],
    )
    assert (answer.code, answer.reason) == ("INVALID_ARGUMENT", "VALIDATION_FAILED")
    assert [field for field, _ in answer.violations] == ["term.starts_on", "term.ends_on"]
    assert await _terms(session, school_class) == []
