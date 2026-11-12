package com.lumenpearson.lessons.core.data.repository

import com.connectrpc.Code
import com.lumenpearson.lessons.core.data.network.v2.RemoteError
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinV2FailureTest {
    @Test
    fun `reason distinguishes a full class from throttling despite their shared code and status`() {
        val full = RemoteError(Code.RESOURCE_EXHAUSTED, "DEVICE_LIMIT_REACHED", httpStatus = 429)
        val throttle = RemoteError(Code.RESOURCE_EXHAUSTED, "THROTTLED", httpStatus = 429,
            retryDelay = Duration.ofMillis(901_500))
        assertEquals(JoinFailure.ClassFull, JoinFailure.of(full))
        assertEquals(JoinFailure.TooManyAttempts(902), JoinFailure.of(throttle))
    }

    @Test
    fun `known join reasons win over a proxy status`() {
        assertEquals(JoinFailure.UnknownCode,
            JoinFailure.of(RemoteError(Code.NOT_FOUND, "JOIN_CODE_UNKNOWN", httpStatus = 400)))
        assertEquals(JoinFailure.InviteOnly,
            JoinFailure.of(RemoteError(Code.PERMISSION_DENIED, "CLASS_INVITE_ONLY", httpStatus = 400)))
    }

    @Test
    fun `throttle metadata is read explicitly and unreadable delays remain unnumbered`() {
        for (raw in listOf("soon", "", "0", "-1", "2147483648")) {
            assertEquals(JoinFailure.TooManyAttempts(null), JoinFailure.of(RemoteError(
                Code.RESOURCE_EXHAUSTED, "THROTTLED", mapOf("retry_after_seconds" to raw), httpStatus = 429)))
        }
        assertEquals(JoinFailure.TooManyAttempts(901), JoinFailure.of(RemoteError(
            Code.RESOURCE_EXHAUSTED, "THROTTLED", mapOf("retry_after_seconds" to "901"), httpStatus = 429)))
    }

    @Test
    fun `a reasonless missing v2 is a server compatibility cue and never an unknown class code`() {
        for (status in listOf(404, 501, 200)) {
            val code = if (status == 200) Code.UNIMPLEMENTED else Code.UNKNOWN
            val error = RemoteError(code, httpStatus = status)
            val actual = JoinFailure.of(error)
            assertTrue(actual is JoinFailure.Rejected)
            actual as JoinFailure.Rejected
            assertTrue(actual.serverTooOld)
            assertSame(error, actual.reason)
        }
    }

    @Test
    fun `FEATURE_UNSUPPORTED is a v2 refusal rather than missing v2`() {
        val error = RemoteError(Code.UNIMPLEMENTED, "FEATURE_UNSUPPORTED", httpStatus = 501)
        val actual = JoinFailure.of(error) as JoinFailure.Rejected
        assertFalse(actual.serverTooOld)
        assertSame(error, actual.reason)
        assertEquals(501, actual.code)
    }

    @Test
    fun `future reasons remain attached to a refusal and do not become offline`() {
        val error = RemoteError(Code.INTERNAL_ERROR, "A_FUTURE_REASON", httpStatus = 500)
        val actual = JoinFailure.of(error) as JoinFailure.Rejected
        assertSame(error, actual.reason)
        assertEquals(500, actual.code)
    }
}
