# Contributing

Thank you for your interest in the project. This page covers how to bring the environment
up, which rules in this repository are non-negotiable, and what has to be green before you
open a pull request.

By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md) and the
[AI usage policy](AI_USAGE_POLICY.md).

The repository has three parts, and each has its own chain of checks: a Python server, an
Android app in Kotlin, and the documentation. You may change them separately, but you run
the checks for everything you touched.

## Environment

| | Required | Where it is pinned |
| --- | --- | --- |
| Python | 3.11+, CI builds on 3.12 | `.python-version`, `server/pyproject.toml` |
| JDK | 21 | `android/app/build.gradle.kts` |
| Android SDK | compileSdk 37, minSdk 26 | `android/app/build.gradle.kts` |
| Gradle | 9.7.1 through the wrapper | `android/gradle/wrapper/` |

The wrapper and its jar are in the repository, so `./gradlew` works on a fresh clone with
no Gradle installed. The Android SDK has to be real: put the path to it in
`android/local.properties` or in `ANDROID_HOME`.

```bash
cd server
python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"
cp .env.example .env          # BOT_TOKEN and OWNER_IDS are your own
.venv/bin/python -m scripts.seed_demo   # demo class, join code DEMO24
```

## Commands

| Command | What it does |
| --- | --- |
| `ruff check app tests scripts migrations` | lints the server — exactly what CI runs |
| `python -m mypy` | one question of all 79 modules: does anything reach for an attribute its type does not have? |
| `python -m pytest -q` | the server tests; 1391 of them, about five minutes, or a third of that with `-n auto` |
| `python -m pytest -q tests/test_schedule.py -k parity` | one file, one test |
| `python -m uvicorn app.main:app --reload` | run the server |
| `alembic upgrade head` | apply the migrations (with a working `DATABASE_URL`) |
| `./gradlew test` | every JVM test across the five modules |
| `./gradlew :core:model:test --tests '*ScheduleEngineTest*'` | one module, one class |
| `./gradlew assembleDebug` | the debug APK |
| `./gradlew assembleRelease` | the release APK, with R8 and resource shrinking |

The first six run from `server/`, the rest from `android/`.

CI (`.github/workflows/ci.yml`) runs exactly this: `ruff`, `pytest -n auto`,
`./gradlew test`, `assembleDebug` and `assembleRelease`. The release build runs on every
push, not only on a release: R8 and resource shrinking are the classic "it worked in debug
and broke in the installed APK", and catching that on a pull request is cheaper than
catching it on people.

`python -m mypy` is not in CI, but run it before you push server code — it is the only
thing in this project that answers "is this renderer written against a type that exists?"

## Rules that may not be broken

They are enforced by review and by tests — there is no automated script for each of them,
so read them as conditions rather than as wishes.

1. **`app/schedule.py` knows neither FastAPI nor aiogram.** It takes ORM rows and hands
   back ordinary dataclasses, which is why its tests run in seconds with no HTTP client. A
   framework import here breaks the main reason this file is separate.
2. **One rule, one implementation.** Every feature of the product has two doors: the bot
   and the app. The rule lives in `server/app/services/`, and the bot handler and the API
   endpoint are two thin shells over it. Two implementations of "rename a subject" would
   disagree within a month, and the one that disagrees quietly is the one that wins.
3. **The schema changes through an Alembic revision.** No `create_all` after `0001`.
   Production is already at `0013`, and a column will not appear there on its own. Mind the
   direction: an additive revision goes on **before** the merge that deploys the code, a
   `UNIQUE` or a `NOT NULL` **after** it.
4. **Nothing on the server happens by itself.** On Vercel nothing runs between requests:
   no scheduler, no background task. Everything that has to happen on a clock goes through
   `/api/v1/cron/tick`, which an external cron service calls.
5. **"Now" is the school's time.** `school_class.tz` on the server,
   `Timetable.nowAtSchool()` on the client. `LocalDateTime.now()` and
   `ZoneId.systemDefault()` in Android code are almost always a bug: two of the fourteen
   defects the audit confirmed were exactly this.
