package com.lumenpearson.lessons.core.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire shapes of `/api/v1/diary`, mirroring every `Diary*` model in
 * `server/app/schemas.py`.
 *
 * Same rules as [BundleDto]: an explicit `@SerialName` on every field so a
 * rename on either side of the repository is a test failure rather than a
 * silently missing value, defaults everywhere a field may be absent, and dates
 * and times left as [String] so that one unreadable value costs one row in the
 * mappers instead of the whole response.
 *
 * Nothing here is an enum. `kind` arrives as a free-form string and is resolved
 * through `DiaryMarkKind.fromWire`, because a diary that learns a sixth kind of
 * register entry must not stop an older build from showing the other five.
 */

/** `POST /login` — used once and never stored; see [DiaryLoginResponseDto]. */
@Serializable
internal data class DiaryLoginRequestDto(
    @SerialName("login") val login: String,
    @SerialName("password") val password: String,
)

/**
 * Mirrors `DiaryLoginOut`.
 *
 * The password is deliberately absent from the answer: the server keeps only
 * the session it opened upstream, which is the whole reason a dead session
 * comes back as `401` + `X-Diary-Reauth: required` rather than being refreshed.
 */
@Serializable
internal data class DiaryLoginResponseDto(
    @SerialName("token") val token: String,
    @SerialName("login") val login: String = "",
)

/** Mirrors `DiaryStudentOut`: one child this account may see. */
@Serializable
internal data class DiaryStudentDto(
    @SerialName("id") val id: Long,
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    @SerialName("middle_name") val middleName: String? = null,
    @SerialName("full_name") val fullName: String = "",
    @SerialName("school") val school: String? = null,
    @SerialName("class_name") val className: String? = null,
)

/**
 * Mirrors `DiaryLessonOut`.
 *
 * [number], [startsAt] and [endsAt] are nullable on the server, so a lesson the
 * upstream published without a bell time still decodes and is still shown —
 * without a time, which is what the diary actually knows about it.
 */
@Serializable
internal data class DiaryLessonDto(
    @SerialName("date") val date: String,
    @SerialName("number") val number: Int? = null,
    @SerialName("subject") val subject: String = "",
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("room") val room: String? = null,
    @SerialName("teacher") val teacher: String? = null,
    @SerialName("homework") val homework: String? = null,
    @SerialName("topic") val topic: String? = null,
)

/** Mirrors `DiaryHomeworkOut`: upstream a field of a lesson, here its own row. */
@Serializable
internal data class DiaryHomeworkDto(
    @SerialName("id") val id: Long? = null,
    @SerialName("due_date") val dueDate: String,
    @SerialName("subject") val subject: String = "",
    @SerialName("text") val text: String = "",
    @SerialName("teacher") val teacher: String? = null,
)

/**
 * Mirrors `DiaryMarkOut`: one cell of the register.
 *
 * [value] is what is written in the cell and [kind] says what it means, so an
 * absence can be drawn as an absence without the client knowing that upstream
 * it was type code 30000.
 */
@Serializable
internal data class DiaryMarkDto(
    @SerialName("id") val id: Long? = null,
    @SerialName("subject_id") val subjectId: Long? = null,
    @SerialName("subject") val subject: String = "",
    @SerialName("date") val date: String? = null,
    @SerialName("value") val value: String = "",
    @SerialName("kind") val kind: String = "",
    @SerialName("reason") val reason: String? = null,
    @SerialName("comment") val comment: String? = null,
)

/** Mirrors `DiaryPeriodOut`: a quarter or a trimester, as the school names it. */
@Serializable
internal data class DiaryPeriodDto(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String = "",
    @SerialName("starts_on") val startsOn: String? = null,
    @SerialName("ends_on") val endsOn: String? = null,
    @SerialName("is_current") val isCurrent: Boolean = false,
)

/** Mirrors `DiarySubjectOut`. */
@Serializable
internal data class DiarySubjectDto(
    @SerialName("id") val id: Long? = null,
    @SerialName("name") val name: String = "",
)

/** Mirrors `DiaryTeacherOut`. */
@Serializable
internal data class DiaryTeacherDto(
    @SerialName("id") val id: Long? = null,
    @SerialName("name") val name: String = "",
    @SerialName("position") val position: String? = null,
    @SerialName("subjects") val subjects: List<String> = emptyList(),
)

/** Mirrors `DiaryAttendanceOut`: one pass through the turnstile. */
@Serializable
internal data class DiaryAttendanceDto(
    @SerialName("at") val at: String,
    @SerialName("direction") val direction: String = "",
)
