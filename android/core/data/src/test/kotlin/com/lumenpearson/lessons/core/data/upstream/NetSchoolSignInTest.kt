package com.lumenpearson.lessons.core.data.upstream

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
 * «Сетевой город»'s sign-in on the phone, over a real socket: the order of the
 * steps, what each one carries, and — above all — where the password and its
 * hashes may and may not appear. The answers themselves are the shared
 * vectors' (`DiaryProtocolVectorsTest`); this is the shape of the exchange.
 */
class NetSchoolSignInTest {

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
    fun `a sign-in walks logindata, getdata and login in that order and ends holding at and cookies`() = runBlocking {
        val script = NetSchoolScript(
            logindata = {
                MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                    .addHeader("Set-Cookie", "NSSESSIONID=s1; Path=/").body("""{"cacheVer":"639"}""").build()
            },
            logins = ArrayDeque(
                listOf({
                    MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                        .addHeader("Set-Cookie", "ESRNSec=e1; Path=/").body("""{"at":"bearer-1","timeOut":900000}""").build()
                }),
            ),
        )
        server.dispatcher = script

        val session = signIn()

        assertEquals(
            listOf(NetSchoolSignIn.LOGINDATA_PATH, NetSchoolSignIn.GETDATA_PATH, NetSchoolSignIn.LOGIN_PATH),
            script.seen.map { it.url.encodedPath },
        )
        assertEquals("bearer-1", session.at)
        assertEquals(mapOf("NSSESSIONID" to "s1", "ESRNSec" to "e1"), session.cookies)
        assertEquals("639", session.ver)
        assertEquals(900_000L, session.timeOut)
        assertEquals("zabaikalsky", session.target.region)
        assertEquals(42L, session.target.schoolId)
    }

    @Test
    fun `the password and its hashes never appear in a URL, and the password in no body`() = runBlocking {
        val script = NetSchoolScript()
        server.dispatcher = script

        signIn(password = "hunter2")

        val (pw, pw2) = NetSchoolPassword.hash("333", "hunter2")!!
        for (request in script.seen) {
            val url = request.url.toString()
            assertFalse(url, "hunter2" in url || pw in url || pw2 in url)
            assertFalse("hunter2" in request.body?.utf8().orEmpty())
            assertFalse(request.headers.toString().contains("hunter2"))
        }
        val form = script.to(NetSchoolSignIn.LOGIN_PATH).single().form()
        assertEquals(pw, form["pw"])
        assertEquals(pw2, form["pw2"])
        assertEquals("42", form["scid"])
        assertEquals("parent", form["un"])
    }

    @Test
    fun `getdata carries the cookie logindata set and no content type`() = runBlocking {
        val script = NetSchoolScript(
            logindata = {
                MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                    .addHeader("Set-Cookie", "NSSESSIONID=s1; Path=/").body("""{"schoolLogin":true}""").build()
            },
        )
        server.dispatcher = script

        signIn()

        val getdata = script.to(NetSchoolSignIn.GETDATA_PATH).single()
        assertEquals("POST", getdata.method)
        assertEquals("NSSESSIONID=s1", getdata.headers["Cookie"])
        assertEquals(0L, getdata.bodySize)
        assertNull(getdata.headers["Content-Type"])
        assertEquals("XMLHttpRequest", getdata.headers["X-Requested-With"])
        // logindata is asked before there is anything to carry.
        assertNull(script.to(NetSchoolSignIn.LOGINDATA_PATH).single().headers["Cookie"])
    }

    @Test
    fun `a Gosuslugi-only answer stops before getdata`() = runBlocking {
        val script = NetSchoolScript(logindata = { jsonAnswer(200, Json.parseToJsonElement("""{"schoolLogin":false}""")) })
        server.dispatcher = script

        val failure = runCatching { signIn() }.exceptionOrNull()

        assertTrue(failure is UpstreamFailure.SignInUnsupported)
        assertTrue(script.to(NetSchoolSignIn.GETDATA_PATH).isEmpty())
    }

