"""``ListCorrections`` and ``BatchUpdateCorrections``: v1's ``GET`` and ``PUT /overrides`` over v2.

Through ``services/diary_corrections``, so both versions keep one set of
corrections per child, refuse the same ones in the same words and answer the
same rows (``docs/specs/2026-10-05-server-v2-design.md``, decisions 2, 5 and
14). A batch lands whole or not at all, by ``invoke``'s one commit; its
success is asked once per transport (the 3b plan, Ruling 17), and its
refusals, which write nothing, through ``both``. Nothing here reaches a real
diary: Petersburg's pooled client and «Сетевой город»'s are each replaced by
one over ``httpx.MockTransport``.
"""

from __future__ import annotations

from datetime import date, datetime
from typing import Any

import httpx
import pytest
from sqlalchemy import func, select

from app import wording
from app.contract.lessons.v2.diary_pb import (
    BatchUpdateCorrectionsRequest,
    CorrectionUpdate,
    ListCorrectionsRequest,
    ListScheduleDaysRequest,
)
from app.db import SessionLocal
from app.models import DiaryOverride, DiarySession
from app.providers.diary import http as diary_http
from app.providers.netschool import client as nsclient
from app.providers.petersburg import client as pbclient
from app.providers.petersburg import provider as pbprovider
from app.rpc import values
from app.rpc.diary import CORRECTIONS_MAX, TOO_MANY_CORRECTIONS
from app.services import diary_corrections

LIST = "DiaryService/ListCorrections"
BATCH = "DiaryService/BatchUpdateCorrections"
SCOPE = "CHILD:petersburg"
CHILDREN = "/api/journal/person/related-child-list"
SCHEDULE = "/api/journal/schedule/list-by-education"
LESSON = "lesson:2026-09-15:n1:Алгебра"
SECRET = "Pa55w0rd-s3cr3t-Hunter2"
PUPILS = [
    {
        "identity": {"id": 4021},
        "firstname": "Пётр",
        "surname": "Иванов",
        "educations": [{"education_id": 90210, "group_id": 771, "group_name": "9А"}],
    },
    # Listed by a plain id, outside the diary's own numbering: a child who can
    # have no corrections at all.
    {
        "id": 7,
        "firstname": "Анна",
        "surname": "Иванова",
        "educations": [{"education_id": 90777, "group_id": 772, "group_name": "5Б"}],
    },
]


@pytest.fixture
def petersburg(monkeypatch, FakeUpstream):
    """Petersburg's upstream on 14 September 2026: two pupils, and one lesson."""
    fake = FakeUpstream(
        {
            CHILDREN: {"items": PUPILS},
            SCHEDULE: {
                "items": [
                    {"date": "15.09.2026", "subject_name": "Алгебра", "number": 1, "office": "12"}
                ]
            },
        }
    )

    async def shared() -> httpx.AsyncClient:
        return httpx.AsyncClient(
            base_url=pbclient.BASE_URL, transport=httpx.MockTransport(fake.handler)
        )

    monkeypatch.setattr(pbclient, "shared_client", shared)
    monkeypatch.setattr(pbprovider, "today", lambda: date(2026, 9, 14))
    return fake


@pytest.fixture
def netschool(monkeypatch) -> None:
    """«Сетевой город»'s regional server: the bootstrap's four answers."""
    answers: dict[str, Any] = {
        "/webapi/student/diary/init": {
            "students": [{"studentId": 11, "nickName": "Иванов Иван", "classId": 3}]
        },
        "/webapi/years/current": {"id": 2026, "startDate": "2026-09-01", "endDate": "2027-05-31"},
        "/webapi/context": {"organizationName": "Гимназия № 7"},
        "/webapi/grade/assignment/types": [{"id": 3, "name": "Домашнее задание"}],
    }

    def handler(request: httpx.Request) -> httpx.Response:
        found = answers.get(request.url.path)
        return httpx.Response(404, json={}) if found is None else httpx.Response(200, json=found)

    async def shared() -> httpx.AsyncClient:
        return httpx.AsyncClient(
            transport=httpx.MockTransport(handler), cookies=diary_http.NoCookieJar()
        )

    monkeypatch.setattr(nsclient, "shared_client", shared)


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _batch(*corrections: CorrectionUpdate, student_id: int = 4021) -> BatchUpdateCorrectionsRequest:
    return BatchUpdateCorrectionsRequest(student_id=student_id, corrections=list(corrections))


