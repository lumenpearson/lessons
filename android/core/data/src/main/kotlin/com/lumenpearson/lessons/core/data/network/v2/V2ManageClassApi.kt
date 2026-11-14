package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.DeleteClassResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.GetClassResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.SchoolClassDto
import com.lumenpearson.lessons.core.data.network.v2.dto.UpdateClassResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Query
import retrofit2.http.Tag

internal interface V2ManageClassApi {
    @Credential(CredentialKind.DEVICE)
    @GET("api/v2/class")
    suspend fun classCard(
        @Tag observation: CallObservation,
    ): Response<GetClassResponseDto>

    @Credential(CredentialKind.DEVICE)
    @PATCH("api/v2/class")
    suspend fun update(
        @Body body: SchoolClassDto,
        @Query("updateMask") mask: String,
        @Tag observation: CallObservation,
    ): Response<UpdateClassResponseDto>

    @Credential(CredentialKind.DEVICE)
    @DELETE("api/v2/class")
    suspend fun delete(
        @Query("confirmation") confirmation: String,
        @Tag observation: CallObservation,
    ): Response<DeleteClassResponseDto>

}
