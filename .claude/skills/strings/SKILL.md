---
name: strings
description: Add, rename or reword a user-facing string on the Android side without breaking the Russian/English parity the test enforces. Use whenever a values/ file changes.
---

# Adding a string

Russian is the source, English is the translation, and **every Russian string has an English
twin**. Code and comments stay English.

## The steps

1. Put the name in the right file. `:app` has `strings.xml` plus `strings_admin`,
   `strings_diary`, `strings_docs`, `strings_github`, `strings_telegram`,
   `strings_translate`, `strings_updates`. `:core:data`, `:core:designsystem` and `:widget`
   have one `strings.xml` each.
2. Add the same name to that module's `values-en/`, in the same file.
3. Keep the format arguments identical. `%1$s` on one side and `%s` on the other is a
   failure, and so is a different count.
4. For a `<plurals>`, the English one must be **exactly** `one` + `other`. Russian has four
   forms; the test counts arguments **per form**, because over the concatenated text the two
   languages can never agree.
5. `./gradlew :app:test --tests '*ResourceTranslationTest*'`.

## Why the test matters more than it looks

Android resolves names one by one. A missing translation is not a missing screen — it is one
Russian line in the middle of an English one, **with nothing logged**. The test reads both
folders out of the source tree, discovers the modules rather than listing them, and fails on
a name missing from `values-en/`, a name only in English, mismatched format arguments, and a
wrong plural shape.

`:core:data`, `:core:designsystem` and `:widget` went unguarded for a long time, which is how
the countdown on the home screen stayed Russian under an English caption.

## What the test cannot see

A Russian string written into Kotlin is in neither folder. Sweep from `android/`:

    grep -rnP '"[^"]*[\x{0400}-\x{04FF}]' */src/main

Today it finds only `@Preview` data, maintainer-facing report bodies, and the timezone list,
whose own file documents the choice. Anything else is a string that should be a resource.

## Correction mode

A string drawn through `correctedString` can be corrected on the phone. Note that
`correctedString` registers through `DisposableEffect`, so a composable that calls it cannot
be `@ReadOnlyComposable`.