async def _correct(
    session, target: str, field: str, value: str, *, original: str | None = None, student_id=4021
) -> None:
    session.add(
        DiaryOverride(
            login=SCOPE,
            student_id=student_id,
            target=target,
            field=field,
            value=value,
            original=original,
        )
    )
    await session.commit()


async def _stored() -> list[tuple[str, int, str, str, str]]:
    """Every correction, as a session of its own reads it: only what is committed."""
    async with SessionLocal() as fresh:
        rows = await fresh.scalars(select(DiaryOverride).order_by(DiaryOverride.id))
        return [(row.login, row.student_id, row.target, row.field, row.value) for row in rows]


async def test_the_corrections_are_v1_s_in_v2_s_shape(v2, v2_tokens, session, petersburg) -> None:
    await _correct(session, LESSON, "room", "204", original="12")
    await _correct(session, "hw:id:77", "text", "§ 3, задачи 1–5")
    await _correct(session, LESSON, "room", "чужой ребёнок", student_id=5)
    token = v2_tokens["diary"]
    answer = await v2.both(LIST, ListCorrectionsRequest(student_id=4021), token=token)
    v1 = (await v2.http.get("/api/v1/diary/students/4021/overrides", headers=_auth(token))).json()
    listed = answer.message.corrections
    assert (
        [
            (
                item.target,
                item.field,
                item.value,
                item.original_when_written if item.has_field("original_when_written") else None,
            )
            for item in listed
        ]
        == [(row["target"], row["field"], row["value"], row["original_when_written"]) for row in v1]
        == [("hw:id:77", "text", "§ 3, задачи 1–5", None), (LESSON, "room", "204", "12")]
    )
    # An instant: v1's naive stamp, which is UTC.
    assert [item.updated_at for item in listed] == [
        values.instant(datetime.fromisoformat(row["updated_at"])) for row in v1
    ]
    assert answer.headers["cache-control"] == "private, no-store"


async def test_a_batch_is_written_whole_and_read_back_by_v1_and_by_the_lessons(
    v2, v2_tokens, petersburg
) -> None:
    token = v2_tokens["diary"]
    rest = await v2.rest(
        BATCH,
        _batch(
            CorrectionUpdate(target=LESSON, field="room", value="204", original="12"),
            CorrectionUpdate(target="hw:id:77", field="text", value="§ 3, задачи 1–5"),
        ),
        token=token,
    )
    assert rest.status == 200
    made = rest.message.corrections
    assert [(item.target, item.field, item.value) for item in made] == [
        (LESSON, "room", "204"),
        ("hw:id:77", "text", "§ 3, задачи 1–5"),
    ]
    assert made[0].original_when_written == "12"
    assert not made[1].has_field("original_when_written")
    # The last writer wins, `original` included, and a key named twice in one
    # batch keeps the later: both entries are the one row, as stored.
    connect = await v2.connect(
        BATCH,
        _batch(
            CorrectionUpdate(target=LESSON, field="room", value="301"),
            CorrectionUpdate(target=LESSON, field="room", value="302", original="12"),
        ),
        token=token,
    )
    assert connect.status == 200
    assert [(item.value, item.original_when_written) for item in connect.message.corrections] == [
        ("302", "12"),
        ("302", "12"),
    ]
    assert await _stored() == [
        (SCOPE, 4021, LESSON, "room", "302"),
        (SCOPE, 4021, "hw:id:77", "text", "§ 3, задачи 1–5"),
    ]
    v1 = (await v2.http.get("/api/v1/diary/students/4021/overrides", headers=_auth(token))).json()
    assert [row["value"] for row in v1] == ["§ 3, задачи 1–5", "302"]
    days = (
        await v2.rest(
            "DiaryService/ListScheduleDays",
            ListScheduleDaysRequest(
                student_id=4021, start_date="2026-09-15", end_date="2026-09-15"
            ),
            token=token,
        )
    ).message.schedule_days
    assert (days[0].lessons[0].room, days[0].lessons[0].edits[0].original) == ("302", "12")


