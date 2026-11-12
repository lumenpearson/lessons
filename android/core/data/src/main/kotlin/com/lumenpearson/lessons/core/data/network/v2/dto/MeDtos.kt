package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class CalendarFeedDto(
    val url: String? = null,
)

@Serializable
internal class CreateCalendarFeedRequestDto

@Serializable
internal data class CreateCalendarFeedResponseDto(
    val calendarFeed: CalendarFeedDto? = null,
)

@Serializable
internal data class CreateHomeworkTickRequestDto(
    val homeworkTick: HomeworkTickDto? = null,
)

@Serializable
internal data class CreateHomeworkTickResponseDto(
    val homeworkTick: HomeworkTickDto? = null,
)

@Serializable
internal class CreateLinkCodeRequestDto

@Serializable
internal data class CreateLinkCodeResponseDto(
    val linkCode: LinkCodeDto? = null,
)

@Serializable
internal data class CreateTaskRequestDto(
    val task: TaskDto? = null,
)

@Serializable
internal data class CreateTaskResponseDto(
    val task: TaskDto? = null,
)

@Serializable
internal data class DeleteHomeworkTickRequestDto(
    val homeworkId: Int = 0,
)

@Serializable
internal class DeleteHomeworkTickResponseDto

@Serializable
internal data class DeleteTaskRequestDto(
    val taskId: Int = 0,
)

@Serializable
internal class DeleteTaskResponseDto

@Serializable
internal class GetCalendarFeedRequestDto

@Serializable
internal data class GetCalendarFeedResponseDto(
    val calendarFeed: CalendarFeedDto? = null,
)

@Serializable
internal class GetMeRequestDto

@Serializable
internal data class GetMeResponseDto(
    val me: MeDto? = null,
)

@Serializable
internal data class GetTaskRequestDto(
    val taskId: Int = 0,
)

@Serializable
internal data class GetTaskResponseDto(
    val task: TaskDto? = null,
)

@Serializable
internal data class HomeworkTickDto(
    val homeworkId: Int = 0,
)

@Serializable
internal data class LinkCodeDto(
    val code: String = "",
    val botDeepLink: String? = null,
)

@Serializable
internal data class ListTasksRequestDto(
    val includeDone: Boolean = false,
)

@Serializable
internal data class ListTasksResponseDto(
    val tasks: List<TaskDto> = emptyList(),
)

@Serializable
internal data class MeDto(
    val deviceName: String? = null,
    val linked: Boolean = false,
    @Serializable(with = RoleWireSerializer::class)
    val role: String = "",
    val canEdit: Boolean = false,
)

@Serializable
internal data class TaskDto(
    val id: Int = 0,
    val title: String = "",
    val notes: String? = null,
    val subjectName: String? = null,
    val dueDate: String? = null,
    val dueTime: String? = null,
    val priority: Int? = null,
    val done: Boolean = false,
    val doneAt: String? = null,
    val homeworkId: Int? = null,
    val remindAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
internal class UnlinkMeRequestDto

@Serializable
internal data class UnlinkMeResponseDto(
    val me: MeDto? = null,
)

@Serializable
internal data class UpdateTaskRequestDto(
    val task: TaskDto? = null,
    val updateMask: String? = null,
)

@Serializable
internal data class UpdateTaskResponseDto(
    val task: TaskDto? = null,
)
