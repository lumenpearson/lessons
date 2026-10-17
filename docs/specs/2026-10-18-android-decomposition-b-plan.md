# Android decomposition, pull request B: `ManagementViewModel` and `SettingsViewModel` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** drafted read-only on 17 October 2026 against `1cc30fe`, the merge of #622. Nothing was built, run or committed while writing it.
- Every count below was taken with `grep`, `awk`, `git` or Python reading the tree.
- Every line number is a line of the file **at `1cc30fe`**. Find a declaration by its name, because the earlier tasks move the lines below it.
- The four move scripts were dry-run in memory against `1cc30fe`, in a condensed form, and wrote nothing. The counts they print and the move checker's `-` lines below come from that dry run. The scripts as written here were not run.
- Task 0 commits this plan as the first commit of `android/decomposition-b`, which was cut from `main` at `1cc30fe`.
- The run at each task's gate is the truth. Where it disagrees with a number here, the run wins, and the task says what to do.

**Goal:** the second of sub-project 4's three pull requests ([design](2026-10-05-android-decomposition-design.md), decision 8).
- **`ManagementViewModel.kt` (821 lines)** splits in two steps (decision 5):
  1. Its five state types go to `ManagementState.kt`.
  2. Its seven sheets' work goes to seven collaborator classes over one `ManagementPlumbing`.
- **`SettingsViewModel.kt` (716 lines)** gets a characterization test first, because nothing tests the class today. Then it splits the same way:
  1. Its state types go to `SettingsState.kt`.
  2. Its update check, its GitHub work and its Telegram link go to `SettingsUpdates`, `SettingsGithub` and `SettingsDeviceLink`.
- Both view models keep their constructors and every public name, each now a one-line delegate. The sheets, the screens and the existing tests change nothing.
- Nothing a test can see changes (decision 1). Two test classes come first, so that the moves are proved by something.

**Architecture:**
- **`:app/ui/admin/`** gains nine files beside `ManagementViewModel.kt`:
  - `ManagementState.kt`, with `Remote`, `PendingImport`, `ManagementNotice`, `SchoolSearch` and `ManagementUiState`, verbatim.
  - `ManagementPlumbing.kt`: `internal class ManagementPlumbing(private val viewModelScope: CoroutineScope)`. It holds `val state` and `read`, `write`, `finish` and `note`.
  - Seven collaborator files, each named after its class: `ClassCardActions`, `SubjectsActions`, `BellsActions`, `TimetableActions`, `DevicesActions`, `LogActions` and `RequestsActions`. Each is an `internal class` over the plumbing and the repository. `ClassCardActions` also takes the session and the scope.

  The view model builds the plumbing first and the seven classes next, all above `init`. It keeps `init`, `recheckRole`, `consumeNotice`, `dismissWriteFailure` and the factory, and 32 one-line delegates.
- **`:app/ui/settings/`** gains four files beside `SettingsViewModel.kt`:
  - `SettingsState.kt`, with `SettingsUiState`, `DeviceLinkState`, `DeviceLinkState.role` and `SettingsUiState.effectiveRole`, verbatim.
  - `SettingsUpdates.kt`, `SettingsGithub.kt` and `SettingsDeviceLink.kt`. Each is an `internal class` that owns the flow it writes and takes `viewModelScope` as a parameter.

  The view model keeps both `combine`s, both `stateIn`s, both `init` launches in their order, `message`, the setters, the sync, and the calls that only forward to a repository.
- **`:app`'s detekt baseline** is rewritten after each view model's split, alone. Both rewrites are expected to change nothing (see Rulings, 13).

**Tech Stack:**
- Kotlin 2.4.21, compiled by AGP 9.4.1.
- AndroidX Lifecycle 2.11.0 (`viewModelScope`, `ViewModelStore`) and kotlinx.coroutines 1.11.0 with `kotlinx-coroutines-test`.
- JUnit 4.13.2 on the JVM.
- detekt 2.0.0-alpha.6, without type resolution.
- Gradle 9.8.1 through the wrapper, on JDK 21.

**Spec:**
- `docs/specs/2026-10-05-android-decomposition-design.md`, approved on 5 October 2026. Decisions 1, 2, 5 and 8 are binding here.
- `docs/specs/2026-10-03-one-contract-design.md`, section 5, «Android (sub-project 4)».
- `docs/specs/2026-10-11-android-decomposition-a-plan.md`: the form, the tools and the gates this plan reuses.
- Epic #273. No issue is filed for this pull request: it fixes no defect, and nothing found while planning was one.

## How pull request B is cut

One pull request from `android/decomposition-b` to `main`, on milestone 11 (`v0.10.0 — One contract: REST v2, Connect and native gRPC, build console`), carrying `Refs #273`.

There are ten tasks and eight commits. Tasks 4 and 8 rewrite the baseline and are expected to find nothing to commit (Rulings, 13).

| Task | Commit | Tests added | `:app` after | Android total after |
| --- | --- | --- | --- | --- |
| 0 | This plan (plus two tools outside the repository, and the server's venv) | — | 687 | 1781 |
| 1 | Hold what the management sheets share | 3 | 690 | 1784 |
| 2 | `ManagementState.kt` | — | 690 | 1784 |
| 3 | `ManagementPlumbing` and the seven sheets' classes | — | 690 | 1784 |
| 4 | `:app`'s baseline after `ManagementViewModel` (no commit expected) | — | 690 | 1784 |
| 5 | Hold what `SettingsViewModel` publishes and writes | 13 | 703 | 1797 |
| 6 | `SettingsState.kt` | — | 703 | 1797 |
| 7 | `SettingsUpdates`, `SettingsGithub`, `SettingsDeviceLink` | — | 703 | 1797 |
| 8 | `:app`'s baseline after `SettingsViewModel` (no commit expected) | — | 703 | 1797 |
| 9 | Gates, counts, documents, close-out | — | 703 | 1797 |

**Base: 1781 tests.**
- `:core:model` 125, `:core:data` 638, `:core:designsystem` 205, `:widget` 126, `:app` 687.
- That is a `grep` of `@Test` on 17 October. It equals, module by module, what `HANDOVER.md` and `docs/architecture.md` give from #622's run.
- The expected total after this pull request is **1797**: `:app` 703.
- If the first real count is different, shift every figure by the difference.

## Rulings

The design left these open. Each was decided here.

1. **Each view model splits in two move commits, after one test commit, and its baseline is rewritten once.**
   - Decision 5 orders state types first, then collaborators.
   - Decision 2's «two commits» keeps the baseline out of every commit that carries code, and that holds.
2. **`ManagementViewModel` gets a small test before its split too** (`ManagementAcrossSheetsTest`, 3 tests).
   - Decision 5 does not ask for one, because `ManagementViewModelTest` exists.
   - That class holds the page going away. It does not hold three things the split could divide without a compile error:
     - the one `working` flag across sheets;
     - the devices' default argument read at call time;
     - a launch outliving the view model.
   - Decision 1 allows a test «that pins what it is about to move, where nothing tests it today». It is a new file, so `ManagementViewModelTest` stays unchanged, as decision 5 says.
3. **The plumbing's five names are qualified, not aliased.**
   - In a collaborator, `state`, `read(`, `write {`, `finish(` and `note(` become `plumbing.state`, `plumbing.read(` and so on: 63 references in the seven classes.
   - The view model's own nine become `plumbing.state`:
     - `uiState`;
     - `init`'s reset;
     - `recheckRole` (3);
     - `consumeNotice` (2);
     - `dismissWriteFailure` (2).
   - That is 72 in all, a mechanical insertion the move checker undoes with one `--sub`.
   - `return@write` keeps its label, because a lambda's implicit label is the called function's name, qualified or not. The script's pattern skips anything after `@`.
4. **A collaborator's scope parameter is named `viewModelScope`.**
   - Every `viewModelScope.launch` then moves verbatim, and every launch names whose scope it is.
   - The collaborator files do not import `androidx.lifecycle.viewModelScope`. If one ever did, the member would still win over the extension.
   - The view model passes its own `viewModelScope`. No collaborator ever builds a scope (decision 5).
5. **What each class takes.**
   - `ManagementPlumbing(viewModelScope)`.
   - `ClassCardActions(plumbing, repository, session, viewModelScope)`. It is the only sheet that launches outside the plumbing: `searchSchools`, and `leaveDeletedClass`'s `session.leaveActive()`.
   - The other six take `(plumbing, repository)`.
   - `SettingsUpdates(settingsRepository, updateRepository, viewModelScope)`.
   - `SettingsGithub(githubRepository, message, viewModelScope)`.
   - `SettingsDeviceLink(sessionRepository, deviceLinkRepository, viewModelScope)`.
   - Constructor properties keep the view model's names, so the bodies move verbatim.
6. **Delegates.**
   - Each delegate stands where its member stood, in the same order. The section comments (`// -- subjects ---…`) stay in the view model above their delegates.
   - A member that had KDoc leaves `/** @see Owner.name */` behind, as pull request A's delegates did. 12 such lines are in `ManagementViewModel` and 11 in `SettingsViewModel`.
   - Delegates are generated from each one-line signature. Every signature in both files is on one line.
   - `loadDevices` keeps its default in both places:
     - in the view model, for `AdminRows`' `viewModel.loadDevices()`;
     - in `DevicesActions`, for the reload after a revoke.

     Both read `plumbing.state.value.showRevokedDevices` at the call.
   - The view model's delegate for `loadDevices` passes 120 characters, so it is wrapped after `=`, as the original signature was.
7. **Comment edits in a move commit are only these:**
   - each new class's KDoc;
   - one paragraph in each view model's KDoc;
   - `ManagementViewModel`'s `[note]` link, requalified as `[ManagementPlumbing.note]`, the only link the move breaks;
   - the dropped section comment `// -- the plumbing under all of it ---…`, because the file is now that section;
   - each view model's comment above its collaborator properties.
8. **Which of `SettingsViewModel`'s functions move.**
   - A collaborator takes what holds a flow of its own, or writes one the view model shares. Each design-named group:
     - **`SettingsUpdates`**: `releaseSheet`, `checkForUpdates`, `checkForUpdatesAtLaunch`, `showReleaseSheet`, `hideReleaseSheet`, `dismissUpdate`, `enablePrereleases`, and `AUTO_CHECK_INTERVAL_MILLIS`, in a private companion.
     - **`SettingsGithub`**: `filingIssue`, `mutableSubmit`, `translationSubmit`, `submitCorrections`, `acknowledgeSubmission` and `fileIssue`.
     - **`SettingsDeviceLink`**: `deviceLink`, `refreshDeviceLink` and `unlinkDevice`.
   - **What only forwards to a repository stays in the view model**, as the setters, `selectClass`, `leaveClass`, `signOut` and `revealDeveloper` do. That is `signInWithGithub`, `cancelGithubSignIn`, `signOutOfGithub` and `installedVersion`. Moving them would add a second one-liner for each and split nothing.
   - **`refreshNow` stays.** It writes `refreshing`, which the `local` combine reads, and `message`, which stays (decision 5).
   - So only `SettingsGithub` gets a write handle to `message`. It is handed the `MutableStateFlow<SyncMessage?>` itself, so `message.value = SyncMessage.IssueFailed` moves verbatim. The design said «both collaborators»; the sync is not one (see Self-review).
9. **The three flows the view model reads become members of their classes.**
   - `releaseSheet`, `filingIssue` and `deviceLink` go from `private val` in the view model to `val` in an `internal class`.
   - The `remote` combine reads the first two, and `uiState`'s combine reads `deviceLink`.
   - `init`'s reset writes `deviceLink`, and stays in the view model (decision 5).
   - `mutableSubmit` stays `private`, and `translationSubmit` stays `internal`. The view model re-exposes it as `internal val translationSubmit = settingsGithub.translationSubmit`.
10. **`SettingsViewModel.kt`'s `private data class Remote` stays where it is**, beside the combine that builds it.
    - `SettingsState.kt` takes the public state types only, and nothing is widened.
    - `ui.admin.Remote` is a different, public type in another package. The two never meet.
11. **Property names are the class's name in lowerCamel:** `classCardActions` … `requestsActions`, `settingsUpdates`, `settingsGithub` and `settingsDeviceLink`. The plumbing's is `plumbing`.
    - This avoids `link`, which is `uiState`'s lambda parameter.
    - It also avoids `deviceLinks`, which is `ManagementViewModel`'s repository.
12. **Init order is written down where it matters, in a comment above the properties:**
    - **In `ManagementViewModel`:** `plumbing` replaces `state`'s line, then come `uiState` and the seven classes, then `init`.
    - **In `SettingsViewModel`:** the three classes come right after `message` (`settingsGithub` is handed it), and before `local`, `remote` and `uiState`, which read their flows.

    Kotlin builds properties in the order they are written. A *direct* read of a property declared below it in the same class is very likely a compile error (`UNINITIALIZED_VARIABLE`), not a quiet `null` — this is an inference, not yet compiled. Only an *indirect* read, through a function, a lambda run later or a leaked `this`, would see `null`; no case in this plan takes that shape. The order is still written down above the properties, so a reader does not have to work this out again.
13. **The baselines are rewritten with `:app:detektBaseline`, not `detektBaseline`, and are expected not to change.**
    - The two entries that name these files are `TooManyFunctions:ManagementViewModel.kt:ManagementViewModel : ViewModel` and `TooManyFunctions:SettingsViewModel.kt:SettingsViewModel : ViewModel`. Their signatures do not change.
    - The view models keep 35 and 58 functions, over detekt's 11. The largest new class, `ClassCardActions`, has 8.
    - So Tasks 4 and 8 expect no diff and commit nothing. Decision 2 is still met: the baseline is rewritten alone and read, and the reading finds nothing to carry.
    - A finding in a new file is fixed in code (decision 2), never baselined.
14. **Tests per commit, and the gates once.**
    - Each commit runs `:app:testDebugUnitTest`. It executes whenever `:app`'s sources change, because #427 (#610) declared every module's `src` as its input.
    - Task 9 runs `test` as CI does, then forces each module's real test task with `--rerun`. `test --rerun` reaches only `:core:model:test` (`docs/build.md`, «What the JVM tests read, Gradle watches»). After that come both assembles, then `detekt`.
15. **Commit messages.** Each is an English sentence saying what the change makes the project do, with no prefix and no attribution. The body ends with `Refs #273.` on its own line. Write it to `C:/Users/lumen/AppData/Local/Temp/decompose-b/msg.txt` and commit with `git commit -F`.
16. **Counts.** `@Test`'s `grep` gives the plan's numbers. Task 9 reads the real ones from the debug unit-test reports, and the documents take those.

## Global Constraints

- **A split is a move** (decision 1).
  - No name a caller uses is renamed. No parameter, default or return type of a public member changes.
  - No behaviour changes.
  - `private` is widened, and no further than a member of an `internal` class, only for the declarations listed under «What is widened».
  - Every split commit (Tasks 2, 3, 6 and 7) splits one file and touches nothing else.
  - Tests are added only in Tasks 1 and 5, and the baseline changes only in Tasks 4 and 8.
- **The Compose invariant holds trivially.** No composable, sheet or row file is touched. The `*Sheet.kt` files still take `(state, viewModel: ManagementViewModel)`, and the settings rows still take `SettingsViewModel`.
- **Language.**
  - English in code, comments, commit messages and the pull request. Russian only as product text, or as test data where the existing tests already use it (`«Алгебра»`, `«Обычное»`).
  - No `values/` file changes, so no user-facing string is added.
- **`compose-stability.conf` and `:core:model` are not touched.** The state types move within `:app` and keep their fields.
- **Security, absolute.**
  - **Never read, `cat`, `grep`, open, scan or list the contents of `~/.gradle/gradle.properties`.** It holds the release keystore's passwords (#318).
  - The same goes for **any `local.properties`**. The worktree has none, which was checked by its existence alone.
  - **Never read `server/.env`.**
  - **Never walk `~/.gradle`.**
  - Stage files by path. Never use `git add -A`: a JVM crash leaves `hs_err_pid*.log` in `android/`.
  - No keystore path, password, token or chat id goes into a commit, a log or the pull request.
- **This machine (faulty RAM).**
  - **One heavy job at a time.** A Gradle build, a pip install or a pytest run of more than a few files runs alone.
  - **Commit the moment a step is green.**
  - **Every Gradle command takes this machine's form**, written out in full: JDK 21 (the shell's `JAVA_HOME` is 17), the SDK by its F: path (the AppData path is a junction the build cannot resolve), and `--max-workers=2`:
    ```bash
    cd /c/Users/lumen/StudioProjects/lessons-b/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 <task>
    ```
  - Run every Gradle command with the Bash tool's `run_in_background`, and wait for its completion notice.
  - `test --tests` is refused in an Android module. One class runs as `:app:testDebugUnitTest --tests '*Name*'`.
  - Agent threads have their working directory reset between calls, so every command below starts with its own `cd`.
