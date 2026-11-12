package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class AuditEntryDto(
    val id: Int = 0,
    val action: String = "",
    val summary: String = "",
    val who: String? = null,
    val at: String? = null,
)

@Serializable
internal data class ListAuditEntriesRequestDto(
    val pageSize: Int = 0,
    val pageToken: String = "",
)

@Serializable
internal data class ListAuditEntriesResponseDto(
    val auditEntries: List<AuditEntryDto> = emptyList(),
    val nextPageToken: String = "",
)
