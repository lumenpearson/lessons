package com.lumenpearson.lessons.core.data.network

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * The server address is missing, or is not an address — or, as
 * [ServerNeedsHttpsException], is one this build will not use.
 *
 * A dedicated type rather than a plain [IOException] so the layers above can
 * tell "you have not set this up yet" apart from "the network is down" and say
 * something the user can act on.
 */
open class ServerAddressMissingException(message: String = "No server address configured") : IOException(message)

/**
 * The server address is plain `http://` to a host this build refuses cleartext
 * to — every host but the phone's own, in a release build (#202).
 *
 * A kind of [ServerAddressMissingException] because the cure is the same
 * screen: every place that already answers «no address» with «set the server»
 * answers this with it too, and none of them retries it on a timer. The places
 * that can say *why* catch it first — a sync, the server badge, a diary
 * sign-in — so the sentence is about https rather than about an address that
 * is plainly there.
 *
 * Thrown before the request leaves, so nothing — not the class bearer, not the
 * diary's — is ever written to the socket, and the user is not shown the
 * platform's «CLEARTEXT communication … not permitted», which names a policy
 * nobody has heard of.
 */
class ServerNeedsHttpsException(host: String) :
    ServerAddressMissingException("Plain http:// to $host is refused by this build; the server needs https://")

/**
 * Points every request at the server address currently stored in settings.
 *
 * Why an interceptor and not a rebuilt Retrofit: the base URL is user-editable
 * (every school self-hosts its own instance), so it can change at any moment.
 * Rebuilding Retrofit + OkHttp on change would mean invalidating a shared
 * singleton from a settings flow, racing with in-flight calls and leaking
 * connection pools. Rewriting the URL per call keeps one client for the process
 * lifetime and makes the next call - not some observer - pick up the change.
 *
 * Retrofit still needs a syntactically valid base URL at build time; it is a
 * `.invalid` host, so a rewrite that ever fails to happen dies at DNS instead of
 * quietly talking to a real server.
 *
 * @param cleartext whether this build may send plain `http://` to a host; see
 *   [CleartextPolicy].
 */
internal class BaseUrlInterceptor(
    private val baseUrlProvider: () -> String,
    private val cleartext: CleartextPolicy,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Failing here rather than proceeding is the whole point. Letting an
        // unusable address through sends the call to the `.invalid` placeholder
        // Retrofit was built with, and the user is shown a DNS error naming a
        // host they have never heard of — or, from a background sync, nothing
        // at all. The two causes are one exception because the cure is the same
        // screen either way.
        val configured = baseUrlProvider().trim().toHttpUrlOrNull()
            ?: throw ServerAddressMissingException()

        // Here, before `proceed`, rather than left to OkHttp, which refuses the
        // same address a step later with an exception the layers above could
        // only tell from «no network» by its message.
        if (!configured.isHttps && !cleartext.permits(configured.host)) {
            throw ServerNeedsHttpsException(configured.host)
        }

        // A configured path such as /lessons/ prefixes the endpoint path, which
        // is what makes hosting behind a reverse proxy subdirectory work.
        val prefix = configured.encodedPath.trimEnd('/')
        val rewritten = request.url.newBuilder()
            .scheme(configured.scheme)
            .host(configured.host)
            .port(configured.port)
            .apply { if (prefix.isNotEmpty()) encodedPath(prefix + request.url.encodedPath) }
            .build()

        return chain.proceed(request.newBuilder().url(rewritten).build())
    }
}