    @Test
    fun `a catalog row without password sends nothing`() = runBlocking {
        val script = NetSchoolScript()
        server.dispatcher = script

        val failure = runCatching {
            NetSchoolSignIn(clientFor(server), clock).signIn(regionAt(server, password = false), netschoolTarget(), "x")
        }.exceptionOrNull()

        assertTrue(failure is UpstreamFailure.SignInUnsupported)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `choose-session-role retries once with a fresh salt and the parent role`() = runBlocking {
        var salts = 0
        val script = NetSchoolScript(
            getdata = {
                salts++
                jsonAnswer(200, Json.parseToJsonElement("""{"salt":"${330 + salts}","lt":"$salts","ver":"2"}"""))
            },
            logins = ArrayDeque(listOf({ jsonAnswer(200, Vectors.flow.getValue("role_question")) })),
        )
        server.dispatcher = script

        val session = signIn()

        val (first, second) = script.to(NetSchoolSignIn.LOGIN_PATH).map { it.form() }
        assertEquals("1", first["lt"])
        assertEquals("2", second["lt"])
        assertEquals(NetSchoolPassword.hash("332", "hunter2")!!.second, second["pw2"])
        assertEquals("12", second["rolegroup"])
        assertEquals("after-role", session.at)
    }

    /** A second «which role» after one was chosen is a refusal, not a loop. */
    @Test
    fun `a second role question is not retried`() = runBlocking {
        val question = { jsonAnswer(200, Vectors.flow.getValue("role_question")) }
        val script = NetSchoolScript(logins = ArrayDeque(listOf(question, question, question)))
        server.dispatcher = script

        val failure = runCatching { signIn() }.exceptionOrNull()

        assertTrue(failure is UpstreamFailure.BadCredentials)
        assertEquals(2, script.to(NetSchoolSignIn.LOGIN_PATH).size)
    }

    @Test
    fun `403 and a firewall page are address refused`() = runBlocking {
        for (answer in listOf(
            { rawAnswer(403, "application/json", "{}") },
            { rawAnswer(200, "text/html", "<h1>Доступ к сайту ограничен</h1>") },
        )) {
            server.dispatcher = NetSchoolScript(logindata = answer)
            assertTrue(runCatching { signIn() }.exceptionOrNull() is UpstreamFailure.AddressRefused)
        }
    }

    /**
     * A redirect to the region's login page, or anywhere else, is read as the
     * answer it is — not JSON — and never followed to where it points.
     */
    @Test
    fun `a redirect is not followed`() = runBlocking {
        val script = NetSchoolScript(
            logindata = {
                MockResponse.Builder().code(302).addHeader("Location", "https://elsewhere.example/").build()
            },
        )
        server.dispatcher = script

        val failure = runCatching { signIn() }.exceptionOrNull()

        assertTrue("got $failure", failure is UpstreamFailure.Unexpected)
        assertEquals(1, script.seen.size)
    }

    @Test
    fun `the login is trimmed before it is sent`() = runBlocking {
        val script = NetSchoolScript()
        server.dispatcher = script

        val session = NetSchoolSignIn(clientFor(server), clock)
            .signIn(regionAt(server), netschoolTarget(login = "  parent  "), "hunter2")

        assertEquals("parent", script.to(NetSchoolSignIn.LOGIN_PATH).single().form()["un"])
        assertEquals("parent", session.target.login)
    }

    /**
     * For a session our server refused: goodbye upstream, with `at` in a
     * header and the body — never in the URL, which somebody logs.
     */
    @Test
    fun `logout posts at and ver in the body and nothing in the URL`() = runBlocking {
        val script = NetSchoolScript()
        server.dispatcher = script
        val session = signIn()

        NetSchoolSignIn(clientFor(server), clock).logout(session, regionAt(server))

        val logout = script.to(NetSchoolSignIn.LOGOUT_PATH).single()
        assertEquals(mapOf("at" to "after-role", "ver" to "639"), logout.form())
        assertEquals("after-role", logout.headers["at"])
        assertFalse("after-role" in logout.url.toString())
    }

    @Test
    fun `a logout that fails is swallowed`() = runBlocking {
        val script = NetSchoolScript()
        server.dispatcher = script
        val session = signIn()
        server.close()

        // Nothing thrown: we are leaving anyway.
        NetSchoolSignIn(clientFor(server), clock).logout(session, regionAt(server))
    }

    @Test
    fun `an upstream session never prints its secret`() = runBlocking {
        server.dispatcher = NetSchoolScript()

        val session = signIn()

        assertFalse("after-role" in session.toString())
        assertFalse(session.toString().contains("NSSESSIONID"))
    }

    private suspend fun signIn(password: String = "hunter2"): UpstreamSession.NetSchool =
        NetSchoolSignIn(clientFor(server), clock).signIn(regionAt(server), netschoolTarget(), password)
}
