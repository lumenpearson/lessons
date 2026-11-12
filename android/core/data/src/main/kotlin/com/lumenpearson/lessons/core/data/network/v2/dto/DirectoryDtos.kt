package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class ListSchoolRegionsRequestDto(
    val query: String = "",
)

@Serializable
internal data class ListSchoolRegionsResponseDto(
    val query: String = "",
    val regions: List<SchoolRegionDto> = emptyList(),
    val truncated: Boolean = false,
    val generic: Boolean = false,
)

@Serializable
internal data class ListSchoolsRequestDto(
    val query: String = "",
    val region: String? = null,
    val pageSize: Int = 0,
    val pageToken: String = "",
)

@Serializable
internal data class ListSchoolsResponseDto(
    val schools: List<SchoolDto> = emptyList(),
    val nextPageToken: String = "",
    val totalSize: Int = 0,
    val truncated: Boolean = false,
)

@Serializable
internal data class SchoolDto(
    val name: String = "",
    val fullName: String = "",
    val ogrn: String? = null,
    val inn: String? = null,
    val address: String? = null,
    val city: String? = null,
    val region: String? = null,
    val active: Boolean = false,
)

@Serializable
internal data class SchoolRegionDto(
    val region: String? = null,
    val code: String? = null,
    val label: String? = null,
    val schools: Int = 0,
    val cities: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
)
