package com.lumenpearson.lessons.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder measures in `dp` and every type size on it is in `sp`.
 *
 * Which means the box and the text stop agreeing the moment a reader enlarges
 * their system font, and on the largest setting they are about twice apart.
 * This is the half of the widget nothing could see: a unit test draws no glyphs
 * and the launcher is the only thing that applies the scale, so a widget picked
 * [WidgetSizeClass.HUGE] on a phone that had room to read half of it.
 *
 * Every test here is written against a size that really is on somebody's home
 * screen, because the defect is not in the arithmetic — it is in which rung the
 * arithmetic chooses.
 */
class WidgetFontScaleTest {

    /** The box in the screenshots: four cells wide, most of a home screen tall. */
    private val bigWidget = DpSize(348.dp, 520.dp)

    @Test
    fun `at the system's largest font a big widget stops claiming the biggest layout`() {
        val atNormal = WidgetSizeClass.of(bigWidget, fontScale = 1f)
        val atLargest = WidgetSizeClass.of(bigWidget, fontScale = 2f)

        // The assertion that fails on a function ignoring the scale, which is
        // the code this was written against: the same box has to resolve to a
        // *different* rung once the type on it has doubled.
        assertNotEquals(
            "the same layout was chosen at 1x and at 2x the system font",
            atNormal,
            atLargest,
        )
    }

    @Test
    fun `what it gives up first is the blocks stacked under the day`() {
        // Not the timeline, and not the week strip: this is the order the
        // ladder already believes in — the rest of today is why somebody put
        // the widget there, and tomorrow's homework is what the tallest rungs
        // add on top. So the tall-only content is what a larger font spends.
        val atNormal = WidgetSizeClass.of(bigWidget, fontScale = 1f)
        val atLargest = WidgetSizeClass.of(bigWidget, fontScale = 2f)

        assertTrue(
            "this box does not reach the next-day block even at normal size, " +
                "so the test is not measuring what it claims",
            atNormal.showsNextDay,
        )
        assertTrue(
            "a widget at twice the font still draws the next school day under " +
                "everything else, which is what ran off the bottom edge",
            !atLargest.showsNextDay,
        )
        assertTrue(
            "and it kept the rest of today, which is the half worth keeping",
            atLargest.timelineRows > 0,
        )
    }

    @Test
    fun `a smaller font never promotes a widget past the box it is in`() {
        // The clamp's floor. A reader who shrank their font has not bought the
        // widget a denser layout: the rungs differ in what they *say*, and a
        // two-cell widget promoted into the four-cell layout would be asked to
        // draw a week strip in 110 dp.
        for (size in WidgetSizeClass.entries) {
            assertEquals(
                "${size.name} changed rung on a font scale below 1",
                WidgetSizeClass.of(size.breakpoint, fontScale = 1f),
                WidgetSizeClass.of(size.breakpoint, fontScale = 0.5f),
            )
        }
    }

    @Test
    fun `no font scale can leave the widget with nothing to say`() {
        // The clamp's ceiling. `fontScale` is a number out of the configuration
        // rather than a promise — Android's own settings stop at 2.0 and an OEM
        // skin need not. Unclamped, a large enough scale drives every widget
        // below TINY's own breakpoint.
        val absurd = listOf(3f, 10f, 100f, Float.MAX_VALUE)
        for (scale in absurd) {
            for (size in WidgetSizeClass.entries) {
                val chosen = WidgetSizeClass.of(size.breakpoint, fontScale = scale)
                assertEquals(
                    "a font scale of $scale gave ${size.name} a different answer " +
                        "than the ceiling of the clamp does",
                    WidgetSizeClass.of(size.breakpoint, fontScale = 2f),
                    chosen,
                )
            }
        }
    }

    @Test
    fun `growing the font never grows what the widget claims it can hold`() {
        // The property the ladder has to keep, over every rung and every scale
        // in between: more type can only ever buy *less* content. One
        // inversion — a scale at which a widget suddenly claims another row —
        // is a widget that overflows at exactly one accessibility setting.
        val scales = listOf(1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f)
        for (size in WidgetSizeClass.entries) {
            for ((smaller, larger) in scales.zipWithNext()) {
                val a = WidgetSizeClass.of(size.breakpoint, fontScale = smaller)
                val b = WidgetSizeClass.of(size.breakpoint, fontScale = larger)
                assertTrue(
                    "${size.name} claims ${b.timelineRows} rows at $larger but " +
                        "${a.timelineRows} at $smaller",
                    b.timelineRows <= a.timelineRows,
                )
                assertTrue(
                    "${size.name} turns the week strip on going from $smaller to $larger",
                    !b.showsWeekStrip || a.showsWeekStrip,
                )
                assertTrue(
                    "${size.name} grows its homework budget going from $smaller to $larger",
                    b.homeworkItems <= a.homeworkItems,
                )
            }
        }
    }

    @Test
    fun `the default is the box alone, so nothing that never asks is changed`() {
        for (size in WidgetSizeClass.entries) {
            assertEquals(
                WidgetSizeClass.of(size.breakpoint, fontScale = 1f),
                WidgetSizeClass.of(size.breakpoint),
            )
        }
    }
}
