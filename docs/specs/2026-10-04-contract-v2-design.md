# Sub-project 2: the v2 contract

Status: **approved by the owner on 4 October 2026**; amended the same day by what writing its
plan found (decision 10 and the renames in decision 7, each with its reason), and again after its final review (`ScheduleWindow.next_school_day` dropped, field 4 reserved: a window is a school year, so 21 days past it is June and the field could never be filled). This is the detailed design of
sub-project 2 of `2026-10-03-one-contract-design.md` (the programme; read its section 1 first —
this document settles what that section left as a draft and does not repeat its reasons). It
delivers the contract and nothing that serves it: the proto files, their generated Python code,
the checks that keep both honest, and the documents. Sub-project 3 serves it; sub-project 5 puts
it on the phone.

## What this sub-project delivers, and what it does not

**Delivers:**
- `proto/lessons/v2/*.proto` — every v2 service, one file per service, plus the shared files
  (`common.proto`, `errors.proto`, `options.proto`);
- `buf.yaml` and `buf.gen.yaml` at the repository root, every remote plugin pinned;
- the generated Python, committed under `server/app/contract/`;
- the two runtime packages the generated code imports, in `server/pyproject.toml`,
  `requirements.in` and the lock;
- a CI job that lints the proto, checks it for breaking changes against `main`, and fails when
  the committed generated code is not what the proto generates;
- server tests that hold what Buf cannot: every method's REST binding, auth requirement and
  idempotency marking (below);
- a «v2: the contract» section in `docs/api.md`, and CLAUDE.md's description of the new
  directories.

**Does not deliver:** any handler, route or mount (sub-project 3); any Kotlin (sub-project 5 —
see «Kotlin waits»); any change to v1. Nothing here is reachable from the deployed server: the
generated modules are imported by nothing in `app.main`, so the cold start is unchanged and
`tests/test_contract.py` holds it, in a fresh interpreter.

## Decisions this document makes

### 1. Where v2 lives on the wire: under `/api`

`vercel.json` has no rewrites: the deployment is one function, `api/index.py`, and every path that
works today is under `/api`. The programme's `/v2/…` and `/rpc/…` would sit outside it and would
need routing nobody has tried. So:
- **REST v2** is served under `/api/v2/…`. The `google.api.http` annotations in the proto say
  `/v2/…`, the conventional form, and the transcoder (sub-project 3) mounts them under `/api`.
- **Connect, gRPC-Web and native gRPC** are mounted at `/api/rpc`: a method is
  `POST /api/rpc/lessons.v2.<Service>/<Method>` (the spike proved `connectrpc` strips a mount's
  prefix). On the host target, native gRPC clients address the same prefix.

### 2. The Python runtime: protobuf-py, generated into a package

The generated code is committed inside the app package, `server/app/contract/`. Google's
`protobuf` code generator writes absolute imports named after the proto package
(`from lessons.v2 import common_pb2`), which cannot live inside `app.contract` without a second
top-level package. Buf's `protobuf-py` runtime writes relative imports and was the one the spike
ran end to end — `connectrpc` mounted in the real app, `google.api.http` read from its
descriptors, canonical proto3 JSON identical to Google's. It is `connectrpc`'s default. It is
beta (0.6.0); the cost of leaving it later is regenerating the committed code with another
plugin, because nothing hand-written imports a runtime-specific name except the transcoder.

Packages added (versions verified on PyPI on 3 October 2026, pinned through the lock as every
other dependency is): `connectrpc` 0.12.1, `protobuf-py` 0.6.0, and what they pull in
(`protobuf-py-ext`, `pyqwest`). `pyqwest` is a 16 MB Rust HTTP client that `connectrpc` requires
even on the server; it adds to the bundle, far under Vercel's 500 MB, and nothing imports it on
the cold path.

### 3. Kotlin waits for sub-project 5

The spike showed what Kotlin brings into the APK: the javalite keep rule, `kotlin-reflect` at a
version behind the standard library, +611 KB. Committing generated Kotlin into `:core:data` now
would put those dependencies into every build for a year's worth of nothing using them. So
`buf.gen.yaml` generates Python only; sub-project 5 adds the Kotlin and Java lite targets
together with the bindings that use them and the dependency decision the programme describes.
The proto files already carry `java_package` and `java_multiple_files`, so that addition changes
no proto.

