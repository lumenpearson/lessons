package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.MethodSpec
import com.connectrpc.StreamResult
import com.connectrpc.StreamType
import com.connectrpc.http.Cancelable
import com.connectrpc.http.HTTPClientInterface
import com.connectrpc.http.HTTPRequest
import com.connectrpc.http.HTTPResponse
import com.connectrpc.http.Stream
import com.connectrpc.http.UnaryHTTPRequest
import com.connectrpc.protocols.NetworkProtocol
import com.lumenpearson.lessons.contract.v2.CreateDeviceRequest
import com.lumenpearson.lessons.contract.v2.CreateDeviceResponse
import com.lumenpearson.lessons.core.data.network.ServerAddressMissingException
import com.lumenpearson.lessons.core.data.network.ServerNeedsHttpsException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcCallsTest {
    private val method = MethodSpec(
        "lessons.v2.DeviceService/CreateDevice", CreateDeviceRequest::class,
        CreateDeviceResponse::class, StreamType.UNARY,
    )

    @Test
    fun `without a response each original IO address HTTPS and cancellation object survives`() = runBlocking {
        val originals = listOf(
            IOException("synthetic"), SocketTimeoutException("synthetic"), UnknownHostException("synthetic"),
            ServerAddressMissingException(), ServerNeedsHttpsException("school.invalid"),
            CancellationException("synthetic"),
        )
        for (original in originals) {
            val http = CallbackClient { _, done -> done(HTTPResponse(
                status = null, headers = emptyMap(), message = Buffer(), trailers = emptyMap(),
                cause = ConnectException(Code.UNAVAILABLE, exception = original),
            )) }
            val rpc = RpcCalls(http, NetworkProtocol.CONNECT)

            val actual = runCatching { rpc.unary(method, CreateDeviceRequest.getDefaultInstance()) }.exceptionOrNull()

            assertSame(original, actual)
        }
    }

    @Test
    fun `concurrent success refusal and no answer never exchange observed status`() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val callbacks = ConcurrentHashMap<String, (HTTPResponse) -> Unit>()
        val http = CallbackClient { request, done ->
            val code = CreateDeviceRequest.parseFrom(request.message.readByteArray()).code
            callbacks[code] = done
            if (callbacks.size == 3) ready.complete(Unit)
        }
        val rpc = RpcCalls(http, NetworkProtocol.CONNECT)
        val success = async { rpc.unary(method, CreateDeviceRequest.newBuilder().setCode("success").build()) }
        val refusal = async {
            runCatching { rpc.unary(method, CreateDeviceRequest.newBuilder().setCode("refusal").build()) }
        }
        val offline = async {
            runCatching { rpc.unary(method, CreateDeviceRequest.newBuilder().setCode("offline").build()) }
        }
        withTimeout(5000) { ready.await() }
        val original = SocketTimeoutException("synthetic offline")
        callbacks.getValue("refusal")(HTTPResponse(
            status = 403, headers = mapOf("X-Lessons-Observed-Status" to listOf("200")),
            message = Buffer(), trailers = emptyMap(), cause = ConnectException(Code.PERMISSION_DENIED),
        ))
        callbacks.getValue("offline")(HTTPResponse(
            status = null, headers = emptyMap(), message = Buffer(), trailers = emptyMap(),
            cause = ConnectException(Code.UNAVAILABLE, exception = original),
        ))
        callbacks.getValue("success")(HTTPResponse(
            status = 200, headers = mapOf("content-type" to listOf("application/proto")),
            message = Buffer().write(byteArrayOf(10, 12)).writeUtf8("server-token"), trailers = emptyMap(),
        ))

        assertEquals("server-token", success.await().token)
        val failure = refusal.await().exceptionOrNull()
        assertTrue(failure is RemoteError)
        assertEquals(403, (failure as RemoteError).httpStatus)
        assertSame(original, offline.await().exceptionOrNull())
    }

    private class CallbackClient(
        private val respond: (UnaryHTTPRequest, (HTTPResponse) -> Unit) -> Unit,
    ) : HTTPClientInterface {
        override fun unary(request: UnaryHTTPRequest, onResult: (HTTPResponse) -> Unit): Cancelable {
            respond(request, onResult)
            return {}
        }

        override fun stream(
            request: HTTPRequest, duplex: Boolean, onResult: suspend (StreamResult<Buffer>) -> Unit,
        ): Stream = error("This fixture exercises unary calls only")
    }
}
