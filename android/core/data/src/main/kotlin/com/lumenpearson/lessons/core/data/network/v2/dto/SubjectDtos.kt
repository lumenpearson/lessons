package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class CreateSubjectRequestDto(
    val subject: SubjectDto? = null,
)

@Serializable
internal data class CreateSubjectResponseDto(
    val subject: SubjectDto? = null,
)

@Serializable
internal data class DeleteSubjectRequestDto(
    val subjectId: Int = 0,
)

@Serializable
internal class DeleteSubjectResponseDto

@Serializable
internal data class GetSubjectRequestDto(
    val subjectId: Int = 0,
)

@Serializable
internal data class GetSubjectResponseDto(
    val subject: SubjectDto? = null,
)

@Serializable
internal class ListSubjectsRequestDto

@Serializable
internal data class ListSubjectsResponseDto(
    val subjects: List<SubjectDto> = emptyList(),
)

@Serializable
internal data class SubjectDto(
    val id: Int = 0,
    val name: String = "",
    val shortName: String? = null,
    val teacher: String? = null,
    val color: String? = null,
)

@Serializable
internal data class UpdateSubjectRequestDto(
    val subject: SubjectDto? = null,
    val updateMask: String? = null,
)

@Serializable
internal data class UpdateSubjectResponseDto(
    val subject: SubjectDto? = null,
    val moved: Int = 0,
)
