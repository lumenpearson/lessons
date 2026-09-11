package com.lumenpearson.lessons.core.data.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The other end of the alarm chain.
 *
 * Separate from the widget's tick receiver even though the two look alike: a
 * `BroadcastReceiver` may hand out its `PendingResult` exactly once, and sharing
 * one between two independent alarm chains means whichever finishes first ends
 * the other's work as well.
 *
 * It also listens for the three broadcasts that silently invalidate every armed
 * alarm on the device — a reboot, an app update, and a manual clock or timezone
 * change — because an alarm chain that only re-arms itself is a chain that stops
 * dead the first time a phone is switched off.
 */
internal class SchoolAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val action = intent.action

        // Room and DataStore are both disk, and a receiver's main thread is the
        // main thread. goAsync buys the work a background thread and keeps the
        // process alive until it is finished.
        val pending = goAsync()
        Thread {
            try {
                when (action) {
                    ACTION_ALERT -> SchoolAlerts.fire(appContext)

                    // Nothing is due at a reboot or a clock change; what matters
                    // is that the chain is armed again from the new wall time.
                    else -> SchoolAlerts.reschedule(appContext)
                }
            } catch (error: Throwable) {
                // A receiver that throws kills the process, and this one runs
                // while nobody is looking at the app. Losing one notification is
                // the cheaper failure.
                Log.w(TAG, "Alert work failed for $action", error)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "SchoolAlerts"

        /** Action of the alarm this receiver arms for itself. */
        const val ACTION_ALERT: String = "com.lumenpearson.lessons.action.SCHOOL_ALERT"
    }
}
