"""``BellService``'s writes: create, one masked update, delete, by v1's rules.

v1's ``/manage/bells`` writes over v2, through the same services and v1's own
``BellScheduleIn``, ``BellSchedulePatch`` and ``BellPeriodsIn``, so the two
versions refuse the same requests in the same words. v1's two writes to one
schedule are one ``UpdateBellSchedule``: the rename, then the rows, then the
default, so ``silenced_lessons`` counts once what the request stopped ringing
(``docs/specs/2026-10-05-server-v2-3b-plan.md``, Ruling 21). A write's success
is asked once per transport on fresh data, and its refusals through ``both``
(Ruling 17).
"""

from __future__ import annotations

from datetime import date

from protobuf.wkt import FieldMask
from sqlalchemy import select

from app import wording
from app.contract.lessons.v2.bell_pb import (
    BellPeriod,
    BellSchedule,
    CreateBellScheduleRequest,
    DeleteBellScheduleRequest,
    UpdateBellScheduleRequest,
)
from app.models import AuditEntry, DayKind, DayOverride, SchoolClass
from app.models import BellPeriod as PeriodRow
from app.models import BellSchedule as ScheduleRow
from app.rpc.masks import NOT_CHANGEABLE

MONDAY = date(2026, 9, 7)
ONE_ROW = [BellPeriod(index=1, starts_at="08:30", ends_at="09:10")]
THREE_ROWS = [
    BellPeriod(index=1, starts_at="08:30", ends_at="09:10"),
    BellPeriod(index=2, starts_at="09:20", ends_at="10:00"),
    BellPeriod(index=3, starts_at="10:10", ends_at="10:50"),
]


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _actions(session) -> list[str]:
    return list(await session.scalars(select(AuditEntry.action).order_by(AuditEntry.id)))


async def _summaries(session, action: str) -> list[str]:
    return list(
        await session.scalars(
            select(AuditEntry.summary).where(AuditEntry.action == action).order_by(AuditEntry.id)
        )
    )


async def _row(session, schedule_id: int) -> ScheduleRow | None:
    """The schedule as the database holds it now, or ``None`` once it is gone."""
    return await session.scalar(
        select(ScheduleRow)
        .where(ScheduleRow.id == schedule_id)
        .execution_options(populate_existing=True)
    )


async def _default_of(session, school_class) -> int | None:
    return await session.scalar(
        select(SchoolClass.bell_schedule_id).where(SchoolClass.id == school_class.id)
    )


async def _indexes(session, schedule_id: int) -> list[int]:
    return list(
        await session.scalars(
            select(PeriodRow.index)
            .where(PeriodRow.schedule_id == schedule_id)
            .order_by(PeriodRow.index)
        )
    )


async def _empty(session, school_class, name: str) -> ScheduleRow:
    schedule = ScheduleRow(class_id=school_class.id, name=name)
    session.add(schedule)
    await session.commit()
    return schedule


def _update(schedule: BellSchedule, *paths: str) -> UpdateBellScheduleRequest:
    return UpdateBellScheduleRequest(schedule=schedule, update_mask=FieldMask(paths=list(paths)))


async def test_a_schedule_is_created_on_either_path_and_rest_says_201(
    v2, v2_tokens, session, school_class
) -> None:
    admin = v2_tokens["admin"]
    rest = await v2.rest(
        "BellService/CreateBellSchedule",
        CreateBellScheduleRequest(
            schedule=BellSchedule(
                name=" Сокращённое ",
                is_default=True,
                periods=[
                    BellPeriod(index=2, starts_at="09:10", ends_at="09:40"),
                    BellPeriod(index=1, starts_at="08:30", ends_at="09:00"),
                ],
            )
        ),
        token=admin,
    )
    assert rest.status == 201
    made = rest.message.schedule
    # Cleaned as v1 cleans a name. A new schedule never becomes the default,
    # whatever the request says of it, and its rows come back in order.
    assert (made.name, made.is_default) == ("Сокращённое", False)
    assert [(row.index, row.starts_at) for row in made.periods] == [(1, "08:30"), (2, "09:10")]
    assert await _indexes(session, made.id) == [1, 2]
    # The id a client sends is ignored: the server assigns it.
    connect = await v2.connect(
        "BellService/CreateBellSchedule",
        CreateBellScheduleRequest(schedule=BellSchedule(id=999, name="Суббота")),
        token=admin,
    )
    assert connect.status == 200
    assert connect.message.schedule.id != 999
    assert list(connect.message.schedule.periods) == []
    assert await _default_of(session, school_class) == school_class.bell_schedule_id
    assert await _actions(session) == ["bells.create", "bells.create"]


