package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.CleartextPolicy
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RequestSnapshotTest(private val path: String) {
    @Test
    fun `address and bearer come from exactly one send time snapshot`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse())
            var reads = 0
            val first = RequestCredentials(server.url("/school/").toString(), "first-class", "first-diary")
            val second = RequestCredentials("https://other.invalid/", "second-class", "second-diary")
            val client = NetworkModule.okHttpClient({ if (++reads == 1) first else second }, CleartextPolicy { true })

            client.newCall(Request.Builder().url("http://base-url.invalid$path").build()).execute().close()
            val sent = server.takeRequest()

            assertEquals("Bearer first-class", sent.headers["Authorization"])
            assertEquals("/school$path", sent.url.encodedPath)
            assertEquals(1, reads)
        }
    }

    @Test
    fun `a missing bearer in the captured snapshot never falls back to a later account`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse())
            var reads = 0
            val first = RequestCredentials(server.url("/").toString(), null, null)
            val later = first.copy(classToken = "later-class", diaryToken = "later-diary")
            val client = NetworkModule.okHttpClient({ if (++reads == 1) first else later }, CleartextPolicy { true })

            client.newCall(Request.Builder().url("http://base-url.invalid$path").build()).execute().close()

            assertEquals(null, server.takeRequest().headers["Authorization"])
            assertEquals(1, reads)
        }
    }

    @Test
    fun `a request created before a settings change uses the new address and bearer`() {
        MockWebServer().use { oldServer ->
            MockWebServer().use { newServer ->
                oldServer.start()
                newServer.start()
                newServer.enqueue(MockResponse())
                var current = RequestCredentials(oldServer.url("/old/").toString(), "old-class", "old-diary")
                val client = NetworkModule.okHttpClient({ current }, CleartextPolicy { true })
                val request = Request.Builder().url("http://base-url.invalid$path").build()
                current = RequestCredentials(newServer.url("/new/").toString(), "new-class", "new-diary")

                client.newCall(request).execute().close()
                val sent = newServer.takeRequest()

                assertEquals(0, oldServer.requestCount)
                assertEquals("/new$path", sent.url.encodedPath)
                assertEquals("Bearer new-class", sent.headers["Authorization"])
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun paths() = listOf(
            arrayOf("/api/v1/bundle"),
            arrayOf("/api/rpc/lessons.v2.ScheduleService/GetScheduleWindow"),
        )
    }
}
