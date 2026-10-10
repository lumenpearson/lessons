"""``DiaryService``: one family's account with an electronic diary.

3a serves ``GetDiaryCapabilities``, which 3b-7 fills from the registry's
table: each provider's ways in and the data it has (decision 12). 3b-7 serves
the sessions: a session the phone opened with the diary itself is kept by
``CreateDiarySession``, through ``services/diary.register`` — v1's
``POST /diary/session``'s rules, order and counting, on the same budget — and
signed out by ``DeleteDiarySession``, v1's ``/logout``. And the reads of one
pupil, each through ``services/diary``'s ``DiaryService`` as v1's routes read:
the pupil resolved from the session's own diary on every call, so an id of
another family's child reaches nothing, and a feature the session's provider
does not declare refused before the diary is asked anything. v1's
``POST /diary/login``, a password through this server, has no twin here
(``docs/api.md``, «Not in v2, on purpose»). The corrections are 3b-8's
(``docs/specs/2026-10-05-server-v2-3b-plan.md``).
"""

from __future__ import annotations

from datetime import date as Date
from datetime import time as Time
from typing import TYPE_CHECKING, Any

from protobuf import Message

from app.contract.lessons.v2.diary_pb import (
    AttendanceDirection,
    CreateDiarySessionRequest,
    CreateDiarySessionResponse,
    DeleteDiarySessionRequest,
    DeleteDiarySessionResponse,
    DiaryAttendance,
    DiaryCapabilities,
    DiaryEdit,
    DiaryFeature,
    DiaryHomework,
    DiaryLesson,
    DiaryMark,
    DiaryPeriod,
    DiaryScheduleDay,
    DiarySession,
    DiaryStudent,
    DiarySubject,
    DiaryTeacher,
    GetDiaryCapabilitiesRequest,
    GetDiaryCapabilitiesResponse,
    ListDiaryHomeworkRequest,
    ListDiaryHomeworkResponse,
    ListDiarySubjectsRequest,
    ListDiarySubjectsResponse,
    ListMarksRequest,
    ListMarksResponse,
    ListPeriodsRequest,
    ListPeriodsResponse,
    ListScheduleDaysRequest,
    ListScheduleDaysResponse,
    ListStudentsRequest,
    ListStudentsResponse,
    ListTeachersRequest,
    ListTeachersResponse,
    ListTurnstileEventsRequest,
    ListTurnstileEventsResponse,
    MarkKind,
    ProviderCapabilities,
    SignInMethod,
)
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.crypto import diary_enabled
from app.models import DiarySession as DiarySessionRow
from app.providers.diary.models import AcademicPeriod, Mark, Student
from app.providers.diary.registry import NETSCHOOL, PETERSBURG, TABLE, Feature, row_for
from app.rpc import dates, values
from app.rpc.errors import Refusal, validate
from app.schemas import NetSchoolSessionIn, PetersburgSessionIn
from app.security import DIARY_FAILURES_BUCKET, DIARY_OPENED_BUCKET
from app.services import diary as diary_service
from app.services import diary_overrides as overrides

if TYPE_CHECKING:
    from app.rpc.call import Call

#: v1's schema for each case of the request's ``credential``, which is the
#: provider's key: v1's ``DiarySessionBody`` told the two apart by ``provider``.
#: Named through the registry rather than spelled again, so the two cannot drift.
_SESSIONS: dict[str, type[PetersburgSessionIn] | type[NetSchoolSessionIn]] = {
    PETERSBURG: PetersburgSessionIn,
    NETSCHOOL: NetSchoolSessionIn,
}

#: v1's spelling of a credential's field where v2's differs: the two cookies,
#: whose upper-case names v2's lint will not take.
_V1_NAMES = {"ns_session_id": "NSSESSIONID", "esrn_sec": "ESRNSec"}
_V2_NAMES = {v1: v2 for v2, v1 in _V1_NAMES.items()}

#: A request whose ``credential`` holds neither case. Fixed, naming the oneof.
NO_CREDENTIAL = "credential must be petersburg or netschool"


#: A method whose feature the session's diary does not have. v2's own
#: sentence, English like the gate's: v1 answered such a read with an empty
#: list, which a client could not tell from an empty diary.
NOT_IN_THIS_DIARY = "this diary does not offer this"

#: A turnstile's direction, as the provider's model spells it. A word the
#: model does not know cannot arrive (the mapper reads it as «unknown»), and is
#: never drawn as the child leaving the building.
_DIRECTIONS = {
    "in": AttendanceDirection.IN,
    "out": AttendanceDirection.OUT,
    "unknown": AttendanceDirection.UNKNOWN,
}


