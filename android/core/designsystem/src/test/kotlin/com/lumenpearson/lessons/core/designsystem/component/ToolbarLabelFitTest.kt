package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A short label is as wide as its text, and so is the button it is in (#242).
 *
 * The back pill's title was never under 100 dp and the selected tab's label
 * never under 80 dp, so «Sync» sat in the same pill as «Settings» and «Today»
 * in a tab half empty. Asked of the text's own line: the box a label is drawn
 * in is as wide as the line laid out inside it, give or take the pixel the
 * measurement keeps for the round trip through dp.
 *
 * Robolectric's text is not a font's — a line here is a pixel or so a letter —
 * but the label is measured and drawn by the same engine, so a box held wider
 * than its line still shows, at 84 px for a 4 px «Sync». 411 dp wide, because
 * below 330 dp a bar of three tabs and a button draws no label at all.
 *
 * A long one stops growing where the owner put the line (#242): on a phone at
 * the width the row leaves it, on a tablet at 30 % of the window, and scrolls
 * past it. Robolectric's window is drawn at one pixel to the dp, so the widths
 * below are written in dp and compared as pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp")
class ToolbarLabelFitTest {

    @get:Rule
    val compose = createComposeRule()

    // Both labels are drawn through `MarqueeText`, whose clock never idles once
    // a line overflows; held, so no label can hang the test.
    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun `a short title is as wide as its text`() {
        showTitle(ShortTitle)

        val title = compose.onNodeWithText(ShortTitle, useUnmergedTree = true).fetchSemanticsNode()

        assertEquals(
            "«$ShortTitle» is laid out ${title.lineWidth()} px wide in a box of ${title.size.width} px",
            title.lineWidth(),
            title.size.width.toFloat(),
            Slack,
        )
    }

    @Test
    fun `the selected tab is as wide as its icon and its label`() {
        showTabs(selected = ShortLabel)

        val label = compose.onNodeWithText(ShortLabel, useUnmergedTree = true).fetchSemanticsNode()
        val tab = compose
            .onNode(hasText(ShortLabel) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .fetchSemanticsNode()
        val icon = with(compose.density) { IconSlot.toPx() }

        assertEquals(
            "the tab is ${tab.size.width} px for an icon of $icon px and «$ShortLabel» of ${label.lineWidth()} px",
            icon + label.lineWidth(),
            tab.size.width.toFloat(),
            Slack,
        )
    }

    @Test
    fun `a long title on a phone takes the whole row before it scrolls`() {
        showTitle(LongTitle)

        val window = compose.onRoot().fetchSemanticsNode().size.width
        val pill = compose.onNodeWithTag(ToolbarPillTag, useUnmergedTree = true).fetchSemanticsNode().size.width

        // The bar's 16 dp margin on either side and nothing else: no button here.
        assertEquals("the pill is $pill px in a $window px window", window - 32, pill)
    }

    @Test
    @Config(qualifiers = "sw800dp-w1000dp-h800dp")
    fun `a long title on a tablet stops at 30 percent of the window`() {
        showTitle(LongTitle)

        val pill = compose.onNodeWithTag(ToolbarPillTag, useUnmergedTree = true).fetchSemanticsNode().size.width

        // 8 + 48 + 8 before the title, its 8 + 300 + 8, and the pill's own 8.
        assertEquals("the pill is $pill px in a 1000 px window", 388, pill)
    }

    @Test
    @Config(qualifiers = "sw800dp-w1000dp-h800dp")
    fun `a long label on a tablet stops at 30 percent of the window`() {
        showTabs(selected = LongTitle)

        val tab = compose
            .onNode(hasText(LongTitle) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .fetchSemanticsNode()
            .size.width

        assertEquals("the tab is $tab px in a 1000 px window", 48 + 300, tab)
    }

    private fun showTitle(title: String) {
        compose.setContent {
            LessonsTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    LessonsFloatingToolbar(title = title, onBackClick = {})
                }
            }
        }
        compose.settle(frames = 30)
    }

    private fun showTabs(selected: String) {
        compose.setContent {
            LessonsTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    LessonsFloatingToolbar(
                        items = listOf(selected, "Calendar", "Homework").map { label ->
                            ToolbarItem(icon = Icons.Rounded.Settings, label = label) {}
                        },
                        selectedIndex = 0,
                        action = ToolbarAction(Icons.Rounded.Settings, "Settings") {},
                    )
                }
            }
        }
        compose.settle(frames = 60)
    }

    /** The width of the first line as laid out, which is the text's own width. */
    private fun SemanticsNode.lineWidth(): Float {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.single()
        return layout.getLineRight(0) - layout.getLineLeft(0)
    }

    private companion object {

        /** The title in the owner's screenshot. */
        const val ShortTitle = "Sync"

        /** About half of the 80 dp the label used to be held to. */
        const val ShortLabel = "Today"

        /**
         * Longer than any window here at Robolectric's pixel or so a letter, so
         * it reaches whatever cap it is under.
         */
        val LongTitle = "Взаимодействие с приложением ".repeat(40).trim()

        /** An icon-only tab: 48 dp, the icon and the gap before the label inside it. */
        val IconSlot = 48.dp

        /** The pixel the measurement adds, and one for rounding. */
        const val Slack = 2f
    }
}
