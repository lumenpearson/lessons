package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class BatchUpdateCorrectionsRequestDto(
    @Serializable(with = Int64StringSerializer::class)
    val studentId: Long = 0L,
    val corrections: List<CorrectionUpdateDto> = emptyList(),
)

@Serializable
internal data class BatchUpdateCorrectionsResponseDto(
    val corrections: List<DiaryCorrectionDto> = emptyList(),
)

@Serializable
internal data class ClearCorrectionsRequestDto(
    @Serializable(with = Int64StringSerializer::class)
    val studentId: Long = 0L,
)

@Serializable
internal class ClearCorrectionsResponseDto

@Serializable
internal data class CorrectionKeyDto(
    val target: String = "",
    val field: String = "",
)

@Serializable
internal data class CorrectionUpdateDto(
    val target: String = "",
    val field: String = "",
    val value: String = "",
    val original: String? = null,
)

@Serializable
internal data class CreateDiarySessionRequestDto(
    val login: String = "",
    val petersburg: PetersburgCredentialDto? = null,
    val netschool: NetSchoolCredentialDto? = null,
    val region: String? = null,
    @Serializable(with = Int64StringSerializer::class)
    val schoolId: Long? = null,
)

@Serializable
internal data class CreateDiarySessionResponseDto(
    val session: DiarySessionDto? = null,
)

@Serializable
internal class DeleteDiarySessionRequestDto

@Serializable
internal class DeleteDiarySessionResponseDto

@Serializable
internal data class DiaryAttendanceDto(
    val at: String = "",
    @Serializable(with = AttendanceDirectionWireSerializer::class)
    val direction: String = "",
)

@Serializable
internal data class DiaryCapabilitiesDto(
    val enabled: Boolean = false,
    val providers: List<ProviderCapabilitiesDto> = emptyList(),
    @Serializable(with = DiaryEgressWireSerializer::class)
    val egress: String = "",
)

@Serializable
internal data class DiaryCorrectionDto(
    val target: String = "",
    val field: String = "",
    val value: String = "",
    val originalWhenWritten: String? = null,
    val updatedAt: String? = null,
)

@Serializable
internal data class DiaryEditDto(
    val field: String = "",
    val value: String = "",
    val original: String? = null,
    val changedUpstream: Boolean = false,
)

@Serializable
internal data class DiaryHomeworkDto(
    @Serializable(with = Int64StringSerializer::class)
    val id: Long? = null,
    val dueDate: String = "",
    val subject: String = "",
    val text: String = "",
    val teacher: String? = null,
    val target: String = "",
    val edits: List<DiaryEditDto> = emptyList(),
    val ambiguous: Boolean = false,
)

@Serializable
internal data class DiaryLessonDto(
    val number: Int? = null,
    val subject: String = "",
    val startsAt: String? = null,
    val endsAt: String? = null,
    val room: String? = null,
    val teacher: String? = null,
    val homework: String? = null,
    val topic: String? = null,
    val target: String = "",
    val edits: List<DiaryEditDto> = emptyList(),
    val ambiguous: Boolean = false,
)

@Serializable
internal data class DiaryMarkDto(
    @Serializable(with = Int64StringSerializer::class)
    val id: Long? = null,
    @Serializable(with = Int64StringSerializer::class)
    val subjectId: Long? = null,
    val subject: String = "",
    val date: String? = null,
    val value: String = "",
    @Serializable(with = MarkKindWireSerializer::class)
    val kind: String = "",
    val reason: String? = null,
    val comment: String? = null,
)

@Serializable
internal data class DiaryPeriodDto(
    @Serializable(with = Int64StringSerializer::class)
    val id: Long = 0L,
    val name: String = "",
    val startsOn: String? = null,
    val endsOn: String? = null,
    val isCurrent: Boolean = false,
)

@Serializable
internal data class DiaryRouteDto(
    val region: String? = null,
    @Serializable(with = DiaryEgressWireSerializer::class)
    val egress: String = "",
)

@Serializable
internal data class DiaryScheduleDayDto(
    val date: String = "",
    val lessons: List<DiaryLessonDto> = emptyList(),
)

@Serializable
internal data class DiarySessionDto(
    val token: String = "",
    val login: String = "",
    val provider: String = "",
    val region: String? = null,
    @Serializable(with = Int64StringSerializer::class)
    val schoolId: Long? = null,
    val schoolName: String? = null,
    val zone: String = "",
    val students: List<DiaryStudentDto> = emptyList(),
)

@Serializable
internal data class DiaryStudentDto(
    @Serializable(with = Int64StringSerializer::class)
    val id: Long = 0L,
    val firstName: String = "",
    val lastName: String = "",
    val middleName: String? = null,
    val fullName: String = "",
    val school: String? = null,
    val className: String? = null,
)

