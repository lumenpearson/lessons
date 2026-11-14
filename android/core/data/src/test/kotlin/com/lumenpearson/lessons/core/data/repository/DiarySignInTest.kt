package com.lumenpearson.lessons.core.data.repository

import com.connectrpc.Code
import com.lumenpearson.lessons.core.data.network.v2.CallObservation
import com.lumenpearson.lessons.core.data.network.v2.RemoteError
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import com.lumenpearson.lessons.core.data.network.v2.DiaryRemote
import com.lumenpearson.lessons.core.data.network.v2.dto.CreateDiarySessionRequestDto
import com.lumenpearson.lessons.core.data.network.v2.restRequest
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.network.ServerAddressMissingException
import com.lumenpearson.lessons.core.data.upstream.FakeUpstreamDirectory
import com.lumenpearson.lessons.core.data.upstream.MutableClock
import com.lumenpearson.lessons.core.data.upstream.NetSchoolScript
import com.lumenpearson.lessons.core.data.upstream.NetSchoolSignIn
import com.lumenpearson.lessons.core.data.upstream.PETERSBURG_JWT
import com.lumenpearson.lessons.core.data.upstream.PetersburgSignIn
import com.lumenpearson.lessons.core.data.upstream.SCRIPT_SESSION_COOKIE
import com.lumenpearson.lessons.core.data.upstream.Vectors
import com.lumenpearson.lessons.core.data.upstream.UpstreamHttp
import com.lumenpearson.lessons.core.data.upstream.UpstreamSession
import com.lumenpearson.lessons.core.data.upstream.jsonAnswer
import com.lumenpearson.lessons.core.data.upstream.netschoolTarget
import com.lumenpearson.lessons.core.data.upstream.originOf
import com.lumenpearson.lessons.core.data.upstream.rawAnswer
import com.lumenpearson.lessons.core.data.upstream.regionAt
import java.io.IOException
import java.time.LocalDate
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Getting into a diary from the phone, end to end: the preflight that runs
 * before a password is taken, the sign-in with the diary's own server (a real
 * socket, MockWebServer), the registration with ours (scripted), and what is
 * kept at the end of it — our bearer and the target, never the diary's session
 * and never the password.
 */
class DiarySignInTest {

    private lateinit var server: MockWebServer
    private lateinit var script: NetSchoolScript
    private val api = ScriptedDiaryApi()
    /** Every forget, pupil selection and session write, in the order they happened. */
    private val events = mutableListOf<String>()
    private val store = RecordingStore(events)
    private val clock = MutableClock()
    private var forgotten = 0

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

    // ---- the whole way in ----------------------------------------------------

    @Test
    fun `a sign-in registers and stores our bearer and the target, never the upstream credential`() = runBlocking {
        val registration = signIn().signIn(netschoolTarget(), "hunter2").getOrThrow()

        val stored = store.session!!
        assertEquals("ours", stored.token)
        assertEquals("parent", stored.login)
        assertEquals("zabaikalsky", stored.target.region)
        assertEquals(42L, stored.target.schoolId)
        assertEquals("Школа № 1", stored.target.schoolName)
        // The zone is the server's, so the phone's «today» is its too.
        assertEquals("Asia/Chita", stored.target.zone)
        assertEquals(stored, registration.session)
        assertEquals(listOf(7L), registration.students.map { it.id })

        // Nothing the diary handed over, and nothing typed, is kept anywhere.
        val everything = "$stored ${store.target} ${store.student}"
        for (secret in listOf("after-role", "hunter2", NetSchoolScript_COOKIE)) {
            assertFalse(secret, secret in everything)
        }
    }

