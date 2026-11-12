# Android transports 5a implementation plan

> **For agentic workers:** Use `superpowers:executing-plans` task by task, with
> RED → GREEN and one fresh whole-branch review after the final task. The owner
> explicitly requested autonomous continuation; do not reopen architectural approval.

**Goal:** Add v2 transport infrastructure and move the whole schedule-window and
join areas while preserving repository behavior and every installed v1 contract.

**Architecture:** Domain-valued remotes isolate repositories from REST DTOs and
generated Java lite messages. One send-time credential snapshot signs the shared
server client; a per-call RPC adapter retains observed HTTP status. Application-owned
graph initialization supplies one build configuration to foreground and background.

**Tech stack:** Existing Kotlin/AGP/Compose/Retrofit/OkHttp; Buf Java plugin36.2,
protobuf-javalite4.36.2, connect-kotlin0.9.0, kotlinx.serialization and Java lite.
The three new pins come from the approved, successfully built spike, not latest versions.

**Spec:** [Approved Android transports design](2026-10-05-android-transports-design.md),
approved in #307. Programme #273 stage4 is delivered through #731/#732: dev
`2503c553`, master `98139d49`, signed main46 and bounded Studio checks passed.
This plan reconciles the original author's preserved six-task draft with that tree.

## Global constraints

- Russian product source and English translation twin; English project prose/code.
- Java lite and Connect outputs both use `android/core/data/src/main/contract/java`.
  Generate the pinned google.rpc input too. Add no protobuf Kotlin builder DSL,
  connect javalite reflection extension, moshi-kotlin or kotlin-reflect runtime.
- Existing AGP9.5.1, Kotlin2.4.21, JDK21, compileSdk37 and minSdk26 stay pinned.
- `lessons.transport=rest|connect|grpc`, default connect; app streaming is accepted
  only with grpc. `LESSONS_APP_STREAMING` is distinct from host `LESSONS_STREAMING`.
- Exactly schedule and join move in production. Me/link-code, diary, management
  and directory stay on v1 until5b. No v2-to-v1 fallback, minimum-client change,
  family installation or v1 removal. Host streaming/live gRPC acceptance remains5c.
- Generated types/DTOs stay below repository boundaries in core:data. A success
  is a domain value; a server refusal is RemoteError, never IOException; without
  an answer the original exception and cancellation identity survive.
- Use all75 unary DTO shapes as contract infrastructure; list WatchClass explicitly
  as non-REST/streaming. Staged production composition never excuses skipped DTOs.
- Preserve school-zone clocks, whole-year bounds, cache transactions, keyed
  single-flight, missing-row unconditional retry and callback/persistence order.
- Existing v1 keys remain until ordinary lifecycle wipes remove them. Define v2
  tag prefixes jointly with every class/window/retained-membership wipe.
- Use the existing isolated TaskParent worktree and one `work/...` branch from dev.
  A persistent branch named dev prevents a `dev/...` ref in Git's namespace.
  Preserve every other worktree and original evidence, including ignored artifacts.
- No commit with lint/tests red. Tasks 1–5 finish their named focused checks;
  their source stays uncommitted until Task 6 runs both complete repository gate
  sets on the entire cohort. The first source commit therefore contains all five
  implementation tasks. This avoids repeating the measured 37-minute server
  suite after each infrastructure step while preserving the full pre-commit
  gate. Focused reports never replace complete reports, and the final review
  covers the whole range from the original dev base.

## Review focus

1. Credentials/address change after request creation or during concurrent calls:
   every send uses the current coherent snapshot, with no cross-call status leak.
2. Unknown enum99/future enum names and a newly added nested field: preserve
   declared fallbacks and repeated values, but reject unregistered fields.
3. A real refusal wrapping IOException versus no answer/cancellation: retain
   refusal provenance, observed HTTP status and original no-answer identity.
4. A worker or notification is first after process restart: initialize the same
   transport/version once, with no background default or container race.
5. R8 keeps the app buildable but strips error-detail reflection: the actual
   signed minified Connect call must still classify a decoded ErrorInfo reason.

