"""``SubstitutionService``'s reads: ``ListSubstitutions`` and ``GetSubstitution``.

v1 had no list of substitutions: a phone read them inside ``/bundle``'s days.
v2 lists the rows themselves, through ``services/substitutions.py``, in a
window read as ``ListHomework`` reads one — today and three weeks on when
unset, sixty-two days at most (``rpc/dates.py``) — and refused in the same
words. Both reads are an editor's, as the contract has them; a substitution of
another class is not found, and a read writes nothing
(``docs/specs/2026-10-05-server-v2-design.md``, decision 10).
"""

from __future__ import annotations

from datetime import date, datetime

from app.contract.lessons.v2.substitution_pb import (
    GetSubstitutionRequest,
    ListSubstitutionsRequest,
    SubstitutionAction,
)
from app.models import LessonOverride, OverrideAction, SchoolClass
from app.rpc.errors import WINDOW_BACKWARDS
from app.rpc.substitution import UNKNOWN_SUBSTITUTION
from app.services import clock

LIST = "SubstitutionService/ListSubstitutions"
GET = "SubstitutionService/GetSubstitution"
MONDAY = date(2026, 9, 14)


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _pin_clock(monkeypatch) -> None:
    """Monday 7 September, 10:00 in the class's zone, for v1 and v2 alike."""
    monkeypatch.setattr(
        clock, "now", lambda school_class: datetime(2026, 9, 7, 10, 0, tzinfo=school_class.tz)
    )


async def _substitution(session, class_id: int, day: date, index: int, **fields):
    row = LessonOverride(
        class_id=class_id,
        date=day,
        index=index,
        action=fields.pop("action", OverrideAction.REPLACE),
        **fields,
    )
    session.add(row)
    await session.commit()
    return row


async def test_the_substitutions_in_a_window_come_by_date_and_number_from_this_class_only(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    replaced = await _substitution(
        session, school_class.id, MONDAY, 3, subject_name="Химия", room="118", teacher="Иванов"
    )
    await _substitution(
        session, school_class.id, MONDAY, 1, action=OverrideAction.CANCEL, note="болеет"
    )
    await _substitution(session, school_class.id, date(2026, 9, 7), 2, room="305")
    await _substitution(session, school_class.id, date(2026, 10, 31), 1, subject_name="Химия")
    await _substitution(session, other.id, MONDAY, 2, subject_name="Чужое")
    editor = v2_tokens["editor"]

    rows = (await v2.both(LIST, token=editor)).message.substitutions
    # Today and three weeks on, by date and then lesson number.
    assert [(row.date, row.index, row.action) for row in rows] == [
        ("2026-09-07", 2, SubstitutionAction.REPLACE),
        ("2026-09-14", 1, SubstitutionAction.CANCEL),
        ("2026-09-14", 3, SubstitutionAction.REPLACE),
    ]
    assert (rows[2].id, rows[2].subject, rows[2].room, rows[2].teacher) == (
        replaced.id,
        "Химия",
        "118",
        "Иванов",
    )
    assert (rows[1].note, rows[1].has_field("subject")) == ("болеет", False)
    # The same rows v1's bundle draws on that Monday.
    v1 = await v2.http.get(
        "/api/v1/bundle", params={"start": "2026-09-14", "days": 1}, headers=_auth(editor)
    )
    lessons = v1.json()["days"][0]["lessons"]
    assert [(lesson["index"], lesson["is_cancelled"]) for lesson in lessons][0] == (1, True)
    assert (lessons[2]["subject"], lessons[2]["is_replaced"]) == ("Химия", True)
    later = await v2.both(
        LIST,
        ListSubstitutionsRequest(start_date="2026-10-01", end_date="2026-10-31"),
        token=editor,
    )
    assert [row.date for row in later.message.substitutions] == ["2026-10-31"]


async def test_a_window_is_refused_as_the_homework_s_is(v2, v2_tokens) -> None:
    editor = v2_tokens["editor"]
    for sent, field, sentence in (
        (
            ListSubstitutionsRequest(start_date="1999-01-01"),
            "start_date",
            clock.DATES_OUT_OF_BOUNDS,
        ),
        (
            ListSubstitutionsRequest(start_date="2026-09-10", end_date="2026-09-01"),
            "end_date",
            WINDOW_BACKWARDS,
        ),
        (
            ListSubstitutionsRequest(start_date="2026-09-01", end_date="2026-12-31"),
            "end_date",
            clock.WINDOW_TOO_WIDE,
        ),
    ):
        answer = await v2.both(LIST, sent, token=editor)
        assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED"), field
        assert (answer.error, answer.violations) == (sentence, [(field, sentence)])
    unparsed = await v2.both(LIST, ListSubstitutionsRequest(end_date="2026-13-01"), token=editor)
    assert [name for name, _ in unparsed.violations] == ["end_date"]


async def test_one_substitution_is_its_row_and_another_class_s_is_not_found(
    v2, v2_tokens, session, school_class
) -> None:
    own = await _substitution(session, school_class.id, MONDAY, 2, subject_name="Химия")
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    foreign = await _substitution(session, other.id, MONDAY, 2, subject_name="Чужое")
    editor = v2_tokens["editor"]
    found = await v2.both(GET, GetSubstitutionRequest(substitution_id=own.id), token=editor)
    substitution = found.message.substitution
    assert (substitution.id, substitution.date, substitution.index, substitution.subject) == (
        own.id,
        "2026-09-14",
        2,
        "Химия",
    )
    for substitution_id in (foreign.id, 999_999):
        refused = await v2.both(
            GET, GetSubstitutionRequest(substitution_id=substitution_id), token=editor
        )
        assert (refused.status, refused.code, refused.reason, refused.metadata) == (
            404,
            "NOT_FOUND",
            "RESOURCE_NOT_FOUND",
            {"resource": "substitution"},
        )
        assert refused.error == UNKNOWN_SUBSTITUTION


async def test_reading_substitutions_writes_nothing_but_the_last_seen(
    v2, v2_tokens, session, school_class, statement_writes, unexpected_writes, last_seen_rule
) -> None:
    own = await _substitution(session, school_class.id, MONDAY, 2, subject_name="Химия")
    editor = v2_tokens["editor"]
    with statement_writes() as seen:
        listed = await v2.both(LIST, token=editor)
        one = await v2.both(GET, GetSubstitutionRequest(substitution_id=own.id), token=editor)
    assert (listed.status, one.message.substitution.id) == (200, own.id)
    # A write happened, the phone's last call, and nothing else did.
    assert seen
    assert unexpected_writes(seen) == []
    assert all(last_seen_rule.match(statement) for statement in seen), seen
