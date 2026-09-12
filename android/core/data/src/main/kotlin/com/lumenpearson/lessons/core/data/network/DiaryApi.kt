package com.lumenpearson.lessons.core.data.network

import com.lumenpearson.lessons.core.data.network.dto.DiaryAttendanceDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryHomeworkDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryLessonDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryLoginRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryLoginResponseDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryMarkDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryPeriodDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryStudentDto
import com.lumenpearson.lessons.core.data.network.dto.DiarySubjectDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryTeacherDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The Petersburg diary, as this server publishes it: `/api/v1/diary`.
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
 * Every call but [login] answers `401` when the session is gone, and `401`
 * carrying `X-Diary-Reauth: required` when it is the *upstream* session that
 * died and the password has to be asked for again. Nothing here decides which:
 * `DiaryFailure.of` reads the response and the screen reacts to the type.
 */
internal interface DiaryApi {

    /**
     * Signs in to the diary. The password is used for this one call; neither
     * this app nor the server stores it.
     *
     * `401` here means the credentials were refused — a different thing from a
     * `401` on any other call, which means our own session is no longer good.
     */
    @POST("api/v1/diary/login")
    suspend fun login(@Body body: DiaryLoginRequestDto): DiaryLoginResponseDto

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
}
