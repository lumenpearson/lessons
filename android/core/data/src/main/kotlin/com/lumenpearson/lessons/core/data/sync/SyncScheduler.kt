package com.lumenpearson.lessons.core.data.sync

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.lumenpearson.lessons.core.data.repository.AppSettings
import java.util.concurrent.TimeUnit

/**
 * The only place that enqueues [SyncWorker].
 *
 * Both entry points use unique work so that the app, the widget and the settings
 * screen can all ask for a sync without ever stacking duplicate requests on a
 * device that has been offline all day.
 */
object SyncScheduler {

    /** Unique name of the background refresh; stable across app versions. */
    const val PERIODIC_WORK_NAME: String = "lessons-sync-periodic"

    /** Unique name of the user-visible "refresh now" run. */
    const val ONE_TIME_WORK_NAME: String = "lessons-sync-now"

    /** Only run when there is a network; there is nothing to do without one. */
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Installs or updates the background refresh.
     *
     * `UPDATE` rather than `REPLACE`: changing the interval in settings must not
     * reset the period and delay the next run by a full cycle.
     *
     * @param intervalMinutes clamped to WorkManager's 15-minute floor, below
     * which the platform silently rounds up anyway.
     */
    fun schedulePeriodic(context: Context, intervalMinutes: Int) {
        val interval = intervalMinutes.toLong()
            .coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES.toLong())

        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            // fallback: WorkRequest.MIN_BACKOFF_MILLIS is 10 seconds.
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * Refreshes as soon as the platform allows - pull to refresh, a widget tap,
     * the moment after joining a class.
     *
     * `KEEP` rather than `REPLACE`: if a sync is already running it will produce
     * exactly the data this call wants, and cancelling it would only make the
     * user wait longer.
     *
     * @param wantsDifferentData the running sync is **not** fetching what this
     * call is asking for, so dropping this request would leave the caller with
     * nothing. True for a class switch: a device may hold several classes, the
     * job already in flight was signed with the previous one's token, and its
     * result is filed under the class the server resolved — so it can neither
     * serve nor be mistaken for the class now on screen. The request is
     * appended rather than replacing, because the running one is still fetching
     * something somebody asked for.
     */
    fun syncNow(
        context: Context,
        wantsDifferentData: Boolean = false,
    ) {
        // Nothing to hand it. The window is the school year that holds today,
        // which the repository works out for itself — a caller asking for «a
        // month» was asking for a window that no longer exists, and the number
        // was carried through WorkManager's input data to be ignored.
        val builder = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)

        // Expedited work runs as a foreground service below API 31, which would
        // force this module to ship a notification channel and a
        // FOREGROUND_SERVICE permission for a two-second HTTP call. On 31+ the
        // platform's expedited job needs neither, so the flag is set only there;
        // older devices get ordinary - and in practice near-immediate - work.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            if (wantsDifferentData) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            builder.build(),
        )
    }

    /** Stops background refreshing, e.g. on sign-out. */
    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(PERIODIC_WORK_NAME)
    }
}
