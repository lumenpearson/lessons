package com.lumenpearson.lessons.core.data.upstream

import com.lumenpearson.lessons.core.data.catalog.RegionCatalog
import com.lumenpearson.lessons.core.data.repository.DiarySessionIdleDays
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The shared known answers for both diary sign-ins, run through the real
 * Kotlin code — the phone's half of `server/tests/test_diary_protocol_vectors.py`.
 *
 * The sign-in protocol of each diary now lives in two codebases: the phone
 * signs in itself and hands the session over, and the server signs in with a
 * password on the bot's page and the older `/login`. Two implementations of one
 * protocol drift apart within a month unless something holds them to one set of
 * answers; `server/tests/vectors/diary_protocol.json` is that set, read in
 * place, and neither implementation is the oracle.
 *
 * Every request-shaped case goes over a real socket to MockWebServer, through
 * the same client the app uses, so a case that fails here is a behaviour of the
 * code and not of a re-implementation of its rules. A rule that is not a
 * request — the hash, the role pick, the charset checks — is asked of the
 * function that implements it.
 *
 * What a failure here means: the Kotlin side changed and the file did not, or
 * the reverse. Change them together, and the server's test with them.
 */
class DiaryProtocolVectorsTest {

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

    // ---- what both sides share ----------------------------------------------

    @Test
    fun `the user agent, the Petersburg origin and its session cookie are the server's`() {
        val root = Vectors.root
        assertEquals(root.getValue("user_agent").jsonPrimitive.content, UpstreamHttp.USER_AGENT)
        assertEquals(Vectors.petersburg.getValue("session_cookie").jsonPrimitive.content, PetersburgSignIn.SESSION_COOKIE)
        assertEquals(Vectors.petersburg.getValue("login_path").jsonPrimitive.content, PetersburgSignIn.LOGIN_PATH)
        // And the origin the phone signs in to is the catalog's, which is the
        // only place the phone learns a host from.
        val catalog = RegionCatalog.parse(resource("regions.json"))
        assertEquals(
            Vectors.petersburg.getValue("origin").jsonPrimitive.content.toHttpUrl(),
            CatalogUpstreamDirectory(catalog).petersburg,
        )
    }

    @Test
    fun `the idle window the screens promise is the server's`() {
        val days = Vectors.root.getValue("session").jsonObject.getValue("idle_days").jsonPrimitive.int
        assertEquals(days.toLong(), DiarySessionIdleDays)
    }

    @Test
    fun `the credential charset rule matches the shared vectors`() {
        val credential = Vectors.root.getValue("credential").jsonObject
        assertEquals(
            credential.getValue("pattern").jsonPrimitive.content,
            "^" + UpstreamValues.COOKIE_VALUE.pattern + "$",
        )
        for (case in Vectors.cases(credential, "cases")) {
            val value = case.getValue("value").jsonPrimitive.content
            val ok = case.getValue("ok").jsonPrimitive.booleanOrNull!!
            assertEquals("credential ${value.quoted()}", ok, UpstreamValues.cookieValueOk(value))
        }
    }

    @Test
    fun `the header token rule matches the shared vectors`() {
        val token = Vectors.root.getValue("header_token").jsonObject
        assertEquals(
            token.getValue("pattern").jsonPrimitive.content,
            "^" + UpstreamValues.HEADER_TOKEN.pattern + "$",
        )
        for (case in Vectors.cases(token, "cases")) {
            val value = case.getValue("value").jsonPrimitive.content
            val ok = case.getValue("ok").jsonPrimitive.booleanOrNull!!
            assertEquals("header value ${value.quoted()}", ok, UpstreamValues.headerValueOk(value))
        }
    }

    // ---- Petersburg -----------------------------------------------------------