async def test_a_schedule_v1_would_refuse_is_refused_on_its_field(v2, v2_tokens) -> None:
    admin = v2_tokens["admin"]
    late = {"index": 1, "starts_at": "09:40", "ends_at": "09:00"}
    twice = [
        {"index": 1, "starts_at": "09:00", "ends_at": "09:40"},
        {"index": 1, "starts_at": "10:00", "ends_at": "10:40"},
    ]
    past = {"index": 21, "starts_at": "09:00", "ends_at": "09:40"}
    for sent, fields in (
        ({"name": "   "}, ["schedule.name"]),
        ({"name": "Суббота", "periods": [late]}, ["schedule.periods.0"]),
        # A rule of the whole schedule names the schedule itself (Ruling 28).
        ({"name": "Суббота", "periods": twice}, ["schedule"]),
        ({"name": "Суббота", "periods": [past]}, ["schedule.periods.0.index"]),
    ):
        request = CreateBellScheduleRequest(
            schedule=BellSchedule(
                name=sent["name"],
                periods=[BellPeriod(**row) for row in sent.get("periods", [])],
            )
        )
        answer = await v2.both("BellService/CreateBellSchedule", request, token=admin)
        assert (answer.code, answer.reason) == ("INVALID_ARGUMENT", "VALIDATION_FAILED"), fields
        assert [field for field, _ in answer.violations] == fields
        v1 = await v2.http.post("/api/v1/manage/bells", json=sent, headers=_auth(admin))
        assert v1.status_code == 422, fields


async def test_new_rows_answer_what_they_stopped_ringing_and_a_rename_answers_none(
    v2, v2_tokens, session, school_class
) -> None:
    """The fixture's Monday carries lessons 1 to 3 on its default. Shrinking the
    default to one row stops two of them ringing, as v1's ``PUT …/periods``
    answers; a rename stops none."""
    admin = v2_tokens["admin"]
    default_id = school_class.bell_schedule_id
    rows = await v2.rest(
        "BellService/UpdateBellSchedule",
        _update(BellSchedule(id=default_id, periods=ONE_ROW), "periods"),
        token=admin,
    )
    assert rows.status == 200
    assert rows.message.silenced_lessons == 2
    assert [row.index for row in rows.message.schedule.periods] == [1]
    assert rows.message.schedule.is_default is True
    renamed = await v2.connect(
        "BellService/UpdateBellSchedule",
        _update(BellSchedule(id=default_id, name="Основное"), "name"),
        token=admin,
    )
    assert renamed.status == 200
    assert (renamed.message.schedule.name, renamed.message.silenced_lessons) == ("Основное", 0)
    assert await _indexes(session, default_id) == [1]
    assert await _actions(session) == ["bells.edit", "bells.rename"]


async def test_rows_and_the_default_in_one_request_count_what_stopped_ringing_once(
    v2, v2_tokens, session, school_class
) -> None:
    """An empty «Суббота» is given one row and made the default in one request.
    It is not refused as empty, because its rows are read back before the
    default moves, and the two Monday lessons it stops ringing are counted
    once, against the bells the class rang before the request. Once it is the
    default, new rows count what they stop ringing, and the move adds
    nothing."""
    saturday = await _empty(session, school_class, "Суббота")
    admin = v2_tokens["admin"]
    moved = await v2.rest(
        "BellService/UpdateBellSchedule",
        _update(
            BellSchedule(id=saturday.id, is_default=True, periods=ONE_ROW),
            "is_default",
            "periods",
        ),
        token=admin,
    )
    assert moved.status == 200
    assert moved.message.silenced_lessons == 2
    assert moved.message.schedule.is_default is True
    assert await _default_of(session, school_class) == saturday.id
    assert await _summaries(session, "bells.edit") == ["звонки «Суббота»: 1 уроков"]
    assert await _summaries(session, "bells.default") == [
        "основное расписание звонков: «Суббота», перестали звонить уроков: 2"
    ]
    grown = await v2.connect(
        "BellService/UpdateBellSchedule",
        _update(
            BellSchedule(id=saturday.id, is_default=True, periods=THREE_ROWS),
            "periods",
            "is_default",
        ),
        token=admin,
    )
    assert grown.message.silenced_lessons == 0
    shrunk = await v2.rest(
        "BellService/UpdateBellSchedule",
        _update(
            BellSchedule(id=saturday.id, is_default=True, periods=ONE_ROW),
            "periods",
            "is_default",
        ),
        token=admin,
    )
    assert shrunk.message.silenced_lessons == 2
    assert await _actions(session) == ["bells.edit", "bells.default", "bells.edit", "bells.edit"]


