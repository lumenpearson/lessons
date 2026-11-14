package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.ApproveAccessRequestRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ApproveAccessRequestResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DeclineAccessRequestRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DeclineAccessRequestResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ListAccessRequestsResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Tag

internal interface V2ManageRequestApi {
    @Credential(CredentialKind.DEVICE)
    @GET("api/v2/class/accessRequests")
    suspend fun list(
        @Tag observation: CallObservation,
    ): Response<ListAccessRequestsResponseDto>

    @Credential(CredentialKind.DEVICE)
    @POST("api/v2/class/accessRequests/{id}:approve")
    suspend fun approve(
        @Path("id") id: Int,
        @Body body: ApproveAccessRequestRequestDto,
        @Tag observation: CallObservation,
    ): Response<ApproveAccessRequestResponseDto>

    @Credential(CredentialKind.DEVICE)
    @POST("api/v2/class/accessRequests/{id}:decline")
    suspend fun decline(
        @Path("id") id: Int,
        @Body body: DeclineAccessRequestRequestDto,
        @Tag observation: CallObservation,
    ): Response<DeclineAccessRequestResponseDto>

}
