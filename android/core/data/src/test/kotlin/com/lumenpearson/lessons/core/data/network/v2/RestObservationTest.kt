package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.CleartextPolicy
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import com.lumenpearson.lessons.core.data.network.v2.dto.CreateDeviceResponseDto
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.Tag

class RestObservationTest {
    private interface Api {
        @GET("api/v2/devices")
        @Credential(CredentialKind.NONE)
        suspend fun failure(@Tag observation: CallObservation): retrofit2.Response<CreateDeviceResponseDto>
    }

    @Test
    fun `an observed refusal whose body throws IO remains a refusal with the actual status`() = runBlocking {
        val original = IOException("synthetic body failure")
        val client = NetworkModule.okHttpClient(
            { RequestCredentials("https://school.invalid/", null, null) }, CleartextPolicy { false },
        ).newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(503).message("Unavailable").header("X-Lessons-Observed-Status", "200")
                .body(failingBody(original)).build()
        }.build()
        val api = NetworkModule.retrofit(client, NetworkModule.json()).create(Api::class.java)

        val error = runCatching { RestRefusals.request { api.failure(it) } }.exceptionOrNull()

        assertTrue(error is RemoteError)
        assertEquals(503, (error as RemoteError).httpStatus)
        assertSame(original, error.cause)
    }

    @Test
    fun `cancellation remains cancellation even after response headers arrived`() = runBlocking {
        val cancelled = CancellationException("synthetic cancellation")

        val actual = runCatching {
            RestRefusals.request<Unit> {
                it.status = 503
                throw cancelled
            }
        }.exceptionOrNull()

        assertSame(cancelled, actual)
    }

    @Test
    fun `without a response the original IO object survives`() = runBlocking {
        val original = IOException("synthetic connection failure")
        val client = NetworkModule.okHttpClient(
            { RequestCredentials("https://school.invalid/", null, null) }, CleartextPolicy { false },
        ).newBuilder().addInterceptor { throw original }.build()
        val api = NetworkModule.retrofit(client, NetworkModule.json()).create(Api::class.java)

        val error = runCatching { RestRefusals.request { api.failure(it) } }.exceptionOrNull()

        assertSame(original, error)
    }

    private fun failingBody(failure: IOException): ResponseBody = object : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = 10
        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw failure
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
    }
}
