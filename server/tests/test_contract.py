"""The v2 contract, read back from the code generated out of proto/lessons/v2.

CI's «Contract» job holds what Buf can: the STANDARD lint rules, the FILE
breaking-change rules against the base branch, and that ``server/app/contract``
is exactly what ``proto/`` generates. What Buf cannot hold is what a method
means on the wire, and that is here, read from the generated descriptors, so it
needs neither Buf nor the network:

- every method says which credential it takes (``(lessons.v2.auth)``), and
  every method acting on the class names the least role
  (``(lessons.v2.min_role)``), reads included -- sub-project 3 enforces both
  from the descriptor, in one place;
- every unary method has exactly one REST binding under ``/v2/``, standard
  methods on their standard verbs and custom ones as ``POST …:verb``, and a
  stream has none;
- every Get and List is ``NO_SIDE_EFFECTS``, which is what lets Connect send it
  as a GET, and nothing else is;
- ``METHODS`` is the resource map of
  ``docs/specs/2026-10-04-contract-v2-design.md`` row for row, and ``REASONS``
  its error table, so neither moves without this file moving with it;
- every generated module imports, keeps its imports inside ``app.contract``,
  was written by the plugin version ``buf.gen.yaml`` pins, and stays off the
  API's cold path.
"""

from __future__ import annotations

import ast
import importlib
import json
import os
import re
import subprocess
import sys
from collections.abc import Iterator
from pathlib import Path
from types import ModuleType
from typing import Any, NamedTuple

import pytest

from app.contract.lessons.v2 import errors_pb, options_pb

SERVER = Path(__file__).resolve().parents[1]
REPOSITORY = SERVER.parent
PROTO = REPOSITORY / "proto" / "lessons" / "v2"
CONTRACT = SERVER / "app" / "contract"
V2 = CONTRACT / "lessons" / "v2"

JAVA_PACKAGE = "com.lumenpearson.lessons.contract.v2"

#: Every file of ``proto/lessons/v2``, by the stem of its generated module. A
#: proto file added without regenerating, or a module left behind by a deleted
#: file, fails here before CI's regeneration finds it.
FILES = {
    "access_request",
    "audit",
    "bell",
    "class_device",
    "common",
    "day",
    "device",
    "diary",
    "directory",
    "errors",
    "event",
    "homework",
    "me",
    "options",
    "school_class",
    "schedule",
    "subject",
    "substitution",
    "timetable",
    "watch",
}

#: The files that declare a service, beside which ``buf.build/connectrpc/py``
#: also writes a ``_connect`` module.
SERVICE_FILES = {
    "access_request",
    "audit",
    "bell",
    "class_device",
    "day",
    "device",
    "diary",
    "directory",
    "event",
    "homework",
    "me",
    "school_class",
    "schedule",
    "subject",
    "substitution",
    "timetable",
    "watch",
}

# AuthKind's and Role's numbers, so the table below reads the way the design's
# does; test_the_option_enums_are_numbered_as_this_file_reads_them pins them to
# the names in options.proto.
NONE, DEVICE, DEVICE_LINKED, DIARY = 1, 2, 3, 4
VIEWER, EDITOR, ADMIN, OWNER = 1, 2, 3, 4

#: MethodOptions.IdempotencyLevel.NO_SIDE_EFFECTS, from descriptor.proto.
NO_SIDE_EFFECTS = 1


class Row(NamedTuple):
    """One method as the resource map states it."""

    #: The google.api.http verb -- "get", "post", "patch" or "delete" -- or
    #: None for a stream, which has no REST binding.
    verb: str | None
    #: The annotation's path as the proto writes it; it is served under /api.
    path: str | None
    #: "" for no body, "*" for the whole request, or the request field it is.
    body: str
    auth: int
    min_role: int | None


