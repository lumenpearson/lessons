# Instruction reference implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax.

**Goal:** Fail the existing server suite when current agent navigation names disappear.
**Architecture:** A stdlib helper inventories public source names and actual test
definitions; pytest compares instruction tokens against that inventory. Existing
CI routing admits every external filename/definition input it consumes.
**Tech Stack:** Python 3.12 stdlib, existing pytest, Git filename inventory.
**Spec:** docs/specs/2026-11-10-instruction-references-design.md

## Global Constraints

- English project text; no application/schema/dependency changes.
- Do not read private configurations or execute documented commands.
- Retain other sessions' work and all existing evidence; no deletion.
- Raw full suites precede #660 facts regeneration; source changes require new proof.
- Full checks, independent review, exact-head CI, pinned merge and production.

## Review Focus

1. Removed source must not match generated files: fixture generated-only path fails.
2. Comment/string fake test definitions must fail: Python AST/Kotlin lexical fixtures.
3. Local optional configuration must not require reading secrets: private-name controls.
4. Commands must check source arguments/selectors: quoted Gradle and pytest fixtures.
5. Android/.github moves must trigger Server: CI input mutation/real pattern checks.

### Task 1: Hold current navigation references

**Files:**
- Create: server/scripts/check_instruction_references.py
- Create: server/tests/test_instruction_references.py
- Modify: server/tests/test_ci_paths.py, .github/workflows/ci.yml
- Modify: docs/engineering.md, current instruction examples as required
- Modify: docs/counts.json, current count declarations, HANDOVER.md, docs/history.md

**Interfaces:**
- Consumes: repository root Path and current public instruction text.
- Produces: differences(root: Path) -> list[str], document:line:token errors.
- Produces: unresolved(text: str, paths: set[str], tests: set[str]) -> list[str],
  pure token/path comparison; inventory(root: Path) -> tuple[set[str], set[str]].
- No application callers; helper is consumed by one existing-suite test module.

- [x] Write parameterized failing fixtures for missing/renamed paths and test
  definitions, quoted source args and selectors, generated-only inventory,
  comment/string fake definitions, positive suffix/ellipsis/glob/directory,
  routes/URLs/private configs/placeholders, untracked source, actual documents.
  Assert errors contain the original missing reference; valid controls return [].
- [x] Run bare pytest -q tests/test_instruction_references.py with owned XML.
  Expected: missing helper/implementation fails; fixture controls later pass.
- [x] Implement the three exact interfaces above and necessary private helpers.
  Use Git inventory filtered to source/config/document names; AST for Python test
  functions/classes; comment/string-aware Kotlin class discovery in test trees.
  Parse inline spans outside fences, tokenize shell command spans without running.
- [x] Run the same focused tests. Expected: all pass. Rewrite misleading current
  historical/example tokens as prose; no allowlist of stale source names.
- [x] Explain test/CI input rationale in engineering, then extend Server's input
  patterns. Add test_instruction_inputs_run_server with Android and .github move
  samples; run path/gate/focused guards. Expected: pass with actual patterns.
- [x] Freeze implementation. Run Ruff, mypy, complete bare pytest -q -n 8 with
  owned XML and native test/assembleDebug/assembleRelease/detekt separately.
  Expected: successful raw suites, no failures/errors, actual JUnit case counts.
- [x] Generate canonical facts with #660 CLI from completed reports; update current
  declarations manually. Update HANDOVER/history last, archive prior batch once.
  Run both count comparisons and final schema/count/path/gate checks.
  Expected: all pass; historic results retained as dated evidence.
- [x] Commit only this batch after lint/tests green; run task-done's focused command.
  Expected: green ledger completion, no unrelated tracked changes.

## Review and delivery

- [ ] Generate committed-range package and obtain fresh independent review.
- [ ] Re-grade findings; one RED/GREEN important fix pass plus full green suite;
  defer minors, rule on every declined item. No second reviewer.
- [ ] Push/draft PR Closes #661, Refs #626, milestone13/labels; attach, ready,
  exact-head green CI/no requests/clean merge, pinned merge, main CI, production,
  synchronize owned refs and persist actual release proof.

## Self-review

One cohesive deliverable: source inventory, parser, live document assertion and
CI routing cannot usefully ship separately. All five review focuses map to Task1
fixtures. Interfaces are defined once; no new dependency, standalone job or product
surface is introduced. Delivery follows implementation completion separately.
