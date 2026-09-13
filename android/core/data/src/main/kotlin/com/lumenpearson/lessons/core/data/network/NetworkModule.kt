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
 * calls [apis] once and everything else takes the resulting [LessonsApi],
 * [DiaryApi] and [ManageApi].
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
     * @param tokenProvider blocking read of the stored class bearer, or `null`
     * before the device has joined a class.
     * @param baseUrlProvider blocking read of the configured server address.
     * @param diaryTokenProvider blocking read of the stored diary bearer, or
     * `null` while nobody is signed in to a diary. A different token from
     * [tokenProvider] and never a substitute for it.
     *
     * Timeouts are short enough that the widget's sync worker cannot hang on a
     * dead school server for minutes, and long enough for a slow mobile network
     * to deliver two weeks of timetable.
     */
    fun okHttpClient(
        tokenProvider: () -> String?,
        baseUrlProvider: () -> String,
        diaryTokenProvider: () -> String? = { null },
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // Order matters: the URL is rewritten first so the auth interceptors see
        // the real path when they decide whether the call is theirs to sign.
        .addInterceptor(BaseUrlInterceptor(baseUrlProvider))
        // Two bearers, one client. The class token and the diary token are
        // independent — either can exist without the other, and signing out of
        // one must not disturb the other — so each has an interceptor that
        // knows which paths belong to it: [AuthInterceptor] skips
        // `/api/v1/diary`, [DiaryAuthInterceptor] touches only it. One client
        // rather than two because they talk to the same host and there is no
        // reason to pay for a second connection pool and dispatcher.
        .addInterceptor(AuthInterceptor(tokenProvider))
        .addInterceptor(DiaryAuthInterceptor(diaryTokenProvider))
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

    /**
     * The stack every call site shares: one client, one Retrofit, three APIs.
     *
     * Built as one object rather than through three factories because the
     * client is the expensive part and all three interfaces want the same one —
     * the same pool, the same timeouts and the same base-URL rewrite.
     */
    fun apis(
        tokenProvider: () -> String?,
        baseUrlProvider: () -> String,
        diaryTokenProvider: () -> String?,
    ): Apis {
        val retrofit = retrofit(
            client = okHttpClient(tokenProvider, baseUrlProvider, diaryTokenProvider),
            json = json(),
        )
        return Apis(
            lessons = retrofit.create(LessonsApi::class.java),
            diary = retrofit.create(DiaryApi::class.java),
            manage = retrofit.create(ManageApi::class.java),
        )
    }

    /**
     * @property manage the management surface. It carries no bearer of its own:
     *   `/api/v1/manage` is signed by the ordinary class token like the rest of
     *   [LessonsApi], and the role behind that token is looked up per request on
     *   the server.
     *
     * @see apis
     */
    data class Apis(val lessons: LessonsApi, val diary: DiaryApi, val manage: ManageApi)
}