#: The resource map (the design's decision 7), one row per method. A method
#: added, moved, re-verbed or re-permissioned changes its row here in the same
#: commit, or the suite fails.
METHODS: dict[tuple[str, str], Row] = {
    ("DeviceService", "CreateDevice"): Row("post", "/v2/devices", "*", NONE, None),
    ("MeService", "GetMe"): Row("get", "/v2/me", "", DEVICE, None),
    ("MeService", "UnlinkMe"): Row("post", "/v2/me:unlink", "*", DEVICE, None),
    ("MeService", "CreateLinkCode"): Row("post", "/v2/me/linkCodes", "*", DEVICE, None),
    ("MeService", "GetCalendarFeed"): Row(
        "get", "/v2/me/calendarFeed", "", DEVICE_LINKED, None
    ),
    ("MeService", "CreateCalendarFeed"): Row(
        "post", "/v2/me/calendarFeed", "*", DEVICE_LINKED, None
    ),
    ("MeService", "ListTasks"): Row("get", "/v2/me/tasks", "", DEVICE_LINKED, None),
    ("MeService", "GetTask"): Row("get", "/v2/me/tasks/{task_id}", "", DEVICE_LINKED, None),
    ("MeService", "CreateTask"): Row("post", "/v2/me/tasks", "task", DEVICE_LINKED, None),
    ("MeService", "UpdateTask"): Row(
        "patch", "/v2/me/tasks/{task.id}", "task", DEVICE_LINKED, None
    ),
    ("MeService", "DeleteTask"): Row(
        "delete", "/v2/me/tasks/{task_id}", "", DEVICE_LINKED, None
    ),
    ("MeService", "CreateHomeworkTick"): Row(
        "post", "/v2/me/homeworkTicks", "homework_tick", DEVICE_LINKED, None
    ),
    ("MeService", "DeleteHomeworkTick"): Row(
        "delete", "/v2/me/homeworkTicks/{homework_id}", "", DEVICE_LINKED, None
    ),
    ("ScheduleService", "GetScheduleWindow"): Row(
        "get", "/v2/class/scheduleWindows/{year}", "", DEVICE, VIEWER
    ),
    ("ClassService", "GetClass"): Row("get", "/v2/class", "", DEVICE, ADMIN),
    ("ClassService", "UpdateClass"): Row("patch", "/v2/class", "school_class", DEVICE, ADMIN),
    ("ClassService", "DeleteClass"): Row("delete", "/v2/class", "", DEVICE, OWNER),
    ("ClassService", "GetClassStats"): Row("get", "/v2/class/stats", "", DEVICE, EDITOR),
    ("ClassService", "GetTermScheme"): Row("get", "/v2/class/termScheme", "", DEVICE, ADMIN),
    ("ClassService", "UpdateTermScheme"): Row(
        "patch", "/v2/class/termScheme", "term_scheme", DEVICE, ADMIN
    ),
    ("ClassService", "ListTerms"): Row("get", "/v2/class/terms", "", DEVICE, ADMIN),
    ("ClassService", "UpdateTerm"): Row(
        "patch", "/v2/class/terms/{term.index}", "term", DEVICE, ADMIN
    ),
    ("SubjectService", "ListSubjects"): Row("get", "/v2/class/subjects", "", DEVICE, VIEWER),
    ("SubjectService", "GetSubject"): Row(
        "get", "/v2/class/subjects/{subject_id}", "", DEVICE, VIEWER
    ),
    ("SubjectService", "CreateSubject"): Row(
        "post", "/v2/class/subjects", "subject", DEVICE, ADMIN
    ),
    ("SubjectService", "UpdateSubject"): Row(
        "patch", "/v2/class/subjects/{subject.id}", "subject", DEVICE, ADMIN
    ),
    ("SubjectService", "DeleteSubject"): Row(
        "delete", "/v2/class/subjects/{subject_id}", "", DEVICE, ADMIN
    ),
    ("BellService", "ListBellSchedules"): Row(
        "get", "/v2/class/bellSchedules", "", DEVICE, ADMIN
    ),
    ("BellService", "GetBellSchedule"): Row(
        "get", "/v2/class/bellSchedules/{schedule_id}", "", DEVICE, ADMIN
    ),
    ("BellService", "CreateBellSchedule"): Row(
        "post", "/v2/class/bellSchedules", "schedule", DEVICE, ADMIN
    ),
    ("BellService", "UpdateBellSchedule"): Row(
        "patch", "/v2/class/bellSchedules/{schedule.id}", "schedule", DEVICE, ADMIN
    ),
    ("BellService", "DeleteBellSchedule"): Row(
        "delete", "/v2/class/bellSchedules/{schedule_id}", "", DEVICE, ADMIN
    ),
    ("TimetableService", "GetTimetable"): Row(
        "get", "/v2/class/timetable", "", DEVICE, ADMIN
    ),
    ("TimetableService", "ImportTimetable"): Row(
        "post", "/v2/class/timetable:import", "*", DEVICE, ADMIN
    ),
    ("HomeworkService", "ListHomework"): Row("get", "/v2/class/homework", "", DEVICE, VIEWER),
    ("HomeworkService", "GetHomework"): Row(
        "get", "/v2/class/homework/{homework_id}", "", DEVICE, VIEWER
    ),
    ("HomeworkService", "CreateHomework"): Row(
        "post", "/v2/class/homework", "homework", DEVICE, EDITOR
    ),
    ("HomeworkService", "UpdateHomework"): Row(
        "patch", "/v2/class/homework/{homework.id}", "homework", DEVICE, EDITOR
    ),
    ("HomeworkService", "DeleteHomework"): Row(
        "delete", "/v2/class/homework/{homework_id}", "", DEVICE, EDITOR
    ),
    ("SubstitutionService", "ListSubstitutions"): Row(
        "get", "/v2/class/substitutions", "", DEVICE, EDITOR
    ),
    ("SubstitutionService", "GetSubstitution"): Row(
        "get", "/v2/class/substitutions/{substitution_id}", "", DEVICE, EDITOR
    ),
    ("SubstitutionService", "CreateSubstitution"): Row(
        "post", "/v2/class/substitutions", "substitution", DEVICE, EDITOR
    ),
    ("SubstitutionService", "UpdateSubstitution"): Row(
        "patch", "/v2/class/substitutions/{substitution.id}", "substitution", DEVICE, EDITOR
    ),
    ("SubstitutionService", "DeleteSubstitution"): Row(
        "delete", "/v2/class/substitutions/{substitution_id}", "", DEVICE, EDITOR
    ),
    ("EventService", "ListEvents"): Row("get", "/v2/class/events", "", DEVICE, EDITOR),
    ("EventService", "GetEvent"): Row("get", "/v2/class/events/{event_id}", "", DEVICE, EDITOR),
    ("EventService", "CreateEvent"): Row("post", "/v2/class/events", "event", DEVICE, EDITOR),
    ("EventService", "UpdateEvent"): Row(
        "patch", "/v2/class/events/{event.id}", "event", DEVICE, EDITOR
    ),
    ("EventService", "DeleteEvent"): Row(
        "delete", "/v2/class/events/{event_id}", "", DEVICE, EDITOR
    ),
    ("DayService", "GetDay"): Row("get", "/v2/class/days/{date}", "", DEVICE, EDITOR),
    ("DayService", "UpdateDay"): Row(
        "patch", "/v2/class/days/{day.date}", "day", DEVICE, EDITOR
    ),
    ("ClassDeviceService", "ListClassDevices"): Row(
        "get", "/v2/class/devices", "", DEVICE, ADMIN
    ),
    ("ClassDeviceService", "RevokeClassDevice"): Row(
        "post", "/v2/class/devices/{device_id}:revoke", "*", DEVICE, ADMIN
    ),
    ("ClassDeviceService", "UnlinkClassDevice"): Row(
        "post", "/v2/class/devices/{device_id}:unlink", "*", DEVICE, ADMIN
    ),
    ("AccessRequestService", "ListAccessRequests"): Row(
        "get", "/v2/class/accessRequests", "", DEVICE, ADMIN
    ),
    ("AccessRequestService", "ApproveAccessRequest"): Row(
        "post", "/v2/class/accessRequests/{request_id}:approve", "*", DEVICE, ADMIN
    ),
    ("AccessRequestService", "DeclineAccessRequest"): Row(
        "post", "/v2/class/accessRequests/{request_id}:decline", "*", DEVICE, ADMIN
    ),
    ("AuditService", "ListAuditEntries"): Row(
        "get", "/v2/class/auditEntries", "", DEVICE, ADMIN
    ),
    ("DirectoryService", "ListSchoolRegions"): Row("get", "/v2/schoolRegions", "", NONE, None),
    ("DirectoryService", "ListSchools"): Row("get", "/v2/schools", "", DEVICE, ADMIN),
    ("DiaryService", "GetDiaryCapabilities"): Row(
        "get", "/v2/diary/capabilities", "", NONE, None
    ),
    ("DiaryService", "CreateDiarySession"): Row("post", "/v2/diary/sessions", "*", NONE, None),
    ("DiaryService", "DeleteDiarySession"): Row(
        "delete", "/v2/diary/sessions/current", "", DIARY, None
    ),
    ("DiaryService", "ListStudents"): Row("get", "/v2/diary/students", "", DIARY, None),
    ("DiaryService", "ListScheduleDays"): Row(
        "get", "/v2/diary/students/{student_id}/scheduleDays", "", DIARY, None
    ),
    ("DiaryService", "ListDiaryHomework"): Row(
        "get", "/v2/diary/students/{student_id}/homework", "", DIARY, None
    ),
    ("DiaryService", "ListMarks"): Row(
        "get", "/v2/diary/students/{student_id}/marks", "", DIARY, None
    ),
    ("DiaryService", "ListPeriods"): Row(
        "get", "/v2/diary/students/{student_id}/periods", "", DIARY, None
    ),
    ("DiaryService", "ListDiarySubjects"): Row(
        "get", "/v2/diary/students/{student_id}/subjects", "", DIARY, None
    ),
    ("DiaryService", "ListTeachers"): Row(
        "get", "/v2/diary/students/{student_id}/teachers", "", DIARY, None
    ),
    ("DiaryService", "ListTurnstileEvents"): Row(
        "get", "/v2/diary/students/{student_id}/turnstileEvents", "", DIARY, None
    ),
    ("DiaryService", "ListCorrections"): Row(
        "get", "/v2/diary/students/{student_id}/corrections", "", DIARY, None
    ),
    ("DiaryService", "BatchUpdateCorrections"): Row(
        "post", "/v2/diary/students/{student_id}/corrections:batchUpdate", "*", DIARY, None
    ),
    ("DiaryService", "ResetCorrections"): Row(
        "post", "/v2/diary/students/{student_id}/corrections:reset", "*", DIARY, None
    ),
    ("DiaryService", "ClearCorrections"): Row(
        "post", "/v2/diary/students/{student_id}/corrections:clear", "*", DIARY, None
    ),
    ("WatchService", "WatchClass"): Row(None, None, "", DEVICE, VIEWER),
}

