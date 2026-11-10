# Test Counts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. The owner chose autonomous native execution; no coding delegation or renewed approval question is needed.

**Goal:** Make published current test totals agree with one file and with complete CI runs.

**Architecture:** A standard-library report/JSON CLI is shared by both CI jobs.
The existing server suite holds the known current declarations; regeneration
writes facts only, while each document keeps its own sentence. Architecture's
current module breakdown is held to the entire measured module map too.

**Tech Stack:** Python 3.12, argparse/json/ElementTree/pathlib, existing pytest,
existing Gradle JUnit reports and existing GitHub Actions setup versions.

**Spec:** [2026-11-10-test-counts-design.md](2026-11-10-test-counts-design.md).

## Global Constraints

- No new dependency, runtime business rule, schema, protobuf or Android production change.
- Keep bare `pytest -q -n auto`; its default XML output is configured, not a replacement invocation.
- Generate only counts JSON. Keep dated history; current declarations fail on missing anchors.
- Report actual full-run numbers, never collection-only or a guessed total.
- Keep #718's release/parent closure ahead of this batch's release. Preserve other worktrees and user files.
- Do not commit an intermediate step without the repository's full gates. One checked batch commit is sufficient.

## Review Focus

1. Missing/malformed/failed reports must refuse regeneration rather than publishing a partial total.
2. Variant duplicates count once, while duplicate identities inside one task/report fail.
3. New/removed/empty modules must not be hidden by a manually fixed list of five.
4. Missing current declarations and stale per-module architecture counts must fail;
   dated previous totals must remain legitimate.
5. CI input filters and #659's command/permission rule must cover both new comparisons.

## Task 1: Report tool and canonical facts

**Files:** Create `server/scripts/check_test_counts.py`, `server/tests/test_counts.py`,
`docs/counts.json`; modify `server/pyproject.toml`.

**Interfaces:** `CountsError(ValueError)`; `junit_cases(path: Path) -> set[tuple[str, str]]`;
`android_counts(root: Path) -> dict[str, int]`; `validate_counts(data: object) -> dict`;
`main(argv: list[str] | None = None, *, root: Path = ROOT) -> int`.
The Android map uses Gradle names. Total is computed from map values. CLI write
reads both complete reports before replacing JSON; check modes never write.

- [x] Write temporary XML/settings fixtures and failing cases named
  `test_junit_counts_cases_including_skips`, `test_bad_or_failed_reports_are_refused`,
  `test_duplicate_cases_in_one_task_are_refused`,
  `test_variant_copies_count_once`, `test_modules_are_discovered`, and
  `test_invalid_count_shapes_are_refused` (include booleans and inconsistent sums).
  Assertions pin exact small fixture totals and error source names.
- [x] Run bare `.venv312/Scripts/pytest.exe -q tests/test_counts.py` from server;
  retain the expected RED output for the absent/minimal implementation.
- [x] Implement the specified functions and CLI with the standard library.
  Ignore commented include declarations; fail empty/unsupported discovery.
  Use actual path discovery, not a copied module list.
- [x] Run the focused tests GREEN. Exercise CLI refusal/nonzero status with a
  temporary invalid server report. Seed JSON from the already measured baseline;
  it is updated again only from the new complete reports before the commit.
- [x] Add default pytest JUnit output, retaining all existing addopts/settings.
  Confirm an explicit per-run XML argument overrides the default.

## Task 2: Hold current document declarations

**Files:** Extend `server/tests/test_counts.py` and tool helpers; modify the seven
existing declaration documents only when the actual new full total is known.

**Interfaces:** `document_differences(root: Path, counts: dict) -> list[str]` names
the document, metric and mismatch. A registry contains exactly the current
seven server/four Android anchors described by the spec.

- [x] Write RED mutations `test_a_changed_current_count_names_its_document`,
  `test_a_missing_declaration_cannot_disable_the_rule`, and
  `test_historical_run_counts_are_not_current_declarations`.
  Each declared source is mutated; old run totals in the same document stay valid.
