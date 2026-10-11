# Android decomposition, pull request A: `LessonsPreferences`, `GithubRepositoryImpl` and the workers cap — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** drafted on 11 October 2026 against `6354c3d`, the merge of #422, read-only. Nothing was built, run or committed while writing it. Every count below was taken with `grep`, `awk` and `git`, and every line number is a line of the file **at `6354c3d`**: find a declaration by its name, because the earlier tasks move the lines below it. Task 0 commits this plan as the first commit of `android/decomposition-a`, which was cut from `main` at `6354c3d`. The run at each task's gate is the truth; where it disagrees with a number here, the run wins and the task says what to do.

**Goal:** the first of sub-project 4's three pull requests ([design](2026-10-05-android-decomposition-design.md), decision 8).
- `LessonsPreferences.kt` (883 lines) splits into the keys, the settings codec, the bundle tags and the diary's and the shell's keys (decision 6).
- `GithubRepositoryImpl.kt` (705 lines) splits along its four concerns: the OAuth device flow, the pull-request helpers, the issues, and the read of the repository's permissions for the developer mode's gate (decision 6).
- `docs/build.md` says why every Gradle run on the maintainer's machine carries `--max-workers=2` (decision 9 and the owner's answer to question 1).
- Nothing a test can see changes (decision 1). Two characterization tests come first, so that the moves are proved by something.

**Architecture:**
- **`:core:data/datastore/`** gains five files beside `LessonsPreferences.kt`:
  - `ShellKeys.kt`: `ShellKeys` and `shellState`.
  - `DiaryKeys.kt`: `DiaryKeys` and its five functions.
  - `PreferenceKeys.kt`: an `internal object` holding what was the class's private companion.
  - `SettingsCodec.kt`: `Preferences.toSettings` and `MutablePreferences.putSettings`, the settings' two halves side by side.
  - `BundleTags.kt`: an `internal class` over the store, holding the bundle tags and the schedule's fingerprint.

  `LessonsPreferences` keeps the class, the sessions and the shell state, and hands the seven tag and fingerprint calls on in one line each. Its callers in `di/` and `notifications/` change nothing.
- **`:core:data/github/`** gains four classes beside `GithubRepositoryImpl.kt`: `GithubDeviceFlow`, `GithubPullRequests`, `GithubIssues` and `GithubPermissions`.
  - The repository builds them and delegates to them.
  - They share `GithubPreferences`, which holds no token in memory: every call reads it, and a `401` clears it.
  - They also share one `Lazy<String>` user agent, and three constants that become `internal`.
- **The detekt baseline** of `:core:data` is rewritten twice, each time in a commit of its own, after each file's split. Its diff is read against the entries listed here.

**Tech Stack:** Kotlin 2.4.20 (compiled by AGP 9.4.1), AndroidX DataStore Preferences 1.2.0, kotlinx.coroutines 1.11.0, OkHttp 5.5.0, kotlinx.serialization, JUnit 4.13.2 on the JVM, detekt 2.0.0-alpha.6 without type resolution, and Gradle 9.8.0 through the wrapper on JDK 21.

**Spec:**
- `docs/specs/2026-10-05-android-decomposition-design.md`, approved on 5 October 2026. Decisions 1, 2, 6, 8 and 9 are binding here.
- `docs/specs/2026-10-03-one-contract-design.md`, section 5, «Android (sub-project 4)».
- `docs/specs/2026-10-05-build-console-design.md`, decision 2: the one spelling of the flag.
- Epic #273. No issue is filed for this pull request: it fixes no defect, and nothing found while planning was one (see «What was verified»).

## How pull request A is cut

One pull request from `android/decomposition-a` to `main`, on milestone 11 (`v0.10.0 — One contract: REST v2, Connect and native gRPC, build console`), carrying `Refs #273`. There are twelve tasks and twelve commits.

| Task | Commit | Tests added | `:core:data` after | Android total after |
| --- | --- | --- | --- | --- |
| 0 | This plan (plus two tools outside the repository) | — | 615 | 1730 |
| 1 | The workers cap in `docs/build.md` | — | 615 | 1730 |
| 2 | Hold the settings' round trip | 3 | 618 | 1733 |
| 3 | Hold the tags, the fingerprint and the roles | 5 | 623 | 1738 |
| 4 | `ShellKeys.kt`, `DiaryKeys.kt` | — | 623 | 1738 |
| 5 | `PreferenceKeys.kt` | — | 623 | 1738 |
| 6 | `SettingsCodec.kt` | — | 623 | 1738 |
| 7 | `BundleTags.kt` | — | 623 | 1738 |
| 8 | `:core:data`'s baseline after `LessonsPreferences` | — | 623 | 1738 |
| 9 | `GithubRepositoryImpl` into four | — | 623 | 1738 |
| 10 | `:core:data`'s baseline after `GithubRepositoryImpl` | — | 623 | 1738 |
| 11 | Gates, counts, documents, close-out | — | 623 | 1738 |

**Base.** 1730 tests: `:core:model` 125, `:core:data` 615, `:core:designsystem` 196, `:widget` 126, `:app` 668.
- That is a count of `@Test` annotations, taken by `grep` on 11 October.
- It equals, module by module, the count `docs/architecture.md` gives from #417's run.
- The expected total after this pull request is **1738**.
- If the first real count is different, shift every figure by the difference.

## Rulings

The design left these open. Each was decided here.

1. **`LessonsPreferences` splits in four move commits, then one baseline commit. `GithubRepositoryImpl` splits in one move commit, then one baseline commit.**
   - Decision 2's «two commits» is about keeping the baseline out of every commit that carries code, and that holds.
   - `LessonsPreferences`' four pieces are of four kinds:
     - a pure move of two objects;
     - a key object whose qualifier rewrites 130 references;
     - a function pair;
     - a class over the store.

     In one diff, the 130 rewritten references would drown the rest.
   - `GithubRepositoryImpl`'s four pieces are of one kind, and they share one change: the user agent becomes a `Lazy` handed to each. Made piece by piece, that change would touch every remaining caller twice.
   - Smaller commits also suit a machine that crashes: each one is committed the moment it is green.
2. **The order:**
   1. `ShellKeys` and `DiaryKeys`: a pure move, with nothing widened.
   2. `PreferenceKeys`, which the codec and the bundle tags both need outside the class.
   3. The codec.
   4. `BundleTags`.

   Then `GithubRepositoryImpl`, after `LessonsPreferences`, in decision 8's order.
3. **Two characterization tests come first, and both go through the store.**
   - **`SettingsRoundTripTest`** is the one decision 6 asks for.
   - **`BundleTagsAndRolesTest`** is added because no test reaches the bundle tags, the schedule's fingerprint or the remembered roles. `docs/history.md` records the tag sweep's prefix arithmetic as «written and not run».
   - Decision 1 allows a test «that pins what it is about to move, where nothing tests it today».
   - Both are written through `LessonsPreferences`' internal two-argument constructor and the tests' `MemoryStore`, not through the new functions. They therefore run green on the code before the split, and no split commit has to touch them.
4. **The round trip writes a value that differs from the defaults in every field.** A reflective check over the declared fields of `AppSettings` and `AlertPreferences` holds that. A field added later without a key would come back as its default; a round trip of a default value would not notice, and the reflective check would fail on it.
5. **The keys keep their names.**
   - `KEY_BASE_URL` becomes `PreferenceKeys.KEY_BASE_URL`, not `PreferenceKeys.BASE_URL` in `MembershipKeys`' style.
   - The qualification is then one mechanical insertion, which the move checker can undo, and the names stay greppable across the history.
   - Every reference becomes `PreferenceKeys.X`, as decision 6 says. That includes the 17 in the class body outside the codec, not only the codec's.
6. **The write half is `internal fun MutablePreferences.putSettings(updated: AppSettings)`.**
   - It is named after its sibling `putDiarySession`, and it is an extension, like the `toSettings` it pairs with.
   - Its parameter keeps the name `updated`, so the 56 lines of its body move verbatim apart from `prefs[` becoming `this[`.
   - `toSettings` keeps its name and its receiver. It goes from a `private` member to an `internal` top-level function.
7. **`rememberRole`'s one line that the qualifier takes to 131 characters is rewritten in Task 5 with a local `key`.** It is not baselined: decision 2 says a finding that is neither moved nor carried is fixed. It is the only line over detekt's 120 that the split creates. Task 5's inventory measured every line the qualifier touches.
8. **`removeKeysStartingWith` becomes a top-level `internal` extension in `LessonsPreferences.kt`.**
   - It reads nothing of the class.
   - Both `clearSession` (the roles) and `BundleTags` (the tags) call it.
   - It stays in its file.
9. **`BundleTags` is `internal class BundleTags(private val store: LessonsPreferences)`.**
   - `LessonsPreferences` builds it as `private val bundleTags = BundleTags(this)`, declared after the last of the class's other properties. Nothing `BundleTags` could ever read is uninitialized when it is built.
   - `write` and `preferences` become `internal`, as decision 6 lists.
   - Handing it the flow and a function reference would have widened nothing. The brief asks for decision 6 exactly, so the widening is taken and listed.
10. **The seven delegates stand where their methods stood**, in the same order, with `lastRole` and `rememberRole` between them as today. `LessonsPreferences.kt`'s diff then shows bodies changing, not methods moving.
11. **In `GithubRepositoryImpl`'s split:**
    - **Names.** The collaborators keep the repository's method names (`signIn`, `cancelSignIn`, `fileIssue`, `openTranslationPullRequest`, `repositoryPermissions`), without `override`.
    - **Constants.** Each companion constant goes to the one class that reads it. `TAG`, `HTTP_CREATED` and `HTTP_UNAUTHORISED` become top-level `internal const val`s in `GithubRepositoryImpl.kt`. `GithubApi`'s own `TAG` is a member of its object, so it shadows the top-level one and nothing clashes.
    - **The user agent.** It becomes one `Lazy<String>` that the repository makes and hands to all four. Each collaborator reads it through `private val userAgent: String by userAgent`, so the bodies move verbatim.
    - **`isConfigured`.** It is `GithubDeviceFlow`'s, and the repository reads it. `clientId` and `scope` become plain constructor parameters of the repository; the container passes them by name, unchanged.
12. **The only comment edits in a move commit:**
    - **New KDoc.** Each new class or object gets a short KDoc that says why it exists, and the repository's KDoc gains one paragraph.
    - **KDoc links.** A link that a move leaves pointing at nothing is requalified. `get`'s `[fileIssue]` becomes `[GithubIssues.fileIssue]`, the only one.
13. **The workers cap goes into `docs/build.md`, «Locally», in its own commit, and nowhere else.** HANDOVER's cheat-sheet points there. The text names neither the JDK's path nor the SDK's F: path: those are this machine's, and they live in this plan.
14. **Baselines are rewritten with `:core:data:detektBaseline`, not `detektBaseline`.**
    - Only `:core:data`'s files moved.
    - The four other baselines must come out byte for byte the same, and the module-scoped task cannot touch them.
    - The final `./gradlew detekt` proves the four still hold.
15. **No commit carries a trailer.** The brief for this batch says so, as #406's plan did, although the `github-pr` skill still asks for one.
16. **Counts.** `@Test`'s `grep` gives the plan's numbers. Task 11 reads the real ones from the debug unit-test reports, and the documents take those. `docs/build.md`'s baseline totals (492; `MagicNumber` 218, `ReturnCount` 81, `MaxLineLength` 65) are recounted from the five files after Task 10.

## Global Constraints

- **A split is a move** (decision 1).
  - No name a caller uses is renamed.
  - No behaviour changes.
  - `private` is widened to `internal`, and no further, only for the declarations listed under «What is widened».
  - Every split commit (Tasks 4–7 and 9) splits one file and touches nothing else.
  - Tests are added only in Tasks 2 and 3, and the baseline changes only in Tasks 8 and 10.
- **Language.** English in code, comments, commit messages and the pull request. Russian only as product text, and in guillemets wherever it is quoted.
  - This pull request adds no user-facing string. `values/` and `values-en/` are untouched, so «every Russian string has its English twin» stays true by construction.
  - `ResourceTranslationTest` runs in Task 11's gate anyway.
- **No commit trailers.** Every message is an English sentence saying what the change makes the project do, with no prefix. The body explains the reasoning and names what is not covered. Write it to `C:/Users/lumen/AppData/Local/Temp/decompose-a/msg.txt` and commit with `git commit -F`.
- **`compose-stability.conf`'s promise.** Neither the file nor anything in `:core:model` is touched, so `StabilityPromiseTest` holds.
  - No `var` is added to `:core:model`.
  - The one `var` that moves, `pollJob`, goes from `:core:data` to `:core:data`, a module the file deliberately leaves out of the promise.
- **Security, absolute.**
  - **Never read, `cat`, `grep`, open, scan or list the contents of `~/.gradle/gradle.properties`.** It holds the release keystore's passwords; on 5 October 2026 an agent printed one into a transcript (#318).
  - The same goes for **any `local.properties`**: the worktree has none, which was checked by its existence alone.
  - **Never read `server/.env`.**
  - **Never walk `~/.gradle`**, not even to look for zero-filled files after a crash: a scan reads every file, `gradle.properties` included. If a cache there is suspect, `--refresh-dependencies` is the remedy, or ask the controller.
  - Stage files by path. Never use `git add -A`: a JVM crash leaves `hs_err_pid*.log` in `android/`.
  - No keystore path, password, token or chat id goes into a commit, a log or the pull request.
- **This machine (faulty RAM; it blue-screened twice on 11 October).**
  - **One heavy job at a time.** A Gradle build or a pytest run of more than a few files runs alone. Never start a second Gradle command, or the server suite, while one is in flight.
  - **Commit the moment a step is green.**
  - **Every Gradle command takes this machine's form,** written out in full in every step: JDK 21, which the shell's own `JAVA_HOME` (17) is not; the SDK by its F: path, because the AppData path is a junction this app cannot resolve; and `--max-workers=2`.
  - Run every Gradle command with the Bash tool's `run_in_background`, and wait for its completion notice. A foreground call is cut at ten minutes, and a build cut off mid-write is one more half-written file.
  - In an Android module, `test --tests` is refused, because `:core:data:test` aggregates two variants. One class runs as `:core:data:testDebugUnitTest --tests '…'`.
  - Agent threads have their working directory reset between calls, so every command below starts with its own `cd`.
