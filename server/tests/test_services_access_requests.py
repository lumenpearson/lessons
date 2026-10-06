"""Answering an access request, in ``services/``: what v1's router held.

v1's ``POST /manage/requests/{id}/approve`` picked the role in its router (none
sent means the one asked for) and put a name to the member it answered with.
v2's ``ApproveAccessRequest`` does both too, so they moved to
``requests_service.approve`` before v2's handler was written, with v1 calling
it (``docs/specs/2026-10-05-server-v2-design.md``, decision 2). A refused grant
now says why, which v2 sends as ``ROLE_GRANT_REFUSED``'s ``why``, and the bot
and v1 keep their words. ``test_api_manage.py`` and ``test_access_service.py``,
untouched, are the proof that neither moved.
"""

from __future__ import annotations

import pytest
from sqlalchemy import select
from sqlalchemy.dialects import postgresql

from app import wording
from app.models import AccessRequest, AuditEntry, BotUser, Role
from app.services import access
from app.services.manage import requests as requests_service

ASKER = 7701


async def _asks(session, school_class, telegram_id: int = ASKER, role: Role = Role.EDITOR):
    request = AccessRequest(
        class_id=school_class.id, telegram_id=telegram_id, requested_role=role, status="pending"
    )
    session.add(request)
    await session.commit()
    return request


async def test_a_refused_grant_says_why_and_keeps_each_shell_s_words(session, school_class) -> None:
    """The fact is ``why``. The bot shows ``str(…)`` and v1 sends ``detail``,
    each the sentence it showed before the fact existed."""
    for_admin = await _asks(session, school_class, role=Role.ADMIN)
    with pytest.raises(access.GrantRefused) as too_high:
        await requests_service.approve(
            session, school_class, for_admin, actor_id=9001, actor_role=Role.ADMIN
        )
    assert too_high.value.why == access.ROLE_TOO_HIGH == "role_too_high"
    assert str(too_high.value) == "Нельзя выдать роль выше вашей"
    assert too_high.value.detail == "cannot grant a role at or above your own"

    session.add(BotUser(telegram_id=7702, class_id=school_class.id, role=Role.ADMIN))
    await session.commit()
    from_a_peer = await _asks(session, school_class, telegram_id=7702)
    with pytest.raises(access.GrantRefused) as senior:
        await requests_service.approve(
            session, school_class, from_a_peer, actor_id=9001, actor_role=Role.ADMIN
        )
    assert senior.value.why == access.MEMBER_SENIOR == "member_senior"
    assert str(senior.value) == "Нельзя менять роль этого пользователя"
    assert senior.value.detail == "cannot change this member's role"

    assert (for_admin.status, from_a_peer.status) == ("pending", "pending")
    assert [row for row in session.new if isinstance(row, AuditEntry)] == []


async def test_an_approval_grants_the_role_asked_for_and_names_the_member(
    session, school_class
) -> None:
    """No role sent is the role asked for, as «✅ Выдать» grants it; a role sent
    goes through the same ladder. The name is the one an admin reads: the
    @username of a member the class knows, else the numeric id."""
    session.add(
        BotUser(telegram_id=ASKER, class_id=school_class.id, role=Role.VIEWER, username="masha")
    )
    await session.commit()
    asked = await _asks(session, school_class)
    approval = await requests_service.approve(
        session, school_class, asked, actor_id=9001, actor_role=Role.ADMIN
    )
    assert (approval.granted, approval.member.role, approval.who) == (
        Role.EDITOR,
        Role.EDITOR,
        "@masha",
    )
    assert asked.status == "approved"

    newcomer = await _asks(session, school_class, telegram_id=7702)
    chosen = await requests_service.approve(
        session, school_class, newcomer, actor_id=9001, actor_role=Role.ADMIN, role=Role.VIEWER
    )
    assert (chosen.granted, chosen.member.role, chosen.who) == (Role.VIEWER, Role.VIEWER, "7702")
    await session.commit()
    actions = await session.scalars(select(AuditEntry.action).order_by(AuditEntry.id))
    assert list(actions) == ["access.approve", "access.approve"]


async def test_an_approval_never_lowers_a_member_above_the_role_asked_for(
    session, school_class
) -> None:
    """A viewer who asked for editor and was made an admin before anybody
    answered stays an admin when the owner says yes to the old request."""
    session.add(
        BotUser(telegram_id=ASKER, class_id=school_class.id, role=Role.ADMIN, full_name="Маша")
    )
    await session.commit()
    asked = await _asks(session, school_class)
    approval = await requests_service.approve(
        session, school_class, asked, actor_id=9001, actor_role=Role.OWNER
    )
    assert (approval.member.role, approval.who) == (Role.ADMIN, "Маша")
    assert asked.status == "approved"


def test_the_requests_sentences_are_v1_s() -> None:
    assert wording.UNKNOWN_ACCESS_REQUEST_DETAIL == "Unknown request"
    assert wording.GRANT_REFUSED_DETAILS == {
        "role_too_high": "cannot grant a role at or above your own",
        "member_senior": "cannot change this member's role",
    }
    assert wording.GRANT_REFUSED_ALERTS == {
        "role_too_high": "Нельзя выдать роль выше вашей",
        "member_senior": "Нельзя менять роль этого пользователя",
    }


async def test_pending_one_locks_the_row_for_update_on_postgres() -> None:
    """Two admins answering the same request at once, or an approval racing a
    decline, must not both find it pending: the statement ``pending_one``
    issues has to lock the row, so that on PostgreSQL a second concurrent
    reader waits for the first answer's commit and then re-checks
    ``status == "pending"`` against the row as it was just left — finding
    none — rather than reading the old, still-pending value under its own
    snapshot (#370). The statement is captured without a database and
    compiled for the PostgreSQL dialect, because SQLite ignores ``FOR
    UPDATE`` (it serialises writes on its own) and so cannot tell a locking
    statement from a plain one.
    """
    captured: list[object] = []

    class _Session:
        async def scalar(self, statement):
            captured.append(statement)
            return None

    await requests_service.pending_one(_Session(), class_id=1, request_id=1)
    assert captured
    compiled = str(captured[0].compile(dialect=postgresql.dialect()))
    assert compiled.rstrip().endswith("FOR UPDATE")
