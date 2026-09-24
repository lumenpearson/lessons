# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project overview

"Дневник" (`lessons`) is a school diary for Russian schools: an Android app with a
resizable home-screen widget, a FastAPI read API, and a Telegram bot that **is** the admin
panel. One class is edited from the bot or from a linked phone, and the same rules apply to
both. **The product speaks Russian; everything written about the project is English.**
User-facing strings live in `values/` (Russian, the source) and `values-en/` (the
translation), and the reader chooses the language in the app. The documentation, the code,
the identifiers, the comments, the commit messages and the pull request descriptions are
English, so that anybody can read them — and where any of those quotes a button, a menu path
or an error the user will see, it quotes it in Russian, in guillemets, because that is what
is on the screen.

Three deliverables in one repository:

```
android/     Kotlin / Compose / Glance, five Gradle modules
server/      FastAPI + aiogram in one process, one database, Alembic migrations
api/         thin Vercel entry point that re-exports server/app/main.py
docs/        nine documents plus an index (docs/README.md), all current, all English;
             docs/diaries/ holds the per-platform reference pages of diaries.md
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
- **`pytest -q -n auto`** — 1610 tests in about two minutes, and **the exact command
  CI runs**. Not `python -m pytest`, which is what this line used to say: the `-m`
  form puts the current directory on `sys.path` and the bare one does not, so a
  `from tests.test_api import …` in a test file passes locally and fails at
  *collection* on CI with «No module named 'tests'» about a directory that is plainly
  there. That shipped once. `tests/test_test_imports.py` now refuses a test module
  that imports another one at all — a shared fixture belongs in `conftest.py`, which
  pytest loads by path rather than by import
- **`python -m mypy`** — one question, of all 84 modules, in seconds: does anything reach
  for an attribute its type does not have? Configured in `pyproject.toml`, where every
  other error code is switched off by name with its count and its reason. Not in CI — the
  owner has not been asked — but run it before you push server code
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

The app is set in **two** bundled faces, both under
`core/designsystem/src/main/res/font/` and both carrying one axis, `wght`:
`google_sans_flex.ttf` draws Latin and digits, `onest.ttf` draws Cyrillic, and
`FallbackTypeface.kt` chains them with `Typeface.CustomFallbackBuilder` because neither a
Compose `FontFamily` nor a font-family XML chooses by coverage. API 26–28 cannot express a
custom chain and get Onest alone. Google Sans Flex has **no Cyrillic at all**, which is why
the second file exists: while it was alone, every Russian word came from the device's
fallback beside digits from the bundle. `FontAxisTest` holds all three halves — the pair
draws Russian, neither carries an axis nothing varies, and no file is bundled unnamed.

CI (`.github/workflows/ci.yml`) is: ruff, pytest (`-n auto`), `./gradlew test`, both
assembles. Nothing else. `apk.yml` builds an installable APK on demand or on a `v*` tag;
`reminders.yml` is a fallback clock, not the clock (see below). The workflows work — do
not edit them casually. The repository is public, so standard runners cost nothing; what
the workflows still carry from the months it was private is in `docs/build.md`, "Actions
minutes", and it is worth reading before undoing any of it — `-n auto` is there because a
full run was twenty-one billed minutes, and a short artifact retention because a full
artifact store reported a passing build as red. **Retention is no longer asked for in any
workflow**: this repository's own setting is lower than anything they requested, so every
`retention-days:` was silently reduced under a warning while three documents went on
quoting the number that had been asked for.

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
- `di.py` — the dishka container both shells draw from: `Settings` and the session
  factory at app scope, one `AsyncSession` per HTTP request or Telegram update. An
  endpoint asks with `session: FromDishka[AsyncSession]`; the bot's `ContextMiddleware`
  opens the update's scope itself — **not** `setup_dishka`, which registers one middleware
  on every observer and so opened two *sibling* scopes per message, either of which could
  hand out a second session nothing commits — and it reads `container()` per update rather
  than capturing one, because `api/telegram.py` caches the dispatcher for the life of the
  process. It still commits there, because for a handler that is the finish line. It is
  built with
  `STRICT_VALIDATION`, so a second provider for a type is an error rather than a silent
  shadowing. Do **not** import `dishka.integrations.aiogram` from it — that pulls aiogram
  onto the cold-start path of every request, which is the thing `main.py` and
  `api/telegram.py` already go out of their way to defer
- `api/` — `public.py` (read), `edit.py` and `manage.py` (write), `diary.py`, `cron.py`,
  `telegram.py` (webhook), `deps.py` (device-token auth), `routing.py` (the route class
  every router here is built with: dishka's wrapper carries its own globals, so under
  `from __future__ import annotations` FastAPI could not resolve an endpoint's
  `-> Response` and took the name for a response model — a 204 route made that an
  `AssertionError` at import, and every route without an explicit `response_model=` would
  have had a schema built from a string)
- `bot/` — aiogram routers, roles, keyboards, renderers. The weekly template has **two**
  editors and both are wanted: `handlers/timetable.py` pastes a whole weekday (fastest way
  to enter a term), `handlers/editor.py` changes one lesson with buttons. They share one
  grammar (`services/timetable_io.py`) and one set of mutations
  (`services/timetable_edit.py`) — the editor's ‹ › pager and «⏱ Перемены» switch live in
  the callback payload, never in FSM state
- `providers/` — the two foreign services, each behind `client.py` / `mapper.py` /
  `models.py`. `petersburg/` is the electronic diary: nothing above `models.py` knows the
  words `p_educations[]` or `X-JWT-Token`. `dadata/` is the school directory, a search over
  the ЕГРЮЛ company register, because no downloadable register of Russian schools exists;
  without `DADATA_TOKEN`
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

**A window is one school year, and a class holds several of them.** `synced_window` says
which years this phone has fetched, `replaceWindow` replaces one and leaves the rest, and
`syncedYears` is what the screens read to tell «нет уроков» from «ещё не загружено». The
table exists because those two cannot be told apart by counting rows — a class made in
March has none before it either way. Three years are kept per class; what goes is the one
furthest from the year holding today, never that year, and its `ETag` goes with its rows.
Only the sync of the current year writes the lookahead row, and the ranged deletes spare
it — but **nothing writes it today**, and the claim that used to stand here is what sent an
audit looking. The server resolves `next_school_day` at most 21 days past the last lesson
*inside the window that was asked for*, and a window running to the school year's end
leaves only June, which is out of season for every class — `services/terms.py` refuses a
term past 31 May, so there is no class it could be in season for. What answers «what is
next» across a gap is `TimetableDao.firstTeachingDayAfter` over the cached year, which is
now a whole year rather than a fortnight; the row's residue is the last days of May.
`docs/architecture.md` says what a server change would have to be, and why it was not made.

There is no Hilt. `Graph` is a small hand-written container a test can swap wholesale,
because the Glance widget and the WorkManager worker both need repositories from entry
points Hilt does not inject cleanly.

## Conventions already true in this codebase

- **Comments explain why, not what.** Read almost any file here: the comment above a
  decision says what would break without it. A comment that restates the line below it does
  not survive review.
- **Russian in user-facing strings, English in everything written about them.** Both halves
  are load bearing. `values/` is Russian and is the source; `values-en/` is the translation;
  documentation, comments, commit messages and pull request descriptions are English. A
  quotation of product text keeps its Russian and goes in guillemets — «⏱ Сокращённые
  уроки», «Алгебра», «9А» — so a reader can tell a quotation from prose.
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
  Commits prefix (none of the 288 commits has one), and the body explains the reasoning and
  names what is left uncovered. Unlike the owner's other repositories, this history does
  carry a `Co-Authored-By: Claude …` trailer; keep doing what the history does.
- **Every pull request carries a milestone**, set when it is opened. Nothing in a session
  here can create a milestone or even list one — when none of the nine fits, ask the owner
  to create it and hand over the title and description already written, rather than
  inventing a version or leaving the pull request bare. The `github-pr` skill has the
  numbers, the one tool that sets them, and the two ways this was got wrong first. **The
  ninth is `v0.8.0 — On a device` and it is the current one**; issues #109–#117 are on it,
  and it is the first milestone whose work needs an emulator or a phone.
- **A defect that is found gets an issue, always, and before it gets a fix.** The rule is
  new and it is not optional: the moment an audit, a review, a CI failure or a reader finds
  something wrong, it becomes an issue of its own — title saying what is broken rather than
  what to do about it, body carrying the failure scenario, `type:bug`, the `area:` it lives
  in, a `status:`, and the milestone the fix will land in. **Then** the fix. A defect fixed
  inside a batch and described only in a commit body is invisible the day somebody asks
  «has this happened before», and eight bug sweeps' worth of that had to be back-filled by
  hand in #128 to make this repository answerable at all.
  - **The issue and the pull request are tied together**, and the tie goes in the pull
    request: `Closes #NN` (or `Fixes #NN`) in its body. GitHub then links them both ways and
    closes the issue when the pull request merges, so neither side can be left behind. One
    pull request may close several; say each on its own line.
  - **Labels are created by using them** — `issue_write` with `method="create"` makes a
    label that does not exist yet. It does **not** when `parent_issue_number` is passed:
    that path validates the labels first and fails on an unknown one. Create the issue
    plainly, then link it.
  - **Adding it to the Project board cannot be done from a session here** — Projects v2 is
    GraphQL-only and this toolset is REST. What a session controls is the labels, and the
    board's auto-add workflow filters on them; a board without one is the owner's click.
    Never report an issue as «added to the project» on the strength of having labelled it.
