# Build console 6b: guarded device tools and production diagnostics

This implements #776, the second stage of approved #308 under programme #273. It builds
on 6a in #772, retaining the isolated Python project, closed commands, redaction,
queue and owned-process cancellation. Source preparation is detached from the
6a delivery branch; integrate only after that branch has landed and its exact dev
CI has passed. Host lifecycle and transport selectors remain 6c.

## User-visible slice

Device refreshes connected devices, selects an online serial and a recognized AGP
APK output, checks install eligibility, installs without destroying data, streams
app-filtered volatile logcat, and manages only the local-server reverse mapping.
Deploy reads production warmup on request and displays production condition and
compatibility with this checkout as two distinct answers. Missing prerequisites
explain which action is unavailable.

## Implementation sequence

1. Extend run metadata with structured, validated parameters and a volatile output
   policy. Existing Build/Contract tasks and transcripts retain their behavior;
   logcat never opens a transcript or enters history. Do not add a free-form shell.
2. Add tool discovery and strict parsers for online devices, AGP output metadata,
   package/version, installed package probes and supported apksigner output.
   Reject ambiguous, malformed or escaping paths. Every targeted command uses
   the selected serial. Tool discovery never installs an SDK or opens signing files.
3. Implement one guarded installation operation: snapshot/hash candidate bytes,
   verify its certificate, distinguish absence from a failed installed-package
   probe, pull/verify the installed base APK, reject a different certificate or
   lower version, then invoke only `adb install -r` and read back the result.
   Never uninstall, clear data, downgrade, reset adb or retry around a refusal.
4. Add PID-filtered volatile logcat and explicit add/remove/readback of
   `tcp:8000` to `tcp:8000`. Stop or explain a changed/stopped app; never broaden
   to device-wide logs or remove unrelated reverse mappings.
5. Add one bounded HTTPS GET to `/api/v1/warmup` from a non-secret configured
   origin. Reject credentials, redirects and invalid origins. Parse recognized
   fields only; never display arbitrary response/error bodies. Read the unique
   literal `EXPECTED_REVISION` assignment with AST without importing server code.
   Healthy responses omit expected_schema; a database-down 503 has no schema.
6. Wire Device/Deploy controls to these operations and the existing runner.
   Preserve visible selection ownership across asynchronous completion; do not
   apply an old response or action to a newly selected device/APK/origin.

## Verification and delivery

Use a small behavioral set for actual refusal and ownership boundaries, injected
adb/apksigner/HTTP results, volatile output and one headless interaction. Tests
must not invoke real Gradle, SDK tools, devices or production. Reuse the existing
runner regressions instead of reproducing their matrix. Run locked Ruff and the
small complete console suite once per final candidate; run affected repository
guards, one independent review and exact-head CI. New failures justify focused
correction. Actual device/production proof uses a fresh coordinated resource
window and remains separate from fixture results.

Update HANDOVER last inside the open batch. Deliver working -> dev, exact dev CI,
then pinned promotion -> master and source-matched production verification.
Preserve 5b's APK54 acceptance fixture, all failed evidence and installed-family
compatibility. No actual device operation is authorized merely by writing this plan.

## Measured candidate

The locked offline Windows suite passed 44 cases in 17.61 seconds, with Ruff
clean: 29 retained runner/console cases and 15 new Device/Deploy witnesses.
The existing integration guards passed 50 cases in 25.38 seconds, with three
document-fixture cases deliberately excluded from that isolated invocation.
One independent review rehashed all 13 frozen executable/test inputs, verified
CI parity and found no executable blocker. Its one documentation finding was a
misspelled environment variable; the guide now uses the actual
`LESSONS_PRODUCTION_ORIGIN`. No compatibility alias or product change was added.

The first 43-pass/one-parity-failure report is preserved: a failed local snapshot
refresh correctly left the changed workflow fingerprint red. The final reviewed
snapshot changes only the routing script to include the new AST reader's
`server/app/db.py` input. Tests used no actual adb, apksigner, HTTP, SDK, local
bridge, native or production operation. Remote Linux/Windows CI, integration and
production receipts remain separate requirements on the open delivery PR.
