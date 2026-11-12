# Signed native harness linkage correction (#743)

> **For agentic workers:** Use `superpowers:executing-plans` inline, RED to
> GREEN, with one independent review of the whole correction. The owner asked
> for autonomous correction and delivery; preserve every unfinished worktree.

**Goal:** Make the existing signed/minified transport harness start and reach
its one bounded repository call without weakening codec shrinking.

**Architecture:** The test APK is a separate R8 consumer of public target-app
APIs. Preserve only its measured external entry points in the native-check
target, allow their names to be obfuscated, and let AGP apply the target mapping
to the test APK. Ordinary release configuration and generated codec rules stay
unchanged; no new production method or test interface is introduced.

**Tech stack:** Existing AGP9.5.1, Kotlin2.4.21, JDK21, compileSdk37, AndroidX
runner1.7.0 and resolved tracing2.0.1; no new dependency or version.

**Spec:** [Approved transport design](2026-10-05-android-transports-design.md),
decision #307 and [5a Task6](2026-11-12-android-transports-5a-plan.md).

## Constraints and evidence

- APK49 at master `c800491f306d3f9489c66906f2639bc46cda4878` failed its one
  instrumentation attempt in AndroidJUnitRunner.onCreate with missing
  `androidx.tracing.Trace`; neither retained APK defines that class.
- The test also references four target types eliminated by main R8: Graph,
  LessonsContainer, SessionRepository and SettingsRepository. A wider scan found
  removed settings-copy/getter and Result boxing methods and Kotlin runtime
  facades used by the harness. The actual coroutine subclass overrides also
  disagree with renamed/reordered target callbacks. Fix the measured boundary as a whole, rather than
  repeating native attempts to discover one missing class at a time.
- Keep the existing main/test signing certificate, exact source/hash guards,
  Connect selection, synthetic `!5A!`, membership checks and 90/75/5-second
  budgets. Do not claim the failed runner reached the repository or its finally.
- Do not retain generated messages, codec implementations, v2 remotes or
  repository implementations for the harness. Preserve only the external
  declarations the separate APK actually calls. Allow obfuscation and internal
  optimization; the actual Connect ErrorInfo decoder still runs under R8.
  Only BaseContinuationImpl.invokeSuspend and its two-argument create callback
  keep their names/prototypes, matching the separate test's real overrides.
- Apply target bridge rules only with `lessons.nativeCheck=true`; ordinary
  debug/release behavior, v1 answers and all dependency pins stay unchanged.
- One working branch from dev in the existing owned TaskParent worktree; no
  edits in Phone or other worktrees. Commit only after all relevant full gates.
- No Gradle or native work before its fresh serial resource agreement. A failed
  native attempt is never retried; a corrected artifact needs a new exact-source
  signing run, independent verification and fresh native slot.
- Project6 readback remains required for formal acceptance. Existing CLI lacks
  read:project and the prior browser policy denial is retained; do not escalate
  scopes or use another route.

## Review focus

1. Native-only preservation leaks into ordinary release or covers transport
   implementations: refuse a green proof made by disabling their shrinker.
2. Kotlin suspend/Result names or descriptor types change: keep only the real
   JVM boundary and verify actual packaged linkage after both R8 passes.
3. Test-side app references remain unresolved despite a successful assembly:
   inspect actual class/member definitions, not strings or mapping alone.
4. Host packaging succeeds but runner/reflection still fails: only a successful
   signed device result establishes R8/ErrorInfo acceptance.
5. Failure occurs before or during the bounded call: preserve logs, unknown
   reachability/restoration where appropriate, and stop without another call.

## Task 1: Preserve the measured external harness boundary

**Files:** app/build.gradle.kts, a new app/native-check-target-proguard-rules.pro,
and the existing app BuildPropertyReachTest. Engineering rationale is recorded
before extending the existing wiring-policy test.

