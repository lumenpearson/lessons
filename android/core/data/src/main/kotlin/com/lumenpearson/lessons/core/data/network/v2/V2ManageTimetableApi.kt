package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.GetTimetableResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ImportTimetableRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ImportTimetableResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Tag

internal interface V2ManageTimetableApi {
    @Credential(CredentialKind.DEVICE)
    @GET("api/v2/class/timetable")
    suspend fun get(
        @Tag observation: CallObservation,
    ): Response<GetTimetableResponseDto>

    @Credential(CredentialKind.DEVICE)
    @POST("api/v2/class/timetable:import")
    suspend fun apply(
        @Body body: ImportTimetableRequestDto,
        @Tag observation: CallObservation,
    ): Response<ImportTimetableResponseDto>

}
