# The build console: one terminal for the gates, the contract, the device and the documents

Status: **drafted on 5 October 2026 while the owner was away, not yet approved.** Sub-project 6 of
[the programme](2026-10-03-one-contract-design.md), whose section 6 already decided most of the
console:
- it lives in `tools/console/`, a separate Python project depending on Textual, started with
  `uv run --project tools/console lessons-console`;
- it is local only and never part of the product;
- it has six tabs: Build, Contract, Deploy, Device, Environment, Docs;
- it runs one heavy job at a time in a visible queue;
- it uses a JDK 21 it finds itself;
- it fetches Buf at the pinned version;
- it never shows a setting's value;
- it never deploys production.

This document adds what the programme left open:
- how the console's commands are held level with CI;
- how a run's log is kept free of secrets;
- which Python and which JDK run what;
- how the console is tested;
- the stages.

It was revised the same night after an independent review, by the agent that knows this
repository's CI, which read `ci.yml`, `config.py`, the Android build and the local machine's
state. That review found:
- a secret set that would not have redacted the release key's own password;
- a CI-parity test that could not see two of the three Buf gates;
- a lock that two consoles would not share;
- several things this machine does that the first draft did not know.

**Nothing here is built before the owner approves it.**

## Decisions

### 1. One closed table of tasks, held level with CI in both directions

**The table.** Every command the console can run is one row in
`tools/console/lessons_console/tasks.py`. There is no free-form command entry: a closed table is
what lets decision 3 promise anything. Each row carries:
- its name and tab;
- its argv and working directory;
- the interpreter or tool it runs under (decision 4);
- whether it is heavy (decision 2);
- whether it is a **gate**, meaning it stands for a CI step;
- the settings a person must have for it to make sense, by name only;
- one sentence on what it checks.

**The test that holds the table to CI** reads `.github/workflows/ci.yml` and checks it in both
directions:
- **Every job is walked, not three named ones.** An explicit skip list, each entry with its
  reason, holds the «What changed» routing job and the console's own job. A skip entry that no
  longer names a real step fails.
- **A single-line `run:` step** (no `&&`, `|` or `;`) is resolved to its working directory and
  split with `shlex`. It must equal a gate row's argv, after:
  - the platform's wrapper (`gradlew` or `gradlew.bat`) is normalised;
  - the row's declared interpreter prefix is removed;
  - an allow-list of local-only flags is removed (`--max-workers=2`, `--console=plain`).
- **A step's `if:` is compared too.** detekt runs after a failed build in CI (`success() ||
  failure()`), so the console's Android «all» does the same. Every other step stops at the first
  failure, as CI's do.
- **A multi-line `run:` step** cannot be parsed honestly, so it is keyed by job and step name and
  recorded as either «routing, skipped because …» or «its equivalent is row X». The test stores a
  SHA-256 of the script's text, and any edit fails the test until somebody re-reads the equivalent
  row.
- **A `uses:` step** must be on a known list, and an unknown action fails, because it may be a
  gate. For `bufbuild/buf-action`, the test reads its `with:` inputs:
  - `lint: true` requires a `buf lint` row;
  - a `breaking` input that is not false requires a `buf breaking` row;
  - `version` is the console's Buf version (decision 4), and `checksum` equals the Linux SHA-256
    in `docs/build.md`'s table.

  `setup-java`'s `java-version` and `setup-python`'s `python-version` are read the same way, so
  «JDK 21» and «Python 3.12» are not third copies.
- **Every row marked a gate must match a CI step.** A row for `./gradlew lint`, which CI does not
  run, cannot call itself a gate.
- **One difference is declared, with its reason.** `buf breaking` runs locally against
  `.git#ref=origin/main` (`docs/build.md`), while CI runs it against the pull request's base.

**Two more things act as gates in CI, and the console mirrors both:**
- **The Install step** fails when the lock no longer satisfies a floor. The console's «Prepare»
  row does that check (decision 4).
