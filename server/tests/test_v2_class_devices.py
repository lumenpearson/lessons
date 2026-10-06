"""``ClassDeviceService``: v1's ``/manage/devices`` over v2, and each phone's app version.

The list is v1's list, phone for phone, with the stamps as instants instead of
the class's wall time; a revoke repeated changes nothing and logs nothing; an
unlink of a phone with nothing to unlink is refused in v1's words. What v2 adds
is ``client_version``, which v1's answer does not carry
(``docs/specs/2026-10-05-server-v2-3b-plan.md``, Rulings 7 and 12).
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import select

from app import wording
from app.contract.lessons.v2.class_device_pb import (
    ListClassDevicesRequest,
    RevokeClassDeviceRequest,
    UnlinkClassDeviceRequest,
)
from app.contract.lessons.v2.options_pb import Role as ProtoRole
from app.models import AuditEntry, DeviceToken, SchoolClass
from app.security import hash_token


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _device(session, token: str) -> DeviceToken:
    device = await session.scalar(
        select(DeviceToken).where(DeviceToken.token_hash == hash_token(token))
    )
    await session.refresh(device)
    return device


async def _actions(session) -> list[str]:
    return list(await session.scalars(select(AuditEntry.action).order_by(AuditEntry.id)))


def _wall(message, field: str, school_class) -> datetime | None:
    """A v2 instant on the class's clock, as v1 writes it, or ``None`` when unset."""
    if not message.has_field(field):
        return None
    moment = getattr(message, field).to_datetime()
    return moment.astimezone(school_class.tz).replace(tzinfo=None)


def _role(name: str | None) -> ProtoRole:
    return ProtoRole[name.upper()] if name else ProtoRole.UNSPECIFIED


async def test_the_list_is_v1_s_list_in_v2_s_shape(v2, v2_tokens, school_class) -> None:
    admin = v2_tokens["admin"]
    v1 = (await v2.http.get("/api/v1/manage/devices", headers=_auth(admin))).json()
    answer = await v2.both("ClassDeviceService/ListClassDevices", token=admin)
    devices = answer.message.devices
    assert [device.id for device in devices] == [row["id"] for row in v1]
    for mine, theirs in zip(devices, v1, strict=True):
        assert mine.device_name == theirs["device_name"]
        assert mine.linked == theirs["linked"]
        assert (mine.owner if mine.has_field("owner") else None) == theirs["owner"]
        assert mine.role == _role(theirs["role"])
        assert mine.revoked == theirs["revoked"]
        for field in ("created_at", "last_seen_at", "linked_at"):
            expected = datetime.fromisoformat(theirs[field]) if theirs[field] else None
            assert _wall(mine, field, school_class) == expected, field
        # v1's answer keeps its shape (Ruling 7); nobody here has sent a version.
        assert "client_version" not in theirs
        assert not mine.has_field("client_version")
    owners = {d.device_name: d.owner for d in devices if d.has_field("owner")}
    assert owners == {
        "viewer phone": "2001",
        "editor phone": "2002",
        "admin phone": "2003",
        "owner phone": "2004",
    }


async def test_revoked_phones_come_back_only_when_asked(v2, v2_tokens, session) -> None:
    stranger = await _device(session, v2_tokens["stranger"])
    stranger.revoked = True
    await session.commit()
    admin = v2_tokens["admin"]
    plain = await v2.both("ClassDeviceService/ListClassDevices", token=admin)
    every = await v2.both(
        "ClassDeviceService/ListClassDevices",
        ListClassDevicesRequest(include_revoked=True),
        token=admin,
    )
    assert stranger.id not in [device.id for device in plain.message.devices]
    listed = {device.id: device.revoked for device in every.message.devices}
    assert listed[stranger.id] is True
    v1 = await v2.http.get(
        "/api/v1/manage/devices", params={"include_revoked": "true"}, headers=_auth(admin)
    )
    assert sorted(listed) == sorted(row["id"] for row in v1.json())


async def test_a_phone_s_app_version_shows_and_one_that_never_sent_it_shows_none(
    v2, v2_tokens
) -> None:
    await v2.rest(
        "MeService/GetMe", token=v2_tokens["viewer"], headers={"X-Lessons-Client": "412"}
    )
    answer = await v2.both("ClassDeviceService/ListClassDevices", token=v2_tokens["admin"])
    versions = {
        device.device_name: device.client_version if device.has_field("client_version") else None
        for device in answer.message.devices
    }
    assert versions.pop("viewer phone") == 412
    # The admin's own calls sent no header, and nobody else has called at all.
    assert set(versions.values()) == {None}


