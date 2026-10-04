# The v2 contract (sub-project 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Write the v2 contract down without serving it. The contract is `proto/lessons/v2/`: 20 files, 17 services, 76 methods and 33 error reasons. Its Python is generated into `server/app/contract/` and committed. The runtime packages that Python imports are added. The checks that keep both honest come with it: Buf in CI for lint, breaking changes and freshness, and `server/tests/test_contract.py` for what Buf cannot see. The documents that describe it are updated. Nothing in `app.main` imports any of it, so v1 and the cold start are unchanged.

**Architecture:**
- Proto is the source, at the repository root.
- `buf.yaml` lints it with Buf's STANDARD rules. It lets exactly one rule off for exactly one file (`errors.proto`), and says why.
- `buf.gen.yaml` pins two remote plugins, `buf.build/bufbuild/py:v0.6.0` and `buf.build/connectrpc/py:v0.12.1`, and writes Python only into `server/app/contract/`.
- Each method carries its own facts:
  - its REST route (`google.api.http`, `/v2/…`);
  - who may call it (`(lessons.v2.auth)`, `(lessons.v2.min_role)`);
  - whether it is side-effect free (`idempotency_level`).
- `test_contract.py` reads those facts back from the generated descriptors and compares them with the design's resource map, pinned row for row.
- A path-filtered «Contract» CI job lints, checks breaking changes against the base, and fails when the committed generated code differs from what `buf generate` writes.

**Tech Stack:** Buf v1.73.0, protobuf-py, connectrpc, Python 3.12, pytest, GitHub Actions
**Spec:** docs/specs/2026-10-04-contract-v2-design.md (programme: docs/specs/2026-10-03-one-contract-design.md)

## Rulings made before execution (they override the text below)

Made by the controller on 4 October 2026, after the owner approved the design and asked for this
plan to be executed with subagents; the design (`2026-10-04-contract-v2-design.md`) was amended
to match the same day.

- **The worktree.** Every `WT` below means `/c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract`,
  branch `contract/spec` (it already carries the design). No new worktree is created: Task 1's
  `git worktree add` step is skipped. Commands that `cd` into `contract-v2` use this path instead.
- **`RotateCalendarFeed` is `CreateCalendarFeed`**, `POST /v2/me/calendarFeed`, a standard Create
  on the singleton: it mints the feed when the class has none and answers the existing one when
  it has. There is no rotation in v2 (v1 has none; rotating the class's feed would cut every
  subscriber). Its request and response are `CreateCalendarFeedRequest`/`CreateCalendarFeedResponse`.
  This settles the plan's Self-review point 8 — no owner decision is pending on it.
- **The diary's attendance method is `ListTurnstileEvents`**, `GET /v2/diary/students/{student_id}/turnstileEvents`,
  with `ListTurnstileEventsRequest`/`ListTurnstileEventsResponse` and the feature `DIARY_FEATURE_TURNSTILE`;
  its record message keeps the plan's name. `DIARY_FEATURE_ATTENDANCE` stays in the enum, with no
  method yet (absences and lateness, when one reads them).