## Task 1: Generate the lite contract and its runtime codecs

**Files:** Modify `buf.gen.yaml`, `android/gradle/libs.versions.toml`,
`android/core/data/build.gradle.kts`, `.github/workflows/ci.yml`,
`server/tests/test_contract.py`, `server/tests/test_ci_paths.py` and
`docs/engineering.md`. Create `android/core/data/consumer-rules.pro`, generated
`android/core/data/src/main/contract/java/`, and
`android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/network/v2/LiteProtobufStrategy.kt`
and `LiteErrorDetails.kt`. Tests live in the matching core:data test package as
`LiteProtobufStrategyTest.kt` and `LiteErrorDetailsTest.kt`.

**Interfaces:** `internal class LiteProtobufStrategy : SerializationStrategy`
implements the exact connect-kotlin0.9.0 API; `internal object LiteErrorDetails :
ErrorDetailParser` provides `unpack(any: AnyError, clazz: KClass<E>): E?` and
`parseDetails(bytes: ByteArray): List<ConnectErrorDetail>`. Its stable type registry
maps ErrorInfo, BadRequest and RetryInfo to their literal protobuf names.
Codecs accept generated MessageLite classes, serialize to Okio Buffer, parse a
BufferedSource and support deterministic bytes. No generated type escapes a remote.

- [x] Extend the existing contract plugin/runtime guard first. Require exactly
  Python's existing pins plus `buf.build/protocolbuffers/java:v36.2` with lite and
  `buf.build/connectrpc/kotlin:v0.9.0`; both outputs use the specified directory.
  Compare Android pins with TOML runtimes4.36.2/0.9.0. Run the existing pin test;
  Expected: FAIL because the Android plugins/runtimes are missing.
- [x] Add generation/dependencies and consumer rules. Exclude moshi-kotlin,
  supply Moshi core1.15.2 as resolved in the spike, and preserve generated lite
  fields plus `getDefaultInstance` needed by Java reflection. Register the extra
  source directory. Exclude generated code with installed
  `dev.detekt.gradle.Detekt`, never the obsolete1.x task package.
- [x] Add independent codec assertions before implementation: a real generated
  CreateDeviceRequest(code=`DEMO24`) has literal bytes `0a0644454d4f3234`; parsing
  restores its code; ScheduleWindow round-trips optional/nested fields; ErrorInfo
  metadata maps inserted in opposite order have equal deterministic bytes. Unknown
  classes are rejected. Error-details tests parse a real Status containing known
  and unknown Any payloads, unpack the three supported detail types, preserve an
  unknown payload and return null for a mismatched type. Observe each missing
  behavior before implementing it; Expected GREEN only after actual codec behavior.
- [x] Extend Contract freshness to diff Android generated output. Make the catalog
  trigger Server, and generated changes trigger Contract. Extend existing CI-path
  checks for those consumers. Record the build rationale in engineering.md.
- [x] Run Buf lint/generate twice; Expected: no second diff, unchanged Python
  generated output. Run `pytest -q tests/test_contract.py tests/test_ci_paths.py`
  and core:data tests; Expected: PASS. Run release assembly/R8 and inspect
  releaseRuntimeClasspath; Expected: no kotlin-reflect/moshi-kotlin. Retain source
  uncommitted for Task 6's complete cohort gates and English commit.

## Task 2: Canonical goldens and strict REST DTO infrastructure

**Files:** Create `server/scripts/write_v2_goldens.py`,
`server/tests/test_v2_goldens.py`, `server/tests/golden/v2/`, and focused DTO files
under `android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/network/v2/dto/`.
Create `CanonicalMethods.kt`, `WireEnumSerializer.kt` and `Int64StringSerializer.kt`
in that package, plus `V2GoldenTest.kt` in the matching test package. Modify the
existing core:data test-resource inputs, CI path tests/workflow and engineering rationale.

