package com.lumenpearson.lessons.core.data.notifications

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which part of the past a firing alarm is allowed to speak for.
 *
 * Without `SCHEDULE_EXACT_ALARM` the chain arms with `setAndAllowWhileIdle`,
 * which the platform may deliver late — and a window of ninety seconds either
 * side of the *arrival* then contains neither the alert it was armed for nor
 * anything that fell due while it was waiting. Both were dropped in silence, and
 * the next alarm was armed past them.
 */
class SchoolAlertsWindowTest {

    private fun at(time: String): LocalDateTime = LocalDateTime.parse("2026-09-07T$time")

    @Test
    fun `a late delivery reaches back to the moment it was armed for`() {
        val start = SchoolAlerts.windowStart(now = at("08:24:00"), armedFor = at("08:20:00"))

        assertEquals(at("08:18:30"), start)
    }

    @Test
    fun `an alarm on time behaves exactly as the plain tolerance did`() {
        val now = at("08:20:00")

        assertEquals(at("08:18:30"), SchoolAlerts.windowStart(now, armedFor = now))
    }

    /**
     * An alarm may also arrive a hair *early*. Reaching only up to its own armed
     * moment would then stop short of it, so the window never closes nearer than
     * the tolerance.
     */
    @Test
    fun `an early delivery still covers its own moment`() {
        val start = SchoolAlerts.windowStart(now = at("08:19:40"), armedFor = at("08:20:00"))

        assertEquals(at("08:18:10"), start)
    }

    /**
     * A phone that spent the afternoon switched off must not empty half a day of
     * reminders onto the shade the moment it comes back.
     */
    @Test
    fun `a delivery hours late announces the recent past and no more`() {
        val start = SchoolAlerts.windowStart(now = at("17:00:00"), armedFor = at("08:20:00"))

        assertEquals(at("16:30:00"), start)
    }

    /** An alarm that claims nothing — the read-failure retry — keeps the old window. */
    @Test
    fun `an alarm with no armed moment falls back to the tolerance`() {
        assertEquals(at("08:18:30"), SchoolAlerts.windowStart(now = at("08:20:00"), armedFor = null))
    }
}
