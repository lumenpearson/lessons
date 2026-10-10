package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.RowMinHeight
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// marquee clock: every title, subtitle and chip label here is one short word or
// phrase — «Класс», «Пн», «замена» — well inside the width Robolectric gives a
// row or a chip at rest, so none of them overflows into basicMarquee.

/** [DpRect] carries no `width`/`height` of its own — just the four edges. */
private val DpRect.width: Dp get() = right - left
private val DpRect.height: Dp get() = bottom - top

/**
 * The row-padding defect the 2026-10-10 geometry audit found in `GroupItem`
 * (`docs/specs/2026-10-10-ui-geometry-design.md`, «What the audit found»): a
 * clickable `GroupItem` read `RowPadding`, and a read-only one fell through to
 * Material's own `ListItemDefaults.ContentPadding` — not the same value — so
 * two rows of the same group, side by side, with the same title and subtitle,
 * measured different heights depending only on whether one of them happened
 * to be tappable. Measured rather than read off the source, because the defect
 * was never a wrong token, it was that one of the two paths named none at all.
 *
 * It fails against that code: with no `contentPadding` on the read-only
 * branch, the two rows below measure 94 and 90 dp.
 */
@RunWith(RobolectricTestRunner::class)
class GroupItemHeightParityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a clickable row and a read-only row of the same content measure the same height`() {
        compose.setContent {
            LessonsTheme {
                RoundedCardContainer {
                    GroupItem(
                        title = "Оформление",
                        subtitle = "Тема, цвета, чёрный фон",
                        icon = Icons.Rounded.Palette,
                        tone = accentTone(0),
                        onClick = {},
                        modifier = Modifier.testTag(Clickable),
                    )
                    GroupItem(
                        title = "Оформление",
                        subtitle = "Тема, цвета, чёрный фон",
                        icon = Icons.Rounded.Palette,
                        tone = accentTone(0),
                        modifier = Modifier.testTag(ReadOnly),
                    )
                }
            }
        }

        val clickable = compose.onNodeWithTag(Clickable).getUnclippedBoundsInRoot()
        val readOnly = compose.onNodeWithTag(ReadOnly).getUnclippedBoundsInRoot()
        assertEquals(
            "A clickable row and a read-only row of the same title and subtitle must measure " +
                "the same height — they are padded by the same RowPadding token on both branches " +
                "of GroupItem. Clickable was ${clickable.height.value} dp, read-only was " +
                "${readOnly.height.value} dp.",
            clickable.height.value,
            readOnly.height.value,
            0.5f,
        )
    }

    private companion object {
        const val Clickable = "clickable"
        const val ReadOnly = "readOnly"
    }
}

/**
 * A two-line row is Material's two-line list item, 72 dp, whichever overload
 * of `ListItem` draws it.
 *
 * `ListItem` floors itself by line count — 56, 72 or 88 dp — but only while
 * nothing outside it has asked for a minimum height of its own: its measure
 * policy takes Material's figure when the incoming `minHeight` is zero and the
 * caller's otherwise, «the same behavior as Modifier.defaultMinSize». A
 * `heightIn(min = RowMinHeight)` on `GroupItem` was such a minimum. It reached
 * `ListItem` as 56 dp less `RowPadding`'s 24, replaced the two-line 72 with
 * it, and a two-line `GroupItem` measured 64 dp beside a two-line
 * `GroupSwitchItem` at 72: the same title and subtitle at two heights, on the
 * same settings page.
 *
 * [GraphicsMode.Mode.NATIVE], because only real text metrics leave the
 * content short enough for a minimum to decide anything. The icon is there
 * because every settings row carries one, and its 40 dp tile is exactly the
 * content height that made the wrong floor read as 64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TwoLineRowHeightTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a two-line GroupItem and a two-line GroupSwitchItem measure the same, at least 72 dp`() {
        compose.setContent {
            LessonsTheme {
                RoundedCardContainer {
                    GroupItem(
                        title = "Оформление",
                        subtitle = "Тема и цвета",
                        icon = Icons.Rounded.Palette,
                        tone = accentTone(0),
                        onClick = {},
                        modifier = Modifier.testTag(Item),
                    )
                    GroupSwitchItem(
                        title = "Оформление",
                        subtitle = "Тема и цвета",
                        icon = Icons.Rounded.Palette,
                        tone = accentTone(0),
                        checked = false,
                        onCheckedChange = {},
                        modifier = Modifier.testTag(Switch),
                    )
                }
            }
        }

        val item = compose.onNodeWithTag(Item).getUnclippedBoundsInRoot().height.value
        val switch = compose.onNodeWithTag(Switch).getUnclippedBoundsInRoot().height.value
        assertEquals(
            "A two-line GroupItem and a two-line GroupSwitchItem of the same content must measure " +
                "the same height; GroupItem was $item dp, GroupSwitchItem was $switch dp.",
            switch,
            item,
            0.5f,
        )
        assertTrue(
            "A two-line row must keep Material's two-line minimum of 72 dp; it measured $item dp.",
            item >= TwoLineMinimum - 0.5f,
        )
    }

    private companion object {
        const val Item = "item"
        const val Switch = "switch"

        /** `ListTokens.ItemTwoLineContainerHeight`, which is internal to Material. */
        const val TwoLineMinimum = 72f
    }
}

