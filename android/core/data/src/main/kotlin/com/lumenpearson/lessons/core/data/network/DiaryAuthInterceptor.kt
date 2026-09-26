package com.lumenpearson.lessons.core.data.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Puts the *diary* bearer on diary calls, and on nothing else.
 *
 * Why a second interceptor rather than reusing [AuthInterceptor]: the two
 * tokens are independent by design (see `current_diary` in
 * `server/app/api/diary.py`). A phone can be joined to a class without a diary
 * account and signed in to a diary without a class, each is minted and revoked
 * on its own, and sending the class token to `/api/v1/diary` would be a 401
 * from the server and a sign-out prompt on the wrong screen. Sharing one
 * interceptor would mean one interceptor that reads two preferences and decides
 * between them by path — which is this class, minus the name that says so.
 *
 * They cannot both act: [AuthInterceptor] skips everything under
 * [DIARY_PATH_PREFIX] and this one touches nothing else, so exactly one
 * `Authorization` header is ever written. Both hang off the same OkHttp client,
 * so there is still one connection pool, one dispatcher and one set of
 * timeouts for the whole app.
 *
 * [tokenProvider] is blocking for the same reason [AuthInterceptor]'s is:
 * interceptors cannot suspend, and they run on OkHttp's own threads.
 */
internal class DiaryAuthInterceptor(
    private val tokenProvider: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath

        // `/login` and `/session` are the calls that mint the token, so they
        // cannot carry one: a stale bearer riding the request that is meant to
        // replace it is a dead session sent along for nothing, and a family
        // signing in to a second account would hand the server the first
        // one's. `/capabilities` is asked before anybody is signed in and says
        // the same thing to everyone. All three are anonymous on the server.
        val mine = path.contains(DIARY_PATH_PREFIX) &&
            ANONYMOUS_SUFFIXES.none { path.endsWith(it) }
        if (!mine || request.header(HEADER) != null) {
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

    internal companion object {
        const val HEADER = "Authorization"

        /**
         * Matched with `contains` rather than `startsWith`: the base URL may
         * carry a path prefix of its own when the server sits behind a reverse
         * proxy, exactly as the relative paths in [DiaryApi] allow for.
         */
        const val DIARY_PATH_PREFIX = "/api/v1/diary"

        /**
         * `/login` stays in the list although this build never calls it: the
         * rule is about what the server treats as anonymous, and a list that
         * matched the app's calls rather than the server's routes would be
         * wrong again the day somebody calls it for a test.
         */
        val ANONYMOUS_SUFFIXES: List<String> =
            listOf("/diary/login", "/diary/session", "/diary/capabilities")
    }
}
