# Serving v2, stage 3a: the shells (sub-project 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** drafted on 5 October 2026, before the owner approved the design, at the owner's instruction to continue overnight. The owner approved the design the same day with every recommended answer (#301), so no task changes for a decision; each is still re-checked against the tree before it runs.

**Goal:** Serve v2 beside v1 on Vercel for the four methods a phone calls first — `GetScheduleWindow`, `GetMe`, `GetDiaryCapabilities` and `CreateDevice` — through every mechanism the other seventy-one will use: the generated `google/rpc` details, the v1 rules moved into `services/`, the one error table, the generic gate, `invoke`, the Connect mount with its two guards and its fail-safe, the REST transcoder, and the harness that calls each method both ways.

**Architecture:**
- v1's routers keep answering byte for byte, but the rules v2 needs leave them first: the join flow (`services/join.py`), the window's tag and v2's year (`services/window.py`), the clock and the date bounds (`services/clock.py`), the device's access (`services/linking.Access`), the limiters (`security.py`, one instance each), `caller_bucket` over header lines and a peer (`api/deps.py`), and the sentences both versions answer with (`app/wording.py`).
- `app/rpc/` reads every method's credential, least role and REST binding from the generated descriptors (`methods.py`, `gate.py`), runs each call through one `invoke` (`call.py`: gate, dishka scope, handler, commit, effects) and words every refusal through one table (`errors.py`). `rpc_app()` puts the seventeen generated Connect apps under `/api/rpc`, each over an adapter that calls `invoke`.
- `app/rest/` builds one Starlette route per unary method from its `google.api.http` rule and calls the same `invoke`; `rest/errors.py` writes Google's JSON error body. `main.mount_v2` mounts both, and answers `503` under their prefixes if v2 cannot be imported, so v1 never goes down with it.

**Tech Stack:** Python 3.12 (3.13 locally), FastAPI/Starlette, dishka, SQLAlchemy 2 async, `connectrpc` 0.12.1, `protobuf-py` 0.6.0, Buf 1.73.0, pytest + httpx's ASGI transport.

