"""``SubjectService``'s writes: create, update by mask, delete, by v1's rules.

v1's ``/manage/subjects`` writes over v2, through the same services and v1's
own ``SubjectIn`` and ``SubjectPatch``, so the two versions refuse the same
requests in the same words: a name the class has in any case, a rename onto
a name with homework the same day, a subject the timetable still teaches.
What v2 adds is the mask (``docs/specs/2026-10-05-server-v2-3b-plan.md``,
Ruling 5). A write's success is asked once per transport on fresh data, and
its refusals through ``both`` (Ruling 17).
"""

from __future__ import annotations

from datetime import date

from protobuf.wkt import FieldMask
from sqlalchemy import select

from app import wording
from app.contract.lessons.v2.subject_pb import (
    CreateSubjectRequest,
    DeleteSubjectRequest,
    Subject,
    UpdateSubjectRequest,
)
from app.models import (
    AuditEntry,
    Homework,
    LessonOverride,
    OverrideAction,
    SchoolClass,
    TimetableEntry,
)
from app.models import Subject as SubjectRow
from app.rpc.masks import NOT_CHANGEABLE

MONDAY = date(2026, 9, 7)


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _subject(session, school_class, name: str, **details) -> SubjectRow:
    row = SubjectRow(class_id=school_class.id, name=name, **details)
    session.add(row)
    await session.commit()
    return row


async def _row(session, subject_id: int) -> SubjectRow | None:
    """The row as the database holds it now, or ``None`` once it is gone."""
    return await session.scalar(
        select(SubjectRow)
        .where(SubjectRow.id == subject_id)
        .execution_options(populate_existing=True)
    )


async def _actions(session) -> list[str]:
    return list(await session.scalars(select(AuditEntry.action).order_by(AuditEntry.id)))


async def _timetable_names(session, school_class) -> set[str]:
    return set(
        await session.scalars(
            select(TimetableEntry.subject_name).where(TimetableEntry.class_id == school_class.id)
        )
    )


def _rename(subject_id: int, name: str) -> UpdateSubjectRequest:
    return UpdateSubjectRequest(
        subject=Subject(id=subject_id, name=name), update_mask=FieldMask(paths=["name"])
    )


async def test_a_subject_is_created_on_either_path_and_rest_says_201(
    v2, v2_tokens, session
) -> None:
    admin = v2_tokens["admin"]
    rest = await v2.rest(
        "SubjectService/CreateSubject",
        CreateSubjectRequest(subject=Subject(name=" Химия ", teacher="Петров", color="5b6abf")),
        token=admin,
    )
    assert rest.status == 201
    made = rest.message.subject
    assert (made.name, made.teacher, made.color) == ("Химия", "Петров", "#5B6ABF")
    assert not made.has_field("short_name")
    stored = await session.scalar(select(SubjectRow.id).where(SubjectRow.name == "Химия"))
    assert made.id == stored
    # The id a client sends is ignored: the server assigns it.
    connect = await v2.connect(
        "SubjectService/CreateSubject",
        CreateSubjectRequest(subject=Subject(id=999, name="Биология")),
        token=admin,
    )
    assert connect.status == 200
    assert connect.message.subject.name == "Биология"
    assert connect.message.subject.id != 999
    assert await _actions(session) == ["subject.add", "subject.add"]


async def test_a_new_subject_is_cleaned_as_v1_cleans_it(v2, v2_tokens) -> None:
    admin = v2_tokens["admin"]
    v1 = await v2.http.post(
        "/api/v1/manage/subjects",
        json={"name": "Алгебра  и  начала", "short_name": " Алг ", "color": "#5b6abf"},
        headers=_auth(admin),
    )
    sent = Subject(name="Геометрия  и  черчение", short_name=" Геом ", color="#5b6abf")
    made = (
        await v2.rest(
            "SubjectService/CreateSubject", CreateSubjectRequest(subject=sent), token=admin
        )
    ).message.subject
    theirs = v1.json()["subject"]
    assert (theirs["name"], theirs["short_name"], theirs["color"]) == (
        "Алгебра и начала",
        "Алг",
        "#5B6ABF",
    )
    assert (made.name, made.short_name, made.color) == ("Геометрия и черчение", "Геом", "#5B6ABF")


