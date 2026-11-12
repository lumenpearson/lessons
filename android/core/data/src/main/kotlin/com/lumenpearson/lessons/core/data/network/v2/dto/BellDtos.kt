package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class BellPeriodDto(
    val index: Int = 0,
    val startsAt: String = "",
    val endsAt: String = "",
)

@Serializable
internal data class BellScheduleDto(
    val id: Int = 0,
    val name: String = "",
    val isDefault: Boolean = false,
    val periods: List<BellPeriodDto> = emptyList(),
)

@Serializable
internal data class CreateBellScheduleRequestDto(
    val schedule: BellScheduleDto? = null,
)

@Serializable
internal data class CreateBellScheduleResponseDto(
    val schedule: BellScheduleDto? = null,
)

@Serializable
internal data class DeleteBellScheduleRequestDto(
    val scheduleId: Int = 0,
)

@Serializable
internal class DeleteBellScheduleResponseDto

@Serializable
internal data class GetBellScheduleRequestDto(
    val scheduleId: Int = 0,
)

@Serializable
internal data class GetBellScheduleResponseDto(
    val schedule: BellScheduleDto? = null,
)

@Serializable
internal class ListBellSchedulesRequestDto

@Serializable
internal data class ListBellSchedulesResponseDto(
    val schedules: List<BellScheduleDto> = emptyList(),
)

@Serializable
internal data class UpdateBellScheduleRequestDto(
    val schedule: BellScheduleDto? = null,
    val updateMask: String? = null,
)

@Serializable
internal data class UpdateBellScheduleResponseDto(
    val schedule: BellScheduleDto? = null,
    val silencedLessons: Int = 0,
)
