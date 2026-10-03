# One contract: REST v2, Connect and native gRPC, decomposition, and a build console

Status: **agreed with the owner on 3 October 2026, not yet built.** This is the design of a
programme of six sub-projects. Each sub-project gets its own detailed specification and its own
implementation plan before any of its code is written; this document fixes what they share and
the order they go in. A feasibility spike (sub-project 0) runs in parallel with the review of
this document, and what it finds amends the sections marked *spike decides*.

## What the owner asked for, and what they decided

The request was: break up the files that do too many things, make the API RESTful, bring in
gRPC — and plan first. The decisions, in the owner's own answers:

| Question | Decision |
| --- | --- |
| Why gRPC | a single typed contract, clients other than this app (iOS, web, third parties), and learning the stack; push/streaming as a **beta** option chosen at build time, which needs a host Vercel is not and can only be tested synthetically for now |
| Where it runs | **both**, chosen at build time: (1) Vercel + Connect, (2) a second, long-running host with native gRPC |
| Which contract the app uses | **both in parallel**: REST v2 and RPC over the same services |
| Installed APKs | only the owner's and their family's — **v1 may be broken** once the new APK is on those phones |
| How REST and RPC share the contract | **approach A**: proto is the source, REST is transcoded from `google.api.http` annotations, one handler per method |
| A build console | local only, a Textual TUI, running builds and gates, the contract tools, deployment, and the emulator or phone, with live logs, the environment variables a task needs, and the documentation |
| Order | spike → server decomposition → contract → server shells and targets → Android decomposition → Android transports → console |

## What the survey of the code found

Four read-only surveys were made before this design; their findings are the reason for several
decisions below, so the ones that matter are kept here.

**The API.** 71 route registrations (72 operations: the cron tick answers GET and POST). The
current APK calls 45; 26 have no client anywhere in the repository's history (tasks, homework
reads and ticks, `/subjects`, `/now`, `/calendar`, all of `edit.py`, terms). What is wrong is not
verbs in paths — `/join` and `/diary/session` are credential exchanges, and that is a fine thing
for a path to say — but:

- **no machine-readable error.** Errors are FastAPI's `{"detail": str}`, and the app branches on
  the exact English strings `"device is not linked"` and `"<role> role required"`
  (`android/core/data/.../repository/ManageModels.kt`). Other signals ride in headers
  (`X-Diary-Unavailable`, `X-Diary-Reauth`, `X-Directory-Unavailable`);
- **`PUT /api/v1/events` always inserts** and answers `201` — a retried PUT duplicates an event;
- **GETs that write**: `/me` mints a link code, `/calendar` mints the feed secret,
  `/manage/terms` seeds and commits, and `GET /cron/tick` sends Telegram messages;
- one resource under several identities: homework is upserted by a natural key in the body,
  deleted by id and ticked by a POST; «overrides» means substitutions in one family and diary
  corrections in another;
- creates answer `201` or `200`, deletes `204` or `200` with a body, and nothing sends `Location`;
- `DELETE /manage/class` carries a body;
- there is no way to retire a client: `api_version` has been `1` since the first release and
  nothing reads it.

**The files.** In this codebase 40–60 % of a long file is comments explaining why, so line counts
overstate the problem. Files that really hold several unrelated concerns: on the server
`api/public.py`, `bot/handlers/start.py`, `bot/handlers/content.py`, `api/diary.py`,
`bot/render.py` and `bot/keyboards.py`, and in part `services/diary.py`; on Android
`SettingsViewModel`, `HomeShell`, `GithubRepositoryImpl`, `LessonsPreferences`,
`ManagementViewModel` and `OnboardingScreen`. Long but cohesive, and left alone: `models.py`
(deliberately one file), `schedule.py`, `api/edit.py`, `DiaryViewModel`, the widget bodies, the
provider clients.

**gRPC on Vercel.** Native gRPC cannot be served by this deployment. Vercel's Python runtime runs
the ASGI app under Uvicorn with HTTP/1.1 only and passes no response trailers, and gRPC carries
its status in trailers; Vercel's own page lists gRPC as unsupported and points at Connect. The
Connect protocol needs no trailers, works over HTTP/1.1 POST, and a Connect server also answers
gRPC-Web. Hence the two targets.

