"""``HomeworkService``'s reads: ``ListHomework`` and ``GetHomework``.

v1's ``GET /homework`` over v2, through the same services: the same rows in
the same order, each with this phone's owner's tick and none for a phone no
account is behind, in a window that is today and three weeks on when unset
and sixty-two days at most (``clock.window``). A window v1 refuses is
``VALIDATION_FAILED`` on the field at fault, in v1's words wherever v1's
fields are v2's too. Homework of another class is not found. A read writes
nothing (``docs/specs/2026-10-05-server-v2-design.md``, decision 10).
"""

from __future__ import annotations

from datetime import date, datetime, timedelta

from app import wording
from app.contract.lessons.v2.homework_pb import GetHomeworkRequest, ListHomeworkRequest
from app.models import Homework, HomeworkDone, SchoolClass
from app.rpc.errors import WINDOW_BACKWARDS
from app.services import clock

#: The account behind v2_tokens' viewer phone.
VIEWER = 2001
MONDAY = date(2026, 9, 7)
LIST = "HomeworkService/ListHomework"
GET = "HomeworkService/GetHomework"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _pin_clock(monkeypatch) -> None:
    """Monday 7 September, 10:00 in the class's zone, for v1 and v2 alike."""
    monkeypatch.setattr(
        clock, "now", lambda school_class: datetime(2026, 9, 7, 10, 0, tzinfo=school_class.tz)
    )


async def _homework(session, school_class, due: date, subject: str = "Алгебра", **fields):
    item = Homework(
        class_id=school_class.id, due_date=due, subject_name=subject, text="№ 12–15", **fields
    )
    session.add(item)
    await session.commit()
    return item


