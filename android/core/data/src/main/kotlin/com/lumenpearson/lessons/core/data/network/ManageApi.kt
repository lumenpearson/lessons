package com.lumenpearson.lessons.core.data.network

import com.lumenpearson.lessons.core.data.network.dto.AccessRequestDto
import com.lumenpearson.lessons.core.data.network.dto.AuditPageDto
import com.lumenpearson.lessons.core.data.network.dto.BellPeriodsDto
import com.lumenpearson.lessons.core.data.network.dto.BellScheduleDto
import com.lumenpearson.lessons.core.data.network.dto.BellScheduleInDto
import com.lumenpearson.lessons.core.data.network.dto.BellSchedulePatchDto
import com.lumenpearson.lessons.core.data.network.dto.ClassDeleteDto
import com.lumenpearson.lessons.core.data.network.dto.ClassPatchDto
import com.lumenpearson.lessons.core.data.network.dto.DeletedDto
import com.lumenpearson.lessons.core.data.network.dto.ManagedClassDto
import com.lumenpearson.lessons.core.data.network.dto.ManagedDeviceDto
import com.lumenpearson.lessons.core.data.network.dto.ManagedSubjectDto
import com.lumenpearson.lessons.core.data.network.dto.RequestDecisionDto
import com.lumenpearson.lessons.core.data.network.dto.RequestDecisionInDto
import com.lumenpearson.lessons.core.data.network.dto.StatsDto
import com.lumenpearson.lessons.core.data.network.dto.SubjectInDto
import com.lumenpearson.lessons.core.data.network.dto.SubjectPatchDto
import com.lumenpearson.lessons.core.data.network.dto.SubjectSavedDto
import com.lumenpearson.lessons.core.data.network.dto.TimetableExportDto
import com.lumenpearson.lessons.core.data.network.dto.TimetableImportDto
import com.lumenpearson.lessons.core.data.network.dto.TimetableImportInDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Running the class from the phone: `/api/v1/manage`, as the bot's management
 * commands.
 *
 * Its own interface rather than more methods on [LessonsApi] for the reason
 * [DiaryApi] is its own: [LessonsApi] is what every install uses on every sync,
 * and this is what two people in a class touch at all. It is *not* a separate
 * account, though — the ordinary class bearer signs it, so [AuthInterceptor]
 * needs no exception and there is no third interceptor.
 *
 * Nothing here decides who may call what. The server looks the role up per
 * request from the linked Telegram account, so a phone whose owner was demoted
 * in the bot is refused on its next tap whatever this app believes about it.
 *
 * Paths are relative, as everywhere else, so a base URL with a path prefix
 * behind a reverse proxy keeps working.
 */
internal interface ManageApi {

    @GET("api/v1/manage/class")
    suspend fun classCard(): ManagedClassDto

    @PATCH("api/v1/manage/class")
    suspend fun updateClass(@Body body: ClassPatchDto): ManagedClassDto

    /**
     * Deletes the class and everything hanging off it. Owner only.
     *
     * `@HTTP(hasBody = true)` rather than `@DELETE`: Retrofit's own annotation
     * carries no body, and the confirmation the server insists on travels in
     * one. Every device token goes with the class, this phone's included, so
     * the next call after this one is a `401` — which is correct, there is
     * nothing left for it to read.
     */
    @HTTP(method = "DELETE", path = "api/v1/manage/class", hasBody = true)
    suspend fun deleteClass(@Body body: ClassDeleteDto): DeletedDto

    @GET("api/v1/manage/subjects")
    suspend fun subjects(): List<ManagedSubjectDto>

    @POST("api/v1/manage/subjects")
    suspend fun createSubject(@Body body: SubjectInDto): SubjectSavedDto

    @PATCH("api/v1/manage/subjects/{id}")
    suspend fun updateSubject(
        @Path("id") subjectId: Long,
        @Body body: SubjectPatchDto,
    ): SubjectSavedDto

    @DELETE("api/v1/manage/subjects/{id}")
    suspend fun deleteSubject(@Path("id") subjectId: Long): DeletedDto

    @GET("api/v1/manage/bells")
    suspend fun bells(): List<BellScheduleDto>

    @POST("api/v1/manage/bells")
    suspend fun createBellSchedule(@Body body: BellScheduleInDto): BellScheduleDto

    @PATCH("api/v1/manage/bells/{id}")
    suspend fun updateBellSchedule(
        @Path("id") scheduleId: Long,
        @Body body: BellSchedulePatchDto,
    ): BellScheduleDto

    /** Replaces the rows wholesale; move one lesson and every later one shifts. */
    @PUT("api/v1/manage/bells/{id}/periods")
    suspend fun writeBellPeriods(
        @Path("id") scheduleId: Long,
        @Body body: BellPeriodsDto,
    ): BellScheduleDto

    @DELETE("api/v1/manage/bells/{id}")
    suspend fun deleteBellSchedule(@Path("id") scheduleId: Long): DeletedDto

    @GET("api/v1/manage/timetable")
    suspend fun timetable(): TimetableExportDto

    /**
     * Parses a paste and replaces exactly the weekdays it names.
     *
     * A collision is not an error: the answer comes back `applied: false` with
     * the weekdays that stand to be overwritten, and sending the same text with
     * `replace: true` is the second tap.
     */
    @POST("api/v1/manage/timetable/import")
    suspend fun importTimetable(@Body body: TimetableImportInDto): TimetableImportDto

    @GET("api/v1/manage/devices")
    suspend fun devices(@Query("include_revoked") includeRevoked: Boolean): List<ManagedDeviceDto>

    @POST("api/v1/manage/devices/{id}/revoke")
    suspend fun revokeDevice(@Path("id") deviceId: Long): ManagedDeviceDto

    @POST("api/v1/manage/devices/{id}/unlink")
    suspend fun unlinkDevice(@Path("id") deviceId: Long): ManagedDeviceDto

    @GET("api/v1/manage/log")
    suspend fun log(
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
    ): AuditPageDto

    @GET("api/v1/manage/stats")
    suspend fun stats(): StatsDto

    @GET("api/v1/manage/requests")
    suspend fun requests(): List<AccessRequestDto>

    /** The body is optional on the server; it is always sent, empty for "as asked". */
    @POST("api/v1/manage/requests/{id}/approve")
    suspend fun approveRequest(
        @Path("id") requestId: Long,
        @Body body: RequestDecisionInDto,
    ): RequestDecisionDto

    @POST("api/v1/manage/requests/{id}/decline")
    suspend fun declineRequest(@Path("id") requestId: Long): RequestDecisionDto
}
