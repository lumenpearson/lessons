package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.CleartextPolicy
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import com.connectrpc.Headers
import com.connectrpc.MethodSpec
import com.connectrpc.ProtocolClientConfig
import com.connectrpc.ProtocolClientInterface
import com.connectrpc.ResponseMessage
import com.connectrpc.StreamType
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.lumenpearson.lessons.contract.v2.CreateDeviceRequest
import com.lumenpearson.lessons.contract.v2.CreateDeviceResponse
import com.lumenpearson.lessons.contract.v2.CreateDiarySessionRequest
import com.lumenpearson.lessons.contract.v2.CreateDiarySessionResponse
import com.lumenpearson.lessons.contract.v2.GetDiaryCapabilitiesRequest
import com.lumenpearson.lessons.contract.v2.GetDiaryCapabilitiesResponse
import com.lumenpearson.lessons.contract.v2.ListSchoolRegionsRequest
import com.lumenpearson.lessons.contract.v2.ListSchoolRegionsResponse
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class V2AnonymousBearerTest(private val call: AnonymousCall) {
    @Test
    fun `an anonymous Connect call never receives either stored bearer or stale metadata`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val current = RequestCredentials(server.url("/school/").toString(), "fake-class", "fake-diary")
            val client = NetworkModule.okHttpClient({ current }, CleartextPolicy { true })
            val rpc = ProtocolClient(ConnectOkHttpClient(client), ProtocolClientConfig(
                host = "http://base-url.invalid/api/rpc", serializationStrategy = LiteProtobufStrategy(),
                timeoutOracle = { null },
            ))
            for (headers in listOf(emptyMap(), mapOf("Authorization" to listOf("Bearer stale")))) {
                server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/proto").build())

                assertTrue(call.invoke(rpc, headers) is ResponseMessage.Success)
                val sent = server.takeRequest()

                assertEquals("/school/api/rpc/${call.method}", sent.url.encodedPath)
                assertEquals("application/proto", sent.headers["Content-Type"])
                assertNull(sent.headers["Authorization"])
            }
        }
    }

    interface AnonymousCall {
        val method: String
        suspend fun invoke(client: ProtocolClientInterface, headers: Headers): ResponseMessage<*>
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun methods() = listOf(
            arrayOf(method<CreateDeviceRequest, CreateDeviceResponse>(
                "lessons.v2.DeviceService/CreateDevice", CreateDeviceRequest.getDefaultInstance(),
            )),
            arrayOf(method<GetDiaryCapabilitiesRequest, GetDiaryCapabilitiesResponse>(
                "lessons.v2.DiaryService/GetDiaryCapabilities", GetDiaryCapabilitiesRequest.getDefaultInstance(),
            )),
            arrayOf(method<CreateDiarySessionRequest, CreateDiarySessionResponse>(
                "lessons.v2.DiaryService/CreateDiarySession", CreateDiarySessionRequest.getDefaultInstance(),
            )),
            arrayOf(method<ListSchoolRegionsRequest, ListSchoolRegionsResponse>(
                "lessons.v2.DirectoryService/ListSchoolRegions", ListSchoolRegionsRequest.getDefaultInstance(),
            )),
        )

        private inline fun <reified Input : Any, reified Output : Any> method(
            name: String, request: Input,
        ): AnonymousCall {
            val spec = MethodSpec(name, Input::class, Output::class, StreamType.UNARY)
            return object : AnonymousCall {
                override val method = name
                override suspend fun invoke(client: ProtocolClientInterface, headers: Headers): ResponseMessage<*> =
                    client.unary(request, headers, spec)
                override fun toString() = name
            }
        }
    }
}
