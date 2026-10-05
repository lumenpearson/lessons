# The app on v2: transport-neutral remotes, three transports, and v1's retirement

Status: **drafted on 5 October 2026 while the owner was away, not yet approved.** Sub-project 5 of
[the programme](2026-10-03-one-contract-design.md), whose section 3 already decided the shape:
- one interface per area in `:core:data`;
- the transport as a build property, `connect` by default;
- one OkHttp for our server;
- `RemoteError` in place of `retrofit2.HttpException`;
- golden files holding the REST DTOs to the contract;
- the keep rule and `kotlin-reflect` handled in `:core:data`;
- v1 deleted once the family's phones carry the new APK.

It builds on [sub-project 3's design](2026-10-05-server-v2-design.md) (#301, itself waiting for
approval) for what the server answers.

It was revised the same night after an independent review that checked every claim against
`android/`, the contract, sub-project 3's design and connect-kotlin 0.9.0's own jars. The review
found five problems:
- a first stage that broke device linking;
- an error contract that lost every transport failure under the default transport;
- duties nobody was assigned;
- two factual errors in the questions;
- several traps the first draft did not name.

This version is the corrected one. **Nothing here is built before the owner approves it, and
before sub-project 3 serves what each stage calls.** No Gradle task was run for it.

## What the network layer is today

The calls and their callers:
- **Four Retrofit interfaces, all `internal` in `core/data/network/`, all under `api/v1/`:**
  - `LessonsApi`: 6 endpoints;
  - `DirectoryApi`: 1;
  - `ManageApi`: 23;
  - `DiaryApi`: 15.
- **Four of those 45 have no caller:** `health`, and the diary's `subjects`, `teachers` and
  `attendance`.
- **One repository per area calls them.** The screens see sealed failure types only;
  `HttpException` never leaves `:core:data`.
- **`GET /me` mints the link code**, and the app relies on that. Asking is what mints it.

The server client:
- **One server client, `NetworkModule.okHttpClient`, with four interceptors in order:**
  1. `BaseUrlInterceptor` rewrites a `.invalid` placeholder to the configured server.
  2. `NetworkLog` records.
  3. `AuthInterceptor` signs with the class token.
  4. `DiaryAuthInterceptor` signs with the diary token.
- **The two signers decide by path, with `api/v1` in their literals.** `AuthInterceptor` signs
  anything that is not one of its listed exceptions, so a v2 path, a Connect path included, would
  get the class token by default.
- **The other three OkHttp clients are separate on purpose** (the diary's own servers, GitHub, the
  developer console), and stay so.

DTOs, enums and Room:
- **DTOs are `@Serializable` with explicit snake_case names.** The one network `Json` is
  configured with `ignoreUnknownKeys`, `coerceInputValues` and `explicitNulls = false`.
- **Every wire enum already travels as a `String`**, resolved by a lenient `fromWire` with a
  stated fallback. The leniency v2 needs is the house style.
- **Room is filled through DTO → domain → entity.** A change of DTO is absorbed in the mappers
  while the `:core:model` types stay (with the exceptions in decision 11).

Errors:
- **Five classifiers turn `HttpException` into the screens' vocabulary:** `ManageFailure`,
  `DiaryFailure`, `DiarySignInProblem`, `JoinFailure`, `DirectoryProblem`.
