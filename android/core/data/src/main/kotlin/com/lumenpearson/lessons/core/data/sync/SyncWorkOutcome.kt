package com.lumenpearson.lessons.core.data.sync

import com.lumenpearson.lessons.core.data.repository.SyncResult

internal sealed interface SyncWorkOutcome {
    data object Success : SyncWorkOutcome
    data object Retry : SyncWorkOutcome
    data class Failure(val reason: String) : SyncWorkOutcome
}

internal fun SyncResult.workOutcome(attempt: Int): SyncWorkOutcome = when (this) {
    SyncResult.Success -> SyncWorkOutcome.Success
    SyncResult.ClientTooOld -> SyncWorkOutcome.Failure(SyncWorker.REASON_CLIENT_TOO_OLD)
    SyncResult.Unauthorised -> SyncWorkOutcome.Failure(SyncWorker.REASON_UNAUTHORISED)
    SyncResult.NotConfigured -> SyncWorkOutcome.Failure(SyncWorker.REASON_NOT_CONFIGURED)
    SyncResult.NeedsHttps -> SyncWorkOutcome.Failure(SyncWorker.REASON_NEEDS_HTTPS)
    is SyncResult.Failed -> if (attempt < MAX_ATTEMPTS) SyncWorkOutcome.Retry else SyncWorkOutcome.Failure(message)
}

private const val MAX_ATTEMPTS = 3
