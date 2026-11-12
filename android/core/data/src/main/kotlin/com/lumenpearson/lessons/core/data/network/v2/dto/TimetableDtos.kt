package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal class GetTimetableRequestDto

@Serializable
internal data class GetTimetableResponseDto(
    val timetable: TimetableDto? = null,
)

@Serializable
internal data class ImportConflictDto(
    val weekday: Int = 0,
    val existing: Int = 0,
    val incoming: Int = 0,
)

@Serializable
internal data class ImportTimetableRequestDto(
    val text: String = "",
    val replace: Boolean = false,
    val validateOnly: Boolean = false,
)

@Serializable
internal data class ImportTimetableResponseDto(
    val applied: Boolean = false,
    val days: List<Int> = emptyList(),
    val lessons: Int = 0,
    val bells: Int = 0,
    val conflicts: List<ImportConflictDto> = emptyList(),
    val rejected: List<String> = emptyList(),
)

@Serializable
internal data class TimetableDto(
    val text: String = "",
    val lessons: Int = 0,
)
