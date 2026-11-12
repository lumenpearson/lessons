package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class DayDto(
    val date: String = "",
    @Serializable(with = DayKindWireSerializer::class)
    val kind: String = "",
    val note: String? = null,
    val bellScheduleId: Int? = null,
)

@Serializable
internal data class GetDayRequestDto(
    val date: String = "",
)

@Serializable
internal data class GetDayResponseDto(
    val day: DayDto? = null,
)

@Serializable
internal data class UpdateDayRequestDto(
    val day: DayDto? = null,
    val updateMask: String? = null,
    val allowMissing: Boolean = false,
)

@Serializable
internal data class UpdateDayResponseDto(
    val day: DayDto? = null,
)
