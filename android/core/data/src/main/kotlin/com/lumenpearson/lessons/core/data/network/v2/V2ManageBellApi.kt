package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.BellScheduleDto
import com.lumenpearson.lessons.core.data.network.v2.dto.CreateBellScheduleResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DeleteBellScheduleResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ListBellSchedulesResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.UpdateBellScheduleResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

internal interface V2ManageBellApi {
    @Credential(CredentialKind.DEVICE)
    @GET("api/v2/class/bellSchedules")
    suspend fun list(
        @Tag observation: CallObservation,
    ): Response<ListBellSchedulesResponseDto>

    @Credential(CredentialKind.DEVICE)
    @POST("api/v2/class/bellSchedules")
    suspend fun create(
        @Body body: BellScheduleDto,
        @Tag observation: CallObservation,
    ): Response<CreateBellScheduleResponseDto>

    @Credential(CredentialKind.DEVICE)
    @PATCH("api/v2/class/bellSchedules/{id}")
    suspend fun update(
        @Path("id") id: Int,
        @Body body: BellScheduleDto,
        @Query("updateMask") mask: String,
        @Tag observation: CallObservation,
    ): Response<UpdateBellScheduleResponseDto>

    @Credential(CredentialKind.DEVICE)
    @DELETE("api/v2/class/bellSchedules/{id}")
    suspend fun delete(
        @Path("id") id: Int,
        @Tag observation: CallObservation,
    ): Response<DeleteBellScheduleResponseDto>

}