**Spec:** `docs/specs/2026-10-05-server-v2-design.md` (stage 3a: decision 1's table, and every decision 3a touches), under the programme `docs/specs/2026-10-03-one-contract-design.md` (section 1, «Errors», «Caching the year window», «Retiring clients»; section 2; «What the spike found»). Read both before Task 1.

## Rulings made while writing this plan

Each is a choice the design leaves to the code, or a place where the code showed the design's text could not be followed literally. Every one was run in a scratch copy (see «What was verified»).

1. **A method with no handler answers `UNIMPLEMENTED` before the gate, on both paths.** The design keeps «the `Protocol`'s own `UNIMPLEMENTED`» and runs the gate's behaviour «over every method a stage implements». So `invoke` asks `HANDLERS` first and raises the generated default's exact error (`ConnectError(Code.UNIMPLEMENTED, "Not implemented")`) with no gate, scope or query; REST answers the same through the transcoder. The gate's code for kinds no served method has yet (a diary token, editor and above, a linked phone) is held by `gate.admit` tests over real unimplemented methods and by handlers put into `HANDLERS` for a test. This overrides the brief's «unimplemented ones still pass through the gate first».
2. **Every adapter method is overridden and looks its handler up per call.** The generated `Protocol` default is never reached; `invoke` raises the same error instead. That makes a handler registered by a test — or by 3b — reachable on both transports without rebuilding the app, and `test_rpc_call.py` holds the two errors level.
3. **`rpc/methods.py` is a module the design does not list.** The gate, the RPC adapters and the transcoder all read a method's facts; one reading of the descriptors, keyed `"lessons.v2.<Service>/<Method>"` (the Connect path), keeps them from disagreeing. `rpc/handlers.py` is the one table method → handler, so `call.py` imports handler modules only through it, and handler modules import `Call` only under `TYPE_CHECKING`.
4. **`services/window.py` holds the tag rules and v2's year; v1's `/bundle` keeps its own order.** `etag_matches` is v1's `_matches` verbatim and `strong_etag` is v1's quoting and hashing over a canonical text; both versions call them. v1's `/bundle` resolves, looks ahead, seeds terms, adopts subjects and commits in that order, and moving any step would move its answers, so its assembly stays in the router. `for_year` is v2's window. The device's access (`linked`, `role`, «editor or above») moves to `services/linking.Access`, used by v1's `/bundle` and `/me` and by v2's `GetMe` and `DeviceAccess`.
5. **The sentences v1 and v2 both answer with live in `app/wording.py`**: the join's four (`JOIN_THROTTLED_DETAIL`, `JOIN_UNKNOWN_CODE_DETAIL`, `JOIN_INVITE_ONLY_DETAIL`, `JOIN_DEVICE_LIMIT_DETAIL`) and the diary's «disabled» one (`DIARY_DISABLED_DETAIL`). v1's `/join` and diary 503 and v2's error table each import them from there. A service refusal carries facts, never a sentence, and the wording both shells share lives in `app/wording.py` (CLAUDE.md), so `services/join.py` and `services/diary.py` hold none. Where v1 had a sentence it is v1's (decision 5), and one copy cannot drift. The review of 5 October named the join's four; the diary's sentence, which this plan was also moving into a service, follows the same rule.
6. **One limiter instance each, re-exported under v1's names.** `join_limiter`, `diary_login_limiter`, `diary_open_limiter`, `directory_limiter`, `MAX_DEVICES_PER_CLASS` and the diary's attempt (`DiaryAttempt`, raising a neutral `security.Throttled`) live in `security.py`; v1's modules re-export them as `from app.security import x as x`, so every existing test import keeps working and `test_every_throttle_on_the_attempts_table_uses_one_window` finds the four in `security.py`.
7. **The port is stripped where it is known to be there.** `caller_bucket(headers, peer)` takes a bare host; `deps.peer_host()` strips `connectrpc`'s `":port"` by its format (`rpartition`), at the adapter. Stripping «a port, IPv6 included» from an arbitrary peer string cannot be done by parsing: `"::1:4321"` is itself a valid IPv6 address.
8. **The bounds and the clock are `services/clock.py`**: `MIN_DATE`, `MAX_DATE`, `in_bounds`, `now`, `today`. v1's sentences («start must be between 2000-01-01 and 2100-01-01» and the rest) are unchanged, and `api/diary.py` keeps its `MIN_DATE`/`MAX_DATE` names as aliases.
9. **`GetScheduleWindow` serves the years 2000 to 2098**, those whose every day is inside the bounds, and refuses any other — `0`, an unset `year`, included — with `VALIDATION_FAILED` and a `BadRequest` on `year`: «year must be between 2000 and 2098». The window runs from `school_year_start(year)` through 31 May (at most 274 days).
10. **The tag is hashed before `generated_at` is set**, not over a copy with it cleared: protobuf-py's `copy.copy` is shallow, and clearing a nested field on the copy cleared the original (probe, 5 October).
11. **`GetDiaryCapabilities` in 3a is v1's answer in v2's shape**: `enabled`, every key of `registry.KEYS`, the NetSchool allow-list's password regions. `sign_in_methods` and `features` stay empty, because v1's answer has neither and what a provider declares is 3b's registry table to say (decision 12).
12. **`WatchClass` refuses after the gate on every target in 3a**, with `UNIMPLEMENTED` / `FEATURE_UNSUPPORTED` and `metadata.feature = "streaming"`: no 3a target streams. The test sets the `VERCEL` marker, as the design asks, and also leaves it unset. 3c adds the host's branch.
13. **The client header is a whole number of at most ten ASCII digits, from 1 to 2,100,000,000** (`gate.MAX_CLIENT_VERSION`): the ceiling `android/app/build.gradle.kts` puts on a versionCode, which is Google Play's. Anything else, with a minimum set, is `VALIDATION_FAILED` with a `BadRequest` naming `X-Lessons-Client`. Without a minimum it is ignored (decision 9). The digits are counted before `int` is asked, so a header of a million digits is never parsed.
14. **An unlinked phone on a method above viewer is `DEVICE_NOT_LINKED`; a linked account that is no member is `ROLE_REQUIRED`.** The role is read for every device method, because `GetMe` and the window's `DeviceAccess` answer it, and refused only above viewer.
15. **A class deleted under a live token is `RESOURCE_NOT_FOUND` with `resource = "class"`** and v1's «Class no longer exists». Not tested: the model cascades the device with the class.
16. **A REST body over 4 MB is the error Connect itself raises**: `RESOURCE_EXHAUSTED`, «message is larger than configured max 4194304», no reason, `429`. The body is read in chunks and abandoned at the limit.
17. **Query binding:** with `body: "*"` no field comes from the query (Google's rule); an unknown name is ignored as an unknown JSON field is; a `bool` reads `true`/`1`/`false`/`0`; a message-typed field other than `FieldMask`, `Timestamp` or `Duration` is undecodable from a query.
18. **«Diary reads» are every `GET` of `DiaryService`**, so `GetDiaryCapabilities` carries `Cache-Control: private, no-store` already.
19. **Effects** are `async` callables appended with `Call.after_commit`; they run after the commit inside the scope, and one that raises is logged and dropped. 3a registers none.
20. **The side-effect test listens for statements** (`before_cursor_execute`) and allows: `UPDATE device_tokens SET last_seen_at=? …`; `UPDATE diary_sessions SET` of `upstream_token`/`last_used_at` only; anything on `join_attempts` and `usage_counters`.
21. **No proto file changes.** The eight «REST answers 201» comments are a table in `rest/` (`CREATED`), held to the comments by a test.
22. **#302's v1 side is already fixed, by #303** (merged 5 October 2026, `aba88f8`): v1's `current_diary` answers «disabled» before it reads a token, and `services/diary.unusable` expires a session only when a configured key cannot open it. The v2 gate asks the same question first, and `find_session` — which the gate calls — no longer expires anything without a key. 3a's pull request does not mention #302; it is closed.
23. **The branch is `server-v2/3a`**, cut in this worktree from `origin/main` once #301 (the design and this plan) has merged, or from `server-v2/design` if the owner approves while #301 is open.
24. **The design's first table test is a list in `test_rpc_errors.py`, `HELD_BY`, and it lands in Task 10.** The design asks that «every row raises its exception through a real method and is read back on both paths». Nothing fails when a row is added without such a test, unless each row names one. So `HELD_BY` maps every exception in `errors.TABLE` to its test, as a file and a function, or to `"3b"` where no method 3a serves can raise it (`DiaryDisabled`). The test fails unless `set(HELD_BY) == set(TABLE)` and every named function is defined in its file. It reads the file with `ast` rather than importing it, because a test module may not import another (`test_test_imports.py`). It is added in Task 10, with the last of the tests it names (`test_v2_devices.py` is Task 9's, `test_v2_window.py` Task 10's), rather than grown task by task: before then there is nothing for most rows to name. From Task 10 on, a row 3b adds without its test fails.

## Global Constraints

- Paths: `server/app/rpc/` (`__init__.py`, `call.py`, `gate.py`, `errors.py`, `values.py`, `methods.py`, `handlers.py`, one module per proto service) and `server/app/rest/` (`__init__.py`, `errors.py`). Generated code stays under `server/app/contract/`, written only by `buf generate`.
- REST annotations are written `/v2/…` and served under `/api` (`/api/v2/…`); Connect and gRPC-Web under `/api/rpc/lessons.v2.<Service>/<Method>`. No path of v1's moves.
- `rpc/` and `rest/` may import `services/`, `models`, `schemas`, `schedule`, `wording`, `contract/`, `security`, `config`, `crypto`, `di`, `api/deps.py`, the diary registry (`app.providers.diary.registry`) and the NetSchool region list (`app.providers.netschool.regions`); never `app.bot`, `app.api.public`, `app.api.edit`, `app.api.manage` or `app.api.diary`. No `app.services` module reaches `app.rpc`, `app.rest` or `app.api`. The layering test of Task 5 forbids exactly the bot and the four v1 routers, followed through the rest of `app/`, so everything on the allowed list passes it.
- Codes → HTTP, `docs/api.md`'s table: `INVALID_ARGUMENT`, `FAILED_PRECONDITION` 400; `UNAUTHENTICATED` 401; `PERMISSION_DENIED` 403; `NOT_FOUND` 404; `ALREADY_EXISTS` 409; `RESOURCE_EXHAUSTED` 429; `INTERNAL` 500 (added by Task 4); `UNIMPLEMENTED` 501; `UNAVAILABLE` 503.
- Every success over REST is `200`, except the eight methods whose proto comment says «REST answers 201», which answer `201`; a `not_modified` answer is `304` with no body.
- `MIN_CLIENT_VERSION` is optional: empty or `0` means no minimum, and it is **not** added to `deployment_problems` or `disabled_features`. `docker-compose.yml` hands it to the server like every other setting (`tests/test_compose.py`). A missing `X-Lessons-Client` is never refused.
- A handler never commits; only `rpc/call.py` does. The writes services commit themselves (the throttles, `find_session`, `touch_last_seen`, the device token `join.join` mints) stay committed when a call is refused, on purpose.
- No exception's own text reaches a client: what the table does not know is `INTERNAL` with a fixed sentence, logged with its traceback.
- Language: English in code, comments, commit messages and documents; Russian only as product text, and in «guillemets» anywhere else. Comments say why.
- Commit messages: English sentences saying what the change makes the project do, no `feat:` prefix; a body with the reasoning and what is left uncovered; ending with exactly these two lines:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV`
- **The machine has faulty RAM.** One heavy job at a time: never run the suite while Gradle or another suite runs (`tasklist | grep -i java` first). The full suite runs **once**, at the end of each task. After a crash, scan for zero-filled files before trusting the tree.
- Commands (shell state does not persist, so every command spells these out):
  - `WT` is `/c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract`.
  - `python` is `$WT/server/.venv/Scripts/python.exe`, a venv made in the worktree's own `server/` exactly as CI makes it (`python -m venv .venv`, then `pip install -r ../requirements.txt -e ".[dev]"`; on Python 3.12, which CI runs). The main checkout's venv is refused: since #312 (#313) `tests/conftest.py` stops the run when `app`, or the editable install of `lessons-server`, is another tree's, under `python -m pytest` as much as bare.
  - `buf` is `/c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe` (1.73.0), run from `$WT`.
- Gates at the end of every task, from `$WT/server`: the task's own test files with `-p no:xdist`; `python -m ruff check app tests scripts migrations` → `All checks passed!`; `python -m mypy` → `Success: no issues found in N source files` (N is given per task, over a base of 197 modules: if mypy on the branch's first commit prints another number, shift every N by the difference; `app/contract` stays excluded); then `pytest -q -n auto`, bare, which is exactly CI's command, once (about ten minutes here) with no failure.
- Milestone 11 (`v0.10.0 — One contract: REST v2, Connect and native gRPC, build console`), under epic #273. Another agent may work in this tree: `git status` before touching a file you did not open.

## Review Focus

The five failure modes most likely to bite a person using v2, each with the test that pins it and the task that owns it:

1. **A phone revoked, or a role taken away, between two calls.** The gate reads the token and the role on every call, so the second call is refused although the first succeeded: `test_a_phone_revoked_between_two_calls_is_refused_on_the_second` (both paths, Task 8) and `test_a_role_taken_away_is_gone_on_the_next_call` (Task 5).
2. **`If-None-Match` with a weak tag, a list or `*`.** v2 must match exactly as v1's `_matches` did, or every phone refetches the year on every poll: `test_a_matching_tag_answers_not_modified_on_both_paths[exact|weak|listed|star]` and `test_a_stale_tag_gets_the_window` (Task 10); the header winning over a query parameter, `test_if_none_match_comes_from_the_header_which_wins_over_the_query` (Task 7).
3. **A class with no terms for the year asked.** The read must answer the conventional set and write nothing, the same days v1 answers after seeding: `test_every_day_is_v1_s_day_and_the_terms_v1_would_have_seeded` and `test_the_window_writes_nothing_where_v1_seeds_and_adopts` (Task 10), `test_a_year_with_no_terms_is_answered_the_conventional_set_and_writes_none` (Task 3).
4. **A password-shaped string in a request field, the bearer or the client header.** protobuf-py's own decoder quotes the value it refused, so a refusal that passed its text on would echo it: `test_no_refusal_repeats_what_was_sent[…]` over every served method, and `test_the_decoder_itself_would_have_quoted_it` (Task 8; `CreateDevice`'s `code` and `device_name` join it in Task 9, the window's `year` and `if_none_match` in Task 10).
5. **The join budget drawn by v1 and v2 alternately, or from two connections of one address.** A caller must not double its thirty wrong codes: `test_v1_and_v2_draw_on_one_budget` and `test_two_connections_from_one_address_are_one_caller` (Task 9), and `test_the_port_connectrpc_reports_is_stripped_ipv6_included` (Task 2).

## What was verified while writing this plan, and what was not

**Run, in a scratch copy of the worktree** (`C:\Users\lumen\.claude\jobs\c9e2d980\tmp\plan3a\`, never the worktree), on 5 October 2026:
- `buf generate` with the second input of Task 1 wrote exactly `google/rpc/{__init__,code_pb,error_details_pb,status_pb}.py` beside `google/api`, and nothing else changed (`diff -r`). `test_contract.py` passed with them.
- Every module and every test file of this plan, in its final form, with `ruff check` clean and `mypy` clean (214 source files). The new and changed test files — 165 new tests and the changed `test_contract.py`, `test_service_layering.py`, `test_hardening.py`, `test_api_extended.py` — passed, one file group at a time, without xdist.
- v1's own tests that touch the moved code passed unchanged against the moves: `test_hardening`, `test_join_modes`, `test_join_device_cap`, `test_directory`, `test_diary_session`, `test_diary_api`, `test_api`, `test_api_extended`, `test_school_year`, `test_di`, `test_api_docs`, `test_startup`, `test_contract`, `test_cold_start` (one test of `test_join_modes` cannot run in a scratch copy without `migrations/`).
- Tasks 2 to 8 were also staged one by one on a second copy, to check that each task's tests pass with only that task's code.
- **The scratch copy held a subset of `tests/`**: the seventeen existing files listed above and the eleven new ones. The other 52 of the worktree's 69 test files were absent, `test_compose.py` among them. That is how `MIN_CLIENT_VERSION`'s missing line in `docker-compose.yml` went unseen until the review. So the full-suite run at the end of each task, from Task 1's on, is the first time those 52 files meet that task's changes.
- **The review's fixes of 5 October were run in a third copy** (`C:\Users\lumen\.claude\jobs\c9e2d980\tmp\planfix\`, the final form plus `docker-compose.yml` and `test_compose.py`): ruff clean, mypy clean on 214 source files, and `test_compose`, `test_rpc_gate` (25), `test_rpc_errors` (12), `test_rpc_mount` (13), `test_rpc_call`, `test_service_layering`, `test_test_imports`, `test_env_example`, `test_v2_devices`, `test_v2_reads`, `test_v2_no_echo`, `test_join_device_cap`, `test_hardening`, `test_diary_api`, `test_diary_session`, `test_api`, `test_directory` and `test_cold_start` all passed, and so did `test_join_modes` but for the one test that needs `migrations/`. Each new test was seen to fail without its fix: `HELD_BY` with a row missing and with a test misnamed, and the guard's new test against the old `in ("1.0", "1.1")` check, where the library raised.
- Probed and relied on: `ConnectError(code, msg, details=[<generated message>])` renders a `debug` field beside the base64 `value`; `connectrpc` passes a decode failure through as a 500 unless the codec raises a `ConnectError`; it reports the peer as `f"{host}:{port}"`; a native gRPC request over HTTP/1.1 is a 500; a Starlette route path `{device_id}:revoke` binds `device_id` alone; `protobuf.message_from_json_value` reads numbers and enums from strings but a `bool` only from JSON booleans; `message_to_json_value(Any.pack(m), registry=Registry(error_details_pb.desc()))` writes `{"@type": …, fields…}`; protobuf-py's decoder messages quote the refused value; pydantic-settings refuses `MIN_CLIENT_VERSION=` for a plain `int`.
- Measured: importing the seventeen generated service modules after FastAPI and SQLAlchemy takes 117–180 ms here (warm and cold), more than the spike's 61 ms for the whole `app.main` delta.

**Not run:** the full suite (`-n auto`); Buf in CI; anything on Vercel or Postgres (`connectrpc`'s native wheels have never been imported there — the fail-safe mount is for exactly that); what `http_version` Vercel's Python bridge puts in the ASGI scope; a real phone.

## File map

| File | Task | What it holds |
| --- | --- | --- |
| `buf.gen.yaml` | 1 | a second input: googleapis' `google/rpc`, pinned to `buf.lock`'s commit |
| `server/app/contract/google/rpc/*` | 1 | generated `ErrorInfo`, `BadRequest`, `RetryInfo`, `Status`, `Code` |
| `server/app/security.py` | 2 | the four limiters, the device cap, `Throttled`, `DiaryAttempt` |
| `server/app/api/deps.py` | 2 | `header`, `caller_bucket(headers, peer)`, `peer_host`, `request_bucket`, `bearer`, `find_device`, `touch_last_seen` |
| `server/app/services/clock.py` | 2 | `MIN_DATE`, `MAX_DATE`, `in_bounds`, `now`, `today` |
| `server/app/wording.py` | 2, 3 | the sentences v1 and v2 both answer with: `DIARY_DISABLED_DETAIL` (moved from `api/diary.py`), the join's four |
| `server/app/api/cron.py`, `server/app/services/diary.py`, the design | 2 | the comments that named `_touch_last_seen` |
| `server/app/services/join.py` | 3 | the join flow, its refusals as facts |
| `server/app/services/window.py` | 3 | `etag_matches`, `strong_etag`, `for_year`, `FIRST_YEAR`/`LAST_YEAR` |
| `server/app/services/linking.py` | 3 | `Access`, `access_of` |
| `server/app/services/terms.py` | 3 | `TermSpan`, `spans` |
| `server/app/rpc/errors.py`, `server/app/rest/errors.py` | 4 | the error table; Google's error body |
| `server/app/rpc/{methods,values,gate,call,handlers}.py` | 5 | the method table, the gate, `invoke` |
| `server/app/config.py`, `server/.env.example`, `docker-compose.yml` | 5 | `MIN_CLIENT_VERSION` |
| `server/app/rpc/__init__.py`, `server/app/rpc/watch.py`, `server/app/main.py` | 6 | `rpc_app()`, the guards, `WatchClass`, `mount_v2` |
| `server/app/rest/__init__.py` | 7 | the transcoder |
| `server/app/rpc/{me,diary}.py` | 8 | `GetMe`, `GetDiaryCapabilities` |
| `server/app/rpc/device.py` | 9 | `CreateDevice` |
| `server/app/rpc/schedule.py` | 10 | `GetScheduleWindow` |
| `server/tests/conftest.py` | 5–7 | `v2_tokens`, the `v2` harness |
| `server/tests/test_rpc_errors.py` | 4, 5, 6, 10 | the table against `errors.proto`; `HELD_BY` (Task 10) |
| documents | 11 | `docs/api.md`, `docs/architecture.md`, `docs/README.md`, `docs/deploy.md`, `CLAUDE.md`, `README.md`, `CONTRIBUTING.md`, the `gates` skill, `HANDOVER.md`, `docs/history.md` |

---

## Task 1: Generate `google/rpc`, and hold its pin and its JSON

The design's decision 5: every refusal carries generated `ErrorInfo`, `BadRequest` and `RetryInfo`, so they are 3a's first commit.

**Files:**
- Modify: `buf.gen.yaml`
- Create (generated, never by hand): `server/app/contract/google/rpc/__init__.py`, `code_pb.py`, `error_details_pb.py`, `status_pb.py`
- Modify: `server/tests/test_contract.py`

**Interfaces:**
- Consumes: `buf.lock`'s googleapis commit `c17df5b2beca46928cc87d5656bd5343`.
- Produces: `app.contract.google.rpc.error_details_pb.ErrorInfo(reason: str, domain: str, metadata: dict[str, str])`, `BadRequest(field_violations=[BadRequest.FieldViolation(field: str, description: str)])`, `RetryInfo(retry_delay: protobuf.wkt.Duration)`; `status_pb.Status`; `code_pb.Code`.

- [ ] **Step 1: Start the branch, from a clean tree.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git status --short && git fetch origin && git switch -c server-v2/3a origin/main && git log --oneline -1
```
Expected: no output from `git status`, then the merge of #301 at the top. If #301 is still open and the owner has approved, use `git switch -c server-v2/3a origin/server-v2/design` instead (Ruling 23).

- [ ] **Step 2: Red.** In `server/tests/test_contract.py`, insert these two tests immediately before `def test_the_readers_see_what_they_are_written_for() -> None:`
```python
def test_google_rpc_is_generated_from_the_commit_buf_lock_pins() -> None:
    """The error model's details are googleapis' ``google/rpc``, generated as a
    second input of buf.gen.yaml. A module under ``inputs:`` is not resolved
    through buf.lock, so the commit is written twice; held level here, so that
    ``google/api`` and ``google/rpc`` never come from two different googleapis."""
    lock = (REPOSITORY / "buf.lock").read_text(encoding="utf-8")
    locked = re.search(r"name: buf\.build/googleapis/googleapis\s+commit: (\w+)", lock)
    config = (REPOSITORY / "buf.gen.yaml").read_text(encoding="utf-8")
    pinned = re.search(
        r"^\s*- module: buf\.build/googleapis/googleapis:(\w+)\s*$", config, re.MULTILINE
    )
    assert locked is not None and pinned is not None
    assert pinned.group(1) == locked.group(1)

    from app.contract.google.rpc import code_pb, error_details_pb, status_pb

    assert {"ErrorInfo", "BadRequest", "RetryInfo"} <= set(vars(error_details_pb))
    assert status_pb.Status.desc().type_name == "google.rpc.Status"
    assert code_pb.Code.desc().name == "Code"


def test_the_error_details_render_as_json_through_the_type_registry() -> None:
    """REST writes a refusal's details as JSON, not as Connect's base64: an
    ``Any`` rendered through protobuf-py's own type registry, ``@type`` first.
    The 5 October spike saw only Connect's form, so this is the first time the
    REST form is asked of the runtime that will write it."""
    from protobuf import Registry, message_to_json_value
    from protobuf.wkt import Any as AnyMessage
    from protobuf.wkt import Duration

    from app.contract.google.rpc import error_details_pb

    registry = Registry(error_details_pb.desc())
    info = error_details_pb.ErrorInfo(
        reason="ROLE_REQUIRED", domain="lessons.app", metadata={"role": "ROLE_ADMIN"}
    )
    retry = error_details_pb.RetryInfo(retry_delay=Duration(seconds=7))
    assert message_to_json_value(AnyMessage.pack(info), registry=registry) == {
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        "reason": "ROLE_REQUIRED",
        "domain": "lessons.app",
        "metadata": {"role": "ROLE_ADMIN"},
    }
    assert message_to_json_value(AnyMessage.pack(retry), registry=registry) == {
        "@type": "type.googleapis.com/google.rpc.RetryInfo",
        "retryDelay": "7s",
    }
```
Run:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m pytest -q -p no:xdist tests/test_contract.py -k "google_rpc or registry"
```
Expected: 2 failed — `assert locked is not None and pinned is not None` (no `- module:` line yet) and `ModuleNotFoundError: No module named 'app.contract.google.rpc'`.

- [ ] **Step 3: Write `buf.gen.yaml` in full.**
```yaml
# Python only: Kotlin and Java lite wait for sub-project 5, which adds them
# with the bindings that use them (the design's decision 3).
#
# Every remote plugin is pinned. An unpinned one takes Buf's latest release,
# and CI regenerates and fails on any difference, so a release on Buf's side
# would turn CI red with nothing changed here. Each pin is the floor of its
# runtime in requirements.in, and tests/test_contract.py holds the two level.
version: v2
# server/app/contract is deleted and rewritten whole, so nothing hand-written
# may live there.
clean: true
plugins:
  # Messages, for protobuf-py. include_imports brings google/api/annotations
  # and http along, which every service file imports; the well-known types are
  # protobuf-py's own (`protobuf.wkt.google.protobuf`) and are not generated.
  - remote: buf.build/bufbuild/py:v0.6.0
    out: server/app/contract
    include_imports: true
  # Services: connectrpc's interfaces and clients over those messages.
  - remote: buf.build/connectrpc/py:v0.12.1
    out: server/app/contract
inputs:
  # The contract: the workspace buf.yaml describes.
  - directory: .
  # The error model's details (google.rpc.ErrorInfo, BadRequest, RetryInfo),
  # which no file of the contract imports, so include_imports never brings
  # them (docs/specs/2026-10-05-server-v2-design.md, decision 5). A module
  # named here is not resolved through buf.lock, so its commit is written out:
  # buf.lock's googleapis commit, held level by tests/test_contract.py.
  - module: buf.build/googleapis/googleapis:c17df5b2beca46928cc87d5656bd5343
    paths:
      - google/rpc/code.proto
      - google/rpc/error_details.proto
      - google/rpc/status.proto
```

- [ ] **Step 4: Generate, and look at what changed.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe generate && git status --short --untracked-files=all server/app/contract
```
Expected: exactly four new files, `server/app/contract/google/rpc/__init__.py`, `code_pb.py`, `error_details_pb.py`, `status_pb.py`, and no modified file (the scratch run of 5 October produced this). A modified `lessons/v2` module means the input is not the commit the rest was generated from: stop.

- [ ] **Step 5: The freshness check, as CI's «Contract» job runs it.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe generate --output /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/regen-3a && diff -r --strip-trailing-cr --exclude=__pycache__ /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/regen-3a/server/app/contract server/app/contract && echo FRESH
```
Expected: `FRESH`. CI's job runs the same `buf generate --output`, which reads `inputs:` from the same file.

- [ ] **Step 6: Green.** The same command as Step 2. Expected: `2 passed`. Then the whole file: `python -m pytest -q -p no:xdist tests/test_contract.py` → `28 passed` (26 before, these two added; `test_every_generated_module_imports` and the header check now cover the three new modules too).

- [ ] **Step 7: Gates.** From `$WT/server`: `python -m ruff check app tests scripts migrations` → `All checks passed!` (ruff skips `app/contract`); `python -m mypy` → `Success: no issues found in 197 source files`; `pytest -q -n auto` (bare) → the previous count plus 2, no failure.

- [ ] **Step 8: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add buf.gen.yaml server/app/contract/google/rpc server/tests/test_contract.py && git commit -F - <<'EOF'
Generate google.rpc's error details beside google.api

Every v2 refusal carries a google.rpc.ErrorInfo, and some a BadRequest or
a RetryInfo; nothing generated them, and the 5 October spike had to
hand-encode the Any. buf.gen.yaml gains a second input, googleapis'
google/rpc code, error_details and status, generated beside google/api.

A module named under inputs: is not resolved through buf.lock, so the
googleapis commit is written out in buf.gen.yaml too; test_contract.py
holds the two level. A second test renders ErrorInfo and RetryInfo as JSON
through protobuf-py's type registry, the form REST will send, which the
spike never saw.

Not covered: nothing imports the new modules yet; the error table does in
the next commits.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 2: The neutral rules v1's routers held: the bucket, the bearer, the clock, the limiters

Decision 2: a rule a v1 router holds moves into `services/` (or `security`, or `api/deps.py`) before its v2 handler is written, v1 calls the moved code, and v1's answers do not move. This task moves the rules no service owns; Task 3 moves the join flow and the window's.

**Files:**
- Modify: `server/app/security.py` (append), `server/app/api/deps.py` (rewrite), `server/app/wording.py` (append), `server/app/api/cron.py` and `server/app/services/diary.py` (one comment each), `server/app/api/public.py`, `server/app/api/diary.py`, `server/app/api/directory.py`, `server/app/api/edit.py`
- Create: `server/app/services/clock.py`, `server/tests/test_shared_rules.py`
- Modify: `server/tests/test_hardening.py`, `server/tests/test_api_extended.py`, `docs/specs/2026-10-05-server-v2-design.md` (one name)

**Interfaces:**
- Produces:
  - `app.security`: `join_limiter`, `diary_login_limiter`, `diary_open_limiter`, `directory_limiter` (each a `JoinThrottle`), `MAX_DEVICES_PER_CLASS = 300`, `class Throttled(Exception)` with `.retry_after: float` and `.seconds -> int` (`int(retry_after) + 1`), `class DiaryAttempt` with `await DiaryAttempt.admit(session, *, failures_key: str, opened_key: str) -> DiaryAttempt` (raises `Throttled`), `.succeeded(session)`, `.failed(session)`, `.not_judged(session)`.
  - `app.api.deps`: `header(headers: Iterable[tuple[str, str]], name: str) -> str | None`; `caller_bucket(headers: Iterable[tuple[str, str]], peer: str | None, *, scope: str = "") -> str`; `peer_host(client_address: str | None) -> str | None`; `request_bucket(request: Request, *, scope: str = "") -> str`; `bearer(authorization: str | None) -> str | None`; `async find_device(session, token: str) -> DeviceToken | None`; `async touch_last_seen(session, device) -> None` (commits).
  - `app.services.clock`: `MIN_DATE`, `MAX_DATE`, `in_bounds(*days: date) -> bool`, `now(school_class) -> datetime`, `today(school_class) -> date`.
  - `app.wording.DIARY_DISABLED_DETAIL: str`, the first of the sentences v1 and v2 both answer with (Ruling 5).

- [ ] **Step 1: Red.** Create `server/tests/test_shared_rules.py`:
```python
"""The rules v1's routers held and v2 needs, now where both can reach them.

``caller_bucket`` over header lines and a peer, the bearer, the clock and the
date bounds, and one instance of each limiter
(``docs/specs/2026-10-05-server-v2-design.md``, decision 2). v1's own tests are
the proof that its answers did not move; these hold the shapes v2 adds.
"""

from __future__ import annotations

from datetime import UTC, date, datetime
from types import SimpleNamespace

import pytest
from starlette.datastructures import Headers

from app import security
from app.api import diary, directory, public
from app.api.deps import bearer, caller_bucket, peer_host, request_bucket
from app.config import get_settings
from app.security import client_bucket
from app.services import clock


def test_one_rule_buckets_a_starlette_request_and_a_list_of_header_lines(monkeypatch) -> None:
    monkeypatch.setattr(get_settings(), "trusted_proxy_hops", 1)
    raw = [(b"x-forwarded-for", b"10.0.0.1"), (b"x-forwarded-for", b"198.51.100.7")]
    request = SimpleNamespace(headers=Headers(raw=raw), client=SimpleNamespace(host="127.0.0.1"))
    lines = [("X-Forwarded-For", "10.0.0.1"), ("X-Forwarded-For", "198.51.100.7")]
    assert request_bucket(request, scope="diary:") == caller_bucket(
        lines, "127.0.0.1", scope="diary:"
    )
    assert caller_bucket(lines, "127.0.0.1") == client_bucket("198.51.100.7")


@pytest.mark.parametrize(
    ("reported", "host"),
    [
        ("203.0.113.9:4321", "203.0.113.9"),
        ("::1:52144", "::1"),
        ("2001:db8::7:443", "2001:db8::7"),
        ("testclient:50000", "testclient"),
        (None, None),
    ],
)
def test_the_port_connectrpc_reports_is_stripped_ipv6_included(reported, host) -> None:
    assert peer_host(reported) == host


def test_two_connections_from_one_address_are_one_bucket() -> None:
    assert caller_bucket([], peer_host("198.51.100.20:40001")) == caller_bucket(
        [], peer_host("198.51.100.20:40002")
    )


@pytest.mark.parametrize(
    ("header", "token"),
    [
        ("Bearer abc", "abc"),
        ("bearer abc", "abc"),
        ("Basic abc", None),
        ("Bearer ", None),
        (None, None),
    ],
)
def test_a_bearer_is_read_by_one_rule(header, token) -> None:
    assert bearer(header) == token


def test_every_limiter_is_one_instance_whichever_module_names_it() -> None:
    assert public.join_limiter is security.join_limiter
    assert public.MAX_DEVICES_PER_CLASS == security.MAX_DEVICES_PER_CLASS == 300
    assert diary.diary_login_limiter is security.diary_login_limiter
    assert diary.diary_open_limiter is security.diary_open_limiter
    assert directory.directory_limiter is security.directory_limiter


def test_the_class_clock_moves_today_with_it(monkeypatch, school_class) -> None:
    moment = datetime(2026, 9, 7, 23, 30, tzinfo=UTC)
    monkeypatch.setattr(clock, "now", lambda klass: moment.astimezone(klass.tz))
    # 23:30 UTC is already Tuesday in Moscow.
    assert clock.today(school_class) == date(2026, 9, 8)


def test_the_bounds_are_inclusive_and_v1_s() -> None:
    assert clock.in_bounds(date(2000, 1, 1), date(2100, 1, 1))
    assert not clock.in_bounds(date(1999, 12, 31))
    assert not clock.in_bounds(date(2026, 9, 1), date(2100, 1, 2))
```
Run `python -m pytest -q -p no:xdist tests/test_shared_rules.py` from `$WT/server`. Expected: collection error, `ImportError: cannot import name 'bearer' from 'app.api.deps'`.

- [ ] **Step 2: Append the limiters to `server/app/security.py`**, two blank lines after its last function (`_utcnow`):
```python
# ---------------------------------------------------------------------------
# The limiters, one instance each
#
# They lived in the routers that used them (`api/public.py`, `api/diary.py`,
# `api/directory.py`). v2's handlers may not import a v1 router, and a second
# instance of a limiter would be a second budget: a caller alternating the two
# versions would get thirty wrong join codes from each
# (`docs/specs/2026-10-05-server-v2-design.md`, decision 11). So each lives
# here, once, and the routers re-export the names their tests import.
# ---------------------------------------------------------------------------

#: Wrong join codes one caller may try in a quarter of an hour.
#:
#: Every guess is checked against *every* class at once, so the search space an
#: attacker has to beat is the code length alone, and what stands between them
#: and a permanent read token for somebody's timetable is this limit. Only
#: failures are counted, so a classroom joining from one school NAT is never
#: blocked by each other's successes.
join_limiter = JoinThrottle(limit=30, window=900.0)

#: The most live phones the class code will put into one class (#199). The
#: throttle counts only failures, so without this a caller holding a real code
#: could mint read tokens for ever, each one alive until 180 days of silence.
#:
#: Generous on purpose, because the refusal lands on a real family: thirty-odd
#: pupils, each with a phone of their own and up to two parents', and the
#: teachers who read the class, come to about 120 — and a row outlives the phone
#: it was minted for, since a reinstall, a cleared app or a new phone each join
#: again and leave the old token live until `cron.DEVICE_TOKEN_TTL` prunes it.
#: Doubling for that residue gives ~250; 300 is that with room to spare. A class
#: that really does reach it has an admin who can switch old phones off, and a
#: personal code from the bot is not counted against it at all.
MAX_DEVICES_PER_CLASS = 300

#: Failed diary sign-ins one caller may make in a quarter of an hour.
#:
#: The other door onto this same service counts even harder: `diary_web`
#: spends its one-time ticket *before* the sign-in, and its own comment says
#: why — «it is what stops whoever holds the URL guessing passwords against
#: the upstream from our address». `/diary/login` had no ticket and no limit at
#: all, so it was that oracle with the door held open: anybody could post a
#: login and a guess and read the answer off the status code, 401 for wrong
#: and 200 for right, as fast as they liked. Two things follow from that and
#: both are ours: credential stuffing against a third party's school diary
#: proxied through this server, and the upstream blocking this deployment's
#: address — which takes the feature down for every family on it, including
#: the `/diary/signin` page the ticket was protecting.
#:
#: Looser than `/join`'s thirty, because a parent who has forgotten which of
#: their two e-mail addresses the school has is a real person making real
#: mistakes, and only failures are counted.
diary_login_limiter = JoinThrottle(limit=10, window=900.0)

#: Sessions one caller may open in a quarter of an hour, through either door.
#:
#: The limit above counts failures only, so a caller whose every attempt
#: *succeeds* was never limited at all — and a success is not free. Each one
#: is a new row the cron keeps alive with a ping from this server's address,
#: for up to thirty days, and nothing ties a row to anything but itself: one
#: real «Сетевой город» session replayed into `/session` a few thousand times
#: was a few thousand rows, four bootstrap calls each, and a keep-alive queue
#: that pinged one account over and over from an address the region can block
#: for everybody. Twenty is a household's phones signing in again with room to
#: spare; the window is the other limiters', as `JoinThrottle` requires.
diary_open_limiter = JoinThrottle(limit=20, window=900.0)

#: Searches one caller may make in a quarter of an hour, **all** of them
#: counted — a search that finds its school has spent the same upstream request
#: as one that finds nothing. Twenty is a person trying spellings with room to
#: spare, and a school's NAT full of phones on an open day will meet it; they
#: then pick the region from the list, which is what the limit costs.
#:
#: The window is `/join`'s and the diary sign-in's, and has to be: the three
#: share one table, and every recorded attempt prunes the whole table to its
#: own window (``JoinThrottle``).
directory_limiter = JoinThrottle(limit=20, window=900.0)


class Throttled(Exception):
    """A door's limit is spent for this caller. Carries facts, never a sentence:
    each shell words it — v1 as a 429 with ``Retry-After``, v2 as ``THROTTLED``."""

    def __init__(self, retry_after: float) -> None:
        super().__init__("throttled")
        self.retry_after = retry_after

    @property
    def seconds(self) -> int:
        """Whole seconds to wait, rounded up and never 0: v1's ``Retry-After``
        and v2's ``retry_after_seconds`` are this one number."""
        return int(self.retry_after) + 1


class DiaryAttempt:
    """One attempt on either diary door, counted by both diary limiters until
    the outcome says which of the two it was.

    Counted *before* the upstream is asked, not after (see
    `JoinThrottle.admit`): recorded after, a burst of concurrent wrong
    passwords all read the count from before any of them and all reached the
    diary. The same limiter **and bucket** for `/login` and `/session`, so a
    caller who spent ten wrong passwords does not get ten more tries by
    session. Moved here from `api/diary.py` so that v2's `CreateDiarySession`
    counts on the same rows (3b).
    """

    __slots__ = ("failures", "opened")

    def __init__(self, failures: Admission, opened: Admission) -> None:
        self.failures = failures
        self.opened = opened

    @classmethod
    async def admit(
        cls, session: AsyncSession, *, failures_key: str, opened_key: str
    ) -> DiaryAttempt:
        """Count the attempt, or raise :class:`Throttled` while the caller has
        spent either limit. The keys are the caller's buckets under the scopes
        ``diary:`` and ``diary-open:``."""
        failures = await diary_login_limiter.admit(session, failures_key)
        if failures.retry_after is not None:
            raise Throttled(failures.retry_after)
        opened = await diary_open_limiter.admit(session, opened_key)
        if opened.retry_after is not None:
            await diary_login_limiter.forgive(session, failures)
            raise Throttled(opened.retry_after)
        return cls(failures, opened)

    async def succeeded(self, session: AsyncSession) -> None:
        """A session was opened: not a failure, and one of the twenty."""
        await diary_login_limiter.forgive(session, self.failures)

    async def failed(self, session: AsyncSession) -> None:
        """The upstream judged it and said no: a failure, and no session."""
        await diary_open_limiter.forgive(session, self.opened)

    async def not_judged(self, session: AsyncSession) -> None:
        """Nothing looked at what was sent — the feature off, the diary down,
        the address refused: neither."""
        await diary_login_limiter.forgive(session, self.failures)
        await diary_open_limiter.forgive(session, self.opened)
```
(`AsyncSession` and `Admission` are already in scope in that module.)

- [ ] **Step 3: Rewrite `server/app/api/deps.py` in full.**
```python
"""How a request says who it is: the device bearer and the caller's address.

Two shells read these rules — v1's FastAPI dependencies below, and the v2 gate
(``app/rpc/gate.py``), which has a list of header lines and a peer address
rather than a Starlette request. So every rule here is written over those two
plain things first, and the FastAPI dependencies are thin wrappers: one
reading of a bearer and one bucket per caller, whichever version of the API
asked.
"""

from __future__ import annotations

import logging
from collections.abc import Iterable
from datetime import UTC, datetime, timedelta

from dishka.integrations.fastapi import FromDishka, inject
from fastapi import Depends, Header, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
from app.models import DeviceToken, SchoolClass
from app.security import client_bucket, hash_token

log = logging.getLogger(__name__)

# ``last_seen_at`` is telemetry with day-level resolution at best. Writing it on
# every request turned a read-only API into one write transaction per poll,
# which on SQLite means a write lock taken on the widget's refresh interval.
LAST_SEEN_INTERVAL = timedelta(minutes=15)


def _utcnow() -> datetime:
    """Naive UTC, matching the naive ``DateTime`` columns the model declares."""
    return datetime.now(UTC).replace(tzinfo=None)


def header(headers: Iterable[tuple[str, str]], name: str) -> str | None:
    """Every line of header ``name``, joined back into the one list it means.

    RFC 9110 §5.3: several field lines of a comma-separated field mean the same
    as one line with the values joined. Reading only the first line is how a
    proxy that adds its own line rather than extending the caller's let the
    caller choose their own rate-limit bucket. ``None`` when there is no line.
    """
    wanted = name.lower()
    values = [value for key, value in headers if key.lower() == wanted]
    return ", ".join(values) if values else None


def _forwarded_entry(value: str | None, hops: int) -> str | None:
    """The address the outermost *trusted* proxy put into a forwarding header.

    Entries are appended left to right, so with ``hops`` proxies in front the
    one they added is ``hops`` places from the right. Everything to its left is
    whatever the caller chose to send, and is ignored. A header with fewer
    entries than there are proxies cannot have come through them, so it yields
    nothing rather than the closest match.
    """
    if not value:
        return None
    parts = [part.strip() for part in value.split(",")]
    parts = [part for part in parts if part]
    if len(parts) < hops:
        return None
    return parts[-hops]


def caller_bucket(
    headers: Iterable[tuple[str, str]], peer: str | None, *, scope: str = ""
) -> str:
    """Identifies the caller for rate-limiting purposes.

    Over the header lines and the peer address rather than a Starlette request,
    so that v1's endpoints and v2's gate measure one caller by one rule — and so
    that a caller alternating the two versions draws on one budget
    (``docs/specs/2026-10-05-server-v2-design.md``, decision 11).

    Reading the leftmost ``X-Forwarded-For`` entry is the usual advice and it is
    exactly wrong: that entry is whatever the client sent, so an attacker sets
    it themselves and lands in a fresh bucket on every request, defeating the
    limit they are being measured by. So no forwarding header is believed unless
    the deployment says how many proxies are in front of it.

    On Vercel the platform writes ``x-vercel-forwarded-for`` itself, replacing
    any copy the client sent, so that one is trustworthy with no configuration —
    and it has to be used, because there every request arrives from the same
    internal address and the peer would put the whole internet in one bucket.

    Otherwise the peer address is used, which is right when the app is run
    directly and, for anything in between, is what ``TRUSTED_PROXY_HOPS`` is for.

    @param scope keeps two families of failures apart — ten wrong diary
        passwords must not spend a phone's thirty join attempts. It is mixed
        into the address **before** the digest and never written in front of
        it: ``JoinAttempt.client_key`` is ``VARCHAR(64)`` and a SHA-256 hex
        digest is exactly 64 characters, so a prefix is a value Postgres
        refuses outright — ``value too long for type character varying(64)``,
        raised out of the insert that was supposed to *record* a failed
        sign-in. SQLite ignores the width, which is why the whole of this was
        green here and 500 there.
    """
    lines = list(headers)
    settings = get_settings()

    address: str | None = None
    if settings.behind_vercel:
        address = _forwarded_entry(header(lines, "x-vercel-forwarded-for"), 1)

    if address is None:
        hops = settings.trusted_proxy_hops
        if hops > 0:
            address = _forwarded_entry(header(lines, "x-forwarded-for"), hops)

    if address is None:
        address = peer or "unknown"

    return client_bucket(f"{scope}{address}")


def peer_host(client_address: str | None) -> str | None:
    """The host of a peer that ``connectrpc`` reports as ``"host:port"``.

    Its ``RequestContext.client_address`` is ``f"{host}:{port}"`` whenever the
    server knows the client, an IPv6 host included and without brackets
    (``"::1:52144"``), and ``None`` otherwise. The port is a different number
    on every connection, so a bucket keyed on it would give each connection a
    budget of its own. It cannot be told apart from the address by looking —
    ``"::1:4321"`` is itself a valid IPv6 address — which is why this strips by
    the format the library writes rather than by parsing the result.
    """
    if not client_address:
        return None
    host, separator, _port = client_address.rpartition(":")
    return host if separator else client_address


def request_bucket(request: Request, *, scope: str = "") -> str:
    """:func:`caller_bucket` for a v1 endpoint, which holds a Starlette request."""
    return caller_bucket(
        request.headers.items(), request.client.host if request.client else None, scope=scope
    )


def bearer(authorization: str | None) -> str | None:
    """The token of ``Authorization: Bearer <token>``, or ``None``.

    The scheme is matched without regard to case, as RFC 9110 has it; anything
    that is not a bearer, or a bearer with nothing after it, is no credential.
    """
    scheme, _, token = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not token:
        return None
    return token


async def find_device(session: AsyncSession, token: str) -> DeviceToken | None:
    """The live device behind a bearer token, or ``None`` for an unknown or
    revoked one. Looked up by hash: the token itself is never stored."""
    return await session.scalar(
        select(DeviceToken).where(
            DeviceToken.token_hash == hash_token(token),
            DeviceToken.revoked.is_(False),
        )
    )


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
    try:
        await session.commit()
    except SQLAlchemyError:
        # Recording that a device phoned home is not worth failing the read the
        # widget actually asked for.
        log.warning("could not record last_seen_at for device %s", device.id, exc_info=True)
        await session.rollback()
        # A rollback expires every instance in the session, including the class
        # this request is about to serialise - and an expired instance in an
        # async session reloads itself lazily, which raises MissingGreenlet from
        # whatever attribute the endpoint touches next. So the failure this
        # branch exists to absorb used to turn into a 500 anyway. Refreshing
        # here brings the rows back inside the greenlet that can do the I/O.
        #
        # And the refresh itself may not raise. It is a SELECT down the
        # connection the commit just lost, so when the commit failed it
        # usually fails too - and this is the last statement of a branch whose
        # entire purpose is that a failed write does not fail the read the
        # widget asked for. A refresh that fails leaves `device` expired, which
        # is where it was before any of this; raising would throw away the read
        # as well. `services/diary.py:_refresh_quietly` is the same call with
        # the same guard.
        try:
            await session.refresh(device)
        except SQLAlchemyError:
            log.warning("could not refresh device %s after a rollback", device.id, exc_info=True)


@inject
async def current_device(
    authorization: str = Header(default=""),
    *,
    session: FromDishka[AsyncSession],
) -> DeviceToken:
    """The device behind the bearer token, or 401.

    Separate from :func:`current_class` because the linking endpoints act on
    the device row itself, and the write endpoints derive their permission
    from ``device.telegram_id``. FastAPI caches a dependency's result for the
    request, so an endpoint that asks for both the device and the class still
    costs one token lookup.
    """
    token = bearer(authorization)
    if token is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    device = await find_device(session, token)
    if device is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return device


@inject
async def current_class(
    device: DeviceToken = Depends(current_device),
    *,
    session: FromDishka[AsyncSession],
) -> SchoolClass:
    await touch_last_seen(session, device)
    # After the write, not before: a rollback inside it expires everything the
    # session holds, and this is the object the endpoint is about to read.
    school_class = await session.get(SchoolClass, device.class_id)
    if school_class is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Class no longer exists")
    return school_class
```
  `_touch_last_seen` is `touch_last_seen` now, because the gate calls it from outside the module. Three places name it by its old, private name, and follow it:
  1. `server/app/api/cron.py`, the comment above `DEVICE_TOKEN_TTL`: in its last line, «# least every fifteen minutes it is read (``deps._touch_last_seen``).», `_touch_last_seen` becomes `touch_last_seen`.
  2. `server/app/services/diary.py`, the comment that ends «carries the same refresh, for the same reason and after the same outage.»: `` `api/deps.py:_touch_last_seen` `` becomes `` `api/deps.py:touch_last_seen` ``.
  3. `docs/specs/2026-10-05-server-v2-design.md`, «What the code is today»: the bullet «`last_seen_at` (`deps._touch_last_seen`);» becomes «`last_seen_at` (`deps.touch_last_seen`);».

  Then, from `$WT`, `grep -rn --include=*.py "_touch_last_seen" server/app` and `grep -n "_touch_last_seen" docs/specs/2026-10-05-server-v2-design.md` print nothing. (`docs/history.md` keeps the name it had when its batch was written, and this plan quotes the old name in this step.)

- [ ] **Step 4: Create `server/app/services/clock.py`.**
```python
"""The class's wall clock, and the dates a request may name.

Both lived in ``api/public.py``, where ``edit.py`` and ``diary.py`` imported
them from a router, and where a v2 handler could not reach them without
importing v1 (``docs/specs/2026-10-05-server-v2-design.md``, decision 2). They
are one answer for every shell: a bound that two copies hold is a bound that
one day disagrees with itself.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import datetime

from app.models import SchoolClass

#: The widest dates a request may name. Every date a client sends is arbitrary
#: input, and the resolver does arithmetic on top of it — up to a year
#: forward, then three weeks of look-ahead — which near ``date.max`` raises
#: OverflowError: a 500 out of a query string. These are the dates a school
#: timetable can plausibly mean, and everything outside them is refused with
#: a sentence saying so.
MIN_DATE = Date(2000, 1, 1)
MAX_DATE = Date(2100, 1, 1)


def in_bounds(*days: Date) -> bool:
    """Whether every one of ``days`` is a date a request may name."""
    return all(MIN_DATE <= day <= MAX_DATE for day in days)


def now(school_class: SchoolClass) -> datetime:
    """The class's wall clock, in its own zone rather than the server's.

    A function rather than an expression at each call site, so that a test
    pins it to a Monday morning in one place: ``today`` reads it through this
    module's globals, so patching ``clock.now`` moves both.
    """
    return datetime.now(school_class.tz)


def today(school_class: SchoolClass) -> Date:
    """The class's date, which is not the server's when they are hours apart."""
    return now(school_class).date()
```

- [ ] **Step 5: Move the diary's «disabled» sentence into `server/app/wording.py`** (Ruling 5): v2's error table may not import `api/diary.py`, and a service carries facts, not sentences. Append at the end of the file, after `render_day`'s `    return clamp(lines)`:
```python


# ---------------------------------------------------------------------------
# What v1 and v2 both answer with
#
# A service refuses with an exception carrying facts, never a sentence, and
# each shell words it. Where v1's endpoint and v2's error table word one
# refusal alike — v2's message is v1's sentence wherever v1 had one
# (docs/specs/2026-10-05-server-v2-design.md, decision 5) — the sentence is
# here, once, so the two cannot drift. Some are English, as v1's generic
# answers always were.
# ---------------------------------------------------------------------------

#: A deployment without ``DIARY_SECRET``, on every door: v1's 503 and v2's
#: ``DIARY_DISABLED`` alike.
DIARY_DISABLED_DETAIL = "Дневник на этом сервере выключен."
```
(`app.bot.render` imports back a fixed list of `wording`'s names, held by `test_service_layering.py`; these are not the bot's and are not added to it.)

- [ ] **Step 6: `server/app/api/public.py` calls the moved rules.** Seven edits:
  1. Replace `from app.api.deps import current_class, current_device` with `from app.api.deps import current_class, current_device, request_bucket`.
  2. Replace `from app.security import JoinThrottle, client_bucket, hash_token, new_token` with `from app.security import MAX_DEVICES_PER_CLASS, hash_token, join_limiter, new_token`.
  3. Replace `from app.services import audit, device_invites, linking` with `from app.services import audit, clock, device_invites, linking`.
  4. Delete the block that begins `# ``start`` is arbitrary client input and the resolver does date arithmetic on` and ends `MAX_BUNDLE_START = Date(2100, 1, 1)`, with the blank line after it.
  5. Delete everything from the line `# Every guess is checked against *every* class at once, so the search space an` down to, not including, `def _to_day_out(day: ResolvedDay) -> DayOut:` — that is `join_limiter`, `MAX_DEVICES_PER_CLASS`, `_forwarded_list`, `_forwarded_entry` and `caller_bucket`, all now in `security.py` and `deps.py`.
  6. In `join`, replace `    client = caller_bucket(request)` with `    client = request_bucket(request)`.
  7. In `bundle`, replace
```python
    if start is not None and not (MIN_BUNDLE_START <= start <= MAX_BUNDLE_START):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"start must be between {MIN_BUNDLE_START.isoformat()} "
                f"and {MAX_BUNDLE_START.isoformat()}"
            ),
        )
```
  with
```python
    if start is not None and not clock.in_bounds(start):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"start must be between {clock.MIN_DATE.isoformat()} "
                f"and {clock.MAX_DATE.isoformat()}"
            ),
        )
```
  and in `_check_bounds` replace
```python
    if any(not (MIN_BUNDLE_START <= day <= MAX_BUNDLE_START) for day in days):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"dates must be between {MIN_BUNDLE_START.isoformat()} "
                f"and {MAX_BUNDLE_START.isoformat()}"
            ),
        )
```
  with
```python
    if not clock.in_bounds(*days):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"dates must be between {clock.MIN_DATE.isoformat()} "
                f"and {clock.MAX_DATE.isoformat()}"
            ),
        )
```
  Then delete the two functions `_today` and `_now` (from `def _today(school_class: SchoolClass) -> Date:` to the blank lines before `# ---…` «The device itself»), and replace their three callers: `    start = from_ or _today(school_class)` → `    start = from_ or clock.today(school_class)`; `    at = _now(school_class)` → `    at = clock.now(school_class)`; and in `calendar_feed` `    today = _today(school_class)` → `    today = clock.today(school_class)`.

- [ ] **Step 7: `server/app/api/diary.py`.**
  1. Replace `from app.api.public import MAX_BUNDLE_START, MIN_BUNDLE_START, caller_bucket` with the two lines `from app import wording` and `from app.api.deps import request_bucket`.
  2. Replace `from app.security import Admission, JoinThrottle` and the two lines after it (`from app.services import diary as service`, `from app.services import diary_corrections`) with:
```python
from app.security import DiaryAttempt, Throttled
from app.security import diary_login_limiter as diary_login_limiter
from app.security import diary_open_limiter as diary_open_limiter
from app.services import clock, diary_corrections
from app.services import diary as service
```
  3. Replace the comment and assignments of `MIN_DATE`/`MAX_DATE`:
```python
#: The widest dates a request may name — literally `/bundle`'s own pair,
#: `services/clock.py`'s, imported rather than repeated, because a second copy
#: of a bound that must agree with the first is a bound that eventually does
#: not.
#:
#: They are needed here for the same reason: `from` is arbitrary client input
#: and the default window is `start + 14 days` on top of it, which within a
#: fortnight of `date.max` raises OverflowError — a 500 out of a query string,
#: where every other bad date on this surface is a 422 saying what was wrong.
MIN_DATE = clock.MIN_DATE
MAX_DATE = clock.MAX_DATE
```
  4. Replace `#: The detail of the 503 a deployment without ``DIARY_SECRET`` answers.` and the line `DISABLED_DETAIL = "Дневник на этом сервере выключен."` with:
```python
#: The detail of the 503 a deployment without ``DIARY_SECRET`` answers, in the
#: words v2's ``DIARY_DISABLED`` uses too (``app/wording.py``).
DISABLED_DETAIL = wording.DIARY_DISABLED_DETAIL
```
  5. Replace everything from `#: Failed diary sign-ins one caller may make in a quarter of an hour.` down to, not including, `def _throttled(retry_after: float) -> HTTPException:` — the two limiters, `_THROTTLED_DETAIL` and `class _Attempt` — with:
```python
#: The two diary limiters and the attempt they count are `app.security`'s:
#: v2's `CreateDiarySession` (3b) counts on the same rows, so a caller cannot
#: double its guesses by alternating versions (the server-v2 design, decision
#: 11). Imported above under their own names, which the tests read here.

_THROTTLED_DETAIL = "Слишком много попыток входа. Попробуйте позже."


async def _admit(session: AsyncSession, request: Request) -> DiaryAttempt:
    """Count the attempt on both diary limiters, or 429 while either is spent."""
    try:
        return await DiaryAttempt.admit(
            session,
            failures_key=request_bucket(request, scope="diary:"),
            opened_key=request_bucket(request, scope="diary-open:"),
        )
    except Throttled as refusal:
        raise _throttled(refusal.retry_after) from None


```
  6. In `login`, replace the comment line
```python
    # The buckets are `_Attempt.admit`'s, asked for through `caller_bucket`
```
  with
```python
    # The buckets are `_admit`'s, asked for through `deps.caller_bucket`
```
  and in `login` and in `register_session` replace `    attempt = await _Attempt.admit(session, request)` with `    attempt = await _admit(session, request)` (two places).

- [ ] **Step 8: `server/app/api/directory.py`.** Replace `from app.api.public import caller_bucket` with `from app.api.deps import request_bucket`; replace `from app.security import JoinThrottle` with `from app.security import directory_limiter as directory_limiter`; replace the comment block that begins `#: Searches one caller may make in a quarter of an hour, **all** of them` and the line `directory_limiter = JoinThrottle(limit=20, window=900.0)` with:
```python
# `directory_limiter` is `app.security`'s, the one instance v1 and v2 share
# (the server-v2 design, decision 11); imported above under its own name so
# that this module, and the tests that read it here, keep it.
```
  and in `school_regions` replace ``see `caller_bucket` for why a`` with ``see `deps.caller_bucket` for why a``, and `    client = caller_bucket(request, scope="directory:")` with `    client = request_bucket(request, scope="directory:")`.

- [ ] **Step 9: `server/app/api/edit.py`.** Replace `from app.api.public import MAX_BUNDLE_START, MIN_BUNDLE_START, _homework_out, _today` with `from app.api.public import _homework_out`; replace `from app.services import audit, linking, notify, subjects, timetable_edit` with `from app.services import audit, clock, linking, notify, subjects, timetable_edit`; in `_check_date` replace `    if not (MIN_BUNDLE_START <= day <= MAX_BUNDLE_START):` with `    if not clock.in_bounds(day):` and the two f-string lines with `                f"date must be between {clock.MIN_DATE.isoformat()} "` / `                f"and {clock.MAX_DATE.isoformat()}"`; then replace all six `_today(school_class)` with `clock.today(school_class)`.

- [ ] **Step 10: The two tests that named the moved helpers follow them.**
  - `server/tests/test_hardening.py`: replace the import block
```python
from app.api.public import (
    MAX_BUNDLE_START,
    MIN_BUNDLE_START,
    caller_bucket,
    join_limiter,
)
```
    with `from app.api.deps import request_bucket` and `from app.api.public import join_limiter`; add `from app.services.clock import MAX_DATE, MIN_DATE` after `from app.services import terms as terms_service`; replace the six calls `caller_bucket(` with `request_bucket(`, the docstring «Enough of a Request for caller_bucket» with «Enough of a Request for request_bucket», and `[MIN_BUNDLE_START, MAX_BUNDLE_START, date(2026, 9, 7)]` with `[MIN_DATE, MAX_DATE, date(2026, 9, 7)]`.
  - `server/tests/test_api_extended.py`: replace `from app.api import cron, edit, public` with `from app.api import cron, edit`; replace `from app.services import linking, subjects` with `from app.services import clock, linking, subjects`; in `_pin_clock` replace `monkeypatch.setattr(public, "_now", lambda school_class: at.astimezone(school_class.tz))` with `monkeypatch.setattr(clock, "now", lambda school_class: at.astimezone(school_class.tz))`. Patching `clock.now` moves `clock.today` too, which `public` and `edit` both read.

- [ ] **Step 11: Green, and v1 unchanged.** From `$WT/server`:
```bash
/c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m pytest -q -p no:xdist tests/test_shared_rules.py tests/test_hardening.py tests/test_join_device_cap.py tests/test_join_modes.py tests/test_directory.py tests/test_diary_session.py tests/test_diary_api.py tests/test_api_extended.py tests/test_api.py
```
Expected: all pass (`test_shared_rules.py` is 15 of them). `test_directory`'s `test_every_throttle_on_the_attempts_table_uses_one_window` now finds all four `JoinThrottle(...)` in `security.py` and resolves them to the objects v1's modules re-export.

- [ ] **Step 12: Gates.** ruff `All checks passed!` (it accepts `import x as x` as a re-export); mypy `Success: no issues found in 198 source files`; the full suite once, previous count plus 15.

- [ ] **Step 13: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/security.py server/app/api/deps.py server/app/wording.py server/app/api/cron.py server/app/services/clock.py server/app/services/diary.py server/app/api/public.py server/app/api/diary.py server/app/api/directory.py server/app/api/edit.py server/tests/test_shared_rules.py server/tests/test_hardening.py server/tests/test_api_extended.py docs/specs/2026-10-05-server-v2-design.md && git commit -F - <<'EOF'
Move the bucket, the bearer, the clock and the limiters out of v1's routers

v2's handlers may not import a v1 router, and a copy of a rule beside it is
a rule that drifts. So the rules that lived in api/public.py and
api/diary.py and that v2 needs move where both versions can reach them,
and v1 calls them:

- caller_bucket now reads header lines and a peer host rather than a
  Starlette request, so a Connect call is bucketed by the same rule;
  request_bucket is v1's wrapper, and peer_host strips the port connectrpc
  writes after every peer, IPv6 included;
- the bearer and the device lookup are plain functions current_device
  calls, and the gate will; touch_last_seen loses its underscore, and
  the comments that named it follow;
- the class's clock and the date bounds are services/clock.py;
- the four limiters, the device cap and the diary's attempt are
  security.py's, one instance each, re-exported under the names v1's
  tests import, so v1 and v2 will draw on one budget;
- the diary's «disabled» sentence is app/wording.py's, the first of the
  sentences both versions answer with: a service carries facts, never a
  sentence.

v1's answers do not move: every sentence, status and header is the same,
and its own tests are the proof.

Not covered: nothing of v2 calls these yet.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 3: The join flow and the window's rules, in `services/`

**Files:**
- Create: `server/app/services/join.py`, `server/app/services/window.py`, `server/tests/test_services_window.py`
- Modify: `server/app/wording.py` (append), `server/app/services/linking.py`, `server/app/services/terms.py`, `server/app/api/public.py`

**Interfaces:**
- Consumes: Task 2's `security.join_limiter`, `MAX_DEVICES_PER_CLASS`, `Throttled`; `services.clock`; Task 2's «What v1 and v2 both answer with» section of `app/wording.py`.
- Produces:
  - `app.wording`: `JOIN_THROTTLED_DETAIL`, `JOIN_UNKNOWN_CODE_DETAIL`, `JOIN_INVITE_ONLY_DETAIL`, `JOIN_DEVICE_LIMIT_DETAIL` (Ruling 5).
  - `app.services.join`, which holds no sentence: `class JoinRefused(Exception)`; `JoinThrottled(JoinRefused, Throttled)`, `JoinCodeUnknown`, `ClassInviteOnly`, `DeviceLimitReached` (`.limit = 300`); `@dataclass(frozen) Joined(token: str, school_class: SchoolClass)`; `async live_devices(session, class_id) -> int`; `async join(session, *, code: str, device_name: str | None, client_key: str) -> Joined` (commits).
  - `app.services.window`: `FIRST_YEAR = 2000`, `LAST_YEAR = 2098`, `class YearOutOfBounds(ValueError)`, `@dataclass(frozen) Window(school_class, scheme: TermKind, terms: list[TermSpan], days: list[ResolvedDay], access: Access)`, `year_in_bounds(year) -> bool`, `async for_year(session, school_class, year, *, access: Access) -> Window`, `strong_etag(canonical: str) -> str`, `etag_matches(if_none_match: str | None, etag: str) -> bool`.
  - `app.services.linking`: `@dataclass(frozen) Access(linked: bool, role: Role | None)` with `.can_edit -> bool` and `Access.of(device, role) -> Access`; `async access_of(session, device) -> Access`.
  - `app.services.terms`: `@dataclass(frozen) TermSpan(index: int, kind: TermKind, starts_on: date, ends_on: date)` with `TermSpan.of(term)`; `async spans(session, school_class, year) -> list[TermSpan]` (writes nothing).

- [ ] **Step 1: Red.** Create `server/tests/test_services_window.py`:
```python
"""The window's rules as ``services/`` holds them: terms that write nothing, the
year's bounds, the access a device is told, and the tag.

v1's ``/join`` and ``/bundle`` tests are the proof that the moves kept v1's
answers; this holds what v2 adds on top of them
(``docs/specs/2026-10-05-server-v2-design.md``, decisions 2 and 10).
"""

from __future__ import annotations

import hashlib
from datetime import date

import pytest
from sqlalchemy import func, select

from app.models import DeviceToken, JoinAttempt, Role, SchoolClass, Term, TermKind
from app.schedule import school_year_end, school_year_start
from app.services import clock, join, window
from app.services import terms as terms_service
from app.services.linking import Access


async def test_a_year_with_no_terms_is_answered_the_conventional_set_and_writes_none(
    session, school_class
) -> None:
    spans = await terms_service.spans(session, school_class, 2026)
    assert [(s.index, s.kind, s.starts_on, s.ends_on) for s in spans] == [
        (1, TermKind.QUARTER, date(2026, 9, 1), date(2026, 10, 31)),
        (2, TermKind.QUARTER, date(2026, 11, 1), date(2026, 12, 31)),
        (3, TermKind.QUARTER, date(2027, 1, 1), date(2027, 3, 22)),
        (4, TermKind.QUARTER, date(2027, 3, 23), date(2027, 5, 31)),
    ]
    assert not session.new
    await session.commit()
    assert await session.scalar(select(func.count()).select_from(Term)) == 0


async def test_a_senior_class_is_answered_half_years(session, school_class) -> None:
    klass = await session.get(SchoolClass, school_class.id)
    klass.grade = 11
    spans = await terms_service.spans(session, klass, 2026)
    assert [(s.kind, s.ends_on) for s in spans] == [
        (TermKind.SEMESTER, date(2026, 12, 31)),
        (TermKind.SEMESTER, date(2027, 5, 31)),
    ]


async def test_stored_terms_are_answered_as_they_are(session, school_class) -> None:
    stored = await terms_service.ensure(session, school_class, 2026)
    stored[0].ends_on = date(2026, 10, 26)
    await session.commit()
    spans = await terms_service.spans(session, school_class, 2026)
    assert spans[0].ends_on == date(2026, 10, 26)


def test_the_first_and_last_years_lie_inside_the_bounds_and_their_neighbours_do_not() -> None:
    first, last = window.FIRST_YEAR, window.LAST_YEAR
    assert clock.in_bounds(school_year_start(first), school_year_end(last))
    assert not clock.in_bounds(school_year_end(last + 1))
    assert not clock.in_bounds(date(first - 1, 12, 31))


@pytest.mark.parametrize("year", [0, -1, 1999, 2099, 10000])
async def test_a_year_out_of_bounds_is_refused_before_a_date_is_built(
    session, school_class, year
) -> None:
    with pytest.raises(window.YearOutOfBounds):
        await window.for_year(session, school_class, year, access=Access(linked=False, role=None))


@pytest.mark.parametrize(
    ("role", "can_edit"),
    [
        (None, False),
        (Role.VIEWER, False),
        (Role.EDITOR, True),
        (Role.ADMIN, True),
        (Role.OWNER, True),
    ],
)
def test_editing_is_editor_or_above(role, can_edit) -> None:
    assert Access(linked=role is not None, role=role).can_edit is can_edit


def test_access_of_an_unlinked_device_says_so() -> None:
    assert Access.of(DeviceToken(token_hash="x", class_id=1), None) == Access(
        linked=False, role=None
    )


@pytest.mark.parametrize(
    ("sent", "matches"),
    [
        ('"abc"', True),
        ('W/"abc"', True),
        ('"other", "abc"', True),
        ("*", True),
        ('"other"', False),
        (None, False),
        ("", False),
    ],
)
def test_a_tag_matches_as_v1_s_if_none_match_did(sent, matches) -> None:
    assert window.etag_matches(sent, '"abc"') is matches


def test_a_tag_is_a_quoted_sha_256_of_the_text() -> None:
    text = '{"days":[]}'
    assert window.strong_etag(text) == '"' + hashlib.sha256(text.encode()).hexdigest() + '"'


async def test_the_join_flow_counts_a_wrong_code_and_forgives_a_right_one(
    session, school_class
) -> None:
    with pytest.raises(join.JoinCodeUnknown):
        await join.join(session, code="NOSUCH99", device_name=None, client_key="k" * 64)
    assert await session.scalar(select(func.count()).select_from(JoinAttempt)) == 1
    joined = await join.join(session, code="test42", device_name="Pixel", client_key="k" * 64)
    assert joined.school_class.id == school_class.id
    assert await session.scalar(select(func.count()).select_from(JoinAttempt)) == 1
```
Run `python -m pytest -q -p no:xdist tests/test_services_window.py`. Expected: collection error, `ImportError: cannot import name 'join' from 'app.services'`.

- [ ] **Step 2: The join's four sentences, and `server/app/services/join.py`.** The sentences v1's `/join` answers with go into `server/app/wording.py` first, appended at the end of the file, below Task 2's `DIARY_DISABLED_DETAIL` (Ruling 5):
```python

#: v1's ``POST /join`` and v2's ``CreateDevice``, for each refusal of
#: ``services/join.py``: too many wrong codes, a code that names nothing, a
#: class that takes personal codes only, and a class at its phone limit.
JOIN_THROTTLED_DETAIL = "Too many join attempts"
JOIN_UNKNOWN_CODE_DETAIL = "Unknown join code"
JOIN_INVITE_ONLY_DETAIL = "Этот класс принимает только по личному приглашению из бота"
JOIN_DEVICE_LIMIT_DETAIL = (
    "К классу подключено слишком много телефонов. Возьмите личный код в боте "
    "(«📱 Подключить телефон») или попросите администратора отключить старые телефоны"
)
```
  Then create `server/app/services/join.py`. The flow is v1's `/join`, line for line, with its comments; the refusals become exceptions carrying facts, and the commit stays where v1's was.
```python
"""Getting a phone into a class: a code in, a device token out.

This was the body of v1's ``POST /join`` (``api/public.py``). It moved here so
that v2's ``CreateDevice`` is the same implementation over the same throttle
(``docs/specs/2026-10-05-server-v2-design.md``, decisions 2 and 11): a caller
who alternates the two versions draws on one budget of thirty wrong codes, not
on two. A refusal is an exception carrying facts, never a sentence, and each
shell words it — v1's ``HTTPException`` details, v2's error table — with the
sentences ``app/wording.py`` keeps for both.

It commits, as the endpoint it came from did, and for the same reason the
throttles do: the attempt that :meth:`JoinThrottle.admit` counted has to be
durable before the code is even looked at, and a refusal that leaves it
counted must leave it counted whatever the caller then rolls back.
"""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import DeviceInvite, DeviceToken, JoinMode, SchoolClass
from app.security import (
    MAX_DEVICES_PER_CLASS,
    Throttled,
    hash_token,
    join_limiter,
    new_token,
)
from app.services import audit, device_invites


class JoinRefused(Exception):
    """A code that bought no token. Each subclass is one answer a shell words.

    The limiter and the device cap are ``security``'s (decision 11 of the
    server-v2 design): one instance each, whichever version asked."""


class JoinThrottled(JoinRefused, Throttled):
    """Too many wrong codes from this caller inside the window: the join
    limiter's own refusal, so a shell can word it as v1 did."""


class JoinCodeUnknown(JoinRefused):
    """Neither a class code nor a live personal code. The one refusal that
    stays counted against the caller."""


class ClassInviteOnly(JoinRefused):
    """A real class code for a class that admits personal codes only."""


class DeviceLimitReached(JoinRefused):
    """The class code has put :data:`MAX_DEVICES_PER_CLASS` live phones in."""

    limit = MAX_DEVICES_PER_CLASS


@dataclass(frozen=True)
class Joined:
    """What a successful join hands back: the token, shown once, and its class."""

    token: str
    school_class: SchoolClass


async def live_devices(session: AsyncSession, class_id: int) -> int:
    """Phones that can still read the class. A revoked row is a tombstone kept
    so its token goes on failing, and holds no phone."""
    count = await session.scalar(
        select(func.count())
        .select_from(DeviceToken)
        .where(DeviceToken.class_id == class_id, DeviceToken.revoked.is_(False))
    )
    return count or 0


async def join(
    session: AsyncSession, *, code: str, device_name: str | None, client_key: str
) -> Joined:
    """Exchange a code for a long-lived device token. Commits.

    Read-only when the code is the class's: a token with no Telegram account
    behind it is refused by every write path. A personal invite from the bot
    carries the account that asked for it, so the phone that redeems one writes
    with that account's role at the moment of each request.

    ``code`` is already cleaned of control characters by the shell's own
    validation; ``client_key`` is the caller's bucket, from
    ``api/deps.caller_bucket`` with no scope.
    """
    # Counted before the code is looked at, and handed back below on every
    # answer that is not a wrong code: see `JoinThrottle.admit` for why a check
    # followed by a later record let a concurrent burst through.
    attempt = await join_limiter.admit(session, client_key)
    if attempt.retry_after is not None:
        raise JoinThrottled(attempt.retry_after)

    code = code.strip().upper()
    # The class code first, and a personal invite only if it names no class.
    # The two cannot collide — they are different lengths, see
    # ``services/device_invites.CODE_LENGTH`` — so the order is about which
    # refusal the caller gets rather than about which code wins.
    school_class = await session.scalar(select(SchoolClass).where(SchoolClass.join_code == code))
    invite: DeviceInvite | None = None

    if school_class is not None and school_class.join_mode is JoinMode.INVITE:
        # A real code for a real class, refused because this class does not let
        # a shared secret in. Said plainly rather than as "unknown code": the
        # person holding it has been given it by somebody, and telling them it
        # is wrong sends them back to that person instead of to the bot.
        #
        # Not a failed attempt for the throttle either. The limiter is there to
        # stop somebody walking the code space, and this caller has already
        # found a code — counting it would let a class that switched to invites
        # lock out everybody who still had the old one.
        await join_limiter.forgive(session, attempt)
        raise ClassInviteOnly("class takes personal codes only")

    if school_class is None:
        invite = await device_invites.find_live(session, code)
        if invite is not None:
            school_class = await session.get(SchoolClass, invite.class_id)

    if school_class is None:
        # The one answer that stays counted: the attempt `admit` wrote is it.
        raise JoinCodeUnknown("unknown join code")

    if invite is None and await live_devices(session, school_class.id) >= MAX_DEVICES_PER_CLASS:
        # Only the class code is bounded. A personal code is minted by the bot
        # for a member it knows, one phone at a time, with a name in the
        # journal: it is not what the bound is against, and it is the way in
        # left to a family when somebody has filled the class through the
        # shared code. Not counted against the throttle, for the reason the
        # invite-only refusal is not — a real code, not a guess. A burst of
        # joins that all counted before any committed can pass the bound by its
        # own size, and no further: after it, every join counts past it.
        await join_limiter.forgive(session, attempt)
        raise DeviceLimitReached("class is full")

    # Spent before the token is minted, not after: the update is what makes
    # "one code, one phone" true against a second request that read the same
    # live row, and a token minted first would be a token already handed out
    # by the time we found out we lost.
    if invite is not None and not await device_invites.burn(session, invite):
        # Lost a race microseconds wide: the row was live when it was read and
        # spent by the time it was written. Not counted against the limiter,
        # for the same reason as above — this caller had a real code, and the
        # limiter is there for somebody who does not.
        await join_limiter.forgive(session, attempt)
        raise JoinCodeUnknown("personal code spent by a concurrent join")

    token = new_token()
    session.add(
        DeviceToken(
            token_hash=hash_token(token),
            class_id=school_class.id,
            device_name=device_name,
            # An invite carries the account that asked for it, so the device is
            # linked in the same breath as it joins. On the class code it stays
            # null, which is what "joined, nobody knows whose phone" looks like.
            telegram_id=invite.telegram_id if invite is not None else None,
            linked_at=device_invites.utcnow() if invite is not None else None,
        )
    )
    if invite is not None:
        # The same line `/link` writes, for the same event: a phone that from
        # now on acts with somebody's role. On the invite path this is the only
        # place it can be written — there is no second step to hang it on — and
        # in «по приглашению» this is the only door, so without it the journal
        # stops answering «кто подключил этот телефон» exactly when it becomes
        # the only question worth asking of it.
        await audit.record(
            session,
            school_class.id,
            invite.telegram_id,
            "device.link",
            f"телефон подключён по личному коду: {device_name or 'без названия'}",
        )
    await session.commit()
    # A real code: only failures are counted, so a classroom joining from one
    # school NAT is never blocked by each other's successes. After the commit
    # above, which is the join, so the invite's burn and the token land in one
    # transaction as they always did.
    await join_limiter.forgive(session, attempt)
    return Joined(token=token, school_class=school_class)
```

- [ ] **Step 3: `server/app/services/linking.py` gains the device's access.** Replace `from datetime import UTC, datetime` with `from dataclasses import dataclass` and `from datetime import UTC, datetime` on two lines, and append at the end of the file:
```python


@dataclass(frozen=True)
class Access:
    """What a device may do in its class, as a phone is told it.

    v1's ``/bundle`` and ``/me`` and v2's ``DeviceAccess`` and ``Me`` all
    answer these three facts, and «may edit» is a rule rather than a field:
    editor or above. One copy of it, here, because a second copy in a v2
    handler is the copy that would one day say «admin».
    """

    linked: bool
    #: ``None`` for an unlinked device, and for a linked account that is not a
    #: member of the class.
    role: Role | None

    @property
    def can_edit(self) -> bool:
        return self.role is not None and self.role.at_least(Role.EDITOR)

    @classmethod
    def of(cls, device: DeviceToken, role: Role | None) -> Access:
        return cls(linked=device.is_linked, role=role)


async def access_of(session: AsyncSession, device: DeviceToken) -> Access:
    """The device's :class:`Access`, with its role read now, as every request does."""
    return Access.of(device, await effective_role(session, device))
```

- [ ] **Step 4: `server/app/services/terms.py` gains terms that write nothing.** Replace `import re` / `from datetime import date as Date` (the first two imports) with `import re`, `from dataclasses import dataclass`, `from datetime import date as Date`, and insert immediately above `async def ensure(`:
```python
@dataclass(frozen=True)
class TermSpan:
    """One term as a reader is shown it: the stored row's facts, or the
    conventional set's, with nothing attached to a session.

    A plain value rather than a :class:`Term` on purpose. A ``Term`` built for
    an answer and never added would still be one ``session.add`` — or one
    relationship append — away from an autoflush that persists it, which is
    the write a read has promised not to make
    (``docs/specs/2026-10-05-server-v2-design.md``, decision 10).
    """

    index: int
    kind: TermKind
    starts_on: Date
    ends_on: Date

    @classmethod
    def of(cls, term: Term) -> TermSpan:
        return cls(index=term.index, kind=term.kind, starts_on=term.starts_on, ends_on=term.ends_on)


async def spans(session: AsyncSession, school_class: SchoolClass, year: int) -> list[TermSpan]:
    """This class's terms for ``year`` as a read answers them, writing nothing.

    The stored rows when the year has any, in order. Otherwise the conventional
    set for the class's scheme today — computed, not seeded: :func:`ensure` is
    what persists it, and only a write that edits a term or the scheme calls
    that. The answer is the one ``ensure`` would have seeded, and the days a
    reader resolves agree with it either way: the conventional bounds run
    contiguously from the year's first day to 31 May, which is exactly the
    span ``schedule.off_reason_for`` falls back to when a year has no rows.
    """
    stored = await read(session, school_class.id, year)
    if stored:
        return [TermSpan.of(term) for term in stored]
    kind = scheme_of(school_class)
    return [
        TermSpan(index=index, kind=kind, starts_on=starts, ends_on=ends)
        for index, (starts, ends) in enumerate(default_term_bounds(year, kind), start=1)
    ]


```

- [ ] **Step 5: Create `server/app/services/window.py`.**
```python
"""One school year of a class's days, and the entity tag a phone caches it under.

v1's ``/bundle`` built its window and its tag inside the router
(``api/public.py``); v2's ``GetScheduleWindow`` may not import a router, and a
second copy of «what is in the window» or «does this tag match» is the copy
that drifts (``docs/specs/2026-10-05-server-v2-design.md``, decision 2). So the
rules live here and both versions call them:

- :func:`etag_matches` is v1's ``_matches``, verbatim: a list of tags, ``*``
  and the ``W/`` prefix;
- :func:`strong_etag` is the quoting and hashing v1's ``_etag`` did, over
  whichever canonical text the caller's wire format has;
- :func:`for_year` is v2's window: one school year, terms computed rather than
  seeded, nothing written.

v1's ``/bundle`` keeps its own order — resolve, look ahead, seed, adopt,
commit — because moving a step would move its answers.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

from sqlalchemy.ext.asyncio import AsyncSession

from app.models import SchoolClass, TermKind
from app.schedule import ResolvedDay, ScheduleResolver, school_year_end, school_year_start
from app.services import clock
from app.services import terms as terms_service
from app.services.linking import Access
from app.services.terms import TermSpan

#: The first and last school years a request may name: those whose every day
#: lies inside ``clock``'s bounds. 2000's year opens on 1 September 2000; 2099's
#: would close on 31 May 2100, past ``MAX_DATE``.
FIRST_YEAR = clock.MIN_DATE.year
LAST_YEAR = clock.MAX_DATE.year - 2


class YearOutOfBounds(ValueError):
    """A school year outside :data:`FIRST_YEAR`..:data:`LAST_YEAR`."""


@dataclass(frozen=True)
class Window:
    """What a phone caches for one school year, before any wire format."""

    school_class: SchoolClass
    #: The scheme in force today, which the stored terms may predate.
    scheme: TermKind
    terms: list[TermSpan]
    days: list[ResolvedDay]
    access: Access


def year_in_bounds(year: int) -> bool:
    """Whether ``year`` opens a school year a request may name."""
    return FIRST_YEAR <= year <= LAST_YEAR


async def for_year(
    session: AsyncSession, school_class: SchoolClass, year: int, *, access: Access
) -> Window:
    """Every day of the school year that opens in ``year``, writing nothing.

    The days are the resolver's, from the year's first teaching day through
    31 May; the terms are :func:`terms.spans`, the stored rows or the
    conventional set, never seeded. Raises :class:`YearOutOfBounds` before any
    date is built from a year outside the bounds, because ``date(0, 9, 1)``
    is a ``ValueError`` and ``date(10000, …)`` one too.
    """
    if not year_in_bounds(year):
        raise YearOutOfBounds(year)
    first, last = school_year_start(year), school_year_end(year)
    days = await ScheduleResolver(session, school_class).resolve_range(
        first, (last - first).days + 1
    )
    return Window(
        school_class=school_class,
        scheme=terms_service.scheme_of(school_class),
        terms=await terms_service.spans(session, school_class, year),
        days=days,
        access=access,
    )


def strong_etag(canonical: str) -> str:
    """A strong validator over a window's canonical text, quoted.

    The caller hands the text with ``generated_at`` blanked: the timestamp
    changes on every request, so hashing it would mean no two answers ever
    match and the 304 path never runs. Everything else is what the phone
    caches, the device's own access included, so a role changed in the bot
    is a new tag.
    """
    return '"' + hashlib.sha256(canonical.encode("utf-8")).hexdigest() + '"'


def etag_matches(if_none_match: str | None, etag: str) -> bool:
    """RFC 9110 ``If-None-Match``: a list of validators, ``*``, weak forms allowed."""
    if not if_none_match:
        return False
    for candidate in if_none_match.split(","):
        candidate = candidate.strip()
        if candidate.startswith("W/"):
            candidate = candidate[2:]
        if candidate == "*" or candidate == etag:
            return True
    return False
```

- [ ] **Step 6: v1's `/join`, tag and access call the moved code.** In `server/app/api/public.py`:
  1. Delete `import hashlib`; replace `from sqlalchemy import func, select, text` with `from sqlalchemy import select, text`; insert `from app import wording` immediately above `from app.api.deps import current_class, current_device, request_bucket`; in the `from app.models import (` block delete the lines `    DeviceInvite,`, `    JoinMode,` and `    Role,`.
  2. Replace
```python
from app.security import MAX_DEVICES_PER_CLASS, hash_token, join_limiter, new_token
from app.services import audit, clock, device_invites, linking
from app.services import calendar as calendar_service
```
  with
```python
from app.security import MAX_DEVICES_PER_CLASS as MAX_DEVICES_PER_CLASS
from app.security import join_limiter as join_limiter
from app.services import calendar as calendar_service
from app.services import clock, linking, window
from app.services import join as join_service
```
  (the two `as` imports are re-exports: `test_join_modes.py`, `test_join_device_cap.py`, `test_hardening.py` and `test_directory.py` import them from here).
  3. Delete `async def _live_devices(…)` (now `join.live_devices`).
  4. Replace the whole `join` endpoint — from `@router.post("/join", response_model=JoinResponse)` to, not including, `@router.get("/bundle", response_model=BundleOut)` — with:
```python
@router.post("/join", response_model=JoinResponse)
async def join(
    request: Request,
    payload: JoinRequest,
    *,
    session: FromDishka[AsyncSession],
) -> JoinResponse:
    """Exchange a code for a long-lived device token.

    The flow is ``services/join.py``'s, which v2's ``CreateDevice`` calls too,
    over the same limiter and the same bucket; this endpoint keeps v1's words
    for each refusal (``app/wording.py``'s, which v2 answers with too), and
    its statuses.
    """
    try:
        joined = await join_service.join(
            session,
            code=payload.code,
            device_name=payload.device_name,
            client_key=request_bucket(request),
        )
    except join_service.JoinThrottled as refusal:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=wording.JOIN_THROTTLED_DETAIL,
            headers={"Retry-After": str(refusal.seconds)},
        ) from None
    except join_service.ClassInviteOnly:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=wording.JOIN_INVITE_ONLY_DETAIL,
        ) from None
    except join_service.JoinCodeUnknown:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=wording.JOIN_UNKNOWN_CODE_DETAIL
        ) from None
    except join_service.DeviceLimitReached:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=wording.JOIN_DEVICE_LIMIT_DETAIL
        ) from None
    school_class = joined.school_class
    return JoinResponse(
        token=joined.token,
        class_id=school_class.id,
        class_name=school_class.name,
        school=school_class.school,
        timezone=school_class.timezone_name,
        # Through `registry.binding`, the one reading of a class's binding, so
        # a region since dropped from the allow-list, one that takes no
        # password, or a «Сетевой город» binding with no school all tell the
        # phone «no diary» rather than point it at one this server refuses.
        diary=DiaryBindingOut.of(diary_binding(school_class)),
    )


```
  5. In `bundle`, replace `    if _matches(if_none_match, etag):` with `    if window.etag_matches(if_none_match, etag):`.
  6. Replace `_etag`, `_matches`, `_role_of` and `_can_edit` — from `def _etag(out: BundleOut) -> str:` to, not including, `async def _device_out(` — with:
```python
def _etag(out: BundleOut) -> str:
    """A strong validator over the body with ``generated_at`` blanked.

    The timestamp changes on every request, so hashing it would mean no two
    responses ever match and the 304 path never runs. Everything else in the
    body is what the client actually caches. The hashing and the quoting are
    ``services/window.py``'s, which v2's tag uses too.
    """
    return window.strong_etag(out.model_copy(update={"generated_at": ""}).model_dump_json())


```
  7. Replace the body of `_device_out` with:
```python
    access = await linking.access_of(session, device)
    return DeviceOut(
        linked=access.linked,
        role=access.role.value if access.role else None,
        can_edit=access.can_edit,
    )
```
  and in `me` replace `    role = await _role_of(session, device)` with `    access = await linking.access_of(session, device)` and the three lines `linked=device.is_linked,` / `role=role.value if role else None,` / `can_edit=_can_edit(role),` with `linked=access.linked,` / `role=access.role.value if access.role else None,` / `can_edit=access.can_edit,`.

- [ ] **Step 7: Green, and v1 unchanged.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m pytest -q -p no:xdist tests/test_services_window.py tests/test_shared_rules.py tests/test_join_modes.py tests/test_join_device_cap.py tests/test_hardening.py tests/test_api_extended.py tests/test_api.py tests/test_directory.py
```
Expected: all pass (`test_services_window.py` is 24). The bundle's `ETag` tests in `test_api_extended.py` prove `strong_etag` hashes exactly what `_etag` did.

- [ ] **Step 8: Gates.** ruff clean; mypy `Success: no issues found in 200 source files`; the full suite once, previous count plus 24.

- [ ] **Step 9: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/wording.py server/app/services/join.py server/app/services/window.py server/app/services/linking.py server/app/services/terms.py server/app/api/public.py server/tests/test_services_window.py && git commit -F - <<'EOF'
Move the join flow and the window's tag into services, with v1 calling them

v1's POST /join held the whole join in the router: the throttle's admit
and forgive, the invite-only refusal, the 300-phone cap, the personal
code's burn and its journal line. v2's CreateDevice needs every one of
those, so the flow is services/join.py now, line for line with its
comments, and the refusals are exceptions carrying facts. The four
sentences v1 answers with are app/wording.py's, beside the diary's, so
both shells say the same and the service holds no sentence.

The bundle's tag rules move to services/window.py (v1's If-None-Match
matching verbatim, and the hashing), beside v2's school-year window, which
computes its terms rather than seeding them: terms.spans answers the
stored rows, or the conventional set as plain values that no autoflush can
persist. A device's access (linked, role, editor or above) is
linking.Access, which v1's /bundle and /me now build from.

v1's /bundle keeps its own order (resolve, look ahead, seed, adopt,
commit), because moving a step would move its answers.

Not covered: nothing of v2 calls these yet.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 4: The one error table, and Google's error body

Decision 5. Every refusal of v2, on either transport, is a `connectrpc.errors.ConnectError` built here: a canonical code, an `ErrorInfo` whose `reason` is an `ErrorReason` name and whose `metadata` holds only the keys `errors.proto` names for it, a `BadRequest` and a `RetryInfo` where they apply, and the shell's sentence.

**Files:**
- Create: `server/app/rpc/__init__.py` (a docstring for now), `server/app/rpc/errors.py`, `server/app/rest/__init__.py` (a docstring for now), `server/app/rest/errors.py`, `server/tests/test_rpc_errors.py`
- Modify: `docs/api.md` (the code table gains `INTERNAL`)

**Interfaces:**
- Consumes: Task 1's `ErrorInfo`, `BadRequest`, `RetryInfo`; Task 3's `join.*` exceptions, `window.YearOutOfBounds`, `FIRST_YEAR`, `LAST_YEAR`; `services.diary.DiaryDisabled`; `app.wording`'s `JOIN_*_DETAIL` (Task 3) and `DIARY_DISABLED_DETAIL` (Task 2).
- Produces:
  - `app.rpc.errors`: `DOMAIN = "lessons.app"`; `INTERNAL_MESSAGE`; `UNDECODABLE_MESSAGE = "The request could not be decoded"`; `CODES: Mapping[ErrorReason, Code]`; `class Refusal(Exception)`: `Refusal(reason: ErrorReason, message: str, *, violations: Sequence[tuple[str, str]] = (), **metadata: str | int)` with `.reason`, `.message`, `.violations`, `.metadata: dict[str, str]`; `TABLE: Mapping[type[Exception], Callable[[Any], Refusal]]`; `refusal_of(error) -> Refusal | None`; `connect_error(error: BaseException) -> ConnectError` (never raises, never quotes the error); `undecodable() -> Refusal`; `validate(model: type[M], data: Mapping[str, object]) -> M`.
  - `app.rest.errors`: `STATUS: dict[Code, int]`; `error_response(error: ConnectError) -> JSONResponse`.

- [ ] **Step 1: Red.** Create `server/tests/test_rpc_errors.py`. Its `LATER` lists the seven reasons later tasks of 3a produce (the gate's six, `WatchClass`'s one) beside the nineteen 3b's methods will; Tasks 5 and 6 take them out as they arrive. Task 10 adds `HELD_BY`, which names for each row of the table the test that reads it back on both paths (Ruling 24).
```python
"""The one error table, held to ``errors.proto`` and to ``docs/api.md``.

``rpc/errors.py`` maps every refusal to a canonical code, an ``ErrorReason``,
the metadata the proto names for that reason, and the shell's own sentence;
``rest/errors.py`` writes it as Google's JSON error body. Both are read here
against the files that promise them, so that a reason moved to another code,
a metadata key the proto does not name, or a status the documentation does not
list fails here rather than on a phone.
"""

from __future__ import annotations

import ast
import json
import logging
import re
from pathlib import Path

from connectrpc.code import Code

from app.contract.google.rpc.error_details_pb import ErrorInfo, RetryInfo
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.rest.errors import STATUS, error_response
from app.rpc import errors
from app.rpc.errors import CODES, Refusal, connect_error, validate
from app.schemas import JoinRequest

SERVER = Path(__file__).resolve().parents[1]
ERRORS_PROTO = SERVER.parent / "proto" / "lessons" / "v2" / "errors.proto"
API_DOC = SERVER.parent / "docs" / "api.md"
RPC = SERVER / "app" / "rpc"

#: The reasons no method of this stage can produce yet, and the stage that
#: brings them. A reason leaves this set in the commit whose handler raises it.
LATER = {
    "DEVICE_TOKEN_INVALID": "3a: the gate",
    "DIARY_TOKEN_INVALID": "3a: the gate",
    "DEVICE_NOT_LINKED": "3a: the gate",
    "ROLE_REQUIRED": "3a: the gate",
    "RESOURCE_NOT_FOUND": "3a: the gate",
    "CLIENT_TOO_OLD": "3a: the gate",
    "FEATURE_UNSUPPORTED": "3a: WatchClass",
    "RESOURCE_EXISTS": "3b",
    "NO_BELL_FOR_LESSON": "3b",
    "EMPTY_BELL_SCHEDULE": "3b",
    "DIARY_UNAVAILABLE": "3b",
    "DIARY_REAUTH": "3b",
    "DIARY_CREDENTIALS_REJECTED": "3b",
    "DIRECTORY_DISABLED": "3b",
    "DIRECTORY_SPENT": "3b",
    "DIRECTORY_UNAVAILABLE": "3b",
    "DIARY_NO_STUDENTS": "3b",
    "DIARY_UPSTREAM_UNREADABLE": "3b",
    "CORRECTIONS_UNAVAILABLE": "3b",
    "RESOURCE_IN_USE": "3b",
    "SUBJECT_RENAME_CLASH": "3b",
    "CLASS_DEVICE_NOT_LINKED": "3b",
    "ROLE_GRANT_REFUSED": "3b",
    "TERM_BOUNDS_REFUSED": "3b",
    "NO_LESSON_ON_DAY": "3b",
    "LESSON_NOT_ON_TIMETABLE": "3b",
}


def _proto_reasons() -> dict[str, str]:
    """Each value of ``ErrorReason`` and the comment above it, joined."""
    found: dict[str, str] = {}
    comment: list[str] = []
    for line in ERRORS_PROTO.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("//"):
            comment.append(stripped.removeprefix("//").strip())
            continue
        value = re.fullmatch(r"([A-Z_]+) = \d+;", stripped)
        if value and value.group(1) != "ERROR_REASON_UNSPECIFIED":
            found[value.group(1)] = " ".join(comment)
        comment = []
    return found


def _proto_metadata(comment: str) -> set[str]:
    """The keys a reason's comment names after «metadata», up to its colon or full stop."""
    match = re.search(r"metadata (.*?)(?::\s|\.\s|\.$|$)", comment)
    return set(re.findall(r"`([a-z_]+)`", match.group(1))) if match else set()


def _refusals_in_app_rpc() -> list[tuple[str, str, set[str]]]:
    """Every ``Refusal(ErrorReason.X, …, key=…)`` written under ``app/rpc``:
    where it is, its reason, and its metadata keys."""
    found = []
    for path in sorted(RPC.rglob("*.py")):
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if not (isinstance(node, ast.Call) and getattr(node.func, "id", None) == "Refusal"):
                continue
            first = node.args[0] if node.args else None
            assert isinstance(first, ast.Attribute), f"{path.name}:{node.lineno} names no reason"
            keys = {kw.arg for kw in node.keywords if kw.arg and kw.arg != "violations"}
            found.append((f"{path.name}:{node.lineno}", first.attr, keys))
    return found


def test_every_reason_is_under_the_code_errors_proto_gives_it() -> None:
    reasons = _proto_reasons()
    assert len(reasons) == 33
    table = {reason.name: code.name for reason, code in CODES.items()}
    assert table == {name: comment.split(".")[0] for name, comment in reasons.items()}


def test_every_refusal_carries_only_the_metadata_errors_proto_names() -> None:
    reasons = _proto_reasons()
    wrong = [
        f"{where}: {reason} carries {sorted(keys - _proto_metadata(reasons[reason]))}"
        for where, reason, keys in _refusals_in_app_rpc()
        if not keys <= _proto_metadata(reasons[reason])
    ]
    assert wrong == []


def test_the_metadata_reader_reads_what_it_is_written_for() -> None:
    """Held here rather than trusted: a reader that found nothing would pass
    the test above for ever."""
    reasons = _proto_reasons()
    assert _proto_metadata(reasons["THROTTLED"]) == {"retry_after_seconds"}
    assert _proto_metadata(reasons["RESOURCE_IN_USE"]) == {"resource", "used_by", "count"}
    assert _proto_metadata(reasons["DIRECTORY_SPENT"]) == {"retry_after_seconds"}
    assert _proto_metadata(reasons["VALIDATION_FAILED"]) == set()


def test_every_reason_is_produced_or_waits_for_a_later_stage() -> None:
    produced = {reason for _where, reason, _keys in _refusals_in_app_rpc()}
    every = set(_proto_reasons())
    assert produced & set(LATER) == set(), "a reason this stage produces is still listed as later"
    assert every - produced - set(LATER) == set(), "a reason nothing produces and nothing awaits"
    assert set(LATER) <= every


def test_a_refusal_is_its_code_its_reason_and_the_details_the_proto_promises() -> None:
    error = connect_error(
        Refusal(ErrorReason.THROTTLED, "Too many join attempts", retry_after_seconds=7)
    )
    assert error.code is Code.RESOURCE_EXHAUSTED
    assert error.message == "Too many join attempts"
    values = [detail.value() for detail in error.details]
    assert values[0] == ErrorInfo(
        reason="THROTTLED", domain="lessons.app", metadata={"retry_after_seconds": "7"}
    )
    assert isinstance(values[1], RetryInfo)
    assert values[1].retry_delay.seconds == 7


def test_an_unknown_failure_is_internal_and_says_nothing_of_itself(caplog) -> None:
    with caplog.at_level(logging.ERROR, logger="app.rpc.errors"):
        error = connect_error(RuntimeError("password=hunter2 leaked into a message"))
    assert error.code is Code.INTERNAL
    assert error.message == errors.INTERNAL_MESSAGE
    assert list(error.details) == []
    assert "hunter2" not in error.message
    assert any(record.exc_info for record in caplog.records)


def test_validation_names_the_field_and_never_the_value() -> None:
    secret = "Pa55w0rd-s3cret-" * 3
    try:
        validate(JoinRequest, {"code": secret, "device_name": None})
    except Refusal as refusal:
        assert refusal.reason is ErrorReason.VALIDATION_FAILED
        assert [field for field, _ in refusal.violations] == ["code"]
        assert secret not in refusal.message
        assert all(secret not in description for _, description in refusal.violations)
    else:
        raise AssertionError("a 48-character code was accepted")


def test_rest_writes_google_s_error_body_through_the_type_registry() -> None:
    response = error_response(
        connect_error(Refusal(ErrorReason.ROLE_REQUIRED, "admin role required", role="ROLE_ADMIN"))
    )
    assert response.status_code == 403
    assert json.loads(response.body) == {
        "error": {
            "code": 403,
            "message": "admin role required",
            "status": "PERMISSION_DENIED",
            "details": [
                {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "ROLE_REQUIRED",
                    "domain": "lessons.app",
                    "metadata": {"role": "ROLE_ADMIN"},
                }
            ],
        }
    }


def test_rest_sends_retry_after_and_the_retry_info_beside_it() -> None:
    response = error_response(
        connect_error(
            Refusal(ErrorReason.THROTTLED, "Too many join attempts", retry_after_seconds=7)
        )
    )
    assert response.status_code == 429
    assert response.headers["Retry-After"] == "7"
    details = json.loads(response.body)["error"]["details"]
    assert {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "7s"} in details


def test_rest_names_the_scheme_on_a_401_and_the_field_on_a_400() -> None:
    unauthenticated = error_response(
        connect_error(Refusal(ErrorReason.DEVICE_TOKEN_INVALID, "Invalid token"))
    )
    assert unauthenticated.status_code == 401
    assert unauthenticated.headers["WWW-Authenticate"] == "Bearer"

    invalid = error_response(
        connect_error(
            Refusal(ErrorReason.VALIDATION_FAILED, "bad year", violations=[("year", "bad year")])
        )
    )
    assert invalid.status_code == 400
    details = json.loads(invalid.body)["error"]["details"]
    assert {
        "@type": "type.googleapis.com/google.rpc.BadRequest",
        "fieldViolations": [{"field": "year", "description": "bad year"}],
    } in details


def test_the_status_table_is_docs_api_md_s() -> None:
    """Every code v2 sends has the status ``docs/api.md`` gives it, INTERNAL included."""
    section = API_DOC.read_text(encoding="utf-8").split("## v2: the contract", 1)[1]
    documented: dict[str, int] = {}
    for codes, status in re.findall(r"^\| ((?:`[A-Z_]+`(?:, )?)+) \| (\d{3}) \|$", section, re.M):
        for code in re.findall(r"`([A-Z_]+)`", codes):
            documented[code] = int(status)
    sent = {code.name for code in CODES.values()} | {"INTERNAL", "UNIMPLEMENTED"}
    assert sent <= set(documented), sorted(sent - set(documented))
    assert {code: STATUS[Code[code]] for code in documented} == documented
```
Run `python -m pytest -q -p no:xdist tests/test_rpc_errors.py`. Expected: collection error, `ModuleNotFoundError: No module named 'app.rest'`.

- [ ] **Step 2: Create the two packages' `__init__.py`.** `server/app/rpc/__init__.py`:
```python
"""v2 over RPC: the handlers, the gate, the error table and one call's scope."""
```
and `server/app/rest/__init__.py`:
```python
"""v2 over REST: the transcoder, from the contract's own annotations."""
```
Task 6 and Task 7 fill them.

- [ ] **Step 3: Create `server/app/rpc/errors.py`.**
```python
"""The one table: what a refusal is on the wire, whichever transport carries it.

A refusal leaves a handler in one of two shapes. A :class:`Refusal` is raised
where v1 raised an ``HTTPException`` inline — an unknown id, a date out of
bounds, the gate's own answers. Anything else is a service's or a provider's
exception carrying facts, and :data:`TABLE` words it: a canonical code, an
``ErrorReason``, the metadata ``errors.proto`` names for that reason, and the
shell's own sentence, which is v1's wherever v1 had one
(``docs/specs/2026-10-05-server-v2-design.md``, decision 5) — kept once in
``app/wording.py`` where both versions say it.

:func:`connect_error` turns either into the ``ConnectError`` both transports
send: Connect as itself, REST through ``app/rest/errors.py``. Anything the
table does not know is ``INTERNAL``: logged here with its traceback, answered
with a fixed sentence, because an exception's own text can carry what was
typed — protobuf-py's decoder writes the refused value into its message.
"""

from __future__ import annotations

import logging
from collections.abc import Callable, Mapping, Sequence
from typing import Any, TypeVar

from connectrpc.code import Code
from connectrpc.errors import ConnectError
from protobuf import Message
from protobuf.wkt import Duration
from pydantic import BaseModel, ValidationError

from app import wording
from app.contract.google.rpc.error_details_pb import BadRequest, ErrorInfo, RetryInfo
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.services import diary as diary_service
from app.services import join, window

log = logging.getLogger(__name__)

#: ``ErrorInfo.domain`` on every refusal, as ``docs/api.md`` promises.
DOMAIN = "lessons.app"

#: The sentence an unexpected failure answers with. Fixed, and English like
#: v1's own generic answers: the app acts on the code, never on this.
INTERNAL_MESSAGE = "The server failed to answer this request"

#: The sentence an undecodable request answers with, on both transports. It
#: names no value on purpose: the decoder's own message quotes what was sent.
UNDECODABLE_MESSAGE = "The request could not be decoded"

#: Each reason's canonical code, as the comment beside it in ``errors.proto``
#: begins. ``test_rpc_errors.py`` reads the file and holds the two level.
CODES: Mapping[ErrorReason, Code] = {
    ErrorReason.DEVICE_TOKEN_INVALID: Code.UNAUTHENTICATED,
    ErrorReason.DIARY_TOKEN_INVALID: Code.UNAUTHENTICATED,
    ErrorReason.DEVICE_NOT_LINKED: Code.PERMISSION_DENIED,
    ErrorReason.ROLE_REQUIRED: Code.PERMISSION_DENIED,
    ErrorReason.JOIN_CODE_UNKNOWN: Code.NOT_FOUND,
    ErrorReason.CLASS_INVITE_ONLY: Code.PERMISSION_DENIED,
    ErrorReason.DEVICE_LIMIT_REACHED: Code.RESOURCE_EXHAUSTED,
    ErrorReason.THROTTLED: Code.RESOURCE_EXHAUSTED,
    ErrorReason.RESOURCE_NOT_FOUND: Code.NOT_FOUND,
    ErrorReason.RESOURCE_EXISTS: Code.ALREADY_EXISTS,
    ErrorReason.VALIDATION_FAILED: Code.INVALID_ARGUMENT,
    ErrorReason.NO_BELL_FOR_LESSON: Code.FAILED_PRECONDITION,
    ErrorReason.EMPTY_BELL_SCHEDULE: Code.FAILED_PRECONDITION,
    ErrorReason.DIARY_DISABLED: Code.UNAVAILABLE,
    ErrorReason.DIARY_UNAVAILABLE: Code.UNAVAILABLE,
    ErrorReason.DIARY_REAUTH: Code.UNAUTHENTICATED,
    ErrorReason.DIARY_CREDENTIALS_REJECTED: Code.PERMISSION_DENIED,
    ErrorReason.DIRECTORY_DISABLED: Code.UNAVAILABLE,
    ErrorReason.DIRECTORY_SPENT: Code.RESOURCE_EXHAUSTED,
    ErrorReason.DIRECTORY_UNAVAILABLE: Code.UNAVAILABLE,
    ErrorReason.CLIENT_TOO_OLD: Code.FAILED_PRECONDITION,
    ErrorReason.FEATURE_UNSUPPORTED: Code.UNIMPLEMENTED,
    ErrorReason.REQUEST_UNDECODABLE: Code.INVALID_ARGUMENT,
    ErrorReason.DIARY_NO_STUDENTS: Code.PERMISSION_DENIED,
    ErrorReason.DIARY_UPSTREAM_UNREADABLE: Code.UNAVAILABLE,
    ErrorReason.CORRECTIONS_UNAVAILABLE: Code.FAILED_PRECONDITION,
    ErrorReason.RESOURCE_IN_USE: Code.FAILED_PRECONDITION,
    ErrorReason.SUBJECT_RENAME_CLASH: Code.FAILED_PRECONDITION,
    ErrorReason.CLASS_DEVICE_NOT_LINKED: Code.FAILED_PRECONDITION,
    ErrorReason.ROLE_GRANT_REFUSED: Code.PERMISSION_DENIED,
    ErrorReason.TERM_BOUNDS_REFUSED: Code.FAILED_PRECONDITION,
    ErrorReason.NO_LESSON_ON_DAY: Code.FAILED_PRECONDITION,
    ErrorReason.LESSON_NOT_ON_TIMETABLE: Code.FAILED_PRECONDITION,
}


class Refusal(Exception):
    """A refusal a handler or the gate raises: a reason, the shell's sentence,
    and the facts.

    ``metadata`` keys are the ones ``errors.proto`` names for the reason —
    ``test_rpc_errors.py`` reads every ``Refusal(...)`` under ``app/rpc`` and
    holds them to the file. ``retry_after_seconds`` also becomes a
    ``google.rpc.RetryInfo``, and on REST a ``Retry-After``. ``violations``
    are ``(field, description)`` pairs for a ``google.rpc.BadRequest``, named
    by the proto field path; a description never quotes the value.
    """

    def __init__(
        self,
        reason: ErrorReason,
        message: str,
        *,
        violations: Sequence[tuple[str, str]] = (),
        **metadata: str | int,
    ) -> None:
        super().__init__(message)
        self.reason = reason
        self.message = message
        self.violations = tuple(violations)
        self.metadata = {key: str(value) for key, value in metadata.items()}


E = TypeVar("E", bound=Exception)
M = TypeVar("M", bound=BaseModel)


def _join_throttled(error: join.JoinThrottled) -> Refusal:
    return Refusal(
        ErrorReason.THROTTLED, wording.JOIN_THROTTLED_DETAIL, retry_after_seconds=error.seconds
    )


def _join_code_unknown(_error: join.JoinCodeUnknown) -> Refusal:
    return Refusal(ErrorReason.JOIN_CODE_UNKNOWN, wording.JOIN_UNKNOWN_CODE_DETAIL)


def _class_invite_only(_error: join.ClassInviteOnly) -> Refusal:
    return Refusal(ErrorReason.CLASS_INVITE_ONLY, wording.JOIN_INVITE_ONLY_DETAIL)


def _device_limit_reached(error: join.DeviceLimitReached) -> Refusal:
    return Refusal(
        ErrorReason.DEVICE_LIMIT_REACHED, wording.JOIN_DEVICE_LIMIT_DETAIL, limit=error.limit
    )


def _diary_disabled(_error: diary_service.DiaryDisabled) -> Refusal:
    return Refusal(ErrorReason.DIARY_DISABLED, wording.DIARY_DISABLED_DETAIL)


def _year_out_of_bounds(_error: window.YearOutOfBounds) -> Refusal:
    sentence = f"year must be between {window.FIRST_YEAR} and {window.LAST_YEAR}"
    return Refusal(ErrorReason.VALIDATION_FAILED, sentence, violations=[("year", sentence)])


#: Every service and provider exception a v2 method can meet, and its refusal.
#: Matched along the exception's MRO, so a subclass is worded by its own row
#: when it has one and by its base's otherwise. 3a holds the rows its four
#: methods meet; 3b adds the rest, method by method.
TABLE: Mapping[type[Exception], Callable[[Any], Refusal]] = {
    join.JoinThrottled: _join_throttled,
    join.JoinCodeUnknown: _join_code_unknown,
    join.ClassInviteOnly: _class_invite_only,
    join.DeviceLimitReached: _device_limit_reached,
    diary_service.DiaryDisabled: _diary_disabled,
    window.YearOutOfBounds: _year_out_of_bounds,
}


def _details(refusal: Refusal) -> list[Message]:
    details: list[Message] = [
        ErrorInfo(reason=refusal.reason.name, domain=DOMAIN, metadata=refusal.metadata)
    ]
    if refusal.violations:
        details.append(
            BadRequest(
                field_violations=[
                    BadRequest.FieldViolation(field=field, description=description)
                    for field, description in refusal.violations
                ]
            )
        )
    seconds = refusal.metadata.get("retry_after_seconds")
    if seconds is not None:
        details.append(RetryInfo(retry_delay=Duration(seconds=int(seconds))))
    return details


def refusal_of(error: BaseException) -> Refusal | None:
    """The table's refusal for ``error``, or ``None`` if the table does not know it."""
    if isinstance(error, Refusal):
        return error
    for cls in type(error).__mro__:
        row = TABLE.get(cls)
        if row is not None:
            return row(error)
    return None


def connect_error(error: BaseException) -> ConnectError:
    """What ``error`` is on the wire. Never raises, and never quotes ``error``'s text."""
    if isinstance(error, ConnectError):
        return error
    refusal = refusal_of(error)
    if refusal is None:
        log.error("v2 call failed", exc_info=error)
        return ConnectError(Code.INTERNAL, INTERNAL_MESSAGE)
    return ConnectError(CODES[refusal.reason], refusal.message, details=_details(refusal))


def undecodable() -> Refusal:
    """The answer to a request body or query that does not decode."""
    return Refusal(ErrorReason.REQUEST_UNDECODABLE, UNDECODABLE_MESSAGE)


def validate(model: type[M], data: Mapping[str, object]) -> M:
    """``model`` validated from ``data``, or ``VALIDATION_FAILED`` naming each
    field — and never the value, which pydantic keeps under ``input``.

    A handler validates with v1's own schema where one exists, so v1 and v2
    refuse the same requests; only the words differ.
    """
    try:
        return model.model_validate(dict(data))
    except ValidationError as failure:
        violations = [
            (".".join(str(part) for part in error["loc"]), error["msg"])
            for error in failure.errors(include_input=False, include_url=False)
        ]
        fields = ", ".join(sorted({field for field, _ in violations}))
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            f"invalid request field: {fields}",
            violations=violations,
        ) from None
```

- [ ] **Step 4: Create `server/app/rest/errors.py`.**
```python
"""A refusal as REST sends it: Google's JSON error body, under ``docs/api.md``'s status.

``{"error": {"code": <HTTP status>, "message": …, "status": "FAILED_PRECONDITION",
"details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo", …}]}}`` — the
body Google's own transcoding writes, so a client that knows AIP-193 reads it
without being told about this server. The same ``ConnectError`` Connect sends
is the source, so the two transports cannot word one refusal two ways.
"""

from __future__ import annotations

from typing import Any

from connectrpc.code import Code
from connectrpc.errors import ConnectError, ErrorDetail
from protobuf import Registry, message_to_json_value
from protobuf.wkt import Any as AnyMessage
from starlette.responses import JSONResponse

from app.contract.google.rpc import error_details_pb

#: ``docs/api.md``, «Errors»: each canonical code's HTTP status. Codes v2 does
#: not send today are here too, with Google's mapping, so that a library's own
#: refusal (``connectrpc`` raises ``RESOURCE_EXHAUSTED`` for an oversized
#: message) never falls through to a 500.
STATUS: dict[Code, int] = {
    Code.INVALID_ARGUMENT: 400,
    Code.FAILED_PRECONDITION: 400,
    Code.OUT_OF_RANGE: 400,
    Code.UNAUTHENTICATED: 401,
    Code.PERMISSION_DENIED: 403,
    Code.NOT_FOUND: 404,
    Code.ALREADY_EXISTS: 409,
    Code.ABORTED: 409,
    Code.RESOURCE_EXHAUSTED: 429,
    Code.CANCELED: 499,
    Code.INTERNAL: 500,
    Code.UNKNOWN: 500,
    Code.DATA_LOSS: 500,
    Code.UNIMPLEMENTED: 501,
    Code.UNAVAILABLE: 503,
    Code.DEADLINE_EXCEEDED: 504,
}

#: The detail types this server sends, so that an ``Any`` renders as JSON
#: with its ``@type`` rather than as base64. protobuf-py's own registry does
#: the rendering, the way Google's runtime would.
_REGISTRY = Registry(error_details_pb.desc())


def _detail(detail: ErrorDetail) -> Any:
    message = detail.value(_REGISTRY)
    if message is None:
        # A detail this server cannot read — none is sent today — still says
        # what it is, rather than vanishing from the body.
        return {"@type": f"type.googleapis.com/{detail.type_name}"}
    return message_to_json_value(AnyMessage.pack(message), registry=_REGISTRY)


def _metadata(error: ConnectError) -> dict[str, str]:
    for detail in error.details:
        message = detail.value(_REGISTRY)
        if isinstance(message, error_details_pb.ErrorInfo):
            return dict(message.metadata)
    return {}


def error_response(error: ConnectError) -> JSONResponse:
    """The REST answer to ``error``."""
    status = STATUS.get(error.code, 500)
    headers: dict[str, str] = {}
    retry_after = _metadata(error).get("retry_after_seconds")
    if retry_after is not None:
        headers["Retry-After"] = retry_after
    if status == 401:
        # RFC 9110 §11.6.1: a 401 names the scheme it wants. v1 sent it too.
        headers["WWW-Authenticate"] = "Bearer"
    body = {
        "error": {
            "code": status,
            "message": error.message,
            "status": error.code.name,
            "details": [_detail(detail) for detail in error.details],
        }
    }
    return JSONResponse(body, status_code=status, headers=headers)
```

- [ ] **Step 5: `docs/api.md` names `INTERNAL`.** In «v2: the contract», «Errors», insert a row between the `RESOURCE_EXHAUSTED` row (429) and the `UNIMPLEMENTED` row (501):
```markdown
| `INTERNAL` | 500 |
```
and after the paragraph that ends «…each with its code, its metadata and what v1 sent instead.» add the sentence: «A failure the server does not know is `INTERNAL`, with a fixed sentence and no detail: what went wrong is logged, never sent, because an exception's own text can carry what was typed.»

- [ ] **Step 6: Green.** `python -m pytest -q -p no:xdist tests/test_rpc_errors.py` → `11 passed`.

- [ ] **Step 7: Gates.** ruff clean; mypy `Success: no issues found in 204 source files`; the full suite once, previous count plus 11.

- [ ] **Step 8: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/rpc server/app/rest docs/api.md server/tests/test_rpc_errors.py && git commit -F - <<'EOF'
Write v2's one error table, and Google's error body for REST

Every refusal of v2 is built in app/rpc/errors.py: a Refusal raised where v1
refused inline, or a service's exception looked up in TABLE, becomes one
ConnectError with the canonical code errors.proto gives its reason, an
ErrorInfo carrying only the metadata the proto names, a BadRequest naming
fields and a RetryInfo where they apply, and the shell's own sentence,
which is v1's wherever v1 had one. What the table does not know is
INTERNAL, logged with its traceback and answered with a fixed sentence:
protobuf-py's own messages quote the value they refused.

app/rest/errors.py writes the same ConnectError as Google's JSON error body
under docs/api.md's status, with the details rendered through protobuf-py's
type registry, Retry-After beside a RetryInfo and WWW-Authenticate on a 401.
docs/api.md's table gains INTERNAL.

The tests read errors.proto for each reason's code and metadata, and fail
on a reason that nothing produces and no later stage is listed for.

Not covered: no transport sends these yet.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 5: The method table, the gate, and `invoke`

Decisions 3, 4 and 9. After this task one call of any method can be served by `invoke` — no transport reaches it until Task 6.

**Files:**
- Create: `server/app/rpc/methods.py`, `server/app/rpc/values.py`, `server/app/rpc/gate.py`, `server/app/rpc/call.py`, `server/app/rpc/handlers.py`, `server/tests/test_rpc_gate.py`, `server/tests/test_rpc_call.py`
- Modify: `server/app/config.py`, `server/.env.example`, `docker-compose.yml`, `server/tests/conftest.py`, `server/tests/test_service_layering.py`, `server/tests/test_rpc_errors.py`

**Interfaces:**
- Consumes: Task 2's `deps.bearer`, `find_device`, `header`, `touch_last_seen`, `caller_bucket`; Task 4's `Refusal`, `connect_error`; `services.diary.find_session`, `DiaryDisabled`; `linking.effective_role`; `crypto.diary_enabled`; `di.container()`.
- Produces:
  - `app.rpc.methods`: `Binding(verb: str, path: str, body: str)` with `.variables -> list[str]`; `Method(service, name, input: type[Message], output: type[Message], auth: AuthKind, min_role: Role | None, side_effect_free: bool, streaming: bool, binding: Binding | None)` with `.key` (`"lessons.v2.ScheduleService/GetScheduleWindow"`) and `.attribute` (`"get_schedule_window"`); `METHODS: dict[str, Method]` (76); `VARIABLE`; `message_class(desc)`.
  - `app.rpc.values`: `proto_name(member: Enum) -> str` (`"ROLE_ADMIN"`), `role(value: models.Role | None) -> options_pb.Role`. Task 10 adds the rest.
  - `app.rpc.gate`: `TABLE: dict[str, tuple[AuthKind, Role | None]]`; `CLIENT_HEADER = "x-lessons-client"`; `MAX_CLIENT_VERSION = 2_100_000_000`; `@dataclass Admitted(client_version, device, school_class, role, diary)`; `client_version(headers, settings) -> int | None`; `async admit(method, session, settings, headers) -> Admitted`.
  - `app.rpc.call`: `NOT_IMPLEMENTED = "Not implemented"`; `@dataclass Call(method, session, settings, headers, peer, client_version, device, school_class, role, diary, effects)` with `bucket(scope="") -> str`, `after_commit(effect: Callable[[], Awaitable[None]])`, `device_and_class() -> tuple[DeviceToken, SchoolClass]`; `async invoke(method: Method, request: Message, *, headers: Sequence[tuple[str, str]], peer: str | None) -> Message` — raises only `ConnectError`.
  - `app.rpc.handlers`: `Handler = Callable[[Any, Any], Awaitable[Any]]`; `HANDLERS: dict[str, Handler]` keyed as `METHODS` (empty in this task).
  - `Settings.min_client_version: int` (`MIN_CLIENT_VERSION`, empty or `0` = none), forwarded by `docker-compose.yml`.
  - Fixture `v2_tokens(session, school_class) -> dict[str, str]`: bearers `unlinked`, `viewer` (telegram 2001), `editor` (2002), `admin` (2003), `owner` (2004), `stranger` (2005, linked, no member), `diary` (a live diary session).

- [ ] **Step 1: Red.** Append to `server/tests/conftest.py`, after its last line:
```python


@pytest.fixture
async def v2_tokens(session, school_class) -> dict[str, str]:
    """A bearer for each caller the gate tells apart, in ``school_class``.

    ``unlinked`` is the class code's anonymous phone; ``viewer`` to ``owner``
    are phones linked to members of those roles; ``stranger`` is linked to
    an account that is no member; ``diary`` is a live diary session.
    Telegram ids start at 2001, clear of the ``OWNER_IDS`` this file sets.
    """
    from datetime import datetime

    from app.crypto import seal
    from app.models import BotUser, DeviceToken, DiarySession, Role
    from app.security import hash_token

    tokens: dict[str, str] = {}
    for name, telegram_id, role in (
        ("unlinked", None, None),
        ("viewer", 2001, Role.VIEWER),
        ("editor", 2002, Role.EDITOR),
        ("admin", 2003, Role.ADMIN),
        ("owner", 2004, Role.OWNER),
        ("stranger", 2005, None),
    ):
        token = f"v2-{name}-token"
        session.add(
            DeviceToken(
                token_hash=hash_token(token),
                class_id=school_class.id,
                device_name=f"{name} phone",
                telegram_id=telegram_id,
                linked_at=datetime(2026, 9, 1) if telegram_id is not None else None,
            )
        )
        if role is not None:
            session.add(BotUser(telegram_id=telegram_id, class_id=school_class.id, role=role))
        tokens[name] = token
    tokens["diary"] = "v2-diary-token"
    session.add(
        DiarySession(
            token_hash=hash_token(tokens["diary"]),
            upstream_token=seal("an-upstream-session"),
            login="parent@example.com",
            provider="petersburg",
        )
    )
    await session.commit()
    return tokens
```
Create `server/tests/test_rpc_gate.py`:
```python
"""The generic gate: its table against the contract, and every check it makes, in order.

``gate.TABLE`` is compared with the descriptors, read here independently of
``rpc/methods.py``, for all 76 methods. The checks themselves run against one
real method of each kind of credential and least role — most of them methods
3a does not serve yet, which is the point: the gate is the code every later
method will stand behind, and it is held before they arrive
(``docs/specs/2026-10-05-server-v2-design.md``, decision 3). Over HTTP, on both
paths, the gate is held by ``test_v2_reads.py`` for the methods 3a serves.
"""

from __future__ import annotations

import importlib
import pkgutil
from datetime import datetime

import pytest
from sqlalchemy import select

import app.contract.lessons.v2 as contract_v2
from app.config import get_settings
from app.contract.lessons.v2 import options_pb
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.models import DiarySession
from app.rpc import gate
from app.rpc.errors import Refusal
from app.rpc.methods import METHODS
from app.services.diary import DiaryDisabled


def _declared() -> dict[str, tuple[int, int | None]]:
    """Every method's (auth, min_role) as the generated descriptors declare them."""
    found: dict[str, tuple[int, int | None]] = {}
    for info in pkgutil.iter_modules(contract_v2.__path__):
        if not info.name.endswith("_pb"):
            continue
        module = importlib.import_module(f"{contract_v2.__name__}.{info.name}")
        for service in module.desc().services:
            for method in service.methods:
                options = method.proto.options
                role = (
                    int(options[options_pb.ext_min_role])
                    if options_pb.ext_min_role in options
                    else None
                )
                found[f"{service.type_name}/{method.name}"] = (
                    int(options[options_pb.ext_auth]),
                    role,
                )
    return found


def test_the_gate_table_is_the_contract_for_all_76_methods() -> None:
    table = {
        key: (int(auth), None if role is None else int(role))
        for key, (auth, role) in gate.TABLE.items()
    }
    assert len(table) == 76
    assert table == _declared()


def test_every_method_reads_its_own_messages() -> None:
    """``rpc/methods.py`` finds each class by name in its file's module; this
    holds that the name found is the descriptor the method declares."""
    for key, method in METHODS.items():
        assert method.input.desc().type_name == f"lessons.v2.{method.name}Request", key
        assert method.output.desc().type_name == f"lessons.v2.{method.name}Response", key
    streams = [key for key, method in METHODS.items() if method.streaming]
    assert streams == ["lessons.v2.WatchService/WatchClass"]
    assert METHODS["lessons.v2.WatchService/WatchClass"].binding is None


# ---- the client version (decision 9) ----------------------------------------


def _version(monkeypatch, minimum: int, value: str | None) -> int | None:
    settings = get_settings()
    monkeypatch.setattr(settings, "min_client_version", minimum)
    headers = [] if value is None else [("X-Lessons-Client", value)]
    return gate.client_version(headers, settings)


def test_without_a_minimum_the_version_is_read_and_never_refused(monkeypatch) -> None:
    assert _version(monkeypatch, 0, None) is None
    assert _version(monkeypatch, 0, "512") == 512
    assert _version(monkeypatch, 0, "2100000000") == 2_100_000_000
    assert _version(monkeypatch, 0, "2100000001") is None
    assert _version(monkeypatch, 0, "a lot") is None
    assert _version(monkeypatch, 0, "0") is None


def test_with_a_minimum_an_older_client_is_told_to_update(monkeypatch) -> None:
    with pytest.raises(Refusal) as refused:
        _version(monkeypatch, 40, "39")
    assert refused.value.reason is ErrorReason.CLIENT_TOO_OLD
    assert refused.value.metadata == {"min_version": "40"}
    assert _version(monkeypatch, 40, "40") == 40
    assert _version(monkeypatch, 40, "2100000000") == 2_100_000_000


def test_with_a_minimum_a_missing_header_is_let_through(monkeypatch) -> None:
    assert _version(monkeypatch, 40, None) is None


@pytest.mark.parametrize("value", ["", "abc", "0", "-3", "4.5", "١٢٣", "2100000001", "9" * 11])
def test_with_a_minimum_a_header_that_is_no_version_code_is_invalid(monkeypatch, value) -> None:
    with pytest.raises(Refusal) as refused:
        _version(monkeypatch, 40, value)
    assert refused.value.reason is ErrorReason.VALIDATION_FAILED
    assert refused.value.violations[0][0] == "X-Lessons-Client"


# ---- the bearer, the link and the role, in order ---------------------------


async def _admit(session, key: str, token: str | None, *, version: str | None = None):
    headers = []
    if token is not None:
        headers.append(("Authorization", f"Bearer {token}"))
    if version is not None:
        headers.append(("X-Lessons-Client", version))
    return await gate.admit(METHODS[f"lessons.v2.{key}"], session, get_settings(), headers)


async def _refusal(session, key: str, token: str | None, **kw) -> Refusal:
    with pytest.raises(Refusal) as refused:
        await _admit(session, key, token, **kw)
    return refused.value


async def test_a_method_that_takes_no_credential_ignores_one(session, v2_tokens) -> None:
    admitted = await _admit(session, "DeviceService/CreateDevice", "not-a-token")
    assert admitted.device is None and admitted.diary is None


async def test_a_device_method_refuses_a_missing_and_a_foreign_bearer(session, v2_tokens) -> None:
    missing = await _refusal(session, "ScheduleService/GetScheduleWindow", None)
    assert (missing.reason, missing.message) == (
        ErrorReason.DEVICE_TOKEN_INVALID,
        "Missing bearer token",
    )
    foreign = await _refusal(session, "ScheduleService/GetScheduleWindow", v2_tokens["diary"])
    assert (foreign.reason, foreign.message) == (ErrorReason.DEVICE_TOKEN_INVALID, "Invalid token")


async def test_a_viewer_method_admits_an_unlinked_phone_with_no_role(session, v2_tokens) -> None:
    admitted = await _admit(session, "ScheduleService/GetScheduleWindow", v2_tokens["unlinked"])
    assert admitted.device is not None and admitted.school_class is not None
    assert admitted.role is None


async def test_a_linked_method_refuses_an_unlinked_phone(session, v2_tokens) -> None:
    refusal = await _refusal(session, "MeService/GetCalendarFeed", v2_tokens["unlinked"])
    assert (refusal.reason, refusal.message) == (
        ErrorReason.DEVICE_NOT_LINKED,
        "device is not linked",
    )
    admitted = await _admit(session, "MeService/GetCalendarFeed", v2_tokens["viewer"])
    assert admitted.role is not None and admitted.role.value == "viewer"


@pytest.mark.parametrize(
    ("key", "below", "at", "needed"),
    [
        ("HomeworkService/CreateHomework", "viewer", "editor", "ROLE_EDITOR"),
        ("ClassService/UpdateClass", "editor", "admin", "ROLE_ADMIN"),
        ("ClassService/DeleteClass", "admin", "owner", "ROLE_OWNER"),
    ],
)
async def test_a_role_method_asks_for_a_link_first_and_then_the_role(
    session, v2_tokens, key, below, at, needed
) -> None:
    unlinked = await _refusal(session, key, v2_tokens["unlinked"])
    assert unlinked.reason is ErrorReason.DEVICE_NOT_LINKED

    stranger = await _refusal(session, key, v2_tokens["stranger"])
    assert stranger.reason is ErrorReason.ROLE_REQUIRED

    under = await _refusal(session, key, v2_tokens[below])
    assert under.reason is ErrorReason.ROLE_REQUIRED
    assert under.metadata == {"role": needed}
    assert under.message == f"{needed.removeprefix('ROLE_').lower()} role required"

    admitted = await _admit(session, key, v2_tokens[at])
    assert admitted.role is not None and admitted.role.value == at


async def test_a_diary_method_takes_the_diary_bearer_and_no_other(session, v2_tokens) -> None:
    missing = await _refusal(session, "DiaryService/ListStudents", None)
    assert (missing.reason, missing.message) == (
        ErrorReason.DIARY_TOKEN_INVALID,
        "Missing bearer token",
    )
    device = await _refusal(session, "DiaryService/ListStudents", v2_tokens["owner"])
    assert (device.reason, device.message) == (
        ErrorReason.DIARY_TOKEN_INVALID,
        "Diary session is not valid",
    )
    admitted = await _admit(session, "DiaryService/ListStudents", v2_tokens["diary"])
    assert admitted.diary is not None and admitted.device is None


async def test_a_diary_method_without_the_secret_touches_no_session(
    session, v2_tokens, monkeypatch
) -> None:
    """#302: v1 asked for the row first, and an unsealable credential expired it
    for good. The gate asks whether the diary runs at all before it reads a token."""
    monkeypatch.setattr("app.rpc.gate.diary_enabled", lambda: False)
    with pytest.raises(DiaryDisabled):
        await _admit(session, "DiaryService/ListStudents", v2_tokens["diary"])
    row = await session.scalar(select(DiarySession))
    await session.refresh(row)
    assert row.expired_at is None


async def test_the_client_version_is_asked_before_the_bearer(session, monkeypatch) -> None:
    monkeypatch.setattr(get_settings(), "min_client_version", 40)
    refusal = await _refusal(session, "ScheduleService/GetScheduleWindow", None, version="12")
    assert refusal.reason is ErrorReason.CLIENT_TOO_OLD


async def test_a_role_taken_away_is_gone_on_the_next_call(session, v2_tokens) -> None:
    from app.models import BotUser

    key = "ClassService/UpdateClass"
    assert (await _admit(session, key, v2_tokens["admin"])).role.value == "admin"
    member = await session.scalar(select(BotUser).where(BotUser.telegram_id == 2003))
    await session.delete(member)
    await session.commit()
    assert (await _refusal(session, key, v2_tokens["admin"])).reason is ErrorReason.ROLE_REQUIRED


async def test_a_device_call_is_seen(session, v2_tokens) -> None:
    from app.models import DeviceToken
    from app.security import hash_token

    await _admit(session, "MeService/GetMe", v2_tokens["viewer"])
    device = await session.scalar(
        select(DeviceToken).where(DeviceToken.token_hash == hash_token(v2_tokens["viewer"]))
    )
    await session.refresh(device)
    assert isinstance(device.last_seen_at, datetime)
```
and `server/tests/test_rpc_call.py`:
```python
"""One call's scope: the commit, the effects after it, and what a refusal keeps.

``invoke`` is driven directly here, with handlers put into ``HANDLERS`` for
the test, so that each rule of decision 4 is asked of the one function both
transports call (``docs/specs/2026-10-05-server-v2-design.md``).
"""

from __future__ import annotations

import logging
import re
from pathlib import Path

import pytest
from connectrpc.code import Code
from connectrpc.errors import ConnectError
from sqlalchemy import func, select

from app.api.deps import caller_bucket
from app.contract.lessons.v2 import school_class_connect
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.school_class_pb import GetClassRequest, GetClassResponse
from app.db import SessionLocal
from app.models import AuditEntry, DeviceToken, DiarySession
from app.rpc import call as call_module
from app.rpc.call import NOT_IMPLEMENTED, invoke
from app.rpc.errors import Refusal
from app.rpc.handlers import HANDLERS
from app.rpc.methods import METHODS
from app.security import hash_token

SERVER = Path(__file__).resolve().parents[1]
GET_CLASS = METHODS["lessons.v2.ClassService/GetClass"]
LIST_STUDENTS = METHODS["lessons.v2.DiaryService/ListStudents"]


def _bearer(token: str) -> list[tuple[str, str]]:
    return [("authorization", f"Bearer {token}")]


async def _audit_lines() -> int:
    async with SessionLocal() as fresh:
        return await fresh.scalar(select(func.count()).select_from(AuditEntry)) or 0


async def test_a_method_with_no_handler_answers_as_the_generated_protocol_does() -> None:
    """Before any gate: no credential is asked of a method nobody serves."""
    with pytest.raises(ConnectError) as protocol:
        await school_class_connect.ClassService.get_class(None, GetClassRequest(), None)
    with pytest.raises(ConnectError) as served:
        await invoke(GET_CLASS, GetClassRequest(), headers=[], peer=None)
    assert served.value.code is protocol.value.code is Code.UNIMPLEMENTED
    assert served.value.message == protocol.value.message == NOT_IMPLEMENTED


async def test_a_success_commits_before_its_effects_run(monkeypatch, v2_tokens, school_class):
    seen: list[int] = []

    async def handler(call, request):
        call.session.add(AuditEntry(class_id=school_class.id, action="test.v2", summary="written"))

        async def effect() -> None:
            # From a session of its own: only a committed row is visible here.
            seen.append(await _audit_lines())

        call.after_commit(effect)
        return GetClassResponse()

    monkeypatch.setitem(HANDLERS, GET_CLASS.key, handler)
    before = await _audit_lines()
    await invoke(GET_CLASS, GetClassRequest(), headers=_bearer(v2_tokens["admin"]), peer=None)
    assert seen == [before + 1]


async def test_a_refusal_rolls_back_and_runs_no_effect(monkeypatch, v2_tokens, school_class):
    ran: list[str] = []

    async def handler(call, request):
        call.session.add(AuditEntry(class_id=school_class.id, action="test.v2", summary="written"))

        async def effect() -> None:
            ran.append("effect")

        call.after_commit(effect)
        raise Refusal(ErrorReason.RESOURCE_NOT_FOUND, "no such thing", resource="class")

    monkeypatch.setitem(HANDLERS, GET_CLASS.key, handler)
    before = await _audit_lines()
    with pytest.raises(ConnectError) as refused:
        await invoke(GET_CLASS, GetClassRequest(), headers=_bearer(v2_tokens["admin"]), peer=None)
    assert refused.value.code is Code.NOT_FOUND
    assert await _audit_lines() == before
    assert ran == []


async def test_an_effect_that_fails_does_not_fail_the_call(monkeypatch, v2_tokens, caplog):
    async def handler(call, request):
        async def effect() -> None:
            raise RuntimeError("Telegram is down")

        call.after_commit(effect)
        return GetClassResponse()

    monkeypatch.setitem(HANDLERS, GET_CLASS.key, handler)
    with caplog.at_level(logging.WARNING, logger="app.rpc.call"):
        answer = await invoke(
            GET_CLASS, GetClassRequest(), headers=_bearer(v2_tokens["admin"]), peer=None
        )
    assert answer == GetClassResponse()
    assert any("after its commit" in record.getMessage() for record in caplog.records)


async def test_a_failure_the_table_does_not_know_is_internal(monkeypatch, v2_tokens):
    async def handler(call, request):
        raise KeyError("a bug")

    monkeypatch.setitem(HANDLERS, GET_CLASS.key, handler)
    with pytest.raises(ConnectError) as failed:
        await invoke(GET_CLASS, GetClassRequest(), headers=_bearer(v2_tokens["admin"]), peer=None)
    assert failed.value.code is Code.INTERNAL
    assert "a bug" not in failed.value.message


async def test_last_seen_is_kept_when_the_call_is_refused(monkeypatch, v2_tokens, session):
    async def handler(call, request):
        raise Refusal(ErrorReason.RESOURCE_NOT_FOUND, "no such thing", resource="class")

    monkeypatch.setitem(HANDLERS, GET_CLASS.key, handler)
    with pytest.raises(ConnectError):
        await invoke(GET_CLASS, GetClassRequest(), headers=_bearer(v2_tokens["editor"]), peer=None)
    device = await session.scalar(
        select(DeviceToken).where(DeviceToken.token_hash == hash_token(v2_tokens["editor"]))
    )
    await session.refresh(device)
    assert device.last_seen_at is not None


async def test_a_dead_diary_credential_stays_expired_when_the_call_is_refused(
    monkeypatch, session, school_class
):
    """``find_session`` expires a row whose credential will not open, and
    commits; the call's rollback does not bring it back."""
    session.add(
        DiarySession(
            token_hash=hash_token("dead-diary"),
            upstream_token="sealed with a key nobody has",
            login="parent@example.com",
            provider="petersburg",
        )
    )
    await session.commit()

    async def handler(call, request):
        raise AssertionError("the gate let a dead session through")

    monkeypatch.setitem(HANDLERS, LIST_STUDENTS.key, handler)
    with pytest.raises(ConnectError) as refused:
        await invoke(
            LIST_STUDENTS,
            LIST_STUDENTS.input(),
            headers=_bearer("dead-diary"),
            peer=None,
        )
    assert refused.value.code is Code.UNAUTHENTICATED
    row = await session.scalar(select(DiarySession))
    await session.refresh(row)
    assert row.expired_at is not None


async def test_a_call_buckets_its_caller_as_v1_does(monkeypatch, v2_tokens):
    buckets: list[str] = []

    async def handler(call, request):
        buckets.append(call.bucket(scope="diary:"))
        return GetClassResponse()

    monkeypatch.setitem(HANDLERS, GET_CLASS.key, handler)
    headers = [*_bearer(v2_tokens["admin"]), ("x-forwarded-for", "198.51.100.7")]
    await invoke(GET_CLASS, GetClassRequest(), headers=headers, peer="203.0.113.9")
    assert buckets == [caller_bucket(headers, "203.0.113.9", scope="diary:")]


def test_no_handler_commits() -> None:
    """The commit is ``invoke``'s: a handler that committed would make a refusal
    after it unable to roll back what came before."""
    offenders = [
        f"{path.relative_to(SERVER).as_posix()}:{number}"
        for folder in ("app/rpc", "app/rest")
        for path in sorted((SERVER / folder).rglob("*.py"))
        if path != Path(call_module.__file__)
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1)
        if re.search(r"\.commit\(", line)
    ]
    assert offenders == []
```
Run `python -m pytest -q -p no:xdist tests/test_rpc_gate.py tests/test_rpc_call.py`. Expected: collection errors, `ModuleNotFoundError: No module named 'app.rpc.methods'` and `No module named 'app.rpc.call'`.

- [ ] **Step 2: `MIN_CLIENT_VERSION`.** In `server/app/config.py`, replace `from pydantic import Field` with `from pydantic import Field, field_validator`, and replace the four lines
```python
    public_base_url: str = ""

    @property
    def owner_id_list(self) -> list[int]:
```
with
```python
    public_base_url: str = ""

    # The oldest v2 client this server still answers, as the APK's versionCode
    # in its `X-Lessons-Client` header (docs/specs/2026-10-05-server-v2-design.md,
    # decision 9). Empty, or 0, means no minimum. With one set, a present
    # version below it is refused with CLIENT_TOO_OLD and the app asks to be
    # updated; a request with no header is never refused, so a third-party
    # client or a `curl` is not an old APK. Optional on purpose: not in
    # `deployment_problems`, because no feature is off without it.
    min_client_version: int = Field(default=0, ge=0)

    @field_validator("min_client_version", mode="before")
    @classmethod
    def _empty_is_no_minimum(cls, value: object) -> object:
        """``MIN_CLIENT_VERSION=`` is how `.env.example` shows a setting left
        off, and pydantic would refuse an empty string for an integer — at
        import, taking v1 down with it. Empty means zero, as the comment says."""
        if isinstance(value, str) and not value.strip():
            return 0
        return value

    @property
    def owner_id_list(self) -> list[int]:
```
In `server/.env.example`, insert above the paragraph that begins `# One variable is deliberately NOT here: VERCEL.`:
```text
# The oldest app this server still answers over v2, as the APK's versionCode,
# which every v2 request carries in its X-Lessons-Client header. Empty or 0 is
# no minimum. With one set, an app that says it is older is refused with
# CLIENT_TOO_OLD and asks to be updated; a request that says nothing is never
# refused. Raise it only after the newer APK is on the phones it would refuse.
# MIN_CLIENT_VERSION=

```
(`tests/test_env_example.py` requires every setting in the file, and accepts it commented out; nothing is added to `deployment_problems` or `disabled_features`.)

In `docker-compose.yml`, in `services.server.environment`, insert immediately below `      PUBLIC_BASE_URL: ${PUBLIC_BASE_URL:-}`:
```yaml
      # A number, but unset is its default all the same: the setting reads an
      # empty value as 0, no minimum (app/config.py), as .env.example leaves it.
      MIN_CLIENT_VERSION: ${MIN_CLIENT_VERSION:-}
```
`tests/test_compose.py` holds both halves of that line. `test_the_server_is_handed_every_setting_the_code_reads` fails on any `Settings` field the server's environment does not list and `NOT_FORWARDED` does not excuse, and `MIN_CLIENT_VERSION` is one from this step on. `test_a_setting_nobody_set_reaches_the_server_as_its_own_default` builds `Settings(min_client_version="")`, which the `mode="before"` validator above turns into `0`, the field's default.

- [ ] **Step 3: Create `server/app/rpc/methods.py`.**
```python
"""Every method of the contract, read once from the generated descriptors.

Three readers need the same facts about a method — the gate (its credential
and least role), the RPC adapters (its service, its Python name) and the REST
transcoder (its ``google.api.http`` rule, its message classes) — so they are
read here, at import, into one table keyed ``"lessons.v2.<Service>/<Method>"``,
the Connect path without its leading slash. Nothing about a method is written
by hand: a method added to ``proto/`` is in this table on the next
``buf generate``, with no row to forget.
"""

from __future__ import annotations

import importlib
import pkgutil
import re
from dataclasses import dataclass
from types import ModuleType

from protobuf import DescMessage, DescMethod, Message

import app.contract.lessons.v2 as contract_v2
from app.contract.google.api import annotations_pb
from app.contract.lessons.v2 import options_pb

#: A path variable of a ``google.api.http`` template: ``{year}``, ``{task.id}``.
#: A ``{name=pattern}`` form is matched so that it can be refused at import —
#: the contract has none, and the transcoder binds only plain variables.
VARIABLE = re.compile(r"\{([^}=]+)(=[^}]*)?\}")


@dataclass(frozen=True)
class Binding:
    """A method's REST binding, as its ``google.api.http`` rule writes it."""

    #: "get", "post", "patch" or "delete".
    verb: str
    #: The template as the proto writes it, ``/v2/…``; it is served under ``/api``.
    path: str
    #: "" for no body, "*" for the whole request, or the request field it is.
    body: str

    @property
    def variables(self) -> list[str]:
        """The path's variables, dotted ones as written: ``["task.id"]``."""
        return [match.group(1) for match in VARIABLE.finditer(self.path)]


@dataclass(frozen=True)
class Method:
    """One method of the contract, and everything a transport needs to serve it."""

    #: The service's full name, ``"lessons.v2.ScheduleService"``.
    service: str
    #: The method's name, ``"GetScheduleWindow"``.
    name: str
    input: type[Message]
    output: type[Message]
    auth: options_pb.AuthKind
    #: The least role, or ``None`` where the method names none.
    min_role: options_pb.Role | None
    side_effect_free: bool
    streaming: bool
    #: ``None`` for a stream, which REST cannot carry.
    binding: Binding | None

    @property
    def key(self) -> str:
        """``"lessons.v2.ScheduleService/GetScheduleWindow"``: the Connect path."""
        return f"{self.service}/{self.name}"

    @property
    def attribute(self) -> str:
        """The method's name on the generated ``Protocol``: ``get_schedule_window``."""
        return re.sub(r"(?<!^)(?=[A-Z])", "_", self.name).lower()


def _module_of(file_name: str) -> ModuleType:
    """The generated module of a proto file: ``lessons/v2/me.proto`` → ``…me_pb``."""
    dotted = file_name.removesuffix(".proto").replace("/", ".")
    return importlib.import_module(f"app.contract.{dotted}_pb")


def message_class(desc: DescMessage) -> type[Message]:
    """The generated class of a top-level message, from its descriptor.

    Every request and response of the contract is top-level in its own file
    (Buf's ``RPC_REQUEST_STANDARD_NAME``), so its name in that module is its
    proto name; ``test_rpc_gate.py`` checks each class's ``desc()`` is the
    descriptor it was found by."""
    return getattr(_module_of(desc.file.proto.name), desc.name)


def _binding(desc: DescMethod) -> Binding | None:
    options = desc.proto.options
    if options is None or annotations_pb.ext_http not in options:
        return None
    rule = options[annotations_pb.ext_http]
    if rule.pattern is None:
        return None
    binding = Binding(verb=rule.pattern.field, path=rule.pattern.value, body=rule.body or "")
    for match in VARIABLE.finditer(binding.path):
        if match.group(2):
            raise RuntimeError(f"{desc.name}: no pattern variable is bound: {match.group(0)}")
    return binding


def _method(desc: DescMethod) -> Method:
    options = desc.proto.options
    if options is None or options_pb.ext_auth not in options:
        raise RuntimeError(f"{desc.parent.type_name}.{desc.name} names no credential")
    min_role = options[options_pb.ext_min_role] if options_pb.ext_min_role in options else None
    return Method(
        service=desc.parent.type_name,
        name=desc.name,
        input=message_class(desc.input),
        output=message_class(desc.output),
        auth=options[options_pb.ext_auth],
        min_role=min_role,
        side_effect_free=desc.idempotency.name == "NO_SIDE_EFFECTS",
        streaming=desc.method_kind != "unary",
        binding=_binding(desc),
    )


def _read() -> dict[str, Method]:
    methods: dict[str, Method] = {}
    for module in pkgutil.iter_modules(contract_v2.__path__):
        if not module.name.endswith("_pb"):
            continue
        generated = importlib.import_module(f"{contract_v2.__name__}.{module.name}")
        for service in generated.desc().services:
            for desc in service.methods:
                method = _method(desc)
                methods[method.key] = method
    return methods


#: Every method of ``lessons.v2``, keyed ``"lessons.v2.<Service>/<Method>"``.
METHODS: dict[str, Method] = _read()
```

- [ ] **Step 4: Create `server/app/rpc/values.py`** with the two conversions the gate and `GetMe` need now:
```python
"""Model ↔ message conversions every handler shares.

One place for the conventions ``common.proto`` states, so that two handlers
cannot write a date, a time or a role two ways: a date is ``"YYYY-MM-DD"``, a
time of day ``"HH:MM"`` (v1 wrote seconds; v2 does not), an instant a
``Timestamp``, and an enum is matched to the model's by its member name —
``DayKind.SELF_STUDY`` is ``DAY_KIND_SELF_STUDY`` — so a value added to one
and not the other is a ``KeyError`` in a test rather than a silent default.
"""

from __future__ import annotations

from protobuf import Enum

from app.contract.lessons.v2 import options_pb
from app.models import Role


def proto_name(member: Enum) -> str:
    """The value's name as the proto writes it — ``"ROLE_ADMIN"``, not the
    generated member's ``ADMIN`` — which is what metadata and JSON carry."""
    return next(value.name for value in type(member).desc().values if value.number == member.value)


def role(value: Role | None) -> options_pb.Role:
    return options_pb.Role[value.name] if value is not None else options_pb.Role.UNSPECIFIED
```

- [ ] **Step 5: Create `server/app/rpc/gate.py`.**
```python
"""The generic gate: who may call a method, decided from the method itself.

Every method of the contract names its credential (``(lessons.v2.auth)``) and,
when it acts on the class, its least role (``(lessons.v2.min_role)``).
:data:`TABLE` is those two options, read once at import from the descriptors;
:func:`admit` asks them of every call before any handler runs, so a handler
cannot forget a check — it never makes one
(``docs/specs/2026-10-05-server-v2-design.md``, decision 3). In this order:

1. the client version, so an old APK with a dead token is told to update
   rather than to sign in again;
2. for a diary method, whether the diary runs at all — before any token is
   resolved, so that a deployment without ``DIARY_SECRET`` touches no session
   row (#302: v1 expires every session it is asked about);
3. the bearer of the method's kind, by ``api/deps.py``'s rules, with the
   device's class and its ``last_seen_at``;
4. a linked account, for ``DEVICE_LINKED`` and for any least role above
   viewer, refused **before** the role is read, as v1's three role checks do;
5. the role, read with ``linking.effective_role`` on every call, so a role
   taken away in the bot is gone here in the same instant.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import bearer, find_device, header, touch_last_seen
from app.config import Settings
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.options_pb import AuthKind
from app.contract.lessons.v2.options_pb import Role as ProtoRole
from app.crypto import diary_enabled
from app.models import DeviceToken, DiarySession, Role, SchoolClass
from app.rpc.errors import Refusal
from app.rpc.methods import METHODS, Method
from app.rpc.values import proto_name
from app.services import linking
from app.services.diary import DiaryDisabled, find_session

#: The header every v2 request carries its client's version in.
CLIENT_HEADER = "x-lessons-client"

#: The largest versionCode an APK can carry: `android/app/build.gradle.kts`
#: refuses to build above it, because Google Play will not publish above it.
MAX_CLIENT_VERSION = 2_100_000_000

#: Each method's ``(auth, min_role)``, as the contract declares them. Compared
#: with the descriptors, read independently, for all 76 methods by
#: ``test_rpc_gate.py``.
TABLE: dict[str, tuple[AuthKind, ProtoRole | None]] = {
    key: (method.auth, method.min_role) for key, method in METHODS.items()
}

#: v1's words for each refusal the gate makes (``api/deps.py``,
#: ``api/diary.py``, the three role checks), kept, because the shell's
#: words are v1's wherever v1 had them.
MISSING_BEARER = "Missing bearer token"
UNKNOWN_DEVICE = "Invalid token"
UNKNOWN_DIARY_SESSION = "Diary session is not valid"
CLASS_GONE = "Class no longer exists"
NOT_LINKED = "device is not linked"
#: New in v2, so English like v1's own generic answers.
BAD_CLIENT_HEADER = f"X-Lessons-Client must be a whole number between 1 and {MAX_CLIENT_VERSION}"
CLIENT_TOO_OLD = "This app is older than the oldest version this server answers; update it"

#: Header lines of a request, names in any case, repeated lines kept.
Headers = Sequence[tuple[str, str]]


@dataclass
class Admitted:
    """What the gate found out about a call it let through."""

    #: The ``X-Lessons-Client`` version, when the client sent a usable one.
    client_version: int | None = None
    device: DeviceToken | None = None
    school_class: SchoolClass | None = None
    #: The linked account's role in the class; ``None`` for an unlinked device
    #: or an account that is not a member. Read for every device method,
    #: because ``GetMe`` and the window's ``DeviceAccess`` answer it.
    role: Role | None = None
    diary: DiarySession | None = None


def client_version(headers: Headers, settings: Settings) -> int | None:
    """The caller's ``X-Lessons-Client``, or a refusal (decision 9).

    A missing header is never refused, with a minimum set or not: the minimum
    retires the family's old APKs, which all send it, and a third-party client
    or a ``curl`` without it is not an old APK. A header that is not a whole
    number from 1 to :data:`MAX_CLIENT_VERSION` is refused only when a minimum
    is set — without one there is nothing to compare it with, and it is
    ignored like a missing one.
    """
    raw = header(headers, CLIENT_HEADER)
    if raw is None:
        return None
    text = raw.strip()
    # Ten ASCII digits at most before `int` is asked, so a header of a million
    # digits is never parsed; then the build's own ceiling.
    version = int(text) if text.isascii() and text.isdecimal() and len(text) <= 10 else 0
    if version > MAX_CLIENT_VERSION:
        version = 0
    minimum = settings.min_client_version
    if minimum <= 0:
        return version or None
    if version <= 0:
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            BAD_CLIENT_HEADER,
            violations=[("X-Lessons-Client", BAD_CLIENT_HEADER)],
        )
    if version < minimum:
        raise Refusal(ErrorReason.CLIENT_TOO_OLD, CLIENT_TOO_OLD, min_version=minimum)
    return version


def _needs_link(method: Method) -> bool:
    above_viewer = method.min_role is not None and method.min_role > ProtoRole.VIEWER
    return method.auth is AuthKind.DEVICE_LINKED or above_viewer


async def admit(
    method: Method, session: AsyncSession, settings: Settings, headers: Headers
) -> Admitted:
    """Everything the gate checks, in the order the module says, or a refusal."""
    admitted = Admitted(client_version=client_version(headers, settings))
    if method.auth is AuthKind.NONE:
        return admitted

    token = bearer(header(headers, "authorization"))
    if method.auth is AuthKind.DIARY:
        if not diary_enabled():
            raise DiaryDisabled("DIARY_SECRET is unusable")
        row = await find_session(session, token) if token is not None else None
        if row is None:
            sentence = MISSING_BEARER if token is None else UNKNOWN_DIARY_SESSION
            raise Refusal(ErrorReason.DIARY_TOKEN_INVALID, sentence)
        admitted.diary = row
        return admitted

    device = await find_device(session, token) if token is not None else None
    if device is None:
        sentence = MISSING_BEARER if token is None else UNKNOWN_DEVICE
        raise Refusal(ErrorReason.DEVICE_TOKEN_INVALID, sentence)
    await touch_last_seen(session, device)
    # After the touch, not before: a rollback inside it expires what the
    # session holds, and this is the row the handler is about to read.
    school_class = await session.get(SchoolClass, device.class_id)
    if school_class is None:
        raise Refusal(ErrorReason.RESOURCE_NOT_FOUND, CLASS_GONE, resource="class")
    if _needs_link(method) and device.telegram_id is None:
        raise Refusal(ErrorReason.DEVICE_NOT_LINKED, NOT_LINKED)

    role = await linking.effective_role(session, device)
    if method.min_role is not None and method.min_role > ProtoRole.VIEWER:
        least = Role[method.min_role.name]
        if role is None or not role.at_least(least):
            raise Refusal(
                ErrorReason.ROLE_REQUIRED,
                f"{least.value} role required",
                role=proto_name(method.min_role),
            )
    admitted.device, admitted.school_class, admitted.role = device, school_class, role
    return admitted
```

- [ ] **Step 6: Create `server/app/rpc/handlers.py`**, empty until Task 6:
```python
"""Which methods this deployment serves, and the handler of each.

A method missing here answers ``UNIMPLEMENTED`` on both transports, before
any gate or scope, exactly as the generated ``Protocol``'s default does. 3a
serves four methods and ``WatchClass``'s refusal; 3b fills the rest in, one
service at a time.

Handler modules import ``Call`` only for their annotations, so that
``call.py``, which imports this table, is never imported back.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

#: A handler: ``async def handler(call: Call, request: <Method>Request) -> <Method>Response``.
Handler = Callable[[Any, Any], Awaitable[Any]]

#: Keyed as ``rpc.methods.METHODS`` is: ``"lessons.v2.<Service>/<Method>"``.
HANDLERS: dict[str, Handler] = {}
```

- [ ] **Step 7: Create `server/app/rpc/call.py`.**
```python
"""One call of one v2 method: its gate, its scope, its handler, its commit, its effects.

Both transports call :func:`invoke` — the RPC adapters with what Connect's
``RequestContext`` carries, the REST transcoder with what Starlette's request
does — so REST and RPC cannot disagree about a rule
(``docs/specs/2026-10-05-server-v2-design.md``, decisions 3 and 4). A handler
is a plain ``async`` function of ``(call, request)`` that knows neither.

**The scope.** Each call opens a dishka ``REQUEST`` scope from the process
container, the way the bot's ``ContextMiddleware`` does and not through
``setup_dishka``: it reads ``di.container()`` per call, because a mounted ASGI
app gets no lifespan and a captured container would outlive a shutdown.

**The commit is here, and only here.** On success the session commits, then
the call's effects run in order with the session still open — a Telegram
notice reads its recipients from it — and an effect that fails is logged and
dropped, as v1's ``edit._tell`` is: the change is already saved. On a refusal
the session rolls back and no effect runs. ``test_rpc_call.py`` greps
``app/rpc`` for ``.commit(`` outside this module.

**What a refusal does not roll back, on purpose.** Services that commit inside
themselves keep doing so, and their writes stay when the call is refused:

- ``JoinThrottle.admit`` — a wrong join code stays counted;
- ``services.join.join`` — the device token it mints is committed inside
  the call, as v1's ``/join`` committed it, so a refusal raised after it
  would not take the phone's token back (``CreateDevice`` raises none);
- ``services.diary.find_session`` (and ``_expire``, ``_remember_token``) — a
  dead diary credential stays expired, a rotated one stays kept;
- ``api.deps.touch_last_seen`` — the device stays seen.

Each has a test over v2.
"""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass, field
from typing import Any

from connectrpc.code import Code
from connectrpc.errors import ConnectError
from protobuf import Message
from sqlalchemy.ext.asyncio import AsyncSession

from app import di
from app.api.deps import caller_bucket
from app.config import Settings
from app.models import DeviceToken, DiarySession, Role, SchoolClass
from app.rpc import gate
from app.rpc.errors import connect_error
from app.rpc.handlers import HANDLERS
from app.rpc.methods import Method

log = logging.getLogger(__name__)

#: What a method with no handler answers, on both transports: the generated
#: ``Protocol``'s own default, word for word, before any gate or scope.
#: ``test_rpc_call.py`` holds the two level.
NOT_IMPLEMENTED = "Not implemented"

Effect = Callable[[], Awaitable[None]]


@dataclass
class Call:
    """What a handler is handed: everything the gate found, and the session."""

    method: Method
    session: AsyncSession
    settings: Settings
    headers: Sequence[tuple[str, str]]
    #: The caller's host, without a port; ``None`` when the server knows none.
    peer: str | None
    client_version: int | None = None
    device: DeviceToken | None = None
    school_class: SchoolClass | None = None
    role: Role | None = None
    diary: DiarySession | None = None
    effects: list[Effect] = field(default_factory=list)

    def bucket(self, scope: str = "") -> str:
        """The caller's rate-limit bucket — v1's, for the same caller."""
        return caller_bucket(self.headers, self.peer, scope=scope)

    def after_commit(self, effect: Effect) -> None:
        """Run ``effect`` once the call's change is committed, and never if it is not."""
        self.effects.append(effect)

    def device_and_class(self) -> tuple[DeviceToken, SchoolClass]:
        """The device and its class, which the gate set for every device method."""
        if self.device is None or self.school_class is None:
            raise RuntimeError(f"{self.method.key} asked for a device it does not take")
        return self.device, self.school_class


async def _run_effects(call: Call) -> None:
    for effect in call.effects:
        try:
            await effect()
        except Exception:
            log.warning("an effect of %s failed after its commit", call.method.key, exc_info=True)


async def invoke(
    method: Method,
    request: Message,
    *,
    headers: Sequence[tuple[str, str]],
    peer: str | None,
) -> Any:
    """Serve one call of ``method``, or raise the ``ConnectError`` it answers with."""
    handler = HANDLERS.get(method.key)
    if handler is None:
        raise ConnectError(Code.UNIMPLEMENTED, NOT_IMPLEMENTED)
    try:
        async with di.container()() as scope:
            session = await scope.get(AsyncSession)
            settings = await scope.get(Settings)
            try:
                admitted = await gate.admit(method, session, settings, headers)
                call = Call(
                    method=method,
                    session=session,
                    settings=settings,
                    headers=headers,
                    peer=peer,
                    client_version=admitted.client_version,
                    device=admitted.device,
                    school_class=admitted.school_class,
                    role=admitted.role,
                    diary=admitted.diary,
                )
                response = await handler(call, request)
                await session.commit()
            except Exception:
                await session.rollback()
                raise
            await _run_effects(call)
            return response
    except ConnectError:
        raise
    except Exception as failure:
        raise connect_error(failure) from None
```

- [ ] **Step 8: The layering test walks both ways (decision 2).** In `server/tests/test_service_layering.py`:
  1. Replace `def _chains_into_the_bot() -> list[str]:` and its first line `    modules = _modules()` with:
```python
#: The v1 routers a v2 module may not import (the server-v2 design, decision 2):
#: a rule a v2 handler needs moves out of them into ``services/`` first.
V1_ROUTERS = ("app.api.public", "app.api.edit", "app.api.manage", "app.api.diary")


def _within(dotted: str, packages: tuple[str, ...]) -> bool:
    return any(dotted == package or dotted.startswith(package + ".") for package in packages)


def _chains(starts: tuple[str, ...], forbidden: tuple[str, ...]) -> list[str]:
    """Every import chain from a module under ``starts`` into one under
    ``forbidden``, followed through the rest of ``app/``."""
    modules = _modules()
```
  2. In that function replace `    for start in sorted(name for name in modules if name.startswith("app.services")):` with `    for start in sorted(name for name in modules if _within(name, starts)):` and `                if _is_forbidden(target):` with `                if _within(target, forbidden):`.
  3. Insert above `def test_no_service_reaches_the_bot():`
```python
def _chains_into_the_bot() -> list[str]:
    return _chains(("app.services",), (FORBIDDEN,))


```
  4. Append at the end of the file:
```python


def test_no_service_reaches_the_shells_above_it():
    """``services/`` is what the shells stand on: v1's routers, v2's handlers
    and its transcoder. A service that imported one of them would make the
    other shells import it too — and v2's cold start carry v1's."""
    chains = _chains(("app.services",), ("app.rpc", "app.rest", "app.api"))
    assert chains == [], "\n".join(chains)


def test_v2_reaches_neither_the_bot_nor_a_v1_router():
    """``rpc/`` and ``rest/`` stand on ``services/``, ``api/deps.py`` and the
    contract. A v1 router reached from them would be a rule with two shells
    and one home in the wrong one; the bot would be aiogram on the API's cold
    start."""
    chains = _chains(("app.rpc", "app.rest"), (FORBIDDEN, *V1_ROUTERS))
    assert chains == [], "\n".join(chains)


def test_the_walk_sees_a_v2_module_reach_a_v1_router():
    """Held here rather than trusted: the walk above finds a chain through a
    neutral module, not only a direct import."""
    tree = ast.parse("from app.api.public import router")
    names = _imported_names(tree, "app.rpc.example", False)
    assert any(_within(name, V1_ROUTERS) for name in names)
    assert not _within("app.api.deps", V1_ROUTERS)
    assert not _within("app.api.publicity", V1_ROUTERS)
```

- [ ] **Step 9: The gate's reasons are produced now.** In `server/tests/test_rpc_errors.py`, delete from `LATER` the six lines whose value is `"3a: the gate"`.

- [ ] **Step 10: Green.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m pytest -q -p no:xdist tests/test_rpc_gate.py tests/test_rpc_call.py tests/test_service_layering.py tests/test_rpc_errors.py tests/test_env_example.py tests/test_compose.py
```
Expected: all pass — `test_rpc_gate.py` 25, `test_rpc_call.py` 9, `test_service_layering.py` 6, and `test_compose.py`'s three with `MIN_CLIENT_VERSION` forwarded.

- [ ] **Step 11: Gates.** ruff clean; mypy `Success: no issues found in 209 source files`; the full suite once, previous count plus 37.

- [ ] **Step 12: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/rpc server/app/config.py server/.env.example docker-compose.yml server/tests/conftest.py server/tests/test_rpc_gate.py server/tests/test_rpc_call.py server/tests/test_service_layering.py server/tests/test_rpc_errors.py && git commit -F - <<'EOF'
Serve one v2 call through one gate, one scope and one commit

app/rpc/methods.py reads every method of the contract once from the
generated descriptors: its credential and least role, its REST binding,
its message classes. gate.py checks them before any handler runs, in the
design's order: the client version (X-Lessons-Client against an optional
MIN_CLIENT_VERSION), whether the diary runs before a diary token is
resolved (#302), the bearer by api/deps.py's rules, a linked account before
the role is read, and the role on every call. call.py's invoke opens a
dishka scope per call, runs the handler, commits, and only then runs the
call's effects, which never fail it; a refusal rolls back and runs none.
The writes services commit themselves (the throttle, the diary session,
last_seen_at) stay on purpose, and each has a test.

A method with no handler answers the generated Protocol's own
UNIMPLEMENTED, before any gate. MIN_CLIENT_VERSION is optional and in no
list a deployment insists on; docker-compose.yml hands it to the server,
as it does every setting. X-Lessons-Client is at most ten digits and at
most 2,100,000,000, the build's own ceiling on a versionCode. The
layering test now walks both ways:
services reach no shell above them, and v2 reaches neither the bot nor a v1
router.

Not covered: no transport calls invoke yet, and no handler is registered.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 6: Connect at `/api/rpc`, its two guards, `WatchClass`, and a mount that cannot take v1 down

Decisions 3, 7 and 8. After this task every method is reachable over Connect and gRPC-Web; none but `WatchClass` has a handler yet.

**Files:**
- Modify: `server/app/rpc/__init__.py` (rewrite), `server/app/rpc/handlers.py`, `server/app/main.py`, `server/tests/conftest.py`, `server/tests/test_contract.py`, `server/tests/test_rpc_errors.py`
- Create: `server/app/rpc/watch.py`, `server/tests/test_rpc_mount.py`

**Interfaces:**
- Consumes: Task 5's `METHODS`, `Method.attribute`, `invoke`, `HANDLERS`; Task 4's `connect_error`, `undecodable`, `Refusal`; Task 2's `deps.peer_host`.
- Produces:
  - `app.rpc`: `rpc_app() -> ASGI app` (the seventeen generated service apps under one dispatcher), `GRPC_REFUSED: str`, `CODECS`.
  - `app.rpc.watch`: `STREAMING = "streaming"`, `NOT_HERE`, `async watch_class(call, request) -> WatchClassResponse` (always refuses in 3a).
  - `app.main`: `mount_v2(target: FastAPI) -> bool`, `V2_UNAVAILABLE`.
  - The harness's Connect half, in `conftest.py`: fixture `v2` yielding `_V2` with `http: httpx.AsyncClient` (peer `203.0.113.9:52144`), `await v2.connect(name: str, request=None, *, token=None, headers=None, binary=False) -> _V2Answer`, `await v2.stream(name, request=None, *, token=None, headers=None) -> _V2Answer`, `_V2.method(name)`. `name` is `"<Service>/<Method>"` without the package, e.g. `"MeService/GetMe"`. `_V2Answer` has `transport`, `status`, `headers`, `body`, `message`, `code` (`"PERMISSION_DENIED"`), `reason`, `metadata: dict[str, str]`, `error` (the sentence), `violations: list[tuple[str, str]]`, `retry_seconds`, and `outcome()`.

- [ ] **Step 1: Red.** Append the harness's Connect half to `server/tests/conftest.py`, after its last line:
```python




# --------------------------------------------------------------------------
# v2, called both ways (docs/specs/2026-10-05-server-v2-design.md, decision 14)
#
# `v2` calls one method over REST, through the app on httpx's ASGI transport,
# and over Connect, as the plain HTTP POST of canonical JSON (or binary) that
# Connect's unary protocol is — no client library, so what is tested is the
# wire. `both` asserts the two answers are one outcome, which is how «REST
# and RPC cannot disagree» is held rather than hoped. The REST request is
# built from the method's own `google.api.http` rule, here, in the client's
# direction, independently of the transcoder that reads it back.
# --------------------------------------------------------------------------

_DOMAIN = "lessons.app"


@dataclass
class _V2Answer:
    """One transport's answer, read into what both transports carry."""

    transport: str
    status: int
    headers: httpx.Headers
    body: bytes
    #: The response message on success; a REST 304 reads as `not_modified`.
    message: Any = None
    #: The canonical code's name, "PERMISSION_DENIED", on a refusal.
    code: str | None = None
    reason: str | None = None
    metadata: dict[str, str] = field(default_factory=dict)
    #: The refusal's sentence.
    error: str | None = None
    violations: list[tuple[str, str]] = field(default_factory=list)
    retry_seconds: int | None = None

    def outcome(self) -> tuple[Any, ...]:
        if self.code is None:
            return ("ok", self.message)
        return (
            self.code,
            self.reason,
            self.metadata,
            self.error,
            self.violations,
            self.retry_seconds,
        )


def _v2_details(answer: _V2Answer, details: list[tuple[str, Any]]) -> None:
    """Fill ``answer`` from ``(type name, message)`` pairs of either transport."""
    for type_name, message in details:
        if type_name == "google.rpc.ErrorInfo":
            assert message.domain == _DOMAIN
            answer.reason = message.reason
            answer.metadata = dict(message.metadata)
        elif type_name == "google.rpc.BadRequest":
            answer.violations = [(v.field, v.description) for v in message.field_violations]
        elif type_name == "google.rpc.RetryInfo":
            answer.retry_seconds = message.retry_delay.seconds


class _V2:
    """`await v2.both("MeService/GetMe", GetMeRequest(), token=…)`, and its halves."""

    def __init__(self, http: httpx.AsyncClient) -> None:
        self.http = http

    @staticmethod
    def method(name: str) -> Any:
        from app.rpc.methods import METHODS

        return METHODS[f"lessons.v2.{name}"]

    @staticmethod
    def _headers(token: str | None, headers: dict[str, str] | None) -> dict[str, str]:
        sent = dict(headers or {})
        if token is not None:
            sent["Authorization"] = f"Bearer {token}"
        return sent

    async def connect(
        self,
        name: str,
        request: Any = None,
        *,
        token: str | None = None,
        headers: dict[str, str] | None = None,
        binary: bool = False,
    ) -> _V2Answer:
        method = self.method(name)
        request = request if request is not None else method.input()
        sent = self._headers(token, headers)
        sent["Content-Type"] = "application/proto" if binary else "application/json"
        sent["Connect-Protocol-Version"] = "1"
        response = await self.http.post(
            f"/api/rpc/{method.key}",
            content=request.to_binary() if binary else request.to_json().encode(),
            headers=sent,
        )
        answer = _V2Answer("connect", response.status_code, response.headers, response.content)
        if response.status_code == 200:
            answer.message = (
                method.output.from_binary(response.content)
                if binary
                else method.output.from_json(response.content)
            )
        else:
            self._connect_error(answer, response.json())
        return answer

    async def stream(
        self,
        name: str,
        request: Any = None,
        *,
        token: str | None = None,
        headers: dict[str, str] | None = None,
    ) -> _V2Answer:
        """A server-streaming call over Connect: its end message's error, if any."""
        import struct

        method = self.method(name)
        request = request if request is not None else method.input()
        payload = request.to_json().encode()
        sent = self._headers(token, headers)
        sent["Content-Type"] = "application/connect+json"
        response = await self.http.post(
            f"/api/rpc/{method.key}",
            content=struct.pack(">BI", 0, len(payload)) + payload,
            headers=sent,
        )
        answer = _V2Answer("stream", response.status_code, response.headers, response.content)
        data = response.content
        while data:
            flags, length = struct.unpack(">BI", data[:5])
            frame, data = data[5 : 5 + length], data[5 + length :]
            if flags & 0x02:
                end = json.loads(frame)
                if "error" in end:
                    self._connect_error(answer, end["error"])
        return answer

    @staticmethod
    def _connect_error(answer: _V2Answer, error: dict[str, Any]) -> None:
        import base64

        from app.contract.google.rpc import error_details_pb

        answer.code, answer.error = error["code"].upper(), error.get("message")
        details = []
        for detail in error.get("details", []):
            cls = getattr(error_details_pb, detail["type"].rpartition(".")[2])
            raw = base64.b64decode(detail["value"] + "=" * (-len(detail["value"]) % 4))
            details.append((detail["type"], cls.from_binary(raw)))
        _v2_details(answer, details)


@pytest.fixture
async def v2() -> AsyncIterator[_V2]:
    """The app, reached over REST and Connect from one peer address."""
    from app.main import app

    transport = httpx.ASGITransport(app=app, client=("203.0.113.9", 52144))
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as http:
        yield _V2(http)
```
Create `server/tests/test_rpc_mount.py`:
```python
"""v2 over Connect, mounted at ``/api/rpc``: the guards, the stream's refusal, the fail-safe.

Each guard stands in front of a defect ``connectrpc`` 0.12.1 shows under
HTTP/1.1, and each test sends the request that found it
(``docs/specs/2026-10-05-server-v2-design.md``, decision 7).
"""

from __future__ import annotations

import sys

import httpx
import pytest
from fastapi import FastAPI

from app import main
from app.api.public import router as public_router
from app.config import get_settings
from app.contract.lessons.v2.school_class_pb import GetClassRequest
from app.rpc import GRPC_REFUSED, rpc_app


async def test_native_grpc_over_http_1_1_is_refused_before_the_library(v2) -> None:
    for content_type in ("application/grpc", "application/grpc+proto", "application/grpc+json"):
        response = await v2.http.post(
            "/api/rpc/lessons.v2.ClassService/GetClass",
            content=b"\x00\x00\x00\x00\x00",
            headers={"Content-Type": content_type},
        )
        assert response.status_code == 415, content_type
        assert response.text == GRPC_REFUSED


async def test_grpc_web_is_not_native_grpc(v2) -> None:
    """gRPC-Web runs over HTTP/1.1, and ``connectrpc`` serves it: the guard
    must not take ``application/grpc-web…`` for ``application/grpc…``."""
    response = await v2.http.post(
        "/api/rpc/lessons.v2.ClassService/GetClass",
        content=b"\x00\x00\x00\x00\x00",
        headers={"Content-Type": "application/grpc-web+proto"},
    )
    assert response.status_code == 200
    assert b"grpc-status: 12" in response.content


async def test_native_grpc_over_http_2_reaches_the_library() -> None:
    """On the host target, over HTTP/2 with trailers, the guard steps aside
    (3c serves it). Asked of the app directly, with the scope an HTTP/2 server
    would build, because httpx's ASGI transport speaks only HTTP/1.1."""
    sent: list[dict] = []
    body = [{"type": "http.request", "body": b"\x00\x00\x00\x00\x00", "more_body": False}]

    async def receive() -> dict:
        return body.pop(0) if body else {"type": "http.disconnect"}

    async def send(message: dict) -> None:
        sent.append(message)

    scope = {
        "type": "http",
        "http_version": "2",
        "method": "POST",
        "scheme": "http",
        "path": "/lessons.v2.ClassService/GetClass",
        "root_path": "",
        "query_string": b"",
        "headers": [(b"content-type", b"application/grpc"), (b"te", b"trailers")],
        "client": ("203.0.113.9", 52144),
        "extensions": {"http.response.trailers": {}},
    }
    await rpc_app()(scope, receive, send)
    assert sent[0]["status"] == 200
    trailers = dict(next(m for m in sent if m["type"] == "http.response.trailers")["headers"])
    assert trailers[b"grpc-status"] == b"12"


async def test_a_scope_that_names_no_http_version_is_refused_as_http_1_1() -> None:
    """ASGI makes ``http_version`` optional and reads a missing one as "1.1",
    so a server that leaves it out must not walk native gRPC past the guard
    into the library's 500. Asked of the app directly, as the HTTP/2 case is."""
    sent: list[dict] = []

    async def receive() -> dict:
        return {"type": "http.request", "body": b"\x00\x00\x00\x00\x00", "more_body": False}

    async def send(message: dict) -> None:
        sent.append(message)

    scope = {
        "type": "http",
        "method": "POST",
        "scheme": "http",
        "path": "/lessons.v2.ClassService/GetClass",
        "root_path": "",
        "query_string": b"",
        "headers": [(b"content-type", b"application/grpc")],
        "client": ("203.0.113.9", 52144),
    }
    await rpc_app()(scope, receive, send)
    assert sent[0]["status"] == 415
    body = b"".join(m.get("body", b"") for m in sent if m["type"] == "http.response.body")
    assert body == GRPC_REFUSED.encode()


@pytest.mark.parametrize(
    ("content_type", "body"),
    [
        ("application/json", b'{"schoolClass": '),
        ("application/json", b'{"confirmation": {"a": 1}}'),
        ("application/json", b"[1, 2]"),
        ("application/proto", b"\xff\xff\xff"),
    ],
    ids=["truncated-json", "wrong-type", "not-an-object", "bad-varint"],
)
async def test_a_body_that_does_not_decode_is_invalid_argument_not_500(
    v2, content_type, body
) -> None:
    response = await v2.http.post(
        "/api/rpc/lessons.v2.ClassService/DeleteClass",
        content=body,
        headers={"Content-Type": content_type},
    )
    assert response.status_code == 400
    error = response.json()
    assert error["code"] == "invalid_argument"
    assert error["message"] == "The request could not be decoded"
    assert error["details"][0]["type"] == "google.rpc.ErrorInfo"
    assert error["details"][0]["debug"]["reason"] == "REQUEST_UNDECODABLE"


async def test_an_unknown_service_is_404_and_a_get_of_a_write_is_405(v2) -> None:
    unknown = await v2.http.post(
        "/api/rpc/lessons.v2.NoSuchService/Nothing",
        content=b"{}",
        headers={"Content-Type": "application/json"},
    )
    assert unknown.status_code == 404
    write = await v2.http.get(
        "/api/rpc/lessons.v2.ClassService/DeleteClass", params={"encoding": "json", "message": "{}"}
    )
    assert write.status_code == 405


@pytest.mark.parametrize("vercel", ["1", ""], ids=["on-vercel", "elsewhere"])
async def test_watch_class_is_not_served_and_says_so_after_the_gate(
    v2, v2_tokens, monkeypatch, vercel
) -> None:
    monkeypatch.setattr(get_settings(), "vercel", vercel)
    anonymous = await v2.stream("WatchService/WatchClass")
    assert (anonymous.code, anonymous.reason) == ("UNAUTHENTICATED", "DEVICE_TOKEN_INVALID")

    watched = await v2.stream("WatchService/WatchClass", token=v2_tokens["unlinked"])
    assert watched.status == 200
    assert (watched.code, watched.reason) == ("UNIMPLEMENTED", "FEATURE_UNSUPPORTED")
    assert watched.metadata == {"feature": "streaming"}


async def test_a_v2_that_cannot_be_imported_leaves_v1_serving(monkeypatch, caplog) -> None:
    """3a is production's first import of ``connectrpc`` and its native wheels.
    A failure there is caught: v2's prefixes answer 503, and v1 goes on."""
    monkeypatch.setitem(sys.modules, "app.rest", None)
    monkeypatch.setitem(sys.modules, "app.rpc", None)
    fresh = FastAPI()
    fresh.include_router(public_router)
    assert main.mount_v2(fresh) is False
    assert any("v2 could not be loaded" in record.getMessage() for record in caplog.records)

    transport = httpx.ASGITransport(app=fresh)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as http:
        assert (await http.get("/api/v1/health")).status_code == 200
        rest = await http.get("/api/v2/me")
        assert rest.status_code == 503
        assert rest.json()["error"]["status"] == "UNAVAILABLE"
        rpc = await http.post("/api/rpc/lessons.v2.MeService/GetMe", content=b"{}")
        assert rpc.status_code == 503
        assert rpc.json()["code"] == "unavailable"


def test_the_live_app_mounted_v2() -> None:
    """The other half: the app every test here reaches did load v2."""
    paths = {getattr(route, "path", None) for route in main.app.routes}
    assert "/api/rpc" in paths
    assert GetClassRequest  # the request the guards above were sent as
```
Run `python -m pytest -q -p no:xdist tests/test_rpc_mount.py`. Expected: collection error, `ImportError: cannot import name 'GRPC_REFUSED' from 'app.rpc'`.

- [ ] **Step 2: Rewrite `server/app/rpc/__init__.py` in full.**
```python
"""v2 over RPC: Connect and gRPC-Web, mounted at ``/api/rpc``.

:func:`rpc_app` is one ASGI app over the seventeen generated service apps. Each
generated ``Protocol`` is implemented by an adapter whose every method turns
Connect's ``RequestContext`` into :func:`call.invoke`'s arguments, so the RPC
path and the REST transcoder run one function
(``docs/specs/2026-10-05-server-v2-design.md``, decision 3).

Two of ``connectrpc`` 0.12.1's defects under HTTP/1.1 are corrected in front
of it (decision 7), because the library answers both with a ``500`` and a
logged traceback:

- a native gRPC request (``application/grpc``, ``application/grpc+…``) is
  refused with ``415`` and a sentence saying where gRPC is served — over
  anything but HTTP/2 and HTTP/3, so the host target's HTTP/2 lets it
  through (3c), and a scope that names no version is HTTP/1.1, as ASGI
  reads it;
- a body that does not decode is ``invalid_argument`` /
  ``REQUEST_UNDECODABLE``, through codecs that wrap the library's own.
"""

from __future__ import annotations

import importlib
from collections.abc import Awaitable, Callable, MutableMapping
from typing import Any

from connectrpc.codec import Codec, proto_binary_codec, proto_json_codec
from starlette.responses import PlainTextResponse, Response

from app.api.deps import peer_host
from app.rpc.call import invoke
from app.rpc.errors import connect_error, undecodable
from app.rpc.methods import METHODS, Method

Scope = MutableMapping[str, Any]
Receive = Callable[[], Awaitable[MutableMapping[str, Any]]]
Send = Callable[[MutableMapping[str, Any]], Awaitable[None]]

#: What a native gRPC request over HTTP/1.1 is told.
GRPC_REFUSED = (
    "Native gRPC is served by the host target over HTTP/2, not by this deployment. "
    "Use Connect or gRPC-Web here."
)


class _Decoding:
    """A codec that answers ``REQUEST_UNDECODABLE`` where the library's raises.

    ``connectrpc`` calls ``codec.decode`` and lets whatever it raises become a
    ``500 unknown`` with a traceback; protobuf-py raises ``ValueError``,
    ``TypeError`` or ``json.JSONDecodeError``, and its message quotes the value
    it refused, so the original is never what the client sees.
    """

    def __init__(self, inner: Codec) -> None:
        self._inner = inner

    def name(self) -> str:
        return self._inner.name()

    def encode(self, message: Any) -> bytes:
        return self._inner.encode(message)

    def decode(self, data: bytes | bytearray, message_class: type[Any]) -> Any:
        try:
            return self._inner.decode(data, message_class)
        except Exception:
            raise connect_error(undecodable()) from None


#: Binary first, as the library's own default list has it.
CODECS: tuple[Codec, ...] = (_Decoding(proto_binary_codec()), _Decoding(proto_json_codec()))


def _unary(method: Method) -> Callable[..., Awaitable[Any]]:
    async def call(self: object, request: Any, ctx: Any) -> Any:
        return await invoke(
            method,
            request,
            headers=list(ctx.request_headers.allitems()),
            peer=peer_host(ctx.client_address),
        )

    return call


def _server_stream(method: Method) -> Callable[..., Any]:
    async def call(self: object, request: Any, ctx: Any) -> Any:
        # A stream's handler answers once, today always with a refusal
        # (`rpc/watch.py`); 3c's host yields from it instead.
        yield await invoke(
            method,
            request,
            headers=list(ctx.request_headers.allitems()),
            peer=peer_host(ctx.client_address),
        )

    return call


def _service_app(service: str, methods: list[Method]) -> Any:
    """The generated ASGI app of ``service``, over an adapter of its ``Protocol``."""
    short = service.rpartition(".")[2]
    stem = methods[0].input.desc().file.proto.name.removesuffix(".proto").replace("/", ".")
    module = importlib.import_module(f"app.contract.{stem}_connect")
    protocol = getattr(module, short)
    application = getattr(module, f"{short}ASGIApplication")
    namespace: dict[str, Any] = {}
    for method in methods:
        if not hasattr(protocol, method.attribute):
            raise RuntimeError(f"{service} has no method {method.attribute}")
        namespace[method.attribute] = (
            _server_stream(method) if method.streaming else _unary(method)
        )
    adapter = type(f"{short}Adapter", (protocol,), namespace)
    return application(adapter(), codecs=CODECS)


def _is_native_grpc(scope: Scope) -> bool:
    for name, value in scope.get("headers", ()):
        if name.lower() == b"content-type":
            media = value.decode("latin-1").split(";", 1)[0].strip().lower()
            return media == "application/grpc" or media.startswith("application/grpc+")
    return False


class _Services:
    """The seventeen service apps under one mount, chosen by the path's first part.

    Not a Starlette ``Router`` of ``Mount``s: a nested mount moves
    ``root_path``, and ``connectrpc`` finds its endpoint by stripping exactly
    the mount's root from the path. This hands each app the scope it was given.
    """

    def __init__(self, apps: dict[str, Any]) -> None:
        self._apps = apps

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            return
        # Not «is it 1.1»: ASGI makes `http_version` optional and reads a
        # missing one as "1.1", so only a scope that says 2 or 3 is let past.
        if scope.get("http_version") not in ("2", "3") and _is_native_grpc(scope):
            await PlainTextResponse(GRPC_REFUSED, status_code=415)(scope, receive, send)
            return
        path = scope["path"].removeprefix(scope.get("root_path", ""))
        service = "/" + path.lstrip("/").partition("/")[0]
        app = self._apps.get(service)
        if app is None:
            await Response(status_code=404)(scope, receive, send)
            return
        await app(scope, receive, send)


def rpc_app() -> _Services:
    """Every service of the contract, each over its adapter, as one ASGI app."""
    by_service: dict[str, list[Method]] = {}
    for method in METHODS.values():
        by_service.setdefault(method.service, []).append(method)
    apps = {}
    for service, methods in sorted(by_service.items()):
        app = _service_app(service, methods)
        apps[app.path] = app
    return _Services(apps)
```

- [ ] **Step 3: Create `server/app/rpc/watch.py`.**
```python
"""``WatchService``: the class changing, as it happens — a beta of the host target.

No target streams in 3a. Vercel never will: it runs this app under HTTP/1.1
with no trailers and no connection held open between requests, which is the
programme's reason for a second target. 3c adds the stream on the host, read
from its deployment marker (``LESSONS_TARGET=host``) with
``LESSONS_STREAMING=true``; until then, and on Vercel always, the method says
the feature is not here, after the gate has checked the caller like any other
(``docs/specs/2026-10-05-server-v2-design.md``, decision 7).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.watch_pb import WatchClassRequest, WatchClassResponse
from app.rpc.errors import Refusal

if TYPE_CHECKING:
    from app.rpc.call import Call

#: ``FEATURE_UNSUPPORTED``'s ``feature`` for a server capability, as
#: ``errors.proto`` allows beside a ``DiaryFeature`` name.
STREAMING = "streaming"

NOT_HERE = "Streaming is served by the host target, not by this deployment"


async def watch_class(call: Call, request: WatchClassRequest) -> WatchClassResponse:
    raise Refusal(ErrorReason.FEATURE_UNSUPPORTED, NOT_HERE, feature=STREAMING)
```
and rewrite `server/app/rpc/handlers.py` to register it:
```python
"""Which methods this deployment serves, and the handler of each.

A method missing here answers ``UNIMPLEMENTED`` on both transports, before
any gate or scope, exactly as the generated ``Protocol``'s default does. 3a
serves four methods and ``WatchClass``'s refusal; 3b fills the rest in, one
service at a time.

Handler modules import ``Call`` only for their annotations, so that
``call.py``, which imports this table, is never imported back.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

from app.rpc import watch

#: A handler: ``async def handler(call: Call, request: <Method>Request) -> <Method>Response``.
Handler = Callable[[Any, Any], Awaitable[Any]]

#: Keyed as ``rpc.methods.METHODS`` is: ``"lessons.v2.<Service>/<Method>"``.
HANDLERS: dict[str, Handler] = {
    "lessons.v2.WatchService/WatchClass": watch.watch_class,
}
```

- [ ] **Step 4: Mount v2 in `server/app/main.py`, fail-safe.** Replace `from fastapi import FastAPI` with
```python
from fastapi import FastAPI
from starlette.responses import JSONResponse
from starlette.routing import Mount
```
and insert immediately above `@app.get("/")`:
```python
#: What `/api/v2` and `/api/rpc` answer when v2 could not be loaded.
V2_UNAVAILABLE = "v2 is not available on this deployment; v1 is unaffected"


async def _v2_unavailable(scope, receive, send) -> None:
    """The answer where v2 would be, in each transport's own error shape."""
    if scope["type"] != "http":
        return
    if scope["path"].startswith("/api/rpc"):
        body: dict[str, object] = {"code": "unavailable", "message": V2_UNAVAILABLE}
    else:
        body = {
            "error": {
                "code": 503,
                "message": V2_UNAVAILABLE,
                "status": "UNAVAILABLE",
                "details": [],
            }
        }
    await JSONResponse(body, status_code=503)(scope, receive, send)


def mount_v2(target: FastAPI) -> bool:
    """Serve v2 beside v1 — REST under `/api/v2`, Connect under `/api/rpc` — or
    answer 503 there if v2 cannot be loaded, and say whether it was.

    3a is the first time production imports `connectrpc`, the generated
    contract and their native wheels (`protobuf-py-ext`, `pyqwest`), none of
    which had been imported on Vercel before
    (docs/specs/2026-10-05-server-v2-design.md, decision 7). An import error
    here would otherwise be the whole function's, and v1 — every phone in
    production — and the webhook would go down with v2. So it is caught,
    logged with its traceback, and v2's two prefixes answer 503 in their own
    error shapes, while everything else is served as before.
    """
    try:
        from app.rpc import rpc_app

        services = rpc_app()
    except Exception:
        log.exception("v2 could not be loaded: /api/v2 and /api/rpc answer 503, v1 is served")
        target.router.routes.append(Mount("/api/v2", app=_v2_unavailable))
        target.router.routes.append(Mount("/api/rpc", app=_v2_unavailable))
        return False
    target.mount("/api/rpc", services)
    return True


# v2, beside v1 and under prefixes v1 never used. After v1's routers, so that
# nothing of v1's can be shadowed by a route built from the contract.
mount_v2(app)
```

- [ ] **Step 5: The cold start's guard turns round (decision 8).** In `server/tests/test_contract.py`:
  1. In the module docstring replace «was written by the plugin version ``buf.gen.yaml`` pins, and stays off the» / «API's cold path.» with «was written by the plugin version ``buf.gen.yaml`` pins, and is on the» / «API's cold path, because the API serves it.».
  2. Replace `test_the_api_cold_start_imports_no_generated_code` and `test_the_cold_start_probe_sees_generated_code` (both functions, with the parametrize decorator) with:
```python
@pytest.mark.parametrize("settings", [_LOCAL, _VERCEL], ids=["webhook-unmounted", "vercel"])
def test_the_api_cold_start_imports_the_contract_it_serves(settings: dict[str, str]) -> None:
    """Turned round by sub-project 3 (docs/specs/2026-10-05-server-v2-design.md,
    decision 8): app.main serves v2, so the generated services, connectrpc and
    the protobuf runtime are on its cold path on purpose, in both
    configurations. Were they missing, `mount_v2` would have caught a failed
    import and v2 would be answering 503 — which this would then say.
    tests/test_cold_start.py still holds that aiogram is not."""
    loaded = _loaded_after_importing("app.main", settings)
    assert "app.contract.lessons.v2.schedule_connect" in loaded
    assert any(name.partition(".")[0] == "connectrpc" for name in loaded)
    assert any(name.partition(".")[0] == "protobuf" for name in loaded)
```
  The probe test goes because the assertion above now proves the probe sees the contract. `tests/test_cold_start.py` is not changed: it still refuses aiogram, and no import-time ceiling is added (decision 8: no fixed number holds across CI's runners and this machine without being flaky or meaningless; the seventeen service modules measured 117–180 ms here).

- [ ] **Step 6: `WatchClass` produces `FEATURE_UNSUPPORTED`.** In `server/tests/test_rpc_errors.py` delete the line `    "FEATURE_UNSUPPORTED": "3a: WatchClass",` from `LATER`.

- [ ] **Step 7: Green.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m pytest -q -p no:xdist tests/test_rpc_mount.py tests/test_rpc_errors.py tests/test_contract.py tests/test_cold_start.py tests/test_rpc_call.py tests/test_service_layering.py tests/test_startup.py tests/test_api_docs.py
```
Expected: all pass — `test_rpc_mount.py` 13, `test_contract.py` 27, `test_cold_start.py` 3 (aiogram still absent in both configurations, with `app.rpc` now imported).

- [ ] **Step 8: Gates.** ruff clean; mypy `Success: no issues found in 210 source files`; the full suite once, previous count plus 12 (13 new, the probe test gone).

- [ ] **Step 9: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/rpc server/app/main.py server/tests/conftest.py server/tests/test_rpc_mount.py server/tests/test_contract.py server/tests/test_rpc_errors.py && git commit -F - <<'EOF'
Mount v2 over Connect at /api/rpc, behind two guards and a fail-safe

rpc_app() puts the seventeen generated Connect apps under one mount, each
over an adapter whose every method calls invoke, so a handler registered
later is reachable at once and a method with none answers the Protocol's
own UNIMPLEMENTED.

Two defects of connectrpc 0.12.1 under HTTP/1.1 are corrected in front of
it: a native gRPC request (application/grpc, application/grpc+...) is a 415
saying where gRPC is served, not a 500 and a traceback, while gRPC-Web,
HTTP/2 and HTTP/3 pass (a scope that names no version is HTTP/1.1, as
ASGI reads it); and a body that does not decode is invalid_argument with
REQUEST_UNDECODABLE, through codecs that wrap the library's own. WatchClass
refuses with FEATURE_UNSUPPORTED after the gate: no target streams yet.

This is production's first import of connectrpc and its native wheels, so
main.mount_v2 catches a failed import, logs it, and answers 503 under
/api/v2 and /api/rpc while v1 and the webhook go on. test_contract's cold
start test is turned round: the contract is on the cold path on purpose.

Not covered: no unary method has a handler yet, and REST is the next commit.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 7: The REST transcoder, and the harness's other half

Decision 6.

**Files:**
- Modify: `server/app/rest/__init__.py` (rewrite), `server/app/main.py`, `server/tests/conftest.py`, `server/tests/test_rpc_mount.py`
- Create: `server/tests/test_rest.py`

**Interfaces:**
- Consumes: Task 5's `METHODS`, `Binding`, `Method`, `invoke`; Task 4's `error_response`, `connect_error`, `Refusal`, `undecodable`.
- Produces:
  - `app.rest`: `rest_routes() -> list[starlette.routing.Route]` (75); `route_path(method) -> str` (`/api` + template, `{task.id}` → `{task__id}`); `decode(method, *, path_params: Mapping[str, str], query: Sequence[tuple[str, str]], headers: Sequence[tuple[str, str]], body: bytes) -> Message` (raises `Refusal` `REQUEST_UNDECODABLE`); `CREATED: frozenset[str]` (the eight method keys answering `201`); `NO_STORE`; `MAX_BODY`; `TOO_LARGE`.
  - The harness, complete: `await v2.rest(name, request=None, *, token=None, headers=None) -> _V2Answer` (a REST `304` reads as `not_modified=True` with the `ETag`), and `await v2.both(name, request=None, *, token=None, headers=None, differ: tuple[str, ...] = ()) -> _V2Answer` — calls REST then Connect, asserts one `outcome()` with the `differ` fields (dotted, e.g. `"window.generated_at"`, `"token"`) cleared, and returns the REST answer.

- [ ] **Step 1: Red.** In `server/tests/conftest.py`:
  1. Insert immediately above `class _V2:`
```python
def _v2_cleared(message: Any, dotted: str) -> Any:
    """A copy of ``message`` with the field at ``dotted`` cleared, where it has one."""
    copy = message.from_binary(message.to_binary())
    target, parts = copy, dotted.split(".")
    for part in parts:
        field_desc = next((f for f in target.desc().fields if f.name == part), None)
        if field_desc is None or not target.has_field(part):
            return copy
        if part == parts[-1]:
            target.clear_field(part)
        else:
            target = target[field_desc]
    return copy
```
  2. Insert inside `class _V2`, immediately above `    async def connect(`:
```python
    async def rest(
        self,
        name: str,
        request: Any = None,
        *,
        token: str | None = None,
        headers: dict[str, str] | None = None,
    ) -> _V2Answer:
        from urllib.parse import quote, urlencode

        from protobuf import message_to_json_value

        method = self.method(name)
        request = request if request is not None else method.input()
        binding = method.binding
        assert binding is not None, f"{name} has no REST binding"
        sent = self._headers(token, headers)
        value = message_to_json_value(request)
        path = binding.path
        for variable in binding.variables:
            leaf: Any = request
            container = value
            parts = variable.split(".")
            for index, part in enumerate(parts):
                field_desc = next(f for f in leaf.desc().fields if f.name == part)
                leaf = leaf[field_desc]
                if index < len(parts) - 1:
                    container = container.get(field_desc.json_name, {})
                else:
                    container.pop(field_desc.json_name, None)
            path = path.replace("{" + variable + "}", quote(str(leaf), safe=""))
        if "ifNoneMatch" in value:
            sent["If-None-Match"] = value.pop("ifNoneMatch")

        body: Any = None
        rest_of: dict[str, Any] = value
        if binding.body == "*":
            body, rest_of = value, {}
        elif binding.body:
            body_field = next(f for f in method.input.desc().fields if f.name == binding.body)
            body = value.pop(body_field.json_name, {})

        query: list[tuple[str, str]] = []

        def flatten(prefix: str, item: Any) -> None:
            if isinstance(item, dict):
                for key, nested in item.items():
                    flatten(f"{prefix}{key}.", nested)
            elif isinstance(item, list):
                for element in item:
                    flatten(prefix, element)
            elif isinstance(item, bool):
                query.append((prefix.rstrip("."), "true" if item else "false"))
            else:
                query.append((prefix.rstrip("."), str(item)))

        flatten("", rest_of)
        url = "/api" + path + (f"?{urlencode(query)}" if query else "")
        response = await self.http.request(
            binding.verb.upper(),
            url,
            content=None if body is None else json.dumps(body).encode(),
            headers={**sent, "Content-Type": "application/json"} if body is not None else sent,
        )
        answer = _V2Answer("rest", response.status_code, response.headers, response.content)
        if response.status_code == 304:
            answer.message = method.output.from_json(
                json.dumps({"notModified": True, "etag": response.headers["etag"]})
            )
        elif response.status_code < 300:
            answer.message = method.output.from_json(response.content)
        else:
            from app.contract.google.rpc import error_details_pb

            error = response.json()["error"]
            assert error["code"] == response.status_code
            answer.code, answer.error = error["status"], error["message"]
            details = []
            for detail in error["details"]:
                type_name = detail["@type"].removeprefix("type.googleapis.com/")
                cls = getattr(error_details_pb, type_name.rpartition(".")[2])
                fields = {key: item for key, item in detail.items() if key != "@type"}
                details.append((type_name, cls.from_json(json.dumps(fields))))
            _v2_details(answer, details)
        return answer
```
  3. Insert inside `class _V2`, after the end of `_connect_error` and before the two blank lines that precede `@pytest.fixture` / `async def v2()`:
```python

    async def both(
        self,
        name: str,
        request: Any = None,
        *,
        token: str | None = None,
        headers: dict[str, str] | None = None,
        differ: tuple[str, ...] = (),
    ) -> _V2Answer:
        """Call ``name`` over REST, then over Connect, and assert one outcome.

        ``differ`` names response fields, dotted, that two calls may answer
        differently by nature — a new token, a ``generated_at`` — and that are
        compared as absent. Returns the REST answer.
        """
        rest = await self.rest(name, request, token=token, headers=headers)
        connect = await self.connect(name, request, token=token, headers=headers)
        for answer in (rest, connect):
            if answer.message is not None:
                for dotted in differ:
                    answer.message = _v2_cleared(answer.message, dotted)
        assert rest.outcome() == connect.outcome(), (rest, connect)
        return rest
```
Create `server/tests/test_rest.py`:
```python
"""The REST transcoder, generically: routes, binding, statuses, the ETag rule.

The routes come from the contract's own ``google.api.http`` rules, so they are
compared here with the descriptors read independently of ``rpc/methods.py``.
Binding is asked of :func:`app.rest.decode` for every method with a path
variable, and of the live app through handlers put into ``HANDLERS`` for the
test, so that what is checked is the transcoder and not a method's rules
(``docs/specs/2026-10-05-server-v2-design.md``, decision 6).
"""

from __future__ import annotations

import importlib
import pkgutil
import re
from pathlib import Path

import pytest
from protobuf.wkt import FieldMask

import app.contract.lessons.v2 as contract_v2
from app.contract.google.api import annotations_pb
from app.contract.lessons.v2 import diary_pb, me_pb, schedule_pb, subject_pb
from app.main import app
from app.rest import CREATED, MAX_BODY, TOO_LARGE, decode, route_path
from app.rpc.errors import Refusal
from app.rpc.handlers import HANDLERS
from app.rpc.methods import METHODS, Binding, Method

PROTO = Path(__file__).resolve().parents[2] / "proto" / "lessons" / "v2"


def _bindings() -> dict[str, tuple[str, str]]:
    """Every unary method's (verb, template), read from the descriptors."""
    found: dict[str, tuple[str, str]] = {}
    for info in pkgutil.iter_modules(contract_v2.__path__):
        if not info.name.endswith("_pb"):
            continue
        module = importlib.import_module(f"{contract_v2.__name__}.{info.name}")
        for service in module.desc().services:
            for method in service.methods:
                options = method.proto.options
                if options is None or annotations_pb.ext_http not in options:
                    continue
                rule = options[annotations_pb.ext_http]
                found[f"{service.type_name}/{method.name}"] = (
                    rule.pattern.field,
                    rule.pattern.value,
                )
    return found


def _sample(method: Method, dotted: str) -> tuple[str, object]:
    """A path value for ``dotted``, as sent and as the request should hold it."""
    message = method.input.desc()
    *parents, last = dotted.split(".")
    for part in parents:
        message = next(f for f in message.fields if f.name == part).value.message
    field = next(f for f in message.fields if f.name == last)
    if field.proto.type.name == "STRING":
        return "2026-09-07", "2026-09-07"
    return "7", 7


def _leaf(message: object, dotted: str) -> object:
    for part in dotted.split("."):
        message = message[next(f for f in message.desc().fields if f.name == part)]
    return message


def test_every_unary_method_has_its_route_with_its_verb() -> None:
    routes = {
        (route.path, verb)
        for route in app.routes
        for verb in (getattr(route, "methods", None) or ())
    }
    bindings = _bindings()
    assert len(bindings) == 75
    for key, (verb, template) in bindings.items():
        path = "/api" + re.sub(
            r"\{([^}]+)\}", lambda m: "{" + m.group(1).replace(".", "__") + "}", template
        )
        assert (path, verb.upper()) in routes, key
        assert route_path(METHODS[key]) == path


def test_every_path_variable_binds_the_dotted_ones_included() -> None:
    checked = 0
    for method in METHODS.values():
        if method.binding is None or not method.binding.variables:
            continue
        params, expected = {}, {}
        for variable in method.binding.variables:
            sent, held = _sample(method, variable)
            params[variable.replace(".", "__")] = sent
            expected[variable] = held
        body = b"{}" if method.binding.body else b""
        request = decode(method, path_params=params, query=[], headers=[], body=body)
        for variable, held in expected.items():
            assert _leaf(request, variable) == held, (method.key, variable)
        checked += 1
    assert checked > 30


def test_a_patch_takes_its_update_mask_and_allow_missing_from_the_query() -> None:
    update = decode(
        METHODS["lessons.v2.MeService/UpdateTask"],
        path_params={"task__id": "5"},
        query=[("updateMask", "title,done")],
        headers=[],
        body='{"title": "Конспект", "id": 99}'.encode(),
    )
    assert update.task.id == 5  # the path wins over the body
    assert update.task.title == "Конспект"
    assert list(update.update_mask.paths) == ["title", "done"]

    day = decode(
        METHODS["lessons.v2.DayService/UpdateDay"],
        path_params={"day__date": "2026-09-07"},
        query=[("allow_missing", "true")],
        headers=[],
        body=b"{}",
    )
    assert day.allow_missing is True
    assert day.day.date == "2026-09-07"


def test_a_get_query_reaches_nested_and_repeated_fields() -> None:
    """No GET of the contract has a nested or a repeated field yet, so the rule
    is asked of a method built for the test over messages that do."""
    repeated = Method(
        service="lessons.v2.Test",
        name="Repeated",
        input=diary_pb.ProviderCapabilities,
        output=diary_pb.ProviderCapabilities,
        auth=METHODS["lessons.v2.MeService/GetMe"].auth,
        min_role=None,
        side_effect_free=True,
        streaming=False,
        binding=Binding(verb="get", path="/v2/test/{provider}", body=""),
    )
    request = decode(
        repeated,
        path_params={"provider": "netschool"},
        query=[
            ("regions", "samara"),
            ("regions", "tomsk"),
            ("signInMethods", "SIGN_IN_METHOD_PASSWORD"),
        ],
        headers=[],
        body=b"",
    )
    assert request.provider == "netschool"
    assert list(request.regions) == ["samara", "tomsk"]
    assert [method.name for method in request.sign_in_methods] == ["PASSWORD"]

    nested = Method(
        service="lessons.v2.Test",
        name="Nested",
        input=me_pb.UpdateTaskRequest,
        output=me_pb.UpdateTaskResponse,
        auth=METHODS["lessons.v2.MeService/GetMe"].auth,
        min_role=None,
        side_effect_free=True,
        streaming=False,
        binding=Binding(verb="get", path="/v2/test", body=""),
    )
    request = decode(
        nested,
        path_params={},
        query=[("task.title", "x"), ("task.priority", "2"), ("task.done", "true"), ("nope", "1")],
        headers=[],
        body=b"",
    )
    assert (request.task.title, request.task.priority, request.task.done) == ("x", 2, True)


@pytest.mark.parametrize(
    ("key", "query", "body"),
    [
        ("lessons.v2.MeService/ListTasks", [("includeDone", "maybe")], b""),
        ("lessons.v2.DeviceService/CreateDevice", [], b"[1, 2]"),
        ("lessons.v2.DeviceService/CreateDevice", [], b'{"code": '),
        ("lessons.v2.MeService/UpdateTask", [], b'"not a task"'),
        ("lessons.v2.ScheduleService/GetScheduleWindow", [], b""),
    ],
    ids=["bool", "not-an-object", "truncated", "body-field", "bad-path"],
)
def test_what_does_not_decode_is_request_undecodable(key, query, body) -> None:
    method = METHODS[key]
    params = {"task__id": "1"} if "task" in route_path(method) else {}
    if "{year}" in route_path(method):
        params = {"year": "two thousand"}
    with pytest.raises(Refusal) as refused:
        decode(method, path_params=params, query=query, headers=[], body=body)
    assert refused.value.reason.name == "REQUEST_UNDECODABLE"
    assert refused.value.message == "The request could not be decoded"


def test_if_none_match_comes_from_the_header_which_wins_over_the_query() -> None:
    method = METHODS["lessons.v2.ScheduleService/GetScheduleWindow"]
    request = decode(
        method,
        path_params={"year": "2026"},
        query=[("ifNoneMatch", '"from-query"')],
        headers=[("if-none-match", '"from-header"')],
        body=b"",
    )
    assert request.if_none_match == '"from-header"'
    assert request.year == 2026


def test_the_created_table_is_what_the_proto_comments_promise() -> None:
    promised = set()
    for path in sorted(PROTO.glob("*.proto")):
        text = path.read_text(encoding="utf-8")
        service = re.search(r"^service (\w+)", text, re.M)
        for comment, name in re.findall(r"((?:\s*//[^\n]*\n)+)\s*rpc (\w+)\(", text):
            if "REST answers 201" in " ".join(comment.split()):
                promised.add(f"lessons.v2.{service.group(1)}/{name}")
    assert len(promised) == 8
    assert promised == set(CREATED)


async def test_a_body_over_four_megabytes_is_refused_by_both_transports(v2) -> None:
    oversized = b'{"code": "' + b"x" * MAX_BODY + b'"}'
    rest = await v2.http.post(
        "/api/v2/devices", content=oversized, headers={"Content-Type": "application/json"}
    )
    connect = await v2.http.post(
        "/api/rpc/lessons.v2.DeviceService/CreateDevice",
        content=oversized,
        headers={"Content-Type": "application/json"},
    )
    assert rest.status_code == connect.status_code == 429
    assert rest.json()["error"]["message"] == connect.json()["message"] == TOO_LARGE


async def _echo(call, request):
    return me_pb.UpdateTaskResponse(task=request.task)


async def test_both_transports_bind_one_request_to_one_message(v2, v2_tokens, monkeypatch) -> None:
    monkeypatch.setitem(HANDLERS, "lessons.v2.MeService/UpdateTask", _echo)
    request = me_pb.UpdateTaskRequest(
        task=me_pb.Task(id=5, title="Конспект", done=True),
        update_mask=FieldMask(paths=["title", "done"]),
    )
    answer = await v2.both("MeService/UpdateTask", request, token=v2_tokens["viewer"])
    assert answer.message.task.id == 5
    assert answer.message.task.title == "Конспект"


async def test_a_create_the_proto_promises_answers_201_and_any_other_200(
    v2, v2_tokens, monkeypatch
) -> None:
    async def created(call, request):
        return subject_pb.CreateSubjectResponse()

    async def minted(call, request):
        return me_pb.CreateLinkCodeResponse()

    monkeypatch.setitem(HANDLERS, "lessons.v2.SubjectService/CreateSubject", created)
    monkeypatch.setitem(HANDLERS, "lessons.v2.MeService/CreateLinkCode", minted)
    subject = await v2.rest("SubjectService/CreateSubject", token=v2_tokens["admin"])
    link = await v2.rest("MeService/CreateLinkCode", token=v2_tokens["unlinked"])
    assert (subject.status, link.status) == (201, 200)


async def test_the_etag_rule_works_both_ways(v2, v2_tokens, monkeypatch) -> None:
    async def window(call, request):
        if request.if_none_match == '"tag"':
            return schedule_pb.GetScheduleWindowResponse(not_modified=True, etag='"tag"')
        return schedule_pb.GetScheduleWindowResponse(
            etag='"tag"', window=schedule_pb.ScheduleWindow()
        )

    monkeypatch.setitem(HANDLERS, "lessons.v2.ScheduleService/GetScheduleWindow", window)
    fresh = await v2.both(
        "ScheduleService/GetScheduleWindow",
        schedule_pb.GetScheduleWindowRequest(year=2026),
        token=v2_tokens["unlinked"],
    )
    assert fresh.status == 200 and fresh.headers["etag"] == '"tag"'
    cached = await v2.both(
        "ScheduleService/GetScheduleWindow",
        schedule_pb.GetScheduleWindowRequest(year=2026, if_none_match='"tag"'),
        token=v2_tokens["unlinked"],
    )
    assert cached.status == 304
    assert cached.body == b""
    assert cached.headers["etag"] == '"tag"'
    assert cached.message.not_modified is True


async def test_diary_reads_are_never_cached_and_nothing_answers_a_browser(
    v2, monkeypatch, v2_tokens
) -> None:
    async def capabilities(call, request):
        return diary_pb.GetDiaryCapabilitiesResponse()

    async def me(call, request):
        return me_pb.GetMeResponse()

    monkeypatch.setitem(HANDLERS, "lessons.v2.DiaryService/GetDiaryCapabilities", capabilities)
    monkeypatch.setitem(HANDLERS, "lessons.v2.MeService/GetMe", me)
    diary = await v2.http.get(
        "/api/v2/diary/capabilities", headers={"Origin": "https://evil.example"}
    )
    assert diary.headers["cache-control"] == "private, no-store"
    assert "access-control-allow-origin" not in diary.headers
    own = await v2.http.get(
        "/api/v2/me", headers={"Authorization": f"Bearer {v2_tokens['viewer']}"}
    )
    assert own.status_code == 200 and "cache-control" not in own.headers
    preflight = await v2.http.options(
        "/api/v2/me",
        headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "GET"},
    )
    assert "access-control-allow-origin" not in preflight.headers