#: ErrorReason, name for number: the design's decision 6, completed from every
#: refusal v1 makes. A reason is added, never renamed or renumbered.
REASONS = {
    "ERROR_REASON_UNSPECIFIED": 0,
    "DEVICE_TOKEN_INVALID": 1,
    "DIARY_TOKEN_INVALID": 2,
    "DEVICE_NOT_LINKED": 3,
    "ROLE_REQUIRED": 4,
    "JOIN_CODE_UNKNOWN": 5,
    "CLASS_INVITE_ONLY": 6,
    "DEVICE_LIMIT_REACHED": 7,
    "THROTTLED": 8,
    "RESOURCE_NOT_FOUND": 9,
    "RESOURCE_EXISTS": 10,
    "VALIDATION_FAILED": 11,
    "NO_BELL_FOR_LESSON": 12,
    "EMPTY_BELL_SCHEDULE": 13,
    "DIARY_DISABLED": 14,
    "DIARY_UNAVAILABLE": 15,
    "DIARY_REAUTH": 16,
    "DIARY_CREDENTIALS_REJECTED": 17,
    "DIRECTORY_DISABLED": 18,
    "DIRECTORY_SPENT": 19,
    "DIRECTORY_UNAVAILABLE": 20,
    "CLIENT_TOO_OLD": 21,
    "FEATURE_UNSUPPORTED": 22,
    "REQUEST_UNDECODABLE": 23,
    "DIARY_NO_STUDENTS": 24,
    "DIARY_UPSTREAM_UNREADABLE": 25,
    "CORRECTIONS_UNAVAILABLE": 26,
    "RESOURCE_IN_USE": 27,
    "SUBJECT_RENAME_CLASH": 28,
    "CLASS_DEVICE_NOT_LINKED": 29,
    "ROLE_GRANT_REFUSED": 30,
    "TERM_BOUNDS_REFUSED": 31,
    "NO_LESSON_ON_DAY": 32,
    "LESSON_NOT_ON_TIMETABLE": 33,
}