- **There are issues now, and until #85 there were none.** Forty-two were opened in one go
  to give the history and the backlog a shape the milestones alone could not: twenty-three
  closed, describing what was built and what each bug sweep found, and nineteen open, which
  are the whole of what is left. Read the open ones before planning a batch — several say
  what was *deliberately* left and why, so that a later session does not re-discover a
  decision as if it were an oversight. The labels are `type:` (feature, bug, chore,
  research, decision, epic), `area:` (android, widget, server, bot, db, ci, docs, design,
  data), `status:` (now, next, someday, done) and `needs:` (device, owner). **A session
  here cannot create a GitHub Project board** — Projects v2 is GraphQL-only and this
  toolset is REST — so the board, if there is one, is the owner's, and these labels are
  what a saved view filters on.
- **A release explains itself, fix by fix.** When a version is tagged, the notes name every
  change in it with **its issue and its pull request** — «what was wrong, what it does now,
  #NN / #MM» — rather than a list of commit subjects. The milestone is what says which
  changes belong to the release, so it is set before the tag rather than after, and the tag
  is the version the milestone is named for. The `release` skill has the order and what the
  notes may not claim. Nothing in this repository has ever been tagged or released, so the
  first one sets the pattern for every one after it.
- **Say what is not covered.** The README has an "Honest status" section and it is honest on
  purpose. "Written, never run" is a legitimate status; a claim that something was
  verified when it was not is not.

