---
name: gates
description: Run the checks this project actually gates on, in the right order, for the half of the tree you touched. Use before every commit, before every push and before claiming anything is done.
---

# The gates

**No commit without lint and tests green.** Touch both halves and run both.

## Server, from `server/`

Setup once: `python3 -m venv .venv && .venv/bin/pip install -r ../requirements.txt -e ".[dev]"`
— the lock Vercel installs, then the package, which is exactly CI's install (#192). Without
the `-r` you test on whatever is newest today, which production does not run.

1. `ruff check app tests scripts migrations` — exactly what CI lints. `ruff check .` from
   `server/` covers the same tree.
2. `python -m mypy` — one question of all 197 modules, in seconds: does anything reach for an
   attribute its type does not have? Every other error code is switched off by name in
   `pyproject.toml`, with its count and its reason. **In CI** since 27 September 2026, right
   after ruff — the owner asked for it through that day's audit (#210) — and still worth
   running before a push: seconds here, minutes there. It is the thing that reproduces the
   «🗓 Четверти» crash.
3. `python -m pytest -q -n auto` — 2175 tests today. Serial takes about five minutes;
   `-n auto` finishes in a third of that and is what CI runs.

Narrower while iterating: `python -m pytest -q tests/test_schedule.py -k parity`.

## Android, from `android/`

1. `./gradlew test` — all JVM unit tests across the five modules, 770 today.
2. `./gradlew assembleDebug`
3. `./gradlew assembleRelease` — **not optional.** CI builds both on every push, because R8
   and resource shrinking are where "worked in debug" stops being true.
4. `./gradlew detekt` — static analysis of the Kotlin, all five modules, about half a
   minute. Neither `test` nor the assembles run it, so it is a gate of its own. It fails on
   a finding the module's `detekt-baseline.xml` does not hold: fix it, or `@Suppress` it at
   the declaration with the reason. Do not regenerate a baseline to make a new finding go
   away — `./gradlew detektBaseline` is for after a merge that moves code between files
   (`docs/build.md`, "detekt").

Narrower: `./gradlew :core:model:test --tests '*ScheduleEngineTest*'`,
`./gradlew :app:detekt`.

In a sandbox with no network every Gradle invocation needs `--offline`.

`./gradlew lint` runs the AGP Android lint. **CI does not run it — do not report it as a
gate.**

## Contract, from the repository root

Only when `proto/`, `buf.*` or `server/app/contract/` changed, which is CI's own filter. Buf 1.73.0 is fetched by hand and kept outside the repository
(`docs/build.md`, «The v2 contract and Buf»).

1. `buf lint`: STANDARD, as CI runs it.
2. `buf generate`, then commit `server/app/contract/` with the proto change. CI regenerates
   and fails on any difference.
3. `python -m pytest -q -p no:xdist tests/test_contract.py` from `server/`: every method's
   route, credential, least role and idempotency against the resource map.

## What CI is

`.github/workflows/ci.yml`: ruff, `python -m mypy`, pytest `-n auto`, `./gradlew test`, both
assembles, and `./gradlew detekt` as a step of its own after them; and «Contract (Buf)» when
the contract changed. Nothing else.

## Two rules about evidence

- **A test that passes on the broken code proves nothing.** Revert the fix, watch the new
  test go red, put the fix back.
- **Report what happened.** If a gate failed, say so and paste the output. If a gate was
  skipped, say it was skipped. "Written, never run" is a legitimate status in this
  project; a claim of verification that did not happen is not.
