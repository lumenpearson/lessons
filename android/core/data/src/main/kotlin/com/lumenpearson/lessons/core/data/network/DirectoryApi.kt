package com.lumenpearson.lessons.core.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * The school directory (`server/app/api/directory.py`): «which regions is a
 * school of this name in», for a phone that is in no class and signed in to
 * nothing.
 *
 * Anonymous, and kept so on purpose. `AuthInterceptor` skips every path under
 * `/api/v1/directory/`, so a phone that *is* in a class does not tell the
 * server which class is looking for which school, and `DiaryAuthInterceptor`
 * signs only `/api/v1/diary`. The server counts every call against the caller
 * and spends a shared daily allowance on it, so the phone asks on submit or
 * after a pause in typing, never per keystroke — see `SchoolDirectory`.
 */
internal interface DirectoryApi {

    /**
     * `200` with the regions, best-ranked first; `422` for a query under three
     * characters; `429` + `Retry-After`; `503` with [UNAVAILABLE_HEADER].
     */
    @GET("api/v1/directory/school-regions")
    suspend fun schoolRegions(@Query("q") query: String): SchoolRegionsDto

    companion object {
        /**
         * Every directory `503` carries it: `disabled` (no directory key on
         * this server), `spent` (today's anonymous allowance is gone; comes with
         * `Retry-After`) or `upstream` (the directory itself failed).
         */
        const val UNAVAILABLE_HEADER = "X-Directory-Unavailable"
    }
}

/** `SchoolRegionsOut` in `server/app/schemas.py`. */
@Serializable
internal data class SchoolRegionsDto(
    @SerialName("query") val query: String = "",
    @SerialName("regions") val regions: List<SchoolRegionDto> = emptyList(),
    @SerialName("truncated") val truncated: Boolean = false,
    @SerialName("generic") val generic: Boolean = false,
)

/**
 * `SchoolRegionOut`: one region the search placed schools in.
 *
 * @property region the catalog's key, or `null` for a region the server's
 *   catalog could not place — then [label] is the directory's own name for it.
 */
@Serializable
internal data class SchoolRegionDto(
    @SerialName("region") val region: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("schools") val schools: Int = 0,
    @SerialName("cities") val cities: List<String> = emptyList(),
    @SerialName("examples") val examples: List<String> = emptyList(),
)
