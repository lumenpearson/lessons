# AGENTS.md

For coding agents that read this file rather than `CLAUDE.md`. It is a pointer and a
minimum, not a second copy: **`CLAUDE.md` at the root is the source of truth**, and
`.claude/README.md` describes the agent configuration around it.

## What this repository is

«Дневник» (`lessons`): a school diary for Russian schools. Three deliverables in one tree —
an Android app with a resizable home-screen widget (`android/`, five Gradle modules), a
FastAPI read API with an aiogram bot in the same process (`server/`), and a thin Vercel entry
point (`api/`). Documentation is `docs/`, indexed by `docs/README.md`. There is no npm and no
web frontend.

**The bot writes, the API reads.** Telegram already solved identity, so there is no admin web
panel. `server/app/services/` holds the rules the bot handlers and the `/api/v1/manage`
endpoints share — two thin shells over one implementation.

## Language

The product speaks **Russian**: `values/` is the source and `values-en/` the translation,
and a test fails on a name that has no twin. Everything written about the project — these
documents, the code comments, the commit messages and the pull request descriptions — is
**English**. A quotation of something the user sees keeps its Russian and goes in
guillemets.

## The checks that are real

From `server/` (Python 3.12):

```
ruff check app tests scripts migrations
python -m mypy
python -m pytest -q -n auto
```

From `android/` (JDK 21, compileSdk 37, wrapper Gradle):

```
./gradlew test
./gradlew assembleDebug
./gradlew assembleRelease
```

CI is exactly: ruff, pytest `-n auto`, `./gradlew test`, both assembles. `./gradlew lint` is
**not** a gate. `python -m mypy` is not in CI but is run before server code is pushed.
`--offline` is needed for Gradle in a sandbox.

**No commit without lint and tests green.** Touch both halves, run both.

## Five things that will bite you

1. **The server has no clock.** Nothing runs between requests on Vercel; digests are driven
   by an external caller of `GET /api/v1/cron/tick`.
2. **Telegram refuses a message over 4096 characters whole**, rather than clipping it, and
   everything from outside must be HTML-escaped before it goes in.
3. **A model change needs an Alembic revision**, and the direction matters: additive goes on
   before the merge, a `UNIQUE` or `NOT NULL` after, a rewrite of a key after where there are
   rows to rewrite. Head is `0017`.
4. **`LocalDateTime.now()` and `ZoneId.systemDefault()` on the Android side are almost always
   a bug** — time is naive local wall time in the *class's* zone.
5. **Secrets never enter the repository.** Redact as `<redacted>` in issues, logs and
   reports. The repository is public.

## Conventions

- Comments explain **why**, not what. A comment that restates the line below it does not
  survive review.
- Commit messages are English sentences saying what the change makes the project do. No
  Conventional Commits prefix — none of this history has one.
- Every pull request carries a milestone, set when it is opened, and so does every issue. A
  defect gets an issue before it gets a fix, and the pull request says `Closes #NN`. No
  session can create a milestone: when none fits, ask the owner, with the title and
  description written out. See `.claude/skills/github-pr/`.
- Versions are copied from a project that builds, never guessed.
- **Say what is not covered.** "Written, never run" is a legitimate status; a claim
  that something was verified when it was not is not.
- Another agent may be working in this tree — check `git status` before touching a file you
  did not open.
- `HANDOVER.md` says where the work stands, and is stale the moment it stops being updated.
  Read it first; update it last, **inside the batch's own pull request** while it is still
  open. A batch whose pull request has merged is not finished until that file describes it,
  and nobody should have to ask. A close-out never gets a close-out of its own.
- Merge your own green pull request rather than asking: the owner's standing instruction,
  with the five checks and the one refusal in `.claude/skills/github-pr/SKILL.md`.

The rest — architecture, the traps each module has already produced, the migration protocol —
is in `CLAUDE.md`, `docs/architecture.md` and `.claude/skills/`.