async def test_a_refusal_is_google_s_body_under_its_status(v2, monkeypatch) -> None:
    async def me(call, request):
        return me_pb.GetMeResponse()

    monkeypatch.setitem(HANDLERS, "lessons.v2.MeService/GetMe", me)
    response = await v2.http.get("/api/v2/me")
    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert response.json() == {
        "error": {
            "code": 401,
            "message": "Missing bearer token",
            "status": "UNAUTHENTICATED",
            "details": [
                {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "DEVICE_TOKEN_INVALID",
                    "domain": "lessons.app",
                }
            ],
        }
    }


async def test_the_wrong_verb_is_405(v2) -> None:
    assert (await v2.http.get("/api/v2/devices")).status_code == 405
```
In `server/tests/test_rpc_mount.py`, in `test_the_live_app_mounted_v2`, add after `    assert "/api/rpc" in paths`:
```python
    assert "/api/v2/me" in paths or "/api/v2/class/scheduleWindows/{year}" in paths
```
Run `python -m pytest -q -p no:xdist tests/test_rest.py`. Expected: collection error, `ImportError: cannot import name 'CREATED' from 'app.rest'`.

- [ ] **Step 2: Rewrite `server/app/rest/__init__.py` in full.**
```python
"""v2 over REST: one Starlette route per unary method, from its ``google.api.http`` rule.

The contract's annotations are the routes — written ``/v2/…``, served under
``/api`` — read at import from the same table the gate and the RPC adapters
use (``rpc/methods.py``), and every route calls the same ``invoke``. So REST
and RPC cannot disagree about a rule; only the transcoding can differ, and it
is tested once, generically
(``docs/specs/2026-10-05-server-v2-design.md``, decision 6).

**Binding a request** (:func:`decode`):

- path variables, dotted ones included; Starlette cannot name a parameter
  ``task.id``, so each is renamed ``task__id`` in the route and back here;
- the body as the rule says: ``"*"``, one field, or none;
- **every field the path and the body leave unbound from the query string,
  whatever the verb** — an ``Update*``'s ``update_mask`` and ``UpdateDay``'s
  ``allow_missing`` arrive there; dotted names reach nested fields, a repeated
  name a repeated field, a ``bool`` is ``true``/``false``, and a well-known
  type (``FieldMask``, ``Timestamp``) is its JSON string form;
- ``If-None-Match`` fills an ``if_none_match`` field, and wins over a query
  parameter of that name.

Canonical proto3 JSON, unknown fields ignored, as Connect does it. A body
over 4 MB is refused, Connect's own limit; anything that does not decode is
``INVALID_ARGUMENT`` / ``REQUEST_UNDECODABLE``.

**Answering**: ``201`` for the eight methods whose proto comment promises it
(:data:`CREATED`), ``200`` for every other success, with the response as
canonical JSON; ``304`` and no body when the response says ``not_modified``;
its ``etag`` as ``ETag``; diary reads ``Cache-Control: private, no-store``;
no CORS header at all (question 5). A refusal is Google's error body
(``rest/errors.py``).
"""