## What will bite you

- **The server has no clock.** On Vercel nothing runs between requests, so there is no
  scheduler, no background task and no `asyncio` loop that survives a response. The morning
  and evening digests are driven from outside by whoever calls `GET /api/v1/cron/tick`
  with `X-Cron-Secret`. The endpoint marks a digest sent **before** sending it, and works
  from "what is due and not yet sent today" rather than "did the last tick fire" — so a
  tick that dies halfway does not send twice and a tick ten minutes late still sends.
  Anything you are tempted to schedule in-process belongs in that tick instead.
  **The caller is an external cron service, not GitHub.** `.github/workflows/reminders.yml`
  asked for a tick every five minutes and delivered 6.7 a day over five days of
  measurement, in gaps of two to six and a half hours — GitHub runs schedules on a
  best-effort basis and that is what the effort came to on a private repository. It still
  asks for five minutes, because public runners cost nothing and the throttling may well
  differ, but until that has been measured again it is the fallback: the promise the bot
  makes («в течение примерно пяти минут») is kept by the external cron in
  `docs/deploy.md`.
- **A deployment refuses to start rather than keep a local default.** `get_settings()`
  raises `DeploymentNotConfigured` when `VERCEL` is set and any of `DATABASE_URL`,
  `BOT_TOKEN`, `WEBHOOK_SECRET`, `RUN_BOT`, `OWNER_IDS` or `TIMEZONE` is missing or
  unusable, and it lists every one of them at once, because finding the next costs another
  deploy. This is not tidiness: `DATABASE_URL` set for one Vercel environment and not the
  other left the SQLite default standing, and the only thing anybody saw was
  `ModuleNotFoundError: No module named 'aiosqlite'` out of SQLAlchemy's sqlite dialect —
  a message naming neither the setting, nor the environment it was missing from, nor this
  project. The check hangs on `VERCEL` because the platform sets it about itself; guessing
  "this looks like production" anywhere else would one day refuse to start on somebody's
  laptop. **Do not add an optional setting to that list.** `DIARY_SECRET`, `DADATA_TOKEN`,
  `PUBLIC_BASE_URL`, `BOT_USERNAME` and `CRON_SECRET` are empty by design and each already
  refuses in view of whoever it concerns; they are logged as switched off at startup
  (`Settings.disabled_features`), which is a different decision from making them mandatory.
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
  `alembic upgrade head` by hand and this session has no `DATABASE_URL`; the project is the
  one named `lessons` on the Neon MCP server — the account has two, so read the name rather
  than guessing an id, and the id itself stays out of the repository — and `0005` through
  `0014` were all applied that way. It is not alembic running — it is the revision's DDL executed as one
  transaction, with `alembic_version` stamped in the same transaction — so three things
  follow. Take the DDL from the model rather than writing it out: `CreateTable(...).compile(
  dialect=postgresql.dialect())` prints exactly what `create_all` would build, which is what
  the revision is supposed to produce. Read the database's state first and stamp it last, so
  a half-applied revision cannot claim to be whole. And say what a revision destroys before
  running it — `0006` deletes every row of `diary_sessions` on purpose, and that is a
  sentence the owner needs *before* the transaction, not after.