    @Test
    fun `petersburg login answers match the shared vectors`() = runBlocking {
        val fields = Vectors.petersburg.getValue("login_fields").jsonObject
        for (case in Vectors.cases(Vectors.petersburg, "login_cases")) {
            val name = case.getValue("name").jsonPrimitive.content
            server.enqueue(
                rawAnswer(
                    status = case.getValue("status").jsonPrimitive.int,
                    contentType = case.getValue("content_type").jsonPrimitive.content,
                    body = case.getValue("body").jsonPrimitive.content,
                    cookies = case.getValue("set_cookie").jsonArray.map { it.jsonPrimitive.content },
                ),
            )
            val outcome = runCatching {
                PetersburgSignIn(clientFor(server), originOf(server), clock)
                    .signIn(DiaryTarget.petersburg("parent@example.com"), "secret")
            }

            val request = server.takeRequest()
            assertEquals(name, Vectors.petersburg.getValue("login_path").jsonPrimitive.content, request.url.encodedPath)
            // Nobody is signed in yet, so nothing rides along.
            assertNull(name, request.headers["Cookie"])
            val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals(name, fields.keys + setOf("login", "password"), sent.keys)
            for ((field, value) in fields) assertEquals("$name: $field", value, sent[field])

            val expect = case.getValue("expect").jsonObject
            val failure = expect["failure"]?.jsonPrimitive?.content
            if (failure != null) {
                assertEquals(name, failure, kindOf(outcome.exceptionOrNull()))
            } else {
                assertEquals(name, expect.getValue("token").jsonPrimitive.content, outcome.getOrThrow().token)
            }
        }
    }

    @Test
    fun `the session cookie rule matches the shared vectors`() {
        val cookie = Vectors.petersburg.getValue("session_cookie").jsonPrimitive.content
        val url = (Vectors.petersburg.getValue("origin").jsonPrimitive.content +
            Vectors.petersburg.getValue("login_path").jsonPrimitive.content).toHttpUrl()
        val withCookies = Vectors.cases(Vectors.petersburg, "login_cases")
            .filter { it.getValue("set_cookie").jsonArray.isNotEmpty() }
        assertTrue("the vectors carry cookie cases", withCookies.isNotEmpty())
        for (case in withCookies) {
            val headers = headersOf(
                *case.getValue("set_cookie").jsonArray
                    .flatMap { listOf("Set-Cookie", it.jsonPrimitive.content) }
                    .toTypedArray(),
            )
            val token = case.getValue("expect").jsonObject.getValue("token").jsonPrimitive.content
            // «body» means the answer carried no live session cookie at all.
            val wanted = token.takeUnless { it == "body" }
            assertEquals(
                case.getValue("name").jsonPrimitive.content,
                wanted,
                UpstreamHttp.sessionCookie(url, headers, cookie, clock.millis()),
            )
        }
    }

    // ---- «Сетевой город» ------------------------------------------------------

    @Test
    fun `the session cookies and the login form are the server's`() {
        assertEquals(
            Vectors.netschool.getValue("session_cookies").jsonArray.map { it.jsonPrimitive.content },
            NetSchoolSignIn.SESSION_COOKIES,
        )
    }

    @Test
    fun `netschool hashes match the shared vectors`() {
        for (case in Vectors.cases(Vectors.netschool, "hash")) {
            val salt = case.getValue("salt").jsonPrimitive.content
            val password = case.getValue("password").jsonPrimitive.content
            val hashed = NetSchoolPassword.hash(salt, password)
            if ("failure" in case) {
                // Refused, and refused without the character in hand: the
                // caller's failure carries nothing of what was typed.
                assertNull(password.quoted(), hashed)
                continue
            }
            assertEquals(
                password.quoted(),
                case.getValue("pw").jsonPrimitive.content to case.getValue("pw2").jsonPrimitive.content,
                hashed,
            )
        }
    }

    @Test
    fun `an unencodable password is bad credentials and the failure carries no part of it`() = runBlocking {
        val script = NetSchoolScript()
        server.dispatcher = script
        val failure = runCatching {
            NetSchoolSignIn(clientFor(server), clock).signIn(regionAt(server), netschoolTarget(), "пароль😀")
        }.exceptionOrNull()

        assertEquals("bad_credentials", kindOf(failure))
        assertFalse("😀" in failure.toString())
        assertFalse("пароль" in failure.toString())
        // Nothing was posted: the refusal came before the login.
        assertTrue(script.to(NetSchoolSignIn.LOGIN_PATH).isEmpty())
    }

    @Test
    fun `logindata answers match the shared vectors`() = runBlocking {
        for (case in Vectors.cases(Vectors.netschool, "logindata_cases")) {
            val body = case.getValue("body")
            val script = NetSchoolScript(logindata = { jsonAnswer(200, body) })
            val outcome = signIn(script)

            val expect = case.getValue("expect").jsonPrimitive.content
            assertEquals("logindata $body", expect, kindOf(outcome.exceptionOrNull()))
            if (expect == "ok") {
                // The session's `ver` is logindata's `cacheVer`, and only a
                // string or an integer — never the word "null" or "true".
                assertEquals("logindata $body", case["ver"]?.jsonPrimitive?.contentOrNull, outcome.getOrThrow().ver)
            } else {
                // Nothing that failed at logindata may have asked for a salt.
                assertTrue(script.to(NetSchoolSignIn.GETDATA_PATH).isEmpty())
            }
        }
    }

