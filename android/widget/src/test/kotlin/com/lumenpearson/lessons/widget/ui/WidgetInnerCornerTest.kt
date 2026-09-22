package com.lumenpearson.lessons.widget.ui

import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.widget.WidgetSizeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every block drawn on the widget is rounded by the surface around it.
 *
 * Two nested rounded rectangles look right for exactly one pair of radii — the
 * inner one plus the padding equals the outer — and wrong for every other pair,
 * with the gap between the curves wider at the corner than along the edge.
 *
 * The widget had a flat 18 dp for «an inner block», chosen as one step tighter
 * than its own 24 dp surface. That would have been correct for exactly one
 * padding, and the widget has **twelve size classes with five different
 * paddings**, from 8 dp to 16 dp. So the gap was right nowhere, and worst where
 * the widget is largest: at 16 dp of padding a concentric block wants 8 dp and
 * it was drawing 18.
 *
 * Checked here rather than on a screen because it is arithmetic, and because a
 * widget cannot be screenshotted from a JVM test at all — Glance builds
 * `RemoteViews`, and there is no frame loop to draw them into.
 */
class WidgetInnerCornerTest {

    @Test
    fun `an inner block is the surface's corner less the padding it sits behind`() {
        // The rule itself, on the size whose padding leaves it unclamped.
        val small = WidgetSizeClass.entries.first { it.paddingDp == 8f }

        assertEquals((WidgetSurfaceCorner - 8.dp).value, small.innerCorner().value, 0.01f)
    }

    @Test
    fun `no size class is rounder than the surface that holds it`() {
        // The defect in one assertion: a block cannot be rounder than what
        // contains it, and the old flat 18 dp was exactly that on the eight
        // sizes whose padding is 10 dp or more.
        for (size in WidgetSizeClass.entries) {
            val corner = size.innerCorner()
            assertTrue(
                "${size.name} draws ${corner.value} dp inside a " +
                    "${WidgetSurfaceCorner.value} dp surface",
                corner <= WidgetSurfaceCorner,
            )
        }
    }

    @Test
    fun `a bigger widget pads more and therefore rounds its blocks less`() {
        // The relationship the old constant could not express, stated as a
        // relationship rather than as twelve numbers, so a size class added
        // later is covered without anybody editing this.
        //
        // **Strictly** smaller where the paddings differ, and that word is the
        // whole test. Written as `<=` it passes on a flat constant — every size
        // draws the same corner, and «not rounder than the one before» is true
        // of a row of identical numbers. That version of this test was green on
        // the defect it was written for.
        val bySize = WidgetSizeClass.entries.sortedBy { it.paddingDp }

        for ((looser, tighter) in bySize.zipWithNext()) {
            if (tighter.paddingDp == looser.paddingDp) continue
            assertTrue(
                "${looser.name} pads ${looser.paddingDp} and rounds " +
                    "${looser.innerCorner().value}; ${tighter.name} pads " +
                    "${tighter.paddingDp} and rounds ${tighter.innerCorner().value}",
                tighter.innerCorner() < looser.innerCorner(),
            )
        }
    }

    @Test
    fun `a block never goes square, however much the widget pads`() {
        // A square corner is the honest answer for a block inset past the
        // surface's curve, and the wrong one here: the rows beside it stay
        // rounded, so one square block among them reads as a rendering fault.
        // Nothing in the ladder reaches the floor today; this is what fails
        // first if a size class is ever given a padding of 18 dp or more.
        for (size in WidgetSizeClass.entries) {
            assertTrue(
                "${size.name} would draw a square block",
                size.innerCorner().value > 0f,
            )
        }
    }

    @Test
    fun `the ladder really does carry more than one padding`() {
        // Without this the three tests above pass on a ladder that has been
        // flattened to one padding, which is the state they exist to rule out:
        // a single padding is exactly the world in which a flat constant was
        // correct, and it is not the world this widget lives in.
        val paddings = WidgetSizeClass.entries.map { it.paddingDp }.toSet()

        assertTrue("expected several paddings, found $paddings", paddings.size >= 3)
    }
}
