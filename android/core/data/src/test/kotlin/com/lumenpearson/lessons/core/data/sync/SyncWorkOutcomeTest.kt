package com.lumenpearson.lessons.core.data.sync

import com.lumenpearson.lessons.core.data.repository.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkOutcomeTest {
    @Test
    fun `an obsolete client permanently fails on every attempt`() {
        for (attempt in listOf(0, 1, 3, 100)) {
            assertEquals(SyncWorkOutcome.Failure("client_too_old"), SyncResult.ClientTooOld.workOutcome(attempt))
        }
    }

    @Test
    fun `only a transient failure retries within the existing budget`() {
        val failed = SyncResult.Failed("synthetic")
        assertEquals(SyncWorkOutcome.Retry, failed.workOutcome(0))
        assertEquals(SyncWorkOutcome.Retry, failed.workOutcome(2))
        assertEquals(SyncWorkOutcome.Failure("synthetic"), failed.workOutcome(3))
        assertEquals(SyncWorkOutcome.Success, SyncResult.Success.workOutcome(0))
        assertEquals(SyncWorkOutcome.Failure("unauthorised"), SyncResult.Unauthorised.workOutcome(0))
        assertEquals(SyncWorkOutcome.Failure("not_configured"), SyncResult.NotConfigured.workOutcome(0))
        assertEquals(SyncWorkOutcome.Failure("needs_https"), SyncResult.NeedsHttps.workOutcome(0))
    }
}
