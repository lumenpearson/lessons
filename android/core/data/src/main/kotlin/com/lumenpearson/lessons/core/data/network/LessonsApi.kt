package com.lumenpearson.lessons.core.data.network

import com.lumenpearson.lessons.core.data.network.dto.BundleDto
import com.lumenpearson.lessons.core.data.network.dto.DeviceMeDto
import com.lumenpearson.lessons.core.data.network.dto.HealthDto
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import com.lumenpearson.lessons.core.data.network.dto.JoinResponseDto
import com.lumenpearson.lessons.core.data.network.dto.UnlinkResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The entire server surface the app uses. Read-only apart from the call that
 * mints a device token and the one that unties it from a Telegram account.
 *
 * Paths are relative (no leading slash) so that a base URL with a path prefix -
 * `https://school.example/lessons/` behind a reverse proxy - keeps working.
 *
 * No `Authorization` parameter appears here: [AuthInterceptor] adds the header,
 * which keeps the token out of every call site and out of stack traces.
 */
internal interface LessonsApi {

    /**
     * Exchanges a join code for a long-lived device token.
     *
     * Fails with HTTP 404 when the code is unknown - the join screen shows that
     * as "wrong code" rather than as a network problem.
     */
    @POST("api/v1/join")
    suspend fun join(@Body body: JoinRequestDto): JoinResponseDto

    /**
     * One round trip that fills the cache for [days] days from [start].
     *
     * @param start first day, ISO `YYYY-MM-DD`, always sent explicitly so the
     * window does not depend on the server's idea of "today".
     * @param days server caps this at 280 — a whole school year, which is what
     * the calendar draws and therefore what the repository asks for.
     * @param ifNoneMatch the tag of the answer this device already holds. The
     * server compares it and answers `304` with no body when nothing in the
     * window has changed, which is most polls — hence `Response`, because that
     * status is the useful half of this call and a body-typed method would
     * throw on it.
     */
    @GET("api/v1/bundle")
    suspend fun bundle(
        @Query("start") start: String,
        @Query("days") days: Int,
        @Header("If-None-Match") ifNoneMatch: String? = null,
    ): Response<BundleDto>

    /**
     * Unauthenticated reachability probe, so "your address is wrong" and "your
     * code is wrong" can be reported as different mistakes.
     */
    @GET("api/v1/health")
    suspend fun health(): HealthDto

    /**
     * What the server knows about this device: whether it is tied to a
     * Telegram account, the role that account holds in the class, and — while
     * it is not tied — the code that ties it.
     *
     * Asking is what mints the code: the server issues one on the first call
     * and repeats it on every later one until it is used, so this is safe to
     * call every time the settings page opens.
     */
    @GET("api/v1/me")
    suspend fun me(): DeviceMeDto

    /**
     * Unties this device from its Telegram account. The token stays valid and
     * read-only; the next [me] call hands out a fresh code.
     */
    @POST("api/v1/me/unlink")
    suspend fun unlink(): UnlinkResponseDto
}
