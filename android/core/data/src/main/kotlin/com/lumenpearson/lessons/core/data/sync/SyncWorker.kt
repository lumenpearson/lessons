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
            is SyncResult.Success -> {
                DataSyncBroadcast.send(applicationContext)
                Result.success()
            }

            is SyncResult.Unauthorised -> Result.failure(
                workDataOf(KEY_ERROR to REASON_UNAUTHORISED),
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

        /** Two weeks: enough for the widget to survive a holiday offline. */
        const val DEFAULT_DAYS: Int = 14

        /**
         * After three tries the next scheduled run will happen sooner than the
         * backoff would anyway, so there is no point retrying further.
         */
        private const val MAX_ATTEMPTS = 3
    }
}
