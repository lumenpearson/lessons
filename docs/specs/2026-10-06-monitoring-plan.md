# Monitoring: a self-check in every tick, the external clock, Sentry and «📊 Проект» Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** written on 6 October 2026 against stage 3b-2's tree: `c83d532`, the head of #356, with its HANDOVER close-out `d82cd1d` on top, which changes only `HANDOVER.md` and `docs/history.md`. #356 had not merged yet. The branch is cut from `main` once it has, and every anchor below is re-checked there by the first task that touches it: each replacement names its text exactly, and a text that moved fails loudly rather than quietly.

**Goal:** Make the deployment say when it is broken: the cron tick checks four things and writes to the owner in Telegram on a change, Sentry receives each error with its place and a 5 % sample of timings and nothing a person wrote, the external clock's setup is documented, and the owner has a «📊 Проект» screen in the bot.

**Architecture:**
- `server/app/telegram_send.py` is the neutral sender: `build_bot()`, `close_bot()` and `send()`, with aiogram imported inside the functions. `app.bot.bot` imports `build_bot` back from it, and the tick and v1's two notice seams build their bot through it.
- `server/app/services/health.py` holds the four checks, the alert rule as one pure function (`decide`), and `run()`, which the tick calls last, in its own guard and time budget. A new table, `health_checks` (revision `0019`), keeps what each check said last.
- `server/app/observability.py` starts Sentry where `SENTRY_DSN` is set, and is imported nowhere else. Its hooks rebuild every event from a list of what may leave.
- `server/app/services/project_stats.py` reads the screen's numbers and writes nothing. `server/app/bot/handlers/project.py` answers `/project` and `/health` for `OWNER_IDS` accounts, and the catch-all answers them for everybody else.