- **They branch on statuses, three headers and `Retry-After`.** One of them, `ManageModels.kt`,
  also matches English text (#270).
- **Each has an `is IOException → Offline` branch.** The sync and the directory also branch on
  the two exceptions `BaseUrlInterceptor` throws.

The build:
- **`:core:data` has no `BuildConfig`.** Build values reach it through `Graph.init`.
- **A new build property needs a line in `apk.yml`**, or `BuildPropertyReachTest` fails.
- **minSdk is 26.**
- **Nothing exists yet for v2:** no client-version header, no protobuf, no Connect, no golden
  file.

Tests: **seven test files** build a real `HttpException`, **eighteen** carry an `/api/v1` literal,
and about fifteen fake a Retrofit interface directly.

## Decisions

### 1. Five remotes, the repositories' only door to the server

`:core:data` gets one interface per area, named for what it serves rather than for a transport:

| Remote | v2 methods | Replaces |
| --- | --- | --- |
| `ScheduleRemote` | `GetScheduleWindow` | `LessonsApi.bundle` |
| `JoinRemote` | `CreateDevice` | `LessonsApi.join` |
| `MeRemote` | `GetMe`, `CreateLinkCode`, `UnlinkMe`, and the tasks and ticks when the app uses them | `LessonsApi.me`, `unlink` |
| `ManageRemote` | the class-management services, and `DirectoryService.ListSchools` for the school search | `ManageApi` |
| `DiaryRemote` | `DiaryService` | `DiaryApi` |
| `DirectoryRemote` | `ListSchoolRegions` | `DirectoryApi` |

`MeRemote` calls `CreateLinkCode` when the phone is not linked, which is what v1's `GET /me` did
on the server's side. `warmup` and `health` stay plain HTTP at their v1 paths (sub-project 3)
behind a small `ServerStatusRemote`.

**The three-way contract.** Every remote method has exactly one of three outcomes:
1. **A success value**, in `:core:model` and `:core:data` domain types, never a DTO or a generated
   message.
2. **A `RemoteError`**, when our server answered with a refusal (decision 4).
3. **The original exception**, when no answer came: an `IOException`, `BaseUrlInterceptor`'s
   `ServerAddressMissingException` and `ServerNeedsHttpsException` included.
   - `CancellationException` is always rethrown.
   - `RemoteError` does not extend `IOException`, so the classifiers' `is IOException` branches
     never catch it first.

connect-kotlin wraps a transport failure in its own `ConnectException` with code `UNKNOWN`. The
review verified this in its 0.9.0 jars. So every `Rpc*` method unwraps it: when no server status
came back and the cause is an `IOException`, the cause is what it throws. Without this, under the
default `connect`, every «нет связи», «укажите адрес сервера» and «нужен https» would become
«Unexpected», and the sync would retry what can never succeed. Each transport has binding tests
for no address, `http://` in a release build, and a timeout.

The repositories depend on the remotes only, and the fifteen test fakes of Retrofit interfaces
become fakes of remotes.

### 2. Three transports over two implementations, and one signer at send time

There are two implementations of each remote:
- **`Rest*`** calls `/api/v2/…` with Retrofit, over kotlinx.serialization DTOs written for v2's
  canonical JSON:
  - field names are lowerCamelCase;
  - 64-bit ids are strings;
  - every field has a default, because canonical JSON omits defaults.
- **`Rpc*`** calls connect-kotlin over the generated lite classes, with protocol Connect or gRPC.
  The lite runtime has no JSON, so **`Rpc*` speaks binary protobuf**:
  - success bodies are `application/proto`;
  - Connect's error bodies are JSON, which connect-kotlin parses.

  Connect JSON is the developer console's alone (decision 5).

**Enums are decoded leniently, and also from numbers.**
- A REST DTO decodes each enum field with a serializer that accepts a name or a number. When the
  server relays an unknown value it writes the number, and `test_contract_json.py` pins that, so a
  `String`-typed field would fail a whole list on `99`.
- The value then resolves through the existing `fromWire` convention.
- A proto enum's `UNRECOGNIZED` maps to the same fallback.

**One `BearerInterceptor` signs v2 calls, at send time, by method.** The token is still read on
OkHttp's thread, as today: `CredentialsSnapshot` relies on never being read on the main thread,
and a repository coroutine is not that.
- **REST.** Each `Rest*` interface method carries a `@Credential(DEVICE | DIARY | NONE)`
  annotation. The interceptor reads it from the request's `retrofit2.Invocation` tag, which
  Retrofit attaches to every request. The annotation class is kept for R8.
- **RPC.** The interceptor looks up the exact method: the path
  `/api/rpc/lessons.v2.<Service>/<Method>` in its table. That is a lookup, not a prefix rule.
- **Fail-closed.** A v2 method not in the table gets no `Authorization` at all.
- **The v1 signers stand aside.** `AuthInterceptor` and `DiaryAuthInterceptor` are guarded to
  `/api/v1/` paths only, so they cannot sign v2's anonymous methods (`CreateDevice`,
  `GetDiaryCapabilities`, `CreateDiarySession`, `ListSchoolRegions`). They are deleted with v1.
