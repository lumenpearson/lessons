package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.Code
import com.connectrpc.ConnectErrorDetail
import com.connectrpc.ConnectException
import com.google.protobuf.Duration
import com.google.rpc.BadRequest
import com.google.rpc.ErrorInfo
import com.google.rpc.RetryInfo
import java.io.IOException
import java.time.Duration as JavaDuration
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class RpcRefusalsTest {
    @Test
    fun `RPC details preserve future reasons metadata violations and retry delay`() {
        val info = ErrorInfo.newBuilder().setReason("A_FUTURE_REASON")
            .putMetadata("required_role", "ROLE_ADMIN").build()
        val bad = BadRequest.newBuilder().addFieldViolations(
            BadRequest.FieldViolation.newBuilder().setField("code").setDescription("Too short"),
        ).build()
        val retry = RetryInfo.newBuilder().setRetryDelay(
            Duration.newBuilder().setSeconds(3).setNanos(500_000_000),
        ).build()
        val exception = ConnectException(Code.RESOURCE_EXHAUSTED, "Wait").withErrorDetails(LiteErrorDetails, listOf(
            ConnectErrorDetail("google.rpc.ErrorInfo", info.toByteArray().toByteString()),
            ConnectErrorDetail("google.rpc.BadRequest", bad.toByteArray().toByteString()),
            ConnectErrorDetail("google.rpc.RetryInfo", retry.toByteArray().toByteString()),
        ))

        val error = RpcRefusals.decode(exception, 429)

        assertEquals(Code.RESOURCE_EXHAUSTED, error.code)
        assertEquals("A_FUTURE_REASON", error.reason)
        assertEquals(mapOf("required_role" to "ROLE_ADMIN"), error.metadata)
        assertEquals(RemoteViolation("code", "Too short"), error.violations.single())
        assertEquals(JavaDuration.ofMillis(3500), error.retryDelay)
        assertEquals(429, error.httpStatus)
    }

    @Test
    fun `an answered refusal wrapping IO is still a refusal`() {
        val original = IOException("synthetic protocol failure")
        val error = RpcRefusals.decode(ConnectException(Code.PERMISSION_DENIED, exception = original), 403)

        assertFalse((error as Throwable) is IOException)
        assertSame(original, error.cause)
        assertEquals(403, error.httpStatus)
    }
}
