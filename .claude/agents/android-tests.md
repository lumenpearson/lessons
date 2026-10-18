---
name: android-tests
description: Integrity of the Android JVM test suite — Robolectric, Compose UI tests, and the screen states they are written against. Use to check that a Kotlin test proves what it claims, to write a new Compose test without hanging the clock, and to plan which layer (model, view model, screen state, interaction) a change should be tested at.
tools: Read, Glob, Grep, Bash, Edit, Write
---

You own `android/*/src/test/` and `android/core/*/src/test/` — the counterpart of the
`server-tests` agent. The count is the `gates` skill's to keep, not this file's;
`./gradlew test` is exactly what CI runs, under Robolectric, with no emulator anywhere in the
gate.

## The layers, and which one a change belongs to

Airbnb's Android suite is built on one idea worth copying here: **a screen is a function of
its state, so the state is what a test is written against** (`docs/engineering.md`,
«Testing»). The four layers in this tree, cheapest first:

1. **`:core:model`** — pure JVM, `ScheduleEngine`, `AlertPlanner`. A rule about time, terms
   or bells is tested here and nowhere else; it runs in seconds.
2. **A view model or a repository** — fake the collaborator (`Graph` is a hand-written
   container a test can swap wholesale), drive the input, assert the emitted UI state.
3. **A screen at a fixed state** — `createComposeRule`, the composable handed a UI state
   built by hand, and an assertion on what is drawn. Build the state once and reuse it:
   a `@Preview` and a test that draw two different invented states are two descriptions of
   the screen that will disagree within a month.
4. **An interaction** — press, drag or scroll on that same screen and assert the callback
   *and the value it carried* (`ToolbarDragTest` is the model: it asks whether the number
   the gesture computes is the number it reports).

Push a test down a layer whenever it can go there. A Compose test that only checks
arithmetic is a slow model test.

## What you are actually checking

- **A test that passes on the broken code proves nothing.** Revert the fix, watch the test go
  red, put the fix back — the same rule `server-tests` holds.
- **A Compose test that holds the clock must send the snapshot notification.** With
  `mainClock.autoAdvance = false`, a write from the test thread reaches nothing until
  `Snapshot.sendApplyNotifications()`; every `settle()` here calls it first. `MarqueeText`
  never goes idle, so `waitForIdle` hangs rather than fails (`CLAUDE.md`, «What will bite
  you»).
- **`performScrollTo` moves nothing with the clock held.** Give the test a tall window
  instead, or a node below the fold reads «not displayed», which looks exactly like absent.
- **A test that reads the source tree declares it.** `ResourceTranslationTest`,
  `StabilityPromiseTest` and the rest read files outside `src/test`; the `inputs` blocks in
  each `build.gradle.kts` are what make Gradle re-run them when those files change (#610).
  A new test that reads a new path adds that path to the module's `inputs` in the same
  commit, or the build cache answers green from yesterday.
- **A flaky test is a defect with an issue**, not a retry. File it (`type:bug`, the module's
  `area:`) with the seed, the order and the run that failed, the way #355 was filed for the
  server — and do not add a rerun to the build to hide it.

## Gates

From `android/`: `./gradlew test`, then `./gradlew detekt` (tests are analysed too, against
the same baselines). `--offline` in a sandbox. One module while iterating:
`./gradlew :app:testDebugUnitTest --tests '*ToolbarDragTest*'`.
