# Copilot instructions

Short on purpose: this file is read on every request. The full picture is in `AGENTS.md` and
`CLAUDE.md` at the repository root.

«Дневник» (`lessons`) is a school diary: an Android app (`android/`, five Gradle modules), a
FastAPI API with an aiogram bot in one process (`server/`), and a thin Vercel entry point
(`api/`). No npm, no web frontend. **Two shells write** — the bot, and a linked phone
through the edit routes (`/api/v1/homework`, `/overrides`, `/events`, `/days`) and
`/api/v1/manage` — over one set of rules in
`server/app/services/`, so they cannot disagree; nothing there may import `app.bot`.

**Language.** The product speaks Russian: user-facing strings live in `values/` with an
English twin in the same module's `values-en/`, or `ResourceTranslationTest` fails.
Everything written about the project — comments, documentation, commit messages, pull
request descriptions — is English, and a quotation of product text keeps its Russian in
guillemets.

**Checks.** From `server/`: `ruff check app tests scripts migrations`, `python -m mypy`,
`pytest -q -n auto` (not `python -m pytest`, which hides a broken import on CI). From `android/`: `./gradlew test`, `./gradlew assembleDebug`,
`./gradlew assembleRelease`, `./gradlew detekt`. CI is exactly those, path-filtered (a half runs
when a file it reads changed), plus the «Contract (Buf)» job when the contract changed;
`./gradlew lint` is not a gate.

**Do not suggest:**

- a background task, a scheduler or an `asyncio` loop that outlives a response — the server
  has no clock on Vercel; `GET /api/v1/cron/tick` is where scheduled work goes
- an unescaped or unbounded string in a Telegram message — the ceiling is 4096 characters
  after entity parsing and the whole message is refused, not clipped
- `create_all` in any migration after `0001`, or a `server_default` spelled `.value` on an
  enum column (`SAEnum` stores the member **name**)
- `LocalDateTime.now()` or `ZoneId.systemDefault()` on the Android side — time is naive local
  wall time in the *class's* zone
- applying `org.jetbrains.kotlin.android` in an Android module — AGP 9 compiles Kotlin itself
  and this is a hard build failure
- a dependency from `:core:data` on `:widget` — the timetable repository's
  `onDataChanged` broadcasts `com.lumenpearson.lessons.action.DATA_SYNCED` after every
  successful sync precisely so that edge does not exist
- a library version you cannot name the source of; four invented versions failed this
  project's first CI run
- any secret in the tree. The repository is public. Redact as `<redacted>`.

**Comments explain why, not what.** A comment that restates the line below it does not
survive review here.
