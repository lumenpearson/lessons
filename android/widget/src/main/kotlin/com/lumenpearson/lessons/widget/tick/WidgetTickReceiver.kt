package com.lumenpearson.lessons.widget.tick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.lumenpearson.lessons.widget.LessonsWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives the tick alarm, the sync broadcast, and the system events that
 * invalidate the alarm.
 *
 * Separate from [com.lumenpearson.lessons.widget.LessonsWidgetReceiver] because
 * a `BroadcastReceiver` can hand out its `PendingResult` only once, and Glance's
 * receiver already spends that on the actions it handles itself.
 *
 * Reboot, package replacement, a manual clock change and a timezone change all
 * silently drop pending alarms, so each of them has to re-arm. A widget frozen
 * on yesterday's lesson is the most visible way this feature can fail.
 *
 * `DATA_SYNCED` from `:core:data` lands here too, and needs no branch: fresh
 * homework wants exactly what a tick wants — redraw, then work out the next
 * wake-up from what is now on screen. It is answered here rather than on the
 * provider because that one must be exported for the launcher and would take
 * the action from any app on the device; this one is exported="false", so the
 * only sender is the app itself.
 */
class WidgetTickReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        scope.launch {
            try {
                // Redraw first: the new state is what decides when the next
                // wake-up should be.
                LessonsWidget().updateAll(appContext)
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Tick handling failed", error)
            } finally {
                // Re-arming lives in `finally`, because it is the only thing
                // that keeps the chain alive. After `updateAll`, one throw — a
                // transient Room error, a RemoteViews payload over the binder
                // limit — meant no alarm was ever armed again, and with
                // updatePeriodMillis at 0 nothing else re-arms it. The widget
                // froze on its last frame until a reboot.
                WidgetTickScheduler.reschedule(appContext)
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_TICK = "com.lumenpearson.lessons.widget.action.TICK"
        private const val TAG = "WidgetTick"
    }
}
