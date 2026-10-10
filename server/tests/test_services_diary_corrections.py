"""The diary's corrections in ``services/``: written by whoever calls, under one set of rules.

``services/diary_corrections`` committed inside itself, which v2 cannot use:
``BatchUpdateCorrections`` lands whole or not at all, and only ``invoke``
commits, once (``docs/specs/2026-10-05-server-v2-design.md``, decision 4). Its
writes now leave the commit to their caller — v1's routes commit after the
call — and the one retry that relied on a failing commit, two writers racing
for one field, concedes inside a savepoint instead. The rules v1's routes held
are the service's too: what a child with no scope may do, and which
corrections are refused, in v1's order (decision 2). ``test_diary_api.py`` and
``test_diary_corrections_per_child.py``, untouched, are the proof that v1's
answers did not move: each of their writes is read back by a request of its
own, which sees only what was committed.
"""

from __future__ import annotations

from datetime import date
from typing import Any

import httpx
import pytest
from sqlalchemy import func, select

from app import wording
from app.crypto import seal
from app.db import SessionLocal
from app.models import DiaryOverride, DiarySession
from app.providers.diary.models import DiaryLesson, HomeworkItem
from app.providers.petersburg import client as pbclient
from app.security import hash_token
from app.services import diary_corrections as service
from app.services import diary_overrides as overrides

SCOPE = "CHILD:petersburg"
TARGET = "lesson:2026-09-15:n1:Алгебра"
CHILDREN = "/api/journal/person/related-child-list"


async def _committed(statement: Any) -> Any:
    """What a session of its own reads: only what is committed."""
    async with SessionLocal() as fresh:
        return await fresh.scalar(statement)


async def test_a_correction_written_or_replaced_is_the_caller_s_to_commit(session) -> None:
    written = await service.put_override(session, SCOPE, 4021, TARGET, "room", "204", "12")
    # Flushed and read back, so that the caller can answer with it.
    assert written.id is not None and written.updated_at is not None
    await session.rollback()
    assert await _committed(select(func.count()).select_from(DiaryOverride)) == 0

    kept = await service.put_override(session, SCOPE, 4021, TARGET, "room", "204", "12")
    await session.commit()
    kept_id = kept.id
    replaced = await service.put_override(session, SCOPE, 4021, TARGET, "room", "301", None)
    assert (replaced.id, replaced.value, replaced.original) == (kept_id, "301", None)
    await session.rollback()
    assert await _committed(select(DiaryOverride.value)) == "204"


async def test_a_reset_and_a_clear_are_the_caller_s_to_commit(session) -> None:
    for field, value in (("room", "204"), ("teacher", "Иванова И. И.")):
        await service.put_override(session, SCOPE, 4021, TARGET, field, value, None)
    await session.commit()
    stored = select(func.count()).select_from(DiaryOverride)

    assert await service.drop_override(session, SCOPE, 4021, TARGET, "room") is True
    await session.rollback()
    assert await _committed(stored) == 2

    assert await service.drop_overrides(session, SCOPE, 4021) == 2
    await session.rollback()
    assert await _committed(stored) == 2

    assert await service.drop_overrides(session, SCOPE, 4021) == 2
    await session.commit()
    assert await _committed(stored) == 0


async def test_two_writers_racing_for_one_field_land_on_one_row_and_commit_nothing(
    session, monkeypatch
) -> None:
    """Both parents correct one room in the same instant. The other's row lands
    between this write's check and its insert; the unique constraint catches
    the insert inside its savepoint, this write becomes the update it would
    have been a moment later, and nothing is committed until the caller
    commits: the old retry rolled the caller's whole transaction back, and
    committed twice itself."""
    checks: list[object] = []
    check = session.scalar

    async def checked_then_taken(statement, *args, **kwargs):
        found = await check(statement, *args, **kwargs)
        checks.append(found)
        if len(checks) == 1:
            # The other parent's write, between this one's check and its insert.
            async with SessionLocal() as other:
                other.add(
                    DiaryOverride(
                        login=SCOPE,
                        student_id=4021,
                        target=TARGET,
                        field="room",
                        value="204",
                        original="12",
                    )
                )
                await other.commit()
        return found

    commits: list[str] = []
    commit = session.commit

    async def counted() -> None:
        commits.append("commit")
        await commit()

    monkeypatch.setattr(session, "scalar", checked_then_taken)
    monkeypatch.setattr(session, "commit", counted)

    written = await service.put_override(session, SCOPE, 4021, TARGET, "room", "301", "12")
    assert written.value == "301"
    assert checks[0] is None and checks[1] is not None
    assert commits == []
    assert await _committed(select(DiaryOverride.value)) == "204"
    await session.commit()
    async with SessionLocal() as fresh:
        assert list(await fresh.scalars(select(DiaryOverride.value))) == ["301"]