- **Migrations are Alembic and production is at `0014`, which is the head.** `0001` is a guarded
  `create_all`, `0002` widens Telegram ids to 64 bits, `0003` adds tasks/reminders/links,
  `0004` adds diary sessions, `0005` adds `bell_schedules.canteen_after_index`, `0006`
  encrypts the diary credential (and **deletes** the existing sessions, on purpose) and adds
  the per-member diary columns, `0007` adds the two class foreign keys `0006` left out,
  `0008` gives a class a number (1–11) and cuts its year into quarters or half-years,
  `0009` adds the corrections a family lays over the diary, `0010` gives a class its join
  mode and adds the personal connect codes, and `0011` tightens two `diary_overrides`
  timestamps `0009` left nullable while the model builds them `NOT NULL` — a no-op on this
  database, because the DDL for `0009` came from the model, and not one on a deployment
  that ran the chain through alembic. `0012` does the same for eight more timestamps in
  seven tables that `0003`, `0004` and `0006` left loose — and on this database it was
  **not** a no-op: all eight really were nullable, and all eight held zero nulls, so it
  tightened them and rewrote nothing. `0013` adds `uq_homework_per_subject_per_day`
  and was **applied after the merge, not before** — see the constraint rule
  below; on this database it deleted nothing, because `homework` held no rows
  and therefore no duplicates. `0014` widens `day_overrides.kind` from
  `VARCHAR(9)` to `VARCHAR(10)`, because `DayKind` gained `SELF_STUDY` and a
  `SAEnum` stores the member **name** — the column is exactly as wide as the
  longest one, so adding a kind is a migration rather than a line. It is the
  ordinary additive shape and went on **before** the merge; it rewrites no row
  and every existing value stays what it was.
  Nothing after `0001` may use `create_all`.
  Beware the enum: `SAEnum(SomeStrEnum)` stores the member **name**, so a `server_default`
  written as `.value` is a string the ORM cannot read back — which on `classes` is a
  `LookupError` in the bot's middleware, i.e. every update at once. `0010` nearly shipped
  exactly that. A model change needs
  a revision — a live database will not grow a column on its own, and lifespan `create_all`
  runs only for local SQLite.