async def test_an_empty_batch_writes_nothing_and_an_empty_value_is_a_real_answer(
    v2, v2_tokens, petersburg
) -> None:
    """A batch of none still asks who the pupil is: another family's id is
    nobody's, and a pupil who can have none is refused a write of nothing too.
    An empty value on a field the diary may leave blank is stored, as v1 stores
    it: «there is nothing here» is a correction."""
    token = v2_tokens["diary"]
    empty = await v2.both(BATCH, _batch(), token=token)
    assert (empty.status, list(empty.message.corrections)) == (200, [])
    assert (await v2.both(BATCH, _batch(student_id=999), token=token)).reason == (
        "RESOURCE_NOT_FOUND"
    )
    assert (await v2.both(BATCH, _batch(student_id=7), token=token)).reason == (
        "CORRECTIONS_UNAVAILABLE"
    )
    assert await _stored() == []
    blank = await v2.rest(
        BATCH, _batch(CorrectionUpdate(target=LESSON, field="room", value="")), token=token
    )
    assert (blank.status, blank.message.corrections[0].value) == (200, "")
    assert await _stored() == [(SCOPE, 4021, LESSON, "room", "")]


#: A correction that always passes, paired below with one that does not, so
#: the test pins the refused one's own index rather than "whichever is bad".
_GOOD = CorrectionUpdate(target=LESSON, field="room", value="204")


@pytest.mark.parametrize(
    ("batch", "field", "error"),
    [
        (
            (_GOOD, CorrectionUpdate(target="nonsense", field="text", value="")),
            "corrections[1].target",
            wording.CORRECTION_TARGET_REFUSED_DETAIL,
        ),
        (
            (_GOOD, CorrectionUpdate(target="hw:id:007", field="text", value="x")),
            "corrections[1].target",
            wording.CORRECTION_TARGET_REFUSED_DETAIL,
        ),
        (
            (_GOOD, CorrectionUpdate(target="hw:id:77", field="room", value="x")),
            "corrections[1].field",
            wording.CORRECTION_FIELD_REFUSED_DETAIL,
        ),
        (
            (_GOOD, CorrectionUpdate(target="hw:id:77", field="text", value="  ")),
            "corrections[1].value",
            wording.CORRECTION_VALUE_EMPTY_DETAIL,
        ),
        (
            (_GOOD, CorrectionUpdate(target="", field="room", value="x")),
            "corrections[1].target",
            None,
        ),
        (
            (_GOOD, CorrectionUpdate(target=LESSON, field="room", value="x" * 4001)),
            "corrections[1].value",
            None,
        ),
        (
            # The first refused correction ends the check (decision 5): the
            # bad field at index 0 is reported, and the bad target waiting at
            # index 1 — which would refuse on its own — is never looked at.
            (
                CorrectionUpdate(target="hw:id:77", field="room", value="x"),
                CorrectionUpdate(target="nonsense", field="text", value=""),
            ),
            "corrections[0].field",
            wording.CORRECTION_FIELD_REFUSED_DETAIL,
        ),
    ],
)
async def test_a_batch_refused_at_any_correction_writes_none_of_them(
    v2, v2_tokens, petersburg, batch, field, error
) -> None:
    """Each correction is checked as v1 checks one, before anything is written
    and before the diary is asked anything, and the refusal names the first
    one refused by its index, from 0, and its part."""
    token = v2_tokens["diary"]
    answer = await v2.both(BATCH, _batch(*batch), token=token)
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "INVALID_ARGUMENT",
        "VALIDATION_FAILED",
    )
    assert [name for name, _ in answer.violations] == [field]
    assert petersburg.seen == []
    assert await _stored() == []
    if error is not None:
        assert (answer.error, answer.violations) == (error, [(field, error)])
        # v1's words for the same correction — the one the index names.
        index = int(field.removeprefix("corrections[").split("]")[0])
        refused = batch[index]
        v1 = await v2.http.put(
            "/api/v1/diary/students/4021/overrides",
            headers=_auth(token),
            json={"target": refused.target, "field": refused.field, "value": refused.value},
        )
        assert (v1.status_code, v1.json()["detail"]) == (422, error)


