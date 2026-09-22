package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
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

    private fun setBar(onReorder: (List<Int>) -> Unit = {}) {
        var reordering by mutableStateOf(false)
        compose.setContent {
            LessonsTheme {
                LessonsFloatingToolbar(
                    items = labels.map { label ->
                        ToolbarItem(icon = Icons.Rounded.Settings, label = label) {}
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
        compose.longPress(labels[0])
    }
}