from __future__ import annotations

import json
from collections.abc import Awaitable, Callable, Mapping, Sequence
from typing import Any

from connectrpc.code import Code
from connectrpc.errors import ConnectError
from connectrpc.server import DEFAULT_READ_MAX_BYTES
from protobuf import (
    DescField,
    DescFieldValueList,
    DescFieldValueMessage,
    DescFieldValueScalar,
    DescMessage,
    Message,
    ScalarType,
    message_from_json_value,
)
from starlette.requests import Request
from starlette.responses import Response
from starlette.routing import Route

from app.rest.errors import error_response
from app.rpc.call import invoke
from app.rpc.errors import Refusal, connect_error, undecodable
from app.rpc.methods import METHODS, Method

#: The methods whose proto comment says «REST answers 201». Comments do not
#: reach the descriptors at runtime, so the set is written here;
#: ``test_rest.py`` reads ``proto/lessons/v2`` and holds the two level.
CREATED = frozenset(
    {
        "lessons.v2.BellService/CreateBellSchedule",
        "lessons.v2.DeviceService/CreateDevice",
        "lessons.v2.DiaryService/CreateDiarySession",
        "lessons.v2.EventService/CreateEvent",
        "lessons.v2.HomeworkService/CreateHomework",
        "lessons.v2.MeService/CreateTask",
        "lessons.v2.SubjectService/CreateSubject",
        "lessons.v2.SubstitutionService/CreateSubstitution",
    }
)

