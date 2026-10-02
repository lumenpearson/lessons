package com.lumenpearson.lessons.core.data.developer

import com.lumenpearson.lessons.core.data.upstream.UpstreamOrigin
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The request console (#237): it reaches our server and the catalog's diaries
 * and nothing else, puts a bearer only on our server and only when chosen, and
 * shows a redirect rather than following it.
 */
class RequestConsoleTest {

    private val server = MockWebServer()
    private val diary = MockWebServer()

    @Before
    fun setUp() {
        server.start()
        diary.start()
    }

    @After
    fun tearDown() {
        server.close()
        diary.close()
    }

    private fun console(
        address: String? = server.url("/").toString(),
        device: String? = "device-bearer",
        diaryToken: String? = null,
    ) = RequestConsole(
        credentials = { ConsoleCredentials(address, device, diaryToken) },
        diaryOrigins = { setOf(UpstreamOrigin.of(diary.url("/"))) },
        cleartextPermitted = { true },
    )

    private fun context(
        address: String? = "https://srv.example/",
        cleartext: Boolean = false,
        device: Boolean = true,
        diaryToken: Boolean = false,
    ) = ConsoleContext(
        serverAddress = address,
        serverCleartextPermitted = { cleartext },
        diaryOrigins = setOf(UpstreamOrigin.of("https://diary.example/".toHttpUrl())),
        hasDeviceToken = device,
        hasDiaryToken = diaryToken,
    )

    private fun refusal(draft: ConsoleDraft, context: ConsoleContext = context()): ConsoleRefusal? =
        (planConsole(draft, context) as? ConsolePlan.Refused)?.reason

    @Test
    fun `a path goes under the server's own prefix, as the app's requests do`() {
        val plan = planConsole(ConsoleDraft(path = "/api/v1/me?x=1"), context(address = "https://srv.example/lessons/"))

        assertEquals("https://srv.example/lessons/api/v1/me?x=1", (plan as ConsolePlan.Ready).url.toString())
    }

    @Test
    fun `no spelling of a path leaves the origin`() {
        assertEquals(ConsoleRefusal.BAD_PATH, refusal(ConsoleDraft(path = "//elsewhere.example/x")))
        assertEquals(ConsoleRefusal.BAD_PATH, refusal(ConsoleDraft(path = "https://elsewhere.example/x")))
        assertEquals(ConsoleRefusal.BAD_PATH, refusal(ConsoleDraft(path = "/a b")))
        for (path in listOf("/\\elsewhere.example/", "/@elsewhere.example/", "/../../x", "/%2F%2Felsewhere.example")) {
            val plan = planConsole(ConsoleDraft(path = path), context())
            assertEquals(path, "srv.example", (plan as ConsolePlan.Ready).url.host)
        }
    }

    @Test
    fun `a diary is only one the catalog lists, and never gets our bearer`() {
        val diaryDraft = ConsoleDraft(target = ConsoleTarget.DIARY, origin = "https://diary.example", path = "/")

        assertTrue(planConsole(diaryDraft, context()) is ConsolePlan.Ready)
        assertEquals(ConsoleRefusal.NO_ORIGIN, refusal(diaryDraft.copy(origin = null)))
        assertEquals(ConsoleRefusal.ORIGIN_NOT_ALLOWED, refusal(diaryDraft.copy(origin = "https://evil.example")))
        assertEquals(ConsoleRefusal.ORIGIN_NOT_ALLOWED, refusal(diaryDraft.copy(origin = "http://diary.example")))
        assertEquals(ConsoleRefusal.AUTH_OFF_SERVER, refusal(diaryDraft.copy(auth = ConsoleAuth.DEVICE)))
    }

    @Test
    fun `the server address must be there and usable`() {
        assertEquals(ConsoleRefusal.NO_SERVER, refusal(ConsoleDraft(), context(address = null)))
        assertEquals(ConsoleRefusal.NO_SERVER, refusal(ConsoleDraft(), context(address = "not an address")))
        val plain = context(address = "http://srv.example")
        assertEquals(ConsoleRefusal.SERVER_NEEDS_HTTPS, refusal(ConsoleDraft(), plain))
        val emulator = context(address = "http://10.0.2.2:8000", cleartext = true)
        assertTrue(planConsole(ConsoleDraft(), emulator) is ConsolePlan.Ready)
    }

    @Test
    fun `headers, bearers and bodies are refused where they cannot be meant`() {
        assertEquals(ConsoleRefusal.BAD_HEADER, refusal(ConsoleDraft(headers = "no colon here")))
        assertEquals(ConsoleRefusal.BAD_HEADER, refusal(ConsoleDraft(headers = "Bad Name: x")))
        assertEquals(ConsoleRefusal.FORBIDDEN_HEADER, refusal(ConsoleDraft(headers = "host: elsewhere.example")))
        assertEquals(
            ConsoleRefusal.AUTH_TWICE,
            refusal(ConsoleDraft(headers = "Authorization: Bearer x", auth = ConsoleAuth.DEVICE)),
        )
        assertEquals(ConsoleRefusal.NO_TOKEN, refusal(ConsoleDraft(auth = ConsoleAuth.DIARY)))
        assertEquals(ConsoleRefusal.NO_TOKEN, refusal(ConsoleDraft(auth = ConsoleAuth.DEVICE), context(device = false)))
        assertEquals(ConsoleRefusal.BODY_NOT_ALLOWED, refusal(ConsoleDraft(body = "{}")))

        val ready = planConsole(ConsoleDraft(headers = "\nX-One: 1\n\nAccept:  application/json \n"), context())
        assertEquals(listOf("X-One" to "1", "Accept" to "application/json"), (ready as ConsolePlan.Ready).headers)
    }

    @Test
    fun `a plan never holds the bearer`() {
        val plan = planConsole(ConsoleDraft(auth = ConsoleAuth.DEVICE), context())

        assertTrue("device-bearer" !in plan.toString())
        assertTrue("device-bearer" !in ConsoleCredentials("https://srv.example", "device-bearer", "d").toString())
    }

    @Test
    fun `the chosen bearer goes to our server, with the typed headers and body`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .addHeader("Content-Type", "application/json")
                .body("""{"ok":true}""")
                .build(),
        )

        val outcome = console().send(
            ConsoleDraft(
                method = ConsoleMethod.POST,
                path = "/api/v1/tasks",
                headers = "X-Trace: abc",
                body = """{"title":"x"}""",
                auth = ConsoleAuth.DEVICE,
            ),
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/tasks", recorded.url.encodedPath)
        assertEquals("Bearer device-bearer", recorded.headers["Authorization"])
        assertEquals("abc", recorded.headers["X-Trace"])
        assertEquals("""{"title":"x"}""", recorded.body!!.utf8())
        val answer = (outcome as ConsoleOutcome.Answered).answer
        assertEquals(201, answer.status)
        assertEquals("{\n    \"ok\": true\n}", answer.body)
    }

    @Test
    fun `without a chosen bearer nothing is signed`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())

        console().send(ConsoleDraft(path = "/api/v1/health"))

        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a redirect is an answer, not a second request`() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://elsewhere.example/").build())

        val outcome = console().send(ConsoleDraft(path = "/go"))

        val answer = (outcome as ConsoleOutcome.Answered).answer
        assertEquals(302, answer.status)
        assertTrue(answer.headers.any { (name, value) -> name == "Location" && value == "https://elsewhere.example/" })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a diary from the catalog is reached, unsigned`() = runTest {
        diary.enqueue(MockResponse.Builder().code(200).body("<html></html>").build())
        val origin = diary.url("/").toString().trimEnd('/')

        val outcome = console(diaryToken = "diary-bearer").send(
            ConsoleDraft(target = ConsoleTarget.DIARY, origin = origin, path = "/webapi/prepareloginform"),
        )

        assertEquals(200, (outcome as ConsoleOutcome.Answered).answer.status)
        assertNull(diary.takeRequest().headers["Authorization"])
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an answer past the shown size is cut and says so`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(ConsoleShownBytes.toInt() + 10)).build())

        val answer = (console().send(ConsoleDraft()) as ConsoleOutcome.Answered).answer

        assertTrue(answer.truncated)
        assertEquals(ConsoleShownBytes.toInt(), answer.body.length)
    }

    @Test
    fun `the offered origins are the catalog's, without default ports`() {
        val console = RequestConsole(
            credentials = { ConsoleCredentials(null, null, null) },
            diaryOrigins = {
                setOf(UpstreamOrigin("https", "b.example", 443), UpstreamOrigin("https", "a.example", 8443))
            },
            cleartextPermitted = { false },
        )

        assertEquals(listOf("https://a.example:8443", "https://b.example"), console.origins())
    }
}
