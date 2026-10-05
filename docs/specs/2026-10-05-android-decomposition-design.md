# Splitting the Android god files: sub-project 4

Status: **drafted on 5 October 2026 while the owner was away, not yet approved.** Sub-project 4
of [the programme](2026-10-03-one-contract-design.md), whose section 5 already decided what this
sub-project does:
- split files within their feature packages, with no feature Gradle modules;
- name the seven files and how each splits;
- run each split's detekt baseline;
- cap Gradle's workers.

This document adds what the programme could not know on 3 October:
- what each file holds today, line by line;
- which declarations a split has to widen;
- which tests are keyed on a file;
- what #264 left behind;
- the order the work goes in.

The questions only the owner can answer are at the end, each with a recommendation. **Nothing
here is built before the owner approves it**, and nothing here changes what the app does.

It stands on a read-only survey of `android/` on 5 October 2026 (at `521c7a5`). No Gradle task
was run for it.

## What the files are today

| File | Lines | Module | Tests that construct or reach into it | detekt baseline entries |
| --- | --- | --- | --- | --- |
| `ui/settings/SettingsViewModel.kt` | 716 | `:app` | none of the class; `EffectiveRoleTest`, `ClassRowsScreenTest`, `TelegramLinkRowsTest` build its state types | 1 (`TooManyFunctions`) |
| `core/data/github/GithubRepositoryImpl.kt` | 705 | `:core:data` | `DeviceFlowPollTest` (the top-level `pollUntilDecided`) | 10 |
| `core/data/datastore/LessonsPreferences.kt` | 883 | `:core:data` | several, through the internal two-argument constructor and the top-level key objects | 7 |
| `ui/admin/ManagementViewModel.kt` | 821 | `:app` | `ManagementViewModelTest` (481 lines), `SchoolSearchTest`, `ClassJoinModeScreenTest` | 1 |
| `ui/onboarding/OnboardingScreen.kt` | 713 | `:app` | `OnboardingPromisesTest` reads this file **by path** and the text `private fun WelcomeStep(` | 4 |
| `navigation/HomeShell.kt` | 926 | `:app` | **nothing composes it** | 1 |
| `core/designsystem/component/FloatingToolbar.kt` | 1,569 | `:core:designsystem` | 12 Robolectric tests through `LessonsFloatingToolbar`, plus the one `androidTest`; they reach `ToolbarPillTag`, `tabContainerColor`, `spareLabelWidth`, `dropIndex` | 1 |

Twenty-five baseline entries name these seven files.

Two things in the programme's text are no longer quite true:
- **`ShellKeys` and `DiaryKeys` have not moved out of `LessonsPreferences`.** The programme says
  they «already moved out». They are top-level objects at the bottom of the same file, and only
  `MembershipKeys` lives in a file of its own.
- **`GithubRepositoryImpl` holds four concerns, not three.** Beside the device flow, the
  pull-request helpers and the issues, it reads the repository's permissions for the developer
  mode's gate (`repositoryPermissions`, `askPermissions`).

#264 has merged (#267, 3 October). No branch, local or remote, changes anything under `android/`.
`HomeShell` and `FloatingToolbar` are free to split.

The package cycles the programme names still exist:
- settings ↔ diary;
- onboarding ↔ join;
- settings → translate → developer → settings;
- diary → admin, one way.

This sub-project does not break them, because it adds no module that would need them broken. No
split below adds a new edge.

## Decisions

### 1. A split moves code, and changes nothing a test can see

Every split is a move:
- no rename of a public or `internal` name a caller uses;
- no change of behaviour;
- no change to a test, except where a test is keyed on a file's path (decision 3).

Kotlin's `private` is file-scoped, so a split widens what it must to `internal` and no further.
Each split lists what it widened. Widening is the cost of the split, and a reviewer should be
able to see it in one place.

The proof that a split moved code and nothing else:
- `./gradlew test` passes unchanged;
- both assembles pass, because R8 is where «worked in debug» stops;
- `./gradlew detekt` passes after the baseline is rewritten.

### 2. The detekt baseline is rewritten by each split, and its diff is read

A baseline entry names the rule, the file and the declaration, so code that moves to a new file
comes back as a new finding (`docs/build.md`, «detekt»). Each split therefore ends with
`./gradlew detektBaseline`, run alone, with nothing else in the diff. The baseline's diff is then
read by a person.

What should happen to the entries:
- an entry whose declaration moved should reappear under the new file;
- an entry that disappears must be explained;
- a new entry that is not a moved one is a finding to fix, not to baseline.

`MagicNumber` entries are keyed by the literal's value and the file. A literal that moves into a
new step file therefore reappears as a new entry with the same value, and that is expected.

