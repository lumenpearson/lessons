# Detekt budget implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax.

**Goal:** Make a baseline rewrite unable to forgive another finding silently.
**Architecture:** One existing pure JVM test reads default baseline/budget pairs;
the budget records numerical policy plus accepted IDs for deterministic diagnostics.
Declare both patterns as Test inputs and prove invalidation with real warm-cache probes.
**Tech Stack:** Existing Kotlin/JDK21/JUnit4.13.2/detekt2.0.0-alpha.6; JDK DOM, no dependency.
**Spec:** docs/specs/2026-11-10-detekt-budget-design.md

## Global Constraints

- No application, schema, dependency or plugin version changes.
- Existing baseline bytes/491entries remain unchanged in final source.
- Budget limits never increase to forgive a new finding; removals lower the limit.
- Default filename discovery; generated trees/links excluded, external XML disabled.
- No root cross-configuration; native maxworkers2/offline/JDK21/isolatedSDK, alone.
- Full server uses owned venv and Git/bin first in PATH; do not use WSL bash shim.
- Completed raw reports precede #660 facts regeneration; no facts from failed probes.
- Keep all artifacts and foreign work; no deletion/reset/force-push.

## Review Focus

1. A baseline-only or budget-only edit must invalidate Test: actual cache probes.
2. A finding moved into manual suppression must still consume budget: fixture growth.
3. A new nested pair must not be omitted by a fixed module list: discovery fixture.
4. Missing/malformed/duplicate/zero records must fail closed: paired negative controls.
5. A snapshot move must not hide growth or retain headroom: growth/removal/move tests.

### Task 1: Ratchet default baselines through the existing JVM gate

**Files:**
- Create: android/core/model/src/test/kotlin/com/lumenpearson/lessons/core/model/DetektBaselineBudgetTest.kt
- Create: android/{app,core/data,core/designsystem,core/model,widget}/detekt-baseline-budget.xml
- Modify: android/core/model/build.gradle.kts
- Modify: docs/engineering.md before the build edit; docs/build.md and
  .claude/skills/large-refactor/SKILL.md with the budget workflow; one CLAUDE trap.
- Modify: docs/counts.json/current declarations/HANDOVER.md/docs/history.md after raw gates.

**Interfaces:**
- Consumes: Android root File, detekt-baseline.xml and sibling budget XML.
- Produces: private budgetProblems(baseline: String, budget: String): List<String>,
  deterministic messages naming added/removed IDs and required lower/unchanged limit.
- Produces: private treeProblems(root: File): List<String>, discovers all default
  pairs and prefixes messages with relative module path; production callers: none.
- Budget schema: SmellBaseline maxEntries="N", one CurrentIssues and one
  ManuallySuppressedIssues, unique nonempty ID leaves; N equals accepted ID count.

- [x] Write tests first: growth from Old to Old+New with max1 prints New; removing
  Old requires max0; Old.kt->New.kt with max1 requires snapshot refresh and then
  passes; adding a manual ID consumes budget; empty max0 passes; malformed roots,
  duplicates and count mismatches fail; nested feature/tools pair discovered;
  missing counterpart and synthetic symlink/entity inputs fail without content read.
- [x] Run offline :core:model:test --tests '*DetektBaselineBudgetTest*'.
  Expected: RED from absent implementation/records; then watch assertion RED against
  an empty-result stub before implementing the comparison, not only compile errors.
- [x] Implement the two private interfaces and JDK DOM parsing; use a regular-file
  boundary and no external XML. Seed five budgets from the audited current XML,
  preserving all original baseline hashes. Explain the rule in engineering first.
- [x] Run the same focused test. Expected: all fixture/current-tree checks pass.
  Then add both patterns and own build script to module Test inputs. Capture a warm
  success, inject one known added baseline ID, rerun unchanged command: expected
  Test executes and fails growth. Restore exact bytes if no foreign edit occurred.
  Repeat with a budget-only invalid count; expected execution/failure, then restore.
- [x] Run native test/assembleDebug/assembleRelease/detekt alone with maintained
  resource caps. Expected: all four green, real full JVM XML/zero failures/errors,
  final five baseline hashes unchanged. Fix new test findings; never widen baselines.
- [x] Freeze implementation/build/records. Run Ruff, mypy, complete bare pytest
  -q -n8 with explicit owned XML and verified Git Bash, alone. Expected: exit0,
  complete unique JUnit cases. Then regenerate #660 facts from both real report sets.
- [x] Update current declarations manually; record #720's verified release in
  HANDOVER, current635/previous661, archive660 body once. Update current build/routing
  prose as part of this build's documentation. Run both post-checks and final
  docs/count/schema/reference/path/gate/CI-summary checks. Expected: all green.
- [x] Commit explicit owned files only after gates; task-done runs the focused JVM
  command with the same native environment. Expected: green completion ledger.

## Review and delivery

- [ ] Fresh independent committed-range review; re-grade and resolve Important in
  one RED/GREEN fix pass plus complete green gates. Defer Minors; no second reviewer.
- [ ] Push/draft PR Closes #635, Refs #626, existing milestone13; attach, labels,
  ready, exact-head CI/reviews, clean pinned merge, main CI, production SHA/three
  aliases/warmups/error-fatal query, owned checkout/ref synchronization and release proof.

## Self-review

One cohesive test/build-policy deliverable; no application refactor. All five focus
items have fixture or behavioral build checks. Numerical limits and accepted IDs
travel together; no hidden Git-history or hardcoded five-module dependency. Native
and server full runs are separate, measured, and precede publication. The budget
parser is test-only, and no command automatically raises a limit during regeneration.

## Review correction pass

The single fresh independent review ofc7f10c35 found5Important/3Minor. One pass
adds seven failing reader regressions (20cases/7RED), preserves group membership,
parses original XML bytes, rejects container text and prunes Windows junctions.
Module-local ValueSource metadata selects regular files before fingerprinting;
actual configured-input audits and identical-file-link warm-cache probes went
RED to GREEN. Baseline/budget content probes each have an independent warm success;
the default console prints the module and exact added ID. Full corrected native
four gates passed52s with1817cases/model145/268reports and zero failures/errors/skips.
Python application/tests remain unchanged; affected document checks and exact-head
full ServerCI complete the verification. No second reviewer. Required current facts
are regenerated from completed successful reports, and the unheld class metric is
omitted rather than copying another unverified count.