- **Tests.** The four anonymous methods leave unsigned over both REST and Connect, whatever tokens
  the phone holds.

**The signer's table is held to the contract.** A golden file lists every method's credential:
`server/tests/golden/v2/credentials.json`, keyed `lessons.v2.<Service>/<Method>`, with
`DEVICE_LINKED` folded into «device». It is written from the descriptors, which `test_contract.py`
already reads for all 76 methods. An Android test compares the signer's table with it.

### 3. The transport is chosen when the APK is built

- **The property.** `-Plessons.transport=rest|connect|grpc`, or `LESSONS_TRANSPORT`, is read with
  the build script's existing `signingSecret` pattern and validated to one of the three. The
  default is `connect`.
- **Streaming.** `-Plessons.streaming=true`, or `LESSONS_STREAMING`, is accepted only with `grpc`
  and fails the build otherwise.
- **`apk.yml`** carries both lines, which `BuildPropertyReachTest` requires.
- **How it reaches `:core:data`.** The transport becomes a `BuildConfig` field in `:app` and
  reaches `:core:data` through `Graph.init`, as a **required** parameter. A defaulted one would
  silently build the wrong container if the order in which `Application`, the worker and the
  widget start ever changed.
- **CI.** All three transports' bindings are covered by the ordinary `./gradlew test`, so CI needs
  no matrix and assembles the default only.
- **Rollback.** After each stage, no value exists only in v1's shape, so rolling back a stage is a
  revert.

### 4. `RemoteError`, and classifiers that read a reason first

`RemoteError(code, reason, metadata, message, httpStatus, violations)` is what both bindings
produce for an answer from our server:
- **Over REST**, from Google's JSON error body and the status.
- **Over Connect and gRPC**, from the protocol's error and its `ErrorInfo` detail, decoded from
  the detail's bytes with our own generated `google.rpc` classes (decision 7).
- **What every answer keeps.** `httpStatus` is always kept. `violations` carries a
  `google.rpc.BadRequest`'s field violations.
- **An unknown reason never throws on the way.** A reason the app does not know is kept by name
  and handled by its code. The generated `ErrorReason.valueOf` throws on an unknown name, so the
  lookup does not use it.

**The rules every classifier follows**, written once and tested in each:
1. **Reason first, then code.** `DEVICE_LIMIT_REACHED`, `DIRECTORY_SPENT` and `THROTTLED` all
   share `RESOURCE_EXHAUSTED` (REST 429), and only the reason tells them apart.
2. **An answer with no reason is classified by its HTTP status**, as v1 is today. That covers:
   - Vercel's 504 ceiling;
   - a bare 503, sub-project 3's fail-safe 503 for all of v2 included;
   - a non-JSON body;
   - **«this server has no v2»** — a 404 over REST, or a reasonless `UNIMPLEMENTED` over Connect —
     which becomes the existing «server too old» cases. It must never become `JoinFailure`'s
     «no class answers this code».
3. **Metadata is mapped explicitly.** `ROLE_REQUIRED`'s role is `"ROLE_ADMIN"` where `RoleLost`
   held `"admin"`. «Address refused» is `DIARY_UNAVAILABLE` with `upstream=address-refused`.

