package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.Code
import com.lumenpearson.lessons.core.data.catalog.DirectoryProblem
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemainingFailureTest {
    @Test
    fun `old servers are distinguished from feature unsupported and future reasons`() {
        for (error in listOf(remainingRefusal(null, status = 404), remainingRefusal(null, status = 501),
            remainingRefusal(null, Code.UNIMPLEMENTED, 200))) {
            assertEquals(DiarySignInProblem.ServerTooOld, DiarySignInProblem.of(error))
            assertEquals(DirectoryProblem.ServerTooOld, DirectoryProblem.of(error))
        }
        for (reason in listOf("FEATURE_UNSUPPORTED", "FUTURE_REASON")) {
            val error = remainingRefusal(reason, Code.UNIMPLEMENTED, 501)
            assertTrue(DiarySignInProblem.of(error) is DiarySignInProblem.Unexpected)
            assertTrue(DirectoryProblem.of(error) is DirectoryProblem.Unexpected)
        }
    }

    @Test
    fun `retry info rounds up fractional seconds and metadata is a fallback`() {
        val info = remainingRefusal("THROTTLED", delay = Duration.ofMillis(1201))
        assertEquals(DiaryFailure.Throttled(2), DiaryFailure.of(info))
        assertEquals(DiarySignInProblem.TooManyAttempts(2), DiarySignInProblem.of(info))
        assertEquals(DirectoryProblem.Throttled(2), DirectoryProblem.of(info))
        val metadata = mapOf("retry_after_seconds" to "17")
        assertEquals(DirectoryProblem.Spent(17), DirectoryProblem.of(
            remainingRefusal("DIRECTORY_SPENT", metadata = metadata),
        ))
        assertEquals(DiaryFailure.Throttled(17), DiaryFailure.of(remainingRefusal("THROTTLED", metadata = metadata)))
        assertEquals(DirectoryProblem.Throttled(null), DirectoryProblem.of(
            remainingRefusal("THROTTLED", metadata = mapOf("retry_after_seconds" to "-3"),
                delay = Duration.ofSeconds(-1)),
        ))
    }

    @Test
    fun `undecodable requests are app defects even with a validation status`() {
        val error = remainingRefusal("REQUEST_UNDECODABLE", Code.INVALID_ARGUMENT, 400)
        assertTrue(ManageFailure.of(error) is ManageFailure.Unexpected)
        assertTrue(DiaryFailure.of(error) is DiaryFailure.Unexpected)
        assertTrue(DiarySignInProblem.of(error) is DiarySignInProblem.Unexpected)
        assertTrue(DirectoryProblem.of(error) is DirectoryProblem.Unexpected)
    }

    @Test
    fun `client update refusal is shared and never becomes sign out or wrong password`() {
        val error = remainingRefusal("CLIENT_TOO_OLD", Code.FAILED_PRECONDITION, 400)
        assertTrue(ClientCompatibility.updateRequired.value)
        assertTrue(ManageFailure.of(error) is ManageFailure.Unexpected)
        assertTrue(DiaryFailure.of(error) is DiaryFailure.Unexpected)
        assertTrue(DiarySignInProblem.of(error) is DiarySignInProblem.Unexpected)
        assertTrue(DirectoryProblem.of(error) is DirectoryProblem.Unexpected)
    }

    @Test
    fun `no answer retains its cause and cancellation is never classified`() {
        val io = IOException("offline")
        assertSame(io, (ManageFailure.of(io) as ManageFailure.Offline).reason)
        assertSame(io, (DiaryFailure.of(io) as DiaryFailure.Offline).reason)
        assertEquals(DiarySignInProblem.RegisterUnreachable, DiarySignInProblem.of(io, registering = true))
        assertEquals(DirectoryProblem.Offline, DirectoryProblem.of(io))
        val cancellation = CancellationException("screen closed")
        for (classify in listOf<(Throwable) -> Any>(
            { ManageFailure.of(it) }, { DiaryFailure.of(it) },
            { DiarySignInProblem.of(it) }, { DirectoryProblem.of(it) },
        )) assertSame(cancellation, runCatching { classify(cancellation) }.exceptionOrNull())
    }
}
