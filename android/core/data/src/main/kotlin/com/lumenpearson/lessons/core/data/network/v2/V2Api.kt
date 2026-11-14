package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.CreateDeviceRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.CreateDeviceResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.GetScheduleWindowResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.CreateLinkCodeRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.CreateLinkCodeResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.GetMeResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.UnlinkMeRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.UnlinkMeResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Tag

internal interface V2Api {
    @Credential(CredentialKind.DEVICE)
    @GET("api/v2/me")
    suspend fun getMe(@Tag observation: CallObservation): Response<GetMeResponseDto>

    @Credential(CredentialKind.DEVICE)
    @POST("api/v2/me:unlink")
    suspend fun unlinkMe(@Body body: UnlinkMeRequestDto,
        @Tag observation: CallObservation): Response<UnlinkMeResponseDto>

    @Credential(CredentialKind.DEVICE)
    @POST("api/v2/me/linkCodes")
    suspend fun createLinkCode(@Body body: CreateLinkCodeRequestDto,
        @Tag observation: CallObservation): Response<CreateLinkCodeResponseDto>

    @Credential(CredentialKind.NONE)
    @POST("api/v2/devices")
    suspend fun createDevice(@Body body: CreateDeviceRequestDto,
        @Tag observation: CallObservation): Response<CreateDeviceResponseDto>

    @Credential(CredentialKind.DEVICE)
    @GET("api/v2/class/scheduleWindows/{year}")
    suspend fun scheduleWindow(@Path("year") year: Int, @Header("If-None-Match") etag: String?,
        @Tag observation: CallObservation): Response<GetScheduleWindowResponseDto>
}
