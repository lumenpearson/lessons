package com.lumenpearson.lessons.widget.ui

import com.lumenpearson.lessons.widget.format.WidgetStrings
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour of the countdown and the figure beside it have to agree.
 *
 * They are one thing on the screen — a number, in a colour — and they were
 * derived from the same duration two different ways: the figure through
 * [WidgetStrings.minutesShown], which rounds up because that is how a clock is
 * read, and the colour through `Duration.toMinutes()`, which truncates. For the
 * fifty-nine seconds above every whole minute the two disagreed, so from 5:01
 * to 5:59 before the bell the widget drew «6 мин» in the error colour under a
 * rule documented as "the last five minutes".
 */
class UrgentCountdownTest {

    private fun minutesSeconds(minutes: Long, seconds: Long) =
        Duration.ofMinutes(minutes).plusSeconds(seconds)

    /**
     * The defect, at the second it happens.
     *
     * Five minutes and forty seconds prints as «6 мин», so it must not be
     * coloured as one of the last five.
     */
    @Test
    fun `a figure that reads six minutes is not urgent`() {
        val remaining = minutesSeconds(5, 40)

        assertEquals(6L, WidgetStrings.minutesShown(remaining))
        assertFalse("«6 мин» was drawn in the urgent colour", isUrgent(remaining))
    }

    /** The whole band above five minutes, not just the one second of it. */
    @Test
    fun `nothing that prints more than five minutes is urgent`() {
        (1L..59L).forEach { seconds ->
            val remaining = minutesSeconds(5, seconds)
            assertEquals(6L, WidgetStrings.minutesShown(remaining))
            assertFalse("5 min $seconds s printed «6 мин» and coloured it", isUrgent(remaining))
        }
    }

    /** Five minutes exactly is the last moment that is. */
    @Test
    fun `five minutes exactly is urgent`() {
        assertEquals(5L, WidgetStrings.minutesShown(Duration.ofMinutes(5)))
        assertTrue(isUrgent(Duration.ofMinutes(5)))
    }

    /** Everything below it stays urgent, down to the bell and past it. */
    @Test
    fun `everything the widget prints as five minutes or fewer is urgent`() {
        listOf(
            minutesSeconds(4, 59),
            minutesSeconds(4, 0),
            minutesSeconds(0, 30),
            Duration.ZERO,
            // A render that arrived after the bell: the countdown reads
            // «меньше минуты» and is as urgent as it will ever be.
            Duration.ofSeconds(-90),
        ).forEach { remaining ->
            assertTrue("$remaining was not urgent", isUrgent(remaining))
            assertTrue(WidgetStrings.minutesShown(remaining) <= 5L)
        }
    }

    /**
     * The property behind both: the colour is a function of the printed figure
     * and of nothing else, so the two can never drift apart again.
     */
    @Test
    fun `urgency is decided by the printed figure across a whole hour`() {
        (0L..3600L step 7L).forEach { seconds ->
            val remaining = Duration.ofSeconds(seconds)
            assertEquals(
                "at $seconds s the colour and the figure disagree",
                WidgetStrings.minutesShown(remaining) <= 5L,
                isUrgent(remaining),
            )
        }
    }
}
