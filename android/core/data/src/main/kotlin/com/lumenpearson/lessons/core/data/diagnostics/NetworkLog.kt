package com.lumenpearson.lessons.core.data.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Which of the app's three clients a request went out through. */
enum class NetworkSource {
    /** Our server, through `NetworkModule`'s client. */
    SERVER,

    /** A diary's own server, through `UpstreamHttp`'s client. */
    DIARY,

    /** GitHub: the sign-in, the issues, the guide and the update check. */
    GITHUB,
}

/**
 * One request as the developer page shows it; see [NetworkRedaction] for what
 * is kept of it and why.
 *
 * @property tookMillis `null` while the request is in flight — which is what
 *   the page draws as a running clock, and the answer to #236's question of
 *   which leg a sign-in that shows nothing is waiting on.
 * @property failure the exception's class and first words, when there was no answer.
 */
data class NetworkEntry(
    val id: Long,
    val source: NetworkSource,
    val method: String,
    val host: String,
    val path: String,
    val startedAtMillis: Long,
    val tookMillis: Long? = null,
    val status: Int? = null,
    val failure: String? = null,
    val bytes: Long? = null,
    val headers: List<Pair<String, String>> = emptyList(),
) {
    val inFlight: Boolean get() = tookMillis == null
}

/**
 * The developer mode's record of every request the app makes (#237).
 *
 * A process-wide object, like `CrashReporter`, because the three clients are
 * built in three places — one of them an object of its own — and all three
 * have to write to the same list. Off, the interceptor is one volatile read
 * and a straight `proceed`; nothing is kept and nothing is allocated.
 *
 * What the mode turns off it empties: losing the GitHub permission, or
 * switching the tool off, takes the record with it.
 */
object NetworkLog {

    /** Enough for a sign-in, a sync and a minute of settings, small enough to copy whole. */
    private const val MaxEntries = 200

    @Volatile
    private var recording = false

    private val lock = Any()
    private val buffer = ArrayDeque<NetworkEntry>()
    private var nextId = 0L

    private val mutable = MutableStateFlow<List<NetworkEntry>>(emptyList())

    /** Oldest first, in flight included. */
    val entries: StateFlow<List<NetworkEntry>> = mutable.asStateFlow()

    val isRecording: Boolean get() = recording

    /** Turns recording on or off; off also forgets what was recorded. */
    fun setRecording(on: Boolean) {
        recording = on
        if (!on) clear()
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            mutable.value = emptyList()
        }
    }

    /** The recorder for one client; see [NetworkSource] for which client is which. */
    fun interceptor(source: NetworkSource): Interceptor = Recorder(source)

    internal fun begin(source: NetworkSource, request: Request, startedAtMillis: Long): Long =
        synchronized(lock) {
            val id = nextId++
            buffer.addLast(
                NetworkEntry(
                    id = id,
                    source = source,
                    method = request.method,
                    host = request.url.host,
                    path = NetworkRedaction.path(request.url),
                    startedAtMillis = startedAtMillis,
                ),
            )
            while (buffer.size > MaxEntries) buffer.removeFirst()
            mutable.value = buffer.toList()
            id
        }

    /** Fills in entry [id]; a no-op when it was cleared or pushed out meanwhile. */
    internal fun end(id: Long, transform: (NetworkEntry) -> NetworkEntry) {
        synchronized(lock) {
            val index = buffer.indexOfFirst { it.id == id }
            if (index < 0) return
            buffer[index] = transform(buffer[index])
            mutable.value = buffer.toList()
        }
    }

    /**
     * Placed after the client's address rewrite, so it records where the
     * request really went, and before anything that signs it, which it never
     * reads anyway. Every failure is recorded and then rethrown untouched: the
     * recorder must not change what the caller sees.
     *
     * Internal rather than private only so a test can name it in the list of
     * a client's interceptors.
     */
    internal class Recorder(private val source: NetworkSource) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!recording) return chain.proceed(request)
            val started = System.nanoTime()
            val id = begin(source, request, System.currentTimeMillis())
            val outcome = runCatching { chain.proceed(request) }
            val took = (System.nanoTime() - started) / NanosPerMilli
            outcome
                .onSuccess { response ->
                    end(id) {
                        it.copy(
                            tookMillis = took,
                            status = response.code,
                            bytes = response.body.contentLength().takeIf { size -> size >= 0 },
                            headers = NetworkRedaction.headers(response.headers),
                        )
                    }
                }
                .onFailure { error ->
                    end(id) { it.copy(tookMillis = took, failure = NetworkRedaction.failure(error)) }
                }
            return outcome.getOrThrow()
        }
    }

    private const val NanosPerMilli = 1_000_000L
}
