# Serving v2, stage 3c: the host and the beta (sub-project 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** written on 10 October 2026 against `a434c9c`, the head of `server-v2/3b-7` (3b-7's close-out, open as #392), in the worktree `continue-previous-session-991230`. Stage 3b-8, the diary's corrections, comes between: as this plan was finished its Tasks 1 to 4 were committed on `server-v2/3b-8` (`1c0a362`), its review's fixes were under way and its documents (its Task 5) were not yet written, and it touches only `services/diary_corrections.py`, `diary_overrides.py`, `api/diary.py`, `rpc/diary.py`, `rpc/errors.py`, `rpc/handlers.py`, `proto/lessons/v2/diary.proto`, their tests, and the documents of its Task 5. This plan's code anchors are in none of those files but `rpc/handlers.py`, whose docstring and `HANDLERS` 3b-8 edits (Task 4 here says how to read them as 3b-8 leaves them); its document anchors were chosen among the lines 3b-8's Task 5 does not rewrite, and «Where 3b-8 moves an anchor», below, lists every place the two meet. Every code step was applied in order to a copy of `a434c9c`'s tree and checked, and every step, documents included, was applied again to `1c0a362`'s committed tree, where each anchor was found exactly once («What was verified»). It is the third and last stage of the design; with it the design is delivered.

**Goal:** Serve the host target — `python -m app.host`, the whole app over HTTP/2 under pyvoy, with hypercorn as the fallback — with native gRPC and `WatchClass`'s stream of «this class changed», fed by an in-process bus that hears every shell's writes; lock the host's dependencies apart from Vercel's; ship its image; and prove it in a CI job that starts the host and asks it over native gRPC.

**Architecture:**
- **The bus, `app/watch.py`.** A listener on SQLAlchemy's `Session` collects, before each flush, the class of every row written to a table a schedule window is read from — a bell period through its schedule, the class through its own id, a member's role because every window carries it — and publishes the set once the transaction's outermost commit is done, never at a savepoint's release, forgetting it when the transaction ends otherwise. A bulk statement calls `watch.touch(session, class_id)`, and a test walks all of `app/` for one that does not. It is attached by `app.main` only where streaming is on.
- **The stream.** `rpc/call.stream` serves `STREAMS` beside `invoke`: the gate runs in a scope of its own, committed and closed, before the first message and before every one after it; `rpc/watch.watch_class` sends the class's revision (`<boot>.<count>`) on open, on every change, and every thirty seconds; a revoked phone or a deleted class ends the stream with the gate's own `DEVICE_TOKEN_INVALID`. No session is held between messages.
- **The host, `app/host.py`.** One ASGI app — `app.main`'s, with native gRPC answered at the root as well, where a gRPC client calls — served by pyvoy (Envoy, one worker thread, lifespan required, gzip) with Envoy told to take the client from the connection rather than from `X-Forwarded-For` (#395), or by hypercorn. The launcher reads the settings first and never sets the marker.
- **The marker and the switch.** `LESSONS_TARGET=host` is the image's statement about itself, and with it `get_settings` refuses a deployment as on Vercel; any other value is refused. `LESSONS_STREAMING=true` turns the stream on anywhere but Vercel.
- **The lock and the image.** `server/requirements-host.in` → `requirements-host.txt`, compiled against `requirements.txt` for every platform; the root `Dockerfile` installs both locks and the package without its floors, as a user of its own, with the marker set.
- **CI.** A «Host» job, a matrix of pyvoy and hypercorn, starts the host on SQLite without the marker and runs `tests/test_host_live.py` (marker `host`, skipped by every other run) with grpcio: a unary call, an unknown method, `WatchClass` hearing an ORM write, a bulk write and a bell change, a revoked phone cut off, the rest of the app over HTTP/1.1, and a forged `X-Forwarded-For` throttled all the same.

**Tech Stack:** Python 3.12, FastAPI/Starlette, dishka, SQLAlchemy 2.1 async, `connectrpc` 0.12.1, `protobuf-py` 0.6.0, Buf 1.73.0; new: pyvoy 1.3.0 (with `envoy-server` 1.39.3, uvloop 0.23.0 on Linux and winloop 0.7.1 on Windows), hypercorn 0.18.0, and grpcio 1.84.0 for the tests that ask a running host; uv 0.12.3 compiles the host's lock; pytest with httpx's ASGI transport, and a hand-driven ASGI scope for a stream.

**Spec:** `docs/specs/2026-10-05-server-v2-design.md`: decision 1's third row (3c), **decision 13 whole**, decision 14 (the host's tests behind a marker the ordinary run skips), decision 7 (the settings refusal on both targets, `LESSONS_TARGET`, no flag that could claim a stream on Vercel), and the questions' answers: the host is a complete deployment chosen instead of Vercel, single instance (question 2), deployed nowhere until a phone needs it (question 3). It sits under the programme `docs/specs/2026-10-03-one-contract-design.md` (section 2, «The two targets are two entry points»; «What the spike found»). The build console's design `docs/specs/2026-10-05-build-console-design.md` asks this stage to give its job a row in the console's task table if the console has merged: it has not (`tools/console/` does not exist), so its stage 6c adds the row (Ruling 163). 3a's plan and the 3b plan describe the machinery used here, and their Rulings 1 to 142 still hold.

## How 3c is cut

One pull request, `server-v2/3c`, cut from `main` once 3b-8 has merged, on milestone 11, referring to #273. This plan is its first commit, as `docs/specs/2026-10-05-server-v2-3c-plan.md`, made by the controller before Task 1; `test_schema_version.py` reads it from then on. Eight tasks:

| Task | Title | Tests added | Suite after | mypy after |
| --- | --- | --- | --- | --- |
| 1 | The class-changed bus, `app/watch.py` | 23 | 3165 | 239 |
| 2 | Every bulk write on a window table touches the bus, the bot's included | 7 | 3172 | 239 |
| 3 | The host's marker and the streaming switch in the settings | 15 | 3187 | 239 |
| 4 | `WatchClass`: a stream that holds no session, served by `call.stream` | 13 | 3200 | 239 |
| 5 | `app/host.py`: the root, the Envoy correction, the launcher | 9 | 3209 | 240 |
| 6 | The host's lock and its image | 8 | 3217 | 240 |
| 7 | CI's «Host» job and the tests it runs, and the batch's one full run | 5 + 6 skipped | 3228 | 240 |
| 8 | The documents, the counts, the HANDOVER close-out | — | 3228 | 240 |

**Counts.**
- Tests: 23 + 7 + 15 + 13 + 9 + 8 + 11 = **86** collected. Six of them, `test_host_live.py`'s, are skipped by every run but CI's «Host» job (Ruling 159), so a full run reports **3222 passed, 6 skipped**. The suite as 3b-8's merge leaves it is 3142, as its close-out records (3094 at `a434c9c`, plus its 48). Counted with `pytest --collect-only -q` on the copy, where the base is `a434c9c` and its count 3094: 3117, 3124, 3139, 3152, 3161, 3169 and 3180 after Tasks 1 to 7. Task 4's thirteen are `test_v2_watch.py`'s: `test_v2_reads.py`'s gate test now walks `STREAMS` beside `HANDLERS`, and `WatchClass` moved from one to the other, so its cases are as many as before; the no-echo sweep never walked a stream.
- mypy: **238** at the base and through 3b-8; `app/watch.py` makes 239 (Task 1) and `app/host.py` 240 (Task 5). If mypy on the branch's first commit prints another number, shift every N by the difference.

**Commands.** As 3b-8's:
- `WT` is `/c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230`, and its venv is `$WT/server/.venv`, made in that tree, which the #312 guard asks for.
- `pytest` is `$WT/server/.venv/Scripts/pytest.exe`, run from `$WT/server`, bare, as CI runs it; a task's gate adds `-p no:xdist` and names its files. Before any run, `wmic process where "name='python.exe' or name='pytest.exe'" get CommandLine | grep -i pytest` must print nothing: one test process at a time on this machine, and `tasklist` does not show a `python -m pytest`. The full suite runs once, alone, at the end of Task 7, as `pytest -q -n 4`, and the controller runs it.
- `ruff` and `mypy` are `$WT/server/.venv/Scripts/python.exe -m ruff check app tests scripts migrations` and `… -m mypy`, from `$WT/server`.
- `buf` is `/c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe` (1.73.0), run from `$WT`; `buf breaking` runs from `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\breaking3c.sh` (written in Task 4), because the shell refuses `.git#ref` on a command line.
- `uv` is `/c/Users/lumen/.local/bin/uv` (0.12.3); it compiles the host's lock in Task 6 and resolves nothing else.
- **The host's packages go into the worktree's venv in Task 6**, from the lock that task writes, and grpcio beside them, so that Task 6's gates check `app/host.py` against pyvoy's and hypercorn's own types and Task 7 can start the host and ask it: `pip install -r requirements-host.txt` (Step 3) and `pip install "grpcio>=1.84.0"` (Step 5). On Windows that is 20.7 MB of Envoy, 5.3 MB of grpcio and under 3 MB of the rest; it moves no pin of `requirements.txt`. Tasks 1 to 5 need none of it.
- Commit messages are `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3c-t<N>.txt`, written with the Write tool and committed with `git commit -F`. They carry no trailer lines (Ruling 102).
- The shell refuses a compound command that `cd`s to a computed path, so every command below spells its paths out. One heavy job at a time: never two test processes at once, never the suite beside Gradle, and never a host left running past its step.
- The files of this checkout are CRLF on disk and LF in Git, as `core.autocrlf` leaves them; the code below is LF, and the Edit tool matches either. A file a step writes whole is written LF. `requirements-host.txt` is written by uv, and its line ends are uv's.

#395 is the defect the controller filed before Task 1 (a placeholder while the plan was written), and `#PR` is the number the controller obtains at Task 8 Step 7. 3142 is the suite's count at 3b-8's merge, read from 3b-8's close-out, and `[AFTER-3b8]` is the slot in Task 8 Step 8 for what the controller hands over about 3b-8's merge.

## Rulings for 3c

Numbered after the plan's own 1 to 17, 3b-2's 18 to 33, 3b-3's 34 to 51, 3b-4's 52 to 67, 3b-5's 68 to 86, 3b-6's 87 to 103, 3b-7's 104 to 123 and 3b-8's 124 to 142, which still hold. The design's answers are taken as given: the host is a complete deployment chosen instead of Vercel, single-instance (question 2), and deployed nowhere by this sub-project (question 3). Each ruling below is a choice the design leaves to the code, or a place where the code showed the design's text could not be followed literally; each says what it costs if it is wrong.

