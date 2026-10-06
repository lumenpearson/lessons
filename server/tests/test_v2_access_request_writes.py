"""``AccessRequestService``'s answers: yes and no, by v1's rules, and the notice after the commit.

v1's ``POST /manage/requests/{id}/approve`` and ``…/decline`` over v2,
through the same ``requests_service.approve`` and ``decline``: the ladder of
«👥 Доступ», ``RESOURCE_NOT_FOUND`` for a request already answered, and
whoever asked told in Telegram in v1's words. The notice is an effect
(``Call.after_commit``): it goes out once the decision is committed, never on
a refusal, and one Telegram refuses leaves the decision standing
(``docs/specs/2026-10-05-server-v2-design.md``, decision 4 and «Risks»). A
write's success is asked once per transport on fresh data, and its refusals
through ``both`` (the 3b plan, Ruling 17).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any

import pytest
from sqlalchemy import select

from app import telegram_send, wording
from app.config import get_settings
from app.contract.lessons.v2.access_request_pb import (
    ApproveAccessRequestRequest,
    DeclineAccessRequestRequest,
)
from app.contract.lessons.v2.options_pb import Role as ProtoRole
from app.db import SessionLocal
from app.models import AccessRequest, AuditEntry, BotUser, Role, SchoolClass
from app.rpc.access_request import ROLE_REFUSED

ASKER = 7701
OTHER_ASKER = 7702
APPROVE = "AccessRequestService/ApproveAccessRequest"
DECLINE = "AccessRequestService/DeclineAccessRequest"


@dataclass
class _Telegram:
    """The bot ``telegram_send.build_bot`` hands out: what it was asked to
    send, with the request's status as a session of its own read it at that
    moment, so that only a committed decision is seen; how often it was built
    and closed; and whether Telegram refuses."""

    sent: list[tuple[int, str, str | None]] = field(default_factory=list)
    built: int = 0
    closed: int = 0
    refuses: bool = False

    async def send_message(self, chat_id: int, text: str, **_: Any) -> None:
        async with SessionLocal() as fresh:
            status = await fresh.scalar(
                select(AccessRequest.status)
                .where(AccessRequest.telegram_id == chat_id)
                .order_by(AccessRequest.id.desc())
            )
        self.sent.append((chat_id, text, status))
        if self.refuses:
            raise RuntimeError("Forbidden: bot was blocked by the user")

    @property
    def session(self) -> _Telegram:
        return self

    async def close(self) -> None:
        self.closed += 1


@pytest.fixture
def telegram(monkeypatch) -> _Telegram:
    """A deployment with a bot, every send of which the test reads."""
    bot = _Telegram()
    monkeypatch.setattr(get_settings(), "bot_token", "123456:TEST")

    def build() -> _Telegram:
        bot.built += 1
        return bot

    monkeypatch.setattr(telegram_send, "build_bot", build)
    return bot


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _approve(request_id: int, role: ProtoRole = ProtoRole.UNSPECIFIED):
    return ApproveAccessRequestRequest(request_id=request_id, role=role)


async def _asks(session, school_class, telegram_id: int = ASKER, role: Role = Role.EDITOR):
    request = AccessRequest(
        class_id=school_class.id, telegram_id=telegram_id, requested_role=role, status="pending"
    )
    session.add(request)
    await session.commit()
    return request


async def _status(session, request_id: int) -> str | None:
    return await session.scalar(select(AccessRequest.status).where(AccessRequest.id == request_id))


async def _role_of(session, school_class, telegram_id: int) -> Role | None:
    return await session.scalar(
        select(BotUser.role).where(
            BotUser.class_id == school_class.id, BotUser.telegram_id == telegram_id
        )
    )


async def _actions(session) -> list[str]:
    return list(await session.scalars(select(AuditEntry.action).order_by(AuditEntry.id)))


async def test_an_approval_grants_the_role_asked_for_and_tells_the_asker_after_the_commit(
    v2, v2_tokens, session, school_class, telegram
) -> None:
    first = await _asks(session, school_class, ASKER)
    second = await _asks(session, school_class, OTHER_ASKER)
    admin = v2_tokens["admin"]
    rest = await v2.rest(APPROVE, _approve(first.id), token=admin)
    connect = await v2.connect(APPROVE, _approve(second.id), token=admin)
    assert (rest.status, connect.status) == (200, 200)
    assert (rest.message.request_id, rest.message.role, rest.message.who) == (
        first.id,
        ProtoRole.EDITOR,
        str(ASKER),
    )
    assert (connect.message.request_id, connect.message.role) == (second.id, ProtoRole.EDITOR)
    assert await _role_of(session, school_class, ASKER) is Role.EDITOR
    assert await _role_of(session, school_class, OTHER_ASKER) is Role.EDITOR
    assert await _actions(session) == ["access.approve", "access.approve"]
    # Told once each, in v1's words, and only after the commit: a session of
    # the bot's own read the request as approved already.
    assert [(chat, status) for chat, _, status in telegram.sent] == [
        (ASKER, "approved"),
        (OTHER_ASKER, "approved"),
    ]
    assert all("<b>Редактор</b>" in text and "9А" in text for _, text, _ in telegram.sent)
    assert telegram.closed == telegram.built == 2


async def test_an_approval_naming_a_role_grants_that_role(
    v2, v2_tokens, session, school_class, telegram
) -> None:
    asked = await _asks(session, school_class)
    answer = await v2.rest(
        APPROVE,
        _approve(asked.id, ProtoRole.VIEWER),
        token=v2_tokens["admin"],
    )
    assert answer.status == 200
    assert answer.message.role == ProtoRole.VIEWER
    assert await _role_of(session, school_class, ASKER) is Role.VIEWER
    assert [text for _, text, _ in telegram.sent] == [
        "✅ Доступ выдан: <b>Наблюдатель</b> в классе <b>9А</b>. Откройте /start."
    ]


async def test_a_role_at_or_above_the_grantor_s_own_is_refused_and_tells_nobody(
    v2, v2_tokens, session, school_class, telegram
) -> None:
    """Nobody grants at or above their own role, and the owner's role nobody
    grants at all: ``OWNER_IDS`` makes an owner."""
    asked = await _asks(session, school_class)
    admin = v2_tokens["admin"]
    v1 = await v2.http.post(
        f"/api/v1/manage/requests/{asked.id}/approve", json={"role": "admin"}, headers=_auth(admin)
    )
    for token, role in ((admin, ProtoRole.ADMIN), (v2_tokens["owner"], ProtoRole.OWNER)):
        answer = await v2.both(APPROVE, _approve(asked.id, role), token=token)
        assert (answer.status, answer.code, answer.reason) == (
            403,
            "PERMISSION_DENIED",
            "ROLE_GRANT_REFUSED",
        ), role
        assert answer.metadata == {"why": "role_too_high"}
        assert answer.error == v1.json()["detail"] == wording.GRANT_REFUSED_DETAILS["role_too_high"]
    assert v1.status_code == 403
    assert await _status(session, asked.id) == "pending"
    assert await _role_of(session, school_class, ASKER) is None
    assert await _actions(session) == []
    assert (telegram.sent, telegram.built) == ([], 0)


async def test_a_member_at_or_above_the_grantor_is_refused_and_tells_nobody(
    v2, v2_tokens, session, school_class, telegram
) -> None:
    session.add(BotUser(telegram_id=ASKER, class_id=school_class.id, role=Role.ADMIN))
    await session.commit()
    asked = await _asks(session, school_class)
    admin = v2_tokens["admin"]
    v1 = await v2.http.post(f"/api/v1/manage/requests/{asked.id}/approve", headers=_auth(admin))
    answer = await v2.both(APPROVE, _approve(asked.id), token=admin)
    assert (answer.status, answer.reason, answer.metadata) == (
        403,
        "ROLE_GRANT_REFUSED",
        {"why": "member_senior"},
    )
    assert answer.error == v1.json()["detail"] == wording.GRANT_REFUSED_DETAILS["member_senior"]
    assert await _status(session, asked.id) == "pending"
    assert await _role_of(session, school_class, ASKER) is Role.ADMIN
    assert (telegram.sent, telegram.built) == ([], 0)


async def test_an_owner_saying_yes_to_an_admin_s_old_request_answers_the_role_they_keep(
    v2, v2_tokens, session, school_class, telegram
) -> None:
    """Asked for as a viewer, answered after a promotion to admin: the member
    is never lowered, and the answer says what they hold, as the proto does."""
    session.add(
        BotUser(telegram_id=ASKER, class_id=school_class.id, role=Role.ADMIN, username="masha")
    )
    await session.commit()
    asked = await _asks(session, school_class)
    answer = await v2.rest(APPROVE, _approve(asked.id), token=v2_tokens["owner"])
    assert answer.status == 200
    assert (answer.message.role, answer.message.who) == (ProtoRole.ADMIN, "@masha")
    assert await _role_of(session, school_class, ASKER) is Role.ADMIN
    assert await _status(session, asked.id) == "approved"
    assert len(telegram.sent) == 1


async def test_a_request_answered_or_of_another_class_is_not_found_and_tells_nobody(
    v2, v2_tokens, session, school_class, telegram
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    theirs = AccessRequest(
        class_id=other.id, telegram_id=OTHER_ASKER, requested_role=Role.EDITOR, status="pending"
    )
    answered = AccessRequest(
        class_id=school_class.id, telegram_id=ASKER, requested_role=Role.EDITOR, status="declined"
    )
    session.add_all([theirs, answered])
    await session.commit()
    admin = v2_tokens["admin"]
    v1 = await v2.http.post(f"/api/v1/manage/requests/{theirs.id}/approve", headers=_auth(admin))
    assert v1.status_code == 404
    for request_id in (theirs.id, answered.id, 999_999):
        for name, request in (
            ("ApproveAccessRequest", _approve(request_id)),
            ("DeclineAccessRequest", DeclineAccessRequestRequest(request_id=request_id)),
        ):
            answer = await v2.both(f"AccessRequestService/{name}", request, token=admin)
            assert (answer.status, answer.code, answer.reason) == (
                404,
                "NOT_FOUND",
                "RESOURCE_NOT_FOUND",
            ), name
            assert answer.metadata == {"resource": "access_request"}
            assert answer.error == v1.json()["detail"] == wording.UNKNOWN_ACCESS_REQUEST_DETAIL
    assert (await _status(session, theirs.id), await _status(session, answered.id)) == (
        "pending",
        "declined",
    )
    assert await _actions(session) == []
    assert telegram.sent == []


async def test_a_role_no_value_of_the_contract_names_is_refused_on_its_field(
    v2, v2_tokens, session, school_class, telegram
) -> None:
    """A number no value of ``Role`` names reaches the handler in the binary
    encoding, which keeps it, and is refused there. Read as JSON it never
    arrives: both transports parse JSON with unknown fields ignored, and
    proto3's parser then drops an unknown enum value, so such a request reads
    as one naming no role (the 3b plan, «Rulings for 3b-3»)."""
    asked = await _asks(session, school_class)
    answer = await v2.connect(
        APPROVE, _approve(asked.id, ProtoRole(99)), token=v2_tokens["admin"], binary=True
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "INVALID_ARGUMENT",
        "VALIDATION_FAILED",
    )
    assert answer.violations == [("role", ROLE_REFUSED)]
    assert await _status(session, asked.id) == "pending"
    assert telegram.sent == []


async def test_a_decline_on_either_path_closes_the_request_and_tells_the_asker(
    v2, v2_tokens, session, school_class, telegram
) -> None:
    session.add(
        BotUser(
            telegram_id=OTHER_ASKER, class_id=school_class.id, role=Role.VIEWER, username="masha"
        )
    )
    await session.commit()
    first = await _asks(session, school_class, ASKER)
    second = await _asks(session, school_class, OTHER_ASKER)
    admin = v2_tokens["admin"]
    rest = await v2.rest(
        DECLINE,
        DeclineAccessRequestRequest(request_id=first.id),
        token=admin,
    )
    connect = await v2.connect(
        DECLINE,
        DeclineAccessRequestRequest(request_id=second.id),
        token=admin,
    )
    assert (rest.status, connect.status) == (200, 200)
    assert (rest.message.request_id, rest.message.who) == (first.id, str(ASKER))
    assert (connect.message.request_id, connect.message.who) == (second.id, "@masha")
    assert (await _status(session, first.id), await _status(session, second.id)) == (
        "declined",
        "declined",
    )
    # Each keeps what they had: nothing, and a viewer's role.
    assert await _role_of(session, school_class, ASKER) is None
    assert await _role_of(session, school_class, OTHER_ASKER) is Role.VIEWER
    assert await _actions(session) == ["access.decline", "access.decline"]
    assert [(chat, status) for chat, _, status in telegram.sent] == [
        (ASKER, "declined"),
        (OTHER_ASKER, "declined"),
    ]
    assert all("отклонён" in text for _, text, _ in telegram.sent)


async def test_answering_one_request_twice_is_one_decision_and_one_notice(
    v2, v2_tokens, session, school_class, telegram
) -> None:
    """This pins the sequential case — a retry over a flaky network, or two
    admins who happen not to overlap: the second answer finds nothing open,
    whichever it is. Two admins genuinely at once, on the same instant, is
    not reachable from this single-connection test client; that case rests on
    ``requests_service.pending_one``'s row lock, which
    ``test_services_access_requests.py``'s
    ``test_pending_one_locks_the_row_for_update_on_postgres`` holds (#370)."""
    asked = await _asks(session, school_class)
    admin = v2_tokens["admin"]
    first = await v2.rest(APPROVE, _approve(asked.id), token=admin)
    again = await v2.connect(APPROVE, _approve(asked.id), token=admin)
    declined = await v2.connect(
        DECLINE,
        DeclineAccessRequestRequest(request_id=asked.id),
        token=admin,
    )
    assert first.status == 200
    assert (again.reason, declined.reason) == ("RESOURCE_NOT_FOUND", "RESOURCE_NOT_FOUND")
    assert await _status(session, asked.id) == "approved"
    assert await _actions(session) == ["access.approve"]
    assert len(telegram.sent) == 1


async def test_a_notice_telegram_refuses_leaves_the_decision_standing(
    v2, v2_tokens, session, school_class, telegram, caplog
) -> None:
    telegram.refuses = True
    asked = await _asks(session, school_class)
    with caplog.at_level(logging.WARNING, logger="app.telegram_send"):
        answer = await v2.connect(APPROVE, _approve(asked.id), token=v2_tokens["admin"])
    assert answer.status == 200
    assert await _status(session, asked.id) == "approved"
    assert await _role_of(session, school_class, ASKER) is Role.EDITOR
    assert (len(telegram.sent), telegram.closed) == (1, 1)
    assert any(
        f"could not send a message to {ASKER}" in record.getMessage() for record in caplog.records
    )


async def test_a_deployment_with_no_bot_still_decides_and_builds_none(
    v2, v2_tokens, session, school_class, monkeypatch
) -> None:
    built: list[object] = []
    monkeypatch.setattr(telegram_send, "build_bot", lambda: built.append("bot"))
    monkeypatch.setattr(get_settings(), "bot_token", "")
    asked = await _asks(session, school_class)
    answer = await v2.rest(APPROVE, _approve(asked.id), token=v2_tokens["admin"])
    assert answer.status == 200
    assert await _role_of(session, school_class, ASKER) is Role.EDITOR
    assert built == []