#: What each remote plugin of buf.gen.yaml writes into the header of a module
#: it generates -- «# Generated by protoc-gen-py v0.6.0 with parameter "".» --
#: and the runtime it generates for, as requirements.in names it.
_PLUGIN_HEADERS = {
    "buf.build/bufbuild/py": "protoc-gen-py",
    "buf.build/connectrpc/py": "protoc-gen-connectrpc-py",
}
_PLUGIN_RUNTIMES = {
    "buf.build/bufbuild/py": "protobuf-py",
    "buf.build/connectrpc/py": "connectrpc",
}

_VARIABLE = re.compile(r"\{([^}=]+)(?:=[^}]*)?\}")
_CUSTOM_VERB = re.compile(r":([A-Za-z]+)$")
_STANDARD = {
    "Get": "get",
    "List": "get",
    "Create": "post",
    "Update": "patch",
    "Delete": "delete",
}


# ---- reading the generated code ---------------------------------------------


def _module_name(path: Path) -> str:
    parts = path.relative_to(SERVER).with_suffix("").parts
    return ".".join(parts[:-1] if parts[-1] == "__init__" else parts)


def _generated(suffix: str) -> dict[str, ModuleType]:
    """The generated modules of lessons.v2 ending in ``suffix``, by file stem."""
    return {
        path.stem.removesuffix(suffix): importlib.import_module(_module_name(path))
        for path in sorted(V2.glob(f"*{suffix}.py"))
    }


def _files() -> list[Any]:
    return [module.desc() for module in _generated("_pb").values()]


def _methods() -> Iterator[tuple[Any, Any]]:
    for file in _files():
        for service in file.services:
            for method in service.methods:
                yield service, method


def _enum_option(method: Any, extension: Any) -> int | None:
    options = method.proto.options
    if options is None or extension not in options:
        return None
    return int(options[extension])


def _http_rule(method: Any) -> Any:
    # Imported here: google/api is generated only once a proto file imports it,
    # which the first service does.
    from app.contract.google.api import annotations_pb

    options = method.proto.options
    if options is None or annotations_pb.ext_http not in options:
        return None
    return options[annotations_pb.ext_http]


def _binding(method: Any) -> tuple[str | None, str | None, str]:
    rule = _http_rule(method)
    if rule is None or rule.pattern is None:
        return None, None, ""
    return rule.pattern.field, rule.pattern.value, rule.body or ""


def _streams(method: Any) -> bool:
    return bool(method.proto.server_streaming) or bool(method.proto.client_streaming)


def _side_effect_free(method: Any) -> bool:
    options = method.proto.options
    return options is not None and int(options.idempotency_level or 0) == NO_SIDE_EFFECTS


def _standard(name: str) -> str | None:
    """The standard method a name is (AIP-131 to 135), or None for a custom one."""
    return next((prefix for prefix in _STANDARD if re.match(f"{prefix}[A-Z]", name)), None)


def _on_the_class(service: Any, method: Any) -> bool:
    _verb, path, _body = _binding(method)
    if path is not None:
        return path == "/v2/class" or path.startswith(("/v2/class/", "/v2/class:"))
    # A stream has no path to say whose it is; WatchService's is the class's.
    return service.name == "WatchService"


def _upper_snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).upper()


def _messages() -> dict[str, Any]:
    """Every message of lessons.v2 by full name, nested ones included."""
    found: dict[str, Any] = {}

    def walk(messages: Any) -> None:
        for message in messages:
            found[message.type_name] = message
            walk(message.nested_messages)

    for file in _files():
        walk(file.messages)
    return found


def _resolves(message: Any, dotted: str, messages: dict[str, Any]) -> bool:
    """Whether ``dotted`` -- ``task_id``, ``subject.id`` -- is a field path of
    ``message``."""
    for part in dotted.split("."):
        if message is None:
            return False
        field = next((f for f in message.proto.field if f.name == part), None)
        if field is None:
            return False
        message = messages.get((field.type_name or "").lstrip("."))
    return True


def _enum_protos(file_proto: Any) -> Iterator[Any]:
    yield from file_proto.enum_type

    def nested(messages: Any) -> Iterator[Any]:
        for message in messages:
            yield from message.enum_type
            yield from nested(message.nested_type)

    yield from nested(file_proto.message_type)


