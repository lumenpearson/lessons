"""``ClassService``'s card: the class as «⚙️ Класс» shows it, changes it and deletes it.

``GetClass`` is v1's ``GET /manage/class`` with the grade and the letter.
``UpdateClass`` is v1's ``PATCH`` through the same ``classes_service.update``,
read through an ``update_mask``. ``DeleteClass`` is the owner's, confirmed by
the class's name typed back, and ``GetClassStats`` is v1's ``/manage/stats``.
The card carries the join code, so its answer is never cached
(``docs/specs/2026-10-05-server-v2-3b-plan.md``, Rulings 18, 29 and 30). A
write's success is asked once per transport on fresh data, and its refusals
through ``both`` (Ruling 17).
"""

from __future__ import annotations

from datetime import date, timedelta

import pytest
from protobuf.wkt import FieldMask
from sqlalchemy import select, text

from app import wording
from app.contract.lessons.v2.options_pb import Role as ProtoRole
from app.contract.lessons.v2.school_class_pb import (
    DeleteClassRequest,
    JoinMode,
    UpdateClassRequest,
)
from app.contract.lessons.v2.school_class_pb import SchoolClass as Card
from app.models import AuditEntry, Homework, SchoolClass
from app.models import JoinMode as JoinModeRow
from app.rpc.errors import CONFIRMATION_MISMATCH
from app.rpc.masks import NOT_CHANGEABLE
from app.rpc.school_class import JOIN_MODE_REFUSED

JOIN_MODE_COLUMN = text("select join_mode from classes where id = :id")


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _actions(session) -> list[str]:
    return list(await session.scalars(select(AuditEntry.action).order_by(AuditEntry.id)))


async def _stored(session, school_class) -> SchoolClass | None:
    """The class as the database holds it now, or ``None`` once it is gone."""
    return await session.scalar(
        select(SchoolClass)
        .where(SchoolClass.id == school_class.id)
        .execution_options(populate_existing=True)
    )


def _update(card: Card, *paths: str) -> UpdateClassRequest:
    """An update with ``paths`` as its mask, or with no mask when none is named."""
    mask = FieldMask(paths=list(paths)) if paths else None
    return UpdateClassRequest(school_class=card, update_mask=mask)


async def test_the_card_is_v1_s_with_the_grade_and_the_letter(
    v2, v2_tokens, session, school_class
) -> None:
    school_class.grade, school_class.letter = 9, "А"
    await session.commit()
    admin = v2_tokens["admin"]
    v1 = (await v2.http.get("/api/v1/manage/class", headers=_auth(admin))).json()
    answer = await v2.both("ClassService/GetClass", token=admin)
    card = answer.message.school_class
    assert (card.id, card.name, card.school, card.join_code) == (
        v1["id"],
        v1["name"],
        v1["school"],
        v1["join_code"],
    )
    assert (card.timezone, card.timezone_label) == (v1["timezone"], v1["timezone_label"])
    assert not card.has_field("city") and v1["city"] is None
    assert (card.members, card.devices, card.pending_requests) == (
        v1["members"],
        v1["devices"],
        v1["pending_requests"],
    )
    assert card.bell_schedule_id == v1["bell_schedule_id"]
    assert card.calendar_ready is v1["calendar_ready"] is False
    assert (card.join_mode, v1["join_mode"]) == (JoinMode.OPEN, "open")
    # What v1's card did not carry.
    assert (card.grade, card.letter) == (9, "А")
    # The four members with a role and the six phones of the fixture.
    assert (card.members, card.devices) == (4, 6)


async def test_the_card_and_the_numbers_write_nothing_but_the_last_seen(
    v2, v2_tokens, statement_writes, unexpected_writes, last_seen_rule
) -> None:
    with statement_writes() as seen:
        card = await v2.both("ClassService/GetClass", token=v2_tokens["admin"])
        stats = await v2.both("ClassService/GetClassStats", token=v2_tokens["editor"])
    assert (card.status, stats.status) == (200, 200)
    assert card.message.school_class.join_code == "TEST42"
    # A write happened, each phone's last call, and nothing else did.
    assert seen
    assert unexpected_writes(seen) == []
    assert all(last_seen_rule.match(statement) for statement in seen), seen