#: What every diary read answers with: a family's marks are nobody's cache's.
NO_STORE = "private, no-store"

#: Connect's own limit on a request message, so the two transports refuse the
#: same size with the same words.
MAX_BODY = DEFAULT_READ_MAX_BYTES
TOO_LARGE = f"message is larger than configured max {MAX_BODY}"

#: The well-known types whose JSON form is a string, so a query parameter can
#: carry them as it is.
_STRING_JSON = frozenset(
    {
        "google.protobuf.FieldMask",
        "google.protobuf.Timestamp",
        "google.protobuf.Duration",
    }
)


def route_path(method: Method) -> str:
    """The Starlette path of ``method``'s route: ``/api`` plus its template,
    each dotted variable renamed ``a__b``."""
    if method.binding is None:
        raise ValueError(f"{method.key} has no REST binding")
    path = method.binding.path
    for variable in method.binding.variables:
        path = path.replace("{" + variable + "}", "{" + variable.replace(".", "__") + "}")
    return "/api" + path


def _field(message: Any, name: str) -> DescField | None:
    """A field of a message descriptor by its proto name or its JSON name."""
    for field in message.fields:
        if name in (field.name, field.json_name):
            return field
    return None


def _query_value(field: DescField, raw: str) -> Any:
    """A query parameter's text as the JSON value of ``field``'s type."""
    value = field.value
    kind: Any = value.element if isinstance(value, DescFieldValueList) else value
    if isinstance(kind, DescFieldValueScalar):
        kind = kind.scalar
    if kind is ScalarType.BOOL:
        if raw in ("true", "1"):
            return True
        if raw in ("false", "0"):
            return False
        raise undecodable()
    if isinstance(kind, DescFieldValueMessage):
        kind = kind.message
    if isinstance(kind, DescMessage) and kind.type_name not in _STRING_JSON:
        # A message in a query string has no form the contract defines.
        raise undecodable()
    # Numbers, enums and strings: canonical JSON reads each from a string.
    return raw