### 3. The one test keyed on a file moves with it

`OnboardingPromisesTest` reads `ui/onboarding/OnboardingScreen.kt` by path and cuts out the body
of `private fun WelcomeStep(`. When `WelcomeStep` moves to its own file and becomes `internal`,
that test's path and anchor move with it, in the same commit, and it must still find
`footer = { LegalAcceptanceLine() }` and no `BuildConfig`.

No other test names one of the seven files. The survey checked every test that reads `.kt`
sources.

### 4. `HomeShell` gets a test that composes it before it is split

Nothing composes `HomeShell` today, which is a 734-line composable. Splitting it with no test that
draws it would be a move nobody can check. So the first commit of its split adds one Robolectric
test that composes the shell with fakes and asserts what it draws:
- the tabs;
- the selected page;
- the toolbar's items;
- the back button on a pushed page.

The split follows only once that test is green, and the test must stay green through it.
`ToolbarShellCallerTest` in `:core:designsystem` shows the shape of a caller test.

### 5. How each file splits

**`LessonsPreferences`** (`:core:data/datastore/`) splits into:
- `PreferenceKeys.kt`: the `KEY_*` values, `bundleTagKey`, `roleKey` and the prefixes, out of the
  private companion;
- `SettingsCodec.kt`: `Preferences.toSettings` and the write half of `updateSettings` as one
  function pair, so the two cannot drift apart again;
- `BundleTags.kt`: the bundle tags and the schedule fingerprint;
- `ShellKeys.kt` and `DiaryKeys.kt`: the two objects and their top-level functions, which is the
  move the programme thought had happened.

`LessonsPreferences` keeps the class, the sessions and the shell state, over the moved pieces. The
DataStore delegate stays file-private in the class's file.

**`GithubRepositoryImpl`** (`:core:data/github/`) splits along its four concerns:
- `GithubDeviceFlow.kt`: the device flow, with the top-level `pollUntilDecided`, `PollOutcome`
  and `PollVerdict` that `DeviceFlowPollTest` reaches, under the same names and package;
- `GithubPullRequests.kt`: the pull-request helpers;
- `GithubIssues.kt`: issues;
- `GithubPermissions.kt`: the permission read.

The class keeps its state, `get` with its 401 handling, and the public `GithubRepository` face,
and delegates to the four. They share one small holder of the token, the user agent and the
HTTP client, rather than four copies. The companion's constants go with the concern that uses
them; `TAG` and `HTTP_UNAUTHORISED` stay in the class.

**`ManagementViewModel`** (`:app/ui/admin/`) splits in two steps, as the programme says:
1. **State types first** (`Remote`, `PendingImport`, `ManagementNotice`, `SchoolSearch`,
   `ManagementUiState`) into `ManagementState.kt`. This is free: they are public, and only
   their file changes.
2. **Then per-sheet collaborators over shared plumbing.**
   - `state`, `read`, `write`, `finish` and `note` become one `internal` `ManagementPlumbing`
     the view model owns.
   - Each sheet's functions move to a collaborator in a file named for the sheet: class card,
     subjects, bells, timetable, devices, log, stats and requests. Each takes the plumbing and
     the repositories it needs.
   - The view model keeps its constructor and its public methods, each now one line that
     delegates.

   So `ManagementViewModelTest`, `SchoolSearchTest`, `ClassJoinModeScreenTest` and every sheet
   file that takes `(state, viewModel)` keep working unchanged.

**`SettingsViewModel`** (`:app/ui/settings/`) splits the same way, by delegation (question 2):
- the state types into `SettingsState.kt`;
- the update check into `SettingsUpdates.kt`;
- GitHub and translation into `SettingsGithub.kt`;
- the device link into `SettingsDeviceLink.kt`.

The view model keeps its constructor, its `Factory` and its public methods. The eleven row files
that take `(state, viewModel)`, `HomeShell` and the onboarding screens that use it keep working.

**`OnboardingScreen`** (`:app/ui/onboarding/`) gets one file per step, as its sibling steps
already are:
- `WelcomeStep.kt`;
- `AcknowledgementStep.kt`, which takes `CrashReportChoices` and `ProseTint`, which only it uses;
- `PreferencesStep.kt`;
- `PermissionsStep.kt`.

The four become `internal`. The driver, its transition constant and `PathTop` stay in
`OnboardingScreen.kt`. The shared helpers used by the sibling steps (`StepScaffold`,
`AccentSection`, `fadeUnderHero`, `RevealStagger`, `labelRes`) move to `OnboardingParts.kt`,
which already holds the steps' shared parts.

