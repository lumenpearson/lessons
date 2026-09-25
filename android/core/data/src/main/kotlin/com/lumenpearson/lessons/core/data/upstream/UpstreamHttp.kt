package com.lumenpearson.lessons.core.data.upstream

import java.io.IOException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Scheme, host and port: what makes two URLs the same server. */
data class UpstreamOrigin(val scheme: String, val host: String, val port: Int) {
    companion object {
        fun of(url: HttpUrl): UpstreamOrigin = UpstreamOrigin(url.scheme, url.host, url.port)
    }
}

/**
 * One diary answer, read whole on OkHttp's thread so the caller never blocks
 * on a socket and never has to remember to close anything.
 */
internal class UpstreamAnswer(
    val code: Int,
    val headers: Headers,
    val url: HttpUrl,
    val body: String,
) {
    val contentType: String get() = headers["Content-Type"].orEmpty()
}

/**
 * The client the phone talks to the diaries with — and nothing else does.
 *
 * It shares nothing with the client that talks to our server, and that is the
 * point: that one rewrites every host to the configured server
 * (`BaseUrlInterceptor`) and signs requests with the class and diary bearers by
 * path (`AuthInterceptor`, `DiaryAuthInterceptor`). A diary request through it
 * would go to our server instead, or carry our bearer to somebody else's.
 * `GithubApi.client` is the same arrangement for the same reason.
 */
internal object UpstreamHttp {

    /**
     * Byte-identical to the server's `providers/diary/http.py` and
     * `petersburg/client.py`, and pinned to both by the vectors: these
     * upstreams are browsers' backends and have been known to answer
     * differently to anything that does not look like one.
     */
    const val USER_AGENT: String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    /** Larger than any sign-in answer by orders of magnitude; past it, something is wrong. */
    private const val MAX_BODY_BYTES = 2L * 1024 * 1024

    /**
     * @param allowed read per request, so the guard and the catalog it comes
     *   from are one answer rather than a copy taken at startup.
     */
    fun client(allowed: () -> Set<UpstreamOrigin>): OkHttpClient = OkHttpClient.Builder()
        // A redirect is the one way around an allow-list: a region answering
        // 302 to another host would carry the next request there. The
        // server's client refuses them for the same reason (`http.py`).
        .followRedirects(false)
        .followSslRedirects(false)
        // The session travels per request, never in a jar, so two sign-ins —
        // two families on one phone, or one family twice — cannot meet.
        .cookieJar(CookieJar.NO_COOKIES)
        // `getdata`'s salt is one-shot: a silent re-POST of the login would
        // come back 409 and read as a wrong password.
        .retryOnConnectionFailure(false)
        .cache(null)
        // A phone on a school's mobile signal. Not a parity point with the
        // server, whose datacentre link is another matter.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .addInterceptor(OriginGuard(allowed))
        .addInterceptor(BrowserHeaders)
        .build()

    /**
     * The named session cookie an answer carries, or `null`: the Kotlin twin
     * of the server's `session_cookie` / `_session_cookie`, held to them by the
     * vectors. Only a live cookie counts — a deletion (`Max-Age=0`, which
     * OkHttp parses as an empty value that expired in the past) is not a
     * session — and of several, the last in header order wins, which is what a
     * browser sending the next request would use.
     */
    fun sessionCookie(url: HttpUrl, headers: Headers, name: String, nowMillis: Long): String? =
        Cookie.parseAll(url, headers)
            .lastOrNull { it.name == name && it.value.isNotEmpty() && it.expiresAt > nowMillis }
            ?.value

    /**
     * One exchange, read whole, with every transport failure already turned
     * into the [UpstreamFailure] it means. Cancelling the caller cancels the
     * call.
     */
    suspend fun fetch(client: OkHttpClient, request: Request): UpstreamAnswer {
        val call = client.newCall(request)
        return try {
            call.await()
        } catch (refused: UpstreamNotAllowed) {
            throw refused
        } catch (failure: IOException) {
            throw classify(failure)
        }
    }

    /** What a transport failure means on a phone. */
    fun classify(failure: IOException): UpstreamFailure {
        val chain = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
        return when {
            // A region signed only by a root the phone does not trust. Nothing
            // is relaxed to get past it; the screen says so instead.
            chain.any {
                it is SSLHandshakeException ||
                    it is SSLPeerUnverifiedException ||
                    it is CertPathValidatorException
            } -> UpstreamFailure.Untrusted(failure)
            chain.any { it is UnknownHostException || it is NoRouteToHostException } ->
                UpstreamFailure.Offline(failure)
            else -> UpstreamFailure.Unavailable(failure)
        }
    }

    private suspend fun Call.await(): UpstreamAnswer = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val answer = try {
                        response.use { read(it) }
                    } catch (e: IOException) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(answer)
                }
            },
        )
    }

    private fun read(response: Response): UpstreamAnswer {
        val body = response.body
        val source = body.source()
        source.request(MAX_BODY_BYTES + 1)
        if (source.buffer.size > MAX_BODY_BYTES) {
            throw IOException("diary answer larger than $MAX_BODY_BYTES bytes")
        }
        return UpstreamAnswer(
            code = response.code,
            headers = response.headers,
            url = response.request.url,
            body = body.string(),
        )
    }
}

/**
 * Refuses any URL whose scheme, host and port are not an allow-listed origin,
 * before a socket opens. Every URL is built from the bundled catalog, so this
 * firing is a bug; it is here so that the bug is a refusal rather than a
 * request to wherever a string happened to point.
 */
internal class OriginGuard(private val allowed: () -> Set<UpstreamOrigin>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (UpstreamOrigin.of(url) !in allowed()) throw UpstreamNotAllowed(url)
        return chain.proceed(chain.request())
    }
}

/**
 * A browser's `User-Agent`, and a refusal of any `Authorization` header: no
 * bearer of ours has any business on a request to a diary, and one appearing
 * here would mean something was wired to the wrong client.
 */
internal object BrowserHeaders : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) throw UpstreamNotAllowed(request.url)
        return chain.proceed(
            request.newBuilder().header("User-Agent", UpstreamHttp.USER_AGENT).build(),
        )
    }
}