**The table, classifier by classifier.** Each keeps its sealed type, so the screens' tests do not
move. Each classifier's unit tests change their input from `HttpException` to `RemoteError` and
keep every expected output.

| Classifier | Reasons it reads | Status-only cases kept |
| --- | --- | --- |
| `JoinFailure` | `JOIN_CODE_UNKNOWN`, `CLASS_INVITE_ONLY`, `DEVICE_LIMIT_REACHED`, `THROTTLED` | «no v2» → `Rejected` with the server-too-old cue |
| `ManageFailure` | `DEVICE_NOT_LINKED`, `ROLE_REQUIRED` (#270 closes here), `DEVICE_TOKEN_INVALID`, `RESOURCE_NOT_FOUND`, `RESOURCE_EXISTS`, `RESOURCE_IN_USE`, `SUBJECT_RENAME_CLASH`, `ROLE_GRANT_REFUSED`, `CLASS_DEVICE_NOT_LINKED`, `VALIDATION_FAILED`, and the `FAILED_PRECONDITION` reasons (see below) | 503 → `Unavailable` |
| `DiaryFailure` | `DIARY_TOKEN_INVALID`, `DIARY_REAUTH`, `DIARY_DISABLED`, `DIARY_UNAVAILABLE` (its `upstream` metadata), `DIARY_UPSTREAM_UNREADABLE`, `RESOURCE_NOT_FOUND`, `THROTTLED`, `CORRECTIONS_UNAVAILABLE`, `VALIDATION_FAILED` (through its existing `unprocessable` parameter), `FEATURE_UNSUPPORTED` | 504 → `Unavailable` |
| `DiarySignInProblem` | `DIARY_CREDENTIALS_REJECTED`, `DIARY_NO_STUDENTS`, `DIARY_DISABLED`, `DIARY_UNAVAILABLE`, `THROTTLED` | 504 → `Timeout`; «no v2» → `ServerTooOld` |
| `DirectoryProblem` | `DIRECTORY_DISABLED`, `DIRECTORY_SPENT`, `DIRECTORY_UNAVAILABLE`, `THROTTLED`, `VALIDATION_FAILED` | 504 → `Upstream`; «no v2» → `ServerTooOld` |

v1's 422s and 409s on management are now `FAILED_PRECONDITION`, and the table says which are which:
- `NO_BELL_FOR_LESSON`, `EMPTY_BELL_SCHEDULE`, `TERM_BOUNDS_REFUSED`, `NO_LESSON_ON_DAY` and
  `LESSON_NOT_ON_TIMETABLE` become `ManageFailure.Invalid`;
- the rest become `Refused`.

The diary sign-in's preflight also loses a check: `capabilities.registration` is gone from v2,
because every v2 server registers sessions.

**`CLIENT_TOO_OLD` is one app-wide state**, not a per-screen refusal:
- **`SyncResult`** gains `ClientTooOld`, which the worker treats as a permanent failure. It is not
  retried three times every period.
- **The app** shows «обновите приложение» once, with a link to its existing update flow. The
  string is in `:core:data`'s `values/` with its `values-en` twin, which `ResourceTranslationTest`
  holds.
- **Any classifier** that meets `CLIENT_TOO_OLD` raises the same state.

`REQUEST_UNDECODABLE` is a defect of the app, not of the person. It is logged and shown as
«Unexpected».

The sync's token rejection (`onTokenRejected`) fires on `DEVICE_TOKEN_INVALID` only.

### 5. One OkHttp, and what it adds

The `Rest*` and `Rpc*` bindings share `NetworkModule`'s server client:
- **The RPC host.** connect-kotlin is given the host `http://base-url.invalid/api/rpc`. It parses
  that, adds a trailing slash and appends the method path, which is what the review read in its
  bytecode. `BaseUrlInterceptor` then rewrites and prefixes it exactly as it does for REST.
- **The order of interceptors.** `BaseUrlInterceptor` stays first, so the network record names the
  real host. `BearerInterceptor` and the version header come after the record, which never reads
  request headers.

**The version header, `ClientVersionInterceptor`.**
- It adds `X-Lessons-Client: <versionCode>` to every request to our server, from the first v2
  build, so that sub-project 3's minimum can retire an APK.
- The version code is `:app`'s `BuildConfig.VERSION_CODE`, passed to `Graph.init`, rather than
  `PackageInfo.longVersionCode`. That is API 28, and minSdk is 26.
- A local or debug build stamps version code 1 (`app/build.gradle.kts`). So once a minimum above 1
  is set, every hand-built APK is refused. `docs/build.md` says so, and so does question 3.

**The diary's own client.** The diary, GitHub and the console keep their own clients. A test makes
sure the diary's servers receive neither our version header nor our bearer: `UpstreamHttpTest`'s
«none of ours» check names the two new interceptors.

**The developer mode.**
- **Presets.** The request console gains v2 presets: REST, and Connect **GET** for the
  side-effect-free methods (`?encoding=json&message=%7B%7D`). A Connect POST would need a
  Content-Type the console does not add, and would get a 415.
- **The network record.** `NetworkRedaction`'s kept headers gain `grpc-status` and `grpc-message`,
  on purpose and nothing else. They arrive as headers only in a trailers-only answer. Under Connect
  the code and reason are in the body, which the record never reads, so a v2 refusal shows its
  status alone. The `X-Diary-*` headers that v1's record showed are gone.

### 6. Golden files hold the REST DTOs to the contract, both ways

**The server writes them, and a stage of this sub-project adds the writer.** 5a adds to
`server/tests` a test that writes the canonical JSON of each v2 method's request and response,
from sub-project 3's harness, to `server/tests/golden/v2/`:
- `<Service>.<Method>.request.json` and `<Service>.<Method>.response.json`;
- `index.json`, listing them.

The test **compares and fails**; it rewrites only under an explicit switch. A test that rewrote the
file in CI would pass by construction. A second server test fails if a sample leaves a field of its
message unset, so every field is exercised at least once.

**`:core:data` reads them in place** as test resources rooted at `server/tests/golden`, the way it
already reads `server/tests/vectors/`. One test walks the index and decodes each response with the
matching REST DTO:
- unknown keys fail the decode, so a renamed field fails;
- a field the server added but the phone deliberately does not read goes on an explicit
  «deliberately unread» list;
- enums stay lenient.

It also encodes each request DTO and compares the result with the request file. The transcoder
ignores unknown request fields, so a stale request name would otherwise be dropped silently.

**CI runs both sides when a golden changes.**
- The Android arm of «What changed» gains `server/tests/golden/*`, through the `build-ci` review.
- An Android twin of `test_ci_paths.py` reads `:core:data`'s in-repository paths and the Android
  arm, so the next such directory cannot be missed.

### 7. The generated code, its dependencies and R8

**The generated code.**
- **Where it goes.** The generated Java lite goes under `android/core/data/src/main/contract/java`,
  added as an extra source directory, as the programme says.
- **The plugins.** `buf.gen.yaml` gains `protocolbuffers/java` v36.2 lite and `connectrpc/kotlin`
  v0.9.0, the spike's pinned versions. Both run over the contract **and over 3a's `google/rpc`
  input**, so `ErrorInfo`, `BadRequest` and `RetryInfo` exist on the phone.
- **The protobuf Kotlin lite plugin is not added.** It adds 225 files of builder DSL and nothing
  the mappers need. The Java builders do.
- **CI's Contract job.** Its regenerate-and-diff step diffs the Android directory too. Its path
  filter gains `android/core/data/src/main/contract/*`, because a hand edit there is exactly what
  that filter exists to catch.
- **The pins.** `test_contract.py`'s plugin-pin test is extended to hold the Java and connect-kotlin
  pins level with `libs.versions.toml`. That file is added to the server suite's read list and to
  the server arm of «What changed».
- **detekt** skips the generated directory.

**The dependencies.**
- **Versions.** `protobuf-javalite` 4.36.2 and connect-kotlin 0.9.0 enter `libs.versions.toml` at
  the spike's versions, each with a provenance comment by the file's own rule.
- **The keep rule** ships as `:core:data`'s `consumerProguardFiles`, which does not exist yet. The
  spike's minified build failed without it. `@Credential` is kept with it.

**`kotlin-reflect` comes from two places, and the design removes both.**
- **connect-kotlin's own POM** declares `moshi-kotlin` at runtime scope, although its core uses
  plain Moshi with code-generated adapters. So `moshi-kotlin` is excluded, and `moshi` core is
  depended on directly.
- **The javalite extension** declares `kotlin-reflect` and uses it. It is not taken. Our own
  serialization strategy over the lite runtime replaces it, with our own error-detail parser over
  our generated `google.rpc` classes.

Only if that fails is `kotlin-reflect` forced to the standard library's version with a dependency
constraint.

**Checked on the emulator.** From the minified release APK: a Connect call, with an `ErrorInfo`
detail decoded. That is how the spike found the missing keep rule.

### 8. ETags survive the change of transport

`BundleTagStore` keeps opaque tags per class and year, and nothing assumes their shape.
- **Over REST**, the tag arrives as `ETag` and the 304 as a status. `RestScheduleRemote` accepts
  200 and 304, and turns every other status into a `RemoteError` itself, which was the trap of
  v1's `Response<T>`.
- **Over RPC**, they arrive as `etag` and `not_modified`.
- `ScheduleRemote.window(year, ifNoneMatch)` answers the window with its tag, or «not modified».
  `TokenRejectedTest`'s regression becomes a MockWebServer test of that binding.
- **A tag stored under v1 never matches v2.** The window is shaped differently, so the first v2
  fetch is a full one, and nothing needs migrating.

### 9. Stages, each moving whole areas after the server serves them

| Stage | Merges | Waits for |
| --- | --- | --- |
| **5a** | The infrastructure: generated code, dependencies, keep rule, `RemoteError` with its three-way contract, the signer, the version header, the transport property, and the golden writers and readers. Then **two whole areas**: `ScheduleRemote` (the window) and `JoinRemote` (`CreateDevice`), with `JoinFailure` and the sync's token rejection already reading reasons | 3a deployed, and its post-merge smoke check passed |
| **5b** | `MeRemote` whole (`GetMe`, `CreateLinkCode`, `UnlinkMe`), `DiaryRemote` whole (capabilities, session, reads, corrections), `ManageRemote`, `DirectoryRemote`; every classifier on reasons; #270 closed | 3b deployed |
| **5c** | The gRPC binding, and the streaming beta | 3c, and a host to point it at |
| **5d** | The new APK on the family's phones, then v1 deleted on both sides | 5b, and the owner's word (question 3) |

**Until 5b, the app calls v1 for every other area.** `LessonsContainer` binds each area's remote
separately, a v2 remote where its area has moved and a v1 adapter where it has not. An area moves
over whole, never half. That is why the link code waits for 5b: v2's `GetMe` deliberately does not
mint one (sub-project 3, decision 10), and moving it without `CreateLinkCode` would take the code
off every unlinked phone's card.

**The streaming beta in 5c.**
- **It does not run in a worker.** A `CoroutineWorker` is stopped after ten minutes, so the stream
  runs in a collector tied to the app's foreground lifecycle, which calls the existing `refresh()`.
- **Its client.** The shared client's 30-second and 60-second timeouts would cut the stream, so the
  stream's client is built from `shared.newBuilder()`: the same pool and interceptors, with no call
  timeout.
- **A local host.** A host reached over plain HTTP/2 needs `H2_PRIOR_KNOWLEDGE`.

### 10. Testing

**Every binding is tested against MockWebServer with what it really sends:**
- REST JSON;
- Connect with binary success bodies and JSON error bodies;
- gRPC over HTTP/2 with prior knowledge, from 5c.

Each binding has the three transport-failure tests of decision 1.

**The classifiers and repositories:**
- the classifiers are tested on `RemoteError`, with every case of decision 4's table;
- the repositories are tested once, over fake remotes.

**The table checks:**
- the golden files, both ways (decision 6);
- the credentials table (decision 2).

**The minified build:** decision 7's check on the emulator, from a minified build, for each stage
that adds a binding.

### 11. What the mappers do not absorb

Most of v2 is absorbed in the mappers. A few changes reach a domain type or a screen, and each is
named in the stage that meets it:
- **The audit log** pages with a `page_token` instead of an offset, so `ManageRepository.log` and
  `LogSheet`'s «N–M» with its back button change in 5b.
- **Instants** (a device's `createdAt`, an audit entry's `at`) arrive as UTC timestamps, and are
  converted with the class's zone.
- **Times of day** lose their seconds.
- **`next_school_day`** is gone from the window. The phone already answers «what is next» from the
  year it holds.
- **The diary's capabilities** become a list of providers.
- **`api_version`** stays on `warmup`, which is plain HTTP. The about card and the developer checks
  will read «API 1» on a v2 app, and 5a decides the wording there.
- **The school search** moves to `DirectoryService.ListSchools`, with the device token and the
  admin role.

The documents that change with each stage are `docs/architecture.md` (the network layer),
`docs/build.md` (the transport property, and the version code's new role) and `docs/api.md`, whose
v2 section turns from «will» to «does».

## Questions for the owner

1. **When does `connect` become the release build's default?** *Recommended: when 5b lands, which
   means 3b is deployed.* Until then a release build stays on v1 for the areas 5a has not moved.
   This agrees with sub-project 3's decision 12, which says the app must not ship against 3a alone.
2. **Should a debug build be able to switch transports at run time, from the developer mode?**
   *Recommended: no.* Two reasons:
   - the container is built once per process, so a switch means a restart;
   - a phone switched to gRPC against Vercel is simply broken, because Vercel cannot serve native
     gRPC.

   Testing another transport means building another APK.
3. **How will you know every family phone has the new APK, so that v1 can go?** *Recommended:
   ask sub-project 3 to record the last `X-Lessons-Client` each device sent.* «📱 Устройства» then
   shows which phones still run an old APK. That is an additive column, a migration of its own,
   which 3b would carry. Your word then rests on what the server sees.
   - **`MIN_CLIENT_VERSION` cannot reach the phones that matter here.** An APK from before v2
     sends no header and calls only v1. After v1 is deleted it gets 404s, which its own
     classifiers read as «код не найден», «Unexpected» or a failed sync.
   - **The minimum's real use comes later**: retiring a v2 APK once v2 itself changes. Setting it
     above 1 refuses every hand-built debug APK too.
4. **Should the app fall back to v1 when v2 answers a bare 503 or 404?** *Recommended: no.* A
   fallback would hide a broken v2 behind a working v1 until the day v1 is deleted. Instead,
   sub-project 3's post-merge smoke check must pass before any v2 APK is installed.

## Risks

- **APK size.** The spike measured +611 KB (+13.8 %), mostly `kotlin-reflect`. Decision 7 removes
  both of its sources. If that fails, the programme's fallback — a thin Connect caller of our own
  over the same OkHttp — is still there.
- **The test churn is real.** About fifteen fakes, seven `HttpException` builders and eighteen
  `/api/v1` literals move. Each classifier keeping its sealed type and its outputs is what keeps
  the screens' tests still.
- **Three transports multiply the binding tests.** That is the price of the owner's choice of all
  three, and the remotes keep it to the bindings.
- **An area half-moved is worse than one not moved.** Decision 9's «whole, never half» is the
  guard. Its first draft broke that rule, and the review caught it.
