package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The drag itself: what the bar reports after a finger has actually moved.
 *
 * `ToolbarReorderTest` asks the arithmetic in pixels and `ToolbarReorderModeTest`
 * asks which callback a press raises; between them sat the one thing neither
 * could see — whether the number the gesture computes is the number the gesture
 * *reports*. It was not.
 *
 * What makes this testable at all, where a «half a slot» drag would not be, is
 * that every drag here is enormous. `dropIndex` parks a finger that has left the
 * bar at the far end, so a drag of ten thousand pixels lands on the last slot
 * under any density Robolectric decides to report, and the assertion is about
 * the permutation rather than about a distance.
 */
@RunWith(RobolectricTestRunner::class)
class ToolbarDragTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun holdTheClock() {
        // `MarqueeText` and the jiggle both run for ever; see `MarqueeClockTest`.
        compose.mainClock.autoAdvance = false
    }

    private val labels = listOf("Сегодня", "Календарь", "Задания")

    @Test
    fun `a tab dragged to the far end is reported in its new place`() {
        // The defect this closes: the drag handlers live in a `pointerInput`
        // keyed on `Unit`, so the coroutine that reads the finger keeps the
        // closures from the composition that started it — and the landing slot
        // was a `val` computed in that composition, which is to say «nothing is
        // being dragged». Every drop therefore reported the order unchanged.
        // On the screen the icons slid correctly the whole time, because *that*
        // is recomputed every frame, so the gesture looked like it worked and
        // then quietly did nothing.
        var reported: List<Int>? = null
        setBar(onReorder = { reported = it })

        compose.dragFarRight(labels[0])

        assertEquals("the drag reported nothing", listOf(1, 2, 0), reported)
    }

    @Test
    fun `and the row is drawn in the order the drag left it`() {
        // The permutation and the pixels are two answers to one question, and
        // the second is the one the reader sees. `translationX` is a
        // `graphicsLayer`, so what is asserted here is the slot each tab was
        // laid out in rather than where an unfinished spring has it.
        setBar()

        compose.dragFarRight(labels[0])

        assertEquals(listOf(labels[1], labels[2], labels[0]), compose.drawnOrder(labels))
    }

    @Test
    fun `a second drag is reported against the list the bar was handed`() {
        // The caller latches the order it opened the mode with, so two drags in
        // one session both describe permutations of that same list. A bar that
        // renumbered itself after the first would make the second a permutation
        // of a list neither side still had.
        var reported: List<Int>? = null
        setBar(onReorder = { reported = it })

        compose.dragFarRight(labels[0])
        compose.dragFarRight(labels[1])

        assertEquals(listOf(2, 0, 1), reported)
        assertEquals(listOf(labels[2], labels[0], labels[1]), compose.drawnOrder(labels))
    }

    @Test
    fun `a list handed back in the order the drag asked for is not permuted twice`() {
        // The bar keeps the reader's drag as a permutation of the items it was
        // *handed*, so the two have to be read together — and a caller that
        // commits the new order and hands it back is handing back a list the
        // old permutation no longer describes. Applying it again reorders an
        // order, which is the drag appearing to jump somewhere nobody asked
        // for.
        //
        // The caller in `:app` latches the list for the length of the mode and
        // so never does this mid-gesture; what it cannot avoid is the frame the
        // mode closes on, where the list and the permutation are replaced one
        // after the other. Keying the permutation to the list it belongs to
        // settles both at once, and it is this — a list swapped under a live
        // permutation — that either holds or does not.
        var order by mutableStateOf(labels)
        compose.setContent {
            LessonsTheme {
                LessonsFloatingToolbar(
                    items = order.map { label ->
                        ToolbarItem(icon = Icons.Rounded.Settings, label = label) {}
                    },
                    selectedIndex = 0,
                    reorderable = true,
                    reordering = true,
                    onReorder = { moved -> order = moved.map { order[it] } },
                )
            }
        }
        compose.settle()

        compose.dragFarRight(labels[0])

        assertEquals(listOf(labels[1], labels[2], labels[0]), order)
        assertEquals(order, compose.drawnOrder(labels))
    }

    @Test
    fun `a long press carries straight on into a drag, in the same touch`() {
        // #181. It used to take two touches: the tab's clickable kept the long
        // press for itself and consumed the rest of the gesture, and the drag
        // detector was only added once the mode it belonged to was open, so it
        // never saw the press that opened it. The finger lifted, pressed again
        // and only then carried anything.
        var reported: List<Int>? = null
        var tapped = false
        val reordering = setBar(open = false, onReorder = { reported = it }, onTap = { tapped = true })

        compose.longPressAndDragFarRight(labels[0])

        assertTrue("the long press did not open the mode", reordering())
        assertEquals("the same touch did not carry the tab", listOf(1, 2, 0), reported)
        assertEquals(listOf(labels[1], labels[2], labels[0]), compose.drawnOrder(labels))
        assertFalse("the lift that ended the drag was taken for a tap", tapped)
    }

    @Test
    fun `on the frame of the drop every tab is still where it was drawn`() {
        // #180. The row composed its tabs by position, so a tab's animated
        // offset belonged to its slot: the drop reordered the row and zeroed
        // the offsets in one frame, and every slot that changed hands drew its
        // new tab from the old tab's offset, for a frame, before springing
        // home — the flash of the row in the wrong order that the reader saw.
        // Against that code the carried tab itself was read here thousands of
        // dp from where the finger had left it.
        //
        // Centres, not edges, and to a dp and a half. What the drop may change
        // on that frame is the carried tab's scale and the jiggle's phase, and
        // both turn about the tab's centre and move no centre sideways; an
        // edge moves with the scale, and the quarter of a tab that allowed for
        // it also let through the gap that travelled with its tab (see
        // `ToolbarGap`), which was a jump of 8 dp.
        setBar()
        compose.holdFarRight(labels[0])
        val before = compose.drawnCentres(labels)

        compose.lift(labels[0])
        val after = compose.drawnCentres(labels)

        for (label in labels) {
            assertEquals(
                "«$label» was drawn somewhere else on the frame of the drop",
                before.getValue(label),
                after.getValue(label),
                1.5f,
            )
        }
    }

    @Test
    fun `as the mode opens, the tab being picked up is never narrower than an icon`() {
        // The label goes as the mode opens, on the bar's bouncy spring, and a
        // bounce on the way to nothing is a dip below it: the tab was briefly
        // narrower than its own icon. That was invisible while the mode opened
        // on one touch and the drag began on another; since the long press also
        // picks the tab up (#181), the neighbour already sliding into the gap
        // rode the dip past the end of the row and out under the pill's edge.
        val reordering = setBar(open = false)
        compose.onNodeWithContentDescription(labels[0]).performTouchInput { down(center) }
        var waited = 0
        while (!reordering() && waited++ < 200) compose.settle(frames = 1)
        assertTrue("the long press never opened the mode", reordering())

        val widths = List(90) {
            compose.settle(frames = 1)
            val bounds = compose.onNodeWithContentDescription(labels[0]).getUnclippedBoundsInRoot()
            (bounds.right - bounds.left).value
        }
        compose.onNodeWithContentDescription(labels[0]).performTouchInput { up() }
        compose.settle()

        assertTrue(
            "the tab dipped to ${widths.min()} dp on its way to ${widths.last()} dp",
            widths.min() >= widths.last() - 0.5f,
        )
    }

    /**
     * The bar, with its mode opened by a long press unless [open] is false.
     * Returns a reading of whether the mode is open now.
     */
    private fun setBar(
        open: Boolean = true,
        onReorder: (List<Int>) -> Unit = {},
        onTap: () -> Unit = {},
    ): () -> Boolean {
        var reordering by mutableStateOf(false)
        compose.setContent {
            LessonsTheme {
                LessonsFloatingToolbar(
                    items = labels.map { label ->
                        ToolbarItem(icon = Icons.Rounded.Settings, label = label) { onTap() }
                    },
                    selectedIndex = 0,
                    reorderable = true,
                    reordering = reordering,
                    onReorderingChange = { reordering = it },
                    onReorder = onReorder,
                )
            }
        }
        compose.settle()
        if (open) compose.longPress(labels[0])
        return { reordering }
    }
}