async def test_a_name_the_class_has_in_any_case_is_refused_as_existing(
    v2, v2_tokens, session, school_class
) -> None:
    await _subject(session, school_class, "Химия")
    admin = v2_tokens["admin"]
    v1 = await v2.http.post(
        "/api/v1/manage/subjects", json={"name": "химия"}, headers=_auth(admin)
    )
    answer = await v2.both(
        "SubjectService/CreateSubject",
        CreateSubjectRequest(subject=Subject(name="ХИМИЯ")),
        token=admin,
    )
    assert (answer.status, answer.code, answer.reason) == (
        409,
        "ALREADY_EXISTS",
        "RESOURCE_EXISTS",
    )
    assert answer.metadata == {"resource": "subject", "field": "name"}
    assert answer.error == v1.json()["detail"] == wording.SUBJECT_EXISTS_DETAIL
    assert v1.status_code == 409
    assert await _actions(session) == []


async def test_a_subject_v1_would_refuse_is_refused_on_its_field(v2, v2_tokens) -> None:
    admin = v2_tokens["admin"]
    for sent, field in (
        ({"name": "   "}, "subject.name"),
        ({"name": "Х" * 121}, "subject.name"),
        ({"name": "Химия", "color": "зелёный"}, "subject.color"),
        ({"name": "Химия", "short_name": "Х" * 17}, "subject.short_name"),
    ):
        answer = await v2.both(
            "SubjectService/CreateSubject",
            CreateSubjectRequest(subject=Subject(**sent)),
            token=admin,
        )
        assert (answer.code, answer.reason) == ("INVALID_ARGUMENT", "VALIDATION_FAILED"), field
        assert [name for name, _ in answer.violations] == [field]
        v1 = await v2.http.post("/api/v1/manage/subjects", json=sent, headers=_auth(admin))
        assert v1.status_code == 422, field


async def test_a_rename_carries_the_timetable_homework_and_substitutions(
    v2, v2_tokens, session, school_class
) -> None:
    algebra = await _subject(session, school_class, "Алгебра")
    session.add_all(
        [
            Homework(
                class_id=school_class.id, due_date=MONDAY, subject_name="Алгебра", text="№ 12"
            ),
            LessonOverride(
                class_id=school_class.id,
                date=MONDAY,
                index=4,
                action=OverrideAction.REPLACE,
                subject_name="Алгебра",
            ),
        ]
    )
    await session.commit()
    answer = await v2.rest(
        "SubjectService/UpdateSubject",
        _rename(algebra.id, "Алгебра и начала анализа"),
        token=v2_tokens["admin"],
    )
    assert answer.status == 200
    # One timetable row of the fixture's Monday, one homework, one substitution.
    assert answer.message.moved == 3
    assert answer.message.subject.name == "Алгебра и начала анализа"
    assert "Алгебра" not in await _timetable_names(session, school_class)
    summaries = list(await session.scalars(select(AuditEntry.summary)))
    assert summaries == ["предмет «Алгебра» → «Алгебра и начала анализа», строк обновлено: 3"]


async def test_an_update_without_a_mask_changes_only_what_it_sends(
    v2, v2_tokens, session, school_class
) -> None:
    physics = await _subject(session, school_class, "Физика", teacher="Петров", color="#111111")
    answer = await v2.rest(
        "SubjectService/UpdateSubject",
        UpdateSubjectRequest(subject=Subject(id=physics.id, short_name="Физ")),
        token=v2_tokens["admin"],
    )
    assert answer.status == 200
    made = answer.message.subject
    assert (made.name, made.short_name, made.teacher, made.color) == (
        "Физика",
        "Физ",
        "Петров",
        "#111111",
    )
    assert answer.message.moved == 0
    assert await _actions(session) == ["subject.short_name"]


async def test_a_rename_with_no_mask_renames_the_subject(
    v2, v2_tokens, session, school_class
) -> None:
    physics = await _subject(session, school_class, "Физика", teacher="Петров")
    answer = await v2.rest(
        "SubjectService/UpdateSubject",
        UpdateSubjectRequest(subject=Subject(id=physics.id, name="Физика и астрономия")),
        token=v2_tokens["admin"],
    )
    assert answer.status == 200
    assert answer.message.subject.name == "Физика и астрономия"
    assert answer.message.subject.teacher == "Петров"
    assert (await _row(session, physics.id)).name == "Физика и астрономия"
    assert await _actions(session) == ["subject.rename"]


