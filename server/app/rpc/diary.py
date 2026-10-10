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
(``docs/api.md``, «Not in v2, on purpose»). 3b-8 serves the family's
corrections through ``services/diary_corrections`` — v1's ``/overrides``
rules, with a batch for v1's one at a time — written all or none by
``invoke``'s one commit (``docs/specs/2026-10-05-server-v2-3b-plan.md``).
"""

from __future__ import annotations

from datetime import date as Date
from datetime import time as Time
from typing import TYPE_CHECKING, Any

from protobuf import Message

from app import wording
from app.contract.lessons.v2.diary_pb import (
    AttendanceDirection,
    BatchUpdateCorrectionsRequest,
    BatchUpdateCorrectionsResponse,
    ClearCorrectionsRequest,
    ClearCorrectionsResponse,
    CreateDiarySessionRequest,
    CreateDiarySessionResponse,
    DeleteDiarySessionRequest,
    DeleteDiarySessionResponse,
    DiaryAttendance,
    DiaryCapabilities,
    DiaryCorrection,
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
    ListCorrectionsRequest,
    ListCorrectionsResponse,
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
    ResetCorrectionsRequest,
    ResetCorrectionsResponse,
    SignInMethod,
)
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.crypto import diary_enabled
from app.models import DiaryOverride
from app.models import DiarySession as DiarySessionRow
from app.providers.diary.models import AcademicPeriod, Mark, Student
from app.providers.diary.registry import NETSCHOOL, PETERSBURG, TABLE, Feature, row_for
from app.rpc import dates, values
from app.rpc.errors import Refusal, validate
from app.schemas import (
    DiaryOverrideIn,
    DiaryResetIn,
    NetSchoolSessionIn,
    PetersburgSessionIn,
)
from app.security import DIARY_FAILURES_BUCKET, DIARY_OPENED_BUCKET
from app.services import diary as diary_service
from app.services import diary_corrections
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


# ---- the family's corrections ---------------------------------------------

#: The most corrections one request may carry, written or taken off. A request
#: is one transaction, which keeps every row it writes locked until it commits
#: — on SQLite, the whole file — and a family has a handful of corrections:
#: past this many a request is refused rather than let hold its locks for long
#: (the 3b plan, Ruling 124).
CORRECTIONS_MAX = 200

#: A request past :data:`CORRECTIONS_MAX`. v2's own sentence, naming the field.
TOO_MANY_CORRECTIONS = f"at most {CORRECTIONS_MAX} corrections a request"

#: Where each of the overlay's refusals falls in a correction, and v1's sentence
#: for it (``diary_overrides.check_correction``). The handler's, not the error
#: table's: a row of the table is handed the exception alone, and only the
#: handler knows which correction of the request it was.
_REFUSED: dict[type[ValueError], tuple[str, str]] = {
    overrides.UnknownTarget: ("target", wording.CORRECTION_TARGET_REFUSED_DETAIL),
    overrides.UnsupportedField: ("field", wording.CORRECTION_FIELD_REFUSED_DETAIL),
    overrides.EmptyNotAllowed: ("value", wording.CORRECTION_VALUE_EMPTY_DETAIL),
}


def _capped(count: int) -> None:
    """``VALIDATION_FAILED`` on ``corrections`` past :data:`CORRECTIONS_MAX`,
    before any correction is read."""
    if count > CORRECTIONS_MAX:
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            TOO_MANY_CORRECTIONS,
            violations=[("corrections", TOO_MANY_CORRECTIONS)],
        )


def _updates(request: BatchUpdateCorrectionsRequest) -> list[diary_corrections.Correction]:
    """The corrections asked for, each validated with v1's own schema and then
    by the overlay's rules, in order (decision 5): the first refused names its
    index, counted from 0, and its part — ``corrections[2].value`` — and never
    what was sent. Nothing here asks the diary anything, so a request v2 can
    judge on its own is refused without an upstream call."""
    _capped(len(request.corrections))
    found: list[diary_corrections.Correction] = []
    for index, update in enumerate(request.corrections):
        sent: dict[str, Any] = {
            "target": update.target,
            "field": update.field,
            "value": update.value,
        }
        if update.has_field("original"):
            sent["original"] = update.original
        form = validate(DiaryOverrideIn, sent, at=f"corrections[{index}].")
        try:
            overrides.check_correction(form.target, form.field, form.value)
        except (
            overrides.UnknownTarget,
            overrides.UnsupportedField,
            overrides.EmptyNotAllowed,
        ) as error:
            part, sentence = _REFUSED[type(error)]
            raise Refusal(
                ErrorReason.VALIDATION_FAILED,
                sentence,
                violations=[(f"corrections[{index}].{part}", sentence)],
            ) from None
        found.append(
            diary_corrections.Correction(form.target, form.field, form.value, form.original)
        )
    return found


def _correction(row: DiaryOverride) -> DiaryCorrection:
    """v1's ``DiaryOverrideOut``, field for field: ``original_when_written`` is
    what the diary said when the correction was written, apart on purpose from
    a read's ``original``, which is what it says now."""
    return DiaryCorrection(
        target=row.target,
        field=row.field,
        value=row.value,
        original_when_written=row.original,
        updated_at=values.instant(row.updated_at),
    )


