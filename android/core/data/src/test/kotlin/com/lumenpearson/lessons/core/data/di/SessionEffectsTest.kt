package com.lumenpearson.lessons.core.data.di

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the app is told when the session changes, and what it is not.
 *
 * These two answers were written inline in the container, where nothing could
 * reach them: every one of the five things they do needs a `Context`, an
 * `AlarmManager` or `WorkManager`. The cost of that was a line nobody could
 * see was missing — `SyncScheduler.cancelPeriodic` exists for sign-out, says
 * so in its own KDoc, and had no caller anywhere in the app, so a phone that
 * signed out went on waking every fifteen minutes to discover it was in no
 * class.
 *
 * The other half of this is the boundary, which is the part worth pinning
 * rather than the list: a *switch* must not cancel the background sync. The
 * device is still in a class and that class is as stale as the last time
 * anybody looked at it.
 */
class SessionEffectsTest {

    private val done = mutableListOf<String>()

    private val effects = SessionEffects(
        redrawWidget = { done += "redraw" },
        clearAlerts = { done += "clear-alerts" },
        replanAlerts = { done += "replan-alerts" },
        stopBackgroundSync = { done += "stop-sync" },
        startBackgroundSync = { done += "start-sync" },
        syncNow = { done += "sync-now" },
    )

    @Test
    fun `signing out stops the background refresh`() {
        effects.onSignedOut()

        assertEquals(
            "a phone in no class has nothing to refresh, and the periodic worker " +
                "would otherwise wake it every fifteen minutes for the life of the install",
            listOf("redraw", "clear-alerts", "stop-sync"),
            done,
        )
    }

    /**
     * The boundary. Switching classes is not signing out: cancelling here
     * would leave the class being switched *to* as stale as it was until the
     * next cold start, which is the one thing a switch is asking to fix.
     */
    @Test
    fun `switching class asks for a sync instead of stopping one`() {
        effects.onActiveClassChanged()

        assertEquals(
            listOf("redraw", "clear-alerts", "replan-alerts", "start-sync", "sync-now"),
            done,
        )
    }

    /**
     * The half of the switch that a cold start used to be the only cure for.
     *
     * `SyncScheduler.schedulePeriodic` is called from one place in the whole
     * app: a `distinctUntilChanged()` collector on the sync interval in
     * `LessonsApplication`. Signing out and joining a class again in the same
     * process moves no interval, so nothing re-emits and nothing re-enqueues
     * — the worker [onSignedOut] cancelled stays cancelled. The one sync
     * [onActiveClassChanged] asks for makes that invisible: the class is
     * correct on screen at the moment of joining and then never refreshes
     * again until the app is killed and reopened.
     */
    @Test
    fun `joining a class again after a sign-out re-arms the background refresh`() {
        effects.onSignedOut()
        done.clear()

        effects.onActiveClassChanged()

        assertEquals(
            "the periodic worker was cancelled by the sign-out and only a session " +
                "change can know to put it back — the interval collector never re-emits",
            true,
            "start-sync" in done,
        )
    }

    /**
     * Stated as its own assertion because it is the thing a future edit is
     * most likely to get wrong: the two answers say opposite things about the
     * background sync, and they are one word apart in the container.
     */
    @Test
    fun `the two answers point the background sync in opposite directions`() {
        effects.onSignedOut()
        val signedOut = done.toList()
        done.clear()
        effects.onActiveClassChanged()

        assertEquals(true, "stop-sync" in signedOut)
        assertEquals(false, "start-sync" in signedOut)
        assertEquals(false, "sync-now" in signedOut)
        assertEquals(false, "stop-sync" in done)
        assertEquals(true, "start-sync" in done)
        assertEquals(true, "sync-now" in done)
    }
}