- **After a crash:**
  1. Run `git -C /c/Users/lumen/StudioProjects/lessons-live-gate status`.
  2. If it fails, or `index` or `COMMIT_EDITMSG` under `C:/Users/lumen/StudioProjects/lessons/.git/worktrees/lessons-live-gate/` is zero-filled, move both aside, then run `git reset` (the index only, never `--hard`), then `git fsck`.
  3. Check every file the task had touched for NUL bytes. Python can read the bytes; `grep -P` fails in Git Bash's locale.
  4. Delete `android/core/data/build` before the next Gradle run, because Kotlin's incremental caches there zero-fill too.
  5. Uncommitted work in the tree has survived every crash so far, so re-read it rather than redoing it.
- **Other agents.** Another agent may be working in this tree. Run `git status` before touching a file you did not open, and revert nothing you did not write.
- **This plan is read by `server/tests/test_schema_version.py`.** Like every Markdown file under `docs/`, it must never state the schema head as a sentence with a backticked revision in it, or quote a warmup answer.

## What is widened (decision 1)

| Declaration | At `6354c3d` | After | Why |
| --- | --- | --- | --- |
| The 62 members of `LessonsPreferences`' companion (57 `KEY_*` keys, `bundleTagKey`, `roleKey`, `bundleTagPrefix`, `ROLE_PREFIX`, `BUNDLE_TAG_PREFIX`) | members of a `private companion object` | members of `internal object PreferenceKeys` | `SettingsCodec.kt` and `BundleTags.kt` read them |
| `Preferences.toSettings()` | `private`, a member extension | `internal`, top level of `SettingsCodec.kt` | the pair lives in a file of its own |
| the write half of `updateSettings` | inline, in the method | `internal fun MutablePreferences.putSettings`, beside it | the same |
| `LessonsPreferences.preferences` | `private val` | `internal val` | `BundleTags` reads through the store (decision 6) |
| `LessonsPreferences.write` | `private suspend fun` | `internal suspend fun` | `BundleTags` writes through the store, so its writes refresh the credentials like every other |
| `MutablePreferences.removeKeysStartingWith` | `private`, a member extension | `internal`, top level of `LessonsPreferences.kt` | `BundleTags` sweeps tags with it; `clearSession` sweeps roles |
| `TAG`, `HTTP_CREATED`, `HTTP_UNAUTHORISED` | `const val` in `GithubRepositoryImpl`'s private companion | `internal const val`, top level of `GithubRepositoryImpl.kt` | four collaborator files read them |

**Not widened:**
- `dataStore`, `vault`, `opened` and `credentials`' internals. Keeping `dataStore` private is what forces every write through `write()`.
- Every GitHub helper (`ensureFork`, `get`, `contents`, `pollOnce`, …). Each stays `private` in the one class that calls it.
- `MIN_INTERVAL_SECONDS`, `SLOW_DOWN_SECONDS` and `EXPIRY_GRACE_MILLIS`. They stay `private` top-level declarations beside `pollUntilDecided`.
- `ShellKeys`, `DiaryKeys` and their functions, which were `internal` already.

## Review Focus

These are the five ways this pull request is likeliest to go wrong, each with the step that pins it.

1. **A setting written under one key and read under another, or lost from one half.**
   - `SettingsRoundTripTest` (Task 2) writes every field away from its default and reads it back.
   - Its reflective check fails if a field is added without being changed in the test.
   - It runs unchanged in Task 5, which rewrites 130 key references, and in Task 6, which moves both halves.
2. **A sweep by prefix that takes the wrong keys,** once the prefixes and `removeKeysStartingWith` have moved.
   - `BundleTagsAndRolesTest` (Task 3) covers class 1 against classes 12 and 100, the tags against the roles and the fingerprint, and a sign-out against the tags.
   - It was seen failing with the trailing separator removed.
   - It runs unchanged in Tasks 5 and 7.
3. **A changed line hidden among moved ones.**
   - Task 0's `moved.py` compares the old file's lines with the new files' lines, after undoing only the rewrites the task names.
   - Each split task lists the exact `-` lines it may print; any other `-` line is a changed line.
   - `git diff --color-moved=dimmed-zebra --color-moved-ws=allow-indentation-change` shows the same for the reviewer.
4. **A new detekt finding absorbed into the baseline.**
   - Tasks 8 and 10 list every entry expected to go and to come. Anything else is fixed in code, or suppressed in place with its reason, and never baselined.
   - The one new finding the split creates, `rememberRole`'s long line, is fixed in Task 5 before any baseline is written.
5. **The GitHub split changing who holds the lock or who forgets a dead token.** Nothing can test the four classes, because the HTTP client and `API_BASE` are global. So Task 9 pins the shared state by count, against `6354c3d`'s:
   - `synchronized(lock)` appears twice, both in `GithubDeviceFlow.kt`.
   - `preferences.clear()` appears four times, one in each file that meets a `401` or signs out.
   - `HTTP_UNAUTHORISED` has three uses, and `HTTP_CREATED` three.
   - There is one `= GithubPreferences(` and one `lazy {`, both in `GithubRepositoryImpl.kt`.

   The move checker allows exactly five `-` lines, and `DeviceFlowPollTest` runs unchanged.

## Risks

- **The baseline's signatures are predicted, not read.**
  - **The putSettings `LongMethod` entry.** By a hand count, `putSettings` is 59 lines of code, plus its signature and its brace: 61, over detekt's 60. So `updateSettings`' `LongMethod` is expected to move to `putSettings`. If detekt counts 60 or fewer, the entry disappears instead, and Task 8 says so.
  - **The `1000L` `MagicNumber` entry.** It carries no class, although the literal also stands in `runDeviceFlow`, inside the class. If it comes back as two entries, the second one is the same literal on the same line.
  - Task 10 holds both cases to that rule.
- **`internal val preferences` exposes the raw flow to all of `:core:data`.** Its two bearers are sealed (#201), so a reader that skips `opened` gets ciphertext. Sent as a bearer, that is a `401`, which costs a sign-in. `BundleTags` reads no token. The KDoc on `opened` already says what must read through it.
- **`BundleTags(this)` hands out `this` from an initializer.** It is safe because `BundleTags` stores the reference and reads nothing at construction. A future `init` in `BundleTags` would be read after every other property of the store, because the property is declared last.
- **No test reaches the four GitHub classes.** The design said so rather than papering over it (decision 6). The compile, `DeviceFlowPollTest` (6 tests, `pollUntilDecided` only), the move checker's exact `-` list, Task 9's counts and review are the check. A constant moved into the wrong companion fails the compile; a `401` that no longer clears the token fails Task 9's count.
- **Top-level functions change their JVM class.** `LessonsPreferencesKt.shellState` becomes `ShellKeysKt.shellState`, and the same happens to the diary functions, `toSettings`, `pollUntilDecided` and its siblings.
  - No keep rule, reflection or Java source names those classes; `git grep` found none.
  - `:core:data` is not minified.
  - `:app`'s R8 sees the same code under other names. Both assembles run anyway.
- **Line endings.** The working tree is CRLF (`core.autocrlf=true`) and the index LF. New files written with LF are normalised on commit, and git's warning about it is harmless.
- **The machine.** A crash in the middle of a commit has zero-filled the git index before. The recovery is in «Global Constraints», and committing early is what keeps the loss to one step.
- **HANDOVER's order.** Section 7's «Next for the programme» put #407 before sub-project 4. The close-out reads #407's state from GitHub and says what is now next, rather than leaving the two orders disagreeing.

## What was verified while writing this plan (11 October 2026, worktree `lessons-live-gate`, `6354c3d`)

**The design's survey of `521c7a5` still holds at `6354c3d`.**
- `git log 521c7a5..6354c3d` and `git diff 521c7a5 6354c3d` over the two files, `:core:data`'s baseline, every caller and every test below are empty.
- `LessonsPreferences.kt` is 883 lines, with 7 baseline entries.
- `GithubRepositoryImpl.kt` is 705 lines, with 10 entries.
- `:core:data`'s baseline holds 201 entries, at both commits.
- The five baselines hold 492: `MagicNumber` 218, `ReturnCount` 81, `MaxLineLength` 65.
- **No remote branch changes either file, the baseline or `docs/build.md`** relative to its merge base with `main`.

**Who reaches `LessonsPreferences`.**
- **Built by:**
  - `di/LessonsContainer.kt:187`;
  - `notifications/SchoolAlerts.kt:359, 455, 468`;
  - the tests `RequestCredentialsTest`, `SealedTokensTest`, `ServerNeedsHttpsTest` and `ServerStatusReadTest`.
- **Members used by:**
  - `LessonsContainer`: `currentSettings`, `followCredentials`, `credentials`, `session`, and the five tag calls at 254–274;
  - `SchoolAlerts`: `currentSettings`, `scheduleFingerprint`, `writeScheduleFingerprint`;
  - `SessionRepositoryImpl`: the sessions;
  - `SettingsRepositoryImpl`: `settings`, `updateSettings`, `currentSettings`, `languageBlocking`;
  - `DeviceLinkRepositoryImpl`: `lastRole`, `rememberRole`;
  - `DiaryRepositoryImpl`, through the `DiarySessionStore` interface.
- **Imported by package and name:** `ShellKeys`, `DiaryKeys`, `shellState` and `writeMemberships`, by `ShellModeTest`. `DiaryKeys` is also read by `TokenSealing.kt`, `DiaryTargetStoreTest` and `SealedTokensTest`, from the same package.
- Not one of them names the file, so none of them changes.

**Who reaches `GithubRepositoryImpl`.**
- It is built at `LessonsContainer.kt:503` alone, with named arguments `context`, `clientId` and `scope`.
- `PollOutcome`, `PollVerdict` and `pollUntilDecided` are reached by `DeviceFlowPollTest` alone (6 tests, package `github`).
- Everything else reaches the `GithubRepository` interface: `SettingsViewModel`, `DeveloperViewModel`, `DeveloperMode` and `DeveloperModeTest`'s fake.

**Coverage.**
- **The tags, the fingerprint and the roles:** no test calls `bundleTag`, `writeBundleTag`, `forgetBundleTag`, `forgetBundleTagsOf`, `forgetBundleTagsOutside`, `scheduleFingerprint`, `writeScheduleFingerprint`, `lastRole` or `rememberRole`. `RecordingTagStore` fakes the container's `BundleTagStore` instead.
- **`AppSettingsTest`'s note:** it says the settings' round trip has no seam, because the store needed a `Context`. That is untrue since #201 gave `LessonsPreferences` an internal constructor over a `DataStore`, which `MemoryStore` plays.

**The new names are free:** no declaration in `android/` is called `PreferenceKeys`, `SettingsCodec`, `BundleTags`, `putSettings`, `GithubDeviceFlow`, `GithubIssues`, `GithubPullRequests`, `GithubPermissions`, `SettingsRoundTripTest` or `BundleTagsAndRolesTest`.
- `BundleTagStore` (`repository/`) is a different name in a different package.
- `LessonsContainer`'s `private val bundleTags: BundleTagStore` is a property in another file and imports nothing from `datastore`.

**The qualification's inventory.** There are 130 references on 129 code lines of the class:
- 56 in the write half and 56 in `toSettings`;
- 18 on 17 other lines: 217, 222, 224, 252, 258, 264, 440, 444, 464, 478, 483 (two), 489, 494, 508, 518, 521 and 550.
- One mention in a comment, `KEY_TOKEN` at 639, names no companion member and is not touched.

**Line lengths after the qualifier.**
- Only line 483 (`rememberRole`) passes 120, at 131.
- The longest of the rest is 118, and `toSettings`' lines lose four columns when they leave the class.
- detekt's limit is 120: the baseline forgives a 122-character line, and nine lines of `:core:data`'s main source between 116 and 120 characters pass with no entry.

**The write half** (lines 351–417) is 59 code lines and 8 comment lines; `updateSettings` is 64 code lines. `AppSettings` has 44 fields and `AlertPreferences` 13: 43 + 13 = 56, the keys each half names.

**Shared state in `GithubRepositoryImpl.kt`.**

| Name | Lines that name it |
| --- | --- |
| `synchronized(lock)` | 2 |
| `preferences.clear()` | 4 |
| `HTTP_UNAUTHORISED` | 4 (one declaration, three uses) |
| `HTTP_CREATED` | 4 (one declaration, three uses) |
| `TAG` | 6 (one declaration, five uses) |
| `lazy {` | 1 in the file, plus `GithubApi.kt:70` in the package |
| `= GithubPreferences(` | 1 in the package |

**The environment.**
- `C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot` exists.
- `F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk` exists.
- `android/local.properties` does not exist; its existence was tested, nothing read.
- `server/.venv/Scripts/pytest.exe` exists in the worktree.
- `python` is 3.13, and `git` 2.46.

**What reads the documents this pull request edits.**
- `docs/build.md` is read by `server/tests/test_env_example.py`.
- Every `docs/**/*.md` (this plan included), the `.claude/` Markdown, `README.md` and `CLAUDE.md` are read by `test_schema_version.py`.
- A change to any of them runs CI's server job (`ci.yml`'s path filter), as a change under `android/` runs the Android job.

---

### Task 0: Commit this plan, and write the two tools (outside the repository)

**Files:**
- Commit: `docs/specs/2026-10-11-android-decomposition-a-plan.md`.
- Create, outside the repository: `C:/Users/lumen/AppData/Local/Temp/decompose-a/moved.py` and `.../count.sh`.

**What the tools produce:**
- `python C:/Users/lumen/AppData/Local/Temp/decompose-a/moved.py <rev> <old path> <path now>... [--sub REGEX=REPLACEMENT]...` is run from the worktree's root in Tasks 4–7 and 9.
- `bash C:/Users/lumen/AppData/Local/Temp/decompose-a/count.sh` gives each module's reported tests and the total, in Tasks 2, 3 and 11.

- [ ] **Step 1: Check where you are.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate && git status --short && git rev-parse --abbrev-ref HEAD && git log --oneline -1
  ```
  Expected:
  - the only line of `git status` is `?? docs/specs/2026-10-11-android-decomposition-a-plan.md`;
  - the branch is `android/decomposition-a`;
  - the last commit is `6354c3d Merge pull request #422 from lumenpearson/server/diary-routes`.

