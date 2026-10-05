"""Each phone's last app version: recorded by v2's gate, never by v1.

The gate writes ``device_tokens.client_version`` in the statement that writes
``last_seen_at``, on the same fifteen-minute clock, and only when a usable
``X-Lessons-Client`` came (``docs/specs/2026-10-05-server-v2-design.md``,
decision 15). So «reads write nothing» grows by one column of an update it
already allows and by nothing else; a call without the header keeps what was
known, and a v1 request never writes it.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from sqlalchemy import select, update

from app.config import get_settings
from app.models import DeviceToken
from app.security import hash_token

#: The one statement a touch writes, with a version and without one.
SEEN_WITH_VERSION = (
    "UPDATE device_tokens SET last_seen_at=?, client_version=? WHERE device_tokens.id = ?"
)
SEEN = "UPDATE device_tokens SET last_seen_at=? WHERE device_tokens.id = ?"


async def _device(session, token: str) -> DeviceToken:
    device = await session.scalar(
        select(DeviceToken).where(DeviceToken.token_hash == hash_token(token))
    )
    await session.refresh(device)
    return device


async def _seen_long_ago(session, token: str) -> None:
    """Put the phone's last call sixteen minutes back, past the clock."""
    await session.execute(
        update(DeviceToken)
        .where(DeviceToken.token_hash == hash_token(token))
        .values(last_seen_at=datetime.now(UTC).replace(tzinfo=None) - timedelta(minutes=16))
    )
    await session.commit()


async def test_a_v2_call_records_the_version_beside_the_last_seen_in_one_statement(
    v2, v2_tokens, session, statement_writes
) -> None:
    with statement_writes() as seen:
        answer = await v2.both(
            "MeService/GetMe", token=v2_tokens["viewer"], headers={"X-Lessons-Client": "412"}
        )
    assert answer.status == 200
    # REST touched the phone; Connect, a moment later, found it seen.
    assert seen == [SEEN_WITH_VERSION]
    device = await _device(session, v2_tokens["viewer"])
    assert device.client_version == 412
    assert device.last_seen_at is not None


async def test_a_new_version_inside_the_fifteen_minutes_waits_for_the_next_touch(
    v2, v2_tokens, session
) -> None:
    token = v2_tokens["viewer"]
    await v2.rest("MeService/GetMe", token=token, headers={"X-Lessons-Client": "412"})
    await v2.rest("MeService/GetMe", token=token, headers={"X-Lessons-Client": "413"})
    assert (await _device(session, token)).client_version == 412
    await _seen_long_ago(session, token)
    await v2.rest("MeService/GetMe", token=token, headers={"X-Lessons-Client": "413"})
    assert (await _device(session, token)).client_version == 413


async def test_a_call_without_the_header_keeps_the_version_it_had(
    v2, v2_tokens, session, statement_writes
) -> None:
    token = v2_tokens["viewer"]
    await v2.rest("MeService/GetMe", token=token, headers={"X-Lessons-Client": "412"})
    await _seen_long_ago(session, token)
    with statement_writes() as seen:
        answer = await v2.rest("MeService/GetMe", token=token)
    assert answer.status == 200
    assert seen == [SEEN]
    assert (await _device(session, token)).client_version == 412


async def test_a_header_that_is_no_version_is_ignored_without_a_minimum(
    v2, v2_tokens, session
) -> None:
    token = v2_tokens["viewer"]
    for junk in ("abc", "0", "-5", "4.1", "2100000001", "1" * 11):
        await _seen_long_ago(session, token)
        answer = await v2.rest(
            "MeService/GetMe", token=token, headers={"X-Lessons-Client": junk}
        )
        assert answer.status == 200, junk
    assert (await _device(session, token)).client_version is None


async def test_a_phone_told_to_update_is_neither_seen_nor_recorded(
    v2, v2_tokens, session, monkeypatch
) -> None:
    """The version is read before the bearer, so its refusal touches nothing."""
    monkeypatch.setattr(get_settings(), "min_client_version", 500)
    answer = await v2.both(
        "MeService/GetMe", token=v2_tokens["viewer"], headers={"X-Lessons-Client": "412"}
    )
    assert (answer.code, answer.reason) == ("FAILED_PRECONDITION", "CLIENT_TOO_OLD")
    device = await _device(session, v2_tokens["viewer"])
    assert (device.client_version, device.last_seen_at) == (None, None)


async def test_v1_never_records_a_version(v2, v2_tokens, session, statement_writes) -> None:
    token = v2_tokens["viewer"]
    with statement_writes() as seen:
        response = await v2.http.get(
            "/api/v1/me",
            headers={"Authorization": f"Bearer {token}", "X-Lessons-Client": "412"},
        )
    assert response.status_code == 200
    assert seen == [SEEN]
    assert (await _device(session, token)).client_version is None
