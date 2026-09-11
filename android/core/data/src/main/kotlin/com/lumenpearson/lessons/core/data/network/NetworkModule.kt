package com.lumenpearson.lessons.core.data.network

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Builds the networking stack by hand, in one place.
 *
 * There is no DI framework in this app (see `di.Graph` for why), so this object
 * is the factory: [DefaultLessonsContainer][com.lumenpearson.lessons.core.data.di.DefaultLessonsContainer]
 * calls [lessonsApi] once and everything else takes the resulting [LessonsApi].
 */
internal object NetworkModule {

    /**
     * Placeholder only. [BaseUrlInterceptor] replaces scheme, host and port on
     * every call; the `.invalid` TLD is reserved by RFC 2606 and can never
     * resolve, so a missing rewrite fails loudly instead of silently.
     */
    private const val PLACEHOLDER_BASE_URL = "http://base-url.invalid/"

    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

    /**
     * Tolerant by design: the server may add fields at any time, and an app that
     * stops syncing because it saw an unknown key is worse than one that ignores
     * it. `coerceInputValues` turns an explicit `null` in a non-null field into
     * the declared default rather than an exception.
     */
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * @param tokenProvider blocking read of the stored bearer token, or `null`
     * before the device has joined a class.
     * @param baseUrlProvider blocking read of the configured server address.
     *
     * Timeouts are short enough that the widget's sync worker cannot hang on a
     * dead school server for minutes, and long enough for a slow mobile network
     * to deliver two weeks of timetable.
     */
    fun okHttpClient(
        tokenProvider: () -> String?,
        baseUrlProvider: () -> String,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // Order matters: the URL is rewritten first so the auth interceptor sees
        // the real path when it decides whether the call needs a token.
        .addInterceptor(BaseUrlInterceptor(baseUrlProvider))
        .addInterceptor(AuthInterceptor(tokenProvider))
        .build()

    /** Retrofit configured for kotlinx.serialization; see [PLACEHOLDER_BASE_URL]. */
    // fallback: converter-kotlinx-serialization also exposes a no-argument
    // asConverterFactory() overload; the explicit media type is the form that
    // has existed since the converter was donated to Square, so it is used here.
    fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
        .build()

    /** The one call sites use. */
    fun lessonsApi(
        tokenProvider: () -> String?,
        baseUrlProvider: () -> String,
    ): LessonsApi = retrofit(
        client = okHttpClient(tokenProvider, baseUrlProvider),
        json = json(),
    ).create(LessonsApi::class.java)
}
