package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.MethodSpec
import com.connectrpc.StreamResult
import com.connectrpc.StreamType
import com.connectrpc.http.Cancelable
import com.connectrpc.http.HTTPClientInterface
import com.connectrpc.http.HTTPRequest
import com.connectrpc.http.HTTPResponse
import com.connectrpc.http.Stream
import com.connectrpc.http.UnaryHTTPRequest
import com.lumenpearson.lessons.contract.v2.CreateDeviceRequest
import com.lumenpearson.lessons.contract.v2.CreateDeviceResponse
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTransportTest {
    @Test
    fun `Connect and grpc use their actual request and response envelopes`() = runBlocking {
        val method = MethodSpec("lessons.v2.DeviceService/CreateDevice", CreateDeviceRequest::class,
            CreateDeviceResponse::class, StreamType.UNARY)
        for ((name, type, hex) in listOf(
            Triple("connect", "application/proto", "0a0644454d4f3234"),
            Triple("grpc", "application/grpc+proto", "00000000080a0644454d4f3234"),
        )) {
            val http = object : HTTPClientInterface {
                override fun unary(request: UnaryHTTPRequest, onResult: (HTTPResponse) -> Unit): Cancelable {
                    assertEquals(type, request.contentType)
                    assertEquals(hex, request.message.readByteString().hex())
                    val response = Buffer().writeByte(10).writeByte(1).writeUtf8("x")
                    val payload = if (name == "grpc") Buffer().writeByte(0).writeInt(3).apply {
                        writeAll(response)
                    } else response
                    onResult(HTTPResponse(status = 200, headers = mapOf("content-type" to listOf(type)),
                        message = payload,
                        trailers = if (name == "grpc") mapOf("grpc-status" to listOf("0")) else emptyMap()))
                    return {}
                }
                override fun stream(request: HTTPRequest, duplex: Boolean,
                    onResult: suspend (StreamResult<Buffer>) -> Unit): Stream = error("Unary fixture")
            }
            val actual = RpcCalls(http, AppTransport.parse(name).rpcProtocol)
                .unary(method, CreateDeviceRequest.newBuilder().setCode("DEMO24").build())
            assertEquals("x", actual.token)
        }
    }

    @Test
    fun `REST cannot accidentally construct an RPC client`() {
        assertTrue(runCatching { AppTransport.REST.rpcProtocol }.isFailure)
        assertTrue(runCatching { AppTransport.parse("typo") }.isFailure)
    }
}