@Serializable
internal data class DiarySubjectDto(
    @Serializable(with = Int64StringSerializer::class)
    val id: Long? = null,
    val name: String = "",
)

@Serializable
internal data class DiaryTeacherDto(
    @Serializable(with = Int64StringSerializer::class)
    val id: Long? = null,
    val name: String = "",
    val position: String? = null,
    val subjects: List<String> = emptyList(),
)

@Serializable
internal class GetDiaryCapabilitiesRequestDto

@Serializable
internal data class GetDiaryCapabilitiesResponseDto(
    val capabilities: DiaryCapabilitiesDto? = null,
)

@Serializable
internal data class ListCorrectionsRequestDto(
    @Serializable(with = Int64StringSerializer::class)
    val studentId: Long = 0L,
)

@Serializable
internal data class ListCorrectionsResponseDto(
    val corrections: List<DiaryCorrectionDto> = emptyList(),
)

@Serializable
internal data class ListDiaryHomeworkRequestDto(
    @Serializable(with = Int64StringSerializer::class)
    val studentId: Long = 0L,
    val startDate: String? = null,
    val endDate: String? = null,
)

@Serializable
internal data class ListDiaryHomeworkResponseDto(
    val homework: List<DiaryHomeworkDto> = emptyList(),
)

@Serializable
internal data class ListDiarySubjectsRequestDto(
    @Serializable(with = Int64StringSerializer::class)
    val studentId: Long = 0L,
    @Serializable(with = Int64StringSerializer::class)
    val periodId: Long? = null,
)

@Serializable
internal data class ListDiarySubjectsResponseDto(
    val subjects: List<DiarySubjectDto> = emptyList(),
)

@Serializable
internal data class ListMarksRequestDto(
    @Serializable(with = Int64StringSerializer::class)
    val studentId: Long = 0L,
    val startDate: String? = null,
    val endDate: String? = null,
)

@Serializable
internal data class ListMarksResponseDto(
    val marks: List<DiaryMarkDto> = emptyList(),
)

@Serializable
internal data class ListPeriodsRequestDto(
    @Serializable(with = Int64StringSerializer::class)
    val studentId: Long = 0L,
)

@Serializable
internal data class ListPeriodsResponseDto(
    val periods: List<DiaryPeriodDto> = emptyList(),
)

@Serializable
internal data class ListScheduleDaysRequestDto(
    @Serializable(with = Int64StringSerializer::class)
    val studentId: Long = 0L,
    val startDate: String? = null,
    val endDate: String? = null,
)

@Serializable
internal data class ListScheduleDaysResponseDto(
    val scheduleDays: List<DiaryScheduleDayDto> = emptyList(),
)

@Serializable
internal class ListStudentsRequestDto

@Serializable
internal data class ListStudentsResponseDto(
    val students: List<DiaryStudentDto> = emptyList(),
)

@Serializable
internal data class ListTeachersRequestDto(
    @Serializable(with = Int64StringSerializer::class)
    val studentId: Long = 0L,
)

@Serializable
internal data class ListTeachersResponseDto(
    val teachers: List<DiaryTeacherDto> = emptyList(),
)

@Serializable
internal data class ListTurnstileEventsRequestDto(
    @Serializable(with = Int64StringSerializer::class)
    val studentId: Long = 0L,
)

@Serializable
internal data class ListTurnstileEventsResponseDto(
    val turnstileEvents: List<DiaryAttendanceDto> = emptyList(),
)

@Serializable
internal data class NetSchoolCookiesDto(
    val nsSessionId: String = "",
    val esrnSec: String? = null,
)

@Serializable
internal data class NetSchoolCredentialDto(
    val at: String = "",
    val cookies: NetSchoolCookiesDto? = null,
    val ver: String? = null,
    val timeOut: Int? = null,
)

@Serializable
internal data class PetersburgCredentialDto(
    val token: String = "",
)

@Serializable
internal data class ProviderCapabilitiesDto(
    val provider: String = "",
    val regions: List<String> = emptyList(),
    val signInMethods: List<@Serializable(with = SignInMethodWireSerializer::class) String> = emptyList(),
    val features: List<@Serializable(with = DiaryFeatureWireSerializer::class) String> = emptyList(),
    val routes: List<DiaryRouteDto> = emptyList(),
)

@Serializable
internal data class ResetCorrectionsRequestDto(
    @Serializable(with = Int64StringSerializer::class)
    val studentId: Long = 0L,
    val corrections: List<CorrectionKeyDto> = emptyList(),
)

@Serializable
internal class ResetCorrectionsResponseDto
