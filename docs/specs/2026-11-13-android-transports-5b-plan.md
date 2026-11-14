# Android transports, stage 5b implementation plan

> Execute inline with `superpowers:executing-plans`. The owner requested the
> approved design's implementation, independent review, complete gates and
> delivery through dev/master on 13 November 2026; no second design approval or
> implementation handoff is needed. Issue #746, parent #273; defect #270.

**Goal:** Every feature area named for 5b uses its v2 binding, with domain-valued
remotes, reason-based failures and preserved user behaviour. Health and warmup
remain plain HTTP at their existing v1 paths, as the approved design requires.
Produce a verified signed candidate and deliver the exact checked
source through protected dev/master and production.

**Approved design:** `docs/specs/2026-10-05-android-transports-design.md` (#307),
approved on 5 October 2026. This plan implements stage 5b only. The existing
Schedule/Join migration and shared transport infrastructure remain the base.

**Baseline:** dev `89cddb513493cfef89934c5328a7f243559c6444` and accepted master
`dab632966012e9f546e8edb47b98ee4578593298` have identical trees. Completed 5a
evidence is historical baseline evidence, not a new 5b test run. Current
`docs/counts.json` recorded 1974 Android and 3910 server cases at plan admission. Fresh completed
reports determine the final counts.

**Implementation checkpoint, 14 November 2026:** Tasks 1–5 have their retained
behavioural RED/GREEN evidence. The full Android report contains 2166 passing
unique cases: app 733, data 953, design 205, model 145 and widget 130. The first full
run executed every task and exposed #755; after its correction and #756's
native-only ABI correction, the standard unfiltered gate passed with fresh app
tests and source-identical report reuse for the other modules. Ordinary R8 was
restored from cache, not newly compiled. Both ordinary assembles and all five
Detekt reports passed. The completed full server run passed Ruff and mypy
(242 files); bare pytest reported 3893 passed, 17 skipped and 15 warnings in
2348.06 seconds. All 3910 unique JUnit cases are retained; the eleven PostgreSQL
and six live-host skip identities and reasons match the preserved baseline.
No new local PostgreSQL or running-host execution is claimed.

Native packaging separately passed after the measured Result accessor correction.
The retained pair has no missing references within the declared structural scan,
and both referenced direct parent constructors are present. That scan is bounded;
the first failed pair and its diagnostic remain preserved. Named ContinuationImpl
helpers inherit create, while SuspendLambda helpers require both measured callbacks.
This does not certify a signed device run. The final affected-document guard run
passed all 168 selected cases without failures, errors or skips, using a separate
JUnit file; the full server report remained byte-identical. Independent whole-branch
review, exact-head CI, dev/master delivery, source-matched production, signed native
execution and Project 6 acceptance remain separate unfinished gates.

## Global constraints

- Work only in lessons, in the existing isolated TaskParent worktree. Preserve
  every other worktree, untracked file, ignored artifact and prior ledger.
- Branch `work/android-transports-5b` starts at dev. Git cannot simultaneously
  store `dev` and `dev/...`; the persistent owner-requested `dev` takes priority
  over the generic branch-prefix default. Working PR targets dev; a separate
  pinned promotion targets master. Every issue and PR uses existing milestone 11.
- Reuse the existing Kotlin/AGP/protobuf/Connect pins, generated lite messages,
  canonical DTOs, golden resources and shared server OkHttp client. No dependency
  upgrades, runtime transport switch, v2-to-v1 fallback or host streaming change.
- Move each area wholesale. No Retrofit DTO or generated proto crosses a remote
  boundary. Keep passwords solely on the upstream diary client; our server gets
  the upstream session, never the password. Anonymous methods remain unsigned.
- A send uses one coherent server/credential snapshot. A multi-call linking flow
  must not combine different memberships. Do not hold a DataStore lock over I/O.
  Keep cancellation and original no-answer exceptions; an answered refusal is
  RemoteError, with reason, status, metadata and violations preserved.
- Preserve missing/set/clear field semantics with explicit update masks, enum
  fallbacks, malformed-row handling and class-zone time. Proto timestamps are UTC
  instants and must be converted using the class zone, never the device zone.
- Class and diary identities remain separate. Only DEVICE_TOKEN_INVALID can
  cause class token rejection; role loss must not sign the device out. Existing
  process-wide CLIENT_TOO_OLD behaviour remains shared by all bindings.
- v1 remains served for installed APKs. 5c live gRPC/streaming and 5d physical
  family acceptance/v1 retirement remain separate. A candidate is not evidence
  of installation on the family's phones.
- Use existing JVM/JUnit/MockWebServer tests at the cheapest meaningful layer.
  Write a failing behavioural regression before the implementation. No new test
  kind, CI shape or native build shape without reading docs/engineering.md first.
- Serialize Gradle, server gates and native work through fresh coordinator grants.
  Never borrow another chat's resource slot or runtime evidence. UI/native work
  requires its own frozen scope and exact source/artifact identity.
- Tasks 1–5 retain their focused RED/GREEN evidence. Source stays uncommitted
  until Task 6 runs the full applicable gates over the whole cohort; no commit
  with lint or tests red. Focused runs never substitute for full report counts.
- Project prose, comments, commits and PRs are English; product resources are
  Russian with their English twins. Never read or publish secret values.

## Review focus

1. Credentials or server address change between GetMe and CreateLinkCode, or
   while UnlinkMe is in flight: no code or role from another identity is published.
2. Missing versus explicitly cleared nullable fields, empty update masks, unknown
   enum values and unknown future reasons: no accidental overwrite, exception or
   broad sign-out. A changed English message must not change the classified result.
3. Diary registration accepted before local persistence fails, cancellation during
   sign-out, and a late cached read after account change: preserve existing session
   ownership, retry decisions and cache-generation barriers.
4. Audit rows inserted between page turns, failed next/back reads and repeated taps:
   tokens stay opaque, history changes only on success, ranges and buttons describe
   the visible page, and a server change cannot reuse another class's history.
5. Class-zone conversion at UTC midnight/DST and minified real Connect execution:
   device zone never changes management dates, and all new binding/error-detail
   paths survive R8 without production test hooks or broader ordinary-release keeps.

## Task 1: Make every remaining failure classifier reason-first

**Files:** Modify `android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/repository/ManageModels.kt`,
`DiaryModels.kt`, `DiarySignInProblem.kt` and `catalog/SchoolDirectory.kt`; add
`network/v2/RemoteErrorSemantics.kt` for shared retry/status fallback; their
existing tests under `android/core/data/src/test/kotlin/.../repository/` and
`.../catalog/`. Add `network/v2/*RemainingFailureTest.kt` for the four classifiers
and common refusal cases, with `RemainingFailureFixture.kt` for synthetic errors.

1. Read each existing sealed failure type and its callers. Write literal cases
   for every approved reason, metadata.role, upstream=address-refused, failed
   preconditions, retry delays, no-v2 404/501/UNIMPLEMENTED, unknown reasons,
   REQUEST_UNDECODABLE, CLIENT_TOO_OLD, cancellation and original IO identity.
2. Run the new focused tests and observe assertion failures against current
   classifiers. Preserve raw output privately.
3. Add RemoteError branches, reading known reasons first and falling back to
   canonical HTTP status/code. Remove English-detail matching from the live
   management classification; keep legacy transport handling only where an
   actually retained caller still needs it. Do not introduce new UI failure types.
4. Run the classifier test cohort including existing cases; update expectations
   only for the explicit v2 semantic change, never to hide a failure.

**Test command:** `./gradlew --offline :core:data:testDebugUnitTest --tests '*RemainingFailureTest' --tests '*ManageFailureTest' --tests '*DiaryFailureTest' --tests '*DiarySignInProblemTest' --tests '*SchoolDirectoryTest'`.
**Expected:** New tests first fail for missing reason classification, then the
whole focused cohort passes; unrelated token/role and upstream outcomes stay distinct.

## Task 2: Move the whole linking flow to MeRemote

**Files:** Add `network/v2/MeRemote.kt`, `RestMeRemote.kt`, `RpcMeRemote.kt` and
their tests. Extend `V2Api.kt`, `V2Remotes.kt`, `NetworkModule.kt`;
modify `repository/DeviceLinkRepositoryImpl.kt` and `di/LessonsContainer.kt`.
Extend the existing test `network/v2/V2BindingFixture.kt`. Reuse
`network/v2/dto/MeDtos.kt` and generated MeService messages.

1. Specify domain-valued refresh/unlink operations and the request ownership
   boundary using existing CallObservation/send-time credentials. Add tests for
   linked and unlinked refresh, explicit link-code creation, unlink plus new code,
   whitespace/unknown roles, failed second call, identity change and cancellation.
2. Observe the new binding and repository tests fail before adding the methods.
3. Implement REST request annotations/paths and binary RPC methods. GetMe never
   implicitly creates a code; call CreateLinkCode only when unlinked. Publish
   latest link state only after the coherent sequence succeeds. Preserve last-role
   preference behaviour and do not add network I/O inside persistence locks.
4. Wire MeRemote into the real graph; keep v1 server endpoints unchanged.

**Test command:** `./gradlew --offline :core:data:testDebugUnitTest --tests '*MeRemoteTest' --tests '*DeviceLink*Test' --tests '*V2CredentialMatrixTest' --tests '*V2AnonymousBearerTest' --tests '*RequestSnapshotTest' --tests '*ServerNeedsHttpsTest'`.
**Expected:** REST and both RPC protocols return the same DeviceLink and refusals;
unlinked refresh creates one code, linked refresh none, and a changed identity
cannot publish a mixed result.

## Task 3: Move the diary and anonymous directory to domain-valued remotes

**Files:** Add `network/v2/DiaryRemote.kt`, `RestDiaryRemote.kt`, `RpcDiaryRemote.kt`,
`DiaryMappings.kt`, `DirectoryRemote.kt`, `RestDirectoryRemote.kt`,
`RpcDirectoryRemote.kt` and binding tests. Extend `V2Api.kt`, `V2Remotes.kt`,
`NetworkModule.kt`; modify `repository/DiaryRepositoryImpl.kt`, `DiarySignInImpl.kt`,
`DiaryMappers.kt`, `catalog/SchoolDirectory.kt`, `di/LessonsContainer.kt` and affected
existing diary/directory tests. Reuse all v2 DTOs and generated Diary/Directory messages.

1. Inventory every used method, including capabilities/providers, session handover,
   session deletion, students, schedule/homework/marks/periods, subjects/teachers/
   attendance if actually consumed, and correction list/create/reset/reset-all.
   Give the remote domain parameters and results; keep upstream credential shape
   explicit without passing a v1 wire DTO across the boundary.
2. Write failing wire/consumer tests for canonical paths/query/body, unsigned
   capabilities/registration/regions, diary bearer only for diary methods,
   optional presence, correction target byte preservation, unknown enums and
   malformed rows. Preserve existing session/cache ownership regression cases.
3. Implement REST and binary RPC mappings. Translate provider capabilities to the
   current domain, removing only the obsolete registration preflight check.
   Keep session age, spent-session, persistence failure and retry logic intact.
4. Return directory domain hits before catalog placement; retain local generic
   query handling and the separate region's own school-search HTTP client.
5. Wire both areas wholesale, then run binding and all affected diary/catalog/
   upstream/translation tests.

**Test command:** `./gradlew --offline :core:data:testDebugUnitTest --tests '*Diary*Test' --tests '*Directory*Test' --tests '*UpstreamHttpTest' --tests '*V2CredentialMatrixTest' --tests '*V2AnonymousBearerTest' --tests '*RequestSnapshotTest'`.
**Expected:** Full diary/directory cohort passes over REST and RPC fixtures; no
password or wrong bearer reaches our server/upstream, and account/cache barriers hold.

## Task 4: Move management, masks and class-zone mappings

**Files:** Add `network/v2/ManageRemote.kt`, `RestManageRemote.kt`,
`RpcManageRemote.kt`, `ManageMappings.kt` and binding tests. Extend `V2Api.kt`,
`V2Remotes.kt`, `NetworkModule.kt`; modify `repository/ManageRepositoryImpl.kt`,
`ManageMappers.kt`, `ManageModels.kt`, `di/LessonsContainer.kt` and management tests.

1. Inventory all 25 current management operations against proto HTTP bindings
   and deployed server handlers, including school search and request decisions. Model
   explicit domain edits (including absent/set/clear); do not leak JsonElement,
   Retrofit DTOs or generated messages into repository interfaces.
2. Write failing real-binding tests for minimal update masks, nullable clearing,
   join-mode-only edits, bell changes with HH:mm serialization, import conflicts/rejected rows,
   full-width IDs, unknown roles, device/access-request UTC timestamps converted
   to the class zone, school result paging and reason-driven role/token loss.
3. Implement both bindings over all methods the current app uses. Preserve
   trimming, meaningful count clamping and sorted/malformed bell behaviour.
   Require the class zone explicitly for timestamp mappings. Delete-class remains
   a user-triggered API feature; do not invoke destructive operations on production.
4. Wire the area wholesale and retain its guarded onTokenRejected behaviour only
   for DEVICE_TOKEN_INVALID. Run all management binding/consumer regressions.

**Test command:** `./gradlew --offline :core:data:testDebugUnitTest --tests '*Manage*Test' --tests '*V2CredentialMatrixTest' --tests '*RequestSnapshotTest'`.
**Expected:** Every used management call has equivalent REST/RPC domain output,
minimal masks preserve concurrent edits, and #270 is held by translated-message tests.

The management remote and the public AuditPage/log signature are coupled. Collect
Task4's binding/ownership RED together with Task5's current-boundary navigation,
management reset and same-class replacement-token regressions before changing that
signature. Only test dependency factories and page construction migrate afterwards;
keep observable assertions and opaque token expectations. Implement Tasks4/5 as
one source cohort and run their combined GREEN without a production offset bridge
or invented server total. Retain each task's actual results separately from full gates.

## Task 5: Replace audit offsets with token navigation and add bounded native proof

**Files:** Modify `repository/ManageRepository.kt`, `ManageModels.kt`,
`ManageRepositoryImpl.kt`, new management bindings, `android/app/src/main/kotlin/com/lumenpearson/lessons/ui/admin/LogActions.kt`,
`LogSheet.kt`, `ManagementState.kt`, `ManagementViewModel.kt` and affected app tests.
For #747, include `ManagementPlumbing.kt` and each action collaborator's async
completion boundary, so a class reset invalidates old reads/writes/role rechecks.
Extend `android/app/src/androidTest/.../TransportReleaseTest.kt` only after reading
the current native harness and `docs/engineering.md`; change native-only bridge
rules only for measured required ABI reachability. Update relevant `docs/build.md`.

1. Write failing tests for opaque next tokens, first/next/back history, failed
   navigation, repeat taps, refresh/reset and session/class changes. Keep row
   counts in UI navigation state, not a fabricated server offset.
2. Change repository log to pageSize/pageToken and domain AuditPage to entries/
   next token. On successful navigation update token history and displayed range;
   on failure retain the visible page and history. Reset on fresh sheet/session.
3. Derive a bounded native scenario from the actual new bindings: actual minified
   Connect call(s) plus decoded ErrorInfo, source-pinned signed pair, exact fixture,
   two read-only diary calls and one existing unissued-code join attempt. The join
   consumes attempt/throttle bookkeeping; it creates no account or membership.
   Require an empty diary/membership fixture, URL restoration and a crash check;
   send no account password, sign-in, sign-out or management write.
   Document precisely which successful binding paths are held only by JVM fixtures.
   Do not add production test hooks or relax release trust/ordinary R8 rules.
4. Run app pagination and data integration tests; acquire a new native grant only
   after source/artifact inputs and all cleanup/readback conditions are frozen.

**Test command:** `./gradlew --offline :app:testDebugUnitTest :core:data:testDebugUnitTest`.
**Expected:** Both modules' complete unit suites pass. Native execution is a
separate acceptance gate, never inferred from the unit result or APK assembly.

## Task 6: Complete gates, independent review and protected delivery

**Files:** Update `docs/architecture.md`, `docs/api.md`/`docs/build.md` where the
staged facts changed, `docs/counts.json`, the repository's measured count displays,
this plan and `HANDOVER.md` last, inside the still-open working PR. Move previous
batch history according to the existing handover convention. Preserve old receipts.

1. Self-check the whole diff against the approved design. Obtain a fresh serialized
   heavy grant. Run the exact unfiltered Android gate from android with JDK21:
   `./gradlew --offline test assembleDebug assembleRelease detekt`, then Python3.12
   `../server/scripts/check_test_counts.py android --root .` over completed reports.
2. From server with its Python3.12 environment run `ruff check app tests scripts migrations`,
   `python -m mypy`, bare `pytest -q -n auto` producing the canonical work/pytest.xml,
   then `python scripts/check_test_counts.py server --junit work/pytest.xml`.
   Inspect exact CI path selection; contract freshness runs if the final diff
   touches its defined inputs. Do not invent a new gate or call a path skip a run.
3. Update measured counts from completed reports and relevant documentation; rerun
   only checks affected by these final changes. Keep full and focused reports distinct.
   Commit the coherent green cohort, open a draft PR to dev with milestone11 and
   links to #746/#270/#273, and update HANDOVER within that open batch.
4. Produce the whole-branch review package from the original dev base. Dispatch one
   fresh independent reviewer on the most capable available model, with approved
   design, full plan, Review focus verbatim, diff and ledger rulings. Regrade by user
   effect. Fix Critical/Important findings once with observed RED/GREEN regression
   and complete gates; record deferred Minors without unrelated polish work.
5. Verify all five repository merge checks and the refusal rule. Wait for exact new
   head CI, merge the green working PR into dev, verify exact dev push CI, then open
   and merge a pinned dev/master promotion after its exact-head CI. Carry closing
   references for #746/#270 only once their criteria are actually met. Preserve any
   incomplete acceptance as Verification; never close on implementation alone.
6. Verify master CI and production READY/source identity, aliases, authenticated
   warmup and bounded runtime-error query. Obtain and cryptographically verify a
   source-pinned production-signed native candidate; execute the frozen native
   scenario and Home/reopen under fresh coordinated grants, independently read back
   evidence and release resources normally with fixture data retained.
7. Reconcile issue/Project6 metadata using its current README and fresh source-event
   evidence; independently read back mutations. Update post-merge evidence on the
   existing delivery records, avoiding a closeout-only source PR. Delete only the
   verified merged working ref, retain dev/master and every worktree/WIP, and put
   the owned checkout on checked master. Leave parent #273 open for later stages.

**Test command:** The complete commands in steps1–2; source-pinned CI/native/
production/Project checks in steps4–7 are separate recorded gates.
**Expected:** Fresh full reports and all applicable CI jobs pass; independent
review has no unresolved blocking findings; actual checked master is deployed;
native/metadata evidence matches that source. Report any external blocking gate
with exact evidence, without claiming the unfinished gate passed.