## 1. The contract

**Proto is the single source of truth.** `proto/lessons/v2/*.proto` at the repository root, one
file per service, linted and checked for breaking changes by Buf (`buf.yaml`, `buf.gen.yaml` at
the root). Resources follow Google's API Improvement Proposals, which is what "RESTful" means for
an RPC contract: standard methods (`Get`, `List`, `Create`, `Update`, `Delete`) map to the HTTP
verbs, anything else is a custom method `POST …/{name}:verb` (AIP-136), and each method carries a
`google.api.http` annotation that is the REST route.

**Generated code is committed**, because Vercel runs no code generation: Python under
`server/app/contract/`, Kotlin and Java lite under `android/core/data/src/main/contract/` (path
*spike decides*). Ruff, mypy and detekt skip those directories. CI regenerates and fails on any
difference, so a proto change without its generated code cannot merge.

**One package, one version:** `lessons.v2`. REST lives under `/v2/…`, RPC under
`/lessons.v2.<Service>/<Method>`. Bumping to `v3` is a new package, never an edit of this one —
Buf's breaking check holds that.

**The class is implied by the token**, as today: `class` is a singleton resource, not
`classes/{id}`. A phone acts on its own class only, and a resource name that pretends otherwise
would invite a check that is not there.

### The resource map (draft — the contract sub-project finalises it)

| Area | Resource | Methods | Replaces |
| --- | --- | --- | --- |
| join | `devices` | `Create` (the code in the body; `201`; returns the device token) | `POST /join` |
| me | `me` | `Get`; `:unlink` | `GET /me`, `POST /me/unlink` |
| me | `me/linkCodes` | `Create` | the code `GET /me` minted as a side effect |
| me | `me/calendarFeed` | `Get` (never mints); `:rotate` (mints) | `GET /calendar` |
| me | `me/tasks/{task}` | standard five; `done` is a field, set by `Update` | `/tasks…`, `/tasks/{id}/done` |
| me | `me/homeworkTicks/{homework}` | `Create`, `Delete` | `POST /homework/{id}/done` |
| schedule | `class/scheduleWindows/{year}` | `Get`, with an entity tag (below) | `GET /bundle` |
| class | `class` | `Get`, `Update` (field mask), `Delete` (confirmation as a request field, no body on DELETE) | `/manage/class` |
| class | `class/subjects/{subject}` | standard five | `/subjects`, `/manage/subjects…` |
| class | `class/bellSchedules/{schedule}` | standard five; periods are a field | `/manage/bells…`, `PUT …/periods` |
| class | `class/timetable` | `Get`; `:import` with `validate_only` for the preview (AIP-163) | `/manage/timetable…` |
| class | `class/homework/{homework}` | standard five; `List` filters by date range | `PUT /homework`, `DELETE /homework/{id}`, `GET /homework` |
| class | `class/substitutions/{substitution}` | standard five | `PUT /overrides` |
| class | `class/events/{event}` | standard five — `Create` is a POST, so a retry can be told apart | `PUT /events` (the defect) |
| class | `class/days/{date}` | `Get`, `Update` with `allow_missing` (an idempotent upsert by date) | `PUT /days` |
| class | `class/devices/{device}` | `List`; `:revoke`, `:unlink` | `/manage/devices…` |
| class | `class/accessRequests/{request}` | `List`; `:approve`, `:decline` | `/manage/requests…` |
| class | `class/auditEntries` | `List` with `page_token` (AIP-158) | `GET /manage/log` |
| class | `class/stats`, `class/termScheme`, `class/terms/{index}` | `Get` / `Update` — `Get` never writes | `/manage/stats`, `/manage/terms…` |
| diary | `diary/sessions` | `Create` (`201`); `Delete` of `current` | `POST /diary/session`, `/diary/login`, `/diary/logout` |
| diary | `diary/capabilities` | `Get` | same |
| diary | `diary/students/{student}` and its `schedule`, `homework`, `grades`, `periods`, `subjects`, `teachers`, `attendance` | `List`/`Get` | `GET /diary/students/{id}/…` |
| diary | `diary/students/{student}/corrections` | `List`, `Update`; `:reset`, `:clear` | `…/overrides`, `…/overrides/reset`, `…/overrides/all` |
| directory | `schoolRegions` | `List` (the order is the contract) | `GET /directory/school-regions` |
| beta | `class:watch` | server-streaming, host target only | — |