async def test_a_rename_and_a_detail_in_one_update_are_logged_rename_first(
    v2, v2_tokens, session, school_class
) -> None:
    physics = await _subject(session, school_class, "Физика")
    answer = await v2.connect(
        "SubjectService/UpdateSubject",
        UpdateSubjectRequest(
            subject=Subject(id=physics.id, name="Физика и астрономия", teacher="Петров"),
            # The detail is named first on purpose: the order is the service's
            # and not the mask's.
            update_mask=FieldMask(paths=["teacher", "name"]),
        ),
        token=v2_tokens["admin"],
    )
    assert answer.status == 200
    assert await _actions(session) == ["subject.rename", "subject.teacher"]


async def test_the_mask_is_spelled_lower_camel_case_over_rest(
    v2, v2_tokens, session, school_class
) -> None:
    physics = await _subject(session, school_class, "Физика")
    url = f"/api/v2/class/subjects/{physics.id}"
    headers = _auth(v2_tokens["admin"])

    camel = await v2.http.patch(
        f"{url}?update_mask=shortName", json={"shortName": "Физ"}, headers=headers
    )
    assert camel.status_code == 200
    assert camel.json()["subject"]["shortName"] == "Физ"
    assert (await _row(session, physics.id)).short_name == "Физ"

    # protobuf's JSON parser refuses a snake_case path: «path names must be
    # lowerCamelCase». Refused, and nothing written.
    snake = await v2.http.patch(
        f"{url}?update_mask=short_name", json={"shortName": "Ф"}, headers=headers
    )
    assert snake.status_code == 400
    error = snake.json()["error"]
    assert error["status"] == "INVALID_ARGUMENT"
    assert error["details"][0]["reason"] == "REQUEST_UNDECODABLE"
    assert (await _row(session, physics.id)).short_name == "Физ"
    assert await _actions(session) == ["subject.short_name"]


async def test_a_masked_field_left_out_is_cleared(v2, v2_tokens, session, school_class) -> None:
    physics = await _subject(
        session, school_class, "Физика", short_name="Физ", teacher="Петров", color="#111111"
    )
    answer = await v2.connect(
        "SubjectService/UpdateSubject",
        UpdateSubjectRequest(
            subject=Subject(id=physics.id), update_mask=FieldMask(paths=["teacher", "color"])
        ),
        token=v2_tokens["admin"],
    )
    assert answer.status == 200
    made = answer.message.subject
    assert made.short_name == "Физ"
    assert not made.has_field("teacher") and not made.has_field("color")
    assert await _actions(session) == ["subject.teacher", "subject.colour"]
    public = (await v2.http.get("/api/v1/subjects", headers=_auth(v2_tokens["admin"]))).json()
    assert public == [{"name": "Физика", "short_name": "Физ", "teacher": None, "color": None}]


async def test_a_mask_naming_a_field_the_method_does_not_change_is_refused_on_it(
    v2, v2_tokens, session, school_class
) -> None:
    physics = await _subject(session, school_class, "Физика")
    for paths in (["id"], ["teacher", "class_id"], ["*"]):
        answer = await v2.both(
            "SubjectService/UpdateSubject",
            UpdateSubjectRequest(
                subject=Subject(id=physics.id, teacher="Петров"),
                update_mask=FieldMask(paths=paths),
            ),
            token=v2_tokens["admin"],
        )
        assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED"), paths
        assert answer.violations == [("update_mask", NOT_CHANGEABLE)]
    assert await _actions(session) == []


async def test_a_masked_name_left_out_is_refused_rather_than_blanked(
    v2, v2_tokens, session, school_class
) -> None:
    physics = await _subject(session, school_class, "Физика")
    answer = await v2.both(
        "SubjectService/UpdateSubject",
        UpdateSubjectRequest(
            subject=Subject(id=physics.id), update_mask=FieldMask(paths=["name"])
        ),
        token=v2_tokens["admin"],
    )
    assert answer.reason == "VALIDATION_FAILED"
    assert [field for field, _ in answer.violations] == ["subject.name"]
    assert (await _row(session, physics.id)).name == "Физика"


