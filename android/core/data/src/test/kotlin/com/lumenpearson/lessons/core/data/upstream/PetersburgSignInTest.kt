package com.lumenpearson.lessons.core.data.upstream

import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Petersburg's sign-in on the phone, over a real socket. The answers are the
 * shared vectors' (`DiaryProtocolVectorsTest`); this is what the request
 * carries, and the two failures a vector cannot express.
 */
class PetersburgSignInTest {

    private lateinit var server: MockWebServer
    private val clock = MutableClock()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the login body carries exactly the five fields, activation_code as an explicit null`() = runBlocking {
        server.enqueue(rawAnswer(200, "application/json", """{"data":{"token":"a.b.c"}}"""))

        signIn(login = "  parent@example.com ", password = " spaced password ")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(setOf("type", "login", "activation_code", "password", "_isEmpty"), body.keys)
        assertEquals(JsonNull, body["activation_code"])
        assertEquals("email", body.getValue("type").jsonPrimitive.content)
        assertEquals("false", body.getValue("_isEmpty").jsonPrimitive.content)
        // The login trimmed, the password exactly as typed.
        assertEquals("parent@example.com", body.getValue("login").jsonPrimitive.content)
        assertEquals(" spaced password ", body.getValue("password").jsonPrimitive.content)
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
    }

    @Test
    fun `a browser user agent is sent and Authorization and Cookie never are`() = runBlocking {
        server.enqueue(rawAnswer(200, "application/json", """{"data":{"token":"a.b.c"}}"""))

        signIn()

        val request = server.takeRequest()
        assertEquals(UpstreamHttp.USER_AGENT, request.headers["User-Agent"])
        assertEquals("application/json", request.headers["Accept"])
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["Cookie"])
    }

    @Test
    fun `the password never appears in the URL`() = runBlocking {
        server.enqueue(rawAnswer(200, "application/json", """{"data":{"token":"a.b.c"}}"""))

        signIn(password = "hunter2")

        assertFalse("hunter2" in server.takeRequest().url.toString())
    }

    @Test
    fun `a timeout is unavailable and says so`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).headersDelay(2, TimeUnit.SECONDS).build())
        val impatient = clientFor(server).newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()

        val failure = runCatching {
            PetersburgSignIn(impatient, originOf(server), clock).signIn(DiaryTarget.petersburg("parent"), "x")
        }.exceptionOrNull()

        assertTrue(failure is UpstreamFailure.Unavailable && failure.timedOut)
    }

    /** Nobody is signed in yet, so a page of HTML is «unreadable», never «sign in again» — which would loop. */
    @Test
    fun `a 200 of HTML is unreadable, not a re-auth loop`() = runBlocking {
        server.enqueue(rawAnswer(200, "text/html", "<html><form>captcha</form></html>"))

        val failure = runCatching { signIn() }.exceptionOrNull()

        assertTrue(failure is UpstreamFailure.Unexpected)
    }

    @Test
    fun `the session is the cookie's and the target's login is trimmed`() = runBlocking {
        server.enqueue(rawAnswer(200, "application/json", """{"data":{"token":"body"}}""", listOf("X-JWT-Token=cookie.jwt.value; Path=/")))

        val session = signIn(login = " parent@example.com ")

        assertEquals("cookie.jwt.value", session.token)
        assertEquals("parent@example.com", session.target.login)
        assertFalse("cookie.jwt.value" in session.toString())
    }

    private suspend fun signIn(login: String = "parent@example.com", password: String = "secret") =
        PetersburgSignIn(clientFor(server), originOf(server), clock).signIn(DiaryTarget.petersburg(login), password)
}
