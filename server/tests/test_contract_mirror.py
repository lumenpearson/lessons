"""The v2 messages, held level with the v1 schemas they were derived from.

Design decision 9 says every v2 message is derived from a v1 Pydantic schema
field by field, with each rename listed in the plan. Nothing held that after
the plan was written: v1 stays in service through the transition, so a field
added to ``app/schemas`` next week would reach the phones that speak v1 and
silently miss the ones that speak v2. This file is where that fails instead.

``MIRRORS`` has one row per v2 message whose comment in
``proto/lessons/v2`` says ``// v1: SomeSchema``. A row names the v1 schemas
the message was derived from (several when v1 had a separate In, Out and Patch
for one thing) and the whole difference between the two field sets:

- ``renamed``: v1 name to ``(v2 name, reason)``, where the lint rules or the
  resource vocabulary of the design changed the name;
- ``dropped``: a v1 field v2 does not carry, with the reason;
- ``added``: a v2 field v1 does not have, with the reason.

Every rename, drop and addition carries a reason, checked non-empty, that names
where it was decided: a row of the plan's table (``_PLAN``) or the comment in
``proto/lessons/v2`` that says it. A
difference nobody wrote down is not entered here to make the test pass: it
fails, because it may be a v1 capability v2 forgot, and that is a defect to
file rather than a row to add.

Both sides are read by field name. The v1 schemas use no ``alias`` anywhere
(``test_no_v1_schema_renames_a_field_on_the_wire`` holds that), so a Pydantic
attribute name is what v1 sends. v2 is read from the descriptors of the
generated code, as ``test_contract.py`` reads them, rather than from the proto
text: a field is whatever the descriptor says it is, ``oneof`` members and
``map`` fields included, with no grammar to keep level. The proto text is read
for one thing only, the comment above each message, because comments are not in
the descriptors.

What the file does not compare: types (times lose their seconds, instants
become Timestamps, enums go by name; design decision 10 and the values section
of ``docs/api.md``), nesting, or what a field means. It holds the *names* level,
which is what a forgotten field is.
"""

from __future__ import annotations

import importlib
import inspect
import pkgutil
import re
from collections.abc import Iterator
from pathlib import Path
from typing import Any, NamedTuple

import pytest
from pydantic import BaseModel

import app.schemas
from app.contract.lessons.v2 import (
    access_request_pb,
    audit_pb,
    bell_pb,
    class_device_pb,
    common_pb,
    day_pb,
    device_pb,
    diary_pb,
    directory_pb,
    event_pb,
    homework_pb,
    me_pb,
    schedule_pb,
    school_class_pb,
    subject_pb,
    substitution_pb,
    timetable_pb,
)

PROTO = Path(__file__).resolve().parents[2] / "proto" / "lessons" / "v2"

#: The heading in docs/specs/2026-10-04-contract-v2-plan.md whose table holds
#: the rename decisions; a reason that cites a row of it starts with this.
_PLAN = "plan «From v1 to v2: every rename and reshape, with its reason»"


class Mirror(NamedTuple):
    #: The v1 schemas the message was derived from; their fields are unioned.
    v1: tuple[str, ...]
    #: v1 field name to (v2 field name, why the name changed).
    renamed: dict[str, tuple[str, str]] = {}
    #: v1 field name to why v2 has no such field.
    dropped: dict[str, str] = {}
    #: v2 field name to why v1 has no such field.
    added: dict[str, str] = {}


