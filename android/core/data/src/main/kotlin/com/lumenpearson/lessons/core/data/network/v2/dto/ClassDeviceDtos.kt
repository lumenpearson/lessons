package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class ClassDeviceDto(
    val id: Int = 0,
    val deviceName: String? = null,
    val linked: Boolean = false,
    val owner: String? = null,
    @Serializable(with = RoleWireSerializer::class)
    val role: String = "",
    val revoked: Boolean = false,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
    val linkedAt: String? = null,
    val clientVersion: Int? = null,
)

@Serializable
internal data class ListClassDevicesRequestDto(
    val includeRevoked: Boolean = false,
)

@Serializable
internal data class ListClassDevicesResponseDto(
    val devices: List<ClassDeviceDto> = emptyList(),
)

@Serializable
internal data class RevokeClassDeviceRequestDto(
    val deviceId: Int = 0,
)

@Serializable
internal data class RevokeClassDeviceResponseDto(
    val device: ClassDeviceDto? = null,
)

@Serializable
internal data class UnlinkClassDeviceRequestDto(
    val deviceId: Int = 0,
)

@Serializable
internal data class UnlinkClassDeviceResponseDto(
    val device: ClassDeviceDto? = null,
)
