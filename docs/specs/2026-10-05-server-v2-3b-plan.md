# Serving v2, stage 3b: every other method (sub-project 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** written on 5 October 2026, after stage 3a merged as #342 (`8d779ef`), on the branch `server-v2/3b` cut from that merge. The design was approved by the owner on 5 October 2026 with every recommended answer (#301), and the owner said yes to question 3 of the app's design (a phone's last app version, «все по рекомендациям») the same day. **This document holds 3b-1's full task list and a summary of each later pull request.** 3b-2 to 3b-8 each get their own full task list, in this same document, when their turn comes, written against the tree as it stands then.

**Goal:** Serve the other seventy-one unary methods of `lessons.v2` beside v1, in eight pull requests. The first, 3b-1, serves the class's records (`ListAuditEntries`, the three `ClassDeviceService` methods and the five `SubjectService` methods) and records each phone's last app version.

**Architecture:**
- 3a's machinery is used as it stands: `rpc/methods.py`, the gate, `invoke`, the error table, the transcoder and the `v2` test harness. A method is served by putting its handler into `rpc/handlers.HANDLERS`. The gate test, the no-echo sweep and the write rules then pick it up without being asked.
- Each stage moves the rules its v1 routers hold into `services/` first, with v1 calling the moved code and v1's answers unchanged (decision 2).
- 3b-1's moves are these:
  - the member names and the class's wall clock out of `api/manage/_common.py`;
  - the dictionary read and the rename-then-details patch out of `api/manage/subjects.py`;
  - a page of the journal keyed on its last line.
- 3b-1 also adds `device_tokens.client_version` (revision `0018`), written by the gate beside `last_seen_at`. It shows in v2's `ClassDevice` and in the bot's «📱 Устройства».