- [ ] Archive existing full Android reports and worktree baseline. Write a
  policy test before configuration changes. It must require native-only
  registration, exact allowed target types/member surface, obfuscation allowed,
  no blanket class/member keep, and correct Gradle inputs for the new rules.
  Run the focused class. Expected: RED because bridge rules/registration are
  missing; old native49 crash and actual DEX linkage are independent RED proof.
- [ ] Add narrowly scoped target rules for Graph.INSTANCE/ensureInitialized/
  getContainer; the two LessonsContainer getters; SessionRepository.currentAll
  and the actual Kotlin-mangled join; SettingsRepository.getSettings/update;
  and Trace.beginSection/endSection/forceEnableAppTracing. Register them only
  for nativeCheck, with test-task input tracking. Expected: focused GREEN.
- [ ] Include the measured AppSettings.getBaseUrl/copy$default and Result
  box-impl/unbox-impl; LazyKt.lazy, ResultKt.throwOnFailure, IntrinsicsKt's
  suspended marker, SpillingKt.nullOutSpilledVariable, BuildersKt.runBlockingK's
  default bridge/withContext, TimeoutKt.withTimeout and FlowKt.first. Preserve
  only their actual declaring members; the two inherited facade owners need
  class-only roots. Add the two measured BaseContinuationImpl callback members
  with stable names/prototypes, without pinning the class name or its other
  implementations. The existing policy test holds the complete seventeen-type,
  twenty-five-method/one-field boundary. Expected: no wildcard implementation
  keep, optimizer disabling, dependency change or extra native invocation.
- [ ] Make one corrected private-fixture signed native pair, for inspection
  only. Compare its target/test DEX definitions and referenced member signatures
  with retained49, following inherited method resolution and all four actual
  coroutine subclasses' callback overrides. Expected: no unresolved measured bridge; target/test mapping
  agrees; production codecs/implementations remain obfuscated. No device install
  or native call belongs to this diagnostic.

## Task 2: Full relevant gates and independent correction review

**Files:** docs/counts.json and its canonical Android declarations, build and
engineering documents, and HANDOVER inside this correction's open working PR.

- [ ] Freeze runtime/config/test source and run ordinary Gradle test,
  assembleDebug, assembleRelease and detekt serially. Archive complete reports
  separately from focused/failed runs. Expected: all green, no native property
  contaminates ordinary JVM test manifests.
- [ ] Reconcile completed Android counts and declarations; record which server
  source remained unchanged. Run relevant document/count policy checks with
  distinct reports. Expected: measured values match, no borrowed full reports.
- [ ] Commit the complete correction only after relevant lint/tests are green;
  open its milestone11 working PR to dev, update HANDOVER last inside it, and
  obtain one fresh whole-branch independent review. Expected: zero remaining
  Critical/Important; fix such findings once with RED/GREEN/full relevant gates,
  preserve deferred Minors in full without a second review.

## Task 3: Deliver and complete actual native acceptance

- [ ] Wait exact-head CI, five merge preconditions and pinned working merge;
  wait dev CI, separate promotion/CI/pinned master merge, master CI and exact
  production/alias/health/warmup/log readbacks. Preserve master/dev and all WIP.
- [ ] Dispatch one expected-commit signed native build on reviewed master.
  Retain and independently verify both actual APKs and receipt. Expected:
  matching real certificate/source/version/hashes; do not modify APK49 in place.
- [ ] Obtain a fresh native resource slot; perform the separately bounded
  corrected-artifact proof and actual Home/reopen, preserving the fixture and
  observing normal own-VM cleanup/qualified release. Expected: actual test PASS
  and source marker, UI/PID/hash/crash/install-time evidence, no unmeasured claims.
- [ ] Read back issue/PR/Project metadata when permitted. Close #743 only with
  its criteria satisfied; keep 5a formally incomplete while Project or native
  acceptance is blocked. Post-merge facts belong in this open batch's existing
  PR delivery receipt, not a close-out-only source PR.
