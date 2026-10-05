"""The window's rules as ``services/`` holds them: terms that write nothing, the
year's bounds, the access a device is told, and the tag.

v1's ``/join`` and ``/bundle`` tests are the proof that the moves kept v1's
answers; this holds what v2 adds on top of them
(``docs/specs/2026-10-05-server-v2-design.md``, decisions 2 and 10).
"""

from __future__ import annotations

import hashlib
from datetime import date

import pytest
from sqlalchemy import func, select

from app.models import DeviceToken, JoinAttempt, Role, SchoolClass, Term, TermKind
from app.schedule import school_year_end, school_year_start
from app.services import clock, join, window
from app.services import terms as terms_service
from app.services.linking import Access


async def test_a_year_with_no_terms_is_answered_the_conventional_set_and_writes_none(
    session, school_class
) -> None:
    spans = await terms_service.spans(session, school_class, 2026)
    assert [(s.index, s.kind, s.starts_on, s.ends_on) for s in spans] == [
        (1, TermKind.QUARTER, date(2026, 9, 1), date(2026, 10, 31)),
        (2, TermKind.QUARTER, date(2026, 11, 1), date(2026, 12, 31)),
        (3, TermKind.QUARTER, date(2027, 1, 1), date(2027, 3, 22)),
        (4, TermKind.QUARTER, date(2027, 3, 23), date(2027, 5, 31)),
    ]
    assert not session.new
    await session.commit()
    assert await session.scalar(select(func.count()).select_from(Term)) == 0


async def test_a_senior_class_is_answered_half_years(session, school_class) -> None:
    klass = await session.get(SchoolClass, school_class.id)
    klass.grade = 11
    spans = await terms_service.spans(session, klass, 2026)
    assert [(s.kind, s.ends_on) for s in spans] == [
        (TermKind.SEMESTER, date(2026, 12, 31)),
        (TermKind.SEMESTER, date(2027, 5, 31)),
    ]


async def test_stored_terms_are_answered_as_they_are(session, school_class) -> None:
    stored = await terms_service.ensure(session, school_class, 2026)
    stored[0].ends_on = date(2026, 10, 26)
    await session.commit()
    spans = await terms_service.spans(session, school_class, 2026)
    assert spans[0].ends_on == date(2026, 10, 26)


def test_the_first_and_last_years_lie_inside_the_bounds_and_their_neighbours_do_not() -> None:
    first, last = window.FIRST_YEAR, window.LAST_YEAR
    assert clock.in_bounds(school_year_start(first), school_year_end(last))
    assert not clock.in_bounds(school_year_end(last + 1))
    assert not clock.in_bounds(date(first - 1, 12, 31))


@pytest.mark.parametrize("year", [0, -1, 1999, 2099, 10000])
async def test_a_year_out_of_bounds_is_refused_before_a_date_is_built(
    session, school_class, year
) -> None:
    with pytest.raises(window.YearOutOfBounds):
        await window.for_year(session, school_class, year, access=Access(linked=False, role=None))


@pytest.mark.parametrize(
    ("role", "can_edit"),
    [
        (None, False),
        (Role.VIEWER, False),
        (Role.EDITOR, True),
        (Role.ADMIN, True),
        (Role.OWNER, True),
    ],
)
def test_editing_is_editor_or_above(role, can_edit) -> None:
    assert Access(linked=role is not None, role=role).can_edit is can_edit


def test_access_of_an_unlinked_device_says_so() -> None:
    assert Access.of(DeviceToken(token_hash="x", class_id=1), None) == Access(
        linked=False, role=None
    )


@pytest.mark.parametrize(
    ("sent", "matches"),
    [
        ('"abc"', True),
        ('W/"abc"', True),
        ('"other", "abc"', True),
        ("*", True),
        ('"other"', False),
        (None, False),
        ("", False),
    ],
)
def test_a_tag_matches_as_v1_s_if_none_match_did(sent, matches) -> None:
    assert window.etag_matches(sent, '"abc"') is matches


def test_a_tag_is_a_quoted_sha_256_of_the_text() -> None:
    text = '{"days":[]}'
    assert window.strong_etag(text) == '"' + hashlib.sha256(text.encode()).hexdigest() + '"'


async def test_the_join_flow_counts_a_wrong_code_and_forgives_a_right_one(
    session, school_class
) -> None:
    with pytest.raises(join.JoinCodeUnknown):
        await join.join(session, code="NOSUCH99", device_name=None, client_key="k" * 64)
    assert await session.scalar(select(func.count()).select_from(JoinAttempt)) == 1
    joined = await join.join(session, code="test42", device_name="Pixel", client_key="k" * 64)
    assert joined.school_class.id == school_class.id
    assert await session.scalar(select(func.count()).select_from(JoinAttempt)) == 1
