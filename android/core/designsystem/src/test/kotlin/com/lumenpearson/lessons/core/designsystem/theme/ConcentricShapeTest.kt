package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tray of a segmented picker is rounded by the buttons inside it.
 *
 * Two nested rounded rectangles look right for exactly one outer radius — the
 * inner radius plus the padding — and wrong for every other one, with the gap
 * between the curves wider at the corner than along the edge. The picker's tray
 * had a 24 dp token around Material's connected buttons with 4 dp of padding,
 * three numbers chosen in three places and no reason for them to agree.
 *
 * Checked here rather than on a screen because it is arithmetic:
 * `createOutline` is a function of a size and a density, and the radius it
 * produces is the whole of the decision.
 */
class ConcentricShapeTest {

    private val density = Density(density = 2.75f)

    /** The radius [ConcentricShape] chose at this size, in pixels. */
    private fun radiusOf(shape: ConcentricShape, size: Size): Float {
        val outline = shape.createOutline(size, LayoutDirection.Ltr, density)
        assertTrue("a rounded tray is a rounded outline", outline is Outline.Rounded)
        return (outline as Outline.Rounded).roundRect.topLeftCornerRadius.x
    }

    private fun px(value: Dp): Float = with(density) { value.toPx() }

    private val tall = Size(width = 1000f, height = 400f)

    @Test
    fun `the outer radius is the inner one plus the padding`() {
        val shape = ConcentricShape(inner = RoundedCornerShape(16.dp), inset = 4.dp)

        assertEquals(px(16.dp) + px(4.dp), radiusOf(shape, tall), 0.01f)
    }

    @Test
    fun `a percentage corner is measured against the box the buttons get`() {
        // Material's connected button shapes are a percentage of the button's
        // own height, so the answer depends on what size the inner shape is
        // asked about — and the right size is the tray minus its padding on
        // both sides, which is the box the buttons actually occupy. Asking the
        // button's shape about the *tray's* height would round it by four more
        // pixels than the button is rounded by, and the gap would then be wrong
        // in the direction this class exists to fix.
        val shape = ConcentricShape(inner = RoundedCornerShape(percent = 50), inset = 4.dp)

        val inset = px(4.dp)
        val innerHeight = tall.height - inset * 2f
        assertEquals(innerHeight / 2f + inset, radiusOf(shape, tall), 0.01f)
    }

    @Test
    fun `a shape with no corner to read falls back rather than squaring off`() {
        // Material's morphing polygons are `Shape`s with no corner size on
        // them. A cast that missed would silently produce a radius of zero —
        // a hard-edged tray, which reads as a bug rather than as a default.
        val shape = ConcentricShape(
            inner = object : androidx.compose.ui.graphics.Shape {
                override fun createOutline(
                    size: Size,
                    layoutDirection: LayoutDirection,
                    density: Density,
                ): Outline = Outline.Rectangle(
                    androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height),
                )
            },
            inset = 4.dp,
            fallback = 20.dp,
        )

        assertEquals(px(20.dp) + px(4.dp), radiusOf(shape, tall), 0.01f)
    }

    @Test
    fun `a tray shorter than its own corners becomes a capsule rather than breaking`() {
        // A radius larger than half the shorter side is not a rounder
        // rectangle, it is a malformed outline — the two corners of one edge
        // overlap. A 24 dp corner on a 20 dp-tall tray is not hypothetical: it
        // is this picker at a small font scale with an empty label.
        val shape = ConcentricShape(inner = RoundedCornerShape(64.dp), inset = 4.dp)
        val short = Size(width = 1000f, height = 40f)

        assertEquals(20f, radiusOf(shape, short), 0.01f)
    }

    @Test
    fun `a tray with no padding is rounded exactly like what is in it`() {
        val shape = ConcentricShape(inner = RoundedCornerShape(12.dp), inset = 0.dp)

        assertEquals(px(12.dp), radiusOf(shape, tall), 0.01f)
    }
}
