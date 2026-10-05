# The build console: one terminal for the gates, the contract, the device and the documents

Status: **drafted on 5 October 2026 while the owner was away, not yet approved.** Sub-project 6 of
[the programme](2026-10-03-one-contract-design.md). Its section 6 already decided most of the
console:
- it lives in `tools/console/`, a separate Python project depending on Textual, started with
  `uv run --project tools/console lessons-console`;
- it is local only and never part of the product;
- it has six tabs: Build, Contract, Deploy, Device, Environment, Docs;
- it runs one heavy job at a time, in a visible queue;
- it uses a JDK 21 it finds itself;
- it fetches Buf at the pinned version;
- it never shows a setting's value;
- it never deploys production.

This document adds what the programme left open:
- how the console's commands are kept level with CI's;
- how a run's log is kept without the secrets a command might print;
- how the console is tested;
- the stages.

The questions only the owner can answer are at the end, each with a recommendation. **Nothing here
is built before the owner approves it.**

## Decisions

### 1. One table of tasks, held level with CI by a test

Every command the console can run is one row in `tools/console/lessons_console/tasks.py`. Each row
carries:
- **Identity:** the task's name and its tab.
- **How it runs:**
  - the command and its working directory;
  - the environment it needs: `JAVA_HOME` from decision 4, and Buf's path;
  - for Gradle, the bounded worker count (`-Dorg.gradle.workers.max=2`, the command-line answer
    to sub-project 4's question 1).
- **What it costs:** whether it is **heavy** (decision 2).
- **What a person needs to know:** the settings a person must have set for it to make sense, by
  name only, and the one sentence that says what it checks.

**The table is held to CI.** CI's commands are written in `.github/workflows/ci.yml`. A test in
the console's suite reads the workflow's `run:` steps for the server, the Android and the contract
jobs, and fails if one of them is not a row of the table with the same command and directory. «The
gates exactly as CI runs them» is then a fact a test holds, not a promise. Those commands today:
- `ruff check app tests scripts migrations`;
- `python -m mypy`;
- `pytest -q -n auto`;
- `./gradlew test assembleDebug assembleRelease`;
- `./gradlew detekt`;
- the three Buf steps.

The `gates` skill and `/pre-push` describe the same commands for agents. The console reads none
of them and copies none of them: the table is the console's, and the test is what keeps it honest.

### 2. One heavy job at a time, for the machine's sake

On this machine, a second heavy job beside the first is how the faulty memory has failed before.
The console therefore holds **one lock** for every row marked heavy:
- Gradle;
- the full test suite;
- the host target;
- `buf generate`.

A second heavy job waits in a queue the Build tab shows, with what it is waiting for. Light jobs
run beside them: a single test file, `ruff`, `mypy`, reading `warmup`, `adb devices`.

The lock lives in the console's process. A Gradle build started in another terminal is not seen,
and the console says so on its first screen rather than pretending to a guarantee it cannot give.

### 3. Every run is logged, and no log keeps a secret

Each run's output is streamed live into its tab and written to
`tools/console/.runs/<timestamp>-<task>.log`, which is git-ignored. The Build tab lists the runs
with their result and their duration.

**Before a line is shown or saved, the value of every secret setting the process can see is
replaced with `<redacted>`.** «Secret» here means:
- every setting `server/app/config.py` declares as a secret;
- every `LESSONS_KEYSTORE_*`, `BUF_TOKEN` and `*_TOKEN` in the environment.

A command that prints a password therefore cannot leave it in a log.

**The console never opens `~/.gradle/gradle.properties`.** The machine keeps the release
keystore's passwords there, and on 5 October 2026 an agent that read it for a Gradle setting
printed one into its transcript. Where the console needs to know whether signing is configured,
it asks Gradle instead of reading the file: a small task in the app's build answers «configured»
or «not configured» (question 3). `signingReport` is not that task, because it prints more than
one word.

### 4. The tools the console finds or fetches

- **The JDK.** On this machine, the `java` on `PATH` is 17 and the build needs 21. The console
  looks for a JDK 21 in `JAVA_HOME`, Android Studio's bundled JBR, and the usual install
  directories, and sets `JAVA_HOME` for Gradle's rows only. If none is found, the Android rows
  are disabled, saying why.
- **Buf.** If the pinned Buf (1.73.0) is not in the console's cache, the console fetches it from
  the release URL `docs/build.md` names. It checks the download against the SHA-256 that document
  carries, and refuses a mismatch. The binary never enters the repository.
- **`uv`, `adb`, the Android SDK.** These are found on `PATH` or through `ANDROID_HOME`, never
  installed by the console. A missing one disables its rows, saying why.

### 5. The tabs, as the programme lists them

**Build:**
- the gates per half (server, Android, contract) or all, in CI's order;
- an APK for a chosen transport and streaming flag, once sub-project 5's build properties exist;
- until then, the debug and release APKs as today;
- the run history.

**Contract:** `buf lint`, `buf generate`, `buf breaking` against `origin/main` (after
`git fetch`), and the freshness check (regenerate into a temporary directory and diff), each a row.

**Deploy:**
- **Production's `/api/v1/warmup`**, read-only: the status, and the schema revision against
  `EXPECTED_REVISION`, which is read from `server/app/db.py`, never a third copy.
- **The host target**, run locally, once sub-project 3's stage 3c exists.
- **No button deploys production.** Production is a merge to `main`.

**Device:**
- `adb devices`;
- installing the chosen APK;
- a logcat filtered to the app;
- `adb reverse` for a locally running server, because on this machine the emulator cannot reach
  `10.0.2.2`.

**The console never installs over the app on the device** (the programme's rule):
- before installing, it reads the installed package's version and signing certificate;
- a build of another signature, or a lower version, goes in under its own application id
  (`.debug` exists for exactly this) or not at all.

**Environment:**
- every setting the server reads, which ones are mandatory on Vercel, which are switched off, and
  what each switches off;
- all of it read from `server/.env.example` and `app/config.py`, never a third copy;
- for each, «set» or «missing» in this process. **The value is never shown.**

**Docs:** `docs/`, `CLAUDE.md` and `HANDOVER.md` in Textual's Markdown viewer.

### 6. How the console is tested

- **The tasks table.** It is tested against `ci.yml` (decision 1), and every row's working
  directory and tools are checked to exist.
- **The lock.** It is tested with fake heavy jobs: a second waits, a light one does not, and a
  cancelled one releases the lock.
- **The redaction.** It is tested with a fake command that prints a known secret: the secret
  appears in neither the screen buffer nor the log.
- **The screens.** They are tested with Textual's `App.run_test()` pilot, with every subprocess
  replaced by a fake: nothing in the console's tests runs Gradle, `adb` or Buf.
- **The Environment tab.** It is tested to render «set» and «missing», and never a value, for a
  setting whose value is present in the test's environment.

These tests are light Python and finish in seconds. Whether CI runs them is question 1.

### 7. Its own project, its own versions

`tools/console/` has its own `pyproject.toml` and its own `uv.lock`, and imports nothing from
`server/`. The Environment tab reads `app/config.py` as text, the way the server's own documents
are read by tests, so the console never pulls the server's dependencies in.

Textual's version is copied from its PyPI release when the code is written, never guessed, and its
provenance is recorded in the `pyproject.toml`, as `android/gradle/libs.versions.toml` does for
the app's versions.

### 8. Stages

| Stage | Merges | Waits for |
| --- | --- | --- |
| **6a** | The project, the tasks table and its CI test, the lock and queue, the logs and their redaction, and four tabs: Build (the gates and the debug and release APKs), Environment, Docs, Contract | the owner's approval of this document |
| **6b** | Device (install with the signature and version check, logcat, `adb reverse`) and Deploy (production's warmup) | 6a |
| **6c** | The APK for a chosen transport and streaming flag, and the host target in Deploy | sub-project 5's 5a, sub-project 3's 3c |

## Questions for the owner

1. **Should CI run the console's tests when `tools/console/` changes?** The programme says the
   console never runs in CI, which this design keeps; its tests are another matter.
   - *Recommended: yes, as a light job behind a path filter.* Without it, the test that holds the
     tasks table to `ci.yml` runs only when somebody remembers to run it, which is the moment it
     stops holding anything.
   - It costs seconds on a free runner.
   - The change to `ci.yml` goes through the `build-ci` agent's review, like every workflow change.
2. **Should the Deploy tab use the Vercel CLI for preview deployments?** The programme ties it to
   #118, which has not said what Preview is for. The Vercel CLI is not installed on this machine.
   - *Recommended: not until #118 answers.* Until then Deploy reads production's warmup only, and
     a login to Vercel stays out of the console.
3. **How should the console know that signing is configured, without opening the file that holds
   the passwords?** *Recommended: a small Gradle task in the app's build that answers «configured»
   or «not configured».* It reads the same properties the release build reads, and prints nothing
   but that one word: no value, no file path. The console calls that task and shows the word.

## Risks

- **A console that drifts from CI is worse than none**, because it would tell the owner a branch
  is green when CI will say otherwise. Decision 1's test and question 1's CI job are what stop the
  drift.
- **The lock cannot see another terminal.** It protects only what the console starts, and says
  so on its first screen.
- **Textual is the one new dependency of note.** It stays inside `tools/console/`, with its own
  lock file, and never reaches the server's or the app's dependencies.