- [ ] **Step 2: Write the move checker.**
  ```bash
  mkdir -p /c/Users/lumen/AppData/Local/Temp/decompose-a
  cat > /c/Users/lumen/AppData/Local/Temp/decompose-a/moved.py <<'EOF'
  """Did a split move lines, or change them?

  usage (from the worktree's root):
    python moved.py <rev> <old path> <path now>... [--sub REGEX=REPLACEMENT]...

  Reads <old path> as it was at <rev>, and every <path now> as it is on disk
  (name the old path again if it still exists), and compares the two
  multisets of lines, each stripped of its indentation, blank lines dropped.
  Each --sub is a regular expression applied to every stripped line on BOTH
  sides before they are compared: it undoes a rewrite the task names (a
  qualifier, a receiver, an `override` a collaborator does not carry), so
  that only what the task did not name is left. The first "=" separates the
  pattern from its replacement. Prints each line only one side has, "-" for
  the old side and "+" for the new, with how many more times that side has
  it, and exits 0 either way: the task lists what is expected.
  """
  import collections
  import re
  import subprocess
  import sys
  from pathlib import Path

  sys.stdout.reconfigure(encoding="utf-8")

  args = sys.argv[1:]
  subs = []
  while "--sub" in args:
      at = args.index("--sub")
      pattern, _, replacement = args[at + 1].partition("=")
      subs.append((re.compile(pattern), replacement))
      del args[at : at + 2]
  rev, old, *now = args


  def lines(text):
      found = []
      for line in text.splitlines():
          line = line.strip()
          if not line:
              continue
          for pattern, replacement in subs:
              line = pattern.sub(replacement, line)
          found.append(line)
      return found


  shown = subprocess.run(
      ["git", "show", f"{rev}:{old}"], capture_output=True, check=True, encoding="utf-8"
  ).stdout
  before = collections.Counter(lines(shown))
  after = collections.Counter()
  for path in now:
      after.update(lines(Path(path).read_text(encoding="utf-8")))
  for line, count in sorted((before - after).items()):
      print(f"- {count}x {line}")
  for line, count in sorted((after - before).items()):
      print(f"+ {count}x {line}")
  EOF
  ```

- [ ] **Step 3: Write the counter.**
  ```bash
  cat > /c/Users/lumen/AppData/Local/Temp/decompose-a/count.sh <<'EOF'
  #!/usr/bin/env bash
  # The JUnit reports of the last run, per module and in all. The Android
  # modules are read from their debug variant: `test` runs the release variant
  # too, and the project's counts are of tests, not of runs.
  cd /c/Users/lumen/StudioProjects/lessons-live-gate/android || exit 1
  total=0
  for m in core/model core/data core/designsystem widget app; do
    d=$m/build/test-results/testDebugUnitTest
    [ -d "$d" ] || d=$m/build/test-results/test
    if [ ! -d "$d" ]; then echo "$m: no reports"; continue; fi
    set -- $(cat "$d"/*.xml | grep -o '<testsuite [^>]*' | awk '
      { for (i = 1; i <= NF; i++) if ($i ~ /^(tests|failures|errors|skipped)=/) {
          split($i, kv, "\""); s[substr($i, 1, index($i, "=") - 1)] += kv[2] } }
      END { printf "%d %d %d %d", s["tests"], s["failures"], s["errors"], s["skipped"] }')
    echo "$m: tests=$1 failures=$2 errors=$3 skipped=$4"
    total=$((total + $1))
  done
  echo "total: $total"
  EOF
  ```

- [ ] **Step 4: Self-test the checker.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate && python /c/Users/lumen/AppData/Local/Temp/decompose-a/moved.py HEAD android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore/LessonsPreferences.kt android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore/LessonsPreferences.kt; echo "exit $?"
  ```
  Expected: `exit 0`, with nothing printed before it.

- [ ] **Step 5: The plan passes the test that reads every document.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate/server && .venv/Scripts/pytest.exe -q -p no:xdist tests/test_schema_version.py
  ```
  Expected: all passed, none failed.

- [ ] **Step 6: Commit.** Stage `docs/specs/2026-10-11-android-decomposition-a-plan.md` alone.
  ```
  Plan the first Android split: LessonsPreferences, GithubRepositoryImpl and the workers cap

  Pull request A of sub-project 4, in the design's order (decision 8): twelve
  tasks, one commit each. Two characterization tests come first, through the
  store's internal constructor, so that they run green before anything moves:
  the settings' round trip the design asks for, and the bundle tags, the
  schedule's fingerprint and the remembered roles, which no test reached.
  Then four moves out of LessonsPreferences, its baseline, one move of
  GithubRepositoryImpl into four classes, its baseline, and the gates.

  Drafted read-only against 6354c3d; every count in it is a grep's, and the
  runs are the truth.
  ```

---

### Task 1: Write down the two-worker cap

**Files:** Modify `docs/build.md`. In «Locally», add a subsection after the paragraph that ends «which is what CI uses.» (line 719), before «### The suite on PostgreSQL».

- [ ] **Step 1: Read `docs/build.md` lines 696–752**, «Locally» and the subsection after it.
- [ ] **Step 2: Insert, after line 719 and a blank line:**

  ````markdown
  ### Two workers, on the maintainer's machine

  The maintainer's Windows machine has faulty memory and has blue-screened under builds and
  test runs since 26 September 2026 — not because memory runs out, but because more pages in
  use meet the bad bit sooner. So there every Gradle run carries one flag, and runs alone:

  ```bash
  cd android
  ./gradlew --max-workers=2 test
  ./gradlew --max-workers=2 assembleRelease
  ```

  - **What it does:** at most two tasks run at once, which is fewer simultaneous peaks.
  - **What it does not do:** cap any memory. The Gradle daemon's 4 GB, the Kotlin daemon,
    Robolectric's test JVMs and R8 take what they take with it or without it, which is why a
    Gradle run there is also the only heavy job on the machine — never beside a second Gradle
    or the full server suite.
  - **Why on the command line.** `android/gradle.properties` is CI's too, and its four-core
    runners are healthy: one machine's fault would slow every run. `~/.gradle/gradle.properties`
    holds the signing passwords (above), so no agent opens it, not even to add this line. And
    the build console ([its design](specs/2026-10-05-build-console-design.md), decision 2) is
    to pass this one spelling to every Gradle run it starts: its check that its gates are CI's
    strips exactly this flag, and another spelling, such as `-Dorg.gradle.workers.max=2`,
    would read as a difference from CI.
  ````

- [ ] **Step 3: The tests that read the document.** Run them only while no Gradle job is running.
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate/server && .venv/Scripts/pytest.exe -q -p no:xdist tests/test_env_example.py tests/test_ci_paths.py tests/test_schema_version.py
  ```
  Expected: all passed, none failed.

- [ ] **Step 4: Commit** `docs/build.md`.
  ```
  Write down why every Gradle run on the maintainer's machine carries --max-workers=2

  That machine has faulty memory and has blue-screened under builds and test
  runs since 26 September. Fewer tasks at once is fewer simultaneous peaks; it
  caps no memory, so a Gradle run there is also the only heavy job.

  The flag goes on the command line, as the owner chose on 5 October (#306,
  question 1): android/gradle.properties is CI's too, and its healthy runners
  would pay for one machine's fault; ~/.gradle/gradle.properties holds the
  signing passwords, and no agent opens it. The build console's design already
  strips exactly this spelling when it holds its gates to CI's.

  Not covered: what the cap saves. Nobody has measured a build with and
  without it on that machine.
  ```

---

### Task 2: Hold the settings' round trip before its two halves move

**Files:**
- Create `android/core/data/src/test/kotlin/com/lumenpearson/lessons/core/data/datastore/SettingsRoundTripTest.kt`.
- Modify `android/core/data/src/test/kotlin/com/lumenpearson/lessons/core/data/repository/AppSettingsTest.kt`, the KDoc at lines 50–61.

- [ ] **Step 1: Write the test.**

  ```kotlin
  package com.lumenpearson.lessons.core.data.datastore

  import com.lumenpearson.lessons.core.data.repository.AppSettings
  import com.lumenpearson.lessons.core.model.AlertPreferences
  import com.lumenpearson.lessons.core.model.AppFont
  import com.lumenpearson.lessons.core.model.AppLanguage
  import com.lumenpearson.lessons.core.model.DayFilter
  import com.lumenpearson.lessons.core.model.DayMode
  import com.lumenpearson.lessons.core.model.DayOrder
  import com.lumenpearson.lessons.core.model.HapticStrength
  import com.lumenpearson.lessons.core.model.HomeTab
  import com.lumenpearson.lessons.core.model.LessonAlertDetail
  import com.lumenpearson.lessons.core.model.RibbonFlow
  import com.lumenpearson.lessons.core.model.ThemeMode
  import com.lumenpearson.lessons.core.model.TodayLayout
  import com.lumenpearson.lessons.core.model.WeekStart
  import java.lang.reflect.Modifier
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.runBlocking
  import org.junit.Assert.assertEquals
  import org.junit.Test

  /**
   * Every setting goes into the preferences file under its key and comes back
   * out under the same one.
   *
   * The write half and the read half each name fifty-six keys, and a setting
   * one of them forgets fails nothing on its own: it is written and never read
   * back, or read and always its default. So the value written here differs
   * from the defaults in every field, and the last test holds that by
   * reflection — a field added to [AppSettings] or [AlertPreferences] without a
   * key then fails here instead of quietly resetting on every launch.
   *
   * Through the store's internal constructor rather than through the two halves
   * themselves, so that it ran green on the code before they were moved side by
   * side, and proves the move.
   */
  class SettingsRoundTripTest {

      private val everyFieldChanged = AppSettings(
          baseUrl = "https://lessons.example",
          themeMode = ThemeMode.DARK,
          dynamicColor = false,
          pitchBlack = true,
          hapticsEnabled = false,
          hapticStrength = HapticStrength.CLICK,
          appFont = AppFont.SYSTEM,
          language = AppLanguage.ENGLISH,
          textScale = 1.15f,
          animations = false,
          motionSpeed = 1.5f,
          swipeTabs = false,
          defaultTab = HomeTab.WEEK,
          tabOrder = listOf(HomeTab.HOMEWORK, HomeTab.TODAY, HomeTab.WEEK),
          motionBlur = true,
          motionBlurScale = 2f,
          edgeBlur = false,
          showTeacher = false,
          widgetShowProgress = false,
          todayShowHero = false,
          todayLayout = TodayLayout.HOMEWORK_FIRST,
          todayWholeDay = true,
          todayHomeworkPreview = 5,
          todayShowEvents = false,
          weekStart = WeekStart.TODAY,
          weekShowWeekends = false,
          weekShowLoad = false,
          weekShowEvents = false,
          weekShowHomework = false,
          calendarFilters = setOf(DayFilter.HAS_HOMEWORK, DayFilter.HAS_EVENTS),
          calendarOrder = DayOrder.DATE_DESC,
          dayRibbonFlow = RibbonFlow.UPWARD,
          dayMode = DayMode.LIST,
          dayRibbonSnap = false,
          dayRibbonDepth = false,
          syncIntervalMinutes = 30,
          debugMode = true,
          onboardingDone = true,
          alerts = AlertPreferences(
              lessonSoon = true,
              lessonLeadMinutes = 15,
              lessonDetail = LessonAlertDetail.SUBJECT,
              morningSummary = true,
              morningAtMinutes = 6 * 60 + 30,
              morningWeekdays = setOf(1, 3, 5),
              homeworkReminder = true,
              homeworkAtMinutes = 19 * 60,
              scheduleChanges = true,
              quietHours = true,
              quietFromMinutes = 23 * 60,
              quietToMinutes = 6 * 60,
              skipHolidays = false,
          ),
          rippleEffects = false,
          themeReveal = false,
          autoCheckUpdates = false,
          includePrerelease = true,
          notifyNewUpdates = false,
      )

      @Test
      fun `a file that holds nothing reads as the defaults`() = runBlocking {
          assertEquals(AppSettings(), LessonsPreferences(MemoryStore(), testVault()).currentSettings())
      }

      @Test
      fun `every setting comes back as it was written`() = runBlocking {
          val store = MemoryStore()
          LessonsPreferences(store, testVault()).updateSettings { everyFieldChanged }

          // Read by a second instance over what the first wrote, so nothing can
          // have come back from memory rather than from the file.
          val reader = LessonsPreferences(MemoryStore(store.value), testVault())

          assertEquals(everyFieldChanged, reader.currentSettings())
          assertEquals(everyFieldChanged, reader.settings.first())
      }

      @Test
      fun `the settings written differ from the defaults in every field`() {
          assertEquals(emptyList<String>(), unchanged(everyFieldChanged, AppSettings()))
          assertEquals(emptyList<String>(), unchanged(everyFieldChanged.alerts, AlertPreferences()))
      }

      /**
       * The fields [value] holds as [default] does. By reflection, so that a field
       * added later is asked about without anybody remembering to name it here.
       */
      private fun unchanged(value: Any, default: Any): List<String> =
          value.javaClass.declaredFields
              .filterNot { Modifier.isStatic(it.modifiers) }
              .onEach { it.setAccessible(true) }
              .filter { it.get(value) == it.get(default) }
              .map { it.name }
  }
  ```

  Each value was chosen to survive the read's repairs:
  - `textScale` 1.15 is one of `TEXT_SCALE_OPTIONS`;
  - `motionSpeed` 1.5 and `motionBlurScale` 2 are inside their ranges;
  - a `todayHomeworkPreview` of 5 is an offered length;
  - 30 minutes is above `MIN_SYNC_INTERVAL_MINUTES`;
  - the base URL has no space to trim.

  Every enum value differs from the one `fromName` falls back to, so a missing key reads back as something else.

- [ ] **Step 2: Correct `AppSettingsTest`'s note.** In the KDoc of `the default tab order is every tab, as declared`, replace the paragraph that starts «The mapping that writes this field» with:
  ```
       * The round trip through the preferences file — this field among every
       * other — is `SettingsRoundTripTest`'s, over the store's in-memory double;
       * what `HomeTab.order` repairs on the way back is checked in `:core:model`.
  ```
  The opening paragraph, «The bar somebody who has never opened the setting sees…», stays as it is.

- [ ] **Step 3: Run the class.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 :core:data:testDebugUnitTest --tests '*SettingsRoundTripTest*'
  ```
  Expected: `BUILD SUCCESSFUL`. `core/data/build/test-results/testDebugUnitTest/TEST-com.lumenpearson.lessons.core.data.datastore.SettingsRoundTripTest.xml` says `tests="3"`, `failures="0"`, `errors="0"`.

  If `a file that holds nothing reads as the defaults` fails, a default in `toSettings` disagrees with `AppSettings()`. That is a defect: stop, and file it before going on.

- [ ] **Step 4: See it fail.**
  - Temporarily delete the line `prefs[KEY_WEEK_LOAD] = updated.weekShowLoad` in `LessonsPreferences.updateSettings`.
  - Run Step 3's command. Expected: `every setting comes back as it was written` fails, with `weekShowLoad=false` against `weekShowLoad=true`; the other two pass.
  - Restore the line with `git -C /c/Users/lumen/StudioProjects/lessons-live-gate checkout -- android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore/LessonsPreferences.kt`.