- **A constraint migrates in the opposite direction from a column.** The rule
  above — migration first, merge second — is about code that knows a column the
  database does not, and it is right for every additive revision in this chain.
  A `UNIQUE` or a `NOT NULL` is the other way round: it is the **old** code that
  breaks against it, with an `IntegrityError` nobody catches where a moment
  earlier there was a duplicate. `0013` is that shape and says so in its own
  docstring — it went on *after* the merge of PR #45, and `services/homework.py`
  is written to be correct with or without it so the window in between behaved
  exactly like production did before. `0012` is the safe shape (eight timestamps that already
  hold no nulls) and was applied the usual way, before the merge. Read the
  database before either: `0012` turned out to be a real fix rather than the
  no-op `0011` was, because all eight columns really were nullable there.
- **The weekly template stops when the school says it stops, and twice it did
  not.** `SCHOOL_YEAR_END_MONTH` is 5 and `schedule.py`'s comment says June
  onwards must not repeat the template; nothing read it, so every summer weekday
  drew a full day on the phone, in the widget, in the calendar feed and in the
  morning digest — whose own rule about staying silent on an empty day could
  never fire, because the day was never empty. That was the first half, and
  `_resolve_day` started asking `school_year_bounds`.
  The second half is that **the constant is not the school's answer — the terms
  are.** A class whose «🗓 Четверти» said the half-year ended on 28 May still
  drew lessons on the 29th, the 30th and the 31st, because the dates an admin
  typed decided the term's *name* and nothing else, and 31 May is what
  `SCHOOL_YEAR_END_MONTH` will always mean. `_resolve_day` now asks
  `schedule.off_reason_for`, which reads the class's own terms for
  that year and falls back to `school_year_bounds` only when the year has none.
  The gaps **between** terms are out of season by the same rule, which is how
  the autumn holidays get marked by moving two dates rather than nine days: the
  conventional bounds are contiguous, so a class that never touched them is
  unaffected. A day somebody marked by hand keeps its kind and note, and events
  and homework are kept either way: it is the lessons that are out of season,
  not the day.
- **A lesson number needs a bell of its own number, everywhere a lesson is
  written.** The resolver takes a lesson's times from the bell row of the same
  number and drops what has none, so a row at a number the day does not ring is
  stored, logged, announced and drawn nowhere. Three ways in had to learn this
  separately: the week import, the button editor, and — later — the substitution
  (`api/edit.py`, `bot/handlers/content.py`) and the bot's single-day paste,
  which used to write the template itself instead of going through
  `services/structure.apply_timetable`. Check with `timetable_edit.can_ring`,
  and for a dated write use `rung_indexes_on`, because a shortened day points
  at a shorter schedule than the class's usual. Two more ways in were closed
  later: **deleting** a lesson closed the gap in the numbering whatever it
  landed on (bells at 1, 2, 4 lost the fourth lesson onto a third slot that
  rings nothing — `remove_lesson` now renumbers only when every moved lesson
  still rings), and a day could be pointed at a bell schedule with **no rows**,
  which draws nothing at all under a card saying «⏱ Сокращённые уроки» — both
  `api/edit.day_put` and the bot refuse that now. And when you report what was
  dropped, count the **rows**, not the numbers: `apply_timetable` hands back
  (weekday, number) pairs, because one number under two weekdays — or under
  «чёт» and «нечёт» in one day — is two lessons nobody will see.
- **The diary sign-in ticket pays for an attempt, and an unreadable answer is
  one.** `api/diary_web` spends the ticket before the sign-in so that whoever
  holds the URL cannot sit and guess against the upstream from our address. Only
  `UpstreamUnavailable` — a transport failure or a 5xx, where nothing ever
  looked at what was typed — hands it back. A 200 of HTML must not: a login form
  on Yii refuses a password with the same bytes a captcha arrives in, and this
  diary has never been opened for real, so from here the two are
  indistinguishable. The page says «дневник ответил непонятно» instead of
  blaming the password, which is the part that was actually broken.
