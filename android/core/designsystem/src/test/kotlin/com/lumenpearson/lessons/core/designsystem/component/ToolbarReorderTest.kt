package com.lumenpearson.lessons.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The half of a drag gesture that is actually wrong when a drag is wrong.
 *
 * None of this needs a pointer, a composition or a device, which is the whole
 * reason it was pulled out of the composable: a Compose test can tell you that
 * something moved, and only arithmetic can tell you it moved to the right slot.
 */
class ToolbarReorderTest {

    /** A 48 dp item and an 8 dp gap at 1x density; the row's real pitch. */
    private val slot = 56f

    @Test
    fun `a tab that has not travelled half a slot stays where it is`() {
        // The threshold is the whole behaviour: below it the reader is still
        // deciding, and a row that rearranged on the first pixel of movement
        // would make deciding impossible.
        assertEquals(1, dropIndex(from = 1, dragPx = 0f, slotPx = slot, count = 3))
        assertEquals(1, dropIndex(from = 1, dragPx = 27f, slotPx = slot, count = 3))
        assertEquals(1, dropIndex(from = 1, dragPx = -27f, slotPx = slot, count = 3))
    }

    @Test
    fun `half a slot is where it changes place, in both directions`() {
        assertEquals(2, dropIndex(from = 1, dragPx = 28f, slotPx = slot, count = 3))
        assertEquals(0, dropIndex(from = 1, dragPx = -28f, slotPx = slot, count = 3))
    }

    @Test
    fun `a finger dragged off the end parks at the end`() {
        // A drag that leaves the bar is still a drag, and the thing being
        // dragged still has to land somewhere. Refusing it would drop the tab
        // back where it started, which reads as the gesture having failed.
        assertEquals(2, dropIndex(from = 0, dragPx = 10_000f, slotPx = slot, count = 3))
        assertEquals(0, dropIndex(from = 2, dragPx = -10_000f, slotPx = slot, count = 3))
    }

    @Test
    fun `a row of one, and a row with no width, are answered rather than divided by`() {
        // `slotPx` comes from a density and a `Dp`, and a row measured before it
        // has been laid out reports zero. Dividing by it is a `NaN` index, which
        // `coerceIn` does not fix — it returns NaN's own answer.
        assertEquals(0, dropIndex(from = 0, dragPx = 500f, slotPx = slot, count = 1))
        assertEquals(1, dropIndex(from = 1, dragPx = 500f, slotPx = 0f, count = 3))
        assertEquals(0, dropIndex(from = 0, dragPx = 500f, slotPx = 0f, count = 0))
    }

    @Test
    fun `everything between the hole and the finger slides one slot towards the hole`() {
        // Dragging the first tab to the last: the two it passes move left by one
        // each, and the tab itself is drawn under the finger rather than in a
        // slot, so it does not shift.
        assertEquals(0, shiftSlots(index = 0, from = 0, to = 2))
        assertEquals(-1, shiftSlots(index = 1, from = 0, to = 2))
        assertEquals(-1, shiftSlots(index = 2, from = 0, to = 2))

        // And the other way.
        assertEquals(1, shiftSlots(index = 0, from = 2, to = 0))
        assertEquals(1, shiftSlots(index = 1, from = 2, to = 0))
        assertEquals(0, shiftSlots(index = 2, from = 2, to = 0))
    }

    @Test
    fun `a tab outside the span the drag covers does not move`() {
        // Five tabs, the second dragged onto the third: the first, fourth and
        // fifth are not between the two and have no reason to move. Without
        // this the whole row would slide, which reads as the bar scrolling.
        assertEquals(0, shiftSlots(index = 0, from = 1, to = 2))
        assertEquals(0, shiftSlots(index = 3, from = 1, to = 2))
        assertEquals(0, shiftSlots(index = 4, from = 1, to = 2))
    }

    @Test
    fun `a drag that ends where it started moves nothing`() {
        assertEquals(0, shiftSlots(index = 1, from = 1, to = 1))
        val order = listOf("a", "b", "c")
        assertSame("a no-op move rebuilt the list", order, moveItem(order, 1, 1))
    }

    @Test
    fun `moving an item closes the gap behind it`() {
        assertEquals(listOf("b", "c", "a"), moveItem(listOf("a", "b", "c"), from = 0, to = 2))
        assertEquals(listOf("c", "a", "b"), moveItem(listOf("a", "b", "c"), from = 2, to = 0))
        assertEquals(listOf("a", "c", "b"), moveItem(listOf("a", "b", "c"), from = 1, to = 2))
    }

    @Test
    fun `a move from or to an index that does not exist is refused rather than thrown`() {
        // The caller is a gesture, and a gesture's indices come from arithmetic
        // over a finger position. An exception here is a crash while somebody is
        // holding an icon; the list unchanged is the drag having done nothing.
        val order = listOf("a", "b", "c")
        assertSame(order, moveItem(order, from = -1, to = 1))
        assertSame(order, moveItem(order, from = 1, to = 3))
        val empty = emptyList<String>()
        assertSame(empty, moveItem(empty, from = 0, to = 1))
    }

    @Test
    fun `a move is a permutation, over every pair of slots`() {
        // The property that matters to the caller: whatever the reader does,
        // the bar afterwards holds exactly the tabs it held before. A reorder
        // that could lose or duplicate one is a tab that cannot be reached.
        val order = (0 until 5).toList()
        for (from in order.indices) {
            for (to in order.indices) {
                val moved = moveItem(order, from, to)
                assertEquals("$from -> $to changed the size", order.size, moved.size)
                assertEquals("$from -> $to lost or duplicated a tab", order.toSet(), moved.toSet())
                assertEquals("$from -> $to put the wrong tab in the slot", order[from], moved[to])
            }
        }
    }
}