6. **`:core:data` does not depend on `:widget`.** After a sync, `SyncWorker` sends the
   internal broadcast `…action.DATA_SYNCED`, which the widget listens for; otherwise the
   dependency would have to be circular.
7. **Every Russian string has an English twin.** `ResourceTranslationTest` in `:app`
   enforces it: a name with no counterpart in `values-en/`, a name that exists only in
   English, format arguments that have drifted apart, and an English `<plurals>` that is
   not `one` + `other` all fail the test. Android will drop a Russian string into the
   middle of an English screen without a word — hence a test, rather than attentiveness.
8. **No secrets in the repository.** The bot token, `OWNER_IDS`, `WEBHOOK_SECRET`,
   `CRON_SECRET` and the connection string live in `server/.env` or in the host's
   environment; the keystore that signs the APK lives in `~/.gradle/gradle.properties` or
   in the Actions secrets.

## Style

- **The interface is Russian; everything written about the project is English.** `values/`
  holds the Russian strings and is the source, `values-en/` is the translation, and the
  reader chooses the language in the app. Documentation, code comments, commit messages and
  pull request descriptions are English, so that anybody can read them.
- **A comment explains why, not what.** Look at any file here: above a decision it says
  what would break without it. A comment that retells the line next to it does not survive
  in this repository.
- Kotlin is four spaces and a hundred and twenty columns (`kotlin.code.style=official`),
  Python is four spaces and a hundred (`line-length` in `pyproject.toml`, which is what ruff
  checks). Everything else is in `.editorconfig`.
- **A dependency version is never invented.** The header of
  `android/gradle/libs.versions.toml` says where each one came from; the first CI run in
  this project failed on four versions that exist in no repository. Check Maven Central or
  PyPI, not your memory.

## Commits

Conventional Commits are not used here — not one commit in the history starts with `feat:`
or `fix:`. Something else is the convention, and it is just as consistent:

- **The subject is an English sentence about what the change lets the project do**, not
  about what the author did: "Let the class be run from the phone, by the same rules as
  from the bot".
- **The body explains the reason** — what was wrong, what is true now, and what the change
  does not cover. Paragraphs, not a list of changed files: the diff shows those already.
- **Name the specific thing** — the file, the endpoint, the setting — rather than "fixed a
  few places".
- **Secrets do not reach the message.** Write `<redacted>`.

## Pull request

1. Branch from an up-to-date `main`; direct commits to `main` are not accepted.
2. One task, one pull request.
3. Run locally what you touched: `ruff check` and `pytest -q` for the server,
   `./gradlew test` for Android, and both assembles if `android/` changed.
4. Fill in the template, including the "Verification" section: paste the real output of the
   commands, with numbers, rather than the phrase "everything passes".
5. Fill in the "What is NOT covered" section. It is mandatory. **"Written, never run" is an
   honest status**, and the README has a whole section that does this on purpose; a claim
   that something was verified when it was not is not an honest status.

### Tests

- New code comes with tests at the same level as the layer it changes. The state logic, the
  notification planner and the parsing of somebody else's responses are covered — keep them
  that way.
- Tests are offline and deterministic: the server is tested through the httpx ASGI client,
  a foreign service through a stubbed upstream (`tests/test_diary_api.py`), Android through
  ordinary JVM tests with no emulator. A test that needs the network does not join the
  suite.
- A negative test has to provably catch the regression: revert the fix and make sure it
  fails. Otherwise it proves nothing.
- Three screens — the class list, the join mode and the connection errors — are pressed in
  JVM tests under Robolectric, and the widget's size ladder is walked at real sizes. What
  no test reaches is a device: there is no `androidTest` directory, so the drawing, the
  alarms under Doze and the dark theme are checked by hand, and it is worth saying so in the
  pull request: which device, and which widget sizes.

## Security

Do not open a public issue for a vulnerability — [SECURITY.md](.github/SECURITY.md)
describes the private channel and what counts as a vulnerability in this project.