async def get_diary_capabilities(
    call: Call, request: GetDiaryCapabilitiesRequest
) -> GetDiaryCapabilitiesResponse:
    """What this server's diary can do, before the phone takes a password.

    v1's ``/diary/capabilities`` in v2's shape, and what v1 did not say: for
    every provider of the registry's table, the allow-list's regions that take
    a password, the ways in a phone may draw a form for, and the data the
    provider has, each read from its row (decision 12). With the diary off a
    provider offers no way in and no data, though its regions are listed as
    v1 lists them (the 3b plan, Ruling 105). Anonymous and database-free, as
    v1's: the gate opens a scope, and nothing asks it for a query.
    """
    enabled = diary_enabled()
    return GetDiaryCapabilitiesResponse(
        capabilities=DiaryCapabilities(
            enabled=enabled,
            providers=[
                ProviderCapabilities(
                    provider=row.key,
                    regions=row.listed_regions(),
                    sign_in_methods=[SignInMethod[way.name] for way in row.sign_in]
                    if enabled
                    else [],
                    features=sorted(
                        (DiaryFeature[feature.name] for feature in row.features),
                        key=lambda feature: feature.value,
                    )
                    if enabled
                    else [],
                )
                for row in TABLE
            ],
        )
    )


def _row(call: Call) -> DiarySessionRow:
    """The diary session the gate found for every method of the diary kind."""
    if call.diary is None:
        raise RuntimeError(f"{call.method.key} asked for a diary session it does not take")
    return call.diary


def _plain(message: Message) -> dict[str, Any]:
    """A credential message as v1's schema reads it: the fields that are set,
    under v1's names, nested messages as objects."""
    found: dict[str, Any] = {}
    for field in message.desc().fields:
        if not message.has_field(field.name):
            continue
        value = getattr(message, field.name)
        found[_V1_NAMES.get(field.name, field.name)] = (
            _plain(value) if isinstance(value, Message) else value
        )
    return found


def _v2_path(path: str, case: str) -> str:
    """A violation's path in v1's body, ``credential.cookies.NSSESSIONID``, as
    the request spells it, ``netschool.cookies.ns_session_id``."""
    head, _, rest = path.partition(".")
    if head != "credential":
        return path
    return ".".join([case, *(_V2_NAMES.get(part, part) for part in rest.split(".") if part)])


def _session_form(request: CreateDiarySessionRequest) -> PetersburgSessionIn | NetSchoolSessionIn:
    """The request validated with v1's own schema (decision 5), so v1 and v2
    refuse the same sessions: no password, no unknown cookie, no value a header
    could not carry. A violation names the field as v2 spells it, and never
    what was sent."""
    chosen = request.credential
    if chosen is None or chosen.field not in _SESSIONS:
        raise Refusal(
            ErrorReason.VALIDATION_FAILED, NO_CREDENTIAL, violations=[("credential", NO_CREDENTIAL)]
        )
    sent: dict[str, Any] = {
        "provider": chosen.field,
        "login": request.login,
        "credential": _plain(chosen.value),
    }
    for name in ("region", "school_id"):
        if request.has_field(name):
            sent[name] = getattr(request, name)
    try:
        return validate(_SESSIONS[chosen.field], sent)
    except Refusal as refusal:
        violations = [(_v2_path(field, chosen.field), text) for field, text in refusal.violations]
        fields = ", ".join(sorted({field for field, _ in violations}))
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            f"invalid request field: {fields}",
            violations=violations,
        ) from None


def _student(student: Student) -> DiaryStudent:
    """v1's ``DiaryStudentOut``, field for field: never the upstream's own
    handles, which the server resolves from the id on every call."""
    return DiaryStudent(
        id=student.id,
        first_name=student.first_name,
        last_name=student.last_name,
        middle_name=student.middle_name,
        full_name=student.full_name,
        school=student.school,
        class_name=student.class_name,
    )


