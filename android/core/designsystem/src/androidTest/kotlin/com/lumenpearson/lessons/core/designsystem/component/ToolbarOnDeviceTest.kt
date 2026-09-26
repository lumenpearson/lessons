package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tab bar's arranging gesture on a device: the project's first instrumented
 * tests (#110).
 *
 * The JVM tests drive the same gesture under Robolectric, whose densities and
 * `ViewConfiguration` are its own, so they drag ten thousand pixels to land on
 * an answer that holds under any of them. What they could not ask is the one
 * thing a hand notices: whether half a slot, at the device's real density and
 * with its real long-press timeout and touch slop, is where a tab changes
 * place. That is what these drags measure — a fraction of one slot's pitch, in
 * the device's own pixels.
 *
 * The clock is held, as in the JVM tests, and for the same reason: the jiggle
 * and the labels' marquee run for ever, so a clock left to advance on its own
 * would never find the composition idle.
 */
@RunWith(AndroidJUnit4::class)
class ToolbarOnDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    private val labels = listOf("Сегодня", "Календарь", "Задания")

    private var reordering by mutableStateOf(false)
    private var reported: List<Int>? = null
    private var tapped = false
    private var dismissed = 0
    private var pageTapped = false

    @Test
    fun a_long_press_carried_past_half_a_slot_moves_the_tab_one_place() {
        showBar()

        compose.holdAndDrag(labels[0], slots = 0.7f)

        assertTrue("the long press did not open the mode", reordering)
        assertEquals("the same touch did not carry the tab one place", listOf(1, 0, 2), reported)
        assertFalse("the lift that ended the drag was taken for a tap", tapped)
    }

    @Test
    fun a_long_press_carried_short_of_half_a_slot_puts_the_tab_back() {
        showBar()

        compose.holdAndDrag(labels[0], slots = 0.4f)

        assertTrue("the long press did not open the mode", reordering)
        assertNull("a drag short of half a slot reported a new order", reported)
    }

    @Test
    fun a_tap_on_the_page_closes_the_mode_and_reaches_nothing() {
        showBar()
        compose.holdAndDrag(labels[0], slots = 0f)
        assertTrue("the long press did not open the mode", reordering)

        compose.onNodeWithTag(Page).performTouchInput { click(Offset(40f, 40f)) }
        compose.settle()

        assertEquals("the tap outside the bar did not close the mode", 1, dismissed)
        assertFalse("the tap reached the page under the layer", pageTapped)
    }

    private fun showBar() {
        compose.setContent {
            LessonsTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag(Page)
                            .clickable { pageTapped = true },
                    )
                    if (reordering) {
                        ArrangingDismissLayer(onDismiss = {
                            dismissed++
                            reordering = false
                        })
                    }
                    LessonsFloatingToolbar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        items = labels.map { label ->
                            ToolbarItem(icon = Icons.Rounded.Settings, label = label) { tapped = true }
                        },
                        selectedIndex = 0,
                        reorderable = true,
                        reordering = reordering,
                        onReorderingChange = { reordering = it },
                        onReorder = { reported = it },
                    )
                }
            }
        }
        compose.settle()
    }

    /**
     * Down, a wait past the long-press timeout on the composition's clock, and
     * a move of [slots] times the row's pitch in the device's own pixels, in
     * ten steps, then the lift — one touch throughout.
     */
    private fun ComposeContentTestRule.holdAndDrag(label: String, slots: Float) {
        val pitch = with(density) { SlotPitch.toPx() }
        onNodeWithContentDescription(label).performTouchInput { down(center) }
        settle()
        mainClock.advanceTimeBy(LongPressWaitMillis)
        settle()
        repeat(Steps) {
            onNodeWithContentDescription(label).performTouchInput {
                moveBy(Offset(pitch * slots / Steps, 0f))
            }
            settle(frames = 2)
        }
        onNodeWithContentDescription(label).performTouchInput { up() }
        settle(frames = 60)
    }

    /** Frames by hand; see the JVM tests' `settle` for why the notification comes first. */
    private fun ComposeContentTestRule.settle(frames: Int = 6) {
        Snapshot.sendApplyNotifications()
        repeat(frames) { mainClock.advanceTimeByFrame() }
    }

    private companion object {
        const val Page = "page"
        const val Steps = 10

        /** Past any platform's long-press timeout, which is 400–500 ms. */
        const val LongPressWaitMillis = 1_000L

        /** One tab and the gap after it, as `ToolbarItems` counts a slot. */
        val SlotPitch = 56.dp
    }
}
