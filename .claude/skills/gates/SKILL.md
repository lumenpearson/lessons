---
name: gates
description: Run the checks this project actually gates on, in the right order, for the half of the tree you touched. Use before every commit, before every push and before claiming anything is done.
---

# The gates

**No commit without lint and tests green.** Touch both halves and run both.

## Server, from `server/`

Setup once: `python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"`

1. `ruff check app tests scripts migrations` — exactly what CI lints. `ruff check .` from
   `server/` covers the same tree.
2. `python -m mypy` — one question of all 81 modules, in seconds: does anything reach for an
   attribute its type does not have? Every other error code is switched off by name in
   `pyproject.toml`, with its count and its reason. **Not in CI** — the owner has not been
   asked — but run it before you push server code. It is the thing that reproduces the
   «🗓 Четверти» crash.
3. `python -m pytest -q -n auto` — 1559 tests today. Serial takes about five minutes;
   `-n auto` finishes in a third of that and is what CI runs.

Narrower while iterating: `python -m pytest -q tests/test_schedule.py -k parity`.

## Android, from `android/`

1. `./gradlew test` — all JVM unit tests across the five modules, 770 today.
2. `./gradlew assembleDebug`
3. `./gradlew assembleRelease` — **not optional.** CI builds both on every push, because R8
   and resource shrinking are where "worked in debug" stops being true.

Narrower: `./gradlew :core:model:test --tests '*ScheduleEngineTest*'`.

In a sandbox with no network every Gradle invocation needs `--offline`.

Every one of them also needs **Python with `fonttools`** on Python 3.10 or newer
(`python3 -m pip install fonttools`): the bundled typeface is a source, and the build
freezes the variation axes the app never moves. Without it the invocation stops naming
`-Plessons.font.axes=all`, which ships the font as it came — correct, and two megabytes
larger. Run the gates at the default, because that is what CI and the APK ship.

`./gradlew lint` runs the AGP Android lint. **CI does not run it — do not report it as a
gate.**

## What CI is

`.github/workflows/ci.yml`: ruff, pytest `-n auto`, `./gradlew test`, both assembles.
Nothing else.

## Two rules about evidence

- **A test that passes on the broken code proves nothing.** Revert the fix, watch the new
  test go red, put the fix back.
- **Report what happened.** If a gate failed, say so and paste the output. If a gate was
  skipped, say it was skipped. "Written, never run" is a legitimate status in this
  project; a claim of verification that did not happen is not.
