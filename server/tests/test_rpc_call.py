"""One call's scope: the commit, the effects after it, and what a refusal keeps.

``invoke`` is driven directly here, with handlers put into ``HANDLERS`` for
the test, so that each rule of decision 4 is asked of the one function both
transports call (``docs/specs/2026-10-05-server-v2-design.md``).
"""

from __future__ import annotations

import logging
import re
from pathlib import Path

import pytest
from connectrpc.code import Code
from connectrpc.errors import ConnectError
from sqlalchemy import func, select

from app.api.deps import caller_bucket
from app.contract.lessons.v2 import school_class_connect
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.school_class_pb import GetClassRequest, GetClassResponse
from app.db import SessionLocal
from app.models import AuditEntry, DeviceToken, DiarySession
from app.rpc import call as call_module
from app.rpc.call import NOT_IMPLEMENTED, invoke
from app.rpc.errors import Refusal
from app.rpc.handlers import HANDLERS
from app.rpc.methods import METHODS
from app.security import hash_token

SERVER = Path(__file__).resolve().parents[1]
GET_CLASS = METHODS["lessons.v2.ClassService/GetClass"]
LIST_STUDENTS = METHODS["lessons.v2.DiaryService/ListStudents"]


def _bearer(token: str) -> list[tuple[str, str]]:
    return [("authorization", f"Bearer {token}")]


async def _audit_lines() -> int:
    async with SessionLocal() as fresh:
        return await fresh.scalar(select(func.count()).select_from(AuditEntry)) or 0


async def test_a_method_with_no_handler_answers_as_the_generated_protocol_does() -> None:
    """Before any gate: no credential is asked of a method nobody serves."""
    with pytest.raises(ConnectError) as protocol:
        await school_class_connect.ClassService.get_class(None, GetClassRequest(), None)
    with pytest.raises(ConnectError) as served:
        await invoke(GET_CLASS, GetClassRequest(), headers=[], peer=None)
    assert served.value.code is protocol.value.code is Code.UNIMPLEMENTED
    assert served.value.message == protocol.value.message == NOT_IMPLEMENTED


async def test_a_success_commits_before_its_effects_run(monkeypatch, v2_tokens, school_class):
    seen: list[int] = []

    async def handler(call, request):
        call.session.add(AuditEntry(class_id=school_class.id, action="test.v2", summary="written"))

        async def effect() -> None:
            # From a session of its own: only a committed row is visible here.
            seen.append(await _audit_lines())

        call.after_commit(effect)
        return GetClassResponse()

    monkeypatch.setitem(HANDLERS, GET_CLASS.key, handler)
    before = await _audit_lines()
    await invoke(GET_CLASS, GetClassRequest(), headers=_bearer(v2_tokens["admin"]), peer=None)
    assert seen == [before + 1]


async def test_a_refusal_rolls_back_and_runs_no_effect(monkeypatch, v2_tokens, school_class):
    ran: list[str] = []

    async def handler(call, request):
        call.session.add(AuditEntry(class_id=school_class.id, action="test.v2", summary="written"))

        async def effect() -> None:
            ran.append("effect")

        call.after_commit(effect)
        raise Refusal(ErrorReason.RESOURCE_NOT_FOUND, "no such thing", resource="class")

    monkeypatch.setitem(HANDLERS, GET_CLASS.key, handler)
    before = await _audit_lines()
    with pytest.raises(ConnectError) as refused:
        await invoke(GET_CLASS, GetClassRequest(), headers=_bearer(v2_tokens["admin"]), peer=None)
    assert refused.value.code is Code.NOT_FOUND
    assert await _audit_lines() == before
    assert ran == []


async def test_an_effect_that_fails_does_not_fail_the_call(monkeypatch, v2_tokens, caplog):
    async def handler(call, request):
        async def effect() -> None:
            raise RuntimeError("Telegram is down")

        call.after_commit(effect)
        return GetClassResponse()

    monkeypatch.setitem(HANDLERS, GET_CLASS.key, handler)
    with caplog.at_level(logging.WARNING, logger="app.rpc.call"):
        answer = await invoke(
            GET_CLASS, GetClassRequest(), headers=_bearer(v2_tokens["admin"]), peer=None
        )
    assert answer == GetClassResponse()
    assert any("after its commit" in record.getMessage() for record in caplog.records)


async def test_a_failure_the_table_does_not_know_is_internal(monkeypatch, v2_tokens):
    async def handler(call, request):
        raise KeyError("a bug")

    monkeypatch.setitem(HANDLERS, GET_CLASS.key, handler)
    with pytest.raises(ConnectError) as failed:
        await invoke(GET_CLASS, GetClassRequest(), headers=_bearer(v2_tokens["admin"]), peer=None)
    assert failed.value.code is Code.INTERNAL
    assert "a bug" not in failed.value.message


async def test_last_seen_is_kept_when_the_call_is_refused(monkeypatch, v2_tokens, session):
    async def handler(call, request):
        raise Refusal(ErrorReason.RESOURCE_NOT_FOUND, "no such thing", resource="class")

    monkeypatch.setitem(HANDLERS, GET_CLASS.key, handler)
    with pytest.raises(ConnectError):
        await invoke(GET_CLASS, GetClassRequest(), headers=_bearer(v2_tokens["editor"]), peer=None)
    device = await session.scalar(
        select(DeviceToken).where(DeviceToken.token_hash == hash_token(v2_tokens["editor"]))
    )
    await session.refresh(device)
    assert device.last_seen_at is not None


async def test_a_dead_diary_credential_stays_expired_when_the_call_is_refused(
    monkeypatch, session, school_class
):
    """``find_session`` expires a row whose credential will not open, and
    commits; the call's rollback does not bring it back."""
    session.add(
        DiarySession(
            token_hash=hash_token("dead-diary"),
            upstream_token="sealed with a key nobody has",
            login="parent@example.com",
            provider="petersburg",
        )
    )
    await session.commit()

    async def handler(call, request):
        raise AssertionError("the gate let a dead session through")

    monkeypatch.setitem(HANDLERS, LIST_STUDENTS.key, handler)
    with pytest.raises(ConnectError) as refused:
        await invoke(
            LIST_STUDENTS,
            LIST_STUDENTS.input(),
            headers=_bearer("dead-diary"),
            peer=None,
        )
    assert refused.value.code is Code.UNAUTHENTICATED
    row = await session.scalar(select(DiarySession))
    await session.refresh(row)
    assert row.expired_at is not None


async def test_a_call_buckets_its_caller_as_v1_does(monkeypatch, v2_tokens):
    buckets: list[str] = []

    async def handler(call, request):
        buckets.append(call.bucket(scope="diary:"))
        return GetClassResponse()

    monkeypatch.setitem(HANDLERS, GET_CLASS.key, handler)
    headers = [*_bearer(v2_tokens["admin"]), ("x-forwarded-for", "198.51.100.7")]
    await invoke(GET_CLASS, GetClassRequest(), headers=headers, peer="203.0.113.9")
    assert buckets == [caller_bucket(headers, "203.0.113.9", scope="diary:")]


def test_no_handler_commits() -> None:
    """The commit is ``invoke``'s: a handler that committed would make a refusal
    after it unable to roll back what came before."""
    offenders = [
        f"{path.relative_to(SERVER).as_posix()}:{number}"
        for folder in ("app/rpc", "app/rest")
        for path in sorted((SERVER / folder).rglob("*.py"))
        if path != Path(call_module.__file__)
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1)
        if re.search(r"\.commit\(", line)
    ]
    assert offenders == []
