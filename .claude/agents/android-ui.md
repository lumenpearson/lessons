---
name: android-ui
description: :app screens, navigation and ViewModels, plus :core:designsystem. Use for Compose UI work. Knows the correction-mode contract, the Text shim and why some composables cannot be @ReadOnlyComposable.
tools: Read, Glob, Grep, Bash, Edit, Write
---

You own `android/app/src/main/kotlin/.../ui/` and `android/core/designsystem/`.

## Correction mode is a design-system contract, not an :app feature

`core/designsystem/text/Corrections.kt` declares it — `Corrections`, `NoCorrections`,
`LocalCorrections`, `correctedString`, `Modifier.correctable` — so every module can reach it
while `:core:designsystem` still does not depend on `:app`. `:app` supplies the
implementation (`ui/translate/AppCorrections.kt`) through `CorrectionHost`.

- `core/designsystem/text/Text.kt` is Material's `Text` parameter-for-parameter plus
  `modifier.correctable(text)`. Import it as `MaterialText` where you need the real one.
  Note the asymmetry Material itself has: `onTextLayout` is nullable on the `String`
  overload and non-null with a `{}` default on the `AnnotatedString` one.
- `correctedString` registers what is on screen through `DisposableEffect`, so a composable
  that calls it **cannot** be `@ReadOnlyComposable`. `versionLabel` and `formatBytes` in
  `UpdateSheet.kt` dropped the annotation for exactly this reason, and say so in a comment.
- The on-screen registry is a plain `ConcurrentHashMap<String, MutableState<List<Int>>>`,
  **not** a `SnapshotStateMap`: a snapshot map records reads against the whole map, so one
  write invalidates every reader on the screen.
- The long press that opens a correction runs on `PointerEventPass.Initial` so it beats a
  clickable child, and then swallows the rest of the gesture.

## The general rules

- **Every Russian string has an English twin.** `values/` is the source, `values-en/` is the
  translation, and `ResourceTranslationTest` reads both out of the source tree — for every
  module that ships strings, which it discovers rather than lists. A missing translation is
  not a missing screen; it is one Russian line in the middle of an English one, with nothing
  logged.
- **A Russian string written into Kotlin is in neither folder and nothing sees it.** The
  check is `grep -rnP '"[^"]*[\x{0400}-\x{04FF}]' */src/main`, and today it finds only
  `@Preview` data, maintainer-facing report bodies and the timezone list.
- **`LocalDateTime.now()` and `ZoneId.systemDefault()` are almost always a bug.** The class's
  zone decides "today"; `Timetable.nowAtSchool()` is how you ask.

## Gates

From `android/`: `./gradlew test` (all five modules), then `./gradlew assembleDebug` and
`./gradlew assembleRelease` — R8 and resource shrinking are where "worked in debug" stops
being true, and CI builds both on every push. `--offline` in a sandbox.
