package com.lumenpearson.lessons.core.data.datastore

import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.Session
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * What the interceptors put on a request, read through the real preferences and
 * the real client — and read from memory.
 *
 * They used to read the store through `runBlocking` on every request, for the
 * address and again for the bearer. The cure has two ways to be wrong, and
 * each is a request signed for somebody else: a copy that is still empty when
 * a process's first request leaves, and a copy that has not yet heard about a
 * switch when the request after it does. Neither may be answered by waiting —
 * nothing in the app waits between a switch and the sync it asks for — so no
 * test here waits either, except the one about a write the copy can only hear
 * about through the store.
 *
 * The store is [MemoryStore] rather than DataStore's file: what is under test is
 * when the copy is filled and refreshed, none of which is about files, and
 * DataStore's file layer cannot replace a file on Windows, where these run too.
 */
class RequestCredentialsTest {

    private lateinit var server: MockWebServer
    private val keys = MemoryKeys()
    private val vault = testVault(keys)
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        server = answering(MockWebServer())
    }

    @After
    fun tearDown() {
        server.close()
        scopes.forEach { it.cancel() }
    }

    /** The regression itself: nothing per request touches the store. */
    @Test
    fun `requests are signed from memory, not from the preferences`() = runBlocking {
        val store = MemoryStore()
        val preferences = LessonsPreferences(store, vault)
        preferences.updateSettings { it.copy(baseUrl = server.url("/").toString()) }
        preferences.addSession(session(7, "token-7"))
        val client = NetworkModule.okHttpClient(preferences.credentials::current, cleartext = { true })
        val readsBefore = store.reads.get()

        repeat(20) {
            assertEquals("Bearer token-7", authorizationOn(client, "/api/v1/bundle"))
        }

        assertEquals(
            "every request used to read the store again, for the address and for the bearer",
            readsBefore,
            store.reads.get(),
        )
    }

    /**
     * A process's first request, before anything has filled the copy — the
     * widget's first sync after a cold start can be exactly that. A new store
     * holding what the last process wrote, and a new copy holding nothing, is
     * what a cold start looks like from here; an empty answer would send that
     * sync to no address, without its bearer.
     */
    @Test
    fun `the first request after a cold start carries what was stored before it`() = runBlocking {
        val lastProcess = MemoryStore()
        LessonsPreferences(lastProcess, vault).apply {
            updateSettings { it.copy(baseUrl = server.url("/").toString()) }
            addSession(session(7, "token-7"))
            writeDiarySession(DiarySession(login = "parent", token = "diary-1"))
        }

        val preferences = LessonsPreferences(MemoryStore(lastProcess.value), testVault(keys))
        val client = NetworkModule.okHttpClient(preferences.credentials::current, cleartext = { true })

        assertEquals("Bearer token-7", authorizationOn(client, "/api/v1/bundle"))
        assertEquals("Bearer diary-1", authorizationOn(client, "/api/v1/diary/days"))
    }

    /**
     * The several-classes half. `select` is followed at once by the sync it
     * asks for, and that request must be the new class's — no follower runs
     * here, so only the write itself can have told the copy.
     */
    @Test
    fun `a switch of class signs the very next request`() = runBlocking {
        val preferences = LessonsPreferences(MemoryStore(), vault)
        preferences.updateSettings { it.copy(baseUrl = server.url("/").toString()) }
        preferences.addSession(session(1, "token-1"))
        preferences.addSession(session(2, "token-2"))
        val client = NetworkModule.okHttpClient(preferences.credentials::current, cleartext = { true })
        assertEquals("Bearer token-2", authorizationOn(client, "/api/v1/bundle"))

        preferences.selectSession(1)
        assertEquals("Bearer token-1", authorizationOn(client, "/api/v1/bundle"))

        preferences.removeSession(1)
        assertEquals("Bearer token-2", authorizationOn(client, "/api/v1/bundle"))

        preferences.clearSession()
        assertNull(
            "a phone that has left every class signs nothing with the last one's token",
            authorizationOn(client, "/api/v1/bundle"),
        )
    }

    /** The base-URL half: a new address is where the very next request goes. */
    @Test
    fun `a new server address takes the very next request`() = runBlocking {
        answering(MockWebServer()).use { other ->
            val preferences = LessonsPreferences(MemoryStore(), vault)
            preferences.updateSettings { it.copy(baseUrl = server.url("/").toString()) }
            val client = NetworkModule.okHttpClient(preferences.credentials::current, cleartext = { true })
            authorizationOn(client, "/api/v1/warmup")
            assertEquals(1, server.requestCount)

            preferences.updateSettings { it.copy(baseUrl = other.url("/").toString()) }
            authorizationOn(client, "/api/v1/warmup", at = other)

            assertEquals("the old server heard nothing more", 1, server.requestCount)
            assertEquals(1, other.requestCount)
        }
    }

    /** Two accounts, two bearers: signing out of one leaves the other signing. */
    @Test
    fun `the diary bearer follows its own sign-in and sign-out, and only its own`() = runBlocking {
        val preferences = LessonsPreferences(MemoryStore(), vault)
        preferences.updateSettings { it.copy(baseUrl = server.url("/").toString()) }
        preferences.addSession(session(7, "token-7"))
        val client = NetworkModule.okHttpClient(preferences.credentials::current, cleartext = { true })
        assertNull(authorizationOn(client, "/api/v1/diary/days"))

        preferences.writeDiarySession(DiarySession(login = "parent", token = "diary-1"))
        assertEquals("Bearer diary-1", authorizationOn(client, "/api/v1/diary/days"))

        preferences.forgetDiary()
        assertNull(authorizationOn(client, "/api/v1/diary/days"))
        assertEquals("Bearer token-7", authorizationOn(client, "/api/v1/bundle"))
    }

    /**
     * `SchoolAlerts` opens preferences of its own, and a write through that
     * instance does not refresh this one's copy; the follower is what hears
     * about it. Without one, the copy would name class 2 for ever.
     */
    @Test
    fun `a write through another instance reaches the copy through the store`() = runBlocking {
        val store = MemoryStore()
        val preferences = LessonsPreferences(store, vault)
        val elsewhere = LessonsPreferences(store, vault)
        preferences.addSession(session(1, "token-1"))
        preferences.addSession(session(2, "token-2"))
        assertEquals("token-2", preferences.credentials.current().classToken)
        scope().launch { preferences.followCredentials() }

        elsewhere.selectSession(1)

        withTimeout(10_000) {
            while (preferences.credentials.current().classToken != "token-1") delay(10)
        }
    }

    // -----------------------------------------------------------------------

    private fun session(classId: Long, token: String) =
        Session(classId = classId, className = "$classId«А»", school = null, token = token)

    private fun scope(): CoroutineScope = CoroutineScope(Job() + Dispatchers.IO).also { scopes += it }

    /** The `Authorization` header [path] arrived with [at] that server, or `null`. */
    private fun authorizationOn(client: OkHttpClient, path: String, at: MockWebServer = server): String? {
        // The placeholder host is what Retrofit builds against; the base-URL
        // interceptor rewrites it, exactly as for every real call.
        client.newCall(Request.Builder().url("http://base-url.invalid$path").build()).execute().close()
        // The server records a request before it answers, so by the time
        // `execute` returns it is either queued there or went somewhere else.
        val arrived = at.takeRequest(2, TimeUnit.SECONDS) ?: error("$path never reached ${at.url("/")}")
        return arrived.headers["Authorization"]
    }

    private fun answering(server: MockWebServer): MockWebServer = server.apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("{}").build()
        }
        start()
    }
}
