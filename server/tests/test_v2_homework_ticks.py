"""``MeService``'s homework ticks: «сделал», for the linked account alone.

v1's ``POST /homework/{id}/done`` set a tick to the state the app showed; v2
makes the tick a resource, put on with ``CreateHomeworkTick`` and taken off
with ``DeleteHomeworkTick``, through the same ``tasks.set_homework_done``.
Either asked twice lands on the same answer, as the proto says, and homework
of another class is not found, as in v1. The tick is the account's: another
member of the class does not see it.
"""

from __future__ import annotations

from datetime import date

from sqlalchemy import func, select

from app import wording
from app.contract.lessons.v2.me_pb import (
    CreateHomeworkTickRequest,
    DeleteHomeworkTickRequest,
    HomeworkTick,
)
from app.models import Homework, HomeworkDone, SchoolClass

#: The account behind v2_tokens' viewer phone.
VIEWER = 2001
TICK = "MeService/CreateHomeworkTick"
UNTICK = "MeService/DeleteHomeworkTick"
MONDAY = date(2026, 9, 7)


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _homework(session, school_class) -> Homework:
    item = Homework(class_id=school_class.id, due_date=MONDAY, subject_name="Алгебра", text="№ 1")
    session.add(item)
    await session.commit()
    return item


def _tick(homework_id: int) -> CreateHomeworkTickRequest:
    return CreateHomeworkTickRequest(homework_tick=HomeworkTick(homework_id=homework_id))


async def _ticks(session) -> list[tuple[int, int]]:
    rows = await session.execute(select(HomeworkDone.homework_id, HomeworkDone.telegram_id))
    return [tuple(row) for row in rows]


async def _v1_done(v2, token: str) -> bool:
    params = {"from": MONDAY.isoformat(), "to": MONDAY.isoformat()}
    rows = (await v2.http.get("/api/v1/homework", params=params, headers=_auth(token))).json()
    return rows[0]["done"]


async def test_a_tick_is_v1_s_done_and_ticking_twice_is_one_tick(
    v2, v2_tokens, session, school_class
) -> None:
    homework = await _homework(session, school_class)
    answer = await v2.both(TICK, _tick(homework.id), token=v2_tokens["viewer"])
    assert answer.status == 200
    assert answer.message.homework_tick.homework_id == homework.id
    assert await _ticks(session) == [(homework.id, VIEWER)]
    # v1 reads it as done for this account, and not for another one.
    assert await _v1_done(v2, v2_tokens["viewer"]) is True
    assert await _v1_done(v2, v2_tokens["editor"]) is False


async def test_taking_a_tick_off_twice_is_not_an_error(
    v2, v2_tokens, session, school_class
) -> None:
    homework = await _homework(session, school_class)
    session.add(HomeworkDone(homework_id=homework.id, telegram_id=VIEWER))
    await session.commit()
    answer = await v2.both(
        UNTICK, DeleteHomeworkTickRequest(homework_id=homework.id), token=v2_tokens["viewer"]
    )
    assert (answer.status, answer.body) == (200, b"{}")
    assert await _ticks(session) == []
    assert await _v1_done(v2, v2_tokens["viewer"]) is False


async def test_homework_of_another_class_or_none_is_not_found(
    v2, v2_tokens, session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    foreign = Homework(class_id=other.id, due_date=MONDAY, subject_name="Алгебра", text="№ 1")
    session.add(foreign)
    await session.commit()
    viewer = v2_tokens["viewer"]
    v1 = await v2.http.post(
        f"/api/v1/homework/{foreign.id}/done", json={"done": True}, headers=_auth(viewer)
    )
    assert v1.status_code == 404
    for homework_id in (foreign.id, 999_999):
        for name, request in (
            (TICK, _tick(homework_id)),
            (UNTICK, DeleteHomeworkTickRequest(homework_id=homework_id)),
        ):
            answer = await v2.both(name, request, token=viewer)
            assert (answer.status, answer.code, answer.reason) == (
                404,
                "NOT_FOUND",
                "RESOURCE_NOT_FOUND",
            ), name
            assert answer.metadata == {"resource": "homework"}
            assert answer.error == v1.json()["detail"] == wording.UNKNOWN_HOMEWORK_DETAIL
    assert await session.scalar(select(func.count()).select_from(HomeworkDone)) == 0


async def test_taking_off_a_tick_that_is_not_there_writes_nothing_but_the_last_seen(
    v2, v2_tokens, session, school_class, statement_writes, unexpected_writes, last_seen_rule
) -> None:
    homework = await _homework(session, school_class)
    with statement_writes() as seen:
        answer = await v2.both(
            UNTICK, DeleteHomeworkTickRequest(homework_id=homework.id), token=v2_tokens["viewer"]
        )
    assert answer.status == 200
    # A write happened, the phone's last call, and nothing else did.
    assert seen
    assert unexpected_writes(seen) == []
    assert all(last_seen_rule.match(statement) for statement in seen), seen
