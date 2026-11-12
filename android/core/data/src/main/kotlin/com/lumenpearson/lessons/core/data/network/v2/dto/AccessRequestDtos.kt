package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class AccessRequestDto(
    val id: Int = 0,
    val who: String = "",
    @Serializable(with = RoleWireSerializer::class)
    val requestedRole: String = "",
    val message: String? = null,
    val createdAt: String? = null,
)

@Serializable
internal data class ApproveAccessRequestRequestDto(
    val requestId: Int = 0,
    @Serializable(with = RoleWireSerializer::class)
    val role: String = "",
)

@Serializable
internal data class ApproveAccessRequestResponseDto(
    val requestId: Int = 0,
    @Serializable(with = RoleWireSerializer::class)
    val role: String = "",
    val who: String = "",
)

@Serializable
internal data class DeclineAccessRequestRequestDto(
    val requestId: Int = 0,
)

@Serializable
internal data class DeclineAccessRequestResponseDto(
    val requestId: Int = 0,
    val who: String = "",
)

@Serializable
internal class ListAccessRequestsRequestDto

@Serializable
internal data class ListAccessRequestsResponseDto(
    val accessRequests: List<AccessRequestDto> = emptyList(),
)