async def test_an_update_without_a_mask_changes_only_what_it_sends(
    v2, v2_tokens, session, school_class
) -> None:
    """``is_default`` false and no rows are what an unset field reads as, and
    without a mask an unset field is left alone, neither refused nor emptied."""
    saturday = await _empty(session, school_class, "Сб")
    answer = await v2.rest(
        "BellService/UpdateBellSchedule",
        UpdateBellScheduleRequest(schedule=BellSchedule(id=saturday.id, name="Суббота")),
        token=v2_tokens["admin"],
    )
    assert answer.status == 200
    assert (answer.message.schedule.name, answer.message.schedule.is_default) == (
        "Суббота",
        False,
    )
    assert await _default_of(session, school_class) == school_class.bell_schedule_id
    assert await _actions(session) == ["bells.rename"]


async def test_a_masked_is_default_left_out_is_refused_and_the_class_keeps_its_default(
    v2, v2_tokens, session, school_class
) -> None:
    admin = v2_tokens["admin"]
    default_id = school_class.bell_schedule_id
    v1 = await v2.http.patch(
        f"/api/v1/manage/bells/{default_id}", json={"is_default": False}, headers=_auth(admin)
    )
    answer = await v2.both(
        "BellService/UpdateBellSchedule",
        _update(BellSchedule(id=default_id, name="Другое"), "name", "is_default"),
        token=admin,
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "INVALID_ARGUMENT",
        "VALIDATION_FAILED",
    )
    assert answer.violations == [("schedule.is_default", wording.BELL_DEFAULT_REQUIRED_DETAIL)]
    assert answer.error == v1.json()["detail"] == wording.BELL_DEFAULT_REQUIRED_DETAIL
    assert v1.status_code == 422
    assert await _default_of(session, school_class) == default_id
    assert (await _row(session, default_id)).name == "Обычное"
    assert await _actions(session) == []


async def test_a_schedule_that_rings_nothing_cannot_become_the_default(
    v2, v2_tokens, session, school_class
) -> None:
    empty = await _empty(session, school_class, "Пустое")
    admin = v2_tokens["admin"]
    v1 = await v2.http.patch(
        f"/api/v1/manage/bells/{empty.id}", json={"is_default": True}, headers=_auth(admin)
    )
    answer = await v2.both(
        "BellService/UpdateBellSchedule",
        _update(BellSchedule(id=empty.id, is_default=True), "is_default"),
        token=admin,
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "FAILED_PRECONDITION",
        "EMPTY_BELL_SCHEDULE",
    )
    assert answer.metadata == {}
    assert answer.error == v1.json()["detail"] == wording.EMPTY_BELL_SCHEDULE_DETAIL
    assert v1.status_code == 422
    assert await _default_of(session, school_class) == school_class.bell_schedule_id
    assert await _actions(session) == []


async def test_masked_rows_left_out_are_refused_rather_than_emptied(
    v2, v2_tokens, session, school_class
) -> None:
    default_id = school_class.bell_schedule_id
    answer = await v2.both(
        "BellService/UpdateBellSchedule",
        _update(BellSchedule(id=default_id), "periods"),
        token=v2_tokens["admin"],
    )
    assert answer.reason == "VALIDATION_FAILED"
    assert [field for field, _ in answer.violations] == ["schedule.periods"]
    assert await _indexes(session, default_id) == [1, 2, 3, 4, 5, 6, 7]


async def test_masked_rows_that_repeat_a_lesson_number_are_refused_on_the_schedule(
    v2, v2_tokens, session, school_class
) -> None:
    """The rule of the whole schedule names the schedule itself, as the create
    does (Ruling 28), and the rows the class has stay as they were."""
    admin = v2_tokens["admin"]
    default_id = school_class.bell_schedule_id
    twice = [
        BellPeriod(index=1, starts_at="09:00", ends_at="09:40"),
        BellPeriod(index=1, starts_at="10:00", ends_at="10:40"),
    ]
    v1 = await v2.http.put(
        f"/api/v1/manage/bells/{default_id}/periods",
        json={
            "periods": [
                {"index": 1, "starts_at": "09:00", "ends_at": "09:40"},
                {"index": 1, "starts_at": "10:00", "ends_at": "10:40"},
            ]
        },
        headers=_auth(admin),
    )
    answer = await v2.both(
        "BellService/UpdateBellSchedule",
        _update(BellSchedule(id=default_id, periods=twice), "periods"),
        token=admin,
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "INVALID_ARGUMENT",
        "VALIDATION_FAILED",
    )
    assert [field for field, _ in answer.violations] == ["schedule"]
    assert v1.status_code == 422
    assert await _indexes(session, default_id) == [1, 2, 3, 4, 5, 6, 7]
    assert await _actions(session) == []