    /**
     * The one body that ever leaves for our server carries the diary's session
     * and no password — there is no field for one, and the server refuses any
     * key it does not know.
     */
    @Test
    fun `the registration body is the server's shape and carries no password`() = runBlocking {
        signIn().signIn(netschoolTarget(), "hunter2").getOrThrow()

        val written = Json.parseToJsonElement(
            NetworkModule.json().encodeToString(
                CreateDiarySessionRequestDto.serializer(), api.registered.single().restRequest(),
            ),
        ).jsonObject
        assertEquals(setOf("login", "region", "schoolId", "netschool"), written.keys)
        assertEquals("42", written.getValue("schoolId").jsonPrimitive.content)
        val credential = written.getValue("netschool").jsonObject
        assertEquals(setOf("at", "cookies", "ver"), credential.keys)
        assertEquals("after-role", credential.getValue("at").jsonPrimitive.content)
        assertEquals(
            mapOf("nsSessionId" to NetSchoolScript_COOKIE),
            credential.getValue("cookies").jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertFalse("hunter2" in written.toString())
    }

    @Test
    fun `a Petersburg sign-in registers the token Petersburg issued`() = runBlocking {
        signIn().signIn(DiaryTarget.petersburg(" parent@example.com "), "secret").getOrThrow()

        val body = api.registered.single()
        assertEquals("petersburg", body.target.provider.wire)
        assertEquals("parent@example.com", body.target.login)
        assertNull(body.target.region)
        assertEquals(PETERSBURG_JWT, (body.credential as DiaryCredential.Petersburg).token)
        assertEquals(DiaryProviderKey.PETERSBURG, store.session!!.target.provider)
    }

    // ---- the preflight: before any password ----------------------------------

    @Test
    fun `no server address fails before any upstream request`() = runBlocking {
        api.capabilities = { throw ServerAddressMissingException() }

        val failure = signIn().signIn(netschoolTarget(), "hunter2").exceptionOrNull()

        assertEquals(DiarySignInProblem.ServerMissing, failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a 404 on capabilities fails before the password is sent`() = runBlocking {
        api.capabilities = { throw httpError(404) }

        val failure = signIn().preflight(netschoolTarget()).exceptionOrNull()

        assertEquals(DiarySignInProblem.ServerTooOld, failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a server with its diary switched off fails before the password is sent`() = runBlocking {
        api.capabilities = { DiaryCapabilities(false, emptyMap()) }

        val failure = signIn().signIn(netschoolTarget(), "hunter2").exceptionOrNull()

        assertEquals(DiarySignInProblem.ServerDisabled, failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a region the server does not list fails before the password is sent`() = runBlocking {
        api.capabilities = { listing(regions = listOf("samara")) }

        val failure = signIn().signIn(netschoolTarget(), "hunter2").exceptionOrNull()

        assertEquals(DiarySignInProblem.RegionNotServed, failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a region the phone's catalog does not know is not asked about at all`() = runBlocking {
        val failure = signIn().preflight(netschoolTarget(region = "volgograd")).exceptionOrNull()

        assertEquals(DiarySignInProblem.RegionNotServed, failure)
        assertEquals(0, api.capabilityCalls)
    }

    @Test
    fun `a Gosuslugi-only region is handed off to its own site with nothing sent`() = runBlocking {
        val directory = FakeUpstreamDirectory(null, listOf(regionAt(server, key = "tula", password = false)))

        val failure = signIn(directory).signIn(netschoolTarget(region = "tula"), "hunter2").exceptionOrNull()

        assertEquals(DiarySignInProblem.GosuslugiOnly("https://region.zabedu.ru"), failure)
        assertEquals(0, server.requestCount)
        assertEquals(0, api.capabilityCalls)
    }

    /**
     * A login pasted from a chat carries invisible characters — bidi isolates
     * around it, a zero-width space after it. The server always cleaned them
     * before any upstream call; the diary is sent, and our server registers,
     * the same cleaned login, and one that cleans to under three characters is
     * refused with nothing sent anywhere.
     */
    @Test
    fun `a pasted login goes to the diary and the registration cleaned, as the server cleans it`() = runBlocking {
        signIn().signIn(DiaryTarget.petersburg("\u2068parent@example.com\u2069\u200b"), "secret").getOrThrow()

        val sent = Json.parseToJsonElement(script.to(PetersburgSignIn.LOGIN_PATH).single().body!!.utf8()).jsonObject
        assertEquals("parent@example.com", sent.getValue("login").jsonPrimitive.content)
        assertEquals("parent@example.com", api.registered.single().target.login)
        assertEquals("parent@example.com", store.session!!.target.login)

        val failure = signIn().openUpstream(netschoolTarget(login = "ab\u200b"), "hunter2").exceptionOrNull()
        assertEquals(DiarySignInProblem.LoginTooShort, failure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a login too short fails before anything is sent`() = runBlocking {
        val failure = signIn().openUpstream(netschoolTarget(login = " ab "), "hunter2").exceptionOrNull()

        assertEquals(DiarySignInProblem.LoginTooShort, failure)
        assertEquals(0, server.requestCount)
    }

    // ---- the diary's own answer ----------------------------------------------

    /**
     * #233: every limit on the way was per call, and «Сетевой город» is three
     * calls or five, so a region answering each one slowly kept «Войти» busy
     * for minutes without a single call timing out. Each answer here takes a
     * fraction of the budget and the three together take more than it.
     */
    @Test
    fun `a diary that answers every call slowly gets one deadline for the whole sign-in`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(SLOW_ANSWER_MILLIS)
                return script.dispatch(request)
            }
        }

        val failure = signIn(upstreamBudgetMillis = SLOW_ANSWER_MILLIS * 2)
            .openUpstream(netschoolTarget(), "hunter2")
            .exceptionOrNull()

        assertEquals(DiarySignInProblem.Timeout(originOf(server).host), failure)
    }

    @Test
    fun `a refusal by the diary names the diary's host`() = runBlocking {
        script.logindata = { rawAnswer(403, "application/json", "{}") }

        val failure = signIn().openUpstream(netschoolTarget(), "hunter2").exceptionOrNull()

        assertEquals(DiarySignInProblem.ProviderRefusesPhone(originOf(server).host), failure)
    }

    /** K19: `logindata` says «Госуслуги only» and names no site; the catalog does. */
    @Test
    fun `logindata saying Gosuslugi only hands off to the catalog's site`() = runBlocking {
        script.logindata = { jsonAnswer(200, Json.parseToJsonElement("""{"schoolLogin":false}""")) }

        val failure = signIn().openUpstream(netschoolTarget(), "hunter2").exceptionOrNull()

        assertEquals(DiarySignInProblem.GosuslugiOnly("https://region.zabedu.ru"), failure)
    }

    @Test
    fun `a wrong password carries what the diary said and nothing typed`() = runBlocking {
        script.logins.addLast { jsonAnswer(409, Json.parseToJsonElement("""{"message":"Неверный пароль"}""")) }

        val failure = signIn().signIn(netschoolTarget(), "hunter2").exceptionOrNull()

        assertEquals(DiarySignInProblem.WrongPassword("Неверный пароль"), failure)
        assertFalse("hunter2" in failure.toString())
        assertTrue(api.registered.isEmpty())
    }

    /** A session the diary shaped in a way the server would refuse is «unreadable», not a 422 from us. */
    @Test
    fun `a Petersburg token that is not a JWT is never sent`() = runBlocking {
        script.petersburg = { rawAnswer(200, "application/json", """{"data":{"token":"not-a-jwt"}}""") }

        val failure = signIn().signIn(DiaryTarget.petersburg("parent@example.com"), "secret").exceptionOrNull()

        assertTrue(failure is DiarySignInProblem.ProviderUnreadable)
        assertTrue(api.registered.isEmpty())
    }

    @Test
    fun `a NetSchool session without its session cookie is never sent`() = runBlocking {
        val sessionless = NetSchoolScript(logindata = { jsonAnswer(200, Vectors.flow.getValue("logindata")) })
        server.dispatcher = sessionless
        val signIn = signIn()
        val upstream = signIn.openUpstream(netschoolTarget(), "hunter2").getOrThrow()

        val failure = signIn.register(upstream).exceptionOrNull()

        assertTrue(failure is DiarySignInProblem.ProviderUnreadable)
        assertTrue(api.registered.isEmpty())
        // And said goodbye to, since nobody can use it.
        assertEquals(1, sessionless.to(NetSchoolSignIn.LOGOUT_PATH).size)
    }

    // ---- the registration ----------------------------------------------------

    @Test
    fun `a refused registration logs out upstream once`() = runBlocking {
        api.register = { throw httpError(409) }

        val failure = signIn().signIn(netschoolTarget(), "hunter2").exceptionOrNull()

        assertEquals(DiarySignInProblem.ServerRefusedSession, failure)
        assertEquals(1, script.to(NetSchoolSignIn.LOGOUT_PATH).size)
        assertNull(store.session)
    }

    @Test
    fun `registration 403 is an account with no pupil`() = runBlocking {
        api.register = { throw httpError(403) }

        assertEquals(DiarySignInProblem.NoStudent, signIn().signIn(netschoolTarget(), "x1").exceptionOrNull())
    }

    /** A 422 is a body the server refused before asking anybody — ours to fix, not a password to retype. */
    @Test
    fun `registration 422 is unexpected, not a wrong password`() = runBlocking {
        api.register = { throw httpError(422) }

        val failure = signIn().signIn(netschoolTarget(), "hunter2").exceptionOrNull()

        assertTrue(failure is DiarySignInProblem.Unexpected)
        assertEquals(DiarySignInProblem.Action.RETRY, (failure as DiarySignInProblem).action)
    }

    @Test
    fun `registration 503s are told apart by their header`() = runBlocking {
        val cases = mapOf(
            "disabled" to DiarySignInProblem.ServerDisabled,
            "address-refused" to DiarySignInProblem.ServerAddressRefused,
            "upstream" to DiarySignInProblem.ProviderUnavailable(null),
        )
        for ((reason, wanted) in cases) {
            api.register = { throw httpError(503, DiaryFailure.UNAVAILABLE_HEADER to reason) }
            assertEquals(reason, wanted, signIn().signIn(netschoolTarget(), "hunter2").exceptionOrNull())
        }
    }

    @Test
    fun `registration 504 is a timeout`() = runBlocking {
        api.register = { throw httpError(504) }

        assertEquals(DiarySignInProblem.Timeout(null), signIn().signIn(netschoolTarget(), "hunter2").exceptionOrNull())
    }

    /**
     * Throttled: the session is still good and still held, so the retry after
     * the wait registers it again — without asking for the password.
     */
    @Test
    fun `registration 429 carries the wait and keeps the session for a retry`() = runBlocking {
        val signIn = signIn()
        val upstream = signIn.openUpstream(netschoolTarget(), "hunter2").getOrThrow()
        api.register = { throw httpError(429, "Retry-After" to "30") }

        val failure = signIn.register(upstream).exceptionOrNull() as DiarySignInProblem

        assertEquals(DiarySignInProblem.TooManyAttempts(30), failure)
        assertTrue(failure.retryKeepsSession)
        assertTrue(script.to(NetSchoolSignIn.LOGOUT_PATH).isEmpty())

        api.register = ACCEPT
        assertEquals("ours", signIn.register(upstream).getOrThrow().session.token)
        assertEquals(1, script.to(NetSchoolSignIn.LOGIN_PATH).size)
    }

    @Test
    fun `a registration that cannot reach the server keeps the session for a retry`() = runBlocking {
        val signIn = signIn()
        val upstream = signIn.openUpstream(netschoolTarget(), "hunter2").getOrThrow()
        api.register = { throw IOException("connection reset") }

        val failure = signIn.register(upstream).exceptionOrNull() as DiarySignInProblem

        assertEquals(DiarySignInProblem.RegisterUnreachable, failure)
        assertTrue(failure.retryKeepsSession)
        api.register = ACCEPT
        assertTrue(signIn.register(upstream).isSuccess)
    }

    @Test
    fun `an aged-out upstream session is discarded, not registered`() = runBlocking {
        val signIn = signIn()
        val upstream = signIn.openUpstream(netschoolTarget(), "hunter2").getOrThrow()
        clock.now = clock.now.plus(Duration.ofMinutes(11))

        val failure = signIn.register(upstream).exceptionOrNull()

        assertEquals(DiarySignInProblem.SessionAgedOut, failure)
        assertTrue(api.registered.isEmpty())
        assertEquals(1, script.to(NetSchoolSignIn.LOGOUT_PATH).size)
    }

    /** «Сетевой город» says how long its sessions idle for; that, not ten minutes, is the limit. */
    @Test
    fun `a session's own idle window decides when it is too old`() = runBlocking {
        script.logins.addLast { jsonAnswer(200, Json.parseToJsonElement("""{"at":"bearer-1","timeOut":1800000}""")) }
        script.logindata = {
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "NSSESSIONID=s1; Path=/").body("{}").build()
        }
        val signIn = signIn()
        val upstream = signIn.openUpstream(netschoolTarget(), "hunter2").getOrThrow()
        clock.now = clock.now.plus(Duration.ofMinutes(20))

        assertTrue(signIn.register(upstream).isSuccess)
    }

    @Test
    fun `a registered session is never registered or discarded again`() = runBlocking {
        val signIn = signIn()
        val upstream = signIn.openUpstream(netschoolTarget(), "hunter2").getOrThrow()
        signIn.register(upstream).getOrThrow()

        signIn.discard(upstream)
        val again = signIn.register(upstream).exceptionOrNull()

        assertTrue(again is DiarySignInProblem.Unexpected)
        assertEquals(1, api.registered.size)
        // Saying goodbye would end the session the server now holds.
        assertTrue(script.to(NetSchoolSignIn.LOGOUT_PATH).isEmpty())
    }

    @Test
    fun `backing out before registration says goodbye upstream once`() = runBlocking {
        val signIn = signIn()
        val upstream = signIn.openUpstream(netschoolTarget(), "hunter2").getOrThrow()

        signIn.discard(upstream)
        signIn.discard(upstream)

        assertEquals(1, script.to(NetSchoolSignIn.LOGOUT_PATH).size)
    }

    // ---- what is kept -----------------------------------------------------------

    /** Re-authentication: the same provider, region and school, and only the password typed. */
    @Test
    fun `re-auth reuses provider, region and school`() = runBlocking {
        val kept = DiaryTarget.netschool("zabaikalsky", 42, "Школа № 1", "parent", "Asia/Chita")
        store.target = kept

        signIn().signIn(kept, "hunter2").getOrThrow()

        val body = api.registered.single()
        assertEquals("zabaikalsky", body.target.region)
        assertEquals(42L, body.target.schoolId)
        assertEquals("42", script.to(NetSchoolSignIn.LOGIN_PATH).single().body!!.utf8().substringAfter("scid=").substringBefore('&'))
    }

    @Test
    fun `a different account empties what was kept first, and the same account keeps it`() = runBlocking {
        store.target = DiaryTarget.netschool("zabaikalsky", 42, null, "parent", "Asia/Chita")
        store.student = 7

        signIn().signIn(netschoolTarget(login = "PARENT"), "hunter2").getOrThrow()
        assertEquals(0, forgotten)
        assertEquals(7L, store.student)

        events.clear()
        signIn().signIn(netschoolTarget(login = "other-parent"), "hunter2").getOrThrow()
        assertEquals(1, forgotten)
        assertNull(store.student)
        // In this order: were the write first, a process dying between the two
        // would leave the new account's bearer over the old account's rows,
        // and every later sign-in to it would match and keep them.
        assertEquals(listOf("forget", "select:null", "write"), events)
    }

    @Test
    fun `an upstream session never prints or exposes its secret`() = runBlocking {
        val upstream: UpstreamSession = signIn().openUpstream(netschoolTarget(), "hunter2").getOrThrow()

        assertEquals("UpstreamSession(netschool, <redacted>)", upstream.toString())
        assertFalse(upstream is java.io.Serializable)
    }

    @Test
    fun `an opened diary session refused by v2 keeps the session refusal recovery`() = runBlocking {
        val flow = signIn()
        val upstream = flow.openUpstream(netschoolTarget(), "hunter2").getOrThrow()
        assertFalse(upstream.spent)
        var handedOver: DiaryHandover? = null
        api.register = { body ->
            handedOver = body
            throw RemoteError(Code.PERMISSION_DENIED, "DIARY_CREDENTIALS_REJECTED", httpStatus = 403)
        }

        val problem = flow.register(upstream).exceptionOrNull()

        assertTrue("The phone already opened a session before the server refused it", handedOver != null)
        assertEquals(DiarySignInProblem.ServerRefusedSession, problem)
        assertEquals(DiarySignInProblem.Action.NONE, (problem as DiarySignInProblem).action)
        assertNull(store.session)
    }

    // ---- plumbing ---------------------------------------------------------------

    private fun signIn(
        directory: FakeUpstreamDirectory = FakeUpstreamDirectory(originOf(server), listOf(regionAt(server))),
        upstreamBudgetMillis: Long = 25_000L,
    ) = DiarySignInImpl(
        api = api,
        store = store,
        directory = { directory },
        client = { UpstreamHttp.client { directory.allowedOrigins() } },
        forgetLocal = {
            forgotten++
            events += "forget"
        },
        clock = clock,
        ioDispatcher = Dispatchers.Unconfined,
        upstreamBudgetMillis = upstreamBudgetMillis,
    )

    private fun listing(regions: List<String> = listOf("zabaikalsky")) = DiaryCapabilities(
        enabled = true,
        providers = mapOf("petersburg" to emptySet(), "netschool" to regions.toSet()),
    )

    /** Our server, as far as registration goes; every read fails the test. */
    private inner class ScriptedDiaryApi : DiaryRemote {
        var capabilities: () -> DiaryCapabilities = { listing() }
        var register: (DiaryHandover) -> DiaryRegistration = ACCEPT
        var capabilityCalls = 0
        val registered = mutableListOf<DiaryHandover>()

        override suspend fun capabilities(): DiaryCapabilities {
            capabilityCalls++
            return capabilities.invoke()
        }

        override suspend fun registerSession(body: DiaryHandover): DiaryRegistration {
            val answer = register(body)
            registered += body
            return answer
        }

        override suspend fun logout() = fail()
        override suspend fun students(observation: CallObservation): List<DiaryStudent> = fail()
        override suspend fun schedule(
            studentId: Long,
            from: LocalDate,
            to: LocalDate,
            observation: CallObservation,
        ): List<DiaryLesson> = fail()
        override suspend fun homework(
            studentId: Long,
            from: LocalDate,
            to: LocalDate,
            observation: CallObservation,
        ): List<DiaryHomework> = fail()
        override suspend fun grades(
            studentId: Long,
            from: LocalDate,
            to: LocalDate,
            observation: CallObservation,
        ): List<DiaryMark> = fail()
        override suspend fun periods(studentId: Long, observation: CallObservation): List<DiaryPeriod> = fail()
        override suspend fun overrides(studentId: Long, observation: CallObservation): List<DiaryOverrideRecord> =
            fail()
        override suspend fun correct(
            studentId: Long,
            target: String,
            field: DiaryField,
            value: String,
            original: String?,
            observation: CallObservation,
        ) = fail()
        override suspend fun reset(
            studentId: Long,
            target: String,
            field: DiaryField,
            observation: CallObservation,
        ) = fail()
        override suspend fun resetAll(studentId: Long, observation: CallObservation) = fail()

        private fun fail(): Nothing = throw AssertionError("a sign-in reads nothing from the diary")
    }

    /** The store, with nothing in it but what was last written. */
    private class RecordingStore(private val events: MutableList<String>) : DiarySessionStore {
        var session: DiarySession? = null
        var target: DiaryTarget? = null
        var student: Long? = null

        override val diarySession: Flow<DiarySession?> get() = MutableStateFlow(session)
        override suspend fun currentDiarySession(): DiarySession? = session
        override suspend fun writeDiarySession(value: DiarySession) {
            events += "write"
            session = value
            target = value.target
        }

        override suspend fun clearDiaryToken() {
            session = null
        }

        override suspend fun rejectDiaryRequest(sent: RequestCredentials) {
            if (session?.token == sent.diaryToken) clearDiaryToken()
        }

        override suspend fun forgetDiary() {
            session = null
            target = null
            student = null
        }

        override val diaryTarget: Flow<DiaryTarget?> get() = MutableStateFlow(target)
        override suspend fun currentDiaryTarget(): DiaryTarget? = target
        override val selectedStudentId: Flow<Long?> get() = MutableStateFlow(student)
        override suspend fun selectStudent(id: Long?) {
            events += "select:$id"
            student = id
        }
    }

    private companion object {
        /** What the script's `logindata` sets as `NSSESSIONID`. */
        const val NetSchoolScript_COOKIE = SCRIPT_SESSION_COOKIE
    }
}

/** Our server's answer to a registration it accepts, echoing what was sent. */
private val ACCEPT: (DiaryHandover) -> DiaryRegistration = { body ->
    val target = body.target.copy(
        schoolName = if (body.target.provider == DiaryProviderKey.NETSCHOOL) "Школа № 1" else null,
        zone = if (body.target.provider == DiaryProviderKey.NETSCHOOL) "Asia/Chita" else "Europe/Moscow",
    )
    DiaryRegistration(
        DiarySession(target.login, "ours", target),
        listOf(DiaryStudent(7, "Пётр", "Иванов", null, "Иванов Пётр", null, null)),
    )
}

private fun httpError(code: Int, vararg headers: Pair<String, String>): HttpException {
    val request = Request.Builder().url("https://school.example/api/v1/diary/session").build()
    val raw = okhttp3.Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("error")
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .build()
    return HttpException(
        Response.error<Unit>("""{"detail":"no"}""".toResponseBody("application/json".toMediaType()), raw),
    )
}

/** One slow answer: well inside every per-call limit, a large share of a test budget. */
private const val SLOW_ANSWER_MILLIS = 300L