- **Time is naive local wall time, in the class's zone, not the server's.** A bell rings at
  08:30 whether or not the clocks changed. `SchoolClass.timezone` carries the zone; the
  server decides "today" through `school_class.tz` and the client through
  `Timetable.nowAtSchool()`. `LocalDateTime.now()` and `ZoneId.systemDefault()` on the
  Android side are almost always a bug — that pair was two of the fourteen defects the audit
  confirmed.
- **`Modifier.pointerInput(Unit)` keeps the lambdas it started with, for ever.** The
  element compares equal on its keys alone, so a recomposition never even hands the node the
  newer block, and the coroutine reading the finger goes on calling whatever it closed over
  on the composition that created it. A state read through a delegate survives that; a plain
  `val` computed in the caller's composition does not. The toolbar's reorder drag computed
  its landing slot as such a `val`, so every drop asked where the tab had been *before* the
  finger moved — `moveItem` refused the out-of-range index and the order came back unchanged.
  It looked right the whole way, because the icons slide from a value recomputed every frame;
  only the drop was stale, and the drop is the part that is remembered. Wrap every callback a
  gesture detector will call in `rememberUpdatedState` — `text/Corrections.kt` already did,
  which is what makes it a rule rather than a discovery. Do **not** key the `pointerInput` on
  something that changes instead: that cancels the gesture under the finger. And a test that
  only asks which callback fires, or only asks the arithmetic, will not see it — the question
  is whether the number the gesture computes is the number it reports, and the way to ask it
  without measuring Robolectric's densities is a drag so long it parks at the end under any
  of them (`ToolbarDragTest`).
- **A Compose test that holds the clock must also send the snapshot notification.**
  `MarqueeText` runs `Int.MAX_VALUE` iterations, so Compose's clock is never idle and
  `waitForIdle` — which every assertion calls into — **hangs rather than fails**. The
  answer is `mainClock.autoAdvance = false` and advancing frames by hand, and that buys a
  second trap: a write made from the test thread (`ScrollState.dispatchRawDelta`, or a
  `mutableStateOf` the composition reads) lands in the global snapshot and **nothing sends
  the apply notification**, so neither the recomposer nor `snapshotFlow` ever wakes and the
  value arrives after the last assertion has read it. Advancing frames does not help at any
  count — twenty still read zero. Every `settle()` here calls
  `Snapshot.sendApplyNotifications()` first. `LazyListState.scrollToItem` happens to escape
  it, because `forceRemeasure` runs a measure pass inside a snapshot of its own, which is
  exactly why the ribbon half of one test looked right while the list half did not.
  The third trap in the same family: `performScrollTo` drives the scrollable's animation, so
  with the clock held it moves nothing and every node below the fold reports «not
  displayed», which is indistinguishable from the element being absent. Give the test a tall
  window instead.
- **`compose-stability.conf` is a promise, and a `var` in `:core:model` breaks it.** The file
  tells the Compose compiler that `java.time.*` and the whole domain package are stable,
  because neither is compiled by the Compose plugin and one unknown field makes every class
  holding it unstable — that one field was `LocalDate`, and it condemned `TodayUiState`,
  `WeekDayUi`, `HomeworkUiState` and the diary's day and range along with it. What a false
  promise costs is invisible: nothing fails to build and nothing throws, Compose simply stops
  comparing the value and the screen keeps the old one. `StabilityPromiseTest` reads
  `:core:model`'s own source and fails on the first `var`; if a type there has to become
  mutable, move it out of the module and take the package off that file rather than leaving
  both standing. The file itself says at length what is deliberately *not* promised —
  `kotlin.collections.List` and `:core:data`'s value types — and why each would be a lie.
  Re-measure with `reportsDestination` in a module's `composeCompiler` block and a
  `--rerun-tasks` compile; it is left out of the build on purpose, because it writes reports
  on every build and the question is asked rarely.
- **AGP 9 compiles Kotlin itself.** Applying `org.jetbrains.kotlin.android` in an Android
  module is a hard build failure, not a warning. Pure-JVM modules still use `kotlin.jvm`.
