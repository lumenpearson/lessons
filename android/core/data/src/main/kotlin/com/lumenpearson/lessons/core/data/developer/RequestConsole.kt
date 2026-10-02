package com.lumenpearson.lessons.core.data.developer

import com.lumenpearson.lessons.core.data.diagnostics.NetworkLog
import com.lumenpearson.lessons.core.data.diagnostics.NetworkSource
import com.lumenpearson.lessons.core.data.upstream.OriginGuard
import com.lumenpearson.lessons.core.data.upstream.UpstreamOrigin
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * What came back: the status line, every response header, and the body as
 * text — cut at [ConsoleShownBytes], pretty-printed when it is JSON.
 *
 * Shown on the developer page and nowhere else: the report leaves it out,
 * because an answer signed with this phone's bearer is the class's data.
 */
data class ConsoleAnswer(
    val url: String,
    val status: Int,
    val message: String,
    val tookMillis: Long,
    val headers: List<Pair<String, String>>,
    val body: String,
    val truncated: Boolean,
)

sealed interface ConsoleOutcome {
    data class Refused(val reason: ConsoleRefusal, val detail: String? = null) : ConsoleOutcome
    data class Answered(val answer: ConsoleAnswer) : ConsoleOutcome

    /** No answer: [description] is the failure's class and its cause's, as the checks word it. */
    data class Failed(val description: String, val tookMillis: Long) : ConsoleOutcome
}

/** How much of a body the page shows: enough for any answer of ours, small enough to scroll. */
const val ConsoleShownBytes: Long = 64L * 1024

/** The bearers, read when a request is sent. Kept out of every data class above. */
internal data class ConsoleCredentials(
    val serverAddress: String?,
    val deviceToken: String?,
    val diaryToken: String?,
) {
    override fun toString(): String = "ConsoleCredentials(serverAddress=$serverAddress, <redacted>)"
}

/**
 * The developer page's request console (#237): any method, path, headers and
 * body, to our server or to a diary the catalog allow-lists — and nowhere else.
 *
 * Its own client, because neither of the app's will do. The server's rewrites
 * every host to the configured address and signs by path, so a request through
 * it could never be sent unsigned or to a diary; the diaries' sends the
 * browser's headers and keeps no answer past 2 MiB. This one keeps no cookie,
 * follows no redirect — a 302 is an answer the developer wants to see, and
 * following it is how a request ends up somewhere the planner never approved —
 * and refuses, through the same [OriginGuard] the diaries' client has, every
 * origin but our server's and the catalog's. That guard is the second line:
 * the planner has already refused anything else.
 *
 * A bearer goes only to our server, only when chosen, and only on the request
 * being sent; the network record shows the request like any other, which means
 * without its headers.
 */
