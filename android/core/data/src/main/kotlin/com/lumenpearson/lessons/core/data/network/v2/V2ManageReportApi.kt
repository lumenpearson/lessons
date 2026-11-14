package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.GetClassStatsResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ListAuditEntriesResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Tag

internal interface V2ManageReportApi {
    @Credential(CredentialKind.DEVICE)
    @GET("api/v2/class/auditEntries")
    suspend fun log(
        @Query("pageSize") pageSize: Int,
        @Query("pageToken") pageToken: String?,
        @Tag observation: CallObservation,
    ): Response<ListAuditEntriesResponseDto>

    @Credential(CredentialKind.DEVICE)
    @GET("api/v2/class/stats")
    suspend fun stats(
        @Tag observation: CallObservation,
    ): Response<GetClassStatsResponseDto>

}