async def create_diary_session(
    call: Call, request: CreateDiarySessionRequest
) -> CreateDiarySessionResponse:
    """Keeps a session the phone opened with the diary itself, and answers a
    diary token of ours (REST ``201``, and never cached).

    ``services/diary.register`` decides, in v1's order and on v1's budget —
    the caller's buckets are v1's for the same caller, so a caller alternating
    versions draws on one: a region this server does not serve is refused
    before anything is counted or sent, then the attempt is counted, then the
    diary reads with the session once, from this server's address. What it
    raises the error table words. The session handed over is never echoed
    back, not even in a refusal.
    """
    form = _session_form(request)
    netschool = form if isinstance(form, NetSchoolSessionIn) else None
    registered = await diary_service.register(
        call.session,
        provider=form.provider,
        login=form.login,
        handed=form.credential.model_dump(exclude_none=True),
        region=netschool.region if netschool is not None else None,
        school_id=netschool.school_id if netschool is not None else None,
        failures_key=call.bucket(DIARY_FAILURES_BUCKET),
        opened_key=call.bucket(DIARY_OPENED_BUCKET),
    )
    row = registered.row
    return CreateDiarySessionResponse(
        session=DiarySession(
            token=registered.token,
            login=row.login,
            provider=form.provider,
            region=row.region,
            school_id=registered.school_id,
            school_name=registered.school_name,
            zone=registered.zone,
            students=[_student(student) for student in registered.students],
        )
    )


async def delete_diary_session(
    call: Call, request: DeleteDiarySessionRequest
) -> DeleteDiarySessionResponse:
    """Signs this session out, as v1's ``/logout``: the upstream is told
    where it can be, the row goes, and the token stops working once the call
    commits."""
    await diary_service.sign_out(call.session, _row(call))
    return DeleteDiarySessionResponse()


# ---- the reads -----------------------------------------------------------


def _service(call: Call) -> diary_service.DiaryService:
    return diary_service.DiaryService(call.session, _row(call))


def _feature(call: Call, feature: Feature) -> None:
    """``FEATURE_UNSUPPORTED``, naming the ``DiaryFeature``, when the session's
    provider does not declare ``feature``: asked before the diary is asked
    anything, so a provider that never had it is not asked for it."""
    row = row_for(_row(call).provider or PETERSBURG)
    if row is None or feature not in row.features:
        raise Refusal(
            ErrorReason.FEATURE_UNSUPPORTED,
            NOT_IN_THIS_DIARY,
            feature=values.proto_name(DiaryFeature[feature.name]),
        )


def _day(day: Date | None) -> str | None:
    return values.date_string(day) if day is not None else None


def _period(period: AcademicPeriod) -> DiaryPeriod:
    return DiaryPeriod(
        id=period.id,
        name=period.name,
        starts_on=_day(period.starts_on),
        ends_on=_day(period.ends_on),
        is_current=period.is_current,
    )


async def list_students(call: Call, request: ListStudentsRequest) -> ListStudentsResponse:
    """Every pupil this account may see, as v1's ``/students``. Writes nothing
    but a credential the diary rotated, and when the session was last used."""
    found = await _service(call).students()
    return ListStudentsResponse(students=[_student(student) for student in found])


async def list_periods(call: Call, request: ListPeriodsRequest) -> ListPeriodsResponse:
    """A pupil's quarters or terms, as v1's ``/periods``: none for a pupil the
    diary files under no class."""
    _feature(call, Feature.PERIODS)
    svc = _service(call)
    student = await svc.student(request.student_id)
    found = await svc.periods_of(student)
    return ListPeriodsResponse(periods=[_period(period) for period in found])


async def list_diary_subjects(
    call: Call, request: ListDiarySubjectsRequest
) -> ListDiarySubjectsResponse:
    """The subjects of a period, the current one when none is named, as v1's
    ``/subjects``: none when the pupil has no class or no period is current."""
    _feature(call, Feature.SUBJECTS)
    svc = _service(call)
    student = await svc.student(request.student_id)
    period = request.period_id if request.has_field("period_id") else None
    found = await svc.subjects_of(student, period)
    return ListDiarySubjectsResponse(
        subjects=[DiarySubject(id=subject.id, name=subject.name) for subject in found]
    )


async def list_teachers(call: Call, request: ListTeachersRequest) -> ListTeachersResponse:
    """A pupil's teachers, as v1's ``/teachers``."""
    _feature(call, Feature.TEACHERS)
    svc = _service(call)
    student = await svc.student(request.student_id)
    found = await svc.teachers(student.education_id)
    return ListTeachersResponse(
        teachers=[
            DiaryTeacher(
                id=teacher.id,
                name=teacher.name,
                position=teacher.position,
                subjects=list(teacher.subjects),
            )
            for teacher in found
        ]
    )


