package com.lumenpearson.lessons.core.data.network

import com.lumenpearson.lessons.core.data.database.InMemoryTimetableDao
import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.datastore.MemoryStore
import com.lumenpearson.lessons.core.data.datastore.testVault
import com.lumenpearson.lessons.core.data.network.dto.BundleDto
import com.lumenpearson.lessons.core.data.network.dto.DeviceMeDto
import com.lumenpearson.lessons.core.data.network.dto.HealthDto
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import com.lumenpearson.lessons.core.data.network.dto.JoinResponseDto
import com.lumenpearson.lessons.core.data.network.dto.UnlinkResponseDto
import com.lumenpearson.lessons.core.data.network.dto.WarmupDto
import com.lumenpearson.lessons.core.data.repository.ServerStatus
import com.lumenpearson.lessons.core.data.repository.SessionRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.SyncResult
import com.lumenpearson.lessons.core.data.repository.TimetableCache
import com.lumenpearson.lessons.core.data.repository.TimetableRepositoryImpl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Response as RetrofitResponse

/**
 * A release build talks to its server over https only (#202).
 *
 * The owner's decision of 27 September: plain `http://` carries the class
 * bearer — often a write token — and the diary's, readable by anybody on the
 * same school Wi-Fi, and a LAN server is not worth that. What is under test is
 * the half this module owns: an address the build refuses is refused *before*
 * the request leaves, so no bearer is ever written to the socket, and every
 * place that reports it says «needs https» rather than «no network» or «no
 * address». Which hosts a build refuses is the app's
 * `network_security_config.xml`, held by `NetworkSecurityConfigTest` in :app;
 * here the two builds are two policies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerNeedsHttpsTest {

    /** What the release configuration answers: only the phone itself. */
    private val release = CleartextPolicy { host -> host == "localhost" || host == "127.0.0.1" }

    /** And the debug one: anywhere. */
    private val debug = CleartextPolicy { true }

    /** A build that refuses the test server's own host, whatever the JVM calls it. */
    private val refusesEverything = CleartextPolicy { false }

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            enqueue(MockResponse.Builder().code(200).body("{}").build())
            start()
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a refused http address is refused before anything reaches the server`() {
        val client = NetworkModule.okHttpClient(
            credentials = { RequestCredentials(server.url("/").toString(), "class-token", "diary-token") },
            cleartext = refusesEverything,
        )

        for (path in listOf("/api/v1/bundle", "/api/v1/diary/days", "/api/v1/join")) {
            try {
                client.newCall(Request.Builder().url("http://base-url.invalid$path").build()).execute().close()
                fail("$path was sent")
            } catch (refused: ServerNeedsHttpsException) {
                // What every «set the server» answer already catches, by kind.
                assertTrue(refused is ServerAddressMissingException)
            }
        }
        assertEquals("not a byte, and so not a bearer, reached the server", 0, server.requestCount)
    }

    @Test
    fun `a build that allows the host still sends to it`() {
        val client = NetworkModule.okHttpClient(
            credentials = { RequestCredentials(server.url("/").toString(), "class-token", null) },
            cleartext = debug,
        )

        client.newCall(Request.Builder().url("http://base-url.invalid/api/v1/bundle").build()).execute().close()

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an https address is never refused, whatever the build says about cleartext`() {
        var sent: HttpUrl? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(BaseUrlInterceptor({ "https://school.example/lessons/" }, refusesEverything))
            .addInterceptor { chain ->
                sent = chain.request().url
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody())
                    .build()
            }
            .build()

        client.newCall(Request.Builder().url("http://base-url.invalid/api/v1/bundle").build()).execute().close()

        assertEquals("https://school.example/lessons/api/v1/bundle", sent.toString())
    }

    @Test
    fun `the verdict on a typed address is the interceptor's`() {
        val cases = listOf(
            Triple("", release, CleartextVerdict.NOT_CLEARTEXT),
            Triple("   ", release, CleartextVerdict.NOT_CLEARTEXT),
            Triple("not an address", release, CleartextVerdict.NOT_CLEARTEXT),
            Triple("https://lessons.example.com/", release, CleartextVerdict.NOT_CLEARTEXT),
            Triple("HTTPS://Lessons.Example.com", release, CleartextVerdict.NOT_CLEARTEXT),
            Triple("http://192.168.1.50:8000/", release, CleartextVerdict.REFUSED),
            Triple(" HTTP://school.example ", release, CleartextVerdict.REFUSED),
            Triple("http://127.0.0.1:8000", release, CleartextVerdict.PERMITTED),
            Triple("http://localhost:8000/", release, CleartextVerdict.PERMITTED),
            Triple("http://192.168.1.50:8000/", debug, CleartextVerdict.PERMITTED),
        )
        for ((address, policy, expected) in cases) {
            assertEquals("«$address»", expected, cleartextVerdict(address, policy))
        }
    }

    @Test
    fun `a sync against a refused address stops, says why, and keeps the class`() = runTest {
        var rejected = 0
        val result = TimetableRepositoryImpl(
            dao = InMemoryTimetableDao(),
            api = RefusingApi,
            activeClassId = flowOf(1L),
            ioDispatcher = UnconfinedTestDispatcher(),
            onTokenRejected = { rejected++ },
        ).refresh()

        assertEquals(SyncResult.NeedsHttps, result)
        assertEquals("the token is not what was wrong", 0, rejected)
    }

    @Test
    fun `the server badge calls a refused address that, not an unreachable server`() = runTest {
        val preferences = LessonsPreferences(MemoryStore(), testVault())
        preferences.updateSettings { it.copy(baseUrl = "http://192.168.1.50:8000/") }
        val repository = SessionRepositoryImpl(
            preferences = preferences,
            api = RefusingApi,
            cache = NoCache,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        assertEquals(ServerStatus.NeedsHttps, repository.serverStatus())
    }

    /** What the interceptor throws for every call, as the real client would. */
    private object RefusingApi : LessonsApi {
        private fun refuse(): Nothing = throw ServerNeedsHttpsException("192.168.1.50")
        override suspend fun join(body: JoinRequestDto): JoinResponseDto = refuse()
        override suspend fun bundle(start: String, days: Int, ifNoneMatch: String?): RetrofitResponse<BundleDto> =
            refuse()
        override suspend fun health(): HealthDto = refuse()
        override suspend fun warmup(): WarmupDto = refuse()
        override suspend fun me(): DeviceMeDto = refuse()
        override suspend fun unlink(): UnlinkResponseDto = refuse()
    }

    private object NoCache : TimetableCache {
        override suspend fun forgetClass(classId: Long) = Unit
        override suspend fun forgetEverything() = Unit
    }
}
