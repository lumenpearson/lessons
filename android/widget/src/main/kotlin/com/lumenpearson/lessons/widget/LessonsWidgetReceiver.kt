package com.lumenpearson.lessons.widget

// updateAll is an extension on GlanceAppWidget, not a member.
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.lumenpearson.lessons.core.data.sync.DataSyncBroadcast
import com.lumenpearson.lessons.core.data.sync.SyncScheduler
import com.lumenpearson.lessons.widget.tick.WidgetTickScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The app-widget provider the launcher talks to.
 *
 * Beyond Glance's own `APPWIDGET_UPDATE` handling this reacts to two things:
 * a system theme change (so Material You colours follow immediately rather than
 * at the next bell) and the sync broadcast from `:core:data` (so new homework
 * appears the moment it lands, not up to fifteen minutes later).
 */
class LessonsWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = LessonsWidget()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            Intent.ACTION_CONFIGURATION_CHANGED, DataSyncBroadcast.ACTION -> redrawAll(context)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Re-arm on every update: the launcher calls this after a restore, a
        // resize and an app upgrade, all of which drop pending alarms.
        rescheduleOffMainThread(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // The first widget was just placed. It very likely has nothing to show,
        // so pull data immediately rather than waiting for the periodic worker.
        SyncScheduler.syncNow(context)
        rescheduleOffMainThread(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // No widgets left: stop waking the device for a countdown nobody sees.
        WidgetTickScheduler.cancel(context)
    }

    /**
     * Re-arms the alarm chain without blocking the caller.
     *
     * `onUpdate` and `onEnabled` are plain `AppWidgetProvider` callbacks and run
     * on the main thread — unlike the tick receiver, which already holds a
     * `PendingResult`. The scheduler reads the cache to work out when the next
     * bell is, so calling it directly opened Room and DataStore on the main
     * looper, on the very first placement, on a cold process. That is how an
     * `APPWIDGET_ENABLED` broadcast turns into an ANR.
     */
    private fun rescheduleOffMainThread(context: Context) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        scope.launch {
            try {
                WidgetTickScheduler.reschedule(appContext)
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Rescheduling failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Redraws every placed instance.
     *
     * `goAsync` is what keeps the process alive past `onReceive` returning; a
     * plain `launch` would be killed mid-render. Glance's own actions already
     * consume this receiver's `PendingResult` for the intents it handles, which
     * is why the tick alarm lives in a separate receiver rather than here.
     */
    private fun redrawAll(context: Context) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        scope.launch {
            try {
                glanceAppWidget.updateAll(appContext)
            } catch (error: Exception) {
                // A redraw failure must never crash the launcher's broadcast.
                android.util.Log.w(TAG, "Widget redraw failed", error)
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

    private companion object {
        const val TAG = "LessonsWidget"
    }
}