143. **A stream is served by `call.stream`, beside `invoke`, from a registry of its own** (Task 4). `rpc/handlers.STREAMS` holds `WatchClass`, which leaves `HANDLERS`: a streaming handler is an async generator, and `HANDLERS`' type is a function that returns an awaitable, which mypy would refuse as a dict item. `call.stream` answers a key with no handler `UNIMPLEMENTED` before any gate, as `invoke` does, wraps the handler's generator in `contextlib.aclosing`, and words a refusal through the one table. connectrpc closes the stream's generator once its client has gone, and closing a generator does not close the one it is iterating: without `aclosing` the handler's own `finally`, which forgets the watcher, waits for asyncio's finalizer hook — an iteration or two of the loop on CPython, the collector's next pass when a reference cycle holds the generator. `test_a_watcher_that_goes_away_is_forgotten` holds the behaviour and cannot prove the mechanism: with the `async with aclosing(…)` block replaced by a bare loop, `-k goes_away` still passed (probed), the orphaned generator having been handed to the hook before the test looked. The RPC adapter hands connectrpc `stream(...)` itself rather than a generator around it, for the same reason. `test_v2_reads.py`'s gate test walks `HANDLERS` and `STREAMS`, so `WatchClass` keeps its place there. Cost if wrong: none a client sees; a second registry to read.
144. **The gate runs again, whole, before every message after the first, in a scope of its own** (Task 4; decision 13, «the token is re-checked on every event and on a heartbeat»). `Stream.recheck` is `gate.admit` in a fresh dishka scope, committed — the device stays seen, on its fifteen-minute clock — and closed; nothing of a session outlives a check (`test_a_watcher_holds_no_connection_between_its_messages` reads the pool). A refusal is the gate's own: a revoked phone is `DEVICE_TOKEN_INVALID`; a deleted class takes its devices with it by the cascade, so it is the same `DEVICE_TOKEN_INVALID` (3a's Ruling 15 kept `CLASS_GONE` for a device whose class vanished without the cascade, which cannot happen on Postgres or on this suite's SQLite); a minimum client version raised meanwhile is `CLIENT_TOO_OLD`. Cost if wrong: one gate's reads per watcher per message, about four queries each thirty seconds.
145. **The heartbeat is the revision again, every thirty seconds** (`rpc/watch.HEARTBEAT_SECONDS`), and the proto says so in a comment (Task 4). Under Envoy's five-minute stream idle timeout and the shorter ones of mobile networks, and the client already compares revisions («compare it, never parse it»), so a repeated one costs it nothing. `changed_at` is when this process last saw the class change, unset when it has seen none since it started — a guess at a time is worse than none. `watch.proto` changes in comments only; `buf generate` changes `watch_connect.py`'s docstrings and `watch_pb.py`'s field comments, and `buf breaking` passes. Cost if wrong: thirty seconds is a number; a client that wants less traffic is a later change of one constant.
146. **A revision is `<boot>.<count>`** (Task 1): `watch.BOOT`, twelve hex characters drawn once per process, and how many committed transactions have changed the class since. A restarted host counts from nothing, and its name keeps its first revision from equalling one a phone kept from before. Opaque to every client. Cost if wrong: none; it is never parsed.
147. **Streaming is `LESSONS_STREAMING=true`, anywhere but Vercel; it does not hang on `LESSONS_TARGET`** (Task 3; decision 7). CI's «Host» job and a local host run start without the marker — with it the settings refusal would reject the SQLite default (decision 13) — and must stream. `Settings.streaming_enabled` is the switch and `app.main` attaches the bus only where it is on; `watch_class` refuses unless the bus is attached, one fact rather than two, and on Vercel whatever is attached. A Connect stream needs no HTTP/2, so a compose deployment may stream too; native gRPC is the server's business, not this flag's. `rpc/watch.py`'s old docstring, which tied the stream to the marker, is rewritten. Cost if wrong: none on Vercel, where it is never on.
148. **The bus hears the whole process's sessions, and publishes only at the outermost commit** (Task 1). It listens on SQLAlchemy's `Session` class: `before_flush` collects, `after_commit` publishes unless `session.in_nested_transaction()`, and `after_transaction_end` forgets at the root transaction's end. SQLAlchemy dispatches `after_commit` for a savepoint's release as well (read in `orm/session.py`, and probed); a release publishing would wake a watcher before the transaction around it is visible, and `homework.create` writes inside exactly such a savepoint. A savepoint rolled back keeps what it collected — a watcher may then be woken for nothing, and fetch a `304` — but the bus never fails to wake one. Single-threaded on the loop that committed (Ruling 153 keeps pyvoy to one thread). Cost if wrong: an extra fetch after a refused nested write.
149. **The window's tables are the design's list and `bot_users`, and not `device_tokens`** (Task 1; «Defects in the design»). Every window carries the caller's `DeviceAccess`, whose role is a `bot_users` row («a role changed in the bot is a new tag», `services/window.strong_etag`), so a role change wakes the class. A phone's own row is left out: every gate writes its `last_seen_at`, which would wake every watcher of the class each quarter hour per phone; a phone's link reaches it at its next sync, and a revocation ends its stream at the next check. Cost if wrong: a newly linked phone shows its editing controls one sync late.
150. **The bulk-touch walk is over all of `app/`, and reads every spelling** (Task 2; «Defects in the design»). The design asks it of `services/`; the bot's «-» on a weekday was a bare `delete(TimetableEntry)` in `bot/handlers/timetable.py`, which no walk of `services/` would see and no watcher would hear. The walk finds sqlalchemy's `update`, `delete` and `insert` under any alias or module name, attributes each to its top-level function or method (a helper defined inside counts as the function's), judges a statement on a model it can name by that model's table and anything else — a loop variable, an attribute — as a window table, and asks that the function call `watch.touch`. The bot's «-» goes through `structure.apply_timetable` with an empty weekday, the service a paste already uses, so the touch is the service's. `touch` is a no-op where streaming is off, and is called after its statement, in the transaction the statement began. Cost if wrong: a function that touches when its statement wrote nothing wakes watchers for a `304`.
151. **Native gRPC is answered at the root on the host, and only native gRPC** (Task 5; «Defects in the design»). A gRPC client calls `/lessons.v2.<Service>/<Method>` and has no notion of a prefix — grpcio's channel takes a host and a port — so a host serving the contract only under `/api/rpc` answers every gRPC client `404`. `app.host.application` hands a request under `/lessons.v2.` that `rpc.is_native_grpc` recognises to the very services `main.mount_v2` mounted (`app.state.v2_services`), and everything else to `app.main`, so Connect keeps one path, `/api/rpc`, on both targets, and connect-kotlin's prefixed gRPC (the app's design) works there too. The root is the host's alone: Vercel refuses native gRPC whatever the path. Cost if wrong: none; a path served twice by one object.
152. **pyvoy's Envoy takes the client from the connection** (Task 5, #395). `app.host.envoy_config` sets `use_remote_address` and `skip_xff_append` on every HTTP connection manager of pyvoy's configuration, and refuses a configuration with none rather than serving it. Without it a request's last `X-Forwarded-For` entry was the client the app saw — the one a caller with no proxy before it writes itself (probed with pyvoy 1.3.0), and every throttle keys on that address when no proxy is trusted. With it the app sees the TCP peer, and the header exactly as it arrived, which `TRUSTED_PROXY_HOPS` reads behind a proxy as under uvicorn (probed: `x-envoy-external-address` is added, and nothing else changes). hypercorn reports the peer already. Cost if wrong: a pyvoy that changes its configuration's shape stops the host at start, loudly.
153. **pyvoy runs one worker thread, requires the lifespan, and compresses with gzip; hypercorn is `--server hypercorn`, never an automatic fallback** (Task 5; decision 13, «HTTP/2, trailers, gzip»). One thread is one event loop, which the engine's pool and the bus both belong to; pyvoy's default for ASGI is one already, and it is written down so that a default changing cannot split them. A required lifespan is where a SQLite file gets its tables. gzip is Envoy's compressor, for the JSON of v1, REST and the docs (probed: `/openapi.json` and `/api/v1/health` came back gzipped under pyvoy, and nothing under hypercorn, which compresses nothing); it leaves gRPC alone, and every Connect answer, which connectrpc marks `identity` once pyvoy has taken the request's `Accept-Encoding` away (probed: a Connect JSON unary came back `identity` under pyvoy, and gzipped by connectrpc itself under hypercorn) — «Not filed», below. A silent fallback to the other server would leave nobody knowing which one runs, so the operator chooses. Cost if wrong: none measured; one constant each.
154. **The launcher reads the settings before any server starts, and never sets the marker** (Task 5; decision 13). `python -m app.host` imports `app.config` alone, calls `get_settings()`, and exits with the refusal's text and status 1 when it refuses, so a misconfigured host says why in its own log rather than from inside Envoy; only then does it import pyvoy or hypercorn. It does not import `app.main`: under pyvoy the app is served by Envoy's own interpreter, and the launcher would otherwise build an engine and start a Sentry of its own. `test_host_image.py` holds that nothing under `app/` writes `LESSONS_TARGET`. Cost if wrong: none.
155. **`LESSONS_TARGET` takes `host` and nothing else, and the refusal applies on both targets** (Task 3; decision 7). `Settings.deployed` is `VERCEL` or any non-empty marker, so that a misspelt marker is named by `deployment_problems` rather than taken for a laptop; the marker beside `VERCEL` is refused too, a deployment being one target. The API docs are off wherever `deployed`. Two sentences of the refusal said something true of Vercel only and are reworded to hold on both, keeping the names the tests read: `DATABASE_URL` («a deployment keeps nothing on a disk of its own»), and `RUN_BOT` («a deployment takes its updates from the webhook»). Like `VERCEL`, the marker is not in `.env.example`, which says why, and not handed over by `docker-compose.yml`, whose image is no host. Cost if wrong: a deployment set by hand with `LESSONS_TARGET=Host ` is read as the host, as intended.
156. **The host's lock is `server/requirements-host.in` → `requirements-host.txt`, compiled from `server/` with `-c ../requirements.txt --universal`** (Task 6; «Defects in the design»). `--python-platform linux` resolves for glibc 2.28, and Envoy's wheel needs 2.31, so the design's command cannot resolve on Vercel's platform tag (probed: «envoy-server==1.39.3 has no wheels with a matching platform tag»); and the host runs on Linux in the image and CI and on the Windows machine where the build console starts it, so the lock is universal, carrying uvloop for the one and winloop for the other. `test_host_image.py` holds every package both locks pin at one version, so a bump of Vercel's lock that moves a shared one (h11, typing-extensions, opentelemetry-api, pyqwest) fails until the host's is regenerated, and the `github-pr` skill says so. Dependabot is not configured for it: its `uv` entry watches the root and its `pip` entry `server/`, whose handling of a uv-compiled file there was not verified («What was verified»). grpcio, the tests' client, is not in it: it is the `host-check` extra of `pyproject.toml`, which only the «Host» job installs. Cost if wrong: a dependabot pull request that touches the host's lock and fails `test_host_image.py`, which names the fix.
157. **The image is the root `Dockerfile`, beside `server/Dockerfile`, which stays compose's** (Task 6; «Defects in the design»). It must install Vercel's lock, which sits at the root, so its context is the root, and `.dockerignore` lets in only the two locks and the server's package. It installs both locks, then the package with `--no-deps` — `pyproject.toml`'s floors would bring uvicorn, aiosqlite and alembic, which the host does not run — sets `LESSONS_TARGET=host`, `HOST=0.0.0.0` and `PORT=8000`, and runs `python -m app.host` as a user of its own. No alembic: migrations are applied from a workstation, as for Vercel. `server/Dockerfile` keeps compose's long polling and its own refusal-free start. Cost if wrong: a platform that builds `server/Dockerfile` by default builds compose's image, which says nothing about being a host.
158. **CI's «Host» job is a matrix of pyvoy and hypercorn, run whenever the server job is** (Task 7; decision 13). On `ubuntu-latest`, it installs the image's two locks and `.[dev,host-check]`, starts `python -m app.host --server <matrix>` in the background on a SQLite file of the runner's, without the marker and with streaming on, a token that is no bot's and a webhook and a cron secret, so that the webhook and the tick are mounted and can be seen refusing a caller without them; waits for `/api/v1/health`; runs `pytest -q -p no:xdist -m host`; and prints the host's log whatever happened. `fail-fast: false`, because one server failing says nothing about the other. `test_host_job.py` holds the job to the tests it runs. Cost if wrong: two jobs of about a minute each per push, on runners a public repository does not pay for.
159. **The marker `host` is registered in `pyproject.toml`, and `conftest.py` skips it unless `LESSONS_HOST_URL` is set** (Task 7; decision 14). Skipped rather than deselected, so the ordinary run's summary says six tests exist and why they did not run; one switch, the address the tests need anyway. The tests import grpcio inside a fixture, so a run without the extra collects them. Cost if wrong: the summary line carries «6 skipped».
160. **The live tests write their class into the host's database directly, and every change after that goes through the host** (Task 7). Only the bot can link a phone, so the class, its members and their phones are rows written over `LESSONS_HOST_DATABASE_URL`, a fresh class per test; a change made in the test's own process would wake nobody, which is the bus's point. The ORM write is `CreateHomework`; the bulk write `ImportTimetable` of a weekday named with nothing under it, one bulk delete and nothing through the unit of work; the bell change `UpdateBellSchedule`'s periods, a bulk delete and new periods found through their schedule. The forged `X-Forwarded-For` test spends the job's address's join budget for fifteen minutes and is the file's last. Cost if wrong: none outside CI.
161. **The suite runs with streaming off and as no deployment, whatever the shell holds** (Task 3). `conftest.py` sets `LESSONS_STREAMING=false` and removes `LESSONS_TARGET` before anything imports: the build console runs the host and the suite on one machine, an exported `LESSONS_STREAMING=true` would attach the bus in the suite and every `WatchClass` the gate test opens through httpx's ASGI transport — which returns only once an answer ends — would wait for ever, and a marker would make the suite a deployment that `get_settings` refuses on the first import. A test that wants the bus attaches it and detaches it. Cost if wrong: none.
162. **No revision, no new reason, no effect, and no handler commits** (all tasks). `WatchClass`'s refusals are the gate's and 3a's `FEATURE_UNSUPPORTED`; `test_rpc_errors.py`'s `HELD_BY`, `LATER` and `STAGES` do not move, and `test_rpc_call.py`'s grep for `.commit(` outside `call.py` still holds, `stream`'s commit being `call.py`'s. mypy grows by `app/watch.py` and `app/host.py`.
163. **The build console has not merged, so its task table gains no row here** (decision 13). `tools/console/` is not in the tree; the console's design gives the host job its row in its stage 6c, «started as CI's host job starts it (without the deployment marker, against SQLite)», which is what this job does.
164. **Process, as Rulings 123 and 142 have it.** Each task's gate is its named test files with `-p no:xdist`, then ruff and mypy; the full suite runs once, alone, with `-n 4`, at the end of Task 7, the last code task, and the controller runs it. Commit messages carry no trailer line. Task 7 also starts the host on this machine, under pyvoy and under hypercorn, and runs the live tests against it, which no CI run can replace for an implementer before the push.

## Global Constraints

Copied from the design and the 3b plan where they still bind, and grown by what 3c adds.

- **Paths.** The bus is `server/app/watch.py`, neutral, so `services/` may import it; the host is `server/app/host.py`; the stream's handler stays `rpc/watch.py`, registered in `rpc/handlers.STREAMS`. Generated code stays under `server/app/contract/`, and only `buf generate` writes it. The host's image is the root `Dockerfile`; its lock is `server/requirements-host.txt`, and only `uv pip compile` writes it.
- **Import rules.** `rpc/` and `rest/` may import what the 3b plan lists, and `app.watch`; never `app.bot` or a v1 router. No `app.services` module reaches `app.rpc`, `app.rest` or `app.api`; `app.watch` imports `app.models` and SQLAlchemy and nothing of a shell. **Nothing on the API's path imports `app.host`, pyvoy or hypercorn** — Vercel's lock has neither — and `test_cold_start.py` asks a fresh interpreter. `tests/test_service_layering.py` holds the rest.
- **Codes to HTTP** are `docs/api.md`'s table, unchanged; over native gRPC a refusal is the same `google.rpc.Status`, in `grpc-status`, `grpc-message` and `grpc-status-details-bin`.
- **The commit.** A handler never commits; only `rpc/call.py` does — `invoke`'s one commit, and each of `stream`'s gate scopes. **A stream holds no session between messages.**
- **Every bulk statement on a window table touches the bus** in the same function: `watch.touch(session, class_id)`, after the statement. `tests/test_watch.py` walks all of `app/`.
- **Refusals.** No exception's own text reaches a client; no refusal repeats what was sent; a sentence v1 and v2 both say lives once, in `app/wording.py`. 3c adds no sentence a client reads but the gate's and 3a's.
- **Reads write nothing.** `WatchClass` writes what the gate writes, `last_seen_at` with the client version beside it on its fifteen-minute clock, and nothing else.
- **Deployments say what they are.** `VERCEL` is Vercel's, `LESSONS_TARGET=host` the root image's; nothing in `app/` sets either, and no laptop does. `LESSONS_STREAMING` is optional, and never on Vercel. **Do not add an optional setting to the refusal's list** (`CLAUDE.md`).
- **Versions are copied from what installs, never guessed.** The host's lock is compiled, never edited, and agrees with Vercel's on every package both pin.
- **Language.** English in code, comments, commit messages and documents. Russian only as product text, and in «guillemets» anywhere else. Comments say why.
- **Commit messages** are English sentences saying what the change makes the project do, with no prefix; the body gives the reasoning and what is left uncovered; no trailer lines (Ruling 102).
- **This machine's RAM is faulty.** One heavy job at a time; the full suite once, at its gate; a host started for a step is stopped in that step; after a crash, scan for zero-filled files before trusting the tree.
- **Milestone 11**, `v0.10.0 — One contract: REST v2, Connect and native gRPC, build console`, under epic #273. Another agent may work in this tree: run `git status` before touching a file you did not open.
- **A defect found during the work gets an issue before its fix**, with `type:bug`, its `area:`, a `status:`, milestone 11 and an item on project 6, as the `github-pr` skill says; the pull request then says `Closes #NN`.
- **Plans under `docs/specs/` are read by the head test.** No text in this plan contains «head is», optionally followed by asterisks, and then a backticked four-digit revision; nor «expects» and then a backticked revision; nor a line holding `/warmup`'s quoted `"status"`/`"ok"` and its `"schema"`. Task 8 Step 5 scans for all three.

## Review Focus (3c)

The five inputs most likely to bite a person using 3c that the generic tests do not reach, each with the tests that pin it and the task that owns them.

1. **A forged `X-Forwarded-For` against the host.** Under pyvoy's own configuration its last entry was the client, so a caller who wrote a new one each time would never meet a throttle (#395): `test_envoy_takes_the_client_from_the_connection_on_every_listener` and `test_a_configuration_with_nothing_to_correct_is_refused_rather_than_served` (Task 5), and against a running host under both servers `test_a_forged_forwarded_for_buys_no_fresh_join_budget` (Task 7).
2. **A change written inside a savepoint, from any shell.** SQLAlchemy fires `after_commit` at a savepoint's release, and `homework.create` writes in one: `test_a_savepoint_released_publishes_nothing_until_the_commit` (Task 1) and `test_a_change_any_shell_makes_wakes_the_watcher_with_a_new_revision[v1|v2|bot]` (Task 4).
3. **A bulk write nobody told the bus about** — the bot's «-» on a weekday, a future service: `test_every_bulk_write_on_a_window_table_touches_the_bus`, `test_the_walk_sees_an_untouched_bulk_write_however_it_is_spelled` and `test_a_write_made_of_bulk_statements_alone_wakes_the_class` (Task 2), and against a running host `test_watch_class_hears_an_orm_write_a_bulk_write_and_a_bell_change` (Task 7).
4. **A phone revoked, a class deleted, or a phone gone while it watches**: `test_a_device_revoked_while_it_watches_is_refused_at_the_next_heartbeat`, `test_a_class_deleted_ends_its_streams_at_once`, `test_a_watcher_that_goes_away_is_forgotten` and `test_a_watcher_holds_no_connection_between_its_messages` (Task 4), and `test_a_phone_revoked_while_it_watches_is_cut_off` (Task 7).
5. **A switch set where it should not be**: a shell that exported `LESSONS_STREAMING` beside the suite, a misspelt `LESSONS_TARGET`, streaming asked of Vercel: `test_without_the_listener_nothing_is_collected_or_published` (Task 1, with `conftest.py`'s Task 3 lines), `test_a_marker_that_names_no_target_is_refused_rather_than_ignored`, `test_vercel_never_streams_whatever_it_is_told` and `test_the_bus_listens_where_streaming_is_on_and_nowhere_else` (Task 3), and `test_where_streaming_is_off_the_gate_runs_and_the_feature_is_refused[on-vercel]` (Task 4).

## Defects in the design, and how this plan resolves them

- **«A test finds every `sa_update(`/`sa_delete(` on a window table under `services/`»**: `bot/handlers/timetable.py` cleared a weekday with a bare `delete(TimetableEntry)` of its own, outside `services/`, and the bus would never have heard it. The walk covers all of `app/`, every spelling and `insert`; the handler goes through `structure.apply_timetable` (Ruling 150).
- **«An allowlist of the tables that change the window … and the class itself»**: every window carries the caller's `DeviceAccess`, and a role is a `bot_users` row; `bot_users` is added. `device_tokens`, which holds the link, is not, with the reason (Ruling 149).
- **«The collected set is published after the commit»**: SQLAlchemy calls a savepoint's release a commit too. Only the outermost commit publishes (Ruling 148).
- **«A native gRPC client then makes a unary call»**, to an app that serves the contract under `/api/rpc` alone: a gRPC client knows no prefix, and grpcio got `404` there (probed). The host answers native gRPC at the root as well (Ruling 151).
- **«`python -m app.host` serves the same app under `pyvoy`»**: under pyvoy's own Envoy configuration the client is whatever `X-Forwarded-For` says (#395). Corrected in `app.host` (Ruling 152).
- **«…an `UNIMPLEMENTED` call»**: once 3b-8 has merged every method of the contract has a handler, so the call is to a method the contract does not have, which connectrpc answers `404` and every gRPC client reads as `UNIMPLEMENTED` (the gRPC spec's mapping of HTTP statuses; probed with grpcio).
- **«`server/requirements-host.in` is compiled with `-c requirements.txt`»**, by implication for Vercel's platform: Envoy's wheel needs glibc 2.31, which `--python-platform linux` does not promise, and the host runs on Windows too. Compiled `--universal`, from `server/` (Ruling 156).
- **«The `Dockerfile` sets it»**: there is a `server/Dockerfile` already, compose's, whose context cannot reach Vercel's lock. The host's image is a second file, at the root (Ruling 157).
- **`rpc/watch.py`'s docstring, «read from its deployment marker (`LESSONS_TARGET=host`) with `LESSONS_STREAMING=true`»**, which 3a wrote: the CI job and a local run stream without the marker (decision 13's own words). Streaming is `LESSONS_STREAMING` anywhere but Vercel (Ruling 147).
- **The design is silent on** what a heartbeat sends (Ruling 145), the revision's shape (146), how a stream is registered and served (143), how many threads pyvoy runs and whether it requires the lifespan (153), where grpcio is pinned (156), how the live tests get a linked phone (160), and the suite's own environment (161).

## Defects found while writing this plan

The controller files each, with `type:bug`, its `area:` labels, a `status:`, milestone 11, where its fix lands, and an item on project 6, as the `github-pr` skill says, and writes its number into this plan in place of the placeholder.

**`#395`.** Title: «Under pyvoy's own Envoy configuration the host takes a client's address from the X-Forwarded-For it sends». Labels `type:bug`, `area:server`, `status:now`; milestone 11. Where: the host target as decision 13 of the server-v2 design describes it, `python -m app.host` serving the app under pyvoy; pyvoy 1.3.0's `PyvoyServer.get_envoy_config` writes an HTTP connection manager without `use_remote_address`. The fix lands in 3c's Task 5. Body, written to `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\issue-3c-f1.md`:
```markdown
The host target of the server-v2 design (decision 13) serves the app under pyvoy, which runs it
inside Envoy. pyvoy 1.3.0 configures Envoy's HTTP connection manager without
`use_remote_address`, and then Envoy hands the app the last `X-Forwarded-For` entry of the
request as the client's address — whatever the caller wrote there — in the ASGI scope's
`client`. Every throttle keys on that address when no proxy is trusted
(`server/app/api/deps.py`, `caller_bucket`: `TRUSTED_PROXY_HOPS=0` reads the peer and nothing
else): the join limiter (thirty wrong codes in fifteen minutes), the diary's two sign-in
limiters and the anonymous school directory's.

**Failure scenario:** the host is deployed as decision 13 has it and a caller guesses class
codes against `POST /api/v1/join` or `DeviceService/CreateDevice`, writing a new
`X-Forwarded-For` on every request. Each request lands in a bucket of its own; the limiter
never refuses; the codes can be walked at the server's speed. The same goes for the diary's
sign-in attempts against the upstream from the host's address, which the limiters exist to
stop. Probed on 10 October 2026 with pyvoy 1.3.0 on Windows: a request carrying
`X-Forwarded-For: 198.51.100.7` reached the app with `scope["client"] == ("198.51.100.7", 0)`,
and one carrying `198.51.100.7, 203.0.113.8` as `203.0.113.8`.
hypercorn, the design's fallback, reports the TCP peer and is not affected.

Fixed in stage 3c of sub-project 3 (#273), before the host is first served: `app/host.py`
sets `use_remote_address` and `skip_xff_append` on every connection manager of pyvoy's
configuration, and refuses to start on a configuration that has none; the app then sees the
connection's peer as the client, and the header exactly as it arrived, which
`TRUSTED_PROXY_HOPS` reads behind a proxy as it does under uvicorn. CI's «Host» job sends
thirty wrong codes from thirty forged addresses and expects the next to be refused.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230 && gh issue create --repo lumenpearson/lessons --title "Under pyvoy's own Envoy configuration the host takes a client's address from the X-Forwarded-For it sends" --body-file C:/Users/lumen/.claude/jobs/c9e2d980/tmp/issue-3c-f1.md --label type:bug --label area:server --label status:now --milestone "v0.10.0 — One contract: REST v2, Connect and native gRPC, build console"
```

**Not filed, and said here so that they are not lost:**
- `server/Dockerfile`, compose's image, installs the package with `pip install .`, so a compose deployment built today gets whatever `pyproject.toml`'s floors resolve to that day, not `requirements.txt`'s pins that CI tested — the shape of #164, on the one image nothing builds. Its context is `server/`, which cannot reach the root lock; the host's image shows the way (Ruling 157). Left as it is: compose is configured by hand and outside this stage.
- pyvoy's gzip filter removes `Accept-Encoding` from every request before the app sees it, so connectrpc never compresses a Connect answer itself and marks it `content-encoding: identity`, which Envoy's compressor then leaves alone. Every Connect answer leaves the host under pyvoy uncompressed, JSON and binary alike, where under hypercorn connectrpc gzips it: probed with `curl -H 'Accept-Encoding: gzip'` against `GetDiaryCapabilities`, `identity` under pyvoy and `gzip` under hypercorn. v1's and REST's JSON is gzipped by Envoy. A size, not a fault; whether to let `Accept-Encoding` through to the app is a question for pyvoy's configuration, left for when a phone speaks Connect to a host.

## Where 3b-8 moves an anchor

- `server/app/rpc/handlers.py`: 3b-8's Tasks 3 and 4 add four `DiaryService` lines to `HANDLERS` and rewrite the module docstring. Task 4's anchors here are the `typing` import, `HANDLERS`' last two lines (`ImportTimetable`, then `WatchClass`), which 3b-8 leaves, and the docstring's sentence naming `WatchClass`'s refusal: read it as 3b-8 leaves it, and wherever it says 3a served `WatchClass` here, say instead that `WatchClass`, the one stream, is in `STREAMS`, which `call.stream` serves (3c).
- `docs/api.md`, `README.md`, `docs/README.md`, `docs/architecture.md`, `CLAUDE.md`, `CONTRIBUTING.md`, the `gates` skill and the `server-tests` agent: 3b-8's Task 5 rewrites the count of methods, the opening of «v2: the contract», the v2 row of «Honest status», the `services/` bullet and the counts. Task 8's anchors are other lines of the same files: the transports' bullets and the paragraph after them in `docs/api.md`, the «Monitoring» row and the compose note in `README.md`, the `deploy.md` row of the index, the tree and a new section in `docs/architecture.md`, and the tree, the requirements paragraph, the commands, the CI paragraph, the `rpc/` bullet, the modules after `observability.py`, the refusal bullet and a new «What will bite you» bullet in `CLAUDE.md`. After 3b-8 the opening of «v2: the contract» says that `WatchClass`, the one stream, «answers as the next section says»; Task 8's rewrite of that next section is what it then says.
- `tests/test_rpc_mount.py`, `tests/test_v2_reads.py`: 3b-8 adds nothing to either at the lines Tasks 4 and 5 edit.
- **Checked, not assumed:** every step of Tasks 1 to 8, applied in order to `1c0a362`'s committed tree, found its anchor exactly once, `handlers.py`'s docstring as Task 4 Step 3 gives it included; «What was verified» says what then ran there. 3b-8's documents were not yet written, so Task 8's anchors were found in the documents as 3b-8's Tasks 1 to 4 left them; the lines Task 8 edits are not among those its Task 5 names.

## What was verified while writing this plan, and what was not

**Read, at `a434c9c`'s code:** the design whole; the programme's «What the spike found» and section 2; 3a's plan's header and Rulings; the 3b plan's header, 3b-7's task list and the 3b-8 list's header, rulings, file map and Task 5; the build console's design (decisions 1 and 6c); `CLAUDE.md` whole; `server/app/main.py`, `db.py`, `di.py`, `config.py`, `models.py`' window tables, `api/deps.py`'s bearer, `find_device`, `touch_last_seen`, `caller_bucket` and `peer_host`; `rpc/` (`__init__.py`, `call.py`, `gate.py`, `methods.py`, `handlers.py`, `errors.py`' `Refusal`, `values.py`, `watch.py`, `schedule.py`, `timetable.py`, `school_class.py`' delete); `services/window.py`' tag, `homework.py`'s create and update, `structure.py`, `subjects.py`' relinking, `terms.ensure`, `timetable_edit.py`'s shifts, `calendar.py`, `manage/timetable.py`'s import; every `sa_update(`, `sa_delete(`, `delete(` and `insert(` under `app/`; `bot/handlers/timetable.py`'s «-»; `api/cron.py`'s and `api/telegram.py`'s refusals; `proto/lessons/v2/watch.proto`, `schedule.proto`, `me.proto`, `bell.proto`, `homework.proto`, `timetable.proto`, `class_device.proto`, `school_class.proto`; `buf.gen.yaml`, `buf.yaml`, `requirements.in` and the lock's header, `server/pyproject.toml`, `server/Dockerfile`, `docker-compose.yml`, `.github/workflows/ci.yml`, `.github/dependabot.yml`; and the tests that read any of them: `conftest.py` whole, `test_rpc_mount.py`, `test_v2_reads.py`, `test_rpc_call.py`, `test_service_layering.py`, `test_cold_start.py`, `test_deployment_config.py`, `test_api_docs.py`, `test_env_example.py`, `test_compose.py`, `test_ci_paths.py`, `test_requirements_mirror.py`, `test_bot_handlers.py`' «-». The documents Task 8 edits were read at the lines it quotes, `HANDOVER.md` at `a434c9c`, and the `handover` skill whole; at 3b-8's `1c0a362`, `rpc/handlers.py`; and connectrpc 0.12.1's server-stream loop (`_server_async.py`), which closes the response stream when its client has gone and turns a cancellation into `CANCELED`. `tools/console/` does not exist.

**Probed**, with a venv of the copy's own (below):
- **pyvoy 1.3.0 on Windows** ran an echo app and the real app: it imports the app inside Envoy with `PYTHONPATH` and `PYTHONHOME` from the launcher, runs the lifespan (the SQLite file got its tables), started in 4.1 s, and serves HTTP/1.1 and HTTP/2 with prior knowledge on one port. Its configuration (`--print-envoy-config`) has no route, so Envoy's fifteen-second route timeout does not apply; a response written every twenty seconds lived eighty. `worker_threads` defaults to one for ASGI.
- **#395**: with pyvoy's own configuration, `X-Forwarded-For: 198.51.100.7` reached the app as `scope["client"] == ["198.51.100.7", 0]`, and `198.51.100.7, 203.0.113.8` as `203.0.113.8`, the last entry; with `use_remote_address` and `skip_xff_append`, the client was `127.0.0.1` and the header arrived as sent, beside an added `x-envoy-external-address`.
- **grpcio 1.84.0**, against the real app under pyvoy and under hypercorn 0.18.0: a unary `GetDiaryCapabilities` at the root answered; the same method under `/api/rpc` only — before the root existed — was `UNIMPLEMENTED` «Received http2 header with status: 404»; a method the contract does not have, and a service, were `UNIMPLEMENTED` the same way; an anonymous `WatchClass` was `UNAUTHENTICATED`, with `grpc-status-details-bin` holding the `google.rpc.Status` and its `ErrorInfo` (`DEVICE_TOKEN_INVALID`, `lessons.app`).
- **SQLAlchemy 2.1.2** dispatches `after_commit` for a savepoint's release (`orm/session.py`, `SessionTransaction.commit`: `if self._parent is None or self.nested`), and `Session.in_nested_transaction()` is true at that dispatch and false at the outermost one; `test_a_savepoint_released_publishes_nothing_until_the_commit` fails with the guard taken out.
- **uv 0.12.3**: `uv pip compile requirements-host.in -c ../requirements.txt --python-version 3.12 --python-platform linux` fails («envoy-server==1.39.3 has no wheels with a matching platform tag (e.g., `manylinux_2_28_x86_64`)»); with `--python-platform x86_64-manylinux_2_31` and with `--universal` it resolves, the second carrying uvloop and winloop under markers. Every package the two locks share is pinned alike: h11 0.16.0, opentelemetry-api 1.45.0, pyqwest 0.11.0, typing-extensions 4.16.0.
- **PyPI**, 10 October 2026: pyvoy's newest is 1.3.0 (published 9 October; 1.2.0, the spike's, needs `envoy-server==1.39.1`), with wheels for Linux (manylinux 2.28 and musl), macOS and Windows; `envoy-server` 1.39.3 has wheels for manylinux 2.31 (x86-64 and aarch64), macOS arm64 and Windows; hypercorn's newest is 0.18.0; grpcio's 1.84.0, with a CPython 3.12 Windows wheel.

**Installed in the copy's venv** (`scratchpad\v3c`, made with uv on CPython 3.12.13 from `requirements.txt` and `-e ".[dev]"` of the copy, so the #312 guard took it; run through the scratchpad's 8.3 short path, because Windows would not load SQLAlchemy's compiled modules at the long one), from PyPI, at the versions the host's lock pins, with Vercel's lock as a constraint: pyvoy 1.3.0 (1,439,632 bytes), envoy-server 1.39.3 (20,689,749), winloop 0.7.1 (695,292), hypercorn 0.18.0 (61,640), h2 4.4.1 (62,636), hpack 4.2.0 (34,246), hyperframe 6.1.0 (13,007), priority 2.0.0 (8,946), wsproto 1.3.2 (24,405) and find-libpython 0.5.1 (9,201); and grpcio 1.84.0 (5,253,534). 28.3 MB in all; pyyaml was there already. A second venv (`scratchpad\v3b8`), for the tree of 3b-8's head below, took the same versions of everything from uv's cache, with nothing downloaded. Nothing was installed anywhere else.

**Applied and run, in a scratch copy** (`git archive` of `a434c9c` without `android/`, made a git repository of its own; never the worktree):
- **How the code got in.** The code was written in the copy, one commit per task, and every step of this plan is rendered from those commits by a script: a file a step writes whole is the commit's file, and every «replace» is cut from the commit's diff, its anchor grown until it is found exactly once in the file as it stands at that step and holds two lines with a letter in them; Task 8's document steps are the ones written by hand, against lines 3b-8 does not rewrite. The same script then applied the rendered steps, in order, to a branch at the base, with the generated contract taken from each commit as `buf generate` would write it, and after every task `git diff --cached --stat` against that task's commit printed nothing.
- **Red and green**, each the step's own command with `-p no:xdist` on the copy's venv, one at a time: Task 1, the collection error naming `watch`, then `23 passed`; Task 2, `6 failed, 24 passed`, then `427 passed` in 155 s; Task 3, `13 failed, 55 passed`, then `102 passed`; Task 4, the collection error naming `STREAMS`, then `421 passed` in 175 s, and `test_v2_watch.py` alone `13 passed` in 14 s, the slowest test 2.0 s; Task 5, the collection error naming `host`, then `42 passed`; Task 6, `10 failed, 1 passed`, then `24 passed`; Task 7, `5 failed, 6 errors`, then `5 passed, 6 skipped`. `test_v2_watch.py` was rewritten once on the way: its first form waited fractions of the heartbeat for a message, which a loaded machine could miss, and it now waits five seconds for what it is owed and puts the heartbeat out of reach where it waits for none.
- **After each task**, `ruff check app tests scripts migrations` printed `All checks passed!`; every new file is as `ruff format` writes it, and no modified file gained a difference from `ruff format` it did not have before (checked file by file against each task's parent); `pytest --collect-only -q` counted 3094 at the base, then 3117, 3124, 3139, 3152, 3161, 3169 and 3180; `mypy` said `no issues found` in 238 source files at the base, 239 after Tasks 1 and 4, and 240 after Tasks 5 and 8 — with pyvoy and hypercorn installed; `pyproject.toml` sets `ignore_missing_imports`, so the worktree's venv, which has neither before Task 6, counts the same.
- **The contract**: after Task 4, `buf lint` printed nothing, `buf generate` changed `watch_connect.py` and `watch_pb.py` alone, in comments, and `buf breaking` against the copy's own commit of the base printed nothing.
- **The host by hand**, after Task 7, on this machine, as Task 7 Step 5 has it: under pyvoy `/api/v1/health` answered two seconds after the launch, and `pytest -m host` gave `6 passed, 3174 deselected in 7.09s`; under hypercorn, two seconds and `6 passed, 3174 deselected in 7.10s`. Asked with `Accept-Encoding: gzip`, pyvoy gzipped `/openapi.json` and `/api/v1/health` and answered a Connect JSON `GetDiaryCapabilities` `content-encoding: identity`; hypercorn gzipped that one alone, by connectrpc's own hand (Ruling 153). After each server the launcher's process tree and Envoy were stopped with `taskkill`, as the step says.
- **On 3b-8's head.** `1c0a362`'s committed tree, exported from the worktree without `android/` and made a repository of its own, took every step of Tasks 1 to 8 in order — Task 4 Step 3's docstring as it stands there — and every anchor was found exactly once; the generated `watch_*` files came from the copy's Task 4. There, with the venv beside it: `pytest --collect-only -q` counted 3140 for 3b-8 alone, exactly 3094 and the 46 its list promises, and 3226 with 3c, which is 86 more; every test file of `a434c9c` that this plan's commands name collects as many tests there as at `a434c9c`, but `test_v2_reads.py` and `test_v2_no_echo.py`, four more each (hence Task 4 Step 6's `429`); `ruff check` printed `All checks passed!`; `mypy` said 238 source files for 3b-8 alone and 240 with 3c; `buf lint` printed nothing, `buf generate` changed nothing that was committed, and `buf breaking` against 3b-8 alone printed nothing; and every test file this plan's commands name, with 3b-8's own four diary files beside them, ran together: `1147 passed, 6 skipped` in 7 min 47 s, the six being `test_host_live.py`'s. This check is also what found Task 3's `docker-compose.yml` edit missing from the copy's commit — `test_compose.py` failed there — so it was put back, every later commit rebuilt, and Task 3's run repeated: `102 passed`. The runs of Tasks 4 to 8 above were made before it went back; none of their files reads `docker-compose.yml`.
- **Task 8**: `docs3c.py` printed twenty-two `missing` lines and two `still says` lines before Steps 2 to 4, and `the documents say what 3c serves` after them; Step 5's eight files gave `210 passed, 1 skipped`, the skip being `test_host_job.py`'s own (`-rs`: «test_host_live.py is not part of this run»).

**Not run:**
- the full suite, in the copy or the worktree;
- **the «Host» job itself**: it needs a Linux runner; its steps were run by hand on Windows instead, and `test_host_job.py` holds the workflow to them;
- **the image**: no Docker here, so the `Dockerfile` has never been built, nor the image run; `test_host_image.py` reads it;
- the host's lock on Linux: it resolves for Linux, and only Windows installed it here;
- whether dependabot's `pip` entry for `server/` touches `requirements-host.txt`;
- `buf breaking` against `origin/main`, which the copy has not: it ran against the copy's own commit of the same tree;
- anything on Postgres, on Vercel, behind a TLS proxy, or from a phone;
- 3b-8's review fixes and its documents beneath this plan's: they were uncommitted or unwritten at `1c0a362`, and «Where 3b-8 moves an anchor» says where the two meet;
- Task 8's HANDOVER edits, which wait for facts that exist only at the merge, and the head scan of Step 5, which reads the worktree.

The run at each task's gate is the truth.

## File map (3c)

| File | Task | What it holds |
| --- | --- | --- |
| `server/app/watch.py` | 1 | new: `WINDOW_TABLES`, `BOOT`, `listening`, `changes`, `revision`, `changed_at`, `watchers`, `touch`, `watching`, `publish`, `attach`, `detach`, the three listeners |
| `server/app/services/structure.py`, `subjects.py`, `terms.py`, `timetable_edit.py`, `calendar.py` | 2 | `watch.touch` after every bulk statement on a window table |
| `server/app/bot/handlers/timetable.py` | 2 | «-» on a weekday through `structure.apply_timetable` |
| `server/app/config.py` | 3 | `lessons_target`, `lessons_streaming`, `behind_host`, `deployed`, `streaming_enabled`; the marker's two problems; two sentences reworded; `get_settings` on `deployed` |
| `server/app/main.py` | 3, 5 | the bus attached where streaming is on; the docs off wherever `deployed`; `app.state.v2_services` |
| `server/.env.example`, `docker-compose.yml` | 3 | `LESSONS_STREAMING` shown and handed over; `LESSONS_TARGET` explained, not listed |
| `server/app/rpc/call.py` | 4 | `Stream`, `_device_and_class`, `_admitted`, `stream` |
| `server/app/rpc/handlers.py` | 4 | `STREAMS`, `StreamHandler`; `WatchClass` leaves `HANDLERS` |
| `server/app/rpc/__init__.py` | 4, 5 | the adapter hands connectrpc `stream(...)`; `is_native_grpc` public |
| `server/app/rpc/watch.py` | 4 | rewritten: `HEARTBEAT_SECONDS`, `watch_class`, `_now` |
| `proto/lessons/v2/watch.proto`, `server/app/contract/**` | 4 | comments, regenerated |
| `server/app/host.py` | 5 | new: `application`, `envoy_config`, `_serve_pyvoy`, `_serve_hypercorn`, `_run`, `main` |
| `server/requirements-host.in`, `server/requirements-host.txt` | 6 | new: the host's lock |
| `Dockerfile`, `.dockerignore` | 6 | new: the host's image and its context |
| `server/pyproject.toml` | 6, 7 | the `host-check` extra; the `host` marker |
| `.github/workflows/ci.yml` | 6, 7 | the root's two files in the server's paths; the «Host» job |
| `server/tests/test_watch.py` | 1, 2 | the bus; the walk; the bulk-only writes |
| `server/tests/test_deployment_config.py`, `test_api_docs.py`, `test_cold_start.py`, `test_env_example.py`, `test_compose.py`, `conftest.py` | 3 | the marker, the switch, the docs, the bus at import, the example, compose, the suite's environment |
| `server/tests/test_v2_watch.py`, `test_v2_reads.py` | 4 | the stream; the gate test over `STREAMS` |
| `server/tests/test_host.py`, `test_cold_start.py`, `test_rpc_mount.py` | 5 | the host's root, Envoy and launcher; nothing of the host on the API's path; `v2_services` |
| `server/tests/test_host_image.py`, `test_ci_paths.py` | 6 | the lock and the image; the root files the suite reads |
| `server/tests/test_host_live.py`, `test_host_job.py`, `conftest.py` | 7 | the tests a running host is asked; the job held to them; the marker's skip |
| documents | 8 | `docs/api.md`, `docs/deploy.md`, `docs/architecture.md`, `docs/build.md`, `docs/README.md`, `README.md`, `CLAUDE.md`, `CONTRIBUTING.md`, the `gates` and `github-pr` skills, the `build-ci`, `deploy-ops` and `server-tests` agents, the counts in the seven places; `HANDOVER.md` and `docs/history.md` |

---

### 3c Task 1: The class-changed bus, `app/watch.py`

Decision 13 («the bus»); Rulings 146, 148 and 149. Nothing attaches the bus yet: Task 3 makes `app.main` do it where streaming is on, and every test here attaches it for itself.

**Files:**
- Create: `server/app/watch.py`, `server/tests/test_watch.py`

**Interfaces:**
- Consumes: `app.models.SchoolClass`, `BellSchedule`, `BellPeriod` and every model's `__tablename__` and `class_id`; SQLAlchemy's `Session` events `before_flush`, `after_commit` and `after_transaction_end`, and `Session.in_nested_transaction()`.
- Produces:
  - `watch.WINDOW_TABLES: frozenset[str]` — `classes`, `bot_users`, `bell_schedules`, `bell_periods`, `subjects`, `timetable_entries`, `terms`, `day_overrides`, `lesson_overrides`, `day_events`, `homework`;
  - `watch.BOOT: str`; `listening() -> bool`; `changes(class_id: int) -> int`; `revision(class_id: int) -> str`; `changed_at(class_id: int) -> datetime | None`; `watchers(class_id: int) -> int`;
  - `touch(session: AsyncSession | Session, class_id: int) -> None`; `watching(class_id: int) -> ContextManager[asyncio.Event]`; `publish(class_ids: Iterable[int]) -> None`; `attach() -> None`; `detach() -> None`.

- [ ] **Step 1: Red.** The bus's tests:

Write `server/tests/test_watch.py` whole:
```python
"""The class-changed bus (``app/watch.py``): what wakes a class's watchers, and when.

Asked of real sessions over the suite's SQLite, with the listener attached for
the test alone: the suite runs with streaming off, as every deployment but a
streaming host does (``docs/specs/2026-10-05-server-v2-design.md``,
decision 13). A test reads ``changes`` before and after, never a revision's
text: a revision is opaque to every client, and to these tests too.
"""

from __future__ import annotations

from datetime import date, time
from typing import Any

import pytest
from sqlalchemy import select
from sqlalchemy import update as sa_update

from app import watch
from app.db import SessionLocal
from app.models import (
    AuditEntry,
    BellPeriod,
    BellSchedule,
    BotUser,
    DayEvent,
    DayKind,
    DayOverride,
    EventKind,
    Homework,
    LessonOverride,
    OverrideAction,
    PersonalTask,
    ReminderSettings,
    Role,
    SchoolClass,
    Subject,
    Term,
    TermKind,
    TimetableEntry,
    WeekParity,
)


@pytest.fixture
def bus():
    """The listener, attached for one test."""
    already = watch.listening()
    watch.attach()
    yield watch
    if not already:
        watch.detach()


def _homework(class_id: int, text: str = "№ 1–5") -> Homework:
    return Homework(
        class_id=class_id, due_date=date(2026, 9, 15), subject_name="Алгебра", text=text
    )


def _row(table: str, class_id: int) -> Any:
    """A new row of ``table`` in ``class_id``, as little as the table needs."""
    return {
        "bot_users": lambda: BotUser(telegram_id=3001, class_id=class_id, role=Role.EDITOR),
        "bell_schedules": lambda: BellSchedule(class_id=class_id, name="Сокращённое"),
        "subjects": lambda: Subject(class_id=class_id, name="Химия"),
        "timetable_entries": lambda: TimetableEntry(
            class_id=class_id, weekday=2, index=1, subject_name="Химия", parity=WeekParity.ANY
        ),
        "terms": lambda: Term(
            class_id=class_id,
            year=2026,
            kind=TermKind.QUARTER,
            index=1,
            starts_on=date(2026, 9, 1),
            ends_on=date(2026, 10, 26),
        ),
        "day_overrides": lambda: DayOverride(
            class_id=class_id, date=date(2026, 11, 4), kind=DayKind.HOLIDAY
        ),
        "lesson_overrides": lambda: LessonOverride(
            class_id=class_id, date=date(2026, 9, 14), index=1, action=OverrideAction.CANCEL
        ),
        "day_events": lambda: DayEvent(
            class_id=class_id,
            date=date(2026, 9, 15),
            starts_at=time(15),
            ends_at=time(16),
            title="Родительское собрание",
            kind=EventKind.MEETING,
        ),
        "homework": lambda: _homework(class_id),
    }[table]()


#: The window tables a plain row of which is a row of its class; the class
#: itself and a bell period are asked on their own below.
ROWS = sorted(watch.WINDOW_TABLES - {"classes", "bell_periods"})


def test_every_window_table_is_asked_here() -> None:
    assert {*ROWS, "classes", "bell_periods"} == watch.WINDOW_TABLES


@pytest.mark.parametrize("table", ROWS)
async def test_a_committed_insert_into_a_window_table_wakes_its_class(
    bus, session, school_class, table
) -> None:
    start = bus.changes(school_class.id)
    session.add(_row(table, school_class.id))
    await session.commit()
    assert bus.changes(school_class.id) == start + 1


async def test_an_update_and_a_delete_wake_it_and_a_write_that_changes_nothing_does_not(
    bus, session, school_class
) -> None:
    entry = await session.scalar(
        select(TimetableEntry).where(
            TimetableEntry.class_id == school_class.id, TimetableEntry.index == 1
        )
    )
    start = bus.changes(school_class.id)
    entry.room = "101"
    await session.commit()
    entry.room = "101"
    await session.commit()
    await session.delete(entry)
    await session.commit()
    assert bus.changes(school_class.id) == start + 2


async def test_the_class_row_wakes_its_own_class(bus, session, school_class) -> None:
    start = bus.changes(school_class.id)
    school_class.name = "9Б"
    await session.commit()
    assert bus.changes(school_class.id) == start + 1


async def test_a_bell_period_wakes_the_class_of_its_schedule(bus, school_class) -> None:
    """In a session that has not read the schedule: the bus reads it to find
    the class, and a period added by id, one changed and one deleted each
    wake it once."""
    class_id, schedule_id = school_class.id, school_class.bell_schedule_id
    start = bus.changes(class_id)
    async with SessionLocal() as fresh:
        period = await fresh.scalar(
            select(BellPeriod).where(BellPeriod.schedule_id == schedule_id, BellPeriod.index == 1)
        )
        period.ends_at = time(9, 10)
        await fresh.commit()
        fresh.add(
            BellPeriod(schedule_id=schedule_id, index=9, starts_at=time(16), ends_at=time(16, 45))
        )
        await fresh.commit()
        await fresh.delete(period)
        await fresh.commit()
    assert bus.changes(class_id) == start + 3


async def test_a_bell_period_added_through_its_schedule_wakes_the_class(
    bus, session, school_class
) -> None:
    start = bus.changes(school_class.id)
    schedule = BellSchedule(class_id=school_class.id, name="Субботнее")
    schedule.periods.append(BellPeriod(index=1, starts_at=time(9), ends_at=time(9, 40)))
    session.add(schedule)
    await session.commit()
    assert bus.changes(school_class.id) == start + 1


async def test_what_changes_no_window_wakes_nobody(bus, session, school_class) -> None:
    start = bus.changes(school_class.id)
    session.add_all(
        [
            PersonalTask(class_id=school_class.id, telegram_id=2001, title="Купить тетрадь"),
            ReminderSettings(class_id=school_class.id, telegram_id=2001),
            AuditEntry(class_id=school_class.id, telegram_id=2001, action="x", summary="y"),
        ]
    )
    await session.commit()
    assert bus.changes(school_class.id) == start


async def test_a_rollback_forgets_what_the_transaction_collected(
    bus, session, school_class
) -> None:
    class_id = school_class.id
    start = bus.changes(class_id)
    session.add(_homework(class_id))
    await session.flush()
    await session.rollback()
    session.add(PersonalTask(class_id=class_id, telegram_id=2001, title="Купить тетрадь"))
    await session.commit()
    assert bus.changes(class_id) == start


async def test_a_savepoint_released_publishes_nothing_until_the_commit(
    bus, session, school_class
) -> None:
    """SQLAlchemy fires ``after_commit`` for a savepoint's release as well. A
    watcher woken there would read the window before the transaction around
    it commits, find nothing new, and never be woken for the change again:
    ``homework.create`` writes inside exactly such a savepoint."""
    class_id = school_class.id
    start = bus.changes(class_id)
    with bus.watching(class_id) as woken:
        async with session.begin_nested():
            session.add(_homework(class_id))
        assert (bus.changes(class_id), woken.is_set()) == (start, False)
        await session.commit()
        assert (bus.changes(class_id), woken.is_set()) == (start + 1, True)


async def test_a_savepoint_rolled_back_never_hides_what_the_commit_holds(
    bus, session, school_class
) -> None:
    class_id = school_class.id
    start = bus.changes(class_id)
    nested = await session.begin_nested()
    session.add(_homework(class_id))
    await session.flush()
    await nested.rollback()
    session.add(_row("day_events", class_id))
    await session.commit()
    assert bus.changes(class_id) == start + 1


async def test_a_touch_goes_out_with_the_commit_and_is_forgotten_with_a_rollback(
    bus, session, school_class
) -> None:
    """What a bulk statement does: the unit of work never sees it."""
    class_id = school_class.id
    statement = (
        sa_update(TimetableEntry).where(TimetableEntry.class_id == class_id).values(room="7")
    )
    start = bus.changes(class_id)
    await session.execute(statement)
    await session.rollback()
    assert bus.changes(class_id) == start, "an untouched bulk write is invisible to the bus"
    await session.execute(statement)
    bus.touch(session, class_id)
    await session.rollback()
    await session.commit()
    assert bus.changes(class_id) == start
    await session.execute(statement)
    bus.touch(session, class_id)
    await session.commit()
    assert bus.changes(class_id) == start + 1


async def test_without_the_listener_nothing_is_collected_or_published(
    session, school_class
) -> None:
    assert not watch.listening(), "the suite runs with streaming off"
    class_id = school_class.id
    start = watch.changes(class_id)
    await session.execute(
        sa_update(TimetableEntry).where(TimetableEntry.class_id == class_id).values(room="7")
    )
    watch.touch(session, class_id)
    assert not session.info
    session.add(_homework(class_id))
    await session.commit()
    assert watch.changes(class_id) == start


async def test_a_watcher_is_woken_by_the_commit_never_by_the_flush_nor_by_another_class(
    bus, session, school_class
) -> None:
    other = SchoolClass(name="5А", join_code="OTHER5")
    session.add(other)
    await session.commit()
    class_id, other_id = school_class.id, other.id
    with bus.watching(class_id) as woken, bus.watching(other_id) as elsewhere:
        session.add(_homework(class_id))
        await session.flush()
        assert not woken.is_set()
        await session.commit()
        assert woken.is_set()
        assert not elsewhere.is_set()
        assert (bus.watchers(class_id), bus.watchers(other_id)) == (1, 1)
    assert (bus.watchers(class_id), bus.watchers(other_id)) == (0, 0)


def test_a_revision_moves_with_every_change_and_names_this_process(bus, monkeypatch) -> None:
    """A restarted host counts from nothing again; its name is what keeps its
    first revision from equalling one a phone kept from before the restart."""
    nobody = 987654
    first = bus.revision(nobody)
    bus.publish([nobody])
    second = bus.revision(nobody)
    assert first != second
    assert bus.changed_at(nobody) is not None
    monkeypatch.setattr(watch, "BOOT", "another-start")
    assert bus.revision(nobody) not in (first, second)


async def test_attaching_twice_hears_each_change_once(bus, session, school_class) -> None:
    bus.attach()
    start = bus.changes(school_class.id)
    session.add(_homework(school_class.id))
    await session.commit()
    assert bus.changes(school_class.id) == start + 1
```


- [ ] **Step 2: Run it.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_watch.py
```
Expected: the collection error `ImportError: cannot import name 'watch' from 'app'`.

- [ ] **Step 3: Green.** The bus:

Write `server/app/watch.py` whole:
```python
"""The class-changed bus: which classes a committed transaction changed, told to whoever watches.

``WatchClass`` (``rpc/watch.py``) streams «this class changed» from the host
target (``docs/specs/2026-10-05-server-v2-design.md``, decision 13), and it has
to hear every change, whichever shell made it: v1, v2 and the bot all write
through this process's sessions, so the bus listens to the session itself.

- **ORM writes** are collected before each flush, by the class they belong to:
  a row's ``class_id``, a bell period through its schedule, the class row
  through its ``id``.
- **Bulk statements** (``update``, ``delete`` and ``insert`` executed directly)
  never pass through the unit of work, so each one on a window table calls
  :func:`touch` with the class it already has at hand.
  ``tests/test_watch.py`` finds every such statement under ``app/`` and fails
  on a function that makes one and does not touch.
- What a transaction collected is published once its **outermost** commit is
  done, and forgotten when that transaction ends any other way. SQLAlchemy
  fires ``after_commit`` for a savepoint's release too, and a release commits
  nothing a watcher's next read could see — so a release publishes nothing,
  and the class goes out with the commit that makes it visible. A savepoint
  rolled back keeps what it collected: that can wake a watcher for nothing,
  which costs it a sync answered ``304``, and it can never fail to wake one.

Only the tables a schedule window is read from wake anybody
(:data:`WINDOW_TABLES`): a pupil's task, a tick, a diary session, a reminder
setting or an audit line changes no window, and a phone's own row is left out
on purpose (the constant says why).

**One process, one event loop.** What a process writes is all it can hear,
which is why the host is a complete deployment rather than a sidecar of
Vercel (the design's question 2), and why ``app.host`` runs pyvoy with one
worker thread: a watcher's event is set from the loop that committed, and the
engine's pool is that loop's anyway. A script, a migration or a second
instance writing the same database wakes nobody here.

**Attached only where streaming is on** (``app.main``, ``Settings.
streaming_enabled``), so Vercel, the suite and a run without
``LESSONS_STREAMING`` pay one ``if`` in :func:`touch` and nothing else.
"""

from __future__ import annotations

import asyncio
import secrets
from collections.abc import Iterable, Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import event
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import Session, SessionTransaction

from app.models import BellPeriod, BellSchedule, SchoolClass

#: The tables a schedule window is read from: the class itself, its bells,
#: its weekly template and its subjects, its terms, every dated thing laid
#: over them, and its members, whose role every window carries in its
#: ``DeviceAccess`` («a role changed in the bot is a new tag»,
#: ``services/window.strong_etag``). A write anywhere else wakes nobody.
#: ``device_tokens`` is not here although a phone's link is in its window too:
#: every call writes ``last_seen_at`` there, which would wake the whole class
#: each quarter of an hour per phone.
WINDOW_TABLES = frozenset(
    {
        "classes",
        "bot_users",
        "bell_schedules",
        "bell_periods",
        "subjects",
        "timetable_entries",
        "terms",
        "day_overrides",
        "lesson_overrides",
        "day_events",
        "homework",
    }
)

#: This process's name, in every revision it hands out. The counts below start
#: from nothing on every start, so without it a restarted host would answer a
#: phone the very revision that phone kept from before the restart, and the
#: change made in between would never be fetched.
BOOT = secrets.token_hex(6)

#: Where a session keeps the classes its transaction changed.
_KEY = "lessons.watch.changed"

_listening = False
_changes: dict[int, int] = {}
_changed_at: dict[int, datetime] = {}
_watchers: dict[int, set[asyncio.Event]] = {}


def listening() -> bool:
    """Whether this process hears its own writes: streaming is on here."""
    return _listening


def changes(class_id: int) -> int:
    """How many committed transactions have changed ``class_id`` since this process started."""
    return _changes.get(class_id, 0)


def revision(class_id: int) -> str:
    """What ``WatchClass`` sends: opaque to a client, equal only for the same
    class in the same state in the same process."""
    return f"{BOOT}.{changes(class_id)}"


def changed_at(class_id: int) -> datetime | None:
    """When this process last saw ``class_id`` change; ``None`` when it has not."""
    return _changed_at.get(class_id)


def watchers(class_id: int) -> int:
    """How many streams are watching ``class_id`` now."""
    return len(_watchers.get(class_id, ()))


def touch(session: AsyncSession | Session, class_id: int) -> None:
    """Say that ``class_id`` changes in ``session``'s transaction.

    For a bulk statement, which the unit of work never sees. Published with
    the transaction's outermost commit and forgotten with anything else, like
    what a flush collects; a no-op where streaming is off.
    """
    if not _listening:
        return
    session.info.setdefault(_KEY, set()).add(class_id)


@contextmanager
def watching(class_id: int) -> Iterator[asyncio.Event]:
    """An event, set every time a commit changes ``class_id``, while the block runs.

    The watcher clears it before it reads the revision, so changes made while
    it was busy are one wake rather than several, and none is lost.
    """
    woken = asyncio.Event()
    _watchers.setdefault(class_id, set()).add(woken)
    try:
        yield woken
    finally:
        remaining = _watchers.get(class_id)
        if remaining is not None:
            remaining.discard(woken)
            if not remaining:
                del _watchers[class_id]


def publish(class_ids: Iterable[int]) -> None:
    """Count a change of each class and wake whoever watches it."""
    now = datetime.now(UTC)
    for class_id in class_ids:
        _changes[class_id] = changes(class_id) + 1
        _changed_at[class_id] = now
        for woken in _watchers.get(class_id, ()):
            woken.set()


def _class_of(session: Session, row: Any) -> int | None:
    if isinstance(row, SchoolClass):
        # None for a class being created, whom nobody can be watching yet.
        return row.id
    if isinstance(row, BellPeriod):
        if row.schedule_id is None:
            # Added through the relationship and not flushed yet.
            parent = row.schedule
        else:
            with session.no_autoflush:
                parent = session.get(BellSchedule, row.schedule_id)
        return parent.class_id if parent is not None else None
    return getattr(row, "class_id", None)


def _collect(session: Session, _context: Any, _instances: Any) -> None:
    changed: set[int] = session.info.setdefault(_KEY, set())
    modified = [row for row in session.dirty if session.is_modified(row, include_collections=False)]
    for row in (*session.new, *modified, *session.deleted):
        if getattr(row, "__tablename__", None) not in WINDOW_TABLES:
            continue
        class_id = _class_of(session, row)
        if class_id is not None:
            changed.add(class_id)


def _committed(session: Session) -> None:
    # A savepoint's release fires this too; what it wrote is not readable by
    # anyone else until the transaction around it commits.
    if session.in_nested_transaction():
        return
    changed = session.info.pop(_KEY, None)
    if changed:
        publish(changed)


def _ended(session: Session, transaction: SessionTransaction) -> None:
    if transaction.parent is None:
        session.info.pop(_KEY, None)


_LISTENERS = (
    ("before_flush", _collect),
    ("after_commit", _committed),
    ("after_transaction_end", _ended),
)


def attach() -> None:
    """Hear every session of this process. Idempotent."""
    global _listening
    if _listening:
        return
    for name, listener in _LISTENERS:
        event.listen(Session, name, listener)
    _listening = True


def detach() -> None:
    """Stop hearing them; for the tests that attach."""
    global _listening
    if not _listening:
        return
    for name, listener in _LISTENERS:
        event.remove(Session, name, listener)
    _listening = False
```


- [ ] **Step 4: Run it again.** The same command. Expected: `23 passed`. To see the savepoint guard bite, change `if session.in_nested_transaction():` in `_committed` to `if False:` and run `-k savepoint`: `test_a_savepoint_released_publishes_nothing_until_the_commit` fails; put it back.

- [ ] **Step 5: The gates.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/python.exe -m ruff check app tests scripts migrations && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/python.exe -m ruff format --check app/watch.py tests/test_watch.py && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/python.exe -m mypy
```
Expected: `All checks passed!`, `2 files already formatted`, and `Success: no issues found in 239 source files`.

- [ ] **Step 6: Commit.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3c-t1.txt`:
```text
Hear every committed change of a class, in the process that made it

app/watch.py is the bus WatchClass will stream from. It listens to
SQLAlchemy's Session, so the bot, v1 and v2 are heard alike: before each
flush it collects the class of every row written to a table a schedule
window is read from - the class itself, its members' roles, its bells and
their periods (found through the schedule), its timetable, subjects,
terms, day marks, substitutions, events and homework - and it publishes
them once the transaction's outermost commit is done. A savepoint's
release fires after_commit too, and publishing there would wake a watcher
before the change is visible; it publishes nothing. A rollback forgets.
touch() is the door for a bulk statement, which never passes through the
unit of work. A revision is the process's boot name and a count, so a
restarted host never repeats one.

Not covered: nothing attaches the bus yet, and no bulk statement touches
it; both are the next tasks'.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230 && git add server/app/watch.py server/tests/test_watch.py && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3c-t1.txt
```

---

### 3c Task 2: Every bulk write on a window table touches the bus, the bot's included

Decision 13 («bulk statements … each of the few that touch a window table calls `watch.touch`»); Ruling 150. Behaviour is unchanged everywhere: the touches are no-ops while streaming is off, and the bot's «-» deletes the same rows through the service a paste already uses — `test_bot_handlers.py`'s `test_a_dash_clears_the_weekday` is the proof and is not edited.

**Files:**
- Modify: `server/app/services/calendar.py`, `structure.py`, `subjects.py`, `terms.py`, `timetable_edit.py`, `server/app/bot/handlers/timetable.py`, `server/tests/test_watch.py`

**Interfaces:**
- Consumes: Task 1's `watch.touch`, `watch.WINDOW_TABLES`, `watch.changes`; `structure.apply_timetable(session, school_class, days, bells)`, which empties a weekday named with no rows.
- Produces: `watch.touch(session, class_id)` after the bulk statements of `calendar.ensure_calendar_token`, `structure.rename_subject`, `write_bell_periods` and `apply_timetable`, `subjects.sync_from_timetable`, `terms.ensure`, `timetable_edit._shift`, `remove_lesson` and `move_lesson`; the bot's `timetable_apply` with no statement of its own. In the tests: `bulk_writes(source, tables) -> list[tuple[str, int, bool]]`.

- [ ] **Step 1: Red.** The walk, its own check, and the five writes made of bulk statements alone, in this order:

In `server/tests/test_watch.py`, replace:
```python
from __future__ import annotations

from datetime import date, time
```
with:
```python
from __future__ import annotations

import ast
from datetime import date, time
```

In `server/tests/test_watch.py`, replace:
```python
from datetime import date, time
from typing import Any
```
with:
```python
from datetime import date, time
from pathlib import Path
from typing import Any
```

In `server/tests/test_watch.py`, replace:
```python
from app import watch
from app.db import SessionLocal
```
with:
```python
from app import watch
from app.bot.handlers.timetable import timetable_apply
from app.db import Base, SessionLocal
```

In `server/tests/test_watch.py`, replace:
```python
    TimetableEntry,
    WeekParity,
)
```
with:
```python
    TimetableEntry,
    WeekParity,
)
from app.services import calendar, structure, timetable_edit

