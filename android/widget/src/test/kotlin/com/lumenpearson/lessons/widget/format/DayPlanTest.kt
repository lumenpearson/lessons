package com.lumenpearson.lessons.widget.format

import android.content.ContextWrapper
import android.content.res.Resources
import com.lumenpearson.lessons.widget.R
import com.lumenpearson.lessons.widget.WidgetSizeClass
import com.lumenpearson.lessons.widget.ui.dayPlanLines
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The day plan keeps its count and its time, and the subject is what gives way (#174).
 *
 * A 2×2 on a phone is `SMALL_TALL`, whose budget is 24 characters, and
 * «4 урока · Алгебра в 08:00» is 25. Cut from the end as a whole the line read
 * «4 урока · Алгебра в 08:…» — the one number on it a pupil packing a bag needs
 * was the part that went.
 *
 * No Robolectric in this module, so the context is a stub whose only real part
 * is `getResources()`, carrying the Russian format and plural.
 */
class DayPlanTest {

    @Suppress("DEPRECATION") // the only public constructor; the stub ignores its arguments
    private val resources = object : Resources(null, null, null) {
        override fun getString(id: Int, vararg formatArgs: Any?): String {
            check(id == R.string.widget_next_day_summary) { "asked for string $id" }
            return String.format("%1\$s · %2\$s в %3\$s", *formatArgs)
        }

        override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String {
            check(id == R.plurals.widget_lesson_count) { "asked for plural $id" }
            return "$quantity " + when {
                quantity % 10 == 1 && quantity % 100 != 11 -> "урок"
                quantity % 10 in 2..4 && quantity % 100 !in 12..14 -> "урока"
                else -> "уроков"
            }
        }
    }

    private val context = object : ContextWrapper(null) {
        override fun getResources(): Resources = this@DayPlanTest.resources
    }

    private fun plan(lessons: Int, subject: String, maxChars: Int) =
        WidgetStrings.dayPlan(context, lessons, subject, LocalTime.of(8, 0), maxChars)

    @Test
    fun `a plan that fits is drawn whole`() {
        assertEquals("4 урока · Алгебра в 08:00", plan(4, "Алгебра", maxChars = 56))
    }

    @Test
    fun `over the budget, the subject is shortened and the time is kept`() {
        val budget = WidgetSizeClass.SMALL_TALL.homeworkChars
        val drawn = plan(4, "Алгебра", maxChars = budget)

        assertTrue("«$drawn» lost the time", drawn.endsWith(" в 08:00"))
        assertTrue("«$drawn» lost the count", drawn.startsWith("4 урока · "))
        assertTrue("«$drawn» does not say it was shortened", "…" in drawn)
        assertTrue("«$drawn» is over the budget of $budget", drawn.length <= budget)
    }

    @Test
    fun `a long subject is what pays for the whole overrun`() {
        val drawn = plan(6, "Литературное чтение", maxChars = 24)

        assertEquals("6 уроков · Лите… в 08:00", drawn)
    }

    @Test
    fun `the subject keeps a readable stub even when the budget cannot hold it`() {
        // Ten lessons and a budget of 18: the count and the time alone are
        // twenty. The time is still not the part that goes; the line overruns,
        // and the narrow rungs that could be handed such a budget have two lines.
        val drawn = plan(10, "Алгебра", maxChars = 18)

        assertEquals("10 уроков · Алг… в 08:00", drawn)
    }

    @Test
    fun `the plan may take two lines only where a column is narrow`() {
        WidgetSizeClass.entries.forEach { size ->
            assertEquals(size.name, if (size.isNarrow) 2 else 1, dayPlanLines(size))
        }
    }
}
