package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.catalog.DirectoryRegion
import com.lumenpearson.lessons.core.data.catalog.DirectoryRegions
import com.lumenpearson.lessons.core.data.network.v2.dto.ListSchoolsResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ListSchoolRegionsResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Tag

internal interface V2DirectoryApi {
    @Credential(CredentialKind.DEVICE)
    @GET("api/v2/schools")
    suspend fun schools(@Query("query") query: String, @Query("pageSize") pageSize: Int,
        @Tag observation: CallObservation): Response<ListSchoolsResponseDto>

    @Credential(CredentialKind.NONE)
    @GET("api/v2/schoolRegions")
    suspend fun schoolRegions(@Query("query") query: String,
        @Tag observation: CallObservation): Response<ListSchoolRegionsResponseDto>
}

internal class RestDirectoryRemote(private val api: V2DirectoryApi) : DirectoryRemote {
    override suspend fun schoolRegions(query: String): DirectoryRegions {
        val value = RestRefusals.body(RestRefusals.request { api.schoolRegions(query, it) })
        return DirectoryRegions(value.query, value.regions.map {
            DirectoryRegion(it.region, it.code, it.label, it.schools, it.cities, it.examples)
        }, value.truncated, value.generic)
    }
}