async def test_a_lost_race_inside_a_batch_keeps_the_batch_s_earlier_writes(
    session, monkeypatch
) -> None:
    """``correct`` writes a batch one ``put_override`` at a time, inside the
    one transaction the caller — v1's route, or ``invoke`` — commits once. The
    second correction here loses its own race and concedes inside its own
    savepoint; the first correction's write, flushed moments earlier in the
    very same transaction, must go on with it rather than be undone, which is
    the promise at the top of ``put_override``'s ``except`` (``:196-197``):
    "the caller's transaction, and what it wrote before this, go on"."""
    other = "hw:id:2"
    async with SessionLocal() as parent2:
        # The other parent's row, committed before this batch even starts:
        # once the batch's own first write opens the transaction (below), the
        # #373 listener has it holding SQLite's one write lock, so a second
        # writer could no longer land here mid-batch — it has to have landed
        # first.
        parent2.add(
            DiaryOverride(
                login=SCOPE,
                student_id=4021,
                target=other,
                field="text",
                value="папина правка",
                original=None,
            )
        )
        await parent2.commit()

    check = session.scalar
    calls: list[object] = []

    async def miss_once(statement, *args, **kwargs):
        calls.append(statement)
        if len(calls) == 2:
            # The 2nd correction's own lookup: answered `None` once, as if
            # this session had not yet seen the row `parent2` just committed,
            # which is what sends `put_override` into the insert path and
            # onto the unique constraint.
            return None
        return await check(statement, *args, **kwargs)

    monkeypatch.setattr(session, "scalar", miss_once)

    rows = await service.correct(
        session,
        SCOPE,
        4021,
        [
            service.Correction("hw:id:1", "text", "мамина правка"),
            service.Correction(other, "text", "правка из этой пачки"),
        ],
    )
    # Both answered with this batch's values, not the one it raced against.
    assert [(row.target, row.value) for row in rows] == [
        ("hw:id:1", "мамина правка"),
        (other, "правка из этой пачки"),
    ]

    async def _seen() -> dict[str, str]:
        async with SessionLocal() as fresh:
            rows = await fresh.scalars(
                select(DiaryOverride).where(DiaryOverride.student_id == 4021)
            )
            return {row.target: row.value for row in rows}

    # Before the caller commits, a fresh session sees only what `parent2`
    # committed: the batch's own writes are still inside this transaction.
    assert await _seen() == {other: "папина правка"}
    await session.commit()
    assert await _seen() == {
        "hw:id:1": "мамина правка",
        other: "правка из этой пачки",
    }


# ---- the rules v1's routes held ---------------------------------------------


async def test_a_child_who_can_have_none_is_refused_a_write_and_given_nothing_else(
    session,
) -> None:
    """``scope`` ``None``: a child the diary lists outside its own numbering. Its
    diary is shown as it came; only a write is refused — and refused for the
    child before the correction is looked at, in v1's order."""
    await service.put_override(session, SCOPE, 4021, TARGET, "room", "204", None)
    await session.commit()
    with pytest.raises(service.CorrectionsUnavailable):
        await service.correct(session, None, 4021, [service.Correction("nonsense", "text", "")])
    assert await service.listed(session, None, 4021) == []
    assert await service.reset(session, None, 4021, [(TARGET, "room")]) == 0
    assert await service.clear(session, None, 4021) == 0
    await session.commit()
    assert await _committed(select(DiaryOverride.value)) == "204"


async def test_each_correction_is_checked_before_any_is_written(session) -> None:
    """The target and the field before the value, as v1 checks them, and every
    correction before the first is written."""
    good = service.Correction(TARGET, "room", "204", "12")
    for refused, error in (
        (service.Correction("nonsense", "text", ""), overrides.UnknownTarget),
        (service.Correction("hw:id:77", "room", "204"), overrides.UnsupportedField),
        (service.Correction("hw:id:77", "text", "   "), overrides.EmptyNotAllowed),
    ):
        with pytest.raises(error):
            await service.correct(session, SCOPE, 4021, [good, refused])
        assert await session.scalar(select(func.count()).select_from(DiaryOverride)) == 0


async def test_a_batch_is_written_in_order_and_a_key_named_twice_keeps_the_later(
    session,
) -> None:
    rows = await service.correct(
        session,
        SCOPE,
        4021,
        [
            service.Correction(TARGET, "room", "204", "12"),
            service.Correction("hw:id:77", "text", "§ 3, задачи 1–5", "§ 3"),
            service.Correction(TARGET, "room", "301", "12"),
        ],
    )
    # Each as it stands once all are written: the repeated key is one row.
    assert [(row.target, row.field, row.value) for row in rows] == [
        (TARGET, "room", "301"),
        ("hw:id:77", "text", "§ 3, задачи 1–5"),
        (TARGET, "room", "301"),
    ]
    assert rows[0] is rows[2]
    await session.commit()
    listed = await service.listed(session, SCOPE, 4021)
    assert [(row.target, row.field, row.value) for row in listed] == [
        ("hw:id:77", "text", "§ 3, задачи 1–5"),
        (TARGET, "room", "301"),
    ]


