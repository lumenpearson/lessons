package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.CreateSubjectResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DeleteSubjectResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.GetSubjectResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ListSubjectsResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.SubjectDto
import com.lumenpearson.lessons.core.data.network.v2.dto.UpdateSubjectResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

internal interface V2ManageSubjectApi {
    @Credential(CredentialKind.DEVICE)
    @GET("api/v2/class/subjects")
    suspend fun list(
        @Tag observation: CallObservation,
    ): Response<ListSubjectsResponseDto>

    @Credential(CredentialKind.DEVICE)
    @GET("api/v2/class/subjects/{id}")
    suspend fun get(
        @Path("id") id: Int,
        @Tag observation: CallObservation,
    ): Response<GetSubjectResponseDto>

    @Credential(CredentialKind.DEVICE)
    @POST("api/v2/class/subjects")
    suspend fun create(
        @Body body: SubjectDto,
        @Tag observation: CallObservation,
    ): Response<CreateSubjectResponseDto>

    @Credential(CredentialKind.DEVICE)
    @PATCH("api/v2/class/subjects/{id}")
    suspend fun update(
        @Path("id") id: Int,
        @Body body: SubjectDto,
        @Query("updateMask") mask: String,
        @Tag observation: CallObservation,
    ): Response<UpdateSubjectResponseDto>

    @Credential(CredentialKind.DEVICE)
    @DELETE("api/v2/class/subjects/{id}")
    suspend fun delete(
        @Path("id") id: Int,
        @Tag observation: CallObservation,
    ): Response<DeleteSubjectResponseDto>

}
