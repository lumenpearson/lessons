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

    /**
     * What the launcher actually does with the breakpoint set, reproduced here.
     *
     * Both the framework's sized `RemoteViews` (API 31+) and Glance's own
     * `findBestSize` keep the breakpoints that fit inside the real size and then
     * take the one at the smallest squared distance from it. Nearest, not
     * largest — which is why the ladder has to be checked against this rule and
     * not against [WidgetSizeClass.of] alone.
     */
    private fun launcherPicks(real: DpSize): WidgetSizeClass {
        val chosen = WidgetSizeClass.breakpoints
            .filter { it.width <= real.width && it.height <= real.height }
            .minByOrNull {
                val dw = (real.width - it.width).value
                val dh = (real.height - it.height).value
                dw * dw + dh * dh
            }
        return WidgetSizeClass.of(chosen ?: WidgetSizeClass.TINY.breakpoint)
    }

    /**
     * A four-cell-wide widget must never be drawn as a two-cell column.
     *
     * It used to be, above about 471 dp of height: with no rung above [LARGE] at
     * 250 dp wide, (110, 300) was nearer to (250, 480) than (250, 250) was, so
     * the largest widget a four-column home screen can hold rendered the narrow
     * layout — no week strip, and homework clipped to the 24 characters that fit
     * in a width it did not have.
     */
    @Test
    fun `a four-cell-wide widget never lands on the narrow column`() {
        (WidgetSizeClass.LARGE.breakpoint.height.value.toInt()..700 step 5).forEach { height ->
            val picked = launcherPicks(DpSize(250.dp, height.dp))
            assertTrue("$height dp tall was drawn as ${picked.name}", !picked.isNarrow)
        }
    }

    /** The same, one grid column wider, where the 320 dp rungs take over. */
    @Test
    fun `a five-cell-wide widget never lands on the narrow column`() {
        (320..900 step 5).forEach { height ->
            val picked = launcherPicks(DpSize(320.dp, height.dp))
            assertTrue("$height dp tall was drawn as ${picked.name}", !picked.isNarrow)
        }
    }

    /** And a genuinely narrow one still gets the column it was written for. */
    @Test
    fun `a two-cell-wide widget still lands on the narrow column`() {
        assertEquals(WidgetSizeClass.NARROW, launcherPicks(DpSize(140.dp, 480.dp)))
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
     * The same property, asked of the sizes a launcher actually reports.
     *
     * The test above compares *breakpoints*, and breakpoints are only
     * comparable when one is larger in both directions — so the ladder's one
     * real inversion sat outside it for two releases. `LARGE` is 250×250 and
     * `NARROW` is 110×300: neither contains the other, nothing compared them,
     * and a widget 250 wide by 300 tall therefore drew *less* than the same
     * widget at 110 wide, because the first lands on `LARGE` and the second on
     * `NARROW`, which had homework on where `LARGE` had it off.
     *
     * What a user grows is the widget, not the breakpoint, so that is what is
     * swept here: every size on a grid, against every size at least as large in
     * both directions, compared through [WidgetSizeClass.of] — which is the
     * function that decides what they will see.
     */
    @Test
    fun `growing a real widget never takes anything away`() {
        val widths = listOf(90, 110, 180, 250, 260, 320, 400, 480)
        val heights = listOf(40, 60, 110, 150, 190, 250, 300, 360, 400, 480, 560, 640)
        val sizes = widths.flatMap { w -> heights.map { h -> DpSize(w.dp, h.dp) } }

        sizes.forEach { small ->
            sizes.filter { it.width >= small.width && it.height >= small.height }.forEach { big ->
                val grown = WidgetSizeClass.of(big)
                val was = WidgetSizeClass.of(small)
                val why = "$big (${grown.name}) is at least as large as $small (${was.name})"
                assertTrue("$why but lists fewer lessons", grown.timelineRows >= was.timelineRows)
                assertTrue("$why but lists less homework", grown.homeworkItems >= was.homeworkItems)
                assertTrue("$why but truncates harder", grown.homeworkChars >= was.homeworkChars)
                assertTrue("$why but drops the week", grown.showsWeekStrip || !was.showsWeekStrip)
                assertTrue("$why but drops homework", grown.showsHomework || !was.showsHomework)
                assertTrue("$why but drops tomorrow", grown.showsNextDay || !was.showsNextDay)
            }
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