- **After a crash:**
  1. Run `git -C /c/Users/lumen/StudioProjects/lessons-b status`.
  2. If it fails, or if `index` or `COMMIT_EDITMSG` under `C:/Users/lumen/StudioProjects/lessons/.git/worktrees/lessons-b/` is zero-filled:
     - move both aside;
     - run `git reset` (the index only, never `--hard`);
     - run `git fsck`.
  3. Check every file the task had touched for NUL bytes, with Python.
  4. Delete `android/app/build` before the next Gradle run.
  5. Re-read uncommitted work rather than redoing it.
- **Other agents.** Another worktree, `continue-previous-session-991230`, is on `android/calendar-period-panel`. That branch touches none of these files (checked), but run `git status` before touching a file you did not open, and revert nothing you did not write.
- **This plan is read by `server/tests/test_schema_version.py`.** Like every Markdown file under `docs/`, it must never state the schema head as a sentence with a backticked revision in it, or quote a warmup answer.

## What is widened (decision 1)

| Declaration | At `1cc30fe` | After | Why |
| --- | --- | --- | --- |
| `ManagementViewModel.state` (line 208) | `private val` | `val state` of `internal class ManagementPlumbing` | the seven classes and the view model read and write it |
| `read` (746), `write` (762), `finish` (777), `note` (800) | `private fun` members | `fun` members of `internal class ManagementPlumbing` | the seven classes call them; `note` is also called by `searchSchools` |
| `SettingsViewModel.releaseSheet` (140) | `private val` | `val` of `internal class SettingsUpdates` | the view model's `remote` combine reads it |
| `SettingsViewModel.filingIssue` (139) | `private val` | `val` of `internal class SettingsGithub` | the `remote` combine reads it |
| `SettingsViewModel.deviceLink` (173) | `private val` | `val` of `internal class SettingsDeviceLink` | `uiState`'s combine reads it, and `init`'s reset writes it |

**Not widened:**
- **Already public or `internal`.** Nothing changes for:
  - the five management state types and `SettingsUiState`, `DeviceLinkState`, `role` and `effectiveRole`, which are public;
  - `translationSubmit`, which is `internal`.
- **Stays `private` in the view model:**
  - `message`, which is handed to `SettingsGithub` as an argument;
  - `refreshing`, `serverStatus`, `cachedRole`, `local`, `remote`, `STOP_TIMEOUT_MILLIS` and `Remote`;
  - both constructors' properties.
- **Stays `private` in its new class:**
  - `mutableSubmit`;
  - `AUTO_CHECK_INTERVAL_MILLIS`, in `SettingsUpdates`' `private companion object`.
- **The 32 + 11 moved public members** become members of `internal` classes. That is narrower, not wider: the view models still declare each name.

## Review Focus

These are the five likeliest ways a pure move silently changes behaviour here, each with what pins it.