async def test_a_batch_that_fails_while_writing_keeps_nothing(
    v2, v2_tokens, session, petersburg, monkeypatch
) -> None:
    """All or none: the second write fails after the first replaced a
    correction, and ``invoke``'s one rollback takes the first back with it."""
    await _correct(session, LESSON, "room", "204")
    put = diary_corrections.put_override
    calls: list[object] = []

    async def fails_the_second(*args: Any, **kwargs: Any) -> DiaryOverride:
        calls.append(args)
        if len(calls) == 2:
            raise RuntimeError("the connection went away")
        return await put(*args, **kwargs)

    monkeypatch.setattr(diary_corrections, "put_override", fails_the_second)
    request = _batch(
        CorrectionUpdate(target=LESSON, field="room", value="301"),
        CorrectionUpdate(target="hw:id:77", field="text", value="§ 3"),
    )
    for call in (v2.rest, v2.connect):
        calls.clear()
        failed = await call(BATCH, request, token=v2_tokens["diary"])
        assert (failed.status, failed.code) == (500, "INTERNAL")
        assert await _stored() == [(SCOPE, 4021, LESSON, "room", "204")]


async def test_a_pupil_the_diary_lists_outside_its_numbering_can_have_none_written(
    v2, v2_tokens, session, petersburg
) -> None:
    """Its list is empty and a write is ``CORRECTIONS_UNAVAILABLE``, in v1's
    words; and its plain 7 reaches nothing of the person whose id is 7."""
    await _correct(session, LESSON, "room", "204", student_id=7)
    token = v2_tokens["diary"]
    refused = await v2.both(
        BATCH,
        _batch(CorrectionUpdate(target=LESSON, field="room", value="999"), student_id=7),
        token=token,
    )
    v1 = await v2.http.put(
        "/api/v1/diary/students/7/overrides",
        headers=_auth(token),
        json={"target": LESSON, "field": "room", "value": "999"},
    )
    assert (refused.status, refused.code, refused.reason, refused.metadata, refused.error) == (
        400,
        "FAILED_PRECONDITION",
        "CORRECTIONS_UNAVAILABLE",
        {},
        v1.json()["detail"],
    )
    assert refused.error == wording.CORRECTIONS_UNAVAILABLE_DETAIL
    listed = await v2.both(LIST, ListCorrectionsRequest(student_id=7), token=token)
    assert list(listed.message.corrections) == []
    assert await _stored() == [(SCOPE, 7, LESSON, "room", "204")]


async def test_another_family_s_child_reaches_nothing(v2, v2_tokens, session, petersburg) -> None:
    await _correct(session, LESSON, "room", "204", student_id=999)
    token = v2_tokens["diary"]
    for name, request in (
        (LIST, ListCorrectionsRequest(student_id=999)),
        (BATCH, _batch(CorrectionUpdate(target=LESSON, field="room", value="666"), student_id=999)),
    ):
        refused = await v2.both(name, request, token=token)
        assert (refused.status, refused.reason, refused.metadata, refused.error) == (
            404,
            "RESOURCE_NOT_FOUND",
            {"resource": "student"},
            wording.UNKNOWN_STUDENT_DETAIL,
        ), name
    assert await _stored() == [(SCOPE, 999, LESSON, "room", "204")]
    assert {request.url.path for request in petersburg.seen} == {CHILDREN}


