package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class ClassSummaryDto(
    val id: Int = 0,
    val name: String = "",
    val grade: Int? = null,
    val letter: String? = null,
    val school: String? = null,
    val city: String? = null,
    val timezone: String = "",
    @Serializable(with = TermKindWireSerializer::class)
    val termKind: String = "",
    val terms: List<TermDto> = emptyList(),
)

@Serializable
internal data class DeviceAccessDto(
    val linked: Boolean = false,
    @Serializable(with = RoleWireSerializer::class)
    val role: String = "",
    val canEdit: Boolean = false,
)

@Serializable
internal data class GetScheduleWindowRequestDto(
    val year: Int = 0,
    val ifNoneMatch: String? = null,
)

@Serializable
internal data class GetScheduleWindowResponseDto(
    val notModified: Boolean = false,
    val etag: String = "",
    val window: ScheduleWindowDto? = null,
)

@Serializable
internal data class HolidayDto(
    val code: String = "",
    val title: String = "",
    val stopsLessons: Boolean = false,
)

@Serializable
internal data class LessonDto(
    val index: Int = 0,
    val subject: String = "",
    val startsAt: String = "",
    val endsAt: String = "",
    val room: String? = null,
    val teacher: String? = null,
    val color: String? = null,
    val isReplaced: Boolean = false,
    val isCancelled: Boolean = false,
    val note: String? = null,
)

@Serializable
internal data class ScheduleDayDto(
    val date: String = "",
    val weekday: Int = 0,
    @Serializable(with = DayKindWireSerializer::class)
    val kind: String = "",
    val lessons: List<LessonDto> = emptyList(),
    val events: List<ScheduleEventDto> = emptyList(),
    val homework: List<ScheduleHomeworkDto> = emptyList(),
    val note: String? = null,
    val holiday: HolidayDto? = null,
    @Serializable(with = DayOffReasonWireSerializer::class)
    val offReason: String = "",
)

@Serializable
internal data class ScheduleEventDto(
    val title: String = "",
    @Serializable(with = EventKindWireSerializer::class)
    val kind: String = "",
    val startsAt: String = "",
    val endsAt: String = "",
    val location: String? = null,
    val coversLesson: Boolean = false,
)

@Serializable
internal data class ScheduleHomeworkDto(
    val subject: String = "",
    val text: String = "",
    val attachmentUrl: String? = null,
)

@Serializable
internal data class ScheduleWindowDto(
    val schoolClass: ClassSummaryDto? = null,
    val generatedAt: String? = null,
    val days: List<ScheduleDayDto> = emptyList(),
    val device: DeviceAccessDto? = null,
)