def _is_app(name: str) -> bool:
    return name == "app" or name.startswith("app.")


# ---- the generated tree -----------------------------------------------------


def test_every_proto_file_was_generated() -> None:
    """Read from ``proto/`` itself, so that a file added there without a
    regeneration -- or one deleted there whose module was left behind -- fails
    here rather than only in CI's regeneration."""
    protos = sorted(PROTO.glob("*.proto"))
    stems = {path.stem for path in protos}
    declares_service = {
        path.stem
        for path in protos
        if re.search(r"^service\s", path.read_text(encoding="utf-8"), re.MULTILINE)
    }
    assert stems == FILES, "FILES is not the file list of proto/lessons/v2"
    assert declares_service == SERVICE_FILES, "SERVICE_FILES is not the services' files"

    messages = {path.name.removesuffix("_pb.py") for path in V2.glob("*_pb.py")}
    services = {path.name.removesuffix("_connect.py") for path in V2.glob("*_connect.py")}
    assert sorted(stems - messages) == [], "proto files with no generated _pb module"
    assert sorted(messages - stems) == [], "generated _pb modules with no proto file"
    assert services == declares_service

    assert set(_generated("_pb")) == FILES
    assert set(_generated("_connect")) == SERVICE_FILES


def test_every_generated_module_imports() -> None:
    """On CI that is Linux CPython 3.12, which the spike never ran it on."""
    names = [_module_name(path) for path in sorted(CONTRACT.rglob("*.py"))]
    assert names, "server/app/contract is empty -- run buf generate"
    for name in names:
        importlib.import_module(name)


def test_generated_code_stays_inside_app_contract() -> None:
    """protobuf-py writes relative imports, which is why its output sits in a
    package (the design's decision 2). One that climbs out of ``app.contract``
    would mean the tree was generated for another place, and an absolute
    ``app`` import would tie generated code to hand-written code that no
    regeneration knows about."""
    escapes: list[str] = []
    for path in sorted(CONTRACT.rglob("*.py")):
        package = path.relative_to(SERVER).with_suffix("").parts[:-1]
        where = path.relative_to(SERVER).as_posix()
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if isinstance(node, ast.ImportFrom) and node.level:
                if len(package) - (node.level - 1) < 2:
                    escapes.append(f"{where}:{node.lineno} climbs out of app.contract")
            elif isinstance(node, ast.ImportFrom) and _is_app(node.module or ""):
                escapes.append(f"{where}:{node.lineno} imports {node.module}")
            elif isinstance(node, ast.Import):
                escapes += [
                    f"{where}:{node.lineno} imports {alias.name}"
                    for alias in node.names
                    if _is_app(alias.name)
                ]
    assert escapes == []


def test_the_generated_code_is_the_pinned_plugins_and_their_runtimes() -> None:
    """An unpinned remote plugin takes Buf's latest release, so CI would
    regenerate something else one morning with nothing changed here. The
    runtime's floor is the plugin's version, because code from a newer plugin
    may call what an older runtime does not have. And every module's header
    must name the pinned version, so a regeneration with another one is caught
    before CI's."""
    config = (REPOSITORY / "buf.gen.yaml").read_text(encoding="utf-8")
    pins = dict(re.findall(r"^\s*- remote: (\S+):v(\S+)\s*$", config, re.MULTILINE))
    assert len(re.findall(r"^\s*- remote: ", config, re.MULTILINE)) == len(pins), config
    assert set(pins) == set(_PLUGIN_HEADERS)

    lines = (REPOSITORY / "requirements.in").read_text(encoding="utf-8").splitlines()
    for remote, version in pins.items():
        assert f"{_PLUGIN_RUNTIMES[remote]}>={version}" in lines, remote

    headers = {header: pins[remote] for remote, header in _PLUGIN_HEADERS.items()}
    seen, stale = 0, []
    for path in sorted(CONTRACT.rglob("*.py")):
        text = path.read_text(encoding="utf-8")
        found = re.search(r"^# Generated by (\S+) v(\S+) ", text, re.MULTILINE)
        if found is None:
            continue
        seen += 1
        if headers.get(found.group(1)) != found.group(2):
            where = path.relative_to(SERVER).as_posix()
            stale.append(f"{where}: {found.group(1)} v{found.group(2)}")
    assert seen, "no generated module carries a «# Generated by» header"
    assert stale == []


def test_the_gate_lets_a_reserved_removal_through_and_nothing_else() -> None:
    """«Evolving the contract» removes a field with its number and name
    reserved, and a bare FILE refuses that, because its FIELD_NO_DELETE does
    not ask whether they were (#298). The gate keeps FILE and swaps that one
    rule for the two that do ask. Buf is not run here: this holds the
    configuration that was checked against twenty-five mutations on 5 October
    2026, so that tidying it back to a bare FILE fails here, not on the first
    pull request that removes a field."""
    config = (REPOSITORY / "buf.yaml").read_text(encoding="utf-8")
    section = config.split("\nbreaking:\n", 1)[1]
    lists: dict[str, list[str]] = {}
    key = None
    for line in section.splitlines():
        heading = re.fullmatch(r"  (\w+):\s*", line)
        item = re.fullmatch(r"    - ([A-Z_]+)\s*", line)
        if heading:
            key = heading.group(1)
            lists[key] = []
        elif item and key:
            lists[key].append(item.group(1))
        elif line and not line.startswith(" "):
            break
    assert lists == {
        "use": [
            "FILE",
            "FIELD_NO_DELETE_UNLESS_NUMBER_RESERVED",
            "FIELD_NO_DELETE_UNLESS_NAME_RESERVED",
        ],
        "except": ["FIELD_NO_DELETE"],
    }