- **The bot runs two ways from one dispatcher.** Long polling inside the API process
  (`app/main.py` lifespan) locally, webhook on serverless, `build_dispatcher()` shared. FSM
  state lives in the database (`app/fsm_storage.py`), not in memory, because each update may
  hit a fresh process. `RUN_BOT=false` starts the API alone — that is what the tests use.

## Notes

- **A message Telegram will not deliver is a screen that says nothing.** The
  ceiling is 4096 characters after entity parsing, and the whole message is
  refused rather than clipped: the homework digest had no bound and a fortnight
  of three assignments a day came to 5371, so «📝 Домашнее задание» answered «что-то
  пошло не так» and `/homework` — a plain `answer`, with no callback to
  apologise on — answered nothing at all. Every renderer that grows with the
  data carries a budget (`WEEK_TEXT_LIMIT`, `TASK_LINES_MAX`,
  `HOMEWORK_DIGEST_LIMIT`, `manage_render.clamp`) and says «… и ещё N». Cut a
  string **before** escaping it: cutting after can leave «&am», which is a
  refused message of its own.
- **Everything from outside is escaped before it goes into a message.** The bot sends
  HTML, and Telegram refuses the **whole message** on a stray `<` rather than damaging one
  row — so an unescaped string does not produce a broken line, it produces a blank screen
  and no error anybody sees. The strings that come from outside are: anything the
  Petersburg diary sends (subject, room, teacher, topic, homework), anything typed into
  the bot or pasted into the timetable grammar (a subject really can be «Алгебра <7>»),
  and anything out of the schools registry. `render.py` always did this; `diary_render.py`
  and `editor_render.py` never did, and both shipped that way.
  Two related traps in the same family: `plural(n, …)` already contains the number, so
  `f"{n} {plural(n, …)}"` prints «10 10 минут» — three callers had it, and one had a test
  that passed because «10 минут» is a substring. And `answerCallbackQuery` takes no parse
  mode, so a card built for a message shows its own tags in an alert; run it through
  `editor_render.as_alert` instead — which also cuts at 200 characters, because past
  that Telegram answers 400 and the press answers nothing at all.
- **A list page and the keyboard under it read the same number.** `manage_render`
  declares `SUBJECTS_MAX`, `BELLS_MAX`, `DEVICES_MAX` and `LIST_MAX`, and
  `manage_keyboards` builds its rows from those same names. While there were two
  numbers, three pages of four drew rows no button could reach and «… и ещё N» said
  nothing, because it counted from the renderer's number. The values differ on
  purpose — a bell schedule's row carries three buttons and twelve lines of times, a
  subject's one of each — so do not "tidy" them into one constant; the rule is that
  what is drawn is what can be pressed, and
  `test_no_list_page_draws_a_row_the_keyboard_cannot_reach` holds it. Nothing
  paginates, so past the cap a row is only a number.
- **Callback data is whatever the client sent, and a bare conversion on it is not
  a small bug.** `int(callback_data.value)` does not refuse a press it cannot parse
  — it raises out of the handler, so `callback.answer()` is never reached and the
  button keeps its spinner until Telegram gives up. Every handler module carries a
  guard for this: `_int_or_none` in `manage.py`, `tasks.py` and `access.py`,
  `_date_or_none` in `calendar.py` and `content.py`, `_role_or_none` in
  `access.py`, `_kind_or_none` and `_index_or_none` in `content.py`, and
  `shift_days`/`shift_weeks` in `keyboards.py` for the offsets (`timedelta(
  days=999999999)` is an OverflowError, not a far-away day). Check the value
  **where it is picked**, not where it is finally read: a value carried through
  three questions raises in front of somebody who has just typed a time and a
  title, and looks like their answer was the problem.
- **Two screens can match one press.** Both role pickers send a `RolePick`, and the
  only thing telling them apart is that the invite flow leaves `target` empty. While
  the invite handler's filter was a bare `RolePick.filter()` it swallowed both,
  because it is registered first — so a role pressed on an older «Новая роль» card
  created a phone invite and said so in a sentence about the number. Split a shared
  payload on a field, never on registration order;
  `test_the_two_role_pickers_never_match_the_same_press` holds it.
