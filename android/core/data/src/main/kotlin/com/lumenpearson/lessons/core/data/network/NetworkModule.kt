package com.lumenpearson.lessons.core.data.network

import com.lumenpearson.lessons.core.data.diagnostics.NetworkLog
import com.lumenpearson.lessons.core.data.diagnostics.NetworkSource
import com.lumenpearson.lessons.core.data.network.v2.BearerInterceptor
import com.lumenpearson.lessons.core.data.network.v2.ClientVersionInterceptor
import com.lumenpearson.lessons.core.data.network.v2.RecordCallStatusInterceptor
import com.lumenpearson.lessons.core.data.network.v2.AppTransport
import com.lumenpearson.lessons.core.data.network.v2.JoinRemote
import com.lumenpearson.lessons.core.data.network.v2.ManageRemote
import com.lumenpearson.lessons.core.data.network.v2.MeRemote
import com.lumenpearson.lessons.core.data.network.v2.DiaryRemote
import com.lumenpearson.lessons.core.data.network.v2.DirectoryRemote
import com.lumenpearson.lessons.core.data.network.v2.ScheduleRemote
import com.lumenpearson.lessons.core.data.network.v2.V2Remotes
import java.time.Clock
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
 * calls [apis] once. Feature repositories receive the selected domain-valued v2
 * remotes; [LessonsApi] retains the plain HTTP health and warmup calls.
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
     * @param credentials the address and both bearers, captured together once
     * at send time, on OkHttp's own thread. It must answer from
     * memory — the container hands in `CredentialsSnapshot.current` — because a
     * read of the store here is a parked thread on every call the app makes.
     *
     * Timeouts are short enough that the widget's sync worker cannot hang on a
     * dead school server for minutes. Whether they are long enough is the half
     * nobody has re-measured: they were chosen for a window of two weeks, and
     * the window is now a whole school year — some 274 days of lessons, events
     * and homework against a 60-second `callTimeout`. The `ETag` makes almost
     * every call a `304`, so what is at stake is the first sync after a join
     * and the one step into another year, on a school's mobile signal. Measure
     * before moving any of these: a number raised on a guess is one nobody can
     * lower again.
     *
     * @param cleartext whether a plain `http://` address may be used, asked
     *   before a request leaves; the container hands in the platform's answer.
     */
    fun okHttpClient(
        credentials: () -> RequestCredentials,
        cleartext: CleartextPolicy,
        clientVersion: Int = 1,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // Order matters: the URL is rewritten first so the auth interceptors see
        // the real path when they decide whether the call is theirs to sign.
        .addInterceptor(BaseUrlInterceptor(credentials, cleartext))
        // The developer mode's recorder, after the rewrite so it records the
        // server the request really went to, and before the bearers, which it
        // would not read anyway. Off, it is one volatile read (#237).
        .addInterceptor(NetworkLog.interceptor(NetworkSource.SERVER))
        // Two bearers, one client. The class token and the diary token are
        // independent — either can exist without the other, and signing out of
        // one must not disturb the other — so each has an interceptor that
        // knows which paths belong to it: [AuthInterceptor] skips
        // `/api/v1/diary`, [DiaryAuthInterceptor] touches only it. One client
        // rather than two because they talk to the same host and there is no
        // reason to pay for a second connection pool and dispatcher.
        .addInterceptor(AuthInterceptor { credentials().classToken })
        .addInterceptor(DiaryAuthInterceptor { credentials().diaryToken })
        .addInterceptor(BearerInterceptor(credentials))
        .addInterceptor(ClientVersionInterceptor(clientVersion))
        .addInterceptor(RecordCallStatusInterceptor())
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
     * One server client for retained plain-HTTP metadata and the selected v2 remotes.
     *
     * The client owns the pool, timeouts and coherent address/credential snapshots;
     * all service proxies and RPC calls share those resources.
     */
    fun apis(credentials: () -> RequestCredentials, cleartext: CleartextPolicy,
        clientVersion: Int, transport: String): Apis {
        val client = okHttpClient(credentials, cleartext, clientVersion)
        val retrofit = retrofit(
            client = client,
            json = json(),
        )
        val remotes = V2Remotes.using(client, retrofit, AppTransport.parse(transport), Clock.systemUTC())
        return Apis(
            serverStatus = RestServerStatusRemote(retrofit.create(ServerStatusApi::class.java)::warmup),
            diary = remotes.diary,
            manage = remotes.manage,
            directory = remotes.directory,
            join = remotes.join,
            schedule = remotes.schedule,
            me = remotes.me,
        )
    }

    /**
     * @property manage the v2 management surface, signed by the class token with
     *   its role checked by the server on every request.
     * @property directory the anonymous school-region lookup. Per-method v2
     *   credential metadata keeps it separate from the authenticated school search.
     *
     * @see apis
     */
    data class Apis(
        val serverStatus: ServerStatusRemote,
        val diary: DiaryRemote,
        val manage: ManageRemote,
        val directory: DirectoryRemote,
        val join: JoinRemote,
        val schedule: ScheduleRemote,
        val me: MeRemote,
    )
}
