"""``BellService``'s reads: the class's bell schedules, as «🔔 Звонки» lists them.

The list is v1's ``GET /manage/bells``, schedule for schedule, with the
default marked and each time ``"HH:MM"`` where v1 wrote seconds. One schedule
is read by its id, which v1 had no endpoint for; an id of another class's
schedule, or of none, is ``RESOURCE_NOT_FOUND`` in v1's words. Reads write
nothing (``docs/specs/2026-10-05-server-v2-design.md``, decision 10).
"""

from __future__ import annotations

from datetime import time

from app import wording
from app.contract.lessons.v2.bell_pb import BellPeriod, GetBellScheduleRequest
from app.models import BellPeriod as PeriodRow
from app.models import BellSchedule as ScheduleRow
from app.models import SchoolClass


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _saturday(session, school_class) -> ScheduleRow:
    """A second schedule, its rows written out of order, as a paste may leave them."""
    saturday = ScheduleRow(class_id=school_class.id, name="Суббота")
    session.add(saturday)
    await session.flush()
    session.add_all(
        [
            PeriodRow(
                schedule_id=saturday.id, index=2, starts_at=time(9, 50), ends_at=time(10, 30)
            ),
            PeriodRow(schedule_id=saturday.id, index=1, starts_at=time(9, 0), ends_at=time(9, 40)),
        ]
    )
    await session.commit()
    return saturday


async def test_the_schedules_are_v1_s_with_the_default_marked(
    v2, v2_tokens, session, school_class
) -> None:
    saturday = await _saturday(session, school_class)
    admin = v2_tokens["admin"]
    v1 = (await v2.http.get("/api/v1/manage/bells", headers=_auth(admin))).json()
    answer = await v2.both("BellService/ListBellSchedules", token=admin)
    schedules = answer.message.schedules
    assert [schedule.id for schedule in schedules] == [row["id"] for row in v1]
    assert [schedule.id for schedule in schedules] == [school_class.bell_schedule_id, saturday.id]
    for mine, theirs in zip(schedules, v1, strict=True):
        assert (mine.name, mine.is_default) == (theirs["name"], theirs["is_default"])
        # v1 wrote "08:30:00"; v2 writes "08:30".
        assert [(row.index, row.starts_at, row.ends_at) for row in mine.periods] == [
            (row["index"], row["starts_at"][:5], row["ends_at"][:5]) for row in theirs["periods"]
        ]
    assert [schedule.is_default for schedule in schedules] == [True, False]


async def test_one_schedule_is_read_by_its_id_with_its_rows_in_order(
    v2, v2_tokens, session, school_class
) -> None:
    saturday = await _saturday(session, school_class)
    answer = await v2.both(
        "BellService/GetBellSchedule",
        GetBellScheduleRequest(schedule_id=saturday.id),
        token=v2_tokens["admin"],
    )
    schedule = answer.message.schedule
    assert (schedule.id, schedule.name, schedule.is_default) == (saturday.id, "Суббота", False)
    assert list(schedule.periods) == [
        BellPeriod(index=1, starts_at="09:00", ends_at="09:40"),
        BellPeriod(index=2, starts_at="09:50", ends_at="10:30"),
    ]


async def test_an_id_that_names_no_schedule_of_the_class_is_not_found(
    v2, v2_tokens, session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    theirs = ScheduleRow(class_id=other.id, name="Чужое")
    session.add(theirs)
    await session.commit()
    admin = v2_tokens["admin"]
    v1 = await v2.http.patch(
        f"/api/v1/manage/bells/{theirs.id}", json={"name": "Моё"}, headers=_auth(admin)
    )
    assert v1.status_code == 404
    for schedule_id in (theirs.id, 999_999):
        answer = await v2.both(
            "BellService/GetBellSchedule",
            GetBellScheduleRequest(schedule_id=schedule_id),
            token=admin,
        )
        assert (answer.status, answer.code, answer.reason) == (
            404,
            "NOT_FOUND",
            "RESOURCE_NOT_FOUND",
        )
        assert answer.metadata == {"resource": "bell_schedule"}
        assert answer.error == v1.json()["detail"] == wording.UNKNOWN_BELL_SCHEDULE_DETAIL


async def test_reading_the_schedules_writes_nothing_but_the_last_seen(
    v2, v2_tokens, session, school_class, statement_writes, unexpected_writes, last_seen_rule
) -> None:
    saturday = await _saturday(session, school_class)
    admin = v2_tokens["admin"]
    with statement_writes() as seen:
        listed = await v2.both("BellService/ListBellSchedules", token=admin)
        one = await v2.both(
            "BellService/GetBellSchedule",
            GetBellScheduleRequest(schedule_id=saturday.id),
            token=admin,
        )
    assert (listed.status, one.status) == (200, 200)
    assert len(listed.message.schedules) == 2
    # A write happened, the phone's last call, and nothing else did.
    assert seen
    assert unexpected_writes(seen) == []
    assert all(last_seen_rule.match(statement) for statement in seen), seen
