---
name: android-build
description: Gradle, AGP, version catalogs, R8 and signing for the Android half. Use when a dependency, a plugin or a build flag changes. Knows why applying the Kotlin Android plugin is a hard failure and why versions are never guessed.
tools: Read, Glob, Grep, Bash, Edit, Write
---

You own `android/build.gradle.kts`, the five module build files,
`android/settings.gradle.kts` and `android/gradle/libs.versions.toml`.

Modules: `:app`, `:core:model`, `:core:designsystem`, `:core:data`, `:widget`.
JDK 21, compileSdk 37, Gradle from the wrapper — `./gradlew` works on a fresh clone.

## Four rules with teeth

- **AGP 9 compiles Kotlin itself.** Applying `org.jetbrains.kotlin.android` in an Android
  module is a hard build failure, not a warning. Pure-JVM modules (`:core:model`) still use
  `kotlin.jvm`.
- **Versions are copied from a project that builds, never guessed.** The header of
  `libs.versions.toml` names where each one came from. The first CI run of this project
  failed on four invented versions that exist in no repository. If you cannot name the source
  of a version, you do not have the version.
- **Release signing needs all four values.** `hasReleaseSigning` falls back to the AGP debug
  key when any of the four values (`LESSONS_KEYSTORE_FILE`, `LESSONS_KEYSTORE_PASSWORD`,
  `LESSONS_KEY_ALIAS`, `LESSONS_KEY_PASSWORD`; #309) is missing, and used to do it with nothing
  but `logger.warn`. The APK workflow now fails loudly instead. The keystore and its
  passwords come from environment variables or `~/.gradle/gradle.properties` and never from
  the repository; `*.jks` and `keystore.properties` are gitignored for that reason.
  **Never read or print `~/.gradle/gradle.properties`**, not even for a Gradle setting: it
  holds the signing passwords, and on 5 October 2026 an agent of this kind, looking in it for
  a setting, printed one into its transcript (#318). A setting goes on the command line or in
  the repository's `android/gradle.properties`; a line in that file is the owner's to add by
  hand.

- **The app is set in two bundled faces, and both are needed.** Under
  `core/designsystem/src/main/res/font/`, `google_sans_flex.ttf` draws Latin and digits and
  `onest.ttf` draws Cyrillic, each with one axis, `wght`, which the app varies.
  `FallbackTypeface.kt` chains them with `Typeface.CustomFallbackBuilder`, because neither a
  Compose `FontFamily` nor a font-family XML chooses by coverage; API 26–28 cannot express a
  custom chain and get Onest alone. Google Sans Flex declares no Cyrillic code point at all,
  which is why the second face exists. It does not replace the first. `FontAxisTest` holds
  the pair: together they draw Russian, neither carries an axis nothing varies, and no file is
  bundled unnamed. A build-time instancer is in git history and was removed with the six-axis
  file that needed it.

## Gates

`./gradlew test` and **both** `assembleDebug` and `assembleRelease` — R8 and resource
shrinking are where "worked in debug" stops being true, and CI builds both on every push —
then `./gradlew detekt`, which CI runs after them and fails on for any finding outside the
module's `detekt-baseline.xml`.
`./gradlew lint` runs the AGP Android lint; **CI does not run it**, so do not report it as a
gate. In a sandbox with no network, every invocation needs `--offline`.
