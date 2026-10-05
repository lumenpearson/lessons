"""``CreateDevice``: v1's ``POST /join`` over v2, by the same flow and on the same budget.

Every refusal v1's ``/join`` tests is asked here too, with v1's own words,
and the throttle is asked across the two versions: a caller who alternates
them, or opens a second connection from the same address, draws on one
budget (``docs/specs/2026-10-05-server-v2-design.md``, decision 11).
"""

from __future__ import annotations

import httpx
from sqlalchemy import func, select

from app import wording
from app.contract.lessons.v2.device_pb import CreateDeviceRequest, CreateDeviceResponse
from app.main import app
from app.models import AuditEntry, DeviceToken, JoinAttempt, JoinMode, SchoolClass
from app.schemas import JoinRequest
from app.security import MAX_DEVICES_PER_CLASS, hash_token, join_limiter
from app.services import device_invites

WRONG = CreateDeviceRequest(code="NOSUCH99")


async def _attempts(session) -> int:
    return await session.scalar(select(func.count()).select_from(JoinAttempt)) or 0


async def test_the_class_code_buys_a_token_and_rest_says_201(v2, school_class) -> None:
    answer = await v2.both(
        "DeviceService/CreateDevice",
        CreateDeviceRequest(code="test42", device_name="Pixel 8"),
        differ=("token",),
    )
    assert answer.status == 201
    response = answer.message
    assert (response.class_id, response.class_name) == (school_class.id, "9А")
    assert response.school == "Школа № 1"
    assert response.timezone == school_class.timezone_name
    assert not response.has_field("diary")

    rest_token = (
        await v2.rest("DeviceService/CreateDevice", CreateDeviceRequest(code="TEST42"))
    ).message.token
    me = await v2.both("MeService/GetMe", token=rest_token)
    assert me.message.me.linked is False


async def test_the_answer_is_v1_s_answer(v2, school_class) -> None:
    v1 = (await v2.http.post("/api/v1/join", json={"code": "TEST42"})).json()
    v2_answer = (
        await v2.rest("DeviceService/CreateDevice", CreateDeviceRequest(code="TEST42"))
    ).message
    assert (v2_answer.class_id, v2_answer.class_name, v2_answer.school, v2_answer.timezone) == (
        v1["class_id"],
        v1["class_name"],
        v1["school"],
        v1["timezone"],
    )


async def test_a_personal_code_links_the_phone_and_writes_the_journal(
    v2, session, school_class
) -> None:
    code = await device_invites.mint(session, telegram_id=2007, class_id=school_class.id)
    await session.commit()
    answer = await v2.rest("DeviceService/CreateDevice", CreateDeviceRequest(code=code))
    assert answer.status == 201
    device = await session.scalar(
        select(DeviceToken).where(DeviceToken.token_hash == hash_token(answer.message.token))
    )
    assert device.telegram_id == 2007 and device.linked_at is not None
    assert await session.scalar(select(func.count()).select_from(AuditEntry)) == 1

    again = await v2.connect("DeviceService/CreateDevice", CreateDeviceRequest(code=code))
    assert (again.code, again.reason) == ("NOT_FOUND", "JOIN_CODE_UNKNOWN")


async def test_a_wrong_code_is_refused_in_v1_s_words_and_stays_counted(v2, session) -> None:
    v1 = await v2.http.post("/api/v1/join", json={"code": "NOSUCH99"})
    before = await _attempts(session)
    answer = await v2.both("DeviceService/CreateDevice", WRONG)
    assert (answer.status, answer.code, answer.reason) == (404, "NOT_FOUND", "JOIN_CODE_UNKNOWN")
    assert answer.error == v1.json()["detail"] == wording.JOIN_UNKNOWN_CODE_DETAIL
    # Refused, and the call rolled back — and both attempts are still counted,
    # because the throttle commits before the code is looked at.
    assert await _attempts(session) == before + 2


async def test_an_invite_only_class_refuses_its_code_and_does_not_count_it(v2, session) -> None:
    session.add(SchoolClass(name="9Б", join_code="LOCKED12", join_mode=JoinMode.INVITE))
    await session.commit()
    before = await _attempts(session)
    answer = await v2.both("DeviceService/CreateDevice", CreateDeviceRequest(code="LOCKED12"))
    assert (answer.status, answer.code, answer.reason) == (
        403,
        "PERMISSION_DENIED",
        "CLASS_INVITE_ONLY",
    )
    assert answer.error == wording.JOIN_INVITE_ONLY_DETAIL
    assert await _attempts(session) == before


async def test_a_full_class_refuses_its_code_with_the_limit_and_does_not_count_it(
    v2, session, school_class
) -> None:
    session.add_all(
        DeviceToken(token_hash=hash_token(f"full-{n}"), class_id=school_class.id)
        for n in range(MAX_DEVICES_PER_CLASS)
    )
    await session.commit()
    before = await _attempts(session)
    answer = await v2.both("DeviceService/CreateDevice", CreateDeviceRequest(code="TEST42"))
    assert (answer.status, answer.code, answer.reason) == (
        429,
        "RESOURCE_EXHAUSTED",
        "DEVICE_LIMIT_REACHED",
    )
    assert answer.metadata == {"limit": str(MAX_DEVICES_PER_CLASS)}
    assert answer.error == wording.JOIN_DEVICE_LIMIT_DETAIL
    assert await _attempts(session) == before


async def test_a_code_v1_would_reject_is_rejected_and_not_counted(v2, session) -> None:
    before = await _attempts(session)
    for code in ("ab", "    ", "X" * 17):
        answer = await v2.both("DeviceService/CreateDevice", CreateDeviceRequest(code=code))
        assert (answer.code, answer.reason) == ("INVALID_ARGUMENT", "VALIDATION_FAILED"), code
        assert [field for field, _ in answer.violations] == ["code"]
        assert (await v2.http.post("/api/v1/join", json={"code": code})).status_code == 422
    assert await _attempts(session) == before


