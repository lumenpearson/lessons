# The app on v2: transport-neutral remotes, three bindings, and v1's retirement

Status: **drafted on 5 October 2026 while the owner was away, not yet approved.** Sub-project 5 of
[the programme](2026-10-03-one-contract-design.md), whose section 3 already decided the shape:
- one interface per area in `:core:data`, with a `Rest*` and an `Rpc*` implementation of each;
- the transport as a build property, `connect` by default;
- one OkHttp for our server;
- `RemoteError` in place of `retrofit2.HttpException`;
- golden files that hold the REST DTOs to the contract;
- the keep rule and `kotlin-reflect` handled in `:core:data`;
- v1 deleted once the family's phones carry the new APK.

It builds on [sub-project 3's design](2026-10-05-server-v2-design.md) (#301, itself waiting for
approval) for what the server answers.

This document adds:
- what the app's network layer is today, from a read-only survey of `android/` on 5 October 2026;
- the decisions the programme left to this sub-project;
- the stages.

**Nothing here is built before the owner approves it, and before sub-project 3 serves what each
stage calls.** No Gradle task was run for this document.

## What the network layer is today

**Calls and their callers.**
- **Four Retrofit interfaces, all `internal` in `core/data/network/`, all under `api/v1/`:**
  - `LessonsApi`: 6 endpoints. `join`, `bundle`, `health`, `warmup`, `me`, `unlink`.
  - `DirectoryApi`: 1. The school regions.
  - `ManageApi`: 23.
  - `DiaryApi`: 15. `/diary/login` is deliberately absent.
- **Four of those 45 have no caller:** `health`, and the diary's `subjects`, `teachers` and
  `attendance`.
- **One repository per area calls them:**
  - `TimetableRepositoryImpl`: the bundle, with its ETag and the 304;
  - `SessionRepositoryImpl`: join and warmup;
  - `DeviceLinkRepositoryImpl`: `me` and unlink;
  - `ManageRepositoryImpl`;
  - `DiaryRepositoryImpl` and `DiarySignInImpl`;
  - `ServerSchoolDirectory`.
- **The screens see sealed failure types only.** `HttpException` never leaves `:core:data`.

**The server client.**
- **One server client, `NetworkModule.okHttpClient`**, with four interceptors in order:
  1. `BaseUrlInterceptor` rewrites a `.invalid` placeholder to the configured server.
  2. `NetworkLog` records.
  3. `AuthInterceptor` signs with the class token.
  4. `DiaryAuthInterceptor` signs with the diary token.
- **The last two decide by path, with `api/v1` in their literals.** A path that is not under
  `/api/v1` therefore gets the class token by default, a Connect path `/lessons.v2.X/Y` included.
- **The other three OkHttp clients are separate on purpose:** the diary's own servers
  (`UpstreamHttp`), GitHub, and the developer console. None of them talks to our server through
  the server client's interceptors.

**DTOs and the database.**
- **DTOs are `@Serializable` with explicit `@SerialName` in snake_case.** One `Json` is configured
  with `ignoreUnknownKeys`, `coerceInputValues` and `explicitNulls = false`.
- **Every wire enum already travels as a `String`**, resolved by a lenient `fromWire` with a
  stated fallback. Nothing uses a `@Serializable enum`. The leniency v2 needs (sub-project 3's
  design and `docs/api.md`) is already the house style.
- **Room is filled through DTO → domain → entity.** `lessons.db` is filled from the bundle only,
  and `diary.db` from the diary's reads. A change of DTO is absorbed in the mappers, and Room does
  not move as long as the `:core:model` types do not.

**Errors.**
- **Five classifiers turn `HttpException` into the screens' vocabulary:** `ManageFailure`,
  `DiaryFailure`, `DiarySignInProblem`, `JoinFailure` and `DirectoryProblem`.
