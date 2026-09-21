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
 *
 * It signs the class timetable's calls only; see [DiaryAuthInterceptor] for the
 * diary's own bearer and for why the two are not one.
 */
internal class AuthInterceptor(
    private val tokenProvider: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // /join and /health are the two calls that must work before a token
        // exists; sending a stale token to them would only invite a 401.
        //
        // The diary is excluded outright: it is a separate account with a
        // separate bearer, minted and revoked independently of this one, and
        // [DiaryAuthInterceptor] is what signs those calls. The class token on
        // a diary request would be answered with a 401 that means nothing the
        // user could act on.
        val path = request.url.encodedPath
        val needsAuth = UNAUTHENTICATED_SUFFIXES.none { path.endsWith(it) } &&
            !path.contains(DiaryAuthInterceptor.DIARY_PATH_PREFIX)
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
        // `/warmup` joins the two: it is the about page's server badge, and it
        // is asked before this device has joined anything at all.
        val UNAUTHENTICATED_SUFFIXES = listOf("/join", "/health", "/warmup")
    }
}