async def test_the_numbers_are_v1_s_stats(v2, v2_tokens, session, school_class) -> None:
    today = date.today()
    session.add_all(
        [
            Homework(
                class_id=school_class.id,
                due_date=today + timedelta(days=3),
                subject_name="Алгебра",
                text="№ 12",
            ),
            Homework(
                class_id=school_class.id,
                due_date=today - timedelta(days=30),
                subject_name="Физика",
                text="старое",
            ),
        ]
    )
    await session.commit()
    editor = v2_tokens["editor"]
    v1 = (await v2.http.get("/api/v1/manage/stats", headers=_auth(editor))).json()
    answer = await v2.both("ClassService/GetClassStats", token=editor)
    stats = answer.message.stats
    assert stats.today == v1["today"]
    assert (stats.lessons_per_week, stats.subjects_count) == (
        v1["lessons_per_week"],
        v1["subjects_count"],
    )
    assert (stats.lessons_per_week, stats.subjects_count) == (3.0, 3)
    assert [(row.name, row.hours) for row in stats.subjects] == [
        (row["name"], row["hours"]) for row in v1["subjects"]
    ]
    assert (stats.homework_open, stats.homework_total) == (
        v1["homework_open"],
        v1["homework_total"],
    )
    assert (stats.homework_open, stats.homework_total) == (1, 2)
    counted = {entry.role: entry.count for entry in stats.members_by_role}
    assert counted == {
        ProtoRole[name.upper()]: count for name, count in v1["members_by_role"].items()
    }
    # Owner first, as the bot lists them; v1 sent a dictionary.
    assert [entry.role for entry in stats.members_by_role] == [
        ProtoRole.OWNER,
        ProtoRole.ADMIN,
        ProtoRole.EDITOR,
        ProtoRole.VIEWER,
    ]
    assert stats.devices_active == v1["devices_active"] == 6
    # v1's overrides_upcoming, under the name v2 gives it.
    assert stats.substitutions_upcoming == v1["overrides_upcoming"] == 0
    assert stats.events_upcoming == v1["events_upcoming"] == 0


async def test_an_update_renames_moves_the_zone_and_logs_each_field(
    v2, v2_tokens, session, school_class
) -> None:
    answer = await v2.rest(
        "ClassService/UpdateClass",
        _update(
            Card(name="9Б", city="Казань", timezone="Asia/Yekaterinburg"),
            "name",
            "city",
            "timezone",
        ),
        token=v2_tokens["admin"],
    )
    assert answer.status == 200
    card = answer.message.school_class
    assert (card.name, card.city, card.timezone) == ("9Б", "Казань", "Asia/Yekaterinburg")
    assert "UTC+5" in card.timezone_label
    stored = await _stored(session, school_class)
    assert (stored.name, stored.timezone) == ("9Б", "Asia/Yekaterinburg")
    assert sorted(await _actions(session)) == ["class.city", "class.name", "class.timezone"]


async def test_moving_the_grade_recomposes_the_name_unless_the_request_names_it(
    v2, v2_tokens, session, school_class
) -> None:
    school_class.grade, school_class.letter = 9, "А"
    await session.commit()
    admin = v2_tokens["admin"]
    moved = await v2.connect(
        "ClassService/UpdateClass", _update(Card(grade=10), "grade"), token=admin
    )
    assert moved.status == 200
    assert moved.message.school_class.name == "10А"
    named = await v2.rest(
        "ClassService/UpdateClass",
        _update(Card(name="11 инженерный", grade=11, letter="Б"), "name", "grade", "letter"),
        token=admin,
    )
    assert (named.message.school_class.name, named.message.school_class.letter) == (
        "11 инженерный",
        "Б",
    )
    assert await _actions(session) == [
        "class.grade",
        "class.name",
        "class.name",
        "class.grade",
        "class.letter",
    ]


async def test_an_update_without_a_mask_changes_only_what_it_sends(
    v2, v2_tokens, session, school_class
) -> None:
    """An invite-only class sent a new city and nothing else stays closed: an
    unset join mode reads as JOIN_MODE_UNSPECIFIED, and without a mask an
    unset field is left alone, neither refused nor cleared."""
    school_class.join_mode = JoinModeRow.INVITE
    await session.commit()
    answer = await v2.rest(
        "ClassService/UpdateClass", _update(Card(city="Казань")), token=v2_tokens["admin"]
    )
    assert answer.status == 200
    card = answer.message.school_class
    assert (card.city, card.school, card.name, card.join_mode) == (
        "Казань",
        "Школа № 1",
        "9А",
        JoinMode.INVITE,
    )
    assert await session.scalar(JOIN_MODE_COLUMN, {"id": school_class.id}) == "INVITE"
    assert await _actions(session) == ["class.city"]


async def test_a_masked_field_left_out_is_cleared_but_the_name_and_zone_are_refused(
    v2, v2_tokens, session, school_class
) -> None:
    admin = v2_tokens["admin"]
    cleared = await v2.connect("ClassService/UpdateClass", _update(Card(), "school"), token=admin)
    assert cleared.status == 200
    assert not cleared.message.school_class.has_field("school")
    for path in ("name", "timezone"):
        answer = await v2.both("ClassService/UpdateClass", _update(Card(), path), token=admin)
        assert answer.reason == "VALIDATION_FAILED", path
        assert [field for field, _ in answer.violations] == [f"school_class.{path}"]
    stored = await _stored(session, school_class)
    assert (stored.school, stored.name, stored.timezone) == (None, "9А", None)
    assert await _actions(session) == ["class.school"]


