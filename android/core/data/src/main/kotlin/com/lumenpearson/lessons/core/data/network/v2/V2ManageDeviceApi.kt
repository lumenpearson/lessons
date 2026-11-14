package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.ListClassDevicesResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.RevokeClassDeviceRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.RevokeClassDeviceResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.UnlinkClassDeviceRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.UnlinkClassDeviceResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

internal interface V2ManageDeviceApi {
    @Credential(CredentialKind.DEVICE)
    @GET("api/v2/class/devices")
    suspend fun list(
        @Query("includeRevoked") includeRevoked: Boolean,
        @Tag observation: CallObservation,
    ): Response<ListClassDevicesResponseDto>

    @Credential(CredentialKind.DEVICE)
    @POST("api/v2/class/devices/{id}:revoke")
    suspend fun revoke(
        @Path("id") id: Int,
        @Body body: RevokeClassDeviceRequestDto,
        @Tag observation: CallObservation,
    ): Response<RevokeClassDeviceResponseDto>

    @Credential(CredentialKind.DEVICE)
    @POST("api/v2/class/devices/{id}:unlink")
    suspend fun unlink(
        @Path("id") id: Int,
        @Body body: UnlinkClassDeviceRequestDto,
        @Tag observation: CallObservation,
    ): Response<UnlinkClassDeviceResponseDto>

}
