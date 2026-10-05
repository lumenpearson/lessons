# Splitting the Android god files: sub-project 4

Status: **approved by the owner on 5 October 2026**, every question answered with its
recommendation (#306): the workers cap is `--max-workers=2` on the command line, the settings
view model splits into collaborators, and the work comes in three pull requests. Sub-project 4
of [the programme](2026-10-03-one-contract-design.md), whose section 5 already decided what this
sub-project does:
- split files within their feature packages, with no feature Gradle modules;
- name the seven files and how each splits;
- run each split's detekt baseline;
- cap Gradle's workers.

This document adds what the programme could not know on 3 October:
- what each file holds today;
- what a split has to widen;
- which tests are keyed on a file or cover nothing;
- what #264 left behind;
- the order the work goes in.

The questions only the owner can answer are at the end, each with a recommendation. **Nothing
here is built before the owner approves it**, and nothing here changes what the app does.

It stands on a read-only survey of `android/` on 5 October 2026 (at `521c7a5`), and was revised
the same night after an independent review that read all seven files in full. That review found:
- that `HomeShell`'s split as first written was not a move;
- shared state described wrongly for `GithubRepositoryImpl`;
- a baseline rule that would have forced refactoring in the middle of a move;
- a recommended home for the workers cap that is the file holding the signing passwords.

No Gradle task was run for either the survey or the review.

## What the files are today

| File | Lines | Module | Tests that construct or reach into it | detekt baseline entries |
| --- | --- | --- | --- | --- |
| `ui/settings/SettingsViewModel.kt` | 716 | `:app` | **none of the class**; `EffectiveRoleTest`, `ClassRowsScreenTest`, `TelegramLinkRowsTest` build its state types | 1 (`TooManyFunctions`) |
| `core/data/github/GithubRepositoryImpl.kt` | 705 | `:core:data` | only the top-level `pollUntilDecided`, through `DeviceFlowPollTest`; nothing tests the class body | 10 |
| `core/data/datastore/LessonsPreferences.kt` | 883 | `:core:data` | several, through the internal two-argument constructor and the top-level key objects | 7 |
| `ui/admin/ManagementViewModel.kt` | 821 | `:app` | `ManagementViewModelTest` (481 lines, with fakes), `SchoolSearchTest`, `ClassJoinModeScreenTest` | 1 |
| `ui/onboarding/OnboardingScreen.kt` | 713 | `:app` | `OnboardingPromisesTest` reads this file **by path** and the text `private fun WelcomeStep(` | 4 |
| `navigation/HomeShell.kt` | 926 | `:app` | **nothing composes it** | 1 |
| `core/designsystem/component/FloatingToolbar.kt` | 1,569 | `:core:designsystem` | eleven Robolectric tests through `LessonsFloatingToolbar`, plus the one `androidTest`; they reach `ToolbarPillTag`, `tabContainerColor` and `spareLabelWidth` | 1 |

Twenty-five baseline entries name these seven files.

Two things in the programme's text are no longer quite true:
- **`ShellKeys` and `DiaryKeys` have not moved out of `LessonsPreferences`.** They are top-level
  objects at the bottom of the same file. Only `MembershipKeys` lives in a file of its own.
- **`GithubRepositoryImpl` holds four concerns, not three.** Beside the device flow, the
  pull-request helpers and the issues, it reads the repository's permissions for the developer
  mode's gate.

#264 has merged (#267, 3 October). No branch, local or remote, changes anything under `android/`.

The package cycles the programme names still exist:
- settings ↔ diary;
- onboarding ↔ join;
- settings → translate → developer → settings;
- diary → admin, one way.

This sub-project adds no module that would need them broken, and no split below adds a new edge.

## Decisions

### 1. A split moves code, and changes nothing a test can see

Every split is a move:
- no rename of a public or `internal` name a caller uses;
- no change of behaviour.

**What a split may change in tests:**
- a test keyed on a file's path is updated (decision 3);
- a split may **add** a test that pins what it is about to move, where nothing tests it today
  (decisions 4 and 5).

**Widening is listed.** Kotlin's `private` is file-scoped, so a split widens what it must to
`internal` and no further. Section 5 lists the widenings, so a reviewer sees the cost in one place.

**The Compose invariant: a composable moves whole.** No `remember`, `rememberSaveable`,
`rememberUpdatedState` or `pointerInput` leaves the function it is in. That is what makes the
toolbar's and onboarding's splits safe. For example, `ToolbarTab`'s `rememberUpdatedState` and its
`pointerInput(Unit)` stay together, which CLAUDE.md's rule about `pointerInput(Unit)` depends on.
It is also why `HomeShell` is not split into composables at all (decision 4).

**What proves it was a move:**
- `./gradlew test` passes;
- both assembles pass, because R8 is where «worked in debug» stops;
- `./gradlew detekt` passes against the rewritten baseline.

For the two splits nothing tests today, decision 5's added tests are what give the first point
any meaning.

### 2. The detekt baseline is rewritten in its own commit, and its diff is read

A baseline entry names the rule, the file and the declaration with its signature, visibility
included. `MagicNumber` entries also carry the enclosing class and the literal. So code that moves
comes back as a new finding (`docs/build.md`, «detekt»).

Each split is therefore two commits:
1. the move;
2. `./gradlew detektBaseline`, run alone, with nothing else in the commit.

A person then reads the second commit's diff:
- **A moved entry reappears under its new file and signature.** For example, `private fun
  ToolbarTab` comes back as `internal fun ToolbarTab` in `ToolbarTab.kt`. One entry may also
  become several, because a literal used in two steps is two `MagicNumber` entries.
- **A finding carried by lines moved into a newly named declaration counts as moved, not new.**
  The write half of `updateSettings`, for instance, keeps its `LongMethod` under its new name.
  The commit's message names each such pair, old → new.
- **An entry that disappears is explained.** `GithubRepositoryImpl`'s `TooManyFunctions` will
  probably go, because the class keeps about seven functions.
- **A finding that is neither moved nor carried is a defect to fix**, not one to baseline.

No new file comes near `TooManyFunctions`' eleven. The largest collaborator has about eight.

### 3. The one test keyed on a file moves with it, and is made to fail when it should

`OnboardingPromisesTest` reads `ui/onboarding/OnboardingScreen.kt` by path and cuts out the body
after `private fun WelcomeStep(`. When `WelcomeStep` moves to its own file and becomes `internal`,
the test's path and anchor move in the same commit. One more change goes with them: today the cut
returns the whole file when the anchor is missing, so a stale anchor would pass silently. The test
is changed to fail when the anchor is absent.

No other test names one of the seven files. The survey and the review checked every test that
reads `.kt` sources. They also checked the source-walking guards, which keep working as guards:
- `CorrectionReachTest` catches an IDE auto-import of Material's `Text` in a new file;
- `DeviceClockTest`'s exemption marker travels with its line;
- `UnusedStringTest` follows a usage wherever it moves.

### 4. `HomeShell` gives up its rules, not its body

`HomeShell` is one 734-line composable. Every piece of it closes over the shell's local state, and
cutting its toolbar, pages and back handlers into composables of their own would pass about twenty
values and callbacks. It would also put four things at risk that no simple test would see:
1. **State remembered inside `AnimatedContent` would become per-slot and die with the slot.** That
   would silently undo #243's scroll restoration.
2. **The pages must read the slot's `page`, and the toolbar must read `destination`.** Swapping
   them brings back #264's bug.
3. **Back handlers are chosen by the order they were registered.** Moving their call moves their
   priority.
4. **A `remember { … }` around a passed-down callback keeps a stale lambda.**

So `HomeShell` is split the way its siblings were. Its siblings are `ShellBack`, `ShellAction`,
`SettingsTrail` and `ShellPage`: pure functions, each with its own unit test. **The pure rules move
out, each with a unit test, and the composable body stays where it is:**
- `shellAction` goes next to `shellActionKind`;
- the toolbar's decisions (the selected index, the items, the title, what the back click does)
  become a pure function of the destination and the shell's state;
- the `when` that picks a page's offset holder becomes a pure function.

`AnimatedContent`'s body and the back handlers stay inline. That makes the file smaller and its
rules testable, with none of the four risks.

### 5. A view model splits into collaborators, after a test pins it

**`ManagementViewModel`** splits in two steps, as the programme says:
1. **State types first** (`Remote`, `PendingImport`, `ManagementNotice`, `SchoolSearch`,
   `ManagementUiState`) into `ManagementState.kt`. This is free: they are public, and only their
   file changes.
2. **Then per-sheet collaborators over shared plumbing.**
   - `state`, `read`, `write`, `finish` and `note` become one `internal` `ManagementPlumbing` the
     view model owns, declared above `init`.
   - Each sheet's functions move to a collaborator class in a file **named after the class**, not
     after the sheet. The `*Sheet.kt` files hold the composables, and detekt's
     `MatchingDeclarationName` wants a file holding one class to carry its name.
   - The collaborators are: `ClassCardActions` (with the school search, `deleteClass` and
     `leaveDeletedClass`), `SubjectsActions`, `BellsActions`, `TimetableActions`,
     `DevicesActions`, `LogActions`, and `RequestsActions` (with the stats, which the view model
     already groups with the requests).
   - `recheckRole`, `consumeNotice`, `dismissWriteFailure` and `init` stay in the view model.
   - It keeps its constructor and its public methods, each now a one-line delegate.

   So `ManagementViewModelTest` and every sheet file that takes `(state, viewModel)` keep working
   unchanged.

**`SettingsViewModel`** splits the same way, by delegation (question 2), **after a
characterization test pins it.** Nothing tests the class today.

The test comes first. It builds the view model with fakes and checks what the move could break:
- the state it publishes;
- the message the sync and the issue filing write;
- the update and GitHub fields;
- the device link's reset when the class changes.

Fakes exist for the settings, the session and the device link. The update, GitHub and developer
mode repositories need small ones.

Then the split:
- **`SettingsState.kt`**: the state types.
- **`SettingsUpdates`** (the update check and the release sheet), **`SettingsGithub`** (GitHub
  and translation) and **`SettingsDeviceLink`**: the collaborators. Each takes the view model's
  scope and the flows it writes.

Who owns each flow:
- **`message`** is shared: the sync and the issue filing both write it. It stays in the view
  model, and both collaborators get a write handle.
- **The `remote` combine** mixes update state with GitHub state, so it stays in the view model,
  over flows the two collaborators expose.
- **`deviceLink`** belongs to `SettingsDeviceLink`.

The rules for the view model's scope:
- **Collaborators use `viewModelScope`.** It is cancelled at `onCleared` and runs on
  `Main.immediate`. A collaborator never makes a scope of its own.
- **`stateIn(…WhileSubscribed)` stays in the view model.**
- **Both `init` launches stay in the view model, in today's order.** `Main.immediate` runs a
  launch up to its first suspension inside the constructor, so moving one into a collaborator
  would reorder them.
- **The collaborators are declared above the combines that read them.**

### 6. How the data-layer files split

**`LessonsPreferences`** (`:core:data/datastore/`):
- **`PreferenceKeys.kt`** becomes an `internal object PreferenceKeys`, like `MembershipKeys`. It
  takes the `KEY_*` values, `bundleTagKey`, `roleKey` and the prefixes out of the private
  companion, and the codec's references become `PreferenceKeys.X`.
- **`SettingsCodec.kt`** holds `Preferences.toSettings` and the write half of `updateSettings`,
  as one `internal` function pair. A round-trip test is added beside it (decision 1): today
  `AppSettingsTest` notes there is no seam to test the pair, and the pair is what «cannot drift
  apart again» depends on.
- **`BundleTags`** is an `internal class` in `BundleTags.kt` over the store. `LessonsPreferences`
  keeps the seven bundle-tag and fingerprint methods as one-line member delegates, so their
  callers in `di/` and `notifications/` change nothing.
- **`ShellKeys.kt` and `DiaryKeys.kt`** take the two objects and their top-level functions,
  which is the move the programme thought had happened.

`LessonsPreferences` keeps the class, the sessions and the shell state. Its `dataStore` is never
widened: keeping it private is what forces every write through `write()` and its credentials
refresh.

**What it widens:**
- `toSettings` and the codec's write half;
- the companion's keys and helpers, into `PreferenceKeys`;
- `write`, `preferences` and `removeKeysStartingWith`, for `BundleTags`.

**`GithubRepositoryImpl`** (`:core:data/github/`) splits along its four concerns:
- **`GithubDeviceFlow`** owns the device flow's state and code:
  - `clientId`, `scope`, the state flow, `lock`, `pollJob` and its constants, so that the
    `synchronized(lock)` rule stays in one file;
  - the top-level `pollUntilDecided`, `PollOutcome` and `PollVerdict` that `DeviceFlowPollTest`
    reaches, under the same names and package.

  The class delegates `flow`, `signIn` and `cancelSignIn`. `signOut` cancels the flow, then
  clears.
- **`GithubPullRequests`** owns `get`, which only the pull-request helpers call.
- **`GithubIssues`** and **`GithubPermissions`** each handle their own 401, as they do today.

What the four share is `GithubPreferences` (no token is held: every call reads it, and a 401
clears it), the lazy `userAgent` (lazy because computing it is a binder call), and three constants
widened to `internal`:
- `TAG`;
- `HTTP_CREATED`;
- `HTTP_UNAUTHORISED`.

The HTTP client and `API_BASE` are global, so nothing can point the class at a mock server. The
split is checked by its compile, `DeviceFlowPollTest` and review. That is said rather than
papered over.

### 7. How the UI files split

**`OnboardingScreen`** (`:app/ui/onboarding/`) gets one file per step, as its sibling steps
already are:
- `WelcomeStep.kt`;
- `AcknowledgementStep.kt`, which takes `CrashReportChoices` and `ProseTint`, which only it uses;
- `PreferencesStep.kt`;
- `PermissionsStep.kt`.

The four become `internal`. The driver, its transition constant and `PathTop` stay in
`OnboardingScreen.kt`. The shared helpers move to `OnboardingParts.kt`, which already holds the
steps' shared parts: `StepScaffold`, `AccentSection`, `fadeUnderHero` with `HeroFade`,
`RevealStagger`, and `labelRes`.

**`FloatingToolbar`** (`:core:designsystem/component/`) splits by concern into files in the same
package:
- `ToolbarModel.kt`: `ToolbarItem`, `ToolbarAction` and `ToolbarPillTag`;
- `ToolbarMetrics.kt`: the sizing constants and the width helpers;
- `ToolbarFaces.kt`: `PillFace` and its faces, and `PillContainer`;
- `ToolbarItems.kt`: the items row with its drag state;
- `ToolbarTab.kt`: the tab and its colours;
- `ToolbarActionSlot.kt`, with `ActionFace` kept private;
- `ToolbarBackAndTitle.kt`;
- `ToolbarSprings.kt`.

`LessonsFloatingToolbar` and the preview stay in `FloatingToolbar.kt`. The names the tests reach
keep their names and package. `dropIndex` and `moveItem` already live in `ToolbarReorder.kt` and
do not move.

**What it widens**, all visible package-wide (none clashes with a name in `main` or in the tests):
- the sizing constants (`ItemSize`, `ItemGap`, `LabelWidth`, `BadgeSize`, …);
- the faces;
- `ToolbarItems`, `ToolbarTab`, `ToolbarGap` and `ToolbarActionButton`;
- the springs;
- `ModeFadeMillis`, `ModeSlideMillis` and `HeldBodyAlpha`.

**`HomeShell`** (`:app/navigation/`) is decision 4. Its rules move out to pure functions in their
own files, with unit tests: `shellAction`, the toolbar's decisions, and the offset holder's choice.
It widens `BackScaleDepth`, `BackSettleMillis`, `BackReturnMillis`, `TabsStateKey` and
`SettingsRootStateKey` only if a moved rule needs them.

### 8. The order, and how many pull requests

| Pull request | Splits | Why together |
| --- | --- | --- |
| A | `LessonsPreferences`, `GithubRepositoryImpl` | both in `:core:data`, with no UI and no shared callers |
| B | `ManagementViewModel`, `SettingsViewModel` (after its characterization test) | the same pattern (state types, then collaborators over plumbing) applied twice; one review of the pattern |
| C | `OnboardingScreen`, `FloatingToolbar`, and `HomeShell`'s rules | the UI. All pure moves or pure extractions, under the Compose invariant |

**Gates.**
- Each pull request runs the Android gates once, in order and alone: `./gradlew test`, both
  assembles, then `detekt`.
- Each commit runs its module's tests. In `:core:designsystem` that does not compile `:app`,
  which uses `ToolbarItem` and `ToolbarAction`. That is safe while names and packages are kept,
  and the pull request's gate is what proves it.

### 9. Gradle's workers are capped where no agent reads a secret

`android/gradle.properties` has no `org.gradle.workers.max` today, and runs with
`org.gradle.parallel=true` and a 4 GB daemon.

**What a workers cap does, and what it does not.**
- It limits how many tasks run at once.
- It does not limit the memory the daemon, the Kotlin daemon, Robolectric's test JVMs or R8 take.
- On a machine whose memory is faulty, fewer parallel tasks is still fewer simultaneous peaks.

**Where it goes is question 1**, and one place is ruled out. The machine's own
`~/.gradle/gradle.properties` holds the release keystore's passwords (`docs/build.md`, «Locally»).
An agent following «add a line there» would open that file, and on 5 October an agent reading it
for a Gradle setting did print a password into its transcript. If the cap is to live there, the
owner adds the line by hand, and no agent opens the file.

## Questions for the owner

1. **Where does the workers cap go?** The choice decides what pull request A carries.
   - *Recommended: on the command line*, `./gradlew --max-workers=2 …`, Gradle's own option for
     `org.gradle.workers.max`. Pull request A writes it into `docs/build.md` for this machine,
     and the build console ([#308](2026-10-05-build-console-design.md), decision 2) passes the
     same spelling to every Gradle run it starts. One spelling, because the console's test that
     holds its table to CI strips exactly that flag and no other form of it.
   - Why not the repository's `gradle.properties`: it would slow CI's healthy four-core runners
     for one machine's problem.
   - Why not the machine's `~/.gradle/gradle.properties`: it holds the signing passwords, so it is
     yours to edit by hand if you prefer it.
2. **Should `SettingsViewModel` split into collaborators behind one view model, or into separate
   view models?** The programme named «an updates view model, a GitHub/translation view model».
   - *Recommended: collaborators behind one view model*, as for `ManagementViewModel`.
   - Separate view models would take five fields out of `SettingsUiState` (`update`, `github`,
     `signIn`, `isFilingIssue`, `showReleaseSheet`), and rewire the rows that read them, so a
     move would become a redesign.
   - Collaborators split the file just as much and change no caller.
3. **Three pull requests, or one per file?** *Recommended: three* (decision 8). Each carries one
   kind of split, and each runs the heavy Android gates once rather than seven times.

## Risks

- **A move that is not a move.** A split is reviewed as a diff of moved lines, and a changed line
  hidden among them is the one risk a pure move has. Two things guard against it:
  - git's move detection (`--color-moved`) in the review;
  - the rule that a split commit splits one file and touches nothing else.
- **The baseline hides a new finding.** `detektBaseline` would accept anything. Decision 2's
  separate commit and its read diff are what stop that.
- **`SettingsViewModel` and `GithubRepositoryImpl` had no tests.** Decision 5 adds one for the
  view model before its split. For the repository, which cannot be pointed at a mock server, the
  compile and the review are the check, and the design says so.
- **R8.** A widened `internal` changes nothing R8 keeps, but both assembles run anyway.
- **Sub-project 5 changes two of these files again.** It replaces the network layer, and its
  stage 5b edits two of pull request B's collaborators: `LogActions`, when the audit log pages by
  a token instead of an offset, and `ClassCardActions`, when the school search moves to
  `ListSchools`. Its design ([#307](2026-10-05-android-transports-design.md)) therefore starts
  after this sub-project has merged, as the programme orders, so that each split stays a move
  rather than being rebased over a rewrite. Its error mapping does not land here: each classifier
  keeps its sealed type, so the collaborators and the screens see the same failures they see
  today.
