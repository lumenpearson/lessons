package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion
import com.lumenpearson.lessons.core.designsystem.theme.MotionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One bar, morphing from one page's form into the next (#264).
 *
 * The shell draws a single bar now and hands it each page's form in turn, the
 * way it hands a page its content. These ask what that needs of the bar:
 * that the form on its way out goes on showing what it showed — the shell
 * passes no tabs at all to a settings page, and `AnimatedContent` composes the
 * leaving face with whatever it is given now — that a title gives way to the
 * next rather than being swapped under the arrow, and that the button beside
 * the pill grows in rather than rebuilding the bar round it.
 *
 * 411 dp wide, because below 330 dp three tabs and a button draw no label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp")
class ToolbarMorphTest {

    @get:Rule
    val compose = createComposeRule()

    // Labels and titles are `MarqueeText`, whose clock never idles once a line
    // overflows. Held, and every frame stepped by hand.
    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    private var page by mutableStateOf(Page(tabs = true, title = null, action = Settings))

    @Test
    fun `the tabs stay on the pill while it becomes a back button`() {
        show()
        compose.settle(frames = 60)

        page = Page(tabs = false, title = "Today", action = null)
        compose.settle(frames = 2)
        val leaving = tabsShown()
        compose.settle(frames = 240)

        assertTrue("the tabs were gone two frames into the morph", leaving)
        assertTrue("the tabs were still on the pill after the morph", !tabsShown())
    }

    @Test
    fun `one title gives way to the next`() {
        page = Page(tabs = false, title = "Settings", action = null)
        show()
        compose.settle(frames = 60)

        page = Page(tabs = false, title = "Notifications", action = null)
        compose.settle(frames = 2)
        val both = titleShown("Settings") && titleShown("Notifications")
        compose.settle(frames = 240)

        assertTrue("the old title was swapped for the new one rather than giving way to it", both)
        assertTrue("the old title stayed", !titleShown("Settings"))
        assertTrue("the new title never arrived", titleShown("Notifications"))
    }

    @Test
    fun `the button beside the pill grows in, moving the pill on its way`() {
        page = Page(tabs = false, title = "Settings", action = null)
        show()
        compose.settle(frames = 60)
        val before = pillCentre()

        page = Page(tabs = false, title = "Settings", action = Debug)
        compose.settle(frames = MidMorphFrames)
        val during = pillCentre()
        compose.settle(frames = 240)
        val after = pillCentre()

        assertTrue("the pill did not move to make room: $before then $after", after < before)
        assertTrue(
            "mid-morph, the pill was at $during, not on its way from $before to $after",
            during < before && during > after,
        )
    }

    /**
     * Back from settings, the pill is its own height again. Material's pill
     * pads its content by the interactive alignment lines it finds in it, and
     * the back button leaving slid its line off to the side: the pill came back
     * to the tabs as a tall oval and stayed one until something measured it
     * again. The owner filmed it on 3 October.
     */
    @Test
    fun `back from a sub-page the pill is its own height`() {
        page = Page(tabs = false, title = "Today", action = null)
        show()
        compose.settle(frames = 60)

        page = Page(tabs = true, title = null, action = Settings)
        compose.settle(frames = 240)

        val pill = compose.onNodeWithTag(ToolbarPillTag, useUnmergedTree = true).fetchSemanticsNode().size.height
        val expected = with(compose.density) { FloatingToolbarDefaults.ContainerSize.roundToPx() }
        assertEquals("the pill came back from the sub-page $pill px tall", expected, pill)
    }

    /**
     * The tabs in a new order are the same tabs. Arranged and confirmed, the
     * shell hands the bar the new order, and while the face was keyed by the
     * order, that was a new face: the old row faded out over the new one, and
     * every icon seemed to move again after the drop.
     */
    @Test
    fun `the same tabs in a new order are not a new face`() {
        show()
        compose.settle(frames = 60)

        order = listOf(2, 0, 1)
        compose.settle(frames = 2)
        val drawn = compose.onAllNodesWithContentDescription(Tabs[1].label).fetchSemanticsNodes().size

        assertEquals("a reordered row was drawn twice, the old order fading over the new", 1, drawn)
    }

    @Test
    fun `with animations off the bar takes each form at once`() {
        motion = MotionSettings(enabled = false)
        show()
        compose.settle(frames = 60)

        page = Page(tabs = false, title = "Today", action = Debug)
        compose.settle(frames = 3)
        val pill = pillCentre()
        compose.settle(frames = 240)

        assertTrue("the tabs lingered with animations off", !tabsShown())
        assertEquals("the pill was still moving with animations off", pillCentre(), pill, 0.5f)
    }

    private var motion by mutableStateOf(MotionSettings())

    private var order by mutableStateOf(listOf(0, 1, 2))

    private fun show() {
        compose.setContent {
            LessonsTheme {
                CompositionLocalProvider(LocalMotion provides motion) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LessonsFloatingToolbar(
                            items = if (page.tabs) order.map { Tabs[it] } else emptyList(),
                            selectedIndex = if (page.tabs) 0 else -1,
                            title = page.title,
                            onBackClick = if (page.tabs) null else ({}),
                            action = page.action,
                        )
                    }
                }
            }
        }
    }

    private fun tabsShown(): Boolean =
        compose.onAllNodesWithContentDescription(Tabs[1].label).fetchSemanticsNodes().isNotEmpty()

    private fun titleShown(title: String): Boolean =
        compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()

    private fun pillCentre(): Float =
        compose.onNodeWithTag(ToolbarPillTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x

    private data class Page(val tabs: Boolean, val title: String?, val action: ToolbarAction?)

    private companion object {

        /** Into the morph, past the frame `AnimatedContent` takes to measure what arrives. */
        const val MidMorphFrames = 6

        val Tabs = listOf("Today", "Calendar", "Homework").map { label ->
            ToolbarItem(icon = Icons.Rounded.Settings, label = label) {}
        }

        val Settings = ToolbarAction(Icons.Rounded.Settings, "Settings action") {}

        val Debug = ToolbarAction(Icons.Rounded.BugReport, "Debug action") {}
    }
}