async def test_v1_and_v2_draw_on_one_budget(v2) -> None:
    """Alternating versions does not double a caller's wrong guesses."""
    for attempt in range(join_limiter.limit):
        if attempt % 2:
            assert (
                await v2.http.post("/api/v1/join", json={"code": "NOSUCH99"})
            ).status_code == 404
        else:
            assert (
                await v2.rest("DeviceService/CreateDevice", WRONG)
            ).reason == "JOIN_CODE_UNKNOWN"

    # Each transport on its own, not `both`: the seconds left are counted at
    # each call, and two calls a millisecond apart may straddle a second.
    rest = await v2.rest("DeviceService/CreateDevice", WRONG)
    connect = await v2.connect("DeviceService/CreateDevice", WRONG)
    for refused in (rest, connect):
        assert (refused.code, refused.reason) == ("RESOURCE_EXHAUSTED", "THROTTLED")
        assert refused.error == wording.JOIN_THROTTLED_DETAIL
        seconds = int(refused.metadata["retry_after_seconds"])
        assert seconds >= 1 and refused.retry_seconds == seconds
    assert rest.status == 429
    assert rest.headers["retry-after"] == rest.metadata["retry_after_seconds"]
    assert (await v2.http.post("/api/v1/join", json={"code": "NOSUCH99"})).status_code == 429


async def test_two_connections_from_one_address_are_one_caller(v2) -> None:
    """``connectrpc`` reports the peer as ``host:port``; the port is stripped,
    so a second connection is not a second budget."""
    ports = (40001, 40002)
    clients = [
        httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app, client=("198.51.100.20", port)),
            base_url="http://test",
        )
        for port in ports
    ]
    try:
        for attempt in range(join_limiter.limit):
            response = await clients[attempt % 2].post(
                "/api/rpc/lessons.v2.DeviceService/CreateDevice",
                content=WRONG.to_json(),
                headers={"Content-Type": "application/json"},
            )
            assert response.status_code == 404
        last = await clients[0].post(
            "/api/rpc/lessons.v2.DeviceService/CreateDevice",
            content=WRONG.to_json(),
            headers={"Content-Type": "application/json"},
        )
        assert last.status_code == 429
        assert last.json()["code"] == "resource_exhausted"
    finally:
        for client in clients:
            await client.aclose()


async def test_a_correct_code_is_never_counted(v2, school_class) -> None:
    for _ in range(join_limiter.limit + 2):
        answer = await v2.connect("DeviceService/CreateDevice", CreateDeviceRequest(code="TEST42"))
        assert answer.status == 200


async def _v1_and_v2_diary(v2) -> tuple[dict | None, CreateDeviceResponse]:
    v1 = (await v2.http.post("/api/v1/join", json={"code": "TEST42"})).json()["diary"]
    answer = (
        await v2.rest("DeviceService/CreateDevice", CreateDeviceRequest(code="TEST42"))
    ).message
    return v1, answer


async def test_an_unbound_class_has_no_diary_in_either_version(v2, school_class) -> None:
    v1, answer = await _v1_and_v2_diary(v2)
    assert v1 is None and not answer.has_field("diary")


async def test_a_petersburg_class_reports_its_diary_as_v1_does(v2, session, school_class) -> None:
    school_class.diary_provider = "petersburg"
    await session.commit()
    v1, answer = await _v1_and_v2_diary(v2)
    assert v1["provider"] == "petersburg" == answer.diary.provider
    assert v1["region"] is None and not answer.diary.has_field("region")
    assert v1["school_id"] is None and not answer.diary.has_field("school_id")


async def test_a_netschool_class_reports_its_diary_as_v1_does(v2, session, school_class) -> None:
    school_class.diary_provider = "netschool"
    school_class.diary_region = "amur"
    school_class.diary_school_id = 11
    school_class.diary_school_name = "Школа № 3"
    await session.commit()
    v1, answer = await _v1_and_v2_diary(v2)
    assert v1 is not None, "the region must be usable for this test to mean anything"
    diary = answer.diary
    assert (diary.provider, diary.region, diary.school_id, diary.school_name) == (
        v1["provider"],
        v1["region"],
        v1["school_id"],
        v1["school_name"],
    )
    assert (diary.region, diary.school_id, diary.school_name) == ("amur", 11, "Школа № 3")


async def test_a_device_name_past_v1_s_limit_is_rejected_and_not_counted(v2, session) -> None:
    limit = JoinRequest.model_fields["device_name"].metadata[0].max_length
    name = "SECRET-NAME-" + "n" * limit
    before = await _attempts(session)
    answer = await v2.both(
        "DeviceService/CreateDevice", CreateDeviceRequest(code="TEST42", device_name=name)
    )
    assert (answer.code, answer.reason) == ("INVALID_ARGUMENT", "VALIDATION_FAILED")
    assert [field for field, _ in answer.violations] in (["device_name"], ["deviceName"])
    assert "SECRET-NAME" not in answer.body.decode()
    v1 = await v2.http.post("/api/v1/join", json={"code": "TEST42", "device_name": name})
    assert v1.status_code == 422
    assert await _attempts(session) == before


async def test_the_answer_that_carries_a_token_is_never_cached(v2, school_class) -> None:
    rest = await v2.rest("DeviceService/CreateDevice", CreateDeviceRequest(code="TEST42"))
    assert rest.status == 201
    assert rest.headers["cache-control"] == "private, no-store"