async def list_turnstile_events(
    call: Call, request: ListTurnstileEventsRequest
) -> ListTurnstileEventsResponse:
    """A pupil's turnstile entries and exits, newest first, as v1's
    ``/attendance``: each at the diary's own wall time, to the minute."""
    _feature(call, Feature.TURNSTILE)
    svc = _service(call)
    student = await svc.student(request.student_id)
    found = await svc.attendance(student.education_id)
    return ListTurnstileEventsResponse(
        turnstile_events=[
            DiaryAttendance(
                at=values.wall_moment(event.at),
                direction=_DIRECTIONS.get(event.direction, AttendanceDirection.UNKNOWN),
            )
            for event in found
        ]
    )


# ---- the reads of a window, with the family's corrections laid over -------


def _window(request: Message, svc: diary_service.DiaryService) -> tuple[Date, Date]:
    """The days a read covers, from the diary's own today: 14 on when unset,
    62 at most, as v1's ``from`` and ``to``. Refused on ``start_date`` or
    ``end_date`` before the diary is asked anything, where v1 asked for the
    pupil first."""
    return diary_service.window(*dates.asked(request), svc.today())


def _time(moment: Time | None) -> str | None:
    return values.time_string(moment) if moment is not None else None


def _edits(edits: tuple[overrides.Edit, ...]) -> list[DiaryEdit]:
    return [
        DiaryEdit(
            field=edit.field,
            value=edit.value,
            original=edit.original,
            changed_upstream=edit.changed_upstream,
        )
        for edit in edits
    ]


def _days(lessons: list[overrides.OverlaidLesson]) -> list[DiaryScheduleDay]:
    """The lessons day by day: a day without lessons is not listed, the days
    come in date order, and each day's lessons in the order the diary is read
    in, which for Petersburg is by number."""
    by_day: dict[Date, list[DiaryLesson]] = {}
    for overlaid in lessons:
        lesson = overlaid.lesson
        by_day.setdefault(lesson.date, []).append(
            DiaryLesson(
                number=lesson.number,
                subject=lesson.subject,
                starts_at=_time(lesson.starts_at),
                ends_at=_time(lesson.ends_at),
                room=lesson.room,
                teacher=lesson.teacher,
                homework=lesson.homework,
                topic=lesson.topic,
                target=overlaid.target,
                edits=_edits(overlaid.edits),
                ambiguous=overlaid.ambiguous,
            )
        )
    return [
        DiaryScheduleDay(date=values.date_string(day), lessons=by_day[day])
        for day in sorted(by_day)
    ]


def _mark(mark: Mark) -> DiaryMark:
    return DiaryMark(
        id=mark.id,
        subject_id=mark.subject_id,
        subject=mark.subject_name,
        date=_day(mark.date),
        value=mark.value,
        kind=MarkKind[mark.kind.name],
        reason=mark.reason,
        comment=mark.comment,
    )


async def list_schedule_days(
    call: Call, request: ListScheduleDaysRequest
) -> ListScheduleDaysResponse:
    """A pupil's lessons day by day, with the family's corrections laid over
    them, as v1's ``/schedule``."""
    _feature(call, Feature.SCHEDULE)
    svc = _service(call)
    start, end = _window(request, svc)
    student, scope = await svc.child(request.student_id)
    lessons = await svc.schedule_of(student, scope, start, end)
    return ListScheduleDaysResponse(schedule_days=_days(lessons))


async def list_diary_homework(
    call: Call, request: ListDiaryHomeworkRequest
) -> ListDiaryHomeworkResponse:
    """A pupil's homework by due date, with the family's corrections laid
    over it, as v1's ``/homework``."""
    _feature(call, Feature.HOMEWORK)
    svc = _service(call)
    start, end = _window(request, svc)
    student, scope = await svc.child(request.student_id)
    found = await svc.homework_of(student, scope, start, end)
    return ListDiaryHomeworkResponse(
        homework=[
            DiaryHomework(
                id=overlaid.item.id,
                due_date=values.date_string(overlaid.item.due_date),
                subject=overlaid.item.subject,
                text=overlaid.item.text,
                teacher=overlaid.item.teacher,
                target=overlaid.target,
                edits=_edits(overlaid.edits),
                ambiguous=overlaid.ambiguous,
            )
            for overlaid in found
        ]
    )


async def list_marks(call: Call, request: ListMarksRequest) -> ListMarksResponse:
    """A pupil's marks, absences and lateness, as v1's ``/grades``: never
    corrected."""
    _feature(call, Feature.MARKS)
    svc = _service(call)
    start, end = _window(request, svc)
    student = await svc.student(request.student_id)
    found = await svc.marks(student.education_id, start, end)
    return ListMarksResponse(marks=[_mark(mark) for mark in found])