- **They branch on statuses, on three headers** (`X-Diary-Reauth`, `X-Diary-Unavailable`,
  `X-Directory-Unavailable`) with `Retry-After`, **and in one place on English text.**
  `ManageModels.kt` matches `"device is not linked"` and `"… role required"` (#270).

**What the build cannot do yet.**
- **`:core:data` has no `BuildConfig`.** Build values reach it as parameters of `Graph.init`, as
  the GitHub client id does.
- **A new `-P`/environment property is a pair, `LESSONS_X` / `lessons.x`,** and
  `BuildPropertyReachTest` fails unless `apk.yml` names it.
- **There is no client-version header, no protobuf, no Connect and no golden file.** `api_version`
  is decoded and acted on by nothing.

**What tests stand on the old types.**
- **About fifteen test files fake a Retrofit interface directly**, and about a dozen build a real
  `HttpException`.
- **Six tests assert an `/api/v1/…` path:** the interceptor tests, `RequestCredentialsTest`,
  `ServerNeedsHttpsTest`, `NetworkLogTest` and `RequestConsoleTest`.

## Decisions

### 1. Five remotes, the repositories' only door to the server

`:core:data` gets one interface per area, named for what it serves rather than for a transport:

| Remote | v2 services | Replaces |
| --- | --- | --- |
| `ScheduleRemote` | `ScheduleService` | `LessonsApi.bundle` |
| `MeRemote` | `MeService`, `DeviceService` | `LessonsApi.join`, `me`, `unlink`; tasks and ticks when the app uses them |
| `ManageRemote` | the class-management services | `ManageApi` |
| `DiaryRemote` | `DiaryService` | `DiaryApi` |
| `DirectoryRemote` | `DirectoryService` | `DirectoryApi` |

`warmup` and `health` stay plain HTTP at their v1 paths (sub-project 3), behind a small
`ServerStatusRemote` with a single implementation.

**What the remotes speak and answer.**
- Each remote speaks `:core:model` and `:core:data`'s own domain types, never a DTO or a generated
  message.
- Each answers a success value or throws `RemoteError` (decision 4).
- The repositories depend on the remotes only. `LessonsContainer` binds one set, chosen by the
  transport (decision 3).

**What it costs the tests.** The fifteen test fakes of Retrofit interfaces become fakes of
remotes, which is simpler than what they replace: no `Response`, no `HttpException`. The
repositories' own tests keep their assertions.

### 2. Two implementations of each remote, and one way to sign a call

- **`Rest*`** calls `/api/v2/…` with Retrofit over kotlinx.serialization DTOs written for v2's
  canonical JSON.
  - Field names are lowerCamelCase.
  - 64-bit ids are strings.
  - Enums are names. They are decoded as `String` and resolved by the existing `fromWire`
    convention, so a new value never fails a read, a list element included.
- **`Rpc*`** calls connect-kotlin over the generated lite classes, with protocol Connect or gRPC.
  - Proto → domain mappers sit beside the REST ones.
  - A proto enum's `UNRECOGNIZED` maps to the same fallback as an unknown name.

**A call is signed by its method, not its path.** The contract declares each method's credential
(`(lessons.v2.auth)`), and both bindings attach the bearer per call from the remote that knows it:
- a device method takes the class token;
- a diary method takes the diary token;
- `CreateDevice`, `GetDiaryCapabilities`, `CreateDiarySession` and `ListSchoolRegions` take none.

`AuthInterceptor` and `DiaryAuthInterceptor` keep v1's path rules for v1's paths until v1 is
deleted, and are then deleted with it.

**The two sides are held to one table of credentials.** A golden file written by the server's
`test_contract.py` lists every method's credential. An Android test compares the remotes' table
with it, so a credential the contract changes fails on the phone's side in the next run. This is
the same mechanism as decision 6.

### 3. The transport is chosen when the APK is built

- **The property.** `-Plessons.transport=rest|connect|grpc`, or `LESSONS_TRANSPORT`, is read with
  the build script's existing `signingSecret` pattern and validated to one of the three. The
  default is `connect`. It is added to `apk.yml`'s environment, which `BuildPropertyReachTest`
  requires.
- **How it reaches `:core:data`.** It becomes a `BuildConfig` field in `:app`, and reaches
  `:core:data` through `Graph.init`, as the GitHub client id does. `:core:data` stays free of
  `BuildConfig`.
- **Streaming.** `-Plessons.streaming=true` is accepted only with `grpc`, and fails the build
  otherwise.
- **CI.** CI assembles the default and unit-tests all three bindings. The matrix costs tests, not
  three times the APKs.

There is no runtime switch, in the developer mode or anywhere else. A transport chosen at run
time would ship all three bindings' failure modes in every APK (question 2).

### 4. `RemoteError`, and classifiers that read a reason, never a sentence

`RemoteError(code, reason, metadata, retryAfterSeconds)` is what both bindings throw:
- **Over REST**, from Google's JSON error body and the status.
- **Over Connect and gRPC**, from the protocol's error and its `ErrorInfo` detail. The detail's
  bytes are decoded with the generated `ErrorInfo`; the library's `debug` rendering is not relied
  on.
- **A reason the app does not know** becomes `UNSPECIFIED` with its name kept, and is handled by
  the code. It never throws on the way: the generated `ErrorReason.valueOf` throws on an unknown
  name, so the lookup does not use it.

**The five classifiers keep their sealed types and their screens.** Only their `HttpException`
branch is replaced, by a branch on `RemoteError`:
- `ManageFailure` reads `DEVICE_NOT_LINKED` and `ROLE_REQUIRED` with its `role`. **#270 closes
  here**: the English matching goes with v1.
- `DiaryFailure` reads `DIARY_REAUTH`, `DIARY_UNAVAILABLE`, `DIARY_DISABLED` and `THROTTLED`.
- `DiarySignInProblem`, `JoinFailure` and `DirectoryProblem` the same way.
- `DiaryFailure.retryAfterSeconds` reads the metadata.

The classifiers' unit tests change their inputs from `HttpException` to `RemoteError`, and keep
every expected output.

**What the screens show changes in one place.** Where the screens showed the server's `detail`
(`ManageFailure.detailText()`), they show the message `RemoteError` carries. That is still the
server's own words (sub-project 3, decision 5).

### 5. One OkHttp, and what it adds

The `Rest*` and `Rpc*` bindings share `NetworkModule`'s server client and its `BaseUrlInterceptor`.
connect-kotlin takes the client and a base URL, and is given the same `.invalid` placeholder, so
the configured address and its path prefix apply to RPC as they do to REST.

The client gains one interceptor, `ClientVersionInterceptor`. It adds `X-Lessons-Client:
<versionCode>` to every request to our server, from the first v2 build, so sub-project 3's minimum
can retire an APK (its decision 9). The version code is read once, through
`PackageInfo.longVersionCode`, and handed to `Graph.init`.

The developer mode keeps up:
- **Presets.** The request console gains v2 presets, REST and Connect JSON.
- **The network record.** `NetworkRedaction`'s list of kept headers gains `grpc-status` and
  `grpc-message`, on purpose and nothing else (CLAUDE.md, «The developer mode's gate is not a
  lock»).

The diary's, GitHub's and the console's own clients are untouched.

### 6. Golden files hold the REST DTOs to the contract

The server's v2 tests write the canonical JSON of every response message they produce to
`server/tests/golden/v2/<Service>.<Method>.json`. A test fails if a file is stale against what the
server now writes.

`:core:data` reads that directory in place as a test resource, the way it already reads
`server/tests/vectors/`. One test decodes each file with the matching REST DTO:
- **Field names are strict**, so a renamed field fails;
- **enums are lenient**, as in production.

A field renamed in a proto and not in Kotlin then fails the next run of both suites. The files are
written by sub-project 3's tests as each stage's methods exist, and read here.

### 7. The generated code, its dependencies and R8

- **Where the generated code goes.** The generated Java and Kotlin lite go under
  `android/core/data/src/main/contract/{java,kotlin}`, added as extra source directories, as the
  programme says. `buf.gen.yaml` gains the three Android plugins at the spike's pinned versions:
  `protocolbuffers/java` and `kotlin` v36.2 lite, and `connectrpc/kotlin` v0.9.0. CI's Contract
  job then regenerates and diffs them like the Python. detekt skips the generated directories.
- **Versions.** The runtime libraries enter `libs.versions.toml` at the versions the spike built
  with: `protobuf-javalite` and `protobuf-kotlin-lite` 4.36.2, and connect-kotlin 0.9.0. Each
  carries a provenance comment, by the file's own rule.
- **The keep rule.** It ships as `:core:data`'s `consumerProguardFiles`, which does not exist yet.
  The spike's minified build failed without it.
- **`kotlin-reflect`.** The first attempt keeps it out: a serialization strategy over the lite
  runtime, without `moshi-kotlin` and the javalite extension. Only if that fails is
  `kotlin-reflect` forced to the standard library's version with a dependency constraint.
- **What a successful release build is checked for.** A Connect call is made from the minified
  APK on the emulator, with an `ErrorInfo` detail decoded. That is how the spike found the
  missing keep rule.

### 8. ETags survive the change of transport

`BundleTagStore` keeps opaque tags per class and year.
- **Over REST**, the window's tag arrives as `ETag`, and the 304 as a status.
- **Over RPC**, they arrive as the `etag` field and `not_modified`.

`ScheduleRemote.window(year, ifNoneMatch)` answers either the window with its tag, or «not
modified». `TimetableRepositoryImpl`'s handling of a 304 for a window it no longer holds stays as
it is.

**A tag stored under v1 never matches v2.** The window is shaped differently, so the first v2
fetch is a full one, and nothing needs migrating.

### 9. Stages, each following the server's

| Stage | Merges | Waits for |
| --- | --- | --- |
| **5a** | Generated code, dependencies, keep rule, `RemoteError`, the client-version header, the transport property, the golden-file test, and the four remotes 3a serves: the schedule window, `GetMe`, the diary's capabilities and `CreateDevice`, over REST and Connect | 3a deployed |
| **5b** | The other remotes over REST and Connect, the classifiers on reasons, and #270 closed | 3b deployed |
| **5c** | The gRPC binding and the streaming beta's `WatchClass` in the sync worker | 3c, and a host to point it at |
| **5d** | The new APK on the family's phones, then v1 deleted on both sides | 5b, and the owner's word that every phone has it (question 3) |

Until v1 is deleted, the app keeps calling v1 for every area whose remote has not landed. Each
area moves over whole, never half.

### 10. Testing

**Every remote binding is tested against MockWebServer with the wire format it really sends:**
- REST JSON;
- Connect JSON;
- gRPC over HTTP/2 with prior knowledge, once 5c lands.

**The classifiers and repositories:**
- the classifiers are tested on `RemoteError`;
- the repositories are tested once, over fake remotes.

**The table checks:**
- the golden-file test (decision 6);
- the credentials table (decision 2).

**The minified build:** decision 7's check on the emulator, from a minified build, for each stage
that adds a binding.

## Questions for the owner

1. **May the release build's default be `connect`, as the programme says, once 5a lands?**
   *Recommended: yes, and only once 5a's four methods are served by 3a in production.* Until then
   a `connect` build would call methods the server does not answer. Before that point, builds
   stay on v1.
2. **Should a debug build be able to switch transports at run time, from the developer mode?**
   *Recommended: no.* The programme chose the build, and a runtime switch would put all three
   bindings, and their failure modes, in every APK. Testing another transport means building
   another APK.
3. **Who says when every family phone has the new APK, so v1 can go?** *Recommended: you, in so
   many words.* The server cannot tell, since an old APK sends no `X-Lessons-Client`. After that,
   setting `MIN_CLIENT_VERSION` turns any phone that was missed into a clear «обновите
   приложение» rather than a broken screen.

## Risks

- **APK size.** The spike measured +611 KB (+13.8 %), mostly `kotlin-reflect`. Decision 7 tries
  the lean way first, and the programme's fallback, a thin Connect caller of our own over the same
  OkHttp, is still there if both ways fail.
- **The test churn is real.** About fifteen fakes and a dozen `HttpException` builders move. The
  rule that each classifier keeps its sealed type and outputs is what keeps the screens' tests
  still.
- **Two transports double the binding tests.** That is the price of the owner's choice of both.
  The remotes keep it to the bindings.
- **An area half-moved is worse than one not moved.** Decision 9's «whole, never half» is the
  guard.
