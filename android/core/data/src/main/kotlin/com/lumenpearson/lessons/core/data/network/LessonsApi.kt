package com.lumenpearson.lessons.core.data.network

import com.lumenpearson.lessons.core.data.network.dto.BundleDto
import com.lumenpearson.lessons.core.data.network.dto.HealthDto
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import com.lumenpearson.lessons.core.data.network.dto.JoinResponseDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The entire server surface the app uses. Three calls, all read-only apart from
 * the one that mints a device token.
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
     * @param days server caps this at 31.
     */
    @GET("api/v1/bundle")
    suspend fun bundle(
        @Query("start") start: String,
        @Query("days") days: Int,
    ): BundleDto

    /**
     * Unauthenticated reachability probe, so "your address is wrong" and "your
     * code is wrong" can be reported as different mistakes.
     */
    @GET("api/v1/health")
    suspend fun health(): HealthDto
}
