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

## 4. The milestone closes before the tag is cut

**The milestone is what decides what is in the release**, so it is settled first and the
tag is the version it is named for — `v0.8.0 — On a device` is tagged `v0.8.0`.

Before tagging, walk the milestone and check three things:

- **every issue on it is closed**, or moved off it. An open issue on a milestone being
  released is either work that did not happen or a milestone that was wrong;
- **every closed issue names the pull request that closed it.** `Closes #NN` in the pull
  request body does this automatically — the issue then shows the merge that closed it,
  which is what the notes are built from;
- **every pull request on it carries the milestone**, set with `issue_write` when it was
  opened. One left bare is a change the notes will not mention.

**If the terms or the privacy policy changed since the last tag, raise `edition` and set
`effective` in `docs/legal/legal.json` before tagging**, in the commit the tag points at. The
APK bundles `docs/legal/` as it is at build time and names the edition in its sheet, so a
tag cut first ships changed texts under the old edition and date.

Then tag. A `v*` tag makes `apk.yml` build an installable APK **and** create a GitHub
release with it attached, so the tag is the moment the notes have to be ready.

```bash
git tag -a v0.8.0 -m 'On a device'
git push origin v0.8.0
```

**Nothing in this repository has ever been tagged or released** — no `v*` tag, no release,
and `versionName` is still the `0.1.0` default in `android/app/build.gradle.kts`. The first
one sets the pattern for every one after it, so it is worth doing properly rather than
quickly. Move `versionName` in the same commit that the tag points at, or the app will
report a version nobody can match to a tag.

## 5. Notes: every fix explained, with its issue and its pull request

**A release note is not a list of commit subjects.** Each entry says what was wrong, what it
does now, and carries the numbers so a reader can go and look:

```markdown
### Fixed

- **A tab dragged to another slot went back where it came from.** The gesture computed the
  landing slot and then reported the one it started from, so the new order was never
  stored — on screen it looked right the whole way, because only the drop was stale.
  #108 / #85
```

- **Group by what it means to a reader** — Added, Fixed, Changed — not by area or by module.
- **Name the issue and the pull request for every entry.** The issue says what was wrong and
  the pull request says what was done; a note with only one of them makes the other
  unfindable.
- **Anything with no issue does not belong in the notes**, and that is the check that keeps
  the rule honest: if a fix shipped without one, file it now, closed, pointing at the
  pull request that carried it.
- **A new tag carries a new milestone.** The one just released is closed on GitHub, and the
  next version's is created before work starts on it — a session here cannot create one, so
  ask, with the title and the description already written.

The README's "Honest status" standard applies throughout. Name what was verified and by
what — a test, a CI run, a deploy, a phone. Name what was only written. "Written, never run"
is a legitimate line; a claim of verification that did not happen is not. **On the first
release this matters more than usual**: almost nothing in this project has been seen on a
device, and the notes must not imply otherwise.

Redact `<redacted>` for anything that is a credential, and keep deployment ids and the
database endpoint out of the notes.