/**
 * The floor every row of a group is held to: 56 dp, the Material measure for a
 * one-line list item — and, unlike a fixed height, one that only ever grows,
 * so a row never clips at a larger system font size
 * (`docs/specs/2026-10-10-ui-geometry-design.md`, «Added»). The hand-built rows
 * carry it as `RowMinHeight`; `GroupItem` gets it from `ListItem`, which floors
 * itself by line count.
 *
 * [GraphicsMode.Mode.NATIVE], because under Robolectric's `LEGACY` default a
 * line of text measures tall enough to clear 56 dp on its own, and the
 * `GroupItem` and `GroupRow` tests here passed there with no floor at all.
 * With real metrics both of those rows are shorter than 56 dp without one, so
 * each fails when its floor goes. The slider and the segmented tests pass with
 * or without theirs, and each says why.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RowMinimumHeightTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * No icon, so the content is one line of text and the 56 dp is
     * `ListItem`'s own one-line minimum. It fails when anything outside
     * `ListItem` asks for a smaller minimum, which `ListItem` then takes
     * instead of its own: a `heightIn(min = 48.dp)` on `GroupItem` measures
     * 48.
     */
    @Test
    fun `a one-line GroupItem is at least 56 dp tall`() {
        compose.setContent {
            LessonsTheme {
                RoundedCardContainer {
                    GroupItem(
                        title = "Класс",
                        tone = accentTone(0),
                        onClick = {},
                        modifier = Modifier.testTag(Tag),
                    )
                }
            }
        }

        assertAtLeastRowMinHeight(compose.onNodeWithTag(Tag).getUnclippedBoundsInRoot().height.value)
    }

    /**
     * `RowMinHeight` is this row's only floor: without it the row is its one
     * line of text plus `RowPadding`, under 56 dp.
     */
    @Test
    fun `a one-line GroupRow is at least 56 dp tall`() {
        compose.setContent {
            LessonsTheme {
                RoundedCardContainer {
                    GroupRow(modifier = Modifier.testTag(Tag)) {
                        Text("Класс")
                    }
                }
            }
        }

        assertAtLeastRowMinHeight(compose.onNodeWithTag(Tag).getUnclippedBoundsInRoot().height.value)
    }

    /**
     * `GroupSliderItem` is a hand-built row, so `RowMinHeight` is its own floor
     * rather than `ListItem`'s.
     *
     * **This passes with or without that floor, and that was checked rather
     * than assumed.** Material 3 1.5.0-alpha24's own sources, for the two
     * controls the row always holds: a plain `IconButton` has a 40 dp
     * container inside a 48 dp touch target
     * (`IconButtonDefaults.smallContainerSize()`), and a horizontal `Slider`'s
     * thumb is 44 dp (`SliderTokens.HandleHeight`). Both sit in one line under
     * the title, and no parameter removes either, so nothing passed here
     * brings the row anywhere near 56 dp. It holds the row to the floor the
     * day those controls change; it cannot fail on today's.
     */
    @Test
    fun `a GroupSliderItem is at least 56 dp tall`() {
        compose.setContent {
            LessonsTheme {
                RoundedCardContainer {
                    GroupSliderItem(
                        title = "Размытие",
                        tone = accentTone(0),
                        value = 0.5f,
                        onValueChange = {},
                        modifier = Modifier.testTag(Tag),
                    )
                }
            }
        }

        assertAtLeastRowMinHeight(compose.onNodeWithTag(Tag).getUnclippedBoundsInRoot().height.value)
    }

    /**
     * The same as [GroupSliderItem], for the same kind of reason: a plain
     * `ToggleButton` — what `SegmentedPicker` builds each segment from —
     * carries its own `.defaultMinSize(minHeight = ToggleButtonDefaults
     * .MinHeight)`, 40 dp (`ButtonSmallTokens.ContainerHeight`), underneath
     * whatever `contentPadding` is passed to it; `SegmentedPicker` passes one,
     * but it cannot shrink that floor. Under the title, no choice of `title`
     * or `items` brings this row under 56 dp, so this too passes with or
     * without `RowMinHeight`.
     */
    @Test
    fun `a GroupSegmentedItem is at least 56 dp tall`() {
        compose.setContent {
            LessonsTheme {
                RoundedCardContainer {
                    GroupSegmentedItem(
                        title = "Вибрация",
                        tone = accentTone(0),
                        items = listOf("Нет", "Лёгкая"),
                        selectedItem = "Нет",
                        onItemSelected = {},
                        labelProvider = { it },
                        modifier = Modifier.testTag(Tag),
                    )
                }
            }
        }

        assertAtLeastRowMinHeight(compose.onNodeWithTag(Tag).getUnclippedBoundsInRoot().height.value)
    }

    private fun assertAtLeastRowMinHeight(measured: Float) {
        assertTrue(
            "A row must be at least ${RowMinHeight.value} dp tall; measured $measured dp.",
            measured >= RowMinHeight.value - 0.5f,
        )
    }

    private companion object {
        const val Tag = "row"
    }
}

