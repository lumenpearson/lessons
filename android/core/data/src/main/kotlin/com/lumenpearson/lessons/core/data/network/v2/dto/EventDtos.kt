package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class CreateEventRequestDto(
    val event: EventDto? = null,
)

@Serializable
internal data class CreateEventResponseDto(
    val event: EventDto? = null,
)

@Serializable
internal data class DeleteEventRequestDto(
    val eventId: Int = 0,
)

@Serializable
internal class DeleteEventResponseDto

@Serializable
internal data class EventDto(
    val id: Int = 0,
    val date: String = "",
    val startsAt: String = "",
    val endsAt: String = "",
    val title: String = "",
    @Serializable(with = EventKindWireSerializer::class)
    val kind: String = "",
    val location: String? = null,
    val coversLesson: Boolean? = null,
)

@Serializable
internal data class GetEventRequestDto(
    val eventId: Int = 0,
)

@Serializable
internal data class GetEventResponseDto(
    val event: EventDto? = null,
)

@Serializable
internal data class ListEventsRequestDto(
    val startDate: String? = null,
    val endDate: String? = null,
)

@Serializable
internal data class ListEventsResponseDto(
    val events: List<EventDto> = emptyList(),
)

@Serializable
internal data class UpdateEventRequestDto(
    val event: EventDto? = null,
    val updateMask: String? = null,
)

@Serializable
internal data class UpdateEventResponseDto(
    val event: EventDto? = null,
)