- [ ] **Step 5: The module.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 :core:data:test
  ```
  Expected: `BUILD SUCCESSFUL`. Then `bash C:/Users/lumen/AppData/Local/Temp/decompose-a/count.sh` should print `core/data: tests=618 failures=0 errors=0 skipped=0`. The other modules print `no reports` or an earlier run's numbers, and are read in Task 11.

- [ ] **Step 6: Commit** the two test files.
  ```
  Hold every setting's round trip through the preferences file before its two halves move

  The settings are written under fifty-six keys in updateSettings and read
  under the same fifty-six in toSettings, and a setting one half forgets fails
  nothing: it is written and never read back, or read and always its default.
  SettingsRoundTripTest writes a value that differs from the defaults in every
  field, reads it back through a second store over the same file, and holds by
  reflection that the value really does differ everywhere, so a field added to
  AppSettings or AlertPreferences without a key fails here. A file holding
  nothing reads as AppSettings(). Seen failing with one key's write removed.

  It goes through the store's internal constructor, which has existed since
  #201, so AppSettingsTest's note that there was no seam for this is corrected.

  Not covered: a file written by an older build that lacks some keys. Only an
  empty file's defaults are held.
  ```

---

### Task 3: Hold the bundle tags, the schedule's fingerprint and the remembered roles before they move

**Files:** Create `android/core/data/src/test/kotlin/com/lumenpearson/lessons/core/data/datastore/BundleTagsAndRolesTest.kt`.

- [ ] **Step 1: Write the test.**

  ```kotlin
  package com.lumenpearson.lessons.core.data.datastore

  import com.lumenpearson.lessons.core.data.repository.ClassRole
  import com.lumenpearson.lessons.core.data.repository.Session
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.runBlocking
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertNull
  import org.junit.Test

  /**
   * What the store keeps beside the settings for the sync's sake rather than the
   * reader's: an `ETag` per window, the shape of the last schedule, and the role
   * last given in each class.
   *
   * Most of it is found by key prefix when it is swept, which is where it goes
   * wrong without a sound: a class's prefix without its trailing separator takes
   * class 12's tags with class 1's, and the cost is a full download nobody sees.
   * docs/history.md records that sweep as written and never run, from when the
   * store needed a `Context`; the in-memory store reaches it now, and these hold
   * it still while it moves into `BundleTags`.
   */
  class BundleTagsAndRolesTest {

      private val preferences = LessonsPreferences(MemoryStore(), testVault())

      @Test
      fun `a tag is read back under its own signature and no other`() = runBlocking {
          preferences.writeBundleTag("1|2026", "etag-a")

          assertEquals("etag-a", preferences.bundleTag("1|2026"))
          assertNull(preferences.bundleTag("1|2025"))

          preferences.forgetBundleTag("1|2026")
          assertNull(preferences.bundleTag("1|2026"))
      }

      @Test
      fun `forgetting one class's tags spares the classes whose ids begin with its digits`() = runBlocking {
          listOf("1|2025", "1|2026", "12|2026", "100|2026").forEach { preferences.writeBundleTag(it, "tag $it") }

          preferences.forgetBundleTagsOf(1)

          assertNull(preferences.bundleTag("1|2025"))
          assertNull(preferences.bundleTag("1|2026"))
          assertEquals("tag 12|2026", preferences.bundleTag("12|2026"))
          assertEquals("tag 100|2026", preferences.bundleTag("100|2026"))
      }

      @Test
      fun `forgetting the tags outside a set spares that set, and nothing else is a tag`() = runBlocking {
          listOf("1|2026", "12|2026", "100|2026").forEach { preferences.writeBundleTag(it, "tag $it") }
          preferences.writeScheduleFingerprint("shape")
          preferences.rememberRole(1, ClassRole.ADMIN)

          preferences.forgetBundleTagsOutside(listOf(12L))

          assertNull(preferences.bundleTag("1|2026"))
          assertEquals("tag 12|2026", preferences.bundleTag("12|2026"))
          assertNull(preferences.bundleTag("100|2026"))

          preferences.forgetBundleTagsOutside(emptyList())

          assertNull(preferences.bundleTag("12|2026"))
          assertEquals("shape", preferences.scheduleFingerprint())
          assertEquals(ClassRole.ADMIN, preferences.lastRole(1).first())
      }

      @Test
      fun `the schedule's shape is the one written last, and a switch of class forgets it`() = runBlocking {
          preferences.addSession(session(1))
          preferences.addSession(session(2))
          assertNull(preferences.scheduleFingerprint())

          preferences.writeScheduleFingerprint("first")
          preferences.writeScheduleFingerprint("second")
          assertEquals("second", preferences.scheduleFingerprint())

          preferences.selectSession(1)
          assertNull(preferences.scheduleFingerprint())
      }

      @Test
      fun `a role is kept per class, forgotten with null, and gone after a sign-out`() = runBlocking {
          preferences.writeBundleTag("9|2026", "kept")
          preferences.rememberRole(7, ClassRole.ADMIN)
          preferences.rememberRole(9, ClassRole.EDITOR)

          preferences.rememberRole(7, null)

          assertNull(preferences.lastRole(7).first())
          assertEquals(ClassRole.EDITOR, preferences.lastRole(9).first())

          preferences.clearSession()

          assertNull(preferences.lastRole(9).first())
          assertEquals("kept", preferences.bundleTag("9|2026"))
      }

      private fun session(classId: Long) =
          Session(classId = classId, className = "Class $classId", school = null, token = "token-$classId")
  }
  ```

  What each test reaches:
  - **Tags.** The seven methods that become `BundleTags` in Task 7.
  - **Roles.** Both branches of `rememberRole`, which Task 5 rewrites, and `lastRole`.
  - **Fingerprint.** `activate`'s removal of it, which Task 5 qualifies.
  - **Sweeps.** `clearSession`'s sweep of the roles by prefix, which Task 7 moves to a top-level function.

- [ ] **Step 2: Run the class.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 :core:data:testDebugUnitTest --tests '*BundleTagsAndRolesTest*'
  ```
  Expected: `BUILD SUCCESSFUL`, and its report says `tests="5"`, `failures="0"`.

- [ ] **Step 3: See it fail.**
  - Temporarily change `fun bundleTagPrefix(classId: Long) = "bundle_etag|$classId|"` in the companion to `"bundle_etag|$classId"`.
  - Run Step 2's command. Expected: `forgetting one class's tags spares the classes whose ids begin with its digits` fails at `tag 12|2026`; the other four pass.
  - Restore the file with `git -C /c/Users/lumen/StudioProjects/lessons-live-gate checkout -- android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore/LessonsPreferences.kt`.

- [ ] **Step 4: The module.** Run Task 2's Step 5 command, then `count.sh`. Expected: `BUILD SUCCESSFUL`, and `core/data: tests=623 failures=0 errors=0 skipped=0`.

- [ ] **Step 5: Commit** the test file.
  ```
  Hold the bundle tags, the schedule's fingerprint and the remembered roles before they move

  Nothing ran any of it. docs/history.md records the tag sweep's prefix
  arithmetic as written and never run, from when LessonsPreferences needed a
  Context; its internal constructor over a DataStore reaches it now.
  BundleTagsAndRolesTest holds what the split is about to move or rewrite: a
  tag read back under its own signature only; forgetting class 1's tags
  sparing class 12's and class 100's, which is the whole point of the trailing
  separator; a sweep outside a set sparing the set, the fingerprint and the
  roles; the fingerprint forgotten when the class on screen changes; and a role
  kept per class, forgotten with null, and gone after a sign-out while the tags
  stay. Seen failing with the trailing separator removed.

  Not covered: removeSession's own handling of the role and the fingerprint,
  beyond what it shares with selectSession and clearSession, and the
  container's runCatching around every tag call.
  ```

---

### Task 4: Give the shell's and the diary's keys files of their own

**Files:**
- Create `android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore/ShellKeys.kt` and `.../datastore/DiaryKeys.kt`.
- Modify `.../datastore/LessonsPreferences.kt`.

**Moves, verbatim with their KDoc (lines at `6354c3d`):**
- **To `ShellKeys.kt`:** `internal object ShellKeys` (771–780) and `internal fun Preferences.shellState(vault: TokenVault): ShellState` (782–792).
- **To `DiaryKeys.kt`:**
  - `internal object DiaryKeys` (794–810);
  - `internal fun Preferences.toDiaryTarget(): DiaryTarget?` (812–828);
  - `internal fun MutablePreferences.putDiarySession(value: DiarySession)` (830–839);
  - `internal fun MutablePreferences.dropDiaryToken()` (841–855);
  - `internal fun MutablePreferences.dropDiary()` (857–863);
  - `internal fun Preferences.toDiarySession(): DiarySession?` (865–883).

**Widens:** nothing, because all eight declarations were `internal` already. This is the move section 5 of the programme believed had happened.

- [ ] **Step 1: Create `ShellKeys.kt`.**
  ```kotlin
  package com.lumenpearson.lessons.core.data.datastore

  import androidx.datastore.preferences.core.Preferences
  import androidx.datastore.preferences.core.booleanPreferencesKey
  import com.lumenpearson.lessons.core.data.repository.ShellModeSource
  import com.lumenpearson.lessons.core.data.repository.ShellState
  import com.lumenpearson.lessons.core.data.repository.shellModeOf

  ```
  Then lines 771–792 of `LessonsPreferences.kt`, verbatim. `ShellModeSource` is imported for the KDoc link `[ShellModeSource]`, as the IDE would import it on a move.

- [ ] **Step 2: Create `DiaryKeys.kt`.**
  ```kotlin
  package com.lumenpearson.lessons.core.data.datastore

  import androidx.datastore.preferences.core.MutablePreferences
  import androidx.datastore.preferences.core.Preferences
  import androidx.datastore.preferences.core.longPreferencesKey
  import androidx.datastore.preferences.core.stringPreferencesKey
  import com.lumenpearson.lessons.core.data.repository.DiarySession
  import com.lumenpearson.lessons.core.data.repository.DiaryTarget
  import com.lumenpearson.lessons.core.data.repository.DiaryTargetCodec

  ```
  Then lines 794–883, verbatim.

- [ ] **Step 3: Cut them from `LessonsPreferences.kt`.**
  - Delete everything after the class's closing brace at line 769, the blank line 770 included. The file then ends at that brace and a newline.
  - Delete three imports, which only the moved code used: `androidx.datastore.preferences.core.longPreferencesKey`, `com.lumenpearson.lessons.core.data.repository.DiaryTargetCodec` and `com.lumenpearson.lessons.core.data.repository.shellModeOf`.

- [ ] **Step 4: Check it was a move.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate && D=android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore && python /c/Users/lumen/AppData/Local/Temp/decompose-a/moved.py HEAD $D/LessonsPreferences.kt $D/LessonsPreferences.kt $D/ShellKeys.kt $D/DiaryKeys.kt
  ```
  Expected:
  - no `-` line;
  - exactly these `+` lines:
    - `+ 2x package com.lumenpearson.lessons.core.data.datastore`;
    - `+ 2x import androidx.datastore.preferences.core.Preferences`;
    - `+ 1x` each of the imports of `MutablePreferences`, `booleanPreferencesKey`, `stringPreferencesKey`, `ShellModeSource`, `ShellState`, `DiarySession` and `DiaryTarget`.

  These are the imports that both the class and a new file now need. Anything else is a changed line: find it and undo it.

- [ ] **Step 5: The module.** Run Task 2's Step 5 command. Expected: `BUILD SUCCESSFUL`, and `count.sh` prints `core/data: tests=623 failures=0`. `ShellModeTest`, `DiaryTargetStoreTest` and `SealedTokensTest` compile unchanged, because they import by package and name.

- [ ] **Step 6: Commit** the three files.
  ```
  Give the shell's and the diary's keys files of their own, beside the code that reads them

  ShellKeys and shellState go to ShellKeys.kt, and DiaryKeys with its five
  functions to DiaryKeys.kt, verbatim, out of the bottom of
  LessonsPreferences.kt: the move the programme's design thought had already
  happened. Everything here was internal, so nothing is widened, and every
  caller imports by package and name and changes nothing.

  Part of pull request A of the Android decomposition; detekt's baseline is
  rewritten in a commit of its own after the last move out of this file.
  ```

---

### Task 5: Move the settings', the roles' and the tags' keys into `PreferenceKeys`

**Files:**
- Create `android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore/PreferenceKeys.kt`.
- Modify `.../datastore/LessonsPreferences.kt`.

**Moves:** the body of `private companion object` (lines 675–767) into `internal object PreferenceKeys`, verbatim and dedented by four. Its two leading comments, about `MembershipKeys` and `DiaryKeys`, go with it. The 62 members are the 57 `KEY_*` keys plus `bundleTagKey`, `roleKey`, `ROLE_PREFIX`, `bundleTagPrefix` and `BUNDLE_TAG_PREFIX`.

**Widens:** the 62 members, from private to the class to `internal`. Tasks 6 and 7 read them from other files.

**Rewrites:**
- Every code reference in the class becomes `PreferenceKeys.<name>`: 130 references on 129 lines, listed under «What was verified». The comment at line 639 that mentions `KEY_TOKEN` is not touched.
- One line is rewritten so that it fits: `rememberRole`'s, which the qualifier takes to 131 characters.

