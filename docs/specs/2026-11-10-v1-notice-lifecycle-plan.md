# V1 notice lifecycle implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax.

**Goal:** A Telegram notification failure cannot change a committed v1 success to500.
**Architecture:** Keep the two existing factory seams, guard their lifecycle, and
freeze successful response DTOs before post-commit notification I/O.
**Tech Stack:** Existing Python3.12/FastAPI/SQLAlchemy/aiogram/pytest; no version changes.
**Spec:** docs/specs/2026-11-10-v1-notice-lifecycle-design.md

## Global Constraints

- Existing milestone13/issue377; no new milestone or unapproved API contract.
- One implementation of domain rules; no app.bot imports or eager aiogram import.
- No model/migration/dependency/protobuf/Android production changes.
- Ordinary notification errors are contained; cancellation remains cancellation.
- Tests use synthetic bots only; no real messages, credentials or live writes.
- No deletion/reset/rebase/force-push; preserve foreign trees and ignored evidence.
- Actual Git Bash before WSL shim; owned editable env, full pytest -n8.
- Heavy server/native gates run separately. Facts follow successful raw reports.

## Review Focus

1. Constructor exceptions must not escape after a real committed write: event and both decision regressions.
2. Close exceptions must not erase delivery or HTTP success: close-count/recipient controls for both seams.
3. A failed notification can expire ORM state: all four model-backed edit replies must remain readable.
4. Absent bot and successful/refused delivery must retain existing behaviour: parameterised controls.
5. Guards must not hide failed writes or cancellation: existing refusal tests and focused cancellation controls.

### Task 1: Contain post-commit notices and preserve their responses

**Files:**
- Modify: server/app/api/edit.py
- Modify: server/app/api/manage/requests.py
- Test: server/tests/test_api_extended.py
- Test: server/tests/test_api_manage.py
- Modify after reports: docs/counts.json, current server declarations in HANDOVER.md,
  README.md, CLAUDE.md, CONTRIBUTING.md, docs/architecture.md,
  .claude/skills/gates/SKILL.md, .claude/agents/server-tests.md; docs/history.md.
- Add one why/trap line in CLAUDE.md naming the regression, not a new CI shape.

**Interfaces:**
- Preserve edit._tell(session: AsyncSession, school_class: SchoolClass, text: str,
  *, kind: str, author: int) -> None.
- Preserve requests._tell(telegram_id: int, text: str) -> None and both _build_bot seams.
- Consume existing service results and response schemas unchanged; response DTOs
  are built after commit and before notification I/O, then returned unchanged.

- [ ] Add lifecycle cases to the existing event and approve/decline ASGI families:
  build/close fail -> successful response, one persisted write/audit; send failure,
  no bot and successful delivery are controls, with recipient/close counts.
- [ ] Add request-session expiry during notice for homework/override/event/day;
  require correct committed DTO and database state. Add cancellation controls.
- [ ] Run focused bare pytest with an owned explicit JUnit path. Expected: failure
  at unguarded build/close or ORM access, not collection/setup. Preserve RED output.
- [ ] Guard build and close inside the existing helpers, retain delivery catch,
  capture class ID before I/O and freeze response DTOs before _tell. Expected:
  all focused cases pass; normal notices/auth/refusals remain unchanged.
- [ ] Freeze implementation/test hashes. Run Ruff app/tests/scripts/migrations,
  python -m mypy, full bare pytest -q -n8 with owned XML and verified Git Bash.
  Expected: complete successful raw report, no failures/errors.
- [ ] After server exits, run native test/assembleDebug/assembleRelease/detekt
  offline with maintained resource caps. Expected: all4green;1817JVMcases unchanged.
- [ ] Run #660 writer from both completed reports; update current server numbers
  manually. Archive previous661 body once, record721 release, current377/previous635.
  Run both count post-checks and final document/schema/reference/path/gate suites.
- [ ] Commit explicit owned files after green gates; task-done runs the focused
  server cases with its own JUnit path, preserving the full report. Ledger completion.

## Review and delivery

- [ ] Fresh independent whole committed range review; re-grade findings, one
  RED/GREEN major-fix pass plus affected full gates; defer minors, no re-review.
- [ ] Draft PR Closes377, milestone13/labels/attach/ready, exact-head CI and review,
  clean pinned merge, main CI, production SHA/three aliases/warmups/error-fatal
  aggregates, owned checkout/ref fast-forwards and actual PR release-proof readback.

## Self-review

One post-commit failure boundary spans the two existing seams; neither can release
alone while377 remains half broken. All five review conditions have controls in
this task. Reuse existing integration fixtures and private factories, not a new
abstraction or test framework. Full reports precede measured facts and publication.
Owner explicitly chose autonomous inline execution and waived plan questions;
decisions/costs are ledgered, not fabricated as owner answers.
