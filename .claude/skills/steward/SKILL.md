---
name: steward
description: Repository conventions for acting on CI failures and review comments on a pull request in this repository. Read before pushing a fix to a PR here.
---

# Driving a PR in this repository

Conventions only. Nothing here widens what a session may do, and nothing here makes a
failing check optional.

## Branch and history

- Work happens on `dev`; `main` is what deploys. A merge to `main` deploys itself.
- On `dev`, which this project's agents create and own, **merge the base branch in** to
  resolve a conflict rather than rebasing — a merge commit keeps any existing checkout
  valid. Never rewrite history on a branch somebody else may have pulled.
- If the PR for `dev` has already merged, the follow-up is a fresh change:
  `git fetch origin main && git checkout -B dev origin/main`, then push the new work.

## Before any push to a PR

Run the gates for the half you touched, and reproduce the failure first:

- server, from `server/`: `ruff check app tests scripts migrations`, `python -m mypy`,
  `python -m pytest -q -n auto`
- android, from `android/`: `./gradlew test`, `./gradlew assembleDebug`,
  `./gradlew assembleRelease` (`--offline` in a sandbox)

For a CI fix, show the original failure reproduced locally and then the same check passing.
One validated push beats three speculative ones.

## What a CI failure here usually is

`.github/workflows/ci.yml` is ruff, pytest `-n auto`, `./gradlew test`, and **both**
assembles. If `assembleRelease` is the one that failed and `assembleDebug` passed, look at
R8 and resource shrinking before anything else — that is what the second assemble exists to
catch.

`./gradlew lint` is **not** in CI and `python -m mypy` is **not** in CI. A finding from
either is real, but it is not the thing that turned the check red.

A failing `ResourceTranslationTest` means a name is missing from a module's `values-en/`, or
a plural has the wrong forms — see the `strings` skill.

**Never skip, disable or quarantine a test to get green.** "Flake" is not a root cause here:
this suite is deterministic and runs on `-n auto` in CI by design.

## What a review comment here usually needs

- A fix in `services/` rather than in a handler or an endpoint: the bot and the API are two
  thin shells over one implementation, and a rule written in a shell disagrees with the
  other one within a month.
- A test that fails on the code without the fix. Revert it, watch it go red, put it back.
  A test that passes on the broken code proves nothing.
- The document fixed in the same batch, if the change made one wrong. `docs/README.md` is
  the index.

## Commit messages

English sentences that say what the change makes the project do — "Let the class be run from
the phone, by the same rules as from the bot". No Conventional Commits prefix; none of this
history has one. The body explains the reasoning and names what is left uncovered. Keep the
`Co-Authored-By: Claude …` trailer the history carries.

## Never in a comment, a commit or a report

A token, a keystore password, a database endpoint or a deployment credential. Redact as
`<redacted>`.