    @Test
    fun `getdata answers match the shared vectors`() = runBlocking {
        val formFields = Vectors.netschool.getValue("login_form_fields").jsonArray.map { it.jsonPrimitive.content }
        for (case in Vectors.cases(Vectors.netschool, "getdata_cases")) {
            val body = case.getValue("body")
            val script = NetSchoolScript(
                getdata = { jsonAnswer(200, body) },
                logins = ArrayDeque(listOf({ jsonAnswer(200, Json.parseToJsonElement("""{"at":"x"}""")) })),
            )
            val outcome = signIn(script)

            val expect = case.getValue("expect").jsonPrimitive.content
            assertEquals("getdata $body", expect, kindOf(outcome.exceptionOrNull()))
            val logins = script.to(NetSchoolSignIn.LOGIN_PATH)
            if (expect != "ok") {
                // Nothing that failed at getdata may have posted a password.
                assertTrue("getdata $body", logins.isEmpty())
                continue
            }
            val form = logins.single().form()
            assertEquals("getdata $body", formFields, form.keys.toList())
            for ((field, value) in case.getValue("form").jsonObject) {
                assertEquals("getdata $body: $field", value.jsonPrimitive.content, form[field])
            }
        }
    }

    @Test
    fun `login answers match the shared vectors`() = runBlocking {
        for (case in Vectors.cases(Vectors.netschool, "login_cases")) {
            val status = case.getValue("status").jsonPrimitive.int
            val body = case.getValue("body")
            val label = "$status $body"
            val answer = { jsonAnswer(status, body) }
            val logins = if (case["tried_role"]?.jsonPrimitive?.booleanOrNull == true) {
                ArrayDeque(listOf({ jsonAnswer(200, Vectors.flow.getValue("role_question")) }, answer))
            } else {
                ArrayDeque(listOf(answer))
            }
            val script = NetSchoolScript(logins = logins)
            val outcome = signIn(script)
            val expect = case.getValue("expect").jsonObject

            val failure = expect["failure"]?.jsonPrimitive?.content
            if (failure != null) {
                val thrown = outcome.exceptionOrNull()
                assertEquals(label, failure, kindOf(thrown))
                if ("message" in expect) {
                    assertEquals(
                        label,
                        expect.getValue("message").jsonPrimitive.contentOrNull,
                        (thrown as UpstreamFailure.BadCredentials).upstreamMessage,
                    )
                }
                continue
            }
            val session = outcome.getOrThrow()
            if (expect["needs_role"]?.jsonPrimitive?.booleanOrNull == true) {
                // Rerun with a fresh salt, and the role picked from the answer.
                assertEquals(label, 2, script.to(NetSchoolSignIn.GETDATA_PATH).size)
                val (first, second) = script.to(NetSchoolSignIn.LOGIN_PATH).map { it.form() }
                assertFalse(label, "rolegroup" in first)
                assertEquals(label, expect.getValue("rolegroup").jsonPrimitive.content, second["rolegroup"])
                assertEquals(label, "after-role", session.at)
                continue
            }
            assertEquals(label, expect.getValue("at").jsonPrimitive.content, session.at)
            assertEquals(label, expect.getValue("time_out").jsonPrimitive.longOrNull, session.timeOut)
        }
    }

    @Test
    fun `role pick matches the shared vectors`() {
        for (case in Vectors.cases(Vectors.netschool, "role_pick")) {
            val body = case.getValue("body").jsonObject
            assertEquals(
                body.toString(),
                case.getValue("expect").jsonPrimitive.contentOrNull,
                NetSchoolSignIn.pickParentRole(body),
            )
        }
    }

