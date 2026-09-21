"""Granting a role from a request: one rule, two shells.

`api/manage.py` and `bot/handlers/manage.py` each used to carry the whole of
it — `can_grant`, the peer guard, create-or-raise, the stamp, the audit line
and the message. They agreed only because somebody kept comparing them, so the
rule now lives in `services/access.py` and this file is where it is tested.
The last test here is the one that keeps it that way: it watches both shells
call the service rather than trusting that they do.
"""

from __future__ import annotations

from types import SimpleNamespace

import pytest
from sqlalchemy import select

from app.db import SessionLocal
from app.models import AccessRequest, AuditEntry, BotUser, Role, SchoolClass
from app.services import access

ASKER = 7701


async def _request(session, school_class: SchoolClass, role: Role = Role.EDITOR) -> AccessRequest:
    request = AccessRequest(
        class_id=school_class.id,
        telegram_id=ASKER,
        requested_role=role,
        status="pending",
    )
    session.add(request)
    await session.commit()
    return request


async def test_a_request_from_somebody_new_creates_the_membership(session, school_class):
    request = await _request(session, school_class)

    member = await access.approve_request(
        session,
        school_class,
        request,
        actor_id=9001,
        actor_role=Role.ADMIN,
        role=Role.EDITOR,
    )
    await session.commit()

    assert member.role is Role.EDITOR and member.granted_by == 9001
    assert request.status == "approved" and request.decided_by == 9001
    assert request.decided_at is not None
    logged = await session.scalar(select(AuditEntry))
    assert logged.action == "access.approve"
    assert "Редактор" in logged.summary


async def test_an_existing_member_is_never_lowered(session, school_class):
    """An admin who asked for EDITOR before being made an admin stays an admin."""
    session.add(BotUser(telegram_id=ASKER, class_id=school_class.id, role=Role.ADMIN))
    await session.commit()
    request = await _request(session, school_class)

    member = await access.approve_request(
        session,
        school_class,
        request,
        actor_id=9001,
        actor_role=Role.OWNER,
        role=Role.EDITOR,
    )

    assert member.role is Role.ADMIN


async def test_nobody_may_grant_a_role_at_or_above_their_own(session, school_class):
    request = await _request(session, school_class, Role.ADMIN)

    with pytest.raises(access.GrantRefused) as refused:
        await access.approve_request(
            session,
            school_class,
            request,
            actor_id=9001,
            actor_role=Role.ADMIN,
            role=Role.ADMIN,
        )

    # Both sentences, because both surfaces say one of them: the bot shows the
    # Russian in an alert, the API sends the English as a 403 detail.
    assert str(refused.value) == "Нельзя выдать роль выше вашей"
    assert refused.value.detail == "cannot grant a role at or above your own"
    assert request.status == "pending"
    assert await session.scalar(select(BotUser).where(BotUser.telegram_id == ASKER)) is None


async def test_a_peer_is_not_yours_to_promote(session, school_class):
    """Without this an admin could raise another admin and be demoted back."""
    session.add(BotUser(telegram_id=ASKER, class_id=school_class.id, role=Role.ADMIN))
    await session.commit()
    request = await _request(session, school_class)

    with pytest.raises(access.GrantRefused) as refused:
        await access.approve_request(
            session,
            school_class,
            request,
            actor_id=9001,
            actor_role=Role.ADMIN,
            role=Role.EDITOR,
        )

    assert str(refused.value) == "Нельзя менять роль этого пользователя"
    assert refused.value.detail == "cannot change this member's role"
    assert request.status == "pending"


async def test_nothing_is_committed_by_the_service(session, school_class):
    """Like the rest of `services/`: the caller commits the grant together with
    its audit line, so a role and the record of who handed it out land
    together or not at all."""
    request = await _request(session, school_class)

    await access.approve_request(
        session,
        school_class,
        request,
        actor_id=9001,
        actor_role=Role.ADMIN,
        role=Role.EDITOR,
    )

    async with SessionLocal() as other:
        assert await other.scalar(select(BotUser).where(BotUser.telegram_id == ASKER)) is None
        assert await other.scalar(select(AuditEntry)) is None


async def test_the_notice_escapes_the_class_name(session, school_class):
    """A class name is typed by an admin, and Telegram refuses the whole
    message on a stray «<» rather than damaging the one tag."""
    school_class.name = "9<А>"

    notice = access.approval_notice(school_class, Role.EDITOR)

    assert "9&lt;А&gt;" in notice and "<b>Редактор</b>" in notice
    assert "<А>" not in notice


async def test_both_shells_go_through_the_service(monkeypatch, session, school_class):
    """The rule is called by both surfaces, not copied into either.

    Watching the call rather than comparing two implementations line by line:
    that comparison is what used to be done by hand, and it is exactly what
    stops being done the week somebody is in a hurry.
    """
    from app.api import manage as api_manage
    from app.bot.handlers import manage as bot_manage

    calls: list[Role] = []
    real = access.approve_request

    async def watched(*args, **kwargs):
        calls.append(kwargs["role"])
        return await real(*args, **kwargs)

    monkeypatch.setattr(access, "approve_request", watched)
    monkeypatch.setattr(api_manage, "_tell", lambda *_, **__: _noop())
    monkeypatch.setattr(bot_manage, "_bot_of", lambda _: None)

    from_bot = await _request(session, school_class)
    await bot_manage.request_approve(
        SimpleNamespace(
            from_user=SimpleNamespace(id=9001),
            message=SimpleNamespace(edit_text=_async_noop, answer=_async_noop),
            answer=_async_noop,
        ),
        SimpleNamespace(action="approve", value=str(from_bot.id)),
        session,
        school_class,
        Role.OWNER,
    )

    from_api = await _request(session, school_class)
    await api_manage.request_approve(
        from_api.id,
        None,
        actor=api_manage.Actor(device=None, telegram_id=9002, role=Role.OWNER),
        school_class=school_class,
        session=session,
    )

    assert calls == [Role.EDITOR, Role.EDITOR]


async def _noop() -> None:
    return None


async def _async_noop(*_args, **_kwargs) -> None:
    return None
