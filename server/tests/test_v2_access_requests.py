"""``AccessRequestService``'s read: everybody waiting for a role in the class.

v1's ``GET /manage/requests`` over v2, through the same services: the open
requests of the class, oldest first, each with who asked as an admin reads
them (``classes.member_names``), and when, as an instant where v1 wrote the
class's wall time. A read writes nothing
(``docs/specs/2026-10-05-server-v2-design.md``, decision 10).
"""

from __future__ import annotations

from datetime import UTC

from sqlalchemy import select

from app.contract.lessons.v2.options_pb import Role as ProtoRole
from app.models import AccessRequest, BotUser, Role, SchoolClass


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _asks(
    session, school_class, telegram_id: int, *, note: str | None = None, status: str = "pending"
) -> AccessRequest:
    request = AccessRequest(
        class_id=school_class.id,
        telegram_id=telegram_id,
        requested_role=Role.EDITOR,
        status=status,
        message=note,
    )
    session.add(request)
    await session.commit()
    return request


async def test_the_requests_are_v1_s_oldest_first(v2, v2_tokens, session, school_class) -> None:
    session.add(
        BotUser(telegram_id=7702, class_id=school_class.id, role=Role.VIEWER, username="masha")
    )
    await session.commit()
    first = await _asks(session, school_class, 7701, note="я староста")
    second = await _asks(session, school_class, 7702)
    admin = v2_tokens["admin"]
    v1 = (await v2.http.get("/api/v1/manage/requests", headers=_auth(admin))).json()
    answer = await v2.both("AccessRequestService/ListAccessRequests", token=admin)
    rows = answer.message.access_requests
    assert [row.id for row in rows] == [row["id"] for row in v1] == [first.id, second.id]
    # A stranger by the number, a member by the name the class knows.
    assert [row.who for row in rows] == [row["who"] for row in v1] == ["7701", "@masha"]
    assert [row.requested_role for row in rows] == [ProtoRole.EDITOR, ProtoRole.EDITOR]
    assert [row["requested_role"] for row in v1] == ["editor", "editor"]
    # A note left is carried; none is the field unset, where v1 wrote null.
    notes = [row.message if row.has_field("message") else None for row in rows]
    assert notes == [row["message"] for row in v1] == ["я староста", None]
    # An instant, where v1 wrote the class's wall time with no zone.
    stamps = await session.scalars(select(AccessRequest.created_at).order_by(AccessRequest.id))
    assert [row.created_at.to_datetime() for row in rows] == [
        stamp.replace(tzinfo=UTC) for stamp in stamps
    ]


async def test_only_this_class_s_open_requests_are_listed(
    v2, v2_tokens, session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    session.add(
        AccessRequest(
            class_id=other.id, telegram_id=7703, requested_role=Role.EDITOR, status="pending"
        )
    )
    await session.commit()
    await _asks(session, school_class, 7704, status="approved")
    await _asks(session, school_class, 7705, status="declined")
    waiting = await _asks(session, school_class, 7701)
    answer = await v2.both("AccessRequestService/ListAccessRequests", token=v2_tokens["admin"])
    assert [row.id for row in answer.message.access_requests] == [waiting.id]


async def test_reading_the_requests_writes_nothing_but_the_last_seen(
    v2, v2_tokens, session, school_class, statement_writes, unexpected_writes, last_seen_rule
) -> None:
    await _asks(session, school_class, 7701)
    with statement_writes() as seen:
        answer = await v2.both("AccessRequestService/ListAccessRequests", token=v2_tokens["admin"])
    assert answer.status == 200
    assert len(answer.message.access_requests) == 1
    # A write happened, the phone's last call, and nothing else did.
    assert seen
    assert unexpected_writes(seen) == []
    assert all(last_seen_rule.match(statement) for statement in seen), seen
