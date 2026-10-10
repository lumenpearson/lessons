# One geometry for the whole app: corners, list rows and insets

Agreed with the owner on 10 October 2026, after an audit of every radius, row and inset in
`android/app` and `android/core/designsystem`. The owner's words were «выровни скругления, элементы
в списках и отступы по всему приложению», with a screenshot of «Календарь»'s week view. In it the
view switcher (a full pill), the «Отмеченные» chip (a pill with insets of its own), a weekday tile
(4 dp) and the «Подробнее →» link sit at four different radii, and their right edges do not line up.

## What the audit found

- **Nine distinct corner radii are in use:** 0, 4, 8, 12, 16, 20, 24 and 28 dp, and full. Only two
  Material shape roles (`large` and `extraLarge`) are read by name. Every other radius is a literal
  or a private constant.
- **Nine distinct horizontal insets are in use**, from 4 to 24 dp.
- **Five shapes of «one row of a group»:**
  - `GroupRow` 16 × 12;
  - a clickable `GroupItem` 16 × 8;
  - a non-clickable `GroupItem`, which falls back to Material's own `ListItem` padding;
  - `GroupSliderItem` 16 / 12 / 4;
  - `GroupSegmentedItem` 16 × 12.
- **Three skins of `SegmentedPicker`:**
  - a 4 dp tray on «Календарь»;
  - the same tray clipped again to 24 dp on the diary's tabs, re-creating the guessed-radius bug the
    component was rewritten to remove;
  - no tray at all on «Задания».
- **Full-width pill buttons at two heights:** 56 dp in four places, 52 dp in two.
- **A 24 dp corner declared seven times** under private names, in `AboutCard`, `BugReportSheet`,
  `TranslationEditorSheet` and others, instead of `LessonsShapeTokens.Group`.
- **Two screens inset 20 dp** where every other screen uses `ScreenPadding` (16).
  - Onboarding's acknowledgement card is inset twice, 36 dp from the edge.
  - «О приложении» is a bespoke card with a 20 / 28 dp inner padding.

## Decisions

The owner chose 1 to 4. The rest follow from the audit and were agreed with them.

1. **Cards, groups, fields and the cards inside sheets are 28 dp:** `LessonsShapeTokens.Group` and
   `Hero`, equal to `extraLarge`. The owner chose 24; the guideline check below replaced it.
2. **Standalone tiles and cells are 12 dp.** That covers the weekday tile, the month-grid cell and
   the icon tiles of «Значок приложения». It is a new token, `LessonsShapeTokens.Cell`: Material's
   medium, which this theme's own roles call `small`.
   - A row *inside* a group stays near-square (`Row`, 4 dp), because the group's 28 dp clip does its
     rounding.