APP = Path(__file__).resolve().parents[1] / "app"
```

In `server/tests/test_watch.py`, replace:
```python
    session.add(_homework(school_class.id))
    await session.commit()
    assert bus.changes(school_class.id) == start + 1
```
with:
```python
    session.add(_homework(school_class.id))
    await session.commit()
    assert bus.changes(school_class.id) == start + 1


# ---- every bulk write on a window table touches the bus ---------------------

#: The statements sqlalchemy builds that never pass through the unit of work.
_BULK = {"update", "delete", "insert"}


def _tables() -> dict[str, str]:
    """Every mapped class's name and its table, the FSM storage's included."""
    from app import fsm_storage, models  # noqa: F401 - both register mappers

    return {mapper.class_.__name__: mapper.local_table.name for mapper in Base.registry.mappers}


def _bulk_names(tree: ast.Module) -> tuple[set[str], set[str]]:
    """The names a module calls sqlalchemy's bulk statements by, however it
    imports them, and the names it calls sqlalchemy itself by."""
    functions: set[str] = set()
    modules: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and (node.module or "").split(".")[0] == "sqlalchemy":
            functions.update(
                alias.asname or alias.name for alias in node.names if alias.name in _BULK
            )
        elif isinstance(node, ast.Import):
            modules.update(
                alias.asname or alias.name
                for alias in node.names
                if alias.name.split(".")[0] == "sqlalchemy"
            )
    return functions, modules


def _is_bulk(call: ast.Call, functions: set[str], modules: set[str]) -> bool:
    if isinstance(call.func, ast.Name):
        return call.func.id in functions
    if isinstance(call.func, ast.Attribute) and call.func.attr in _BULK:
        root = call.func.value
        while isinstance(root, ast.Attribute):
            root = root.value
        return isinstance(root, ast.Name) and root.id in modules
    return False


def _on_a_window_table(call: ast.Call, tables: dict[str, str]) -> bool:
    """A statement on a model the walk can name is judged by its table; any
    other — a loop variable, an attribute — is taken to be a window table."""
    first = call.args[0] if call.args else None
    if isinstance(first, ast.Name) and first.id in tables:
        return tables[first.id] in watch.WINDOW_TABLES
    return True


def _touches(scope: ast.AST) -> bool:
    return any(
        isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr == "touch"
        and isinstance(node.func.value, ast.Name)
        and node.func.value.id == "watch"
        for node in ast.walk(scope)
    )


def bulk_writes(source: str, tables: dict[str, str]) -> list[tuple[str, int, bool]]:
    """Each bulk statement on a window table in ``source``: the top-level
    function or method it is built in, its line, and whether that function
    touches the bus. A helper defined inside the function is the function's."""
    tree = ast.parse(source)
    functions, modules = _bulk_names(tree)
    scopes: list[tuple[str, ast.AST]] = []
    for node in tree.body:
        if isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef):
            scopes.append((node.name, node))
        elif isinstance(node, ast.ClassDef):
            scopes += [
                (f"{node.name}.{item.name}", item)
                for item in node.body
                if isinstance(item, ast.FunctionDef | ast.AsyncFunctionDef)
            ]
        else:
            scopes.append(("<module>", node))
    return [
        (name, call.lineno, _touches(scope))
        for name, scope in scopes
        for call in ast.walk(scope)
        if isinstance(call, ast.Call)
        and _is_bulk(call, functions, modules)
        and _on_a_window_table(call, tables)
    ]


def test_every_bulk_write_on_a_window_table_touches_the_bus() -> None:
    """The design asks this of ``services/``; it is asked of all of ``app/``,
    because the bus hears every shell, and the bot's «-» on a weekday was a
    bare delete in a handler until this stage moved it into the service."""
    tables = _tables()
    found = {
        path.relative_to(APP.parent).as_posix(): bulk_writes(path.read_text("utf-8"), tables)
        for path in sorted(APP.rglob("*.py"))
        if "contract" not in path.relative_to(APP).parts
    }
    untouched = [
        f"{name} {scope}:{line}"
        for name, writes in found.items()
        for scope, line, touched in writes
        if not touched
    ]
    assert untouched == [], (
        "a bulk statement on a window table that the bus never hears of; call "
        "watch.touch(session, class_id) in the same function: " + ", ".join(untouched)
    )
    # And the walk is not vacuous: it sees the writes this stage touched.
    assert {"app/services/structure.py", "app/services/timetable_edit.py"} <= {
        name for name, writes in found.items() if writes
    }


def test_the_walk_sees_an_untouched_bulk_write_however_it_is_spelled() -> None:
    slips = """
from sqlalchemy import delete as sa_delete
from sqlalchemy import update
import sqlalchemy as sa


async def direct(session, class_id):
    await session.execute(sa_delete(Homework).where(Homework.class_id == class_id))


async def looped(session, class_id):
    for model in (Homework, DayEvent):
        await session.execute(update(model).values(subject_name="x"))


class Holder:
    async def method(self, session):
        await session.execute(sa.delete(TimetableEntry))


async def elsewhere(session):
    await session.execute(sa_delete(PersonalTask))


async def kept(session, class_id):
    await session.execute(sa_delete(Homework))
    watch.touch(session, class_id)
"""
    writes = bulk_writes(slips, _tables())
    assert [(scope, touched) for scope, _line, touched in writes] == [
        ("direct", False),
        ("looped", False),
        ("Holder.method", False),
        ("kept", True),
    ]


@pytest.mark.parametrize(
    "write",
    ["empty_a_weekday", "remove_a_lesson", "move_a_lesson", "mint_the_feed_secret", "bot_dash"],
)
async def test_a_write_made_of_bulk_statements_alone_wakes_the_class(
    bus, session, school_class, FakeState, FakeMessage, write
) -> None:
    """Each path here writes a window table through bulk statements and
    nothing else, so only its touch can wake anybody."""
    class_id = school_class.id
    start = bus.changes(class_id)
    if write == "empty_a_weekday":
        await structure.apply_timetable(session, school_class, {1: []}, [])
    elif write == "remove_a_lesson":
        assert await timetable_edit.remove_lesson(session, class_id, 1, 3) == 1
    elif write == "move_a_lesson":
        assert await timetable_edit.move_lesson(session, class_id, 1, 1, up=False) == 2
    elif write == "mint_the_feed_secret":
        assert await calendar.ensure_calendar_token(session, school_class)
    else:
        message = FakeMessage(text="-")
        state = FakeState(data={"weekday": 1})
        await timetable_apply(message, state, session, school_class, Role.ADMIN)
        assert "очищено" in message.last
    await session.commit()
    assert bus.changes(class_id) == start + 1
```


- [ ] **Step 2: Run it.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_watch.py
```
Expected: `6 failed, 24 passed`. The walk names twelve statements: `app/bot/handlers/timetable.py timetable_apply:215`, `app/services/calendar.py ensure_calendar_token:70`, `structure.py rename_subject:141` and `:155`, `write_bell_periods:273`, `apply_timetable:334`, `subjects.py sync_from_timetable:282`, `terms.py ensure:192`, `timetable_edit.py _shift:348` and `:351`, `remove_lesson:440` and `move_lesson:475`; each of the five bulk-only writes is `assert start == start + 1`.

- [ ] **Step 3: Green.** The touches, and the bot's «-» through the service:

In `server/app/bot/handlers/timetable.py`, replace:
```python
from aiogram.types import CallbackQuery, InlineKeyboardButton, Message
from sqlalchemy import delete, select
```
with:
```python
from aiogram.types import CallbackQuery, InlineKeyboardButton, Message
from sqlalchemy import select
```

In `server/app/bot/handlers/timetable.py`, replace:
```python
        await session.execute(
            delete(TimetableEntry).where(
                TimetableEntry.class_id == school_class.id, TimetableEntry.weekday == weekday
            )
        )
```
with:
```python
        # Through the service that writes the template, as a paste is below: a
        # weekday named with nothing under it is emptied, and the service is
        # what tells a host's watchers that the class changed (app/watch.py).
        await structure.apply_timetable(session, school_class, {weekday: []}, [])
```

In `server/app/services/calendar.py`, replace:
```python
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import PersonalTask, SchoolClass, TaskPriority
```
with:
```python
from sqlalchemy.ext.asyncio import AsyncSession

from app import watch
from app.models import PersonalTask, SchoolClass, TaskPriority
```

In `server/app/services/calendar.py`, replace:
```python
        .values(calendar_token=secrets.token_urlsafe(24))
    )
    await session.refresh(school_class, ["calendar_token"])
```
with:
```python
        .values(calendar_token=secrets.token_urlsafe(24))
    )
    watch.touch(session, school_class.id)
    await session.refresh(school_class, ["calendar_token"])
```

In `server/app/services/structure.py`, replace:
```python
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import rows_affected
```
with:
```python
from sqlalchemy.ext.asyncio import AsyncSession

from app import watch
from app.db import rows_affected
```

In `server/app/services/structure.py`, replace:
```python
    )
    moved += rows_affected(result)

    # Homework and substitutions carry no link at all: a lesson keeps its name when a
```
with:
```python
    )
    moved += rows_affected(result)
    watch.touch(session, class_id)

    # Homework and substitutions carry no link at all: a lesson keeps its name when a
```

In `server/app/services/structure.py`, replace:
```python
    await session.execute(sa_delete(BellPeriod).where(BellPeriod.schedule_id == schedule.id))
    for index, start, end in rows:
```
with:
```python
    await session.execute(sa_delete(BellPeriod).where(BellPeriod.schedule_id == schedule.id))
    watch.touch(session, schedule.class_id)
    for index, start, end in rows:
```

In `server/app/services/structure.py`, replace:
```python
                TimetableEntry.weekday.in_(list(days)),
            )
        )

    rung = (
```
with:
```python
                TimetableEntry.weekday.in_(list(days)),
            )
        )
        watch.touch(session, school_class.id)

    rung = (
```

In `server/app/services/subjects.py`, replace:
```python
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Subject, TimetableEntry
```
with:
```python
from sqlalchemy.ext.asyncio import AsyncSession

from app import watch
from app.models import Subject, TimetableEntry
```

In `server/app/services/subjects.py`, replace:
```python
            .values(subject_id=subject.id, subject_name=subject.name)
        )
    return created
```
with:
```python
            .values(subject_id=subject.id, subject_name=subject.name)
        )
        watch.touch(session, class_id)
    return created
```

In `server/app/services/terms.py`, replace:
```python
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import SchoolClass, Term, TermKind
```
with:
```python
from sqlalchemy.ext.asyncio import AsyncSession

from app import watch
from app.models import SchoolClass, Term, TermKind
```

In `server/app/services/terms.py`, replace:
```python
            sa_delete(Term).where(Term.class_id == school_class.id, Term.year == year)
        )

    seeded = [
```
with:
```python
            sa_delete(Term).where(Term.class_id == school_class.id, Term.year == year)
        )
        watch.touch(session, school_class.id)

    seeded = [
```

In `server/app/services/timetable_edit.py`, replace:
```python
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import rows_affected
```
with:
```python
from sqlalchemy.ext.asyncio import AsyncSession

from app import watch
from app.db import rows_affected
```

In `server/app/services/timetable_edit.py`, replace:
```python
        )
        .values(index=TimetableEntry.index - PARK + by)
    )


async def add_lesson(
```
with:
```python
        )
        .values(index=TimetableEntry.index - PARK + by)
    )
    watch.touch(session, class_id)


async def add_lesson(
```

In `server/app/services/timetable_edit.py`, replace:
```python
    if removed:
        rung = await rung_indexes(session, class_id)
```
with:
```python
    if removed:
        watch.touch(session, class_id)
        rung = await rung_indexes(session, class_id)
```

In `server/app/services/timetable_edit.py`, replace:
```python
    await session.execute(_set(PARK, neighbour))
    return neighbour
```
with:
```python
    await session.execute(_set(PARK, neighbour))
    watch.touch(session, class_id)
    return neighbour
```


- [ ] **Step 4: Run them, with the tests of every module touched.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_watch.py tests/test_bot_handlers.py tests/test_bot_editor.py tests/test_timetable_edit.py tests/test_subjects.py tests/test_services.py tests/test_v2_calendar_feed.py tests/test_v2_terms.py tests/test_hardening.py tests/test_the_caller_commits.py tests/test_service_layering.py
```
Expected: `427 passed`, in about two and a half minutes. `test_a_dash_clears_the_weekday` is among them, unedited.

- [ ] **Step 5: The gates.** ruff and mypy as Task 1's Step 5, without the format check. Expected: `All checks passed!` and `Success: no issues found in 239 source files`. No modified file gains a difference from `ruff format` it did not have before.

- [ ] **Step 6: Commit.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3c-t2.txt`:
```text
Tell the bus of every bulk write on a window table, the bot's included

A bulk update or delete never passes through the unit of work, so the bus
cannot see it by itself. Each one on a table a schedule window is read
from now calls watch.touch with the class it already has: the calendar
secret, a subject's rename and the timetable's relinking, a bell
schedule's rows, a timetable import, a term scheme's change, and the
button editor's shifts, deletes and moves. The bot's «-» on a weekday was
a bare delete in its handler, outside services/, which no walk of the
services would have found and no watcher would have heard; it now goes
through structure.apply_timetable with an empty weekday, as a paste does.

tests/test_watch.py walks all of app/, every spelling of sqlalchemy's
update, delete and insert, and fails on a function that writes a window
table that way without touching; five paths made of bulk statements alone
are asked to wake the class.

Not covered: a script or a migration writing the database directly wakes
nobody, by design - the bus hears its own process.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230 && git add server/app/services/calendar.py server/app/services/structure.py server/app/services/subjects.py server/app/services/terms.py server/app/services/timetable_edit.py server/app/bot/handlers/timetable.py server/tests/test_watch.py && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3c-t2.txt
```

---

### 3c Task 3: The host's marker and the streaming switch in the settings

Decisions 7 and 13; Rulings 147, 155 and 161. The refusal is the same list on both targets; only its trigger grows. v1's answers do not move.

**Files:**
- Modify: `server/app/config.py`, `server/app/main.py`, `server/.env.example`, `docker-compose.yml`, `server/tests/conftest.py`, `server/tests/test_deployment_config.py`, `server/tests/test_api_docs.py`, `server/tests/test_cold_start.py`, `server/tests/test_env_example.py`, `server/tests/test_compose.py`

**Interfaces:**
- Consumes: Task 1's `watch.attach` and `watch.listening`.
- Produces: `Settings.lessons_target: str`, `lessons_streaming: bool`, `behind_host -> bool`, `deployed -> bool`, `streaming_enabled -> bool`; `get_settings()` refusing wherever `deployed`; `app.main` attaching the bus at import where `streaming_enabled`, and serving no docs wherever `deployed`; the test probe's `streaming` key (`test_cold_start.py`).

- [ ] **Step 1: Red.** The tests, the suite's environment, and the files the tests read of a deployment:

In `server/tests/conftest.py`, replace:
```python
# directory. A test that wants the directory configures one and replaces it.
os.environ["DADATA_TOKEN"] = ""
```
with:
```python
# directory. A test that wants the directory configures one and replaces it.
os.environ["DADATA_TOKEN"] = ""
# Whatever the shell holds: the suite is no host and streams nothing. The build
# console runs the host and this suite on one machine, and a LESSONS_STREAMING
# exported for the one would attach the bus here, so that every WatchClass the
# gate test opens through httpx's ASGI transport — which returns only when the
# answer ends — would wait for ever; a LESSONS_TARGET would make the suite a
# deployment, which get_settings refuses on the first import. A test that wants
# the bus attaches it (test_watch.py, test_v2_watch.py).
os.environ["LESSONS_STREAMING"] = "false"
os.environ.pop("LESSONS_TARGET", None)
```

In `server/tests/test_api_docs.py`, replace:
```python
import httpx
from httpx import ASGITransport
```
with:
```python
import httpx
import pytest
from httpx import ASGITransport
```

In `server/tests/test_api_docs.py`, replace:
```python
def test_a_vercel_deployment_serves_no_docs_and_still_answers():
    result = subprocess.run(
```
with:
```python
#: What the host image is started with: the same settings, said by the host's
#: own marker rather than by Vercel's (the server-v2 design, decision 7).
_HOST_ENV = {**_VERCEL_ENV, "VERCEL": "", "LESSONS_TARGET": "host"}


@pytest.mark.parametrize("deployment", [_VERCEL_ENV, _HOST_ENV], ids=["vercel", "host"])
def test_a_deployment_serves_no_docs_and_still_answers(deployment):
    result = subprocess.run(
```

In `server/tests/test_api_docs.py`, replace:
```python
        cwd=str(SERVER),
        env={**os.environ, **_VERCEL_ENV},
```
with:
```python
        cwd=str(SERVER),
        env={**os.environ, **deployment},
```

In `server/tests/test_cold_start.py`, replace:
```python
loaded = sorted(name for name in sys.modules if name.partition(".")[0] == "aiogram")
sentry = sorted(name for name in sys.modules if name.partition(".")[0] == "sentry_sdk")
```
with:
```python
loaded = sorted(name for name in sys.modules if name.partition(".")[0] == "aiogram")
bus = sys.modules.get("app.watch")
sentry = sorted(name for name in sys.modules if name.partition(".")[0] == "sentry_sdk")
```

In `server/tests/test_cold_start.py`, replace:
```python
            "holders": holders,
            "webhook": "app.api.telegram" in sys.modules,
        }
```
with:
```python
            "holders": holders,
            "webhook": "app.api.telegram" in sys.modules,
            "streaming": bool(bus is not None and bus.listening()),
        }
```

In `server/tests/test_cold_start.py`, replace:
```python
    # `VERCEL` dropped first, so the local case is local even in a shell that
    # happens to carry it; the Vercel case sets it again.
    env = {name: value for name, value in os.environ.items() if name != "VERCEL"}
```
with:
```python
    # `VERCEL` and the host's marker dropped first, so the local case is local
    # even in a shell that happens to carry one; the Vercel case sets it again.
    env = {
        name: value
        for name, value in os.environ.items()
        if name not in ("VERCEL", "LESSONS_TARGET")
    }
```

