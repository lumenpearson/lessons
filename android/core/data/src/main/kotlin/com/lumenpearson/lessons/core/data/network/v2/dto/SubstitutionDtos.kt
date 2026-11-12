package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class CreateSubstitutionRequestDto(
    val substitution: SubstitutionDto? = null,
)

@Serializable
internal data class CreateSubstitutionResponseDto(
    val substitution: SubstitutionDto? = null,
)

@Serializable
internal data class DeleteSubstitutionRequestDto(
    val substitutionId: Int = 0,
)

@Serializable
internal class DeleteSubstitutionResponseDto

@Serializable
internal data class GetSubstitutionRequestDto(
    val substitutionId: Int = 0,
)

@Serializable
internal data class GetSubstitutionResponseDto(
    val substitution: SubstitutionDto? = null,
)

@Serializable
internal data class ListSubstitutionsRequestDto(
    val startDate: String? = null,
    val endDate: String? = null,
)

@Serializable
internal data class ListSubstitutionsResponseDto(
    val substitutions: List<SubstitutionDto> = emptyList(),
)

@Serializable
internal data class SubstitutionDto(
    val id: Int = 0,
    val date: String = "",
    val index: Int = 0,
    @Serializable(with = SubstitutionActionWireSerializer::class)
    val action: String = "",
    val subject: String? = null,
    val room: String? = null,
    val teacher: String? = null,
    val note: String? = null,
)

@Serializable
internal data class UpdateSubstitutionRequestDto(
    val substitution: SubstitutionDto? = null,
    val updateMask: String? = null,
)

@Serializable
internal data class UpdateSubstitutionResponseDto(
    val substitution: SubstitutionDto? = null,
)
