package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.Code
import com.connectrpc.ConnectErrorDetail
import com.connectrpc.ConnectException
import com.google.rpc.ErrorInfo
import kotlinx.coroutines.flow.MutableStateFlow
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompatibilityRefusalsTest {
    @Before fun before() = clearLatch()
    @After fun after() = clearLatch()

    @Test
    fun `a REST ErrorInfo refusal reaches the process state without a UI collector`() {
        RestRefusals.decode(400, """{"error":{"status":"FAILED_PRECONDITION","details":[
            {"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"CLIENT_TOO_OLD"}]}}""")
        assertTrue(ClientCompatibility.updateRequired.value)
    }

    @Test
    fun `a binary RPC ErrorInfo refusal reaches the same process state`() {
        val info = ErrorInfo.newBuilder().setReason("CLIENT_TOO_OLD").build()
        val error = ConnectException(Code.FAILED_PRECONDITION).withErrorDetails(LiteErrorDetails,
            listOf(ConnectErrorDetail("google.rpc.ErrorInfo", info.toByteArray().toByteString())))
        RpcRefusals.decode(error, 400)
        assertTrue(ClientCompatibility.updateRequired.value)
    }

    private fun clearLatch() {
        val latch = ClientCompatibility.javaClass.getDeclaredField("latch").apply { isAccessible = true }
            .get(ClientCompatibility)
        val required = latch.javaClass.getDeclaredField("required").apply { isAccessible = true }.get(latch)
        @Suppress("UNCHECKED_CAST")
        (required as MutableStateFlow<Boolean>).value = false
        latch.javaClass.getDeclaredField("claimed").apply { isAccessible = true }.set(latch, false)
    }
}