- [ ] **Step 1: Write the script that does the 130 edits** (not committed).
  ```bash
  cat > /c/Users/lumen/AppData/Local/Temp/decompose-a/qualify.py <<'EOF'
  """Task 5: LessonsPreferences' companion becomes PreferenceKeys, and every
  code reference to one of its members is qualified. Run from the worktree's
  root. Prints how many names and references it found."""
  import re
  import sys
  from pathlib import Path

  sys.stdout.reconfigure(encoding="utf-8")
  D = Path("android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore")
  source = D / "LessonsPreferences.kt"
  lines = source.read_text(encoding="utf-8").splitlines()

  start = lines.index("    private companion object {")
  end = lines.index("    }", start)  # the companion's closing brace, the first at four spaces
  body = lines[start + 1 : end]
  names = sorted(
      {m.group(1) for line in body if (m := re.match(r"\s*(?:const\s+)?(?:val|fun)\s+(\w+)", line))},
      key=len,
      reverse=True,
  )
  member = re.compile(r"(?<![\w.])(" + "|".join(names) + r")\b")
  comment = re.compile(r"^\s*(//|\*|/\*)")

  count = 0
  for i in range(start):
      if not comment.match(lines[i]):
          lines[i], n = member.subn(r"PreferenceKeys.\1", lines[i])
          count += n

  assert lines[start - 1] == "", "expected a blank line above the companion"
  del lines[start - 1 : end + 1]

  builders = [
      "booleanPreferencesKey",
      "floatPreferencesKey",
      "intPreferencesKey",
      "stringPreferencesKey",
      "stringSetPreferencesKey",
  ]
  imports = [f"import androidx.datastore.preferences.core.{b}" for b in builders]
  for line in imports:
      lines.remove(line)
  source.write_text("\n".join(lines) + "\n", encoding="utf-8")

  keys = [line[4:] if line.startswith("    ") else line for line in body]
  head = [
      "package com.lumenpearson.lessons.core.data.datastore",
      "",
      *imports,
      "",
      "/**",
      " * The keys of the settings, the remembered roles and the bundle tags.",
      " *",
      " * An object of its own, internal like [MembershipKeys], because the settings",
      " * codec and the bundle tags read them from files of their own, and Kotlin's",
      " * `private` ends at the file.",
      " */",
      "internal object PreferenceKeys {",
  ]
  (D / "PreferenceKeys.kt").write_text("\n".join(head + keys + ["}", ""]), encoding="utf-8")
  print(f"{len(names)} names, {count} references qualified")
  EOF
  cd /c/Users/lumen/StudioProjects/lessons-live-gate && python /c/Users/lumen/AppData/Local/Temp/decompose-a/qualify.py
  ```
  Expected: `62 names, 130 references qualified`. On any other number, `git checkout -- android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore/LessonsPreferences.kt`, delete `PreferenceKeys.kt`, and find out why before going on.

- [ ] **Step 2: Fit `rememberRole` under 120 characters.** Its body becomes:
  ```kotlin
      /** @see lastRole; `null` forgets it. */
      suspend fun rememberRole(classId: Long, role: ClassRole?) {
          write { prefs ->
              val key = PreferenceKeys.roleKey(classId)
              if (role == null) prefs.remove(key) else prefs[key] = role.name
          }
      }
  ```

- [ ] **Step 3: Read both files.**
  - `PreferenceKeys.kt` holds the 62 members at four spaces, with their KDoc and the two leading comments, and `roleKey` still says `/** @see LessonsPreferences.lastRole */`.
  - `LessonsPreferences.kt` holds no `companion`.
  - Check that no line runs past 120 characters:
    ```bash
    cd /c/Users/lumen/StudioProjects/lessons-live-gate && awk 'length($0) > 120 {print FILENAME": "FNR": "length($0)}' android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore/LessonsPreferences.kt android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore/PreferenceKeys.kt
    ```
    Expected: no output.

