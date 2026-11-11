# Pending design notes: the languages of Russia, and settings in the style of Essentials

These are notes, not a spec. They were captured on 5 October 2026 while the diary sign-in design was being
brainstormed. This sub-project is brainstormed on its own after the sign-in spec is written.

## The owner's requests, in their words

- «добавь все языковые группы россии»:
  - **Altaic:** Turkic, Mongolic and Tungusic — Tatar, Bashkir, Yakut, Chuvash, Tuvan, and others.
  - **Uralic:** Komi, Udmurt, Mari, Mordvin, Nenets.
  - **North Caucasian:** Chechen, Ingush, Avar, Dargin, Kabardian-Circassian, and others.
  - **Indo-European:** Russian, Ossetian, Armenian, Romani, and others.
  - **Isolates:** for example Nivkh.
- «выбор языков и дизайн внутри разделов настроек возьми прямо из настроек Essentials, а также, выбор языков с диалогом возьми оттуда же».

## Decided so far

1. **Who translates:** the owner chose machine draft plus native speakers' corrections.
   - The interface and the in-app guide are machine-translated into every language, and each draft language is marked «черновик» in the picker.
   - Native speakers correct the drafts through the app's existing correction mode, `ui/translate/`: a long press, an edit, then a submit.
   - The privacy policy and the terms stay Russian and English, with a translated retelling and the note «юридически действует русский текст».
2. **The settings design and the language picker come from Essentials**:
   - **Upstream** is `sameerasw/essentials`, branch `main`, MIT, «Copyright (c) 2026 sameerasw.com». The owner's fork `lumenpearson/essentials` is 5300 commits behind, so use upstream.
   - **Attribution:** keep the copyright header in any copied file, and add Essentials to the app's licenses sheet.
   - **The files:**
     - `app/src/main/java/com/sameerasw/essentials/ui/core/pickers/LanguagePicker.kt`: a `ConfigPickerItem` whose pill reads «nativeName (name)» and opens a `SegmentedDropdownMenu`.
     - `utils/LanguageUtils.kt`: the `Language(code, name, nativeName)` list, with a `Locale` display-name fallback.
     - `ui/core/cards/ConfigPickerItem.kt`: a `ListItem` on `surfaceBright` with a 16 dp padding and a primary-tinted leading icon. Its trailing pill is a `primaryContainer` `Surface` with 12 dp corners and `labelLarge`.
     - `ui/core/containers/RoundedCardContainer.kt`: a Column clipped to 24 dp with a 2 dp gap. Its items are `surfaceBright` with `shapes.extraSmall`, which gives the segmented M3E list.
     - `ui/activities/SettingsActivity.kt`: the section header is a `titleMedium` in `onSurfaceVariant`, padded 16 / 16 / 8, above a `RoundedCardContainer`.
     - `translation/ui/TranslationWarningBottomSheet.kt`, the "dialog": an `EssentialsBottomSheet` with an icon in a pastel circle, a bold `titleMedium`, a `RoundedCardContainer` of explanatory `ListItem`s, and a «don't show again» checkbox. It is the model for the warning shown when a draft language is picked.
     - `ui/components/menus/SegmentedMenu.kt` and `ui/core/sheets/EssentialsBottomSheet.kt`.
   - Local copies of some of them, which die with the job: `C:\Users\lumen\.claude\jobs\c9e2d980\tmp\essentials\upstream\`.

## Facts found

- **Strings:** the app has Russian (`values/`) and English (`values-en/`), 1318 `<string>` and `<plurals>` in `values/`.
- **Material:** 1.5.0-alpha24, and Material 3 Expressive is already used (`Theme.kt`, `LoadingIndicator`, `WavyProgress`).
- **The language picker today:** `app/.../ui/common/AppLocales.kt`, `ui/settings/AppearanceRows.kt` and `OnboardingScreen.kt`.
- **Fonts:**
  - Google Sans Flex (Latin and digits) and Onest (Cyrillic). Onest's coverage of extended Cyrillic must be checked: Ӏ ӧ ҥ ӈ ә һ ҡ ҙ ҫ ӑ ӗ ӳ ӱ ӄ ӽ ӻ ҕ and others.
  - Armenian is its own script and needs a third bundled face (for example Noto Sans Armenian, under OFL). `FallbackTypeface.kt` chains the faces.
- **Android:** any BCP-47 tag works as a per-app language (`b+sah`, `b+tyv`). Many small languages lack ICU plural rules and month names, which then fall back.
- **The tests:** `ResourceTranslationTest` checks Russian against English only, so it must grow to every language.

## Open questions for that brainstorm

- The exact list. The state languages of the republics plus the ones the owner named come to about 35–40. Do we add more?
- Which script for each language (Cyrillic for nearly all; Armenian script for Armenian).
- How the correction-mode submissions are collected for drafts.