**Interfaces:** Writer `main(argv: list[str] | None = None, *, root: Path | None = None)
-> int` compares by default and rewrites only with `--update`. The index identifies
full method, credential, request/response type, fixture samples and explicitly unread
fields. `CanonicalMethods` supplies a request serializer and response serializer for
every unary method; WatchClass has an explicit non-REST row. DTOs have defaults,
lowerCamelCase names, string int64 encoding and name-or-number enum serialization.

- [x] Add tests that alter a fixture/nested descriptor field, omit a method and
  omit a oneof alternative. Default comparison returns nonzero and preserves all
  original bytes; explicit `--update` writes deterministic canonical files.
  Credentials enumerate all76 methods and fold DEVICE_LINKED to device. Expected
  RED: writer/index missing; GREEN: every defect independently detected.
- [x] Implement descriptor-owned fixtures with the real protobuf-py JSON runtime.
  Exercise every request/response field recursively and each oneof alternative.
  Include default/optional absence, large int64, timestamps, date/time strings,
  enum99 and a future enum name. Never use the writer to derive Android expectations.
- [x] Implement strict DTO registry tests: decode every indexed response, encode
  every indexed request and compare literal canonical JSON trees. Reject an extra
  unrelated key; preserve known repeated elements around unknown enums. Any field
  intentionally unread by domain mapping has a named reason, never broad leniency.
- [x] Make both CI arms consume golden changes and register all external Gradle
  inputs. Run writer default mode, its focused server suite and
  `:core:data:testDebugUnitTest --tests '*V2GoldenTest*'`; Expected: complete strict
  coverage, no silently skipped unary method. Retain source for Task 6's full gates.

## Task 3: Send-time credentials and refusal provenance

**Files:** Add focused files under core:data `network/v2/`: `Credential.kt`,
`BearerInterceptor.kt`, `ClientVersionInterceptor.kt`, `RemoteError.kt`,
`RestRefusals.kt` and `RpcCalls.kt`. Modify existing `NetworkModule.kt`,
`AuthInterceptor.kt` and `DiaryAuthInterceptor.kt`. Add matching network tests and
extend `UpstreamHttpTest.kt`.

**Interfaces:** Runtime-retained `@Credential(CredentialKind.NONE|DEVICE|DIARY)`;
`BearerInterceptor(credentials: () -> RequestCredentials)`; explicit full RPC
method credential table held to Task2. `RemoteError` carries a canonical code,
string reason, metadata, message, nullable observed HTTP status, field violations
and retry information. `RpcCalls` retains status per call and returns domain-bound
success or throws RemoteError/original no-answer exception, never a global status slot.

- [x] Write real MockWebServer assertions for all four anonymous methods on REST
  and Connect, device/diary/no token, both tokens present, unknown/unannotated
  methods, configured base-path prefixes and credential/address changes between
  construction and send. Assert actual received Authorization/version headers,
  never merely interceptor registration. Expected RED for leaked v2 bearer.
- [x] Restrict both v1 signers to actual v1 paths, preserving existing v1 behavior.
  Resolve v2 REST from Retrofit Invocation and RPC from exact full method paths.
  Anonymous/unknown methods remove stale server Authorization. Preserve upstream,
  GitHub and console-client isolation and credential/log redaction.
- [x] Test known/unknown ErrorInfo reasons, BadRequest violations, RetryInfo,
  malformed/non-JSON bodies and reasonless404/501/503/504. Concurrent success,
  refusal and offline calls keep independent observed status. A fake response
  header cannot spoof that status. A refusal with IOException cause remains a
  refusal; original timeout/address/HTTPS/cancellation objects retain identity
  when no answer arrived. Implement the two decoders and per-call RPC seam.
- [x] Run `:core:data:testDebugUnitTest` and detekt; Expected: all matrix cases
  pass and no credential/log leak. Retain source for Task 6's full gates.

## Task 4: One build/runtime configuration and client compatibility

**Files:** Modify app Gradle, `.github/workflows/apk.yml`, LessonsApplication,
core:data `di/Graph.kt`, `di/LessonsContainer.kt`, SyncWorker, SchoolAlerts and
the existing update-state presentation. Add `di/GraphOwner.kt`, compatibility
state and matching tests; add required Russian text and English twin.