**`FloatingToolbar`** (`:core:designsystem/component/`) splits by concern into files in the same
package:
- `ToolbarModel.kt`: `ToolbarItem`, `ToolbarAction` and `ToolbarPillTag`;
- `ToolbarMetrics.kt`: the sizing constants and the width helpers, widened to `internal`;
- `ToolbarFaces.kt`: `PillFace` and its faces, and `PillContainer`;
- `ToolbarItems.kt`: the items row with its drag state;
- `ToolbarTab.kt`: the tab and its colours;
- `ToolbarActionSlot.kt`: the action slot and button;
- `ToolbarBackAndTitle.kt`;
- `ToolbarSprings.kt`.

`LessonsFloatingToolbar` and the preview stay in `FloatingToolbar.kt`. The names the tests reach
(`ToolbarPillTag`, `tabContainerColor`, `spareLabelWidth`, `dropIndex`) keep their names and
package, so the twelve Robolectric tests and the `androidTest` are unchanged.

**`HomeShell`** (`:app/navigation/`) is split after decision 4's test exists:
- `ShellToolbar.kt`: building the toolbar's items, selection, action and title, which is lines
  741–876 inline today;
- `ShellBackHandlers.kt`: the four back handlers and the predictive one;
- `ShellPages.kt`: the page body inside `AnimatedContent`.

`HomeShell` keeps the state it hoists and calls the three. Its siblings (`ShellBack.kt`,
`ShellAction.kt`, `ShellMotion.kt`, `SettingsTrail.kt`) already show the pattern.

### 6. The order, and how many pull requests

| Pull request | Splits | Why together |
| --- | --- | --- |
| A | `LessonsPreferences`, `GithubRepositoryImpl` | both in `:core:data`, with no UI and no shared callers; one detekt baseline |
| B | `ManagementViewModel`, `SettingsViewModel` | the same pattern (state types, then collaborators over plumbing) applied twice; one review of the pattern |
| C | `OnboardingScreen`, `FloatingToolbar`, `HomeShell` | the UI. `HomeShell` last, after its new test, because it calls the toolbar |

Each pull request runs the Android gates once, in order and alone: `./gradlew test`, both
assembles, then `detekt`. Each commit inside a pull request splits one file and passes
`./gradlew :module:test` for its module.

The workers cap (decision 7) comes first, in pull request A.

### 7. Gradle's workers are capped

`android/gradle.properties` has no `org.gradle.workers.max` today, with `org.gradle.parallel=true`
and a 4 GB daemon. On the owner's machine, whose memory is faulty, a parallel build is a heavy job
by itself. The programme asks for the cap as part of this sub-project. Where it goes is question
1: in the repository, which also caps CI's healthy runners, or in the machine's own
`~/.gradle/gradle.properties`, documented in `docs/build.md`.

## Questions for the owner

1. **Where does the workers cap go?**
   - *Recommended: the machine's own `~/.gradle/gradle.properties`*
     (`org.gradle.workers.max=2`), with a paragraph in `docs/build.md` saying why and how.
   - CI's runners have healthy memory and four cores, and a cap in the repository would slow
     every CI run for a machine-specific problem.
   - The programme wrote «in `gradle.properties`» without choosing which one.
2. **Should `SettingsViewModel` split into collaborators behind one view model, or into
   separate view models?** The programme named «an updates view model, a GitHub/translation view
   model».
   - *Recommended: collaborators behind one view model*, as for `ManagementViewModel`.
   - Separate view models would change the `(state, viewModel)` parameters of eleven row files,
     `HomeShell` and the onboarding screens, so a pure move would become a rewiring.
   - Collaborators split the file just as much and change no caller.
   - If the lifecycles ever need to differ, the collaborators are already the seams.
3. **Three pull requests, or one per file?** *Recommended: three* (decision 6). Each carries one
   kind of split, and each runs the heavy Android gates once rather than seven times.

## Risks

- **A move that is not a move.** A split is reviewed as a diff of moved lines. A changed line
  hidden among them is the one risk a pure move has. Two things guard against it:
  - git's move detection (`--color-moved`) in the review;
  - the rule that a commit splits one file and touches nothing else.
- **The baseline hides a new finding.** `detektBaseline` would accept anything. Decision 2's read
  of the baseline's diff is what stops that.
- **`HomeShell` before its test.** Decision 4 exists so the riskiest split is not the only one
  with no test.
- **R8.** A widened `internal` changes nothing R8 keeps, but both assembles run anyway.
- **Sub-project 5 changes two of these files again.** It replaces the network layer, and its
  errors reach `ManagementViewModel` and the settings. Splitting first gives sub-project 5
  smaller files to change. The collaborators of decision 5 are where its error mapping will land.
