"""``SubjectService``'s reads: the dictionary with ids, for any phone in the class.

v2 has one subject resource where v1 had two: ``GET /subjects`` (no ids, any
phone, a plain read) and ``/manage/subjects`` (ids, an editor's, adopting the
timetable's names and committing them). The list is v1's list, and the read
writes nothing where v1's manage list adopts
(``docs/specs/2026-10-05-server-v2-design.md``, decision 10).
"""

from __future__ import annotations

from app import wording
from app.contract.lessons.v2.subject_pb import GetSubjectRequest, Subject
from app.models import SchoolClass
from app.models import Subject as SubjectRow

LAST_SEEN = "UPDATE device_tokens SET last_seen_at=?"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _subject(session, school_class, name: str, **details) -> SubjectRow:
    row = SubjectRow(class_id=school_class.id, name=name, **details)
    session.add(row)
    await session.commit()
    return row


def _plain(subject: Subject) -> dict[str, str | None]:
    """A v2 subject as v1's ``SubjectOut`` writes it: an unset field is ``null``."""
    return {
        "name": subject.name,
        **{
            field: getattr(subject, field) if subject.has_field(field) else None
            for field in ("short_name", "teacher", "color")
        },
    }


async def test_the_dictionary_is_v1_s_with_its_ids(v2, v2_tokens, session, school_class) -> None:
    physics = await _subject(session, school_class, "Физика", teacher="Петров", color="#111111")
    algebra = await _subject(session, school_class, "Алгебра", short_name="Алг")
    token = v2_tokens["unlinked"]
    answer = await v2.both("SubjectService/ListSubjects", token=token)
    subjects = list(answer.message.subjects)
    assert [subject.id for subject in subjects] == [algebra.id, physics.id]
    public = (await v2.http.get("/api/v1/subjects", headers=_auth(token))).json()
    assert [_plain(subject) for subject in subjects] == public


async def test_the_list_adopts_nothing_where_v1_s_manage_list_does(
    v2, v2_tokens, statement_writes
) -> None:
    """The fixture's Monday teaches three subjects the dictionary has never
    heard of. v1's manage list adopts them on read and commits; v2's read
    leaves the dictionary as it is and writes nothing but the last seen."""
    token = v2_tokens["editor"]
    with statement_writes() as seen:
        before = await v2.both("SubjectService/ListSubjects", token=token)
    assert before.status == 200
    assert list(before.message.subjects) == []
    assert all(statement.startswith(LAST_SEEN) for statement in seen), seen

    with statement_writes() as seen:
        v1 = await v2.http.get("/api/v1/manage/subjects", headers=_auth(token))
    assert any(statement.startswith("INSERT INTO subjects") for statement in seen)

    after = await v2.both("SubjectService/ListSubjects", token=token)
    assert [(subject.id, subject.name) for subject in after.message.subjects] == [
        (row["id"], row["name"]) for row in v1.json()
    ]


async def test_any_phone_in_the_class_may_read_it(v2, v2_tokens, session, school_class) -> None:
    await _subject(session, school_class, "Химия")
    for who in ("unlinked", "viewer", "stranger"):
        answer = await v2.both("SubjectService/ListSubjects", token=v2_tokens[who])
        assert [subject.name for subject in answer.message.subjects] == ["Химия"], who


async def test_one_subject_is_read_by_its_id(v2, v2_tokens, session, school_class) -> None:
    physics = await _subject(session, school_class, "Физика", short_name="Физ", color="#5B6ABF")
    answer = await v2.both(
        "SubjectService/GetSubject",
        GetSubjectRequest(subject_id=physics.id),
        token=v2_tokens["viewer"],
    )
    assert answer.message.subject == Subject(
        id=physics.id, name="Физика", short_name="Физ", color="#5B6ABF"
    )


async def test_an_id_that_names_no_subject_of_the_class_is_not_found(
    v2, v2_tokens, session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    stranger = SubjectRow(class_id=other.id, name="Биология")
    session.add(stranger)
    await session.commit()
    v1 = await v2.http.patch(
        f"/api/v1/manage/subjects/{stranger.id}",
        json={"name": "Ботаника"},
        headers=_auth(v2_tokens["admin"]),
    )
    for subject_id in (stranger.id, 999_999):
        answer = await v2.both(
            "SubjectService/GetSubject",
            GetSubjectRequest(subject_id=subject_id),
            token=v2_tokens["viewer"],
        )
        assert (answer.status, answer.code, answer.reason) == (
            404,
            "NOT_FOUND",
            "RESOURCE_NOT_FOUND",
        )
        assert answer.metadata == {"resource": "subject"}
        assert answer.error == v1.json()["detail"] == wording.UNKNOWN_SUBJECT_DETAIL


async def test_reading_one_subject_writes_nothing_but_the_last_seen(
    v2, v2_tokens, session, school_class, statement_writes
) -> None:
    physics = await _subject(session, school_class, "Физика")
    with statement_writes() as seen:
        answer = await v2.both(
            "SubjectService/GetSubject",
            GetSubjectRequest(subject_id=physics.id),
            token=v2_tokens["viewer"],
        )
    assert answer.status == 200
    assert answer.message.subject.name == "Физика"
    assert all(statement.startswith(LAST_SEEN) for statement in seen), seen