**Interfaces:** Required public `Graph.init(context: Context, transport: String,
clientVersion: Int, appStreaming: Boolean, githubClientId: String = "",
githubProject: GithubProject = GithubProject.UPSTREAM)`. App-owned
`GraphOwner.initializeLessonsGraph()` passes actual app BuildConfig values;
`Graph.ensureInitialized(context: Context)` uses it for early/background callers.
Internal transport selection chooses REST or the shared RPC binding with the
requested unary Connect/gRPC protocol. Latched compatibility state exposes an
update-required event independent of foreground collection.

- [x] Extend build-property reach tests for rest/connect/grpc, connect default,
  invalid values, app-streaming-only-with-grpc and separation from host streaming.
  Observe RED before wiring properties through workflow and required Graph inputs.
- [x] Test first worker/notification initialization, foreground-before-background
  and concurrent initialization: one container and identical actual config, no
  missing/default transport/version. Keep test cleanup in test utilities.
- [x] Test CLIENT_TOO_OLD arriving with no Activity collector: foreground later
  presents update once, worker returns permanent failure, memberships/cache stay.
  Wire the existing update flow and localized text. Clarify that warmup API1 is
  the server's reported version. Do not infer a v1 response means v2 is unavailable.
- [x] Prove actual selected unary protocol bytes. The common RPC infrastructure
  must not silently select Connect for grpc; live host/streaming acceptance stays5c.
  Run app/core:data tests, translation and property reach checks; Expected: PASS.
  Retain source for Task 6's full gates.
- [x] Include the approved design's developer visibility: v2 REST/side-effect-free
  Connect GET presets and the two explicitly allowed gRPC response headers.
  Keep warmup's reported server API separate from the selected app transport.

## Task 5: Migrate the two whole production areas

**Files:** Create `JoinRemote.kt`, `ScheduleRemote.kt`, `RestJoinRemote.kt`,
`RpcJoinRemote.kt`, `RestScheduleRemote.kt`, `RpcScheduleRemote.kt` and focused
mappers under core:data `network/v2/`. Modify SessionRepositoryImpl,
TimetableRepositoryImpl, JoinFailure, BundleTags, PreferenceKeys and LessonsContainer.
Adapt existing ConditionalSyncTest, ConcurrentSyncTest, SyncWindowTest,
TokenRejectedTest, BundleTagsAndRolesTest and SessionEffectsTest.

**Interfaces:** `internal interface JoinRemote { suspend fun create(code: String,
deviceName: String?): Session }`; `internal interface ScheduleRemote { suspend fun
window(openingYear: Int, ifNoneMatch: String?): ScheduleRemoteResult }`.
`ScheduleRemoteResult.Changed(timetable: Timetable, etag: String?)` and
`NotModified(etag: String?)` are domain-valued. Mappers consume an injected Clock
for existing absent/malformed generated-at fallback. Repository public types stay.

- [x] Characterize literal repository outcomes/order before replacing network
  boundaries: normalized join input; joined-class cache wipe before new bearer
  persistence and callback; school-zone whole-year bounds; single-flight; exactly
  one unconditional retry when not-modified names missing rows; server-resolved
  class ID; persist-before-tag and exactly one notification callback.
- [x] Test actual REST200/304/other refusals and RPC etag/notModified/changed
  responses. Preserve optional fields, timestamp fallback and unknown enum domain
  mapping. Every non-success response is rejected before treating its body as data.
- [x] Add v2 tag namespace jointly to preferences, fetch and every lifecycle wipe.
  Populate v1/v2 tags for two classes/two years, then evict/leave/retain one/sign-out;
  assert only the intended entries go and no v1 tag is sent on v2.
- [x] Test JoinFailure reasons separately: JOIN_CODE_UNKNOWN, CLASS_INVITE_ONLY,
  DEVICE_LIMIT_REACHED, THROTTLED, reason-bearing FEATURE_UNSUPPORTED and reasonless
  missing-v2. Only DEVICE_TOKEN_INVALID triggers sync token rejection; unrelated401
  and CLIENT_TOO_OLD retain membership. Preserve cancellation identity throughout.
