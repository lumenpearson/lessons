"""The reads 3a serves, the gate in front of every method it serves, and reads that write nothing.

``GetMe`` and ``GetDiaryCapabilities`` are asked both ways and against v1's
own answer for the same caller, because «the same answer in v2's shape» is the
promise (``docs/specs/2026-10-05-server-v2-design.md``, decisions 10 and 12).
The gate's behaviour is asked of every method in ``HANDLERS``, so a method a
later task registers is held by the same test the moment it is served.
The statement test counts writes, not rows: the link code, the feed secret
and a relinked subject are all updates, which a row count cannot see.
"""

from __future__ import annotations

import re

import pytest

from app.contract.lessons.v2.me_pb import GetMeRequest, Me
from app.contract.lessons.v2.options_pb import AuthKind
from app.contract.lessons.v2.options_pb import Role as ProtoRole
from app.providers.diary.registry import KEYS
from app.rpc.handlers import HANDLERS
from app.rpc.methods import METHODS

#: The refusals only the gate makes.
GATE_REASONS = {"DEVICE_TOKEN_INVALID", "DIARY_TOKEN_INVALID", "DEVICE_NOT_LINKED", "ROLE_REQUIRED"}

#: The writes a read may make, on purpose (decision 10): telemetry no client
#: observes, a diary credential the upstream rotated and when it was used,
#: and the throttles' and the directory counter's own rows.
ALLOWED_WRITES = (
    re.compile(r"^UPDATE device_tokens SET last_seen_at=\? WHERE"),
    re.compile(r"^UPDATE diary_sessions SET (?:(?:upstream_token|last_used_at)=\?(?:, )?)+ WHERE"),
    re.compile(r"^(?:INSERT INTO|UPDATE|DELETE FROM) (?:join_attempts|usage_counters)\b"),
)


#: The one write GetMe may make.
LAST_SEEN = ALLOWED_WRITES[0]


def unexpected(statements: list[str]) -> list[str]:
    return [s for s in statements if not any(rule.match(s) for rule in ALLOWED_WRITES)]


def _served() -> list[str]:
    return sorted(HANDLERS)


def _request(key: str):
    method = METHODS[key]
    request = method.input()
    if key == "lessons.v2.ScheduleService/GetScheduleWindow":
        request.year = 2026
    return request


async def _call(v2, key: str, token: str | None):
    name = key.removeprefix("lessons.v2.")
    if METHODS[key].streaming:
        return await v2.stream(name, token=token)
    return await v2.both(name, _request(key), token=token, differ=("token", "window.generated_at"))


# ---- the gate, over every method 3a serves, on both paths -------------------


@pytest.mark.parametrize("key", _served())
async def test_the_gate_stands_in_front_of_every_served_method(v2, v2_tokens, key) -> None:
    method = METHODS[key]
    nobody = await _call(v2, key, None)
    wrong = await _call(
        v2, key, v2_tokens["owner"] if method.auth is AuthKind.DIARY else v2_tokens["diary"]
    )
    if method.auth is AuthKind.NONE:
        assert nobody.reason not in GATE_REASONS
        bogus = await _call(v2, key, "not-a-token")
        assert bogus.reason not in GATE_REASONS
        return
    expected = "DIARY_TOKEN_INVALID" if method.auth is AuthKind.DIARY else "DEVICE_TOKEN_INVALID"
    assert (nobody.code, nobody.reason) == ("UNAUTHENTICATED", expected)
    assert (wrong.code, wrong.reason) == ("UNAUTHENTICATED", expected)
    # The gate must also let the right caller through: a gate that refused
    # everyone would pass every assertion below. The handler may still refuse
    # (an empty request is often invalid); only the gate's own reasons count.
    passing = {
        ProtoRole.EDITOR: "editor",
        ProtoRole.ADMIN: "admin",
        ProtoRole.OWNER: "owner",
    }
    if method.auth is AuthKind.DIARY:
        admitted = await _call(v2, key, v2_tokens["diary"])
        assert admitted.reason not in GATE_REASONS
        return
    admitted = await _call(v2, key, v2_tokens[passing.get(method.min_role, "viewer")])
    assert admitted.reason not in GATE_REASONS

    unlinked = await _call(v2, key, v2_tokens["unlinked"])
    linked_needed = method.auth is AuthKind.DEVICE_LINKED or (
        method.min_role is not None and method.min_role > ProtoRole.VIEWER
    )
    if linked_needed:
        assert unlinked.reason == "DEVICE_NOT_LINKED"
    else:
        assert unlinked.reason not in GATE_REASONS
    if method.min_role is not None and method.min_role > ProtoRole.VIEWER:
        below = {ProtoRole.EDITOR: "viewer", ProtoRole.ADMIN: "editor", ProtoRole.OWNER: "admin"}
        refused = await _call(v2, key, v2_tokens[below[method.min_role]])
        assert refused.reason == "ROLE_REQUIRED"


