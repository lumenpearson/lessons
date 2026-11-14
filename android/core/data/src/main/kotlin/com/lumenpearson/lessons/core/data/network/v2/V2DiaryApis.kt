package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.BatchUpdateCorrectionsRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.BatchUpdateCorrectionsResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ClearCorrectionsRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ClearCorrectionsResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.CreateDiarySessionRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.CreateDiarySessionResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DeleteDiarySessionResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.GetDiaryCapabilitiesResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ListCorrectionsResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ListDiaryHomeworkResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ListMarksResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ListPeriodsResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ListScheduleDaysResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ListStudentsResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ResetCorrectionsRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ResetCorrectionsResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

internal interface V2DiarySessionApi {
    @Credential(CredentialKind.NONE)
    @GET("api/v2/diary/capabilities")
    suspend fun capabilities(@Tag observation: CallObservation): Response<GetDiaryCapabilitiesResponseDto>

    @Credential(CredentialKind.NONE)
    @POST("api/v2/diary/sessions")
    suspend fun register(@Body body: CreateDiarySessionRequestDto,
        @Tag observation: CallObservation): Response<CreateDiarySessionResponseDto>

    @Credential(CredentialKind.DIARY)
    @DELETE("api/v2/diary/sessions/current")
    suspend fun logout(@Tag observation: CallObservation): Response<DeleteDiarySessionResponseDto>
}

internal interface V2DiaryReadApi {
    @Credential(CredentialKind.DIARY)
    @GET("api/v2/diary/students")
    suspend fun students(@Tag observation: CallObservation): Response<ListStudentsResponseDto>

    @Credential(CredentialKind.DIARY)
    @GET("api/v2/diary/students/{id}/scheduleDays")
    suspend fun schedule(@Path("id") id: Long, @Query("startDate") from: String,
        @Query("endDate") to: String, @Tag observation: CallObservation): Response<ListScheduleDaysResponseDto>

    @Credential(CredentialKind.DIARY)
    @GET("api/v2/diary/students/{id}/homework")
    suspend fun homework(@Path("id") id: Long, @Query("startDate") from: String,
        @Query("endDate") to: String, @Tag observation: CallObservation): Response<ListDiaryHomeworkResponseDto>

    @Credential(CredentialKind.DIARY)
    @GET("api/v2/diary/students/{id}/marks")
    suspend fun grades(@Path("id") id: Long, @Query("startDate") from: String,
        @Query("endDate") to: String, @Tag observation: CallObservation): Response<ListMarksResponseDto>

    @Credential(CredentialKind.DIARY)
    @GET("api/v2/diary/students/{id}/periods")
    suspend fun periods(@Path("id") id: Long, @Tag observation: CallObservation): Response<ListPeriodsResponseDto>
}

internal interface V2DiaryCorrectionApi {
    @Credential(CredentialKind.DIARY)
    @GET("api/v2/diary/students/{id}/corrections")
    suspend fun overrides(@Path("id") id: Long, @Tag observation: CallObservation): Response<ListCorrectionsResponseDto>

    @Credential(CredentialKind.DIARY)
    @POST("api/v2/diary/students/{id}/corrections:batchUpdate")
    suspend fun correct(@Path("id") id: Long, @Body body: BatchUpdateCorrectionsRequestDto,
        @Tag observation: CallObservation): Response<BatchUpdateCorrectionsResponseDto>

    @Credential(CredentialKind.DIARY)
    @POST("api/v2/diary/students/{id}/corrections:reset")
    suspend fun reset(@Path("id") id: Long, @Body body: ResetCorrectionsRequestDto,
        @Tag observation: CallObservation): Response<ResetCorrectionsResponseDto>

    @Credential(CredentialKind.DIARY)
    @POST("api/v2/diary/students/{id}/corrections:clear")
    suspend fun clear(@Path("id") id: Long, @Body body: ClearCorrectionsRequestDto,
        @Tag observation: CallObservation): Response<ClearCorrectionsResponseDto>
}