- [x] Bind only these two complete areas to v2. Me/link-code and all other areas
  continue their v1 adapters. Run complete repository tests; Expected: unchanged
  characterized behavior and all new binding cases pass. Task 6 runs both full
  gate sets before committing the complete cohort.

## Task 6: Minified proof, independent review and delivery

**Files:** Add app release-target instrumentation under
`android/app/src/androidTest/kotlin/com/lumenpearson/lessons/TransportReleaseTest.kt`
and narrowly extend app build/APK workflow for a separate signed native-check
artifact. Update measured counts, engineering/build documents and HANDOVER.

**Interfaces:** The native harness calls existing public settings/Graph/session
repository APIs; no production debug entry point or test-only production method.
Use the same release certificate through existing protected-master Actions secrets,
never retrieve their values. Ordinary `lessons-apk` still contains one main APK.

- [x] Hold harness workflow/source pinning, variant/signing and isolated artifact
  naming with existing reach tests first. Verify installed AGP9.5.1 release-test
  API against its actual cached API/source before editing build configuration.
  Record why this device layer is required in engineering.md: JVM codecs do not
  establish R8 survival. A build or signature failure is not native evidence.
- [x] Pin a synthetic four-character code `!5A!`: accepted by current schema's
  length/control validation, never issued by the alphanumeric class/invite
  generators. Add a server fixture assertion that it returns JOIN_CODE_UNKNOWN
  without membership/device creation. An invalid attempt still consumes a throttle
  slot; make one bounded native call, never retry a throttle or unexpected answer.
  Assert the public repository's UnknownCode over minified Connect, which requires
  ErrorInfo decoding; a reasonless404 must instead classify server-too-old. No
  account, family code, UI Continue or Terms acceptance is involved.
- [ ] Run complete server and Android gate sets serially, with dedicated complete
  reports; update counts from them. Build the whole-branch review package and use
  one fresh independent most-capable reviewer with this Review Focus and rulings.
  Re-grade findings, fix Important/Critical once with RED→GREEN/full green suite,
  defer Minors in full and do not dispatch a re-review.
- [ ] Update HANDOVER inside the still-open working PR to dev/M11. Wait exact-head
  CI and pinned merge, dev push CI, separate promotion/M11/CI/pinned merge, master
  CI and exact production/aliases/warmup/runtime checks. Dispatch the existing
  expected-commit signing flow only on the protected reviewed master.
- [ ] Retain/verify actual signed artifacts, then obtain a fresh coordinated UI
  slot and run bounded minified Connect/ErrorInfo proof plus normal update/startup.
  Record actual scope, data retention and normal own-VM shutdown. Complete Project
  readbacks, preserve persistent master/dev and retire only the merged working ref.
  Final Expected: all gates/readbacks green;5a complete,5b/5c/5d still separate.

## Commands and report ownership

Use the own server Python3.12 environment. Run bare pytest, not `python -m pytest`.
All focused JUnit paths are distinct from the completed full report. Android gates
run serially on JDK21 with the wrapper and own Gradle home; use offline after the
new pinned artifacts are available. Do not overwrite full module reports with a
filtered run and then credit them as complete. Full gates are exactly AGENTS.md:
ruff, mypy, pytest `-q -n auto`, both report-count checks, Gradle test/debug/release/
detekt, and Contract Buf when its paths change. AGP lint is not a gate.

## Plan self-review

Task1 codecs feed Task3 RPC refusal provenance and Task5 bindings; Task2 registry
feeds Task3 signing and Task5 REST mapping; Task3 feeds Task4 compatibility and
Task5 classifiers; Task4 required configuration feeds Task5 composition; Task6
uses public repositories after Task5. Each producer/consumer name above matches.
Every Review Focus row has an assertion in its owning task. The staged-DTo scope,
background configuration seam, v2 wipe namespace and per-call status are explicit.
No extra architecture approval or family/v1-retirement permission is inferred.
