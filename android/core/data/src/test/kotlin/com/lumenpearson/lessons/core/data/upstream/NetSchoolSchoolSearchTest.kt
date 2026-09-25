package com.lumenpearson.lessons.core.data.upstream

import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The phone's own school search, straight to the region's server: what it
 * sends, what it refuses to send, and how its failures read. The row rule and
 * the query cut are the shared vectors' (`DiaryProtocolVectorsTest`).
 */
class NetSchoolSchoolSearchTest {

    private lateinit var server: MockWebServer
    private lateinit var script: NetSchoolScript

    @Before
    fun setUp() {
        server = MockWebServer()
        script = NetSchoolScript()
        server.dispatcher = script
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the query is cut at 60 code points, never inside a surrogate pair`() = runBlocking {
        val query = "а" + "😀".repeat(70)

        search().search("zabaikalsky", query).getOrThrow()

        val sent = script.to(NetSchoolSchoolSearch.PATH).single().url.queryParameter("name")!!
        assertEquals(60, sent.codePointCount(0, sent.length))
        assertTrue(sent.last().isLowSurrogate())
    }

    @Test
    fun `no session header rides a search`() = runBlocking {
        search().search("zabaikalsky", "лицей").getOrThrow()

        val request = script.to(NetSchoolSchoolSearch.PATH).single()
        assertNull(request.headers["at"])
        assertNull(request.headers["Cookie"])
        assertNull(request.headers["Authorization"])
        assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
    }

    @Test
    fun `a firewall page is the region refusing the phone, named by its host`() = runBlocking {
        script.search = { rawAnswer(200, "text/html", "<h1>Access denied</h1>") }

        val failure = search().search("zabaikalsky", "лицей").exceptionOrNull()

        assertEquals(DiarySignInProblem.ProviderRefusesPhone(originOf(server).host), failure)
    }

    @Test
    fun `a region that answers 5xx is unavailable`() = runBlocking {
        script.search = { rawAnswer(502, "text/html", "<html>") }

        val failure = search().search("zabaikalsky", "лицей").exceptionOrNull()

        assertEquals(DiarySignInProblem.ProviderUnavailable(originOf(server).host), failure)
    }

    @Test
    fun `a query too short to mean anything sends nothing`() = runBlocking {
        val rows = search().search("zabaikalsky", "  ш ").getOrThrow()

        assertTrue(rows.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a region outside the allow-list sends nothing`() = runBlocking {
        val failure = search().search("volgograd", "лицей").exceptionOrNull()

        assertEquals(DiarySignInProblem.RegionNotServed, failure)
        assertEquals(0, server.requestCount)
    }

    /** There is no school to pick for a sign-in this app will never make. */
    @Test
    fun `a Gosuslugi-only region sends nothing`() = runBlocking {
        val directory = FakeUpstreamDirectory(null, listOf(regionAt(server, key = "tula", password = false)))
        val failure = NetSchoolSchoolSearch({ clientFor(server) }, { directory }, Dispatchers.Unconfined)
            .search("tula", "лицей").exceptionOrNull()

        assertTrue(failure is DiarySignInProblem.GosuslugiOnly)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `at most fifty rows come back`() = runBlocking {
        val rows = (1..80).joinToString(",", "[", "]") { """{"id":$it,"name":"Школа № $it"}""" }
        script.search = { jsonAnswer(200, Json.parseToJsonElement(rows)) }

        val found = search().search("zabaikalsky", "школа").getOrThrow()

        assertEquals(DiarySchoolSearch.MAX_ROWS, found.size)
        assertEquals(1L, found.first().id)
    }

    private fun search() = NetSchoolSchoolSearch(
        client = { clientFor(server) },
        directory = { FakeUpstreamDirectory(null, listOf(regionAt(server))) },
        ioDispatcher = Dispatchers.Unconfined,
    )
}