async def test_a_rename_onto_a_name_taken_in_another_case_moves_nothing(
    v2, v2_tokens, session, school_class
) -> None:
    algebra = await _subject(session, school_class, "Алгебра")
    await _subject(session, school_class, "Геометрия")
    admin = v2_tokens["admin"]
    v1 = await v2.http.patch(
        f"/api/v1/manage/subjects/{algebra.id}", json={"name": "геометрия"}, headers=_auth(admin)
    )
    answer = await v2.both(
        "SubjectService/UpdateSubject", _rename(algebra.id, "ГЕОМЕТРИЯ"), token=admin
    )
    assert (answer.status, answer.code, answer.reason) == (
        409,
        "ALREADY_EXISTS",
        "RESOURCE_EXISTS",
    )
    assert answer.metadata == {"resource": "subject", "field": "name"}
    assert answer.error == v1.json()["detail"] == wording.SUBJECT_EXISTS_DETAIL
    assert (await _row(session, algebra.id)).name == "Алгебра"
    assert "Алгебра" in await _timetable_names(session, school_class)
    assert await _actions(session) == []


async def test_a_rename_onto_a_name_with_homework_the_same_day_is_a_clash(
    v2, v2_tokens, session, school_class
) -> None:
    algebra = await _subject(session, school_class, "Алгебра")
    session.add_all(
        [
            Homework(class_id=school_class.id, due_date=MONDAY, subject_name=name, text="п.1")
            for name in ("Алгебра", "Матан")
        ]
    )
    await session.commit()
    admin = v2_tokens["admin"]
    v1 = await v2.http.patch(
        f"/api/v1/manage/subjects/{algebra.id}", json={"name": "Матан"}, headers=_auth(admin)
    )
    answer = await v2.both(
        "SubjectService/UpdateSubject", _rename(algebra.id, "Матан"), token=admin
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "FAILED_PRECONDITION",
        "SUBJECT_RENAME_CLASH",
    )
    assert answer.metadata == {"dates": "2026-09-07"}
    assert answer.error == v1.json()["detail"] == wording.subject_rename_clash_detail([MONDAY])
    assert v1.status_code == 409
    assert (await _row(session, algebra.id)).name == "Алгебра"


async def test_a_subject_the_timetable_teaches_is_refused_as_in_use(
    v2, v2_tokens, session, school_class
) -> None:
    algebra = await _subject(session, school_class, "Алгебра")
    admin = v2_tokens["admin"]
    v1 = await v2.http.delete(f"/api/v1/manage/subjects/{algebra.id}", headers=_auth(admin))
    answer = await v2.both(
        "SubjectService/DeleteSubject", DeleteSubjectRequest(subject_id=algebra.id), token=admin
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "FAILED_PRECONDITION",
        "RESOURCE_IN_USE",
    )
    # The fixture's Monday teaches it once.
    assert answer.metadata == {"resource": "subject", "used_by": "lessons", "count": "1"}
    assert answer.error == v1.json()["detail"] == wording.subject_in_use_detail(1)
    assert v1.status_code == 409
    assert await _row(session, algebra.id) is not None


async def test_a_subject_nothing_teaches_is_deleted_on_either_path(
    v2, v2_tokens, session, school_class
) -> None:
    astronomy = await _subject(session, school_class, "Астрономия")
    biology = await _subject(session, school_class, "Биология")
    admin = v2_tokens["admin"]
    rest = await v2.rest(
        "SubjectService/DeleteSubject", DeleteSubjectRequest(subject_id=astronomy.id), token=admin
    )
    connect = await v2.connect(
        "SubjectService/DeleteSubject", DeleteSubjectRequest(subject_id=biology.id), token=admin
    )
    assert (rest.status, connect.status) == (200, 200)
    assert await _row(session, astronomy.id) is None
    assert await _row(session, biology.id) is None
    assert await _actions(session) == ["subject.delete", "subject.delete"]
    again = await v2.both(
        "SubjectService/DeleteSubject", DeleteSubjectRequest(subject_id=astronomy.id), token=admin
    )
    assert (again.status, again.reason) == (404, "RESOURCE_NOT_FOUND")


async def test_another_class_s_subject_is_found_by_no_write(
    v2, v2_tokens, session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    stranger = SubjectRow(class_id=other.id, name="Биология")
    session.add(stranger)
    await session.commit()
    for name, request in (
        ("UpdateSubject", _rename(stranger.id, "Ботаника")),
        ("DeleteSubject", DeleteSubjectRequest(subject_id=stranger.id)),
    ):
        answer = await v2.both(f"SubjectService/{name}", request, token=v2_tokens["admin"])
        assert (answer.status, answer.reason, answer.metadata, answer.error) == (
            404,
            "RESOURCE_NOT_FOUND",
            {"resource": "subject"},
            wording.UNKNOWN_SUBJECT_DETAIL,
        ), name
    assert (await _row(session, stranger.id)).name == "Биология"