async def test_a_mask_naming_a_field_the_method_does_not_change_is_refused_on_it(
    v2, v2_tokens, session, school_class
) -> None:
    for paths in (["id"], ["name", "canteen_after_index"], ["*"]):
        answer = await v2.both(
            "BellService/UpdateBellSchedule",
            _update(BellSchedule(id=school_class.bell_schedule_id, name="Другое"), *paths),
            token=v2_tokens["admin"],
        )
        assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED"), paths
        assert answer.violations == [("update_mask", NOT_CHANGEABLE)]
    assert await _actions(session) == []


async def test_the_default_and_a_schedule_days_use_are_refused_as_in_use(
    v2, v2_tokens, session, school_class
) -> None:
    admin = v2_tokens["admin"]
    default_id = school_class.bell_schedule_id
    v1_default = await v2.http.delete(f"/api/v1/manage/bells/{default_id}", headers=_auth(admin))
    default = await v2.both(
        "BellService/DeleteBellSchedule",
        DeleteBellScheduleRequest(schedule_id=default_id),
        token=admin,
    )
    assert (default.status, default.code, default.reason) == (
        400,
        "FAILED_PRECONDITION",
        "RESOURCE_IN_USE",
    )
    assert default.metadata == {"resource": "bell_schedule", "used_by": "class"}
    assert default.error == v1_default.json()["detail"] == wording.BELL_SCHEDULE_IS_DEFAULT_DETAIL

    short = await _empty(session, school_class, "Сокращённое")
    session.add(
        DayOverride(
            class_id=school_class.id,
            date=MONDAY,
            kind=DayKind.SHORTENED,
            bell_schedule_id=short.id,
        )
    )
    await session.commit()
    v1_used = await v2.http.delete(f"/api/v1/manage/bells/{short.id}", headers=_auth(admin))
    used = await v2.both(
        "BellService/DeleteBellSchedule",
        DeleteBellScheduleRequest(schedule_id=short.id),
        token=admin,
    )
    assert (used.status, used.reason) == (400, "RESOURCE_IN_USE")
    assert used.metadata == {"resource": "bell_schedule", "used_by": "days", "count": "1"}
    assert used.error == v1_used.json()["detail"] == wording.bell_schedule_in_use_detail(1)
    assert v1_default.status_code == v1_used.status_code == 409
    assert await _row(session, short.id) is not None
    assert await _actions(session) == []


async def test_a_free_schedule_is_deleted_on_either_path(
    v2, v2_tokens, session, school_class
) -> None:
    first = await _empty(session, school_class, "Первое")
    second = await _empty(session, school_class, "Второе")
    admin = v2_tokens["admin"]
    rest = await v2.rest(
        "BellService/DeleteBellSchedule",
        DeleteBellScheduleRequest(schedule_id=first.id),
        token=admin,
    )
    connect = await v2.connect(
        "BellService/DeleteBellSchedule",
        DeleteBellScheduleRequest(schedule_id=second.id),
        token=admin,
    )
    assert (rest.status, connect.status) == (200, 200)
    assert await _row(session, first.id) is None
    assert await _row(session, second.id) is None
    assert await _actions(session) == ["bells.delete", "bells.delete"]
    again = await v2.both(
        "BellService/DeleteBellSchedule",
        DeleteBellScheduleRequest(schedule_id=first.id),
        token=admin,
    )
    assert (again.status, again.reason) == (404, "RESOURCE_NOT_FOUND")


async def test_another_class_s_schedule_is_found_by_no_write(
    v2, v2_tokens, session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    theirs = ScheduleRow(class_id=other.id, name="Чужое")
    session.add(theirs)
    await session.commit()
    for name, request in (
        ("UpdateBellSchedule", _update(BellSchedule(id=theirs.id, name="Моё"), "name")),
        ("DeleteBellSchedule", DeleteBellScheduleRequest(schedule_id=theirs.id)),
    ):
        answer = await v2.both(f"BellService/{name}", request, token=v2_tokens["admin"])
        assert (answer.status, answer.reason, answer.metadata, answer.error) == (
            404,
            "RESOURCE_NOT_FOUND",
            {"resource": "bell_schedule"},
            wording.UNKNOWN_BELL_SCHEDULE_DETAIL,
        ), name
    assert (await _row(session, theirs.id)).name == "Чужое"
