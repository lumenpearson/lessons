package com.lumenpearson.lessons.ui.common

import com.lumenpearson.lessons.core.data.repository.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A failed refresh is worded by the app, never by the exception (#177).
 *
 * The repository's failure message is for a log — OkHttp's «Failed to connect
 * to /127.0.0.1:8000», «Server returned HTTP 502» — and it used to reach the
 * snackbar after «Не удалось обновить:». Whatever it says, the screen gets the
 * one sentence written for a refresh that did not happen.
 */
class SyncMessageTest {

    @Test
    fun `a transport failure's own message does not reach the screen`() {
        assertEquals(
            SyncMessage.Failed,
            SyncResult.Failed("Failed to connect to /127.0.0.1:8000").toMessageOrNull(),
        )
        assertEquals(SyncMessage.Failed, SyncResult.Failed("Server returned HTTP 502").toMessageOrNull())
    }

    @Test
    fun `the cases with a remedy keep their own sentences`() {
        assertEquals(SyncMessage.Unauthorised, SyncResult.Unauthorised.toMessageOrNull())
        assertEquals(SyncMessage.NotConfigured, SyncResult.NotConfigured.toMessageOrNull())
        // An address that is there but http:// is not «no address» (#202).
        assertEquals(SyncMessage.NeedsHttps, SyncResult.NeedsHttps.toMessageOrNull())
        assertNull(SyncResult.Success.toMessageOrNull())
    }
}
