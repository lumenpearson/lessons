package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Long-pressing a tab puts the bar into the mode where its tabs can be moved.
 *
 * What is asserted here is the *contract*, not the look: which callback fires
 * on which gesture, and that a tap means two different things inside the mode
 * and outside it. Whether the icons wobble convincingly is not something a JVM
 * test can be asked — `Modifier.jiggling` is held instead by the one thing that
 * can be checked without a screen, which is that it starts no animation at all
 * when the mode is closed.
 *
 * The drag itself is deliberately not driven from here. Robolectric lays text
 * out at roughly a pixel a glyph and reports its own densities, so a synthetic
 * swipe of «half a slot» would be measuring this environment rather than the
 * gesture; `ToolbarReorderTest` asks the arithmetic directly instead, in
 * pixels, with no composition at all.
 */
@RunWith(RobolectricTestRunner::class)
class ToolbarReorderModeTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun holdTheClock() {
        // The bar draws `MarqueeText`, which runs for ever, and in reorder mode
        // it also jiggles, which runs for ever — so Compose's clock is never
        // idle and `waitForIdle` would hang rather than fail, taking the whole
        // Gradle run with it. See `MarqueeClockTest`.
        compose.mainClock.autoAdvance = false
    }

    private val labels = listOf("Сегодня", "Календарь", "Задания")

    @Test
    fun `a long press opens the mode, and an ordinary tap does not`() {
        var reordering: Boolean? = null
        var tapped: String? = null
        setBar(reorderingNow = false, onReordering = { reordering = it }, onTap = { tapped = it })

        compose.onNodeWithContentDescription(labels[1]).performClick()
        compose.settle()

        assertEquals("a tap outside the mode has to navigate", labels[1], tapped)
        assertNull("and it must not open the reordering mode", reordering)

        compose.longPress(labels[1])

        assertEquals("a long press has to open the mode", true, reordering)
    }

    @Test
    fun `a long press does nothing when the caller has not asked for it`() {
        // The documentation's bar uses the same component, and its order is the
        // document's rather than the reader's. A mode that opened there would
        // let somebody rearrange a table of contents into one the text no
        // longer matches.
        var reordering: Boolean? = null
        setBar(reorderable = false, reorderingNow = false, onReordering = { reordering = it })

        compose.longPress(labels[0])

        assertNull("the mode opened on a bar that is not reorderable", reordering)
    }

    @Test
    fun `inside the mode a tap closes it rather than navigating`() {
        // The iOS gesture is a tap on the wallpaper; there is no wallpaper
        // under a floating bar, so the bar takes the tap. Navigating instead
        // would land the reader on a page they did not ask for, with the bar
        // still wobbling.
        var reordering: Boolean? = null
        var tapped: String? = null
        setBar(reorderingNow = true, onReordering = { reordering = it }, onTap = { tapped = it })

        compose.onNodeWithContentDescription(labels[2]).performClick()
        compose.settle()

        assertEquals("a tap inside the mode has to close it", false, reordering)
        assertNull("and it must not navigate", tapped)
    }

    @Test
    fun `every tab is still reachable by name in the mode`() {
        // The labels are dropped while the tabs are being arranged — they are
        // all one width, which is what makes the drag arithmetic a single
        // pitch — and a screen reader must not lose them with the pixels.
        setBar(reorderingNow = true)

        for (label in labels) {
            compose.onNodeWithContentDescription(label).assertExists()
        }
    }

    @Test
    fun `closing the mode does not itself report an order`() {
        // Opening and closing without dragging anything is the ordinary way to
        // change one's mind. A permutation raised there would be a write to the
        // preferences file for a gesture that moved nothing.
        var reordered: List<Int>? = null
        var reorderingNow by mutableStateOf(false)
        compose.setContent {
            LessonsTheme {
                LessonsFloatingToolbar(
                    items = labels.map { label ->
                        ToolbarItem(icon = Icons.Rounded.Settings, label = label) {}
                    },
                    selectedIndex = 0,
                    reorderable = true,
                    reordering = reorderingNow,
                    onReorderingChange = { reorderingNow = it },
                    onReorder = { reordered = it },
                )
            }
        }
        compose.settle()

        compose.longPress(labels[0])
        assertTrue("the mode did not open", reorderingNow)

        compose.onNodeWithContentDescription(labels[0]).performClick()
        compose.settle()

        assertFalse("the mode did not close", reorderingNow)
        assertNull("an order was reported for a gesture that moved nothing", reordered)
    }

    private fun setBar(
        reorderable: Boolean = true,
        reorderingNow: Boolean,
        onReordering: (Boolean) -> Unit = {},
        onTap: (String) -> Unit = {},
    ) {
        compose.setContent {
            LessonsTheme {
                LessonsFloatingToolbar(
                    items = labels.map { label ->
                        ToolbarItem(icon = Icons.Rounded.Settings, label = label) { onTap(label) }
                    },
                    selectedIndex = 0,
                    reorderable = reorderable,
                    reordering = reorderingNow,
                    onReorderingChange = onReordering,
                )
            }
        }
        compose.settle()
    }
}

/**
 * Frames by hand, because the clock is held.
 *
 * `sendApplyNotifications` first: a value written from the test thread lands in
 * the global snapshot and nothing wakes the recomposer on its own, so advancing
 * frames alone reads the value that was there before the gesture. See
 * `CLAUDE.md`, which records what that cost to find.
 */
private fun ComposeContentTestRule.settle(frames: Int = 6) {
    Snapshot.sendApplyNotifications()
    repeat(frames) { mainClock.advanceTimeByFrame() }
}

/**
 * A press held long enough to be a long press, with the clock stopped.
 *
 * Not `performTouchInput { longClick() }`, which is what this was first: that
 * helper puts the wait inside the *gesture's* timeline, and the timeout it has
 * to outlast is a `withTimeout` running on the composition's clock — which is
 * held here, so the press was released before the coroutine had aged a
 * millisecond and the long press simply never happened. The two clocks have to
 * be advanced separately, and this is the down, the wait and the up spelled out
 * so that the wait lands on the right one.
 */
private fun ComposeContentTestRule.longPress(label: String) {
    onNodeWithContentDescription(label).performTouchInput { down(center) }
    settle()
    mainClock.advanceTimeBy(LongPressMillis)
    settle()
    onNodeWithContentDescription(label).performTouchInput { up() }
    settle()
}

/** Comfortably past any platform's long-press timeout, which is 400–500 ms. */
private const val LongPressMillis = 1_000L
