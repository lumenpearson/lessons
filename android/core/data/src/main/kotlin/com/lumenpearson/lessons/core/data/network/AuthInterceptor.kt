package com.lumenpearson.lessons.core.data.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <token>` to every authenticated call.
 *
 * The token is read through a provider rather than captured once, because it
 * only exists after the user joins a class and disappears again on sign-out -
 * the OkHttp client outlives both events.
 *
 * [tokenProvider] is blocking on purpose: OkHttp interceptors cannot suspend,
 * and they run on OkHttp's own dispatcher threads, never on the main thread.
 */
internal class AuthInterceptor(
    private val tokenProvider: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // /join and /health are the two calls that must work before a token
        // exists; sending a stale token to them would only invite a 401.
        val path = request.url.encodedPath
        val needsAuth = UNAUTHENTICATED_SUFFIXES.none { path.endsWith(it) }
        if (!needsAuth || request.header(HEADER) != null) {
            return chain.proceed(request)
        }

        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header(HEADER, "Bearer $token")
                .build(),
        )
    }

    private companion object {
        const val HEADER = "Authorization"
        val UNAUTHENTICATED_SUFFIXES = listOf("/join", "/health")
    }
}