async def test_the_homework_is_v1_s_in_v2_s_shape(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    tuesday = MONDAY + timedelta(days=1)
    physics = await _homework(
        session, school_class, tuesday, "Физика", attachment_url="https://example.com/p.pdf"
    )
    await _homework(session, school_class, tuesday, "Алгебра")
    await _homework(session, school_class, MONDAY, "История")
    await _homework(session, school_class, MONDAY + timedelta(days=40), "Химия")
    session.add(HomeworkDone(homework_id=physics.id, telegram_id=VIEWER))
    await session.commit()
    viewer = v2_tokens["viewer"]
    v1 = (await v2.http.get("/api/v1/homework", headers=_auth(viewer))).json()

    rows = (await v2.both(LIST, token=viewer)).message.homework
    # Today and three weeks on, by date and then subject, as v1 lists them.
    assert [row.id for row in rows] == [row["id"] for row in v1]
    assert [(row.due_date, row.subject) for row in rows] == [
        ("2026-09-07", "История"),
        ("2026-09-08", "Алгебра"),
        ("2026-09-08", "Физика"),
    ]
    assert (rows[2].text, rows[2].attachment_url) == ("№ 12–15", "https://example.com/p.pdf")
    assert not rows[0].has_field("attachment_url")
    assert [row.done for row in rows] == [row["done"] for row in v1] == [False, False, True]
    # The tick is this phone's owner's: the class code's phone has none.
    unlinked = (await v2.both(LIST, token=v2_tokens["unlinked"])).message.homework
    assert [row.done for row in unlinked] == [False, False, False]


async def test_a_window_named_by_its_edges_is_v1_s_from_and_to(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    _pin_clock(monkeypatch)
    for due, subject in (
        (date(2026, 8, 31), "Вчерашнее"),
        (MONDAY, "История"),
        (date(2026, 10, 31), "Химия"),
    ):
        await _homework(session, school_class, due, subject)
    viewer = v2_tokens["viewer"]
    v1 = await v2.http.get(
        "/api/v1/homework",
        params={"from": "2026-09-01", "to": "2026-10-31"},
        headers=_auth(viewer),
    )
    both_edges = await v2.both(
        LIST, ListHomeworkRequest(start_date="2026-09-01", end_date="2026-10-31"), token=viewer
    )
    assert [row.subject for row in both_edges.message.homework] == ["История", "Химия"]
    assert [row["subject"] for row in v1.json()] == ["История", "Химия"]
    # Three weeks on from the start named: 21 September, before October's.
    start_only = await v2.both(LIST, ListHomeworkRequest(start_date="2026-08-31"), token=viewer)
    assert [row.subject for row in start_only.message.homework] == ["Вчерашнее", "История"]


async def test_a_window_v1_refuses_is_refused_on_the_field_at_fault(v2, v2_tokens) -> None:
    viewer = v2_tokens["viewer"]
    for sent, field, sentence in (
        (ListHomeworkRequest(start_date="1999-01-01"), "start_date", clock.DATES_OUT_OF_BOUNDS),
        (
            ListHomeworkRequest(start_date="2026-09-10", end_date="2026-09-01"),
            "end_date",
            WINDOW_BACKWARDS,
        ),
        (
            ListHomeworkRequest(start_date="2026-09-01", end_date="2026-12-31"),
            "end_date",
            clock.WINDOW_TOO_WIDE,
        ),
    ):
        answer = await v2.both(LIST, sent, token=viewer)
        assert (answer.status, answer.code, answer.reason) == (
            400,
            "INVALID_ARGUMENT",
            "VALIDATION_FAILED",
        ), field
        assert (answer.error, answer.violations) == (sentence, [(field, sentence)])
    # v1 refuses the same windows, in the same words where its fields are v2's.
    for params, sentence in (
        ({"from": "1999-01-01"}, clock.DATES_OUT_OF_BOUNDS),
        ({"from": "2026-09-01", "to": "2026-12-31"}, clock.WINDOW_TOO_WIDE),
    ):
        v1 = await v2.http.get("/api/v1/homework", params=params, headers=_auth(viewer))
        assert (v1.status_code, v1.json()["detail"]) == (422, sentence)
    # A date that does not parse is refused on its field, as v1 refuses it.
    unparsed = await v2.both(LIST, ListHomeworkRequest(end_date="2026-13-01"), token=viewer)
    assert (unparsed.reason, [name for name, _ in unparsed.violations]) == (
        "VALIDATION_FAILED",
        ["end_date"],
    )


async def test_one_assignment_is_its_row_and_another_class_s_is_not_found(
    v2, v2_tokens, session, school_class
) -> None:
    own = await _homework(session, school_class, MONDAY)
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    foreign = Homework(class_id=other.id, due_date=MONDAY, subject_name="Алгебра", text="№ 1")
    session.add(foreign)
    await session.commit()
    viewer = v2_tokens["viewer"]
    answer = await v2.both(GET, GetHomeworkRequest(homework_id=own.id), token=viewer)
    homework = answer.message.homework
    assert (homework.id, homework.due_date, homework.subject, homework.done) == (
        own.id,
        "2026-09-07",
        "Алгебра",
        False,
    )
    v1 = await v2.http.post(
        f"/api/v1/homework/{foreign.id}/done", json={"done": True}, headers=_auth(viewer)
    )
    for homework_id in (foreign.id, 999_999):
        refused = await v2.both(GET, GetHomeworkRequest(homework_id=homework_id), token=viewer)
        assert (refused.status, refused.code, refused.reason, refused.metadata) == (
            404,
            "NOT_FOUND",
            "RESOURCE_NOT_FOUND",
            {"resource": "homework"},
        )
        assert refused.error == v1.json()["detail"] == wording.UNKNOWN_HOMEWORK_DETAIL


async def test_reading_homework_writes_nothing_but_the_last_seen(
    v2, v2_tokens, session, school_class, statement_writes, unexpected_writes, last_seen_rule
) -> None:
    own = await _homework(session, school_class, MONDAY)
    viewer = v2_tokens["viewer"]
    with statement_writes() as seen:
        listed = await v2.both(LIST, token=viewer)
        one = await v2.both(GET, GetHomeworkRequest(homework_id=own.id), token=viewer)
    assert (listed.status, one.message.homework.id) == (200, own.id)
    # A write happened, the phone's last call, and nothing else did.
    assert seen
    assert unexpected_writes(seen) == []
    assert all(last_seen_rule.match(statement) for statement in seen), seen
