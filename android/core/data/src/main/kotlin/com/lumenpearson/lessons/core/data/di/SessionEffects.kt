package com.lumenpearson.lessons.core.data.di

/**
 * What the rest of the app has to be told when the session changes.
 *
 * These two answers used to be a pair of lambdas written inline in
 * [DefaultLessonsContainer], which made them the only part of the container
 * that decides anything and the only part nothing could test: reaching them
 * needs a `Context`, an `AlarmManager` and `WorkManager`. The consequence was
 * the defect this type was extracted for: `SessionRepository` had called
 * `onSignedOut` correctly on every path since it was written, and the one
 * thing that answer was supposed to include was simply never written down.
 *
 * Every dependency arrives as a function so that the table is a table: what
 * happens is here, and where it happens is the container's business.
 *
 * @param redrawWidget the `DATA_SYNCED` broadcast. The widget lives in another
 *   module that this one must not depend on, so it is told rather than called.
 * @param clearAlerts drop the armed alarm *and* what is already on the shade.
 * @param replanAlerts recompute the chain from the class now on screen.
 * @param stopBackgroundSync cancel the periodic `SyncWorker`.
 * @param syncNow ask for one refresh, as soon as the platform allows.
 */
internal class SessionEffects(
    private val redrawWidget: () -> Unit,
    private val clearAlerts: () -> Unit,
    private val replanAlerts: () -> Unit,
    private val stopBackgroundSync: () -> Unit,
    private val syncNow: () -> Unit,
) {

    /**
     * The last class is gone: this phone is in none.
     *
     * The widget is told because it is still drawing one, and the alarms go
     * because the chain was planned from a timetable that is about to be
     * deleted — without that, the phone announced a lesson for a class it had
     * already left, and no screen in the app could explain where it came from.
     *
     * And the background refresh stops, which is the part that was missing.
     * `SyncScheduler.cancelPeriodic` was written for exactly this moment and
     * had no caller at all, so a phone that signed out went on waking every
     * fifteen minutes for the rest of the install's life. Nothing was wrong
     * with what it then did — `SyncWorker` reads the session, finds none and
     * reports success — but the wake-up is real and it is forever, and the
     * next `schedulePeriodic` on the next launch reinstates it anyway, so
     * nothing is lost by stopping now.
     */
    fun onSignedOut() {
        redrawWidget()
        clearAlerts()
        stopBackgroundSync()
    }

    /**
     * A different class is the one on screen — a join, a switch, or leaving the
     * class that was showing while others remain.
     *
     * One step short of [onSignedOut] and the step it stops short of is the
     * background sync, deliberately: the device is still in a class, that class
     * is as stale as the last time anybody looked at it, and cancelling the
     * periodic refresh here would leave it stale until the next cold start.
     * The switch *wants* syncing, which is why [syncNow] is in this list and
     * [stopBackgroundSync] is not.
     *
     * The alerts are cleared before they are re-planned rather than instead of
     * it. An alert already on the shade names no class — «первый в 08:30» is
     * all it says — so after a switch it is an unattributed statement about a
     * class the phone is no longer showing, and tapping it opens the app on the
     * other one.
     */
    fun onActiveClassChanged() {
        redrawWidget()
        clearAlerts()
        replanAlerts()
        syncNow()
    }
}
