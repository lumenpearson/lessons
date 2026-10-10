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

// marquee clock: every title, subtitle and chip label here is one short word or
// phrase — «Класс», «Пн», «замена» — well inside the width Robolectric gives a
// row or a chip at rest, so none of them overflows into basicMarquee.

/** [DpRect] carries no `width`/`height` of its own — just the four edges. */
private val DpRect.width: Dp get() = right - left
private val DpRect.height: Dp get() = bottom - top

/**
 * The one row-padding defect the 2026-10-10 geometry audit named by number
 * (`docs/specs/2026-10-10-ui-geometry-design.md`, §3.1): a clickable `GroupItem`
 * read `RowPadding`, and a read-only one fell through to Material's own
 * `ListItemDefaults.ContentPadding` — not the same value — so two rows of the
 * same group, side by side, with the same title and subtitle, measured
 * different heights depending only on whether one of them happened to be
 * tappable. Measured rather than read off the source, because the defect was
 * never a wrong token, it was that one of the two paths named none at all.
 *
 * Before Task 2's fix this test failed: the non-clickable branch of `GroupItem`
 * passed no `contentPadding`, `ListItemDefaults.ContentPadding` differs from
 * `RowPadding`, and the two rows below measured different heights — confirmed
 * by staging that revert and running this test red before restoring the fix
 * (see the Task 2 report's red/green evidence).
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
 * The floor every row of a group is held to: `RowMinHeight`, 56 dp, the
 * Material measure for a one-line list item — and, unlike a fixed height,
 * one that only ever grows, so a row never clips at a larger system font
 * size (`docs/specs/2026-10-10-ui-geometry-design.md`, "Added").
 */
@RunWith(RobolectricTestRunner::class)
class RowMinimumHeightTest {

    @get:Rule
    val compose = createComposeRule()

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
     * Task 2's review named this the one Major gap: `GroupSliderItem` is on
     * the design doc's own list of rows that share `RowMinHeight`, but its
     * outer `Column` carried no floor at all. Latent today — a slider plus
     * two 48 dp `IconButton`s is already well over 56 dp — which is exactly
     * why only a measurement, not a reading of the source, would have caught
     * its absence.
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

    /** Same Major gap as [GroupSliderItem], same reason, same fix. */
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
