package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class ClassStatsDto(
    val today: String = "",
    val lessonsPerWeek: Double = 0.0,
    val subjectsCount: Int = 0,
    val subjects: List<SubjectHoursDto> = emptyList(),
    val homeworkOpen: Int = 0,
    val homeworkTotal: Int = 0,
    val membersByRole: List<RoleCountDto> = emptyList(),
    val devicesActive: Int = 0,
    val substitutionsUpcoming: Int = 0,
    val eventsUpcoming: Int = 0,
)

@Serializable
internal data class DeleteClassRequestDto(
    val confirmation: String = "",
)

@Serializable
internal class DeleteClassResponseDto

@Serializable
internal class GetClassRequestDto

@Serializable
internal data class GetClassResponseDto(
    val schoolClass: SchoolClassDto? = null,
)

@Serializable
internal class GetClassStatsRequestDto

@Serializable
internal data class GetClassStatsResponseDto(
    val stats: ClassStatsDto? = null,
)

@Serializable
internal class GetTermSchemeRequestDto

@Serializable
internal data class GetTermSchemeResponseDto(
    val termScheme: TermSchemeDto? = null,
)

@Serializable
internal class ListTermsRequestDto

@Serializable
internal data class ListTermsResponseDto(
    val year: Int = 0,
    val terms: List<TermDto> = emptyList(),
)

@Serializable
internal data class RoleCountDto(
    @Serializable(with = RoleWireSerializer::class)
    val role: String = "",
    val count: Int = 0,
)

@Serializable
internal data class SchoolClassDto(
    val id: Int = 0,
    val name: String = "",
    val grade: Int? = null,
    val letter: String? = null,
    val school: String? = null,
    val city: String? = null,
    val timezone: String = "",
    val timezoneLabel: String = "",
    val joinCode: String = "",
    @Serializable(with = JoinModeWireSerializer::class)
    val joinMode: String = "",
    val members: Int = 0,
    val devices: Int = 0,
    val pendingRequests: Int = 0,
    val bellScheduleId: Int? = null,
    val calendarReady: Boolean = false,
)

@Serializable
internal data class SubjectHoursDto(
    val name: String = "",
    val hours: Double = 0.0,
)

@Serializable
internal data class TermSchemeDto(
    @Serializable(with = TermKindWireSerializer::class)
    val kind: String = "",
    val year: Int = 0,
)

@Serializable
internal data class UpdateClassRequestDto(
    val schoolClass: SchoolClassDto? = null,
    val updateMask: String? = null,
)

@Serializable
internal data class UpdateClassResponseDto(
    val schoolClass: SchoolClassDto? = null,
)

@Serializable
internal data class UpdateTermRequestDto(
    val term: TermDto? = null,
)

@Serializable
internal data class UpdateTermResponseDto(
    val year: Int = 0,
    val terms: List<TermDto> = emptyList(),
)

@Serializable
internal data class UpdateTermSchemeRequestDto(
    val termScheme: TermSchemeDto? = null,
)

@Serializable
internal data class UpdateTermSchemeResponseDto(
    val termScheme: TermSchemeDto? = null,
    val terms: List<TermDto> = emptyList(),
)