async def test_revoking_twice_is_one_switch_and_one_line(v2, v2_tokens, session) -> None:
    victim = await _device(session, v2_tokens["viewer"])
    # `both` sends it over REST and then over Connect: the second is a repeat,
    # and it must answer what the first did.
    answer = await v2.both(
        "ClassDeviceService/RevokeClassDevice",
        RevokeClassDeviceRequest(device_id=victim.id),
        token=v2_tokens["admin"],
    )
    device = answer.message.device
    assert (device.id, device.revoked, device.linked) == (victim.id, True, True)
    assert (device.owner, device.role) == ("2001", ProtoRole.VIEWER)
    assert await _actions(session) == ["device.revoke"]
    gone = await v2.both("MeService/GetMe", token=v2_tokens["viewer"])
    assert (gone.status, gone.reason) == (401, "DEVICE_TOKEN_INVALID")


async def test_an_admin_may_revoke_the_phone_in_their_hand(v2, v2_tokens, session) -> None:
    admin = v2_tokens["admin"]
    mine = await _device(session, admin)
    answer = await v2.rest(
        "ClassDeviceService/RevokeClassDevice",
        RevokeClassDeviceRequest(device_id=mine.id),
        token=admin,
    )
    assert answer.status == 200 and answer.message.device.revoked
    after = await v2.connect("ClassDeviceService/ListClassDevices", token=admin)
    assert (after.code, after.reason) == ("UNAUTHENTICATED", "DEVICE_TOKEN_INVALID")


async def test_unlinking_puts_a_phone_back_to_read_only(v2, v2_tokens, session) -> None:
    editor = await _device(session, v2_tokens["editor"])
    request = UnlinkClassDeviceRequest(device_id=editor.id)
    admin = v2_tokens["admin"]
    answer = await v2.rest("ClassDeviceService/UnlinkClassDevice", request, token=admin)
    device = answer.message.device
    assert (device.linked, device.role) == (False, ProtoRole.UNSPECIFIED)
    assert not device.has_field("owner") and not device.has_field("linked_at")
    me = await v2.both("MeService/GetMe", token=v2_tokens["editor"])
    assert (me.message.me.linked, me.message.me.can_edit) == (False, False)
    again = await v2.connect("ClassDeviceService/UnlinkClassDevice", request, token=admin)
    assert again.reason == "CLASS_DEVICE_NOT_LINKED"
    assert await _actions(session) == ["device.unlink"]


async def test_unlinking_a_phone_with_nothing_to_unlink_is_refused_in_v1_s_words(
    v2, v2_tokens, session
) -> None:
    phone = await _device(session, v2_tokens["unlinked"])
    admin = v2_tokens["admin"]
    v1 = await v2.http.post(f"/api/v1/manage/devices/{phone.id}/unlink", headers=_auth(admin))
    answer = await v2.both(
        "ClassDeviceService/UnlinkClassDevice",
        UnlinkClassDeviceRequest(device_id=phone.id),
        token=admin,
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "FAILED_PRECONDITION",
        "CLASS_DEVICE_NOT_LINKED",
    )
    assert answer.metadata == {}
    assert answer.error == v1.json()["detail"] == wording.CLASS_DEVICE_NOT_LINKED_DETAIL
    assert v1.status_code == 409
    assert await _actions(session) == []


async def test_a_phone_of_another_class_is_found_by_neither_write(
    v2, v2_tokens, session
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    stranger = DeviceToken(
        token_hash="x" * 64,
        class_id=other.id,
        device_name="чужой",
        telegram_id=2003,
        linked_at=datetime(2026, 9, 1),
    )
    session.add(stranger)
    await session.commit()
    for name, request in (
        ("RevokeClassDevice", RevokeClassDeviceRequest(device_id=stranger.id)),
        ("UnlinkClassDevice", UnlinkClassDeviceRequest(device_id=stranger.id)),
    ):
        answer = await v2.both(f"ClassDeviceService/{name}", request, token=v2_tokens["admin"])
        assert (answer.status, answer.reason, answer.metadata, answer.error) == (
            404,
            "RESOURCE_NOT_FOUND",
            {"resource": "device"},
            wording.UNKNOWN_DEVICE_DETAIL,
        ), name
    await session.refresh(stranger)
    assert (stranger.revoked, stranger.telegram_id) == (False, 2003)


async def test_the_device_list_writes_nothing_but_the_last_seen(
    v2, v2_tokens, statement_writes, unexpected_writes
) -> None:
    with statement_writes() as seen:
        answer = await v2.both(
            "ClassDeviceService/ListClassDevices",
            ListClassDevicesRequest(include_revoked=True),
            token=v2_tokens["admin"],
        )
    assert answer.status == 200
    assert len(answer.message.devices) == 6
    assert seen
    assert unexpected_writes(seen) == []
