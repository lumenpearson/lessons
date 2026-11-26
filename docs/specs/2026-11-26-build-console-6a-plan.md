# Deliver the first useful build console (6a)

The owner approved the [console design](2026-10-05-build-console-design.md) in
#308. Issue #771 owns this implementation under #273, milestone 11. On 26 November
the owner explicitly asked for useful progress across independent programme work,
with development time and quality considered together. The pending 5b device gate
does not block 6a: the approved stage table gives 6a no transport prerequisite.

## Outcome and boundaries

`uv run --project tools/console lessons-console` opens four usable tabs: Build,
Contract, Environment and Docs. A person selects a closed, described task, sees its
queued/running/result state and redacted output, can cancel its owned process tree,
and can inspect the saved history. Tool or checkout mismatches explain which tasks
are unavailable. Environment displays setting names and set/missing status only.

Device/Deploy are 6b; transport selectors and local Host lifecycle are 6c. The
console does not deploy production, run free-form shell text, read signing-password
properties or install over a phone. A conventional machine-wide lock coordinates
console jobs; it does not supersede an external operator's resource ownership.
An occupied emulator requires a per-job contention choice. An idle machine does
not require an internal agent approval ceremony.

## Reconcile the approved design with the current tree

- Start from dev `f6865bd8`, tree `77d2561d`, identical to the checked 5b master
  `6776e663`. Keep its signed-source acceptance checkout and all older WIP intact.
- Read current CI rather than reproduce October's job list. Host matrix jobs and
  the final aggregate now exist. Explicitly fingerprint their current exceptions
  until 6c supplies a local Host row; never label a represented subset all of CI.
- Local Buf breaking comparison uses `origin/master`, as current `docs/build.md`
  specifies. CI uses its actual pull-request base. Keep that difference visible.
- The current machine's conservative Gradle cap is one worker. Normalize only
  declared local flags for parity; never turn a changed gate or unknown action
  into a skip by default.
- Keep dependencies in this project's own lock. Copy Textual and PyYAML versions
  from their published package releases, as the approved design prescribes.
- Defer the optional signing-status Gradle task and LF-attribute follow-up from
  this first useful console batch, avoiding unrelated Android source churn.

## Implementation batches

1. **Closed tasks and discovery.** Implement the isolated Python 3.12 project,
   server/Android/Contract rows and compound commands. Resolve the server's own
   environment, verify its editable import location, discover JDK 21 by release
   metadata and read Buf version/checksum from existing CI/docs. Read configuration
   names without exposing values. Prepare only on explicit task selection.
2. **Run, queue and cancel.** Stream output after redaction to the screen and log.
   Serialize heavy jobs using one external OS-backed file lock, preserving light
   work. On Windows, own the complete child tree through a Job Object; retain the
   lock until descendants exit. Persist useful result/history metadata.
3. **Four working tabs.** Supply task selection, missing-tool reasons, run/cancel,
   queue/result/history display and Markdown navigation. Keep unknown markup and
   subprocess output inert. Exercise the visible flow with a Textual pilot and
   fake tasks instead of requiring an emulator.
4. **Current CI parity and maintenance.** Hold every real job/action/script and
   gate row to an explicit mapping or reviewed exception, including conditions,
   both generated trees and output APK checks. Add the path-routed console job
   on Linux and Windows, require it through the existing aggregate, and add its
   isolated uv project to the existing dependency cohort.
5. **Review and delivery.** Run the small console suite and Ruff, plus relevant
   existing repository guards. Complete one independent review of the final
   candidate. Update HANDOVER inside the open batch. Use working PR -> dev and
   pinned dev -> master promotion, recording actual exact-head CI and production
   source readback without repeating unchanged Android/device acceptance.

## Verification sized to the question

Use roughly a few dozen behavioral witnesses: CI drift, wrong tool/venv, fake
heavy/light ordering, a cancelled child with a grandchild, secret forms and chunk
boundaries, and one headless four-tab interaction. These tests start no Gradle,
Buf, adb, VM or live service. They are not substitutes for actual external-tool
execution and must not be presented as such.

The console's commands are:

```text
uv run --project tools/console --locked ruff check tools/console/lessons_console tools/console/tests
uv run --project tools/console --locked pytest -q tools/console/tests
```

The CI job runs the equivalent commands from `tools/console`. Existing server
workflow/count/reference guards cover integration changes. The unchanged 5b app
and server baseline remains revision-bound evidence, not a newly executed suite;
the working PR's exact-head CI must run every job its changed paths select.
Only new changes or failures justify another focused correction and affected
verification. Heavy local or device work uses fresh coordinated resources.

## Coordination

The console batch owns `tools/console`, its CI/dependency routing and its own
documentation. Separate detached work on 5c may implement streaming, while 7's
detailed standalone design and parity map can proceed in parallel. Merge/ref
updates serialize through the one temporary branch; source work does not wait
for a native resource slot. The user's number 8 denotes documented subproject 7
when the initial spike is counted from one.

## Measured candidate results

The final locked, offline Windows console suite passed 29 cases in 12.41 seconds;
Ruff passed. The independent review found three runner defects: Gradle daemon
lifetime, coroutine-cancelled process ownership and a failed output consumer
blocking a verbose child. Their three focused witnesses failed before correction
and passed afterward. A fourth case holds job-level CI condition drift. The
reviewer's bounded correction readback passed those four cases and accepted the
correction; it did not repeat the whole-branch review.

Existing CI shell/routing guards passed 48 cases (three document-fixture cases
excluded from that isolated invocation); gate/reference guards passed 88 cases.
The final source-reference and declared-gate checks passed again after the
instruction updates. These reports are separate from the unchanged full
server/Android reports. Actual external-tool execution, Linux behavior and remote
workflow execution remain distinct from these Windows results; record exact-head
CI and delivery on the PR. The local Gradle command deliberately adds
`--no-daemon`, so its actual build cannot escape into a reused daemon or leave a
persistent descendant holding the console queue.