#: Keyed by the v2 message's name, dotted for a nested one.
MIRRORS: dict[str, Mirror] = {
    "AccessRequest": Mirror(("AccessRequestOut",)),
    "ApproveAccessRequestResponse": Mirror(
        ("RequestDecisionOut",),
        renamed={
            "id": (
                "request_id",
                f"{_PLAN}, row RequestDecisionOut: «{{request_id, role, who}}»",
            )
        },
        dropped={
            "status": (
                f"{_PLAN}, row RequestDecisionOut: «status implied by the "
                "method»; proto comment on ApproveAccessRequestResponse"
            )
        },
    ),
    "AuditEntry": Mirror(("AuditEntryOut",)),
    "BellSchedule": Mirror(
        ("BellScheduleOut", "BellScheduleIn", "BellSchedulePatch"),
        dropped={
            "silenced_lessons": (
                f"{_PLAN}, row BellScheduleOut.silenced_lessons: «a fact of the "
                "write», so it is UpdateBellScheduleResponse.silenced_lessons; "
                "proto comment on that field"
            )
        },
    ),
    "BellPeriod": Mirror(("BellPeriodIn", "BellPeriodOut")),
    "ClassDevice": Mirror(
        ("ManagedDeviceOut",),
        added={
            "client_version": (
                "proto comment on ClassDevice.client_version: new in v2, recorded by "
                "v2's gate only (server-v2 design, decision 15); v1's answer keeps its "
                "shape, because that design delivers no change to v1"
            )
        },
    ),
    "Term": Mirror(("TermOut",)),
    "Day": Mirror(("DayIn", "DayOverrideOut")),
    "DiaryBinding": Mirror(("DiaryBindingOut",)),
    "DiaryCapabilities": Mirror(
        ("DiaryCapabilitiesOut",),
        dropped={
            "registration": (
                "proto comment on DiaryCapabilities: every v2 server "
                "registers sessions"
            )
        },
    ),
    "PetersburgCredential": Mirror(("PetersburgCredentialIn",)),
    "NetSchoolCredential": Mirror(("NetSchoolCredentialIn",)),
    "NetSchoolCookies": Mirror(
        ("NetSchoolCookiesIn",),
        renamed={
            "NSSESSIONID": (
                "ns_session_id",
                f"{_PLAN}, row NetSchoolCookiesIn: «FIELD_LOWER_SNAKE_CASE»",
            ),
            "ESRNSec": (
                "esrn_sec",
                f"{_PLAN}, row NetSchoolCookiesIn: «FIELD_LOWER_SNAKE_CASE»",
            ),
        },
    ),
    "DiarySession": Mirror(("DiarySessionOut",)),
    "DiaryStudent": Mirror(("DiaryStudentOut",)),
    "DiaryEdit": Mirror(("DiaryEditOut",)),
    "DiaryLesson": Mirror(
        ("DiaryLessonOut",),
        dropped={"date": "proto comment on DiaryLesson: the date is its day's now"},
    ),
    "DiaryHomework": Mirror(("DiaryHomeworkOut",)),
    "DiaryMark": Mirror(("DiaryMarkOut",)),
    "DiaryPeriod": Mirror(("DiaryPeriodOut",)),
    "DiarySubject": Mirror(("DiarySubjectOut",)),
    "DiaryTeacher": Mirror(("DiaryTeacherOut",)),
    "DiaryAttendance": Mirror(("DiaryAttendanceOut",)),
    "DiaryCorrection": Mirror(("DiaryOverrideOut",)),
    "CorrectionUpdate": Mirror(("DiaryOverrideIn",)),
    "CorrectionKey": Mirror(("DiaryResetIn",)),
    "SchoolRegion": Mirror(("SchoolRegionOut",)),
    "School": Mirror(("SchoolOut",)),
    "ListSchoolRegionsResponse": Mirror(("SchoolRegionsOut",)),
    "ListSchoolsResponse": Mirror(
        ("SchoolSearchOut",),
        renamed={
            "items": (
                "schools",
                f"{_PLAN}, row SchoolSearchOut: «ListSchools{{schools,…}}»",
            ),
            "total": (
                "total_size",
                f"{_PLAN}, row SchoolSearchOut: «total_size»; AIP-158 paging",
            ),
        },
        dropped={
            "page": "proto comment on ListSchoolsResponse: AIP-158 paging",
            "pages": "proto comment on ListSchoolsResponse: AIP-158 paging",
        },
        added={
            "next_page_token": (
                f"{_PLAN}, row SchoolSearchOut: «next_page_token»; proto comment "
                "on ListSchoolsResponse: AIP-158 paging instead of page and pages"
            )
        },
    ),
    "Event": Mirror(("EventIn", "EventCreatedOut")),
    "Homework": Mirror(("HomeworkItemOut", "HomeworkIn")),
    "Me": Mirror(
        ("MeOut",),
        dropped={
            "link_code": "proto comment on Me: the code is CreateLinkCode's now",
            "bot_deep_link": "proto comment on Me: the code is CreateLinkCode's now",
        },
    ),
    "LinkCode": Mirror(
        ("MeOut",),
        renamed={
            "link_code": (
                "code",
                f"{_PLAN}, row MeOut.link_code: «CreateLinkCode → LinkCode»; "
                "proto comment on LinkCode.code",
            )
        },
        dropped={
            name: (
                "proto comment on LinkCode: «v1: MeOut.link_code and "
                "MeOut.bot_deep_link.», so the rest of MeOut stays on Me"
            )
            for name in ("device_name", "linked", "role", "can_edit")
        },
    ),
    "CalendarFeed": Mirror(("CalendarOut",)),
    "Task": Mirror(("TaskOut", "TaskIn", "TaskPatch")),
    "HomeworkTick": Mirror(
        ("DoneIn", "DoneOut"),
        renamed={
            "id": (
                "homework_id",
                f"{_PLAN}, row DoneIn/DoneOut; proto comment on HomeworkTick",
            )
        },
        dropped={
            "done": (
                f"{_PLAN}, row DoneIn/DoneOut: «a tick is a resource», "
                "CreateHomeworkTick and DeleteHomeworkTick"
            )
        },
    ),
    "ScheduleWindow": Mirror(
        ("BundleOut",),
        dropped={
            "api_version": "proto comment on ScheduleWindow: the package name is the version",
            "next_school_day": "proto comment on ScheduleWindow: a v2 window is a school year",
        },
    ),
    "ClassSummary": Mirror(("ClassOut",)),
    "DeviceAccess": Mirror(("DeviceOut",)),
    "ScheduleDay": Mirror(("DayOut",)),
    "Holiday": Mirror(("HolidayOut",)),
    "Lesson": Mirror(("LessonOut",)),
    "ScheduleEvent": Mirror(("EventOut",)),
    "ScheduleHomework": Mirror(("HomeworkOut",)),
    "SchoolClass": Mirror(("ManagedClassOut", "ClassPatch")),
    "ClassStats": Mirror(
        ("StatsOut",),
        renamed={
            "overrides_upcoming": (
                "substitutions_upcoming",
                f"{_PLAN}, row StatsOut.overrides_upcoming: «'overrides' meant "
                "corrections on the diary side»; proto comment on the field",
            )
        },
    ),
    "SubjectHours": Mirror(("SubjectHoursOut",)),
    "TermScheme": Mirror(
        ("TermsOut",),
        dropped={
            "terms": (
                "proto comment on TermScheme: «v1: TermsOut's kind and year»; "
                f"{_PLAN}, row TermsOut: TermsOut becomes TermScheme and "
                "ListTerms, and ListTermsResponse.terms carries them"
            )
        },
    ),
    "Subject": Mirror(("SubjectOut", "ManagedSubjectOut", "SubjectIn", "SubjectPatch")),
    "Substitution": Mirror(
        ("OverrideIn", "OverrideOut"),
        added={"id": "proto comment on Substitution: now with an id"},
    ),
    "Timetable": Mirror(("TimetableExportOut",)),
    "ImportConflict": Mirror(("ImportConflictOut",)),
    "ImportTimetableRequest": Mirror(
        ("TimetableImportIn",),
        added={
            "validate_only": (
                f"{_PLAN}, row TimetableImportIn: «ImportTimetableRequest"
                "{validate_only}», AIP-163 preview; proto comment on the request"
            )
        },
    ),
    "ImportTimetableResponse": Mirror(("TimetableImportOut",)),
}