- [ ] **Step 4: Check it was a move.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate && D=android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore && python /c/Users/lumen/AppData/Local/Temp/decompose-a/moved.py HEAD $D/LessonsPreferences.kt $D/LessonsPreferences.kt $D/PreferenceKeys.kt --sub 'PreferenceKeys\.='
  ```
  Expected `-` lines, exactly:
  - `- 1x private companion object {`;
  - `- 1x if (role == null) prefs.remove(roleKey(classId)) else prefs[roleKey(classId)] = role.name`.

  Expected `+` lines, exactly:
  - `package …`;
  - the object's KDoc lines (`/**`, its four text lines and the `*` between them, `*/`);
  - `internal object PreferenceKeys {`;
  - `val key = roleKey(classId)`;
  - `if (role == null) prefs.remove(key) else prefs[key] = role.name`.

- [ ] **Step 5: The module.** Run Task 2's Step 5 command. Expected: `BUILD SUCCESSFUL`, with `core/data: tests=623 failures=0`. `SettingsRoundTripTest` and `BundleTagsAndRolesTest` are among them, unchanged.

- [ ] **Step 6: Commit** the two files.
  ```
  Move the settings', the roles' and the tags' keys out of LessonsPreferences into PreferenceKeys

  The class's private companion becomes internal object PreferenceKeys, like
  MembershipKeys, because the settings codec and the bundle tags are about to
  read the keys from files of their own and Kotlin's private ends at the file.
  The 62 members move verbatim and keep their names; the class's 130
  references to them, on 129 lines, become PreferenceKeys.<name> and nothing
  else changes on those lines.

  One line is rewritten rather than baselined: the qualifier took
  rememberRole's to 131 characters, past detekt's 120, so the role's key is
  named once in a local. Both branches are held by BundleTagsAndRolesTest.

  Not covered: detekt is not run in this commit. The baseline is rewritten
  after the last move out of this file, in a commit of its own.
  ```

---

### Task 6: Put the settings' two halves side by side in `SettingsCodec`

**Files:**
- Create `android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore/SettingsCodec.kt`.
- Modify `.../datastore/LessonsPreferences.kt`.

**Moves:**
- **The write half.** The 67 lines of `updateSettings`' write half, after `val updated = transform(prefs.toSettings())` (lines 351–417 at `6354c3d`), become the body of `internal fun MutablePreferences.putSettings(updated: AppSettings)`. They are dedented by eight, and each `prefs[` that starts a line becomes `this[`. The 8 comment lines go with them.
- **The read half.** `private fun Preferences.toSettings(): AppSettings` (570–672) becomes `internal` and top-level, with its KDoc, dedented by four.

**Widens:** `toSettings` and the write half, to `internal`.

**Stays:** `updateSettings` keeps its KDoc, its `write` and its read-modify-write, and calls the pair:
```kotlin
    /** Read-modify-write inside DataStore's transaction, so concurrent edits merge. */
    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        write { prefs ->
            val updated = transform(prefs.toSettings())
            prefs.putSettings(updated)
        }
    }
```
`requestCredentials` and `syncArming` go on calling `toSettings()`, which now resolves to the top-level function.

- [ ] **Step 1: Write and run the script** (not committed).
  ```bash
  cat > /c/Users/lumen/AppData/Local/Temp/decompose-a/codec.py <<'EOF'
  """Task 6: the settings' two halves move to SettingsCodec.kt. Run from the
  worktree's root."""
  import sys
  from pathlib import Path

  sys.stdout.reconfigure(encoding="utf-8")
  D = Path("android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore")
  source = D / "LessonsPreferences.kt"
  lines = source.read_text(encoding="utf-8").splitlines()

  update = lines.index("    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {")
  assert lines[update + 2].strip() == "val updated = transform(prefs.toSettings())"
  first = update + 3
  last = lines.index("        }", first)  # the write block's closing brace
  write_half = [line[8:] for line in lines[first:last]]
  write_half = ["    this[" + line[len("    prefs["):] if line.startswith("    prefs[") else line
                for line in write_half]
  lines[first:last] = ["            prefs.putSettings(updated)"]

  read = lines.index("    private fun Preferences.toSettings(): AppSettings = AppSettings(")
  kdoc = max(i for i in range(read) if lines[i] == "    /**")
  close = lines.index("    )", read)
  read_half = [line[4:] if line.startswith("    ") else line for line in lines[kdoc : close + 1]]
  read_half = [line.replace("private fun Preferences.toSettings", "internal fun Preferences.toSettings", 1)
               for line in read_half]
  assert lines[kdoc - 1] == ""
  del lines[kdoc - 1 : close + 1]

  model = ["AlertPreferences", "AppFont", "DayFilter", "DayMode", "DayOrder", "HapticStrength",
           "HomeTab", "LessonAlertDetail", "RibbonFlow", "ThemeMode", "TodayLayout", "WeekStart"]
  for name in model:
      lines.remove(f"import com.lumenpearson.lessons.core.model.{name}")
  source.write_text("\n".join(lines) + "\n", encoding="utf-8")

  head = [
      "package com.lumenpearson.lessons.core.data.datastore",
      "",
      "import androidx.datastore.preferences.core.MutablePreferences",
      "import androidx.datastore.preferences.core.Preferences",
      "import com.lumenpearson.lessons.core.data.repository.AppSettings",
      *[f"import com.lumenpearson.lessons.core.model.{name}"
        for name in sorted(model + ["AppLanguage"])],
      "",
      "/**",
      " * Every field of [updated] under its key: the write half of [toSettings].",
      " *",
      " * The two are a pair, and a setting one of them forgets fails nothing — it is",
      " * written and never read back, or read and always its default — so",
      " * `SettingsRoundTripTest` writes a value that differs from the defaults in every",
      " * field and reads it back through both.",
      " */",
      "internal fun MutablePreferences.putSettings(updated: AppSettings) {",
  ]
  text = head + write_half + ["}", ""] + read_half + [""]
  (D / "SettingsCodec.kt").write_text("\n".join(text), encoding="utf-8")
  print(f"{len(write_half)} write lines, {len(read_half)} read lines")
  EOF
  cd /c/Users/lumen/StudioProjects/lessons-live-gate && python /c/Users/lumen/AppData/Local/Temp/decompose-a/codec.py
  ```
  Expected: `67 write lines, 103 read lines`, which is lines 570–672 with the KDoc. On any other count, restore `LessonsPreferences.kt` from git, delete `SettingsCodec.kt`, and find out why.

- [ ] **Step 2: Read `SettingsCodec.kt` and `updateSettings`.**
  - Every line of `putSettings` but the comments and the two continuation lines starts with `this[PreferenceKeys.`.
  - `toSettings` is `internal fun`, at the top level.
  - `LessonsPreferences.kt` still imports `AppLanguage` (`languageBlocking`) and no other `core.model` type.

- [ ] **Step 3: Check it was a move.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate && D=android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore && python /c/Users/lumen/AppData/Local/Temp/decompose-a/moved.py HEAD $D/LessonsPreferences.kt $D/LessonsPreferences.kt $D/SettingsCodec.kt --sub '^this\[=prefs['
  ```
  Expected `-`, exactly: `- 1x private fun Preferences.toSettings(): AppSettings = AppSettings(`.

  Expected `+`, exactly:
  - `package …`;
  - one more import each of `MutablePreferences`, `Preferences`, `AppSettings` and `AppLanguage`;
  - `putSettings`' KDoc lines (`/**`, its five text lines and the blank `*`, `*/`);
  - `internal fun MutablePreferences.putSettings(updated: AppSettings) {`;
  - one `}`;
  - `prefs.putSettings(updated)`;
  - `internal fun Preferences.toSettings(): AppSettings = AppSettings(`.

- [ ] **Step 4: The module.** Run Task 2's Step 5 command. Expected: `BUILD SUCCESSFUL`, with `core/data: tests=623 failures=0`; `SettingsRoundTripTest`'s three among them, unchanged.

- [ ] **Step 5: Commit** the two files.
  ```
  Put the settings' two halves side by side, in SettingsCodec

  toSettings moves out of LessonsPreferences verbatim, from a private member
  extension to an internal top-level one, and the write half of updateSettings
  becomes MutablePreferences.putSettings beside it, named after its sibling
  putDiarySession: its 56 key writes and their comments move unchanged but for
  the receiver, this where it was prefs. updateSettings keeps the transaction
  and the read-modify-write, and calls the pair.

  The pair is what keeps a setting from being written under one key and read
  under another, and SettingsRoundTripTest, which ran on the code before this
  commit, holds it unchanged after it. detekt's LongMethod on updateSettings
  is carried by putSettings (updateSettings -> putSettings), which the
  baseline commit after the next one records.
  ```

---

### Task 7: Give the bundle tags and the schedule's fingerprint a class of their own over the store

**Files:**
- Create `android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore/BundleTags.kt`.
- Modify `.../datastore/LessonsPreferences.kt`.

**Moves, verbatim with their KDoc, into `BundleTags`:**
- `scheduleFingerprint` (432–440);
- `writeScheduleFingerprint` (442–445);
- `bundleTag` (447–464);
- `writeBundleTag` (487–490);
- `forgetBundleTag` (492–495);
- `forgetBundleTagsOf` (497–510);
- `forgetBundleTagsOutside` (512–524).

In their bodies, `preferences.first()` becomes `store.preferences.first()` (twice) and `write {` becomes `store.write {` (five times). Nothing else changes.

**Widens:**
- `LessonsPreferences.preferences`: `private val` → `internal val`.
- `LessonsPreferences.write`: `private suspend fun` → `internal suspend fun`.
- `MutablePreferences.removeKeysStartingWith` (526–538): it leaves the class for the top level of `LessonsPreferences.kt`, after the class's closing brace, and goes from `private` to `internal`. Its KDoc goes with it, dedented by four.

Why: `BundleTags` reads and writes through the store (decision 6), and sweeps tags by prefix. `clearSession` still sweeps the roles with the same function.

**Stays:**
- `lastRole` and `rememberRole`, between the delegates as today.
- `activate`, `clearSession` and `removeSession`, which go on reaching the fingerprint's key through `PreferenceKeys`.

- [ ] **Step 1: Create `BundleTags.kt`.**
  ```kotlin
  package com.lumenpearson.lessons.core.data.datastore

  import kotlinx.coroutines.flow.first

  /**
   * What the sync keeps in the preferences file beside the settings: one `ETag`
   * per window it holds, and the shape of the last schedule it saw.
   *
   * Over the store rather than over the file, so that every write here goes
   * through [LessonsPreferences.write] and refreshes the credentials like every
   * other, and the store keeps its `dataStore` to itself. Its callers still ask
   * [LessonsPreferences], which hands each call on.
   */
  internal class BundleTags(private val store: LessonsPreferences) {

      // The seven methods, in today's order, verbatim with their KDoc but for
      // `store.` before `preferences.first()` and before `write {`.
  }
  ```
  The comment in the body stands in for the seven methods. Write the methods, not the comment.

- [ ] **Step 2: In `LessonsPreferences.kt`, put the delegates where the methods stood.**
  - Just above the first of them, after `languageBlocking`, add:
    ```kotlin
        /**
         * The tags and the fingerprint, which only the sync reads. Over this instance
         * rather than over the file, so that their writes go through [write] like every
         * other; declared after every other property, because it is handed `this`.
         */
        private val bundleTags = BundleTags(this)

    ```
  - Replace each moved method, in its own place, with its delegate:
    ```kotlin
        /** @see BundleTags.scheduleFingerprint */
        suspend fun scheduleFingerprint(): String? = bundleTags.scheduleFingerprint()

        /** @see BundleTags.scheduleFingerprint */
        suspend fun writeScheduleFingerprint(value: String) = bundleTags.writeScheduleFingerprint(value)

        /** @see BundleTags.bundleTag */
        suspend fun bundleTag(signature: String): String? = bundleTags.bundleTag(signature)
    ```
  - `lastRole` and `rememberRole` stay where they are, then:
    ```kotlin
        /** @see BundleTags.bundleTag */
        suspend fun writeBundleTag(signature: String, etag: String) = bundleTags.writeBundleTag(signature, etag)

        /** @see BundleTags.bundleTag */
        suspend fun forgetBundleTag(signature: String) = bundleTags.forgetBundleTag(signature)

        /** @see BundleTags.forgetBundleTagsOf */
        suspend fun forgetBundleTagsOf(classId: Long) = bundleTags.forgetBundleTagsOf(classId)

        /** @see BundleTags.forgetBundleTagsOutside */
        suspend fun forgetBundleTagsOutside(keep: Collection<Long>) = bundleTags.forgetBundleTagsOutside(keep)
    ```
  - Change `private val preferences: Flow<Preferences>` to `internal val …`, and `private suspend fun write(` to `internal suspend fun write(`.
  - Move `removeKeysStartingWith` out of the class, as above.

- [ ] **Step 3: Check it was a move.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate && D=android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore && python /c/Users/lumen/AppData/Local/Temp/decompose-a/moved.py HEAD $D/LessonsPreferences.kt $D/LessonsPreferences.kt $D/BundleTags.kt --sub '\bstore\.='
  ```
  Expected `-` lines, exactly the three declarations that are widened:
  - `- 1x private val preferences: Flow<Preferences> = dataStore.data.catch { cause ->`;
  - `- 1x private suspend fun write(transform: suspend (MutablePreferences) -> Unit) {`;
  - `- 1x private fun MutablePreferences.removeKeysStartingWith(matches: (String) -> Boolean) {`.

  Expected `+` lines:
  - those three declarations as `internal`;
  - `package …` and `import kotlinx.coroutines.flow.first`;
  - `BundleTags`' KDoc lines;
  - `internal class BundleTags(private val store: LessonsPreferences) {` and one `}`;
  - the `bundleTags` property with its KDoc;
  - the seven delegate lines and their seven `/** @see BundleTags.… */` lines.

  Nothing else.

- [ ] **Step 4: Line lengths.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate && awk 'length($0) > 120 {print FILENAME": "FNR": "length($0)}' android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/datastore/*.kt
  ```
  Expected: no output. The longest new lines are `scheduleFingerprint` in `BundleTags`, at 115, and the `writeBundleTag` delegate, at 105.

- [ ] **Step 5: The module.** Run Task 2's Step 5 command. Expected: `BUILD SUCCESSFUL`, with `core/data: tests=623 failures=0`; `BundleTagsAndRolesTest`'s five among them, unchanged.

- [ ] **Step 6: Commit** the two files.
  ```
  Give the bundle tags and the schedule's fingerprint a class of their own over the store

  BundleTags holds the seven calls the sync makes about what it keeps beside
  the settings, moved verbatim but for store. before the two reads and the five
  writes. It works through LessonsPreferences rather than the file, so every
  write still goes through write() and refreshes the credentials, and the
  store's dataStore stays private: write and the preferences flow become
  internal instead, as the design's decision 6 lists, and removeKeysStartingWith
  becomes a top-level internal function in the same file, because the tags and
  clearSession's roles both sweep with it.

  LessonsPreferences keeps the seven as one-line delegates where they stood,
  so LessonsContainer and SchoolAlerts change nothing, and
  BundleTagsAndRolesTest, written before this commit, holds them unchanged.
  ```

---

### Task 8: Rewrite `:core:data`'s baseline after `LessonsPreferences`' split, and read it

**Files:** Modify `android/core/data/detekt-baseline.xml`, and nothing else.

**Expected diff** (decision 2: a moved entry reappears under its new file and signature; one carried into a newly named declaration counts as moved). Six entries go:
```
CyclomaticComplexMethod:LessonsPreferences.kt:LessonsPreferences$private fun Preferences.toSettings: AppSettings
LongMethod:LessonsPreferences.kt:LessonsPreferences$override suspend fun updateSettings
LongMethod:LessonsPreferences.kt:LessonsPreferences$private fun Preferences.toSettings: AppSettings
MagicNumber:LessonsPreferences.kt:LessonsPreferences$7
ReturnCount:LessonsPreferences.kt:internal fun Preferences.toDiarySession: DiarySession?
ReturnCount:LessonsPreferences.kt:internal fun Preferences.toDiaryTarget: DiaryTarget?
```
And come back as:
```
CyclomaticComplexMethod:SettingsCodec.kt:internal fun Preferences.toSettings: AppSettings
LongMethod:SettingsCodec.kt:internal fun MutablePreferences.putSettings
LongMethod:SettingsCodec.kt:internal fun Preferences.toSettings: AppSettings
MagicNumber:SettingsCodec.kt:7
ReturnCount:DiaryKeys.kt:internal fun Preferences.toDiarySession: DiarySession?
ReturnCount:DiaryKeys.kt:internal fun Preferences.toDiaryTarget: DiaryTarget?
```
**`TooManyFunctions:LessonsPreferences.kt:LessonsPreferences : DiarySessionStoreShellModeSourceSettingsStore` stays**, because the class keeps thirty-two functions, the delegates among them.

Three things may differ, and are still moves:
- **The rendering of a signature.** For example, a `MagicNumber` entry with no class, like `GithubRepositoryImpl.kt:1000L` today, or a receiver written another way.
- **`putSettings`' `LongMethod` may be absent.** It is 59 code lines plus its signature and brace, against a limit of 60. If it is absent, `updateSettings`' entry simply went, and the commit message says so.
- **Order.** The file is sorted.

**Anything else** in the diff is a new finding: an entry for `PreferenceKeys.kt`, `BundleTags.kt` or `ShellKeys.kt`, a `MaxLineLength`, or an entry that does not pair with one that went.

- [ ] **Step 1: Rewrite.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 :core:data:detektBaseline
  ```
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Read the diff.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate && git status --short && git diff -U0 -- android/core/data/detekt-baseline.xml && grep -c "<ID>" android/core/data/detekt-baseline.xml
  ```
  Expected:
  - only `android/core/data/detekt-baseline.xml` is modified;
  - the diff is the six `-` and six `+` lines above, or six and five;
  - the file holds 201 entries, or 200 without `putSettings`' `LongMethod`.

- [ ] **Step 3: If there is a new finding, it is fixed, not baselined.**
  1. `git checkout -- android/core/data/detekt-baseline.xml`.
  2. Fix the code in a commit of its own, named for the finding, for example «Wrap the line the key qualifier took past 120 characters». If the code is right as written, use `@Suppress("<Rule>")` on the declaration, with a comment saying why.
  3. Rerun Step 1 and Step 2.

- [ ] **Step 4: detekt passes against it.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 :core:data:detekt
  ```
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit** `android/core/data/detekt-baseline.xml` alone. The pairs below are the expected ones: write what the diff really showed.
  ```
  Move LessonsPreferences' detekt findings to the files that now hold them

  Rewritten with :core:data:detektBaseline alone, and read line by line. Every
  entry that went came back, under the same rule, for the same code:
  - toSettings' CyclomaticComplexMethod and LongMethod, and its MagicNumber 7,
    LessonsPreferences.kt -> SettingsCodec.kt;
  - updateSettings' LongMethod, carried by the lines that became its write
    half: updateSettings -> putSettings, in SettingsCodec.kt;
  - toDiaryTarget's and toDiarySession's ReturnCount, LessonsPreferences.kt ->
    DiaryKeys.kt.
  LessonsPreferences' TooManyFunctions stays, because the class keeps
  thirty-two functions, the seven delegates among them. No entry is new, and
  the module's file holds 201, as before.
  ```

---

### Task 9: Split `GithubRepositoryImpl` into the device flow, the pull requests, the issues and the permissions read

**Files:**
- Create, all in `android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/github/`: `GithubDeviceFlow.kt`, `GithubPullRequests.kt`, `GithubIssues.kt` and `GithubPermissions.kt`.
- Modify `GithubRepositoryImpl.kt`, in the same folder.

**Moves, verbatim with their KDoc (lines at `6354c3d`).** Each `override` that a collaborator's method does not carry is dropped.

- **`GithubDeviceFlow`:**
  - the constructor lines `private val clientId: String,` and `private val scope: CoroutineScope,` (48–49);
  - `isConfigured` (60);
  - `mutableFlow` and `flow` (64–65);
  - `lock`'s KDoc, `lock` and `pollJob` (67–73);
  - `signIn` (75–90) and `cancelSignIn` (92–98);
  - `runDeviceFlow` (458–493), `complete` (495–509), `requestDeviceCode` (511–526), `pollOnce` (528–550), `fetchAccount` (552–563) and `loginRequest` (565–573);
  - into its own private companion: `DEVICE_CODE_URL`, `ACCESS_TOKEN_URL` and `USER_URL` (578–580), `SCOPE` with its KDoc (582–583), `GRANT_TYPE` (584), and `ERROR_PENDING` and `ERROR_SLOW_DOWN` (618–619);
  - at its file's top level: `PollOutcome` (626–632), `PollVerdict` (634–642), `pollUntilDecided` (644–700), and `MIN_INTERVAL_SECONDS`, `SLOW_DOWN_SECONDS` and `EXPIRY_GRACE_MILLIS` (702–705), still `private`.

  `DeviceFlowPollTest` reaches the top-level three under the same names and package.
- **`GithubIssues`:** `fileIssue` (119–157); into its companion, `ISSUE_LABEL` with its KDoc (586–587).
- **`GithubPullRequests`:**
  - `openTranslationPullRequest` (159–246);
  - `ensureFork` (299–321), `repositoryExists` (323–337), `refSha` (339–344), `createBranch` (346–359), `contents` (361–377), `putContents` (379–405) and `openPullRequest` (407–438);
  - `get` (440–456), with line 443's `[fileIssue]` requalified as `[GithubIssues.fileIssue]`;
  - into its companion: `BASE_BRANCH` (589–594), `BRANCH_PREFIX` (596–602), `COMMIT_MESSAGE` (604), `PULL_TITLE` (605), `EMPTY_JSON` (607–608), and `FORK_ATTEMPTS` with `FORK_POLL_MILLIS` (610–616).
- **`GithubPermissions`:** `repositoryPermissions` with its KDoc (248–269), and `askPermissions` (271–297).
- **Stays in `GithubRepositoryImpl.kt`:**
  - the class KDoc, plus one paragraph;
  - `appContext` and `preferences` (52–53);
  - the `userAgent` KDoc (55);
  - `account` (62);
  - `signOut` (100–117), verbatim. It calls `cancelSignIn()`, now the delegate, so «signOut cancels the flow, then clears» holds.

**Widens:**
- `TAG`, `HTTP_CREATED` and `HTTP_UNAUTHORISED`, from the private companion to `internal const val` at the top level of `GithubRepositoryImpl.kt`, because four files read them.
- **`userAgent`** changes type rather than visibility: `private val userAgent: String by lazy` becomes `private val userAgent: Lazy<String> = lazy`. The one `Lazy` is handed to the four, so the binder call behind it is still made at most once, at the first call that needs it.
- Nothing else. Every helper is `private` in the one class that calls it, and the four classes are `internal`.

- [ ] **Step 1: Write `GithubRepositoryImpl.kt` as this.** The marked part is lines 101–116, verbatim.
  ```kotlin
  package com.lumenpearson.lessons.core.data.github

  import android.content.Context
  import android.util.Log
  import com.lumenpearson.lessons.core.data.repository.DeviceFlow
  import com.lumenpearson.lessons.core.data.repository.GithubAccount
  import com.lumenpearson.lessons.core.data.repository.GithubRepository
  import com.lumenpearson.lessons.core.data.repository.IssueDraft
  import com.lumenpearson.lessons.core.data.repository.IssueResult
  import com.lumenpearson.lessons.core.data.repository.PermissionsAnswer
  import com.lumenpearson.lessons.core.data.repository.PullRequestResult
  import com.lumenpearson.lessons.core.data.repository.TranslationChange
  import kotlinx.coroutines.CancellationException
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.flow.Flow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.withContext

  /**
   * GitHub sign-in by device flow, and issues filed with the result.
   *
   * Its four concerns are four classes — [GithubDeviceFlow], [GithubPullRequests],
   * [GithubIssues] and [GithubPermissions] — and this one hands them out behind
   * the interface. They share [GithubPreferences] rather than a token: none of
   * them holds one, every call reads it, and whichever of them meets a `401`
   * clears it, so a token GitHub stopped honouring is forgotten wherever it is
   * found. They share one lazy user agent too, which is asked for once.
   *
   * Internal because the container hands out the interface; there is nothing to
   * do with the class itself except build a second one, and the polling job
   * needs there to be one.
   *
   * @param scope where the polling lives. It has to outlive the sign-in sheet: the
   *   user is sent to the browser to type the code, and by the time they come
   *   back the sheet may have been dismissed, rotated away or killed with the
   *   activity. The container passes a process-wide scope for exactly this.
   */
  internal class GithubRepositoryImpl(
      context: Context,
      clientId: String,
      scope: CoroutineScope,
  ) : GithubRepository {

      private val appContext: Context = context.applicationContext
      private val preferences = GithubPreferences(appContext)

      /** Lazy: the package info is a binder call, and most sessions never sign in. */
      private val userAgent: Lazy<String> = lazy {
          GithubApi.userAgent(GithubApi.installedVersion(appContext))
      }

      private val deviceFlow = GithubDeviceFlow(clientId, scope, preferences, userAgent)
      private val issues = GithubIssues(preferences, userAgent)
      private val pullRequests = GithubPullRequests(preferences, userAgent)
      private val permissions = GithubPermissions(preferences, userAgent)

      override val isConfigured: Boolean = deviceFlow.isConfigured

      override val account: Flow<GithubAccount?> = preferences.account

      override val flow: StateFlow<DeviceFlow> = deviceFlow.flow

      override fun signIn() = deviceFlow.signIn()

      override fun cancelSignIn() = deviceFlow.cancelSignIn()

      override suspend fun signOut() {
          // ← lines 101–116 of 6354c3d, verbatim
      }

      override suspend fun fileIssue(draft: IssueDraft): IssueResult = issues.fileIssue(draft)

      override suspend fun openTranslationPullRequest(changes: List<TranslationChange>): PullRequestResult =
          pullRequests.openTranslationPullRequest(changes)

      override suspend fun repositoryPermissions(): PermissionsAnswer = permissions.repositoryPermissions()
  }

  // Shared by the repository and the four classes it hands its work to, which live
  // in files of their own: Kotlin's `private` ends at the file.
  internal const val TAG = "Lessons"
  internal const val HTTP_CREATED = 201
  internal const val HTTP_UNAUTHORISED = 401
  ```

- [ ] **Step 2: Write the four collaborators.** Each header is below; the bodies are the lines listed under «Moves».

  **`GithubDeviceFlow.kt`.** The class's body holds, in this order:
  1. `private val userAgent: String by userAgent`;
  2. `isConfigured`;
  3. `mutableFlow` and `flow`;
  4. `lock` and `pollJob`;
  5. `signIn` and `cancelSignIn`;
  6. the six private functions;
  7. the private companion.

  `PollOutcome`, `PollVerdict`, `pollUntilDecided` and its three constants follow the class.
  ```kotlin
  package com.lumenpearson.lessons.core.data.github

  import android.util.Log
  import com.lumenpearson.lessons.core.data.repository.DeviceFlow
  import com.lumenpearson.lessons.core.data.repository.GithubAccount
  import java.io.IOException
  import kotlinx.coroutines.CancellationException
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.Job
  import kotlinx.coroutines.NonCancellable
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.asStateFlow
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.withContext
  import okhttp3.FormBody
  import okhttp3.Request

  /**
   * The OAuth device flow: its state, its polling job, and the requests to
   * GitHub's login endpoints.
   *
   * One class, so that the rule [lock] carries — [signIn] and [cancelSignIn] are
   * called from the main thread while the job finishes on a worker — is read in
   * one file. The token it is granted goes into [GithubPreferences] and nowhere
   * else; the other three read it from there.
   *
   * @param scope where the polling lives; see [GithubRepositoryImpl].
   */
  internal class GithubDeviceFlow(
      private val clientId: String,
      private val scope: CoroutineScope,
      private val preferences: GithubPreferences,
      userAgent: Lazy<String>,
  ) {

      private val userAgent: String by userAgent
  ```

  **`GithubIssues.kt`:**
  ```kotlin
  package com.lumenpearson.lessons.core.data.github

  import android.util.Log
  import com.lumenpearson.lessons.core.data.repository.IssueDraft
  import com.lumenpearson.lessons.core.data.repository.IssueResult
  import kotlinx.coroutines.CancellationException
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.withContext
  import okhttp3.RequestBody.Companion.toRequestBody

  /**
   * An issue filed as the signed-in account, labelled as sent from the app.
   *
   * Reads the token from [GithubPreferences] on every call, and clears it on a
   * `401` itself, as each of [GithubRepositoryImpl]'s collaborators does.
   */
  internal class GithubIssues(
      private val preferences: GithubPreferences,
      userAgent: Lazy<String>,
  ) {

      private val userAgent: String by userAgent
  ```

  **`GithubPullRequests.kt`:**
  ```kotlin
  package com.lumenpearson.lessons.core.data.github

  import android.util.Base64
  import android.util.Log
  import com.lumenpearson.lessons.core.data.repository.PullRequestResult
  import com.lumenpearson.lessons.core.data.repository.TranslationChange
  import kotlinx.coroutines.CancellationException
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.withContext
  import okhttp3.RequestBody.Companion.toRequestBody

  /**
   * A translation correction offered as a pull request from the reader's own
   * account: the fork, a branch cut from upstream, the files patched, the pull
   * request opened.
   *
   * [get] is this class's own, because only these helpers read a body by URL;
   * like every call here it reads the token from [GithubPreferences], and a
   * `401` clears it.
   */
  internal class GithubPullRequests(
      private val preferences: GithubPreferences,
      userAgent: Lazy<String>,
  ) {

      private val userAgent: String by userAgent
  ```

  **`GithubPermissions.kt`.** It has no companion.
  ```kotlin
  package com.lumenpearson.lessons.core.data.github

  import com.lumenpearson.lessons.core.data.developer.RepositoryPermissions
  import com.lumenpearson.lessons.core.data.repository.PermissionsAnswer
  import java.io.IOException
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.withContext
  import kotlinx.serialization.SerializationException

  /**
   * What the signed-in account may do in this project's repository: the
   * developer mode's gate.
   *
   * Reads the token from [GithubPreferences] on every call and clears it on a
   * `401`, as the other three do; any other refusal decides nothing (see
   * [repositoryPermissions]).
   */
  internal class GithubPermissions(
      private val preferences: GithubPreferences,
      userAgent: Lazy<String>,
  ) {

      private val userAgent: String by userAgent
  ```

- [ ] **Step 3: Check it was a move.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate && G=android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/github && python /c/Users/lumen/AppData/Local/Temp/decompose-a/moved.py HEAD $G/GithubRepositoryImpl.kt $G/GithubRepositoryImpl.kt $G/GithubDeviceFlow.kt $G/GithubPullRequests.kt $G/GithubIssues.kt $G/GithubPermissions.kt --sub '^override ='
  ```
  Expected `-` lines, exactly these five:
  ```
  - 1x * A 401 clears the token for the same reason [fileIssue] does: GitHub no
  - 1x const val HTTP_CREATED = 201
  - 1x const val HTTP_UNAUTHORISED = 401
  - 1x const val TAG = "Lessons"
  - 1x private val userAgent: String by lazy {
  ```
  Expected `+` lines, and nothing else:
  - **Per new file:** four `package` lines; the imports the five files now repeat; the four classes' KDoc lines; the four headers `internal class Github…(`; `+ 4x private val preferences: GithubPreferences,`; `+ 4x userAgent: Lazy<String>,`; `+ 4x ) {`; `+ 4x private val userAgent: String by userAgent`; `+ 2x private companion object {`; and closing braces.
  - **In the repository:**
    - its new KDoc paragraph;
    - `clientId: String,` and `scope: CoroutineScope,`;
    - `private val userAgent: Lazy<String> = lazy {`;
    - the four collaborator properties;
    - `val isConfigured: Boolean = deviceFlow.isConfigured` and `val flow: StateFlow<DeviceFlow> = deviceFlow.flow`;
    - the five delegates (`openTranslationPullRequest`'s is two lines);
    - the two-line comment and the three `internal const val`s.
  - **In `get`'s KDoc:** the line with `[GithubIssues.fileIssue]`.

- [ ] **Step 4: Who holds the lock, and who forgets a dead token.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate/android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/github && F="GithubRepositoryImpl.kt GithubDeviceFlow.kt GithubPullRequests.kt GithubIssues.kt GithubPermissions.kt" && grep -c "synchronized(lock)" $F; grep -c "preferences.clear()" $F; grep -c "HTTP_UNAUTHORISED" $F; grep -c "HTTP_CREATED" $F; grep -n "lazy {\|= GithubPreferences(" *.kt
  ```
  Expected, against `6354c3d`'s single file (2, 4, 4, 4):

  | File | `synchronized(lock)` | `preferences.clear()` | `HTTP_UNAUTHORISED` | `HTTP_CREATED` |
  | --- | --- | --- | --- | --- |
  | `GithubRepositoryImpl.kt` | 0 | 1 (`signOut`) | 1 (the declaration) | 1 (the declaration) |
  | `GithubDeviceFlow.kt` | 2 | 0 | 0 | 0 |
  | `GithubPullRequests.kt` | 0 | 1 (`get`) | 1 | 2 |
  | `GithubIssues.kt` | 0 | 1 | 1 | 1 |
  | `GithubPermissions.kt` | 0 | 1 | 1 | 0 |

  The last `grep` prints three lines: `GithubApi.kt:70`, and `GithubRepositoryImpl.kt`'s `GithubPreferences(` and its `lazy {`.

- [ ] **Step 5: Line lengths.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate && awk 'length($0) > 120 {print FILENAME": "FNR": "length($0)}' android/core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/github/*.kt
  ```
  Expected: no output.

- [ ] **Step 6: The module.** Run Task 2's Step 5 command. Expected: `BUILD SUCCESSFUL`, and `count.sh` prints `core/data: tests=623 failures=0`. `DeviceFlowPollTest`'s 6 are among them, unchanged.

- [ ] **Step 7: Commit** the five files.
  ```
  Split GithubRepositoryImpl into the device flow, the pull requests, the issues and the permissions read

  Four concerns, four classes, each moved verbatim from the one file:
  GithubDeviceFlow holds the client id, the scope, the state flow, the lock,
  the polling job and the login requests, so the synchronized(lock) rule is
  read in one file, with pollUntilDecided, PollOutcome and PollVerdict beside
  it under the same names; GithubPullRequests holds the fork, the branch, the
  patched files, the pull request and the get only they use; GithubIssues
  files an issue; GithubPermissions reads the repository's rights for the
  developer mode's gate. Each constant goes to the one class that reads it.

  They share GithubPreferences, which holds no token in memory: every call
  reads it, and whichever class meets a 401 clears it, four places as before.
  They share one Lazy user agent, which the repository makes and hands to
  each, so the binder call behind it is still made once at most. TAG,
  HTTP_CREATED and HTTP_UNAUTHORISED become internal and top-level, because
  four files read them. The repository builds the four and delegates;
  signOut still cancels the flow, then clears.

  Not covered: no test reaches any of the four classes. The HTTP client and
  API_BASE are global, so nothing can point them at a mock server; the
  compile, DeviceFlowPollTest, a line-by-line move check and review are the
  check, as the design said they would be.
  ```

---

### Task 10: Rewrite `:core:data`'s baseline after `GithubRepositoryImpl`'s split, and read it

**Files:** Modify `android/core/data/detekt-baseline.xml`, and nothing else.

**Expected diff.** Four entries go:
```
CyclomaticComplexMethod:GithubRepositoryImpl.kt:GithubRepositoryImpl$override suspend fun openTranslationPullRequest: PullRequestResult
LoopWithTooManyJumpStatements:GithubRepositoryImpl.kt:GithubRepositoryImpl$for
MagicNumber:GithubRepositoryImpl.kt:1000L
ReturnCount:GithubRepositoryImpl.kt:GithubRepositoryImpl$private suspend fun contents: Pair&lt;String, String&gt;?
```
And four more go:
```
ReturnCount:GithubRepositoryImpl.kt:GithubRepositoryImpl$private suspend fun ensureFork: Boolean
ReturnCount:GithubRepositoryImpl.kt:internal suspend fun pollUntilDecided: PollVerdict
SwallowedException:GithubRepositoryImpl.kt:unreachable: IOException
TooGenericExceptionCaught:GithubRepositoryImpl.kt:GithubRepositoryImpl$failure: Throwable
```
**`TooManyFunctions:GithubRepositoryImpl.kt:GithubRepositoryImpl : GithubRepository`** goes for good: the class keeps six functions. Decision 2 expected it.

They come back as:
```
CyclomaticComplexMethod:GithubPullRequests.kt:GithubPullRequests$suspend fun openTranslationPullRequest: PullRequestResult
LoopWithTooManyJumpStatements:GithubPullRequests.kt:GithubPullRequests$for
MagicNumber:GithubDeviceFlow.kt:1000L
ReturnCount:GithubPullRequests.kt:GithubPullRequests$private suspend fun contents: Pair&lt;String, String&gt;?
ReturnCount:GithubPullRequests.kt:GithubPullRequests$private suspend fun ensureFork: Boolean
ReturnCount:GithubDeviceFlow.kt:internal suspend fun pollUntilDecided: PollVerdict
SwallowedException:GithubDeviceFlow.kt:unreachable: IOException
TooGenericExceptionCaught:GithubDeviceFlow.kt:GithubDeviceFlow$failure: Throwable
TooGenericExceptionCaught:GithubIssues.kt:GithubIssues$failure: Throwable
TooGenericExceptionCaught:GithubPullRequests.kt:GithubPullRequests$failure: Throwable
```
- **`TooGenericExceptionCaught:GithubRepositoryImpl.kt:GithubRepositoryImpl$failure: Exception` stays,** because `signOut` stays.
- **One entry becomes three:** `failure: Throwable` covered `fileIssue`, `openTranslationPullRequest` and `runDeviceFlow`, which are three classes now.
- **`MagicNumber`** may also come back as `GithubDeviceFlow.kt:GithubDeviceFlow$1000L`. That is the same literal on the same line of `runDeviceFlow`, and it counts as moved.

The module's file goes to 202, or 203 with a second `1000L` entry, and one fewer in either case if Task 8 ended at 200.

**Anything else is a new finding:** an entry for `GithubPermissions.kt`, a `TooManyFunctions` for a collaborator, a `MaxLineLength`, or a `MatchingDeclarationName`.

- [ ] **Step 1: Rewrite.** Run Task 8's Step 1 command. Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 2: Read the diff.** Run Task 8's Step 2 command. Expected: only `android/core/data/detekt-baseline.xml` is modified, and the diff is the entries above.
- [ ] **Step 3: A new finding is fixed, not baselined.** The procedure is Task 8's Step 3.
- [ ] **Step 4: detekt passes.** Run Task 8's Step 4 command. Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit** `android/core/data/detekt-baseline.xml` alone. Write what the diff really showed.
  ```
  Move GithubRepositoryImpl's detekt findings to the four files that now hold them

  Rewritten with :core:data:detektBaseline alone, and read line by line:
  - openTranslationPullRequest's CyclomaticComplexMethod, its loop's
    LoopWithTooManyJumpStatements, and contents' and ensureFork's ReturnCount,
    GithubRepositoryImpl -> GithubPullRequests;
  - pollUntilDecided's ReturnCount, the swallowed IOException it polls through
    and the 1000L MagicNumber, GithubRepositoryImpl.kt -> GithubDeviceFlow.kt;
  - the one TooGenericExceptionCaught for Throwable, which covered fileIssue,
    openTranslationPullRequest and runDeviceFlow, is three now, one per class
    those three moved to.
  The repository's TooManyFunctions is gone, as the design expected: it keeps
  six functions. Its TooGenericExceptionCaught for Exception stays with
  signOut. No entry is new.
  ```

---

### Task 11: The gates, the counts, the documents, the close-out and the pull request

**Files:**
- `README.md`, `docs/architecture.md`, `docs/build.md`, `.claude/skills/gates/SKILL.md`, `HANDOVER.md` and `docs/history.md`.
- The `handover` skill's seven places hold the Android count in four of them: HANDOVER's cheat-sheet, the README, `docs/architecture.md` and the `gates` skill. `CLAUDE.md`, `CONTRIBUTING.md` and the `server-tests` agent carry the server's count only, which `grep` confirmed.

**The pull request's gates.** Each one runs alone, in order, with the Bash tool's `run_in_background`. None starts before the one above it has finished.

- [ ] **Step 1: `test`.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 test
  ```
  Expected: `BUILD SUCCESSFUL`. `bash C:/Users/lumen/AppData/Local/Temp/decompose-a/count.sh` should then print `core/model: tests=125`, `core/data: tests=623`, `core/designsystem: tests=196`, `widget: tests=126`, `app: tests=668` and `total: 1738`, with every failure and error 0. Use the numbers it prints; if they differ from these, the documents take the printed ones, and the difference is explained before the pull request is opened.
- [ ] **Step 2: `assembleDebug`.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 assembleDebug
  ```
  Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 3: `assembleRelease`.** R8 is where «worked in debug» stops. The build signs with the debug key when no release key is configured. Nothing here reads, sets or prints a keystore value.
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 assembleRelease
  ```
  Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 4: `detekt`**, all five modules against their baselines.
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 detekt
  ```
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: The pull request touched only what it says.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate && git diff --stat 6354c3d..HEAD -- android/ && git diff --name-only 6354c3d..HEAD -- '*/detekt-baseline.xml'
  ```
  Expected:
  - the eleven Kotlin files under `datastore/` and `github/` (two of them modified, nine created);
  - `SettingsRoundTripTest.kt`, `BundleTagsAndRolesTest.kt` and `AppSettingsTest.kt`;
  - `android/core/data/detekt-baseline.xml`, the only baseline.

- [ ] **Step 6: Recount the baselines.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate/android && cat */detekt-baseline.xml core/*/detekt-baseline.xml | grep -c "<ID>" && cat */detekt-baseline.xml core/*/detekt-baseline.xml | grep -o "<ID>[A-Za-z]*:" | sort | uniq -c | sort -rn | head -3
  ```
  In `docs/build.md`, «detekt», «What it forgives» (lines 843–844), replace «492 entries» and the three counts after it with the printed ones. The expected total is 493, from 492 − 1 + 2, with `MagicNumber` 218, `ReturnCount` 81 and `MaxLineLength` 65; it is 494 with a second `1000L` entry, and one fewer without `putSettings`' `LongMethod`. «(#325)» becomes «(#325, and pull request A of the Android decomposition)». The sentence about 576 findings stays: it is a record of when the baselines were first written.

- [ ] **Step 7: The Android count, in the four places that carry it.** First, `git grep -n "1730"`.
  - `README.md:216`: `1730 tests` → `1738 tests`.
  - `docs/architecture.md:1008–1010`: the total, 1730 → 1738, and `:core:data` 615 → 623. The other four modules stay.
  - `.claude/skills/gates/SKILL.md:41`: `1730 today` → `1738 today`.
  - `HANDOVER.md`, the cheat-sheet in «How to continue» (line 1907): `# 1730 tests across the five modules` → `# 1738 …`.

  Every other `1730` in `HANDOVER.md` belongs to a batch section and is a record; it moves with its section and is not rewritten.

- [ ] **Step 8: `docs/architecture.md`'s test table.** After the row for `android/core/data/.../sync/SyncWorkerSourceTest.kt`, add:
  ```
  | `android/core/data/.../datastore/SettingsRoundTripTest.kt`, `BundleTagsAndRolesTest.kt` | every setting written comes back under its own key, with a value that differs from the defaults in every field (held by reflection, so a field added without a key fails) and an empty file reading as the defaults; a bundle tag read under its own signature only, class 1's sweep sparing classes 12 and 100, a sweep outside a set sparing the set, the fingerprint and the roles, the fingerprint forgotten on a switch of class, and a role kept per class and gone at a sign-out | JVM JUnit |
  ```

- [ ] **Step 9: No document names a declaration where it no longer is.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate && git grep -nE "LessonsPreferences\.(toSettings|bundleTag|scheduleFingerprint|forgetBundleTag|removeKeysStartingWith)|GithubRepositoryImpl\.(signIn|fileIssue|runDeviceFlow|ensureFork|pollUntilDecided)|LessonsPreferences\.kt.*(ShellKeys|DiaryKeys)|no seam to pin" -- . ':!docs/history.md' ':!docs/specs/'
  ```
  Expected: no output, exit 1.
  - Today's reference documents name neither file; only `HANDOVER.md:1191` names `LessonsPreferences`, and what it says («the memberships live in `Memberships.kt`, separately from `LessonsPreferences`») stays true.
  - The baselines still carry `LessonsPreferences.kt` and `GithubRepositoryImpl.kt`, for the entries that stayed, and the pattern does not match them.

- [ ] **Step 10: The server's tests that read these documents.** Run them while no Gradle job is running.
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate/server && .venv/Scripts/pytest.exe -q -p no:xdist tests/test_env_example.py tests/test_ci_paths.py tests/test_schema_version.py
  ```
  Expected: all passed. The full server suite does not run: nothing under `server/` changes. CI's server job runs it, because `docs/*.md` and `.claude/*.md` are in its path filter.

- [ ] **Step 11: The close-out**, by the `handover` skill (read it first), written while the pull request is open. The facts of #422's merge are in `C:/Users/lumen/AppData/Local/Temp/claude/C--Users-lumen-StudioProjects-lessons--claude-worktrees-continue-previous-session-991230/22193d0b-d006-4776-83a8-e78de1f22a12/scratchpad/after-422.md`; read it then.
  - **The opening paragraph.**
    - #422 joins the merged list. `main` is at `6354c3d`, the merge of #422, made by the session at 13:48:33 UTC on 11 October 2026 after the five checks on `f395971`.
    - Production deployed `6354c3d` (READY). Its warmup and v2 capabilities answers are in `after-422.md`; quote them there, in `HANDOVER.md`, which `test_schema_version.py` does not read.
    - The one open pull request is this one, from `android/decomposition-a`, on milestone 11, `Refs #273`: the first of sub-project 4's three.
    - The schema did not move.
    - Every «this pull request» meaning #406 now says #422.
  - **A new section on top**, «What the last session added: sub-project 4's first pull request, `LessonsPreferences` and `GithubRepositoryImpl` split, and the two-worker cap». It carries:
    - one sentence per commit with its SHA, in the commit messages' voice;
    - «Gates», with the real outputs of Steps 1–6, the counts and the baseline's entries;
    - «What was deliberately left alone», from «Deliberately left out» below;
    - «What nobody has verified in this batch»:
      - no test reaches the four GitHub classes, so their check was the compile, `DeviceFlowPollTest`, the move check and review;
      - no APK from this branch was installed on a device or an emulator;
      - what the workers cap saves is unmeasured;
    - «After #422's merge», with the facts of `after-422.md`:
      - the merge;
      - the production read;
      - the board (#406, #419, #420 and #422 Done; #421 in the backlog);
      - the parked docs items (`«Петербург»` in English prose at `docs/api.md:1892`, which stays parked);
      - the owner's open question on the privacy point's wording for edition 4;
      - the git-ignored SDD workspace left in place.
  - **#406's section becomes «What the session before it added».** Its first line becomes «Merged as #422 (`6354c3d`, 11 October 2026), from `server/diary-routes`, on milestone 11.», and its «this pull request» becomes «#422».
  - **#411's section moves verbatim** to the top of `docs/history.md`, under its introduction, retitled «What the batch before added: the server suite on PostgreSQL, and sub-project 3's live-verification gate (#411)». Any «section 5» or «above» in it gains «in `HANDOVER.md`».
  - **The milestone table.**
    - Row 11: #422 joins the merged pull requests, and «this pull request (open)» stays.
    - Row 13: #421 is added. It was missing, and `after-422.md` parked it for this.
  - **The counts:** the cheat-sheet's, from Step 7. Under its code block, add one line: «On the maintainer's machine every Gradle command carries `--max-workers=2` and runs alone: `docs/build.md`, «Locally».»
  - **Section 5** gains the three «not verified» items above.
  - **Section 7.**
    - «Next for the programme» put #407 before sub-project 4. Read #407's state on GitHub, and say what is next: this pull request, then B (`ManagementViewModel`, `SettingsViewModel` after its characterization test), then C (`OnboardingScreen`, `FloatingToolbar`, `HomeShell`'s rules), and #407 where it now stands.
    - The #406 paragraphs whose condition has passed (the merge landed on 11 October, the date `legal.json` already says) move to «Moved out of section 7» in `docs/history.md`.
    - The owner's question about edition 4's wording is added.

- [ ] **Step 12: Commit.** Stage the six files by path: `README.md`, `docs/architecture.md`, `docs/build.md`, `.claude/skills/gates/SKILL.md`, `HANDOVER.md` and `docs/history.md`.
  ```
  Count the tests and the baselines after the first Android split, and hand over pull request A

  Eight tests more on Android, 1738 across the five modules (:core:data 623),
  in the four places that carry the Android count, and a row in
  architecture.md's test table for the two that hold the settings' round trip
  and the store's tags, fingerprint and roles. docs/build.md's baseline
  totals are recounted from the five files. HANDOVER.md describes this pull
  request and #422's merge, #406's batch becomes the one before, and #411's
  moves to docs/history.md.
  ```

- [ ] **Step 13: Push, and open the pull request as a draft.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-live-gate && git push -u origin android/decomposition-a
  ```
  - **How.** On this machine `gh` is `C:\Program Files\GitHub CLI\gh.exe`, called through PowerShell. Run `gh pr create --draft --base main --head android/decomposition-a --milestone "v0.10.0 — One contract: REST v2, Connect and native gRPC, build console"`. `gh pr edit` fails on this repository, so a body is changed with `gh api -X PATCH repos/lumenpearson/lessons/pulls/<N> -F body=@<file>`.
  - **Title.** `Split LessonsPreferences and GithubRepositoryImpl along their concerns, and write down the two-worker cap`.
  - **Body**, mirroring `.github/PULL_REQUEST_TEMPLATE.md`:
    - tick `android/core/data` and `docs/`;
    - under «Boundaries», tick that `:core:data` does not depend on `:widget`, that no new Russian string lacks its twin (none is added), and that no token, code, password or keystore path is in the diff;
    - «Verification» carries the real summary lines of Steps 1–4 and `count.sh`'s output, plus both baseline diffs' counts. Server: no code changed, the three document-reading tests passed, and CI runs the suite;
    - «What is NOT covered» is the three items of Step 11;
    - end the body with `Refs #273` on a line of its own.
  - **The board.** Add the pull request to project 6 and fill Priority, Size, Estimate and the dates by the project's README, as the `github-pr` skill's «The board» says. Read it back.

- [ ] **Step 14: Merge.**
  - **When.** After CI is green on the exact head, run the `github-pr` skill's five checks, then mark the pull request ready and merge it with `expectedHeadSha` pinned.
  - **If the merge is refused.** If the session's permissions refuse it, ask the owner to merge it.
  - **After the merge.** The next close-out writes the SHA of this merge; this one does not.

---

## Deliberately left out

- **Pull request B:**
  - `ManagementViewModel`: its state types, then per-sheet collaborators over shared plumbing;
  - `SettingsViewModel`: its characterization test, then its collaborators (decision 5).
- **Pull request C:**
  - `OnboardingScreen`, one file per step, with `OnboardingPromisesTest`'s path and anchor and the anchor made to fail when missing (decision 3);
  - `FloatingToolbar`'s split by concern;
  - `HomeShell`'s pure rules, with the body left inline (decision 4).
- **Anything sub-project 5 rewrites:**
  - the network layer: the Retrofit interfaces, the v1 DTOs, the failure classifiers;
  - the container's bindings.

  Nothing here splits or touches them. `GithubApi.kt` and `GithubDtos.kt` stay as they are.
- **A seam for the GitHub client.** Making `GithubApi.client` or `API_BASE` injectable, so that a mock server could reach the four classes, is a change rather than a move. It belongs to a pull request of its own, if anybody wants it.
- **detekt with type resolution**, and the 93 rules it would switch on (`docs/build.md`, «detekt»).
- **The cap anywhere but the command line.** It goes neither in `android/gradle.properties`, which CI reads, nor in `~/.gradle/gradle.properties`, which no agent opens.
- **`docs/history.md`'s note** that the tag sweep was «written and not run». It is a record of its time and is not edited. `BundleTagsAndRolesTest` is what answers it now.
- **Feature Gradle modules and the package cycles**, which the programme rejected for this sub-project.

## Self-review

**Coverage of the design and the brief:**

| Asked for | Where it is done |
| --- | --- |
| `LessonsPreferences` → keys, the settings codec, the bundle tags, the diary keys (decision 6) | Tasks 4 (`ShellKeys`, `DiaryKeys`), 5 (`PreferenceKeys`), 6 (`SettingsCodec`), 7 (`BundleTags`) |
| A round-trip test beside the codec (decision 6) | Task 2, before the move |
| `GithubRepositoryImpl` → device flow, pull requests, issues, permissions (decision 6) | Task 9 |
| What is widened, listed (decision 1) | «What is widened»; each task's «Widens» |
| The baseline rewritten in its own commit, its diff read (decision 2) | Tasks 8 and 10, each with the expected entries |
| The workers cap on the command line, in `docs/build.md` (decision 9, question 1) | Task 1 |
| `:core:data:test` per commit, then the gates once | every task from 2 on; Task 11, Steps 1–4 |
| The counts in the handover skill's places, the documents, the close-out | Task 11, Steps 6–12 |

**Where the code at `6354c3d` departs from what the design and the brief assumed:**
- **`MembershipKeys`'s file.** It is the last declaration of `Memberships.kt`, beside the code that reads it, not a file named after it. Decision 6's «like `MembershipKeys`» is about the object's shape, and that is what `PreferenceKeys` copies.
- **The settings' round-trip seam.** The design repeats `AppSettingsTest`'s note that there is none, but the internal two-argument constructor over a `DataStore` has been that seam since #201. So the round trip is held before the move, not beside it afterwards.
- **No test reaches the bundle tags, the fingerprint or the roles,** and the design names none for them. Task 3 adds one under decision 1.
- **A new finding from the qualifier.** `PreferenceKeys.` takes one line to 131 characters, and the design did not foresee it. Task 5 fixes it.
- **The `TooGenericExceptionCaught` entry for `Throwable` becomes three entries,** because its three catch sites move to three classes. The baseline grows by one or two where decision 2 pictured pure moves.
- **«The largest collaborator has about eight»:** `GithubPullRequests` has nine functions. That is still under detekt's eleven.

**Not verified while planning** (read-only; no Gradle, no pytest):
- that the two new test classes compile and pass, and that the default-file and round-trip assertions hold on the code as it is. They were reasoned from `toSettings`' fallbacks and each enum's `fromName`, all of which agree with `AppSettings()`;
- the exact text of every baseline signature, and whether `putSettings` keeps a `LongMethod`;
- that `qualify.py` and `codec.py` print the counts given. Both are checked against `moved.py` before anything is committed;
- that Java reflection's `declaredFields` on `AppSettings` and `AlertPreferences` holds exactly their constructor properties. The Kotlin compiler adds no instance field to a data class, and `AlertPreferences.silent` has a getter and no field;
- the final test total, which is a `grep` of `@Test`. It matched the documented per-module counts exactly.
