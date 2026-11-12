package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.CleartextPolicy
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class ClientVersionInterceptorTest {
    @Test
    fun `the configured client version replaces stale headers on every server surface`() {
        MockWebServer().use { server ->
            server.start()
            val credentials = RequestCredentials(server.url("/school/").toString(), null, null)
            val client = NetworkModule.okHttpClient({ credentials }, CleartextPolicy { true }, clientVersion = 73)
            for (path in listOf(
                "/api/v1/warmup", "/api/v2/devices", "/api/rpc/lessons.v2.DeviceService/CreateDevice",
            )) {
                server.enqueue(MockResponse())
                val request = Request.Builder().url("http://base-url.invalid$path")
                    .header("X-Lessons-Client", "stale").build()

                client.newCall(request).execute().close()

                assertEquals("73", server.takeRequest().headers["X-Lessons-Client"])
            }
        }
    }
}