def _put(target: dict[str, Any], message: Any, dotted: str, value: Any, *, append: bool) -> None:
    """Set ``dotted`` — ``"task.id"`` — in a JSON object of ``message``'s type.

    The key written is the field's JSON name, and its proto-name spelling is
    removed first, so a body that said ``task_id`` and a path that says
    ``taskId`` cannot both reach the parser. Unknown names are ignored, as an
    unknown JSON field is.
    """
    parts = dotted.split(".")
    for part in parts[:-1]:
        field = _field(message, part)
        if field is None or not isinstance(field.value, DescFieldValueMessage):
            return
        if field.name != field.json_name and field.name in target:
            target.setdefault(field.json_name, target.pop(field.name))
        nested = target.setdefault(field.json_name, {})
        if not isinstance(nested, dict):
            raise undecodable()
        target, message = nested, field.value.message
    field = _field(message, parts[-1])
    if field is None:
        return
    if field.name != field.json_name:
        target.pop(field.name, None)
    if append and isinstance(field.value, DescFieldValueList):
        previous = target.get(field.json_name)
        target[field.json_name] = [*(previous if isinstance(previous, list) else []), value]
    else:
        target[field.json_name] = value


def _leaf(message: Any, dotted: str) -> DescField | None:
    for part in dotted.split(".")[:-1]:
        field = _field(message, part)
        if field is None or not isinstance(field.value, DescFieldValueMessage):
            return None
        message = field.value.message
    return _field(message, dotted.split(".")[-1])


def decode(
    method: Method,
    *,
    path_params: Mapping[str, str],
    query: Sequence[tuple[str, str]],
    headers: Sequence[tuple[str, str]],
    body: bytes,
) -> Message:
    """The request message a REST call means, or ``REQUEST_UNDECODABLE``."""
    if method.binding is None:
        raise ValueError(f"{method.key} has no REST binding")
    desc = method.input.desc()
    value: dict[str, Any] = {}
    bound: set[str] = set()

    if method.binding.body:
        try:
            parsed = json.loads(body) if body.strip() else {}
        except ValueError:
            raise undecodable() from None
        if method.binding.body == "*":
            if not isinstance(parsed, dict):
                raise undecodable()
            value = parsed
            bound = {field.name for field in desc.fields}
        else:
            _put(value, desc, method.binding.body, parsed, append=False)
            bound = {method.binding.body}

    for name, raw in query:
        top = _field(desc, name.split(".")[0])
        if top is None or top.name in bound:
            continue
        leaf = _leaf(desc, name)
        if leaf is None:
            continue
        _put(value, desc, name, _query_value(leaf, raw), append=True)

    if _field(desc, "if_none_match") is not None:
        tags = [tag for key, tag in headers if key.lower() == "if-none-match"]
        if tags:
            _put(value, desc, "if_none_match", ", ".join(tags), append=False)

    for name, raw in path_params.items():
        _put(value, desc, name.replace("__", "."), raw, append=False)

    try:
        return message_from_json_value(method.input, value, ignore_unknown_fields=True)
    except Exception:
        raise undecodable() from None


async def _read_body(request: Request) -> bytes:
    """The body, or ``RESOURCE_EXHAUSTED`` past :data:`MAX_BODY` — read in
    chunks, so an oversized body is never held whole."""
    chunks: list[bytes] = []
    size = 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > MAX_BODY:
            raise ConnectError(Code.RESOURCE_EXHAUSTED, TOO_LARGE)
        chunks.append(chunk)
    return b"".join(chunks)


def _answer(method: Method, response: Message) -> Response:
    fields = {field.name: field for field in response.desc().fields}
    headers: dict[str, str] = {}
    etag = response[fields["etag"]] if "etag" in fields else ""
    if etag:
        headers["ETag"] = etag
    if method.service == "lessons.v2.DiaryService" and method.binding is not None:
        if method.binding.verb == "get":
            headers["Cache-Control"] = NO_STORE
    if "not_modified" in fields and response[fields["not_modified"]]:
        return Response(status_code=304, headers=headers)
    status = 201 if method.key in CREATED else 200
    return Response(
        response.to_json(), status_code=status, media_type="application/json", headers=headers
    )


def _endpoint(method: Method) -> Callable[[Request], Awaitable[Response]]:
    async def endpoint(request: Request) -> Response:
        headers = request.headers.items()
        try:
            body = await _read_body(request) if method.binding and method.binding.body else b""
            message = decode(
                method,
                path_params=request.path_params,
                query=request.query_params.multi_items(),
                headers=headers,
                body=body,
            )
            response = await invoke(
                method,
                message,
                headers=headers,
                peer=request.client.host if request.client else None,
            )
        except ConnectError as error:
            return error_response(error)
        except Refusal as refusal:
            return error_response(connect_error(refusal))
        return _answer(method, response)

    return endpoint


def rest_routes() -> list[Route]:
    """One route per unary method of the contract, in the contract's order."""
    return [
        Route(
            route_path(method),
            _endpoint(method),
            methods=[method.binding.verb.upper()],
            name=method.key,
        )
        for method in METHODS.values()
        if method.binding is not None
    ]
```

- [ ] **Step 3: Mount the routes.** In `server/app/main.py`'s `mount_v2`, replace
```python
    try:
        from app.rpc import rpc_app

        services = rpc_app()
    except Exception:
```
with
```python
    try:
        from app.rest import rest_routes
        from app.rpc import rpc_app

        routes = rest_routes()
        services = rpc_app()
    except Exception:
```
and `    target.mount("/api/rpc", services)` with
```python
    target.router.routes.extend(routes)
    target.mount("/api/rpc", services)
```

- [ ] **Step 4: Green.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m pytest -q -p no:xdist tests/test_rest.py tests/test_rpc_mount.py tests/test_api_docs.py tests/test_contract.py
```
Expected: all pass, `test_rest.py` 18. `test_api_docs.py` passes unchanged: the transcoder's routes are plain Starlette routes, which FastAPI's schema does not list.

- [ ] **Step 5: Gates.** ruff clean; mypy `Success: no issues found in 210 source files`; the full suite once, previous count plus 18.

- [ ] **Step 6: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/rest/__init__.py server/app/main.py server/tests/conftest.py server/tests/test_rest.py server/tests/test_rpc_mount.py && git commit -F - <<'EOF'
Serve v2 over REST under /api/v2, transcoded from the contract's annotations

rest_routes() builds one Starlette route per unary method from its
google.api.http rule and calls the same invoke Connect does, so the two
transports cannot disagree about a rule. A request binds its path
variables (dotted ones renamed for Starlette), its body as the rule says,
and every other field from the query string whatever the verb, so an
Update's update_mask arrives there; If-None-Match fills if_none_match and
wins over a query parameter. Canonical JSON, unknown fields ignored, a body
over Connect's 4 MB refused with Connect's own words.

Successes are 200, except the eight Creates whose proto comment promises
201 (a table here, held to the comments by a test); not_modified is a 304
with its ETag and no body; diary reads are never cached; no CORS header is
sent. The harness now calls a method both ways in one test and asserts one
outcome.

Not covered: no unary method has a handler yet; the next commits add the
four 3a serves.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 8: `GetMe` and `GetDiaryCapabilities`; the gate, the reads and the no-echo sweep over what is served

