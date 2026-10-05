"""The generic gate: its table against the contract, and every check it makes, in order.

``gate.TABLE`` is compared with the descriptors, read here independently of
``rpc/methods.py``, for all 76 methods. The checks themselves run against one
real method of each kind of credential and least role — most of them methods
3a does not serve yet, which is the point: the gate is the code every later
method will stand behind, and it is held before they arrive
(``docs/specs/2026-10-05-server-v2-design.md``, decision 3). Over HTTP, on both
paths, the gate is held by ``test_v2_reads.py`` for the methods 3a serves.
"""

from __future__ import annotations

import importlib
import pkgutil
from datetime import datetime

import pytest
from sqlalchemy import select

import app.contract.lessons.v2 as contract_v2
from app.config import get_settings
from app.contract.lessons.v2 import options_pb
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.models import DiarySession, SchoolClass
from app.rpc import gate
from app.rpc.errors import Refusal
from app.rpc.methods import METHODS
from app.security import hash_token
from app.services.diary import DiaryDisabled, find_session


def _declared() -> dict[str, tuple[int, int | None]]:
    """Every method's (auth, min_role) as the generated descriptors declare them."""
    found: dict[str, tuple[int, int | None]] = {}
    for info in pkgutil.iter_modules(contract_v2.__path__):
        if not info.name.endswith("_pb"):
            continue
        module = importlib.import_module(f"{contract_v2.__name__}.{info.name}")
        for service in module.desc().services:
            for method in service.methods:
                options = method.proto.options
                role = (
                    int(options[options_pb.ext_min_role])
                    if options_pb.ext_min_role in options
                    else None
                )
                found[f"{service.type_name}/{method.name}"] = (
                    int(options[options_pb.ext_auth]),
                    role,
                )
    return found


def test_the_gate_table_is_the_contract_for_all_76_methods() -> None:
    table = {
        key: (int(auth), None if role is None else int(role))
        for key, (auth, role) in gate.TABLE.items()
    }
    assert len(table) == 76
    assert table == _declared()


def test_every_method_reads_its_own_messages() -> None:
    """``rpc/methods.py`` finds each class by name in its file's module; this
    holds that the name found is the descriptor the method declares."""
    for key, method in METHODS.items():
        assert method.input.desc().type_name == f"lessons.v2.{method.name}Request", key
        assert method.output.desc().type_name == f"lessons.v2.{method.name}Response", key
    streams = [key for key, method in METHODS.items() if method.streaming]
    assert streams == ["lessons.v2.WatchService/WatchClass"]
    assert METHODS["lessons.v2.WatchService/WatchClass"].binding is None


# ---- the client version (decision 9) ----------------------------------------


def _version(monkeypatch, minimum: int, value: str | None) -> int | None:
    settings = get_settings()
    monkeypatch.setattr(settings, "min_client_version", minimum)
    headers = [] if value is None else [("X-Lessons-Client", value)]
    return gate.client_version(headers, settings)


def test_without_a_minimum_the_version_is_read_and_never_refused(monkeypatch) -> None:
    assert _version(monkeypatch, 0, None) is None
    assert _version(monkeypatch, 0, "512") == 512
    assert _version(monkeypatch, 0, "2100000000") == 2_100_000_000
    assert _version(monkeypatch, 0, "2100000001") is None
    assert _version(monkeypatch, 0, "a lot") is None
    assert _version(monkeypatch, 0, "0") is None


def test_with_a_minimum_an_older_client_is_told_to_update(monkeypatch) -> None:
    with pytest.raises(Refusal) as refused:
        _version(monkeypatch, 40, "39")
    assert refused.value.reason is ErrorReason.CLIENT_TOO_OLD
    assert refused.value.metadata == {"min_version": "40"}
    assert _version(monkeypatch, 40, "40") == 40
    assert _version(monkeypatch, 40, "2100000000") == 2_100_000_000


def test_with_a_minimum_a_missing_header_is_let_through(monkeypatch) -> None:
    assert _version(monkeypatch, 40, None) is None