**Tech Stack:** Python 3.12 (CI's, and this worktree's venv), FastAPI/Starlette, dishka, SQLAlchemy 2 async, Alembic, `connectrpc` 0.12.1, `protobuf-py` 0.6.0, Buf 1.73.0, aiogram (the bot's renderer only), pytest with httpx's ASGI transport.

**Spec:** `docs/specs/2026-10-05-server-v2-design.md`. 3b is decision 1's second row, and its decisions are 1, 2, 4, 10, 12, 14 and 15. It sits under the programme `docs/specs/2026-10-03-one-contract-design.md`. Question 3 of `docs/specs/2026-10-05-android-transports-design.md` is the column. Read the design before Task 1. 3a's plan, `docs/specs/2026-10-05-server-v2-3a-plan.md`, describes the machinery this one uses, and its Rulings 1 to 24 still hold.

## How 3b is cut: eight pull requests

Each is one batch, with its own subagent-driven run, its own review, its own HANDOVER close-out and its own merge, in this order. The order is the controller's ruling of 5 October 2026.

| PR | Title | Methods |
| --- | --- | --- |
| **3b-1** | The class's records: journal, devices, subjects, and each phone's app version | `AuditService.ListAuditEntries`; `ClassDeviceService.ListClassDevices`, `RevokeClassDevice`, `UnlinkClassDevice`; `SubjectService.ListSubjects`, `GetSubject`, `CreateSubject`, `UpdateSubject`, `DeleteSubject` (9) |
| **3b-2** | Bells, the timetable and the class | `BellService` ×5, `TimetableService` ×2, `ClassService` ×8, the terms included (15) |
| **3b-3** | Notices as effects; access requests; the school directory | `AccessRequestService` ×3, `DirectoryService` ×2, `server/app/telegram_send.py` (5) |
| **3b-4** | The phone's own | `MeService.UnlinkMe`, `CreateLinkCode`, `GetCalendarFeed`, `CreateCalendarFeed`; the tasks ×5; the homework ticks ×2 (11) |
| **3b-5** | Homework and events | `HomeworkService` ×5, `EventService` ×5 (10) |
| **3b-6** | Days and substitutions | `DayService` ×2, `SubstitutionService` ×5 (7) |
| **3b-7** | The diary's registry, sessions and reads | `CreateDiarySession`, `DeleteDiarySession`, `ListStudents` and the seven reads, with the per-provider registry as a table (10) |
| **3b-8** | The diary's corrections | `ListCorrections`, `BatchUpdateCorrections`, `ResetCorrections`, `ClearCorrections` (4) |

9 + 15 + 5 + 11 + 10 + 7 + 10 + 4 = 71: every unary method 3a left unserved. `WatchClass`'s stream is 3c's.

## Rulings made while writing this plan

Each is a choice the design leaves to the code, or a place where the code showed that the controller's brief could not be followed literally. Each was checked against the tree at `8d779ef`, and the probes are listed under «What was verified».

1. **The later stages are summaries.** A full task list written now for 3b-2 to 3b-8 would be stale by the time each runs, because every stage moves code the next one reads. Each summary says:
   - the methods;
   - the v1 rules that move into `services/` first, and where they go;
   - the error-table rows and their reasons;
   - the effects;
   - the v1 behaviour v2 does not repeat;
   - the open questions.

   The full task list is written into this document, under the summary, before that stage's batch starts.
2. **`LATER` and `HELD_BY` name the stage that brings a reason, not «3b».** `test_rpc_errors.py` gains `STAGES`, the stages still to come, which starts as `{"3b-1", …, "3b-8"}`. Every value of `LATER` and every string row of `HELD_BY` must be one of them. Each pull request removes itself from `STAGES` in the task that produces its last reason. A reason it forgot to take out of `LATER` then fails the test, because it names a stage that is no longer to come. Task 4 brings this in, and Task 6 removes `"3b-1"`.
3. **The member-name and wall-clock helpers move to `services/`.**
   - `api/manage/_common.py`'s `_person` and `_member_names` become `services/manage/classes.display_name` and `member_names`.
   - Its `_wall` becomes `services/clock.wall`.
   - These sit at lines 76–108 at `8d779ef`, not at 188–220 as the brief says: the survey counted another file's lines.
   - v1's three routers (`devices`, `journal`, `requests`) call the moved code.
   - v2 calls `member_names`, but never `wall`. The contract's instants are `Timestamp`s («What the values look like» in `docs/api.md`), and a client converts them with the class's zone. `wall` moves anyway, as the controller ruled, so that 3b-3's access requests find nothing of this left in a router.
4. **`ListAuditEntries` pages by a token that names the last line served.**
   - **The token** is the URL-safe base64, unpadded, of `audit:<id>`, where the id is the last line of the page. It is opaque to a client.
   - **The next page** is the lines after that one, in `audit.recent`'s order (`created_at` descending, then `id`). So a line written between two page turns moves nothing; an offset would show the last line of one page again on the next.
   - **The anchor's `created_at` is read in SQL**, as a scalar subquery over an alias, and never bound from Python. On SQLite a server-default stamp is stored without microseconds and a bound one with them, so the same instant compares unequal (probed).
   - **Page size.** `page_size` 0 reads as 30. Above 100 it reads as 100, which is AIP-158's coercion and the proto's «100 at most». A negative one is `VALIDATION_FAILED` on `page_size`.
   - **A bad token** is `VALIDATION_FAILED` on `page_token`, with a fixed sentence that never repeats the token. That covers a token that does not decode, one with another prefix, one past 64 characters, and one that names no line of this class's log, another class's lines included.
   - The proto comment says all of this, so `audit.proto` changes in a comment only and is regenerated.
5. **One reading of `update_mask` for every `Update*` of 3b** (`rpc/masks.update_paths`, AIP-134).
   - **An explicit mask** is taken as it is. Every path must be one the method changes, or the request is `VALIDATION_FAILED` on `update_mask`, with a sentence that names no path. `*` is not taken.
   - **A masked path whose field is unset clears the field**, which is the meaning `me.proto` and `school_class.proto` state for their masks.
   - **No mask, or an empty one,** means every changeable field the resource sets, which is v1's `PATCH`: only the fields present change.
   - The paths are applied in the method's own order, once each.
   - For `UpdateSubject` the order is `name`, `short_name`, `teacher`, `color`, so the rename, the one change that can be refused, goes first, as v1's loop did.
6. **`rpc/errors.validate` gains `at=`**, a prefix for the request path. `CreateSubject` and `UpdateSubject` validate the nested `subject` with v1's own `SubjectIn` and `SubjectPatch`, and a violation then names `subject.name`, the field as the request spells it.
7. **v1's `GET /manage/devices` keeps its shape: no `client_version`.**
   - The design delivers no change to v1's behaviour beyond the moves («Does not deliver»).
   - The clients that would read the field are sub-project 5's, which speak v2.
   - A phone that speaks only v1 never sends the header, so v1 would show `null` on every row it serves.
   - The bot shows the version to admins today.
   - `test_contract_mirror.py`'s `ClassDevice` row records the addition with this reason.
8. **The column is `device_tokens.client_version INTEGER NULL`.**
   - It is declared right after `last_seen_at`, so that SQLAlchemy writes `UPDATE device_tokens SET last_seen_at=?, client_version=? WHERE device_tokens.id = ?`: one statement, in table order (probed).
   - `deps.touch_last_seen(session, device, *, client_version=None)` writes it only inside its own fifteen-minute clock and only when it is not `None`.
   - The gate passes the version it read. v1's `current_class` passes none.
   - So the write rule grows by exactly that column, and a v1 request never writes it.
9. **The bot shows «сборка N»**, appended to each row of «📱 Устройства». A versionCode is a build number, not the «0.10.0» a person reads in the app, and «сборка» says so. A phone that never sent one shows nothing about a version. The value is an integer from a column, so it needs no escaping. The row's budget is `DEVICES_MAX` rows under `render.clamp`, and the test fills it.
10. **Revision `0018` is additive and has a per-column guard, as `0015`'s does.** The controller applies it to production through the Neon connector **before** the merge. That is not a task. The DDL and the transaction are under Task 7, «Not a task».
11. **The head moves in Task 2, in every document that names it.**
    - `test_schema_version.py`'s `test_every_document_that_names_the_head_names_this_one` reads every `*.md` under `docs/` except `history.md`, `docs/specs/` included.
    - `docs/specs/2026-10-05-server-v2-3a-plan.md` quotes `/api/v1/warmup`'s answer with its schema as an expected output, so the moment `EXPECTED_REVISION` moves, that record fails the test. That is a defect, filed in Task 2 before its fix: the record is reworded so that it states the same expectation without the quoted answer.
    - This plan names the head only in forms the test does not read, and Task 2 scans it.
12. **`RevokeClassDevice` answers the revoked phone again on a repeat.** It is idempotent and writes one audit line, so `v2.both` can call it twice. The role is read before the revoke, as v1's endpoint reads it.
13. **`ListSubjects` and v1's `GET /subjects` both read `subjects.dictionary_of`.** v1's `/manage/subjects` keeps adopting, through `listing`, which now calls `dictionary_of` after the adoption.
14. **3b-1's error rows**, with messages that are v1's, moved into `app/wording.py`:
    - `subjects.SubjectExists` is `RESOURCE_EXISTS`, with `{resource: "subject", field: "name"}`.
    - `subjects.HomeworkClash` is `SUBJECT_RENAME_CLASH`, with `{dates: "YYYY-MM-DD,…"}`, comma-separated without spaces as the proto says.
    - `subjects.SubjectInUse` is `RESOURCE_IN_USE`, with `{resource: "subject", used_by: "lessons", count}`.
    - `devices.DeviceNotLinked` is `CLASS_DEVICE_NOT_LINKED`.
    - An unknown id is a `Refusal` of `RESOURCE_NOT_FOUND`, with `resource` `"subject"` or `"device"`.
15. **`CreateSubject` flushes to learn the new row's id.** A flush is not a commit, and `invoke` commits.
16. **Handler modules are named for their proto file**, as 3a's `device.py` and `schedule.py` are: `rpc/audit.py`, `rpc/class_device.py`, `rpc/subject.py`. The mask helper is `rpc/masks.py`.
17. **`v2.both` calls REST and then Connect, so a write that changes state cannot be compared through it.** The second call would see the first one's change. A write's success is therefore asked once per transport, on fresh data, and its refusals and its idempotent repeats through `both`.

## Global Constraints

- **Paths.** Handlers live in `server/app/rpc/`, one module per proto file, registered in `rpc/handlers.HANDLERS`. Generated code stays under `server/app/contract/`, and only `buf generate` writes it.
- **Import rules.**
  - `rpc/` and `rest/` may import:
    - `services/`, `models`, `schemas`, `schedule` and `wording`;
    - `contract/`, `security`, `config`, `crypto`, `di` and `api/deps.py`;
    - the diary registry and the NetSchool region list.
  - `rpc/` and `rest/` may never import `app.bot`, `app.api.public`, `app.api.edit`, `app.api.manage` or `app.api.diary`.
  - No `app.services` module reaches `app.rpc`, `app.rest` or `app.api`.
  - `tests/test_service_layering.py` holds all of this.
- **Codes to HTTP**, as in `docs/api.md`'s table:

  | Code | HTTP |
  | --- | --- |
  | `INVALID_ARGUMENT`, `FAILED_PRECONDITION` | 400 |
  | `UNAUTHENTICATED` | 401 |
  | `PERMISSION_DENIED` | 403 |
  | `NOT_FOUND` | 404 |
  | `ALREADY_EXISTS` | 409 |
  | `RESOURCE_EXHAUSTED` | 429 |
  | `INTERNAL` | 500 |
  | `UNIMPLEMENTED` | 501 |
  | `UNAVAILABLE` | 503 |

  A success over REST is `200`, except the eight methods of `rest.CREATED`, which answer `201`. In 3b-1 that is `CreateSubject`.
- **The commit.** A handler never commits. It may flush to learn an id. Only `rpc/call.py` commits.
- **Refusals.**
  - No exception's own text reaches a client.
  - No refusal repeats what was sent, in its message, its metadata or its violations. The no-echo sweep sends a password-shaped string in every field of every served method.
  - A sentence v1 and v2 both answer with lives once, in `app/wording.py`.
- **A rule a v1 router holds moves into `services/` before its v2 handler is written.** v1 calls the moved code, and v1's answers stay byte for byte. v1's own tests (`test_api_manage.py` and the rest) are the proof, and they are not edited to fit.
- **Reads write nothing.** Every `Get` and `List` writes only what `test_v2_reads.ALLOWED_WRITES` allows: `last_seen_at` with the client version beside it, a rotated diary credential and its `last_used_at`, and the throttles' and the counter's own rows.
- **Language.** English in code, comments, commit messages and documents. Russian only as product text, and in «guillemets» anywhere else. Comments say why.
- **Commit messages.**
  - They are English sentences saying what the change makes the project do, with no prefix.
  - The body gives the reasoning and what is left uncovered.
  - Every message ends with exactly these two lines:
    `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
    `Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV`
  - Write the message with the Write tool to `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3b1-t<N>.txt`, then `git commit -F` that file.
  - The shell refuses compound commands that `cd` to a computed path, or that pipe a command's output into `git` or `pytest`. Every command below spells its paths out.
- **This machine's RAM is faulty.**
  - Run one heavy job at a time. Never run the suite while Gradle or another suite runs.
  - Run the full suite once per task, at its gate.
  - After a crash, scan for zero-filled files before trusting the tree.
- **Commands.** Shell state does not persist, so each command spells these out:
  - `WT` is `/c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract`.
  - `pytest` is `$WT/server/.venv/Scripts/pytest.exe`, run from `$WT/server`, in the bare form CI runs. A focused run adds `-p no:xdist`. The full suite is `pytest -q -n auto`.
  - `ruff` and `mypy` are `$WT/server/.venv/Scripts/python.exe -m ruff check app tests scripts migrations` and `… -m mypy`, from `$WT/server`.
  - `buf` is `/c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe` (1.73.0), run from `$WT`.
- **Gates at the end of every task**, from `$WT/server`, in this order:
  1. the task's own test files, with `-p no:xdist`;
  2. ruff, which prints `All checks passed!`;
  3. mypy, which prints `Success: no issues found in N source files`. N is given per task, over a base of 214 at `8d779ef`. If mypy on the branch's first commit prints another number, shift every N by the difference;
  4. `pytest -q -n auto` once, with no failure.

  The suite is 2437 at `8d779ef`, and each task gives the number it adds.
- **Milestone 11**, `v0.10.0 — One contract: REST v2, Connect and native gRPC, build console`, under epic #273. Another agent may work in this tree: run `git status` before touching a file you did not open.
- **A defect found during the work gets an issue before its fix.** The issue has `type:bug`, its `area:`, a `status:`, milestone 11, and an item on project 6, as the `github-pr` skill says. The pull request then says `Closes #NN`.
- **Plans under `docs/specs/` are read by the head test.**
  - No text in this plan may contain «head is», optionally followed by asterisks, and then a backticked four-digit revision.
  - Nor may it contain «expects» and then a backticked revision.
  - Nor may one line hold `/warmup`'s quoted `"status"`/`"ok"` and its `"schema"`. A line break or a `}` between the two is enough to keep them apart.
  - Task 2 Step 7 scans for all three.

## Review Focus (3b-1)

The five failure modes most likely to bite a person using 3b-1, each with the test that pins it and the task that owns it.

1. **The journal grows between two page turns.** An admin reads a page, an editor saves homework, and the admin turns the page. No line may repeat and none may be skipped. This is the case offsets get wrong.
   - `test_a_line_written_between_two_pages_repeats_none_and_skips_none`, both paths (Task 3);
   - its service-level twin of the same name (Task 1);
   - `test_lines_written_in_one_second_page_by_id` (Task 1), because on SQLite the stamps of one transaction tie, and on Postgres the stamps of one transaction are equal.
2. **An update with no mask, or with a mask that names a field the body leaves out.** An admin who sends only a teacher must not wipe the colour; a masked field left out is cleared; an unknown path is refused and not repeated back.
   - `test_an_update_without_a_mask_changes_only_what_it_sends`, `test_a_masked_field_left_out_is_cleared` and `test_a_mask_naming_a_field_the_method_does_not_change_is_refused_on_it` (Task 6);
   - `test_a_path_the_method_does_not_change_is_refused_without_repeating_it` (Task 6, `test_rpc_masks.py`).
3. **A rename onto a name that differs only in case** («ГЕОМЕТРИЯ» onto «Геометрия»). It is `RESOURCE_EXISTS`, and the timetable is not half-moved: `test_a_rename_onto_a_name_taken_in_another_case_moves_nothing` (Task 6).
4. **A revoke retried over a flaky network, or of the phone in the admin's own hand.** The repeat gets the same answer, one audit line is written, and the token is dead on the next call: `test_revoking_twice_is_one_switch_and_one_line` and `test_an_admin_may_revoke_the_phone_in_their_hand` (Task 4).
5. **A phone whose app version changes in odd ways.** It updates its APK inside fifteen minutes, sends no header, sends garbage, is told to update, or speaks v1. The version column is written only beside `last_seen_at`, it is never nulled, and v1 never writes it: the six tests of `test_v2_client_version.py` (Task 2), and `test_the_last_seen_rule_takes_the_version_beside_it_and_never_alone` (Task 2).

## What was verified while writing this plan, and what was not

**Probed on 5 October 2026**, with the worktree's own venv and scratch scripts in `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\` (`probe3b1*.py`). Nothing in the worktree was changed.

- **protobuf-py's `has_field`** is `False` for an implicit-presence string left empty and `True` once it is set. For a proto3 `optional` string it is `True` once the field is set, even to `""`. An unset optional string reads as `""`, not `None`, so a handler must ask `has_field` before it reads one.
- **A message field left unset reads as `None`.** `CreateSubjectRequest().subject is None`, and `UpdateSubjectRequest().update_mask is None`.
- **`FieldMask` in JSON** takes `"shortName,teacher"` as the paths `["short_name", "teacher"]`, and turns any string into paths, so `"Pa55w0rd-s3cr3t-Hunter2"` became `["_pa55w0rd-s3cr3t-_hunter2"]`. That is why the mask's refusal names no path.
- **`Timestamp.to_datetime()`** returns an aware UTC datetime.
- **The UPDATE statement's column order.** With a column declared after `last_seen_at`, SQLAlchemy 2 writes `UPDATE device_tokens SET last_seen_at=?, client_version=? WHERE device_tokens.id = ?`. It does so in table order, whichever attribute was set first.
- **The keyset page**, an aliased scalar subquery for the anchor's `created_at`:
  - it pages correctly on SQLite through lines written in one second, which tie and are ordered by id;
  - it does so through lines a minute apart with a new line written between two pages;
  - an anchor that names nothing gives an empty page.
- **The same row compared through a bound datetime parameter** counted 0 on SQLite. The stored `'2026-10-05 16:52:36'` and the bound `'… .000000'` differ as strings, which is why the anchor is read in SQL.
- **`buf.exe --version`** prints `1.73.0`.

**Run, in a scratch copy of the worktree** (`C:\Users\lumen\.claude\jobs\c9e2d980\tmp\plan3b1\`, never the worktree).
- **The copy.** It has its own venv, made with `uv` from `requirements.txt` and `-e ".[dev]"` on Python 3.12, which the #313 guard requires.
- **How the code got in.** Every code step of Tasks 1 to 6 was applied by a script, `plan3b1_apply.py`, which reads this plan's own fenced blocks rather than a retyped copy. Task 7's document edits, the head script and the counts script were applied the same way. So the anchors each step quotes were proved to exist exactly once.
- **What each task's run gave**, task by task:
  - `ruff check app tests scripts migrations` was clean, and `mypy` printed 214, 214, 215, 216, 217 and 218 source files;
  - the focused runs each task's green step names all passed: 421, 161, 109, 194 and 5, 56, and 305 tests;
  - `buf lint` was clean, and `buf generate` changed only `audit_connect.py` (Task 3) and `class_device_pb.py` (Task 4);
  - `pytest --collect-only` gave 2520 in the copy against 2437 in the worktree, which is the 83 this plan counts;
  - after Task 7's edits, `test_rpc_errors.py`, `test_schema_version.py`, `test_ci_paths.py` and `test_contract.py` passed, and the head scan named only `0018`.
- **The scratch run found two defects in this plan's first draft, and both are fixed above.**
  - Task 1's `clock.py` import order was not ruff's.
  - The gate test failed for `UpdateSubject` in the harness itself, because `v2.rest` could not build `{subject.id}` for a request with no `subject`. Task 6 now carries the harness change.

**Not run:**
- the full suite, in the copy or the worktree;
- `buf breaking`, because the copy is no git repository;
- anything on Postgres or Vercel;
- a real phone.

The counts each task gives are counted from the tests it writes and confirmed by the collection above. The run at each task's gate is still the truth.

## File map (3b-1)

| File | Task | What it holds |
| --- | --- | --- |
| `server/app/services/clock.py` | 1 | `wall`, moved from `api/manage/_common._wall` |
| `server/app/services/manage/classes.py` | 1 | `display_name`, `member_names`, moved from `_common` |
| `server/app/services/manage/subjects.py` | 1 | `dictionary_of` (no adoption), `update` (the patch v1's router held) |
| `server/app/services/audit.py` | 1 | `NEWEST_FIRST`, `older_than` (the keyset page) |
| `server/app/services/manage/journal.py` | 1 | `has_line`, `page_after` |
| `server/app/wording.py` | 1 | the subject and device sentences v1 and v2 share |
| `server/app/api/manage/{_common,devices,journal,requests,subjects}.py`, `server/app/api/public.py` | 1 | v1 calling the moved code |
| `server/app/models.py`, `server/migrations/versions/0018_device_client_version.py`, `server/app/db.py` | 2 | the column, its revision, `EXPECTED_REVISION` |
| `server/app/api/deps.py`, `server/app/rpc/gate.py` | 2 | the version written beside `last_seen_at` |
| `CLAUDE.md`, `AGENTS.md`, `docs/api.md`, `docs/deploy.md`, `.claude/agents/server-migrations.md`, `.claude/skills/migration/SKILL.md`, `docs/specs/2026-10-05-server-v2-3a-plan.md` | 2 | the head, moved |
| `proto/lessons/v2/audit.proto`, `server/app/rpc/audit.py` | 3 | `ListAuditEntries` and its token |
| `proto/lessons/v2/class_device.proto`, `server/app/rpc/class_device.py`, `server/app/rpc/values.py` | 4 | `ClassDevice.client_version`, the three methods, `maybe_instant` |
| `server/app/bot/manage_render/devices.py`, `docs/bot.md` | 4 | «сборка N» |
| `server/app/rpc/errors.py` | 4, 6 | the table's rows; `validate(…, at=)` |
| `server/app/rpc/subject.py` | 5, 6 | `SubjectService` |
| `server/app/rpc/masks.py` | 6 | `update_paths` |
| `server/app/rpc/handlers.py` | 3–6 | `HANDLERS` |
| `server/app/contract/**` | 3, 4 | regenerated by `buf generate` |
| `server/tests/test_services_manage.py` | 1 | the moved rules |
| `server/tests/test_v2_client_version.py`, `server/tests/test_client_version_revision.py` | 2 | the column |
| `server/tests/test_v2_audit.py` | 3 | `ListAuditEntries` |
| `server/tests/test_v2_class_devices.py` | 4 | `ClassDeviceService` |
| `server/tests/test_v2_subjects.py` | 5 | the subject reads |
| `server/tests/test_v2_subject_writes.py`, `server/tests/test_rpc_masks.py` | 6 | the subject writes, the mask |
| `server/tests/test_v2_reads.py`, `server/tests/test_rpc_errors.py`, `server/tests/test_contract_mirror.py`, `server/tests/test_bot_manage.py` | 2, 4, 6 | the shared rules grown |
| documents | 7 | `docs/api.md`, `docs/README.md`, `docs/architecture.md`, `CLAUDE.md`, `README.md`, `CONTRIBUTING.md`, the `gates` skill, the `server-tests` agent, `HANDOVER.md`, `docs/history.md` |

---

# 3b-1: The class's records: journal, devices, subjects, and each phone's app version

Nine methods, the column, and the rules they need, in seven tasks:
- Task 1 moves the rules.
- Task 2 adds the column, which Task 4 shows.
- Tasks 3 to 6 serve the methods.
- Task 7 is the documents and the close-out.

Expected growth of the suite: 10 + 11 + 9 + 16 + 10 + 27 = **83** tests, so 2437 becomes **2520** if nothing else moved. mypy grows from 214 to **218** modules: `rpc/audit.py`, `rpc/class_device.py`, `rpc/subject.py` and `rpc/masks.py`.

## Task 1: The member names, the class's clock, the dictionary read, the subject patch and the journal's page, in `services/`

Decision 2. v1 keeps its answers; `test_api_manage.py` is the proof and is not edited.

**Files:**
- Modify: `server/app/services/clock.py`, `server/app/services/manage/classes.py`, `server/app/services/manage/subjects.py`, `server/app/services/audit.py`, `server/app/services/manage/journal.py`, `server/app/wording.py`
- Modify: `server/app/api/manage/_common.py`, `server/app/api/manage/devices.py`, `server/app/api/manage/journal.py`, `server/app/api/manage/requests.py`, `server/app/api/manage/subjects.py`, `server/app/api/public.py`
- Create: `server/tests/test_services_manage.py`

**Interfaces:**
- Consumes:
  - `SchoolClass.tz`;
  - `services/manage/classes.members(session, class_id) -> list[BotUser]`;
  - `subjects_service.rename`, `set_detail` and `DETAILS`;
  - `dictionary.sync_from_timetable`;
  - `audit.recent`.
- Produces:
  - `clock.wall(stamp: datetime | None, school_class: SchoolClass) -> datetime | None`;
  - `classes.display_name(full_name: str | None, username: str | None, telegram_id: int | None) -> str`;
  - `classes.member_names(session, class_id: int) -> dict[int, str]`;
  - `subjects_service.dictionary_of(session, class_id: int) -> list[Subject]`;
  - `subjects_service.update(session, class_id: int, actor_id: int | None, subject: Subject, changes: Mapping[str, str | None]) -> int`;
  - `audit.NEWEST_FIRST`;
  - `audit.older_than(session, class_id: int, after_id: int | None, *, limit: int) -> list[AuditEntry]`;
  - `journal.has_line(session, class_id: int, entry_id: int) -> bool`;
  - `journal.page_after(session, class_id: int, *, limit: int, after_id: int | None) -> tuple[list[AuditEntry], bool]`;
  - `wording.UNKNOWN_SUBJECT_DETAIL`, `SUBJECT_EXISTS_DETAIL`, `subject_rename_clash_detail(days: Iterable[Date]) -> str`, `subject_in_use_detail(lessons: int) -> str`, `UNKNOWN_DEVICE_DETAIL` and `CLASS_DEVICE_NOT_LINKED_DETAIL`.

- [ ] **Step 1: Red.** Create `server/tests/test_services_manage.py`:
```python
"""The rules v1's manage routers held and 3b-1's v2 handlers need, in ``services/``.

The member names and the class's wall clock (``api/manage/_common.py``), the
dictionary read without adoption and the rename-then-details patch
(``api/manage/subjects.py``), and the journal's page after a line: each moved
before its v2 handler is written, with v1 calling the moved code
(``docs/specs/2026-10-05-server-v2-design.md``, decision 2).
``test_api_manage.py`` is the proof v1's answers did not move; these hold the
shapes v2 adds.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta

import pytest
from sqlalchemy import select

from app import wording
from app.models import AuditEntry, BotUser, Role, SchoolClass, Subject
from app.services import clock
from app.services.manage import classes, journal
from app.services.manage import subjects as subjects_service

START = datetime(2026, 9, 1, 8, 0)


async def _lines(session, school_class, count: int) -> None:
    """``count`` log lines a minute apart, oldest first: «строка 0» onwards."""
    session.add_all(
        AuditEntry(
            class_id=school_class.id,
            action="test.line",
            summary=f"строка {n}",
            created_at=START + timedelta(minutes=n),
        )
        for n in range(count)
    )
    await session.commit()


def _summaries(rows: list[AuditEntry]) -> list[str]:
    return [row.summary for row in rows]


async def test_wall_puts_a_stored_utc_stamp_on_the_class_clock(session, school_class) -> None:
    school_class.timezone = "Asia/Vladivostok"
    await session.commit()
    # Vladivostok is ten hours ahead of UTC all year.
    assert clock.wall(datetime(2026, 9, 12, 5, 5, 33), school_class) == datetime(
        2026, 9, 12, 15, 5, 33
    )
    assert clock.wall(None, school_class) is None


async def test_a_member_is_named_by_username_then_full_name_then_id(
    session, school_class
) -> None:
    session.add_all(
        [
            BotUser(
                telegram_id=3001,
                class_id=school_class.id,
                role=Role.EDITOR,
                username="anna",
                full_name="Анна",
            ),
            BotUser(
                telegram_id=3002,
                class_id=school_class.id,
                role=Role.VIEWER,
                full_name="Пётр <7>",
            ),
            BotUser(telegram_id=3003, class_id=school_class.id, role=Role.VIEWER),
        ]
    )
    await session.commit()
    # Plain text, not HTML: these names go into JSON and protobuf, and the
    # bot escapes its own copy when it renders one.
    assert await classes.member_names(session, school_class.id) == {
        3001: "@anna",
        3002: "Пётр <7>",
        3003: "3003",
    }
    assert classes.display_name(None, None, None) == "—"


async def test_the_dictionary_read_adopts_nothing_and_the_listing_still_does(
    session, school_class, statement_writes
) -> None:
    # The fixture's Monday teaches three subjects the dictionary has never seen.
    with statement_writes() as seen:
        assert await subjects_service.dictionary_of(session, school_class.id) == []
    assert seen == []
    adopted = await subjects_service.listing(session, school_class.id)
    assert [row.name for row in adopted] == ["Алгебра", "История", "Физика"]


async def test_an_update_renames_first_then_sets_each_detail_with_a_line_each(
    session, school_class
) -> None:
    subject = Subject(class_id=school_class.id, name="Алгебра")
    session.add(subject)
    await session.commit()
    moved = await subjects_service.update(
        session,
        school_class.id,
        2003,
        subject,
        {"teacher": "Иванова А. П.", "name": "Алгебра и начала анализа"},
    )
    # One timetable row of the fixture's Monday spells the old name.
    assert moved == 1
    assert (subject.name, subject.teacher) == ("Алгебра и начала анализа", "Иванова А. П.")
    await session.commit()
    actions = list(await session.scalars(select(AuditEntry.action).order_by(AuditEntry.id)))
    assert actions == ["subject.rename", "subject.teacher"]


async def test_an_update_whose_rename_is_refused_writes_none_of_it(session, school_class) -> None:
    algebra = Subject(class_id=school_class.id, name="Алгебра")
    session.add_all([algebra, Subject(class_id=school_class.id, name="Геометрия")])
    await session.commit()
    with pytest.raises(subjects_service.SubjectExists):
        await subjects_service.update(
            session, school_class.id, 2003, algebra, {"name": "ГЕОМЕТРИЯ", "teacher": "Петров"}
        )
    assert algebra.teacher is None
    assert [row for row in session.new if isinstance(row, AuditEntry)] == []


def test_the_shared_sentences_are_v1_s() -> None:
    days = [date(2026, 9, 7), date(2026, 9, 14)]
    assert wording.subject_rename_clash_detail(days) == (
        "homework under both names on the same day: 2026-09-07, 2026-09-14"
    )
    assert wording.subject_in_use_detail(12) == "12 lesson(s) still use this subject"


async def test_a_page_after_a_line_is_the_lines_older_than_it(session, school_class) -> None:
    await _lines(session, school_class, 5)
    first, more = await journal.page_after(session, school_class.id, limit=2, after_id=None)
    assert _summaries(first) == ["строка 4", "строка 3"] and more
    second, more = await journal.page_after(
        session, school_class.id, limit=2, after_id=first[-1].id
    )
    assert _summaries(second) == ["строка 2", "строка 1"] and more
    last, more = await journal.page_after(
        session, school_class.id, limit=2, after_id=second[-1].id
    )
    assert _summaries(last) == ["строка 0"] and not more


async def test_lines_written_in_one_second_page_by_id(session, school_class) -> None:
    """A tie is ordinary: SQLite's stamps have one-second resolution, and
    Postgres gives every line of one transaction its ``now()``. ``id`` breaks
    it, as ``audit.recent`` does, and these lines take the server's own
    default stamp, the one a bound parameter would not equal."""
    for n in range(3):
        session.add(
            AuditEntry(class_id=school_class.id, action="test.line", summary=f"строка {n}")
        )
    await session.commit()
    first, _ = await journal.page_after(session, school_class.id, limit=2, after_id=None)
    second, more = await journal.page_after(
        session, school_class.id, limit=2, after_id=first[-1].id
    )
    assert _summaries(first + second) == ["строка 2", "строка 1", "строка 0"] and not more


async def test_a_line_written_between_two_pages_repeats_none_and_skips_none(
    session, school_class
) -> None:
    await _lines(session, school_class, 4)
    first, _ = await journal.page_after(session, school_class.id, limit=2, after_id=None)
    session.add(
        AuditEntry(
            class_id=school_class.id,
            action="test.line",
            summary="новая",
            created_at=START + timedelta(hours=1),
        )
    )
    await session.commit()
    second, more = await journal.page_after(
        session, school_class.id, limit=2, after_id=first[-1].id
    )
    assert _summaries(second) == ["строка 1", "строка 0"] and not more
    # v1's offset, for contrast: the new line pushes «строка 2» onto page two again.
    by_offset, _ = await journal.page(session, school_class.id, limit=2, offset=2)
    assert _summaries(by_offset) == ["строка 2", "строка 1"]


async def test_a_line_of_another_class_or_of_none_is_not_this_log_s(
    session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    theirs = AuditEntry(class_id=other.id, action="test.line", summary="чужая")
    session.add(theirs)
    await session.commit()
    assert await journal.has_line(session, school_class.id, theirs.id) is False
    assert await journal.has_line(session, other.id, theirs.id) is True
    assert await journal.has_line(session, school_class.id, 999_999) is False
```
Run, from `$WT/server`:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_services_manage.py
```
Expected: 10 tests, every one failing with `AttributeError` on a name this task adds (`clock.wall`, `classes.member_names`, `subjects_service.dictionary_of`, `subjects_service.update`, `wording.subject_rename_clash_detail`, `journal.page_after`, `journal.has_line`).

- [ ] **Step 2: `services/clock.py` gains `wall`.** Replace the two lines `from datetime import date as Date` and `from datetime import datetime` with these two, in this order, which is ruff's: `from datetime import UTC, datetime`, then `from datetime import date as Date`. Then append:
```python


def wall(stamp: datetime | None, school_class: SchoolClass) -> datetime | None:
    """A stored UTC stamp on the class's own wall clock.

    ``created_at`` and friends are naive UTC in the database. An admin in
    Vladivostok reading a Moscow server's log should not see yesterday evening
    against this morning's change, so v1's management answers convert them
    here, as the bot's «📜 Журнал» does. v2 sends instants instead
    (``rpc.values.instant``), and a client converts them with the class's zone.
    Moved from ``api/manage/_common._wall`` (the server-v2 design, decision 2).
    """
    if stamp is None:
        return None
    return stamp.replace(tzinfo=UTC).astimezone(school_class.tz).replace(tzinfo=None)
```

- [ ] **Step 3: `services/manage/classes.py` gains the names.** After `members` (which ends `return list(await session.scalars(select(BotUser).where(BotUser.class_id == class_id)))`), insert:
```python


def display_name(full_name: str | None, username: str | None, telegram_id: int | None) -> str:
    """The best name held for somebody, as plain text, in the order the bot picks it.

    Falls back to the numeric id rather than to «неизвестный»: an id is
    something an admin can act on, and every answer that shows one is an
    admin's. Not escaped, because it goes into JSON and protobuf; the bot keeps
    an escaping twin (``bot/manage_render/_common.person``). Moved from
    ``api/manage/_common._person`` (the server-v2 design, decision 2).
    """
    if username:
        return f"@{username}"
    if full_name:
        return full_name
    return str(telegram_id) if telegram_id is not None else "—"


async def member_names(session: AsyncSession, class_id: int) -> dict[int, str]:
    """Telegram id -> :func:`display_name`, for everybody with a role in the class."""
    return {
        member.telegram_id: display_name(member.full_name, member.username, member.telegram_id)
        for member in await members(session, class_id)
    }
```

- [ ] **Step 4: `services/manage/subjects.py` gains the pure read and the patch.**
  1. Add `from collections.abc import Mapping` above `from datetime import date as Date`.
  2. Replace the whole of `listing`, from its `async def` to its final `)`, with:
```python
async def dictionary_of(session: AsyncSession, class_id: int) -> list[Subject]:
    """The dictionary, alphabetically, as it stands, adopting nothing.

    What v1's ``GET /subjects`` reads and v2's ``ListSubjects`` reads: a read
    writes nothing (the server-v2 design, decision 10). Every write that names
    a subject in the weekly template links it already, so what :func:`listing`
    adopts is only what was written before the link existed.
    """
    return list(
        await session.scalars(
            select(Subject).where(Subject.class_id == class_id).order_by(Subject.name)
        )
    )


async def listing(session: AsyncSession, class_id: int) -> list[Subject]:
    """The dictionary, alphabetically, after adopting what the timetable uses.

    So the list cannot be empty while the class has a full timetable. The
    adoption writes when something is out of step, and the caller commits it.
    v1's ``/manage/subjects`` and the bot read this; v2 reads
    :func:`dictionary_of`.
    """
    await dictionary.sync_from_timetable(session, class_id)
    return await dictionary_of(session, class_id)
```
  3. After `set_detail` (which ends `f"{label} предмета «{subject.name}»: {value or removed}",` and `)`), insert:
```python


async def update(
    session: AsyncSession,
    class_id: int,
    actor_id: int | None,
    subject: Subject,
    changes: Mapping[str, str | None],
) -> int:
    """Apply a patch: the rename first, then each detail, one log line each.

    ``changes`` maps ``"name"`` and keys of :data:`DETAILS` to their new
    values; a key that is absent is left alone, and ``None`` takes a detail
    away. The rename goes first because it is the change that can be refused,
    and it refuses before anything is written. v1's ``PATCH`` and v2's
    ``UpdateSubject`` both call this, so the order and the lines are one.

    @return how many rows the rename moved; zero without one.
    @raises SubjectExists: another entry has the name, ignoring case.
    @raises HomeworkClash: both names have homework on the same day.
    """
    details = dict(changes)
    name = details.pop("name", None)
    moved = 0
    if name is not None:
        moved = await rename(session, class_id, actor_id, subject, name) or 0
    for column, value in details.items():
        await set_detail(session, class_id, actor_id, subject, column, value)
    return moved
```

- [ ] **Step 5: `services/audit.py` gains the order and the keyset.**
  1. Replace `from sqlalchemy import select` with `from sqlalchemy import and_, or_, select`, and add `from sqlalchemy.orm import aliased` below `from sqlalchemy.ext.asyncio import AsyncSession`.
  2. Below `ACTION_MAX = 64`, insert:
```python

#: Newest first. ``id`` breaks ties: ``created_at`` is a server default with
#: one-second resolution on SQLite and one transaction's ``now()`` on
#: Postgres, and a handler that logs two lines in one go would otherwise show
#: them in arbitrary order. Every reader of the log pages in this order.
NEWEST_FIRST = (AuditEntry.created_at.desc(), AuditEntry.id.desc())
```
  3. In `recent`, replace its docstring and query with:
```python
    """Newest first, in :data:`NEWEST_FIRST` order."""
    rows = await session.scalars(
        select(AuditEntry)
        .where(AuditEntry.class_id == class_id)
        .order_by(*NEWEST_FIRST)
        .limit(limit)
        .offset(offset)
    )
    return list(rows)
```
  4. Append:
```python


async def older_than(
    session: AsyncSession, class_id: int, after_id: int | None, *, limit: int
) -> list[AuditEntry]:
    """Up to ``limit`` lines after the line ``after_id`` in :data:`NEWEST_FIRST`
    order, from the newest when ``after_id`` is ``None``.

    Keyed on a line rather than counted from the top, so a line written
    between two page turns shifts nothing: an offset would show the last line
    of one page again at the top of the next. The anchor's ``created_at`` is
    read in SQL rather than bound from Python, because SQLite stores a
    server-default stamp without microseconds and a bound one with them, and
    the two would compare as different strings at the same instant.
    """
    query = select(AuditEntry).where(AuditEntry.class_id == class_id)
    if after_id is not None:
        anchor = aliased(AuditEntry)
        at = select(anchor.created_at).where(anchor.id == after_id).scalar_subquery()
        query = query.where(
            or_(
                AuditEntry.created_at < at,
                and_(AuditEntry.created_at == at, AuditEntry.id < after_id),
            )
        )
    return list(await session.scalars(query.order_by(*NEWEST_FIRST).limit(limit)))
```

- [ ] **Step 6: `services/manage/journal.py` gains `has_line` and `page_after`.** Replace `from sqlalchemy.ext.asyncio import AsyncSession` with the two lines `from sqlalchemy import select` and `from sqlalchemy.ext.asyncio import AsyncSession`, and append:
```python


async def has_line(session: AsyncSession, class_id: int, entry_id: int) -> bool:
    """Whether ``entry_id`` names a line of this class's log. A page token of
    another class's log, or of a line that never was, names nothing here."""
    found = await session.scalar(
        select(AuditEntry.id).where(AuditEntry.id == entry_id, AuditEntry.class_id == class_id)
    )
    return found is not None


async def page_after(
    session: AsyncSession, class_id: int, *, limit: int, after_id: int | None
) -> tuple[list[AuditEntry], bool]:
    """``limit`` lines after the line ``after_id``, newest first, and whether
    more follow: v2's page, keyed on the last line it served
    (:func:`app.services.audit.older_than`). One row more is read and thrown
    away, as :func:`page` does, because that is what «more» is."""
    entries = await audit.older_than(session, class_id, after_id, limit=limit + 1)
    return entries[:limit], len(entries) > limit
```

- [ ] **Step 7: `app/wording.py` gains the sentences both versions say.**
  1. Add `from collections.abc import Iterable` above `from datetime import date as Date`.
  2. Append to the end of the file:
```python

#: v1's ``/manage/subjects`` and v2's ``SubjectService``: an id that names no
#: subject of the class, a name the class already has (ignoring case), a
#: rename that would put two assignments on one subject and one day, and a
#: subject the weekly template still teaches.
UNKNOWN_SUBJECT_DETAIL = "Unknown subject"
SUBJECT_EXISTS_DETAIL = "a subject with that name is already in this class"


def subject_rename_clash_detail(days: Iterable[Date]) -> str:
    return "homework under both names on the same day: " + ", ".join(
        day.isoformat() for day in days
    )


def subject_in_use_detail(lessons: int) -> str:
    return f"{lessons} lesson(s) still use this subject"


#: v1's ``/manage/devices`` and v2's ``ClassDeviceService``: an id that names
#: no phone of the class, and an unlink of a phone no account is behind.
UNKNOWN_DEVICE_DETAIL = "Unknown device"
CLASS_DEVICE_NOT_LINKED_DETAIL = "device is not linked"
```

- [ ] **Step 8: v1 calls the moved code.**
  1. Replace `server/app/api/manage/_common.py` whole with:
```python
"""What every endpoint of :mod:`app.api.manage` shares: who is asking, and
the refusal the class's own state makes.

The member names and the class's wall clock lived here too, until v2 needed
them (``docs/specs/2026-10-05-server-v2-design.md``, decision 2): they are
``services/manage/classes.member_names`` and ``services/clock.wall`` now.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass

from dishka.integrations.fastapi import FromDishka, inject
from fastapi import Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_device
from app.models import DeviceToken, Role
from app.services import linking

# --------------------------------------------------------------------------
# Who may manage
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Actor:
    """Who is making the change: the device, the Telegram account behind it,
    and that account's role in the class *right now*."""

    device: DeviceToken
    telegram_id: int
    role: Role


def _role_at_least(minimum: Role) -> Callable[..., Awaitable[Actor]]:
    """A dependency demanding ``minimum`` of the linked account.

    One factory rather than a guard written out in each endpoint, for the
    reason the bot keeps ``_allowed`` in one place: a check that is copied is a
    check that will eventually be copied wrong. The refusals are the two the
    app already knows from ``app.api.edit`` - «привяжите телефон» and «нужна
    роль» - with the role named, because the app shows a different screen for
    each and an admin-only page has to say «admin», not «editor».
    """

    @inject
    async def dependency(
        device: DeviceToken = Depends(current_device),
        *,
        session: FromDishka[AsyncSession],
    ) -> Actor:
        if device.telegram_id is None:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN, detail="device is not linked"
            )
        role = await linking.effective_role(session, device)
        if role is None or not role.at_least(minimum):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN, detail=f"{minimum.value} role required"
            )
        return Actor(device=device, telegram_id=device.telegram_id, role=role)

    return dependency


editor_actor = _role_at_least(Role.EDITOR)
admin_actor = _role_at_least(Role.ADMIN)
owner_actor = _role_at_least(Role.OWNER)


# --------------------------------------------------------------------------
# Small shared pieces
# --------------------------------------------------------------------------


def _conflict(detail: str) -> HTTPException:
    """409, for a request that is well-formed and refused by the class's own
    state: a name already taken, a schedule days still point at."""
    return HTTPException(status_code=status.HTTP_409_CONFLICT, detail=detail)
```
  2. Replace `server/app/api/manage/journal.py` whole with:
```python
"""``/log``: «📜 Журнал», a page at a time.

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring.
"""

from __future__ import annotations

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class
from app.api.manage._common import Actor, admin_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import AuditEntry, SchoolClass
from app.schemas import AuditEntryOut, AuditPageOut
from app.services.clock import wall
from app.services.manage import journal as journal_service
from app.services.manage.classes import member_names

router = APIRouter(route_class=DishkaAnnotatedRoute)

#: Audit lines per page, matching «📜 Журнал» in the bot.
AUDIT_PAGE = 30


# --------------------------------------------------------------------------
# 📜 The audit log
# --------------------------------------------------------------------------


@router.get("/log", response_model=AuditPageOut)
async def audit_log(
    limit: int = Query(default=AUDIT_PAGE, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    _: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> AuditPageOut:
    """Who changed what, newest first; ``has_more`` says whether a page follows."""
    entries, has_more = await journal_service.page(
        session, school_class.id, limit=limit, offset=offset
    )
    names = await member_names(session, school_class.id)

    def _entry(row: AuditEntry) -> AuditEntryOut:
        return AuditEntryOut(
            id=row.id,
            action=row.action,
            summary=row.summary,
            who=names.get(row.telegram_id) if row.telegram_id is not None else None,
            at=wall(row.created_at, school_class),
        )

    return AuditPageOut(
        entries=[_entry(row) for row in entries],
        limit=limit,
        offset=offset,
        has_more=has_more,
    )
```
  3. Replace `server/app/api/manage/devices.py` whole with:
```python
"""``/devices``: the phones on the class, as «📱 Устройства» lists them.

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring.
"""

from __future__ import annotations

import logging

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app import wording
from app.api.deps import current_class
from app.api.manage._common import Actor, _conflict, admin_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import DeviceToken, Role, SchoolClass
from app.schemas import ManagedDeviceOut
from app.services import linking
from app.services.clock import wall
from app.services.manage import devices as devices_service
from app.services.manage.classes import member_names

log = logging.getLogger(__name__)

router = APIRouter(route_class=DishkaAnnotatedRoute)


# --------------------------------------------------------------------------
# 📱 Devices
# --------------------------------------------------------------------------


def _device_out(
    device: DeviceToken,
    school_class: SchoolClass,
    names: dict[int, str],
    role: Role | None,
) -> ManagedDeviceOut:
    return ManagedDeviceOut(
        id=device.id,
        device_name=device.device_name,
        linked=device.is_linked,
        owner=names.get(device.telegram_id) if device.telegram_id is not None else None,
        role=role.value if role is not None else None,
        revoked=device.revoked,
        created_at=wall(device.created_at, school_class),
        last_seen_at=wall(device.last_seen_at, school_class),
        linked_at=wall(device.linked_at, school_class),
    )


async def _device_or_404(
    session: AsyncSession, school_class: SchoolClass, device_id: int
) -> DeviceToken:
    device = await devices_service.device_of(session, school_class.id, device_id)
    if device is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=wording.UNKNOWN_DEVICE_DETAIL
        )
    return device


@router.get("/devices", response_model=list[ManagedDeviceOut])
async def devices_list(
    include_revoked: bool = Query(default=False),
    _: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> list[ManagedDeviceOut]:
    """The phones on the class's list, oldest first.

    The role is a lookup, not a stored field: a device acts with whatever role
    its owner holds right now, so revoking somebody in the bot has already
    changed this list by the time it is drawn. ``include_revoked`` brings back
    the ones that were switched off, which the bot's page leaves out.
    """
    devices = await linking.devices_of(session, school_class.id, include_revoked=include_revoked)
    names = await member_names(session, school_class.id)
    # Once per owner, not once per phone, through the function «📱 Устройства»
    # in the bot resolves it with.
    roles = await devices_service.owner_roles(session, devices)
    return [
        _device_out(device, school_class, names, roles.get(device.telegram_id))
        for device in devices
    ]


@router.post("/devices/{device_id}/revoke", response_model=ManagedDeviceOut)
async def device_revoke(
    device_id: int,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> ManagedDeviceOut:
    """Switch a phone off. Revoked, not deleted: the row is what a token is
    checked against, and keeping it is what makes the refusal instant and
    permanent. Revoking an already revoked device changes nothing and adds no
    second line to the log.

    An admin may do this to the phone they are holding, and then the next
    request from it is a 401. That is the point - it is how a lost phone is
    dealt with from the one that is still in a pocket.
    """
    device = await _device_or_404(session, school_class, device_id)
    names = await member_names(session, school_class.id)
    role = await linking.effective_role(session, device)
    if await devices_service.revoke(session, school_class.id, actor.telegram_id, device):
        await session.commit()
    return _device_out(device, school_class, names, role)


@router.post("/devices/{device_id}/unlink", response_model=ManagedDeviceOut)
async def device_unlink(
    device_id: int,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> ManagedDeviceOut:
    """Back to read-only, without taking the phone off the class.

    The device keeps reading the timetable and loses the role it borrowed from
    its owner's account. A device that is not linked has nothing to unlink,
    which is a 409 rather than a silent success: the admin pressed it expecting
    something to change.
    """
    device = await _device_or_404(session, school_class, device_id)
    try:
        await devices_service.unlink(session, school_class.id, actor.telegram_id, device)
    except devices_service.DeviceNotLinked as not_linked:
        raise _conflict(wording.CLASS_DEVICE_NOT_LINKED_DETAIL) from not_linked
    await session.commit()
    names = await member_names(session, school_class.id)
    role = await linking.effective_role(session, device)
    return _device_out(device, school_class, names, role)
```
  4. In `server/app/api/manage/requests.py`:
     - Replace the import `from app.api.manage._common import Actor, _member_names, _person, _wall, admin_actor` with `from app.api.manage._common import Actor, admin_actor`.
     - Below `from app.services import access as access_service`, insert `from app.services.clock import wall` and `from app.services.manage import classes as classes_service`, so that the block reads `access`, `clock`, `manage … classes`, `manage … requests`.
     - Replace both `names = await _member_names(session, school_class.id)` with `names = await classes_service.member_names(session, school_class.id)`.
     - Replace `created_at=_wall(row.created_at, school_class),` with `created_at=wall(row.created_at, school_class),`.
     - Replace `who = _person(member.full_name, member.username, member.telegram_id)` with `who = classes_service.display_name(member.full_name, member.username, member.telegram_id)`.
  5. Replace `server/app/api/manage/subjects.py` whole with:
```python
"""``/subjects``: the class's subject dictionary, as «📚 Предметы» edits it.

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring.
"""

from __future__ import annotations

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app import wording
from app.api.deps import current_class
from app.api.manage._common import Actor, _conflict, admin_actor, editor_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import SchoolClass, Subject
from app.schemas import DeletedOut, ManagedSubjectOut, SubjectIn, SubjectPatch, SubjectSavedOut
from app.services.manage import subjects as subjects_service

router = APIRouter(route_class=DishkaAnnotatedRoute)


# --------------------------------------------------------------------------
# 📚 Subjects
# --------------------------------------------------------------------------


def _subject_out(subject: Subject) -> ManagedSubjectOut:
    return ManagedSubjectOut(
        id=subject.id,
        name=subject.name,
        short_name=subject.short_name,
        teacher=subject.teacher,
        color=subject.color,
    )


async def _subject_or_404(
    session: AsyncSession, school_class: SchoolClass, subject_id: int
) -> Subject:
    subject = await subjects_service.subject_of(session, school_class.id, subject_id)
    if subject is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=wording.UNKNOWN_SUBJECT_DETAIL
        )
    return subject


@router.get("/subjects", response_model=list[ManagedSubjectOut])
async def subjects_list(
    _: Actor = Depends(editor_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> list[ManagedSubjectOut]:
    """The dictionary with ids, for a screen that edits it. An editor may
    read it - it is the same list ``GET /api/v1/subjects`` gives any device.

    Adopts whatever the timetable already uses on the way, so this list is
    never emptily lying about a class with thirty-five lessons in it. Free once
    the two agree, which after the first read they do. v2's ``ListSubjects``
    does not adopt (the server-v2 design, decision 10); this read keeps doing
    it until v1 is retired.
    """
    # Committed whatever the count says, the way `public.bundle` does it. The
    # number that comes back is how many dictionary entries were *created*, and
    # the function also links the timetable rows to them — so a class whose
    # dictionary was already complete but whose lessons were not yet pointed at
    # it got its UPDATEs run and then dropped when the session closed, on every
    # read, forever. It healed only because `/bundle` commits unconditionally
    # and a phone polls it; the screen that exists to edit this list did the
    # work and threw it away.
    rows = await subjects_service.listing(session, school_class.id)
    await session.commit()
    return [_subject_out(row) for row in rows]


@router.post("/subjects", response_model=SubjectSavedOut, status_code=status.HTTP_201_CREATED)
async def subject_create(
    payload: SubjectIn,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> SubjectSavedOut:
    """Add a subject. The name is unique within the class - that uniqueness is
    the whole point of the dictionary, so a duplicate is a 409, not a silent
    second «Алгебра»."""
    try:
        subject = await subjects_service.create(
            session,
            school_class.id,
            actor.telegram_id,
            payload.name,
            short_name=payload.short_name,
            teacher=payload.teacher,
            color=payload.color,
        )
    except subjects_service.SubjectExists as taken:
        raise _conflict(wording.SUBJECT_EXISTS_DETAIL) from taken
    await session.commit()
    await session.refresh(subject)
    return SubjectSavedOut(subject=_subject_out(subject))


@router.patch("/subjects/{subject_id}", response_model=SubjectSavedOut)
async def subject_update(
    subject_id: int,
    payload: SubjectPatch,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> SubjectSavedOut:
    """Rename a subject, or set its short name, teacher or colour.

    A rename is a cascade: the timetable, the homework and the substitutions store the
    subject as text, so all three move with it in this one transaction, and
    ``moved`` says how many rows did. Renaming onto a name the class already
    uses is refused - merging two subjects is a different operation, and doing
    it by accident cannot be undone. The patch itself is
    ``services/manage/subjects.update``, which v2's ``UpdateSubject`` applies too.
    """
    subject = await _subject_or_404(session, school_class, subject_id)
    try:
        moved = await subjects_service.update(
            session,
            school_class.id,
            actor.telegram_id,
            subject,
            payload.model_dump(exclude_unset=True),
        )
    except subjects_service.SubjectExists as taken:
        raise _conflict(wording.SUBJECT_EXISTS_DETAIL) from taken
    except subjects_service.HomeworkClash as clash:
        raise _conflict(wording.subject_rename_clash_detail(clash.days)) from clash

    await session.commit()
    await session.refresh(subject)
    return SubjectSavedOut(subject=_subject_out(subject), moved=moved)


@router.delete("/subjects/{subject_id}", response_model=DeletedOut)
async def subject_delete(
    subject_id: int,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> DeletedOut:
    """Deleting a subject the timetable still uses is refused.

    It used to be allowed, and it left the lessons alone: the timetable stores
    the name as well as the link, so the class kept its timetable and lost
    only the colour and the teacher. That stopped being true when the
    dictionary started keeping itself. The name is still in the template, so
    the next read adopts it again - the entry returns within one poll, without
    its colour, its short name or its teacher, and the admin is left believing
    they deleted something.

    So the two halves of one list are deleted in one order: take the subject
    out of the weekly template, and then out of the dictionary. A subject
    nothing teaches still deletes in one step, which is the case this endpoint
    was really for.
    """
    subject = await _subject_or_404(session, school_class, subject_id)
    try:
        await subjects_service.delete(session, school_class.id, actor.telegram_id, subject)
    except subjects_service.SubjectInUse as in_use:
        raise _conflict(wording.subject_in_use_detail(in_use.lessons)) from in_use
    await session.commit()
    return DeletedOut(id=subject_id)
```
  6. In `server/app/api/public.py`:
     - Delete the line `    Subject,` from the `from app.models import (` block; `/subjects` is its only user.
     - Below `from app.services import terms as terms_service`, insert `from app.services.manage import subjects as manage_subjects`.
     - In `subjects`, replace the three lines that start `rows = await session.scalars(` and end `)`:
```python
    rows = await session.scalars(
        select(Subject).where(Subject.class_id == school_class.id).order_by(Subject.name)
    )
```
       with:
```python
    rows = await manage_subjects.dictionary_of(session, school_class.id)
```

- [ ] **Step 9: Green, and v1 unchanged.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_services_manage.py tests/test_api_manage.py tests/test_services.py tests/test_subjects.py tests/test_bot_manage.py tests/test_api_extended.py tests/test_service_layering.py
```
Expected: every test passes. `test_services_manage.py` has 10. v1's files pass unchanged: `test_api_manage.py` holds every 404, 409 and audit line of `/manage/devices`, `/manage/log`, `/manage/subjects` and `/manage/requests`, and `test_api_extended.py` holds `/subjects`.

- [ ] **Step 10: Gates.** ruff: `All checks passed!`. mypy: `Success: no issues found in 214 source files`. Then the full suite once:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -n auto
```
Expected: 2447 passed (2437 + 10).

- [ ] **Step 11: Commit.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3b1-t1.txt`:
```text
Move the manage routers' shared rules into services before v2 needs them

The member names and the class's wall clock lived in api/manage/_common.py,
the dictionary read and the rename-then-details patch in
api/manage/subjects.py. A v2 handler can import neither a v1 router nor a
copy of its rules, so they move into services/ first, and v1 calls them:
classes.member_names and display_name, clock.wall, subjects.dictionary_of
(the pure read v1's /subjects already made) and subjects.update. v1's
answers are unchanged; test_api_manage.py, untouched, is the proof. The
sentences v1 answers with for these refusals move into app/wording.py,
where v2's error table will read them.

The journal gains a page keyed on its last line (audit.older_than,
journal.page_after), for v2's page token: a line written between two page
turns moves nothing on the next page, which v1's offset does. The anchor's
stamp is read in SQL, because SQLite stores a server-default stamp without
microseconds and a bound one with them.

Not covered: no v2 method uses any of this yet; the next commits serve them.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/services/clock.py server/app/services/manage/classes.py server/app/services/manage/subjects.py server/app/services/audit.py server/app/services/manage/journal.py server/app/wording.py server/app/api/manage/_common.py server/app/api/manage/devices.py server/app/api/manage/journal.py server/app/api/manage/requests.py server/app/api/manage/subjects.py server/app/api/public.py server/tests/test_services_manage.py && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3b1-t1.txt
```

---

## Task 2: Each phone's last app version, recorded beside `last_seen_at` (revision `0018`)

Decisions 10 and 15. This task records the version; Task 4 shows it.

**Files:**
- Create: `server/migrations/versions/0018_device_client_version.py`, `server/tests/test_v2_client_version.py`, `server/tests/test_client_version_revision.py`
- Modify: `server/app/models.py`, `server/app/db.py`, `server/app/api/deps.py`, `server/app/rpc/gate.py`, `server/tests/test_v2_reads.py`
- Modify (the head moves): `CLAUDE.md`, `AGENTS.md`, `docs/api.md`, `docs/deploy.md`, `.claude/agents/server-migrations.md`, `.claude/skills/migration/SKILL.md`, `docs/specs/2026-10-05-server-v2-3a-plan.md`
- Scratch, never committed: `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\head0018.py`, `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\scan_heads.py`, `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\issue-3b1-head-record.md`

**Interfaces:**
- Consumes:
  - `gate.client_version(headers, settings) -> int | None`, which is `None` for a missing header, and for a malformed one when no minimum is set;
  - `Admitted.client_version`.
- Produces:
  - `DeviceToken.client_version: Mapped[int | None]`;
  - `deps.touch_last_seen(session, device, *, client_version: int | None = None) -> None`;
  - `test_v2_reads.ALLOWED_WRITES[0]`, which matches `UPDATE device_tokens SET last_seen_at=?[, client_version=?] WHERE`;
  - `db.EXPECTED_REVISION == "0018"`.

- [ ] **Step 1: File the defect this task fixes, before the fix.** When `EXPECTED_REVISION` moves, `docs/specs/2026-10-05-server-v2-3a-plan.md` fails the head test (Ruling 11).
  1. Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\issue-3b1-head-record.md`:
```markdown
`test_schema_version.py::test_every_document_that_names_the_head_names_this_one`
reads every `*.md` under `docs/` except `docs/history.md`, and `docs/specs/` is
included. It fails when any of those documents names a revision other than
`EXPECTED_REVISION` in one of three shapes:

- the words «head is», then a backticked revision;
- the word «expects», then a backticked revision;
- `/warmup`'s quoted ok answer, with its schema on the same line.

`docs/specs/2026-10-05-server-v2-3a-plan.md`, line 6952, quotes that answer with
schema `0017`, as the expected output of 3a's post-merge check. It is a record,
and it was true when it was written. Once a revision moves `EXPECTED_REVISION`,
the suite fails on a sentence that nobody meant as a claim about today's head.

**Failure scenario:** add revision `0018`, move `EXPECTED_REVISION`, and update
every reference document. `pytest -q -n auto` then fails in that test, which
names `docs/specs/2026-10-05-server-v2-3a-plan.md` under `0017`.

**Fix**, in 3b-1's Task 2 (`docs/specs/2026-10-05-server-v2-3b-plan.md`):
- Reword the record so that it states the same expectation without the quoted
  answer.
- Keep later plans out of the three shapes: the 3b plan's Global Constraints say
  so, and its Task 2 scans for them.

Excluding `docs/specs/` from the test was considered and not done, because
`test_ci_paths.py` keys CI's path filter on the same list of documents.
```
  2. Run from the worktree:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && gh issue create --repo lumenpearson/lessons --title "A plan under docs/specs that quotes /api/v1/warmup's answer fails the head test at the next revision" --body-file C:/Users/lumen/.claude/jobs/c9e2d980/tmp/issue-3b1-head-record.md --label type:bug --label area:docs --label status:now --milestone "v0.10.0 — One contract: REST v2, Connect and native gRPC, build console"
```
  3. Write down the number it prints. It is `#HEADREC` below, and the pull request's body says `Closes #HEADREC`.
  4. Put the issue on project 6 with the `github-pr` skill's «The board» commands, and read the board back.

- [ ] **Step 2: Red.** Create `server/tests/test_v2_client_version.py`:
```python
"""Each phone's last app version: recorded by v2's gate, never by v1.

The gate writes ``device_tokens.client_version`` in the statement that writes
``last_seen_at``, on the same fifteen-minute clock, and only when a usable
``X-Lessons-Client`` came (``docs/specs/2026-10-05-server-v2-design.md``,
decision 15). So «reads write nothing» grows by one column of an update it
already allows and by nothing else; a call without the header keeps what was
known, and a v1 request never writes it.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from sqlalchemy import select, update

from app.config import get_settings
from app.models import DeviceToken
from app.security import hash_token

#: The one statement a touch writes, with a version and without one.
SEEN_WITH_VERSION = (
    "UPDATE device_tokens SET last_seen_at=?, client_version=? WHERE device_tokens.id = ?"
)
SEEN = "UPDATE device_tokens SET last_seen_at=? WHERE device_tokens.id = ?"


async def _device(session, token: str) -> DeviceToken:
    device = await session.scalar(
        select(DeviceToken).where(DeviceToken.token_hash == hash_token(token))
    )
    await session.refresh(device)
    return device


async def _seen_long_ago(session, token: str) -> None:
    """Put the phone's last call sixteen minutes back, past the clock."""
    await session.execute(
        update(DeviceToken)
        .where(DeviceToken.token_hash == hash_token(token))
        .values(last_seen_at=datetime.now(UTC).replace(tzinfo=None) - timedelta(minutes=16))
    )
    await session.commit()


async def test_a_v2_call_records_the_version_beside_the_last_seen_in_one_statement(
    v2, v2_tokens, session, statement_writes
) -> None:
    with statement_writes() as seen:
        answer = await v2.both(
            "MeService/GetMe", token=v2_tokens["viewer"], headers={"X-Lessons-Client": "412"}
        )
    assert answer.status == 200
    # REST touched the phone; Connect, a moment later, found it seen.
    assert seen == [SEEN_WITH_VERSION]
    device = await _device(session, v2_tokens["viewer"])
    assert device.client_version == 412
    assert device.last_seen_at is not None


async def test_a_new_version_inside_the_fifteen_minutes_waits_for_the_next_touch(
    v2, v2_tokens, session
) -> None:
    token = v2_tokens["viewer"]
    await v2.rest("MeService/GetMe", token=token, headers={"X-Lessons-Client": "412"})
    await v2.rest("MeService/GetMe", token=token, headers={"X-Lessons-Client": "413"})
    assert (await _device(session, token)).client_version == 412
    await _seen_long_ago(session, token)
    await v2.rest("MeService/GetMe", token=token, headers={"X-Lessons-Client": "413"})
    assert (await _device(session, token)).client_version == 413


async def test_a_call_without_the_header_keeps_the_version_it_had(
    v2, v2_tokens, session, statement_writes
) -> None:
    token = v2_tokens["viewer"]
    await v2.rest("MeService/GetMe", token=token, headers={"X-Lessons-Client": "412"})
    await _seen_long_ago(session, token)
    with statement_writes() as seen:
        answer = await v2.rest("MeService/GetMe", token=token)
    assert answer.status == 200
    assert seen == [SEEN]
    assert (await _device(session, token)).client_version == 412


async def test_a_header_that_is_no_version_is_ignored_without_a_minimum(
    v2, v2_tokens, session
) -> None:
    token = v2_tokens["viewer"]
    for junk in ("abc", "0", "-5", "4.1", "2100000001", "1" * 11):
        await _seen_long_ago(session, token)
        answer = await v2.rest(
            "MeService/GetMe", token=token, headers={"X-Lessons-Client": junk}
        )
        assert answer.status == 200, junk
    assert (await _device(session, token)).client_version is None


async def test_a_phone_told_to_update_is_neither_seen_nor_recorded(
    v2, v2_tokens, session, monkeypatch
) -> None:
    """The version is read before the bearer, so its refusal touches nothing."""
    monkeypatch.setattr(get_settings(), "min_client_version", 500)
    answer = await v2.both(
        "MeService/GetMe", token=v2_tokens["viewer"], headers={"X-Lessons-Client": "412"}
    )
    assert (answer.code, answer.reason) == ("FAILED_PRECONDITION", "CLIENT_TOO_OLD")
    device = await _device(session, v2_tokens["viewer"])
    assert (device.client_version, device.last_seen_at) == (None, None)


async def test_v1_never_records_a_version(v2, v2_tokens, session, statement_writes) -> None:
    token = v2_tokens["viewer"]
    with statement_writes() as seen:
        response = await v2.http.get(
            "/api/v1/me",
            headers={"Authorization": f"Bearer {token}", "X-Lessons-Client": "412"},
        )
    assert response.status_code == 200
    assert seen == [SEEN]
    assert (await _device(session, token)).client_version is None
```
Create `server/tests/test_client_version_revision.py`:
```python
"""``0018``, the revision that records each phone's last app version.

The ordinary additive shape: one nullable integer column on ``device_tokens``,
with no default, no index and no key, so it goes on before the merge and the
running code never notices it (``docs/specs/2026-10-05-server-v2-design.md``,
decision 15). Held as ``0015``'s columns are held: on Postgres it builds
exactly what the model declares, it asks whether the column is there before
it adds it, and on the way down it drops that column and nothing else.

Nothing here connects to Postgres: the Postgres half renders the revision
offline, the way ``alembic upgrade --sql`` would.
"""

from __future__ import annotations

import importlib.util
import io
import os
import sqlite3
import subprocess
import sys
from pathlib import Path

from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy.dialects import postgresql

from app.db import EXPECTED_REVISION
from app.models import DeviceToken

SERVER_ROOT = Path(__file__).resolve().parent.parent
REVISION = SERVER_ROOT / "migrations" / "versions" / "0018_device_client_version.py"


def _revision():
    spec = importlib.util.spec_from_file_location("revision_0018", REVISION)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _render(function) -> list[str]:
    rendered = io.StringIO()
    context = MigrationContext.configure(
        dialect_name="postgresql", opts={"as_sql": True, "output_buffer": rendered}
    )
    with Operations.context(context):
        function()
    return [" ".join(part.split()) for part in rendered.getvalue().split(";") if part.strip()]


def _alembic(database: Path, *args: str) -> subprocess.CompletedProcess:
    env = dict(os.environ, DATABASE_URL=f"sqlite+aiosqlite:///{database}")
    return subprocess.run(
        [sys.executable, "-m", "alembic", *args],
        env=env,
        cwd=str(SERVER_ROOT),
        capture_output=True,
        text=True,
    )


def _columns(database: Path) -> set[str]:
    with sqlite3.connect(database) as db:
        return {row[1] for row in db.execute("PRAGMA table_info(device_tokens)")}


def test_the_revision_follows_0017_and_is_the_one_the_code_expects() -> None:
    revision = _revision()
    assert (revision.revision, revision.down_revision) == ("0018", "0017")
    assert EXPECTED_REVISION == "0018"


def test_on_postgres_it_adds_exactly_the_column_the_model_declares() -> None:
    column = DeviceToken.__table__.c.client_version
    assert column.nullable
    assert column.server_default is None
    assert not column.foreign_keys
    assert not any("client_version" in index.columns for index in DeviceToken.__table__.indexes)
    kind = column.type.compile(dialect=postgresql.dialect())
    assert kind == "INTEGER"
    assert _render(_revision().upgrade) == [
        f"ALTER TABLE device_tokens ADD COLUMN client_version {kind}"
    ]


def test_on_the_way_down_it_drops_the_column_and_nothing_else() -> None:
    assert _render(_revision().downgrade) == [
        "ALTER TABLE device_tokens DROP COLUMN client_version"
    ]


def test_a_file_at_0017_gets_the_column_and_a_file_that_has_it_is_left_alone(tmp_path) -> None:
    """``0001`` builds today's schema, the column included, so the first
    ``upgrade head`` meets the guard with the column there. Taking the column
    out and the stamp back to ``0017`` then makes a file shaped like a
    deployment at ``0017``."""
    database = tmp_path / "at17.db"
    database.touch()
    built = _alembic(database, "upgrade", "head")
    assert built.returncode == 0, built.stderr
    assert "client_version" in _columns(database)

    with sqlite3.connect(database) as db:
        db.execute("ALTER TABLE device_tokens DROP COLUMN client_version")
        db.execute("UPDATE alembic_version SET version_num = '0017'")
    assert "client_version" not in _columns(database)

    upgraded = _alembic(database, "upgrade", "head")
    assert upgraded.returncode == 0, upgraded.stderr
    assert "client_version" in _columns(database)

    down = _alembic(database, "downgrade", "0017")
    assert down.returncode == 0, down.stderr
    assert "client_version" not in _columns(database)
```
In `server/tests/test_v2_reads.py`, replace the first rule of `ALLOWED_WRITES`:
```python
    re.compile(r"^UPDATE device_tokens SET last_seen_at=\? WHERE"),
```
with:
```python
    # The phone's last call, and the app version it sent with it: one
    # statement, on one fifteen-minute clock (decision 15).
    re.compile(r"^UPDATE device_tokens SET last_seen_at=\?(?:, client_version=\?)? WHERE"),
```
and append to the end of the file:
```python


def test_the_last_seen_rule_takes_the_version_beside_it_and_never_alone() -> None:
    """Decision 15: one column more of an update the rule already allowed, and
    nothing else. The version alone, or beside any other column, is a write."""
    seen = "UPDATE device_tokens SET last_seen_at=? WHERE device_tokens.id = ?"
    with_version = (
        "UPDATE device_tokens SET last_seen_at=?, client_version=? WHERE device_tokens.id = ?"
    )
    assert LAST_SEEN.match(seen) and LAST_SEEN.match(with_version)
    assert unexpected([seen, with_version]) == []
    assert unexpected(["UPDATE device_tokens SET client_version=? WHERE device_tokens.id = ?"])
    assert unexpected(
        ["UPDATE device_tokens SET last_seen_at=?, link_code=? WHERE device_tokens.id = ?"]
    )
```
Run:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_v2_client_version.py tests/test_client_version_revision.py tests/test_v2_reads.py
```
Expected:
- `test_v2_client_version.py` fails at the first touch, with `AttributeError`, or a `seen` without `client_version`.
- `test_client_version_revision.py` fails on the missing file and `EXPECTED_REVISION`.
- `test_v2_reads.py`'s new test passes, because the rule already moved. Every other test there passes too.

- [ ] **Step 3: The column.** In `server/app/models.py`, `DeviceToken`, directly below `last_seen_at: Mapped[datetime | None] = mapped_column(DateTime)`, insert:
```python
    # The ``X-Lessons-Client`` version code this phone last sent to v2, written
    # by v2's gate in the statement that writes ``last_seen_at`` and on its
    # fifteen-minute clock (the server-v2 design, decision 15), so that
    # «📱 Устройства» can say which phones still run an old APK before v1 is
    # retired. Null for a phone that never sent one, which is every APK that
    # speaks only v1: v1's requests never write it. Declared right after
    # ``last_seen_at`` so the ORM writes the two in one ``SET``. Revision 0018.
    client_version: Mapped[int | None] = mapped_column(Integer)
```
(`Integer` is already imported there.)

- [ ] **Step 4: The revision.** Create `server/migrations/versions/0018_device_client_version.py`:
```python
"""Record the app version each phone last sent to v2.

Revision ID: 0018
Revises: 0017
Create Date: 2026-10-05

v2's gate reads ``X-Lessons-Client: <versionCode>`` on every call
(``docs/specs/2026-10-05-server-v2-design.md``, decision 9). Decision 15 keeps
the last one per phone, so that «📱 Устройства» can say which phones still run
an old APK before v1 is retired: ``device_tokens.client_version``, written by
the gate in the statement that writes ``last_seen_at`` and on its
fifteen-minute clock. It is ``NULL`` on every row before this revision, and on
every phone that never sent the header, which is every APK that speaks only v1.

**Destroys nothing.** One nullable column with no default, no index and no key.

**Apply this BEFORE the merge**, the ordinary additive order: the new code
writes and reads the column, and the old code never mentions it.

The DDL is the model's own, as ``CLAUDE.md`` requires: the column's type
compiled for PostgreSQL is ``INTEGER``, so the one statement is::

    ALTER TABLE device_tokens ADD COLUMN client_version INTEGER

``tests/test_client_version_revision.py`` holds the revision to it. Applied
through the Neon connector, with ``alembic_version`` stamped in the same
transaction.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0018"
down_revision: str | None = "0017"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _has_column(table: str, column: str) -> bool:
    # 0015's guard, for 0015's reasons: on an empty database 0001's
    # `create_all` has already built the column, and adding it twice is a hard
    # error on Postgres; a SQLite file `scripts.init_db` built before this
    # model change is stamped 0017 without it. Offline (`--sql`) there is
    # nobody to ask, and the database the script is for is one at 0017.
    if op.get_context().as_sql:
        return False
    inspector = sa.inspect(op.get_bind())
    if not inspector.has_table(table):
        return False
    return any(existing["name"] == column for existing in inspector.get_columns(table))


def upgrade() -> None:
    # Nullable with no default: SQLite's own ALTER TABLE ADD COLUMN takes it
    # as it is, and Postgres rewrites no row.
    if not _has_column("device_tokens", "client_version"):
        op.add_column("device_tokens", sa.Column("client_version", sa.Integer(), nullable=True))


def downgrade() -> None:
    op.drop_column("device_tokens", "client_version")
```
In `server/app/db.py`, replace `EXPECTED_REVISION = "0017"` with `EXPECTED_REVISION = "0018"`.

- [ ] **Step 5: The gate writes it, and only beside `last_seen_at`.** In `server/app/api/deps.py`, replace `touch_last_seen`'s signature, docstring and the lines up to `device.last_seen_at = now`:
```python
async def touch_last_seen(session: AsyncSession, device: DeviceToken) -> None:
    """Record that ``device`` phoned home, at most every fifteen minutes. Commits.

    Kept on every read on purpose — v1's and v2's alike — because it is
    telemetry no client observes (the server-v2 design, decision 10).
    """
    now = _utcnow()
    seen = device.last_seen_at
    if seen is not None and timedelta(0) <= now - seen < LAST_SEEN_INTERVAL:
        return

    device.last_seen_at = now
```
with:
```python
async def touch_last_seen(
    session: AsyncSession, device: DeviceToken, *, client_version: int | None = None
) -> None:
    """Record that ``device`` phoned home, at most every fifteen minutes. Commits.

    Kept on every read on purpose — v1's and v2's alike — because it is
    telemetry no client observes (the server-v2 design, decision 10).
    ``client_version`` is the ``X-Lessons-Client`` v2's gate read, recorded in
    the same statement and on the same clock (decision 15): never on its own,
    so a read writes one column more of an update it already makes, and never
    ``None`` over a version already known, so a call without the header
    forgets nothing. v1 passes none and never writes it.
    """
    now = _utcnow()
    seen = device.last_seen_at
    if seen is not None and timedelta(0) <= now - seen < LAST_SEEN_INTERVAL:
        return

    device.last_seen_at = now
    if client_version is not None:
        device.client_version = client_version
```
In `server/app/rpc/gate.py`:
- In the module docstring, replace the item
```
3. the bearer of the method's kind, by ``api/deps.py``'s rules, with the
   device's class and its ``last_seen_at``;
```
  with
```
3. the bearer of the method's kind, by ``api/deps.py``'s rules, with the
   device's class, its ``last_seen_at``, and beside it the client version
   (decision 15);
```
- In `admit`, replace `    await touch_last_seen(session, device)` with `    await touch_last_seen(session, device, client_version=admitted.client_version)`.

- [ ] **Step 6: Move the head in every document that names it** (Ruling 11). Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\head0018.py`:
```python
"""Move every sentence that names the schema head to 0018 (3b-1, Task 2).

Each replacement asserts how often it lands, so a document that moved since
the plan was written fails here rather than keeping the old head quietly.
"""

import re
from pathlib import Path

ROOT = Path("C:/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract")
OLD, NEW = "0017", "0018"

# The two shapes test_schema_version.py reads, built from pieces so that this
# script, quoted in a plan under docs/, is not itself read as naming a head.
HEAD = re.compile(r"([Hh]ead is \**`)" + OLD + "`")
WARM = re.compile(r'("status": ?"ok"[^}\n]*"schema": ?")' + OLD + '"')


def move(name, pattern, end, count):
    path = ROOT / name
    text = path.read_text("utf-8")
    text, made = pattern.subn(lambda match: match.group(1) + NEW + end, text)
    assert made == count, (name, made)
    path.write_text(text, "utf-8")


def swap(name, old, new):
    path = ROOT / name
    text = path.read_text("utf-8")
    assert text.count(old) == 1, (name, old[:50])
    path.write_text(text.replace(old, new), "utf-8")


move("CLAUDE.md", HEAD, "`", 1)
move("AGENTS.md", HEAD, "`", 1)
move(".claude/agents/server-migrations.md", HEAD, "`", 1)
move(".claude/skills/migration/SKILL.md", HEAD, "`", 1)
move("docs/deploy.md", HEAD, "`", 1)
move("docs/deploy.md", WARM, '"', 2)
move("docs/api.md", WARM, '"', 1)

swap(
    "CLAUDE.md",
    ", on production since 26 September 2026 with `0015` and `0016`, before #140's merge.**",
    ", applied before the merge of stage 3b-1 of sub-project 3; `0015` to `0017` were"
    " applied on 26 September 2026, before #140's merge.**",
)
swap(
    "CLAUDE.md",
    "  `alembic downgrade 0016 && alembic upgrade head`.\n"
    "  Nothing after `0001` may use `create_all`.",
    "  `alembic downgrade 0016 && alembic upgrade head`.\n"
    "  `0018` adds `device_tokens.client_version`, the app version each phone last sent\n"
    "  to v2 (the server-v2 design, decision 15): one nullable integer column, the\n"
    "  ordinary additive shape, on **before** the merge. Its downgrade drops the column\n"
    "  and with it nothing but the versions.\n"
    "  Nothing after `0001` may use `create_all`.",
)
swap(
    ".claude/agents/server-migrations.md",
    ", and production is at `0017`: `0015`,\n"
    "`0016` and `0017` went on together on 26 September 2026, before #140 merged.",
    ": `0015`, `0016` and `0017` went on together on 26 September 2026,\n"
    "before #140 merged, and `0018` before stage 3b-1 of sub-project 3 merged.",
)
swap(
    ".claude/agents/server-migrations.md",
    "every collision; its docstring counts both before the transaction.",
    "every collision; its docstring counts both before the transaction. `0018` adds\n"
    "`device_tokens.client_version`, the app version a phone last sent to v2: additive.",
)
swap(
    ".claude/agents/server-migrations.md",
    "`0005` through `0017` were all applied that way.",
    "`0005` through `0018` were all applied that way.",
)
swap(
    "docs/deploy.md",
    "which re-runs it because the downgrade does\nnothing.\n",
    "which re-runs it because the downgrade does\nnothing.\n\n"
    "`0018` adds one column, `device_tokens.client_version`: the app version each phone last\n"
    "sent with a v2 request, written by v2's gate beside `last_seen_at` and on its clock. It\n"
    "is nullable with no default, additive, and on **before** the merge; its downgrade drops\n"
    "the column, which loses nothing but the versions.\n",
)
swap(
    "docs/deploy.md",
    "a second run finds nothing to do. ",
    "a second run finds nothing to do. `0018` asks per column, as `0015` does. ",
)
swap(
    "docs/specs/2026-10-05-server-v2-3a-plan.md",
    'Expected: `{"status":"ok","api_version":1,'
    '"schema":"0017"}`;',
    'Expected: `/api/v1/warmup` reporting `status` `"ok"` at schema `0017`, the head when'
    " this plan ran;",
)
print("moved")
```
and run it:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe C:/Users/lumen/.claude/jobs/c9e2d980/tmp/head0018.py
```
Expected: `moved`. An `AssertionError` names the document and the text that moved since this plan was written: read it, fix that one replacement, and run again on a clean tree (`git checkout -- <file>` for the documents it already wrote).

- [ ] **Step 7: Scan every document the head test reads, this plan included.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\scan_heads.py`:
```python
"""Every sentence the head test reads, in every document it reads (3b-1, Task 2)."""

import re
from pathlib import Path

ROOT = Path("C:/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract")
# test_schema_version.py's three shapes, built from pieces for the reason
# head0018.py gives.
HEAD = re.compile(r"[Hh]ead is " + r"\**`(\d{4})`")
EXPECTS = re.compile(r"expects " + r"`(\d{4})`")
WARM = re.compile(r'"status": ?"ok"' + r'[^}\n]*"schema": ?"(\d{4})"')

documents = [ROOT / name for name in ("README.md", "CLAUDE.md", "AGENTS.md")]
documents.append(ROOT / ".github" / "copilot-instructions.md")
documents += sorted((ROOT / "docs").rglob("*.md"))
worktrees = ROOT / ".claude" / "worktrees"
documents += sorted(p for p in (ROOT / ".claude").rglob("*.md") if not p.is_relative_to(worktrees))

named = set()
for document in documents:
    if not document.is_file() or document == ROOT / "docs" / "history.md":
        continue
    text = document.read_text("utf-8")
    patterns = [HEAD, EXPECTS] + ([WARM] if document.is_relative_to(ROOT / "docs") else [])
    for pattern in patterns:
        for match in pattern.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            print(match.group(1), document.relative_to(ROOT), line)
            named.add(match.group(1))
print("revisions named:", sorted(named))
```
and run it:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe C:/Users/lumen/.claude/jobs/c9e2d980/tmp/scan_heads.py
```
Expected: eight lines, all `0018`, from these places:
- `CLAUDE.md`;
- `AGENTS.md`;
- `docs/api.md`;
- `docs/deploy.md`, three times;
- `.claude/agents/server-migrations.md`;
- `.claude/skills/migration/SKILL.md`.

The last line is `revisions named: ['0018']`. No line names `docs/specs/`.

- [ ] **Step 8: Green.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_v2_client_version.py tests/test_client_version_revision.py tests/test_v2_reads.py tests/test_rpc_gate.py tests/test_schema_version.py tests/test_diary_provider_revision.py tests/test_quota.py tests/test_corrections_per_child_revision.py tests/test_api.py tests/test_hardening.py tests/test_ci_paths.py
```
Expected: all pass. The counts:
- `test_v2_client_version.py`: 6;
- `test_client_version_revision.py`: 4;
- `test_v2_reads.py` gains 1.

The other revision tests run the chain to `0018` through its guard, and `test_schema_version.py` reads the moved documents.

- [ ] **Step 9: Gates.** ruff: `All checks passed!`. mypy: `Success: no issues found in 214 source files`. Then the full suite once, as in Task 1. Expected: 2458 passed (2447 + 11).

- [ ] **Step 10: Commit.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3b1-t2.txt`, with `#HEADREC` replaced by the number from Step 1:
```text
Record the app version each phone last sent to v2, beside its last call

device_tokens.client_version (revision 0018, additive) holds the
X-Lessons-Client version a phone last sent to v2. The gate writes it in
the statement that writes last_seen_at and on the same fifteen-minute
clock, and only when a usable header came, so «reads write nothing» grows
by one column of an update it already allowed: test_v2_reads.py's rule
says so and refuses the column alone. A call without the header keeps the
version known, and v1's requests never write it.

0018 is guarded per column as 0015 is, applied before the merge through
the Neon connector, and destroys nothing. EXPECTED_REVISION and every
document that names the head move with it. The 3a plan quoted /warmup's
answer with its schema as a record, which the head test reads as a claim;
it is reworded (#HEADREC).

Not covered: nothing shows the version yet; v2's ClassDevice and the
bot's «📱 Устройства» do in a later commit. No APK sends the header yet.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/models.py server/migrations/versions/0018_device_client_version.py server/app/db.py server/app/api/deps.py server/app/rpc/gate.py server/tests/test_v2_client_version.py server/tests/test_client_version_revision.py server/tests/test_v2_reads.py CLAUDE.md AGENTS.md docs/api.md docs/deploy.md .claude/agents/server-migrations.md .claude/skills/migration/SKILL.md docs/specs/2026-10-05-server-v2-3a-plan.md && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3b1-t2.txt
```

---

## Task 3: `ListAuditEntries`, a page at a time by a token that names its last line

Decisions 2, 10 and 14; Ruling 4.

**Files:**
- Create: `server/app/rpc/audit.py`, `server/tests/test_v2_audit.py`
- Modify: `proto/lessons/v2/audit.proto` (a comment only), `server/app/contract/**` (regenerated), `server/app/rpc/handlers.py`

**Interfaces:**
- Consumes:
  - Task 1's `journal.has_line`, `journal.page_after` and `classes.member_names`;
  - `values.instant`;
  - `Refusal`.
- Produces:
  - `audit.list_audit_entries(call, ListAuditEntriesRequest) -> ListAuditEntriesResponse`;
  - `audit.page_token(entry_id: int) -> str`;
  - `audit.PAGE_SIZE_REFUSED` and `audit.PAGE_TOKEN_REFUSED`, the two sentences;
  - `audit.DEFAULT_PAGE_SIZE == 30` and `audit.MAX_PAGE_SIZE == 100`.

- [ ] **Step 1: Red.** Create `server/tests/test_v2_audit.py`:
```python
"""``ListAuditEntries``: v1's ``GET /manage/log`` over v2, a page token in place of an offset.

The log is v1's, line for line, with ``at`` an instant instead of the class's
wall time. The token names the last line a page served, so a line written
between two page turns moves nothing on the next page, and a token this list
did not hand out is refused on its field without being repeated
(``docs/specs/2026-10-05-server-v2-3b-plan.md``, Ruling 4).
"""

from __future__ import annotations

import base64
from datetime import datetime, timedelta

from app.contract.lessons.v2.audit_pb import ListAuditEntriesRequest
from app.models import AuditEntry, BotUser, Role, SchoolClass
from app.rpc.audit import PAGE_SIZE_REFUSED, PAGE_TOKEN_REFUSED

START = datetime(2026, 9, 1, 8, 0)
LAST_SEEN = "UPDATE device_tokens SET last_seen_at=?"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _lines(session, school_class, count: int, *, author: int | None = None) -> None:
    """``count`` lines a minute apart, oldest first: «строка 0» onwards."""
    session.add_all(
        AuditEntry(
            class_id=school_class.id,
            telegram_id=author,
            action="test.line",
            summary=f"строка {n}",
            created_at=START + timedelta(minutes=n),
        )
        for n in range(count)
    )
    await session.commit()


def _summaries(answer) -> list[str]:
    return [entry.summary for entry in answer.message.audit_entries]


def _token_of(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


async def test_the_log_is_v1_s_log_newest_first_with_its_authors(
    v2, v2_tokens, session, school_class
) -> None:
    session.add(
        BotUser(telegram_id=2006, class_id=school_class.id, role=Role.EDITOR, username="anna")
    )
    await session.commit()
    await _lines(session, school_class, 3, author=2006)
    # A line the system wrote, and one by somebody who has left the class.
    session.add(
        AuditEntry(
            class_id=school_class.id,
            action="test.system",
            summary="без автора",
            created_at=START + timedelta(hours=1),
        )
    )
    session.add(
        AuditEntry(
            class_id=school_class.id,
            telegram_id=2999,
            action="test.left",
            summary="ушедший",
            created_at=START + timedelta(hours=2),
        )
    )
    await session.commit()
    admin = v2_tokens["admin"]
    v1 = (
        await v2.http.get("/api/v1/manage/log", params={"limit": 100}, headers=_auth(admin))
    ).json()

    answer = await v2.both("AuditService/ListAuditEntries", token=admin)
    entries = answer.message.audit_entries
    assert [entry.id for entry in entries] == [row["id"] for row in v1["entries"]]
    for mine, theirs in zip(entries, v1["entries"], strict=True):
        assert (mine.action, mine.summary) == (theirs["action"], theirs["summary"])
        assert (mine.who if mine.has_field("who") else None) == theirs["who"]
        # v1 wrote the class's wall time; v2 writes the instant.
        on_the_wall = mine.at.to_datetime().astimezone(school_class.tz).replace(tzinfo=None)
        assert on_the_wall == datetime.fromisoformat(theirs["at"])
    assert _summaries(answer) == ["ушедший", "без автора", "строка 2", "строка 1", "строка 0"]
    assert [entry.who for entry in entries if entry.has_field("who")] == ["@anna"] * 3
    assert answer.message.next_page_token == ""


async def test_a_page_is_thirty_lines_and_its_token_brings_the_rest(
    v2, v2_tokens, session, school_class
) -> None:
    await _lines(session, school_class, 35)
    admin = v2_tokens["admin"]
    first = await v2.both("AuditService/ListAuditEntries", token=admin)
    assert _summaries(first) == [f"строка {n}" for n in range(34, 4, -1)]
    token = first.message.next_page_token
    assert token
    second = await v2.both(
        "AuditService/ListAuditEntries", ListAuditEntriesRequest(page_token=token), token=admin
    )
    assert _summaries(second) == [f"строка {n}" for n in range(4, -1, -1)]
    assert second.message.next_page_token == ""


async def test_a_page_size_is_kept_and_one_above_a_hundred_is_a_hundred(
    v2, v2_tokens, session, school_class
) -> None:
    await _lines(session, school_class, 120)
    admin = v2_tokens["admin"]
    two = await v2.both(
        "AuditService/ListAuditEntries", ListAuditEntriesRequest(page_size=2), token=admin
    )
    assert _summaries(two) == ["строка 119", "строка 118"]
    assert two.message.next_page_token
    many = await v2.both(
        "AuditService/ListAuditEntries", ListAuditEntriesRequest(page_size=500), token=admin
    )
    assert len(many.message.audit_entries) == 100
    assert many.message.next_page_token


async def test_a_line_written_between_two_pages_repeats_none_and_skips_none(
    v2, v2_tokens, session, school_class
) -> None:
    await _lines(session, school_class, 4)
    admin = v2_tokens["admin"]
    first = await v2.both(
        "AuditService/ListAuditEntries", ListAuditEntriesRequest(page_size=2), token=admin
    )
    assert _summaries(first) == ["строка 3", "строка 2"]
    session.add(
        AuditEntry(
            class_id=school_class.id,
            action="test.line",
            summary="новая",
            created_at=START + timedelta(hours=1),
        )
    )
    await session.commit()
    second = await v2.both(
        "AuditService/ListAuditEntries",
        ListAuditEntriesRequest(page_size=2, page_token=first.message.next_page_token),
        token=admin,
    )
    assert _summaries(second) == ["строка 1", "строка 0"]
    assert second.message.next_page_token == ""
    fresh = await v2.both(
        "AuditService/ListAuditEntries", ListAuditEntriesRequest(page_size=2), token=admin
    )
    assert _summaries(fresh) == ["новая", "строка 3"]


async def test_a_token_this_list_did_not_give_is_refused_on_its_field(
    v2, v2_tokens, session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    theirs = AuditEntry(class_id=other.id, action="test.line", summary="чужая")
    session.add(theirs)
    await session.commit()
    await _lines(session, school_class, 3)
    for bad in (
        "not a token",
        "@@@@",
        "x" * 65,
        _token_of(b"audit:999999"),
        _token_of(b"audit:0"),
        _token_of(b"audit:-4"),
        _token_of(b"class:1"),
        _token_of(f"audit:{theirs.id}".encode()),
    ):
        answer = await v2.both(
            "AuditService/ListAuditEntries",
            ListAuditEntriesRequest(page_token=bad),
            token=v2_tokens["admin"],
        )
        assert (answer.status, answer.code, answer.reason) == (
            400,
            "INVALID_ARGUMENT",
            "VALIDATION_FAILED",
        ), bad
        assert answer.violations == [("page_token", PAGE_TOKEN_REFUSED)]
        assert answer.error == PAGE_TOKEN_REFUSED


async def test_a_negative_page_size_is_refused_on_its_field(v2, v2_tokens) -> None:
    answer = await v2.both(
        "AuditService/ListAuditEntries",
        ListAuditEntriesRequest(page_size=-1),
        token=v2_tokens["admin"],
    )
    assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED")
    assert answer.violations == [("page_size", PAGE_SIZE_REFUSED)]


async def test_the_log_writes_nothing_but_the_last_seen(
    v2, v2_tokens, session, school_class, statement_writes
) -> None:
    await _lines(session, school_class, 3)
    with statement_writes() as seen:
        answer = await v2.both(
            "AuditService/ListAuditEntries",
            ListAuditEntriesRequest(page_size=2),
            token=v2_tokens["admin"],
        )
    assert answer.status == 200
    assert len(answer.message.audit_entries) == 2
    assert all(statement.startswith(LAST_SEEN) for statement in seen), seen
```
Run:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_v2_audit.py
```
Expected: collection fails with `ModuleNotFoundError: No module named 'app.rpc.audit'`.

- [ ] **Step 2: The proto says what the token is.** In `proto/lessons/v2/audit.proto`, replace the three comment lines above `rpc ListAuditEntries`:
```proto
  // Newest first, `page_size` lines at a time: 30 when unset, 100 at most.
  // `next_page_token` is empty on the last page. There is no total, because
  // the log only grows and counting it would scan it on every page turn.
```
with:
```proto
  // Newest first, `page_size` lines at a time: 30 when unset, and a larger
  // one than 100 is read as 100 (AIP-158). `next_page_token` is empty on the
  // last page. There is no total, because the log only grows and counting it
  // would scan it on every page turn. A page token is opaque: send back a
  // `next_page_token` unchanged. A line written between two page turns moves
  // nothing on the next page. A token this list did not hand out, or a
  // negative `page_size`, is VALIDATION_FAILED on that field.
```
Then, from the worktree root:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe lint && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe generate && git status --short server/app/contract
```
Expected:
- `buf lint` prints nothing.
- `buf generate` prints nothing.
- `git status` lists only `server/app/contract/lessons/v2/audit_*` files, because the comment is a docstring in the generated service.

Then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git fetch origin main && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe breaking --against ".git#ref=origin/main"
```
Expected: exit 0, nothing printed. A comment is not a breaking change.

- [ ] **Step 3: Create `server/app/rpc/audit.py`.**
```python
"""``AuditService``: «📜 Журнал», a page at a time. v1's ``GET /manage/log``.

v1 paged by ``limit`` and ``offset``; v2 by AIP-158's ``page_size`` and
``page_token``. The token is opaque to a client, and what it is here is plain:
the id of the last line a page served, so the next page is the lines after
that one (``journal.page_after``). A line written between two page turns
therefore moves nothing on the next page, which an offset would. A token this
list did not hand out — one that does not decode, has another prefix, or
names no line of this class's log — is ``VALIDATION_FAILED`` on
``page_token``, and the refusal never repeats it
(``docs/specs/2026-10-05-server-v2-3b-plan.md``, Ruling 4).
"""

from __future__ import annotations

import base64
import binascii
from typing import TYPE_CHECKING

from app.contract.lessons.v2.audit_pb import (
    AuditEntry,
    ListAuditEntriesRequest,
    ListAuditEntriesResponse,
)
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.rpc import values
from app.rpc.errors import Refusal
from app.services.manage import classes as classes_service
from app.services.manage import journal

if TYPE_CHECKING:
    from app.rpc.call import Call

#: Lines per page when the request names none: «📜 Журнал»'s own page, and v1's.
DEFAULT_PAGE_SIZE = 30
#: The most one page holds; a larger ``page_size`` is read as this (AIP-158).
MAX_PAGE_SIZE = 100

#: Fixed, and naming no value: a refusal never repeats what was sent.
PAGE_SIZE_REFUSED = "page_size must not be negative"
PAGE_TOKEN_REFUSED = "page_token is not one this list handed out"

#: What a token says before its line id, so that a token of another list can
#: never pass for one of this.
_PREFIX = b"audit:"
#: Longer than any token this list hands out; a longer one is refused unread.
_TOKEN_MAX = 64
#: The largest id an ``int32`` holds, which bounds every id this server assigns.
_ID_MAX = 2**31 - 1


def page_token(entry_id: int) -> str:
    """The token of the page after the line ``entry_id``: URL-safe base64, unpadded."""
    return base64.urlsafe_b64encode(_PREFIX + str(entry_id).encode()).rstrip(b"=").decode()


def _bad_token() -> Refusal:
    return Refusal(
        ErrorReason.VALIDATION_FAILED,
        PAGE_TOKEN_REFUSED,
        violations=[("page_token", PAGE_TOKEN_REFUSED)],
    )


def _entry_id(token: str) -> int:
    """The line ``token`` points after, or ``VALIDATION_FAILED`` on ``page_token``."""
    if len(token) > _TOKEN_MAX or not token.isascii():
        raise _bad_token()
    try:
        # Strict: the lenient decoder drops characters outside the alphabet,
        # so «audit:7» with a stray «!» would decode as a real token.
        raw = base64.b64decode(token + "=" * (-len(token) % 4), altchars=b"-_", validate=True)
    except (binascii.Error, ValueError):
        raise _bad_token() from None
    digits = raw.removeprefix(_PREFIX)
    if digits == raw or not digits.isdigit() or len(digits) > 10:
        raise _bad_token()
    entry_id = int(digits)
    if not 0 < entry_id <= _ID_MAX:
        raise _bad_token()
    return entry_id


async def list_audit_entries(
    call: Call, request: ListAuditEntriesRequest
) -> ListAuditEntriesResponse:
    """Who changed what, newest first, a page at a time. Writes nothing."""
    _admin, school_class = call.device_and_class()
    if request.page_size < 0:
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            PAGE_SIZE_REFUSED,
            violations=[("page_size", PAGE_SIZE_REFUSED)],
        )
    size = min(request.page_size or DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE)
    after_id: int | None = None
    if request.page_token:
        after_id = _entry_id(request.page_token)
        if not await journal.has_line(call.session, school_class.id, after_id):
            raise _bad_token()
    entries, more = await journal.page_after(
        call.session, school_class.id, limit=size, after_id=after_id
    )
    names = await classes_service.member_names(call.session, school_class.id)
    return ListAuditEntriesResponse(
        audit_entries=[
            AuditEntry(
                id=row.id,
                action=row.action,
                summary=row.summary,
                who=names.get(row.telegram_id) if row.telegram_id is not None else None,
                at=values.instant(row.created_at),
            )
            for row in entries
        ],
        next_page_token=page_token(entries[-1].id) if more else "",
    )
```

- [ ] **Step 4: Serve it.** Replace `server/app/rpc/handlers.py` whole with:
```python
"""Which methods this deployment serves, and the handler of each.

A method missing here answers ``UNIMPLEMENTED`` on both transports, before
any gate or scope, exactly as the generated ``Protocol``'s default does. 3a
served ``WatchClass``'s refusal, ``GetMe``, ``GetDiaryCapabilities``,
``CreateDevice`` and ``GetScheduleWindow``; 3b fills the rest in, one service
at a time (``docs/specs/2026-10-05-server-v2-3b-plan.md``).

Handler modules import ``Call`` only for their annotations, so that
``call.py``, which imports this table, is never imported back.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

from app.rpc import audit, device, diary, me, schedule, watch

#: A handler: ``async def handler(call: Call, request: <Method>Request) -> <Method>Response``.
Handler = Callable[[Any, Any], Awaitable[Any]]

#: Keyed as ``rpc.methods.METHODS`` is: ``"lessons.v2.<Service>/<Method>"``.
HANDLERS: dict[str, Handler] = {
    "lessons.v2.AuditService/ListAuditEntries": audit.list_audit_entries,
    "lessons.v2.DeviceService/CreateDevice": device.create_device,
    "lessons.v2.DiaryService/GetDiaryCapabilities": diary.get_diary_capabilities,
    "lessons.v2.MeService/GetMe": me.get_me,
    "lessons.v2.ScheduleService/GetScheduleWindow": schedule.get_schedule_window,
    "lessons.v2.WatchService/WatchClass": watch.watch_class,
}
```

- [ ] **Step 5: Green.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_v2_audit.py tests/test_v2_reads.py tests/test_v2_no_echo.py tests/test_rpc_errors.py tests/test_contract.py tests/test_rest.py tests/test_api_manage.py
```
Expected: all pass. `test_v2_audit.py` has 7. The gate test and the no-echo sweep each gain a `ListAuditEntries` case, and the sweep's `page_token` probe is refused with the fixed sentence.

- [ ] **Step 6: Gates.** ruff: `All checks passed!`. mypy: `Success: no issues found in 215 source files`. Then the full suite once. Expected: 2467 passed (2458 + 9).

- [ ] **Step 7: Commit.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3b1-t3.txt`:
```text
Serve the class's journal over v2, a page at a time by a token

ListAuditEntries is v1's /manage/log line for line, with each stamp an
instant rather than the class's wall time. v1's limit and offset become
AIP-158's page_size and page_token: 30 lines when unset, a larger size
than 100 read as 100, a negative one VALIDATION_FAILED on its field. The
token is opaque and plainly what it is, the last line a page served, so
a line written between two page turns moves nothing on the next page,
which an offset did. A token this list did not hand out, another class's
included, is VALIDATION_FAILED on page_token, in a sentence that does
not repeat it. audit.proto's comment says so; only the generated
docstring changed.

Tests ask it both ways and against v1's own answer, through 35 and 120
lines, across a line written between two pages, with eight bad tokens,
and through the statement listener: it writes nothing but the last seen.

Not covered: the keyset's SQL on Postgres; every test runs on SQLite.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add proto/lessons/v2/audit.proto server/app/contract server/app/rpc/audit.py server/app/rpc/handlers.py server/tests/test_v2_audit.py && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3b1-t3.txt
```

---

## Task 4: `ClassDeviceService`, and where each phone's version shows

Decisions 2, 5, 14 and 15; Rulings 2, 7, 9 and 12.

**Files:**
- Create: `server/app/rpc/class_device.py`, `server/tests/test_v2_class_devices.py`
- Modify:
  - `proto/lessons/v2/class_device.proto` and `server/app/contract/**` (regenerated);
  - `server/app/rpc/values.py`, `server/app/rpc/errors.py` and `server/app/rpc/handlers.py`;
  - `server/app/bot/manage_render/devices.py` and `docs/bot.md`;
  - `server/tests/test_rpc_errors.py`, `server/tests/test_contract_mirror.py` and `server/tests/test_bot_manage.py`.

**Interfaces:**
- Consumes:
  - Task 1's `classes.member_names` and `wording.UNKNOWN_DEVICE_DETAIL` / `CLASS_DEVICE_NOT_LINKED_DETAIL`;
  - Task 2's `DeviceToken.client_version`;
  - `linking.devices_of` and `linking.effective_role`;
  - `devices_service.device_of`, `owner_roles`, `revoke`, `unlink` and `DeviceNotLinked`.
- Produces:
  - `values.maybe_instant(moment: datetime | None) -> Timestamp | None`;
  - `class_device.list_class_devices`, `revoke_class_device` and `unlink_class_device`;
  - `ClassDevice.client_version` (field 10);
  - `errors.TABLE[devices_service.DeviceNotLinked]`;
  - `test_rpc_errors.STAGES`.

- [ ] **Step 1: Red.** Create `server/tests/test_v2_class_devices.py`:
```python
"""``ClassDeviceService``: v1's ``/manage/devices`` over v2, and each phone's app version.

The list is v1's list, phone for phone, with the stamps as instants instead of
the class's wall time; a revoke repeated changes nothing and logs nothing; an
unlink of a phone with nothing to unlink is refused in v1's words. What v2 adds
is ``client_version``, which v1's answer does not carry
(``docs/specs/2026-10-05-server-v2-3b-plan.md``, Rulings 7 and 12).
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import select

from app import wording
from app.contract.lessons.v2.class_device_pb import (
    ListClassDevicesRequest,
    RevokeClassDeviceRequest,
    UnlinkClassDeviceRequest,
)
from app.contract.lessons.v2.options_pb import Role as ProtoRole
from app.models import AuditEntry, DeviceToken, SchoolClass
from app.security import hash_token

LAST_SEEN = "UPDATE device_tokens SET last_seen_at=?"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _device(session, token: str) -> DeviceToken:
    device = await session.scalar(
        select(DeviceToken).where(DeviceToken.token_hash == hash_token(token))
    )
    await session.refresh(device)
    return device


async def _actions(session) -> list[str]:
    return list(await session.scalars(select(AuditEntry.action).order_by(AuditEntry.id)))


def _wall(message, field: str, school_class) -> datetime | None:
    """A v2 instant on the class's clock, as v1 writes it, or ``None`` when unset."""
    if not message.has_field(field):
        return None
    moment = getattr(message, field).to_datetime()
    return moment.astimezone(school_class.tz).replace(tzinfo=None)


def _role(name: str | None) -> ProtoRole:
    return ProtoRole[name.upper()] if name else ProtoRole.UNSPECIFIED


async def test_the_list_is_v1_s_list_in_v2_s_shape(v2, v2_tokens, school_class) -> None:
    admin = v2_tokens["admin"]
    v1 = (await v2.http.get("/api/v1/manage/devices", headers=_auth(admin))).json()
    answer = await v2.both("ClassDeviceService/ListClassDevices", token=admin)
    devices = answer.message.devices
    assert [device.id for device in devices] == [row["id"] for row in v1]
    for mine, theirs in zip(devices, v1, strict=True):
        assert mine.device_name == theirs["device_name"]
        assert mine.linked == theirs["linked"]
        assert (mine.owner if mine.has_field("owner") else None) == theirs["owner"]
        assert mine.role == _role(theirs["role"])
        assert mine.revoked == theirs["revoked"]
        for field in ("created_at", "last_seen_at", "linked_at"):
            expected = datetime.fromisoformat(theirs[field]) if theirs[field] else None
            assert _wall(mine, field, school_class) == expected, field
        # v1's answer keeps its shape (Ruling 7); nobody here has sent a version.
        assert "client_version" not in theirs
        assert not mine.has_field("client_version")
    owners = {d.device_name: d.owner for d in devices if d.has_field("owner")}
    assert owners == {
        "viewer phone": "2001",
        "editor phone": "2002",
        "admin phone": "2003",
        "owner phone": "2004",
    }


async def test_revoked_phones_come_back_only_when_asked(v2, v2_tokens, session) -> None:
    stranger = await _device(session, v2_tokens["stranger"])
    stranger.revoked = True
    await session.commit()
    admin = v2_tokens["admin"]
    plain = await v2.both("ClassDeviceService/ListClassDevices", token=admin)
    every = await v2.both(
        "ClassDeviceService/ListClassDevices",
        ListClassDevicesRequest(include_revoked=True),
        token=admin,
    )
    assert stranger.id not in [device.id for device in plain.message.devices]
    listed = {device.id: device.revoked for device in every.message.devices}
    assert listed[stranger.id] is True
    v1 = await v2.http.get(
        "/api/v1/manage/devices", params={"include_revoked": "true"}, headers=_auth(admin)
    )
    assert sorted(listed) == sorted(row["id"] for row in v1.json())


async def test_a_phone_s_app_version_shows_and_one_that_never_sent_it_shows_none(
    v2, v2_tokens
) -> None:
    await v2.rest(
        "MeService/GetMe", token=v2_tokens["viewer"], headers={"X-Lessons-Client": "412"}
    )
    answer = await v2.both("ClassDeviceService/ListClassDevices", token=v2_tokens["admin"])
    versions = {
        device.device_name: device.client_version if device.has_field("client_version") else None
        for device in answer.message.devices
    }
    assert versions.pop("viewer phone") == 412
    # The admin's own calls sent no header, and nobody else has called at all.
    assert set(versions.values()) == {None}


async def test_revoking_twice_is_one_switch_and_one_line(v2, v2_tokens, session) -> None:
    victim = await _device(session, v2_tokens["viewer"])
    # `both` sends it over REST and then over Connect: the second is a repeat,
    # and it must answer what the first did.
    answer = await v2.both(
        "ClassDeviceService/RevokeClassDevice",
        RevokeClassDeviceRequest(device_id=victim.id),
        token=v2_tokens["admin"],
    )
    device = answer.message.device
    assert (device.id, device.revoked, device.linked) == (victim.id, True, True)
    assert (device.owner, device.role) == ("2001", ProtoRole.VIEWER)
    assert await _actions(session) == ["device.revoke"]
    gone = await v2.both("MeService/GetMe", token=v2_tokens["viewer"])
    assert (gone.status, gone.reason) == (401, "DEVICE_TOKEN_INVALID")


async def test_an_admin_may_revoke_the_phone_in_their_hand(v2, v2_tokens, session) -> None:
    admin = v2_tokens["admin"]
    mine = await _device(session, admin)
    answer = await v2.rest(
        "ClassDeviceService/RevokeClassDevice",
        RevokeClassDeviceRequest(device_id=mine.id),
        token=admin,
    )
    assert answer.status == 200 and answer.message.device.revoked
    after = await v2.connect("ClassDeviceService/ListClassDevices", token=admin)
    assert (after.code, after.reason) == ("UNAUTHENTICATED", "DEVICE_TOKEN_INVALID")


async def test_unlinking_puts_a_phone_back_to_read_only(v2, v2_tokens, session) -> None:
    editor = await _device(session, v2_tokens["editor"])
    request = UnlinkClassDeviceRequest(device_id=editor.id)
    admin = v2_tokens["admin"]
    answer = await v2.rest("ClassDeviceService/UnlinkClassDevice", request, token=admin)
    device = answer.message.device
    assert (device.linked, device.role) == (False, ProtoRole.UNSPECIFIED)
    assert not device.has_field("owner") and not device.has_field("linked_at")
    me = await v2.both("MeService/GetMe", token=v2_tokens["editor"])
    assert (me.message.me.linked, me.message.me.can_edit) == (False, False)
    again = await v2.connect("ClassDeviceService/UnlinkClassDevice", request, token=admin)
    assert again.reason == "CLASS_DEVICE_NOT_LINKED"
    assert await _actions(session) == ["device.unlink"]


async def test_unlinking_a_phone_with_nothing_to_unlink_is_refused_in_v1_s_words(
    v2, v2_tokens, session
) -> None:
    phone = await _device(session, v2_tokens["unlinked"])
    admin = v2_tokens["admin"]
    v1 = await v2.http.post(f"/api/v1/manage/devices/{phone.id}/unlink", headers=_auth(admin))
    answer = await v2.both(
        "ClassDeviceService/UnlinkClassDevice",
        UnlinkClassDeviceRequest(device_id=phone.id),
        token=admin,
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "FAILED_PRECONDITION",
        "CLASS_DEVICE_NOT_LINKED",
    )
    assert answer.metadata == {}
    assert answer.error == v1.json()["detail"] == wording.CLASS_DEVICE_NOT_LINKED_DETAIL
    assert v1.status_code == 409
    assert await _actions(session) == []


async def test_a_phone_of_another_class_is_found_by_neither_write(
    v2, v2_tokens, session
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    stranger = DeviceToken(
        token_hash="x" * 64,
        class_id=other.id,
        device_name="чужой",
        telegram_id=2003,
        linked_at=datetime(2026, 9, 1),
    )
    session.add(stranger)
    await session.commit()
    for name, request in (
        ("RevokeClassDevice", RevokeClassDeviceRequest(device_id=stranger.id)),
        ("UnlinkClassDevice", UnlinkClassDeviceRequest(device_id=stranger.id)),
    ):
        answer = await v2.both(f"ClassDeviceService/{name}", request, token=v2_tokens["admin"])
        assert (answer.status, answer.reason, answer.metadata, answer.error) == (
            404,
            "RESOURCE_NOT_FOUND",
            {"resource": "device"},
            wording.UNKNOWN_DEVICE_DETAIL,
        ), name
    await session.refresh(stranger)
    assert (stranger.revoked, stranger.telegram_id) == (False, 2003)


async def test_the_device_list_writes_nothing_but_the_last_seen(
    v2, v2_tokens, statement_writes
) -> None:
    with statement_writes() as seen:
        answer = await v2.both(
            "ClassDeviceService/ListClassDevices",
            ListClassDevicesRequest(include_revoked=True),
            token=v2_tokens["admin"],
        )
    assert answer.status == 200
    assert len(answer.message.devices) == 6
    assert all(statement.startswith(LAST_SEEN) for statement in seen), seen
```
In `server/tests/test_bot_manage.py`, insert after `test_time_ago_reads_like_russian` (whose last line is `assert time_ago(datetime(2026, 1, 5, 9, 0), now) == "05.01.2026"`):
```python


def test_the_device_page_shows_the_build_a_phone_last_sent_and_nothing_if_none():
    from app.wording import MESSAGE_LIMIT

    def phone(n: int, name: str, build: int | None) -> SimpleNamespace:
        return SimpleNamespace(
            id=n, device_name=name, telegram_id=None, last_seen_at=None, client_version=build
        )

    lines = render_devices([phone(1, "Pixel 8", 412), phone(2, "Samsung A54", None)], {})
    rows = lines.splitlines()
    assert rows[2] == "📱 <b>Pixel 8</b> · не привязан · ещё не выходил на связь · сборка 412"
    assert rows[3] == "📱 <b>Samsung A54</b> · не привязан · ещё не выходил на связь"
    # A full page of the longest names and the largest build still fits one message.
    crowd = [phone(n, "Т" * 120, 2_100_000_000) for n in range(40)]
    assert len(render_devices(crowd, {})) <= MESSAGE_LIMIT
```
and in `test_no_list_page_draws_a_row_the_keyboard_cannot_reach`, give the page's devices the field the renderer now reads. Replace:
```python
    devices = [
        SimpleNamespace(
            id=n, device_name=f"Телефон {n}", telegram_id=None, last_seen_at=None
        )
        for n in range(1, count + 1)
    ]
```
with:
```python
    devices = [
        SimpleNamespace(
            id=n,
            device_name=f"Телефон {n}",
            telegram_id=None,
            last_seen_at=None,
            client_version=None,
        )
        for n in range(1, count + 1)
    ]
```
In `server/tests/test_rpc_errors.py`:
1. Below `from app.services import join, window`, add `from app.services.manage import devices as devices_service`.
2. Replace the whole `LATER = {…}` block, its comment included, with:
```python
#: The stages of 3b still to come. A stage leaves this set in the commit that
#: produces the last reason it brings, and a reason still listed under it in
#: ``LATER`` then fails below
#: (``docs/specs/2026-10-05-server-v2-3b-plan.md``, Ruling 2).
STAGES = {"3b-1", "3b-2", "3b-3", "3b-4", "3b-5", "3b-6", "3b-7", "3b-8"}

#: The reasons no served method produces yet, and the stage that brings each.
#: A reason leaves this table in the commit whose handler raises it.
LATER = {
    "RESOURCE_EXISTS": "3b-1",
    "NO_BELL_FOR_LESSON": "3b-6",
    "EMPTY_BELL_SCHEDULE": "3b-2",
    "DIARY_UNAVAILABLE": "3b-7",
    "DIARY_REAUTH": "3b-7",
    "DIARY_CREDENTIALS_REJECTED": "3b-7",
    "DIRECTORY_DISABLED": "3b-3",
    "DIRECTORY_SPENT": "3b-3",
    "DIRECTORY_UNAVAILABLE": "3b-3",
    "DIARY_NO_STUDENTS": "3b-7",
    "DIARY_UPSTREAM_UNREADABLE": "3b-7",
    "CORRECTIONS_UNAVAILABLE": "3b-8",
    "RESOURCE_IN_USE": "3b-1",
    "SUBJECT_RENAME_CLASH": "3b-1",
    "ROLE_GRANT_REFUSED": "3b-3",
    "TERM_BOUNDS_REFUSED": "3b-2",
    "NO_LESSON_ON_DAY": "3b-6",
    "LESSON_NOT_ON_TIMETABLE": "3b-6",
}
```
3. Replace the comment above `HELD_BY` and the dict's last two entries:
```python
#: Each row of ``errors.TABLE`` and the test that raises its exception through
#: a served method and reads the refusal back on both transports, as a file
#: under ``tests/`` and a function in it; or "3b", where no method 3a serves
#: can raise it. A row added to the table without either fails below.
```
   with:
```python
#: Each row of ``errors.TABLE`` and the test that raises its exception through
#: a served method and reads the refusal back on both transports, as a file
#: under ``tests/`` and a function in it; or the stage, one of ``STAGES``,
#: that will serve a method raising it. A row added to the table without
#: either fails below.
```
   and
```python
    # The gate raises it for a diary method, and 3a serves none:
    # test_rpc_gate.py holds the gate raising it until 3b does.
    diary_service.DiaryDisabled: "3b",
}
```
   with:
```python
    devices_service.DeviceNotLinked: (
        "test_v2_class_devices.py",
        "test_unlinking_a_phone_with_nothing_to_unlink_is_refused_in_v1_s_words",
    ),
    # The gate raises it for a diary method, and none is served before 3b-7:
    # test_rpc_gate.py holds the gate raising it until then.
    diary_service.DiaryDisabled: "3b-7",
}
```
4. In `test_every_reason_is_produced_or_waits_for_a_later_stage`, append the line `    assert set(LATER.values()) <= STAGES`.
5. In `test_every_row_of_the_table_names_the_test_that_reads_it_back`, replace:
```python
        if held == "3b":
            continue
```
   with:
```python
        if isinstance(held, str):
            assert held in STAGES, (exception.__name__, held)
            continue
```

Run:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_v2_class_devices.py tests/test_rpc_errors.py tests/test_bot_manage.py
```
Expected:
- `test_v2_class_devices.py` fails on `UNIMPLEMENTED`, and on a `ClassDevice` with no `client_version` field.
- `test_rpc_errors.py::test_every_row_of_the_table_names_the_test_that_reads_it_back` fails: `HELD_BY` names a row `TABLE` lacks.
- `test_rpc_errors.py::test_every_reason_is_produced_or_waits_for_a_later_stage` fails: `CLASS_DEVICE_NOT_LINKED` is produced by nothing and listed nowhere.
- The new bot test fails: the row has no «сборка».

- [ ] **Step 2: The field, and the contract regenerated.** In `proto/lessons/v2/class_device.proto`, after `  google.protobuf.Timestamp linked_at = 9;`, insert:
```proto
  // The X-Lessons-Client version this phone last sent to v2, recorded beside
  // last_seen_at and on its fifteen-minute clock; absent for a phone that
  // never sent one, which is every APK that speaks only v1. New in v2: v1's
  // answer does not carry it.
  optional int32 client_version = 10;
```
Then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe lint && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe generate && git status --short server/app/contract
```
Expected: lint and generate print nothing, and `git status` lists only `server/app/contract/lessons/v2/class_device_*` files. Then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git fetch origin main && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe breaking --against ".git#ref=origin/main"
```
Expected: exit 0, nothing printed. A new field under a new number passes the `FILE` rules.

In `server/tests/test_contract_mirror.py`, replace `    "ClassDevice": Mirror(("ManagedDeviceOut",)),` with:
```python
    "ClassDevice": Mirror(
        ("ManagedDeviceOut",),
        added={
            "client_version": (
                "proto comment on ClassDevice.client_version: new in v2, recorded by "
                "v2's gate only (server-v2 design, decision 15); v1's answer keeps its "
                "shape, because that design delivers no change to v1"
            )
        },
    ),
```

- [ ] **Step 3: `values.maybe_instant`.** In `server/app/rpc/values.py`, after `instant`, insert:
```python


def maybe_instant(moment: datetime | None) -> Timestamp | None:
    """:func:`instant`, or ``None`` — the field left unset — for a column that
    holds no moment: a phone never seen, never linked."""
    return instant(moment) if moment is not None else None
```

- [ ] **Step 4: The table's row.** In `server/app/rpc/errors.py`:
- Below `from app.services import join, window`, add `from app.services.manage import devices as devices_service`.
- After `_year_out_of_bounds`, insert:
```python


def _class_device_not_linked(_error: devices_service.DeviceNotLinked) -> Refusal:
    return Refusal(ErrorReason.CLASS_DEVICE_NOT_LINKED, wording.CLASS_DEVICE_NOT_LINKED_DETAIL)
```
- In `TABLE`, after `    window.YearOutOfBounds: _year_out_of_bounds,`, insert `    devices_service.DeviceNotLinked: _class_device_not_linked,`.

- [ ] **Step 5: Create `server/app/rpc/class_device.py`.**
```python
"""``ClassDeviceService``: the phones on the class, as «📱 Устройства» lists them.

v1's ``/manage/devices``, over the same services: ``linking.devices_of``,
``devices_service.owner_roles``, ``revoke`` and ``unlink``. A role is looked
up, never stored, so a phone revoked in the bot has changed this list already.
What v2 adds is ``client_version``, the app version a phone last sent to v2's
gate (``docs/specs/2026-10-05-server-v2-design.md``, decision 15), and every
stamp is an instant rather than the class's wall time.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app import wording
from app.contract.lessons.v2.class_device_pb import (
    ClassDevice,
    ListClassDevicesRequest,
    ListClassDevicesResponse,
    RevokeClassDeviceRequest,
    RevokeClassDeviceResponse,
    UnlinkClassDeviceRequest,
    UnlinkClassDeviceResponse,
)
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.models import DeviceToken, Role, SchoolClass
from app.rpc import values
from app.rpc.errors import Refusal
from app.services import linking
from app.services.manage import classes as classes_service
from app.services.manage import devices as devices_service

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.rpc.call import Call


def _message(device: DeviceToken, names: dict[int, str], role: Role | None) -> ClassDevice:
    return ClassDevice(
        id=device.id,
        device_name=device.device_name,
        linked=device.is_linked,
        # A display name, never the Telegram id behind it.
        owner=names.get(device.telegram_id) if device.telegram_id is not None else None,
        role=values.role(role),
        revoked=device.revoked,
        created_at=values.maybe_instant(device.created_at),
        last_seen_at=values.maybe_instant(device.last_seen_at),
        linked_at=values.maybe_instant(device.linked_at),
        client_version=device.client_version,
    )


async def _device(
    session: AsyncSession, school_class: SchoolClass, device_id: int
) -> DeviceToken:
    """This class's phone ``device_id``, revoked or not, or ``RESOURCE_NOT_FOUND``:
    an id of another class's phone finds nothing, as in v1."""
    device = await devices_service.device_of(session, school_class.id, device_id)
    if device is None:
        raise Refusal(
            ErrorReason.RESOURCE_NOT_FOUND, wording.UNKNOWN_DEVICE_DETAIL, resource="device"
        )
    return device


async def list_class_devices(
    call: Call, request: ListClassDevicesRequest
) -> ListClassDevicesResponse:
    """The phones on the class's list, oldest first; revoked ones only when asked."""
    _admin, school_class = call.device_and_class()
    devices = await linking.devices_of(
        call.session, school_class.id, include_revoked=request.include_revoked
    )
    names = await classes_service.member_names(call.session, school_class.id)
    # Once per owner, not once per phone, as v1's list and the bot's page do.
    roles = await devices_service.owner_roles(call.session, devices)
    return ListClassDevicesResponse(
        devices=[_message(device, names, roles.get(device.telegram_id)) for device in devices]
    )


async def revoke_class_device(
    call: Call, request: RevokeClassDeviceRequest
) -> RevokeClassDeviceResponse:
    """Switch a phone off for good: revoked, not deleted, so its token goes on failing.

    A repeat changes nothing, logs nothing and answers the revoked phone again,
    so a retried request lands on the first one's answer. An admin may revoke
    the phone in their hand, whose next call is ``DEVICE_TOKEN_INVALID``. The
    role is read before the revoke, as v1 reads it.
    """
    admin, school_class = call.device_and_class()
    device = await _device(call.session, school_class, request.device_id)
    names = await classes_service.member_names(call.session, school_class.id)
    role = await linking.effective_role(call.session, device)
    await devices_service.revoke(call.session, school_class.id, admin.telegram_id, device)
    return RevokeClassDeviceResponse(device=_message(device, names, role))


async def unlink_class_device(
    call: Call, request: UnlinkClassDeviceRequest
) -> UnlinkClassDeviceResponse:
    """Back to read-only, keeping the phone in the class. A phone no account is
    behind is ``CLASS_DEVICE_NOT_LINKED``: the admin expected something to change."""
    admin, school_class = call.device_and_class()
    device = await _device(call.session, school_class, request.device_id)
    await devices_service.unlink(call.session, school_class.id, admin.telegram_id, device)
    names = await classes_service.member_names(call.session, school_class.id)
    role = await linking.effective_role(call.session, device)
    return UnlinkClassDeviceResponse(device=_message(device, names, role))
```

- [ ] **Step 6: Serve them.** In `server/app/rpc/handlers.py`, replace everything from the line `from app.rpc import audit, device, diary, me, schedule, watch` to the end of the file with:
```python
from app.rpc import audit, class_device, device, diary, me, schedule, watch

#: A handler: ``async def handler(call: Call, request: <Method>Request) -> <Method>Response``.
Handler = Callable[[Any, Any], Awaitable[Any]]

#: Keyed as ``rpc.methods.METHODS`` is: ``"lessons.v2.<Service>/<Method>"``.
HANDLERS: dict[str, Handler] = {
    "lessons.v2.AuditService/ListAuditEntries": audit.list_audit_entries,
    "lessons.v2.ClassDeviceService/ListClassDevices": class_device.list_class_devices,
    "lessons.v2.ClassDeviceService/RevokeClassDevice": class_device.revoke_class_device,
    "lessons.v2.ClassDeviceService/UnlinkClassDevice": class_device.unlink_class_device,
    "lessons.v2.DeviceService/CreateDevice": device.create_device,
    "lessons.v2.DiaryService/GetDiaryCapabilities": diary.get_diary_capabilities,
    "lessons.v2.MeService/GetMe": me.get_me,
    "lessons.v2.ScheduleService/GetScheduleWindow": schedule.get_schedule_window,
    "lessons.v2.WatchService/WatchClass": watch.watch_class,
}
```

- [ ] **Step 7: The bot's «📱 Устройства» shows the build.** In `server/app/bot/manage_render/devices.py`:
- In `render_devices`'s docstring, replace `"""«📱 Pixel 8 · привязан: @user (Редактор) · был 2 ч назад».` with `"""«📱 Pixel 8 · привязан: @user (Редактор) · был 2 ч назад · сборка 412».`
- Replace `        lines.append(f"📱 <b>{name}</b> · {link} · {seen}")` with:
```python
        # The app's build, as the phone last sent it with a v2 request: which
        # phones still run an old APK before v1 is retired (the server-v2
        # design, decision 15). An integer from a column, so nothing to escape;
        # a phone that never sent one — every APK that speaks only v1 — says
        # nothing about a version.
        build = (
            f" · сборка {device.client_version}" if device.client_version is not None else ""
        )
        lines.append(f"📱 <b>{name}</b> · {link} · {seen}{build}")
```
The handler (`bot/handlers/manage/devices.py`) needs no change, because it hands the renderer `DeviceToken` rows, which carry the column.

In `docs/bot.md`, under «Devices — `/devices`»:
- Replace the example line `📱 Pixel 8 · привязан: @masha (Редактор) · был 2 ч назад` with `📱 Pixel 8 · привязан: @masha (Редактор) · был 2 ч назад · сборка 412`.
- Replace the paragraph's opening `A device token is read-only until its owner links it with` with:
```markdown
«сборка» is the app's build number, its versionCode, as the phone last sent it with a v2
request; it is recorded at most every fifteen minutes, beside «был …». A phone that never
sent one shows nothing about a version, and that is every APK that speaks only v1: the line
exists so that an admin can see which phones still run an old APK before v1 is retired.

A device token is read-only until its owner links it with
```

- [ ] **Step 8: Green.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_v2_class_devices.py tests/test_v2_reads.py tests/test_v2_no_echo.py tests/test_rpc_errors.py tests/test_contract.py tests/test_contract_mirror.py tests/test_contract_json.py tests/test_rest.py tests/test_bot_manage.py tests/test_api_manage.py tests/test_v2_client_version.py
```
Expected: all pass.
- `test_v2_class_devices.py` has 9.
- The gate test and the no-echo sweep each gain three cases.
- `test_bot_manage.py` gains 1.
- The mirror's `ClassDevice` row passes with its addition.

- [ ] **Step 9: Gates.** ruff: `All checks passed!`. mypy: `Success: no issues found in 216 source files`. Then the full suite once. Expected: 2483 passed (2467 + 16).

- [ ] **Step 10: Commit.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3b1-t4.txt`:
```text
Serve the class's phones over v2, and show each phone's app build

ListClassDevices, RevokeClassDevice and UnlinkClassDevice are v1's
/manage/devices over the same services, phone for phone, with each stamp
an instant. A revoke repeated answers the revoked phone again and logs
nothing, so a retried request lands on the first one's answer; an admin
may revoke the phone in their hand. Unlinking a phone no account is
behind is CLASS_DEVICE_NOT_LINKED, in v1's words.

ClassDevice gains client_version, a new optional field under a new
number, regenerated: the version a phone last sent to v2. The bot's
«📱 Устройства» shows it as «сборка N», and nothing for a phone that never
sent one. v1's answer keeps its shape, and the contract's mirror test
records the addition and why.

The error table's later reasons now name the stage of 3b that brings
each, and a stage takes itself out of STAGES when it is done, so a reason
it forgot to take out of LATER fails.

Not covered: no APK sends X-Lessons-Client yet, so no phone in production
shows a build.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add proto/lessons/v2/class_device.proto server/app/contract server/app/rpc/values.py server/app/rpc/errors.py server/app/rpc/class_device.py server/app/rpc/handlers.py server/app/bot/manage_render/devices.py docs/bot.md server/tests/test_v2_class_devices.py server/tests/test_rpc_errors.py server/tests/test_contract_mirror.py server/tests/test_bot_manage.py && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3b1-t4.txt
```

---

## Task 5: `ListSubjects` and `GetSubject`: the dictionary with ids, adopting nothing

Decisions 2, 10 and 14; Ruling 13.

**Files:**
- Create: `server/app/rpc/subject.py`, `server/tests/test_v2_subjects.py`
- Modify: `server/app/rpc/handlers.py`

**Interfaces:**
- Consumes:
  - Task 1's `subjects_service.dictionary_of` and `wording.UNKNOWN_SUBJECT_DETAIL`;
  - `subjects_service.subject_of`.
- Produces:
  - `subject.list_subjects` and `subject.get_subject`;
  - `subject._message(row) -> Subject` and `subject._row(session, class_id, subject_id) -> SubjectRow`, which Task 6 reuses.

- [ ] **Step 1: Red.** Create `server/tests/test_v2_subjects.py`:
```python
"""``SubjectService``'s reads: the dictionary with ids, for any phone in the class.

v2 has one subject resource where v1 had two: ``GET /subjects`` (no ids, any
phone, a plain read) and ``/manage/subjects`` (ids, an editor's, adopting the
timetable's names and committing them). The list is v1's list, and the read
writes nothing where v1's manage list adopts
(``docs/specs/2026-10-05-server-v2-design.md``, decision 10).
"""

from __future__ import annotations

from app import wording
from app.contract.lessons.v2.subject_pb import GetSubjectRequest, Subject
from app.models import SchoolClass
from app.models import Subject as SubjectRow

LAST_SEEN = "UPDATE device_tokens SET last_seen_at=?"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _subject(session, school_class, name: str, **details) -> SubjectRow:
    row = SubjectRow(class_id=school_class.id, name=name, **details)
    session.add(row)
    await session.commit()
    return row


def _plain(subject: Subject) -> dict[str, str | None]:
    """A v2 subject as v1's ``SubjectOut`` writes it: an unset field is ``null``."""
    return {
        "name": subject.name,
        **{
            field: getattr(subject, field) if subject.has_field(field) else None
            for field in ("short_name", "teacher", "color")
        },
    }


async def test_the_dictionary_is_v1_s_with_its_ids(v2, v2_tokens, session, school_class) -> None:
    physics = await _subject(session, school_class, "Физика", teacher="Петров", color="#111111")
    algebra = await _subject(session, school_class, "Алгебра", short_name="Алг")
    token = v2_tokens["unlinked"]
    answer = await v2.both("SubjectService/ListSubjects", token=token)
    subjects = list(answer.message.subjects)
    assert [subject.id for subject in subjects] == [algebra.id, physics.id]
    public = (await v2.http.get("/api/v1/subjects", headers=_auth(token))).json()
    assert [_plain(subject) for subject in subjects] == public


async def test_the_list_adopts_nothing_where_v1_s_manage_list_does(
    v2, v2_tokens, statement_writes
) -> None:
    """The fixture's Monday teaches three subjects the dictionary has never
    heard of. v1's manage list adopts them on read and commits; v2's read
    leaves the dictionary as it is and writes nothing but the last seen."""
    token = v2_tokens["editor"]
    with statement_writes() as seen:
        before = await v2.both("SubjectService/ListSubjects", token=token)
    assert before.status == 200
    assert list(before.message.subjects) == []
    assert all(statement.startswith(LAST_SEEN) for statement in seen), seen

    with statement_writes() as seen:
        v1 = await v2.http.get("/api/v1/manage/subjects", headers=_auth(token))
    assert any(statement.startswith("INSERT INTO subjects") for statement in seen)

    after = await v2.both("SubjectService/ListSubjects", token=token)
    assert [(subject.id, subject.name) for subject in after.message.subjects] == [
        (row["id"], row["name"]) for row in v1.json()
    ]


async def test_any_phone_in_the_class_may_read_it(v2, v2_tokens, session, school_class) -> None:
    await _subject(session, school_class, "Химия")
    for who in ("unlinked", "viewer", "stranger"):
        answer = await v2.both("SubjectService/ListSubjects", token=v2_tokens[who])
        assert [subject.name for subject in answer.message.subjects] == ["Химия"], who


async def test_one_subject_is_read_by_its_id(v2, v2_tokens, session, school_class) -> None:
    physics = await _subject(session, school_class, "Физика", short_name="Физ", color="#5B6ABF")
    answer = await v2.both(
        "SubjectService/GetSubject",
        GetSubjectRequest(subject_id=physics.id),
        token=v2_tokens["viewer"],
    )
    assert answer.message.subject == Subject(
        id=physics.id, name="Физика", short_name="Физ", color="#5B6ABF"
    )


async def test_an_id_that_names_no_subject_of_the_class_is_not_found(
    v2, v2_tokens, session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    stranger = SubjectRow(class_id=other.id, name="Биология")
    session.add(stranger)
    await session.commit()
    v1 = await v2.http.patch(
        f"/api/v1/manage/subjects/{stranger.id}",
        json={"name": "Ботаника"},
        headers=_auth(v2_tokens["admin"]),
    )
    for subject_id in (stranger.id, 999_999):
        answer = await v2.both(
            "SubjectService/GetSubject",
            GetSubjectRequest(subject_id=subject_id),
            token=v2_tokens["viewer"],
        )
        assert (answer.status, answer.code, answer.reason) == (
            404,
            "NOT_FOUND",
            "RESOURCE_NOT_FOUND",
        )
        assert answer.metadata == {"resource": "subject"}
        assert answer.error == v1.json()["detail"] == wording.UNKNOWN_SUBJECT_DETAIL


async def test_reading_one_subject_writes_nothing_but_the_last_seen(
    v2, v2_tokens, session, school_class, statement_writes
) -> None:
    physics = await _subject(session, school_class, "Физика")
    with statement_writes() as seen:
        answer = await v2.both(
            "SubjectService/GetSubject",
            GetSubjectRequest(subject_id=physics.id),
            token=v2_tokens["viewer"],
        )
    assert answer.status == 200
    assert answer.message.subject.name == "Физика"
    assert all(statement.startswith(LAST_SEEN) for statement in seen), seen
```
Run:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_v2_subjects.py
```
Expected: every test fails on `UNIMPLEMENTED`.

- [ ] **Step 2: Create `server/app/rpc/subject.py`** with the reads; Task 6 replaces the file with the writes added:
```python
"""``SubjectService``: the class's subject dictionary, as «📚 Предметы» edits it.

One resource with ids, readable by any phone in the class, where v1 had two:
``GET /subjects`` (no ids) and ``/manage/subjects`` (ids, an editor's). v2's
reads adopt nothing (``docs/specs/2026-10-05-server-v2-design.md``, decision
10): every write that names a subject in the weekly template links it
already, and v1's repair of rows written before the link existed stays in
v1's manage list and in the bot.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app import wording
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.subject_pb import (
    GetSubjectRequest,
    GetSubjectResponse,
    ListSubjectsRequest,
    ListSubjectsResponse,
    Subject,
)
from app.models import Subject as SubjectRow
from app.rpc.errors import Refusal
from app.services.manage import subjects as subjects_service

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.rpc.call import Call


def _message(row: SubjectRow) -> Subject:
    return Subject(
        id=row.id,
        name=row.name,
        short_name=row.short_name,
        teacher=row.teacher,
        color=row.color,
    )


async def _row(session: AsyncSession, class_id: int, subject_id: int) -> SubjectRow:
    """This class's subject ``subject_id``, or ``RESOURCE_NOT_FOUND``: an id of
    another class's subject finds nothing, as in v1."""
    row = await subjects_service.subject_of(session, class_id, subject_id)
    if row is None:
        raise Refusal(
            ErrorReason.RESOURCE_NOT_FOUND, wording.UNKNOWN_SUBJECT_DETAIL, resource="subject"
        )
    return row


async def list_subjects(call: Call, request: ListSubjectsRequest) -> ListSubjectsResponse:
    """The dictionary, alphabetically, as it stands. Writes nothing."""
    _device, school_class = call.device_and_class()
    rows = await subjects_service.dictionary_of(call.session, school_class.id)
    return ListSubjectsResponse(subjects=[_message(row) for row in rows])


async def get_subject(call: Call, request: GetSubjectRequest) -> GetSubjectResponse:
    """One entry of the dictionary, by its id."""
    _device, school_class = call.device_and_class()
    row = await _row(call.session, school_class.id, request.subject_id)
    return GetSubjectResponse(subject=_message(row))
```

- [ ] **Step 3: Serve them.** In `server/app/rpc/handlers.py`, replace `from app.rpc import audit, class_device, device, diary, me, schedule, watch` with `from app.rpc import audit, class_device, device, diary, me, schedule, subject, watch`. In `HANDLERS`, after the `ScheduleService/GetScheduleWindow` row, insert:
```python
    "lessons.v2.SubjectService/GetSubject": subject.get_subject,
    "lessons.v2.SubjectService/ListSubjects": subject.list_subjects,
```

- [ ] **Step 4: Green.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_v2_subjects.py tests/test_v2_reads.py tests/test_v2_no_echo.py tests/test_rpc_errors.py tests/test_api_manage.py tests/test_api_extended.py
```
Expected: all pass. `test_v2_subjects.py` has 6, and the gate test and the no-echo sweep each gain two cases.

- [ ] **Step 5: Gates.** ruff: `All checks passed!`. mypy: `Success: no issues found in 217 source files`. Then the full suite once. Expected: 2493 passed (2483 + 10).

- [ ] **Step 6: Commit.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3b1-t5.txt`:
```text
Serve the class's subject dictionary over v2, read without adopting

ListSubjects and GetSubject read the dictionary with its ids, any phone
in the class, the unlinked one included. The list is v1's: the same rows
as GET /subjects and, once v1 has adopted, as /manage/subjects. Unlike
v1's manage list it adopts nothing and writes nothing but the last seen
(the server-v2 design, decision 10); every write that names a subject in
the timetable links it already. An id of another class's subject, or of
none, is RESOURCE_NOT_FOUND in v1's words.

Not covered: the dictionary's writes, the next commit.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/rpc/subject.py server/app/rpc/handlers.py server/tests/test_v2_subjects.py && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3b1-t5.txt
```

---

## Task 6: `CreateSubject`, `UpdateSubject` and `DeleteSubject`, and one reading of a mask

Decisions 2, 5 and 14; Rulings 5, 6, 14, 15 and 17.

**Files:**
- Create: `server/app/rpc/masks.py`, `server/tests/test_rpc_masks.py`, `server/tests/test_v2_subject_writes.py`
- Modify: `server/app/rpc/subject.py` (replaced), `server/app/rpc/errors.py`, `server/app/rpc/handlers.py`, `server/tests/test_rpc_errors.py`, `server/tests/conftest.py`

**Interfaces:**
- Consumes:
  - Task 1's `subjects_service.update` and the wording;
  - Task 5's `_message` and `_row`;
  - `subjects_service.create`, `delete`, `SubjectExists`, `HomeworkClash` and `SubjectInUse`;
  - the `SubjectIn` and `SubjectPatch` schemas.
- Produces:
  - `masks.update_paths(mask: FieldMask | None, resource: Message | None, changeable: Sequence[str]) -> list[str]` and `masks.NOT_CHANGEABLE`;
  - `errors.validate(model, data, *, at: str = "")`;
  - `subject.create_subject`, `update_subject`, `delete_subject` and `subject.CHANGEABLE`;
  - three `TABLE` rows;
  - a harness, `v2.rest`, that builds the path of a dotted variable whose parent message is unset.

- [ ] **Step 1: Red.** Create `server/tests/test_rpc_masks.py`:
```python
"""One reading of an ``update_mask`` for every ``Update*`` method (AIP-134).

``docs/specs/2026-10-05-server-v2-3b-plan.md``, Ruling 5: an explicit mask is
taken as it is, a path the method does not change is refused without being
repeated, and no mask means every field the request sets.
"""

from __future__ import annotations

import pytest
from protobuf.wkt import FieldMask

from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.subject_pb import Subject
from app.rpc.errors import Refusal
from app.rpc.masks import NOT_CHANGEABLE, update_paths

CHANGEABLE = ("name", "short_name", "teacher", "color")


def test_no_mask_means_every_field_the_resource_sets() -> None:
    # An optional field set to "" is set: it clears, which is what was sent.
    sent = Subject(id=4, short_name="Физ", teacher="")
    assert update_paths(None, sent, CHANGEABLE) == ["short_name", "teacher"]
    assert update_paths(FieldMask(paths=[]), sent, CHANGEABLE) == ["short_name", "teacher"]
    assert update_paths(None, None, CHANGEABLE) == []


def test_a_mask_is_taken_as_it_is_in_the_method_s_order_once_each() -> None:
    mask = FieldMask(paths=["color", "name", "color"])
    assert update_paths(mask, Subject(), CHANGEABLE) == ["name", "color"]


@pytest.mark.parametrize("path", ["id", "*", "subject.name", "Pa55w0rd-s3cr3t-Hunter2"])
def test_a_path_the_method_does_not_change_is_refused_without_repeating_it(path) -> None:
    with pytest.raises(Refusal) as refused:
        update_paths(FieldMask(paths=["teacher", path]), Subject(), CHANGEABLE)
    assert refused.value.reason is ErrorReason.VALIDATION_FAILED
    assert refused.value.violations == (("update_mask", NOT_CHANGEABLE),)
    assert path not in refused.value.message
```
Create `server/tests/test_v2_subject_writes.py`:
```python
"""``SubjectService``'s writes: create, update by mask, delete, by v1's rules.

v1's ``/manage/subjects`` writes over v2, through the same services and v1's
own ``SubjectIn`` and ``SubjectPatch``, so the two versions refuse the same
requests in the same words: a name the class has in any case, a rename onto
a name with homework the same day, a subject the timetable still teaches.
What v2 adds is the mask (``docs/specs/2026-10-05-server-v2-3b-plan.md``,
Ruling 5). A write's success is asked once per transport on fresh data, and
its refusals through ``both`` (Ruling 17).
"""

from __future__ import annotations

from datetime import date

from protobuf.wkt import FieldMask
from sqlalchemy import select

from app import wording
from app.contract.lessons.v2.subject_pb import (
    CreateSubjectRequest,
    DeleteSubjectRequest,
    Subject,
    UpdateSubjectRequest,
)
from app.models import (
    AuditEntry,
    Homework,
    LessonOverride,
    OverrideAction,
    SchoolClass,
    TimetableEntry,
)
from app.models import Subject as SubjectRow
from app.rpc.masks import NOT_CHANGEABLE

MONDAY = date(2026, 9, 7)


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _subject(session, school_class, name: str, **details) -> SubjectRow:
    row = SubjectRow(class_id=school_class.id, name=name, **details)
    session.add(row)
    await session.commit()
    return row


async def _row(session, subject_id: int) -> SubjectRow | None:
    """The row as the database holds it now, or ``None`` once it is gone."""
    return await session.scalar(
        select(SubjectRow)
        .where(SubjectRow.id == subject_id)
        .execution_options(populate_existing=True)
    )


async def _actions(session) -> list[str]:
    return list(await session.scalars(select(AuditEntry.action).order_by(AuditEntry.id)))


async def _timetable_names(session, school_class) -> set[str]:
    return set(
        await session.scalars(
            select(TimetableEntry.subject_name).where(TimetableEntry.class_id == school_class.id)
        )
    )


def _rename(subject_id: int, name: str) -> UpdateSubjectRequest:
    return UpdateSubjectRequest(
        subject=Subject(id=subject_id, name=name), update_mask=FieldMask(paths=["name"])
    )


async def test_a_subject_is_created_on_either_path_and_rest_says_201(
    v2, v2_tokens, session
) -> None:
    admin = v2_tokens["admin"]
    rest = await v2.rest(
        "SubjectService/CreateSubject",
        CreateSubjectRequest(subject=Subject(name=" Химия ", teacher="Петров", color="5b6abf")),
        token=admin,
    )
    assert rest.status == 201
    made = rest.message.subject
    assert (made.name, made.teacher, made.color) == ("Химия", "Петров", "#5B6ABF")
    assert not made.has_field("short_name")
    stored = await session.scalar(select(SubjectRow.id).where(SubjectRow.name == "Химия"))
    assert made.id == stored
    # The id a client sends is ignored: the server assigns it.
    connect = await v2.connect(
        "SubjectService/CreateSubject",
        CreateSubjectRequest(subject=Subject(id=999, name="Биология")),
        token=admin,
    )
    assert connect.status == 200
    assert connect.message.subject.name == "Биология"
    assert connect.message.subject.id != 999
    assert await _actions(session) == ["subject.add", "subject.add"]


async def test_a_new_subject_is_cleaned_as_v1_cleans_it(v2, v2_tokens) -> None:
    admin = v2_tokens["admin"]
    v1 = await v2.http.post(
        "/api/v1/manage/subjects",
        json={"name": "Алгебра  и  начала", "short_name": " Алг ", "color": "#5b6abf"},
        headers=_auth(admin),
    )
    sent = Subject(name="Геометрия  и  черчение", short_name=" Геом ", color="#5b6abf")
    made = (
        await v2.rest(
            "SubjectService/CreateSubject", CreateSubjectRequest(subject=sent), token=admin
        )
    ).message.subject
    theirs = v1.json()["subject"]
    assert (theirs["name"], theirs["short_name"], theirs["color"]) == (
        "Алгебра и начала",
        "Алг",
        "#5B6ABF",
    )
    assert (made.name, made.short_name, made.color) == ("Геометрия и черчение", "Геом", "#5B6ABF")


async def test_a_name_the_class_has_in_any_case_is_refused_as_existing(
    v2, v2_tokens, session, school_class
) -> None:
    await _subject(session, school_class, "Химия")
    admin = v2_tokens["admin"]
    v1 = await v2.http.post(
        "/api/v1/manage/subjects", json={"name": "химия"}, headers=_auth(admin)
    )
    answer = await v2.both(
        "SubjectService/CreateSubject",
        CreateSubjectRequest(subject=Subject(name="ХИМИЯ")),
        token=admin,
    )
    assert (answer.status, answer.code, answer.reason) == (
        409,
        "ALREADY_EXISTS",
        "RESOURCE_EXISTS",
    )
    assert answer.metadata == {"resource": "subject", "field": "name"}
    assert answer.error == v1.json()["detail"] == wording.SUBJECT_EXISTS_DETAIL
    assert v1.status_code == 409
    assert await _actions(session) == []


async def test_a_subject_v1_would_refuse_is_refused_on_its_field(v2, v2_tokens) -> None:
    admin = v2_tokens["admin"]
    for sent, field in (
        ({"name": "   "}, "subject.name"),
        ({"name": "Х" * 121}, "subject.name"),
        ({"name": "Химия", "color": "зелёный"}, "subject.color"),
        ({"name": "Химия", "short_name": "Х" * 17}, "subject.short_name"),
    ):
        answer = await v2.both(
            "SubjectService/CreateSubject",
            CreateSubjectRequest(subject=Subject(**sent)),
            token=admin,
        )
        assert (answer.code, answer.reason) == ("INVALID_ARGUMENT", "VALIDATION_FAILED"), field
        assert [name for name, _ in answer.violations] == [field]
        v1 = await v2.http.post("/api/v1/manage/subjects", json=sent, headers=_auth(admin))
        assert v1.status_code == 422, field


async def test_a_rename_carries_the_timetable_homework_and_substitutions(
    v2, v2_tokens, session, school_class
) -> None:
    algebra = await _subject(session, school_class, "Алгебра")
    session.add_all(
        [
            Homework(
                class_id=school_class.id, due_date=MONDAY, subject_name="Алгебра", text="№ 12"
            ),
            LessonOverride(
                class_id=school_class.id,
                date=MONDAY,
                index=4,
                action=OverrideAction.REPLACE,
                subject_name="Алгебра",
            ),
        ]
    )
    await session.commit()
    answer = await v2.rest(
        "SubjectService/UpdateSubject",
        _rename(algebra.id, "Алгебра и начала анализа"),
        token=v2_tokens["admin"],
    )
    assert answer.status == 200
    # One timetable row of the fixture's Monday, one homework, one substitution.
    assert answer.message.moved == 3
    assert answer.message.subject.name == "Алгебра и начала анализа"
    assert "Алгебра" not in await _timetable_names(session, school_class)
    summaries = list(await session.scalars(select(AuditEntry.summary)))
    assert summaries == ["предмет «Алгебра» → «Алгебра и начала анализа», строк обновлено: 3"]


async def test_an_update_without_a_mask_changes_only_what_it_sends(
    v2, v2_tokens, session, school_class
) -> None:
    physics = await _subject(session, school_class, "Физика", teacher="Петров", color="#111111")
    answer = await v2.rest(
        "SubjectService/UpdateSubject",
        UpdateSubjectRequest(subject=Subject(id=physics.id, short_name="Физ")),
        token=v2_tokens["admin"],
    )
    assert answer.status == 200
    made = answer.message.subject
    assert (made.name, made.short_name, made.teacher, made.color) == (
        "Физика",
        "Физ",
        "Петров",
        "#111111",
    )
    assert answer.message.moved == 0
    assert await _actions(session) == ["subject.short_name"]


async def test_a_masked_field_left_out_is_cleared(v2, v2_tokens, session, school_class) -> None:
    physics = await _subject(
        session, school_class, "Физика", short_name="Физ", teacher="Петров", color="#111111"
    )
    answer = await v2.connect(
        "SubjectService/UpdateSubject",
        UpdateSubjectRequest(
            subject=Subject(id=physics.id), update_mask=FieldMask(paths=["teacher", "color"])
        ),
        token=v2_tokens["admin"],
    )
    assert answer.status == 200
    made = answer.message.subject
    assert made.short_name == "Физ"
    assert not made.has_field("teacher") and not made.has_field("color")
    assert await _actions(session) == ["subject.teacher", "subject.colour"]
    public = (await v2.http.get("/api/v1/subjects", headers=_auth(v2_tokens["admin"]))).json()
    assert public == [{"name": "Физика", "short_name": "Физ", "teacher": None, "color": None}]


async def test_a_mask_naming_a_field_the_method_does_not_change_is_refused_on_it(
    v2, v2_tokens, session, school_class
) -> None:
    physics = await _subject(session, school_class, "Физика")
    for paths in (["id"], ["teacher", "class_id"], ["*"]):
        answer = await v2.both(
            "SubjectService/UpdateSubject",
            UpdateSubjectRequest(
                subject=Subject(id=physics.id, teacher="Петров"),
                update_mask=FieldMask(paths=paths),
            ),
            token=v2_tokens["admin"],
        )
        assert (answer.status, answer.reason) == (400, "VALIDATION_FAILED"), paths
        assert answer.violations == [("update_mask", NOT_CHANGEABLE)]
    assert await _actions(session) == []


async def test_a_masked_name_left_out_is_refused_rather_than_blanked(
    v2, v2_tokens, session, school_class
) -> None:
    physics = await _subject(session, school_class, "Физика")
    answer = await v2.both(
        "SubjectService/UpdateSubject",
        UpdateSubjectRequest(
            subject=Subject(id=physics.id), update_mask=FieldMask(paths=["name"])
        ),
        token=v2_tokens["admin"],
    )
    assert answer.reason == "VALIDATION_FAILED"
    assert [field for field, _ in answer.violations] == ["subject.name"]
    assert (await _row(session, physics.id)).name == "Физика"


async def test_a_rename_onto_a_name_taken_in_another_case_moves_nothing(
    v2, v2_tokens, session, school_class
) -> None:
    algebra = await _subject(session, school_class, "Алгебра")
    await _subject(session, school_class, "Геометрия")
    admin = v2_tokens["admin"]
    v1 = await v2.http.patch(
        f"/api/v1/manage/subjects/{algebra.id}", json={"name": "геометрия"}, headers=_auth(admin)
    )
    answer = await v2.both(
        "SubjectService/UpdateSubject", _rename(algebra.id, "ГЕОМЕТРИЯ"), token=admin
    )
    assert (answer.status, answer.code, answer.reason) == (
        409,
        "ALREADY_EXISTS",
        "RESOURCE_EXISTS",
    )
    assert answer.metadata == {"resource": "subject", "field": "name"}
    assert answer.error == v1.json()["detail"] == wording.SUBJECT_EXISTS_DETAIL
    assert (await _row(session, algebra.id)).name == "Алгебра"
    assert "Алгебра" in await _timetable_names(session, school_class)
    assert await _actions(session) == []


async def test_a_rename_onto_a_name_with_homework_the_same_day_is_a_clash(
    v2, v2_tokens, session, school_class
) -> None:
    algebra = await _subject(session, school_class, "Алгебра")
    session.add_all(
        [
            Homework(class_id=school_class.id, due_date=MONDAY, subject_name=name, text="п.1")
            for name in ("Алгебра", "Матан")
        ]
    )
    await session.commit()
    admin = v2_tokens["admin"]
    v1 = await v2.http.patch(
        f"/api/v1/manage/subjects/{algebra.id}", json={"name": "Матан"}, headers=_auth(admin)
    )
    answer = await v2.both(
        "SubjectService/UpdateSubject", _rename(algebra.id, "Матан"), token=admin
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "FAILED_PRECONDITION",
        "SUBJECT_RENAME_CLASH",
    )
    assert answer.metadata == {"dates": "2026-09-07"}
    assert answer.error == v1.json()["detail"] == wording.subject_rename_clash_detail([MONDAY])
    assert v1.status_code == 409
    assert (await _row(session, algebra.id)).name == "Алгебра"


async def test_a_subject_the_timetable_teaches_is_refused_as_in_use(
    v2, v2_tokens, session, school_class
) -> None:
    algebra = await _subject(session, school_class, "Алгебра")
    admin = v2_tokens["admin"]
    v1 = await v2.http.delete(f"/api/v1/manage/subjects/{algebra.id}", headers=_auth(admin))
    answer = await v2.both(
        "SubjectService/DeleteSubject", DeleteSubjectRequest(subject_id=algebra.id), token=admin
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "FAILED_PRECONDITION",
        "RESOURCE_IN_USE",
    )
    # The fixture's Monday teaches it once.
    assert answer.metadata == {"resource": "subject", "used_by": "lessons", "count": "1"}
    assert answer.error == v1.json()["detail"] == wording.subject_in_use_detail(1)
    assert v1.status_code == 409
    assert await _row(session, algebra.id) is not None


async def test_a_subject_nothing_teaches_is_deleted_on_either_path(
    v2, v2_tokens, session, school_class
) -> None:
    astronomy = await _subject(session, school_class, "Астрономия")
    biology = await _subject(session, school_class, "Биология")
    admin = v2_tokens["admin"]
    rest = await v2.rest(
        "SubjectService/DeleteSubject", DeleteSubjectRequest(subject_id=astronomy.id), token=admin
    )
    connect = await v2.connect(
        "SubjectService/DeleteSubject", DeleteSubjectRequest(subject_id=biology.id), token=admin
    )
    assert (rest.status, connect.status) == (200, 200)
    assert await _row(session, astronomy.id) is None
    assert await _row(session, biology.id) is None
    assert await _actions(session) == ["subject.delete", "subject.delete"]
    again = await v2.both(
        "SubjectService/DeleteSubject", DeleteSubjectRequest(subject_id=astronomy.id), token=admin
    )
    assert (again.status, again.reason) == (404, "RESOURCE_NOT_FOUND")


async def test_another_class_s_subject_is_found_by_no_write(
    v2, v2_tokens, session, school_class
) -> None:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    stranger = SubjectRow(class_id=other.id, name="Биология")
    session.add(stranger)
    await session.commit()
    for name, request in (
        ("UpdateSubject", _rename(stranger.id, "Ботаника")),
        ("DeleteSubject", DeleteSubjectRequest(subject_id=stranger.id)),
    ):
        answer = await v2.both(f"SubjectService/{name}", request, token=v2_tokens["admin"])
        assert (answer.status, answer.reason, answer.metadata, answer.error) == (
            404,
            "RESOURCE_NOT_FOUND",
            {"resource": "subject"},
            wording.UNKNOWN_SUBJECT_DETAIL,
        ), name
    assert (await _row(session, stranger.id)).name == "Биология"
```
In `server/tests/test_rpc_errors.py`:
1. Replace `from app.schemas import JoinRequest` with `from app.schemas import JoinRequest, SubjectIn`, and below `from app.services.manage import devices as devices_service` add `from app.services.manage import subjects as subjects_service`.
2. Replace `STAGES = {"3b-1", "3b-2", "3b-3", "3b-4", "3b-5", "3b-6", "3b-7", "3b-8"}` with `STAGES = {"3b-2", "3b-3", "3b-4", "3b-5", "3b-6", "3b-7", "3b-8"}`.
3. From `LATER`, delete the three lines `    "RESOURCE_EXISTS": "3b-1",`, `    "RESOURCE_IN_USE": "3b-1",` and `    "SUBJECT_RENAME_CLASH": "3b-1",`.
4. In `HELD_BY`, after the `devices_service.DeviceNotLinked` row, insert:
```python
    subjects_service.SubjectExists: (
        "test_v2_subject_writes.py",
        "test_a_name_the_class_has_in_any_case_is_refused_as_existing",
    ),
    subjects_service.HomeworkClash: (
        "test_v2_subject_writes.py",
        "test_a_rename_onto_a_name_with_homework_the_same_day_is_a_clash",
    ),
    subjects_service.SubjectInUse: (
        "test_v2_subject_writes.py",
        "test_a_subject_the_timetable_teaches_is_refused_as_in_use",
    ),
```
5. After `test_validation_names_the_field_and_never_the_value`, insert:
```python


def test_validation_names_a_nested_field_by_its_request_path() -> None:
    """``CreateSubject`` validates its ``subject`` with v1's ``SubjectIn``; a
    violation names ``subject.name``, the field as the request spells it."""
    try:
        validate(SubjectIn, {"name": "   "}, at="subject.")
    except Refusal as refusal:
        assert [field for field, _ in refusal.violations] == ["subject.name"]
        assert refusal.message == "invalid request field: subject.name"
    else:
        raise AssertionError("a blank name was accepted")
```
In `server/tests/conftest.py`, `_V2.rest` must build the path of `UpdateSubject`'s `{subject.id}` when the request carries no `subject`, which is what the gate test sends. This is the first dotted path variable any stage serves, and without the change the gate test fails in the harness with `AttributeError: 'NoneType' object has no attribute 'desc'`, which the plan's scratch run met.
1. Replace the two local imports at the top of `rest`:
```python
        from urllib.parse import quote, urlencode

        from protobuf import message_to_json_value
```
   with:
```python
        from urllib.parse import quote, urlencode

        from protobuf import message_to_json_value

        from app.rpc.methods import message_class
```
2. In the loop over `binding.variables`, replace:
```python
                field_desc = next(f for f in leaf.desc().fields if f.name == part)
                leaf = leaf[field_desc]
```
   with:
```python
                field_desc = next(f for f in leaf.desc().fields if f.name == part)
                nested = leaf[field_desc]
                if nested is None:
                    # An unset message on the way to a path variable, as in an
                    # empty `UpdateSubjectRequest`: the variable is its field's
                    # default, the URL a client writes from an empty resource.
                    nested = message_class(field_desc.value.message)()
                leaf = nested
```
   (`message_class` reads a top-level message's class from its descriptor, and every resource a path names is top-level in its file.)

Run:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_rpc_masks.py tests/test_v2_subject_writes.py tests/test_rpc_errors.py
```
Expected:
- `test_rpc_masks.py` fails at collection: no `app.rpc.masks`.
- `test_v2_subject_writes.py` fails at collection the same way, because it imports `NOT_CHANGEABLE`.
- In `test_rpc_errors.py`, the `HELD_BY` test fails on rows `TABLE` lacks, the stage test on three reasons nothing produces, and the new test on `validate`'s unknown keyword `at`.

- [ ] **Step 2: `validate` names a nested field by its path.** In `server/app/rpc/errors.py`, replace the whole of `validate`:
```python
def validate(model: type[M], data: Mapping[str, object]) -> M:
    """``model`` validated from ``data``, or ``VALIDATION_FAILED`` naming each
    field — and never the value, which pydantic keeps under ``input``.

    A handler validates with v1's own schema where one exists, so v1 and v2
    refuse the same requests; only the words differ. pydantic's own messages
    never quote the value; a custom validator's are its author's words, so a
    ``ValueError`` raised in one must not quote it either.
    """
    try:
        return model.model_validate(dict(data))
    except ValidationError as failure:
        violations = [
            (".".join(str(part) for part in error["loc"]), error["msg"])
            for error in failure.errors(include_input=False, include_url=False)
        ]
```
with:
```python
def validate(model: type[M], data: Mapping[str, object], *, at: str = "") -> M:
    """``model`` validated from ``data``, or ``VALIDATION_FAILED`` naming each
    field — and never the value, which pydantic keeps under ``input``.

    A handler validates with v1's own schema where one exists, so v1 and v2
    refuse the same requests; only the words differ. pydantic's own messages
    never quote the value; a custom validator's are its author's words, so a
    ``ValueError`` raised in one must not quote it either. ``at`` is where
    ``data`` sits in the request — ``"subject."`` for ``CreateSubject``'s
    ``subject`` — so that each violation names the field as the request spells
    it.
    """
    try:
        return model.model_validate(dict(data))
    except ValidationError as failure:
        violations = [
            (at + ".".join(str(part) for part in error["loc"]), error["msg"])
            for error in failure.errors(include_input=False, include_url=False)
        ]
```
(The rest of the function, the `fields` line and the `raise Refusal(…)`, stays as it is.)

- [ ] **Step 3: The table's three rows.** In `server/app/rpc/errors.py`:
- Below `from app.services.manage import devices as devices_service`, add `from app.services.manage import subjects as subjects_service`.
- After `_class_device_not_linked`, insert:
```python


def _subject_exists(_error: subjects_service.SubjectExists) -> Refusal:
    return Refusal(
        ErrorReason.RESOURCE_EXISTS,
        wording.SUBJECT_EXISTS_DETAIL,
        resource="subject",
        field="name",
    )


def _subject_rename_clash(error: subjects_service.HomeworkClash) -> Refusal:
    return Refusal(
        ErrorReason.SUBJECT_RENAME_CLASH,
        wording.subject_rename_clash_detail(error.days),
        dates=",".join(day.isoformat() for day in error.days),
    )


def _subject_in_use(error: subjects_service.SubjectInUse) -> Refusal:
    return Refusal(
        ErrorReason.RESOURCE_IN_USE,
        wording.subject_in_use_detail(error.lessons),
        resource="subject",
        used_by="lessons",
        count=error.lessons,
    )
```
- In `TABLE`, after `    devices_service.DeviceNotLinked: _class_device_not_linked,`, insert:
```python
    subjects_service.SubjectExists: _subject_exists,
    subjects_service.HomeworkClash: _subject_rename_clash,
    subjects_service.SubjectInUse: _subject_in_use,
```

- [ ] **Step 4: Create `server/app/rpc/masks.py`.**
```python
"""One reading of an ``update_mask``, for every ``Update*`` method (AIP-134).

Each update method names the fields its mask takes, and the proto comments
state what a masked field left unset means: it is cleared. What they leave to
the server is a request with no mask at all, which AIP-134 answers and v1's
``PATCH`` already did: change what the request sets. Written once here, so
that 3b's seven update methods cannot read a mask seven ways
(``docs/specs/2026-10-05-server-v2-3b-plan.md``, Ruling 5).
"""

from __future__ import annotations

from collections.abc import Sequence

from protobuf import Message
from protobuf.wkt import FieldMask

from app.contract.lessons.v2.errors_pb import ErrorReason
from app.rpc.errors import Refusal

#: Fixed, and naming no path: a path is the client's own text, and a refusal
#: never repeats what was sent.
NOT_CHANGEABLE = "update_mask names a field this method does not change"


def update_paths(
    mask: FieldMask | None, resource: Message | None, changeable: Sequence[str]
) -> list[str]:
    """The fields of ``resource`` an ``Update*`` request changes, in ``changeable``'s order.

    - A mask with paths is taken as it is. Every path must be one of
      ``changeable``, or the request is ``VALIDATION_FAILED`` on
      ``update_mask``; ``*`` is not taken. A masked path whose field
      ``resource`` leaves unset clears that field, as the proto comments say.
    - No mask, or one without paths, means every changeable field
      ``resource`` sets: AIP-134's implied mask, and v1's ``PATCH``, where
      only the fields present change.

    Each field comes once, in the method's order, whatever order the mask
    named them in, so a change that can be refused runs before the others.
    """
    if mask is None or not mask.paths:
        if resource is None:
            return []
        return [name for name in changeable if resource.has_field(name)]
    named = set(mask.paths)
    if not named <= set(changeable):
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            NOT_CHANGEABLE,
            violations=[("update_mask", NOT_CHANGEABLE)],
        )
    return [name for name in changeable if name in named]
```

- [ ] **Step 5: Replace `server/app/rpc/subject.py` whole**, adding the writes:
```python
"""``SubjectService``: the class's subject dictionary, as «📚 Предметы» edits it.

One resource with ids, readable by any phone in the class, where v1 had two:
``GET /subjects`` (no ids) and ``/manage/subjects`` (ids, an editor's). v2's
reads adopt nothing (``docs/specs/2026-10-05-server-v2-design.md``, decision
10): every write that names a subject in the weekly template links it
already, and v1's repair of rows written before the link existed stays in
v1's manage list and in the bot.

The writes are v1's, through the same services and v1's own schemas, so the
two versions refuse the same requests: a name the class has in any case is
``RESOURCE_EXISTS``, a rename onto a name with homework the same day
``SUBJECT_RENAME_CLASH``, and a subject the timetable teaches
``RESOURCE_IN_USE``, each worded by ``rpc/errors.py`` in v1's words.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app import wording
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.subject_pb import (
    CreateSubjectRequest,
    CreateSubjectResponse,
    DeleteSubjectRequest,
    DeleteSubjectResponse,
    GetSubjectRequest,
    GetSubjectResponse,
    ListSubjectsRequest,
    ListSubjectsResponse,
    Subject,
    UpdateSubjectRequest,
    UpdateSubjectResponse,
)
from app.models import Subject as SubjectRow
from app.rpc.errors import Refusal, validate
from app.rpc.masks import update_paths
from app.schemas import SubjectIn, SubjectPatch
from app.services.manage import subjects as subjects_service

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.rpc.call import Call

#: What ``update_mask`` takes, in the order a patch is applied: the rename
#: first, because it is the change that can be refused, then each detail
#: (``subjects_service.update``).
CHANGEABLE = ("name", "short_name", "teacher", "color")

#: The ``optional`` fields of ``Subject``: unset reads as ``""``, and means none.
_OPTIONAL = frozenset({"short_name", "teacher", "color"})


def _message(row: SubjectRow) -> Subject:
    return Subject(
        id=row.id,
        name=row.name,
        short_name=row.short_name,
        teacher=row.teacher,
        color=row.color,
    )


def _sent(subject: Subject, field: str) -> str | None:
    """What the request says of ``field``: ``None`` for an optional one it
    leaves unset, since protobuf-py reads an unset string as ``""``."""
    if field in _OPTIONAL and not subject.has_field(field):
        return None
    return getattr(subject, field)


async def _row(session: AsyncSession, class_id: int, subject_id: int) -> SubjectRow:
    """This class's subject ``subject_id``, or ``RESOURCE_NOT_FOUND``: an id of
    another class's subject finds nothing, as in v1."""
    row = await subjects_service.subject_of(session, class_id, subject_id)
    if row is None:
        raise Refusal(
            ErrorReason.RESOURCE_NOT_FOUND, wording.UNKNOWN_SUBJECT_DETAIL, resource="subject"
        )
    return row


async def list_subjects(call: Call, request: ListSubjectsRequest) -> ListSubjectsResponse:
    """The dictionary, alphabetically, as it stands. Writes nothing."""
    _device, school_class = call.device_and_class()
    rows = await subjects_service.dictionary_of(call.session, school_class.id)
    return ListSubjectsResponse(subjects=[_message(row) for row in rows])


async def get_subject(call: Call, request: GetSubjectRequest) -> GetSubjectResponse:
    """One entry of the dictionary, by its id."""
    _device, school_class = call.device_and_class()
    row = await _row(call.session, school_class.id, request.subject_id)
    return GetSubjectResponse(subject=_message(row))


async def create_subject(call: Call, request: CreateSubjectRequest) -> CreateSubjectResponse:
    """Add a subject, cleaned and checked by v1's ``SubjectIn``. The id a client
    sends is ignored; REST answers 201."""
    admin, school_class = call.device_and_class()
    sent = request.subject if request.subject is not None else Subject()
    form = validate(SubjectIn, {field: _sent(sent, field) for field in CHANGEABLE}, at="subject.")
    row = await subjects_service.create(
        call.session,
        school_class.id,
        admin.telegram_id,
        form.name,
        short_name=form.short_name,
        teacher=form.teacher,
        color=form.color,
    )
    # The id is the database's: flushed here, committed by `invoke`.
    await call.session.flush()
    return CreateSubjectResponse(subject=_message(row))


async def update_subject(call: Call, request: UpdateSubjectRequest) -> UpdateSubjectResponse:
    """Rename a subject, or set or clear its short name, teacher or colour.

    The mask is read once, by ``masks.update_paths``; the fields it names are
    cleaned and checked by v1's ``SubjectPatch``, then applied by the patch v1
    applies (``subjects_service.update``): the rename first, carrying the
    timetable, the homework and the substitutions with it, then each detail,
    one log line each. ``moved`` says how many rows the rename carried.
    """
    admin, school_class = call.device_and_class()
    sent = request.subject if request.subject is not None else Subject()
    paths = update_paths(request.update_mask, request.subject, CHANGEABLE)
    patch = validate(SubjectPatch, {field: _sent(sent, field) for field in paths}, at="subject.")
    row = await _row(call.session, school_class.id, sent.id)
    moved = await subjects_service.update(
        call.session,
        school_class.id,
        admin.telegram_id,
        row,
        patch.model_dump(exclude_unset=True),
    )
    return UpdateSubjectResponse(subject=_message(row), moved=moved)


async def delete_subject(call: Call, request: DeleteSubjectRequest) -> DeleteSubjectResponse:
    """Take a subject out of the dictionary, once nothing in the weekly template
    teaches it: out of the timetable first, out of the dictionary second."""
    admin, school_class = call.device_and_class()
    row = await _row(call.session, school_class.id, request.subject_id)
    await subjects_service.delete(call.session, school_class.id, admin.telegram_id, row)
    return DeleteSubjectResponse()
```

- [ ] **Step 6: Serve them.** In `server/app/rpc/handlers.py`'s `HANDLERS`, replace the two `SubjectService` rows with the five, in this order:
```python
    "lessons.v2.SubjectService/CreateSubject": subject.create_subject,
    "lessons.v2.SubjectService/DeleteSubject": subject.delete_subject,
    "lessons.v2.SubjectService/GetSubject": subject.get_subject,
    "lessons.v2.SubjectService/ListSubjects": subject.list_subjects,
    "lessons.v2.SubjectService/UpdateSubject": subject.update_subject,
```

- [ ] **Step 7: Green.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_rpc_masks.py tests/test_v2_subject_writes.py tests/test_v2_subjects.py tests/test_rpc_errors.py tests/test_v2_reads.py tests/test_v2_no_echo.py tests/test_rest.py tests/test_api_manage.py tests/test_service_layering.py
```
Expected: all pass.
- `test_rpc_masks.py` has 6.
- `test_v2_subject_writes.py` has 14.
- `test_rpc_errors.py` gains 1.
- The gate test and the no-echo sweep each gain three cases.
- `test_rest.py`'s 201 test, which puts a stand-in `CreateSubject` into `HANDLERS` for its run, still passes.

- [ ] **Step 8: Gates.** ruff: `All checks passed!`. mypy: `Success: no issues found in 218 source files`. Then the full suite once. Expected: 2520 passed (2493 + 27).

- [ ] **Step 9: Commit.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3b1-t6.txt`:
```text
Serve the subject dictionary's writes over v2, by v1's rules and a mask

CreateSubject, UpdateSubject and DeleteSubject go through the services
and the schemas v1's /manage/subjects uses, so both versions refuse the
same requests in the same words: a name the class has in any case is
RESOURCE_EXISTS, a rename onto a name with homework the same day
SUBJECT_RENAME_CLASH with the dates, a subject the timetable teaches
RESOURCE_IN_USE with the count. Each is a row of the error table, read
back on both paths by a named test. A violation names the field as the
request spells it, subject.name.

rpc/masks.py reads an update_mask once, for every Update* of 3b: an
explicit mask is taken as it is and a masked field left unset is
cleared, as the proto comments say; a path the method does not change is
VALIDATION_FAILED without being repeated; no mask changes what the
request sets, which is AIP-134 and v1's PATCH. The fields apply in the
method's order, so the rename that can be refused runs first. The test
harness now builds the REST path of a dotted variable, {subject.id},
when the request carries no subject, as the gate test's empty request
does.

Not covered: the other six update methods use the mask in their stages.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/rpc/masks.py server/app/rpc/subject.py server/app/rpc/errors.py server/app/rpc/handlers.py server/tests/test_rpc_masks.py server/tests/test_v2_subject_writes.py server/tests/test_rpc_errors.py server/tests/conftest.py && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3b1-t6.txt
```

---

## Task 7: The documents, the counts, and the HANDOVER close-out

**Files:**
- Modify: `docs/api.md`, `docs/README.md`, `docs/architecture.md`, `CLAUDE.md`, `README.md`, `CONTRIBUTING.md`, `.claude/skills/gates/SKILL.md`, `.claude/agents/server-tests.md`, `HANDOVER.md`, `docs/history.md`
- Scratch, never committed: `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\counts3b1.py`, `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\issue-3b1-vercel.md`

**Interfaces:**
- Consumes:
  - Tasks 1 to 6, and the numbers of Step 5's real run;
  - the pull request's number, which exists only once the controller opens the pull request (Step 7, as 3a's ruling T11-1 had it);
  - the controller's notes of 5 October 2026 on what followed #342's merge, kept as its memory `stage-3a-post-merge`, which is the source quoted in Step 9.
- Produces: documents that are true at the moment the pull request merges, and the post-merge check.

- [ ] **Step 1: `docs/api.md`.**
  1. In the opening, replace `four of its methods so far: «v2: the contract», at the end of this page.` with `thirteen of its methods so far: «v2: the contract», at the end of this page.`
  2. Under «v2: the contract», replace these lines:
```markdown
**Served beside v1, four methods so far.** Everything above this section is v1, and v1 is
unchanged. v2 is the contract in `proto/lessons/v2/` at the root of the repository, checked by
Buf, with its Python generated into `server/app/contract/`. Sub-project 3 of
[the programme](specs/2026-10-03-one-contract-design.md) serves it in three stages
([its design](specs/2026-10-05-server-v2-design.md)). The first serves `GetScheduleWindow`,
`GetMe`, `GetDiaryCapabilities` and `CreateDevice`; every other method answers `UNIMPLEMENTED`
until its stage, before it asks for any credential. No APK calls v2 yet. The proto files are
```
     with:
```markdown
**Served beside v1, thirteen methods so far.** Everything above this section is v1, and v1 is
unchanged. v2 is the contract in `proto/lessons/v2/` at the root of the repository, checked by
Buf, with its Python generated into `server/app/contract/`. Sub-project 3 of
[the programme](specs/2026-10-03-one-contract-design.md) serves it in three stages
([its design](specs/2026-10-05-server-v2-design.md)), the second in eight pull requests.
Served so far: `GetScheduleWindow`, `GetMe`, `GetDiaryCapabilities` and `CreateDevice` (3a),
and `ListAuditEntries`, the three `ClassDeviceService` methods and the five `SubjectService`
methods (3b-1). Every other method answers `UNIMPLEMENTED` until its stage, before it asks
for any credential. No APK calls v2 yet. The proto files are
```
  3. Directly above `### What the values look like`, insert:
```markdown
### Paging and updating

- **A page** is AIP-158's: `page_size` and `page_token` in, `next_page_token` out, empty on
  the last page. `ListAuditEntries` reads 30 lines when `page_size` is unset, and reads a
  larger one than 100 as 100; a negative one is `VALIDATION_FAILED`. A page token is opaque:
  send back the `next_page_token` you were given, unchanged. It names the last line of its
  page, so a line written between two page turns moves nothing on the next page. A token this
  list did not hand out, another class's or an edited one, is `VALIDATION_FAILED` on
  `page_token`.
- **An `Update…`** takes an `update_mask` (AIP-134). A masked field the request leaves unset
  is cleared, as each method's comment says. A path the method does not take is
  `VALIDATION_FAILED` on `update_mask`, and the refusal does not repeat it. Without a mask, or
  with an empty one, the fields the request sets change and no other, which is what v1's
  `PATCH` did. The fields apply in the method's own order, whatever the mask's: `UpdateSubject`
  renames before it sets anything else, because the rename is what can be refused.
- **`ClassDevice.client_version`** is the `X-Lessons-Client` version a phone last sent to v2,
  recorded beside `last_seen_at` and on its fifteen-minute clock, and absent for a phone that
  never sent one. v1's `GET /manage/devices` does not carry it: v1's answers do not change, and
  a phone that speaks only v1 never sends the header.

```

- [ ] **Step 2: `docs/README.md`, `docs/architecture.md` and `README.md`.**
  1. In `docs/README.md`'s row for `api.md`, replace `served beside v1 four methods so far` with `served beside v1, thirteen methods so far`.
  2. In `docs/architecture.md`, «v2: one invoke behind two transports»:
     - Replace the line
```markdown
`services/` first — the join flow, the window's tag, the clock and the bounds — and the
```
       with these three lines:
```markdown
`services/` first — the join flow, the window's tag, the clock and the bounds, then the
member names, the class's wall clock, the dictionary read, the subject patch and the
journal's page keyed on its last line — and the
```
     - After `under its two prefixes, so v1 and the webhook never go down with it.`, add:
```markdown
Every `Update…` reads its mask through `rpc/masks.py` (AIP-134), and the gate records the app
version a phone sends beside its `last_seen_at`, which v2's `ClassDevice` and the bot's
«📱 Устройства» show.
```
  3. In `README.md`'s «Honest status», replace the row that begins `| v2 over REST and Connect |` with:
```markdown
| v2 over REST and Connect | thirteen methods served beside v1: `GetScheduleWindow`, `GetMe`, `GetDiaryCapabilities` and `CreateDevice` (3a), and the journal, the class's phones and the subjects (3b-1), each tested both ways in-process and against v1's own answer where v1 has one; no APK calls them yet |
```

- [ ] **Step 3: `CLAUDE.md`.**
  1. In «Server modules», the `services/` bullet: replace
```markdown
  `window.py` (the year's window and its tag), `clock.py` (the class's clock and the date
  bounds); the limiters are `security.py`'s, one instance each, and the sentences both
  versions answer with (the join's four, the diary's «disabled») are `app/wording.py`'s.
```
     with
```markdown
  `window.py` (the year's window and its tag), `clock.py` (the class's clock, the date
  bounds and `wall`, a stored stamp on the class's clock), `manage/classes.py`'s
  `member_names`, `manage/subjects.py`'s `dictionary_of` (the read that adopts nothing) and
  `update` (the rename-then-details patch), and `audit.py`'s `older_than` (a page keyed on
  its last line); the limiters are `security.py`'s, one instance each, and the sentences
  both versions answer with (the join's four, the diary's «disabled», the subjects' and the
  devices' refusals) are `app/wording.py`'s.
```
  2. In the `rpc/` bullet, replace the line
```markdown
  table) and `handlers.py` (which methods are served). A handler never commits and never
```
     with the two lines
```markdown
  table), `masks.py` (one reading of an `update_mask`, AIP-134) and `handlers.py` (which
  methods are served). A handler never commits and never
```

- [ ] **Step 4: Check the documents against what the tests read.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_rpc_errors.py tests/test_schema_version.py tests/test_ci_paths.py tests/test_contract.py
```
Expected: all pass. `test_rpc_errors.py` reads `docs/api.md`'s status table, and `test_schema_version.py` reads every document that names the head. Then run `scan_heads.py` from Task 2 Step 7 once more: `revisions named: ['0018']`, and nothing under `docs/specs/`.

- [ ] **Step 5: The gates, and their numbers everywhere.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m ruff check app tests scripts migrations && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m mypy && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -n auto
```
Expected:
- ruff prints `All checks passed!`;
- mypy prints `Success: no issues found in 218 source files`;
- pytest prints `2520 passed` and its time.

If the count is not 2520, find the test file that moved before writing anything. Then write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\counts3b1.py`:
```python
"""Write the suite's and mypy's counts from a real run into the places that carry them."""

import sys
from pathlib import Path

ROOT = Path("C:/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract")
TESTS, MODULES = sys.argv[1], sys.argv[2]
PLACES = {
    "CLAUDE.md": [
        ("— 2437 tests in about four minutes", f"— {TESTS} tests in about four minutes"),
        ("of all 214 modules", f"of all {MODULES} modules"),
    ],
    "README.md": [
        ("| clean, 214 modules —", f"| clean, {MODULES} modules —"),
        ("| 2437 tests, green,", f"| {TESTS} tests, green,"),
    ],
    "CONTRIBUTING.md": [
        ("of all 214 modules:", f"of all {MODULES} modules:"),
        ("the server tests, 2437 of them,", f"the server tests, {TESTS} of them,"),
    ],
    "docs/architecture.md": [("2437 tests on the server,", f"{TESTS} tests on the server,")],
    ".claude/skills/gates/SKILL.md": [
        ("of all 214 modules,", f"of all {MODULES} modules,"),
        ("— 2437 tests today,", f"— {TESTS} tests today,"),
    ],
    ".claude/agents/server-tests.md": [
        ("`server/tests/`. 2437 tests;", f"`server/tests/`. {TESTS} tests;"),
    ],
    "HANDOVER.md": [
        ("# 2437 tests, ~12 min alone on Windows", f"# {TESTS} tests, ~12 min alone on Windows"),
        ("# clean, 214 modules", f"# clean, {MODULES} modules"),
    ],
}
for name, swaps in PLACES.items():
    path = ROOT / name
    text = path.read_text("utf-8")
    for old, new in swaps:
        assert text.count(old) == 1, (name, old)
        text = text.replace(old, new)
    path.write_text(text, "utf-8")
print("written")
```
and run it with the two numbers the run printed, the test count first:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe C:/Users/lumen/.claude/jobs/c9e2d980/tmp/counts3b1.py 2520 218
```
Expected: `written`. These are the seven places the `handover` skill names, with mypy's count where they carry it. The 3a batch section's «2437 passed» in `HANDOVER.md` is a record of its own commit, and it stays.

- [ ] **Step 6: Commit the documents.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3b1-t7.txt`:
```text
Describe the thirteen v2 methods served, and the counts from the run

docs/api.md's «v2: the contract» names what 3b-1 serves and gains
«Paging and updating»: AIP-158's page and its opaque token, which names
the last line of its page; AIP-134's mask, cleared when a masked field is
left unset, refused when a path is not the method's, and v1's PATCH when
absent; and ClassDevice.client_version, which v1's answer does not carry.
CLAUDE.md and docs/architecture.md name the rules that moved into
services/ and rpc/masks.py. The counts are the run's own, in the seven
places that carry them.

Not covered: HANDOVER.md's close-out, written once the pull request has a
number.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add docs/api.md docs/README.md docs/architecture.md CLAUDE.md README.md CONTRIBUTING.md .claude/skills/gates/SKILL.md .claude/agents/server-tests.md HANDOVER.md && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3b1-t7.txt
```

- [ ] **Step 7: The controller pushes and opens the pull request** (the `github-pr` skill). It goes from `server-v2/3b` to `main`, on milestone 11, with its board item filled as the skill says. Its body names `Closes #HEADREC` and `Refs #273`, and says, before the merge, that revision `0018` is applied to production first («Not a task», below). Write the number it gets down as `#PR`.

- [ ] **Step 8: File the defect the close-out records, if nobody has.** Run:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && gh issue list --repo lumenpearson/lessons --state all --search "production deployment merge in:title"
```
If no issue says that a merge to `main` did not deploy production, write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\issue-3b1-vercel.md`:
```markdown
#342 merged as `8d779ef` at 14:54 UTC on 5 October 2026, and Vercel built no production
deployment for it. Only a Preview was built, from the push to `dev`. Production went on
serving `175ff42` until the owner promoted or redeployed `8d779ef` by hand, at about 15:20 UTC.

**Failure scenario:** a pull request merges, and every check is green. The merge looks done,
and production answers with the code from before it. The next merge that needs a migration
applied first would also leave `/api/v1/warmup` reading «База впереди кода» for as long as
nobody looks.

**What is known:** `vercel.json` has no ignore rule, and the Vercel connector this project's
sessions use lacks the team's scope, so the cause could not be read from here.

**Until it is found:** after every merge, read production for the new code: `/api/v1/warmup`,
and a call the merged stage serves. The 3b plan's post-merge step does that.
```
and file it:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && gh issue create --repo lumenpearson/lessons --title "A merge to main did not deploy production: #342's merge served the old code until a manual promote" --body-file C:/Users/lumen/.claude/jobs/c9e2d980/tmp/issue-3b1-vercel.md --label type:bug --label area:ci --label status:next --label needs:owner --milestone "v0.10.0 — One contract: REST v2, Connect and native gRPC, build console"
```
Put it on project 6 as the `github-pr` skill says. Write its number down as `#VERCEL`, or the existing issue's number if one was found.

- [ ] **Step 9: The HANDOVER close-out** (the `handover` skill, «What goes stale mechanically»). Write it while the pull request is open, after the controller has applied `0018` («Not a task», below), so that it can say so.
  1. Move «What the session before it added: the Petersburg diary can go through a Russian proxy (#334)» verbatim, retitled «What the batch before added: the Petersburg diary can go through a Russian proxy (#334)», to the top of `docs/history.md`, directly under its introduction. The current «What the last session added: v2 served beside v1 — stage 3a of sub-project 3 (#273)» becomes «What the session before it added: …». Its first paragraph changes from «Open as #342, a draft…» to «Merged as #342 (`8d779ef`, 5 October 2026), from `server-v2/3a`, on milestone 11.»
  2. Write the new section above it, with the run's numbers, the real SHAs and the issue numbers in place of the bracketed words:
```markdown
## What the last session added: the class's records over v2 — stage 3b-1 of sub-project 3 (#273)

Open as #PR, from `server-v2/3b` to `main`, on milestone 11, and on project 6. It closes
#HEADREC and refers to #273. The branch was cut from `main` at `8d779ef`, the merge of #342,
and carries [the number of] commits before this close-out, to `[short SHA]`. Written on
[date], after #342 merged. The schema head moved from `0017` to `0018`, which the session
applied to production through the Neon connector on [date and time, UTC], before the merge.
This is stage 3b-1 of `docs/specs/2026-10-05-server-v2-design.md`, built by the plan beside
it, `docs/specs/2026-10-05-server-v2-3b-plan.md`, which also summarises 3b-2 to 3b-8. v1
answers as before; v2 now answers thirteen methods.

- **The manage routers' shared rules moved into `services/` first**, with v1 calling them:
  the member names and the class's wall clock (`services/manage/classes.member_names`,
  `services/clock.wall`), the dictionary read that adopts nothing
  (`subjects.dictionary_of`, which v1's `/subjects` now reads too), the rename-then-details
  patch (`subjects.update`), and a page of the journal keyed on its last line
  (`audit.older_than`, `journal.page_after`). The sentences v1 and v2 both answer with are
  `app/wording.py`'s.
- **Nine methods.**
  - `ListAuditEntries`: AIP-158 paging, with an opaque token that names the last line
    served, so a line written between page turns moves nothing.
  - `ListClassDevices`.
  - `RevokeClassDevice`: idempotent, one audit line.
  - `UnlinkClassDevice`: `CLASS_DEVICE_NOT_LINKED`.
  - `ListSubjects` (no adoption) and `GetSubject`.
  - `CreateSubject`: `201`; `RESOURCE_EXISTS`.
  - `UpdateSubject`: AIP-134's mask, read once for every `Update…` by `rpc/masks.py`;
    `SUBJECT_RENAME_CLASH`.
  - `DeleteSubject`: `RESOURCE_IN_USE`.
- **Each phone's last app version**, `device_tokens.client_version` (revision `0018`,
  additive). The gate writes it beside `last_seen_at` and on its clock, and v1 never does.
  It shows in v2's `ClassDevice` (a new optional field) and as «сборка N» in the bot's
  «📱 Устройства». v1's `/manage/devices` keeps its shape.
- **The error table's later reasons name the stage that brings each** (`STAGES`, `LATER`,
  `HELD_BY`). A stage takes itself out of `STAGES` when it is done, so a reason it forgot
  in `LATER` fails.
- **One defect, filed before its fix**: #HEADREC, the 3a plan's quoted `/warmup` answer, which
  the head test read as a claim once the head moved.

### Gates

All at `[short SHA of the head]`.

- **ruff**: `ruff check app tests scripts migrations`, all checks passed.
- **mypy**: no issues found in [the number] source files.
- **The server suite.** `pytest -q -n auto`, run alone from `server/`, gave **[the number]
  passed** in [the time]. The seven places the `handover` skill names say [the number].
- **The contract**: `buf lint` exit 0; `buf breaking --against .git#ref=origin/main` exit 0;
  `buf generate` reproduces the committed files.
- **CI on the head** is read before the merge.
- **Android** was not run, because nothing under `android/` changed; its 1635 tests stand from
  before.

### What was deliberately left alone

- **3b-2 to 3b-8**, each summarised in the 3b plan with what it moves, its errors, its effects
  and its open questions, and **3c**.
- **v1's `/manage/devices` without `client_version`**: the design changes no v1 answer, and a
  phone that speaks only v1 never sends the header (the 3b plan, Ruling 7).
- **v1's manage list still adopts**, and v2's does not (decision 10).
- **An `UpdateSubject` without a mask that sends a subject as it was read** writes a log line
  per field it sends, as v1's `PATCH` does.

### What nobody has verified in this batch

- **The nine methods against Postgres**: every v2 test ran on SQLite, the journal's keyset
  query among them. On Postgres the stamps compare as timestamps, where SQLite compares them
  as strings.
- **`X-Lessons-Client` from an APK**: none sends it yet, so every `client_version` in
  production is null and «сборка» shows on no phone.
- **The nine on Vercel** beyond the post-merge check: it calls each new service once without
  a token.

### After #342's merge: stage 3a in production, and what the owner answered

None of this is code in #PR, and a close-out never gets a close-out of its own, so it is
written here. The source is the controller's notes of 5 October 2026.

- **Vercel built no production deployment for the merge.** Only a Preview was built, from
  the `dev` push. Production kept serving `175ff42` until the owner promoted or redeployed
  `8d779ef` by hand, at about 15:20 UTC. The cause is unknown: `vercel.json` has no ignore
  rule, and the Vercel connector lacks the team's scope. It is #VERCEL. After every merge, read
  production for the new code rather than trusting the merge.
- **Production after the promote:**
  - `/api/v1/warmup` reported `status` `ok`, schema `0017` and `"v2": true`;
  - REST `/api/v2/diary/capabilities` answered `200` with `private, no-store`;
  - Connect answered `200` in JSON and in binary (`application/proto`, 180 bytes);
  - native gRPC got `415` with the guard's sentence, so Vercel's bridge reports HTTP/1.1;
  - `/api/v2/me` without a token answered `401 DEVICE_TOKEN_INVALID` in Google's body;
  - `/api/v1/health` answered `200`.
- **The diary proxy went down from about 15:20 to 16:06 UTC.**
  - The VM was up and its outbound traffic worked, while nothing inbound arrived, not even
    ping.
  - The owner's password reset in the RUVDS panel had rebooted it and regenerated its SSH host
    key, which the console confirmed.
  - From 16:13 UTC everything answered again, and production's made-up Petersburg session
    got `409` through the proxy again.
- **The owner answered:**
  - **Preview holds the same variables as Production.** This is recorded on #118 with what it
    risks: previews write to the real database, `RUN_BOT` must stay `false` there, and the
    webhook is safe only because it is registered by hand.
  - **The real Petersburg account's password is right, but the account needs a code from SMS
    or MAX.** The app has no second-factor step and tells the parent the password is wrong:
    #343, on milestone 10, a sub-issue of #109, `needs:device`.
```
  3. **The opening paragraph.**
     - It names the pull requests open at that moment, read from `gh pr list --state open` rather than assumed. #PR is one, and it is the one carrying the paragraph.
     - It says that #342 merged as `8d779ef` on 5 October 2026, and that `main` is at the merge before #PR (`git rev-parse --short origin/main`).
     - It says the schema head moved to `0018`, applied before the merge, and that `EXPECTED_REVISION` moved with it.
     - It adds #342 to the merged list, and #343, #HEADREC and #VERCEL to the issue sentences.
     - It ends, as the skill says: «The SHA of its own merge is for the next close-out to write.»
  4. **The milestone table**: milestone 11's row moves #342 to the merged list, and gains #PR (open), #HEADREC and #VERCEL. Milestone 10's row gains #343.
  5. **Section 5:**
     - In the bullet «v2 as #342 serves it has been asked little outside the test client», replace the first sub-item, the preview read over REST only, with what production answered after the promote. Delete the sub-item on `http_version`, which production answered: HTTP/1.1, and `415`.
     - Add two sub-items: «the nine methods of 3b-1 against Postgres», and «a `client_version` from an APK: none sends the header».
     - Move the bullet «Vercel's proxy in front of Connect has been asked nothing», verbatim, to `docs/history.md`, under «Moved out of section 5 on [date]». Create that heading after the last «Moved out of section …» heading if it is not there, or append to it if it is. Add one line saying what answered it: Connect `200` in JSON and in binary in production on 5 October, after #342's merge.
  6. **Section 7:**
     - In the paragraph on Preview deployments, replace the sentences from «**On 5 October 2026 this changed, and it is not known how.**» to its end with: «**The owner answered on 5 October:** Preview holds the same variables as Production (#118). Previews write to the real database, `RUN_BOT` must stay `false` there, and the webhook is safe only because it is registered by hand.» The replaced sentences move, verbatim, to «Moved out of section 7 on [date]» in `docs/history.md`.
     - Replace the item that begins «**Try the Petersburg account you supplied on the diary's own site**» with: «**The Petersburg account needs a second factor (#343).** Its password is right, and the diary asks for a code by SMS or MAX, which the app has no step for. Change the password once #343 is decided.» The old item moves to the same «Moved out of section 7» heading.
  7. The cheat-sheet's counts under «How to continue» were written by Step 5.

  Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3b1-handover.txt`:
```text
Hand over stage 3b-1, and record what followed stage 3a's merge

HANDOVER.md's close-out is written while the pull request is open, so the
file is true when it merges. It describes 3b-1, and it records what no
close-out had: production after #342's merge, which Vercel deployed only
after a manual promote (filed as an issue), the diary proxy's outage that
afternoon, and the owner's answers on Preview's variables and the real
Petersburg account (#343). Section 5 loses what production answered;
section 7 loses what the owner did. The batch before moves to
docs/history.md.

Not covered: production after this pull request's merge; the next
close-out records it.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add HANDOVER.md docs/history.md && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3b1-handover.txt
```
and push. Once the close-out's facts are in `HANDOVER.md`, the controller may delete its `stage-3a-post-merge` memory.

- [ ] **Step 10: The merge** is the controller's, under the `github-pr` skill's five checks:
  1. CI is green on the exact head;
  2. `mergeable_state` is clean;
  3. the gates ran locally before the push;
  4. a milestone is attached;
  5. no review is waiting.

  It also needs `0018` on production already («Not a task», below).

- [ ] **Step 11: After the merge, read production.** Vercel did not deploy 3a's merge by itself (#VERCEL).
```bash
curl -s https://lessons-ruddy-zeta.vercel.app/api/v1/warmup; echo
curl -s -i https://lessons-ruddy-zeta.vercel.app/api/v2/class/auditEntries
curl -s -i https://lessons-ruddy-zeta.vercel.app/api/v2/class/devices
curl -s -i https://lessons-ruddy-zeta.vercel.app/api/v2/class/subjects
curl -s -i -X POST -H "Content-Type: application/json" --data "{}" https://lessons-ruddy-zeta.vercel.app/api/rpc/lessons.v2.SubjectService/ListSubjects
```
Expected:
- `/api/v1/warmup` reports `status` `ok`, `schema` `0018` and `v2` `true`.
- Each REST call is `HTTP/1.1 401` with Google's body and the reason `DEVICE_TOKEN_INVALID`.
- The Connect call is `401` with `"code":"unauthenticated"`.

A `501` with `UNIMPLEMENTED` means production still runs the code from before the merge. Ask the owner to promote or redeploy the merge, then read again. A `degraded` warmup reading «База отстала от кода» means `0018` is missing on production, which is an incident: apply it now («Not a task», below). Write what was seen into the controller's notes for the next close-out.

### Not a task: revision `0018` on production, before the merge (the controller)

The controller does this, through the Neon connector, once the pull request is reviewed and before it merges. That is the ordinary order for an additive revision (`CLAUDE.md`, «Run the migration BEFORE the merge that needs it»; the `migration` skill). No subagent does it.

1. **Take the DDL from the model**, from `$WT/server` once Task 2 is in:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -c "from sqlalchemy.dialects import postgresql; from sqlalchemy.schema import CreateColumn; from app.models import DeviceToken; print(CreateColumn(DeviceToken.__table__.c.client_version).compile(dialect=postgresql.dialect()))"
```
   Expected: `client_version INTEGER`.
2. **Find the project.** It is the one named `lessons`; the account has two projects, so read the name, and keep the id out of the repository.
3. **Read the database first:**
   - `SELECT version_num FROM alembic_version;` reads `0017`;
   - `SELECT column_name FROM information_schema.columns WHERE table_name = 'device_tokens' AND column_name = 'client_version';` returns no row.
4. **Say what it destroys, before the transaction:** «`0018` adds one nullable column to `device_tokens`. It destroys nothing and rewrites no row.»
5. **One transaction**, the stamp last:
```sql
ALTER TABLE device_tokens ADD COLUMN client_version INTEGER;
UPDATE alembic_version SET version_num = '0018' WHERE version_num = '0017';
```
6. **Read it back:**
   - the `information_schema` query returns one row, with `data_type` `integer` and `is_nullable` `YES`;
   - `alembic_version` reads `0018`;
   - production's `/api/v1/warmup` reports `degraded` with «База впереди кода…». That is the normal window between the migration and the deploy, and the merge closes it.

---

# 3b-2 to 3b-8: what each later pull request holds

**These are summaries, not task lists.** Each pull request below gets its own full task list, with complete code, written into this document under its summary when its turn comes. It is written against the tree as the pull request before it left it, and it follows the shape of 3b-1's tasks:
- the moves first, with v1 calling the moved code;
- then the methods, each tested both ways and against v1's own answer;
- the `HELD_BY`, `LATER` and `STAGES` changes;
- the documents and the close-out last.

Every file and line named here was read at `8d779ef`. Lines drift, so each task list re-reads them.

Every stage inherits 3b-1's machinery:
- the gate test, the no-echo sweep and the write rules, which pick up a served method by themselves;
- `rpc/masks.update_paths` for every `Update…`;
- `validate(…, at=)` for nested fields;
- `values.maybe_instant`;
- `STAGES`: the stage removes itself in the task that produces its last reason.

## 3b-2: Bells, the timetable and the class (15 methods)

**Methods.**
- **`BellService`** (all `ROLE_ADMIN`):
  - `ListBellSchedules`, `GetBellSchedule`;
  - `CreateBellSchedule`, which answers `201`;
  - `UpdateBellSchedule`, whose mask takes `name`, `is_default` and `periods`;
  - `DeleteBellSchedule`.
- **`TimetableService`** (`ROLE_ADMIN`): `GetTimetable`, and `ImportTimetable` with `validate_only` and `replace`.
- **`ClassService`:**
  - `GetClass`, and `UpdateClass` with a mask (`ROLE_ADMIN`);
  - `DeleteClass` (`ROLE_OWNER`, with `confirmation` as a query field);
  - `GetClassStats` (`ROLE_EDITOR`);
  - `GetTermScheme`, `UpdateTermScheme`, `ListTerms` and `UpdateTerm` (`ROLE_ADMIN`).

**v1 rules that move into `services/` first.**
- **The bells' update.** `api/manage/bells.py:bells_update` branches: a rename; `is_default: false` refused with «make another schedule the default instead»; `make_default`, whose `ScheduleEmpty` is a 422. `bells_periods` calls `replace_rows`. All of it moves to a `services/manage/bells.update(…)` that returns the rows silenced, which v1's two endpoints and v2's one masked method call. When both `periods` and `is_default` change in one request, `silenced_lessons` counts the rows this request stopped ringing, once.
- **The empty-schedule sentence.** «в этом расписании звонков нет ни одного урока» is written twice, in `api/manage/bells.py` and in `api/edit.py:day_put`. It moves to `app/wording.py`, together with «make another schedule the default instead».
- **The timetable import.** `api/manage/timetable.py:timetable_import` holds about 57 lines: the parse; the 422 for a paste with no weekday header and no bells block; the conflicts; the lines dropped for a number no bell rings; and the lines orphaned by shorter bells. They move to a `services/manage/timetable.import_paste(…)`, which returns a result (applied, days, lessons, bells, conflicts, rejected) and writes only when told to. v2's `validate_only` is that call with nothing written.
- **The class's update.** `api/manage/classes.py:class_update` holds about 50 lines:
  - the letter normalised with `terms.normalise_letter`;
  - the zone check;
  - `set_field` for each field;
  - `set_timezone`;
  - the name recomposed from the grade and the letter, with its own `class.name` line, unless the request names the class;
  - `set_join_mode`.

  It all moves to a `services/manage/classes.update(…)`, which v1's `PATCH` and v2's `UpdateClass` call.
- **The terms.** `GetTermScheme` and `ListTerms` read 3a's `terms.spans`, and never `services/manage/terms.current`, which seeds. `UpdateTermScheme` (`change_scheme`) and `UpdateTerm` (`move_term`) persist the set inside their own write, as `ensure` does today.

**Error-table rows.**

| Exception | Reason | Metadata |
| --- | --- | --- |
| `bells.ScheduleEmpty` | `EMPTY_BELL_SCHEDULE` | none |
| `bells.ScheduleIsDefault` | `RESOURCE_IN_USE` | `resource: "bell_schedule"`, `used_by: "class"` |
| `bells.ScheduleInUse` | `RESOURCE_IN_USE` | `resource: "bell_schedule"`, `used_by: "days"`, `count` (its `days`) |
| `classes.UnknownTimezone` | `VALIDATION_FAILED` | a violation on `school_class.timezone` |
| `classes.NameMismatch` | `VALIDATION_FAILED` | a violation on `confirmation` |
| `terms.TermError` | `TERM_BOUNDS_REFUSED` | none; the message is the service's own Russian sentence, as the proto says |

An unknown schedule id is a `Refusal` of `RESOURCE_NOT_FOUND`, with `resource: "bell_schedule"`. `EMPTY_BELL_SCHEDULE` and `TERM_BOUNDS_REFUSED` leave `LATER` here.

**Effects.** None: v1's manage endpoints send no notice.

**What v2 does not repeat.**
- `/manage/terms` seeding on a read.
- v1's two bell endpoints (`PATCH` and `PUT …/periods`), which become one masked update.
- A preview that refuses: v2's `validate_only` answers the preview as a success.

**Open questions, each with a recommendation.**
- **`UpdateClass` with `join_mode` masked and left `JOIN_MODE_UNSPECIFIED`.** Recommended: `VALIDATION_FAILED` on `school_class.join_mode`, as v1's 422 for a mode nobody defined.
- **`DeleteClass` is the caller's own token too.** Recommended: answer, then let the next call be `DEVICE_TOKEN_INVALID`, as v1 does and as the proto says.

## 3b-3: Notices as effects; access requests; the school directory (5 methods)

**Methods.**
- `AccessRequestService.ListAccessRequests`, `ApproveAccessRequest` and `DeclineAccessRequest` (`ROLE_ADMIN`).
- `DirectoryService.ListSchoolRegions` (`AUTH_KIND_NONE`), and `ListSchools` (`ROLE_ADMIN`).

**The notices become effects.**
- **`server/app/telegram_send.py`, new and neutral**, holds:
  - `build_bot()`, moved from `app/bot/bot.py:build_bot` and importing aiogram inside the function, so that the cold start stays free of aiogram (`test_cold_start.py`);
  - a one-shot send to one person;
  - the class notice over `services/notify.notify_subscribers`.
- **Both sends never fail the call.** Each logs and drops a failure, and each closes the bot's session, as `api/edit._tell` and `api/manage/requests._tell` do today.
- **`app.bot.bot` re-exports `build_bot`**, so the bot is unchanged.
- **The three copies of `_build_bot`** (`api/edit.py`, `api/manage/requests.py`, `api/cron.py`) call `telegram_send`. The tests that patch `manage.requests._build_bot` (`test_api_manage.py`'s `recording_bot`) patch `app.telegram_send.build_bot` instead, and that change is the stage's one edit to a v1 test, said in its commit.
- **v2's approve and decline register the notice with `call.after_commit(…)`**, so it is sent only after the commit, and never on a refusal (the design's Risks). Each notifying method's test asserts that no notice goes out on a refusal.
- **`tests/test_service_layering.py`** gains `app.telegram_send` among the modules that may reach neither `app.bot` nor a v1 router.

**v1 rules that move into `services/` first.**
- **Approving.** `api/manage/requests.py:request_approve` picks the role (absent means the one asked for) and turns `GrantRefused` into a 403. `GrantRefused` today carries two sentences (`str` for the bot, `.detail` for the API), and no fact. It gains a `why` (`"role_too_high"` or `"member_senior"`) for the proto's metadata, and both shells keep their own words.
- **The directory.** `api/directory.py:school_regions` holds the order of its checks:
  1. admit;
  2. throttled, a 429;
  3. a query too short to search with: a 422, and the attempt forgiven;
  4. disabled;
  5. the allowance spent;
  6. the upstream failing.

  That order moves to a `services/directory.py`, which raises facts, and v1 calls it.
- **The schools search.** `api/manage/schools.py:schools_search`'s error mapping and paging move beside it.
- **Member names.** `ListAccessRequests` uses 3b-1's `member_names`.

**Error-table rows.**
- `access.GrantRefused` → `ROLE_GRANT_REFUSED`, with `why`.
- `quota.AllowanceSpent` → `DIRECTORY_SPENT`, with `retry_after_seconds` until the next Moscow day.
- The directory's disabled and upstream facts → `DIRECTORY_DISABLED` and `DIRECTORY_UNAVAILABLE`.
- `schools.SearchError` → `VALIDATION_FAILED` on `query`.
- The throttle → `THROTTLED`.
- An answered or unknown request → a `Refusal` of `RESOURCE_NOT_FOUND`, with `resource: "access_request"`.

`ROLE_GRANT_REFUSED`, `DIRECTORY_DISABLED`, `DIRECTORY_SPENT` and `DIRECTORY_UNAVAILABLE` leave `LATER`.

**What v2 does not repeat.** Nothing of substance; the directory's two budgets are shared with v1 through `security.directory_limiter` (decision 11).

**Open questions.**
- **`ListSchools`' `page_token`**, when the directory itself has no offset (the proto says every call is one upstream search). Recommended: an opaque token carrying the next page's number, refused as 3b-1's are.
- **A notice to a requester who blocked the bot.** Recommended: logged and dropped, as v1's `_tell`; the decision stands.

## 3b-4: The phone's own (11 methods)

**Methods.**
- `MeService.UnlinkMe` and `CreateLinkCode` (`AUTH_KIND_DEVICE`).
- `GetCalendarFeed` and `CreateCalendarFeed` (`AUTH_KIND_DEVICE_LINKED`).
- `ListTasks`, `GetTask`, `CreateTask` (which answers `201`), `UpdateTask` (masked) and `DeleteTask` (`AUTH_KIND_DEVICE_LINKED`).
- `CreateHomeworkTick` and `DeleteHomeworkTick` (`AUTH_KIND_DEVICE_LINKED`).

**v1 rules that move into `services/` first.**
- **From `api/public.py`:**
  - `_check_homework_id` and the homework-in-class lookup (`_homework_in_class`), which 3b-5 shares;
  - `_wall_time`;
  - `_own_task`;
  - `tasks_update`'s setattr loop with `done`.

  They move into `services/tasks.py`, and v1 calls them.
- **The commits inside services**, which a handler cannot use, because a handler never commits:
  - `tasks.add_task`, `set_done`, `delete_task` and `toggle_homework_done` (`services/tasks.py`, lines 249, 259, 264, 312 and 317);
  - `calendar.ensure_calendar_token` (lines 69 and 81);
  - `linking.issue_link_code` (line 57).

  The survey names «non-committing variants» for these. Recommended instead: each service stops committing, and v1's routers commit after the call, as the manage endpoints do. That way one implementation serves both, and no variant stands beside the committing one. The bot loses nothing by it, because its `ContextMiddleware` commits at the end of every update (`CLAUDE.md`, `di.py`); the task list greps every other caller first. 3b-4's task list follows whichever the controller confirms. `issue_link_code`'s retry on a colliding code depends on the commit failing, so it moves to a savepoint (`session.begin_nested()`) around the write. That is the one place where the rule changes shape, and its test makes two invocations draw the same code.

**Error-table rows.**
- An unknown or foreign task → `RESOURCE_NOT_FOUND`, with `resource: "task"`, the same for somebody else's task as for none, as the proto says.
- A foreign `homework_id` → `VALIDATION_FAILED` on `task.homework_id`.
- An unknown homework for a tick → `RESOURCE_NOT_FOUND`, with `resource: "homework"`.

No new reason.

**Effects.** None.

**What v2 does not repeat.**
- `/me` minting a link code on a read: `CreateLinkCode` mints, and a linked phone gets none.
- `/calendar` minting the feed secret on a read: `GetCalendarFeed` never mints.
- v1 letting any device, an anonymous one included, mint the feed: v2 asks for a linked account, as the proto says.
- v1's `POST /tasks/{id}/done`, which is `UpdateTask` with `done`.

**Open questions.**
- **`Cache-Control: private, no-store`** on `CreateCalendarFeed`'s and `CreateLinkCode`'s answers, which are a secret URL and a short-lived code. Recommended: yes, through `rest.NO_STORE_CREDENTIAL`.
- **`UnlinkMe` on a phone that is not linked.** Recommended: success with nothing written, as the proto says.

## 3b-5: Homework and events (10 methods)

**Methods.**
- `HomeworkService.ListHomework` and `GetHomework` (`ROLE_VIEWER`); `CreateHomework` (`201`), `UpdateHomework` (masked) and `DeleteHomework` (`ROLE_EDITOR`).
- `EventService.ListEvents`, `GetEvent`, `CreateEvent` (`201`), `UpdateEvent` and `DeleteEvent`, all `ROLE_EDITOR`, the reads included, as the contract has them.

**v1 rules that move into `services/` first.**
- **From `api/edit.py`:**
  - `homework_put`: validation, `subjects.spelling`, the upsert, its audit line and its notice;
  - `homework_delete`;
  - `event_put`, about 45 lines;
  - `event_delete`;
  - `_check_date`, which is `services/clock.in_bounds` already.

  They move to `services/homework.py` (which has `upsert`) and a new `services/events.py`. v2 needs a create that refuses an existing (date, subject) where v1 upserts, so the service gains `create` beside `upsert`, raising `HomeworkExists`.
- **From `api/public.py`:** `homework_list` (the window: 21 days by default, 62 at most; the ticks from `tasks.homework_ticks`) moves into `services/homework.py`.

**Error-table rows.**
- `HomeworkExists` → `RESOURCE_EXISTS`, with `{resource: "homework", field: "subject"}`.
- A date out of bounds, or a window over 62 days → `VALIDATION_FAILED` on the field.
- An unknown id → `RESOURCE_NOT_FOUND`, with `resource` `"homework"` or `"event"`.

**Effects.** Notices, through 3b-3's `telegram_send` and `call.after_commit`, after the commit and never on a refusal, with the author excluded:
- homework is kind `"homework"`, for v1's create, update and delete;
- events are kind `"changes"`.

The texts are v1's, moved to `app/wording.py`.

**What v2 does not repeat.** v1's `PUT /homework` upsert by (date, subject): `CreateHomework` is create-only, and `UpdateHomework` changes it, announcing «обновлено» as v1's update branch did. v1's `PUT /events` becomes a `POST`, so that a retried create can be told apart from a second event, as the proto says.

**Open questions.**
- **An `UpdateHomework` that moves `due_date` or `subject` onto an existing pair.** Recommended: `RESOURCE_EXISTS`.
- **An `UpdateEvent` that moves an event to another date.** Recommended: one notice naming the new date.

## 3b-6: Days and substitutions (7 methods)

**Methods.**
- `DayService.GetDay` (an unmarked date is `DAY_KIND_NORMAL`), and `UpdateDay` (masked, with `allow_missing`), both `ROLE_EDITOR`.
- `SubstitutionService.ListSubstitutions`, `GetSubstitution`, `CreateSubstitution` (`201`), `UpdateSubstitution` and `DeleteSubstitution`, all `ROLE_EDITOR`.

**v1 rules that move into `services/` first.**
- **`api/edit.py:day_put`**, about 105 lines, is reconciled with `services/manage/special_days.mark`, the bot's near-duplicate, rather than replaced by it.

  | | `day_put` | `mark` |
  | --- | --- | --- |
  | A schedule of another class | refused | not asked |
  | An empty schedule | refused (`EMPTY_BELL_SCHEDULE`) | not asked |
  | A shortened day without a schedule | refused | takes the class default |
  | Another kind's schedule | sets it as sent | clears it |
  | The note | writes it | writes none |
  | The audit line | `day.set` and `day.clear` | none |
  | A notice | sends one | sends none |

  One `special_days.put_day(…)` holds `day_put`'s checks as facts (`ScheduleNotInClass`, `bells.ScheduleEmpty`, `ShortenedNeedsSchedule`) and its write. v1's `PUT /days` and v2's `UpdateDay` call it. The bot's two-step flow keeps offering the class default as the picker's starting value, which is a step of its screen and not a second rule, and it calls `put_day` with the schedule it ends on.
- **`api/edit.py:override_put`**, about 150 lines, moves to a new `services/substitutions.py`. That covers `why_no_lesson_can_be_drawn`, `rung_indexes_on`, `can_ring`, `template_indexes_on` and `subjects.spelling`, with facts: `NoLessonOnDay(why)`, `NoBellForLesson(index)`, `LessonNotOnTimetable(index)` and `SubstitutionExists`.

**Error-table rows.**

| Exception | Reason | Metadata |
| --- | --- | --- |
| `NoLessonOnDay` | `NO_LESSON_ON_DAY` | `why` |
| `NoBellForLesson` | `NO_BELL_FOR_LESSON` | `index` |
| `LessonNotOnTimetable` | `LESSON_NOT_ON_TIMETABLE` | `index` |
| `SubstitutionExists` | `RESOURCE_EXISTS` | `{resource: "substitution", field: "index"}` |
| `bells.ScheduleEmpty` | `EMPTY_BELL_SCHEDULE` | none; the row from 3b-2 |
| a foreign schedule | `VALIDATION_FAILED` | a violation on `day.bell_schedule_id` |
| `SELF_STUDY` or `DAY_OFF` sent | `VALIDATION_FAILED` | a violation on `day.kind`; the bot sets these and v2 only reads them |

`NO_LESSON_ON_DAY`, `NO_BELL_FOR_LESSON` and `LESSON_NOT_ON_TIMETABLE` leave `LATER`.

**Effects.** Notices of kind `"changes"`, after the commit:
- `UpdateDay` sends one when a mark is set;
- clearing one sends one only if a mark existed;
- `DeleteSubstitution` sends one only when a row existed.

**What v2 does not repeat.** v1's `PUT /overrides` upsert, with `"clear"` as an action: v2 has a create-only `CreateSubstitution` (`RESOURCE_EXISTS` for a second one on the same date and number), a masked update, and a delete.

**A harness note for the task list.** `UpdateDay`'s path variable is `{day.date}`, a string. The gate test's empty request therefore builds a REST path that ends in an empty segment, which no route matches. Recommended: `test_v2_reads._request` gives `UpdateDayRequest` and `GetDayRequest` a valid date, as it gives `GetScheduleWindow` its year.

**Open questions.**
- **Is `UpdateDay` with `allow_missing` and `DAY_KIND_NORMAL` on an unmarked date a no-op success?** Recommended: yes, with no notice.
- **A substitution at a number that no longer rings stays editable** (the proto). Recommended: `UpdateSubstitution` skips the bell check, and `CreateSubstitution` keeps it.

## 3b-7: The diary's registry, sessions and reads (10 methods)

**Methods.**
- `DiaryService.CreateDiarySession` (`AUTH_KIND_NONE`, `201`), and `DeleteDiarySession`.
- `ListStudents`, `ListScheduleDays`, `ListDiaryHomework`, `ListMarks`, `ListPeriods`, `ListDiarySubjects`, `ListTeachers` and `ListTurnstileEvents` (`AUTH_KIND_DIARY`).

**The registry becomes a table (decision 12).** `providers/diary/registry.py` today has `PETERSBURG`, `NETSCHOOL`, `KEYS`, `provider_for` with one `if` per key, and `binding()` with an `if == NETSCHOOL` branch. Each provider becomes a row:
- the key;
- the module and class, still imported lazily;
- what a binding needs: nothing, or a region and a school;
- the sign-in methods (`SignInMethod`);
- the `DiaryFeature`s it declares;
- how its corrections are scoped (`services/diary_corrections.child_scope` today).

`binding()` validates per row, with no `if` per key. `GetDiaryCapabilities` fills `sign_in_methods` and `features` from the table, where 3a left them empty, and that is what sub-project 5 waits for before it moves the diary.

**Features are read from what each provider implements.** NetSchool's `subjects` and `teachers` return `[]`, and its `attendance` returns `m.to_attendance()` (`providers/netschool/provider.py`, lines 128–135). So NetSchool does not declare `DIARY_FEATURE_SUBJECTS`, `DIARY_FEATURE_TEACHERS` or `DIARY_FEATURE_TURNSTILE`. A method whose feature the session's provider does not declare answers `UNIMPLEMENTED` / `FEATURE_UNSUPPORTED`, with `feature` the `DiaryFeature` name, before any upstream call.

**v1 rules that move into `services/` first.**
- **From `api/diary.py`:**
  - `_admit`, over 3a's `security.DiaryAttempt`;
  - `_served_region`;
  - `_resolve_session_target`;
  - the credential serialised per provider;
  - `register_session`'s outcome mapping with `attempt.succeeded`, `failed` and `not_judged`, about 85 lines.

  They move to a `services/diary.register(…)` that raises facts.
- **The reads' rules:**
  - `_range`: 14 days by default, 62 at most, from the diary's today;
  - `_child`;
  - `_corrections`;
  - `_guard`, which maps each provider error to its refusal.

  They move beside it.
- **Every branch on the provider key**, as the survey lists them (`registry.py`, `services/diary.py`, `services/diary_keepalive.py`, `services/diary_corrections.py`, `api/diary.py`, `api/diary_web.py`, `rpc/diary.py`, `bot/handlers/manage/diary_binding.py`), reads the row instead, where it is a property of the provider.

**Error-table rows.**

| Exception | Reason | Metadata |
| --- | --- | --- |
| `BadCredentials` | `DIARY_CREDENTIALS_REJECTED` | none |
| `SessionExpired` | `DIARY_REAUTH` | none |
| `NoStudents`, a subclass of `SessionExpired`, with a row of its own, which the table's MRO lookup finds first | `DIARY_NO_STUDENTS` | none |
| `UpstreamUnavailable` and `SignInUnsupported` | `DIARY_UNAVAILABLE` | `upstream` (`"upstream"` or `"address-refused"`) |
| `UnexpectedResponse`, and `DiaryError` as the fallback | `DIARY_UPSTREAM_UNREADABLE` | none |
| the attempt's throttle | `THROTTLED` | `retry_after_seconds` |
| an unknown student | `RESOURCE_NOT_FOUND` | `resource: "student"` |

`DiaryDisabled`'s `HELD_BY` row stops saying `"3b-7"` and names its test. Five reasons leave `LATER`. `rest.NO_STORE_CREDENTIAL` gains `CreateDiarySession`, as its comment already says.

**The writes services commit, kept on purpose** (decision 4): `find_session`, `_expire` and `_remember_token`. A dead credential stays expired whatever the call then does.

**What v2 does not repeat.** `POST /diary/login`, the password through this server (`docs/api.md`, «Not in v2, on purpose»).

**Open questions.**
- **Is a feature declared by a provider that returns empty for one pupil unsupported?** Recommended: no. Only what the provider never implements is undeclared; an empty answer is an answer.
- **What do `features` hold when the diary is disabled?** Recommended: empty, with `enabled` false, as today.

## 3b-8: The diary's corrections (4 methods)

**Methods.** `DiaryService.ListCorrections`, `BatchUpdateCorrections` (all or none), `ResetCorrections` and `ClearCorrections` (`AUTH_KIND_DIARY`).

**v1 rules that move into `services/` first.**
- **The commits.** `services/diary_corrections.put_override` and `drop_override` commit inside themselves (lines 166, 180, 193 and 212), and so does `drop_overrides` (227). A batch that must land whole cannot be built from them.
  - The controller's ruling of 5 October asks for non-committing variants of `put_override` and `drop_override`.
  - This plan recommends one step further, as in 3b-4: the functions stop committing, and v1's routers commit after the call. Two variants of one write are two implementations of one rule, which `CLAUDE.md`'s service layer exists to prevent.
  - Either way, `BatchUpdateCorrections`' all-or-none is `invoke`'s one commit.
  - 3b-8's task list follows whichever the controller confirms.
- **The routers' rules.** `api/diary.py`'s `put_override`, `reset_override`, `reset_all_overrides` and `list_overrides` move beside the services: the target, the field, and the scope through the 3b-7 row's correction scope.

**Error-table rows.**
- `diary_overrides.UnknownTarget`, `UnsupportedField` and `EmptyNotAllowed` → `VALIDATION_FAILED`, naming `corrections[i].target`, `.field` or `.value`, as the proto says. The handler adds the index, because the service knows only one correction.
- A pupil `child_scope` cannot scope → `CORRECTIONS_UNAVAILABLE`.
- `diary_corrections.UnknownDiaryServer` → `CORRECTIONS_UNAVAILABLE`.

`CORRECTIONS_UNAVAILABLE` leaves `LATER`, and `STAGES` is empty when 3b-8 merges.

**Effects.** None.

**What v2 does not repeat.** v1's one-correction-per-request `PUT`: v2 takes a batch, and the last writer wins, `original` included, as the proto says.

**Open questions.**
- **A cap on a batch.** Recommended: 200 corrections, past which the request is `VALIDATION_FAILED` on `corrections`, so that one request cannot hold the table's lock for long.
- **`ResetCorrections` on a target with no correction.** Recommended: success, as the proto says.

---

## Self-review

**Spec coverage, against the design's decisions for 3b and the controller's rulings.**
- **Decision 1, 3b's row.**
  - The other seventy-one methods: 3b-1 serves nine, and the summaries name the other sixty-two, 15 + 5 + 11 + 10 + 7 + 10 + 4.
  - Each stage brings its v1 rules into `services/` first: Task 1 for 3b-1, and «v1 rules that move» in every summary.
  - The per-provider registry table is 3b-7's.
  - The Telegram notices as effects are 3b-3's, then 3b-5's and 3b-6's.
- **Decision 2, where things live and the rules that move first.**
  - 3b-1's moves: Task 1, with v1 calling the moved code and `test_api_manage.py` unchanged as the proof.
  - `telegram_send.py` and `app.bot.bot`'s re-export: 3b-3.
  - The layering test grows in 3b-3 for `telegram_send`. 3b-1 adds no new kind of import, so the existing walk holds it.
- **Decision 4, effects after the commit.** No 3b-1 method notifies; v1's manage devices, log and subjects send nothing. The effect hook is first used in 3b-3, whose summary asks for the no-notice-on-refusal test the design's Risks name.
- **Decision 5, the table.**
  - New rows: Tasks 4 and 6.
  - `HELD_BY`: each row names its read-back test, and the stage strings are checked against `STAGES`.
  - `LATER`: each reason leaves in the commit that produces it.
  - The no-echo sweep picks up every served method by itself, and Task 3's and Task 6's refusals name no value.
- **Decision 10, reads write nothing.**
  - `ListAuditEntries`, `ListClassDevices`, `ListSubjects` and `GetSubject` each have a statement test.
  - `ListSubjects` adopts nothing, where v1 does.
  - The allowlist grows by the one column decision 15 allows (Task 2).
- **Decision 12:** 3b-7's summary, with the features read from what each provider implements.
- **Decision 14:** every new method is called both ways. Each is checked against v1's own answer wherever v1 has the endpoint: the log, the devices, both subject lists, the create's cleaning, and every refusal's words and status. A non-idempotent write's success is asked once per transport (Ruling 17).
- **Decision 15, the client version:**
  - the column, its revision and the head: Task 2;
  - written beside `last_seen_at` on its clock, never by v1: Task 2's six tests and the rule's test;
  - shown in v2's `ClassDevice`, additively and regenerated, with `test_contract_mirror.py` and `docs/api.md`: Tasks 4 and 7;
  - shown in the bot's «📱 Устройства», escaping and the budget per the `bot-message` skill: Task 4;
  - v1's `/manage/devices`: decided, and kept (Ruling 7).
- **The controller's rulings for 3b-1.**
  - The helpers move: Task 1.
  - `ListAuditEntries`' token, defined honestly: Ruling 4 and Task 3.
  - `RevokeClassDevice` idempotent with no second line, and `CLASS_DEVICE_NOT_LINKED`: Task 4.
  - `ListSubjects` without adoption, `CreateSubject` 201 with `RESOURCE_EXISTS`, `UpdateSubject`'s mask with `SUBJECT_RENAME_CLASH` and `RESOURCE_EXISTS`, and `DeleteSubject`'s `RESOURCE_IN_USE`: Tasks 5 and 6.
  - The migration applied by the controller before the merge, with the model's DDL: «Not a task».
  - A REST POST body is `application/json`, which the harness sends.
  - The close-out after the pull request has a number, recording the post-3a facts from the controller's notes: Task 7, Steps 7 to 9.
  - The counts written from a real run: Task 7, Step 5.
  - Production read after the merge: Task 7, Step 11.

**Findings of this review and of the scratch run, fixed above.**
- **The head test reads `docs/specs/`.** The head's move in Task 2 would have failed the suite on 3a's plan. It would also have failed on this plan, which said the head in the test's own words in one sentence of Task 7 until the scan found it. The fix:
  - Ruling 11;
  - Task 2's Steps 1, 6 and 7: an issue first, the reworded record, and a scan;
  - a Global Constraint for every later stage.
- **The harness could not send `UpdateSubject`'s empty request over REST**, the first dotted path variable any stage serves. The fix is Task 6's change to `conftest.py`. Seven later methods have a dotted variable too: `UpdateBellSchedule`, `UpdateTerm`, `UpdateDay`, `UpdateEvent`, `UpdateHomework`, `UpdateTask` and `UpdateSubstitution`.
- **`clock.py`'s imports were in an order ruff refuses.** Task 1 Step 2 now gives ruff's order.

**Placeholder scan.**
- No «TBD», and no «similar to Task N».
- Every code step quotes the whole file or the exact text it replaces.
- The bracketed words in Task 7's close-out are measured values: a SHA, a count, a time, a date, an issue's number. They are known only when the step runs, as 3a's plan had them.
- `#HEADREC`, `#VERCEL` and `#PR` are numbers that Task 2 Step 1, Task 7 Step 8 and Task 7 Step 7 obtain.

**Type consistency.**
- `classes.member_names(session, class_id) -> dict[int, str]` is called so by v1's three routers and by `rpc/audit.py` and `rpc/class_device.py`.
- `clock.wall(stamp, school_class)` is called so by v1's devices, journal and requests routers.
- `journal.page_after(session, class_id, *, limit, after_id)` and `journal.has_line(session, class_id, entry_id)` are called so by `rpc/audit.py` and the Task 1 tests.
- `audit.older_than(session, class_id, after_id, *, limit)` is called so by `page_after`.
- `subjects_service.update(session, class_id, actor_id, subject, changes)` is called so by v1's `PATCH`, `rpc/subject.update_subject` and the Task 1 tests.
- `subjects_service.dictionary_of(session, class_id)` is called so by `listing`, v1's `/subjects` and `rpc/subject.list_subjects`.
- `touch_last_seen(session, device, *, client_version=None)` is called so by the gate. v1's `current_class` passes none.
- `masks.update_paths(mask, resource, changeable)` and `masks.NOT_CHANGEABLE` are used so by `rpc/subject.py`, `test_rpc_masks.py` and `test_v2_subject_writes.py`.
- `validate(model, data, *, at="")` is called with `at="subject."` by `rpc/subject.py`.
- `values.maybe_instant(moment)` is used by `rpc/class_device.py`.
- The wording names (`UNKNOWN_SUBJECT_DETAIL`, `SUBJECT_EXISTS_DETAIL`, `subject_rename_clash_detail`, `subject_in_use_detail`, `UNKNOWN_DEVICE_DETAIL`, `CLASS_DEVICE_NOT_LINKED_DETAIL`) are defined in Task 1 and read under those names by v1's routers, `rpc/errors.py`, the handlers and the tests.
- `HELD_BY`'s function names are the test functions Tasks 4 and 6 define, letter for letter.

**Review Focus.** Each of the five lines names its test and its task, and each test is in that task's code.

**Counts.** The tasks add 10, 11, 9, 16, 10 and 27 tests: 83, making 2437 into 2520. The gate test and the no-echo sweep add two cases per served method, eighteen of the 83. The new modules are four, making 214 into 218. The real run of Task 7 Step 5 is what the documents carry.