- **Accepted as the plan proposes** (Self-review «contradicts the design»): times `"HH:MM"`; wrapped
  `<Method>Response`s; the one `ignore_only` for `ENUM_VALUE_PREFIX` in `errors.proto`; `ListSchools`;
  `ListDiaryHomework` and `ListDiarySubjects`; skipping `buf breaking` with a notice when the base has
  no `buf.yaml`; both calendar-feed methods `DEVICE_LINKED`; every Get and List `NO_SIDE_EFFECTS`
  (sub-project 3 moves v1's seeding and adoption off the reads); `opentelemetry-api` as a transitive
  dependency; canonical proto3 JSON (lowerCamelCase) on both transports (design decision 10).
- **Approval.** The design is approved (owner, 4 October 2026); the Self-review's point 12 no longer
  gates the merge. The merge follows the owner's standing instruction and the five checks.

## Changed by the final review

- **`ScheduleWindow.next_school_day` is dropped and field 4 reserved.** v1 looked up to 21 days past
  a window of a few days; a v2 window is a whole school year, 21 days past its last lesson is June,
  and the field could never be filled, so the phone answers «what is next» from the year it holds.

## Global Constraints

- One package, `lessons.v2`, in `proto/lessons/v2/`. One file per service, plus `options.proto`, `errors.proto` and `common.proto`.
- REST annotations are written `/v2/…`, and REST is served under `/api` (`/api/v2/…`). Connect, gRPC-Web and native gRPC go under `/api/rpc/lessons.v2.<Service>/<Method>`.
- Python only. Kotlin and Java lite wait for sub-project 5, but every file already carries `java_package = "com.lumenpearson.lessons.contract.v2"` and `java_multiple_files = true`.
- Dates are `"YYYY-MM-DD"` strings and times `"HH:MM"` strings, both naive wall time in the class's zone. A wall-clock moment is `"YYYY-MM-DDTHH:MM"`. Instants are `google.protobuf.Timestamp`.
- Every method sets `(lessons.v2.auth)`. Every method that acts on the class sets `(lessons.v2.min_role)`, reads included. Every class-writing method's `min_role` is `ROLE_EDITOR` or above.
- Every side-effect-free Get and List is `idempotency_level = NO_SIDE_EFFECTS`, and no other method is.
- Every enum's zero value is `<NAME>_UNSPECIFIED`.
- Every method takes `<Method>Request` and answers `<Method>Response`.
- Nothing in `app.main` imports `app.contract`, `connectrpc`, `pyqwest` or `protobuf`.
- Nothing hand-written lives under `server/app/contract/`. `buf.gen.yaml` says `clean: true`, so every regeneration deletes the directory first.
- Language: English in code, comments and commits. Russian only in user-facing strings, and in guillemets anywhere else.
- Commit messages:
  - English sentences that say what the change makes the project do; no Conventional Commits prefix.
  - The body explains the reasoning and names what is left uncovered.
  - Trailers, in this order: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`, then `Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV`.
- Gates before every commit (the `gates` skill), run from the worktree's `server/`:
  - `python -m ruff check app tests scripts migrations` → `All checks passed!`
  - `python -m mypy` → `Success: no issues found in 197 source files`
  - the touched test files, each with `-p no:xdist`.
- The full suite, `python -m pytest -q -n auto`, runs at the end of each task. It takes about 10 minutes on this machine. Never run it while a Gradle build runs (faulty RAM): check `tasklist | grep -i java` first.
- `python` in every command means `/c/Users/lumen/StudioProjects/lessons/server/.venv/Scripts/python.exe`, the main checkout's venv (Python 3.13.15). Always use `python -m pytest`, never bare `pytest`. The venv's editable install points `app` at the main checkout, and only the `-m` form puts the worktree's `server/` first.
- Never `pip install -e .` from the worktree: it would re-point the shared venv's `app` at this worktree for every other session.
- `buf` in every command means `/c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe`. Task 0 fetches it; it is never committed.
- `uv` is `/c/Users/lumen/.local/bin/uv` (0.12.3, on `PATH`).
- `WT` means `/c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2`. Shell state does not persist between commands, so every command spells it out.
- Another agent may be working in the main tree. Run `git status` before touching a file you did not open.
- Milestone 11 (`v0.10.0 — One contract: REST v2, Connect and native gRPC, build console`), under epic #273. This work closes nothing. #268, #269 and #270 stay open.

## Review Focus

These are five failure modes no ordinary test exercises, each with what pins it and where.

1. **A proto change merged without regenerating.**
   - The «Contract» CI job (Task 8) runs `buf generate --output "$RUNNER_TEMP/generated"` and `diff -r` against `server/app/contract`. A difference fails the job and prints the command to run.
   - Locally, `test_every_proto_file_was_generated` (Task 1) fails when a proto file has no `_pb` module, or a module outlives its file.
   - Task 9 Step 1 runs the same regenerate-and-diff on the final tree.
2. **A method without a credential, or a class method without a least role.**
   - `test_every_method_says_which_credential_it_takes`, `test_every_method_on_the_class_names_its_least_role` (a write needs `ROLE_EDITOR` at least) and `test_a_role_is_asked_only_of_a_device_token` (all Task 1).
   - `METHODS`: every row pins auth and role, and `test_the_methods_are_the_resource_map` fails on a method that has no row.
3. **A REST binding outside `/v2`, on the wrong verb, or with a path field the request lacks.**
   - `test_every_unary_method_has_exactly_one_binding_under_v2`, `test_standard_methods_use_their_verbs`, `test_custom_methods_are_posts_named_for_their_verb` and `test_every_path_variable_is_a_request_field` (Task 1).
   - The exact verb, path and body per row in `METHODS` (Tasks 2–7).
4. **Generated code imported on the cold path.**
   - `test_the_api_cold_start_imports_no_generated_code[webhook-unmounted|vercel]` imports `app.main` in a fresh interpreter in both deployment configurations (Task 1).
   - `test_the_cold_start_probe_sees_generated_code` proves the probe is not blind.
5. **A remote plugin version drifting.**
   - `test_the_generated_code_is_the_pinned_plugins_and_their_runtimes` (Task 1) checks three things:
     - every `remote:` line in `buf.gen.yaml` carries `:vX.Y.Z`;
     - each pin equals its runtime's floor in `requirements.in`;
     - every generated module's `# Generated by <plugin> v<version>` header names the pinned version.
   - CI's regeneration catches the rest.

## What was verified while writing this plan, and what was not

**Not run, at all.** This plan was written by a planning agent that may not create files anywhere, download, or install. So nothing here was executed: no Buf, no `buf lint`, no `buf generate`, no import of generated code. **Task 0 is that run**, with every expectation written down and a stop rule.

**Read from the repository** (worktree `spec-one-contract`, branch `contract/spec` at `03d3fac`):
- both specs and `CLAUDE.md`;
- every module of `server/app/schemas/`, every router under `server/app/api/` (`public.py`, `edit.py`, `diary.py`, `directory.py`, `deps.py`, `manage/*`);
- every exception class in `server/app/services/**`, `providers/diary/errors.py` and `providers/dadata/exceptions.py`;
- the enums in `models.py`, `schedule.py` and `providers/diary/models.py`;
- `pyproject.toml`, `requirements.in`, `requirements.txt` (34 pins), `test_requirements_mirror.py`;
- every test that walks `app/` (listed in Task 1), `test_cold_start.py`, `test_env_example.py`, `test_schema_version.py`;
- `ci.yml`, `docs/build.md`, `.github/PULL_REQUEST_TEMPLATE.md`, `Dockerfile`, `vercel.json`, `.claude/settings.json`, and the `handover`, `gates`, `github-pr` and `steward` skills.

**Read from the web, read-only:**
- **Buf v1.73.0**: published 2026-09-11 (GitHub API).
  - Assets `buf-Windows-x86_64.exe` and `buf-Linux-x86_64`.
  - `sha256.txt`, read verbatim: Windows `13542f2892c4f774150ddb525266d6421d457b3e741297056b64427853526e36`, Linux `8f2986298ad08f0cc1bf999b9797b7c383adf32d7edf0f73d6f1e1a701baeac1`.
- **Remote plugin versions**, from `github.com/bufbuild/plugins` (the BSR pages render client-side and returned nothing):
  - `plugins/bufbuild/py`: v0.1.1, v0.2.0, v0.3.0, v0.4.0, v0.5.0, **v0.6.0**. Its `buf.plugin.yaml` requires `protobuf-py>=0.6.0`, and its registry SDK passes `init_files=false`, so the default is `true`.
  - `plugins/connectrpc/py`: v0.11.0, v0.11.1, **v0.12.1**. It requires `connectrpc >= 0.12.1`.
- **PyPI JSON:**
  - `connectrpc` 0.12.1 (2026-09-04): requires `protobuf-py>=0.3.0`, `pyqwest>=0.5.1`.
  - `protobuf-py` 0.6.0 (2026-09-30): requires `protobuf-py-ext==0.6.0` (on CPython x86-64 Linux/Windows and arm64) and `typing-extensions>=4.13.0`.
  - `pyqwest` 0.11.0: has cp312 manylinux x86-64 and cp313 win_amd64 wheels, and requires `opentelemetry-api>=1.39.1`.
  - `opentelemetry-api` 1.45.0: requires `typing-extensions>=4.5.0`.
  - `protobuf-py-ext` 0.6.0: abi3 and cp312/313 wheels.
- **`bufbuild/buf-action` v1.6.0** (released 2026-09-22):
  - `action.yml` inputs: `version`, `checksum`, `token`, `lint`, `format`, `breaking`, `breaking_against`, `push`, `archive`, `pr_comment`, `setup_only`; outputs `buf_version` and `buf_path`.
  - `src/inputs.ts`: on a pull request, `breaking_against` defaults to `<clone_url>#format=git,commit=<base sha>`.
  - `src/main.ts`: build first, then lint, format and breaking.
  - `src/installer.ts`: it downloads the raw `buf-Linux-x86_64` and checks `checksum` against that binary.
  - Defaults: `pr_comment` is on for same-repository pull requests; `format` is on for pull requests; `push` is on for pushes.
- **protobuf-py documentation and API reference:**
  - generated modules are `<file>_pb.py` with a module-level `desc()` returning a `DescFile`;
  - `DescFile` has `proto`, `services`, `messages`, `enums`, `extensions`; `DescService` has `name`, `type_name`, `methods`, `proto`; `DescMethod` has `name`, `input`, `output`, `idempotency`, `proto`;
  - `Enum` subclasses `IntEnum`; a oneof holds a `Oneof` with `field` and `value`;
  - `msg[extension]` and `extension in msg`; extensions are module-level `ext_<name>`;
  - generated headers read `# Generated by protoc-gen-py v0.6.0 with parameter "".` then `# ruff: noqa`.
  - The `protoc-gen-py` v0.6.0 source summary says `init_files` defaults to true, and the output root always gets an `__init__.py`.
- **connect-py v0.12.1 conformance output:**
  - `_connect.py` exists only beside files that declare a service;
  - it imports `from . import service_pb`;
  - `_pb.py` imports well-known types as `from protobuf.wkt import any_pb` and siblings as `from . import config_pb`;
  - the service Protocol's `desc()` is `next(s for s in service_pb.desc().services if s.type_name == '…')`.
- **Buf lint rules page:** STANDARD = MINIMAL + BASIC + `ENUM_VALUE_PREFIX`, `ENUM_ZERO_VALUE_SUFFIX`, `FILE_LOWER_SNAKE_CASE`, `PACKAGE_VERSION_SUFFIX`, `PROTOVALIDATE`, `RPC_REQUEST_RESPONSE_UNIQUE`, `RPC_REQUEST_STANDARD_NAME`, `RPC_RESPONSE_STANDARD_NAME`, `SERVICE_SUFFIX`.

**Read locally, read-only:**
- Buf's cache `%LOCALAPPDATA%\buf\v3` holds `buf.build/googleapis/googleapis` commit `c17df5b2beca46928cc87d5656bd5343`, which the spike resolved on 3 October.
- `%TEMP%` holds the spike's PyPI JSON, which agrees with the versions above.
- No `buf` is on `PATH`. `uv` 0.12.3 is.
- `core.autocrlf=true`, and there is no `.gitattributes`.

## From v1 to v2: every rename and reshape, with its reason

**Applies everywhere:**
- Every response is a `<Method>Response`, where v1 answered a bare object or a bare list. Buf's `RPC_RESPONSE_STANDARD_NAME` and `RPC_REQUEST_RESPONSE_UNIQUE` require it. It is also where per-write facts live: `moved` on a subject rename, `silenced_lessons` on a bell write.
- v1's `DeletedOut {id, deleted}` becomes an empty `Delete…Response` (AIP-135).
- Enum strings get prefixed names: `"holiday"` becomes `DAY_KIND_HOLIDAY` (`ENUM_VALUE_PREFIX`).
- v1's `null` for a role, a term kind or an off-reason becomes `…_UNSPECIFIED`.
- Times are `"HH:MM"`, while v1 sent `"HH:MM:SS"` (see Self-review).
- Instants become `Timestamp` (decision 4): a device's `created_at`, `last_seen_at` and `linked_at`, an access request's `created_at`, an audit entry's `at`, a task's `done_at`, `created_at` and `updated_at`, a correction's `updated_at`, and the window's `generated_at`. v1 sent several of these as class wall time without a zone.
- Ids a diary assigns are `int64`, which proto3 JSON writes as strings.
- Query fields `from`/`to` become `start_date`/`end_date`, because `from` is a Python keyword the generated attribute could not carry.
- Every `Update…` uses AIP-134: the resource as the body, `{resource.id}` in the path, and an `update_mask` replacing v1's present-or-absent PATCH semantics. «null clears» becomes «path in the mask, field absent».

**Per area:**

| v1 | v2 | Why |
| --- | --- | --- |
| `POST /join`, `JoinRequest`/`JoinResponse` | `DeviceService.CreateDevice`; `DiaryBinding.provider` is a string | decision 8: a provider is a key, not an enum |
| `MeOut.link_code`, `MeOut.bot_deep_link` | `CreateLinkCode` → `LinkCode` | a GET must not mint |
| `UnlinkOut{linked}` | `UnlinkMeResponse{me}` | a custom method answers the resource |
| `CalendarOut{url}` | `CalendarFeed{optional url}` | `GetCalendarFeed` never mints, so it may have none |
| `TaskIn`/`TaskPatch`/`TaskOut`, `POST /tasks/{id}/done` | `Task` + `update_mask`; `done` set by `UpdateTask`; `priority` optional (absent = 1); `remind_at` a wall-time string | one resource; proto3 cannot tell an absent 1 from 0 otherwise |
| `DoneIn`/`DoneOut` on `/homework/{id}/done` | `CreateHomeworkTick` / `DeleteHomeworkTick` | a tick is a resource |
| `BundleOut` (`api_version`, `start`, `days`, `If-None-Match`) | `ScheduleWindow` per `year`; `if_none_match`, `not_modified`, `etag` | the package is the version; the window is a school year |
| `DayOut`, `EventOut`, `HomeworkOut`, `LessonOut`, `DeviceOut`, `ClassOut` | `ScheduleDay`, `ScheduleEvent`, `ScheduleHomework`, `Lesson`, `DeviceAccess`, `ClassSummary` | `Day`, `Event` and `Homework` are other services' resources in the same package |
| `ManagedClassOut` + `ClassPatch` | `SchoolClass` (adds `grade` and `letter`); `join_mode` becomes `JoinMode` | `class` is a keyword in Python, Kotlin and Java |
| `ClassDeleteIn.confirm_name` (DELETE with a body) | `DeleteClassRequest.confirmation`, a query field | design: no body on a DELETE |
| `StatsOut.members_by_role` (a dict) | `repeated RoleCount` | a map key cannot be an enum |
| `StatsOut.overrides_upcoming` | `substitutions_upcoming` | «overrides» meant corrections on the diary side |
| `TermsOut`, `TermSchemeIn`, `TermBoundsIn` | `TermScheme`, `ListTerms`, `UpdateTermScheme`, `UpdateTerm{term}` | `Get` never seeds |
| `SubjectOut` (no id) and `ManagedSubjectOut` | one `Subject` with an id; `SubjectSavedOut` becomes `Create/UpdateSubjectResponse` | one resource, readable by a viewer |
| `BellScheduleOut.silenced_lessons`, `PUT …/periods` | `UpdateBellScheduleResponse.silenced_lessons`; `periods` set through the mask | a fact of the write; «periods are a field» |
| `TimetableExportOut`, `TimetableImportIn`, `ImportConflictOut` | `Timetable`, `ImportTimetableRequest{validate_only}`, `ImportConflict` | AIP-163 preview |
| `HomeworkItemOut`; `PUT /homework` upsert | `Homework`; `CreateHomework` refuses a second for the same subject and day, `UpdateHomework` changes it | standard methods |
| `OverrideIn`/`Out`, action `clear` | `Substitution` with an id; `DeleteSubstitution` | «overrides» renamed; clear is a delete |
| `EventIn` + `EventCreatedOut{id}`, `PUT /events` | `Event`, `CreateEvent` (POST) | #268 |
| `DayIn`/`DayOverrideOut` | `Day`; upsert through `allow_missing` | AIP-134 |
| `ManagedDeviceOut` | `ClassDevice` | |
| `AccessRequestOut`; `RequestDecisionOut{id,status,role,who}` | `AccessRequest`; `{request_id, role, who}`, status implied by the method | |
| `AuditPageOut{entries,limit,offset,has_more}` | `{audit_entries, next_page_token}` | AIP-158, as the design asks |
| `SchoolRegionsOut` (`q`) | `ListSchoolRegionsResponse` (`query`) | |
| `SchoolSearchOut{items,page,pages,total,truncated}`, design's `SearchSchools` | `ListSchools{schools,next_page_token,total_size,truncated}` | a Search on `GET /v2/schools` is neither a standard method nor `POST …:verb`; a List with a query is standard |
| `DiaryCapabilitiesOut{enabled,registration,providers{petersburg,netschool}}` | `DiaryCapabilities{enabled, providers[]}` | decision 8; every v2 server registers sessions |
| `DiarySessionBody` (discriminated by `provider`) | `CreateDiarySessionRequest` with `oneof credential` | |
| `NetSchoolCookiesIn.NSSESSIONID`/`ESRNSec` | `ns_session_id`/`esrn_sec` | `FIELD_LOWER_SNAKE_CASE` |
| `/schedule`, a list of lessons | `ListScheduleDays`, grouped into `DiaryScheduleDay{date, lessons}`; `DiaryLesson` loses `date` | the design's method name says the resource is a day |
| `/grades` | `ListMarks`; `kind` becomes `MarkKind` | |
| `/homework`, `/subjects` | `ListDiaryHomework`, `ListDiarySubjects` | a second `ListHomeworkRequest`/`ListSubjectsRequest` cannot share `lessons.v2` (`RPC_REQUEST_STANDARD_NAME`) |
| `/overrides` family | `ListCorrections`, `BatchUpdateCorrections` (v1 wrote one at a time), `ResetCorrections` (a body of keys), `ClearCorrections` (v1 `DELETE …/overrides/all`); `DiaryOverrideOut` becomes `DiaryCorrection` | «overrides» renamed |
| `DiaryAttendanceOut.direction` (string) | `AttendanceDirection` | |
| `/diary/logout` | `DeleteDiarySession` (`current`) | |

---

## Task 0: Prove the toolchain in a scratch directory

**Files:**
- Create, outside the repository only: `C:\Users\lumen\AppData\Local\Temp\contract-plan-scratch\` with `bin\buf.exe`, `buf.yaml`, `buf.gen.yaml`, `proto\lessons\v2\{options,errors,ping}.proto`, `.venv\` and `observations.md`.

**Interfaces:**
- Consumes: the network (GitHub releases, the BSR, PyPI).
- Produces: `buf` at the path the Global Constraints name, and `observations.md` holding every command's output.
- Confirms or refutes each inference that later tasks rely on.

**Stop rule:** if any observation differs from the expectation written here, stop. Copy the output into `observations.md` and report to the owner before Task 1. Do not adapt later tasks silently.

- [ ] **Step 1: Fetch Buf 1.73.0 and check it.**
```bash
mkdir -p /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin && curl -fsSL -o /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe https://github.com/bufbuild/buf/releases/download/v1.73.0/buf-Windows-x86_64.exe && sha256sum /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe --version
```
Expected: `13542f2892c4f774150ddb525266d6421d457b3e741297056b64427853526e36 *…/buf.exe`, then `1.73.0`.

- [ ] **Step 2: Write the scratch module.**
  - `buf.yaml`: exactly Task 2's final `buf.yaml`, with the googleapis dependency.
  - `buf.gen.yaml`: exactly Task 1's.
  - `proto/lessons/v2/options.proto`: exactly Task 1's.
  - `proto/lessons/v2/errors.proto`: Task 1's header, with only `ERROR_REASON_UNSPECIFIED = 0;` and `DEVICE_NOT_LINKED = 3;`.
  - `proto/lessons/v2/ping.proto`:
```proto
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "google/protobuf/timestamp.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service PingService {
  rpc GetPing(GetPingRequest) returns (GetPingResponse) {
    option (google.api.http) = {get: "/v2/pings/{name}"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  rpc WatchPings(WatchPingsRequest) returns (stream WatchPingsResponse) {
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
  }
}

message GetPingRequest {
  string name = 1;
}

message GetPingResponse {
  string name = 1;
  google.protobuf.Timestamp at = 2;
}

message WatchPingsRequest {}

message WatchPingsResponse {
  string revision = 1;
}
```

- [ ] **Step 3: Resolve, lint, generate, list.**
```bash
cd /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch && bin/buf.exe dep update && cat buf.lock && bin/buf.exe lint && echo LINT-CLEAN && bin/buf.exe generate && find server -type f | sort && head -3 server/app/contract/lessons/v2/ping_pb.py && grep -n "^from \|^import " server/app/contract/lessons/v2/ping_pb.py server/app/contract/lessons/v2/ping_connect.py
```
Expected:
- `buf.lock` is `version: v2` with one dep `buf.build/googleapis/googleapis` and a 32-hex `commit:` (`c17df5b2beca46928cc87d5656bd5343` or newer).
- `LINT-CLEAN`.
- Files (inference):
```
server/app/contract/__init__.py
server/app/contract/google/__init__.py
server/app/contract/google/api/__init__.py
server/app/contract/google/api/annotations_pb.py
server/app/contract/google/api/http_pb.py
server/app/contract/lessons/__init__.py
server/app/contract/lessons/v2/__init__.py
server/app/contract/lessons/v2/errors_pb.py
server/app/contract/lessons/v2/options_pb.py
server/app/contract/lessons/v2/ping_connect.py
server/app/contract/lessons/v2/ping_pb.py
```
- A header naming `protoc-gen-py v0.6.0`.
- Imports including `from protobuf import Message`, `from protobuf.wkt import timestamp_pb`, a relative `from ...google.api import annotations_pb` and `from . import options_pb` in `ping_pb.py`; and `from connectrpc.…` with `from . import ping_pb` in `ping_connect.py`.
- No `google/protobuf/*` file is generated.
- If `server/app/contract/__init__.py` is missing, stop: Task 1's packaging argument (the Dockerfile's `pip install .`) needs it.

- [ ] **Step 4: Learn which lint rules bite.** Write `proto/lessons/v2/lintcheck.proto`:
```proto
syntax = "proto3";

package lessons.v2;

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

enum Colour {
  RED = 0;
  GREEN = 1;
}

message Thing {
  string name = 1;
}

service ThingService {
  rpc GetThing(Thing) returns (Thing);
  rpc ListThings(Thing) returns (Thing);
}
```
```bash
cd /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch && bin/buf.exe lint; echo "exit $?"; rm proto/lessons/v2/lintcheck.proto && bin/buf.exe lint && echo LINT-CLEAN-AGAIN
```
Expected:
- Findings for `ENUM_VALUE_PREFIX` (RED, GREEN), `ENUM_ZERO_VALUE_SUFFIX` (RED), `RPC_REQUEST_STANDARD_NAME`, `RPC_RESPONSE_STANDARD_NAME` and `RPC_REQUEST_RESPONSE_UNIQUE`; nothing for `errors.proto`; `exit 100`; then `LINT-CLEAN-AGAIN`.
- If `errors.proto`'s `DEVICE_NOT_LINKED` is reported, the `ignore_only` path form is wrong. Change it to `lessons/v2/errors.proto` (module-relative), re-run, and use in Task 1 whichever form silences exactly that file.

- [ ] **Step 5: Prove the freshness check and the first-PR case of `breaking`.**
```bash
cd /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch && bin/buf.exe generate --output regen && diff -r --exclude=__pycache__ regen/server/app/contract server/app/contract && echo SAME; mkdir -p empty && bin/buf.exe breaking --against empty; echo "exit $?"
```
Expected:
- `SAME`.
- Then the breaking result against a directory with no proto, recorded verbatim. Inference: a non-zero exit about no `.proto` files, which is why Task 8 skips `breaking` when the base has no `buf.yaml`.
- If it exits 0, record that too: Task 8's skip stays either way, because it says what happened instead of passing.

- [ ] **Step 6: A Python 3.12 venv with the runtime.**
```bash
uv venv --python 3.12 /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/.venv && uv pip install --python /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/.venv/Scripts/python.exe connectrpc==0.12.1 protobuf-py==0.6.0
```
Expected: installs `connectrpc 0.12.1`, `protobuf-py 0.6.0`, `protobuf-py-ext 0.6.0`, `pyqwest 0.11.0`, `opentelemetry-api` and `typing-extensions`. Record the versions.

- [ ] **Step 7: Read every fact `test_contract.py` relies on.**
```bash
cd /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/server && ../.venv/Scripts/python.exe - <<'EOF'
import importlib, pathlib, sys
sys.path.insert(0, ".")
for path in sorted(pathlib.Path("app/contract").rglob("*.py")):
    parts = path.with_suffix("").parts
    importlib.import_module(".".join(parts[:-1] if parts[-1] == "__init__" else parts))
print("all imported")
from app.contract.google.api import annotations_pb
from app.contract.lessons.v2 import errors_pb, options_pb, ping_pb
for service in ping_pb.desc().services:
    print("service", service.name, service.type_name)
    for method in service.methods:
        opts = method.proto.options
        rule = opts[annotations_pb.ext_http] if annotations_pb.ext_http in opts else None
        print(" ", method.name,
              "http", (rule.pattern.field, rule.pattern.value, rule.body) if rule else None,
              "auth", int(opts[options_pb.ext_auth]) if options_pb.ext_auth in opts else None,
              "min_role", int(opts[options_pb.ext_min_role]) if options_pb.ext_min_role in opts else None,
              "idempotency", int(opts.idempotency_level or 0),
              "streams", bool(method.proto.server_streaming), bool(method.proto.client_streaming),
              "in", method.proto.input_type, "out", method.proto.output_type)
file = ping_pb.desc().proto
print("java", file.options.java_package, bool(file.options.java_multiple_files))
print("messages", [m.type_name for m in ping_pb.desc().messages])
print("fields", [(m.name, [(f.name, f.type_name) for f in m.field]) for m in file.message_type])
print("enums", [(e.name, [(v.name, v.number) for v in e.value]) for e in errors_pb.desc().proto.enum_type])
print("extensions", [(e.name, e.number, e.extendee) for e in options_pb.desc().proto.extension])
EOF
```
Expected (each line is a fact Task 1's test reads):
```
all imported
service PingService lessons.v2.PingService
  GetPing http ('get', '/v2/pings/{name}', '') auth 2 min_role 3 idempotency 1 streams False False in .lessons.v2.GetPingRequest out .lessons.v2.GetPingResponse
  WatchPings http None auth 2 min_role None idempotency 0 streams True False in .lessons.v2.WatchPingsRequest out .lessons.v2.WatchPingsResponse
java com.lumenpearson.lessons.contract.v2 True
messages ['lessons.v2.GetPingRequest', 'lessons.v2.GetPingResponse', 'lessons.v2.WatchPingsRequest', 'lessons.v2.WatchPingsResponse']
fields [('GetPingRequest', [('name', '')]), ('GetPingResponse', [('name', ''), ('at', '.google.protobuf.Timestamp')]), …]
enums [('ErrorReason', [('ERROR_REASON_UNSPECIFIED', 0), ('DEVICE_NOT_LINKED', 3)])]
extensions [('auth', 50001, '.google.protobuf.MethodOptions'), ('min_role', 50002, '.google.protobuf.MethodOptions')]
```
If an attribute raises (for example `rule.pattern.field`, `file.options`, or `type_name` empty or `None` for a scalar instead of `''`), stop: `test_contract.py`'s helpers are written against exactly these attributes.

- [ ] **Step 8: Record.** Paste every command and its output into `C:\Users\lumen\AppData\Local\Temp\contract-plan-scratch\observations.md`. Keep `bin\buf.exe`; every later task uses it. No commit: nothing in the repository changed.

---

## Task 1: Tooling, the shared protos, the runtime packages, and the test that reads them

**Files:**
- Create: `buf.yaml`, `buf.gen.yaml`, `proto/lessons/v2/options.proto`, `proto/lessons/v2/errors.proto`, `proto/lessons/v2/common.proto`, `server/tests/test_contract.py`, and the generated `server/app/contract/**`.
- Modify: `server/pyproject.toml`, `requirements.in`, `requirements.txt` (regenerated by uv).

**Interfaces:**
- Consumes: `buf` and Task 0's observations.
- Produces:
  - `options.proto`: `AuthKind`, `Role`, extensions `auth` (50001) and `min_role` (50002); Python `options_pb.ext_auth` and `options_pb.ext_min_role`.
  - `errors.proto`: `ErrorReason` (33 values).
  - `common.proto`: `DayKind`, `EventKind`, `TermKind`, `Term`.
  - The `app.contract` package.
  - `test_contract.py`'s helpers (`_binding`, `_enum_option`, `_side_effect_free`, `_streams`, `_messages`, `_resolves`) and its tables `FILES`, `SERVICE_FILES`, `METHODS`, `REASONS`.

**What else walks `app/`, and what the generated tree does to each** (all read; none needs an exclusion):
- `tests/test_naive_utc.py` scans `app` for `datetime.utcnow`. Generated code uses none, and it ships, so it should be scanned. Harmless.
- `tests/test_plural_calls.py` scans `app/**/*.py` for `{n} {plural(n,…)}` f-strings. Generated code has none. Harmless.
- `tests/test_announcements.py` (`_announcing_call_sites`) parses every file under `app/`, and imports only modules that call `notify_subscribers`. Generated code never does. Harmless; it adds parse time.
- `tests/test_directory.py` (`_throttles_built_in_app`) parses every file, and imports only those building a `JoinThrottle`. Harmless.
- `tests/test_service_layering.py` builds import edges for every module under `app/`, and starts only from `app.services*`. Generated code imports only `protobuf`, `connectrpc` and its own siblings. Harmless. Extending its start set to `rpc/` and `rest/` is sub-project 3's, as the programme says. The contract gets a stronger rule of its own here: `test_generated_code_stays_inside_app_contract`.
- `tests/test_bot_manage.py` (the `CallbackData` census) walks only `app.bot` through `pkgutil`. Unaffected.
- `tests/test_cold_start.py` imports `app.main`, which imports nothing of the contract. Unaffected; Task 1 adds the contract's own probe.
- Ruff and mypy are told to skip the tree (Step 7). Every generated file also starts with `# ruff: noqa`.

- [ ] **Step 1: Make the worktree.**
```bash
cd /c/Users/lumen/StudioProjects/lessons && git status --short && git worktree add .claude/worktrees/contract-v2 -b contract/v2 contract/spec
```
Expected: `Preparing worktree (new branch 'contract/v2')`, then `HEAD is now at …` naming the newest commit of `contract/spec`, which holds both specs and this plan.

- [ ] **Step 2: Write the failing test, `server/tests/test_contract.py`, in full.**
```python
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
CONTRACT = SERVER / "app" / "contract"
V2 = CONTRACT / "lessons" / "v2"

JAVA_PACKAGE = "com.lumenpearson.lessons.contract.v2"

#: Every file of ``proto/lessons/v2``, by the stem of its generated module. A
#: proto file added without regenerating, or a module left behind by a deleted
#: file, fails here before CI's regeneration finds it.
FILES = {"common", "errors", "options"}

#: The files that declare a service, beside which ``buf.build/connectrpc/py``
#: also writes a ``_connect`` module.
SERVICE_FILES: set[str] = set()

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
METHODS: dict[tuple[str, str], Row] = {}

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
    whole request as its body, and the verb is the method's own --
    ``RotateCalendarFeed`` is ``:rotate``."""
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
```

- [ ] **Step 3: Watch it fail.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2/server && /c/Users/lumen/StudioProjects/lessons/server/.venv/Scripts/python.exe -m pytest -q -p no:xdist tests/test_contract.py
```
Expected: `ERROR tests/test_contract.py` with `ModuleNotFoundError: No module named 'app.contract'`, then `1 error`.

- [ ] **Step 4: Declare the runtime.**
  - In `server/pyproject.toml`, append to `[project] dependencies` after the `cryptography` entry:
```toml
    # The v2 contract's runtime (docs/specs/2026-10-04-contract-v2-design.md,
    # decision 2). The code generated into app/contract imports protobuf-py's
    # `protobuf` package, and its services import `connectrpc`; each floor is the
    # version of the Buf plugin that generates for it, which buf.gen.yaml pins
    # and tests/test_contract.py holds level. Nothing in app.main imports either
    # yet, so the cold start does not change until sub-project 3 serves v2.
    # connectrpc brings pyqwest, a Rust HTTP client of about 16 MB it requires
    # even on a server, and pyqwest brings opentelemetry-api.
    "connectrpc>=0.12.1",
    "protobuf-py>=0.6.0",
```
  - In `requirements.in`, append at the end:
```
# The v2 contract's runtime: the code generated into server/app/contract
# imports protobuf-py, and its services import connectrpc. Floors are the
# pinned plugins' versions (buf.gen.yaml); imported by nothing on the cold path
# until sub-project 3 serves v2.
connectrpc>=0.12.1
protobuf-py>=0.6.0
```

- [ ] **Step 5: Regenerate the lock and check it only grew.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && uv pip compile requirements.in --python-version 3.12 --python-platform linux --output-file requirements.txt && git diff requirements.txt | grep -E '^[-+][a-z]'
```
Expected: `Resolved 39 packages in …`, then exactly these five lines and no `-` line (versions as of 4 October 2026; inference):
```
+connectrpc==0.12.1
+opentelemetry-api==1.45.0
+protobuf-py==0.6.0
+protobuf-py-ext==0.6.0
+pyqwest==0.11.0
```
If an existing pin moved, stop: uv without `--upgrade` keeps pins, so a moved one means the lock was not what `main` has.

- [ ] **Step 6: Install the new packages into the shared venv, constrained by the lock** (additive, and not `-e`):
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2/server && /c/Users/lumen/StudioProjects/lessons/server/.venv/Scripts/python.exe -m pip install connectrpc protobuf-py -c ../requirements.txt
```
Expected: `Successfully installed connectrpc-0.12.1 opentelemetry-api-1.45.0 protobuf-py-0.6.0 protobuf-py-ext-0.6.0 pyqwest-0.11.0`.

- [ ] **Step 7: Keep ruff and mypy off the generated tree.**
  - In `server/pyproject.toml`, under `[tool.ruff]`, after `target-version = "py311"`:
```toml
# The generated contract: buf generate rewrites server/app/contract whole, so a
# finding in it has nobody to fix it. Every generated file already says
# `# ruff: noqa`; this keeps it out of every future rule and of `ruff format`.
extend-exclude = ["app/contract"]
```
  - Under `[tool.mypy]`, after `files = ["app"]`:
```toml
# The generated contract is not ours to type-check: regeneration rewrites it
# whole. Excluded from the walk here, and silenced below where hand-written code
# imports it (sub-project 3's rpc/ and rest/), so its types still reach that code.
exclude = ["^app/contract/"]
```
  - At the end of the `[tool.mypy]` section, before `[tool.pytest.ini_options]`:
```toml
[[tool.mypy.overrides]]
module = ["app.contract.*"]
ignore_errors = true
```

- [ ] **Step 8: Write `buf.yaml` (repository root) in full.**
```yaml
# The v2 contract (docs/specs/2026-10-04-contract-v2-design.md). One module,
# proto/, whose files are imported as lessons/v2/<file>.proto. CI's «Contract»
# job lints it, checks it for breaking changes against the base branch, and
# regenerates server/app/contract from it (.github/workflows/ci.yml).
version: v2
modules:
  - path: proto
lint:
  use:
    - STANDARD
  ignore_only:
    # ErrorReason's value names are the ErrorInfo.reason strings the server
    # sends -- "DEVICE_NOT_LINKED" -- as google/api/error_reason.proto does it,
    # so they carry no ERROR_REASON_ prefix. Only this rule, only this file.
    ENUM_VALUE_PREFIX:
      - proto/lessons/v2/errors.proto
breaking:
  use:
    # The strictest category: it also protects the generated Python and Kotlin
    # names, which a field moved between files would change.
    - FILE
```
If Task 0 Step 4 found that the module-relative form works instead, write `lessons/v2/errors.proto` there.

- [ ] **Step 9: Write `buf.gen.yaml` (repository root) in full.**
```yaml
# Python only: Kotlin and Java lite wait for sub-project 5, which adds them
# with the bindings that use them (the design's decision 3).
#
# Every remote plugin is pinned. An unpinned one takes Buf's latest release,
# and CI regenerates and fails on any difference, so a release on Buf's side
# would turn CI red with nothing changed here. Each pin is the floor of its
# runtime in requirements.in, and tests/test_contract.py holds the two level.
version: v2
# server/app/contract is deleted and rewritten whole, so nothing hand-written
# may live there.
clean: true
plugins:
  # Messages, for protobuf-py. include_imports brings google/api/annotations
  # and http along, which every service file imports; the well-known types are
  # protobuf-py's own (`protobuf.wkt`) and are not generated.
  - remote: buf.build/bufbuild/py:v0.6.0
    out: server/app/contract
    include_imports: true
  # Services: connectrpc's interfaces and clients over those messages.
  - remote: buf.build/connectrpc/py:v0.12.1
    out: server/app/contract
```

- [ ] **Step 10: Write `proto/lessons/v2/options.proto` in full.**
```proto
// Who may call a method, said by the method itself (the design's decision 5).
//
// Every method sets (lessons.v2.auth); every method that acts on the class sets
// (lessons.v2.min_role), reads included. Sub-project 3's RPC layer enforces both
// from the descriptor in one place, so a method cannot be added without saying
// who may call it, and which of the two bearer tokens a method takes is a
// property of the method rather than of a path prefix.
// server/tests/test_contract.py fails on a method that leaves either out.
syntax = "proto3";

package lessons.v2;

import "google/protobuf/descriptor.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

// The credential a method takes.
enum AuthKind {
  // Never set on purpose: a method left here is a method nobody decided about.
  AUTH_KIND_UNSPECIFIED = 0;
  // No credential: exchanging a code for a token, the diary's capabilities,
  // the anonymous region search.
  AUTH_KIND_NONE = 1;
  // The class device token, linked to an account or not.
  AUTH_KIND_DEVICE = 2;
  // A device token whose phone is linked to an account. An unlinked phone is
  // refused with DEVICE_NOT_LINKED, as v1's personal endpoints refuse it.
  AUTH_KIND_DEVICE_LINKED = 3;
  // The diary session token, the other bearer. A phone may hold either
  // without the other, so neither implies the other.
  AUTH_KIND_DIARY = 4;
}

// A member's role in a class, weakest first: the ladder of services/roles.py.
// Declared here rather than in common.proto because (min_role) is typed by
// it, and every file that sets the option already imports this one.
enum Role {
  // No role: an unlinked device. v1 sent null.
  ROLE_UNSPECIFIED = 0;
  // «Наблюдатель».
  ROLE_VIEWER = 1;
  // «Редактор»: homework, substitutions, events and marked days.
  ROLE_EDITOR = 2;
  // «Администратор»: subjects, bells, the timetable, devices, requests,
  // terms and the class itself.
  ROLE_ADMIN = 3;
  // «Владелец»: an admin's rights, and deleting the class.
  ROLE_OWNER = 4;
}

extend google.protobuf.MethodOptions {
  // The credential the method takes.
  AuthKind auth = 50001;
  // The least role the linked account must hold, read only when `auth` is a
  // device kind. ROLE_VIEWER is any holder of the class's device token, the
  // class code's anonymous read-only token included, and asks no role; the
  // other three ask linking.effective_role on every request, as v1 does, so
  // an unlinked phone reaches none of them and a role taken away in the bot
  // is gone here in the same instant.
  Role min_role = 50002;
}
```

- [ ] **Step 11: Write `proto/lessons/v2/errors.proto` in full.**
```proto
// Why a request was refused (the design's decision 6).
//
// Every refusal carries a google.rpc.ErrorInfo whose `domain` is "lessons.app"
// and whose `reason` is one of these names exactly: the server sends the
// string "DEVICE_NOT_LINKED", and the enum exists so that both sides get a
// generated constant rather than a retyped string. That is why the values
// carry no ERROR_REASON_ prefix, as google/api/error_reason.proto does it;
// buf.yaml lets this one file off ENUM_VALUE_PREFIX, and only it.
//
// A client acts on the reason, never on the message, which is the server's
// own sentence for a person. A reason it does not know it handles by the
// canonical code, which is why every reason sits under a code that already
// says what to do. A reason is added, never renamed, renumbered or reused.
//
// Each value says its canonical code, the ErrorInfo metadata it carries, and
// what v1 sent in its place.
syntax = "proto3";

package lessons.v2;

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

enum ErrorReason {
  // Never sent.
  ERROR_REASON_UNSPECIFIED = 0;
  // UNAUTHENTICATED. No device token, or one that is unknown or revoked.
  // v1: the 401 of api/deps.py («Missing bearer token», «Invalid token»).
  DEVICE_TOKEN_INVALID = 1;
  // UNAUTHENTICATED. No diary session token, or one that is not valid.
  // v1: the 401 of api/diary.py current_diary.
  DIARY_TOKEN_INVALID = 2;
  // PERMISSION_DENIED. The method needs a linked phone and this one is not.
  // v1: 403 «device is not linked».
  DEVICE_NOT_LINKED = 3;
  // PERMISSION_DENIED. metadata `role`: the least role the method needs, as a
  // Role value name ("ROLE_ADMIN"). v1: 403 «<role> role required».
  ROLE_REQUIRED = 4;
  // NOT_FOUND. Neither a class code nor a live personal code; counted against
  // the caller. v1: 404 from /join.
  JOIN_CODE_UNKNOWN = 5;
  // PERMISSION_DENIED. A real class code for a class that takes personal codes
  // only; not counted, because the caller had a real code. v1: 403 from /join.
  CLASS_INVITE_ONLY = 6;
  // RESOURCE_EXHAUSTED. metadata `limit`: how many live phones the class code
  // admits (#199). A personal code from the bot still works.
  // v1: 409 from /join.
  DEVICE_LIMIT_REACHED = 7;
  // RESOURCE_EXHAUSTED. metadata `retry_after_seconds`.
  // v1: every 429, with Retry-After.
  THROTTLED = 8;
  // NOT_FOUND. metadata `resource`: "class", "subject", "bell_schedule",
  // "homework", "substitution", "event", "task", "device", "access_request"
  // or "student". An id from another class finds nothing, by design.
  // v1: every other 404.
  RESOURCE_NOT_FOUND = 9;
  // ALREADY_EXISTS. metadata `resource` and `field`: what is taken, and by
  // which field ("subject" and "name", "homework" and "subject",
  // "substitution" and "index"). v1: the 409 «a subject with that name is
  // already in this class».
  RESOURCE_EXISTS = 10;
  // INVALID_ARGUMENT. A google.rpc.BadRequest in the details names each
  // request field that was wrong, so a client tells them apart without the
  // message. v1: every 422.
  VALIDATION_FAILED = 11;
  // FAILED_PRECONDITION. metadata `index`: a lesson number the day rings no
  // bell for, so nothing would draw it. v1: 422 «нет звонка для урока №N в
  // этот день».
  NO_BELL_FOR_LESSON = 12;
  // FAILED_PRECONDITION. A day, or the class default, pointed at a bell
  // schedule with no rows. v1: 422 «в этом расписании звонков нет ни одного
  // урока».
  EMPTY_BELL_SCHEDULE = 13;
  // UNAVAILABLE. The deployment has no DIARY_SECRET, so the diary does not
  // run; nothing the person types will help.
  // v1: 503 with X-Diary-Unavailable: disabled.
  DIARY_DISABLED = 14;
  // UNAVAILABLE. metadata `upstream`: "upstream" (the diary is down or will
  // not take this way in: try later) or "address-refused" (the region drops
  // this server's address: not the password, not worth retrying).
  // v1: 503 with X-Diary-Unavailable.
  DIARY_UNAVAILABLE = 15;
  // UNAUTHENTICATED. The diary ended the session: sign in to it again.
  // v1: 401 with X-Diary-Reauth: required.
  DIARY_REAUTH = 16;
  // PERMISSION_DENIED. The diary would not take the session the phone opened
  // from this server. Asking for the password again would loop: the session
  // was good on the phone seconds ago. v1: 409 from /diary/session.
  DIARY_CREDENTIALS_REJECTED = 17;
  // UNAVAILABLE. The deployment has no DADATA_TOKEN: pick the region from the
  // list, or type the school. v1: 503 with X-Directory-Unavailable: disabled.
  DIRECTORY_DISABLED = 18;
  // RESOURCE_EXHAUSTED. metadata `retry_after_seconds`, until the next Moscow
  // day: today's anonymous share of the directory is spent.
  // v1: 503 with X-Directory-Unavailable: spent, and Retry-After.
  DIRECTORY_SPENT = 19;
  // UNAVAILABLE. The directory failed.
  // v1: 503 with X-Directory-Unavailable: upstream.
  DIRECTORY_UNAVAILABLE = 20;
  // FAILED_PRECONDITION. metadata `min_version`: the oldest client version
  // code the server still serves; the app says «обновите приложение».
  // New in v2.
  CLIENT_TOO_OLD = 21;
  // UNIMPLEMENTED. metadata `feature`: a DiaryFeature value name, or a server
  // capability, that this server or this diary does not have. New in v2.
  FEATURE_UNSUPPORTED = 22;
  // INVALID_ARGUMENT. The request could not be decoded at all: bad JSON, a
  // bad varint, a string where a number belongs. New in v2: the spike's 500.
  REQUEST_UNDECODABLE = 23;
  // PERMISSION_DENIED. The diary account lists no pupil, so there is nothing
  // to read. v1: 403 from /diary/session (api/diary.py, NoStudents).
  DIARY_NO_STUDENTS = 24;
  // UNAVAILABLE. The diary answered something nobody can read, often a login
  // page where data belongs. v1: 502 (api/diary.py, UnexpectedResponse and
  // DiaryError, in _guard and in register_session).
  DIARY_UPSTREAM_UNREADABLE = 25;
  // FAILED_PRECONDITION. This pupil can have no corrections: the diary lists
  // them outside its own numbering. v1: 422 «Для этого ученика правки
  // недоступны» (api/diary.py, put_override).
  CORRECTIONS_UNAVAILABLE = 26;
  // FAILED_PRECONDITION. metadata `resource`, `used_by` and, where there is
  // one, `count`: a subject the weekly template still teaches ("lessons"), a
  // bell schedule special days point at ("days"), or the class default
  // ("class"). v1: 409 from DELETE /manage/subjects/{id} (SubjectInUse) and
  // DELETE /manage/bells/{id} (ScheduleIsDefault, ScheduleInUse).
  RESOURCE_IN_USE = 27;
  // FAILED_PRECONDITION. metadata `dates`: comma-separated "YYYY-MM-DD", the
  // days on which both names have homework, which a rename would merge.
  // v1: 409 «homework under both names on the same day» (HomeworkClash).
  SUBJECT_RENAME_CLASH = 28;
  // FAILED_PRECONDITION. The device an admin asked to unlink is not linked.
  // v1: 409 from POST /manage/devices/{id}/unlink (DeviceNotLinked).
  CLASS_DEVICE_NOT_LINKED = 29;
  // PERMISSION_DENIED. metadata `why`: "role_too_high" (nobody grants at or
  // above their own role) or "member_senior" (the member is the admin's peer
  // or senior). v1: 403 from POST /manage/requests/{id}/approve (GrantRefused).
  ROLE_GRANT_REFUSED = 30;
  // FAILED_PRECONDITION. A term's dates the school year cannot hold: past
  // 31 May, overlapping a neighbour, ending before they start. The message is
  // the service's own Russian sentence. v1: 422 from PUT /manage/terms/{index}
  // (TermError).
  TERM_BOUNDS_REFUSED = 31;
  // FAILED_PRECONDITION. metadata `why`: "out_of_year", "between_terms",
  // "public_holiday" or "marked_day_off". The day draws no lessons at all, so
  // a substitution on it would be announced and drawn nowhere. v1: 422 from
  // PUT /overrides (timetable_edit.why_no_lesson_can_be_drawn).
  NO_LESSON_ON_DAY = 32;
  // FAILED_PRECONDITION. metadata `index`: cancelling, or substituting without
  // a subject, a lesson number the day's template does not have.
  // v1: 422 «в этот день нет урока №N…» from PUT /overrides.
  LESSON_NOT_ON_TIMETABLE = 33;
}
```

- [ ] **Step 12: Write `proto/lessons/v2/common.proto` in full.**
```proto
// What several files of lessons.v2 share, and the conventions the package keeps.
//
// - A date is a string "YYYY-MM-DD" and a time of day a string "HH:MM": naive
//   wall time in the class's zone, because a bell rings at 08:30 whether or
//   not the clocks changed, and google.protobuf.Timestamp is an instant in UTC.
//   A wall-clock moment is "YYYY-MM-DDTHH:MM". Timestamp is kept for what
//   really is an instant: when something was written, done or last seen.
// - An id this server assigns is int32, the width of its columns. An id a
//   diary assigns is int64, because nothing bounds it; proto3 JSON writes an
//   int64 as a string.
// - `optional` marks a field that may be absent: v1's null. An enum field says
//   «none» with its _UNSPECIFIED value instead, so absence has one spelling.
// - Every enum starts at <NAME>_UNSPECIFIED = 0, and a client maps a value it
//   does not know to a fallback it states, never to a failure.
// - Every method answers <Method>Response, even where it holds one resource:
//   one method's answer can then grow a field without touching another's.
syntax = "proto3";

package lessons.v2;

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

// How a whole date departs from the weekly rhythm. v1: DayKind's values.
enum DayKind {
  DAY_KIND_UNSPECIFIED = 0;
  // An ordinary teaching day. Setting it takes a mark off.
  DAY_KIND_NORMAL = 1;
  // A holiday or a day off for everybody: no lessons.
  DAY_KIND_HOLIDAY = 2;
  // Shortened lessons, on the bell schedule the day names.
  DAY_KIND_SHORTENED = 3;
  // Remote teaching, at the usual times.
  DAY_KIND_REMOTE = 4;
  // Set work, nobody at school.
  DAY_KIND_SELF_STUDY = 5;
  // A day off this class alone was given («🌿 Отгул»): no lessons.
  DAY_KIND_DAY_OFF = 6;
}

// What an event is. v1: EventKindName.
enum EventKind {
  EVENT_KIND_UNSPECIFIED = 0;
  // «мероприятие»: stands in for the lessons it overlaps unless told otherwise.
  EVENT_KIND_EVENT = 1;
  // A canteen break: sits in a break.
  EVENT_KIND_CANTEEN = 2;
  EVENT_KIND_EXAM = 3;
  // A trip: stands in for lessons unless told otherwise.
  EVENT_KIND_TRIP = 4;
  EVENT_KIND_MEETING = 5;
}

// How the school year is cut. v1: "quarter" | "semester".
enum TermKind {
  TERM_KIND_UNSPECIFIED = 0;
  // Four quarters: what younger classes are taught in.
  TERM_KIND_QUARTER = 1;
  // Two half-years: usual in 10 and 11, for the leaving exams.
  TERM_KIND_SEMESTER = 2;
}

// One quarter or half-year, as the class actually runs it. v1: TermOut.
message Term {
  // 1-based within the school year.
  int32 index = 1;
  TermKind kind = 2;
  // "YYYY-MM-DD", the term's first day.
  string starts_on = 3;
  // "YYYY-MM-DD", the term's last day.
  string ends_on = 4;
}
```

- [ ] **Step 13: Lint and generate.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe lint && echo LINT-CLEAN && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe generate && git status --short --untracked-files=all server/app/contract
```
Expected:
```
LINT-CLEAN
?? server/app/contract/__init__.py
?? server/app/contract/lessons/__init__.py
?? server/app/contract/lessons/v2/__init__.py
?? server/app/contract/lessons/v2/common_pb.py
?? server/app/contract/lessons/v2/errors_pb.py
?? server/app/contract/lessons/v2/options_pb.py
```
The `__init__.py` files matter beyond imports: the `Dockerfile`'s `pip install .` finds packages through `setuptools.packages.find`, which skips a directory with no `__init__.py`.

- [ ] **Step 14: Watch the test pass.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2/server && /c/Users/lumen/StudioProjects/lessons/server/.venv/Scripts/python.exe -m pytest -q -p no:xdist tests/test_contract.py
```
Expected: `24 passed`. The method checks pass over nothing until Task 2.

- [ ] **Step 15: Gates, and the tests that walk `app/`.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2/server && /c/Users/lumen/StudioProjects/lessons/server/.venv/Scripts/python.exe -m ruff check app tests scripts migrations && /c/Users/lumen/StudioProjects/lessons/server/.venv/Scripts/python.exe -m mypy && /c/Users/lumen/StudioProjects/lessons/server/.venv/Scripts/python.exe -m pytest -q -p no:xdist tests/test_requirements_mirror.py tests/test_naive_utc.py tests/test_plural_calls.py tests/test_service_layering.py tests/test_announcements.py tests/test_directory.py tests/test_cold_start.py tests/test_test_imports.py
```
Expected:
- `All checks passed!`
- `Success: no issues found in 197 source files`
- no failures. `test_requirements_mirror.py` has 8 tests: all pass, or 7 pass and 1 is skipped with «this environment is not the lock's…». CI's install settles the eighth.

- [ ] **Step 16: Full suite** (check `tasklist | grep -i java` first).
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2/server && /c/Users/lumen/StudioProjects/lessons/server/.venv/Scripts/python.exe -m pytest -q -n auto
```
Expected: no failures, and the passed count is the previous run's plus 24 (2099 if the documented 2075 is current).

- [ ] **Step 17: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && git add buf.yaml buf.gen.yaml proto server/app/contract server/tests/test_contract.py server/pyproject.toml requirements.in requirements.txt && git commit -F - <<'EOF'
Write down the contract's shared vocabulary and generate it into app.contract

The v2 contract starts with what every service will lean on. options.proto
declares the two method options that say who may call a method, the
credential and the least role; errors.proto the reasons a refusal carries,
the design's twenty-three completed with the ten more that v1 really raises;
common.proto the day, event and term kinds and the term that several
services share. buf.yaml lints them with Buf's STANDARD rules and lets only
errors.proto off the enum-prefix rule, because its value names are the
reason strings the server sends. buf.gen.yaml pins both remote plugins and
writes Python only, into server/app/contract, because Kotlin waits for
sub-project 5.

The generated modules import protobuf-py, so it and connectrpc join
pyproject.toml, requirements.in and the lock, each at the version of the
plugin that generates for it. Nothing in app.main imports any of it, and
tests/test_contract.py says so in a fresh interpreter in both deployment
configurations, beside the checks Buf cannot make. Ruff and mypy skip the
generated tree, which every regeneration rewrites whole.

Not covered yet: no service exists, so the method checks pass over nothing
until the next commit, and CI runs no Buf until the Contract job is added.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 2: DeviceService, MeService, ScheduleService, and the googleapis dependency

**Files:**
- Create: `proto/lessons/v2/device.proto`, `me.proto`, `schedule.proto`, `buf.lock`, and the generated `google/**`, `device_*`, `me_*` and `schedule_*`.
- Modify: `buf.yaml` (adds the dependency), `server/tests/test_contract.py`.

**Interfaces:**
- Consumes: `options.proto` (`AuthKind`, `Role`), `common.proto` (`DayKind`, `EventKind`, `TermKind`, `Term`).
- Produces:
  - `DeviceService`, `MeService`, `ScheduleService`;
  - the messages `Me`, `LinkCode`, `CalendarFeed`, `Task`, `HomeworkTick`, `ScheduleWindow`, `ScheduleDay`, `Lesson`, `ScheduleEvent`, `ScheduleHomework`, `Holiday`, `DeviceAccess`, `ClassSummary`, `DiaryBinding`; the enum `DayOffReason`;
  - `app.contract.google.api.annotations_pb.ext_http`;
  - the meta-test `test_the_readers_see_what_they_are_written_for`.

- [ ] **Step 1: Red first.** In `test_contract.py`:
  - Set `FILES = {"common", "device", "errors", "me", "options", "schedule"}` and `SERVICE_FILES = {"device", "me", "schedule"}`.
  - Replace `METHODS: dict[tuple[str, str], Row] = {}` with:
```python
METHODS: dict[tuple[str, str], Row] = {
    ("DeviceService", "CreateDevice"): Row("post", "/v2/devices", "*", NONE, None),
    ("MeService", "GetMe"): Row("get", "/v2/me", "", DEVICE, None),
    ("MeService", "UnlinkMe"): Row("post", "/v2/me:unlink", "*", DEVICE, None),
    ("MeService", "CreateLinkCode"): Row("post", "/v2/me/linkCodes", "*", DEVICE, None),
    ("MeService", "GetCalendarFeed"): Row(
        "get", "/v2/me/calendarFeed", "", DEVICE_LINKED, None
    ),
    ("MeService", "RotateCalendarFeed"): Row(
        "post", "/v2/me/calendarFeed:rotate", "*", DEVICE_LINKED, None
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
}
```
  - Append the meta-test at the end of the file:
```python
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
```
  - Run `python -m pytest -q -p no:xdist tests/test_contract.py`. Expected failures: `test_every_proto_file_was_generated`, `test_the_methods_are_the_resource_map` («rows of METHODS the proto does not define») and `test_the_readers_see_what_they_are_written_for` (`KeyError`).

- [ ] **Step 2: Add the dependency to `buf.yaml`.** It now reads in full:
```yaml
# The v2 contract (docs/specs/2026-10-04-contract-v2-design.md). One module,
# proto/, whose files are imported as lessons/v2/<file>.proto. CI's «Contract»
# job lints it, checks it for breaking changes against the base branch, and
# regenerates server/app/contract from it (.github/workflows/ci.yml).
version: v2
modules:
  - path: proto
deps:
  # google/api/annotations.proto and http.proto: every method's REST binding.
  # The commit is pinned in buf.lock.
  - buf.build/googleapis/googleapis
lint:
  use:
    - STANDARD
  ignore_only:
    # ErrorReason's value names are the ErrorInfo.reason strings the server
    # sends -- "DEVICE_NOT_LINKED" -- as google/api/error_reason.proto does it,
    # so they carry no ERROR_REASON_ prefix. Only this rule, only this file.
    ENUM_VALUE_PREFIX:
      - proto/lessons/v2/errors.proto
breaking:
  use:
    # The strictest category: it also protects the generated Python and Kotlin
    # names, which a field moved between files would change.
    - FILE
```
Then `buf dep update` from the worktree root. Expected: `buf.lock` with `buf.build/googleapis/googleapis`, its `commit:` and a `digest: b5:…`.

- [ ] **Step 3: Write `proto/lessons/v2/device.proto` in full.**
```proto
// Getting a phone into a class: a code in, the device token out.
// Replaces v1's POST /join.
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service DeviceService {
  // Exchanges a code for a long-lived device token. The class code (eight
  // characters) buys an anonymous, read-only token; a personal code from the
  // bot's «📱 Подключить телефон» (ten characters, fifteen minutes, one phone)
  // buys one already linked to the account that minted it. The class code is
  // looked up first, and the lengths keep the two apart. REST answers 201.
  //
  // Refusals: JOIN_CODE_UNKNOWN, which is counted against the caller;
  // CLASS_INVITE_ONLY and DEVICE_LIMIT_REACHED, which are not, because the
  // caller had a real code; THROTTLED.
  rpc CreateDevice(CreateDeviceRequest) returns (CreateDeviceResponse) {
    option (google.api.http) = {
      post: "/v2/devices"
      body: "*"
    };
    option (lessons.v2.auth) = AUTH_KIND_NONE;
  }
}

message CreateDeviceRequest {
  // The class code or a personal code: 4 to 16 characters once control
  // characters are dropped; case does not matter. v1: JoinRequest.code.
  string code = 1;
  // What the person calls this phone, up to 120 characters, shown on
  // «📱 Устройства». v1: JoinRequest.device_name.
  optional string device_name = 2;
}

message CreateDeviceResponse {
  // The device token, for `Authorization: Bearer`. Sent once: the server
  // keeps only its hash.
  string token = 1;
  int32 class_id = 2;
  string class_name = 3;
  optional string school = 4;
  // The class's IANA zone, e.g. "Europe/Moscow": where its days are cut.
  string timezone = 5;
  // The diary the class is bound to in the bot, when this server can reach
  // it, so the phone signs in there without searching for its school.
  DiaryBinding diary = 6;
}

// Which diary a class reads. v1: DiaryBindingOut.
message DiaryBinding {
  // A provider key, "petersburg" or "netschool" today: a string rather than
  // an enum, so a provider added on the server reaches the phone with no
  // change here (the design's decision 8).
  string provider = 1;
  // The allow-list key, which is the region catalog's key; absent for
  // Petersburg, which is one city's server.
  optional string region = 2;
  // The diary's own school id («scid»), which the phone signs in with.
  optional int64 school_id = 3;
  optional string school_name = 4;
}
```

- [ ] **Step 4: Write `proto/lessons/v2/me.proto` in full.**
```proto
// What a phone does for itself: who it is, linking it to an account, the
// class's calendar feed, and the linked account's own tasks and homework
// ticks, which nobody else in the class sees. Nothing here changes the class.
// Replaces v1's /me, /me/unlink, /calendar, /tasks… and
// POST /homework/{id}/done.
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "google/protobuf/field_mask.proto";
import "google/protobuf/timestamp.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service MeService {
  // Who this device is. Mints nothing: v1's GET /me issued a link code as a
  // side effect, which CreateLinkCode does now.
  rpc GetMe(GetMeRequest) returns (GetMeResponse) {
    option (google.api.http) = {get: "/v2/me"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
  }
  // Back to read-only, keeping the phone in the class. Unlinking a phone that
  // is not linked is not an error.
  rpc UnlinkMe(UnlinkMeRequest) returns (UnlinkMeResponse) {
    option (google.api.http) = {
      post: "/v2/me:unlink"
      body: "*"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
  }
  // A code that links this phone to the Telegram account that sends it to the
  // bot. A phone already linked gets none: a code left on a linked row could
  // be typed by somebody else and re-home the phone.
  rpc CreateLinkCode(CreateLinkCodeRequest) returns (CreateLinkCodeResponse) {
    option (google.api.http) = {
      post: "/v2/me/linkCodes"
      body: "*"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
  }
  // The class's calendar subscription, when the class has a feed secret.
  // Never mints one.
  rpc GetCalendarFeed(GetCalendarFeedRequest) returns (GetCalendarFeedResponse) {
    option (google.api.http) = {get: "/v2/me/calendarFeed"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE_LINKED;
  }
  // Mints the class's feed secret when it has none, and answers the feed.
  // Like v1's GET /calendar on first ask, it does not replace a secret that
  // exists: the feed is the whole class's, and a new secret would cut off
  // every calendar subscribed to the old one.
  rpc RotateCalendarFeed(RotateCalendarFeedRequest) returns (RotateCalendarFeedResponse) {
    option (google.api.http) = {
      post: "/v2/me/calendarFeed:rotate"
      body: "*"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE_LINKED;
  }
  // The linked account's tasks in this class, at most 200; done ones only
  // when asked for.
  rpc ListTasks(ListTasksRequest) returns (ListTasksResponse) {
    option (google.api.http) = {get: "/v2/me/tasks"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE_LINKED;
  }
  // RESOURCE_NOT_FOUND for somebody else's task as for one that never
  // existed: an id must not reveal that a classmate keeps a list.
  rpc GetTask(GetTaskRequest) returns (GetTaskResponse) {
    option (google.api.http) = {get: "/v2/me/tasks/{task_id}"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE_LINKED;
  }
  // `homework_id`, when set, names homework of this class (VALIDATION_FAILED
  // otherwise). REST answers 201.
  rpc CreateTask(CreateTaskRequest) returns (CreateTaskResponse) {
    option (google.api.http) = {
      post: "/v2/me/tasks"
      body: "task"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE_LINKED;
  }
  // `update_mask` takes title, notes, subject_name, due_date, due_time,
  // priority, homework_id, remind_at and done; v1's POST /tasks/{id}/done is
  // this with `done`. A path whose field is absent clears it, except title
  // and priority, which cannot be cleared.
  rpc UpdateTask(UpdateTaskRequest) returns (UpdateTaskResponse) {
    option (google.api.http) = {
      patch: "/v2/me/tasks/{task.id}"
      body: "task"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE_LINKED;
  }
  rpc DeleteTask(DeleteTaskRequest) returns (DeleteTaskResponse) {
    option (google.api.http) = {delete: "/v2/me/tasks/{task_id}"};
    option (lessons.v2.auth) = AUTH_KIND_DEVICE_LINKED;
  }
  // Ticks homework off for the linked account. Ticking it twice is not an
  // error: the app sends the state it shows, so a retry lands on the same
  // answer.
  rpc CreateHomeworkTick(CreateHomeworkTickRequest) returns (CreateHomeworkTickResponse) {
    option (google.api.http) = {
      post: "/v2/me/homeworkTicks"
      body: "homework_tick"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE_LINKED;
  }
  // Takes the tick off; taking off one that is not there is not an error.
  rpc DeleteHomeworkTick(DeleteHomeworkTickRequest) returns (DeleteHomeworkTickResponse) {
    option (google.api.http) = {delete: "/v2/me/homeworkTicks/{homework_id}"};
    option (lessons.v2.auth) = AUTH_KIND_DEVICE_LINKED;
  }
}

// This device, as it sees itself. Never carries the Telegram id behind it.
// v1: MeOut without the code, which is CreateLinkCode's now.
message Me {
  optional string device_name = 1;
  bool linked = 2;
  // ROLE_UNSPECIFIED for an unlinked device.
  Role role = 3;
  // Editor or above.
  bool can_edit = 4;
}

// v1: MeOut.link_code and MeOut.bot_deep_link.
message LinkCode {
  // What the bot takes as «/start link_<code>».
  string code = 1;
  // https://t.me/<bot>?start=link_<code>; absent when the deployment names
  // no bot.
  optional string bot_deep_link = 2;
}

// v1: CalendarOut.
message CalendarFeed {
  // The subscription address, …/api/v1/calendar/<secret>.ics: the feed
  // itself stays plain HTTP at its v1 path. Absent while the class has no
  // secret.
  optional string url = 1;
}

// One of the linked account's own tasks. v1: TaskOut, TaskIn and TaskPatch.
message Task {
  // Assigned by the server; ignored on create.
  int32 id = 1;
  // 1 to 200 characters, one line.
  string title = 2;
  // Up to 2000 characters; line breaks kept.
  optional string notes = 3;
  optional string subject_name = 4;
  // "YYYY-MM-DD".
  optional string due_date = 5;
  // "HH:MM".
  optional string due_time = 6;
  // 0, 1 or 2; absent on create means 1.
  optional int32 priority = 7;
  bool done = 8;
  // When it was done. Output only.
  google.protobuf.Timestamp done_at = 9;
  optional int32 homework_id = 10;
  // "YYYY-MM-DDTHH:MM", the class's wall time.
  optional string remind_at = 11;
  // Output only.
  google.protobuf.Timestamp created_at = 12;
  // Output only.
  google.protobuf.Timestamp updated_at = 13;
}

// The linked account has done this homework. v1: DoneIn/DoneOut on
// POST /homework/{id}/done.
message HomeworkTick {
  int32 homework_id = 1;
}

message GetMeRequest {}

message GetMeResponse {
  Me me = 1;
}

message UnlinkMeRequest {}

message UnlinkMeResponse {
  // Unlinked now. v1 answered {"linked": false}.
  Me me = 1;
}

message CreateLinkCodeRequest {}

message CreateLinkCodeResponse {
  // Absent for a phone that is already linked.
  LinkCode link_code = 1;
}

message GetCalendarFeedRequest {}

message GetCalendarFeedResponse {
  CalendarFeed calendar_feed = 1;
}

message RotateCalendarFeedRequest {}

message RotateCalendarFeedResponse {
  CalendarFeed calendar_feed = 1;
}

message ListTasksRequest {
  bool include_done = 1;
}

message ListTasksResponse {
  repeated Task tasks = 1;
}

message GetTaskRequest {
  int32 task_id = 1;
}

message GetTaskResponse {
  Task task = 1;
}

message CreateTaskRequest {
  Task task = 1;
}

message CreateTaskResponse {
  Task task = 1;
}

message UpdateTaskRequest {
  Task task = 1;
  google.protobuf.FieldMask update_mask = 2;
}

message UpdateTaskResponse {
  Task task = 1;
}

message DeleteTaskRequest {
  int32 task_id = 1;
}

message DeleteTaskResponse {}

message CreateHomeworkTickRequest {
  HomeworkTick homework_tick = 1;
}

message CreateHomeworkTickResponse {
  HomeworkTick homework_tick = 1;
}

message DeleteHomeworkTickRequest {
  int32 homework_id = 1;
}

message DeleteHomeworkTickResponse {}
```

- [ ] **Step 5: Write `proto/lessons/v2/schedule.proto` in full.**
```proto
// The class's days as the widget and the calendar read them offline: one
// school year at a time, cached on the phone under an entity tag. Replaces
// v1's GET /bundle.
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "google/protobuf/timestamp.proto";
import "lessons/v2/common.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service ScheduleService {
  // One school year of the class's days. The phone computes the current
  // lesson and the countdown from this itself, so the widget keeps ticking
  // with no network. With `if_none_match` naming the window the phone holds,
  // the answer is `not_modified` and nothing else; over REST the transcoder
  // carries the field as If-None-Match and the answer as a 304 with an ETag,
  // so HTTP caches see ordinary HTTP.
  rpc GetScheduleWindow(GetScheduleWindowRequest) returns (GetScheduleWindowResponse) {
    option (google.api.http) = {get: "/v2/class/scheduleWindows/{year}"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_VIEWER;
  }
}

message GetScheduleWindowRequest {
  // The calendar year the school year opens in: 2026 asks for 2026/27.
  int32 year = 1;
  // The `etag` of the window the phone already holds.
  optional string if_none_match = 2;
}

message GetScheduleWindowResponse {
  // True when `if_none_match` still names this window; `window` is then
  // absent.
  bool not_modified = 1;
  // The window's entity tag, to send back as `if_none_match`: a hash of the
  // window without `generated_at`, which changes on every answer.
  string etag = 2;
  ScheduleWindow window = 3;
}

// v1: BundleOut. `api_version` is gone, because the package name is the
// version, and the window is a school year rather than v1's `start` and `days`.
message ScheduleWindow {
  ClassSummary school_class = 1;
  google.protobuf.Timestamp generated_at = 2;
  // Every day of the school year, in order.
  repeated ScheduleDay days = 3;
  // The first day with lessons after the last one in `days`, looked for up to
  // 21 days past it, so «homework for the next school day» resolves across a
  // holiday. Absent when there is none.
  ScheduleDay next_school_day = 4;
  DeviceAccess device = 5;
}

// The class as the widget needs it. v1: ClassOut.
message ClassSummary {
  int32 id = 1;
  string name = 2;
  // The year of school, 1 to 11, and the letter that tells two classes of a
  // year apart. Absent on a class made before they existed: its name is all
  // there is.
  optional int32 grade = 3;
  optional string letter = 4;
  optional string school = 5;
  optional string city = 6;
  // The class's IANA zone: what «today» means for it.
  string timezone = 7;
  TermKind term_kind = 8;
  // The terms as the class runs them, so the phone renders «2 четверть» from
  // them rather than from dates a school is free to have moved.
  repeated Term terms = 9;
}

// What this device may do. Never carries the Telegram id it is linked to.
// v1: DeviceOut.
message DeviceAccess {
  bool linked = 1;
  // ROLE_UNSPECIFIED for an unlinked device.
  Role role = 2;
  // Editor or above, so the phone shows its editing controls.
  bool can_edit = 3;
}

// One date of the window. v1: DayOut, renamed because DayService's resource
// is `Day`.
message ScheduleDay {
  // "YYYY-MM-DD".
  string date = 1;
  // 1 is Monday, 7 is Sunday.
  int32 weekday = 2;
  DayKind kind = 3;
  repeated Lesson lessons = 4;
  repeated ScheduleEvent events = 5;
  repeated ScheduleHomework homework = 6;
  optional string note = 7;
  // What this date is called, if it is called anything.
  Holiday holiday = 8;
  // Why there are no lessons when the timetable was not what decided it;
  // DAY_OFF_REASON_UNSPECIFIED on an ordinary day, empty or not.
  DayOffReason off_reason = 9;
}

// v1: DayOut.off_reason's three strings.
enum DayOffReason {
  DAY_OFF_REASON_UNSPECIFIED = 0;
  // Before the year opened or after it closed: the summer, mostly.
  DAY_OFF_REASON_OUT_OF_YEAR = 1;
  // Inside the year, but in none of its terms: the holidays between them.
  DAY_OFF_REASON_BETWEEN_TERMS = 2;
  // A statutory non-working day.
  DAY_OFF_REASON_PUBLIC_HOLIDAY = 3;
}

// A named date, whether or not it stops the lessons. v1: HolidayOut.
message Holiday {
  // Stable: a client matches it to name the date in its own language, and
  // falls back to `title` for a code it does not know.
  string code = 1;
  // Russian, as every string the server puts on a screen.
  string title = 2;
  // True only for a statutory non-working day; «День учителя» is a full
  // Wednesday.
  bool stops_lessons = 3;
}

// v1: LessonOut.
message Lesson {
  // The lesson number; its times are the bell of the same number.
  int32 index = 1;
  string subject = 2;
  // "HH:MM".
  string starts_at = 3;
  // "HH:MM".
  string ends_at = 4;
  optional string room = 5;
  optional string teacher = 6;
  // The subject's colour, "#RRGGBB".
  optional string color = 7;
  bool is_replaced = 8;
  bool is_cancelled = 9;
  optional string note = 10;
}

// An event on a day. v1: EventOut, renamed because EventService's resource
// is `Event`.
message ScheduleEvent {
  string title = 1;
  EventKind kind = 2;
  // "HH:MM".
  string starts_at = 3;
  // "HH:MM".
  string ends_at = 4;
  optional string location = 5;
  // Whether the event stands in for the lessons it overlaps.
  bool covers_lesson = 6;
}

// Homework due on a day. v1: HomeworkOut, renamed because HomeworkService's
// resource is `Homework`.
message ScheduleHomework {
  string subject = 1;
  string text = 2;
  optional string attachment_url = 3;
}
```

- [ ] **Step 6: Lint, generate, check the tree.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe lint && echo LINT-CLEAN && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe generate && git status --short --untracked-files=all server/app/contract buf.lock
```
Expected: `LINT-CLEAN`, then new files `buf.lock`, `google/__init__.py`, `google/api/__init__.py`, `google/api/annotations_pb.py`, `google/api/http_pb.py`, and `lessons/v2/{device,me,schedule}_{pb,connect}.py`.

- [ ] **Step 7: Green.** `python -m pytest -q -p no:xdist tests/test_contract.py` → `25 passed`.

- [ ] **Step 8: Gates and full suite.** ruff → `All checks passed!`; mypy → `Success: no issues found in 197 source files`; the full suite → previous count + 1, no failures.

- [ ] **Step 9: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && git add buf.yaml buf.lock proto server/app/contract server/tests/test_contract.py && git commit -F - <<'EOF'
Write the phone's own services into the contract: joining, itself, and its year

DeviceService exchanges a code for the device token, as POST /join did.
MeService is everything a phone does for itself: who it is (a read that no
longer mints a link code), unlinking, a link code minted by its own method,
the class's calendar feed (read without minting; minted by its own method),
and the linked account's tasks and homework ticks as resources. The task's
«done» is a field set by UpdateTask rather than its own endpoint.
ScheduleService is v1's bundle as one school year, with an entity tag
carried as a field so that Connect and gRPC get the same cache as REST's
If-None-Match.

The googleapis dependency arrives with the first google.api.http
annotation and is pinned in buf.lock. tests/test_contract.py now pins these
fourteen methods' routes, credentials and roles, and proves its own readers
can see an option, a binding and a path field, not only their absence.

Not covered: RotateCalendarFeed keeps v1's mint-when-absent behaviour under
the design's name; whether it should really replace a live secret is the
owner's question, recorded in the plan.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 3: ClassService (with terms), SubjectService, BellService, TimetableService

**Files:**
- Create: `proto/lessons/v2/school_class.proto`, `subject.proto`, `bell.proto`, `timetable.proto`, and their generated modules.
- Modify: `server/tests/test_contract.py`.

**Interfaces:**
- Consumes: `Role` (`options.proto`); `TermKind` and `Term` (`common.proto`).
- Produces: `SchoolClass`, `JoinMode`, `ClassStats`, `SubjectHours`, `RoleCount`, `TermScheme`, `Subject`, `BellSchedule`, `BellPeriod`, `Timetable`, `ImportConflict`.

- [ ] **Step 1: Red.**
  - `FILES |=` `"bell", "school_class", "subject", "timetable"`; write the set literal out in full in sorted order.
  - `SERVICE_FILES` likewise gains the same four.
  - Insert into `METHODS` before its closing brace:
```python
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
```
  - Run `test_contract.py` → `test_every_proto_file_was_generated` and `test_the_methods_are_the_resource_map` fail.

- [ ] **Step 2: Write `proto/lessons/v2/school_class.proto` in full.**
```proto
// The class itself, as «⚙️ Класс» runs it: its card and settings, deleting
// it, its numbers, and how its year is cut into terms. Replaces v1's
// /manage/class, /manage/stats and /manage/terms….
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "google/protobuf/field_mask.proto";
import "lessons/v2/common.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service ClassService {
  // What «⚙️ Класс» shows, as data: the join code included, which is why it
  // is an admin's.
  rpc GetClass(GetClassRequest) returns (GetClassResponse) {
    option (google.api.http) = {get: "/v2/class"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  // Renames the class, re-homes it, moves its zone or changes who may join,
  // one audit line per field. Moving the grade or the letter recomposes the
  // name unless the same request names it. `update_mask` takes name, grade,
  // letter, school, city, timezone and join_mode; a path whose field is
  // absent clears it, except name and timezone, which cannot be cleared.
  // Changing the zone moves no stored time: a bell rings at 08:30 whatever
  // the zone says.
  rpc UpdateClass(UpdateClassRequest) returns (UpdateClassResponse) {
    option (google.api.http) = {
      patch: "/v2/class"
      body: "school_class"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  // Deletes the class and everything hanging off it, every device token
  // included: the caller's own next request is DEVICE_TOKEN_INVALID.
  // `confirmation` is a query field, because a DELETE carries no body.
  rpc DeleteClass(DeleteClassRequest) returns (DeleteClassResponse) {
    option (google.api.http) = {delete: "/v2/class"};
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_OWNER;
  }
  // The numbers «📊 Статистика» shows. An editor may read them, as in the bot:
  // they say whether the timetable is complete and homework is being entered.
  rpc GetClassStats(GetClassStatsRequest) returns (GetClassStatsResponse) {
    option (google.api.http) = {get: "/v2/class/stats"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
  // Quarters or half-years, and the school year they are for. Never seeds:
  // v1's GET /manage/terms wrote the conventional terms on read.
  rpc GetTermScheme(GetTermSchemeRequest) returns (GetTermSchemeResponse) {
    option (google.api.http) = {get: "/v2/class/termScheme"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  // Switches between quarters and half-years and reseeds the year: four
  // quarters and two halves do not map onto each other.
  rpc UpdateTermScheme(UpdateTermSchemeRequest) returns (UpdateTermSchemeResponse) {
    option (google.api.http) = {
      patch: "/v2/class/termScheme"
      body: "term_scheme"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  // This school year's terms: the conventional ones when the class has none
  // stored, without storing them.
  rpc ListTerms(ListTermsRequest) returns (ListTermsResponse) {
    option (google.api.http) = {get: "/v2/class/terms"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  // Moves one term's edges. TERM_BOUNDS_REFUSED, with the service's own
  // sentence, when the year cannot hold them.
  rpc UpdateTerm(UpdateTermRequest) returns (UpdateTermResponse) {
    option (google.api.http) = {
      patch: "/v2/class/terms/{term.index}"
      body: "term"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
}

// Who vouches for a phone. v1: "open" | "invite".
enum JoinMode {
  JOIN_MODE_UNSPECIFIED = 0;
  // The class code admits whoever types it.
  JOIN_MODE_OPEN = 1;
  // Only a personal code from the bot admits a phone. Switching revokes no
  // device, in either direction.
  JOIN_MODE_INVITE = 2;
}

// The class card. v1: ManagedClassOut, with ClassPatch's grade and letter.
message SchoolClass {
  // Output only.
  int32 id = 1;
  // 1 to 64 characters: what every message calls the class, and what
  // DeleteClass is confirmed against.
  string name = 2;
  // 1 to 11.
  optional int32 grade = 3;
  // Up to 8 characters; «-» means none, as the bot reads it.
  optional string letter = 4;
  // Up to 200 characters.
  optional string school = 5;
  // Up to 120 characters.
  optional string city = 6;
  // An IANA zone this deployment offers.
  string timezone = 7;
  // «МСК+2 (UTC+5) · Екатеринбург», the label the bot prints. Output only.
  string timezone_label = 8;
  // The class code an admin reads out to the class. Output only.
  string join_code = 9;
  JoinMode join_mode = 10;
  // Output only, as are the next five.
  int32 members = 11;
  int32 devices = 12;
  int32 pending_requests = 13;
  // The default bell schedule; BellService's is_default changes it.
  optional int32 bell_schedule_id = 14;
  // Whether the class has a calendar feed secret yet.
  bool calendar_ready = 15;
}

// v1: StatsOut.
message ClassStats {
  // "YYYY-MM-DD", the class's today.
  string today = 1;
  // A subject that alternates weeks counts a half, as a school's own papers
  // write «часов в неделю».
  double lessons_per_week = 2;
  int32 subjects_count = 3;
  repeated SubjectHours subjects = 4;
  int32 homework_open = 5;
  int32 homework_total = 6;
  // v1 sent a dictionary keyed by role; a map key cannot be an enum.
  repeated RoleCount members_by_role = 7;
  int32 devices_active = 8;
  // v1: overrides_upcoming. «Overrides» meant substitutions here and
  // corrections in the diary; v2 calls each by its own name.
  int32 substitutions_upcoming = 9;
  int32 events_upcoming = 10;
}

// v1: SubjectHoursOut.
message SubjectHours {
  string name = 1;
  double hours = 2;
}

message RoleCount {
  Role role = 1;
  int32 count = 2;
}

// v1: TermsOut's kind and year.
message TermScheme {
  TermKind kind = 1;
  // The calendar year the school year opens in. Output only.
  int32 year = 2;
}

message GetClassRequest {}

message GetClassResponse {
  SchoolClass school_class = 1;
}

message UpdateClassRequest {
  SchoolClass school_class = 1;
  google.protobuf.FieldMask update_mask = 2;
}

message UpdateClassResponse {
  SchoolClass school_class = 1;
}

message DeleteClassRequest {
  // The class's name, typed back, as the bot makes an owner type it: a
  // confirmation button is pressed by the same thumb that pressed the one
  // before it, while a name has to be read off the screen first.
  // v1: ClassDeleteIn.confirm_name.
  string confirmation = 1;
}

message DeleteClassResponse {}

message GetClassStatsRequest {}

message GetClassStatsResponse {
  ClassStats stats = 1;
}

message GetTermSchemeRequest {}

message GetTermSchemeResponse {
  TermScheme term_scheme = 1;
}

message UpdateTermSchemeRequest {
  TermScheme term_scheme = 1;
}

message UpdateTermSchemeResponse {
  TermScheme term_scheme = 1;
  // The year's terms as the switch reseeded them.
  repeated Term terms = 2;
}

message ListTermsRequest {}

message ListTermsResponse {
  // The calendar year the school year opens in.
  int32 year = 1;
  repeated Term terms = 2;
}

message UpdateTermRequest {
  // `index` names the term; `starts_on` and `ends_on` are its new edges;
  // `kind` is ignored.
  Term term = 1;
}

message UpdateTermResponse {
  int32 year = 1;
  repeated Term terms = 2;
}
```

- [ ] **Step 3: Write `proto/lessons/v2/subject.proto` in full.**
```proto
// The class's subject dictionary, as «📚 Предметы» edits it. Replaces v1's
// GET /subjects (no ids) and /manage/subjects… (with ids): one resource with
// ids, readable by any phone in the class.
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "google/protobuf/field_mask.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service SubjectService {
  // The dictionary, alphabetically.
  rpc ListSubjects(ListSubjectsRequest) returns (ListSubjectsResponse) {
    option (google.api.http) = {get: "/v2/class/subjects"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_VIEWER;
  }
  rpc GetSubject(GetSubjectRequest) returns (GetSubjectResponse) {
    option (google.api.http) = {get: "/v2/class/subjects/{subject_id}"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_VIEWER;
  }
  // RESOURCE_EXISTS for a name the class already has, ignoring case: that
  // uniqueness is the whole point of the dictionary. REST answers 201.
  rpc CreateSubject(CreateSubjectRequest) returns (CreateSubjectResponse) {
    option (google.api.http) = {
      post: "/v2/class/subjects"
      body: "subject"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  // `update_mask` takes name, short_name, teacher and color. A rename carries
  // the timetable, the homework and the substitutions with it in one
  // transaction, and `moved` says how many rows. Renaming onto a taken name is
  // RESOURCE_EXISTS; onto one with homework on the same days,
  // SUBJECT_RENAME_CLASH.
  rpc UpdateSubject(UpdateSubjectRequest) returns (UpdateSubjectResponse) {
    option (google.api.http) = {
      patch: "/v2/class/subjects/{subject.id}"
      body: "subject"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  // RESOURCE_IN_USE while the weekly template still teaches it; take it out
  // of the timetable first, or the next read would adopt it again without its
  // colour or teacher.
  rpc DeleteSubject(DeleteSubjectRequest) returns (DeleteSubjectResponse) {
    option (google.api.http) = {delete: "/v2/class/subjects/{subject_id}"};
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
}

// v1: SubjectOut, ManagedSubjectOut, SubjectIn and SubjectPatch.
message Subject {
  // Assigned by the server; ignored on create.
  int32 id = 1;
  // 1 to 120 characters, one line, single-spaced.
  string name = 2;
  // Up to 16 characters.
  optional string short_name = 3;
  // Up to 120 characters.
  optional string teacher = 4;
  // "#RRGGBB" as stored; "#5b6abf" or "5B6ABF" are taken and normalised.
  optional string color = 5;
}

message ListSubjectsRequest {}

message ListSubjectsResponse {
  repeated Subject subjects = 1;
}

message GetSubjectRequest {
  int32 subject_id = 1;
}

message GetSubjectResponse {
  Subject subject = 1;
}

message CreateSubjectRequest {
  Subject subject = 1;
}

message CreateSubjectResponse {
  Subject subject = 1;
}

message UpdateSubjectRequest {
  Subject subject = 1;
  google.protobuf.FieldMask update_mask = 2;
}

message UpdateSubjectResponse {
  Subject subject = 1;
  // How many timetable, homework and substitution rows a rename moved; zero
  // for any other edit. v1: SubjectSavedOut.moved.
  int32 moved = 2;
}

message DeleteSubjectRequest {
  int32 subject_id = 1;
}

message DeleteSubjectResponse {}
```

- [ ] **Step 4: Write `proto/lessons/v2/bell.proto` in full.**
```proto
// The bell schedules a class runs on, as «🔔 Звонки» edits them. Replaces
// v1's /manage/bells… and PUT /manage/bells/{id}/periods.
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "google/protobuf/field_mask.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service BellService {
  // Every schedule the class keeps («Обычное», «Сокращённое», «Суббота»),
  // with the default marked.
  rpc ListBellSchedules(ListBellSchedulesRequest) returns (ListBellSchedulesResponse) {
    option (google.api.http) = {get: "/v2/class/bellSchedules"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  rpc GetBellSchedule(GetBellScheduleRequest) returns (GetBellScheduleResponse) {
    option (google.api.http) = {get: "/v2/class/bellSchedules/{schedule_id}"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  // A new named schedule, with its rows if they come with it. It does not
  // become the default: a shortened schedule exists to be pointed at by
  // particular days. REST answers 201.
  rpc CreateBellSchedule(CreateBellScheduleRequest) returns (CreateBellScheduleResponse) {
    option (google.api.http) = {
      post: "/v2/class/bellSchedules"
      body: "schedule"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  // `update_mask` takes name, is_default and periods. Periods are replaced
  // wholesale, because the lessons after the one that moved shift with it,
  // and must hold at least one row. is_default false is VALIDATION_FAILED:
  // make another schedule the default instead. A schedule with no rows cannot
  // become it (EMPTY_BELL_SCHEDULE).
  rpc UpdateBellSchedule(UpdateBellScheduleRequest) returns (UpdateBellScheduleResponse) {
    option (google.api.http) = {
      patch: "/v2/class/bellSchedules/{schedule.id}"
      body: "schedule"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  // RESOURCE_IN_USE for the class default and for a schedule special days
  // point at: deleting either would silently move those days onto another.
  rpc DeleteBellSchedule(DeleteBellScheduleRequest) returns (DeleteBellScheduleResponse) {
    option (google.api.http) = {delete: "/v2/class/bellSchedules/{schedule_id}"};
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
}

// v1: BellScheduleOut, BellScheduleIn and BellSchedulePatch.
message BellSchedule {
  // Assigned by the server; ignored on create.
  int32 id = 1;
  // 1 to 64 characters.
  string name = 2;
  // The one the class runs on when no day says otherwise.
  bool is_default = 3;
  // At most 20, numbers unique, ordered by number.
  repeated BellPeriod periods = 4;
}

// v1: BellPeriodIn and BellPeriodOut.
message BellPeriod {
  // The lesson number, 1 to 20.
  int32 index = 1;
  // "HH:MM".
  string starts_at = 2;
  // "HH:MM", after starts_at.
  string ends_at = 3;
}

message ListBellSchedulesRequest {}

message ListBellSchedulesResponse {
  repeated BellSchedule schedules = 1;
}

message GetBellScheduleRequest {
  int32 schedule_id = 1;
}

message GetBellScheduleResponse {
  BellSchedule schedule = 1;
}

message CreateBellScheduleRequest {
  BellSchedule schedule = 1;
}

message CreateBellScheduleResponse {
  BellSchedule schedule = 1;
}

message UpdateBellScheduleRequest {
  BellSchedule schedule = 1;
  google.protobuf.FieldMask update_mask = 2;
}

message UpdateBellScheduleResponse {
  BellSchedule schedule = 1;
  // Lessons this write stopped ringing, counted in rows: one number under two
  // weekdays is two lessons nobody will see. Zero on almost every write.
  // v1: BellScheduleOut.silenced_lessons, a fact of the write rather than of
  // the schedule.
  int32 silenced_lessons = 2;
}

message DeleteBellScheduleRequest {
  int32 schedule_id = 1;
}

message DeleteBellScheduleResponse {}
```

- [ ] **Step 5: Write `proto/lessons/v2/timetable.proto` in full.**
```proto
// The weekly template as text, out and back in: «📤 Экспорт» and «📥 Импорт».
// Replaces v1's /manage/timetable and /manage/timetable/import.
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service TimetableService {
  // The weekly template in the bot's paste format, byte for byte what
  // «📤 Экспорт» sends, so a text saved from either goes back in through the
  // other. An empty timetable is an empty text, not NOT_FOUND.
  rpc GetTimetable(GetTimetableRequest) returns (GetTimetableResponse) {
    option (google.api.http) = {get: "/v2/class/timetable"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  // Parses a paste and replaces exactly the weekdays it names; a day named
  // with nothing under it is emptied, and a «== Звонки ==» block replaces the
  // default schedule's rows. With `validate_only` nothing is written and the
  // answer is the preview the bot shows before «Применить» (AIP-163). Without
  // it, a paste that would overwrite a weekday that has lessons writes
  // nothing either and lists the conflicts, unless `replace` is set: the
  // second tap. A paste with no weekday header and no bells block is
  // VALIDATION_FAILED.
  rpc ImportTimetable(ImportTimetableRequest) returns (ImportTimetableResponse) {
    option (google.api.http) = {
      post: "/v2/class/timetable:import"
      body: "*"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
}

// v1: TimetableExportOut.
message Timetable {
  string text = 1;
  int32 lessons = 2;
}

// One weekday the paste would overwrite. v1: ImportConflictOut.
message ImportConflict {
  // 1 is Monday, 7 is Sunday.
  int32 weekday = 1;
  int32 existing = 2;
  int32 incoming = 3;
}

message GetTimetableRequest {}

message GetTimetableResponse {
  Timetable timetable = 1;
}

// v1: TimetableImportIn, with the preview made explicit.
message ImportTimetableRequest {
  // 1 to 20000 characters.
  string text = 1;
  // Overwrite weekdays that already have lessons.
  bool replace = 2;
  // Parse and answer, write nothing.
  bool validate_only = 3;
}

// v1: TimetableImportOut.
message ImportTimetableResponse {
  // False when nothing was written.
  bool applied = 1;
  // The weekdays the paste names, 1 to 7.
  repeated int32 days = 2;
  int32 lessons = 3;
  int32 bells = 4;
  repeated ImportConflict conflicts = 5;
  // Lines the parser could not read, then one line per lesson dropped for
  // having no bell and per stored lesson the new bells no longer ring: an
  // admin fixes the typos rather than re-reading the whole paste.
  repeated string rejected = 6;
}
```

- [ ] **Step 6: Lint, generate, green, gates, full suite.**
  - From the worktree root: `buf lint` → `LINT-CLEAN`; `buf generate`.
  - `git status --short --untracked-files=all server/app/contract` shows `{school_class,subject,bell,timetable}_{pb,connect}.py`.
  - `test_contract.py` → `25 passed`; ruff and mypy clean; the full suite → same count as Task 2.

- [ ] **Step 7: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && git add proto server/app/contract server/tests/test_contract.py && git commit -F - <<'EOF'
Write the running of the class into the contract: the class, its terms, subjects, bells and timetable

ClassService is «⚙️ Класс»: the card and its field-masked edits, deletion
with the name typed back as a query field (a DELETE carries no body), the
statistics, and the term scheme and terms, whose reads never seed the year
as v1's did. SubjectService is one dictionary with ids, readable by any
phone, where v1 had two lists. BellService folds v1's separate «replace the
periods» endpoint into UpdateBellSchedule's mask, and moves the count of
silenced lessons from the schedule to the answer of the write that silenced
them. TimetableService keeps the bot's paste format and makes its preview an
explicit validate_only.

Each method's least role is the one v1 and the bot ask: admin for the
card, the terms, the bells and the timetable, owner to delete, editor for
the statistics, any viewer to read subjects. tests/test_contract.py pins all
twenty rows.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 4: HomeworkService, SubstitutionService, EventService, DayService

**Files:**
- Create: `proto/lessons/v2/homework.proto`, `substitution.proto`, `event.proto`, `day.proto`, and their generated modules.
- Modify: `server/tests/test_contract.py`.

**Interfaces:**
- Consumes: `Role`; `EventKind` and `DayKind` from `common.proto`.
- Produces: `Homework`, `Substitution`, `SubstitutionAction`, `Event`, `Day`.

- [ ] **Step 1: Red.**
  - `FILES` and `SERVICE_FILES` gain `"day"`, `"event"`, `"homework"`, `"substitution"`.
  - Insert into `METHODS`:
```python
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
```
  - Run → the two map tests fail.

- [ ] **Step 2: Write `proto/lessons/v2/homework.proto` in full.**
```proto
// The class's homework, written by an editor and read by every phone in the
// class. Replaces v1's PUT /homework, DELETE /homework/{id} and GET /homework.
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "google/protobuf/field_mask.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service HomeworkService {
  // Homework due in a window, each row with this device's owner's tick. The
  // window is today and 21 days on when unset, and 62 days at most.
  rpc ListHomework(ListHomeworkRequest) returns (ListHomeworkResponse) {
    option (google.api.http) = {get: "/v2/class/homework"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_VIEWER;
  }
  rpc GetHomework(GetHomeworkRequest) returns (GetHomeworkResponse) {
    option (google.api.http) = {get: "/v2/class/homework/{homework_id}"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_VIEWER;
  }
  // One assignment per subject per day: a second for the same pair is
  // RESOURCE_EXISTS, and UpdateHomework changes its text. v1's PUT /homework
  // upserted by that pair instead. Announced to the class's subscribers.
  // REST answers 201.
  rpc CreateHomework(CreateHomeworkRequest) returns (CreateHomeworkResponse) {
    option (google.api.http) = {
      post: "/v2/class/homework"
      body: "homework"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
  // `update_mask` takes due_date, subject, text and attachment_url; a tick is
  // MeService's, not this.
  rpc UpdateHomework(UpdateHomeworkRequest) returns (UpdateHomeworkResponse) {
    option (google.api.http) = {
      patch: "/v2/class/homework/{homework.id}"
      body: "homework"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
  rpc DeleteHomework(DeleteHomeworkRequest) returns (DeleteHomeworkResponse) {
    option (google.api.http) = {delete: "/v2/class/homework/{homework_id}"};
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
}

// v1: HomeworkItemOut and HomeworkIn.
message Homework {
  // Assigned by the server; ignored on create.
  int32 id = 1;
  // "YYYY-MM-DD".
  string due_date = 2;
  // 1 to 120 characters; the class's spelling of the subject.
  string subject = 3;
  // 1 to 4000 characters; line breaks kept («№ 12–15\nустно § 4»).
  string text = 4;
  // Up to 500 characters.
  optional string attachment_url = 5;
  // This device's owner's tick; always false for an unlinked device. Output
  // only.
  bool done = 6;
}

message ListHomeworkRequest {
  // "YYYY-MM-DD"; today when absent. v1: the query field `from`.
  optional string start_date = 1;
  // "YYYY-MM-DD"; 21 days after start_date when absent. v1: `to`.
  optional string end_date = 2;
}

message ListHomeworkResponse {
  repeated Homework homework = 1;
}

message GetHomeworkRequest {
  int32 homework_id = 1;
}

message GetHomeworkResponse {
  Homework homework = 1;
}

message CreateHomeworkRequest {
  Homework homework = 1;
}

message CreateHomeworkResponse {
  Homework homework = 1;
}

message UpdateHomeworkRequest {
  Homework homework = 1;
  google.protobuf.FieldMask update_mask = 2;
}

message UpdateHomeworkResponse {
  Homework homework = 1;
}

message DeleteHomeworkRequest {
  int32 homework_id = 1;
}

message DeleteHomeworkResponse {}
```

- [ ] **Step 3: Write `proto/lessons/v2/substitution.proto` in full.**
```proto
// Substitutions: one lesson on one date replaced or cancelled. Replaces v1's
// PUT /overrides; «overrides» is the diary's word for corrections, so v2 calls
// these by their own name.
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "google/protobuf/field_mask.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service SubstitutionService {
  // Substitutions on dates in a window: today and 21 days on when unset, 62
  // days at most.
  rpc ListSubstitutions(ListSubstitutionsRequest) returns (ListSubstitutionsResponse) {
    option (google.api.http) = {get: "/v2/class/substitutions"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
  rpc GetSubstitution(GetSubstitutionRequest) returns (GetSubstitutionResponse) {
    option (google.api.http) = {get: "/v2/class/substitutions/{substitution_id}"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
  // One per date and lesson number: a second is RESOURCE_EXISTS. Refused
  // with:
  // - NO_LESSON_ON_DAY on a day that draws no lessons;
  // - NO_BELL_FOR_LESSON at a number the day rings no bell for;
  // - LESSON_NOT_ON_TIMETABLE when cancelling, or replacing without a
  //   subject, a lesson the day's template does not have.
  // Otherwise the write would be stored, announced to every subscriber, and
  // drawn nowhere. REST answers 201.
  rpc CreateSubstitution(CreateSubstitutionRequest) returns (CreateSubstitutionResponse) {
    option (google.api.http) = {
      post: "/v2/class/substitutions"
      body: "substitution"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
  // `update_mask` takes action, subject, room, teacher and note; the date and
  // the number are the row. A row at a number that no longer rings stays
  // editable, which is how a class gets out of one.
  rpc UpdateSubstitution(UpdateSubstitutionRequest) returns (UpdateSubstitutionResponse) {
    option (google.api.http) = {
      patch: "/v2/class/substitutions/{substitution.id}"
      body: "substitution"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
  // The lesson goes back to the timetable. v1: PUT /overrides with "clear".
  rpc DeleteSubstitution(DeleteSubstitutionRequest) returns (DeleteSubstitutionResponse) {
    option (google.api.http) = {delete: "/v2/class/substitutions/{substitution_id}"};
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
}

// v1: OverrideActionName without "clear", which is DeleteSubstitution. The
// model's ADD is not here because nothing writes it; adding it later is safe.
enum SubstitutionAction {
  SUBSTITUTION_ACTION_UNSPECIFIED = 0;
  // Another subject, room or teacher in this lesson; at least one of the
  // three is set. At a number the template leaves empty, this adds a lesson,
  // and then it needs a subject.
  SUBSTITUTION_ACTION_REPLACE = 1;
  // The lesson does not happen.
  SUBSTITUTION_ACTION_CANCEL = 2;
}

// v1: OverrideIn and OverrideOut, now with an id.
message Substitution {
  // Assigned by the server; ignored on create.
  int32 id = 1;
  // "YYYY-MM-DD".
  string date = 2;
  // The lesson number, 1 to 20.
  int32 index = 3;
  SubstitutionAction action = 4;
  // Up to 120 characters; stored in the class's own spelling of the subject.
  optional string subject = 5;
  // Up to 32 characters.
  optional string room = 6;
  // Up to 120 characters.
  optional string teacher = 7;
  // Up to 500 characters.
  optional string note = 8;
}

message ListSubstitutionsRequest {
  // "YYYY-MM-DD"; today when absent.
  optional string start_date = 1;
  // "YYYY-MM-DD"; 21 days after start_date when absent.
  optional string end_date = 2;
}

message ListSubstitutionsResponse {
  repeated Substitution substitutions = 1;
}

message GetSubstitutionRequest {
  int32 substitution_id = 1;
}

message GetSubstitutionResponse {
  Substitution substitution = 1;
}

message CreateSubstitutionRequest {
  Substitution substitution = 1;
}

message CreateSubstitutionResponse {
  Substitution substitution = 1;
}

message UpdateSubstitutionRequest {
  Substitution substitution = 1;
  google.protobuf.FieldMask update_mask = 2;
}

message UpdateSubstitutionResponse {
  Substitution substitution = 1;
}

message DeleteSubstitutionRequest {
  int32 substitution_id = 1;
}

message DeleteSubstitutionResponse {}
```

- [ ] **Step 4: Write `proto/lessons/v2/event.proto` in full.**
```proto
// The class's events: a trip, an exam, a canteen break. Replaces v1's
// PUT /events, which always inserted (#268), and DELETE /events/{id}.
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "google/protobuf/field_mask.proto";
import "lessons/v2/common.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service EventService {
  // Events on dates in a window: today and 21 days on when unset, 62 days at
  // most.
  rpc ListEvents(ListEventsRequest) returns (ListEventsResponse) {
    option (google.api.http) = {get: "/v2/class/events"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
  rpc GetEvent(GetEventRequest) returns (GetEventResponse) {
    option (google.api.http) = {get: "/v2/class/events/{event_id}"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
  // A POST, so a retried create can be told apart from a second event. Events
  // have no natural key: two «Обед» on one day are two breaks. Announced to
  // the class's subscribers. REST answers 201.
  rpc CreateEvent(CreateEventRequest) returns (CreateEventResponse) {
    option (google.api.http) = {
      post: "/v2/class/events"
      body: "event"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
  // `update_mask` takes date, starts_at, ends_at, title, kind, location and
  // covers_lesson.
  rpc UpdateEvent(UpdateEventRequest) returns (UpdateEventResponse) {
    option (google.api.http) = {
      patch: "/v2/class/events/{event.id}"
      body: "event"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
  rpc DeleteEvent(DeleteEventRequest) returns (DeleteEventResponse) {
    option (google.api.http) = {delete: "/v2/class/events/{event_id}"};
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
}

// v1: EventIn and EventCreatedOut.
message Event {
  // Assigned by the server; ignored on create.
  int32 id = 1;
  // "YYYY-MM-DD".
  string date = 2;
  // "HH:MM".
  string starts_at = 3;
  // "HH:MM", after starts_at.
  string ends_at = 4;
  // 1 to 200 characters, one line.
  string title = 5;
  // EVENT_KIND_EVENT when unset, as v1 defaulted.
  EventKind kind = 6;
  // Up to 120 characters.
  optional string location = 7;
  // Whether it stands in for the lessons it overlaps. Absent on create means
  // what the bot means: yes for an event or a trip, no otherwise.
  optional bool covers_lesson = 8;
}

message ListEventsRequest {
  // "YYYY-MM-DD"; today when absent.
  optional string start_date = 1;
  // "YYYY-MM-DD"; 21 days after start_date when absent.
  optional string end_date = 2;
}

message ListEventsResponse {
  repeated Event events = 1;
}

message GetEventRequest {
  int32 event_id = 1;
}

message GetEventResponse {
  Event event = 1;
}

message CreateEventRequest {
  Event event = 1;
}

message CreateEventResponse {
  Event event = 1;
}

message UpdateEventRequest {
  Event event = 1;
  google.protobuf.FieldMask update_mask = 2;
}

message UpdateEventResponse {
  Event event = 1;
}

message DeleteEventRequest {
  int32 event_id = 1;
}

message DeleteEventResponse {}
```

- [ ] **Step 5: Write `proto/lessons/v2/day.proto` in full.**
```proto
// How a whole date departs from the weekly rhythm: a holiday, a shortened
// day, remote teaching. Replaces v1's PUT /days.
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "google/protobuf/field_mask.proto";
import "lessons/v2/common.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service DayService {
  // A date nobody marked is DAY_KIND_NORMAL, not NOT_FOUND: every date has a
  // kind.
  rpc GetDay(GetDayRequest) returns (GetDayResponse) {
    option (google.api.http) = {get: "/v2/class/days/{date}"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
  // Marks a date, or takes the mark off with DAY_KIND_NORMAL: a day never
  // carries a row that says nothing. With `allow_missing`, a date with no
  // mark is created, which makes this an idempotent upsert by date.
  // `update_mask` takes kind, note and bell_schedule_id.
  //
  // As v1's PUT /days did, it takes NORMAL, HOLIDAY, SHORTENED and REMOTE;
  // SELF_STUDY and DAY_OFF are set from the bot and only read here. A
  // shortened day names the bell schedule it rings, and that schedule must
  // ring something (EMPTY_BELL_SCHEDULE).
  rpc UpdateDay(UpdateDayRequest) returns (UpdateDayResponse) {
    option (google.api.http) = {
      patch: "/v2/class/days/{day.date}"
      body: "day"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_EDITOR;
  }
}

// v1: DayIn and DayOverrideOut.
message Day {
  // "YYYY-MM-DD".
  string date = 1;
  DayKind kind = 2;
  // Up to 500 characters.
  optional string note = 3;
  // The bell schedule the day rings; required for a shortened day.
  optional int32 bell_schedule_id = 4;
}

message GetDayRequest {
  // "YYYY-MM-DD".
  string date = 1;
}

message GetDayResponse {
  Day day = 1;
}

message UpdateDayRequest {
  Day day = 1;
  google.protobuf.FieldMask update_mask = 2;
  // Create the mark when the date has none (AIP-134).
  bool allow_missing = 3;
}

message UpdateDayResponse {
  Day day = 1;
}
```

- [ ] **Step 6: Lint, generate, green (`25 passed`), gates, full suite.** As Task 3 Step 6.

- [ ] **Step 7: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && git add proto server/app/contract server/tests/test_contract.py && git commit -F - <<'EOF'
Write the day-to-day writes into the contract: homework, substitutions, events and marked days

The four things an editor writes become resources with ids and the standard
five methods. v1 wrote each through a PUT that meant something different
every time: homework upserted by subject and day, a substitution keyed by
date and number with «clear» as a delete, an event inserted on every call
(#268), a day upserted and deleted by kind. Now a create is a POST that
refuses a duplicate of a natural key, an update is a PATCH with a mask, a
delete is a DELETE, and a day's upsert is the explicit allow_missing. Every
refusal v1 gives for a substitution nobody could see keeps a reason of its
own.

Homework stays readable by any viewer, as v1's GET /homework was; the rest
needs an editor, as v1's /edit did.

Not covered: the 21-day default and 62-day ceiling for listing
substitutions and events are new (v1 could not list them) and copy
homework's.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 5: ClassDeviceService, AccessRequestService, AuditService, DirectoryService

**Files:**
- Create: `proto/lessons/v2/class_device.proto`, `access_request.proto`, `audit.proto`, `directory.proto`, and their generated modules.
- Modify: `server/tests/test_contract.py`.

**Interfaces:**
- Consumes: `Role`.
- Produces: `ClassDevice`, `AccessRequest`, `AuditEntry`, `SchoolRegion`, `School`.

- [ ] **Step 1: Red.**
  - `FILES` and `SERVICE_FILES` gain `"access_request"`, `"audit"`, `"class_device"`, `"directory"`.
  - Insert into `METHODS`:
```python
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
```
  - Run → red.

- [ ] **Step 2: Write `proto/lessons/v2/class_device.proto` in full.**
```proto
// The phones on the class's list, as «📱 Устройства» shows them. Replaces
// v1's /manage/devices….
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "google/protobuf/timestamp.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service ClassDeviceService {
  // The phones on the class's list, oldest first. A role is looked up, not
  // stored, so revoking somebody in the bot has already changed this list.
  rpc ListClassDevices(ListClassDevicesRequest) returns (ListClassDevicesResponse) {
    option (google.api.http) = {get: "/v2/class/devices"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  // Switches a phone off for good: revoked, not deleted, so its token goes on
  // failing. An admin may do it to the phone in their hand; that is how a lost
  // phone is dealt with from the one still in a pocket. Revoking twice changes
  // nothing and logs nothing.
  rpc RevokeClassDevice(RevokeClassDeviceRequest) returns (RevokeClassDeviceResponse) {
    option (google.api.http) = {
      post: "/v2/class/devices/{device_id}:revoke"
      body: "*"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  // Back to read-only, keeping the phone in the class. CLASS_DEVICE_NOT_LINKED
  // for a phone with nothing to unlink: the admin expected something to
  // change.
  rpc UnlinkClassDevice(UnlinkClassDeviceRequest) returns (UnlinkClassDeviceResponse) {
    option (google.api.http) = {
      post: "/v2/class/devices/{device_id}:unlink"
      body: "*"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
}

// One phone on the list. v1: ManagedDeviceOut.
message ClassDevice {
  int32 id = 1;
  optional string device_name = 2;
  bool linked = 3;
  // Whose phone it is, as a display name: never the Telegram id.
  optional string owner = 4;
  // The owner's role now; ROLE_UNSPECIFIED when unlinked.
  Role role = 5;
  bool revoked = 6;
  google.protobuf.Timestamp created_at = 7;
  // Recorded at most every fifteen minutes.
  google.protobuf.Timestamp last_seen_at = 8;
  google.protobuf.Timestamp linked_at = 9;
}

message ListClassDevicesRequest {
  // Bring back the phones that were switched off, which the bot's page leaves
  // out.
  bool include_revoked = 1;
}

message ListClassDevicesResponse {
  repeated ClassDevice devices = 1;
}

message RevokeClassDeviceRequest {
  int32 device_id = 1;
}

message RevokeClassDeviceResponse {
  ClassDevice device = 1;
}

message UnlinkClassDeviceRequest {
  int32 device_id = 1;
}

message UnlinkClassDeviceResponse {
  ClassDevice device = 1;
}
```

- [ ] **Step 3: Write `proto/lessons/v2/access_request.proto` in full.**
```proto
// A member asking the admins for a higher role («Хочу редактировать»): not a
// request to join, which is CreateDevice. Replaces v1's /manage/requests….
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "google/protobuf/timestamp.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service AccessRequestService {
  // Everybody waiting for a role, oldest first.
  rpc ListAccessRequests(ListAccessRequestsRequest) returns (ListAccessRequestsResponse) {
    option (google.api.http) = {get: "/v2/class/accessRequests"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  // Grants the role through the same rules as «👥 Доступ» in the bot, and
  // tells the requester in Telegram, where they asked. ROLE_GRANT_REFUSED
  // when the ladder does not allow it. RESOURCE_NOT_FOUND for a request
  // already answered, so two admins cannot grant twice.
  rpc ApproveAccessRequest(ApproveAccessRequestRequest) returns (ApproveAccessRequestResponse) {
    option (google.api.http) = {
      post: "/v2/class/accessRequests/{request_id}:approve"
      body: "*"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
  // Says no, and tells the requester; they keep whatever role they had.
  rpc DeclineAccessRequest(DeclineAccessRequestRequest) returns (DeclineAccessRequestResponse) {
    option (google.api.http) = {
      post: "/v2/class/accessRequests/{request_id}:decline"
      body: "*"
    };
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
}

// v1: AccessRequestOut.
message AccessRequest {
  int32 id = 1;
  // A display name, never the Telegram id.
  string who = 2;
  Role requested_role = 3;
  optional string message = 4;
  google.protobuf.Timestamp created_at = 5;
}

message ListAccessRequestsRequest {}

message ListAccessRequestsResponse {
  repeated AccessRequest access_requests = 1;
}

message ApproveAccessRequestRequest {
  int32 request_id = 1;
  // The role to grant; ROLE_UNSPECIFIED grants the one asked for, which is
  // what pressing «Выдать» in the bot does. v1: RequestDecisionIn.role.
  Role role = 2;
}

// v1: RequestDecisionOut, whose `status` is the method's name now.
message ApproveAccessRequestResponse {
  int32 request_id = 1;
  // The role actually granted: an existing member is never lowered.
  Role role = 2;
  string who = 3;
}

message DeclineAccessRequestRequest {
  int32 request_id = 1;
}

message DeclineAccessRequestResponse {
  int32 request_id = 1;
  string who = 2;
}
```

- [ ] **Step 4: Write `proto/lessons/v2/audit.proto` in full.**
```proto
// «📜 Журнал»: who changed what in the class. Replaces v1's GET /manage/log.
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "google/protobuf/timestamp.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service AuditService {
  // Newest first, `page_size` lines at a time: 30 when unset, 100 at most.
  // `next_page_token` is empty on the last page. There is no total, because
  // the log only grows and counting it would scan it on every page turn.
  rpc ListAuditEntries(ListAuditEntriesRequest) returns (ListAuditEntriesResponse) {
    option (google.api.http) = {get: "/v2/class/auditEntries"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
}

// v1: AuditEntryOut.
message AuditEntry {
  int32 id = 1;
  // A machine tag: homework.add, subject.rename, timetable.import, …
  string action = 2;
  // The line as the bot prints it, in Russian.
  string summary = 3;
  // A display name; absent when nobody is known.
  optional string who = 4;
  // An instant. v1 sent it as the class's wall time; a client converts with
  // the class's zone.
  google.protobuf.Timestamp at = 5;
}

// AIP-158, which replaces v1's limit, offset and has_more.
message ListAuditEntriesRequest {
  int32 page_size = 1;
  string page_token = 2;
}

message ListAuditEntriesResponse {
  repeated AuditEntry audit_entries = 1;
  string next_page_token = 2;
}
```

- [ ] **Step 5: Write `proto/lessons/v2/directory.proto` in full.**
```proto
// The school directory: a search over the ЕГРЮЛ company register, because no
// downloadable register of Russian schools exists. Replaces v1's
// GET /directory/school-regions and GET /manage/schools.
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service DirectoryService {
  // Which regions a school called `query` may be in, best first, at most
  // ten; the order is the contract. For a phone that belongs to no class
  // yet, so anonymous, and therefore metered twice:
  // - twenty searches per caller in fifteen minutes (THROTTLED);
  // - the anonymous share of the directory's daily allowance (DIRECTORY_SPENT).
  // A query under three characters is VALIDATION_FAILED and is not counted.
  // Picking the region from the list always works, so every failure says so.
  rpc ListSchoolRegions(ListSchoolRegionsRequest) returns (ListSchoolRegionsResponse) {
    option (google.api.http) = {get: "/v2/schoolRegions"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_NONE;
  }
  // Schools matching `query`, looked up before UpdateClass names one, as
  // «⚙️ Класс» does in the bot. An admin's, because every call spends an
  // allowance somebody else pays for. Every call is one upstream search
  // whatever the page, because the directory has no offset: a client asks
  // once with page_size 20 and cuts the answer up itself. v1 and the design
  // called it a search; as a List of schools with a query it is a standard
  // method, which a method named Search on a GET is not.
  rpc ListSchools(ListSchoolsRequest) returns (ListSchoolsResponse) {
    option (google.api.http) = {get: "/v2/schools"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_ADMIN;
  }
}

// One region the search found schools in. v1: SchoolRegionOut.
message SchoolRegion {
  // The region catalog's key («tatarstan»); absent when the directory named
  // a region the catalog cannot place, and then `label` says what it called
  // it.
  optional string region = 1;
  // The two-digit subject code.
  optional string code = 2;
  // The directory's own name, only for an unplaced region.
  optional string label = 3;
  // Hits in this region among the twenty the directory returns at most.
  int32 schools = 4;
  // Up to three towns, best first.
  repeated string cities = 5;
  // Up to three school names, as a person writes them.
  repeated string examples = 6;
}

// One row of the directory. v1: SchoolOut.
message School {
  string name = 1;
  string full_name = 2;
  // The OGRN: thirteen digits, assigned once and never reused, so a client
  // tells two «Гимназия № 3» apart.
  optional string ogrn = 3;
  optional string inn = 4;
  optional string address = 5;
  optional string city = 6;
  optional string region = 7;
  // False for a school the register has closed: shown, not hidden.
  bool active = 8;
}

message ListSchoolRegionsRequest {
  // The school's name as people write it. v1: the query field `q`.
  string query = 1;
}

// v1: SchoolRegionsOut.
message ListSchoolRegionsResponse {
  // As searched: whitespace collapsed, cut to 150 characters.
  string query = 1;
  repeated SchoolRegion regions = 2;
  // The directory's ceiling of twenty was reached: these are the first
  // twenty's regions.
  bool truncated = 3;
  // Too common to place («школа № 5» is in every region).
  bool generic = 4;
}

message ListSchoolsRequest {
  string query = 1;
  // Narrows the search to a region's name.
  optional string region = 2;
  // 1 to 20; 20 returns everything one search found.
  int32 page_size = 3;
  string page_token = 4;
}

// v1: SchoolSearchOut, with AIP-158 paging instead of page and pages.
message ListSchoolsResponse {
  repeated School schools = 1;
  string next_page_token = 2;
  int32 total_size = 3;
  // The directory's own ceiling of twenty was reached: the way forward is a
  // longer query, not a next page.
  bool truncated = 4;
}
```

- [ ] **Step 6: Lint, generate, green (`25 passed`), gates, full suite.** As Task 3 Step 6.

- [ ] **Step 7: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && git add proto server/app/contract server/tests/test_contract.py && git commit -F - <<'EOF'
Write the class's phones, access requests, journal and the school directory into the contract

ClassDeviceService and AccessRequestService keep v1's actions as AIP custom
methods (:revoke, :unlink, :approve, :decline) under the admin's role.
AuditService pages the journal with page tokens instead of offsets.
DirectoryService keeps the anonymous region search, its order and its two
meters. The admin's school search becomes ListSchools: under the design's
own rule, a method named Search on a GET is neither standard nor a custom
:verb, and a List with a query is standard.

Instants that v1 sent as the class's wall time without a zone (a device's
dates, a request's and a journal line's) are Timestamps now, as the design's
decision 4 asks.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 6: DiaryService

**Files:**
- Create: `proto/lessons/v2/diary.proto` and its generated modules.
- Modify: `server/tests/test_contract.py`.

**Interfaces:**
- Consumes: `AuthKind` (no `Role`: no diary method takes `min_role`).
- Produces: `DiaryCapabilities`, `ProviderCapabilities`, `DiaryFeature`, `SignInMethod`, `DiarySession`, `DiaryStudent`, `DiaryScheduleDay`, `DiaryLesson`, `DiaryEdit`, `DiaryHomework`, `DiaryMark`, `MarkKind`, `DiaryPeriod`, `DiarySubject`, `DiaryTeacher`, `DiaryAttendance`, `AttendanceDirection`, `DiaryCorrection`, `CorrectionUpdate`, `CorrectionKey`.

- [ ] **Step 1: Red.**
  - `FILES` and `SERVICE_FILES` gain `"diary"`.
  - Insert into `METHODS`:
```python
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
    ("DiaryService", "ListAttendance"): Row(
        "get", "/v2/diary/students/{student_id}/attendance", "", DIARY, None
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
```
  - Run → red.

- [ ] **Step 2: Write `proto/lessons/v2/diary.proto` in full.**
```proto
// A family's electronic diary, through the diary session token. It covers
// what this server's diary can do, registering a session the phone opened
// with the diary itself, what the diary says about each pupil, and the
// corrections a family lays over it. The diary token is independent of the
// device token: a phone may hold either without the other. Replaces v1's
// /diary/….
//
// What is not here, on purpose: v1's POST /diary/login, a password through
// this server, kept in v1 for APKs from before /diary/session. Every APK v2
// serves opens its diary session itself.
syntax = "proto3";

package lessons.v2;

import "google/api/annotations.proto";
import "google/protobuf/timestamp.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service DiaryService {
  // What this server's diary can do, asked before the phone shows a sign-in
  // form: whether it runs at all, and for each provider the regions, the ways
  // in, and the data it has. Anonymous and database-free.
  rpc GetDiaryCapabilities(GetDiaryCapabilitiesRequest) returns (GetDiaryCapabilitiesResponse) {
    option (google.api.http) = {get: "/v2/diary/capabilities"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_NONE;
  }
  // Keeps a session the phone opened with the diary itself, and answers a
  // diary token of ours. The session is read with once, from this server's
  // address (that read is the check), then sealed, and it is never echoed
  // back, not even in a refusal. The caller is counted under v1's
  // /diary/session limits. REST answers 201.
  //
  // Refusals:
  // - DIARY_DISABLED and DIARY_UNAVAILABLE: not counted, because nothing
  //   judged the session;
  // - DIARY_CREDENTIALS_REJECTED, DIARY_NO_STUDENTS and
  //   DIARY_UPSTREAM_UNREADABLE: counted;
  // - VALIDATION_FAILED: a region outside the allow-list, before any
  //   upstream call;
  // - THROTTLED.
  rpc CreateDiarySession(CreateDiarySessionRequest) returns (CreateDiarySessionResponse) {
    option (google.api.http) = {
      post: "/v2/diary/sessions"
      body: "*"
    };
    option (lessons.v2.auth) = AUTH_KIND_NONE;
  }
  // Signs this session out: the token stops working and the sealed upstream
  // session goes.
  rpc DeleteDiarySession(DeleteDiarySessionRequest) returns (DeleteDiarySessionResponse) {
    option (google.api.http) = {delete: "/v2/diary/sessions/current"};
    option (lessons.v2.auth) = AUTH_KIND_DIARY;
  }
  // Every pupil this diary account may see: one, for most parents.
  rpc ListStudents(ListStudentsRequest) returns (ListStudentsResponse) {
    option (google.api.http) = {get: "/v2/diary/students"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DIARY;
  }
  // A pupil's lessons, day by day, with the family's corrections laid over
  // them. The range is the diary's today and 14 days on when unset, and
  // spans 62 days at most. A student id this session's diary does not list
  // is RESOURCE_NOT_FOUND, on every method below: an id from another family
  // must reach nothing. DIARY_FEATURE_SCHEDULE.
  rpc ListScheduleDays(ListScheduleDaysRequest) returns (ListScheduleDaysResponse) {
    option (google.api.http) = {get: "/v2/diary/students/{student_id}/scheduleDays"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DIARY;
  }
  // A pupil's homework by due date, with corrections laid over it. Named for
  // the diary because HomeworkService's ListHomework is the class's.
  // DIARY_FEATURE_HOMEWORK.
  rpc ListDiaryHomework(ListDiaryHomeworkRequest) returns (ListDiaryHomeworkResponse) {
    option (google.api.http) = {get: "/v2/diary/students/{student_id}/homework"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DIARY;
  }
  // A pupil's marks, absences and lateness, which a diary files together.
  // Never corrected: a family rewriting a mark would be producing a false
  // record that looks official. DIARY_FEATURE_MARKS.
  rpc ListMarks(ListMarksRequest) returns (ListMarksResponse) {
    option (google.api.http) = {get: "/v2/diary/students/{student_id}/marks"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DIARY;
  }
  // A pupil's quarters or terms as the diary has them. DIARY_FEATURE_PERIODS.
  rpc ListPeriods(ListPeriodsRequest) returns (ListPeriodsResponse) {
    option (google.api.http) = {get: "/v2/diary/students/{student_id}/periods"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DIARY;
  }
  // The subjects of a period, the current one when none is named. Named for
  // the diary because SubjectService's ListSubjects is the class's.
  // DIARY_FEATURE_SUBJECTS.
  rpc ListDiarySubjects(ListDiarySubjectsRequest) returns (ListDiarySubjectsResponse) {
    option (google.api.http) = {get: "/v2/diary/students/{student_id}/subjects"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DIARY;
  }
  // DIARY_FEATURE_TEACHERS.
  rpc ListTeachers(ListTeachersRequest) returns (ListTeachersResponse) {
    option (google.api.http) = {get: "/v2/diary/students/{student_id}/teachers"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DIARY;
  }
  // Turnstile entries and exits, newest first: what v1's /attendance served.
  // DIARY_FEATURE_TURNSTILE.
  rpc ListAttendance(ListAttendanceRequest) returns (ListAttendanceResponse) {
    option (google.api.http) = {get: "/v2/diary/students/{student_id}/attendance"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DIARY;
  }
  // Every correction anybody who sees this pupil made, both parents' alike;
  // no row says whose.
  rpc ListCorrections(ListCorrectionsRequest) returns (ListCorrectionsResponse) {
    option (google.api.http) = {get: "/v2/diary/students/{student_id}/corrections"};
    option idempotency_level = NO_SIDE_EFFECTS;
    option (lessons.v2.auth) = AUTH_KIND_DIARY;
  }
  // Writes or replaces corrections, all or none. Each target is the string a
  // read handed down, echoed back; the last writer wins, `original`
  // included. CORRECTIONS_UNAVAILABLE for a pupil who can have none.
  // VALIDATION_FAILED, naming `corrections[i].target`, `.field` or `.value`,
  // for a target this server would never produce, a field that cannot be
  // corrected, or an empty value where one is required.
  rpc BatchUpdateCorrections(BatchUpdateCorrectionsRequest) returns (BatchUpdateCorrectionsResponse) {
    option (google.api.http) = {
      post: "/v2/diary/students/{student_id}/corrections:batchUpdate"
      body: "*"
    };
    option (lessons.v2.auth) = AUTH_KIND_DIARY;
  }
  // Takes the named corrections off, for everyone who sees the pupil. It
  // answers the same whether or not there was one: «no correction here» is
  // what was asked for. A POST with a body, because a target is free text,
  // and a value that must match byte for byte does not travel in a URL.
  rpc ResetCorrections(ResetCorrectionsRequest) returns (ResetCorrectionsResponse) {
    option (google.api.http) = {
      post: "/v2/diary/students/{student_id}/corrections:reset"
      body: "*"
    };
    option (lessons.v2.auth) = AUTH_KIND_DIARY;
  }
  // Takes every correction for this pupil off, and nothing of any other's.
  rpc ClearCorrections(ClearCorrectionsRequest) returns (ClearCorrectionsResponse) {
    option (google.api.http) = {
      post: "/v2/diary/students/{student_id}/corrections:clear"
      body: "*"
    };
    option (lessons.v2.auth) = AUTH_KIND_DIARY;
  }
}

// What a provider has (the design's decision 8). A new datum is a new value,
// which an old client ignores by the enum rule; a diary method whose feature
// a provider lacks answers FEATURE_UNSUPPORTED.
enum DiaryFeature {
  DIARY_FEATURE_UNSPECIFIED = 0;
  DIARY_FEATURE_SCHEDULE = 1;
  DIARY_FEATURE_HOMEWORK = 2;
  DIARY_FEATURE_MARKS = 3;
  DIARY_FEATURE_PERIODS = 4;
  DIARY_FEATURE_SUBJECTS = 5;
  DIARY_FEATURE_TEACHERS = 6;
  // Absences by lesson and their statistics: no method serves it yet.
  DIARY_FEATURE_ATTENDANCE = 7;
  // Turnstile entries and exits: ListAttendance.
  DIARY_FEATURE_TURNSTILE = 8;
  DIARY_FEATURE_MEAL_ACCOUNT = 9;
  DIARY_FEATURE_FINAL_MARKS = 10;
}

// How the phone gets into a provider's diary, so it draws the right form.
enum SignInMethod {
  SIGN_IN_METHOD_UNSPECIFIED = 0;
  // A login and a password, typed into the phone's own form.
  SIGN_IN_METHOD_PASSWORD = 1;
  // A session the phone opened elsewhere and hands to CreateDiarySession.
  SIGN_IN_METHOD_SESSION_ADOPT = 2;
}

// v1: DiaryCapabilitiesOut. Its `registration` is gone: every v2 server
// registers sessions.
message DiaryCapabilities {
  // False without DIARY_SECRET: nothing anybody types will help.
  bool enabled = 1;
  repeated ProviderCapabilities providers = 2;
}

message ProviderCapabilities {
  // A provider key, "petersburg" or "netschool" today: a string, so a
  // provider added on the server reaches the phone with no proto change.
  string provider = 1;
  // The allow-list keys this server signs in to for the provider. A region
  // missing here is one the phone should not send a password to.
  repeated string regions = 2;
  repeated SignInMethod sign_in_methods = 3;
  repeated DiaryFeature features = 4;
}

// The session Petersburg handed the phone. v1: PetersburgCredentialIn.
message PetersburgCredential {
  // The X-JWT-Token cookie, or data.token when the answer carried no cookie:
  // a JWT, 16 to 4096 characters, one cookie value.
  string token = 1;
}

// What the phone's «Сетевой город» sign-in ended holding.
// v1: NetSchoolCredentialIn.
message NetSchoolCredential {
  // The bearer sent in the `at` header: 8 to 512 characters, one header
  // value.
  string at = 1;
  NetSchoolCookies cookies = 2;
  // Up to 32 characters of [A-Za-z0-9._-].
  optional string ver = 3;
  // The login's timeOut. Stored and read by nothing.
  optional int32 time_out = 4;
}

// The two session cookies «Сетевой город» sets, and no other.
// v1: NetSchoolCookiesIn, whose upper-case field names v2's lint refuses.
message NetSchoolCookies {
  // v1: NSSESSIONID.
  string ns_session_id = 1;
  // v1: ESRNSec.
  optional string esrn_sec = 2;
}

// v1: DiarySessionBody, told apart by `provider`; here the credential's case
// says which.
message CreateDiarySessionRequest {
  // What the person typed into the phone's form, 3 to 200 characters. It only
  // names the session: what the session reaches is what its own diary lists.
  string login = 1;
  oneof credential {
    PetersburgCredential petersburg = 2;
    NetSchoolCredential netschool = 3;
  }
  // «Сетевой город» only: an allow-list key that still takes a password.
  optional string region = 4;
  // «Сетевой город» only: the diary's own school id («scid»).
  optional int64 school_id = 5;
}

message CreateDiarySessionResponse {
  DiarySession session = 1;
}

// Our bearer for the session, and what the phone needs to keep reading it.
// Never the upstream credential, which stays here, sealed. v1: DiarySessionOut.
message DiarySession {
  // The diary token, for `Authorization: Bearer`.
  string token = 1;
  string login = 2;
  string provider = 3;
  optional string region = 4;
  optional int64 school_id = 5;
  // What the diary calls the school, where it says; absent for Petersburg.
  optional string school_name = 6;
  // The zone the diary cuts its days at.
  string zone = 7;
  // The pupils the validating read already fetched, so the phone does not
  // ask again straight away.
  repeated DiaryStudent students = 8;
}

message DeleteDiarySessionRequest {}

message DeleteDiarySessionResponse {}

// v1: DiaryStudentOut. Never the upstream's own handles: the server resolves
// them from the id on every call, and a client that learned them could be
// pointed at somebody else's child.
message DiaryStudent {
  int64 id = 1;
  string first_name = 2;
  string last_name = 3;
  optional string middle_name = 4;
  string full_name = 5;
  optional string school = 6;
  optional string class_name = 7;
}

message ListStudentsRequest {}

message ListStudentsResponse {
  repeated DiaryStudent students = 1;
}

// One field a family corrected, as the client draws it. v1: DiaryEditOut.
message DiaryEdit {
  string field = 1;
  string value = 2;
  // What the diary says now, not what it said when the correction was
  // written: the client shows it as «в дневнике: …».
  optional string original = 3;
  // The diary changed this field since the correction was made.
  bool changed_upstream = 4;
}

// A day of a pupil's lessons.
message DiaryScheduleDay {
  // "YYYY-MM-DD".
  string date = 1;
  repeated DiaryLesson lessons = 2;
}

// v1: DiaryLessonOut, whose date is its day's now.
message DiaryLesson {
  optional int32 number = 1;
  string subject = 2;
  // "HH:MM".
  optional string starts_at = 3;
  // "HH:MM".
  optional string ends_at = 4;
  optional string room = 5;
  optional string teacher = 6;
  optional string homework = 7;
  optional string topic = 8;
  // The key a correction for this lesson is filed under: sent down so the
  // client echoes it back rather than building its own.
  string target = 9;
  repeated DiaryEdit edits = 10;
  // Another lesson the same day carries the same key, so no correction is
  // applied to either.
  bool ambiguous = 11;
}

message ListScheduleDaysRequest {
  int64 student_id = 1;
  // "YYYY-MM-DD"; the diary's today when absent. v1: the query field `from`.
  optional string start_date = 2;
  // "YYYY-MM-DD"; 14 days after start_date when absent. v1: `to`.
  optional string end_date = 3;
}

message ListScheduleDaysResponse {
  repeated DiaryScheduleDay schedule_days = 1;
}

// v1: DiaryHomeworkOut.
message DiaryHomework {
  optional int64 id = 1;
  // "YYYY-MM-DD".
  string due_date = 2;
  string subject = 3;
  string text = 4;
  optional string teacher = 5;
  // @see DiaryLesson.target
  string target = 6;
  repeated DiaryEdit edits = 7;
  // Two assignments due the same day share this key, so neither is corrected.
  bool ambiguous = 8;
}

message ListDiaryHomeworkRequest {
  int64 student_id = 1;
  // "YYYY-MM-DD"; the diary's today when absent.
  optional string start_date = 2;
  // "YYYY-MM-DD"; 14 days after start_date when absent.
  optional string end_date = 3;
}

message ListDiaryHomeworkResponse {
  repeated DiaryHomework homework = 1;
}

// What a register entry is. v1: DiaryMarkOut.kind's strings.
enum MarkKind {
  MARK_KIND_UNSPECIFIED = 0;
  MARK_KIND_GRADE = 1;
  MARK_KIND_ABSENCE = 2;
  MARK_KIND_LATE = 3;
  MARK_KIND_REMARK = 4;
  MARK_KIND_OTHER = 5;
}

// One register entry: `value` is what belongs in the cell, `kind` what it
// means. v1: DiaryMarkOut.
message DiaryMark {
  optional int64 id = 1;
  optional int64 subject_id = 2;
  string subject = 3;
  // "YYYY-MM-DD".
  optional string date = 4;
  // «5», «Н», «!», already normalised.
  string value = 5;
  MarkKind kind = 6;
  // The teacher's word for the occasion: «Контрольная работа».
  optional string reason = 7;
  optional string comment = 8;
}

message ListMarksRequest {
  int64 student_id = 1;
  // "YYYY-MM-DD"; the diary's today when absent.
  optional string start_date = 2;
  // "YYYY-MM-DD"; 14 days after start_date when absent.
  optional string end_date = 3;
}

message ListMarksResponse {
  repeated DiaryMark marks = 1;
}

// v1: DiaryPeriodOut.
message DiaryPeriod {
  int64 id = 1;
  string name = 2;
  // "YYYY-MM-DD".
  optional string starts_on = 3;
  // "YYYY-MM-DD".
  optional string ends_on = 4;
  bool is_current = 5;
}

message ListPeriodsRequest {
  int64 student_id = 1;
}

message ListPeriodsResponse {
  repeated DiaryPeriod periods = 1;
}

// v1: DiarySubjectOut.
message DiarySubject {
  optional int64 id = 1;
  string name = 2;
}

message ListDiarySubjectsRequest {
  int64 student_id = 1;
  // The period; the current one when absent.
  optional int64 period_id = 2;
}

message ListDiarySubjectsResponse {
  repeated DiarySubject subjects = 1;
}

// v1: DiaryTeacherOut.
message DiaryTeacher {
  optional int64 id = 1;
  string name = 2;
  optional string position = 3;
  repeated string subjects = 4;
}

message ListTeachersRequest {
  int64 student_id = 1;
}

message ListTeachersResponse {
  repeated DiaryTeacher teachers = 1;
}

// Which way a turnstile was passed. v1: "in" | "out" | "unknown".
enum AttendanceDirection {
  ATTENDANCE_DIRECTION_UNSPECIFIED = 0;
  ATTENDANCE_DIRECTION_IN = 1;
  ATTENDANCE_DIRECTION_OUT = 2;
  // The diary did not say which way: a spelling this server has not seen
  // must not be drawn as the child leaving the building.
  ATTENDANCE_DIRECTION_UNKNOWN = 3;
}

// A turnstile record. v1: DiaryAttendanceOut.
message DiaryAttendance {
  // "YYYY-MM-DDTHH:MM:SS", the diary's own wall time, as it wrote it.
  string at = 1;
  AttendanceDirection direction = 2;
}

message ListAttendanceRequest {
  int64 student_id = 1;
}

message ListAttendanceResponse {
  repeated DiaryAttendance attendance = 1;
}

// A stored correction. v1: DiaryOverrideOut.
message DiaryCorrection {
  string target = 1;
  string field = 2;
  string value = 3;
  // What the diary said when this was written, not what it says now; spelled
  // apart from DiaryEdit.original on purpose.
  optional string original_when_written = 4;
  google.protobuf.Timestamp updated_at = 5;
}

// A correction being written. v1: DiaryOverrideIn.
message CorrectionUpdate {
  // The target a read handed down, 1 to 300 characters.
  string target = 1;
  // 1 to 40 characters.
  string field = 2;
  // Up to 4000 characters. Empty is a real answer and is stored; taking a
  // correction off is ResetCorrections.
  string value = 3;
  // What the person was looking at when they wrote it, up to 4000
  // characters.
  optional string original = 4;
}

// Which correction to take off. v1: DiaryResetIn.
message CorrectionKey {
  string target = 1;
  string field = 2;
}

message ListCorrectionsRequest {
  int64 student_id = 1;
}

message ListCorrectionsResponse {
  repeated DiaryCorrection corrections = 1;
}

message BatchUpdateCorrectionsRequest {
  int64 student_id = 1;
  repeated CorrectionUpdate corrections = 2;
}

message BatchUpdateCorrectionsResponse {
  repeated DiaryCorrection corrections = 1;
}

message ResetCorrectionsRequest {
  int64 student_id = 1;
  repeated CorrectionKey corrections = 2;
}

message ResetCorrectionsResponse {}

message ClearCorrectionsRequest {
  int64 student_id = 1;
}

message ClearCorrectionsResponse {}
```

- [ ] **Step 3: Lint, generate, green (`25 passed`), gates, full suite.** As Task 3 Step 6.

- [ ] **Step 4: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && git add proto server/app/contract server/tests/test_contract.py && git commit -F - <<'EOF'
Write the family's diary into the contract, with capabilities a new provider needs no proto for

DiaryService takes v1's diary family under the diary token. The capabilities
become a list of providers, each a string key with its regions, its ways in
and the features it has, so a provider added on the server reaches the
phone with no proto change. A session the phone opened is registered under
a typed oneof per provider. The pupil's lessons arrive grouped by day, as
the design's ListScheduleDays names them. Corrections keep their filing
under the child and gain a batch write, a reset by keys and a clear-all as
AIP custom methods.

Two names differ from the design's map, and the plan says why: the
diary's homework and subjects are ListDiaryHomework and ListDiarySubjects,
because their request messages would otherwise share a name with the
class's in one package. v1's password sign-in is left out, as the design
decided.

Not covered: ListAttendance answers v1's turnstile records
(DIARY_FEATURE_TURNSTILE), while DIARY_FEATURE_ATTENDANCE has no method yet;
the names are the design's and the owner may want them looked at.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 7: WatchService (beta)

**Files:**
- Create: `proto/lessons/v2/watch.proto` and its generated modules.
- Modify: `server/tests/test_contract.py`.

**Interfaces:**
- Consumes: `AuthKind`, `Role`.
- Produces: `WatchService.WatchClass`, a server stream. This is the case that exercises the stream branches of every generic check.

- [ ] **Step 1: Red.**
  - `FILES` and `SERVICE_FILES` gain `"watch"`.
  - Insert into `METHODS`: `("WatchService", "WatchClass"): Row(None, None, "", DEVICE, VIEWER),`.
  - Append to `test_the_readers_see_what_they_are_written_for`:
```python
    watch_service, watch = found[("WatchService", "WatchClass")]
    assert _binding(watch) == (None, None, "")
    assert _streams(watch)
    assert _on_the_class(watch_service, watch)
    assert _enum_option(watch, options_pb.ext_min_role) == VIEWER
```
  - Run → red: `KeyError` and the map tests.

- [ ] **Step 2: Write `proto/lessons/v2/watch.proto` in full.**
```proto
// The class changing, as it happens: a beta of the long-running host target
// (the programme's section 4). The app opens it while it is in the foreground
// and syncs the moment the class changes, instead of at the next periodic
// sync. Nothing else depends on it. One host instance serves it, and Vercel
// never does.
syntax = "proto3";

package lessons.v2;

import "google/protobuf/timestamp.proto";
import "lessons/v2/options.proto";

option java_multiple_files = true;
option java_package = "com.lumenpearson.lessons.contract.v2";

service WatchService {
  // A server stream of revisions, never the data: on each, the phone fetches
  // the schedule window as it already does. No REST binding, because REST
  // cannot carry a stream. A target without streaming answers
  // FEATURE_UNSUPPORTED.
  rpc WatchClass(WatchClassRequest) returns (stream WatchClassResponse) {
    option (lessons.v2.auth) = AUTH_KIND_DEVICE;
    option (lessons.v2.min_role) = ROLE_VIEWER;
  }
}

message WatchClassRequest {}

message WatchClassResponse {
  // Changes whenever the class does. Opaque: compare it, never parse it.
  string revision = 1;
  google.protobuf.Timestamp changed_at = 2;
}
```

- [ ] **Step 3: Lint, generate, green (`25 passed`), gates, full suite.** The full suite is the previous count + 0; across Tasks 1–7 that is 2075 + 25 = 2100 if 2075 was current.

- [ ] **Step 4: Count what exists.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && ls proto/lessons/v2 | wc -l && grep -c "^  rpc " proto/lessons/v2/*.proto | awk -F: '{s+=$2} END {print s}' && grep -h "^service " proto/lessons/v2/*.proto | wc -l
```
Expected: `20`, `76`, `17`.

- [ ] **Step 5: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && git add proto server/app/contract server/tests/test_contract.py && git commit -F - <<'EOF'
Write the streaming beta into the contract: WatchClass, a revision at a time

WatchService is the one server stream: revisions of the class, never its
data, for the long-running host target's beta. It has no REST binding and
no idempotency marking, it is a device's with the least role any viewer
holds, and tests/test_contract.py now proves its readers see a method
without a binding as well as with one. With it, the contract is the
design's map: seventeen services, seventy-six methods, twenty files.

Not covered: nothing serves it; the host target, the in-process bus and the
synthetic CI test are sub-project 3's.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 8: The «Contract» CI job

**Files:**
- Modify: `.github/workflows/ci.yml` (the `changes` job, and a new `contract` job); `docs/build.md`; `CLAUDE.md` (the CI sentence); `CONTRIBUTING.md` (the CI sentence); `.claude/agents/build-ci.md`; `.claude/skills/gates/SKILL.md`; `.claude/skills/steward/SKILL.md`.

**Interfaces:**
- Consumes: `buf.yaml`, `buf.gen.yaml`, `buf.lock`, `proto/`, `server/app/contract/`.
- Produces: the `contract` output of «What changed», and a «Contract (Buf)» check on every pull request and push that touches the contract.

**Why this workflow edit is warranted, against «the workflows are not edited casually»:**
- The design requires this edit: generated code is committed, because Vercel runs no code generation, and the design's freshness check is what stops a proto change from merging without its Python.
- Buf's `FILE` breaking check is the mechanical half of the programme's «Evolving the contract», which no local test can do without the base.
- It costs nothing on other changes: the job is path-filtered, and the repository is public, so standard runners are unbilled (`docs/build.md`, «Actions minutes»).
- It reads no secret, so `ci.yml` keeps its documented property that a fork's pull request runs it whole.
- The programme requires the `build-ci` agent's review (Step 1).

- [ ] **Step 1: Have the `build-ci` agent review the change before it is made.** Dispatch the `build-ci` agent (`.claude/agents/build-ci.md`). Give it this task's Steps 2 and 3 verbatim, and ask:
  - whether the filter fails open as the other outputs do;
  - whether anything here asks for a retention, a secret or a permission;
  - whether `pr_comment: false` and `push: false` are right on this repository.

  Apply its findings before Step 2. If it asks for a change to this plan's YAML, record the change in the commit body.

- [ ] **Step 2: Rewrite the `changes` job** (the first approval prompt is expected: `.claude/settings.json` asks for every edit under `.github/workflows/`). It reads in full:
```yaml
  changes:
    name: What changed
    runs-on: ubuntu-latest
    timeout-minutes: 5
    outputs:
      android: ${{ steps.filter.outputs.android }}
      server: ${{ steps.filter.outputs.server }}
      contract: ${{ steps.filter.outputs.contract }}
    steps:
      - uses: actions/checkout@v7
        with:
          # A pull request needs its base to diff against; a push needs the
          # commit before it. Both are cheap on a repository this size.
          fetch-depth: 0

      - id: filter
        env:
          BASE_REF: ${{ github.base_ref }}
          BEFORE: ${{ github.event.before }}
        run: |
          set -euo pipefail

          range=""
          if [ -n "${BASE_REF:-}" ]; then
            git fetch --no-tags --depth=200 origin "$BASE_REF" || true
            if git rev-parse --verify -q FETCH_HEAD >/dev/null; then
              range="FETCH_HEAD...HEAD"
            fi
          elif [ -n "${BEFORE:-}" ] && [ "$BEFORE" != "0000000000000000000000000000000000000000" ]; then
            if git cat-file -e "$BEFORE^{commit}" 2>/dev/null; then
              range="$BEFORE..HEAD"
            fi
          fi

          if [ -z "$range" ]; then
            echo "::notice::Change range unknown; building everything."
            echo "android=true" >> "$GITHUB_OUTPUT"
            echo "server=true" >> "$GITHUB_OUTPUT"
            echo "contract=true" >> "$GITHUB_OUTPUT"
            exit 0
          fi

          files=$(git diff --name-only "$range")
          echo "Changed files:"
          echo "$files"

          # The workflow files themselves count as all three: a change to how
          # the build runs has to be proved by running it.
          android=false
          server=false
          contract=false
          while IFS= read -r file; do
            case "$file" in
              # `docs/app/` is Android: `:core:data` packages it as assets
              # (core/data/build.gradle.kts) and `DocsGuideParityTest` holds the
              # two guides level page for page. A commit touching only
              # `guide.ru.md` set neither output, both jobs were skipped by
              # their `if:`, and the workflow was green with nothing having run
              # — which is how the Russian guide grows a page the English one
              # does not have, and an English reader gets a toolbar with a
              # different number of buttons and nothing logged.
              # `docs/legal/` is the same case: `:app` packages it as assets
              # and `LegalDocumentsTest` holds the two languages level. The
              # region catalog and the protocol vectors live under `server/`
              # but are Android inputs too — the catalog is the phone's
              # allow-list of diary hosts, bundled in place, and the vectors
              # are `DiaryProtocolVectorsTest`'s resource — so an edit to
              # either must run both halves, not only the server's.
              android/*|docs/app/*|docs/legal/*|server/app/catalog/data/*|server/tests/vectors/*|.github/workflows/*) android=true ;;
            esac
            case "$file" in
              # Server tests read documents too, and a commit touching only one
              # of them used to run nothing (#159): `docs/deploy.md` and
              # `docs/build.md` are compared with the settings and `.env.example`
              # (test_deployment_config, test_database_url, test_roles,
              # test_env_example), `docs/diaries/regions.md` is the region
              # catalog's input and `docs/diaries.md` its table
              # (test_region_catalog), `docs/diaries/netschool.md` is quoted by
              # test_netschool_mapper. `docker-compose.yml` is read by
              # test_compose, which is all that checks it: nothing here runs Docker.
              # `requirements.in` is the input of the lock `requirements.txt`, and
              # test_requirements_mirror holds the two level with pyproject.toml.
              # `buf.gen.yaml` is read by test_contract, which holds its pinned
              # plugin versions level with the runtime floors in requirements.in.
              server/*|api/*|requirements.txt|requirements.in|docker-compose.yml|docs/deploy.md|docs/build.md|docs/diaries.md|docs/diaries/*|buf.gen.yaml|.github/workflows/*) server=true ;;
            esac
            case "$file" in
              # The v2 contract and what is generated from it: the job below.
              # `server/app/contract/` is here as well as under the server,
              # because a hand edit there, with no proto change, is exactly what
              # the regeneration check exists to refuse.
              proto/*|buf.yaml|buf.gen.yaml|buf.lock|server/app/contract/*|.github/workflows/*) contract=true ;;
            esac
          done <<< "$files"

          echo "android=$android" >> "$GITHUB_OUTPUT"
          echo "server=$server" >> "$GITHUB_OUTPUT"
          echo "contract=$contract" >> "$GITHUB_OUTPUT"
```

- [ ] **Step 3: Add the `contract` job**, between `changes` and `server`. It reads in full:
```yaml
  # The v2 contract (docs/specs/2026-10-04-contract-v2-design.md, «Checks»).
  #
  # Generated code is committed, because Vercel runs no code generation, so a
  # proto change merged without its regenerated Python would deploy the old
  # contract with nothing anywhere saying so. This job regenerates from proto/
  # and fails on any difference. It lints with Buf's STANDARD rules. On a pull
  # request it checks the proto against the base with the FILE category, the
  # mechanical half of «Evolving the contract»: no field renamed, renumbered
  # or retyped in place.
  #
  # Only when the contract changed (the filter above), so every other run pays
  # nothing for it. It reads no secret, like the rest of this file, so a pull
  # request from a fork runs it whole. Buf's remote plugins and the Schema
  # Registry are used unauthenticated, as the spike used them. If Buf ever
  # throttles this job, the generate step says so, and the fix is a BUF_TOKEN
  # repository secret handed to the buf-action step: the owner's step,
  # docs/build.md, «The v2 contract and Buf».
  contract:
    name: Contract (Buf)
    needs: changes
    if: needs.changes.outputs.contract == 'true'
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@v7
        with:
          # The base commit has to be here to be asked whether it has a contract.
          fetch-depth: 0

      # On the pull request that adds the contract, the base has no buf.yaml
      # and there is nothing to compare against. That is said, not passed off
      # as a green check.
      - name: Does the base have a contract
        id: base
        if: github.event_name == 'pull_request'
        env:
          BASE_SHA: ${{ github.event.pull_request.base.sha }}
        run: |
          set -euo pipefail
          if git cat-file -e "$BASE_SHA:buf.yaml" 2>/dev/null; then
            echo "proto=true" >> "$GITHUB_OUTPUT"
          else
            echo "proto=false" >> "$GITHUB_OUTPUT"
            echo "::notice::The base of this pull request has no buf.yaml: buf breaking has nothing to compare against and is skipped, not passed."
          fi

      # Pinned twice: the action by its exact tag, and Buf by version and by
      # the SHA-256 of the Linux binary the action downloads (the release's
      # sha256.txt), so neither moves without a commit here.
      - id: buf
        uses: bufbuild/buf-action@v1.6.0
        with:
          version: 1.73.0
          checksum: 8f2986298ad08f0cc1bf999b9797b7c383adf32d7edf0f73d6f1e1a701baeac1
          lint: true
          breaking: ${{ github.event_name == 'pull_request' && steps.base.outputs.proto == 'true' }}
          # Not asked for by the design; the proto files are formatted by hand.
          format: false
          # This repository publishes nothing to the Buf Schema Registry.
          push: false
          archive: false
          # A comment needs pull-requests: write, and this workflow asks for
          # read only.
          pr_comment: false

      - name: Generated code is what proto/ generates
        env:
          BUF: ${{ steps.buf.outputs.buf_path }}
        run: |
          set -euo pipefail
          out="$RUNNER_TEMP/generated"
          if ! "$BUF" generate --output "$out"; then
            echo "::error::buf generate failed. If the log says Buf refused an unauthenticated request (429, resource exhausted), the remote plugins need a BUF_TOKEN repository secret passed to this job: docs/build.md, «The v2 contract and Buf»."
            exit 1
          fi
          if ! diff -r --exclude=__pycache__ "$out/server/app/contract" server/app/contract; then
            echo "::error::server/app/contract is not what proto/ generates. From the repository root run: buf generate, then commit server/app/contract with the proto change."
            exit 1
          fi
```
The `server` and `android` jobs are unchanged.

- [ ] **Step 4: Check the YAML parses and the filter does what it says.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && /c/Users/lumen/StudioProjects/lessons/server/.venv/Scripts/python.exe -c "import yaml; d = yaml.safe_load(open('.github/workflows/ci.yml', encoding='utf-8')); print(sorted(d['jobs'])); print(d['jobs']['changes']['outputs'])" && for f in proto/lessons/v2/me.proto buf.yaml buf.gen.yaml buf.lock server/app/contract/lessons/v2/me_pb.py server/app/main.py docs/api.md; do case "$f" in proto/*|buf.yaml|buf.gen.yaml|buf.lock|server/app/contract/*|.github/workflows/*) echo "$f contract" ;; *) echo "$f -" ;; esac; done
```
Expected:
```
['android', 'changes', 'contract', 'server']
{'android': '${{ steps.filter.outputs.android }}', 'server': '${{ steps.filter.outputs.server }}', 'contract': '${{ steps.filter.outputs.contract }}'}
proto/lessons/v2/me.proto contract
buf.yaml contract
buf.gen.yaml contract
buf.lock contract
server/app/contract/lessons/v2/me_pb.py contract
server/app/main.py -
docs/api.md -
```

- [ ] **Step 5: Write `docs/build.md`'s new section.** Insert it after «## Locally» and before «## detekt»:
~~~markdown
## The v2 contract and Buf

`proto/lessons/v2/` is the v2 contract ([api.md](api.md), «v2: the contract»). Buf checks it
and generates the Python under `server/app/contract/` from it. Buf is not in the repository
and nothing installs it. CI's «Contract» job fetches **Buf 1.73.0** through
`bufbuild/buf-action` v1.6.0, pinned in `ci.yml` by version and by checksum. A local copy
is fetched by hand at the same version and kept outside the repository:

| Platform | Download | SHA-256 |
| --- | --- | --- |
| Windows x86-64 | `https://github.com/bufbuild/buf/releases/download/v1.73.0/buf-Windows-x86_64.exe` | `13542f2892c4f774150ddb525266d6421d457b3e741297056b64427853526e36` |
| Linux x86-64 | `https://github.com/bufbuild/buf/releases/download/v1.73.0/buf-Linux-x86_64` | `8f2986298ad08f0cc1bf999b9797b7c383adf32d7edf0f73d6f1e1a701baeac1` |

From the repository root:

- `buf lint`: the STANDARD rules, with the one exception `buf.yaml` names and why.
- `buf generate`: rewrites `server/app/contract/` whole (`clean: true`). Commit it in the
  same change as the proto, because CI regenerates and fails on any difference.
- `buf breaking --against "$(git rev-parse --git-common-dir)#branch=main"`: the FILE rules
  against `main`, once `main` has a contract. Before that there is nothing to compare
  against, which is also why CI skips the check on the pull request that adds the contract
  and says so.

**The plugins are remote and pinned.** `buf.gen.yaml` names `buf.build/bufbuild/py:v0.6.0`
and `buf.build/connectrpc/py:v0.12.1`. An unpinned plugin takes Buf's latest release, so
CI would regenerate something else one morning with nothing changed here.
`server/tests/test_contract.py` holds each pin level with its runtime's floor in
`requirements.in` (`protobuf-py>=0.6.0`, `connectrpc>=0.12.1`) and with the header of every
generated module. Moving one is three edits and a regeneration, in one commit.

**Unauthenticated, for now.** Generation, and the `googleapis` dependency pinned in
`buf.lock`, come from the Buf Schema Registry without a login, as the spike did it. Buf
rate-limits anonymous use, and CI has not met the limit. If it does, the generate step says
so, and the fix is the owner's:
- a `BUF_TOKEN` repository secret holding a Buf token;
- `token: ${{ secrets.BUF_TOKEN }}` on the `buf-action` step.

That is also the day `ci.yml` stops reading no secret, and the table in «The other place
variables live» gains a row.
~~~
  - In «Actions minutes», extend the «**Path filters.**» bullet. After «…run the server;», insert: «a change to `proto/`, `buf.yaml`, `buf.gen.yaml`, `buf.lock` or `server/app/contract/` runs the «Contract» job, which nothing else runs;».
  - Under «Actions minutes», after the measurement table, add the paragraph: «The «Contract (Buf)» job is not in the table: it runs only when the contract changes, and it had not been timed when it was added (`docs/specs/2026-10-04-contract-v2-plan.md`, Task 8).»

- [ ] **Step 6: Bring the other descriptions of CI level.**
  - **`CLAUDE.md`.** Replace «CI (`.github/workflows/ci.yml`) is: ruff, mypy, pytest (`-n auto`), `./gradlew test`, both assembles, and `./gradlew detekt` after them. Nothing else.» with «CI (`.github/workflows/ci.yml`) is: ruff, mypy, pytest (`-n auto`), `./gradlew test`, both assembles, and `./gradlew detekt` after them; and, when `proto/`, `buf.*` or `server/app/contract/` changed, the «Contract» job: `buf lint`, `buf breaking` against the base, and the check that the committed generated code is what `buf generate` writes. Nothing else.»
  - **`CONTRIBUTING.md`.** Replace «`pytest -n auto`, `./gradlew test`, `assembleDebug` and `assembleRelease`.» with «`pytest -n auto`, `./gradlew test`, `assembleDebug` and `assembleRelease`; and, when the contract changed, `buf lint`, `buf breaking` and a check that `server/app/contract/` is what `buf generate` writes.»
  - **`.claude/agents/build-ci.md`.** Replace «`ci.yml` is: `ruff`, `python -m mypy`, `pytest -n auto`, `./gradlew test`, `assembleDebug`, `assembleRelease`. Nothing else.» with «`ci.yml` is: `ruff`, `python -m mypy`, `pytest -n auto`, `./gradlew test`, `assembleDebug`, `assembleRelease`, `./gradlew detekt`, and the path-filtered «Contract (Buf)» job (`buf lint`, `buf breaking` against the base, the generated-code check), which reads no secret. Nothing else.»
  - **`.claude/skills/gates/SKILL.md`, «What CI is».** It becomes «`.github/workflows/ci.yml`: ruff, `python -m mypy`, pytest `-n auto`, `./gradlew test`, both assembles, and `./gradlew detekt` as a step of its own after them; and «Contract (Buf)» when the contract changed. Nothing else.»
  - **`.claude/skills/gates/SKILL.md`, new section.** Add before «## What CI is»:
~~~markdown
## Contract, from the repository root

Only when `proto/` changed. Buf 1.73.0 is fetched by hand and kept outside the repository
(`docs/build.md`, «The v2 contract and Buf").

1. `buf lint`: STANDARD, as CI runs it.
2. `buf generate`, then commit `server/app/contract/` with the proto change. CI regenerates
   and fails on any difference.
3. `python -m pytest -q -p no:xdist tests/test_contract.py` from `server/`: every method's
   route, credential, least role and idempotency against the resource map.
~~~
  - **`.claude/skills/steward/SKILL.md`, «What a CI failure here usually is».** Add a paragraph:
    > A red «Contract (Buf)» is one of four things, and its log says which. A lint finding: fix the proto. A breaking change: the contract grows by addition, so the fix is a new field or method, never an edit in place. «server/app/contract is not what proto/ generates»: run `buf generate` and commit. A 429 from Buf: unauthenticated use was throttled, and a `BUF_TOKEN` secret is the owner's step (`docs/build.md`).

- [ ] **Step 7: The tests that read these files.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2/server && /c/Users/lumen/StudioProjects/lessons/server/.venv/Scripts/python.exe -m pytest -q -p no:xdist tests/test_env_example.py tests/test_schema_version.py tests/test_contract.py
```
Expected: all pass. `test_env_example` finds no `secrets.` reference in `ci.yml`. `test_schema_version` finds no head named in the new text.

- [ ] **Step 8: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && git add .github/workflows/ci.yml docs/build.md CLAUDE.md CONTRIBUTING.md .claude/agents/build-ci.md .claude/skills/gates/SKILL.md .claude/skills/steward/SKILL.md && git commit -F - <<'EOF'
Run Buf in CI whenever the contract changes: lint, breaking changes, and stale generated code

The generated Python is committed because Vercel runs no code generation,
so nothing but CI can stop a proto change from merging without it. The new
«Contract (Buf)» job regenerates from proto/ into a temporary directory and
fails, with the command to run, on any difference from server/app/contract.
It lints with Buf's STANDARD rules. On a pull request it checks the proto
against its base with the FILE category, the mechanical half of the
programme's rules for evolving the contract.

It runs only when proto/, buf.yaml, buf.gen.yaml, buf.lock or
server/app/contract/ changes, and fails open like the other outputs of
«What changed». Buf and the action are pinned by version, by tag and by the
binary's checksum. It reads no secret, so ci.yml keeps the property its
documents promise: a fork's pull request runs all of it. buf.gen.yaml now
also runs the server, because test_contract reads it. The build-ci agent
reviewed the change before it was made.

Not covered until it runs: on this pull request the base has no buf.yaml,
so breaking is skipped with a notice rather than passed, and Buf's
unauthenticated rate limit in CI has never been met.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

- [ ] **Step 9: Push, open the pull request as a draft, and read the run.** A workflow change is verified by a run, not by reading.
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && git push -u origin contract/v2 && gh pr create --draft --base main --head contract/v2 --milestone "v0.10.0 — One contract: REST v2, Connect and native gRPC, build console" --title "Write down the v2 contract: proto/lessons/v2, its generated Python, and Buf in CI" --body-file - <<'EOF'
# Description

Sub-project 2 of docs/specs/2026-10-03-one-contract-design.md, built from
docs/specs/2026-10-04-contract-v2-design.md by docs/specs/2026-10-04-contract-v2-plan.md.

- The contract: proto/lessons/v2, seventeen services in twenty files, seventy-six methods.
  Each method carries its REST route (`google.api.http`, `/v2/…`), its credential
  (`(lessons.v2.auth)`), its least role on the class (`(lessons.v2.min_role)`) and its
  idempotency. `ErrorReason` holds thirty-three reasons.
- Its Python, generated by pinned remote Buf plugins into server/app/contract and committed;
  connectrpc and protobuf-py are added to pyproject.toml, requirements.in and the lock.
- server/tests/test_contract.py: everything Buf cannot check, against the design's resource
  map row for row; and that app.main imports none of it.
- A path-filtered «Contract (Buf)» CI job: lint, breaking against the base, and stale
  generated code.

Nothing is served: no handler, no route, no mount, no change to v1.

Refs #273. Does not close #268 (closes when v2 is served and v1's PUT /events is gone),
#269 or #270 (closes on the phone).

## What is affected

- [x] `docs/`, `README.md`
- [x] `.github/` — build and automation

(and `proto/`, `buf.*`, `server/app/contract/`, `server/tests/test_contract.py`,
`server/pyproject.toml`, `requirements.in`, `requirements.txt` — not on the template's list)

## Boundaries

- [x] `app/schedule.py` still imports neither FastAPI nor aiogram
- [x] The rule did not appear in two places
- [x] A model change comes with an Alembic revision: no model change
- [x] Nothing is scheduled "in process" on the server
- [x] "Now" is taken in the class's time zone: no clock is read
- [x] `:core:data` does not depend on `:widget`: no Android change
- [x] Every new Russian string has its English twin: none added
- [x] There is no token, class code, password or keystore path in the diff

## Verification

```text
cd server && ruff check app tests scripts migrations → All checks passed!
cd server && python -m mypy                          → Success: no issues found in 197 source files
cd server && pytest -q -n auto                       → (Task 9 Step 5's run)
cd android && ./gradlew test                         → not run: no android/ change
cd android && ./gradlew detekt                       → not run: no android/ change
```

## What is NOT covered

- Nothing serves the contract; every behaviour its comments describe is sub-project 3's.
- On this pull request «Contract (Buf)» skips buf breaking with a notice, because main has
  no buf.yaml yet.
- Buf's unauthenticated rate limit in CI has never been met.
- The generated Python had never been imported on Linux CPython 3.12 before this pull
  request's server job.

## Documentation

- [x] `docs/` updated
- [x] `CLAUDE.md` updated
- [x] `README.md` updated

https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
gh pr checks --watch
```
Expected:
- «What changed», «Server (API + bot)» and «Contract (Buf)» pass; «Android» runs too, because the workflow changed.
- The Contract run's annotations show the notice «The base of this pull request has no buf.yaml…».
- If «Contract (Buf)» fails on a 429, record it in HANDOVER section 7 as the owner's `BUF_TOKEN` step, and do not add a secret from here.

---

## Task 9: Documentation, CLAUDE.md, the test counts, and the HANDOVER close-out

**Files:**
- Modify: `docs/api.md`, `docs/README.md`, `CLAUDE.md`, `README.md`, `CONTRIBUTING.md`, `docs/architecture.md`, `.claude/skills/gates/SKILL.md`, `HANDOVER.md`, `docs/history.md`.

**Interfaces:**
- Consumes: Tasks 1–8, and the real counts from Step 5's run.
- Produces: «v2: the contract» in `docs/api.md`, and a close-out that is true at the moment the pull request merges.

- [ ] **Step 1: The final freshness check, as CI runs it.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe lint && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe generate --output /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/regen && diff -r --strip-trailing-cr --exclude=__pycache__ /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/regen/server/app/contract server/app/contract && echo FRESH
```
Expected: `FRESH`. `--strip-trailing-cr` is here because `core.autocrlf=true` may have converted a checkout to CRLF; CI on Linux needs no such flag.

- [ ] **Step 2: Write «v2: the contract» at the end of `docs/api.md`.** Also add a sentence to the page's first paragraph, after «…keeps working unchanged.»: «A second version, v2, is written down as a proto contract and served by nothing yet: «v2: the contract», at the end of this page.» The section reads in full:
~~~markdown
## v2: the contract

**Written down, not served.** Everything above this section is v1, and v1 is what every
request to this server reaches today. v2 is the contract the server will answer next:
`proto/lessons/v2/` at the root of the repository, checked by Buf, with its Python generated
into `server/app/contract/` and imported by nothing the deployment runs. Sub-project 3 of
[the programme](specs/2026-10-03-one-contract-design.md) serves it, and this section changes
from «will» to «does» then. The proto files are the reference: every service, method,
message and field there carries the comment that says what it means and what v1 sent in its
place. This section says only what a file cannot.

### One contract, three ways to call it

v2 is one package, `lessons.v2`: seventeen services in twenty files, one file per service
plus `options.proto`, `errors.proto` and `common.proto`. Each method will be reachable three
ways, from one handler:

- **REST, under `/api/v2/…`.** A method's `google.api.http` annotation is its route. It is
  written `/v2/…` and served under `/api`, because this deployment is one function and every
  path that works on it is there. Standard methods use their verbs: `GET` reads, `POST`
  creates, `PATCH` updates the fields an `update_mask` names, `DELETE` carries no body.
  Anything else is `POST …:verb`, as in `POST /api/v2/me:unlink`. Path fields are written
  in the path, and the rest of a `GET` or a `DELETE` in the query string.
- **Connect and gRPC-Web**, as `POST /api/rpc/lessons.v2.<Service>/<Method>`. Every `Get`
  and `List` is marked `NO_SIDE_EFFECTS`, so Connect may send it as a `GET`. Nothing else is
  marked.
- **Native gRPC**, on the same path, on the long-running host target only: Vercel passes no
  response trailers, and gRPC carries its status in them.

One method, `WatchService.WatchClass`, is a server stream: a beta of the host target, with
no REST binding.

### What the values look like

- A date is `"YYYY-MM-DD"` and a time of day is `"HH:MM"`: naive wall time in the class's
  zone, as in v1, except that v1 wrote times with seconds (`"08:30:00"`) and v2 does not. A
  wall-clock moment, such as a task's reminder, is `"YYYY-MM-DDTHH:MM"`.
- What really is an instant (when something was written, done or last seen) is a
  `google.protobuf.Timestamp`: an RFC 3339 string in UTC in JSON. v1 sent several of these
  as the class's wall time with no zone, such as a device's `created_at` and an audit
  entry's `at`. A client converts them with the class's zone.
- An id this server assigns is a 32-bit number. An id a diary assigns is 64-bit, and proto3
  JSON writes a 64-bit number as a string.
- An enum is its value's name: `"DAY_KIND_HOLIDAY"`, not v1's `"holiday"`. Every enum starts
  at `…_UNSPECIFIED`, which is how a field says «none» where v1 said `null`. A client maps a
  value it does not know to a fallback it states.
- Every method answers a `<Method>Response`, even where it holds a single resource, so one
  method's answer can grow without touching another's.

Canonical proto3 JSON names fields in lowerCamelCase and accepts either spelling. Whether
REST writes the proto's own snake_case names instead is sub-project 3's to settle, and this
section's to record.

### Who may call a method

Each method says so itself, in two options declared in `options.proto`:

| `(lessons.v2.auth)` | Credential | Who gets through |
| --- | --- | --- |
| `AUTH_KIND_NONE` | none | anybody: `CreateDevice`, `GetDiaryCapabilities`, `CreateDiarySession`, `ListSchoolRegions` |
| `AUTH_KIND_DEVICE` | `Authorization: Bearer` with the device token | any phone in the class, linked or not |
| `AUTH_KIND_DEVICE_LINKED` | the device token | a phone linked to an account; any other is refused with `DEVICE_NOT_LINKED` |
| `AUTH_KIND_DIARY` | `Authorization: Bearer` with the diary token | a diary session |

The two tokens stay independent, as in v1 («Authentication», above). Which one a method
takes is part of the method, not of its path.

`(lessons.v2.min_role)` is the least role a device method needs:
- `ROLE_VIEWER` is any holder of the class's token, the class code's anonymous one
  included, and asks no role.
- `ROLE_EDITOR`, `ROLE_ADMIN` and `ROLE_OWNER` ask the linked account's role in the class on
  every request, so a role taken away in the bot is gone here in the same instant.

Every method that acts on the class names one, reads included.

### Errors

A refusal is a `google.rpc.Status`. It carries:
- a canonical code;
- the server's own sentence for a person;
- in its details, a `google.rpc.ErrorInfo` with `domain` `"lessons.app"`, a `reason`, and
  `metadata`.

RPC sends it as the protocol's error. REST sends Google's JSON error body
(`{"error": {"code", "message", "status", "details"}}`) under the standard status:

| Code | HTTP |
| --- | --- |
| `INVALID_ARGUMENT`, `FAILED_PRECONDITION` | 400 |
| `UNAUTHENTICATED` | 401 |
| `PERMISSION_DENIED` | 403 |
| `NOT_FOUND` | 404 |
| `ALREADY_EXISTS` | 409 |
| `RESOURCE_EXHAUSTED` | 429 |
| `UNIMPLEMENTED` | 501 |
| `UNAVAILABLE` | 503 |

The reasons are `ErrorReason` in `errors.proto`: thirty-three of them, each with its code,
its metadata and what v1 sent instead.

**A client acts on the reason, never on the message.** The app's habit of matching
`"device is not linked"` (#270) ends here. A reason a client does not know, it handles by the
code. `VALIDATION_FAILED` names each wrong request field in a `google.rpc.BadRequest`. No
error repeats a request field back: a password or a diary session sent by mistake is never
in a refusal.

### Not in v2, on purpose

- `GET /now`: it served a developer-console preset and nothing else.
- `POST /diary/login`: a password through this server, kept in v1 for APKs from before
  `/diary/session`. Every APK v2 serves opens its diary session itself.
- `/subjects` without ids beside `/manage/subjects`: v2 has one `SubjectService`, with ids,
  readable by a viewer.

The calendar feed itself (`/api/v1/calendar/{token}.ics`), the Telegram webhook, the cron
tick, `/diary/signin/{code}`, `/api/v1/health` and `/api/v1/warmup` stay plain HTTP at their
v1 paths.

### Changing it

The contract grows by addition only: a new field under a new number, a new method, a new
enum value, a new reason. It never renames, renumbers or retypes in place
([the programme](specs/2026-10-03-one-contract-design.md), «Evolving the contract»).

- CI's «Contract» job holds the mechanical half, with Buf's `FILE` breaking rules.
- `server/tests/test_contract.py` holds each method's route, credential, role and
  idempotency against the resource map.
- [build.md](build.md), «The v2 contract and Buf», has the commands.
~~~

- [ ] **Step 3: `docs/README.md` and `CLAUDE.md`.**
  - **`docs/README.md`.** In the row for `api.md`, replace «the whole `/api/v1` contract: » with «the whole `/api/v1` contract, and at its end the v2 contract that nothing serves yet: ».
  - **`CLAUDE.md`, the deliverables block.** It is fenced; insert after the `api/` line:
~~~
proto/       the v2 contract (package lessons.v2), checked and generated by Buf from
             buf.yaml and buf.gen.yaml at the root; its Python is committed under
             server/app/contract/, and nothing serves it yet
~~~
  - **`CLAUDE.md`, «Commands».** Add after the server bullets and before «Android, from `android/`:»:
~~~markdown
Contract, from the repository root, with Buf 1.73.0 fetched by hand (`docs/build.md`, «The
v2 contract and Buf»; the binary never enters the repository):

- `buf lint`: the STANDARD rules, as CI's «Contract» job runs them
- `buf generate`: rewrites `server/app/contract/` from `proto/`. Commit it with the proto
  change, because CI regenerates and fails on any difference
- `python -m pytest -q tests/test_contract.py` (from `server/`): what Buf cannot check.
  Every method's REST binding, credential, least role and idempotency, against the resource
  map of `docs/specs/2026-10-04-contract-v2-design.md`
~~~
  - **`CLAUDE.md`, «Server modules».** Append after the bullet that begins «- `providers/`»:
~~~markdown
- `contract/` — the Python `buf generate` writes from `proto/lessons/v2/`. Never edited by
  hand, since every regeneration deletes and rewrites it. Skipped by ruff and mypy, and
  imported by nothing in `app.main`: `tests/test_contract.py` holds that in a fresh
  interpreter, so the cold start is unchanged until sub-project 3 serves v2
~~~

- [ ] **Step 4: README's «Honest status».** Add a row after the `pytest -q -n auto` row:
  `| buf lint, buf breaking, the generated-code check | CI's «Contract (Buf)» job, only when the contract changes. Its first run was this sub-project's pull request, where breaking was skipped because main had no contract yet |`

- [ ] **Step 5: Run the full suite, and write its numbers everywhere they live.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2/server && /c/Users/lumen/StudioProjects/lessons/server/.venv/Scripts/python.exe -m mypy && /c/Users/lumen/StudioProjects/lessons/server/.venv/Scripts/python.exe -m pytest -q -n auto
```
Expected: `Success: no issues found in 197 source files`, and the passed count 25 above the count before Task 1 (2100 if 2075 was current). Write the printed count, not the expected one, in each place that holds it:
- `CLAUDE.md` («2075 tests in about four minutes»);
- `CONTRIBUTING.md` («2075 of them»);
- `README.md` («2075 tests, green»);
- `docs/architecture.md` («2075 tests on the server»);
- `.claude/skills/gates/SKILL.md` («2075 tests today»);
- `HANDOVER.md`'s cheat-sheet («# 2075 tests»).

`197 modules` stays everywhere, because mypy skips `app/contract`.

- [ ] **Step 6: The HANDOVER close-out** (the `handover` skill). Walk «What goes stale mechanically»:
  1. The section «What the session before it added: …» moves verbatim, retitled «What the batch before added: …», to the top of `docs/history.md` under its introduction. The current «What the last session added: …» becomes «What the session before it added: …».
  2. Above it, write the new section:
~~~markdown
## What the last session added: the v2 contract written down — `proto/lessons/v2`, its Python, and Buf in CI (sub-project 2 of #273)

Sub-project 2 of `docs/specs/2026-10-03-one-contract-design.md`, built from
`docs/specs/2026-10-04-contract-v2-design.md` by the plan beside it, on `contract/v2`, in the
pull request this section rides in, on milestone 11. It adds a contract and serves none of
it: nothing under `/api` answers differently, and v1 is untouched.

- **The contract exists**: `proto/lessons/v2/`, seventeen services in twenty files, seventy-six
  methods. Each method carries its REST route (`/v2/…`, served under `/api` once something
  serves it), its credential (`(lessons.v2.auth)`), its least role on the class
  (`(lessons.v2.min_role)`) and its idempotency. `ErrorReason` has the design's twenty-three
  reasons and ten more, each found in a v1 refusal.
- **Its Python is generated and committed** into `server/app/contract/` by two pinned remote
  Buf plugins. `connectrpc` and `protobuf-py` joined `pyproject.toml`, `requirements.in` and
  the lock, which also gained `protobuf-py-ext`, `pyqwest` and `opentelemetry-api`.
  Ruff and mypy skip the tree.
- **`server/tests/test_contract.py`** holds what Buf cannot. Every method's route, credential,
  role and idempotency are pinned against the design's resource map, row for row. The generic
  rules hold too (standard verbs, `POST …:verb`, path fields that exist, no client streams).
  It also checks that the generated code was written by the pinned plugins and keeps its
  imports inside `app.contract`, and that `app.main` imports none of it, in both deployment
  configurations.
- **CI gained a «Contract (Buf)» job**, run only when the contract changes. It does `buf lint`
  (STANDARD), `buf breaking` against the base (FILE), and regenerates and diffs. It reads no
  secret.

### Gates

Write the numbers from Task 9 Step 5's run:
- ruff clean;
- `python -m mypy` clean, 197 source files;
- `pytest -q -n auto`: the printed count, and the time it took;
- the Contract job's result on the pull request's head, read with `gh pr checks`;
- Android not run, because nothing under `android/` changed.

### What was deliberately left alone

- Kotlin and Java lite: sub-project 5, with the bindings that use them (the design's decision
  3). Every file already carries the Java options.
- Extending `tests/test_service_layering.py` to `rpc/` and `rest/`: those packages do not exist
  yet. The generated tree has its own stricter rule.
- `buf format`: the design does not ask for it, and the action's format step is off.

### What nobody has verified in this batch

- Buf's unauthenticated rate limit in CI.
- `buf breaking` against a base that has a contract: the first run of it is the next pull
  request that touches `proto/`.
- Every behaviour the proto comments describe: they are sub-project 3's handlers to make true.
~~~
  3. **The opening paragraph.** Name this pull request as the only one open, with the number `gh pr view --json number` prints. Say that `main` is at the merge before it, read with `git rev-parse --short origin/main`, and that the schema head did not move.
  4. **The milestone table**: milestone 11's row gains this pull request's number.
  5. **Section 5** gains the three unverified items above.
  6. **Section 7** gains the owner's decisions the plan's Self-review lists:
     - what `RotateCalendarFeed` should mean;
     - the calendar feed's tightening to linked phones;
     - `"HH:MM"` against v1's `"HH:MM:SS"`;
     - `ListAttendance` against `DIARY_FEATURE_ATTENDANCE`;
     - a `BUF_TOKEN` if CI is ever throttled.

- [ ] **Step 7: Gates for the documents, then commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2/server && /c/Users/lumen/StudioProjects/lessons/server/.venv/Scripts/python.exe -m pytest -q -p no:xdist tests/test_schema_version.py tests/test_env_example.py tests/test_contract.py
```
Expected: all pass. Then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/contract-v2 && git add docs/api.md docs/README.md CLAUDE.md README.md CONTRIBUTING.md docs/architecture.md .claude/skills/gates/SKILL.md HANDOVER.md docs/history.md && git commit -F - <<'EOF'
Describe the v2 contract where readers look, and hand the batch over

docs/api.md ends with «v2: the contract»:
- what v2 is, and that nothing serves it yet;
- where it will be served (REST under /api/v2, Connect and gRPC-Web under
  /api/rpc, native gRPC on the host target only);
- what its values look like;
- who may call a method, through the two options;
- the error model;
- what it leaves out on purpose.
The proto files stay the reference, and the section says only what a file
cannot. CLAUDE.md names proto/ and app/contract/ among the deliverables and
modules, and gives the three commands for the contract. The README's honest
status names the new CI job and that its breaking check has not yet
compared anything. The test counts are the full suite's own, in the six
places that carry them.

HANDOVER.md's close-out is written while this pull request is open, so the
file is true when it merges. The batch before moves to docs/history.md.

Not covered: the owner's decisions the plan lists (the calendar feed's
rotation and auth, times without seconds, the attendance names) are
recorded as open, not made.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
git push && gh pr checks --watch
```
- Then update the pull request body's «Verification» block with Step 5's real numbers (`gh pr edit --body-file -`).
- **Merge only under the `github-pr` skill's five checks, and only if the owner has approved the design.** Its status line says «proposed … for the owner's review». Otherwise leave the pull request open as a draft, and say so in the report.

---

## Self-review

**Spec coverage, design section → task:**
- «Delivers»:
  - proto files: Tasks 1–7;
  - `buf.yaml` and `buf.gen.yaml`, remote plugins pinned: Tasks 1–2;
  - generated Python: Tasks 1–7;
  - the two runtime packages in pyproject, requirements.in and the lock: Task 1;
  - the CI job: Task 8;
  - the server tests: Tasks 1–7;
  - `docs/api.md` and `CLAUDE.md`: Tasks 8–9.
- «Does not deliver»: no handler, route or mount, and the cold start is held by `test_the_api_cold_start_imports_no_generated_code`. No Kotlin: `buf.gen.yaml` is Python only. No v1 change: no task edits `app/api` or `app/schemas`.
- Decision 1 (`/api`): `/v2/…` annotations everywhere, and docs in Task 9.
- Decision 2 (protobuf-py, a package): Task 1, with `test_generated_code_stays_inside_app_contract`.
- Decision 3 (Kotlin waits, Java options): every file, and `test_every_file_carries_the_java_options`.
- Decision 4 (dates and times as strings, instants as Timestamp): `common.proto`'s conventions and every field comment.
- Decision 5 (auth and min_role): `options.proto` and four tests.
- Decision 6 (errors): `errors.proto`, and `REASONS` pinned.
- Decision 7 (services and map): Tasks 2–7, and `METHODS` pinned row for row.
- Decision 8 (diary capabilities): Task 6.
- Decision 9 (mirror v1, list renames): «From v1 to v2», and every message's «v1:» comment.
- «Checks»:
  - Buf (version, action, lint, breaking, generate-and-diff, path filter): Task 8;
  - the server tests: Task 1;
  - the rate limit: Task 8's failure message and `docs/build.md`.
- «What is not verified»: Task 9's HANDOVER section 5.
- «Tracking»: milestone 11, `Refs #273`, and #268/#269/#270 left open, in Task 8's pull request body.

**Verified while writing:**
- Every v1 schema, route and refusal, and every test that walks `app/`, read in the repository.
- The Buf release, its assets and checksums; the plugin versions and their `buf.plugin.yaml`; the PyPI versions and dependencies; buf-action's inputs and defaults from its source.
- protobuf-py's generated layout and descriptor API, from its documentation, its API reference and connect-py's committed generated code.
- The spike's googleapis commit, from Buf's local cache.

**Not verified, because this agent may not create files or run tools:**
- Any Buf command.
- That the protos compile and lint clean, in particular that the `ignore_only` path form is right.
- The exact generated tree and its imports.
- That every attribute `test_contract.py` reads exists as written: `rule.pattern.field` and `.value`, `file.proto.options`, `message.proto.field`, `type_name` being `''` for a scalar.
- The lock's exact new pins.
- That `buf breaking` fails against an empty base.

Task 0 runs all of these, and its stop rule forbids proceeding on a mismatch.

**Where the code or the tooling contradicts the design, plainly:**
1. **Times.** The design says times are `"HH:MM"`, «exactly as v1 sends them». v1 sends `"08:30:00"` (Pydantic serialises `datetime.time` with seconds; `docs/api.md` shows it). The plan follows the design's format and says the difference out loud. Sub-project 3 formats with `%H:%M`, and sub-project 5's DTOs change.
2. **AIP against Buf STANDARD.** AIP-131/133/134 return the bare resource. Buf's `RPC_RESPONSE_STANDARD_NAME` and `RPC_REQUEST_RESPONSE_UNIQUE`, which the design adopts, refuse that. The plan wraps every answer (`GetSubjectResponse{subject}`), with no `except`.
3. **Error reason names.** The design wants them to be the reason strings themselves. That violates `ENUM_VALUE_PREFIX`. The plan takes one narrow `ignore_only`, for one rule in one file, with its reason in `buf.yaml`, as Google's own `error_reason.proto` does.
4. **`SearchSchools` on `GET /v2/schools`.** This breaks the design's own test rule (custom methods are `POST …:verb`). Renamed `ListSchools`.
5. **The diary's `ListHomework` and `ListSubjects`.** They collide with the class services' request and response message names in one package. Renamed `ListDiaryHomework` and `ListDiarySubjects`; paths unchanged.
6. **«On the first pull request, `buf breaking` compares against nothing and passes».** buf-action's default against-input is the base commit, and with no `.proto` there Buf most likely fails (inference). The plan skips the check with a notice when the base has no `buf.yaml`, which keeps the design's «says rather than hides».
7. **Calendar feed authentication.** v1's `GET /calendar` lets any device, anonymous ones included, read and mint the class's feed. The design makes both feed methods `DEVICE_LINKED`, which tightens it.
8. **`RotateCalendarFeed`.** The name says rotate. The plan's comment keeps v1's mint-when-absent, because the feed is class-wide and any linked viewer could otherwise cut every subscriber. The owner must decide before merge, because changing a method's meaning later is a breaking change by another name.
9. **v1 reads that write.** v1's `/bundle` seeds terms and adopts subjects, `/manage/subjects` adopts, and `/manage/terms` seeds; every request also touches `last_seen_at`. The contract marks every Get and List `NO_SIDE_EFFECTS`, so sub-project 3 must move those writes off the reads. Telemetry like `last_seen_at` is arguably not a side effect a client observes.
10. **`ListAttendance`.** It answers v1's turnstile records, while `DiaryFeature` has both `ATTENDANCE` and `TURNSTILE`. The plan maps the method to `TURNSTILE`, and leaves `ATTENDANCE` without a method.
11. **Dependencies.** The design lists `connectrpc`, `protobuf-py`, `protobuf-py-ext` and `pyqwest`. `pyqwest` 0.11.0 also requires `opentelemetry-api` (from PyPI; inference about what uv resolves).
12. **Approval.** The design's status line says «proposed … for the owner's review», while this plan was commissioned against an «approved design». The merge is therefore gated on the owner.
13. **Smaller completions.**
    - `DIRECTORY_SPENT` gains `retry_after_seconds`, which v1 sends.
    - `DIARY_CREDENTIALS_REJECTED` is mapped to v1's 409 «refused from here», because v2 has no password sign-in to refuse (inference).
    - Proto3 JSON is lowerCamelCase, which will change v1's snake_case on the wire unless sub-project 3's transcoder writes proto names.
    - `connectrpc/py` v0.12.1's plugin metadata names `bufbuild/py:v0.4.0` as a dependency while the plan pins v0.6.0. That should not matter to `buf generate` (inference), and Task 0 shows whether it does.

### Critical Files for Implementation
- C:\Users\lumen\StudioProjects\lessons\.claude\worktrees\spec-one-contract\docs\specs\2026-10-04-contract-v2-design.md
- C:\Users\lumen\StudioProjects\lessons\.claude\worktrees\spec-one-contract\server\pyproject.toml
- C:\Users\lumen\StudioProjects\lessons\.claude\worktrees\spec-one-contract\requirements.in
- C:\Users\lumen\StudioProjects\lessons\.claude\worktrees\spec-one-contract\.github\workflows\ci.yml
- C:\Users\lumen\StudioProjects\lessons\.claude\worktrees\spec-one-contract\server\tests\test_requirements_mirror.py