/**
 * [PillChip]'s own touch-target rule: a tappable chip keeps a 48 dp target
 * whatever its drawn height, a static one does not grow at all — it is never
 * pressed, so Material's touch-target floor does not apply to it
 * (`docs/specs/2026-10-10-ui-geometry-design.md`, "Added").
 *
 * The tappable case is measured through `SemanticsNode.touchBoundsInRoot`
 * rather than `getUnclippedBoundsInRoot`: this Compose expands the *touch*
 * bounds a pointer is tested against, not the layout bounds the row occupies
 * on screen — `getUnclippedBoundsInRoot` on this chip reads 35 dp wide
 * regardless, which is what sent this test looking for the touch-bounds API
 * instead. And it asks "at least 48", not "equal to 48": a chip's own
 * content can already be taller than 48 dp (this one measures 51 dp tall
 * before any expansion, from label-small text under a custom font's real
 * metrics), and the floor has nothing to raise there — only the narrower
 * dimension, the 35 dp width, is actually pushed out to 48.
 */
@RunWith(RobolectricTestRunner::class)
class PillChipTouchTargetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a tappable chip's touch target is at least 48 by 48 dp`() {
        compose.setContent {
            LessonsTheme {
                PillChip(text = "Пн", onClick = {}, modifier = Modifier.testTag(Tag))
            }
        }

        val node = compose.onNodeWithTag(Tag).fetchSemanticsNode()
        val density = node.layoutInfo.density
        val touch = node.touchBoundsInRoot
        val width = with(density) { touch.width.toDp() }
        val height = with(density) { touch.height.toDp() }

        assertTrue("touch width must be at least 48 dp, was ${width.value}", width.value >= 47.5f)
        assertTrue("touch height must be at least 48 dp, was ${height.value}", height.value >= 47.5f)
    }

    @Test
    fun `a static chip keeps its drawn size, under the touch target floor`() {
        compose.setContent {
            LessonsTheme {
                PillChip(text = "замена", modifier = Modifier.testTag(Tag))
            }
        }

        val bounds = compose.onNodeWithTag(Tag).getUnclippedBoundsInRoot()
        assertTrue(
            "a static chip has no touch target to raise, so it must stay under the 48 dp floor a " +
                "tappable chip is raised to; measured ${bounds.height.value} dp",
            bounds.height.value < 47.5f,
        )
    }

    private companion object {
        const val Tag = "chip"
    }
}
