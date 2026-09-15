# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project overview

"Дневник" (`lessons`) is a school diary for Russian schools: an Android app with a
resizable home-screen widget, a FastAPI read API, and a Telegram bot that **is** the admin
panel. One class is edited from the bot or from a linked phone, and the same rules apply to
both. **User-facing strings and most of `docs/` are Russian; code, identifiers and comments
are English.**

Three deliverables in one repository:

```
android/     Kotlin / Compose / Glance, five Gradle modules
server/      FastAPI + aiogram in one process, one database, Alembic migrations
api/         thin Vercel entry point that re-exports server/app/main.py
docs/        eight documents plus an index (docs/README.md), all current
```

There is no npm, no Node and no web frontend — with **one** deliberate exception:
`server/app/api/diary_web.py` serves a single server-rendered sign-in form at `/diary/signin`,
because a password typed into a Telegram chat is in the chat history, on Telegram's servers
and in that phone's backup, and deleting the message undoes none of it. No JavaScript, no
cookie, no build step. Do not grow it into a second admin surface: anything the bot can
express belongs in the bot.

`requirements.txt` at the root exists only because Vercel's Python builder does not read
`pyproject.toml` from a subdirectory — and it must stay level with it.

## Commands

Python 3.11+ (`.python-version` says 3.12, CI runs 3.12), JDK 21, Android SDK with
compileSdk 37. Gradle comes from the wrapper — `./gradlew` works on a fresh clone.

Server, from `server/`:

- `python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"` — setup
- **`ruff check app tests scripts migrations`** — exactly what CI lints; `ruff check .` from
  `server/` covers the same tree
- **`python -m pytest -q`** — 1024 tests, about four minutes
- `python -m pytest -q tests/test_schedule.py -k parity` — one file, one test
- `python -m uvicorn app.main:app --reload` — run it; add `--host 0.0.0.0` for a phone to
  reach it
- `python -m scripts.seed_demo` — demo class, join code `DEMO24`
- `DATABASE_URL='postgresql+asyncpg://…' .venv/bin/alembic upgrade head` — schema; never
  from inside a request

Android, from `android/`:

- **`./gradlew test`** — all JVM unit tests across the five modules
- `./gradlew :core:model:test --tests '*ScheduleEngineTest*'` — one module, one class
- **`./gradlew assembleDebug`** and **`./gradlew assembleRelease`** — CI builds both on every
  push, because R8 and resource shrinking are where "worked in debug" stops being true
- `./gradlew lint` runs the AGP Android lint; CI does not, so do not report it as a gate

CI (`.github/workflows/ci.yml`) is: ruff, pytest, `./gradlew test`, both assembles. Nothing
else. `apk.yml` builds an installable APK on demand or on a `v*` tag; `reminders.yml` is the
server's clock (see below). The workflows work — do not edit them casually.

## Architecture

Read `docs/architecture.md` before any cross-cutting change; it is current and it explains
the decisions, not just the layout.

**The bot writes, the API reads.** Telegram already solved identity, so there is no admin
web panel, no session cookies and no password reset. The cost is real and deliberate:
anything the bot cannot express does not exist. `server/app/services/` holds the rules
shared by the bot handlers and the `/api/v1/manage` endpoints — two thin shells over one
implementation, because two implementations of "rename a subject" disagree within a month.

Server modules:

- `models.py` — the whole SQLAlchemy 2.0 domain in one file
- `schedule.py` — template + overrides → concrete days. No FastAPI, no aiogram imports, and
  it must stay that way: that is why its tests run in seconds
- `api/` — `public.py` (read), `edit.py` and `manage.py` (write), `diary.py`, `cron.py`,
  `telegram.py` (webhook), `deps.py` (device-token auth)
- `bot/` — aiogram routers, roles, keyboards, renderers. The weekly template has **two**
  editors and both are wanted: `handlers/timetable.py` pastes a whole weekday (fastest way
  to enter a term), `handlers/editor.py` changes one lesson with buttons. They share one
  grammar (`services/timetable_io.py`) and one set of mutations
  (`services/timetable_edit.py`) — the editor's ‹ › pager and «⏱ Перемены» switch live in
  the callback payload, never in FSM state
- `providers/` — the two foreign services, each behind `client.py` / `mapper.py` /
  `models.py`. `petersburg/` is the electronic diary: nothing above `models.py` knows the
  words `p_educations[]` or `X-JWT-Token`. `dadata/` is the school directory, a search over
  ЕГРЮЛ because no downloadable register of Russian schools exists; without `DADATA_TOKEN`
  it refuses at the door and the bot asks for the name to be typed, exactly as the diary
  refuses without `DIARY_SECRET`

Android modules (`android/settings.gradle.kts`):

```
:core:model          pure JVM — domain types, ScheduleEngine, AlertPlanner
:core:designsystem   theme, typography, Compose components
:core:data           Room, Retrofit, repositories, sync, notifications
:widget              Glance home-screen widget
:app                 screens, navigation, ViewModels
```

Room is the single source of truth; the network only fills it. `:core:data` must **not**
depend on `:widget` — the sync worker tells the widget it has new data by broadcasting
`com.lumenpearson.lessons.action.DATA_SYNCED`, precisely so the dependency does not have to
be circular.

