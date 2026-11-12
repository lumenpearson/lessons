package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.AnyError
import com.google.protobuf.Any as ProtoAny
import com.google.protobuf.ByteString as ProtoBytes
import com.google.protobuf.Duration
import com.google.rpc.BadRequest
import com.google.rpc.ErrorInfo
import com.google.rpc.RetryInfo
import com.google.rpc.Status
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiteErrorDetailsTest {
    @Test
    fun `status preserves the type and raw payload of known and future details`() {
        val info = ErrorInfo.newBuilder().setReason("JOIN_CODE_UNKNOWN").build()
        val status = Status.newBuilder().setCode(5)
            .addDetails(ProtoAny.newBuilder().setTypeUrl("type.googleapis.com/google.rpc.ErrorInfo")
                .setValue(info.toByteString()))
            .addDetails(ProtoAny.newBuilder().setTypeUrl("example.invalid/future.Detail")
                .setValue(ProtoBytes.copyFrom(byteArrayOf(8, 1))))
            .build()

        val details = LiteErrorDetails.parseDetails(status.toByteArray())

        assertEquals(2, details.size)
        assertEquals("type.googleapis.com/google.rpc.ErrorInfo", details[0].type)
        assertEquals(info.toByteArray().toByteString(), details[0].payload)
        assertEquals("example.invalid/future.Detail", details[1].type)
        assertEquals(byteArrayOf(8, 1).toByteString(), details[1].payload)
    }

    @Test
    fun `error info retains an unknown reason and its metadata`() {
        val message = ErrorInfo.newBuilder().setReason("A_FUTURE_REASON")
            .putMetadata("required_role", "ROLE_ADMIN").build()
        val decoded = LiteErrorDetails.unpack(
            AnyError("type.googleapis.com/google.rpc.ErrorInfo", message.toByteArray().toByteString()),
            ErrorInfo::class,
        )!!

        assertEquals("A_FUTURE_REASON", decoded.reason)
        assertEquals("ROLE_ADMIN", decoded.metadataMap["required_role"])
    }

    @Test
    fun `bad request unpacks its field violation`() {
        val message = BadRequest.newBuilder().addFieldViolations(
            BadRequest.FieldViolation.newBuilder().setField("code").setDescription("Too short"),
        ).build()
        val decoded = LiteErrorDetails.unpack(
            AnyError("type.googleapis.com/google.rpc.BadRequest", message.toByteArray().toByteString()),
            BadRequest::class,
        )!!

        assertEquals("code", decoded.fieldViolationsList.single().field)
        assertEquals("Too short", decoded.fieldViolationsList.single().description)
    }

    @Test
    fun `retry info preserves seconds and nanos`() {
        val message = RetryInfo.newBuilder().setRetryDelay(
            Duration.newBuilder().setSeconds(3).setNanos(500_000_000),
        ).build()
        val decoded = LiteErrorDetails.unpack(
            AnyError("type.googleapis.com/google.rpc.RetryInfo", message.toByteArray().toByteString()),
            RetryInfo::class,
        )!!

        assertEquals(3L, decoded.retryDelay.seconds)
        assertEquals(500_000_000, decoded.retryDelay.nanos)
    }

    @Test
    fun `a mismatched or unknown type is not decoded as the requested message`() {
        val payload = ErrorInfo.newBuilder().setReason("JOIN_CODE_UNKNOWN").build().toByteArray().toByteString()

        assertNull(LiteErrorDetails.unpack(
            AnyError("type.googleapis.com/google.rpc.ErrorInfo", payload), RetryInfo::class,
        ))
        assertNull(LiteErrorDetails.unpack(AnyError("future/ErrorInfo", payload), ErrorInfo::class))
        assertNull(LiteErrorDetails.unpack(
            AnyError("type.googleapis.com/google.rpc.ErrorInfo", payload), String::class,
        ))
    }
}