async def test_a_masked_join_mode_left_unspecified_is_refused_and_the_class_stays_closed(
    v2, v2_tokens, session, school_class
) -> None:
    school_class.join_mode = JoinModeRow.INVITE
    await session.commit()
    answer = await v2.both(
        "ClassService/UpdateClass",
        _update(Card(name="9Б"), "name", "join_mode"),
        token=v2_tokens["admin"],
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "INVALID_ARGUMENT",
        "VALIDATION_FAILED",
    )
    assert answer.violations == [("school_class.join_mode", JOIN_MODE_REFUSED)]
    assert answer.error == JOIN_MODE_REFUSED
    stored = await _stored(session, school_class)
    assert (stored.join_mode, stored.name) == (JoinModeRow.INVITE, "9А")
    assert await _actions(session) == []


async def test_the_join_mode_switches_both_ways(v2, v2_tokens, session, school_class) -> None:
    admin = v2_tokens["admin"]
    closed = await v2.rest(
        "ClassService/UpdateClass",
        _update(Card(join_mode=JoinMode.INVITE), "join_mode"),
        token=admin,
    )
    assert closed.message.school_class.join_mode == JoinMode.INVITE
    # The column holds the member's name, as SQLAlchemy stores an enum.
    assert await session.scalar(JOIN_MODE_COLUMN, {"id": school_class.id}) == "INVITE"
    opened = await v2.connect(
        "ClassService/UpdateClass", _update(Card(join_mode=JoinMode.OPEN), "join_mode"), token=admin
    )
    assert opened.message.school_class.join_mode == JoinMode.OPEN
    assert await session.scalar(JOIN_MODE_COLUMN, {"id": school_class.id}) == "OPEN"
    assert await _actions(session) == ["access.join_mode", "access.join_mode"]


async def test_an_unknown_zone_is_refused_on_its_field_in_v1_s_words(
    v2, v2_tokens, session, school_class
) -> None:
    admin = v2_tokens["admin"]
    v1 = await v2.http.patch(
        "/api/v1/manage/class",
        json={"name": "9Б", "timezone": "Mars/Olympus"},
        headers=_auth(admin),
    )
    answer = await v2.both(
        "ClassService/UpdateClass",
        _update(Card(name="9Б", timezone="Mars/Olympus"), "name", "timezone"),
        token=admin,
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "INVALID_ARGUMENT",
        "VALIDATION_FAILED",
    )
    assert answer.violations == [("school_class.timezone", wording.UNKNOWN_TIMEZONE_DETAIL)]
    assert answer.error == v1.json()["detail"] == wording.UNKNOWN_TIMEZONE_DETAIL
    assert v1.status_code == 422
    # The zone is asked about before anything is written, the name included.
    assert (await _stored(session, school_class)).name == "9А"
    assert await _actions(session) == []


async def test_a_mask_naming_a_field_the_method_does_not_change_is_refused_on_it(
    v2, v2_tokens, session
) -> None:
    for paths in (["join_code"], ["name", "members"], ["*"]):
        answer = await v2.both(
            "ClassService/UpdateClass", _update(Card(name="9Б"), *paths), token=v2_tokens["admin"]
        )
        assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED"), paths
        assert answer.violations == [("update_mask", NOT_CHANGEABLE)]
    assert await _actions(session) == []


async def test_the_card_with_its_join_code_is_never_cached(v2, v2_tokens) -> None:
    admin = v2_tokens["admin"]
    read = await v2.rest("ClassService/GetClass", token=admin)
    written = await v2.rest(
        "ClassService/UpdateClass", _update(Card(city="Казань"), "city"), token=admin
    )
    assert (read.status, written.status) == (200, 200)
    assert read.message.school_class.join_code == "TEST42"
    assert read.headers["cache-control"] == written.headers["cache-control"] == "private, no-store"


async def test_a_confirmation_that_is_not_the_name_is_refused_on_its_field(
    v2, v2_tokens, session, school_class
) -> None:
    owner = v2_tokens["owner"]
    v1 = await v2.http.request(
        "DELETE", "/api/v1/manage/class", json={"confirm_name": "9а"}, headers=_auth(owner)
    )
    answer = await v2.both(
        "ClassService/DeleteClass", DeleteClassRequest(confirmation="9а"), token=owner
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "INVALID_ARGUMENT",
        "VALIDATION_FAILED",
    )
    assert answer.violations == [("confirmation", CONFIRMATION_MISMATCH)]
    assert answer.error == CONFIRMATION_MISMATCH
    assert v1.status_code == 422
    assert await _stored(session, school_class) is not None


@pytest.mark.parametrize("transport", ["rest", "connect"])
async def test_the_owner_deletes_the_class_and_their_next_call_is_refused(
    v2, v2_tokens, session, school_class, transport
) -> None:
    """Every device token goes with the class, the owner's own included: the
    call answers, and the next one is ``DEVICE_TOKEN_INVALID``, as v1's next
    request was a 401. Asked once per transport, because a second delete would
    find no class. The name is typed back give or take the spaces around it."""
    owner = v2_tokens["owner"]
    call = getattr(v2, transport)
    answer = await call(
        "ClassService/DeleteClass", DeleteClassRequest(confirmation=" 9А "), token=owner
    )
    assert answer.status == 200
    assert await _stored(session, school_class) is None
    after = await v2.both("MeService/GetMe", token=owner)
    assert (after.status, after.reason) == (401, "DEVICE_TOKEN_INVALID")
