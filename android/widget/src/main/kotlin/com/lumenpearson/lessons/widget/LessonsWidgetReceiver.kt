package com.lumenpearson.lessons.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver.PendingResult
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.lumenpearson.lessons.core.data.sync.SyncScheduler
import com.lumenpearson.lessons.widget.tick.WidgetTickScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The app-widget provider the launcher talks to.
 *
 * Beyond Glance's own `APPWIDGET_UPDATE` handling it does one thing: re-arm the
 * tick alarm on the placements and restores that silently drop it.
 *
 * It is the one receiver in this module that has to be exported — the launcher
 * is another process — which is why it is also the one that listens for as
 * little as possible. The sync broadcast from `:core:data` used to be answered
 * here; it is answered by [com.lumenpearson.lessons.widget.tick.WidgetTickReceiver]
 * instead, where the filter is not open to every app on the device.
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

    private companion object {
        const val TAG = "LessonsWidget"
    }
}
