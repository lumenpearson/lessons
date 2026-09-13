package com.lumenpearson.lessons.core.designsystem.state

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arithmetic behind the time picker.
 *
 * Nothing here is interesting on a good day, which is the point: every one of
 * these functions sits between a preferences file that any past version of the
 * app may have written and a dial that will happily render whatever it is given.
 * The failures worth a test are the ones that produce a plausible wrong answer —
 * midnight printed as "0:00 PM", a stored 1 500 wrapping round to one in the
 * morning — rather than the ones that throw.
 */
class ClockTimeTest {

    @Test
    fun `a 24-hour clock pads the hour, so a column of rows lines up`() {
        assertEquals("00:00", formatClockTime(0, use24Hour = true))
        assertEquals("07:00", formatClockTime(7 * 60, use24Hour = true))
        assertEquals("08:30", formatClockTime(8 * 60 + 30, use24Hour = true))
        assertEquals("23:59", formatClockTime(24 * 60 - 1, use24Hour = true))
    }

    /**
     * The two ends of a 12-hour day, which is where every off-by-twelve lives:
     * midnight is twelve in the morning and noon is twelve in the afternoon, and
     * an hour of `0` or `12` naively formatted gets both of them wrong.
     */
    @Test
    fun `a 12-hour clock names midnight and noon`() {
        assertEquals("12:00 AM", formatClockTime(0, use24Hour = false, locale = Locale.US))
        assertEquals("12:00 PM", formatClockTime(12 * 60, use24Hour = false, locale = Locale.US))
        assertEquals("7:00 AM", formatClockTime(7 * 60, use24Hour = false, locale = Locale.US))
        assertEquals("1:05 PM", formatClockTime(13 * 60 + 5, use24Hour = false, locale = Locale.US))
    }

    @Test
    fun `a time outside the day is pinned to the day, not wrapped into it`() {
        assertEquals(0, clampMinutesOfDay(-1))
        assertEquals(0, clampMinutesOfDay(-24 * 60))
        assertEquals(24 * 60 - 1, clampMinutesOfDay(24 * 60))
        assertEquals(24 * 60 - 1, clampMinutesOfDay(5000))
    }

    /** The clamp has to reach the formatter too, or a bad value crashes `LocalTime`. */
    @Test
    fun `formatting a value outside the day still produces a time`() {
        assertEquals("00:00", formatClockTime(-90, use24Hour = true))
        assertEquals("23:59", formatClockTime(9999, use24Hour = true))
    }

    @Test
    fun `hours and minutes come apart and go back together`() {
        for (minutes in 0 until 24 * 60) {
            assertEquals(minutes, minutesOfDayOf(hourOfDay(minutes), minuteOfHour(minutes)))
        }
    }

    @Test
    fun `the picker's own dials are clamped on the way back in`() {
        assertEquals(0, minutesOfDayOf(hour = -1, minute = 0))
        assertEquals(8 * 60 + 30, minutesOfDayOf(hour = 8, minute = 30))
        assertEquals(24 * 60 - 1, minutesOfDayOf(hour = 25, minute = 0))
    }
}