3. **A list row's vertical padding is 12 dp.** Every row of a group is padded `RowPadding =
   16 × 12`, the clickable and non-clickable `GroupItem`, `GroupRow`, `GroupSliderItem` and
   `GroupSegmentedItem` alike. The slider keeps 4 dp under its track, because the track is the row's
   content.
4. **A full-width pill button is 56 dp tall:** one token, `PillButtonHeight`.
5. **Every screen is inset `ScreenPadding` (16 dp) from its edges.** That includes onboarding's
   acknowledgement card, inset once, and «О приложении», which becomes a `RoundedCardContainer` with
   rows like every other group.
6. **Full rounding is `LessonsShapeTokens.Pill`.**
   - `CircleShape` stays only on things that are circles: an avatar or a dot.
   - A full-width button, a chip and an attachment pill use `Pill`.
   - Chips have one inner padding, `PillChip`'s two variants. The attachment pill in
     `HomeworkRow` becomes a `PillChip`.
7. **`SegmentedPicker` has one skin everywhere:** its own concentric 4 dp tray on `rowContainer`. The
   diary's extra clip goes, and «Задания» gains the tray.
8. **Edges line up.** `SectionHeader`'s built-in 16 dp start inset is `ScreenPadding`, not a
   literal, and the `ScreenPadding - 16.dp` workaround at four call sites goes.
9. **The radius scale is four values, all Material tokens:**
   - 4: `Row`, extra small;
   - 12: `Cell`, medium;
   - 28: `Group` and `Hero`, extra large, the same as a sheet's and a dialog's own corners;
   - full: `Pill` and `Tile`.
   - Material's `medium` (16) and `large` (20) are no longer read by the app. The class-code field
     moves from `large` to `Group`.
   - 0 dp stays where a month-grid run is flat inside, by design.
10. **The spacing scale is named:**
    - `ScreenPadding` 16;
    - `GroupSpacing` 16;
    - `GroupRowSpacing` 2;
    - `RowPadding` 16 × 12;
    - `CardPadding` 20, the inner padding of a card that holds text rather than rows: the sheets'
      notes, and the hero;
    - `InlineGap` 8, between the items of a row or a chip strip, which is today's most common
      `spacedBy`.

## Checked against the guidelines (the owner asked for this, then for work to start)

The values were checked against Material 3 Expressive as this app ships it: the tokens in
`androidx.compose.material3` 1.5.0-alpha24's `tokens/` sources. Material's own scale is 0, 4, 8,
12, 16, 20, 28, 32, 48 and full. Its components use these values:

- cards: medium, 12;
- chips: small, 8, at 32 dp high;
- text fields: extra small, 4;
- dialogs and bottom sheets: extra large, 28;
- the Medium button: 56 dp high, full or large corners, 24 dp side padding;
- a list item: 12 dp top and bottom padding, 16 at the start and end, and 12 between the leading
  element and the text. Its minimum height is 56 dp for one line, 72 for two and 88 for three;
- an Expressive segmented list: items 2 dp apart, inner corners extra small (4);
- touch targets: at least 48 × 48 dp.

The users of a school diary are children from seven and their parents. Accessibility guidance for
that audience is the same, only more pressing: large touch targets, text that scales with the
system font size without clipping, and one predictable rhythm.

**Kept as the owner chose:**
- **Tiles and cells at 12.** That is Material's medium, the card radius.
- **Rows at 12 dp top and bottom.** That is exactly Material's list item.
- **Full-width buttons at 56.** That is Material's Medium button.

**Changed: groups and cards go from 24 to 28 (`extraLarge`).**
- 24 is on no Material scale. The theme already carries 28 for its sheets and dialogs. So a card
  inside a sheet sat at 24 under a sheet edge at 28: two large radii side by side, where the
  screenshot complained of exactly that.
- With 28, the radius scale shrinks from five values to four: 4 (row), 12 (cell), 28 (container)
  and full. Every one of them is a Material token.
- The cost: every group and card on every screen becomes 4 dp rounder. The reference app,
  Essentials, used 24, and this departs from it. Taking it back is one token.

**Added:**
- **A row is at least 56 dp tall, and never a fixed height** (`heightIn(min = …)`). A row grows
  with the system font size instead of clipping, and it is always a touch target past 48 dp.
- **The gap between a row's leading tile and its text is 12 dp**, Material's, instead of
  `GroupRow`'s 14.
- **A tappable chip keeps a 48 dp touch target** (`minimumInteractiveComponentSize`) whatever its
  drawn height. Chips stay full pills, as the switcher's connected buttons are.
- **Chip padding sits on the 4 dp grid:** 12 × 4 for a status chip and 16 × 8 for a tappable one,
  where it was 10 × 4 and 14 × 8.
- **Every inset and gap is a multiple of 4** (`InlineGap` 8, `CardPadding` 20). A literal off the
  grid needs a reason in its comment.

**Not now:** the wider screen margins Material asks for at medium and expanded widths (24 dp). That
means a margin that depends on the window, and `ScreenPadding` is a constant read in places that
are not composable. It is filed as its own issue.

## What holds it

- **A source scan in `:core:designsystem`'s tests**, in the manner of `StabilityPromiseTest`, fails
  on any of these in `android/app/src/main` or the design system outside `theme/Shape.kt`:
  - a `RoundedCornerShape(` with a literal dp;
  - a `CircleShape` given to a button or a chip;
  - a private constant whose name ends in `Corner`.
  - `@Preview` code is exempt. The allowed exceptions are listed in the test, each with its reason.
- **Compose tests on `GroupItem`** check that its clickable and non-clickable rows measure the same
  height for the same content. The difference between them is what the audit found.
- **Every existing UI test still passes.** A test that pinned an old value is updated, and its
  commit says so.

## What it does not cover

- **How it looks on a device.** Robolectric measures sizes and does not judge looks, so the owner
  looks at «Сегодня», «Календарь», «Задания», the diary, «Оформление», «О приложении» and
  onboarding on the emulator.
- **The widget.** Glance has its own corner handling and its own size ladder, and the widget was
  outside the owner's screenshot. It stays as it is and gets a look of its own later if asked.
- **Typography and colour.** Unchanged.

## How it is built (the owner asked for work to start without reviewing this)

Four tasks on `android/ui-geometry`, each with its own commit and its own review. Every commit
keeps `./gradlew test detekt assembleDebug` green.

1. **The tokens and the guard.**
   - `theme/Shape.kt` gains `Cell`, `RowPadding`, `RowMinHeight`, `RowLeadingGap`,
     `PillButtonHeight`, `CardPadding` and `InlineGap`, and `Group` and `Hero` become 28.
   - `RoundedCardContainer` and `SectionHeader` read the tokens instead of literals.
   - A source-scan test lists today's offenders as a pending allowance, which Tasks 2 and 3 empty.
2. **The design system's components.**
   - `GroupItem` (both overloads), `GroupRow`, `GroupSliderItem` and `GroupSegmentedItem` share
     `RowPadding`, `RowMinHeight` and `RowLeadingGap`.
   - `GroupActionItem` is 56 dp.
   - `PillChip`'s padding moves onto the grid, and a tappable chip gets its 48 dp target.
   - `HomeworkRow`'s attachment pill becomes a `PillChip`.
   - Tests: equal row heights, the minimum height, and the chip's touch target.
3. **The screens.**
   - «Календарь»'s tiles and cells become `Cell`, and `DayAccent`'s fallback is fixed.
   - The diary's extra clip goes, and «Задания» gets the tray.
   - «О приложении» becomes a group, and onboarding's card is inset once.
   - The sheets' private corners and heights become tokens, and `CircleShape` pills become `Pill`.
   - The class-code field becomes `Group`, and the `ScreenPadding - 16.dp` workaround goes.
   - «Значок приложения»'s tiles are renamed and given `Cell`.
4. **The documents.** `docs/design.md` and the honest list of what nobody has looked at on a device.
