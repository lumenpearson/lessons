package com.lumenpearson.lessons.ui.day

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much of the ribbon a soft edge is allowed to cover.
 *
 * Reported from a real build: «в портретном режиме можно было пользоваться, но
 * ничего не было видно, из-за соотношения». The fade was a fixed 48 dp at each
 * end, sized for a scroll that owns the whole screen — and this one does not.
 * The ribbon is handed the height left over under the header and the picker, so
 * on a phone on its side it had about 150 dp, and 96 of them were under a fade.
 *
 * A fade is a hint that the list carries on past the edge. Past some share of
 * the view it stops being a hint and becomes a curtain, and the shorter the
 * view the worse it gets — which is exactly what «из-за соотношения» means.
 */
class RibbonEdgeHeightTest {

    /** A phone's worth of pixels at 2.75×: 48 dp is 132 px. */
    private val full = 132f

    @Test
    fun `a tall ribbon gets the whole fade`() {
        // 600 dp at 2.75× is 1650 px; a twelfth of it is well past 132.
        assertEquals(full, ribbonEdgeHeight(viewportHeight = 1650f, fullHeight = full), 0.01f)
    }

    @Test
    fun `a short ribbon gets a fade in proportion to itself`() {
        // 150 dp — a phone on its side. The fade must not be two thirds of it.
        val short = 412f
        val edge = ribbonEdgeHeight(viewportHeight = short, fullHeight = full)

        assertTrue("$edge px of $short", edge < full)
        assertTrue("both ends must leave the middle readable", edge * 2 < short / 2)
    }

    @Test
    fun `no height means no fade rather than a division by it`() {
        // One frame of a view being laid out, and a foldable's cover screen.
        assertEquals(0f, ribbonEdgeHeight(viewportHeight = 0f, fullHeight = full), 0.01f)
        assertEquals(0f, ribbonEdgeHeight(viewportHeight = -10f, fullHeight = full), 0.01f)
        assertEquals(0f, ribbonEdgeHeight(viewportHeight = 1650f, fullHeight = 0f), 0.01f)
    }

    @Test
    fun `the fade is never more than a share of the view, at any size`() {
        // The rule itself, over every height a phone can hand this view.
        for (height in 1..2000 step 7) {
            val edge = ribbonEdgeHeight(viewportHeight = height.toFloat(), fullHeight = full)
            assertTrue("$edge px of $height", edge <= height * 0.125f)
        }
    }
}