class RequestConsole internal constructor(
    private val credentials: () -> ConsoleCredentials,
    private val diaryOrigins: () -> Set<UpstreamOrigin>,
    private val cleartextPermitted: (String) -> Boolean,
    clientOverride: OkHttpClient? = null,
) {

    private val client: OkHttpClient by lazy { clientOverride ?: buildClient() }

    /** The diaries the console may reach, as origins to pick from, sorted by host. */
    fun origins(): List<String> = diaryOrigins()
        .sortedWith(compareBy({ it.host }, { it.port }))
        .map { origin ->
            val defaultPort = (origin.scheme == "https" && origin.port == HttpsPort) ||
                (origin.scheme == "http" && origin.port == HttpPort)
            if (defaultPort) "${origin.scheme}://${origin.host}" else "${origin.scheme}://${origin.host}:${origin.port}"
        }

    fun plan(draft: ConsoleDraft): ConsolePlan = planConsole(draft, context(credentials()))

    /** On the IO dispatcher: the first read of the credentials may wait on the preferences file. */
    suspend fun send(draft: ConsoleDraft): ConsoleOutcome = withContext(Dispatchers.IO) { sendNow(draft) }

    private suspend fun sendNow(draft: ConsoleDraft): ConsoleOutcome {
        val held = credentials()
        val plan = planConsole(draft, context(held))
        if (plan is ConsolePlan.Refused) return ConsoleOutcome.Refused(plan.reason, plan.detail)
        val ready = plan as ConsolePlan.Ready
        val request = request(ready, held)
        val started = System.nanoTime()
        return try {
            ConsoleOutcome.Answered(client.newCall(request).await { tookSince(started) })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: IOException) {
            ConsoleOutcome.Failed(describe(failure), tookSince(started))
        }
    }

    private fun context(held: ConsoleCredentials) = ConsoleContext(
        serverAddress = held.serverAddress,
        serverCleartextPermitted = cleartextPermitted,
        diaryOrigins = diaryOrigins(),
        hasDeviceToken = !held.deviceToken.isNullOrBlank(),
        hasDiaryToken = !held.diaryToken.isNullOrBlank(),
    )

    private fun request(plan: ConsolePlan.Ready, held: ConsoleCredentials): Request {
        val contentType = plan.headers.lastOrNull { (name, _) -> name.equals("Content-Type", true) }?.second
            ?: DefaultContentType
        val body = plan.body?.toRequestBody(contentType.toMediaTypeOrNull())
            ?: if (plan.method.carriesBody) ByteArray(0).toRequestBody(null) else null
        return Request.Builder()
            .url(plan.url)
            .apply { plan.headers.forEach { (name, value) -> addHeader(name, value) } }
            .apply {
                val bearer = when (plan.auth) {
                    ConsoleAuth.NONE -> null
                    ConsoleAuth.DEVICE -> held.deviceToken
                    ConsoleAuth.DIARY -> held.diaryToken
                }
                if (bearer != null) header("Authorization", "Bearer $bearer")
            }
            .method(plan.method.name, body)
            .build()
    }

    /** The guard's list: the catalog's diaries, and our server as the settings name it now. */
    private fun allowed(): Set<UpstreamOrigin> {
        val server = credentials().serverAddress?.trim()?.toHttpUrlOrNull()?.let(UpstreamOrigin::of)
        return diaryOrigins() + setOfNotNull(server)
    }

    private fun buildClient(): OkHttpClient {
        // Read per request, as `BaseUrlInterceptor` reads it, so an address
        // changed in the settings is the one the guard lets through.
        val guard = OriginGuard(::allowed)
        return OkHttpClient.Builder()
            .connectTimeout(TimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(TimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(CallTimeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(guard)
            .addInterceptor(NetworkLog.interceptor(NetworkSource.CONSOLE))
            .build()
    }

    private companion object {
        const val DefaultContentType = "application/json; charset=utf-8"
        const val TimeoutSeconds = 20L
        const val CallTimeoutSeconds = 30L
        const val HttpsPort = 443
        const val HttpPort = 80
    }
}

private const val NanosPerMilli = 1_000_000L

private fun tookSince(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / NanosPerMilli

private suspend fun Call.await(took: () -> Long): ConsoleAnswer = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val answer = try {
                    response.use { readAnswer(it, took()) }
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                    return
                }
                continuation.resume(answer)
            }
        },
    )
}

private fun readAnswer(response: Response, tookMillis: Long): ConsoleAnswer {
    val source = response.body.source()
    source.request(ConsoleShownBytes + 1)
    val truncated = source.buffer.size > ConsoleShownBytes
    val bytes = source.buffer.snapshot().let { all ->
        if (truncated) all.substring(0, ConsoleShownBytes.toInt()) else all
    }
    val type = response.header("Content-Type").orEmpty()
    return ConsoleAnswer(
        url = response.request.url.toString(),
        status = response.code,
        message = response.message,
        tookMillis = tookMillis,
        headers = response.headers.map { (name, value) -> name to value },
        body = shownBody(bytes.utf8(), type, truncated),
        truncated = truncated,
    )
}

private val Pretty = Json { prettyPrint = true }

/** JSON indented when it parses whole; anything else as it came. */
internal fun shownBody(text: String, contentType: String, truncated: Boolean): String {
    if (truncated || !contentType.contains("json", ignoreCase = true)) return text
    return runCatching { Pretty.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text)) }
        .getOrDefault(text)
}