# ---- the files --------------------------------------------------------------


def test_every_file_carries_the_java_options() -> None:
    """Kotlin waits for sub-project 5 (the design's decision 3); the options are
    in every file now so that adding the Kotlin and Java lite targets then
    changes no proto."""
    wrong = {}
    for stem, module in _generated("_pb").items():
        options = module.desc().proto.options
        found = (
            None
            if options is None
            else (options.java_package, bool(options.java_multiple_files))
        )
        if found != (JAVA_PACKAGE, True):
            wrong[stem] = found
    assert wrong == {}


def test_every_enum_starts_at_unspecified() -> None:
    """Buf's lint says the same in CI; this says it on every local run, because
    the rule is what makes a new value safe for a client that has never heard
    of it."""
    wrong = []
    for file in _files():
        for enum in _enum_protos(file.proto):
            zero = next((value for value in enum.value if value.number == 0), None)
            if zero is None or zero.name != f"{_upper_snake(enum.name)}_UNSPECIFIED":
                wrong.append(f"{file.name}: {enum.name}")
    assert wrong == []


def test_the_two_method_options_are_declared() -> None:
    declared = {
        extension.name: (extension.number, extension.extendee)
        for extension in options_pb.desc().proto.extension
    }
    assert declared == {
        "auth": (50001, ".google.protobuf.MethodOptions"),
        "min_role": (50002, ".google.protobuf.MethodOptions"),
    }


def test_the_option_enums_are_numbered_as_this_file_reads_them() -> None:
    enums = {
        enum.name: {value.name: value.number for value in enum.value}
        for enum in options_pb.desc().proto.enum_type
    }
    assert enums == {
        "AuthKind": {
            "AUTH_KIND_UNSPECIFIED": 0,
            "AUTH_KIND_NONE": NONE,
            "AUTH_KIND_DEVICE": DEVICE,
            "AUTH_KIND_DEVICE_LINKED": DEVICE_LINKED,
            "AUTH_KIND_DIARY": DIARY,
        },
        "Role": {
            "ROLE_UNSPECIFIED": 0,
            "ROLE_VIEWER": VIEWER,
            "ROLE_EDITOR": EDITOR,
            "ROLE_ADMIN": ADMIN,
            "ROLE_OWNER": OWNER,
        },
    }


def test_the_error_reasons_are_the_designs() -> None:
    (reasons,) = [
        enum for enum in errors_pb.desc().proto.enum_type if enum.name == "ErrorReason"
    ]
    assert {value.name: value.number for value in reasons.value} == REASONS


# ---- the methods ------------------------------------------------------------


def test_the_methods_are_the_resource_map() -> None:
    found = {(service.name, method.name) for service, method in _methods()}
    assert sorted(found - METHODS.keys()) == [], "methods METHODS does not name"
    assert sorted(METHODS.keys() - found) == [], "rows of METHODS the proto does not define"


def test_every_method_binds_and_authorises_as_the_map_says() -> None:
    differ = {}
    for service, method in _methods():
        verb, path, body = _binding(method)
        found = Row(
            verb,
            path,
            body,
            _enum_option(method, options_pb.ext_auth) or 0,
            _enum_option(method, options_pb.ext_min_role),
        )
        expected = METHODS.get((service.name, method.name))
        if expected is not None and found != expected:
            differ[f"{service.name}.{method.name}"] = (found, expected)
    assert differ == {}


def test_every_method_says_which_credential_it_takes() -> None:
    silent = [
        f"{service.name}.{method.name}"
        for service, method in _methods()
        if not _enum_option(method, options_pb.ext_auth)
    ]
    assert silent == []


def test_a_role_is_asked_only_of_a_device_token() -> None:
    """``min_role`` is read only for a device credential: with a diary token, or
    with none, there is no class to hold a role in."""
    misplaced = [
        f"{service.name}.{method.name}"
        for service, method in _methods()
        if _enum_option(method, options_pb.ext_min_role) is not None
        and _enum_option(method, options_pb.ext_auth) not in (DEVICE, DEVICE_LINKED)
    ]
    assert misplaced == []


def test_every_method_on_the_class_names_its_least_role() -> None:
    """Reads included, so that «any viewer» is a decision somebody wrote down
    rather than what a forgotten option happens to mean; and a write needs an
    editor at least, because a viewer's token may be the class code's
    anonymous one."""
    unnamed, too_weak = [], []
    for service, method in _methods():
        if not _on_the_class(service, method):
            continue
        role = _enum_option(method, options_pb.ext_min_role)
        label = f"{service.name}.{method.name}"
        if role is None:
            unnamed.append(label)
        elif not _streams(method) and not _side_effect_free(method) and role < EDITOR:
            too_weak.append(label)
    assert unnamed == []
    assert too_weak == []