**Pagination** is one scheme everywhere a list can grow: `page_size`, `page_token`,
`next_page_token` (AIP-158). The school search and the audit log, which today have two different
schemes, both move to it.

**What stays plain HTTP, outside the contract, at its current path:** the calendar feed
`/api/v1/calendar/{token}.ics` (the path is the credential and lives in people's calendar
settings), the Telegram webhook, `/api/v1/cron/tick` (POST; the GET is kept until the external
cron is reconfigured, which is the owner's step in #120), `/diary/signin/{code}` (a link the bot
sends), `/api/v1/health` and `/api/v1/warmup` (probes and the deploy checklist read them).

### Errors

A refusal in `services/` is already an exception carrying facts rather than a sentence. **One
module** maps each such exception to a `google.rpc.Status`: a canonical code plus an
`ErrorInfo` whose `reason` is a stable machine name — `DEVICE_NOT_LINKED`, `ROLE_REQUIRED` (with
`metadata.role`), `CLASS_INVITE_ONLY`, `DIARY_UNAVAILABLE`, `DIARY_REAUTH`,
`DIRECTORY_UNAVAILABLE`, `CLIENT_TOO_OLD`, … — and a human message. RPC sends it as the
protocol's error; REST sends the standard code-to-status mapping (`PERMISSION_DENIED` → 403,
`ALREADY_EXISTS` → 409, `RESOURCE_EXHAUSTED` → 429, …) with Google's JSON error body. The phone
reads `reason`, never the message. The message stays the shell's own words, as the bot's are.

**No error ever echoes a request field.** Today `_NoEchoRoute` strips `input` from the diary
session's 422s so a password is not sent back; the transcoder and the RPC layer hold that for
every method, and a test sends a password-shaped field to every method and asserts it is absent
from every error.

### Caching the year window

`GetScheduleWindow` takes `if_none_match` and answers `not_modified: true` with no body when the
tag matches. That works the same over Connect, gRPC-Web and native gRPC. The REST transcoder maps
the field to `If-None-Match` and `not_modified` to a real `304` with an `ETag`, so REST clients and
HTTP caches see ordinary HTTP. The tag is computed as today; the per-year cache on the phone does
not change.

### Retiring clients

Every request carries `X-Lessons-Client: <versionCode>`. Below a server-side minimum the answer
is `FAILED_PRECONDITION` with reason `CLIENT_TOO_OLD` and `metadata.min_version`, and the app shows
«обновите приложение». Unset, the minimum is zero. This is the mechanism v1 never had, and it is
what a third-party client will need.

## 2. The server

```
proto/lessons/v2/        the contract (repository root)
server/app/contract/     generated code — not edited, not linted
server/app/rpc/          one module per service: THE handler of every method, over services/
server/app/rpc/errors.py the one exception → Status mapping
server/app/rpc/auth.py   device and diary bearers, roles — the same rules as api/deps.py
server/app/rest/         the transcoder: annotations → routes → the same handlers
server/app/host.py       the second target's entry point
```

**One handler per method.** An `rpc/` module implements the generated service interface by
calling `services/`, exactly as `api/manage/` does today. The transcoder reads the
`google.api.http` options from the generated descriptors at import time, builds one route per
method, binds the path parameters and the body into the request message, calls the same handler,
and writes canonical proto3 JSON. REST and RPC therefore cannot disagree about a rule; the only
thing that can differ is the transcoding, and that is tested once, generically. Whether the
annotations are readable at runtime from the generated code is *spike decides*; if not, the
transcoder reads a route table emitted at generation time instead.

**A request scope per call.** Each RPC call opens a dishka `REQUEST` scope from the app
container — the way the bot's `ContextMiddleware` does, not through `setup_dishka` — and commits
on success, so a method behaves exactly as the endpoint it replaces.

**The two targets are two entry points, not a setting.**

