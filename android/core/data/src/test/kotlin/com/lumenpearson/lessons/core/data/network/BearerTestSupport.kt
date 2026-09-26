package com.lumenpearson.lessons.core.data.network

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The app's own client — `NetworkModule.okHttpClient`, all three interceptors
 * in their real order — pointed at [server] through the base-URL setting, with
 * both bearers stored. What each request arrived carrying is the question.
 * [basePath] is a server behind a reverse proxy at a path of its own.
 */
internal class BearerHarness(
    private val server: MockWebServer,
    classToken: String? = CLASS_TOKEN,
    diaryToken: String? = DIARY_TOKEN,
    basePath: String = "/",
) {
    private val client = NetworkModule.okHttpClient(
        tokenProvider = { classToken },
        baseUrlProvider = { server.url(basePath).toString() },
        diaryTokenProvider = { diaryToken },
    )

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("{}").build()
        }
    }

    /** The `Authorization` header [path] arrived with, or `null`. */
    fun authorizationOn(path: String, method: String = "GET"): String? {
        val body = if (method == "GET") null else "{}".toRequestBody("application/json".toMediaType())
        // The placeholder host is what Retrofit builds against; the base-URL
        // interceptor rewrites it, exactly as for every real call.
        val request = Request.Builder().url("http://base-url.invalid$path").method(method, body).build()
        client.newCall(request).execute().close()
        return server.takeRequest().headers["Authorization"]
    }

    companion object {
        const val CLASS_TOKEN = "class-token"
        const val DIARY_TOKEN = "diary-token"
    }
}