def test_every_unary_method_has_exactly_one_binding_under_v2() -> None:
    wrong = []
    for service, method in _methods():
        if _streams(method):
            continue
        rule = _http_rule(method)
        label = f"{service.name}.{method.name}"
        if rule is None:
            wrong.append(f"{label}: no google.api.http")
        elif list(rule.additional_bindings):
            wrong.append(f"{label}: more than one binding")
        elif not (_binding(method)[1] or "").startswith("/v2/"):
            wrong.append(f"{label}: {_binding(method)[1]}")
    assert wrong == []


def test_standard_methods_use_their_verbs() -> None:
    """AIP-131 to 135: a Get or a List is a GET, a Create a POST, an Update a
    PATCH and a Delete a DELETE; reads and deletes carry no body, and the
    other two always do."""
    wrong = []
    for service, method in _methods():
        standard = _standard(method.name)
        if standard is None or _streams(method):
            continue
        verb, path, body = _binding(method)
        expected = _STANDARD[standard]
        carries_body = expected in ("post", "patch")
        if (
            verb != expected
            or _CUSTOM_VERB.search(path or "") is not None
            or bool(body) != carries_body
        ):
            wrong.append(f"{service.name}.{method.name}: {verb} {path} body={body!r}")
    assert wrong == []


def test_custom_methods_are_posts_named_for_their_verb() -> None:
    """AIP-136: anything that is not one of the five is ``POST …:verb`` with the
    whole request as its body, and the verb is the method's own -- an
    ``ApproveRequest`` method would be ``:approve``."""
    wrong = []
    for service, method in _methods():
        if _standard(method.name) is not None or _streams(method):
            continue
        verb, path, body = _binding(method)
        custom = _CUSTOM_VERB.search(path or "")
        own = method.name[0].lower() + method.name[1:]
        if (
            verb != "post"
            or body != "*"
            or custom is None
            or not own.startswith(custom.group(1))
        ):
            wrong.append(f"{service.name}.{method.name}: {verb} {path} body={body!r}")
    assert wrong == []


def test_every_path_variable_is_a_request_field() -> None:
    """And so is a named body: a binding that names what the request lacks is a
    route the transcoder cannot fill."""
    messages = _messages()
    wrong = []
    for service, method in _methods():
        _verb, path, body = _binding(method)
        request = messages[method.proto.input_type.lstrip(".")]
        named = _VARIABLE.findall(path or "") + ([body] if body not in ("", "*") else [])
        wrong += [
            f"{service.name}.{method.name}: {name}"
            for name in named
            if not _resolves(request, name, messages)
        ]
    assert wrong == []


def test_a_stream_has_no_rest_binding_and_nothing_streams_in() -> None:
    """A server stream is the host target's beta and REST cannot carry it; no
    method takes a client stream, which neither Vercel nor the phone needs."""
    wrong = []
    for service, method in _methods():
        label = f"{service.name}.{method.name}"
        if method.proto.client_streaming:
            wrong.append(f"{label}: a client stream")
        if method.proto.server_streaming and _http_rule(method) is not None:
            wrong.append(f"{label}: a stream with a REST binding")
    assert wrong == []


def test_reads_are_side_effect_free_and_nothing_else_is() -> None:
    """``NO_SIDE_EFFECTS`` is what lets Connect send a call as a GET, which a
    cache may keep and a link preview may fire. Every Get and List carries it,
    so v1's reads that wrote -- ``/me`` minting a code, ``/calendar`` minting a
    secret, ``/manage/terms`` seeding -- cannot come back under a read's name,
    and nothing else may carry it."""
    wrong = [
        f"{service.name}.{method.name}"
        for service, method in _methods()
        if not _streams(method)
        and _side_effect_free(method) != (_standard(method.name) in ("Get", "List"))
    ]
    assert wrong == []


def test_messages_are_named_for_their_method() -> None:
    wrong = [
        f"{service.name}.{method.name}"
        for service, method in _methods()
        if method.proto.input_type != f".lessons.v2.{method.name}Request"
        or method.proto.output_type != f".lessons.v2.{method.name}Response"
    ]
    assert wrong == []


# ---- the cold path ----------------------------------------------------------

#: Imports MODULE in a fresh interpreter and prints, as JSON, every module of
#: the contract and of its runtime that is then loaded.
_PROBE = """
import json, sys
import MODULE
print(json.dumps(sorted(
    name for name in sys.modules
    if name.partition(".")[0] in ("connectrpc", "pyqwest", "protobuf")
    or name == "app.contract" or name.startswith("app.contract.")
)))
"""

#: The suite's own settings, and a Vercel deployment's -- the two
#: configurations tests/test_cold_start.py asks about, written out again
#: because a test module may not import another (test_test_imports.py).
_LOCAL = {"BOT_TOKEN": "", "WEBHOOK_SECRET": "", "RUN_BOT": "false"}
_VERCEL = {
    "VERCEL": "1",
    "DATABASE_URL": "postgresql+asyncpg://user:secret@db.invalid:5432/lessons",
    "BOT_TOKEN": "123456:not-a-real-token",
    "WEBHOOK_SECRET": "not-a-real-secret",
    "RUN_BOT": "false",
    "OWNER_IDS": "1000",
    "TIMEZONE": "Europe/Moscow",
}


def _loaded_after_importing(module: str, settings: dict[str, str]) -> list[str]:
    env = {name: value for name, value in os.environ.items() if name != "VERCEL"}
    result = subprocess.run(
        [sys.executable, "-c", _PROBE.replace("MODULE", module)],
        cwd=str(SERVER),
        env={**env, **settings},
        capture_output=True,
        text=True,
        timeout=120,
    )
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout.strip().splitlines()[-1])