- **The APK upload's `if-no-files-found: error`** checks that the build produced what it claims.
  The console's assemble row checks that the APKs exist.

### 2. One heavy job at a time, for the whole machine

On this machine, a second heavy job beside the first is how the faulty memory has failed before.

**The heavy jobs are:**
- every Gradle task, detekt and `connectedDebugAndroidTest` included, and a single Gradle test
  class too;
- the full pytest suite;
- `buf generate`, whose reason is a write race with pytest reading `server/app/contract/`, not
  memory;
- the «Prepare» row, which writes the venv pytest uses.

**The light jobs are:** a single pytest file, `ruff`, `mypy`, reading production's warmup, and
`adb devices`.

**The lock covers the machine, not the console.** It is a file lock at a fixed path outside the
repository, holding the owner's PID and reclaimed when that process is dead. Two consoles in two
worktrees, which this repository's way of working makes likely, then share one lock, and an agent
can take the same lock by convention. A second heavy job waits in a queue the Build tab shows, with
what it is waiting for.

**Cancelling kills the whole tree.** On Windows, killing `gradlew.bat` does not kill the Java
client it started. So each job runs in a Windows Job Object with kill-on-close, or its whole
process tree is killed, and the lock is released only when the tree has exited. That also covers
quitting the console with a job running.

**Long-running things are occupants, not jobs.**
- **The host target**, once 3c exists, is shown in the queue for as long as it runs. It does not
  hold the job lock for hours.
- **A running emulator**, seen through `adb devices`, is the largest load this machine carries.
  It is shown the same way, and starting a heavy job beside it asks first (question 4).

### 3. Every run is logged, and no log keeps a secret

Each run's output is streamed live, with Textual's markup off so a stray `[` cannot break the
screen, and decoded with `errors="replace"`. It is written to
`tools/console/.runs/<timestamp>-<task>.log`; the 6a change adds `.runs/` to `.gitignore`. Gradle
rows pass `--console=plain`, so redaction sees whole lines.

**Before a line is shown or saved, every secret value the console can see is replaced with
`<redacted>`.** «Secret» is a defined set, not a convention:
- **The server's settings.** `server/app/config.py` has no marker for a secret: every field is a
  plain `str`. The console therefore keeps an explicit classification of every `Settings` field,
  secret or not. A console test reads `config.py` with `ast`, taking the class's annotated fields
  and their `alias` names, and fails on any field the classification does not name. The secrets
  are:
  - `bot_token`, `webhook_secret`, `cron_secret`, `diary_secret`, `dadata_token`;
  - `database_url`, which carries a password;
  - `owner_ids`, which `config.py` refuses to quote even in a build log.
- **The environment**, matched by name: `(?i)(TOKEN|SECRET|PASSWORD|PASSWD|API_KEY|CREDENTIAL)`,
  plus `LESSONS_KEY_*`, `LESSONS_KEYSTORE_*`, `DATABASE_URL` and `OWNER_IDS`. That catches all four
  signing names (`LESSONS_KEYSTORE_FILE`, `LESSONS_KEYSTORE_PASSWORD`, `LESSONS_KEY_ALIAS`,
  `LESSONS_KEY_PASSWORD`), and also any variable whose value is a URL carrying a password.
- **Every form a secret takes.** Each value is registered as written and percent-encoded. A URL's
  password is registered both encoded and decoded, because a `DATABASE_URL` holds `%40`-style
  escapes, and a traceback's locals can show either. Values are replaced longest first. A value
  shorter than eight characters is not redacted, because it would shred ordinary text, and the
  screen says so.
- **What the console never saw, it matches by shape.** Logcat and server output can carry a bearer
  the console was never told about: `Authorization: Bearer …`, `X-Cron-Secret`, `X-JWT-Token`,
  `X-Telegram-Bot-Api-Secret-Token`. Logcat is not saved to `.runs/` at all.