In `server/tests/test_cold_start.py`, replace:
```python
        f"importing app.main loaded {len(found['aiogram'])} aiogram modules; "
        f"held by {found['holders']}"
    )
```
with:
```python
        f"importing app.main loaded {len(found['aiogram'])} aiogram modules; "
        f"held by {found['holders']}"
    )


@pytest.mark.parametrize(
    ("settings", "listening"),
    [
        ({**_LOCAL, "LESSONS_STREAMING": "true"}, True),
        ({**_VERCEL, "LESSONS_STREAMING": "true"}, False),
        ({**_LOCAL, "LESSONS_STREAMING": "false"}, False),
    ],
    ids=["asked-for", "on-vercel", "not-asked"],
)
def test_the_bus_listens_where_streaming_is_on_and_nowhere_else(settings, listening):
    """``app.main`` attaches the class-changed bus at import where streaming is
    on (``app/watch.py``), so that the first write of the first request is
    heard — and never on Vercel, whatever LESSONS_STREAMING says."""
    found = _import_in_a_fresh_interpreter("app.main", settings)
    assert found["streaming"] is listening
```

In `server/tests/test_compose.py`, replace:
```python
    "VERCEL": "the platform's own; set here it would switch on Vercel's refusal to start",
    "HOST": "read only by `python -m app.main`; the image's command binds 0.0.0.0 itself",
```
with:
```python
    "VERCEL": "the platform's own; set here it would switch on Vercel's refusal to start",
    "LESSONS_TARGET": "the host image's own statement; it would switch on the same refusal",
    "HOST": "read only by `python -m app.main` and `python -m app.host`; the image binds itself",
```

In `server/tests/test_deployment_config.py`, replace:
```python
    padded = deployed(DIARY_SECRET="  " + "k" * (MIN_DIARY_SECRET_LENGTH - 1) + "  ")
    assert padded.diary_configured is False
```
with:
```python
    padded = deployed(DIARY_SECRET="  " + "k" * (MIN_DIARY_SECRET_LENGTH - 1) + "  ")
    assert padded.diary_configured is False


# ---- the host target (docs/specs/2026-10-05-server-v2-design.md, decisions 7, 13)

#: A correctly configured host: everything a Vercel deployment needs, said by
#: the host image's own marker rather than by Vercel's.
HOSTED = {**DEPLOYED, "VERCEL": "", "LESSONS_TARGET": "host"}


def hosted(**overrides: str) -> Settings:
    """A correctly configured host, with one thing changed."""
    return Settings(**{**HOSTED, **overrides})


def test_a_configured_host_has_nothing_to_report():
    settings = hosted()
    assert (settings.behind_host, settings.behind_vercel, settings.deployed) == (True, False, True)
    assert settings.deployment_problems() == []


def test_a_host_on_the_local_defaults_is_refused_as_vercel_is():
    problems = hosted(
        DATABASE_URL=LOCAL_DATABASE_URL, BOT_TOKEN="", RUN_BOT="true"
    ).deployment_problems()
    for name in ("DATABASE_URL", "BOT_TOKEN", "RUN_BOT"):
        assert any(name in problem for problem in problems), name


@pytest.mark.parametrize("marker", ["hots", "vercel", "docker"])
def test_a_marker_that_names_no_target_is_refused_rather_than_ignored(marker):
    """A misspelt marker must not leave a deployment on the local defaults."""
    settings = hosted(LESSONS_TARGET=marker)
    assert settings.deployed is True
    assert settings.behind_host is False
    (problem,) = settings.deployment_problems()
    assert "LESSONS_TARGET" in problem


def test_the_marker_is_read_whatever_its_case_and_spacing():
    assert hosted(LESSONS_TARGET=" Host\n").behind_host is True


def test_a_deployment_is_one_target_or_the_other():
    (problem,) = hosted(VERCEL="1").deployment_problems()
    assert "LESSONS_TARGET" in problem and "VERCEL" in problem


def test_get_settings_refuses_a_misconfigured_host(monkeypatch):
    monkeypatch.delenv("VERCEL", raising=False)
    monkeypatch.setenv("LESSONS_TARGET", "host")
    monkeypatch.setenv("DATABASE_URL", LOCAL_DATABASE_URL)
    get_settings.cache_clear()
    try:
        with pytest.raises(DeploymentNotConfigured) as raised:
            get_settings()
        assert "DATABASE_URL" in str(raised.value)
        assert "docs/deploy.md" in str(raised.value)
    finally:
        get_settings.cache_clear()


def test_a_local_run_is_no_deployment_and_streams_only_when_asked():
    assert Settings().deployed is False
    assert Settings().streaming_enabled is False
    assert Settings(LESSONS_STREAMING="true").streaming_enabled is True


def test_vercel_never_streams_whatever_it_is_told():
    """There is no flag that could claim a stream on Vercel (decision 7)."""
    assert deployed(LESSONS_STREAMING="true").streaming_enabled is False
    assert hosted(LESSONS_STREAMING="true").streaming_enabled is True
```

In `server/tests/test_env_example.py`, replace:
```python
from pathlib import Path

from app.config import Settings
```
with:
```python
from pathlib import Path

import pytest

from app.config import Settings
```

In `server/tests/test_env_example.py`, replace:
```python
# The one setting that is never written by hand. Vercel sets it about itself,
# and it is what switches on the refusal-to-start check — so a line for it in
# the file people copy would be an invitation to make a laptop refuse to start.
# The file says so in prose, which this test checks for rather than against.
PLATFORM_SET = {"VERCEL"}
```
with:
```python
# The settings that are never written by hand. Vercel sets VERCEL about
# itself, and the root Dockerfile sets LESSONS_TARGET about the host image;
# each is what switches on the refusal-to-start check — so a line for either
# in the file people copy would be an invitation to make a laptop refuse to
# start. The file says so in prose, which this test checks for rather than
# against.
PLATFORM_SET = {"VERCEL", "LESSONS_TARGET"}
```

In `server/tests/test_env_example.py`, replace:
```python
def test_the_platform_variable_is_explained_rather_than_listed() -> None:
    # It is deliberately absent, and «absent» and «forgotten» look identical in
```
with:
```python
@pytest.mark.parametrize("name", sorted(PLATFORM_SET))
def test_the_platform_variable_is_explained_rather_than_listed(name: str) -> None:
    # It is deliberately absent, and «absent» and «forgotten» look identical in
```

In `server/tests/test_env_example.py`, replace:
```python
    assert "VERCEL" in text, "the example no longer says why VERCEL is not in it"
    assert not re.search(r"^#?\s*VERCEL=", text, re.MULTILINE), (
        "VERCEL is given as a variable to set; it is the platform's own, and "
        "setting it by hand makes a machine refuse to start"
```
with:
```python
    assert name in text, f"the example no longer says why {name} is not in it"
    assert not re.search(rf"^#?\s*{name}=", text, re.MULTILINE), (
        f"{name} is given as a variable to set; it is a deployment's statement "
        "about itself, and setting it by hand makes a machine refuse to start"
```


- [ ] **Step 2: Run it.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_deployment_config.py tests/test_api_docs.py tests/test_cold_start.py tests/test_env_example.py tests/test_compose.py
```
Expected: `13 failed, 55 passed`: nine of `test_deployment_config.py`'s new ten (the one asking the reworded sentences keeps the names they already had, and passes), `test_api_docs.py`'s host case, `test_cold_start.py`'s `asked-for` case, `test_env_example.py`'s `LESSONS_TARGET` case, and `test_compose.py`'s, whose exception names a setting `Settings` does not have yet.

- [ ] **Step 3: Green.** The settings, the app, the example and compose:

In `docker-compose.yml`, replace:
```yaml
      TRUSTED_PROXY_HOPS: ${TRUSTED_PROXY_HOPS:-0}
      # Deliberately absent: VERCEL is the platform's own, and set here it would
```
with:
```yaml
      TRUSTED_PROXY_HOPS: ${TRUSTED_PROXY_HOPS:-0}
      # WatchClass's stream, over Connect: this image serves HTTP/1.1, so
      # native gRPC is the host image's alone (the root Dockerfile).
      LESSONS_STREAMING: ${LESSONS_STREAMING:-false}
      # Deliberately absent: VERCEL is the platform's own, and set here it would
```

In `docker-compose.yml`, replace:
```yaml
      # switch on Vercel's refusal to start; HOST and PORT are read only by
      # `python -m app.main`, while the image's command binds 0.0.0.0:8000
      # itself; WEBHOOK_PATH is read by nothing.
```
with:
```yaml
      # switch on Vercel's refusal to start; LESSONS_TARGET is the host image's
      # statement about itself and switches on the same refusal; HOST and PORT
      # are read only by `python -m app.main` and `python -m app.host`, while
      # this image's command binds 0.0.0.0:8000 itself; WEBHOOK_PATH is read by
      # nothing.
```

In `server/.env.example`, replace:
```bash
# MIN_CLIENT_VERSION=

# --- A proxy for the Petersburg diary (optional) -------------------------------
```
with:
```bash
# MIN_CLIENT_VERSION=

# WatchClass, the stream of «this class changed» a phone holds open while the
# app is in front: a beta (docs/api.md, «v2: the contract»). Off unless set to
# true, and never on Vercel whatever this says, because nothing there outlives
# a response. On a host the streams are heard by this process alone, so run
# one instance.
# LESSONS_STREAMING=false

# --- A proxy for the Petersburg diary (optional) -------------------------------
```

In `server/.env.example`, replace:
```bash
# VERCEL_REGION: Vercel states them about the running deployment, and the
# server reads them from its environment alone (app/config.py, deployment()).
```
with:
```bash
# VERCEL_REGION: Vercel states them about the running deployment, and the
# server reads them from its environment alone (app/config.py, deployment()).
#
# Nor is LESSONS_TARGET, for the same reason as VERCEL: it is the host image's
# statement about itself, set by the root Dockerfile, and it switches on the
# same refusal. A local `python -m app.host` runs without it.
```

In `server/app/config.py`, replace:
```python
    github_read_token: str = ""

    # The oldest v2 client this server still answers, as the APK's versionCode
```
with:
```python
    github_read_token: str = ""

    # The host target's statement about itself, as VERCEL is Vercel's: the root
    # Dockerfile sets LESSONS_TARGET=host, and `python -m app.host` never does
    # (docs/specs/2026-10-05-server-v2-design.md, decisions 7 and 13). With it,
    # `get_settings` refuses a deployment that is missing what one needs,
    # exactly as on Vercel. Never set by hand on a laptop, where it would make
    # the SQLite default a refusal; CI's «Host» job and a local host run go
    # without it. "host" is the only value: anything else is refused at the
    # door, because a misspelt marker would otherwise leave a deployment on
    # every local default with nothing saying so.
    lessons_target: str = ""

    # WatchClass's stream, the host target's beta (decision 13): «this class
    # changed», as it happens. Off unless asked for, and never on Vercel,
    # whatever this says, because nothing there outlives a response.
    lessons_streaming: bool = False

    # The oldest v2 client this server still answers, as the APK's versionCode
```

In `server/app/config.py`, replace:
```python
    def behind_vercel(self) -> bool:
        return bool(self.vercel)
```
with:
```python
    def behind_vercel(self) -> bool:
        return bool(self.vercel)

    @property
    def behind_host(self) -> bool:
        """Whether the deployment says it is the host target (``LESSONS_TARGET=host``)."""
        return self.lessons_target.strip().lower() == "host"

    @property
    def deployed(self) -> bool:
        """Whether this process is a deployment that says so about itself:
        Vercel's ``VERCEL``, or a ``LESSONS_TARGET`` at all — a misspelt one
        included, so that `get_settings` names it rather than letting it pass
        for a laptop. A deployment refuses to start without what one needs and
        serves no API docs."""
        return self.behind_vercel or bool(self.lessons_target.strip())

    @property
    def streaming_enabled(self) -> bool:
        """Whether ``WatchClass`` streams here: asked for, and not on Vercel.

        Not tied to the host's marker: CI's «Host» job and a local host run
        stream without it, and a stream over Connect needs no HTTP/2 —
        native gRPC does, which is the server's business, not this flag's.
        """
        return self.lessons_streaming and not self.behind_vercel
```

In `server/app/config.py`, replace:
```python
                "DATABASE_URL is unset or empty, so the local SQLite default stands. A "
                "deployment has no disk to keep that file on, and `aiosqlite` is "
```
with:
```python
                "DATABASE_URL is unset or empty, so the local SQLite default stands. A "
                "deployment keeps nothing on a disk of its own - Vercel has none, and a "
                "container's goes with it - and `aiosqlite` is "
```

In `server/app/config.py`, replace:
```python
                "RUN_BOT is not false. Long polling cannot outlive a response here, and "
                "Telegram hands updates to one consumer: a loop started on a cold start "
                "takes them away from the webhook that is supposed to receive them."
```
with:
```python
                "RUN_BOT is not false. A deployment takes its updates from the webhook, "
                "and Telegram hands updates to one consumer: a polling loop started here "
                "takes them away from the webhook that is supposed to receive them - and "
                "on Vercel it dies with the response that started it."
```

In `server/app/config.py`, replace:
```python
                "for. Nothing raises: `resolve` falls back on purpose, so every class "
                "created here quietly gets Europe/Moscow instead of the zone you meant."
            )
```
with:
```python
                "for. Nothing raises: `resolve` falls back on purpose, so every class "
                "created here quietly gets Europe/Moscow instead of the zone you meant."
            )

        target = self.lessons_target.strip()
        if target and not self.behind_host:
            problems.append(
                f"LESSONS_TARGET is {target!r}, which names no target. The one value it "
                "takes is `host`, which the root Dockerfile sets; a local run leaves it "
                "unset."
            )
        if target and self.behind_vercel:
            problems.append(
                "LESSONS_TARGET is set on Vercel. A deployment is one target: Vercel says "
                "so about itself with VERCEL, and the host image with LESSONS_TARGET."
            )
```

In `server/app/config.py`, replace:
```python
    # Only where the platform says so about itself. Guessing "this looks like
    # production" anywhere else would eventually refuse to start on somebody's
    # laptop, which is a worse failure than the one this prevents. A VPS
    # deployment is configured by hand from docs/deploy.md and is not covered.
    if settings.behind_vercel:
```
with:
```python
    # Only where the deployment says so about itself: Vercel's VERCEL, or the
    # host image's LESSONS_TARGET. Guessing "this looks like production"
    # anywhere else would eventually refuse to start on somebody's laptop,
    # which is a worse failure than the one this prevents. A compose or VPS
    # deployment run from server/Dockerfile is configured by hand from
    # docs/deploy.md and is not covered.
    if settings.deployed:
```

In `server/app/main.py`, replace:
```python
_start_sentry()


def _report_bot_exit(task: asyncio.Task) -> None:
```
with:
```python
_start_sentry()

# The class-changed bus `WatchClass` streams from, where streaming is on
# (app/watch.py). At import, like Sentry, so that the first write of the first
# request is heard; nowhere else, so Vercel, the suite and every run without
# LESSONS_STREAMING pay nothing for it.
if get_settings().streaming_enabled:
    from app import watch

    watch.attach()


def _report_bot_exit(task: asyncio.Task) -> None:
```

In `server/app/main.py`, replace:
```python
# the production server (#200): nobody there needs them — the app is written
# against docs/api.md, not against a schema it fetches — and each is surface.
# The signal is the one `get_settings` refuses to start on, Vercel's own
# `VERCEL`, never a guess at "this looks like production". A compose or VPS
# deployment keeps them, as it keeps every other local default.
_api_docs = not get_settings().behind_vercel
```
with:
```python
# a deployment (#200): nobody there needs them — the app is written against
# docs/api.md, not against a schema it fetches — and each is surface. The
# signal is the one `get_settings` refuses to start on, Vercel's own `VERCEL`
# or the host image's `LESSONS_TARGET`, never a guess at "this looks like
# production". A compose or VPS deployment keeps them, as it keeps every other
# local default.
_api_docs = not get_settings().deployed
```


- [ ] **Step 4: Run it again**, with the bus's tests and the lifespan's. The command of Step 2 with `tests/test_watch.py tests/test_startup.py` added. Expected: `102 passed`.

- [ ] **Step 5: The gates.** As Task 2's. Expected: `All checks passed!` and `Success: no issues found in 239 source files`.

- [ ] **Step 6: Commit.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3c-t3.txt`:
```text
Let the host image say it is a deployment, and switch streaming on apart from it

LESSONS_TARGET=host is the host image's statement about itself, as
VERCEL is Vercel's, and get_settings now refuses either deployment
without what one needs; any other value is refused too, so a misspelt
marker cannot leave a deployment on the local defaults, and both at once
are one target too many. The API docs are off on both. Two sentences of
the refusal held for Vercel alone and are reworded to hold on both.
LESSONS_STREAMING=true switches WatchClass's stream on anywhere but
Vercel, without the marker, because CI's host job and a local host run
without it; app.main attaches the bus only there. The suite forces it off
and drops the marker, since the build console runs the host and the
suite on one machine and an exported switch would hang every stream the
gate test opens.

Not covered: nothing streams yet; the next task serves WatchClass.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230 && git add server/app/config.py server/app/main.py server/.env.example docker-compose.yml server/tests/conftest.py server/tests/test_deployment_config.py server/tests/test_api_docs.py server/tests/test_cold_start.py server/tests/test_env_example.py server/tests/test_compose.py && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3c-t3.txt
```

---

### 3c Task 4: `WatchClass`: a stream that holds no session, served by `call.stream`

Decisions 4, 7 and 13; Rulings 143 to 145 and 147. The gate test and the mount's tests stand as they are; `WatchClass` moves from `HANDLERS` to `STREAMS` and the gate test follows it.

**Files:**
- Create: `server/tests/test_v2_watch.py`
- Rewrite: `server/app/rpc/watch.py`
- Modify: `server/app/rpc/call.py`, `server/app/rpc/handlers.py`, `server/app/rpc/__init__.py`, `proto/lessons/v2/watch.proto`, `server/app/contract/**` (regenerated), `server/tests/test_v2_reads.py`

**Interfaces:**
- Consumes: Task 1's bus (`watching`, `revision`, `changed_at`, `listening`, `BOOT`, `publish`, `watchers`), Task 3's `Settings.behind_vercel` and the bus attached by a fixture; `gate.admit(method, session, settings, headers) -> gate.Admitted`; `values.maybe_instant`; `contextlib.aclosing`.
- Produces:
  - `call.Stream(method, settings, headers, peer, class_id, device_id, role)` with `async recheck() -> None`; `call.stream(method, request, *, headers, peer) -> AsyncIterator[Any]`;
  - `handlers.StreamHandler`, `handlers.STREAMS: dict[str, StreamHandler]` holding `lessons.v2.WatchService/WatchClass`;
  - `rpc/watch.HEARTBEAT_SECONDS = 30.0`, `watch_class(call: Stream, request) -> AsyncGenerator[WatchClassResponse, None]`; `STREAMING` and `NOT_HERE` as 3a left them.

- [ ] **Step 1: Red.** Create `server/tests/test_v2_watch.py`, and let the gate test walk the streams:

In `server/tests/test_v2_reads.py`, replace:
```python
The gate's behaviour is asked of every method in ``HANDLERS``, so a method a
later task registers is held by the same test the moment it is served.
```
with:
```python
The gate's behaviour is asked of every method in ``HANDLERS`` and ``STREAMS``,
so a method a later task registers is held by the same test the moment it is
served.
```

In `server/tests/test_v2_reads.py`, replace:
```python
from app.providers.diary.registry import KEYS
from app.rpc.handlers import HANDLERS
```
with:
```python
from app.providers.diary.registry import KEYS
from app.rpc.handlers import HANDLERS, STREAMS
```

In `server/tests/test_v2_reads.py`, replace:
```python
def _served() -> list[str]:
    return sorted(HANDLERS)
```
with:
```python
def _served() -> list[str]:
    return sorted([*HANDLERS, *STREAMS])
```

Write `server/tests/test_v2_watch.py` whole:
```python
"""``WatchClass``: the class's revision on open, on every change, and on a heartbeat.

A stream that works never ends, and httpx's ASGI transport answers only once
an answer has ended — so each stream here is driven through the app message
by message, as an HTTP server drives it, over Connect's streaming protocol and,
once, over native gRPC with the scope an HTTP/2 server builds. The bus is
attached for each test, as ``app.main`` attaches it where streaming is on, and
the heartbeat is put out of reach, or to a moment where a test waits for one, so
that what the design promises
(``docs/specs/2026-10-05-server-v2-design.md``, decision 13) is asked in
seconds: a revision on open, a new one for a change any shell makes, the same
one again when nothing changes, the gate asked again each time, and nothing
held between.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import struct
from datetime import date
from typing import Any

import pytest
from connectrpc.errors import ConnectError
from sqlalchemy import update

from app import watch
from app.contract.lessons.v2.homework_pb import CreateHomeworkRequest, Homework
from app.contract.lessons.v2.me_pb import CreateTaskRequest, Task
from app.contract.lessons.v2.school_class_pb import DeleteClassRequest
from app.contract.lessons.v2.watch_pb import WatchClassResponse
from app.db import SessionLocal, engine
from app.models import DeviceToken, SchoolClass
from app.rpc import watch as rpc_watch
from app.security import hash_token
from app.services import homework as homework_service

PATH = "/api/rpc/lessons.v2.WatchService/WatchClass"
#: The heartbeat of a test that does not wait for one: a message that comes
#: within PATIENCE is a change's, never the heartbeat's.
QUIET = 30.0
#: The heartbeat of a test that waits for one: a moment.
BEAT = 0.3
#: How long a test waits for a message it is owed: generous, because a loaded
#: CI worker is slow, and a stream that works answers in milliseconds.
PATIENCE = 5.0


@pytest.fixture
def streaming(monkeypatch):
    """Streaming on, as on a host: the bus attached, and no heartbeat in sight."""
    already = watch.listening()
    watch.attach()
    monkeypatch.setattr(rpc_watch, "HEARTBEAT_SECONDS", QUIET)
    yield watch
    if not already:
        watch.detach()


@pytest.fixture
def beating(streaming, monkeypatch):
    """Streaming on, with a heartbeat a test can wait for."""
    monkeypatch.setattr(rpc_watch, "HEARTBEAT_SECONDS", BEAT)
    return streaming


class _Watcher:
    """One ``WatchClass`` call, driven through the app as a server drives it."""

    def __init__(self, token: str | None, *, grpc: bool = False) -> None:
        self.token, self.grpc = token, grpc
        self.frames: asyncio.Queue[tuple[int, bytes] | None] = asyncio.Queue()
        self.status: int | None = None
        self.trailers: dict[bytes, bytes] = {}
        self.error: dict[str, Any] | None = None
        self._gone = asyncio.Event()
        self._task: asyncio.Task[None] | None = None

    async def __aenter__(self) -> _Watcher:
        from app.main import app

        payload = b"" if self.grpc else b"{}"
        body = struct.pack(">BI", 0, len(payload)) + payload
        headers = [
            (b"content-type", b"application/grpc" if self.grpc else b"application/connect+json")
        ]
        if self.grpc:
            headers.append((b"te", b"trailers"))
        if self.token is not None:
            headers.append((b"authorization", f"Bearer {self.token}".encode()))
        scope = {
            "type": "http",
            "http_version": "2" if self.grpc else "1.1",
            "method": "POST",
            "scheme": "http",
            "path": PATH,
            "raw_path": PATH.encode(),
            "root_path": "",
            "query_string": b"",
            "headers": headers,
            "client": ("203.0.113.9", 52144),
            "server": ("test", 80),
            "extensions": {"http.response.trailers": {}},
        }
        delivered = False

        async def receive() -> dict[str, Any]:
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": body, "more_body": False}
            await self._gone.wait()
            return {"type": "http.disconnect"}

        buffer = b""

        async def send(message: dict[str, Any]) -> None:
            nonlocal buffer
            if message["type"] == "http.response.start":
                self.status = message["status"]
            elif message["type"] == "http.response.body":
                buffer += message.get("body", b"")
                while len(buffer) >= 5:
                    flags, length = struct.unpack(">BI", buffer[:5])
                    if len(buffer) < 5 + length:
                        break
                    frame, buffer = buffer[5 : 5 + length], buffer[5 + length :]
                    await self.frames.put((flags, frame))
                if not message.get("more_body", False) and not self.grpc:
                    await self.frames.put(None)
            elif message["type"] == "http.response.trailers":
                self.trailers = dict(message["headers"])
                await self.frames.put(None)

        self._task = asyncio.create_task(app(scope, receive, send))
        return self

    async def __aexit__(self, *exc: object) -> None:
        self._gone.set()
        assert self._task is not None
        try:
            await asyncio.wait_for(asyncio.shield(self._task), 1)
        except TimeoutError:
            # The stream notices a client gone only when it next sends, which
            # with a quiet heartbeat is long after the test: cancel it, as a
            # server shutting down does. connectrpc answers a cancellation by
            # ending the stream with CANCELED, raised out of the app.
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError, ConnectError):
                await self._task

    async def next(self, timeout: float = 5.0) -> WatchClassResponse | None:
        """The next message, or ``None`` when the stream has ended."""
        item = await asyncio.wait_for(self.frames.get(), timeout)
        if item is None:
            return None
        flags, frame = item
        if flags & 0x02:
            # Connect's end-of-stream message: the error, if the stream ended on one.
            self.error = json.loads(frame).get("error")
            return await self.next(timeout)
        if self.grpc:
            return WatchClassResponse.from_binary(frame)
        return WatchClassResponse.from_json(frame)

    async def end(self, within: float = PATIENCE) -> list[WatchClassResponse]:
        """Every message until the stream ends, which it must ``within`` seconds:
        a heartbeat may run before the write a test waits on has landed."""
        messages = []
        async with asyncio.timeout(within):
            while (message := await self.next(within)) is not None:
                messages.append(message)
        return messages

    def ended_with(self) -> tuple[str | None, str | None]:
        """The code and the reason the stream ended with, on either protocol."""
        if self.grpc:
            status = self.trailers.get(b"grpc-status", b"").decode()
            return status, None
        assert self.error is not None, "the stream ended without an error"
        detail = self.error["details"][0]
        return self.error["code"], detail.get("debug", {}).get("reason")


async def test_the_first_message_is_the_class_s_revision_now(
    streaming, v2_tokens, school_class
) -> None:
    async with _Watcher(v2_tokens["unlinked"]) as stream:
        first = await stream.next()
        assert first is not None
        assert first.revision == streaming.revision(school_class.id)
        assert first.revision.startswith(streaming.BOOT)


@pytest.mark.parametrize("shell", ["v1", "v2", "bot"])
async def test_a_change_any_shell_makes_wakes_the_watcher_with_a_new_revision(
    streaming, v2, v2_tokens, school_class, shell
) -> None:
    async with _Watcher(v2_tokens["viewer"]) as stream:
        first = await stream.next()
        if shell == "v1":
            written = await v2.http.put(
                "/api/v1/homework",
                json={"due_date": "2026-09-15", "subject": "Алгебра", "text": "№ 1–5"},
                headers={"Authorization": f"Bearer {v2_tokens['editor']}"},
            )
            assert written.status_code == 200, written.text
        elif shell == "v2":
            homework = Homework(due_date="2026-09-15", subject="Алгебра", text="№ 1–5")
            written = await v2.connect(
                "HomeworkService/CreateHomework",
                CreateHomeworkRequest(homework=homework),
                token=v2_tokens["editor"],
            )
            assert written.status == 200, written.body
        else:
            async with SessionLocal() as update_session:
                klass = await update_session.get(SchoolClass, school_class.id)
                await homework_service.create(
                    update_session, klass, 2002, date(2026, 9, 15), "Алгебра", "№ 1–5"
                )
                await update_session.commit()
        changed = await stream.next(timeout=PATIENCE)
        assert changed is not None and first is not None
        assert changed.revision != first.revision
        assert changed.revision == streaming.revision(school_class.id)
        assert changed.has_field("changed_at")


async def test_a_quiet_class_hears_its_own_revision_again_at_the_heartbeat(
    beating, v2, v2_tokens, school_class
) -> None:
    """A pupil's own task is no change of the class: the next message is the
    heartbeat, saying the same revision."""
    async with _Watcher(v2_tokens["viewer"]) as stream:
        first = await stream.next()
        written = await v2.connect(
            "MeService/CreateTask",
            CreateTaskRequest(task=Task(title="Купить тетрадь")),
            token=v2_tokens["viewer"],
        )
        assert written.status == 200, written.body
        again = await stream.next(timeout=PATIENCE)
        assert again is not None and first is not None
        assert again.revision == first.revision


async def test_a_burst_of_changes_is_one_message_with_the_last_revision(
    streaming, v2_tokens, school_class
) -> None:
    async with _Watcher(v2_tokens["viewer"]) as stream:
        await stream.next()
        streaming.publish([school_class.id])
        streaming.publish([school_class.id])
        latest = streaming.revision(school_class.id)
        woken = await stream.next(timeout=PATIENCE)
        assert woken is not None and woken.revision == latest
        with pytest.raises(TimeoutError):
            await stream.next(timeout=0.5)


async def test_a_device_revoked_while_it_watches_is_refused_at_the_next_heartbeat(
    beating, v2_tokens, session
) -> None:
    """The revocation writes no window table and wakes nobody; the heartbeat's
    gate is what finds it."""
    token = v2_tokens["viewer"]
    async with _Watcher(token) as stream:
        await stream.next()
        await session.execute(
            update(DeviceToken)
            .where(DeviceToken.token_hash == hash_token(token))
            .values(revoked=True)
        )
        await session.commit()
        await stream.end()
        assert stream.ended_with() == ("unauthenticated", "DEVICE_TOKEN_INVALID")


async def test_a_class_deleted_ends_its_streams_at_once(
    streaming, v2, v2_tokens, school_class, monkeypatch
) -> None:
    """The delete is a change of the class: every watcher's gate runs at once
    and finds its device gone with the class, long before any heartbeat."""
    async with _Watcher(v2_tokens["viewer"]) as stream:
        await stream.next()
        deleted = await v2.connect(
            "ClassService/DeleteClass",
            DeleteClassRequest(confirmation=school_class.name),
            token=v2_tokens["owner"],
        )
        assert deleted.status == 200, deleted.body
        assert await stream.next(timeout=PATIENCE) is None
        assert stream.ended_with() == ("unauthenticated", "DEVICE_TOKEN_INVALID")


async def test_a_watcher_holds_no_connection_between_its_messages(
    streaming, v2_tokens, school_class
) -> None:
    """One session held per watcher would hold a pooled connection for as long
    as a phone is in the foreground; the pool is 5 + 10 (decision 13)."""
    async with _Watcher(v2_tokens["viewer"]) as one, _Watcher(v2_tokens["editor"]) as two:
        await one.next()
        await two.next()
        assert engine.pool.checkedout() == 0
        streaming.publish([school_class.id])
        await one.next(timeout=PATIENCE)
        await two.next(timeout=PATIENCE)
        assert engine.pool.checkedout() == 0


async def test_a_watcher_that_goes_away_is_forgotten(beating, v2_tokens, school_class) -> None:
    """With a heartbeat due, the stream learns at its next message that its
    client is gone and closes the handler; a server that cancels the task
    instead reaches the same ``finally``, which forgets the watcher."""
    async with _Watcher(v2_tokens["viewer"]) as stream:
        await stream.next()
        assert beating.watchers(school_class.id) == 1
    assert beating.watchers(school_class.id) == 0


@pytest.mark.parametrize("vercel", ["1", ""], ids=["on-vercel", "streaming-off"])
async def test_where_streaming_is_off_the_gate_runs_and_the_feature_is_refused(
    v2_tokens, served_settings, monkeypatch, vercel, request
) -> None:
    """On Vercel even with the bus attached: no flag can claim a stream there."""
    if vercel:
        request.getfixturevalue("streaming")
    monkeypatch.setattr(served_settings, "vercel", vercel)
    async with _Watcher(None) as anonymous:
        assert await anonymous.next() is None
        assert anonymous.ended_with() == ("unauthenticated", "DEVICE_TOKEN_INVALID")
    async with _Watcher(v2_tokens["viewer"]) as refused:
        assert await refused.next() is None
        assert refused.ended_with() == ("unimplemented", "FEATURE_UNSUPPORTED")


async def test_over_native_grpc_the_revision_comes_in_binary_and_the_end_in_trailers(
    streaming, v2, v2_tokens, school_class
) -> None:
    async with _Watcher(v2_tokens["viewer"], grpc=True) as stream:
        first = await stream.next()
        assert first is not None
        assert first.revision == streaming.revision(school_class.id)
        assert stream.status == 200
        deleted = await v2.connect(
            "ClassService/DeleteClass",
            DeleteClassRequest(confirmation=school_class.name),
            token=v2_tokens["owner"],
        )
        assert deleted.status == 200, deleted.body
        assert await stream.next(timeout=PATIENCE) is None
        assert stream.ended_with() == ("16", None)
```