@pytest.mark.parametrize("value", ["", "abc", "0", "-3", "4.5", "١٢٣", "2100000001", "9" * 11])
def test_with_a_minimum_a_header_that_is_no_version_code_is_invalid(monkeypatch, value) -> None:
    with pytest.raises(Refusal) as refused:
        _version(monkeypatch, 40, value)
    assert refused.value.reason is ErrorReason.VALIDATION_FAILED
    assert refused.value.violations[0][0] == "X-Lessons-Client"


# ---- the bearer, the link and the role, in order ---------------------------


async def _admit(session, key: str, token: str | None, *, version: str | None = None):
    headers = []
    if token is not None:
        headers.append(("Authorization", f"Bearer {token}"))
    if version is not None:
        headers.append(("X-Lessons-Client", version))
    return await gate.admit(METHODS[f"lessons.v2.{key}"], session, get_settings(), headers)


async def _refusal(session, key: str, token: str | None, **kw) -> Refusal:
    with pytest.raises(Refusal) as refused:
        await _admit(session, key, token, **kw)
    return refused.value


async def test_a_method_that_takes_no_credential_ignores_one(session, v2_tokens) -> None:
    admitted = await _admit(session, "DeviceService/CreateDevice", "not-a-token")
    assert admitted.device is None and admitted.diary is None


async def test_a_device_method_refuses_a_missing_and_a_foreign_bearer(session, v2_tokens) -> None:
    missing = await _refusal(session, "ScheduleService/GetScheduleWindow", None)
    assert (missing.reason, missing.message) == (
        ErrorReason.DEVICE_TOKEN_INVALID,
        "Missing bearer token",
    )
    foreign = await _refusal(session, "ScheduleService/GetScheduleWindow", v2_tokens["diary"])
    assert (foreign.reason, foreign.message) == (ErrorReason.DEVICE_TOKEN_INVALID, "Invalid token")


async def test_a_viewer_method_admits_an_unlinked_phone_with_no_role(session, v2_tokens) -> None:
    admitted = await _admit(session, "ScheduleService/GetScheduleWindow", v2_tokens["unlinked"])
    assert admitted.device is not None and admitted.school_class is not None
    assert admitted.role is None


async def test_a_linked_method_refuses_an_unlinked_phone(session, v2_tokens) -> None:
    refusal = await _refusal(session, "MeService/GetCalendarFeed", v2_tokens["unlinked"])
    assert (refusal.reason, refusal.message) == (
        ErrorReason.DEVICE_NOT_LINKED,
        "device is not linked",
    )
    admitted = await _admit(session, "MeService/GetCalendarFeed", v2_tokens["viewer"])
    assert admitted.role is not None and admitted.role.value == "viewer"


async def test_a_stranger_is_admitted_without_a_role_where_none_is_asked(
    session, v2_tokens
) -> None:
    """Linked to an account that is no member: a link is all a viewer or a
    linked method asks for, and the role it answers with is none."""
    for key in ("ScheduleService/GetScheduleWindow", "MeService/GetCalendarFeed"):
        admitted = await _admit(session, key, v2_tokens["stranger"])
        assert admitted.device is not None and admitted.role is None, key
    refusal = await _refusal(session, "HomeworkService/CreateHomework", v2_tokens["stranger"])
    assert refusal.reason is ErrorReason.ROLE_REQUIRED


async def test_a_device_whose_class_is_gone_is_refused(session, v2_tokens, monkeypatch) -> None:
    """The foreign key cascades a class's devices away, so the gate's own
    answer is reached only in the instant between the two reads; the class
    lookup is made to miss to hold what it says then."""
    real_get = session.get

    async def get(entity, *args, **kwargs):
        return None if entity is SchoolClass else await real_get(entity, *args, **kwargs)

    monkeypatch.setattr(session, "get", get)
    refusal = await _refusal(session, "ScheduleService/GetScheduleWindow", v2_tokens["viewer"])
    assert (refusal.reason, refusal.message) == (
        ErrorReason.RESOURCE_NOT_FOUND,
        "Class no longer exists",
    )
    assert refusal.metadata == {"resource": "class"}


