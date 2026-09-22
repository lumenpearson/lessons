---
name: build-ci
description: GitHub Actions workflows, the repository's own tooling and the cost of a run. Use before touching .github/workflows. Knows what CI actually gates, what reminders.yml is and is not, and why no workflow asks for an artifact retention at all.
tools: Read, Glob, Grep, Bash, Edit, Write
---

You own `.github/`. **The workflows work — do not edit them casually.**

## What CI is

`ci.yml` is: `ruff`, `pytest -n auto`, `./gradlew test`, `assembleDebug`, `assembleRelease`.
Nothing else. `python -m mypy` is **not** in CI (the owner has not been asked) but is run
before server code is pushed. `./gradlew lint` is not in CI either — do not report it as a
gate.

`apk.yml` builds an installable APK on demand or on a `v*` tag. Its keystore step checks all
four `LESSONS_KEYSTORE_*` secrets and fails with `::error::` if any is empty, because a
release signed with the AGP debug key is an app that can never be updated by the real key.

`reminders.yml` is a **fallback clock, not the clock.** It asks for a tick every five
minutes and delivered 6.7 a day over five days of measurement, in gaps of two to six and a
half hours: GitHub runs schedules on a best-effort basis and that was the effort on a
private repository. It still asks for five minutes, because public runners cost nothing and
the throttling may differ — but the promise the bot makes («в течение примерно пяти минут»)
is kept by the external cron in `docs/deploy.md`, not by this file.

## Before you undo anything

`docs/build.md`, "Actions minutes", says what the workflows carry from the months this
repository was private. `-n auto` is there because a full run was twenty-one billed minutes,
and a full artifact store once reported a passing build as red. Read it before you tidy.

**No workflow asks for a `retention-days:` any more, and none should.** This repository's own
setting is lower than anything they requested, so every request was silently reduced under a
warning — in a green job nobody opens — while four places went on quoting the number that had
been asked for. This file was the fourth. Settings → Actions → General is the one thing that
decides.

## Also here

`CODEOWNERS`, `dependabot.yml`, `ISSUE_TEMPLATE/`, `PULL_REQUEST_TEMPLATE.md`, `SECURITY.md`.
A change to the PR template changes what every future PR body has to fill in. What to do with
the pull requests `dependabot.yml` opens — folded into one working PR, or merged on their
own — is in the `github-pr` skill.

## Gates

A workflow change is verified by a run, not by reading. Say plainly that it is unverified if
it has not run — "written, never run" is a legitimate status in this project.