| | Vercel (`api/index.py`, unchanged mechanism) | Host (`python -m app.host`) |
| --- | --- | --- |
| server | Vercel's Uvicorn, HTTP/1.1 | an ASGI server with HTTP/2 and trailers — `pyvoy`, or `hypercorn` (*spike decides*) |
| REST v2 | yes | yes |
| Connect, gRPC-Web | yes, unary | yes, unary and server-streaming |
| native gRPC | no | yes |
| streaming (beta) | no | yes, with `LESSONS_STREAMING=true` |
| packaging | Vercel's builder, `requirements.txt` lock | a `Dockerfile`, for Cloud Run, Fly.io or a VPS |

What the process is running on decides what it can serve, so there is no flag that could claim
gRPC on Vercel. The host target keeps every rule of the Vercel one — the external cron tick, no
in-process scheduler, the same settings refusal — so one codebase never has two behaviours
beyond the transport. **Where the host lives is not decided here.** One option worth weighing
when it is: a host inside Russia would also be the egress #235 is waiting for.

**Cold start.** Nothing under `rpc/`, `rest/` or `contract/` may import `app.bot`;
`tests/test_service_layering.py` is extended to those packages, and a new test imports
`app.main` in a fresh interpreter and fails if `aiogram` is in `sys.modules`. Import cost of the
new runtime is measured by the spike.