- [x] Implement the registry/parser and baseline repository assertion. Every
  required anchor matches once; zero or multiple matches report an error. Hold
  architecture's current module map as well, including zero-case additions,
  wrong/missing/duplicate/unlisted entries and preserved historical paragraphs.
- [x] Run focused tests GREEN against fixtures and the actual repository.
  Keep script/fixture access independent of sys.path and outside application code.

## Task 3: Wire existing CI and prepare the checked release

**Files:** Modify `.github/workflows/ci.yml`, `server/tests/test_ci_paths.py`,
`CLAUDE.md`, `AGENTS.md`, `.claude/settings.json`, `.claude/skills/gates/SKILL.md`,
`docs/engineering.md`, `README.md`, `CONTRIBUTING.md`, `docs/architecture.md`,
`.claude/agents/server-tests.md`, `HANDOVER.md`, `docs/history.md` as needed.

- [x] Add RED path-filter assertions for `docs/counts.json` triggering both jobs
  and the shared script triggering Android. Register the server's JSON input.
- [x] Add the two exact post-run commands from the design. Use the already pinned
  Python 3.12/setup-python action for Android; preserve existing test/assemble,
  detekt, Host, Buf and final CI aggregation behavior.
- [x] Update both primary instruction lists and specific allowed script prefixes.
  Run `test_gate_lists.py`, `test_ci_paths.py` and the focused counts tests GREEN.
- [x] Record #660's scope clarification about current declarations versus historical
  evidence. Update the engineering rationale before introducing the new CI shape.
- [x] Freeze implementation inputs. Run full server SQLite with owned XML/log paths,
  Ruff and mypy in this worktree's venv. Use a resource-checked worker cap.
- [x] Run native Android `test assembleDebug assembleRelease detekt`, JDK 21 and the
  isolated SDK already verified in this session; keep reports from this checkout.
- [x] Run explicit CLI write with those two complete report sets. Update current
  declaration numbers by hand, then run both CLI checks and focused document/gate
  guards against the new JSON. Mark historical PostgreSQL evidence as dated.
- [x] Update HANDOVER/history last, including actual results and the checked #718
  release/parent closure. Run relevant final docs/schema tests and `git diff --check`.
- [x] After full gates and final document checks, commit only this batch's files
  with an English sentence. Task completion here records implementation/gates;
  the review and delivery follow-through below remains required.

## Review and delivery

- [x] Generate the required committed-range review package;
  obtain fresh independent review and address important findings in one RED/GREEN
  fix pass with a green full suite before publication.
- [ ] Push; create draft PR with `Closes #660`, `Refs #626`, existing milestone 13;
  immediately attach it, set labels, mark ready, wait for exact-head CI/reviews.
- [ ] Merge only clean/no requests/green pinned head. Verify main CI, production
  SHA/all aliases/warmup/runtime counts, then fast-forward owned checkouts/refs.

## Self-review

The independent final review identified four Important defects in the first checked
snapshot. One fix pass must hold missing previously positive reports/intentional-zero
acknowledgement, unsupported include contexts, the raw-report/module-set update
sequence and JUnit hierarchy/skip totals. Regressions were observed RED21/2 controls
(11.04s), then the counts module passed93 (36.94s). Complete corrected gates and
current facts remain required before publishing the fix commit; no re-review is used.
The Minor generated-report ignore suggestion is deferred from this fix pass.
The corrected complete SQLite suite passed3723/skipped17/warnings15 in1983.38s,
3740JUnitcases/zero failures/errors. Current facts regenerated3740/1797; final
declaration/schema/count/gate checks passed134/twowarnings80.46s. Delivery gates
above remain pending until their actual results exist.

The three tasks cover each spec section. Review Focus items 1–3 belong to Task 1,
item 4 to Task 2 and item 5 to Task 3. Helpers, error type, JSON field names and
command paths are shared consistently. Module parsing/report selection remain
implementation choices only within the explicit discovery/identity rules above.
