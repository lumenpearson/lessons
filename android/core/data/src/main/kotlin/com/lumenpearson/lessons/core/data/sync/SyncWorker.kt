package com.lumenpearson.lessons.core.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.SyncResult

/**
 * Pulls a fresh bundle into the cache in the background.
 *
 * It reaches for its dependencies through [Graph] rather than being injected:
 * WorkManager builds workers reflectively, and a service locator is what makes
 * that possible without an app-level `WorkerFactory` (see the KDoc on [Graph]).
 *
 * Failure handling is the interesting part:
 *  * transient failures ask WorkManager to [Result.retry], which applies the
 *    request's backoff instead of hammering a school server that is down;
 *  * [SyncResult.Unauthorised] is a permanent [Result.failure] - retrying a
 *    revoked token can only ever fail, and the reason is put in the output data
 *    so the app can react by sending the user back to the join screen.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // The worker may be the first thing to run after a process restart.
        Graph.init(applicationContext)
        val container = Graph.container

        // Nothing to sync before the device has joined a class. Reporting
        // success stops WorkManager from burning backoff on a no-op.
        if (container.sessionRepository.current() == null) {
            return Result.success()
        }

        val days = inputData.getInt(KEY_DAYS, DEFAULT_DAYS)
        return when (val result = container.timetableRepository.refresh(days)) {
            // The repository broadcasts on its own now, so that an in-app
            // refresh reaches the widget too; this path needs nothing extra.
            is SyncResult.Success -> Result.success()

            is SyncResult.Unauthorised -> Result.failure(
                workDataOf(KEY_ERROR to REASON_UNAUTHORISED),
            )

            // Retrying on a timer cannot invent a server address. Fail once and
            // leave it; the next run after the user sets one will go through.
            is SyncResult.NotConfigured -> Result.failure(
                workDataOf(KEY_ERROR to REASON_NOT_CONFIGURED),
            )

            is SyncResult.Failed ->
                if (runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure(workDataOf(KEY_ERROR to result.message))
                }
        }
    }

    companion object {
        /** Input: how many days to fetch. */
        const val KEY_DAYS: String = "days"

        /** Output: why the run failed, for the app to surface or log. */
        const val KEY_ERROR: String = "error"

        /** Value of [KEY_ERROR] that means "join again"; the app matches on it. */
        const val REASON_UNAUTHORISED: String = "unauthorised"
        
        /** @see SyncResult.NotConfigured */
        const val REASON_NOT_CONFIGURED: String = "not_configured"

        /**
         * A month, which is what the calendar's month view needs to be a month.
         *
         * It was two weeks, chosen so the widget could survive a holiday
         * offline. A month grid drawn over a two-week cache is half real and
         * half "нет данных", and the difference between the two on the wire is
         * one integer in a query string — the server already caps the window at
         * 31 days and resolves the whole range in one query.
         */
        const val DEFAULT_DAYS: Int = 31

        /**
         * After three tries the next scheduled run will happen sooner than the
         * backoff would anyway, so there is no point retrying further.
         */
        private const val MAX_ATTEMPTS = 3
    }
}
