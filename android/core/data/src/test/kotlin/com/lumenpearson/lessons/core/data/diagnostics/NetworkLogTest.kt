package com.lumenpearson.lessons.core.data.diagnostics

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The developer mode's network record (#237): what it keeps of a request,
 * what it never keeps, and that a request still out is on it while it is out —
 * the row #236 needed.
 */
class NetworkLogTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        NetworkLog.setRecording(false)
        server.start()
    }

    @After
    fun tearDown() {
        NetworkLog.setRecording(false)
        server.close()
    }

    private fun client(vararg after: okhttp3.Interceptor): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(NetworkLog.interceptor(NetworkSource.SERVER))
        .apply { after.forEach { addInterceptor(it) } }
        .build()

    @Test
    fun `off, nothing is kept`() {
        server.enqueue(MockResponse.Builder().code(200).build())

        client().newCall(Request.Builder().url(server.url("/api/v1/health")).build()).execute().close()

        assertTrue(NetworkLog.entries.value.isEmpty())
    }

    @Test
    fun `on, a request is kept as its method, host, masked path and answer`() {
        NetworkLog.setRecording(true)
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Vercel-Id", "fra1::abc")
                .addHeader("Set-Cookie", "X-JWT-Token=secret; Path=/")
                .body("{}")
                .build(),
        )
        val url = server.url("/api/v1/calendar/AbCdEf1234567890GhIjKl.ics?token=secret&from=2026-09-01")

        client().newCall(
            Request.Builder().url(url).header("Authorization", "Bearer secret").build(),
        ).execute().close()

        val entry = NetworkLog.entries.value.single()
        assertEquals(NetworkSource.SERVER, entry.source)
        assertEquals("GET", entry.method)
        assertEquals(url.host, entry.host)
        assertEquals("/api/v1/calendar/${NetworkRedaction.TokenMask}?from&token", entry.path)
        assertEquals(200, entry.status)
        assertFalse(entry.inFlight)
        assertEquals(2L, entry.bytes)
        assertEquals(listOf("Content-Type" to "application/json", "X-Vercel-Id" to "fra1::abc"), entry.headers)
        assertFalse("no value of a secret reaches the record", entry.toString().contains("secret"))
    }

    @Test
    fun `a request still out is on the record, and finished once it answers`() {
        NetworkLog.setRecording(true)
        server.enqueue(MockResponse.Builder().code(204).build())
        val release = CountDownLatch(1)
        val held = okhttp3.Interceptor { chain ->
            release.await(5, TimeUnit.SECONDS)
            chain.proceed(chain.request())
        }
        val call = thread { client(held).newCall(Request.Builder().url(server.url("/slow")).build()).execute().close() }

        val deadline = System.currentTimeMillis() + 5_000
        while (NetworkLog.entries.value.none { it.inFlight } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val out = NetworkLog.entries.value.single()
        assertTrue(out.inFlight)
        assertNull(out.status)

        release.countDown()
        call.join(5_000)
        val done = NetworkLog.entries.value.single()
        assertFalse(done.inFlight)
        assertEquals(204, done.status)
        assertEquals(out.id, done.id)
    }

    @Test
    fun `a failure is kept with its class, and the caller still gets it`() {
        NetworkLog.setRecording(true)
        val broken = okhttp3.Interceptor { throw IOException("connection reset") }

        try {
            client(broken).newCall(Request.Builder().url(server.url("/x")).build()).execute()
            fail("the recorder swallowed the failure")
        } catch (expected: IOException) {
            assertEquals("connection reset", expected.message)
        }

        val entry = NetworkLog.entries.value.single()
        assertEquals("IOException: connection reset", entry.failure)
        assertFalse(entry.inFlight)
        assertNull(entry.status)
    }

    @Test
    fun `the record keeps the newest and drops the oldest past its size`() {
        NetworkLog.setRecording(true)
        val request = Request.Builder().url("https://example.invalid/").build()

        repeat(205) { NetworkLog.begin(NetworkSource.DIARY, request, startedAtMillis = it.toLong()) }

        val entries = NetworkLog.entries.value
        assertEquals(200, entries.size)
        assertEquals(5L, entries.first().startedAtMillis)
        assertEquals(204L, entries.last().startedAtMillis)
    }

    @Test
    fun `switching recording off empties the record`() {
        NetworkLog.setRecording(true)
        NetworkLog.begin(NetworkSource.GITHUB, Request.Builder().url("https://example.invalid/").build(), 0L)

        NetworkLog.setRecording(false)

        assertTrue(NetworkLog.entries.value.isEmpty())
    }

    @Test
    fun `a segment is masked as a token or an id, and nothing else is`() {
        assertEquals(NetworkRedaction.TokenMask, NetworkRedaction.segment("Zq3xR9vT0pLk2mN8bW4yC6"))
        assertEquals(NetworkRedaction.IdMask, NetworkRedaction.segment("1234567"))
        assertEquals("related-child-list", NetworkRedaction.segment("related-child-list"))
        assertEquals("v1", NetworkRedaction.segment("v1"))
        assertEquals("2026", NetworkRedaction.segment("2026"))
        assertEquals("/", NetworkRedaction.path("https://example.invalid/".toHttpUrl()))
    }

    @Test
    fun `only the listed response headers are kept, and never one that carries a credential`() {
        val headers = Headers.Builder()
            .add("Set-Cookie", "NSSESSIONID=x")
            .add("Authorization", "Bearer x")
            .add("Retry-After", "60")
            .add("X-Diary-Unavailable", "upstream")
            .build()

        assertEquals(
            listOf("Retry-After" to "60", "X-Diary-Unavailable" to "upstream"),
            NetworkRedaction.headers(headers),
        )
    }
}