- [ ] **Step 2: Run it.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_v2_watch.py tests/test_v2_reads.py
```
Expected: the collection error `ImportError: cannot import name 'STREAMS' from 'app.rpc.handlers'`.

- [ ] **Step 3: Green: the stream's call, its registry and its adapter.** The first replacement in `server/app/rpc/handlers.py` is its module docstring's sentence as 3b-8's Task 4 leaves it (`1c0a362`, on `server-v2/3b-8`). If 3b-8's review rewrote it again, make the same change to the sentence as it stands: it no longer says 3a served `WatchClass`'s refusal, and says instead that `WatchClass`, the one stream, is in `STREAMS`, which `call.stream` serves (3c). The two after it, the import and `HANDLERS`' last lines, 3b-8 leaves as they are.

In `server/app/rpc/__init__.py`, replace:
```python
from app.api.deps import peer_host
from app.rpc.call import invoke
```
with:
```python
from app.api.deps import peer_host
from app.rpc.call import invoke, stream
```

In `server/app/rpc/__init__.py`, replace:
```python
    async def call(self: object, request: Any, ctx: Any) -> Any:
        # A stream's handler answers once, today always with a refusal
        # (`rpc/watch.py`); 3c's host yields from it instead.
        yield await invoke(
```
with:
```python
    def call(self: object, request: Any, ctx: Any) -> Any:
        # The generator itself, not one wrapped in another: connectrpc closes
        # what it is handed when the client goes, and a wrapper's close would
        # not reach the stream inside it (`call.stream` says why that matters).
        return stream(
```

In `server/app/rpc/call.py`, replace:
```python
- ``api.deps.touch_last_seen`` — the device stays seen.

Each has a test over v2.
"""
```
with:
```python
- ``api.deps.touch_last_seen`` — the device stays seen.

Each has a test over v2.

**A stream holds no session** (decision 13). :func:`stream` serves a
server-streaming method: the gate runs in a scope of its own, committed and
closed before the first message, and again in another each time the handler
calls :meth:`Stream.recheck`. One scope held per watcher would hold a pooled
connection for as long as a phone is in the foreground, and the pool is
5 + 10. A refusal from any of those gates ends the stream with the gate's own
answer, worded by the one table as every refusal is.
"""
```

In `server/app/rpc/call.py`, replace:
```python
import logging
from collections.abc import Awaitable, Callable, Sequence
```
with:
```python
import logging
from collections.abc import AsyncIterator, Awaitable, Callable, Sequence
from contextlib import aclosing
```

In `server/app/rpc/call.py`, replace:
```python
from app.rpc.errors import connect_error
from app.rpc.handlers import HANDLERS
```
with:
```python
from app.rpc.errors import connect_error
from app.rpc.handlers import HANDLERS, STREAMS
```

In `server/app/rpc/call.py`, replace:
```python
            raise RuntimeError(f"{self.method.key} asked for a device it does not take")
        return self.device, self.school_class
```
with:
```python
            raise RuntimeError(f"{self.method.key} asked for a device it does not take")
        return self.device, self.school_class


@dataclass
class Stream:
    """What a streaming handler is handed: the caller the gate admitted, and no session."""

    method: Method
    settings: Settings
    # Out of the repr, as `Call`'s: the lines include `Authorization: Bearer …`.
    headers: Sequence[tuple[str, str]] = field(repr=False)
    peer: str | None
    class_id: int
    device_id: int
    role: Role | None = None

    async def recheck(self) -> None:
        """The gate again, in a scope of its own, or the refusal it makes.

        On every event and every heartbeat: a device revoked, or its class
        deleted with its devices, since the last check is refused here, as a
        unary call would be, and the stream ends with that refusal.
        """
        self.settings, admitted = await _admitted(self.method, self.headers)
        self.device_id, self.class_id = _device_and_class(self.method, admitted)
        self.role = admitted.role


def _device_and_class(method: Method, admitted: gate.Admitted) -> tuple[int, int]:
    if admitted.device is None or admitted.school_class is None:
        raise RuntimeError(f"{method.key} streams to a caller that is no device")
    return admitted.device.id, admitted.school_class.id


async def _admitted(
    method: Method, headers: Sequence[tuple[str, str]]
) -> tuple[Settings, gate.Admitted]:
    """The gate in a scope of its own: committed — the device stays seen — and closed."""
    async with di.container()() as scope:
        session = await scope.get(AsyncSession)
        settings = await scope.get(Settings)
        try:
            admitted = await gate.admit(method, session, settings, headers)
            await session.commit()
        except Exception:
            await session.rollback()
            raise
        return settings, admitted


async def stream(
    method: Method,
    request: Message,
    *,
    headers: Sequence[tuple[str, str]],
    peer: str | None,
) -> AsyncIterator[Any]:
    """Serve one call of a streaming ``method``, message by message, or raise
    the ``ConnectError`` it ends with."""
    handler = STREAMS.get(method.key)
    if handler is None:
        raise ConnectError(Code.UNIMPLEMENTED, NOT_IMPLEMENTED)
    try:
        settings, admitted = await _admitted(method, headers)
        device_id, class_id = _device_and_class(method, admitted)
        call = Stream(
            method=method,
            settings=settings,
            headers=headers,
            peer=peer,
            class_id=class_id,
            device_id=device_id,
            role=admitted.role,
        )
        # `aclosing`, because closing this generator — the client gone, the
        # server shutting down — does not close the one it is iterating: the
        # handler's own `finally`, which forgets the watcher, would otherwise
        # wait for the garbage collector.
        async with aclosing(handler(call, request)) as messages:
            async for message in messages:
                yield message
    except ConnectError:
        raise
    except Exception as failure:
        raise connect_error(failure) from None
```

In `server/app/rpc/handlers.py`, replace:
```python
served ``WatchClass``'s refusal, ``GetMe``, ``GetDiaryCapabilities``,
``CreateDevice`` and ``GetScheduleWindow``; 3b filled the rest in, one service
at a time (``docs/specs/2026-10-05-server-v2-3b-plan.md``), and since 3b-8
every unary method of the contract is here. ``WatchClass``'s stream is 3c's.
```
with:
```python
served ``GetMe``, ``GetDiaryCapabilities``, ``CreateDevice`` and
``GetScheduleWindow``; 3b filled the rest in, one service at a time
(``docs/specs/2026-10-05-server-v2-3b-plan.md``), and since 3b-8 every unary
method of the contract is here. ``WatchClass``, the one stream, is in
:data:`STREAMS`, which ``call.stream`` serves (3c).
```

In `server/app/rpc/handlers.py`, replace:
```python
from collections.abc import Awaitable, Callable
from typing import Any
```
with:
```python
from collections.abc import AsyncGenerator, Awaitable, Callable
from typing import Any
```

In `server/app/rpc/handlers.py`, replace:
```python
    "lessons.v2.TimetableService/ImportTimetable": timetable.import_timetable,
    "lessons.v2.WatchService/WatchClass": watch.watch_class,
```
with:
```python
    "lessons.v2.TimetableService/ImportTimetable": timetable.import_timetable,
}

#: A streaming handler: ``async def handler(call: Stream, request) ->
#: AsyncGenerator[<Response>, None]``, served by ``call.stream`` rather than
#: ``invoke``: the gate runs before its first message and again whenever it
#: asks, and no session outlives one check (decision 13).
StreamHandler = Callable[[Any, Any], AsyncGenerator[Any, None]]

#: Keyed as ``HANDLERS`` is. ``WatchClass`` is the contract's one stream.
STREAMS: dict[str, StreamHandler] = {
    "lessons.v2.WatchService/WatchClass": watch.watch_class,
```


- [ ] **Step 4: Green: the stream.**

Write `server/app/rpc/watch.py` whole:
```python
"""``WatchService``: the class changing, as it happens — a beta of the host target.

``WatchClass`` streams revisions, never data (decision 13 of
``docs/specs/2026-10-05-server-v2-design.md``): on open, the class's revision
now; then a new one whenever a commit changes the class — whichever shell made
it, v1, v2 or the bot, as long as this process made it (``app/watch.py``); and
every :data:`HEARTBEAT_SECONDS` the one it has, so that a connection that quiet
is known to be alive and nothing between closes it as idle. Before every
message after the first the gate runs again (``call.Stream.recheck``): a
device revoked, or its class deleted with its devices, ends the stream with
``UNAUTHENTICATED`` / ``DEVICE_TOKEN_INVALID`` at the next change or
heartbeat. Changes made while the watcher is busy are one message, read as it
is sent, so none is lost.

Where streaming is off — on Vercel always, and wherever ``LESSONS_STREAMING``
is not true — the method says the feature is not here, after the gate has
checked the caller like any other (decision 7).
"""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import AsyncGenerator
from typing import TYPE_CHECKING

from app import watch as bus
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.watch_pb import WatchClassRequest, WatchClassResponse
from app.rpc import values
from app.rpc.errors import Refusal

if TYPE_CHECKING:
    from app.rpc.call import Stream

#: ``FEATURE_UNSUPPORTED``'s ``feature`` for a server capability, as
#: ``errors.proto`` allows beside a ``DiaryFeature`` name.
STREAMING = "streaming"

NOT_HERE = "Streaming is served by the host target, not by this deployment"

#: How long a stream stays quiet before it says the same revision again. Under
#: the idle timeouts a proxy or a mobile network keeps — Envoy's own is five
#: minutes — and long enough that a class's watchers cost the database one
#: gate's reads each per half a minute.
HEARTBEAT_SECONDS = 30.0


async def watch_class(
    call: Stream, request: WatchClassRequest
) -> AsyncGenerator[WatchClassResponse, None]:
    """The class's revision now, then again whenever it changes or it is time."""
    if call.settings.behind_vercel or not bus.listening():
        raise Refusal(ErrorReason.FEATURE_UNSUPPORTED, NOT_HERE, feature=STREAMING)
    with bus.watching(call.class_id) as woken:
        while True:
            yield _now(call.class_id)
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(woken.wait(), HEARTBEAT_SECONDS)
            woken.clear()
            await call.recheck()


def _now(class_id: int) -> WatchClassResponse:
    return WatchClassResponse(
        revision=bus.revision(class_id),
        changed_at=values.maybe_instant(bus.changed_at(class_id)),
    )
```


- [ ] **Step 5: What the contract promises, in a comment.** In `proto/lessons/v2/watch.proto`:

In `proto/lessons/v2/watch.proto`, replace:
```proto
  // the schedule window as it already does. No REST binding, because REST
  // cannot carry a stream. A target without streaming answers
  // FEATURE_UNSUPPORTED.
```
with:
```proto
  // the schedule window as it already does. The first message is the class's
  // revision now; another follows whenever the class changes, and the same
  // one again at least every thirty seconds while nothing does, so a revision
  // the phone already has means nothing changed. No REST binding, because
  // REST cannot carry a stream. A target without streaming answers
  // FEATURE_UNSUPPORTED; a device revoked, or its class deleted, ends the
  // stream with DEVICE_TOKEN_INVALID.
```

In `proto/lessons/v2/watch.proto`, replace:
```proto
message WatchClassResponse {
  // Changes whenever the class does. Opaque: compare it, never parse it.
```
with:
```proto
message WatchClassResponse {
  // Changes whenever the class does, and when the host restarts. Opaque:
  // compare it, never parse it.
```

In `proto/lessons/v2/watch.proto`, replace:
```proto
  string revision = 1;
  google.protobuf.Timestamp changed_at = 2;
```
with:
```proto
  string revision = 1;
  // When this host last saw the class change; unset when it has seen no
  // change since it started.
  google.protobuf.Timestamp changed_at = 2;
```

Then, from `$WT`, `buf lint`, `buf generate` and `buf breaking`. Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\breaking3c.sh`:
```bash
#!/bin/sh
# buf breaking for 3c, from a file: the shell refuses `.git#ref` on a command line.
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230 && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe breaking --against '.git#ref=origin/main'
```
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230 && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe lint && /c/Users/lumen/AppData/Local/Temp/contract-plan-scratch/bin/buf.exe generate && git status --short server/app/contract && sh C:/Users/lumen/.claude/jobs/c9e2d980/tmp/breaking3c.sh
```
Expected: nothing from `lint`; `generate` changes `server/app/contract/lessons/v2/watch_connect.py` (the method's docstring, three times) and `watch_pb.py` (the two fields' comments) and nothing else; nothing from `breaking`.

- [ ] **Step 6: Run it again**, with every test of the mount, the call, the gate, the contract and the sweep.
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_v2_watch.py tests/test_watch.py tests/test_v2_reads.py tests/test_rpc_mount.py tests/test_rpc_call.py tests/test_rpc_gate.py tests/test_contract.py tests/test_contract_json.py tests/test_contract_mirror.py tests/test_v2_no_echo.py tests/test_service_layering.py tests/test_rpc_errors.py tests/test_rest.py
```
Expected: `429 passed`, in about three minutes: `421` on `a434c9c`, and 3b-8's four methods add four cases each to the gate test and the sweep. `test_v2_watch.py` alone takes about fifteen seconds: a stream still open when its test ends is given a second to end, then cancelled, as a server shutting down cancels it.

- [ ] **Step 7: The gates.** As Task 2's. Expected: `All checks passed!` and `Success: no issues found in 239 source files`.

- [ ] **Step 8: Commit.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3c-t4.txt`:
```text
Stream a class's revision to a phone, asking the gate again before each one

WatchClass now streams where the bus is attached and the target is not
Vercel: the class's revision on open, a new one whenever a commit
changes the class, and the same one every thirty seconds, so a quiet
connection is known to be alive. call.stream serves it beside invoke,
from a registry of its own: the gate runs in a dishka scope of its own,
committed and closed, before the first message and before every one
after it, so a revoked phone or a deleted class ends the stream with
DEVICE_TOKEN_INVALID and no watcher holds a pooled connection while it
waits. The handler's generator is closed with the stream, so a phone
that goes away is forgotten at once. watch.proto says so in comments,
regenerated. Off where streaming is off, as before, after the gate.

Not covered: no host serves it over HTTP/2 yet; the next task's.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230 && git add server/app/rpc/call.py server/app/rpc/handlers.py server/app/rpc/__init__.py server/app/rpc/watch.py proto/lessons/v2/watch.proto server/app/contract server/tests/test_v2_watch.py server/tests/test_v2_reads.py && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3c-t4.txt
```

---

### 3c Task 5: `app/host.py`: the root, the Envoy correction, the launcher

Decision 13 («what runs», «who sets the marker»); Rulings 151 to 154; #395. Nothing here needs pyvoy or hypercorn installed: the tests drive the ASGI app, a configuration shaped as pyvoy writes it, and a launcher that refuses before it imports either.

**Files:**
- Create: `server/app/host.py`, `server/tests/test_host.py`
- Modify: `server/app/main.py`, `server/app/rpc/__init__.py`, `server/tests/test_cold_start.py`, `server/tests/test_rpc_mount.py`

**Interfaces:**
- Consumes: `app.main.app`, `main.mount_v2`; `rpc._Services`; `config.get_settings`, `DeploymentNotConfigured`, `Settings.host` and `.port`; pyvoy's `PyvoyServer(app, *, address, port, worker_threads, lifespan, content_encodings)` with `get_envoy_config()`, `listener_address`, `wait()` and its async context; hypercorn's `serve(app, config, *, shutdown_trigger, mode)` and `Config`.
- Produces: `host.GRPC_ROOT`, `ENTRY = "app.host:application"`, `SERVERS = ("pyvoy", "hypercorn")`; `application(scope, receive, send)`; `envoy_config(config: dict[str, Any]) -> dict[str, Any]`; `main(argv: list[str] | None = None) -> None`; `rpc.is_native_grpc(scope) -> bool`; `app.state.v2_services`, the mounted `_Services` or `None`.

- [ ] **Step 1: Red.** Create `server/tests/test_host.py`, and the two tests that hold what the host leans on:

In `server/tests/test_cold_start.py`, replace:
```python
bus = sys.modules.get("app.watch")
sentry = sorted(name for name in sys.modules if name.partition(".")[0] == "sentry_sdk")
```
with:
```python
bus = sys.modules.get("app.watch")
host = sorted(
    name
    for name in sys.modules
    if name == "app.host" or name.partition(".")[0] in ("pyvoy", "hypercorn", "envoy")
)
sentry = sorted(name for name in sys.modules if name.partition(".")[0] == "sentry_sdk")
```

In `server/tests/test_cold_start.py`, replace:
```python
            "webhook": "app.api.telegram" in sys.modules,
            "streaming": bool(bus is not None and bus.listening()),
        }
```
with:
```python
            "webhook": "app.api.telegram" in sys.modules,
            "streaming": bool(bus is not None and bus.listening()),
            "host": host,
        }
```

In `server/tests/test_cold_start.py`, replace:
```python
def test_the_probe_sees_aiogram_where_it_is():
    """Held here rather than trusted: a probe that could not see aiogram would
```
with:
```python
@pytest.mark.parametrize("settings", [_LOCAL, _VERCEL], ids=["webhook-unmounted", "vercel"])
def test_the_api_cold_start_carries_nothing_of_the_host_target(settings):
    """``app.main`` is Vercel's function, and Vercel's lock has neither pyvoy
    nor hypercorn: an import of either from the API's path would be a
    ``ModuleNotFoundError`` on every cold start. The host's server is
    ``app.host``'s alone, and only its own ``main`` imports it."""
    found = _import_in_a_fresh_interpreter("app.main", settings)
    assert found["host"] == []
    hosted = _import_in_a_fresh_interpreter("app.host", _LOCAL)
    assert hosted["host"] == ["app.host"]


def test_the_probe_sees_aiogram_where_it_is():
    """Held here rather than trusted: a probe that could not see aiogram would
```

Write `server/tests/test_host.py` whole:
```python
"""The host target (``app/host.py``), asked in-process: its root, its Envoy, its door.

What only a running server can show — HTTP/2 itself, a native gRPC client, a
stream that outlives a response — is ``test_host_live.py``'s, against the host
CI's «Host» job starts. What is asked here runs anywhere the suite does: the
ASGI app the host serves, driven with the scope an HTTP/2 server builds; the
correction made to pyvoy's Envoy configuration, on a configuration shaped as
pyvoy 1.3.0 writes it; and the settings refusal, which the launcher meets
before it starts any server.
"""

from __future__ import annotations

import copy
import os
import struct
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

from app import host
from app.config import LOCAL_DATABASE_URL
from app.contract.lessons.v2.diary_pb import GetDiaryCapabilitiesResponse
from app.rpc import GRPC_REFUSED

SERVER = Path(__file__).resolve().parents[1]


async def _ask(
    path: str, content_type: str, body: bytes, *, http_version: str = "2"
) -> tuple[int, bytes, dict[bytes, bytes]]:
    """One request through ``host.application``: its status, body and trailers."""
    sent: list[dict[str, Any]] = []
    delivered = False

    async def receive() -> dict[str, Any]:
        nonlocal delivered
        if not delivered:
            delivered = True
            return {"type": "http.request", "body": body, "more_body": False}
        return {"type": "http.disconnect"}

    async def send(message: dict[str, Any]) -> None:
        sent.append(message)

    scope = {
        "type": "http",
        "http_version": http_version,
        "method": "POST",
        "scheme": "http",
        "path": path,
        "raw_path": path.encode(),
        "root_path": "",
        "query_string": b"",
        "headers": [(b"content-type", content_type.encode()), (b"te", b"trailers")],
        "client": ("203.0.113.9", 52144),
        "server": ("test", 80),
        "extensions": {"http.response.trailers": {}},
    }
    await host.application(scope, receive, send)
    status = next(m["status"] for m in sent if m["type"] == "http.response.start")
    answer = b"".join(m.get("body", b"") for m in sent if m["type"] == "http.response.body")
    trailers = next((dict(m["headers"]) for m in sent if m["type"] == "http.response.trailers"), {})
    return status, answer, trailers


def _envelope(payload: bytes) -> bytes:
    return struct.pack(">BI", 0, len(payload)) + payload


async def test_native_grpc_at_the_root_reaches_the_contract() -> None:
    """Where every gRPC client calls, with no prefix: the same services
    ``app.main`` mounts at ``/api/rpc``."""
    status, body, trailers = await _ask(
        "/lessons.v2.DiaryService/GetDiaryCapabilities", "application/grpc", _envelope(b"")
    )
    assert (status, trailers[b"grpc-status"]) == (200, b"0")
    _flags, length = struct.unpack(">BI", body[:5])
    answer = GetDiaryCapabilitiesResponse.from_binary(body[5 : 5 + length])
    assert answer.capabilities.enabled is True


async def test_the_root_answers_native_grpc_and_nothing_else() -> None:
    """Connect keeps its one path, ``/api/rpc``, on both targets: at the
    root it is ``app.main``'s, which has nothing there."""
    status, _body, _trailers = await _ask(
        "/lessons.v2.DiaryService/GetDiaryCapabilities", "application/json", b"{}"
    )
    assert status == 404


async def test_native_grpc_at_the_root_over_http_1_1_meets_the_same_guard() -> None:
    status, body, _trailers = await _ask(
        "/lessons.v2.DiaryService/GetDiaryCapabilities",
        "application/grpc",
        _envelope(b""),
        http_version="1.1",
    )
    assert (status, body) == (415, GRPC_REFUSED.encode())


async def test_everything_else_is_the_app_vercel_serves() -> None:
    sent: list[dict[str, Any]] = []

    async def receive() -> dict[str, Any]:
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(message: dict[str, Any]) -> None:
        sent.append(message)

    scope = {
        "type": "http",
        "http_version": "1.1",
        "method": "GET",
        "scheme": "http",
        "path": "/api/v1/health",
        "raw_path": b"/api/v1/health",
        "root_path": "",
        "query_string": b"",
        "headers": [],
        "client": ("203.0.113.9", 52144),
        "server": ("test", 80),
    }
    await host.application(scope, receive, send)
    assert next(m["status"] for m in sent if m["type"] == "http.response.start") == 200


#: The configuration pyvoy 1.3.0 writes for an app on a plain and a TLS port,
#: read with ``--print-envoy-config`` and cut to what the correction touches.
PYVOY_CONFIG: dict[str, Any] = {
    "admin": {"address": {"socket_address": {"address": "127.0.0.1", "port_value": 0}}},
    "static_resources": {
        "listeners": [
            {
                "name": listener,
                "filter_chains": [
                    {
                        "filters": [
                            {
                                "name": "envoy.filters.network.http_connection_manager",
                                "typed_config": {
                                    "@type": "type.googleapis.com/envoy.extensions.filters"
                                    ".network.http_connection_manager.v3.HttpConnectionManager",
                                    "generate_request_id": False,
                                    "stat_prefix": "ingress_http",
                                },
                            }
                        ]
                    }
                ],
            }
            for listener in ("listener", "listener_tls")
        ]
    },
}


def test_envoy_takes_the_client_from_the_connection_on_every_listener() -> None:
    corrected = host.envoy_config(copy.deepcopy(PYVOY_CONFIG))
    managers = [
        chain["filters"][0]["typed_config"]
        for listener in corrected["static_resources"]["listeners"]
        for chain in listener["filter_chains"]
    ]
    assert len(managers) == 2
    for manager in managers:
        assert manager["use_remote_address"] is True
        assert manager["skip_xff_append"] is True
        assert manager["generate_request_id"] is False


def test_a_configuration_with_nothing_to_correct_is_refused_rather_than_served() -> None:
    """A pyvoy that stopped writing the manager would otherwise start a host
    whose throttles a forged header walks past."""
    bare = copy.deepcopy(PYVOY_CONFIG)
    for listener in bare["static_resources"]["listeners"]:
        listener["filter_chains"][0]["filters"][0]["name"] = "envoy.filters.network.tcp_proxy"
    with pytest.raises(RuntimeError, match="client's address"):
        host.envoy_config(bare)


def test_the_launcher_meets_the_settings_refusal_before_any_server_starts() -> None:
    """With the marker and the local defaults, ``python -m app.host`` says
    why and stops, before pyvoy is so much as imported: the same refusal a
    Vercel deployment meets (decision 7)."""
    env = {name: value for name, value in os.environ.items() if name != "VERCEL"}
    env.update(
        {
            "LESSONS_TARGET": "host",
            "DATABASE_URL": LOCAL_DATABASE_URL,
            "BOT_TOKEN": "",
            "RUN_BOT": "false",
        }
    )
    result = subprocess.run(
        [sys.executable, "-m", "app.host"],
        cwd=str(SERVER),
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert result.returncode == 1
    assert "DATABASE_URL" in result.stderr and "BOT_TOKEN" in result.stderr
    assert "docs/deploy.md" in result.stderr
    assert "host target on" not in result.stderr
```

In `server/tests/test_rpc_mount.py`, replace:
```python
    assert main.app.state.v2_mounted is True
    paths = {getattr(r, "path", None) for r in main.app.routes}
```
with:
```python
    assert main.app.state.v2_mounted is True
    # The host's root answers native gRPC from this same object (app/host.py).
    assert main.app.state.v2_services is route.app  # type: ignore[attr-defined]
    paths = {getattr(r, "path", None) for r in main.app.routes}
```

In `server/tests/test_rpc_mount.py`, replace:
```python
    assert main.mount_v2(fresh) is False
    assert fresh.state.v2_mounted is False
```
with:
```python
    assert main.mount_v2(fresh) is False
    assert fresh.state.v2_mounted is False
    assert fresh.state.v2_services is None
```


- [ ] **Step 2: Run it.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_host.py tests/test_cold_start.py tests/test_rpc_mount.py
```
Expected: the collection error `ImportError: cannot import name 'host' from 'app'`.

- [ ] **Step 3: Green: the services kept where the host can find them.**

In `server/app/main.py`, replace:
```python
        target.state.v2_mounted = False
        return False
```
with:
```python
        target.state.v2_mounted = False
        target.state.v2_services = None
        return False
```

In `server/app/main.py`, replace:
```python
    target.state.v2_mounted = True
    return True
```
with:
```python
    target.state.v2_mounted = True
    # The same services, for the host target to answer native gRPC at the
    # root with (app/host.py): one set, so the two paths cannot differ.
    target.state.v2_services = services
    return True
```

In `server/app/rpc/__init__.py`, replace:
```python
def _is_native_grpc(scope: Scope) -> bool:
    for name, value in scope.get("headers", ()):
```
with:
```python
def is_native_grpc(scope: Scope) -> bool:
    """Whether a request is native gRPC — ``application/grpc`` or
    ``application/grpc+…``, never gRPC-Web — as the guard below and the host's
    root (``app/host.py``) both ask it."""
    for name, value in scope.get("headers", ()):
```

In `server/app/rpc/__init__.py`, replace:
```python
        # missing one as "1.1", so only a scope that says 2 or 3 is let past.
        if scope.get("http_version") not in ("2", "3") and _is_native_grpc(scope):
```
with:
```python
        # missing one as "1.1", so only a scope that says 2 or 3 is let past.
        if scope.get("http_version") not in ("2", "3") and is_native_grpc(scope):
```


- [ ] **Step 4: Green: the host.**

Write `server/app/host.py` whole:
```python
"""The host target: the whole app over HTTP/2, from one long-running process.

``python -m app.host`` serves what Vercel serves — v1, v2 over REST and
Connect, the Telegram webhook and the cron tick — and what Vercel cannot:
native gRPC, which needs HTTP/2 and response trailers, and ``WatchClass``'s
stream, which needs a process that outlives a response
(``docs/specs/2026-10-05-server-v2-design.md``, decision 13). It is a
complete deployment, chosen instead of Vercel rather than beside it
(question 2): the webhook and the external cron are pointed at it, and it
runs as one instance, because a stream hears what this one process writes
(``app/watch.py``).

**The server is pyvoy**: Envoy, with the app running inside it, HTTP/1.1 and
HTTP/2 with prior knowledge on one port, trailers and gzip. ``--server
hypercorn`` is the fallback the 5 October spike proved as well; CI's «Host»
job runs both.

**It never sets ``LESSONS_TARGET``.** The marker is a deployment's statement
about itself, and the root ``Dockerfile`` makes it; CI's job and a local run
start this module without it, against SQLite, as a local uvicorn runs. The
settings are read here before any server starts, so a host the refusal stops
says why in its own log and not from inside Envoy.

**Native gRPC is answered at the root too.** A gRPC client calls
``/lessons.v2.<Service>/<Method>`` and knows no path prefix; connect-kotlin,
which the app uses, takes ``/api/rpc`` as Vercel serves it. So
:func:`application` hands a native gRPC request under ``/lessons.v2.`` to the
same services ``app.main`` mounts at ``/api/rpc``, and everything else to
``app.main``, as on Vercel.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import signal
import sys
from collections.abc import Awaitable, Callable, MutableMapping
from typing import Any

from app.config import DeploymentNotConfigured, get_settings

log = logging.getLogger(__name__)

Scope = MutableMapping[str, Any]
Receive = Callable[[], Awaitable[MutableMapping[str, Any]]]
Send = Callable[[MutableMapping[str, Any]], Awaitable[None]]

#: Where a native gRPC call lands when a client is pointed at the host itself.
GRPC_ROOT = "/lessons.v2."

#: The app pyvoy loads into Envoy, by name.
ENTRY = "app.host:application"

#: The servers this module can run, the first by default.
SERVERS = ("pyvoy", "hypercorn")

#: Envoy's HTTP connection manager: the filter whose idea of the client's
#: address :func:`envoy_config` corrects.
_HCM = "envoy.filters.network.http_connection_manager"


def _main_app() -> Any:
    # Imported on the first call rather than with this module, so that the
    # launcher — which under pyvoy only starts Envoy, whose own interpreter
    # serves the app — builds no engine, mounts no route and starts no Sentry
    # of its own.
    from app.main import app

    return app


async def application(scope: Scope, receive: Receive, send: Send) -> None:
    """``app.main``'s app, with native gRPC answered at the root as well."""
    main = _main_app()
    if scope["type"] == "http" and scope["path"].startswith(GRPC_ROOT):
        # None when v2 could not be loaded: the path then falls to app.main,
        # which answers it 404, while /api/rpc says 503 (main.mount_v2).
        services = getattr(main.state, "v2_services", None)
        if services is not None:
            from app.rpc import is_native_grpc

            if is_native_grpc(scope):
                await services({**scope, "root_path": ""}, receive, send)
                return
    await main(scope, receive, send)


def envoy_config(config: dict[str, Any]) -> dict[str, Any]:
    """pyvoy's Envoy configuration, with the client's address taken from the connection.

    pyvoy leaves Envoy's ``use_remote_address`` off, and then Envoy hands the
    app the last ``X-Forwarded-For`` entry as the client: whatever the caller
    wrote there. Probed with pyvoy 1.3.0, a request carrying
    ``X-Forwarded-For: 198.51.100.7, 203.0.113.8`` reached the app as
    ``203.0.113.8``. Every
    throttle keys on that address when no proxy is trusted
    (``api/deps.caller_bucket``), so a caller writing a new one each time
    would never be throttled at all. Turned on, with ``skip_xff_append`` so
    that Envoy adds no entry of its own, the app sees the connection's peer as
    the client and the header exactly as it arrived — which is what
    ``TRUSTED_PROXY_HOPS`` reads behind a proxy, as it does under uvicorn.
    """
    managers = [
        chain_filter["typed_config"]
        for listener in config.get("static_resources", {}).get("listeners", [])
        for chain in listener.get("filter_chains", [])
        for chain_filter in chain.get("filters", [])
        if chain_filter.get("name") == _HCM
    ]
    if not managers:
        raise RuntimeError(
            "pyvoy's Envoy configuration has no HTTP connection manager to correct: "
            "the client's address would be the caller's to choose"
        )
    for manager in managers:
        manager["use_remote_address"] = True
        manager["skip_xff_append"] = True
    return config


async def _serve_pyvoy(host: str, port: int, stop: asyncio.Event) -> int:
    from pyvoy import PyvoyServer

    class Server(PyvoyServer):
        def get_envoy_config(self) -> dict[str, Any]:
            return envoy_config(super().get_envoy_config())

    server = Server(
        ENTRY,
        address=host,
        port=port,
        # One Python thread, so one event loop: the engine's pool and the bus
        # a stream is woken by both belong to the loop that made them.
        worker_threads=1,
        # Required, not guessed: the lifespan is where a SQLite file gets its
        # tables and the providers' clients are closed.
        lifespan=True,
        content_encodings=["gzip"],
    )
    async with server:
        log.info("host target on %s:%s, served by pyvoy", server.listener_address, port)
        exited = asyncio.ensure_future(server.wait())
        stopping = asyncio.ensure_future(stop.wait())
        done, _pending = await asyncio.wait({exited, stopping}, return_when=asyncio.FIRST_COMPLETED)
        stopping.cancel()
        if exited in done:
            # Envoy went away by itself: never a clean exit for a server.
            log.error("Envoy stopped on its own, with %s", exited.result())
            return exited.result() or 1
        exited.cancel()
    return 0


async def _serve_hypercorn(host: str, port: int, stop: asyncio.Event) -> int:
    from hypercorn.asyncio import serve
    from hypercorn.config import Config

    config = Config()
    config.bind = [f"{host}:{port}"]
    # The app logs for itself; a line per request would put every phone's
    # path and address into the log.
    config.accesslog = None
    log.info("host target on %s:%s, served by hypercorn", host, port)
    await serve(application, config, shutdown_trigger=stop.wait, mode="asgi")
    return 0


async def _run(server: str, host: str, port: int) -> int:
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for signum in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(signum, stop.set)
        except (NotImplementedError, RuntimeError):
            # Windows: Ctrl+C arrives as KeyboardInterrupt, which asyncio.run
            # turns into a cancellation that stops the server on its way out.
            pass
    serve = _serve_pyvoy if server == "pyvoy" else _serve_hypercorn
    return await serve(host, port, stop)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        prog="python -m app.host",
        description="Serve the host target: v1, v2 over REST, Connect and native gRPC, "
        "WatchClass, the webhook and the tick, over HTTP/2.",
    )
    parser.add_argument(
        "--server",
        choices=SERVERS,
        default=SERVERS[0],
        help="pyvoy (the default), or hypercorn, the fallback",
    )
    arguments = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s"
    )
    try:
        settings = get_settings()
    except DeploymentNotConfigured as refusal:
        sys.exit(str(refusal))
    sys.exit(asyncio.run(_run(arguments.server, settings.host, settings.port)))


if __name__ == "__main__":
    main()
```


- [ ] **Step 5: Run it again.** The command of Step 2. Expected: `42 passed`.

- [ ] **Step 6: The gates.** As Task 1's, with `app/host.py tests/test_host.py` for the format check. Expected: `All checks passed!`, `2 files already formatted`, and `Success: no issues found in 240 source files`.

- [ ] **Step 7: Commit.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3c-t5.txt`:
```text
Serve the whole app over HTTP/2 from python -m app.host, under pyvoy

app/host.py is the host target: app.main's app, with native gRPC also
answered at the root, where a gRPC client calls - grpcio has no notion
of a prefix and got 404 under /api/rpc alone - and everything else
app.main's, so Connect keeps one path on both targets. pyvoy runs it
inside Envoy with one worker thread, the lifespan required, and gzip;
--server hypercorn is the fallback, chosen, never automatic.

pyvoy's own Envoy configuration hands the app the last X-Forwarded-For
entry as the client, so a caller writing a new one each time would never
meet a throttle (#395). envoy_config turns on use_remote_address with
skip_xff_append on every connection manager and refuses a configuration
with none: the app sees the peer, and the header as it came, as under
uvicorn.

The launcher reads the settings before any server starts and exits with
the refusal's own text; it never sets LESSONS_TARGET and never imports
app.main, which Envoy's interpreter serves. Nothing on the API's path
imports the host, pyvoy or hypercorn.

Not covered here: a running host; the next tasks lock its packages and
start it in CI.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230 && git add server/app/host.py server/app/main.py server/app/rpc/__init__.py server/tests/test_host.py server/tests/test_cold_start.py server/tests/test_rpc_mount.py && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3c-t5.txt
```

---

### 3c Task 6: The host's lock and its image

Decision 13 («dependencies and packaging»); Rulings 156 and 157. Vercel's lock, its input and `pyproject.toml`'s dependencies do not move.

**Files:**
- Create: `server/requirements-host.in`, `server/requirements-host.txt` (by `uv pip compile`), `Dockerfile`, `.dockerignore`, `server/tests/test_host_image.py`
- Modify: `server/pyproject.toml`, `server/tests/test_ci_paths.py`, `.github/workflows/ci.yml` (the server's paths only)

**Interfaces:**
- Consumes: `requirements.txt` as a constraint; Task 5's `python -m app.host`.
- Produces: the lock's pins (pyvoy 1.3.0, envoy-server 1.39.3, hypercorn 0.18.0, and what they need, uvloop and winloop under markers); the image's instructions; the `host-check` extra (`grpcio>=1.84.0`).

- [ ] **Step 1: Red.** Create `server/tests/test_host_image.py`, and say the root's two files are the suite's to read:

In `server/tests/test_ci_paths.py`, replace:
```python
    "docker-compose.yml": "test_compose",
    "api/*.py": "test_vercel_entry",
```
with:
```python
    "docker-compose.yml": "test_compose",
    "Dockerfile": "test_host_image",
    ".dockerignore": "test_host_image",
    "api/*.py": "test_vercel_entry",
```

Write `server/tests/test_host_image.py` whole:
```python
"""The host's image (the root ``Dockerfile``) and its lock, read against what they promise.

Nothing here builds the image: there is no Docker where this is developed, and
CI's «Host» job installs the same two locks and starts the same command
instead. What can be held without Docker is held here: that the host's lock
pins every package once, was compiled from its input against Vercel's lock,
and agrees with it on every package both pin; that Vercel's lock carries
nothing of the host's; and that the image installs both, says it is the host,
copies only what its context holds, and runs it as nobody in particular
(``docs/specs/2026-10-05-server-v2-design.md``, decision 13).
"""

from __future__ import annotations

import re
import tomllib
from pathlib import Path

from packaging.requirements import Requirement
from packaging.utils import NormalizedName, canonicalize_name

ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / "server"
DOCKERFILE = ROOT / "Dockerfile"
IGNORE = ROOT / ".dockerignore"
HOST_INPUT = SERVER / "requirements-host.in"
HOST_LOCK = SERVER / "requirements-host.txt"
LOCK = ROOT / "requirements.txt"
LOCK_INPUT = ROOT / "requirements.in"
PYPROJECT = SERVER / "pyproject.toml"
PYTHON_VERSION = ROOT / ".python-version"

#: What the host runs and Vercel never does.
HOST_ONLY = {"pyvoy", "envoy-server", "hypercorn"}


def _requirements(path: Path) -> list[str]:
    """Every requirement line of a requirements file, comments dropped."""
    return [
        line.split(" #", 1)[0].strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def _names(path: Path) -> set[NormalizedName]:
    return {canonicalize_name(Requirement(line).name) for line in _requirements(path)}


def _pins(path: Path) -> dict[NormalizedName, str]:
    pins: dict[NormalizedName, str] = {}
    for line in _requirements(path):
        requirement = Requirement(line)
        (specifier,) = requirement.specifier
        pins[canonicalize_name(requirement.name)] = specifier.version
    return pins


def _header(path: Path) -> str:
    lines = path.read_text(encoding="utf-8").splitlines()
    return "\n".join(line for line in lines[:5] if line.startswith("#"))


def test_the_host_lock_pins_every_package_to_one_version() -> None:
    loose = []
    for line in _requirements(HOST_LOCK):
        specifiers = list(Requirement(line).specifier)
        if len(specifiers) != 1 or specifiers[0].operator != "==" or "*" in specifiers[0].version:
            loose.append(line)
    assert loose == [], f"requirements-host.txt must pin with == and nothing else: {loose}"


def test_the_host_lock_was_compiled_from_its_input_against_vercel_s() -> None:
    """Its header is the command that regenerates it, from server/; uv marks
    each package the input asks for by name, so an input edited and not
    compiled shows here."""
    header = _header(HOST_LOCK)
    assert "uv pip compile requirements-host.in -c ../requirements.txt" in header
    assert "--universal" in header, "the host runs on Linux and on the console's Windows"
    python = PYTHON_VERSION.read_text(encoding="utf-8").strip()
    assert f"--python-version {python}" in header
    direct: set[NormalizedName] = set()
    current: NormalizedName | None = None
    for line in HOST_LOCK.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            current = canonicalize_name(Requirement(stripped).name)
        elif current is not None and "-r requirements-host.in" in stripped:
            direct.add(current)
    assert direct == _names(HOST_INPUT), (
        f"the host lock marks {sorted(direct)}, its input asks for {sorted(_names(HOST_INPUT))} "
        "— regenerate it with the command in its header"
    )


def test_every_package_both_locks_pin_is_pinned_at_one_version() -> None:
    """The point of compiling one against the other: the image installs both,
    and two pins of one package are a resolution pip refuses, or worse, the
    later one silently winning. A bump of Vercel's lock that moves a package
    the host shares fails here until the host's lock is regenerated."""
    vercel, hosted = _pins(LOCK), _pins(HOST_LOCK)
    shared = set(vercel) & set(hosted)
    assert shared, "the two locks share no package, so this would hold nothing"
    differing = {
        name: (vercel[name], hosted[name]) for name in shared if vercel[name] != hosted[name]
    }
    assert differing == {}, (
        f"requirements.txt and server/requirements-host.txt disagree: {differing} — regenerate "
        "the host's lock with the command in its header"
    )


def test_vercel_s_lock_and_its_input_carry_nothing_of_the_host_s() -> None:
    """Vercel installs requirements.txt, and app.main is its cold start: a
    server package there is weight on every request and a reason it could be
    imported by mistake."""
    declared = {
        canonicalize_name(Requirement(line).name)
        for line in tomllib.loads(PYPROJECT.read_text(encoding="utf-8"))["project"]["dependencies"]
    }
    for name, found in (
        ("requirements.txt", set(_pins(LOCK))),
        ("requirements.in", _names(LOCK_INPUT)),
        ("pyproject.toml's dependencies", declared),
    ):
        assert HOST_ONLY & found == set(), f"{name} carries the host's {sorted(HOST_ONLY & found)}"
    assert HOST_ONLY <= set(_pins(HOST_LOCK))


def _instructions() -> list[str]:
    """The Dockerfile's instructions, continuation lines joined, comments dropped."""
    joined = re.sub(r"\\\n\s*", " ", DOCKERFILE.read_text(encoding="utf-8"))
    return [
        line.strip()
        for line in joined.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def test_the_image_installs_both_locks_and_then_the_package_alone() -> None:
    steps = _instructions()
    locks = next(
        i
        for i, step in enumerate(steps)
        if step.startswith("RUN pip install")
        and "-r requirements.txt" in step
        and "-r requirements-host.txt" in step
    )
    package = next(
        i
        for i, step in enumerate(steps)
        if step.startswith("RUN pip install") and "--no-deps ." in step
    )
    assert locks < package
    installs = [step for step in steps if step.startswith("RUN pip install")]
    assert len(installs) == 2, (
        f"nothing may be installed but the two locks and the package: {installs}"
    )


def test_the_image_says_it_is_the_host_and_the_code_never_does() -> None:
    environment = " ".join(step for step in _instructions() if step.startswith("ENV "))
    assert re.search(r"\bLESSONS_TARGET=host\b", environment)
    setting = re.compile(
        r"""environ\[\s*["']LESSONS_TARGET["']\s*\]\s*=(?!=)|setdefault\(\s*["']LESSONS_TARGET"""
        r"""|putenv\(\s*["']LESSONS_TARGET|setenv\(\s*["']LESSONS_TARGET"""
    )
    writers = [
        path.relative_to(SERVER).as_posix()
        for path in sorted((SERVER / "app").rglob("*.py"))
        if setting.search(path.read_text(encoding="utf-8"))
    ]
    assert writers == []


def test_the_image_runs_the_host_as_a_user_of_its_own() -> None:
    steps = _instructions()
    users = [step for step in steps if step.startswith("USER ")]
    assert users and users[-1] != "USER root"
    assert steps[-1] == 'CMD ["python", "-m", "app.host"]'


def test_the_build_context_holds_everything_the_image_copies() -> None:
    """The context is the repository's root with everything ignored but what
    is let back in; a COPY of something not let back in fails the build."""
    let_in = [
        line[1:].rstrip("/")
        for line in IGNORE.read_text(encoding="utf-8").splitlines()
        if line.startswith("!")
    ]
    assert IGNORE.read_text(encoding="utf-8").splitlines().count("*") == 1
    sources = [step.split()[1] for step in _instructions() if step.startswith("COPY ")]
    missing = [
        source
        for source in sources
        if not any(source == entry or source.startswith(entry + "/") for entry in let_in)
    ]
    assert missing == [], f"the Dockerfile copies what .dockerignore leaves out: {missing}"
    assert all((ROOT / source).exists() for source in sources)
```


- [ ] **Step 2: Run it.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_host_image.py tests/test_ci_paths.py
```
Expected: `10 failed, 1 passed`: `test_host_image.py`'s eight, which find no lock and no image, and `test_ci_paths.py`'s `test_a_change_to_anything_the_suite_reads_runs_the_suite` and `test_every_name_at_the_root_a_test_spells_is_in_the_list`, since the server's paths in `ci.yml` do not name the root's two files yet and the files are not there.

- [ ] **Step 3: Green: the lock's input, and the lock.**

Write `server/requirements-host.in` whole:
```text
# What the host target adds to requirements.txt, and nothing else: the input of
# its lock, requirements-host.txt beside this file.
#
# The host target is `python -m app.host` (app/host.py): Vercel's app, served
# over HTTP/2 so that native gRPC and WatchClass's stream work
# (docs/specs/2026-10-05-server-v2-design.md, decision 13). A server is all it
# needs beyond Vercel's function, so a server is all this file asks for, and
# Vercel's lock and its cold start carry none of it. The root Dockerfile
# installs both locks; CI's «Host» job does the same.
#
# The lock is compiled against requirements.txt (the `-c` in its header), so a
# package both need is pinned at the version Vercel runs and the two locks
# cannot disagree: server/tests/test_host_image.py holds it, and fails when a
# change to requirements.txt moves a package this lock pins too. Regenerate
# with the command in the lock's header, run from server/. It resolves for
# every platform (`--universal`), where Vercel's lock resolves for Linux alone:
# the host runs in the image and in CI, on Linux, and on the Windows machine
# the build console starts it on, and pyvoy needs uvloop on the one and
# winloop on the other. Envoy's Linux wheel needs glibc 2.31 or newer, which
# the image's Debian has.
#
# pyvoy is Envoy with the app inside it: HTTP/2 with prior knowledge,
# trailers, gzip. The floor is the release the 5 October spike ran native gRPC
# and a stream on (docs/specs/2026-10-03-one-contract-design.md).
pyvoy>=1.2.0
# The fallback the same spike proved: `python -m app.host --server hypercorn`.
hypercorn>=0.18.0
```

then compile it, from `server/`:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/.local/bin/uv pip compile requirements-host.in -c ../requirements.txt --python-version 3.12 --universal --output-file requirements-host.txt
```
Expected: `server/requirements-host.txt` reads, line for line:

```text
# This file was autogenerated by uv via the following command:
#    uv pip compile requirements-host.in -c ../requirements.txt --python-version 3.12 --universal --output-file requirements-host.txt
envoy-server==1.39.3
    # via pyvoy
find-libpython==0.5.1
    # via pyvoy
h11==0.16.0
    # via
    #   -c ../requirements.txt
    #   hypercorn
    #   wsproto
h2==4.4.1
    # via hypercorn
hpack==4.2.0
    # via h2
hypercorn==0.18.0
    # via -r requirements-host.in
hyperframe==6.1.0
    # via h2
opentelemetry-api==1.45.0
    # via
    #   -c ../requirements.txt
    #   pyqwest
priority==2.0.0
    # via hypercorn
pyqwest==0.11.0
    # via
    #   -c ../requirements.txt
    #   pyvoy
pyvoy==1.3.0
    # via -r requirements-host.in
pyyaml==6.0.3
    # via pyvoy
typing-extensions==4.16.0
    # via
    #   -c ../requirements.txt
    #   opentelemetry-api
uvloop==0.23.0 ; sys_platform != 'win32'
    # via pyvoy
winloop==0.7.1 ; sys_platform == 'win32'
    # via pyvoy
wsproto==1.3.2
    # via hypercorn
```

If a pin differs — a release published since 10 October — keep what uv wrote: the lock is the truth, and Step 6 asks it of Vercel's. Then complete the worktree's venv, which Task 7 runs the host from:
```bash
/c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/python.exe -m pip install -r /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/requirements-host.txt
```
Expected: pyvoy, envoy-server, hypercorn, winloop and their few others installed, and no package of `requirements.txt` moved (`pip` says nothing about uninstalling one).

- [ ] **Step 4: Green: the image, its context, the extra and the paths.**

Write `.dockerignore` whole:
```text
# The context of the root Dockerfile, the host image: two locks and the
# server's package. Everything else here — the Android app, the documents, the
# history, a developer's .env and venv — would be uploaded to the builder on
# every build and read by nothing, and a .env is a secret besides.
# server/Dockerfile builds from server/ and does not read this file.
*
!requirements.txt
!server/requirements-host.txt
!server/pyproject.toml
!server/app/
!server/scripts/
**/__pycache__
**/*.pyc
```

In `.github/workflows/ci.yml`, replace:
```yaml
              # `docker-compose.yml` is read by test_compose, which is all that
              # checks it: nothing here runs Docker. `api/` and `vercel.json`
```
with:
```yaml
              # `docker-compose.yml` is read by test_compose, which is all that
              # checks it: nothing here runs Docker; the host's `Dockerfile` and
              # `.dockerignore`, at the root, by test_host_image. `api/` and `vercel.json`
```

In `.github/workflows/ci.yml`, replace:
```yaml
              # file the suite reads that this line does not match.
              server/*|api/*|vercel.json|.vercelignore|requirements.txt|requirements.in|.python-version|docker-compose.yml|proto/*|buf.yaml|buf.lock|buf.gen.yaml|README.md|CLAUDE.md|AGENTS.md|.github/copilot-instructions.md|docs/*.md|.claude/*.md|.github/workflows/*) server=true ;;
```
with:
```yaml
              # file the suite reads that this line does not match.
              server/*|api/*|vercel.json|.vercelignore|requirements.txt|requirements.in|.python-version|docker-compose.yml|Dockerfile|.dockerignore|proto/*|buf.yaml|buf.lock|buf.gen.yaml|README.md|CLAUDE.md|AGENTS.md|.github/copilot-instructions.md|docs/*.md|.claude/*.md|.github/workflows/*) server=true ;;
```

Write `Dockerfile` whole:
```dockerfile
# The host target (server/app/host.py): the whole app over HTTP/2 — v1, v2 over
# REST, Connect and native gRPC, WatchClass's stream, the webhook and the
# tick — as one container, chosen instead of Vercel rather than beside it
# (docs/specs/2026-10-05-server-v2-design.md, decision 13; docs/deploy.md,
# «Option 3: the host target»). Built from the repository root, because it
# installs Vercel's lock, which is there, beside the host's:
#
#     docker build -t lessons-host .
#
# server/Dockerfile is the other image, docker-compose.yml's: uvicorn, long
# polling, no marker — a deployment configured by hand, not this one.
FROM python:3.12-slim

# LESSONS_TARGET is the deployment's statement about itself, as VERCEL is
# Vercel's: with it the server refuses to start without what a deployment
# needs (app/config.py, get_settings). The image says it; app.host never does.
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    LESSONS_TARGET=host \
    HOST=0.0.0.0 \
    PORT=8000

WORKDIR /srv

# The two locks first, so that a change to the code does not install them
# again: Vercel's, and the host's, compiled against it so that the two cannot
# disagree. Nothing is resolved here that one of them does not pin.
COPY requirements.txt ./requirements.txt
COPY server/requirements-host.txt ./requirements-host.txt
RUN pip install --no-cache-dir -r requirements.txt -r requirements-host.txt

# The package itself, and not its dependencies: pyproject.toml's floors would
# bring what the locks leave out on purpose — uvicorn, aiosqlite and alembic,
# none of which the host runs. Migrations are applied from a workstation, as
# for Vercel (docs/deploy.md).
COPY server/pyproject.toml ./
COPY server/app ./app
COPY server/scripts ./scripts
RUN pip install --no-cache-dir --no-deps .

# Not root: nothing here needs a privilege — 8000 is above 1024, and the
# process reads its code and talks to the network — and a process that is root
# in a container is root to whatever a container escape reaches.
RUN useradd --system --no-create-home --shell /usr/sbin/nologin lessons
USER lessons

EXPOSE 8000

# One process, one instance: a stream hears what this process writes
# (server/app/watch.py), so a second replica would serve streams that miss
# every change made through the first.
CMD ["python", "-m", "app.host"]
```

In `server/pyproject.toml`, replace:
```toml
    "mypy>=1.11",
]

# Not a type checker in the usual sense - one question, asked of the whole
```
with:
```toml
    "mypy>=1.11",
]
# A native gRPC client, for the tests CI's «Host» job runs against a running
# host (tests/test_host_live.py, behind the `host` marker): Google's own
# implementation, so that what is proved is that a gRPC client that is not
# this server's library reads what it answers. Only that job installs it.
host-check = ["grpcio>=1.84.0"]

# Not a type checker in the usual sense - one question, asked of the whole
```


- [ ] **Step 5: Install the extra** the tests of a running host ask with, into the worktree's venv:
```bash
/c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/python.exe -m pip install "grpcio>=1.84.0"
```
Expected: grpcio 1.84.0 or later.

- [ ] **Step 6: Run it again.** The command of Step 2, with `tests/test_requirements_mirror.py tests/test_env_example.py` added. Expected: `24 passed`.

- [ ] **Step 7: The gates.** As Task 2's. Expected: `All checks passed!` and `Success: no issues found in 240 source files` — now with pyvoy and hypercorn installed, so `app/host.py` is checked against their own types.

- [ ] **Step 8: Commit.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3c-t6.txt`:
```text
Lock the host's server apart from Vercel's, and build its image from both

server/requirements-host.in asks for pyvoy and hypercorn and nothing
else, and requirements-host.txt is it compiled against requirements.txt,
so a package both need is pinned at the version Vercel runs. It resolves
for every platform: Envoy's wheel needs glibc 2.31, which Vercel's
platform tag does not promise, and the host runs on Linux and on the
Windows machine the build console starts it on. test_host_image.py holds
every shared pin equal, so a bump of Vercel's lock that moves one fails
until the host's is regenerated.

The root Dockerfile is the host's image: both locks, then the package
without its floors, LESSONS_TARGET=host, python -m app.host as a user of
its own. It builds from the root because Vercel's lock is there;
.dockerignore lets in the two locks and the server's package alone.
server/Dockerfile stays compose's. grpcio, which only the tests of a
running host need, is pyproject's host-check extra.

Not covered: the image has never been built - there is no Docker here -
and dependabot does not watch the host's lock.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230 && git add server/requirements-host.in server/requirements-host.txt Dockerfile .dockerignore server/pyproject.toml server/tests/test_host_image.py server/tests/test_ci_paths.py .github/workflows/ci.yml && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3c-t6.txt
```

---

### 3c Task 7: CI's «Host» job and the tests it runs, and the batch's one full run

Decisions 13 («CI») and 14; Rulings 158 to 160 and 164.

**Files:**
- Create: `server/tests/test_host_live.py`, `server/tests/test_host_job.py`
- Modify: `server/tests/conftest.py`, `server/pyproject.toml`, `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: Tasks 1 to 6; grpcio's `insecure_channel`, `unary_unary`, `unary_stream`, `RpcError.code()` and `StatusCode`; the contract's messages; the models, to write a class into the host's database.
- Produces: the `host` marker; `conftest.HOST_ABSENT` and its `pytest_collection_modifyitems`; the «Host» job; `LESSONS_HOST_URL` and `LESSONS_HOST_DATABASE_URL`, read by the live tests.

- [ ] **Step 1: Red.** The tests a running host is asked, the job held to them, and the marker's skip:

Write `server/tests/test_host_job.py` whole:
```python
"""CI's «Host» job, read against the tests it runs and the host it starts.

The job is the one place the host target runs (``.github/workflows/ci.yml``;
``docs/specs/2026-10-05-server-v2-design.md``, decisions 13 and 14), and it
cannot be run from here: it needs Linux, the host's lock and a network. What
can be held here is that it asks what ``test_host_live.py`` needs — both
servers, streaming on, no deployment marker, the address and the database
the tests read — and that the ordinary run leaves those tests skipped.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
CI = ROOT / ".github" / "workflows" / "ci.yml"


def _job() -> dict[str, Any]:
    return yaml.safe_load(CI.read_text(encoding="utf-8"))["jobs"]["host"]


def _step(job: dict[str, Any], name: str) -> dict[str, Any]:
    return next(step for step in job["steps"] if step.get("name") == name)


def test_the_job_runs_both_servers_whenever_the_server_job_does() -> None:
    job = _job()
    assert job["needs"] == "changes"
    assert job["if"] == "needs.changes.outputs.server == 'true'"
    assert job["strategy"]["matrix"]["server"] == ["pyvoy", "hypercorn"]
    assert job["runs-on"] == "ubuntu-latest"


def test_the_job_installs_the_image_s_two_locks_and_the_grpc_client() -> None:
    install = _step(_job(), "Install")["run"]
    for part in ("-r ../requirements.txt", "-r requirements-host.txt", '-e ".[dev,host-check]"'):
        assert part in install, part


def test_the_job_starts_the_host_as_a_local_run_does_with_streaming_on() -> None:
    """Without the marker: with it, the settings refusal would reject the
    SQLite default and the host would never start (decision 13)."""
    start = _step(_job(), "Start the host")
    assert "python -m app.host --server ${{ matrix.server }}" in start["run"]
    environment = start["env"]
    assert "LESSONS_TARGET" not in environment and "VERCEL" not in environment
    assert environment["LESSONS_STREAMING"] == "true"
    assert environment["RUN_BOT"] == "false"
    assert environment["DATABASE_URL"].startswith("sqlite+aiosqlite:///")
    # The webhook and the tick mounted, so the tests can find them refusing.
    assert environment["BOT_TOKEN"] and environment["WEBHOOK_SECRET"] and environment["CRON_SECRET"]


def test_the_job_tests_the_host_it_started() -> None:
    job = _job()
    start, test = _step(job, "Start the host"), _step(job, "Test against it")
    assert test["run"] == "pytest -q -p no:xdist -m host"
    assert test["env"]["LESSONS_HOST_DATABASE_URL"] == start["env"]["DATABASE_URL"]
    assert test["env"]["LESSONS_HOST_URL"] == f"http://127.0.0.1:{start['env']['PORT']}"


def test_the_ordinary_run_skips_the_tests_that_need_a_host(request) -> None:
    if os.environ.get("LESSONS_HOST_URL"):
        pytest.skip("a host is running: its tests are not skipped")
    marked = [item for item in request.session.items if item.get_closest_marker("host")]
    if not marked:
        pytest.skip("test_host_live.py is not part of this run")
    assert all(item.get_closest_marker("skip") is not None for item in marked)
```

Write `server/tests/test_host_live.py` whole:
```python
"""The host target, asked over the network: what only a running HTTP/2 server can answer.

Run by CI's «Host» job against ``python -m app.host``, under pyvoy in one job
and hypercorn in the other, each started on a SQLite file of its own and with
streaming on (``docs/specs/2026-10-05-server-v2-design.md``, decisions 13 and 14).
Everywhere else every test here is skipped: they are marked ``host``, and
``conftest.py`` skips the mark unless ``LESSONS_HOST_URL`` names a running
host. The class, its members and their phones are written into the host's
database directly (``LESSONS_HOST_DATABASE_URL``), because only the bot can
link a phone; every change after that goes through the host, because a stream
is woken only by what the host itself writes.

The client is grpcio, Google's own implementation, so what is proved is that
a gRPC client that is not this server's library reads what it answers. The
forged ``X-Forwarded-For`` test spends the join budget of the job's address
for a quarter of an hour, so it is the last in the file.
"""

from __future__ import annotations

import asyncio
import os
import queue
import secrets
import threading
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from typing import Any
from urllib.parse import urlsplit

import httpx
import pytest
from protobuf.wkt import FieldMask
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.contract.lessons.v2.bell_pb import BellPeriod as BellRow
from app.contract.lessons.v2.bell_pb import BellSchedule as BellMessage
from app.contract.lessons.v2.bell_pb import UpdateBellScheduleRequest, UpdateBellScheduleResponse
from app.contract.lessons.v2.class_device_pb import (
    RevokeClassDeviceRequest,
    RevokeClassDeviceResponse,
)
from app.contract.lessons.v2.diary_pb import (
    GetDiaryCapabilitiesRequest,
    GetDiaryCapabilitiesResponse,
)
from app.contract.lessons.v2.homework_pb import (
    CreateHomeworkRequest,
    CreateHomeworkResponse,
    Homework,
)
from app.contract.lessons.v2.me_pb import GetMeRequest, GetMeResponse
from app.contract.lessons.v2.timetable_pb import ImportTimetableRequest, ImportTimetableResponse
from app.contract.lessons.v2.watch_pb import WatchClassRequest, WatchClassResponse
from app.models import (
    DEFAULT_BELLS,
    BellPeriod,
    BellSchedule,
    BotUser,
    DeviceToken,
    Role,
    SchoolClass,
    TimetableEntry,
    WeekParity,
)
from app.security import hash_token

pytestmark = pytest.mark.host

#: How long a test waits for a message the host owes it. Generous, because a
#: CI runner is slower than anything here should be; a stream that works
#: answers in milliseconds.
PATIENCE = 15.0


@dataclass
class Seeded:
    """A class written into the host's database, and the bearers of its phones."""

    class_id: int
    schedule_id: int
    tokens: dict[str, str] = field(default_factory=dict)
    devices: dict[str, int] = field(default_factory=dict)


async def _seed() -> Seeded:
    engine = create_async_engine(os.environ["LESSONS_HOST_DATABASE_URL"])
    try:
        sessions = async_sessionmaker(engine, expire_on_commit=False)
        async with sessions() as session:
            school_class = SchoolClass(
                name="9А", school="Школа № 1", join_code=f"H{secrets.token_hex(4).upper()}"
            )
            session.add(school_class)
            await session.flush()
            bells = BellSchedule(class_id=school_class.id, name="Обычное")
            session.add(bells)
            await session.flush()
            for index, starts_at, ends_at in DEFAULT_BELLS:
                session.add(
                    BellPeriod(
                        schedule_id=bells.id, index=index, starts_at=starts_at, ends_at=ends_at
                    )
                )
            school_class.bell_schedule_id = bells.id
            for weekday in (1, 2):
                for index, subject in ((1, "Алгебра"), (2, "Физика")):
                    session.add(
                        TimetableEntry(
                            class_id=school_class.id,
                            weekday=weekday,
                            index=index,
                            subject_name=subject,
                            parity=WeekParity.ANY,
                        )
                    )
            seeded = Seeded(class_id=school_class.id, schedule_id=bells.id)
            first_id = 8_000_000 + secrets.randbelow(1_000_000)
            phones = {}
            for offset, (name, role) in enumerate(
                (("viewer", Role.VIEWER), ("editor", Role.EDITOR), ("admin", Role.ADMIN))
            ):
                telegram_id = first_id + offset
                session.add(BotUser(telegram_id=telegram_id, class_id=school_class.id, role=role))
                token = f"host-{name}-{secrets.token_hex(12)}"
                phones[name] = DeviceToken(
                    token_hash=hash_token(token),
                    class_id=school_class.id,
                    device_name=f"{name} phone",
                    telegram_id=telegram_id,
                    linked_at=datetime(2026, 9, 1),
                )
                session.add(phones[name])
                seeded.tokens[name] = token
            await session.commit()
            seeded.devices = {name: phone.id for name, phone in phones.items()}
            return seeded
    finally:
        await engine.dispose()


@pytest.fixture
def seeded() -> Seeded:
    return asyncio.run(_seed())


@pytest.fixture
def grpc() -> Any:
    """grpcio, imported here: only the «Host» job installs it (``.[host-check]``)."""
    import grpc as grpcio

    return grpcio


@pytest.fixture
def channel(grpc) -> Any:
    target = urlsplit(os.environ["LESSONS_HOST_URL"]).netloc
    with grpc.insecure_channel(target) as opened:
        yield opened


@pytest.fixture
def http() -> Any:
    with httpx.Client(base_url=os.environ["LESSONS_HOST_URL"], timeout=PATIENCE) as client:
        yield client


def _unary(channel: Any, path: str, response: Any) -> Any:
    return channel.unary_unary(
        path,
        request_serializer=lambda message: message.to_binary(),
        response_deserializer=response.from_binary,
    )


def _bearer(token: str) -> list[tuple[str, str]]:
    return [("authorization", f"Bearer {token}")]


class _Watch:
    """A ``WatchClass`` stream, read on a thread of its own into a queue."""

    def __init__(self, channel: Any, grpc: Any, token: str) -> None:
        self._grpc = grpc
        method = channel.unary_stream(
            "/lessons.v2.WatchService/WatchClass",
            request_serializer=lambda message: message.to_binary(),
            response_deserializer=WatchClassResponse.from_binary,
        )
        self.call = method(WatchClassRequest(), metadata=_bearer(token))
        self.messages: queue.Queue[Any] = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        try:
            for message in self.call:
                self.messages.put(message)
        except self._grpc.RpcError as error:
            self.messages.put(error)
        else:
            self.messages.put(None)

    def next(self) -> Any:
        """The next message, or the error the stream ended with."""
        return self.messages.get(timeout=PATIENCE)

    def next_revision_after(self, revision: str) -> str:
        """The first revision other than ``revision``: heartbeats repeat it."""
        while True:
            message = self.next()
            assert isinstance(message, WatchClassResponse), message
            if message.revision != revision:
                return message.revision

    def close(self) -> None:
        self.call.cancel()


def test_a_unary_call_is_answered_over_native_grpc(channel, seeded) -> None:
    """At the root, where a gRPC client calls, with a credential and without."""
    capabilities = _unary(
        channel, "/lessons.v2.DiaryService/GetDiaryCapabilities", GetDiaryCapabilitiesResponse
    )(GetDiaryCapabilitiesRequest(), timeout=PATIENCE)
    assert capabilities.capabilities.providers
    me = _unary(channel, "/lessons.v2.MeService/GetMe", GetMeResponse)(
        GetMeRequest(), metadata=_bearer(seeded.tokens["editor"]), timeout=PATIENCE
    )
    # The seeded phone, read back from the host's own database.
    assert (me.me.device_name, me.me.linked, me.me.can_edit) == ("editor phone", True, True)


def test_a_method_the_contract_does_not_have_is_unimplemented(channel, grpc) -> None:
    with pytest.raises(grpc.RpcError) as refused:
        _unary(channel, "/lessons.v2.MeService/NoSuchMethod", GetMeResponse)(
            GetMeRequest(), timeout=PATIENCE
        )
    assert refused.value.code() == grpc.StatusCode.UNIMPLEMENTED


def test_watch_class_hears_an_orm_write_a_bulk_write_and_a_bell_change(
    channel, grpc, seeded
) -> None:
    """The three ways a class changes (``app/watch.py``): a row through the
    unit of work, a bulk statement that only touches the bus, and a bell
    period, found through its schedule."""
    watch = _Watch(channel, grpc, seeded.tokens["viewer"])
    try:
        first = watch.next()
        assert isinstance(first, WatchClassResponse), first
        seen = [first.revision]

        homework = Homework(due_date="2026-09-15", subject="Алгебра", text="№ 1–5")
        _unary(channel, "/lessons.v2.HomeworkService/CreateHomework", CreateHomeworkResponse)(
            CreateHomeworkRequest(homework=homework),
            metadata=_bearer(seeded.tokens["editor"]),
            timeout=PATIENCE,
        )
        seen.append(watch.next_revision_after(seen[-1]))

        # A weekday named with nothing under it is emptied: one bulk delete,
        # nothing through the unit of work.
        imported = _unary(
            channel, "/lessons.v2.TimetableService/ImportTimetable", ImportTimetableResponse
        )(
            ImportTimetableRequest(text="== Вторник ==\n", replace=True),
            metadata=_bearer(seeded.tokens["admin"]),
            timeout=PATIENCE,
        )
        assert imported.applied
        seen.append(watch.next_revision_after(seen[-1]))

        periods = [
            BellRow(
                index=index,
                starts_at=starts_at.strftime("%H:%M"),
                ends_at=(datetime.combine(date(2026, 9, 1), ends_at) - timedelta(minutes=5))
                .time()
                .strftime("%H:%M"),
            )
            for index, starts_at, ends_at in DEFAULT_BELLS
        ]
        _unary(channel, "/lessons.v2.BellService/UpdateBellSchedule", UpdateBellScheduleResponse)(
            UpdateBellScheduleRequest(
                schedule=BellMessage(id=seeded.schedule_id, periods=periods),
                update_mask=FieldMask(paths=["periods"]),
            ),
            metadata=_bearer(seeded.tokens["admin"]),
            timeout=PATIENCE,
        )
        seen.append(watch.next_revision_after(seen[-1]))
        assert len(set(seen)) == 4
    finally:
        watch.close()


def test_a_phone_revoked_while_it_watches_is_cut_off(channel, grpc, seeded) -> None:
    """Revoked from another phone, then woken by the next change of its
    class: its gate runs again and refuses it."""
    watch = _Watch(channel, grpc, seeded.tokens["viewer"])
    try:
        assert isinstance(watch.next(), WatchClassResponse)
        _unary(
            channel, "/lessons.v2.ClassDeviceService/RevokeClassDevice", RevokeClassDeviceResponse
        )(
            RevokeClassDeviceRequest(device_id=seeded.devices["viewer"]),
            metadata=_bearer(seeded.tokens["admin"]),
            timeout=PATIENCE,
        )
        homework = Homework(due_date="2026-09-16", subject="Физика", text="§ 3")
        _unary(channel, "/lessons.v2.HomeworkService/CreateHomework", CreateHomeworkResponse)(
            CreateHomeworkRequest(homework=homework),
            metadata=_bearer(seeded.tokens["editor"]),
            timeout=PATIENCE,
        )
        while isinstance(message := watch.next(), WatchClassResponse):
            pass
        assert isinstance(message, grpc.RpcError), message
        assert message.code() == grpc.StatusCode.UNAUTHENTICATED
    finally:
        watch.close()


def test_the_rest_of_the_app_answers_over_http_1_1(http, seeded) -> None:
    """v1, REST and Connect beside native gRPC on one port, and the webhook and
    the tick mounted — refusing a caller without their secret, not missing."""
    bearer = {"Authorization": f"Bearer {seeded.tokens['viewer']}"}
    assert http.get("/api/v1/health").status_code == 200
    assert http.get("/api/v1/me", headers=bearer).status_code == 200
    assert http.get("/api/v2/me", headers=bearer).status_code == 200
    connect = http.post(
        "/api/rpc/lessons.v2.MeService/GetMe",
        content=b"{}",
        headers={**bearer, "Content-Type": "application/json"},
    )
    assert connect.status_code == 200
    assert http.post("/api/v1/telegram/webhook", json={}).status_code == 403
    assert http.get("/api/v1/cron/tick").status_code == 403


def test_a_forged_forwarded_for_buys_no_fresh_join_budget(http) -> None:
    """pyvoy's Envoy took the client from the last ``X-Forwarded-For`` entry
    until ``app.host.envoy_config`` turned that off: a caller writing a new
    one each time would never have met the join limiter. Thirty wrong codes
    from thirty forged addresses, and the next is refused, as from one."""
    answers = [
        http.post(
            "/api/v1/join",
            json={"code": f"NOSUCH{attempt:02d}"},
            headers={"X-Forwarded-For": f"198.51.100.{attempt + 1}"},
        ).status_code
        for attempt in range(30)
    ]
    assert set(answers) == {404}
    refused = http.post(
        "/api/v1/join",
        json={"code": "NOSUCH99"},
        headers={"X-Forwarded-For": "203.0.113.200"},
    )
    assert refused.status_code == 429
```


- [ ] **Step 2: Run it.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_host_job.py tests/test_host_live.py
```
Expected: `5 failed, 6 errors`, and pytest's warning about an unknown mark: `test_host_job.py`'s five find no «Host» job and no registered marker, and `test_host_live.py`'s six, which nothing skips yet, stop in their fixtures on a `KeyError` for `LESSONS_HOST_URL` or `LESSONS_HOST_DATABASE_URL`.

- [ ] **Step 3: Green: the marker, and the job.**

In `.github/workflows/ci.yml`, replace:
```yaml
        run: pytest -q -n auto

  android:
```
with:
```yaml
        run: pytest -q -n auto

  # The host target (server/app/host.py; docs/specs/2026-10-05-server-v2-
  # design.md, decision 13): the app over HTTP/2, which nothing else here
  # starts, under pyvoy and under hypercorn, its fallback. Each is started as a
  # local run starts it — on SQLite and without the deployment marker, whose
  # refusal would reject the SQLite default — with streaming on, and then a
  # native gRPC client (grpcio) asks it what only a long-running HTTP/2 process
  # can answer: a unary call, a method the contract does not have, WatchClass
  # hearing an ORM write, a bulk write and a bell change, a revoked phone's
  # stream cut off, the rest of the app over HTTP/1.1, and a forged
  # X-Forwarded-For buying no fresh join budget. The tests are
  # server/tests/test_host_live.py, behind the `host` marker the ordinary run
  # skips; test_host_job.py holds this job to them. Whenever the server job
  # runs, because anything the server job tests can break the host.
  host:
    name: Host (${{ matrix.server }})
    needs: changes
    if: needs.changes.outputs.server == 'true'
    runs-on: ubuntu-latest
    timeout-minutes: 10
    strategy:
      # One server failing says nothing about the other.
      fail-fast: false
      matrix:
        server: [pyvoy, hypercorn]
    defaults:
      run:
        working-directory: server
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-python@v7
        with:
          python-version: "3.12"
          cache: pip
          cache-dependency-path: |
            requirements.txt
            server/requirements-host.txt
            server/pyproject.toml
      # The image's two locks (the root Dockerfile), the package and its dev
      # tools, and the gRPC client the tests ask with.
      - name: Install
        run: pip install -r ../requirements.txt -r requirements-host.txt -e ".[dev,host-check]"
      # In the background, for the steps after it; the runner stops it with
      # the job. The token is no bot's: it mounts the webhook, which the tests
      # find refusing a caller without the secret, and RUN_BOT=false keeps
      # anything from calling Telegram with it.
      - name: Start the host
        env:
          DATABASE_URL: sqlite+aiosqlite:///${{ runner.temp }}/host.db
          HOST: 127.0.0.1
          PORT: "8000"
          RUN_BOT: "false"
          BOT_TOKEN: "123456:not-a-real-token"
          WEBHOOK_SECRET: ci-webhook-secret
          CRON_SECRET: ci-cron-secret
          LESSONS_STREAMING: "true"
        run: |
          nohup python -m app.host --server ${{ matrix.server }} > "$RUNNER_TEMP/host.log" 2>&1 &
          for attempt in $(seq 1 60); do
            if curl -fsS http://127.0.0.1:8000/api/v1/health > /dev/null 2>&1; then
              exit 0
            fi
            sleep 1
          done
          echo "::error::The host did not answer /api/v1/health within a minute."
          cat "$RUNNER_TEMP/host.log"
          exit 1
      - name: Test against it
        env:
          LESSONS_HOST_URL: http://127.0.0.1:8000
          LESSONS_HOST_DATABASE_URL: sqlite+aiosqlite:///${{ runner.temp }}/host.db
        run: pytest -q -p no:xdist -m host
      # Always, because it is worth most when the step before it failed.
      - name: The host's log
        if: always()
        run: cat "$RUNNER_TEMP/host.log"

  android:
```

In `server/pyproject.toml`, replace:
```toml
asyncio_mode = "auto"
testpaths = ["tests"]
```
with:
```toml
asyncio_mode = "auto"
testpaths = ["tests"]
# What the ordinary run skips: conftest.py skips the mark unless
# LESSONS_HOST_URL names a running host, which CI's «Host» job does.
markers = [
    "host: needs a running host target at LESSONS_HOST_URL (tests/test_host_live.py)",
]
```

In `server/tests/conftest.py`, replace:
```python
            'pip install -r ../requirements.txt -e ".[dev]") and run pytest from it, and '
            "unset a PYTHONPATH that points elsewhere."
        )
```
with:
```python
            'pip install -r ../requirements.txt -e ".[dev]") and run pytest from it, and '
            "unset a PYTHONPATH that points elsewhere."
        )


#: Why a test marked ``host`` did not run.
HOST_ABSENT = (
    "needs a running host target: LESSONS_HOST_URL is unset, as everywhere but "
    "CI's «Host» job (docs/specs/2026-10-05-server-v2-design.md, decision 14)"
)


def pytest_collection_modifyitems(config: pytest.Config, items: list[pytest.Item]) -> None:
    """Skip the tests marked ``host`` unless a host is running to be asked.

    They talk to ``python -m app.host`` over the network (``test_host_live.py``):
    with no address there is nothing to ask, and a test that failed for that
    would only teach people to ignore it. Skipped rather than deselected, so
    the ordinary run's summary says they exist and why they did not run.
    """
    if os.environ.get("LESSONS_HOST_URL"):
        return
    absent = pytest.mark.skip(reason=HOST_ABSENT)
    for item in items:
        if item.get_closest_marker("host") is not None:
            item.add_marker(absent)
```


- [ ] **Step 4: Run it again.** The command of Step 2. Expected: `5 passed, 6 skipped`.

- [ ] **Step 5: The host on this machine, under both servers, asked by the live tests.** This is CI's job by hand, and the only run of it before the push. For each of `pyvoy` and `hypercorn`, start the host in the background, against a SQLite file of its own:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && rm -f C:/Users/lumen/.claude/jobs/c9e2d980/tmp/host-3c.db && DATABASE_URL="sqlite+aiosqlite:///C:/Users/lumen/.claude/jobs/c9e2d980/tmp/host-3c.db" HOST=127.0.0.1 PORT=8000 RUN_BOT=false BOT_TOKEN="123456:not-a-real-token" WEBHOOK_SECRET=local-webhook-secret CRON_SECRET=local-cron-secret LESSONS_STREAMING=true /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/python.exe -m app.host --server pyvoy
```
(run with the Bash tool's `run_in_background`), wait until `curl -fsS http://127.0.0.1:8000/api/v1/health` answers, then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && LESSONS_HOST_URL=http://127.0.0.1:8000 LESSONS_HOST_DATABASE_URL="sqlite+aiosqlite:///C:/Users/lumen/.claude/jobs/c9e2d980/tmp/host-3c.db" /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/pytest.exe -q -p no:xdist -m host
```
Expected, under each server: `6 passed`, and the rest of the suite deselected by `-m host`, in about seven seconds; the host answers `/api/v1/health` a few seconds after it starts. Then stop the host, the launcher and, under pyvoy, the Envoy it started, which ending the background task alone leaves holding port 8000:
```bash
for pid in $(wmic process where "CommandLine like '%app.host%' or name='envoy.exe'" get ProcessId | grep -E '^[0-9]+'); do taskkill //F //T //PID "$pid"; done
```
delete the database file, and do the same with `--server hypercorn`. A `.env` in `server/` is read by the host as by any run; the variables above override what it holds.

- [ ] **Step 6: The gates.** As Task 1's, with `tests/test_host_job.py tests/test_host_live.py` for the format check. Expected: `All checks passed!`, `2 files already formatted`, `Success: no issues found in 240 source files`.

- [ ] **Step 7: The full suite, once, alone.** The controller runs it: `pytest -q -n 4`, from `$WT/server`, with no other test process running. Expected: every test passes but the six `test_host_live.py` skips: `3222 passed, 6 skipped`. Keep the summary line and the time for Task 8.

- [ ] **Step 8: Commit.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3c-t7.txt`:
```text
Start the host in CI under both servers and ask it over native gRPC

A «Host» job, a matrix of pyvoy and hypercorn run whenever the server job
is, installs the image's two locks, starts python -m app.host on SQLite
without the deployment marker and with streaming on, and runs
tests/test_host_live.py against it with grpcio: a unary call at the
root, a method the contract does not have, WatchClass hearing an ORM
write, a bulk write and a bell change, a revoked phone's stream cut off,
v1, REST, Connect, the webhook and the tick over HTTP/1.1, and thirty
wrong join codes from thirty forged addresses followed by a refusal. The
tests are marked host and skipped by every other run, unless
LESSONS_HOST_URL names a running host; test_host_job.py holds the job to
them.

Not covered: the image itself, and a host anywhere but a runner.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230 && git add server/tests/test_host_live.py server/tests/test_host_job.py server/tests/conftest.py server/pyproject.toml .github/workflows/ci.yml && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3c-t7.txt
```


---

### 3c Task 8: The documents, the counts, the HANDOVER close-out

**Files:**
- Modify: `docs/api.md`, `docs/deploy.md`, `docs/architecture.md`, `docs/build.md`, `docs/README.md`, `README.md`, `CLAUDE.md`, `CONTRIBUTING.md`, `.claude/skills/gates/SKILL.md`, `.claude/skills/github-pr/SKILL.md`, `.claude/agents/build-ci.md`, `.claude/agents/deploy-ops.md`, `.claude/agents/server-tests.md`, `HANDOVER.md`, `docs/history.md`
- Scratch, never committed: `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\docs3c.py`. 3b-3's `counts3b2_3b3.py` and `scan_heads_3b3.py`, in the same folder, are used again as they are.

**Interfaces:**
- Consumes: Tasks 1 to 7, and the numbers of Task 7's full run; the documents as 3b-8's merge leaves them («Where 3b-8 moves an anchor»); what followed 3b-8's merge, which the controller hands over at Step 8 for the slot `[AFTER-3b8]`; this pull request's number, `#PR`, which exists only once the controller opens it (Step 7), and `#395`.
- Produces: documents that are true at the moment the pull request merges, and the post-merge read.

The anchors below are `a434c9c`'s, at lines 3b-8's Task 5 does not rewrite. If a line has moved, read it as it stands and apply the same change to it. `#395` in the text below is written as the number the controller filed.

- [ ] **Step 1: Red: the documents say nothing of the host.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\docs3c.py`:
```python
"""Which documents do not yet say what 3c serves (3c, Task 8)."""

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(
    "C:/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230"
)
SAID = {
    "docs/api.md": ["### The host target, and WatchClass", "`/lessons.v2.<Service>/<Method>`"],
    "docs/deploy.md": ["## Option 3: the host target", "`LESSONS_TARGET=host`"],
    "docs/architecture.md": [
        "### The host target, and a stream that hears every shell",
        "├── watch.py",
        "├── host.py",
    ],
    "docs/build.md": ["the host's `Dockerfile` and\n  `.dockerignore`"],
    "docs/README.md": ["or the host target"],
    "README.md": ["| The host target and `WatchClass` |", "Nor has the host's\n  image"],
    "CLAUDE.md": [
        "- `python -m app.host`",
        "- `watch.py`",
        "- `host.py`",
        "**A bulk statement on a window table must touch the bus.**",
        "«Host», under pyvoy and under hypercorn",
        "`server/requirements-host.txt` is the host target's lock",
    ],
    "CONTRIBUTING.md": ["the «Host» job"],
    ".claude/skills/gates/SKILL.md": ["«Host», under pyvoy and hypercorn"],
    ".claude/skills/github-pr/SKILL.md": ["`server/requirements-host.txt` is regenerated"],
    ".claude/agents/build-ci.md": ["«Host», a matrix of pyvoy and hypercorn"],
    ".claude/agents/deploy-ops.md": ["The host target (`python -m app.host`"],
}
STALE = re.compile(r"no deployment streams yet|on the long-running host target only")

problems = []
for name, phrases in SAID.items():
    text = (ROOT / name).read_bytes().decode("utf-8").replace("\r\n", "\n")
    problems += [f"{name}: missing {phrase!r}" for phrase in phrases if phrase not in text]
    problems += [f"{name}: still says {match.group(0)!r}" for match in STALE.finditer(text)]
print("\n".join(problems) or "the documents say what 3c serves")
sys.exit(1 if problems else 0)
```
and run it:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/python.exe C:/Users/lumen/.claude/jobs/c9e2d980/tmp/docs3c.py
```
Expected: exit 1, with twenty-two `missing` lines and two `still says` lines, both `docs/api.md`'s. Keep this output as the evidence.

- [ ] **Step 2: `docs/api.md` and `docs/deploy.md`.**

In `docs/api.md`, replace:
```markdown
- **Native gRPC**, on the same path, on the long-running host target only: Vercel passes no
  response trailers, and gRPC carries its status in them.

One method, `WatchService.WatchClass`, is a server stream: a beta of the host target, with
no REST binding.

On this deployment, which speaks HTTP/1.1, a request with `Content-Type: application/grpc` or
`application/grpc+…` is refused with `415` and a sentence saying where native gRPC is served;
gRPC-Web passes. `WatchClass` answers `UNIMPLEMENTED` with the reason `FEATURE_UNSUPPORTED`
(`feature: "streaming"`): no deployment streams yet. If a deployment cannot load v2 at all,
```
with:
```markdown
- **Native gRPC**, on the same path and at the root, `/lessons.v2.<Service>/<Method>`, where a
  gRPC client calls when it is given no prefix, on the host target only («The host target,
  and WatchClass», below): Vercel passes no response trailers, and gRPC carries its status in
  them.

One method, `WatchService.WatchClass`, is a server stream: a beta of the host target, with
no REST binding.

On Vercel, which speaks HTTP/1.1, a request with `Content-Type: application/grpc` or
`application/grpc+…` is refused with `415` and a sentence saying where native gRPC is served;
gRPC-Web passes. A native gRPC request that reaches the host over HTTP/1.1 — through a proxy
that does not speak HTTP/2 to it — is refused the same way. `WatchClass` answers
`UNIMPLEMENTED` with the reason `FEATURE_UNSUPPORTED` (`feature: "streaming"`) on Vercel,
whatever it is told, and wherever `LESSONS_STREAMING` is not `true`. If a deployment cannot
load v2 at all,
```

In `docs/api.md`, replace:
```markdown
otherwise spend the visitor's address's join budget (#340, #341). A request without a body is
unaffected.

### What REST adds
```
with:
```markdown
otherwise spend the visitor's address's join budget (#340, #341). A request without a body is
unaffected.

### The host target, and WatchClass

`python -m app.host` serves this whole API — v1, v2 over REST and Connect, the webhook and
the tick — and native gRPC, over HTTP/1.1 and HTTP/2 with prior knowledge on one port
(`docs/deploy.md`, «Option 3: the host target»). It is the same app, and nothing on this
page answers differently there but two things.

- **Native gRPC.** A refusal is the same `google.rpc.Status` the other transports carry, in
  `grpc-status`, `grpc-message` and `grpc-status-details-bin`. A method the contract does not
  have is `UNIMPLEMENTED`.
- **`WatchClass` streams**, where `LESSONS_STREAMING` is `true`. The first message is the
  class's `revision` now. Another follows whenever the class changes — its card, a member's
  role, its bells, its timetable or subjects, a term, a day's mark, a substitution, an event
  or homework — whether the bot, v1 or v2 changed it, and the same one again at least every
  thirty seconds while nothing does. A revision the phone already holds means nothing
  changed; on any other it fetches `GetScheduleWindow` as it does anyway, and a burst of
  changes is one message. A host that restarted never repeats a revision from before. A
  pupil's task, a tick, the diary, the journal and a phone's own row change no revision.
  `changed_at` is when the host last saw the class change, unset when it has seen none since
  it started. Before each message after the first, the stream asks the gate again: a phone
  revoked, or a class deleted, ends its stream with `UNAUTHENTICATED` /
  `DEVICE_TOKEN_INVALID` at the next change, or within the thirty seconds. The host is one
  instance, because a stream hears what that one process writes; a script writing the
  database directly wakes nobody.

### What REST adds
```

In `docs/deploy.md`, replace:
```markdown
If being free is a hard requirement, take the first. If four euros a month are acceptable,
the second is simpler, faster and requires rewriting nothing.
```
with:
```markdown
If being free is a hard requirement, take the first. If four euros a month are acceptable,
the second is simpler, faster and requires rewriting nothing.

A third, the host target, is for what neither serves: native gRPC and the class's changes
streamed to a phone as they happen. It is a beta, deployed nowhere yet («Option 3: the host
target», below).
```

In `docs/deploy.md`, replace:
```markdown
The check is switched on by the `VERCEL` variable, which the platform sets about itself.
Guessing "this looks like production" anywhere else would one day mean refusing to start on
somebody's laptop, and that is worse than what the check prevents. Your own server
(option 2) is configured by hand from the same table.
```
with:
```markdown
The check is switched on by the `VERCEL` variable, which the platform sets about itself,
and by `LESSONS_TARGET=host`, which the host's image (option 3) sets about itself; a
`LESSONS_TARGET` that says anything else is refused too, so that a misspelt marker cannot
leave a deployment on the local defaults. Guessing "this looks like production" anywhere
else would one day mean refusing to start on somebody's laptop, and that is worse than what
the check prevents. Your own server (option 2) is configured by hand from the same table.
```

In `docs/deploy.md`, replace:
```markdown
`DIARY_SECRET`, `DADATA_TOKEN`, `DIARY_PROXY_URL`, `PUBLIC_BASE_URL`, `MIN_CLIENT_VERSION`,
`RUN_BOT` and `TRUSTED_PROXY_HOPS` from the same `.env`, each arriving as its own default when
unset.
```
with:
```markdown
`DIARY_SECRET`, `DADATA_TOKEN`, `DIARY_PROXY_URL`, `PUBLIC_BASE_URL`, `MIN_CLIENT_VERSION`,
`RUN_BOT`, `TRUSTED_PROXY_HOPS` and `LESSONS_STREAMING` from the same `.env`, each arriving as
its own default when unset. This image speaks HTTP/1.1, so `WatchClass` streams over Connect
there and native gRPC is option 3's alone.
```

In `docs/deploy.md`, replace:
```markdown
Any VPS with 1 GB of memory will do. One class's load is a few hundred requests a day.

## The files Vercel needs
```
with:
````markdown
Any VPS with 1 GB of memory will do. One class's load is a few hundred requests a day.

## Option 3: the host target

The same app as Vercel's, and everything it serves — v1, v2 over REST and Connect, the
webhook and the tick — from one long-running process over HTTP/2, which adds what Vercel
cannot: native gRPC, and `WatchClass`, the class's changes streamed to a phone while the app
is open (`docs/api.md`, «The host target, and WatchClass»). It is a whole deployment, chosen
**instead of** Vercel, not beside it: a stream hears only what its own process writes, and
on Vercel the bot's webhook would land elsewhere. **A beta, and deployed nowhere yet**: where
it runs is decided when a phone needs it (the server-v2 design, question 3).

```bash
docker build -t lessons-host .          # from the repository's root
docker run -p 8000:8000 --env-file host.env lessons-host
```

- **The image is the root `Dockerfile`**, not `server/Dockerfile`, which is option 2's. It
  installs Vercel's lock and the host's (`server/requirements-host.txt`, compiled against
  it), then the package without its floors, and runs `python -m app.host` as a user of its
  own. Nothing here has built it: there is no Docker where this project is developed. CI's
  «Host» job installs the same two locks and starts the same command on every change to the
  server.
- **It says it is a deployment.** The image sets `LESSONS_TARGET=host`, and with it the server
  refuses to start without what option 1's table asks for, exactly as on Vercel — the
  database, `BOT_TOKEN`, `WEBHOOK_SECRET` and `RUN_BOT=false` among them («What is missing is
  said at the door»). `host.env` is that table's values, and `LESSONS_STREAMING=true` for the
  stream.
- **The webhook and the cron point at it.** Register the webhook with the host's own HTTPS
  address («Registering the webhook»), and point the external cron at its
  `/api/v1/cron/tick` («The external cron»): the host has no clock either, by the same rule.
  Telegram hands updates to one consumer, so the webhook can point at the host or at Vercel,
  never both.
- **One instance.** Two would each stream what they wrote and miss what the other did.
- **HTTP/2 the whole way.** The host serves HTTP/1.1 and HTTP/2 with prior knowledge on one
  plain port and terminates no TLS, so it sits behind something that does. For native gRPC
  that proxy must speak HTTP/2 to it: one that downgrades to HTTP/1.1 gets the `415` Vercel
  gives, and Connect and REST work either way. Behind it, `TRUSTED_PROXY_HOPS=1` is what lets
  the throttles see the caller rather than the proxy, as on option 2. The host takes the
  client's address from the connection and never from `X-Forwarded-For` by itself: pyvoy's
  Envoy did the second until `app.host` turned it off, and a caller who wrote a new address
  each time would never have been throttled (#395).
- **Migrations** are applied from a workstation, before the deploy that needs them, as for
  Vercel; the image carries no `alembic`.
- **The self-check's `deploy` check** reads what Vercel says about the running deployment,
  so on the host it answers ❔ «not on Vercel»; the schema, v2 and the diary's proxy are
  checked there as anywhere («Monitoring», below).

To run it on a laptop, from `server/` with the dev install: `pip install -r
requirements-host.txt`, which resolves for Windows as well as Linux, then `python -m
app.host`, on the local defaults and SQLite, without the marker — `--server hypercorn` is the
fallback the 5 October spike proved beside pyvoy. A host on the local defaults streams only
when `LESSONS_STREAMING=true`.

## The files Vercel needs
````


- [ ] **Step 3: `docs/architecture.md`, `docs/build.md`, `docs/README.md` and `README.md`.**

In `docs/architecture.md`, replace:
```markdown
├── observability.py  Sentry's start and its scrubbing, imported only where SENTRY_DSN is set
```
with:
```markdown
├── observability.py  Sentry's start and its scrubbing, imported only where SENTRY_DSN is set
├── watch.py       the class-changed bus WatchClass streams from, fed by the session itself
├── host.py        the host target: the whole app over HTTP/2, under pyvoy or hypercorn
```

In `docs/architecture.md`, replace:
```markdown
### The tick checks the deployment, and tells its owner
```
with:
```markdown
### The host target, and a stream that hears every shell

`python -m app.host` serves the same app as Vercel, under pyvoy — Envoy with the app
inside it — or hypercorn, and adds what HTTP/2 and a long-running process allow: native
gRPC, also at the root where a gRPC client calls, and `WatchClass`, a stream of «this class
changed» (`docs/specs/2026-10-05-server-v2-design.md`, decision 13). It is a complete
deployment chosen instead of Vercel, because of how the stream hears a change.

`app/watch.py` listens to the session itself, so it hears the bot, v1 and v2 alike: before
each flush it collects the class of every row written to a table a schedule window is read
from — a bell period through its schedule, the class through its own id — and it publishes
them once the transaction's outermost commit is done, never at a savepoint's release, whose
write nobody else can read yet. A bulk `update` or `delete` never passes through the unit of
work, so every one on such a table calls `watch.touch` with the class it has at hand, and
`tests/test_watch.py` walks all of `app/` and fails on one that does not. A process hears
only its own writes: a host beside Vercel would never hear an edit the webhook made there,
which is why the host replaces Vercel, and why it runs as one instance.

The stream holds no database session between messages (`rpc/call.py`, `stream`): the gate
runs in a scope of its own before the first message and again before each one after it, so
a revoked phone or a deleted class ends its stream, and a hundred open phones hold no pooled
connection. The host never sets its own deployment marker, `LESSONS_TARGET`; the root
`Dockerfile` does, and with it the host refuses to start without what a deployment needs, as
Vercel does.

### The tick checks the deployment, and tells its owner
```

In `docs/build.md`, replace:
```markdown
  the two requirements files or `docker-compose.yml`, each read by a server test.
```
with:
```markdown
  the two requirements files, `docker-compose.yml` or the host's `Dockerfile` and
  `.dockerignore`, each read by a server test; the «Host» job runs whenever the server job
  does.
```

In `docs/README.md`, replace:
```markdown
| [deploy.md](deploy.md) | Vercel plus Neon or your own server, the webhook,
```
with:
```markdown
| [deploy.md](deploy.md) | Vercel plus Neon, your own server, or the host target — the whole app over HTTP/2, with native gRPC and the streaming beta — the webhook,
```

In `README.md`, replace:
```markdown
| Monitoring | the tick's self-check of the schema,
```
with:
```markdown
| The host target and `WatchClass` | `python -m app.host`, under pyvoy and under hypercorn, started on SQLite by CI's «Host» job and asked over native gRPC: a unary call, a method the contract lacks, `WatchClass` hearing an ORM write, a bulk write and a bell change, a revoked phone cut off, and a forged `X-Forwarded-For` throttled all the same; the bus and the stream are tested in-process too. The image has never been built here, and the host is deployed nowhere; no phone streams yet |
| Monitoring | the tick's self-check of the schema,
```

In `README.md`, replace:
```markdown
  the revisions now in the image are written and never watched coming up.
```
with:
```markdown
  the revisions now in the image are written and never watched coming up. Nor has the host's
  image, the root `Dockerfile`: CI starts `python -m app.host` from the same two locks
  instead, on Linux, and the file is read by a test.
```


- [ ] **Step 4: `CLAUDE.md`, `CONTRIBUTING.md`, the skills and the agents.**

In `CLAUDE.md`, replace:
```markdown
api/         thin Vercel entry point that re-exports server/app/main.py
```
with:
```markdown
api/         thin Vercel entry point that re-exports server/app/main.py
Dockerfile   the host target's image (server/app/host.py), built from the root:
             the whole app over HTTP/2, chosen instead of Vercel; server/Dockerfile
             is docker-compose.yml's
```

In `CLAUDE.md`, replace:
```markdown
command; never edit a pin by hand. CI installs the lock too, so the tests run on what deploys.
```
with:
```markdown
command; never edit a pin by hand. CI installs the lock too, so the tests run on what deploys.
`server/requirements-host.txt` is the host target's lock: its server and nothing else,
compiled from `requirements-host.in` against `requirements.txt` by the command in its
header, for every platform, so the two cannot disagree (`test_host_image.py`); the root
`Dockerfile` and CI's «Host» job install both, and Vercel neither sees nor imports it.
```

In `CLAUDE.md`, replace:
```markdown
- `python -m uvicorn app.main:app --reload` — run it; add `--host 0.0.0.0` for a phone to
  reach it
```
with:
```markdown
- `python -m uvicorn app.main:app --reload` — run it; add `--host 0.0.0.0` for a phone to
  reach it
- `python -m app.host` — the host target: the same app over HTTP/2 under pyvoy, with native
  gRPC and, with `LESSONS_STREAMING=true`, `WatchClass`; `--server hypercorn` is the
  fallback. Needs `pip install -r requirements-host.txt` beside the dev install. Without
  `LESSONS_TARGET`, which only the root `Dockerfile` sets, it runs on the local defaults
- `LESSONS_HOST_URL=http://127.0.0.1:8000 LESSONS_HOST_DATABASE_URL=sqlite+aiosqlite:///…
  pytest -q -p no:xdist -m host` — the tests of a running host (`tests/test_host_live.py`,
  with grpcio from `.[host-check]`), which every other run skips; CI's «Host» job runs them
```

In `CLAUDE.md`, replace:
```markdown
and the check that the committed generated code is what `buf generate` writes. Nothing else.
```
with:
```markdown
and the check that the committed generated code is what `buf generate` writes; and,
whenever the server job runs, «Host», under pyvoy and under hypercorn: `python -m app.host`
started on SQLite, asked over native gRPC by `tests/test_host_live.py`. Nothing else.
```

In `CLAUDE.md`, replace:
```markdown
  (client version, then the bearer, the link and the role), `call.py` (`invoke`: the gate,
  one dishka scope, the handler, the one commit, then the effects), `errors.py` (the one error
```
with:
```markdown
  (client version, then the bearer, the link and the role), `call.py` (`invoke`: the gate,
  one dishka scope, the handler, the one commit, then the effects; and `stream`, which serves
  `WatchClass` with the gate in a scope of its own for every check and no session held
  between), `errors.py` (the one error
```

In `CLAUDE.md`, replace:
```markdown
  `handlers.py` (which methods are served). A handler never commits and never
```
with:
```markdown
  `handlers.py` (which methods are served, and `STREAMS`). A handler never commits and never
```

In `CLAUDE.md`, replace:
```markdown
  (152-ФЗ), which `tests/test_observability.py` proves on an event built from a real request
```
with:
```markdown
  (152-ФЗ), which `tests/test_observability.py` proves on an event built from a real request
- `watch.py` — the class-changed bus `WatchClass` streams from: before each flush it collects
  the class of every row written to a window table, a bell period through its schedule;
  `touch` says the same for a bulk statement; the outermost commit publishes, a savepoint's
  release never does, a rollback forgets. Attached by `app.main` only where streaming is on
  (`Settings.streaming_enabled`, never on Vercel); neutral, so services import it
- `host.py` — the host target, `python -m app.host`: the app under pyvoy (or hypercorn) over
  HTTP/2, native gRPC answered at the root too, pyvoy's Envoy told to take the client from
  the connection. It reads the settings before any server starts and never sets
  `LESSONS_TARGET`; nothing on the API's path imports it, pyvoy or hypercorn
```

In `CLAUDE.md`, replace:
```markdown
  raises `DeploymentNotConfigured` when `VERCEL` is set and any of `DATABASE_URL`,
```
with:
```markdown
  raises `DeploymentNotConfigured` when `VERCEL` is set, or `LESSONS_TARGET` (the host
  image's marker, `host` and nothing else), and any of `DATABASE_URL`,
```

In `CLAUDE.md`, replace:
```markdown
  project. The check hangs on `VERCEL` because the platform sets it about itself; guessing
```
with:
```markdown
  project. The check hangs on `VERCEL` and `LESSONS_TARGET` because each is a deployment's
  statement about itself, and a `LESSONS_TARGET` that names no target is refused; guessing
```

In `CLAUDE.md`, replace:
```markdown
  neither list. What Vercel says about the running deployment — `VERCEL_ENV`,
```
with:
```markdown
  neither list. `LESSONS_STREAMING` is optional too — off unless `true`, and never on
  Vercel whatever it says. What Vercel says about the running deployment — `VERCEL_ENV`,
```

In `CLAUDE.md`, replace:
```markdown
- **The widget's size ladder has twelve rungs, and the count is the point.**
```
with:
```markdown
- **A bulk statement on a window table must touch the bus.** `WatchClass` hears a class
  change through the session (`app/watch.py`), and an `update`, `delete` or `insert`
  executed directly never passes through the unit of work. Whoever writes one on a table a
  schedule window is read from calls `watch.touch(session, class_id)` in the same function,
  or a phone watching the class never learns of the change; `tests/test_watch.py` walks all
  of `app/` for them, the bot included, and names the function that forgot.
- **The widget's size ladder has twelve rungs, and the count is the point.**
```

In `CONTRIBUTING.md`, replace:
```markdown
contract changed, `buf lint`, `buf breaking` and a check that `server/app/contract/` is what
`buf generate` writes. The release
```
with:
```markdown
contract changed, `buf lint`, `buf breaking` and a check that `server/app/contract/` is what
`buf generate` writes; and, whenever the server's do, the «Host» job, which starts `python -m
app.host` under pyvoy and under hypercorn and asks it over native gRPC. The release
```

In `.claude/skills/gates/SKILL.md`, replace:
```markdown
assembles, and `./gradlew detekt` as a step of its own after them; and «Contract (Buf)» when
the contract changed. Nothing else.
```
with:
```markdown
assembles, and `./gradlew detekt` as a step of its own after them; «Contract (Buf)» when
the contract changed; and «Host», under pyvoy and hypercorn, whenever the server job runs: the
host started on SQLite, and `pytest -m host` asking it over native gRPC — tests the ordinary
run skips, and which need a Linux runner, the host's lock and a network. Nothing else.
```

In `.claude/agents/build-ci.md`, replace:
```markdown
`assembleRelease`, `./gradlew detekt`, and the path-filtered «Contract (Buf)» job (`buf lint`,
`buf breaking` against the base, the generated-code check), which reads no secret. Nothing
else.
```
with:
```markdown
`assembleRelease`, `./gradlew detekt`, the path-filtered «Contract (Buf)» job (`buf lint`,
`buf breaking` against the base, the generated-code check), which reads no secret, and
«Host», a matrix of pyvoy and hypercorn run whenever the server job is: `python -m app.host`
started in the background on SQLite and `pytest -m host` asking it over native gRPC
(`server/tests/test_host_job.py` holds the job to those tests). Nothing
else.
```

In `.claude/agents/deploy-ops.md`, replace:
```markdown
Locally the bot long-polls from the `app/main.py` lifespan; on serverless it is a webhook.
`RUN_BOT=false` starts the API alone.
```
with:
```markdown
Locally the bot long-polls from the `app/main.py` lifespan; on serverless it is a webhook.
`RUN_BOT=false` starts the API alone.

The host target (`python -m app.host`, the root `Dockerfile`; `docs/deploy.md`, «Option 3»)
is a whole deployment chosen instead of Vercel, never beside it: it sets `LESSONS_TARGET=host`
about itself and refuses to start exactly as Vercel does, takes the webhook and the cron, and
runs as one instance, because its stream hears only its own process's writes. It is deployed
nowhere yet.
```

In `.claude/skills/github-pr/SKILL.md`, replace:
```markdown
  edited. A bump of a transitive package changes the lock alone and needs nothing.
```
with:
```markdown
  edited. A bump of a transitive package changes the lock alone and needs nothing — unless
  the host's lock pins it too: `test_host_image.py` then fails and names it, and
  `server/requirements-host.txt` is regenerated with the command in its header, from
  `server/`, in the same pull request.
```


- [ ] **Step 5: Green: the documents, and what the tests read of them.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/python.exe C:/Users/lumen/.claude/jobs/c9e2d980/tmp/docs3c.py
```
Expected: `the documents say what 3c serves`, exit 0. Then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_schema_version.py tests/test_ci_paths.py tests/test_deployment_config.py tests/test_env_example.py tests/test_rpc_errors.py tests/test_api_docs.py tests/test_bot_commands.py tests/test_host_job.py
```
Expected: `210 passed, 1 skipped` — the skip is `test_host_job.py`'s check that the ordinary run skips the host's tests, which skips itself when `test_host_live.py` is not in the run. `test_schema_version.py` reads every document that names the schema, this plan included; `test_deployment_config.py` the heading the refusal names; `test_env_example.py` `docs/build.md`'s secrets. Then 3b-3's head scan:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/python.exe C:/Users/lumen/.claude/jobs/c9e2d980/tmp/scan_heads_3b3.py
```
Expected: the last line names `0019` alone, and no line names `docs/specs/`.

- [ ] **Step 6: The gates, and their numbers everywhere.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/python.exe -m ruff check app tests scripts migrations && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/python.exe -m mypy
```
Expected: `All checks passed!`, and `Success: no issues found in 240 source files`. The suite is not run again: Task 7's run is the batch's one, and nothing but documents has changed since it, whose tests Step 5 ran. Then 3b-3's script, which takes the four numbers, the old count first:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230/server/.venv/Scripts/python.exe C:/Users/lumen/.claude/jobs/c9e2d980/tmp/counts3b2_3b3.py 3142 NEW_TESTS 238 240
```
with `NEW_TESTS` the collected count of Task 7's run, passed and skipped together: `3228` if nothing else moved. Expected: `written`. These are the seven places the `handover` skill names. Then, by hand, in the two of them that describe the suite in a sentence — `CLAUDE.md`'s `pytest -q -n auto` bullet and the `gates` skill's step 3 — add after the number: «, six of them skipped unless a host is running (`-m host`, CI's «Host» job)». The batch sections' own counts in `HANDOVER.md` are records of their commits, and they stay.

- [ ] **Step 7: Commit the documents, and the controller opens the pull request.** Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3c-t8.txt`:
```text
Describe the host target, its stream, its image and its CI job

docs/api.md says where native gRPC is answered - at the root as well,
on the host alone - and what WatchClass promises: a revision on open,
on every change any shell makes and every thirty seconds, a stream cut
off when its phone is revoked or its class deleted, one instance.
docs/deploy.md gains «Option 3: the host target»: the root Dockerfile,
the marker and the refusal it switches on, the webhook and the cron
pointed at it, HTTP/2 the whole way, and why Envoy is told to take the
client from the connection (#395). docs/architecture.md says how the
bus hears every shell and why the host replaces Vercel. CLAUDE.md names
watch.py and host.py, the host's lock, the commands, the «Host» job and
the rule that a bulk statement on a window table touches the bus; the
skills and the agents say the same where they describe CI, a deploy or
a dependabot bump. The counts are the run's own.

Not covered: HANDOVER.md's close-out, written once the pull request has
a number.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230 && git add docs/api.md docs/deploy.md docs/architecture.md docs/build.md docs/README.md README.md CLAUDE.md CONTRIBUTING.md .claude/skills/gates/SKILL.md .claude/skills/github-pr/SKILL.md .claude/agents/build-ci.md .claude/agents/deploy-ops.md .claude/agents/server-tests.md HANDOVER.md && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3c-t8.txt
```
The controller pushes and opens the pull request (the `github-pr` skill), from `server-v2/3c` to `main`, on milestone 11, with its board item filled as the skill says. Its body says `Closes #395`, with its number, refers to #273, and says that no revision goes with it, that `watch.proto` changes in comments only, and that two «Host» jobs run on it for the first time. Write the number it gets down as `#PR`.

- [ ] **Step 8: The HANDOVER close-out** (the `handover` skill, «What goes stale mechanically»). Write it while the pull request is open, after its CI has run once, because the «Host» job's first result belongs in it. Read `HANDOVER.md`'s batch sections first: after 3b-8's merge they are 3b-8's and 3b-7's.
  1. **The chain of batch sections.**
     - The section «## What the session before it added: the diary's registry as a table, its sessions and its reads over v2 — stage 3b-7 of sub-project 3 (#273)», with all its subsections, moves verbatim to the top of `docs/history.md`, directly under the `---` that closes the file's introduction, retitled «## What the batch before added: …»; its subsections keep their titles. A sentence in it that says «section 5» or «above» now names `HANDOVER.md`, as the skill says.
     - The section on 3b-8, «## What the last session added: the diary's corrections over v2, a batch at a time and all or none — stage 3b-8 of sub-project 3 (#273)», becomes «## What the session before it added: …», with its subsections. Its first sentence, which names its pull request as open, becomes «Merged as #… (`[SHA]`, <date>), from `server-v2/3b-8`, on milestone 11.», with the number, the SHA and the date read from `gh pr view`.
  2. **The new section**, above it, with the run's numbers, the real SHAs and the numbers in place of the bracketed words and the placeholders:
```markdown
## What the last session added: the host target, its stream and its CI job — stage 3c of sub-project 3 (#273)

Open as #PR, from `server-v2/3c` to `main`, on milestone 11, and on project 6. It closes #395,
and refers to #273. The branch was cut from `main` at `[short SHA]`, the merge of 3b-8, and
carries [the number of] commits before this close-out, to `[short SHA]`. Written on [date]. No
revision goes with it: the schema stays at `0019`. This is stage 3c of
`docs/specs/2026-10-05-server-v2-design.md`, the last, built by
`docs/specs/2026-10-05-server-v2-3c-plan.md`, one task at a time, each reviewed before the
next. With it the design is delivered: v1 answers as before, v2 serves every unary method on
both targets, and the host target serves native gRPC and `WatchClass` besides.

- **The class-changed bus, `app/watch.py`.** It listens to the session, so the bot, v1 and v2
  are heard alike: before each flush it collects the class of every row written to a table a
  window is read from — `bot_users` among them, since every window carries the caller's role —
  and publishes them at the transaction's outermost commit, never at a savepoint's release. A
  bulk statement touches it, and a walk of all of `app/` holds that; the bot's «-» on a
  weekday, a bare delete in its handler, goes through `structure.apply_timetable` now.
- **`WatchClass` streams** where `LESSONS_STREAMING=true` and the target is not Vercel: the
  class's revision on open, on every change, and every thirty seconds; `call.stream` asks the
  gate again, in a scope of its own, before every message after the first, so a revoked phone
  or a deleted class ends its stream, and no watcher holds a pooled connection.
- **The host, `python -m app.host`**: the app under pyvoy (one thread, lifespan required,
  gzip), or hypercorn on `--server hypercorn`; native gRPC answered at the root too. #395:
  pyvoy's own Envoy took the client's address from `X-Forwarded-For`; `app.host` turns that
  off and refuses a configuration it cannot correct.
- **The marker and the switch.** `LESSONS_TARGET=host`, set by the root `Dockerfile` alone,
  makes the settings refusal apply as on Vercel; any other value is refused. The suite forces
  streaming off and drops the marker.
- **The host's lock and image.** `server/requirements-host.txt`, compiled against
  `requirements.txt` for every platform and held equal to it on every shared pin; the root
  `Dockerfile` installs both and the package without its floors, as a user of its own.
- **CI's «Host» job**, under pyvoy and under hypercorn: the host on SQLite, asked over native
  gRPC by `tests/test_host_live.py`, which every other run skips. [The job's first result on
  #PR: each server's time and outcome.]
- **`watch.proto`** says, in comments only, what a stream sends and when it ends.

### Gates

The full suite ran once, at `[short SHA]`, the head of the seven code tasks; the documents
(`[short SHA]`) came after it, and their own files ran again. CI runs on the head the merge is
made from, and the merge waits for it to be green.

- **ruff**: `ruff check app tests scripts migrations`, all checks passed, at `[short SHA]`.
- **mypy**: no issues found in 240 source files, at `[short SHA]`, with pyvoy and hypercorn
  installed.
- **The server suite.** `pytest -q -n 4`, run alone from `server/` at `[short SHA]`, gave
  **[the number] passed, 6 skipped** in [the time]. The seven places the `handover` skill
  names say [the collected number].
- **The host by hand.** `python -m app.host`, under pyvoy and under hypercorn on this machine,
  answered `pytest -m host`: [each server's summary line].
- **The contract**: `buf lint` exit 0; `buf breaking --against .git#ref=origin/main` exit 0;
  `buf generate` reproduces the committed files, `watch_connect.py`'s docstrings and
  `watch_pb.py`'s field comments the only change.
- **CI on the head** is read before the merge; the «Contract» job runs, since `proto/`
  changed, and the two «Host» jobs run for the first time.
- **Android** was not run, because nothing under `android/` changed; its count stands.

### What was deliberately left alone

- **A deployment of the host**, which waits for a phone that needs it (the design's
  question 3); a host inside Russia would also be the egress #235 waited for.
- **The image itself**, never built: there is no Docker here, and CI starts the same command
  from the same two locks instead.
- **`device_tokens` on the bus**: a phone's link reaches it at its next sync, because every
  call writes `last_seen_at` there.
- **Dependabot for the host's lock**: a bump of Vercel's lock that moves a shared pin fails
  `test_host_image.py` until the host's is regenerated (the `github-pr` skill).
- **`server/Dockerfile`**, compose's, which still installs `pyproject.toml`'s floors rather
  than the lock (said in the 3c plan, not filed).
- **The app's side of the stream**, sub-project 5's stage 5c.

### What nobody has verified in this batch

- **The host anywhere but a CI runner and this machine**: never behind a TLS proxy, never on
  Postgres, never with a real bot's webhook pointed at it.
- **A stream held for hours**: the longest held in a test is seconds; Envoy's five-minute idle
  timeout is answered by the heartbeat on the strength of its documentation and an
  eighty-second probe.
- **A phone over native gRPC**: no APK speaks it before sub-project 5's stage 5c.
- **The image**: never built, never run.

### After 3b-8's merge: [the controller's title for it]

None of this is code in #PR, and a close-out never gets a close-out of its own, so it is
written here. The source is the controller's notes of [date].

[AFTER-3b8: the controller's facts, handed over at this step and written in the shape of the
last close-out's «After …'s merge», one bullet each: 3b-8's merge and its CI; whether Vercel
built production from it or the owner had to promote it; what production answered after it;
what the monitoring said meanwhile; and anything the owner did or decided since the last
close-out. Nothing here is guessed: what the controller does not hand over is left out, and if
it hands over nothing, this subsection is left out whole and the report says so.]
```
  3. **The opening paragraph**, in the shape the last close-out left it: «Last updated:» is the day of writing; the merged list gains 3b-8's pull request (read back with `gh pr view` first); `main` is at its merge, or at whatever `git log -1 origin/main` says is newer; the open pull requests are read from `gh pr list --state open`, #PR among them, «the one carrying this paragraph», from `server-v2/3c`, on milestone 11, which closes #395 and refers to #273: the host target, its stream and its CI job; the schema did not move, still `0019`, and `EXPECTED_REVISION` did not move either; the issues filed since 3b-8 merged are named, #395 among them; «The section «What the last session added» below is …» names #PR, and the batch before it, 3b-8's; it still ends: «The SHA of its own merge is for the next close-out to write.»
  4. **The milestone table**: milestone 11's row gains #PR as open, and #395 among its issues, and 3b-8's pull request as merged.
  5. **Section 5**, the bullet on v2's unverified paths: its head gains #PR and «3c»; add the sub-item «**the host target outside CI**: `python -m app.host` has run on a CI runner and on the development machine only, on SQLite, behind no proxy; its image has never been built; no stream has been held longer than a test's seconds, and no phone has spoken native gRPC to it;».
  6. **Section 7**: replace the paragraph that begins «**Next for the programme: stage 3c of sub-project 3**» (or, if 3b-8's close-out named it otherwise, the paragraph naming 3c as next), up to and including its last sentence, with:
```markdown
**Next for the programme: sub-project 3's live tests, then sub-project 4.** Stages 3a (#342),
3b-1 to 3b-8 and 3c (#PR) are merged, and the server-v2 design is delivered: v2 serves every
unary method beside v1 on Vercel, and the host target serves native gRPC and `WatchClass`.
By the owner's order of 8 October, everything recorded as unverified in sub-project 3 is
checked on the development machine before sub-project 4 starts; where the host runs is
decided when a phone needs it (the design's question 3).
```
     Read section 7 for anything the owner did since the last close-out (the `[AFTER-3b8]` facts say), and move what they did to «## Moved out of section 7 on [date]» in `docs/history.md`, as the skill says.
  7. The cheat-sheet's counts under «How to continue» were written by Step 6.

  Write `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\commit-3c-handover.txt`, with the placeholders replaced by their numbers:
```text
Hand over stage 3c: the host target, its stream and its CI job

HANDOVER.md's close-out is written while the pull request is open, so
the file is true when it merges. It describes 3c: the class-changed bus
and the bulk writes that touch it, WatchClass served by call.stream with
the gate asked again before every message, python -m app.host under
pyvoy and hypercorn, the defect filed and fixed (#395), the marker and the
switch, the host's lock and image, the «Host» job and its first result,
what is left alone and unverified, and what followed 3b-8's merge. The
batch before becomes the session before, its merge recorded, and the one
before that moves to docs/history.md. Section 5 adds the host outside
CI; section 7 names the live tests and sub-project 4 as next.

Not covered: production after this pull request's merge; the next
close-out records it.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/continue-previous-session-991230 && git add HANDOVER.md docs/history.md && git commit -F C:/Users/lumen/.claude/jobs/c9e2d980/tmp/commit-3c-handover.txt
```
and push.

- [ ] **Step 9: The merge** is the controller's, under the `github-pr` skill's five checks: CI green on the exact head, the «Contract» job and both «Host» jobs included; `mergeable_state` clean; the gates run locally before the push; a milestone attached; no review waiting. No revision has to go on first: 3c has none.

- [ ] **Step 10: After the merge, read production.** Vercel is not the host, and nothing it answers should have moved but the stream's path through `call.stream`:
```bash
curl -s https://lessons-ruddy-zeta.vercel.app/api/v1/warmup; echo
printf '\x00\x00\x00\x00\x02{}' > C:/Users/lumen/.claude/jobs/c9e2d980/tmp/watch-envelope.bin
curl -s -i -X POST -H "Content-Type: application/connect+json" --data-binary @C:/Users/lumen/.claude/jobs/c9e2d980/tmp/watch-envelope.bin https://lessons-ruddy-zeta.vercel.app/api/rpc/lessons.v2.WatchService/WatchClass
curl -s -i -X POST -H "Content-Type: application/grpc" --data-binary @C:/Users/lumen/.claude/jobs/c9e2d980/tmp/watch-envelope.bin https://lessons-ruddy-zeta.vercel.app/api/rpc/lessons.v2.WatchService/WatchClass
curl -s -i -X POST -H "Content-Type: application/grpc" --data-binary @C:/Users/lumen/.claude/jobs/c9e2d980/tmp/watch-envelope.bin https://lessons-ruddy-zeta.vercel.app/lessons.v2.WatchService/WatchClass
```
Expected:
- `/api/v1/warmup` reports `status` `ok`, `schema` `0019` and `v2` `true`.
- The Connect stream answers `200` with one end-of-stream message whose error is `unauthenticated`, reason `DEVICE_TOKEN_INVALID`: the gate runs before the stream, on Vercel as anywhere.
- Native gRPC under `/api/rpc` answers `415` with the sentence that names the host target: Vercel speaks HTTP/1.1.
- Native gRPC at the root answers `404`: the root is the host's alone.

Write what was seen into the controller's notes for the next close-out.


---

## Self-review

**Against the design.**
- **Decision 1, 3c's row.** `host.py` (Task 5), the `Dockerfile` (Task 6), native gRPC (Tasks 5 and 7), `WatchClass` and its bus (Tasks 1, 2 and 4), and a CI job that starts the host (Task 7).
- **Decision 13, whole.**
  - *What runs*: `python -m app.host` under pyvoy, with HTTP/2, trailers and gzip, serving v1, v2 over REST and Connect, native gRPC, the webhook and the tick (Task 5; asked by Task 7's `test_the_rest_of_the_app_answers_over_http_1_1` and its gRPC tests); hypercorn the fallback (Rulings 153; CI's matrix).
  - *Who sets the marker*: the `Dockerfile` (Task 6), never `app.host` (`test_the_image_says_it_is_the_host_and_the_code_never_does`); CI and a local run start without it, against SQLite (Task 7; `test_the_job_starts_the_host_as_a_local_run_does_with_streaming_on`).
  - *Dependencies and packaging*: `server/requirements-host.in` compiled with `-c` Vercel's lock into `requirements-host.txt`, both installed by the `Dockerfile` (Task 6; Ruling 156 says why `--universal`).
  - *`WatchClass`*, with `LESSONS_STREAMING=true`: a revision, never the data, carrying a boot identifier (Tasks 1 and 4; Rulings 145 to 147).
  - *The stream holds no session*: the gate in a short scope at the start and again on every event and heartbeat; a revoked device or a deleted class ends it with `UNAUTHENTICATED` (Task 4; Ruling 144).
  - *The bus*: in process, fed from the session, hearing v1, v2 and the bot; the allowlist (with `bot_users`, Ruling 149); ORM writes collected before each flush by their class, a bell period through its schedule, the class through its id; bulk statements touching it, held by a walk (wider than `services/`, Ruling 150); published after the commit and cleared on a rollback (outermost only, Ruling 148); attached only when streaming is on (Task 3).
  - *CI*: a Linux runner starts the host against SQLite; a native gRPC client makes a unary call, an `UNIMPLEMENTED` call, and a `WatchClass` that sees a change made through an ORM write, a bulk write and a bell change (Task 7). The build console has not merged, so no row (Ruling 163).
  - *What the programme did not see*: the host is complete, chosen instead of Vercel, single-instance (Ruling 157's image, `docs/deploy.md`'s «Option 3»).
- **Decision 14.** The host's native gRPC and streaming tests run only in the CI job, behind a marker the ordinary run skips (Ruling 159); the in-process tests of the stream drive it through the app as a server would (Task 4).
- **Decision 7.** The settings refusal applies on both targets, read from `VERCEL` or `LESSONS_TARGET` (Task 3); `WatchClass` answers `UNIMPLEMENTED` / `FEATURE_UNSUPPORTED` on Vercel whatever is attached (`test_where_streaming_is_off_the_gate_runs_and_the_feature_is_refused[on-vercel]`); the API docs stay off on both (`test_a_deployment_serves_no_docs_and_still_answers[host]`); no flag can claim gRPC or a stream on Vercel.
- **Questions 2 and 3.** Complete and single-instance; deployed nowhere — the image is delivered, never built here.

**Placeholders.** Every code step is the code, rendered from the copy's own commits and applied in order to rebuild them («What was verified»). The bracketed words left are facts that exist only later: `#395` and `#PR`, `3142`, the SHAs, dates, times and counts of the real run, the «Host» job's first result, and `[AFTER-3b8]`.

**Types across tasks.** `watch.touch(session, class_id)` (Task 1) is called so by the five services and nowhere else (Task 2). `watch.watching`, `revision`, `changed_at`, `listening`, `publish`, `watchers` and `BOOT` (Task 1) are read by `rpc/watch.py` and the tests (Task 4). `Settings.streaming_enabled`, `deployed` and `behind_host` (Task 3) are read by `app.main` (Task 3) and `rpc/watch.py` reads `behind_vercel` (Task 4). `call.Stream` and `call.stream(method, request, *, headers, peer)` (Task 4) are read by `rpc/__init__._server_stream` and `rpc/watch.watch_class`; `handlers.STREAMS` by `call.stream` and `test_v2_reads.py`. `app.state.v2_services` and `rpc.is_native_grpc` (Task 5) are read by `host.application`. `host.envoy_config(dict) -> dict` (Task 5) is read by `_serve_pyvoy` and `test_host.py`. The `host` marker (Task 7) is read by `conftest.py` and `test_host_job.py`; `LESSONS_HOST_URL` and `LESSONS_HOST_DATABASE_URL` by `test_host_live.py`, and set by the job.

**Review Focus.** Each of its five lines names tests that exist in the tasks it names, letter for letter.

**The head test's three shapes** appear nowhere in this plan: no «head is» or «expects» before a backticked revision, and no line with `/warmup`'s quoted JSON.
