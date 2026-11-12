package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class CreateHomeworkRequestDto(
    val homework: HomeworkDto? = null,
)

@Serializable
internal data class CreateHomeworkResponseDto(
    val homework: HomeworkDto? = null,
)

@Serializable
internal data class DeleteHomeworkRequestDto(
    val homeworkId: Int = 0,
)

@Serializable
internal class DeleteHomeworkResponseDto

@Serializable
internal data class GetHomeworkRequestDto(
    val homeworkId: Int = 0,
)

@Serializable
internal data class GetHomeworkResponseDto(
    val homework: HomeworkDto? = null,
)

@Serializable
internal data class HomeworkDto(
    val id: Int = 0,
    val dueDate: String = "",
    val subject: String = "",
    val text: String = "",
    val attachmentUrl: String? = null,
    val done: Boolean = false,
)

@Serializable
internal data class ListHomeworkRequestDto(
    val startDate: String? = null,
    val endDate: String? = null,
)

@Serializable
internal data class ListHomeworkResponseDto(
    val homework: List<HomeworkDto> = emptyList(),
)

@Serializable
internal data class UpdateHomeworkRequestDto(
    val homework: HomeworkDto? = null,
    val updateMask: String? = null,
)

@Serializable
internal data class UpdateHomeworkResponseDto(
    val homework: HomeworkDto? = null,
)