There is no Hilt. `Graph` is a small hand-written container a test can swap wholesale,
because the Glance widget and the WorkManager worker both need repositories from entry
points Hilt does not inject cleanly.

## Conventions already true in this codebase

- **Comments explain why, not what.** Read almost any file here: the comment above a
  decision says what would break without it. A comment that restates the line below it does
  not survive review.
- **Russian in user-facing strings, English in code and comments.** Both halves are load
  bearing. `values/` is Russian and is the source; `values-en/` is the translation.
- **Every Russian string has an English twin.** `app/src/test/.../ResourceTranslationTest.kt`
  reads both folders out of the source tree and fails on a name missing from `values-en/`,
  on a name only in English, on mismatched format arguments, and on an English `<plurals>`
  that is not exactly `one` + `other`. Android resolves names one by one, so a missing
  translation is not a missing screen — it is one Russian line in the middle of an English
  one, with nothing logged.
- **No commit without lint and tests green.** `ruff check` and `pytest -q` for the server,
  `./gradlew test` for Android; touch both halves and run both.
- **Versions are copied from a project that builds, never guessed.** The header of
  `android/gradle/libs.versions.toml` names where each one came from. The first CI run of
  this project failed on four invented versions that exist in no repository.
- **Commit messages are English sentences that say what the change makes the project do** —
  "Let the class be run from the phone, by the same rules as from the bot". No Conventional
  Commits prefix (none of the 178 commits has one), and the body explains the reasoning and
  names what is left uncovered. Unlike the owner's other repositories, this history does
  carry a `Co-Authored-By: Claude …` trailer; keep doing what the history does.
- **Say what is not covered.** The README has a "Честный статус" section and it is honest on
  purpose. "Написано, не запускалось" is a legitimate status; a claim that something was
  verified when it was not is not.

## What will bite you

- **The server has no clock.** On Vercel nothing runs between requests, so there is no
  scheduler, no background task and no `asyncio` loop that survives a response. The morning
  and evening digests are driven from outside by `.github/workflows/reminders.yml`, which
  calls `GET /api/v1/cron/tick` every five minutes with `X-Cron-Secret`. The endpoint marks
  a digest sent **before** sending it, and works from "what is due and not yet sent today"
  rather than "did the last tick fire" — so a tick that dies halfway does not send twice and
  a tick ten minutes late still sends. Anything you are tempted to schedule in-process
  belongs in that tick instead.
- **The widget's size ladder has twelve rungs, and the count is the point.**
  `WidgetSizeClass` (in `:widget`) declares twelve breakpoints because the launcher and
  Glance both pick the **nearest** breakpoint by squared distance, not the largest that
  fits. With five rungs, a 4-cell-wide widget taller than about 471 dp landed on the narrow
  110×300 column and drew half a screen of one column. Do not remove an intermediate rung to
  tidy the enum; `WidgetSizeClassTest` reproduces the launcher's rule and will say so.
- **A phone gets into a class two ways, and one of them is not read-only.** The
  class code (eight characters) buys an anonymous, read-only token. A personal
  code from the bot's «📱 Подключить телефон» (ten characters, fifteen minutes,
  one phone) buys a token already linked to the account that minted it, so it
  writes with that account's role — checked per request, never cached. Both go
  into the same `code` field of the same `POST /api/v1/join`; they cannot
  collide because the lengths differ, and the class code is looked up first.
  `SchoolClass.join_mode` decides whether the class code opens anything at all;
  switching it revokes no device, in either direction, and three screens promise
  that out loud. A `403` from `/join` means the class takes invites only — it is
  deliberately *not* counted against the throttle, because that caller had a
  real code.
- **There are two independent bearer tokens.** The device token from `POST /api/v1/join`
  (`api/deps.py:current_device`) and the diary session token from
  `POST /api/v1/diary/login` (`api/diary.py:current_diary`). A phone can be joined to a class
  without a diary account and signed in to a diary without a class; neither implies the
  other, and one token meaning both would have to be re-minted whenever either half changed.
  They go in the same `Authorization: Bearer` header on different endpoint families — check
  which one an endpoint depends on before moving it.
- **The diary needs `DIARY_SECRET` or it does not run.** The Petersburg session token is
  the one stored credential that cannot be a hash (it is replayed upstream on every call),
  so it is sealed with Fernet — `app/crypto.py`. No key means the feature refuses at the
  door rather than falling back to plaintext, because a silent fallback is invisible and
  deployments stay in that state for years. `tests/conftest.py` sets one; a test that wants
  the feature *off* patches it away.
- **Run the migration BEFORE the merge that needs it.** A merge to `main` deploys itself;
  `alembic upgrade head` is run by hand. Between them is a window where the code knows a
  column the database does not, and that window has already taken production down: the new
  columns on `classes` landed first, nothing failed at startup, and the first ORM read of a
  class did — which for the bot is the middleware, so every update died. Every revision
  after `0001` is additive, so applying it to the *running* code is safe; the other order
  never is. `GET /api/v1/warmup` answers `{"status": "degraded", ...}` when the database is
  behind and names both revisions (`app/db.py:EXPECTED_REVISION`, pinned to the real head by
  `tests/test_schema_version.py`), and `{"status": "degraded", "detail": "База впереди
  кода…"}` in the window the correct order creates. `/api/v1/health` deliberately opens no
  connection, so it cannot tell you this.
