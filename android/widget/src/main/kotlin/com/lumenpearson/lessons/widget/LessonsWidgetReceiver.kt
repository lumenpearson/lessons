package com.lumenpearson.lessons.widget

// updateAll is an extension on GlanceAppWidget, not a member.
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver.PendingResult
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
 * Beyond Glance's own `APPWIDGET_UPDATE` handling this reacts to one thing: the
 * sync broadcast from `:core:data`, so new homework appears the moment it lands
 * rather than up to fifteen minutes later.
 *
 * It used to listen for `ACTION_CONFIGURATION_CHANGED` as well, to redraw on a
 * theme flip. That never ran: the platform does not deliver that broadcast to
 * manifest-declared receivers at all, only to ones registered with
 * `Context.registerReceiver`, and a widget has no running process to register
 * from. Night colours come from `res/values-night`, which the launcher resolves
 * when it inflates the `RemoteViews`, so the flip is handled without us.
 */
class LessonsWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = LessonsWidget()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            DataSyncBroadcast.ACTION -> redrawAll(context)
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
     *
     * The result is nullable because a `BroadcastReceiver` hands its
     * `PendingResult` out exactly once, and `onUpdate` is a dispatch where
     * Glance's own override has already taken it — every `APPWIDGET_UPDATE`
     * therefore reached `finish()` on null and threw out of a coroutine nobody
     * catches, which is a process death. Glance's own `goAsync` is keeping this
     * process alive for the same dispatch anyway; when there is no result to
     * hold, the re-arm simply rides along with it.
     */
    private fun rescheduleOffMainThread(context: Context) {
        val appContext = context.applicationContext
        val pendingResult: PendingResult? = goAsync()
        scope.launch {
            try {
                WidgetTickScheduler.reschedule(appContext)
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Rescheduling failed", error)
            } finally {
                pendingResult?.finish()
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