async def test_a_reset_counts_what_it_took_off_and_a_key_with_nothing_is_no_error(
    session,
) -> None:
    await service.correct(
        session,
        SCOPE,
        4021,
        [
            service.Correction(TARGET, "room", "204"),
            service.Correction(TARGET, "teacher", "Иванова И. И."),
        ],
    )
    await service.correct(session, SCOPE, 5, [service.Correction(TARGET, "room", "999")])
    await session.commit()
    keys = [(TARGET, "room"), (TARGET, "topic"), (TARGET, "room")]
    assert await service.reset(session, SCOPE, 4021, keys) == 1
    await session.commit()
    assert [row.field for row in await service.listed(session, SCOPE, 4021)] == ["teacher"]
    assert await service.clear(session, SCOPE, 4021) == 1
    await session.commit()
    assert await service.listed(session, SCOPE, 4021) == []
    assert [row.value for row in await service.listed(session, SCOPE, 5)] == ["999"]


@pytest.mark.parametrize(
    "target",
    [
        "hw:id:007",
        "hw:id:²",
        "hw:id:٣",
        "lesson:2026-09-15:n01:Алгебра",
        "lesson:2026-09-15:n²:Алгебра",
        "lesson:2026-09-15:n١:Алгебра",
    ],
)
def test_a_number_the_read_path_never_writes_is_no_target(target) -> None:
    """#393. ``str.isdigit`` took each of these, so each was stored and then
    matched by nothing for ever: the read path writes an id and a lesson
    number as ``str()`` writes an ``int``, and nothing else."""
    field = "text" if target.startswith("hw:") else "room"
    with pytest.raises(overrides.UnknownTarget):
        overrides.check(target, field)


def test_every_number_the_read_path_writes_is_a_target() -> None:
    day = date(2026, 9, 15)
    for item_id in (0, 7, 10, 90210):
        item = HomeworkItem(id=item_id, due_date=day, subject="Алгебра", text="№ 1")
        overrides.check(overrides.homework_target(item), "text")
    for number in (None, 0, 1, 10):
        lesson = DiaryLesson(date=day, number=number, subject="Алгебра")
        overrides.check(overrides.lesson_target(lesson), "room")


async def test_v1_words_each_refusal_in_the_sentences_v2_shares(
    v2, session, FakeUpstream, monkeypatch
) -> None:
    """v1's four 422s, now ``app/wording.py``'s, and a write v1 commits before
    it answers: a request of its own reads it, and then reads it gone."""
    fake = FakeUpstream(
        {
            CHILDREN: {
                "items": [
                    {
                        "identity": {"id": 4021},
                        "firstname": "Пётр",
                        "surname": "Иванов",
                        "educations": [{"education_id": 90210, "group_id": 771}],
                    },
                    {
                        "id": 7,
                        "firstname": "Анна",
                        "surname": "Иванова",
                        "educations": [{"education_id": 90777, "group_id": 772}],
                    },
                ]
            }
        }
    )

    async def shared() -> httpx.AsyncClient:
        return httpx.AsyncClient(
            base_url=pbclient.BASE_URL, transport=httpx.MockTransport(fake.handler)
        )

    monkeypatch.setattr(pbclient, "shared_client", shared)
    session.add(
        DiarySession(
            token_hash=hash_token("v1-corrections"),
            upstream_token=seal("a-jwt"),
            login="parent@example.com",
            provider="petersburg",
        )
    )
    await session.commit()
    headers = {"Authorization": "Bearer v1-corrections"}

    async def put(student: int, target: str, field: str, value: str) -> httpx.Response:
        return await v2.http.put(
            f"/api/v1/diary/students/{student}/overrides",
            headers=headers,
            json={"target": target, "field": field, "value": value},
        )

    for answer, detail in (
        (await put(7, TARGET, "room", "204"), wording.CORRECTIONS_UNAVAILABLE_DETAIL),
        (await put(4021, "hw:id:007", "text", "x"), wording.CORRECTION_TARGET_REFUSED_DETAIL),
        (await put(4021, "hw:id:77", "room", "x"), wording.CORRECTION_FIELD_REFUSED_DETAIL),
        (await put(4021, "hw:id:77", "text", " "), wording.CORRECTION_VALUE_EMPTY_DETAIL),
    ):
        assert (answer.status_code, answer.json()["detail"]) == (422, detail)
    assert (await put(4021, TARGET, "room", "204")).status_code == 200
    assert await _committed(select(DiaryOverride.value)) == "204"
    reset = await v2.http.post(
        "/api/v1/diary/students/4021/overrides/reset",
        headers=headers,
        json={"target": TARGET, "field": "room"},
    )
    assert reset.status_code == 204
    assert await _committed(select(func.count()).select_from(DiaryOverride)) == 0