Decisions 3 (behaviour over served methods), 5 (no echo), 10 (reads write nothing) and 12 (capabilities from today's registry).

**Files:**
- Create: `server/app/rpc/me.py`, `server/app/rpc/diary.py`, `server/tests/test_v2_reads.py`, `server/tests/test_v2_no_echo.py`
- Modify: `server/app/rpc/handlers.py`

**Interfaces:**
- Consumes: the harness (`v2`, `v2_tokens`), `HANDLERS`, `METHODS`, `values.role`, `linking.Access`.
- Produces: handlers `me.get_me(call, GetMeRequest) -> GetMeResponse` and `diary.get_diary_capabilities(call, GetDiaryCapabilitiesRequest) -> GetDiaryCapabilitiesResponse`. The tests `test_the_gate_stands_in_front_of_every_served_method[…]` and `test_no_refusal_repeats_what_was_sent[…]` are parametrised over `HANDLERS` at collection, so every method a later task registers is held by them without a line changing.

- [ ] **Step 1: Red.** Create `server/tests/test_v2_reads.py`:
```python
"""The reads 3a serves, the gate in front of every method it serves, and reads that write nothing.

``GetMe`` and ``GetDiaryCapabilities`` are asked both ways and against v1's
own answer for the same caller, because «the same answer in v2's shape» is the
promise (``docs/specs/2026-10-05-server-v2-design.md``, decisions 10 and 12).
The gate's behaviour is asked of every method in ``HANDLERS``, so a method a
later task registers is held by the same test the moment it is served.
The statement test counts writes, not rows: the link code, the feed secret
and a relinked subject are all updates, which a row count cannot see.
"""

from __future__ import annotations

import contextlib
import re
from collections.abc import Iterator

import pytest
from sqlalchemy import event

from app.contract.lessons.v2.me_pb import GetMeRequest, Me
from app.contract.lessons.v2.options_pb import AuthKind
from app.contract.lessons.v2.options_pb import Role as ProtoRole
from app.db import engine
from app.rpc.handlers import HANDLERS
from app.rpc.methods import METHODS

#: The refusals only the gate makes.
GATE_REASONS = {"DEVICE_TOKEN_INVALID", "DIARY_TOKEN_INVALID", "DEVICE_NOT_LINKED", "ROLE_REQUIRED"}

#: The writes a read may make, on purpose (decision 10): telemetry no client
#: observes, a diary credential the upstream rotated and when it was used,
#: and the throttles' and the directory counter's own rows.
ALLOWED_WRITES = (
    re.compile(r"^UPDATE device_tokens SET last_seen_at=\? WHERE"),
    re.compile(r"^UPDATE diary_sessions SET (?:(?:upstream_token|last_used_at)=\?(?:, )?)+ WHERE"),
    re.compile(r"^(?:INSERT INTO|UPDATE|DELETE FROM) (?:join_attempts|usage_counters)\b"),
)


@contextlib.contextmanager
def writes() -> Iterator[list[str]]:
    """Every INSERT, UPDATE and DELETE the engine sends while the block runs."""
    seen: list[str] = []

    def record(conn, cursor, statement, parameters, context, executemany) -> None:
        if re.match(r"\s*(?:INSERT|UPDATE|DELETE)\b", statement, re.IGNORECASE):
            seen.append(" ".join(statement.split()))

    event.listen(engine.sync_engine, "before_cursor_execute", record)
    try:
        yield seen
    finally:
        event.remove(engine.sync_engine, "before_cursor_execute", record)


def unexpected(statements: list[str]) -> list[str]:
    return [s for s in statements if not any(rule.match(s) for rule in ALLOWED_WRITES)]


def _served() -> list[str]:
    return sorted(HANDLERS)


def _request(key: str):
    method = METHODS[key]
    request = method.input()
    if key == "lessons.v2.ScheduleService/GetScheduleWindow":
        request.year = 2026
    return request


async def _call(v2, key: str, token: str | None):
    name = key.removeprefix("lessons.v2.")
    if METHODS[key].streaming:
        return await v2.stream(name, token=token)
    return await v2.both(name, _request(key), token=token, differ=("token", "window.generated_at"))


# ---- the gate, over every method 3a serves, on both paths -------------------


@pytest.mark.parametrize("key", _served())
async def test_the_gate_stands_in_front_of_every_served_method(v2, v2_tokens, key) -> None:
    method = METHODS[key]
    nobody = await _call(v2, key, None)
    wrong = await _call(
        v2, key, v2_tokens["owner"] if method.auth is AuthKind.DIARY else v2_tokens["diary"]
    )
    if method.auth is AuthKind.NONE:
        assert nobody.reason not in GATE_REASONS
        return
    expected = "DIARY_TOKEN_INVALID" if method.auth is AuthKind.DIARY else "DEVICE_TOKEN_INVALID"
    assert (nobody.code, nobody.reason) == ("UNAUTHENTICATED", expected)
    assert (wrong.code, wrong.reason) == ("UNAUTHENTICATED", expected)
    if method.auth is AuthKind.DIARY:
        return

    unlinked = await _call(v2, key, v2_tokens["unlinked"])
    linked_needed = method.auth is AuthKind.DEVICE_LINKED or (
        method.min_role is not None and method.min_role > ProtoRole.VIEWER
    )
    if linked_needed:
        assert unlinked.reason == "DEVICE_NOT_LINKED"
    else:
        assert unlinked.reason not in GATE_REASONS
    if method.min_role is not None and method.min_role > ProtoRole.VIEWER:
        below = {ProtoRole.EDITOR: "viewer", ProtoRole.ADMIN: "editor", ProtoRole.OWNER: "admin"}
        refused = await _call(v2, key, v2_tokens[below[method.min_role]])
        assert refused.reason == "ROLE_REQUIRED"


async def test_a_phone_revoked_between_two_calls_is_refused_on_the_second(
    v2, v2_tokens, session
) -> None:
    """The gate reads the token on every call; nothing of the first call's
    answer outlives a revocation made in the bot between the two."""
    from sqlalchemy import update

    from app.models import DeviceToken
    from app.security import hash_token

    token = v2_tokens["editor"]
    assert (await v2.both("MeService/GetMe", token=token)).message.me.can_edit is True
    await session.execute(
        update(DeviceToken)
        .where(DeviceToken.token_hash == hash_token(token))
        .values(revoked=True)
    )
    await session.commit()
    refused = await v2.both("MeService/GetMe", token=token)
    assert (refused.status, refused.reason, refused.error) == (
        401,
        "DEVICE_TOKEN_INVALID",
        "Invalid token",
    )


# ---- GetMe -----------------------------------------------------------------


@pytest.mark.parametrize("who", ["unlinked", "viewer", "editor", "admin", "owner", "stranger"])
async def test_get_me_is_v1_s_me_without_the_code(v2, v2_tokens, who) -> None:
    answer = await v2.both("MeService/GetMe", GetMeRequest(), token=v2_tokens[who])
    v1 = (
        await v2.http.get("/api/v1/me", headers={"Authorization": f"Bearer {v2_tokens[who]}"})
    ).json()
    me = answer.message.me
    assert me.device_name == f"{who} phone"
    assert me.linked == v1["linked"]
    assert me.role == (ProtoRole[v1["role"].upper()] if v1["role"] else ProtoRole.UNSPECIFIED)
    assert me.can_edit == v1["can_edit"]


async def test_get_me_answers_in_binary_too(v2, v2_tokens) -> None:
    answer = await v2.connect("MeService/GetMe", token=v2_tokens["editor"], binary=True)
    assert answer.message.me == Me(
        device_name="editor phone", linked=True, role=ProtoRole.EDITOR, can_edit=True
    )


# ---- GetDiaryCapabilities -------------------------------------------------


async def test_diary_capabilities_are_v1_s_in_v2_s_shape(v2) -> None:
    answer = await v2.both("DiaryService/GetDiaryCapabilities")
    v1 = (await v2.http.get("/api/v1/diary/capabilities")).json()
    capabilities = answer.message.capabilities
    assert capabilities.enabled is v1["enabled"] is True
    providers = {p.provider: p for p in capabilities.providers}
    assert set(providers) == {"petersburg", "netschool"}
    assert list(providers["netschool"].regions) == v1["providers"]["netschool"]["regions"]
    assert list(providers["petersburg"].regions) == []
    assert all(not p.sign_in_methods and not p.features for p in capabilities.providers)
    assert answer.headers["cache-control"] == "private, no-store"


async def test_diary_capabilities_say_when_the_diary_is_off(v2, monkeypatch) -> None:
    monkeypatch.setattr("app.rpc.diary.diary_enabled", lambda: False)
    answer = await v2.both("DiaryService/GetDiaryCapabilities")
    assert answer.message.capabilities.enabled is False


# ---- reads that write nothing (decision 10) --------------------------------


@pytest.mark.parametrize("who", ["unlinked", "editor"])
async def test_get_me_writes_nothing_but_the_last_seen(v2, v2_tokens, who) -> None:
    with writes() as seen:
        await v2.both("MeService/GetMe", GetMeRequest(), token=v2_tokens[who])
    assert unexpected(seen) == []


async def test_diary_capabilities_write_nothing(v2) -> None:
    with writes() as seen:
        await v2.both("DiaryService/GetDiaryCapabilities")
    assert seen == []


async def test_the_statement_probe_sees_the_write_v1_makes_on_a_read(v2, v2_tokens) -> None:
    """Held here rather than trusted: v1's ``/me`` mints a link code for an
    unlinked phone — an UPDATE, which a count of rows would never see."""
    with writes() as seen:
        await v2.http.get(
            "/api/v1/me", headers={"Authorization": f"Bearer {v2_tokens['unlinked']}"}
        )
    assert any("link_code" in statement for statement in unexpected(seen))
```
and `server/tests/test_v2_no_echo.py`:
```python
"""No refusal repeats what was sent: the programme's «No error ever echoes a request field».

A password-shaped string is sent in every field of every method v2 serves —
as text, as an object where text belongs, as a list — on both transports, in
the path, in the query, in the bearer and in the client header, and no
refusal carries it back, in its body or in a header. protobuf-py's own
decoder quotes the value it refused («invalid integer value: '…'»), so this
holds the fixed sentence ``rpc/errors.py`` answers with instead
(``docs/specs/2026-10-05-server-v2-design.md``, decision 5).
"""

from __future__ import annotations

import json
from urllib.parse import quote, urlencode

import httpx
import pytest

from app.config import get_settings
from app.rpc.handlers import HANDLERS
from app.rpc.methods import METHODS

SECRET = "Pa55w0rd-s3cr3t-Hunter2"

SERVED = sorted(key for key in HANDLERS if not METHODS[key].streaming)


def _path(key: str, fill: str = "2026") -> str:
    binding = METHODS[key].binding
    assert binding is not None
    path = binding.path
    for variable in binding.variables:
        path = path.replace("{" + variable + "}", quote(fill, safe=""))
    return "/api" + path


def _leaks(response: httpx.Response) -> bool:
    return SECRET in response.text or any(SECRET in value for value in response.headers.values())


@pytest.mark.parametrize("key", SERVED)
async def test_no_refusal_repeats_what_was_sent(v2, v2_tokens, monkeypatch, key) -> None:
    monkeypatch.setattr(get_settings(), "min_client_version", 40)
    method = METHODS[key]
    binding = method.binding
    assert binding is not None
    bearer = {"Authorization": f"Bearer {v2_tokens['unlinked']}"}
    answers: list[httpx.Response] = []

    for field in method.input.desc().fields:
        for raw in (SECRET, SECRET * 8, {"nested": SECRET}, [SECRET]):
            body = json.dumps({field.json_name: raw}).encode()
            answers.append(
                await v2.http.post(
                    f"/api/rpc/{key}",
                    content=body,
                    headers={**bearer, "Content-Type": "application/json"},
                )
            )
            if binding.body:
                answers.append(
                    await v2.http.request(
                        binding.verb.upper(),
                        _path(key),
                        content=body,
                        headers={**bearer, "Content-Type": "application/json"},
                    )
                )
            elif isinstance(raw, str):
                answers.append(
                    await v2.http.request(
                        binding.verb.upper(),
                        f"{_path(key)}?{urlencode({field.json_name: raw})}",
                        headers=bearer,
                    )
                )

    if binding.variables:
        answers.append(
            await v2.http.request(binding.verb.upper(), _path(key, SECRET), headers=bearer)
        )
    for headers in ({"Authorization": f"Bearer {SECRET}"}, {**bearer, "X-Lessons-Client": SECRET}):
        answers.append(
            await v2.http.post(
                f"/api/rpc/{key}",
                content=b"{}",
                headers={**headers, "Content-Type": "application/json"},
            )
        )
        answers.append(await v2.http.request(binding.verb.upper(), _path(key), headers=headers))

    refused = [response for response in answers if response.status_code >= 400]
    assert refused, "the sweep refused nothing, so it proved nothing"
    leaking = [
        f"{response.request.method} {response.request.url} -> {response.status_code}"
        for response in refused
        if _leaks(response)
    ]
    assert leaking == []


def test_the_decoder_itself_would_have_quoted_it() -> None:
    """Held here rather than trusted: the sweep above means something only
    because the library's own message carries the value."""
    from app.contract.lessons.v2.schedule_pb import GetScheduleWindowRequest

    with pytest.raises(ValueError) as refused:
        GetScheduleWindowRequest.from_json(json.dumps({"year": SECRET}))
    assert SECRET in str(refused.value)
```
Run `python -m pytest -q -p no:xdist tests/test_v2_reads.py tests/test_v2_no_echo.py`. Expected: every test that calls `GetMe` or `GetDiaryCapabilities` fails, because both answer `UNIMPLEMENTED` (an `AttributeError` on `message`, or an assertion on the reason); the `WatchClass` case of the gate test, the no-echo sweeps, the statement probe and the decoder test already pass.

- [ ] **Step 2: Create `server/app/rpc/me.py`.**
```python
"""``MeService``: what a phone does for itself.

3a serves ``GetMe``; the other eight methods are 3b's.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.contract.lessons.v2.me_pb import GetMeRequest, GetMeResponse, Me
from app.rpc import values
from app.services.linking import Access

if TYPE_CHECKING:
    from app.rpc.call import Call


async def get_me(call: Call, request: GetMeRequest) -> GetMeResponse:
    """Who this device is. Mints nothing: v1's ``/me`` issued a link code as a
    side effect, which ``CreateLinkCode`` does in v2 (decision 10). The role is
    the one the gate read for this call."""
    device, _school_class = call.device_and_class()
    access = Access.of(device, call.role)
    return GetMeResponse(
        me=Me(
            device_name=device.device_name,
            linked=access.linked,
            role=values.role(access.role),
            can_edit=access.can_edit,
        )
    )
```

- [ ] **Step 3: Create `server/app/rpc/diary.py`.**
```python
"""``DiaryService``: one family's account with an electronic diary.

3a serves ``GetDiaryCapabilities``; sessions and every read are 3b's, with the
per-provider registry table (decision 12).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.contract.lessons.v2.diary_pb import (
    DiaryCapabilities,
    GetDiaryCapabilitiesRequest,
    GetDiaryCapabilitiesResponse,
    ProviderCapabilities,
)
from app.crypto import diary_enabled
from app.providers.diary.registry import KEYS, NETSCHOOL

if TYPE_CHECKING:
    from app.rpc.call import Call


async def get_diary_capabilities(
    call: Call, request: GetDiaryCapabilitiesRequest
) -> GetDiaryCapabilitiesResponse:
    """What this server's diary can do, before the phone takes a password.

    The same answer v1's ``/diary/capabilities`` gives, in v2's shape
    (decision 12): whether the diary runs here at all, every provider the
    registry knows, and for «Сетевой город» the allow-list's regions that take
    a password. ``sign_in_methods`` and ``features`` stay empty, because v1's
    answer has neither and what each provider declares is 3b's registry table
    to say, from what its connection really implements. Anonymous and
    database-free, as v1's: the gate opens a scope, and nothing asks it for a
    query.
    """
    from app.providers.netschool import regions

    listed = [region.key for region in regions.listed()]
    return GetDiaryCapabilitiesResponse(
        capabilities=DiaryCapabilities(
            enabled=diary_enabled(),
            providers=[
                ProviderCapabilities(provider=key, regions=listed if key == NETSCHOOL else [])
                for key in KEYS
            ],
        )
    )
```

- [ ] **Step 4: Register them.** In `server/app/rpc/handlers.py`, replace `from app.rpc import watch` with `from app.rpc import diary, me, watch`, and insert above `    "lessons.v2.WatchService/WatchClass": watch.watch_class,`:
```python
    "lessons.v2.DiaryService/GetDiaryCapabilities": diary.get_diary_capabilities,
    "lessons.v2.MeService/GetMe": me.get_me,
```

- [ ] **Step 5: Green.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m pytest -q -p no:xdist tests/test_v2_reads.py tests/test_v2_no_echo.py tests/test_rest.py tests/test_rpc_errors.py
```
Expected: all pass — `test_v2_reads.py` 17 (the gate test over three served methods), `test_v2_no_echo.py` 3. `test_rest.py`'s handlers put into `HANDLERS` for `GetMe` and `GetDiaryCapabilities` replace the real ones for their test only.

- [ ] **Step 6: Gates.** ruff clean; mypy `Success: no issues found in 212 source files`; the full suite once, previous count plus 20.

- [ ] **Step 7: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/rpc/me.py server/app/rpc/diary.py server/app/rpc/handlers.py server/tests/test_v2_reads.py server/tests/test_v2_no_echo.py && git commit -F - <<'EOF'
Serve GetMe and GetDiaryCapabilities over v2, writing nothing

GetMe is v1's /me without the code: it reads the role the gate read for
the call and mints no link code, which CreateLinkCode will do.
GetDiaryCapabilities is v1's /diary/capabilities in v2's shape: whether the
diary runs, every provider the registry knows, and «Сетевой город»'s
password regions; the sign-in methods and features stay empty until 3b's
registry table declares them.

Three tests now stand over every method HANDLERS serves, so each later
method joins them by being registered: the gate's behaviour on both paths
(no credential, the wrong kind, an unlinked phone, the role below), a
sweep that sends a password-shaped string in every field, the path, the
query, the bearer and the client header and finds it in no refusal, and a
statement listener that fails a read on any INSERT, UPDATE or DELETE
outside last_seen_at, the diary's re-seal and the throttles' rows. Each is
shown to see what it is written for: v1's /me minting a code, protobuf-py
quoting a refused value.

Not covered: CreateDevice and GetScheduleWindow, the next two commits.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 9: `CreateDevice`, on v1's budget

Decisions 2, 4 and 11.

**Files:**
- Create: `server/app/rpc/device.py`, `server/tests/test_v2_devices.py`
- Modify: `server/app/rpc/handlers.py`

**Interfaces:**
- Consumes: Task 3's `join.join` and its exceptions; Task 4's `validate` and `TABLE`; `schemas.JoinRequest`; `registry.binding`; `Call.bucket()`.
- Produces: `device.create_device(call, CreateDeviceRequest) -> CreateDeviceResponse`.

- [ ] **Step 1: Red.** Create `server/tests/test_v2_devices.py`:
```python
"""``CreateDevice``: v1's ``POST /join`` over v2, by the same flow and on the same budget.

Every refusal v1's ``/join`` tests is asked here too, with v1's own words,
and the throttle is asked across the two versions: a caller who alternates
them, or opens a second connection from the same address, draws on one
budget (``docs/specs/2026-10-05-server-v2-design.md``, decision 11).
"""

from __future__ import annotations

import httpx
from sqlalchemy import func, select

from app import wording
from app.contract.lessons.v2.device_pb import CreateDeviceRequest
from app.main import app
from app.models import AuditEntry, DeviceToken, JoinAttempt, JoinMode, SchoolClass
from app.security import MAX_DEVICES_PER_CLASS, hash_token, join_limiter
from app.services import device_invites

WRONG = CreateDeviceRequest(code="NOSUCH99")


async def _attempts(session) -> int:
    return await session.scalar(select(func.count()).select_from(JoinAttempt)) or 0


async def test_the_class_code_buys_a_token_and_rest_says_201(v2, school_class) -> None:
    answer = await v2.both(
        "DeviceService/CreateDevice",
        CreateDeviceRequest(code="test42", device_name="Pixel 8"),
        differ=("token",),
    )
    assert answer.status == 201
    response = answer.message
    assert (response.class_id, response.class_name) == (school_class.id, "9А")
    assert response.school == "Школа № 1"
    assert response.timezone == school_class.timezone_name
    assert not response.has_field("diary")

    rest_token = (
        await v2.rest("DeviceService/CreateDevice", CreateDeviceRequest(code="TEST42"))
    ).message.token
    me = await v2.both("MeService/GetMe", token=rest_token)
    assert me.message.me.linked is False


async def test_the_answer_is_v1_s_answer(v2, school_class) -> None:
    v1 = (await v2.http.post("/api/v1/join", json={"code": "TEST42"})).json()
    v2_answer = (
        await v2.rest("DeviceService/CreateDevice", CreateDeviceRequest(code="TEST42"))
    ).message
    assert (v2_answer.class_id, v2_answer.class_name, v2_answer.school, v2_answer.timezone) == (
        v1["class_id"],
        v1["class_name"],
        v1["school"],
        v1["timezone"],
    )


async def test_a_personal_code_links_the_phone_and_writes_the_journal(
    v2, session, school_class
) -> None:
    code = await device_invites.mint(session, telegram_id=2007, class_id=school_class.id)
    await session.commit()
    answer = await v2.rest("DeviceService/CreateDevice", CreateDeviceRequest(code=code))
    assert answer.status == 201
    device = await session.scalar(
        select(DeviceToken).where(DeviceToken.token_hash == hash_token(answer.message.token))
    )
    assert device.telegram_id == 2007 and device.linked_at is not None
    assert await session.scalar(select(func.count()).select_from(AuditEntry)) == 1

    again = await v2.connect("DeviceService/CreateDevice", CreateDeviceRequest(code=code))
    assert (again.code, again.reason) == ("NOT_FOUND", "JOIN_CODE_UNKNOWN")


async def test_a_wrong_code_is_refused_in_v1_s_words_and_stays_counted(v2, session) -> None:
    v1 = await v2.http.post("/api/v1/join", json={"code": "NOSUCH99"})
    before = await _attempts(session)
    answer = await v2.both("DeviceService/CreateDevice", WRONG)
    assert (answer.status, answer.code, answer.reason) == (404, "NOT_FOUND", "JOIN_CODE_UNKNOWN")
    assert answer.error == v1.json()["detail"] == wording.JOIN_UNKNOWN_CODE_DETAIL
    # Refused, and the call rolled back — and both attempts are still counted,
    # because the throttle commits before the code is looked at.
    assert await _attempts(session) == before + 2


async def test_an_invite_only_class_refuses_its_code_and_does_not_count_it(v2, session) -> None:
    session.add(SchoolClass(name="9Б", join_code="LOCKED12", join_mode=JoinMode.INVITE))
    await session.commit()
    before = await _attempts(session)
    answer = await v2.both("DeviceService/CreateDevice", CreateDeviceRequest(code="LOCKED12"))
    assert (answer.status, answer.code, answer.reason) == (
        403,
        "PERMISSION_DENIED",
        "CLASS_INVITE_ONLY",
    )
    assert answer.error == wording.JOIN_INVITE_ONLY_DETAIL
    assert await _attempts(session) == before


async def test_a_full_class_refuses_its_code_with_the_limit_and_does_not_count_it(
    v2, session, school_class
) -> None:
    session.add_all(
        DeviceToken(token_hash=hash_token(f"full-{n}"), class_id=school_class.id)
        for n in range(MAX_DEVICES_PER_CLASS)
    )
    await session.commit()
    before = await _attempts(session)
    answer = await v2.both("DeviceService/CreateDevice", CreateDeviceRequest(code="TEST42"))
    assert (answer.status, answer.code, answer.reason) == (
        429,
        "RESOURCE_EXHAUSTED",
        "DEVICE_LIMIT_REACHED",
    )
    assert answer.metadata == {"limit": str(MAX_DEVICES_PER_CLASS)}
    assert answer.error == wording.JOIN_DEVICE_LIMIT_DETAIL
    assert await _attempts(session) == before


async def test_a_code_v1_would_reject_is_rejected_and_not_counted(v2, session) -> None:
    before = await _attempts(session)
    for code in ("ab", "    ", "X" * 17):
        answer = await v2.both("DeviceService/CreateDevice", CreateDeviceRequest(code=code))
        assert (answer.code, answer.reason) == ("INVALID_ARGUMENT", "VALIDATION_FAILED"), code
        assert [field for field, _ in answer.violations] == ["code"]
        assert (await v2.http.post("/api/v1/join", json={"code": code})).status_code == 422
    assert await _attempts(session) == before


async def test_v1_and_v2_draw_on_one_budget(v2) -> None:
    """Alternating versions does not double a caller's wrong guesses."""
    for attempt in range(join_limiter.limit):
        if attempt % 2:
            assert (
                await v2.http.post("/api/v1/join", json={"code": "NOSUCH99"})
            ).status_code == 404
        else:
            assert (
                await v2.rest("DeviceService/CreateDevice", WRONG)
            ).reason == "JOIN_CODE_UNKNOWN"

    # Each transport on its own, not `both`: the seconds left are counted at
    # each call, and two calls a millisecond apart may straddle a second.
    rest = await v2.rest("DeviceService/CreateDevice", WRONG)
    connect = await v2.connect("DeviceService/CreateDevice", WRONG)
    for refused in (rest, connect):
        assert (refused.code, refused.reason) == ("RESOURCE_EXHAUSTED", "THROTTLED")
        assert refused.error == wording.JOIN_THROTTLED_DETAIL
        seconds = int(refused.metadata["retry_after_seconds"])
        assert seconds >= 1 and refused.retry_seconds == seconds
    assert rest.status == 429
    assert rest.headers["retry-after"] == rest.metadata["retry_after_seconds"]
    assert (await v2.http.post("/api/v1/join", json={"code": "NOSUCH99"})).status_code == 429


async def test_two_connections_from_one_address_are_one_caller(v2) -> None:
    """``connectrpc`` reports the peer as ``host:port``; the port is stripped,
    so a second connection is not a second budget."""
    ports = (40001, 40002)
    clients = [
        httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app, client=("198.51.100.20", port)),
            base_url="http://test",
        )
        for port in ports
    ]
    try:
        for attempt in range(join_limiter.limit):
            response = await clients[attempt % 2].post(
                "/api/rpc/lessons.v2.DeviceService/CreateDevice",
                content=WRONG.to_json(),
                headers={"Content-Type": "application/json"},
            )
            assert response.status_code == 404
        last = await clients[0].post(
            "/api/rpc/lessons.v2.DeviceService/CreateDevice",
            content=WRONG.to_json(),
            headers={"Content-Type": "application/json"},
        )
        assert last.status_code == 429
        assert last.json()["code"] == "resource_exhausted"
    finally:
        for client in clients:
            await client.aclose()


async def test_a_correct_code_is_never_counted(v2, school_class) -> None:
    for _ in range(join_limiter.limit + 2):
        answer = await v2.connect("DeviceService/CreateDevice", CreateDeviceRequest(code="TEST42"))
        assert answer.status == 200
```
Run `python -m pytest -q -p no:xdist tests/test_v2_devices.py`. Expected: every test fails on `UNIMPLEMENTED`.

- [ ] **Step 2: Create `server/app/rpc/device.py`.**
```python
"""``DeviceService``: a code in, the device token out. v1's ``POST /join``."""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.contract.lessons.v2.device_pb import (
    CreateDeviceRequest,
    CreateDeviceResponse,
    DiaryBinding,
)
from app.providers.diary.registry import binding
from app.rpc.errors import validate
from app.schemas import JoinRequest
from app.services import join

if TYPE_CHECKING:
    from app.rpc.call import Call


async def create_device(call: Call, request: CreateDeviceRequest) -> CreateDeviceResponse:
    """Exchange a code for a long-lived device token, by v1's flow.

    Validated with v1's own ``JoinRequest``, so the two versions refuse the
    same codes (4 to 16 usable characters, a name up to 120). Then
    ``services/join.py``, over ``security``'s one ``join_limiter`` and the
    caller's bucket under v1's scope, so v1 and v2 draw on one budget
    (decision 11); its refusals are worded by ``rpc/errors.py``'s table. The
    service commits, as v1's endpoint did: a wrong code stays counted whatever
    this call then rolls back.
    """
    form = validate(
        JoinRequest,
        {
            "code": request.code,
            "device_name": request.device_name if request.has_field("device_name") else None,
        },
    )
    joined = await join.join(
        call.session, code=form.code, device_name=form.device_name, client_key=call.bucket()
    )
    school_class = joined.school_class
    bound = binding(school_class)
    return CreateDeviceResponse(
        token=joined.token,
        class_id=school_class.id,
        class_name=school_class.name,
        school=school_class.school,
        timezone=school_class.timezone_name,
        diary=DiaryBinding(
            provider=bound.provider.key,
            region=bound.region,
            school_id=bound.school_id,
            school_name=bound.school_name,
        )
        if bound is not None
        else None,
    )
```

- [ ] **Step 3: Register it.** In `server/app/rpc/handlers.py`, replace `from app.rpc import diary, me, watch` with `from app.rpc import device, diary, me, watch`, and insert as the table's first row:
```python
    "lessons.v2.DeviceService/CreateDevice": device.create_device,
```

- [ ] **Step 4: Green.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m pytest -q -p no:xdist tests/test_v2_devices.py tests/test_v2_reads.py tests/test_v2_no_echo.py tests/test_join_modes.py tests/test_join_device_cap.py tests/test_hardening.py
```
Expected: all pass — `test_v2_devices.py` 10; the gate and no-echo tests gain `CreateDevice`'s case each. Note `DEVICE_LIMIT_REACHED` is `429` on REST, where v1 answers `409`: the contract puts it under `RESOURCE_EXHAUSTED`.

- [ ] **Step 5: Gates.** ruff clean; mypy `Success: no issues found in 213 source files`; the full suite once, previous count plus 12.

- [ ] **Step 6: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/rpc/device.py server/app/rpc/handlers.py server/tests/test_v2_devices.py && git commit -F - <<'EOF'
Serve CreateDevice over v2, on the same budget as v1's /join

CreateDevice validates with v1's own JoinRequest and joins through
services/join.py, over security's one join_limiter and the caller's
bucket, so v1 and v2 refuse the same codes and count the same attempts. A
wrong code is still counted although the call is refused and rolled back,
because the throttle commits before the code is looked at; an invite-only
class, a full class and a malformed code are not counted, as in v1. REST
answers 201, as the proto promises.

The tests ask every refusal v1's /join tests, in v1's words, and the budget
both ways: alternating v1 and v2 is refused on the thirty-first attempt,
and two connections from one address, on two ports, are one caller.

Not covered: GetScheduleWindow, the next commit.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 10: `GetScheduleWindow`: one school year, its tag, and nothing written

Decisions 6 (the tag), 10 (terms computed) and the design's «Risks» (the 2 MB ceiling).

**Files:**
- Create: `server/app/rpc/schedule.py`, `server/tests/test_v2_window.py`
- Modify: `server/app/rpc/values.py` (rewrite), `server/app/rpc/handlers.py` (rewrite), `server/tests/test_rpc_errors.py` (`HELD_BY`, Ruling 24)

**Interfaces:**
- Consumes: Task 3's `window.for_year`, `strong_etag`, `etag_matches`, `linking.Access`; `ResolvedDay`; Task 9's `test_v2_devices.py`, whose tests `HELD_BY` names.
- Produces:
  - `app.rpc.values` (complete): `proto_name`, `date_string(date) -> str`, `time_string(time) -> str` (`"HH:MM"`), `instant(datetime) -> Timestamp` (naive is UTC), `role`, `term_kind`, `term(TermSpan) -> common_pb.Term`, `device_access(Access) -> DeviceAccess`, `day(ResolvedDay) -> ScheduleDay`, `now() -> datetime` (UTC).
  - `schedule.get_schedule_window(call, GetScheduleWindowRequest) -> GetScheduleWindowResponse`.

- [ ] **Step 1: Red.** Create `server/tests/test_v2_window.py`:
```python
"""``GetScheduleWindow``: one school year, v1's days in v2's shape, writing nothing.

The window is asked against v1's ``/bundle`` for the same class over the same
dates, day by day, because «the same answer v1 gives» is what decision 10
claims for terms computed rather than seeded. Its tag is asked in both
directions on both paths, and the dense year of the design's risks is held
under 2 MB (``docs/specs/2026-10-05-server-v2-design.md``).
"""

from __future__ import annotations

import re
from datetime import UTC, date, datetime, time, timedelta

import pytest
from sqlalchemy import select

from app.contract.lessons.v2.common_pb import TermKind
from app.contract.lessons.v2.options_pb import Role as ProtoRole
from app.contract.lessons.v2.schedule_pb import DayOffReason, GetScheduleWindowRequest
from app.models import (
    BellPeriod,
    BotUser,
    DayEvent,
    EventKind,
    Homework,
    LessonOverride,
    OverrideAction,
    Role,
    SchoolClass,
    Term,
    TimetableEntry,
    WeekParity,
)
from app.services import terms as terms_service

#: The school year 2026/27: Tuesday 1 September 2026 to Monday 31 May 2027.
YEAR = 2026
FIRST, LAST = date(2026, 9, 1), date(2027, 5, 31)
EVERY_DAY = ("window.generated_at",)


def _window(year: int = YEAR, **kw) -> GetScheduleWindowRequest:
    return GetScheduleWindowRequest(year=year, **kw)


def _as_v1(day) -> dict:
    """A v2 day in v1's ``DayOut`` spelling, to compare the two field for field."""
    return {
        "date": day.date,
        "weekday": day.weekday,
        "kind": day.kind.name.lower(),
        "note": day.note if day.has_field("note") else None,
        "holiday": {
            "code": day.holiday.code,
            "title": day.holiday.title,
            "stops_lessons": day.holiday.stops_lessons,
        }
        if day.has_field("holiday")
        else None,
        "off_reason": None
        if day.off_reason is DayOffReason.UNSPECIFIED
        else day.off_reason.name.lower(),
        "lessons": [
            {
                "index": lesson.index,
                "subject": lesson.subject,
                "starts_at": lesson.starts_at,
                "ends_at": lesson.ends_at,
                "room": lesson.room if lesson.has_field("room") else None,
                "teacher": lesson.teacher if lesson.has_field("teacher") else None,
                "color": lesson.color if lesson.has_field("color") else None,
                "is_replaced": lesson.is_replaced,
                "is_cancelled": lesson.is_cancelled,
                "note": lesson.note if lesson.has_field("note") else None,
            }
            for lesson in day.lessons
        ],
        "events": [
            {
                "title": event.title,
                "kind": event.kind.name.lower(),
                "starts_at": event.starts_at,
                "ends_at": event.ends_at,
                "location": event.location if event.has_field("location") else None,
                "covers_lesson": event.covers_lesson,
            }
            for event in day.events
        ],
        "homework": [
            {
                "subject": item.subject,
                "text": item.text,
                "attachment_url": item.attachment_url if item.has_field("attachment_url") else None,
            }
            for item in day.homework
        ],
    }


def _v1_day(day: dict) -> dict:
    """v1's day with its times cut to v2's ``HH:MM``, the one difference the
    contract makes on purpose."""
    for lesson in day["lessons"]:
        lesson["starts_at"], lesson["ends_at"] = lesson["starts_at"][:5], lesson["ends_at"][:5]
    for event in day["events"]:
        event["starts_at"], event["ends_at"] = event["starts_at"][:5], event["ends_at"][:5]
    return day


async def test_the_window_is_the_whole_school_year(v2, v2_tokens) -> None:
    answer = await v2.both(
        "ScheduleService/GetScheduleWindow",
        _window(),
        token=v2_tokens["unlinked"],
        differ=EVERY_DAY,
    )
    days = answer.message.window.days
    assert (days[0].date, days[-1].date) == (FIRST.isoformat(), LAST.isoformat())
    assert len(days) == (LAST - FIRST).days + 1
    monday = next(day for day in days if day.date == "2026-09-07")
    assert [(x.index, x.subject, x.starts_at, x.ends_at) for x in monday.lessons] == [
        (1, "Алгебра", "08:30", "09:15"),
        (2, "Физика", "09:25", "10:10"),
        (3, "История", "10:25", "11:10"),
    ]


async def test_every_day_is_v1_s_day_and_the_terms_v1_would_have_seeded(
    v2, v2_tokens, session, school_class
) -> None:
    """Asked of v2 first, so that v1's own seeding on its read cannot help it."""
    session.add(
        Homework(
            class_id=school_class.id,
            due_date=date(2026, 9, 7),
            subject_name="Алгебра",
            text="№ 1–5",
        )
    )
    session.add(
        DayEvent(
            class_id=school_class.id,
            date=date(2026, 9, 8),
            starts_at=time(12, 0),
            ends_at=time(13, 0),
            title="Экскурсия",
            kind=EventKind.TRIP,
            covers_lesson=True,
        )
    )
    await session.commit()
    token = v2_tokens["editor"]
    v2_answer = (await v2.rest("ScheduleService/GetScheduleWindow", _window(), token=token)).message
    v1 = (
        await v2.http.get(
            "/api/v1/bundle",
            params={"start": FIRST.isoformat(), "days": (LAST - FIRST).days + 1},
            headers={"Authorization": f"Bearer {token}"},
        )
    ).json()

    assert [_as_v1(day) for day in v2_answer.window.days] == [_v1_day(day) for day in v1["days"]]
    summary = v2_answer.window.school_class
    assert summary.term_kind is TermKind.QUARTER
    assert [(t.index, t.starts_on, t.ends_on) for t in summary.terms] == [
        (t["index"], t["starts_on"], t["ends_on"]) for t in v1["school_class"]["terms"]
    ]
    device = v2_answer.window.device
    assert (device.linked, device.role, device.can_edit) == (True, ProtoRole.EDITOR, True)


async def test_stored_terms_are_answered_as_stored(v2, v2_tokens, session, school_class) -> None:
    await terms_service.ensure(session, school_class, YEAR)
    first = await session.scalar(
        select(Term).where(Term.class_id == school_class.id, Term.index == 1)
    )
    first.ends_on = date(2026, 10, 26)
    await session.commit()
    answer = await v2.rest(
        "ScheduleService/GetScheduleWindow", _window(), token=v2_tokens["unlinked"]
    )
    terms = answer.message.window.school_class.terms
    assert (terms[0].ends_on, terms[1].starts_on) == ("2026-10-26", "2026-11-01")
    holidays = [d for d in answer.message.window.days if d.date == "2026-10-28"]
    assert holidays[0].off_reason is DayOffReason.BETWEEN_TERMS


async def test_a_senior_class_with_no_terms_is_shown_half_years(
    v2, v2_tokens, session, school_class
) -> None:
    klass = await session.get(SchoolClass, school_class.id)
    klass.grade = 10
    await session.commit()
    answer = await v2.rest(
        "ScheduleService/GetScheduleWindow", _window(), token=v2_tokens["unlinked"]
    )
    summary = answer.message.window.school_class
    assert summary.term_kind is TermKind.SEMESTER
    assert [(t.index, t.starts_on, t.ends_on) for t in summary.terms] == [
        (1, "2026-09-01", "2026-12-31"),
        (2, "2027-01-01", "2027-05-31"),
    ]


@pytest.mark.parametrize("year", [0, 1999, 2099, 10000])
async def test_a_year_outside_the_bounds_is_refused_on_its_field(v2, v2_tokens, year) -> None:
    answer = await v2.both(
        "ScheduleService/GetScheduleWindow", _window(year), token=v2_tokens["unlinked"]
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "INVALID_ARGUMENT",
        "VALIDATION_FAILED",
    )
    assert answer.violations == [("year", "year must be between 2000 and 2098")]


async def test_the_first_and_last_years_are_served(v2, v2_tokens) -> None:
    for year in (2000, 2098):
        answer = await v2.rest(
            "ScheduleService/GetScheduleWindow", _window(year), token=v2_tokens["unlinked"]
        )
        assert answer.status == 200, year


# ---- the tag ---------------------------------------------------------------


async def test_an_unchanged_window_keeps_its_tag_and_its_answer_moves_on(v2, v2_tokens) -> None:
    token = v2_tokens["unlinked"]
    first = await v2.rest("ScheduleService/GetScheduleWindow", _window(), token=token)
    second = await v2.connect("ScheduleService/GetScheduleWindow", _window(), token=token)
    assert first.message.etag == second.message.etag == first.headers["etag"]
    assert re.fullmatch(r'"[0-9a-f]{64}"', first.message.etag)
    generated = first.message.window.generated_at.to_datetime()
    assert abs(generated - datetime.now(UTC)) < timedelta(minutes=1)


@pytest.mark.parametrize(
    "shape",
    ["{tag}", "W/{tag}", '"another", {tag}', "*"],
    ids=["exact", "weak", "listed", "star"],
)
async def test_a_matching_tag_answers_not_modified_on_both_paths(v2, v2_tokens, shape) -> None:
    token = v2_tokens["unlinked"]
    tag = (await v2.rest("ScheduleService/GetScheduleWindow", _window(), token=token)).message.etag
    answer = await v2.both(
        "ScheduleService/GetScheduleWindow",
        _window(if_none_match=shape.format(tag=tag)),
        token=token,
    )
    assert answer.status == 304 and answer.body == b""
    assert answer.headers["etag"] == tag
    assert answer.message.not_modified is True and not answer.message.has_field("window")


async def test_a_stale_tag_gets_the_window(v2, v2_tokens) -> None:
    answer = await v2.both(
        "ScheduleService/GetScheduleWindow",
        _window(if_none_match='"stale"'),
        token=v2_tokens["unlinked"],
        differ=EVERY_DAY,
    )
    assert answer.status == 200 and answer.message.has_field("window")


async def test_the_tag_moves_with_the_class_and_with_the_device_s_role(
    v2, v2_tokens, session, school_class
) -> None:
    token = v2_tokens["viewer"]
    before = (
        await v2.rest("ScheduleService/GetScheduleWindow", _window(), token=token)
    ).message.etag
    session.add(
        Homework(
            class_id=school_class.id, due_date=date(2026, 9, 7), subject_name="Физика", text="§ 3"
        )
    )
    await session.commit()
    after_homework = (
        await v2.rest("ScheduleService/GetScheduleWindow", _window(), token=token)
    ).message.etag
    member = await session.scalar(select(BotUser).where(BotUser.telegram_id == 2001))
    member.role = Role.EDITOR
    await session.commit()
    after_role = (
        await v2.rest("ScheduleService/GetScheduleWindow", _window(), token=token)
    ).message.etag
    assert len({before, after_homework, after_role}) == 3


# ---- writing nothing, and the size of a dense year -------------------------


async def test_the_window_writes_nothing_where_v1_seeds_and_adopts(v2, v2_tokens) -> None:
    from sqlalchemy import event

    from app.db import engine

    seen: list[str] = []

    def record(conn, cursor, statement, parameters, context, executemany) -> None:
        if statement.lstrip().split(" ", 1)[0].upper() in ("INSERT", "UPDATE", "DELETE"):
            seen.append(" ".join(statement.split()))

    event.listen(engine.sync_engine, "before_cursor_execute", record)
    try:
        await v2.both(
            "ScheduleService/GetScheduleWindow",
            _window(),
            token=v2_tokens["unlinked"],
            differ=EVERY_DAY,
        )
        v2_writes = list(seen)
        seen.clear()
        await v2.http.get(
            "/api/v1/bundle", headers={"Authorization": f"Bearer {v2_tokens['unlinked']}"}
        )
    finally:
        event.remove(engine.sync_engine, "before_cursor_execute", record)

    assert all(s.startswith("UPDATE device_tokens SET last_seen_at=?") for s in v2_writes), (
        v2_writes
    )
    assert any(s.startswith("INSERT INTO terms") for s in seen), "the probe saw v1 seed nothing"
    assert any(s.startswith("INSERT INTO subjects") for s in seen), "the probe saw v1 adopt nothing"


async def test_a_dense_year_stays_under_two_megabytes(v2, v2_tokens, session, school_class) -> None:
    """The design's risks: Vercel fails a response over 4.5 MB. Six weekdays of
    eight lessons, a homework of 200 characters on every lesson of every
    teaching day, two events and one substitution a week."""
    session.add(
        BellPeriod(
            schedule_id=school_class.bell_schedule_id,
            index=8,
            starts_at=time(15, 15),
            ends_at=time(16, 0),
        )
    )
    for weekday in range(1, 7):
        for index in range(1, 9):
            if weekday == 1 and index <= 3:
                continue
            session.add(
                TimetableEntry(
                    class_id=school_class.id,
                    weekday=weekday,
                    index=index,
                    subject_name=f"Предмет {index}",
                    room=str(200 + index),
                    parity=WeekParity.ANY,
                )
            )
    day = FIRST
    while day <= LAST:
        if day.isoweekday() <= 6:
            for index in range(1, 9):
                session.add(
                    Homework(
                        class_id=school_class.id,
                        due_date=day,
                        subject_name=f"Предмет {index}",
                        text="Прочитать параграф и ответить на вопросы. " * 5,
                    )
                )
        if day.isoweekday() in (2, 4):
            session.add(
                DayEvent(
                    class_id=school_class.id,
                    date=day,
                    starts_at=time(16, 0),
                    ends_at=time(17, 0),
                    title="Кружок",
                    kind=EventKind.EVENT,
                )
            )
        if day.isoweekday() == 3:
            session.add(
                LessonOverride(
                    class_id=school_class.id,
                    date=day,
                    index=2,
                    action=OverrideAction.REPLACE,
                    subject_name="Замена",
                    room="101",
                )
            )
        day += timedelta(days=1)
    await session.commit()

    answer = await v2.rest(
        "ScheduleService/GetScheduleWindow", _window(), token=v2_tokens["unlinked"]
    )
    assert answer.status == 200
    assert len(answer.body) < 2_000_000, len(answer.body)
    assert sum(len(d.homework) for d in answer.message.window.days) > 1500
```
  Then the design's first table test (decision 5, Ruling 24), now that the last test it names exists. In `server/tests/test_rpc_errors.py`:
  1. Replace `from app.schemas import JoinRequest` with the three lines `from app.schemas import JoinRequest`, `from app.services import diary as diary_service` and `from app.services import join, window`.
  2. Insert immediately above `def _proto_reasons() -> dict[str, str]:`