- **Apply migrations through the Neon connector, from here.** The owner does not run
  `alembic upgrade head` by hand and this session has no `DATABASE_URL`; the project is
  `proud-math-08001107` on the Neon MCP server, and `0005` through `0011` were all applied
  that way. It is not alembic running — it is the revision's DDL executed as one
  transaction, with `alembic_version` stamped in the same transaction — so three things
  follow. Take the DDL from the model rather than writing it out: `CreateTable(...).compile(
  dialect=postgresql.dialect())` prints exactly what `create_all` would build, which is what
  the revision is supposed to produce. Read the database's state first and stamp it last, so
  a half-applied revision cannot claim to be whole. And say what a revision destroys before
  running it — `0006` deletes every row of `diary_sessions` on purpose, and that is a
  sentence the owner needs *before* the transaction, not after.
- **Migrations are Alembic and production is already at `0011`.** `0001` is a guarded
  `create_all`, `0002` widens Telegram ids to 64 bits, `0003` adds tasks/reminders/links,
  `0004` adds diary sessions, `0005` adds `bell_schedules.canteen_after_index`, `0006`
  encrypts the diary credential (and **deletes** the existing sessions, on purpose) and adds
  the per-member diary columns, `0007` adds the two class foreign keys `0006` left out,
  `0008` gives a class a number (1–11) and cuts its year into четверти or полугодия,
  `0009` adds the corrections a family lays over the diary, `0010` gives a class its join
  mode and adds the personal connect codes, and `0011` tightens two `diary_overrides`
  timestamps `0009` left nullable while the model builds them `NOT NULL` — a no-op on this
  database, because the DDL for `0009` came from the model, and not one on a deployment
  that ran the chain through alembic.
  Nothing after `0001` may use `create_all`.
  Beware the enum: `SAEnum(SomeStrEnum)` stores the member **name**, so a `server_default`
  written as `.value` is a string the ORM cannot read back — which on `classes` is a
  `LookupError` in the bot's middleware, i.e. every update at once. `0010` nearly shipped
  exactly that. A model change needs
  a revision — a live database will not grow a column on its own, and lifespan `create_all`
  runs only for local SQLite.
- **Time is naive local wall time, in the class's zone, not the server's.** A bell rings at
  08:30 whether or not the clocks changed. `SchoolClass.timezone` carries the zone; the
  server decides "today" through `school_class.tz` and the client through
  `Timetable.nowAtSchool()`. `LocalDateTime.now()` and `ZoneId.systemDefault()` on the
  Android side are almost always a bug — that pair was two of the fourteen defects the audit
  confirmed.
- **AGP 9 compiles Kotlin itself.** Applying `org.jetbrains.kotlin.android` in an Android
  module is a hard build failure, not a warning. Pure-JVM modules still use `kotlin.jvm`.
- **The bot runs two ways from one dispatcher.** Long polling inside the API process
  (`app/main.py` lifespan) locally, webhook on serverless, `build_dispatcher()` shared. FSM
  state lives in the database (`app/fsm_storage.py`), not in memory, because each update may
  hit a fresh process. `RUN_BOT=false` starts the API alone — that is what the tests use.

## Notes

- **An enum column stores the member NAME.** `SAEnum(SomeStrEnum)` writes
  `OPEN`, not `open` — check `bot_users.role` in the live database if you doubt
  it. A `server_default` spelled as `.value` therefore lands an unreadable
  string on every existing row, and the first ORM read of one raises
  `LookupError` — which on `classes` is the bot's middleware, i.e. every update
  at once. `0010` was written that way and caught before it was applied;
  `tests/test_join_modes.py` now holds all three sides of it.
- **Another agent may be working in this tree.** Check `git status` before you touch a file
  you did not open, and do not revert someone else's uncommitted work.
- **`HANDOVER.md` at the root says where the work stands** — the branches, what the last
  session finished, what it deliberately left alone and what nothing has verified. It is
  working state, not part of `docs/`, so it is stale the moment it stops being updated:
  re-check the PR and CI before trusting it, and update it when you finish a batch.
- **Read a file before editing it; grep every caller before changing a function.** The
  audits in `docs/design.md` exist because a conclusion drawn from call sites was wrong.
- Secrets never enter the repository: `BOT_TOKEN`, `OWNER_IDS`, `WEBHOOK_SECRET`,
  `CRON_SECRET` live in `server/.env` or the host's environment; the release keystore and
  its passwords come from `LESSONS_KEYSTORE_*` environment variables or
  `~/.gradle/gradle.properties`. Redact them as `<redacted>` in issues, logs and reports.
- The documentation index is `docs/README.md`. If a change makes a document wrong, fix it in
  the same batch — `docs/widget.md` and `docs/bot.md` were each rewritten once because they
  had drifted from the code, and that is more expensive than keeping up.