- **`ResourceTranslationTest` covers every module that ships strings**, not just
  `:app` — `:core:data`, `:core:designsystem` and `:widget` have their own
  `values/` and went unguarded for a long time, which is how the countdown on the
  home screen stayed Russian under an English caption. It discovers the modules
  rather than listing them, and counts a `<plurals>`' arguments per form (Russian
  has four forms and English two; over the concatenated text they can never agree).
  What it cannot see is a Russian string written into Kotlin, because that word is
  in neither folder — `grep -rnP '"[^"]*[\x{0400}-\x{04FF}]' */src/main` is the
  check for that, and today it finds only `@Preview` data, maintainer-facing report
  bodies, and the timezone list, whose file documents the choice.
- **A renderer is written against the type it is handed, and nothing checks that but you.**
  «🗓 Четверти» crashed on every press in production because the card printed `term.days`
  and `days` lived on a flattened copy of a term that nothing ever constructed. There is
  no compiler here. `python -m mypy` is: it reproduces that exact failure when the property
  is removed, and it is clean today. If you add a «view» dataclass beside a model, check
  that something builds it — an unused twin is how a renderer ends up written against the
  one it will never receive.
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
  re-check the PR and CI before trusting it.
- **A pull request of this session's own work is merged without asking.** The owner asked for
  that on 20 September 2026; it is their standing instruction, recorded in the `github-pr`
  skill with the five things to check first — CI green on the exact head, `mergeable_state`
  clean, the gates run locally before the push, a milestone attached, and no review waiting
  on an answer. The merge pins `expectedHeadSha` to the SHA that was checked, which is what
  makes it safe. A migration whose side of the merge is unsettled, somebody else's pull
  request, and «I want to look at this one» are outside it.
- **Updating it is the last step of every batch, and nobody should have to ask for it.** The
  trigger is the merge: once a pull request is in `main`, that batch is not finished until
  this file describes it. Write the close-out **while that pull request is still open** — it
  is then one pull request rather than two, and the file is true at the moment it merges.
  **A close-out never gets a close-out of its own:** it names itself as the only thing open,
  and the SHA of its own merge is written by the next batch. A pull request whose only
  content is correcting that SHA is the recursion rather than the cure. It has had to be asked for twice, and each time a batch had been
  reported as done while the one document the next session starts from still described the
  batch before. If the batch's own pull request has already merged, the update is its own
  commit and its own pull request — with a milestone, like every other. What drifts every
  time, and is not a judgement call, is listed in the `handover` skill: the opening
  paragraph, the chain of batch sections, the milestone table, the test counts in their
  three places, and sections 5 and 7.
- **`.claude/` holds the agent configuration, and it describes the shape rather than
  repeating this file.** `.claude/agents/` has one agent per area that has produced a defect
  here, carrying the fact that would have prevented it; `.claude/skills/` has the procedures
  (`gates`, `audit`, `migration`, `release`, `strings`, `bot-message`, `steward`,
  `github-pr`, `handover`); `.claude/commands/` has `/where-are-we` and `/pre-push`;
  `.claude/README.md` says what is deliberately absent — there is no `.mcp.json`, because
  the Neon and Vercel connections need credentials. `AGENTS.md` and
  `.github/copilot-instructions.md` are the same pointer for agents that read those instead.
- **Read a file before editing it; grep every caller before changing a function.** The
  audits in `docs/design.md` exist because a conclusion drawn from call sites was wrong.
- Secrets never enter the repository: `BOT_TOKEN`, `OWNER_IDS`, `WEBHOOK_SECRET`,
  `CRON_SECRET` live in `server/.env` or the host's environment; the release keystore and
  its passwords come from `LESSONS_KEYSTORE_*` environment variables or
  `~/.gradle/gradle.properties`. Redact them as `<redacted>` in issues, logs and reports.
- The documentation index is `docs/README.md`. If a change makes a document wrong, fix it in
  the same batch — `docs/widget.md` and `docs/bot.md` were each rewritten once because they
  had drifted from the code, and that is more expensive than keeping up.