### 4. Dates and times are strings, as in v1

Time here is **naive local wall time in the class's zone** (CLAUDE.md, «What will bite you»).
`google.protobuf.Timestamp` is an instant in UTC, which is the wrong type for «08:30 on a school
day», and `google.type.Date` is an object of three numbers in JSON. A date is
`string … // "YYYY-MM-DD"` and a time `string … // "HH:MM"`, and every such field's comment says
so. (v1 sends times with seconds, `"08:30:00"`, because Pydantic serialises `datetime.time` that
way; v2 drops the seconds nothing uses, and sub-project 3 formats with `%H:%M`.) Server-side instants that really are instants (an audit entry's
moment, a token's expiry) are `google.protobuf.Timestamp`.

### 5. Who may call a method is in the contract

`options.proto` declares two method options:
- `(lessons.v2.auth)`: `NONE`, `DEVICE` (the class device token), `DEVICE_LINKED` (a device
  token whose phone is linked to an account), `DIARY` (the diary session token);
- `(lessons.v2.min_role)`: `VIEWER`, `EDITOR`, `ADMIN`, `OWNER` — read only when `auth` is a
  device kind. `VIEWER` means any holder of the class's device token, the class code's
  anonymous read-only token included, and checks no role; the other three ask
  `linking.effective_role` on every request, as v1 does, so an unlinked phone reaches none of
  them.

Every method sets `auth`; every class-writing method sets `min_role`. A server test fails on a
method without them. Sub-project 3's RPC layer enforces them in one place, from the descriptor,
so a new method cannot be added without saying who may call it — and the two bearer tokens
(CLAUDE.md, «There are two independent bearer tokens») are a property of the method, not of a
path prefix.

### 6. Errors: one enum of reasons

`errors.proto` declares `enum ErrorReason`, whose names are the `ErrorInfo.reason` strings the
server sends (domain `lessons.app`); the enum exists so that both sides get generated constants
rather than retyping strings. The first set, from every refusal v1 makes today (the plan reads
each `HTTPException` and each service exception to complete it):

| Reason | Code | Metadata | Replaces in v1 |
| --- | --- | --- | --- |
| `DEVICE_TOKEN_INVALID` | `UNAUTHENTICATED` | | the device 401 |
| `DIARY_TOKEN_INVALID` | `UNAUTHENTICATED` | | the diary 401 |
| `DEVICE_NOT_LINKED` | `PERMISSION_DENIED` | | «device is not linked» |
| `ROLE_REQUIRED` | `PERMISSION_DENIED` | `role` | «<role> role required» |
| `JOIN_CODE_UNKNOWN` | `NOT_FOUND` | | `/join` 404 |
| `CLASS_INVITE_ONLY` | `PERMISSION_DENIED` | | `/join` 403 |
| `DEVICE_LIMIT_REACHED` | `RESOURCE_EXHAUSTED` | `limit` | `/join` 409 (#199) |
| `THROTTLED` | `RESOURCE_EXHAUSTED` | `retry_after_seconds` | every 429 |
| `RESOURCE_NOT_FOUND` | `NOT_FOUND` | `resource` | every other 404 |
| `RESOURCE_EXISTS` | `ALREADY_EXISTS` | `resource`, `field` | «Предмет … уже есть» and its kin |
| `VALIDATION_FAILED` | `INVALID_ARGUMENT` | — (`BadRequest` field violations) | 422s |
| `NO_BELL_FOR_LESSON` | `FAILED_PRECONDITION` | `index` | the bell rule (CLAUDE.md) |
| `EMPTY_BELL_SCHEDULE` | `FAILED_PRECONDITION` | | a day pointed at an empty schedule |
| `DIARY_DISABLED` | `UNAVAILABLE` | | no `DIARY_SECRET` |
| `DIARY_UNAVAILABLE` | `UNAVAILABLE` | `upstream` | `X-Diary-Unavailable` |
| `DIARY_REAUTH` | `UNAUTHENTICATED` | | `X-Diary-Reauth` |
| `DIARY_CREDENTIALS_REJECTED` | `PERMISSION_DENIED` | | a refused sign-in |
| `DIRECTORY_DISABLED` | `UNAVAILABLE` | | no `DADATA_TOKEN` |
| `DIRECTORY_SPENT` | `RESOURCE_EXHAUSTED` | | the day's DaData share spent |
| `DIRECTORY_UNAVAILABLE` | `UNAVAILABLE` | | `X-Directory-Unavailable` |
| `CLIENT_TOO_OLD` | `FAILED_PRECONDITION` | `min_version` | (new) |
| `FEATURE_UNSUPPORTED` | `UNIMPLEMENTED` | `feature` | (new) |
| `REQUEST_UNDECODABLE` | `INVALID_ARGUMENT` | | (new: the spike's `500`) |

A reason is added, never renamed or reused (the programme's «Evolving the contract»).

### 7. The services and the final resource map

One file and one service per area. Paths are the annotations (served under `/api`). `{x}` is a
path field; «auth/role» is decision 5. Every `Get` and `List` that changes nothing is marked
`idempotency_level = NO_SIDE_EFFECTS`.

| Service | Method | HTTP | auth / role |
| --- | --- | --- | --- |
| `DeviceService` | `CreateDevice` (the class code or a personal code in the body; answers the token) | `POST /v2/devices` | NONE |
| `MeService` | `GetMe` | `GET /v2/me` | DEVICE |
| | `UnlinkMe` | `POST /v2/me:unlink` | DEVICE |
| | `CreateLinkCode` | `POST /v2/me/linkCodes` | DEVICE |
| | `GetCalendarFeed` (never mints) | `GET /v2/me/calendarFeed` | DEVICE_LINKED |
| | `CreateCalendarFeed` (mints when absent, answers the existing feed when present — no rotation: the feed is the class's, and rotating it would cut every subscriber) | `POST /v2/me/calendarFeed` | DEVICE_LINKED |
| | `ListTasks` · `GetTask` · `CreateTask` · `UpdateTask` · `DeleteTask` | `/v2/me/tasks[/{task_id}]` | DEVICE_LINKED |
| | `CreateHomeworkTick` · `DeleteHomeworkTick` | `POST /v2/me/homeworkTicks` · `DELETE /v2/me/homeworkTicks/{homework_id}` | DEVICE_LINKED |
| `ScheduleService` | `GetScheduleWindow` (`if_none_match` → `not_modified`) | `GET /v2/class/scheduleWindows/{year}` | DEVICE |
| `ClassService` | `GetClass` · `UpdateClass` (field mask) | `GET` · `PATCH /v2/class` | DEVICE · ADMIN |
| | `DeleteClass` (`confirmation` as a query field — no body on DELETE) | `DELETE /v2/class` | DEVICE · OWNER |
| | `GetClassStats` | `GET /v2/class/stats` | DEVICE · EDITOR |
| | `GetTermScheme` · `UpdateTermScheme` · `ListTerms` · `UpdateTerm` | `/v2/class/termScheme`, `/v2/class/terms[/{index}]` | DEVICE · ADMIN |
| `SubjectService` | the standard five | `/v2/class/subjects[/{subject_id}]` | DEVICE · VIEWER to read, ADMIN to write |
| `BellService` | the standard five (periods are a field) | `/v2/class/bellSchedules[/{schedule_id}]` | DEVICE · ADMIN |
| `TimetableService` | `GetTimetable` · `ImportTimetable` (`validate_only` is the preview) | `GET /v2/class/timetable` · `POST /v2/class/timetable:import` | DEVICE · ADMIN |
| `HomeworkService` | the standard five; `List` by date range | `/v2/class/homework[/{homework_id}]` | DEVICE · VIEWER to read, EDITOR to write |
| `SubstitutionService` | the standard five | `/v2/class/substitutions[/{substitution_id}]` | DEVICE · EDITOR |
| `EventService` | the standard five — `Create` is a POST (#268) | `/v2/class/events[/{event_id}]` | DEVICE · EDITOR |
| `DayService` | `GetDay` · `UpdateDay` (`allow_missing`: an idempotent upsert by date) | `/v2/class/days/{date}` | DEVICE · EDITOR |
| `ClassDeviceService` | `ListClassDevices` · `RevokeClassDevice` · `UnlinkClassDevice` | `/v2/class/devices[/{device_id}:revoke\|:unlink]` | DEVICE · ADMIN |
| `AccessRequestService` | `ListAccessRequests` · `ApproveAccessRequest` · `DeclineAccessRequest` | `/v2/class/accessRequests[/{request_id}:approve\|:decline]` | DEVICE · ADMIN |
| `AuditService` | `ListAuditEntries` (`page_token`) | `GET /v2/class/auditEntries` | DEVICE · ADMIN |
| `DirectoryService` | `ListSchoolRegions` (the order is the contract) | `GET /v2/schoolRegions` | NONE |
| | `ListSchools` (a `query` and `page_token`; a List with a query, because a «Search» on a GET is neither a standard method nor a `POST …:verb`) | `GET /v2/schools` | DEVICE · ADMIN |
| `DiaryService` | `GetDiaryCapabilities` (decision 8) | `GET /v2/diary/capabilities` | NONE |
| | `CreateDiarySession` (a session the phone opened; `201`) · `DeleteDiarySession` (`current`) | `POST /v2/diary/sessions` · `DELETE /v2/diary/sessions/current` | NONE · DIARY |
| | `ListStudents` | `GET /v2/diary/students` | DIARY |
| | `ListScheduleDays` · `ListDiaryHomework` · `ListMarks` · `ListPeriods` · `ListDiarySubjects` · `ListTeachers` · `ListTurnstileEvents` | `GET /v2/diary/students/{student_id}/…` (`scheduleDays`, `homework`, `marks`, `periods`, `subjects`, `teachers`, `turnstileEvents`) | DIARY |
| | `ListCorrections` · `BatchUpdateCorrections` · `ResetCorrections` · `ClearCorrections` | `/v2/diary/students/{student_id}/corrections[:batchUpdate\|:reset\|:clear]` | DIARY |
| `WatchService` (beta) | `WatchClass` — server streaming, **no REST binding** | — | DEVICE |

Renames that Buf's `STANDARD` lint forces, decided while writing the plan: the diary's
`ListHomework` and `ListSubjects` become `ListDiaryHomework` and `ListDiarySubjects`, because one
package cannot hold two `ListHomeworkRequest`s; and every method answers a `<Method>Response`
that wraps the resource (`GetSubjectResponse{subject}`), where AIP returns the bare resource,
because `RPC_RESPONSE_STANDARD_NAME` and `RPC_REQUEST_RESPONSE_UNIQUE` require it — which is also
where per-write facts live (`silenced_lessons` on a bell write). The diary's turnstile records are
`ListTurnstileEvents` (`DiaryFeature.TURNSTILE`), so that `ATTENDANCE` stays free for absences
and lateness when a method reads them.

`ListHomework`, `ListSubstitutions` and `ListEvents` default to a 21-day range and refuse more than
62 days: those are v1's homework limits, and v1 had no list of substitutions or events to take
limits from.

What v1 has that v2 does not, on purpose:
- `GET /now` — a developer-console preset and nothing else; the console will read v2 like any client.
- `POST /diary/login` — a password through this server, kept in v1 for APKs built before
  `/diary/session`; every APK v2 serves opens its diary session itself.
- `/subjects` without ids beside `/manage/subjects` — one `SubjectService` with ids, readable by
  a viewer.

### 8. The diary's capabilities, for every platform

`DiaryCapabilities { bool enabled; repeated ProviderCapabilities providers; }` and
`ProviderCapabilities { string provider; repeated string regions; repeated SignInMethod
sign_in_methods; repeated DiaryFeature features; }`. `provider` is a string key, not an enum,
so a provider added on the server reaches the phone without a proto change (the programme's
«Growing the diary»). `DiaryFeature` (`SCHEDULE`, `HOMEWORK`, `MARKS`, `PERIODS`, `SUBJECTS`,
`TEACHERS`, `ATTENDANCE`, `TURNSTILE`, `MEAL_ACCOUNT`, `FINAL_MARKS`, …) and `SignInMethod`
(`PASSWORD`, `SESSION_ADOPT`, …) are enums: a new feature is a new value. In binary protobuf an
old client keeps an unknown value as a number; in canonical JSON it arrives as a name the client
has never seen, so a client decodes enum names leniently, elements of a list included, and drops
what it does not know. A new platform's credential is one more case of the `credential` oneof of
`CreateDiarySessionRequest`, the one contract change a platform makes. A diary method whose feature a provider lacks answers
`UNIMPLEMENTED` / `FEATURE_UNSUPPORTED`.

### 9. Field names and messages mirror v1 where v1 was right

Messages are derived from v1's Pydantic schemas (`server/app/schemas/`), field by field, so that
sub-project 3's handlers map one to the other mechanically and sub-project 5's REST DTOs change
the least. Where v1 used a name that v2's rules reject (a verb, a plural mismatch, an id in a
body that the path carries), the plan lists the rename. Every enum starts with
`<NAME>_UNSPECIFIED = 0`.

### 10. JSON on the wire is canonical proto3 JSON

REST v2 and Connect's JSON both write canonical proto3 JSON: field names in lowerCamelCase
(`startDate`, `ifNoneMatch`), `int64` as strings, enums by name. Both parsers accept the proto
field names too. This is a change from v1's snake_case, made once, for one rule on both
transports; sub-project 3's transcoder writes it, and sub-project 5's REST DTOs read it.

### Reads stop writing

v1 has reads that write: `/bundle` seeds terms and adopts subjects, `/manage/subjects` adopts,
`/manage/terms` seeds. The contract marks every `Get` and `List` `NO_SIDE_EFFECTS`, so
sub-project 3 moves those writes off the reads (to the writes that make them necessary, or to
the class's creation). Touching a device's `last_seen_at` on every request is telemetry no client
observes, and stays.

## Checks

- **Buf** (`bufbuild/buf-action` v1.6.0 in CI, Buf v1.73.0, both pinned): `buf lint` with the
  `STANDARD` rules; `buf breaking --against` the base branch with the `FILE` category — on the
  first pull request, where `main` has no proto, it compares against nothing and passes, which
  the job says rather than hides; and `buf generate` into a temporary directory diffed against
  `server/app/contract/` — a difference fails the job with the command to run. The job runs when
  `proto/`, `buf.*` or `server/app/contract/` changes («What changed» learns those paths).
- **Server tests** (`tests/test_contract.py`), over the generated descriptors, so they need no
  network: every method has an `auth` option and, if it writes the class, a `min_role`; every
  unary method has exactly one `google.api.http` rule under `/v2/`, standard methods on their
  standard verbs, custom methods as `POST …:verb`; every streaming method has none; every
  side-effect-free `Get`/`List` is `NO_SIDE_EFFECTS`; every generated module imports; and
  `app.main` still imports none of them (the cold-start test already covers aiogram; this one
  adds `app.contract`).
- **Buf's remote plugins and their rate limit.** CI uses unauthenticated remote plugins, as the
  spike did. If Buf ever throttles it, a `BUF_TOKEN` secret is the owner's step; the job says so
  in its failure.

## What is not verified, and what could change the plan

- Buf's unauthenticated rate limit in CI has not been met; a token would be needed if it is.
- The generated Python has never been imported on Linux CPython 3.12 (the spike ran on Windows,
  3.13); CI will be the first.
- `protobuf-py` is beta. If a release breaks the generated code, the fallback is Google's
  `protobuf` with the generated tree placed so its absolute imports resolve — a regeneration,
  not a redesign.

## Tracking

Milestone 11, under epic #273. Defects this sub-project touches but does not close: #268 closes
when v2 is served and v1's `PUT /events` is gone (sub-projects 3 and 5); #269 is plain HTTP, not
the contract; #270 closes on the phone (sub-project 5).
