package com.lumenpearson.lessons.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Growing the widget has to buy more content.
 *
 * The row budget is fixed per class and Glance offers no measure pass, so a size
 * the ladder does not reach is drawn with the surplus left blank — which is what
 * a widget stretched down half a home screen used to look like.
 */
class WidgetSizeClassTest {

    @Test
    fun `half a home screen resolves to the tallest class`() {
        assertEquals(WidgetSizeClass.TALL, WidgetSizeClass.of(DpSize(348.dp, 520.dp)))
    }

    @Test
    fun `the tallest class shows more lessons than the one below it`() {
        assertTrue(WidgetSizeClass.TALL.timelineRows > WidgetSizeClass.XLARGE.timelineRows)
    }

    @Test
    fun `a wide but short widget is not mistaken for a tall one`() {
        assertEquals(WidgetSizeClass.MEDIUM, WidgetSizeClass.of(DpSize(348.dp, 120.dp)))
    }

    @Test
    fun `every declared breakpoint maps back to its own class`() {
        WidgetSizeClass.entries.forEach { expected ->
            assertEquals(expected, WidgetSizeClass.of(expected.breakpoint))
        }
    }

    @Test
    fun `each breakpoint is offered to the launcher exactly once`() {
        assertEquals(WidgetSizeClass.entries.size, WidgetSizeClass.breakpoints.size)
    }
}
