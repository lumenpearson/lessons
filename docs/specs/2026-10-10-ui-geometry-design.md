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

1. **Cards, groups, fields and the cards inside sheets are 24 dp:** `LessonsShapeTokens.Group`.
2. **Standalone tiles and cells are 12 dp.** That covers the weekday tile, the month-grid cell and
   the icon tiles of «Значок приложения». It is a new token, `LessonsShapeTokens.Cell`, equal to
   Material's `small`.
   - A row *inside* a group stays near-square (`Row`, 4 dp), because the group's 24 dp clip does its
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
9. **The radius scale is five values:** 4 (`Row`), 12 (`Cell`), 24 (`Group`), 28 (`extraLarge`,
   the bottom sheet's own top corners alone) and full (`Pill`, `Tile`).
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