async def test_more_than_two_hundred_corrections_are_refused_before_the_diary_is_asked(
    v2, v2_tokens, petersburg
) -> None:
    token = v2_tokens["diary"]
    many = [
        CorrectionUpdate(target=f"hw:id:{number}", field="text", value="§ 1")
        for number in range(1, CORRECTIONS_MAX + 2)
    ]
    refused = await v2.both(BATCH, _batch(*many), token=token)
    assert (refused.status, refused.reason, refused.error, refused.violations) == (
        400,
        "VALIDATION_FAILED",
        TOO_MANY_CORRECTIONS,
        [("corrections", TOO_MANY_CORRECTIONS)],
    )
    assert petersburg.seen == []

    # The cap first (Ruling 124): one of the 201 is itself invalid — an empty
    # target at index 0 — and the cap's refusal on `corrections` still wins,
    # never `corrections[0].target`.
    one_bad = [CorrectionUpdate(target="", field="room", value="x"), *many[1:]]
    also_refused = await v2.both(BATCH, _batch(*one_bad), token=token)
    assert (also_refused.reason, also_refused.violations) == (
        "VALIDATION_FAILED",
        [("corrections", TOO_MANY_CORRECTIONS)],
    )

    accepted = await v2.rest(BATCH, _batch(*many[:CORRECTIONS_MAX]), token=token)
    assert (accepted.status, len(accepted.message.corrections)) == (200, CORRECTIONS_MAX)
    async with SessionLocal() as fresh:
        count = await fresh.scalar(select(func.count()).select_from(DiaryOverride))
    assert count == CORRECTIONS_MAX


async def test_a_refused_correction_never_repeats_what_was_sent(v2, v2_tokens, petersburg) -> None:
    """The no-echo sweep sends ``corrections`` as an object or a string, which
    the decoder refuses before any handler; these reach the handler's own
    checks, part by part, and are refused without the value."""
    token = v2_tokens["diary"]
    for refused in (
        CorrectionUpdate(target=SECRET, field="text", value="x"),
        CorrectionUpdate(target=f"lesson:{SECRET}", field="room", value="x"),
        CorrectionUpdate(target=SECRET * 14, field="text", value="x"),
        CorrectionUpdate(target="hw:id:77", field=SECRET, value="x"),
        CorrectionUpdate(target="hw:id:77", field=SECRET * 2, value="x"),
        CorrectionUpdate(target="hw:id:77", field="text", value=SECRET * 200),
        CorrectionUpdate(target="hw:id:77", field="text", value="x", original=SECRET * 200),
    ):
        for answer in (
            await v2.rest(BATCH, _batch(refused), token=token),
            await v2.connect(BATCH, _batch(refused), token=token),
        ):
            assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED")
            assert SECRET not in answer.body.decode()
    assert await _stored() == []


async def test_a_session_whose_region_names_no_server_is_sent_to_sign_in_again(
    v2, session, netschool
) -> None:
    """The summary's ``UnknownDiaryServer`` row: it never reaches a handler.
    ``DiaryService.scope_of`` expires a «Сетевой город» session the allow-list
    cannot place, as v1 does, and v2 says «sign in again» through the row for
    ``SessionExpired``; the next call is the gate's to refuse."""
    registered = await v2.http.post(
        "/api/v1/diary/session",
        json={
            "provider": "netschool",
            "login": "ivanova",
            "region": "zabaikalsky",
            "school_id": 42,
            "credential": {"at": "at-zab-mother-001", "cookies": {"NSSESSIONID": "sess-zab"}},
        },
    )
    assert registered.status_code == 200, registered.text
    token = registered.json()["token"]
    row = await session.scalar(select(DiarySession).where(DiarySession.provider == "netschool"))
    row.region = "atlantis"
    await session.commit()
    ended = await v2.rest(LIST, ListCorrectionsRequest(student_id=11), token=token)
    assert (ended.status, ended.reason, ended.error) == (
        401,
        "DIARY_REAUTH",
        "Сессия дневника истекла — войдите заново",
    )
    async with SessionLocal() as fresh:
        assert (await fresh.get(DiarySession, row.id)).expired_at is not None
    again = await v2.connect(
        BATCH,
        _batch(CorrectionUpdate(target="hw:id:77", field="text", value="§ 3"), student_id=11),
        token=token,
    )
    assert again.reason == "DIARY_TOKEN_INVALID"
    assert await _stored() == []


async def test_listing_the_corrections_writes_nothing_but_the_session_s_last_use(
    v2, v2_tokens, session, petersburg, statement_writes, unexpected_writes
) -> None:
    await _correct(session, LESSON, "room", "204")
    with statement_writes() as seen:
        answer = await v2.both(
            LIST, ListCorrectionsRequest(student_id=4021), token=v2_tokens["diary"]
        )
        assert answer.status == 200
    assert seen, "the session's last use is written once in fifteen minutes"
    assert unexpected_writes(seen) == []
