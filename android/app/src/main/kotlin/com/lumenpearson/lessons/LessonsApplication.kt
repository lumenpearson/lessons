package com.lumenpearson.lessons

import android.app.Application
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.notifications.SchoolAlerts
import com.lumenpearson.lessons.core.data.repository.SyncArming
import com.lumenpearson.lessons.core.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Process entry point.
 *
 * It exists for exactly two reasons, and deliberately does no more:
 *
 *  1. [Graph.init] has to run before anything can reach a repository. There is
 *     no Hilt in this project — the widget and the sync worker are woken by the
 *     system with no injection point of their own, so a plain object graph that
 *     any entry point can read is simpler and honest about its lifetime.
 *  2. Background sync has to be armed or disarmed from a place that runs on
 *     every cold start, otherwise a user who changes the interval and never
 *     opens the settings screen again keeps the old cadence forever. The
 *     notification alarm is re-armed here for the same reason, and because a
 *     missed boot broadcast should cost one launch rather than a reinstall.
 *
 * What it does **not** do is read the diary. The diary is refreshed only while
 * somebody is looking at it — `HomeShell`'s start effect, in the diary mode —
 * and a refresh from here would run on every widget update and every alarm
 * that wakes the process, which is a phone reading a child's marks with nobody
 * holding it, and on the server an activity that keeps a session alive for ever.
 */
class LessonsApplication : Application() {

    /**
     * Process-scoped scope. It is never cancelled because the only thing running
     * in it is the arming collector below, which must live exactly as long as
     * the process does.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Graph.init(this, githubClientId = BuildConfig.GITHUB_CLIENT_ID)
        observeSyncArming()
        // Off the main thread: it reads the cached timetable to work out what to
        // arm for, and Application.onCreate is on the critical path of every
        // cold start, including the one a widget update triggers.
        applicationScope.launch { SchoolAlerts.onAppStart(this@LessonsApplication) }
    }

    /**
     * Keeps the WorkManager cadence in step with the stored mode and interval.
     *
     * It used to follow the interval alone, and so it armed the worker on the
     * first emission of every cold start whatever was stored: a phone that had
     * left its last class — whose sign-out had just cancelled the worker — was
     * woken every fifteen minutes again from its next launch on, for a run that
     * found no class and returned (#152). `shellMode.syncArming` is the mode and
     * the interval read from one preferences emission, so the answer here is the
     * same rule `SessionEffects` acts on when a session changes, and the two
     * cannot disagree about a phone in no class.
     *
     * Collecting instead of reading once means the settings screen only has to
     * write the new interval; rescheduling is this class's job, which keeps
     * [com.lumenpearson.lessons.ui.settings.SettingsViewModel] free of any
     * Android scheduling concern.
     */
    private fun observeSyncArming() {
        applicationScope.launch {
            Graph.container.shellMode.syncArming.driveSyncScheduler(
                schedule = { minutes -> SyncScheduler.schedulePeriodic(this@LessonsApplication, minutes) },
                cancel = { SyncScheduler.cancelPeriodic(this@LessonsApplication) },
            )
        }
    }
}

/**
 * Carries each [SyncArming] out, one call per emission: armed installs the
 * periodic worker at its interval, disarmed cancels it.
 *
 * Cancelling on every disarmed emission rather than only on a change is
 * deliberate — `cancelPeriodic` on nothing is a no-op, and the one emission
 * that matters most is the first after a cold start, where there is no
 * «before» to compare with.
 */
internal suspend fun Flow<SyncArming>.driveSyncScheduler(
    schedule: (intervalMinutes: Int) -> Unit,
    cancel: () -> Unit,
) {
    collect { arming ->
        when (arming) {
            is SyncArming.Armed -> schedule(arming.intervalMinutes)
            SyncArming.Disarmed -> cancel()
        }
    }
}
