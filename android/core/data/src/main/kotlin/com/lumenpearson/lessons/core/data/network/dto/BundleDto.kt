package com.lumenpearson.lessons.core.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The whole offline payload: `GET /api/v1/bundle`.
 *
 * Mirrors `BundleOut` in `server/app/schemas.py`. Every collection has a default
 * so that a server which drops an empty list still decodes, and every date/time
 * stays a [String] here - parsing happens in the mappers, where a malformed
 * value can be dropped instead of failing the entire sync.
 */
@Serializable
internal data class BundleDto(
    @SerialName("api_version") val apiVersion: Int = 0,
    @SerialName("school_class") val schoolClass: SchoolClassDto,
    @SerialName("generated_at") val generatedAt: String = "",
    @SerialName("days") val days: List<DayDto> = emptyList(),
    @SerialName("next_school_day") val nextSchoolDay: DayDto? = null,
)

/** Mirrors `ClassOut`: identity of the class this device joined. */
@Serializable
internal data class SchoolClassDto(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String,
    // 1..11 and the letter beside it. Null on a class made before the server
    // had them, and defaulted here so an older server's payload still parses.
    @SerialName("grade") val grade: Int? = null,
    @SerialName("letter") val letter: String? = null,
    @SerialName("school") val school: String? = null,
    @SerialName("timezone") val timezone: String = "",
    // A free-form wire string for the same reason `DayDto.kind` is one: a
    // server that learns тримест­ры must not break a client that has not.
    @SerialName("term_kind") val termKind: String? = null,
    @SerialName("terms") val terms: List<TermDto> = emptyList(),
)

/**
 * Mirrors `TermOut`: one четверть or полугодие as the class actually runs it.
 *
 * Carried rather than recomputed on the phone: the dates are the school's own
 * and they move — каникулы shift, a region starts its spring break early — so
 * a formula here would be a second answer to a question the server already
 * answers from rows an admin edited.
 */
@Serializable
internal data class TermDto(
    @SerialName("index") val index: Int,
    @SerialName("kind") val kind: String = "quarter",
    @SerialName("starts_on") val startsOn: String,
    @SerialName("ends_on") val endsOn: String,
)

/**
 * Mirrors `DayOut`: one calendar date with everything that happens on it.
 *
 * [kind] is a free-form wire string on purpose - a server that learns a new day
 * kind must not break older clients, so it is resolved through
 * `DayKind.fromWire` rather than a serializable enum.
 */
@Serializable
internal data class DayDto(
    @SerialName("date") val date: String,
    @SerialName("weekday") val weekday: Int = 0,
    @SerialName("kind") val kind: String = "",
    @SerialName("lessons") val lessons: List<LessonDto> = emptyList(),
    @SerialName("events") val events: List<EventDto> = emptyList(),
    @SerialName("homework") val homework: List<HomeworkDto> = emptyList(),
    @SerialName("note") val note: String? = null,
)

/** Mirrors `LessonOut`. Times are `HH:MM:SS` local wall time. */
@Serializable
internal data class LessonDto(
    @SerialName("index") val index: Int = 0,
    @SerialName("subject") val subject: String = "",
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("room") val room: String? = null,
    @SerialName("teacher") val teacher: String? = null,
    @SerialName("color") val color: String? = null,
    @SerialName("is_replaced") val isReplaced: Boolean = false,
    @SerialName("is_cancelled") val isCancelled: Boolean = false,
    @SerialName("note") val note: String? = null,
)

/** Mirrors `EventOut`: anything on the timeline that is not a lesson. */
@Serializable
internal data class EventDto(
    @SerialName("title") val title: String = "",
    @SerialName("kind") val kind: String = "",
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("location") val location: String? = null,
    @SerialName("covers_lesson") val coversLesson: Boolean = false,
)

/** Mirrors `HomeworkOut`. */
@Serializable
internal data class HomeworkDto(
    @SerialName("subject") val subject: String = "",
    @SerialName("text") val text: String = "",
    @SerialName("attachment_url") val attachmentUrl: String? = null,
)