**Tech Stack:** Python 3.12 (CI's), FastAPI/Starlette, aiogram 3.31, SQLAlchemy 2.1 async, Alembic, httpx 0.28 with `MockTransport` in the tests, `sentry-sdk` 2.71.0 (new), pytest.

**Spec:** `docs/specs/2026-10-05-monitoring-design.md`, approved by the owner on 5 October 2026 with every recommended answer: reminders every six hours; `/project` for `OWNER_IDS` only; Sentry's timings at 5 %. Task 1 commits it beside this plan. Read it before Task 2. Where this plan and the design differ, a ruling below says why.

## Rulings made while writing this plan

Each is a place where the design leaves a choice to the code, or where the code made the design ambiguous. Each was checked against the tree at `c83d532`, and the probes are listed under «What was verified».

1. **For anybody but the deployment's owner, `/project` and `/health` answer as any unknown command does.** The design says they get «the bot's ordinary "unknown command" silence». Since `handlers/unknown.py`, the ordinary answer to an unknown command is not silence but «🤔 Не знаю такой команды…». The design's point is that nothing reveals the screen exists, so the handlers carry a filter (`deployment_owner`) that hands the update on, and the catch-all answers it.
   - A filter rather than `@needs`: a failed filter hands the update on instead of refusing it, and here that is what is wanted.
   - Neither command is in `COMMANDS`. That list is the one menu every account sees, and `test_a_command_in_the_menu_is_never_called_unknown` refuses a menu command that reaches the catch-all.
2. **The «📊 Проект» button on «⚙️ Класс» is drawn for an `OWNER_IDS` account only** (`roles.is_env_owner`), not for a class's owner. «⚙️ Класс» needs a class; an owner in no class uses `/project`.
3. **`health_checks` has two columns more than the design's six.**
   - `previous_checked_at`: the «Часы» block shows the gap between the last two ticks, and the six columns hold one tick's time.
   - `round_trip_ms`: the «Прокси дневника» block shows the check's round trip in milliseconds, and none of the six holds a number.

   Both are nullable, in a table this revision creates, so they cost the migration nothing.
4. **The alert rule, made exact** (`health.decide`, one pure function):
   - `since` is when the check last moved between `ok` and `failing`. `unknown` neither starts nor ends a period, so a failure that went `unknown` and then came back is reported whole.
   - `last_alert_at` is what the owner believes. It is set when they are told a check is failing, and cleared when they are told it is back. An «up» follows only a failure they were told of.
5. **An alert is claimed by a compare-and-set on `last_alert_at`** (`claim_alert`), as the digests are claimed (`reminders.claim`), so two overlapping ticks tell the owner once.
   - A «down» or a reminder that nobody received has its claim given back, so the next tick tries again.
   - An «up» that was not delivered is not retried: by the next tick the downtime it states would be wrong.
   - The alert counts as delivered when at least one `OWNER_IDS` account got it.
   - The rows themselves are written by the ORM. Two ticks overlapping on the very first run can both insert the same row: the second's commit fails inside the tick's guard, and the next tick writes it.
6. **A check that does not apply is `unknown`, with its reason.** That covers no proxy configured, an unusable proxy, off Vercel, not production, and Vercel's system variables not exposed. Every tick writes all four rows, and none of these ever alerts.
7. **The `deploy` check's ETag lives in the process, beside the answer it validates.**
   - A warm instance asks with it, and an unchanged `main` answers `304`. A cold instance asks without it and pays one request of the sixty an hour GitHub allows an address.
   - GitHub is called with httpx, unauthenticated, at `GET /repos/{owner}/{slug}/commits/main`, with a 5-second budget.
   - The repository is Vercel's `VERCEL_GIT_REPO_OWNER` and `VERCEL_GIT_REPO_SLUG`, so a fork asks about its own `main`.
   - When `main` moved is its head commit's committer date, which for a merge made on GitHub is the moment of the merge.
   - `403`, `429`, a body that is not a commit, a transport error and a timeout are all `unknown`, never `failing`.
8. **Vercel's system variables are read by `config.deployment()` from the process environment, not as `Settings` fields.** They are `VERCEL_ENV`, `VERCEL_GIT_COMMIT_SHA`, `VERCEL_GIT_REPO_OWNER`, `VERCEL_GIT_REPO_SLUG` and `VERCEL_REGION`. They are not configuration: as fields, `test_env_example.py` and `test_compose.py` would demand them in `.env.example` and `docker-compose.yml`, where they must never be set by hand, for the reason `VERCEL` is not there.
9. **The time budget.** Everything the self-check does with the network stops 28 seconds after the request began (`HEALTH_HARD_STOP_SECONDS`). Within that, each network check gets at most 5 seconds and the sends at most 10. The keep-alive hard-stops at 24 seconds, and `vercel.json` gives the function 30. A check that would start past the deadline is `unknown`: «the tick ran out of time before this check».
10. **A reason is built from facts, never from an exception's text.** That means a revision, an HTTP status, a commit, or an exception's type name: httpx's message for a refused tunnel can carry the proxy's address, and with it the proxy's password. An alert carries its reason on a second line, escaped, in `<code>`.
11. **The tick's body does not change.** The self-check's answer is in `health_checks` and in the owner's chat. So `TickOut` keeps its fields, `test_api_extended.py`'s exact-dict tick test is not edited, and cron-job.org's history reads as before.
12. **`telegram_send.send` takes a batch.** It takes `(telegram_id, text)` pairs and returns, per pair, whether Telegram took it. It never raises, not even on a token aiogram refuses at construction.
    - The three routers' `_build_bot` seams (`api/cron.py`, `api/edit.py`, `api/manage/requests.py`) stay, because the tests patch them; they now build through `telegram_send`.
    - v1's `_tell`s stay where they are for 3b-3, which reuses this module rather than creating it.
13. **`wording.duration` moves out of `bot/week_render.py`**, which imports it back: the alerts say how long a failure lasted in the same words, and `services/` may not import the bot.
14. **Sentry's configuration.**
    - Default and auto-enabling integrations are both off, and only Starlette's and FastAPI's are on. The defaults add log lines, argv and modules. The auto-enabling set patches httpx and aiohttp, and would put `sentry-trace` and `baggage` headers on the diary's and Telegram's requests.
    - `trace_propagation_targets=[]`, `max_request_body_size="never"`, `send_default_pii`, `include_local_variables` and `include_source_context` all off, and `max_breadcrumbs=0`.
    - The hooks rebuild every event from a list of what may stay, and drop a transaction's spans. A frame keeps its file, function, module, line and `in_app`, and loses `abs_path`.
    - A transaction no route matched is named «unmatched route»; a Connect method's path is kept, because its shape holds no value. The probe found that the SDK names a `404` and a request to the Connect mount by the raw URL, which for a calendar feed carries its secret.
15. **Sentry is started at `app.main`'s import, where `SENTRY_DSN` is set.** Not in the lifespan, which a serverless platform may never run.
    - `release` is `VERCEL_GIT_COMMIT_SHA`. `environment` is `VERCEL_ENV`, or `self-hosted` off Vercel.
    - The bot's errors are captured in `bot._on_error`, because the webhook answers 200 whatever happened and no integration sees them.
    - v2's own `INTERNAL` answers are not captured: `invoke` turns the exception into a `ConnectError` before any integration sees it (left alone, below).
16. **`sentry-sdk>=2.71.0`, without the `fastapi` extra.** The FastAPI integration is in the package, and the extra only asks for FastAPI. 2.71.0 is PyPI's current release, of 28 September 2026. The lock gains `sentry-sdk==2.71.0` and `urllib3==2.8.0`, and `certifi`'s line names `sentry-sdk` among its users.
17. **«Сводок сегодня» counts claims, on the deployment's day.** `last_morning_sent` and `last_evening_sent` are marked before the send. The counts take `Settings.timezone`'s date, because the screen sums classes in every zone.
18. **«v2: включён» is the `v2` check's row.** The bot's handler holds no reference to the ASGI app, and the tick's answer is at most five minutes old.
19. **The dashboards are listed in `docs/deploy.md`.**
    - Vercel's and Sentry's by address: the team `codeilluminators` and the organisation `hubdpi` are already public in `HANDOVER.md`.
    - Neon's by the way there, because its project id stays out of the repository (`CLAUDE.md`).
    - The bot names the three and links none, as the design says.
20. **`test_client_version_revision.py` pins the head to `0018`**, so `0019` fails it. That is a defect, filed in Task 3 Step 1 as `#HEADPIN` before its fix. The fix asserts `0018`'s own place in the chain, and leaves the head to `test_schema_version.py`, which pins it once.
21. **The head moves by a scratch script built from pieces**, as 3b-1's `head0018.py` did, because this plan sits under `docs/specs/` and the head test reads it. This plan never writes the words the head test matches.
22. **Issues.** The pull request's body says:
    - `Closes #349`: its own text names the `deploy` check as the cure «for good», and the controller's notes of 6 October kept it open for this pull request;
    - `Closes #HEADPIN`;
    - and refers to #120, #127 and #269.

    #120 is not closed. Its cron half was done by the owner on 5 October and its failure email is still to be switched on, and its `DADATA_TOKEN` half is not this pull request's. #269 is the tick's `GET` that writes, which now also writes `health_checks`.
23. **The 3b plan's summary of 3b-3 is corrected in the same batch** (Task 8). `telegram_send.py` exists from this pull request on, and 3b-3 reuses it.

## Global Constraints

- **Import rules.** `services/` imports nothing from `app.bot`, `app.api`, `app.rpc` or `app.rest`. `app.telegram_send` reaches none of them either, and Task 2 adds the test. `app.observability` is imported only behind `Settings.sentry_configured`. Nothing on the API's import path loads aiogram or `sentry_sdk` (`tests/test_cold_start.py`).
- **The deployment refusal list does not grow.** `SENTRY_DSN` joins `Settings.disabled_features` and never `deployment_problems` (`CLAUDE.md`, «Do not add an optional setting to that list»).
- **Product text is Russian; everything about it is English.** A message names the infrastructure only, never a class, a family or a child. Everything that goes into a message from outside is escaped, and cut before it is escaped. A page that grows with the data carries a budget and says «… и ещё N» (`bot-message` skill).
- **Times are naive UTC** in every column, as `datetime.now(UTC).replace(tzinfo=None)`. A screen shows them in `Settings.timezone`.
- **The lock.** `requirements.txt` is written only by the command in its own header, run from the repository root with uv at `/c/Users/lumen/.local/bin/uv.exe`, and never edited by hand. `requirements.in` and `server/pyproject.toml` name the same requirement, character for character.
- **Versions are copied, never guessed.** `sentry-sdk` 2.71.0 is PyPI's `info.version` read on 6 October 2026.
- **No task touches a real database.** The controller applies `0019` through the Neon connector, to the branch `preview` when the pull request is pushed and to production before the merge (Task 9, and the `migration` skill).
- **The head test reads this plan** (`docs/specs/` is under `docs/`). No text here may contain «head is», optionally followed by asterisks, and then a backticked four-digit revision. Nor «expects» directly followed by a backticked revision, nor `/warmup`'s quoted `"status"` with `"ok"` and its `"schema"` on one line. Task 3 Step 6 scans for all three.
- **Commit messages.** An English sentence saying what the change makes the project do, with no prefix. The body gives the reasoning and what is left uncovered. There are **no** `Co-Authored-By` or `Claude-Session` lines. Write the message to `$SCRATCH/commit-mon-t<N>.txt` with the Write tool, then `git commit -F` that file.
- **This machine's RAM is faulty.**
  - Run one heavy job at a time, and never the suite while Gradle or another suite runs.
  - Run the full suite once per task, at its gate.
  - An xdist worker that crashes with no assertion, on a test that passes alone, is the machine. Note it against #355 or the machine, and do not chase it.
- **Commands.** Shell state does not persist, so every command spells out its paths:
  - `$WT` is `/c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract`;
  - `$SCRATCH` is `C:/Users/lumen/AppData/Local/Temp/monitoring-run`, outside the repository, made once with `mkdir -p`;
  - `pytest` is `$WT/server/.venv/Scripts/pytest.exe`, run from `$WT/server` in the bare form CI runs, with `-p no:xdist` for a focused run and `-q -n auto` for the suite;
  - ruff and mypy are `$WT/server/.venv/Scripts/python.exe -m ruff check app tests scripts migrations` and `… -m mypy`, from `$WT/server`.
- **Gates at the end of every task**, from `$WT/server`, in this order:
  1. the task's own test files, with `-p no:xdist`;
  2. ruff, which prints `All checks passed!`;
  3. mypy, which prints `Success: no issues found in N source files`. N is given per task, over a base of 221. If the branch's first run prints another base, shift every N by the difference;
  4. `pytest -q -n auto` once.

  The suite is 2621 at `c83d532`, and each task gives its running total. If `main` moved before the branch was cut, shift every total by the difference; Task 2's first full run is the truth.
- **Milestone 12**, `v1.0.0 — A build somebody else can install`, under epic #127. A defect found during the work gets an issue before its fix (`type:bug`, its `area:`, a `status:`, milestone 12, and an item on project 6, as the `github-pr` skill says).
- **Another agent may work in this tree.** Run `git status` before touching a file you did not open.

## Review Focus

The five inputs most likely to bite the owner that the design implies and its own test list does not name. Each has the test that pins it and the task that owns it.

1. **Two owners, one of whom has blocked the bot.** The other was told, so the alert stands, and the next tick says nothing more: `test_an_alert_one_of_two_owners_got_is_told_and_not_repeated` (Task 4).
2. **Two ticks at once**, as when cron-job.org and the fallback workflow overlap, or a caller's timeout kills a tick whose function runs on. The owner is told once: `test_two_ticks_running_together_tell_the_owner_once` (Task 4).
3. **GitHub refusing every request**, because sixty an hour is shared by every Vercel function on the address. That is `unknown`, never `failing`, never an alert, and the screen says why: `test_a_github_that_refuses_or_does_not_answer_is_unknown_never_failing` with its three cases (Task 4).
4. **A tick that has already spent its time** on a heavy morning of digests and a slow keep-alive. The self-check asks nobody, writes why, and the function ends inside its ceiling: `test_a_tick_out_of_time_asks_nobody_and_says_so` (Task 4).
5. **A request whose path carries a secret and matches no route**, such as a calendar feed's token. Sentry never gets the path as a name: `test_a_path_no_route_matched_is_never_the_name` (Task 6). The same probe through the real app found REST v2 named by its template and a Connect method by its path.

## What was verified while writing this plan, and what was not

**Read on 6 October 2026:**
- **PyPI's `sentry-sdk`** is 2.71.0, uploaded on 28 September 2026. It requires `urllib3>=1.26.11` and `certifi`.
- **Its `init` options**, read from `ClientConstructor` in the installed 2.71.0: `default_integrations`, `auto_enabling_integrations`, `include_local_variables`, `include_source_context`, `max_breadcrumbs`, `max_request_body_size`, `trace_propagation_targets`, `before_send` and `before_send_transaction`. `setup_integrations` adds the default and auto-enabling sets only when `default_integrations` is true.
- **Sentry, through its connector:** the organisation `hubdpi` answers at `https://hubdpi.sentry.io`, its region is `https://de.sentry.io` (EU), and its one project is `lessons`. No DSN was read, and none is written anywhere.
- **#120 and #349, through `gh`:** #120 has two halves, the external cron and `DADATA_TOKEN`. #349 names the `deploy` check as its cure «for good».
- **`CreateTable(HealthCheck.__table__).compile(dialect=postgresql.dialect())`** prints the eight columns and `PRIMARY KEY (name)`, as `0019`'s docstring quotes them.

**Probed in a scratch copy, with sentry-sdk 2.71.0 and a capturing transport:**
- **The SDK's own event**, before any hook:
  - a matched FastAPI route is named by its template, with the source `route`;
  - a request to a Starlette `Mount`, which is how Connect is mounted, and a `404` are named by the raw URL (`http://test:None/api/rpc/…`), with the source `url`;
  - `request` carries the query string and the URL, while `authorization` and `cookie` read `[Filtered]` and every other header is kept;
  - each frame carries `abs_path`, which names the machine's directories;
  - `contexts.trace.dynamic_sampling_context` repeats the transaction's name.
- **Through the real `app.main`, with the hooks:** `GET /api/v2/class/subjects/5` was named `/api/v2/class/subjects/{subject_id}`; Connect `SubjectService/ListSubjects` was named by its path; `GET /api/v1/calendar/<a token>.ics` was named `/api/v1/calendar/{calendar_token}.ics`; and a `404` was named «unmatched route».

**The pre-flight**, in `C:\Users\lumen\AppData\Local\Temp\monitoring-preflight\`, never the worktree:
- **The copy** was `git archive` of `c83d532`, with `HANDOVER.md` and `docs/history.md` taken from `d82cd1d`, the only two files that commit changes. Its venv was made as CI makes one: `python -m venv .venv` on Python 3.12.13, then `pip install -r ../requirements.txt -e ".[dev]"`, because `conftest.py` refuses a borrowed one (#312). Before any change it collected 2621 tests, and mypy found 221 source files.
- **How the steps got in.**
  - The code was first written and run in the copy. This plan was then built from it, with every new file's block filled from the file that ran.
  - The copy was then reset to the archive, and every step applied by a script (`apply.py`) that reads this plan's own fenced blocks: 101 «Create», «In …, replace» and «Append to» steps, each anchor found exactly once.
  - The scratch scripts were cut out of this plan the same way and run. `head0019.py` printed `moved`. `scan_heads.py` printed eight lines, all `0019`, none under `docs/specs/`, with this plan and the design copied there. `docsmon.py` printed seventeen `missing` lines on the base and none after Task 8. `countsmon.py 2621 2687 221 228` printed `written`. uv's own command regenerated the lock with exactly the three changes Task 6 names.
- **The whole plan applied:** ruff printed `All checks passed!`; mypy printed `Success: no issues found in 228 source files`; `pytest --collect-only` counted 2687; and **the full suite ran once**, `pytest -q -n auto` from the copy's `server/` (with pytest's cache plugin off, so that the copy kept no cache): **2687 passed** in 584.12 s (9 min 44 s), with no failure and no worker crash.
- **Task by task.** Each prefix of the tasks was applied alone to a fresh copy: Task 1, then Tasks 1–2, and so on to Tasks 1–8, with the head script from Task 3 on, the lock from Task 6 on, and the counts at Task 8. Each gave ruff clean, and these mypy and collection counts:

  | After task | mypy | collected | The task's own «Green» command |
  | --- | --- | --- | --- |
  | 1 | 221 | 2621 | 17 passed |
  | 2 | 222 | 2626 | 432 passed |
  | 3 | 222 | 2629 | 52 passed |
  | 4 | 223 | 2662 | 136 passed |
  | 5 | 223 | 2666 | 144 passed |
  | 6 | 224 | 2676 | 172 passed |
  | 7 | 228 | 2687 | 344 passed |
  | 8 | 228 | 2687 | 66 passed |
- **The red steps of Tasks 2 to 7** were run the same way, on the tasks before them and the steps up to their «Run». They fail as each says.
  - The first draft of this plan put a file that fails at collection in one run with files that should pass or fail on their own. pytest stops the whole run at a collection error, so those expectations could not be seen. Tasks 2, 3 and 6 now run them apart.
  - Task 5's red step gave 3 failed, 1 passed. Task 6's second run gave 2 failed, 34 passed.

**Not run:**
- anything on Postgres: the two catalogue queries, the claim's compare-and-set on `last_alert_at`, and `0019` itself;
- anything on Vercel: whether the system variables reach the function (the project's «Automatically expose System Environment Variables»), and `VERCEL_REGION` at runtime;
- GitHub's API, live: whether an unauthenticated `304` is exempt from the rate limit, as the design says. If it is not, a warm instance spends twelve of the sixty an hour;
- a real message to the owner, and Sentry ingesting an event rebuilt by the hooks;
- cron-job.org's settings and history, which are the owner's.

## File map

| File | Task | What it holds |
| --- | --- | --- |
| `docs/specs/2026-10-05-monitoring-design.md`, `docs/specs/2026-10-06-monitoring-plan.md` | 1 | the design and this plan |
| `server/app/telegram_send.py` | 2 | `build_bot`, `close_bot`, `send` |
| `server/app/bot/bot.py` | 2, 6 | `build_bot` imported back; `_on_error` captures for Sentry |
| `server/app/api/cron.py`, `server/app/api/edit.py`, `server/app/api/manage/requests.py` | 2, 5 | the seams build through `telegram_send`; the tick ends with `health.run` |
| `server/app/models.py`, `server/app/db.py`, `server/migrations/versions/0019_health_checks.py` | 3 | `HealthCheck`, `EXPECTED_REVISION`, the revision |
| `CLAUDE.md`, `AGENTS.md`, `docs/deploy.md`, `docs/api.md`, `.claude/agents/server-migrations.md`, `.claude/skills/migration/SKILL.md`, `.github/PULL_REQUEST_TEMPLATE.md` | 3 | the head moved, and `0019` described |
| `server/app/config.py` | 4, 6 | `STARTED_AT`, `Deployment`, `deployment()`; `sentry_dsn`, `sentry_configured` |
| `server/app/wording.py`, `server/app/bot/week_render.py` | 4 | `duration`, moved |
| `server/app/services/health.py` | 4 | the checks, `decide`, `message`, `claim_alert`, `run` |
| `server/app/observability.py`, `server/app/main.py` | 6 | Sentry's start and its hooks |
| `server/pyproject.toml`, `requirements.in`, `requirements.txt` | 6 | `sentry-sdk` |
| `server/.env.example`, `docker-compose.yml`, `server/tests/conftest.py` | 6 | `SENTRY_DSN` |
| `server/app/services/project_stats.py` | 7 | the screen's numbers |
| `server/app/bot/project_keyboard.py`, `server/app/bot/project_render.py`, `server/app/bot/handlers/project.py` | 7 | the screen |
| `server/app/bot/handlers/__init__.py`, `server/app/bot/manage_keyboards/class_card.py`, `server/app/bot/handlers/manage/class_card.py` | 7 | the router's place; the button |
| `server/tests/test_telegram_send.py`, `test_service_layering.py` | 2 | the sender |
| `server/tests/test_health_checks_revision.py`, `test_client_version_revision.py` | 3 | `0019`; #HEADPIN |
| `server/tests/test_health.py` | 4 | the checks and the rule |
| `server/tests/test_health_tick.py` | 5 | the tick |
| `server/tests/test_observability.py`, `test_cold_start.py`, `test_deployment_config.py` | 6 | Sentry |
| `server/tests/test_project_screen.py`, `test_bot_commands.py` | 7 | the screen |
| `docs/deploy.md`, `docs/architecture.md`, `docs/bot.md`, `docs/README.md`, `CLAUDE.md`, `README.md`, `.claude/agents/*`, `docs/specs/2026-10-05-server-v2-3b-plan.md`, the counts | 8 | the documents |
| `HANDOVER.md`, `docs/history.md` | 9 | the close-out |

Counts: 5 + 3 + 33 + 4 + 10 + 11 = **66** tests, so **2621 becomes 2687**. mypy grows from 221 to **228**: `telegram_send` (Task 2), `services/health` (Task 4), `observability` (Task 6), and `services/project_stats`, `bot/project_keyboard`, `bot/project_render` and `bot/handlers/project` (Task 7).

---

## Task 1: The design and this plan, committed first on the branch

As the 3b plan's commit was the first on its branch: the plan travels with the work it describes.

**Files:**
- Create: `docs/specs/2026-10-05-monitoring-design.md`, copied from `$WT/.superpowers/sdd/pending-monitoring-design/2026-10-05-monitoring-design.md`
- Create: `docs/specs/2026-10-06-monitoring-plan.md`, copied from `$WT/.superpowers/sdd/2026-10-06-monitoring-plan/plan-draft.md`

**Interfaces:**
- Consumes: `main` with #356 merged.
- Produces: the branch `monitoring`, whose first commit carries the design and this plan.

- [ ] **Step 1: Cut the branch.** Check that #356 has merged, then cut the branch from `main`:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && gh pr view 356 --json state,mergeCommit --jq '"\(.state) \(.mergeCommit.oid)"'
```
Expected: `MERGED` and a SHA. Then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git status --short && git fetch origin && git checkout -b monitoring origin/main && git log --oneline -3
```
Expected: the first line of the log is the merge of #356. `git status` lists nothing but the untracked `.superpowers/`, or the checkout refuses; another agent's uncommitted work is never reverted. Make `$SCRATCH` once: `mkdir -p /c/Users/lumen/AppData/Local/Temp/monitoring-run`.

- [ ] **Step 2: Copy the two documents into `docs/specs/`.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && cp .superpowers/sdd/pending-monitoring-design/2026-10-05-monitoring-design.md docs/specs/2026-10-05-monitoring-design.md && cp .superpowers/sdd/2026-10-06-monitoring-plan/plan-draft.md docs/specs/2026-10-06-monitoring-plan.md
```

- [ ] **Step 3: Point the design at its plan.**

In `docs/specs/2026-10-05-monitoring-design.md`, replace:
```markdown
- Not yet built. It is planned as its own pull request after stage 3b-1 merges, and its plan is
  written against the tree as it stands then.
```
with:
```markdown
- Not yet built. It is its own pull request, after stage 3b-2 (#356), and its plan is
  `docs/specs/2026-10-06-monitoring-plan.md`, written on 6 October 2026 against that tree.
```

- [ ] **Step 4: The head test reads both documents.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_schema_version.py tests/test_ci_paths.py
```
Expected: all pass. Neither document names a head in a shape the test reads; Task 3 Step 6 scans for them again.

- [ ] **Step 5: Commit.** Write `$SCRATCH/commit-mon-t1.txt`:
```text
Record the monitoring design and the plan that builds it

The design was approved by the owner on 5 October 2026 with every
recommended answer: reminders every six hours, the project screen for
OWNER_IDS only, and Sentry's timings at 5 %. The plan beside it was
written against stage 3b-2's tree and checked by applying every code step
to a scratch copy and running the gates there. Its rulings say where the
code made the design ambiguous and what was decided.

Not covered: nothing is built yet; the tasks that follow build it.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add docs/specs/2026-10-05-monitoring-design.md docs/specs/2026-10-06-monitoring-plan.md && git commit -F C:/Users/lumen/AppData/Local/Temp/monitoring-run/commit-mon-t1.txt
```

---

## Task 2: A neutral sender, so that nothing outside the bot imports the bot to send

Rulings 12 and 23. The tick, v1's two notice seams and `app.bot.bot` build their bot through one module that loads aiogram only when a bot is built. v1's answers and the bot do not change.

**Files:**
- Create: `server/app/telegram_send.py`, `server/tests/test_telegram_send.py`
- Modify: `server/app/bot/bot.py`, `server/app/api/cron.py`, `server/app/api/edit.py`, `server/app/api/manage/requests.py`, `server/tests/test_service_layering.py`

**Interfaces:**
- Consumes: `config.get_settings().bot_token`.
- Produces:
  - `telegram_send.build_bot() -> aiogram.Bot`, importing aiogram inside the function;
  - `telegram_send.close_bot(bot: Any) -> None`;
  - `telegram_send.send(messages: Sequence[tuple[int, str]]) -> list[bool]`, which never raises;
  - `app.bot.bot.build_bot is telegram_send.build_bot`.

- [ ] **Step 1: Red.**

Create `server/tests/test_telegram_send.py`:
```python
"""The neutral sender: one bot built for one job, and closed after it.

``app.telegram_send`` is what the code outside the bot sends through - the
tick, v1's notices and the self-check's alerts - so that none of them needs
``app.bot`` (``tests/test_service_layering.py``) and none loads aiogram on the
API's cold start (``tests/test_cold_start.py``). What is held here is the
promise every caller leans on: it reports what it delivered, it never raises,
and it closes what it built.
"""

from __future__ import annotations

from typing import Any

from app import telegram_send
from app.config import get_settings


class _Bot:
    """A bot that refuses the ids it is told to, and says whether it was closed."""

    def __init__(self, refuses: set[int] | None = None) -> None:
        self.refuses = refuses or set()
        self.sent: list[tuple[int, str]] = []
        self.closed = False

    async def send_message(self, chat_id: int, text: str, **_: Any) -> None:
        if chat_id in self.refuses:
            raise RuntimeError("Forbidden: bot was blocked by the user")
        self.sent.append((chat_id, text))

    @property
    def session(self) -> _Bot:
        return self

    async def close(self) -> None:
        self.closed = True


def test_the_bot_package_builds_its_bot_here():
    """One factory, imported back rather than copied: a copy is a second
    definition free to drift from the first (a parse mode, a token)."""
    from app.bot import bot as bot_module

    assert bot_module.build_bot is telegram_send.build_bot


async def test_a_send_says_what_each_message_did_and_closes_its_bot(monkeypatch):
    bot = _Bot(refuses={2})
    monkeypatch.setattr(get_settings(), "bot_token", "123456:TEST")
    monkeypatch.setattr(telegram_send, "build_bot", lambda: bot)

    delivered = await telegram_send.send([(1, "раз"), (2, "два"), (3, "три")])

    assert delivered == [True, False, True]
    assert bot.sent == [(1, "раз"), (3, "три")]
    assert bot.closed


async def test_without_a_token_nothing_is_built_and_nothing_is_sent(monkeypatch):
    def refuse() -> None:
        raise AssertionError("a bot was built with no token")

    monkeypatch.setattr(get_settings(), "bot_token", "")
    monkeypatch.setattr(telegram_send, "build_bot", refuse)

    assert await telegram_send.send([(1, "раз"), (2, "два")]) == [False, False]
    assert await telegram_send.send([]) == []


async def test_a_token_aiogram_will_not_build_a_bot_from_is_a_refusal_not_a_raise(monkeypatch):
    """aiogram refuses a malformed token when the bot is constructed, which
    is the one place a send could raise before its own guard. The real
    factory, so that the refusal is aiogram's own."""
    monkeypatch.setattr(get_settings(), "bot_token", "not-a-token")

    assert await telegram_send.send([(1, "раз")]) == [False]
```

Append to `server/tests/test_service_layering.py`:
```python


def test_the_neutral_sender_reaches_neither_the_bot_nor_a_shell():
    """``app.telegram_send`` is what ``services/`` sends through (the
    self-check's alerts) and what the tick and v1's writes build their bot
    with. Were it to import ``app.bot``, every service that sends would reach
    the bot through it, and the walk above would name that chain only once a
    service did; were it to import a shell, a service would stand on it."""
    chains = _chains(("app.telegram_send",), (FORBIDDEN, "app.api", "app.rpc", "app.rest"))
    assert chains == [], "\n".join(chains)
```

Run:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_telegram_send.py
```
Expected: collection fails with `ImportError: cannot import name 'telegram_send' from 'app'`. The new layering test is not in this run: it passes before the module exists, because there is nothing to walk, and Step 5 runs it with the rest.

- [ ] **Step 2: The sender.**

Create `server/app/telegram_send.py`:
```python
"""Telegram for the code that is not the bot: a bot built for one job, and closed after it.

The bot's package, ``app.bot``, is aiogram's routers, keyboards and renderers,
and the code under it is the only code that may lean on it: ``services/``
never imports ``app.bot`` (``tests/test_service_layering.py``), and the API's
cold start never loads aiogram (``tests/test_cold_start.py``). Yet three things
outside the bot send messages - the reminder tick, the notices v1's writes
send, and the self-check's alerts to the owner (``services/health.py``) - and
each used to build its bot through ``app.bot.bot``. This module is the neutral
place for that. aiogram is imported inside the functions and never at the top,
so importing this module costs a cold start nothing, and ``app.bot.bot``
imports ``build_bot`` back from here.

Stage 3b-3 of the server-v2 design moves v1's notices here too (decision 2);
the tick and the alerts use it from the start.
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from typing import TYPE_CHECKING, Any

from app.config import get_settings

if TYPE_CHECKING:
    # A name for the annotation, and nothing more: see the module docstring.
    from aiogram import Bot

log = logging.getLogger(__name__)


def build_bot() -> Bot:
    """The bot every sender uses: this deployment's token, and HTML as its parse mode."""
    from aiogram import Bot
    from aiogram.client.default import DefaultBotProperties
    from aiogram.enums import ParseMode

    return Bot(
        token=get_settings().bot_token,
        default=DefaultBotProperties(parse_mode=ParseMode.HTML),
    )


async def close_bot(bot: Any) -> None:
    """Close the HTTP session a bot built for one job opened.

    ``getattr`` because the tests hand the callers fakes, and a fake without a
    session has nothing to close.
    """
    bot_session = getattr(bot, "session", None)
    if bot_session is not None:
        await bot_session.close()


async def send(messages: Sequence[tuple[int, str]]) -> list[bool]:
    """Send each ``(telegram_id, text)`` through one bot built for this call.

    Returns, per message, whether Telegram took it. Never raises: a refused
    message, or a token aiogram will not build a bot from, is logged and
    reported as ``False``, because every caller is in the middle of something
    a Telegram outage must not undo. With no ``BOT_TOKEN`` nothing is built
    and nothing is sent. The bot is closed before this returns, whatever
    happened.
    """
    if not messages:
        return []
    if not get_settings().bot_token:
        log.warning("%d message(s) not sent: BOT_TOKEN is empty", len(messages))
        return [False] * len(messages)
    try:
        bot = build_bot()
    except Exception:  # noqa: BLE001 - see the docstring
        log.warning("%d message(s) not sent: no bot could be built", len(messages), exc_info=True)
        return [False] * len(messages)
    delivered: list[bool] = []
    try:
        for telegram_id, text in messages:
            try:
                await bot.send_message(telegram_id, text)
            except Exception:  # noqa: BLE001 - see the docstring
                log.warning("could not send a message to %s", telegram_id, exc_info=True)
                delivered.append(False)
            else:
                delivered.append(True)
    finally:
        await close_bot(bot)
    return delivered
```

- [ ] **Step 3: The bot builds its bot through it.**

In `server/app/bot/bot.py`, replace:
```python
from aiogram import Bot, Dispatcher
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.exceptions import TelegramBadRequest
```
with:
```python
from aiogram import Dispatcher
from aiogram.exceptions import TelegramBadRequest
```

In `server/app/bot/bot.py`, replace:
```python
from app.config import get_settings
from app.db import SessionLocal
from app.fsm_storage import DatabaseStorage
```
with:
```python
from app.db import SessionLocal
from app.fsm_storage import DatabaseStorage
from app.telegram_send import build_bot  # neutral: the tick and the alerts need no app.bot
```

In `server/app/bot/bot.py`, replace:
```python
def build_bot() -> Bot:
    return Bot(
        token=get_settings().bot_token,
        default=DefaultBotProperties(parse_mode=ParseMode.HTML),
    )


async def run_polling(stop_event: asyncio.Event) -> None:
```
with:
```python
async def run_polling(stop_event: asyncio.Event) -> None:
```

- [ ] **Step 4: The three seams build through it.** The seams stay, because the tests replace them (`cron._build_bot`, `edit._build_bot`, `manage.requests._build_bot`).

In `server/app/api/cron.py`, replace:
```python
from app.api.routing import DishkaAnnotatedRoute
from app.config import get_settings
```
with:
```python
from app import telegram_send
from app.api.routing import DishkaAnnotatedRoute
from app.config import get_settings
```

In `server/app/api/cron.py`, replace:
```python
def _build_bot() -> Any:
    """Imported lazily: aiogram costs seconds to import, and the module is
    imported by ``app.main`` on every cold start of every endpoint."""
    from app.bot.bot import build_bot

    return build_bot()


async def _close_bot(bot: Any) -> None:
    bot_session = getattr(bot, "session", None)
    if bot_session is not None:
        await bot_session.close()
```
with:
```python
def _build_bot() -> Any:
    """The seam the tests replace. aiogram is imported inside
    ``telegram_send.build_bot``, never here: this module is imported by
    ``app.main`` on every cold start of every endpoint."""
    return telegram_send.build_bot()
```

In `server/app/api/cron.py`, replace:
```python
        await _close_bot(bot)
```
with:
```python
        await telegram_send.close_bot(bot)
```

In `server/app/api/edit.py`, replace:
```python
    from app.bot.bot import build_bot
```
with:
```python
    from app.telegram_send import build_bot
```

In `server/app/api/manage/requests.py`, replace:
```python
    from app.bot.bot import build_bot
```
with:
```python
    from app.telegram_send import build_bot
```

- [ ] **Step 5: Green, with every caller's tests.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_telegram_send.py tests/test_service_layering.py tests/test_cold_start.py tests/test_announcements.py tests/test_api_manage.py tests/test_join_modes.py tests/test_webhook.py tests/test_api_extended.py tests/test_bot_commands.py
```
Expected: all pass. The first two files hold 4 and 7 tests; the rest did not change. `test_cold_start.py` is the proof that the tick's import of `telegram_send` loads no aiogram.

- [ ] **Step 6: Gates.** ruff prints `All checks passed!`. mypy prints `Success: no issues found in 222 source files`. `pytest -q -n auto`: 2626 passed (2621 + 5).

- [ ] **Step 7: Commit.** Write `$SCRATCH/commit-mon-t2.txt`:
```text
Send to Telegram from outside the bot through one neutral module

app/telegram_send.py builds a bot for one job, sends a batch through it
and closes it, importing aiogram inside its functions, so importing it
costs a cold start nothing. app.bot.bot imports build_bot back from it,
and the tick and v1's two notice seams build their bot through it rather
than reaching into the bot's package. The seams stay where they were,
because the tests replace them. send never raises, not even on a token
aiogram refuses: every caller is in the middle of something a Telegram
outage must not undo. test_service_layering.py now holds that the
module reaches neither the bot nor a shell, since services/ will send
through it.

Not covered: v1's _tell helpers are unchanged; 3b-3 moves them here.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/telegram_send.py server/app/bot/bot.py server/app/api/cron.py server/app/api/edit.py server/app/api/manage/requests.py server/tests/test_telegram_send.py server/tests/test_service_layering.py && git commit -F C:/Users/lumen/AppData/Local/Temp/monitoring-run/commit-mon-t2.txt
```

---

## Task 3: `health_checks`, revision `0019`, and the head moved in every document

Rulings 3, 20 and 21. One new table, the ordinary additive shape, which goes on **before** the merge (Task 9).

**Files:**
- Create: `server/migrations/versions/0019_health_checks.py`, `server/tests/test_health_checks_revision.py`
- Modify: `server/app/models.py`, `server/app/db.py`, `server/tests/test_client_version_revision.py`
- Modify (the head moves): `CLAUDE.md`, `AGENTS.md`, `docs/deploy.md`, `docs/api.md`, `.claude/agents/server-migrations.md`, `.claude/skills/migration/SKILL.md`, `.github/PULL_REQUEST_TEMPLATE.md`
- Scratch, never committed: `$SCRATCH/issue-headpin.md`, `$SCRATCH/head0019.py`, `$SCRATCH/scan_heads.py`

**Interfaces:**
- Produces:
  - `models.HealthCheck`, with `name` (primary key), `status`, `reason`, `since`, `last_alert_at`, `checked_at`, `previous_checked_at` and `round_trip_ms`;
  - `db.EXPECTED_REVISION == "0019"`.

- [ ] **Step 1: File the defect this task fixes, before the fix.** `test_client_version_revision.py` asserts the head by number. Write `$SCRATCH/issue-headpin.md`:
```markdown
`server/tests/test_client_version_revision.py::test_the_revision_follows_0017_and_is_the_one_the_code_expects`
asserts `EXPECTED_REVISION == "0018"` beside `0018`'s place in the chain. The head is
pinned once already, by `test_schema_version.py::test_the_constant_the_app_ships_is_the_real_migration_head`,
which reads it from the migrations themselves. A second pin, written as a number, fails the
moment the next revision moves the head, although nothing about `0018` changed.

**Failure scenario:** add revision `0019` and move `EXPECTED_REVISION` to it, as the
monitoring pull request does. `pytest -q -n auto` then fails in that test with
`assert '0019' == '0018'`.

**Fix**, in the monitoring pull request (`docs/specs/2026-10-06-monitoring-plan.md`, Task 3):
the test keeps `0018`'s own place, revision `0018` down from `0017`, and asks only that the
code's head is at `0018` or later. `test_schema_version.py` stays the one pin.
```
Then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && gh issue create --repo lumenpearson/lessons --title "test_client_version_revision.py pins the schema head to 0018, so the next revision fails it" --body-file C:/Users/lumen/AppData/Local/Temp/monitoring-run/issue-headpin.md --label type:bug --label area:db --label status:now --milestone "v1.0.0 — A build somebody else can install"
```
Write down the number it prints: it is `#HEADPIN` below, and the pull request says `Closes #HEADPIN`. Put the issue on project 6 with the `github-pr` skill's «The board» commands, and read the board back.

- [ ] **Step 2: Red.**

Create `server/tests/test_health_checks_revision.py`:
```python
"""``0019``, the revision that keeps what each self-check said last.

The ordinary additive shape: one new table, created where it is missing and
dropped on the way down, held as ``0016``'s is held - on Postgres it builds
exactly the DDL the model declares, so a column changed in the one and not
the other fails here before anybody applies it through the Neon connector.

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
from sqlalchemy.schema import CreateTable

from app.db import EXPECTED_REVISION
from app.models import HealthCheck

SERVER_ROOT = Path(__file__).resolve().parent.parent
REVISION = SERVER_ROOT / "migrations" / "versions" / "0019_health_checks.py"


def _revision():
    spec = importlib.util.spec_from_file_location("revision_0019", REVISION)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _words(sql: str) -> str:
    return " ".join(sql.replace(";", " ").split())


def _render(function) -> str:
    rendered = io.StringIO()
    context = MigrationContext.configure(
        dialect_name="postgresql", opts={"as_sql": True, "output_buffer": rendered}
    )
    with Operations.context(context):
        function()
    return _words(rendered.getvalue())


def _alembic(database: Path, *args: str) -> subprocess.CompletedProcess:
    env = dict(os.environ, DATABASE_URL=f"sqlite+aiosqlite:///{database}")
    return subprocess.run(
        [sys.executable, "-m", "alembic", *args],
        env=env,
        cwd=str(SERVER_ROOT),
        capture_output=True,
        text=True,
    )


def test_the_revision_follows_0018_and_is_the_head_the_code_expects():
    revision = _revision()
    assert (revision.revision, revision.down_revision) == ("0019", "0018")
    assert EXPECTED_REVISION == "0019"


def test_on_postgres_it_builds_exactly_what_the_model_declares(monkeypatch):
    revision = _revision()
    # Offline there is no database to ask whether the table exists; the
    # module's own guard answers «missing» in that mode, as here.
    monkeypatch.setattr(revision, "_missing", lambda table: True)

    model = CreateTable(HealthCheck.__table__).compile(dialect=postgresql.dialect())
    assert _render(revision.upgrade) == _words(str(model))
    assert _render(revision.downgrade) == "DROP TABLE health_checks"


def test_a_file_without_the_table_gets_it_and_loses_it_going_down(tmp_path):
    """Run for real, on a database at ``0018`` with no such table.

    The chain from nothing never gets here: ``0001`` builds today's whole
    schema, the table with it, and this revision finds it and does nothing,
    which is the other half and is checked first.
    """
    database = tmp_path / "health.db"
    database.touch()
    built = _alembic(database, "upgrade", "head")
    assert built.returncode == 0, built.stderr

    with sqlite3.connect(database) as db:
        db.execute("DROP TABLE health_checks")
        db.execute("UPDATE alembic_version SET version_num = '0018'")

    upgraded = _alembic(database, "upgrade", "head")
    assert upgraded.returncode == 0, upgraded.stderr
    with sqlite3.connect(database) as db:
        # (cid, name, type, notnull, default, pk)
        columns = {row[1]: row for row in db.execute("PRAGMA table_info(health_checks)")}
        assert list(columns) == [column.name for column in HealthCheck.__table__.columns]
        assert columns["name"][5] == 1
        assert [name for name, row in columns.items() if row[3]] == [
            "name", "status", "reason", "since", "checked_at",
        ]

    down = _alembic(database, "downgrade", "0018")
    assert down.returncode == 0, down.stderr
    with sqlite3.connect(database) as db:
        tables = {row[0] for row in db.execute("select name from sqlite_master")}
        assert "health_checks" not in tables
        assert "device_tokens" in tables
```

In `server/tests/test_client_version_revision.py`, replace:
```python
def test_the_revision_follows_0017_and_is_the_one_the_code_expects() -> None:
    revision = _revision()
    assert (revision.revision, revision.down_revision) == ("0018", "0017")
    assert EXPECTED_REVISION == "0018"
```
with:
```python
def test_the_revision_follows_0017_and_the_code_expects_it_or_a_later_one() -> None:
    """Not the head by name: ``test_schema_version.py`` pins the head, and a
    second pin here failed the moment ``0019`` moved it."""
    revision = _revision()
    assert (revision.revision, revision.down_revision) == ("0018", "0017")
    assert EXPECTED_REVISION >= "0018"
```

Run:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_health_checks_revision.py
```
Expected: collection fails, on `ImportError: cannot import name 'HealthCheck'`. Then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_client_version_revision.py
```
Expected: 4 passed. The edited test holds before the head moves as after.

- [ ] **Step 3: The model.**

In `server/app/models.py`, replace:
```python
DEFAULT_BELLS: list[tuple[int, time, time]] = [
```
with:
```python
class HealthCheck(Base):
    """What one self-check of the cron tick said last, and when the owner was told.

    One row per check (``services/health.py``: ``schema``, ``v2``,
    ``diary_proxy``, ``deploy``), rewritten by every tick. It is what lets the
    owner be told of a *change* rather than once per tick, and what the bot's
    «📊 Проект» reads. Nothing in it names a class, a family or a child.
    """

    __tablename__ = "health_checks"

    name: Mapped[str] = mapped_column(String(32), primary_key=True)
    #: ``ok``, ``failing`` or ``unknown``. A string rather than an ``SAEnum``:
    #: a new state is then a value and not a migration, and never a ``.value``
    #: stored where the ORM reads a member's name.
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    #: What the check saw, in English, for the log and the owner's screen. Built
    #: by the check from facts - a revision, an HTTP status, an exception's
    #: type - and never from an exception's own text, which can carry the
    #: proxy's password.
    reason: Mapped[str] = mapped_column(String(200), nullable=False, default="")
    #: When the check last moved between ``ok`` and ``failing``. An ``unknown``
    #: neither starts nor ends a period, so a recovery after one says how long
    #: the whole failure lasted.
    since: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    #: When the owner was last told this check is failing; ``NULL`` once they
    #: have been told it is back, and before they were ever told anything.
    last_alert_at: Mapped[datetime | None] = mapped_column(DateTime)
    checked_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    #: The run before ``checked_at``, so the screen can show the gap between
    #: the last two ticks: five minutes from the external clock, hours from
    #: GitHub's.
    previous_checked_at: Mapped[datetime | None] = mapped_column(DateTime)
    #: The round trip of the request a check makes, when it makes one.
    round_trip_ms: Mapped[int | None] = mapped_column(Integer)


DEFAULT_BELLS: list[tuple[int, time, time]] = [
```

- [ ] **Step 4: The revision, and the constant.**

Create `server/migrations/versions/0019_health_checks.py`:
```python
"""Keep what each self-check of the cron tick said last, and when the owner was told.

Revision ID: 0019
Revises: 0018
Create Date: 2026-10-06

Every tick ends with four checks (``services/health.py``): the schema, v2,
the diary's proxy and the deploy. The owner is written to when one starts
failing, when it comes back, and every six hours while it stays failing -
never once per tick - so what each check said last has to outlive the
function instance that asked it. ``health_checks`` holds one row per check,
and the bot's «📊 Проект» reads it.

**Destroys nothing.** One new table; no existing row is read or rewritten.

**Apply this BEFORE the merge**, the ordinary additive order: the new code
writes the table on every tick and the bot reads it, and the old code never
mentions it.

The DDL is the model's own, as ``CLAUDE.md`` requires -
``CreateTable(HealthCheck.__table__).compile(dialect=postgresql.dialect())``
prints exactly::

    CREATE TABLE health_checks (
        name VARCHAR(32) NOT NULL,
        status VARCHAR(16) NOT NULL,
        reason VARCHAR(200) NOT NULL,
        since TIMESTAMP WITHOUT TIME ZONE NOT NULL,
        last_alert_at TIMESTAMP WITHOUT TIME ZONE,
        checked_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
        previous_checked_at TIMESTAMP WITHOUT TIME ZONE,
        round_trip_ms INTEGER,
        PRIMARY KEY (name)
    )

``tests/test_health_checks_revision.py`` holds the revision to that text.
Applied through the Neon connector with ``alembic_version`` stamped in the
same transaction; on local SQLite the table arrives through ``create_all``,
which is why the guard below asks first.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0019"
down_revision: str | None = "0018"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _missing(table: str) -> bool:
    # 0016's guard, for 0016's reasons: a database built by `create_all` after
    # the model landed - `0001` on an empty file, or `scripts.init_db` -
    # already has the table, and creating it again is a hard error rather
    # than a no-op. Offline there is nobody to ask.
    if context.is_offline_mode():
        return True
    return not sa.inspect(op.get_bind()).has_table(table)


def upgrade() -> None:
    if not _missing("health_checks"):
        return
    op.create_table(
        "health_checks",
        sa.Column("name", sa.String(length=32), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("reason", sa.String(length=200), nullable=False),
        sa.Column("since", sa.DateTime(), nullable=False),
        sa.Column("last_alert_at", sa.DateTime(), nullable=True),
        sa.Column("checked_at", sa.DateTime(), nullable=False),
        sa.Column("previous_checked_at", sa.DateTime(), nullable=True),
        sa.Column("round_trip_ms", sa.Integer(), nullable=True),
        sa.PrimaryKeyConstraint("name"),
    )


def downgrade() -> None:
    op.drop_table("health_checks")
```

In `server/app/db.py`, replace:
```python
EXPECTED_REVISION = "0018"
```
with:
```python
EXPECTED_REVISION = "0019"
```

- [ ] **Step 5: Move the head in every document that names it.** Write `$SCRATCH/head0019.py`:
```python
"""Move every sentence the head test reads to 0019 (monitoring, Task 3).

Each move asserts how often it lands, so a document that changed since the
plan was written fails here rather than keeping the old head quietly. The
patterns are built from pieces, as 3b-1's head0018.py built them, so that
this script, quoted in a plan under docs/, is not itself read as naming a
head. The tree is the worktree unless another is named.
"""

import re
import sys
from pathlib import Path

ROOT = Path(
    sys.argv[1] if len(sys.argv) > 1
    else "C:/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract"
)
OLD, NEW = "0018", "0019"
HEAD = re.compile(r"([Hh]ead is \**`)" + OLD + "`")
WARM = re.compile(r'("status": ?"ok"[^}\n]*"schema": ?")' + OLD + '"')


def move(name, pattern, end, count):
    path = ROOT / name
    text = path.read_text("utf-8")
    text, made = pattern.subn(lambda match: match.group(1) + NEW + end, text)
    assert made == count, (name, made)
    path.write_text(text, "utf-8")


move("CLAUDE.md", HEAD, "`", 1)
move("AGENTS.md", HEAD, "`", 1)
move(".claude/agents/server-migrations.md", HEAD, "`", 1)
move(".claude/skills/migration/SKILL.md", HEAD, "`", 1)
move("docs/deploy.md", HEAD, "`", 1)
move("docs/deploy.md", WARM, '"', 2)
move("docs/api.md", WARM, '"', 1)
print("moved")
```
and run it:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe C:/Users/lumen/AppData/Local/Temp/monitoring-run/head0019.py
```
Expected: `moved`. An `AssertionError` names the document whose text moved since this plan was written: read it, fix that one move, and run again on a clean tree (`git checkout -- <file>` for the documents it already wrote).

Then say what `0019` is, where `0018` is said.

In `CLAUDE.md`, replace:
```markdown
  `0018` were all applied that way, `0018` to the Neon branch `preview` first, when its pull
  request was pushed, and to production before the merge; every revision now goes that way.
```
with:
```markdown
  `0019` were all applied that way, and from `0018` on each went to the Neon branch `preview`
  first, when its pull request was pushed, and to production before the merge; every revision
  now goes that way.
```

In `CLAUDE.md`, replace:
```markdown
, applied before the merge of stage 3b-1 of sub-project 3; `0015` to `0017` were applied on 26 September 2026, before #140's merge.**
```
with:
```markdown
, applied before the merge of the monitoring pull request, as `0018` was before stage 3b-1's; `0015` to `0017` were applied on 26 September 2026, before #140's merge.**
```

In `CLAUDE.md`, replace:
```markdown
  and with it nothing but the versions.
  Nothing after `0001` may use `create_all`.
```
with:
```markdown
  and with it nothing but the versions.
  `0019` creates `health_checks`, one row per check of the tick's self-check
  (`services/health.py`): what it said last, since when, and when the owner was last
  told. One new table and nothing else, the ordinary additive shape, on **before** the
  merge; its downgrade drops the table and with it nothing but what the checks remembered.
  Nothing after `0001` may use `create_all`.
```

In `.claude/agents/server-migrations.md`, replace:
```markdown
before #140 merged, and `0018` before stage 3b-1 of sub-project 3 merged.
```
with:
```markdown
before #140 merged, `0018` before stage 3b-1 of sub-project 3 merged, and `0019` before the
monitoring pull request merged.
```

In `.claude/agents/server-migrations.md`, replace:
```markdown
`device_tokens.client_version`, the app version a phone last sent to v2: additive.
```
with:
```markdown
`device_tokens.client_version`, the app version a phone last sent to v2: additive. `0019`
creates `health_checks`, what each check of the tick's self-check said last: additive.
```

In `.claude/agents/server-migrations.md`, replace:
```markdown
`0005` through `0018` were all applied that way.
```
with:
```markdown
`0005` through `0019` were all applied that way.
```

In `docs/deploy.md`, replace:
```markdown
is nullable with no default, additive, and on **before** the merge; its downgrade drops
the column, which loses nothing but the versions.
```
with:
```markdown
is nullable with no default, additive, and on **before** the merge; its downgrade drops
the column, which loses nothing but the versions.

`0019` creates `health_checks`: one row per check of the tick's self-check, holding what it
said last, since when, and when the owner was last told («Monitoring», below). One new table
and nothing else, additive, and on **before** the merge; its downgrade drops the table, which
loses nothing but what the checks remembered.
```

In `docs/deploy.md`, replace:
```markdown
`0018` asks per column, as `0015` does.
```
with:
```markdown
`0018` asks per column, as `0015` does, and `0019` per table, as `0016` does.
```

In `docs/deploy.md`, replace:
```markdown
with the `alembic` command — that is how `0005`–`0017` were applied, the last three
together on 26 September 2026, before #140 merged. The Neon project is
called `lessons`; its identifier is not kept in the repository — anybody with access sees it
```
with:
```markdown
with the `alembic` command — that is how `0005`–`0019` were applied: `0015`–`0017` together
on 26 September 2026, before #140 merged, and from `0018` on each to the Neon branch
`preview` when its pull request was pushed and to production before the merge. The Neon
project is called `lessons`; its identifier is not kept in the repository — anybody with
access sees it
```

In `.github/PULL_REQUEST_TEMPLATE.md`, replace:
```markdown
      `app/db.py:EXPECTED_REVISION`, currently `0018`; no `create_all` after `0001`)
```
with:
```markdown
      `app/db.py:EXPECTED_REVISION`, currently `0019`; no `create_all` after `0001`)
```

- [ ] **Step 6: Scan every document the head test reads, this plan included.** Write `$SCRATCH/scan_heads.py`:
```python
"""Every sentence the head test reads, in every document it reads (monitoring, Task 3)."""

import re
import sys
from pathlib import Path

ROOT = Path(
    sys.argv[1] if len(sys.argv) > 1
    else "C:/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract"
)
# test_schema_version.py's three shapes, built from pieces for the reason
# head0019.py gives.
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
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe C:/Users/lumen/AppData/Local/Temp/monitoring-run/scan_heads.py
```
Expected: eight lines, all `0019`, from these places:
- `CLAUDE.md`;
- `AGENTS.md`;
- `docs/api.md`;
- `docs/deploy.md`, three times;
- `.claude/agents/server-migrations.md`;
- `.claude/skills/migration/SKILL.md`.

The last line is `revisions named: ['0019']`. No line names `docs/specs/`.

- [ ] **Step 7: Green.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_health_checks_revision.py tests/test_client_version_revision.py tests/test_schema_version.py tests/test_quota.py tests/test_diary_provider_revision.py tests/test_corrections_per_child_revision.py tests/test_enum_column_widths.py tests/test_ci_paths.py
```
Expected: all pass, `test_health_checks_revision.py` 3 of 3. The other revision tests run the chain to `0019`, and down through it, past its guard.

- [ ] **Step 8: Gates.** ruff prints `All checks passed!`. mypy prints `Success: no issues found in 222 source files`, since migrations are not in mypy's walk. `pytest -q -n auto`: 2629 passed (2626 + 3).

- [ ] **Step 9: Commit.** Write `$SCRATCH/commit-mon-t3.txt`, with `#HEADPIN` replaced by the number from Step 1:
```text
Keep what each self-check of the tick said last, in health_checks

Revision 0019 creates one table, one row per check: its status, the
reason in English, since when, when the owner was last told, when it ran
and the run before, and the round trip of the request it made. The DDL
is the model's own, which test_health_checks_revision.py holds, and the
revision asks whether the table is there first, as 0016 does. It
destroys nothing, and goes on before the merge.

EXPECTED_REVISION and every document that names the head move with it.
test_client_version_revision.py pinned the head as a number beside
0018's own place in the chain, and failed the moment it moved (#HEADPIN);
it asks only for 0018's place now, and test_schema_version.py stays the
one pin.

Not covered: nothing writes the table yet; the tick does in a later
commit.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/models.py server/app/db.py server/migrations/versions/0019_health_checks.py server/tests/test_health_checks_revision.py server/tests/test_client_version_revision.py CLAUDE.md AGENTS.md docs/deploy.md docs/api.md .claude/agents/server-migrations.md .claude/skills/migration/SKILL.md .github/PULL_REQUEST_TEMPLATE.md && git commit -F C:/Users/lumen/AppData/Local/Temp/monitoring-run/commit-mon-t3.txt
```

---

## Task 4: The four checks, the alert rule, and one run of them

Rulings 4 to 10 and 13. Nothing calls `run` yet; Task 5 does.

**Files:**
- Create: `server/app/services/health.py`, `server/tests/test_health.py`
- Modify: `server/app/config.py`, `server/app/wording.py`, `server/app/bot/week_render.py`

**Interfaces:**
- Consumes:
  - `telegram_send.send` (Task 2);
  - `models.HealthCheck` (Task 3);
  - `db.current_revision`, `db.EXPECTED_REVISION`, `db.rows_affected`;
  - `Settings.diary_proxy`, `Settings.behind_vercel`, `Settings.owner_id_list`;
  - `providers.petersburg.client.BASE_URL`.
- Produces:
  - `config.STARTED_AT: datetime`;
  - `config.Deployment(environment, commit, repository, region)` and `config.deployment(environ=None) -> Deployment`;
  - `wording.duration(minutes: int) -> str`;
  - in `services/health`:
    - `OK`, `FAILING`, `UNKNOWN`, `CHECKS`;
    - `Result(status, reason="", round_trip_ms=None)`, `Previous(status, since, last_alert_at)` and `Step(status, since, alert=None, lasted=None)`;
    - `decide(previous, result, now) -> Step` and `message(name, step, reason) -> str`;
    - `check_schema(session)`, `check_v2(mounted)`, `check_diary_proxy(settings, *, timeout)` and `check_deploy(settings, now, *, timeout)`;
    - `claim_alert(session, name, expected, new) -> bool`;
    - `run(session, settings, *, v2_mounted, started=None, send=None) -> dict[str, str]`.

- [ ] **Step 1: Red.**

Create `server/tests/test_health.py`:
```python
"""The self-check the tick ends with: four checks, their states, and the alert rule.

The outside world is faked at its three seams: the proxy's client
(``health._proxy_client``), GitHub's (``health._github_client``) and Vercel's
own environment variables. Nothing here reaches the network, and nothing here
sends a message: ``run`` is handed a sender that records what it was given.

What the owner would notice first is pinned hardest: one message on a change,
none per tick, a reminder every six hours, nothing at all for ``unknown``, and
a Telegram that refuses never failing the run.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Callable
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import pytest
from sqlalchemy import select, update
from sqlalchemy import text as sa_text

from app.config import Deployment, Settings, deployment
from app.db import EXPECTED_REVISION
from app.models import HealthCheck
from app.services import health
from app.services.health import FAILING, OK, UNKNOWN, Previous, Result, Step, decide

OWNER = 1000  # OWNER_IDS in conftest
PROXY = "http://user:hunter2@proxy.example:3128"
COMMIT = "a" * 40
MAIN = "b" * 40
T0 = datetime(2026, 10, 6, 12, 0)


def _now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


@pytest.fixture(autouse=True)
def forget_main_s_head(monkeypatch):
    """The ETag cache is the process's; each test starts without one."""
    monkeypatch.setattr(health, "_main_head", None)


class Sent:
    """A sender that records each batch and answers as it is told to."""

    def __init__(self, answer: str = "delivered") -> None:
        self.batches: list[list[tuple[int, str]]] = []
        self.answer = answer

    async def __call__(self, messages):
        self.batches.append(list(messages))
        if self.answer == "raises":
            raise RuntimeError("Telegram is down")
        return [self.answer == "delivered"] * len(messages)

    @property
    def texts(self) -> list[str]:
        return [text for batch in self.batches for _, text in batch]


async def _rows(session) -> dict[str, HealthCheck]:
    session.expire_all()
    return {row.name: row for row in await session.scalars(select(HealthCheck))}


@pytest.fixture
async def stamped(session) -> AsyncIterator[Callable[[str], Any]]:
    """Put an ``alembic_version`` in the test database, and take it away
    after: the autouse ``drop_all`` does not know alembic's table."""

    async def stamp(revision: str) -> None:
        await session.execute(sa_text("DROP TABLE IF EXISTS alembic_version"))
        await session.execute(
            sa_text("CREATE TABLE alembic_version (version_num VARCHAR(32) NOT NULL)")
        )
        await session.execute(
            sa_text("INSERT INTO alembic_version (version_num) VALUES (:v)"), {"v": revision}
        )
        await session.commit()

    yield stamp
    await session.execute(sa_text("DROP TABLE IF EXISTS alembic_version"))
    await session.commit()


def _fake_proxy(monkeypatch, handler) -> list[str]:
    """Answer the proxy check with ``handler``; returns the proxies it was built with."""
    built: list[str] = []

    def client(proxy: str, timeout: float) -> httpx.AsyncClient:
        built.append(proxy)
        return httpx.AsyncClient(transport=httpx.MockTransport(handler), timeout=timeout)

    monkeypatch.setattr(health, "_proxy_client", client)
    return built


def _fake_github(monkeypatch, handler) -> list[httpx.Request]:
    """Answer GitHub with ``handler``; returns the requests it was asked."""
    asked: list[httpx.Request] = []

    async def recording(request: httpx.Request) -> httpx.Response:
        asked.append(request)
        answer = handler(request)
        return await answer if asyncio.iscoroutine(answer) else answer

    def client(timeout: float) -> httpx.AsyncClient:
        return httpx.AsyncClient(
            base_url=health.GITHUB_API, transport=httpx.MockTransport(recording), timeout=timeout
        )

    monkeypatch.setattr(health, "_github_client", client)
    return asked


def _commit(sha: str, moved_at: datetime, etag: str = 'W/"one"') -> httpx.Response:
    return httpx.Response(
        200,
        json={"sha": sha, "commit": {"committer": {"date": f"{moved_at:%Y-%m-%dT%H:%M:%S}Z"}}},
        headers={"etag": etag},
    )


@pytest.fixture
def production(monkeypatch) -> Settings:
    """A production deployment on Vercel, as the platform describes it."""
    monkeypatch.setenv("VERCEL_ENV", "production")
    monkeypatch.setenv("VERCEL_GIT_COMMIT_SHA", COMMIT)
    monkeypatch.setenv("VERCEL_GIT_REPO_OWNER", "lumenpearson")
    monkeypatch.setenv("VERCEL_GIT_REPO_SLUG", "lessons")
    return Settings(VERCEL="1")


# --------------------------------------------------------------------------
# The rule
# --------------------------------------------------------------------------


def test_a_check_that_starts_failing_is_told_once():
    first = decide(None, Result(FAILING, "x"), T0)
    assert (first.status, first.since, first.alert) == (FAILING, T0, "down")

    after_ok = decide(Previous(OK, T0 - timedelta(days=2), None), Result(FAILING), T0)
    assert (after_ok.since, after_ok.alert) == (T0, "down")


def test_a_failure_is_not_told_again_on_every_tick():
    told = Previous(FAILING, T0, T0)
    later = decide(told, Result(FAILING), T0 + timedelta(minutes=5))
    assert (later.status, later.since, later.alert) == (FAILING, T0, None)


def test_a_failure_six_hours_after_the_last_alert_is_told_again():
    told = Previous(FAILING, T0, T0)
    almost = decide(told, Result(FAILING), T0 + timedelta(hours=6) - timedelta(seconds=1))
    assert almost.alert is None

    again = decide(told, Result(FAILING), T0 + timedelta(hours=6))
    assert (again.alert, again.lasted, again.since) == ("reminder", timedelta(hours=6), T0)


def test_a_recovery_is_told_once_with_how_long_it_lasted():
    back = decide(Previous(FAILING, T0, T0), Result(OK), T0 + timedelta(minutes=40))
    assert (back.status, back.since, back.alert, back.lasted) == (
        OK, T0 + timedelta(minutes=40), "up", timedelta(minutes=40),
    )

    settled = decide(Previous(OK, back.since, None), Result(OK), T0 + timedelta(hours=1))
    assert (settled.since, settled.alert) == (back.since, None)


def test_unknown_never_alerts_and_moves_no_period():
    assert decide(None, Result(UNKNOWN), T0).alert is None
    for previous in (Previous(OK, T0, None), Previous(FAILING, T0, T0)):
        step = decide(previous, Result(UNKNOWN), T0 + timedelta(hours=7))
        assert (step.status, step.since, step.alert) == (UNKNOWN, T0, None)


def test_a_failure_that_went_unknown_is_reported_whole_when_it_comes_back():
    """GitHub stopped answering in the middle of a late deploy, then the
    deploy landed: the «up» says how long the whole failure lasted."""
    went_unknown = Previous(UNKNOWN, T0, T0)
    back = decide(went_unknown, Result(OK), T0 + timedelta(hours=2))
    assert (back.alert, back.lasted) == ("up", timedelta(hours=2))


def test_a_failure_that_went_unknown_and_failed_again_is_not_told_twice():
    went_unknown = Previous(UNKNOWN, T0, T0)
    again = decide(went_unknown, Result(FAILING), T0 + timedelta(minutes=10))
    assert (again.status, again.since, again.alert) == (FAILING, T0, None)


def test_a_failure_the_owner_never_heard_of_is_told_next_tick_and_has_no_recovery():
    """Its alert did not get through, so the claim was given back: the next
    tick tells it, and a recovery before then says nothing at all."""
    unheard = Previous(FAILING, T0, None)
    assert decide(unheard, Result(FAILING), T0 + timedelta(minutes=5)).alert == "down"
    assert decide(unheard, Result(OK), T0 + timedelta(minutes=5)).alert is None


def test_the_messages_are_the_design_s_and_escape_the_reason():
    up = Step(OK, T0, "up", timedelta(minutes=40))
    assert health.message("diary_proxy", up, "") == (
        "🟢 Прокси дневника снова работает, простой 40 мин"
    )

    down = Step(FAILING, T0, "down")
    assert health.message("diary_proxy", down, "no answer: <ProxyError> & co") == (
        "🔴 Прокси дневника не отвечает\n"
        "<code>no answer: &lt;ProxyError&gt; &amp; co</code>"
    )

    reminder = Step(FAILING, T0, "reminder", timedelta(hours=6))
    assert health.message("deploy", reminder, "") == (
        "🔴 Продакшен не на последнем коммите main — уже 6 ч"
    )
    assert health.message("schema", Step(OK, T0), "at 0019") == ""
    assert set(health.WORDS) == set(health.CHECKS)


# --------------------------------------------------------------------------
# The checks
# --------------------------------------------------------------------------


async def test_the_schema_check_reads_the_revision_warmup_reads(session, stamped):
    unknown = await health.check_schema(session)
    assert unknown.status == UNKNOWN

    await stamped(EXPECTED_REVISION)
    assert (await health.check_schema(session)).status == OK

    await stamped("0001")
    failing = await health.check_schema(session)
    assert failing.status == FAILING
    assert "0001" in failing.reason and EXPECTED_REVISION in failing.reason


def test_the_v2_check_is_the_mount_s_own_record():
    assert health.check_v2(True).status == OK
    assert health.check_v2(False).status == FAILING


async def test_the_proxy_check_takes_any_answer_and_times_it(monkeypatch):
    seen: list[httpx.Request] = []

    def answer(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(405)

    built = _fake_proxy(monkeypatch, answer)
    result = await health.check_diary_proxy(Settings(DIARY_PROXY_URL=PROXY))

    assert (result.status, result.reason) == (OK, "HTTP 405")
    assert result.round_trip_ms is not None and result.round_trip_ms >= 0
    assert built == [PROXY]
    assert [(r.method, str(r.url)) for r in seen] == [("HEAD", "https://dnevnik2.petersburgedu.ru/")]


async def test_a_proxy_that_does_not_answer_is_failing_and_its_password_is_never_said(
    monkeypatch,
):
    def refuse(request: httpx.Request) -> httpx.Response:
        raise httpx.ProxyError(f"407 Proxy Authentication Required from {PROXY}")

    _fake_proxy(monkeypatch, refuse)
    result = await health.check_diary_proxy(Settings(DIARY_PROXY_URL=PROXY))

    assert result.status == FAILING
    assert result.reason == "no answer through the proxy: ProxyError"
    assert "hunter2" not in result.reason


async def test_a_proxy_slower_than_its_budget_is_failing(monkeypatch):
    async def stall(request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(5)
        return httpx.Response(200)

    _fake_proxy(monkeypatch, stall)
    result = await health.check_diary_proxy(Settings(DIARY_PROXY_URL=PROXY), timeout=0.05)

    assert result.status == FAILING
    assert result.reason == "no answer through the proxy: TimeoutError"


async def test_without_a_usable_proxy_there_is_nothing_to_check(monkeypatch):
    built = _fake_proxy(monkeypatch, lambda request: httpx.Response(200))

    empty = await health.check_diary_proxy(Settings(DIARY_PROXY_URL=""))
    unusable = await health.check_diary_proxy(Settings(DIARY_PROXY_URL="ftp://user:x@host:21"))

    assert (empty.status, unusable.status) == (UNKNOWN, UNKNOWN)
    assert "x@host" not in unusable.reason
    assert built == []


async def test_the_deploy_check_is_ok_on_main_s_head(monkeypatch, production):
    _fake_github(monkeypatch, lambda request: _commit(COMMIT, _now() - timedelta(days=1)))
    result = await health.check_deploy(production, _now())
    assert (result.status, result.reason) == (OK, f"running main's head {COMMIT[:7]}")


async def test_a_deploy_inside_its_grace_is_ok_and_one_past_it_is_failing(monkeypatch, production):
    now = _now()
    _fake_github(monkeypatch, lambda request: _commit(MAIN, now - timedelta(minutes=5)))
    assert (await health.check_deploy(production, now)).status == OK

    health._main_head = None
    _fake_github(monkeypatch, lambda request: _commit(MAIN, now - timedelta(minutes=20)))
    late = await health.check_deploy(production, now)
    assert late.status == FAILING
    assert COMMIT[:7] in late.reason and MAIN[:7] in late.reason


async def test_the_deploy_check_is_unknown_off_production(monkeypatch, production):
    asked = _fake_github(monkeypatch, lambda request: _commit(COMMIT, _now()))

    off_vercel = await health.check_deploy(Settings(VERCEL=""), _now())
    monkeypatch.setenv("VERCEL_ENV", "preview")
    preview = await health.check_deploy(production, _now())
    monkeypatch.setenv("VERCEL_ENV", "production")
    monkeypatch.delenv("VERCEL_GIT_COMMIT_SHA")
    unexposed = await health.check_deploy(production, _now())

    assert [off_vercel.status, preview.status, unexposed.status] == [UNKNOWN] * 3
    assert asked == []


@pytest.mark.parametrize(
    "answer",
    [
        lambda request: httpx.Response(403, json={"message": "API rate limit exceeded"}),
        lambda request: httpx.Response(200, json={"message": "not a commit"}),
        lambda request: (_ for _ in ()).throw(httpx.ConnectError("no route")),
    ],
    ids=["rate-limited", "not-a-commit", "no-answer"],
)
async def test_a_github_that_refuses_or_does_not_answer_is_unknown_never_failing(
    monkeypatch, production, answer
):
    _fake_github(monkeypatch, answer)
    result = await health.check_deploy(production, _now())
    assert result.status == UNKNOWN


async def test_an_unchanged_main_is_asked_with_its_etag_and_answered_304(monkeypatch, production):
    moved = _now() - timedelta(days=1)

    def answer(request: httpx.Request) -> httpx.Response:
        if request.headers.get("if-none-match") == 'W/"one"':
            return httpx.Response(304)
        return _commit(COMMIT, moved)

    asked = _fake_github(monkeypatch, answer)
    first = await health.check_deploy(production, _now())
    second = await health.check_deploy(production, _now())

    assert (first.status, second.status) == (OK, OK)
    assert [r.headers.get("if-none-match") for r in asked] == [None, 'W/"one"']
    assert asked[0].url.path == "/repos/lumenpearson/lessons/commits/main"


def test_the_deployment_is_what_vercel_says_and_nothing_else():
    said = deployment(
        {
            "VERCEL_ENV": "production",
            "VERCEL_GIT_COMMIT_SHA": f" {COMMIT}\n",
            "VERCEL_GIT_REPO_OWNER": "lumenpearson",
            "VERCEL_GIT_REPO_SLUG": "lessons",
            "VERCEL_REGION": "fra1",
        }
    )
    assert (said.environment, said.commit, said.repository, said.region) == (
        "production", COMMIT, "lumenpearson/lessons", "fra1",
    )
    assert deployment({"VERCEL_GIT_REPO_OWNER": "lumenpearson"}).repository == ""
    assert deployment({}) == Deployment(environment="", commit="", repository="", region="")


# --------------------------------------------------------------------------
# One run
# --------------------------------------------------------------------------


async def test_a_run_keeps_one_row_per_check_and_the_tick_before(session):
    sent = Sent()
    statuses = await health.run(session, Settings(), v2_mounted=True, send=sent)

    assert statuses == {"schema": UNKNOWN, "v2": OK, "diary_proxy": UNKNOWN, "deploy": UNKNOWN}
    first = await _rows(session)
    assert set(first) == set(health.CHECKS)
    assert all(row.previous_checked_at is None for row in first.values())
    checked = first["v2"].checked_at

    await health.run(session, Settings(), v2_mounted=True, send=sent)
    second = await _rows(session)
    assert second["v2"].previous_checked_at == checked
    assert second["v2"].since == first["v2"].since
    assert sent.batches == []


async def test_one_message_on_a_change_both_ways_and_none_per_tick(session):
    sent = Sent()
    for _ in range(3):
        await health.run(session, Settings(), v2_mounted=False, send=sent)
    assert sent.batches == [[(OWNER, "🔴 v2 не загрузился: /api/v2 и /api/rpc отвечают 503\n"
                                     "<code>not loaded: /api/v2 and /api/rpc answer 503</code>")]]

    await session.execute(
        update(HealthCheck)
        .where(HealthCheck.name == "v2")
        .values(since=_now() - timedelta(minutes=40))
    )
    await session.commit()
    for _ in range(2):
        await health.run(session, Settings(), v2_mounted=True, send=sent)

    assert sent.texts[1:] == ["🟢 v2 снова загружен, простой 40 мин"]
    assert (await _rows(session))["v2"].last_alert_at is None


async def test_a_check_still_failing_six_hours_on_is_told_again(session):
    sent = Sent()
    await health.run(session, Settings(), v2_mounted=False, send=sent)
    await session.execute(
        update(HealthCheck)
        .where(HealthCheck.name == "v2")
        .values(
            since=_now() - timedelta(hours=6, minutes=1),
            last_alert_at=_now() - timedelta(hours=6, minutes=1),
        )
    )
    await session.commit()

    await health.run(session, Settings(), v2_mounted=False, send=sent)
    await health.run(session, Settings(), v2_mounted=False, send=sent)

    assert len(sent.texts) == 2
    assert sent.texts[1].startswith(
        "🔴 v2 не загрузился: /api/v2 и /api/rpc отвечают 503 — уже 6 ч"
    )


@pytest.mark.parametrize("answer", ["raises", "refused"])
async def test_a_send_that_fails_fails_nothing_and_is_tried_again_next_tick(session, answer):
    failing = Sent(answer)
    statuses = await health.run(session, Settings(), v2_mounted=False, send=failing)

    assert statuses["v2"] == FAILING
    assert len(failing.batches) == 1
    assert (await _rows(session))["v2"].last_alert_at is None

    working = Sent()
    await health.run(session, Settings(), v2_mounted=False, send=working)
    assert len(working.batches) == 1
    assert (await _rows(session))["v2"].last_alert_at is not None


async def test_an_alert_one_of_two_owners_got_is_told_and_not_repeated(session):
    """One owner has blocked the bot: the other was told, which is what the
    alert is for, so the claim stands and the next tick says nothing."""

    class OneOfTwo(Sent):
        async def __call__(self, messages):
            self.batches.append(list(messages))
            return [True, False]

    sent = OneOfTwo()
    settings = Settings(OWNER_IDS="1000,1001")
    await health.run(session, settings, v2_mounted=False, send=sent)
    await health.run(session, settings, v2_mounted=False, send=sent)

    assert [[owner for owner, _ in batch] for batch in sent.batches] == [[1000, 1001]]
    assert (await _rows(session))["v2"].last_alert_at is not None


async def test_two_ticks_running_together_tell_the_owner_once(session):
    await health.run(session, Settings(), v2_mounted=True, send=Sent())
    now = _now()

    assert await health.claim_alert(session, "v2", None, now)
    assert not await health.claim_alert(session, "v2", None, now)
    assert await health.claim_alert(session, "v2", now, None)


async def test_with_no_owner_nothing_is_claimed_or_sent(session):
    sent = Sent()
    await health.run(session, Settings(OWNER_IDS=""), v2_mounted=False, send=sent)

    assert sent.batches == []
    assert (await _rows(session))["v2"].last_alert_at is None


async def test_a_tick_out_of_time_asks_nobody_and_says_so(monkeypatch, session, production):
    proxies = _fake_proxy(monkeypatch, lambda request: httpx.Response(200))
    asked = _fake_github(monkeypatch, lambda request: _commit(COMMIT, _now()))
    settings = Settings(VERCEL="1", DIARY_PROXY_URL=PROXY)
    long_ago = asyncio.get_running_loop().time() - 100

    statuses = await health.run(session, settings, v2_mounted=True, started=long_ago, send=Sent())

    assert (statuses["diary_proxy"], statuses["deploy"]) == (UNKNOWN, UNKNOWN)
    assert (proxies, asked) == ([], [])
    rows = await _rows(session)
    assert rows["deploy"].reason == "the tick ran out of time before this check"


async def test_a_check_that_raises_is_unknown_and_the_run_goes_on(monkeypatch, session):
    async def broken(*args, **kwargs):
        raise RuntimeError("a bug")

    monkeypatch.setattr(health, "check_schema", broken)
    monkeypatch.setattr(health, "check_diary_proxy", broken)

    statuses = await health.run(session, Settings(), v2_mounted=True, send=Sent())

    assert statuses == {"schema": UNKNOWN, "v2": OK, "diary_proxy": UNKNOWN, "deploy": UNKNOWN}
    rows = await _rows(session)
    assert rows["schema"].reason == "the check raised RuntimeError"
    assert rows["diary_proxy"].reason == "the check raised RuntimeError"
```

Run:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_health.py
```
Expected: collection fails with `ImportError: cannot import name 'Deployment' from 'app.config'`.

- [ ] **Step 2: What Vercel says about the deployment, and when this process started.**

In `server/app/config.py`, replace:
```python
from __future__ import annotations

from functools import lru_cache
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo
```
with:
```python
from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from functools import lru_cache
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo
```

In `server/app/config.py`, replace:
```python
@lru_cache
def get_settings() -> Settings:
```
with:
```python
#: When this process first loaded its settings, which every entry point does
#: before anything else: on Vercel, the cold start of this function instance.
#: «📊 Проект» shows how long ago that was, so a cold start reads as minutes.
STARTED_AT = datetime.now(UTC).replace(tzinfo=None)


@dataclass(frozen=True)
class Deployment:
    """What Vercel says about the deployment this process belongs to.

    Its System Environment Variables, read from the process's environment and
    never from ``.env``: none of them is configuration. A value the platform
    states about itself has no business in the file people copy, for the
    reason ``VERCEL`` is not there (``server/.env.example``), and off Vercel
    every field is empty. They reach a function only while the project's
    «Automatically expose System Environment Variables» is on, which is
    Vercel's default (``docs/deploy.md``).
    """

    #: ``VERCEL_ENV``: ``production``, ``preview`` or ``development``.
    environment: str
    #: ``VERCEL_GIT_COMMIT_SHA``: the commit this deployment was built from.
    commit: str
    #: ``owner/slug``, from ``VERCEL_GIT_REPO_OWNER`` and
    #: ``VERCEL_GIT_REPO_SLUG``; empty unless both are there. Read rather than
    #: written down, so that a fork's deployment asks about its own ``main``.
    repository: str
    #: ``VERCEL_REGION``: where this function runs, ``fra1`` here.
    region: str


def deployment(environ: Mapping[str, str] | None = None) -> Deployment:
    """The running deployment as Vercel describes it; see :class:`Deployment`."""
    env = os.environ if environ is None else environ

    def read(name: str) -> str:
        return env.get(name, "").strip()

    owner, slug = read("VERCEL_GIT_REPO_OWNER"), read("VERCEL_GIT_REPO_SLUG")
    return Deployment(
        environment=read("VERCEL_ENV"),
        commit=read("VERCEL_GIT_COMMIT_SHA"),
        repository=f"{owner}/{slug}" if owner and slug else "",
        region=read("VERCEL_REGION"),
    )


@lru_cache
def get_settings() -> Settings:
```

- [ ] **Step 3: `duration` moves where `services/` can reach it** (Ruling 13).

In `server/app/bot/week_render.py`, replace:
```python
def duration(minutes: int) -> str:
    """«1 ч 12 мин», «45 мин», «2 ч» — whole minutes, never seconds."""
    hours, rest = divmod(max(minutes, 0), 60)
    parts = []
    if hours:
        parts.append(plural(hours, "ч", "ч", "ч"))
    if rest or not hours:
        parts.append(f"{rest} мин")
    return " ".join(parts)


def _minutes_until(now: datetime, at: Time) -> int:
```
with:
```python
def _minutes_until(now: datetime, at: Time) -> int:
```

In `server/app/bot/week_render.py`, replace:
```python
from app.wording import DAY_KIND_LABELS, EVENT_ICONS, MONTHS_GENITIVE, WEEKDAYS_SHORT, clamp, plural
```
with:
```python
from app.wording import (
    DAY_KIND_LABELS,
    EVENT_ICONS,
    MONTHS_GENITIVE,
    WEEKDAYS_SHORT,
    clamp,
    duration,
    plural,
)
```

In `server/app/wording.py`, replace:
```python
        form = one if tail == 1 else few if 2 <= tail <= 4 else many
    return f"{count} {form}"
```
with:
```python
        form = one if tail == 1 else few if 2 <= tail <= 4 else many
    return f"{count} {form}"


def duration(minutes: int) -> str:
    """«1 ч 12 мин», «45 мин», «2 ч» — whole minutes, never seconds.

    Here rather than in the bot's week view, where it was written: the
    self-check's alerts say how long a failure lasted in the same words, and
    ``services/`` may not import the bot.
    """
    hours, rest = divmod(max(minutes, 0), 60)
    parts = []
    if hours:
        parts.append(plural(hours, "ч", "ч", "ч"))
    if rest or not hours:
        parts.append(f"{rest} мин")
    return " ".join(parts)
```

- [ ] **Step 4: The checks and the rule.**

Create `server/app/services/health.py`:
```python
"""The self-check every cron tick ends with, and what the owner is told of it.

Nothing inside a serverless deployment notices that it is broken. On 5 October
a merge did not deploy for half an hour (#349) and the diary's proxy answered
nothing for forty minutes, and the owner found each one by asking. So the tick,
the one thing that arrives from outside every five minutes, ends by asking four
questions (``docs/specs/2026-10-05-monitoring-design.md``):

- ``schema``: is the database at the revision this code expects?
- ``v2``: did v2 load, or does ``main.mount_v2`` answer 503 for it?
- ``diary_proxy``: does a ``HEAD`` of the Petersburg diary through
  ``DIARY_PROXY_URL`` get any answer at all within five seconds?
- ``deploy``: is production running ``main``'s head, or did ``main`` move less
  than fifteen minutes ago?

Each ends ``ok``, ``failing`` or ``unknown``. Unknown is a check that could not
run - GitHub not answering, a deployment that is not production, a proxy
nobody configured - and it never alerts. What each said last is a row of
``health_checks``. The owner, every account in ``OWNER_IDS``, is written to when
a check starts failing, when it comes back, and every six hours while it stays
failing, never once per tick. The text names the infrastructure only, never a
class, a family or a child.

It runs last in the tick, inside its own guard and its own time budget, as the
diary keep-alive does, so a failing check, a slow GitHub or a Telegram that
refuses the alert never fails the tick (``api/cron.py``).
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from html import escape

import httpx
from sqlalchemy import select
from sqlalchemy import update as sa_update
from sqlalchemy.ext.asyncio import AsyncSession

from app import telegram_send
from app.config import Settings, deployment
from app.db import EXPECTED_REVISION, current_revision, rows_affected
from app.models import HealthCheck
from app.providers.petersburg.client import BASE_URL as DIARY_ORIGIN
from app.wording import cut, duration

log = logging.getLogger(__name__)

OK, FAILING, UNKNOWN = "ok", "failing", "unknown"

#: The checks, in the order the owner's screen lists them.
CHECKS = ("schema", "v2", "diary_proxy", "deploy")

#: How often the owner is reminded of a check that stays failing: often
#: enough not to be forgotten, rarely enough not to be muted (the design's
#: question 1, answered by the owner).
REMINDER_EVERY = timedelta(hours=6)

#: How long ``main`` may be ahead of production before the deploy is late.
#: Vercel builds a merge in two or three minutes; fifteen is a deploy that
#: did not start, which is what #349 was.
DEPLOY_GRACE = timedelta(minutes=15)

#: The proxy's ``HEAD`` and GitHub's read each get this long, and no longer.
CHECK_TIMEOUT_SECONDS = 5.0
#: The alerts' sends together get this long, and no longer.
SEND_TIMEOUT_SECONDS = 10.0
#: Counted from the start of the request, as the keep-alive's deadlines are:
#: nothing of the self-check is started past this point, so the tick ends
#: inside the function's ceiling (``maxDuration: 30`` in ``vercel.json``)
#: however long the digests and the keep-alive took first.
HEALTH_HARD_STOP_SECONDS = 28.0

#: The longest reason kept: one line on a screen, and the column's width.
REASON_MAX = 200

GITHUB_API = "https://api.github.com"

#: What the owner is told, per check: when it starts failing, when it is back.
WORDS: dict[str, tuple[str, str]] = {
    "schema": ("Схема базы не совпадает с кодом", "Схема базы снова совпадает с кодом"),
    "v2": ("v2 не загрузился: /api/v2 и /api/rpc отвечают 503", "v2 снова загружен"),
    "diary_proxy": ("Прокси дневника не отвечает", "Прокси дневника снова работает"),
    "deploy": (
        "Продакшен не на последнем коммите main",
        "Продакшен снова на последнем коммите main",
    ),
}

#: A sender: each ``(telegram_id, text)`` in, whether each was delivered out.
Sender = Callable[[Sequence[tuple[int, str]]], Awaitable[list[bool]]]


def _now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


@dataclass(frozen=True)
class Result:
    """What one check said this tick."""

    status: str
    reason: str = ""
    round_trip_ms: int | None = None


@dataclass(frozen=True)
class Previous:
    """What :func:`decide` reads of a check's row."""

    status: str
    since: datetime
    last_alert_at: datetime | None


@dataclass(frozen=True)
class Step:
    """What a check's row becomes, and what, if anything, the owner is told.

    ``alert`` is ``"down"``, ``"reminder"``, ``"up"`` or ``None``; ``lasted``
    is how long the failure has gone on, for a reminder and for ``"up"``.
    """

    status: str
    since: datetime
    alert: str | None = None
    lasted: timedelta | None = None


# --------------------------------------------------------------------------
# The rule
# --------------------------------------------------------------------------


def decide(previous: Previous | None, result: Result, now: datetime) -> Step:
    """The alert rule, as one function of what was stored and what was seen.

    - A check that starts failing is told once («down»), and again every
      :data:`REMINDER_EVERY` while it stays failing («reminder»).
    - One that comes back after the owner was told is told once («up»), with
      how long it was down.
    - ``unknown`` is told nothing, and it neither starts nor ends a failure:
      ``since`` stays, so a failure that went unknown and then came back is
      reported whole, and one that went unknown and then failed again is not
      reported twice.
    - ``last_alert_at`` is what the owner was told: set, they believe the
      check is failing. A failure the owner was never told of (its alert did
      not get through, and the next tick retries it) is not followed by an
      «up».
    """
    if previous is None:
        return Step(result.status, now, "down" if result.status == FAILING else None)

    told = previous.last_alert_at
    was_failing = previous.status == FAILING or (previous.status == UNKNOWN and told is not None)

    if result.status == UNKNOWN:
        return Step(UNKNOWN, previous.since)

    if result.status == FAILING:
        since = previous.since if was_failing else now
        if told is None:
            return Step(FAILING, since, "down")
        if now - told >= REMINDER_EVERY:
            return Step(FAILING, since, "reminder", now - since)
        return Step(FAILING, since)

    since = now if was_failing else previous.since
    if told is not None:
        return Step(OK, since, "up", now - previous.since)
    return Step(OK, since)


def message(name: str, step: Step, reason: str) -> str:
    """The owner's message for ``step``, or ``""`` when there is none.

    HTML, as the bot sends everything: the reason is built from facts by the
    check, and escaped all the same, because everything that goes into a
    message is.
    """
    down, back = WORDS[name]
    minutes = max(1, round(step.lasted.total_seconds() / 60)) if step.lasted else 0
    if step.alert == "up":
        return f"🟢 {back}, простой {duration(minutes)}"
    if step.alert == "reminder":
        text = f"🔴 {down} — уже {duration(minutes)}"
    elif step.alert == "down":
        text = f"🔴 {down}"
    else:
        return ""
    if reason:
        text += f"\n<code>{escape(cut(reason, REASON_MAX))}</code>"
    return text


# --------------------------------------------------------------------------
# The checks
# --------------------------------------------------------------------------


async def check_schema(session: AsyncSession) -> Result:
    """The same reading ``/api/v1/warmup`` makes."""
    revision = await current_revision(session)
    if revision is None:
        return Result(UNKNOWN, "no alembic_version table to read")
    if revision == EXPECTED_REVISION:
        return Result(OK, f"at {revision}")
    return Result(FAILING, f"database at {revision}, code at {EXPECTED_REVISION}")


def check_v2(mounted: bool) -> Result:
    """``main.mount_v2``'s own record of whether v2 loaded."""
    if mounted:
        return Result(OK, "mounted")
    return Result(FAILING, "not loaded: /api/v2 and /api/rpc answer 503")


def _proxy_client(proxy: str, timeout: float) -> httpx.AsyncClient:
    """A client of its own rather than the diary's shared one, whose pool a
    probe must not hold and whose timeouts are a diary call's. The seam the
    tests replace."""
    return httpx.AsyncClient(proxy=proxy, timeout=timeout, follow_redirects=False)


async def check_diary_proxy(
    settings: Settings, *, timeout: float = CHECK_TIMEOUT_SECONDS
) -> Result:
    """One ``HEAD`` of the diary's front page through the proxy.

    Any HTTP status is ``ok``: it means the tunnel and the diary both
    answered, which is all this asks. Only when the setting is set; empty is
    a route, not a fault (``config.py``).
    """
    if not settings.diary_proxy_url.strip():
        return Result(UNKNOWN, "DIARY_PROXY_URL is empty: the diary is called directly")
    proxy = settings.diary_proxy
    if proxy is None:
        return Result(UNKNOWN, "DIARY_PROXY_URL is unusable: the diary is called directly")
    loop = asyncio.get_running_loop()
    started = loop.time()
    try:
        async with _proxy_client(proxy, timeout) as client:
            response = await asyncio.wait_for(client.head(f"{DIARY_ORIGIN}/"), timeout)
    except (httpx.HTTPError, TimeoutError) as failure:
        # The exception's type and never its text: httpx's message for a
        # refused tunnel can carry the proxy's address, which carries its
        # password.
        return Result(FAILING, f"no answer through the proxy: {type(failure).__name__}")
    round_trip = round((loop.time() - started) * 1000)
    return Result(OK, f"HTTP {response.status_code}", round_trip_ms=round_trip)


class _Unreadable(Exception):
    """GitHub answered, and not with ``main``'s head."""


@dataclass(frozen=True)
class _MainHead:
    repository: str
    etag: str
    sha: str
    #: When ``main`` moved to ``sha``: the commit's committer date, which for
    #: a merge made on GitHub is the moment of the merge. Naive UTC.
    moved_at: datetime


#: ``main``'s head as GitHub last told this process, and the ``ETag`` to ask
#: again with. In memory rather than in a row: a warm instance asks with it
#: and an unchanged answer is a ``304``; a cold one asks without it and pays
#: one request of the sixty an hour GitHub allows an address.
_main_head: _MainHead | None = None


def _github_client(timeout: float) -> httpx.AsyncClient:
    """The seam the tests replace."""
    return httpx.AsyncClient(
        base_url=GITHUB_API,
        timeout=timeout,
        follow_redirects=False,
        headers={
            "accept": "application/vnd.github+json",
            "user-agent": "lessons-health-check",
            "x-github-api-version": "2022-11-28",
        },
    )


async def _read_main(repository: str, timeout: float) -> _MainHead:
    global _main_head
    known = _main_head if _main_head is not None and _main_head.repository == repository else None
    headers = {"if-none-match": known.etag} if known is not None and known.etag else {}
    async with _github_client(timeout) as client:
        response = await client.get(f"/repos/{repository}/commits/main", headers=headers)
    if response.status_code == 304 and known is not None:
        return known
    if response.status_code != 200:
        # 403 and 429 are the rate limit, shared by every function on the
        # address: a refusal is GitHub's state, never production's.
        raise _Unreadable(f"GitHub answered HTTP {response.status_code}")
    body = response.json()
    moved = datetime.fromisoformat(str(body["commit"]["committer"]["date"]))
    head = _MainHead(
        repository=repository,
        etag=response.headers.get("etag", ""),
        sha=str(body["sha"]),
        moved_at=moved.astimezone(UTC).replace(tzinfo=None),
    )
    _main_head = head
    return head


async def check_deploy(
    settings: Settings, now: datetime, *, timeout: float = CHECK_TIMEOUT_SECONDS
) -> Result:
    """Is production running ``main``'s head, or is the deploy still within its grace?"""
    running = deployment()
    if not settings.behind_vercel:
        return Result(UNKNOWN, "not on Vercel")
    if running.environment != "production":
        return Result(UNKNOWN, f"not production: VERCEL_ENV is {running.environment or 'unset'}")
    if not running.commit or not running.repository:
        return Result(UNKNOWN, "Vercel's system environment variables are not exposed")
    try:
        head = await asyncio.wait_for(_read_main(running.repository, timeout), timeout)
    except _Unreadable as refused:
        return Result(UNKNOWN, str(refused))
    except (httpx.HTTPError, TimeoutError) as failure:
        return Result(UNKNOWN, f"GitHub did not answer: {type(failure).__name__}")
    except (KeyError, TypeError, ValueError):
        return Result(UNKNOWN, "GitHub's answer was not a commit")
    if head.sha == running.commit:
        return Result(OK, f"running main's head {head.sha[:7]}")
    behind = now - head.moved_at
    if behind < DEPLOY_GRACE:
        minutes = max(0, int(behind.total_seconds() // 60))
        return Result(OK, f"main moved to {head.sha[:7]} {minutes} min ago; the deploy has time")
    since = f"{head.moved_at:%Y-%m-%d %H:%M} UTC"
    return Result(FAILING, f"running {running.commit[:7]}, main is {head.sha[:7]} since {since}")


# --------------------------------------------------------------------------
# One run
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class _Alert:
    name: str
    kind: str
    text: str
    #: ``last_alert_at`` as this tick read it, and what telling the owner
    #: makes it: the claim is a compare-and-set from the one to the other.
    expected: datetime | None
    claimed: datetime | None


async def claim_alert(
    session: AsyncSession, name: str, expected: datetime | None, new: datetime | None
) -> bool:
    """Move ``last_alert_at`` from ``expected`` to ``new``, or report that
    something else already moved it. Commits.

    Two ticks overlap here as they do over the digests (``reminders.claim``):
    the external cron and the fallback workflow, or a tick killed by its
    caller's timeout and a retry. Both read the same row and both decide to
    tell the owner; the database lets exactly one of them.
    """
    column = HealthCheck.last_alert_at
    matches = column.is_(None) if expected is None else column == expected
    result = await session.execute(
        sa_update(HealthCheck).where(HealthCheck.name == name, matches).values(last_alert_at=new)
    )
    await session.commit()
    return rows_affected(result) == 1


async def _within(deadline: float, check: Callable[[float], Awaitable[Result]]) -> Result:
    """Run a check that goes to the network, inside what is left of the tick."""
    left = deadline - asyncio.get_running_loop().time()
    if left <= 0:
        return Result(UNKNOWN, "the tick ran out of time before this check")
    try:
        return await check(min(CHECK_TIMEOUT_SECONDS, left))
    except Exception as failure:  # noqa: BLE001 - a check that raises is a check that could not run
        log.exception("a health check raised")
        return Result(UNKNOWN, f"the check raised {type(failure).__name__}")


async def _results(
    session: AsyncSession, settings: Settings, *, v2_mounted: bool, deadline: float, now: datetime
) -> dict[str, Result]:
    try:
        schema = await check_schema(session)
    except Exception as failure:  # noqa: BLE001 - see _within
        # On Postgres a statement that raised leaves the transaction aborted,
        # and everything this run writes next would fail on it.
        await session.rollback()
        log.exception("a health check raised")
        schema = Result(UNKNOWN, f"the check raised {type(failure).__name__}")
    try:
        v2 = check_v2(v2_mounted)
    except Exception as failure:  # noqa: BLE001 - see _within
        log.exception("a health check raised")
        v2 = Result(UNKNOWN, f"the check raised {type(failure).__name__}")
    proxy, deploy = await asyncio.gather(
        _within(deadline, lambda timeout: check_diary_proxy(settings, timeout=timeout)),
        _within(deadline, lambda timeout: check_deploy(settings, now, timeout=timeout)),
    )
    return {"schema": schema, "v2": v2, "diary_proxy": proxy, "deploy": deploy}


async def run(
    session: AsyncSession,
    settings: Settings,
    *,
    v2_mounted: bool,
    started: float | None = None,
    send: Sender | None = None,
) -> dict[str, str]:
    """Run the checks, keep what each said, and tell the owner of a change.

    ``started`` is ``loop.time()`` at the start of the request, so the budget
    counts what the tick did first; omitted, it counts from here. ``send`` is
    ``telegram_send.send`` unless a test hands another. Returns each check's
    status.
    """
    loop = asyncio.get_running_loop()
    deadline = (started if started is not None else loop.time()) + HEALTH_HARD_STOP_SECONDS
    now = _now()
    results = await _results(session, settings, v2_mounted=v2_mounted, deadline=deadline, now=now)

    stored = {row.name: row for row in await session.scalars(select(HealthCheck))}
    alerts: list[_Alert] = []
    for name in CHECKS:
        result = results[name]
        row = stored.get(name)
        previous = None if row is None else Previous(row.status, row.since, row.last_alert_at)
        step = decide(previous, result, now)
        if row is None:
            row = HealthCheck(name=name)
            session.add(row)
        else:
            row.previous_checked_at = row.checked_at
        row.status = step.status
        row.reason = cut(result.reason, REASON_MAX)
        row.since = step.since
        row.checked_at = now
        row.round_trip_ms = result.round_trip_ms
        if result.status == FAILING:
            log.warning("health check %s is failing: %s", name, result.reason)
        if step.alert is not None:
            expected = None if previous is None else previous.last_alert_at
            alerts.append(
                _Alert(
                    name=name,
                    kind=step.alert,
                    text=message(name, step, result.reason),
                    expected=expected,
                    claimed=None if step.alert == "up" else now,
                )
            )
    await session.commit()

    owners = settings.owner_id_list
    if owners:
        for alert in alerts:
            await _tell(session, alert, owners, send or telegram_send.send, deadline)
    return {name: results[name].status for name in CHECKS}


async def _tell(
    session: AsyncSession, alert: _Alert, owners: list[int], send: Sender, deadline: float
) -> None:
    """Claim the alert, send it, and give the claim back if nobody got it.

    Given back so that the next tick tries again rather than six hours later;
    except «up», whose figure would be wrong by then, so a recovery that does
    not get through is logged and not repeated.
    """
    if not await claim_alert(session, alert.name, alert.expected, alert.claimed):
        return
    delivered: list[bool] = []
    left = deadline - asyncio.get_running_loop().time()
    if left > 0:
        try:
            delivered = await asyncio.wait_for(
                send([(owner, alert.text) for owner in owners]), min(SEND_TIMEOUT_SECONDS, left)
            )
        except Exception:  # noqa: BLE001 - a Telegram that refuses must not fail the tick
            log.warning("could not tell the owner of %s", alert.name, exc_info=True)
    if not any(delivered):
        log.warning("nobody was told that %s is %s", alert.name, alert.kind)
        if alert.kind != "up":
            await claim_alert(session, alert.name, alert.claimed, alert.expected)
```

- [ ] **Step 5: Green.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_health.py tests/test_bot_views.py tests/test_service_layering.py
```
Expected: all pass, `test_health.py` 33 of 33. `test_bot_views.py`'s `test_duration` reads the moved function through `week_render`, and the layering test walks `services/health.py` through `telegram_send`.

- [ ] **Step 6: Gates.** ruff prints `All checks passed!`. mypy prints `Success: no issues found in 223 source files`. `pytest -q -n auto`: 2662 passed (2629 + 33).

- [ ] **Step 7: Commit.** Write `$SCRATCH/commit-mon-t4.txt`:
```text
Check the schema, v2, the diary's proxy and the deploy, and decide whom to tell

services/health.py asks four questions. Is the database at the code's
revision; did v2 load; does a HEAD of the diary through DIARY_PROXY_URL
get any answer in five seconds; is production on main's head, or did
main move less than fifteen minutes ago. Each answer is ok, failing or
unknown, and unknown, which includes a check that does not apply here,
never alerts. GitHub is asked with an ETag held in the process, and its
refusals read as unknown, because sixty requests an hour are shared by
every function on the address. A reason is built from facts and an
exception's type, never from its text, which can carry the proxy's
password.

The rule is one function, decide. The owner is told once when a check
starts failing, every six hours while it stays failing, and once when it
is back, with how long it lasted. An unknown between two answers neither
starts nor ends a failure. run keeps what each said in health_checks,
claims an alert by a compare-and-set on last_alert_at, so two overlapping
ticks tell once, and gives a claim back when nobody got the message, so
the next tick tries again. Everything it does with the network stops 28
seconds into the request. config.deployment() reads Vercel's own
variables from the environment, never from .env, and wording.duration
moved out of the bot so that services/ can say how long.

Not covered: nothing runs it yet; the tick does in the next commit.
Nothing has asked GitHub, the proxy or Postgres for real.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/services/health.py server/app/config.py server/app/wording.py server/app/bot/week_render.py server/tests/test_health.py && git commit -F C:/Users/lumen/AppData/Local/Temp/monitoring-run/commit-mon-t4.txt
```

---

## Task 5: The tick ends with the self-check, in its own guard

Rulings 9 and 11. The tick answers as before, whatever the self-check meets.

**Files:**
- Create: `server/tests/test_health_tick.py`
- Modify: `server/app/api/cron.py`

**Interfaces:**
- Consumes: `health.run(session, settings, *, v2_mounted, started)` (Task 4); `app.state.v2_mounted`, which `main.mount_v2` sets.
- Produces: the tick's last step. `TickOut` is unchanged.

- [ ] **Step 1: Red.**

Create `server/tests/test_health_tick.py`:
```python
"""The tick ends with the self-check, and the self-check never fails the tick.

Driven through the real endpoint, as ``test_api_extended.py``'s tick tests
are, with the same two seams: the settings the tick reads and the bot it
builds for the digests. The self-check's own sender is ``telegram_send.send``,
replaced here by a recorder, so nothing reaches Telegram.
"""

from __future__ import annotations

from typing import Any

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import select

from app import (
    fsm_storage,  # noqa: F401 - registers fsm_states, which the tick sweeps
    telegram_send,
)
from app.api import cron
from app.config import Settings, get_settings
from app.main import app
from app.models import HealthCheck
from app.services import health

CRON_SECRET = "tick-secret"
HEADERS = {"X-Cron-Secret": CRON_SECRET}

#: What a tick with nothing due answers, as it did before the self-check.
QUIET_TICK = {
    "morning": 0,
    "evening": 0,
    "tasks": 0,
    "failed": 0,
    "fsm_purged": 0,
    "join_attempts_purged": 0,
    "diary_sessions_purged": 0,
    "device_tokens_purged": 0,
    "diary_links_purged": 0,
    "device_invites_purged": 0,
    "diary_sessions_kept_alive": 0,
    "diary_sessions_lost": 0,
    "diary_keepalive_failed": False,
}


class _Bot:
    """The digests' bot; nothing is due in these ticks, so it only closes."""

    async def send_message(self, chat_id: int, text: str, **_: Any) -> None:
        raise AssertionError("no digest is due")

    @property
    def session(self) -> _Bot:
        return self

    async def close(self) -> None:
        return None


@pytest.fixture
def ticking(monkeypatch) -> list[tuple[int, str]]:
    """A configured tick, and what the self-check sent through ``telegram_send``."""
    settings = Settings(
        bot_token="123456:TEST",
        cron_secret=CRON_SECRET,
        database_url=get_settings().database_url,
        run_bot=False,
    )
    monkeypatch.setattr(cron, "get_settings", lambda: settings)
    monkeypatch.setattr(cron, "_build_bot", _Bot)
    sent: list[tuple[int, str]] = []

    async def record(messages):
        sent.extend(messages)
        return [True] * len(messages)

    monkeypatch.setattr(telegram_send, "send", record)
    return sent


async def _tick() -> httpx.Response:
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.get("/api/v1/cron/tick", headers=HEADERS)


async def test_the_tick_ends_with_the_four_checks(session, ticking):
    response = await _tick()

    assert response.status_code == 200, response.text
    assert response.json() == QUIET_TICK
    rows = {row.name: row.status for row in await session.scalars(select(HealthCheck))}
    # The suite's database has no alembic_version, v2 loaded, no proxy is
    # configured, and this is not Vercel.
    assert rows == {"schema": "unknown", "v2": "ok", "diary_proxy": "unknown", "deploy": "unknown"}
    assert ticking == []


async def test_the_tick_tells_the_self_check_whether_v2_loaded(session, ticking, monkeypatch):
    monkeypatch.setattr(app.state, "v2_mounted", False)

    assert (await _tick()).status_code == 200
    assert (await _tick()).status_code == 200

    status = await session.scalar(select(HealthCheck.status).where(HealthCheck.name == "v2"))
    assert status == "failing"
    assert [recipient for recipient, _ in ticking] == [1000]
    assert ticking[0][1].startswith("🔴 v2 не загрузился")


async def test_the_tick_answers_as_before_when_every_check_raises(session, ticking, monkeypatch):
    async def broken(*args, **kwargs):
        raise RuntimeError("a bug")

    def broken_now(*args, **kwargs):
        raise RuntimeError("a bug")

    for name in ("check_schema", "check_diary_proxy", "check_deploy"):
        monkeypatch.setattr(health, name, broken)
    monkeypatch.setattr(health, "check_v2", broken_now)

    response = await _tick()

    assert response.status_code == 200, response.text
    assert response.json() == QUIET_TICK
    reasons = {row.name: row.reason for row in await session.scalars(select(HealthCheck))}
    assert set(reasons.values()) == {"the check raised RuntimeError"}


async def test_a_self_check_that_raises_whole_does_not_fail_the_tick(ticking, monkeypatch):
    async def broken(*args, **kwargs):
        raise RuntimeError("the self-check fell over")

    monkeypatch.setattr(health, "run", broken)

    response = await _tick()

    assert response.status_code == 200, response.text
    assert response.json() == QUIET_TICK
```

Run:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_health_tick.py
```
Expected: three fail and one passes. `test_the_tick_ends_with_the_four_checks` and `test_the_tick_tells_the_self_check_whether_v2_loaded` find no rows, and `test_the_tick_answers_as_before_when_every_check_raises` finds no reasons. `test_a_self_check_that_raises_whole_does_not_fail_the_tick` passes, because nothing calls `run` yet.

- [ ] **Step 2: The tick's last step.**

In `server/app/api/cron.py`, replace:
```python
from fastapi import APIRouter, Header, HTTPException, status
```
with:
```python
from fastapi import APIRouter, Header, HTTPException, Request, status
```

In `server/app/api/cron.py`, replace:
```python
from app.services import device_invites, diary_keepalive, diary_link, reminders
```
with:
```python
from app.services import device_invites, diary_keepalive, diary_link, health, reminders
```

In `server/app/api/cron.py`, replace:
```python
async def tick(
    x_cron_secret: str | None = Header(default=None),
```
with:
```python
async def tick(
    request: Request,
    x_cron_secret: str | None = Header(default=None),
```

In `server/app/api/cron.py`, replace:
```python
        keepalive_failed = True
        log.exception("diary keep-alive failed")
    return TickOut(
```
with:
```python
        keepalive_failed = True
        log.exception("diary keep-alive failed")
    # The self-check, after everything else and in its own guard and budget
    # for the keep-alive's reasons: a failing check, a slow GitHub or a
    # Telegram that refuses the owner's alert must not fail the tick that
    # reports it, nor cost the digests and sweeps above. Its answer is in
    # `health_checks` and the owner's chat, not in the tick's body, so the
    # caller's history reads as it always has.
    try:
        await health.run(
            session,
            settings,
            v2_mounted=bool(getattr(request.app.state, "v2_mounted", False)),
            started=started,
        )
    except Exception:  # noqa: BLE001 - see above
        log.exception("health checks failed")
    return TickOut(
```

- [ ] **Step 3: Green, with every tick test.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_health_tick.py tests/test_api_extended.py tests/test_join_modes.py tests/test_health.py
```
Expected: all pass, `test_health_tick.py` 4 of 4. `test_api_extended.py`'s tick tests are not edited, and `test_cron_tick_delivers_a_morning_digest_once` still reads the whole body as it did.

- [ ] **Step 4: Gates.** ruff prints `All checks passed!`. mypy prints `Success: no issues found in 223 source files`. `pytest -q -n auto`: 2666 passed (2662 + 4).

- [ ] **Step 5: Commit.** Write `$SCRATCH/commit-mon-t5.txt`:
```text
End every cron tick with the self-check, so a broken deployment says so

The tick runs health.run after the digests, the sweeps and the diary
keep-alive, and inside a guard of its own, as the keep-alive is: a
failing check, a GitHub that does not answer or a Telegram that refuses
the owner's alert costs the tick nothing, and its answer is unchanged, so
cron-job.org's history reads as before. The self-check's budget counts
from the start of the request, like the keep-alive's. Whether v2 loaded
is the app's own record, which mount_v2 keeps.

Not covered: the self-check has not run in production; the post-merge
read does that.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/api/cron.py server/tests/test_health_tick.py && git commit -F C:/Users/lumen/AppData/Local/Temp/monitoring-run/commit-mon-t5.txt
```

---

## Task 6: Sentry, started only where `SENTRY_DSN` is set, and scrubbed

Rulings 8, 14, 15 and 16. 152-ФЗ is the reason for the whitelist: what may leave is listed, and everything else stays.

**Files:**
- Create: `server/app/observability.py`, `server/tests/test_observability.py`
- Modify: `server/app/config.py`, `server/app/main.py`, `server/app/bot/bot.py`, `server/pyproject.toml`, `requirements.in`, `requirements.txt` (regenerated), `server/.env.example`, `docker-compose.yml`, `server/tests/conftest.py`, `server/tests/test_cold_start.py`, `server/tests/test_deployment_config.py`

**Interfaces:**
- Consumes: `config.deployment()` (Task 4).
- Produces:
  - `Settings.sentry_dsn: str` and `Settings.sentry_configured: bool`;
  - `observability.init(settings, *, transport=None, traces_sample_rate=0.05) -> None`;
  - `observability.capture(error) -> None`;
  - `observability.scrub(event, hint=None) -> dict` and `observability.scrub_transaction(event, hint=None) -> dict`;
  - `observability.TRACES_SAMPLE_RATE` and `observability.UNMATCHED`.

- [ ] **Step 1: The dependency, in both lists, and the lock regenerated.**

In `server/pyproject.toml`, replace:
```toml
    "connectrpc>=0.12.1",
    "protobuf-py>=0.6.0",
```
with:
```toml
    "connectrpc>=0.12.1",
    "protobuf-py>=0.6.0",
    # Sentry (docs/specs/2026-10-05-monitoring-design.md, part 3): each error
    # with its place, and a 5 % sample of request timings. Imported only where
    # SENTRY_DSN is set (app/observability.py), so a deployment without it pays
    # nothing on its cold start. No extra: the FastAPI integration is in the
    # package, and the `fastapi` extra would only ask for FastAPI, which is
    # above. The floor is the release that was current when this was written.
    "sentry-sdk>=2.71.0",
```

In `requirements.in`, replace:
```text
connectrpc>=0.12.1
protobuf-py>=0.6.0
```
with:
```text
connectrpc>=0.12.1
protobuf-py>=0.6.0
# Errors and a sample of timings, to Sentry; imported only where SENTRY_DSN
# is set, so a deployment without it pays nothing on its cold start.
sentry-sdk>=2.71.0
```

Regenerate the lock with the command in its header, from the repository root, and install what it says:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && /c/Users/lumen/.local/bin/uv.exe pip compile requirements.in --python-version 3.12 --python-platform linux --output-file requirements.txt && git diff --stat requirements.txt
```
Expected: `requirements.txt` gains `sentry-sdk==2.71.0` (via `-r requirements.in`) and `urllib3==2.8.0` (via `sentry-sdk`), and `certifi`'s «via» list gains `sentry-sdk`. Nothing else moves: without `--upgrade`, uv keeps every pin the edit does not force. Then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m pip install -q -r ../requirements.txt -e ".[dev]"
```

- [ ] **Step 2: Red.**

Create `server/tests/test_observability.py`:
```python
"""What leaves for Sentry, and what never does (152-ФЗ).

Two halves. The scrubbing hooks are asked directly, with an event carrying
everything the SDK could ever put in one. And one event is built for real: a
fresh interpreter starts Sentry exactly as ``app.main`` does, sends a request
carrying a diary token and a child's name through a route that fails, and
hands back what the SDK would have put on the wire. Fresh, because Sentry's
integrations patch Starlette for the life of a process, and this suite's
process serves a thousand other requests.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

from app import observability
from app.config import Settings, get_settings

SERVER = Path(__file__).resolve().parents[1]

TOKEN = "dt_9f3b2c7e1a5d4f6b8c0e2a4d6f8b0c1e"
CHILD = "Иванова Мария"
FAKE_DSN = "https://public@o0.ingest.de.sentry.io/0"
ROUTE = "/api/v1/diary/students/{student_id}/schedule"


def _everything() -> dict:
    """An error event as the SDK builds one, with every field that could
    carry the token or the name filled with them."""
    return {
        "event_id": "e" * 32,
        "timestamp": "2026-10-06T12:00:00Z",
        "level": "error",
        "platform": "python",
        "sdk": {"name": "sentry.python", "version": "2.71.0"},
        "release": "a" * 40,
        "environment": "production",
        "server_name": "ip-10-0-0-1",
        "transaction": ROUTE,
        "transaction_info": {"source": "route"},
        "message": f"failed for {CHILD}",
        "logentry": {"message": "diary read for %s", "params": [CHILD]},
        "exception": {
            "values": [
                {
                    "type": "ValueError",
                    "module": None,
                    "value": f"no lessons for {CHILD}",
                    "mechanism": {"type": "starlette", "handled": False, "data": {"t": TOKEN}},
                    "stacktrace": {
                        "frames": [
                            {
                                "filename": "app/api/diary.py",
                                "abs_path": f"/home/{CHILD}/app/api/diary.py",
                                "function": "schedule",
                                "module": "app.api.diary",
                                "lineno": 120,
                                "in_app": True,
                                "context_line": f'    name = "{CHILD}"',
                                "pre_context": [TOKEN],
                                "vars": {"token": TOKEN, "name": CHILD},
                            }
                        ]
                    },
                }
            ]
        },
        "request": {
            "url": f"https://lessons.example/api/v1/diary/students/42/schedule?child={CHILD}",
            "method": "GET",
            "headers": {"authorization": f"Bearer {TOKEN}"},
            "cookies": {"session": TOKEN},
            "query_string": f"child={CHILD}",
            "data": {"name": CHILD},
        },
        "breadcrumbs": {"values": [{"message": f"signed in as {CHILD}"}]},
        "extra": {"token": TOKEN},
        "user": {"id": "42", "username": CHILD},
        "tags": {"child": CHILD},
        "modules": {"fastapi": "0.141.1"},
        "contexts": {
            "trace": {
                "trace_id": "1" * 32,
                "span_id": "2" * 16,
                "op": "http.server",
                "status": "internal_error",
                "data": {"url": f"/schedule?child={CHILD}"},
                "dynamic_sampling_context": {"transaction": ROUTE},
            },
            "runtime": {"name": "CPython", "version": "3.12"},
        },
    }


def _leaks(event: dict) -> list[str]:
    dumped = json.dumps(event, ensure_ascii=False)
    return [secret for secret in (TOKEN, CHILD, "Иванова", "Мария") if secret in dumped]


def test_an_error_keeps_its_type_its_place_and_its_route_and_nothing_else():
    scrubbed = observability.scrub(_everything())

    assert _leaks(scrubbed) == []
    assert set(scrubbed) == {
        "event_id", "timestamp", "level", "platform", "sdk", "release", "environment",
        "transaction", "transaction_info", "exception", "contexts",
    }
    (value,) = scrubbed["exception"]["values"]
    assert value["type"] == "ValueError" and "value" not in value
    assert value["stacktrace"]["frames"] == [
        {
            "filename": "app/api/diary.py",
            "function": "schedule",
            "module": "app.api.diary",
            "lineno": 120,
            "in_app": True,
        }
    ]
    assert value["mechanism"] == {"type": "starlette", "handled": False}
    assert scrubbed["transaction"] == ROUTE
    assert scrubbed["contexts"] == {
        "trace": {
            "trace_id": "1" * 32,
            "span_id": "2" * 16,
            "op": "http.server",
            "status": "internal_error",
        }
    }


def test_a_timed_request_keeps_its_route_and_its_duration_and_nothing_else():
    timed = {
        **_everything(),
        "type": "transaction",
        "start_timestamp": "2026-10-06T11:59:59Z",
        "spans": [
            {"op": "http.client", "description": f"GET https://dnevnik2/?child={CHILD}"},
            {"op": "db", "description": "SELECT * FROM diary_sessions WHERE token_hash = ?"},
        ],
        "measurements": {"x": {"value": 1}},
    }
    scrubbed = observability.scrub_transaction(timed)

    assert _leaks(scrubbed) == []
    assert scrubbed["spans"] == []
    assert (scrubbed["transaction"], scrubbed["start_timestamp"], scrubbed["timestamp"]) == (
        ROUTE, "2026-10-06T11:59:59Z", "2026-10-06T12:00:00Z",
    )
    assert "request" not in scrubbed and "measurements" not in scrubbed


def test_a_path_no_route_matched_is_never_the_name():
    """A path the router did not match is whatever the caller sent: a
    calendar feed's carries its secret. A Connect method's names a service
    and a method, and nothing a caller could hide a value in."""

    def named(transaction: str) -> str:
        event = {"transaction": transaction, "transaction_info": {"source": "url"}}
        return observability.scrub(event)["transaction"]

    assert named(f"http://test/api/v1/calendar/{TOKEN}.ics") == observability.UNMATCHED
    assert named(f"http://test/api/rpc/lessons.v2.X/{CHILD}") == observability.UNMATCHED
    assert (
        named("http://test:None/api/rpc/lessons.v2.SubjectService/ListSubjects")
        == "/api/rpc/lessons.v2.SubjectService/ListSubjects"
    )


async def test_a_bot_error_reaches_sentry_only_where_it_is_set(monkeypatch):
    from app.bot import bot as bot_module

    captured: list[BaseException] = []
    monkeypatch.setattr(observability, "capture", captured.append)
    failure = RuntimeError(f"a handler failed for {CHILD}")
    event = SimpleNamespace(update=SimpleNamespace(callback_query=None), exception=failure)

    monkeypatch.setattr(get_settings(), "sentry_dsn", "")
    await bot_module._on_error(event)
    assert captured == []

    monkeypatch.setattr(get_settings(), "sentry_dsn", FAKE_DSN)
    await bot_module._on_error(event)
    assert captured == [failure]


def test_the_sample_of_timings_is_the_owner_s_five_percent():
    assert observability.TRACES_SAMPLE_RATE == 0.05
    assert Settings(SENTRY_DSN=f" {FAKE_DSN} ").sentry_configured
    assert not Settings(SENTRY_DSN="  ").sentry_configured


#: Starts Sentry as ``app.main`` does, through a transport that keeps what it
#: is handed, and fails one request that carries a diary token in its header
#: and a child's name in its query, its body, its exception and a local.
_REQUEST = """
import asyncio, json, logging
import httpx
from fastapi import FastAPI
from sentry_sdk.transport import Transport

from app import observability
from app.config import Settings

TOKEN, CHILD, ROUTE = {token!r}, {child!r}, {route!r}
caught = []


class Keep(Transport):
    def capture_envelope(self, envelope):
        for item in envelope.items:
            if item.payload.json is not None:
                caught.append(item.payload.json)


observability.init(Settings(SENTRY_DSN={dsn!r}), transport=Keep(), traces_sample_rate=1.0)
app = FastAPI()


@app.post(ROUTE)
async def schedule(student_id: int, child: str = ""):
    diary_token = TOKEN
    logging.getLogger("app").error("reading the diary of %s", child)
    raise ValueError(f"no lessons for {{child}} with {{diary_token}}")


async def main():
    transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        answer = await client.post(
            "/api/v1/diary/students/42/schedule",
            params={{"child": CHILD}},
            json={{"name": CHILD}},
            headers={{"Authorization": f"Bearer {{TOKEN}}", "Cookie": f"s={{TOKEN}}"}},
        )
    assert answer.status_code == 500


asyncio.run(main())
import sentry_sdk
sentry_sdk.flush()
print(json.dumps(caught, ensure_ascii=False, default=str))
"""


def test_an_event_built_from_a_request_leaves_with_neither_the_token_nor_the_child_s_name():
    script = _REQUEST.format(token=TOKEN, child=CHILD, route=ROUTE, dsn=FAKE_DSN)
    result = subprocess.run(
        [sys.executable, "-c", script],
        cwd=str(SERVER),
        env={**os.environ, "SENTRY_DSN": FAKE_DSN, "PYTHONIOENCODING": "utf-8"},
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=120,
    )
    assert result.returncode == 0, result.stderr
    sent = json.loads(result.stdout.strip().splitlines()[-1])

    errors = [event for event in sent if event.get("type") != "transaction"]
    timings = [event for event in sent if event.get("type") == "transaction"]
    assert len(errors) == 1 and len(timings) == 1
    assert _leaks(sent) == []
    (value,) = errors[0]["exception"]["values"]
    assert value["type"] == "ValueError"
    assert any(frame.get("function") == "schedule" for frame in value["stacktrace"]["frames"])
    assert errors[0]["transaction"] == timings[0]["transaction"] == ROUTE
    assert timings[0]["spans"] == []
```

In `server/tests/conftest.py`, replace:
```python
os.environ["DIARY_SECRET"] = "test-secret-not-a-real-one-0123456789abcdef"
```
with:
```python
os.environ["DIARY_SECRET"] = "test-secret-not-a-real-one-0123456789abcdef"
# Whatever the shell holds: the suite reports to nobody's Sentry. A test that
# wants it on starts it in a fresh interpreter (test_observability.py).
os.environ["SENTRY_DSN"] = ""
```

In `server/tests/test_cold_start.py`, replace:
```python
loaded = sorted(name for name in sys.modules if name.partition(".")[0] == "aiogram")
```
with:
```python
loaded = sorted(name for name in sys.modules if name.partition(".")[0] == "aiogram")
sentry = sorted(name for name in sys.modules if name.partition(".")[0] == "sentry_sdk")
```

In `server/tests/test_cold_start.py`, replace:
```python
            "aiogram": loaded,
            "holders": holders,
```
with:
```python
            "aiogram": loaded,
            "sentry": sentry,
            "holders": holders,
```

In `server/tests/test_cold_start.py`, replace:
```python
_LOCAL = {"BOT_TOKEN": "", "WEBHOOK_SECRET": "", "RUN_BOT": "false"}
```
with:
```python
_LOCAL = {"BOT_TOKEN": "", "WEBHOOK_SECRET": "", "RUN_BOT": "false", "SENTRY_DSN": ""}
```

In `server/tests/test_cold_start.py`, replace:
```python
    "OWNER_IDS": "1000",
    "TIMEZONE": "Europe/Moscow",
}
```
with:
```python
    "OWNER_IDS": "1000",
    "TIMEZONE": "Europe/Moscow",
    "SENTRY_DSN": "",
}
```

Append to `server/tests/test_cold_start.py`:
```python


@pytest.mark.parametrize("settings", [_LOCAL, _VERCEL], ids=["webhook-unmounted", "vercel"])
def test_without_a_dsn_the_api_never_imports_sentry(settings):
    """The monitoring design's promise: a deployment without ``SENTRY_DSN``
    starts as it did before Sentry was a dependency."""
    found = _import_in_a_fresh_interpreter("app.main", settings)

    assert found["sentry"] == [], (
        f"importing app.main without SENTRY_DSN loaded {len(found['sentry'])} sentry_sdk modules"
    )


def test_with_a_dsn_the_api_starts_sentry_and_still_leaves_aiogram_out():
    """And the other side, which is what proves the probe can see it: with
    the setting, Sentry is started at import. Its integrations are Starlette's
    and FastAPI's alone, so it brings no aiogram along either."""
    dsn = "https://public@o0.ingest.de.sentry.io/0"
    found = _import_in_a_fresh_interpreter(
        "app.main", {**_VERCEL, "SENTRY_DSN": dsn, "VERCEL_ENV": "production"}
    )

    assert "sentry_sdk" in found["sentry"]
    assert found["aiogram"] == []
```

In `server/tests/test_deployment_config.py`, replace:
```python
        ("CRON_SECRET", "CRON_SECRET"),
    ],
)
```
with:
```python
        ("CRON_SECRET", "CRON_SECRET"),
        ("SENTRY_DSN", "SENTRY_DSN"),
    ],
)
```

In `server/tests/test_deployment_config.py`, replace:
```python
        CRON_SECRET="k",
    )
    assert settings.disabled_features() == []
```
with:
```python
        CRON_SECRET="k",
        SENTRY_DSN="https://public@o0.ingest.de.sentry.io/0",
    )
    assert settings.disabled_features() == []
```
That one edit to an existing test is the fully configured deployment growing by the new setting, which is what the test describes.

Run:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_observability.py
```
Expected: collection fails on `from app import observability`. Then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_cold_start.py tests/test_deployment_config.py
```
Expected: 2 failed, 34 passed. The two are `test_with_a_dsn_the_api_starts_sentry_and_still_leaves_aiogram_out`, because nothing starts Sentry yet, and the `SENTRY_DSN` case of `test_an_optional_setting_is_announced_rather_than_fatal`, because nothing announces it. The two cold-start tests without a DSN pass already.

- [ ] **Step 3: The setting.**

In `server/app/config.py`, replace:
```python
    public_base_url: str = ""

```
with:
```python
    public_base_url: str = ""

    # The DSN of a Sentry project, for errors with their place and a sample of
    # timings (app/observability.py). Empty sends nothing and imports nothing
    # of Sentry, and the startup log says it is off; it is never a reason to
    # refuse to start. It names a project anybody could send events to, so it
    # lives where the other secrets do.
    sentry_dsn: str = ""

```

In `server/app/config.py`, replace:
```python
    def disabled_features(self) -> list[str]:
```
with:
```python
    @property
    def sentry_configured(self) -> bool:
        """Whether errors go to Sentry - the question ``app.main`` and the
        bot's error handler ask before they import anything of it."""
        return bool(self.sentry_dsn.strip())

    def disabled_features(self) -> list[str]:
```

In `server/app/config.py`, replace:
```python
        if not self.cron_secret:
            off.append("CRON_SECRET is empty: the tick answers 404, so no digest is ever sent")
        return off
```
with:
```python
        if not self.cron_secret:
            off.append("CRON_SECRET is empty: the tick answers 404, so no digest is ever sent")
        if not self.sentry_configured:
            off.append("SENTRY_DSN is empty: errors and request timings are not sent to Sentry")
        return off
```

- [ ] **Step 4: Sentry's start and its hooks.**

Create `server/app/observability.py`:
```python
"""Sentry: each error with its place, a sample of timings, and nothing a person wrote.

Imported only where ``SENTRY_DSN`` is set. ``app.main`` and the bot's error
handler ask the setting before they import this module, so a deployment
without it never loads ``sentry_sdk`` and its cold start is what it was
(``tests/test_cold_start.py``). Deleting the setting turns all of it off with
no change to the code.

What leaves, for each error: the exception's type and its stack - file,
function, line - the route template (the path as the router declares it,
``{student_id}`` and never the number), the release (the commit) and the
environment. For 5 % of requests, chosen at random: the route template and
how long the request took. Those are Sentry's request graphs.

What never leaves, because it can carry a child's name, a family's credential
or what somebody typed (152-ФЗ): request and response bodies; headers, cookies
and query strings; the exception's message; local variables; source lines;
log lines and breadcrumbs; the spans inside a request, whose descriptions are
a diary URL with its query or an SQL statement. The SDK is told not to
collect most of it, and :func:`scrub` and :func:`scrub_transaction` then
rebuild every event from a list of what may stay, so a field the SDK starts
sending in a later release stays here unless somebody adds it to that list.
``tests/test_observability.py`` is the proof.
"""

from __future__ import annotations

import re
from typing import Any
from urllib.parse import urlsplit

import sentry_sdk
from sentry_sdk.integrations.fastapi import FastApiIntegration
from sentry_sdk.integrations.starlette import StarletteIntegration

from app.config import Settings, deployment

#: The share of requests whose timing is sent: well inside the free tier at
#: this project's traffic, and enough to draw the graphs (the design's
#: question 3, answered by the owner).
TRACES_SAMPLE_RATE = 0.05

#: What a request no route template matched is called: its path is whatever
#: the caller sent, and a calendar feed's path carries its secret.
UNMATCHED = "unmatched route"

#: A Connect method's path names a service and a method and nothing else, so
#: it may stand for a route; anything else under the mount may not.
_CONNECT_METHOD = re.compile(r"/api/rpc/lessons\.v2\.[A-Za-z]+/[A-Za-z]+")

_EVENT_KEYS = ("event_id", "timestamp", "level", "platform", "sdk", "release", "environment")
_TRANSACTION_KEYS = (
    "event_id",
    "type",
    "timestamp",
    "start_timestamp",
    "platform",
    "sdk",
    "release",
    "environment",
)
_FRAME_KEYS = ("filename", "function", "module", "lineno", "in_app")
_TRACE_KEYS = ("trace_id", "span_id", "parent_span_id", "op", "status")


def init(
    settings: Settings,
    *,
    transport: Any = None,
    traces_sample_rate: float = TRACES_SAMPLE_RATE,
) -> None:
    """Start Sentry for this process. ``transport`` and the rate are for the tests."""
    running = deployment()
    sentry_sdk.init(
        dsn=settings.sentry_dsn.strip(),
        # Vercel's own name for the environment, so a preview's errors stay
        # apart from production's.
        environment=running.environment or "self-hosted",
        release=running.commit or None,
        transport=transport,
        # The two integrations a request needs and nothing else: the default
        # set would add log lines, the process's arguments and its modules,
        # and the automatic set would patch httpx and aiohttp to put
        # Sentry's headers on the diary's and Telegram's requests.
        default_integrations=False,
        auto_enabling_integrations=False,
        integrations=[
            StarletteIntegration(transaction_style="url"),
            FastApiIntegration(transaction_style="url"),
        ],
        traces_sample_rate=traces_sample_rate,
        send_default_pii=False,
        include_local_variables=False,
        include_source_context=False,
        max_breadcrumbs=0,
        max_request_body_size="never",
        trace_propagation_targets=[],
        before_send=scrub,
        before_send_transaction=scrub_transaction,
    )


def capture(error: BaseException) -> None:
    """Send one error the code caught itself: the bot's, which the webhook
    answers 200 for whatever happened, so no integration ever sees it."""
    sentry_sdk.capture_exception(error)


def scrub(event: dict[str, Any], hint: Any = None) -> dict[str, Any]:
    """An error event, rebuilt from what may leave and nothing else."""
    kept = {key: event[key] for key in _EVENT_KEYS if key in event}
    kept.update(_route(event))
    values = (event.get("exception") or {}).get("values") or []
    if values:
        kept["exception"] = {"values": [_exception(value) for value in values]}
    trace = _trace(event)
    if trace:
        kept["contexts"] = {"trace": trace}
    return kept


def scrub_transaction(event: dict[str, Any], hint: Any = None) -> dict[str, Any]:
    """A timed request, rebuilt the same way: its route and its duration."""
    kept = {key: event[key] for key in _TRANSACTION_KEYS if key in event}
    kept.update(_route(event))
    trace = _trace(event)
    if trace:
        kept["contexts"] = {"trace": trace}
    kept["spans"] = []
    return kept


def _route(event: dict[str, Any]) -> dict[str, Any]:
    name = event.get("transaction")
    if not name:
        return {}
    source = (event.get("transaction_info") or {}).get("source")
    if getattr(source, "value", source) == "route":
        return {"transaction": name, "transaction_info": {"source": "route"}}
    path = urlsplit(str(name)).path
    if _CONNECT_METHOD.fullmatch(path):
        return {"transaction": path, "transaction_info": {"source": "route"}}
    return {"transaction": UNMATCHED, "transaction_info": {"source": "custom"}}


def _exception(value: dict[str, Any]) -> dict[str, Any]:
    kept: dict[str, Any] = {"type": value.get("type"), "module": value.get("module")}
    mechanism = value.get("mechanism") or {}
    if mechanism:
        kept["mechanism"] = {key: mechanism[key] for key in ("type", "handled") if key in mechanism}
    frames = (value.get("stacktrace") or {}).get("frames") or []
    if frames:
        kept["stacktrace"] = {
            "frames": [{key: frame[key] for key in _FRAME_KEYS if key in frame} for frame in frames]
        }
    return kept


def _trace(event: dict[str, Any]) -> dict[str, Any]:
    trace = (event.get("contexts") or {}).get("trace") or {}
    return {key: trace[key] for key in _TRACE_KEYS if trace.get(key) is not None}
```

In `server/app/main.py`, replace:
```python
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger(__name__)
```
with:
```python
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger(__name__)

# Sentry, where SENTRY_DSN is set, and imported only there: a deployment
# without it never loads sentry_sdk (tests/test_cold_start.py). Before the app
# is built, so that its integration sees the first request. Not in the
# lifespan, which a serverless platform may never run.
if get_settings().sentry_configured:
    from app.observability import init as init_sentry

    init_sentry(get_settings())
```

In `server/app/bot/bot.py`, replace:
```python
from app.bot.middlewares import CommandBreakoutMiddleware, ContextMiddleware
from app.db import SessionLocal
```
with:
```python
from app.bot.middlewares import CommandBreakoutMiddleware, ContextMiddleware
from app.config import get_settings
from app.db import SessionLocal
```

In `server/app/bot/bot.py`, replace:
```python
    log.exception("Bot handler failed", exc_info=error)

```
with:
```python
    log.exception("Bot handler failed", exc_info=error)
    # Sentry sees a request's own failures through its integration, and never
    # these: the dispatcher catches them here, and the webhook answers 200
    # whatever happened. Imported only where it is set, as in `app.main`.
    if get_settings().sentry_configured:
        from app.observability import capture

        capture(error)

```

- [ ] **Step 5: Where the setting is written down.**

In `server/.env.example`, replace:
```text
# One variable is deliberately NOT here: VERCEL.
```
with:
```text
# --- Error tracking (optional) -----------------------------------------------
#
# The DSN of a Sentry project: sentry.io, data region EU, platform Python /
# FastAPI. Set, each unhandled error is sent with its type and its place -
# file, function, line - the route template, the commit and the environment,
# and 5 % of requests are timed by route. Never a body, a header, a cookie, a
# query string, an exception's message, a local variable or a log line
# (app/observability.py). Empty sends nothing and imports nothing of Sentry;
# the startup log says it is off.
#
# It names a project anybody could send events to, so it is a secret like the
# others: the environment of whatever runs the app, never the repository.
# SENTRY_DSN=

# One variable is deliberately NOT here: VERCEL.
```

In `server/.env.example`, replace:
```text
# accident the check is written to avoid causing.
```
with:
```text
# accident the check is written to avoid causing. Nor are VERCEL_ENV,
# VERCEL_GIT_COMMIT_SHA, VERCEL_GIT_REPO_OWNER, VERCEL_GIT_REPO_SLUG and
# VERCEL_REGION: Vercel states them about the running deployment, and the
# server reads them from its environment alone (app/config.py, deployment()).
```

In `docker-compose.yml`, replace:
```yaml
      PUBLIC_BASE_URL: ${PUBLIC_BASE_URL:-}
```
with:
```yaml
      PUBLIC_BASE_URL: ${PUBLIC_BASE_URL:-}
      # Empty sends nothing to Sentry, and imports nothing of it.
      SENTRY_DSN: ${SENTRY_DSN:-}
```

- [ ] **Step 6: Green.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_observability.py tests/test_cold_start.py tests/test_deployment_config.py tests/test_env_example.py tests/test_compose.py tests/test_requirements_mirror.py tests/test_bot_commands.py
```
Expected: all pass:
- `test_observability.py`, 6 of 6. Its last test starts Sentry in a fresh interpreter, fails a request carrying a diary token and a child's name, and finds neither in the one error and the one timing that would leave;
- `test_cold_start.py`, 6 of 6;
- `test_requirements_mirror.py` holds `pyproject.toml`, `requirements.in` and the lock level, and holds the lock against what is installed;
- `test_bot_commands.py` drives the bot's error handler through the real dispatcher, with Sentry off.

- [ ] **Step 7: Gates.** ruff prints `All checks passed!`. mypy prints `Success: no issues found in 224 source files`. `pytest -q -n auto`: 2676 passed (2666 + 10).

- [ ] **Step 8: Commit.** Write `$SCRATCH/commit-mon-t6.txt`:
```text
Send errors and a sample of timings to Sentry, and nothing a person wrote

Where SENTRY_DSN is set, app.main starts Sentry before the app is built,
with the Starlette and FastAPI integrations and nothing else: the default
set would send log lines and the process's modules, and the automatic set
would put Sentry's headers on the diary's and Telegram's requests. The
bot's error handler captures what the dispatcher catches, which no
integration sees. Each error leaves with its type, its stack (file,
function, line), its route template, the commit and Vercel's environment,
and 5 % of requests leave as a route and a duration. Two hooks rebuild
every event from a list of what may stay. A request no route matched is
named for no path, because a calendar feed's path carries its secret.
test_observability.py fails a real request carrying a diary token and a
child's name in a fresh interpreter, and finds neither in what would
leave (152-ФЗ).

Without the setting nothing of Sentry is imported, and
test_cold_start.py holds that; SENTRY_DSN is announced as off at startup
and is never a reason to refuse to start. sentry-sdk 2.71.0 is in both
lists, and the lock was regenerated by its own command, gaining it and
urllib3.

Not covered: no event has reached Sentry; the owner puts the DSN into
Vercel. v2's own INTERNAL answers are not captured, because invoke turns
the exception into a ConnectError first.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/observability.py server/app/config.py server/app/main.py server/app/bot/bot.py server/pyproject.toml requirements.in requirements.txt server/.env.example docker-compose.yml server/tests/conftest.py server/tests/test_observability.py server/tests/test_cold_start.py server/tests/test_deployment_config.py && git commit -F C:/Users/lumen/AppData/Local/Temp/monitoring-run/commit-mon-t6.txt
```

---

## Task 7: «📊 Проект» and `/health`, for the deployment's owner alone

Rulings 1, 2, 17 and 18. Every number is read, nothing is written, and the page fits one message.

**Files:**
- Create: `server/app/services/project_stats.py`, `server/app/bot/project_keyboard.py`, `server/app/bot/project_render.py`, `server/app/bot/handlers/project.py`, `server/tests/test_project_screen.py`
- Modify: `server/app/bot/handlers/__init__.py`, `server/app/bot/manage_keyboards/class_card.py`, `server/app/bot/handlers/manage/class_card.py`, `server/tests/test_bot_commands.py`

**Interfaces:**
- Consumes: `health.CHECKS`, `OK`, `FAILING`, `UNKNOWN` (Task 4); `config.STARTED_AT` and `deployment()` (Task 4); `HealthCheck` (Task 3); `roles.is_env_owner`; `wording.clamp`, `cut`, `duration`, `more_line`.
- Produces:
  - `project_stats.CheckRow`, `ProjectStats`, `checks(session)`, `postgres_numbers(session)` and `gather(session, settings, now=None)`;
  - `project_render.render_project(stats, zone)`, `render_health(checks, zone)`, `BUILDS_MAX` and `GRAPHS`;
  - `project_keyboard.ProjectAction` (prefix `prj`), `open_button()` and `project_keyboard()`;
  - `handlers.project.router` and `deployment_owner(event)`;
  - `class_menu(..., deployment_owner: bool = False)`.

- [ ] **Step 1: Red.**

Create `server/tests/test_project_screen.py`:
```python
"""«📊 Проект»: the deployment's numbers, read without writing, drawn within budget.

The screen's numbers are read from a database seeded with one of everything
they count, and the reading is watched for writes; the renderer is handed the
pathological input in one line - five hundred builds and four failing checks
with the longest reasons - and has to send; and everything that came from
outside is escaped. Who may see it is held through the real dispatcher, in
``test_bot_commands.py``, because aiogram attaches a router to one
dispatcher only.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from types import SimpleNamespace
from zoneinfo import ZoneInfo

from sqlalchemy.dialects import postgresql

from app.bot import project_render
from app.bot.manage_keyboards.class_card import class_menu
from app.bot.project_keyboard import ProjectAction
from app.config import Settings
from app.db import EXPECTED_REVISION
from app.models import (
    BotUser,
    DeviceToken,
    DiarySession,
    HealthCheck,
    ReminderSettings,
    Role,
    SchoolClass,
)
from app.services import project_stats
from app.services.project_stats import CheckRow, ProjectStats
from app.wording import MESSAGE_LIMIT

MOSCOW = ZoneInfo("Europe/Moscow")


def _now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


def _stats(**overrides) -> ProjectStats:
    now = _now()
    values = dict(
        now=now,
        checks=[],
        commit="a" * 40,
        environment="production",
        region="fra1",
        started_at=now - timedelta(minutes=12),
        python="3.12.13",
        revision=EXPECTED_REVISION,
        expected_revision=EXPECTED_REVISION,
        database_bytes=None,
        connections=None,
        last_tick=None,
        tick_before=None,
        mornings_today=0,
        evenings_today=0,
        classes=1,
        accounts=1,
        phones_day=0,
        phones_week=0,
        builds=[],
        diaries=[],
    )
    values.update(overrides)
    return ProjectStats(**values)


def _check(name: str, status: str, reason: str = "", **overrides) -> CheckRow:
    now = _now()
    values = dict(
        name=name,
        status=status,
        reason=reason,
        since=now - timedelta(hours=1),
        checked_at=now,
        previous_checked_at=now - timedelta(minutes=5),
        round_trip_ms=None,
    )
    values.update(overrides)
    return CheckRow(**values)


async def test_the_numbers_are_the_deployment_s_and_reading_them_writes_nothing(
    session, school_class, statement_writes
):
    now = _now()
    other = SchoolClass(name="5Б", join_code="OTHER5B1")
    session.add(other)
    await session.flush()
    session.add_all(
        [
            BotUser(telegram_id=2001, class_id=school_class.id, role=Role.ADMIN),
            BotUser(telegram_id=2001, class_id=other.id, role=Role.VIEWER),
            BotUser(telegram_id=2002, class_id=other.id, role=Role.EDITOR),
            DeviceToken(token_hash="a" * 64, class_id=school_class.id, client_version=412,
                        last_seen_at=now - timedelta(hours=2)),
            DeviceToken(token_hash="b" * 64, class_id=school_class.id, client_version=412,
                        last_seen_at=now - timedelta(days=3)),
            DeviceToken(token_hash="c" * 64, class_id=other.id, client_version=None,
                        last_seen_at=now - timedelta(days=30)),
            DeviceToken(token_hash="d" * 64, class_id=other.id, client_version=413,
                        revoked=True, last_seen_at=now),
            DiarySession(token_hash="e" * 64, upstream_token="x", login="a", provider=None),
            DiarySession(token_hash="f" * 64, upstream_token="x", login="b",
                         provider="netschool"),
            DiarySession(token_hash="g" * 64, upstream_token="x", login="c",
                         provider="netschool", expired_at=now),
            ReminderSettings(class_id=school_class.id, telegram_id=2001,
                             last_morning_sent=datetime.now(MOSCOW).date()),
            ReminderSettings(class_id=other.id, telegram_id=2002,
                             last_morning_sent=date(2026, 1, 1),
                             last_evening_sent=datetime.now(MOSCOW).date()),
            HealthCheck(name="v2", status="ok", reason="mounted", since=now - timedelta(days=1),
                        checked_at=now - timedelta(minutes=2),
                        previous_checked_at=now - timedelta(minutes=7)),
        ]
    )
    await session.commit()

    with statement_writes() as seen:
        stats = await project_stats.gather(session, Settings(), now)

    assert seen == []
    assert (stats.classes, stats.accounts) == (2, 2)
    assert (stats.phones_day, stats.phones_week) == (1, 2)
    assert stats.builds == [(412, 2), (None, 1)]
    assert stats.diaries == [("netschool", 1), ("petersburg", 1)]
    assert (stats.mornings_today, stats.evenings_today) == (1, 1)
    assert [check.name for check in stats.checks] == ["v2"]
    assert stats.last_tick == now - timedelta(minutes=2)
    assert stats.tick_before == now - timedelta(minutes=7)
    # SQLite: no alembic_version in the suite's database, and no catalogue.
    assert (stats.revision, stats.expected_revision) == (None, EXPECTED_REVISION)
    assert (stats.database_bytes, stats.connections) == (None, None)


async def test_on_postgres_the_size_and_the_connections_are_its_own_catalogue_s():
    """CI has no Postgres, so the two statements are read off a session that
    says it is one."""
    asked: list[str] = []

    class _Postgres:
        def get_bind(self):
            return SimpleNamespace(dialect=postgresql.dialect())

        async def scalar(self, statement):
            asked.append(str(statement))
            return 12_900_000 if "pg_database_size" in str(statement) else 3

    assert await project_stats.postgres_numbers(_Postgres()) == (12_900_000, 3)
    assert asked == [
        "SELECT pg_database_size(current_database())",
        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()",
    ]


def test_the_screen_shows_each_block():
    now = _now()
    stats = _stats(
        now=now,
        checks=[
            _check("schema", "ok"),
            _check("v2", "ok"),
            _check("diary_proxy", "ok", "HTTP 200", round_trip_ms=312),
            _check("deploy", "failing", "running aaaaaaa, main is bbbbbbb since 2026-10-06"),
        ],
        database_bytes=12_900_000,
        connections=3,
        last_tick=now - timedelta(minutes=2),
        tick_before=now - timedelta(minutes=7),
        mornings_today=12,
        evenings_today=8,
        builds=[(412, 15), (None, 4)],
        diaries=[("petersburg", 3)],
    )
    text = project_render.render_project(stats, MOSCOW)

    for line in (
        "<b>📊 Проект</b>",
        "Коммит: <code>aaaaaaa</code> · голова main: 🔴",
        "Регион: fra1 · Python 3.12.13",
        "Экземпляр жив: 12 мин",
        f"Схема: {EXPECTED_REVISION}, код ждёт {EXPECTED_REVISION}",
        "Размер: 12,3 МБ · соединений: 3",
        "Сводок сегодня: утренних 12, вечерних 8",
        "• сборка 412 — 15",
        "• без версии — 4",
        "Дневники: petersburg — 3",
        "v2: включён",
        project_render.GRAPHS,
    ):
        assert line in text.splitlines(), line
    assert "✅ отвечает · 312 мс · с " in text
    assert "(2 мин назад), до него: 5 мин" in text
    assert "https://" not in text


def test_a_check_never_run_says_so():
    text = project_render.render_health([_check("v2", "ok")], MOSCOW)
    assert text.splitlines()[0] == "<b>🩺 Состояние</b>"
    assert "❔ Схема базы — ещё не проверялось" in text
    assert "✅ v2 — с " in text
    assert len(text.splitlines()) == 1 + 4


def test_five_hundred_builds_and_four_long_failures_still_send():
    reason = "<" * 200
    stats = _stats(
        checks=[_check(name, "failing", reason) for name in project_render.CHECKS],
        builds=[(build, 1) for build in range(1000, 500, -1)],
        diaries=[(f"provider-{n}", n) for n in range(20)],
    )
    text = project_render.render_project(stats, MOSCOW)

    assert len(text) <= MESSAGE_LIMIT
    assert "… и ещё 490" in text
    assert text.count("• сборка") == project_render.BUILDS_MAX


def test_what_came_from_outside_is_escaped():
    stats = _stats(
        checks=[_check("diary_proxy", "failing", "no answer: <ProxyError> & co")],
        diaries=[("<b>bold</b>", 1)],
        region="fra1<script>",
        commit="<a href>",
    )
    text = project_render.render_project(stats, MOSCOW)

    assert "&lt;ProxyError&gt; &amp; co" in text
    assert "&lt;b&gt;bold&lt;/b&gt; — 1" in text
    assert "fra1&lt;script&gt;" in text
    assert "<ProxyError>" not in text and "<script>" not in text and "<b>bold" not in text


def test_the_class_card_opens_the_screen_for_the_deployment_s_owner_alone():
    def buttons(menu) -> list[str]:
        return [button.callback_data for row in menu.inline_keyboard for button in row]

    opened = ProjectAction(action="open").pack()
    owner = class_menu(is_owner=True, many_classes=False, pending=0, deployment_owner=True)
    class_owner = class_menu(is_owner=True, many_classes=False, pending=0)

    assert opened in buttons(owner)
    assert opened not in buttons(class_owner)
```

In `server/tests/test_bot_commands.py`, replace:
```python
from app.bot.middlewares import FORM_DROPPED, looks_like_command
```
with:
```python
from app.bot.middlewares import FORM_DROPPED, looks_like_command
from app.bot.project_keyboard import ProjectAction
```

Append to `server/tests/test_bot_commands.py`:
```python


# --------------------------------------------------------------------------
# «📊 Проект»: the deployment owner's screen, and an unknown command to anybody else
# --------------------------------------------------------------------------

#: OWNER_IDS in conftest: the deployment's owner, who is nobody's member here.
DEPLOYMENT_OWNER = 1000


def _message_from(user_id: int, text: str) -> Update:
    return Update(
        update_id=3,
        message=Message(
            message_id=1,
            date=datetime.now(UTC),
            chat=Chat(id=user_id, type="private"),
            from_user=TgUser(id=user_id, is_bot=False, first_name="Владелец"),
            text=text,
        ),
    )


def _press_from(user_id: int, data: str) -> Update:
    user = TgUser(id=user_id, is_bot=False, first_name="Владелец")
    return Update(
        update_id=4,
        callback_query=CallbackQuery(
            id="press-2",
            from_user=user,
            chat_instance="chat-instance",
            data=data,
            message=Message(
                message_id=7,
                date=datetime.now(UTC),
                chat=Chat(id=user_id, type="private"),
                from_user=user,
            ),
        ),
    )


@pytest.mark.parametrize("command", ["/project", "/health"])
async def test_the_project_is_an_unknown_command_to_a_class_s_own_owner(
    bot, sent, session, school_class, command
):
    """A class's owner is not the deployment's: the screen sums every class
    and shows the infrastructure. To them the command answers as any command
    the bot does not have answers, so nothing says the screen exists."""
    session.add(BotUser(telegram_id=USER_ID, class_id=school_class.id, role=Role.OWNER))
    await session.commit()

    await dispatcher().feed_update(bot, _message(command))

    assert sent.texts == [UNKNOWN_COMMAND]


async def test_the_deployment_s_owner_gets_the_project_and_its_state(bot, sent):
    await dispatcher().feed_update(bot, _message_from(DEPLOYMENT_OWNER, "/project"))
    await dispatcher().feed_update(bot, _message_from(DEPLOYMENT_OWNER, "/health"))

    project, state = sent.texts
    assert project.startswith("<b>📊 Проект</b>")
    assert "<b>🖥 Сервер</b>" in project
    assert state.startswith("<b>🩺 Состояние</b>")
    assert "<b>🖥 Сервер</b>" not in state


async def test_the_project_button_opens_for_the_deployment_s_owner_alone(bot, sent):
    press = ProjectAction(action="open").pack()

    await dispatcher().feed_update(bot, _press(press))
    answers = [m for m in sent.sent if type(m).__name__ == "AnswerCallbackQuery"]
    assert [a.text for a in answers] == [STALE_CARD]
    assert [m for m in sent.sent if type(m).__name__ == "EditMessageText"] == []

    await dispatcher().feed_update(bot, _press_from(DEPLOYMENT_OWNER, press))
    edits = [m for m in sent.sent if type(m).__name__ == "EditMessageText"]
    assert len(edits) == 1 and edits[0].text.startswith("<b>📊 Проект</b>")
```
These live in `test_bot_commands.py` because they go through the real dispatcher, and aiogram attaches a router to one dispatcher only: that module already holds the one this process builds.

Run:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_project_screen.py tests/test_bot_commands.py
```
Expected: both fail at collection, on `app.bot.project_render` and `app.bot.project_keyboard`.

- [ ] **Step 2: The numbers.**

Create `server/app/services/project_stats.py`:
```python
"""The numbers on the owner's «📊 Проект»: how the whole deployment stands.

Reads only - a handful of ``count(*)``, the self-check's rows and, on
Postgres, two catalogue queries - and nothing is written by looking. No
request is counted here: counting them in the database would add a write to
every request, which on serverless with Neon is real latency, and would break
«reads stop writing». The request graphs are Vercel's and Sentry's, which see
every request already (``docs/specs/2026-10-05-monitoring-design.md``).

Every number is the deployment's, every class's summed, which is why the
screen is the deployment owner's alone (``bot/handlers/project.py``).
"""

from __future__ import annotations

import platform
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy import text as sa_text
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import STARTED_AT, Settings, deployment
from app.db import EXPECTED_REVISION, current_revision
from app.models import (
    BotUser,
    DeviceToken,
    DiarySession,
    HealthCheck,
    ReminderSettings,
    SchoolClass,
)
from app.providers.diary.registry import PETERSBURG


@dataclass(frozen=True)
class CheckRow:
    """One self-check as the screen shows it."""

    name: str
    status: str
    reason: str
    since: datetime
    checked_at: datetime
    previous_checked_at: datetime | None
    round_trip_ms: int | None


@dataclass(frozen=True)
class ProjectStats:
    """Everything «📊 Проект» draws. Times are naive UTC, like every column."""

    now: datetime
    checks: list[CheckRow]
    # The server.
    commit: str
    environment: str
    region: str
    started_at: datetime
    python: str
    # The database.
    revision: str | None
    expected_revision: str
    database_bytes: int | None
    connections: int | None
    # The clock.
    last_tick: datetime | None
    tick_before: datetime | None
    mornings_today: int
    evenings_today: int
    # The project.
    classes: int
    accounts: int
    phones_day: int
    phones_week: int
    #: ``(build, phones)``, newest build first and the phones that never said
    #: one (every APK that speaks only v1) last, as ``None``.
    builds: list[tuple[int | None, int]]
    #: ``(provider, live sessions)``, the most first.
    diaries: list[tuple[str, int]]


def _now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


async def checks(session: AsyncSession) -> list[CheckRow]:
    """The self-check's rows, as the last tick left them."""
    rows = await session.scalars(select(HealthCheck).order_by(HealthCheck.name))
    return [
        CheckRow(
            name=row.name,
            status=row.status,
            reason=row.reason,
            since=row.since,
            checked_at=row.checked_at,
            previous_checked_at=row.previous_checked_at,
            round_trip_ms=row.round_trip_ms,
        )
        for row in rows
    ]


async def postgres_numbers(session: AsyncSession) -> tuple[int | None, int | None]:
    """The database's size in bytes and its open connections, from Postgres's
    own catalogue. Asked of Postgres only: SQLite has neither."""
    size = await session.scalar(sa_text("SELECT pg_database_size(current_database())"))
    connections = await session.scalar(
        sa_text("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()")
    )
    return (
        None if size is None else int(size),
        None if connections is None else int(connections),
    )


async def _count(session: AsyncSession, query) -> int:
    return int(await session.scalar(select(func.count()).select_from(query.subquery())) or 0)


async def gather(
    session: AsyncSession, settings: Settings, now: datetime | None = None
) -> ProjectStats:
    """Read every number «📊 Проект» shows. Writes nothing."""
    now = now or _now()
    rows = await checks(session)
    running = deployment()

    revision = await current_revision(session)
    database_bytes = connections = None
    bind = session.get_bind()
    if bind.dialect.name == "postgresql":
        database_bytes, connections = await postgres_numbers(session)

    # The deployment's day, for a count across classes in every zone: a digest
    # is marked with its class's own date, so near midnight Moscow a class far
    # east has already moved on. The marks are claims, made before the send
    # (``reminders.claim``), so this counts digests the tick took on, which a
    # send that failed is among.
    today = datetime.now(settings.tz).date()
    mornings = await _count(
        session, select(ReminderSettings.id).where(ReminderSettings.last_morning_sent == today)
    )
    evenings = await _count(
        session, select(ReminderSettings.id).where(ReminderSettings.last_evening_sent == today)
    )

    live_phone = DeviceToken.revoked.is_(False)

    async def seen_within(days: int) -> int:
        since = now - timedelta(days=days)
        return await _count(
            session, select(DeviceToken.id).where(live_phone, DeviceToken.last_seen_at >= since)
        )

    phones_day = await seen_within(1)
    phones_week = await seen_within(7)
    builds = [
        (build, int(phones))
        for build, phones in await session.execute(
            select(DeviceToken.client_version, func.count())
            .where(live_phone)
            .group_by(DeviceToken.client_version)
        )
    ]
    builds.sort(key=lambda pair: (pair[0] is None, -(pair[0] or 0)))

    # A NULL provider is a session from before 0015, which is Petersburg's.
    provider = func.coalesce(DiarySession.provider, PETERSBURG)
    diaries = [
        (str(name), int(sessions))
        for name, sessions in await session.execute(
            select(provider, func.count())
            .where(DiarySession.expired_at.is_(None))
            .group_by(provider)
        )
    ]
    diaries.sort(key=lambda pair: (-pair[1], pair[0]))

    return ProjectStats(
        now=now,
        checks=rows,
        commit=running.commit,
        environment=running.environment,
        region=running.region,
        started_at=STARTED_AT,
        python=platform.python_version(),
        revision=revision,
        expected_revision=EXPECTED_REVISION,
        database_bytes=database_bytes,
        connections=connections,
        last_tick=max((row.checked_at for row in rows), default=None),
        tick_before=max(
            (row.previous_checked_at for row in rows if row.previous_checked_at is not None),
            default=None,
        ),
        mornings_today=mornings,
        evenings_today=evenings,
        classes=await _count(session, select(SchoolClass.id)),
        accounts=await _count(session, select(BotUser.telegram_id).distinct()),
        phones_day=phones_day,
        phones_week=phones_week,
        builds=builds,
        diaries=diaries,
    )
```

- [ ] **Step 3: The keyboard and the page.**

Create `server/app/bot/project_keyboard.py`:
```python
"""Buttons for «📊 Проект» (``handlers/project``)."""

from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from app.bot.keyboards import back_to_menu


class ProjectAction(CallbackData, prefix="prj"):
    action: str  # open


def open_button() -> InlineKeyboardButton:
    """The way in from «⚙️ Класс», drawn for the deployment's owner alone."""
    return InlineKeyboardButton(text="📊 Проект", callback_data=ProjectAction(action="open").pack())


def project_keyboard() -> InlineKeyboardMarkup:
    return back_to_menu(
        [
            [
                InlineKeyboardButton(
                    text="🔄 Обновить", callback_data=ProjectAction(action="open").pack()
                )
            ]
        ]
    )
```

Create `server/app/bot/project_render.py`:
```python
"""«📊 Проект» and its first block alone, «🩺 Состояние» (``handlers/project``).

One message, so one budget: :func:`render_project` is clamped like every
page that grows with the data, and the one list that grows without bound -
phones by app build, one row per APK still in the wild - stops at
:data:`BUILDS_MAX` and says «… и ещё N». Everything that did not come from a
constant here is escaped: a check's reason, a diary provider's name, the
commit and the region. Times are shown in the deployment's zone.
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import UTC, datetime
from html import escape
from zoneinfo import ZoneInfo

from app.services.health import CHECKS, FAILING, OK, UNKNOWN
from app.services.project_stats import CheckRow, ProjectStats
from app.wording import clamp, cut, duration, more_line

#: Rows of «Сборки» before «… и ещё N».
BUILDS_MAX = 10

#: The longest reason quoted on a check's line.
REASON_SHOWN = 120

CHECK_TITLES = {
    "schema": "Схема базы",
    "v2": "v2",
    "diary_proxy": "Прокси дневника",
    "deploy": "Деплой",
}
ICONS = {OK: "✅", FAILING: "🔴", UNKNOWN: "❔"}

#: The dashboards, by name and never by link: a link names the owner's
#: accounts, and ``docs/deploy.md`` keeps them.
GRAPHS = (
    "Vercel → Observability (запросы, ошибки, время по маршрутам), "
    "Sentry → Issues и Performance, Neon → Monitoring. Ссылки — в docs/deploy.md."
)


def _when(stamp: datetime, zone: ZoneInfo) -> str:
    return f"{stamp.replace(tzinfo=UTC).astimezone(zone):%d.%m %H:%M}"


def _minutes(span) -> int:
    return max(0, int(span.total_seconds() // 60))


def _code(text: str, limit: int = REASON_SHOWN) -> str:
    return f"<code>{escape(cut(text, limit))}</code>"


def state_lines(checks: Sequence[CheckRow], zone: ZoneInfo) -> list[str]:
    """«🩺 Состояние»: each check, its state, since when, and why when it is not ok."""
    by_name = {check.name: check for check in checks}
    lines = ["<b>🩺 Состояние</b>"]
    for name in CHECKS:
        title = CHECK_TITLES[name]
        check = by_name.get(name)
        if check is None:
            lines.append(f"❔ {title} — ещё не проверялось")
            continue
        line = f"{ICONS.get(check.status, '❔')} {title} — с {_when(check.since, zone)}"
        if check.status != OK and check.reason:
            line += f" · {_code(check.reason)}"
        lines.append(line)
    return lines


def render_health(checks: Sequence[CheckRow], zone: ZoneInfo) -> str:
    """``/health``: the first block of «📊 Проект», alone."""
    return clamp(state_lines(checks, zone))


def render_project(stats: ProjectStats, zone: ZoneInfo) -> str:
    by_name = {check.name: check for check in stats.checks}
    lines = ["<b>📊 Проект</b>", ""]
    lines += state_lines(stats.checks, zone)

    lines += ["", "<b>🖥 Сервер</b>"]
    deploy = by_name.get("deploy")
    on_main = ICONS.get(deploy.status, "❔") if deploy is not None else "❔"
    commit = _code(stats.commit[:7]) if stats.commit else "не известен"
    lines.append(f"Коммит: {commit} · голова main: {on_main}")
    where = escape(stats.region) if stats.region else "не Vercel"
    lines.append(f"Регион: {where} · Python {escape(stats.python)}")
    lines.append(f"Экземпляр жив: {duration(_minutes(stats.now - stats.started_at))}")

    lines += ["", "<b>🗄 База</b>"]
    revision = escape(stats.revision) if stats.revision else "не известна"
    lines.append(f"Схема: {revision}, код ждёт {escape(stats.expected_revision)}")
    if stats.database_bytes is not None:
        size = f"{stats.database_bytes / 1024 / 1024:.1f}".replace(".", ",")
        lines.append(f"Размер: {size} МБ · соединений: {stats.connections}")

    lines += ["", "<b>🛰 Прокси дневника</b>"]
    proxy = by_name.get("diary_proxy")
    if proxy is None:
        lines.append("❔ ещё не проверялось")
    elif proxy.status == OK:
        took = f" · {proxy.round_trip_ms} мс" if proxy.round_trip_ms is not None else ""
        lines.append(f"✅ отвечает{took} · с {_when(proxy.since, zone)}")
    elif proxy.status == FAILING:
        lines.append(f"🔴 не отвечает · с {_when(proxy.since, zone)} · {_code(proxy.reason)}")
    else:
        lines.append(f"❔ {_code(proxy.reason)}")

    lines += ["", "<b>⏰ Часы</b>"]
    if stats.last_tick is None:
        lines.append("Тиков ещё не было")
    else:
        ago = duration(_minutes(stats.now - stats.last_tick))
        tick = f"Последний тик: {_when(stats.last_tick, zone)} ({ago} назад)"
        if stats.tick_before is not None:
            tick += f", до него: {duration(_minutes(stats.last_tick - stats.tick_before))}"
        lines.append(tick)
    lines.append(
        f"Сводок сегодня: утренних {stats.mornings_today}, вечерних {stats.evenings_today}"
    )

    lines += ["", "<b>📦 Проект</b>"]
    lines.append(f"Классов: {stats.classes} · аккаунтов: {stats.accounts}")
    lines.append(f"Телефонов за сутки: {stats.phones_day} · за неделю: {stats.phones_week}")
    if stats.builds:
        lines.append("Сборки:")
        shown = stats.builds[:BUILDS_MAX]
        for build, phones in shown:
            label = f"сборка {build}" if build is not None else "без версии"
            lines.append(f"• {label} — {phones}")
        lines += more_line(len(stats.builds), len(shown))
    if stats.diaries:
        listed = ", ".join(f"{escape(name)} — {sessions}" for name, sessions in stats.diaries)
        lines.append(f"Дневники: {listed}")
    v2 = by_name.get("v2")
    v2_state = {OK: "включён", FAILING: "выключен"}.get(v2.status if v2 else "", "не известно")
    lines.append(f"v2: {v2_state}")

    lines += ["", "<b>📈 Где графики</b>", GRAPHS]
    return clamp(lines)
```

- [ ] **Step 4: The handlers, and their place before the catch-all.**

Create `server/app/bot/handlers/project.py`:
```python
"""«📊 Проект»: how the whole deployment stands, for the deployment's owner.

Owner here means an account in ``OWNER_IDS``, not a class's owner: the screen
sums every class's numbers and shows the infrastructure under them, which are
the deployment's (the monitoring design's question 2, answered by the owner).
``/project`` shows all of it; ``/health`` its first block alone; a button on
«⚙️ Класс» opens it too, drawn for the deployment's owner and nobody else.

For anybody else these are commands this bot does not have. The filter hands
the update on, and the catch-all answers it as it answers any command it does
not know (``handlers/unknown.py``). A filter rather than ``@needs``, for the
very reason ``@needs`` is a decorator: a failed filter hands the update on
instead of refusing it, and here handing it on is the point - a refusal would
say the screen exists. Neither is in ``COMMANDS``, the one menu every account
sees.

It reads and writes nothing (``services/project_stats.py``).
"""

from __future__ import annotations

from aiogram import Router
from aiogram.filters import Command
from aiogram.types import CallbackQuery, Message, TelegramObject
from sqlalchemy.ext.asyncio import AsyncSession

from app.bot import project_render
from app.bot.keyboards import back_to_menu
from app.bot.project_keyboard import ProjectAction, project_keyboard
from app.config import get_settings
from app.services import project_stats
from app.services.roles import is_env_owner

router = Router(name="project")


def deployment_owner(event: TelegramObject) -> bool:
    """Whether the update comes from an account in ``OWNER_IDS``."""
    user = getattr(event, "from_user", None)
    return user is not None and is_env_owner(user.id)


async def _screen(session: AsyncSession) -> str:
    settings = get_settings()
    stats = await project_stats.gather(session, settings)
    return project_render.render_project(stats, settings.tz)


@router.message(Command("project"), deployment_owner)
async def cmd_project(message: Message, session: AsyncSession) -> None:
    await message.answer(await _screen(session), reply_markup=project_keyboard())


@router.message(Command("health"), deployment_owner)
async def cmd_health(message: Message, session: AsyncSession) -> None:
    checks = await project_stats.checks(session)
    text = project_render.render_health(checks, get_settings().tz)
    await message.answer(text, reply_markup=back_to_menu())


@router.callback_query(ProjectAction.filter(), deployment_owner)
async def project_open(callback: CallbackQuery, session: AsyncSession) -> None:
    # «🔄 Обновить» pressed twice within a minute draws the same text, which
    # Telegram refuses as «message is not modified»; `bot._on_error` answers
    # that press, as it does for every screen.
    await callback.message.edit_text(await _screen(session), reply_markup=project_keyboard())
    await callback.answer()
```

In `server/app/bot/handlers/__init__.py`, replace:
```python
    manage,
    reminders,
```
with:
```python
    manage,
    project,
    reminders,
```

In `server/app/bot/handlers/__init__.py`, replace:
```python
    router.include_router(manage.router)
    # Last, and it has to be
```
with:
```python
    router.include_router(manage.router)
    # The deployment owner's screen. Before the catch-all, which answers its
    # commands for everybody else (handlers/project.py says why).
    router.include_router(project.router)
    # Last, and it has to be
```

- [ ] **Step 5: The button on «⚙️ Класс», for the deployment's owner.**

In `server/app/bot/manage_keyboards/class_card.py`, replace:
```python
from app.bot.manage_keyboards.terms import TermAction
```
with:
```python
from app.bot.manage_keyboards.terms import TermAction
from app.bot.project_keyboard import open_button
```

In `server/app/bot/manage_keyboards/class_card.py`, replace:
```python
    pending: int,
    diary_bound: bool = False,
) -> InlineKeyboardMarkup:
```
with:
```python
    pending: int,
    diary_bound: bool = False,
    deployment_owner: bool = False,
) -> InlineKeyboardMarkup:
```

In `server/app/bot/manage_keyboards/class_card.py`, replace:
```python
    if is_owner:
        rows.append(
```
with:
```python
    if deployment_owner:
        # The deployment's screen, not the class's: drawn for an account in
        # OWNER_IDS, which a class's own owner need not be.
        rows.append([open_button()])
    if is_owner:
        rows.append(
```

In `server/app/bot/handlers/manage/class_card.py`, replace:
```python
from app.bot.roles import list_memberships
```
with:
```python
from app.bot.roles import is_env_owner, list_memberships
```

In `server/app/bot/handlers/manage/class_card.py`, replace:
```python
        diary_bound=bool(school_class.diary_provider),
    )
    return text, keyboard
```
with:
```python
        diary_bound=bool(school_class.diary_provider),
        deployment_owner=is_env_owner(telegram_id),
    )
    return text, keyboard
```

- [ ] **Step 6: Green.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_project_screen.py tests/test_bot_commands.py tests/test_bot_manage.py tests/test_bot_message_limits.py tests/test_service_layering.py
```
Expected: all pass:
- `test_project_screen.py`, 7 of 7;
- `test_bot_commands.py`, 119, which is 115 and the four new;
- `test_bot_manage.py`, whose payload walk discovers `ProjectAction` and finds its prefix unique.

- [ ] **Step 7: Gates.** ruff prints `All checks passed!`. mypy prints `Success: no issues found in 228 source files`. `pytest -q -n auto`: 2687 passed (2676 + 11).

- [ ] **Step 8: Commit.** Write `$SCRATCH/commit-mon-t7.txt`:
```text
Show the deployment's owner how the whole project stands, in the bot

/project draws «📊 Проект» in one message. It shows the four checks with
their states and since when, the running commit and whether it is main's
head, the region, the instance's age and Python's version, the schema
and on Postgres the database's size and connections, the proxy's round
trip, the last tick and the gap before it, the digests marked today, the
classes, accounts, phones by day, week and app build, and diary sessions
by diary, and where the graphs are. /health is the first block alone.
Both read and write nothing. A button on «⚙️ Класс» opens it for an
account in OWNER_IDS.

Anybody else is answered as for any command the bot does not have: the
handlers' filter hands the update on to the catch-all, so nothing says
the screen exists. Neither is in the menu everybody sees. The page is
clamped, phones by build stop at ten with «… и ещё N», and everything
from outside is escaped.

Not covered: the screen has not been seen on Postgres or in Telegram.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add server/app/services/project_stats.py server/app/bot/project_keyboard.py server/app/bot/project_render.py server/app/bot/handlers/project.py server/app/bot/handlers/__init__.py server/app/bot/manage_keyboards/class_card.py server/app/bot/handlers/manage/class_card.py server/tests/test_project_screen.py server/tests/test_bot_commands.py && git commit -F C:/Users/lumen/AppData/Local/Temp/monitoring-run/commit-mon-t7.txt
```

---

## Task 8: The documents, the 3b plan's summary of 3b-3, and the counts

Rulings 19 and 23. Every document that names a module, a setting or a count this pull request moved says what is true at its merge.

**Files:**
- Modify: `docs/deploy.md`, `docs/architecture.md`, `docs/bot.md`, `docs/README.md`, `CLAUDE.md`, `README.md`, `.claude/agents/deploy-ops.md`, `.claude/agents/server-api.md`, `.claude/agents/server-bot.md`, `.claude/agents/server-providers.md`, `.claude/agents/security-reviewer.md`, `docs/specs/2026-10-05-server-v2-3b-plan.md`
- Modify (the counts): `CLAUDE.md`, `README.md`, `CONTRIBUTING.md`, `docs/architecture.md`, `.claude/skills/gates/SKILL.md`, `.claude/agents/server-tests.md`, `HANDOVER.md`
- Scratch, never committed: `$SCRATCH/docsmon.py`, `$SCRATCH/countsmon.py`

**Interfaces:**
- Consumes: Tasks 2 to 7, and the numbers of Step 10's real run.
- Produces: documents that are true when the pull request merges.

- [ ] **Step 1: Red: the documents do not know the monitoring.** Write `$SCRATCH/docsmon.py`:
```python
"""Which documents do not yet say what the monitoring pull request built (Task 8)."""

import sys
from pathlib import Path

ROOT = Path(
    sys.argv[1] if len(sys.argv) > 1
    else "C:/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract"
)
SAID = {
    "docs/deploy.md": [
        "| `SENTRY_DSN` | optional:",
        "## Monitoring: what tells the owner something is wrong",
        "### Errors and timings: Sentry",
        "### Where the graphs are",
        "**Its failure email is the one alarm for a server that is down entirely.**",
    ],
    "docs/architecture.md": [
        "### The tick checks the deployment, and tells its owner",
        "├── telegram_send.py",
        "`server/tests/test_observability.py`",
    ],
    "docs/bot.md": ["## The project — `/project`, `/health`", "* **📊 Проект** appears only"],
    "docs/README.md": ["and what tells the owner when something is wrong"],
    "CLAUDE.md": ["- `telegram_send.py` — ", "- `observability.py` — ", "and `SENTRY_DSN` are empty"],
    "README.md": ["| Monitoring |"],
    ".claude/agents/deploy-ops.md": ["4. **Nobody was told.**"],
    "docs/specs/2026-10-05-server-v2-3b-plan.md": ["which the monitoring pull request writes first"],
}

problems = []
for name, phrases in SAID.items():
    text = (ROOT / name).read_text("utf-8")
    problems += [f"{name}: missing {phrase!r}" for phrase in phrases if phrase not in text]
print("\n".join(problems) or "the documents say what the monitoring built")
sys.exit(1 if problems else 0)
```
and run it:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe C:/Users/lumen/AppData/Local/Temp/monitoring-run/docsmon.py
```
Expected: exit 1, with seventeen `missing` lines. Keep the output as the evidence.

- [ ] **Step 2: `docs/deploy.md`.**

In `docs/deploy.md`, replace:
```markdown
| `MIN_CLIENT_VERSION` | empty — or the oldest APK versionCode v2 still answers; raise it only once the newer APK is on the phones it would refuse |
```
with:
```markdown
| `MIN_CLIENT_VERSION` | empty — or the oldest APK versionCode v2 still answers; raise it only once the newer APK is on the phones it would refuse |
| `SENTRY_DSN` | optional: the DSN of the Sentry project errors and request timings go to, see "Errors and timings: Sentry" below. Empty sends nothing |
```

In `docs/deploy.md`, replace:
```markdown
(the phone-linking link), `CRON_SECRET` (the tick answers 404 and not a single digest goes
out). Each of them already refuses at its own door — in full view of whoever it concerns.
```
with:
```markdown
(the phone-linking link), `CRON_SECRET` (the tick answers 404 and not a single digest goes
out), `SENTRY_DSN` (no error and no timing is sent to Sentry). Each of them already refuses at
its own door — in full view of whoever it concerns.
```

In `docs/deploy.md`, replace:
```markdown
variables and the cron job), or the digests will go silent silently.
```
with:
```markdown
variables and the cron job), or the digests will go silent silently.

**Its failure email is the one alarm for a server that is down entirely.** Nothing inside the
server can say so — the bot is down with it — but the cron service sees every run fail. On
cron-job.org that is the job's notifications: an email when the job fails, after a few
failures in a row rather than after one blip, and an email when it succeeds again. The job's
history is the other half of the check: a run answers `200` with the tick's JSON; `403 Bad
cron secret` means the header is not the server's `CRON_SECRET`, and `404 Cron is not
configured` means the server has none. Either way no digest goes out.

**This deployment's job was set up by the owner** on cron-job.org on 5 October 2026, at
about 18:45 UTC, and production has its `CRON_SECRET`. What is still to read is the job's
history — `200` with JSON, not `403` — and whether its failure email is on (#120).
```

Append to `docs/deploy.md`:
```markdown

## Monitoring: what tells the owner something is wrong

On 5 October 2026 three things went wrong in production and nobody was told: a merge did not
deploy for half an hour (#349), the diary's proxy answered nothing for forty minutes, and the
external cron had not been set up (#120). Three pieces say so now
([the design](specs/2026-10-05-monitoring-design.md)): the self-check below, the external
cron's failure email above, and Sentry.

### The self-check, in every tick

Every tick ends with four checks (`server/app/services/health.py`), run last, inside their own
guard and their own time budget, so none of them can fail the tick:

| Check | Ok when |
| --- | --- |
| `schema` | the database is at the revision the code expects — the reading `/api/v1/warmup` makes |
| `v2` | v2 loaded, rather than answering `503` under its two prefixes |
| `diary_proxy` | a `HEAD` of the Petersburg diary through `DIARY_PROXY_URL` gets any answer within five seconds; asked only when the setting is set |
| `deploy` | production runs `main`'s head, or `main` moved less than fifteen minutes ago; asked on production only |

Each ends `ok`, `failing` or `unknown`, and `health_checks` keeps what each said last.
Unknown is a check that could not run, or one that does not apply here, and it never alerts.
The bot writes to every `OWNER_IDS` account when a check starts failing, when it comes back
(with how long it was down), and every six hours while it stays failing — never once per tick.
The text names the infrastructure only. In the bot, `/health` shows the four, and `/project`
the whole of «📊 Проект» (`docs/bot.md`).

The `deploy` check reads `main`'s head from GitHub's public API, without a token, with an
`ETag`, so an unchanged answer is a `304`. GitHub allows sixty unauthenticated requests an hour
per address, and every Vercel function behind the same address shares them, so a refusal reads
as `unknown`, never as `failing`. It learns which commit is running, and which repository to
ask, from Vercel's system environment variables (`VERCEL_ENV`, `VERCEL_GIT_COMMIT_SHA`,
`VERCEL_GIT_REPO_OWNER`, `VERCEL_GIT_REPO_SLUG`). They reach a function while the project's
**Settings → Environment Variables → «Automatically expose System Environment Variables»** is
on, which is Vercel's default. With it off, the check says so and stays `unknown`.

### Errors and timings: Sentry

`SENTRY_DSN` is optional. Empty sends nothing and imports nothing of Sentry, and the startup
log says it is off. Set, the server sends each unhandled error, and each failure the bot's
handlers raise, with:

- the exception's type and its stack — file, function, line;
- the route template, `/api/v1/diary/students/{student_id}/schedule`, never the number in it;
- the release, which is the commit, and the environment, which is Vercel's `VERCEL_ENV`, so a
  preview's errors stay apart from production's.

It also times 5 % of requests, named by route template: those are Sentry's request graphs.

What is never sent, because it can carry a child's name or a family's credential (152-ФЗ):
request and response bodies; headers, cookies and query strings; the exception's message; local
variables and source lines; log lines and breadcrumbs; the spans inside a request. The SDK is
told not to collect them, and two hooks then rebuild every event from a list of what may leave
(`server/app/observability.py`). `server/tests/test_observability.py` sends a request carrying
a diary token and a child's name through a failing route, and finds neither in what would
leave.

What the owner does, once:

1. A Sentry account in the **EU** data region, and a project of the platform «Python /
   FastAPI». For this deployment both exist: the organisation `hubdpi` and its project
   `lessons`.
2. The project's DSN into Vercel as `SENTRY_DSN`, for **Production**, and for **Preview** if a
   preview's errors are wanted too, marked `Sensitive`; then a redeploy. The DSN is a secret
   like the others: never in the repository, an issue or a log.
3. To turn it all off: delete `SENTRY_DSN` and redeploy. No code changes.

The free plan takes 5,000 errors a month, and a 5 % sample of this project's requests is far
inside its allowance for timings.

### Where the graphs are

The bot names the dashboards and links none, because a link names the owner's accounts:

| What | Where |
| --- | --- |
| requests, errors and durations by route | Vercel → the project → **Observability**: `https://vercel.com/codeilluminators/lessons/observability` |
| errors with their place, and the 5 % of timings | Sentry → **Issues** and **Performance**: `https://hubdpi.sentry.io/projects/lessons/` |
| the database's load, connections and storage | the Neon console → the project named `lessons` → **Monitoring**; its id stays out of the repository |

Longer retention in Vercel is Observability Plus, a paid add-on, and nothing here needs it.
```

- [ ] **Step 3: `docs/architecture.md`, `docs/bot.md` and `docs/README.md`.**

In `docs/architecture.md`, replace:
```text
├── wording.py     the words both shells print: dates, plurals, a day's card
```
with:
```text
├── wording.py     the words both shells print: dates, plurals, a day's card
├── telegram_send.py  a bot built for one job: the tick's, v1's notices', the owner's alerts
├── observability.py  Sentry's start and its scrubbing, imported only where SENTRY_DSN is set
```

In `docs/architecture.md`, replace:
```markdown
### The resolution model
```
with:
```markdown
### The tick checks the deployment, and tells its owner

Every cron tick ends with a self-check (`services/health.py`; the design is
`docs/specs/2026-10-05-monitoring-design.md`): whether the database is at the code's revision,
whether v2 loaded, whether the diary's proxy answers, and whether production runs `main`'s
head. Each says `ok`, `failing` or `unknown`, and a row of `health_checks` keeps what it said
last, so the owner is written to on a change — when a check starts failing, when it comes
back, and every six hours between — and never once per tick. Unknown is a check that could
not run, and it never alerts. The rule is one function, `health.decide`, over what was stored
and what was seen, and the claim on an alert is a compare-and-set on `last_alert_at`, so two
ticks running together tell the owner once. It runs last, inside its own guard and time
budget, as the diary keep-alive does, so nothing it meets can fail the tick.

The messages go through `app/telegram_send.py`, a bot built for one job and closed after it,
which imports aiogram inside its functions: `services/` may not import the bot, and the API's
cold start must not load aiogram. The owner reads the same rows in the bot's «📊 Проект»
(`/project`, `/health`), which also counts the deployment's classes, accounts, phones by app
build and diary sessions, and reads only (`services/project_stats.py`). Requests are not
counted in the database, which would be a write on every request. Their graphs are Vercel's
Observability and Sentry's; Sentry (`app/observability.py`) is started only where
`SENTRY_DSN` is set, and every event it sends is rebuilt from a list of what may leave.

### The resolution model
```

In `docs/architecture.md`, replace:
```markdown
| `server/tests/test_announcements.py` | that every place pushing to the class is bounded, measured at the call site rather than on the helper | pytest |
```
with:
```markdown
| `server/tests/test_announcements.py` | that every place pushing to the class is bounded, measured at the call site rather than on the helper | pytest |
| `server/tests/test_health.py`, `test_health_tick.py` | the four checks with the proxy, GitHub and Vercel faked; the alert rule — one message on a change, none per tick, a reminder after six hours, nothing for `unknown`, a refused send tried again; the time budget; and a tick that answers as before when every check raises | pytest + `httpx.MockTransport` |
| `server/tests/test_observability.py` | that no body, header, query, message or local leaves for Sentry, on an event built from a real request in a fresh interpreter | pytest + a subprocess |
| `server/tests/test_project_screen.py` | «📊 Проект»'s numbers read without a write, the page within budget, everything from outside escaped | pytest |
```

In `docs/bot.md`, replace:
```markdown
* **🗑 Удалить класс** is owner-only and asks for the class name typed back
  exactly. Nothing else is accepted: an "are you sure?" button is pressed by the
  same thumb that pressed the one before it.
```
with:
```markdown
* **🗑 Удалить класс** is owner-only and asks for the class name typed back
  exactly. Nothing else is accepted: an "are you sure?" button is pressed by the
  same thumb that pressed the one before it.
* **📊 Проект** appears only for the deployment's owner — an account in `OWNER_IDS`,
  which a class's owner need not be — and opens the screen of `/project` (below).
```

In `docs/bot.md`, replace:
```markdown
## Time zone
```
with:
```markdown
## The project — `/project`, `/health`

The deployment owner's screen, «📊 Проект». The owner here is an account in `OWNER_IDS`, not
a class's owner, because the screen sums every class and shows the infrastructure under them.
One message, read only, in blocks:

| Block | What it shows |
| --- | --- |
| «🩺 Состояние» | the tick's four checks — the schema, v2, the diary's proxy and the deploy — each ✅, 🔴 or ❔, since when, and why when it is not ok |
| «🖥 Сервер» | the running commit and whether it is `main`'s head, the region, how long this function instance has been alive, Python's version |
| «🗄 База» | the database's revision and the one the code expects; on Postgres its size and its connections |
| «🛰 Прокси дневника» | whether the proxy answers, its round trip, since when |
| «⏰ Часы» | the last tick, the gap since the one before, and the digests marked today |
| «📦 Проект» | classes, accounts, phones seen in a day and a week, phones by app build, live diary sessions by diary, v2 on or off |
| «📈 Где графики» | where the request graphs are: Vercel, Sentry and Neon, named and not linked; `docs/deploy.md` has the addresses |

`/health` is the first block alone. The same owner is written to by the tick when a check
changes — «🔴 Прокси дневника не отвечает», then «🟢 Прокси дневника снова работает, простой
40 мин» — and every six hours while one stays failing; `docs/deploy.md`, «Monitoring», has the
rule.

## Time zone
```

In `docs/bot.md`, replace:
```markdown
| `/code` | admin+ | join code for the app |
```
with:
```markdown
| `/code` | admin+ | join code for the app |
| `/project` | the deployment's owner | «📊 Проект»: the deployment's state and numbers |
| `/health` | the deployment's owner | its first block: the tick's four checks |
```

In `docs/bot.md`, replace:
```markdown
is asked first, and a test walks the whole `COMMANDS` list to hold that.
```
with:
```markdown
is asked first, and a test walks the whole `COMMANDS` list to hold that.

`/project` and `/health` are commands the bot does not have for anybody but the deployment's
owner: they are not in `COMMANDS`, the one menu everybody sees, and for anybody else the
catch-all answers them as it answers a typo, so nothing says they exist.
```

In `docs/README.md`, replace:
```markdown
| [deploy.md](deploy.md) | Vercel plus Neon or your own server, the webhook, migrations, why the server has no clock of its own |
```
with:
```markdown
| [deploy.md](deploy.md) | Vercel plus Neon or your own server, the webhook, migrations, why the server has no clock of its own, and what tells the owner when something is wrong |
```

- [ ] **Step 4: `CLAUDE.md` and `README.md`.**

In `CLAUDE.md`, replace:
```markdown
  `main.mount_v2` mounts both and answers `503` under their prefixes if v2 will not import
```
with:
```markdown
  `main.mount_v2` mounts both and answers `503` under their prefixes if v2 will not import
- `telegram_send.py` — a bot built for one job and closed after it (`build_bot`, `close_bot`,
  `send`), for the code that is not the bot: the tick, v1's notices and the self-check's
  alerts. aiogram is imported inside its functions and never at the top, so it costs a cold
  start nothing; `app.bot.bot` imports `build_bot` back from it, and
  `tests/test_service_layering.py` holds that it reaches neither the bot nor a shell
- `observability.py` — Sentry's start and its scrubbing hooks, imported only where
  `SENTRY_DSN` is set (`app.main` and the bot's error handler ask first). Every event is
  rebuilt from a list of what may leave: an exception's type and stack, the route template,
  the release and the environment; never a body, a header, a query, a message or a local
  (152-ФЗ), which `tests/test_observability.py` proves on an event built from a real request
```

In `CLAUDE.md`, replace:
```markdown
  import's and the zone's refusals) are `app/wording.py`'s.
```
with:
```markdown
  import's and the zone's refusals) are `app/wording.py`'s.
  The tick ends with `health.py`, its self-check: four checks, what each said last in
  `health_checks`, and the owner's alerts on a change; the owner's «📊 Проект» reads
  `project_stats.py`, which writes nothing.
```

In `CLAUDE.md`, replace:
```markdown
  Anything you are tempted to schedule in-process belongs in that tick instead.
```
with:
```markdown
  Anything you are tempted to schedule in-process belongs in that tick instead.
  The tick ends with the self-check (`services/health.py`), which writes to `OWNER_IDS`
  when a check changes; when the whole server is down, only the external cron's own
  failure email can say so (`docs/deploy.md`, «The external cron»).
```

In `CLAUDE.md`, replace:
```markdown
  `PUBLIC_BASE_URL`, `BOT_USERNAME` and `CRON_SECRET` are empty by design and each already
  refuses in view of whoever it concerns; they are logged as switched off at startup
```
with:
```markdown
  `PUBLIC_BASE_URL`, `BOT_USERNAME`, `CRON_SECRET` and `SENTRY_DSN` are empty by design and
  each already refuses in view of whoever it concerns; they are logged as switched off at startup
```

In `CLAUDE.md`, replace:
```markdown
  `X-Lessons-Client` is never refused — and it is not a feature switched off, so it is in
  neither list.
```
with:
```markdown
  `X-Lessons-Client` is never refused — and it is not a feature switched off, so it is in
  neither list. What Vercel says about the running deployment — `VERCEL_ENV`,
  `VERCEL_GIT_COMMIT_SHA`, `VERCEL_GIT_REPO_OWNER`, `VERCEL_GIT_REPO_SLUG` and
  `VERCEL_REGION` — is no setting at all: `config.deployment()` reads it from the environment
  alone, never from `.env`, and off Vercel it is empty.
```

In `README.md`, replace:
```markdown
| `./gradlew test` | 1635 tests, green, all five modules |
```
with:
```markdown
| Monitoring | the tick's self-check of the schema, v2, the diary's proxy and the deploy, the owner's alerts on a change, Sentry's scrubbing and «📊 Проект» — tested in-process with the outside world faked, and the scrubbing on an event built from a real request in a fresh interpreter; none of it has run in production yet |
| `./gradlew test` | 1635 tests, green, all five modules |
```

- [ ] **Step 5: The agents.**

In `.claude/agents/deploy-ops.md`, replace:
```markdown
   the external cron service named in `docs/deploy.md`.
```
with:
```markdown
   the external cron service named in `docs/deploy.md`.
4. **Nobody was told.** The tick ends with a self-check (`services/health.py`) that writes to
   the owner on a change, and the bot's `/health` shows what each check said last: the
   schema, v2, the diary's proxy and whether production runs `main`'s head. Read it first.
   A check that reads ❔ could not run, and its reason says why. Errors with their place are
   in Sentry where `SENTRY_DSN` is set (`docs/deploy.md`, «Monitoring»).
```

In `.claude/agents/server-api.md`, replace:
```markdown
  `diary_web.py` is the one server-rendered page in the project, `cron.py` is the clock the
  server does not have, `telegram.py` is the webhook, `deps.py` is device-token auth.
```
with:
```markdown
  `diary_web.py` is the one server-rendered page in the project, `cron.py` is the clock the
  server does not have, and ends with the self-check in its own guard, `telegram.py` is the
  webhook, `deps.py` is device-token auth.
```

In `.claude/agents/server-bot.md`, replace:
```markdown
- `handlers/` — `access`, `calendar`, `content`, `diary`, `editor`, `manage`, `reminders`,
```
with:
```markdown
- `handlers/` — `access`, `calendar`, `content`, `diary`, `editor`, `manage`, `project` (the
  deployment owner's «📊 Проект», answered as an unknown command to anybody else), `reminders`,
```

In `.claude/agents/server-providers.md`, replace:
```markdown
`PUBLIC_BASE_URL`, `BOT_USERNAME` and `CRON_SECRET` are empty by design and each already
```
with:
```markdown
`PUBLIC_BASE_URL`, `BOT_USERNAME`, `CRON_SECRET` and `SENTRY_DSN` are empty by design and each already
```

In `.claude/agents/security-reviewer.md`, replace:
```markdown
- **Which token an endpoint depends on.**
```
with:
```markdown
- **What leaves for Sentry.** `app/observability.py` rebuilds every event from a list of what
  may stay — an exception's type and stack, the route template, the release, the environment
  — and drops the rest: bodies, headers, queries, the exception's message, locals, spans.
  A field added to those lists is a field that leaves the country; it needs a reason, and
  `tests/test_observability.py` has to go on finding no token and no name.
- **Which token an endpoint depends on.**
```

- [ ] **Step 6: The 3b plan's summary of 3b-3: `telegram_send.py` exists before it.**

In `docs/specs/2026-10-05-server-v2-3b-plan.md`, replace:
```markdown
`DirectoryService` ×2, `server/app/telegram_send.py` (5) |
```
with:
```markdown
`DirectoryService` ×2, and v1's notices onto `server/app/telegram_send.py`, which the monitoring pull request writes first (5) |
```

In `docs/specs/2026-10-05-server-v2-3b-plan.md`, replace:
```markdown
- **`server/app/telegram_send.py`, new and neutral**, holds:
  - `build_bot()`, moved from `app/bot/bot.py:build_bot` and importing aiogram inside the function, so that the cold start stays free of aiogram (`test_cold_start.py`);
  - a one-shot send to one person;
  - the class notice over `services/notify.notify_subscribers`.
```
with:
```markdown
- **`server/app/telegram_send.py` exists before 3b-3.** The monitoring pull request writes it (`docs/specs/2026-10-06-monitoring-plan.md`, Task 2), and 3b-3 reuses it rather than creating it. It already holds `build_bot()`, moved from `app/bot/bot.py` and importing aiogram inside the function, `close_bot()`, and `send()`, a batch of `(telegram_id, text)` that never raises. 3b-3 adds to it:
  - a one-shot send to one person, unless `send()` of one is enough;
  - the class notice over `services/notify.notify_subscribers`.
```

In `docs/specs/2026-10-05-server-v2-3b-plan.md`, replace:
```markdown
- **`app.bot.bot` re-exports `build_bot`**, so the bot is unchanged.
- **The three copies of `_build_bot`** (`api/edit.py`, `api/manage/requests.py`, `api/cron.py`) call `telegram_send`. The tests that patch
```
with:
```markdown
- **`app.bot.bot` already imports `build_bot` back from it**, since the monitoring pull request, so the bot is unchanged.
- **The three copies of `_build_bot`** (`api/edit.py`, `api/manage/requests.py`, `api/cron.py`) already build through `telegram_send` since the monitoring pull request, and stay the seams the tests replace. If 3b-3 removes them, the tests that patch
```

In `docs/specs/2026-10-05-server-v2-3b-plan.md`, replace:
```markdown
- **`tests/test_service_layering.py`** gains `app.telegram_send` among the modules that may reach neither `app.bot` nor a v1 router.
```
with:
```markdown
- **`tests/test_service_layering.py`** already holds that `app.telegram_send` reaches neither `app.bot` nor a shell, since the monitoring pull request.
```

- [ ] **Step 7: Green: the documents, and what the tests read of them.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe C:/Users/lumen/AppData/Local/Temp/monitoring-run/docsmon.py
```
Expected: `the documents say what the monitoring built`, exit 0. Then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -p no:xdist tests/test_schema_version.py tests/test_ci_paths.py tests/test_deployment_config.py tests/test_env_example.py tests/test_rpc_errors.py
```
Expected: all pass. `test_deployment_config.py` reads `docs/deploy.md`'s headings, and `test_rpc_errors.py` reads `docs/api.md`. Then the head scan of Task 3 Step 6 once more:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe C:/Users/lumen/AppData/Local/Temp/monitoring-run/scan_heads.py
```
Expected: the last line is `revisions named: ['0019']`, and no line names `docs/specs/`.

- [ ] **Step 8: The gates, and their numbers everywhere.**
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m ruff check app tests scripts migrations && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe -m mypy && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/pytest.exe -q -n auto
```
Expected:
- ruff prints `All checks passed!`;
- mypy prints `Success: no issues found in 228 source files`;
- pytest prints `2687 passed` and its time.

If the count is not 2687, find the test file that moved before writing anything. Then read the numbers the documents carry now, which the branch's base left at 2621 and 221:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git grep -n "tests in about four minutes" -- CLAUDE.md
```
The number in that line is `OLD_TESTS`. mypy's is in the next line of `CLAUDE.md` that says `of all … modules`: `OLD_MODULES`. Then write `$SCRATCH/countsmon.py`:
```python
"""Write the suite's and mypy's counts from a real run into the seven places that carry them.

Usage: countsmon.py OLD_TESTS NEW_TESTS OLD_MODULES NEW_MODULES [ROOT], the old ones as the
documents carry them, the new ones from this batch's run.
"""

import sys
from pathlib import Path

OLD_T, NEW_T, OLD_M, NEW_M = sys.argv[1:5]
ROOT = Path(
    sys.argv[5] if len(sys.argv) > 5
    else "C:/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract"
)
PLACES = {
    "CLAUDE.md": [
        (f"— {OLD_T} tests in about four minutes", f"— {NEW_T} tests in about four minutes"),
        (f"of all {OLD_M} modules", f"of all {NEW_M} modules"),
    ],
    "README.md": [
        (f"| clean, {OLD_M} modules —", f"| clean, {NEW_M} modules —"),
        (f"| {OLD_T} tests, green,", f"| {NEW_T} tests, green,"),
    ],
    "CONTRIBUTING.md": [
        (f"of all {OLD_M} modules:", f"of all {NEW_M} modules:"),
        (f"the server tests, {OLD_T} of them,", f"the server tests, {NEW_T} of them,"),
    ],
    "docs/architecture.md": [(f"{OLD_T} tests on the server,", f"{NEW_T} tests on the server,")],
    ".claude/skills/gates/SKILL.md": [
        (f"of all {OLD_M} modules,", f"of all {NEW_M} modules,"),
        (f"— {OLD_T} tests today,", f"— {NEW_T} tests today,"),
    ],
    ".claude/agents/server-tests.md": [
        (f"`server/tests/`. {OLD_T} tests;", f"`server/tests/`. {NEW_T} tests;"),
    ],
    "HANDOVER.md": [
        (f"# {OLD_T} tests, ~12 min alone on Windows", f"# {NEW_T} tests, ~12 min alone on Windows"),
        (f"# clean, {OLD_M} modules", f"# clean, {NEW_M} modules"),
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
and run it with the four numbers, the old count first:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server && /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract/server/.venv/Scripts/python.exe C:/Users/lumen/AppData/Local/Temp/monitoring-run/countsmon.py 2621 2687 221 228
```
(with the numbers the commands above printed in place of `2621`, `2687`, `221` and `228`). Expected: `written`. The 3b-2 batch section's own count in `HANDOVER.md` is a record of its commit, and it stays.

- [ ] **Step 9: Commit the documents.** Write `$SCRATCH/commit-mon-t8.txt`:
```text
Describe the monitoring where each part of it is looked for

docs/deploy.md gains SENTRY_DSN among the optional settings and a
section on monitoring. It covers the four checks and the alert rule, the
deploy check's GitHub budget and Vercel's system variables, what Sentry
receives and what it never does, the owner's three steps, and where the
graphs are: Vercel's and Sentry's by address, and Neon's by the way there,
because its id stays out of the repository. The external cron's failure
email is named as the one alarm for a server that is down, with the check
of the job's history that #120 still asks for.

docs/architecture.md, docs/bot.md, CLAUDE.md, README.md and the agents
name the new modules, the screen and the setting. The 3b plan's summary
of 3b-3 now says that telegram_send.py exists, and that 3b-3 reuses it
rather than creating it. The counts are the run's own, in the seven
places that carry them.

Not covered: HANDOVER.md's close-out, written once the pull request has a
number.
```
then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add docs/deploy.md docs/architecture.md docs/bot.md docs/README.md CLAUDE.md README.md CONTRIBUTING.md .claude/agents/deploy-ops.md .claude/agents/server-api.md .claude/agents/server-bot.md .claude/agents/server-providers.md .claude/agents/security-reviewer.md .claude/agents/server-tests.md .claude/skills/gates/SKILL.md HANDOVER.md docs/specs/2026-10-05-server-v2-3b-plan.md && git commit -F C:/Users/lumen/AppData/Local/Temp/monitoring-run/commit-mon-t8.txt
```

---

## Task 9: The pull request, `0019` on both databases, the HANDOVER close-out, the merge, and production after it

In the shape of 3b-2's Task 8 Steps 8 to 11. Steps 1, 2, 4, 5 and 6 are the controller's. Step 3 is written while the pull request is open, so that `HANDOVER.md` is true when it merges.

**Files:**
- Modify: `HANDOVER.md`, `docs/history.md`
- Scratch, never committed: `$SCRATCH/commit-mon-handover.txt`

**Interfaces:**
- Consumes:
  - Tasks 1 to 8, and the numbers of Task 8's run;
  - `HANDOVER.md` as the base left it: «What the last session added» is 3b-2's (#356), with its subsection «After #350's merge: stage 3b-1 in production, and what followed», and «What the session before it added» is 3b-1's (#350);
  - #356's merge: its SHA and its time, from `gh pr view 356`;
  - what followed #356's merge, which the controller hands over at Step 3 for the slot `[AFTER-356]`;
  - this pull request's number, `#PR`, which exists only once Step 1 opens it.
- Produces: the merged pull request, `0019` on production, and the post-merge read.

- [ ] **Step 1: The controller pushes and opens the pull request** (the `github-pr` skill). It goes from `monitoring` to `main`, on milestone 12, `v1.0.0 — A build somebody else can install`, with its board item filled as the skill says. Its body:
  - names `Closes #349` and `Closes #HEADPIN`, each on its own line;
  - refers to #120, whose cron half the owner did on 5 October and whose `DADATA_TOKEN` half is not this pull request's; to #127, the epic; and to #269, the tick's `GET` that writes, which now also writes `health_checks`;
  - says that revision `0019` goes on **before** the merge, and that it is additive and destroys nothing;
  - names the gates as Task 8 Step 8 ran them, with their counts.

  Write down the number it gets as `#PR`.

- [ ] **Step 2: The controller applies `0019` to the Neon branch `preview`** (the `migration` skill), through the Neon connector, on the project **named** `lessons`. Read the name, because the account has two, and keep the id out of everything written.
  1. **Read first:** `SELECT version_num FROM alembic_version;` reads `0018`, and `SELECT to_regclass('public.health_checks');` reads `NULL`.
  2. **Say what it destroys, before the transaction:** «`0019` creates one table, `health_checks`. It destroys nothing and rewrites no row.»
  3. **One transaction**, the stamp last:
```sql
BEGIN;
CREATE TABLE health_checks (
	name VARCHAR(32) NOT NULL,
	status VARCHAR(16) NOT NULL,
	reason VARCHAR(200) NOT NULL,
	since TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	last_alert_at TIMESTAMP WITHOUT TIME ZONE,
	checked_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
	previous_checked_at TIMESTAMP WITHOUT TIME ZONE,
	round_trip_ms INTEGER,
	PRIMARY KEY (name)
);
UPDATE alembic_version SET version_num = '0019' WHERE version_num = '0018';
COMMIT;
```
  4. **Read back:** `alembic_version` reads `0019`, and `health_checks` exists with the eight columns and no row.

  The DDL is `0019`'s docstring, which `test_health_checks_revision.py` holds to the model. Preview has no `CRON_SECRET`, so nothing ticks there; its table stays empty.

- [ ] **Step 3: The HANDOVER close-out** (the `handover` skill, «What goes stale mechanically»). Read the titles again before editing, in case a later merge to `main` moved one.
  1. **The chain of batch sections.**
     - «## What the session before it added: the class's records over v2 — stage 3b-1 of sub-project 3 (#273)», with its four subsections, moves verbatim to the top of `docs/history.md`. It goes directly under the `---` that closes the file's introduction, above «## What the batch before added: v2 served beside v1 — stage 3a of sub-project 3 (#273)», retitled «## What the batch before added: the class's records over v2 — stage 3b-1 of sub-project 3 (#273)». Its subsections keep their titles. A sentence in it that says «section 5» or «above» now points into another file: name `HANDOVER.md` in it.
     - «## What the last session added: bells, the timetable and the class over v2 — stage 3b-2 of sub-project 3 (#273)» becomes «## What the session before it added: …», with the same rest of the title. Its subsection «After #350's merge: stage 3b-1 in production, and what followed» stays with it. Its first sentence, «Open as #356, from `server-v2/3b-2` to `main`, on milestone 11, and on project 6.», becomes «Merged as #356 (`[short SHA of #356's merge]`, [its date]), from `server-v2/3b-2`, on milestone 11.»
  2. **The new section**, above it, with the run's numbers, the real SHAs and the numbers in place of the bracketed words:
```markdown
## What the last session added: the deployment says when it is broken — monitoring (#349, #120)

Open as #PR, from `monitoring` to `main`, on milestone 12, and on project 6. It closes #349
and #HEADPIN, and refers to #120, #127 and #269. The branch was cut from `main` at
`[short SHA]`, the merge of #356, and carries [the number of] commits before this close-out,
to `[short SHA]`. Written on [date], after #356 merged. The schema head moved from `0018` to
`0019`, which the session applied through the Neon connector to the branch `preview` at
[time] UTC on [date] and to production at [time] UTC, both before the merge. This is
`docs/specs/2026-10-05-monitoring-design.md`, built by the plan beside it,
`docs/specs/2026-10-06-monitoring-plan.md`.

- **Every cron tick ends with a self-check** (`services/health.py`): the schema, v2, the
  diary's proxy, and whether production runs `main`'s head. Each says `ok`, `failing` or
  `unknown`, and `health_checks` (revision `0019`) keeps what it said last. The owner, every
  `OWNER_IDS` account, is written to when a check starts failing, when it comes back with how
  long it was down, and every six hours while it stays failing; never once per tick, and
  never for `unknown`. The rule is one pure function, `decide`. An alert is claimed by a
  compare-and-set, so two ticks tell once, and a claim nobody received is given back. The
  self-check runs last, in its own guard and inside 28 seconds of the request, and the tick's
  body is unchanged.
- **The `deploy` check is #349's cure.** It reads `main`'s head from GitHub with an `ETag`
  held in the process, and production's commit from Vercel's system variables, which
  `config.deployment()` reads from the environment alone. A GitHub that refuses is
  `unknown`, never `failing`.
- **A neutral sender**, `app/telegram_send.py`: `build_bot`, `close_bot` and `send`, which
  never raises. The tick and v1's two notice seams build through it, and `app.bot.bot`
  imports `build_bot` back. 3b-3 reuses it, and the 3b plan says so now.
- **Sentry**, where `SENTRY_DSN` is set and nowhere else (`app/observability.py`). Each error
  goes with its type, its stack, its route template, the commit and `VERCEL_ENV`, and 5 % of
  requests go as a route and a duration. Two hooks rebuild every event from a list of what may
  leave, and a request carrying a diary token and a child's name leaves with neither
  (152-ФЗ). Without the setting nothing of Sentry is imported, and `SENTRY_DSN` is announced
  as off, never refused.
- **«📊 Проект» in the bot**, `/project` and `/health`, and a button on «⚙️ Класс», for an
  `OWNER_IDS` account alone. To anybody else they answer as an unknown command. It reads
  and writes nothing.
- **The external clock's documents**: `docs/deploy.md` says the owner set up cron-job.org on
  5 October, names its failure email as the one alarm for a server that is down, and lists the
  dashboards.
- **One defect, filed before its fix**: #HEADPIN, `test_client_version_revision.py` pinning
  the head to `0018`. It asks for `0018`'s own place in the chain now.

### Gates

All at `[short SHA of the head]`, the head before this close-out. CI runs on the head the
merge is made from, and the merge waits for it to be green.

- **ruff**: `ruff check app tests scripts migrations`, all checks passed.
- **mypy**: no issues found in [the number] source files.
- **The server suite.** `pytest -q -n auto`, run alone from `server/`, gave **[the number]
  passed** in [the time]. The seven places the `handover` skill names say [the number].
- **The contract** was not run: nothing under `proto/`, `buf.*` or `server/app/contract/`
  changed.
- **CI on the head** is read before the merge.
- **Android** was not run, because nothing under `android/` changed; its 1635 tests stand from
  before.

### What was deliberately left alone

- **v1's `_tell` helpers**, for 3b-3, which reuses `telegram_send`.
- **v2's own `INTERNAL` answers** reach no Sentry: `invoke` turns the exception into a
  `ConnectError` before any integration sees it.
- **The exceptions the tick catches and logs itself** — the keep-alive's and the self-check's
  — are logged and not sent to Sentry; the logging integration is off on purpose.
- **A GitHub token for the `deploy` check**: unauthenticated, sixty an hour per address. If
  `/health` often shows it ❔ with «GitHub answered HTTP 403», an optional read-only token is
  the next step.
- **Request counting in the database**, which the design rules out: the request graphs are
  Vercel's and Sentry's.
- **#120's `DADATA_TOKEN` half**, which is the owner's.

### What nobody has verified in this batch

- **The self-check in production** until the post-merge read below, and a real alert in the
  owner's chat.
- **Anything of it on Postgres**: the claim's compare-and-set on `last_alert_at`, the two
  catalogue queries of «📊 Проект», and the screen itself.
- **Vercel's system variables reaching the function**, and `VERCEL_REGION` at runtime.
- **Whether GitHub exempts an unauthenticated `304` from its limit**, as the design says. If
  it does not, a warm instance spends twelve of the sixty an hour.
- **Sentry ingesting an event the hooks rebuilt**: no DSN is set yet.

### After #356's merge: stage 3b-2 in production, and what followed

None of this is code in #PR, and a close-out never gets a close-out of its own, so it is
written here. The source is the controller's notes of [date].

[AFTER-356: the controller's facts, handed over at this step and written in the shape of the
3b-2 section's «After #350's merge», one bullet each: the merge and its five checks; whether
Vercel built production from the merge by itself (#349); what production answered after it,
the fifteen methods of 3b-2 asked once without a token among them; and anything the owner did
or decided since 3b-2's close-out, `SENTRY_DSN` and cron-job.org's history among it. Nothing
here is guessed: what the controller does not hand over is left out, and if it hands over
nothing, this subsection is left out whole and the report says so.]
```
  3. **The opening paragraph**, in the shape the base left it:
     - «Last updated:» is the day of writing. The merged list gains #356, and `main` is at #356's merge, with its time; read `git rev-parse --short origin/main` first, in case something merged since.
     - The open pull requests are read from `gh pr list --state open`, not assumed. #PR is one, «the one carrying this paragraph», from `monitoring`, on milestone 12, which closes #349 and #HEADPIN and refers to #120, #127 and #269. The dependabot ones are named with what each bumps, as now.
     - **The schema head moved to `0019`**, applied before the merge to the Neon branch `preview` and then to production, at the Step 2 and Step 4 times, and `EXPECTED_REVISION` moved with it.
     - The issues filed since #356 merged are named: #HEADPIN, closed by #PR.
     - «The section «What the last session added» below is #356's batch, and «What the session before it added» is #350's.» names #PR and #356 instead.
     - It still ends: «The SHA of its own merge is for the next close-out to write.»
     - The bold paragraph that begins «The code expects head `0018`» becomes «The code expects head `0019`, and production has it since [time] UTC on [date], before #PR's merge.». Its body says what `0019` is — one new table, `health_checks`, additive, nothing destroyed — where it said what `0018` is, and keeps one sentence for `0018`.
  4. **The milestone table**: milestone 12's row reads «PRs #329, #333 (merged) and #PR (open); issues #120–#122, #127, #142, #144, #326, #327, #330, #349, #HEADPIN — …». Milestone 11's «#356 (open)» becomes «#356 (merged)».
  5. **Section 5** gains a bullet after «v2 as #342, #350 and #356 serve it…»:
```markdown
- **The monitoring of #PR has been seen only by its tests** until the read after its merge:
  - the four checks against production, and a real alert in the owner's chat;
  - the claim on an alert, and «📊 Проект»'s catalogue queries, on Postgres: every test ran on
    SQLite;
  - Vercel's system variables reaching the function, without which the `deploy` check stays
    ❔ and says so;
  - whether GitHub exempts an unauthenticated `304` from its sixty an hour;
  - an event reaching Sentry, which needs the owner's `SENTRY_DSN`.
```
  6. **Section 7.**
     - The paragraph «**The external cron is connected** (cron-job.org, 5 October). Read its run history once: …» gains, at its end: «Switch on its failure email, after a few failures in a row, and its email when the job succeeds again: it is the one alarm for a server that is down entirely (`docs/deploy.md`, «The external cron»).»
     - A new paragraph after it: «**Put `SENTRY_DSN` into Vercel** for Production, and for Preview if a preview's errors are wanted, marked `Sensitive`, then redeploy. The project is `lessons` in the organisation `hubdpi`, in the EU region. Until then the startup log says Sentry is off, and nothing is sent. Never write the DSN anywhere else.»
     - Read the section for anything the owner did since 3b-2's close-out (the `[AFTER-356]` facts say), and move what they did to «## Moved out of section 7 on [date]» in `docs/history.md`, after «## Moved out of section 7 on 5 October 2026», as the skill says.
  7. The cheat-sheet's counts under «How to continue» were written by Task 8 Step 8.

  Write `$SCRATCH/commit-mon-handover.txt`:
```text
Hand over the monitoring: the self-check, the alerts, Sentry and the screen

HANDOVER.md's close-out is written while the pull request is open, so
the file is true when it merges. It describes the batch: the tick's
self-check and its alert rule, health_checks and revision 0019, the
neutral sender, Sentry and what it never receives, «📊 Проект», the
external clock's documents, the one defect filed before its fix
(#HEADPIN), and what is left alone and unverified, and what followed
#356's merge. 3b-2's section becomes the session before, its merge
recorded, and 3b-1's moves to docs/history.md. Section 7 asks the owner
for SENTRY_DSN and for cron-job.org's failure email.

Not covered: production after this pull request's merge; the next
close-out records it.
```
  then:
```bash
cd /c/Users/lumen/StudioProjects/lessons/.claude/worktrees/spec-one-contract && git add HANDOVER.md docs/history.md && git commit -F C:/Users/lumen/AppData/Local/Temp/monitoring-run/commit-mon-handover.txt
```
  and push.

- [ ] **Step 4: The controller applies `0019` to production, before the merge**, exactly as Step 2 did on `preview`: read first, say what it destroys, one transaction with the stamp last, and read back. Until the merge deploys, production's `/api/v1/warmup` answers `degraded` with «База впереди кода», which is the window the right order makes.

- [ ] **Step 5: The merge** is the controller's, under the `github-pr` skill's five checks:
  1. CI is green on the exact head;
  2. `mergeable_state` is clean;
  3. the gates ran locally before the push;
  4. a milestone is attached;
  5. no review is waiting.

  `0019` must already be on production (Step 4).

- [ ] **Step 6: After the merge, read production.** A merge to `main` has not always deployed production by itself (#349).
```bash
curl -s https://lessons-ruddy-zeta.vercel.app/api/v1/warmup; echo
```
Expected: `status` `ok`, `schema` `0019` and `v2` `true`. A `degraded` answer reading «База впереди кода» means production still runs the code from before the merge: ask the owner to promote or redeploy the merge, then read again.

Then, after the next tick (cron-job.org calls one every five minutes), read the table through the Neon connector, read-only:
```sql
SELECT name, status, reason, since, checked_at, round_trip_ms FROM health_checks ORDER BY name;
```
Expected: four rows, each `checked_at` within five minutes:
- `schema` `ok`, at `0019`;
- `v2` `ok`;
- `diary_proxy` `ok` with an HTTP status and a round trip, because production has `DIARY_PROXY_URL`;
- `deploy` `ok` on this merge's commit, or `unknown` with its reason. «GitHub answered HTTP 403» is the shared limit; «Vercel's system environment variables are not exposed» is a setting for the owner (`docs/deploy.md`, «Monitoring»).

A `failing` row is a finding: file it as an issue before anything else. No rows after ten minutes means no tick reached the new code: read cron-job.org's history with the owner. Write what was seen into the controller's notes for the next close-out. Ask the owner to send `/health` once and say what it showed, because that is the screen's first sight of production.

---

## Questions for the owner

None of these blocks the work; each has a recommendation the plan already follows or can take later.

1. **Should #349 close with this pull request, or only once the `deploy` check has caught a real late deploy?** Recommended: close with it, as the plan does. The issue's own text names this check as the cure «for good», and it can be reopened if the check ever misses one.
   **Answered on 6 October 2026:** yes, close #349 with this pull request.
2. **A read-only GitHub token for the `deploy` check?** Unauthenticated, it shares sixty requests an hour with every Vercel function on the same address, and a refusal reads ❔. Recommended: not now. Add an optional `GITHUB_TOKEN` only if `/health` shows the check ❔ with «GitHub answered HTTP 403» more than now and then.
   **Answered on 6 October 2026:** left to the controller, who added the optional `GITHUB_READ_TOKEN` after the review of Task 4 found that an anonymous `304` is not free — it still spends one of the sixty an hour shared on Vercel's address, against a check that closes #349 on the strength of it. The owner set the token the same day.
3. **Sentry for Preview too?** Recommended: Production only at first. A preview's errors are this session's own experiments, and they would spend the same 5,000 a month.
   **Answered on 6 October 2026:** Production only, for now.
4. **cron-job.org's failure email: after how many failures in a row?** Recommended: three, which is fifteen minutes. One is a blip on a cold start; three is an outage.
   **Answered on 6 October 2026:** three failures in a row.

## What changed while it was built

The reviews of Tasks 4 and 6 changed what the code does in five ways, and this pull request's
documents (Task 8) say what is true now rather than what this plan assumed when it was written.

- **The order in the tick.** The self-check runs after the digests and the sweeps and before
  the diary keep-alive, not last: the keep-alive goes through the diary's proxy and, when it
  hangs, holds the request to its own hard stop, so after it the proxy check would have no
  time left to report the one incident it exists for. Built this way in Task 5 (`6ebea80`).
- **The time budget.** The network checks stop `SEND_RESERVE_SECONDS` (five seconds) before
  the self-check's own hard stop, so an alert always has a real chance to send, and a check
  given less than its full budget that then times out reads `unknown`, never `failing`, read
  from the exception's type rather than its wording. Found by Task 4's review of its own first
  commit (`de8dc68`, `1d63dcd`).
- **`GITHUB_READ_TOKEN`**, a new optional setting. With it the `deploy` check's `304`s are
  authorized and free, and the ceiling is 5,000 GitHub requests an hour instead of the sixty
  shared anonymously. Found by the same review: an anonymous `304` still spends one of the
  sixty against a check that closes #349 on the strength of it (`de8dc68`). The owner set it
  on 6 October 2026.
- **A malformed `SENTRY_DSN`.** `Settings` now judges its own shape and announces a
  set-but-unusable value as off, instead of letting `sentry_sdk.init` raise `BadDsn` at
  `app.main`'s import and take v1, v2, the webhook and the cron tick down together. Found by
  Task 6's review (`238efeb`), which also switched off release-health sessions and client
  reports and replaced the plain sample rate with `traces_sampler`.
- **`test_client_version_revision.py` pinned the schema head to `0018` by number.** It failed
  the moment `0019` moved the head, although nothing about `0018` had changed. Filed as #358
  and fixed in Task 3 (`3e615b1`): the test now asks only for `0018`'s own place in the chain,
  and `test_schema_version.py` stays the one pin.

## Self-review

- **Spec coverage.**
  - Part 1, the self-check: Tasks 3 to 5. The four checks, the three states, the table, the alert rule both ways, the reminder, unknown silent, a failing send that does not fail the tick, and the tick answering as before when every check raises.
  - Part 2, the external clock: Task 8's `docs/deploy.md` steps; the fallback workflow stays as it is.
  - Part 3, Sentry: Task 6. The setting off by default and never refused, the SDK imported only with the DSN, the FastAPI integration, the bot's errors, what is sent and never sent, 5 %, and the scrubbing test the design names.
  - Part 4, the screen: Task 7. Owner only, the button, `/health`, the seven blocks, read only, `render.clamp`, escaping.
  - Where things live: as the design lists, plus `bot/project_keyboard.py`, by the convention that each feature has a `*_keyboard.py` beside its `*_render.py`.
- **Placeholders.** `#HEADPIN`, `#PR`, `[AFTER-356]` and the bracketed numbers of Task 9 exist only once the controller files, opens or runs something. Every code step carries its code.
- **Types.**
  - `Result`, `Previous`, `Step`, `decide`, `message`, `claim_alert` and `run` are named alike in Tasks 4, 5 and 7.
  - `CheckRow` and `ProjectStats` alike in Task 7's service, renderer and tests.
  - `deployment()` and `STARTED_AT` alike in Tasks 4, 6 and 7.
- **Review Focus.** Each of its five lines has its test in the task that owns the code.
