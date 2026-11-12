package com.lumenpearson.lessons.core.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.diagnostics.ActivityKind
import com.lumenpearson.lessons.core.data.diagnostics.ActivityLog
import com.lumenpearson.lessons.core.data.repository.SyncResult

/**
 * Pulls a fresh schedule window into the cache in the background.
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
 *  * [SyncResult.ClientTooOld] permanently fails this run without removing
 *    memberships or cached rows; the foreground asks for an APK update.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // The worker may be the first thing to run after a process restart.
        Graph.ensureInitialized(applicationContext)
        val container = Graph.container

        // Nothing to sync before the device has joined a class. Reporting
        // success stops WorkManager from burning backoff on a no-op.
        if (container.sessionRepository.current() == null) {
            return Result.success()
        }

        // Before the sync, not after: the rows of a class this device has left
        // are dead weight that no screen can reach, and the one thing that can
        // create them — a sync that landed after the class was left — is the
        // very job this is. Cheap: five deletes that match nothing on a phone
        // that has left no class.
        runCatching {
            container.timetableRepository.forgetClassesOtherThan(
                container.sessionRepository.currentAll().map { it.classId }.toSet(),
            )
        }

        val result = container.timetableRepository.refresh()
        ActivityLog.record(
            ActivityKind.SYNC,
            "background sync, attempt ${runAttemptCount + 1}: ${result::class.java.simpleName}",
        )
        return when (val outcome = result.workOutcome(runAttemptCount)) {
            SyncWorkOutcome.Success -> Result.success()
            SyncWorkOutcome.Retry -> Result.retry()
            is SyncWorkOutcome.Failure -> Result.failure(workDataOf(KEY_ERROR to outcome.reason))
        }
    }

    companion object {
        /** Output: why the run failed, for the app to surface or log. */
        const val KEY_ERROR: String = "error"

        /** Value of [KEY_ERROR] that means "join again"; the app matches on it. */
        const val REASON_UNAUTHORISED: String = "unauthorised"

        /** @see SyncResult.NotConfigured */
        const val REASON_NOT_CONFIGURED: String = "not_configured"

        /** @see SyncResult.NeedsHttps */
        const val REASON_NEEDS_HTTPS: String = "needs_https"

        /** @see SyncResult.ClientTooOld */
        const val REASON_CLIENT_TOO_OLD: String = "client_too_old"
    }
}
