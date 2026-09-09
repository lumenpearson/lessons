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
 * Receives the tick alarm and the system events that invalidate it.
 *
 * Separate from [com.lumenpearson.lessons.widget.LessonsWidgetReceiver] because
 * a `BroadcastReceiver` can hand out its `PendingResult` only once, and Glance's
 * receiver already spends that on the actions it handles itself.
 *
 * Reboot, package replacement, a manual clock change and a timezone change all
 * silently drop pending alarms, so each of them has to re-arm. A widget frozen
 * on yesterday's lesson is the most visible way this feature can fail.
 */
class WidgetTickReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        scope.launch {
            try {
                // Redraw first, then re-arm: the new state is what decides when
                // the next wake-up should be.
                LessonsWidget().updateAll(appContext)
                WidgetTickScheduler.reschedule(appContext)
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Tick handling failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_TICK = "com.lumenpearson.lessons.widget.action.TICK"
        private const val TAG = "WidgetTick"
    }
}
