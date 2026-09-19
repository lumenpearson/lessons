# Copilot instructions

Short on purpose: this file is read on every request. The full picture is in `AGENTS.md` and
`CLAUDE.md` at the repository root.

«Дневник» (`lessons`) is a school diary: an Android app (`android/`, five Gradle modules), a
FastAPI read API with an aiogram bot in one process (`server/`), and a thin Vercel entry
point (`api/`). No npm, no web frontend. **The bot writes, the API reads**, and the rules
they share live in `server/app/services/` so the two shells cannot disagree.

**Language.** User-facing strings are Russian; code, identifiers and comments are English.
Every Russian Android string needs its English twin in the same module's `values-en/`, or
`ResourceTranslationTest` fails.

**Checks.** From `server/`: `ruff check app tests scripts migrations`, `python -m mypy`,
`python -m pytest -q -n auto`. From `android/`: `./gradlew test`, `./gradlew assembleDebug`,
`./gradlew assembleRelease`. CI is exactly those; `./gradlew lint` is not a gate.

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
- a dependency from `:core:data` on `:widget` — the sync worker broadcasts
  `com.lumenpearson.lessons.action.DATA_SYNCED` precisely so that edge does not exist
- a library version you cannot name the source of; four invented versions failed this
  project's first CI run
- any secret in the tree. The repository is public. Redact as `<redacted>`.

**Comments explain why, not what.** A comment that restates the line below it does not
survive review here.