async def list_corrections(call: Call, request: ListCorrectionsRequest) -> ListCorrectionsResponse:
    """Every correction anybody who sees this pupil made, both parents' alike,
    as v1's ``GET /overrides``: by target, then field, and none for a pupil who
    can have none. Writes nothing but a credential the diary rotated, and when
    the session was last used."""
    svc = _service(call)
    student, scope = await svc.child(request.student_id)
    found = await diary_corrections.listed(call.session, scope, student.id)
    return ListCorrectionsResponse(corrections=[_correction(row) for row in found])


async def batch_update_corrections(
    call: Call, request: BatchUpdateCorrectionsRequest
) -> BatchUpdateCorrectionsResponse:
    """Writes or replaces corrections, all or none, as v1's ``PUT /overrides``
    writes one: ``invoke`` commits them together, or rolls every one back.

    In this order: each correction, checked as v1 checks one (:func:`_updates`);
    the pupil, from the session's own diary; the pupil's scope,
    ``CORRECTIONS_UNAVAILABLE`` for one who can have none; then the writes. No
    read of the diary comes after the first write, so the session's own
    telemetry, which ``DiaryService`` commits as it reads, can never commit
    half a batch. The answer is each correction asked for, in order, as stored
    once all are written."""
    updates = _updates(request)
    svc = _service(call)
    student, scope = await svc.child(request.student_id)
    stored = await diary_corrections.correct(call.session, scope, student.id, updates)
    return BatchUpdateCorrectionsResponse(corrections=[_correction(row) for row in stored])


def _keys(request: ResetCorrectionsRequest) -> list[tuple[str, str]]:
    """The corrections to take off, each validated with v1's own schema,
    ``DiaryResetIn``: a target and a field, and nothing of their shape — a key
    that names nothing takes nothing off. The first refused names its index,
    from 0, and its part, and never what was sent."""
    _capped(len(request.corrections))
    keys: list[tuple[str, str]] = []
    for index, key in enumerate(request.corrections):
        form = validate(
            DiaryResetIn, {"target": key.target, "field": key.field}, at=f"corrections[{index}]."
        )
        keys.append((form.target, form.field))
    return keys


async def reset_corrections(
    call: Call, request: ResetCorrectionsRequest
) -> ResetCorrectionsResponse:
    """Takes the named corrections off, for everyone who sees the pupil, as
    v1's ``POST /overrides/reset`` takes one off, all or none: the same answer
    whether or not there was one, and for a pupil who can have none. The keys
    are checked before the diary is asked anything."""
    keys = _keys(request)
    svc = _service(call)
    student, scope = await svc.child(request.student_id)
    await diary_corrections.reset(call.session, scope, student.id, keys)
    return ResetCorrectionsResponse()


async def clear_corrections(
    call: Call, request: ClearCorrectionsRequest
) -> ClearCorrectionsResponse:
    """Takes every correction for this pupil off, and nothing of any other's,
    as v1's ``DELETE /overrides/all``: nothing for a pupil who can have none."""
    svc = _service(call)
    student, scope = await svc.child(request.student_id)
    await diary_corrections.clear(call.session, scope, student.id)
    return ClearCorrectionsResponse()