1. **Init order: a flow or a collaborator read before it is built.**
   - **Every misorder named here is a direct read**: `plumbing` read by `uiState` above it, `settingsDeviceLink` read by `uiState`'s combine above it, and `settingsGithub(githubRepository, message, …)` reading `message` above it. Kotlin very likely refuses each of these at compile time (`UNINITIALIZED_VARIABLE`) rather than building and handing back `null` — an inference, not yet compiled (Rulings, 12). None of the three is expected to compile wrong.
   - **The order is still written down** (Rulings, 12), because getting it wrong is a build failure the whole branch would show, not a quiet one a single test would have to catch.
   - **`init`'s two launches stay in the view model, in today's order** (decision 5). Tasks 3 and 7 check by count that `init {` appears once, in each view model, and in none of the eleven new files. The move checker shows `init`'s body unchanged but for one qualifier.
   - Every one of the **51** tests that builds `ManagementViewModel` (`ManagementViewModelTest`'s 18, `SchoolSearchTest`'s 10, `ClassJoinModeScreenTest`'s 7 and Task 1's 3) or `SettingsViewModel` (Task 5's 13) would simply fail to build if the order slipped, rather than failing one at a time.
2. **Scope: a collaborator that launches somewhere other than `viewModelScope`** outlives the screen.
   - **The tests:**
     - `ManagementAcrossSheetsTest`'s `a search still out when the view model is cleared does not land`;
     - `SettingsViewModelTest`'s `work still out when the view model is cleared does not land`.

     Both clear a real `ViewModelStore`, and both were seen failing with the launch moved to `CoroutineScope(Dispatchers.Main)`.
   - **The count:** `CoroutineScope(` appears nowhere in either package's main code, and `viewModelScope.launch` totals 6 and 17 lines, as before the split.
3. **`stateIn`.**
   - **The rule.** Both `stateIn(…WhileSubscribed(STOP_TIMEOUT_MILLIS))` stay in `SettingsViewModel` (decision 5), and `developerRevealed` starts from the mode's own answer.
   - **The test.** `nothing is published until the page is watched, and the developer row starts from the mode` holds both:
     - `uiState.value` is `SettingsUiState()` with no subscriber;
     - `developerRevealed.value` is `true` from the start.

     It was seen failing with `SharingStarted.Eagerly`.
   - **The count.** `stateIn(` appears twice, both in `SettingsViewModel.kt`.
4. **A shared flow written by two pieces.**
   - **`message`** is written by the sync (the view model) and by the issue filing (`SettingsGithub`). These tests hold that both reach `uiState.message`, and that one consumer clears it:
     - `a sync that fails says so on the page until the message is consumed`;
     - `a bug report GitHub refuses writes the same message, …`.
   - **`deviceLink`** is written by `init`'s reset and by `SettingsDeviceLink`. These tests hold it, the second seen failing with the class-switch guard removed:
     - `the Telegram link answers for the class on screen and resets when the class changes`;
     - `an answer about the class switched away from is dropped`.
   - **Management's `state`** is written by all seven classes and by the view model. These tests hold it across classes:
     - `ManagementViewModelTest`'s `a refusal empties what earlier reads had loaded` (`SubjectsActions` then `RequestsActions`);
     - `ManagementAcrossSheetsTest`'s `a write on one sheet is refused while another sheet's write is in flight` (`BellsActions` then `SubjectsActions`), seen failing with the `working` guard removed.
   - **Counted:** each of `MutableStateFlow(ManagementUiState())`, `MutableStateFlow<SyncMessage?>(null)` and `MutableStateFlow<DeviceLinkState>(` is constructed exactly once.
5. **A stale lambda or a value read once instead of at the call.** These tests hold it:
   - **`ManagementAcrossSheetsTest`'s `a device list asked for without a choice follows the switch as it stands`.** It covers both `loadDevices` defaults, the delegate's and the reload's, and was seen failing with the default fixed to `false`.
   - **`SettingsViewModelTest`'s `a check by hand reads the switch at the tap and raises the sheet whatever it finds`.** The pre-release switch is flipped after the view model is built.
   - **`each bug report that is filed hands its own page back and writes no message`.** Two reports, two callbacks: each call's own `onFiled` is the one called.

## Risks

- **The baselines are expected not to change, and that is a prediction.**
  - If `:app:detektBaseline` writes anything, Tasks 4 and 8 read it entry by entry.
  - A pure re-rendering of one of the two `TooManyFunctions` entries is committed alone with its pair named.
  - Anything naming a new file is a new finding, fixed in code in a commit of its own.
- **`ViewModelProvider(store, factory)[Class]` and `ViewModelStore.clear()` were not compiled while planning.**
  - Lifecycle 2.11 keeps both on Android.
  - If the constructor form refuses, `ViewModelProvider.create(store, factory)[SettingsViewModel::class]` is the same call, and the test text changes only there.
- **Unconfined is not `Main.immediate`.**
  - The tests set `Dispatchers.Main` to an `UnconfinedTestDispatcher`, as `ManagementViewModelTest` and `AppIconViewModelTest` do. That is eager like `Main.immediate` inside the constructor, and pins the order of effects within a call.
  - It does not pin threading. Nothing in either split changes a dispatcher.
- **`TranslationMode.clear()` runs in `SettingsViewModelTest`**, because an opened pull request clears the session. It is global Compose snapshot state. `AppCorrectionsTest` already clears it in `@Before` and `@After`, and the test JVM runs classes one at a time.
- **No test composes a settings screen over a real `SettingsViewModel`.** The screens' wiring to the delegates is held by the compile, and by the 15 function references and the calls `git grep` found, which keep their names and parameters.
- **Sub-project 5 rewrites two of these classes later** (design, «Risks»): `LogActions`, when the log pages by a token, and `ClassCardActions`, when the search moves to `ListSchools`. That is why both stay pure moves here.
- **Line endings.** The working tree is CRLF (`core.autocrlf=true`), and the scripts write LF. Git normalises on commit, and its warning is harmless.
- **HANDOVER's order.** Section 7 says the owner ordered the 152-ФЗ work (epic #438, milestone 12) to come after #451 and before sub-project 4's B and C.
  - On 17 October 2026 the owner said, in Russian, «продолжай с PR B подпроекта 4» («continue with sub-project 4's PR B»), which puts B ahead of that order for this pull request.
  - Task 0, Step 1 records that word. Task 9, Step 15's merge is conditional on it: without it recorded, the pull request stays open and the owner is asked, rather than merging under the standing instruction.
- **The machine.** A crash mid-commit has zero-filled the index before. The recovery is in «Global Constraints».

## What was verified while writing this plan (17 October 2026, worktree `lessons-b`, `1cc30fe`)

**The design's survey of `521c7a5` still holds.**
- `git log 521c7a5..1cc30fe` over both view models and `android/app/detekt-baseline.xml` is empty.
- `ManagementViewModel.kt` is 821 lines, `SettingsViewModel.kt` 716, and `ManagementViewModelTest.kt` 481.
- Each view model has one baseline entry, `TooManyFunctions`.
- `:app`'s baseline holds 184 entries. The five baselines hold 493: `MagicNumber` 218, `ReturnCount` 81, `MaxLineLength` 65, as `docs/build.md` says.
- No remote branch (`dev`, `pending/recovered-designs`) and no local branch changes either package's sources, tests or `:app`'s baseline, relative to its merge base with `main`.
- **#609** changed `FeelRows.kt` and other settings rows, but neither view model, and no interface they take.
- **#622's GitHub plumbing** (`GithubProject`, `BuildProvenance.project`) is used by `AboutCard`, `AboutProvenance` and `SupportSheets`, never by `SettingsViewModel`.
  - `SupportSheets.kt:107` calls `viewModel.fileIssue(draft) { url -> … }`, and the delegate keeps that shape.
  - None of the eight interfaces the two view models take changed since `521c7a5`: `ManageRepository`, `DeviceLinkRepository`, `SessionRepository`, `SettingsRepository`, `TimetableRepository`, `UpdateRepository`, `GithubRepository` and `DeveloperMode`.

**`ManagementViewModel.kt` at `1cc30fe`.**

| Lines | What | Goes to |
| --- | --- | --- |
| 37–48, 50–60, 62–102, 104–140, 142–185 | `Remote`, `PendingImport`, `ManagementNotice`, `SchoolSearch`, `ManagementUiState`, with KDoc | `ManagementState.kt` (Task 2; 149 lines) |
| 187–201 | the class KDoc | stays, with one paragraph and one link requalified |
| 208–209 | `state`, `uiState` | `state` → plumbing; `uiState` stays |
| 211–243 | `init` | stays, one line qualified |
| 247–399 | `loadClass`, `saveClass`, `setJoinMode`, `searchSchools`, `showSchoolPage`, `clearSchoolSearch`, `deleteClass`, `leaveDeletedClass` (8; 6 with KDoc) | `ClassCardActions` (137 lines, 16 references) |
| 404–450 | `loadSubjects`, `addSubject`, `saveSubject`, `deleteSubject` (4; 1 with KDoc) | `SubjectsActions` (41 lines, 8) |
| 455–518 | `loadBells`, `addBellSchedule`, `renameBellSchedule`, `makeBellScheduleDefault`, `saveBellPeriods`, `deleteBellSchedule` (6; none) | `BellsActions` (54 lines, 12) |
| 523–603 | `loadTimetable`, `importTimetable`, `replacePendingImport`, `cancelPendingImport` (4; 4) | `TimetableActions` (75 lines, 8) |
| 608–646 | `loadDevices`, `revokeDevice`, `unlinkDevice` (3; none) | `DevicesActions` (36 lines, 7) |
| 651–669 | `loadLog`, `showNextLogPage`, `showPreviousLogPage` (3; 1) | `LogActions` (16 lines, 4) |
| 674–705 | `loadStats`, `loadRequests`, `approveRequest`, `declineRequest` (4; none) | `RequestsActions` (26 lines, 8) |
| 710–741 | `recheckRole`, `consumeNotice`, `dismissWriteFailure` | stay, 7 references qualified |
| 743–808 | the section comment, then `read`, `write`, `finish`, `note` with KDoc | `ManagementPlumbing` (comment dropped) |
| 810–820 | `companion object { val Factory }` | stays |

The «lines» counts are each member's KDoc and body, without the blank lines between. The references are the qualifier's 63 in the seven classes.

**`SettingsViewModel.kt` at `1cc30fe`.**

| Lines | What | Goes to |
| --- | --- | --- |
| 57–97 | `SettingsUiState` with KDoc (41 lines) | `SettingsState.kt` |
| 650–716 | `DeviceLinkState`, `role`, `effectiveRole` (67 lines) | `SettingsState.kt` |
| 641–648 | `private data class Remote` | stays |
| 119–135 | `developerRevealed` (its `stateIn`), `revealDeveloper` | stay |
| 137–138 | `refreshing`, `message` | stay |
| 139, 140 | `filingIssue`, `releaseSheet` | `SettingsGithub`, `SettingsUpdates` |
| 142–171 | `installedVersion`, `local`, `remote` | stay; `remote` reads two flows through their classes |
| 173 | `deviceLink` | `SettingsDeviceLink` |
| 175–214 | `serverStatus`, `cachedRole`, `uiState` (its `stateIn`) | stay; `uiState` reads `deviceLink` through its class |
| 216–247 | `init`: the server-status launch, then the class-switch reset | stay, in this order; one line qualified |
| 249–292 | `refreshDeviceLink`, `unlinkDevice` (both with KDoc) | `SettingsDeviceLink` |
| 294–482 | 37 setters, `refreshNow`, `selectClass`, `leaveClass`, `signOut`, `consumeMessage` | stay |
| 484–548 | `checkForUpdates`, `checkForUpdatesAtLaunch`, `showReleaseSheet`, `hideReleaseSheet`, `dismissUpdate`, `enablePrereleases` (5 with KDoc) | `SettingsUpdates` |
| 550–586 | `mutableSubmit`, `translationSubmit`, `submitCorrections`, `acknowledgeSubmission` | `SettingsGithub` |
| 588–594 | `signInWithGithub`, `cancelGithubSignIn`, `signOutOfGithub` | stay (Rulings, 8) |
| 596–613 | `fileIssue` | `SettingsGithub` |
| 615–617 | `private fun update` | stays |
| 619–638 | companion: `STOP_TIMEOUT_MILLIS`, `AUTO_CHECK_INTERVAL_MILLIS` (622–623), `Factory` | `AUTO_CHECK…` → `SettingsUpdates`' private companion |

**Who reaches them.**
- **`ManagementViewModel` is built by:**
  - `AdminRows.kt:131`, through its factory;
  - `ManagementViewModelTest` (18 tests), `SchoolSearchTest` (10) and `ClassJoinModeScreenTest` (7, Robolectric), through the constructor.

  Nine files take it as `viewModel: ManagementViewModel`: `AdminRows` and eight `*Sheet.kt`.
- **Fifteen function references** reach it, all keeping their names and parameters:
  - `AdminRows`: `recheckRole`;
  - `BellsSheet`: `loadBells`;
  - `ClassCardSheet`: `loadClass`, `searchSchools`, `showSchoolPage`, `clearSchoolSearch`, `deleteClass`;
  - `DevicesSheet`: `loadDevices` as `(Boolean) -> Unit`;
  - `LogSheet`: `showPreviousLogPage`, `showNextLogPage`;
  - `RequestsSheet`: `loadRequests`;
  - `StatsSheet`: `loadStats`;
  - `SubjectsSheet`: `loadSubjects`;
  - `TimetableSheet`: `loadTimetable`, `cancelPendingImport`.
- **`SettingsViewModel` is built** only through its factory: at `HomeShell.kt:204`, `OnboardingScreen.kt:119` and `SettingsScreen.kt:76` and `:145`. No test builds it. Twelve row and sheet files take it.
- **Its state types are reached by:**
  - `OnboardingScreen.kt:75` and `SummaryPage.kt:31`, which import `SettingsUiState` by name;
  - `HomeShell.kt:82`, which imports `effectiveRole`;
  - `EffectiveRoleTest`, `ClassRowsScreenTest` and `TelegramLinkRowsTest`, from the same package.

  None of them names the file.
- **No test reads either file by path.** `UpstreamNameTest`'s allow-list names `GithubProject.kt` and `UpdateSheet.kt` only.
- **The source-walking guards** (`DeviceClockTest`, `CorrectionReachTest`, `UnusedStringTest` and `RowTitleSentenceTest`) match nothing in the moved code.
  - `checkForUpdatesAtLaunch`'s `System.currentTimeMillis()` is not one of `DeviceClockTest`'s calls.

**Fakes.**
- **These exist:**
  - `FakeManageRepository`, `FakeDeviceLinkRepository` and `FakeSessionRepository`, all `internal`, in `ui/admin`'s tests;
  - `FakeSettings`, `internal`, in `ui/onboarding/OnboardingTestSupport.kt`.
- **`FakeDeviceLinkRepository` cannot hold a refresh or record a remembered role.** So `SettingsViewModelTest` has its own, `Links`.
- **New ones are needed** for the updates, GitHub, the developer mode and the timetable repositories. No `:app` test has a reusable one; `JoinRig`'s timetable fake is private. They are private classes in the test file.

**The new names are free:** none of the eleven class names, `ManagementState`, `SettingsState`, `ManagementAcrossSheetsTest` or `SettingsViewModelTest` appears in `android/`.

**The dry run** (condensed logic, nothing written):
- **Task 2:** prints 149 lines moved. `ManagementViewModel.kt` 665 lines, `ManagementState.kt` 165.
- **Task 3:** 72 references qualified. Before the KDoc and comment lines are added, `ManagementViewModel.kt` is 237 lines, longest 112. Each class's body is 18–145 lines, longest 114.
- **Task 6:** 41 + 67 lines moved. `SettingsViewModel.kt` 603 lines.
- **Task 7:** `SettingsViewModel.kt` 463 lines before its comment and KDoc paragraph, longest 112, 58 functions. The class bodies are 44, 65 and 54 lines.
- **No line reaches 120.** The qualifier's longest lines are `loadSubjects` and `loadRequests`, at 114.

**The environment.**
- `C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot` and `F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk` exist.
- `android/local.properties` does not exist; its existence was tested, nothing read.
- **`server/.venv` does not exist in `lessons-b`.** Task 0 makes one.
- `python` is 3.13.15, and `git` 2.46.
- Pull request A's tools are still in `C:/Users/lumen/AppData/Local/Temp/decompose-a/`. `count.sh` there points at `lessons-live-gate`, so Task 0 writes this worktree's own.
- The worktree's git directory is `C:/Users/lumen/StudioProjects/lessons/.git/worktrees/lessons-b`.

---

### Task 0: Commit this plan, write the two tools, and make the server's venv

**Files:**
- Commit: `docs/specs/2026-10-18-android-decomposition-b-plan.md`.
- Create, outside the repository: `C:/Users/lumen/AppData/Local/Temp/decompose-b/moved.py` and `.../count.sh`.
- Create, git-ignored: `server/.venv`.

- [ ] **Step 1: Check where you are, and the order.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b && git status --short && git rev-parse --abbrev-ref HEAD && git log --oneline -1
  ```
  Expected:
  - the only line of `git status` is `?? docs/specs/2026-10-18-android-decomposition-b-plan.md`;
  - the branch is `android/decomposition-b`;
  - the last commit is `1cc30fe Merge pull request #622 from lumenpearson/android/report-target-469`.

  Then read `HANDOVER.md`'s section 7, «Next for the programme». It puts epic #438 before this pull request. The owner's word overrides that order: on 17 October 2026 the owner said, in Russian, «продолжай с PR B подпроекта 4» («continue with sub-project 4's PR B»), given directly for this session. Record that B goes ahead of epic #438 by that word, in the close-out (Task 9); it is also what Task 9, Step 15's merge depends on.

- [ ] **Step 2: The move checker, pull request A's, byte for byte.**
  ```bash
  mkdir -p /c/Users/lumen/AppData/Local/Temp/decompose-b && cp /c/Users/lumen/AppData/Local/Temp/decompose-a/moved.py /c/Users/lumen/AppData/Local/Temp/decompose-b/moved.py
  ```
  If `decompose-a` is gone, write the file from pull request A's plan, Task 0, Step 2. Its usage:
  ```
  python moved.py <rev> <old path> <path now>... [--sub REGEX=REPLACEMENT]...
  ```
  It prints each stripped line only one side has: `-` for the old side, `+` for the new.

- [ ] **Step 3: This worktree's counter.**
  ```bash
  cat > /c/Users/lumen/AppData/Local/Temp/decompose-b/count.sh <<'EOF'
  #!/usr/bin/env bash
  # The JUnit reports of the last run, per module and in all, read from each
  # Android module's debug variant: the project's counts are of tests, not runs.
  cd /c/Users/lumen/StudioProjects/lessons-b/android || exit 1
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
  cd /c/Users/lumen/StudioProjects/lessons-b && A=android/app/src/main/kotlin/com/lumenpearson/lessons/ui/admin/ManagementViewModel.kt && python /c/Users/lumen/AppData/Local/Temp/decompose-b/moved.py HEAD $A $A; echo "exit $?"
  ```
  Expected: `exit 0`, with nothing printed before it.

- [ ] **Step 5: The server's venv in this worktree's own `server/`.**
  - A borrowed one tests the tree it was made in, and `conftest.py` refuses it (#312).
  - Run it alone:
    ```bash
    cd /c/Users/lumen/StudioProjects/lessons-b/server && python -m venv .venv && .venv/Scripts/pip install -r ../requirements.txt -e ".[dev]"
    ```
  - Expected: it ends with `Successfully installed …`.

- [ ] **Step 6: The plan passes the test that reads every document.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b/server && .venv/Scripts/pytest.exe -q -p no:xdist tests/test_schema_version.py
  ```
  Expected: all passed, none failed.

- [ ] **Step 7: Commit** the plan alone.
  ```
  Plan the second Android split: ManagementViewModel and SettingsViewModel into collaborators

  Pull request B of sub-project 4, in the design's order (decision 8): a
  test of what the management sheets share, the management state types into
  a file of their own, the seven sheets' work into seven classes over one
  plumbing; then a characterization test of SettingsViewModel, which nothing
  tested, its state types, and its update check, GitHub work and Telegram
  link into three classes. Both view models keep their constructors and
  every public name as one-line delegates, so no sheet, screen or existing
  test changes. The baselines are rewritten after each split and expected
  not to move.

  Drafted read-only against 1cc30fe; the counts in it are a grep's or an
  in-memory dry run's, and the runs are the truth.

  Refs #273.
  ```

---

### Task 1: Hold what the management sheets share before their work moves out

**Files:** Create `android/app/src/test/kotlin/com/lumenpearson/lessons/ui/admin/ManagementAcrossSheetsTest.kt`.

- [ ] **Step 1: Write the test.**

  ```kotlin
  package com.lumenpearson.lessons.ui.admin

  import androidx.lifecycle.ViewModel
  import androidx.lifecycle.ViewModelProvider
  import androidx.lifecycle.ViewModelStore
  import com.lumenpearson.lessons.core.data.repository.BellSchedule
  import com.lumenpearson.lessons.core.data.repository.BellsWritten
  import com.lumenpearson.lessons.core.data.repository.ClassRole
  import com.lumenpearson.lessons.core.data.repository.ManagedDevice
  import com.lumenpearson.lessons.core.data.repository.ManagedSubject
  import com.lumenpearson.lessons.core.data.repository.SchoolPage
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.test.UnconfinedTestDispatcher
  import kotlinx.coroutines.test.resetMain
  import kotlinx.coroutines.test.setMain
  import org.junit.After
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertTrue
  import org.junit.Before
  import org.junit.Test

  /**
   * What the eight sheets share through the one view model, beyond the page
   * being taken away from all of them at once, which `ManagementViewModelTest`
   * holds.
   *
   * Written before each sheet's work moved into a class of its own, and run on
   * the code before the move, so that the move is proved by something: one
   * write at a time across every sheet, the devices' switch read when a list is
   * asked for rather than when anything was built, and work that stops with
   * the view model instead of outliving it.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  class ManagementAcrossSheetsTest {

      private val repository = FakeManageRepository()

      @Before
      fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

      @After
      fun tearDown() = Dispatchers.resetMain()

      private fun model() =
          ManagementViewModel(repository, FakeDeviceLinkRepository(ClassRole.ADMIN), FakeSessionRepository())

      /**
       * The page's one write flag belongs to the page, not to a sheet: two
       * writes on the same class at once are what it exists to stop, from
       * whichever two sheets they come.
       */
      @Test
      fun `a write on one sheet is refused while another sheet's write is in flight`() {
          val model = model()
          model.saveBellPeriods(3L, emptyList())

          model.deleteSubject(ManagedSubject(1L, "Алгебра", null, null, null))
          assertEquals(1, repository.pending.size)
          assertTrue(model.uiState.value.working)

          repository.answer(0, Result.success(BellsWritten(BELLS, silencedLessons = 0)))
          assertFalse(model.uiState.value.working)
      }

      /**
       * A list asked for without saying which, by the sheet opening or by a
       * revoke reloading it, asks for what the switch shows now.
       */
      @Test
      fun `a device list asked for without a choice follows the switch as it stands`() {
          val model = model()
          model.loadDevices(includeRevoked = true)
          repository.answer(0, Result.success(emptyList<ManagedDevice>()))

          model.loadDevices()
          repository.answer(1, Result.success(emptyList<ManagedDevice>()))
          model.revokeDevice(device(1L))
          repository.answer(2, Result.success(device(1L)))

          assertEquals(listOf(true, true, true), repository.deviceCalls)
      }

      /**
       * Work the view model started stops with it: a search still out when the
       * screen's store is cleared lands nowhere.
       */
      @Test
      fun `a search still out when the view model is cleared does not land`() {
          val store = ViewModelStore()
          val model = ViewModelProvider(store, factoryOf(model()))[ManagementViewModel::class.java]
          model.searchSchools("лицей")

          store.clear()
          repository.answer(0, Result.success(
              SchoolPage(emptyList(), page = 1, pages = 1, total = 0, truncated = false),
          ))

          assertTrue(model.uiState.value.schoolSearch.searching)
          assertFalse(model.uiState.value.schoolSearch.searched)
      }

      private fun device(id: Long) = ManagedDevice(
          id = id,
          deviceName = "Pixel $id",
          linked = true,
          owner = "@anna",
          role = ClassRole.EDITOR,
          revoked = false,
          createdAt = null,
          lastSeenAt = null,
          linkedAt = null,
      )

      private companion object {
          val BELLS = BellSchedule(id = 3L, name = "Обычное", isDefault = true, periods = emptyList())
      }
  }

  /** Hands [model] to a store, the way the activity's provider would. */
  private fun factoryOf(model: ViewModel) = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
  }
  ```

- [ ] **Step 2: Run the class.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 :app:testDebugUnitTest --tests '*ManagementAcrossSheetsTest*'
  ```
  Expected: `BUILD SUCCESSFUL`. The report `app/build/test-results/testDebugUnitTest/TEST-com.lumenpearson.lessons.ui.admin.ManagementAcrossSheetsTest.xml` says `tests="3"`, `failures="0"`, `errors="0"`.

- [ ] **Step 3: See each fail, in one run.** Make three temporary edits in `ManagementViewModel.kt`:
  - **M1.** In `write`, change `if (state.value.gone != null || state.value.working) return` to `if (state.value.gone != null) return`.
  - **M2.** Change `fun loadDevices(includeRevoked: Boolean = state.value.showRevokedDevices) =` to `fun loadDevices(includeRevoked: Boolean = false) =`.
  - **M3.** In `searchSchools`, change `viewModelScope.launch {` to `kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {`.

  Run Step 2's command. Expected: `BUILD FAILED`, with exactly three failures:
  - the cross-sheet write fails at `expected:<1> but was:<2>`;
  - the switch fails at `[true, true, true]` against `[true, false, false]`;
  - the cleared search fails at `assertTrue(… searching)`.

  Restore with `git -C /c/Users/lumen/StudioProjects/lessons-b checkout -- android/app/src/main/kotlin/com/lumenpearson/lessons/ui/admin/ManagementViewModel.kt`.

- [ ] **Step 4: The module.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 :app:testDebugUnitTest
  ```
  Expected: `BUILD SUCCESSFUL`. Then `bash C:/Users/lumen/AppData/Local/Temp/decompose-b/count.sh` prints `app: tests=690 failures=0 errors=0 skipped=0`.

- [ ] **Step 5: Commit** the test file.
  ```
  Hold what the management sheets share before their work moves out of the view model

  ManagementViewModelTest holds the page going away under any sheet. Three
  things the sheets share were held by nothing, and a split into one class
  per sheet could divide each without a compile error: the page's one write
  flag, which refuses a second write from any sheet while one is in flight;
  the devices' switch, which a list asked for without a choice (the sheet
  opening, a revoke reloading it) reads at the call; and work that stops with
  the view model, which a search still out when its store is cleared shows.
  ManagementAcrossSheetsTest holds the three, through the constructor and a
  real ViewModelStore. Each was seen failing: without the write guard, with
  the default fixed to false, and with the search launched in a scope of its
  own.

  Not covered: the other sheets' reloads after a write; the existing tests
  and this one reach every sheet but not every member.

  Refs #273.
  ```

---

### Task 2: Give the management page's state types a file of their own

**Files:**
- Create `android/app/src/main/kotlin/com/lumenpearson/lessons/ui/admin/ManagementState.kt`.
- Modify `.../ui/admin/ManagementViewModel.kt`.

**Moves, verbatim with their KDoc:** `Remote` (37–48), `PendingImport` (50–60), `ManagementNotice` (62–102), `SchoolSearch` (104–140) and `ManagementUiState` (142–185), 149 lines.

**Widens:** nothing. All five are public.

- [ ] **Step 1: Write and run the script** (not committed).
  ```bash
  cat > /c/Users/lumen/AppData/Local/Temp/decompose-b/state_management.py <<'EOF'
  """Task 2: the management page's five state types move to ManagementState.kt.
  Run from the worktree's root."""
  import sys
  from pathlib import Path

  sys.stdout.reconfigure(encoding="utf-8")
  D = Path("android/app/src/main/kotlin/com/lumenpearson/lessons/ui/admin")
  source = D / "ManagementViewModel.kt"
  lines = source.read_text(encoding="utf-8").splitlines()


  def kdoc_above(i):
      """The index of the `/**` that opens the KDoc of the declaration at i."""
      while lines[i] != "/**":
          i -= 1
      return i


  first = kdoc_above(lines.index("data class Remote<T>("))
  last = kdoc_above(lines.index("class ManagementViewModel(")) - 1  # the blank line above its KDoc
  assert lines[first - 1] == "" and lines[last] == ""
  types = lines[first:last]
  del lines[first : last + 1]

  moved = ["AuditPage", "ClassStats", "ImportConflict", "ManagedClass", "School", "TimetableExport"]
  shared = ["AccessRequest", "BellSchedule", "ClassJoinMode", "ClassRole", "ManageFailure",
            "ManagedDevice", "ManagedSubject"]
  for name in moved:
      lines.remove(f"import com.lumenpearson.lessons.core.data.repository.{name}")
  source.write_text("\n".join(lines) + "\n", encoding="utf-8")

  head = ["package com.lumenpearson.lessons.ui.admin", ""]
  head += [f"import com.lumenpearson.lessons.core.data.repository.{n}" for n in sorted(moved + shared)]
  (D / "ManagementState.kt").write_text("\n".join(head + [""] + types) + "\n", encoding="utf-8")
  print(f"{len(types)} lines of state types moved, {len(moved)} imports with them")
  EOF
  cd /c/Users/lumen/StudioProjects/lessons-b && python /c/Users/lumen/AppData/Local/Temp/decompose-b/state_management.py
  ```
  - Expected: `149 lines of state types moved, 6 imports with them`.
  - `wc -l` gives `ManagementViewModel.kt` 665 and `ManagementState.kt` 165.
  - On anything else, restore `ManagementViewModel.kt` from git, delete `ManagementState.kt`, and find out why.

- [ ] **Step 2: Check it was a move.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b && A=android/app/src/main/kotlin/com/lumenpearson/lessons/ui/admin && python /c/Users/lumen/AppData/Local/Temp/decompose-b/moved.py HEAD $A/ManagementViewModel.kt $A/ManagementViewModel.kt $A/ManagementState.kt
  ```
  Expected:
  - no `-` line;
  - exactly these `+` lines:
    - `+ 1x package com.lumenpearson.lessons.ui.admin`;
    - `+ 1x import com.lumenpearson.lessons.core.data.repository.X`, for each `X` of `AccessRequest`, `BellSchedule`, `ClassJoinMode`, `ClassRole`, `ManageFailure`, `ManagedDevice` and `ManagedSubject`. Both files now need these.

- [ ] **Step 3: The module.** Run Task 1's Step 4 command. Expected: `BUILD SUCCESSFUL`, and `count.sh` prints `app: tests=690 failures=0`.

- [ ] **Step 4: Commit** the two files.
  ```
  Give the management page's state types a file of their own

  Remote, PendingImport, ManagementNotice, SchoolSearch and ManagementUiState
  move verbatim with their KDoc to ManagementState.kt, the first of the two
  steps the design gives ManagementViewModel (decision 5). All five were
  public, so nothing is widened, and every caller (the sheets, the forms and
  the three test classes) reaches them by package and changes nothing. Six
  imports go with them.

  detekt's baseline is rewritten after the next commit, in a commit of its
  own if it moves at all.

  Not covered: detekt is not run in this commit, and nothing here runs on a
  device.

  Refs #273.
  ```

---

### Task 3: Split `ManagementViewModel`'s sheets into seven classes over one plumbing

**Files:**
- Create, in `android/app/src/main/kotlin/com/lumenpearson/lessons/ui/admin/`: `ManagementPlumbing.kt`, `ClassCardActions.kt`, `SubjectsActions.kt`, `BellsActions.kt`, `TimetableActions.kt`, `DevicesActions.kt`, `LogActions.kt` and `RequestsActions.kt`.
- Modify `ManagementViewModel.kt`.

**Moves**, verbatim with their KDoc, but for the qualifier (Rulings, 3). The groups are in the «What was verified» table.
- **The plumbing** takes `state` and `read`, `write`, `finish` and `note`. `private fun` becomes `fun` on their four signature lines.
- **The view model keeps:**
  - `uiState`;
  - `init`;
  - the 32 delegates under their section comments;
  - `recheckRole`, `consumeNotice` and `dismissWriteFailure`;
  - the factory.

**Widens:** `state` and the four functions (the table above).

- [ ] **Step 1: Write and run the script** (not committed).
  ```bash
  cat > /c/Users/lumen/AppData/Local/Temp/decompose-b/split_management.py <<'EOF'
  """Task 3: ManagementViewModel's sheets move into seven classes over
  ManagementPlumbing, and the view model hands each call on in one line.
  Run from the worktree's root, after Task 2. Prints what it moved."""
  import re
  import sys
  from pathlib import Path

  sys.stdout.reconfigure(encoding="utf-8")
  D = Path("android/app/src/main/kotlin/com/lumenpearson/lessons/ui/admin")
  source = D / "ManagementViewModel.kt"
  lines = source.read_text(encoding="utf-8").splitlines()

  GROUPS = {
      "ClassCardActions": ["loadClass", "saveClass", "setJoinMode", "searchSchools", "showSchoolPage",
                           "clearSchoolSearch", "deleteClass", "leaveDeletedClass"],
      "SubjectsActions": ["loadSubjects", "addSubject", "saveSubject", "deleteSubject"],
      "BellsActions": ["loadBells", "addBellSchedule", "renameBellSchedule", "makeBellScheduleDefault",
                       "saveBellPeriods", "deleteBellSchedule"],
      "TimetableActions": ["loadTimetable", "importTimetable", "replacePendingImport", "cancelPendingImport"],
      "DevicesActions": ["loadDevices", "revokeDevice", "unlinkDevice"],
      "LogActions": ["loadLog", "showNextLogPage", "showPreviousLogPage"],
      "RequestsActions": ["loadStats", "loadRequests", "approveRequest", "declineRequest"],
  }
  PLUMBING = ["read", "write", "finish", "note"]

  # The plumbing's five names where code uses them: not in a comment, not a
  # label (`return@write`), and not a member of something else.
  SHARED = re.compile(r"(?<![\w.@])(state|read|write|finish|note)\b(?=\s*[.({])")
  COMMENT = re.compile(r"^\s*(//|\*|/\*)")
  qualified = 0


  def qualify(line):
      global qualified
      if COMMENT.match(line):
          return line
      line, n = SHARED.subn(r"plumbing.\1", line)
      qualified += n
      return line


  def member(name):
      """(start, signature, end) of a member with its KDoc; end is the blank line after it."""
      signature = next(i for i, l in enumerate(lines) if re.match(rf"    (private )?fun (<T> )?{name}\(", l))
      start = signature
      if lines[start - 1].strip().endswith("*/"):
          while not lines[start - 1].strip().startswith("/**"):
              start -= 1
          start -= 1
      end = signature + 1
      while not (lines[end] == "" and re.match(r"    \S", lines[end + 1])):
          end += 1
      return start, signature, end


  def delegate(signature, owner, kdoc):
      """The member the view model keeps: the same signature, handing the call on."""
      name = re.match(r"    fun (\w+)\(", signature).group(1)
      opened = signature.index("(")
      depth, at = 0, opened
      while True:
          depth += signature[at] == "("
          depth -= signature[at] == ")"
          if depth == 0:
              break
          at += 1
      declared = signature[opened + 1 : at]
      passed = ", ".join(p.split(":")[0].strip() for p in declared.split(", ") if p)
      field = owner[0].lower() + owner[1:]
      out = [f"    /** @see {owner}.{name} */"] if kdoc else []
      one = f"    fun {name}({declared}) = {field}.{name}({passed})"
      if len(one) <= 120:
          return out + [one]
      return out + [f"    fun {name}({declared}) =", f"        {field}.{name}({passed})"]


  bodies = {}
  for owner, names in GROUPS.items():
      for name in names:
          start, signature, end = member(name)
          body = [qualify(l) for l in lines[start:end]]
          bodies.setdefault(owner, []).append(body)
          lines[start:end] = delegate(body[signature - start], owner, start < signature)

  plumbing = []
  for name in PLUMBING:
      start, signature, end = member(name)
      body = lines[start:end]
      body[signature - start] = body[signature - start].replace("    private fun ", "    fun ", 1)
      plumbing.append(body)
      del lines[start : end + 1]
  section = lines.index("    // -- the plumbing under all of it ---------------------------------------")
  assert lines[section + 1] == "" and lines[section + 2] == "    companion object {"
  del lines[section : section + 2]

  at = lines.index("    private val state = MutableStateFlow(ManagementUiState())")
  lines[at : at + 1] = [
      "    /** Built first, so the seven classes after it always take one that exists. */",
      "    private val plumbing = ManagementPlumbing(viewModelScope)",
  ]
  assert lines[at + 2] == "    val uiState: StateFlow<ManagementUiState> = state.asStateFlow()"
  lines[at + 3 : at + 3] = [
      "",
      "    // Each sheet's work, over the one plumbing: built before init, so that",
      "    // nothing the view model runs can reach one that is not there yet.",
      "    private val classCardActions = ClassCardActions(plumbing, repository, session, viewModelScope)",
      "    private val subjectsActions = SubjectsActions(plumbing, repository)",
      "    private val bellsActions = BellsActions(plumbing, repository)",
      "    private val timetableActions = TimetableActions(plumbing, repository)",
      "    private val devicesActions = DevicesActions(plumbing, repository)",
      "    private val logActions = LogActions(plumbing, repository)",
      "    private val requestsActions = RequestsActions(plumbing, repository)",
  ]
  lines = [qualify(l) for l in lines]

  note = lines.index(" * call happened to be next. [note] is the single place that reads it, and it")
  lines[note] = " * call happened to be next. [ManagementPlumbing.note] is the single place that reads it, and it"
  caches = lines.index(" * Nothing here caches. Every sheet loads when it opens; see")
  lines[caches:caches] = [
      " * Each sheet's work is a class of its own over one [ManagementPlumbing]:",
      " * [ClassCardActions], [SubjectsActions], [BellsActions], [TimetableActions],",
      " * [DevicesActions], [LogActions] and [RequestsActions]. Every public method",
      " * here hands its call on in one line, so the sheets and the tests take this",
      " * class as they did.",
      " *",
  ]
  lines.remove("import com.lumenpearson.lessons.core.data.repository.ManageFailure")
  lines.remove("import kotlinx.coroutines.flow.MutableStateFlow")
  source.write_text("\n".join(lines) + "\n", encoding="utf-8")

  REPO = "import com.lumenpearson.lessons.core.data.repository."
  UPDATE = "import kotlinx.coroutines.flow.update"
  IMPORTS = {
      "ClassCardActions": [REPO + n for n in ["ClassEdit", "ClassJoinMode", "ManageFailure", "ManageRepository",
                                               "SessionRepository"]]
      + ["import kotlinx.coroutines.CoroutineScope", UPDATE, "import kotlinx.coroutines.launch"],
      "SubjectsActions": [REPO + n for n in ["ManageRepository", "ManagedSubject", "SubjectForm"]] + [UPDATE],
      "BellsActions": [REPO + n for n in ["BellPeriod", "BellSchedule", "ManageRepository"]] + [UPDATE],
      "TimetableActions": [REPO + "ManageRepository", UPDATE],
      "DevicesActions": [REPO + n for n in ["ManageRepository", "ManagedDevice"]] + [UPDATE],
      "LogActions": [REPO + "ManageRepository"],
      "RequestsActions": [REPO + n for n in ["AccessRequest", "ClassRole", "ManageRepository"]] + [UPDATE],
  }
  KDOC = {
      "ClassCardActions": [
          "What the class card's sheet does: the card and its join mode, the school",
          "directory, and deleting the class and leaving it afterwards.",
          "",
          "Holds no state of its own: everything it reads and writes is",
          "[ManagementPlumbing.state], so a refusal met here takes the page away from",
          "every sheet at once. Its own two launches, the search and the leaving, run",
          "in [viewModelScope], the view model's, which is why the parameter carries",
          "that name.",
      ],
      "SubjectsActions": ["What the subjects sheet does: the list, and adding, editing and deleting one."],
      "BellsActions": [
          "What the bells sheet does: the schedules, and adding, renaming, making the",
          "default, rewriting and deleting one.",
      ],
      "TimetableActions": [
          "What the import sheet does: the export it opens on, an import, and the",
          "consent an import that would overwrite something waits for.",
      ],
      "DevicesActions": ["What the devices sheet does: the list under its switch, and revoking or unlinking a phone."],
      "LogActions": ["What the log sheet does: one page of the audit log at a time, forward and back."],
      "RequestsActions": [
          "What the statistics and requests sheets do: the class's numbers, the access",
          "requests, and approving or declining one.",
      ],
  }
  for owner, groups in bodies.items():
      kdoc = KDOC[owner]
      if owner != "ClassCardActions":
          kdoc = kdoc + ["", "Holds no state of its own; see [ManagementPlumbing]."]
      text = ["package com.lumenpearson.lessons.ui.admin", "", *IMPORTS[owner], "", "/**"]
      text += [f" * {l}" if l else " *" for l in kdoc] + [" */", f"internal class {owner}("]
      text += ["    private val plumbing: ManagementPlumbing,", "    private val repository: ManageRepository,"]
      if owner == "ClassCardActions":
          text += ["    private val session: SessionRepository,", "    private val viewModelScope: CoroutineScope,"]
      text += [") {"]
      for body in groups:
          text += [""] + body
      (D / f"{owner}.kt").write_text("\n".join(text + ["}", ""]), encoding="utf-8")

  text = [
      "package com.lumenpearson.lessons.ui.admin",
      "",
      "import com.lumenpearson.lessons.core.data.repository.ManageFailure",
      "import kotlinx.coroutines.CoroutineScope",
      "import kotlinx.coroutines.flow.MutableStateFlow",
      "import kotlinx.coroutines.flow.update",
      "import kotlinx.coroutines.launch",
      "",
      "/**",
      " * The management page's one state, and the four ways every sheet touches it.",
      " *",
      " * One object under all seven sheets' actions rather than a copy in each,",
      " * because what they share is the point: one [state], so a refusal [note]",
      " * recognises on any sheet empties the page for all eight; one `working` flag,",
      " * so two writes never race on the same class; and one place that drops an",
      " * answer arriving after the page has gone.",
      " *",
      " * Launches in [viewModelScope], the view model's, which is cancelled when the",
      " * view model is cleared, and which is why the parameter carries that name.",
      " */",
      "internal class ManagementPlumbing(private val viewModelScope: CoroutineScope) {",
      "",
      "    val state = MutableStateFlow(ManagementUiState())",
  ]
  for body in plumbing:
      text += [""] + body
  (D / "ManagementPlumbing.kt").write_text("\n".join(text + ["}", ""]), encoding="utf-8")
  print(f"{sum(len(n) for n in GROUPS.values())} members to seven classes, {len(PLUMBING)} to the plumbing, "
        f"{qualified} references qualified")
  EOF
  cd /c/Users/lumen/StudioProjects/lessons-b && python /c/Users/lumen/AppData/Local/Temp/decompose-b/split_management.py
  ```
  - Expected: `32 members to seven classes, 4 to the plumbing, 72 references qualified`.
  - On any other count, restore `ManagementViewModel.kt` from git, delete the eight new files, and find out why.

- [ ] **Step 2: Read the files.**
  - **The view model:**
    - the plumbing above `uiState`;
    - the seven classes between `uiState` and `init`;
    - `init`'s reset is `.collect { plumbing.state.value = ManagementUiState() }`;
    - the delegates are in their old order, under their section comments;
    - `loadDevices`' delegate is wrapped over two lines, and its default is `plumbing.state.value.showRevokedDevices`.
  - **`ClassCardActions.saveClass`** reads `plumbing.write {` and keeps `return@write Result.success(Unit)`.
  - **Line lengths:**
    ```bash
    cd /c/Users/lumen/StudioProjects/lessons-b && awk 'length($0) > 120 {print FILENAME": "FNR": "length($0)}' android/app/src/main/kotlin/com/lumenpearson/lessons/ui/admin/*.kt
    ```
    Expected: no output. The longest new lines are 114.

- [ ] **Step 3: Check it was a move.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b && A=android/app/src/main/kotlin/com/lumenpearson/lessons/ui/admin && python /c/Users/lumen/AppData/Local/Temp/decompose-b/moved.py HEAD $A/ManagementViewModel.kt $A/ManagementViewModel.kt $A/ManagementPlumbing.kt $A/ClassCardActions.kt $A/SubjectsActions.kt $A/BellsActions.kt $A/TimetableActions.kt $A/DevicesActions.kt $A/LogActions.kt $A/RequestsActions.kt --sub '(?<![\w.])plumbing\.='
  ```
  Expected `-` lines, exactly these seven:
  ```
  - 1x * call happened to be next. [note] is the single place that reads it, and it
  - 1x // -- the plumbing under all of it ---------------------------------------
  - 1x private fun <T> finish(
  - 1x private fun note(failure: ManageFailure?): Boolean {
  - 1x private fun read(
  - 1x private fun write(block: suspend () -> Result<*>) {
  - 1x private val state = MutableStateFlow(ManagementUiState())
  ```
  Expected `+` lines, and nothing else:
  - **Per new file:**
    - eight `package` lines;
    - the imports the new files repeat;
    - the eight classes' KDoc lines (`/**`, ` *`, text, ` */`);
    - `internal class X(` seven times, and `internal class ManagementPlumbing(private val viewModelScope: CoroutineScope) {`;
    - `+ 7x private val plumbing: ManagementPlumbing,` and `+ 7x private val repository: ManageRepository,`;
    - one each of `private val session: SessionRepository,` and `private val viewModelScope: CoroutineScope,`;
    - `+ 7x ) {`;
    - `val state = MutableStateFlow(ManagementUiState())`;
    - `fun read(`, `fun write(block: suspend () -> Result<*>) {`, `fun <T> finish(` and `fun note(failure: ManageFailure?): Boolean {`;
    - eight closing braces.
  - **In the view model:**
    - the plumbing's KDoc line and `private val plumbing = ManagementPlumbing(viewModelScope)`;
    - the two comment lines and the seven `private val …Actions = …` lines;
    - the five KDoc paragraph lines and its ` *`, and the `[ManagementPlumbing.note]` line;
    - the 32 delegates. `loadDevices` shows as `+ 1x fun loadDevices(includeRevoked: Boolean = state.value.showRevokedDevices) =` (old 1, new 2) and `devicesActions.loadDevices(includeRevoked)`;
    - the 12 `/** @see …Actions.… */` lines: `saveClass`, `setJoinMode`, `searchSchools`, `clearSchoolSearch`, `deleteClass`, `leaveDeletedClass`, `saveSubject`, `loadTimetable`, `importTimetable`, `replacePendingImport`, `cancelPendingImport` and `showNextLogPage`.

- [ ] **Step 4: Who holds the state, and who launches.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b/android/app/src/main/kotlin/com/lumenpearson/lessons/ui/admin && F="ManagementViewModel.kt ManagementPlumbing.kt ClassCardActions.kt SubjectsActions.kt BellsActions.kt TimetableActions.kt DevicesActions.kt LogActions.kt RequestsActions.kt" && grep -c "viewModelScope.launch" $F; grep -c "init {" $F; grep -cE "(^|[^A-Za-z])CoroutineScope\(" $F; grep -n "MutableStateFlow(\|= ManagementPlumbing(\|ManagementUiState()" $F; cat $F | grep -o "plumbing\." | wc -l
  ```
  Expected, against `1cc30fe`'s single file (6 launches, 1 `init`, 0 scopes made):
  - **`viewModelScope.launch`:** `ManagementViewModel.kt` 2 (`init`, `recheckRole`), `ManagementPlumbing.kt` 2 (`read`, `write`), `ClassCardActions.kt` 2 (`searchSchools`, `leaveDeletedClass`), and 0 in the other six.
  - **`init {`:** 1 in `ManagementViewModel.kt`, 0 everywhere else.
  - **`CoroutineScope(`:** 0 in every file.
  - **The `grep -n` prints three lines:**
    - `ManagementPlumbing.kt`'s `val state = MutableStateFlow(ManagementUiState())`;
    - `ManagementViewModel.kt`'s `private val plumbing = ManagementPlumbing(viewModelScope)`;
    - `ManagementViewModel.kt`'s `.collect { plumbing.state.value = ManagementUiState() }`.
  - **`plumbing.`:** 73, the 72 qualified plus the copy in `loadDevices`' delegate.

- [ ] **Step 5: The module.** Run Task 1's Step 4 command.
  - Expected: `BUILD SUCCESSFUL`, and `count.sh` prints `app: tests=690 failures=0`.
  - Among them, unchanged: `ManagementViewModelTest`'s 18, `SchoolSearchTest`'s 10, `ClassJoinModeScreenTest`'s 7 and `ManagementAcrossSheetsTest`'s 3.

- [ ] **Step 6: Commit** the nine files.
  ```
  Split ManagementViewModel's sheets into seven classes over one plumbing, behind one-line delegates

  The page's state and the four ways every sheet touches it (read, write,
  finish, note) become ManagementPlumbing, which the view model builds first.
  Each sheet's work moves verbatim to a class named after it, not after the
  sheet: ClassCardActions (the card, the join mode, the school directory and
  deleting the class), SubjectsActions, BellsActions, TimetableActions,
  DevicesActions, LogActions and RequestsActions (with the statistics). The
  plumbing's names are qualified, 63 references in the seven classes and 9
  in the view model, and nothing else changes; return@write keeps its label.
  The scope parameter is named
  viewModelScope, the view model's own, and no class makes one.

  The view model keeps its constructor, init, recheckRole, consumeNotice,
  dismissWriteFailure and the factory, and every other public method as a
  one-line delegate where it stood, so every sheet and test reaches it as
  before. state and the plumbing's four functions go from private to members
  of an internal class, the only widening.

  Not covered: detekt is not run in this commit; the baseline is rewritten
  in the next task.

  Refs #273.
  ```

---

### Task 4: Rewrite `:app`'s baseline after `ManagementViewModel`'s split, and read it

**Files:** at most `android/app/detekt-baseline.xml`, and nothing else.

**Expected diff: none.**
- `TooManyFunctions:ManagementViewModel.kt:ManagementViewModel : ViewModel` stays as it is: the class keeps 35 functions.
- No new file reaches a threshold. The most functions is 8, in `ClassCardActions`, against 11. The longest line is 114. Every new file holds one class and is named after it.

- [ ] **Step 1: Rewrite.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 :app:detektBaseline
  ```
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Read the diff.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b && git status --short && git diff -U0 -- android/app/detekt-baseline.xml && grep -c "<ID>" android/app/detekt-baseline.xml
  ```
  Expected: an empty `git status`, an empty diff, and `184`.

- [ ] **Step 3: If the diff is not empty.**
  - **An entry naming a new file is a new finding.**
    1. Run `git checkout -- android/app/detekt-baseline.xml`.
    2. Fix the code in a commit of its own, named for the finding. If the code is right as written, use `@Suppress("<Rule>")` on the declaration, with the reason.
    3. Rerun Steps 1 and 2.
  - **A re-rendering of the `ManagementViewModel` entry, with the same rule and class, counts as a move.** Commit it alone:
    ```
    Carry ManagementViewModel's detekt finding over its split

    Rewritten with :app:detektBaseline alone and read line by line: <the entry
    as it was> -> <the entry as it is>. No entry is new.

    Refs #273.
    ```

- [ ] **Step 4: detekt passes.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 :app:detekt
  ```
  Expected: `BUILD SUCCESSFUL`. With an empty diff there is no commit, and Task 9's close-out says so.

---

### Task 5: Hold what `SettingsViewModel` publishes and writes before it is split

**Files:** Create `android/app/src/test/kotlin/com/lumenpearson/lessons/ui/settings/SettingsViewModelTest.kt`.

**The cases**, as decision 5 asks: the state it publishes, the message the sync and the issue filing write, the update and GitHub fields, and the device link's reset when the class changes. The «Review Focus» adds the scope, `stateIn` and the values read at the call.

| # | Test | What would break it |
| --- | --- | --- |
| 1 | the whole `SettingsUiState` while watched, then a setter reaching it | a combine wired to the wrong flow |
| 2 | nothing published until watched; `developerRevealed` true from the start | `stateIn` moved or started eagerly; the initial value changed |
| 3 | the server asked once, then only on an address change | `init`'s first launch moved or reordered |
| 4 | a failed sync's message, consumed | `message` written somewhere `local` does not read |
| 5 | a refused report: the same message, one at a time | `SettingsGithub` handed no `message`, or another one; `filingIssue` read from elsewhere |
| 6 | each filed report calls its own callback | a callback kept from an earlier call |
| 7 | a check by hand reads the switch at the tap; the sheet rises, hides, re-rises without a check, is dismissed | the setting read once; `releaseSheet` not the one `remote` reads |
| 8 | the check at launch: put off, wanted, not allowed | the order of its four guards |
| 9 | turning pre-releases on writes, checks and raises | — |
| 10 | GitHub's account, sign-in and the pull request's progress | the GitHub half of `remote` or `translationSubmit` miswired |
| 11 | the link answers, remembers its role, and resets on a class switch | `deviceLink` split between two flows |
| 12 | an answer about the class switched away from is dropped | the guard on the class asked |
| 13 | work still out at `ViewModelStore.clear()` does not land | a collaborator's own scope |

- [ ] **Step 1: Write the test.**

  ```kotlin
  package com.lumenpearson.lessons.ui.settings

  import androidx.lifecycle.ViewModel
  import androidx.lifecycle.ViewModelProvider
  import androidx.lifecycle.ViewModelStore
  import com.lumenpearson.lessons.core.data.developer.DeveloperMode
  import com.lumenpearson.lessons.core.data.developer.DeveloperState
  import com.lumenpearson.lessons.core.data.developer.DeveloperTool
  import com.lumenpearson.lessons.core.data.repository.AppSettings
  import com.lumenpearson.lessons.core.data.repository.ClassRole
  import com.lumenpearson.lessons.core.data.repository.DeviceFlow
  import com.lumenpearson.lessons.core.data.repository.DeviceLink
  import com.lumenpearson.lessons.core.data.repository.DeviceLinkRepository
  import com.lumenpearson.lessons.core.data.repository.GithubAccount
  import com.lumenpearson.lessons.core.data.repository.GithubRepository
  import com.lumenpearson.lessons.core.data.repository.IssueDraft
  import com.lumenpearson.lessons.core.data.repository.IssueResult
  import com.lumenpearson.lessons.core.data.repository.PullRequestResult
  import com.lumenpearson.lessons.core.data.repository.ReleaseInfo
  import com.lumenpearson.lessons.core.data.repository.ServerStatus
  import com.lumenpearson.lessons.core.data.repository.SessionRepository
  import com.lumenpearson.lessons.core.data.repository.SyncResult
  import com.lumenpearson.lessons.core.data.repository.TimetableRepository
  import com.lumenpearson.lessons.core.data.repository.TranslationChange
  import com.lumenpearson.lessons.core.data.repository.UpdateCheck
  import com.lumenpearson.lessons.core.data.repository.UpdateRepository
  import com.lumenpearson.lessons.core.model.Timetable
  import com.lumenpearson.lessons.ui.admin.FakeSessionRepository
  import com.lumenpearson.lessons.ui.common.SyncMessage
  import com.lumenpearson.lessons.ui.onboarding.FakeSettings
  import com.lumenpearson.lessons.ui.translate.TranslationSubmit
  import kotlinx.coroutines.CompletableDeferred
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.flow.Flow
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.map
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.test.TestScope
  import kotlinx.coroutines.test.UnconfinedTestDispatcher
  import kotlinx.coroutines.test.resetMain
  import kotlinx.coroutines.test.runTest
  import kotlinx.coroutines.test.setMain
  import org.junit.After
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertNull
  import org.junit.Assert.assertTrue
  import org.junit.Before
  import org.junit.Test

  /**
   * What the settings page's view model publishes and writes, held before it is
   * split into the update check, the GitHub work and the Telegram link.
   *
   * Nothing tested the class before this. Each case is one thing that split
   * could break without a compile error: a flow read before it is built, a
   * flow two pieces write, a launch in a scope that outlives the page,
   * `stateIn` moved or started otherwise, or a value read once instead of at
   * the call. Every fake answers at once unless a test holds it with a gate.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  class SettingsViewModelTest {

      private val settings = FakeSettings()
      private val sessions = CountingSessions()
      private val timetables = Timetables()
      private val updates = Updates()
      private val github = Github()
      private val links = Links()
      private val developer = Developer()

      @Before
      fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

      @After
      fun tearDown() = Dispatchers.resetMain()

      private fun model() = SettingsViewModel(settings, sessions, timetables, updates, github, links, developer)

      /** The page on screen: `uiState` is shared while subscribed, so something subscribes. */
      private fun TestScope.watched(built: SettingsViewModel = model()): SettingsViewModel {
          backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { built.uiState.collect {} }
          return built
      }

      @Test
      fun `while it is watched the page publishes the settings, the classes and every remote field`() = runTest {
          links.roles.value = mapOf(1L to ClassRole.EDITOR)
          val model = watched()

          val session = checkNotNull(sessions.inner.session.value)
          assertEquals(
              SettingsUiState(
                  settings = AppSettings(),
                  session = session,
                  sessions = listOf(session),
                  serverStatus = ServerStatus.NotConfigured,
                  githubConfigured = true,
                  cachedRole = ClassRole.EDITOR,
              ),
              model.uiState.value,
          )

          model.setPitchBlack(true)
          assertTrue(model.uiState.value.settings.pitchBlack)
      }

      @Test
      fun `nothing is published until the page is watched, and the developer row starts from the mode`() {
          developer.state.value = DeveloperState(revealed = true)
          val model = model()

          assertEquals(SettingsUiState(), model.uiState.value)
          assertTrue(model.developerRevealed.value)
      }

      @Test
      fun `the server is asked once at the start, and again only when the address changes`() {
          val model = model()
          assertEquals(1, sessions.asked)

          model.setPitchBlack(true)
          assertEquals(1, sessions.asked)

          model.setBaseUrl("https://other.example")
          assertEquals(2, sessions.asked)
      }

      @Test
      fun `a sync that fails says so on the page until the message is consumed`() = runTest {
          timetables.answer = SyncResult.Unauthorised
          val model = watched()

          model.refreshNow()
          assertEquals(SyncMessage.Unauthorised, model.uiState.value.message)
          assertFalse(model.uiState.value.isRefreshing)

          model.consumeMessage()
          assertNull(model.uiState.value.message)
      }

      @Test
      fun `a bug report GitHub refuses writes the same message, and one report goes at a time`() = runTest {
          github.issueAnswer = IssueResult.Failed("refused")
          val gate = CompletableDeferred<Unit>().also { github.issueGate = it }
          val filed = mutableListOf<String>()
          val model = watched()

          model.fileIssue(DRAFT) { filed += it }
          model.fileIssue(DRAFT) { filed += it }
          assertTrue(model.uiState.value.isFilingIssue)
          assertEquals(1, github.issues.size)

          gate.complete(Unit)
          assertFalse(model.uiState.value.isFilingIssue)
          assertEquals(SyncMessage.IssueFailed, model.uiState.value.message)
          assertEquals(emptyList<String>(), filed)
      }

      @Test
      fun `each bug report that is filed hands its own page back and writes no message`() = runTest {
          val filed = mutableListOf<String>()
          val model = watched()

          model.fileIssue(DRAFT) { filed += "first $it" }
          model.fileIssue(DRAFT) { filed += "second $it" }

          assertEquals(listOf("first $ISSUE_URL", "second $ISSUE_URL"), filed)
          assertNull(model.uiState.value.message)
      }

      @Test
      fun `a check by hand reads the switch at the tap and raises the sheet whatever it finds`() = runTest {
          val model = watched()

          // Flipped after the view model was built, as the reader does it.
          settings.stored.value = settings.stored.value.copy(includePrerelease = true)
          model.checkForUpdates()
          assertEquals(listOf(true), updates.checks)
          assertEquals(UpdateCheck.UpToDate(null), model.uiState.value.update)
          assertTrue(model.uiState.value.showReleaseSheet)

          model.hideReleaseSheet()
          assertFalse(model.uiState.value.showReleaseSheet)

          // Over a check that has run, raising the sheet asks nothing more.
          model.showReleaseSheet()
          assertTrue(model.uiState.value.showReleaseSheet)
          assertEquals(listOf(true), updates.checks)

          model.dismissUpdate("v0.10.0")
          assertFalse(model.uiState.value.showReleaseSheet)
          assertEquals("v0.10.0", updates.dismissedTag.value)
      }

      @Test
      fun `the check at launch raises the sheet only for a release that is wanted and not put off`() = runTest {
          updates.answer = UpdateCheck.Available(RELEASE)
          updates.dismissedTag.value = RELEASE.tag
          val model = watched()

          model.checkForUpdatesAtLaunch()
          assertEquals(listOf(false), updates.checks)
          assertFalse(model.uiState.value.showReleaseSheet)

          updates.dismissedTag.value = null
          model.checkForUpdatesAtLaunch()
          assertTrue(model.uiState.value.showReleaseSheet)

          model.hideReleaseSheet()
          settings.stored.value = settings.stored.value.copy(autoCheckUpdates = false)
          model.checkForUpdatesAtLaunch()
          assertEquals(listOf(false, false), updates.checks)
          assertFalse(model.uiState.value.showReleaseSheet)
      }

      @Test
      fun `turning pre-releases on writes the switch, checks at once and raises the sheet`() = runTest {
          updates.answer = UpdateCheck.Available(RELEASE)
          val model = watched()

          model.enablePrereleases()

          assertTrue(settings.stored.value.includePrerelease)
          assertEquals(listOf(true), updates.checks)
          assertTrue(model.uiState.value.showReleaseSheet)
      }

      @Test
      fun `GitHub's account and sign-in are the page's, and a pull request that opens is shown once`() = runTest {
          val model = watched()
          github.account.value = GithubAccount("octocat", avatarUrl = null)
          github.flow.value = SIGNING_IN
          assertEquals(GithubAccount("octocat", avatarUrl = null), model.uiState.value.github)
          assertEquals(SIGNING_IN, model.uiState.value.signIn)

          model.signInWithGithub()
          assertEquals(1, github.signIns)

          github.pullAnswer = PullRequestResult.Opened(PULL_URL, refused = listOf("missing_key"))
          model.submitCorrections(listOf(CHANGE))
          assertEquals(TranslationSubmit.Opened(PULL_URL, listOf("missing_key")), model.translationSubmit.value)

          model.acknowledgeSubmission()
          assertEquals(TranslationSubmit.Idle, model.translationSubmit.value)

          github.pullAnswer = PullRequestResult.Failed("no fork")
          model.submitCorrections(listOf(CHANGE))
          assertEquals(TranslationSubmit.Failed, model.translationSubmit.value)
      }

      @Test
      fun `the Telegram link answers for the class on screen and resets when the class changes`() = runTest {
          val model = watched()

          model.refreshDeviceLink()
          assertEquals(DeviceLinkState.Ready(linkOf(ClassRole.ADMIN)), model.uiState.value.deviceLink)
          assertEquals(ClassRole.ADMIN, model.uiState.value.cachedRole)

          model.unlinkDevice()
          assertEquals(DeviceLinkState.Ready(linkOf(null)), model.uiState.value.deviceLink)
          assertEquals(listOf(1L to ClassRole.ADMIN, 1L to null), links.remembered)

          links.roles.value = links.roles.value + (2L to ClassRole.EDITOR)
          sessions.inner.rejoin(classId = 2L, className = "9Б")
          assertEquals(DeviceLinkState.Idle, model.uiState.value.deviceLink)
          assertEquals(ClassRole.EDITOR, model.uiState.value.cachedRole)
          assertEquals(ClassRole.EDITOR, model.uiState.value.effectiveRole)
      }

      @Test
      fun `an answer about the class switched away from is dropped`() = runTest {
          val gate = CompletableDeferred<Unit>().also { links.gate = it }
          val model = watched()

          model.refreshDeviceLink()
          assertEquals(DeviceLinkState.Loading(null), model.uiState.value.deviceLink)

          sessions.inner.rejoin(classId = 2L, className = "9Б")
          gate.complete(Unit)

          assertEquals(DeviceLinkState.Idle, model.uiState.value.deviceLink)
          assertEquals(emptyList<Pair<Long, ClassRole?>>(), links.remembered)
      }

      @Test
      fun `work still out when the view model is cleared does not land`() {
          val gate = CompletableDeferred<Unit>().also { github.issueGate = it }
          val filed = mutableListOf<String>()
          val store = ViewModelStore()
          val model = ViewModelProvider(store, factoryOf(model()))[SettingsViewModel::class.java]

          model.fileIssue(DRAFT) { filed += it }
          store.clear()
          gate.complete(Unit)

          assertEquals(1, github.issues.size)
          assertEquals(emptyList<String>(), filed)
      }
  }

  private const val ISSUE_URL = "https://github.com/example/lessons/issues/7"
  private const val PULL_URL = "https://github.com/example/lessons/pull/9"

  private val DRAFT = IssueDraft(title = "It closes on opening", body = "Steps")

  private val RELEASE = ReleaseInfo(
      tag = "v0.10.0",
      name = "v0.10.0",
      notes = "",
      htmlUrl = "https://github.com/example/lessons/releases/tag/v0.10.0",
      publishedAt = null,
      isPreRelease = false,
      apkUrl = null,
      apkBytes = null,
  )

  private val SIGNING_IN = DeviceFlow.AwaitingUser(
      userCode = "ABCD-1234",
      verificationUrl = "https://github.com/login/device",
      expiresAtMillis = 0L,
  )

  private val CHANGE = TranslationChange(
      paths = listOf("app/src/main/res/values/strings.xml"),
      key = "app_name",
      body = "Lessons",
  )

  /** Hands [model] to a store, the way the activity's provider would. */
  private fun factoryOf(model: ViewModel) = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
  }

  private fun linkOf(role: ClassRole?) = DeviceLink(
      deviceName = "Pixel",
      linked = role != null,
      role = role,
      canEdit = role != null,
      linkCode = null,
      botDeepLink = null,
  )

  /** [FakeSessionRepository], counting how often the server's status is asked for. */
  private class CountingSessions(
      val inner: FakeSessionRepository = FakeSessionRepository(),
  ) : SessionRepository by inner {
      var asked = 0
          private set

      override suspend fun serverStatus(): ServerStatus {
          asked++
          return inner.serverStatus()
      }
  }

  /** A sync that answers [answer] at once. */
  private class Timetables : TimetableRepository {
      var answer: SyncResult = SyncResult.Success
      override val timetable: Flow<Timetable?> = MutableStateFlow(null)
      override val syncedYears: Flow<Set<Int>> = MutableStateFlow(emptySet())
      override suspend fun snapshot(): Timetable? = null
      override suspend fun snapshotAroundToday(): Timetable? = null
      override suspend fun refresh(): SyncResult = answer
      override suspend fun refreshYear(openingYear: Int): SyncResult = answer
      override suspend fun forgetClassesOtherThan(keep: Set<Long>) = Unit
  }

  /** Releases as a test sets them: every check finds [answer] and publishes it. */
  private class Updates : UpdateRepository {
      var answer: UpdateCheck = UpdateCheck.UpToDate(null)
      val checks = mutableListOf<Boolean>()
      override val installedVersion: String = "0.9.0"
      override val state = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
      override val dismissedTag = MutableStateFlow<String?>(null)

      override suspend fun check(includePrerelease: Boolean): UpdateCheck {
          checks += includePrerelease
          state.value = answer
          return answer
      }

      override suspend fun dismiss(tag: String) {
          dismissedTag.value = tag
      }

      override suspend fun lastCheckMillis(): Long? = null
  }

  /** GitHub as a test sets it; [issueGate], when set, holds a report until completed. */
  private class Github : GithubRepository {
      var issueAnswer: IssueResult = IssueResult.Filed(ISSUE_URL)
      var issueGate: CompletableDeferred<Unit>? = null
      val issues = mutableListOf<IssueDraft>()
      var pullAnswer: PullRequestResult = PullRequestResult.Failed(null)
      var signIns = 0
      override val isConfigured = true
      override val account = MutableStateFlow<GithubAccount?>(null)
      override val flow = MutableStateFlow<DeviceFlow>(DeviceFlow.Idle)

      override fun signIn() {
          signIns++
      }

      override fun cancelSignIn() = Unit

      override suspend fun signOut() {
          account.value = null
      }

      override suspend fun fileIssue(draft: IssueDraft): IssueResult {
          issues += draft
          issueGate?.await()
          return issueAnswer
      }

      override suspend fun openTranslationPullRequest(changes: List<TranslationChange>): PullRequestResult =
          pullAnswer
  }

  /**
   * The Telegram link: [role] is what a refresh answers, [gate] holds one until
   * completed, and the roles are remembered per class, as the store keeps them.
   */
  private class Links : DeviceLinkRepository {
      var role: ClassRole? = ClassRole.ADMIN
      var gate: CompletableDeferred<Unit>? = null
      val roles = MutableStateFlow<Map<Long, ClassRole?>>(emptyMap())
      val remembered = mutableListOf<Pair<Long, ClassRole?>>()
      override val link = MutableStateFlow<DeviceLink?>(null)

      override suspend fun refresh(): Result<DeviceLink> {
          gate?.await()
          return Result.success(linkOf(role))
      }

      override suspend fun unlink(): Result<DeviceLink> = Result.success(linkOf(null))

      override fun lastRole(classId: Long): Flow<ClassRole?> = roles.map { it[classId] }

      override suspend fun rememberRole(classId: Long, role: ClassRole?) {
          remembered += classId to role
          roles.value = roles.value + (classId to role)
      }
  }

  /** The developer mode, revealed or not; nothing here asks GitHub. */
  private class Developer : DeveloperMode {
      override val state = MutableStateFlow(DeveloperState())

      override suspend fun reveal() {
          state.value = state.value.copy(revealed = true)
      }

      override suspend fun hide() = Unit
      override suspend fun verify() = Unit
      override suspend fun verifyIfStale() = Unit
      override suspend fun setTool(tool: DeveloperTool, on: Boolean) = Unit
  }
  ```

  **Why each value is what it is:**
  - `AppSettings`' defaults are `autoCheckUpdates = true`, `includePrerelease = false`, `notifyNewUpdates = true` and `baseUrl = ""`. So `https://other.example` is a change.
  - `lastCheckMillis()` is `null`, which passes the once-a-day guard.
  - `FakeSessionRepository` starts on class 1, `9А`, answers `ServerStatus.NotConfigured`, and `rejoin` switches the class without a `null` in between.

- [ ] **Step 2: Run the class.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 :app:testDebugUnitTest --tests '*SettingsViewModelTest*'
  ```
  - Expected: `BUILD SUCCESSFUL`, and the report says `tests="13"`, `failures="0"`.
  - If a case fails on the unsplit code, it describes the code wrongly: correct the test, not the code. If the code is what is wrong, it is a defect: stop, and file it before going on.
  - If `ViewModelProvider(store, …)` does not compile, use `ViewModelProvider.create(store, factoryOf(model()))[SettingsViewModel::class]` (Risks).

- [ ] **Step 3: See it fail, in one run.** Make four temporary edits in `SettingsViewModel.kt`:
  - **M1.** In `uiState`'s `stateIn`, the second `SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS)` in the file, write `SharingStarted.Eagerly`.
  - **M2.** Change `is IssueResult.Failed -> message.value = SyncMessage.IssueFailed` to `is IssueResult.Failed -> Unit`.
  - **M3.** Delete the line `if (asked != sessionRepository.current()?.classId) return@launch`.
  - **M4.** In `fileIssue`, change `viewModelScope.launch {` to `kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {`.

  Run Step 2's command. Expected: `BUILD FAILED`, with these four failing:
  - `nothing is published until the page is watched, …`, at `assertEquals(SettingsUiState(), …)`;
  - `a bug report GitHub refuses writes the same message, …`, at `IssueFailed`;
  - `an answer about the class switched away from is dropped`, at `Idle` against `Ready`;
  - `work still out when the view model is cleared does not land`, because the default `IssueResult.Filed` reaches `onFiled` after `store.clear()`.

  The other nine pass. If another fails as well, find out why before restoring.

  Restore with `git -C /c/Users/lumen/StudioProjects/lessons-b checkout -- android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings/SettingsViewModel.kt`.

- [ ] **Step 4: The module.** Run Task 1's Step 4 command. Expected: `BUILD SUCCESSFUL`, and `count.sh` prints `app: tests=703 failures=0 errors=0 skipped=0`.

- [ ] **Step 5: Commit** the test file.
  ```
  Hold what the settings view model publishes and writes before it is split

  Nothing tested SettingsViewModel. SettingsViewModelTest builds it with
  fakes and holds what a split into collaborators could break without a
  compile error: the whole state it publishes while watched, and nothing
  until then; the developer row starting from the mode's own answer; the
  server asked once and again only for a new address; the message a failed
  sync and a refused bug report both write; one report at a time, each with
  its own callback; the update check reading the pre-release switch at the
  tap, the sheet it raises, and the launch check's guards; GitHub's account,
  sign-in and pull request; the Telegram link, its remembered role, its reset
  when the class changes, and an answer about the class switched away from
  dropped; and work still out when the view model is cleared not landing.
  Seen failing with stateIn started eagerly, with the refused report's
  message removed, with the class guard removed, and with the issue filing's
  launch moved to a scope of its own.

  The settings, session and device-link fakes are reused where they can
  answer what is asked; the updates, GitHub, developer-mode and timetable
  ones are new and private to the test.

  Not covered: the screens over a real view model; the rows' wiring is the
  compile's.

  Refs #273.
  ```

---

### Task 6: Give the settings page's state types a file of their own

**Files:**
- Create `android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings/SettingsState.kt`.
- Modify `.../ui/settings/SettingsViewModel.kt`.

**Moves, verbatim with their KDoc:** `SettingsUiState` (57–97), and `DeviceLinkState`, `DeviceLinkState.role` and `SettingsUiState.effectiveRole` (650–716).

**Stays:** `private data class Remote` (Rulings, 10).

**Widens:** nothing.

- [ ] **Step 1: Write and run the script** (not committed).
  ```bash
  cat > /c/Users/lumen/AppData/Local/Temp/decompose-b/state_settings.py <<'EOF'
  """Task 6: the settings page's state types move to SettingsState.kt.
  Run from the worktree's root."""
  import sys
  from pathlib import Path

  sys.stdout.reconfigure(encoding="utf-8")
  D = Path("android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings")
  source = D / "SettingsViewModel.kt"
  lines = source.read_text(encoding="utf-8").splitlines()


  def kdoc_above(i):
      while lines[i] != "/**":
          i -= 1
      return i


  first = kdoc_above(lines.index("data class SettingsUiState("))
  last = lines.index(")", first)  # the data class's closing parenthesis
  assert lines[last + 1] == ""
  ui_state = lines[first : last + 1]
  del lines[first : last + 2]

  link = kdoc_above(lines.index("sealed interface DeviceLinkState {"))
  assert lines[link - 1] == ""
  link_state = lines[link:]
  del lines[link - 1 :]

  REPO = "com.lumenpearson.lessons.core.data.repository."
  moved = [REPO + "DeviceLink", REPO + "Session", "com.lumenpearson.lessons.ui.common.DefaultAppSettings"]
  shared = [REPO + n for n in ["AppSettings", "ClassRole", "DeviceFlow", "GithubAccount", "ServerStatus",
                               "UpdateCheck"]] + ["com.lumenpearson.lessons.ui.common.SyncMessage"]
  for name in moved:
      lines.remove(f"import {name}")
  source.write_text("\n".join(lines) + "\n", encoding="utf-8")

  head = ["package com.lumenpearson.lessons.ui.settings", ""] + [f"import {n}" for n in sorted(moved + shared)]
  (D / "SettingsState.kt").write_text("\n".join(head + [""] + ui_state + [""] + link_state) + "\n", encoding="utf-8")
  print(f"{len(ui_state)} + {len(link_state)} lines of state types moved")
  EOF
  cd /c/Users/lumen/StudioProjects/lessons-b && python /c/Users/lumen/AppData/Local/Temp/decompose-b/state_settings.py
  ```
  - Expected: `41 + 67 lines of state types moved`.
  - `wc -l` gives `SettingsViewModel.kt` 603 and `SettingsState.kt` 122.

- [ ] **Step 2: Check it was a move.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b && S=android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings && python /c/Users/lumen/AppData/Local/Temp/decompose-b/moved.py HEAD $S/SettingsViewModel.kt $S/SettingsViewModel.kt $S/SettingsState.kt
  ```
  Expected:
  - no `-` line;
  - exactly these `+` lines:
    - `+ 1x package com.lumenpearson.lessons.ui.settings`;
    - `+ 1x import …` for `AppSettings`, `ClassRole`, `DeviceFlow`, `GithubAccount`, `ServerStatus`, `UpdateCheck` and `ui.common.SyncMessage`. `Remote` and the view model still use them.

- [ ] **Step 3: The module.** Run Task 1's Step 4 command.
  - Expected: `BUILD SUCCESSFUL`, and `app: tests=703 failures=0`.
  - Among them, unchanged: `SettingsViewModelTest`'s 13, `EffectiveRoleTest`, `ClassRowsScreenTest` and `TelegramLinkRowsTest`.

- [ ] **Step 4: Commit** the two files.
  ```
  Give the settings page's state types a file of their own

  SettingsUiState, DeviceLinkState and the two properties read off them,
  DeviceLinkState.role and SettingsUiState.effectiveRole, move verbatim with
  their KDoc to SettingsState.kt. All four were public, so nothing is
  widened; the onboarding, the shell and the three tests that build these
  types import them by package and name and change nothing. The private
  Remote stays beside the combine that builds it, so it is not widened
  either.

  Not covered: detekt is not run in this commit, and nothing here runs on a
  device.

  Refs #273.
  ```

---

### Task 7: Split `SettingsViewModel`'s update check, GitHub work and Telegram link into classes of their own

**Files:**
- Create, in `android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings/`: `SettingsUpdates.kt`, `SettingsGithub.kt` and `SettingsDeviceLink.kt`.
- Modify `SettingsViewModel.kt`.

**Moves, verbatim with their KDoc** (Rulings, 8). Each owned flow goes from `private val` to `val`.
- **`SettingsDeviceLink`:** `deviceLink`, `refreshDeviceLink` and `unlinkDevice`.
- **`SettingsUpdates`:** `releaseSheet`, the six update functions, and `AUTO_CHECK_INTERVAL_MILLIS` with its KDoc, in a `private companion object`.
- **`SettingsGithub`:** `filingIssue`, `mutableSubmit`, `translationSubmit` with its KDoc, `submitCorrections`, `acknowledgeSubmission` and `fileIssue`.

**Rewrites in the view model:**
- `        filingIssue,` → `        settingsGithub.filingIssue,` and `        releaseSheet,` → `        settingsUpdates.releaseSheet,`, in `remote`.
- `        deviceLink,` → `        settingsDeviceLink.deviceLink,`, in `uiState`'s combine. The lambda's `deviceLink = link,` is a named argument and is not touched.
- `init`'s `.collect { deviceLink.value = … }` → `.collect { settingsDeviceLink.deviceLink.value = … }`.

**Widens:** `releaseSheet`, `filingIssue` and `deviceLink` (the table above).

- [ ] **Step 1: Write and run the script** (not committed).
  ```bash
  cat > /c/Users/lumen/AppData/Local/Temp/decompose-b/split_settings.py <<'EOF'
  """Task 7: SettingsViewModel's update check, GitHub work and Telegram link
  move into classes of their own, and the view model hands each call on in
  one line. Run from the worktree's root, after Task 6."""
  import re
  import sys
  from pathlib import Path

  sys.stdout.reconfigure(encoding="utf-8")
  D = Path("android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings")
  source = D / "SettingsViewModel.kt"
  lines = source.read_text(encoding="utf-8").splitlines()

  GROUPS = {
      "SettingsDeviceLink": ["refreshDeviceLink", "unlinkDevice"],
      "SettingsUpdates": ["checkForUpdates", "checkForUpdatesAtLaunch", "showReleaseSheet", "hideReleaseSheet",
                          "dismissUpdate", "enablePrereleases"],
      "SettingsGithub": ["submitCorrections", "acknowledgeSubmission", "fileIssue"],
  }


  def member(name):
      signature = next(i for i, l in enumerate(lines) if re.match(rf"    fun {name}\(", l))
      start = signature
      if lines[start - 1].strip().endswith("*/"):
          while not lines[start - 1].strip().startswith("/**"):
              start -= 1
          start -= 1
      end = signature + 1
      while not (lines[end] == "" and re.match(r"    \S", lines[end + 1])):
          end += 1
      return start, signature, end


  def delegate(signature, owner, kdoc):
      name = re.match(r"    fun (\w+)\(", signature).group(1)
      opened = signature.index("(")
      depth, at = 0, opened
      while True:
          depth += signature[at] == "("
          depth -= signature[at] == ")"
          if depth == 0:
              break
          at += 1
      declared = signature[opened + 1 : at]
      passed = ", ".join(p.split(":")[0].strip() for p in declared.split(", ") if p)
      field = owner[0].lower() + owner[1:]
      out = [f"    /** @see {owner}.{name} */"] if kdoc else []
      one = f"    fun {name}({declared}) = {field}.{name}({passed})"
      assert len(one) <= 120, one
      return out + [one]


  bodies = {owner: [] for owner in GROUPS}
  for owner, names in GROUPS.items():
      for name in names:
          start, signature, end = member(name)
          bodies[owner].append(lines[start:end])
          lines[start:end] = delegate(lines[signature], owner, start < signature)

  at = lines.index("    private val mutableSubmit = MutableStateFlow<TranslationSubmit>(TranslationSubmit.Idle)")
  assert lines[at + 1] == "" and lines[at + 4] == ""
  bodies["SettingsGithub"].insert(0, lines[at : at + 4])
  lines[at : at + 4] = [
      "    /** @see SettingsGithub.translationSubmit */",
      "    internal val translationSubmit: StateFlow<TranslationSubmit> = settingsGithub.translationSubmit",
  ]

  at = lines.index("    private val filingIssue = MutableStateFlow(false)")
  assert lines[at + 1] == "    private val releaseSheet = MutableStateFlow(false)"
  bodies["SettingsGithub"].insert(0, ["    val filingIssue = MutableStateFlow(false)"])
  bodies["SettingsUpdates"].insert(0, ["    val releaseSheet = MutableStateFlow(false)"])
  lines[at : at + 2] = [
      "",
      "    // What holds a flow of its own, each in a class of its own. Built here: after",
      "    // `message`, which one of them is handed, and before the combines that read",
      "    // their flows, which take them when they are built.",
      "    private val settingsUpdates = SettingsUpdates(settingsRepository, updateRepository, viewModelScope)",
      "    private val settingsGithub = SettingsGithub(githubRepository, message, viewModelScope)",
      "    private val settingsDeviceLink = SettingsDeviceLink(sessionRepository, deviceLinkRepository, viewModelScope)",
  ]

  at = lines.index("    private val deviceLink = MutableStateFlow<DeviceLinkState>(DeviceLinkState.Idle)")
  assert lines[at + 1] == ""
  del lines[at : at + 2]
  bodies["SettingsDeviceLink"].insert(0, ["    val deviceLink = MutableStateFlow<DeviceLinkState>(DeviceLinkState.Idle)"])

  for old, new in [("filingIssue,", "settingsGithub.filingIssue,"),
                   ("releaseSheet,", "settingsUpdates.releaseSheet,"),
                   ("deviceLink,", "settingsDeviceLink.deviceLink,")]:
      lines[lines.index("        " + old)] = "        " + new
  reset = lines.index("                .collect { deviceLink.value = DeviceLinkState.Idle }")
  lines[reset] = "                .collect { settingsDeviceLink.deviceLink.value = DeviceLinkState.Idle }"

  const = lines.index("        private const val AUTO_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L")
  assert lines[const - 2] == ""
  bodies["SettingsUpdates"].append(["    private companion object {", *lines[const - 1 : const + 1], "    }"])
  del lines[const - 2 : const + 1]

  kdoc_end = lines.index(" * work, which keeps WorkManager out of the UI layer entirely.")
  lines[kdoc_end + 1 : kdoc_end + 1] = [
      " *",
      " * What holds a flow of its own is a class of its own: [SettingsUpdates],",
      " * [SettingsGithub] and [SettingsDeviceLink]. This one builds them, reads",
      " * their flows in its combines, and hands each call on in one line, so every",
      " * screen takes this class as it did.",
  ]
  for name in ["com.lumenpearson.lessons.core.data.repository.IssueResult",
               "com.lumenpearson.lessons.core.data.repository.PullRequestResult",
               "com.lumenpearson.lessons.ui.translate.TranslationMode",
               "kotlinx.coroutines.flow.first",
               "kotlinx.coroutines.flow.asStateFlow"]:
      lines.remove(f"import {name}")
  source.write_text("\n".join(lines) + "\n", encoding="utf-8")

  REPO = "import com.lumenpearson.lessons.core.data.repository."
  HEADS = {
      "SettingsUpdates": [
          REPO + "SettingsRepository", REPO + "UpdateCheck", REPO + "UpdateRepository",
          "import kotlinx.coroutines.CoroutineScope", "import kotlinx.coroutines.flow.MutableStateFlow",
          "import kotlinx.coroutines.flow.first", "import kotlinx.coroutines.launch", "",
          "/**",
          " * The update check and the release sheet: a check by hand, the quieter one at",
          " * launch, and the sheet both of them raise.",
          " *",
          " * Owns [releaseSheet], which the view model's `remote` combine reads beside the",
          " * repository's own state. Launches in [viewModelScope], the view model's, which",
          " * is why the parameter carries that name; it never makes a scope of its own.",
          " */",
          "internal class SettingsUpdates(",
          "    private val settingsRepository: SettingsRepository,",
          "    private val updateRepository: UpdateRepository,",
      ],
      "SettingsGithub": [
          REPO + "GithubRepository", REPO + "IssueDraft", REPO + "IssueResult", REPO + "PullRequestResult",
          REPO + "TranslationChange", "import com.lumenpearson.lessons.ui.common.SyncMessage",
          "import com.lumenpearson.lessons.ui.translate.TranslationMode",
          "import com.lumenpearson.lessons.ui.translate.TranslationSubmit",
          "import kotlinx.coroutines.CoroutineScope", "import kotlinx.coroutines.flow.MutableStateFlow",
          "import kotlinx.coroutines.flow.StateFlow", "import kotlinx.coroutines.flow.asStateFlow",
          "import kotlinx.coroutines.launch", "",
          "/**",
          " * What the page sends to GitHub as the signed-in account: a bug report, and a",
          " * session of translation corrections as a pull request.",
          " *",
          " * Owns [filingIssue], which the view model's `remote` combine reads, and the",
          " * pull request's progress. [message] is the view model's, not this class's:",
          " * the sync writes it too, and the page shows whichever wrote last, so this",
          " * class is handed the flow itself. Launches in [viewModelScope], the view",
          " * model's; it never makes a scope of its own.",
          " */",
          "internal class SettingsGithub(",
          "    private val githubRepository: GithubRepository,",
          "    private val message: MutableStateFlow<SyncMessage?>,",
      ],
      "SettingsDeviceLink": [
          REPO + "DeviceLinkRepository", REPO + "SessionRepository",
          "import kotlinx.coroutines.CoroutineScope", "import kotlinx.coroutines.flow.MutableStateFlow",
          "import kotlinx.coroutines.launch", "",
          "/**",
          " * The Telegram link of this phone, as the class page shows it: asked for, and",
          " * undone.",
          " *",
          " * Owns [deviceLink]. One write to it is not here: the view model's `init`",
          " * resets it to [DeviceLinkState.Idle] when the class on screen changes, and",
          " * keeps that launch so that it keeps its place among the view model's two.",
          " * Launches in [viewModelScope], the view model's; it never makes a scope of its own.",
          " */",
          "internal class SettingsDeviceLink(",
          "    private val sessionRepository: SessionRepository,",
          "    private val deviceLinkRepository: DeviceLinkRepository,",
      ],
  }
  for owner, groups in bodies.items():
      text = ["package com.lumenpearson.lessons.ui.settings", "", *HEADS[owner],
              "    private val viewModelScope: CoroutineScope,", ") {"]
      for body in groups:
          text += [""] + body
      (D / f"{owner}.kt").write_text("\n".join(text + ["}", ""]), encoding="utf-8")
  print(f"{sum(len(n) for n in GROUPS.values())} members to three classes, with three flows and two more members")
  EOF
  cd /c/Users/lumen/StudioProjects/lessons-b && python /c/Users/lumen/AppData/Local/Temp/decompose-b/split_settings.py
  ```
  - Expected: `11 members to three classes, with three flows and two more members`.
  - `wc -l` gives `SettingsViewModel.kt` about 472. The three new files are about 97 (`SettingsUpdates`), 92 (`SettingsGithub`) and 70 (`SettingsDeviceLink`).

- [ ] **Step 2: Read the files.**
  - **The three collaborator properties** stand after `message` and before `installedVersion`, `local`, `remote` and `uiState`.
  - **`init`'s two launches are in today's order**: the server status, then the reset.
  - **`signInWithGithub`, `cancelGithubSignIn` and `signOutOfGithub`** are still in the view model, unchanged.
  - **Line lengths**, over the four files this task writes, not the whole package (`UpdateSheet.kt` carries release-note sample text long enough to trip a package-wide check):
    ```bash
    cd /c/Users/lumen/StudioProjects/lessons-b/android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings && awk 'length($0) > 120 {print FILENAME": "FNR": "length($0)}' SettingsViewModel.kt SettingsUpdates.kt SettingsGithub.kt SettingsDeviceLink.kt
    ```
    Expected: no output. The longest new line is the `fileIssue` delegate, at 111; `settingsDeviceLink`'s property is 112.

- [ ] **Step 3: Check it was a move.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b && S=android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings && python /c/Users/lumen/AppData/Local/Temp/decompose-b/moved.py HEAD $S/SettingsViewModel.kt $S/SettingsViewModel.kt $S/SettingsUpdates.kt $S/SettingsGithub.kt $S/SettingsDeviceLink.kt --sub '\bsettings(Updates|Github|DeviceLink)\.='
  ```
  Expected `-` lines, exactly these three widened flows:
  ```
  - 1x private val deviceLink = MutableStateFlow<DeviceLinkState>(DeviceLinkState.Idle)
  - 1x private val filingIssue = MutableStateFlow(false)
  - 1x private val releaseSheet = MutableStateFlow(false)
  ```
  Expected `+` lines, and nothing else:
  - **The three flows** as `val`.
  - **Per new file:**
    - three `package` lines;
    - the imports the new files repeat;
    - three class KDocs;
    - the three headers and constructor lines, with `+ 3x private val viewModelScope: CoroutineScope,` and `+ 3x ) {`;
    - `private companion object {` and its `}`;
    - closing braces.
  - **In the view model:**
    - the four comment lines and the three collaborator properties;
    - the KDoc paragraph's five lines;
    - the 11 delegates, which read as `fun x(…) = x(…)` after the substitution;
    - the 11 `/** @see Settings….… */` lines;
    - `internal val translationSubmit: StateFlow<TranslationSubmit> = translationSubmit`.

- [ ] **Step 4: Who writes each flow, and who launches.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b/android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings && F="SettingsViewModel.kt SettingsUpdates.kt SettingsGithub.kt SettingsDeviceLink.kt" && for p in "viewModelScope.launch" "stateIn(" "message.value =" "deviceLink.value =" "releaseSheet.value =" "filingIssue.value =" "init {"; do echo "== $p"; grep -c "$p" $F; done; grep -cE "(^|[^A-Za-z])CoroutineScope\(" $F; grep -n "MutableStateFlow<SyncMessage?>(\|MutableStateFlow<DeviceLinkState>(" $F
  ```
  Expected, against `1cc30fe`'s single file (17, 2, 3, 7, 6, 2, 1):

  | Pattern | `SettingsViewModel.kt` | `SettingsUpdates.kt` | `SettingsGithub.kt` | `SettingsDeviceLink.kt` |
  | --- | --- | --- | --- | --- |
  | `viewModelScope.launch` | 9 | 4 | 2 | 2 |
  | `stateIn(` | 2 | 0 | 0 | 0 |
  | `message.value =` | 2 (`refreshNow`, `consumeMessage`) | 0 | 1 (`fileIssue`) | 0 |
  | `deviceLink.value =` | 1 (`init`'s reset) | 0 | 0 | 6 |
  | `releaseSheet.value =` | 0 | 6 | 0 | 0 |
  | `filingIssue.value =` | 0 | 0 | 2 | 0 |
  | `init {` | 1 | 0 | 0 | 0 |
  | `CoroutineScope(` | 0 | 0 | 0 | 0 |

  The last `grep` prints two lines: the view model's `private val message = MutableStateFlow<SyncMessage?>(null)`, and `SettingsDeviceLink.kt`'s `val deviceLink = MutableStateFlow<DeviceLinkState>(DeviceLinkState.Idle)`.

- [ ] **Step 5: The module.** Run Task 1's Step 4 command. Expected: `BUILD SUCCESSFUL`, and `app: tests=703 failures=0`. `SettingsViewModelTest`'s 13 are among them, unchanged.

- [ ] **Step 6: Commit** the four files.
  ```
  Split SettingsViewModel's update check, GitHub work and Telegram link into classes of their own

  What holds a flow of its own moves verbatim to a class of its own:
  SettingsUpdates (the release sheet's flow, the check by hand and at
  launch, the sheet, the dismissal, pre-releases on, and the once-a-day
  interval), SettingsGithub (the issue filing and its flow, and the
  translation pull request and its progress) and SettingsDeviceLink (the
  Telegram link's state, its refresh and its unlinking). Each takes the view
  model's viewModelScope and makes none of its own.

  The view model keeps both combines and both stateIns, both init launches
  in today's order, message, the sync, the setters and the calls that only
  forward to a repository. message stays its own and SettingsGithub is handed
  the flow, because the sync writes it too. The three classes are built after
  message and before the combines that read them, and the comment above them
  says why. releaseSheet, filingIssue and deviceLink go from private to
  members of internal classes, the only widening; every public name stays,
  as a one-line delegate, so no screen changes. SettingsViewModelTest, written
  before this commit, holds it unchanged.

  Not covered: detekt is not run in this commit, and nothing here runs on a
  device.

  Refs #273.
  ```

---

### Task 8: Rewrite `:app`'s baseline after `SettingsViewModel`'s split, and read it

**Files:** at most `android/app/detekt-baseline.xml`.

**Expected diff: none.**
- `TooManyFunctions:SettingsViewModel.kt:SettingsViewModel : ViewModel` stays, because the class keeps 58 functions.
- The new classes have 6, 3 and 2.
- `AUTO_CHECK_INTERVAL_MILLIS` is a constant in a companion, which `MagicNumber` ignores.
- `SettingsViewModelTest` is excluded from `MagicNumber` and `TooManyFunctions` as a test. Its private classes do not count for `MatchingDeclarationName`, and its lines are under 120.

- [ ] **Steps 1–4:** Task 4's, word for word — but for the entry count, which is the count Task 4 left, not necessarily 184 (Task 4 may have committed a re-rendering). If an entry carries over, the commit message reads «Carry SettingsViewModel's detekt finding over its split», with the pair named. With an empty diff there is no commit.

---

### Task 9: The gates, the counts, the documents, the close-out and the pull request

**Files:**
- `README.md`, `docs/architecture.md`, `.claude/skills/gates/SKILL.md`, `HANDOVER.md` and `docs/history.md`.
- `docs/build.md` only if Task 4 or 8 committed a baseline.
- The `handover` skill's seven places hold the Android count in four of them: the cheat-sheet, the README, `docs/architecture.md` and the `gates` skill. `CLAUDE.md`, `CONTRIBUTING.md` and the `server-tests` agent carry the server's count only, which `git grep 1781` confirmed.

**The gates.** Each runs alone, in order, with the Bash tool's `run_in_background`.

- [ ] **Step 1: `test`, as CI runs it.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 test
  ```
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Every module's real test task, forced, for the counts.**
  - `test --rerun` reaches only `:core:model:test`. In the four Android modules `test` is an aggregate (`docs/build.md`, «What the JVM tests read, Gradle watches»).
  - So each real task is named, each `--rerun` following its own task, in one Gradle command:
    ```bash
    cd /c/Users/lumen/StudioProjects/lessons-b/android && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" ANDROID_HOME="F:/MovedFromC/Users/lumen/AppData/Local/Android/Sdk" ./gradlew --max-workers=2 :core:model:test --rerun :core:data:testDebugUnitTest --rerun :core:designsystem:testDebugUnitTest --rerun :widget:testDebugUnitTest --rerun :app:testDebugUnitTest --rerun
    ```
  - Expected: `BUILD SUCCESSFUL`, with each of the five tasks executed rather than `UP-TO-DATE` or `FROM-CACHE`.
  - Then `bash C:/Users/lumen/AppData/Local/Temp/decompose-b/count.sh` should print `core/model: tests=125`, `core/data: tests=638`, `core/designsystem: tests=205`, `widget: tests=126`, `app: tests=703` and `total: 1797`, every failure and error 0.
  - The documents take the printed numbers. A difference is explained before the pull request is opened.

- [ ] **Step 3: `assembleDebug`.** Use Step 1's command with `assembleDebug`. Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 4: `assembleRelease`.** Use Step 1's command with `assembleRelease`. Expected: `BUILD SUCCESSFUL`. Nothing here reads, sets or prints a keystore value.
- [ ] **Step 5: `detekt`**, all five modules. Use Step 1's command with `detekt`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: The pull request touched only what it says.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b && git diff --stat 1cc30fe..HEAD -- android/ && git diff --name-only 1cc30fe..HEAD -- '*/detekt-baseline.xml'
  ```
  Expected:
  - **13 created Kotlin files:** nine in `ui/admin/` and four in `ui/settings/`.
  - **2 modified:** the two view models.
  - **2 created tests:** `ManagementAcrossSheetsTest.kt` and `SettingsViewModelTest.kt`.
  - **No baseline**, unless Task 4 or 8 committed one.

- [ ] **Step 7: The baselines.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b/android && cat */detekt-baseline.xml core/*/detekt-baseline.xml | grep -c "<ID>"
  ```
  - Expected: `493`, and `docs/build.md`'s «493 entries … (#325, and pull request A …)» stays as it is.
  - If a baseline was committed, recount the three largest rules as pull request A's Task 11, Step 6 did, and write «(#325, and pull requests A and B of the Android decomposition)».

- [ ] **Step 8: The Android count, in the four places that carry it.** First, `git grep -n "1781"`.
  - `README.md:229`: `1781 tests` → `1797 tests`.
  - `docs/architecture.md:1011–1013`: the total, 1781 → 1797, and `:app` 687 → 703. The other four modules stay.
  - `.claude/skills/gates/SKILL.md:41`: `1781 today` → `1797 today`.
  - `HANDOVER.md`'s cheat-sheet in «How to continue» (line 2221): `# 1781 tests across the five modules` → `# 1797 …`.

  Every other `1781` in `HANDOVER.md` belongs to a batch section and is a record.

- [ ] **Step 9: `docs/architecture.md`'s test table.** After the row for `ui/onboarding/OnboardingFlowTest.kt`, add:
  ```
  | `android/app/.../ui/admin/ManagementAcrossSheetsTest.kt`, `ui/settings/SettingsViewModelTest.kt` | the management page's one write at a time across sheets, the devices' switch read at the call, and a search still out when the view model is cleared not landing; the settings view model's whole published state and nothing before it is watched, the message a failed sync and a refused bug report both write, the update check's switch read at the tap and the launch check's guards, GitHub's account, sign-in and pull request, and the Telegram link's reset when the class changes, with an answer about the old class dropped | JVM JUnit |
  ```

- [ ] **Step 10: No document names a member where it no longer is.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b && git grep -nE "ManagementViewModel\.(note|read|write|finish|state)\b|SettingsViewModel\.(deviceLink|releaseSheet|filingIssue|mutableSubmit)\b" -- . ':!docs/history.md' ':!docs/specs/'
  ```
  Expected: no output, exit 1.

- [ ] **Step 11: The server's tests that read these documents.** Run them while no Gradle job is running.
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b/server && .venv/Scripts/pytest.exe -q -p no:xdist tests/test_env_example.py tests/test_ci_paths.py tests/test_schema_version.py
  ```
  Expected: all passed (22 at #622).

- [ ] **Step 12: The close-out**, by the `handover` skill (read it first), written while the pull request is open.
  - **The opening paragraph.**
    - #622 joins the merged list. `main` is at `1cc30fe`, the merge of #622 at 18:54:47 UTC on 17 October 2026.
    - Read GitHub's commit status for it, rather than assuming «Vercel: success».
    - The one open pull request is this one, from `android/decomposition-b`, on milestone 11, `Refs #273`: the second of sub-project 4's three.
    - The schema did not move.
  - **A new section on top**, «What the last session added: sub-project 4's second pull request, `ManagementViewModel` and `SettingsViewModel` split into collaborators». It carries:
    - one sentence per commit with its SHA, in the commit messages' voice;
    - «Gates», with the real outputs of Steps 1–7, the counts, and the two baseline readings (empty, or the pairs carried);
    - «What was deliberately left alone», from «Deliberately left out» below;
    - «What nobody has verified in this batch»:
      - no APK from this branch was installed on a device or an emulator;
      - no screen test composes a settings row over a real `SettingsViewModel`, so the rows' wiring to the delegates is held by the compile;
      - Task 3's and Task 7's scripts were checked by the move checker and the counts, and their dry run was condensed.
  - **#622's section becomes «What the session before it added».** Its first line becomes «Merged as #622 (`1cc30fe`, 17 October 2026), from `android/report-target-469`, on milestone 12.», and its «this pull request» becomes «#622».
  - **The #451 batch's section moves verbatim** to the top of `docs/history.md`, under its introduction, retitled «What the batch before added: #451 merged, five dependency bumps in #452, …». Any «section 5» or «above» in it gains «in `HANDOVER.md`».
  - **The milestone table.**
    - Row 11: «this pull request (open)» is added.
    - Row 12: #622 joins the merged pull requests, if it is not there.
  - **The counts:** the cheat-sheet's, from Step 8.
  - **Section 5** gains the «not verified» items above.
  - **Section 7, «Next for the programme».**
    - Record the owner's word of 17 October 2026, in Russian, «продолжай с PR B подпроекта 4» («continue with sub-project 4's PR B») — recorded at Task 0, Step 1 — which puts B ahead of epic #438 and the 152-ФЗ work for this pull request.
    - Then what is next: C (`OnboardingScreen`, `FloatingToolbar`, `HomeShell`'s rules), and epic #438 where it stands.

- [ ] **Step 13: Commit.** Stage the five files by path, plus `docs/build.md` if Step 7 changed it.
  ```
  Count the tests after the second Android split, and hand over pull request B

  Sixteen tests more on Android, 1797 across the five modules (:app 703),
  in the four places that carry the Android count, and a row in
  architecture.md's test table for the two classes that hold what the
  management sheets share and what the settings view model publishes and
  writes. HANDOVER.md describes this pull request and #622's merge, #622's
  batch becomes the one before, and #451's moves to docs/history.md.

  Refs #273.
  ```

- [ ] **Step 14: Push, and open the pull request as a draft.**
  ```bash
  cd /c/Users/lumen/StudioProjects/lessons-b && git push -u origin android/decomposition-b
  ```
  - **How.** `gh` is `C:\Program Files\GitHub CLI\gh.exe`, called through PowerShell:
    ```
    gh pr create --draft --base main --head android/decomposition-b --milestone "v0.10.0 — One contract: REST v2, Connect and native gRPC, build console"
    ```
    A body is changed with `gh api -X PATCH repos/lumenpearson/lessons/pulls/<N> -F body=@<file>`, because `gh pr edit` fails here.
  - **Title.** `Split ManagementViewModel and SettingsViewModel into collaborators behind one view model each`.
  - **Body**, mirroring `.github/PULL_REQUEST_TEMPLATE.md`:
    - tick `android/app` and `docs/`;
    - «Boundaries»: no new Russian string, and no token, code, password or keystore path in the diff;
    - «Verification»: the real summary lines of Steps 1–5, `count.sh`'s output, and the two baseline readings. Server: no code changed, and the three document-reading tests passed;
    - «What is NOT covered»: Step 12's three items;
    - «Departures from the design», the four in Self-review: `message` handed to one collaborator, not «both»; a test added before `ManagementViewModel`'s split too; Tasks 4 and 8 likely committing nothing; and `loadDevices`' delegate wrapped over two lines;
    - end the body with `Refs #273` on a line of its own.
  - **The board.** Add the pull request to project 6, and fill Priority, Size, Estimate and the dates by the project's README, as the `github-pr` skill's «The board» says. Read it back.

- [ ] **Step 15: Merge, conditional on Task 0, Step 1's recorded word.**
  - **If** Task 0, Step 1 recorded the owner's word that B goes ahead of epic #438 (the 17 October 2026 «продолжай с PR B подпроекта 4»): after CI is green on the exact head, run the `github-pr` skill's five checks, then mark the pull request ready and merge it with `expectedHeadSha` pinned. If the session's permissions refuse it, ask the owner.
  - **Otherwise:** leave the pull request open, as a draft or ready, and ask the owner. A pull request the owner ordered built later is outside «a pull request of this session's own work is merged without asking».
  - The next close-out writes this merge's SHA, or says it is still waiting on the owner.

---

## Deliberately left out

- **Pull request C:**
  - `OnboardingScreen`, with `OnboardingPromisesTest`'s path and anchor (decision 3);
  - `FloatingToolbar`'s split by concern;
  - `HomeShell`'s pure rules, with the body left inline (decision 4).
- **Changing what the classes do.** No class gets an interface, an injected dispatcher or a test seam of its own. They are reached through the view models, as the screens reach them.
- **Moving `signInWithGithub`, `cancelGithubSignIn`, `signOutOfGithub`, `installedVersion` and `refreshNow`.** Rulings, 8 says why.
- **Shrinking the view models' `TooManyFunctions`.** Both keep their entries: the public surface is the screens' contract, and a move keeps it.
- **Anything sub-project 5 rewrites.** The audit log's paging, the school search's transport and the failure classifiers stay as they are: `LogActions` and `ClassCardActions` carry them verbatim.
- **A screen test over the real `SettingsViewModel`.** That is a test of the rows, not of the split.

## Self-review

**Coverage of the design and the brief:**

| Asked for | Where it is done |
| --- | --- |
| `ManagementViewModel`: state types first, into `ManagementState.kt` (decision 5) | Task 2 |
| `ManagementPlumbing` over `state`, `read`, `write`, `finish` and `note`, declared above `init` | Task 3; Rulings, 12 |
| Seven collaborators, each in a file named after its class | Task 3 |
| `recheckRole`, `consumeNotice`, `dismissWriteFailure` and `init` stay; one-line delegates | Task 3 |
| `SettingsViewModel`'s characterization test first: state, message, update and GitHub fields, the link's reset | Task 5, cases 1–12 |
| `SettingsState.kt`, then `SettingsUpdates`, `SettingsGithub` and `SettingsDeviceLink`, over `viewModelScope` | Tasks 6 and 7 |
| `message` in the view model; `remote` there over the classes' flows; `deviceLink` in `SettingsDeviceLink` | Task 7; Rulings, 8 and 9 |
| `stateIn` in the view model, both `init` launches in order, classes above the combines | Task 7; Review Focus 1 and 3 |
| What is widened, listed (decision 1) | «What is widened»; each task's «Widens» |
| The baseline rewritten in its own step and read (decision 2) | Tasks 4 and 8 |
| Per-module test runs, `--rerun` per real task, the gates once | every task from 1 on; Task 9, Steps 1–5 |
| Counts from 1781, docs, close-out | Task 9 |
| Review Focus: init order, scope, `stateIn`, a shared flow, a stale lambda | «Review Focus» |

**Where the code at `1cc30fe` departs from what the design assumed:**
- **«Both collaborators get a write handle» to `message`.** The sync is not a collaborator. `refreshNow` belongs to none of the three and stays in the view model, which owns `message`. Only `SettingsGithub` is handed it.
- **«Fakes exist for … the device link».** `FakeDeviceLinkRepository` exists, but cannot hold a refresh or record a remembered role, which two of the cases need. The timetable repository also needs a fake, which the design did not name.
- **«Each split is two commits».** Both baselines are expected not to change, so Tasks 4 and 8 most likely commit nothing.
- **`ManagementViewModel` gets a test before its split too**, though the design asked for one only for `SettingsViewModel` (Rulings, 2).
- **«Each now a one-line delegate».** `loadDevices`' is one statement over two lines, wrapped where its signature was.

**Not verified while planning** (read-only; no Gradle, no pytest, no pip):
- that the two test classes compile and pass on the unsplit code. They were reasoned from the code, the fakes and `kotlinx-coroutines-test`'s handling of an `UnconfinedTestDispatcher` as `Main`, as the existing view-model tests use it;
- that the three mutations of Task 1 and of Task 5 fail exactly the named tests;
- that the four scripts as written print the counts given. Their logic ran in condensed form, in memory, and the move checker is what each task trusts;
- that `:app:detektBaseline` comes back unchanged;
- the final test total, which is a `grep` of `@Test`. It matched the documented per-module counts exactly.