**v1 goes, in three steps.** v2 ships beside v1 (a merge to `main` deploys itself, and the
owner's phone runs the current APK until it is replaced); the new APK is installed on the
family's phones; a later pull request deletes v1, keeping only the plain-HTTP paths listed above.
`docs/api.md` is rewritten for v2 and points at the proto as the reference.

## 3. Android

**Transport-neutral sources.** `:core:data` gets one interface per area —
`ScheduleRemote`, `MeRemote`, `ManageRemote`, `DiaryRemote`, `DirectoryRemote` — and two
implementations of each: `Rest*` (Retrofit, kotlinx.serialization DTOs for the v2 JSON) and
`Rpc*` (`connect-kotlin` over the generated lite code, protocol Connect or gRPC). Repositories
depend on the interfaces only. `LessonsContainer` binds one set.

**The transport is a build property.** `-Plessons.transport=rest|connect|grpc` becomes a
`BuildConfig` field; `-Plessons.streaming=true` is accepted only with `grpc`. The default is
`connect`: production is the Vercel target, which serves it, and it exercises the generated
contract on every build. CI assembles the default and unit-tests all three bindings, so the matrix
costs tests, not three times the APKs.

**One OkHttp.** `connect-kotlin` takes the app's `OkHttpClient`, so the existing interceptors —
the base URL, the two bearers, the developer mode's network record — keep working. Two things
must learn the new paths: which bearer goes on which request (today chosen by path prefix in
`AuthInterceptor` and `DiaryAuthInterceptor`, and held by a test), and `NetworkRedaction`'s list
of what the record may keep (the Connect and gRPC headers are not on it, so by design they are
dropped until added on purpose).

**Errors without Retrofit.** `ManageFailure`, `DiaryFailure`, `DiarySignInProblem` and
`JoinFailure` stop catching `retrofit2.HttpException`. Both bindings turn a failure into one
`RemoteError(code, reason, metadata)`, and the classifiers read `reason` only.

**The REST DTOs are checked against the contract.** The server's tests write the canonical JSON
of every message into golden files; an Android unit test decodes each one strictly with the DTOs.
A field renamed in proto and not in Kotlin fails a test on the next run of both suites, rather than
in front of somebody.

**Streaming (beta).** With `grpc` and `streaming`, the sync worker opens `WatchClass` while the
app is in the foreground and syncs the moment the class changes, instead of waiting for the next
periodic sync. Nothing else depends on it, and a build without it behaves exactly as today.

## 4. Streaming, the beta

One method: `WatchClass`, a server stream of «this class changed» events (a revision, never the
data — the phone fetches the window as it already does). The source is an in-process bus that
`services/` notifies after a commit, which is correct for **one** host instance; several instances
would need Postgres `LISTEN/NOTIFY`, and Neon only offers that on a direct, unpooled connection
whose compute may suspend — so the beta is documented as single-instance. It is tested
synthetically: CI starts the host target on a Linux runner and a test client watches a change it
makes itself.

## 5. Decomposition: only what survives v2

`api/public.py`, `api/diary.py`, `api/edit.py` and the Android network layer (the Retrofit
interfaces and v1 DTOs) are replaced by this programme. Splitting them first would be work thrown
away, so they are not split; their shared helpers (`caller_bucket`, the bounds checks, the clock)
move to `api/deps.py` and `services/` as the v2 layer needs them.

**Server (sub-project 1).** Feature sub-packages **inside** the layers, the way `manage/` already
is in `api/`, `services/` and `bot/handlers/` (precedent: the ~3,500-line `handlers/manage.py`
became a package with one module per screen and an `__init__` that re-exports the old names).
Vertical feature folders (`app/features/diary/{api,bot,services}`) were considered and rejected:
`test_service_layering.py` walks module names starting with `app.services`, so under feature
folders it would check nothing and still pass, and a feature package whose `__init__` imported its
bot half would put aiogram on the API's cold start.

- `bot/handlers/start.py` → onboarding (the create-class wizard), day browsing, codes, help.
- `bot/handlers/content.py` → homework, substitutions, events, and their common helpers.
- `bot/handlers/tasks.py` → personal tasks, with the homework ticks moved to the homework module.
- `bot/render.py` and `bot/keyboards.py` → per feature, following the existing `diary_render`,
  `editor_keyboard` and `calendar_keyboard`; `render.py` stays the re-exporter of `app.wording`
  that `test_service_layering.py` checks; `manage_keyboards`/`manage_render` per screen.
- `services/diary.py` → the corrections move next to `services/diary_overrides.py`.

Before any of it, the tests that would fight a move are made to follow symbols rather than paths:
`test_announcements.py` keys its list by `"file.py:function"`, `test_directory.py` hard-codes the
files that construct a throttle, and the callback-prefix collision check in `test_bot_manage.py`
scans three keyboard modules by name — and already misses `editor_keyboard` and `diary_keyboard`.
Monkeypatches by module path (`public._now`, `content._today`, `start_handlers.datetime`, …) are
listed in each split's plan and moved with the symbol.

**Android (sub-project 4).** Files are split within the existing feature packages; no feature
Gradle modules. Those would first need the package cycles broken (settings↔diary,
onboarding↔join, settings→translate→developer→settings, diary→admin for sheet scaffolding), a
replacement for the `Graph` service locator, and a detekt baseline, a stability-file block and an
Android block per module — while more modules mean more parallel Gradle work on a machine whose
memory is faulty. The splits:

- `SettingsViewModel` → its state types, an updates view model, a GitHub/translation view model.
- `GithubRepositoryImpl` → the OAuth device flow, the pull-request helpers, issues.
- `LessonsPreferences` → keys, the settings codec, the bundle tags, the diary keys — following
  `ShellKeys`/`DiaryKeys`, which already moved out.
- `ManagementViewModel` → its state types first (free), then per-sheet collaborators over shared
  plumbing.
- `OnboardingScreen` → one file per step, as its sibling steps already are.
- `HomeShell` and `FloatingToolbar` → **only after #264 lands**; both are being changed for it.

Kotlin's `private` is file-scoped, so a split widens some declarations to `internal`, and detekt's
baseline entries name the file: each split ends with `./gradlew detektBaseline` and a review of
what it removed. `org.gradle.workers.max` is capped in `gradle.properties` as part of this
sub-project.

## 6. The build console

`tools/console/` — a separate Python project with its own `pyproject.toml`, depending on Textual,
started with `uv run --project tools/console lessons-console`. Local only; it never runs in CI and
it is not part of the product. It is a terminal application, so the rule that this repository has
no web frontend stands.

| Tab | What it does |
| --- | --- |
| Build | the gates exactly as CI runs them, per half or all; an APK for a chosen transport and streaming flag; a history of runs, each with its saved log |
| Contract | `buf generate`, `buf lint`, `buf breaking` against `main`, and the generated-code freshness check |
| Deploy | the Vercel target: production's `/api/v1/warmup` (status, schema revision against `EXPECTED_REVISION`) and a preview deployment through the Vercel CLI once #118 has said what Preview is for; the host target run locally |
| Device | `adb devices`, install the chosen APK on the emulator or a phone, a filtered logcat |
| Environment | every setting the server reads, which are mandatory on Vercel, which are switched off and what that switches off — read from `server/.env.example` and `app/config.py`, never a third copy; set or missing locally, values never shown |
| Docs | `docs/`, `CLAUDE.md` and `HANDOVER.md` in Textual's Markdown viewer |

**One heavy job at a time.** Gradle, the full test suite and the host target take a lock; a
second heavy job waits in a visible queue. Gradle runs with a bounded worker count and with
`JAVA_HOME` pointed at a JDK 21, which the console finds (the `java` on this machine's `PATH` is
17). `buf` is fetched to a cache at the pinned version if it is missing. The console never
deploys production: production is a merge to `main`, and a button that bypassed that would be a
second way in that nothing reviews.

## 7. Gates, CI and testing

- **CI** gains `buf lint`, `buf breaking` against the base branch, and the generated-code check.
  The workflows are not edited casually; this change goes through the `build-ci` agent's review.
- **Parity of REST and RPC** is tested once, generically: a table of scenarios runs through the
  transcoder and through an in-process Connect client, and the decoded messages must be equal.
- **The error mapping** has a table test: every service exception, its code, its reason, its
  HTTP status.
- **No echo**, **no aiogram on the cold path**, and the **layering** rule over the new packages,
  as above.
- **Android:** each binding is unit-tested against MockWebServer (REST and Connect JSON; gRPC over
  HTTP/2 prior knowledge), the golden-file DTO test, and the existing suites unchanged.
- **The host target** is started on a Linux runner for the streaming and native-gRPC tests.

## 8. Order, and what each sub-project delivers

| # | Sub-project | Delivers | Waits for |
| --- | --- | --- | --- |
| 0 | Spike (throwaway) | answers: codegen, mounting, annotations at runtime, native gRPC under an HTTP/2 ASGI server, `connect-kotlin` under AGP 9 and Kotlin 2.4, R8 and APK size, a call from the emulator | — |
| 1 | Server decomposition | the bot and `services/diary.py` splits, the path-keyed tests made symbol-keyed, the two guards | — |
| 2 | Contract | `proto/`, Buf in CI, the error model, the resource map final, `docs/api.md` for v2 | 0 |
| 3 | Server shells and targets | `rpc/`, `rest/`, `host.py`, the `Dockerfile`, streaming beta, v2 live beside v1 | 2 |
| 4 | Android decomposition | the splits above, the worker cap | #264 for the shell |
| 5 | Android transports | the remotes, three bindings, `RemoteError`, golden files, the new APK; then v1 deleted | 3, 4 |
| 6 | Build console | `tools/console/` | 3, 5 for the variants it builds |

Each sub-project is its own pull request (or several), with the gates green and a milestone.

## 9. Tracking

- **A milestone the owner creates** (a session here cannot): proposed title
  `v0.10.0 — One contract: REST v2, Connect and native gRPC, build console`, description: the
  first paragraph of this document.
- **Defects the survey found get issues before fixes**, on that milestone: the non-idempotent
  `PUT /api/v1/events`; `GET /api/v1/cron/tick` sending messages; the app branching on English
  error strings; the callback-prefix check missing two keyboards; nothing guarding aiogram off the
  API's cold start.
- `CLAUDE.md` changes with the sub-projects that change what it describes: the deliverables
  (`proto/`, `tools/console/`), the commands, the CI steps, the two targets.

## Risks, and what would change the plan

- **`connectrpc` is beta (0.12.1).** If the spike finds it unfit, the RPC layer is still the one
  handler per method, served by a different library; the contract and the transcoder do not move.
- **The transcoder is ours to maintain.** It is small and generic, and tested generically; a
  method it cannot express is a sign the resource is wrong, not a reason to special-case it.
- **APK size and dependencies.** `connect-kotlin` brings Moshi, `kotlin-reflect` and `ktor-http`
  at runtime. The spike measures the release APK; a large cost would argue for a thin Connect
  unary caller of our own over the same OkHttp.
- **Two transports double the Android test surface.** That is the price of the owner's choice of
  both; the remotes keep it to the bindings, and the repositories above them are tested once.
- **A response over 4.5 MB fails on Vercel.** A school year's window is far below that in JSON
  and smaller in binary, but the contract sub-project measures the largest real one.
- **Streaming is single-instance** until `LISTEN/NOTIFY` is worth its cost on Neon.