@pytest.mark.parametrize(
    ("key", "below", "at", "needed"),
    [
        ("HomeworkService/CreateHomework", "viewer", "editor", "ROLE_EDITOR"),
        ("ClassService/UpdateClass", "editor", "admin", "ROLE_ADMIN"),
        ("ClassService/DeleteClass", "admin", "owner", "ROLE_OWNER"),
    ],
)
async def test_a_role_method_asks_for_a_link_first_and_then_the_role(
    session, v2_tokens, key, below, at, needed
) -> None:
    unlinked = await _refusal(session, key, v2_tokens["unlinked"])
    assert unlinked.reason is ErrorReason.DEVICE_NOT_LINKED

    stranger = await _refusal(session, key, v2_tokens["stranger"])
    assert stranger.reason is ErrorReason.ROLE_REQUIRED

    under = await _refusal(session, key, v2_tokens[below])
    assert under.reason is ErrorReason.ROLE_REQUIRED
    assert under.metadata == {"role": needed}
    assert under.message == f"{needed.removeprefix('ROLE_').lower()} role required"

    admitted = await _admit(session, key, v2_tokens[at])
    assert admitted.role is not None and admitted.role.value == at


async def test_a_diary_method_takes_the_diary_bearer_and_no_other(session, v2_tokens) -> None:
    missing = await _refusal(session, "DiaryService/ListStudents", None)
    assert (missing.reason, missing.message) == (
        ErrorReason.DIARY_TOKEN_INVALID,
        "Missing bearer token",
    )
    device = await _refusal(session, "DiaryService/ListStudents", v2_tokens["owner"])
    assert (device.reason, device.message) == (
        ErrorReason.DIARY_TOKEN_INVALID,
        "Diary session is not valid",
    )
    admitted = await _admit(session, "DiaryService/ListStudents", v2_tokens["diary"])
    assert admitted.diary is not None and admitted.device is None


async def test_a_diary_method_without_the_secret_touches_no_session(
    session, v2_tokens, monkeypatch
) -> None:
    """#302: v1 asked for the row first, and an unsealable credential expired it
    for good. The gate asks whether the diary runs at all before it reads a token."""
    # A credential sealed with a key nobody has: ``find_session`` would expire
    # it (the service's own ``diary_enabled`` is true here), so a lookup made
    # before the gate's check is visible as an ``expired_at``. A live row
    # would not show it, which is how the swapped order once passed this test.
    session.add(
        DiarySession(
            token_hash=hash_token("unsealable"),
            upstream_token="sealed with a key nobody has",
            login="parent@example.com",
            provider="petersburg",
        )
    )
    await session.commit()
    looked_up: list[object] = []

    async def spy(*args, **kwargs):
        looked_up.append(args)
        return await find_session(*args, **kwargs)

    monkeypatch.setattr("app.rpc.gate.diary_enabled", lambda: False)
    monkeypatch.setattr("app.rpc.gate.find_session", spy)
    with pytest.raises(DiaryDisabled):
        await _admit(session, "DiaryService/ListStudents", "unsealable")
    assert looked_up == []
    row = await session.scalar(
        select(DiarySession).where(DiarySession.token_hash == hash_token("unsealable"))
    )
    await session.refresh(row)
    assert row.expired_at is None


async def test_the_client_version_is_asked_before_the_bearer(session, monkeypatch) -> None:
    monkeypatch.setattr(get_settings(), "min_client_version", 40)
    refusal = await _refusal(session, "ScheduleService/GetScheduleWindow", None, version="12")
    assert refusal.reason is ErrorReason.CLIENT_TOO_OLD


async def test_a_role_taken_away_is_gone_on_the_next_call(session, v2_tokens) -> None:
    from app.models import BotUser

    key = "ClassService/UpdateClass"
    assert (await _admit(session, key, v2_tokens["admin"])).role.value == "admin"
    member = await session.scalar(select(BotUser).where(BotUser.telegram_id == 2003))
    await session.delete(member)
    await session.commit()
    assert (await _refusal(session, key, v2_tokens["admin"])).reason is ErrorReason.ROLE_REQUIRED


async def test_a_device_call_is_seen(session, v2_tokens) -> None:
    from app.models import DeviceToken
    from app.security import hash_token

    await _admit(session, "MeService/GetMe", v2_tokens["viewer"])
    device = await session.scalar(
        select(DeviceToken).where(DeviceToken.token_hash == hash_token(v2_tokens["viewer"]))
    )
    await session.refresh(device)
    assert isinstance(device.last_seen_at, datetime)
