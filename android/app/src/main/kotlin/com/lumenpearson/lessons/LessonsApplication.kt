package com.lumenpearson.lessons

import android.app.Application
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.notifications.SchoolAlerts
import com.lumenpearson.lessons.core.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 *  2. Background sync has to be (re)scheduled from a place that runs on every
 *     cold start, otherwise a user who changes the interval and never opens the
 *     settings screen again keeps the old cadence forever. The notification
 *     alarm is re-armed here for the same reason, and because a missed boot
 *     broadcast should cost one launch rather than a reinstall.
 */
class LessonsApplication : Application() {

    /**
     * Process-scoped scope. It is never cancelled because the only thing running
     * in it is the settings observer below, which must live exactly as long as
     * the process does.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        observeSyncInterval()
        // Off the main thread: it reads the cached timetable to work out what to
        // arm for, and Application.onCreate is on the critical path of every
        // cold start, including the one a widget update triggers.
        applicationScope.launch { SchoolAlerts.onAppStart(this@LessonsApplication) }
    }

    /**
     * Keeps the WorkManager cadence in step with the user's preference.
     *
     * Collecting instead of reading once means the settings screen only has to
     * write the new interval; rescheduling is this class's job, which keeps
     * [com.lumenpearson.lessons.ui.settings.SettingsViewModel] free of any
     * Android scheduling concern.
     */
    private fun observeSyncInterval() {
        applicationScope.launch {
            Graph.container.settingsRepository.settings
                .map { it.syncIntervalMinutes }
                .distinctUntilChanged()
                .collect { intervalMinutes ->
                    SyncScheduler.schedulePeriodic(this@LessonsApplication, intervalMinutes)
                }
        }
    }
}
