"""``ListAuditEntries``: v1's ``GET /manage/log`` over v2, a page token in place of an offset.

The log is v1's, line for line, with ``at`` an instant instead of the class's
wall time. The token names the last line a page served, so a line written
between two page turns moves nothing on the next page, and a token this list
did not hand out is refused on its field without being repeated
(``docs/specs/2026-10-05-server-v2-3b-plan.md``, Ruling 4).
"""

from __future__ import annotations

import base64
from datetime import datetime, timedelta

from app.contract.lessons.v2.audit_pb import ListAuditEntriesRequest
from app.models import AuditEntry, BotUser, Role, SchoolClass
from app.rpc.audit import PAGE_SIZE_REFUSED, PAGE_TOKEN_REFUSED

START = datetime(2026, 9, 1, 8, 0)
LAST_SEEN = "UPDATE device_tokens SET last_seen_at=?"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _lines(session, school_class, count: int, *, author: int | None = None) -> None:
    """``count`` lines a minute apart, oldest first: «строка 0» onwards."""
    session.add_all(
        AuditEntry(
            class_id=school_class.id,
            telegram_id=author,
            action="test.line",
            summary=f"строка {n}",
            created_at=START + timedelta(minutes=n),
        )
        for n in range(count)
    )
    await session.commit()


def _summaries(answer) -> list[str]:
    return [entry.summary for entry in answer.message.audit_entries]


def _token_of(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


async def test_the_log_is_v1_s_log_newest_first_with_its_authors(
    v2, v2_tokens, session, school_class
) -> None:
    session.add(
        BotUser(telegram_id=2006, class_id=school_class.id, role=Role.EDITOR, username="anna")
    )
    await session.commit()
    await _lines(session, school_class, 3, author=2006)
    # A line the system wrote, and one by somebody who has left the class.
    session.add(
        AuditEntry(
            class_id=school_class.id,
            action="test.system",
            summary="без автора",
            created_at=START + timedelta(hours=1),
        )
    )
    session.add(
        AuditEntry(
            class_id=school_class.id,
            telegram_id=2999,
            action="test.left",
            summary="ушедший",
            created_at=START + timedelta(hours=2),
        )
    )
    await session.commit()
    admin = v2_tokens["admin"]
    v1 = (
        await v2.http.get("/api/v1/manage/log", params={"limit": 100}, headers=_auth(admin))
    ).json()

    answer = await v2.both("AuditService/ListAuditEntries", token=admin)
    entries = answer.message.audit_entries
    assert [entry.id for entry in entries] == [row["id"] for row in v1["entries"]]
    for mine, theirs in zip(entries, v1["entries"], strict=True):
        assert (mine.action, mine.summary) == (theirs["action"], theirs["summary"])
        assert (mine.who if mine.has_field("who") else None) == theirs["who"]
        # v1 wrote the class's wall time; v2 writes the instant.
        on_the_wall = mine.at.to_datetime().astimezone(school_class.tz).replace(tzinfo=None)
        assert on_the_wall == datetime.fromisoformat(theirs["at"])
    assert _summaries(answer) == ["ушедший", "без автора", "строка 2", "строка 1", "строка 0"]
    assert [entry.who for entry in entries if entry.has_field("who")] == ["@anna"] * 3
    assert answer.message.next_page_token == ""


async def test_a_page_is_thirty_lines_and_its_token_brings_the_rest(
    v2, v2_tokens, session, school_class
) -> None:
    await _lines(session, school_class, 35)
    admin = v2_tokens["admin"]
    first = await v2.both("AuditService/ListAuditEntries", token=admin)
    assert _summaries(first) == [f"строка {n}" for n in range(34, 4, -1)]
    token = first.message.next_page_token
    assert token
    second = await v2.both(
        "AuditService/ListAuditEntries", ListAuditEntriesRequest(page_token=token), token=admin
    )
    assert _summaries(second) == [f"строка {n}" for n in range(4, -1, -1)]
    assert second.message.next_page_token == ""


async def test_a_page_size_is_kept_and_one_above_a_hundred_is_a_hundred(
    v2, v2_tokens, session, school_class
) -> None:
    await _lines(session, school_class, 120)
    admin = v2_tokens["admin"]
    two = await v2.both(
        "AuditService/ListAuditEntries", ListAuditEntriesRequest(page_size=2), token=admin
    )
    assert _summaries(two) == ["строка 119", "строка 118"]
    assert two.message.next_page_token
    many = await v2.both(
        "AuditService/ListAuditEntries", ListAuditEntriesRequest(page_size=500), token=admin
    )
    assert len(many.message.audit_entries) == 100
    assert many.message.next_page_token


async def test_a_line_written_between_two_pages_repeats_none_and_skips_none(
    v2, v2_tokens, session, school_class
) -> None:
    await _lines(session, school_class, 4)
    admin = v2_tokens["admin"]
    first = await v2.both(
        "AuditService/ListAuditEntries", ListAuditEntriesRequest(page_size=2), token=admin
    )
    assert _summaries(first) == ["строка 3", "строка 2"]
    session.add(
        AuditEntry(
            class_id=school_class.id,
            action="test.line",
            summary="новая",
            created_at=START + timedelta(hours=1),
        )
    )
    await session.commit()
    second = await v2.both(
        "AuditService/ListAuditEntries",
        ListAuditEntriesRequest(page_size=2, page_token=first.message.next_page_token),
        token=admin,
    )
    assert _summaries(second) == ["строка 1", "строка 0"]
    assert second.message.next_page_token == ""
    fresh = await v2.both(
        "AuditService/ListAuditEntries", ListAuditEntriesRequest(page_size=2), token=admin
    )
    assert _summaries(fresh) == ["новая", "строка 3"]


async def test_a_token_this_list_did_not_give_is_refused_on_its_field(
    v2, v2_tokens, session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    theirs = AuditEntry(class_id=other.id, action="test.line", summary="чужая")
    session.add(theirs)
    await session.commit()
    await _lines(session, school_class, 3)
    for bad in (
        "not a token",
        "@@@@",
        "x" * 65,
        _token_of(b"audit:999999"),
        _token_of(b"audit:0"),
        _token_of(b"audit:-4"),
        _token_of(b"class:1"),
        _token_of(f"audit:{theirs.id}".encode()),
    ):
        answer = await v2.both(
            "AuditService/ListAuditEntries",
            ListAuditEntriesRequest(page_token=bad),
            token=v2_tokens["admin"],
        )
        assert (answer.status, answer.code, answer.reason) == (
            400,
            "INVALID_ARGUMENT",
            "VALIDATION_FAILED",
        ), bad
        assert answer.violations == [("page_token", PAGE_TOKEN_REFUSED)]
        assert answer.error == PAGE_TOKEN_REFUSED


async def test_a_negative_page_size_is_refused_on_its_field(v2, v2_tokens) -> None:
    answer = await v2.both(
        "AuditService/ListAuditEntries",
        ListAuditEntriesRequest(page_size=-1),
        token=v2_tokens["admin"],
    )
    assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED")
    assert answer.violations == [("page_size", PAGE_SIZE_REFUSED)]


async def test_the_log_writes_nothing_but_the_last_seen(
    v2, v2_tokens, session, school_class, statement_writes
) -> None:
    await _lines(session, school_class, 3)
    with statement_writes() as seen:
        answer = await v2.both(
            "AuditService/ListAuditEntries",
            ListAuditEntriesRequest(page_size=2),
            token=v2_tokens["admin"],
        )
    assert answer.status == 200
    assert len(answer.message.audit_entries) == 2
    assert all(statement.startswith(LAST_SEEN) for statement in seen), seen
