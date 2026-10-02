package com.lumenpearson.lessons.core.data.diagnostics

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * The app coming to the front and leaving it, on the developer mode's activity
 * record (#237). Registered once, by the application; a no-op while the record
 * is off, which is what `ActivityLog.record` already is.
 *
 * Created, started, stopped and destroyed, and not resumed or paused: those
 * two come in pairs around every dialog and every permission prompt, and a
 * record of them would bury the moments that matter.
 */
object LifecycleRecorder : Application.ActivityLifecycleCallbacks {

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
        record(activity, if (savedInstanceState == null) "created" else "recreated")

    override fun onActivityStarted(activity: Activity) = record(activity, "started")

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = record(activity, "stopped")

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) =
        record(activity, if (activity.isChangingConfigurations) "destroyed for a configuration change" else "destroyed")

    private fun record(activity: Activity, what: String) {
        ActivityLog.record(ActivityKind.LIFECYCLE, "${activity.javaClass.simpleName} $what")
    }
}