# ---- reading both sides ------------------------------------------------------

_PB_MODULES = (
    access_request_pb,
    audit_pb,
    bell_pb,
    class_device_pb,
    common_pb,
    day_pb,
    device_pb,
    diary_pb,
    directory_pb,
    event_pb,
    homework_pb,
    me_pb,
    schedule_pb,
    school_class_pb,
    subject_pb,
    substitution_pb,
    timetable_pb,
)


def _v1_schemas() -> dict[str, type[BaseModel]]:
    """Every Pydantic model defined in a module of ``app.schemas``, by name."""
    found: dict[str, type[BaseModel]] = {}
    for info in pkgutil.iter_modules(app.schemas.__path__):
        module = importlib.import_module(f"app.schemas.{info.name}")
        for name, value in vars(module).items():
            if (
                inspect.isclass(value)
                and issubclass(value, BaseModel)
                and value.__module__ == module.__name__
            ):
                found.setdefault(name, value)
    return found


def _v2_fields() -> dict[str, set[str]]:
    """Every message of lessons.v2 by short dotted name, as its field names."""
    found: dict[str, set[str]] = {}

    def walk(messages: Any, prefix: str) -> None:
        for message in messages:
            name = prefix + message.proto.name
            found[name] = {field.name for field in message.proto.field}
            walk(message.nested_messages, name + ".")

    for module in _PB_MODULES:
        walk(module.desc().messages, "")
    return found


def _commented_v1() -> dict[str, tuple[str, ...]]:
    """Each v2 message whose own comment says ``v1:`` and names a v1 schema,
    with the schemas it names.

    Only the comment block directly above a ``message`` line counts: a field's
    comment saying ``v1: BellScheduleOut.silenced_lessons`` belongs to a field
    of a different message and is the table's ``dropped`` and ``added``
    reasons' business. Every word after the first ``v1:`` that is a schema's
    name is taken as named.
    """
    schemas = _v1_schemas()
    found: dict[str, tuple[str, ...]] = {}
    for path in sorted(PROTO.glob("*.proto")):
        comment: list[str] = []
        scope: list[str] = []
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if line.startswith("//"):
                comment.append(line[2:].strip())
                continue
            opened = re.match(r"message (\w+) \{(?!.*\})", line)
            if opened:
                text = " ".join(comment)
                _, marker, after = text.partition("v1:")
                if marker:
                    named = tuple(
                        dict.fromkeys(
                            word
                            for word in re.findall(r"[A-Za-z_]\w*", after)
                            if word in schemas
                        )
                    )
                    if named:
                        found[".".join([*scope, opened.group(1)])] = named
                scope.append(opened.group(1))
            elif line.endswith("{") and re.match(r"(enum|oneof|service|extend) ", line):
                scope.append("")
            elif line.startswith("}") and scope:
                scope.pop()
            comment = []
    # A nested scope contributes no name segment of its own.
    return {name.replace("..", "."): named for name, named in found.items()}


