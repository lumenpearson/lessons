package com.lumenpearson.lessons.core.data.di

import android.content.Context
import androidx.annotation.VisibleForTesting

/**
 * The process-wide service locator, initialised from `Application.onCreate`.
 *
 * Why this and not Hilt, deliberately:
 *
 *  * The two components that need the repositories most - the Glance app widget
 *    and the WorkManager workers - are not Hilt-friendly entry points. A Glance
 *    `GlanceAppWidget` is constructed by the framework with no injection site,
 *    and getting a worker injected means `@HiltWorker`, a custom
 *    `Configuration.Provider` and an app-level `WorkerFactory`. All three would
 *    end up calling an `EntryPointAccessors.fromApplication(...)` anyway - which
 *    is a service locator with more ceremony.
 *  * Hilt adds a second annotation processor over every module. Room's KSP round
 *    is already the slowest step in this build; one processor is cheaper than
 *    two, and this app has exactly three injectable objects.
 *  * The whole graph is three lazy singletons with no scoping and no runtime
 *    variants. There is nothing here for a DI framework to decide.
 *
 * The cost is that missing initialisation fails at runtime instead of at compile
 * time, so [container] throws with an instruction rather than an NPE.
 */
object Graph {

    @Volatile
    private var instance: LessonsContainer? = null

    /**
     * Idempotent, and safe to call from any entry point: workers and the widget
     * receiver may run in a process where `Application.onCreate` has already
     * done this, or - after a process restart triggered by WorkManager - be the
     * first thing to touch it.
     */
    fun init(context: Context) {
        if (instance != null) return
        synchronized(this) {
            if (instance == null) {
                instance = DefaultLessonsContainer(context.applicationContext)
            }
        }
    }

    /** @throws IllegalStateException if [init] has not run in this process yet. */
    val container: LessonsContainer
        get() = instance ?: error(
            "LessonsContainer is not initialised. Call Graph.init(context) from " +
                "Application.onCreate() before touching any repository.",
        )

    /**
     * Replaces the graph with fakes. Tests only: there is no way to restore the
     * previous instance, and no production code path should ever swap the graph
     * out from under a running screen.
     */
    @VisibleForTesting
    fun override(container: LessonsContainer) {
        synchronized(this) {
            instance = container
        }
    }
}