    /** The one Cyrillic protocol constant beside the WAF markers, held to the vectors' parent role. */
    @Test
    fun `the parent role marker matches the parent role the vectors name`() {
        val question = Vectors.flow.getValue("role_question").jsonObject
        val role = question.getValue("accountInfo").jsonObject.getValue("userRoles").jsonArray
            .first().jsonObject.getValue("role").jsonObject
        assertTrue(UpstreamMarkers.PARENT_ROLE in role.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `the refusal rule and its markers match the shared vectors`() = runBlocking {
        val refusal = Vectors.netschool.getValue("refusal").jsonObject
        assertEquals(
            refusal.getValue("markers").jsonArray.map { it.jsonPrimitive.content },
            UpstreamMarkers.WAF,
        )
        for (case in Vectors.cases(refusal, "cases")) {
            val label = "${case["status"]} ${case["body"]}"
            val script = NetSchoolScript(
                logindata = {
                    rawAnswer(
                        status = case.getValue("status").jsonPrimitive.int,
                        contentType = case.getValue("content_type").jsonPrimitive.content,
                        body = case.getValue("body").jsonPrimitive.content,
                    )
                },
            )
            val refused = signIn(script).exceptionOrNull() is UpstreamFailure.AddressRefused
            assertEquals(label, case.getValue("refused").jsonPrimitive.booleanOrNull, refused)
        }
    }

    @Test
    fun `school search queries are cut like the shared vectors`() = runBlocking {
        val search = Vectors.netschool.getValue("schools_search").jsonObject
        assertEquals(search.getValue("path").jsonPrimitive.content, NetSchoolSchoolSearch.PATH)
        assertEquals(search.getValue("query_parameter").jsonPrimitive.content, NetSchoolSchoolSearch.QUERY_PARAMETER)
        val limit = search.getValue("query_cut_code_points").jsonPrimitive.int
        assertEquals(limit, NetSchoolSchoolSearch.QUERY_CUT_CODE_POINTS)
        for (case in Vectors.cases(search, "query_cases")) {
            val script = NetSchoolScript()
            server.dispatcher = script
            val query = case.getValue("query").jsonPrimitive.content
            searchOf(script).search("zabaikalsky", query).getOrThrow()

            val request = script.to(NetSchoolSchoolSearch.PATH).single()
            // No session rides a search.
            assertNull(request.headers["at"])
            assertNull(request.headers["Cookie"])
            val sent = request.url.queryParameter(NetSchoolSchoolSearch.QUERY_PARAMETER)!!
            assertEquals(query.take(12), case.getValue("sent").jsonPrimitive.content, sent)
            assertTrue(sent.codePointCount(0, sent.length) <= limit)
        }
    }

    @Test
    fun `school search rows match the shared vectors`() = runBlocking {
        val search = Vectors.netschool.getValue("schools_search").jsonObject
        for (case in Vectors.cases(search, "cases")) {
            val body = case.getValue("body")
            val script = NetSchoolScript(search = { jsonAnswer(200, body) })
            server.dispatcher = script
            val outcome = searchOf(script).search("zabaikalsky", "школа")

            val expect = case.getValue("expect")
            if (expect is JsonPrimitive) {
                assertEquals(body.toString(), "unexpected", expect.content)
                assertTrue(body.toString(), outcome.exceptionOrNull() is DiarySignInProblem.ProviderUnreadable)
                continue
            }
            val wanted = expect.jsonArray.map { row ->
                val fields = row.jsonObject
                DiarySchool(
                    id = fields.getValue("id").jsonPrimitive.longOrNull!!,
                    name = fields.getValue("name").jsonPrimitive.content,
                    address = fields["address"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
                )
            }
            assertEquals(body.toString(), wanted, outcome.getOrThrow())
        }
    }

    // ---- plumbing -------------------------------------------------------------

    private suspend fun signIn(script: NetSchoolScript): Result<UpstreamSession.NetSchool> {
        server.dispatcher = script
        val flow = Vectors.flow
        val target = netschoolTarget(
            login = flow.getValue("login").jsonPrimitive.content,
            schoolId = flow.getValue("school_id").jsonPrimitive.longOrNull!!,
        )
        return runCatching {
            NetSchoolSignIn(clientFor(server), clock)
                .signIn(regionAt(server), target, flow.getValue("password").jsonPrimitive.content)
        }
    }

    private fun searchOf(script: NetSchoolScript): NetSchoolSchoolSearch {
        server.dispatcher = script
        val directory = FakeUpstreamDirectory(petersburg = null, regions = listOf(regionAt(server)))
        return NetSchoolSchoolSearch(
            client = { clientFor(server) },
            directory = { directory },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
    }

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun String.quoted(): String = JsonPrimitive(this).toString()
}
