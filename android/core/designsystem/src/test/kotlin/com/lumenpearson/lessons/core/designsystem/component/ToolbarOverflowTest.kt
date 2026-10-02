package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the toolbar does when it is handed more destinations than fit.
 *
 * Three tabs were the only case for as long as the bar had one caller. The
 * documentation has eight sections and the bar is its only navigation, and
 * eight items plus a label plus the action button is roughly 570 dp of content
 * asking for a 360 dp phone. Without the scrolling mode that is not a layout
 * that merely looks cramped — it is a pill wider than the screen, with the one
 * button that leaves the screen pushed off the edge of it.
 *
 * The assertions are about the shape rather than the pixels: everything stays
 * inside the window, and the button beside the pill is still there to press.
 */
// marquee clock: the labels are «Раздел 0» … «Раздел 7», eight characters,
// and a toolbar tab's label is drawn at its own width up to what the scrolling
// window leaves beside its icon — well over a hundred dp in this window — so
// no label here can overflow and start the marquee (#242). The long strings
// here are assertion messages.
@RunWith(RobolectricTestRunner::class)
class ToolbarOverflowTest {

    @get:Rule
    val compose = createComposeRule()

    private val manySections = List(SectionCount) { index ->
        ToolbarItem(icon = Icons.Rounded.Settings, label = "Раздел $index") {}
    }

    @Test
    fun `eight destinations and a button stay inside the window`() {
        showToolbar(selectedIndex = 0)

        val window = compose.onRoot().fetchSemanticsNode().size.width
        val button = compose.onNodeWithContentDescription(ActionLabel).fetchSemanticsNode()
        val right = button.positionInRoot.x + button.size.width

        assertTrue(
            "The action button ends at $right px, past a $window px window",
            right <= window,
        )
    }

    /**
     * The button is the way out of the documentation. If the pill ever pushed
     * it off, that screen would have no exit but the system gesture — which is
     * exactly the "two ways out, one of them missing" this arrangement exists
     * to avoid.
     */
    @Test
    fun `the action button survives a selection late in the row`() {
        showToolbar(selectedIndex = SectionCount - 1)

        compose.onNodeWithContentDescription(ActionLabel).assertExists()
    }

    /**
     * A section scrolled half out of view goes under the pill's round end (#183).
     *
     * Every scroll container clips to a rectangle along its axis, and there are
     * two here — Material scrolls the content of a pill with a button beside it,
     * and the row scrolls its sections — so the edge a section is cut by is the
     * nearer of their two windows. While Material's padding sat outside both,
     * both windows began exactly where the first section did: 8 dp inside the
     * pill, a straight line, and the round end the pill clips itself to was
     * never reached. Asked of the windows rather than of the pixels, because a
     * JVM test does not draw.
     */
    @Test
    fun `no scroll window cuts the row short of the pill's end`() {
        showToolbar(selectedIndex = 0)

        val first = compose.onNodeWithContentDescription("Раздел 0").fetchSemanticsNode().boundsInRoot
        val windows = compose
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
                useUnmergedTree = true,
            )
            .fetchSemanticsNodes()
        val endPadding = with(compose.density) { 8.dp.toPx() }

        assertTrue("found no scrolling window to ask", windows.isNotEmpty())
        for (window in windows) {
            val before = first.left - window.boundsInRoot.left
            assertTrue(
                "A scroll window begins $before px before the first section, " +
                    "inside the pill's $endPadding px end, and cuts it with a straight edge",
                before >= endPadding - 0.5f,
            )
        }
    }

    private fun showToolbar(selectedIndex: Int) {
        compose.setContent {
            LessonsTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    LessonsFloatingToolbar(
                        items = manySections,
                        selectedIndex = selectedIndex,
                        scrollableItems = true,
                        action = ToolbarAction(Icons.Rounded.Settings, ActionLabel) {},
                    )
                }
            }
        }
    }

    private companion object {

        /** As many sections as the documentation has. */
        const val SectionCount = 8

        const val ActionLabel = "Назад"
    }
}