def _v1_fields(row: Mirror) -> set[str]:
    schemas = _v1_schemas()
    return {name for schema in row.v1 for name in schemas[schema].model_fields}


def _rows() -> Iterator[Any]:
    for message, row in MIRRORS.items():
        yield pytest.param(message, row, id=message)


# ---- the tests ---------------------------------------------------------------


def test_every_v1_comment_in_the_protos_has_a_row() -> None:
    """A message that says ``// v1: SomeSchema`` is derived from it and must be
    held level with it. Without this, a new mirrored message skips the table
    and nothing ever compares it, which is how a field goes missing quietly."""
    commented = _commented_v1()
    assert sorted(commented.keys() - MIRRORS.keys()) == [], (
        "messages whose comment names a v1 schema but that have no row in "
        "MIRRORS: add one"
    )
    assert sorted(MIRRORS.keys() - commented.keys()) == [], (
        "rows of MIRRORS whose message no longer says «v1: <schema>» in its "
        "comment: put the comment back, or delete the row"
    )
    for message, named in commented.items():
        assert set(MIRRORS[message].v1) == set(named), (
            f"{message}: the comment names {sorted(named)} but the row "
            f"derives it from {sorted(MIRRORS[message].v1)}"
        )


def test_every_row_names_a_v1_schema_that_exists() -> None:
    """A v1 schema renamed or deleted would turn its row into a comparison
    with nothing; this is where that is said in words rather than as a
    KeyError, and the message the row belongs to must exist in v2 too."""
    schemas = _v1_schemas()
    v2 = _v2_fields()
    for message, row in MIRRORS.items():
        missing = [name for name in row.v1 if name not in schemas]
        assert missing == [], f"{message}: no such v1 schema in app.schemas: {missing}"
        assert message in v2, f"{message}: no such message in lessons.v2"


def test_no_v1_schema_renames_a_field_on_the_wire() -> None:
    """The comparison reads v1's attribute names because those are the names
    on the wire. A schema that adds an ``alias`` makes them differ, and this
    says so before the comparison quietly reads the wrong side."""
    aliased = sorted(
        f"{name}.{field}"
        for name, schema in _v1_schemas().items()
        for field, info in schema.model_fields.items()
        if info.alias is not None
        or info.validation_alias is not None
        or info.serialization_alias is not None
    )
    assert aliased == [], f"v1 fields with an alias; compare their alias instead: {aliased}"


@pytest.mark.parametrize(("message", "row"), list(_rows()))
def test_a_v2_message_carries_every_field_of_the_v1_schema_it_came_from(
    message: str, row: Mirror
) -> None:
    """The point of the file. Both directions, so a field added to v1 and not
    to v2 fails here, and so does a v2 field with no v1 origin and no reason."""
    v1 = _v1_fields(row)
    v2 = _v2_fields()[message]
    where = f"{message} (v1: {', '.join(row.v1)})"

    stale = {
        "renamed (v1 side)": sorted(row.renamed.keys() - v1),
        "dropped": sorted(row.dropped.keys() - v1),
        "added": sorted(row.added.keys() & v1),
        "renamed (v2 side)": sorted({new for new, _ in row.renamed.values()} - v2),
    }
    for kind, names in stale.items():
        assert names == [], f"{where}: stale {kind} entries {names}: table and code differ"
    overlap = sorted(row.renamed.keys() & row.dropped.keys())
    assert overlap == [], f"{where}: {overlap} are both renamed and dropped"

    carried = {
        row.renamed[name][0] if name in row.renamed else name
        for name in v1
        if name not in row.dropped
    }

    lost = sorted(carried - v2)
    assert lost == [], (
        f"{where}: v1 carries {lost} and v2 does not. Add the field to the "
        f"proto, or record it in this row's `dropped` with the document that "
        f"says why it goes."
    )
    unexplained = sorted(v2 - carried - row.added.keys())
    assert unexplained == [], (
        f"{where}: v2 carries {unexplained} and v1 does not. Derive it from a "
        f"v1 field (`renamed`), or record it in this row's `added` with the "
        f"document that says why it is new."
    )
    reasons = [
        *(reason for _, reason in row.renamed.values()),
        *row.dropped.values(),
        *row.added.values(),
    ]
    assert all(reason.strip() for reason in reasons), f"{where}: a reason is empty"
