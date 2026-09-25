package com.lumenpearson.lessons.core.data.network

import com.lumenpearson.lessons.core.data.network.dto.DiaryAttendanceDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryHomeworkDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryLessonDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryCapabilitiesDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryMarkDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryOverrideDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryOverrideRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryPeriodDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryResetRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiarySessionRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiarySessionResponseDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryStudentDto
import com.lumenpearson.lessons.core.data.network.dto.DiarySubjectDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryTeacherDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The diary, as this server publishes it: `/api/v1/diary`, for Petersburg and
 * «Сетевой город» alike.
 *
 * Its own interface rather than more methods on [LessonsApi] because it is its
 * own thing — one family's account in a foreign service, not the class
 * timetable the bot fills — and because it carries its own bearer. A single
 * interface would have been a single token by accident: see
 * [DiaryAuthInterceptor] for how the two are kept apart.
 *
 * Paths are relative, as in [LessonsApi], so a base URL with a path prefix
 * behind a reverse proxy keeps working.
 *
 * Every call but [capabilities] and [registerSession] answers `401` when the
 * session is gone, and `401` carrying `X-Diary-Reauth: required` when it is the
 * *upstream* session that died and the password has to be asked for again.
 * Nothing here decides which: `DiaryFailure.of` reads the response and the
 * screen reacts to the type.
 *
 * `POST /login`, which took the password itself, is deliberately absent. The
 * endpoint is still served for older builds; this one signs in with the diary
 * directly (`upstream/`) and hands over only the session it was given, so the
 * password never reaches our server. Leaving the method out is what makes that
 * a fact of the build rather than a promise about its callers.
 */
internal interface DiaryApi {

    /**
     * What this server's diary can do: whether it runs at all, and which
     * «Сетевой город» regions it keeps sessions for. Anonymous and cheap,
     * asked before a password field is enabled. `404` from a server that
     * predates registration.
     */
    @GET("api/v1/diary/capabilities")
    suspend fun capabilities(): DiaryCapabilitiesDto

    /**
     * Hands over a session the phone opened with the diary, for one of ours.
     *
     * Anonymous — see `DiaryAuthInterceptor`, which keeps a stale bearer off
     * it — and throttled together with `/login`. `409` means the diary would
     * not take the session from our server's address, which is not the
     * password and not worth an automatic retry; `403` means the account has no
     * pupil; `422` means the body was refused before anything was asked
     * upstream, which only a bug in this app produces.
     */
    @POST("api/v1/diary/session")
    suspend fun registerSession(@Body body: DiarySessionRequestDto): DiarySessionResponseDto

    /** Forgets the session on the server; answers 204 with no body. */
    @POST("api/v1/diary/logout")
    suspend fun logout()

    /** Every pupil this account may see. One for most parents. */
    @GET("api/v1/diary/students")
    suspend fun students(): List<DiaryStudentDto>

    /**
     * @param from first day, ISO `YYYY-MM-DD`.
     * @param to last day; the server refuses a range wider than 62 days with
     *   `422`, which is why the week view asks for a week at a time.
     */
    @GET("api/v1/diary/students/{id}/schedule")
    suspend fun schedule(
        @Path("id") studentId: Long,
        @Query("from") from: String,
        @Query("to") to: String,
    ): List<DiaryLessonDto>

    /** The homework of the same window, which upstream is a field of a lesson. */
    @GET("api/v1/diary/students/{id}/homework")
    suspend fun homework(
        @Path("id") studentId: Long,
        @Query("from") from: String,
        @Query("to") to: String,
    ): List<DiaryHomeworkDto>

    /** Marks, absences, lateness and remarks in one list; `kind` tells them apart. */
    @GET("api/v1/diary/students/{id}/grades")
    suspend fun grades(
        @Path("id") studentId: Long,
        @Query("from") from: String,
        @Query("to") to: String,
    ): List<DiaryMarkDto>

    /** Quarters or trimesters; the one with `is_current` is the one to show. */
    @GET("api/v1/diary/students/{id}/periods")
    suspend fun periods(@Path("id") studentId: Long): List<DiaryPeriodDto>

    /** Subjects studied in a period; the current one when [periodId] is null. */
    @GET("api/v1/diary/students/{id}/subjects")
    suspend fun subjects(
        @Path("id") studentId: Long,
        @Query("period_id") periodId: Long? = null,
    ): List<DiarySubjectDto>

    /** The teachers of this pupil's class. */
    @GET("api/v1/diary/students/{id}/teachers")
    suspend fun teachers(@Path("id") studentId: Long): List<DiaryTeacherDto>

    /** Turnstile records, newest first. */
    @GET("api/v1/diary/students/{id}/attendance")
    suspend fun attendance(@Path("id") studentId: Long): List<DiaryAttendanceDto>

    /**
     * Every correction this account has stored for this child.
     *
     * The only calls in this interface that write anything, and they write
     * nothing upstream: a correction is laid over the diary's answer on the way
     * out and never sent to the school. Marks and attendance are not in the
     * field set, deliberately — see `services/diary_overrides` on why an app
     * that let a family rewrite a grade would produce a false record that looks
     * official.
     */
    @GET("api/v1/diary/students/{id}/overrides")
    suspend fun overrides(@Path("id") studentId: Long): List<DiaryOverrideDto>

    /**
     * Writes or replaces one correction; the answer is the stored row.
     *
     * `422` here does not mean what it means on [schedule]: it is the server
     * refusing a target it would never have produced or a field that is not
     * correctable, not a date range. `DiaryFailure.of` cannot tell the two
     * apart from the response, so the repository says which one it asked for.
     */
    @PUT("api/v1/diary/students/{id}/overrides")
    suspend fun putOverride(
        @Path("id") studentId: Long,
        @Body body: DiaryOverrideRequestDto,
    ): DiaryOverrideDto

    /**
     * Puts one field back to what the diary says. Answers 204 with no body.
     *
     * A POST with a body rather than a DELETE with a query string, because the
     * value that has to match byte for byte is the target and a target can
     * carry an ampersand: a key spelled a shade differently in a URL resets
     * nothing and is still answered 204, which is a reset button that looks
     * like it worked.
     *
     * Idempotent, and 204 whether or not there was a row: "there is no
     * correction here" is the state the caller asked for.
     */
    @POST("api/v1/diary/students/{id}/overrides/reset")
    suspend fun resetOverride(
        @Path("id") studentId: Long,
        @Body body: DiaryResetRequestDto,
    )

    /** Drops every correction for this child, so the diary answers for itself again. */
    @DELETE("api/v1/diary/students/{id}/overrides/all")
    suspend fun resetOverrides(@Path("id") studentId: Long)
}
