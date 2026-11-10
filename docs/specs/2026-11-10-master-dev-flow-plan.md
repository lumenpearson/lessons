# Master/dev integration implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax.

**Goal:** Preserve branch work and release through tested dev into master.
**Architecture:** Existing shared services and gates; default-branch-aware deploy
monitoring, explicit promotion and audited ref retirement.
**Tech Stack:** Existing Python3.12/FastAPI/SQLAlchemy and Android/JDK21/Gradle.
**Spec:** docs/specs/2026-11-10-master-dev-flow-design.md

## Global Constraints

- Owner authorizes branch deletion after preservation; no files/worktree directories,
  uncommitted changes, secrets, force-push or history rewrite may be discarded.
- Master/dev/one working branch on GitHub and locally; keep historical artifacts.
- Use existing isolated task-parent checkout and owned Python environment.
- Existing complete gates, serialized native/server execution, actual Git Bash.
- Task1 PR/issues use existing milestone13; Task2 dependency PR uses milestone7.
  Independent review precedes each release.
- No new schema or parallel domain-rule implementation; retain installed v1 APIs.

## Review Focus

1. A renamed default branch must still be monitored, with cache/304 scoped to repo.
2. Dev push and both PR targets must actually run lint/tests and required CI.
3. Dev/working refs must never receive production signing secrets.
4. Historical bot fixes must retain atomic writes/rollback/parent protections;
   old regression names alone do not prove semantic coverage.
5. Ref cleanup must retain unique commits, pending designs and exact local WIP.

### Task 1: Reconcile features and build the production-branch transition

**Files:**
- Existing bot handler/service/test paths identified by branch inventory.
- server/app/services/health.py, server/app/config.py, server/tests/test_health.py.
- .github/workflows/ci.yml, .github/workflows/apk.yml and their existing pytest guards.
- Android GithubApi/GithubPullRequests and the existing GithubProject URL test.
- docs/specs/pending/ recovered documents; current build/deploy/agent instructions.
- HANDOVER.md/docs/history.md and measured count declarations after full reports.

**Interfaces:** Keep service/HTTP/protobuf contracts unchanged. GitHub commit reads
use the repository's default branch rather than literal main. CI admits master/dev
pushes and PRs; signing admits master and v-tags only. Existing cache schema may
remain private if semantics stay repository-scoped and tests hold it.
Android guide raw URLs follow HEAD; new translation PRs start from and target
persistent dev. Existing APKs cannot have their embedded GitHub refs rewritten;
their server API remains compatible and their guide retains its bundled fallback.

- [ ] Extract all15 old bot regression functions into owned ignored evidence;
  map each to current code/tests and run any missing scenarios RED before edits.
- [ ] Reconcile only genuine gaps in the current shared rules; observe GREEN.
- [ ] Restore the three pending designs without treating unapproved designs as code.
- [ ] Add existing-family assertions for default-branch commit path, dev/master CI
  pushes and master-only signing; observe RED, then minimal implementation/GREEN.
- [ ] Run affected suites, Ruff/mypy; freeze source/test inputs. Complete server,
  native4gates, report writer/count comparisons and final document checks.
- [ ] Update in-batch HANDOVER including prior722 release; commit owned files,
  task-done and fresh whole-branch independent review; one major-fix pass if needed.
- [ ] Draft/ready PR into dev, milestone13, exact-head CI/review, pinned merge.
- [ ] Verify dev CI; coordinated GitHub rename/rules/Vercel branch cutover, pinned
  dev-to-master promotion, master CI and production SHA/aliases/warmups/log counts.

### Task 2: Integrate dependency heads and retire redundant refs

**Files:** Exact paths from the eight existing PRs, requirement mirrors/locks,
dependency configuration, current documentation and counts.

**Interfaces:** Consumes verified master/dev fromTask1. Original dependency commits
remain traceable. Resolving build incompatibilities cannot weaken tests/budgets.

- [ ] Read current PR heads/diffs; fold original commits with conflict resolution
  that preserves previous dependency updates. Install only declared versions.
- [ ] Run affected checks while integrating, then complete server/native gates,
  measured facts and final docs on the finished snapshot.
- [ ] Obtain coordinated UI grant for pinned APK; Android Studio Emulator smoke
  tests record actual app startup/navigation and tested limitations; release UI.
- [ ] Independent review, green exact-head CI, dev merge/CI and master promotion/
  CI/production verification; read back dependency PR dispositions.
- [ ] Preserve modified agent settings and all worktree files; detach old worktrees
  at their retained commits, then remove safe redundant refs. Verify live/local
  branch lists and working-branch deletion policy. No unique feature remains only
  on a deleted ref without preserved documented history.

## Execution ruling

The owner explicitly requested autonomous continuation and clarified that Claude
Code branches must be analysed as owned project work. Execute inline; retain
independent review. The tasks are separate release batches because dependency
compatibility can fail independently of the branching policy. No unverified
dependency bump is needed to publish the safe workflow transition.