- **Secret values are held in a wrapper whose `repr` is `<redacted>`**, so a crash traceback that
  renders frame locals cannot print them.

**What redaction cannot cover is closed instead.**
- Values the console never reads (`server/.env`, the machine's `~/.gradle/gradle.properties`)
  cannot be redacted. The task table therefore has no row that prints them:
  - no `./gradlew properties`, which prints every project property, the signing passwords
    included;
  - no `--debug`;
  - no free-form command.
- **The console never opens `~/.gradle/gradle.properties`.** The release keystore's passwords live
  there, and on 5 October 2026 an agent that read the file for a Gradle setting printed one into
  its transcript. Where the console needs to know whether signing is configured, it asks Gradle
  instead (question 3).

### 4. Which Python, which JDK, which Buf

**The server's gates run in the server's own venv, never the console's.**
- `uv run --project tools/console` puts the console's venv first on `PATH` and sets
  `VIRTUAL_ENV`, so a bare `pytest` would resolve to the console's own.
- Each server row therefore names the absolute path of `<this checkout>/server/.venv`'s `ruff`,
  `pytest` and `python -m mypy`, and its child process runs without the console's `VIRTUAL_ENV` or
  `PATH` entry.
- Before the first server row, the console checks that `app` imports from this checkout's
  `server/app`. A venv that is an editable install of *another* checkout, which the main checkout's
  venv is, would test the wrong tree. When it does, the server rows are disabled, saying why.

**«Prepare» mirrors CI's Install step.**
- CI installs `-r ../requirements.txt -e ".[dev]"`, and the dev tools float. A local venv built on
  another day runs a different ruff or mypy, and SQLAlchemy once changed mypy's verdict (#164).
- The «Prepare» row checks the venv against the lock, and installs it when asked. It is heavy.
- Every run's log begins with `ruff --version` and `mypy --version`, so a different verdict can be
  traced to a different tool.

**The JDK.** On this machine the `java` on `PATH` is 17, and Android Studio's bundled runtime is
25. Neither is used. The console tries, in order, each **confirmed by its `release` file**
(`JAVA_VERSION="21…"`) and never by where it is installed:
1. the `java.home` in `android/.gradle/config.properties`, which is the owner's own Gradle JDK, a
   path and not a secret;
2. `C:\Program Files\Eclipse Adoptium\jdk-21*`;
3. `JAVA_HOME`.

If none is a 21, the Android rows are disabled, saying why.

**Buf.**
- **The version** is read from `ci.yml`'s `bufbuild/buf-action` `version:`, not written down a
  third time.
- **The download** is the release URL `docs/build.md`'s table names. It is checked against the
  SHA-256 that table carries for this platform, and a mismatch is refused. The table row is parsed
  strictly and fails closed.
- **After the fetch**, `buf --version` must match.
- **The cache** is keyed by version and kept outside the repository.
- **A console test holds `docs/build.md`'s table level with `ci.yml`:** the URLs' version, and the
  Linux SHA-256 against `ci.yml`'s checksum. No test does this today.

**`uv`, `adb`, `apksigner` and the Android SDK** are found on `PATH` or through `ANDROID_HOME`,
never installed by the console. `apksigner` needs the JDK above. A missing tool disables its rows,
saying why.

### 5. The tabs, as the programme lists them

**Build:**
- the gates per half (server, Android, contract) or all, in CI's order and with CI's `if:`;
- «Prepare»;
- an APK for a chosen transport and streaming flag, once sub-project 5's build properties exist,
  and until then the debug and release APKs as today;
- the run history.

**Contract:**
- `buf lint`, `buf generate`, and `buf breaking` against `origin/main` after `git fetch`;
- the freshness check, which regenerates into a temporary directory and diffs it with the
  committed tree. The diff ignores `__pycache__`, as CI's does, and normalises line endings. This
  machine has `core.autocrlf=true` and no `.gitattributes`, so a byte diff would pass or fail by
  accident (question 5).

**Deploy:**
- **Production's warmup.** The production address is not in the repository, so it comes from a
  non-secret environment variable the owner sets. The tab shows two answers, labelled apart:
  - **production's own**: `status`, `schema` and `expected_schema`, as warmup returns them;
  - **«is production ready for this branch»**: production's `schema` against this checkout's
    `EXPECTED_REVISION`. The constant is read from `server/app/db.py` with `ast`, as one `Assign`,
    failing closed on anything else. This is the «migrate before merge» question.
- **The host target**, run locally, once sub-project 3's 3c exists.
- **No button deploys production.** Production is a merge to `main`.

**Device:**
- `adb devices`, with `-s <serial>` whenever more than one is attached;
- installing the chosen APK;
- a logcat filtered to the app, shown and never saved;
- `adb reverse` for a locally running server, because on this machine the emulator cannot reach
  `10.0.2.2`.

**The console never installs over a different build of the app.**
- **What it compares.** It reads the candidate's application id and version from AGP's
  `output-metadata.json` beside the APK. It pulls the installed APK (`pm path`, then `adb pull`),
  and compares both signing certificates' SHA-256 with `apksigner verify --print-certs`.
- **A mismatch is refused, saying why.** A different certificate, or a lower version, refuses the
  install. `.debug` only lets a debug build sit beside a release one; it does not separate one
  machine's debug key from another's. A local release build has version code 1 and the debug key,
  so it can never go onto a phone that holds the real release.
- **What it never runs.** The console never runs `adb uninstall`, `install -d` or `pm clear`.
  Android already refuses a signature mismatch or a downgrade, and the real guard is never helping
  it past that refusal.

**Environment:**
- **Which settings.** Every setting the server reads, by name, from `server/.env.example` and
  `app/config.py`, never a third copy.
- **Where each is set.** «Set» or «missing» counts both the process environment and `server/.env`,
  where the local secrets live and which pydantic-settings also reads. `server/.env` is parsed for
  **names only**, and each value is discarded as it is read (question 6).
- **Which are mandatory on Vercel, and what each switches off.** These are code in `config.py`, not
  data, so they are not re-written in the console. The console runs the server's own
  `deployment_problems()` and `disabled_features()` in the server's venv, as a subprocess, with
  `Settings(_env_file=None)` and an empty environment, and shows their sentences.
- **The value is never shown.**

**Docs:** `docs/`, `CLAUDE.md` and `HANDOVER.md` in Textual's Markdown viewer. The console's own
`README.md` does not quote the migration head, because no test reads `tools/` for it.

### 6. How the console is tested

- **The tasks table:** against `ci.yml` (decision 1). Each row's discovery rule is tested, not the
  binary, because `buf`, `adb` and the JDK do not exist on a CI runner.
- **The lock:** with fake heavy jobs.
  - A second heavy job waits, and a light one does not.
  - A cancelled job whose fake spawned a grandchild releases the lock only after both have exited.
  - A dead owner's lock is reclaimed.
- **The redaction:**
  - a fake command that prints a known secret, in each of its encoded forms, leaves it in neither
    the screen buffer nor the log;
  - the classification test fails on an unclassified `Settings` field.
- **The screens:** with Textual's `App.run_test()` pilot, every subprocess replaced by a fake.
  Nothing in the console's tests runs Gradle, `adb` or Buf.
- **The Environment tab:** it renders «set» and «missing», never a value, for a setting present in
  the test's environment or in a fake `.env`.

These tests are light Python, on Linux and Windows alike. Whether CI runs them is question 1.

### 7. Its own project, its own versions

`tools/console/` has its own `pyproject.toml` and `uv.lock`, and imports nothing from `server/`. It
reads the server's files as text, or runs the server's code as a subprocess in the server's venv
(decision 5).

**Its dependencies, and where their versions come from:**
- **Textual's version** is copied from its PyPI release when the code is written, never guessed,
  with its provenance in `pyproject.toml`.
- **PyYAML** is needed to read `ci.yml`. It reads the `on:` key as `True`, and the parser accounts
  for that.
- **Dependabot** gains a `uv` entry for `/tools/console`, or Textual's lock never moves.

### 8. Stages

| Stage | Merges | Waits for |
| --- | --- | --- |
| **6a** | The project, the closed tasks table and its CI test, the machine-wide lock and its queue, the logs and their redaction, «Prepare», and four tabs: Build (the gates and the debug and release APKs), Environment, Docs, Contract | the owner's approval of this document |
| **6b** | Device (install with the certificate and version check, logcat, `adb reverse`) and Deploy (production's warmup, both answers) | 6a |
| **6c** | The APK for a chosen transport and streaming flag, and the host target in Deploy | sub-project 5's 5a, sub-project 3's 3c |

## Questions for the owner

1. **Should CI run the console's tests when `tools/console/` changes?** The programme says the
   console never runs in CI, and that holds; its tests are another matter.
   - *Recommended: yes.* It is a light job behind a fourth arm of «What changed», which also fires
     on `.github/workflows/*`, so that a `ci.yml` edit runs the parity test. It also fires on every
     file the console's tests read.
   - The job is `uv run --locked pytest -q` under `tools/console`, with `astral-sh/setup-uv` pinned
     to an exact tag copied from its releases.
   - It reads no secret, and costs seconds on a free runner.
   - A sibling of `test_ci_paths.py` would hold its arm to what the console's tests read.
   - The change to `ci.yml` goes through the `build-ci` agent's review.
2. **Should the Deploy tab use the Vercel CLI for preview deployments?** The programme ties it to
   #118, which has not said what Preview is for, and the CLI is not installed on this machine.
   *Recommended: not until #118 answers.* Until then the tab reads production's warmup only, and a
   login to Vercel stays out of the console.
3. **How should the console know whether signing is configured, without opening the file that
   holds the passwords?** *Recommended: a small Gradle task in the app's build that prints one
   word.*
   - **What it reads.** The release build already computes `hasReleaseSigning` from the four
     `signingSecret(...)` reads. The task copies that value into its own `val`, as the
     configuration cache requires, and adds no new `signingSecret("LESSONS_…")` call, so
     `BuildPropertyReachTest` is unaffected.
   - **What it prints.** One of «configured», «not configured», or «partly configured» with the
     missing names, and never a value or a path.
   - It is a Gradle invocation, so it is heavy, and its answer is cached for the session.
   - Whether the configuration cache notices a moved keystore file has to be tried once.
4. **Should the console start a heavy job while an emulator is running?** *Recommended: ask each
   time, showing the emulator as the occupant.* The emulator is the largest load this machine
   carries, and the owner is often watching it.
5. **Should `server/app/contract/**` be checked out with LF line endings
   (`text eol=lf` in a new `.gitattributes`)?** *Recommended: yes, as its own small change.* It
   makes the freshness check's normalisation a belt rather than the only thing holding it.
6. **May the Environment tab parse `server/.env` for names only?** *Recommended: yes.* Without it,
   the tab says «missing» for exactly the settings the server does have locally. With it, each
   value is discarded the moment its line is read, and never held.

## Risks

- **A console that drifts from CI is worse than none**, because it would call a branch green that
  CI will not. Decision 1's two-way test, the script hashes and question 1's job are what stop
  that.
- **Redaction is a net, not a wall.** It catches what the console knows and what it can match by
  shape. The closed task table, and the file the console never opens, are what keep the rest out.
- **The lock is a convention for anything the console does not start.** An agent or a terminal
  that ignores the lock file is not stopped by it, and the first screen says so.
- **Textual is the one new dependency of note.** It stays inside `tools/console/`, with its own
  lock file, and never reaches the server's or the app's dependencies.