async def test_a_phone_revoked_between_two_calls_is_refused_on_the_second(
    v2, v2_tokens, session
) -> None:
    """The gate reads the token on every call; nothing of the first call's
    answer outlives a revocation made in the bot between the two."""
    from sqlalchemy import update

    from app.models import DeviceToken
    from app.security import hash_token

    token = v2_tokens["editor"]
    assert (await v2.both("MeService/GetMe", token=token)).message.me.can_edit is True
    await session.execute(
        update(DeviceToken)
        .where(DeviceToken.token_hash == hash_token(token))
        .values(revoked=True)
    )
    await session.commit()
    refused = await v2.both("MeService/GetMe", token=token)
    assert (refused.status, refused.reason, refused.error) == (
        401,
        "DEVICE_TOKEN_INVALID",
        "Invalid token",
    )


# ---- GetMe -----------------------------------------------------------------


@pytest.mark.parametrize("who", ["unlinked", "viewer", "editor", "admin", "owner", "stranger"])
async def test_get_me_is_v1_s_me_without_the_code(v2, v2_tokens, who) -> None:
    answer = await v2.both("MeService/GetMe", GetMeRequest(), token=v2_tokens[who])
    v1 = (
        await v2.http.get("/api/v1/me", headers={"Authorization": f"Bearer {v2_tokens[who]}"})
    ).json()
    me = answer.message.me
    assert me.device_name == f"{who} phone"
    assert me.linked == v1["linked"]
    assert me.role == (ProtoRole[v1["role"].upper()] if v1["role"] else ProtoRole.UNSPECIFIED)
    assert me.can_edit == v1["can_edit"]


async def test_get_me_answers_in_binary_too(v2, v2_tokens) -> None:
    answer = await v2.connect("MeService/GetMe", token=v2_tokens["editor"], binary=True)
    assert answer.message.me == Me(
        device_name="editor phone", linked=True, role=ProtoRole.EDITOR, can_edit=True
    )


# ---- GetDiaryCapabilities -------------------------------------------------


async def test_diary_capabilities_are_v1_s_in_v2_s_shape(v2) -> None:
    answer = await v2.both("DiaryService/GetDiaryCapabilities")
    v1 = (await v2.http.get("/api/v1/diary/capabilities")).json()
    capabilities = answer.message.capabilities
    assert capabilities.enabled is v1["enabled"] is True
    providers = {p.provider: p for p in capabilities.providers}
    assert set(providers) == set(KEYS)
    assert list(providers["netschool"].regions) == v1["providers"]["netschool"]["regions"]
    assert list(providers["petersburg"].regions) == []
    assert all(not p.sign_in_methods and not p.features for p in capabilities.providers)
    assert answer.headers["cache-control"] == "private, no-store"


async def test_diary_capabilities_say_when_the_diary_is_off(v2, monkeypatch) -> None:
    monkeypatch.setattr("app.rpc.diary.diary_enabled", lambda: False)
    answer = await v2.both("DiaryService/GetDiaryCapabilities")
    assert answer.message.capabilities.enabled is False


# ---- reads that write nothing (decision 10) --------------------------------
# Each asserts that the method really answered: while it is unserved,
# ``UNIMPLEMENTED`` writes nothing either, and ``both()`` only compares the
# two transports with each other, so the write assertion alone would pass.


@pytest.mark.parametrize("who", ["unlinked", "editor"])
async def test_get_me_writes_nothing_but_the_last_seen(
    v2, v2_tokens, statement_writes, who
) -> None:
    with statement_writes() as seen:
        answer = await v2.both("MeService/GetMe", GetMeRequest(), token=v2_tokens[who])
    assert answer.status == 200
    assert answer.message is not None
    # GetMe's own rule is stricter than the general one: it touches the device's
    # last_seen_at and nothing else — no diary row, no throttle row.
    assert unexpected(seen) == []
    assert all(LAST_SEEN.match(statement) for statement in seen), seen


async def test_diary_capabilities_write_nothing(v2, statement_writes) -> None:
    with statement_writes() as seen:
        answer = await v2.both("DiaryService/GetDiaryCapabilities")
    assert answer.status == 200
    assert answer.message is not None
    assert seen == []


async def test_the_statement_probe_sees_the_write_v1_makes_on_a_read(
    v2, v2_tokens, statement_writes
) -> None:
    """Held here rather than trusted: v1's ``/me`` mints a link code for an
    unlinked phone — an UPDATE, which a count of rows would never see."""
    with statement_writes() as seen:
        await v2.http.get(
            "/api/v1/me", headers={"Authorization": f"Bearer {v2_tokens['unlinked']}"}
        )
    assert any("link_code" in statement for statement in unexpected(seen))
