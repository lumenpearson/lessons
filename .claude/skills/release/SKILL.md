---
name: release
description: Cut a build of the Android app — gates, migration order, signing, tag — and write release notes this project would accept. Use when a version is about to move or an APK is about to be published.
---

# Cutting a release

## 1. Gates, all of them

From `server/`: `ruff check app tests scripts migrations`, `python -m mypy`,
`python -m pytest -q -n auto`.
From `android/`: `./gradlew test`, `./gradlew assembleDebug`, `./gradlew assembleRelease`.

`assembleRelease` is not optional. R8 and resource shrinking are where "worked in debug"
stops being true, which is why CI builds both on every push.

## 2. Migration, in the right direction

An additive revision goes on **before** the merge that deploys the code; a `UNIQUE` or a
`NOT NULL` goes on **after**. `GET /api/v1/warmup` says which side the database is on and
names both revisions.

## 3. Signing

`apk.yml` needs all four `LESSONS_KEYSTORE_*` secrets and fails with `::error::` if any is
empty. That failure is the feature: an APK signed with the AGP debug key can never be updated
by the real key on a phone that already installed it. The keystore and its passwords come
from environment variables or `~/.gradle/gradle.properties` — never from the repository,
where `*.jks` and `keystore.properties` are gitignored.

## 4. Tag

`apk.yml` builds an installable APK on demand or on a `v*` tag.

## 5. Notes

The README's «Честный статус» standard applies. Name what was verified and by what — a test,
a CI run, a deploy, a phone. Name what was only written. «Написано, не запускалось» is a
legitimate line; a claim of verification that did not happen is not.

Redact `<redacted>` for anything that is a credential, and keep deployment ids and the
database endpoint out of the notes.