@pytest.mark.parametrize("settings", [_LOCAL, _VERCEL], ids=["webhook-unmounted", "vercel"])
def test_the_api_cold_start_imports_no_generated_code(settings: dict[str, str]) -> None:
    """Nothing serves v2 yet, so nothing in app.main may import it: the cold
    start stays what tests/test_cold_start.py measures, and sub-project 3 is the
    change that pays the spike's 61 ms, knowingly."""
    assert _loaded_after_importing("app.main", settings) == []


def test_the_cold_start_probe_sees_generated_code() -> None:
    """Held here rather than trusted: a probe blind to the contract would pass
    the test above for ever."""
    loaded = _loaded_after_importing("app.contract.lessons.v2.options_pb", _LOCAL)
    assert "app.contract.lessons.v2.options_pb" in loaded
    assert any(name.partition(".")[0] == "protobuf" for name in loaded)


def test_google_rpc_is_generated_from_the_commit_buf_lock_pins() -> None:
    """The error model's details are googleapis' ``google/rpc``, generated as a
    second input of buf.gen.yaml. A module under ``inputs:`` is not resolved
    through buf.lock, so the commit is written twice; held level here, so that
    ``google/api`` and ``google/rpc`` never come from two different googleapis."""
    lock = (REPOSITORY / "buf.lock").read_text(encoding="utf-8")
    locked = re.search(r"name: buf\.build/googleapis/googleapis\s+commit: (\w+)", lock)
    config = (REPOSITORY / "buf.gen.yaml").read_text(encoding="utf-8")
    pinned = re.search(
        r"^\s*- module: buf\.build/googleapis/googleapis:(\w+)\s*$", config, re.MULTILINE
    )
    assert locked is not None and pinned is not None
    assert pinned.group(1) == locked.group(1)

    from app.contract.google.rpc import code_pb, error_details_pb, status_pb

    assert {"ErrorInfo", "BadRequest", "RetryInfo"} <= set(vars(error_details_pb))
    assert status_pb.Status.desc().type_name == "google.rpc.Status"
    assert code_pb.Code.desc().name == "Code"


def test_the_error_details_render_as_json_through_the_type_registry() -> None:
    """REST writes a refusal's details as JSON, not as Connect's base64: an
    ``Any`` rendered through protobuf-py's own type registry, ``@type`` first.
    The 5 October spike saw only Connect's form, so this is the first time the
    REST form is asked of the runtime that will write it."""
    from protobuf import Registry, message_to_json_value
    from protobuf.wkt import Any as AnyMessage
    from protobuf.wkt import Duration

    from app.contract.google.rpc import error_details_pb

    registry = Registry(error_details_pb.desc())
    info = error_details_pb.ErrorInfo(
        reason="ROLE_REQUIRED", domain="lessons.app", metadata={"role": "ROLE_ADMIN"}
    )
    retry = error_details_pb.RetryInfo(retry_delay=Duration(seconds=7))
    assert message_to_json_value(AnyMessage.pack(info), registry=registry) == {
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        "reason": "ROLE_REQUIRED",
        "domain": "lessons.app",
        "metadata": {"role": "ROLE_ADMIN"},
    }
    assert message_to_json_value(AnyMessage.pack(retry), registry=registry) == {
        "@type": "type.googleapis.com/google.rpc.RetryInfo",
        "retryDelay": "7s",
    }


def test_the_readers_see_what_they_are_written_for() -> None:
    """Held here rather than trusted: a reader that always answered None would
    pass every check above that asks whether something is absent."""
    found = {(service.name, method.name): (service, method) for service, method in _methods()}
    _, get_me = found[("MeService", "GetMe")]
    _, unlink_me = found[("MeService", "UnlinkMe")]
    window_service, window = found[("ScheduleService", "GetScheduleWindow")]

    assert _binding(get_me) == ("get", "/v2/me", "")
    assert _enum_option(get_me, options_pb.ext_auth) == DEVICE
    assert _enum_option(get_me, options_pb.ext_min_role) is None
    assert _enum_option(window, options_pb.ext_min_role) == VIEWER
    assert _on_the_class(window_service, window)
    assert _side_effect_free(get_me)
    assert not _side_effect_free(unlink_me)
    assert not _streams(get_me)

    assert _VARIABLE.findall("/v2/class/subjects/{subject.id}") == ["subject.id"]
    assert _CUSTOM_VERB.search("/v2/class/devices/{device_id}:revoke").group(1) == "revoke"
    assert _CUSTOM_VERB.search("/v2/class/subjects/{subject.id}") is None
    assert _standard("GetMe") == "Get"
    assert _standard("Getaway") is None
    assert _upper_snake("SignInMethod") == "SIGN_IN_METHOD"

    messages = _messages()
    update_task = messages["lessons.v2.UpdateTaskRequest"]
    assert _resolves(update_task, "task.id", messages)
    assert not _resolves(update_task, "task.nothing", messages)

    watch_service, watch = found[("WatchService", "WatchClass")]
    assert _binding(watch) == (None, None, "")
    assert _streams(watch)
    assert _on_the_class(watch_service, watch)
    assert _enum_option(watch, options_pb.ext_min_role) == VIEWER
