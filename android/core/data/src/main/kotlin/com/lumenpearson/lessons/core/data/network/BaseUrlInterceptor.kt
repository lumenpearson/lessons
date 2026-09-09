package com.lumenpearson.lessons.core.data.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

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
 */
internal class BaseUrlInterceptor(
    private val baseUrlProvider: () -> String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val configured = baseUrlProvider().trim().toHttpUrlOrNull()
            ?: return chain.proceed(request)

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
