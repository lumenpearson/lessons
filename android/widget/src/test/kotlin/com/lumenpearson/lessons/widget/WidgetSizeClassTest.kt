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
    fun `half a home screen resolves to the tall class`() {
        assertEquals(WidgetSizeClass.TALL, WidgetSizeClass.of(DpSize(348.dp, 520.dp)))
    }

    @Test
    fun `most of a home screen resolves to the top of the ladder`() {
        assertEquals(WidgetSizeClass.HUGE, WidgetSizeClass.of(DpSize(360.dp, 640.dp)))
    }

    @Test
    fun `a wide but short widget is not mistaken for a tall one`() {
        assertEquals(WidgetSizeClass.MEDIUM, WidgetSizeClass.of(DpSize(348.dp, 120.dp)))
    }

    /**
     * The shape that used to be the worst of the lot: two cells wide and five
     * tall drew the 110 × 110 layout and left four rows of background under it.
     */
    @Test
    fun `a narrow but tall widget gets a column, not a square`() {
        assertEquals(WidgetSizeClass.NARROW, WidgetSizeClass.of(DpSize(140.dp, 420.dp)))
        assertEquals(WidgetSizeClass.SMALL_TALL, WidgetSizeClass.of(DpSize(140.dp, 220.dp)))
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

    /**
     * The one property the whole ladder rests on: a widget that is bigger in both
     * directions never says less.
     *
     * Checked over every comparable pair rather than between neighbours, because
     * the ladder is not a line — it branches by width — and a rung added to one
     * branch can quietly undercut a rung on another. The flags left out are the
     * ones a larger class legitimately drops: [WidgetSizeClass.showsNextUp] goes
     * away on the sizes that list the whole day, where a separate "Дальше" line
     * would repeat the row directly under it.
     */
    @Test
    fun `a bigger widget never shows less`() {
        val pairs = WidgetSizeClass.entries.flatMap { bigger ->
            WidgetSizeClass.entries.map { smaller -> bigger to smaller }
        }.filter { (bigger, smaller) ->
            bigger != smaller &&
                bigger.breakpoint.width >= smaller.breakpoint.width &&
                bigger.breakpoint.height >= smaller.breakpoint.height
        }

        // A ladder whose rungs are never comparable would pass every assertion
        // below by having nothing to assert.
        assertTrue("no comparable pairs to check", pairs.size >= WidgetSizeClass.entries.size)

        pairs.forEach { (bigger, smaller) ->
            val why = "${bigger.name} is at least as large as ${smaller.name}"
            assertTrue("$why but lists fewer lessons", bigger.timelineRows >= smaller.timelineRows)
            assertTrue("$why but lists less homework", bigger.homeworkItems >= smaller.homeworkItems)
            assertTrue("$why but truncates harder", bigger.homeworkChars >= smaller.homeworkChars)
            assertTrue("$why but drops the week", bigger.showsWeekStrip || !smaller.showsWeekStrip)
            assertTrue("$why but drops homework", bigger.showsHomework || !smaller.showsHomework)
            assertTrue("$why but drops tomorrow", bigger.showsNextDay || !smaller.showsNextDay)
        }
    }

    /**
     * Every size answers "what is next", one way or another.
     *
     * The narrow column drops the dedicated line because its timeline already
     * starts with that lesson; the sizes with neither are the two that are one
     * line tall, where there is nothing to drop it from.
     */
    @Test
    fun `every size that can say what is next does`() {
        WidgetSizeClass.entries
            .filter { it.breakpoint.height >= WidgetSizeClass.SMALL_TALL.breakpoint.height }
            .forEach { size ->
                assertTrue(
                    "${size.name} says nothing about what comes next",
                    size.showsNextUp || size.timelineRows > 0,
                )
            }
    }
}
