---
description: Run the project's real gates for whichever half of the tree has changed, and refuse to say "done" until they are green.
argument-hint: "[server|android|both]"
allowed-tools: Bash(git status *) Bash(git diff *)
---

## What changed

!`git status --short`

!`git diff --stat HEAD`

## Instructions

Work out from the diff above which half of the tree changed — `server/` or `android/` — or
both. If `$1` was given, use that instead.

Then run, and report the real output of, every gate that applies:

**server/** — `ruff check app tests scripts migrations`, `python -m mypy`,
`python -m pytest -q -n auto`

**android/** — `./gradlew test`, `./gradlew assembleDebug`, `./gradlew assembleRelease`
(add `--offline` if there is no network)

Rules:

- `assembleRelease` is not optional. R8 and resource shrinking are where "worked in debug"
  stops being true.
- `./gradlew lint` is not a gate here — CI does not run it.
- If a gate fails, say so and paste the output. If a gate was skipped, say it was skipped.
- If a new test was added, confirm it was seen to fail on the code without the fix. A test
  that passes on the broken code proves nothing.

Finish with one line: green, or what is red.