```python
#: Each row of ``errors.TABLE`` and the test that raises its exception through
#: a served method and reads the refusal back on both transports, as a file
#: under ``tests/`` and a function in it; or "3b", where no method 3a serves
#: can raise it. A row added to the table without either fails below.
HELD_BY: dict[type[Exception], tuple[str, str] | str] = {
    join.JoinThrottled: ("test_v2_devices.py", "test_v1_and_v2_draw_on_one_budget"),
    join.JoinCodeUnknown: (
        "test_v2_devices.py",
        "test_a_wrong_code_is_refused_in_v1_s_words_and_stays_counted",
    ),
    join.ClassInviteOnly: (
        "test_v2_devices.py",
        "test_an_invite_only_class_refuses_its_code_and_does_not_count_it",
    ),
    join.DeviceLimitReached: (
        "test_v2_devices.py",
        "test_a_full_class_refuses_its_code_with_the_limit_and_does_not_count_it",
    ),
    window.YearOutOfBounds: (
        "test_v2_window.py",
        "test_a_year_outside_the_bounds_is_refused_on_its_field",
    ),
    # The gate raises it for a diary method, and 3a serves none:
    # test_rpc_gate.py holds the gate raising it until 3b does.
    diary_service.DiaryDisabled: "3b",
}


```
  3. Insert immediately above `def test_a_refusal_is_its_code_its_reason_and_the_details_the_proto_promises() -> None:`
```python
def test_every_row_of_the_table_names_the_test_that_reads_it_back() -> None:
    """The design's first table test (decision 5): every row raises its
    exception through a real method and is read back on both paths. The
    reading back is the named test's; this holds that no row is without one.
    A test module may not import another (``test_test_imports.py``), so the
    named function is found by parsing its file."""
    assert set(HELD_BY) == set(errors.TABLE)
    missing = []
    for exception, held in HELD_BY.items():
        if held == "3b":
            continue
        assert isinstance(held, tuple), (exception.__name__, held)
        file_name, function = held
        tree = ast.parse((SERVER / "tests" / file_name).read_text(encoding="utf-8"))
        defined = {
            node.name
            for node in tree.body
            if isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef)
        }
        if function not in defined:
            missing.append(f"{exception.__name__}: {file_name}::{function}")
    assert missing == []


```
Run `python -m pytest -q -p no:xdist tests/test_v2_window.py tests/test_rpc_errors.py`. Expected: every test of `test_v2_window.py` fails on `UNIMPLEMENTED` except `test_the_window_writes_nothing_where_v1_seeds_and_adopts`, which an unserved method passes trivially — it holds the handler from Step 3 on. `test_rpc_errors.py` passes, the new test included: it asks only that each row names a test that exists, and this step wrote the last of them. A row without a test, or a name with no function behind it, fails it (both seen in the review's scratch run).

- [ ] **Step 2: Rewrite `server/app/rpc/values.py` in full.**
```python
"""Model ↔ message conversions every handler shares.

One place for the conventions ``common.proto`` states, so that two handlers
cannot write a date, a time or a role two ways: a date is ``"YYYY-MM-DD"``, a
time of day ``"HH:MM"`` (v1 wrote seconds; v2 does not), an instant a
``Timestamp``, and an enum is matched to the model's by its member name —
``DayKind.SELF_STUDY`` is ``DAY_KIND_SELF_STUDY`` — so a value added to one
and not the other is a ``KeyError`` in a test rather than a silent default.
"""

from __future__ import annotations

from datetime import UTC, datetime
from datetime import date as Date
from datetime import time as Time

from protobuf import Enum
from protobuf.wkt import Timestamp

from app.contract.lessons.v2 import common_pb, options_pb, schedule_pb
from app.models import Role, TermKind
from app.schedule import ResolvedDay
from app.services.linking import Access
from app.services.terms import TermSpan


def proto_name(member: Enum) -> str:
    """The value's name as the proto writes it — ``"ROLE_ADMIN"``, not the
    generated member's ``ADMIN`` — which is what metadata and JSON carry."""
    return next(value.name for value in type(member).desc().values if value.number == member.value)


def date_string(day: Date) -> str:
    return day.isoformat()


def time_string(clock: Time) -> str:
    return clock.strftime("%H:%M")


def instant(moment: datetime) -> Timestamp:
    """A ``Timestamp`` for ``moment``. A naive datetime is UTC, as every naive
    ``DateTime`` column of the model is."""
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    return Timestamp.from_datetime(moment)


def role(value: Role | None) -> options_pb.Role:
    return options_pb.Role[value.name] if value is not None else options_pb.Role.UNSPECIFIED


def term_kind(value: TermKind) -> common_pb.TermKind:
    return common_pb.TermKind[value.name]


def term(span: TermSpan) -> common_pb.Term:
    return common_pb.Term(
        index=span.index,
        kind=term_kind(span.kind),
        starts_on=date_string(span.starts_on),
        ends_on=date_string(span.ends_on),
    )


def device_access(access: Access) -> schedule_pb.DeviceAccess:
    return schedule_pb.DeviceAccess(
        linked=access.linked, role=role(access.role), can_edit=access.can_edit
    )


def day(resolved: ResolvedDay) -> schedule_pb.ScheduleDay:
    """One resolved date as ``ScheduleDay``: v1's ``DayOut``, field for field."""
    return schedule_pb.ScheduleDay(
        date=date_string(resolved.date),
        weekday=resolved.weekday,
        kind=common_pb.DayKind[resolved.kind.name],
        note=resolved.note,
        holiday=schedule_pb.Holiday(
            code=resolved.holiday.code,
            title=resolved.holiday.title,
            stops_lessons=resolved.holiday.stops_lessons,
        )
        if resolved.holiday is not None
        else None,
        off_reason=schedule_pb.DayOffReason[resolved.off_reason.name]
        if resolved.off_reason is not None
        else schedule_pb.DayOffReason.UNSPECIFIED,
        lessons=[
            schedule_pb.Lesson(
                index=lesson.index,
                subject=lesson.subject,
                starts_at=time_string(lesson.starts_at),
                ends_at=time_string(lesson.ends_at),
                room=lesson.room,
                teacher=lesson.teacher,
                color=lesson.color,
                is_replaced=lesson.is_replaced,
                is_cancelled=lesson.is_cancelled,
                note=lesson.note,
            )
            for lesson in resolved.lessons
        ],
        events=[
            schedule_pb.ScheduleEvent(
                title=event.title,
                kind=common_pb.EventKind[event.kind.name],
                starts_at=time_string(event.starts_at),
                ends_at=time_string(event.ends_at),
                location=event.location,
                covers_lesson=event.covers_lesson,
            )
            for event in resolved.events
        ],
        homework=[
            schedule_pb.ScheduleHomework(
                subject=item.subject, text=item.text, attachment_url=item.attachment_url
            )
            for item in resolved.homework
        ],
    )


def now() -> datetime:
    """The instant an answer is made, for ``generated_at``. A function, so a
    test can pin it."""
    return datetime.now(UTC)
```

- [ ] **Step 3: Create `server/app/rpc/schedule.py`.**
```python
"""``ScheduleService``: one school year of the class's days. v1's ``GET /bundle``."""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.contract.lessons.v2.schedule_pb import (
    ClassSummary,
    GetScheduleWindowRequest,
    GetScheduleWindowResponse,
    ScheduleWindow,
)
from app.rpc import values
from app.services import window as window_service
from app.services.linking import Access

if TYPE_CHECKING:
    from app.rpc.call import Call


async def get_schedule_window(
    call: Call, request: GetScheduleWindowRequest
) -> GetScheduleWindowResponse:
    """Every day of the school year that opens in ``request.year``, and its tag.

    Writes nothing (decision 10): terms are computed when the year has none,
    subjects are not adopted, and the only write is the gate's
    ``last_seen_at``. The tag is a strong SHA-256 of the window's canonical
    JSON before ``generated_at`` is set, so two answers of an unchanged window
    carry one tag; a ``W/``, a list or ``*`` in ``if_none_match`` match as
    v1's ``If-None-Match`` did. A year outside ``window.FIRST_YEAR`` ..
    ``LAST_YEAR`` is ``VALIDATION_FAILED`` on ``year``.
    """
    device, school_class = call.device_and_class()
    built = await window_service.for_year(
        call.session, school_class, request.year, access=Access.of(device, call.role)
    )
    window = ScheduleWindow(
        school_class=ClassSummary(
            id=school_class.id,
            name=school_class.name,
            grade=school_class.grade,
            letter=school_class.letter,
            school=school_class.school,
            city=school_class.city,
            timezone=school_class.timezone_name,
            term_kind=values.term_kind(built.scheme),
            terms=[values.term(span) for span in built.terms],
        ),
        days=[values.day(day) for day in built.days],
        device=values.device_access(built.access),
    )
    etag = window_service.strong_etag(window.to_json())
    if request.has_field("if_none_match") and window_service.etag_matches(
        request.if_none_match, etag
    ):
        return GetScheduleWindowResponse(not_modified=True, etag=etag)
    window.generated_at = values.instant(values.now())
    return GetScheduleWindowResponse(etag=etag, window=window)
```

- [ ] **Step 4: Rewrite `server/app/rpc/handlers.py`** with every 3a method:
```python
"""Which methods this deployment serves, and the handler of each.

A method missing here answers ``UNIMPLEMENTED`` on both transports, before
any gate or scope, exactly as the generated ``Protocol``'s default does. 3a
serves four methods and ``WatchClass``'s refusal; 3b fills the rest in, one
service at a time.

Handler modules import ``Call`` only for their annotations, so that
``call.py``, which imports this table, is never imported back.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

from app.rpc import device, diary, me, schedule, watch

#: A handler: ``async def handler(call: Call, request: <Method>Request) -> <Method>Response``.
Handler = Callable[[Any, Any], Awaitable[Any]]

#: Keyed as ``rpc.methods.METHODS`` is: ``"lessons.v2.<Service>/<Method>"``.
HANDLERS: dict[str, Handler] = {
    "lessons.v2.DeviceService/CreateDevice": device.create_device,
    "lessons.v2.DiaryService/GetDiaryCapabilities": diary.get_diary_capabilities,
    "lessons.v2.MeService/GetMe": me.get_me,
    "lessons.v2.ScheduleService/GetScheduleWindow": schedule.get_schedule_window,
    "lessons.v2.WatchService/WatchClass": watch.watch_class,
}
```

- [ ] **Step 5: Green.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m pytest -q -p no:xdist tests/test_v2_window.py tests/test_v2_reads.py tests/test_v2_no_echo.py tests/test_rpc_errors.py
```
Expected: all pass — `test_v2_window.py` 18, `test_rpc_errors.py` 12. `test_every_day_is_v1_s_day_and_the_terms_v1_would_have_seeded` is the review's claim of decision 10, asked of every day of 2026/27; `test_a_dense_year_stays_under_two_megabytes` inserts about 1,900 homework rows, so it is the slowest test of the task (about two seconds here).

- [ ] **Step 6: Gates.** ruff clean; mypy `Success: no issues found in 214 source files`; the full suite once, previous count plus 21.

- [ ] **Step 7: Commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/rpc/values.py server/app/rpc/schedule.py server/app/rpc/handlers.py server/tests/test_v2_window.py server/tests/test_rpc_errors.py && git commit -F - <<'EOF'
Serve GetScheduleWindow over v2: a school year, its tag, and no write

GetScheduleWindow answers every day of the school year that opens in the
year asked, from its first teaching day through 31 May, in v1's resolution
and v2's shape: times as HH:MM, enums by name, generated_at an instant. A
year with no terms is answered the conventional set for the class's scheme
as plain values, never seeded; subjects are not adopted; the only write is
the gate's last_seen_at. The tag is a strong SHA-256 of the window's
canonical JSON before generated_at is set, and a weak tag, a list or *
match as v1's If-None-Match did, answering not_modified (a 304 over REST).
A year outside 2000 to 2098 is VALIDATION_FAILED on year.

The tests compare every day of 2026/27 with v1's /bundle for the same
class, asked of v2 first so v1's own seeding cannot help it; hold the tag
both ways on both paths; and hold a dense year (six days of eight lessons,
homework on every lesson) under 2 MB; it came to 992,080 bytes, about a
fifth of Vercel's 4.5 MB.

With the window served, every row of the error table has the test that
reads it back through a real method on both paths, and test_rpc_errors.py
names each one in HELD_BY (DiaryDisabled's is 3b's): a row added without
its test fails there.

Not covered: the documents, the next commit.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

---

## Task 11: The documents from «will» to «does», the counts, and the HANDOVER close-out

**Files:**
- Modify: `docs/api.md`, `docs/README.md`, `docs/deploy.md`, `docs/architecture.md`, `CLAUDE.md`, `README.md`, `CONTRIBUTING.md`, `.claude/skills/gates/SKILL.md`, `HANDOVER.md`, `docs/history.md`

**Interfaces:**
- Consumes: Tasks 1–10, and the real numbers of Step 6's run.
- Produces: documents true at the moment the pull request merges, and the post-merge check.

- [ ] **Step 1: `docs/api.md`.**
  1. Replace the two lines «A second version, v2, is written down as a proto contract and served by nothing yet:» / ««v2: the contract», at the end of this page.» with:
```markdown
A second version, v2, is a proto contract served beside v1 under `/api/v2` and `/api/rpc`,
four of its methods so far: «v2: the contract», at the end of this page.
```
  2. Replace the paragraph that begins «**Written down, not served.**» (to «…This section says only what a file cannot.») with:
```markdown
**Served beside v1, four methods so far.** Everything above this section is v1, and v1 is
unchanged. v2 is the contract in `proto/lessons/v2/` at the root of the repository, checked by
Buf, with its Python generated into `server/app/contract/`. Sub-project 3 of
[the programme](specs/2026-10-03-one-contract-design.md) serves it in three stages
([its design](specs/2026-10-05-server-v2-design.md)). The first serves `GetScheduleWindow`,
`GetMe`, `GetDiaryCapabilities` and `CreateDevice`; every other method answers `UNIMPLEMENTED`
until its stage, before it asks for any credential. No APK calls v2 yet. The proto files are
the reference: every service, method, message and field there carries the comment that says
what it means and what v1 sent in its place. This section says only what a file cannot.
```
  3. Under «One contract, three ways to call it», the sentence «Each method will be reachable three ways, from one handler:» is split across two lines. Replace the two lines
```markdown
plus `options.proto`, `errors.proto` and `common.proto`. Each method will be reachable three
ways, from one handler:
```
  with
```markdown
plus `options.proto`, `errors.proto` and `common.proto`. Each method is reachable three ways,
from one handler — `server/app/rpc/call.py`'s `invoke`, which both transports call:
```
  In the REST bullet just below, the query string is not only a `GET`'s or a `DELETE`'s (decision 6: an `Update*`'s `update_mask` arrives there too). Replace its last two lines
```markdown
  Anything else is `POST …:verb`, as in `POST /api/v2/me:unlink`. Path fields are written
  in the path, and the rest of a `GET` or a `DELETE` in the query string.
```
  with
```markdown
  Anything else is `POST …:verb`, as in `POST /api/v2/me:unlink`. Path fields are written
  in the path, and every field the path and the body do not bind comes from the query
  string, whatever the verb.
```
  Then append after the paragraph about `WatchService.WatchClass`:
```markdown
On this deployment, which speaks HTTP/1.1, a request with `Content-Type: application/grpc` or
`application/grpc+…` is refused with `415` and a sentence saying where native gRPC is served;
gRPC-Web passes. `WatchClass` answers `UNIMPLEMENTED` with the reason `FEATURE_UNSUPPORTED`
(`feature: "streaming"`): no deployment streams yet. If a deployment cannot load v2 at all,
`/api/v2` and `/api/rpc` answer `503` in their own error shapes and v1 goes on.

### What REST adds

- **Statuses.** A success is `200` with the `<Method>Response` as canonical JSON, except the
  eight methods whose proto comment says «REST answers 201», which answer `201`:
  `CreateBellSchedule`, `CreateDevice`, `CreateDiarySession`, `CreateEvent`, `CreateHomework`,
  `CreateTask`, `CreateSubject` and `CreateSubstitution`.
- **Binding.** Path variables bind into the request, `{task.id}` included; the body is the
  whole request (`body: "*"`), one field, or nothing, as the method's rule says; and every
  field neither binds comes from the query string, whatever the verb — an `Update…`'s
  `update_mask` is `?updateMask=title,done`. Dotted names reach nested fields and a repeated
  name a repeated field; a `bool` is `true` or `false`. Unknown fields and parameters are
  ignored, as Connect ignores them. A body that does not decode is `INVALID_ARGUMENT` with the
  reason `REQUEST_UNDECODABLE`, and a body over 4 MB is refused as Connect refuses it.
- **Caching.** A request with an `if_none_match` field takes it from `If-None-Match`, which wins
  over a query parameter of that name; a list, `*` and `W/` match as `/bundle`'s do. A response
  with `not_modified` set is a `304` with its `ETag` and no body; any other response with an
  `etag` sends it as `ETag`. `GetScheduleWindow`'s tag is a strong SHA-256 of the window's
  canonical JSON without `generatedAt`. Every diary read carries `Cache-Control: private,
  no-store`.
- **No CORS header**, on any answer: v2 answers apps, not pages on other sites.
```
  4. Under «Who may call a method», append:
```markdown
Every request may carry `X-Lessons-Client: <versionCode>`, and an APK's first v2 build sends it.
The server reads it before anything else. With `MIN_CLIENT_VERSION` set, a version below it is
`FAILED_PRECONDITION` with the reason `CLIENT_TOO_OLD` and `min_version` in its metadata, and a
header that is not a whole number from 1 to 2100000000, the most a versionCode can be, is
`VALIDATION_FAILED`; without a minimum a malformed header is ignored. A request without the
header is never refused. Then, in this order: a diary method on a deployment without
`DIARY_SECRET` is `DIARY_DISABLED`, before any token is read; the bearer; a linked account for
`AUTH_KIND_DEVICE_LINKED` and for any role above viewer (`DEVICE_NOT_LINKED`); the role
(`ROLE_REQUIRED`).
```
  5. Under «Errors», after the code table's paragraph, append:
```markdown
Over Connect the error is its JSON body, under the same status: `{"code": "permission_denied",
"message": …, "details": [{"type": "google.rpc.ErrorInfo", "value": <base64>, "debug": {…}}]}`.
Decode `value`; `debug` is the server library's courtesy rendering of the same message.
```

- [ ] **Step 2: `docs/README.md` and `docs/deploy.md`.** In `docs/README.md`'s row for `api.md`, replace «and at its end the v2 contract that nothing serves yet:» with «and at its end the v2 contract, served beside v1 four methods so far:».

  `docs/deploy.md` names every setting twice, and `MIN_CLIENT_VERSION` (Task 5) joins both:
  1. In the table under «Secrets» (`| Variable | Value |`), insert below the `DADATA_TOKEN` row:
```markdown
| `MIN_CLIENT_VERSION` | empty — or the oldest APK versionCode v2 still answers; raise it only once the newer APK is on the phones it would refuse |
```
  2. Under «Option 2», in the paragraph that begins «**Every setting the server reads reaches the container.**», the sentence that lists what compose hands the server gains it. Replace the line
```markdown
`DIARY_SECRET`, `DADATA_TOKEN`, `PUBLIC_BASE_URL`, `RUN_BOT` and `TRUSTED_PROXY_HOPS` from the
```
  with the two lines
```markdown
`DIARY_SECRET`, `DADATA_TOKEN`, `PUBLIC_BASE_URL`, `MIN_CLIENT_VERSION`, `RUN_BOT` and
`TRUSTED_PROXY_HOPS` from the
```

- [ ] **Step 3: `docs/architecture.md`.** In the tree under «The server», replace the `contract/` line with these three:
```
├── contract/      the v2 contract's Python, generated from proto/ — never edited by hand
├── rpc/           v2: the method table, the gate, invoke, the error table, a module per service
├── rest/          v2 over REST: routes transcoded from the contract's own annotations
```
and append after the section «One container, two shells»:
```markdown
### v2: one invoke behind two transports

v2 is served by `app/rpc/` and `app/rest/` beside v1, over the same `services/`
(`docs/specs/2026-10-05-server-v2-design.md`). Every method's credential, least role and
REST route are read from the generated descriptors once (`rpc/methods.py`); Connect's
adapters and the REST transcoder both call one `invoke` (`rpc/call.py`), which runs the
gate, opens the call's dishka scope the way the bot's middleware does, runs the handler,
commits, and only then runs the call's effects. A handler never checks a credential and
never commits, and every refusal is worded by one table (`rpc/errors.py`), so the two
transports cannot disagree about a rule. The rules v1's routers held and v2 needs moved into
`services/` first — the join flow, the window's tag, the clock and the bounds — and the
limiters into `security.py`, one instance each, so a caller cannot double its attempts by
alternating versions. `main.mount_v2` catches a v2 that will not import and answers `503`
under its two prefixes, so v1 and the webhook never go down with it.
```
  and in «Testing» replace the server count with Step 6's printed count.

- [ ] **Step 4: `CLAUDE.md`.**
  1. In the deliverables block, replace `server/app/contract/, and nothing serves it yet` with `server/app/contract/, and app/rpc and app/rest serve it beside v1`.
  2. In «Commands», read the two counts the file quotes as it stands: the `pytest -q -n auto` line's «N tests in about four minutes» and the `python -m mypy` line's «of all M modules». Both move with every batch, so this plan does not write them down. Steps 5 and 6 use the same N and M. Replace each with the number Step 6 prints: N plus the 172 tests this stage adds, and M plus its 17 modules, unless something else has moved them.
  3. In «Server modules», replace the `contract/` bullet with:
```markdown
- `contract/` — the Python `buf generate` writes from `proto/lessons/v2/` (and googleapis'
  `google/api` and `google/rpc`). Never edited by hand, since every regeneration deletes and
  rewrites it. Skipped by ruff and mypy, and on the API's cold path on purpose since v2 is
  served: `tests/test_contract.py` holds that in a fresh interpreter
- `rpc/` — v2's handlers, one module per proto service, over `services/`, and what every
  method shares: `methods.py` (each method's facts, read from the descriptors), `gate.py`
  (client version, then the bearer, the link and the role), `call.py` (`invoke`: the gate,
  one dishka scope, the handler, the one commit, then the effects), `errors.py` (the one error
  table) and `handlers.py` (which methods are served). A handler never commits and never
  checks a credential; `rpc_app()` mounts the seventeen Connect apps at `/api/rpc`, refusing
  native gRPC over HTTP/1.1 with `415`. May import `services/`, `models`, `schedule`,
  `wording`, `security`, `api/deps.py`, the diary registry and the contract — never `app.bot`
  or a v1 router; `tests/test_service_layering.py` walks it
- `rest/` — the transcoder: one Starlette route per unary method from its `google.api.http`
  rule, under `/api/v2`, calling the same `invoke`; `errors.py` writes Google's error body.
  `main.mount_v2` mounts both and answers `503` under their prefixes if v2 will not import
```
  4. Append to the `services/` bullet: «The rules v2 shares with v1 live here too: `join.py` (the join flow, refusing with facts), `window.py` (the year's window and its tag), `clock.py` (the class's clock and the date bounds); the limiters are `security.py`'s, one instance each, and the sentences both versions answer with (the join's four, the diary's «disabled») are `app/wording.py`'s.»
  5. After «…which is a different decision from making them mandatory.» add: «`MIN_CLIENT_VERSION` is optional too — empty means no minimum, and a request without `X-Lessons-Client` is never refused — and it is not a feature switched off, so it is in neither list.»

- [ ] **Step 5: `README.md`, `CONTRIBUTING.md` and the `gates` skill.** In `README.md`'s «Honest status», the `python -m mypy` row says «clean, M modules» and the `pytest -q -n auto` row «N tests», M and N as Step 4.2 read them. Replace both with Step 6's printed numbers, and add after the Buf row:
```markdown
| v2 over REST and Connect | four methods served beside v1 (`GetScheduleWindow`, `GetMe`, `GetDiaryCapabilities`, `CreateDevice`), each tested both ways in-process, the window day by day against v1's `/bundle`; no APK calls them yet |
```
In `CONTRIBUTING.md` (its «M modules» and «N of them») and `.claude/skills/gates/SKILL.md` («M modules», «N tests today») put the same two numbers.

- [ ] **Step 6: Run the gates, and write their numbers everywhere.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m ruff check app tests scripts migrations && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m mypy && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -n auto
```
Expected: `All checks passed!`; `Success: no issues found in` M + 17 `source files`; the passed count N + 172, with N and M as Step 4.2 read them from `CLAUDE.md`. The 172 is this stage's own tests, task by task: 2, 15, 24, 11, 37, 12, 18, 20, 12 and 21. If the count is not N + 172, find the test file that moved before writing anything. Write the printed numbers, not the sums, into `CLAUDE.md`, `CONTRIBUTING.md`, `README.md`, `docs/architecture.md`, the `gates` skill and `HANDOVER.md`'s cheat-sheet. Then the freshness check of Task 1 Step 5 once more → `FRESH`.

- [ ] **Step 7: The HANDOVER close-out** (the `handover` skill, «What goes stale mechanically»):
  1. Move «What the session before it added: …» verbatim, retitled «What the batch before added: …», to the top of `docs/history.md` under its introduction; the current «What the last session added: …» becomes «What the session before it added: …».
  2. Write the new section above it:
```markdown
## What the last session added: v2 served beside v1 — stage 3a of sub-project 3 (#273)

Stage 3a of `docs/specs/2026-10-05-server-v2-design.md`, built by the plan beside it, on
`server-v2/3a`, in the pull request this section rides in, on milestone 11. v1 answers exactly
as before; v2 now answers too, four methods of it.

- **The v1 rules v2 needs moved into `services/` first**, with v1 calling them: the join flow
  (`services/join.py`), the window's tag and v2's school year (`services/window.py`), the
  class's clock and the date bounds (`services/clock.py`), a device's access
  (`linking.Access`), and the four limiters with the device cap and the diary's attempt
  (`security.py`, one instance each). `caller_bucket` reads header lines and a peer host.
- **`app/rpc/` and `app/rest/` serve v2**: one method table read from the descriptors, one
  gate (client version, the diary's switch before any token, the bearer, the link, the role),
  one `invoke` per call (scope, handler, the one commit, then effects), one error table, the
  seventeen Connect apps at `/api/rpc` behind a `415` for native gRPC and a decoding guard,
  and the REST transcoder under `/api/v2`. `main.mount_v2` answers `503` there if v2 will not
  import.
- **Four methods**: `GetScheduleWindow` (a school year, terms computed rather than seeded,
  a strong tag and `not_modified`), `GetMe` (no code minted), `GetDiaryCapabilities` (v1's
  answer in v2's shape), `CreateDevice` (v1's flow, on v1's budget). `WatchClass` refuses with
  `FEATURE_UNSUPPORTED`. `MIN_CLIENT_VERSION` is a new optional setting.
- **`google/rpc` is generated** beside `google/api`, pinned to `buf.lock`'s googleapis commit.

### Gates

Write the numbers from Task 11 Step 6's run: ruff clean; mypy clean on 214 source files;
`pytest -q -n auto` with its count and its time; CI's checks on the pull request's head, read
with `gh pr checks`; Android not run, as nothing under `android/` changed.

### What was deliberately left alone

- The other seventy-one unary methods, the per-provider diary registry, the Telegram notices
  as effects: stage 3b. The host, native gRPC and `WatchClass`'s stream: stage 3c.
- #302 is not 3a's: #303 fixed v1 on 5 October 2026, and the v2 gate's own check is part of
  Task 5.
- An import-time ceiling in `test_cold_start.py` (the design's decision 8).

### What nobody has verified in this batch

- v2 on Vercel before the merge: `connectrpc`, `protobuf-py-ext` and `pyqwest` had never been
  imported there. The post-merge check below is the first look; the fail-safe mount is there
  for a failure.
- v2 against Postgres: every v2 test ran on SQLite.
- `x-vercel-forwarded-for` reaching a Connect call's bucket: held by unit tests of
  `caller_bucket`, never through Vercel's proxy.
- What `http_version` Vercel's Python bridge puts in the ASGI scope. The native-gRPC
  guard steps aside only for `"2"` and `"3"` and reads a missing one as HTTP/1.1, as ASGI
  does; a bridge that said `"2"` for a request it carried over HTTP/1.1 would let native
  gRPC through to the library's `500`. The post-merge check sends no `application/grpc`.
- `X-Lessons-Client` from an APK: no APK sends it yet.
```
  3. **The opening paragraph**: name the pull requests open at that moment, from `gh pr list --state open`, rather than assuming which they are. This pull request is one, with the number `gh pr view --json number` prints. If #301 (the design and this plan) is still open, because the owner approved while it was and Ruling 23 cut this branch from `server-v2/design`, name it too, and say that this pull request carries #301's commits and merges after it. Then `main` at the merge before this pull request (`git rev-parse --short origin/main`); the schema head unchanged at `0017`, and `EXPECTED_REVISION` unchanged.
  4. **The milestone table**: milestone 11's row gains this pull request's number.
  5. **Section 5** gains the five unverified items above; **section 7** gains: «Set `MIN_CLIENT_VERSION` only after sub-project 5's APK is on the family's phones, and never above the version they run» and the design's open questions as the owner leaves them.
  6. The cheat-sheet's counts under «How to continue»: Step 6's numbers.

- [ ] **Step 8: Gates for the documents, then commit.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m pytest -q -p no:xdist tests/test_rpc_errors.py tests/test_env_example.py tests/test_contract.py tests/test_schema_version.py
```
Expected: all pass (`test_rpc_errors.py` reads `docs/api.md`'s table). Then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add docs/api.md docs/README.md docs/deploy.md docs/architecture.md CLAUDE.md README.md CONTRIBUTING.md .claude/skills/gates/SKILL.md HANDOVER.md docs/history.md && git commit -F - <<'EOF'
Describe v2 as served, and hand the batch over

docs/api.md's «v2: the contract» says what is served now rather than what
will be: the four methods, the guard in front of native gRPC, the 503 a
deployment that cannot load v2 answers, what REST adds (201 for the eight
Creates the proto names, the query binding whatever the verb, the tag, no
CORS), the client version and the gate's order, and Connect's error body.
CLAUDE.md and docs/architecture.md name app/rpc and app/rest and the rules
that moved into services/; MIN_CLIENT_VERSION is recorded as optional and
in neither list, and docs/deploy.md names it beside every other setting
and among those compose hands the server. The counts are the run's own,
in the places that carry them.

HANDOVER.md's close-out is written while this pull request is open, so the
file is true when it merges; the batch before moves to docs/history.md.

Not covered: production has not yet answered a v2 call; the check after
the merge is in HANDOVER.md.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cv9sFsZ5Si3SUdX5cRgHKV
EOF
```

- [ ] **Step 9: Push and open the pull request** (the `github-pr` skill): from `server-v2/3a` to `main`, milestone 11, body naming «Refs #273» (#302 is closed by #303), the board item filled as the skill says. **Merge only under the skill's five checks, and only once the owner has approved the design**; otherwise leave it open and say so.

- [ ] **Step 10: After the merge, read production** (the design's decision 7). The deployment is `lessons-ruddy-zeta.vercel.app`:
```bash
curl -s https://lessons-ruddy-zeta.vercel.app/api/v1/warmup; echo
curl -s -i https://lessons-ruddy-zeta.vercel.app/api/v2/diary/capabilities
curl -s -i -X POST -H 'Content-Type: application/json' --data '{}' https://lessons-ruddy-zeta.vercel.app/api/rpc/lessons.v2.DiaryService/GetDiaryCapabilities
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -X POST -H 'Content-Type: application/proto' --data-binary '' https://lessons-ruddy-zeta.vercel.app/api/rpc/lessons.v2.DiaryService/GetDiaryCapabilities
```
Expected: `{"status":"ok","api_version":1,"schema":"0017"}`; `HTTP/1.1 200` with `cache-control: private, no-store` and a JSON body naming `petersburg` and `netschool`; a `200` JSON body from Connect; `200 application/proto`. A `503` saying «v2 is not available on this deployment» means `mount_v2` caught a failed import: read the function's runtime log for «v2 could not be loaded», and file the defect as an issue before anything else. Write what was seen into `HANDOVER.md`'s next close-out.

---

## Self-review

**Spec coverage — stage 3a of the design, item by item:**
- Decision 1, «3a merges»: `google/rpc` (Task 1); `rpc/` and `rest/` with the gate, the error table, `invoke`, the mounts and the guards (Tasks 4–7); the harness (Tasks 5–7); the four methods (Tasks 8–10); the join flow, the window builder and the throttles moved (Tasks 2–3); terms computed on a read (Task 3, Task 10); the side-effect test (Task 8, extended in 10); capabilities from today's registry (Task 8).
- Decision 2, where things live and what moves first: Tasks 2–3; the two-way layering walk (Task 5). The design names `services/join.py` and `services/window.py`, and they are those files.
- Decision 3, one `invoke` and the generic gate, its order, its table over 76 methods and its behaviour over served methods: Task 5 (`test_rpc_gate.py`, `test_rpc_call.py`), Task 8 (`test_the_gate_stands_in_front_of_every_served_method`).
- Decision 4, the scope, the commit, effects after it, the self-committing writes named and tested, the `.commit(` grep: Task 5. «A wrong code over v2 is still counted»: Task 9.
- Decision 5, the table, metadata as `errors.proto` names it, `INTERNAL` → 500 in `docs/api.md`, the registry-rendered JSON first: Tasks 1 and 4; the three table tests: rows read back through real methods on both paths (Tasks 9, 10), each named in `HELD_BY` so that a row without one fails (Task 10, Ruling 24) — except `DiaryDisabled`'s, which no served method reaches before 3b, so Task 5 holds the gate raising it and Task 4 its wording — every reason produced or later (Task 4, narrowed in 5 and 6), the no-echo sweep (Task 8). The shell's sentences shared with v1 are `app/wording.py`'s (Tasks 2, 3; Ruling 5).
- Decision 6, the transcoder: binding, the query whatever the verb, WKT strings, dotted variables, 4 MB, 201 for eight, no CORS, diary `no-store`, the ETag rule: Task 7; the tag of the window: Task 10.
- Decision 7, the mounts, the fail-safe with its test, the 415 for anything but HTTP/2 and HTTP/3 (a scope with no version included), the decoding guard, `WatchClass` with the marker, the post-merge check: Tasks 6 and 11.
- Decision 8, the cold start turned round, no ceiling: Task 6.
- Decision 9, the client version, up to the build's own ceiling, and the setting forwarded by `docker-compose.yml`: Task 5; in `docs/deploy.md`: Task 11.
- Decision 10, terms as plain values, no adoption on a read, the statement-count test with the allowlist: Tasks 3, 8, 10.
- Decision 11, one instance each, one bucket for v1 and v2 and for two ports: Tasks 2 and 9.
- Decision 12 (3a's part), capabilities from today's registry: Task 8.
- Decision 14, the harness both ways with binary cases: Tasks 5–7 (`v2.both`, `v2.connect(binary=True)` in Task 8).
- «Delivers: the documents turned from will to does»: Task 11.
- Not 3a, and not planned here: decisions 12's table and 13, the other methods, the notices, the host.

**Placeholder scan:** no «TBD», no «similar to Task N»; every code step quotes the file or the exact replacement. The test and module counts in Task 11 are read from the files at execution (N and M, Step 4.2) and written as the run prints them, with what this stage adds beside them (172 tests, 17 modules), because the suite's size on the day of execution is not known now.

**Type consistency:** `Method.key` is `"lessons.v2.<Service>/<Method>"` in `methods.py`, `HANDLERS`, `gate.TABLE`, `rest.CREATED` and every test; the harness's `name` is the same without `lessons.v2.`. `invoke(method, request, *, headers, peer)` is called with those keywords by `rpc/__init__.py`, `rest/__init__.py` and `test_rpc_call.py`. `Refusal(reason, message, *, violations=(), **metadata)` is raised that way in `errors.py`, `gate.py`, `watch.py` and the tests. `caller_bucket(headers, peer, *, scope="")` is called so by `Call.bucket`, `request_bucket` and the tests; `peer` is a host, made one by `peer_host` for Connect. `Access(linked, role)` is built by `Access.of` — directly in `me.py` and `schedule.py`, through `linking.access_of` in `public.py`. `TermSpan(index, kind, starts_on, ends_on)` is what `spans` returns and `values.term` reads. `wording.JOIN_THROTTLED_DETAIL`, `JOIN_UNKNOWN_CODE_DETAIL`, `JOIN_INVITE_ONLY_DETAIL`, `JOIN_DEVICE_LIMIT_DETAIL` and `DIARY_DISABLED_DETAIL` are read under those names by `api/public.py`, `api/diary.py`, `rpc/errors.py` and `test_v2_devices.py`, and `services/join.py` and `services/diary.py` define no sentence. `gate.MAX_CLIENT_VERSION` is the one ceiling `client_version` and `BAD_CLIENT_HEADER` read, and `test_rpc_gate.py` asks it at 2100000000 and one above.

**Review Focus:** each of the five lines names its test and its task, and each test was run in the scratch copy.
