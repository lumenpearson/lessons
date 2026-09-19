package com.lumenpearson.lessons.core.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a sync that found nothing new is allowed to cost.
 *
 * A `304` is the ordinary answer on a phone whose schedule is not moving, so
 * this runs once an hour by default and four times an hour at the floor the
 * settings screen allows. It used to answer by re-planning the whole chain,
 * which reads every lesson, event and homework row of the cached school year
 * — about two hundred days — to arrive at an alarm that was already armed.
 *
 * The question it actually has is «is anything armed», and the answer to that
 * is a `PendingIntent` lookup. What is pinned here is that the common case
 * touches nothing else at all: the moment this starts reading the preferences
 * or the cache to decide, the saving is gone, and nothing on any screen would
 * show it.
 */
class AlarmReArmTest {

    /** Counts what the decision was willing to go and look at. */
    private var preferencesRead = 0

    private fun silent(value: Boolean?): () -> Boolean? = {
        preferencesRead += 1
        value
    }

    @Test
    fun `an alarm already standing costs nothing to confirm`() {
        val replan = SchoolAlerts.needsReplan(alarmStanding = true, silent = silent(false))

        assertEquals("a 304 with a live chain has nothing to do", false, replan)
        assertEquals(
            "reading anything here is the whole cost this exists to avoid",
            0,
            preferencesRead,
        )
    }

    /**
     * The case the re-arm is kept for. A force-stop and a package replace both
     * take the app's pending intents with them, so nothing is standing and
     * nothing in the chain can notice — `NextAlarm.LookAgain` keeps a chain
     * alive, but it cannot resurrect one that was taken away from outside.
     */
    @Test
    fun `nothing standing is re-planned`() {
        assertEquals(true, SchoolAlerts.needsReplan(alarmStanding = false, silent = silent(false)))
    }

    /**
     * Armed for nothing on purpose. A user who has switched every alert off is
     * *supposed* to have no alarm standing, so without this they would be the
     * one user paying the full read on every single poll, for ever, to be told
     * again what they had already said.
     */
    @Test
    fun `a phone with every alert switched off is not a chain to repair`() {
        assertEquals(false, SchoolAlerts.needsReplan(alarmStanding = false, silent = silent(true)))
    }

    /**
     * An unreadable preferences file is not consent. It is also the state a
     * corrupt store degrades to, and refusing to re-plan there would end the
     * chain on a bad read of a file that has nothing to do with alarms.
     */
    @Test
    fun `preferences that cannot be read still get the chain back`() {
        assertEquals(true, SchoolAlerts.needsReplan(alarmStanding = false, silent = silent(null)))
    }
}
